package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SpecialCardActionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testingVitalityKeepsFixedLoadoutAndCannotBeRepeatedOrErasedBySaves() throws Exception {
        var actor = Campaign.Actor.builder().id("hero").name("Hero").testingCharacter(true).build();
        var campaign = Campaign.builder().id("campaign").name("Campaign").build();
        ObjectNode sheet = (ObjectNode) mapper.readTree("""
                {"loadout":[{"name":"Vitality"}],"hp":{"slots":6,"total":6},"stress":{"slots":6,"total":6}}
                """);
        var request = mapper.readTree("{\"choices\":[\"hp\",\"stress\"]}");
        SpecialCardActions.apply(sheet, actor, campaign, "vitality", request, 50, new Random(1));
        assertEquals(7, sheet.path("hp").path("slots").asInt());
        assertEquals(7, sheet.path("hp").path("total").asInt());
        assertEquals(7, sheet.path("stress").path("slots").asInt());
        assertEquals(1, sheet.path("loadout").size());
        assertFalse(sheet.has("vault"));
        assertThrows(IllegalArgumentException.class,
                () -> SpecialCardActions.apply(sheet, actor, campaign, "vitality", request, 50, new Random(1)));
        var incoming = sheet.deepCopy();
        incoming.remove("vitalityBenefits");
        SpecialCardActions.preserveSave(incoming, sheet, true);
        assertEquals(sheet.path("vitalityBenefits"), incoming.path("vitalityBenefits"));
    }

    @Test
    void usedRealVitalityCannotBeRecalledOrItsPermanentChoicesChangedBySheetSaves() throws Exception {
        ObjectNode previous = (ObjectNode) mapper.readTree("""
                {"vitalityBenefits":["hp","thresholds"],"loadout":[],"vault":[{"name":"Vitality","text":"Original"}]}
                """);
        ObjectNode incoming = (ObjectNode) mapper.readTree("""
                {"vitalityBenefits":[],"loadout":[{"name":"Vitality"}],"vault":[]}
                """);
        SpecialCardActions.migrateVitality(previous);
        incoming.set("effects", previous.path("effects").deepCopy());
        SpecialCardActions.preserveSave(incoming, previous, false);
        assertTrue(incoming.path("loadout").isEmpty());
        assertEquals(previous.path("vault"), incoming.path("vault"));
        assertEquals(previous.path("vitalityBenefits"), incoming.path("vitalityBenefits"));
        incoming.putArray("vitalityBenefits").add("hp").add("stress");
        incoming.putArray("effects");
        SpecialCardActions.preserveSave(incoming, mapper.createObjectNode(), false);
        assertFalse(incoming.has("vitalityBenefits"), "Ordinary saves cannot grant permanent benefits.");
    }

    @Test
    void legacyVitalityMigratesWithoutRegrantingAndRemovalIsExplicitVersionedAndIrreversible() throws Exception {
        ObjectNode previous = (ObjectNode) mapper.readTree("""
                {"vitalityBenefits":["hp","thresholds"],"loadout":[],"vault":[{"name":"Vitality"}],
                 "hp":{"slots":7,"total":12,"on":7},"stress":{"slots":6,"total":12,"on":1}}
                """);
        SpecialCardActions.migrateVitality(previous);
        SpecialCardActions.migrateVitality(previous);
        assertEquals(7, previous.path("hp").path("slots").asInt());
        assertEquals(1, previous.path("effects").size());
        assertEquals("buff", previous.path("effects").get(0).path("kind").asText());
        assertEquals(2, previous.path("effects").get(0).path("modifiers").get(0).path("value").asInt());
        var stale = previous.deepCopy();
        var incoming = previous.deepCopy();
        incoming.putArray("effects");
        incoming.withObject("/hp").put("slots", 6);
        incoming.put("vitalityEffectRemoved", true);
        assertThrows(IllegalArgumentException.class, () -> SpecialCardActions.preserveSave(incoming, previous, false, true));
        incoming.putObject("vitalityRemoval").put("editMode", true).put("effectId", "vitality-permanent");
        var unversioned = incoming.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> SpecialCardActions.preserveSave(unversioned, previous, false, false));
        var noEdit = incoming.deepCopy();
        noEdit.withObject("/vitalityRemoval").put("editMode", false);
        assertThrows(IllegalArgumentException.class, () -> SpecialCardActions.preserveSave(noEdit, previous, false, true));
        SpecialCardActions.preserveSave(incoming, previous, false, true);
        assertTrue(incoming.path("vitalityEffectRemoved").asBoolean());
        assertEquals(6, incoming.path("hp").path("on").asInt());
        assertFalse(incoming.has("vitalityRemoval"));
        assertEquals(previous.path("vitalityBenefits"), incoming.path("vitalityBenefits"));
        assertThrows(IllegalArgumentException.class, () -> SpecialCardActions.preserveSave(stale, incoming, false, true));
        SpecialCardActions.migrateVitality(incoming);
        assertTrue(incoming.path("effects").isEmpty());
        var actor = Campaign.Actor.builder().id("hero").name("Hero").testingCharacter(true).build();
        assertThrows(IllegalArgumentException.class, () -> SpecialCardActions.apply(incoming, actor,
                Campaign.builder().id("campaign").name("Campaign").build(), "vitality",
                mapper.readTree("{\"choices\":[\"hp\",\"stress\"]}"), 50, new Random(1)));
    }

    @Test
    void removingNewGrantReversesOnlyCapacityActuallyAdded() throws Exception {
        for (int total : new int[]{6, 7, 12}) {
            var actor = Campaign.Actor.builder().id("hero").name("Hero").testingCharacter(true).build();
            var campaign = Campaign.builder().id("campaign").name("Campaign").build();
            ObjectNode previous = (ObjectNode) mapper.readTree("""
                    {"hp":{"slots":6,"total":%d,"on":6},"stress":{"slots":6,"total":12,"on":0}}
                    """.formatted(total));
            SpecialCardActions.apply(previous, actor, campaign, "vitality",
                    mapper.readTree("{\"choices\":[\"hp\",\"stress\"]}"), 50, new Random(1));
            var incoming = previous.deepCopy();
            incoming.putArray("effects");
            incoming.withObject("/hp").put("slots", 6).put("total", total);
            incoming.withObject("/stress").put("slots", 6);
            incoming.putObject("vitalityRemoval").put("editMode", true).put("effectId", "vitality-permanent");
            SpecialCardActions.preserveSave(incoming, previous, true, true);
            assertEquals(total, incoming.path("hp").path("total").asInt());
            assertEquals(6, incoming.path("stress").path("slots").asInt());
        }
    }

    @Test
    void nightTerrorRollsExactlyStolenFearAndAcceptsZeroWithoutDice() throws Exception {
        var actor = Campaign.Actor.builder().id("hero").name("Hero").build();
        var campaign = Campaign.builder().id("campaign").name("Campaign").fear(3).build();
        var result = SpecialCardActions.apply(mapper.createObjectNode(), actor, campaign, "nightTerror",
                mapper.readTree("{\"amount\":3}"), 50, new Random(1));
        assertEquals(0, result.fear());
        assertEquals(3, result.roll().rolls().size());
        assertEquals("3d6", result.roll().expression());
        assertTrue(result.roll().total() >= 3 && result.roll().total() <= 18);
        assertEquals(3, campaign.getFear(), "Only a committed transaction changes the campaign.");
        result = SpecialCardActions.apply(mapper.createObjectNode(), actor, campaign, "nightTerror",
                mapper.readTree("{\"amount\":0}"), 50, new Random(1));
        assertNull(result.roll());
        assertEquals(3, result.fear());
    }
}
