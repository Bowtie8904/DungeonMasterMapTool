package dmmt.online.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class EventContextContractTest {
    private static final List<String> EVENTS = List.of("action.request", "roll.costs", "roll.prepare", "roll.prepared",
            "roll.finished", "damage.prepare", "damage.marks", "damage.applied", "claim.accept", "rest.prepare", "rest.short",
            "rest.long", "scene.end", "session.start", "downtime.move", "downtime.completed", "rest.roll", "death.options",
            "track.changed", "pool.changed", "equipment.changed", "effect.started", "effect.ended", "creation.apply", "equipment.plan");
    private static final List<String> CHECK_KEYS = List.of("kind", "trait", "weapon", "range", "dominant", "outcome",
            "critical", "hopeDie", "fearDie", "spell", "origin", "total", "difficulty", "weaponSource", "weaponSourceId",
            "fixedDice", "damageType", "move", "adjustedHope", "recoverableHp", "reroll", "duality", "origins", "sourceName", "sourceDomain", "facesByRole");

    private static void assertContract(String type, ObjectNode event) {
        for (String key : List.of("table", "thresholds", "damage", "damageType", "roll", "check"))
            assertTrue(event.has(key), type + " lacks " + key);
        assertTrue(event.at("/table/fear").isNumber(), type + " lacks table.fear");
        assertTrue(event.at("/thresholds").has("major") && event.at("/thresholds").has("severe"), type + " thresholds");
        TreeSet<String> keys = new TreeSet<>();
        event.path("check").fieldNames().forEachRemaining(keys::add);
        assertEquals(new TreeSet<>(CHECK_KEYS), keys, type + " check keys");
    }

    @Test
    void everyEventTypeCarriesTheFullContext() {
        for (String type : EVENTS) {
            ObjectNode event = AutomationHost.event(type, "actor-1", 3);
            assertEquals(type, event.path("type").asText());
            assertContract(type, event);
        }
    }

    @Test
    void nestedRollEventsCarryTheFullContext() {
        ObjectNode parent = AutomationHost.event("action.request", "actor-1", 4);
        ObjectNode actor = RuleJson.JSON.createObjectNode().put("id", "actor-1");
        ObjectNode source = RuleJson.JSON.createObjectNode().put("instanceId", "src-1").put("name", "Source");
        ObjectNode roll = RuleJson.JSON.createObjectNode();
        roll.putObject("metadata").put("kind", "roll");
        roll.putArray("contributions").addObject().put("id", "flat").put("kind", "flat").put("role", "modifier")
                .put("sourceId", "src").put("label", "Bonus").put("value", 2);
        roll.put("id", "r1");
        ObjectNode event = ProfileRoll.nestedEvent(parent, actor, source, StructuredRoll.resolve(
                RuleJson.JSON.createObjectNode().put("id", "r1").set("contributions", roll.get("contributions")), RuleJson.JSON.createObjectNode(), null));
        assertContract("nested", event);
        assertEquals(4, event.at("/table/fear").asInt());
    }
}
