package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Builds a character roll from a compiled profile and the active source on the saved sheet. */
final class ProfileRoll {
    private ProfileRoll() {}

    record Modifier(JsonNode value, String sourceId, String label, boolean weapon) {}

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
        boolean attack = "attack".equals(kind);
        if (!attack && !"damage".equals(kind)) throw RuleJson.error("/request/profileRoll/kind", "Choose attack or damage.");
        JsonNode gear = source.element();
        JsonNode damage = profile.path("damage");
        if (!attack && !damage.isObject()) throw new RuleRuntime.Unavailable("profile", "This profile has no damage roll.");
        JsonNode stats = actor.path("stats");
        String trait = profile.path("trait").asText(equippedWeapon ? gear.path("trait").textValue() : null);
        ObjectNode check = event.withObject("/check");
        check.put("kind", kind).put("duality", attack).put("trait", trait).put("range", profile.path("range").asText(null))
                .put("spell", false).put("weaponSource", source.instanceId()).put("weaponSourceId", source.sourceId());
        if (equippedWeapon) {
            String slot = gear.path("slot").asText("").toLowerCase(Locale.ROOT);
            if ("primary".equals(slot) || "secondary".equals(slot)) check.put("weapon", slot);
        }
        event.put("type", "roll.prepare").put("sourceInstanceId", source.instanceId())
                .put("profileId", profile.path("id").asText()).put("label", profile.path("label").asText());
        if (stats.path("thresholds").isObject()) event.set("thresholds", stats.get("thresholds").deepCopy());
        if (!attack) {
            String type = damage.path("type").asText(equippedWeapon ? gear.path("damageType").textValue() : null);
            if ("phys".equals(type)) type = "physical";
            if ("mag".equals(type)) type = "magic";
            check.put("fixedDice", damage.has("count")).put("damageType", type);
            event.put("damageType", type);
        }
    }

    static void prepare(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                        ObjectNode actor, boolean equippedWeapon, List<Modifier> modifiers, RandomGenerator random) {
        facts(event, source, profile, kind, actor, equippedWeapon);
        JsonNode check = event.path("check"), stats = actor.path("stats"), damage = profile.path("damage");
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", java.util.UUID.randomUUID().toString());
        ArrayNode parts = plan.putArray("contributions");
        if ("attack".equals(kind)) {
            dice(parts, "hope", "Hope die", "core", 1, 12);
            dice(parts, "fear", "Fear die", "core", 1, 12);
            String trait = check.path("trait").textValue();
            if (trait != null) {
                JsonNode mod = stats.path("trait").path(trait);
                if (!mod.isNumber()) throw new RuleRuntime.Unavailable("trait", "The profile trait is not available.");
                parts.addObject().put("id", "trait").put("kind", "flat").put("role", "modifier")
                        .put("sourceId", source.sourceId()).put("label", trait).set("value", mod);
            }
        } else {
            plan.putObject("metadata").set("damageType", check.get("damageType"));
            dice(parts, "damage", profile.path("label").asText(), source.sourceId(),
                    damage.path("count").asInt(stats.path("proficiency").asInt()), damage.path("sides").intValue());
            if (damage.path("flat").asInt() != 0) parts.addObject().put("id", "bonus").put("kind", "flat")
                    .put("role", "damage").put("sourceId", source.sourceId()).put("label", profile.path("label").asText())
                    .put("value", damage.path("flat").intValue());
        }
        for (String stat : "attack".equals(kind) ? List.of("roll.action", "roll.attack") : List.of("roll.damage"))
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
        parts.addObject().put("id", role).put("kind", "dice").put("role", role).put("label", label)
                .put("sourceId", sourceId).put("count", count).put("sides", sides);
    }
}
