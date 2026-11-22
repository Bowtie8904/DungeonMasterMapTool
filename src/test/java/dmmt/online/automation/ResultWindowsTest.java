package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultWindowsTest {
    @Test
    void retainsOnlyTheLatestMatchingResultAndNeverRevivesASpentOrRecastOffer() throws Exception {
        var state = RuleJson.JSON.createObjectNode();
        var event = state.objectNode().put("sourceInstanceId", "weapon").put("total", 12);
        var sources = List.of(new SheetProjection.SourceElement("gear:blade", "weapon", "Blade", state.objectNode(), null),
                new SheetProjection.SourceElement("features:edge", "edge", "Edge", state.objectNode(), null));
        var effects = (ArrayNode) RuleJson.parse("""
                [{"id":"first-activation","sourceInstanceId":"edge"}]""");
        var guards = ResultWindows.guards(sources, effects);
        var first = (ArrayNode) RuleJson.parse("""
                [{"sourceInstanceId":"edge","ruleId":"bonus","bindingId":"button"},
                 {"sourceInstanceId":"edge","ruleId":"restore","bindingId":"button"}]""");
        String a = ResultWindows.publish(state, "pack", "owner", event, first, guards);
        event.put("total", 7);
        String b = ResultWindows.publish(state, "pack", "owner", event, first.arrayNode().add(first.get(0)), guards);
        state = (ObjectNode) RuleJson.parse(state.toString());
        var old = ResultWindows.lookup(state, "pack", "owner", a);
        assertEquals(12, old.requiredAt("/event/total").intValue());
        assertEquals(List.of("restore"), old.required("buttons").findValuesAsText("ruleId"));
        var latest = ResultWindows.lookup(state, "pack", "owner", b);
        assertEquals(7, latest.requiredAt("/event/total").intValue());
        var bonus = ResultWindows.Button.of(first.get(0));
        assertThrows(RuleRuntime.Unavailable.class, () -> ResultWindows.take(old, bonus));
        ResultWindows.take(latest, bonus);
        assertThrows(RuleRuntime.Unavailable.class, () -> ResultWindows.take(latest, bonus));
        ObjectNode saved = state;
        assertThrows(RuleRuntime.Unavailable.class, () -> ResultWindows.lookup(saved, "pack", "other", a));
        assertThrows(RuleRuntime.Unavailable.class, () -> ResultWindows.lookup(saved, "new-pack", "owner", a));
        ((ObjectNode) effects.get(0)).put("id", "new-activation");
        ResultWindows.prune(state, ResultWindows.guards(sources, effects));
        assertTrue(state.isEmpty());
    }
}
