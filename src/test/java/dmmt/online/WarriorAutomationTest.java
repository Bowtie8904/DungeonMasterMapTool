package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WarriorAutomationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void grantsDowntimeToEverySheetWithoutDuplicatingOrResettingEarnedDice() throws Exception {
        ObjectNode warrior = MAPPER.createObjectNode();
        var features = warrior.putArray("features");
        for (var entry : MAPPER.readTree(Path.of("dmmap-daggerheart", "classes.json").toFile()).path("entries"))
            if ("warrior-call-of-the-slayer-mastery".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features").deepCopy());
        ObjectNode ally = MAPPER.createObjectNode();
        ObjectNode offline = MAPPER.createObjectNode();
        Map<String, ObjectNode> party = new LinkedHashMap<>(Map.of("warrior", warrior, "ally", ally, "offline", offline));
        WarriorAutomation.synchronizeDowntime(party);
        assertEquals(0, warrior.path("effects").size());
        for (ObjectNode sheet : java.util.List.of(ally, offline)) {
            assertEquals("train", sheet.path("effects").get(0).path("actions").get(0).path("id").asText());
            assertEquals("manual", sheet.path("effects").get(0).path("until").asText());
            assertEquals("warrior", sheet.path("effects").get(0).path("sourceActorId").asText());
            assertFalse(sheet.has("resources"));
        }
        ObjectNode snapshot = ally.deepCopy();
        WarriorAutomation.synchronizeDowntime(party);
        assertEquals(snapshot, ally);
        ally.withArray("effects").addObject().put("id", "earned").put("name", "Martial Preparation: Slayer Die");
        warrior.putArray("features");
        WarriorAutomation.synchronizeDowntime(party);
        assertEquals(1, ally.path("effects").size());
        assertEquals("earned", ally.path("effects").get(0).path("id").asText());
        assertEquals(0, offline.path("effects").size());
    }

    @Test
    void preservesAndIdentifiesExistingGrantsWhenSourceIsDisabledWithoutGrantingNewOnes() throws Exception {
        ObjectNode warrior = MAPPER.createObjectNode();
        var features = warrior.putArray("features");
        for (var entry : MAPPER.readTree(Path.of("dmmap-daggerheart", "classes.json").toFile()).path("entries"))
            if ("warrior-call-of-the-slayer-mastery".equals(entry.path("id").asText()))
                features.addAll((com.fasterxml.jackson.databind.node.ArrayNode) entry.path("features").deepCopy());
        ObjectNode ally = MAPPER.createObjectNode();
        WarriorAutomation.synchronizeDowntime(new LinkedHashMap<>(Map.of("warrior", warrior, "ally", ally)));
        ((ObjectNode) ally.path("effects").get(0)).remove("sourceActorId");
        ObjectNode newAlly = MAPPER.createObjectNode();
        Map<String, ObjectNode> active = new LinkedHashMap<>(Map.of("ally", ally, "new", newAlly));
        WarriorAutomation.synchronizeDowntime(active, Map.of("warrior", warrior));
        assertEquals("warrior", ally.path("effects").get(0).path("sourceActorId").asText());
        assertEquals(0, newAlly.path("effects").size());
        active.put("warrior", warrior);
        WarriorAutomation.synchronizeDowntime(active);
        assertEquals("warrior", newAlly.path("effects").get(0).path("sourceActorId").asText());
        assertEquals(1, ally.path("effects").size());
    }

    @Test
    void rerollsOnlyMarkedSlayerOnesAndPreservesDualityAndOtherDice() throws Exception {
        for (String kind : java.util.List.of("duality", "damage")) {
            ObjectNode roll = (ObjectNode) MAPPER.readTree("""
                    {"total":20,"rolls":[1,8,1,5,1],"breakdown":[
                      {"label":"Base","expression":"2d12","total":9,"rolls":[1,8]},
                      {"label":"Slayer","expression":"0+2d6","total":6,"rolls":[1,5],
                       "notes":[{"label":"Dice resource","impact":"slayer-dice"}]},
                      {"label":"Other bonus","expression":"1d6+4","total":5,"rolls":[1]}]}
                    """);
            roll.put("kind", kind).put("outcome", "fear");
            var reports = new ArrayList<String>();
            long seed = 0;
            while (new Random(seed).nextInt(6) == 0) seed++;
            WarriorAutomation.rerollOnes(roll, "slayer-dice", 100, new Random(seed), reports);
            int face = roll.path("rolls").path(2).asInt();
            assertNotEquals(1, face);
            assertEquals(1, roll.path("rolls").path(0).asInt());
            assertEquals(8, roll.path("rolls").path(1).asInt());
            assertEquals(5, roll.path("rolls").path(3).asInt());
            assertEquals(1, roll.path("rolls").path(4).asInt());
            assertEquals(19 + face, roll.path("total").asInt());
            assertEquals(5 + face, roll.path("breakdown").path(1).path("total").asInt());
            assertEquals("fear", roll.path("outcome").asText());
            assertEquals(2, reports.size());
            assertThrows(IllegalArgumentException.class, () -> WarriorAutomation.rerollOnes(roll, "slayer-dice", 100, new Random(0), reports));
        }
    }
}
