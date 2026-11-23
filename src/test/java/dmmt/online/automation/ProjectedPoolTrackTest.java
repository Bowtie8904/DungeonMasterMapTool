package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectedPoolTrackTest {
    @Test
    void mappedPoolUsesTheLiveTrackAndPersistsStickyFullFlag() throws Exception {
        SheetProjection projection = SheetProjection.parse("""
                {"format":"dmmap-sheet-projection","version":2,
                 "stateField":"automationState","sourceField":"automationSource","instanceField":"instanceId","nameField":"name",
                 "sources":[{"path":"features"}],
                 "actor":{
                   "name":{"kind":"value","path":"name"},
                   "ward":{"kind":"object","path":"ward.track","fields":{"marked":"on","capacity":"slots"},"writable":["marked"]},
                   "broken":{"kind":"value","type":"boolean","path":"ward.broken","writable":true,"default":false},
                   "pools":{"kind":"engine","path":"automationState.pools","default":{}}
                 },
                 "poolTracks":[{"pool":"ward-slots","track":"ward","fullFlag":"broken"}]}
                """);
        AutomationHost host = new AutomationHost(projection);
        var pack = AutomationHost.compile("ward", Map.of("classes", """
                {"format":"dmmap-daggerheart","version":2,"category":"classes","entries":[
                 {"id":"ward","name":"Ward","automation":{
                  "pools":{"ward-slots":{"kind":"number","scope":"actor","max":{"read":"actor.ward.capacity"}}},
                  "rules":[
                   {"id":"mark","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                    "do":[{"op":"pool.spend","pool":"ward-slots","amount":1}],"ui":{"label":"Mark"}},
                   {"id":"clear","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                    "do":[{"op":"pool.gain","pool":"ward-slots","amount":1}],"ui":{"label":"Clear"}},
                   {"id":"restore","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                    "do":[{"op":"flag.set","flag":"actor.broken","value":false}],"ui":{"label":"Restore"}}
                  ]}}]}
                """), LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","ward":{"track":{"slots":3,"on":2},"broken":false},
                 "features":[{"name":"Ward","instanceId":"ward","automationSource":"classes:ward"}],
                 "automationState":{"pools":{"ward-slots":{"max":9,"value":9}}}}
                """);
        assertEquals(1, projection.actor(sheet).at("/pools/ward-slots/value").asInt());
        assertEquals(3, projection.actor(sheet).at("/pools/ward-slots/max").asInt());
        execute(host, pack, sheet, "mark", 0);
        assertEquals(3, sheet.at("/ward/track/on").asInt());
        assertTrue(sheet.at("/ward/broken").asBoolean());
        execute(host, pack, sheet, "clear", 1);
        assertEquals(2, sheet.at("/ward/track/on").asInt());
        assertTrue(sheet.at("/ward/broken").asBoolean(), "Recovery alone does not reset a sticky full flag.");
        execute(host, pack, sheet, "restore", 2);
        assertFalse(sheet.at("/ward/broken").asBoolean());
        sheet.withObject("/ward/track").put("slots", 5);
        assertEquals(3, projection.actor(sheet).at("/pools/ward-slots/value").asInt());
        assertEquals(5, projection.actor(sheet).at("/pools/ward-slots/max").asInt());
    }

    private static void execute(AutomationHost host, AutomationHost.Pack pack, ObjectNode sheet,
                                String rule, long revision) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", rule).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", "ward").put("ruleId", rule).put("bindingId", "use");
        host.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0), request, "self", StructuredRollTest.noRandom());
    }
}
