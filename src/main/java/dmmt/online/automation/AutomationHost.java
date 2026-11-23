package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.CampaignStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Connects the generic rule engine to stored sheets through a {@link SheetProjection}. All game behaviour comes from
 * compiled catalog JSON; this class only selects active sources, checks audiences and revisions, and writes results back.
 */
public final class AutomationHost {
    public static final int JOURNAL_LIMIT = 32;
    private static final Set<String> RELATIONS = Set.of("self", "other", "dm");
    private static final String EFFECT_PROFILE = "effect.profile";
    // Runtime revisions pass through double-valued expressions; reserve room for intermediate stages.
    private static final long MAX_REST_REVISION = 1L << 52;
    private static final int MAX_PARTY_RECOVERIES = 1024;
    private static final int MAX_REACTION_PROJECTION_PASSES = 16;
    private static final Set<String> STAGED_REST_COMMANDS = Set.of(
            "countdown.start", "limits.refresh", "effect.endElsewhere", "table.gain", "table.spend");
    private static final Set<String> STAGED_TARGET_REST_COMMANDS = Set.of(
            "countdown.start", "limits.refresh", "table.gain", "table.spend");
    static final String RULESET_SOURCE = "ruleset:base";

    /** Compiled automation keyed by fully qualified source ID. */
    public record RestLimits(int moves, int maxMoves, int maxLongMoves, int consecutiveLimit) {}
    public record RestConfiguration(RestLimits shortRest, RestLimits longRest, Map<String, String> startFacts) {
        public RestConfiguration {
            startFacts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(startFacts));
        }
        public RestConfiguration(RestLimits shortRest, RestLimits longRest) { this(shortRest, longRest, Map.of()); }
    }

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
    private final byte[] restOfferKey = new byte[32];

    public AutomationHost(SheetProjection projection) {
        this.projection = projection;
        new SecureRandom().nextBytes(restOfferKey);
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
        RuleJson.fields(rest, "/rest", "short", "long", "startFacts");
        Map<String, String> startFacts = new LinkedHashMap<>();
        if (rest.has("startFacts")) {
            ObjectNode facts = RuleJson.object(rest.get("startFacts"), "/rest/startFacts");
            if (facts.size() > 32) throw RuleJson.error("/rest/startFacts", "Expected at most 32 initial facts.");
            facts.fields().forEachRemaining(field -> {
                if (!field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                    throw RuleJson.error("/rest/startFacts/" + field.getKey(), "Expected an initial fact name.");
                startFacts.put(field.getKey(), SheetProjection.path(field.getValue(), "/rest/startFacts/" + field.getKey()));
            });
        }
        return new RestConfiguration(restLimits(rest.path("short"), "/rest/short", true),
                restLimits(rest.path("long"), "/rest/long", false), startFacts);
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
        String fingerprint = intentFingerprint(request);
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode replay = replayReceipt(state, requestId, principalId, "dm".equals(relation), fingerprint, true);
        if (replay != null) return replay;
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
        recordReceipt(projection.state(working), requestId, principalId, "dm".equals(relation), fingerprint, response);
        replace(sheet, working);
        return response;
    }

    private static String intentFingerprint(ObjectNode request) {
        return CampaignStore.sha256(canonical(request).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode replayReceipt(ObjectNode state, String requestId, String principalId,
                                            boolean dmPrincipal, String fingerprint, boolean allowLegacy) {
        for (JsonNode entry : state.path("journal"))
            if (requestId.equals(entry.path("requestId").asText())) {
                if (!principalId.equals(entry.path("principalId").asText())
                        || dmPrincipal != entry.path("dmPrincipal").asBoolean(false))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used by another caller.");
                if (!entry.has("intentHash") ? !allowLegacy : !fingerprint.equals(entry.path("intentHash").asText()))
                    throw new RuleRuntime.Unavailable("requestId", "This request ID was already used for a different action.");
                return RuleJson.object(entry.get("response"), "/journal/response").deepCopy();
            }
        return null;
    }

    private static void recordReceipt(ObjectNode state, String requestId, String principalId,
                                      boolean dmPrincipal, String fingerprint, ObjectNode response) {
        ArrayNode journal = (ArrayNode) state.get("journal");
        ObjectNode receipt = journal.addObject().put("requestId", requestId).put("principalId", principalId).put("intentHash", fingerprint);
        if (dmPrincipal) receipt.put("dmPrincipal", true);
        receipt.set("response", response.deepCopy());
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
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

    public record RestMember(String id, String name, String sheetVersion, long revision, Supplier<ObjectNode> loadSheet) {
        public RestMember {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/recipient/id");
            if (name == null || name.isBlank() || sheetVersion == null || sheetVersion.isBlank()
                    || revision < 0 || revision > MAX_REST_REVISION || loadSheet == null)
                throw RuleJson.error("/recipient", "A host character, sheet version and representable revision are required.");
        }
    }

    public record RestRoster(String sourceVersion, Function<String, List<RestMember>> loadScope) {
        public RestRoster {
            if (sourceVersion == null || sourceVersion.isBlank() || loadScope == null)
                throw RuleJson.error("/recipients", "A host source version and roster are required.");
        }
    }

    record PartyRecoveryRequest(String sourceId, String sourceInstanceId, String track, int requested, String label) {
        PartyRecoveryRequest {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/recovery/sourceId");
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceInstanceId), "/recovery/sourceInstanceId");
            if (track == null || !track.matches("[a-zA-Z][a-zA-Z0-9_-]*"))
                throw RuleJson.error("/recovery/track", "Expected an actor track name.");
            if (requested < 1 || requested > 1_000_000)
                throw RuleJson.error("/recovery/requested", "Expected a positive recovery amount up to 1000000.");
            RuleJson.text(RuleJson.JSON.getNodeFactory().textNode(label), "/recovery/label");
        }
    }

    record ScopedPartyRecoveryRequest(String scope, PartyRecoveryRequest request) {
        ScopedPartyRecoveryRequest {
            if (scope == null || !RuleCompiler.TARGET_SCOPES.contains(scope))
                throw RuleJson.error("/recovery/scope", "Expected a host character scope.");
            if (request == null) throw RuleJson.error("/recovery/request", "Expected a recovery request.");
        }
    }

    record RestPartyRecovery(int moveIndex, int adjustmentIndex, ScopedPartyRecoveryRequest request) {}

    private record RecoverySourceOrder(int index, String sourceId, String sourcePath, RuleCompiler.Program program) {}

    private record OrderedRestRecovery(RestPartyRecovery recovery, int sourceIndex, int ruleIndex, int stepIndex) {}

    private record ScheduledRecoveryKey(String sourceInstanceId, String ruleId, String bindingId, int stepIndex,
                                        int moveIndex) {}

    private record RecoveryTask(ScopedPartyRecoveryRequest request, ObjectNode moveEvent, int moveIndex,
                                int adjustmentIndex) {}

    record PartyRecovery(ObjectNode response, Map<String, ObjectNode> recipients, Map<String, String> readVersions,
                         Map<String, List<String>> scopeMembership, Map<String, List<String>> scopeOrder) {
        PartyRecovery {
            response = response.deepCopy();
            recipients = copySheets(recipients);
            readVersions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(readVersions));
            scopeMembership = copyScopeMembership(scopeMembership);
            scopeOrder = copyScopeMembership(scopeOrder);
        }
        @Override public ObjectNode response() { return response.deepCopy(); }
        @Override public Map<String, ObjectNode> recipients() { return copySheets(recipients); }
    }

    public record RestTransaction(ObjectNode response, Map<String, ObjectNode> recipients, Map<String, String> readVersions,
                                  Map<String, List<String>> scopeMembership, Map<String, List<String>> scopeOrder,
                                  boolean replayed) {
        public RestTransaction {
            response = response.deepCopy();
            recipients = copySheets(recipients);
            readVersions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(readVersions));
            scopeMembership = copyScopeMembership(scopeMembership);
            scopeOrder = copyScopeMembership(scopeOrder);
        }
        public RestTransaction(ObjectNode response, Map<String, ObjectNode> recipients, Map<String, String> readVersions,
                               Map<String, List<String>> scopeMembership, boolean replayed) {
            this(response, recipients, readVersions, scopeMembership, Map.of(), replayed);
        }
        public RestTransaction(ObjectNode response, Map<String, ObjectNode> recipients, Map<String, String> readVersions,
                               boolean replayed) {
            this(response, recipients, readVersions, Map.of(), Map.of(), replayed);
        }
        @Override public ObjectNode response() { return response.deepCopy(); }
        @Override public Map<String, ObjectNode> recipients() { return copySheets(recipients); }
    }

    private static Map<String, List<String>> copyScopeMembership(Map<String, List<String>> scopes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        scopes.forEach((scope, members) -> result.put(scope, List.copyOf(members)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, ObjectNode> copySheets(Map<String, ObjectNode> sheets) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        sheets.forEach((id, sheet) -> result.put(id, sheet.deepCopy()));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Stages a host-owned clear against the holder's sheet; the caller must settle commands and commit it. */
    ObjectNode stageHolderRecovery(Pack pack, ObjectNode sheet, ObjectNode parentEvent, String holderId,
                                   String track, int requested, RandomGenerator random) {
        RuleJson.object(sheet, "/sheet");
        RuleJson.object(parentEvent, "/event");
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(holderId), "/holderId");
        if (pack == null || random == null) throw RuleJson.error("/recovery", "A compiled pack and random source are required.");
        ObjectNode working = sheet.deepCopy();
        long revision = restRevision(working);
        ObjectNode state = projection.state(working);
        var sources = sources(pack, working);
        ObjectNode projectionEvent = parentEvent.deepCopy().put("actorId", holderId);
        ObjectNode actor = actor(pack, working, state, projectionEvent, sources);
        var candidates = candidates(pack, sources, state, actor, parentEvent);
        var recovered = RuleDispatcher.clearOwnerTrack(candidates, actor, parentEvent, revision,
                track, requested, random, pack.programs(),
                ownerRecoveryProjection(pack, working, state, sources, actor, holderId));
        if (recovered.revision() > MAX_REST_REVISION)
            throw new RuleRuntime.Unavailable("revision", "The holder's recovery revision cannot be advanced.");

        ObjectNode response;
        if (recovered.actions().isEmpty()) {
            response = RuleJson.JSON.createObjectNode();
            response.putArray("actions");
            recovered.outbox().forEach(response.putArray("outbox")::add);
            if (recovered.revision() != revision)
                projection.write(working, projection.actor(working), persisted(recovered.actor()));
        } else {
            response = applyResolved(pack, working, state, parentEvent, recovered.contexts(),
                    recovered.actions(), recovered.outbox());
        }
        response.put("holderId", holderId).put("track", track).put("requested", requested)
                .put("cleared", recovered.cleared()).put("revision", recovered.revision());
        response.set("event", recovered.event());
        if (recovered.revision() != revision) {
            projection.state(working).put("revision", recovered.revision());
            replace(sheet, working);
        }
        return response;
    }

    private RuleDispatcher.OwnerProjection ownerRecoveryProjection(Pack pack, ObjectNode sheet, ObjectNode state,
                                                                    List<SheetProjection.SourceElement> sources,
                                                                    ObjectNode initialActor, String holderId) {
        return ownerRecoveryProjection(pack, sheet, state, sources, initialActor, holderId, false);
    }

    private RuleDispatcher.OwnerProjection ownerRecoveryProjection(Pack pack, ObjectNode sheet, ObjectNode state,
                                                                    List<SheetProjection.SourceElement> sources,
                                                                    ObjectNode initialActor, String holderId,
                                                                    boolean projectTrackStats) {
        Map<String, ObjectNode> initialSources = new HashMap<>();
        Map<String, SheetProjection.SourceElement> sourceElements = new HashMap<>();
        boolean hasTrackObservers = false;
        boolean hasComputedSourcePools = false;
        boolean hasProjectionDependencies = false;
        for (var element : sources) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program == null) continue;
            initialSources.put(element.instanceId(), sourceContext(element));
            sourceElements.put(element.instanceId(), element);
            hasTrackObservers |= !program.automatic("track.changed").isEmpty();
            hasComputedSourcePools |= program.hasComputedSourcePools();
            hasProjectionDependencies |= program.stateMayAffectProjection();
        }
        boolean needsTrackProjection = projectTrackStats || hasTrackObservers || hasProjectionDependencies
                || !initialActor.path("effects").isEmpty();
        boolean needsSourcePoolGuard = hasComputedSourcePools;
        return (staged, contexts, operation, previousActor, target) -> {
            if ("recipient".equals(target)
                    && !staged.at("/recipient/id").equals(staged.at("/actor/id")))
                throw new RuleRuntime.Unavailable("recipientReactions",
                        "Changing another character during a track reaction requires a recipient transaction.");
            if ("track.changed".equals(operation) && !needsTrackProjection)
                return (ObjectNode) staged.get("actor");
            if (operation.startsWith("state.")) {
                ObjectNode current = contexts.get(staged.at("/source/instanceId").asText());
                RuleCompiler.Program program = current == null ? null : pack.programs().get(current.at("/source/id").asText());
                if (program != null && !program.stateMayAffectProjection())
                    return (ObjectNode) staged.get("actor");
            }
            if (operation.startsWith("effect.")) {
                for (JsonNode command : staged.path("outbox"))
                    if ("effect.endElsewhere".equals(command.path("kind").asText()))
                        throw new RuleRuntime.Unavailable("reactionEffect",
                                "A single-holder effect requires cross-holder lifecycle settlement.");
                if (previousActor != null)
                    RuleEffects.reconcileHolder(previousActor, staged.path("actor"), Set.of(), pack.programs(), "/actor");
            }
            for (String fact : projection.sourceFacts())
                if (!initialActor.path(fact).equals(staged.path("actor").path(fact)))
                    throw new RuleRuntime.Unavailable("reactionSource",
                            "Changing source-bearing character fields during a track reaction requires source activation settlement.");
            for (var entry : initialSources.entrySet()) {
                ObjectNode current = contexts.get(entry.getKey());
                RuleCompiler.Program program = pack.programs().get(sourceElements.get(entry.getKey()).sourceId());
                if (current == null && (program.hasComputedActorPools() || !program.automatic("track.changed").isEmpty())
                        || current != null && !entry.getValue().equals(current.path("source")))
                    throw new RuleRuntime.Unavailable("reactionSource",
                            "Changing a source during a track reaction requires source activation settlement.");
            }
            ObjectNode currentState = state.deepCopy();
            contexts.forEach((instance, source) -> storeSource(currentState, instance, source));
            if (needsSourcePoolGuard)
                throw new RuleRuntime.Unavailable("reactionSourcePool",
                        "A computed source pool cannot yet be settled during a track reaction.");
            ObjectNode event = ((ObjectNode) staged.get("event")).deepCopy().put("actorId", holderId);
            ObjectNode prior = (ObjectNode) staged.get("actor");
            for (int pass = 0; pass < MAX_REACTION_PROJECTION_PASSES; pass++) {
                ObjectNode projected = projectActor(pack, sheet, currentState, event, sources, false,
                        persisted(prior)).actor();
                JsonNode pools = projected.path("pools").deepCopy();
                RuleEffects.reconcileHolder(prior, projected, Set.of(), pack.programs(), "/actor");
                if (pools.equals(projected.path("pools"))) {
                    requireSettledReactionEffects(pack, projected, contexts, currentState, sourceElements, event, holderId);
                    return projected;
                }
                prior = projected;
            }
            throw new RuleRuntime.Unavailable("reactionEffectDependencies",
                    "Effect-bound pools did not stabilize during this reaction.");
        };
    }

    private static void requireSettledReactionEffects(Pack pack, ObjectNode actor, Map<String, ObjectNode> contexts,
                                                       ObjectNode state, Map<String, SheetProjection.SourceElement> sources,
                                                       ObjectNode event, String holderId) {
        for (JsonNode effect : actor.path("effects")) {
            if (effect.path("derived").asBoolean()
                    || !effect.has("effect") && !effect.has("sourceInstanceId")) continue;
            String key = effect.path("effect").asText("");
            int split = key.lastIndexOf('#');
            RuleCompiler.Program program = split > 0 ? pack.programs().get(key.substring(0, split)) : null;
            String effectId = split > 0 ? key.substring(split + 1) : "";
            if (program == null || !program.hasEffect(effectId))
                throw new RuleRuntime.Unavailable("reactionEffect",
                        "A track reaction cannot resolve an active effect's automation definition.");
            ObjectNode definition = program.effect(effectId);
            if (!definition.has("endWhen")) continue;
            String instanceId = effect.path("sourceInstanceId").asText();
            ObjectNode source = contexts.get(instanceId);
            SheetProjection.SourceElement element = sources.get(instanceId);
            if (source == null && element != null) source = projectionInput(state, element, actor, event);
            if (!RuleEffects.hasSourceActorId(effect) || !holderId.equals(effect.path("sourceActorId").asText())
                    || source == null || !program.sourceId().equals(source.at("/source/id").asText()))
                throw new RuleRuntime.Unavailable("reactionEffect",
                        "A conditional effect requires its original source character and state before recovery.");
            ObjectNode view = source.deepCopy();
            view.set("actor", actor.deepCopy());
            view.set("event", event.deepCopy());
            view = RuleRuntime.projectionContext(program, view);
            if (RuleExpression.bool(RuleExpression.evaluate(definition.get("endWhen"), view), "/effects/" + effectId + "/endWhen"))
                throw new RuleRuntime.Unavailable("reactionEffect",
                        "A conditional effect must end during this reaction; lifecycle settlement is not available yet.");
        }
    }

    /** Stages trusted recovery requests for a host-selected scope without persisting or publishing them. */
    PartyRecovery stagePartyRecovery(Pack pack, ObjectNode event, RestRoster roster, String scope,
                                     List<PartyRecoveryRequest> requests, RandomGenerator random) {
        if (scope == null || !RuleCompiler.TARGET_SCOPES.contains(scope))
            throw RuleJson.error("/recovery/scope", "Expected a host character scope.");
        if (requests == null || requests.size() > 128 || requests.stream().anyMatch(java.util.Objects::isNull))
            throw RuleJson.error("/recovery/requests", "Expected at most 128 recovery requests.");
        List<ScopedPartyRecoveryRequest> scoped = requests.stream()
                .map(request -> new ScopedPartyRecoveryRequest(scope, request)).toList();
        PartyRecovery staged = stagePartyRecoveries(pack, event, roster, scoped, random);
        ObjectNode response = staged.response().put("scope", scope).put("scopeInspected", !requests.isEmpty());
        response.set("membership", requests.isEmpty() ? RuleJson.JSON.createArrayNode()
                : response.at("/scopeSnapshots/0/membership").deepCopy());
        return new PartyRecovery(response, staged.recipients(), staged.readVersions(), staged.scopeMembership(),
                staged.scopeOrder());
    }

    /** Reads only host-produced move adjustments; it does not authorize or settle the resulting requests. */
    static List<ScopedPartyRecoveryRequest> partyRecoveryRequests(ObjectNode event) {
        if (!"downtime.move".equals(RuleJson.text(RuleJson.object(event, "/event").get("type"), "/event/type")))
            throw RuleJson.error("/event/type", "Expected a host-generated downtime move.");
        JsonNode move = RuleJson.object(event.get("move"), "/event/move");
        if (!move.path("ally").isBoolean()) throw RuleJson.error("/event/move/ally", "Expected a boolean.");
        if (move.path("ally").asBoolean()) return List.of();
        int allies = RuleJson.integer(move.get("allies"), "/event/move/allies", 0, 128);
        JsonNode adjustments = move.path("adjustments");
        if (!adjustments.isMissingNode() && !adjustments.isArray())
            throw RuleJson.error("/event/move/adjustments", "Expected an ordered adjustment list.");
        List<ScopedPartyRecoveryRequest> result = new ArrayList<>();
        boolean hasPositiveAllies = false;
        for (int i = 0; i < adjustments.size(); i++) {
            JsonNode adjustment = RuleJson.object(adjustments.get(i), "/event/move/adjustments/" + i);
            String at = "/event/move/adjustments/" + i;
            String field = RuleJson.id(adjustment.get("field"), at + "/field");
            int amount = RuleJson.integer(adjustment.get("requested"), at + "/requested", -1000, 1000);
            if (amount <= 0) {
                if (adjustment.path("partyRecovery").has("afterRest"))
                    throw RuleJson.error(at + "/requested", "A scheduled recovery must have its compiled positive amount.");
                continue;
            }
            if ("allies".equals(field)) hasPositiveAllies = true;
            if (!adjustment.has("partyRecovery")) {
                if ("allies".equals(field))
                    throw new RuleRuntime.Unavailable("restCommand",
                            "A party recovery adjustment must declare its track and character scope.");
                continue;
            }
            JsonNode recovery = adjustment.get("partyRecovery");
            RuleJson.fields(recovery, at + "/partyRecovery", "track", "scope", "afterRest");
            if (recovery.has("afterRest")) {
                JsonNode schedule = recovery.get("afterRest");
                RuleJson.fields(schedule, at + "/partyRecovery/afterRest", "sourcePath", "move");
                SheetProjection.path(schedule.path("sourcePath"), at + "/partyRecovery/afterRest/sourcePath");
                RuleJson.id(schedule.path("move"), at + "/partyRecovery/afterRest/move");
            }
            String scope = RuleJson.text(recovery.get("scope"), at + "/partyRecovery/scope");
            PartyRecoveryRequest request = new PartyRecoveryRequest(
                    RuleJson.id(adjustment.get("sourceId"), at + "/sourceId"),
                    RuleJson.id(adjustment.get("sourceInstanceId"), at + "/sourceInstanceId"),
                    RuleJson.text(recovery.get("track"), at + "/partyRecovery/track"), amount,
                    RuleJson.text(adjustment.get("label"), at + "/label"));
            result.add(new ScopedPartyRecoveryRequest(scope, request));
            if (result.size() > 128) throw RuleJson.error("/event/move/adjustments", "A move is limited to 128 recovery requests.");
        }
        if (allies > 0 && !hasPositiveAllies)
            throw new RuleRuntime.Unavailable("restCommand", "The party recovery request has no authored adjustment.");
        return List.copyOf(result);
    }

    /**
     * Orders initial-source schedules and host-produced adjustments across a rest. Events must originate from
     * the private rest transaction, not from a client; checking provenance does not authenticate an event.
     */
    List<RestPartyRecovery> orderRestPartyRecoveries(Pack pack, ObjectNode initialSheet, String actorId,
                                                     List<ObjectNode> moveEvents) {
        if (pack == null) throw RuleJson.error("/recovery", "A compiled pack is required.");
        RuleJson.object(initialSheet, "/sheet");
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(actorId), "/event/actorId");
        if (moveEvents == null || moveEvents.size() > 64 || moveEvents.stream().anyMatch(java.util.Objects::isNull))
            throw RuleJson.error("/rest/moves", "Expected at most 64 host-generated move events.");
        List<SheetProjection.SourceElement> initialSources = sources(pack, initialSheet);
        Map<String, RecoverySourceOrder> sourceOrder = new HashMap<>();
        for (int i = 0; i < initialSources.size(); i++) {
            var source = initialSources.get(i);
            sourceOrder.put(source.instanceId(), new RecoverySourceOrder(i, source.sourceId(), source.sourcePath(),
                    pack.programs().get(source.sourceId())));
        }
        Set<String> sourcePaths = Set.copyOf(projection.sourcePaths());
        Map<RuleCompiler.Program, Map<String, Integer>> ruleOrders = new HashMap<>();
        Map<ScheduledRecoveryKey, Integer> adjustmentIndexes = new HashMap<>();
        List<OrderedRestRecovery> ordered = new ArrayList<>();
        for (int moveIndex = 0; moveIndex < moveEvents.size(); moveIndex++) {
            ObjectNode event = moveEvents.get(moveIndex);
            RuleJson.checkTree(event, "/event", 0, new int[]{0});
            if (!actorId.equals(RuleJson.id(event.get("actorId"), "/event/actorId")))
                throw RuleJson.error("/event/actorId", "A rest move belongs to a different character.");
            String moveId = RuleJson.id(event.at("/move/id"), "/event/move/id");
            List<ScopedPartyRecoveryRequest> requests = partyRecoveryRequests(event);
            if (requests.isEmpty()) continue;
            JsonNode adjustments = event.at("/move/adjustments");
            int requestIndex = 0;
            for (int adjustmentIndex = 0; adjustmentIndex < adjustments.size(); adjustmentIndex++) {
                JsonNode adjustment = adjustments.get(adjustmentIndex);
                if (adjustment.path("requested").asInt() <= 0 || !adjustment.has("partyRecovery")) continue;
                ScopedPartyRecoveryRequest request = requests.get(requestIndex++);
                String at = "/event/move/adjustments/" + adjustmentIndex;
                RecoverySourceOrder source = sourceOrder.get(request.request().sourceInstanceId());
                if (source == null || source.program() == null || !source.sourceId().equals(request.request().sourceId()))
                    throw new RuleRuntime.Unavailable("source",
                            "A party recovery source was not present at the start of this rest.");
                String ruleId = RuleJson.id(adjustment.get("ruleId"), at + "/ruleId");
                String bindingId = RuleJson.id(adjustment.get("bindingId"), at + "/bindingId");
                if (!"do".equals(RuleJson.text(adjustment.get("phase"), at + "/phase")))
                    throw RuleJson.error(at + "/phase", "Expected a pre-move operation.");
                ObjectNode rule = source.program().rule(ruleId);
                JsonNode binding = RuleDispatcher.binding(rule, bindingId);
                if (!"downtime.move".equals(binding.path("event").asText())
                        || !"downtime".equals(binding.path("surface").asText()))
                    throw new RuleRuntime.Unavailable("restCommand",
                            "A recovery triggered by another event cannot yet be ordered across a rest.");
                JsonNode steps = rule.path("do");
                if (steps.isEmpty()) throw RuleJson.error(at + "/stepIndex", "The recovery rule has no operations.");
                int stepIndex = RuleJson.integer(adjustment.get("stepIndex"), at + "/stepIndex", 0, steps.size() - 1);
                JsonNode step = steps.get(stepIndex);
                if (!"plan.adjust".equals(step.path("op").asText()) || !"event.move".equals(step.path("plan").asText())
                        || !step.path("field").equals(adjustment.path("field"))
                        || !step.path("partyRecovery").equals(adjustment.path("partyRecovery")))
                    throw RuleJson.error(at, "The recovery does not match its compiled operation.");
                JsonNode schedule = step.path("partyRecovery").path("afterRest");
                if (!schedule.isMissingNode()) {
                    if (request.request().requested() != step.path("by").asInt()
                            || !request.request().label().equals(RuleTemplate.render(step.path("label").asText(),
                            RuleJson.JSON.createObjectNode())))
                        throw RuleJson.error(at, "The scheduled recovery does not match its compiled amount or label.");
                    String sourcePath = schedule.path("sourcePath").asText();
                    requireRestSourcePath(sourcePaths, sourcePath);
                    if (sourcePath.equals(source.sourcePath()) && moveId.equals(schedule.path("move").asText())) {
                        var key = new ScheduledRecoveryKey(request.request().sourceInstanceId(), ruleId, bindingId,
                                stepIndex, moveIndex);
                        if (adjustmentIndexes.putIfAbsent(key, adjustmentIndex) != null)
                            throw RuleJson.error(at, "A scheduled recovery has more than one matching adjustment.");
                    }
                    continue;
                }
                Map<String, Integer> rules = ruleOrders.computeIfAbsent(source.program(), program -> {
                    Map<String, Integer> indexes = new HashMap<>();
                    List<String> ids = program.orderedRuleIds();
                    for (int i = 0; i < ids.size(); i++) indexes.put(ids.get(i), i);
                    return indexes;
                });
                if (ordered.size() == 128)
                    throw RuleJson.error("/rest/moves", "A rest is limited to 128 party recovery requests.");
                ordered.add(new OrderedRestRecovery(new RestPartyRecovery(moveIndex, adjustmentIndex, request),
                        source.index(), rules.get(ruleId), stepIndex));
            }
        }
        for (int sourceIndex = 0; sourceIndex < initialSources.size(); sourceIndex++) {
            var source = initialSources.get(sourceIndex);
            RuleCompiler.Program program = pack.programs().get(source.sourceId());
            if (program == null) continue;
            for (int moveIndex = 0; moveIndex < moveEvents.size(); moveIndex++) {
                JsonNode move = moveEvents.get(moveIndex).path("move");
                if (move.path("ally").asBoolean()) continue;
                for (var schedule : program.restRecoveries(move.path("id").asText())) {
                    requireRestSourcePath(sourcePaths, schedule.sourcePath());
                    if (!schedule.sourcePath().equals(source.sourcePath())) continue;
                    var key = new ScheduledRecoveryKey(source.instanceId(), schedule.ruleId(), schedule.bindingId(),
                            schedule.stepIndex(), moveIndex);
                    var request = new ScopedPartyRecoveryRequest(schedule.scope(),
                            new PartyRecoveryRequest(source.sourceId(), source.instanceId(), schedule.track(),
                                    schedule.amount(), schedule.label()));
                    if (ordered.size() == 128)
                        throw RuleJson.error("/rest/moves", "A rest is limited to 128 party recovery requests.");
                    ordered.add(new OrderedRestRecovery(new RestPartyRecovery(moveIndex,
                            adjustmentIndexes.getOrDefault(key, -1), request),
                            sourceIndex, schedule.ruleIndex(), schedule.stepIndex()));
                }
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(OrderedRestRecovery::sourceIndex)
                .thenComparingInt(OrderedRestRecovery::ruleIndex).thenComparingInt(OrderedRestRecovery::stepIndex)
                .thenComparingInt(item -> item.recovery().moveIndex())
                .thenComparingInt(item -> item.recovery().adjustmentIndex()));
        return ordered.stream().map(OrderedRestRecovery::recovery).toList();
    }

    private static void requireRestSourcePath(Set<String> sourcePaths, String path) {
        if (!sourcePaths.contains(path))
            throw RuleJson.error("/recovery/sourcePath", "The scheduled source location is not in the sheet projection: " + path + ".");
    }

    private boolean hasScheduledRestRecovery(Pack pack, List<SheetProjection.SourceElement> initialSources, String move) {
        Set<String> sourcePaths = Set.copyOf(projection.sourcePaths());
        for (var source : initialSources) {
            RuleCompiler.Program program = pack.programs().get(source.sourceId());
            if (program == null) continue;
            for (var schedule : program.restRecoveries(move)) {
                requireRestSourcePath(sourcePaths, schedule.sourcePath());
                if (schedule.sourcePath().equals(source.sourcePath())) return true;
            }
        }
        return false;
    }

    /** Stages an ordered sequence with potentially different host-selected scopes. */
    PartyRecovery stagePartyRecoveries(Pack pack, ObjectNode event, RestRoster roster,
                                       List<ScopedPartyRecoveryRequest> requests, RandomGenerator random) {
        return stagePartyRecoveries(pack, event, roster, requests, random, false);
    }

    /** Uses the host roster's original order and retains it as a commit dependency. */
    PartyRecovery stagePartyRecoveriesInRosterOrder(Pack pack, ObjectNode event, RestRoster roster,
                                                    List<ScopedPartyRecoveryRequest> requests, RandomGenerator random) {
        return stagePartyRecoveries(pack, event, roster, requests, random, true);
    }

    private PartyRecovery stagePartyRecoveries(Pack pack, ObjectNode event, RestRoster roster,
                                               List<ScopedPartyRecoveryRequest> requests, RandomGenerator random,
                                               boolean rosterOrder) {
        RuleJson.object(event, "/event");
        String sourceId = RuleJson.id(event.get("actorId"), "/event/actorId");
        RuleJson.text(event.get("type"), "/event/type");
        RuleJson.checkTree(event, "/event", 0, new int[]{0});
        if (pack == null || roster == null || random == null)
            throw RuleJson.error("/recovery", "A compiled pack, host roster and random source are required.");
        if (requests == null || requests.size() > 128 || requests.stream().anyMatch(java.util.Objects::isNull))
            throw RuleJson.error("/recovery/requests", "Expected at most 128 recovery requests.");
        StagedRestRecipients recipients = new StagedRestRecipients(pack, sourceId, roster);
        List<RecoveryTask> tasks = requests.stream().map(request -> new RecoveryTask(request, null, -1, -1)).toList();
        ObjectNode response = recoverParty(recipients, event, tasks, random, rosterOrder);
        return new PartyRecovery(response, recipients.changed(), recipients.readVersions(),
                recipients.scopeMembership(), recipients.scopeOrder());
    }

    /** Stages trusted recovery from all moves after a private rest has completed; it does not commit any sheets. */
    PartyRecovery stageRestPartyRecoveries(Pack pack, ObjectNode initialSheet, ObjectNode completedEvent,
                                           List<ObjectNode> moveEvents, RestRoster roster, RandomGenerator random) {
        if (roster == null || random == null)
            throw RuleJson.error("/recovery", "A host roster and random source are required.");
        String actorId = RuleJson.id(RuleJson.object(completedEvent, "/event").get("actorId"), "/event/actorId");
        StagedRestRecipients recipients = new StagedRestRecipients(pack, actorId, roster);
        ObjectNode response = stageRestPartyRecoveries(pack, initialSheet, completedEvent, moveEvents, recipients, random);
        return new PartyRecovery(response, recipients.changed(), recipients.readVersions(),
                recipients.scopeMembership(), recipients.scopeOrder());
    }

    private ObjectNode stageRestPartyRecoveries(Pack pack, ObjectNode initialSheet, ObjectNode completedEvent,
                                                List<ObjectNode> moveEvents, StagedRestRecipients recipients,
                                                RandomGenerator random) {
        if (random == null) throw RuleJson.error("/recovery", "A random source is required.");
        RuleJson.object(completedEvent, "/event");
        RuleJson.checkTree(completedEvent, "/event", 0, new int[]{0});
        String actorId = RuleJson.id(completedEvent.get("actorId"), "/event/actorId");
        if (!actorId.equals(recipients.sourceId))
            throw RuleJson.error("/event/actorId", "The completed rest belongs to a different character.");
        String type = RuleJson.text(completedEvent.get("type"), "/event/type");
        if (!RuleRuntime.isRestEvent(type)) throw RuleJson.error("/event/type", "Expected a completed rest.");
        String kind = type.substring("rest.".length());
        if (!kind.equals(completedEvent.at("/rest/kind").asText())
                || !"afterReset".equals(completedEvent.at("/rest/phase").asText()))
            throw RuleJson.error("/event/rest", "Expected the completed rest's after-reset facts.");
        List<RestPartyRecovery> ordered = orderRestPartyRecoveries(pack, initialSheet, actorId, moveEvents);
        for (ObjectNode move : moveEvents) {
            if (!kind.equals(move.at("/move/rest").asText())
                    || !java.util.Objects.equals(completedEvent.get("sessionId"), move.get("sessionId")))
                throw RuleJson.error("/rest/moves", "A move does not belong to this completed rest.");
        }
        List<RecoveryTask> tasks = ordered.stream().map(item -> new RecoveryTask(item.request(),
                moveEvents.get(item.moveIndex()), item.moveIndex(), item.adjustmentIndex())).toList();
        return recoverParty(recipients, completedEvent, tasks, random, true);
    }

    private ObjectNode recoverParty(StagedRestRecipients recipients, ObjectNode parentEvent,
                                    List<RecoveryTask> requests, RandomGenerator random, boolean rosterOrder) {
        ObjectNode response = RuleJson.JSON.createObjectNode();
        ArrayNode actions = response.putArray("actions"), outbox = response.putArray("outbox");
        ArrayNode clears = response.putArray("clears");
        ArrayNode snapshots = response.putArray("scopeSnapshots");
        ObjectNode event = parentEvent.deepCopy();
        if (!requests.isEmpty()) {
            Map<String, List<StagedRecipient>> scopes = new LinkedHashMap<>();
            long applications = 0;
            for (RecoveryTask task : requests) {
                ScopedPartyRecoveryRequest request = task.request();
                List<StagedRecipient> members = scopes.get(request.scope());
                if (members == null) {
                    members = recipients.scoped(request.scope(), rosterOrder);
                    scopes.put(request.scope(), members);
                    ArrayNode membership = snapshots.addObject().put("scope", request.scope()).putArray("membership");
                    for (StagedRecipient member : members)
                        membership.addObject().put("id", member.member.id()).put("name", member.member.name())
                                .put("sheetVersion", member.member.sheetVersion()).put("revision", member.member.revision());
                }
                applications += members.size();
                if (applications > MAX_PARTY_RECOVERIES)
                    throw RuleJson.error("/recovery/requests", "A transaction is limited to 1024 recipient recoveries.");
            }
            for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
                RecoveryTask task = requests.get(requestIndex);
                ScopedPartyRecoveryRequest scoped = task.request();
                PartyRecoveryRequest request = scoped.request();
                ObjectNode moveEvent = task.moveEvent() == null ? null : task.moveEvent().deepCopy();
                ObjectNode completedFacts = null;
                if (moveEvent != null) {
                    carryRestTable(moveEvent, event);
                    completedFacts = moveEvent.deepCopy();
                    completedFacts.remove("table");
                }
                for (StagedRecipient member : scopes.get(scoped.scope())) {
                    ObjectNode sheet = member.sheet();
                    ObjectNode recovered = stageHolderRecovery(recipients.pack, sheet, moveEvent == null ? event : moveEvent,
                            member.member.id(),
                            request.track(), request.requested(), random);
                    ObjectNode recoveredEvent = RuleJson.object(recovered.get("event"), "/event");
                    if (moveEvent != null) {
                        if (!recoveredEvent.path("blocked").isEmpty())
                            throw new RuleRuntime.Unavailable("blocked", recoveredEvent.at("/blocked/0/reason").asText());
                        ObjectNode unchanged = recoveredEvent.deepCopy();
                        unchanged.remove("table");
                        if (!completedFacts.equals(unchanged))
                            throw new RuleRuntime.Unavailable("restEvent",
                                    "A recovery reaction cannot change an already-completed rest or move.");
                    }
                    for (JsonNode command : recovered.path("outbox")) {
                        if (!STAGED_TARGET_REST_COMMANDS.contains(command.path("kind").asText()))
                            throw new RuleRuntime.Unavailable("restCommand",
                                    "This recovery requires host settlement before later recipients: " + command.path("kind").asText() + ".");
                        outbox.add(((ObjectNode) command).deepCopy().put("actorId", member.member.id()));
                    }
                    for (JsonNode action : recovered.path("actions"))
                        actions.add(((ObjectNode) action).deepCopy().put("actorId", member.member.id()));
                    ObjectNode clear = clears.addObject().put("requestIndex", requestIndex).put("holderId", member.member.id())
                            .put("holderName", member.member.name()).put("sourceId", request.sourceId())
                            .put("sourceInstanceId", request.sourceInstanceId()).put("scope", scoped.scope()).put("track", request.track())
                            .put("requested", request.requested()).put("cleared", recovered.path("cleared").asInt())
                            .put("label", request.label());
                    if (moveEvent == null) event = recoveredEvent.deepCopy();
                    else {
                        clear.put("moveIndex", task.moveIndex()).put("adjustmentIndex", task.adjustmentIndex());
                        carryRestTable(event, recoveredEvent);
                        carryRestTable(moveEvent, event);
                    }
                    if (recovered.path("revision").asLong() != member.member.revision()) {
                        projection.state(sheet).put("revision", member.member.revision());
                        recipients.changed.add(member.member.id());
                    }
                }
            }
        }
        response.set("event", event);
        RuleJson.checkTree(response, "/recovery", 0, new int[]{0});
        return response;
    }

    /**
     * Stages a self-only rest without publishing or persistence. A live caller must authenticate offered choices,
     * settle the outbox and journal and commit the resulting sheet and reports as one transaction.
     */
    ObjectNode stageSelfRest(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request, RandomGenerator random) {
        return stageRest(pack, sheet, origin, request, random, null).response();
    }

    /**
     * Stages a rest and its recipient sheets on private copies. The roster supplies original host versions; the
     * returned read versions identify every recipient sheet the caller must compare during the eventual commit.
     */
    RestTransaction stageRest(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                              RandomGenerator random, RestRoster roster) {
        return stageRest(pack, sheet, origin, request, random, roster, false);
    }

    /** Private staging prerequisite; it does not authorize or commit a live rest. */
    RestTransaction stageRestWithPartyRecovery(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                                RandomGenerator random, RestRoster roster) {
        return stageRest(pack, sheet, origin, request, random, roster, true);
    }

    private RestTransaction stageRest(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                      RandomGenerator random, RestRoster roster, boolean settlePartyRecovery) {
        RuleJson.fields(request, "/request", "kind", "expectedRevision", "preparation", "moves", "completion");
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        String kind = RuleJson.text(request.get("kind"), "/request/kind");
        if (!Set.of("short", "long").contains(kind)) throw RuleJson.error("/request/kind", "Expected short or long.");
        JsonNode expected = request.path("expectedRevision");
        if (!expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0)
            throw RuleJson.error("/request/expectedRevision", "Expected a nonnegative host revision.");
        ArrayNode preparation = restSelections(request.get("preparation"), "/request/preparation");
        ArrayNode moves = restSelections(request.get("moves"), "/request/moves");
        ArrayNode completion = restSelections(request.get("completion"), "/request/completion");
        if (moves.size() > 64) throw RuleJson.error("/request/moves", "A rest may contain at most 64 moves.");
        boolean targeted = false;
        for (int i = 0; i < moves.size(); i++) {
            String at = "/request/moves/" + i;
            RuleJson.fields(moves.get(i), at, "selection", "options", "target");
            RuleJson.object(moves.get(i).get("selection"), at + "/selection");
            restSelections(moves.get(i).get("options"), at + "/options");
            if (moves.get(i).has("target")) {
                RuleJson.object(moves.get(i).get("target"), at + "/target");
                targeted = true;
            }
        }
        String actorId = RuleJson.id(RuleJson.object(origin, "/event").get("actorId"), "/event/actorId");
        if (pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        if ((targeted || settlePartyRecovery) && roster == null)
            throw new RuleRuntime.Unavailable("restTarget", "A host recipient roster is required for this rest.");

        ObjectNode working = sheet.deepCopy();
        long revision = restRevision(working);
        if (expected.longValue() != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this rest.");
        if (revision == MAX_REST_REVISION)
            throw new RuleRuntime.Unavailable("revision", "The character's rest revision cannot be advanced.");
        List<SheetProjection.SourceElement> initialSources = sources(pack, sheet);
        StagedRestRecipients recipients = targeted || settlePartyRecovery ? new StagedRestRecipients(pack, actorId, roster) : null;

        Set<String> initial = restEffectSnapshot(working);
        ObjectNode restEvent = origin.deepCopy().put("type", "rest." + kind);
        expireRestBeforeMoves(pack, working, restEvent, initial);
        projection.state(working).put("revision", revision);
        ObjectNode prepared = prepareRest(pack, working, origin, kind, revision, preparation, random, initial);
        projection.state(working).put("revision", revision);
        RestProgress progress = restProgress(pack, prepared);
        ObjectNode stagedOrigin = origin.deepCopy();
        carryRestTable(stagedOrigin, prepared.required("event"));

        ObjectNode response = RuleJson.JSON.createObjectNode();
        ArrayNode actions = response.putArray("actions"), outbox = response.putArray("outbox");
        ArrayNode moveEvents = response.putArray("moveEvents");
        List<ObjectNode> completedMoves = settlePartyRecovery ? new ArrayList<>() : null;
        response.set("preparationEvent", prepared.path("event").deepCopy());
        appendRestStage(prepared, actions, outbox, targeted || settlePartyRecovery);
        for (JsonNode move : moves) {
            PartyTargets party = move.has("target") ? recipients.party() : null;
            RestTarget target = party == null ? null : new RestTarget(party, (ObjectNode) move.get("target"), recipients::reproject);
            RestMoveResult result = restMove(pack, working, stagedOrigin, progress, (ObjectNode) move.get("selection"),
                    (ArrayNode) move.get("options"), random, target, initialSources, settlePartyRecovery);
            progress = result.progress();
            ObjectNode completedMove = RuleJson.object(result.response().get("event"), "/event").deepCopy();
            if (completedMoves != null) completedMoves.add(completedMove);
            moveEvents.add(completedMove);
            appendRestStage(result.response(), actions, outbox, targeted || settlePartyRecovery);
            carryRestTable(stagedOrigin, result.response().required("event"));
            if (party != null) recipients.apply(party.updates());
            projection.state(working).put("revision", revision);
        }
        ObjectNode finished = completeRest(pack, working, stagedOrigin, progress, initial, completion, random);
        appendRestStage(finished, actions, outbox, targeted || settlePartyRecovery);
        ObjectNode finalEvent = RuleJson.object(finished.get("event"), "/event");
        if (settlePartyRecovery) {
            ObjectNode recovery = stageRestPartyRecoveries(pack, sheet, finalEvent, completedMoves, recipients, random);
            appendRestStage(recovery, actions, outbox, true);
            response.set("partyRecoveries", recovery.path("clears").deepCopy());
            finalEvent = RuleJson.object(recovery.get("event"), "/event");
        }
        response.set("event", finalEvent.deepCopy());
        response.set("skipped", finished.path("skipped").deepCopy());
        response.putObject("progress").put("moves", progress.moves()).put("longMoves", progress.longMoves())
                .put("slotsUsed", progress.slotsUsed()).put("longMovesUsed", progress.longMovesUsed())
                .put("count", progress.count());
        response.put("revision", revision + 1);
        projection.state(working).put("revision", revision + 1);
        RuleJson.checkTree(response, "/response", 0, new int[]{0});
        Map<String, ObjectNode> changed = recipients == null ? Map.of() : recipients.changed();
        Map<String, String> readVersions = recipients == null ? Map.of() : recipients.readVersions();
        Map<String, List<String>> scopes = recipients == null ? Map.of() : recipients.scopeMembership();
        Map<String, List<String>> order = recipients == null ? Map.of() : recipients.scopeOrder();
        RestTransaction transaction = new RestTransaction(response, changed, readVersions, scopes, order, false);
        replace(sheet, working);
        return transaction;
    }

    /**
     * Adds a retry receipt to a fully staged rest. The caller must authenticate the owner and commit the source
     * and all dependencies atomically; a replay never contains recipient updates or requires another commit.
     */
    RestTransaction stageRestWithReceipt(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                         String principalId, RandomGenerator random, RestRoster roster) {
        return stageRestReceipt(pack, sheet, origin, request, principalId, random, roster, null, false, false);
    }

    /** Adds a receipt to the private shared-recipient staging prerequisite. */
    RestTransaction stageRestWithPartyRecoveryReceipt(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                                      String principalId, RandomGenerator random, RestRoster roster) {
        return stageRestReceipt(pack, sheet, origin, request, principalId, random, roster, null, false, true);
    }

    /**
     * Signs a rest snapshot after the caller has authenticated its owner and obtained the stored-sheet version.
     * This is a concurrency token, not permission to execute client-supplied rules or event facts.
     */
    public String restOfferVersion(Pack pack, ObjectNode sheet, ObjectNode origin, String kind,
                                   String principalId, String sourceVersion) {
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("Missing automation principal.");
        if (sourceVersion == null || sourceVersion.isBlank()) throw new IllegalArgumentException("Missing stored sheet version.");
        if (pack == null || pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        if (!"short".equals(kind) && !"long".equals(kind)) throw RuleJson.error("/rest/kind", "Expected short or long.");
        RuleJson.object(origin, "/event");
        RuleJson.id(origin.get("actorId"), "/event/actorId");
        RuleJson.text(origin.get("sessionId"), "/event/sessionId");
        RuleJson.checkTree(origin, "/event", 0, new int[]{0});
        long revision = restRevision(RuleJson.object(sheet, "/sheet").deepCopy());
        ObjectNode signed = RuleJson.JSON.createObjectNode().put("purpose", "rest-offer-v1")
                .put("pack", pack.revision()).put("principal", principalId).put("sheetVersion", sourceVersion)
                .put("revision", revision).put("kind", kind);
        signed.set("event", canonical(origin));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(restOfferKey, "HmacSHA256"));
            byte[] signature = mac.doFinal(canonical(signed).toString().getBytes(StandardCharsets.UTF_8));
            return "rest-v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot authenticate rest offers.", ex);
        }
    }

    /** Stages a signed rest; the caller must authenticate ownership and atomically commit all dependencies. */
    public RestTransaction stageOfferedRest(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                            String principalId, String sourceVersion, RandomGenerator random, RestRoster roster) {
        return stageRestReceipt(pack, sheet, origin, request, principalId, random, roster, sourceVersion, true, false);
    }

    private RestTransaction stageRestReceipt(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode request,
                                             String principalId, RandomGenerator random, RestRoster roster,
                                             String sourceVersion, boolean offered, boolean settlePartyRecovery) {
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("Missing automation principal.");
        if (offered)
            RuleJson.fields(request, "/request", "requestId", "offerVersion", "kind", "expectedRevision", "preparation", "moves", "completion");
        else RuleJson.fields(request, "/request", "requestId", "kind", "expectedRevision", "preparation", "moves", "completion");
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        String requestId = RuleJson.id(request.get("requestId"), "/request/requestId");
        String fingerprint = intentFingerprint(request);
        ObjectNode working = sheet.deepCopy();
        restRevision(working);
        ObjectNode replay = replayReceipt(projection.state(working), requestId, principalId, false, fingerprint, false);
        if (replay != null) return new RestTransaction(replay, Map.of(), Map.of(), true);

        if (offered) {
            String supplied = RuleJson.text(request.get("offerVersion"), "/request/offerVersion");
            String expected = restOfferVersion(pack, working, origin, request.path("kind").asText(), principalId, sourceVersion);
            if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
                throw new RuleRuntime.Unavailable("restOffer", "The rest offer changed. Reopen downtime.");
            if (roster != null && !sourceVersion.equals(roster.sourceVersion()))
                throw new RuleRuntime.Unavailable("restOffer", "The rest roster changed. Reopen downtime.");
        }
        ObjectNode rest = request.deepCopy();
        rest.remove("requestId");
        rest.remove("offerVersion");
        RestTransaction staged = stageRest(pack, working, origin, rest, random, roster, settlePartyRecovery);
        ObjectNode response = staged.response().put("requestId", requestId);
        RuleJson.checkTree(response, "/response", 0, new int[]{0});
        recordReceipt(projection.state(working), requestId, principalId, false, fingerprint, response);
        RestTransaction transaction = new RestTransaction(response, staged.recipients(), staged.readVersions(),
                staged.scopeMembership(), staged.scopeOrder(), false);
        replace(sheet, working);
        return transaction;
    }

    private static void carryRestTable(ObjectNode origin, JsonNode event) {
        JsonNode table = event.get("table");
        if (table == null) origin.remove("table");
        else origin.set("table", table.deepCopy());
    }

    private long restRevision(ObjectNode sheet) {
        JsonNode persistedState = sheet.get(projection.stateField());
        if (persistedState != null) {
            RuleJson.object(persistedState, "/automationState");
            if (persistedState.has("sources") && !persistedState.path("sources").isObject())
                throw RuleJson.error("/automationState/sources", "Expected an object.");
            if (persistedState.has("journal") && !persistedState.path("journal").isArray())
                throw RuleJson.error("/automationState/journal", "Expected a list.");
        }
        ObjectNode state = projection.state(sheet);
        JsonNode savedRevision = state.path("revision");
        if (!savedRevision.isIntegralNumber() || !savedRevision.canConvertToLong()
                || savedRevision.longValue() < 0 || savedRevision.longValue() > MAX_REST_REVISION)
            throw RuleJson.error("/automationState/revision", "Expected a nonnegative, representable host revision.");
        return savedRevision.longValue();
    }

    private static ArrayNode restSelections(JsonNode node, String path) {
        if (!(node instanceof ArrayNode array)) throw RuleJson.error(path, "Expected a list.");
        return array;
    }

    private static void appendRestStage(ObjectNode stage, ArrayNode actions, ArrayNode outbox, boolean targeted) {
        for (JsonNode command : stage.path("outbox")) {
            String kind = command.path("kind").asText();
            if (!(targeted ? STAGED_TARGET_REST_COMMANDS : STAGED_REST_COMMANDS).contains(kind))
                throw new RuleRuntime.Unavailable("restCommand",
                        "This rest requires host settlement before later stages: " + kind + ".");
        }
        actions.addAll((ArrayNode) stage.get("actions"));
        outbox.addAll((ArrayNode) stage.get("outbox"));
    }

    /** Rechecks only membership; the caller must compare read sheet bytes within the same commit boundary. */
    public static void requireRestScopeMembership(String sourceId, Map<String, List<String>> expected, RestRoster roster) {
        requireRestScopeSnapshots(sourceId, expected, Map.of(), roster);
    }

    /** Rechecks host ordering without hydrating recipient sheets. */
    public static void requireRestScopeOrder(String sourceId, Map<String, List<String>> expected, RestRoster roster) {
        requireRestScopeSnapshots(sourceId, Map.of(), expected, roster);
    }

    /** Rechecks membership and order from one roster snapshot per scope; the caller must hold the commit boundary. */
    public static void requireRestScopeSnapshots(String sourceId, Map<String, List<String>> membership,
                                                 Map<String, List<String>> order, RestRoster roster) {
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/event/actorId");
        if (membership == null || order == null || roster == null)
            throw RuleJson.error("/recipients", "Expected host scope snapshots and roster.");
        Set<String> scopes = new java.util.LinkedHashSet<>(membership.keySet());
        scopes.addAll(order.keySet());
        for (String scope : scopes) {
            if (scope == null || !RuleCompiler.TARGET_SCOPES.contains(scope)
                    || (membership.containsKey(scope) && membership.get(scope) == null)
                    || (order.containsKey(scope) && order.get(scope) == null))
                throw RuleJson.error("/recipients", "Invalid host scope snapshot.");
            List<String> current = restScopeMembers(sourceId, roster.loadScope().apply(scope)).stream()
                    .map(RestMember::id).toList();
            if (membership.containsKey(scope) && !current.stream().sorted().toList().equals(membership.get(scope)))
                throw new RuleRuntime.Unavailable("staleRecipient", "The eligible characters changed. Reopen this rest.");
            if (order.containsKey(scope) && !current.equals(order.get(scope)))
                throw new RuleRuntime.Unavailable("staleRecipient", "The character order changed. Reopen this rest.");
        }
    }

    private static List<RestMember> restScopeMembers(String sourceId, List<RestMember> members) {
        if (members == null || members.size() > 128)
            throw RuleJson.error("/recipients", "A rest is limited to 128 characters.");
        List<RestMember> result = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (RestMember member : members) {
            if (member == null || !seen.add(member.id()))
                throw RuleJson.error("/recipients", "Duplicate or missing target.");
            if (!sourceId.equals(member.id())) result.add(member);
        }
        return List.copyOf(result);
    }

    private final class StagedRestRecipients {
        private final Pack pack;
        private final String sourceId;
        private final RestRoster roster;
        private final Map<String, StagedRecipient> participants = new LinkedHashMap<>();
        private final Set<String> changed = new java.util.LinkedHashSet<>();
        private final Map<String, List<String>> scopeMembership = new LinkedHashMap<>();
        private final Map<String, List<String>> scopeOrder = new LinkedHashMap<>();

        private StagedRestRecipients(Pack pack, String sourceId, RestRoster roster) {
            this.pack = pack;
            this.sourceId = sourceId;
            this.roster = roster;
        }

        private PartyTargets party() {
            return new PartyTargets(pack.revision(), roster.sourceVersion(), scope -> {
                List<PartyTargets.Member> result = new ArrayList<>();
                for (StagedRecipient selected : scoped(scope)) {
                    RestMember member = selected.member;
                    result.add(new PartyTargets.Member(member.id(), member.name(), member.sheetVersion(), member.revision(),
                            event -> recipientSnapshot(pack, selected.sheet(), event, member.id())));
                }
                return result;
            });
        }

        private List<StagedRecipient> scoped(String scope) {
            return scoped(scope, false);
        }

        private List<StagedRecipient> scoped(String scope, boolean preserveOrder) {
            if (!RuleCompiler.TARGET_SCOPES.contains(scope))
                throw RuleJson.error("/recipients", "Expected a host character scope.");
            List<RestMember> members = restScopeMembers(sourceId, roster.loadScope().apply(scope));
            List<String> hostOrder = members.stream().map(RestMember::id).toList();
            List<String> ids = hostOrder.stream().sorted().toList();
            List<String> previous = scopeMembership.putIfAbsent(scope, ids);
            if (previous != null && !previous.equals(ids))
                throw new RuleRuntime.Unavailable("staleRecipient", "The eligible characters changed. Reopen this rest.");
            List<String> previousOrder = scopeOrder.get(scope);
            if (previousOrder != null && !previousOrder.equals(hostOrder))
                throw new RuleRuntime.Unavailable("staleRecipient", "The character order changed. Reopen this rest.");
            if (preserveOrder) scopeOrder.putIfAbsent(scope, hostOrder);
            else members = members.stream().sorted(java.util.Comparator.comparing(RestMember::id)).toList();
            List<StagedRecipient> result = new ArrayList<>();
            for (RestMember member : members) {
                StagedRecipient participant = participants.get(member.id());
                if (participant == null) {
                    if (participants.size() == 128) throw RuleJson.error("/recipients", "A rest is limited to 128 characters.");
                    participant = new StagedRecipient(member);
                    participants.put(member.id(), participant);
                } else if (!participant.sameVersion(member)) {
                    throw new RuleRuntime.Unavailable("staleRecipient", "A character changed. Reopen this rest.");
                }
                result.add(participant);
            }
            return List.copyOf(result);
        }

        private void apply(List<RuleDispatcher.RecipientUpdate> updates) {
            if (updates.size() != 1) throw RuleJson.error("/recipients", "An ally move requires one recipient update.");
            var update = updates.getFirst();
            StagedRecipient participant = participants.get(update.actorId());
            if (participant == null || participant.sheet == null)
                throw RuleJson.error("/recipients", "The staged recipient was not read from the host roster.");
            if (participant.member.revision() == MAX_REST_REVISION)
                throw new RuleRuntime.Unavailable("revision", "The recipient's rest revision cannot be advanced.");
            writeRecipient(participant.sheet, update);
            projection.state(participant.sheet).put("revision", participant.member.revision());
            changed.add(update.actorId());
        }

        private ObjectNode reproject(RuleDispatcher.RecipientUpdate update, ObjectNode event) {
            StagedRecipient participant = participants.get(update.actorId());
            if (participant == null || participant.sheet == null)
                throw RuleJson.error("/recipients", "The staged recipient was not read from the host roster.");
            ObjectNode after = participant.sheet.deepCopy();
            writeRecipient(after, update);
            return recipientSnapshot(pack, after, event, update.actorId());
        }

        private Map<String, ObjectNode> changed() {
            Map<String, ObjectNode> result = new LinkedHashMap<>();
            for (String id : changed) {
                StagedRecipient participant = participants.get(id);
                ObjectNode sheet = participant.sheet.deepCopy();
                projection.state(sheet).put("revision", participant.member.revision() + 1);
                result.put(id, sheet);
            }
            return result;
        }

        private Map<String, String> readVersions() {
            Map<String, String> result = new LinkedHashMap<>();
            participants.forEach((id, participant) -> {
                if (participant.sheet != null) result.put(id, participant.member.sheetVersion());
            });
            return result;
        }

        private Map<String, List<String>> scopeMembership() {
            return copyScopeMembership(scopeMembership);
        }

        private Map<String, List<String>> scopeOrder() {
            return copyScopeMembership(scopeOrder);
        }
    }

    private final class StagedRecipient {
        private final RestMember member;
        private ObjectNode sheet;

        private StagedRecipient(RestMember member) { this.member = member; }

        private boolean sameVersion(RestMember other) {
            return member.name().equals(other.name()) && member.sheetVersion().equals(other.sheetVersion())
                    && member.revision() == other.revision();
        }

        private ObjectNode sheet() {
            if (sheet == null) {
                ObjectNode loaded = member.loadSheet().get();
                if (loaded == null) throw RuleJson.error("/recipients/" + member.id(), "The recipient sheet is missing.");
                ObjectNode copy = loaded.deepCopy();
                if (restRevision(copy) != member.revision())
                    throw new RuleRuntime.Unavailable("staleRecipient", "A character changed. Reopen this rest.");
                sheet = copy;
            }
            return sheet;
        }
    }

    /** Stages rest preparation only; the caller must complete and atomically commit the entire rest. */
    ObjectNode prepareRest(Pack pack, ObjectNode sheet, ObjectNode origin, String kind, long expectedRevision,
                           ArrayNode choices, RandomGenerator random) {
        return prepareRest(pack, sheet, origin, kind, expectedRevision, choices, random, null);
    }

    ObjectNode prepareRest(Pack pack, ObjectNode sheet, ObjectNode origin, String kind, long expectedRevision,
                           ArrayNode choices, RandomGenerator random, Set<String> initialEffectIds) {
        if (pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        if (!Set.of("short", "long").contains(kind)) throw RuleJson.error("/rest/kind", "Expected short or long.");
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        long revision = state.path("revision").asLong();
        if (expectedRevision < 0 || expectedRevision != revision)
            throw new RuleRuntime.Unavailable("stale", "The character changed. Reopen this rest.");
        if (RuleEffects.hasEarlyRestExpiry(projection.actorFact(working, "effects"), "rest." + kind, initialEffectIds))
            throw new RuleRuntime.Unavailable("restExpiry", "Rest effects that end before downtime must be released first.");
        int consecutive = working.has("shortRestsInRow")
                ? RuleJson.integer(working.get("shortRestsInRow"), "/sheet/shortRestsInRow", 0, 1_000_000) : 0;
        RestLimits limits = "short".equals(kind) ? pack.rest().shortRest() : pack.rest().longRest();
        if ("short".equals(kind) && consecutive >= limits.consecutiveLimit())
            throw new RuleRuntime.Unavailable("restLimit", "The party has taken " + limits.consecutiveLimit()
                    + " short rests in a row and must take a long rest next.");
        ObjectNode event = RuleJson.object(origin, "/event").deepCopy().put("type", "rest.prepare");
        ObjectNode plan = event.putObject("rest").put("kind", kind).put("moves", limits.moves()).put("longMoves", 0);
        ObjectNode start = initialRestFacts(pack.rest(), projection.actor(working));
        plan.set("start", start.deepCopy());
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
        if (!kind.equals(result.path("kind").asText()) || !adjustable.equals(result.path("adjustable"))
                || !start.equals(result.path("start")))
            throw RuleJson.error("/event/rest", "The rest plan's identity or bounds changed.");
        RuleJson.integer(result.get("moves"), "/event/rest/moves", 0, limits.maxMoves());
        RuleJson.integer(result.get("longMoves"), "/event/rest/longMoves", 0, limits.maxLongMoves());
        if (!response.path("event").path("blocked").isEmpty())
            throw new RuleRuntime.Unavailable("blocked", response.at("/event/blocked/0/reason").asText());
        replace(sheet, working);
        return response;
    }

    private static ObjectNode initialRestFacts(RestConfiguration configuration, ObjectNode actor) {
        ObjectNode start = RuleJson.JSON.createObjectNode();
        configuration.startFacts().forEach((name, path) -> {
            JsonNode value = SheetProjection.get(actor, path);
            String at = "/event/rest/start/" + name;
            validateRestFact(value, at);
            start.set(name, value.deepCopy());
        });
        return start;
    }

    private static void validateRestFact(JsonNode value, String at) {
        if (value == null || !value.isNumber() && !value.isBoolean() && !value.isTextual())
            throw RuleJson.error(at, "The configured initial actor fact must be a scalar value.");
        RuleJson.checkTree(value, at, 0, new int[]{0});
    }

    record RestMoveGroup(String sourceInstanceId, String group) {}
    record RestProgress(String kind, int moves, int longMoves, int slotsUsed, int longMovesUsed, int count,
                        Set<RestMoveGroup> groups, ObjectNode plan) {
        RestProgress { groups = Set.copyOf(groups); plan = plan.deepCopy(); }
        @Override public ObjectNode plan() { return plan.deepCopy(); }
    }
    record RestTarget(PartyTargets party, ObjectNode request,
                      BiFunction<RuleDispatcher.RecipientUpdate, ObjectNode, ObjectNode> reproject) {
        RestTarget {
            if (party == null || request == null || reproject == null)
                throw RuleJson.error("/target", "A host roster, target selection and recipient projection are required.");
            request = request.deepCopy();
        }
        @Override public ObjectNode request() { return request.deepCopy(); }
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
        ObjectNode start = RuleJson.object(plan.get("start"), "/event/rest/start");
        if (start.size() != pack.rest().startFacts().size())
            throw RuleJson.error("/event/rest/start", "The initial rest facts do not match the ruleset.");
        for (String name : pack.rest().startFacts().keySet())
            validateRestFact(start.get(name), "/event/rest/start/" + name);
        return new RestProgress(kind, moves, longMoves, 0, 0, 0, Set.of(), RuleJson.object(plan, "/event/rest"));
    }

    /** Stages one move. Progress and the caller's sheet change only after all move checks succeed. */
    RestMoveResult restMove(Pack pack, ObjectNode sheet, ObjectNode origin, RestProgress progress,
                            ObjectNode selection, ArrayNode options, RandomGenerator random) {
        return restMove(pack, sheet, origin, progress, selection, options, random, null);
    }

    RestMoveResult restMove(Pack pack, ObjectNode sheet, ObjectNode origin, RestProgress progress,
                            ObjectNode selection, ArrayNode options, RandomGenerator random, RestTarget target) {
        return restMove(pack, sheet, origin, progress, selection, options, random, target, sources(pack, sheet), false);
    }

    private RestMoveResult restMove(Pack pack, ObjectNode sheet, ObjectNode origin, RestProgress progress,
                                    ObjectNode selection, ArrayNode options, RandomGenerator random, RestTarget target,
                                    List<SheetProjection.SourceElement> initialSources, boolean settlePartyRecovery) {
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
        boolean targeted = binding.has("targetSelection");
        if (target == null && (targeted || (metadata.path("ally").asBoolean(false)
                && !metadata.path("narrative").asBoolean(false))))
            throw new RuleRuntime.Unavailable("restTarget", "This downtime move requires an authoritative recipient transaction.");
        if (target != null && (!targeted || !metadata.path("ally").asBoolean(false)))
            throw RuleJson.error("/target", "This downtime move does not select an ally.");
        if (target != null && !target.party().updates().isEmpty())
            throw new RuleRuntime.Unavailable("restTarget", "Apply the previous staged recipient update before another move.");
        if (!settlePartyRecovery && !metadata.path("ally").asBoolean(false) && hasScheduledRestRecovery(pack, initialSources, id))
            throw new RuleRuntime.Unavailable("restCommand",
                    "This downtime move requires party recovery and recipient track-change settlement, which are not available yet.");

        ObjectNode event = RuleJson.object(origin, "/event").deepCopy().put("type", "downtime.move");
        ObjectNode rest = progress.plan();
        event.set("rest", rest.deepCopy());
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
        ObjectNode inputs = selection.has("inputs") ? RuleJson.object(selection.get("inputs"), "/selection/inputs")
                : RuleJson.JSON.createObjectNode();
        if (target != null) {
            var candidate = byInstance.get(instanceId);
            var selected = new RuleRuntime.Intent(ruleId, bindingId, instanceId, revision(working), inputs);
            var chosen = target.party().resolve(binding, candidate.context(), selected, target.request());
            if (chosen.recipients().size() != 1)
                throw RuleJson.error("/target", "An ally move requires one other recipient.");
            ObjectNode recipient = chosen.recipients().getFirst().snapshot();
            List<RuleDispatcher.Candidate> withRecipient = new ArrayList<>();
            for (var source : candidates) {
                ObjectNode context = source.context();
                context.set("recipient", recipient.deepCopy());
                withRecipient.add(new RuleDispatcher.Candidate(source.program(), context));
            }
            candidates = withRecipient;
            byInstance.clear();
            candidates.forEach(candidateWithRecipient -> byInstance.put(candidateWithRecipient.instanceId(), candidateWithRecipient));
        }
        boolean observeOwner = target != null || needsOwnerMoveReactions(candidates);
        var adjustmentSources = observeOwner ? ownerMoveSources(candidates,
                intents.stream().map(RuleRuntime.Intent::sourceInstanceId).collect(java.util.stream.Collectors.toSet()), true)
                : candidates;
        RuleDispatcher.EventResolution adjustments;
        try {
            adjustments = observeOwner
                    ? RuleDispatcher.executeStagedMoveWithOwnerReactions(adjustmentSources, intents, random, pack.programs(),
                            ownerMoveProjection(pack, working, state, candidates))
                    : RuleDispatcher.executeStagedMove(candidates, intents, random, pack.programs());
        } catch (RuleRuntime.Unavailable failure) {
            throw target == null ? failure : targetRestFailure(failure);
        }
        ObjectNode result = applyResolved(pack, working, state, event, adjustments.contexts(),
                adjustments.actions(), adjustments.outbox());
        if (target != null) validateTargetRestCommands(result);
        ObjectNode adjusted = RuleJson.object(result.get("event"), "/event");
        validateRestMove(adjusted, move, rest, settlePartyRecovery);
        candidates = candidates(pack, working, adjusted);
        var candidate = candidates.stream().filter(item -> instanceId.equals(item.instanceId())
                        && element.sourceId().equals(item.program().sourceId())).findFirst()
                .orElseThrow(() -> new RuleRuntime.Unavailable("source", "This source is no longer active."));
        JsonNode currentBinding = RuleDispatcher.binding(candidate.program().rule(ruleId), bindingId);
        if (!metadata.equals(currentBinding.path("restMove")))
            throw new RuleRuntime.Unavailable("move", "This downtime move changed.");
        var intent = new RuleRuntime.Intent(ruleId, bindingId, instanceId, revision(working), inputs);
        ObjectNode after;
        List<RuleDispatcher.RecipientUpdate> recipientUpdates = List.of();
        if (target == null) {
            ObjectNode currentState = projection.state(working);
            if (observeOwner) {
                var selectedSources = ownerMoveSources(candidates, Set.of(instanceId), false);
                var resolution = RuleDispatcher.executeSelectedWithOwnerReactions(selectedSources, intent, random, pack.programs(),
                        ownerMoveProjection(pack, working, currentState, candidates));
                after = applyResolved(pack, working, currentState, adjusted,
                        resolution.contexts(), resolution.actions(), resolution.outbox());
            } else {
                var resolution = RuleRuntime.execute(candidate.program(), intent, candidate.context(), random, pack.programs());
                var action = new RuleDispatcher.ActionResult(instanceId, ruleId, bindingId, resolution.report(),
                        resolution.publishedRolls(), resolution.diceRolls());
                List<ObjectNode> commands = new ArrayList<>();
                resolution.context().path("outbox").forEach(command -> commands.add((ObjectNode) command));
                after = applyResolved(pack, working, currentState, adjusted,
                        List.of(resolution.context()), List.of(action), commands);
            }
        } else {
            var chosen = target.party().resolve(currentBinding, candidate.context(), intent, target.request());
            var resolved = RuleDispatcher.executeTargets(candidates, chosen.intent(), chosen.recipients(), random, pack.programs());
            if (resolved.recipients().size() != 1)
                throw RuleJson.error("/target", "An ally move requires one other recipient.");
            var actions = resolved.actions().stream().map(RuleDispatcher.TargetActionResult::action).toList();
            after = applyResolved(pack, working, projection.state(working), adjusted,
                    resolved.sourceContexts(), actions, resolved.outbox());
            for (int i = 0; i < resolved.actions().size(); i++)
                ((ObjectNode) after.path("actions").get(i)).put("recipientId", resolved.actions().get(i).recipientId());
            validateTargetRestCommands(after);
            recipientUpdates = resolved.recipients();
        }
        ObjectNode finalEvent = RuleJson.object(after.get("event"), "/event");
        validateRestMove(finalEvent, move, rest, settlePartyRecovery);
        int slots = finalEvent.at("/move/slots").intValue();
        if (progress.slotsUsed() + slots > progress.moves())
            throw new RuleRuntime.Unavailable("restMoves", "No downtime move slots remain.");
        for (String key : List.of("actions", "outbox")) ((ArrayNode) result.get(key)).addAll((ArrayNode) after.get(key));
        JsonNode completedMove = finalEvent.path("move").deepCopy();
        Map<JsonNode, ObjectNode> recipientViews = new HashMap<>();
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
            ObjectNode recipient = null;
            if (target != null) {
                var update = recipientUpdates.getFirst();
                ObjectNode currentEvent = finalEvent.deepCopy();
                recipient = recipientViews.computeIfAbsent(currentEvent,
                        ignored -> target.reproject().apply(update, currentEvent.deepCopy()));
                if (recipient == null || !update.actorId().equals(recipient.path("id").asText()))
                    throw RuleJson.error("/recipient", "The host must reproject the updated recipient.");
            }
            ObjectNode response;
            if (observeOwner) {
                var deferredCandidates = ownerMoveSources(candidates(pack, activeSources, currentState, currentActor, finalEvent),
                        Set.of(adjustment.sourceInstanceId()), false);
                if (recipient != null) {
                    List<RuleDispatcher.Candidate> withRecipient = new ArrayList<>();
                    for (var source : deferredCandidates) {
                        ObjectNode context = source.context();
                        context.set("recipient", recipient.deepCopy());
                        withRecipient.add(new RuleDispatcher.Candidate(source.program(), context));
                    }
                    deferredCandidates = withRecipient;
                }
                RuleDispatcher.EventResolution completed;
                try {
                    completed = RuleDispatcher.executeDeferredWithOwnerReactions(deferredCandidates, deferred, random,
                            pack.programs(), ownerRecoveryProjection(pack, working, currentState, activeSources,
                                    currentActor, finalEvent.path("actorId").asText(), true));
                } catch (RuleRuntime.Unavailable failure) {
                    throw target == null ? failure : targetRestFailure(failure);
                }
                response = applyResolved(pack, working, currentState, finalEvent,
                        completed.contexts(), completed.actions(), completed.outbox());
            } else {
                ObjectNode deferredContext = context(currentState, active, currentActor, finalEvent);
                var completed = RuleRuntime.executeAfter(original.program(), deferred,
                        deferredContext, random, pack.programs());
                var deferredAction = new RuleDispatcher.ActionResult(adjustment.sourceInstanceId(), adjustment.ruleId(),
                        adjustment.bindingId(), completed.report(), completed.publishedRolls(), completed.diceRolls());
                List<ObjectNode> deferredCommands = new ArrayList<>();
                completed.context().path("outbox").forEach(command -> deferredCommands.add((ObjectNode) command));
                response = applyResolved(pack, working, currentState, finalEvent,
                        List.of(completed.context()), List.of(deferredAction), deferredCommands);
            }
            if (target != null) validateTargetRestCommands(response);
            finalEvent = RuleJson.object(response.get("event"), "/event");
            validateRestMove(finalEvent, move, rest, settlePartyRecovery);
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
                progress.count() + 1, groups, rest);
        if (target != null) target.party().stage(recipientUpdates);
        replace(sheet, working);
        return new RestMoveResult(next, result);
    }

    private boolean needsOwnerMoveReactions(List<RuleDispatcher.Candidate> candidates) {
        if (!candidates.isEmpty() && !candidates.getFirst().context().path("actor").path("effects").isEmpty())
            return true;
        for (var candidate : candidates) {
            RuleCompiler.Program program = candidate.program();
            if (!program.automatic("track.changed").isEmpty() || program.stateMayAffectProjection()
                    || program.downtimeMayAddSource() || projection.readsMutableStats(program.downtimeStatReads()))
                return true;
        }
        return false;
    }

    private static List<RuleDispatcher.Candidate> ownerMoveSources(List<RuleDispatcher.Candidate> candidates,
                                                                    Set<String> selected, boolean automatic) {
        List<RuleDispatcher.Candidate> included = new ArrayList<>();
        for (var candidate : candidates) {
            RuleCompiler.Program program = candidate.program();
            if (selected.contains(candidate.instanceId()) || program.hasComputedActorPools()
                    || !program.automatic("track.changed").isEmpty()
                    || automatic && !program.automatic("downtime.move").isEmpty())
                included.add(candidate);
        }
        if (included.isEmpty() && !candidates.isEmpty()) included.add(candidates.getFirst());
        return included;
    }

    private RuleDispatcher.OwnerProjection ownerMoveProjection(Pack pack, ObjectNode sheet, ObjectNode state,
                                                                List<RuleDispatcher.Candidate> candidates) {
        ObjectNode actor = RuleJson.object(candidates.getFirst().context().get("actor"), "/actor");
        return ownerRecoveryProjection(pack, sheet, state, sources(pack, sheet), actor, actor.path("id").asText(), true);
    }

    private static void validateTargetRestCommands(ObjectNode response) {
        for (JsonNode command : response.path("outbox")) {
            String kind = command.path("kind").asText();
            if (!STAGED_TARGET_REST_COMMANDS.contains(kind))
                throw new RuleRuntime.Unavailable("restCommand",
                        "This ally move requires host settlement before later stages: " + kind + ".");
        }
    }

    private static RuleRuntime.Unavailable targetRestFailure(RuleRuntime.Unavailable failure) {
        if (Set.of("reactionCommand", "reactionLimits").contains(failure.code()))
            return new RuleRuntime.Unavailable("restCommand", failure.getMessage());
        return failure;
    }

    private record RestChoiceKey(String source, String rule, String binding, String each) {}

    /**
     * Completes an already staged rest on a private copy. The caller must commit its outbox, reports and sheet
     * atomically and must not expose this primitive as a complete player workflow.
     */
    ObjectNode completeRest(Pack pack, ObjectNode sheet, ObjectNode origin, RestProgress progress,
                            Set<String> initialEffectIds, ArrayNode choices, RandomGenerator random) {
        if (pack.rest() == null) throw new NotReady("The shared ruleset has no rest configuration.");
        if (progress == null) throw RuleJson.error("/rest", "A prepared rest is required.");
        if (initialEffectIds == null) throw RuleJson.error("/effects", "The initial rest effect snapshot is required.");
        if (choices == null) throw RuleJson.error("/request/selections", "Expected completion options.");
        ObjectNode working = sheet.deepCopy();
        ObjectNode plan = progress.plan();
        ObjectNode prepared = RuleJson.JSON.createObjectNode();
        prepared.putObject("event").put("type", "rest.prepare").set("rest", plan.deepCopy());
        RestProgress checked = restProgress(pack, prepared);
        if (!checked.kind().equals(progress.kind()) || checked.moves() != progress.moves()
                || checked.longMoves() != progress.longMoves() || progress.count() < 0 || progress.count() > 64
                || progress.slotsUsed() < 0 || progress.slotsUsed() > progress.moves()
                || progress.longMovesUsed() < 0 || progress.longMovesUsed() > progress.longMoves()
                || progress.slotsUsed() > progress.count() || progress.longMovesUsed() > progress.count()
                || progress.groups().size() != progress.count())
            throw RuleJson.error("/rest", "The staged rest progress is invalid.");
        if (RuleEffects.hasEarlyRestExpiry(projection.actorFact(working, "effects"),
                "rest." + progress.kind(), initialEffectIds))
            throw new RuleRuntime.Unavailable("restExpiry", "Rest effects that end before downtime were not released.");

        int consecutive = working.has("shortRestsInRow")
                ? RuleJson.integer(working.get("shortRestsInRow"), "/sheet/shortRestsInRow", 0, 1_000_000) : 0;
        if ("short".equals(progress.kind()) && consecutive >= pack.rest().shortRest().consecutiveLimit())
            throw new RuleRuntime.Unavailable("restLimit", "The party must take a long rest next.");
        int longRevision = "long".equals(progress.kind()) && working.has("longRestRevision")
                ? RuleJson.integer(working.get("longRestRevision"), "/sheet/longRestRevision", 0, Integer.MAX_VALUE - 1) : 0;

        ObjectNode beforeEvent = restCompletionEvent(pack, working, origin, plan, "beforeReset");
        var initialCandidates = candidates(pack, working, beforeEvent);
        ObjectNode request = RuleJson.JSON.createObjectNode();
        request.set("selections", choices);
        RuleJson.checkTree(request, "/request", 0, new int[]{0});
        var intents = selections(request, initialCandidates, revision(working), "self", "rest", false);
        Map<String, RuleDispatcher.Candidate> byInstance = new HashMap<>();
        initialCandidates.forEach(candidate -> byInstance.put(candidate.instanceId(), candidate));
        Set<RestChoiceKey> seen = new java.util.HashSet<>();
        Map<RestChoiceKey, String> approvedSources = new HashMap<>();
        Set<String> exclusive = new java.util.HashSet<>();
        ArrayNode beforeChoices = RuleJson.JSON.createArrayNode(), afterChoices = RuleJson.JSON.createArrayNode();
        for (int i = 0; i < intents.size(); i++) {
            var intent = intents.get(i);
            JsonNode binding = RuleDispatcher.binding(byInstance.get(intent.sourceInstanceId()).program().rule(intent.ruleId()),
                    intent.bindingId());
            if (!beforeEvent.path("type").equals(binding.path("event")) || !"option".equals(binding.path("mode").asText())
                    || binding.has("claimGroup") || binding.has("targetSelection"))
                throw RuleJson.error("/request/selections", "Choose only self-scoped rest completion options.");
            RestChoiceKey key = new RestChoiceKey(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(), intent.eachId());
            if (!seen.add(key))
                throw RuleJson.error("/request/selections", "A binding cannot be selected more than once.");
            approvedSources.put(key, byInstance.get(intent.sourceInstanceId()).program().sourceId());
            String group = binding.path("exclusiveGroup").asText(null);
            if (group != null && !exclusive.add(group))
                throw RuleJson.error("/request/selections", "Only one option in each exclusive group may be selected.");
            ("afterReset".equals(binding.path("restPhase").asText()) ? afterChoices : beforeChoices)
                    .add(choices.get(i).deepCopy());
        }

        ObjectNode response = RuleJson.JSON.createObjectNode();
        response.putArray("actions");
        response.putArray("outbox");
        response.putArray("skipped");
        runRestPhase(pack, working, beforeEvent, beforeChoices, approvedSources, response, random);
        ObjectNode settledBeforeEvent = RuleJson.object(response.get("event"), "/event");
        working.put("shortRestsInRow", "short".equals(progress.kind()) ? consecutive + 1 : 0);
        if ("long".equals(progress.kind())) working.put("longRestRevision", longRevision + 1);
        ObjectNode state = projection.state(working);
        state.put("revision", state.path("revision").asLong() + 1);
        resetRest(pack, working, settledBeforeEvent, initialEffectIds);
        ObjectNode afterOrigin = origin.deepCopy();
        carryRestTable(afterOrigin, settledBeforeEvent);
        ObjectNode afterEvent = restCompletionEvent(pack, working, afterOrigin, plan, "afterReset");
        runRestPhase(pack, working, afterEvent, afterChoices, approvedSources, response, random);
        response.put("revision", revision(working));
        replace(sheet, working);
        return response;
    }

    private ObjectNode restCompletionEvent(Pack pack, ObjectNode sheet, ObjectNode origin, ObjectNode plan, String phase) {
        ObjectNode event = RuleJson.object(origin, "/event").deepCopy().put("type", "rest." + plan.path("kind").asText());
        ObjectNode rest = plan.deepCopy().put("phase", phase);
        ObjectNode cleared = rest.putObject("cleared");
        ObjectNode actor = projection.actor(sheet);
        pack.rest().startFacts().forEach((name, path) -> {
            JsonNode start = rest.path("start").path(name);
            if (!start.isNumber()) return;
            String at = "/event/rest/cleared/" + name;
            double initial = RuleExpression.number(start, at);
            double current = RuleExpression.number(SheetProjection.get(actor, path), at);
            cleared.set(name, RuleExpression.numeric(initial - current, at));
        });
        event.set("rest", rest);
        return event;
    }

    private void runRestPhase(Pack pack, ObjectNode sheet, ObjectNode event, ArrayNode choices,
                              Map<RestChoiceKey, String> approvedSources, ObjectNode combined, RandomGenerator random) {
        var candidates = candidates(pack, sheet, event);
        ObjectNode request = RuleJson.JSON.createObjectNode();
        request.set("selections", choices);
        var intents = selections(request, candidates, revision(sheet), "self", "rest", false);
        Map<String, RuleDispatcher.Candidate> byInstance = new HashMap<>();
        candidates.forEach(candidate -> byInstance.put(candidate.instanceId(), candidate));
        List<RuleRuntime.Intent> active = new ArrayList<>();
        for (var intent : intents) {
            var candidate = byInstance.get(intent.sourceInstanceId());
            RestChoiceKey key = new RestChoiceKey(intent.sourceInstanceId(), intent.ruleId(), intent.bindingId(), intent.eachId());
            if (!candidate.program().sourceId().equals(approvedSources.get(key)))
                throw new RuleRuntime.Unavailable("source", "A selected rest benefit's source changed.");
            try {
                RuleRuntime.preflight(candidate.program(), intent, candidate.context());
                active.add(intent);
            } catch (RuleRuntime.Unavailable unavailable) {
                if (!"condition".equals(unavailable.code())) throw unavailable;
                RuleRuntime.validateSkippedInputs(candidate.program(), intent, candidate.context());
                ((ArrayNode) combined.get("skipped")).addObject().put("sourceInstanceId", intent.sourceInstanceId())
                        .put("ruleId", intent.ruleId()).put("bindingId", intent.bindingId())
                        .put("phase", RuleRuntime.restPhase(event)).put("reason", unavailable.getMessage());
            }
        }
        ObjectNode result = applyEvent(pack, sheet, projection.state(sheet), event, candidates, active, random);
        JsonNode finalEvent = result.path("event");
        if (!event.path("rest").equals(finalEvent.path("rest")))
            throw RuleJson.error("/event/rest", "A rest completion rule cannot change the prepared rest.");
        if (!finalEvent.path("blocked").isEmpty())
            throw new RuleRuntime.Unavailable("blocked", finalEvent.at("/blocked/0/reason").asText());
        for (String key : List.of("actions", "outbox")) ((ArrayNode) combined.get(key)).addAll((ArrayNode) result.get(key));
        combined.set("event", finalEvent.deepCopy());
    }

    private static void validateRestMove(ObjectNode event, ObjectNode initial, ObjectNode rest, boolean settlePartyRecovery) {
        if (!rest.equals(event.path("rest")))
            throw RuleJson.error("/event/rest", "A downtime move cannot change the prepared rest.");
        JsonNode move = event.path("move");
        for (String field : List.of("id", "rest", "long", "ally", "adjustable"))
            if (!initial.path(field).equals(move.path(field)))
                throw RuleJson.error("/event/move/" + field, "The move identity or bounds changed.");
        RuleJson.integer(move.get("slots"), "/event/move/slots", 0, 1);
        RuleJson.integer(move.get("bonus"), "/event/move/bonus", -1000, 1000);
        int allies = RuleJson.integer(move.get("allies"), "/event/move/allies", 0, 128);
        if (settlePartyRecovery) {
            partyRecoveryRequests(event);
        } else {
            boolean partyRecovery = false;
            int ignoredScheduledAllies = 0;
            for (JsonNode adjustment : move.path("adjustments")) {
                if (adjustment.path("requested").asInt() <= 0) continue;
                if (adjustment.path("partyRecovery").has("afterRest")) {
                    if ("allies".equals(adjustment.path("field").asText()))
                        ignoredScheduledAllies += adjustment.path("by").asInt();
                } else if (adjustment.has("partyRecovery") || "allies".equals(adjustment.path("field").asText())) {
                    partyRecovery = true;
                    break;
                }
            }
            partyRecovery |= allies > ignoredScheduledAllies;
            if (!move.path("ally").asBoolean() && partyRecovery)
                throw new RuleRuntime.Unavailable("restCommand",
                        "This downtime move requires party recovery and recipient track-change settlement, which are not available yet.");
        }
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

    /** Expires initial effects marked for early rest removal without resetting limits or later effects. */
    boolean expireRestBeforeMoves(Pack pack, ObjectNode sheet, ObjectNode event, Set<String> initialEffectIds) {
        if (initialEffectIds == null) throw RuleJson.error("/effects", "The initial rest effect snapshot is required.");
        String type = RuleJson.text(RuleJson.object(event, "/event").get("type"), "/event/type");
        if (!RuleRuntime.isRestEvent(type)) throw RuleJson.error("/event/type", "Expected a short or long rest.");
        Set<String> initial = Set.copyOf(initialEffectIds);
        if (!RuleEffects.hasEarlyRestExpiry(projection.actorFact(sheet, "effects"), type, initial)) return false;
        ObjectNode working = sheet.deepCopy();
        ObjectNode state = projection.state(working);
        ObjectNode actor = actor(pack, working, state, event);
        ArrayNode kept = RuleEffects.expireBeforeMoves(actor.path("effects"), type, initial);
        ObjectNode before = actor.deepCopy();
        actor.set("effects", kept);
        RuleEffects.reconcileHolder(before, actor, Set.of(), pack.programs(), "/actor");
        projection.write(working, projection.actor(working), persisted(actor));
        state = projection.state(working);
        state.put("revision", state.path("revision").asLong() + 1);
        replace(sheet, working);
        return true;
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
        for (var element : sources) {
            RuleCompiler.Program program = pack.programs().get(element.sourceId());
            if (program != null && program.hasComputedActorPools()) {
                ObjectNode actor = projectActor(pack, sheet, state, event, sources, false).actor();
                return actor.path("effects").isArray()
                        ? (ArrayNode) actor.path("effects").deepCopy() : RuleJson.JSON.createArrayNode();
            }
        }
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
        return projectActor(pack, sheet, state, event, sources, collectRollModifiers, projection.actor(sheet));
    }

    private ActorProjection projectActor(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                                         List<SheetProjection.SourceElement> sources, boolean collectRollModifiers,
                                         ObjectNode originalActor) {
        List<SheetProjection.SourceElement> computedPools = new ArrayList<>();
        ActorProjection result = projectActorView(pack, sheet, state, event, sources, collectRollModifiers,
                originalActor.deepCopy(), computedPools);
        if (computedPools.isEmpty()) return result;
        if (computedPools.size() > 1)
            computedPools.sort(java.util.Comparator.comparing(SheetProjection.SourceElement::instanceId));
        ObjectNode actor = result.actor();
        JsonNode before = actor.path("pools").deepCopy();
        for (SheetProjection.SourceElement element : computedPools) {
            ObjectNode view = context(state, element, actor, event);
            RuleRuntime.clampComputedActorPools(pack.programs().get(element.sourceId()), view);
            actor = (ObjectNode) view.get("actor");
        }
        if (!before.equals(actor.path("pools"))) {
            ObjectNode original = originalActor.deepCopy();
            original.set("pools", actor.path("pools").deepCopy());
            result = projectActorView(pack, sheet, state, event, sources, collectRollModifiers, original, null);
            actor = result.actor();
        }
        for (SheetProjection.SourceElement element : computedPools)
            RuleRuntime.requireStableComputedActorPools(pack.programs().get(element.sourceId()),
                    context(state, element, actor, event));
        return result;
    }

    private ActorProjection projectActorView(Pack pack, ObjectNode sheet, ObjectNode state, ObjectNode event,
                                             List<SheetProjection.SourceElement> sources, boolean collectRollModifiers,
                                             ObjectNode actor, List<SheetProjection.SourceElement> computedPools) {
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
            if (computedPools != null && program.hasComputedActorPools()) computedPools.add(element);
            for (JsonNode override : program.limitOverrides()) {
                String key = override.path("feature").asText() + ":" + override.path("limit").asText();
                limitOverrides.put(key, Math.max(limitOverrides.path(key).asInt(), override.path("max").asInt()));
            }
            if (program.hasModifiers()) {
                passiveSources.add(new PassiveProjection(element, program));
                readsDerivedTraits |= program.readsDerivedTraits();
            }
            derived.addAll(RuleEffects.projectedDerived(program, projectionInput(state, element, base, event)));
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
                    RuleEffects.projectedPassives(source.program(), projectionInput(state, source.source(), base, event), true)
                            .forEach(traitModifiers::add);
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
            ArrayNode values = RuleEffects.projectedPassives(source.program(), projectionInput(state, source.source(), base, event), false);
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
        return context(state, element, actor, event, true);
    }

    private static ObjectNode projectionInput(ObjectNode state, SheetProjection.SourceElement element, ObjectNode actor, ObjectNode event) {
        // Effect and passive projection validate and copy this private view before evaluating it.
        return context(state, element, actor, event, false);
    }

    private static ObjectNode context(ObjectNode state, SheetProjection.SourceElement element, ObjectNode actor,
                                      ObjectNode event, boolean copy) {
        ObjectNode context = RuleJson.JSON.createObjectNode();
        context.set("source", sourceContext(element));
        context.put("revision", state.path("revision").asLong());
        context.set("actor", copy ? actor.deepCopy() : actor);
        context.set("event", copy ? event.deepCopy() : event);
        JsonNode stored = state.path("sources").path(element.instanceId());
        for (String field : List.of("state", "usage", "sourcePools"))
            if (stored.path(field).isObject()) context.set(field, copy ? stored.get(field).deepCopy() : stored.get(field));
        return context;
    }

    private static ObjectNode sourceContext(SheetProjection.SourceElement element) {
        ObjectNode source = RuleJson.JSON.createObjectNode();
        source.put("id", element.sourceId()).put("instanceId", element.instanceId()).put("name", element.name());
        if (element.quantityField() != null) source.put("quantity", element.element().path(element.quantityField()).asInt(1));
        return source;
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
