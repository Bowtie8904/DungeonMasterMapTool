package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestPreparationTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() throws Exception {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "rest":{"startFacts":{"hope":"hope.value","armor":"armorTrack.marked"},
                         "short":{"moves":2,"maxMoves":4,"maxLongMoves":1,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":4}},
                 "automation":{
                   "effects":{"shelter":{"label":"Shelter","until":["rest.short","rest.long"]}},
                   "rules":[
                     {"id":"extra","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                      "when":{"eq":[{"read":"event.rest.kind"},"short"]},
                      "do":[{"op":"plan.adjust","plan":"event.rest","field":"moves","by":1,"label":"Extra move"},
                            {"op":"plan.adjust","plan":"event.rest","field":"longMoves","by":1,"label":"Long move"}],
                      "ui":{"label":"Extra"}},
                     {"id":"shelter","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"plan.adjust","plan":"event.rest","field":"moves","by":1,"label":"Shelter"},
                            {"op":"effect.start","effect":"shelter","target":"actor"}],
                      "ui":{"label":"Shelter","report":"Prepared shelter."}},
                     {"id":"not-option","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"button"}],
                      "ui":{"label":"Not a preparation option"}},
                     {"id":"deny","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"event.block","reason":"Rest is unavailable."}],"ui":{"label":"Deny"}},
                     {"id":"ordinary","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                      "ui":{"label":"Ordinary"}}
                   ]}}
                """;
        return AutomationHost.compile("test", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","shortRestsInRow":2,"hope":{"slots":6,"on":2},
                 "hp":{"slots":6,"on":1},"stress":{"slots":6,"on":2},
                 "armorTrack":{"slots":3,"on":1}}
                """);
    }

    private static ObjectNode origin() {
        return AutomationHost.event("action.request", "ada", 1).put("sessionId", "session");
    }

    private static ArrayNode choice(String rule, String binding) {
        ArrayNode choices = RuleJson.JSON.createArrayNode();
        choices.addObject().put("sourceInstanceId", "ruleset:base").put("ruleId", rule).put("bindingId", binding);
        return choices;
    }

    @Test
    void preparationUsesAuthoredBoundsAndStagesCostsAndEffectsInOrder() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var effectsBefore = HOST.restEffectSnapshot(sheet);
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("shelter", "rest"), StructuredRollTest.noRandom());
        assertEquals(4, prepared.at("/event/rest/moves").asInt());
        assertEquals(1, prepared.at("/event/rest/longMoves").asInt());
        assertEquals(4, prepared.at("/event/rest/adjustable/moves/max").asInt());
        assertEquals(2, prepared.at("/event/rest/start/hope").asInt());
        assertEquals(1, prepared.at("/event/rest/start/armor").asInt());
        assertEquals(3, prepared.at("/event/rest/adjustments").size());
        assertEquals("extra", prepared.at("/actions/0/ruleId").asText());
        assertEquals("shelter", prepared.at("/actions/1/ruleId").asText());
        ObjectNode missingStart = prepared.deepCopy();
        missingStart.withObject("/event/rest/start").remove("hope");
        assertThrows(IllegalArgumentException.class, () -> HOST.restProgress(pack, missingStart));
        ObjectNode malformedStart = prepared.deepCopy();
        malformedStart.withObject("/event/rest/start").putObject("hope");
        assertThrows(IllegalArgumentException.class, () -> HOST.restProgress(pack, malformedStart));
        assertEquals(1, sheet.at("/hope/on").asInt());
        assertEquals(1, HOST.revision(sheet));
        assertEquals(2, sheet.path("shortRestsInRow").asInt(), "Preparation does not finish a rest.");
        assertEquals(1, sheet.at("/automationState/effects").size());
        HOST.resetRest(pack, sheet, AutomationHost.event("rest.short", "ada", 1), effectsBefore);
        assertEquals(1, sheet.at("/automationState/effects").size(), "A preparation-created effect survives this rest.");

        ObjectNode longRest = sheet();
        ObjectNode result = HOST.prepareRest(pack, longRest, origin(), "long", 0,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals(2, result.at("/event/rest/moves").asInt());
        assertEquals(0, result.at("/event/rest/adjustable/longMoves/max").asInt());
        assertEquals(0, HOST.revision(longRest));
    }

    @Test
    void invalidRestPlansAndSelectionsNeverAlterTheSheet() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode before = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 1,
                choice("shelter", "rest"), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, sheet, origin(), "nap", 0,
                choice("shelter", "rest"), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("not-option", "rest"), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("ordinary", "sheet"), StructuredRollTest.noRandom()));
        ArrayNode duplicate = choice("shelter", "rest");
        duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                duplicate, StructuredRollTest.noRandom()));
        var denied = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("deny", "rest"), StructuredRollTest.noRandom()));
        assertEquals("blocked", denied.code());
        assertEquals("Rest is unavailable.", denied.getMessage());
        assertEquals(before, sheet);

        sheet.withObject("/hope").put("on", 0);
        before = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("shelter", "rest"), StructuredRollTest.noRandom()));
        assertEquals(before, sheet, "A failed cost must not preserve automatic adjustments.");
        sheet.withObject("/hope").put("on", 2);
        sheet.put("shortRestsInRow", 3);
        before = sheet.deepCopy();
        var blocked = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.prepareRest(pack, sheet, origin(), "short", 0,
                choice("shelter", "rest"), StructuredRollTest.noRandom()));
        assertEquals("restLimit", blocked.code());
        assertTrue(blocked.getMessage().contains("3"));
        assertEquals(before, sheet);
        HOST.prepareRest(pack, sheet, origin(), "long", 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals(3, sheet.path("shortRestsInRow").asInt(), "A long-rest preparation does not reset the counter.");
        assertEquals(2, sheet.at("/hope/on").asInt());
        assertEquals(0, HOST.revision(sheet));

        sheet.put("shortRestsInRow", -1);
        before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, sheet, origin(), "long", 0,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
        var noConfig = new AutomationHost.Pack("old", pack.programs(), pack.rulesetName());
        assertThrows(AutomationHost.NotReady.class, () -> HOST.prepareRest(noConfig, sheet, origin(), "long", 0,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void missingOrNonScalarInitialFactsRejectPreparationWithoutChangingTheSheet() throws Exception {
        var pack = pack();
        ObjectNode missing = sheet();
        missing.remove("hope");
        ObjectNode before = missing.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, missing, origin(), "short", 0,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom()));
        assertEquals(before, missing);

        ObjectNode invalid = sheet();
        invalid.withObject("/hope").putObject("on");
        before = invalid.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.prepareRest(pack, invalid, origin(), "short", 0,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom()));
        assertEquals(before, invalid);
    }
}
