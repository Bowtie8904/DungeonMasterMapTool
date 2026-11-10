package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmorFeatureRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode sheet(String armor, String automation, int armorOn, int hope) throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"hope":{"on":%d,"slots":6},"armorTrack":{"on":%d,"slots":4},"effects":[],
                 "activeArmor":{"name":"%s","automation":%s}}
                """.formatted(hope, armorOn, armor, automation));
    }

    private static long named(JsonNode sheet, String name) {
        long n = 0;
        for (JsonNode effect : sheet.path("effects")) if (name.equals(effect.path("name").asText())) n++;
        return n;
    }

    private static final String BEACON = """
            {"armorHalfEffect":{"name":"Beacon of Despair","kind":"debuff","until":"manual","text":"x"}}""";
    private static final String BASTION = """
            {"armorFullEffect":{"name":"Last Bastion","kind":"buff","until":"scene","text":"x",
             "mitigation":[{"id":"bastion","label":"Last Bastion","passive":true,"halve":true}]}}""";
    private static final String OATH = """
            {"equipEffect":{"name":"Oathbound","kind":"buff","until":"manual","modifiers":[{"stat":"evasion","value":2}]}}""";
    private static final String REINFORCED = """
            {"armorFullHeldEffect":{"name":"Reinforced","kind":"buff","until":"manual",
             "modifiers":[{"stat":"thresholds","value":2,"armorFull":true}]}}""";

    @Test
    void fullHeldEffectLastsUntilAnArmorSlotIsCleared() throws Exception {
        ObjectNode next = sheet("Irontree", REINFORCED, 4, 2);
        ArmorFeatureRules.normalize(sheet("Irontree", REINFORCED, 3, 2), next);
        assertEquals(1, named(next, "Reinforced"));
        assertEquals(2, next.path("effects").path(0).path("modifiers").path(0).path("value").asInt());
        ArmorFeatureRules.normalize(next, next);
        assertEquals(1, named(next, "Reinforced"));

        ObjectNode cleared = next.deepCopy();
        cleared.with("armorTrack").put("on", 3);
        ArmorFeatureRules.normalize(next, cleared);
        assertEquals(0, named(cleared, "Reinforced"));
    }

    @Test
    void halfArmorEffectFollowsMarkedSlots() throws Exception {
        ObjectNode previous = sheet("Beacon", BEACON, 1, 2);
        ObjectNode next = sheet("Beacon", BEACON, 2, 2);
        ArmorFeatureRules.normalize(previous, next);
        assertEquals(1, named(next, "Beacon of Despair"));
        ArmorFeatureRules.normalize(previous, next);
        assertEquals(1, named(next, "Beacon of Despair"));
        ObjectNode cleared = next.deepCopy();
        cleared.with("armorTrack").put("on", 1);
        ArmorFeatureRules.normalize(next, cleared);
        assertEquals(0, named(cleared, "Beacon of Despair"));
    }

    @Test
    void fullArmorEffectStartsOnceWhenTheLastSlotIsMarked() throws Exception {
        ObjectNode previous = sheet("Bastion", BASTION, 3, 2);
        ObjectNode next = sheet("Bastion", BASTION, 4, 2);
        ArmorFeatureRules.normalize(previous, next);
        assertEquals(1, named(next, "Last Bastion"));
        assertEquals("scene", next.path("effects").path(0).path("until").asText());

        ObjectNode ended = next.deepCopy();
        ended.withArray("effects").removeAll();
        ArmorFeatureRules.normalize(next, ended);
        assertEquals(0, named(ended, "Last Bastion"), "Ending the effect while still full does not recreate it");
    }

    @Test
    void equipEffectIsAddedOnEquipAndRemovedOnUnequip() throws Exception {
        ObjectNode previous = sheet("Other", "{}", 0, 2);
        ObjectNode next = sheet("Oath", OATH, 0, 2);
        ArmorFeatureRules.normalize(previous, next);
        assertEquals(1, named(next, "Oathbound"));

        ObjectNode broken = next.deepCopy();
        broken.withArray("effects").removeAll();
        ArmorFeatureRules.normalize(next, broken);
        assertEquals(0, named(broken, "Oathbound"), "Breaking the vow keeps the effect ended");

        ObjectNode unequipped = sheet("Other", "{}", 0, 2);
        unequipped.set("effects", next.path("effects").deepCopy());
        ArmorFeatureRules.normalize(next, unequipped);
        assertEquals(0, named(unequipped, "Oathbound"));
    }

    @Test
    void zeroHopeClearsArmorOnlyWhenHopeDropsToZero() throws Exception {
        String engine = "{\"zeroHopeClearArmor\":1}";
        ObjectNode next = sheet("Engine", engine, 3, 0);
        ArmorFeatureRules.normalize(sheet("Engine", engine, 3, 1), next);
        assertEquals(2, next.path("armorTrack").path("on").asInt());

        ObjectNode stillZero = sheet("Engine", engine, 3, 0);
        ArmorFeatureRules.normalize(sheet("Engine", engine, 3, 0), stillZero);
        assertEquals(3, stillZero.path("armorTrack").path("on").asInt());
    }

    @Test
    void rejectsNonListEffects() throws Exception {
        ObjectNode next = sheet("Beacon", BEACON, 2, 2);
        next.put("effects", "bad");
        assertThrows(IllegalArgumentException.class, () -> ArmorFeatureRules.normalize(null, next));
    }
}