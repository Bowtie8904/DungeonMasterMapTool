package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Validates DM-owned effects in client-submitted sheet snapshots. */
final class DmEffectProtection {
    private DmEffectProtection() {
    }

    static void validate(JsonNode previous, JsonNode next, String restKind) {
        HelpAllyRules.validateEffects(previous, next);
        Map<String, JsonNode> oldEffects = managed(previous);
        Map<String, JsonNode> newEffects = managed(next);
        for (var entry : newEffects.entrySet()) {
            JsonNode old = oldEffects.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                throw new IllegalArgumentException("Only the DM can create or change a DM-managed effect.");
            }
        }
        for (JsonNode effect : next.path("effects")) {
            if (!effect.path("dmManaged").asBoolean(false)
                    && oldEffects.containsKey(effect.path("id").asText(""))) {
                throw new IllegalArgumentException("A DM-managed effect identifier cannot be reused.");
            }
        }
        Set<String> expired = "long".equals(restKind)
                ? Set.of("scene", "rest", "shortRest", "longRest")
                : "short".equals(restKind) ? Set.of("scene", "rest", "shortRest") : Set.of();
        for (var entry : oldEffects.entrySet()) {
            boolean present = newEffects.containsKey(entry.getKey());
            boolean shouldExpire = expired.contains(entry.getValue().path("until").asText("manual"));
            if (!present && !shouldExpire) {
                throw new IllegalArgumentException("Only the DM can remove this effect.");
            }
            if (present && shouldExpire) throw new IllegalArgumentException("This effect expires during the rest.");
        }
    }

    private static Map<String, JsonNode> managed(JsonNode sheet) {
        Map<String, JsonNode> effects = new HashMap<>();
        for (JsonNode effect : sheet.path("effects")) {
            if (!effect.path("dmManaged").asBoolean(false)) continue;
            String id = effect.path("id").asText("");
            if (id.isBlank() || effects.putIfAbsent(id, effect) != null) {
                throw new IllegalArgumentException("Invalid DM-managed effect identifier.");
            }
        }
        return effects;
    }
}
