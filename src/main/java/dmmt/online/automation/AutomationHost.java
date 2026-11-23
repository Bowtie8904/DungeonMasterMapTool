package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.CampaignStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final String EFFECT_PROFILE = "effect.profile";
    static final String RULESET_SOURCE = "ruleset:base";

    /** Compiled automation keyed by fully qualified source ID. */
    public record RestLimits(int moves, int maxMoves, int maxLongMoves, int consecutiveLimit) {}
    public record RestConfiguration(RestLimits shortRest, RestLimits longRest) {}

    public record Pack(String revision, Map<String, RuleCompiler.Program> programs, String rulesetName,
                       RestConfiguration rest) {
        public Pack { programs = Map.copyOf(programs); }
        public Pack(String revision, Map<String, RuleCompiler.Program> programs, String rulesetName) {
            this(revision, programs, rulesetName, null);
        }
        public Pack(String revision, Map<String, RuleCompiler.Program> programs) { this(revision, programs, null, null); }
    }

    public record SingleHolderEffect(String sourceActorId, String sourceInstanceId, String effect, String holder) {}

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
        return compile(revision, catalogs, recipes, null);
    }

    /** Compiles the optional always-active ruleset with the same recipes as the catalogs. */
    public static Pack compile(String revision, Map<String, String> catalogs, String recipes, String ruleset) {
        if (recipes == null) throw new NotReady("The compendium has no recipes.json (or legacy rules.json) recipe document.");
        Map<String, RuleCompiler.Program> programs = new LinkedHashMap<>();
        Map<String, ObjectNode> compiledRecipes;
        try {
            compiledRecipes = RuleCatalog.recipes(recipes);
        } catch (IOException | IllegalArgumentException ex) {
            throw new NotReady("recipes.json (or legacy rules.json): " + ex.getMessage());
        }
        for (var entry : catalogs.entrySet()) {
            try {
                for (RuleCatalog.Source source : RuleCatalog.compileNamed(entry.getKey(), entry.getValue(), compiledRecipes))
                    if (programs.put(source.id(), source.program()) != null)
                        throw new NotReady("Duplicate automation source " + source.id() + ".");
            } catch (IOException | IllegalArgumentException ex) {
                throw new NotReady(entry.getKey() + ": " + ex.getMessage());
            }
        }
        String rulesetName = null;
        RestConfiguration rest = null;
        if (ruleset != null) {
            try {
                ObjectNode document = RuleCompiler.parseDocument(ruleset, "dmmap-daggerheart-ruleset");
                RuleJson.fields(document, "/", "format", "version", "name", "automation", "rest");
                rulesetName = RuleJson.text(document.get("name"), "/name");
                if (document.has("rest")) rest = restConfiguration(document.get("rest"));
                RuleCompiler.Program program =
                        RuleCompiler.compileWithRecipes(RULESET_SOURCE, document.get("automation"), compiledRecipes);
                if (programs.putIfAbsent(RULESET_SOURCE, program) != null)
                    throw new NotReady("The shared ruleset source identity is reserved.");
            } catch (IOException | IllegalArgumentException ex) {
                throw new NotReady("ruleset.json: " + ex.getMessage());
            }
        }
        return new Pack(revision, programs, rulesetName, rest);
    }

    private static RestConfiguration restConfiguration(JsonNode rest) {
        RuleJson.fields(rest, "/rest", "short", "long");
        return new RestConfiguration(restLimits(rest.path("short"), "/rest/short", true),
                restLimits(rest.path("long"), "/rest/long", false));
    }

    private static RestLimits restLimits(JsonNode value, String path, boolean shortRest) {
        if (shortRest) RuleJson.fields(value, path, "moves", "maxMoves", "maxLongMoves", "consecutiveLimit");
        else RuleJson.fields(value, path, "moves", "maxMoves");
        int maxMoves = RuleJson.integer(value.get("maxMoves"), path + "/maxMoves", 0, 32);
        int moves = RuleJson.integer(value.get("moves"), path + "/moves", 0, maxMoves);
        int maxLongMoves = shortRest ? RuleJson.integer(value.get("maxLongMoves"), path + "/maxLongMoves", 0, 32) : 0;
        int consecutiveLimit = shortRest ? RuleJson.integer(value.get("consecutiveLimit"), path + "/consecutiveLimit", 1, 1000) : 0;
        return new RestLimits(moves, maxMoves, maxLongMoves, consecutiveLimit);
    }

    /** Active sheet sources plus the pack's reserved shared source, if present. */
    public List<SheetProjection.SourceElement> sources(Pack pack, ObjectNode sheet) {
        List<SheetProjection.SourceElement> sources = projection.sources(sheet);
        if (pack.rulesetName() == null) return sources;
        for (var source : sources)
            if (RULESET_SOURCE.equals(source.instanceId()) || RULESET_SOURCE.equals(source.sourceId()))
                throw RuleJson.error("/sources", "The shared ruleset source identity is reserved.");
        sources.add(new SheetProjection.SourceElement(RULESET_SOURCE, RULESET_SOURCE, pack.rulesetName(),
                RuleJson.JSON.createObjectNode().put("name", pack.rulesetName()), null));
        return sources;
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
        var sources = sources(pack, copy);
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
            ArrayNode declared = program == null ? RuleJson.JSON.createArrayNode() : program.profiles();
            ArrayNode available = declared.deepCopy();
            if (equippedWeapon(copy, element)) available.insert(0, ProfileRoll.weaponProfile(element, declared));
            for (JsonNode profile : available)
                describeProfile(profiles, pack, sources, state, actor, event, element, profile, null,
                        equippedWeapon(copy, element), hasRollOptions, relation);
        }
        Map<String, SheetProjection.SourceElement> active = new HashMap<>();
        sources.forEach(source -> active.put(source.instanceId(), source));
        for (JsonNode effect : actor.path("effects")) {
            var source = active.get(effect.path("sourceInstanceId").textValue());
            if (source == null) continue;
            ObjectNode profile = effectProfile(pack, source, effect, actor.path("id").textValue());
            if (profile != null)
                describeProfile(profiles, pack, sources, state, actor, event, source, profile,
                        RuleJson.text(effect.get("id"), "/effects/id"), false, hasRollOptions, relation);
        }
        return result;
    }

    private void describeProfile(ArrayNode profiles, Pack pack, List<SheetProjection.SourceElement> sources,
                                 ObjectNode state, ObjectNode actor, ObjectNode event, SheetProjection.SourceElement source,
                                 JsonNode profile, String effectId, boolean weapon, boolean hasRollOptions, String relation) {
        ObjectNode item = profiles.addObject().put("sourceId", source.sourceId())
                .put("sourceInstanceId", source.instanceId()).put("sourceName", source.name());
        item.setAll((ObjectNode) profile);
        if (effectId != null) item.put("effectInstanceId", effectId);
        if ("spellcast".equals(profile.path("trait").asText()) && !actor.path("spellcastTrait").path("name").isTextual())
            item.put("available", false).put("reason", "Choose a Spellcast trait before making this roll.");
        if (profile.has("limitRef")) {
            RuleCompiler.Program program = pack.programs().get(source.sourceId());
            ObjectNode context = RuleRuntime.context(program, null, context(state, source, actor, event));
            try {
                RuleRuntime.reserveUse(program, context, profile.path("limitRef").asText());
            } catch (RuleRuntime.Unavailable unavailable) {
                item.put("available", false).put("reason", unavailable.getMessage());
            }
        }
        if (!hasRollOptions) return;
        ObjectNode options = item.putObject("options");
        for (String kind : ProfileRoll.kinds(profile)) {
            ObjectNode facts = event.deepCopy();
            if (effectId != null) facts.put("effectInstanceId", effectId);
            ProfileRoll.facts(facts, source, profile, kind, actor, weapon);
            options.set(kind, RuleDispatcher.describeRollOptions(candidates(pack, sources, state, actor, facts),
                    audience -> allowed(audience, relation)));
        }
    }

    private static ObjectNode effectProfile(Pack pack, SheetProjection.SourceElement source, JsonNode effect, String actorId) {
        if (!effect.has("profile") || !RuleEffects.sourceActorMatches(effect, actorId)) return null;
        String key = effect.path("effect").asText("");
        String prefix = source.sourceId() + "#";
        RuleCompiler.Program program = pack.programs().get(source.sourceId());
        if (program == null || !key.startsWith(prefix) || !program.hasEffect(key.substring(prefix.length()))) return null;
        RuleCompiler.validateProfile(effect.get("profile"), "/effects/profile");
        return ((ObjectNode) effect.get("profile")).deepCopy().put("id", EFFECT_PROFILE);
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
        RuleJson.fields(request, "/request", "requestId", "expectedRevision", "selections", "profileRoll", "target", "resultId");
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        if (request.has("resultId")) {
            if (!"self".equals(relation)) throw new SecurityException("Only the character owner may use this roll result.");
            if (request.has("profileRoll") || request.path("selections").size() != 1)
                throw RuleJson.error("/request/resultId", "Choose one ordinary result action.");
        }
        if (request.has("target") && "dm".equals(relation))
            throw new SecurityException("A scoped DM sheet grant cannot target other characters.");
        if (request.has("target") && (request.has("profileRoll") || request.path("selections").size() != 1))
            throw RuleJson.error("/request/target", "Choose a single target action.");
        if (request.has("profileRoll") && !"self".equals(relation))
            throw new SecurityException("Only the character owner may roll an attack profile.");
        String requestId = RuleJson.id(request.get("requestId"), "/request/requestId");
        String fingerprint = CampaignStore.sha256(canonical(request).toString().getBytes(StandardCharsets.UTF_8));
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        for (JsonNode entry : state.path("journal"))
            if (requestId.equals(entry.path("requestId").asText())) {
                if (!principalId.equals(entry.path("principalId").asText()) ||
                        "dm".equals(relation) != entry.path("dmPrincipal").asBoolean(false))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used by another caller.");
                if (entry.has("intentHash") && !fingerprint.equals(entry.path("intentHash").asText()))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used for a different action.");
                return ((ObjectNode) entry.path("response")).deepCopy();
            }
        long revision = state.path("revision").asLong();
        if (!request.path("expectedRevision").isIntegralNumber() || request.path("expectedRevision").asLong() != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this action.");

        ObjectNode response;
        if (request.has("profileRoll")) {
            response = profileRoll(pack, working, state, event, request, random);
            offerResult(pack, working, response, principalId, null, null);
        } else if (request.has("resultId")) {
            response = resultAction(pack, working, state, event, request, principalId, random, party);
        } else if (request.has("target")) {
            if (party == null) throw RuleJson.error("/request/target", "This action requires a host roster.");
            response = targeted(pack, working, state, event, request, relation, random, party);
        } else {
            var candidates = candidates(pack, working, event);
            var intents = selections(request, candidates, revision, relation, "sheet", false);
            response = applyEvent(pack, working, state, event, candidates, intents, random);
        }
        pruneResultWindows(pack, working, event);
        response.put("requestId", requestId);
        ArrayNode journal = (ArrayNode) projection.state(working).get("journal");
        ObjectNode receipt = journal.addObject().put("requestId", requestId).put("principalId", principalId).put("intentHash", fingerprint);
        if ("dm".equals(relation)) receipt.put("dmPrincipal", true);
        receipt.set("response", response.deepCopy());
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
        replace(sheet, working);
        return response;
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = RuleJson.JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) sorted.set(name, canonical(value.get(name)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = RuleJson.JSON.createArrayNode();
            value.forEach(item -> sorted.add(canonical(item)));
            return sorted;
        }
        return value;
    }

    private record TargetAction(List<RuleDispatcher.Candidate> candidates, PartyTargets.Selection selection) {}

    private TargetAction targetAction(Pack pack, ObjectNode sheet, ObjectNode event, ObjectNode request,
                                      String relation, PartyTargets party) {
        var candidates = candidates(pack, sheet, event);
        var intent = selections(request, candidates, revision(sheet), relation, "sheet", false).getFirst();
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
        var resolved = RuleDispatcher.executeTargets(action.candidates(), targets.intent(), targets.recipients(), random, pack.programs());
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
                                                        long revision, String relation, String surface, boolean rollOptions) {
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
            if (surface != null && !surface.equals(binding.path("surface").asText()))
                throw RuleJson.error(at + "/bindingId", "This binding is not available on this action surface.");
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
        RuleJson.fields(intent, "/request/profileRoll", "sourceInstanceId", "profileId", "kind", "effectInstanceId");
        String instance = RuleJson.id(intent.get("sourceInstanceId"), "/request/profileRoll/sourceInstanceId");
        String profileId = RuleJson.id(intent.get("profileId"), "/request/profileRoll/profileId");
        String kind = RuleJson.text(intent.get("kind"), "/request/profileRoll/kind");
        var sources = sources(pack, working);
        var source = sources.stream().filter(item -> instance.equals(item.instanceId())).findFirst()
                .orElseThrow(() -> new RuleRuntime.Unavailable("source", "This source is no longer active."));
        var program = pack.programs().get(source.sourceId());
        ObjectNode event = origin.deepCopy();
        String effectId = intent.has("effectInstanceId")
                ? RuleJson.text(intent.get("effectInstanceId"), "/request/profileRoll/effectInstanceId") : null;
        if (effectId != null) event.put("effectInstanceId", effectId);
        ActorProjection projected = projectActor(pack, working, state, event, sources, true);
        ObjectNode actor = projected.actor();
        JsonNode profile = null;
        ArrayNode declared = program == null ? RuleJson.JSON.createArrayNode() : program.profiles();
        if (effectId != null) {
            if (EFFECT_PROFILE.equals(profileId))
                for (JsonNode effect : actor.path("effects"))
                    if (effectId.equals(effect.path("id").textValue())
                            && instance.equals(effect.path("sourceInstanceId").textValue()))
                        profile = effectProfile(pack, source, effect, actor.path("id").textValue());
        } else {
            if (equippedWeapon(working, source)) {
                JsonNode base = ProfileRoll.weaponProfile(source, declared);
                if (profileId.equals(base.path("id").asText())) profile = base;
            }
            for (JsonNode candidate : declared)
                if (profileId.equals(candidate.path("id").asText())) profile = candidate;
        }
        if (profile == null) throw new RuleRuntime.Unavailable("profile", "This profile is no longer available.");

        long revision = state.path("revision").longValue();
        ProfileRoll.facts(event, source, profile, kind, actor, effectId == null && equippedWeapon(working, source));
        if (profile.has("limitRef")) {
            ObjectNode context = RuleRuntime.context(program, null, context(state, source, actor, event));
            RuleRuntime.reserveUse(program, context, profile.path("limitRef").asText());
            storeSource(state, source.instanceId(), context);
        }
        ProfileRoll.prepare(event, source, profile, kind, actor, effectId == null && equippedWeapon(working, source),
                projected.rollModifiers(), random);
        var candidates = candidates(pack, sources, state, actor, event);
        var intents = selections(request, candidates, revision, "self", "roll", true);
        ObjectNode result = applyEvent(pack, working, state, event, candidates, intents, random);
        ObjectNode finished = (ObjectNode) result.get("event");
        if (finished.path("blocked").size() > 0)
            throw new RuleRuntime.Unavailable("blocked", finished.at("/blocked/0/reason").asText());
        if (!finished.path("roll").equals(event.path("roll"))) ProfileRoll.updateCheck(finished);
        finished.put("type", "roll.finished");
        boolean successOnly = "success".equals(profile.path("consumeOn").asText());
        String outcome = successOnly ? limitedOutcome(finished) : null;
        if ("failure".equals(outcome))
            adjustProfileUse(pack, working, finished, source, program, profile.path("limitRef").asText(), true);
        ObjectNode after = automatic(pack, working, finished, random);
        ObjectNode finalEvent = (ObjectNode) after.get("event");
        if (!finalEvent.path("roll").equals(finished.path("roll"))) ProfileRoll.updateCheck(finalEvent);
        if (successOnly) {
            String finalOutcome = limitedOutcome(finalEvent);
            if (!finalOutcome.equals(outcome))
                adjustProfileUse(pack, working, finalEvent, source, program, profile.path("limitRef").asText(),
                        "failure".equals(finalOutcome));
        }
        result.set("event", finalEvent);
        for (String key : List.of("actions", "outbox"))
            ((ArrayNode) result.get(key)).addAll((ArrayNode) after.get(key));
        projection.state(working).put("revision", revision + 1);
        return result.put("revision", revision + 1);
    }

    private static String limitedOutcome(ObjectNode event) {
        String outcome = event.at("/check/outcome").asText();
        if (!Set.of("success", "failure").contains(outcome))
            throw new RuleRuntime.Unavailable("outcome", "This use-limited roll must have a definite success or failure.");
        return outcome;
    }

    private void adjustProfileUse(Pack pack, ObjectNode working, ObjectNode event, SheetProjection.SourceElement source,
                                  RuleCompiler.Program program, String limit, boolean refund) {
        var remaining = sources(pack, working).stream().filter(item -> source.instanceId().equals(item.instanceId())
                && source.sourceId().equals(item.sourceId())).findFirst().orElse(null);
        if (remaining == null) return;
        ObjectNode currentState = projection.state(working);
        ObjectNode currentActor = actor(pack, working, currentState, event);
        ObjectNode context = RuleRuntime.context(program, null, context(currentState, remaining, currentActor, event));
        if (refund) RuleRuntime.refundUse(program, context, limit);
        else RuleRuntime.reserveUse(program, context, limit);
        storeSource(currentState, source.instanceId(), context);
    }

    private static boolean equippedWeapon(ObjectNode sheet, SheetProjection.SourceElement source) {
        for (JsonNode item : sheet.path("activeWeapons")) if (item == source.element()) return true;
        return false;
    }

    private ObjectNode resultAction(Pack pack, ObjectNode working, ObjectNode state, ObjectNode origin,
                                    ObjectNode request, String principal, RandomGenerator random, PartyTargets party) {
        var sources = sources(pack, working);
        ResultWindows.prune(state, ResultWindows.guards(sources, activationEffects(pack, working, state, origin, sources),
                origin.path("actorId").textValue()));
        var window = ResultWindows.lookup(state, pack.revision(), principal, RuleJson.id(request.get("resultId"), "/request/resultId"));
        ObjectNode event = savedResultEvent(window, origin);
        ObjectNode actor = actor(pack, working, state, event, sources);
        var selection = request.requiredAt("/selections/0");
        String instance = RuleJson.id(selection.get("sourceInstanceId"), "/request/selections/0/sourceInstanceId");
        var element = sources.stream().filter(e -> instance.equals(e.instanceId())).findFirst().orElseThrow(ResultWindows::stale);
        var program = pack.programs().get(element.sourceId());
        if (program == null) throw ResultWindows.stale();
        var context = context(state, element, actor, event);
        var intent = selections(request, List.of(new RuleDispatcher.Candidate(program, context)), state.required("revision").longValue(),
                "self", null, false).getFirst();
        JsonNode binding = RuleDispatcher.binding(program.rule(intent.ruleId()), intent.bindingId());
        String claimGroup = binding.path("claimGroup").textValue();
        ResultWindows.take(window, new ResultWindows.Button(instance, intent.ruleId(), intent.bindingId(), intent.eachId()),
                claimGroup, binding.path("claimGroupScope").asText("source"), binding.path("exclusiveGroup").textValue(),
                binding.path("repeatable").asBoolean(false));
        if (binding.has("targetSelection") != request.has("target"))
            throw RuleJson.error("/request/target", "This result action requires its current target selection.");
        if (claimGroup != null) {
            String actorId = RuleJson.id(actor.get("id"), "/actor/id");
            long revision = state.required("revision").longValue();
            var candidate = new RuleDispatcher.Candidate(program, context);
            var recipient = new RuleDispatcher.RecipientCandidate(actorId, actor, revision);
            var claim = new RuleDispatcher.ClaimIntent(intent, actorId, revision, 0);
            var resolved = RuleDispatcher.executeClaims(List.of(candidate), List.of(claim), List.of(recipient),
                    new RuleDispatcher.ClaimLedger(0, Set.of()), random, pack.programs());
            var actions = resolved.actions().stream().map(RuleDispatcher.ClaimActionResult::action).toList();
            long published = actions.stream().mapToLong(action -> action.publishedRolls().size()).sum();
            if (published > 1)
                throw RuleJson.error("/result", "A result action may publish at most one revision of its roll.");
            var response = applyResolved(pack, working, state, event, resolved.sourceContexts(), actions, resolved.outbox());
            if (published == 1) {
                ObjectNode roll = actions.stream().flatMap(action -> action.publishedRolls().stream()).findFirst().orElseThrow();
                ((ObjectNode) response.required("event")).set("roll", roll);
                reviseResult(pack, working, response, principal, window);
            }
            return response;
        }
        if (request.has("target")) {
            if (party == null) throw RuleJson.error("/request/target", "This action requires a host roster.");
            var candidate = new RuleDispatcher.Candidate(program, context);
            var targets = party.resolve(binding, context, intent, request.get("target"));
            var resolved = RuleDispatcher.executeTargets(List.of(candidate), targets.intent(), targets.recipients(), random, pack.programs());
            var actions = resolved.actions().stream().map(RuleDispatcher.TargetActionResult::action).toList();
            long published = actions.stream().mapToLong(action -> action.publishedRolls().size()).sum();
            if (published > 1)
                throw RuleJson.error("/result", "A result action may publish at most one revision of its roll.");
            var response = applyResolved(pack, working, state, event, resolved.sourceContexts(), actions, resolved.outbox());
            for (int i = 0; i < resolved.actions().size(); i++)
                ((ObjectNode) response.path("actions").get(i)).put("recipientId", resolved.actions().get(i).recipientId());
            party.stage(resolved.recipients());
            if (published == 1) {
                ObjectNode roll = actions.stream().flatMap(action -> action.publishedRolls().stream()).findFirst().orElseThrow();
                ((ObjectNode) response.required("event")).set("roll", roll);
                reviseResult(pack, working, response, principal, window);
            }
            return response;
        }
        // Dispatch only the chosen button: automatic roll.finished reactions already ran when the roll was made.
        var resolution = RuleRuntime.execute(program, intent, context, random);
        if (resolution.publishedRolls().size() > 1)
            throw RuleJson.error("/result", "A result button may publish at most one revision of its roll.");
        var action = new RuleDispatcher.ActionResult(instance, intent.ruleId(), intent.bindingId(), resolution.report(),
                resolution.publishedRolls(), resolution.diceRolls());
        List<ObjectNode> commands = new ArrayList<>();
        resolution.context().path("outbox").forEach(cmd -> commands.add((ObjectNode) cmd));
        var response = applyResolved(pack, working, state, event, List.of(resolution.context()), List.of(action), commands);
        if (!resolution.publishedRolls().isEmpty()) reviseResult(pack, working, response, principal, window);
        return response;
    }

    private void reviseResult(Pack pack, ObjectNode working, ObjectNode response, String principal, ObjectNode window) {
        ProfileRoll.updateCheck((ObjectNode) response.required("event"));
        response.put("resultPublished", true);
        window.withArray("buttons").removeAll();
        offerResult(pack, working, response, principal, (ArrayNode) window.required("used"),
                window.path("usedClaimGroups"));
    }

    private void offerResult(Pack pack, ObjectNode sheet, ObjectNode response, String principal, ArrayNode used,
                             JsonNode usedClaimGroups) {
        ObjectNode event = (ObjectNode) response.required("event");
        var sources = sources(pack, sheet);
        if (sources.stream().noneMatch(element -> {
            var program = pack.programs().get(element.sourceId());
            return program != null && !program.resultButtons().isEmpty();
        })) return;
        if (sources.stream().noneMatch(source -> source.instanceId().equals(event.path("sourceInstanceId").textValue())))
            return; // Consuming the roll's source cannot leave an actionable result behind.
        ObjectNode state = projection.state(sheet);
        ObjectNode actor = actor(pack, sheet, state, event, sources);
        if (event.has("effectInstanceId")) {
            boolean active = false;
            for (JsonNode effect : actor.path("effects"))
                if (effect.path("id").equals(event.path("effectInstanceId"))
                        && effect.path("sourceInstanceId").equals(event.path("sourceInstanceId"))
                        && RuleEffects.sourceActorMatches(effect, actor.path("id").textValue())) active = true;
            if (!active) return;
        }
        ArrayNode matches = resultButtons(pack, sources, state, actor, event, null, null);
        if (used != null) {
            Set<ResultWindows.Button> spent = new java.util.HashSet<>();
            used.forEach(item -> spent.add(ResultWindows.Button.of(item)));
            Set<ResultWindows.ExclusiveGroup> spentAlternatives = new java.util.HashSet<>();
            used.forEach(item -> {
                if (item.has("exclusiveGroup")) spentAlternatives.add(ResultWindows.ExclusiveGroup.of(item));
            });
            Set<ResultWindows.ClaimGroup> spentGroups = new java.util.HashSet<>();
            if (usedClaimGroups != null && usedClaimGroups.isArray())
                usedClaimGroups.forEach(item -> spentGroups.add(ResultWindows.ClaimGroup.of(item)));
            for (int i = matches.size() - 1; i >= 0; i--)
                if (spent.contains(ResultWindows.Button.of(matches.get(i)))
                        || matches.get(i).has("exclusiveGroup")
                        && spentAlternatives.contains(ResultWindows.ExclusiveGroup.of(matches.get(i)))
                        || matches.get(i).has("claimGroup") && spentGroups.contains(ResultWindows.ClaimGroup.of(matches.get(i))))
                    matches.remove(i);
        }
        String id = ResultWindows.publish(state, pack.revision(), principal, event, matches,
                ResultWindows.guards(sources, actor.path("effects"), actor.path("id").textValue()));
        if (id != null) {
            if (used != null) ResultWindows.lookup(state, pack.revision(), principal, id).set("used", used.deepCopy());
            if (usedClaimGroups != null && usedClaimGroups.isArray())
                ResultWindows.lookup(state, pack.revision(), principal, id).set("usedClaimGroups", usedClaimGroups.deepCopy());
            event.put("resultId", id);
            response.set("resultActions", matches);
        }
    }

    private ArrayNode resultButtons(Pack pack, List<SheetProjection.SourceElement> sources, ObjectNode state,
                                    ObjectNode actor, ObjectNode event, Set<ResultWindows.Button> retained, PartyTargets party) {
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (var element : sources) {
            var program = pack.programs().get(element.sourceId());
            if (program == null || program.resultButtons().isEmpty()) continue;
            var context = context(state, element, actor, event);
            for (var binding : program.resultButtons()) {
                if (!allowed(binding.audience(), "self")) continue;
                if (binding.claimGroup() != null) context.set("recipient", context.get("actor"));
                else context.remove("recipient");
                Iterable<JsonNode> options = binding.forEach()
                        ? RuleRuntime.describeEach(program, binding.ruleId(), binding.bindingId(), context, party)
                        : List.of(RuleRuntime.describe(program, binding.ruleId(), binding.bindingId(), context, party));
                for (JsonNode item : options) {
                    if ("condition".equals(item.path("reasonCode").asText())) continue;
                    if (retained == null || retained.contains(ResultWindows.Button.of(item))) result.add(item);
                }
            }
        }
        return result;
    }

    public ObjectNode describeResult(Pack pack, ObjectNode sheet, String id, String relation, String principal, ObjectNode origin) {
        return describeResult(pack, sheet, id, relation, principal, origin, null);
    }

    public ObjectNode describeResult(Pack pack, ObjectNode sheet, String id, String relation, String principal,
                                     ObjectNode origin, PartyTargets party) {
        if (!"self".equals(relation)) throw new SecurityException("Only the character owner may use this roll result.");
        ObjectNode copy = sheet.deepCopy(), state = projection.state(copy);
        var sources = sources(pack, copy);
        ResultWindows.prune(state, ResultWindows.guards(sources, activationEffects(pack, copy, state, origin, sources),
                origin.path("actorId").textValue()));
        var window = ResultWindows.lookup(state, pack.revision(), principal, id);
        Set<ResultWindows.Button> retained = new java.util.HashSet<>();
        window.required("buttons").forEach(item -> retained.add(ResultWindows.Button.of(item)));
        ObjectNode event = savedResultEvent(window, origin);
        ObjectNode actor = actor(pack, copy, state, event, sources);
        return RuleJson.JSON.createObjectNode().put("revision", state.path("revision").asLong())
                .set("actions", resultButtons(pack, sources, state, actor, event, retained, party));
    }

    private static ObjectNode savedResultEvent(ObjectNode window, ObjectNode origin) {
        ObjectNode event = ((ObjectNode) window.required("event")).deepCopy();
        if (!origin.path("actorId").equals(event.path("actorId"))
                || !origin.path("sessionId").equals(event.path("sessionId"))) throw ResultWindows.stale();
        event.set("table", origin.required("table").deepCopy());
        return event;
    }

    /** Do not let removing and later re-equipping a source revive its old actions. */
    public void pruneResultWindows(Pack pack, ObjectNode sheet, ObjectNode event) {
        if (!(sheet.path(projection.stateField()) instanceof ObjectNode state) || !state.has("resultWindows")) return;
        var sources = sources(pack, sheet);
        ResultWindows.prune(state, ResultWindows.guards(sources, activationEffects(pack, sheet, state, event, sources),
                event.path("actorId").textValue()));
    }

    /** Keeps a newly published receipt and its retry record consistent with the final staged result windows. */
    public boolean reconcileResultOffer(Pack pack, ObjectNode sheet, ObjectNode response) {
        JsonNode origin = response.path("event");
        String resultId = origin.path("resultId").textValue();
        if (resultId == null) return false;
        JsonNode state = sheet.path(projection.stateField());
        ObjectNode current = null;
        for (JsonNode window : state.path("resultWindows").path("results"))
            if (resultId.equals(window.path("id").textValue())) current = RuleJson.object(window, "/result");
        ArrayNode actions = null;
        if (current != null) {
            Set<ResultWindows.Button> retained = new java.util.HashSet<>();
            current.required("buttons").forEach(item -> retained.add(ResultWindows.Button.of(item)));
            ObjectNode event = savedResultEvent(current, RuleJson.object(origin, "/response/event"));
            var sources = sources(pack, sheet);
            ObjectNode actor = actor(pack, sheet, RuleJson.object(state, "/" + projection.stateField()), event, sources);
            actions = resultButtons(pack, sources, (ObjectNode) state, actor, event, retained, null);
            if (actions.equals(response.path("resultActions"))) return false;
        }
        String requestId = RuleJson.id(response.get("requestId"), "/response/requestId");
        ObjectNode receipt = null;
        for (JsonNode entry : state.path("journal")) {
            if (!requestId.equals(entry.path("requestId").textValue())) continue;
            if (receipt != null || !(entry instanceof ObjectNode object))
                throw RuleJson.error("/journal", "The action receipt is not unique.");
            receipt = object;
        }
        if (receipt == null) throw RuleJson.error("/journal", "The action receipt is missing.");
        if (current == null) {
            ((ObjectNode) origin).remove("resultId");
            response.remove("resultActions");
        } else response.set("resultActions", actions);
        receipt.set("response", response.deepCopy());
        return true;
    }

    public SingleHolderEffect singleHolderCommand(Pack pack, JsonNode command) {
        RuleJson.fields(command, "/outbox", "kind", "sourceActorId", "sourceInstanceId", "effect", "holder", "label", "recipientId");
        if (!"effect.endElsewhere".equals(RuleJson.text(command.get("kind"), "/outbox/kind")))
            throw RuleJson.error("/outbox/kind", "Expected a single-holder effect request.");
        String sourceActor = RuleJson.id(command.get("sourceActorId"), "/outbox/sourceActorId");
        String instance = RuleJson.id(command.get("sourceInstanceId"), "/outbox/sourceInstanceId");
        String effect = RuleJson.text(command.get("effect"), "/outbox/effect");
        String holder = RuleJson.id(command.get("holder"), "/outbox/holder");
        int split = effect.lastIndexOf('#');
        RuleCompiler.Program program = split > 0 ? pack.programs().get(effect.substring(0, split)) : null;
        String definition = split > 0 ? effect.substring(split + 1) : "";
        if (program == null || !program.hasEffect(definition) || !program.effect(definition).path("singleHolder").asBoolean())
            throw RuleJson.error("/outbox/effect", "The requested single-holder effect is not declared by the current pack.");
        return new SingleHolderEffect(sourceActor, instance, effect, holder);
    }

    /** Removes other holders' matching stored effects, preserving supporting pools and the original sheet on failure. */
    public boolean endEffectsElsewhere(Pack pack, ObjectNode sheet, ObjectNode event,
                                       List<SingleHolderEffect> commands, boolean advanceRevision) {
        String holder = RuleJson.id(event.get("actorId"), "/event/actorId");
        ArrayNode original = RuleEffects.instances(projection.actorFact(sheet, "effects"), "/actor/effects");
        ArrayNode effects = null;
        for (int i = original.size() - 1; i >= 0; i--) {
            JsonNode effect = original.get(i);
            for (SingleHolderEffect command : commands) {
                if (holder.equals(command.holder()) || !command.effect().equals(effect.path("effect").asText())
                        || !command.sourceInstanceId().equals(effect.path("sourceInstanceId").asText())) continue;
                if (!RuleEffects.hasSourceActorId(effect))
                    throw RuleJson.error("/actor/effects", "Cannot determine the source character for effect '"
                            + effect.path("id").asText() + "' on character '" + holder
                            + "'. Migrate or repair its source identity before transferring it.");
                if (effect.path("sourceActorId").textValue().equals(command.sourceActorId())) {
                    if (effects == null) effects = original.deepCopy();
                    effects.remove(i);
                    break;
                }
            }
        }
        if (effects == null) return false;
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode before = actor(pack, working, state, event);
        ObjectNode after = before.deepCopy();
        for (JsonNode effect : before.path("effects"))
            if (effect.path("derived").asBoolean()) effects.add(effect.deepCopy());
        after.set("effects", effects);
        RuleEffects.reconcileHolder(before, after, Set.of(), pack.programs(), "/actor");
        projection.write(working, projection.actor(working), persisted(after));
        if (advanceRevision) state.put("revision", state.path("revision").asLong() + 1);
        replace(sheet, working);
        return true;
    }

    /** Dispatch a host transition in the caller's staged sheet transaction, without creating a player retry record. */
    public ObjectNode automatic(Pack pack, ObjectNode working, ObjectNode event, RandomGenerator random) {
        var sources = sources(pack, working);
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

    /** Stages rest preparation only; the caller must complete and atomically commit the entire rest. */
    ObjectNode prepareRest(Pack pack, ObjectNode sheet, ObjectNode origin, String kind, long expectedRevision,
                           ArrayNode choices, RandomGenerator random) {
        if (pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        if (!Set.of("short", "long").contains(kind)) throw RuleJson.error("/rest/kind", "Expected short or long.");
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        long revision = state.path("revision").asLong();
        if (expectedRevision < 0 || expectedRevision != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this rest.");
        int consecutive = working.has("shortRestsInRow")
                ? RuleJson.integer(working.get("shortRestsInRow"), "/sheet/shortRestsInRow", 0, 1_000_000) : 0;
        RestLimits limits = "short".equals(kind) ? pack.rest().shortRest() : pack.rest().longRest();
        if ("short".equals(kind) && consecutive >= limits.consecutiveLimit())
            throw new RuleRuntime.Unavailable("restLimit", "The party has taken " + limits.consecutiveLimit()
                    + " short rests in a row and must take a long rest next.");
        ObjectNode event = RuleJson.object(origin, "/event").deepCopy().put("type", "rest.prepare");
        ObjectNode plan = event.putObject("rest").put("kind", kind).put("moves", limits.moves()).put("longMoves", 0);
        ObjectNode adjustable = plan.putObject("adjustable");
        adjustable.putObject("moves").put("min", 0).put("max", limits.maxMoves());
        adjustable.putObject("longMoves").put("min", 0).put("max", limits.maxLongMoves());
        var candidates = candidates(pack, working, event);
        ObjectNode request = RuleJson.JSON.createObjectNode();
        request.set("selections", choices);
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        var intents = selections(request, candidates, revision, "self", "rest", false);
        Map<String, RuleDispatcher.Candidate> byInstance = new HashMap<>();
        candidates.forEach(candidate -> byInstance.put(candidate.instanceId(), candidate));
        for (var intent : intents) {
            JsonNode binding = RuleDispatcher.binding(byInstance.get(intent.sourceInstanceId()).program().rule(intent.ruleId()),
                    intent.bindingId());
            if (!"rest.prepare".equals(binding.path("event").asText()) || !"option".equals(binding.path("mode").asText()))
                throw RuleJson.error("/request/selections", "Choose only rest preparation options.");
        }
        ObjectNode response = applyEvent(pack, working, state, event, candidates, intents, random);
        JsonNode result = response.path("event").path("rest");
        if (!kind.equals(result.path("kind").asText()) || !adjustable.equals(result.path("adjustable")))
            throw RuleJson.error("/event/rest", "The rest plan's identity or bounds changed.");
        RuleJson.integer(result.get("moves"), "/event/rest/moves", 0, limits.maxMoves());
        RuleJson.integer(result.get("longMoves"), "/event/rest/longMoves", 0, limits.maxLongMoves());
        if (!response.path("event").path("blocked").isEmpty())
            throw new RuleRuntime.Unavailable("blocked", response.at("/event/blocked/0/reason").asText());
        replace(sheet, working);
        return response;
    }

    record RestMoveGroup(String sourceInstanceId, String group) {}
    record RestProgress(String kind, int moves, int longMoves, int slotsUsed, int longMovesUsed, int count,
                        Set<RestMoveGroup> groups) {
        RestProgress { groups = Set.copyOf(groups); }
    }
    record RestMoveResult(RestProgress progress, ObjectNode response) {}

    RestProgress restProgress(Pack pack, ObjectNode preparation) {
        if (pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        JsonNode event = preparation.path("event"), plan = event.path("rest");
        if (!"rest.prepare".equals(event.path("type").asText()))
            throw RuleJson.error("/event/type", "Expected a prepared rest.");
        String kind = plan.path("kind").asText();
        if (!Set.of("short", "long").contains(kind)) throw RuleJson.error("/event/rest/kind", "Expected short or long.");
        RestLimits limits = "short".equals(kind) ? pack.rest().shortRest() : pack.rest().longRest();
        int moves = RuleJson.integer(plan.get("moves"), "/event/rest/moves", 0, limits.maxMoves());
        int longMoves = RuleJson.integer(plan.get("longMoves"), "/event/rest/longMoves", 0, limits.maxLongMoves());
        return new RestProgress(kind, moves, longMoves, 0, 0, 0, Set.of());
    }

    /** Stages one move. Progress and the caller's sheet change only after all move checks succeed. */
    RestMoveResult restMove(Pack pack, ObjectNode sheet, ObjectNode origin, RestProgress progress,
                            ObjectNode selection, ArrayNode options, RandomGenerator random) {
        if (progress.count() >= 64) throw RuleJson.error("/rest/moves", "A rest may contain at most 64 moves.");
        RuleJson.fields(selection, "/selection", "sourceInstanceId", "ruleId", "bindingId", "inputs");
        RuleJson.checkTree(selection, "/selection", 0, new int[]{0});
        String instanceId = RuleJson.id(selection.get("sourceInstanceId"), "/selection/sourceInstanceId");
        String ruleId = RuleJson.text(selection.get("ruleId"), "/selection/ruleId");
        String bindingId = RuleJson.text(selection.get("bindingId"), "/selection/bindingId");
        ObjectNode working = sheet.deepCopy();
        var element = sources(pack, working).stream().filter(source -> instanceId.equals(source.instanceId())).findFirst()
                .orElseThrow(() -> new RuleRuntime.Unavailable("source", "This source is no longer active."));
        var program = pack.programs().get(element.sourceId());
        if (program == null) throw new RuleRuntime.Unavailable("source", "This source is no longer active.");
        JsonNode binding = RuleDispatcher.binding(program.rule(ruleId), bindingId);
        if (!binding.has("restMove") || !allowed(binding.path("audience").asText("self"), "self"))
            throw new RuleRuntime.Unavailable("move", "This is not an available downtime move.");
        JsonNode metadata = binding.path("restMove");
        String id = metadata.path("id").asText(), availability = metadata.path("kind").asText();
        if ("short".equals(availability) && !"short".equals(progress.kind())
                || "long".equals(availability) && !"long".equals(progress.kind()))
            throw new RuleRuntime.Unavailable("move", "This move is not available during this rest.");
        boolean usesLongAllowance = "short".equals(progress.kind()) && "longMove".equals(availability);
        if (usesLongAllowance && progress.longMovesUsed() >= progress.longMoves())
            throw new RuleRuntime.Unavailable("longMove", "No long-rest move remains available during this short rest.");
        RestMoveGroup group = new RestMoveGroup(instanceId, metadata.path("group").asText(id));
        if (progress.groups().contains(group))
            throw new RuleRuntime.Unavailable("duplicateMove", "This downtime move was already chosen.");

        ObjectNode event = RuleJson.object(origin, "/event").deepCopy().put("type", "downtime.move");
        ObjectNode move = event.putObject("move").put("id", id).put("rest", progress.kind())
                .put("long", "longMove".equals(availability)).put("ally", metadata.path("ally").asBoolean(false))
                .put("slots", 1).put("bonus", 0).put("allies", 0);
        ObjectNode adjustable = move.putObject("adjustable");
        adjustable.putObject("slots").put("min", 0).put("max", 1);
        adjustable.putObject("bonus").put("min", -1000).put("max", 1000);
        adjustable.putObject("allies").put("min", 0).put("max", 128);

        ObjectNode state = projection.state(working);
        var candidates = candidates(pack, working, event);
        ObjectNode request = RuleJson.JSON.createObjectNode();
        request.set("selections", options);
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        var intents = selections(request, candidates, state.path("revision").asLong(), "self", "downtime", false);
        Map<String, RuleDispatcher.Candidate> byInstance = new HashMap<>();
        candidates.forEach(candidate -> byInstance.put(candidate.instanceId(), candidate));
        for (var intent : intents) {
            JsonNode option = RuleDispatcher.binding(byInstance.get(intent.sourceInstanceId()).program().rule(intent.ruleId()),
                    intent.bindingId());
            if (!"downtime.move".equals(option.path("event").asText()) || !"option".equals(option.path("mode").asText()))
                throw RuleJson.error("/request/selections", "Choose only downtime move options.");
        }
        var adjustments = RuleDispatcher.executeStagedMove(candidates, intents, random, pack.programs());
        ObjectNode result = applyResolved(pack, working, state, event, adjustments.contexts(),
                adjustments.actions(), adjustments.outbox());
        ObjectNode adjusted = RuleJson.object(result.get("event"), "/event");
        validateRestMove(adjusted, move);
        candidates = candidates(pack, working, adjusted);
        var candidate = candidates.stream().filter(item -> instanceId.equals(item.instanceId())
                        && element.sourceId().equals(item.program().sourceId())).findFirst()
                .orElseThrow(() -> new RuleRuntime.Unavailable("source", "This source is no longer active."));
        JsonNode currentBinding = RuleDispatcher.binding(candidate.program().rule(ruleId), bindingId);
        if (!metadata.equals(currentBinding.path("restMove")))
            throw new RuleRuntime.Unavailable("move", "This downtime move changed.");
        ObjectNode inputs = selection.has("inputs") ? RuleJson.object(selection.get("inputs"), "/selection/inputs")
                : RuleJson.JSON.createObjectNode();
        var intent = new RuleRuntime.Intent(ruleId, bindingId, instanceId, revision(working), inputs);
        var resolution = RuleRuntime.execute(candidate.program(), intent, candidate.context(), random, pack.programs());
        var action = new RuleDispatcher.ActionResult(instanceId, ruleId, bindingId, resolution.report(),
                resolution.publishedRolls(), resolution.diceRolls());
        List<ObjectNode> commands = new ArrayList<>();
        resolution.context().path("outbox").forEach(command -> commands.add((ObjectNode) command));
        ObjectNode after = applyResolved(pack, working, projection.state(working), adjusted,
                List.of(resolution.context()), List.of(action), commands);
        ObjectNode finalEvent = RuleJson.object(after.get("event"), "/event");
        validateRestMove(finalEvent, move);
        int slots = finalEvent.at("/move/slots").intValue();
        if (progress.slotsUsed() + slots > progress.moves())
            throw new RuleRuntime.Unavailable("restMoves", "No downtime move slots remain.");
        for (String key : List.of("actions", "outbox")) ((ArrayNode) result.get(key)).addAll((ArrayNode) after.get(key));
        JsonNode completedMove = finalEvent.path("move").deepCopy();
        for (var adjustment : adjustments.actions()) {
            var original = byInstance.get(adjustment.sourceInstanceId());
            if (!original.program().rule(adjustment.ruleId()).has("after")) continue;
            var activeSources = sources(pack, working);
            var active = activeSources.stream()
                    .filter(item -> adjustment.sourceInstanceId().equals(item.instanceId())
                            && original.program().sourceId().equals(item.sourceId()))
                    .findFirst().orElseThrow(() -> new RuleRuntime.Unavailable("source",
                            "A deferred rest benefit's source is no longer active."));
            ObjectNode currentState = projection.state(working);
            ObjectNode currentActor = actor(pack, working, currentState, finalEvent, activeSources);
            var deferred = new RuleRuntime.Intent(adjustment.ruleId(), adjustment.bindingId(),
                    adjustment.sourceInstanceId(), currentState.path("revision").asLong(), RuleJson.JSON.createObjectNode());
            var completed = RuleRuntime.executeAfter(original.program(), deferred,
                    context(currentState, active, currentActor, finalEvent), random, pack.programs());
            var deferredAction = new RuleDispatcher.ActionResult(adjustment.sourceInstanceId(), adjustment.ruleId(),
                    adjustment.bindingId(), completed.report(), completed.publishedRolls(), completed.diceRolls());
            List<ObjectNode> deferredCommands = new ArrayList<>();
            completed.context().path("outbox").forEach(command -> deferredCommands.add((ObjectNode) command));
            ObjectNode response = applyResolved(pack, working, currentState, finalEvent,
                    List.of(completed.context()), List.of(deferredAction), deferredCommands);
            finalEvent = RuleJson.object(response.get("event"), "/event");
            validateRestMove(finalEvent, move);
            if (!completedMove.equals(finalEvent.path("move")))
                throw RuleJson.error("/event/move", "A deferred benefit cannot change the completed move.");
            for (String key : List.of("actions", "outbox"))
                ((ArrayNode) result.get(key)).addAll((ArrayNode) response.get(key));
            after = response;
        }
        result.set("event", finalEvent);
        result.put("revision", after.path("revision").asLong());
        Set<RestMoveGroup> groups = new java.util.HashSet<>(progress.groups());
        groups.add(group);
        RestProgress next = new RestProgress(progress.kind(), progress.moves(), progress.longMoves(),
                progress.slotsUsed() + slots, progress.longMovesUsed() + (usesLongAllowance ? 1 : 0),
                progress.count() + 1, groups);
        replace(sheet, working);
        return new RestMoveResult(next, result);
    }

    private static void validateRestMove(ObjectNode event, ObjectNode initial) {
        JsonNode move = event.path("move");
        for (String field : List.of("id", "rest", "long", "ally", "adjustable"))
            if (!initial.path(field).equals(move.path(field)))
                throw RuleJson.error("/event/move/" + field, "The move identity or bounds changed.");
        RuleJson.integer(move.get("slots"), "/event/move/slots", 0, 1);
        RuleJson.integer(move.get("bonus"), "/event/move/bonus", -1000, 1000);
        RuleJson.integer(move.get("allies"), "/event/move/allies", 0, 128);
        if (!event.path("blocked").isEmpty())
            throw new RuleRuntime.Unavailable("blocked", event.at("/blocked/0/reason").asText());
    }

    private ObjectNode applyEvent(Pack pack, ObjectNode working, ObjectNode state, ObjectNode event,
                                  List<RuleDispatcher.Candidate> candidates, List<RuleRuntime.Intent> intents, RandomGenerator random) {
        RuleDispatcher.EventResolution resolution = RuleDispatcher.executeEvent(candidates, intents, random, pack.programs());
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
        for (SheetProjection.SourceElement element : sources(pack, working)) elements.put(element.instanceId(), element);
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
    public boolean resetLimits(Pack pack, ObjectNode sheet, ObjectNode event) {
        return resetLimits(pack, sheet, event, null, false);
    }

    /** Captures the stored effects present before any downtime move is executed. */
    Set<String> restEffectSnapshot(ObjectNode sheet) {
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode effect : RuleEffects.instances(projection.actorFact(sheet, "effects"), "/actor/effects"))
            ids.add(effect.path("id").asText());
        return Set.copyOf(ids);
    }

    /** Resets a rest without expiring effects created after its validated initial snapshot. */
    boolean resetRest(Pack pack, ObjectNode sheet, ObjectNode event, Set<String> initialEffectIds) {
        if (initialEffectIds == null) throw RuleJson.error("/effects", "The initial rest effect snapshot is required.");
        return resetLimits(pack, sheet, event, Set.copyOf(initialEffectIds), true);
    }

    private boolean resetLimits(Pack pack, ObjectNode sheet, ObjectNode event, Set<String> eligibleEffectIds, boolean restOnly) {
        String type = RuleJson.text(RuleJson.object(event, "/event").get("type"), "/event/type");
        if (!Set.of("rest.short", "rest.long", "scene.end", "session.start").contains(type))
            throw RuleJson.error("/event/type", "Unknown limit-reset event.");
        if (restOnly && !Set.of("rest.short", "rest.long").contains(type))
            throw RuleJson.error("/event/type", "A rest effect snapshot requires a short or long rest.");
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode actor = actor(pack, working, state, event);
        boolean changed = "session.start".equals(type) && state.remove("resultWindows") != null;
        for (SheetProjection.SourceElement element : sources(pack, working)) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            ObjectNode context = context(state, element, actor, event);
            ObjectNode next = RuleRuntime.resetLimits(program, context, type);
            if (next.path("revision").asLong() != context.path("revision").asLong()) {
                changed = true;
                storeSource(state, element.instanceId(), next);
                actor = (ObjectNode) next.path("actor");
            }
        }
        if (actor.path("effects").isArray()) {
            ArrayNode kept = RuleEffects.expire(actor.path("effects"), type, eligibleEffectIds);
            if (kept.size() != actor.path("effects").size()) {
                ObjectNode beforeExpiry = actor.deepCopy();
                actor.set("effects", kept);
                RuleEffects.reconcileHolder(beforeExpiry, actor, Set.of(), pack.programs(), "/actor");
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

    private List<RuleDispatcher.Candidate> candidates(Pack pack, ObjectNode sheet, ObjectNode event) {
        ObjectNode state = projection.state(sheet);
        var sources = sources(pack, sheet);
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
     * Projects the actor and attaches {@code stats}. Trait bonuses are resolved before dependent non-trait passives;
     * other passive-stat reads and derived-effect activation use the unmodified baseline.
     */
    ObjectNode actor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event) {
        return actor(pack, sheet, state, event, sources(pack, sheet));
    }

    private ObjectNode actor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                             List<SheetProjection.SourceElement> sources) {
        return projectActor(pack, sheet, state, event, sources, false).actor();
    }

    private record ActorProjection(ObjectNode actor, List<ProfileRoll.Modifier> rollModifiers) {}

    private record PassiveProjection(SheetProjection.SourceElement source, RuleCompiler.Program program) {}

    private ArrayNode activationEffects(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                                        List<SheetProjection.SourceElement> sources) {
        ObjectNode actor = projection.actor(sheet);
        if (event.hasNonNull("actorId")) actor.set("id", event.get("actorId"));
        actor.set("stats", projection.stats(actor, List.of()));
        ArrayNode effects = actor.path("effects").isArray()
                ? (ArrayNode) actor.path("effects").deepCopy() : RuleJson.JSON.createArrayNode();
        for (var element : sources) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program != null) effects.addAll(RuleEffects.derived(program, context(state, element, actor, event)));
        }
        return effects;
    }

    private ActorProjection projectActor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                                         List<SheetProjection.SourceElement> sources, boolean collectRollModifiers) {
        ObjectNode actor = projection.actor(sheet);
        if (event.hasNonNull("actorId")) actor.set("id", event.get("actorId"));
        ObjectNode base = actor.deepCopy();
        base.set("stats", projection.stats(actor, List.of()));
        List<JsonNode> modifiers = new ArrayList<>();
        List<ProfileRoll.Modifier> rollModifiers = new ArrayList<>();
        List<PassiveProjection> passiveSources = new ArrayList<>();
        ObjectNode limitOverrides = RuleJson.JSON.createObjectNode();
        ArrayNode derived = RuleJson.JSON.createArrayNode();
        boolean readsDerivedTraits = false;
        for (SheetProjection.SourceElement element : sources) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            for (JsonNode override : program.limitOverrides()) {
                String key = override.path("feature").asText() + ":" + override.path("limit").asText();
                limitOverrides.put(key, Math.max(limitOverrides.path(key).asInt(), override.path("max").asInt()));
            }
            if (program.hasModifiers()) {
                passiveSources.add(new PassiveProjection(element, program));
                readsDerivedTraits |= program.readsDerivedTraits();
            }
            derived.addAll(RuleEffects.derived(program, context(state, element, base, event)));
        }
        if (!derived.isEmpty()) {
            ArrayNode effects = actor.path("effects").isArray() ? (ArrayNode) actor.get("effects") : actor.putArray("effects");
            effects.addAll(derived);
        }
        ArrayNode effectModifiers = actor.path("effects").isArray()
                ? RuleEffects.modifiers(actor.get("effects")) : RuleJson.JSON.createArrayNode();
        if (readsDerivedTraits) {
            List<JsonNode> traitModifiers = new ArrayList<>();
            for (PassiveProjection source : passiveSources)
                if (source.program().hasTraitModifiers())
                    RuleEffects.traitPassives(source.program(), context(state, source.source(), base, event)).forEach(traitModifiers::add);
            for (JsonNode modifier : effectModifiers)
                if (modifier.path("stat").asText().startsWith("trait.")) traitModifiers.add(modifier);
            if (!traitModifiers.isEmpty()) {
                ObjectNode preliminary = projection.traitStats(actor, traitModifiers);
                ObjectNode baseStats = (ObjectNode) base.get("stats");
                if (preliminary.has("trait")) baseStats.set("trait", preliminary.get("trait").deepCopy());
                if (preliminary.path("spellcast").has("total") && baseStats.path("spellcast").isObject())
                    ((ObjectNode) baseStats.get("spellcast")).set("total", preliminary.at("/spellcast/total").deepCopy());
            }
        }
        for (PassiveProjection source : passiveSources) {
            ArrayNode values = RuleEffects.passives(source.program(), context(state, source.source(), base, event));
            values.forEach(modifiers::add);
            if (collectRollModifiers) for (JsonNode modifier : values)
                if (modifier.path("stat").asText().startsWith("roll."))
                    rollModifiers.add(new ProfileRoll.Modifier(modifier, source.source().instanceId(),
                            modifier.path("label").asText(source.source().name()), equippedWeapon(sheet, source.source())));
        }
        effectModifiers.forEach(modifiers::add);
        if (actor.path("effects").isArray()) {
            if (collectRollModifiers) for (JsonNode effect : actor.get("effects"))
                for (JsonNode modifier : effect.path("modifiers"))
                    if (modifier.path("stat").asText().startsWith("roll."))
                        rollModifiers.add(new ProfileRoll.Modifier(modifier,
                                effect.path("sourceInstanceId").asText(effect.path("id").asText()),
                                modifier.path("label").asText(effect.path("label").asText()), false));
        }
        actor.set("stats", projection.stats(actor, modifiers));
        if (!limitOverrides.isEmpty()) actor.set("limitOverrides", limitOverrides);
        return new ActorProjection(actor, rollModifiers);
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
