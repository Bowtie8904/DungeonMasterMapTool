package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoneValorRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deathrunUpdatesSeparatelyAttributedSmiteWithoutRerollingBonuses() throws Exception {
        ObjectNode run = (ObjectNode) JSON.readTree("""
                {"deathrunRemaining":[0,1,2],"deathrunRoll":{
                  "expression":"(3d8+2+1d6)*2","total":44,"rolls":[3,4,8,5],
                  "breakdown":[{"label":"Weapon","expression":"3d8","total":15,"rolls":[3,4,8]},
                    {"label":"Weapon","expression":"2","total":2,"rolls":[]},
                    {"label":"Bonus","expression":"1d6","total":5,"rolls":[5]},
                    {"label":"Smite","expression":"22","total":22,"rolls":[]}]}}
                """);
        ObjectNode continued = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(2));
        assertEquals(28, continued.path("total").asInt());
        assertEquals(7, continued.path("breakdown").path(0).path("total").asInt());
        assertEquals(14, continued.path("breakdown").path(3).path("total").asInt());
        assertEquals("[3,4,5]", continued.path("rolls").toString());
    }

    @Test
    void deathrunReusesFirstWeaponDiceAndKeepsFixedAndBonusDamage() throws Exception {
        ObjectNode run = (ObjectNode) JSON.readTree("""
                {"deathrunRemaining":[0,1,2],"deathrunRoll":{
                  "expression":"3d8 + 2 + 1d6","total":22,"rolls":[3,4,8,5],
                  "breakdown":[{"label":"Weapon","expression":"3d8","total":15,"rolls":[3,4,8]},
                    {"label":"Weapon","expression":"2","total":2,"rolls":[]},
                    {"label":"Bonus","expression":"1d6","total":5,"rolls":[5]}]}}
                """);
        ObjectNode continued = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(2));
        assertEquals(14, continued.path("total").asInt());
        assertEquals("2d8 + 2 + 1d6", continued.path("expression").asText());
        assertEquals("[3,4,5]", continued.path("rolls").toString());
        assertEquals("[0,1]", run.path("deathrunRemaining").toString());
        assertEquals(22, run.path("deathrunRoll").path("total").asInt());
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(2)));
        assertEquals(10, BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(1)).path("total").asInt());
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(0)));
    }

    @Test
    void deathrunDoesNotSubtractOriginallyDroppedWeaponDiceOrRerollBonuses() throws Exception {
        ObjectNode run = (ObjectNode) JSON.readTree("""
                {"deathrunRemaining":[1,2,3],"deathrunRoll":{
                  "expression":"4d8kh3 + 5","total":24,"rolls":[1,4,8,7],
                  "breakdown":[{"label":"Weapon","expression":"4d8kh3","total":19,"rolls":[1,4,8,7]},
                    {"label":"Weapon","expression":"5","total":5,"rolls":[]}]}}
                """);
        assertEquals(List.of(1, 2, 3), BoneValorRules.keptWeaponDice(run.path("deathrunRoll").path("breakdown").path(0)));
        ObjectNode continued = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(2));
        assertEquals(16, continued.path("total").asInt());
        assertEquals("2d8 + 5", continued.path("expression").asText());
        assertEquals("[4,7]", continued.path("rolls").toString());
        assertEquals(11, continued.path("breakdown").path(0).path("total").asInt());
    }

    @Test
    void deathrunPreservesAMaximizedWeaponDieAsOneRemovableDie() throws Exception {
        ObjectNode first = (ObjectNode) JSON.readTree("""
                {"expression":"(2d8+8+2)*2","total":34,"rolls":[3,4],
                 "breakdown":[{"label":"Weapon","expression":"(2d8+8)*2","total":30,"rolls":[3,4]},
                   {"label":"Weapon","expression":"(2)*2","total":4,"rolls":[]}]}
                """);
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"contributions":[{"expression":"(2d8+8)*2"}]}
                """);
        ObjectNode weapon = (ObjectNode) JSON.readTree("{\"damage\":\"d8\"}");
        ObjectNode snapshot = BoneValorRules.deathrunSnapshot(first, request, weapon, 3, JSON.createObjectNode());
        assertEquals("[3,4,8]", snapshot.path("rolls").toString());
        ObjectNode run = JSON.createObjectNode();
        run.set("deathrunRoll", snapshot);
        run.putArray("deathrunRemaining").add(0).add(1).add(2);
        assertEquals(18, BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(2)).path("total").asInt());
        assertEquals("[3,4]", first.path("rolls").toString());
    }

    @Test
    void deathrunValidatesAndContinuesMixedWeaponPools() throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"contributions":[{"expression":"3d8+3d6"}]}
                """);
        ObjectNode weapon = (ObjectNode) JSON.readTree("{\"damage\":\"d8+d6\"}");
        ObjectNode sheet = (ObjectNode) JSON.readTree("{\"effects\":[]}");
        assertDoesNotThrow(() -> BoneValorRules.validateWeaponDice(request, weapon, 3, sheet));
        ((ObjectNode) request.path("contributions").path(0)).put("expression", "3d8+4d6");
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.validateWeaponDice(request, weapon, 3, sheet));
        ((ObjectNode) request.path("contributions").path(0)).put("expression", "3d8+3d6");
        ObjectNode roll = (ObjectNode) JSON.readTree("""
                {"expression":"3d8+3d6+2","total":28,"rolls":[1,4,8,2,5,6],
                 "breakdown":[{"label":"Weapon","expression":"3d8+3d6","total":26,"rolls":[1,4,8,2,5,6]},
                   {"label":"Bonus","expression":"2","total":2,"rolls":[]}]}
                """);
        ObjectNode run = JSON.createObjectNode();
        run.set("deathrunRoll", BoneValorRules.deathrunSnapshot(roll, request, weapon, 3, sheet));
        run.putArray("deathrunRemaining").add(0).add(1).add(2).add(3).add(4).add(5);
        ObjectNode next = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(4));
        assertEquals(23, next.path("total").asInt());
        assertEquals("3d8+2d6+2", next.path("expression").asText());
        assertEquals("[1,4,8,2,6]", next.path("rolls").toString());
        for (int index : List.of(0, 1, 2)) next = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(index));
        assertEquals("2d6+2", next.path("expression").asText());
        assertEquals(10, next.path("total").asInt());
    }

    @Test
    void deathrunHandlesMaximizedAndDroppedDiceInEachMixedPool() throws Exception {
        ObjectNode weapon = (ObjectNode) JSON.readTree("{\"damage\":\"d8+d6\"}");
        ObjectNode sheet = (ObjectNode) JSON.readTree("""
                {"effects":[{"damage":{"extraDice":1,"dropLowest":1}}]}
                """);
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"contributions":[{"expression":"3d8kh2+2d6kh1+6"}]}
                """);
        BoneValorRules.validateWeaponDice(request, weapon, 2, sheet);
        ObjectNode roll = (ObjectNode) JSON.readTree("""
                {"expression":"3d8kh2+2d6kh1+6","total":26,"rolls":[1,7,8,2,5],
                 "breakdown":[{"label":"Weapon","expression":"3d8kh2+2d6kh1+6","total":26,"rolls":[1,7,8,2,5]}]}
                """);
        ObjectNode snapshot = BoneValorRules.deathrunSnapshot(roll, request, weapon, 2, sheet);
        assertEquals("3d8kh2+3d6kh2", snapshot.path("expression").asText());
        assertEquals("[1,7,8,2,5,6]", snapshot.path("rolls").toString());
        assertEquals(List.of(1, 2, 4, 5), BoneValorRules.keptWeaponDice(snapshot.path("breakdown").path(0)));
        ObjectNode run = JSON.createObjectNode();
        run.set("deathrunRoll", snapshot);
        run.putArray("deathrunRemaining").add(1).add(2).add(4).add(5);
        ObjectNode next = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(5));
        assertEquals(20, next.path("total").asInt());
        assertEquals("2d8+1d6", next.path("expression").asText());
        assertEquals("[7,8,5]", next.path("rolls").toString());
    }

    @Test
    void deathrunPreservesMaximizedSingleDieBeforeAnotherPool() throws Exception {
        ObjectNode weapon = (ObjectNode) JSON.readTree("{\"damage\":\"d8+d6\"}");
        ObjectNode sheet = (ObjectNode) JSON.readTree("{\"effects\":[{\"damage\":{\"extraDice\":2}}]}");
        ObjectNode request = (ObjectNode) JSON.readTree("{\"contributions\":[{\"expression\":\"8+1d6\"}]}");
        ObjectNode roll = (ObjectNode) JSON.readTree("""
                {"expression":"8+1d6+2d8+2d6","total":23,"rolls":[4,1,2,3,5],
                 "breakdown":[{"label":"Weapon","expression":"8+1d6","total":12,"rolls":[4]},
                   {"label":"Extra","expression":"2d8+2d6","total":11,"rolls":[1,2,3,5]}]}
                """);
        BoneValorRules.validateWeaponDice(request, weapon, 1, sheet);
        ObjectNode snapshot = BoneValorRules.deathrunSnapshot(roll, request, weapon, 1, sheet);
        assertEquals("1d8+1d6+2d8+2d6", snapshot.path("expression").asText());
        assertEquals("[8,4,1,2,3,5]", snapshot.path("rolls").toString());
        ObjectNode run = JSON.createObjectNode();
        run.set("deathrunRoll", snapshot);
        run.putArray("deathrunRemaining").add(0).add(1);
        ObjectNode next = BoneValorRules.continueDeathrun(run, JSON.getNodeFactory().numberNode(0));
        assertEquals(15, next.path("total").asInt());
        assertEquals("1d6+2d8+2d6", next.path("expression").asText());
        assertEquals("[4,1,2,3,5]", next.path("rolls").toString());
    }

    @Test
    void recoveryRejectsMultipleLongRestMovesAndForgedGrants() throws Exception {
        ObjectNode before = (ObjectNode) JSON.readTree("""
                {"className":"Guardian","shortRests":0,"hp":{"on":4,"slots":6},
                  "effects":[{"id":"grant","name":"Recovery","consumeLongRestMove":true}]}
                """);
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"kind":"short","picks":[{"move":"tend-all"},{"move":"stress-all"}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.validateRest(before, before.deepCopy(), request));
        before.putArray("effects");
        request.withArray("picks").remove(1);
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.validateRest(before, before.deepCopy(), request));
    }

    @Test
    void incomingDamagePreservesBondedRescueOfTheLastHitPoint() throws Exception {
        ObjectNode before = (ObjectNode) JSON.readTree("""
                {"hp":{"on":5,"slots":6},"stress":{"on":2,"slots":6},
                 "hope":{"on":3,"slots":6},"armorTrack":{"on":0,"slots":3},
                 "companion":{"training":[{"id":"bonded"}],"stress":{"on":0,"slots":3}}}
                """);
        ObjectNode next = before.deepCopy();
        next.withObject("/companion").put("bondedAttempted", true).put("unavailable", true);
        next.withObject("/companion/stress").put("on", 3);
        assertDoesNotThrow(() -> BoneValorRules.validateDamage(before, next, 1));
        next.withObject("/hp").put("on", 4);
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.validateDamage(before, next, 1));
    }

    @Test
    void sheetSavesCannotForgeDeathrunCountersButMayManuallyRemoveTheBuff() throws Exception {
        ObjectNode before = (ObjectNode) JSON.readTree("""
                {"effects":[{"id":"run","name":"Deathrun","deathrun":true,"counter":{"value":1}}]}
                """);
        ObjectNode next = before.deepCopy();
        BoneValorRules.validateSave(before, next);
        ((ObjectNode) next.path("effects").path(0).path("counter")).put("value", 0);
        assertThrows(IllegalArgumentException.class, () -> BoneValorRules.validateSave(before, next));
        next.putArray("effects");
        BoneValorRules.validateSave(before, next);
    }
}
