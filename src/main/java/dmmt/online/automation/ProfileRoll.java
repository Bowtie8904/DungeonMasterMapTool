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

    static ObjectNode event(String type, String actorId, int fear) {
        ObjectNode event = RuleJson.JSON.createObjectNode().put("type", type).put("actorId", actorId);
        event.putObject("table").put("fear", fear);
        event.putObject("thresholds").putNull("major").putNull("severe");
        event.putNull("damage").putNull("damageType").putNull("roll");
        ObjectNode check = event.putObject("check");
        for (String key : List.of("kind", "trait", "weapon", "range", "dominant", "outcome", "critical",
                "hopeDie", "fearDie", "spell", "origin", "total", "difficulty", "weaponSource", "weaponSourceId",
                "fixedDice", "damageType", "move", "reroll")) check.putNull(key);
        return event;
    }

    static void prepare(ObjectNode event, SheetProjection.SourceElement source, JsonNode profile, String kind,
                        ObjectNode actor, boolean equippedWeapon, RandomGenerator random) {
        boolean attack = "attack".equals(kind);
        if (!attack && !"damage".equals(kind)) throw RuleJson.error("/request/profileRoll/kind", "Choose attack or damage.");
        JsonNode gear = source.element();
        JsonNode damage = profile.path("damage");
        if (!attack && !damage.isObject()) throw new RuleRuntime.Unavailable("profile", "This profile has no damage roll.");
        JsonNode stats = actor.path("stats");
        String trait = profile.path("trait").asText(equippedWeapon ? gear.path("trait").textValue() : null);
        ObjectNode check = (ObjectNode) event.path("check");
        check.put("kind", kind).put("trait", trait).put("range", profile.path("range").asText(null))
                .put("spell", false).put("weaponSource", source.instanceId()).put("weaponSourceId", source.sourceId());
        if (equippedWeapon) {
            String slot = gear.path("slot").asText("").toLowerCase(Locale.ROOT);
            if ("primary".equals(slot) || "secondary".equals(slot)) check.put("weapon", slot);
        }
        event.put("type", "roll.prepare").put("sourceInstanceId", source.instanceId())
                .put("profileId", profile.path("id").asText()).put("label", profile.path("label").asText());
        if (stats.path("thresholds").isObject()) event.set("thresholds", stats.get("thresholds").deepCopy());
        ObjectNode plan = RuleJson.JSON.createObjectNode().put("id", java.util.UUID.randomUUID().toString());
        ArrayNode parts = plan.putArray("contributions");
        if (attack) {
            dice(parts, "hope", "Hope die", "core", 1, 12);
            dice(parts, "fear", "Fear die", "core", 1, 12);
            if (trait != null) {
                JsonNode mod = stats.path("trait").path(trait);
                if (!mod.isNumber()) throw new RuleRuntime.Unavailable("trait", "The profile trait is not available.");
                parts.addObject().put("id", "trait").put("kind", "flat").put("role", "modifier")
                        .put("sourceId", source.sourceId()).put("label", trait).set("value", mod);
            }
        } else {
            check.put("fixedDice", damage.has("count"));
            String type = damage.path("type").asText(equippedWeapon ? gear.path("damageType").textValue() : null);
            if ("phys".equals(type)) type = "physical";
            check.put("damageType", type);
            plan.putObject("metadata").put("damageType", type);
            dice(parts, "damage", profile.path("label").asText(), source.sourceId(),
                    damage.path("count").asInt(stats.path("proficiency").asInt()), damage.path("sides").intValue());
            if (damage.path("flat").asInt() != 0) parts.addObject().put("id", "bonus").put("kind", "flat")
                    .put("role", "damage").put("sourceId", source.sourceId()).put("label", profile.path("label").asText())
                    .put("value", damage.path("flat").intValue());
        }
        event.set("roll", StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), random));
        updateCheck(event);
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
