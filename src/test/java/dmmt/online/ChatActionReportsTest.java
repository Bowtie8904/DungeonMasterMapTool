package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatActionReportsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArrayNode actions(String label) {
        ArrayNode actions = MAPPER.createArrayNode();
        actions.addObject().put("label", label).put("disabled", false).put("detail", "");
        return actions;
    }

    @Test
    void aggregatesViewersWithoutDuplicateTabsAndClearsClaimsAndDisconnectedConnections() {
        ChatActionReports reports = new ChatActionReports();
        var ref = new ChatActionReports.Ref(1, 2);
        reports.update("a1", "a", "Alice", ref, actions("Gain 1 Hope"));
        ArrayNode unavailable = actions("Gain 1 Hope");
        ((com.fasterxml.jackson.databind.node.ObjectNode) unavailable.get(0)).put("disabled", true).put("detail", "Maximum Hope");
        reports.update("a1", "a", "Alice", ref, unavailable);
        reports.update("a2", "a", "Alice", ref, actions("Gain 1 Hope"));
        reports.update("b", "b", "Bob", ref, actions("Reroll"));
        reports.update("c", "c", "Carol", ref, actions("Claim"));
        assertEquals(3, reports.summary(ref).size());
        assertEquals(1, reports.summary(ref).get(0).path("actions").size());
        assertFalse(reports.summary(ref).get(0).path("actions").get(0).path("disabled").asBoolean());
        reports.disconnect("a1");
        assertEquals(3, reports.summary(ref).size());
        reports.update("a2", "a", "Alice", ref, MAPPER.createArrayNode());
        assertEquals(2, reports.summary(ref).size());
        var disconnected = reports.disconnect("b");
        assertEquals("Carol", disconnected.getFirst().path("players").get(0).path("name").asText());
        reports.delete(ref);
        assertTrue(reports.summary(ref).isEmpty());
        assertTrue(reports.summary(new ChatActionReports.Ref(1, 99)).isEmpty());
    }

    @Test
    void boundsMemoryAndValidatesUntrustedLabels() {
        ChatActionReports reports = new ChatActionReports();
        for (int i = 0; i <= 300; i++)
            reports.update("a", "a", "Alice", new ChatActionReports.Ref(i, 2), actions("Gain 1 Hope"));
        assertTrue(reports.summary(new ChatActionReports.Ref(0, 2)).isEmpty());
        assertEquals(1, reports.summary(new ChatActionReports.Ref(300, 2)).size());
        assertEquals(actions("Claim"), ChatActionReports.validate(actions("Claim")));
        assertThrows(IllegalArgumentException.class, () -> ChatActionReports.validate(actions("x".repeat(201))));
        assertThrows(IllegalArgumentException.class, () -> ChatActionReports.validate(actions(" ")));
        ArrayNode tooMany = actions("Claim");
        for (int i = 0; i < 128; i++) tooMany.add(tooMany.get(0));
        assertThrows(IllegalArgumentException.class, () -> ChatActionReports.validate(tooMany));
        assertThrows(IllegalArgumentException.class, () -> ChatActionReports.validate(MAPPER.createObjectNode()));
        ArrayNode missingState = MAPPER.createArrayNode();
        missingState.addObject().put("label", "Claim");
        assertThrows(IllegalArgumentException.class, () -> ChatActionReports.validate(missingState));
    }
}
