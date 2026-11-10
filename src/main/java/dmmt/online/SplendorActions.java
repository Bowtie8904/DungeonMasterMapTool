package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SplendorActions {
    private SplendorActions() {}

    static int heal(ObjectNode sheet, String resource, int amount) {
        ObjectNode track = (ObjectNode) sheet.path(resource);
        int before = track.path("on").asInt();
        int floor = "hp".equals(resource) ? DeathMoves.hpHealingFloor(sheet) : 0;
        track.put("on", Math.max(floor, before - amount));
        return before - track.path("on").asInt();
    }

    static boolean critical(JsonNode roll) {
        return roll != null && roll.path("rolls").path(0).asInt() > 0
                && roll.path("rolls").path(0).equals(roll.path("rolls").path(1));
    }

    static boolean hopeful(JsonNode roll) {
        return critical(roll) || roll != null && roll.path("rolls").path(0).asInt() > roll.path("rolls").path(1).asInt();
    }

    static Map<String, Integer> allocations(JsonNode input, String caster, Map<String, ObjectNode> eligible, int capacity) {
        if (!input.isArray()) throw new IllegalArgumentException("Choose healing allocations.");
        Map<String, Integer> result = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode entry : input) {
            String id = entry.path("actorId").asText();
            JsonNode amount = entry.path("amount");
            if (id.equals(caster) || !eligible.containsKey(id) || !amount.isInt() || amount.asInt() < 0
                    || result.containsKey(id) || amount.asInt() > DeathMoves.recoverableHp(eligible.get(id)))
                throw new IllegalArgumentException("Healing must target wounded allies and cannot exceed their recoverable HP.");
            total = Math.addExact(total, amount.asInt());
            if (total > capacity) throw new IllegalArgumentException("Not enough unmarked Stress for this healing.");
            result.put(id, amount.asInt());
        }
        if (total < 1) throw new IllegalArgumentException("Allocate at least one HP of healing.");
        return result;
    }

    static ObjectNode zoneEffect(ObjectNode sheet, String caster, String activation, int value) {
        ObjectNode effect = sheet.objectNode().put("id", "zone-" + UUID.randomUUID())
                .put("name", "Zone of Protection").put("kind", "buff").put("until", "manual")
                .put("sourceActorId", caster).put("sourceKey", "card:Zone of Protection")
                .put("protectionZone", true).put("activationId", activation)
                .put("requiresSourceEffect", "Zone of Protection source")
                .put("text", "Reduce damage by the shared Protection value; each use increases it.");
        effect.putArray("mitigation").addObject().put("id", "protection").put("label", "Zone of Protection")
                .put("reduce", value).put("defaultOn", true).put("protectionZone", true);
        return effect;
    }

    static void preserveSave(ObjectNode saved, ObjectNode previous) {
        if (saved.path("longRestRevision").asLong() <= previous.path("longRestRevision").asLong()) {
            for (JsonNode effect : previous.path("effects")) {
                if (!effect.path("healingHands").asBoolean()) continue;
                ArrayNode effects = saved.withArray("effects");
                for (int i = effects.size() - 1; i >= 0; i--)
                    if (effects.path(i).path("id").equals(effect.path("id"))) effects.remove(i);
                effects.add(effect.deepCopy());
            }
        }
        if (previous.path("resurrectionVaulted").asBoolean()) {
            saved.put("resurrectionVaulted", true);
            for (JsonNode card : previous.path("vault")) {
                if (card.path("permanentlyVaulted").asBoolean() || "Resurrection".equals(card.path("name").asText())) {
                    String name = card.path("name").asText();
                    ArrayNode loadout = saved.withArray("loadout"), vault = saved.withArray("vault");
                    for (int i = loadout.size() - 1; i >= 0; i--)
                        if (name.equals(loadout.path(i).path("name").asText())) loadout.remove(i);
                    for (int i = vault.size() - 1; i >= 0; i--)
                        if (name.equals(vault.path(i).path("name").asText())) vault.remove(i);
                    ObjectNode permanent = card.deepCopy();
                    permanent.put("permanentlyVaulted", true);
                    vault.add(permanent);
                }
            }
        } else saved.remove("resurrectionVaulted");
    }

    static void vaultResurrection(ObjectNode sheet) {
        vaultResurrection(sheet, "card:Resurrection");
    }

    static void vaultResurrection(ObjectNode sheet, String key) {
        ArrayNode cards = sheet.withArray("loadout");
        String source = key.startsWith("card:Copycat/") ? "Copycat" : "Resurrection";
        for (int i = cards.size() - 1; i >= 0; i--)
            if (source.equals(cards.path(i).path("name").asText())) {
                ObjectNode card = (ObjectNode) cards.remove(i);
                card.put("permanentlyVaulted", true);
                sheet.withArray("vault").add(card);
            }
        sheet.put("resurrectionVaulted", true);
        sheet.put("compendiumRevision", sheet.path("compendiumRevision").asLong() + 1);
    }

    static void restore(ObjectNode sheet, String key) {
        if (!(sheet.path("usage").path(key) instanceof ObjectNode usage) || usage.path("used").asInt() < 1
                || !List.of("rest", "longRest", "shortRest", "session", "scene").contains(usage.path("per").asText()))
            throw new IllegalArgumentException("That limited feature has no spent use to restore.");
        usage.put("used", usage.path("used").asInt() - 1);
    }
}
