package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplendorActionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void healingRespectsPermanentWoundsAndCriticalSuccessDoesNotDependOnTotal() throws Exception {
        ObjectNode sheet = (ObjectNode) JSON.readTree("""
                {"hp":{"on":4,"slots":6},"permanentHp":2,"stress":{"on":3,"slots":6}}
                """);
        assertEquals(2, SplendorActions.heal(sheet, "hp", 6));
        assertEquals(2, sheet.path("hp").path("on").asInt());
        assertEquals(3, SplendorActions.heal(sheet, "stress", 6));
        assertTrue(SplendorActions.critical(JSON.readTree("{\"rolls\":[1,1],\"total\":2}")));
        assertTrue(SplendorActions.hopeful(JSON.readTree("{\"rolls\":[1,1],\"total\":2}")));
        assertFalse(SplendorActions.critical(JSON.readTree("{\"rolls\":[0,0]}")));
    }

    @Test
    void allocationsRejectDuplicatesSelfOverhealingFractionsAndOverpaymentWithoutMutation() throws Exception {
        ObjectNode target = (ObjectNode) JSON.readTree("{\"hp\":{\"on\":3,\"slots\":6}}");
        String original = target.toString();
        Map<String, ObjectNode> eligible = Map.of("ally", target, "caster", target);
        for (String invalid : new String[]{
                "[{\"actorId\":\"ally\",\"amount\":4}]", "[{\"actorId\":\"ally\",\"amount\":1.5}]",
                "[{\"actorId\":\"caster\",\"amount\":1}]", "[{\"actorId\":\"other\",\"amount\":1}]",
                "[{\"actorId\":\"ally\",\"amount\":2}]", "[{\"actorId\":\"ally\",\"amount\":-1}]",
                "[{\"actorId\":\"ally\",\"amount\":1},{\"actorId\":\"ally\",\"amount\":1}]"
        }) {
            var input = JSON.readTree(invalid);
            assertThrows(IllegalArgumentException.class, () -> SplendorActions.allocations(input, "caster", eligible, 1));
            assertEquals(original, target.toString());
        }
    }

    @Test
    void healingHandsLockSurvivesManualRemovalAndExpiresOnlyOnTargetsLongRest() throws Exception {
        ObjectNode previous = (ObjectNode) JSON.readTree("""
                {"longRestRevision":2,"effects":[{"id":"hands","healingHands":true,"until":"longRest"}]}
                """);
        ObjectNode saved = previous.deepCopy();
        saved.putArray("effects");
        SplendorActions.preserveSave(saved, previous);
        assertEquals(previous.path("effects"), saved.path("effects"));
        saved.put("longRestRevision", 3);
        saved.putArray("effects");
        SplendorActions.preserveSave(saved, previous);
        assertTrue(saved.path("effects").isEmpty());
    }

    @Test
    void restoreDecrementsExactlyOneUseAndRejectsExhaustedCounters() throws Exception {
        ObjectNode sheet = (ObjectNode) JSON.readTree("{\"usage\":{\"feat:Limit\":{\"used\":2,\"per\":\"session\"}}}");
        SplendorActions.restore(sheet, "feat:Limit");
        assertEquals(1, sheet.path("usage").path("feat:Limit").path("used").asInt());
        SplendorActions.restore(sheet, "feat:Limit");
        assertThrows(IllegalArgumentException.class, () -> SplendorActions.restore(sheet, "feat:Limit"));
        assertThrows(IllegalArgumentException.class, () -> SplendorActions.restore(sheet, "feat:Absent"));
    }
}
