package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a character roll from a compiled profile and the active source on the saved sheet. */
final class ProfileRoll {
    private static final Pattern DAMAGE = Pattern.compile("(?i)^(\\d*d\\d+)(\\+\\d*d\\d+)*([+-]\\d+)?$");
    private static final Pattern DAMAGE_DIE = Pattern.compile("(?i)(\\d*)d(\\d+)");
    private ProfileRoll() {}

    record Modifier(JsonNode value, String sourceId, String label, boolean weapon) {}
    private record WeaponDamage(List<int[]> pools, int flat) {}

    static List<String> kinds(JsonNode profile) {
        return profile.has("rollKind") ? List.of(profile.path("rollKind").asText())
                : profile.has("damage") ? List.of("attack", "damage") : List.of("attack");
    }

    static ObjectNode weaponProfile(SheetProjection.SourceElement source, JsonNode declaredProfiles) {
        JsonNode weapon = source.element();
        String id = "weapon.base";
        while (hasProfile(declaredProfiles, id)) id += ".base";
        String name = RuleJson.text(weapon.get("name"), "/weapon/name");
        String trait = RuleJson.text(weapon.get("trait"), "/weapon/trait");
        if (!List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge").contains(trait))
            throw RuleJson.error("/weapon/trait", "Unknown weapon trait.");
        String range = switch (RuleJson.text(weapon.get("range"), "/weapon/range").replaceAll("\\s+", "").toLowerCase(Locale.ROOT)) {
            case "melee" -> "melee";
            case "veryclose" -> "veryClose";
            case "close" -> "close";
            case "far" -> "far";
            case "veryfar" -> "veryFar";
            default -> throw RuleJson.error("/weapon/range", "Unknown weapon range.");
        };
        String type = switch (RuleJson.text(weapon.get("damageType"), "/weapon/damageType").toLowerCase(Locale.ROOT)) {
            case "phys", "physical" -> "physical";
            case "mag", "magic" -> "magic";
            default -> throw RuleJson.error("/weapon/damageType", "Expected physical or magic weapon damage.");
        };
        if (weapon.has("fixedDice") && !weapon.get("fixedDice").isBoolean())
            throw RuleJson.error("/weapon/fixedDice", "Expected a boolean.");
        ObjectNode profile = RuleJson.JSON.createObjectNode().put("id", id).put("label", name)
                .put("trait", trait).put("range", range);
        profile.putObject("damage").put("formula", RuleJson.text(weapon.get("damage"), "/weapon/damage"))
                .put("fixedDice", weapon.path("fixedDice").asBoolean()).put("type", type);
        return profile;
    }

    private static boolean hasProfile(JsonNode profiles, String id) {
        for (JsonNode profile : profiles) if (id.equals(profile.path("id").asText())) return true;
        return false;
    }

    private static WeaponDamage weaponDamage(JsonNode damage, int proficiency) {
        String expression = RuleJson.text(damage.get("formula"), "/weapon/damage").replaceAll("\\s+", "");
        if (expression.length() > 256 || !DAMAGE.matcher(expression).matches())
            throw RuleJson.error("/weapon/damage", "Expected dice pools such as d8+d6+2.");
        boolean fixed = damage.path("fixedDice").booleanValue();
        if (!fixed && (proficiency < 1 || proficiency > 1024))
            throw RuleJson.error("/weapon/damage", "Weapon Proficiency must be between 1 and 1024.");
        List<int[]> pools = new ArrayList<>();
        Matcher matcher = DAMAGE_DIE.matcher(expression);
        int end = 0, total = 0;
        while (matcher.find()) {
            if (pools.size() >= 32) throw RuleJson.error("/weapon/damage", "Too many weapon dice pools.");
            int count = parseWeaponNumber(matcher.group(1).isEmpty() ? "1" : matcher.group(1), 1, 1024);
            int sides = parseWeaponNumber(matcher.group(2), 2, 1000);
            int actual = fixed ? count : proficiency;
            total += actual;
            if (total > 1024) throw RuleJson.error("/weapon/damage", "Weapon damage exceeds its dice limit.");
            pools.add(new int[]{actual, sides});
            end = matcher.end();
        }
        int flat = end == expression.length() ? 0 : parseWeaponNumber(expression.substring(end), -1_000_000, 1_000_000);
        return new WeaponDamage(pools, flat);
    }

    private static int parseWeaponNumber(String text, int min, int max) {
        try {
            return RuleJson.integer(RuleJson.JSON.getNodeFactory().numberNode(Long.parseLong(text)), "/weapon/damage", min, max);
        } catch (NumberFormatException ex) {
            throw RuleJson.error("/weapon/damage", "Weapon damage contains an out-of-range number.");
        }
    }

    static ObjectNode event(String type, String actorId, int fear) {
        ObjectNode event = RuleJson.JSON.createObjectNode().put("type", type).put("actorId", actorId);
        event.putObject("table").put("fear", fear);
        event.putObject("thresholds").putNull("major").putNull("severe");
        event.putNull("damage").putNull("damageType").putNull("roll");
        ObjectNode check = event.putObject("check");
        for (String key : List.of("kind", "trait", "weapon", "range", "dominant", "outcome", "critical",
                "hopeDie", "fearDie", "spell", "origin", "total", "difficulty", "weaponSource", "weaponSourceId",
                "fixedDice", "damageType", "move", "reroll", "duality")) check.putNull(key);
        return event;
    }

    static void facts(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                      ObjectNode actor, boolean equippedWeapon) {
        boolean damageRoll = "damage".equals(kind);
        if (!kinds(profile).contains(kind))
            throw RuleJson.error("/request/profileRoll/kind", "This roll kind is not available for the selected profile.");
        JsonNode gear = source.element();
        JsonNode damage = profile.path("damage");
        if (damageRoll && !damage.isObject()) throw new RuleRuntime.Unavailable("profile", "This profile has no damage roll.");
        JsonNode stats = actor.path("stats");
        String declaredTrait = profile.path("trait").asText(equippedWeapon && !profile.has("rollKind")
                ? gear.path("trait").textValue() : null);
        String trait = "spellcast".equals(declaredTrait) ? actor.path("spellcastTrait").path("name").textValue() : declaredTrait;
        ObjectNode check = event.withObject("/check");
        check.put("kind", kind).put("duality", !damageRoll).put("trait", trait).put("range", profile.path("range").asText(null))
                .put("spell", "spellcast".equals(kind));
        if (!profile.has("rollKind"))
            check.put("weaponSource", source.instanceId()).put("weaponSourceId", source.sourceId());
        if (equippedWeapon && !profile.has("rollKind")) {
            String slot = gear.path("slot").asText("").toLowerCase(Locale.ROOT);
            if ("primary".equals(slot) || "secondary".equals(slot)) check.put("weapon", slot);
        }
        if (profile.has("difficulty")) check.set("difficulty", profile.get("difficulty").deepCopy());
        if (profile.has("origin")) check.set("origin", profile.get("origin").deepCopy());
        event.put("type", "roll.prepare").put("sourceInstanceId", source.instanceId())
                .put("profileId", profile.path("id").asText()).put("label", profile.path("label").asText());
        if (stats.path("thresholds").isObject()) event.set("thresholds", stats.get("thresholds").deepCopy());
        if (damageRoll) {
            String type = damage.path("type").asText(equippedWeapon ? gear.path("damageType").textValue() : null);
            if ("phys".equals(type)) type = "physical";
            if ("mag".equals(type)) type = "magic";
            check.put("fixedDice", damage.has("count") || damage.path("fixedDice").asBoolean()).put("damageType", type);
            event.put("damageType", type);
        }
    }

    static void prepare(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                        ObjectNode actor, boolean equippedWeapon, List<Modifier> modifiers, RandomGenerator random) {
        facts(event, source, profile, kind, actor, equippedWeapon);
        JsonNode check = event.path("check"), stats = actor.path("stats"), damage = profile.path("damage");
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", java.util.UUID.randomUUID().toString());
        ArrayNode parts = plan.putArray("contributions");
        if (!"damage".equals(kind)) {
            dice(parts, "hope", "Hope die", "core", 1, 12);
            dice(parts, "fear", "Fear die", "core", 1, 12);
            String trait = check.path("trait").textValue();
            if ("spellcast".equals(profile.path("trait").asText()) && trait == null)
                throw new RuleRuntime.Unavailable("trait", "Choose a Spellcast trait before making this roll.");
            if (trait != null) {
                JsonNode mod = "spellcast".equals(profile.path("trait").asText())
                        ? stats.path("spellcast").path("total") : stats.path("trait").path(trait);
                if (!mod.isNumber()) throw new RuleRuntime.Unavailable("trait", "The profile trait is not available.");
                parts.addObject().put("id", "trait").put("kind", "flat").put("role", "modifier")
                        .put("sourceId", source.sourceId()).put("label", trait).set("value", mod);
            }
            if (profile.has("difficulty")) plan.putObject("metadata").set("difficulty", profile.get("difficulty").deepCopy());
        } else {
            ObjectNode metadata = plan.putObject("metadata");
            metadata.set("damageType", check.get("damageType"));
            int flat;
            if (damage.has("formula")) {
                WeaponDamage base = weaponDamage(damage, stats.path("proficiency").asInt());
                for (int[] pool : base.pools())
                    dice(parts, parts.isEmpty() ? "damage" : "damage." + (parts.size() + 1), "damage",
                            profile.path("label").asText(), source.sourceId(), pool[0], pool[1]);
                flat = base.flat();
            } else {
                dice(parts, "damage", profile.path("label").asText(), source.sourceId(),
                        damage.path("count").asInt(stats.path("proficiency").asInt()), damage.path("sides").intValue());
                flat = damage.path("flat").asInt();
            }
            ArrayNode baseIds = metadata.putArray("weaponBaseContributions");
            for (JsonNode part : parts) baseIds.add(part.path("id").asText());
            if (flat != 0) parts.addObject().put("id", "bonus").put("kind", "flat")
                    .put("role", "damage").put("sourceId", source.sourceId()).put("label", profile.path("label").asText())
                    .put("value", flat);
        }
        List<String> statsForRoll = switch (kind) {
            case "attack" -> List.of("roll.action", "roll.attack");
            case "damage" -> List.of("roll.damage");
            case "action" -> List.of("roll.action");
            case "reaction" -> List.of("roll.reaction");
            case "spellcast" -> List.of("roll.action", "roll.spellcast");
            default -> throw RuleJson.error("/request/profileRoll/kind", "Unknown profile roll kind.");
        };
        for (String stat : statsForRoll)
            addModifiers(parts, modifiers, stat, source.instanceId(), kind);
        event.set("roll", StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), random));
        updateCheck(event);
    }

    private static void addModifiers(ArrayNode parts, List<Modifier> modifiers, String stat, String sourceInstanceId,
                                     String kind) {
        Modifier set = null, floor = null, ceiling = null;
        double value = 0;
        for (Modifier modifier : modifiers) {
            JsonNode item = modifier.value();
            if (!stat.equals(item.path("stat").asText())
                    || modifier.weapon() && !sourceInstanceId.equals(modifier.sourceId())) continue;
            double amount = RuleExpression.number(item.get("value"), "/modifiers/value");
            switch (item.path("op").asText("add")) {
                case "add" -> {
                    value += amount;
                    addModifier(parts, modifier, kind, amount);
                }
                case "set" -> set = modifier;
                case "atLeast" -> {
                    if (floor == null || amount > floor.value().path("value").asDouble()) floor = modifier;
                }
                case "atMost" -> {
                    if (ceiling == null || amount < ceiling.value().path("value").asDouble()) ceiling = modifier;
                }
                default -> throw RuleJson.error("/modifiers/op", "Unknown modifier operation.");
            }
        }
        if (set != null) {
            double amount = set.value().path("value").asDouble();
            value += amount;
            addModifier(parts, set, kind, amount);
        }
        if (floor != null) {
            double adjusted = Math.max(value, floor.value().path("value").asDouble());
            addModifier(parts, floor, kind, adjusted - value);
            value = adjusted;
        }
        if (ceiling != null) {
            double adjusted = Math.min(value, ceiling.value().path("value").asDouble());
            addModifier(parts, ceiling, kind, adjusted - value);
        }
    }

    private static void addModifier(ArrayNode parts, Modifier modifier, String kind, double value) {
        if (value == 0) return;
        parts.addObject().put("id", "modifier." + parts.size()).put("kind", "flat")
                .put("role", "damage".equals(kind) ? "damage" : "modifier")
                .put("sourceId", modifier.sourceId()).put("label", modifier.label())
                .set("value", RuleExpression.numeric(value, "/modifiers/value"));
    }

    static void updateCheck(ObjectNode event) {
        JsonNode roll = event.path("roll");
        ObjectNode check = (ObjectNode) event.path("check");
        check.putNull("hopeDie").putNull("fearDie").setAll(StructuredRoll.check(roll));
        if ("damage".equals(check.path("kind").asText())) {
            String type = roll.path("metadata").path("damageType").asText(null);
            event.put("damageType", type);
            check.put("damageType", type);
        }
    }

    private static void dice(ArrayNode parts, String role, String label, String sourceId, int count, int sides) {
        dice(parts, role, role, label, sourceId, count, sides);
    }

    private static void dice(ArrayNode parts, String id, String role, String label, String sourceId, int count, int sides) {
        parts.addObject().put("id", id).put("kind", "dice").put("role", role).put("label", label)
                .put("sourceId", sourceId).put("count", count).put("sides", sides);
    }
}
