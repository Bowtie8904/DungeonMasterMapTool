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

    public static ObjectNode event(String type, String actorId, int fear) {
        return ProfileRoll.event(type, actorId, fear);
    }

    public ObjectNode describe(Pack pack, ObjectNode sheet, ObjectNode event, String surface, String relation) {
        return describe(pack, sheet, event, surface, relation, null);
    }

    public ObjectNode describe(Pack pack, ObjectNode sheet, ObjectNode event, String surface, String relation, PartyTargets party) {
        relation(relation);
        ObjectNode copy = sheet.deepCopy();
        ObjectNode state = projection.state(copy);
        var sources = projection.sources(copy);
        ObjectNode actor = actor(pack, copy, state, event, sources);
        ArrayNode visible = RuleDispatcher.describe(candidates(pack, sources, state, actor, event), surface,
                audience -> allowed(audience, relation), "dm".equals(relation) ? null : party);
        ObjectNode result = RuleJson.JSON.createObjectNode();
        result.put("revision", revision(copy));
        result.set("actions", visible);
        ArrayNode derived = result.putArray("derivedEffects");
        for (JsonNode effect : actor.path("effects"))
            if (effect.path("derived").asBoolean()) derived.add(effect);
        ArrayNode profiles = result.putArray("profiles");
        if (!"self".equals(relation) || !"sheet".equals(surface) || !"action.request".equals(event.path("type").asText()))
            return result;
        boolean hasRollOptions = sources.stream().map(source -> pack.programs().get(source.sourceId()))
                .filter(java.util.Objects::nonNull).anyMatch(program -> program.bindings("roll.prepare", "roll")
                        .stream().anyMatch(binding -> binding.option() && allowed(binding.audience(), relation)));
        for (SheetProjection.SourceElement element : sources) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            for (JsonNode profile : program.profiles()) {
                ObjectNode item = profiles.addObject().put("sourceId", element.sourceId())
                        .put("sourceInstanceId", element.instanceId()).put("sourceName", element.name());
                item.setAll((ObjectNode) profile);
                if (hasRollOptions) {
                    ObjectNode options = item.putObject("options");
                    for (String kind : profile.has("damage") ? List.of("attack", "damage") : List.of("attack")) {
                        ObjectNode facts = event.deepCopy();
                        ProfileRoll.facts(facts, element, profile, kind, actor, equippedWeapon(copy, element));
                        options.set(kind, RuleDispatcher.describeRollOptions(candidates(pack, sources, state, actor, facts),
                                audience -> allowed(audience, relation)));
                    }
                }
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
        return execute(pack, sheet, event, request, relation, principalId, random, null);
    }

    public ObjectNode execute(Pack pack, ObjectNode sheet, ObjectNode event, ObjectNode request, String relation,
                              String principalId, RandomGenerator random, PartyTargets party) {
        relation(relation);
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("Missing automation principal.");
        RuleJson.fields(request, "/request", "requestId", "expectedRevision", "selections", "profileRoll", "target");
        if (request.has("target") && "dm".equals(relation))
            throw new SecurityException("A scoped DM sheet grant cannot target other characters.");
        if (request.has("target") && (request.has("profileRoll") || request.path("selections").size() != 1))
            throw RuleJson.error("/request/target", "Choose a single target action.");
        if (request.has("profileRoll") && !"self".equals(relation))
            throw new SecurityException("Only the character owner may roll an attack profile.");
        String requestId = RuleJson.id(request.get("requestId"), "/request/requestId");
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        for (JsonNode entry : state.path("journal"))
            if (requestId.equals(entry.path("requestId").asText())) {
                if (!principalId.equals(entry.path("principalId").asText()) ||
                        "dm".equals(relation) != entry.path("dmPrincipal").asBoolean(false))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used by another caller.");
                return ((ObjectNode) entry.path("response")).deepCopy();
            }
        long revision = state.path("revision").asLong();
        if (!request.path("expectedRevision").isIntegralNumber() || request.path("expectedRevision").asLong() != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this action.");

        ObjectNode response;
        if (request.has("profileRoll")) {
            response = profileRoll(pack, working, state, event, request, random);
            offerResult(pack, working, response, principalId);
        } else if (request.has("target")) {
            if (party == null) throw RuleJson.error("/request/target", "This action requires a host roster.");
            response = targeted(pack, working, state, event, request, relation, random, party);
        } else {
            var candidates = candidates(pack, working, event);
            var intents = selections(request, candidates, revision, relation, false);
            response = applyEvent(pack, working, state, event, candidates, intents, random);
        }
        response.put("requestId", requestId);
        ArrayNode journal = (ArrayNode) projection.state(working).get("journal");
        ObjectNode receipt = journal.addObject().put("requestId", requestId).put("principalId", principalId);
        if ("dm".equals(relation)) receipt.put("dmPrincipal", true);
        receipt.set("response", response.deepCopy());
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
        replace(sheet, working);
        return response;
    }

    private record TargetAction(List<RuleDispatcher.Candidate> candidates, PartyTargets.Selection selection) {}

    private TargetAction targetAction(Pack pack, ObjectNode sheet, ObjectNode event, ObjectNode request,
                                      String relation, PartyTargets party) {
        var candidates = candidates(pack, sheet, event);
        var intent = selections(request, candidates, revision(sheet), relation, false).getFirst();
        var candidate = candidates.stream().filter(c -> c.instanceId().equals(intent.sourceInstanceId())).findFirst().orElseThrow();
        ObjectNode rule = candidate.program().rule(intent.ruleId());
        JsonNode binding = RuleDispatcher.binding(rule, intent.bindingId());
        if (!binding.has("targetSelection") || !event.path("type").equals(binding.path("event")))
            throw RuleJson.error("/request/target", "Choose a target action for this event.");
        var selection = party.resolve(binding, candidate.context(), intent, request.get("target"));
        return new TargetAction(candidates, selection);
    }

    private ObjectNode targeted(Pack pack, ObjectNode working, ObjectNode state, ObjectNode event, ObjectNode request,
                                String relation, RandomGenerator random, PartyTargets party) {
        long revision = state.path("revision").longValue();
        var action = targetAction(pack, working, event, request, relation, party);
        ObjectNode result = automatic(pack, working, event, random);
        if (!result.path("actions").isEmpty()) {
            state.put("revision", revision);
            event = (ObjectNode) result.required("event");
            action = targetAction(pack, working, event, request, relation, party);
        }
        var targets = action.selection();
        var resolved = RuleDispatcher.executeTargets(action.candidates(), targets.intent(), targets.recipients(), random);
        var targetedActions = resolved.actions().stream().map(RuleDispatcher.TargetActionResult::action).toList();
        ObjectNode after = applyResolved(pack, working, state, event, resolved.sourceContexts(), targetedActions, resolved.outbox());
        for (int i = 0; i < resolved.actions().size(); i++)
            ((ObjectNode) after.path("actions").get(i)).put("recipientId", resolved.actions().get(i).recipientId());
        for (String key : List.of("actions", "outbox")) ((ArrayNode) result.get(key)).addAll((ArrayNode) after.get(key));
        result.set("event", after.get("event"));
        party.stage(resolved.recipients());
        return result.put("revision", revision + 1);
    }

    private static List<RuleRuntime.Intent> selections(ObjectNode request, List<RuleDispatcher.Candidate> candidates,
                                                        long revision, String relation, boolean rollOptions) {
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
            if (rollOptions && (!"option".equals(binding.path("mode").asText()) || !"roll".equals(binding.path("surface").asText())))
                throw RuleJson.error(at + "/bindingId", "Only roll options may accompany a profile roll.");
            if (!allowed(binding.path("audience").asText("self"), relation))
                throw new SecurityException("This action is not available to you.");
            ObjectNode inputs = selection.has("inputs") ? RuleJson.object(selection.get("inputs"), at + "/inputs") : RuleJson.JSON.createObjectNode();
            String eachId = selection.has("eachId") ? RuleJson.id(selection.get("eachId"), at + "/eachId") : null;
            intents.add(new RuleRuntime.Intent(ruleId, bindingId, instanceId, revision, inputs, eachId));
        }

        return intents;
    }

    private ObjectNode profileRoll(Pack pack, ObjectNode working, ObjectNode state, ObjectNode origin,
                                   ObjectNode request, RandomGenerator random) {
        JsonNode intent = request.get("profileRoll");
        RuleJson.fields(intent, "/request/profileRoll", "sourceInstanceId", "profileId", "kind");
        String instance = RuleJson.id(intent.get("sourceInstanceId"), "/request/profileRoll/sourceInstanceId");
        String profileId = RuleJson.id(intent.get("profileId"), "/request/profileRoll/profileId");
        String kind = RuleJson.text(intent.get("kind"), "/request/profileRoll/kind");
        var sources = projection.sources(working);
        var source = sources.stream().filter(item -> instance.equals(item.instanceId())).findFirst()
                .orElseThrow(() -> new RuleRuntime.Unavailable("source", "This source is no longer active."));
        var program = pack.programs().get(source.sourceId());
        JsonNode profile = null;
        if (program != null) for (JsonNode candidate : program.profiles())
            if (profileId.equals(candidate.path("id").asText())) profile = candidate;
        if (profile == null) throw new RuleRuntime.Unavailable("profile", "This profile is no longer available.");

        long revision = state.path("revision").longValue();
        ObjectNode event = origin.deepCopy();
        ObjectNode actor = actor(pack, working, state, event, sources);
        ProfileRoll.prepare(event, source, profile, kind, actor, equippedWeapon(working, source), random);
        var candidates = candidates(pack, sources, state, actor, event);
        var intents = selections(request, candidates, revision, "self", true);
        ObjectNode result = applyEvent(pack, working, state, event, candidates, intents, random);
        ObjectNode finished = (ObjectNode) result.get("event");
        if (finished.path("blocked").size() > 0)
            throw new RuleRuntime.Unavailable("blocked", finished.at("/blocked/0/reason").asText());
        if (!finished.path("roll").equals(event.path("roll"))) ProfileRoll.updateCheck(finished);
        finished.put("type", "roll.finished");
        ObjectNode after = automatic(pack, working, finished, random);
        ObjectNode finalEvent = (ObjectNode) after.get("event");
        if (!finalEvent.path("roll").equals(finished.path("roll"))) ProfileRoll.updateCheck(finalEvent);
        result.set("event", finalEvent);
        for (String key : List.of("actions", "outbox"))
            ((ArrayNode) result.get(key)).addAll((ArrayNode) after.get(key));
        projection.state(working).put("revision", revision + 1);
        return result.put("revision", revision + 1);
    }

    private static boolean equippedWeapon(ObjectNode sheet, SheetProjection.SourceElement source) {
        for (JsonNode item : sheet.path("activeWeapons")) if (item == source.element()) return true;
        return false;
    }

    private void offerResult(Pack pack, ObjectNode sheet, ObjectNode response, String principal) {
        ObjectNode event = (ObjectNode) response.required("event");
        var sources = projection.sources(sheet);
        if (sources.stream().noneMatch(element -> {
            var program = pack.programs().get(element.sourceId());
            return program != null && !program.resultButtons().isEmpty();
        })) return;
        if (sources.stream().noneMatch(source -> source.instanceId().equals(event.path("sourceInstanceId").textValue())))
            return; // Consuming the roll's source cannot leave an actionable result behind.
        ObjectNode state = projection.state(sheet);
        ArrayNode matches = resultButtons(pack, sources, state, actor(pack, sheet, state, event, sources), event, null);
        String id = ResultWindows.publish(state, pack.revision(), principal, event, matches, ResultWindows.guards(sources, state.path("effects")));
        if (id != null) {
            event.put("resultId", id);
            response.set("resultActions", matches);
        }
    }

    private ArrayNode resultButtons(Pack pack, List<SheetProjection.SourceElement> sources, ObjectNode state,
                                    ObjectNode actor, ObjectNode event, Set<ResultWindows.Button> retained) {
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (var element : sources) {
            var program = pack.programs().get(element.sourceId());
            if (program == null || program.resultButtons().isEmpty()) continue;
            var context = context(state, element, actor, event);
            for (var binding : program.resultButtons()) {
                if (!allowed(binding.audience(), "self")) continue;
                Iterable<JsonNode> options = binding.forEach()
                        ? RuleRuntime.describeEach(program, binding.ruleId(), binding.bindingId(), context)
                        : List.of(RuleRuntime.describe(program, binding.ruleId(), binding.bindingId(), context));
                for (JsonNode item : options) {
                    if ("condition".equals(item.path("reasonCode").asText())) continue;
                    if (retained == null || retained.contains(ResultWindows.Button.of(item))) result.add(item);
                }
            }
        }
        return result;
    }

    public ObjectNode describeResult(Pack pack, ObjectNode sheet, String id, String relation, String principal, ObjectNode origin) {
        if (!"self".equals(relation)) throw new SecurityException("Only the character owner may use this roll result.");
        ObjectNode copy = sheet.deepCopy(), state = projection.state(copy);
        var sources = projection.sources(copy);
        ResultWindows.prune(state, ResultWindows.guards(sources, state.path("effects")));
        var window = ResultWindows.lookup(state, pack.revision(), principal, id);
        Set<ResultWindows.Button> retained = new java.util.HashSet<>();
        window.required("buttons").forEach(item -> retained.add(ResultWindows.Button.of(item)));
        ObjectNode event = savedResultEvent(window, origin);
        return RuleJson.JSON.createObjectNode().put("revision", state.path("revision").asLong())
                .set("actions", resultButtons(pack, sources, state, actor(pack, copy, state, event, sources), event, retained));
    }

    private static ObjectNode savedResultEvent(ObjectNode window, ObjectNode origin) {
        ObjectNode event = ((ObjectNode) window.required("event")).deepCopy();
        if (!origin.path("actorId").equals(event.path("actorId"))
                || !origin.path("sessionId").equals(event.path("sessionId"))) throw ResultWindows.stale();
        event.set("table", origin.required("table").deepCopy());
        return event;
    }

    /** Do not let removing and later re-equipping a source revive its old actions. */
    public void pruneResultWindows(ObjectNode sheet) {
        if (!(sheet.path(projection.stateField()) instanceof ObjectNode state) || !state.has("resultWindows")) return;
        ResultWindows.prune(state, ResultWindows.guards(projection.sources(sheet), state.path("effects")));
    }

    /** Dispatch a host transition in the caller's staged sheet transaction, without creating a player retry record. */
    public ObjectNode automatic(Pack pack, ObjectNode working, ObjectNode event, RandomGenerator random) {
        var sources = projection.sources(working);
        String type = RuleJson.text(event.path("type"), "/event/type");
        if (sources.stream().noneMatch(source -> {
            var program = pack.programs().get(source.sourceId());
            return program != null && !program.automatic(type).isEmpty();
        })) {
            ObjectNode response = RuleJson.JSON.createObjectNode().put("revision", working.path(projection.stateField()).path("revision").asLong());
            response.putArray("actions");
            response.putArray("outbox");
            response.set("event", event.deepCopy());
            return response;
        }
        return applyEvent(pack, working, projection.state(working), event, candidates(pack, working, event), List.of(), random);
    }

    private ObjectNode applyEvent(Pack pack, ObjectNode working, ObjectNode state, ObjectNode event,
                                  List<RuleDispatcher.Candidate> candidates, List<RuleRuntime.Intent> intents, RandomGenerator random) {
        RuleDispatcher.EventResolution resolution = RuleDispatcher.executeEvent(candidates, intents, random);
        return applyResolved(pack, working, state, event, resolution.contexts(), resolution.actions(), resolution.outbox());
    }

    private ObjectNode applyResolved(Pack pack, ObjectNode working, ObjectNode state, ObjectNode event,
                                     List<ObjectNode> contexts, List<RuleDispatcher.ActionResult> results, List<ObjectNode> commands) {
        long revision = state.path("revision").asLong();
        ObjectNode before = projection.actor(working);
        ArrayNode outbox = RuleJson.JSON.createArrayNode();
        commands.forEach(outbox::add);
        ObjectNode actor = before;
        Map<String, SheetProjection.SourceElement> elements = new HashMap<>();
        for (SheetProjection.SourceElement element : projection.sources(working)) elements.put(element.instanceId(), element);
        List<SheetProjection.SourceElement> emptied = new ArrayList<>();
        JsonNode finalEvent = contexts.isEmpty() ? event.deepCopy() : contexts.getFirst().path("event");
        for (ObjectNode context : contexts) {
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
        if (!results.isEmpty()) projection.write(working, before, persisted(actor));
        for (SheetProjection.SourceElement element : emptied) remove(working, element);

        ObjectNode response = RuleJson.JSON.createObjectNode();
        long next = results.isEmpty() ? revision : revision + 1;
        response.put("revision", next);
        ArrayNode actions = response.putArray("actions");
        for (RuleDispatcher.ActionResult result : results) {
            ObjectNode item = actions.addObject();
            item.put("sourceInstanceId", result.sourceInstanceId()).put("ruleId", result.ruleId())
                    .put("bindingId", result.bindingId()).put("report", result.report());
            ArrayNode published = item.putArray("publishedRolls");
            result.publishedRolls().forEach(published::add);
            ArrayNode dice = item.putArray("diceRolls");
            result.diceRolls().forEach(dice::add);
        }
        response.set("outbox", outbox);
        response.set("event", finalEvent);
        state = projection.state(working);
        state.put("revision", next);
        return response;
    }

    /** Applies limit and pool resets for a lifecycle event. Returns whether anything changed. */
    public boolean resetLimits(Pack pack, ObjectNode sheet, String event) {
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode actor = actor(pack, working, state, RuleJson.JSON.createObjectNode().put("type", event));
        boolean changed = "session.start".equals(event) && state.remove("resultWindows") != null;
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
        if (actor.path("effects").isArray()) {
            ArrayNode kept = RuleEffects.expire(actor.path("effects"), event);
            if (kept.size() != actor.path("effects").size()) {
                Set<String> losingPools = boundPools(pack, actor.path("effects"));
                losingPools.removeAll(boundPools(pack, kept));
                for (String id : losingPools) {
                    JsonNode pool = actor.path("pools").get(id);
                    if (pool != null) RuleJson.object(pool, "/actor/pools/" + id).put("value", 0);
                }
                actor.set("effects", kept);
                changed = true;
            }
        }
        if (!changed) return false;
        projection.write(working, projection.actor(working), persisted(actor));
        state = projection.state(working);
        state.put("revision", state.path("revision").asLong() + 1);
        replace(sheet, working);
        return true;
    }

    private static Set<String> boundPools(Pack pack, JsonNode effects) {
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode instance : effects) {
            String key = instance.path("effect").asText();
            int split = key.lastIndexOf('#');
            if (split < 1) continue;
            var program = pack.programs().get(key.substring(0, split));
            if (program != null) ids.addAll(program.poolsBoundTo(key.substring(split + 1)));
        }
        return ids;
    }

    private List<RuleDispatcher.Candidate> candidates(Pack pack, ObjectNode sheet, ObjectNode event) {
        ObjectNode state = projection.state(sheet);
        var sources = projection.sources(sheet);
        return candidates(pack, sources, state, actor(pack, sheet, state, event, sources), event);
    }

    private List<RuleDispatcher.Candidate> candidates(Pack pack, List<SheetProjection.SourceElement> sources,
                                                       ObjectNode state, ObjectNode actor, ObjectNode event) {
        RuleJson.text(RuleJson.object(event, "/event").get("type"), "/event/type");
        List<RuleDispatcher.Candidate> result = new ArrayList<>();
        for (SheetProjection.SourceElement element : sources) {
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
        return actor(pack, sheet, state, event, projection.sources(sheet));
    }

    private ObjectNode actor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                             List<SheetProjection.SourceElement> sources) {
        ObjectNode actor = projection.actor(sheet);
        if (event.hasNonNull("actorId")) actor.set("id", event.get("actorId"));
        ObjectNode base = actor.deepCopy();
        base.set("stats", projection.stats(actor, List.of()));
        List<JsonNode> modifiers = new ArrayList<>();
        ObjectNode limitOverrides = RuleJson.JSON.createObjectNode();
        ArrayNode derived = RuleJson.JSON.createArrayNode();
        for (SheetProjection.SourceElement element : sources) {
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

    public ObjectNode recipientSnapshot(Pack pack, ObjectNode sheet, ObjectNode event, String recipientId) {
        return actor(pack, sheet, projection.state(sheet), event.deepCopy().put("actorId", recipientId));
    }

    public void writeRecipient(ObjectNode stagedSheet, RuleDispatcher.RecipientUpdate update) {
        if (revision(stagedSheet) + 1 != update.revision())
            throw new RuleRuntime.Unavailable("staleRecipient", "The recipient changed. Refresh the action.");
        projection.write(stagedSheet, projection.actor(stagedSheet), persisted(update.snapshot()));
        projection.state(stagedSheet).put("revision", update.revision());
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
