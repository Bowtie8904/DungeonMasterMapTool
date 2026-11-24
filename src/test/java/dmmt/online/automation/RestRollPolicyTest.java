package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestRollPolicyTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"hp":{"slots":6,"on":4},"stress":{"slots":6,"on":5},
                 "armorTrack":{"slots":3,"on":1},"hope":{"slots":6,"on":1},
                 "features":[{"name":"Bedroll","instanceId":"bedroll","automationSource":"classes:bedroll"}]}
                """);
    }

    private static ObjectNode request(boolean reroll, String move) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", "short").put("expectedRevision", 0);
        if (reroll) request.putArray("preparation").addObject()
                .put("sourceInstanceId", "bedroll").put("ruleId", "comfort").put("bindingId", "rest");
        else request.putArray("preparation");
        ObjectNode selected = request.putArray("moves").addObject();
        selected.putObject("selection").put("sourceInstanceId", "ruleset:base").put("ruleId", move).put("bindingId", "move");
        selected.putArray("options");
        request.putArray("completion");
        return request;
    }

    private static AutomationHost.Pack pack(String... extraRules) throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","entries":[
                 {"id":"bedroll","name":"Bedroll","automation":{"rest":{"passives":[
                   {"id":"comfort","label":"Comfort","rest":"short","defaultOn":true,"reroll":true}]}}}]}
                """);
        assertEquals(1, converted.converted(), converted.diagnostics().toString());
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        for (String rule : extraRules) ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse(rule));
        return AutomationHost.compile("reroll", Map.of("classes", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static final String PREPARATION = """
            {"id":"preparation-roll","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
             "do":[{"op":"dice.roll","count":1,"sides":4,"as":"roll"}],
             "ui":{"report":"Preparation {result.roll}."}}
            """;
    private static final String COMPLETION = """
            {"id":"completion-roll","bindings":[{"id":"rest","event":"rest.short","surface":"rest","mode":"automatic"}],
             "do":[{"op":"dice.roll","count":1,"sides":6,"as":"roll"}],
             "ui":{"report":"Completion {result.roll}."}}
            """;

    @Test
    void convertedSelectedPolicyAppliesBeforePreparationThroughCompletion() throws Exception {
        AutomationHost.Pack pack = pack(PREPARATION, COMPLETION);
        ObjectNode selected = sheet();
        ObjectNode result = HOST.stageSelfRest(pack, selected,
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(true, "rest-stress"), StructuredRollTest.faces(1, 4, 2, 3, 1, 6));
        assertEquals("keepHigher", result.at("/preparationEvent/rest/rollPolicy").asText());
        assertEquals(5, result.at("/preparationEvent/rest/start/stress").asInt());
        assertTrue(result.path("actions").findValuesAsText("report").contains("Preparation 4."));
        assertTrue(result.path("actions").findValuesAsText("report").contains("Completion 6."));
        assertEquals(1, selected.at("/stress/on").asInt());
        assertTrue(result.path("actions").findValues("alternateFaces").size() >= 3);
        assertEquals(3, result.path("outbox").findValues("roll").getFirst()
                .at("/contributions/0/dice/0/face").asInt());

        ObjectNode unselected = sheet();
        ObjectNode plain = HOST.stageSelfRest(pack, unselected,
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(false, "rest-stress"), StructuredRollTest.faces(1, 2, 1));
        assertTrue(plain.at("/preparationEvent/rest/rollPolicy").isMissingNode());
        assertEquals(2, unselected.at("/stress/on").asInt());
        assertTrue(plain.path("actions").findValues("alternateFaces").isEmpty());
    }

    @Test
    void policyKeepsWholeExpressionTotalsAndEachIndependentTableResult() throws Exception {
        AutomationHost.Pack pack = pack("""
                {"id":"experiment","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"experiment","kind":"short"}}],
                 "do":[{"op":"dice.roll","count":2,"sides":6,"as":"sum"},
                       {"op":"dice.table","count":2,"outcomes":["one","two","three","four"],"as":"table"},
                       {"op":"roll.resolve","plan":{"literal":{"id":"roll","contributions":[
                         {"id":"base","kind":"dice","role":"bonus","sourceId":"ruleset:base","label":"Dice","count":2,"sides":6}]}},
                        "as":"structured"}],
                 "ui":{"label":"Experiment","report":"Total {result.sum}; table {result.table.outcomes|list}; structured {result.structured.total}."}}
                """);
        ObjectNode selected = sheet();
        ObjectNode result = HOST.stageSelfRest(pack, selected,
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(true, "experiment"), StructuredRollTest.faces(1, 6, 4, 2, 1, 4, 3, 2, 1, 2, 6, 4));
        assertTrue(result.path("actions").findValuesAsText("report")
                .contains("Total 7; table four, three; structured 10."));
        JsonNode action = null;
        for (JsonNode candidate : result.path("actions"))
            if ("experiment".equals(candidate.path("ruleId").asText())) action = candidate;
        assertNotNull(action);
        assertEquals(1, action.at("/diceRolls/0/faces/0").asInt());
        assertEquals(6, action.at("/diceRolls/0/faces/1").asInt());
        assertEquals(4, action.at("/diceRolls/0/alternateFaces/0").asInt());
        assertEquals(3, action.at("/diceRolls/2/total").asInt());
        assertEquals(10, action.at("/diceRolls/3/total").asInt());
    }

    @Test
    void policyComparesHitCountsAndHighestDieResults() throws Exception {
        AutomationHost.Pack pack = pack("""
                {"id":"experiment","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"experiment","kind":"short"}}],
                 "do":[{"op":"dice.hits","count":2,"sides":6,"atLeast":3,"as":"hits"},
                       {"op":"dice.highest","count":2,"sides":6,"as":"highest"}],
                 "ui":{"label":"Experiment","report":"Hits {result.hits}; highest {result.highest}."}}
                """);
        ObjectNode result = HOST.stageSelfRest(pack, sheet(),
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(true, "experiment"), StructuredRollTest.faces(1, 1, 3, 5, 6, 1, 5, 4));
        assertTrue(result.path("actions").findValuesAsText("report").contains("Hits 2; highest 6."));
        JsonNode action = null;
        for (JsonNode candidate : result.path("actions"))
            if ("experiment".equals(candidate.path("ruleId").asText())) action = candidate.path("diceRolls");
        assertNotNull(action);
        assertEquals(3, action.at("/0/faces/0").asInt());
        assertEquals(1, action.at("/0/alternateFaces/0").asInt());
        assertEquals(6, action.at("/1/faces/0").asInt());
        assertEquals(5, action.at("/1/alternateFaces/0").asInt());
    }

    @Test
    void nestedTrackReactionsShareTheSelectedRestRollPolicy() throws Exception {
        AutomationHost.Pack pack = pack("""
                {"id":"reaction-roll","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                 "when":{"eq":[{"read":"event.track"},"stress"]},
                 "do":[{"op":"dice.roll","count":1,"sides":6,"as":"roll"}],
                 "ui":{"report":"Reaction {result.roll}."}}
                """);
        ObjectNode selected = HOST.stageSelfRest(pack, sheet(),
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(true, "rest-stress"), StructuredRollTest.faces(2, 3, 1, 6));
        assertTrue(selected.path("actions").findValuesAsText("report").contains("Reaction 6."));
        ObjectNode plain = HOST.stageSelfRest(pack, sheet(),
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request(false, "rest-stress"), StructuredRollTest.faces(2, 1));
        assertTrue(plain.path("actions").findValuesAsText("report").contains("Reaction 1."));
    }

    @Test
    void policyMetadataOnlyCompilesOnSelectedRestPreparation() throws Exception {
        for (String binding : new String[] {
                """
                {"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic","restRollPolicy":"keepHigher"}
                """,
                """
                {"id":"move","event":"downtime.move","surface":"downtime","mode":"option","restRollPolicy":"keepHigher"}
                """,
                """
                {"id":"rest","event":"rest.prepare","surface":"rest","mode":"option","restRollPolicy":"keepLower"}
                """
        }) {
            AutomationHost.NotReady error = assertThrows(AutomationHost.NotReady.class, () -> pack("""
                    {"id":"invalid","bindings":[%s],"ui":{"label":"Invalid"}}
                    """.formatted(binding)));
            assertTrue(error.getMessage().contains("restRollPolicy"), error.getMessage());
        }
    }

    @Test
    void converterReportsUnsupportedRerollCombinations() throws Exception {
        for (String extra : new String[] {
                "\"move\":\"tend\"",
                "\"condition\":\"noRecovery\"",
                "\"steps\":[{\"op\":\"gain\",\"resource\":\"hope\",\"amount\":1}]"
        }) {
            var converted = LegacyCatalogConverter.convert("""
                    {"format":"dmmap-daggerheart","version":1,"category":"classes","entries":[
                     {"id":"bedroll","name":"Bedroll","automation":{"rest":{"passives":[
                       {"id":"comfort","label":"Comfort","rest":"short","reroll":true,%s}]}}}]}
                    """.formatted(extra));
            assertFalse(converted.diagnostics().isEmpty(), extra);
            assertTrue(converted.diagnostics().toString().contains("reroll"), converted.diagnostics().toString());
        }
    }
}
