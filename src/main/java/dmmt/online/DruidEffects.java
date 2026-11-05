package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Source-linked Earth aura options are campaign-wide; distance remains player-confirmed. */
final class DruidEffects {
    private DruidEffects() {}

    static void normalize(ObjectNode sheet) {
        if (!(sheet.path("effects") instanceof ArrayNode effects)) return;
        boolean changed;
        do {
            changed = false;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                boolean missingParent = effect.has("requiresEffect") && !hasEffect(sheet, effect.path("requiresEffect").asText());
                boolean lastHp = effect.path("beastform").asBoolean()
                        && sheet.path("hp").path("slots").asInt() > 0
                        && sheet.path("hp").path("on").asInt() >= sheet.path("hp").path("slots").asInt();
                if (missingParent || lastHp) {
                    effects.remove(i);
                    changed = true;
                }
            }
        } while (changed);
    }

    static boolean hasEffect(ObjectNode sheet, String name) {
        for (JsonNode effect : sheet.path("effects"))
            if (name.equals(effect.path("name").asText())) return true;
        return false;
    }

    static void synchronizeEarth(Map<String, ObjectNode> sheets) {
        sheets.values().forEach(DruidEffects::normalize);
        List<String> sources = new ArrayList<>();
        sheets.forEach((id, sheet) -> {
            if (hasEffect(sheet, "Elemental Aura: Earth") && hasEffect(sheet, "Channeling: Earth")) sources.add(id);
        });
        for (ObjectNode sheet : sheets.values()) {
            if (sources.isEmpty() && !sheet.path("effects").isArray()) continue;
            ArrayNode effects = sheet.withArray("effects");
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (effect.path("earthAuraGrant").asBoolean() && !sources.contains(effect.path("sourceActorId").asText())) effects.remove(i);
            }
            for (String id : sources) {
                boolean exists = false;
                for (JsonNode effect : effects)
                    if (effect.path("earthAuraGrant").asBoolean() && id.equals(effect.path("sourceActorId").asText())) exists = true;
                if (exists) continue;
                ObjectNode effect = effects.addObject();
                effect.put("id", "earth-aura-" + id).put("name", "Elemental Aura: Earth - Strength")
                        .put("kind", "buff").put("until", "manual").put("earthAuraGrant", true)
                        .put("sourceActorId", id).put("source", "Elemental Aura: Earth")
                        .put("text", "Select +1 Strength only while within Close range of the druid. Multiple Earth auras do not stack.");
                effect.putArray("rollOptions").addObject().put("id", "strength")
                        .put("label", "Earth aura: +1 Strength (within Close range)")
                        .put("roll", "trait:Strength").put("value", 1).put("exclusive", "earth-aura")
                        .put("description", "Select only while within Close range of the granting druid.");
            }
        }
    }
}
