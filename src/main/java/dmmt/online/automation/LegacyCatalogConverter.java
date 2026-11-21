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
            "damage", "alternateProfiles", "rest", "attach");
    private static final Map<String, String> RANGES = Map.of("melee", "melee", "very close", "veryClose", "veryclose", "veryClose",
            "close", "close", "far", "far", "very far", "veryFar", "veryfar", "veryFar");
    private static final Set<String> RESOURCE_FIELDS = Set.of("id", "label", "initial", "max", "resetOn");
    private static final Set<String> OPTION_FIELDS = Set.of("id", "label", "description", "roll", "value", "dice", "advantage", "cost",
            "defaultOn", "exclusive", "requiresEffect", "excludesEffect", "weapon", "weaponRange", "excludeRolls", "effect", "uses",
            "usesSource", "endEffect", "hostOwned", "consumeItem", "quantity", "proficiency", "hopeDie", "diceResource", "condition");
    private static final Set<String> TRIGGER_FIELDS = Set.of("id", "label", "description", "on", "cost", "reroll", "when", "outcome",
            "audience", "damage", "sendRoll", "report", "requiresEffect", "excludesEffect", "weaponRange", "failedOnly", "excludeRolls", "die", "uses",
            "usesSource", "steps", "criticalOnly", "weaponAttack", "endEffect", "successIfKnown", "difficulty", "exclusiveRollGroup", "hostOwned",
            "chatBenefits", "allowSourceOwner");
    private static final Set<String> MITIGATION_FIELDS = Set.of("id", "label", "description", "when", "cost", "negate", "halve", "reduce",
            "passive", "report", "requiresEffect", "excludesEffect", "defaultOn", "effect", "uses", "usesSource", "endEffect", "hostOwned",
            "roll", "severity", "hp", "replaceHp", "armorSlots", "armorSeverity", "armorBlocked", "saveArmor", "insteadOfArmor",
            "markedHpOnly", "finalSevere", "markStress", "gainHope", "clearStress", "gainTokens", "armorCost", "followUp", "hiddenRoll", "consumeItem");
    private static final List<String> MARKS_FIELDS = List.of("severity", "hp", "replaceHp", "armorSlots", "armorSeverity", "armorBlocked", "saveArmor");
    private static final List<String> PREPARE_FIELDS = List.of("negate", "halve", "reduce");
    private static final List<String> SEVERITIES = List.of("minor", "major", "severe", "massive");
    private static final Pattern DICE = Pattern.compile("([1-9][0-9]?)d([1-9][0-9]{0,2})");
    private static final Pattern ACTION_DAMAGE = Pattern.compile("(?i)(\\d*)d(\\d+)(?:([+-])(\\d+))?");
    private static final Set<String> ACTION_FIELDS = Set.of("id", "label", "description", "cost", "steps", "report", "damage",
            "requiresEffect", "excludesEffect", "endEffect", "uses", "usesSource", "actionRoll", "target",
            "excludeSourceActor", "hostOwned", "selectAmount", "timing", "sendRoll", "chatBenefits", "allowSourceOwner", "benefitsPerAmount");
    private static final Set<String> BENEFIT_FIELDS = Set.of("id", "label", "steps", "exclusiveClaim", "featureAction", "cost", "report",
            "sourceOnly", "transferStress");
    private static final Set<String> EFFECT_FIELDS = Set.of("name", "kind", "until", "text", "modifiers",
            "actions", "rollOptions", "rollTriggers", "mitigation", "rest", "consumeLongRestMove");
    private static final Set<String> REST_FIELDS = Set.of("extraMoves", "longMovesInShort", "passives", "modifiers", "requiresEffect");
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

    public static Result convert(String legacyJson) throws IOException {
        return convert(legacyJson, Map.of());
    }

    public static Result convert(String legacyJson, Map<String, ObjectNode> sharedPools) throws IOException {
        JsonNode parsed = RuleJson.parse(legacyJson, RuleJson.MAX_DOCUMENT_CHARACTERS, RuleJson.MAX_DOCUMENT_NODES);
        if (!parsed.isObject() || !"dmmap-daggerheart".equals(parsed.path("format").asText()) || parsed.path("version").asInt() != 1)
            throw new IllegalArgumentException("Expected a format-1 dmmap-daggerheart catalog.");
        ObjectNode catalog = ((ObjectNode) parsed).deepCopy();
        catalog.put("version", RuleCompiler.FORMAT_VERSION);
        String category = catalog.path("category").asText();
        Context context = new Context();
        JsonNode recipes = RuleJson.parse(EMPTY_RECIPES);
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
            ObjectNode converted = convertBlock(node.get("automation"), path + "/automation", sourceId, problems, shared);
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

    static ObjectNode convertBlock(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems) {
        return convertBlock(legacy, path, sourceId, problems, Map.of());
    }

    /** Resources are actor-scoped, so a pool declared by one source (for example a class) can be spent by another. */
    static ObjectNode convertBlock(JsonNode legacy, String path, String sourceId, List<Diagnostic> problems,
                                   Map<String, ObjectNode> shared) {
        ObjectNode out = RuleJson.JSON.createObjectNode();
        Problems p = new Problems(sourceId, problems, new ArrayList<>());
        if (!legacy.isObject()) { p.add(path, "Automation must be an object."); return out; }
        if (legacy.has("attach")) return attachment(legacy, path, sourceId, problems, shared, p);
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
        if (legacy.has("resources")) {
            JsonNode list = legacy.get("resources");
            for (int i = 0; i < list.size(); i++) resource(list.get(i), path + "/resources/" + i, p, pools.local());
        }
        if (legacy.has("modifiers")) {
            ArrayNode modifiers = RuleJson.JSON.createArrayNode();
            JsonNode list = legacy.get("modifiers");
            for (int i = 0; i < list.size(); i++) modifier(list.get(i), path + "/modifiers/" + i, p, modifiers);
            if (!modifiers.isEmpty()) out.set("modifiers", modifiers);
        }
        ArrayNode rules = RuleJson.JSON.createArrayNode();
        ObjectNode effects = RuleJson.JSON.createObjectNode();
        Set<String> ruleIds = new HashSet<>();
        List<Nested> queue = new ArrayList<>();
        List<ObjectNode> extra = new ArrayList<>();
        for (String family : FAMILIES) {
            JsonNode list = legacy.path(family);
            for (int i = 0; i < list.size(); i++) queue.add(new Nested(family, list.get(i), path + "/" + family + "/" + i));
        }
        if (legacy.has("rest")) queue.add(new Nested("rest", legacy.get("rest"), path + "/rest"));
        for (int n = 0; n < queue.size() && n < 256; n++) {
            Nested next = queue.get(n);
            JsonNode legacyRule = next.legacy();
            if (Set.of("rest", "consumeLongRestMove").contains(next.family())) {
                List<ObjectNode> restRules = "rest".equals(next.family())
                        ? rest(legacyRule, next.path(), p, effects, pools, next.path().equals(path + "/rest") ? legacy : null, ruleIds)
                        : List.of(consumeLongRestMove(legacyRule, next.path()));
                for (ObjectNode rule : restRules) {
                    rule.put("id", unique(rule.path("id").asText(), ruleIds));
                    rules.add(rule);
                }
                queue.addAll(p.nested());
                p.nested().clear();
                continue;
            }
            boolean usesBudget = limited && legacyRule.path("usesSource").asBoolean(true);
            ObjectNode rule = switch (next.family()) {
                case "actions" -> action(legacyRule, next.path(), p, effects, pools, usesBudget);
                case "rollOptions" -> rollOption(legacyRule, next.path(), p, effects, pools, usesBudget, extra);
                case "rollTriggers", "conditionalRollTriggers" -> rollTrigger(legacyRule, next.path(), p, effects, pools, usesBudget);
                default -> mitigation(legacyRule, next.path(), p, effects, pools, usesBudget);
            };
            if (rule == null) { extra.clear(); continue; }
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
        for (JsonNode rule : rules) for (JsonNode op : rule.path("do")) {
            if (!"effect.end".equals(op.path("op").asText())) continue;
            String label = escape(op.path("effect").asText());
            String id = null;
            for (var effect : (Iterable<java.util.Map.Entry<String, JsonNode>>) effects::fields)
                if (label.equals(effect.getValue().path("label").asText())) id = effect.getKey();
            if (id == null) p.add(path, "Ending an effect requires it to be declared by the same source: " + op.path("effect").asText());
            else ((ObjectNode) op).put("effect", id);
        }
        if (legacy.has("spellcastDifficulty")) {
            ObjectNode rule = spellcastRoll(legacy, path, p, limited);
            if (rule != null) {
                rule.put("id", unique(rule.path("id").asText(), ruleIds));
                rules.add(rule);
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
        if (legacy.has("alternateProfiles")) {
            ArrayNode profiles = profiles(legacy.get("alternateProfiles"), path + "/alternateProfiles", p);
            if (!profiles.isEmpty()) out.set("profiles", profiles);
        }
        if (!pools.local().isEmpty()) out.set("pools", pools.local());
        if (!effects.isEmpty()) out.set("effects", effects);
        if (!rules.isEmpty()) out.set("rules", rules);
        else if (limited) p.add(path + "/uses", "A use limit without rules has nothing to limit.");
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
            when.addObject().put("exists", "event.check.weaponSource");
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
        reroll.putObject("not").put("exists", "event.check.reroll");
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
        java.util.function.Supplier<ObjectNode> weaponDice = () -> {
            ObjectNode where = RuleJson.JSON.createObjectNode();
            where.putArray("eq").add(read("die.sourceId")).add(read("source.id"));
            return where;
        };
        java.util.function.BiConsumer<ObjectNode, String> edit = (step, as) -> {
            step.set("from", from[0].deepCopy());
            step.put("as", as);
            from[0] = read("result." + as);
        };
        int extra = damage.path("extraDice").asInt(0), drop = damage.path("dropLowest").asInt(0);
        if (extra > 0) {
            String dice = select.apply(weaponDice.get());
            ObjectNode step = steps.addObject().put("op", "roll.addDice");
            step.set("dice", read("result." + dice));
            step.put("count", extra);
            edit.accept(step, "added");
        }
        if (drop > 0) {
            String dice = select.apply(weaponDice.get());
            ObjectNode step = steps.addObject().put("op", "roll.dropLowest");
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
            all.addObject().putArray("eq").add(read("die.sourceId")).add(read("source.id"));
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

    private static void resource(JsonNode resource, String path, Problems p, ObjectNode pools) {
        p.onlyFields(resource, path, RESOURCE_FIELDS);
        String id = resource.path("id").asText();
        if (!ID.matcher(id).matches() || pools.has(id)) { p.add(path + "/id", "Invalid or duplicate resource ID: " + id); return; }
        ObjectNode pool = pools.putObject(id).put("kind", "number").put("scope", "actor");
        if (resource.has("label")) pool.put("label", escape(resource.path("label").asText()));
        JsonNode max = resource.path("max");
        if (resource.has("max") && !(max.isInt() && max.asInt() > 0)) p.add(path + "/max", "Computed pool capacity is not supported: " + max);
        pool.put("max", resource.has("max") ? max.asInt() : 1_000_000);
        if (resource.has("initial")) {
            if (!resource.path("initial").isInt()) p.add(path + "/initial", "Expected a whole initial value.");
            pool.put("initial", resource.path("initial").asInt());
        }
        if (resource.has("resetOn")) {
            List<String> resets = RESETS.get(resource.path("resetOn").asText());
            if (resets == null) p.add(path + "/resetOn", "Unsupported reset: " + resource.path("resetOn").asText());
            else resets.forEach(pool.putArray("resetOn")::add);
        }
    }

    private static void costs(JsonNode cost, String path, Problems p, Pools pools, ObjectNode rule) {
        ArrayNode costs = RuleJson.JSON.createArrayNode();
        if (!cost.isObject()) { p.add(path, "Expected a cost object."); return; }
        cost.fields().forEachRemaining(entry -> {
            String at = path + "/" + entry.getKey();
            if (entry.getValue().isInt() && entry.getValue().asInt() == 0) return;
            JsonNode amount = amount(entry.getValue());
            if (amount == null) { p.add(at, "Expected a positive whole cost or a supported computed value."); return; }
            if ("hope".equals(entry.getKey())) costs.addObject().put("op", "resource.spend").put("pool", "actor.hope").set("amount", amount);
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
        if (ROLLS.contains(roll)) when.addObject().putArray("eq").add(read("event.check.kind")).add(roll);
        else if (roll.startsWith("trait:") && TRAITS.contains(roll.substring(6)))
            when.addObject().putArray("eq").add(read("event.check.trait")).add(roll.substring(6));
        else if ("trait".equals(roll)) when.addObject().putArray("ne").add(read("event.check.trait")).addNull();
        else if ("duality".equals(roll)) when.addObject().put("exists", "event.check.dominant");
        else if ("companion".equals(roll)) when.addObject().putArray("eq").add(read("event.check.kind")).add("companion");
        else if ("spelldamage".equals(roll)) {
            when.addObject().putArray("eq").add(read("event.check.kind")).add("damage");
            when.addObject().putArray("eq").add(read("event.check.spell")).add(true);
        } else if (roll.matches("(card|feat|featureAction):.+"))
            when.addObject().putArray("eq").add(read("event.check.origin")).add(roll);
        else if (!"any".equals(roll)) p.add(path, "Unsupported roll filter: " + roll);
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
        if (legacy.has("weaponRange")) when.addObject().putArray("eq").add(read("event.check.range")).add(legacy.path("weaponRange").asText());
        if (legacy.has("excludeRolls")) {
            for (JsonNode excluded : legacy.get("excludeRolls")) {
                if (!ROLLS.contains(excluded.asText())) { p.add(path + "/excludeRolls", "Unsupported roll filter: " + excluded.asText()); continue; }
                when.addObject().putArray("ne").add(read("event.check.kind")).add(excluded.asText());
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

    private static ObjectNode read(String path) { return RuleJson.JSON.createObjectNode().put("read", path); }

    private static ObjectNode ruleShell(JsonNode legacy, String path, Problems p, String event, String surface, String mode) {
        String id = legacy.path("id").asText();
        if (!ID.matcher(id).matches()) { p.add(path + "/id", "Invalid rule ID: " + id); return null; }
        ObjectNode rule = RuleJson.JSON.createObjectNode().put("id", id);
        rule.putArray("bindings").addObject().put("id", "use").put("event", event).put("surface", surface).put("mode", mode);
        rule.putObject("ui");
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
        String roll = option.path("roll").asText("");
        if (!roll.isEmpty()) rollCondition(roll, path + "/roll", p, when);
        String weapon = option.path("weapon").asText("");
        if (Set.of("primary", "secondary").contains(weapon)) when.addObject().putArray("eq").add(read("event.check.weapon")).add(weapon);
        else if ("any".equals(weapon)) when.addObject().put("exists", "event.check.weapon");
        else if (!weapon.isEmpty()) p.add(path + "/weapon", "Unsupported weapon filter: " + weapon);
        commonConditions(option, path, p, when);
        if (option.has("condition")) {
            JsonNode condition = option.get("condition");
            p.onlyFields(condition, path + "/condition", Set.of("unmarkedHp"));
            JsonNode range = condition.path("unmarkedHp");
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
        if (option.has("dice")) {
            var matcher = DICE.matcher(option.path("dice").asText());
            if (!matcher.matches()) p.add(path + "/dice", "Unsupported dice: " + option.path("dice").asText());
            else parts.add(times(contribution(id + "-dice", "dice", role, label)
                    .put("count", Integer.parseInt(matcher.group(1))).put("sides", Integer.parseInt(matcher.group(2))), "count", quantity));
        }
        if (option.has("advantage")) {
            String advantage = option.path("advantage").asText();
            if (!Set.of("advantage", "disadvantage").contains(advantage)) p.add(path + "/advantage", "Expected advantage or disadvantage.");
            else parts.add(contribution(id + "-" + advantage, "dice", advantage, label)
                    .put("count", 1).put("sides", 6).put("sign", "advantage".equals(advantage) ? 1 : -1));
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
        if (edited) steps.addObject().put("op", "roll.publish").set("roll", from[0]);
        if (option.has("effect")) {
            String effect = declareEffect(option.get("effect"), path + "/effect", p, effects);
            if (effect != null) steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        }
        if (proficiency > 0 && "attack".equals(roll)) carriedProficiency(option, label, proficiency, effects, steps, extra);
        endEffect(option, steps);
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
        when.addObject().put("exists", "event.check.weaponSourceId");
        when.addObject().putArray("eq").add(read("event.check.fixedDice")).add(false);
    }

    /** Rolls {@code count} more of the attacking weapon's base damage dice; returns the edited roll reference. */
    private static JsonNode addWeaponDice(ArrayNode steps, JsonNode from, int count) {
        ObjectNode where = RuleJson.JSON.createObjectNode();
        where.putArray("eq").add(read("die.sourceId")).add(read("event.check.weaponSourceId"));
        ObjectNode select = steps.addObject().put("op", "roll.selectDice");
        select.set("from", from);
        select.set("where", where);
        select.put("as", "weaponDice");
        ObjectNode add = steps.addObject().put("op", "roll.addDice");
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
        Set<String> used = new HashSet<>();
        effects.fieldNames().forEachRemaining(used::add);
        String effect = unique(slug(option.path("id").asText() + "-proficiency"), used);
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
        weaponDiceConditions(when);
        when.add(active.deepCopy());
        applyWhen(damage, when);
        ArrayNode damageSteps = damage.putArray("do");
        JsonNode roll = addWeaponDice(damageSteps, read("event.roll"), proficiency);
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
        ArrayNode when = RuleJson.JSON.createArrayNode();
        if ("spellcast".equals(trigger.path("damage").path("count").asText()))
            when.addObject().put("exists", "actor.spellcastTrait.value");
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
        if (trigger.path("weaponAttack").asBoolean(false)) when.addObject().put("exists", "event.check.weapon");
        commonConditions(trigger, path, p, when);
        sentRollConditions(trigger, when);
        applyWhen(rule, when);
        if (trigger.has("cost")) costs(trigger.get("cost"), path + "/cost", p, pools, rule);
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = RuleJson.JSON.createArrayNode();
        JsonNode from = read("event.roll");
        boolean edited = false;
        if (trigger.has("reroll")) {
            String reroll = trigger.path("reroll").asText();
            ObjectNode where = RuleJson.JSON.createObjectNode();
            if ("hope".equals(reroll)) where.putArray("eq").add(read("die.role")).add("hope");
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
        if (edited) steps.addObject().put("op", "roll.publish").set("roll", from);
        if (trigger.has("damage"))
            actionDamage(trigger.get("damage"), path + "/damage", trigger, p, steps, read("event.check.critical"), "triggerDamage");
        if (trigger.has("sendRoll")) sentRoll(trigger.get("sendRoll"), path + "/sendRoll", trigger, p, steps);
                    JsonNode extra = trigger.path("steps");
        for (int i = 0; i < extra.size(); i++) step(extra.get(i), path + "/steps/" + i, p, steps, effects, pools);
        endEffect(trigger, steps);
        if (!steps.isEmpty()) rule.set("do", steps);
        ObjectNode ui = labels(trigger, path, p, rule);
        report(trigger, path, p, ui);
        if (trigger.has("damage"))
            ui.put("report", "Damage total: {result.triggerDamage.total}." + (ui.has("report") ? " " + ui.path("report").asText() : ""));
        sentRollReport(trigger, ui);
        return rule;
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
        JsonNode roll = mitigation.path("roll");
        boolean rolled = mitigation.has("roll"), rollHits = rolled && roll.has("success");
        boolean marks = MARKS_FIELDS.stream().anyMatch(mitigation::has);
        boolean prepare = PREPARE_FIELDS.stream().anyMatch(mitigation::has) || (rolled && !rollHits);
        if (marks && prepare) { p.add(path, "A damage option cannot change both the damage amount and the marks plan."); return null; }
        if (rollHits && !marks) { p.add(path + "/roll", "Success rolls need a severity, Hit Point or saved Armor change to scale."); return null; }
        String stage = marks ? "marks" : prepare ? "prepare" : "applied";
        boolean passive = mitigation.path("passive").asBoolean(false);
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
        applyWhen(rule, when);
        if (mitigation.has("cost")) {
            if ("marks".equals(stage) && (mitigation.get("cost").has("hp") || mitigation.get("cost").has("armor")))
                p.add(path + "/cost", "Marks-stage options cannot pay with the tracks the damage is about to mark.");
            costs(mitigation.get("cost"), path + "/cost", p, pools, rule);
        }
        if (limited) rule.put("limitRef", "uses");
        if (mitigation.has("defaultOn")) {
            ObjectNode binding = (ObjectNode) rule.path("bindings").get(0);
            binding.put("mode", "option").put("defaultOn", mitigation.path("defaultOn").asBoolean());
        }
        ArrayNode steps = rule.putArray("do");
        String id = slug(mitigation.path("id").asText()), label = escape(mitigation.path("label").asText(""));
        switch (stage) {
            case "prepare" -> prepareSteps(mitigation, path, p, steps, id, label);
            case "marks" -> marksSteps(mitigation, path, p, steps, id, label);
            default -> {}
        }
        appliedSteps(mitigation, path, p, pools, steps, stage);
        if (mitigation.has("effect")) {
            String effect = declareEffect(mitigation.get("effect"), path + "/effect", p, effects);
            if (effect != null) steps.addObject().put("op", "effect.start").put("effect", effect).put("target", "actor");
        }
        endEffect(mitigation, steps);
        ObjectNode ui = labels(mitigation, path, p, rule);
        report(mitigation, path, p, ui);
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

    private static void prepareSteps(JsonNode mitigation, String path, Problems p, ArrayNode steps, String id, String label) {
        int kinds = (mitigation.path("negate").asBoolean(false) ? 1 : 0) + (mitigation.path("halve").asBoolean(false) ? 1 : 0)
                + (mitigation.has("reduce") ? 1 : 0) + (mitigation.has("roll") ? 1 : 0);
        if (kinds != 1) { p.add(path, "Expected exactly one of negate, halve, reduce or a rolled reduction."); return; }
        JsonNode amount;
        if (mitigation.path("negate").asBoolean(false)) amount = read("event.damage.amount");
        else if (mitigation.path("halve").asBoolean(false)) {
            ObjectNode half = RuleJson.JSON.createObjectNode();
            half.putArray("divide").add(read("event.damage.amount")).add(2);
            amount = RuleJson.JSON.createObjectNode().set("floor", half);
        } else if (mitigation.has("roll")) {
            JsonNode count = rollCount(mitigation.get("roll"), path + "/roll", p, false);
            if (count == null) return;
            steps.addObject().put("op", "dice.roll").put("sides", mitigation.get("roll").path("sides").asInt()).put("as", "reduction").set("count", count);
            amount = read("result.reduction");
        } else {
            amount = amount(mitigation.path("reduce"));
            if (amount == null) { p.add(path + "/reduce", "Expected a positive whole reduction or a supported computed value."); return; }
        }
        ObjectNode reduce = steps.addObject().put("op", "damage.reduce");
        reduce.set("from", read("event.damage"));
        reduce.set("amount", amount);
        reduce.put("id", id).put("label", label).put("as", "reduced");
        steps.addObject().put("op", "damage.publish").set("damage", read("result.reduced.damage"));
    }

    private static JsonNode rollCount(JsonNode roll, String path, Problems p, boolean marks) {
        p.onlyFields(roll, path, Set.of("count", "sides", "success", "each"));
        int sides = roll.path("sides").asInt();
        if (!roll.path("sides").isInt() || sides < 2 || sides > 1000) { p.add(path + "/sides", "Expected 2 to 1000 sides."); return null; }
        if (roll.has("success") && !(roll.path("success").isInt() && roll.path("success").asInt() >= 1 && roll.path("success").asInt() <= sides)) {
            p.add(path + "/success", "Expected a face between 1 and the number of sides."); return null;
        }
        JsonNode count = roll.path("count");
        if (!roll.has("count")) return RuleJson.JSON.getNodeFactory().numberNode(1);
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
    private static void marksSteps(JsonNode mitigation, String path, Problems p, ArrayNode steps, String id, String label) {
        JsonNode scale = null;
        if (mitigation.has("roll")) {
            JsonNode roll = mitigation.get("roll");
            JsonNode count = rollCount(roll, path + "/roll", p, true);
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
        if (!legacy.has("report")) return;
        JsonNode report = legacy.get("report");
        String text = report.isTextual() ? escape(report.asText()) : null;
        if (report.isObject()) {
            p.onlyFields(report, path + "/report", Set.of("text", "amount"));
            text = escape(report.path("text").asText(""));
            if (report.has("amount")) {
                JsonNode amount = report.get("amount");
                JsonNode value = amount.isInt() ? null : computed(amount.asText());
                if (amount.isInt()) text = text.replace("{{amount}}", amount.asText());
                else if (value != null && value.has("read")) text = text.replace("{{amount}}", "{" + value.path("read").asText() + "}");
                else p.add(path + "/report/amount", "Report amounts must be whole numbers or a directly projected value.");
            }
        }
        if (text == null || text.isBlank()) p.add(path + "/report", "Report text is missing.");
        else ui.put("report", text);
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

    private static ObjectNode action(JsonNode action, String path, Problems p, ObjectNode effects, Pools pools, boolean limited) {
        p.onlyFields(action, path, ACTION_FIELDS);
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
            else binding.put("id", "downtime").put("event", "downtime.move").put("surface", "downtime");
        }
        String operationTarget = "actor";
        if (action.has("target")) {
            if (!action.path("target").isTextual()) p.add(path + "/target", "Expected a supported target mode.");
            else {
                String[] target = switch (action.path("target").asText()) {
                    case "actor" -> new String[]{"one", "connectedPlayerCharacters"};
                    case "anyActor" -> new String[]{"one", "activeCharacters"};
                    case "party" -> new String[]{"all", "connectedPlayerCharacters"};
                    case "allActors" -> new String[]{"all", "activePlayerCharacters"};
                    default -> null;
                };
                if (target == null) p.add(path + "/target", "Unsupported target mode: " + action.path("target").asText());
                else {
                    ObjectNode selection = binding.putObject("targetSelection");
                    selection.put("cardinality", target[0]).put("scope", target[1]);
                    if (action.has("excludeSourceActor") && !action.path("excludeSourceActor").isBoolean())
                        p.add(path + "/excludeSourceActor", "Expected a boolean.");
                    selection.put("excludeSource", action.path("excludeSourceActor").asBoolean(false));
                    operationTarget = "recipient";
                }
            }
        } else if (action.has("excludeSourceActor") && !action.has("chatBenefits") && !action.has("benefitsPerAmount"))
            p.add(path + "/excludeSourceActor", "Source exclusion requires a target mode or chat benefits.");
        if (action.has("hostOwned") && !action.path("hostOwned").isBoolean())
            p.add(path + "/hostOwned", "Expected a boolean.");
        ArrayNode when = RuleJson.JSON.createArrayNode();
        commonConditions(action, path, p, when);
        JsonNode damage = action.path("damage");
        boolean usesSpellcastTrait = "spellcast".equals(action.path("actionRoll").path("trait").asText())
                || "spellcast".equals(damage.path("count").asText());
        if (usesSpellcastTrait)
            when.addObject().put("exists", "actor.spellcastTrait.value");
        sentRollConditions(action, when);
        applyWhen(rule, when);
        if (action.has("cost")) costs(action.get("cost"), path + "/cost", p, pools, rule);
        if (action.has("selectAmount")) selectAmount(action.get("selectAmount"), path + "/selectAmount", p, pools, rule);
        if (limited) rule.put("limitRef", "uses");
        ArrayNode steps = RuleJson.JSON.createArrayNode();
        if (action.has("actionRoll")) actionRoll(action.get("actionRoll"), path + "/actionRoll", action, p, steps);
        if (action.has("damage")) actionDamage(action.get("damage"), path + "/damage", action, p, steps);
        if (action.has("sendRoll")) sentRoll(action.get("sendRoll"), path + "/sendRoll", action, p, steps);
        JsonNode list = action.path("steps");
        for (int i = 0; i < list.size(); i++) step(list.get(i), path + "/steps/" + i, p, steps, effects, pools, operationTarget);
        endEffect(action, steps, operationTarget);
        if (!steps.isEmpty()) rule.set("do", steps);
        ObjectNode ui = rule.putObject("ui");
        ui.put("label", escape(label));
        if (action.has("description")) ui.put("description", escape(action.path("description").asText()));
        if (!action.has("report")) {
            if (action.has("actionRoll") && action.has("damage"))
                ui.put("report", "Roll total: {result.actionRoll.total}. Damage total: {result.actionDamage.total}.");
            else if (action.has("actionRoll")) ui.put("report", "Roll total: {result.actionRoll.total}.");
            else if (action.has("damage")) ui.put("report", "Damage total: {result.actionDamage.total}.");
        }
        report(action, path, p, ui);
        sentRollReport(action, ui);
        return rule;
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
    /** How much of a named resource the actor can currently pay: Hope, free track boxes or a pool's value; null if unknown. */
    private static JsonNode available(String resource, Pools pools) {
        if ("hope".equals(resource)) return read("actor.hope.value");
        if (TRACKS.containsKey(resource)) {
            ObjectNode free = RuleJson.JSON.createObjectNode();
            free.putArray("subtract").add(read(TRACKS.get(resource) + ".capacity")).add(read(TRACKS.get(resource) + ".marked"));
            return free;
        }
        return pools.has(resource) ? read("actor.pools." + resource + ".value") : null;
    }

    /** A player-chosen whole amount, bounded by what the named resource can currently pay; {@code @amount} reads it. */
    private static void selectAmount(JsonNode legacy, String path, Problems p, Pools pools, ObjectNode rule) {
        p.onlyFields(legacy, path, Set.of("resource", "label", "min", "heal"));
        String resource = legacy.path("resource").asText();
        boolean heal = legacy.path("heal").asBoolean(false);
        if (legacy.has("heal") && !legacy.path("heal").isBoolean()) p.add(path + "/heal", "Expected a boolean.");
        if (legacy.has("min") && (!legacy.path("min").isInt() || legacy.path("min").asInt() < 0))
            p.add(path + "/min", "Expected a nonnegative whole minimum.");
        JsonNode max;
        if (heal) max = RuleJson.JSON.getNodeFactory().numberNode(1_000_000);
        else max = available(resource, pools);
        if (max == null) { p.add(path + "/resource", "Amounts need a known resource: " + resource); return; }
        ObjectNode input = rule.putArray("inputs").addObject().put("id", "amount").put("kind", "integer")
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

    private static void actionDamage(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps) {
        actionDamage(legacy, path, action, p, steps, null, "actionDamage");
    }

    /**
     * A separate damage roll. With {@code critical}, a critical success adds the maximum result of the damage dice as a
     * flat contribution, matching the Daggerheart critical damage rule.
     */
    private static void actionDamage(JsonNode legacy, String path, JsonNode action, Problems p, ArrayNode steps, JsonNode critical, String as) {
        p.onlyFields(legacy, path, Set.of("dice", "type", "fixed", "count"));
        String diceText = legacy.path("dice").asText("");
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
        if (spellcastCount) dice.set("count", spellcastDiceCount(fixedCount));
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
        JsonNode value = clearAllTrack || clearAllPool ? null : amount(amount);
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
        if (value == null && !clearAllTrack && !clearAllPool) {
            p.add(path + "/amount", "Computed amounts need an explicit expression: " + amount);
            return;
        }
        switch (op) {
            case "clear", "mark" -> {
                if (TRACKS.containsKey(resource)) {
                    String track = TRACKS.get(resource).replaceFirst("^actor\\.", operationTarget + ".");
                    ObjectNode converted = out.addObject().put("op", "track." + op).put("track", track);
                    if (value != null) converted.set("amount", value);
                    else converted.putObject("amount").put("read", track + ".marked");
                } else if ("clear".equals(op) && clearAllPool) {
                    ObjectNode converted = out.addObject().put("op", "pool.spend").put("pool", resource);
                    if ("recipient".equals(operationTarget)) converted.put("owner", "recipient");
                    converted.set("amount", read(operationTarget + ".pools." + resource + ".value"));
                } else p.add(path + "/resource", "Unsupported track: " + resource);
            }
            case "gain" -> {
                if ("hope".equals(resource)) out.addObject().put("op", "resource.gain").put("pool", operationTarget + ".hope").set("amount", value);
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
            for (int i = 0; i < list.size(); i++) modifier(list.get(i), path + "/modifiers/" + i, p, modifiers);
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
            "actor.stats.tier", "actor.stats.proficiency", "actor.stats.armorScore", "actor.stats.armorAvailable");

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
            case "amount" -> read("input.amount");
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

    private static void modifier(JsonNode modifier, String path, Problems p, ArrayNode out) {
        JsonNode value = signed(modifier.path("value"));
        if (value == null) { p.add(path + "/value", "Expected a whole-number modifier or a supported computed value."); return; }
        List<String> stats = new ArrayList<>();
        if (modifier.has("roll")) {
            p.onlyFields(modifier, path, Set.of("roll", "value"));
            String roll = modifier.path("roll").asText();
            if (ROLLS.contains(roll)) stats.add("roll." + roll);
            else p.add(path + "/roll", "Unsupported roll modifier: " + roll);
        } else {
            p.onlyFields(modifier, path, Set.of("stat", "value"));
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
        for (String stat : stats) out.addObject().put("stat", stat).set("value", value.deepCopy());
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
                resource(resource, "", new Problems("", new ArrayList<>(), new ArrayList<>()), pools);
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
        JsonNode passives = rest.path("passives");
        if (rest.has("passives") && (!passives.isArray() || passives.size() > 16)) p.add(path + "/passives", "Expected up to 16 rest passives.");
        else for (int i = 0; i < passives.size(); i++)
            out.addAll(restPassive(passives.get(i), path + "/passives/" + i, p, effects, pools, block, effectGate, ruleIds));
        JsonNode modifiers = rest.path("modifiers");
        if (rest.has("modifiers") && (!modifiers.isArray() || modifiers.size() > 16)) p.add(path + "/modifiers", "Expected up to 16 move modifiers.");
        else for (int i = 0; i < modifiers.size(); i++) {
            ObjectNode rule = moveModifier(modifiers.get(i), path + "/modifiers/" + i, p, effects, pools, effectGate);
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
            else when.addObject().putArray("gte").add(read("actor.hope.value")).add(passive.path("minHope").asInt());
        }
        if (passive.has("condition")) {
            String condition = passive.path("condition").asText();
            switch (condition) {
                case "armorMarked" -> when.addObject().putArray("gt").add(read("actor.armorTrack.marked")).add(0);
                case "noArmorMarked" -> when.addObject().putArray("eq").add(read("actor.armorTrack.marked")).add(0);
                case "noRecovery" -> {
                    if (prepare || move) p.add(path + "/condition", "Recovery is only known after the rest moves.");
                    ObjectNode cleared = RuleJson.JSON.createObjectNode();
                    cleared.putArray("add").add(read("event.rest.cleared.hitPoints")).add(read("event.rest.cleared.stress"));
                    when.addObject().putArray("eq").add(cleared).add(0);
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
            out.add(claim);
            return out;
        }
        for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, steps, effects, pools);
        if (steps.isEmpty() && !rule.has("costs")) p.add(path, "A rest passive needs an effect.");
        return out;
    }

    private static ObjectNode moveModifier(JsonNode modifier, String path, Problems p, ObjectNode effects, Pools pools, ArrayNode effectGate) {
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
        for (String field : List.of("bonus", "repairAllies")) {
            if (!modifier.has(field)) continue;
            JsonNode by = signed(modifier.path(field));
            if (by == null) p.add(path + "/" + field, "Expected a whole number.");
            else steps.add(planAdjust("event.move", "bonus".equals(field) ? "bonus" : "allies", by,
                    "bonus".equals(field) ? "Move bonus" : "Additional allies"));
        }
        JsonNode list = modifier.path("steps");
        for (int s = 0; s < list.size(); s++) step(list.get(s), path + "/steps/" + s, p, steps, effects, pools);
        if (modifier.has("note")) ((ObjectNode) rule.get("ui")).put("report", escape(modifier.path("note").asText()));
        if (steps.isEmpty()) p.add(path, "A move modifier needs a bonus, ally count or steps.");
        return rule;
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
            Result result = convert(legacyCatalog.getValue(), shared);
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
        Files.writeString(staging.resolve("rules.json"), EMPTY_RECIPES);
        for (String output : outputs.values()) RuleCatalog.compile(output, EMPTY_RECIPES);
        report.put("blocks", blocks).put("converted", converted);
        ObjectNode summary = report.putObject("reasons");
        reasons.forEach(summary::put);
        Files.writeString(staging.resolve("conversion-report.json"), RuleJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.printf("Converted %d of %d automation blocks (%.1f%%); report: %s%n", converted, blocks,
                blocks == 0 ? 100.0 : converted * 100.0 / blocks, staging.resolve("conversion-report.json"));
    }
}
