package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleCompiler {
    public static final int FORMAT_VERSION = 2;
    private static final Set<String> EVENTS = Set.of("action.request", "roll.prepare", "roll.finished",
            "damage.prepare", "damage.marks", "damage.applied", "claim.accept", "rest.prepare", "rest.short", "rest.long", "scene.end", "session.start",
            "downtime.move", "downtime.completed", "rest.roll", "death.options", "track.changed", "pool.changed", "equipment.changed", "effect.started",
            "effect.ended", "creation.apply");
    static final Set<String> SURFACES = Set.of("sheet", "chat", "roll", "damage", "effect", "resource", "rest",
            "death", "downtime", "creation", "companion");
    static final Set<String> AUDIENCES = Set.of("self", "others", "party", "any");
    static final Set<String> CLAIM_RECIPIENTS = Set.of("otherPlayersCharacters", "playerCharacters", "sourceActor");
    static final Set<String> TARGET_SCOPES = Set.of("connectedPlayerCharacters", "activeCharacters", "activePlayerCharacters");
    /** Actor-owned lists that {@code collection.*} operations may change; each item is an object with an {@code id}. */
    static final Set<String> COLLECTIONS = Set.of("inventory", "features", "loadout", "vault", "heritage", "conditions", "companions");
    private static final Set<String> MODES = Set.of("button", "option", "automatic");
    private static final Set<String> TEXT_FIELDS = Set.of("label", "description", "report", "preview", "unavailable");
    private static final Set<String> COSTS = Set.of("resource.spend", "pool.spend", "track.mark", "inventory.consume");
    private static final Map<String, List<String>> OPERATIONS = Map.ofEntries(
            Map.entry("resource.spend", List.of("pool", "amount")),
            Map.entry("resource.gain", List.of("pool", "amount")),
            Map.entry("pool.spend", List.of("pool", "amount", "owner")),
            Map.entry("pool.gain", List.of("pool", "amount", "owner")),
            Map.entry("pool.roll", List.of("pool", "count", "sides", "keepHighest", "owner")),
            Map.entry("pool.removeDie", List.of("pool", "dieId", "owner")),
            Map.entry("track.mark", List.of("track", "amount")),
            Map.entry("track.clear", List.of("track", "amount")),
            Map.entry("inventory.consume", List.of("amount")),
            Map.entry("dice.roll", List.of("count", "sides")),
            Map.entry("dice.hits", List.of("count", "sides", "atLeast")),
            Map.entry("dice.highest", List.of("count", "sides")),
            Map.entry("dice.table", List.of("count", "outcomes")),
            Map.entry("recovery.offerRoll", List.of("roll", "track", "label", "offset")),
            Map.entry("downtime.offerRoll", List.of("roll", "label", "offset", "group")),
            Map.entry("inventory.grant", List.of("catalog", "entry", "quantity", "label", "to", "tag", "max", "scope")),
            Map.entry("state.set", List.of("slot", "value")),
            Map.entry("state.increment", List.of("slot", "by")),
            Map.entry("flag.set", List.of("flag", "value")),
            Map.entry("value", List.of("value")),
            Map.entry("roll.resolve", List.of("plan", "metadata")),
            Map.entry("roll.capture", List.of("from")),
            Map.entry("roll.check", List.of("from")),
            Map.entry("roll.addPoolDie", List.of("from", "pool", "dieId", "role", "label", "owner")),
            Map.entry("roll.keptDice", List.of("from", "role")),
            Map.entry("roll.derive", List.of("from", "keepSelectedWithin", "keep")),
            Map.entry("roll.publish", List.of("roll")),
            Map.entry("damage.publish", List.of("damage")),
            Map.entry("damage.reduce", List.of("from", "amount", "id", "label")),
            Map.entry("damage.increase", List.of("from", "amount", "id", "label")),
            Map.entry("damage.resolve", List.of("from", "thresholds", "armor", "hitPoints")),
            Map.entry("damage.assess", List.of("from", "type", "thresholds", "massive", "armor", "hitPoints")),
            Map.entry("damage.adjust", List.of("from", "field", "to", "id", "label")),
            Map.entry("marks.publish", List.of("marks")),
            Map.entry("damage.mark", List.of("from", "armor", "hitPoints")),
            Map.entry("set.subtract", List.of("from", "items")),
            Map.entry("effect.start", List.of("effect", "target")),
            Map.entry("effect.end", List.of("effect", "target")),
            Map.entry("effect.endLabeled", List.of("label", "target")),
            Map.entry("limit.reset", List.of("limit")),
            Map.entry("limit.modify", List.of("limit", "by")),
            Map.entry("track.capacity", List.of("track", "by", "counter", "amount", "direction")),
            Map.entry("collection.add", List.of("target", "collection", "item")),
            Map.entry("collection.remove", List.of("target", "collection", "item")),
            Map.entry("damage.deal", List.of("to", "amount", "label")),
            Map.entry("countdown.start", List.of("label", "value")),
            Map.entry("table.gain", List.of("resource", "amount", "label")),
            Map.entry("table.spend", List.of("resource", "amount", "label")),
            Map.entry("claim.offer", List.of("group", "label", "limit")),
            Map.entry("limits.refresh", List.of("on", "label")),
            Map.entry("limit.restore", List.of("to", "label")),
            Map.entry("roll.selectDice", List.of("from", "where")),
            Map.entry("roll.reroll", List.of("from", "dice", "choose")),
            Map.entry("roll.setFaces", List.of("from", "dice", "face")),
            Map.entry("roll.resize", List.of("from", "dice", "sides")),
            Map.entry("roll.explode", List.of("from", "dice", "when", "maxDice")),
            Map.entry("roll.addDice", List.of("from", "dice", "count")),
            Map.entry("roll.dropLowest", List.of("from", "dice", "count")),
            Map.entry("roll.addDicePerPool", List.of("from", "dice", "count")),
            Map.entry("roll.dropLowestPerPool", List.of("from", "dice", "count")),
            Map.entry("roll.contribute", List.of("from", "contribution")),
            Map.entry("roll.annotate", List.of("from", "field", "value")),
            Map.entry("roll.discard", List.of("from", "role")),
            Map.entry("roll.swapRoles", List.of("from", "first", "second")),
            Map.entry("roll.diceTotal", List.of("from", "dice")),
            Map.entry("plan.adjust", List.of("plan", "field", "by", "label", "partyRecovery")),
            Map.entry("event.block", List.of("reason")));
    /** Fields evaluated once per die with the {@code die} context root. */
    private static final Set<String> PER_DIE_FIELDS = Set.of("where", "face", "when");
    private static final Set<String> CONTRIBUTION_FIELDS = Set.of("id", "kind", "role", "label", "count", "sides",
            "keepHighest", "maximize", "sign", "value", "of", "factor", "editable");
    static final Set<String> RESET_EVENTS = Set.of("rest.short", "rest.long", "scene.end", "session.start");
    private static final java.util.regex.Pattern TRACK_FACT = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final java.util.regex.Pattern STATS = java.util.regex.Pattern.compile(
            "evasion|armorScore|thresholds\\.major|thresholds\\.severe|proficiency|hp\\.max|stress\\.max|hope\\.max|loadout\\.max"
                    + "|roll\\.(attack|damage|spellcast|action|reaction)|trait\\.(Agility|Strength|Finesse|Instinct|Presence|Knowledge)");

    /** Document-level declarations that operations and references may name. */
    record Decl(ObjectNode state, ObjectNode limits, ObjectNode pools, ObjectNode effects, boolean attachable) {
        Decl(ObjectNode state, ObjectNode limits, ObjectNode pools, ObjectNode effects) { this(state, limits, pools, effects, false); }

        static Decl empty() {
            return new Decl(RuleJson.JSON.createObjectNode(), RuleJson.JSON.createObjectNode(),
                    RuleJson.JSON.createObjectNode(), RuleJson.JSON.createObjectNode());
        }
    }

    public static final class Program {
        record BindingKey(String event, String surface) {}
        record BindingRef(String ruleId, String bindingId, boolean forEach, String audience, boolean option,
                          String claimGroup) {}
        record AutomaticBinding(String ruleId, String bindingId, int priority) {}
        record ScheduledRestRecovery(String ruleId, String bindingId, int ruleIndex, int stepIndex, String sourcePath,
                                     String move, String track, String scope, int amount, String label) {}

        private final String sourceId;
        private final ObjectNode initialState;
        private final ObjectNode limits;
        private final ObjectNode pools;
        private final boolean hasComputedActorPools;
        private final boolean computedActorPoolsReadSourcePools;
        private final boolean hasComputedSourcePools;
        private final Map<String, List<String>> poolsByEffect;
        private final Set<String> boundPoolIds;
        private final ObjectNode effects;
        private final ArrayNode modifiers;
        private final boolean hasTraitModifiers;
        private final boolean readsDerivedTraits;
        private final boolean downtimeMayAddSource;
        private final Set<String> downtimeStatReads;
        private final Map<String, ObjectNode> rules;
        private final Map<BindingKey, List<BindingRef>> bindingsByEventSurface;
        private final List<BindingRef> resultButtons;
        private final Map<String, List<AutomaticBinding>> automaticByEvent;
        private final Map<String, List<ScheduledRestRecovery>> restRecoveriesByMove;
        private ArrayNode profiles = RuleJson.JSON.createArrayNode();

        private Program(String sourceId, Decl decl, ArrayNode modifiers, Map<String, ObjectNode> rules) {
            this.sourceId = sourceId;
            this.initialState = decl.state().deepCopy();
            this.limits = decl.limits().deepCopy();
            this.pools = decl.pools().deepCopy();
            boolean computedActorPools = false;
            boolean readsSourcePools = false;
            boolean computedSourcePools = false;
            var poolDefinitions = this.pools.elements();
            while (poolDefinitions.hasNext()) {
                JsonNode definition = poolDefinitions.next();
                if ("actor".equals(definition.path("scope").asText()) && !definition.path("max").isInt()) {
                    computedActorPools = true;
                    readsSourcePools |= RuleExpression.references(definition.path("max")).stream()
                            .anyMatch(ref -> ref.startsWith("sourcePools."));
                } else if ("source".equals(definition.path("scope").asText()) && !definition.path("max").isInt())
                    computedSourcePools = true;
            }
            this.hasComputedActorPools = computedActorPools;
            this.computedActorPoolsReadSourcePools = readsSourcePools;
            this.hasComputedSourcePools = computedSourcePools;
            Map<String, List<String>> bound = new HashMap<>();
            this.pools.fields().forEachRemaining(entry -> {
                if (entry.getValue().has("whileEffect"))
                    bound.computeIfAbsent(entry.getValue().get("whileEffect").textValue(), ignored -> new ArrayList<>()).add(entry.getKey());
            });
            bound.replaceAll((key, ids) -> List.copyOf(ids));
            this.poolsByEffect = Map.copyOf(bound);
            Set<String> boundIds = new HashSet<>();
            bound.values().forEach(boundIds::addAll);
            this.boundPoolIds = Set.copyOf(boundIds);
            this.effects = decl.effects().deepCopy();
            this.modifiers = modifiers.deepCopy();
            boolean traitModifiers = false;
            for (JsonNode modifier : modifiers)
                if (modifier.path("stat").asText().startsWith("trait.")) { traitModifiers = true; break; }
            this.hasTraitModifiers = traitModifiers;
            this.readsDerivedTraits = containsDerivedTraitRead(modifiers);
            this.rules = new LinkedHashMap<>();
            rules.forEach((id, rule) -> this.rules.put(id, rule.deepCopy()));
            boolean downtimeAddition = false;
            Set<String> downtimeReads = new HashSet<>();
            for (ObjectNode rule : this.rules.values()) {
                boolean downtime = false;
                for (JsonNode binding : rule.path("bindings"))
                    downtime |= "downtime.move".equals(binding.path("event").asText());
                if (!downtime) continue;
                downtimeReads.addAll(RuleExpression.references(rule));
                rule.path("ui").fields().forEachRemaining(field ->
                        downtimeReads.addAll(RuleTemplate.references(field.getValue().asText())));
                if (rule.path("after").has("report"))
                    downtimeReads.addAll(RuleTemplate.references(rule.path("after").path("report").asText()));
                for (JsonNode steps : List.of(rule.path("costs"), rule.path("do"),
                        rule.path("after").path("costs"), rule.path("after").path("do")))
                    for (JsonNode step : steps) {
                        downtimeAddition |= "collection.add".equals(step.path("op").asText());
                        operationReferences(step, "/operation", false, false,
                                (refs, die, at) -> downtimeReads.addAll(refs));
                    }
            }
            this.downtimeMayAddSource = downtimeAddition;
            this.downtimeStatReads = downtimeReads.stream()
                    .filter(ref -> "actor".equals(ref) || "actor.stats".equals(ref) || ref.startsWith("actor.stats."))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Map<String, List<ScheduledRestRecovery>> recoveries = new HashMap<>();
            int ruleIndex = 0;
            for (var entry : this.rules.entrySet()) {
                ObjectNode rule = entry.getValue();
                for (int stepIndex = 0; stepIndex < rule.path("do").size(); stepIndex++) {
                    JsonNode step = rule.path("do").get(stepIndex);
                    JsonNode recovery = step.path("partyRecovery"), schedule = recovery.path("afterRest");
                    if (schedule.isMissingNode()) continue;
                    String move = schedule.path("move").asText();
                    var item = new ScheduledRestRecovery(entry.getKey(), rule.at("/bindings/0/id").asText(),
                            ruleIndex, stepIndex, schedule.path("sourcePath").asText(), move,
                            recovery.path("track").asText(), recovery.path("scope").asText(),
                            step.path("by").asInt(), RuleTemplate.render(step.path("label").asText(),
                            RuleJson.JSON.createObjectNode()));
                    recoveries.computeIfAbsent(move, ignored -> new ArrayList<>()).add(item);
                }
                ruleIndex++;
            }
            recoveries.replaceAll((key, items) -> List.copyOf(items));
            this.restRecoveriesByMove = Map.copyOf(recoveries);
            Map<BindingKey, List<BindingRef>> bindingIndex = new HashMap<>();
            List<BindingRef> results = new ArrayList<>();
            Map<String, List<AutomaticBinding>> automatic = new HashMap<>();
            this.rules.forEach((ruleId, rule) -> {
                for (JsonNode binding : rule.path("bindings")) {
                    String event = binding.path("event").asText(), mode = binding.path("mode").asText();
                    if ("automatic".equals(mode) && !binding.has("forEach"))
                        automatic.computeIfAbsent(event, ignored -> new ArrayList<>()).add(
                                new AutomaticBinding(ruleId, binding.path("id").asText(), binding.path("priority").asInt()));
                    if (!"button".equals(mode) && !"option".equals(mode)) continue;
                    BindingKey key = new BindingKey(event, binding.path("surface").asText());
                    var ref = new BindingRef(ruleId, binding.path("id").asText(), binding.has("forEach"),
                            binding.path("audience").asText("self"), "option".equals(mode),
                            binding.path("claimGroup").textValue());
                    bindingIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ref);
                    if ("roll.finished".equals(event) && ("chat".equals(key.surface()) || "roll".equals(key.surface()))
                            && !ref.option() && (ref.claimGroup() == null || selfClaim(binding)))
                        results.add(ref);
                }
            });
            Map<BindingKey, List<BindingRef>> immutableIndex = new HashMap<>();
            bindingIndex.forEach((key, refs) -> immutableIndex.put(key, List.copyOf(refs)));
            this.bindingsByEventSurface = Map.copyOf(immutableIndex);
            this.resultButtons = List.copyOf(results);
            automatic.replaceAll((key, refs) -> List.copyOf(refs));
            this.automaticByEvent = Map.copyOf(automatic);
        }

        private static boolean selfClaim(JsonNode binding) {
            JsonNode scope = binding.path("claimScope");
            String recipients = scope.path("recipients").asText("otherPlayersCharacters");
            return !scope.path("excludeSource").asBoolean(false)
                    && Set.of("sourceActor", "playerCharacters").contains(recipients);
        }

        public String sourceId() { return sourceId; }
        List<BindingRef> resultButtons() { return resultButtons; }
        public Set<String> ruleIds() { return Set.copyOf(rules.keySet()); }
        List<String> orderedRuleIds() { return List.copyOf(rules.keySet()); }
        List<AutomaticBinding> automatic(String event) { return automaticByEvent.getOrDefault(event, List.of()); }
        List<ScheduledRestRecovery> restRecoveries(String move) { return restRecoveriesByMove.getOrDefault(move, List.of()); }
        List<BindingRef> bindings(String event, String surface) {
            return bindingsByEventSurface.getOrDefault(new BindingKey(event, surface), List.of());
        }
        public ObjectNode initialState() { return initialState.deepCopy(); }
        ObjectNode limits() { return limits.deepCopy(); }
        ObjectNode pools() { return pools.deepCopy(); }
        boolean hasComputedActorPools() { return hasComputedActorPools; }
        boolean computedActorPoolsReadSourcePools() { return computedActorPoolsReadSourcePools; }
        boolean hasComputedSourcePools() { return hasComputedSourcePools; }
        boolean downtimeMayAddSource() { return downtimeMayAddSource; }
        Set<String> downtimeStatReads() { return downtimeStatReads; }
        boolean stateMayAffectProjection() {
            return hasComputedActorPools || hasComputedSourcePools || !modifiers.isEmpty() || !effects.isEmpty();
        }
        List<String> poolsBoundTo(String effectId) { return poolsByEffect.getOrDefault(effectId, List.of()); }
        Set<String> boundPoolIds() { return boundPoolIds; }
        boolean hasEffect(String effectId) { return effects.has(effectId); }
        ObjectNode effects() { return effects.deepCopy(); }
        boolean hasModifiers() { return !modifiers.isEmpty(); }
        boolean hasTraitModifiers() { return hasTraitModifiers; }
        boolean readsDerivedTraits() { return readsDerivedTraits; }
        ArrayNode modifiers() { return modifiers.deepCopy(); }
        /** Additional attack profiles the source offers while it is active. */
        public ArrayNode profiles() { return profiles.deepCopy(); }
        private Program withProfiles(ArrayNode list) { profiles = list.deepCopy(); return this; }
        private ObjectNode attachment;
        /** The attachment declaration, or {@code null} when the source is not attached to gear. */
        public ObjectNode attachment() { return attachment == null ? null : attachment.deepCopy(); }
        private Program withAttachment(ObjectNode declaration) { attachment = declaration == null ? null : declaration.deepCopy(); return this; }
        private ArrayNode limitOverrides = RuleJson.JSON.createArrayNode();
        /** Limits of other sheet features this source raises while present. */
        public ArrayNode limitOverrides() { return limitOverrides.deepCopy(); }
        private Program withLimitOverrides(ArrayNode list) { limitOverrides = list.deepCopy(); return this; }
        private ObjectNode grants;
        /** Features granted alongside this source ({@code features}, {@code display}), or {@code null}. Sheet metadata only. */
        public ObjectNode grants() { return grants == null ? null : grants.deepCopy(); }
        private Program withGrants(ObjectNode declaration) { grants = declaration == null ? null : declaration.deepCopy(); return this; }

        /**
         * Reasons this source cannot be attached to the described gear, in declaration order; empty when it may attach.
         * {@code gear} supplies {@code kind}, {@code name}, {@code range}, {@code damageType}, {@code hasFeature},
         * {@code generated} and {@code hasTraitAttachment}; requirement conditions and reasons read it as {@code gear.*}.
         */
        public List<String> attachmentBlockers(JsonNode gear, JsonNode source) {
            if (attachment == null) return List.of("This source cannot be attached.");
            ObjectNode context = RuleJson.JSON.createObjectNode();
            context.set("gear", gear.deepCopy());
            context.set("source", source.deepCopy());
            List<String> reasons = new ArrayList<>();
            if (!attachment.path("to").asText().equals(gear.path("kind").asText()))
                reasons.add("Attach this to " + attachment.path("to").asText() + " gear.");
            for (JsonNode requirement : attachment.path("requires"))
                if (!RuleExpression.evaluate(requirement.get("when"), context).asBoolean())
                    reasons.add(RuleTemplate.render(requirement.path("reason").asText(), context));
            return reasons;
        }
        ObjectNode effect(String id) {
            JsonNode effect = effects.get(id);
            if (effect == null) throw RuleJson.error("/effect", "Unknown effect: " + id);
            return (ObjectNode) effect.deepCopy();
        }
        ObjectNode pool(String id) {
            JsonNode pool = pools.get(id);
            if (pool == null) throw RuleJson.error("/pool", "Unknown pool: " + id);
            return RuleJson.object(pool, "/automation/pools/" + id).deepCopy();
        }
        ObjectNode rule(String id) {
            ObjectNode rule = rules.get(id);
            if (rule == null) throw RuleJson.error("/ruleId", "Unknown rule: " + id);
            return rule.deepCopy();
        }
    }

    private RuleCompiler() {}

    public static ObjectNode parseDocument(String text, String format) throws IOException {
        ObjectNode document = RuleJson.object(RuleJson.parse(text, RuleJson.MAX_DOCUMENT_CHARACTERS, RuleJson.MAX_DOCUMENT_NODES), "/");
        if (!format.equals(document.path("format").asText()))
            throw RuleJson.error("/format", "Expected " + format + ".");
        if (!document.path("version").isIntegralNumber() || document.path("version").longValue() != FORMAT_VERSION)
            throw RuleJson.error("/version", "Expected format " + FORMAT_VERSION + "; convert the complete catalog pack before loading.");
        return document;
    }

    public static Program compile(String sourceId, JsonNode automation, JsonNode recipeDocument) {
        return compileWithRecipes(sourceId, automation, recipes(recipeDocument));
    }

    static Program compileWithRecipes(String sourceId, JsonNode automation, Map<String, ObjectNode> recipes) {
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(sourceId), "/sourceId");
        RuleJson.checkTree(automation, "/automation", 0, new int[]{0});
        RuleJson.fields(automation, "/automation", "state", "limits", "pools", "effects", "modifiers", "profiles", "rules", "attachment", "grants", "limitOverrides");
        ObjectNode grants = automation.has("grants") ? grants(automation.get("grants")) : null;
        ArrayNode limitOverrides = automation.has("limitOverrides") ? limitOverrides(automation.get("limitOverrides")) : RuleJson.JSON.createArrayNode();
        ObjectNode attachment = automation.has("attachment") ? attachment(automation.get("attachment")) : null;
        ObjectNode state = automation.has("state") ? RuleJson.object(automation.get("state"), "/automation/state") : RuleJson.JSON.createObjectNode();
        state.fieldNames().forEachRemaining(slot -> slot(slot, "/automation/state"));
        ObjectNode pools = automation.has("pools") ? RuleJson.object(automation.get("pools"), "/automation/pools") : RuleJson.JSON.createObjectNode();
        pools.fields().forEachRemaining(field -> {
            String id = RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(field.getKey()), "/automation/pools");
            String path = "/automation/pools/" + id;
            RuleJson.fields(field.getValue(), path, "kind", "scope", "label", "max", "initial", "resetOn", "resetTo", "section", "whileEffect");
            if (field.getValue().has("whileEffect")) {
                if (!"number".equals(field.getValue().path("kind").asText()) || !"actor".equals(field.getValue().path("scope").asText()))
                    throw RuleJson.error(path + "/whileEffect", "Only numeric actor pools can follow an effect.");
                String bound = RuleJson.id(field.getValue().get("whileEffect"), path + "/whileEffect");
                if (!automation.path("effects").has(bound)) throw RuleJson.error(path + "/whileEffect", "Unknown effect: " + bound);
            }
            if (field.getValue().has("section") && !Set.of("feature", "class").contains(field.getValue().path("section").asText()))
                throw RuleJson.error(path + "/section", "Expected feature or class.");
            if (field.getValue().has("label")) RuleTemplate.validate(RuleJson.text(field.getValue().get("label"), path + "/label"));
            String kind = RuleJson.text(field.getValue().get("kind"), path + "/kind");
            if (!Set.of("number", "dice").contains(kind)) throw RuleJson.error(path + "/kind", "Expected number or dice.");
            String scope = RuleJson.text(field.getValue().get("scope"), path + "/scope");
            if (!Set.of("source", "actor").contains(scope)) throw RuleJson.error(path + "/scope", "Expected source or actor scope.");
            JsonNode max = field.getValue().get("max");
            if ("number".equals(kind) && max != null && !max.isNumber()) RuleExpression.validate(max, path + "/max");
            else RuleJson.integer(max, path + "/max", 1, "dice".equals(kind) ? 1024 : 1_000_000);
            if (field.getValue().has("initial")) {
                if (!"number".equals(kind)) throw RuleJson.error(path + "/initial", "Only numeric pools have an initial value.");
                RuleJson.integer(field.getValue().get("initial"), path + "/initial", 0, max.isNumber() ? max.asInt() : 1_000_000);
            }
            if (field.getValue().has("resetOn")) resetEvents(field.getValue().get("resetOn"), path + "/resetOn");
            if (field.getValue().has("resetTo")) {
                if (!"number".equals(kind)) throw RuleJson.error(path + "/resetTo", "Only numeric pools have a reset value.");
                if (!field.getValue().has("resetOn")) throw RuleJson.error(path + "/resetTo", "A reset value needs resetOn.");
                RuleExpression.validate(field.getValue().get("resetTo"), path + "/resetTo");
            }
        });
        ObjectNode limits = automation.has("limits") ? RuleJson.object(automation.get("limits"), "/automation/limits") : RuleJson.JSON.createObjectNode();
        limits.fields().forEachRemaining(field -> {
            RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(field.getKey()), "/automation/limits");
            String path = "/automation/limits/" + field.getKey();
            RuleJson.fields(field.getValue(), path, "max", "resetOn");
            RuleJson.integer(field.getValue().get("max"), path + "/max", 1, 1000);
            resetEvents(field.getValue().path("resetOn"), path + "/resetOn");
        });
        ObjectNode effects = automation.has("effects") ? RuleJson.object(automation.get("effects"), "/automation/effects") : RuleJson.JSON.createObjectNode();
        if (effects.size() > 64) throw RuleJson.error("/automation/effects", "Expected at most 64 effects.");
        effects.fields().forEachRemaining(field -> validateEffect(field.getKey(), field.getValue(), state, effects));
        ArrayNode modifiers = automation.has("modifiers") ? modifierList(automation.get("modifiers"), "/automation/modifiers", state, true) : RuleJson.JSON.createArrayNode();
        Decl decl = new Decl(state, limits, pools, effects, attachment != null);
        ArrayNode profiles = automation.has("profiles") ? profileList(automation.get("profiles"), limits) : RuleJson.JSON.createArrayNode();
        JsonNode definitions = automation.path("rules");
        boolean derived = false;
        for (JsonNode effect : effects) derived |= effect.has("while");
        if (definitions.isMissingNode() && (!modifiers.isEmpty() || !pools.isEmpty() || !profiles.isEmpty() || attachment != null || grants != null || derived || !limitOverrides.isEmpty()))
            return new Program(sourceId, decl, modifiers, Map.of()).withProfiles(profiles).withAttachment(attachment).withGrants(grants).withLimitOverrides(limitOverrides);
        if (!definitions.isArray() || definitions.isEmpty() || definitions.size() > 256)
            throw RuleJson.error("/automation/rules", "Expected 1-256 rules (or only passive modifiers, pools, profiles, derived effects, grants and an attachment).");
        Map<String, ObjectNode> expanded = new LinkedHashMap<>();
        int[] expandedNodes = {0};
        Set<String> localIds = new HashSet<>();
        for (int i = 0; i < definitions.size(); i++) {
            JsonNode definition = definitions.get(i);
            String path = "/automation/rules/" + i;
            String id = RuleJson.id(definition.path("id"), path + "/id");
            if (!localIds.add(id)) throw RuleJson.error(path + "/id", "Duplicate rule ID.");
            if (definition.has("useRecipe")) {
                RuleJson.fields(definition, path, "id", "useRecipe", "args", "ui");
                String recipeId = RuleJson.text(definition.get("useRecipe"), path + "/useRecipe");
                ObjectNode recipe = recipes.get(recipeId);
                if (recipe == null) throw RuleJson.error(path + "/useRecipe", "Unknown recipe: " + recipeId);
                ObjectNode args = arguments(recipe.path("args"), definition.path("args"), path);
                ObjectNode ui = mergedUi(recipe.path("ui"), definition.path("ui"), path);
                for (JsonNode child : recipe.path("rules")) {
                    ObjectNode rule = RuleJson.object(substitute(child, args, ui, path), path);
                    rule.put("id", id + "." + RuleJson.id(child.path("id"), path + "/recipe/id"));
                    addRule(expanded, rule, args, decl, path, expandedNodes);
                }
            } else addRule(expanded, RuleJson.object(definition, path).deepCopy(), RuleJson.JSON.createObjectNode(), decl, path, expandedNodes);
        }
        if (expanded.size() > 256) throw RuleJson.error("/automation/rules", "Expanded rules exceed the limit.");
        Set<String> claimGroups = new HashSet<>();
        for (ObjectNode rule : expanded.values()) for (JsonNode binding : rule.path("bindings"))
            if (binding.has("claimGroup") && "claim.accept".equals(binding.path("event").asText())
                    && "chat".equals(binding.path("surface").asText()))
                claimGroups.add(binding.path("claimGroup").asText());
        for (ObjectNode rule : expanded.values()) for (JsonNode operations : List.of(rule.path("do"), rule.path("after").path("do")))
            for (JsonNode operation : operations)
                if ("claim.offer".equals(operation.path("op").asText()) && !claimGroups.contains(operation.path("group").asText()))
                    throw RuleJson.error("/automation/rules/" + rule.path("id").asText() + "/do",
                            "A claim offer must name a claim.accept chat group declared by this source: " + operation.path("group").asText());
        return new Program(sourceId, decl, modifiers, expanded).withProfiles(profiles).withAttachment(attachment).withGrants(grants).withLimitOverrides(limitOverrides);
    }

    /** Raises another sheet feature's use limit while this source is present: {@code [{feature, limit, max}]}. */
    private static ArrayNode limitOverrides(JsonNode declaration) {
        String path = "/automation/limitOverrides";
        if (!declaration.isArray() || declaration.isEmpty() || declaration.size() > 16) throw RuleJson.error(path, "Expected 1-16 limit overrides.");
        for (int i = 0; i < declaration.size(); i++) {
            String at = path + "/" + i;
            RuleJson.fields(declaration.get(i), at, "feature", "limit", "max");
            RuleJson.text(declaration.get(i).get("feature"), at + "/feature");
            RuleJson.id(declaration.get(i).get("limit"), at + "/limit");
            RuleJson.integer(declaration.get(i).get("max"), at + "/max", 1, 1000);
        }
        return (ArrayNode) declaration.deepCopy();
    }

    /** Sheet metadata: features the character gains together with this source, and whether they display grouped under it. */
    private static ObjectNode grants(JsonNode declaration) {
        String path = "/automation/grants";
        RuleJson.fields(declaration, path, "features", "display");
        JsonNode features = declaration.path("features");
        if (!features.isArray() || features.isEmpty() || features.size() > 16) throw RuleJson.error(path + "/features", "Expected 1-16 feature names.");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < features.size(); i++)
            if (!names.add(RuleJson.text(features.get(i), path + "/features/" + i))) throw RuleJson.error(path + "/features/" + i, "Duplicate feature name.");
        if (declaration.has("display") && !Set.of("grouped", "separate").contains(declaration.path("display").asText()))
            throw RuleJson.error(path + "/display", "Expected grouped or separate.");
        return (ObjectNode) declaration.deepCopy();
    }

    private static final Set<String> GEAR_FACTS = Set.of("kind", "name", "range", "damageType", "hasFeature", "generated", "hasTraitAttachment");
    private static final Set<String> ATTACHED_TO_FACTS = Set.of("id", "instanceId", "kind", "name");

    /**
     * A source that is attached to a piece of gear instead of being active on its own. Its modifiers, profiles and
     * rules apply only while attached; the gear shows {@code feature} and, with {@code trait}, attacks with that trait.
     */
    private static ObjectNode attachment(JsonNode declaration) {
        String path = "/automation/attachment";
        RuleJson.fields(declaration, path, "to", "requires", "feature", "trait");
        RuleJson.id(declaration.path("to"), path + "/to");
        JsonNode requires = declaration.path("requires");
        if (declaration.has("requires") && (!requires.isArray() || requires.size() > 16))
            throw RuleJson.error(path + "/requires", "Expected at most 16 requirements.");
        for (int i = 0; i < requires.size(); i++) {
            String at = path + "/requires/" + i;
            RuleJson.fields(requires.get(i), at, "when", "reason");
            RuleExpression.validate(requires.get(i).path("when"), at + "/when");
            String reason = RuleJson.text(requires.get(i).get("reason"), at + "/reason");
            RuleTemplate.validate(reason);
            Set<String> refs = new HashSet<>(RuleExpression.references(requires.get(i).get("when")));
            refs.addAll(RuleTemplate.references(reason));
            for (String ref : refs) {
                String[] parts = ref.split("\\.");
                boolean valid = "gear".equals(parts[0]) && parts.length == 2 && GEAR_FACTS.contains(parts[1])
                        || "source".equals(parts[0]) && Set.of("id", "name").contains(parts[1]);
                if (!valid) throw RuleJson.error(at, "Attachment requirements may only read gear facts and the source: " + ref);
            }
        }
        if (declaration.has("feature")) {
            RuleJson.fields(declaration.get("feature"), path + "/feature", "name", "text");
            for (String field : List.of("name", "text")) RuleJson.text(declaration.get("feature").get(field), path + "/feature/" + field);
        }
        if (declaration.has("trait") && !Set.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge").contains(declaration.path("trait").asText()))
            throw RuleJson.error(path + "/trait", "Unknown trait.");
        return (ObjectNode) declaration.deepCopy();
    }

    private static void validateEffect(String id, JsonNode effect, ObjectNode state, ObjectNode effects) {
        RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/automation/effects");
        String path = "/automation/effects/" + id;
        RuleJson.fields(effect, path, "label", "text", "kind", "until", "stacking", "modifiers", "profile",
                "while", "endWhen", "exclusiveGroup", "singleHolder", "locks", "restExpiry");
        if (effect.has("endWhen")) {
            if (effect.has("while")) throw RuleJson.error(path + "/endWhen", "A derived effect (with while) cannot also declare endWhen.");
            RuleExpression.validate(effect.get("endWhen"), path + "/endWhen");
            for (String ref : RuleExpression.references(effect.get("endWhen"))) {
                String root = ref.split("\\.")[0];
                if (!Set.of("source", "actor", "state", "sourcePools").contains(root) || "state".equals(root) && !state.has(ref.split("\\.")[1]))
                    throw RuleJson.error(path + "/endWhen", "An effect end condition may only read source, actor, source pools and declared state: " + ref);
            }
        }
        if (effect.has("while")) {
            for (String field : List.of("until", "stacking", "exclusiveGroup", "singleHolder", "locks", "restExpiry"))
                if (effect.has(field)) throw RuleJson.error(path + "/" + field, "A derived effect (with while) is active exactly while its condition holds; it cannot also declare " + field + ".");
            RuleExpression.validate(effect.get("while"), path + "/while");
            for (String ref : RuleExpression.references(effect.get("while"))) {
                String root = ref.split("\\.")[0];
                if (!Set.of("source", "actor", "state", "sourcePools").contains(root) || "state".equals(root) && !state.has(ref.split("\\.")[1]))
                    throw RuleJson.error(path + "/while", "A derived effect condition may only read source, actor, source pools and declared state: " + ref);
            }
        }
        if (effect.has("exclusiveGroup")) RuleJson.id(effect.get("exclusiveGroup"), path + "/exclusiveGroup");
        for (String text : List.of("label", "text")) if (effect.has(text) || "label".equals(text)) {
            String template = RuleJson.text(effect.get(text), path + "/" + text);
            RuleTemplate.validate(template);
            for (String ref : RuleTemplate.references(template))
                if (!ref.startsWith("source.") && !ref.startsWith("actor.") && !(ref.startsWith("state.") && state.has(ref.split("\\.")[1])))
                    throw RuleJson.error(path + "/" + text, "Effect text may only read source, actor and declared state: " + ref);
        }
        if (effect.has("kind") && !Set.of("buff", "debuff").contains(effect.path("kind").asText()))
            throw RuleJson.error(path + "/kind", "Expected buff or debuff.");
        if (effect.has("stacking") && !Set.of("replace", "replaceAnySource", "stack", "unique").contains(effect.path("stacking").asText()))
            throw RuleJson.error(path + "/stacking", "Expected replace, replaceAnySource, stack or unique.");
        if (effect.has("singleHolder") && !effect.get("singleHolder").isBoolean()) throw RuleJson.error(path + "/singleHolder", "Expected a boolean.");
        if (effect.has("until")) resetEvents(effect.get("until"), path + "/until");
        if (effect.has("restExpiry")) {
            if (!"beforeMoves".equals(effect.path("restExpiry").asText()))
                throw RuleJson.error(path + "/restExpiry", "Expected beforeMoves.");
            boolean rests = false;
            for (JsonNode event : effect.path("until"))
                rests |= "rest.short".equals(event.asText()) || "rest.long".equals(event.asText());
            if (!rests) throw RuleJson.error(path + "/restExpiry", "Early expiry requires at least one rest event.");
        }
        if (effect.has("locks")) {
            ObjectNode locks = RuleJson.object(effect.get("locks"), path + "/locks");
            if (locks.isEmpty() || locks.size() > 8) throw RuleJson.error(path + "/locks", "Expected 1-8 track locks.");
            locks.fields().forEachRemaining(lock -> {
                String at = path + "/locks/" + lock.getKey();
                trackLockName(lock.getKey(), at);
                RuleExpression.validate(lock.getValue(), at);
                for (String ref : RuleExpression.references(lock.getValue())) {
                    String[] parts = ref.split("\\.");
                    if (!Set.of("source", "actor", "state", "effects").contains(parts[0])
                            || "state".equals(parts[0]) && (parts.length < 2 || !state.has(parts[1])))
                        throw RuleJson.error(at, "A track lock may only read source, actor, effects and declared state: " + ref);
                }
            });
        }
        if (effect.has("profile")) validateProfile(effect.get("profile"), path + "/profile");
        if (effect.has("modifiers")) {
            ArrayNode list = modifierList(effect.get("modifiers"), path + "/modifiers", state, false);
            for (JsonNode modifier : list) if (modifier.has("when"))
                throw RuleJson.error(path + "/modifiers", "Effect modifiers are resolved when the effect starts; use separate effects instead of conditions.");
        }
    }

    static void trackLockName(String name, String path) {
        if (!TRACK_FACT.matcher(name).matches())
            throw RuleJson.error(path, "Expected a projected track fact name.");
    }

    private static ArrayNode profileList(JsonNode list, ObjectNode limits) {
        if (!list.isArray() || list.isEmpty() || list.size() > 8) throw RuleJson.error("/automation/profiles", "Expected 1-8 roll profiles.");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "/automation/profiles/" + i;
            if (!ids.add(RuleJson.id(list.get(i).path("id"), path + "/id"))) throw RuleJson.error(path + "/id", "Duplicate profile ID.");
            RuleJson.fields(list.get(i), path, "id", "label", "trait", "range", "damage", "rollKind", "difficulty", "origin",
                    "limitRef", "consumeOn");
            validateProfileBody(list.get(i), path);
            JsonNode profile = list.get(i);
            if (profile.has("limitRef")) {
                String limit = RuleJson.id(profile.get("limitRef"), path + "/limitRef");
                if (!limits.has(limit)) throw RuleJson.error(path + "/limitRef", "Unknown use budget: " + limit);
                if (!profile.has("rollKind") || !Set.of("attempt", "success").contains(profile.path("consumeOn").asText()))
                    throw RuleJson.error(path + "/consumeOn", "An action-roll limit must be consumed on attempt or success.");
                if ("success".equals(profile.path("consumeOn").asText()) && !profile.has("difficulty"))
                    throw RuleJson.error(path + "/difficulty", "A success-limited roll needs a difficulty.");
            } else if (profile.has("consumeOn"))
                throw RuleJson.error(path + "/consumeOn", "Consumption requires a use budget.");
        }
        return (ArrayNode) list.deepCopy();
    }

    static void validateProfile(JsonNode profile, String path) {
        RuleJson.fields(profile, path, "label", "trait", "range", "damage");
        validateProfileBody(profile, path);
    }

    /**
     * An effect profile or a source profile without a roll kind describes an attack. A source may instead declare
     * an action, reaction or spellcast roll. Attack damage count defaults to the actor's proficiency.
     */
    private static void validateProfileBody(JsonNode profile, String path) {
        RuleJson.text(profile.get("label"), path + "/label");
        boolean actionRoll = profile.has("rollKind");
        if (actionRoll && !Set.of("action", "reaction", "spellcast").contains(profile.path("rollKind").asText()))
            throw RuleJson.error(path + "/rollKind", "Expected action, reaction or spellcast.");
        if (!actionRoll && (profile.has("difficulty") || profile.has("origin")))
            throw RuleJson.error(path, "Difficulty and origin require an action-roll profile.");
        if (actionRoll && profile.has("damage"))
            throw RuleJson.error(path + "/damage", "An action-roll profile cannot declare attack damage.");
        if (profile.has("difficulty")) RuleJson.integer(profile.get("difficulty"), path + "/difficulty", 0, 1_000_000);
        if (profile.has("origin")) RuleJson.text(profile.get("origin"), path + "/origin");
        String trait = profile.path("trait").asText();
        if (profile.has("trait") && !Set.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge").contains(trait)
                && !(actionRoll && "spellcast".equals(trait)))
            throw RuleJson.error(path + "/trait", "Unknown trait.");
        if (profile.has("range") && !Set.of("melee", "veryClose", "close", "far", "veryFar").contains(profile.path("range").asText()))
            throw RuleJson.error(path + "/range", "Unknown range.");
        if (profile.has("damage")) {
            JsonNode damage = profile.get("damage");
            RuleJson.fields(damage, path + "/damage", "count", "sides", "flat", "type");
            if (damage.has("count")) RuleJson.integer(damage.get("count"), path + "/damage/count", 1, 32);
            RuleJson.integer(damage.get("sides"), path + "/damage/sides", 2, 100);
            if (damage.has("flat")) RuleJson.integer(damage.get("flat"), path + "/damage/flat", -100, 100);
            if (damage.has("type") && !Set.of("physical", "magic").contains(damage.path("type").asText()))
                throw RuleJson.error(path + "/damage/type", "Expected physical or magic.");
        }
    }

    private static void resetEvents(JsonNode resets, String path) {
        if (!resets.isArray() || resets.isEmpty()) throw RuleJson.error(path, "Expected reset events.");
        Set<String> unique = new HashSet<>();
        for (JsonNode event : resets)
            if (!RESET_EVENTS.contains(event.asText()) || !unique.add(event.asText()))
                throw RuleJson.error(path, "Unknown or duplicate reset event.");
    }

    private static boolean readsDerivedTrait(String ref) {
        return "actor".equals(ref) || "actor.stats".equals(ref) || "actor.stats.trait".equals(ref)
                || ref.startsWith("actor.stats.trait.") || "actor.stats.spellcast".equals(ref)
                || "actor.stats.spellcast.total".equals(ref);
    }

    private static boolean containsDerivedTraitRead(JsonNode modifiers) {
        for (JsonNode modifier : modifiers) {
            for (String field : List.of("value", "when"))
                if (modifier.has(field) && RuleExpression.references(modifier.get(field)).stream().anyMatch(RuleCompiler::readsDerivedTrait))
                    return true;
            if (modifier.has("label") && RuleTemplate.references(modifier.path("label").asText()).stream().anyMatch(RuleCompiler::readsDerivedTrait))
                return true;
        }
        return false;
    }

    private static ArrayNode modifierList(JsonNode list, String path, ObjectNode state, boolean passive) {
        if (!list.isArray() || list.size() > 64) throw RuleJson.error(path, "Expected up to 64 modifiers.");
        for (int i = 0; i < list.size(); i++) {
            JsonNode modifier = list.get(i);
            String at = path + "/" + i;
            RuleJson.fields(modifier, at, "stat", "op", "value", "when", "label");
            String stat = RuleJson.text(modifier.get("stat"), at + "/stat");
            if (!STATS.matcher(stat).matches()) throw RuleJson.error(at + "/stat", "Unknown statistic: " + stat);
            if (modifier.has("op") && !Set.of("add", "set", "atLeast", "atMost").contains(modifier.path("op").asText()))
                throw RuleJson.error(at + "/op", "Expected add, set, atLeast or atMost.");
            if (!modifier.has("value")) throw RuleJson.error(at + "/value", "Missing modifier value.");
            RuleExpression.validate(modifier.get("value"), at + "/value");
            if (modifier.has("when")) RuleExpression.validate(modifier.get("when"), at + "/when");
            if (modifier.has("label")) RuleTemplate.validate(RuleJson.text(modifier.get("label"), at + "/label"));
            for (String field : List.of("value", "when")) if (modifier.has(field))
                for (String ref : RuleExpression.references(modifier.get(field))) {
                    String root = ref.split("\\.")[0];
                    if (!Set.of("source", "actor", "state", "effects").contains(root) || "state".equals(root) && !state.has(ref.split("\\.")[1]))
                        throw RuleJson.error(at + "/" + field, "Passive modifiers may only read source, actor, effects and declared state: " + ref);
                    if (passive && stat.startsWith("trait.") && readsDerivedTrait(ref))
                        throw RuleJson.error(at + "/" + field, "A passive trait modifier cannot depend on derived trait totals: " + ref);
                }
        }
        return (ArrayNode) list.deepCopy();
    }

    static Map<String, ObjectNode> recipes(JsonNode document) {
        RuleJson.checkTree(document, "/recipes", 0, new int[]{0}, RuleJson.MAX_DOCUMENT_NODES);
        RuleJson.fields(document, "/recipes", "format", "version", "recipes");
        if (!"dmmap-daggerheart-rules".equals(document.path("format").asText())
                || !document.path("version").isIntegralNumber() || document.path("version").longValue() != FORMAT_VERSION)
            throw RuleJson.error("/recipes/version", "Recipe pack must use format " + FORMAT_VERSION + ".");
        if (!document.path("recipes").isArray() || document.path("recipes").size() > 256)
            throw RuleJson.error("/recipes", "Expected a bounded recipe list.");
        Map<String, ObjectNode> recipes = new LinkedHashMap<>();
        for (JsonNode recipe : document.path("recipes")) {
            RuleJson.fields(recipe, "/recipes", "id", "args", "ui", "rules");
            String id = RuleJson.id(recipe.path("id"), "/recipes/id");
            if (recipes.put(id, (ObjectNode) recipe) != null) throw RuleJson.error("/recipes/id", "Duplicate recipe ID.");
            RuleJson.object(recipe.path("args"), "/recipes/" + id + "/args");
            RuleJson.object(recipe.path("ui"), "/recipes/" + id + "/ui");
            validateRecipeArguments(recipe.path("args"), "/recipes/" + id);
            mergedUi(recipe.path("ui"), RuleJson.JSON.createObjectNode(), "/recipes/" + id);
            if (!recipe.path("rules").isArray() || recipe.path("rules").isEmpty() || recipe.path("rules").size() > 64)
                throw RuleJson.error("/recipes/" + id + "/rules", "Expected 1-64 recipe rules.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < recipe.path("rules").size(); i++) {
                JsonNode rule = recipe.path("rules").get(i);
                if (rule.has("useRecipe")) throw RuleJson.error("/recipes/" + id, "Nested recipes are not supported.");
                if (!ids.add(RuleJson.id(rule.path("id"), "/recipes/" + id + "/rules/id")))
                    throw RuleJson.error("/recipes/" + id, "Duplicate recipe rule ID.");
                String path = "/recipes/" + id + "/rules/" + i;
                validateRecipeReferences(rule, recipe.path("args"), recipe.path("ui"), path);
                validateRecipeShape(rule, path);
                validateRecipeLocalReferences(rule, recipe.path("args"), path);
            }
        }
        return recipes;
    }

    private static void validateRecipeArguments(JsonNode specs, String path) {
        ObjectNode examples = RuleJson.JSON.createObjectNode();
        specs.fields().forEachRemaining(field -> {
            String at = path + "/args/" + field.getKey();
            slot(field.getKey(), at);
            JsonNode spec = field.getValue();
            RuleJson.fields(spec, at, "type", "min", "max", "default");
            String type = RuleJson.text(spec.get("type"), at + "/type");
            switch (type) {
                case "integer" -> {
                    int min = spec.has("min") ? RuleJson.integer(spec.get("min"), at + "/min", -1_000_000, 1_000_000) : -1_000_000;
                    int max = spec.has("max") ? RuleJson.integer(spec.get("max"), at + "/max", -1_000_000, 1_000_000) : 1_000_000;
                    if (min > max) throw RuleJson.error(at, "Minimum exceeds maximum.");
                    examples.put(field.getKey(), min);
                }
                case "string" -> examples.put(field.getKey(), "example");
                case "costs" -> examples.putArray(field.getKey());
                default -> throw RuleJson.error(at + "/type", "Unknown recipe argument type.");
            }
            if (spec.has("default")) examples.set(field.getKey(), spec.get("default"));
        });
        arguments(specs, examples, path);
    }

    private static void validateRecipeReferences(JsonNode node, JsonNode args, JsonNode ui, String path) {
        if (node.isObject()) {
            if (node.has("$arg") || node.has("$ui")) {
                if (node.size() != 1) throw RuleJson.error(path, "A recipe substitution must be a single field.");
                boolean argument = node.has("$arg");
                String name = RuleJson.text(node.get(argument ? "$arg" : "$ui"), path);
                if (!(argument ? args : ui).has(name))
                    throw RuleJson.error(path, "Unknown recipe substitution: " + name);
                return;
            }
            node.fields().forEachRemaining(field ->
                    validateRecipeReferences(field.getValue(), args, ui, path + "/" + field.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                validateRecipeReferences(node.get(i), args, ui, path + "/" + i);
        }
    }

    private static boolean substitution(JsonNode node) {
        return node.isObject() && (node.has("$arg") || node.has("$ui"));
    }

    private static boolean containsSubstitution(JsonNode node) {
        if (substitution(node)) return true;
        if (node.isContainerNode()) for (JsonNode child : node)
            if (containsSubstitution(child)) return true;
        return false;
    }

    private static void validateBindingBasics(JsonNode binding, String path, boolean allowSubstitutions) {
        RuleJson.fields(binding, path, "id", "event", "surface", "mode", "when", "group",
                "defaultOn", "forEach", "exclusiveGroup", "priority", "claimGroup", "claimGroupScope", "claimScope", "audience", "targetSelection", "repeatable", "restMove", "restPhase", "restRollPolicy");
        if (!(allowSubstitutions && substitution(binding.path("id")))) RuleJson.id(binding.path("id"), path + "/id");
        if (!(allowSubstitutions && substitution(binding.path("event"))) && !EVENTS.contains(binding.path("event").asText()))
            throw RuleJson.error(path + "/event", "Unknown event.");
        if (!(allowSubstitutions && substitution(binding.path("surface"))) && !SURFACES.contains(binding.path("surface").asText()))
            throw RuleJson.error(path + "/surface", "Unknown surface.");
        if (!(allowSubstitutions && substitution(binding.path("mode"))) && !MODES.contains(binding.path("mode").asText()))
            throw RuleJson.error(path + "/mode", "Unknown binding mode.");
        if (binding.has("when") && !(allowSubstitutions && containsSubstitution(binding.get("when"))))
            RuleExpression.validate(binding.get("when"), path + "/when");
        if (binding.has("audience") && !(allowSubstitutions && substitution(binding.get("audience")))
                && !AUDIENCES.contains(binding.path("audience").asText()))
            throw RuleJson.error(path + "/audience", "Expected self, others, party or any.");
        if (binding.has("defaultOn") && !(allowSubstitutions && substitution(binding.get("defaultOn"))))
            RuleExpression.bool(binding.get("defaultOn"), path + "/defaultOn");
        if (binding.has("priority") && !(allowSubstitutions && substitution(binding.get("priority"))))
            RuleJson.integer(binding.get("priority"), path + "/priority", -1000, 1000);
        if (binding.has("group") && !(allowSubstitutions && substitution(binding.get("group")))
                && !Set.of("result", "sourceFeature", "otherFeatures").contains(binding.path("group").asText()))
            throw RuleJson.error(path + "/group", "Unknown result section.");
        if (binding.has("defaultOn") && !(allowSubstitutions && substitution(binding.path("mode")))
                && !"option".equals(binding.path("mode").asText()))
            throw RuleJson.error(path + "/defaultOn", "Defaults are only valid for option bindings.");
        if (binding.has("restPhase")) {
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            if (!eventUnknown && !Set.of("rest.short", "rest.long").contains(binding.path("event").asText()))
                throw RuleJson.error(path + "/restPhase", "Rest phases require a rest.short or rest.long binding.");
            if (!(allowSubstitutions && substitution(binding.get("restPhase")))
                    && !Set.of("beforeReset", "afterReset").contains(binding.path("restPhase").asText()))
                throw RuleJson.error(path + "/restPhase", "Expected beforeReset or afterReset.");
        }
        if (binding.has("restRollPolicy")) {
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
            boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
            if ((!eventUnknown && !"rest.prepare".equals(binding.path("event").asText()))
                    || (!surfaceUnknown && !"rest".equals(binding.path("surface").asText()))
                    || (!modeUnknown && !"option".equals(binding.path("mode").asText()))
                    || binding.has("forEach"))
                throw RuleJson.error(path + "/restRollPolicy", "A rest roll policy requires a rest.prepare option without forEach.");
            if (!(allowSubstitutions && substitution(binding.get("restRollPolicy")))
                    && !"keepHigher".equals(binding.path("restRollPolicy").asText()))
                throw RuleJson.error(path + "/restRollPolicy", "Expected keepHigher.");
        }
        if (binding.has("repeatable")) {
            if (!(allowSubstitutions && substitution(binding.get("repeatable"))))
                RuleExpression.bool(binding.get("repeatable"), path + "/repeatable");
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
            boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
            if ((!eventUnknown && !"roll.finished".equals(binding.path("event").asText()))
                    || (!surfaceUnknown && !"roll".equals(binding.path("surface").asText()))
                    || (!modeUnknown && !"button".equals(binding.path("mode").asText())))
                throw RuleJson.error(path + "/repeatable", "Only roll.finished result buttons can be repeatable.");
        }
        if (binding.has("restMove")) {
            String at = path + "/restMove";
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
            boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
            if ((!eventUnknown && !"downtime.move".equals(binding.path("event").asText()))
                    || (!surfaceUnknown && !"downtime".equals(binding.path("surface").asText()))
                    || (!modeUnknown && !"button".equals(binding.path("mode").asText())) || binding.has("forEach"))
                throw RuleJson.error(at, "A rest move requires a downtime.move button without forEach.");
            if (!(allowSubstitutions && substitution(binding.get("restMove")))) {
                JsonNode move = binding.get("restMove");
                RuleJson.fields(move, at, "id", "kind", "group", "ally", "narrative");
                if (!(allowSubstitutions && substitution(move.path("id")))) RuleJson.id(move.path("id"), at + "/id");
                if (!(allowSubstitutions && substitution(move.path("kind")))
                        && !Set.of("any", "short", "long", "longMove").contains(move.path("kind").asText()))
                    throw RuleJson.error(at + "/kind", "Expected any, short, long or longMove.");
                if (move.has("group") && !(allowSubstitutions && substitution(move.get("group"))))
                    RuleJson.id(move.get("group"), at + "/group");
                if (move.has("ally") && !(allowSubstitutions && substitution(move.get("ally"))) && !move.get("ally").isBoolean())
                    throw RuleJson.error(at + "/ally", "Expected a boolean.");
                if (move.has("narrative") && !(allowSubstitutions && substitution(move.get("narrative")))
                        && !move.get("narrative").isBoolean())
                    throw RuleJson.error(at + "/narrative", "Expected a boolean.");
                boolean narrativeUnknown = allowSubstitutions && substitution(move.path("narrative"));
                boolean allyUnknown = allowSubstitutions && substitution(move.path("ally"));
                if (!narrativeUnknown && move.path("narrative").asBoolean(false)
                        && (binding.has("targetSelection") || (!allyUnknown && !move.path("ally").asBoolean(false))))
                    throw RuleJson.error(at + "/narrative", "A narrative move requires ally: true and no target selection.");
            }
        }
        for (String group : List.of("exclusiveGroup", "claimGroup")) if (binding.has(group)) {
            if (!(allowSubstitutions && substitution(binding.get(group)))) RuleJson.id(binding.get(group), path + "/" + group);
            if (!(allowSubstitutions && substitution(binding.path("mode")))
                    && !Set.of("button", "option").contains(binding.path("mode").asText()))
                throw RuleJson.error(path + "/" + group, "Groups require selected bindings.");
        }
        if (binding.has("claimGroupScope")) {
            String at = path + "/claimGroupScope";
            if (!binding.has("claimGroup")) throw RuleJson.error(at, "A group scope requires a claim group.");
            boolean scopeUnknown = allowSubstitutions && substitution(binding.get("claimGroupScope"));
            if (!scopeUnknown && !Set.of("source", "result").contains(binding.path("claimGroupScope").asText()))
                throw RuleJson.error(at, "Expected source or result.");
            if (!scopeUnknown && "result".equals(binding.path("claimGroupScope").asText())) {
                boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
                boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
                boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
                if ((!eventUnknown && !"roll.finished".equals(binding.path("event").asText()))
                        || (!surfaceUnknown && !Set.of("roll", "chat").contains(binding.path("surface").asText()))
                        || (!modeUnknown && !"button".equals(binding.path("mode").asText())))
                    throw RuleJson.error(at, "Result-wide groups require roll.finished result buttons.");
            }
        }
        if (binding.has("claimScope") && !binding.has("claimGroup"))
            throw RuleJson.error(path + "/claimScope", "A claim scope requires a claim group.");
        if (binding.has("claimScope") && !(allowSubstitutions && substitution(binding.get("claimScope")))) {
            JsonNode scope = binding.get("claimScope");
            String at = path + "/claimScope";
            RuleJson.fields(scope, at, "recipients", "excludeSource");
            if (!(allowSubstitutions && substitution(scope.path("recipients")))
                    && !CLAIM_RECIPIENTS.contains(scope.path("recipients").asText()))
                throw RuleJson.error(at + "/recipients", "Expected otherPlayersCharacters, playerCharacters or sourceActor.");
            if (scope.has("excludeSource") && !(allowSubstitutions && substitution(scope.get("excludeSource")))
                    && !scope.path("excludeSource").isBoolean())
                throw RuleJson.error(at + "/excludeSource", "Expected a boolean.");
            if (!(allowSubstitutions && (substitution(scope.path("recipients")) || substitution(scope.path("excludeSource"))))
                    && "sourceActor".equals(scope.path("recipients").asText()) && scope.path("excludeSource").asBoolean(false))
                throw RuleJson.error(at + "/excludeSource", "A source-actor claim cannot exclude the source.");
        }
        if (binding.has("targetSelection")) {
            String at = path + "/targetSelection";
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
            boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
            boolean sheetAction = (eventUnknown || "action.request".equals(binding.path("event").asText()))
                    && (surfaceUnknown || "sheet".equals(binding.path("surface").asText()));
            boolean rollAction = (eventUnknown || "roll.finished".equals(binding.path("event").asText()))
                    && (surfaceUnknown || "roll".equals(binding.path("surface").asText()));
            boolean restAction = (eventUnknown || "downtime.move".equals(binding.path("event").asText()))
                    && (surfaceUnknown || "downtime".equals(binding.path("surface").asText())) && binding.has("restMove");
            if (!modeUnknown && !"button".equals(binding.path("mode").asText()) || !sheetAction && !rollAction && !restAction)
                throw RuleJson.error(at, "Target selection requires a sheet, roll result or ally downtime button.");
            if (binding.has("claimGroup")) throw RuleJson.error(at, "Target selections use a transaction, not a claim group.");
            if (restAction && !sheetAction && !rollAction) {
                JsonNode move = binding.path("restMove"), targets = binding.path("targetSelection");
                if (!(allowSubstitutions && (substitution(move) || substitution(move.path("ally"))))
                        && !move.path("ally").asBoolean(false))
                    throw RuleJson.error(at, "A targeted downtime move must declare ally: true.");
                if (!(allowSubstitutions && (substitution(targets) || substitution(targets.path("cardinality"))))
                        && !"one".equals(targets.path("cardinality").asText()))
                    throw RuleJson.error(at + "/cardinality", "An ally downtime move selects one character.");
                if (!(allowSubstitutions && (substitution(targets) || substitution(targets.path("excludeSource"))))
                        && !targets.path("excludeSource").asBoolean(false))
                    throw RuleJson.error(at + "/excludeSource", "An ally downtime move must exclude its source.");
            }
        }
        if (binding.has("targetSelection") && !(allowSubstitutions && substitution(binding.get("targetSelection")))) {
            JsonNode targets = binding.get("targetSelection");
            String at = path + "/targetSelection";
            RuleJson.fields(targets, at, "cardinality", "scope", "excludeSource", "where");
            if (!(allowSubstitutions && substitution(targets.path("cardinality")))
                    && !Set.of("one", "all").contains(targets.path("cardinality").asText()))
                throw RuleJson.error(at + "/cardinality", "Expected one or all.");
            if (!(allowSubstitutions && substitution(targets.path("scope")))
                    && !TARGET_SCOPES.contains(targets.path("scope").asText()))
                throw RuleJson.error(at + "/scope", "Expected connectedPlayerCharacters, activeCharacters or activePlayerCharacters.");
            if (targets.has("excludeSource") && !(allowSubstitutions && substitution(targets.get("excludeSource")))
                    && !targets.path("excludeSource").isBoolean())
                throw RuleJson.error(at + "/excludeSource", "Expected a boolean.");
            if (targets.has("where") && !(allowSubstitutions && containsSubstitution(targets.get("where"))))
                RuleExpression.validate(targets.get("where"), at + "/where");
        }
        if (binding.has("forEach") && !(allowSubstitutions && substitution(binding.path("mode")))
                && !"button".equals(binding.path("mode").asText()))
            throw RuleJson.error(path + "/forEach", "Per-die controls must be buttons.");
    }

    private static void validateRecipeShape(JsonNode rule, String path) {
        RuleJson.fields(rule, path, "id", "bindings", "when", "inputs", "costs", "limitRef", "do", "after", "ui");
        if (rule.has("when") && !containsSubstitution(rule.get("when")))
            RuleExpression.validate(rule.get("when"), path + "/when");
        JsonNode bindings = rule.path("bindings");
        if (!substitution(bindings)) {
            if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 32)
                throw RuleJson.error(path + "/bindings", "Expected 1-32 bindings.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < bindings.size(); i++) {
                JsonNode binding = bindings.get(i);
                if (!substitution(binding)) {
                    String at = path + "/bindings/" + i;
                    validateBindingBasics(binding, at, true);
                    if (!substitution(binding.path("id")) && !ids.add(binding.path("id").asText()))
                        throw RuleJson.error(at + "/id", "Duplicate binding ID.");
                }
            }
        }
        JsonNode ui = rule.path("ui");
        if (!substitution(ui)) {
            RuleJson.fields(ui, path + "/ui", TEXT_FIELDS.toArray(String[]::new));
            ui.fields().forEachRemaining(field -> {
                if (!substitution(field.getValue()))
                    RuleTemplate.validate(RuleJson.text(field.getValue(), path + "/ui/" + field.getKey()));
            });
        }
        JsonNode inputs = rule.path("inputs");
        if (!inputs.isMissingNode() && !substitution(inputs)) {
            if (!inputs.isArray() || inputs.size() > 16)
                throw RuleJson.error(path + "/inputs", "Expected up to 16 inputs.");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < inputs.size(); i++) {
                JsonNode input = inputs.get(i);
                String at = path + "/inputs/" + i;
                if (substitution(input)) continue;
                RuleJson.object(input, at);
                if (!substitution(input.path("id"))) {
                    String id = RuleJson.id(input.path("id"), at + "/id");
                    slot(id, at + "/id");
                    if (!ids.add(id)) throw RuleJson.error(at + "/id", "Duplicate input ID.");
                }
                if (!containsSubstitution(input)) validateInput(input, at);
            }
        }
        if (bindings.isArray()) for (JsonNode binding : bindings) {
            if (!"automatic".equals(binding.path("mode").asText())) continue;
            JsonNode costs = rule.path("costs");
            if (inputs.isArray() && !inputs.isEmpty() || costs.isArray() && !costs.isEmpty())
                throw RuleJson.error(path, "Automatic rules cannot require inputs or explicit costs.");
        }
        Set<String> outputs = new HashSet<>();
        Decl recipeDeclarations = Decl.empty();
        for (String list : List.of("costs", "do")) {
            JsonNode steps = rule.path(list);
            if (steps.isMissingNode() || substitution(steps)) continue;
            if (!steps.isArray() || steps.size() > 128)
                throw RuleJson.error(path + "/" + list, "Expected a bounded operation list.");
            for (int i = 0; i < steps.size(); i++) {
                JsonNode step = steps.get(i);
                if (substitution(step)) continue;
                String at = path + "/" + list + "/" + i;
                RuleJson.object(step, at);
                JsonNode name = step.path("op");
                if (substitution(name)) continue;
                String op = RuleJson.text(name, at + "/op");
                if (containsSubstitution(step))
                    validateOperationShape(step, "costs".equals(list), op, at);
                else validateOperation(step, "costs".equals(list), recipeDeclarations, at, true);
                if (step.has("as") && !substitution(step.get("as"))) {
                    String output = RuleJson.text(step.get("as"), at + "/as");
                    slot(output, at + "/as");
                    if (!outputs.add(output)) throw RuleJson.error(at + "/as", "Duplicate operation result ID.");
                }
            }
        }
        validateScheduledRecoveryPlacement(rule, path, true);
        if (rule.has("after") && !substitution(rule.get("after"))) {
            validateAfterPlacement(rule, path, true);
            validateRecipeShape(afterRule(rule.get("after")), path + "/after");
        }
    }

    private static void validateAfterPlacement(JsonNode rule, String path, boolean allowSubstitutions) {
        JsonNode after = rule.get("after");
        String at = path + "/after";
        RuleJson.fields(after, at, "costs", "do", "report");
        if (!after.has("costs") && !after.has("do") && !after.has("report"))
            throw RuleJson.error(at, "A deferred move benefit needs a cost, operation or report.");
        if (after.has("report") && !(allowSubstitutions && substitution(after.get("report"))))
            RuleTemplate.validate(RuleJson.text(after.get("report"), at + "/report"));
        for (JsonNode binding : rule.path("bindings")) {
            if (allowSubstitutions && substitution(binding)) continue;
            boolean eventUnknown = allowSubstitutions && substitution(binding.path("event"));
            boolean surfaceUnknown = allowSubstitutions && substitution(binding.path("surface"));
            boolean modeUnknown = allowSubstitutions && substitution(binding.path("mode"));
            if ((!eventUnknown && !"downtime.move".equals(binding.path("event").asText()))
                    || (!surfaceUnknown && !"downtime".equals(binding.path("surface").asText()))
                    || (!modeUnknown && !Set.of("automatic", "option").contains(binding.path("mode").asText()))
                    || binding.has("forEach") || binding.has("targetSelection") || binding.has("claimGroup"))
                throw RuleJson.error(at, "Deferred benefits require downtime.move automatic or option bindings.");
            if ("automatic".equals(binding.path("mode").asText()) && after.path("costs").isArray()
                    && !after.path("costs").isEmpty())
                throw RuleJson.error(at, "Automatic rules cannot require explicit deferred costs.");
        }
        for (JsonNode operation : after.path("do"))
            if ("plan.adjust".equals(operation.path("op").asText()))
                throw RuleJson.error(at + "/do", "A completed move's plan cannot be adjusted.");
    }

    private static ObjectNode afterRule(JsonNode after) {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "after");
        rule.putArray("bindings").addObject().put("id", "after").put("event", "downtime.move")
                .put("surface", "downtime").put("mode", "option");
        if (after.has("costs")) rule.set("costs", after.get("costs").deepCopy());
        if (after.has("do")) rule.set("do", after.get("do").deepCopy());
        ObjectNode ui = rule.putObject("ui").put("label", "Deferred move benefit");
        if (after.has("report")) ui.set("report", after.get("report").deepCopy());
        return rule;
    }

    private static ObjectNode arguments(JsonNode specs, JsonNode supplied, String path) {
        ObjectNode args = supplied.isMissingNode() ? RuleJson.JSON.createObjectNode() : RuleJson.object(supplied, path + "/args").deepCopy();
        args.fieldNames().forEachRemaining(key -> {
            if (!specs.has(key)) throw RuleJson.error(path + "/args/" + key, "Unknown recipe argument.");
        });
        specs.fields().forEachRemaining(field -> {
            String key = field.getKey(), at = path + "/args/" + key;
            slot(key, at);
            JsonNode spec = field.getValue();
            RuleJson.fields(spec, at, "type", "min", "max", "default");
            JsonNode value = args.get(key);
            if (value == null) value = spec.get("default");
            if (value == null) throw RuleJson.error(at, "Required recipe argument is missing.");
            switch (spec.path("type").asText()) {
                case "integer" -> RuleJson.integer(value, at,
                        spec.has("min") ? RuleJson.integer(spec.get("min"), at, -1_000_000, 1_000_000) : -1_000_000,
                        spec.has("max") ? RuleJson.integer(spec.get("max"), at, -1_000_000, 1_000_000) : 1_000_000);
                case "string" -> RuleJson.text(value, at);
                case "costs" -> {
                    if (!value.isArray() || value.size() > 32) throw RuleJson.error(at, "Expected a bounded cost list.");
                    for (JsonNode cost : value) validateOperation(cost, true, Decl.empty(), at, true);
                }
                default -> throw RuleJson.error(at, "Unknown recipe argument type.");
            }
            args.set(key, value.deepCopy());
        });
        return args;
    }

    private static ObjectNode mergedUi(JsonNode defaults, JsonNode overrides, String path) {
        ObjectNode ui = RuleJson.object(defaults, path + "/ui").deepCopy();
        if (!overrides.isMissingNode()) {
            RuleJson.object(overrides, path + "/ui").fields().forEachRemaining(field -> {
                if (!ui.has(field.getKey())) throw RuleJson.error(path + "/ui/" + field.getKey(), "Unknown recipe text.");
                ui.set(field.getKey(), field.getValue().deepCopy());
            });
        }
        ui.fields().forEachRemaining(field -> RuleTemplate.validate(RuleJson.text(field.getValue(), path + "/ui/" + field.getKey())));
        return ui;
    }

    private static JsonNode substitute(JsonNode node, ObjectNode args, ObjectNode ui, String path) {
        if (node.isObject()) {
            if (node.has("$arg") || node.has("$ui")) {
                if (node.size() != 1) throw RuleJson.error(path, "A recipe substitution must be a single field.");
                boolean argument = node.has("$arg");
                String name = RuleJson.text(node.get(argument ? "$arg" : "$ui"), path);
                JsonNode value = (argument ? args : ui).get(name);
                if (value == null) throw RuleJson.error(path, "Unknown recipe substitution: " + name);
                return value.deepCopy();
            }
            ObjectNode result = RuleJson.JSON.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(field.getKey(), substitute(field.getValue(), args, ui, path + "/" + field.getKey())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = RuleJson.JSON.createArrayNode();
            for (int i = 0; i < node.size(); i++) result.add(substitute(node.get(i), args, ui, path + "/" + i));
            return result;
        }
        return node.deepCopy();
    }

    private static void addRule(Map<String, ObjectNode> rules, ObjectNode rule, ObjectNode args, Decl decl, String path, int[] expandedNodes) {
        ObjectNode limits = decl.limits(), pools = decl.pools();
        RuleJson.checkTree(rule, path, 0, expandedNodes);
        RuleJson.checkTree(args, path + "/args", 0, expandedNodes);
        if (rules.size() >= 256) throw RuleJson.error(path, "Expanded rules exceed the limit.");
        RuleJson.fields(rule, path, "id", "bindings", "when", "inputs", "costs", "limitRef", "do", "after", "ui");
        String id = RuleJson.id(rule.path("id"), path + "/id");
        if (rules.containsKey(id)) throw RuleJson.error(path + "/id", "Expanded rule ID collision: " + id);
        if (rule.has("when")) RuleExpression.validate(rule.get("when"), path + "/when");
        if (rule.has("limitRef") && !limits.has(RuleJson.text(rule.get("limitRef"), path + "/limitRef")))
            throw RuleJson.error(path + "/limitRef", "Unknown use budget.");
        JsonNode bindings = rule.path("bindings");
        if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 32) throw RuleJson.error(path + "/bindings", "Expected 1-32 bindings.");
        Set<String> bindingIds = new HashSet<>();
        boolean needsLabel = false;
        boolean usesDieContext = false;
        for (JsonNode binding : bindings) {
            validateBindingBasics(binding, path + "/bindings", false);
            if (!bindingIds.add(RuleJson.id(binding.path("id"), path + "/bindings/id"))) throw RuleJson.error(path, "Duplicate binding ID.");
            if (binding.has("forEach")) {
                String pool = RuleJson.text(binding.get("forEach"), path + "/bindings/forEach");
                if (!pools.has(pool) || !"dice".equals(pools.path(pool).path("kind").asText()))
                    throw RuleJson.error(path + "/bindings/forEach", "Expected a declared dice pool.");
                usesDieContext = true;
            }
            needsLabel |= !"automatic".equals(binding.path("mode").asText());
        }
        JsonNode ui = rule.path("ui");
        RuleJson.fields(ui, path + "/ui", TEXT_FIELDS.toArray(String[]::new));
        if (needsLabel) RuleJson.text(ui.get("label"), path + "/ui/label");
        ui.fields().forEachRemaining(field -> RuleTemplate.validate(RuleJson.text(field.getValue(), path + "/ui/" + field.getKey())));
        Set<String> inputs = new HashSet<>();
        if (rule.has("inputs")) {
            JsonNode definitions = rule.get("inputs");
            if (!definitions.isArray() || definitions.size() > 16) throw RuleJson.error(path + "/inputs", "Expected up to 16 inputs.");
            for (JsonNode input : definitions) {
                String inputId = RuleJson.id(input.path("id"), path + "/inputs/id");
                slot(inputId, path + "/inputs/id");
                if (!inputs.add(inputId)) throw RuleJson.error(path + "/inputs", "Duplicate input ID.");
                validateInput(input, path + "/inputs");
            }
        }
        boolean hasAutomatic = false;
        for (JsonNode binding : bindings) hasAutomatic |= "automatic".equals(binding.path("mode").asText());
        if (hasAutomatic && (rule.has("inputs") && !rule.path("inputs").isEmpty()
                || rule.has("costs") && !rule.path("costs").isEmpty()))
            throw RuleJson.error(path, "Automatic rules cannot require inputs or explicit costs.");
        Set<String> outputs = new HashSet<>();
        for (String list : List.of("costs", "do")) {
            JsonNode steps = rule.path(list);
            if (steps.isMissingNode()) continue;
            if (!steps.isArray() || steps.size() > 128)
                throw RuleJson.error(path + "/" + list, "Expected a bounded operation list.");
            for (int i = 0; i < steps.size(); i++) {
                JsonNode step = steps.get(i);
                validateOperation(step, "costs".equals(list), decl, path + "/" + list + "/" + i);
                if (step.has("as") && !outputs.add(step.path("as").asText())) throw RuleJson.error(path, "Duplicate operation result ID.");
            }
        }
        validateScheduledRecoveryPlacement(rule, path, false);
        validateReferences(rule, args, decl, inputs, usesDieContext, path);
        validatePreRollChoices(rule, path);
        if (rule.has("after")) {
            validateAfterPlacement(rule, path, false);
            addRule(new LinkedHashMap<>(), afterRule(rule.get("after")),
                    args, decl, path + "/after", expandedNodes);
        }
        if (hasRecipientMutation(rule)) {
            for (JsonNode binding : bindings)
                if (!binding.has("claimGroup") && !binding.has("targetSelection")
                        && !("downtime.move".equals(binding.path("event").asText())
                        && Set.of("automatic", "option").contains(binding.path("mode").asText())
                        && supportedRestRecipientMutations(rule)))
                    throw RuleJson.error(path + "/bindings",
                            "Recipient mutations require a claim, target selection, or supported targeted downtime phase.");
        }
        if (ruleHasTargetSelection(bindings) && !hasRecipientMutation(rule))
            throw RuleJson.error(path + "/bindings", "Target selection requires a recipient mutation.");
        rule.set("args", args.deepCopy());
        rules.put(id, rule);
    }

    private static void validateInput(JsonNode input, String path) {
        RuleTemplate.validate(RuleJson.text(input.path("label"), path + "/label"));
        switch (input.path("kind").asText()) {
            case "integer" -> {
                RuleJson.fields(input, path, "id", "kind", "min", "max", "default", "label");
                if (input.has("default")) RuleJson.integer(input.get("default"), path + "/default", -1_000_000, 1_000_000);
                RuleExpression.validate(input.path("min"), path + "/min");
                RuleExpression.validate(input.path("max"), path + "/max");
            }
            case "die.select" -> {
                RuleJson.fields(input, path, "id", "kind", "from", "eligible", "where", "count", "min", "default", "label", "choiceLabel");
                if (input.has("eligible") == input.has("where"))
                    throw RuleJson.error(path, "Die selections need either eligible dice or a per-die where filter.");
                for (String field : List.of("from", "eligible", "where", "count", "min"))
                    if (input.has(field) || !Set.of("eligible", "where", "min").contains(field))
                        RuleExpression.validate(input.path(field), path + "/" + field);
                if (input.has("default") && !"lowest".equals(input.path("default").asText()))
                    throw RuleJson.error(path + "/default", "Unknown die selection default.");
                RuleTemplate.validate(RuleJson.text(input.path("choiceLabel"), path + "/choiceLabel"));
            }
            case "choice" -> {
                RuleJson.fields(input, path, "id", "kind", "label", "options", "optionsFrom", "count");
                int size = validateOptions(input, path);
                int count = input.has("count") ? RuleJson.integer(input.get("count"), path + "/count", 1, size) : 1;
                if (count < 1) throw RuleJson.error(path + "/count", "Expected at least one choice.");
            }
            case "allocation" -> {
                RuleJson.fields(input, path, "id", "kind", "label", "options", "optionsFrom", "total", "max");
                validateOptions(input, path);
                RuleExpression.validate(input.path("total"), path + "/total");
                if (input.has("max")) RuleExpression.validate(input.get("max"), path + "/max");
            }
            default -> throw RuleJson.error(path + "/kind", "Unknown input kind.");
        }
    }

    private static final Set<String> ROLLED_CHECK_FIELDS = Set.of("hopeDie", "fearDie", "dominant", "critical", "total", "outcome");

    private static void validatePreRollChoices(ObjectNode rule, String path) {
        Set<String> reads = new HashSet<>();
        boolean preRoll = false;
        for (JsonNode binding : rule.path("bindings")) {
            if (!"roll.prepare".equals(binding.path("event").asText()) || !"option".equals(binding.path("mode").asText())) continue;
            preRoll = true;
            reads.addAll(RuleExpression.references(binding.path("when")));
        }
        if (!preRoll) return;
        for (String key : List.of("when", "inputs", "costs")) reads.addAll(RuleExpression.references(rule.path(key)));
        for (String key : List.of("label", "description", "unavailable"))
            reads.addAll(RuleTemplate.references(rule.path("ui").path(key).asText("")));
        for (JsonNode input : rule.path("inputs")) {
            for (String key : List.of("label", "choiceLabel")) reads.addAll(RuleTemplate.references(input.path(key).asText("")));
            for (JsonNode option : input.path("options")) reads.addAll(RuleTemplate.references(option.path("label").asText()));
        }
        for (String ref : reads) {
            String[] parts = ref.split("\\.");
            if (parts[0].equals("event") && (parts[1].equals("roll") || parts[1].equals("check")
                    && (parts.length == 2 || ROLLED_CHECK_FIELDS.contains(parts[2]))))
                throw RuleJson.error(path, "A pre-roll choice cannot depend on " + ref + "; use a roll.finished result action.");
        }
    }

    private static boolean hasRecipientMutation(JsonNode rule) {
        for (String phase : List.of("costs", "do"))
            for (JsonNode operation : rule.path(phase))
                if (recipientMutation(operation)) return true;
        return false;
    }

    private static boolean supportedRestRecipientMutations(JsonNode rule) {
        for (String phase : List.of("costs", "do"))
            for (JsonNode operation : rule.path(phase))
                if (recipientMutation(operation) && !Set.of("resource.spend", "resource.gain", "track.mark",
                        "track.clear", "track.capacity", "pool.spend", "pool.gain", "flag.set",
                        "collection.add", "collection.remove", "inventory.grant",
                        "effect.start", "effect.end", "effect.endLabeled")
                        .contains(operation.path("op").asText()))
                    return false;
        return true;
    }

    private static boolean recipientMutation(JsonNode operation) {
        String kind = operation.path("op").asText();
        String target = Set.of("resource.spend", "resource.gain").contains(kind)
                ? operation.path("pool").asText()
                : Set.of("track.mark", "track.clear", "track.capacity").contains(kind) ? operation.path("track").asText()
                : "flag.set".equals(kind) ? operation.path("flag").asText()
                : (kind.startsWith("effect.") || kind.startsWith("collection."))
                && "recipient".equals(operation.path("target").asText()) ? "recipient." : "";
        return target.startsWith("recipient.") || "recipient".equals(operation.path("owner").asText())
                || Set.of("limit.restore", "inventory.grant").contains(kind)
                && "recipient".equals(operation.path("to").asText());
    }

    private static boolean ruleHasTargetSelection(JsonNode bindings) {
        for (JsonNode binding : bindings) if (binding.has("targetSelection")) return true;
        return false;
    }

    private static void validateReferences(ObjectNode rule, ObjectNode args, Decl decl, Set<String> inputs, boolean usesDieContext, String path) {
        Set<String> noVariables = Set.of();
        if (rule.has("when")) references(RuleExpression.references(rule.get("when")), args, decl, noVariables, noVariables, false, path + "/when");
        for (JsonNode binding : rule.path("bindings")) {
            if (binding.has("when"))
                references(RuleExpression.references(binding.get("when")), args, decl, noVariables, noVariables, binding.has("forEach"), path + "/bindings/when");
            if (binding.path("targetSelection").has("where"))
                references(RuleExpression.references(binding.path("targetSelection").get("where")), args, decl, noVariables,
                        noVariables, false, path + "/bindings/targetSelection/where");
        }
        for (JsonNode input : rule.path("inputs")) {
            for (String field : List.of("from", "eligible", "count", "min", "max", "optionsFrom", "total")) if (input.has(field))
                references(RuleExpression.references(input.get(field)), args, decl, noVariables, noVariables, false, path + "/inputs/" + field);
            references(RuleTemplate.references(input.path("label").asText()), args, decl, noVariables, noVariables, false, path + "/inputs/label");
            if (input.has("where"))
                references(RuleExpression.references(input.get("where")), args, decl, noVariables, noVariables, true, path + "/inputs/where");
            if (input.has("choiceLabel"))
                references(RuleTemplate.references(input.path("choiceLabel").asText()), args, decl, noVariables, noVariables, true, path + "/inputs/choiceLabel");
            for (JsonNode option : input.path("options")) {
                references(RuleTemplate.references(option.path("label").asText()), args, decl, noVariables, noVariables, false, path + "/inputs/options/label");
                if (option.has("when"))
                    references(RuleExpression.references(option.get("when")), args, decl, noVariables, noVariables, false, path + "/inputs/options/when");
            }
        }
        Set<String> results = new HashSet<>();
        for (String list : List.of("costs", "do")) for (JsonNode op : rule.path(list)) {
            operationReferences(op, path + "/" + list, usesDieContext, false,
                    (refs, die, at) -> references(refs, args, decl, inputs, results, die, at));
            if (op.has("as")) results.add(op.path("as").asText());
        }
        rule.path("ui").fields().forEachRemaining(field -> {
            boolean resolved = Set.of("report", "preview").contains(field.getKey());
            references(RuleTemplate.references(field.getValue().asText()), args, decl, resolved ? inputs : noVariables,
                    resolved ? results : noVariables, usesDieContext, path + "/ui/" + field.getKey());
        });
    }

    @FunctionalInterface
    private interface ReferenceVisitor {
        void visit(Set<String> refs, boolean die, String path);
    }

    private static void operationReferences(JsonNode op, String path, boolean usesDieContext, boolean partial,
                                            ReferenceVisitor visitor) {
        String name = op.path("op").asText();
        List<String> fields = OPERATIONS.get(name);
        if (fields != null) for (String field : fields) if (op.has(field)) {
            JsonNode value = op.get(field);
            if (partial && substitution(value)) continue;
            String at = path + "/" + field;
            if (Set.of("id", "label", "reason").contains(field))
                templateReferences(value, at, usesDieContext, partial, visitor);
            else if ("contribution".equals(field)) {
                templateReferences(value.path("label"), at + "/label", usesDieContext, partial, visitor);
                for (String expression : List.of("count", "sides", "keepHighest", "maximize", "value", "factor"))
                    if (value.has(expression))
                        expressionReferences(value.get(expression), at + "/" + expression, usesDieContext, partial, visitor);
            } else if ("metadata".equals(field) && "roll.resolve".equals(name)) {
                value.fields().forEachRemaining(entry -> expressionReferences(entry.getValue(), at + "/" + entry.getKey(),
                        usesDieContext, partial, visitor));
            } else if ("outcomes".equals(field) && "dice.table".equals(name) && value.isArray()) {
                for (int i = 0; i < value.size(); i++)
                    templateReferences(value.get(i), at + "/" + i, usesDieContext, partial, visitor);
            } else if (PER_DIE_FIELDS.contains(field) && name.startsWith("roll."))
                expressionReferences(value, at, true, partial, visitor);
            else if ("plan".equals(field) && "roll.resolve".equals(name)
                    || !Set.of("pool", "track", "slot", "role", "effect", "target", "owner", "limit",
                    "choose", "maxDice", "collection", "to", "plan", "field", "counter", "direction", "partyRecovery").contains(field))
                expressionReferences(value, at, usesDieContext, partial, visitor);
        }
        if (op.has("if")) expressionReferences(op.get("if"), path + "/if", usesDieContext, partial, visitor);
    }

    private static void expressionReferences(JsonNode expression, String path, boolean die, boolean partial,
                                             ReferenceVisitor visitor) {
        if (!partial || !containsSubstitution(expression))
            visitor.visit(RuleExpression.references(expression), die, path);
    }

    private static void templateReferences(JsonNode template, String path, boolean die, boolean partial,
                                           ReferenceVisitor visitor) {
        if (!partial || !containsSubstitution(template))
            visitor.visit(RuleTemplate.references(template.asText()), die, path);
    }

    private static void validateRecipeLocalReferences(JsonNode rule, JsonNode args, String path) {
        Set<String> inputs = new HashSet<>(), results = new HashSet<>();
        JsonNode inputNodes = rule.path("inputs");
        boolean unknownInputs = substitution(inputNodes);
        for (JsonNode input : inputNodes) {
            if (substitution(input) || substitution(input.path("id"))) unknownInputs = true;
            else if (input.path("id").isTextual()) inputs.add(input.path("id").textValue());
        }
        ReferenceVisitor before = (refs, die, at) -> localReferences(refs, args, Set.of(), false, Set.of(), false, at);
        if (rule.has("when")) expressionReferences(rule.get("when"), path + "/when", false, true, before);
        for (JsonNode binding : rule.path("bindings")) {
            if (binding.has("when")) expressionReferences(binding.get("when"), path + "/bindings/when", false, true, before);
            if (binding.path("targetSelection").has("where"))
                expressionReferences(binding.path("targetSelection").get("where"),
                        path + "/bindings/targetSelection/where", false, true, before);
        }
        for (JsonNode input : inputNodes) {
            if (substitution(input)) continue;
            for (String field : List.of("from", "eligible", "count", "min", "max", "optionsFrom", "total", "where"))
                if (input.has(field)) expressionReferences(input.get(field), path + "/inputs/" + field, false, true, before);
            for (String field : List.of("label", "choiceLabel"))
                if (input.has(field)) templateReferences(input.get(field), path + "/inputs/" + field, false, true, before);
            for (JsonNode option : input.path("options")) {
                if (option.has("label")) templateReferences(option.get("label"), path + "/inputs/options/label", false, true, before);
                if (option.has("when")) expressionReferences(option.get("when"), path + "/inputs/options/when", false, true, before);
            }
        }
        boolean unknownResults = false;
        for (String list : List.of("costs", "do")) {
            JsonNode operations = rule.path(list);
            if (substitution(operations)) {
                unknownResults = true;
                continue;
            }
            for (int i = 0; i < operations.size(); i++) {
                JsonNode op = operations.get(i);
                if (substitution(op)) {
                    unknownResults = true;
                    continue;
                }
                boolean deferredInputs = unknownInputs, deferredResults = unknownResults;
                operationReferences(op, path + "/" + list + "/" + i, false, true,
                        (refs, die, at) -> localReferences(refs, args, inputs, deferredInputs, results, deferredResults, at));
                if (substitution(op.path("as"))) unknownResults = true;
                else if (op.path("as").isTextual()) results.add(op.path("as").textValue());
            }
        }
        boolean deferredInputs = unknownInputs, deferredResults = unknownResults;
        rule.path("ui").fields().forEachRemaining(field -> {
            if (substitution(field.getValue())) return;
            boolean resolved = Set.of("report", "preview").contains(field.getKey());
            templateReferences(field.getValue(), path + "/ui/" + field.getKey(), false, true,
                    (refs, die, at) -> localReferences(refs, args, resolved ? inputs : Set.of(), resolved && deferredInputs,
                            resolved ? results : Set.of(), resolved && deferredResults, at));
        });
        if (rule.has("after") && !substitution(rule.get("after")))
            validateRecipeLocalReferences(afterRule(rule.get("after")), args, path + "/after");
    }

    private static void localReferences(Set<String> refs, JsonNode args, Set<String> inputs, boolean unknownInputs,
                                        Set<String> results, boolean unknownResults, String path) {
        for (String ref : refs) {
            RuleExpression.validateRead(ref, path);
            String[] parts = ref.split("\\.");
            boolean valid = switch (parts[0]) {
                case "args" -> args.has(parts[1]);
                case "input" -> unknownInputs || inputs.contains(parts[1]);
                case "result" -> unknownResults || results.contains(parts[1]);
                default -> true;
            };
            if (!valid) throw RuleJson.error(path, "Unknown or unavailable reference: " + ref);
        }
    }

    private static void references(Set<String> references, ObjectNode args, Decl decl, Set<String> inputs,
                                   Set<String> results, boolean die, String path) {
        for (String ref : references) {
            String[] parts = ref.split("\\.");
            boolean valid = switch (parts[0]) {
                case "args" -> args.has(parts[1]);
                case "state" -> decl.state().has(parts[1]);
                case "input" -> inputs.contains(parts[1]);
                case "result" -> results.contains(parts[1]);
                case "die" -> die && Set.of("id", "face", "sides", "kept", "origin", "poolId", "role",
                        "previous", "editedBy", "contributionId", "sourceId", "matches").contains(parts[1]);
                case "source" -> Set.of("id", "instanceId", "name", "quantity").contains(parts[1])
                        || "attachedTo".equals(parts[1]) && decl.attachable() && parts.length == 3 && ATTACHED_TO_FACTS.contains(parts[2]);
                case "gear" -> false;
                case "pool" -> decl.pools().has(parts[1]);
                case "sourcePools" -> decl.pools().has(parts[1]) && "source".equals(decl.pools().path(parts[1]).path("scope").asText());
                case "effects" -> parts.length == 2 && decl.effects().has(parts[1]);
                default -> true;
            };
            if (!valid) throw RuleJson.error(path, "Unknown or unavailable reference: " + ref);
        }
    }

    private static void validateOperation(JsonNode operation, boolean cost, Decl decl, String path) {
        validateOperation(operation, cost, decl, path, false);
    }

    private static void validateOperation(JsonNode operation, boolean cost, Decl decl, String path, boolean deferSourceDeclarations) {
        ObjectNode state = decl.state(), pools = decl.pools();
        String op = RuleJson.text(operation.path("op"), path + "/op");
        List<String> fields = validateOperationShape(operation, cost, op, path);
        if (operation.has("if")) RuleExpression.validate(operation.get("if"), path + "/if");
        if (operation.has("as")) slot(RuleJson.text(operation.get("as"), path + "/as"), path + "/as");
        if ("damage.resolve".equals(op)) {
            validateDamageResolution(operation, path);
            return;
        }
        if (Set.of("damage.assess", "damage.mark").contains(op)) {
            validateMarksTracks(operation, "damage.assess".equals(op), path);
            return;
        }
        if ("damage.adjust".equals(op)) {
            if (!StructuredDamage.MARK_FIELDS.containsKey(RuleJson.text(operation.get("field"), path + "/field")))
                throw RuleJson.error(path + "/field", "Expected one of " + new java.util.TreeSet<>(StructuredDamage.MARK_FIELDS.keySet()) + ".");
            RuleExpression.validate(operation.get("from"), path + "/from");
            RuleExpression.validate(operation.get("to"), path + "/to");
            RuleTemplate.validate(RuleJson.text(operation.get("id"), path + "/id"));
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            return;
        }
        if ("event.block".equals(op)) {
            RuleTemplate.validate(RuleJson.text(operation.get("reason"), path + "/reason"));
            return;
        }
        if ("limit.restore".equals(op)) {
            if (!Set.of("actor", "recipient").contains(operation.path("to").asText()))
                throw RuleJson.error(path + "/to", "Expected actor or recipient.");
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            return;
        }
        if ("limits.refresh".equals(op)) {
            resetEvents(operation.get("on"), path + "/on");
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            return;
        }
        if ("plan.adjust".equals(op)) {
            if (!RuleJson.text(operation.get("plan"), path + "/plan").matches("event(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
                throw RuleJson.error(path + "/plan", "Expected a host-provided event plan path such as event.rest.");
            RuleJson.id(operation.get("field"), path + "/field");
            RuleExpression.validate(operation.get("by"), path + "/by");
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            validatePartyRecovery(operation, path, false);
            return;
        }
        if ("dice.table".equals(op)) {
            RuleExpression.validate(operation.get("count"), path + "/count");
            JsonNode outcomes = operation.get("outcomes");
            if (!outcomes.isArray() || outcomes.size() < 2 || outcomes.size() > 100)
                throw RuleJson.error(path + "/outcomes", "Expected 2-100 outcomes, one per die face.");
            for (int i = 0; i < outcomes.size(); i++) RuleTemplate.validate(RuleJson.text(outcomes.get(i), path + "/outcomes/" + i));
            return;
        }
        if ("inventory.grant".equals(op)) {
            if (!RuleCatalog.CATEGORIES.contains(RuleJson.text(operation.get("catalog"), path + "/catalog")))
                throw RuleJson.error(path + "/catalog", "Unknown catalog category.");
            RuleExpression.validate(operation.get("entry"), path + "/entry");
            RuleExpression.validate(operation.get("quantity"), path + "/quantity");
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            if (operation.has("to") && !Set.of("actor", "recipient").contains(operation.path("to").asText()))
                throw RuleJson.error(path + "/to", "Expected actor or recipient.");
            if (operation.has("tag")) RuleJson.id(operation.get("tag"), path + "/tag");
            if (operation.has("max")) RuleExpression.validate(operation.get("max"), path + "/max");
            if (operation.has("scope") && !operation.has("max")) throw RuleJson.error(path + "/scope", "A cap scope requires max.");
            if (operation.has("scope") && !Set.of("actor", "party").contains(operation.path("scope").asText()))
                throw RuleJson.error(path + "/scope", "Expected actor or party.");
            return;
        }
        if ("recovery.offerRoll".equals(op) || "downtime.offerRoll".equals(op)) {
            RuleExpression.validate(operation.get("roll"), path + "/roll");
            if ("recovery.offerRoll".equals(op) && !RuleJson.text(operation.get("track"), path + "/track")
                    .matches("(actor|recipient)\\.[a-zA-Z][a-zA-Z0-9_-]*"))
                throw RuleJson.error(path + "/track", "Expected an actor or recipient recovery track.");
            if (operation.has("offset")) RuleExpression.validate(operation.get("offset"), path + "/offset");
            if (operation.has("group") && !operation.get("group").isBoolean())
                throw RuleJson.error(path + "/group", "Expected a boolean.");
            RuleTemplate.validate(RuleJson.text(operation.get("label"), path + "/label"));
            return;
        }
        if ("roll.reroll".equals(op) && !Set.of("new", "higher", "higherTotal").contains(operation.path("choose").asText()))
            throw RuleJson.error(path + "/choose", "Expected new, higher or higherTotal.");
        if ("roll.explode".equals(op)) RuleJson.integer(operation.get("maxDice"), path + "/maxDice", 1, 32);
        if ("track.capacity".equals(op) && operation.has("counter")) {
            String track = RuleJson.text(operation.get("track"), path + "/track");
            String counter = RuleJson.text(operation.get("counter"), path + "/counter");
            if (!track.matches("(actor|recipient)\\.[a-zA-Z][a-zA-Z0-9_-]*")
                    || !counter.matches("(actor|recipient)\\.[a-zA-Z][a-zA-Z0-9_-]*")
                    || !counter.substring(0, counter.indexOf('.')).equals(track.substring(0, track.indexOf('.')))
                    || counter.equals(track))
                throw RuleJson.error(path + "/counter", "The reserve counter must be another fact of the track's owner.");
        }
        if ("track.capacity".equals(op)) {
            boolean directed = operation.has("amount") || operation.has("direction");
            if (directed && (operation.has("by") || !operation.has("counter")
                    || !operation.has("amount") || !Set.of("reserve", "release").contains(operation.path("direction").asText())))
                throw RuleJson.error(path, "A directed capacity change requires counter, amount and reserve/release direction instead of by.");
            if (!directed && !operation.has("by"))
                throw RuleJson.error(path + "/by", "A capacity change needs by or a directed amount.");
        }
        if ("roll.annotate".equals(op)) {
            RuleExpression.validate(operation.get("from"), path + "/from");
            RuleJson.id(operation.get("field"), path + "/field");
            RuleExpression.validate(operation.get("value"), path + "/value");
            return;
        }
        if ("roll.contribute".equals(op)) {
            RuleExpression.validate(operation.get("from"), path + "/from");
            validateContribution(operation.get("contribution"), path + "/contribution");
            return;
        }
        for (String field : fields) {
            if (!operation.has(field)) continue;
            JsonNode value = operation.path(field);
            if ("pool".equals(field) && Set.of("pool.spend", "pool.gain", "pool.roll", "pool.removeDie", "roll.addPoolDie").contains(op)) {
                String pool = RuleJson.id(value, path + "/pool");
                if (!deferSourceDeclarations && !pools.has(pool))
                    throw RuleJson.error(path + "/pool", "Undeclared pool: " + pool);
                if (pools.has(pool)) {
                    String requiredKind = "pool.roll".equals(op) || "pool.removeDie".equals(op) || "roll.addPoolDie".equals(op) ? "dice" : "number";
                    if (!requiredKind.equals(pools.path(pool).path("kind").asText()))
                        throw RuleJson.error(path + "/pool", "Operation requires a " + requiredKind + " pool.");
                }
            } else if ("flag".equals(field)) {
                String target = RuleJson.text(value, path + "/flag");
                if (!target.matches("(actor|recipient)\\.[a-zA-Z][a-zA-Z0-9_-]*"))
                    throw RuleJson.error(path + "/flag", "Expected an actor or recipient boolean fact.");
            } else if (Set.of("pool", "track").contains(field)) {
                String target = RuleJson.text(value, path + "/" + field);
                if (("resource.spend".equals(op) || "resource.gain".equals(op)) && "pool".equals(field)
                        || Set.of("track.mark", "track.clear", "track.capacity").contains(op) && "track".equals(field)) {
                    if (!target.matches("(actor|recipient)\\.[a-zA-Z][a-zA-Z0-9_-]*"))
                        throw RuleJson.error(path + "/" + field, "Expected an actor or recipient pool/track, not an arbitrary state path.");
                    if (cost && target.startsWith("recipient."))
                        throw RuleJson.error(path + "/" + field, "Costs are charged to the source actor; put target changes in the action steps.");
                } else throw RuleJson.error(path + "/" + field, "Invalid operation target.");
            } else if (Set.of("counter", "direction").contains(field) && "track.capacity".equals(op)) {
                continue;
            } else if ("slot".equals(field)) {
                String name = RuleJson.text(value, path + "/slot");
                slot(name, path + "/slot");
                if (!deferSourceDeclarations && !state.has(name)) throw RuleJson.error(path + "/slot", "Undeclared state slot: " + name);
            } else if ("role".equals(field)) RuleJson.id(value, path + "/role");
            else if ("effect".equals(field)) {
                String effect = RuleJson.id(value, path + "/effect");
                if (!deferSourceDeclarations) {
                    if (!decl.effects().has(effect))
                        throw RuleJson.error(path + "/effect", "Undeclared effect: " + effect);
                    if (decl.effects().get(effect).has("while"))
                        throw RuleJson.error(path + "/effect", "A derived effect follows its while condition and cannot be started or ended: " + effect);
                }
            } else if ("target".equals(field)) {
                if (!Set.of("actor", "recipient").contains(value.asText()))
                    throw RuleJson.error(path + "/target", "Expected actor or recipient.");
            } else if ("owner".equals(field)) {
                if (!Set.of("actor", "recipient").contains(value.asText()))
                    throw RuleJson.error(path + "/owner", "Expected actor or recipient.");
                if (!Set.of("pool.spend", "pool.gain", "pool.roll", "pool.removeDie", "roll.addPoolDie").contains(op))
                    throw RuleJson.error(path + "/owner", "Only pool operations may select an owner.");
                if (cost && "recipient".equals(value.asText()))
                    throw RuleJson.error(path + "/owner", "Costs are charged to the source actor.");
                String poolId = operation.path("pool").asText();
                if (!deferSourceDeclarations && (!pools.has(poolId) || !"actor".equals(pools.path(poolId).path("scope").asText())))
                    throw RuleJson.error(path + "/owner", "Only declared actor-scoped pools can select an owner.");
            } else if ("limit".equals(field) && !"claim.offer".equals(op)) {
                String limit = RuleJson.id(value, path + "/limit");
                if (!deferSourceDeclarations && !decl.limits().has(limit))
                    throw RuleJson.error(path + "/limit", "Undeclared use budget: " + limit);
            }
            else if ("collection".equals(field)) {
                if (!COLLECTIONS.contains(RuleJson.text(value, path + "/collection")))
                    throw RuleJson.error(path + "/collection", "Unknown actor collection: " + value.asText());
            }
            else if ("to".equals(field) && "damage.deal".equals(op)) {
                if (!Set.of("actor", "recipient", "attacker").contains(value.asText()))
                    throw RuleJson.error(path + "/to", "Expected actor, recipient or attacker.");
            }
            else if ("group".equals(field) && "claim.offer".equals(op)) RuleJson.id(value, path + "/group");
            else if ("resource".equals(field) && Set.of("table.gain", "table.spend").contains(op)) RuleJson.id(value, path + "/resource");
            else if (Set.of("id", "label").contains(field) && Set.of("damage.reduce", "damage.increase", "damage.deal", "countdown.start", "claim.offer", "table.gain", "table.spend", "effect.endLabeled").contains(op))
                RuleTemplate.validate(RuleJson.text(value, path + "/" + field));
            else if (Set.of("choose", "maxDice").contains(field)) continue;
            else if ("metadata".equals(field) && "roll.resolve".equals(op)) {
                if (!value.isObject() || value.size() > 16) throw RuleJson.error(path + "/metadata", "Expected a bounded object of expressions.");
                value.fields().forEachRemaining(entry -> {
                    RuleJson.id(com.fasterxml.jackson.databind.node.TextNode.valueOf(entry.getKey()), path + "/metadata");
                    RuleExpression.validate(entry.getValue(), path + "/metadata/" + entry.getKey());
                });
            }
            else if ("dieId".equals(field)) RuleExpression.validate(value, path + "/" + field);
            else RuleExpression.validate(value, path + "/" + field);
        }
    }

    private static List<String> validateOperationShape(JsonNode operation, boolean cost, String op, String path) {
        List<String> fields = OPERATIONS.get(op);
        if (fields == null || cost && !COSTS.contains(op)) throw RuleJson.error(path + "/op", "Unsupported operation in this phase: " + op);
        List<String> allowed = new ArrayList<>(fields);
        allowed.add("op");
        allowed.add("as");
        if (!cost) allowed.add("if");
        RuleJson.fields(operation, path, allowed.toArray(String[]::new));
        if (operation.has("if") && operation.has("as"))
            throw RuleJson.error(path + "/as", "A conditional step cannot name a result; later steps could read a skipped value.");
        for (String field : fields) if (!operation.has(field)
                && !("pool.roll".equals(op) && "keepHighest".equals(field))
                && !"owner".equals(field)
                && !("roll.resolve".equals(op) && "metadata".equals(field))
                && !("damage.resolve".equals(op) && "armor".equals(field))
                && !("track.capacity".equals(op) && Set.of("by", "counter", "amount", "direction").contains(field))
                && !("claim.offer".equals(op) && "limit".equals(field))
                && !("plan.adjust".equals(op) && "partyRecovery".equals(field))
                && !(Set.of("recovery.offerRoll", "downtime.offerRoll").contains(op) && "offset".equals(field))
                && !("downtime.offerRoll".equals(op) && "group".equals(field))
                && !("inventory.grant".equals(op) && Set.of("to", "tag", "max", "scope").contains(field)))
            throw RuleJson.error(path + "/" + field, "Missing operation field.");
        if ("plan.adjust".equals(op)) validatePartyRecovery(operation, path, true);
        return fields;
    }

    private static void validatePartyRecovery(JsonNode operation, String path, boolean allowSubstitutions) {
        if (!operation.has("partyRecovery")) return;
        JsonNode recovery = operation.get("partyRecovery");
        String at = path + "/partyRecovery";
        if (allowSubstitutions && substitution(recovery)) return;
        RuleJson.fields(recovery, at, "track", "scope", "afterRest");
        JsonNode plan = operation.path("plan");
        if (!(allowSubstitutions && substitution(plan)) && !"event.move".equals(plan.asText()))
            throw RuleJson.error(at, "Party recovery requires an event.move plan.");
        JsonNode track = recovery.path("track"), scope = recovery.path("scope");
        if (!(allowSubstitutions && substitution(track))
                && !RuleJson.text(track, at + "/track").matches("[a-zA-Z][a-zA-Z0-9_-]*"))
            throw RuleJson.error(at + "/track", "Expected an actor track name.");
        if (!(allowSubstitutions && substitution(scope)) && !TARGET_SCOPES.contains(RuleJson.text(scope, at + "/scope")))
            throw RuleJson.error(at + "/scope", "Expected a host character scope.");
        if (!recovery.has("afterRest")) return;
        JsonNode schedule = recovery.get("afterRest");
        if (!(allowSubstitutions && substitution(schedule))) {
            RuleJson.fields(schedule, at + "/afterRest", "sourcePath", "move");
            if (!(allowSubstitutions && substitution(schedule.path("sourcePath"))))
                SheetProjection.path(schedule.path("sourcePath"), at + "/afterRest/sourcePath");
            if (!(allowSubstitutions && substitution(schedule.path("move"))))
                RuleJson.id(schedule.path("move"), at + "/afterRest/move");
        }
        if (operation.has("if"))
            throw RuleJson.error(path + "/if", "A scheduled recovery cannot be a conditional step.");
        if (!(allowSubstitutions && substitution(operation.path("by"))))
            RuleJson.integer(operation.path("by"), path + "/by", 1, 1000);
        if (!(allowSubstitutions && substitution(operation.path("label")))) {
            String label = RuleJson.text(operation.path("label"), path + "/label");
            if (!RuleTemplate.references(label).isEmpty())
                throw RuleJson.error(path + "/label", "A scheduled recovery needs a literal label.");
        }
    }

    private static void validateScheduledRecoveryPlacement(JsonNode rule, String path, boolean allowSubstitutions) {
        boolean scheduled = false;
        for (JsonNode step : rule.path("do"))
            scheduled |= step.path("partyRecovery").has("afterRest");
        if (!scheduled) return;
        if (rule.has("limitRef"))
            throw RuleJson.error(path + "/limitRef", "Scheduled recoveries cannot use a limited rule.");
        JsonNode bindings = rule.path("bindings");
        if (allowSubstitutions && substitution(bindings)) return;
        if (!bindings.isArray() || bindings.size() != 1)
            throw RuleJson.error(path + "/bindings", "A scheduled recovery needs exactly one automatic downtime binding.");
        JsonNode binding = bindings.get(0);
        if (allowSubstitutions && substitution(binding)) return;
        for (String field : List.of("event", "surface", "mode")) {
            JsonNode value = binding.path(field);
            if (allowSubstitutions && substitution(value)) continue;
            String expected = switch (field) {
                case "event" -> "downtime.move";
                case "surface" -> "downtime";
                default -> "automatic";
            };
            if (!expected.equals(value.asText()))
                throw RuleJson.error(path + "/bindings", "A scheduled recovery needs exactly one automatic downtime binding.");
        }
        if (binding.has("forEach") || binding.has("targetSelection") || binding.has("claimGroup"))
            throw RuleJson.error(path + "/bindings", "A scheduled recovery cannot select targets or claims.");
    }

    /** Static {@code options} or one {@code optionsFrom} expression yielding {@code [{id, label}]}; returns the static maximum. */
    private static int validateOptions(JsonNode input, String path) {
        if (input.has("options") == input.has("optionsFrom"))
            throw RuleJson.error(path + "/options", "Declare exactly one of options or optionsFrom.");
        if (input.has("optionsFrom")) {
            RuleExpression.validate(input.get("optionsFrom"), path + "/optionsFrom");
            return 64;
        }
        JsonNode options = input.path("options");
        if (!options.isArray() || options.isEmpty() || options.size() > 64)
            throw RuleJson.error(path + "/options", "Expected 1-64 options.");
        Set<String> optionIds = new HashSet<>();
        for (JsonNode option : options) {
            RuleJson.fields(option, path + "/options", "id", "label", "when");
            if (!optionIds.add(RuleJson.id(option.path("id"), path + "/options/id")))
                throw RuleJson.error(path + "/options", "Duplicate option ID.");
            RuleTemplate.validate(RuleJson.text(option.path("label"), path + "/options/label"));
            if (option.has("when")) RuleExpression.validate(option.get("when"), path + "/options/when");
        }
        return options.size();
    }

    private static void validateContribution(JsonNode part, String path) {
        RuleJson.fields(part, path, CONTRIBUTION_FIELDS.toArray(String[]::new));
        RuleJson.id(part.get("id"), path + "/id");
        RuleJson.id(part.get("role"), path + "/role");
        RuleTemplate.validate(RuleJson.text(part.get("label"), path + "/label"));
        if (part.has("editable")) RuleExpression.bool(part.get("editable"), path + "/editable");
        List<String> required = switch (part.path("kind").asText()) {
            case "dice" -> List.of("count", "sides");
            case "flat" -> List.of("value");
            case "scale" -> List.of("of", "factor");
            default -> throw RuleJson.error(path + "/kind", "Expected dice, flat or scale.");
        };
        Set<String> allowed = new HashSet<>(Set.of("id", "kind", "role", "label", "editable"));
        allowed.addAll(required);
        if ("dice".equals(part.path("kind").asText())) allowed.addAll(Set.of("keepHighest", "maximize", "sign"));
        part.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw RuleJson.error(path + "/" + field, "Field is not valid for this contribution kind.");
        });
        for (String field : required) if (!part.has(field)) throw RuleJson.error(path + "/" + field, "Missing contribution field.");
        if (part.has("of") && !StructuredRoll.scalesAll(part.get("of"))) StructuredRoll.ids(part.get("of"), path + "/of", true);
        for (String field : List.of("count", "sides", "keepHighest", "maximize", "value", "factor"))
            if (part.has(field)) RuleExpression.validate(part.get(field), path + "/" + field);
        if (part.has("sign")) {
            int sign = RuleJson.integer(part.get("sign"), path + "/sign", -1, 1);
            if (sign == 0) throw RuleJson.error(path + "/sign", "Dice sign must be -1 or 1.");
        }
    }

    private static void validateDamageResolution(JsonNode operation, String path) {
        RuleJson.fields(operation, path, "op", "from", "thresholds", "armor", "hitPoints", "as");
        RuleExpression.validate(operation.get("from"), path + "/from");

        JsonNode thresholds = operation.get("thresholds");
        RuleJson.fields(thresholds, path + "/thresholds", "majorAt", "severeAt");
        RuleExpression.validate(thresholds.get("majorAt"), path + "/thresholds/majorAt");
        RuleExpression.validate(thresholds.get("severeAt"), path + "/thresholds/severeAt");

        if (operation.has("armor")) {
            JsonNode armor = operation.get("armor");
            RuleJson.fields(armor, path + "/armor", "track", "marked", "capacity", "spend", "severitySteps");
            validateTrackTarget(armor, path + "/armor");
            RuleExpression.validate(armor.get("spend"), path + "/armor/spend");
            RuleJson.integer(armor.get("severitySteps"), path + "/armor/severitySteps", 1, 3);
        }

        JsonNode hitPoints = operation.get("hitPoints");
        RuleJson.fields(hitPoints, path + "/hitPoints", "track", "marked", "capacity", "minor", "major", "severe");
        validateTrackTarget(hitPoints, path + "/hitPoints");
        for (String severity : List.of("minor", "major", "severe"))
            RuleExpression.validate(hitPoints.get(severity), path + "/hitPoints/" + severity);

        if (operation.has("armor")
                && operation.path("armor").path("track").asText().equals(operation.path("hitPoints").path("track").asText()))
            throw RuleJson.error(path, "Armor and hit point tracks must be different.");
    }

    private static void validateMarksTracks(JsonNode operation, boolean assess, String path) {
        RuleExpression.validate(operation.get("from"), path + "/from");
        if (assess) {
            RuleExpression.validate(operation.get("type"), path + "/type");
            RuleExpression.validate(operation.get("massive"), path + "/massive");
            JsonNode thresholds = operation.get("thresholds");
            RuleJson.fields(thresholds, path + "/thresholds", "majorAt", "severeAt");
            RuleExpression.validate(thresholds.get("majorAt"), path + "/thresholds/majorAt");
            RuleExpression.validate(thresholds.get("severeAt"), path + "/thresholds/severeAt");
        }
        JsonNode armor = operation.get("armor");
        if (assess) {
            RuleJson.fields(armor, path + "/armor", "track", "marked", "capacity", "requested");
            RuleExpression.validate(armor.get("requested"), path + "/armor/requested");
        } else RuleJson.fields(armor, path + "/armor", "track", "marked", "capacity");
        validateTrackTarget(armor, path + "/armor");
        JsonNode hitPoints = operation.get("hitPoints");
        RuleJson.fields(hitPoints, path + "/hitPoints", "track", "marked", "capacity");
        validateTrackTarget(hitPoints, path + "/hitPoints");
        if (armor.path("track").asText().equals(hitPoints.path("track").asText()))
            throw RuleJson.error(path, "Armor and hit point tracks must be different.");
    }

    private static void validateTrackTarget(JsonNode track, String path) {
        String target = RuleJson.text(track.get("track"), path + "/track");
        RuleExpression.validateRead(target, path + "/track");
        if (!target.startsWith("actor.") || target.split("\\.").length < 2)
            throw RuleJson.error(path + "/track", "Damage tracks must be actor-owned.");
        for (String field : List.of("marked", "capacity")) {
            String name = RuleJson.text(track.get(field), path + "/" + field);
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_-]*"))
                throw RuleJson.error(path + "/" + field, "Invalid track field.");
        }
    }

    private static void slot(String name, String path) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,63}") || Set.of("__proto__", "constructor", "prototype").contains(name))
            throw RuleJson.error(path, "Invalid state or variable name.");
    }
}
