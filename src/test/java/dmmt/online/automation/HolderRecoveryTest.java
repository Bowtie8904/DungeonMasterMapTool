package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class HolderRecoveryTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Mira","permanentHp":1,"hope":{"slots":6,"on":0},"hp":{"slots":6,"on":5},
                 "stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0}}
                """);
        HOST.projection().state(sheet).put("revision", 2);
        return sheet;
    }

    private static ObjectNode event() {
        return AutomationHost.event("downtime.move", "ada", 2).put("sessionId", "session");
    }

    private static AutomationHost.Pack pack(String operation) throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("armor:overflow", RuleJson.parse("""
                {"state":{"count":0},
                 "effects":{"ward":{"label":"Ward"}},
                 "rules":[
                  {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.actorId"},"mira"]},
                                  {"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.change"},"clear"]},
                                  {"gt":[{"read":"event.requested"},
                                         {"subtract":[{"read":"event.before.marked"},{"read":"event.after.marked"}]}]}]},
                   "do":[{"op":"state.increment","slot":"count","by":1},%s],
                   "ui":{"report":"Recovered for {actor.name}."}}
                 ]}
                """.formatted(operation)), fixture("rewrite/recipes"));
        return new AutomationHost.Pack("holder-recovery", Map.of(program.sourceId(), program));
    }

    private static void equip(ObjectNode sheet) {
        sheet.putObject("activeArmor").put("name", "Overflowing").put("instanceId", "armor-instance")
                .put("automationSource", "armor:overflow");
    }

    @Test
    void appliesAProtectedClearWithoutAnySourcesAndLeavesNoopsUntouched() throws Exception {
        ObjectNode sheet = sheet(), event = event();
        ObjectNode result = HOST.stageHolderRecovery(new AutomationHost.Pack("empty", Map.of()), sheet, event,
                "mira", "hp", 9, noRandom());
        assertEquals(4, result.path("cleared").asInt());
        assertEquals(9, result.path("requested").asInt());
        assertEquals(1, sheet.at("/hp/on").asInt());
        assertEquals(3, HOST.revision(sheet));
        assertEquals("ada", result.at("/event/actorId").asText());
        assertEquals("session", result.at("/event/sessionId").asText());
        assertTrue(result.path("actions").isEmpty());

        ObjectNode before = sheet.deepCopy();
        ObjectNode noop = HOST.stageHolderRecovery(new AutomationHost.Pack("empty", Map.of()), sheet, event,
                "mira", "hp", 1, noRandom());
        assertEquals(0, noop.path("cleared").asInt());
        assertEquals(3, noop.path("revision").asInt());
        assertEquals(before, sheet);
    }

    @Test
    void usesTheHoldersSourceStateAndIdentityWithoutReplacingTheParentEvent() throws Exception {
        var pack = pack("""
                {"op":"resource.gain","pool":"actor.hope","amount":1},
                {"op":"effect.start","effect":"ward","target":"actor"},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}
                """);
        ObjectNode sheet = sheet(), event = event(), originalEvent = event.deepCopy();
        equip(sheet);
        ObjectNode first = HOST.stageHolderRecovery(pack, sheet, event, "mira", "armorTrack", 1, noRandom());
        assertEquals(0, first.path("cleared").asInt());
        assertEquals(1, sheet.at("/hope/on").asInt());
        assertEquals(1, sheet.at("/automationState/sources/armor-instance/state/count").asInt());
        assertEquals("mira", sheet.at("/automationState/effects/0/sourceActorId").asText());
        assertEquals("armor-instance", sheet.at("/automationState/effects/0/sourceInstanceId").asText());
        assertEquals("ada", first.at("/event/actorId").asText());
        assertEquals(3, first.at("/event/table/fear").asInt());
        assertEquals("Recovered for Mira.", first.at("/actions/0/report").asText());
        assertEquals(2, first.at("/outbox/0/before").asInt());
        assertEquals(3, first.at("/outbox/0/after").asInt());
        assertEquals(3, HOST.revision(sheet));
        assertEquals(originalEvent, event);

        ObjectNode second = HOST.stageHolderRecovery(pack, sheet, (ObjectNode) first.get("event"),
                "mira", "armorTrack", 1, noRandom());
        assertEquals(2, sheet.at("/hope/on").asInt());
        assertEquals(2, sheet.at("/automationState/sources/armor-instance/state/count").asInt());
        assertEquals(4, second.at("/event/table/fear").asInt());
        assertEquals(4, HOST.revision(sheet));
    }

    @Test
    void failureAndRevisionOverflowDoNotChangeTheSheet() throws Exception {
        var pack = pack("{\"op\":\"table.spend\",\"resource\":\"fear\",\"amount\":3,\"label\":\"Fail\"}");
        ObjectNode sheet = sheet(), event = event();
        equip(sheet);
        ObjectNode original = sheet.deepCopy();
        assertEquals("tableResource", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stageHolderRecovery(pack, sheet, event, "mira", "armorTrack", 1, noRandom())).code());
        assertEquals(original, sheet);
        assertThrows(IllegalArgumentException.class, () ->
                HOST.stageHolderRecovery(pack, sheet, event, "mira", "armorTrack", -1, noRandom()));
        assertEquals(original, sheet);

        HOST.projection().state(sheet).put("revision", 1L << 52);
        ObjectNode atLimit = sheet.deepCopy();
        assertEquals("revision", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stageHolderRecovery(new AutomationHost.Pack("empty", Map.of()), sheet, event,
                        "mira", "hp", 1, noRandom())).code());
        assertEquals(atLimit, sheet);
        ObjectNode noop = HOST.stageHolderRecovery(new AutomationHost.Pack("empty", Map.of()), sheet, event,
                "mira", "armorTrack", 0, noRandom());
        assertEquals(1L << 52, noop.path("revision").asLong());
        assertEquals(atLimit, sheet);
    }
}
