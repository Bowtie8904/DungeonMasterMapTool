package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Applies armor features that react to sheet state: Oathbound, Last Light Engine, Last Bastion, Reinforced and Beacon of Despair. */
public final class ArmorFeatureRules {
    static final String MARKER = "armorAutomation";
    static final String HALF = "half";
    static final String FULL = "full";
    static final String HELD = "fullHeld";
    static final String EQUIP = "equip";

    private ArmorFeatureRules() {
    }

    /** Normalizes {@code next} in place; {@code previous} is the stored sheet before the change, if any. */
    public static void normalize(JsonNode previous, ObjectNode next) {
        if (next == null) return;
        JsonNode effects = next.path("effects");
        if (!effects.isMissingNode() && !effects.isArray())
            throw new IllegalArgumentException("The character sheet effects must be a list.");
        JsonNode automation = next.path("activeArmor").path("automation");
        String armorName = next.path("activeArmor").path("name").asText("");
        if (automation.isObject() && sameArmor(previous, armorName)) clearArmorAtZeroHope(previous, next, automation);
        int slots = next.path("armorTrack").path("slots").asInt();
        int on = next.path("armorTrack").path("on").asInt();
        boolean hasArmor = automation.isObject() && slots > 0;

        JsonNode half = automation.path("armorHalfEffect");
        boolean halfActive = hasArmor && half.isObject() && on >= (slots + 1) / 2;
        removeMarked(next, HALF, halfActive ? armorName : null);
        if (halfActive && !hasMarked(next, HALF)) addEffect(next, half, armorName, HALF, "armor-half-effect");

        JsonNode held = automation.path("armorFullHeldEffect");
        boolean heldActive = hasArmor && held.isObject() && on >= slots;
        removeMarked(next, HELD, heldActive ? armorName : null);
        if (heldActive && !hasMarked(next, HELD)) addEffect(next, held, armorName, HELD, "armor-full-held-effect");

        JsonNode full = automation.path("armorFullEffect");
        boolean nowFull = hasArmor && on >= slots;
        if (!hasArmor) removeMarked(next, FULL, null);
        if (nowFull && full.isObject() && !wasFull(previous, armorName) && !hasNamed(next, full.path("name").asText()))
            addEffect(next, full, armorName, FULL, "armor-full-" + Long.toString(System.nanoTime(), 36));

        JsonNode equip = automation.path("equipEffect");
        boolean equipped = automation.isObject() && !armorName.isEmpty();
        removeMarked(next, EQUIP, equipped ? armorName : null);
        if (equipped && equip.isObject() && !sameArmor(previous, armorName) && !hasNamed(next, equip.path("name").asText()))
            addEffect(next, equip, armorName, EQUIP, "armor-equip-" + Long.toString(System.nanoTime(), 36));
    }

    private static void clearArmorAtZeroHope(JsonNode previous, ObjectNode next, JsonNode automation) {
        int amount = automation.path("zeroHopeClearArmor").asInt();
        if (amount <= 0 || previous == null) return;
        if (previous.path("hope").path("on").asInt() <= 0 || next.path("hope").path("on").asInt() != 0) return;
        if (!(next.path("armorTrack") instanceof ObjectNode track)) return;
        int on = track.path("on").asInt();
        if (on > 0) track.put("on", Math.max(0, on - amount));
    }

    private static boolean sameArmor(JsonNode previous, String armorName) {
        return previous != null && armorName.equals(previous.path("activeArmor").path("name").asText(""));
    }

    private static boolean wasFull(JsonNode previous, String armorName) {
        if (!sameArmor(previous, armorName)) return false;
        int slots = previous.path("armorTrack").path("slots").asInt();
        return slots > 0 && previous.path("armorTrack").path("on").asInt() >= slots;
    }

    private static void removeMarked(ObjectNode sheet, String kind, String keepSource) {
        if (!(sheet.path("effects") instanceof ArrayNode list)) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            JsonNode effect = list.get(i);
            if (!kind.equals(effect.path(MARKER).asText())) continue;
            if (keepSource != null && keepSource.equals(effect.path("source").asText())) continue;
            list.remove(i);
        }
    }

    private static boolean hasMarked(ObjectNode sheet, String kind) {
        for (JsonNode effect : sheet.path("effects")) if (kind.equals(effect.path(MARKER).asText())) return true;
        return false;
    }

    private static boolean hasNamed(ObjectNode sheet, String name) {
        for (JsonNode effect : sheet.path("effects")) if (name.equals(effect.path("name").asText())) return true;
        return false;
    }

    private static void addEffect(ObjectNode sheet, JsonNode definition, String source, String kind, String id) {
        ObjectNode effect = definition.deepCopy();
        if (!effect.has("until")) effect.put("until", "manual");
        if (!effect.has("modifiers")) effect.putArray("modifiers");
        effect.put("id", id).put("source", source).put(MARKER, kind);
        ArrayNode list = sheet.path("effects") instanceof ArrayNode array ? array : sheet.putArray("effects");
        list.add(effect);
    }
}
