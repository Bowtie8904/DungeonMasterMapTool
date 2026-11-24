package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestCompletionTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "rest":{"startFacts":{"hitPoints":"hp.marked","stress":"stress.marked","hope":"hope.value"},
                         "short":{"moves":2,"maxMoves":3,"maxLongMoves":1,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":3}},
                 "automation":{
                   "limits":{"once":{"max":1,"resetOn":["rest.short","rest.long"]}},
                   "effects":{
                     "before":{"label":"Before","until":["rest.short","rest.long"]},
                     "after":{"label":"After","until":["rest.short","rest.long"]}
                   },
                   "rules":[
                     {"id":"use","limitRef":"once",
                      "bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                      "ui":{"label":"Use"}},
                     {"id":"mixed","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"mixed","kind":"any"}}],
                      "do":[{"op":"track.clear","track":"actor.hp","amount":1},
                            {"op":"track.mark","track":"actor.stress","amount":1}],"ui":{"label":"Mixed"}},
                     {"id":"ally","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"ally","kind":"any","ally":true}}],
                      "do":[{"op":"track.clear","track":"actor.hp","amount":2}],"ui":{"label":"Ally"}},
                     {"id":"before","bindings":[
                       {"id":"short","event":"rest.short","surface":"rest","mode":"option"},
                       {"id":"long","event":"rest.long","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"before","target":"actor"},
                            {"op":"countdown.start","value":2,"label":"Before"}],
                      "ui":{"label":"Before","report":"Before reset."}},
                     {"id":"after","bindings":[
                       {"id":"short","event":"rest.short","surface":"rest","mode":"option","restPhase":"afterReset"},
                       {"id":"long","event":"rest.long","surface":"rest","mode":"option","restPhase":"afterReset"}],
                      "when":{"and":[{"eq":[{"read":"event.rest.cleared.hitPoints"},0]},
                                      {"eq":[{"read":"event.rest.cleared.stress"},0]}]},
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"after","target":"actor"},
                            {"op":"countdown.start","value":3,"label":"After"}],
                      "ui":{"label":"After","report":"After reset."}},
                     {"id":"heal","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "do":[{"op":"track.clear","track":"actor.hp","amount":1}],"ui":{"label":"Heal"}},
                     {"id":"deny","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "do":[{"op":"event.block","reason":"No rest."}],"ui":{"label":"Deny"}},
                     {"id":"alter","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "do":[{"op":"plan.adjust","plan":"event.rest","field":"moves","by":1,"label":"Extra"}],
                      "ui":{"label":"Alter"}},
                     {"id":"other","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option","audience":"others"}],
                      "ui":{"label":"Other"}}
                   ]}}
                """;
        return AutomationHost.compile("completion", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","shortRestsInRow":2,"longRestRevision":4,
                 "hope":{"slots":6,"on":2},"hp":{"slots":6,"on":3},
                 "stress":{"slots":6,"on":2},"armorTrack":{"slots":3,"on":0}}
                """);
        ObjectNode state = HOST.projection().state(sheet);
        state.putArray("effects").addObject().put("id", "old").putArray("until").add("rest.short").add("rest.long");
        state.withObject("/sources").putObject("ruleset:base").putObject("usage").put("once", 1);
        return sheet;
    }

    private static ObjectNode origin() {
        return AutomationHost.event("action.request", "ada", 1).put("sessionId", "session");
    }

    private static ObjectNode choice(String rule, String binding) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", binding);
    }

    private static ArrayNode choices(ObjectNode... selections) {
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (ObjectNode selection : selections) result.add(selection);
        return result;
    }

    private static AutomationHost.RestProgress prepare(AutomationHost.Pack pack, ObjectNode sheet, String kind) {
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), kind, 0, choices(), StructuredRollTest.noRandom());
        return HOST.restProgress(pack, prepared);
    }

    @Test
    void completesShortAndLongRestsAroundTheSnapshotReset() throws Exception {
        var pack = pack();
        for (String kind : List.of("short", "long")) {
            ObjectNode sheet = sheet();
            var snapshot = HOST.restEffectSnapshot(sheet);
            var progress = prepare(pack, sheet, kind);
            ObjectNode result = HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                    choices(choice("before", kind), choice("after", kind)), StructuredRollTest.noRandom());
            assertEquals(List.of("before", "after"), result.path("actions").findValuesAsText("ruleId"));
            assertEquals(List.of("Before", "After"), result.path("outbox").findValuesAsText("label"));
            assertEquals(List.of("Before", "After"), sheet.at("/automationState/effects").findValuesAsText("label"));
            assertEquals(0, sheet.at("/hope/on").asInt());
            assertEquals(0, sheet.at("/automationState/sources/ruleset:base/usage/once").asInt());
            assertEquals("afterReset", result.at("/event/rest/phase").asText());
            assertEquals(0, result.at("/event/rest/cleared/hitPoints").asInt());
            assertEquals(0, result.at("/event/rest/cleared/stress").asInt());
            assertEquals(1, result.at("/event/rest/cleared/hope").asInt());
            assertTrue(result.path("skipped").isEmpty());
            assertEquals("short".equals(kind) ? 3 : 0, sheet.path("shortRestsInRow").asInt());
            assertEquals("long".equals(kind) ? 5 : 4, sheet.path("longRestRevision").asInt());
            assertEquals(HOST.revision(sheet), result.path("revision").asLong());
        }
    }

    @Test
    void individuallySignedRecoverySkipsAConditionWithoutChargingIt() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var snapshot = HOST.restEffectSnapshot(sheet);
        var progress = prepare(pack, sheet, "short");
        var move = HOST.restMove(pack, sheet, origin(), progress, choice("mixed", "move"),
                choices(), StructuredRollTest.noRandom());
        ObjectNode result = HOST.completeRest(pack, sheet, origin(), move.progress(), snapshot,
                choices(choice("before", "short"), choice("after", "short")), StructuredRollTest.noRandom());
        assertEquals(1, result.at("/event/rest/cleared/hitPoints").asInt());
        assertEquals(-1, result.at("/event/rest/cleared/stress").asInt());
        assertEquals(1, sheet.at("/hope/on").asInt());
        assertEquals(List.of("before"), result.path("actions").findValuesAsText("ruleId"));
        assertEquals("after", result.at("/skipped/0/ruleId").asText());
        assertEquals("afterReset", result.at("/skipped/0/phase").asText());
        assertEquals(List.of("Before"), sheet.at("/automationState/effects").findValuesAsText("label"));
    }

    @Test
    void recoveryIsRecomputedAfterBeforeResetBenefits() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var snapshot = HOST.restEffectSnapshot(sheet);
        var progress = prepare(pack, sheet, "short");
        ObjectNode result = HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                choices(choice("heal", "short"), choice("after", "short")), StructuredRollTest.noRandom());
        assertEquals(2, sheet.at("/hp/on").asInt());
        assertEquals(1, result.at("/event/rest/cleared/hitPoints").asInt());
        assertEquals("after", result.at("/skipped/0/ruleId").asText());
        assertEquals(2, sheet.at("/hope/on").asInt());
    }

    @Test
    void failuresInEitherPhaseLeaveTheStagedSheetUnchanged() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var snapshot = HOST.restEffectSnapshot(sheet);
        var progress = prepare(pack, sheet, "short");
        sheet.withObject("/hope").put("on", 1);
        ObjectNode before = sheet.deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.completeRest(pack, sheet, origin(),
                progress, snapshot, choices(choice("before", "short"), choice("after", "short")),
                StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet, "A failed post-reset cost must roll back prior costs, resets and effects.");
        assertEquals("blocked", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.completeRest(pack, sheet, origin(),
                progress, snapshot, choices(choice("deny", "short")), StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);
        assertThrows(IllegalArgumentException.class, () -> HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                choices(choice("alter", "short")), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void invalidChoicesAndSkippedInputsAreNotSilentlyAccepted() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var snapshot = HOST.restEffectSnapshot(sheet);
        var progress = prepare(pack, sheet, "short");
        var moved = HOST.restMove(pack, sheet, origin(), progress, choice("mixed", "move"),
                choices(), StructuredRollTest.noRandom());
        ObjectNode before = sheet.deepCopy();
        ObjectNode invalidInput = choice("after", "short");
        invalidInput.putObject("inputs").put("invented", true);
        assertThrows(IllegalArgumentException.class, () -> HOST.completeRest(pack, sheet, origin(), moved.progress(),
                snapshot, choices(invalidInput), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.completeRest(pack, sheet, origin(), moved.progress(),
                snapshot, choices(choice("before", "short"), choice("before", "short")), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.completeRest(pack, sheet, origin(), moved.progress(),
                snapshot, choices(choice("before", "long")), StructuredRollTest.noRandom()));
        assertThrows(SecurityException.class, () -> HOST.completeRest(pack, sheet, origin(), moved.progress(),
                snapshot, choices(choice("other", "short")), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void missingRecoveryFactsAndSelfOnlyAllyMovesFailSafely() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        var snapshot = HOST.restEffectSnapshot(sheet);
        var progress = prepare(pack, sheet, "short");
        ObjectNode before = sheet.deepCopy();
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                origin(), progress, choice("ally", "move"), choices(), StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);
        sheet.remove("hp");
        before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                choices(), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void convertedNoRecoveryBenefitUsesTheCompletedRestFacts() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"items","entries":[
                 {"id":"camp","name":"Camp","automation":{"rest":{"passives":[
                   {"id":"idle","label":"Idle","condition":"noRecovery",
                    "steps":[{"op":"gain","resource":"hope","amount":1}]}
                 ]}}}]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        var pack = AutomationHost.compile("converted", Map.of("items", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        for (boolean recover : List.of(false, true)) {
            ObjectNode sheet = sheet();
            HOST.projection().state(sheet).withObject("/sources").remove("ruleset:base");
            sheet.putArray("inventory").addObject().put("name", "Camp").put("qty", 1)
                    .put("instanceId", "camp").put("automationSource", "items:camp");
            var snapshot = HOST.restEffectSnapshot(sheet);
            var progress = prepare(pack, sheet, "short");
            if (recover)
                progress = HOST.restMove(pack, sheet, origin(), progress, choice("rest-tend", "move"),
                        choices(), StructuredRollTest.faces(1)).progress();
            ObjectNode result = HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                    choices(choice("idle", "rest-short").put("sourceInstanceId", "camp")), StructuredRollTest.noRandom());
            assertEquals(recover ? 2 : 3, sheet.at("/hope/on").asInt());
            assertEquals(recover ? 1 : 0, result.path("skipped").size());
            assertEquals(recover ? 0 : 1, result.path("actions").size());
        }
    }

    @Test
    void convertedCompletionDiceAreOfferedOnlyOnShortRests() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"items","entries":[
                 {"id":"camp","name":"Camp","automation":{"rest":{"passives":[
                   {"id":"mend","label":"Mend","steps":[{"op":"clear","resource":"hp","amount":"1d4"}]}
                 ]}}}]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        var pack = AutomationHost.compile("converted", Map.of("items", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        for (String kind : List.of("short", "long")) {
            ObjectNode sheet = sheet();
            HOST.projection().state(sheet).withObject("/sources").remove("ruleset:base");
            sheet.putArray("inventory").addObject().put("name", "Camp").put("qty", 1)
                    .put("instanceId", "camp").put("automationSource", "items:camp");
            var snapshot = HOST.restEffectSnapshot(sheet);
            var progress = prepare(pack, sheet, kind);
            ObjectNode result = HOST.completeRest(pack, sheet, origin(), progress, snapshot,
                    choices(choice("mend", "rest-" + kind).put("sourceInstanceId", "camp")), StructuredRollTest.faces(2));
            if ("short".equals(kind)) {
                assertEquals(1, result.path("outbox").size());
                assertEquals("recovery.offerRoll", result.at("/outbox/0/kind").asText());
                assertEquals("hp", result.at("/outbox/0/track").asText());
            } else assertTrue(result.path("outbox").isEmpty());
        }
    }
}
