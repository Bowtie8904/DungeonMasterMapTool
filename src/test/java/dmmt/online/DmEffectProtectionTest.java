package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DmEffectProtectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode sheet(String duration) {
        ObjectNode sheet = mapper.createObjectNode();
        sheet.putArray("effects").addObject().put("id", "effect-1").put("dmManaged", true)
                .put("name", "Ward").put("until", duration).put("value", 2);
        return sheet;
    }

    @Test
    void ordinarySavesCannotCreateAlterOrRemoveDmEffects() {
        ObjectNode previous = sheet("manual");
        assertDoesNotThrow(() -> DmEffectProtection.validate(previous, previous.deepCopy(), null));
        ObjectNode changed = previous.deepCopy();
        ((ObjectNode) changed.path("effects").get(0)).put("value", 10);
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, changed, null));
        ObjectNode removed = previous.deepCopy();
        removed.putArray("effects");
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, removed, null));
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(removed, previous, null));
        ObjectNode unmarked = previous.deepCopy();
        ((ObjectNode) unmarked.path("effects").get(0)).remove("dmManaged");
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, unmarked, null));
        ObjectNode collision = previous.deepCopy();
        collision.withArray("effects").addObject().put("id", "effect-1").put("name", "Forged");
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, collision, null));
    }

    @Test
    void onlyMatchingRestExpirationMayRemoveAnEffect() {
        for (String duration : new String[] { "scene", "rest", "shortRest", "longRest", "session", "manual" }) {
            JsonNode old = sheet(duration);
            ObjectNode empty = mapper.createObjectNode();
            empty.putArray("effects");
            if (java.util.Set.of("scene", "rest", "shortRest").contains(duration)) {
                assertDoesNotThrow(() -> DmEffectProtection.validate(old, empty, "short"));
                assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(old, old.deepCopy(), "short"));
            } else {
                assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(old, empty, "short"));
            }
            if (java.util.Set.of("scene", "rest", "shortRest", "longRest").contains(duration)) {
                assertDoesNotThrow(() -> DmEffectProtection.validate(old, empty, "long"));
                assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(old, old.deepCopy(), "long"));
            } else {
                assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(old, empty, "long"));
            }
        }
    }

    @Test
    void duplicateOrMissingManagedIdsAreRejected() {
        ObjectNode previous = sheet("manual");
        ObjectNode duplicate = previous.deepCopy();
        duplicate.withArray("effects").add(duplicate.path("effects").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, duplicate, null));
        ObjectNode missing = previous.deepCopy();
        ((ObjectNode) missing.path("effects").get(0)).remove("id");
        assertThrows(IllegalArgumentException.class, () -> DmEffectProtection.validate(previous, missing, null));
    }
}
