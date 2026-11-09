package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArcanaSageRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode object(String json) throws Exception { return (ObjectNode) JSON.readTree(json); }

    @Test
    void amountsAreBoundedWholeNumbersAndDoNotMutateTheCatalogOrSheet() throws Exception {
        ObjectNode sheet = object("""
                {"hope":{"on":4,"slots":6},"stress":{"on":2,"slots":6},"resources":{"fane":3}}
                """);
        ObjectNode definition = object("""
                {"id":"summon","selectAmount":{"resource":"hope","store":"sprites"}}
                """);
        ObjectNode before = definition.deepCopy();
        ObjectNode chosen = ArcanaSageRules.selectAmount(definition, object("{\"amount\":3}"), sheet);
        assertEquals(3, chosen.path("cost").path("hope").asInt());
        assertEquals("clear", chosen.path("steps").get(0).path("op").asText());
        assertEquals(3, chosen.path("steps").get(1).path("amount").asInt());
        assertEquals(before, definition);
        assertEquals(4, sheet.path("hope").path("on").asInt());
        for (String invalid : List.of("0", "-1", "5", "1.5", "\"2\"", "null"))
            assertThrows(IllegalArgumentException.class, () -> ArcanaSageRules.selectAmount(definition, object("{\"amount\":" + invalid + "}"), sheet));
    }

    @Test
    void confusingAuraIncludesItsBaseLayerEvenWithZeroStress() throws Exception {
        ObjectNode definition = object("""
                {"selectAmount":{"resource":"stress","min":0,"store":"aura-layers","base":1}}
                """);
        ObjectNode sheet = object("{\"stress\":{\"on\":6,\"slots\":6}}");
        ObjectNode chosen = ArcanaSageRules.selectAmount(definition, object("{\"amount\":0}"), sheet);
        assertEquals(0, chosen.path("cost").path("stress").asInt());
        assertEquals(1, chosen.path("steps").get(1).path("amount").asInt());
        assertThrows(IllegalArgumentException.class, () -> ArcanaSageRules.selectAmount(definition, object("{\"amount\":1}"), sheet));
    }

    @Test
    void fallingSkyAndFaneScaleExactlyWithTheSelectedAmount() throws Exception {
        ObjectNode sheet = object("{\"stress\":{\"on\":1,\"slots\":6},\"resources\":{\"fane\":4}}");
        ObjectNode sky = ArcanaSageRules.selectAmount(object("""
                {"resourceRoll":{"resource":"stress","sides":20,"bonusPerDie":2,"type":"magic"}}
                """), object("{\"amount\":3}"), sheet);
        assertEquals("3d20+6", sky.path("damage").path("dice").asText());
        assertEquals(3, sky.path("cost").path("stress").asInt());
        ObjectNode fane = ArcanaSageRules.selectAmount(object("""
                {"selectAmount":{"resource":"fane","addToRoll":true}}
                """), object("{\"amount\":4}"), sheet);
        assertEquals("4", fane.path("addDice").asText());
        assertEquals(4, fane.path("cost").path("fane").asInt());
    }

    @Test
    void unleashChaosRequiresAtLeastOneTokenAndSteedsLimitTheRemainingMounts() throws Exception {
        ObjectNode sheet = object("{\"hope\":{\"on\":4,\"slots\":6},\"resources\":{\"chaos\":3}}");
        ObjectNode chaos = object("{\"castTokens\":{\"resource\":\"chaos\",\"sides\":10}}");
        assertEquals(2, ArcanaSageRules.selectAmount(chaos, object("{\"amount\":2}"), sheet).path("cost").path("chaos").asInt());
        assertThrows(IllegalArgumentException.class, () -> ArcanaSageRules.selectAmount(chaos, object("{\"amount\":0}"), sheet));
        ObjectNode steeds = object("{\"selectAmount\":{\"resource\":\"hope\"},\"benefitsPerAmount\":true}");
        assertEquals(2, ArcanaSageRules.selectAmount(steeds, object("{\"amount\":3}"), sheet).path("chatBenefitLimit").asInt());
    }

    @Test
    void surgeOverflowMarksStressAndExplainsTheDroppedFormExactlyOnce() throws Exception {
        ObjectNode sheet = object("""
                {"stress":{"on":1,"slots":6},"resources":{"wild-surge":7},
                 "effects":[{"id":"surge","name":"Wild Surge","surgeResource":"wild-surge"}]}
                """);
        ObjectNode roll = object("{\"source\":\"action,spellcast\"}");
        assertTrue(ArcanaSageRules.finishForms(sheet, roll).getFirst().contains("exceeded 6"));
        assertEquals(2, sheet.path("stress").path("on").asInt());
        assertEquals(0, sheet.path("resources").path("wild-surge").asInt());
        assertTrue(sheet.path("effects").isEmpty());
        assertTrue(ArcanaSageRules.finishForms(sheet, roll).isEmpty());
    }

    @Test
    void maintenanceRequiresTheExactEffectOptionButReactionsDoNotDropTheForm() throws Exception {
        ObjectNode sheet = object("{\"effects\":[{\"id\":\"form\",\"name\":\"Force of Nature\",\"maintainOnAction\":\"maintain\"}]}");
        assertTrue(ArcanaSageRules.finishForms(sheet, object("{\"source\":\"reaction\"}")).isEmpty());
        assertTrue(ArcanaSageRules.finishForms(sheet, object("{\"source\":\"action,option:effect:form:maintain\"}")).isEmpty());
        assertEquals(1, sheet.path("effects").size());
        assertEquals(List.of("Force of Nature ended."), ArcanaSageRules.finishForms(sheet, object("{\"source\":\"action\"}")));
        assertTrue(sheet.path("effects").isEmpty());
    }

    @Test
    void faneCriticalRewardRequiresASageSpellInTheActiveLoadout() throws Exception {
        ObjectNode trigger = object("{\"spellDomain\":\"Sage\"}");
        ObjectNode sheet = object("{\"loadout\":[{\"name\":\"Vicious Entangle\",\"domain\":\"Sage\"},{\"name\":\"Flight\",\"domain\":\"Arcana\"}]}");
        ArcanaSageRules.validateDomain(sheet, trigger, object("{\"source\":\"spellcast,card:Vicious Entangle\"}"));
        assertThrows(IllegalArgumentException.class, () -> ArcanaSageRules.validateDomain(sheet, trigger, object("{\"source\":\"spellcast,card:Flight\"}")));
    }
}
