package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One-time migration tool from format-1 automation blocks to format-2 rules. Every block either converts completely or
 * is reported as blocked with path-specific diagnostics; nothing is guessed. The tool writes to a staging directory and
 * never touches the shipped catalogs or runs at application runtime.
 */
public final class LegacyCatalogConverter {
    public record Diagnostic(String path, String sourceId, String message) {}

    public record Result(ObjectNode catalog, int blocks, int converted, List<Diagnostic> diagnostics, Map<String, String> assignedIds) {}

    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}");
    private static final List<String> FAMILIES = List.of("actions", "rollOptions", "rollTriggers", "conditionalRollTriggers", "mitigation");
    private static final Set<String> BLOCK_FIELDS = Set.of("actions", "reloading", "uses", "modifiers", "resources", "rollOptions", "rollTriggers",
            "conditionalRollTriggers", "mitigation", "spellcastDifficulty", "automaticRestUse", "automaticRestUseOnAttempt",
            "damage", "alternateProfiles", "damageTypeChoice", "rest", "restReroll", "attach", "grantsFeatures", "groupGrantedFeatures", "channelBenefit", "conditionalModifiers", "conditionalActions", "conditionalRollOptions",
            "hopeReward", "classResourceOnly", "armorHalfEffect", "armorFullHeldEffect", "armorFullEffect", "zeroHopeClearArmor",
            "overflowHope", "criticalMelee", "deathMove", "manual", "creation", "usesOverrides", "effectCounters", "lunarPhases", "hideSpellcast", "companion", "companionTraining", "conditionalDamageRewards");
    private static final Set<String> DEATH_KINDS = Set.of("hopeBonus", "hopeSpend", "mask", "spectral", "egg", "battleHardened",
            "unbreakable", "undying", "lifeWard");
    /** Legacy gated families that are their base family plus extra conditions, which every family converts the same way. */
    private static final Map<String, String> GATED_FAMILIES = Map.of("conditionalActions", "actions", "conditionalRollOptions", "rollOptions");
    private static final Map<String, String> RANGES = Map.of("melee", "melee", "very close", "veryClose", "veryclose", "veryClose",
            "close", "close", "far", "far", "very far", "veryFar", "veryfar", "veryFar");
    private static final Set<String> RESOURCE_FIELDS = Set.of("id", "label", "initial", "max", "resetOn", "resetValue", "minimumReset",
            "session", "sessionHope", "sessionReset", "cardOnly", "whileEffect");
    private static final Set<String> OPTION_FIELDS = Set.of("id", "label", "description", "roll", "value", "dice", "advantage", "cost",
            "defaultOn", "exclusive", "requiresEffect", "excludesEffect", "weapon", "weaponRange", "excludeRolls", "effect", "uses",
            "usesSource", "endEffect", "hostOwned", "consumeItem", "quantity", "proficiency", "hopeDie", "diceResource", "condition", "loadoutDomain",
            "required", "traitValue", "multiplier", "allResourceDice", "steps", "damageDie", "diceByLevel", "diceByFeature", "diceConditions", "magicOnly", "weaponRanges",
            "ignoreDisadvantage", "matchingDice", "resourceMultiplier", "input");
    private static final Set<String> TRIGGER_FIELDS = Set.of("id", "label", "description", "on", "cost", "reroll", "when", "outcome",
            "audience", "damage", "sendRoll", "report", "requiresEffect", "excludesEffect", "weaponRange", "failedOnly", "excludeRolls", "die", "uses",
            "usesSource", "steps", "criticalOnly", "weaponAttack", "endEffect", "successIfKnown", "difficulty", "exclusiveRollGroup", "hostOwned",
            "chatBenefits", "allowSourceOwner", "loadoutDomain", "primaryWeapon", "anyWeapon", "advantage", "giveFear", "check", "choices",
            "magicOnly", "weaponRanges", "addDice", "damageDice", "damageDiceBelow", "rerollDualityDie", "spendHopeAmount", "disadvantage", "removeFear", "weaponDamage", "rollSection", "containsDie", "resourceRoll", "selectAmount",
            "target", "excludeSourceActor", "splendorAction", "hostRollAction", "otherPlayerOnly", "spentTokenDamage", "bloodAutomation", "swapDuality", "repeatable");
    private static final Set<String> MITIGATION_FIELDS = Set.of("id", "label", "description", "when", "cost", "negate", "halve", "reduce",
            "passive", "report", "requiresEffect", "excludesEffect", "defaultOn", "effect", "uses", "usesSource", "endEffect", "hostOwned",
            "roll", "severity", "hp", "replaceHp", "armorSlots", "armorSeverity", "armorBlocked", "saveArmor", "insteadOfArmor",
            "markedHpOnly", "finalSevere", "markStress", "gainHope", "giveFear", "clearStress", "gainTokens", "armorCost", "followUp", "hiddenRoll", "consumeItem", "preserveAfterIncomingDamage", "loadoutDomain",
            "double", "recoveryChoice", "reflectHp", "quantity", "advancesSourcePool", "patronDice", "destroyResourceOnMax", "reportRoll", "companionIntercept", "companionArmored");
    private static final List<String> MARKS_FIELDS = List.of("severity", "hp", "replaceHp", "armorSlots", "armorSeverity", "armorBlocked", "saveArmor");
    private static final List<String> PREPARE_FIELDS = List.of("negate", "halve", "reduce", "double");
    private static final List<String> SEVERITIES = List.of("minor", "major", "severe", "massive");
    private static final Pattern DICE = Pattern.compile("([1-9][0-9]?)d([1-9][0-9]{0,2})");
    private static final Pattern ACTION_DAMAGE = Pattern.compile("(?i)(\\d*)d(\\d+)(?:([+-])(\\d+))?");
    private static final Pattern REPORT_DICE_TERM = Pattern.compile("([+-]?)(?:(\\d*)[dD](\\d+)|(\\d+))");
    private static final Pattern POOL_DICE = Pattern.compile("@([a-z][a-z0-9_]*)(d\\d+(?:[+-]\\d+)?)");
    private static final Set<String> ACTION_FIELDS = Set.of("toggleEffect", "splendorAction", "id", "label", "description", "cost", "steps", "report", "damage",
            "requiresEffect", "excludesEffect", "endEffect", "uses", "usesSource", "actionRoll", "target",
            "excludeSourceActor", "hostOwned", "selectAmount", "resourceRoll", "timing", "sendRoll", "chatBenefits", "allowSourceOwner", "benefitsPerAmount",
            "channel", "aura", "loadoutDomain", "requiresZeroHope", "check", "choices", "minimumHope", "keepItem", "portions", "refreshUses", "transferHope", "downtime", "table",
            "hostRollAction", "bloodAutomation", "castTokens", "patronDice", "witchAction", "requiresCompanion", "specialAction");
    private static final Set<String> BENEFIT_FIELDS = Set.of("id", "label", "steps", "exclusiveClaim", "featureAction", "cost", "report",
            "sourceOnly", "transferStress");
    private static final Set<String> EFFECT_FIELDS = Set.of("name", "kind", "until", "text", "modifiers",
            "actions", "rollOptions", "rollTriggers", "mitigation", "rest", "consumeLongRestMove", "group", "endOnDamage", "endOnSourceSevere", "endAfterIncomingDamage", "damage",
            "unique", "exclusiveSource", "endOnMarkedHp", "endOnDealtDamage", "endOnAttack", "endOnAction", "lifeWard", "tokenResource", "maintainOnAction");
    private static final Set<String> REST_FIELDS = Set.of("extraMoves", "longMovesInShort", "passives", "modifiers", "requiresEffect", "expireItem");
    private static final Set<String> PASSIVE_FIELDS = Set.of("id", "label", "defaultOn", "condition", "steps", "extraMoves", "consumeItem",
            "rest", "move", "bonus", "minHope", "cost", "chooseAlly", "hostOwned", "castBeforeRest", "requiresEffect", "excludesEffect");
    private static final Set<String> MOVE_MODIFIER_FIELDS = Set.of("move", "rest", "target", "bonus", "steps", "note", "repairAllies");
    private static final Set<String> ROLLS = Set.of("attack", "damage", "spellcast", "action", "reaction");
    private static final List<String> TRAITS = List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge");
    private static final Map<String, String> TRACKS = Map.of("hp", "actor.hp", "stress", "actor.stress", "armor", "actor.armorTrack");
    private static final Map<String, List<String>> RESETS = Map.of(
            "rest", List.of("rest.short", "rest.long"), "shortRest", List.of("rest.short"), "longRest", List.of("rest.long"),
            "scene", List.of("scene.end"), "session", List.of("session.start"));
    static final String EMPTY_RECIPES = "{\"format\":\"dmmap-daggerheart-rules\",\"version\":2,\"recipes\":[]}";

    private LegacyCatalogConverter() {}

    /** Shipped recipes that converted catalogs may reference with {@code useRecipe}. */
    public static String coreRecipes() {
        return sharedRulesResource("recipes.json");
    }

    /** Shipped default ruleset for conversion of catalogs without a shared rules directory. */
    public static String coreRuleset() {
        return sharedRulesResource("ruleset.json");
    }

    private static String sharedRulesResource(String file) {
        try (var in = LegacyCatalogConverter.class.getResourceAsStream("/dmmt/automation/" + file)) {
            if (in == null) throw new IllegalStateException("Missing shared rules resource: " + file);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Result convert(String legacyJson) throws IOException {
        return convert(legacyJson, Map.of());
    }

    public static Result convert(String legacyJson, Map<String, ObjectNode> sharedPools) throws IOException {
        return convert(legacyJson, sharedPools, RuleJson.parse(coreRecipes()));
    }

    private static Result convert(String legacyJson, Map<String, ObjectNode> sharedPools, JsonNode recipes) throws IOException {
        JsonNode parsed = RuleJson.parse(legacyJson, RuleJson.MAX_DOCUMENT_CHARACTERS, RuleJson.MAX_DOCUMENT_NODES);
        if (!parsed.isObject() || !"dmmap-daggerheart".equals(parsed.path("format").asText()) || parsed.path("version").asInt() != 1)
            throw new IllegalArgumentException("Expected a format-1 dmmap-daggerheart catalog.");
        ObjectNode catalog = ((ObjectNode) parsed).deepCopy();
        catalog.put("version", RuleCompiler.FORMAT_VERSION);
        String category = catalog.path("category").asText();
        Context context = new Context();
        JsonNode entries = catalog.path("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            String path = "/entries/" + i;
            if (!entry.isObject() || !ID.matcher(entry.path("id").asText()).matches()) {
                if (containsAutomation(entry)) context.diagnostics.add(new Diagnostic(path + "/id", category, "Entry without a valid ID cannot carry automation."));
                continue;
            }
            walk((ObjectNode) entry, category + ":" + entry.path("id").asText(), path, recipes, context, sharedPools);
        }
        return new Result(catalog, context.blocks, context.converted, List.copyOf(context.diagnostics), Map.copyOf(context.assignedIds));
    }

    private static final class Context {
        int blocks, converted;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, String> assignedIds = new TreeMap<>();
    }

    private static void walk(ObjectNode node, String sourceId, String path, JsonNode recipes, Context context,
                             Map<String, ObjectNode> shared) {
        if (node.has("automation")) {
            context.blocks++;
            List<Diagnostic> problems = new ArrayList<>();
            String origin = null;
            if (sourceId.startsWith("domain-cards:") && path.matches("/entries/\\d+")
                    && node.path("name").isTextual() && !node.path("name").asText().isBlank())
                origin = "card:" + node.path("name").asText();
            ObjectNode converted = convertBlock(node.get("automation"), path + "/automation", sourceId, problems, shared, origin);
            boolean empty = node.get("automation").isObject() && node.get("automation").isEmpty();
            if (problems.isEmpty() && !empty) {
                try { RuleCompiler.compile(sourceId, converted, recipes); }
                catch (IllegalArgumentException ex) { problems.add(new Diagnostic(path + "/automation", sourceId, "Converted block rejected: " + ex.getMessage())); }
            }
            if (problems.isEmpty() && empty) { node.remove("automation"); context.converted++; }
            else if (problems.isEmpty()) { node.set("automation", converted); context.converted++; }
            else { node.remove("automation"); context.diagnostics.addAll(problems); }
        }
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (key.equals("automation") || key.startsWith("_") || !containsAutomation(child)) continue;
            String childPath = path + "/" + key, scope = sourceId + ":" + key;
            if (child.isArray()) {
                Set<String> used = new HashSet<>();
                for (JsonNode member : child)
                    if (member.isObject() && ID.matcher(member.path("id").asText()).matches()) used.add(member.path("id").asText());
                for (int i = 0; i < child.size(); i++) {
                    JsonNode member = child.get(i);
                    if (!member.isObject() || !containsAutomation(member)) continue;
                    String at = childPath + "/" + i;
                    String id = member.path("id").asText();
                    if (!ID.matcher(id).matches()) {
                        id = unique(slug(member.path("name").asText(member.path("label").asText(key))), used);
                        ((ObjectNode) member).put("id", id);
                        context.assignedIds.put(at, id);
                    }
                    walk((ObjectNode) member, scope + ":" + id, at, recipes, context, shared);
                }
            } else if (child.isObject()) {
                ObjectNode object = (ObjectNode) child;
                if (object.has("automation") || object.has("id")) {
                    if (!ID.matcher(object.path("id").asText()).matches()) {
                        object.put("id", slug(object.path("name").asText(key)));
                        context.assignedIds.put(childPath, object.path("id").asText());
                    }
                    scope += ":" + object.path("id").asText();
                }
                walk(object, scope, childPath, recipes, context, shared);
            }
        }
    }

    private static boolean isDeathrun(JsonNode legacy) {
        for (JsonNode trigger : legacy.path("rollTriggers")) if (trigger.path("deathrunResult").asBoolean()) return true;
        return false;
    }

    /** The saved-damage run becomes one instance of the shared repeat-damage recipe with the legacy constants as arguments. */
    private static ObjectNode deathrun(JsonNode legacy, String path, Problems p) {
        p.onlyFields(legacy, path, Set.of("actions", "rollOptions", "rollTriggers"));
        ObjectNode out = RuleJson.JSON.createObjectNode();
        JsonNode cost = legacy.path("rollOptions").path(0).path("cost");
        ObjectNode spend = RuleJson.JSON.createObjectNode();
        if (cost.size() == 1 && cost.path("hope").isInt() && cost.path("hope").asInt() > 0)
            spend.put("op", "resource.spend").put("pool", "actor.hope").put("amount", cost.path("hope").asInt());
        else if (cost.size() == 1 && cost.path("stress").isInt() && cost.path("stress").asInt() > 0)
            spend.put("op", "track.mark").put("track", "actor.stress").put("amount", cost.path("stress").asInt());
        else { p.add(path + "/rollOptions/0/cost", "A repeated damage run needs one Hope or Stress price."); return out; }
        ObjectNode state = out.putObject("state");
        state.put("phase", "inactive").put("activation", 0).put("uses", 0).putNull("weaponId").putNull("firstDamage");
        state.putArray("originalEligibleDice");
        state.putArray("remainingDice");
        ObjectNode rule = out.putArray("rules").addObject();
        rule.put("id", "run").put("useRecipe", "core:repeat-saved-weapon-damage");
        ObjectNode args = rule.putObject("args");
        args.putArray("startCosts").add(spend);
        args.put("firstProficiencyBonus", 1).put("removeEachTime", 1);
        return out;
    }

    static ObjectNode convertBlock(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems) {
        return convertBlock(legacy, path, sourceId, problems, Map.of());
    }

    /** Resources are actor-scoped, so a pool declared by one source (for example a class) can be spent by another. */
    static ObjectNode convertBlock(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems,
                                   Map<String, ObjectNode> shared) {
        return convertBlock(legacy, path, sourceId, problems, shared, null);
    }

    private static ObjectNode convertBlock(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems,
                                           Map<String, ObjectNode> shared, String cardOrigin) {
        ObjectNode out = RuleJson.JSON.createObjectNode();
        Problems p = new Problems(sourceId, problems, new ArrayList<>());
        if (!legacy.isObject()) { p.add(path, "Automation must be an object."); return out; }
        if (legacy.has("attach")) return attachment(legacy, path, sourceId, problems, shared, p);
        if (isDeathrun(legacy)) return deathrun(legacy, path, p);
        p.onlyFields(legacy, path, BLOCK_FIELDS);
        boolean limited = false;
        if (legacy.has("uses")) {
            JsonNode uses = legacy.get("uses");
            p.onlyFields(uses, path + "/uses", Set.of("max", "per"));
            List<String> resets = RESETS.get(uses.path("per").asText());
            if (!uses.path("max").isInt() || uses.path("max").asInt() < 1) p.add(path + "/uses/max", "Expected a positive whole number.");
            else if (resets == null) p.add(path + "/uses/per", "Unsupported reset: " + uses.path("per").asText());
            else {
                ObjectNode limit = out.putObject("limits").putObject("uses");
                limit.put("max", uses.path("max").asInt());
                ArrayNode resetOn = limit.putArray("resetOn");
                resets.forEach(resetOn::add);
                limited = true;
            }
        }
        Pools pools = new Pools(RuleJson.JSON.createObjectNode(), shared);
        List<ObjectNode> lifecycle = new ArrayList<>();
        if (legacy.has("resources")) {
            JsonNode list = legacy.get("resources");
            for (int i = 0; i < list.size(); i++) resource(list.get(i), path + "/resources/" + i, p, pools.local(), lifecycle);
        }
        if (legacy.has("companion")) companion(legacy.get("companion"), path + "/companion", p, pools.local(), lifecycle);
        if (legacy.has("companionTraining") && (!legacy.path("companionTraining").isInt() || legacy.path("companionTraining").asInt() < 1))
            p.add(path + "/companionTraining", "Expected a positive whole number of training options.");
        ArrayNode passive = RuleJson.JSON.createArrayNode();
        for (String family : List.of("modifiers", "conditionalModifiers")) {
            JsonNode list = legacy.path(family);
            for (int i = 0; i < list.size(); i++) modifier(list.get(i), path + "/" + family + "/" + i, p, passive, true);
        }
        if (!passive.isEmpty()) out.set("modifiers", passive);
        if (legacy.has("grantsFeatures")) grants(legacy, path, p, out);
        else if (legacy.has("groupGrantedFeatures")) p.add(path + "/groupGrantedFeatures", "Grouping needs granted features.");
        if (legacy.has("usesOverrides")) usesOverrides(legacy.get("usesOverrides"), path + "/usesOverrides", p, out);
        ArrayNode rules = RuleJson.JSON.createArrayNode();
        ObjectNode effects = RuleJson.JSON.createObjectNode();
        Set<String> ruleIds = new HashSet<>();
        if (legacy.has("restReroll")) {
            if (!legacy.path("restReroll").isBoolean()) p.add(path + "/restReroll", "Expected a boolean.");
            else if (legacy.path("restReroll").asBoolean()) {
                ObjectNode reroll = restReroll(limited);
                reroll.put("id", unique(reroll.path("id").asText(), ruleIds));
                rules.add(reroll);
            }
        }
        String lunarPool = lunar(legacy, path, p, pools, effects, lifecycle);
        for (ObjectNode rule : lifecycle) {
            rule.put("id", unique(rule.path("id").asText(), ruleIds));
            rules.add(rule);
        }
        List<Nested> queue = new ArrayList<>(p.nested());
        p.nested().clear();
        if (legacy.has("channelBenefit")) {
            channelBenefit(legacy.get("channelBenefit"), path + "/channelBenefit", p, effects);
            queue.addAll(p.nested());
            p.nested().clear();
        }
        List<ObjectNode> extra = new ArrayList<>();
        List<ObjectNode> grants = new ArrayList<>();
        List<String> families = new ArrayList<>(FAMILIES);
        families.addAll(GATED_FAMILIES.keySet().stream().sorted().toList());
        for (String family : families) {
            JsonNode list = legacy.path(family);
            String base = GATED_FAMILIES.getOrDefault(family, family);
            for (int i = 0; i < list.size(); i++) queue.add(new Nested(base, list.get(i), path + "/" + family + "/" + i));
        }
        if (legacy.has("rest")) queue.add(new Nested("rest", legacy.get("rest"), path + "/rest"));
        if (legacy.has("creation")) queue.add(new Nested("creation", legacy.get("creation"), path + "/creation"));
        JsonNode death = legacy.path("deathMove");
        if (legacy.has("deathMove")) deathMove(death, path + "/deathMove", p, queue);
        for (int n = 0; n < queue.size() && n < 256; n++) {
            Nested next = queue.get(n);
            JsonNode legacyRule = next.legacy();
            if (Set.of("rest", "consumeLongRestMove", "endOnDamage", "endOnDealtDamage", "effectDamage", "undying", "lifeWard", "creation", "maintainEnd").contains(next.family())) {
                List<ObjectNode> restRules = switch (next.family()) {
                    case "rest" -> rest(legacyRule, next.path(), p, effects, pools, next.path().equals(path + "/rest") ? legacy : null, ruleIds);
                    case "undying" -> List.of(undying());
                    case "creation" -> creation(legacyRule, next.path(), p);
                    case "lifeWard" -> List.of(lifeWard(legacyRule.path("effect").asText()));
                    case "endOnDamage" -> List.of(endOnDamage(legacyRule));
                    case "endOnDealtDamage" -> List.of(endOnDealtDamage(legacyRule));
                    case "maintainEnd" -> List.of(maintainEnd(legacyRule));
                    case "effectDamage" -> effectDamage(legacyRule, next.path(), p);
                    default -> List.of(consumeLongRestMove(legacyRule, next.path()));
                };
                for (ObjectNode rule : restRules) {
                    rule.put("id", unique(rule.path("id").asText(), ruleIds));
                    rules.add(rule);
                }
                queue.addAll(p.nested());
                p.nested().clear();
                continue;
            }
            boolean usesBudget = limited && legacyRule.path("usesSource").asBoolean(true);
            if ("conditionalRollTriggers".equals(next.family()) && "zone".equals(legacyRule.path("splendorAction").asText()))
                legacyRule = zone(legacyRule, next.path(), p, effects, pools);
            legacyRule = witch(legacyRule, next.path(), p, pools, grants);
            ObjectNode rule = switch (next.family()) {
                case "actions" -> legacyRule.has("deathMove")
                        ? deathAction(legacyRule, death, next.path(), p, effects, pools, usesBudget, extra, out)
                        : action(legacyRule, next.path(), p, effects, pools, usesBudget);
                case "rollOptions" -> rollOption(legacyRule, next.path(), p, effects, pools, usesBudget, extra);
                case "slotModifier" -> {
                    ObjectNode option = rollOption(legacyRule, next.path(), p, effects, pools, false, extra);
                    if (option != null) ((ObjectNode) option.get("ui")).put("label", "{source.name}");
                    yield option;
                }
                case "rollTriggers", "conditionalRollTriggers" -> rollTrigger(legacyRule, next.path(), p, effects, pools, usesBudget);
                default -> mitigation(legacyRule, next.path(), p, effects, pools, usesBudget);
            };
            if (rule == null) { extra.clear(); grants.clear(); continue; }
            if (!grants.isEmpty()) {
                ArrayNode doSteps = rule.has("do") ? (ArrayNode) rule.get("do") : rule.putArray("do");
                grants.forEach(doSteps::add);
                grants.clear();
            }
            if (legacyRule.path("witchAction").asText("").startsWith("lunar")) {
                if (lunarPool == null) p.add(next.path() + "/witchAction", "Lunar actions need lunar phases.");
                else lunarSteps(legacyRule.get("witchAction").asText(), lunarPool, rule, rule.has("do") ? (ArrayNode) rule.get("do") : rule.putArray("do"), next.path(), p);
            }
            if ("actions".equals(next.family()) && legacyRule.has("downtime"))
                downtime(legacyRule, next.path(), p, rule, extra);
            if ("source".equals(legacyRule.path("uses").asText()) && !limited)
                p.add(next.path() + "/uses", "Rule references a source use budget that is not declared.");
            rule.put("id", unique(rule.path("id").asText(), ruleIds));
            if (legacyRule.path("uses").isObject()) ruleLimit(legacyRule.get("uses"), next.path() + "/uses", p, out, rule);
            if (legacyRule.has("consumeItem")) {
                if (!legacyRule.path("consumeItem").isBoolean()) p.add(next.path() + "/consumeItem", "Expected a boolean.");
                else if (legacyRule.path("consumeItem").asBoolean()) {
                    ArrayNode costs = rule.has("costs") ? (ArrayNode) rule.get("costs") : rule.putArray("costs");
                    costs.addObject().put("op", "inventory.consume").put("amount", 1);
                }
            }
            if ("actions".equals(next.family()) && p.sourceId().startsWith("consumables:") && next.path().matches(Pattern.quote(path) + "/actions/\\d+"))
                consumableUnit(legacyRule, next.path(), p, pools, rule);
            rules.add(rule);
            for (ObjectNode follow : extra) {
                follow.put("id", unique(follow.path("id").asText(), ruleIds));
                rules.add(follow);
            }
            extra.clear();
            if (!Set.of("rollOptions", "mitigation").contains(next.family()))
                for (ObjectNode benefit : chatBenefits(legacyRule, next.path(), p, effects, pools, rule, ruleIds)) rules.add(benefit);
            queue.addAll(p.nested());
            p.nested().clear();
        }
        keepEffects(rules, path, p, out);
        for (JsonNode rule : rules) for (JsonNode op : rule.path("do")) {
            if (!"effect.end".equals(op.path("op").asText())) continue;
            String label = escape(op.path("effect").asText());
            String id = null;
            for (var effect : (Iterable<java.util.Map.Entry<String, JsonNode>>) effects::fields)
                if (label.equals(effect.getValue().path("label").asText())) id = effect.getKey();
            if (id == null) {
                ObjectNode ending = (ObjectNode) op;
                String target = ending.path("target").asText("actor");
                ending.removeAll();
                ending.put("op", "effect.endLabeled").put("label", label).put("target", target);
            } else ((ObjectNode) op).put("effect", id);
        }
        ObjectNode castProfile = null;
        if (legacy.has("spellcastDifficulty")) {
            if (cardOrigin != null) castProfile = spellcastProfile(legacy, path, p, limited, cardOrigin);
            else {
                ObjectNode rule = spellcastRoll(legacy, path, p, limited);
                if (rule != null) {
                    rule.put("id", unique(rule.path("id").asText(), ruleIds));
                    rules.add(rule);
                }
            }
        } else for (String flag : List.of("automaticRestUse", "automaticRestUseOnAttempt"))
            if (legacy.has(flag)) p.add(path + "/" + flag, "Automatic use consumption needs a spellcast difficulty.");
        if (legacy.has("damage")) {
            ObjectNode rule = weaponDamage(legacy.get("damage"), path + "/damage", p);
            if (rule != null) {
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        }
        if (legacy.has("reloading"))
            for (ObjectNode rule : reloading(legacy.get("reloading"), path + "/reloading", p, effects)) {
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        if (legacy.has("damageTypeChoice")) {
            if (!legacy.path("damageTypeChoice").isBoolean() || !p.sourceId().startsWith("weapons:"))
                p.add(path + "/damageTypeChoice", "Only weapons can offer a damage type choice.");
            else if (legacy.path("damageTypeChoice").asBoolean()) {
                ObjectNode rule = damageTypeChoice();
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        }
        if (legacy.has("alternateProfiles")) {
            ArrayNode profiles = profiles(legacy.get("alternateProfiles"), path + "/alternateProfiles", p);
            if (!profiles.isEmpty()) out.set("profiles", profiles);
        }
        if (castProfile != null) {
            ArrayNode profiles = out.withArray("profiles");
            Set<String> ids = new HashSet<>();
            profiles.forEach(profile -> ids.add(profile.path("id").asText()));
            castProfile.put("id", unique(castProfile.path("id").asText(), ids));
            profiles.add(castProfile);
        }
        if (legacy.has("hopeReward")) {
            ObjectNode rule = hopeReward(legacy.get("hopeReward"), path + "/hopeReward", p, pools);
            if (rule != null) {
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        }
        if (legacy.has("conditionalDamageRewards")) {
            JsonNode list = legacy.get("conditionalDamageRewards");
            if (!list.isArray()) p.add(path + "/conditionalDamageRewards", "Expected a list.");
            else for (int i = 0; i < list.size(); i++) {
                ObjectNode rule = damageReward(list.get(i), path + "/conditionalDamageRewards/" + i, p);
                if (rule == null) continue;
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        }
        for (ObjectNode rule : armorStates(legacy, path, p, effects)) {
            rule.put("id", unique(rule.path("id").asText(), ruleIds));
            rules.add(rule);
        }
        if (legacy.has("criticalMelee"))
            for (ObjectNode rule : criticalMelee(legacy.get("criticalMelee"), path + "/criticalMelee", p, effects)) {
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
            }
        if (legacy.has("classResourceOnly")) {
            if (!legacy.path("classResourceOnly").isBoolean()) p.add(path + "/classResourceOnly", "Expected a boolean.");
            else if (legacy.path("classResourceOnly").asBoolean()) {
                if (pools.local().isEmpty()) p.add(path + "/classResourceOnly", "Only declared pools can be shown with class resources.");
                pools.local().forEach(pool -> {
                    if ("feature".equals(pool.path("section").asText())) p.add(path + "/classResourceOnly", "A card-only resource cannot also be a class resource.");
                    ((ObjectNode) pool).put("section", "class");
                });
            }
        }
        if (legacy.has("resources")) {
            JsonNode list = legacy.get("resources");
            for (int i = 0; i < list.size(); i++) {
                JsonNode label = list.get(i).path("whileEffect");
                if (!label.isTextual() || !pools.local().has(list.get(i).path("id").asText())
                        || "source".equals(pools.local().path(list.get(i).path("id").asText()).path("scope").asText())) continue;
                String bound = null;
                for (var names = effects.fields(); names.hasNext(); ) {
                    var entry = names.next();
                    if (label.asText().equals(entry.getValue().path("label").asText())) bound = entry.getKey();
                }
                if (bound == null) p.add(path + "/resources/" + i + "/whileEffect", "No effect declared by this source is named " + label.asText() + ".");
                else ((ObjectNode) pools.local().get(list.get(i).path("id").asText())).put("whileEffect", bound);
            }
        }
        if (legacy.has("manual")) manualNotes(legacy.get("manual"), path + "/manual", p, rules);
        tokenEnds(effects, path, p, pools);
        if (!pools.local().isEmpty()) out.set("pools", pools.local());
        if (!effects.isEmpty()) out.set("effects", effects);
        if (!rules.isEmpty()) out.set("rules", rules);
        else if (limited && (castProfile == null || !castProfile.has("limitRef")))
            p.add(path + "/uses", "A use limit without rules has nothing to limit.");
        return out;
    }

    /**
     * An attachable item becomes an {@code attachment} declaration whose legacy blockers are requirement conditions
     * over {@code gear.*}; its nested automation applies to the host gear, so weapon identity reads
     * {@code source.attachedTo.*}.
     */
    private static ObjectNode attachment(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems,
                                         Map<String, ObjectNode> shared, Problems p) {
        p.onlyFields(legacy, path, Set.of("attach"));
        JsonNode attach = legacy.get("attach");
        String at = path + "/attach";
        p.onlyFields(attach, at, Set.of("to", "requires", "feature", "trait", "automation"));
        String to = attach.path("to").asText();
        ObjectNode out = attach.has("automation")
                ? convertBlock(attach.get("automation"), at + "/automation", sourceId, problems, shared)
                : RuleJson.JSON.createObjectNode();
        retarget(out);
        if (!Set.of("weapon", "armor").contains(to)) { p.add(at + "/to", "Expected weapon or armor."); return out; }
        ObjectNode declaration = RuleJson.JSON.createObjectNode().put("to", to);
        ArrayNode requires = declaration.putArray("requires");
        java.util.function.BiConsumer<JsonNode, String> require = (when, reason) ->
                requires.addObject().<ObjectNode>set("when", when).put("reason", reason);
        if ("weapon".equals(to))
            require.accept(RuleJson.JSON.createObjectNode().set("not", read("gear.generated")), "Generated weapons cannot hold attachments.");
        JsonNode needs = attach.path("requires");
        if (attach.has("requires")) p.onlyFields(needs, at + "/requires", Set.of("noFeature", "range", "damageType", "nameIncludes"));
        if (needs.path("noFeature").asBoolean(false))
            require.accept(RuleJson.JSON.createObjectNode().set("not", read("gear.hasFeature")), "{gear.name} already has a feature.");
        if (needs.has("range")) {
            String range = RANGES.get(needs.path("range").asText().toLowerCase(java.util.Locale.ROOT));
            if (range == null || !"weapon".equals(to)) p.add(at + "/requires/range", "Expected a weapon range.");
            else {
                ObjectNode when = RuleJson.JSON.createObjectNode();
                when.putArray("eq").add(read("gear.range")).add(range);
                require.accept(when, "Requires a " + escape(needs.path("range").asText()) + " weapon.");
            }
        }
        if (needs.has("damageType")) {
            String type = switch (needs.path("damageType").asText()) {
                case "phys", "physical" -> "physical";
                case "mag", "magic" -> "magic";
                default -> null;
            };
            if (type == null || !"weapon".equals(to)) p.add(at + "/requires/damageType", "Expected a weapon damage type.");
            else {
                ObjectNode when = RuleJson.JSON.createObjectNode();
                when.putArray("eq").add(read("gear.damageType")).add(type);
                require.accept(when, "Requires a " + type + " weapon.");
            }
        }
        if (attach.has("trait")) {
            String trait = attach.path("trait").asText();
            if (!TRAITS.contains(trait) || !"weapon".equals(to)) p.add(at + "/trait", "Expected a weapon attack trait.");
            else {
                declaration.put("trait", trait);
                require.accept(RuleJson.JSON.createObjectNode().set("not", read("gear.hasTraitAttachment")),
                        "{gear.name} already has an attack-trait attachment.");
            }
        }
        if (needs.has("nameIncludes")) {
            JsonNode names = needs.get("nameIncludes");
            if (!names.isArray() || names.isEmpty()) p.add(at + "/requires/nameIncludes", "Expected one or more names.");
            else {
                ObjectNode when = RuleJson.JSON.createObjectNode();
                ArrayNode any = when.putArray("or");
                List<String> parts = new ArrayList<>();
                for (JsonNode name : names) {
                    any.addObject().putArray("containsText").add(read("gear.name")).add(name.asText());
                    parts.add(escape(name.asText()));
                }
                require.accept(when, "Requires a " + String.join(" or ", parts) + ".");
            }
        }
        if (requires.isEmpty()) declaration.remove("requires");
        if (attach.has("feature")) {
            JsonNode feature = attach.get("feature");
            p.onlyFields(feature, at + "/feature", Set.of("name", "text"));
            if (feature.path("name").asText("").isBlank() || feature.path("text").asText("").isBlank())
                p.add(at + "/feature", "A granted feature needs a name and text.");
            else declaration.putObject("feature").put("name", feature.path("name").asText()).put("text", feature.path("text").asText());
        }
        ObjectNode result = RuleJson.JSON.createObjectNode();
        result.set("attachment", declaration);
        result.setAll(out);
        return result;
    }

    /** Points weapon identity reads at the gear the source is attached to. */
    private static void retarget(JsonNode node) {
        if (node.isObject()) {
            JsonNode read = node.get("read");
            if (node.size() == 1 && read != null && Set.of("source.id", "source.instanceId").contains(read.asText()))
                ((ObjectNode) node).put("read", read.asText().replace("source.", "source.attachedTo."));
            else node.elements().forEachRemaining(LegacyCatalogConverter::retarget);
        } else if (node.isArray()) node.forEach(LegacyCatalogConverter::retarget);
    }

    /**
     * A reloading check becomes three rules over one declared per-weapon effect: an automatic post-attack die roll that
     * starts the effect on the failing face, an automatic {@code event.block} of attacks with this weapon while the
     * effect is active, and a Stress-paid button that ends it.
     */
    private static List<ObjectNode> reloading(JsonNode legacy, String path, Problems p, ObjectNode effects) {
        if (!legacy.isObject()) { p.add(path, "Expected a reloading object."); return List.of(); }
        p.onlyFields(legacy, path, Set.of("dice", "fail"));
        var dice = DICE.matcher(legacy.path("dice").asText("1d6"));
        if (!dice.matches() || !"1".equals(dice.group(1)) || Integer.parseInt(dice.group(2)) < 2) {
            p.add(path + "/dice", "Expected one die such as 1d6.");
            return List.of();
        }
        int sides = Integer.parseInt(dice.group(2));
        JsonNode failNode = legacy.has("fail") ? legacy.get("fail") : RuleJson.JSON.getNodeFactory().numberNode(1);
        if (!failNode.isInt() || failNode.asInt() < 1 || failNode.asInt() > sides) {
            p.add(path + "/fail", "Expected a face from 1 to " + sides + ".");
            return List.of();
        }
        int fail = failNode.asInt();
        Set<String> used = new HashSet<>();
        effects.fieldNames().forEachRemaining(used::add);
        String effect = unique("unloaded", used);
        effects.putObject(effect).put("label", "{source.name}: Unloaded").put("kind", "debuff")
                .put("text", "You must mark a Stress to reload {source.name} before you can fire it again.");
        java.util.function.Supplier<ArrayNode> attackWithThis = () -> {
            ArrayNode when = RuleJson.JSON.createArrayNode();
            when.addObject().putArray("eq").add(read("event.check.kind")).add("attack");
            when.add(hasFact("event.check.weaponSource"));
            when.addObject().putArray("eq").add(read("event.check.weaponSource")).add(read("source.instanceId"));
            return when;
        };
        ObjectNode unloaded = RuleJson.JSON.createObjectNode();
        unloaded.putArray("gt").add(read("effects." + effect)).add(0);

        ObjectNode block = restRule("reloading-block", "roll.prepare", "use", "roll", "automatic");
        ArrayNode blockWhen = attackWithThis.get();
        blockWhen.add(unloaded.deepCopy());
        applyWhen(block, blockWhen);
        block.putArray("do").addObject().put("op", "event.block").put("reason", "{source.name} is unloaded. Reload it first.");

        ObjectNode check = restRule("reloading-check", "roll.finished", "use", "roll", "automatic");
        ArrayNode checkWhen = attackWithThis.get();
        ObjectNode reroll = RuleJson.JSON.createObjectNode();
        reroll.set("not", hasFact("event.check.reroll"));
        checkWhen.add(reroll);
        applyWhen(check, checkWhen);
        ArrayNode steps = check.putArray("do");
        steps.addObject().put("op", "dice.roll").put("count", 1).put("sides", sides).put("as", "check");
        ObjectNode start = steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        start.putObject("if").putArray("eq").add(read("result.check")).add(fail);
        check.putObject("ui").put("report", "Reloading d" + sides + ": {result.check} (unloads on " + fail + ").");

        ObjectNode reload = RuleJson.JSON.createObjectNode().put("id", "reload");
        reload.putArray("bindings").addObject().put("id", "sheet").put("event", "action.request").put("surface", "sheet").put("mode", "button");
        reload.set("when", unloaded);
        reload.putArray("costs").addObject().put("op", "track.mark").put("track", "actor.stress").put("amount", 1);
        reload.putArray("do").addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        reload.putObject("ui").put("label", "Reload").put("description", "Mark a Stress to reload {source.name}.")
                .put("unavailable", "{source.name} is already loaded.").put("report", "Reloaded {source.name}.");
        return List.of(block, check, reload);
    }
    /**
     * Weapon damage dice handling becomes an automatic edit of the finished damage roll made with this source. The host
     * marks the weapon's base dice with {@code sourceId = source.id} and supplies {@code event.check.weaponSource}.
     */
    private static ObjectNode weaponDamage(JsonNode damage, String path, Problems p) {
        if (!damage.isObject()) { p.add(path, "Expected a damage object."); return null; }
        p.onlyFields(damage, path, Set.of("extraDice", "dropLowest", "explode", "onesAs", "maximumOnes", "onesBonus",
                "proficiencyOnly", "label"));
        for (String field : List.of("extraDice", "dropLowest"))
            if (damage.has(field) && !(damage.path(field).isInt() && damage.path(field).asInt() >= 0 && damage.path(field).asInt() <= 32))
                p.add(path + "/" + field, "Expected a whole number from 0 to 32.");
        for (String field : List.of("onesAs", "onesBonus"))
            if (damage.has(field) && !(damage.path(field).isInt() && damage.path(field).asInt() >= 2 && damage.path(field).asInt() <= 100))
                p.add(path + "/" + field, "Expected a whole number from 2 to 100.");
        String explode = damage.path("explode").asText("");
        if (!explode.isEmpty() && !Set.of("once", "repeat").contains(explode)) p.add(path + "/explode", "Unsupported explode mode: " + explode);
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "weapon-damage");
        rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
        when.addObject().putArray("eq").add(read("event.check.weaponSource")).add(read("source.instanceId"));
        if (damage.path("proficiencyOnly").asBoolean(false))
            when.addObject().putArray("eq").add(read("event.check.fixedDice")).add(false);
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        String label = escape(damage.path("label").asText("Weapon damage"));
        JsonNode[] from = { read("event.roll") };
        int[] slot = { 0 };
        java.util.function.Function<ObjectNode, String> select = where -> {
            String as = "dice" + (++slot[0]);
            ObjectNode step = steps.addObject().put("op", "roll.selectDice");
            step.set("from", from[0].deepCopy());
            step.set("where", where);
            step.put("as", as);
            return as;
        };
        java.util.function.Supplier<ObjectNode> weaponDice = () -> weaponDiceWhere(read("source.id"));
        java.util.function.BiConsumer<ObjectNode, String> edit = (step, as) -> {
            step.set("from", from[0].deepCopy());
            step.put("as", as);
            from[0] = read("result." + as);
        };
        int extra = damage.path("extraDice").asInt(0), drop = damage.path("dropLowest").asInt(0);
        if (extra > 0) {
            String dice = select.apply(weaponDice.get());
            ObjectNode step = steps.addObject().put("op", "roll.addDicePerPool");
            step.set("dice", read("result." + dice));
            step.put("count", extra);
            edit.accept(step, "added");
        }
        if (drop > 0) {
            String dice = select.apply(weaponDice.get());
            ObjectNode step = steps.addObject().put("op", "roll.dropLowestPerPool");
            step.set("dice", read("result." + dice));
            step.put("count", drop);
            edit.accept(step, "dropped");
        }
        if (!explode.isEmpty()) {
            String dice = select.apply(weaponDice.get());
            ObjectNode step = steps.addObject().put("op", "roll.explode");
            step.set("dice", read("result." + dice));
            ObjectNode test = RuleJson.JSON.createObjectNode();
            ArrayNode all = test.putArray("and");
            all.addObject().putArray("eq").add(read("die.face")).add(read("die.sides"));
            if ("once".equals(explode)) all.addObject().putArray("ne").add(read("die.origin")).add("exploded");
            step.set("when", test);
            step.put("maxDice", 32);
            edit.accept(step, "exploded");
        }
        java.util.function.Supplier<ObjectNode> weaponOnes = () -> {
            ObjectNode where = RuleJson.JSON.createObjectNode();
            ArrayNode all = where.putArray("and");
            all.add(weaponDiceWhere(read("source.id")));
            all.addObject().putArray("eq").add(read("die.face")).add(1);
            return where;
        };
        if (damage.path("maximumOnes").asBoolean(false)) {
            String dice = select.apply(weaponOnes.get());
            ObjectNode step = steps.addObject().put("op", "roll.setFaces");
            step.set("dice", read("result." + dice));
            step.set("face", read("die.sides"));
            edit.accept(step, "maximized");
        }
        if (damage.has("onesAs")) {
            String dice = select.apply(weaponOnes.get());
            ObjectNode value = RuleJson.JSON.createObjectNode();
            ObjectNode size = RuleJson.JSON.createObjectNode().set("size", read("result." + dice));
            value.putArray("multiply").add(damage.path("onesAs").asInt() - 1).add(size);
            ObjectNode step = steps.addObject().put("op", "roll.contribute");
            step.set("contribution", contribution("weapon-ones-as", "flat", "damage", label).set("value", value));
            edit.accept(step, "onesAs");
        }
        if (damage.has("onesBonus")) {
            String dice = select.apply(weaponOnes.get());
            ObjectNode step = steps.addObject().put("op", "roll.contribute");
            ObjectNode contribution = contribution("weapon-ones-bonus", "dice", "damage", label).put("sides", damage.path("onesBonus").asInt());
            contribution.set("count", RuleJson.JSON.createObjectNode().set("size", read("result." + dice)));
            step.set("contribution", contribution);
            edit.accept(step, "onesBonus");
        }
        if (slot[0] == 0) { p.add(path, "Weapon damage handling changes nothing."); return null; }
        steps.addObject().put("op", "roll.publish").set("roll", from[0]);
        rule.putObject("ui").put("label", label);
        return rule;
    }

    /** Additional ways to attack with a source; a profile without a dice count uses the actor's proficiency. */
    private static ArrayNode profiles(JsonNode list, String path, Problems p) {
        ArrayNode out = RuleJson.JSON.createArrayNode();
        if (!list.isArray() || list.isEmpty() || list.size() > 8) { p.add(path, "Expected one to eight profiles."); return out; }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode legacy = list.get(i);
            String at = path + "/" + i;
            p.onlyFields(legacy, at, Set.of("label", "trait", "range", "damage", "damageType"));
            String label = legacy.path("label").asText("");
            if (label.isBlank()) { p.add(at + "/label", "Profiles need a label."); continue; }
            ObjectNode profile = RuleJson.JSON.createObjectNode().put("id", unique(slug(label), ids)).put("label", escape(label));
            if (legacy.has("trait")) {
                String trait = legacy.path("trait").asText();
                if (!TRAITS.contains(trait)) p.add(at + "/trait", "Unknown trait: " + trait);
                else profile.put("trait", trait);
            }
            if (legacy.has("range")) {
                String range = RANGES.get(legacy.path("range").asText().toLowerCase(java.util.Locale.ROOT));
                if (range == null) p.add(at + "/range", "Unknown range: " + legacy.path("range").asText());
                else profile.put("range", range);
            }
            var matcher = java.util.regex.Pattern.compile("d(\\d+)\\s*(?:([+-])\\s*(\\d+))?")
                    .matcher(legacy.path("damage").asText("").trim().replaceFirst("^\\d*", ""));
            if (!matcher.matches()) { p.add(at + "/damage", "Unsupported profile damage: " + legacy.path("damage").asText()); continue; }
            ObjectNode damage = profile.putObject("damage").put("sides", Integer.parseInt(matcher.group(1)));
            if (matcher.group(3) != null) damage.put("flat", ("-".equals(matcher.group(2)) ? -1 : 1) * Integer.parseInt(matcher.group(3)));
            if (legacy.has("damageType")) {
                String type = switch (legacy.path("damageType").asText()) {
                    case "phys", "physical" -> "physical";
                    case "mag", "magic" -> "magic";
                    default -> null;
                };
                if (type == null) p.add(at + "/damageType", "Unknown damage type: " + legacy.path("damageType").asText());
                else damage.put("type", type);
            }
            out.add(profile);
        }
        return out;
    }

    /** A rule-specific use budget replaces the shared source budget for that rule only. */
    private static void ruleLimit(JsonNode uses, String path, Problems p, ObjectNode out, ObjectNode rule) {
        p.onlyFields(uses, path, Set.of("max", "per"));
        List<String> resets = RESETS.get(uses.path("per").asText());
        if (!uses.path("max").isInt() || uses.path("max").asInt() < 1) { p.add(path + "/max", "Expected a positive whole number."); return; }
        if (resets == null) { p.add(path + "/per", "Unsupported reset: " + uses.path("per").asText()); return; }
        String id = "rule-" + rule.path("id").asText();
        ObjectNode limits = out.has("limits") ? (ObjectNode) out.get("limits") : out.putObject("limits");
        ObjectNode limit = limits.putObject(id).put("max", uses.path("max").asInt());
        resets.forEach(limit.putArray("resetOn")::add);
        rule.put("limitRef", id);
    }

    /** {@code usesOverrides} keys name the sheet feature ({@code feat:<name>}) whose use limit this feature raises. */
    private static void usesOverrides(JsonNode overrides, String path, Problems p, ObjectNode out) {
        if (!overrides.isObject() || overrides.isEmpty()) { p.add(path, "Expected a map of overridden features."); return; }
        ArrayNode list = RuleJson.JSON.createArrayNode();
        overrides.fields().forEachRemaining(entry -> {
            String at = path + "/" + entry.getKey();
            if (!entry.getKey().startsWith("feat:") || entry.getKey().length() == 5) { p.add(at, "Expected a key of the form feat:<feature name>."); return; }
            if (!entry.getValue().isInt() || entry.getValue().asInt() < 1) { p.add(at, "Expected a positive whole number."); return; }
            list.addObject().put("feature", entry.getKey().substring(5)).put("limit", "uses").put("max", entry.getValue().asInt());
        });
        if (!list.isEmpty()) out.set("limitOverrides", list);
    }

    private static final String COMPANION_SLOTS = "companion-stress-slots";

    /** A companion's unmarked Stress is a projected actor pool; training flags remain separate pools. */
    private static void companion(JsonNode companion, String path, Problems p, ObjectNode pools, List<ObjectNode> lifecycle) {
        if (!companion.isObject()) { p.add(path, "Expected a companion object."); return; }
        p.onlyFields(companion, path, Set.of("evasion", "stress", "maxStress", "damage", "range", "dice", "ranges", "options"));
        JsonNode slots = companion.path("stress"), max = companion.path("maxStress");
        if (!slots.isInt() || slots.asInt() < 1 || !max.isInt() || max.asInt() < slots.asInt()) {
            p.add(path + "/stress", "Expected starting and maximum Stress slots."); return;
        }
        if (pools.has(COMPANION_SLOTS)) { p.add(path, "Only one companion can be declared."); return; }
        ObjectNode stressPool = pools.putObject(COMPANION_SLOTS).put("kind", "number").put("scope", "actor")
                .put("label", "Companion Stress slots left").put("initial", slots.asInt());
        stressPool.putObject("max").putArray("if")
                .addObject().put("exists", "actor.companionStress.capacity");
        ((ArrayNode) stressPool.path("max").path("if")).add(read("actor.companionStress.capacity")).add(max.asInt());
        JsonNode options = companion.path("options");
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.get(i);
            String id = "companion-" + option.path("id").asText();
            if (!ID.matcher(id).matches() || !option.path("max").isInt() || option.path("max").asInt() < 1) {
                p.add(path + "/options/" + i, "Companion training needs an ID and a maximum."); continue;
            }
            if (option.has("stat") || option.has("choose") || pools.has(id)) continue;
            pools.putObject(id).put("kind", "number").put("scope", "actor").put("label", escape(option.path("name").asText(id)))
                    .put("max", option.path("max").asInt());
        }
        ObjectNode rule = restRule("companion-returns", "rest.prepare", "rest", "rest", "automatic");
        ArrayNode returns = rule.putObject("when").putArray("and");
        returns.addObject().putArray("eq").add(read("event.rest.kind")).add("long");
        returns.addObject().put("exists", "actor.companionStress");
        returns.add(read("actor.companionUnavailable"));
        ArrayNode returning = rule.putArray("do");
        returning.addObject().put("op", "flag.set").put("flag", "actor.companionUnavailable").put("value", false);
        returning.addObject().put("op", "pool.gain").put("pool", COMPANION_SLOTS).put("amount", 1);
        ((ObjectNode) rule.get("ui")).put("label", "Companion returns").put("report", "Your companion returns and clears 1 Stress.");
        lifecycle.add(rule);
        ObjectNode recovery = restRule("companion-recovers", "downtime.completed", "downtime", "downtime", "automatic");
        ArrayNode canRecover = recovery.putObject("when").putArray("and");
        canRecover.addObject().put("exists", "actor.companionStress");
        canRecover.addObject().putObject("not").set("read", RuleJson.JSON.getNodeFactory().textNode("actor.companionUnavailable"));
        canRecover.addObject().putArray("gt").add(read("event.move.cleared.stress")).add(0);
        canRecover.addObject().putArray("gt").add(read("actor.companionStress.marked")).add(0);
        ObjectNode clear = recovery.putArray("do").addObject().put("op", "pool.gain").put("pool", COMPANION_SLOTS).put("as", "cleared");
        clear.set("amount", read("event.move.cleared.stress"));
        ((ObjectNode) recovery.get("ui")).put("label", "Companion recovery").put("report", "Your companion clears {result.cleared} Stress.");
        lifecycle.add(recovery);
    }

    /** {@code requiresCompanion} and the intercept options need an available companion; Armored also needs that training. */
    private static void companionGates(JsonNode legacy, String path, Problems p, Pools pools, ArrayNode when) {
        boolean needs = legacy.path("requiresCompanion").asBoolean(false) || legacy.path("companionIntercept").asBoolean(false);
        if (legacy.has("requiresCompanion") && !legacy.path("requiresCompanion").isBoolean()) p.add(path + "/requiresCompanion", "Expected a boolean.");
        if (needs) {
            if (!pools.has(COMPANION_SLOTS)) p.add(path, "A companion rule needs a companion declaration.");
            else {
                when.addObject().put("exists", "actor.companionStress");
                when.addObject().putObject("not").set("read", RuleJson.JSON.getNodeFactory().textNode("actor.companionUnavailable"));
                when.addObject().putArray("gt").add(read("actor.pools." + COMPANION_SLOTS + ".value")).add(0);
            }
        }
        if (legacy.path("companionArmored").asBoolean(false)) {
            if (!pools.has("companion-armored")) p.add(path + "/companionArmored", "Armored needs the companion's Armored training option.");
            else when.addObject().putArray("gt").add(read("actor.pools.companion-armored.value")).add(0);
        }
    }

    private static void resource(JsonNode resource, String path, Problems p, ObjectNode pools, List<ObjectNode> lifecycle) {
        p.onlyFields(resource, path, RESOURCE_FIELDS);
        String id = resource.path("id").asText();
        if (!ID.matcher(id).matches() || pools.has(id)) { p.add(path + "/id", "Invalid or duplicate resource ID: " + id); return; }
        ObjectNode pool = pools.putObject(id).put("kind", "number").put("scope", "actor");
        if (resource.has("label")) pool.put("label", escape(resource.path("label").asText()));
        JsonNode max = resource.path("max");
        JsonNode capacity = !resource.has("max") ? RuleJson.JSON.getNodeFactory().numberNode(1_000_000)
                : max.isInt() && max.asInt() > 0 ? max : max.isTextual() ? computed(max.asText()) : null;
        if (capacity == null) { p.add(path + "/max", "Unsupported pool capacity: " + max); return; }
        pool.set("max", capacity);
        if (resource.has("cardOnly")) {
            if (!resource.path("cardOnly").isBoolean()) p.add(path + "/cardOnly", "Expected a boolean.");
            else if (resource.path("cardOnly").asBoolean()) pool.put("section", "feature");
        }
        if (resource.has("whileEffect") && !resource.path("whileEffect").isTextual()) p.add(path + "/whileEffect", "Expected an effect label.");
        if (resource.has("initial")) {            if (!resource.path("initial").isInt()) p.add(path + "/initial", "Expected a whole initial value.");
            pool.put("initial", resource.path("initial").asInt());
        }
        boolean session = resource.path("session").asBoolean(false);
        if (resource.has("session") && !resource.path("session").isBoolean()) p.add(path + "/session", "Expected a boolean.");
        if (!session && (resource.has("sessionHope") || resource.has("sessionReset"))) {
            p.add(path + "/session", "Session conversions need a session resource.");
            return;
        }
        if (session && (resource.has("resetOn") || resource.has("resetValue") || resource.has("minimumReset"))) { p.add(path + "/resetOn", "A session resource cannot declare another reset."); return; }
        if (resource.path("sessionHope").asBoolean(false)) {
            // Unspent tokens become Hope at the start of a session; the rule spends them, so the pool needs no reset.
            if (resource.has("sessionReset")) { p.add(path + "/sessionReset", "Hope conversion empties the pool."); return; }
            ObjectNode rule = restRule("session-" + id, "session.start", "use", "resource", "automatic");
            ObjectNode value = read("actor.pools." + id + ".value");
            rule.putObject("when").putArray("gt").add(value).add(0);
            ArrayNode steps = rule.putArray("do");
            steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").put("as", "gained").set("amount", value);
            steps.addObject().put("op", "pool.spend").put("pool", id).set("amount", value);
            String label = resource.has("label") ? escape(resource.path("label").asText()) : id;
            ((ObjectNode) rule.get("ui")).put("label", label).put("report", "Gained {result.gained} Hope from unspent " + label + ".");
            lifecycle.add(rule);
            return;
        }
        if (session) {
            pool.putArray("resetOn").add("session.start");
            if (resource.has("sessionReset")) {
                JsonNode reset = resource.path("sessionReset");
                JsonNode value = reset.isInt() && reset.asInt() >= 0 ? reset : reset.isTextual() ? computed(reset.asText()) : null;
                if (value == null) { p.add(path + "/sessionReset", "Unsupported session reset: " + reset); return; }
                pool.set("resetTo", value);
            }
            return;
        }
        if (resource.has("resetOn")) {
            List<String> resets = RESETS.get(resource.path("resetOn").asText());
            if (resets == null) p.add(path + "/resetOn", "Unsupported reset: " + resource.path("resetOn").asText());
            else resets.forEach(pool.putArray("resetOn")::add);
        }
        if (resource.has("resetValue") || resource.has("minimumReset")) {
            if (!resource.has("resetOn")) { p.add(path + "/resetValue", "A reset value needs resetOn."); return; }
            JsonNode value = resource.path("resetValue");
            JsonNode reset = !resource.has("resetValue") ? RuleJson.JSON.getNodeFactory().numberNode(resource.path("initial").asInt(0))
                    : value.isInt() ? value : value.isTextual() ? resetExpression(value.asText()) : null;
            if (reset == null) { p.add(path + "/resetValue", "Unsupported reset value: " + value); return; }
            if (resource.has("minimumReset")) {
                if (!resource.path("minimumReset").isInt()) { p.add(path + "/minimumReset", "Expected a whole minimum."); return; }
                ObjectNode floor = RuleJson.JSON.createObjectNode();
                floor.putArray("max").add(reset).add(resource.path("minimumReset").asInt());
                reset = floor;
            }
            pool.set("resetTo", reset);
        }
    }

    /** {@code @cards_<domain>} counts loadout and vault cards of the domain; other references use {@link #computed}. */
    private static JsonNode resetExpression(String text) {
        var cards = Pattern.compile("@cards_([A-Za-z]+)").matcher(text.trim());
        if (!cards.matches()) return computed(text);
        String domain = cards.group(1).substring(0, 1).toUpperCase(java.util.Locale.ROOT) + cards.group(1).substring(1).toLowerCase(java.util.Locale.ROOT);
        ObjectNode sum = RuleJson.JSON.createObjectNode();
        ArrayNode add = sum.putArray("add");
        for (String collection : List.of("actor.loadout", "actor.vault")) {
            ObjectNode pluck = RuleJson.JSON.createObjectNode();
            pluck.putArray("pluck").add(read(collection)).add("domain");
            add.addObject().putArray("count").add(pluck).add(domain);
        }
        return sum;
    }

    private static void costs(JsonNode cost, String path, Problems p, Pools pools, ObjectNode rule) {
        ArrayNode costs = RuleJson.JSON.createArrayNode();
        if (!cost.isObject()) { p.add(path, "Expected a cost object."); return; }
        cost.fields().forEachRemaining(entry -> {
            String at = path + "/" + entry.getKey();
            if (entry.getValue().isInt() && entry.getValue().asInt() == 0) return;
            JsonNode amount = amount(entry.getValue());
            if (amount == null) { p.add(at, "Expected a positive whole cost or a supported computed value."); return; }
            if ("hope".equals(entry.getKey()) || "gold".equals(entry.getKey()) && !pools.has("gold"))
                costs.addObject().put("op", "resource.spend").put("pool", "actor." + entry.getKey()).set("amount", amount);
            else if ("@item".equals(entry.getKey())) costs.addObject().put("op", "inventory.consume").set("amount", amount);
            else if (TRACKS.containsKey(entry.getKey())) costs.addObject().put("op", "track.mark").put("track", TRACKS.get(entry.getKey())).set("amount", amount);
            else if (pools.has(entry.getKey())) costs.addObject().put("op", "pool.spend").put("pool", entry.getKey()).set("amount", amount);
            else p.add(at, "Custom resource cost needs a declared pool: " + entry.getKey());
        });
        if (!costs.isEmpty()) rule.set("costs", costs);
    }

    /**
     * Chat benefits become one claim rule per benefit plus a {@code claim.offer} step on the offering rule. The offer
     * is scoped to the offering source instance; each recipient may claim once per offer, and exclusive benefits or
     * per-amount offers publish a claim limit.
     */
    private static List<ObjectNode> chatBenefits(JsonNode legacy, String path, Problems p, ObjectNode effects, Pools pools,
                                                 ObjectNode parent, Set<String> ruleIds) {
        for (String flag : List.of("allowSourceOwner", "benefitsPerAmount", "excludeSourceActor"))
            if (legacy.has(flag) && !legacy.path(flag).isBoolean()) p.add(path + "/" + flag, "Expected a boolean.");
        if (!legacy.has("chatBenefits")) return List.of();
        JsonNode list = legacy.get("chatBenefits");
        if (!list.isArray() || list.isEmpty() || list.size() > 16) {
            p.add(path + "/chatBenefits", "Expected 1-16 chat benefits.");
            return List.of();
        }
        boolean party = legacy.path("allowSourceOwner").asBoolean(false);
        boolean excludeSource = legacy.path("excludeSourceActor").asBoolean(false) && !legacy.has("target");
        String group = parent.path("id").asText() + "-claims";
        boolean exclusive = false;
        List<ObjectNode> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode benefit = list.get(i);
            String at = path + "/chatBenefits/" + i;
            if (!benefit.isObject()) { p.add(at, "Expected a benefit object."); continue; }
            p.onlyFields(benefit, at, BENEFIT_FIELDS);
            String id = benefit.path("id").asText();
            if (!ID.matcher(id).matches()) { p.add(at + "/id", "Invalid benefit ID: " + id); continue; }
            String label = benefit.path("label").asText("");
            if (label.isBlank()) p.add(at + "/label", "Benefits need a label.");
            for (String flag : List.of("exclusiveClaim", "sourceOnly", "transferStress"))
                if (benefit.has(flag) && !benefit.path(flag).isBoolean()) p.add(at + "/" + flag, "Expected a boolean.");
            exclusive |= benefit.path("exclusiveClaim").asBoolean(false);
            if (benefit.has("featureAction"))
                p.add(at + "/featureAction", "Opening a granted feature action after a claim is not supported.");
            boolean sourceOnly = benefit.path("sourceOnly").asBoolean(false);
            String recipients = sourceOnly ? "sourceActor" : party ? "playerCharacters" : "otherPlayersCharacters";
            ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", unique(parent.path("id").asText() + "-" + id, ruleIds));
            ObjectNode binding = rule.putArray("bindings").addObject().put("id", "claim").put("event", "claim.accept")
                    .put("surface", "chat").put("mode", "button").put("claimGroup", group)
                    .put("audience", sourceOnly ? "self" : party ? "party" : "others");
            ObjectNode scope = binding.putObject("claimScope").put("recipients", recipients);
            if (excludeSource && !sourceOnly) scope.put("excludeSource", true);
            ArrayNode steps = rule.putArray("do");
            if (benefit.has("cost")) {
                if (!benefit.path("cost").isObject()) p.add(at + "/cost", "Expected a cost object.");
                else benefit.get("cost").fields().forEachRemaining(entry -> {
                    JsonNode amount = amount(entry.getValue());
                    if (amount == null) p.add(at + "/cost/" + entry.getKey(), "Expected a positive whole cost.");
                    else if ("hope".equals(entry.getKey()))
                        steps.addObject().put("op", "resource.spend").put("pool", "recipient.hope").set("amount", amount);
                    else if (TRACKS.containsKey(entry.getKey()))
                        steps.addObject().put("op", "track.mark").put("track", TRACKS.get(entry.getKey()).replaceFirst("^actor\\.", "recipient."))
                                .set("amount", amount);
                    else p.add(at + "/cost/" + entry.getKey(), "Claim costs support Hope, Stress, Hit Points and Armor.");
                });
            }
            if (benefit.path("transferStress").asBoolean(false)) {
                ObjectNode free = RuleJson.JSON.createObjectNode();
                free.putArray("subtract").add(read("actor.stress.capacity")).add(read("actor.stress.marked"));
                ObjectNode max = RuleJson.JSON.createObjectNode();
                max.putArray("min").add(read("recipient.stress.marked")).add(free);
                ObjectNode input = rule.putArray("inputs").addObject().put("id", "amount").put("kind", "integer")
                        .put("label", "Stress to transfer").put("min", 1);
                input.set("max", max);
                steps.addObject().put("op", "track.clear").put("track", "recipient.stress").set("amount", read("input.amount"));
                steps.addObject().put("op", "track.mark").put("track", "actor.stress").set("amount", read("input.amount"));
                steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").set("amount", read("input.amount"));
            }
            JsonNode legacySteps = benefit.path("steps");
            for (int s = 0; s < legacySteps.size(); s++)
                step(legacySteps.get(s), at + "/steps/" + s, p, steps, effects, pools, "recipient");
            if (steps.isEmpty()) p.add(at + "/steps", "Benefits need at least one effect.");
            ObjectNode ui = rule.putObject("ui").put("label", escape(label));
            ObjectNode extra = RuleJson.JSON.createObjectNode();
            report(benefit, at, p, extra);
            String claimed = "{recipient.name} claimed " + escape(label) + ".";
            if (benefit.path("transferStress").asBoolean(false))
                claimed = "{recipient.name} transferred {input.amount} Stress to {actor.name}.";
            ui.put("report", claimed + (extra.has("report") ? " " + extra.path("report").asText() : ""));
            out.add(rule);
        }
        ArrayNode parentSteps = parent.has("do") ? (ArrayNode) parent.get("do") : parent.putArray("do");
        ObjectNode offer = parentSteps.addObject().put("op", "claim.offer").put("group", group)
                .put("label", parent.path("ui").path("label").asText("Benefits"));
        if (exclusive) offer.put("limit", 1);
        else if (legacy.path("benefitsPerAmount").asBoolean(false) && legacy.has("selectAmount")) {
            ObjectNode limit = RuleJson.JSON.createObjectNode();
            limit.putArray("subtract").add(read("input.amount")).add(1);
            offer.set("limit", limit);
        } else if (legacy.path("benefitsPerAmount").asBoolean(false))
            p.add(path + "/benefitsPerAmount", "Per-amount benefits need a selected amount.");
        return out;
    }

    private static void rollCondition(String roll, String path, Problems p, ArrayNode when) {
        if ("action".equals(roll)) when.add(actionRollCondition());
        else if (ROLLS.contains(roll)) when.addObject().putArray("eq").add(read("event.check.kind")).add(roll);
        else if (roll.startsWith("trait:") && TRAITS.contains(roll.substring(6)))
            when.addObject().putArray("eq").add(read("event.check.trait")).add(roll.substring(6));
        else if ("trait".equals(roll)) when.addObject().putArray("ne").add(read("event.check.trait")).addNull();
        else if ("duality".equals(roll)) {
            ArrayNode either = when.addObject().putArray("or");
            either.add(flag("event.check.duality", true));
            either.add(hasFact("event.check.dominant"));
        }
        else if ("companion".equals(roll)) when.addObject().putArray("eq").add(read("event.check.kind")).add("companion");
        else if (roll.matches("death(:(risk|avoid))?")) {
            when.addObject().putArray("eq").add(read("event.check.kind")).add("death");
            if (roll.contains(":")) when.addObject().putArray("eq").add(read("event.check.move")).add(roll.substring(6));
        }
        else if ("spelldamage".equals(roll)) {
            when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
            when.addObject().putArray("eq").add(read("event.check.spell")).add(true);
        } else if (roll.matches("(card|feat|featureAction|option):.+"))
            when.addObject().putArray("eq").add(read("event.check.origin")).add(roll);
        else if (!"any".equals(roll)) p.add(path, "Unsupported roll filter: " + roll);
    }

    private static ObjectNode actionRollCondition() {
        ObjectNode condition = RuleJson.JSON.createObjectNode();
        ArrayNode any = condition.putArray("or");
        for (String kind : List.of("action", "attack", "reaction", "spellcast"))
            any.addObject().putArray("eq").add(read("event.check.kind")).add(kind);
        return condition;
    }

    private static void commonConditions(JsonNode legacy, String path, Problems p, ArrayNode when) {
        if (legacy.has("requiresEffect")) {
            ObjectNode pluck = RuleJson.JSON.createObjectNode();
            pluck.putArray("pluck").add(read("actor.effects")).add("label");
            when.addObject().putArray("includes").add(pluck).add(legacy.path("requiresEffect").asText());
        }
        if (legacy.has("excludesEffect")) {
            ObjectNode pluck = RuleJson.JSON.createObjectNode();
            pluck.putArray("pluck").add(read("actor.effects")).add("label");
            ObjectNode includes = RuleJson.JSON.createObjectNode();
            includes.putArray("includes").add(pluck).add(legacy.path("excludesEffect").asText());
            when.addObject().set("not", includes);
        }
        if (legacy.has("uses") && !"source".equals(legacy.path("uses").asText()) && !legacy.path("uses").isObject())
            p.add(path + "/uses", "Per-rule use limits must reference the source budget.");
        if (legacy.has("weaponRange")) {
            String range = RANGES.get(legacy.path("weaponRange").asText().toLowerCase(java.util.Locale.ROOT));
            if (range == null) p.add(path + "/weaponRange", "Unknown range: " + legacy.path("weaponRange").asText());
            else when.addObject().putArray("eq").add(read("event.check.range")).add(range);
        }
        if (legacy.has("weaponRanges")) {
            // Only weapon attacks at one of the listed ranges; spells cast through a weapon never qualify.
            JsonNode ranges = legacy.get("weaponRanges");
            ObjectNode any = RuleJson.JSON.createObjectNode();
            ArrayNode options = any.putArray("or");
            if (!ranges.isArray() || ranges.isEmpty()) p.add(path + "/weaponRanges", "Expected a list of ranges.");
            else for (JsonNode name : ranges) {
                String range = RANGES.get(name.asText().toLowerCase(java.util.Locale.ROOT));
                if (range == null) p.add(path + "/weaponRanges", "Unknown range: " + name.asText());
                else options.addObject().putArray("eq").add(read("event.check.range")).add(range);
            }
            when.add(hasFact("event.check.weapon"));
            when.addObject().set("not", flag("event.check.spell", true));
            when.add(hasFact("event.check.range"));
            when.add(any);
        }
        if (legacy.has("magicOnly")) {
            if (!legacy.path("magicOnly").isBoolean()) p.add(path + "/magicOnly", "Expected a boolean.");
            else if (legacy.path("magicOnly").asBoolean()) when.addObject().putArray("or")
                    .add(flag("event.check.damageType", "magic")).add(flag("event.check.spell", true));
        }
        if (legacy.has("loadoutDomain")) {
            JsonNode domain = legacy.get("loadoutDomain");
            p.onlyFields(domain, path + "/loadoutDomain", Set.of("domain", "min"));
            if (domain.path("domain").asText("").isBlank() || !domain.path("min").isInt() || domain.path("min").asInt() < 1)
                p.add(path + "/loadoutDomain", "Expected a domain name and a positive minimum.");
            else when.add(loadoutDomain(domain));
        }
        if (legacy.has("requiresZeroHope")) {
            if (!legacy.path("requiresZeroHope").isBoolean()) p.add(path + "/requiresZeroHope", "Expected a boolean.");
            else if (legacy.path("requiresZeroHope").asBoolean()) when.addObject().putArray("eq").add(read("actor.hope.value")).add(0);
        }
        if (legacy.has("minimumHope")) {
            if (!legacy.path("minimumHope").isInt() || legacy.path("minimumHope").asInt() < 1) p.add(path + "/minimumHope", "Expected a positive whole number.");
            else when.addObject().putArray("gte").add(read("actor.hope.value")).add(legacy.path("minimumHope").asInt());
        }
        if (legacy.has("excludeRolls")) {
            for (JsonNode excluded : legacy.get("excludeRolls")) {
                if ("spelldamage".equals(excluded.asText())) {
                    when.addObject().putArray("or").add(RuleJson.JSON.createObjectNode().set("not", flag("event.check.kind", "damage")))
                            .add(RuleJson.JSON.createObjectNode().set("not", flag("event.check.spell", true)));
                    continue;
                }
                if (excluded.asText().matches("(card|feat|featureAction|option):.+")) {
                    when.addObject().putArray("ne").add(read("event.check.origin")).add(excluded.asText());
                    continue;
                }
                if (!ROLLS.contains(excluded.asText())) { p.add(path + "/excludeRolls", "Unsupported roll filter: " + excluded.asText()); continue; }
                if ("action".equals(excluded.asText())) when.addObject().set("not", actionRollCondition());
                else when.addObject().putArray("ne").add(read("event.check.kind")).add(excluded.asText());
            }
        }
    }

    private static void hostOwned(JsonNode legacy, String path, Problems p) {
        if (legacy.has("hostOwned") && !legacy.path("hostOwned").isBoolean())
            p.add(path + "/hostOwned", "Expected a boolean.");
    }

    private static void applyWhen(ObjectNode rule, ArrayNode when) {
        if (when.size() == 1) rule.set("when", when.get(0));
        else if (when.size() > 1) rule.putObject("when").set("and", when);
    }

    /** Optional event facts can be absent in previews or explicitly null in committed events. */
    private static ObjectNode hasFact(String path) {
        ObjectNode expression = RuleJson.JSON.createObjectNode();
        ArrayNode and = expression.putArray("and");
        and.addObject().put("exists", path);
        and.addObject().putArray("ne").add(read(path)).addNull();
        return expression;
    }

    private static ObjectNode read(String path) { return RuleJson.JSON.createObjectNode().put("read", path); }

    /** A damage-roll option on this weapon that marks the roll as dealing magic instead of its listed type. */
    private static ObjectNode damageTypeChoice() {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "magic-damage");
        rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.prepare").put("surface", "roll").put("mode", "option");
        ObjectNode when = rule.putObject("when");
        ArrayNode all = when.putArray("and");
        all.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
        all.addObject().putArray("eq").add(read("event.check.weaponSource")).add(read("source.instanceId"));
        ArrayNode steps = rule.putArray("do");
        ObjectNode annotate = steps.addObject().put("op", "roll.annotate");
        annotate.set("from", read("event.roll"));
        annotate.put("field", "damageType").put("value", "magic").put("as", "magic");
        steps.addObject().put("op", "roll.publish").set("roll", read("result.magic"));
        rule.putObject("ui").put("label", "Deal magic damage").put("description", "This weapon can deal physical or magic damage.");
        return rule;
    }

    /** True when an optional host-provided event value is present and equals {@code value}. */
    private static ObjectNode flag(String path, Object value) {
        ObjectNode both = RuleJson.JSON.createObjectNode();
        ArrayNode and = both.putArray("and");
        and.addObject().put("exists", path);
        ArrayNode eq = and.addObject().putArray("eq").add(read(path));
        if (value instanceof Boolean b) eq.add(b); else eq.add(value.toString());
        return both;
    }

    /** True when the actor has a feature or heritage feature with this sheet name. */
    private static ObjectNode hasFeature(String name) {
        ObjectNode any = RuleJson.JSON.createObjectNode();
        ArrayNode or = any.putArray("or");
        for (String collection : List.of("actor.features", "actor.heritage")) {
            ObjectNode pluck = RuleJson.JSON.createObjectNode();
            pluck.putArray("pluck").add(read(collection)).add("label");
            or.addObject().putArray("includes").add(pluck).add(name);
        }
        return any;
    }

    private static final Pattern SCALED_DICE = Pattern.compile("(@tier|@fear|[1-9][0-9]?)d([1-9][0-9]{0,2})");

    /** {@code NdM} or tier-scaled {@code @tierdM} as count and sides expressions, or {@code null} after reporting a problem. */
    private static JsonNode[] diceTerms(String text, String path, Problems p) {
        var matcher = SCALED_DICE.matcher(text);
        if (!matcher.matches()) { p.add(path, "Unsupported dice: " + text); return null; }
        JsonNode count = "@fear".equals(matcher.group(1)) ? read("event.table.fear") : "@tier".equals(matcher.group(1)) ? read("actor.stats.tier")
                : RuleJson.JSON.getNodeFactory().numberNode(Integer.parseInt(matcher.group(1)));
        return new JsonNode[]{ count, RuleJson.JSON.getNodeFactory().numberNode(Integer.parseInt(matcher.group(2))) };
    }

    private static JsonNode choose(JsonNode condition, JsonNode then, JsonNode otherwise) {
        ObjectNode node = RuleJson.JSON.createObjectNode();
        node.putArray("if").add(condition.deepCopy()).add(then.deepCopy()).add(otherwise.deepCopy());
        return node;
    }

    /**
     * An option's dice as count and sides expressions: the base dice, replaced by the highest reached
     * {@code diceByLevel} threshold, then by the last present {@code diceByFeature} entry, then by matching
     * {@code diceConditions} (whose {@code dieSize} replaces only the sides).
     */
    private static JsonNode[] optionDice(JsonNode option, String path, Problems p) {
        JsonNode[] dice = diceTerms(option.path("dice").asText(""), path + "/dice", p);
        if (dice == null) return null;
        if (option.has("diceByLevel")) {
            java.util.TreeMap<Integer, JsonNode[]> levels = new java.util.TreeMap<>();
            option.get("diceByLevel").fields().forEachRemaining(entry -> {
                if (!entry.getKey().matches("[1-9][0-9]?")) { p.add(path + "/diceByLevel", "Expected level thresholds: " + entry.getKey()); return; }
                JsonNode[] terms = diceTerms(entry.getValue().asText(), path + "/diceByLevel/" + entry.getKey(), p);
                if (terms != null) levels.put(Integer.parseInt(entry.getKey()), terms);
            });
            for (var level : levels.entrySet()) {
                ObjectNode reached = RuleJson.JSON.createObjectNode();
                reached.putArray("gte").add(read("actor.stats.level")).add(level.getKey());
                dice = new JsonNode[]{ choose(reached, level.getValue()[0], dice[0]), choose(reached, level.getValue()[1], dice[1]) };
            }
        }
        if (option.has("diceByFeature")) {
            List<Map.Entry<String, JsonNode>> features = new ArrayList<>();
            option.get("diceByFeature").fields().forEachRemaining(features::add);
            for (var feature : features) {
                JsonNode[] terms = diceTerms(feature.getValue().asText(), path + "/diceByFeature", p);
                if (terms == null) continue;
                ObjectNode has = hasFeature(feature.getKey());
                dice = new JsonNode[]{ choose(has, terms[0], dice[0]), choose(has, terms[1], dice[1]) };
            }
        }
        if (option.has("diceConditions")) {
            JsonNode conditions = option.get("diceConditions");
            if (!conditions.isArray()) { p.add(path + "/diceConditions", "Expected a list."); return dice; }
            for (int i = 0; i < conditions.size(); i++) {
                JsonNode condition = conditions.get(i);
                String at = path + "/diceConditions/" + i;
                p.onlyFields(condition, at, Set.of("feature", "unmarkedHp", "dice", "dieSize"));
                ArrayNode all = RuleJson.JSON.createArrayNode();
                if (condition.has("feature")) all.add(hasFeature(condition.path("feature").asText()));
                JsonNode range = condition.path("unmarkedHp");
                p.onlyFields(range.isMissingNode() ? RuleJson.JSON.createObjectNode() : range, at + "/unmarkedHp", Set.of("min", "max"));
                for (String bound : List.of("min", "max")) {
                    if (!range.has(bound)) continue;
                    ObjectNode unmarked = RuleJson.JSON.createObjectNode();
                    unmarked.putArray("subtract").add(read("actor.hp.capacity")).add(read("actor.hp.marked"));
                    all.addObject().putArray("min".equals(bound) ? "gte" : "lte").add(unmarked).add(range.path(bound).asInt());
                }
                if (all.isEmpty()) { p.add(at, "Dice conditions need a feature or Hit Point condition."); continue; }
                ObjectNode met = RuleJson.JSON.createObjectNode();
                if (all.size() == 1) met = (ObjectNode) all.get(0); else met.set("and", all);
                if (condition.has("dice")) {
                    JsonNode[] terms = diceTerms(condition.path("dice").asText(), at + "/dice", p);
                    if (terms != null) dice = new JsonNode[]{ choose(met, terms[0], dice[0]), choose(met, terms[1], dice[1]) };
                }
                if (condition.has("dieSize")) {
                    if (!(condition.path("dieSize").isInt() && condition.path("dieSize").asInt() >= 2)) p.add(at + "/dieSize", "Expected a die size.");
                    else dice = new JsonNode[]{ dice[0], choose(met, condition.get("dieSize"), dice[1]) };
                }
                if (!condition.has("dice") && !condition.has("dieSize")) p.add(at, "Dice conditions need dice or a die size.");
            }
        }
        return dice;
    }

    /**
     * An alternative Hope reward is a claim on the owner's own successful non-critical duality roll with Hope, in the
     * {@code roll-reward} claim group the ruleset's base reward also uses, so taking it replaces the normal Hope gain.
     */
    private static ObjectNode hopeReward(JsonNode reward, String path, Problems p, Pools pools) {
        p.onlyFields(reward, path, Set.of("roll", "resource", "amount", "favor", "removeFear"));
        List<String> kinds = new ArrayList<>(java.util.stream.Stream.of("resource", "favor", "removeFear").filter(reward::has).toList());
        if (kinds.size() != 1) { p.add(path, "Expected exactly one of resource, favor or removeFear."); return null; }
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "hope-reward");
        ObjectNode binding = rule.putArray("bindings").addObject().put("id", "claim").put("event", "roll.finished")
                .put("surface", "roll").put("mode", "button").put("claimGroup", "roll-reward")
                .put("claimGroupScope", "result").put("audience", "self");
        binding.putObject("claimScope").put("recipients", "sourceActor");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        rollCondition(reward.path("roll").asText("any"), path + "/roll", p, when);
        when.addObject().putArray("ne").add(read("event.check.kind")).add("reaction");
        when.addObject().putArray("eq").add(read("event.check.dominant")).add("hope");
        when.addObject().putArray("eq").add(read("event.check.critical")).add(false);
        ObjectNode failure = RuleJson.JSON.createObjectNode();
        failure.putArray("eq").add(read("event.check.outcome")).add("failure");
        when.addObject().set("not", failure);
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        ObjectNode ui = rule.putObject("ui");
        String kind = kinds.getFirst();
        JsonNode amount = amount("resource".equals(kind) ? reward.path("amount") : reward.path(kind));
        if (amount == null || !amount.isInt()) { p.add(path + "/" + ("resource".equals(kind) ? "amount" : kind), "Expected a positive whole amount."); return null; }
        if ("removeFear".equals(kind)) {
            steps.addObject().put("op", "table.spend").put("resource", "fear").put("label", "Remove GM Fear").put("as", "removed").set("amount", amount);
            ui.put("label", "Remove " + amount.asText() + " GM Fear instead of Hope");
            ui.put("report", "Removed {result.removed.amount} GM Fear instead of gaining Hope.");
            return rule;
        }
        String resource = "favor".equals(kind) ? "favor" : reward.path("resource").asText();
        if (!pools.has(resource)) { p.add(path, "Gaining needs a declared pool: " + resource); return null; }
        steps.addObject().put("op", "pool.gain").put("pool", resource).set("amount", amount);
        String label = pools.local().path(resource).path("label").asText(resource);
        ui.put("label", "Gain " + amount.asText() + " " + label + " instead of Hope");
        ui.put("report", "Gained " + amount.asText() + " " + label + " instead of gaining Hope.");
        return rule;
    }

    /**
     * Armor-slot reactions. Half and held effects are derived effects active while enough Armor Slots are marked; the
     * full effect starts once when the track becomes full; zero-Hope and overflow rules react to {@code track.changed}.
     */
    private static List<ObjectNode> armorStates(JsonNode legacy, String path, Problems p, ObjectNode effects) {
        List<ObjectNode> rules = new ArrayList<>();
        JsonNode marked = read("actor.armorTrack.marked"), capacity = read("actor.armorTrack.capacity");
        ObjectNode hasSlots = RuleJson.JSON.createObjectNode();
        hasSlots.putArray("gt").add(capacity).add(0);
        if (legacy.has("armorHalfEffect")) {
            ObjectNode twice = RuleJson.JSON.createObjectNode();
            twice.putArray("multiply").add(marked).add(2);
            ObjectNode half = RuleJson.JSON.createObjectNode();
            half.putArray("gte").add(twice).add(capacity);
            ObjectNode condition = RuleJson.JSON.createObjectNode();
            condition.putArray("and").add(hasSlots.deepCopy()).add(half);
            derivedArmorEffect(legacy.get("armorHalfEffect"), path + "/armorHalfEffect", p, effects, condition);
        }
        ObjectNode full = RuleJson.JSON.createObjectNode();
        full.putArray("gte").add(marked).add(capacity);
        if (legacy.has("armorFullHeldEffect")) {
            ObjectNode condition = RuleJson.JSON.createObjectNode();
            condition.putArray("and").add(hasSlots.deepCopy()).add(full.deepCopy());
            derivedArmorEffect(legacy.get("armorFullHeldEffect"), path + "/armorFullHeldEffect", p, effects, condition);
        }
        if (legacy.has("armorFullEffect")) {
            JsonNode effect = legacy.get("armorFullEffect");
            String id = effect.isObject() ? declareEffect(effect, path + "/armorFullEffect", p, effects) : null;
            if (!effect.isObject()) p.add(path + "/armorFullEffect", "Expected an effect.");
            if (id != null) {
                ObjectNode rule = trackChanged("armor-full", "armorTrack");
                ArrayNode when = RuleJson.JSON.createArrayNode();
                when.add(rule.at("/bindings/0/when"));
                when.addObject().putArray("gt").add(read("event.after.capacity")).add(0);
                when.addObject().putArray("gte").add(read("event.after.marked")).add(read("event.after.capacity"));
                when.addObject().putArray("lt").add(read("event.before.marked")).add(read("event.before.capacity"));
                when.addObject().putArray("eq").add(read("effects." + id)).add(0);
                ((ObjectNode) rule.at("/bindings/0")).remove("when");
                applyWhen(rule, when);
                rule.putArray("do").addObject().put("op", "effect.start").put("effect", id).put("target", "actor");
                String name = escape(effect.path("name").asText());
                rule.putObject("ui").put("label", name).put("report", name + " started.");
                rules.add(rule);
            }
        }
        if (legacy.has("zeroHopeClearArmor")) {
            JsonNode amount = amount(legacy.get("zeroHopeClearArmor"));
            if (amount == null) p.add(path + "/zeroHopeClearArmor", "Expected a positive whole amount.");
            else {
                ObjectNode rule = trackChanged("zero-hope-clear-armor", "hope");
                ArrayNode when = RuleJson.JSON.createArrayNode();
                when.add(rule.at("/bindings/0/when"));
                when.addObject().putArray("gt").add(read("event.before.value")).add(0);
                when.addObject().putArray("eq").add(read("event.after.value")).add(0);
                when.addObject().putArray("gt").add(marked.deepCopy()).add(0);
                ((ObjectNode) rule.at("/bindings/0")).remove("when");
                applyWhen(rule, when);
                rule.putArray("do").addObject().put("op", "track.clear").put("track", "actor.armorTrack").put("as", "cleared").set("amount", amount);
                rule.putObject("ui").put("label", "Clear Armor at 0 Hope").put("report", "Cleared {result.cleared} Armor Slots at 0 Hope.");
                rules.add(rule);
            }
        }
        if (legacy.has("overflowHope")) {
            JsonNode amount = amount(legacy.get("overflowHope"));
            if (amount == null) p.add(path + "/overflowHope", "Expected a positive whole amount.");
            else {
                ObjectNode rule = trackChanged("overflow-hope", "armorTrack");
                ArrayNode when = RuleJson.JSON.createArrayNode();
                when.add(rule.at("/bindings/0/when"));
                when.addObject().putArray("eq").add(read("event.change")).add("clear");
                ObjectNode cleared = RuleJson.JSON.createObjectNode();
                cleared.putArray("subtract").add(read("event.before.marked")).add(read("event.after.marked"));
                when.addObject().putArray("gt").add(read("event.requested")).add(cleared);
                ((ObjectNode) rule.at("/bindings/0")).remove("when");
                applyWhen(rule, when);
                rule.putArray("do").addObject().put("op", "resource.gain").put("pool", "actor.hope").put("as", "gained").set("amount", amount);
                rule.putObject("ui").put("label", "Overflow Hope").put("report", "Gained {result.gained} Hope from clearing more Armor Slots than were marked.");
                rules.add(rule);
            }
        }
        return rules;
    }

    private static void derivedArmorEffect(JsonNode effect, String path, Problems p, ObjectNode effects, ObjectNode condition) {
        if (!effect.isObject()) { p.add(path, "Expected an effect."); return; }
        ObjectNode copy = ((ObjectNode) effect).deepCopy();
        copy.remove("until");
        // The effect's own condition already requires a full track, so per-modifier armorFull gates are redundant.
        for (JsonNode modifier : copy.path("modifiers")) if (modifier instanceof ObjectNode object) object.remove("armorFull");
        String id = declareEffect(copy, path, p, effects);
        if (id != null) ((ObjectNode) effects.get(id)).set("while", condition);
    }

    /** An automatic rule reacting to a change of the named actor track ({@code event.track}). */
    private static ObjectNode trackChanged(String id, String track) {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        ObjectNode binding = rule.putArray("bindings").addObject().put("id", "changed").put("event", "track.changed")
                .put("surface", "resource").put("mode", "automatic");
        binding.putObject("when").putArray("eq").add(read("event.track")).add(track);
        return rule;
    }

    /**
     * A critical melee attack: an alternative roll reward (claim group {@code roll-reward}) that includes the base critical
     * reward of 1 Hope and 1 cleared Stress plus the extra amounts, and extra proficiency carried to the next damage roll.
     */
    private static List<ObjectNode> criticalMelee(JsonNode legacy, String path, Problems p, ObjectNode effects) {
        List<ObjectNode> rules = new ArrayList<>();
        p.onlyFields(legacy, path, Set.of("hope", "stress", "proficiency"));
        for (String field : List.of("hope", "stress", "proficiency"))
            if (legacy.has(field) && (!legacy.get(field).isInt() || legacy.get(field).asInt() < 0 || legacy.get(field).asInt() > 10))
                { p.add(path + "/" + field, "Expected a whole number from 0 to 10."); return rules; }
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("eq").add(read("event.check.kind")).add("attack");
        when.addObject().putArray("eq").add(read("event.check.critical")).add(true);
        when.addObject().putArray("eq").add(read("event.check.range")).add("melee");
        int hope = 1 + legacy.path("hope").asInt(), stress = 1 + legacy.path("stress").asInt();
        ObjectNode reward = RuleJson.JSON.createObjectNode().put("id", "critical-melee-reward");
        ObjectNode binding = reward.putArray("bindings").addObject().put("id", "claim").put("event", "roll.finished")
                .put("surface", "roll").put("mode", "button").put("claimGroup", "roll-reward")
                .put("claimGroupScope", "result").put("audience", "self");
        binding.putObject("claimScope").put("recipients", "sourceActor");
        ArrayNode rewardWhen = when.deepCopy();
        ArrayNode hasBenefit = rewardWhen.addObject().putArray("or");
        hasBenefit.addObject().putArray("lt").add(read("actor.hope.value")).add(read("actor.hope.max"));
        hasBenefit.addObject().putArray("gt").add(read("actor.stress.marked")).add(0);
        applyWhen(reward, rewardWhen);
        ArrayNode steps = reward.putArray("do");
        steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").put("amount", hope).put("as", "hope");
        steps.addObject().put("op", "track.clear").put("track", "actor.stress").put("amount", stress).put("as", "stress");
        reward.putObject("ui").put("label", "Gain " + hope + " Hope and clear " + stress + " Stress")
                .put("report", "Gained {result.hope} Hope and cleared {result.stress} Stress from a critical melee attack.");
        rules.add(reward);
        int proficiency = legacy.path("proficiency").asInt();
        if (proficiency > 0) {
            ObjectNode option = RuleJson.JSON.createObjectNode().put("id", "critical-melee").put("label", "Critical melee");
            ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "critical-melee");
            rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
            applyWhen(rule, when);
            List<ObjectNode> extra = new ArrayList<>();
            carriedProficiency(option, "Critical melee: +" + proficiency + " proficiency", proficiency, effects, rule.putArray("do"), extra);
            rule.putObject("ui").put("label", "Critical melee: +" + proficiency + " proficiency on damage");
            rules.add(rule);
            for (ObjectNode added : extra)
                for (JsonNode op : added.path("do"))
                    if ("effect.end".equals(op.path("op").asText()) && op.path("effect").asText().endsWith(" (pending damage)"))
                        effects.fields().forEachRemaining(e -> {
                            if (escape(op.path("effect").asText()).equals(e.getValue().path("label").asText())) ((ObjectNode) op).put("effect", e.getKey());
                        });
            rules.addAll(extra);
        }
        return rules;
    }

    private static ObjectNode ruleShell(JsonNode legacy, String path, Problems p, String event, String surface, String mode) {
        String id = legacy.path("id").asText();
        if (!ID.matcher(id).matches()) { p.add(path + "/id", "Invalid rule ID: " + id); return null; }
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        rule.putArray("bindings").addObject().put("id", "use").put("event", event).put("surface", surface).put("mode", mode);
        rule.putObject("ui");
        return rule;
    }

    private static ObjectNode restReroll(boolean limited) {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "rest-reroll");
        rule.putArray("bindings").addObject().put("id", "use").put("event", "rest.roll")
                .put("surface", "chat").put("mode", "button");
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = rule.putArray("do");
        ObjectNode dice = steps.addObject().put("op", "roll.keptDice").put("role", "downtime").put("as", "dice");
        dice.set("from", read("event.roll"));
        ObjectNode reroll = steps.addObject().put("op", "roll.reroll").put("choose", "higherTotal").put("as", "rerolled");
        reroll.set("from", read("event.roll"));
        reroll.set("dice", read("result.dice"));
        steps.addObject().put("op", "roll.publish").set("roll", read("result.rerolled"));
        rule.putObject("ui").put("label", "Reroll downtime die")
                .put("report", "{actor.name} rerolled the downtime die and kept {result.rerolled.total}.");
        return rule;
    }

    private static ObjectNode labels(JsonNode legacy, String path, Problems p, ObjectNode rule) {
        ObjectNode ui = rule.putObject("ui");
        String label = legacy.path("label").asText("");
        if (label.isBlank()) p.add(path + "/label", "Rules shown to players need a label.");
        ui.put("label", escape(label));
        if (legacy.has("description")) ui.put("description", escape(legacy.path("description").asText()));
        return ui;
    }

    private static ObjectNode rollOption(JsonNode option, String path, Problems p, ObjectNode effects, Pools pools, boolean limited,
                                         List<ObjectNode> extra) {
        p.onlyFields(option, path, OPTION_FIELDS);
        hostOwned(option, path, p);
        ObjectNode rule = ruleShell(option, path, p, "roll.prepare", "roll", "option");
        if (rule == null) return null;
        ObjectNode binding = (ObjectNode) rule.path("bindings").get(0);
        if (option.has("defaultOn")) binding.put("defaultOn", option.path("defaultOn").asBoolean());
        if (option.has("exclusive")) binding.put("exclusiveGroup", slug(option.path("exclusive").asText()));
        ArrayNode when = RuleJson.JSON.createArrayNode();
        if (option.has("required")) {
            // A required option is not a choice: it applies (and is paid) on every matching roll with this source.
            if (!option.path("required").isBoolean()) p.add(path + "/required", "Expected a boolean.");
            else if (option.path("required").asBoolean()) {
                binding.put("mode", "automatic");
                binding.remove("defaultOn");
                binding.remove("exclusiveGroup");
                // Effect-granted bonuses follow the effect holder (possibly an ally), not rolls with this weapon.
                // A slot filter targets whichever weapon is equipped in that slot instead.
                if (p.sourceId().startsWith("weapons:") && !option.has("requiresEffect") && !option.has("weapon"))
                    when.addObject().putArray("eq").add(read("event.check.weaponSource")).add(read("source.instanceId"));
            }
        }
        String roll = option.path("roll").asText("");
        if ("duality".equals(roll)) when.add(flag("event.check.duality", true));
        else if (!roll.isEmpty()) rollCondition(roll, path + "/roll", p, when);
        String weapon = option.path("weapon").asText("");
        if (Set.of("primary", "secondary").contains(weapon)) when.addObject().putArray("eq").add(read("event.check.weapon")).add(weapon);
        else if ("any".equals(weapon)) when.add(hasFact("event.check.weapon"));
        else if (!weapon.isEmpty()) p.add(path + "/weapon", "Unsupported weapon filter: " + weapon);
        commonConditions(option, path, p, when);
        if (option.has("condition")) {
            JsonNode condition = option.get("condition");
            p.onlyFields(condition, path + "/condition", Set.of("unmarkedHp", "markedHp", "stressFull"));
            if (condition.has("markedHp")) {
                if (!condition.path("markedHp").isInt() || condition.path("markedHp").asInt() < 1) p.add(path + "/condition/markedHp", "Expected a positive whole number.");
                else when.addObject().putArray("gte").add(read("actor.hp.marked")).add(condition.path("markedHp").asInt());
            }
            if (condition.has("stressFull")) {
                if (!condition.path("stressFull").asBoolean(false)) p.add(path + "/condition/stressFull", "Expected true.");
                else {
                    when.addObject().putArray("gt").add(read("actor.stress.capacity")).add(0);
                    when.addObject().putArray("gte").add(read("actor.stress.marked")).add(read("actor.stress.capacity"));
                }
            }
            JsonNode range = condition.has("unmarkedHp") ? condition.get("unmarkedHp") : RuleJson.JSON.createObjectNode();
            p.onlyFields(range, path + "/condition/unmarkedHp", Set.of("min", "max"));
            ObjectNode unmarked = RuleJson.JSON.createObjectNode();
            unmarked.putArray("subtract").add(read("actor.hp.capacity")).add(read("actor.hp.marked"));
            for (String bound : List.of("min", "max")) {
                if (!range.has(bound)) continue;
                if (!range.path(bound).isInt() || range.path(bound).asInt() < 0) p.add(path + "/condition/unmarkedHp/" + bound, "Expected a nonnegative whole number.");
                else when.addObject().putArray("min".equals(bound) ? "gte" : "lte").add(unmarked.deepCopy()).add(range.path(bound).asInt());
            }
        }
        JsonNode quantity = null;
        if (option.has("quantity")) {
            JsonNode legacy = option.get("quantity");
            p.onlyFields(legacy, path + "/quantity", Set.of("resource", "label", "max"));
            JsonNode max = available(legacy.path("resource").asText(), pools);
            if (max == null) p.add(path + "/quantity/resource", "Quantities need a known resource: " + legacy.path("resource").asText());
            else if (legacy.has("max") && !(legacy.path("max").isInt() && legacy.path("max").asInt() > 0))
                p.add(path + "/quantity/max", "Expected a positive whole maximum.");
            else {
                if (legacy.has("max")) {
                    ObjectNode bounded = RuleJson.JSON.createObjectNode();
                    bounded.putArray("min").add(max).add(legacy.path("max").asInt());
                    max = bounded;
                }
                ObjectNode input = rule.putArray("inputs").addObject().put("id", "quantity").put("kind", "integer")
                        .put("label", escape(legacy.path("label").asText("Quantity"))).put("min", 1);
                input.set("max", max);
                when.addObject().putArray("gte").add(max.deepCopy()).add(1);
                quantity = read("input.quantity");
            }
        }
        JsonNode typed = null;
        if (option.has("input")) {
            // The player types the bonus; "source" only hinted at a prefilled Fear Die in the legacy UI and has no host fact to read.
            JsonNode spec = option.get("input");
            p.onlyFields(spec, path + "/input", Set.of("label", "default", "min", "max", "source"));
            boolean valid = true;
            for (String bound : List.of("default", "min", "max"))
                if (!(spec.path(bound).isInt() && spec.path(bound).asInt() >= -1000 && spec.path(bound).asInt() <= 1000)) {
                    p.add(path + "/input/" + bound, "Expected a whole number from -1000 to 1000.");
                    valid = false;
                }
            if (spec.has("source") && !"attackFear".equals(spec.path("source").asText())) p.add(path + "/input/source", "Unsupported input source: " + spec.path("source"));
            if (valid && spec.path("min").asInt() > spec.path("max").asInt()) { p.add(path + "/input", "The minimum exceeds the maximum."); valid = false; }
            if (rule.has("inputs")) p.add(path + "/input", "An option cannot take both a quantity and a typed bonus.");
            else if (valid) {
                rule.putArray("inputs").addObject().put("id", "bonus").put("kind", "integer").put("label", escape(spec.path("label").asText("Bonus")))
                        .put("min", spec.path("min").asInt()).put("max", spec.path("max").asInt()).put("default", spec.path("default").asInt());
                typed = read("input.bonus");
            }
        }
        String id = option.path("id").asText(), label = escape(option.path("label").asText(""));
        int proficiency = 0;
        if (option.has("proficiency")) {
            if (!(option.path("proficiency").isInt() && option.path("proficiency").asInt() > 0 && option.path("proficiency").asInt() <= 32))
                p.add(path + "/proficiency", "Expected a whole Proficiency bonus from 1 to 32.");
            else if (!Set.of("damage", "attack").contains(roll)) p.add(path + "/proficiency", "Proficiency bonuses apply to attack or damage rolls.");
            else proficiency = option.path("proficiency").asInt();
        }
        if (proficiency > 0 && "damage".equals(roll)) weaponDiceConditions(when);
        applyWhen(rule, when);
        if (option.has("cost")) costs(option.get("cost"), path + "/cost", p, pools, rule);
        if (quantity != null) for (JsonNode cost : rule.path("costs")) times((ObjectNode) cost, "amount", quantity);
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = RuleJson.JSON.createArrayNode();
        String role = option.has("diceResource") ? option.path("diceResource").asText() : "damage".equals(roll) ? "damage" : "modifier";
        if (option.has("diceResource") && (!ID.matcher(role).matches() || Set.of("hope", "fear").contains(role)))
            p.add(path + "/diceResource", "Expected a resource ID to tag the option's dice with.");
        List<ObjectNode> parts = new ArrayList<>();
        if (option.has("value")) {
            JsonNode value = signed(option.path("value"));
            if (value == null) p.add(path + "/value", "Unsupported bonus value: " + option.path("value"));
            else parts.add(times((ObjectNode) contribution(id + "-flat", "flat", "damage".equals(roll) ? "damage" : "modifier", label)
                    .set("value", value), "value", quantity));
        }
        if (typed != null)
            parts.add((ObjectNode) contribution(id + "-input", "flat", "damage".equals(roll) ? "damage" : "modifier", label).set("value", typed));
        if (option.has("dice")) {
            JsonNode[] dice = optionDice(option, path, p);
            if (dice != null) {
                ObjectNode part = contribution(id + "-dice", "dice", role, label);
                part.set("count", dice[0]);
                part.set("sides", dice[1]);
                parts.add(times(part, "count", quantity));
                if (option.path("dice").asText("").startsWith("@fear")) {
                    // Zero dice are not a roll, so the option is offered only while the GM holds Fear.
                    ArrayNode gate = RuleJson.JSON.createArrayNode();
                    if (rule.has("when")) gate.add(rule.get("when"));
                    gate.add(hasFact("event.table.fear"));
                    gate.addObject().putArray("gte").add(read("event.table.fear")).add(1);
                    rule.remove("when");
                    applyWhen(rule, gate);
                }
            }
        } else for (String scaled : List.of("diceByLevel", "diceByFeature", "diceConditions"))
            if (option.has(scaled)) p.add(path + "/" + scaled, "Scaled dice need base dice.");
        if (option.has("allResourceDice")) {
            // Rolls one die per unit currently in the pool, so the option is offered only while the pool holds any.
            JsonNode all = option.get("allResourceDice");
            p.onlyFields(all, path + "/allResourceDice", Set.of("resource", "sides"));
            String resource = all.path("resource").asText();
            if (!pools.has(resource)) p.add(path + "/allResourceDice/resource", "Unknown resource: " + resource);
            else if (!(all.path("sides").isInt() && all.path("sides").asInt() >= 2 && all.path("sides").asInt() <= 1000))
                p.add(path + "/allResourceDice/sides", "Expected a die size from 2 to 1000.");
            else {
                ObjectNode count = read("actor.pools." + resource + ".value");
                ObjectNode part = contribution(id + "-pool", "dice", role, label).put("sides", all.path("sides").asInt());
                part.set("count", count);
                parts.add(part);
                ObjectNode available = RuleJson.JSON.createObjectNode();
                available.putArray("gte").add(count.deepCopy()).add(1);
                ArrayNode gate = RuleJson.JSON.createArrayNode();
                if (rule.has("when")) gate.add(rule.get("when"));
                gate.add(available);
                rule.remove("when");
                applyWhen(rule, gate);
            }
        }
        if (option.has("resourceMultiplier")) {
            // The bonus is the pool's current value times a factor, so the option is offered only while the pool holds any.
            JsonNode multiplier = option.get("resourceMultiplier");
            p.onlyFields(multiplier, path + "/resourceMultiplier", Set.of("resource", "value"));
            String resource = multiplier.path("resource").asText();
            if (!pools.has(resource)) p.add(path + "/resourceMultiplier/resource", "Unknown resource: " + resource);
            else if (!(multiplier.path("value").isInt() && multiplier.path("value").asInt() >= 1 && multiplier.path("value").asInt() <= 1000))
                p.add(path + "/resourceMultiplier/value", "Expected a whole multiplier from 1 to 1000.");
            else {
                ObjectNode count = read("actor.pools." + resource + ".value");
                ObjectNode product = RuleJson.JSON.createObjectNode();
                product.putArray("multiply").add(count).add(multiplier.path("value").asInt());
                parts.add((ObjectNode) contribution(id + "-pool", "flat", "damage".equals(roll) ? "damage" : "modifier", label).set("value", product));
                ObjectNode available = RuleJson.JSON.createObjectNode();
                available.putArray("gte").add(count.deepCopy()).add(1);
                ArrayNode gate = RuleJson.JSON.createArrayNode();
                if (rule.has("when")) gate.add(rule.get("when"));
                gate.add(available);
                rule.remove("when");
                applyWhen(rule, gate);
            }
        }
        if (option.has("advantage")) {
            String advantage = option.path("advantage").asText();
            if (!Set.of("advantage", "disadvantage").contains(advantage)) p.add(path + "/advantage", "Expected advantage or disadvantage.");
            else parts.add(contribution(id + "-" + advantage, "dice", advantage, label)
                    .put("count", 1).put("sides", 6).put("sign", "advantage".equals(advantage) ? 1 : -1));
        }
        if (option.has("traitValue")) {
            String trait = option.path("traitValue").asText();
            if (!TRAITS.contains(trait)) p.add(path + "/traitValue", "Unknown trait: " + trait);
            else parts.add((ObjectNode) contribution(id + "-trait", "flat", "damage".equals(roll) ? "damage" : "modifier", label)
                    .set("value", read("actor.stats.trait." + trait)));
        }
        if (option.has("multiplier")) {
            JsonNode factor = option.get("multiplier");
            if (!(factor.isInt() && factor.asInt() >= 2 && factor.asInt() <= 10)) p.add(path + "/multiplier", "Expected a whole multiplier from 2 to 10.");
            else {
                // Multipliers scale everything else on the roll, so they apply last and never stack with each other.
                parts.add(contribution(id + "-multiplier", "scale", "multiplier", label).put("of", "all").put("factor", factor.asInt()));
                binding.put("priority", -100);
                if (!option.has("exclusive")) binding.put("exclusiveGroup", "roll-multiplier");
            }
        }
        JsonNode[] from = { read("event.roll") };
        for (int i = 0; i < parts.size(); i++) {
            String slot = "roll" + (i + 1);
            ObjectNode step = steps.addObject().put("op", "roll.contribute");
            step.set("from", from[0]);
            step.set("contribution", parts.get(i));
            step.put("as", slot);
            from[0] = read("result." + slot);
        }
        boolean edited = !parts.isEmpty();
        if (option.has("hopeDie")) {
            JsonNode sides = option.get("hopeDie");
            if (!(sides.isInt() && sides.asInt() >= 2 && sides.asInt() <= 1000)) p.add(path + "/hopeDie", "Expected a die size from 2 to 1000.");
            else {
                ObjectNode where = RuleJson.JSON.createObjectNode();
                where.putArray("eq").add(read("die.role")).add("hope");
                ObjectNode select = steps.addObject().put("op", "roll.selectDice");
                select.set("from", from[0]);
                select.set("where", where);
                select.put("as", "hopeDice");
                ObjectNode resize = steps.addObject().put("op", "roll.resize");
                resize.set("from", from[0]);
                resize.set("dice", read("result.hopeDice"));
                resize.put("sides", sides.asInt()).put("as", "resized");
                from[0] = read("result.resized");
                edited = true;
            }
        }
        if (proficiency > 0 && "damage".equals(roll)) {
            from[0] = addWeaponDice(steps, from[0], proficiency);
            edited = true;
        }
        if (option.has("ignoreDisadvantage")) {
            if (!option.path("ignoreDisadvantage").asBoolean(false)) p.add(path + "/ignoreDisadvantage", "Expected true.");
            else {
                // Neutralises disadvantage only; it runs after other options have contributed their dice.
                ObjectNode discard = steps.addObject().put("op", "roll.discard");
                discard.set("from", from[0]);
                discard.put("role", "disadvantage").put("as", "neutral");
                from[0] = read("result.neutral");
                binding.put("priority", -90);
                edited = true;
            }
        }
        if (option.has("matchingDice")) {
            if (!option.path("matchingDice").asBoolean(false)) p.add(path + "/matchingDice", "Expected true.");
            else if (!"damage".equals(roll)) p.add(path + "/matchingDice", "Matching dice apply to damage rolls.");
            else {
                // Doubles every kept damage die that shares its face with another, after other options add their dice.
                ObjectNode where = RuleJson.JSON.createObjectNode();
                ArrayNode all = where.putArray("and");
                all.addObject().putArray("eq").add(read("die.role")).add("damage");
                all.addObject().putArray("gt").add(read("die.matches")).add(0);
                ObjectNode select = steps.addObject().put("op", "roll.selectDice");
                select.set("from", from[0]);
                select.set("where", where);
                select.put("as", "matching");
                ObjectNode total = steps.addObject().put("op", "roll.diceTotal");
                total.set("from", from[0]);
                total.set("dice", read("result.matching"));
                total.put("as", "matchingTotal");
                ObjectNode step = steps.addObject().put("op", "roll.contribute");
                step.set("from", from[0]);
                step.set("contribution", contribution(id + "-matching", "flat", "damage", label).set("value", read("result.matchingTotal")));
                step.put("as", "matched");
                from[0] = read("result.matched");
                if (!binding.has("priority")) binding.put("priority", -80);
                edited = true;
            }
        }
        if (edited) steps.addObject().put("op", "roll.publish").set("roll", from[0]);
        if (option.has("effect")) {
            String effect = declareEffect(option.get("effect"), path + "/effect", p, effects);
            if (effect != null) steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        }
        if (proficiency > 0 && "attack".equals(roll)) carriedProficiency(option, label, proficiency, effects, steps, extra);
        if (option.has("damageDie")) {
            JsonNode sides = option.get("damageDie");
            if (!(sides.isInt() && sides.asInt() >= 2 && sides.asInt() <= 1000)) p.add(path + "/damageDie", "Expected a die size from 2 to 1000.");
            else if (!"attack".equals(roll)) p.add(path + "/damageDie", "Damage die sizes are chosen on the attack roll.");
            else carriedDamageDie(option, label, sides.asInt(), effects, steps, extra);
        }
        JsonNode legacySteps = option.path("steps");
        for (int s = 0; s < legacySteps.size(); s++) step(legacySteps.get(s), path + "/steps/" + s, p, steps, effects, pools);
        endEffect(option, steps);
        if ("automatic".equals(binding.path("mode").asText()) && rule.has("costs")) {
            // Automatic rules have no choice to pay for, so a required option pays as its first steps.
            ArrayNode paid = (ArrayNode) rule.remove("costs");
            paid.addAll(steps);
            steps = paid;
        }
        if (!steps.isEmpty()) rule.set("do", steps);
        labels(option, path, p, rule);
        return rule;
    }

    /** Multiplies a numeric field by the chosen quantity; without a quantity the node is unchanged. */
    private static ObjectNode times(ObjectNode node, String field, JsonNode quantity) {
        if (quantity == null) return node;
        ObjectNode product = RuleJson.JSON.createObjectNode();
        product.putArray("multiply").add(node.get(field)).add(quantity.deepCopy());
        node.set(field, product);
        return node;
    }

    /** Only damage rolls of a weapon whose dice scale with Proficiency can gain Proficiency dice. */
    private static void weaponDiceConditions(ArrayNode when) {
        when.add(hasFact("event.check.weaponSourceId"));
        when.addObject().putArray("eq").add(read("event.check.fixedDice")).add(false);
    }

    private static ObjectNode weaponDiceWhere(JsonNode source) {
        ObjectNode where = RuleJson.JSON.createObjectNode();
        ArrayNode all = where.putArray("and");
        all.addObject().putArray("eq").add(read("die.sourceId")).add(source);
        all.addObject().putArray("includes").add(read("event.roll.metadata.weaponBaseContributions"))
                .add(read("die.contributionId"));
        return where;
    }

    /** Rolls {@code count} more of the attacking weapon's base damage dice; returns the edited roll reference. */
    private static JsonNode addWeaponDice(ArrayNode steps, JsonNode from, int count) {
        ObjectNode where = weaponDiceWhere(read("event.check.weaponSourceId"));
        ObjectNode select = steps.addObject().put("op", "roll.selectDice");
        select.set("from", from);
        select.set("where", where);
        select.put("as", "weaponDice");
        ObjectNode add = steps.addObject().put("op", "roll.addDicePerPool");
        add.set("from", from);
        add.set("dice", read("result.weaponDice"));
        add.put("count", count).put("as", "proficiency");
        return read("result.proficiency");
    }

    /**
     * An attack-roll Proficiency bonus applies to that attack's damage: the option starts a pending effect, the next
     * weapon damage roll automatically gains the dice and ends it, and a failed attack ends it unused.
     */
    private static void carriedProficiency(JsonNode option, String label, int proficiency, ObjectNode effects, ArrayNode steps,
                                           List<ObjectNode> extra) {
        carried(option, "proficiency", label, effects, steps, extra, true, (damageSteps, from) -> addWeaponDice(damageSteps, from, proficiency));
    }

    /** Resizes the attacking weapon's base damage dice on the next damage roll. */
    private static void carriedDamageDie(JsonNode option, String label, int sides, ObjectNode effects, ArrayNode steps,
                                         List<ObjectNode> extra) {
        carried(option, "damage-die", label, effects, steps, extra, false, (damageSteps, from) -> {
            ObjectNode where = weaponDiceWhere(read("event.check.weaponSourceId"));
            ObjectNode select = damageSteps.addObject().put("op", "roll.selectDice");
            select.set("from", from);
            select.set("where", where);
            select.put("as", "weaponDice");
            ObjectNode resize = damageSteps.addObject().put("op", "roll.resize");
            resize.set("from", from.deepCopy());
            resize.set("dice", read("result.weaponDice"));
            resize.put("sides", sides).put("as", "resized");
            return read("result.resized");
        });
    }

    /**
     * An attack-roll option whose benefit lands on the following damage roll: choosing it starts a pending effect that
     * the next weapon damage roll consumes, and a failed attack discards.
     */
    private static void carried(JsonNode option, String suffix, String label, ObjectNode effects, ArrayNode steps,
                                List<ObjectNode> extra, boolean proficiencyDice,
                                java.util.function.BiFunction<ArrayNode, JsonNode, JsonNode> edit) {
        Set<String> used = new HashSet<>();
        effects.fieldNames().forEachRemaining(used::add);
        String effect = unique(slug(option.path("id").asText() + "-" + suffix), used);
        String pending = option.path("label").asText() + " (pending damage)";
        ObjectNode declared = effects.putObject(effect).put("label", escape(pending)).put("kind", "buff");
        declared.putArray("until").add("scene.end");
        steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        ObjectNode active = RuleJson.JSON.createObjectNode();
        active.putArray("gt").add(read("effects." + effect)).add(0);

        ObjectNode damage = RuleJson.JSON.createObjectNode().put("id", option.path("id").asText() + "-damage");
        damage.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
        if (proficiencyDice) weaponDiceConditions(when);
        else when.add(hasFact("event.check.weaponSourceId"));
        when.add(active.deepCopy());
        applyWhen(damage, when);
        ArrayNode damageSteps = damage.putArray("do");
        JsonNode roll = edit.apply(damageSteps, read("event.roll"));
        damageSteps.addObject().put("op", "roll.publish").set("roll", roll);
        damageSteps.addObject().put("op", "effect.end").put("effect", pending).put("target", "actor");
        damage.putObject("ui").put("label", label);
        extra.add(damage);

        ObjectNode miss = RuleJson.JSON.createObjectNode().put("id", option.path("id").asText() + "-miss");
        miss.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
        ArrayNode missWhen = RuleJson.JSON.createArrayNode();
        missWhen.addObject().putArray("eq").add(read("event.check.kind")).add("attack");
        missWhen.addObject().putArray("eq").add(read("event.check.outcome")).add("failure");
        missWhen.add(active);
        applyWhen(miss, missWhen);
        miss.putArray("do").addObject().put("op", "effect.end").put("effect", pending).put("target", "actor");
        miss.putObject("ui").put("label", label);
        extra.add(miss);
    }

    private static void endEffect(JsonNode legacy, ArrayNode steps) {
        endEffect(legacy, steps, "actor");
    }

    private static void endEffect(JsonNode legacy, ArrayNode steps, String target) {
        if (legacy.has("endEffect"))
            steps.addObject().put("op", "effect.end").put("effect", legacy.path("endEffect").asText()).put("target", target);
    }

    private static ObjectNode contribution(String id, String kind, String role, String label) {
        return RuleJson.JSON.createObjectNode().put("id", slug(id)).put("kind", kind).put("role", role).put("label", label);
    }

    private static ObjectNode rollTrigger(JsonNode trigger, String path, Problems p, ObjectNode effects, Pools pools, boolean limited) {
        p.onlyFields(trigger, path, TRIGGER_FIELDS);
        hostOwned(trigger, path, p);
        hostRollAction(trigger, path, p);
        boolean spentTokenDamage = false;
        if (trigger.has("spentTokenDamage")) {
            JsonNode sides = trigger.path("spentTokenDamage").path("sides");
            p.onlyFields(trigger.path("spentTokenDamage"), path + "/spentTokenDamage", Set.of("sides"));
            if (trigger.has("damage") || !sides.isInt() || sides.asInt() < 2 || sides.asInt() > 1000) {
                p.add(path + "/spentTokenDamage", "Expected one die size and no other damage.");
            } else {
                spentTokenDamage = true;
                trigger = trigger.deepCopy();
                ((ObjectNode) trigger).remove("spentTokenDamage");
                ((ObjectNode) trigger).putObject("damage").put("dice", "d" + sides.asInt()).put("fixed", true).put("type", "magic")
                        .put(COUNT_READ, "event.roll.metadata." + SPENT_TOKENS);
            }
        }
        if (trigger.has("successIfKnown") && !trigger.path("successIfKnown").isBoolean())
            p.add(path + "/successIfKnown", "Expected a boolean.");
        if (trigger.has("difficulty") && (!trigger.path("difficulty").isIntegralNumber() || trigger.path("difficulty").asInt() < 0))
            p.add(path + "/difficulty", "Expected a nonnegative whole difficulty.");
        if (trigger.has("exclusiveRollGroup") && (!trigger.path("exclusiveRollGroup").isTextual()
                || trigger.path("exclusiveRollGroup").asText().isBlank()))
            p.add(path + "/exclusiveRollGroup", "Expected a nonempty exclusive group name.");
        ObjectNode rule = ruleShell(trigger, path, p, "roll.finished", "roll", "button");
        if (rule == null) return null;
        if (trigger.has("audience")) {
            String audience = switch (trigger.path("audience").asText()) {
                case "ally" -> "others";
                case "any" -> "any";
                case "self" -> "self";
                default -> null;
            };
            if (audience == null) p.add(path + "/audience", "Unsupported audience: " + trigger.path("audience").asText());
            else ((ObjectNode) rule.path("bindings").get(0)).put("audience", audience);
        }
        if (trigger.has("exclusiveRollGroup"))
            ((ObjectNode) rule.path("bindings").get(0)).put("exclusiveGroup", slug(trigger.path("exclusiveRollGroup").asText()));
        if (trigger.has("rollSection")) {
            String section = switch (trigger.path("rollSection").asText()) {
                case "result" -> "result";
                case "feature" -> "sourceFeature";
                case "other" -> "otherFeatures";
                default -> null;
            };
            if (section == null) p.add(path + "/rollSection", "Unsupported roll section: " + trigger.path("rollSection").asText());
            else ((ObjectNode) rule.path("bindings").get(0)).put("group", section);
        }
        String operationTarget = targetSelection(trigger, path, p, (ObjectNode) rule.path("bindings").get(0));
        boolean healingHands = false;
        boolean secondWindTrigger = "secondWind".equals(trigger.path("splendorAction").asText());
        boolean salvationBeam = "salvation".equals(trigger.path("splendorAction").asText());
        boolean invigoration = "invigoration".equals(trigger.path("splendorAction").asText());
        if (invigoration) {
            operationTarget = targetSelection(RuleJson.JSON.createObjectNode().put("target", "anyActor"), path, p,
                    (ObjectNode) rule.path("bindings").get(0));
        }
        if (secondWindTrigger || salvationBeam) {
            operationTarget = targetSelection(RuleJson.JSON.createObjectNode().put("target", "actor").put("excludeSourceActor", true), path, p,
                    (ObjectNode) rule.path("bindings").get(0));
        }
        String splendor = trigger.path("splendorAction").asText("");
        if (trigger.has("splendorAction")) {
            if ("healingHands".equals(splendor) && "recipient".equals(operationTarget)) healingHands = true;
            else if (secondWindTrigger || salvationBeam || invigoration) { }
            else if (!Set.of("sunlight", "resurrection").contains(splendor) || "recipient".equals(operationTarget))
                p.add(path + "/splendorAction", "Unsupported Splendor action: " + splendor);
            else if ("sunlight".equals(splendor) && !trigger.has("selectAmount")) {
                trigger = trigger.deepCopy();
                ((ObjectNode) trigger).putObject("selectAmount").put("resource", "hope").put("label", "Hope to spend");
            }
        }
        if (!trigger.has("target") && trigger.has("excludeSourceActor")) p.add(path + "/excludeSourceActor", "Source exclusion requires a target mode.");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        if (secondWindTrigger) {
            ObjectNode hopeful = when.addObject().putArray("or").addObject();
            hopeful.putArray("eq").add(read("event.check.critical")).add(true);
            ((ArrayNode) when.get(when.size() - 1).get("or")).addObject().putArray("eq").add(read("event.check.dominant")).add("hope");
        }
        if ("spellcast".equals(trigger.path("damage").path("count").asText()))
            when.addObject().put("exists", "actor.spellcastTrait.value");
        if (spentTokenDamage) {
            String spent = "event.roll.metadata." + SPENT_TOKENS;
            when.addObject().put("exists", spent);
            when.addObject().putArray("gte").add(read(spent)).add(1);
        }
        if (trigger.has("on")) rollCondition(trigger.path("on").asText(), path + "/on", p, when);
        if (trigger.has("when")) {
            String condition = trigger.path("when").asText();
            if (Set.of("hope", "fear").contains(condition)) when.addObject().putArray("eq").add(read("event.check.dominant")).add(condition);
            else if ("hopeOrCritical".equals(condition)) {
                ArrayNode any = RuleJson.JSON.createArrayNode();
                any.addObject().putArray("eq").add(read("event.check.dominant")).add("hope");
                any.addObject().putArray("eq").add(read("event.check.critical")).add(true);
                when.addObject().set("or", any);
            } else if ("success".equals(condition)) {
                JsonNode difficulty = trigger.has("difficulty") ? trigger.get("difficulty") : read("event.check.difficulty");
                ArrayNode all = RuleJson.JSON.createArrayNode();
                all.addObject().putArray("ne").add(difficulty.deepCopy()).addNull();
                ArrayNode success = RuleJson.JSON.createArrayNode();
                success.addObject().putArray("eq").add(read("event.check.critical")).add(true);
                success.addObject().putArray("gte").add(read("event.check.total")).add(difficulty.deepCopy());
                all.addObject().set("or", success);
                when.addObject().set("and", all);
            } else if ("failure".equals(condition)) when.addObject().putArray("eq").add(read("event.check.outcome")).add("failure");
            else if ("always".equals(condition)) {
                // No condition is needed for an unconditional trigger.
            } else if ("hopeDie".equals(condition) && trigger.path("die").isInt())
                when.addObject().putArray("eq").add(read("event.check.hopeDie")).add(trigger.path("die").asInt());
            else p.add(path + "/when", "Unsupported trigger condition: " + condition);
        }
        if (trigger.path("successIfKnown").asBoolean(false)) {
            ObjectNode notFailure = RuleJson.JSON.createObjectNode();
            notFailure.putArray("eq").add(read("event.check.outcome")).add("failure");
            when.addObject().set("not", notFailure);
        }
        if (trigger.has("outcome")) when.addObject().putArray("eq").add(read("event.check.outcome")).add(trigger.path("outcome").asText());
        if (trigger.path("failedOnly").asBoolean(false)) when.addObject().putArray("eq").add(read("event.check.outcome")).add("failure");
        if (trigger.path("criticalOnly").asBoolean(false)) when.addObject().putArray("eq").add(read("event.check.critical")).add(true);
        if (trigger.has("swapDuality")) {
            if (!trigger.path("swapDuality").isBoolean()) p.add(path + "/swapDuality", "Expected a boolean.");
            else if (trigger.path("swapDuality").asBoolean()) {
                when.addObject().putArray("ne").add(read("event.check.kind")).add("damage");
                when.addObject().putArray("ne").add(read("event.check.hopeDie")).add(read("event.check.fearDie"));
            }
        }
        if (trigger.has("repeatable") && !trigger.path("repeatable").isBoolean()) p.add(path + "/repeatable", "Expected a boolean.");
        else if (trigger.path("repeatable").asBoolean(false))
            ((ObjectNode) rule.path("bindings").get(0)).put("repeatable", true);
        if (trigger.path("weaponAttack").asBoolean(false)) when.add(hasFact("event.check.weapon"));
        if (trigger.path("primaryWeapon").asBoolean(false)) when.addObject().putArray("eq").add(read("event.check.weapon")).add("primary");
        if (trigger.has("weaponDamage")) {
            // Only weapon damage rolls; spells cast through a weapon do not qualify.
            if (!trigger.path("weaponDamage").asBoolean(false)) p.add(path + "/weaponDamage", "Expected true.");
            else {
                when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
                when.add(hasFact("event.check.weapon"));
                when.addObject().set("not", flag("event.check.spell", true));
            }
        }
        if (trigger.has("containsDie")) {
            if (!trigger.path("containsDie").isInt() || trigger.path("containsDie").asInt() < 1)
                p.add(path + "/containsDie", "Expected a positive die face.");
            else when.addObject().set("includes", RuleJson.JSON.createArrayNode().add(rolledFaces()).add(trigger.path("containsDie").asInt()));
        }
        commonConditions(trigger, path, p, when);
        sentRollConditions(trigger, when);
        applyWhen(rule, when);
        if (trigger.has("cost")) costs(trigger.get("cost"), path + "/cost", p, pools, rule);
        if (trigger.has("spendHopeAmount")) {
            if (!trigger.path("spendHopeAmount").asBoolean(false) || trigger.has("selectAmount")) p.add(path + "/spendHopeAmount", "Expected true and no other chosen amount.");
            else trigger = withHopeAmount(trigger);
        }
        if (trigger.has("selectAmount")) {
            selectAmount(trigger.get("selectAmount"), path + "/selectAmount", p, pools, rule);
            amountCost(trigger, path + "/selectAmount", p, pools, rule);
        }
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = RuleJson.JSON.createArrayNode();
        JsonNode from = read("event.roll");
        boolean edited = false;
        if (trigger.has("rerollDualityDie")) {
            String role = trigger.path("rerollDualityDie").asText();
            if (trigger.has("reroll") || !Set.of("hope", "fear").contains(role)) p.add(path + "/rerollDualityDie", "Expected hope or fear and no other reroll.");
            else {
                trigger = trigger.deepCopy();
                ((ObjectNode) trigger).put("reroll", role);
            }
        }
        if (trigger.has("reroll")) {
            String reroll = trigger.path("reroll").asText();
            ObjectNode where = RuleJson.JSON.createObjectNode();
            if (Set.of("hope", "fear").contains(reroll)) where.putArray("eq").add(read("die.role")).add(reroll);
            else if ("duality".equals(reroll)) {
                ArrayNode any = where.putArray("or");
                any.addObject().putArray("eq").add(read("die.role")).add("hope");
                any.addObject().putArray("eq").add(read("die.role")).add("fear");
            } else p.add(path + "/reroll", "Unsupported reroll: " + reroll);
            ObjectNode select = steps.addObject().put("op", "roll.selectDice");
            select.set("from", from);
            select.set("where", where);
            select.put("as", "dice");
            ObjectNode step = steps.addObject().put("op", "roll.reroll");
            step.set("from", from);
            step.set("dice", read("result.dice"));
            step.put("choose", "new").put("as", "rerolled");
            from = read("result.rerolled");
            edited = true;
        }
        if (trigger.path("swapDuality").asBoolean(false)) {
            ObjectNode step = steps.addObject().put("op", "roll.swapRoles");
            step.set("from", from);
            step.put("first", "hope").put("second", "fear").put("as", "swapped");
            from = read("result.swapped");
            edited = true;
        }
        if (trigger.has("advantage") || trigger.has("disadvantage")) {
            String field = trigger.has("advantage") ? "advantage" : "disadvantage";
            if (trigger.has("advantage") && trigger.has("disadvantage")) p.add(path + "/disadvantage", "A reroll gains either advantage or disadvantage.");
            else if (!trigger.path(field).isBoolean() || !trigger.has("reroll")) p.add(path + "/" + field, "Advantage on a trigger needs a reroll and a boolean.");
            else if (trigger.path(field).asBoolean()) {
                ObjectNode step = steps.addObject().put("op", "roll.contribute");
                step.set("from", from);
                step.set("contribution", contribution(trigger.path("id").asText() + "-" + field, "dice", field,
                        escape(trigger.path("label").asText(""))).put("count", 1).put("sides", 6).put("sign", "advantage".equals(field) ? 1 : -1));
                step.put("as", "advantaged");
                from = read("result.advantaged");
            }
        }
        if (trigger.has("addDice")) {
            String text = trigger.path("addDice").asText("");
            String role = "damage".equals(trigger.path("on").asText()) ? "damage" : "modifier";
            String label = escape(trigger.path("label").asText(""));
            ObjectNode part = null;
            if (text.matches("[1-9][0-9]?")) part = (ObjectNode) contribution(trigger.path("id").asText() + "-flat", "flat", role, label)
                    .set("value", RuleJson.JSON.getNodeFactory().numberNode(Integer.parseInt(text)));
            else {
                JsonNode[] dice = diceTerms(text, path + "/addDice", p);
                if (dice != null) {
                    part = contribution(trigger.path("id").asText() + "-dice", "dice", role, label);
                    part.set("count", dice[0]);
                    part.set("sides", dice[1]);
                }
            }
            if (part != null) {
                ObjectNode step = steps.addObject().put("op", "roll.contribute");
                step.set("from", from);
                step.set("contribution", part);
                step.put("as", "added");
                from = read("result.added");
                edited = true;
            }
        }
        if (trigger.has("damageDice")) {
            String mode = trigger.path("damageDice").asText();
            if (!"damage".equals(trigger.path("on").asText())) p.add(path + "/damageDice", "Damage dice edits need damage rolls.");
            else if (!Set.of("reroll", "double").contains(mode)) p.add(path + "/damageDice", "Unsupported damage dice edit: " + mode);
            else {
                boolean reroll = "reroll".equals(mode);
                ObjectNode where = RuleJson.JSON.createObjectNode();
                ArrayNode notDuality = where.putArray("and");
                for (String role : List.of("hope", "fear", "advantage", "disadvantage"))
                    notDuality.addObject().putArray("ne").add(read("die.role")).add(role);
                if (trigger.has("damageDiceBelow")) {
                    if (!reroll || !trigger.path("damageDiceBelow").isInt() || trigger.path("damageDiceBelow").asInt() < 2)
                        p.add(path + "/damageDiceBelow", "Expected a die face limit of 2 or more on a damage reroll.");
                    else {
                        notDuality.addObject().putArray("gt").add(read("die.face")).add(0);
                        notDuality.addObject().putArray("lt").add(read("die.face")).add(trigger.path("damageDiceBelow").asInt());
                    }
                }
                ObjectNode input = rule.withArray("inputs").addObject().put("id", "dice").put("kind", "die.select")
                        .put("label", reroll ? "Damage dice to reroll" : "Damage die to double").put("choiceLabel", "d{die.sides}: {die.face}");
                input.set("from", read("event.roll"));
                input.set("where", where);
                input.put("count", reroll ? 32 : 1);
                if (reroll) input.put("min", 1);
                if (reroll) {
                    ObjectNode step = steps.addObject().put("op", "roll.reroll");
                    step.set("from", from);
                    step.set("dice", read("input.dice"));
                    step.put("choose", "new").put("as", "rerolledDamage");
                    from = read("result.rerolledDamage");
                } else {
                    ObjectNode total = steps.addObject().put("op", "roll.diceTotal");
                    total.set("from", from);
                    total.set("dice", read("input.dice"));
                    total.put("as", "doubled");
                    ObjectNode step = steps.addObject().put("op", "roll.contribute");
                    step.set("from", from);
                    step.set("contribution", contribution(trigger.path("id").asText() + "-double", "flat", "damage",
                            escape(trigger.path("label").asText(""))).set("value", read("result.doubled")));
                    step.put("as", "doubledDamage");
                    from = read("result.doubledDamage");
                }
                edited = true;
            }
        }
        if (trigger.path("selectAmount").path("addToRoll").asBoolean(false) && rule.has("inputs")) {
            ObjectNode step = steps.addObject().put("op", "roll.contribute");
            step.set("from", from);
            step.set("contribution", contribution(trigger.path("id").asText() + "-amount", "flat",
                    "damage".equals(trigger.path("on").asText()) ? "damage" : "modifier", escape(trigger.path("label").asText("")))
                    .set("value", read("input.amount")));
            step.put("as", "addedAmount");
            from = read("result.addedAmount");
            edited = true;
        }
        if (edited) steps.addObject().put("op", "roll.publish").set("roll", from);
        if (trigger.has("damage"))
            actionDamage(trigger.get("damage"), path + "/damage", trigger, p, steps, read("event.check.critical"), "triggerDamage");
        if (trigger.has("sendRoll")) sentRoll(trigger.get("sendRoll"), path + "/sendRoll", trigger, p, steps);
        if (trigger.has("resourceRoll")) resourceRoll(trigger.get("resourceRoll"), path + "/resourceRoll", trigger, p, pools, rule, steps);
        if (trigger.has("check")) check(trigger, path, p, rule, steps, effects, pools, operationTarget);
        else noChoices(trigger, path, p);
        JsonNode extra = trigger.has("check") ? RuleJson.JSON.missingNode() : trigger.path("steps");
        amountStore(trigger.path("selectAmount"), path + "/selectAmount", p, pools, steps, effects);
        for (int i = 0; i < extra.size(); i++) step(extra.get(i), path + "/steps/" + i, p, steps, effects, pools, operationTarget);
        String healingResource = healingHands ? healingHandsRecovery(trigger, rule, path, p, steps, effects) : null;
        if (secondWindTrigger) secondWind(rule, steps, "recipient");
        if (salvationBeam) salvationBeam(rule, steps);
        if (invigoration) steps.addObject().put("op", "limit.restore").put("to", "recipient").put("label", "Restore one spent limited feature use");
        amountHeal(trigger.path("selectAmount"), path + "/selectAmount", p, steps);
        giveFear(trigger, path, p, steps);
        if (trigger.has("removeFear")) {
            JsonNode amount = amount(trigger.get("removeFear"));
            if (amount == null || !amount.isInt()) p.add(path + "/removeFear", "Expected a positive whole amount.");
            else steps.addObject().put("op", "table.spend").put("resource", "fear").put("label", "Remove GM Fear").put("as", "removedFear").set("amount", amount);
        }
        endEffect(trigger, steps);
        if (!steps.isEmpty()) rule.set("do", steps);
        ObjectNode ui = labels(trigger, path, p, rule);
        if ("sunlight".equals(splendor)) stunningSunlight(ui, steps);
        if ("resurrection".equals(splendor)) resurrection(ui, steps);
        if (!steps.isEmpty()) rule.set("do", steps);
        report(trigger, path, p, ui);
        if (healingResource != null)
            ui.put("report", "{recipient.name}: cleared {result.healingHandsCleared} " + healingResource + "."
                    + (ui.has("report") ? " " + ui.path("report").asText() : ""));
        if (trigger.has("removeFear") && !ui.has("report")) ui.put("report", "Removed {result.removedFear.amount} GM Fear.");
        if (trigger.has("damage"))
            ui.put("report", "Damage total: {result.triggerDamage.total}." + (ui.has("report") ? " " + ui.path("report").asText() : ""));
        resourceRollReport(trigger, ui);
        sentRollReport(trigger, ui);
        checkReport(trigger, ui);
        return rule;
    }

    /** Each use raises the shared source pool by one; using it at capacity empties the pool, ending the shared effect. */
    private static void advanceSourcePool(String pool, int max, ArrayNode steps) {
        JsonNode value = read("sourcePools." + pool + ".value");
        ObjectNode full = RuleJson.JSON.createObjectNode();
        full.putArray("gte").add(value).add(max);
        steps.addObject().put("op", "pool.spend").put("pool", pool).set("amount", value.deepCopy());
        ObjectNode spend = (ObjectNode) steps.get(steps.size() - 1);
        spend.set("if", full);
        steps.addObject().put("op", "pool.gain").put("pool", pool).put("amount", 1).putObject("if").putArray("lt").add(value.deepCopy()).add(max);
    }

    /**
     * Zone of Protection: activating sets the shared Protection pool to 1 and offers allies a claim that starts the zone
     * effect on them. Each use of the zone's mitigation, by anyone, raises the shared value that reduces the next hit.
     */
    private static JsonNode zone(JsonNode trigger, String path, Problems p, ObjectNode effects, Pools pools) {
        ObjectNode copy = ((ObjectNode) trigger).deepCopy();
        copy.remove("splendorAction");
        if (!pools.local().has("protection")) { p.add(path + "/splendorAction", "The zone needs a Protection pool."); return copy; }
        ((ObjectNode) pools.local().get("protection")).put("scope", "source");
        ObjectNode marker = RuleJson.JSON.createObjectNode().put("name", "Zone of Protection source").put("kind", "buff");
        String id = declareEffect(marker, path + "/effect", p, effects);
        if (id != null) {
            ObjectNode declared = (ObjectNode) effects.get(id);
            declared.remove("until");
            ObjectNode active = RuleJson.JSON.createObjectNode();
            active.putArray("gt").add(read("sourcePools.protection.value")).add(0);
            declared.set("while", active);
        }
        copy.putArray("steps").addObject().put("op", "gain").put("resource", "protection").put("amount", 1);
        ObjectNode mitigation = RuleJson.JSON.createObjectNode().put("id", "protection").put("label", "Zone of Protection")
                .put("reduce", "@sourcepool_protection").put("defaultOn", true).put("advancesSourcePool", "protection");
        ObjectNode zoneEffect = RuleJson.JSON.createObjectNode().put("name", "Zone of Protection").put("kind", "buff").put("until", "manual");
        zoneEffect.putArray("mitigation").add(mitigation);
        ObjectNode join = copy.putArray("chatBenefits").addObject().put("id", "join").put("label", "Join Zone of Protection");
        join.putArray("steps").addObject().put("op", "effect").set("effect", zoneEffect);
        return copy;
    }

    private static ObjectNode poolValue(Pools pools, String id) {
        boolean source = "source".equals(pools.local().path(id).path("scope").asText());
        return read((source ? "sourcePools." : "actor.pools.") + id + ".value");
    }

    private static final Set<String> WITCH_GRANTS = Set.of("circle", "moonbeam", "sprites");
    private static final Map<String, String> WITCH_ITEMS = Map.of("talisman", "Enchanted Talisman", "runeWard", "Rune Ward", "lifeblood", "Lifeblood Talisman");

    /**
     * Witch grants: the legacy marker effect only announces the grant. The rewrite turns it into a plain marker effect plus a
     * claim that starts a benefit effect on whoever joins; pools that holders spend become shared source pools. The
     * talisman, Rune Ward and Lifeblood Talisman are real inventory items instead: the witch grants one to their own sheet and
     * it is passed on like any other item, carrying its own mitigation from the items catalog.
     */
    private static JsonNode witch(JsonNode rule, String path, Problems p, Pools pools, List<ObjectNode> grants) {
        String action = rule.path("witchAction").asText("");
        boolean marked = false;
        for (JsonNode step : rule.path("steps")) marked |= step.path("effect").has("witchGrant");
        if (action.isEmpty() && !marked) return rule;
        ObjectNode copy = rule.deepCopy();
        if (!action.startsWith("lunar")) copy.remove("witchAction");
        ArrayNode steps = copy.has("steps") ? (ArrayNode) copy.get("steps") : copy.putArray("steps");
        if ("talisman".equals(action)) {
            copy.putObject("selectAmount").put("resource", "hope").put("label", "Hope to spend");
            grants.add(itemGrant("talisman", read("input.amount")));
        } else if ("lifeblood".equals(action)) grants.add(itemGrant("lifeblood", RuleJson.JSON.getNodeFactory().numberNode(1)));
        else if (!action.isEmpty() && !action.startsWith("lunar")) p.add(path + "/witchAction", "Unsupported witch action: " + action);
        List<ObjectNode> benefits = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (!(steps.get(i).path("effect") instanceof ObjectNode effect) || !effect.has("witchGrant")) continue;
            String kind = effect.path("witchGrant").asText();
            if ("runeWard".equals(kind)) { grants.add(itemGrant(kind, RuleJson.JSON.getNodeFactory().numberNode(1))); steps.remove(i--); continue; }
            effect.remove("witchGrant");
            if (!WITCH_GRANTS.contains(kind)) { p.add(path + "/steps/" + i + "/effect/witchGrant", "Unsupported witch grant: " + kind); continue; }
            ObjectNode benefit = witchBenefit(kind);
            String pool = "sprites".equals(kind) ? "sprites" : "";
            if (!pool.isEmpty()) {
                if (!pools.local().has(pool)) { p.add(path + "/steps/" + i + "/effect", "The grant needs a declared pool: " + pool); continue; }
                ((ObjectNode) pools.local().get(pool)).put("scope", "source");
            }
            benefits.add(benefit);
        }
        if (steps.isEmpty()) copy.remove("steps");
        if (!benefits.isEmpty()) {
            copy.put("allowSourceOwner", true);
            ArrayNode joins = copy.putArray("chatBenefits");
            for (ObjectNode benefit : benefits)
                joins.addObject().put("id", "join-" + slug(benefit.path("name").asText())).put("label", "Join " + benefit.path("name").asText().replace(" benefits", ""))
                        .putArray("steps").addObject().put("op", "effect").set("effect", benefit);
        }
        return copy;
    }

    private static ObjectNode itemGrant(String kind, JsonNode quantity) {
        String name = WITCH_ITEMS.get(kind);
        ObjectNode grant = RuleJson.JSON.createObjectNode().put("op", "inventory.grant").put("catalog", "items").put("entry", slug(name));
        grant.set("quantity", quantity);
        return grant.put("label", name).put("to", "actor");
    }
    private static ObjectNode witchBenefit(String kind) {
        ObjectNode effect = RuleJson.JSON.createObjectNode().put("kind", "buff");
        switch (kind) {
            case "circle" -> {
                effect.put("name", "Circle of Power benefits").put("until", "rest")
                        .put("text", "+2 damage thresholds, +2 Evasion, and +2 attack rolls while within the Circle of Power. Remove these benefits manually when you leave the circle.");
                ArrayNode modifiers = effect.putArray("modifiers");
                modifiers.addObject().put("stat", "thresholds").put("value", 2);
                modifiers.addObject().put("stat", "evasion").put("value", 2);
                effect.putArray("rollOptions").addObject().put("id", "circle").put("label", "Circle of Power").put("roll", "attack").put("value", 2).put("defaultOn", true);
            }
            case "moonbeam" -> {
                effect.put("name", "Moonbeam benefits").put("until", "scene")
                        .put("text", "+1 Spellcast while within the Close-range moonlight, and you can see through illusions. Remove these benefits manually when you leave the moonlight.");
                effect.putArray("rollOptions").addObject().put("id", "moonbeam").put("label", "Moonbeam").put("roll", "spellcast").put("value", 1).put("defaultOn", true);
            }
            case "sprites" -> {
                effect.put("name", "Forest Sprites benefits").put("until", "manual")
                        .put("text", "Use within Melee range of a sprite. Each benefit consumes one sprite shared by the party.");
                ObjectNode attack = effect.putArray("rollOptions").addObject().put("id", "sprite-attack").put("label", "Forest Sprite nearby").put("roll", "attack").put("value", 3);
                attack.putObject("cost").put("sprites", 1);
                ObjectNode armor = effect.putArray("mitigation").addObject().put("id", "sprite-armor").put("label", "Forest Sprite: additional Armor Slot").put("armorSlots", 1);
                armor.putObject("when").put("armor", true);
                armor.putObject("cost").put("sprites", 1);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return effect;
    }

    /**
     * Lunar Phases: the effect counter becomes a shared 0-6 die pool and each phase an effect derived from the faces it covers,
     * so exactly the matching phase is active. The roll and advance actions then only change that pool.
     */
    private static String lunar(JsonNode legacy, String path, Problems p, Pools pools, ObjectNode effects, List<ObjectNode> lifecycle) {
        if (!legacy.has("effectCounters") && !legacy.has("lunarPhases")) return null;
        JsonNode counters = legacy.path("effectCounters"), phases = legacy.path("lunarPhases");
        if (!counters.isArray() || counters.size() != 1 || !phases.isArray() || phases.isEmpty()) {
            p.add(path + "/" + (legacy.has("effectCounters") ? "effectCounters" : "lunarPhases"), "Unsupported legacy field: " + (legacy.has("effectCounters") ? "effectCounters" : "lunarPhases")); return null;
        }
        JsonNode counter = counters.get(0);
        p.onlyFields(counter, path + "/effectCounters/0", Set.of("label", "effect", "cardOnly"));
        String id = slug(counter.path("label").asText(""));
        ObjectNode resource = RuleJson.JSON.createObjectNode().put("id", id).put("label", counter.path("label").asText()).put("initial", 0).put("max", 6);
        if (counter.has("cardOnly")) resource.set("cardOnly", counter.get("cardOnly"));
        resource(resource, path + "/effectCounters/0", p, pools.local(), lifecycle);
        for (int i = 0; i < phases.size(); i++) {
            JsonNode phase = phases.get(i);
            String at = path + "/lunarPhases/" + i;
            p.onlyFields(phase, at, Set.of("faces", "effect"));
            if (!phase.path("faces").isArray() || phase.path("faces").isEmpty() || !phase.path("effect").isObject()) { p.add(at, "Phases need faces and an effect."); continue; }
            ArrayNode faces = RuleJson.JSON.createArrayNode();
            List<String> names = new ArrayList<>();
            boolean valid = true;
            for (JsonNode face : phase.get("faces")) {
                valid &= face.isInt() && face.asInt() >= 1 && face.asInt() <= 6;
                if (valid) {
                    names.add(face.asText());
                    ObjectNode same = RuleJson.JSON.createObjectNode();
                    same.putArray("eq").add(read("actor.pools." + id + ".value")).add(face.asInt());
                    faces.add(same);
                }
            }
            if (!valid) { p.add(at + "/faces", "Expected faces from 1 to 6."); continue; }
            ObjectNode copy = ((ObjectNode) phase.get("effect")).deepCopy();
            copy.put("name", copy.path("name").asText("Lunar Phases") + " (die " + String.join("/", names) + ")");
            String effect = declareEffect(copy, at + "/effect", p, effects);
            if (effect == null) continue;
            ObjectNode active = RuleJson.JSON.createObjectNode();
            if (faces.size() == 1) active = (ObjectNode) faces.get(0);
            else active.set("or", faces);
            ((ObjectNode) effects.get(effect)).set("while", active);
        }
        return id;
    }

    /** The roll replaces the die; advancing wraps a 6 back to 1. Both only touch the converted die pool. */
    private static void lunarSteps(String action, String pool, ObjectNode rule, ArrayNode steps, String path, Problems p) {
        ObjectNode value = read("actor.pools." + pool + ".value");
        if ("lunarRoll".equals(action)) {
            steps.addObject().put("op", "dice.roll").put("count", 1).put("sides", 6).put("as", "die");
            steps.addObject().put("op", "pool.spend").put("pool", pool).set("amount", value);
            steps.addObject().put("op", "pool.gain").put("pool", pool).set("amount", read("result.die"));
            ((ObjectNode) rule.get("ui")).put("report", "{actor.name} rolled {result.die} on the lunar die.");
        } else if ("lunarAdvance".equals(action)) {
            ObjectNode wrapped = RuleJson.JSON.createObjectNode();
            wrapped.putArray("gte").add(value.deepCopy()).add(6);
            steps.addObject().put("op", "pool.spend").put("pool", pool).set("amount", value.deepCopy());
            ((ObjectNode) steps.get(steps.size() - 1)).set("if", wrapped);
            steps.addObject().put("op", "pool.gain").put("pool", pool).put("amount", 1);
            ObjectNode rolled = RuleJson.JSON.createObjectNode();
            rolled.putArray("gt").add(value.deepCopy()).add(0);
            rule.set("when", rule.has("when") ? RuleJson.JSON.createObjectNode().set("and", RuleJson.JSON.createArrayNode().add(rule.get("when")).add(rolled)) : rolled);
            ((ObjectNode) rule.get("ui")).put("report", "{actor.name} advanced the lunar die.");
        } else p.add(path + "/witchAction", "Unsupported witch action: " + action);
    }

    /** Salvation Beam: spend chosen Stress to clear the same amount of Hit Points on one wounded ally per use. */
    private static void salvationBeam(ObjectNode rule, ArrayNode steps) {
        ObjectNode unmarked = RuleJson.JSON.createObjectNode();
        unmarked.putArray("add").add(read("actor.stress.capacity")).addObject().set("negate", read("actor.stress.marked"));
        ObjectNode input = rule.withArray("inputs").addObject().put("id", "amount").put("kind", "integer")
                .put("label", "Stress to spend (HP healed)").put("min", 1);
        input.set("max", unmarked);
        ObjectNode enough = RuleJson.JSON.createObjectNode();
        enough.putArray("gte").add(unmarked.deepCopy()).add(1);
        rule.set("when", rule.has("when") ? RuleJson.JSON.createObjectNode().set("and",
                RuleJson.JSON.createArrayNode().add(rule.get("when")).add(enough)) : enough);
        ObjectNode wounded = RuleJson.JSON.createObjectNode();
        wounded.putArray("gt").add(read("recipient.hp.marked")).add(0);
        ((ObjectNode) rule.path("bindings").get(0).path("targetSelection")).set("where", wounded);
        rule.withArray("costs").addObject().put("op", "track.mark").put("track", "actor.stress").set("amount", read("input.amount"));
        steps.addObject().put("op", "track.clear").put("track", "recipient.hp").set("amount", read("input.amount"));
    }

    /** Second Wind: choose HP (1) or Stress (3) to clear on yourself or, after a hopeful attack, on an ally. */
    private static void secondWind(ObjectNode rule, ArrayNode steps, String who) {
        ArrayNode options = rule.withArray("inputs").addObject().put("id", "recovery").put("kind", "choice")
                .put("label", "Choose your recovery").putArray("options");
        options.addObject().put("id", "hp").put("label", "Clear 1 HP");
        options.addObject().put("id", "stress").put("label", "Clear 3 Stress");
        for (String[] clear : new String[][]{{"hp", "1"}, {"stress", "3"}}) {
            ObjectNode step = steps.addObject().put("op", "track.clear").put("track", who + "." + clear[0]).put("amount", Integer.parseInt(clear[1]));
            step.putObject("if").putArray("eq").add(read("input.recovery")).add(clear[0]);
        }
    }

    /** Stunning Sunlight: the spent Hope sets the target count; damage is rolled once for each reaction outcome. */
    private static void stunningSunlight(ObjectNode ui, ArrayNode steps) {
        for (String[] roll : new String[][]{{"success", "3", "3"}, {"failure", "4", "5"}}) {
            steps.addObject().put("op", "dice.roll").put("count", Integer.parseInt(roll[1])).put("sides", 20).put("as", roll[0] + "Dice");
            ObjectNode sum = steps.addObject().put("op", "value").put("as", roll[0] + "Damage");
            sum.putObject("value").putArray("add").add(read("result." + roll[0] + "Dice")).add(Integer.parseInt(roll[2]));
        }
        ui.put("report", "Spent {input.amount} Hope: that many targets make a Reaction Roll (14). Successful reaction: {result.successDamage} magic damage. "
                + "Failed reaction: {result.failureDamage} magic damage; temporarily Stunned.");
    }

    /** Resurrection: a d6 of 5 or less permanently vaults the card; a 6 keeps it. */
    private static void resurrection(ObjectNode ui, ArrayNode steps) {
        steps.addObject().put("op", "dice.roll").put("count", 1).put("sides", 6).put("as", "die");
        ObjectNode face = steps.addObject().put("op", "value").put("as", "face");
        face.set("value", read("result.die"));
        ObjectNode lost = RuleJson.JSON.createObjectNode(), kept = RuleJson.JSON.createObjectNode();
        lost.putArray("lte").add(read("result.face")).add(5);
        kept.putArray("gt").add(read("result.face")).add(5);
        steps.addObject().put("op", "collection.remove").put("target", "actor").put("collection", "loadout").put("as", "card")
                .set("item", read("source.instanceId"));
        ObjectNode vault = steps.addObject().put("op", "collection.add").put("target", "actor").put("collection", "vault");
        vault.set("item", read("result.card")); vault.set("if", lost);
        ObjectNode back = steps.addObject().put("op", "collection.add").put("target", "actor").put("collection", "loadout");
        back.set("item", read("result.card")); back.set("if", kept);
        ui.put("report", "Rolled {result.face}: on 5 or less {source.name} is permanently vaulted; on 6 it remains available.");
    }
    /** One Healing Hands benefit per ally per long rest: the recovery debuff both gates and follows each heal. */
    private static String healingHandsRecovery(JsonNode trigger, ObjectNode rule, String path, Problems p,
                                               ArrayNode steps, ObjectNode effects) {
        String resource = trigger.path("steps").path(0).path("resource").asText();
        if (!Set.of("hp", "stress").contains(resource)) {
            p.add(path + "/steps/0/resource", "Healing Hands needs an HP or Stress recovery step.");
            return null;
        }
        ObjectNode recoveryStep = null;
        for (JsonNode candidate : steps)
            if ("track.clear".equals(candidate.path("op").asText())
                    && ("recipient." + resource).equals(candidate.path("track").asText())) {
                recoveryStep = (ObjectNode) candidate;
                break;
            }
        if (recoveryStep == null) {
            p.add(path + "/steps/0", "Healing Hands needs a recipient recovery step.");
            return null;
        }
        recoveryStep.put("as", "healingHandsCleared");
        ObjectNode recovery = RuleJson.JSON.createObjectNode().put("name", "Healing Hands recovery").put("kind", "debuff").put("until", "longRest")
                .put("text", "Cannot be healed by Healing Hands again until a long rest.");
        String id = null;
        for (var names = effects.fields(); names.hasNext(); ) {
            var entry = names.next();
            if ("Healing Hands recovery".equals(entry.getValue().path("label").asText())) id = entry.getKey();
        }
        if (id == null) id = declareEffect(recovery, path + "/splendorAction", p, effects);
        if (id == null) return null;
        steps.addObject().put("op", "effect.start").put("effect", id).put("target", "recipient");
        ObjectNode pluck = RuleJson.JSON.createObjectNode();
        pluck.putArray("pluck").add(read("recipient.effects")).add("label");
        ObjectNode includes = RuleJson.JSON.createObjectNode();
        includes.putArray("includes").add(pluck).add("Healing Hands recovery");
        ((ObjectNode) rule.path("bindings").get(0).path("targetSelection")).putObject("where").set("not", includes);
        return "hp".equals(resource) ? "HP" : "Stress";
    }

    /** Daggerheart severity bands from {@code event.thresholds}; massive damage is at least twice the severe threshold. */
    private static ObjectNode severity(String name) {
        ObjectNode amount = read("event.damage.amount"), major = read("event.thresholds.major"), severe = read("event.thresholds.severe");
        ObjectNode massive = RuleJson.JSON.createObjectNode();
        massive.putArray("multiply").add(severe).add(2);
        ObjectNode test = RuleJson.JSON.createObjectNode();
        switch (name) {
            case "minor" -> test.putArray("lt").add(amount).add(major);
            case "major" -> {
                ArrayNode all = test.putArray("and");
                all.addObject().putArray("gte").add(amount).add(major);
                all.addObject().putArray("lt").add(amount.deepCopy()).add(severe);
            }
            case "severe" -> {
                ArrayNode all = test.putArray("and");
                all.addObject().putArray("gte").add(amount).add(severe);
                all.addObject().putArray("lt").add(amount.deepCopy()).add(massive);
            }
            case "massive" -> test.putArray("gte").add(amount).add(massive);
            default -> { return null; }
        }
        return test;
    }

    /**
     * Routes a legacy damage option to one of the three generic damage stages: {@code damage.prepare} changes the raw
     * amount, {@code damage.marks} adjusts the assessed marks plan (severity, armor and Hit Points), and
     * {@code damage.applied} reacts to the committed result.
     */
    private static ObjectNode mitigation(JsonNode mitigation, String path, Problems p, ObjectNode effects, Pools pools, boolean limited) {
        p.onlyFields(mitigation, path, MITIGATION_FIELDS);
        hostOwned(mitigation, path, p);
        boolean armoredIntercept = mitigation.path("companionArmored").asBoolean(false);
        if (mitigation.path("companionIntercept").asBoolean(false) && !armoredIntercept && pools.has(COMPANION_SLOTS)) {
            // The companion marks Stress instead of the owner; Armored pays an owner Armor Slot instead.
            ObjectNode copy = mitigation.deepCopy();
            ObjectNode cost = copy.has("cost") && copy.get("cost").isObject() ? (ObjectNode) copy.get("cost") : copy.putObject("cost");
            cost.put(COMPANION_SLOTS, 1);
            mitigation = copy;
        }
        JsonNode roll = mitigation.path("roll");
        boolean rolled = mitigation.has("roll"), rollHits = rolled && roll.has("success");
        boolean marks = MARKS_FIELDS.stream().anyMatch(mitigation::has);
        boolean preserve = mitigation.has("preserveAfterIncomingDamage");
        boolean prepare = PREPARE_FIELDS.stream().anyMatch(mitigation::has) || (rolled && !rollHits);
        if (marks && prepare) { p.add(path, "A damage option cannot change both the damage amount and the marks plan."); return null; }
        if (preserve && (marks || prepare || !mitigation.path("preserveAfterIncomingDamage").isTextual())) {
            p.add(path + "/preserveAfterIncomingDamage", "Keeping an effect needs its name and cannot change the damage itself."); return null;
        }
        prepare |= preserve;
        if (rollHits && !marks) { p.add(path + "/roll", "Success rolls need a severity, Hit Point or saved Armor change to scale."); return null; }
        String stage = marks ? "marks" : prepare ? "prepare" : "applied";
        // A recovery choice needs the player's pick, so it is offered as a button even when the legacy option was passive.
        boolean recovery = mitigation.path("recoveryChoice").asBoolean(false);
        boolean passive = mitigation.path("passive").asBoolean(false) && !recovery;
        ObjectNode rule = ruleShell(mitigation, path, p, "damage." + stage, "damage", passive ? "automatic" : "button");
        if (rule == null) return null;
        ArrayNode when = RuleJson.JSON.createArrayNode();
        String base = "prepare".equals(stage) ? "event.damage.amount" : "event." + stage + ".incoming";
        when.addObject().putArray("gt").add(read(base)).add(0);
        if (mitigation.has("when")) damageConditions(mitigation.get("when"), stage, path + "/when", p, when);
        if (mitigation.path("insteadOfArmor").asBoolean(false)) {
            if ("prepare".equals(stage)) p.add(path + "/insteadOfArmor", "Replacing Armor needs a marks or applied stage.");
            else when.addObject().putArray("eq").add(read("marks".equals(stage) ? "event.marks.armor.requested" : "event.applied.armorSpent")).add(0);
        }
        if (mitigation.path("markedHpOnly").asBoolean(false) && !mitigation.has("hp"))
            when.addObject().putArray("gt").add(read("marks".equals(stage) ? "event.marks.hitPoints.wouldMark" : "event.applied.hitPoints")).add(0);
        if (mitigation.path("finalSevere").asBoolean(false)) {
            if (!"applied".equals(stage)) p.add(path + "/finalSevere", "Final severity is only known once damage is applied.");
            else when.addObject().putArray("gte").add(read("event.applied.severity")).add(3);
        }
        commonConditions(mitigation, path, p, when);
        companionGates(mitigation, path, p, pools, when);
        boolean quantity = mitigationQuantity(mitigation, path, p, pools, rule, when);
        String shared = mitigation.path("advancesSourcePool").asText("");
        if (!shared.isEmpty()) when.addObject().putArray("gt").add(read("sourcePools." + shared + ".value")).add(0);
        applyWhen(rule, when);
        if (mitigation.has("cost")) {
            if ("marks".equals(stage) && !armoredIntercept && (mitigation.get("cost").has("hp") || mitigation.get("cost").has("armor")))
                p.add(path + "/cost", "Marks-stage options cannot pay with the tracks the damage is about to mark.");
            costs(mitigation.get("cost"), path + "/cost", p, pools, rule);
            if (quantity) for (JsonNode cost : rule.path("costs")) times((ObjectNode) cost, "amount", read("input.quantity"));
        }
        if (limited) rule.put("limitRef", "uses");
        if (mitigation.has("defaultOn")) {
            ObjectNode binding = (ObjectNode) rule.path("bindings").get(0);
            binding.put("mode", "option").put("defaultOn", mitigation.path("defaultOn").asBoolean());
        }
        ArrayNode steps = rule.putArray("do");
        String id = slug(mitigation.path("id").asText()), label = escape(mitigation.path("label").asText(""));
        switch (stage) {
            case "prepare" -> {
                // Chosen while damage is prepared so the automatic ending, which runs before selected options, already sees it.
                if (preserve) steps.addObject().put("op", "state.set").put("slot", keepSlot(mitigation.path("preserveAfterIncomingDamage").asText())).put("value", true);
                else prepareSteps(mitigation, path, p, steps, id, label, quantity);
            }
            case "marks" -> marksSteps(mitigation, path, p, steps, id, label, quantity);
            default -> {}
        }
        if (mitigation.has("destroyResourceOnMax")) destroyItemOnMax(mitigation, path, p, stage, steps);
        appliedSteps(mitigation, path, p, pools, steps, stage);
        String patronReport = null;
        if (mitigation.has("patronDice")) {
            if (!"vengeance".equals(mitigation.path("patronDice").asText()) || !"applied".equals(stage) || !steps.isEmpty())
                p.add(path + "/patronDice", "Only an applied-stage Deadly Vengeance option can roll Patron Dice.");
            else patronReport = patronDice("vengeance", read("event.applied.hitPoints"), path + "/patronDice", p, steps, "{event.applied.hitPoints}");
        }
        if (!shared.isEmpty()) advanceSourcePool(shared, pools.local().path(shared).path("max").asInt(), steps);
        if (recovery) recoveryChoice(path, p, rule, steps, stage);
        if (mitigation.has("effect")) {
            String effect = declareEffect(mitigation.get("effect"), path + "/effect", p, effects);
            if (effect != null) steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        }
        endEffect(mitigation, steps);
        ObjectNode ui = labels(mitigation, path, p, rule);
        report(mitigation, path, p, ui);
        if (mitigation.path("reportRoll").asBoolean(false) && mitigation.has("roll") && !ui.has("report"))
            ui.put("report", (label.isBlank() ? "{source.name}" : label) + ": rolled {result.reduction}.");
        if (patronReport != null && !ui.has("report")) ui.put("report", patronReport);
        if (mitigation.path("reflectHp").asBoolean(false)) {
            if (!"applied".equals(stage)) p.add(path + "/reflectHp", "Reflected Hit Points are only known once damage is applied.");
            else if (!ui.has("report")) ui.put("report", "{source.name}: the attacker marks {event.applied.hitPoints} Hit Points.");
        }
        // A paid option without a mechanical change is a table-resolved reaction; its report tells the table what happened.
        if (steps.isEmpty() && !ui.has("report") && rule.has("costs") && !label.isBlank()) ui.put("report", label + ".");
        if (mitigation.has("followUp")) {
            JsonNode follow = mitigation.get("followUp");
            p.onlyFields(follow, path + "/followUp", Set.of("dice", "text"));
            var dice = DICE.matcher(follow.path("dice").asText());
            if (!dice.matches() || follow.path("text").asText("").isBlank())
                p.add(path + "/followUp", "Follow-up rolls need written dice such as 1d6 and a text.");
            else {
                steps.addObject().put("op", "dice.roll").put("count", Integer.parseInt(dice.group(1)))
                        .put("sides", Integer.parseInt(dice.group(2))).put("as", "followUp");
                String text = escape(follow.path("dice").asText()) + ": {result.followUp}, " + escape(follow.path("text").asText()) + ".";
                ui.put("report", ui.has("report") ? ui.path("report").asText() + " " + text : text);
            }
        }
        if (steps.isEmpty() && !ui.has("report")) p.add(path, "Damage option has no supported mechanical effect.");
        return rule;
    }

    /**
     * A legacy {@code quantity: true} spends several units of the option's single resource cost; the count becomes a bounded
     * integer input that multiplies the cost and can drive the roll count.
     */
    /** A rolled reduction that rolls its maximum face uses up the carried item ({@code @item}) after reducing the damage. */
    private static void destroyItemOnMax(JsonNode mitigation, String path, Problems p, String stage, ArrayNode steps) {
        if (!"@item".equals(mitigation.path("destroyResourceOnMax").asText()) || !"prepare".equals(stage) || !mitigation.path("roll").has("sides")) {
            p.add(path + "/destroyResourceOnMax", "Only a rolled damage reduction can use up its carried item (@item)."); return;
        }
        ObjectNode consume = steps.addObject().put("op", "inventory.consume").put("amount", 1);
        consume.putObject("if").putArray("eq").add(read("result.reduction")).add(mitigation.path("roll").path("sides").asInt());
    }

    private static boolean mitigationQuantity(JsonNode mitigation, String path, Problems p, Pools pools, ObjectNode rule, ArrayNode when) {
        if (!mitigation.has("quantity")) return false;
        JsonNode cost = mitigation.path("cost");
        if (!mitigation.path("quantity").asBoolean(false) || !cost.isObject() || cost.size() != 1) {
            p.add(path + "/quantity", "Quantities need exactly one resource cost to multiply."); return false;
        }
        String resource = cost.fieldNames().next();
        JsonNode max = available(resource, pools);
        if (max == null) { p.add(path + "/quantity", "Quantities need a known resource: " + resource); return false; }
        if (!cost.path(resource).isInt() || cost.path(resource).asInt() < 1) { p.add(path + "/cost", "Expected a positive whole cost per unit."); return false; }
        int per = cost.path(resource).asInt();
        if (per > 1) {
            ObjectNode divided = RuleJson.JSON.createObjectNode();
            divided.putArray("divide").add(max).add(per);
            max = RuleJson.JSON.createObjectNode().set("floor", divided);
        }
        ObjectNode input = rule.withArray("inputs").addObject().put("id", "quantity").put("kind", "integer")
                .put("label", "How many to spend").put("min", 1);
        input.set("max", max);
        when.addObject().putArray("gte").add(max.deepCopy()).add(1);
        return true;
    }

    private static void recoveryChoice(String path, Problems p, ObjectNode rule, ArrayNode steps, String stage) {
        if (!"applied".equals(stage)) { p.add(path + "/recoveryChoice", "Recovery choices react to applied damage."); return; }
        ArrayNode options = rule.withArray("inputs").addObject().put("id", "recovery").put("kind", "choice")
                .put("label", "Choose your recovery").putArray("options");
        options.addObject().put("id", "hope").put("label", "Gain 1 Hope");
        options.addObject().put("id", "stress").put("label", "Clear 1 Stress");
        ObjectNode hope = steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").put("amount", 1);
        hope.putObject("if").putArray("eq").add(read("input.recovery")).add("hope");
        ObjectNode stress = steps.addObject().put("op", "track.clear").put("track", "actor.stress").put("amount", 1);
        stress.putObject("if").putArray("eq").add(read("input.recovery")).add("stress");
    }

    /** Stage-specific conditions; the marks stage reads the assessed plan and the applied stage the committed summary. */
    private static void damageConditions(JsonNode condition, String stage, String path, Problems p, ArrayNode when) {
        p.onlyFields(condition, path, Set.of("type", "severity", "hpUnmarkedMax", "armor", "lastArmor", "lastHp", "markingHp",
                "avoidsAll", "armorFull", "minMarkedHp"));
        boolean marks = "marks".equals(stage), applied = "applied".equals(stage);
        String root = marks ? "event.marks." : "event.applied.";
        String spent = marks ? root + "armor.spent" : root + "armorSpent";
        String wouldMark = marks ? root + "hitPoints.wouldMark" : root + "wouldMark";
        String marked = marks ? root + "hitPoints.marks" : root + "hitPoints";
        if (condition.has("type"))
            when.addObject().putArray("eq").add(read(marks || applied ? root + "type" : "event.damageType")).add(condition.path("type").asText());
        if (condition.has("severity")) {
            ArrayNode any = RuleJson.JSON.createArrayNode();
            for (JsonNode severity : condition.get("severity")) {
                int band = SEVERITIES.indexOf(severity.asText()) + 1;
                ObjectNode test = band == 0 ? null : marks || applied
                        ? (ObjectNode) RuleJson.JSON.createObjectNode().set("eq", RuleJson.JSON.createArrayNode().add(read(root + "incoming")).add(band))
                        : severity(severity.asText());
                if (test == null) p.add(path + "/severity", "Unsupported severity: " + severity.asText());
                else any.add(test);
            }
            if (any.size() == 1) when.add(any.get(0));
            else if (any.size() > 1) when.addObject().set("or", any);
        }
        if (condition.has("hpUnmarkedMax")) {
            ObjectNode unmarked = RuleJson.JSON.createObjectNode();
            unmarked.putArray("subtract").add(read("actor.hp.capacity")).add(read("actor.hp.marked"));
            when.addObject().putArray("lte").add(unmarked).add(condition.path("hpUnmarkedMax").asInt());
        }
        if (condition.path("armorFull").asBoolean(false))
            when.addObject().putArray("gte").add(read("actor.armorTrack.marked")).add(read("actor.armorTrack.capacity"));
        List<String> needsPlan = List.of("armor", "lastArmor", "lastHp", "markingHp", "avoidsAll", "minMarkedHp");
        if (!marks && !applied) {
            for (String key : needsPlan) if (condition.has(key)) p.add(path + "/" + key, "Armor and Hit Point conditions need the marks or applied stage.");
            return;
        }
        if (condition.path("armor").asBoolean(false) || condition.path("lastArmor").asBoolean(false) || condition.path("avoidsAll").asBoolean(false))
            when.addObject().putArray("gt").add(read(spent)).add(0);
        if (condition.path("lastArmor").asBoolean(false)) {
            if (marks) when.addObject().putArray("gte").add(read(spent)).add(read(root + "armor.available"));
            else when.addObject().putArray("eq").add(read(root + "armorRemaining")).add(0);
        }
        if (condition.path("lastHp").asBoolean(false)) {
            when.addObject().putArray("gt").add(read(wouldMark)).add(0);
            if (marks) when.addObject().putArray("gte").add(read(wouldMark)).add(read(root + "hitPoints.remaining"));
            else when.addObject().putArray("eq").add(read(root + "hitPointsRemaining")).add(0);
        }
        if (condition.path("markingHp").asBoolean(false)) when.addObject().putArray("gt").add(read(wouldMark)).add(0);
        if (condition.has("minMarkedHp")) when.addObject().putArray("gte").add(read(wouldMark)).add(condition.path("minMarkedHp").asInt());
        if (condition.path("avoidsAll").asBoolean(false)) {
            when.addObject().putArray("eq").add(read(wouldMark)).add(0);
            when.addObject().putArray("eq").add(read(marked)).add(0);
        }
    }

    private static void prepareSteps(JsonNode mitigation, String path, Problems p, ArrayNode steps, String id, String label, boolean quantity) {
        boolean doubled = mitigation.path("double").asBoolean(false);
        int kinds = (mitigation.path("negate").asBoolean(false) ? 1 : 0) + (mitigation.path("halve").asBoolean(false) ? 1 : 0)
                + (mitigation.has("reduce") ? 1 : 0) + (mitigation.has("roll") ? 1 : 0) + (doubled ? 1 : 0);
        if (kinds != 1) { p.add(path, "Expected exactly one of negate, halve, double, reduce or a rolled reduction."); return; }
        JsonNode amount;
        if (doubled || mitigation.path("negate").asBoolean(false)) amount = read("event.damage.amount");
        else if (mitigation.path("halve").asBoolean(false)) {
            ObjectNode half = RuleJson.JSON.createObjectNode();
            half.putArray("divide").add(read("event.damage.amount")).add(2);
            amount = RuleJson.JSON.createObjectNode().set("floor", half);
        } else if (mitigation.has("roll")) {
            JsonNode count = rollCount(mitigation.get("roll"), path + "/roll", p, false, quantity);
            if (count == null) return;
            steps.addObject().put("op", "dice.roll").put("sides", mitigation.get("roll").path("sides").asInt()).put("as", "reduction").set("count", count);
            amount = read("result.reduction");
        } else {
            amount = amount(mitigation.path("reduce"));
            if (amount == null) { p.add(path + "/reduce", "Expected a positive whole reduction or a supported computed value."); return; }
        }
        ObjectNode reduce = steps.addObject().put("op", doubled ? "damage.increase" : "damage.reduce");
        reduce.set("from", read("event.damage"));
        reduce.set("amount", amount);
        reduce.put("id", id).put("label", label).put("as", "reduced");
        steps.addObject().put("op", "damage.publish").set("damage", read("result.reduced.damage"));
    }

    private static JsonNode rollCount(JsonNode roll, String path, Problems p, boolean marks, boolean quantity) {
        p.onlyFields(roll, path, Set.of("count", "sides", "success", "each"));
        int sides = roll.path("sides").asInt();
        if (!roll.path("sides").isInt() || sides < 2 || sides > 1000) { p.add(path + "/sides", "Expected 2 to 1000 sides."); return null; }
        if (roll.has("success") && !(roll.path("success").isInt() && roll.path("success").asInt() >= 1 && roll.path("success").asInt() <= sides)) {
            p.add(path + "/success", "Expected a face between 1 and the number of sides."); return null;
        }
        JsonNode count = roll.path("count");
        if (!roll.has("count")) return RuleJson.JSON.getNodeFactory().numberNode(1);
        if (quantity && "quantity".equals(count.asText())) return read("input.quantity");
        if (marks && "hp".equals(count.asText())) {
            ObjectNode atLeastOne = RuleJson.JSON.createObjectNode();
            atLeastOne.putArray("max").add(1).add(read("event.marks.hitPoints.wouldMark"));
            return atLeastOne;
        }
        JsonNode value = amount(count);
        if (value == null) p.add(path + "/count", "Unsupported roll count: " + count);
        return value;
    }

    /** Ordered marks-plan changes; every numeric change scales with the success roll when one is written. */
    private static void marksSteps(JsonNode mitigation, String path, Problems p, ArrayNode steps, String id, String label, boolean quantity) {
        JsonNode scale = null;
        if (mitigation.has("roll")) {
            JsonNode roll = mitigation.get("roll");
            JsonNode count = rollCount(roll, path + "/roll", p, true, quantity);
            if (count == null) return;
            steps.addObject().put("op", "dice.hits").put("sides", roll.path("sides").asInt()).put("atLeast", roll.path("success").asInt())
                    .put("as", "hits").set("count", count);
            scale = read("result.hits");
            if (!roll.path("each").asBoolean(false)) {
                ObjectNode any = RuleJson.JSON.createObjectNode();
                any.putArray("min").add(1).add(scale);
                scale = any;
            }
        }
        String[] from = {"event.marks"};
        java.util.function.BiConsumer<String, JsonNode> adjust = (field, to) -> {
            String slot = "m" + (steps.size() + 1);
            ObjectNode step = steps.addObject().put("op", "damage.adjust").put("field", field);
            step.set("from", read(from[0]));
            step.set("to", to);
            step.put("id", id + "-" + field.replace('.', '-')).put("label", label).put("as", slot);
            from[0] = "result." + slot;
        };
        JsonNode finalScale = scale;
        java.util.function.BiFunction<String, JsonNode, JsonNode> plus = (field, by) -> {
            JsonNode scaled = by;
            if (finalScale != null) {
                ObjectNode product = RuleJson.JSON.createObjectNode();
                product.putArray("multiply").add(by).add(finalScale.deepCopy());
                scaled = product;
            }
            ObjectNode sum = RuleJson.JSON.createObjectNode();
            sum.putArray("add").add(read(from[0] + "." + field)).add(scaled);
            return sum;
        };
        if (mitigation.path("armorBlocked").asBoolean(false)) adjust.accept("armor.allowed", RuleJson.JSON.getNodeFactory().numberNode(0));
        JsonNode slots = mitigation.path("armorSlots");
        if ("any".equals(slots.asText())) {
            ObjectNode all = RuleJson.JSON.createObjectNode();
            all.putArray("max").add(1).add(read(from[0] + ".armor.available"));
            adjust.accept("armor.allowed", all);
        } else if (slots.isInt() && slots.asInt() > 0) adjust.accept("armor.allowed", plus.apply("armor.allowed", slots));
        else if (mitigation.has("armorSlots")) p.add(path + "/armorSlots", "Expected a positive whole number or \"any\".");
        for (String[] field : new String[][]{{"armorSeverity", "armor.steps"}, {"saveArmor", "armor.saved"}, {"severity", "severityReduction"},
                {"hp", "hitPoints.bonus"}}) {
            if (!mitigation.has(field[0])) continue;
            JsonNode by = mitigation.get(field[0]);
            boolean signed = "hp".equals(field[0]);
            if (!by.isInt() || (!signed && by.asInt() < 1) || by.asInt() == 0) { p.add(path + "/" + field[0], "Expected a nonzero whole number."); continue; }
            if ("armorSeverity".equals(field[0])) {
                ObjectNode sum = RuleJson.JSON.createObjectNode();
                sum.putArray("add").add(read(from[0] + "." + field[1])).add(by);
                adjust.accept(field[1], sum);
            } else adjust.accept(field[1], plus.apply(field[1], by));
        }
        if (mitigation.has("saveArmor") && !mitigation.has("roll")) p.add(path + "/saveArmor", "Saved Armor Slots need a success roll.");
        if (mitigation.path("replaceHp").asBoolean(false)) adjust.accept("hitPoints.max", RuleJson.JSON.getNodeFactory().numberNode(0));
        if (steps.isEmpty() || !steps.get(steps.size() - 1).path("op").asText().equals("damage.adjust")) return;
        steps.addObject().put("op", "marks.publish").set("marks", read(from[0]));
    }

    /** {@code giveFear} asks the host to add to a table resource; the request reaches the GM through the outbox. */
    private static void giveFear(JsonNode legacy, String path, Problems p, ArrayNode steps) {
        if (!legacy.has("giveFear")) return;
        JsonNode amount = amount(legacy.get("giveFear"));
        if (amount == null) { p.add(path + "/giveFear", "Expected a positive whole number."); return; }
        steps.addObject().put("op", "table.gain").put("resource", "fear").put("label", "Give the GM Fear").set("amount", amount);
    }

    /** An automatic reward once committed damage marked enough Hit Points, optionally only when no Armor Slot was marked. */
    private static ObjectNode damageReward(JsonNode reward, String path, Problems p) {
        p.onlyFields(reward, path, Set.of("minHp", "noArmorMarked", "gainHope", "clearArmor", "loadoutDomain"));
        if (!reward.path("minHp").isInt() || reward.path("minHp").asInt() < 1) { p.add(path + "/minHp", "Expected a positive whole number."); return null; }
        if (reward.has("noArmorMarked") && !reward.path("noArmorMarked").isBoolean()) { p.add(path + "/noArmorMarked", "Expected a boolean."); return null; }
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "damage-reward");
        rule.putArray("bindings").addObject().put("id", "use").put("event", "damage.applied").put("surface", "damage").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("gte").add(read("event.applied.hitPoints")).add(reward.path("minHp").asInt());
        if (reward.path("noArmorMarked").asBoolean(false)) when.addObject().putArray("eq").add(read("event.applied.armorMarked")).add(0);
        commonConditions(reward, path, p, when);
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        for (String field : List.of("gainHope", "clearArmor")) {
            if (!reward.has(field)) continue;
            JsonNode amount = amount(reward.get(field));
            if (amount == null) { p.add(path + "/" + field, "Expected a positive whole number."); return null; }
            if ("gainHope".equals(field)) steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").set("amount", amount);
            else steps.addObject().put("op", "track.clear").put("track", "actor.armorTrack").set("amount", amount);
        }
        if (steps.isEmpty()) { p.add(path, "A damage reward needs a Hope gain or Armor clear."); return null; }
        rule.putObject("ui").put("label", "Damage reward");
        return rule;
    }

    /** Reactions to the committed damage, or immediate side effects of a marks-stage choice. */
    private static void appliedSteps(JsonNode mitigation, String path, Problems p, Pools pools, ArrayNode steps, String stage) {
        boolean applied = "applied".equals(stage);
        for (String field : List.of("clearStress", "gainHope")) {
            if (!mitigation.has(field)) continue;
            JsonNode amount = amount(mitigation.get(field));
            if (amount == null) { p.add(path + "/" + field, "Expected a positive whole number."); continue; }
            if ("gainHope".equals(field)) steps.addObject().put("op", "resource.gain").put("pool", "actor.hope").set("amount", amount);
            else steps.addObject().put("op", "track.clear").put("track", "actor.stress").set("amount", amount);
        }
        giveFear(mitigation, path, p, steps);
        if (mitigation.has("gainTokens")) {
            JsonNode gain = mitigation.get("gainTokens");
            p.onlyFields(gain, path + "/gainTokens", Set.of("resource", "perHp"));
            String pool = gain.path("resource").asText();
            if (!applied) p.add(path + "/gainTokens", "Tokens per Hit Point need the applied stage.");
            else if (!pools.has(pool)) p.add(path + "/gainTokens/resource", "Gaining needs a declared pool: " + pool);
            else if (!gain.path("perHp").isInt() || gain.path("perHp").asInt() < 1) p.add(path + "/gainTokens/perHp", "Expected a positive whole number.");
            else {
                ObjectNode amount = RuleJson.JSON.createObjectNode();
                amount.putArray("multiply").add(read("event.applied.hitPoints")).add(gain.path("perHp").asInt());
                steps.addObject().put("op", "pool.gain").put("pool", pool).set("amount", amount);
            }
        }
        if (mitigation.has("armorCost")) {
            JsonNode cost = mitigation.get("armorCost");
            if (!applied || !cost.isObject()) p.add(path + "/armorCost", "Per-slot costs need the applied stage.");
            else cost.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isInt() || entry.getValue().asInt() < 1) { p.add(path + "/armorCost/" + entry.getKey(), "Expected a positive whole number."); return; }
                ObjectNode amount = RuleJson.JSON.createObjectNode();
                amount.putArray("multiply").add(read("event.applied.armorMarked")).add(entry.getValue().asInt());
                if ("hope".equals(entry.getKey())) steps.addObject().put("op", "resource.spend").put("pool", "actor.hope").set("amount", amount);
                else if (TRACKS.containsKey(entry.getKey())) steps.addObject().put("op", "track.mark").put("track", TRACKS.get(entry.getKey())).set("amount", amount);
                else if (pools.has(entry.getKey())) steps.addObject().put("op", "pool.spend").put("pool", entry.getKey()).set("amount", amount);
                else p.add(path + "/armorCost/" + entry.getKey(), "Custom resource cost needs a declared pool: " + entry.getKey());
            });
        }
        if (mitigation.has("markStress")) {
            JsonNode stress = mitigation.get("markStress");
            if (!applied || !stress.isInt() || stress.asInt() < 1) { p.add(path + "/markStress", "Expected a positive whole number in the applied stage."); return; }
            ObjectNode free = RuleJson.JSON.createObjectNode();
            free.putArray("subtract").add(read("actor.stress.capacity")).add(read("actor.stress.marked"));
            ObjectNode marked = RuleJson.JSON.createObjectNode();
            marked.putArray("min").add(stress.asInt()).add(free);
            steps.addObject().put("op", "value").put("as", "stressMarked").set("value", marked);
            steps.addObject().put("op", "track.mark").put("track", "actor.stress").set("amount", read("result.stressMarked"));
            ObjectNode overflow = RuleJson.JSON.createObjectNode();
            overflow.putArray("subtract").add(stress.asInt()).add(read("result.stressMarked"));
            ObjectNode hpFree = RuleJson.JSON.createObjectNode();
            hpFree.putArray("subtract").add(read("actor.hp.capacity")).add(read("actor.hp.marked"));
            ObjectNode capped = RuleJson.JSON.createObjectNode();
            capped.putArray("min").add(overflow).add(hpFree);
            steps.addObject().put("op", "track.mark").put("track", "actor.hp").set("amount", capped);
        }
    }

    private static void report(JsonNode legacy, String path, Problems p, ObjectNode ui) {
        report(legacy, path, p, ui, null, null);
    }

    /** Action report values are captured after costs but before the action's subsequent steps. */
    private static void report(JsonNode legacy, String path, Problems p, ObjectNode ui, ArrayNode steps, Pools pools) {
        if (!legacy.has("report")) return;
        JsonNode report = legacy.get("report");
        String text = report.isTextual() ? escape(report.asText()) : null;
        if (report.isObject()) {
            p.onlyFields(report, path + "/report", Set.of("text", "amount", "dice"));
            text = escape(report.path("text").asText(""));
            if (report.has("dice")) {
                if (steps == null) p.add(path + "/report/dice", "Rolled reports are supported only for actions.");
                else {
                    for (String otherRoll : List.of("table", "actionRoll", "damage", "sendRoll", "resourceRoll"))
                        if (legacy.has(otherRoll)) p.add(path + "/report/dice", "Rolled reports cannot yet preserve the order of " + otherRoll + ".");
                    ObjectNode plan = reportRoll(report.get("dice"), path + "/report/dice", p);
                    if (plan != null) {
                        ObjectNode roll = RuleJson.JSON.createObjectNode().put("op", "roll.resolve").put("as", "reportRoll");
                        roll.putObject("plan").set("literal", plan);
                        steps.insert(0, roll);
                        int valueIndex = 1;
                        if ("downtime".equals(legacy.path("timing").asText())) {
                            ObjectNode offer = RuleJson.JSON.createObjectNode().put("op", "downtime.offerRoll")
                                    .put("label", legacy.path("label").asText("Downtime report"));
                            offer.set("roll", read("result.reportRoll"));
                            offer.putObject("if").putArray("eq").add(read("event.move.rest")).add("short");
                            steps.insert(valueIndex++, offer);
                        }
                        ObjectNode value = RuleJson.JSON.createObjectNode();
                        value.putArray("max").add(0).add(read("result.reportRoll.total"));
                        steps.insertObject(valueIndex).put("op", "value").put("as", "reportAmount").set("value", value);
                        text = substituteReportAmount(text, "{result.reportAmount}");
                    }
                }
            } else if (report.has("amount")) {
                JsonNode amount = report.get("amount");
                JsonNode value = null;
                if (amount.isTextual()) {
                    String token = amount.asText();
                    String trait = TRAITS.stream().filter(name -> token.equals("@" + name)).findFirst().orElse(null);
                    value = trait == null ? computed(token) : read("actor.stats.trait." + trait);
                    if (value == null && steps != null && token.startsWith("@") && pools.has(token.substring(1)))
                        value = read("actor.pools." + token.substring(1) + ".value");
                }
                if (amount.isInt()) text = substituteReportAmount(text, Integer.toString(Math.max(0, amount.asInt())));
                else if (value != null && steps != null) {
                    if (legacy.has("toggleEffect"))
                        p.add(path + "/report/amount", "A computed report after toggling an effect cannot yet preserve legacy ordering.");
                    if (RuleExpression.references(value).stream().anyMatch(ref -> ref.startsWith("actor.stats."))
                            && List.of("cost", "selectAmount", "castTokens", "resourceRoll", "portions").stream().anyMatch(legacy::has))
                        p.add(path + "/report/amount", "Derived report values after payment require recalculating actor stats.");
                    ObjectNode bounded = RuleJson.JSON.createObjectNode();
                    bounded.putArray("max").add(0).add(value);
                    steps.insertObject(0).put("op", "value").put("as", "reportAmount").set("value", bounded);
                    text = substituteReportAmount(text, "{result.reportAmount}");
                } else p.add(path + "/report/amount", value != null && steps == null
                        ? "Computed reports outside actions cannot yet preserve their evaluation order."
                        : "Report amounts must be whole numbers or a directly projected value.");
            } else text = substituteReportAmount(text, "0");
        }
        if (text == null || text.isBlank()) p.add(path + "/report", "Report text is missing.");
        else ui.put("report", text);
    }

    private static String substituteReportAmount(String text, String value) {
        int index = text.indexOf("{{amount}}");
        return index < 0 ? text : text.substring(0, index) + value + text.substring(index + "{{amount}}".length());
    }

    /** Parse the entire legacy expression into bounded, ordered generic roll contributions. */
    private static ObjectNode reportRoll(JsonNode dice, String path, Problems p) {
        if (!dice.isTextual()) { p.add(path, "Expected a dice expression."); return null; }
        String expression = dice.asText().replaceAll("\\s", "");
        if (expression.isEmpty() || expression.length() > 1024) {
            p.add(path, "Expected a nonempty dice expression of at most 1024 characters.");
            return null;
        }
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", "report");
        ArrayNode contributions = plan.putArray("contributions");
        var matcher = REPORT_DICE_TERM.matcher(expression);
        int offset = 0, totalDice = 0;
        while (offset < expression.length()) {
            matcher.region(offset, expression.length());
            if (!matcher.lookingAt() || offset > 0 && matcher.group(1).isEmpty() || contributions.size() == 256) {
                p.add(path, "Unsupported report dice expression: " + dice.asText());
                return null;
            }
            int sign = "-".equals(matcher.group(1)) ? -1 : 1;
            ObjectNode part = contributions.addObject().put("id", "term-" + contributions.size())
                    .put("role", "base").put("sourceId", "core").put("label", "Report");
            try {
                if (matcher.group(3) != null) {
                    int count = matcher.group(2).isEmpty() ? 1 : Integer.parseInt(matcher.group(2));
                    int sides = Integer.parseInt(matcher.group(3));
                    if (count > 1024 || sides < 2 || sides > 1000 || totalDice + count > 1024)
                        throw new NumberFormatException();
                    totalDice += count;
                    part.put("kind", "dice").put("count", count).put("sides", sides).put("sign", sign);
                } else {
                    int flat = Integer.parseInt(matcher.group(4));
                    if (flat > 1_000_000) throw new NumberFormatException();
                    part.put("kind", "flat").put("value", sign * flat);
                }
            } catch (NumberFormatException invalid) {
                p.add(path, "Report dice exceed the supported count, die-size or flat-value bounds: " + dice.asText());
                return null;
            }
            offset = matcher.end();
        }
        return plan;
    }

    private static ObjectNode spellcastProfile(JsonNode legacy, String path, Problems p, boolean limited, String origin) {
        JsonNode difficulty = legacy.path("spellcastDifficulty");
        if (!difficulty.isInt() || difficulty.asInt() < 0 || difficulty.asInt() > 1_000_000) {
            p.add(path + "/spellcastDifficulty", "Expected a nonnegative whole difficulty up to 1000000.");
            return null;
        }
        for (String flag : List.of("automaticRestUse", "automaticRestUseOnAttempt"))
            if (legacy.has(flag) && !legacy.path(flag).isBoolean()) p.add(path + "/" + flag, "Expected a boolean.");
        boolean onAttempt = legacy.path("automaticRestUseOnAttempt").asBoolean(false);
        boolean consumes = legacy.path("automaticRestUse").asBoolean(false);
        if (onAttempt && !consumes)
            p.add(path + "/automaticRestUseOnAttempt", "Attempt consumption requires automaticRestUse.");
        if (consumes && !limited) {
            p.add(path + "/automaticRestUse", "Automatic use consumption needs a declared use limit.");
            return null;
        }
        ObjectNode profile = RuleJson.JSON.createObjectNode().put("id", "spellcast-roll")
                .put("label", "Spellcast Roll (Difficulty " + difficulty.asInt() + ")")
                .put("rollKind", "spellcast").put("trait", "spellcast")
                .put("difficulty", difficulty.asInt()).put("origin", origin);
        if (consumes) profile.put("limitRef", "uses").put("consumeOn", onAttempt ? "attempt" : "success");
        return profile;
    }

    /**
     * A source-specific spellcast roll against a written difficulty. With automatic use consumption the source budget is
     * reserved up front and refunded when the roll fails, unless every attempt counts.
     */
    private static ObjectNode spellcastRoll(JsonNode legacy, String path, Problems p, boolean limited) {
        JsonNode difficulty = legacy.path("spellcastDifficulty");
        if (!difficulty.isInt() || difficulty.asInt() < 0) {
            p.add(path + "/spellcastDifficulty", "Expected a nonnegative whole difficulty.");
            return null;
        }
        for (String flag : List.of("automaticRestUse", "automaticRestUseOnAttempt"))
            if (legacy.has(flag) && !legacy.path(flag).isBoolean()) p.add(path + "/" + flag, "Expected a boolean.");
        boolean consumes = legacy.path("automaticRestUse").asBoolean(false);
        boolean onAttempt = legacy.path("automaticRestUseOnAttempt").asBoolean(false);
        if (onAttempt && !consumes) consumes = true;
        if (consumes && !limited) {
            p.add(path + "/automaticRestUse", "Automatic use consumption needs a declared use limit.");
            return null;
        }
        ObjectNode action = RuleJson.JSON.createObjectNode().put("id", "spellcast-roll")
                .put("label", "Spellcast Roll (Difficulty " + difficulty.asInt() + ")");
        action.putObject("actionRoll").put("trait", "spellcast").put("difficulty", difficulty.asInt());
        ObjectNode rule = action(action, path + "/spellcastDifficulty", p, RuleJson.JSON.createObjectNode(),
                new Pools(RuleJson.JSON.createObjectNode(), Map.of()), consumes);
        if (rule == null) return null;
        ArrayNode steps = (ArrayNode) rule.get("do");
        steps.addObject().put("op", "roll.check").put("as", "check").set("from", read("result.actionRoll"));
        if (consumes && !onAttempt) {
            ObjectNode refund = RuleJson.JSON.createObjectNode();
            ObjectNode failed = RuleJson.JSON.createObjectNode();
            failed.putArray("eq").add(read("result.check.outcome")).add("failure");
            refund.putArray("if").add(failed).add(1).add(0);
            steps.addObject().put("op", "limit.modify").put("limit", "uses").set("by", refund);
        }
        ((ObjectNode) rule.get("ui")).put("report", "Roll total: {result.actionRoll.total} against {result.check.difficulty}: {result.check.outcome}.");
        return rule;
    }

    /** Maps a legacy target mode to a binding target selection; returns the operation target for the action steps. */
    private static String targetSelection(JsonNode legacy, String path, Problems p, ObjectNode binding) {
        if (!legacy.has("target")) return "actor";
        if (!legacy.path("target").isTextual()) { p.add(path + "/target", "Expected a supported target mode."); return "actor"; }
        String[] target = switch (legacy.path("target").asText()) {
            case "actor" -> new String[]{"one", "connectedPlayerCharacters"};
            case "anyActor" -> new String[]{"one", "activeCharacters"};
            case "party" -> new String[]{"all", "connectedPlayerCharacters"};
            case "allActors" -> new String[]{"all", "activePlayerCharacters"};
            default -> null;
        };
        if (target == null) { p.add(path + "/target", "Unsupported target mode: " + legacy.path("target").asText()); return "actor"; }
        ObjectNode selection = binding.putObject("targetSelection");
        selection.put("cardinality", target[0]).put("scope", target[1]);
        if (legacy.has("excludeSourceActor") && !legacy.path("excludeSourceActor").isBoolean())
            p.add(path + "/excludeSourceActor", "Expected a boolean.");
        selection.put("excludeSource", legacy.path("excludeSourceActor").asBoolean(false));
        return "recipient";
    }

    /** Roll metadata field recording how many tokens the cast spent, read later by {@code spentTokenDamage} triggers. */
    private static final String SPENT_TOKENS = "spentTokens";
    private static final String COUNT_READ = "@countRead";

    /**
     * {@code hostRollAction}, {@code bloodAutomation} and {@code otherPlayerOnly} only described how the legacy host
     * gated a roll; the converted rule already pays, limits and filters by its own cost, limit and audience, so they are
     * validated and dropped.
     */
    private static void hostRollAction(JsonNode legacy, String path, Problems p) {
        for (String flag : List.of("hostRollAction", "bloodAutomation", "otherPlayerOnly"))
            if (legacy.has(flag) && !legacy.path(flag).asBoolean(false)) p.add(path + "/" + flag, "Expected true.");
        if (legacy.path("hostRollAction").asBoolean(false)) {
            boolean action = legacy.has("actionRoll");
            if (action && legacy.path("actionRoll").path("reaction").asBoolean(false))
                p.add(path + "/hostRollAction", "A host-paid roll action cannot be a reaction.");
            else if (!action && !legacy.has("reroll")) p.add(path + "/hostRollAction", "A host-paid roll needs an action roll or a reroll.");
        }
        if (legacy.has("otherPlayerOnly") && !"ally".equals(legacy.path("audience").asText()))
            p.add(path + "/otherPlayerOnly", "Other-player-only triggers need the ally audience.");
    }

    /** {@code castTokens}: the cast chooses how many tokens of a pool to spend; the roll records the amount. */
    private static JsonNode castTokens(JsonNode action, String path, Problems p) {
        JsonNode spec = action.path("castTokens");
        p.onlyFields(spec, path + "/castTokens", Set.of("resource", "sides"));
        if (action.has("selectAmount")) p.add(path + "/castTokens", "A cast cannot also select another amount.");
        if (!action.has("actionRoll")) p.add(path + "/castTokens", "Spent tokens need an action roll.");
        if (spec.path("resource").asText("").isBlank()) p.add(path + "/castTokens/resource", "Expected a resource.");
        if (!spec.path("sides").isInt() || spec.path("sides").asInt() < 2 || spec.path("sides").asInt() > 1000)
            p.add(path + "/castTokens/sides", "Expected a die size of 2 to 1000.");
        ObjectNode copy = action.deepCopy();
        copy.remove("castTokens");
        copy.putObject("selectAmount").put("resource", spec.path("resource").asText()).put("label", "Tokens to spend");
        return copy;
    }

    private static ObjectNode action(JsonNode action, String path, Problems p, ObjectNode effects, Pools pools, boolean limited) {
        p.onlyFields(action, path, ACTION_FIELDS);
        hostRollAction(action, path, p);
        boolean spendsTokens = action.has("castTokens");
        if (spendsTokens) action = castTokens(action, path, p);
        String id = action.path("id").asText();
        if (!ID.matcher(id).matches()) { p.add(path + "/id", "Invalid action ID: " + id); return null; }
        String label = action.path("label").asText("");
        if (label.isBlank()) p.add(path + "/label", "Action buttons need a label.");
        ObjectNode rule = RuleJson.JSON.createObjectNode();
        rule.put("id", id);
        ObjectNode binding = rule.putArray("bindings").addObject()
                .put("id", "sheet").put("event", "action.request").put("surface", "sheet").put("mode", "button");
        if (action.has("timing")) {
            if (!Set.of("downtime", "rest").contains(action.path("timing").asText()))
                p.add(path + "/timing", "Unsupported action timing: " + action.path("timing").asText());
            else {
                binding.put("id", "downtime").put("event", "downtime.move").put("surface", "downtime");
                String rest = action.path("downtime").path("rest").asText("");
                binding.putObject("restMove").put("id", id)
                        .put("kind", Set.of("short", "long").contains(rest) ? rest : "any");
            }
        }
        String operationTarget = targetSelection(action, path, p, binding);
        boolean secondWind = "secondWind".equals(action.path("splendorAction").asText());
        if (action.has("splendorAction") && !secondWind) p.add(path + "/splendorAction", "Unsupported Splendor action: " + action.path("splendorAction").asText());
        if (!action.has("target") && action.has("excludeSourceActor") && !action.has("chatBenefits") && !action.has("benefitsPerAmount"))
            p.add(path + "/excludeSourceActor", "Source exclusion requires a target mode or chat benefits.");
        if (action.has("hostOwned") && !action.path("hostOwned").isBoolean())
            p.add(path + "/hostOwned", "Expected a boolean.");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        commonConditions(action, path, p, when);
        companionGates(action, path, p, pools, when);
        JsonNode damage = action.path("damage");
        boolean usesSpellcastTrait = "spellcast".equals(action.path("actionRoll").path("trait").asText())
                || "spellcast".equals(damage.path("count").asText());
        if (usesSpellcastTrait)
            when.addObject().put("exists", "actor.spellcastTrait.value");
        sentRollConditions(action, when);
        var dicePool = POOL_DICE.matcher(damage.path("dice").asText(""));
        if (dicePool.matches() && pools.has(dicePool.group(1)))
            when.addObject().putArray("gt").add(read("actor.pools." + dicePool.group(1) + ".value")).add(0);
        if (action.has("channel") && (!action.path("channel").isTextual() || action.path("channel").asText().isBlank()))
            p.add(path + "/channel", "Expected an element name.");
        boolean aura = action.has("aura");
        if (aura && !action.path("aura").asBoolean(false)) p.add(path + "/aura", "Expected true.");
        if (aura) {
            ObjectNode pluck = RuleJson.JSON.createObjectNode();
            pluck.putArray("pluck").add(read("actor.effects")).add("group");
            when.addObject().putArray("includes").add(pluck).add("channel");
        }
        if ("nightTerror".equals(action.path("specialAction").asText())) {
            when.add(hasFact("event.table.fear"));
            when.addObject().putArray("gt").add(read("event.table.fear")).add(0);
        }
        applyWhen(rule, when);
        if (action.has("cost")) costs(action.get("cost"), path + "/cost", p, pools, rule);
        if (action.has("selectAmount")) {
            selectAmount(action.get("selectAmount"), path + "/selectAmount", p, pools, rule);
            amountCost(action, path + "/selectAmount", p, pools, rule);
            if (action.path("selectAmount").has("addToRoll")) p.add(path + "/selectAmount/addToRoll", "Only roll triggers can add the amount to a roll.");
        }
        boolean transfer = action.has("transferHope");
        if (transfer) {
            if (!action.path("transferHope").asBoolean(false) || !"recipient".equals(operationTarget) || action.has("selectAmount")
                    || !"one".equals(binding.path("targetSelection").path("cardinality").asText())) {
                p.add(path + "/transferHope", "A Hope transfer needs exactly one ally target and no other amount.");
                transfer = false;
            } else {
                ((ObjectNode) binding.get("targetSelection")).put("excludeSource", true);
                selectAmount(RuleJson.JSON.createObjectNode().put("resource", "hope").put("label", "Hope to give"), path + "/transferHope", p, pools, rule);
                ArrayNode costs = rule.has("costs") ? (ArrayNode) rule.get("costs") : rule.putArray("costs");
                costs.addObject().put("op", "resource.spend").put("pool", "actor.hope").set("amount", read("input.amount"));
            }
        }
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = RuleJson.JSON.createArrayNode();
        if (action.has("actionRoll")) actionRoll(action.get("actionRoll"), path + "/actionRoll", action, p, steps);
        if (spendsTokens && rule.has("inputs") && !steps.isEmpty())
            ((ObjectNode) steps.get(0)).putObject("metadata").set(SPENT_TOKENS, read("input.amount"));
        if (action.has("damage")) {
            var pooled = POOL_DICE.matcher(damage.path("dice").asText(""));
            if (pooled.matches() && !pools.has(pooled.group(1))) p.add(path + "/damage/dice", "Unknown dice-count pool: " + pooled.group(1));
            else {
                actionDamage(action.get("damage"), path + "/damage", action, p, steps);
            }
        }
        if (action.has("sendRoll")) sentRoll(action.get("sendRoll"), path + "/sendRoll", action, p, steps);
        if (action.has("resourceRoll")) resourceRoll(action.get("resourceRoll"), path + "/resourceRoll", action, p, pools, rule, steps);
        String patronReport = action.has("patronDice") ? patronAction(action, path, p, pools, rule, steps) : null;
        if (aura) {
            ObjectNode marker = RuleJson.JSON.createObjectNode().put("name", label).put("kind", "buff").put("until", "rest")
                    .put("text", "Active with the element currently channeled.");
            String effect = declareEffect(marker, path + "/aura", p, effects);
            if (effect != null) steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        }
        if (action.has("check")) check(action, path, p, rule, steps, effects, pools, operationTarget);
        else noChoices(action, path, p);
        JsonNode list = action.has("check") ? RuleJson.JSON.missingNode() : action.path("steps");
        amountStore(action.path("selectAmount"), path + "/selectAmount", p, pools, steps, effects);
        for (int i = 0; i < list.size(); i++) step(list.get(i), path + "/steps/" + i, p, steps, effects, pools, operationTarget);
        amountHeal(action.path("selectAmount"), path + "/selectAmount", p, steps);
        if (action.has("table")) actionTable(action.get("table"), path + "/table", p, steps, effects, pools, operationTarget);
        endEffect(action, steps, operationTarget);
        if (action.has("toggleEffect")) {
            String toggled = declareEffect(action.get("toggleEffect"), path + "/toggleEffect", p, effects);
            if (toggled != null) {
                ObjectNode present = labelPresent(escape(action.path("toggleEffect").path("name").asText()));
                steps.addObject().put("op", "effect.end").put("effect", toggled).put("target", "actor").set("if", present.deepCopy());
                steps.addObject().put("op", "effect.start").put("effect", toggled).put("target", "actor")
                        .putObject("if").set("not", present);

            }
        }
        if (secondWind) secondWind(rule, steps, "actor");
        if (transfer) steps.addObject().put("op", "resource.gain").put("pool", "recipient.hope").set("amount", read("input.amount"));
        if (action.has("refreshUses")) {
            String rest = action.path("refreshUses").asText();
            if (!Set.of("short", "long").contains(rest)) p.add(path + "/refreshUses", "Expected short or long.");
            else {
                ArrayNode on = steps.addObject().put("op", "limits.refresh")
                        .put("label", "Refreshed limited uses as if after a " + rest + " rest").putArray("on").add("rest.short");
                if ("long".equals(rest)) on.add("rest.long");
            }
        }
        String specialReport = action.has("specialAction") ? specialAction(action, path, p, effects, rule, steps) : null;
        if (!steps.isEmpty()) rule.set("do", steps);
        ObjectNode ui = rule.putObject("ui");
        ui.put("label", escape(label));
        if (specialReport != null) ui.put("report", specialReport);
        if (action.has("description")) ui.put("description", escape(action.path("description").asText()));
        if (aura) ui.put("unavailable", "Channel an element first.");
        if (!action.has("report")) {
            if (patronReport != null) ui.put("report", patronReport);
            else if (transfer) ui.put("report", "Gave {input.amount} Hope to {recipient.name}.");
            else if (action.has("actionRoll") && action.has("damage"))
                ui.put("report", "Roll total: {result.actionRoll.total}. Damage total: {result.actionDamage.total}.");
            else if (action.has("actionRoll")) ui.put("report", "Roll total: {result.actionRoll.total}.");
            else if (action.has("damage")) ui.put("report", "Damage total: {result.actionDamage.total}.");
            else if (action.has("table")) ui.put("report", "{source.name}: rolled {result.table.faces|list}. {result.table.outcomes|list}");
            else if (action.has("toggleEffect")) ui.put("report", "{source.name}: toggled.");
        }
        report(action, path, p, ui, steps, pools);
        if (!steps.isEmpty() && !rule.has("do")) rule.set("do", steps);
        resourceRollReport(action, ui);
        sentRollReport(action, ui);
        checkReport(action, ui);
        return rule;
    }

    /**
     * Cards with a bespoke legacy handler: each becomes ordinary inputs, costs and steps. Returns the default report.
     */
    private static String specialAction(JsonNode action, String path, Problems p, ObjectNode effects, ObjectNode rule, ArrayNode steps) {
        String kind = action.path("specialAction").asText();
        switch (kind) {
            case "massDisguise" -> {
                steps.addObject().put("op", "countdown.start").put("label", "Mass Disguise").put("value", 8);
                return "Started a Mass Disguise countdown (8). It ticks down as a consequence the GM chooses; when it triggers, the disguise drops.";
            }
            case "nightTerror" -> {
                ObjectNode targets = rule.withArray("inputs").addObject().put("id", "targets").put("kind", "integer")
                        .put("label", "Horrified targets");
                targets.put("min", 1);
                targets.putObject("max").putArray("min").add(read("event.table.fear")).add(1000);
                steps.addObject().put("op", "table.spend").put("resource", "fear").put("label", "Steal Fear from the GM").put("as", "stolen")
                        .set("amount", read("input.targets"));
                steps.addObject().put("op", "dice.roll").put("sides", 6).put("as", "damage").set("count", read("input.targets"));
                return "Stole {input.targets} Fear and rolled {result.damage} damage for each Horrified target. Apply it at the table.";
            }
            case "vitality" -> {
                ObjectNode pick = rule.withArray("inputs").addObject().put("id", "pick").put("kind", "choice")
                        .put("label", "Choose two permanent benefits").put("count", 2);
                ArrayNode options = pick.putArray("options");
                options.addObject().put("id", "stress").put("label", "One Stress slot");
                options.addObject().put("id", "hp").put("label", "One Hit Point slot");
                options.addObject().put("id", "thresholds").put("label", "+2 bonus to your damage thresholds");
                for (String slot : List.of("stress", "hp")) {
                    ObjectNode step = steps.addObject().put("op", "track.capacity").put("track", "actor." + slot).put("by", 1);
                    step.putObject("if").putArray("includes").add(read("input.pick")).add(slot);
                }
                ObjectNode bonus = RuleJson.JSON.createObjectNode().put("name", "Vitality").put("kind", "buff")
                        .put("text", "A permanent +2 bonus to your damage thresholds.");
                bonus.putArray("modifiers").addObject().put("stat", "thresholds").put("value", 2);
                String effect = declareEffect(bonus, path + "/specialAction", p, effects);
                if (effect != null) {
                    ObjectNode start = steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
                    start.putObject("if").putArray("includes").add(read("input.pick")).add("thresholds");
                }
                steps.addObject().put("op", "collection.remove").put("target", "actor").put("collection", "loadout").put("as", "card")
                        .set("item", read("source.instanceId"));
                steps.addObject().put("op", "collection.add").put("target", "actor").put("collection", "vault").set("item", read("result.card"));
                return "{source.name}: gained the chosen permanent benefits and moved the card to the vault.";
            }
            default -> p.add(path + "/specialAction", "Unsupported special action: " + kind);
        }
        return null;
    }

    private static final Pattern CHECK_DIE = Pattern.compile("1?d(\\d{1,3})");

    /**
     * A one-die outcome table whose {@code min}/{@code max} face ranges cover the die exactly. It becomes a
     * {@code dice.table} roll listing each face's outcome text, followed by every outcome's steps gated with {@code if}
     * on the rolled face.
     */
    private static void actionTable(JsonNode table, String at, Problems p, ArrayNode steps, ObjectNode effects, Pools pools, String target) {
        if (!table.isObject()) { p.add(at, "Expected a table object."); return; }
        p.onlyFields(table, at, Set.of("dice", "outcomes"));
        var die = CHECK_DIE.matcher(table.path("dice").asText(""));
        int sides = die.matches() ? Integer.parseInt(die.group(1)) : 0;
        if (sides < 2 || sides > 100) { p.add(at + "/dice", "Expected one die with 2 to 100 sides."); return; }
        JsonNode outcomes = table.path("outcomes");
        if (!outcomes.isArray() || outcomes.isEmpty()) { p.add(at + "/outcomes", "Expected outcomes."); return; }
        String[] texts = new String[sides];
        ObjectNode face = RuleJson.JSON.createObjectNode();
        face.putArray("at").add(read("result.table.faces")).add(0);
        ArrayNode gated = RuleJson.JSON.createArrayNode();
        int next = 1;
        for (int i = 0; i < outcomes.size(); i++) {
            JsonNode outcome = outcomes.get(i);
            String path = at + "/outcomes/" + i;
            p.onlyFields(outcome, path, Set.of("min", "max", "text", "steps"));
            int min = outcome.path("min").asInt(0), max = outcome.has("max") ? outcome.path("max").asInt(0) : min;
            if (!outcome.path("min").isInt() || min != next || max < min || max > sides) {
                p.add(path, "Outcome ranges must cover the die in order without gaps.");
                return;
            }
            next = max + 1;
            String text = outcome.path("text").asText("");
            if (text.isBlank()) { p.add(path + "/text", "Outcomes need a text."); return; }
            for (int f = min; f <= max; f++) texts[f - 1] = escape(text);
            ArrayNode converted = RuleJson.JSON.createArrayNode();
            JsonNode list = outcome.path("steps");
            for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, converted, effects, pools, target);
            ObjectNode in = RuleJson.JSON.createObjectNode();
            if (min == max) in.putArray("eq").add(face.deepCopy()).add(min);
            else {
                ArrayNode both = in.putArray("and");
                both.addObject().putArray("gte").add(face.deepCopy()).add(min);
                both.addObject().putArray("lte").add(face.deepCopy()).add(max);
            }
            for (JsonNode op : converted) {
                if (op.has("as")) { p.add(path + "/steps", "Table outcome steps cannot roll amounts."); return; }
                ((ObjectNode) op).set("if", in.deepCopy());
                gated.add(op);
            }
        }
        if (next != sides + 1) { p.add(at + "/outcomes", "Outcome ranges must cover the die in order without gaps."); return; }
        ObjectNode roll = steps.addObject().put("op", "dice.table").put("count", 1);
        ArrayNode labels = roll.putArray("outcomes");
        for (String text : texts) labels.add(text);
        roll.put("as", "table");
        steps.addAll(gated);
    }
    private static final List<String> FORAGED = List.of("foraged-food", "foraged-relic", "foraged-rune", "foraged-vial", "foraged-charm");
    private static final Pattern CATALOG_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /**
     * A downtime move body: {@code craft} grants one catalog consumable (at most 5 carried), {@code table} rolls
     * {@code repeat} (default 1) dice whose faces pick the listed outcomes, {@code forage} grants the foraged consumable
     * of faces 1-5 (party cap 5) or offers a choice on a 6, {@code additional} frees the move slot ({@code event.move.slots}),
     * {@code rest} limits the move to a short or long rest and {@code consumeItem} uses up one unit of the source item.
     */
    private static void downtime(JsonNode action, String path, Problems p, ObjectNode rule, List<ObjectNode> extra) {
        String at = path + "/downtime";
        JsonNode downtime = action.get("downtime");
        if (!downtime.isObject()) { p.add(at, "Expected a downtime object."); return; }
        p.onlyFields(downtime, at, Set.of("craft", "table", "additional", "rest", "consumeItem"));
        if (!Set.of("downtime", "rest").contains(action.path("timing").asText())) p.add(at, "Downtime bodies need a downtime timing.");
        ArrayNode steps = rule.has("do") ? (ArrayNode) rule.get("do") : rule.putArray("do");
        ObjectNode ui = (ObjectNode) rule.get("ui");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        restFilter(downtime, at, p, "event.move.rest", when);
        if (!when.isEmpty()) applyWhen(rule, when);
        for (String flag : List.of("additional", "consumeItem"))
            if (downtime.has(flag) && !downtime.path(flag).isBoolean()) p.add(at + "/" + flag, "Expected a boolean.");
        if (downtime.path("additional").asBoolean(false))
            steps.add(planAdjust("event.move", "slots", -1, "Additional downtime move"));
        if (downtime.path("consumeItem").asBoolean(false)) {
            ArrayNode costs = rule.has("costs") ? (ArrayNode) rule.get("costs") : rule.putArray("costs");
            costs.addObject().put("op", "inventory.consume").put("amount", 1);
        }
        if (downtime.has("craft")) {
            String id = downtime.path("craft").asText("");
            if (!CATALOG_ID.matcher(id).matches()) p.add(at + "/craft", "Expected a consumable catalog ID.");
            else {
                steps.addObject().put("op", "inventory.grant").put("catalog", "consumables").put("entry", id).put("quantity", 1)
                        .put("max", 5).put("scope", "actor").put("label", ui.path("label").asText("Craft"));
                if (!action.has("report")) ui.put("report", "{source.name}: crafted one " + escape(title(id)) + ".");
            }
        }
        if (downtime.has("table")) table(downtime.get("table"), at + "/table", p, rule, steps, ui, extra, action.has("report"));
        if (steps.isEmpty()) rule.remove("do");
    }

    private static void table(JsonNode table, String at, Problems p, ObjectNode rule, ArrayNode steps, ObjectNode ui, List<ObjectNode> extra,
                              boolean reported) {
        if (!table.isObject()) { p.add(at, "Expected a table object."); return; }
        p.onlyFields(table, at, Set.of("expression", "outcomes", "repeat", "forage"));
        var die = CHECK_DIE.matcher(table.path("expression").asText(""));
        JsonNode outcomes = table.path("outcomes");
        if (!die.matches()) { p.add(at + "/expression", "Expected one die such as 1d6."); return; }
        int sides = Integer.parseInt(die.group(1));
        if (!outcomes.isArray() || outcomes.size() != sides || sides < 2 || sides > 100) {
            p.add(at + "/outcomes", "Expected one outcome per die face.");
            return;
        }
        ArrayNode labels = RuleJson.JSON.createArrayNode();
        for (JsonNode outcome : outcomes) {
            if (!outcome.isTextual() || outcome.asText().isBlank()) { p.add(at + "/outcomes", "Outcomes must be texts."); return; }
            labels.add(escape(outcome.asText()));
        }
        if (table.has("forage") && !table.path("forage").isBoolean()) p.add(at + "/forage", "Expected a boolean.");
        boolean forage = table.path("forage").asBoolean(false);
        JsonNode count = RuleJson.JSON.getNodeFactory().numberNode(1);
        if (table.has("repeat")) {
            if (forage) { p.add(at + "/repeat", "A forage table rolls once."); return; }
            ArrayNode add = RuleJson.JSON.createArrayNode();
            String[] terms = table.path("repeat").asText("").replace(" ", "").replace("-", "+-").split("\\+");
            for (int i = 0; i < terms.length; i++) {
                String term = terms[i];
                if (term.isEmpty()) continue;
                var dice = DICE.matcher(term.replaceFirst("^d", "1d"));
                if (dice.matches()) {
                    String slot = "repeat" + i;
                    steps.addObject().put("op", "dice.roll").put("count", Integer.parseInt(dice.group(1)))
                            .put("sides", Integer.parseInt(dice.group(2))).put("as", slot);
                    ObjectNode offer = steps.addObject().put("op", "downtime.offerRoll")
                            .put("label", ui.path("label").asText("Downtime table") + " count");
                    offer.set("roll", read("result." + slot));
                    offer.putObject("if").putArray("eq").add(read("event.move.rest")).add("short");
                    add.add(read("result." + slot));
                } else if (term.matches("-?\\d{1,2}")) add.add(Integer.parseInt(term));
                else {
                    JsonNode value = computed(term);
                    if (value == null) { p.add(at + "/repeat", "Unsupported repeat term: " + term); return; }
                    add.add(value);
                }
            }
            if (add.isEmpty()) { p.add(at + "/repeat", "Expected a repeat count."); return; }
            ObjectNode sum = RuleJson.JSON.createObjectNode();
            sum.set("add", add);
            ObjectNode bounded = RuleJson.JSON.createObjectNode();
            bounded.putArray("clamp").add(sum).add(0).add(100);
            count = bounded;
        }
        ObjectNode roll = steps.addObject().put("op", "dice.table");
        roll.set("count", count);
        roll.set("outcomes", labels);
        roll.put("as", "table");
        if (!forage) {
            ObjectNode offer = steps.addObject().put("op", "downtime.offerRoll")
                    .put("label", ui.path("label").asText("Downtime table"));
            offer.set("roll", read("result.table.faces"));
            ArrayNode conditions = offer.putObject("if").putArray("and");
            conditions.addObject().putArray("eq").add(read("event.move.rest")).add("short");
            conditions.addObject().putArray("gt").add(read("result.table.count")).add(0);
        }
        if (!reported) ui.put("report", "{source.name}: rolled {result.table.faces|list}: {result.table.outcomes|list}.");
        if (!forage) return;
        if (sides != FORAGED.size() + 1) { p.add(at + "/outcomes", "A forage table lists the five foraged consumables and a choice."); return; }
        ObjectNode face = RuleJson.JSON.createObjectNode();
        face.putArray("at").add(read("result.table.faces")).add(0);
        ObjectNode index = RuleJson.JSON.createObjectNode();
        index.putArray("subtract").add(face).add(1);
        ObjectNode ids = RuleJson.JSON.createObjectNode();
        ids.set("literal", RuleJson.JSON.valueToTree(FORAGED));
        ObjectNode entry = RuleJson.JSON.createObjectNode();
        entry.putArray("at").add(ids).add(index);
        ObjectNode found = RuleJson.JSON.createObjectNode();
        found.putArray("lt").add(face.deepCopy()).add(sides);
        ObjectNode grant = forageGrant(entry, "Foraged consumable");
        grant.set("if", found);
        steps.add(grant);
        String group = rule.path("id").asText() + "-forage";
        ObjectNode choose = RuleJson.JSON.createObjectNode();
        choose.putArray("eq").add(face.deepCopy()).add(sides);
        steps.addObject().put("op", "claim.offer").put("group", group).put("label", "Choose one foraged consumable").put("limit", 1)
                .set("if", choose);
        for (String id : FORAGED) {
            ObjectNode claim = RuleJson.JSON.createObjectNode().put("id", rule.path("id").asText() + "-" + id);
            ObjectNode binding = claim.putArray("bindings").addObject().put("id", "claim").put("event", "claim.accept")
                    .put("surface", "chat").put("mode", "button").put("claimGroup", group).put("audience", "self");
            binding.putObject("claimScope").put("recipients", "sourceActor");
            claim.putArray("do").add(forageGrant(RuleJson.JSON.getNodeFactory().textNode(id), title(id)));
            claim.putObject("ui").put("label", "Forager: " + title(id)).put("report", "{recipient.name} foraged one " + title(id) + ".");
            extra.add(claim);
        }
    }

    private static ObjectNode forageGrant(JsonNode entry, String label) {
        ObjectNode grant = RuleJson.JSON.createObjectNode().put("op", "inventory.grant").put("catalog", "consumables");
        grant.set("entry", entry);
        return grant.put("quantity", 1).put("tag", "foraged").put("max", 5).put("scope", "party").put("label", label);
    }

    private static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("-")) if (!word.isEmpty())
            out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return out.toString();
    }

    /**
     * A consumable's sheet action uses up one unit unless it keeps the item. With a portion pool, the unit is used up
     * only once the pool is empty after paying the cost, and the pool then refills for the next unit.
     */
    private static void consumableUnit(JsonNode action, String path, Problems p, Pools pools, ObjectNode rule) {
        if (action.has("keepItem") && !action.path("keepItem").isBoolean()) { p.add(path + "/keepItem", "Expected a boolean."); return; }
        if (action.path("keepItem").asBoolean(false)) return;
        if (!action.has("portions")) {
            ArrayNode costs = rule.has("costs") ? (ArrayNode) rule.get("costs") : rule.putArray("costs");
            costs.addObject().put("op", "inventory.consume").put("amount", 1);
            return;
        }
        String pool = action.path("portions").asText("");
        if (!pools.has(pool) || !"number".equals(pools.local().path(pool).path("kind").asText())) {
            p.add(path + "/portions", "Unknown portion pool: " + pool);
            return;
        }
        ObjectNode empty = RuleJson.JSON.createObjectNode();
        empty.putArray("eq").add(read("actor.pools." + pool + ".value")).add(0);
        ArrayNode steps = rule.has("do") ? (ArrayNode) rule.get("do") : rule.putArray("do");
        steps.addObject().put("op", "inventory.consume").put("amount", 1).set("if", empty.deepCopy());
        steps.addObject().put("op", "pool.gain").put("pool", pool).put("amount", Math.max(0, pools.local().path(pool).path("initial").asInt(0)))
                .set("if", empty);
    }

    /**
     * A one-die check: {@code success} runs the outcome on that face, {@code unless} runs it on any other face. The outcome
     * is {@code gain}/{@code mark} (one point), the legacy steps, or the steps of one chosen option.
     */
    private static void check(JsonNode legacy, String path, Problems p, ObjectNode rule, ArrayNode steps, ObjectNode effects, Pools pools, String target) {
        JsonNode check = legacy.get("check");
        p.onlyFields(check, path + "/check", Set.of("dice", "success", "unless", "gain", "mark"));
        var die = CHECK_DIE.matcher(check.path("dice").asText(""));
        if (!die.matches() || Integer.parseInt(die.group(1)) < 2) { p.add(path + "/check/dice", "Expected one die such as 1d6."); return; }
        int sides = Integer.parseInt(die.group(1));
        boolean success = check.has("success");
        if (success == check.has("unless")) { p.add(path + "/check", "Expected exactly one of success or unless."); return; }
        JsonNode face = check.get(success ? "success" : "unless");
        if (!face.isInt() || face.asInt() < 1 || face.asInt() > sides) { p.add(path + "/check", "Expected a face of the die."); return; }
        if (check.has("gain") && !success || check.has("mark") && success) { p.add(path + "/check", "Gains need success; marks need unless."); return; }
        steps.addObject().put("op", "dice.roll").put("count", 1).put("sides", sides).put("as", "check");
        ObjectNode hit = RuleJson.JSON.createObjectNode();
        hit.putArray(success ? "eq" : "ne").add(read("result.check")).add(face.asInt());
        ArrayNode outcome = RuleJson.JSON.createArrayNode();
        if (check.has("gain") || !success) {
            ObjectNode point = RuleJson.JSON.createObjectNode().put("op", success ? "gain" : "mark")
                    .put("resource", check.path(success ? "gain" : "mark").asText("stress")).put("amount", 1);
            step(point, path + "/check", p, outcome, effects, pools, target);
        }
        JsonNode list = legacy.path("steps");
        for (int i = 0; i < list.size(); i++) step(list.get(i), path + "/steps/" + i, p, outcome, effects, pools, target);
        JsonNode choices = legacy.path("choices");
        if (legacy.has("choices")) {
            if (!choices.isArray() || choices.isEmpty()) { p.add(path + "/choices", "Expected choices."); return; }
            ArrayNode options = rule.withArray("inputs").addObject().put("id", "choice").put("kind", "choice")
                    .put("label", "Choose the outcome").putArray("options");
            for (int c = 0; c < choices.size(); c++) {
                JsonNode choice = choices.get(c);
                String at = path + "/choices/" + c;
                p.onlyFields(choice, at, Set.of("id", "label", "steps"));
                String id = choice.path("id").asText();
                if (!ID.matcher(id).matches()) { p.add(at + "/id", "Invalid choice ID: " + id); continue; }
                options.addObject().put("id", id).put("label", escape(choice.path("label").asText(id)));
                ArrayNode chosen = RuleJson.JSON.createArrayNode();
                for (int i = 0; i < choice.path("steps").size(); i++) step(choice.path("steps").get(i), at + "/steps/" + i, p, chosen, effects, pools, target);
                ObjectNode picked = RuleJson.JSON.createObjectNode();
                picked.putArray("eq").add(read("input.choice")).add(id);
                chosen.forEach(converted -> guard((ObjectNode) converted, picked));
                outcome.addAll(chosen);
            }
        }
        if (outcome.isEmpty()) { p.add(path + "/check", "A check needs an outcome."); return; }
        outcome.forEach(converted -> guard((ObjectNode) converted, hit));
        steps.addAll(outcome);
    }

    private static void noChoices(JsonNode legacy, String path, Problems p) {
        if (legacy.has("choices")) p.add(path + "/choices", "Choices need a check.");
    }

    private static void guard(ObjectNode step, JsonNode condition) {
        if (!step.has("if")) { step.set("if", condition.deepCopy()); return; }
        ObjectNode both = RuleJson.JSON.createObjectNode();
        both.putArray("and").add(condition.deepCopy()).add(step.get("if"));
        step.set("if", both);
    }

    private static void checkReport(JsonNode legacy, ObjectNode ui) {
        if (!legacy.has("check") || ui.has("report")) return;
        ui.put("report", "Check " + legacy.path("check").path("dice").asText() + ": {result.check}.");
    }
    private static final Pattern SENT_ROLL = Pattern.compile("(?:(\\d{1,2})|@([A-Za-z_]+?))(?:d(\\d{1,4})(?:kh(\\d{1,2}))?)?");

    /** Legacy dice that need a positive Spellcast trait are only offered when the actor has one. */
    private static void sentRollConditions(JsonNode legacy, ArrayNode when) {
        if (!legacy.path("sendRoll").path("dice").asText("").contains("@spellcast")) return;
        when.addObject().put("exists", "actor.spellcastTrait.value");
        when.addObject().putArray("gte").add(read("actor.stats.spellcast.base")).add(1);
    }

    /** The sent roll total, ahead of any authored report; a legacy {@code {result}} placeholder names that total. */
    private static void sentRollReport(JsonNode legacy, ObjectNode ui) {
        if (!legacy.has("sendRoll")) return;
        String total = "{result.sentRoll.total}";
        String label = escape(legacy.path("sendRoll").path("label").asText("Roll"));
        String authored = ui.has("report") ? " " + ui.path("report").asText().replace("{{result}}", total) : "";
        ui.put("report", label + ": " + total + "." + authored);
    }

    /**
     * A standalone host roll such as a reaction or Evasion die: a fixed or computed number of dice (optionally keeping
     * the highest), a bare computed value, and an optional Evasion base. Computed counts never go below zero.
     */
    private static void sentRoll(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps) {
        p.onlyFields(legacy, path, Set.of("dice", "label", "base"));
        String label = legacy.path("label").asText("");
        if (label.isBlank()) { p.add(path + "/label", "Sent rolls need a label."); return; }
        if (legacy.has("base") && !"evasion".equals(legacy.path("base").asText())) {
            p.add(path + "/base", "Unsupported roll base: " + legacy.path("base").asText());
            return;
        }
        var matcher = SENT_ROLL.matcher(legacy.path("dice").asText("").trim());
        if (!matcher.matches() || matcher.group(1) != null && matcher.group(3) == null) {
            p.add(path + "/dice", "Expected dice such as 1d6, @tier, @total_proficiencyd6 or @Agilityd6kh1.");
            return;
        }
        JsonNode count;
        if (matcher.group(1) != null) count = RuleJson.JSON.getNodeFactory().numberNode(Integer.parseInt(matcher.group(1)));
        else {
            String token = matcher.group(2).toLowerCase(Locale.ROOT);
            String trait = trait(token);
            JsonNode value = trait != null ? read("actor.stats.trait." + trait) : computed("@" + token);
            if (value == null) { p.add(path + "/dice", "Unsupported computed dice count: @" + matcher.group(2)); return; }
            ObjectNode bounded = RuleJson.JSON.createObjectNode();
            bounded.putArray("max").add(0).add(value);
            count = bounded;
        }
        String id = slug(action.path("id").asText("action"));
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", slug(id + "-roll"));
        ArrayNode contributions = plan.putArray("contributions");
        if (legacy.has("base")) contributions.addObject().put("id", slug(id + "-roll-evasion")).put("kind", "flat")
                .put("role", "roll").put("sourceId", "core").put("label", "Evasion").set("value", read("actor.stats.evasion"));
        if (matcher.group(3) == null) {
            contributions.addObject().put("id", slug(id + "-roll-value")).put("kind", "flat").put("role", "roll")
                    .put("sourceId", "core").put("label", label).set("value", count);
        } else {
            int sides = Integer.parseInt(matcher.group(3));
            if (sides < 2 || sides > 1000 || count.isInt() && count.asInt() < 1) {
                p.add(path + "/dice", "Dice exceed the supported bounds.");
                return;
            }
            ObjectNode dice = contributions.addObject().put("id", slug(id + "-roll-dice")).put("kind", "dice").put("role", "roll")
                    .put("sourceId", "core").put("label", label);
            dice.set("count", count);
            dice.put("sides", sides);
            if (matcher.group(4) != null) {
                int keep = Integer.parseInt(matcher.group(4));
                if (keep < 1) { p.add(path + "/dice", "Keep at least one die."); return; }
                ObjectNode kept = RuleJson.JSON.createObjectNode();
                kept.putArray("min").add(keep).add(count.deepCopy());
                dice.set("keepHighest", count.isInt() ? RuleJson.JSON.getNodeFactory().numberNode(Math.min(keep, count.asInt())) : kept);
            }
        }
        plan.putObject("metadata").put("kind", "roll").put("label", label);
        ObjectNode planExpression = RuleJson.JSON.createObjectNode();
        planExpression.set("literal", plan);
        ObjectNode resolve = steps.addObject().put("op", "roll.resolve");
        resolve.set("plan", planExpression);
        resolve.put("as", "sentRoll");
    }
    /** Every die face of the finished structured roll, across all dice contributions. */
    private static ObjectNode rolledFaces() {
        ObjectNode dice = RuleJson.JSON.createObjectNode();
        dice.putArray("pluck").add(read("event.roll.contributions")).add("dice");
        ObjectNode flat = RuleJson.JSON.createObjectNode();
        flat.set("flatten", dice);
        ObjectNode faces = RuleJson.JSON.createObjectNode();
        faces.putArray("pluck").add(flat).add("face");
        return faces;
    }

    /**
     * A roll whose dice count is a chosen amount of a resource: an integer input bounded by what the resource can pay,
     * a cost of that amount, and N dice (plus an optional flat bonus per die). With a damage {@code type} it is a fixed
     * damage roll ({@code result.resourceDamage}); otherwise a plain roll ({@code result.resourceRoll}) that later roll
     * triggers can inspect.
     */
    private static void resourceRoll(JsonNode legacy, String path, JsonNode owner, Problems p, Pools pools, ObjectNode rule, ArrayNode steps) {
        p.onlyFields(legacy, path, Set.of("resource", "sides", "bonusPerDie", "type"));
        String resource = legacy.path("resource").asText();
        int sides = legacy.path("sides").asInt(0);
        if (!legacy.path("sides").isInt() || sides < 2 || sides > 1000) { p.add(path + "/sides", "Expected 2 to 1000 sides."); return; }
        if (legacy.has("bonusPerDie") && (!legacy.path("bonusPerDie").isInt() || legacy.path("bonusPerDie").asInt() < 0))
            { p.add(path + "/bonusPerDie", "Expected a nonnegative whole bonus."); return; }
        String type = legacy.path("type").asText(null);
        if (legacy.has("type") && !Set.of("physical", "magic").contains(type)) { p.add(path + "/type", "Expected physical or magic damage."); return; }
        if (rule.has("inputs")) { p.add(path, "A resource roll cannot be combined with another amount."); return; }
        String verb = TRACKS.containsKey(resource) ? "mark" : "spend";
        String noun = resource.isEmpty() ? resource : Character.toUpperCase(resource.charAt(0)) + resource.substring(1);
        selectAmount(RuleJson.JSON.createObjectNode().put("resource", resource).put("label", noun + " to " + verb), path + "/resource", p, pools, rule);
        if (!rule.has("inputs")) return;
        ObjectNode scratch = RuleJson.JSON.createObjectNode();
        costs(RuleJson.JSON.createObjectNode().put(resource, "@amount"), path + "/resource", p, pools, scratch);
        if (scratch.has("costs")) rule.withArray("costs").addAll((ArrayNode) scratch.get("costs"));
        String id = slug(owner.path("id").asText("action"));
        String label = escape(owner.path("label").asText("Roll"));
        String role = type == null ? "roll" : "damage";
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", slug(id + "-resource-roll"));
        ArrayNode contributions = plan.putArray("contributions");
        ObjectNode dice = contributions.addObject().put("id", slug(id + "-resource-dice")).put("kind", "dice").put("role", role)
                .put("sourceId", "core").put("label", label);
        dice.set("count", read("input.amount"));
        dice.put("sides", sides);
        int bonus = legacy.path("bonusPerDie").asInt(0);
        if (bonus > 0) {
            ObjectNode value = RuleJson.JSON.createObjectNode();
            value.putArray("multiply").add(read("input.amount")).add(bonus);
            contributions.addObject().put("id", slug(id + "-resource-bonus")).put("kind", "flat").put("role", role)
                    .put("sourceId", "core").put("label", label).set("value", value);
        }
        ObjectNode metadata = plan.putObject("metadata");
        if (type == null) metadata.put("kind", "roll").put("label", label);
        else metadata.put("kind", "damage").put("damageType", type).put("fixedDice", true);
        ObjectNode literal = RuleJson.JSON.createObjectNode();
        literal.set("literal", plan);
        ObjectNode resolve = steps.addObject().put("op", "roll.resolve");
        resolve.set("plan", literal);
        resolve.put("as", type == null ? "resourceRoll" : "resourceDamage");
    }

    /** The chosen amount and the resource roll total, ahead of any authored report. */
    private static void resourceRollReport(JsonNode legacy, ObjectNode ui) {
        JsonNode spec = legacy.path("resourceRoll");
        if (!spec.isObject()) return;
        String resource = spec.path("resource").asText();
        String dice = "{input.amount}d" + spec.path("sides").asInt();
        String paid = (TRACKS.containsKey(resource) ? "Marked" : "Spent") + " {input.amount} " + resource;
        String text = spec.has("type")
                ? paid + ": " + dice + " " + spec.path("type").asText() + " damage, total {result.resourceDamage.total}."
                : paid + ": " + dice + " total {result.resourceRoll.total}.";
        ui.put("report", text + (ui.has("report") ? " " + ui.path("report").asText() : ""));
    }

    /** How much of a named resource the actor can currently pay: Hope, free track boxes or a pool's value; null if unknown. */
    private static JsonNode available(String resource, Pools pools) {
        if ("hope".equals(resource)) return read("actor.hope.value");
        if ("@item".equals(resource)) return read("source.quantity");
        if (TRACKS.containsKey(resource)) {
            ObjectNode free = RuleJson.JSON.createObjectNode();
            free.putArray("subtract").add(read(TRACKS.get(resource) + ".capacity")).add(read(TRACKS.get(resource) + ".marked"));
            return free;
        }
        if (pools.has(resource)) return poolValue(pools, resource);
        return "gold".equals(resource) ? read("actor.gold") : null;
    }

    /** A player-chosen whole amount, bounded by what the named resource can currently pay; {@code @amount} reads it. */
    /** {@code spendHopeAmount} is a chosen amount of Hope that is paid when the trigger is used. */
    private static JsonNode withHopeAmount(JsonNode trigger) {
        ObjectNode copy = trigger.deepCopy();
        copy.remove("spendHopeAmount");
        copy.putObject("selectAmount").put("resource", "hope").put("label", "Hope to spend");
        return copy;
    }

    private static void selectAmount(JsonNode legacy, String path, Problems p, Pools pools, ObjectNode rule) {
        p.onlyFields(legacy, path, Set.of("resource", "label", "min", "heal", "store", "base", "addToRoll"));
        String resource = legacy.path("resource").asText();
        boolean heal = legacy.path("heal").asBoolean(false);
        if (legacy.has("heal") && !legacy.path("heal").isBoolean()) p.add(path + "/heal", "Expected a boolean.");
        if (legacy.has("min") && (!legacy.path("min").isInt() || legacy.path("min").asInt() < 0))
            p.add(path + "/min", "Expected a nonnegative whole minimum.");
        JsonNode max;
        if (heal) max = RuleJson.JSON.getNodeFactory().numberNode(1_000_000);
        else max = available(resource, pools);
        if (max == null) { p.add(path + "/resource", "Amounts need a known resource: " + resource); return; }
        ObjectNode input = rule.withArray("inputs").addObject().put("id", "amount").put("kind", "integer")
                .put("label", escape(legacy.path("label").asText("Amount"))).put("min", legacy.path("min").asInt(1));
        input.set("max", max);
        ObjectNode enough = RuleJson.JSON.createObjectNode();
        enough.putArray("gte").add(max.deepCopy()).add(legacy.path("min").asInt(1));
        if (!rule.has("when")) rule.set("when", enough);
        else {
            ObjectNode both = RuleJson.JSON.createObjectNode();
            both.putArray("and").add(rule.get("when")).add(enough);
            rule.set("when", both);
        }
    }

    /**
     * The legacy chosen amount is paid from its resource (added to any fixed cost of that resource) unless it counts
     * healed Hit Points; an explicit {@code "@amount"} cost already pays it.
     */
    private static void amountCost(JsonNode owner, String path, Problems p, Pools pools, ObjectNode rule) {
        JsonNode spec = owner.path("selectAmount");
        if (!rule.has("inputs") || spec.path("heal").asBoolean(false)) return;
        String resource = spec.path("resource").asText();
        if ("@amount".equals(owner.path("cost").path(resource).asText())) return;
        ObjectNode scratch = RuleJson.JSON.createObjectNode();
        costs(RuleJson.JSON.createObjectNode().put(resource, "@amount"), path + "/resource", p, pools, scratch);
        if (scratch.has("costs")) rule.withArray("costs").addAll((ArrayNode) scratch.get("costs"));
    }

    /** {@code store}: the chosen amount (plus {@code base}) replaces the value of a declared pool, before other steps. */
    private static void amountStore(JsonNode spec, String path, Problems p, Pools pools, ArrayNode steps, ObjectNode effects) {
        if (!spec.has("store")) return;
        String store = spec.path("store").asText();
        if (!pools.has(store)) { p.add(path + "/store", "Unknown store pool: " + store); return; }
        if (spec.has("base") && (!spec.path("base").isInt() || spec.path("base").asInt() < 0)) {
            p.add(path + "/base", "Expected a nonnegative whole base."); return;
        }
        step(RuleJson.JSON.createObjectNode().put("op", "clear").put("resource", store).put("amount", "all"), path + "/store", p, steps, effects, pools);
        JsonNode amount = read("input.amount");
        if (spec.path("base").asInt(0) > 0) {
            ObjectNode sum = RuleJson.JSON.createObjectNode();
            sum.putArray("add").add(amount).add(spec.path("base").asInt());
            amount = sum;
        }
        steps.addObject().put("op", "pool.gain").put("pool", store).set("amount", amount);
    }

    /** {@code heal}: the chosen amount of marked Hit Points is cleared after the other steps. */
    private static void amountHeal(JsonNode spec, String path, Problems p, ArrayNode steps) {
        if (!spec.path("heal").asBoolean(false)) return;
        if (!"hp".equals(spec.path("resource").asText())) { p.add(path + "/resource", "Only marked Hit Points can be healed."); return; }
        steps.addObject().put("op", "track.clear").put("track", TRACKS.get("hp")).set("amount", read("input.amount"));
    }

    private static final Set<String> PATRON_MODES = Set.of("heal", "ire", "vengeance");

    /** Patron dice are d6, or d8 from level 5; the host evaluates the die size from the sheet's level. */
    private static JsonNode patronSides() {
        ObjectNode ratio = RuleJson.JSON.createObjectNode();
        ratio.putArray("divide").add(read("actor.level")).add(5);
        ObjectNode whole = RuleJson.JSON.createObjectNode().set("floor", ratio);
        ObjectNode tier = RuleJson.JSON.createObjectNode();
        tier.putArray("min").add(1).add(whole);
        ObjectNode doubled = RuleJson.JSON.createObjectNode();
        doubled.putArray("multiply").add(2).add(tier);
        ObjectNode sides = RuleJson.JSON.createObjectNode();
        sides.putArray("add").add(6).add(doubled);
        return sides;
    }

    /**
     * Rolls Patron Dice: {@code heal} clears marked Hit Points per 4+, {@code ire} reports the highest die and
     * {@code vengeance} reports one marked Hit Point per 4+. The dice count is the chosen Favor (heal, ire), the chosen
     * marked Hit Points (vengeance action) or the Hit Points just marked (vengeance damage option).
     */
    private static String patronDice(String mode, JsonNode count, String path, Problems p, ArrayNode steps, String countText) {
        if (!PATRON_MODES.contains(mode)) { p.add(path, "Unsupported Patron Dice action: " + mode); return null; }
        ObjectNode roll = steps.addObject().put("op", "ire".equals(mode) ? "dice.highest" : "dice.hits").put("as", "patron");
        roll.set("count", count);
        roll.set("sides", patronSides());
        if (!"ire".equals(mode)) roll.put("atLeast", 4);
        String rolled = "Rolled " + countText + " Patron Dice. ";
        return switch (mode) {
            case "heal" -> {
                steps.addObject().put("op", "track.clear").put("track", "actor.hp").put("as", "healed").set("amount", read("result.patron"));
                yield "Spent {input.amount} Favor. " + rolled + "{result.patron} results of 4+: cleared {result.healed} HP.";
            }
            case "ire" -> "Spent {input.amount} Favor. " + rolled + "Highest result: {result.patron}. Target that many creatures within Close range; each target marks 1 HP.";
            default -> "Spent 1 Favor. " + rolled + "For " + countText + " HP marked from the attack, the attacker marks {result.patron} HP (one per 4+).";
        };
    }

    private static String patronAction(JsonNode action, String path, Problems p, Pools pools, ObjectNode rule, ArrayNode steps) {
        String mode = action.path("patronDice").asText();
        if (!PATRON_MODES.contains(mode)) { p.add(path + "/patronDice", "Unsupported Patron Dice action: " + mode); return null; }
        if (action.has("selectAmount") || action.has("steps")) { p.add(path + "/patronDice", "Patron Dice actions cannot add their own steps or amounts."); return null; }
        if ("vengeance".equals(mode)) {
            rule.withArray("inputs").addObject().put("id", "amount").put("kind", "integer").put("label", "HP marked").put("min", 1)
                    .set("max", read("actor.hp.capacity"));
            return patronDice(mode, read("input.amount"), path + "/patronDice", p, steps, "{input.amount}");
        }
        if (!pools.has("favor")) { p.add(path + "/patronDice", "Patron Dice need a Favor resource."); return null; }
        JsonNode spec = RuleJson.JSON.createObjectNode().put("resource", "favor").put("label", "Favor to spend");
        selectAmount(spec, path + "/patronDice", p, pools, rule);
        amountCost(RuleJson.JSON.createObjectNode().set("selectAmount", spec), path + "/patronDice", p, pools, rule);
        return patronDice(mode, read("input.amount"), path + "/patronDice", p, steps, "{input.amount}");
    }

    private static void actionDamage(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps) {
        actionDamage(legacy, path, action, p, steps, null, "actionDamage");
    }

    /**
     * A separate damage roll. With {@code critical}, a critical success adds the maximum result of the damage dice as a
     * flat contribution, matching the Daggerheart critical damage rule.
     */
    private static void actionDamage(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps, JsonNode critical, String as) {
        p.onlyFields(legacy, path, Set.of("dice", "type", "fixed", "count", COUNT_READ));
        String countRead = legacy.path(COUNT_READ).asText(null);
        String diceText = legacy.path("dice").asText("");
        String poolCount = null;
        var pooled = POOL_DICE.matcher(diceText);
        if (pooled.matches()) {
            poolCount = pooled.group(1);
            diceText = pooled.group(2);
        }
        var matcher = ACTION_DAMAGE.matcher(diceText);
        if (!matcher.matches()) {
            p.add(path + "/dice", "Expected one damage die expression such as d8, 2d10+3 or d8-1.");
            return;
        }
        String countText = matcher.group(1);
        String sidesText = matcher.group(2);
        if (countText.length() > 4 || sidesText.length() > 4) {
            p.add(path + "/dice", "Damage dice exceed the supported bounds.");
            return;
        }
        int fixedCount = countText.isEmpty() ? 1 : Integer.parseInt(countText);
        int sides = Integer.parseInt(sidesText);
        if (fixedCount < 1 || fixedCount > 1024 || sides < 2 || sides > 1000) {
            p.add(path + "/dice", "Damage dice exceed the supported bounds.");
            return;
        }
        boolean fixed = false;
        if (legacy.has("fixed")) {
            if (!legacy.path("fixed").isBoolean()) p.add(path + "/fixed", "Expected a boolean.");
            else fixed = legacy.path("fixed").asBoolean();
        }
        String countSelector = legacy.path("count").asText("");
        boolean spellcastCount = "spellcast".equals(countSelector);
        if (legacy.has("count") && !spellcastCount) {
            p.add(path + "/count", "Unsupported damage dice count selector: " + countSelector);
            return;
        }
        if (legacy.has("type") && !legacy.path("type").isTextual()) {
            p.add(path + "/type", "Expected physical or magic damage.");
            return;
        }
        String type = legacy.path("type").asText("physical");
        if (!Set.of("physical", "magic").contains(type)) {
            p.add(path + "/type", "Expected physical or magic damage.");
            return;
        }
        String bonusText = matcher.group(4);
        if (bonusText != null && bonusText.length() > 7) {
            p.add(path + "/dice", "Damage bonus exceeds the supported bounds.");
            return;
        }

        String id = slug(action.path("id").asText("action"));
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", slug(id + "-damage"));
        ArrayNode contributions = plan.putArray("contributions");
        ObjectNode dice = contributions.addObject().put("id", slug(id + "-damage-dice")).put("kind", "dice")
                .put("role", "damage").put("sourceId", "core").put("label", action.path("label").asText("Damage"));
        if (countRead != null) dice.set("count", read(countRead));
        else if (spellcastCount) dice.set("count", spellcastDiceCount(fixedCount));
        else if (poolCount != null) dice.set("count", read("actor.pools." + poolCount + ".value"));
        else if (fixed) dice.put("count", fixedCount);
        else dice.set("count", read("actor.proficiency.value"));
        dice.put("sides", sides);
        if (matcher.group(3) != null) {
            int bonus = Integer.parseInt(bonusText);
            if (bonus > 1_000_000) {
                p.add(path + "/dice", "Damage bonus exceeds the supported bounds.");
                return;
            }
            if ("-".equals(matcher.group(3))) bonus = -bonus;
            if (bonus != 0) contributions.addObject().put("id", slug(id + "-damage-flat")).put("kind", "flat")
                    .put("role", "damage").put("sourceId", "core").put("label", action.path("label").asText("Damage")).put("value", bonus);
        }
        if (critical != null) {
            ObjectNode maximum = RuleJson.JSON.createObjectNode();
            maximum.putArray("multiply").add(dice.get("count").deepCopy()).add(sides);
            ObjectNode value = RuleJson.JSON.createObjectNode();
            value.putArray("if").add(critical.deepCopy()).add(maximum).add(0);
            contributions.addObject().put("id", slug(id + "-damage-critical")).put("kind", "flat")
                    .put("role", "damage").put("sourceId", "core").put("label", "Critical").set("value", value);
        }
        ObjectNode metadata = plan.putObject("metadata");
        metadata.put("kind", "damage").put("damageType", type).put("fixedDice", fixed);
        ObjectNode planExpression = RuleJson.JSON.createObjectNode();
        planExpression.set("literal", plan);
        ObjectNode resolve = steps.addObject().put("op", "roll.resolve");
        resolve.set("plan", planExpression);
        resolve.put("as", as);
    }

    private static ObjectNode spellcastDiceCount(int writtenCount) {
        ObjectNode count = RuleJson.JSON.createObjectNode();
        count.putArray("max").add(0).add(read("actor.spellcastTrait.value"));
        if (writtenCount == 1) return count;
        return RuleJson.JSON.createObjectNode().set("multiply", RuleJson.JSON.createArrayNode().add(count).add(writtenCount));
    }

    private static void actionRoll(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps) {
        p.onlyFields(legacy, path, Set.of("trait", "attack", "reaction", "difficulty"));
        String trait = legacy.path("trait").asText("");
        boolean highestTrait = "highest".equals(trait);
        boolean spellcastTrait = "spellcast".equals(trait);
        if (!trait.isEmpty() && !TRAITS.contains(trait) && !highestTrait && !spellcastTrait) {
            p.add(path + "/trait", "Unsupported trait selector; only fixed traits, highest and spellcast are currently convertible.");
            return;
        }
        for (String flag : List.of("attack", "reaction"))
            if (legacy.has(flag) && !legacy.path(flag).isBoolean()) p.add(path + "/" + flag, "Expected a boolean.");
        if (legacy.has("difficulty") && (!legacy.path("difficulty").isIntegralNumber() || legacy.path("difficulty").asInt() < 0))
            p.add(path + "/difficulty", "Expected a nonnegative whole difficulty.");

        String id = slug(action.path("id").asText("action"));
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", slug(id + "-roll"));
        ArrayNode contributions = plan.putArray("contributions");
        contributions.addObject().put("id", slug(id + "-hope")).put("kind", "dice").put("role", "hope")
                .put("sourceId", "core").put("label", "Hope Die").put("count", 1).put("sides", 12);
        contributions.addObject().put("id", slug(id + "-fear")).put("kind", "dice").put("role", "fear")
                .put("sourceId", "core").put("label", "Fear Die").put("count", 1).put("sides", 12);
        if (!trait.isEmpty()) {
            ObjectNode modifier = contributions.addObject().put("id", slug(id + "-trait")).put("kind", "flat")
                    .put("role", "modifier").put("sourceId", "core")
                    .put("label", spellcastTrait ? "Spellcast Trait" : trait);
            if (highestTrait) {
                ArrayNode values = RuleJson.JSON.createArrayNode();
                for (String candidate : TRAITS) values.add(read("actor.traits." + candidate));
                modifier.putObject("value").set("max", values);
            } else modifier.set("value", read(spellcastTrait ? "actor.spellcastTrait.value" : "actor.traits." + trait));
        }
        ObjectNode metadata = plan.putObject("metadata");
        metadata.put("kind", legacy.path("reaction").asBoolean(false) ? "reaction" : "action");
        if (!trait.isEmpty()) metadata.put("trait", trait);
        metadata.put("attack", legacy.path("attack").asBoolean(false));
        if (legacy.has("difficulty")) metadata.put("difficulty", legacy.path("difficulty").asInt());
        ObjectNode planExpression = RuleJson.JSON.createObjectNode();
        planExpression.set("literal", plan);
        ObjectNode resolve = steps.addObject().put("op", "roll.resolve");
        resolve.set("plan", planExpression);
        resolve.put("as", "actionRoll");
    }

    private static void step(JsonNode step, String path, Problems p, ArrayNode out, ObjectNode effects, Pools pools) {
        step(step, path, p, out, effects, pools, "actor");
    }

    private static void step(JsonNode step, String path, Problems p, ArrayNode out, ObjectNode effects, Pools pools,
                             String operationTarget) {
        String op = step.path("op").asText();
        if ("effect".equals(op)) {
            p.onlyFields(step, path, Set.of("op", "effect"));
            String id = declareEffect(step.path("effect"), path + "/effect", p, effects);
            if (id != null) out.addObject().put("op", "effect.start").put("effect", id).put("target", operationTarget);
            return;
        }
        p.onlyFields(step, path, Set.of("op", "resource", "amount"));
        String resource = step.path("resource").asText();
        JsonNode amount = step.path("amount");
        boolean clearAllTrack = "all".equals(amount.asText()) && "clear".equals(op) && TRACKS.containsKey(resource);
        boolean clearAllPool = "all".equals(amount.asText()) && "clear".equals(op) && pools.has(resource);
        boolean markAllTrack = "all".equals(amount.asText()) && "mark".equals(op) && TRACKS.containsKey(resource);
        boolean clearAllScars = "all".equals(amount.asText()) && "clear".equals(op)
                && "scars".equals(resource) && !pools.has(resource);
        JsonNode value = clearAllTrack || clearAllPool || markAllTrack ? null : amount(amount);
        if (clearAllScars) value = read(operationTarget + ".scars");
        var rolled = amount.isTextual() ? ACTION_DAMAGE.matcher(amount.asText().trim()) : null;
        if (value == null && rolled != null && rolled.matches() && rolled.group(1).length() <= 2 && rolled.group(2).length() <= 3
                && (rolled.group(4) == null || rolled.group(4).length() <= 6)) {
            int count = rolled.group(1).isEmpty() ? 1 : Integer.parseInt(rolled.group(1));
            int sides = Integer.parseInt(rolled.group(2));
            if (count >= 1 && sides >= 2) {
                String slot = "amount" + (out.size() + 1);
                out.addObject().put("op", "dice.roll").put("count", count).put("sides", sides).put("as", slot);
                ObjectNode total = read("result." + slot);
                if (rolled.group(3) != null) {
                    int bonus = Integer.parseInt(rolled.group(4)) * ("-".equals(rolled.group(3)) ? -1 : 1);
                    ObjectNode sum = RuleJson.JSON.createObjectNode();
                    sum.putArray("add").add(total).add(bonus);
                    ObjectNode bounded = RuleJson.JSON.createObjectNode();
                    bounded.putArray("max").add(0).add(sum);
                    value = bonus < 0 ? bounded : sum;
                } else value = total;
            }
        }
        if (value == null && !clearAllTrack && !clearAllPool && !markAllTrack) {
            p.add(path + "/amount", "Computed amounts need an explicit expression: " + amount);
            return;
        }
        switch (op) {
            case "clear", "mark" -> {
                if (TRACKS.containsKey(resource)) {
                    String track = TRACKS.get(resource).replaceFirst("^actor\\.", operationTarget + ".");
                    ObjectNode converted = out.addObject().put("op", "track." + op).put("track", track);
                    if (value != null) converted.set("amount", value);
                    else if (markAllTrack) converted.putObject("amount").putArray("subtract").add(read(track + ".capacity")).add(read(track + ".marked"));
                    else converted.putObject("amount").put("read", track + ".marked");
                } else if ("scars".equals(resource) && !pools.has(resource)) {
                    scarCapacity(out, operationTarget, value, "mark".equals(op));
                } else if ("clear".equals(op) && clearAllPool) {
                    ObjectNode converted = out.addObject().put("op", "pool.spend").put("pool", resource);
                    if ("recipient".equals(operationTarget)) converted.put("owner", "recipient");
                    converted.set("amount", "recipient".equals(operationTarget) ? read("recipient.pools." + resource + ".value") : poolValue(pools, resource));
                } else p.add(path + "/resource", "Unsupported track: " + resource);
            }
            case "gain" -> {
                if ("hope".equals(resource)) out.addObject().put("op", "resource.gain").put("pool", operationTarget + ".hope").set("amount", value);
                else if ("scars".equals(resource) && !pools.has(resource))
                    scarCapacity(out, operationTarget, value, true);
                else if (pools.has(resource)) {
                    ObjectNode converted = out.addObject().put("op", "pool.gain").put("pool", resource);
                    if ("recipient".equals(operationTarget)) converted.put("owner", "recipient");
                    converted.set("amount", value);
                }
                else p.add(path + "/resource", "Gaining needs a declared pool: " + resource);
            }
            default -> p.add(path + "/op", "Unsupported step: " + op);
        }
    }

    private static void scarCapacity(ArrayNode out, String target, JsonNode amount, boolean reserve) {
        out.addObject().put("op", "track.capacity").put("track", target + ".hope")
                .put("counter", target + ".scars").put("direction", reserve ? "reserve" : "release").set("amount", amount);
    }

    /** A token effect ({@code tokenResource}) ends as soon as its pool of tokens is empty. */
    private static void tokenEnds(ObjectNode effects, String path, Problems p, Pools pools) {
        effects.fields().forEachRemaining(entry -> {
            if (!(entry.getValue() instanceof ObjectNode effect) || !effect.has("tokenResource")) return;
            String pool = effect.remove("tokenResource").asText();
            if (!pools.has(pool)) { p.add(path + "/tokenResource", "Unknown token resource: " + pool); return; }
            if (effect.has("while")) { p.add(path + "/tokenResource", "A conditional effect cannot also end when its tokens run out."); return; }
            effect.putObject("endWhen").putArray("lte").add(poolValue(pools, pool)).add(0);
        });
    }

    private static String declareEffect(JsonNode effect, String path, Problems p, ObjectNode effects) {
        p.onlyFields(effect, path, EFFECT_FIELDS);
        String name = effect.path("name").asText("");
        if (name.isBlank()) { p.add(path + "/name", "Effects need a name."); return null; }
        Set<String> used = new HashSet<>();
        effects.fieldNames().forEachRemaining(used::add);
        String id = unique(slug(name), used);
        ObjectNode declared = effects.putObject(id);
        declared.put("label", escape(name));
        if (effect.has("text")) declared.put("text", escape(effect.path("text").asText()));
        String kind = effect.path("kind").asText("buff");
        if (!Set.of("buff", "debuff").contains(kind)) p.add(path + "/kind", "Expected buff or debuff.");
        declared.put("kind", kind);
        String until = effect.path("until").asText("manual");
        if (!"manual".equals(until)) {
            List<String> resets = RESETS.get(until);
            if (resets == null) p.add(path + "/until", "Unsupported effect duration: " + until);
            else resets.forEach(declared.putArray("until")::add);
        }
        if (effect.has("group")) {
            String group = slug(effect.path("group").asText(""));
            if (!effect.path("group").isTextual() || group.equals("item")) p.add(path + "/group", "Expected a group name.");
            else declared.put("exclusiveGroup", group);
        }
        if (effect.has("unique")) {
            if (!effect.path("unique").isBoolean()) p.add(path + "/unique", "Expected a boolean.");
            else if (effect.path("unique").asBoolean()) declared.put("stacking", "replaceAnySource");
        }
        if (effect.has("exclusiveSource")) {
            if (!effect.path("exclusiveSource").isBoolean()) p.add(path + "/exclusiveSource", "Expected a boolean.");
            else if (effect.path("exclusiveSource").asBoolean()) declared.put("singleHolder", true);
        }
        if (effect.has("tokenResource")) {
            if (!effect.path("tokenResource").isTextual() || effect.path("tokenResource").asText().isBlank())
                p.add(path + "/tokenResource", "Expected a resource ID.");
            else declared.put("tokenResource", effect.path("tokenResource").asText());
        }
        if (effect.has("lifeWard")) {
            if (!effect.path("lifeWard").asBoolean(false)) p.add(path + "/lifeWard", "Expected true.");
            else p.nested().add(new Nested("lifeWard", RuleJson.JSON.createObjectNode().put("effect", name), path + "/lifeWard"));
        }
        if (effect.has("endOnMarkedHp")) {
            if (!effect.path("endOnMarkedHp").asBoolean(false)) p.add(path + "/endOnMarkedHp", "Expected true.");
            else p.nested().add(new Nested("endOnDamage", RuleJson.JSON.createObjectNode().put("effect", name).put("field", "hitPoints")
                    .put("severity", 1), path + "/endOnMarkedHp"));
        }
        if (effect.has("endOnDealtDamage")) {
            if (!effect.path("endOnDealtDamage").asBoolean(false)) p.add(path + "/endOnDealtDamage", "Expected true.");
            else p.nested().add(new Nested("endOnDealtDamage", RuleJson.JSON.createObjectNode().put("effect", name), path + "/endOnDealtDamage"));
        }
        // Legacy attacks and spellcasts are also action rolls; reactions are not.
        String[][] rollEnds = {{"endOnAttack", "attack"}, {"endOnAction", "action"}};
        for (String[] end : rollEnds) {
            if (!effect.has(end[0])) continue;
            if (!effect.path(end[0]).asBoolean(false)) { p.add(path + "/" + end[0], "Expected true."); continue; }
            ObjectNode ending = RuleJson.JSON.createObjectNode().put("effect", name).put("on", end[1]);
            ArrayNode kinds = ending.putArray("kinds").add("attack");
            if ("action".equals(end[1])) kinds.add("action").add("spellcast");
            p.nested().add(new Nested("endOnDealtDamage", ending, path + "/" + end[0]));
        }
        if (effect.has("maintainOnAction")) {
            String maintain = effect.path("maintainOnAction").asText("");
            boolean paid = false;
            for (JsonNode option : effect.path("rollOptions"))
                paid |= maintain.equals(option.path("id").asText()) && "action".equals(option.path("roll").asText()) && option.path("cost").path("hope").isInt();
            if (!paid) p.add(path + "/maintainOnAction", "Expected the ID of an action roll option that costs Hope.");
            else p.nested().add(new Nested("maintainEnd", RuleJson.JSON.createObjectNode().put("effect", name), path + "/maintainOnAction"));
        }
        if (effect.has("endOnDamage")) {
            JsonNode severity = effect.get("endOnDamage");
            if (!severity.isInt() || severity.asInt() < 1 || severity.asInt() > 4) p.add(path + "/endOnDamage", "Expected a severity from 1 to 4.");
            else p.nested().add(new Nested("endOnDamage", RuleJson.JSON.createObjectNode().put("effect", name).put("severity", severity.asInt()), path + "/endOnDamage"));
        }
        // Legacy "source severe" excluded effects granted by another actor; converted effects always belong to their starter.
        String[][] ends = {{"endOnSourceSevere", "severity", "3"}, {"endAfterIncomingDamage", "incoming", "1"}};
        for (String[] end : ends) {
            if (!effect.has(end[0])) continue;
            if (!effect.path(end[0]).asBoolean(false)) { p.add(path + "/" + end[0], "Expected true."); continue; }
            if (effect.has("endOnDamage") || effect.has("endOnSourceSevere") && effect.has("endAfterIncomingDamage")) {
                p.add(path + "/" + end[0], "An effect can declare only one damage ending."); continue;
            }
            p.nested().add(new Nested("endOnDamage", RuleJson.JSON.createObjectNode().put("effect", name).put("field", end[1])
                    .put("severity", Integer.parseInt(end[2])), path + "/" + end[0]));
        }
        if (effect.has("damage")) {
            ObjectNode granted = RuleJson.JSON.createObjectNode().put("effect", name);
            granted.set("damage", effect.get("damage").deepCopy());
            p.nested().add(new Nested("effectDamage", granted, path + "/damage"));
        }
        for (String family : FAMILIES) {
            JsonNode list = effect.path(family);
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).isObject()) { p.add(path + "/" + family + "/" + i, "Expected an object."); continue; }
                ObjectNode granted = ((ObjectNode) list.get(i)).deepCopy();
                if (!granted.has("requiresEffect")) granted.put("requiresEffect", name);
                p.nested().add(new Nested(family, granted, path + "/" + family + "/" + i));
            }
        }
        if (effect.has("rest")) {
            if (!effect.path("rest").isObject()) p.add(path + "/rest", "Expected a rest object.");
            else {
                ObjectNode granted = ((ObjectNode) effect.get("rest")).deepCopy();
                if (!granted.has("requiresEffect")) granted.put("requiresEffect", name);
                p.nested().add(new Nested("rest", granted, path + "/rest"));
            }
        }
        if (effect.has("consumeLongRestMove")) {
            if (!effect.path("consumeLongRestMove").isBoolean()) p.add(path + "/consumeLongRestMove", "Expected a boolean.");
            else if (effect.path("consumeLongRestMove").asBoolean())
                p.nested().add(new Nested("consumeLongRestMove", RuleJson.JSON.createObjectNode().put("effect", name), path + "/consumeLongRestMove"));
        }
        if (effect.has("modifiers")) {
            ArrayNode modifiers = RuleJson.JSON.createArrayNode();
            JsonNode list = effect.get("modifiers");
            boolean allConsumed = true;
            for (JsonNode modifier : list) allConsumed &= modifier.path("consume").asBoolean(false);
            for (int i = 0; i < list.size(); i++) {
                JsonNode modifier = list.get(i);
                String roll = modifier.path("roll").asText(null);
                boolean consume = modifier.path("consume").asBoolean(false);
                // Roll bonuses with dice, trait filters or a single use become automatic roll options granted by the effect.
                if (roll != null && (consume || modifier.has("dice") || !ROLLS.contains(roll))) {
                    p.onlyFields(modifier, path + "/modifiers/" + i, Set.of("roll", "value", "dice", "consume"));
                    if (consume && !allConsumed) { p.add(path + "/modifiers/" + i + "/consume", "A used-up roll bonus ends its effect, so every modifier of that effect must be used up together."); continue; }
                    ObjectNode option = RuleJson.JSON.createObjectNode().put("id", "bonus-" + i).put("label", name).put("roll", roll).put("required", true);
                    if (modifier.has("value")) option.set("value", modifier.get("value").deepCopy());
                    if (modifier.has("dice")) option.set("dice", modifier.get("dice").deepCopy());
                    option.put("requiresEffect", name);
                    if (consume) option.put("endEffect", name);
                    p.nested().add(new Nested("rollOptions", option, path + "/modifiers/" + i));
                    continue;
                }
                modifier(modifier, path + "/modifiers/" + i, p, modifiers, false);
            }
            declared.set("modifiers", modifiers);
        }
        return id;
    }

    /** Positive whole numbers, or a computed legacy value that is never negative. */
    private static JsonNode amount(JsonNode legacy) {
        if (legacy.isInt()) return legacy.asInt() > 0 ? legacy : null;
        if (!legacy.isTextual() || legacy.asText().trim().startsWith("-")) return null;
        JsonNode value = computed(legacy.asText());
        if (value == null) return null;
        ObjectNode bounded = RuleJson.JSON.createObjectNode();
        bounded.putArray("max").add(0).add(value);
        return value.has("read") && NONNEGATIVE.contains(value.path("read").asText()) ? value : bounded;
    }

    /** Whole numbers, or a computed legacy value that may be negated with a leading minus. */
    private static JsonNode signed(JsonNode legacy) {
        if (legacy.isInt()) return legacy;
        return legacy.isTextual() ? computed(legacy.asText()) : null;
    }

    private static final Set<String> NONNEGATIVE = Set.of("actor.proficiency.value", "actor.level", "actor.hope.value", "input.amount",
            "actor.stats.tier", "actor.stats.proficiency", "actor.stats.armorScore", "actor.stats.armorAvailable", "event.table.fear");

    /**
     * Maps a legacy {@code @token} to the projected fact it names. Trait tokens read the base modifier; {@code total_}
     * tokens read the derived statistic including every active modifier.
     */
    private static JsonNode computed(String text) {
        var matcher = Pattern.compile("([+-]?)@([A-Za-z][A-Za-z0-9_-]*)").matcher(text.trim());
        if (!matcher.matches()) return null;
        String key = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        JsonNode value = switch (key) {
            case "tier" -> read("actor.stats.tier");
            case "level" -> read("actor.level");
            case "hope" -> read("actor.hope.value");
            case "fear" -> read("event.table.fear");
            case "amount" -> read("input.amount");
            case "sourcepool_protection" -> read("sourcePools.protection.value");
            case "proficiency" -> read("actor.proficiency.value");
            case "total_proficiency" -> read("actor.stats.proficiency");
            case "armor_available" -> read("actor.stats.armorAvailable");
            case "armor_score", "armorscore" -> read("actor.stats.armorScore");
            case "spellcast" -> read("actor.stats.spellcast.base");
            case "total_spellcast" -> read("actor.stats.spellcast.total");
            default -> {
                String trait = trait(key.startsWith("total_") ? key.substring(6) : key);
                if (trait == null) yield null;
                if (key.startsWith("total_")) yield read("actor.stats.trait." + trait);
                ObjectNode base = RuleJson.JSON.createObjectNode();
                base.putArray("if").add(RuleJson.JSON.createObjectNode().put("exists", "actor.traits." + trait))
                        .add(read("actor.traits." + trait)).add(0);
                yield base;
            }
        };
        if (value == null || !"-".equals(matcher.group(1))) return value;
        return RuleJson.JSON.createObjectNode().set("negate", value);
    }

    private static String trait(String lower) {
        for (String trait : TRAITS) if (trait.toLowerCase(java.util.Locale.ROOT).equals(lower)) return trait;
        return null;
    }

    /** Passive (source) modifiers may carry legacy conditions as a {@code when}; effect modifiers are frozen and unconditional. */
    private static void modifier(JsonNode modifier, String path, Problems p, ArrayNode out, boolean passive) {
        if (!passive && modifier.has("traitValue")) {
            p.add(path + "/traitValue", "Dynamic trait-derived effect modifiers cannot be converted to frozen values.");
            return;
        }
        if (passive && modifier.has("weapon")) {
            // A roll bonus for the equipped weapon in a slot (not rolls with this source) becomes a required, slot-filtered roll option.
            p.onlyFields(modifier, path, Set.of("roll", "value", "weapon"));
            if (!modifier.has("roll") || !Set.of("primary", "secondary").contains(modifier.path("weapon").asText())) {
                p.add(path + "/weapon", "Slot-filtered modifiers need a roll and a primary or secondary slot.");
                return;
            }
            ObjectNode option = RuleJson.JSON.createObjectNode().put("id", "slot-bonus").put("label", "slot bonus")
                    .put("roll", modifier.path("roll").asText()).put("required", true).put("weapon", modifier.path("weapon").asText());
            option.set("value", modifier.path("value").deepCopy());
            p.nested().add(new Nested("slotModifier", option, path));
            return;
        }
        Set<String> allowed = new HashSet<>(Set.of("value", "min", "max", "markedHpDivisor", "markedHpMultiplier", "markedStressMultiplier", "traitValue", "divisor", "round"));
        if (passive) allowed.addAll(MODIFIER_CONDITIONS);
        JsonNode value = modifierValue(modifier, path, p);
        if (value == null) return;
        ArrayNode when = RuleJson.JSON.createArrayNode();
        if (passive) modifierConditions(modifier, path, p, when);
        List<String> stats = new ArrayList<>();
        if (modifier.has("roll")) {
            allowed.add("roll");
            p.onlyFields(modifier, path, allowed);
            String roll = modifier.path("roll").asText();
            if (ROLLS.contains(roll)) stats.add("roll." + roll);
            else p.add(path + "/roll", "Unsupported roll modifier: " + roll);
        } else {
            allowed.add("stat");
            p.onlyFields(modifier, path, allowed);
            String stat = modifier.path("stat").asText();
            switch (stat) {
                case "thresholds" -> stats.addAll(List.of("thresholds.major", "thresholds.severe"));
                case "majorThreshold" -> stats.add("thresholds.major");
                case "severeThreshold" -> stats.add("thresholds.severe");
                case "loadoutMax" -> stats.add("loadout.max");
                case "traits" -> TRAITS.forEach(trait -> stats.add("trait." + trait));
                case "evasion", "armorScore", "proficiency" -> stats.add(stat);
                default -> {
                    if (stat.startsWith("trait.") && TRAITS.contains(stat.substring(6))) stats.add(stat);
                    else p.add(path + "/stat", "Unsupported statistic: " + stat);
                }
            }
        }
        for (String stat : stats) {
            ObjectNode added = out.addObject().put("stat", stat);
            added.set("value", value.deepCopy());
            if (when.size() == 1) added.set("when", when.get(0).deepCopy());
            else if (when.size() > 1) added.putObject("when").set("and", when.deepCopy());
        }
    }

    private static final Set<String> MODIFIER_CONDITIONS = Set.of("hopeMin", "requiresArmor", "loadoutDomain", "armorFull", "requiresEffect");

    /** A fixed or computed value; marked-track scaling replaces {@code value}, and {@code min}/{@code max} clamp the result. */
    private static JsonNode modifierValue(JsonNode modifier, String path, Problems p) {
        Map<String, String[]> scaled = Map.of("markedHpDivisor", new String[]{"actor.hp.marked", "divide"},
                "markedHpMultiplier", new String[]{"actor.hp.marked", "multiply"}, "markedStressMultiplier", new String[]{"actor.stress.marked", "multiply"});
        List<String> present = scaled.keySet().stream().filter(modifier::has).sorted().toList();
        JsonNode value;
        if (modifier.has("traitValue")) {
            String trait = modifier.path("traitValue").asText();
            String rounding = modifier.path("round").asText("down");
            if (!TRAITS.contains(trait) || modifier.has("value") || !present.isEmpty()
                    || !modifier.path("divisor").isInt() || modifier.path("divisor").asInt() < 1 || modifier.path("divisor").asInt() > 100
                    || !Set.of("up", "down").contains(rounding)) {
                p.add(path + "/traitValue", "Expected one trait, a divisor from 1 to 100 and round up or down.");
                return null;
            }
            ObjectNode quotient = RuleJson.JSON.createObjectNode();
            quotient.putArray("divide").add(read("actor.stats.trait." + trait)).add(modifier.path("divisor").asInt());
            value = RuleJson.JSON.createObjectNode().set("up".equals(rounding) ? "ceil" : "floor", quotient);
        } else if (!present.isEmpty()) {
            String field = present.get(0);
            if (present.size() > 1 || modifier.has("value")) { p.add(path + "/" + field, "A modifier needs exactly one value source."); return null; }
            JsonNode factor = modifier.get(field);
            if (!factor.isInt() || factor.asInt() < 1 || factor.asInt() > 100) { p.add(path + "/" + field, "Expected a whole number from 1 to 100."); return null; }
            ObjectNode computed = RuleJson.JSON.createObjectNode();
            computed.putArray(scaled.get(field)[1]).add(read(scaled.get(field)[0])).add(factor.asInt());
            value = "divide".equals(scaled.get(field)[1]) ? RuleJson.JSON.createObjectNode().set("floor", computed) : computed;
        } else {
            value = signed(modifier.path("value"));
            if (value == null) { p.add(path + "/value", "Expected a whole-number modifier or a supported computed value."); return null; }
        }
        for (String bound : List.of("min", "max")) {
            if (!modifier.has(bound)) continue;
            if (!modifier.path(bound).isInt()) { p.add(path + "/" + bound, "Expected a whole number."); return null; }
            ObjectNode clamped = RuleJson.JSON.createObjectNode();
            clamped.putArray("min".equals(bound) ? "max" : "min").add(value).add(modifier.path(bound).asInt());
            value = clamped;
        }
        return value;
    }

    private static void modifierConditions(JsonNode modifier, String path, Problems p, ArrayNode when) {
        if (modifier.has("hopeMin")) {
            if (!modifier.path("hopeMin").isInt()) p.add(path + "/hopeMin", "Expected a whole number.");
            else when.addObject().putArray("gte").add(read("actor.hope.value")).add(modifier.path("hopeMin").asInt());
        }
        if (modifier.path("requiresArmor").asBoolean(false)) when.addObject().put("exists", "actor.armor.score");
        if (modifier.path("armorFull").asBoolean(false)) {
            when.addObject().putArray("gt").add(read("actor.armorTrack.capacity")).add(0);
            when.addObject().putArray("gte").add(read("actor.armorTrack.marked")).add(read("actor.armorTrack.capacity"));
        }
        if (modifier.has("loadoutDomain")) {
            JsonNode domain = modifier.get("loadoutDomain");
            p.onlyFields(domain, path + "/loadoutDomain", Set.of("domain", "min"));
            if (domain.path("domain").asText("").isBlank() || !domain.path("min").isInt() || domain.path("min").asInt() < 1)
                p.add(path + "/loadoutDomain", "Expected a domain name and a positive minimum.");
            else when.add(loadoutDomain(domain));
        }
        if (modifier.has("requiresEffect")) when.add(labelPresent(modifier.path("requiresEffect").asText()));
    }

    /** At least {@code min} active loadout cards of the domain. */
    private static ObjectNode loadoutDomain(JsonNode domain) {
        ObjectNode pluck = RuleJson.JSON.createObjectNode();
        pluck.putArray("pluck").add(read("actor.loadout")).add("domain");
        ObjectNode count = RuleJson.JSON.createObjectNode();
        count.putArray("count").add(pluck).add(domain.path("domain").asText());
        ObjectNode gte = RuleJson.JSON.createObjectNode();
        gte.putArray("gte").add(count).add(domain.path("min").asInt());
        return gte;
    }

    private record Pools(ObjectNode local, Map<String, ObjectNode> shared) {
        boolean has(String id) {
            if (local.has(id)) return true;
            ObjectNode declaration = shared.get(id);
            if (declaration == null) return false;
            local.set(id, declaration.deepCopy());
            return true;
        }
    }

    /** Collects every legacy resource declaration so costs in other catalogs can reference them. */
    public static Map<String, ObjectNode> sharedPools(List<String> legacyCatalogs) throws IOException {
        Map<String, ObjectNode> shared = new TreeMap<>();
        for (String text : legacyCatalogs)
            collectPools(RuleJson.parse(text, RuleJson.MAX_DOCUMENT_CHARACTERS, RuleJson.MAX_DOCUMENT_NODES), shared);
        return shared;
    }

    private static void collectPools(JsonNode node, Map<String, ObjectNode> shared) {
        if (node.isObject()) {
            for (JsonNode resource : node.path("automation").path("resources")) {
                ObjectNode pools = RuleJson.JSON.createObjectNode();
                resource(resource, "", new Problems("", new ArrayList<>(), new ArrayList<>()), pools, new ArrayList<>());
                pools.fields().forEachRemaining(field -> shared.putIfAbsent(field.getKey(), (ObjectNode) field.getValue()));
            }
            if (node.has("automation") && node.path("automation").has("companion")) {
                ObjectNode pools = RuleJson.JSON.createObjectNode();
                companion(node.path("automation").get("companion"), "", new Problems("", new ArrayList<>(), new ArrayList<>()), pools, new ArrayList<>());
                pools.fields().forEachRemaining(field -> shared.putIfAbsent(field.getKey(), (ObjectNode) field.getValue()));
            }
            node.forEach(child -> collectPools(child, shared));
        } else if (node.isArray()) node.forEach(child -> collectPools(child, shared));
    }
    /**
     * Rest allowances, passives and move modifiers become rules on the generic rest events. {@code rest.prepare}
     * publishes the adjustable rest plan ({@code event.rest}), {@code downtime.move} publishes the adjustable move plan
     * ({@code event.move}) and {@code rest.short}/{@code rest.long} run after the moves with {@code event.rest.cleared}.
     */
    private static List<ObjectNode> rest(JsonNode rest, String path, Problems p, ObjectNode effects, Pools pools, JsonNode block,
                                         Set<String> ruleIds) {
        List<ObjectNode> out = new ArrayList<>();
        if (!rest.isObject()) { p.add(path, "Expected a rest object."); return out; }
        p.onlyFields(rest, path, REST_FIELDS);
        ArrayNode effectGate = RuleJson.JSON.createArrayNode();
        commonConditions(rest, path, p, effectGate);
        if (rest.has("extraMoves")) {
            int moves = rest.path("extraMoves").asInt(0);
            if (!rest.path("extraMoves").isInt() || moves < 1 || moves > 8) p.add(path + "/extraMoves", "Expected 1-8 extra moves.");
            else {
                ObjectNode rule = restRule("rest-moves", "rest.prepare", "rest", "rest", "automatic");
                applyWhen(rule, effectGate.deepCopy());
                rule.putArray("do").add(planAdjust("event.rest", "moves", moves, "Extra downtime moves"));
                out.add(rule);
            }
        }
        if (rest.has("longMovesInShort")) {
            if (!rest.path("longMovesInShort").isBoolean()) p.add(path + "/longMovesInShort", "Expected a boolean.");
            else if (rest.path("longMovesInShort").asBoolean()) {
                ObjectNode rule = restRule("rest-long-moves", "rest.prepare", "rest", "rest", "automatic");
                ArrayNode when = effectGate.deepCopy();
                when.addObject().putArray("eq").add(read("event.rest.kind")).add("short");
                applyWhen(rule, when);
                rule.putArray("do").add(planAdjust("event.rest", "longMoves", 1, "Long-rest move during a short rest"));
                out.add(rule);
            }
        }
        if (rest.has("expireItem")) {
            if (!rest.path("expireItem").isBoolean()) p.add(path + "/expireItem", "Expected a boolean.");
            else if (rest.path("expireItem").asBoolean()) for (String kind : List.of("short", "long")) {
                ObjectNode rule = restRule("expire-" + kind, "rest." + kind, "rest-" + kind, "rest", "automatic");
                rule.putObject("when").putArray("gt").add(read("source.quantity")).add(0);
                rule.putArray("do").addObject().put("op", "inventory.consume").set("amount", read("source.quantity"));
                ((ObjectNode) rule.get("ui")).put("label", "Item expires").put("report", "{source.name} expires.");
                out.add(rule);
            }
        }
        JsonNode passives = rest.path("passives");
        if (rest.has("passives") && (!passives.isArray() || passives.size() > 16)) p.add(path + "/passives", "Expected up to 16 rest passives.");
        else for (int i = 0; i < passives.size(); i++)
            out.addAll(restPassive(passives.get(i), path + "/passives/" + i, p, effects, pools, block, effectGate, ruleIds));
        JsonNode modifiers = rest.path("modifiers");
        if (rest.has("modifiers") && (!modifiers.isArray() || modifiers.size() > 16)) p.add(path + "/modifiers", "Expected up to 16 move modifiers.");
        else for (int i = 0; i < modifiers.size(); i++) {
            ObjectNode rule = moveModifier(modifiers.get(i), path + "/modifiers/" + i, p, effects, pools, effectGate,
                    block != null);
            if (rule != null) out.add(rule);
        }
        if (out.isEmpty()) p.add(path, "A rest block needs allowances, passives or move modifiers.");
        return out;
    }

    private static ObjectNode restRule(String id, String event, String bindingId, String surface, String mode) {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        rule.putArray("bindings").addObject().put("id", bindingId).put("event", event).put("surface", surface).put("mode", mode);
        rule.putObject("ui");
        return rule;
    }

    private static ObjectNode planAdjust(String plan, String field, Object by, String label) {
        ObjectNode op = RuleJson.JSON.createObjectNode().put("op", "plan.adjust").put("plan", plan).put("field", field);
        if (by instanceof JsonNode node) op.set("by", node);
        else op.put("by", (Integer) by);
        return op.put("label", label);
    }

    private static void restFilter(JsonNode legacy, String path, Problems p, String read, ArrayNode when) {
        if (!legacy.has("rest")) return;
        String kind = legacy.path("rest").asText();
        if (!Set.of("short", "long").contains(kind)) p.add(path + "/rest", "Expected short or long.");
        else when.addObject().putArray("eq").add(read(read)).add(kind);
    }

    private static List<ObjectNode> restPassive(JsonNode passive, String path, Problems p, ObjectNode effects, Pools pools,
                                                JsonNode block, ArrayNode effectGate, Set<String> ruleIds) {
        if (!passive.isObject()) { p.add(path, "Expected a passive object."); return List.of(); }
        p.onlyFields(passive, path, PASSIVE_FIELDS);
        hostOwned(passive, path, p);
        String id = passive.path("id").asText();
        if (!ID.matcher(id).matches()) { p.add(path + "/id", "Invalid passive ID: " + id); return List.of(); }
        boolean prepare = passive.has("extraMoves"), move = passive.has("move");
        if (prepare && move) p.add(path, "A passive either adds moves or improves one move.");
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        ArrayNode bindings = rule.putArray("bindings");
        String kind = passive.path("rest").asText("");
        if (prepare) bindings.addObject().put("id", "rest").put("event", "rest.prepare").put("surface", "rest");
        else if (move) bindings.addObject().put("id", "downtime").put("event", "downtime.move").put("surface", "downtime");
        else for (String rest : List.of("short", "long"))
            if (kind.isEmpty() || kind.equals(rest))
                bindings.addObject().put("id", "rest-" + rest).put("event", "rest." + rest).put("surface", "rest");
        if (bindings.isEmpty()) { p.add(path + "/rest", "Expected short or long."); return List.of(); }
        for (JsonNode binding : bindings) {
            ((ObjectNode) binding).put("mode", "option");
            if (!prepare && !move && "noRecovery".equals(passive.path("condition").asText()))
                ((ObjectNode) binding).put("restPhase", "afterReset");
            if (passive.has("defaultOn")) {
                if (!passive.path("defaultOn").isBoolean()) p.add(path + "/defaultOn", "Expected a boolean.");
                ((ObjectNode) binding).put("defaultOn", passive.path("defaultOn").asBoolean());
            }
        }
        labels(passive, path, p, rule);
        ArrayNode when = effectGate.deepCopy();
        commonConditions(passive, path, p, when);
        if (prepare) restFilter(passive, path, p, "event.rest.kind", when);
        if (move) {
            restFilter(passive, path, p, "event.move.rest", when);
            when.addObject().putArray("eq").add(read("event.move.id")).add(passive.path("move").asText());
        }
        if (passive.has("minHope")) {
            if (!passive.path("minHope").isInt() || passive.path("minHope").asInt() < 1) p.add(path + "/minHope", "Expected a positive whole number.");
            else when.addObject().putArray("gte").add(read("event.rest.start.hope")).add(passive.path("minHope").asInt());
        }
        if (passive.has("condition")) {
            String condition = passive.path("condition").asText();
            switch (condition) {
                case "armorMarked" -> when.addObject().putArray("gt").add(read("actor.armorTrack.marked")).add(0);
                case "noArmorMarked" -> when.addObject().putArray("eq").add(read("event.rest.start.armor")).add(0);
                case "noRecovery" -> {
                    if (prepare || move) p.add(path + "/condition", "Recovery is only known after the rest moves.");
                    when.addObject().putArray("eq").add(read("event.rest.cleared.hitPoints")).add(0);
                    when.addObject().putArray("eq").add(read("event.rest.cleared.stress")).add(0);
                }
                default -> p.add(path + "/condition", "Unsupported rest condition: " + condition);
            }
        }
        if (passive.has("castBeforeRest")) {
            String action = passive.path("castBeforeRest").asText();
            boolean found = false;
            if (block != null) for (JsonNode candidate : block.path("actions")) found |= action.equals(candidate.path("id").asText());
            if (!found) p.add(path + "/castBeforeRest", "Cast-before-rest must name an action of the same source: " + action);
        }
        applyWhen(rule, when);
        if (passive.has("cost")) costs(passive.get("cost"), path + "/cost", p, pools, rule);
        if (passive.has("consumeItem")) {
            if (!passive.path("consumeItem").isBoolean()) p.add(path + "/consumeItem", "Expected a boolean.");
            else if (passive.path("consumeItem").asBoolean()) {
                ArrayNode costs = rule.has("costs") ? (ArrayNode) rule.get("costs") : rule.putArray("costs");
                costs.addObject().put("op", "inventory.consume").put("amount", 1);
            }
        }
        ArrayNode steps = rule.putArray("do");
        String label = escape(passive.path("label").asText(id));
        if (prepare) {
            JsonNode extra = passive.path("extraMoves");
            if (!extra.isInt() || extra.asInt() < 1 || extra.asInt() > 8) p.add(path + "/extraMoves", "Expected 1-8 extra moves.");
            else steps.add(planAdjust("event.rest", "moves", extra.asInt(), label));
        }
        if (move && passive.has("bonus")) {
            JsonNode bonus = signed(passive.path("bonus"));
            if (bonus == null) p.add(path + "/bonus", "Expected a whole-number move bonus.");
            else steps.add(planAdjust("event.move", "bonus", bonus, label));
        }
        List<ObjectNode> out = new ArrayList<>(List.of(rule));
        if (passive.has("chooseAlly") && !passive.path("chooseAlly").isBoolean()) p.add(path + "/chooseAlly", "Expected a boolean.");
        JsonNode list = passive.path("steps");
        if (passive.path("chooseAlly").asBoolean(false)) {
            // The chosen ally claims the benefit from a one-claim offer; the passive's steps apply to that recipient.
            String group = id + "-ally";
            ObjectNode claim = RuleJson.JSON.createObjectNode().put("id", group);
            claim.putArray("bindings").addObject().put("id", "claim").put("event", "claim.accept").put("surface", "chat")
                    .put("mode", "button").put("claimGroup", group).put("audience", "others")
                    .putObject("claimScope").put("recipients", "otherPlayersCharacters");
            ArrayNode granted = claim.putArray("do");
            for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, granted, effects, pools, "recipient");
            if (granted.isEmpty()) p.add(path + "/steps", "An ally benefit needs at least one step.");
            claim.putObject("ui").put("label", label).put("report", "{recipient.name} accepted " + label + ".");
            steps.addObject().put("op", "claim.offer").put("group", group).put("label", label).put("limit", 1);
            if (move) deferMoveBenefit(rule);
            out.add(claim);
            return out;
        }
        for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, steps, effects, pools);
        if (steps.isEmpty() && !rule.has("costs")) p.add(path, "A rest passive needs an effect.");
        if (move) deferMoveBenefit(rule);
        return out;
    }

    private static ObjectNode moveModifier(JsonNode modifier, String path, Problems p, ObjectNode effects, Pools pools,
                                           ArrayNode effectGate, boolean sourceRest) {
        if (!modifier.isObject()) { p.add(path, "Expected a move modifier object."); return null; }
        p.onlyFields(modifier, path, MOVE_MODIFIER_FIELDS);
        String move = modifier.path("move").asText("");
        if (!ID.matcher(move).matches()) { p.add(path + "/move", "Expected a downtime move ID."); return null; }
        ObjectNode rule = restRule("move-" + move, "downtime.move", "downtime", "downtime", "automatic");
        ArrayNode when = effectGate.deepCopy();
        when.addObject().putArray("eq").add(read("event.move.id")).add(move);
        restFilter(modifier, path, p, "event.move.rest", when);
        if (modifier.has("target")) {
            if (!"ally".equals(modifier.path("target").asText())) p.add(path + "/target", "Expected ally.");
            else when.addObject().putArray("eq").add(read("event.move.ally")).add(true);
        }
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        boolean ignoredRepair = false;
        for (String field : List.of("bonus", "repairAllies")) {
            if (!modifier.has(field)) continue;
            JsonNode value = modifier.path(field);
            if ("repairAllies".equals(field)) {
                if (!sourceRest) {
                    ignoredRepair = true;
                    continue;
                }
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() > 1000) {
                    p.add(path + "/repairAllies", "Expected a fixed integer no greater than 1000 for party recovery.");
                    continue;
                }
                if (value.asInt() <= 0) {
                    ignoredRepair = true;
                    continue;
                }
            }
            JsonNode by = "repairAllies".equals(field) ? value : signed(value);
            if (by == null) p.add(path + "/" + field, "Expected a whole number.");
            else {
                ObjectNode adjustment = planAdjust("event.move", "bonus".equals(field) ? "bonus" : "allies", by,
                        "bonus".equals(field) ? "Move bonus" : "Party Armor recovery");
                if ("repairAllies".equals(field))
                    adjustment.putObject("partyRecovery").put("track", "armorTrack").put("scope", "activePlayerCharacters")
                            .putObject("afterRest").put("sourcePath", "loadout").put("move", move);
                steps.add(adjustment);
            }
        }
        JsonNode list = modifier.path("steps");
        for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, steps, effects, pools);
        if (modifier.has("note")) ((ObjectNode) rule.get("ui")).put("report", escape(modifier.path("note").asText()));
        if (steps.isEmpty() && !ignoredRepair) p.add(path, "A move modifier needs a bonus, ally count or steps.");
        deferMoveBenefit(rule);
        return rule;
    }

    private static void deferMoveBenefit(ObjectNode rule) {
        ArrayNode before = (ArrayNode) rule.path("do");
        ArrayNode adjustments = RuleJson.JSON.createArrayNode(), deferred = RuleJson.JSON.createArrayNode();
        for (JsonNode step : before) {
            if ("plan.adjust".equals(step.path("op").asText())) adjustments.add(step);
            else deferred.add(step);
        }
        JsonNode costs = rule.remove("costs");
        ObjectNode ui = (ObjectNode) rule.path("ui");
        JsonNode report = ui.remove("report");
        rule.set("do", adjustments);
        if (!deferred.isEmpty() || costs != null || report != null) {
            ObjectNode after = rule.putObject("after");
            if (costs != null) after.set("costs", costs);
            if (!deferred.isEmpty()) after.set("do", deferred);
            if (report != null) after.set("report", report);
        }
    }

    /** Sheet metadata: the features acquired with this source, optionally shown grouped beneath it. */
    private static void grants(JsonNode legacy, String path, Problems p, ObjectNode out) {
        JsonNode names = legacy.get("grantsFeatures");
        if (!names.isArray() || names.isEmpty() || names.size() > 16) { p.add(path + "/grantsFeatures", "Expected 1-16 feature names."); return; }
        ObjectNode grants = out.putObject("grants");
        ArrayNode features = grants.putArray("features");
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).isTextual() || names.get(i).asText().isBlank()) p.add(path + "/grantsFeatures/" + i, "Expected a feature name.");
            else features.add(names.get(i).asText());
        }
        JsonNode grouped = legacy.path("groupGrantedFeatures");
        if (legacy.has("groupGrantedFeatures") && !grouped.isBoolean()) p.add(path + "/groupGrantedFeatures", "Expected a boolean.");
        grants.put("display", grouped.asBoolean(false) ? "grouped" : "separate");
    }

    private static ObjectNode labelPresent(String label) {
        ObjectNode pluck = RuleJson.JSON.createObjectNode();
        pluck.putArray("pluck").add(read("actor.effects")).add("label");
        ObjectNode includes = RuleJson.JSON.createObjectNode();
        includes.putArray("includes").add(pluck).add(label);
        return includes;
    }

    /**
     * A channel benefit is a derived effect: active exactly while the actor has the channel effect it names. An aura
     * benefit ("Name: Element") is active while the "Name" marker effect and the "Channeling: Element" effect are both
     * active, which is how the legacy catalog pairs an aura with the element currently channeled.
     */
    private static void channelBenefit(JsonNode legacy, String path, Problems p, ObjectNode effects) {
        p.onlyFields(legacy, path, Set.of("element", "effect"));
        JsonNode effect = legacy.path("effect");
        if (!effect.isObject()) { p.add(path + "/effect", "Expected an effect."); return; }
        String element = legacy.path("element").asText("");
        String name = effect.path("name").asText("");
        ObjectNode condition;
        if ("aura".equals(element)) {
            int split = name.indexOf(": ");
            if (split <= 0) { p.add(path + "/effect/name", "Aura benefits are named \"Aura: Element\"."); return; }
            ObjectNode both = RuleJson.JSON.createObjectNode();
            both.putArray("and").add(labelPresent(name.substring(0, split))).add(labelPresent("Channeling: " + name.substring(split + 2)));
            condition = both;
        } else {
            String required = effect.path("requiresEffect").asText("");
            if (element.isBlank() || required.isBlank()) { p.add(path, "Channel benefits need an element and the channel effect they require."); return; }
            condition = labelPresent(required);
        }
        ObjectNode copy = ((ObjectNode) effect).deepCopy();
        copy.remove("requiresEffect");
        copy.remove("until");
        String id = declareEffect(copy, path + "/effect", p, effects);
        if (id != null) ((ObjectNode) effects.get(id)).set("while", condition);
    }

    private static String keepSlot(String effect) { String slot = "keep-" + slug(effect); return slot.substring(0, Math.min(64, slot.length())); }

    /** A kept effect survives its next damage ending: the ending skips while the keep slot is set and then clears it. */
    private static void keepEffects(ArrayNode rules, String path, Problems p, ObjectNode out) {
        for (JsonNode rule : rules) for (JsonNode op : rule.path("do")) {
            if (!"state.set".equals(op.path("op").asText()) || !op.path("slot").asText().startsWith("keep-")) continue;
            String slot = op.path("slot").asText();
            ObjectNode ending = null;
            for (JsonNode candidate : rules) {
                JsonNode first = candidate.path("do").path(0);
                if ("damage.applied".equals(candidate.path("bindings").path(0).path("event").asText()) && "effect.end".equals(first.path("op").asText())
                        && slot.equals(keepSlot(first.path("effect").asText()))) ending = (ObjectNode) candidate;
            }
            if (ending == null) { p.add(path, "Keeping an effect needs the same source to end it after damage."); continue; }
            ObjectNode state = out.has("state") ? (ObjectNode) out.get("state") : out.putObject("state");
            if (state.has(slot)) continue;
            state.put(slot, false);
            ((ObjectNode) ending.path("do").get(0)).putObject("if").set("not", read("state." + slot));
            ((ArrayNode) ending.get("do")).addObject().put("op", "state.set").put("slot", slot).put("value", false);
        }
    }

    /** Ends the effect once applied damage reaches the given final (or, for {@code field: incoming}, pre-armor) severity (1 minor ... 4 massive). */
    /** Ends an effect once its holder finishes a roll of one of the given kinds (damage by default). */
    private static ObjectNode endOnDealtDamage(JsonNode legacy) {
        String effect = legacy.path("effect").asText();
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "end-" + slug(effect) + "-on-" + legacy.path("on").asText("damage"));
        rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        if (!legacy.has("kinds")) when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
        else {
            ArrayNode any = when.addObject().putArray("or");
            for (JsonNode kind : legacy.get("kinds")) any.addObject().putArray("eq").add(read("event.check.kind")).add(kind.asText());
        }
        when.add(labelPresent(effect));
        applyWhen(rule, when);
        rule.putArray("do").addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        rule.putObject("ui").put("label", escape(effect) + " ends");
        return rule;
    }

    /** The effect ends when its holder starts an action roll with no Hope left to pay for maintaining it. */
    private static ObjectNode maintainEnd(JsonNode legacy) {
        String effect = legacy.path("effect").asText();
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "revert-" + slug(effect));
        rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.prepare").put("surface", "roll").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        ArrayNode any = when.addObject().putArray("or");
        for (String kind : List.of("attack", "action", "spellcast")) any.addObject().putArray("eq").add(read("event.check.kind")).add(kind);
        when.add(labelPresent(effect));
        when.addObject().putArray("eq").add(read("actor.hope.value")).add(0);
        applyWhen(rule, when);
        rule.putArray("do").addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        rule.putObject("ui").put("label", escape(effect) + " reverts");
        return rule;
    }

    private static ObjectNode endOnDamage(JsonNode legacy) {
        String effect = legacy.path("effect").asText();
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "end-" + slug(effect));
        rule.putArray("bindings").addObject().put("id", "use").put("event", "damage.applied").put("surface", "damage").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.add(labelPresent(effect));
        when.addObject().putArray("gte").add(read("event.applied." + legacy.path("field").asText("severity"))).add(legacy.path("severity").asInt());
        applyWhen(rule, when);
        rule.putArray("do").addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        rule.putObject("ui").put("label", escape(effect) + " ends");
        return rule;
    }

    /** An effect that adds weapon damage dice: each Proficiency-scaled weapon damage roll gains the dice while it is active. */
    private static List<ObjectNode> effectDamage(JsonNode legacy, String path, Problems p) {
        JsonNode damage = legacy.path("damage");
        p.onlyFields(damage, path, Set.of("extraDice", "dropLowest", "proficiencyOnly"));
        int extra = damage.path("extraDice").asInt(-1);
        if (!damage.path("extraDice").isInt() || extra < 1 || extra > 32) { p.add(path + "/extraDice", "Expected 1-32 extra dice."); return List.of(); }
        if (damage.path("dropLowest").asInt(0) != 0) { p.add(path + "/dropLowest", "Effects cannot drop damage dice."); return List.of(); }
        if (!damage.path("proficiencyOnly").asBoolean(false)) { p.add(path + "/proficiencyOnly", "Effect damage dice apply only to Proficiency-scaled weapons."); return List.of(); }
        String effect = legacy.path("effect").asText();
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", slug(effect) + "-damage");
        rule.putArray("bindings").addObject().put("id", "use").put("event", "roll.finished").put("surface", "roll").put("mode", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
        weaponDiceConditions(when);
        when.add(labelPresent(effect));
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        JsonNode roll = addWeaponDice(steps, read("event.roll"), extra);
        steps.addObject().put("op", "roll.publish").set("roll", roll);
        rule.putObject("ui").put("label", escape(effect));
        return List.of(rule);
    }

    /** Ends the granting effect once its holder uses a long-rest move during a short rest. */
    private static ObjectNode consumeLongRestMove(JsonNode legacy, String path) {
        String effect = legacy.path("effect").asText();
        ObjectNode rule = restRule("end-" + slug(effect), "downtime.move", "downtime", "downtime", "automatic");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        ObjectNode pluck = RuleJson.JSON.createObjectNode();
        pluck.putArray("pluck").add(read("actor.effects")).add("label");
        when.addObject().putArray("includes").add(pluck).add(effect);
        when.addObject().putArray("eq").add(read("event.move.long")).add(true);
        when.addObject().putArray("eq").add(read("event.move.rest")).add("short");
        applyWhen(rule, when);
        rule.putArray("do").addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        return rule;
    }

    /** The roll filter for death-move Hope bonuses: {@code death} for both moves, otherwise {@code death:<move>}. */
    private static String deathRoll(JsonNode moves, String path, Problems p) {
        Set<String> listed = new HashSet<>();
        if (!moves.isArray() || moves.isEmpty()) { p.add(path, "Expected risk and/or avoid."); return null; }
        for (JsonNode move : moves)
            if (!Set.of("risk", "avoid").contains(move.asText()) || !listed.add(move.asText())) { p.add(path, "Expected risk and/or avoid."); return null; }
        return listed.size() == 2 ? "death" : "death:" + listed.iterator().next();
    }

    /**
     * A source's death-move declaration. Flat Hope bonuses become automatic death-roll modifiers, Mask tokens an optional
     * paid modifier, and Won't Stay Dead a post-roll button; alternative moves are converted with their actions.
     */
    private static void deathMove(JsonNode move, String path, Problems p, List<Nested> queue) {
        p.onlyFields(move, path, Set.of("kind", "moves", "bonus"));
        String kind = move.path("kind").asText("");
        if (!DEATH_KINDS.contains(kind)) { p.add(path + "/kind", "Unsupported death move: " + kind); return; }
        boolean listsMoves = Set.of("hopeBonus", "mask", "hopeSpend").contains(kind);
        if (move.has("moves") != listsMoves) { p.add(path + "/moves", "Only Hope bonuses list the death moves they apply to."); return; }
        if (move.has("bonus") != Set.of("hopeBonus", "mask").contains(kind)) { p.add(path + "/bonus", "Only flat Hope bonuses declare a bonus."); return; }
        String roll = listsMoves ? deathRoll(move.get("moves"), path + "/moves", p) : null;
        if (listsMoves && roll == null) return;
        if ("undying".equals(kind)) queue.add(new Nested("undying", RuleJson.JSON.createObjectNode(), path));
        if (!move.has("bonus")) return;
        JsonNode bonus = move.get("bonus");
        if (!bonus.isInt() || bonus.asInt() < 1 || bonus.asInt() > 12) { p.add(path + "/bonus", "Expected a Hope bonus from 1 to 12."); return; }
        ObjectNode option = RuleJson.JSON.createObjectNode().put("roll", roll).put("value", bonus.asInt());
        if ("hopeBonus".equals(kind)) queue.add(new Nested("slotModifier", option.put("id", "death-hope-bonus").put("label", "Hope bonus").put("required", true), path));
        else {
            option.put("id", "death-mask").put("label", "Spend a Mask token (mask worn)").putObject("cost").put("death-mask", 1);
            queue.add(new Nested("rollOptions", option, path));
        }
    }

    /**
     * A death-move action. Spending Hope on a death roll is a quantity roll option; alternative moves are buttons in the
     * death-move dialog ({@code death.options}) that replace the core moves.
     */
    private static ObjectNode deathAction(JsonNode action, JsonNode move, String path, Problems p, ObjectNode effects, Pools pools,
                                          boolean limited, List<ObjectNode> extra, ObjectNode out) {
        p.onlyFields(action, path, Set.of("id", "label", "description", "deathMove"));
        if (!move.isObject()) { p.add(path + "/deathMove", "Death-move actions need the source's deathMove declaration."); return null; }
        String kind = move.path("kind").asText(""), chosen = action.path("deathMove").asText("");
        if ("hopeSpend".equals(kind)) {
            boolean listed = false;
            for (JsonNode listedMove : move.path("moves")) listed |= chosen.equals(listedMove.asText());
            if (!listed) { p.add(path + "/deathMove", "The death move is not listed by the source: " + chosen); return null; }
            ObjectNode option = RuleJson.JSON.createObjectNode().put("id", action.path("id").asText()).put("label", action.path("label").asText(""))
                    .put("roll", "death:" + chosen).put("value", 1);
            if (action.has("description")) option.set("description", action.get("description"));
            option.putObject("quantity").put("resource", "hope").put("label", "Hope to spend");
            option.putObject("cost").put("hope", 1);
            return rollOption(option, path, p, effects, pools, limited, extra);
        }
        if (!kind.equals(chosen) || !Set.of("spectral", "egg", "battleHardened", "unbreakable").contains(kind)) {
            p.add(path + "/deathMove", "Expected the source's alternative death move: " + kind);
            return null;
        }
        String id = action.path("id").asText();
        if (!ID.matcher(id).matches()) { p.add(path + "/id", "Invalid action ID: " + id); return null; }
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        rule.putArray("bindings").addObject().put("id", "death").put("event", "death.options").put("surface", "death").put("mode", "button");
        ObjectNode ui = labels(action, path, p, rule);
        if (limited) rule.put("limitRef", "uses");
        ArrayNode costs = RuleJson.JSON.createArrayNode(), steps = RuleJson.JSON.createArrayNode();
        switch (kind) {
            case "battleHardened" -> {
                costs.addObject().put("op", "resource.spend").put("pool", "actor.hope").put("amount", 1);
                steps.addObject().put("op", "track.clear").put("track", "actor.hp").put("amount", 1).put("as", "hp");
                ui.put("report", "Spent 1 Hope and cleared {result.hp} HP instead of a death move.");
            }
            case "unbreakable" -> {
                steps.addObject().put("op", "dice.roll").put("count", 1).put("sides", 6).put("as", "roll");
                steps.addObject().put("op", "track.clear").put("track", "actor.hp").put("as", "hp").set("amount", read("result.roll"));
                steps.addObject().put("op", "collection.remove").put("target", "actor").put("collection", "loadout").put("as", "card")
                        .set("item", read("source.instanceId"));
                steps.addObject().put("op", "collection.add").put("target", "actor").put("collection", "vault").set("item", read("result.card"));
                ui.put("report", "Rolled {result.roll}: cleared {result.hp} HP instead of a death move and moved {source.name} to the vault.");
            }
            case "egg" -> {
                if (!p.sourceId().startsWith("consumables:")) costs.addObject().put("op", "inventory.consume").put("amount", 1);
                ui.put("report", "{source.name}: returned as an ancient black-winged avian, retaining memories, now desiring to reside in the "
                        + "Sanctum Mortalis. Resolve the new form with the GM.");
            }
            default -> {
                ObjectNode form = RuleJson.JSON.createObjectNode().put("name", "Spectral Form").put("kind", "buff")
                        .put("text", "Resistance to physical damage; pass through objects. Ends when you clear more HP or mark your last HP. "
                                + "If inside an object when it ends, the GM must move you to the nearest open space.");
                String effect = declareEffect(form, path + "/deathMove", p, effects);
                if (effect == null) return null;
                String hpSlot = effect + "-hp", blocked = effect + "-blocked";
                ObjectNode state = out.has("state") ? (ObjectNode) out.get("state") : out.putObject("state");
                state.put(hpSlot, 0).put(blocked, false);
                rule.putObject("when").set("not", read("state." + blocked));
                ui.put("unavailable", "Choose a different death move after Spectral Form ends at your last HP.");
                steps.addObject().put("op", "track.clear").put("track", "actor.hp").put("amount", 1).put("as", "hp");
                steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
                steps.addObject().put("op", "state.set").put("slot", hpSlot).set("value", read("actor.hp.marked"));
                ui.put("report", "Cleared {result.hp} HP and became spectral. Further HP healing or marking the last HP ends this form.");
                ObjectNode last = RuleJson.JSON.createObjectNode();
                last.putArray("gte").add(read("actor.hp.marked")).add(read("actor.hp.capacity"));
                ObjectNode healed = RuleJson.JSON.createObjectNode();
                healed.putArray("lt").add(read("actor.hp.marked")).add(read("state." + hpSlot));
                ObjectNode ends = trackChanged(effect + "-ends", "hp");
                ArrayNode when = RuleJson.JSON.createArrayNode();
                when.add(labelPresent("Spectral Form"));
                when.addObject().putArray("or").add(healed).add(last.deepCopy());
                applyWhen(ends, when);
                ArrayNode ending = ends.putArray("do");
                ending.addObject().put("op", "state.set").put("slot", blocked).set("value", last.deepCopy());
                ending.addObject().put("op", "effect.end").put("effect", "Spectral Form").put("target", "actor");
                ends.putObject("ui").put("label", "Spectral Form ends");
                extra.add(ends);
                ObjectNode recovers = trackChanged(effect + "-available", "hp");
                ArrayNode open = RuleJson.JSON.createArrayNode();
                open.add(read("state." + blocked));
                open.addObject().putArray("lt").add(read("actor.hp.marked")).add(read("actor.hp.capacity"));
                applyWhen(recovers, open);
                recovers.putArray("do").addObject().put("op", "state.set").put("slot", blocked).put("value", false);
                recovers.putObject("ui").put("label", "Spectral Form is available again");
                extra.add(recovers);
            }
        }
        if (!costs.isEmpty()) rule.set("costs", costs);
        if (!steps.isEmpty()) rule.set("do", steps);
        return rule;
    }

    private static final Map<String, String> CREATION_FIELDS = Map.of("hp", "Hit Point slots", "stress", "Stress slots",
            "evasion", "Evasion", "domainCards", "Domain cards", "experienceBonus", "Experience bonus");

    /**
     * Character-creation allowances become an automatic {@code creation.apply} rule adjusting the host-published creation
     * plan ({@code event.creation}); the character builder lists the adjustable fields and applies the adjusted plan once.
     */
    private static List<ObjectNode> creation(JsonNode creation, String path, Problems p) {
        if (!creation.isObject() || creation.isEmpty()) { p.add(path, "Expected creation allowances."); return List.of(); }
        p.onlyFields(creation, path, CREATION_FIELDS.keySet());
        ObjectNode rule = restRule("creation", "creation.apply", "creation", "creation", "automatic");
        ArrayNode steps = rule.putArray("do");
        for (String field : CREATION_FIELDS.keySet().stream().sorted().toList()) {
            if (!creation.has(field)) continue;
            JsonNode by = creation.get(field);
            if (!by.isInt() || by.asInt() == 0 || Math.abs(by.asInt()) > 8) p.add(path + "/" + field, "Expected a non-zero whole number from -8 to 8.");
            else steps.add(planAdjust("event.creation", field, by.asInt(), CREATION_FIELDS.get(field)));
        }
        return steps.isEmpty() ? List.of() : List.of(rule);
    }

    /** Won't Stay Dead: after a failed Risk It All, permanently lose a Hit Point slot to succeed with Hope instead. */
    private static ObjectNode undying() {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", "undying");
        ObjectNode binding = rule.putArray("bindings").addObject().put("id", "claim").put("event", "roll.finished")
                .put("surface", "roll").put("mode", "button").put("claimGroup", "death-outcome").put("audience", "self");
        binding.putObject("claimScope").put("recipients", "sourceActor");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        when.addObject().putArray("eq").add(read("event.check.kind")).add("death");
        when.addObject().putArray("eq").add(read("event.check.move")).add("risk");
        when.addObject().putArray("eq").add(read("event.check.dominant")).add("fear");
        when.addObject().putArray("ne").add(read("event.check.critical")).add(true);
        applyWhen(rule, when);
        ArrayNode steps = rule.putArray("do");
        steps.addObject().put("op", "track.capacity").put("track", "actor.hp").put("by", -1).put("as", "hp");
        steps.addObject().put("op", "roll.annotate").put("field", "deathOutcome").put("value", "hope").put("as", "roll").set("from", read("event.roll"));
        steps.addObject().put("op", "roll.publish").set("roll", read("result.roll"));
        rule.putObject("ui").put("label", "Mark a Hit Point permanently to succeed with Hope")
                .put("report", "Permanently marked a Hit Point to succeed with Hope.");
        return rule;
    }

    /** An effect that lets its holder clear a Hit Point instead of making a death move, ending the effect. */
    private static ObjectNode lifeWard(String effect) {
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", slug(effect) + "-death");
        rule.putArray("bindings").addObject().put("id", "death").put("event", "death.options").put("surface", "death").put("mode", "button");
        rule.set("when", labelPresent(effect));
        ArrayNode steps = rule.putArray("do");
        steps.addObject().put("op", "track.clear").put("track", "actor.hp").put("amount", 1).put("as", "hp");
        steps.addObject().put("op", "effect.end").put("effect", effect).put("target", "actor");
        rule.putObject("ui").put("label", "Clear 1 HP instead of a death move (" + escape(effect) + ")")
                .put("report", "Cleared {result.hp} HP instead of a death move; " + escape(effect) + " ended.");
        return rule;
    }

    /** Manual notes are shown with the source's player-facing rules. */
    private static void manualNotes(JsonNode notes, String path, Problems p, ArrayNode rules) {
        if (!notes.isArray() || notes.isEmpty()) { p.add(path, "Expected a list of notes."); return; }
        List<String> text = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            if (!notes.get(i).isTextual() || notes.get(i).asText().isBlank()) p.add(path + "/" + i, "Expected a note.");
            else text.add(escape(notes.get(i).asText()));
        }
        boolean shown = false;
        for (JsonNode rule : rules) {
            if (!Set.of("button", "option").contains(rule.path("bindings").path(0).path("mode").asText())) continue;
            ObjectNode ui = rule.has("ui") ? (ObjectNode) rule.get("ui") : ((ObjectNode) rule).putObject("ui");
            List<String> parts = new ArrayList<>();
            if (ui.has("description")) parts.add(ui.path("description").asText());
            parts.addAll(text);
            ui.put("description", String.join(" ", parts));
            shown = true;
        }
        if (!shown) p.add(path, "Manual notes need a player-facing rule.");
    }

    /** A legacy rule still to convert; effect-granted rules are queued with the granting effect as a condition. */
    private record Nested(String family, JsonNode legacy, String path) {}

    private record Problems(String sourceId, List<Diagnostic> list, List<Nested> nested) {
        void add(String path, String message) { list.add(new Diagnostic(path, sourceId, message)); }

        void onlyFields(JsonNode node, String path, Set<String> allowed) {
            if (!node.isObject()) { add(path, "Expected an object."); return; }
            node.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) add(path + "/" + field, "Unsupported legacy field: " + field);
            });
        }
    }

    static String escape(String text) { return text.replace("{", "{{").replace("}", "}}"); }

    static String slug(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "item";
        return slug.length() > 96 ? slug.substring(0, 96) : slug;
    }

    private static String unique(String base, Set<String> used) {
        String id = base;
        for (int n = 2; !used.add(id); n++) id = base + "-" + n;
        return id;
    }

    private static boolean containsAutomation(JsonNode node) {
        if (node.isObject()) {
            if (node.has("automation")) return true;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getKey().startsWith("_") && containsAutomation(field.getValue())) return true;
            }
        } else if (node.isArray()) for (JsonNode child : node) if (containsAutomation(child)) return true;
        return false;
    }

    /** Converts every catalog in {@code legacyDir} into {@code stagingDir} and writes a coverage report. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: LegacyCatalogConverter <legacy-catalog-dir> <staging-dir>");
        Path source = Path.of(args[0]).toAbsolutePath().normalize(), staging = Path.of(args[1]).toAbsolutePath().normalize();
        if (staging.startsWith(source) || source.startsWith(staging))
            throw new IllegalArgumentException("The staging directory must be separate from the shipped catalogs.");
        Files.createDirectories(staging);
        Path sourceRules = source.resolve("rules");
        String recipesText, rulesetText;
        if (Files.notExists(sourceRules)) {
            recipesText = coreRecipes();
            rulesetText = coreRuleset();
        } else {
            if (!Files.isDirectory(sourceRules))
                throw new IOException("The reserved rules path is not a directory: " + sourceRules);
            recipesText = Files.readString(sourceRules.resolve("recipes.json"));
            rulesetText = Files.readString(sourceRules.resolve("ruleset.json"));
        }
        AutomationHost.compile("conversion-rules", Map.of(), recipesText, rulesetText);
        JsonNode recipes = RuleJson.parse(recipesText);
        ObjectNode report = RuleJson.JSON.createObjectNode();
        ObjectNode catalogs = report.putObject("catalogs");
        Map<String, Integer> reasons = new TreeMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        int blocks = 0, converted = 0;
        List<Path> files;
        try (var list = Files.list(source)) { files = list.filter(f -> f.toString().endsWith(".json")).sorted().toList(); }
        Map<Path, String> texts = new LinkedHashMap<>();
        for (Path file : files) {
            String text = Files.readString(file);
            if ("dmmap-daggerheart".equals(RuleJson.parse(text, RuleJson.MAX_DOCUMENT_CHARACTERS, RuleJson.MAX_DOCUMENT_NODES).path("format").asText()))
                texts.put(file, text);
        }
        Map<String, ObjectNode> shared = sharedPools(List.copyOf(texts.values()));
        for (var legacyCatalog : texts.entrySet()) {
            Path file = legacyCatalog.getKey();
            Result result = convert(legacyCatalog.getValue(), shared, recipes);
            String json = RuleJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.catalog());
            outputs.put(result.catalog().path("category").asText(), json);
            Files.writeString(staging.resolve(file.getFileName()), json);
            blocks += result.blocks();
            converted += result.converted();
            ObjectNode entry = catalogs.putObject(result.catalog().path("category").asText());
            entry.put("blocks", result.blocks()).put("converted", result.converted());
            ArrayNode diagnostics = entry.putArray("diagnostics");
            for (Diagnostic d : result.diagnostics()) {
                diagnostics.addObject().put("path", d.path()).put("source", d.sourceId()).put("message", d.message());
                reasons.merge(d.message().replaceAll(":.*", ""), 1, Integer::sum);
            }
            ObjectNode ids = entry.putObject("assignedIds");
            result.assignedIds().forEach(ids::put);
        }
        AutomationHost.compile("conversion-pack", outputs, recipesText, rulesetText);
        Path stagedRules = Files.createDirectories(staging.resolve("rules"));
        Files.writeString(stagedRules.resolve("recipes.json"), recipesText);
        Files.writeString(stagedRules.resolve("ruleset.json"), rulesetText);
        report.put("blocks", blocks).put("converted", converted);
        ObjectNode summary = report.putObject("reasons");
        reasons.forEach(summary::put);
        Files.writeString(staging.resolve("conversion-report.json"), RuleJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.printf("Converted %d of %d automation blocks (%.1f%%); report: %s%n", converted, blocks,
                blocks == 0 ? 100.0 : converted * 100.0 / blocks, staging.resolve("conversion-report.json"));
    }
}
