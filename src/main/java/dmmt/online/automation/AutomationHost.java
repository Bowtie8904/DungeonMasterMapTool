package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Connects the generic rule engine to stored sheets through a {@link SheetProjection}. All game behaviour comes from
 * compiled catalog JSON; this class only selects active sources, checks audiences and revisions, and writes results back.
 */
public final class AutomationHost {
    public static final int JOURNAL_LIMIT = 32;
    private static final Set<String> RELATIONS = Set.of("self", "other", "dm");

    /** Compiled automation keyed by fully qualified source ID. */
    public record Pack(String revision, Map<String, RuleCompiler.Program> programs) {
        public Pack { programs = Map.copyOf(programs); }
    }

    /** Raised when the configured catalogs cannot drive automation (for example a pack that is not format 2). */
    public static final class NotReady extends IllegalStateException {
        public NotReady(String message) { super(message); }
    }

    private final SheetProjection projection;

    public AutomationHost(SheetProjection projection) {
        this.projection = projection;
    }

    public static AutomationHost bundled() {
        try (var input = AutomationHost.class.getResourceAsStream("/dmmt/online/automation/daggerheart-projection.json")) {
            if (input == null) throw new IllegalStateException("The bundled sheet projection is missing.");
            return new AutomationHost(SheetProjection.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new IllegalStateException("The bundled sheet projection is invalid.", ex);
        }
    }

    public SheetProjection projection() { return projection; }

    /** Compiles every catalog that is present. Any catalog that is not format 2 makes the pack unavailable. */
    public static Pack compile(String revision, Map<String, String> catalogs, String recipes) {
        if (recipes == null) throw new NotReady("The compendium has no rules.json recipe document.");
        Map<String, RuleCompiler.Program> programs = new LinkedHashMap<>();
        for (var entry : catalogs.entrySet()) {
            try {
                for (RuleCatalog.Source source : RuleCatalog.compile(entry.getValue(), recipes))
                    if (programs.put(source.id(), source.program()) != null)
                        throw new NotReady("Duplicate automation source " + source.id() + ".");
            } catch (IOException | IllegalArgumentException ex) {
                throw new NotReady(entry.getKey() + ": " + ex.getMessage());
            }
        }
        return new Pack(revision, programs);
    }

    public long revision(ObjectNode sheet) {
        return projection.state(sheet).path("revision").asLong();
    }

    public ObjectNode describe(Pack pack, ObjectNode sheet, ObjectNode event, String surface, String relation) {
        relation(relation);
        ObjectNode copy = sheet.deepCopy();
        ArrayNode all = RuleDispatcher.describe(candidates(pack, copy, event), surface);
        ArrayNode visible = RuleJson.JSON.createArrayNode();
        for (JsonNode item : all) if (allowed(item.path("audience").asText("self"), relation)) visible.add(item);
        ObjectNode result = RuleJson.JSON.createObjectNode();
        result.put("revision", revision(copy));
        result.set("actions", visible);
        ArrayNode derived = result.putArray("derivedEffects");
        for (JsonNode effect : actor(pack, copy, projection.state(copy), event).path("effects"))
            if (effect.path("derived").asBoolean()) derived.add(effect);
        ArrayNode profiles = result.putArray("profiles");
        for (SheetProjection.SourceElement element : projection.sources(copy)) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            for (JsonNode profile : program.profiles()) {
                ObjectNode item = profiles.addObject().put("sourceId", element.sourceId())
                        .put("sourceInstanceId", element.instanceId()).put("sourceName", element.name());
                item.setAll((ObjectNode) profile);
            }
        }
        return result;
    }

    /**
     * Executes selected bindings plus automatic reactions for {@code event}. Repeated request IDs return the journaled
     * response without running again. The sheet is changed in place only on success.
     */
    public ObjectNode execute(Pack pack, ObjectNode sheet, ObjectNode event, ObjectNode request, String relation,
                              RandomGenerator random) {
        return execute(pack, sheet, event, request, relation, "local", random);
    }

    public ObjectNode execute(Pack pack, ObjectNode sheet, ObjectNode event, ObjectNode request, String relation,
                              String principalId, RandomGenerator random) {
        relation(relation);
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("Missing automation principal.");
        RuleJson.fields(request, "/request", "requestId", "expectedRevision", "selections");
        String requestId = RuleJson.id(request.get("requestId"), "/request/requestId");
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        for (JsonNode entry : state.path("journal"))
            if (requestId.equals(entry.path("requestId").asText())) {
                if (!principalId.equals(entry.path("principalId").asText()))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used by another caller.");
                return ((ObjectNode) entry.path("response")).deepCopy();
            }
        long revision = state.path("revision").asLong();
        if (!request.path("expectedRevision").isIntegralNumber() || request.path("expectedRevision").asLong() != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this action.");

        List<RuleDispatcher.Candidate> candidates = candidates(pack, working, event);
        Map<String, RuleDispatcher.Candidate> byInstance = new HashMap<>();
        for (RuleDispatcher.Candidate candidate : candidates)
            byInstance.put(candidate.context().path("source").path("instanceId").asText(), candidate);
        List<RuleRuntime.Intent> intents = new ArrayList<>();
        JsonNode selections = request.path("selections");
        if (!selections.isArray() || selections.size() > 32) throw RuleJson.error("/request/selections", "Expected at most 32 selections.");
        for (int i = 0; i < selections.size(); i++) {
            JsonNode selection = selections.get(i);
            String at = "/request/selections/" + i;
            RuleJson.fields(selection, at, "sourceInstanceId", "ruleId", "bindingId", "eachId", "inputs");
            String instanceId = RuleJson.id(selection.get("sourceInstanceId"), at + "/sourceInstanceId");
            RuleDispatcher.Candidate candidate = byInstance.get(instanceId);
            if (candidate == null) throw new RuleRuntime.Unavailable("source", "This source is no longer active.");
            String ruleId = RuleJson.text(selection.get("ruleId"), at + "/ruleId");
            String bindingId = RuleJson.text(selection.get("bindingId"), at + "/bindingId");
            JsonNode binding = null;
            for (JsonNode item : candidate.program().rule(ruleId).path("bindings"))
                if (bindingId.equals(item.path("id").asText())) binding = item;
            if (binding == null) throw RuleJson.error(at + "/bindingId", "Unknown binding.");
            if (!allowed(binding.path("audience").asText("self"), relation))
                throw new SecurityException("This action is not available to you.");
            ObjectNode inputs = selection.has("inputs") ? RuleJson.object(selection.get("inputs"), at + "/inputs") : RuleJson.JSON.createObjectNode();
            String eachId = selection.has("eachId") ? RuleJson.id(selection.get("eachId"), at + "/eachId") : null;
            intents.add(new RuleRuntime.Intent(ruleId, bindingId, instanceId, revision, inputs, eachId));
        }

        ObjectNode before = projection.actor(working);
        RuleDispatcher.EventResolution resolution = RuleDispatcher.executeEvent(candidates, intents, random);
        ArrayNode outbox = RuleJson.JSON.createArrayNode();
        resolution.outbox().forEach(outbox::add);
        ObjectNode actor = before;
        Map<String, SheetProjection.SourceElement> elements = new HashMap<>();
        for (SheetProjection.SourceElement element : projection.sources(working)) elements.put(element.instanceId(), element);
        List<SheetProjection.SourceElement> emptied = new ArrayList<>();
        for (ObjectNode context : resolution.contexts()) {
            actor = (ObjectNode) context.path("actor");
            String instanceId = context.path("source").path("instanceId").asText();
            storeSource(state, instanceId, context);
            SheetProjection.SourceElement element = elements.get(instanceId);
            if (element != null && element.quantityField() != null && context.path("source").has("quantity")) {
                int quantity = context.path("source").path("quantity").asInt();
                if (quantity <= 0) emptied.add(element);
                else element.element().put(element.quantityField(), quantity);
            }
        }
        for (JsonNode refresh : outbox) {
            if (!"limits.refresh".equals(refresh.path("kind").asText())) continue;
            Set<String> events = new java.util.HashSet<>();
            refresh.path("on").forEach(item -> events.add(item.asText()));
            for (SheetProjection.SourceElement element : elements.values()) {
                if (element.instanceId().equals(refresh.path("sourceInstanceId").asText())) continue;
                RuleCompiler.Program program = pack.programs().get(element.sourceId());
                if (program != null) storeSource(state, element.instanceId(), RuleRuntime.refreshLimits(program, context(state, element, actor, event), events));
            }
        }
        if (!resolution.actions().isEmpty()) projection.write(working, before, persisted(actor));
        for (SheetProjection.SourceElement element : emptied) remove(working, element);

        ObjectNode response = RuleJson.JSON.createObjectNode();
        response.put("requestId", requestId);
        long next = resolution.actions().isEmpty() ? revision : revision + 1;
        response.put("revision", next);
        ArrayNode actions = response.putArray("actions");
        for (RuleDispatcher.ActionResult result : resolution.actions()) {
            ObjectNode item = actions.addObject();
            item.put("sourceInstanceId", result.sourceInstanceId()).put("ruleId", result.ruleId())
                    .put("bindingId", result.bindingId()).put("report", result.report());
            ArrayNode published = item.putArray("publishedRolls");
            result.publishedRolls().forEach(published::add);
            ArrayNode dice = item.putArray("diceRolls");
            result.diceRolls().forEach(dice::add);
        }
        response.set("outbox", outbox);
        response.set("event", resolution.contexts().isEmpty() ? event.deepCopy() : resolution.contexts().getFirst().path("event").deepCopy());
        state = projection.state(working);
        state.put("revision", next);
        ArrayNode journal = (ArrayNode) state.get("journal");
        journal.addObject().put("requestId", requestId).put("principalId", principalId).set("response", response.deepCopy());
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
        replace(sheet, working);
        return response;
    }

    /** Applies limit and pool resets for a lifecycle event. Returns whether anything changed. */
    public boolean resetLimits(Pack pack, ObjectNode sheet, String event) {
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode actor = actor(pack, working, state, RuleJson.JSON.createObjectNode().put("type", event));
        boolean changed = false;
        for (SheetProjection.SourceElement element : projection.sources(working)) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            ObjectNode context = context(state, element, actor, RuleJson.JSON.createObjectNode().put("type", event));
            ObjectNode next = RuleRuntime.resetLimits(program, context, event);
            if (next.path("revision").asLong() != context.path("revision").asLong()) {
                changed = true;
                storeSource(state, element.instanceId(), next);
                actor = (ObjectNode) next.path("actor");
            }
        }
        if (!changed) return false;
        projection.write(working, projection.actor(working), persisted(actor));
        state = projection.state(working);
        state.put("revision", state.path("revision").asLong() + 1);
        replace(sheet, working);
        return true;
    }

    private List<RuleDispatcher.Candidate> candidates(Pack pack, ObjectNode sheet, ObjectNode event) {
        RuleJson.text(RuleJson.object(event, "/event").get("type"), "/event/type");
        ObjectNode state = projection.state(sheet);
        ObjectNode actor = actor(pack, sheet, state, event);
        List<RuleDispatcher.Candidate> result = new ArrayList<>();
        for (SheetProjection.SourceElement element : projection.sources(sheet)) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program != null) result.add(new RuleDispatcher.Candidate(program, context(state, element, actor, event)));
        }
        return result;
    }

    /**
     * Projects the actor and attaches {@code stats}. Passive modifiers are evaluated against the unmodified statistics
     * so a modifier can never depend on itself; active effect instances contribute their frozen modifiers.
     */
    ObjectNode actor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event) {
        ObjectNode actor = projection.actor(sheet);
        ObjectNode base = actor.deepCopy();
        base.set("stats", projection.stats(actor, List.of()));
        List<JsonNode> modifiers = new ArrayList<>();
        ObjectNode limitOverrides = RuleJson.JSON.createObjectNode();
        ArrayNode derived = RuleJson.JSON.createArrayNode();
        for (SheetProjection.SourceElement element : projection.sources(sheet)) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            for (JsonNode override : program.limitOverrides()) {
                String key = override.path("feature").asText() + ":" + override.path("limit").asText();
                limitOverrides.put(key, Math.max(limitOverrides.path(key).asInt(), override.path("max").asInt()));
            }
            if (!program.modifiers().isEmpty()) RuleEffects.passives(program, context(state, element, base, event)).forEach(modifiers::add);
            derived.addAll(RuleEffects.derived(program, context(state, element, base, event)));
        }
        if (!derived.isEmpty()) {
            ArrayNode effects = actor.path("effects").isArray() ? (ArrayNode) actor.get("effects") : actor.putArray("effects");
            effects.addAll(derived);
        }
        if (actor.path("effects").isArray()) RuleEffects.modifiers(actor.get("effects")).forEach(modifiers::add);
        actor.set("stats", projection.stats(actor, modifiers));
        if (!limitOverrides.isEmpty()) actor.set("limitOverrides", limitOverrides);
        return actor;
    }

    /** The actor without derived effect instances, which are recomputed on every projection and never stored. */
    private static ObjectNode persisted(ObjectNode actor) {
        if (!actor.path("effects").isArray()) return actor;
        ObjectNode copy = actor.deepCopy();
        copy.set("effects", RuleEffects.stored(actor.get("effects")));
        return copy;
    }

    private static ObjectNode context(ObjectNode state, SheetProjection.SourceElement element, ObjectNode actor, ObjectNode event) {
        ObjectNode context = RuleJson.JSON.createObjectNode();
        ObjectNode source = context.putObject("source");
        source.put("id", element.sourceId()).put("instanceId", element.instanceId()).put("name", element.name());
        if (element.quantityField() != null) source.put("quantity", element.element().path(element.quantityField()).asInt(1));
        context.put("revision", state.path("revision").asLong());
        context.set("actor", actor.deepCopy());
        context.set("event", event.deepCopy());
        JsonNode stored = state.path("sources").path(element.instanceId());
        for (String field : List.of("state", "usage", "sourcePools"))
            if (stored.path(field).isObject()) context.set(field, stored.get(field).deepCopy());
        return context;
    }

    private static void storeSource(ObjectNode state, String instanceId, ObjectNode context) {
        ObjectNode stored = ((ObjectNode) state.get("sources")).putObject(instanceId);
        for (String field : List.of("state", "usage", "sourcePools"))
            if (context.path(field).isObject() && !context.get(field).isEmpty()) stored.set(field, context.get(field).deepCopy());
        if (stored.isEmpty()) ((ObjectNode) state.get("sources")).remove(instanceId);
    }

    private void remove(ObjectNode sheet, SheetProjection.SourceElement element) {
        for (String location : projection.sourcePaths()) {
            JsonNode list = SheetProjection.get(sheet, location);
            if (!list.isArray()) continue;
            for (int i = 0; i < list.size(); i++) if (list.get(i) == element.element()) { ((ArrayNode) list).remove(i); break; }
        }
        ((ObjectNode) projection.state(sheet).get("sources")).remove(element.instanceId());
    }

    private static void replace(ObjectNode target, ObjectNode source) {
        target.removeAll();
        target.setAll(source);
    }

    private static void relation(String relation) {
        if (!RELATIONS.contains(relation)) throw RuleJson.error("/relation", "Unknown viewer relation.");
    }

    /** self: the owning player; others: other players; party: any player; any: players and the DM. */
    static boolean allowed(String audience, String relation) {
        return switch (audience) {
            case "self" -> "self".equals(relation);
            case "others" -> "other".equals(relation);
            case "party" -> !"dm".equals(relation);
            case "any" -> true;
            default -> false;
        };
    }
}
