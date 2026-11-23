package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestTransactionTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "rest":{"startFacts":{"hitPoints":"hp.marked","stress":"stress.marked","hope":"hope.value"},
                         "short":{"moves":2,"maxMoves":2,"maxLongMoves":0,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":2}},
                 "automation":{
                   "limits":{"once":{"max":1,"resetOn":["rest.short","rest.long"]}},
                   "effects":{
                     "old":{"label":"Old","locks":{"hp":2},"restExpiry":"beforeMoves",
                            "until":["rest.short","rest.long"]},
                     "shelter":{"label":"Shelter","until":["rest.short","rest.long"]},
                     "new":{"label":"New","until":["rest.short","rest.long"]}
                   },
                   "rules":[
                     {"id":"prepare","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"shelter","target":"actor"},
                            {"op":"countdown.start","value":2,"label":"Prepared"}],
                      "ui":{"label":"Prepare","report":"Prepared."}},
                     {"id":"mend","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"mend","kind":"any"}}],
                      "do":[{"op":"dice.roll","count":1,"sides":4,"as":"roll"},
                            {"op":"track.clear","track":"actor.hp","amount":{"add":[{"read":"result.roll"},1]},"as":"clear"},
                            {"op":"countdown.start","value":2,"label":"Move"}],
                      "ui":{"label":"Mend","report":"Cleared {result.clear} HP."}},
                     {"id":"ready","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"ready","kind":"any"}}],
                      "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"Ready"}},
                     {"id":"ally","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"ally","kind":"any","ally":true}}],"ui":{"label":"Ally"}},
                     {"id":"before","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"new","target":"actor"},
                            {"op":"countdown.start","value":2,"label":"Finished"}],
                      "ui":{"label":"Before","report":"Finished."}},
                     {"id":"after","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option",
                       "restPhase":"afterReset"}],
                      "when":{"eq":[{"read":"event.rest.cleared.hitPoints"},0]},
                      "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"After"}}
                   ]}}
                """;
        return AutomationHost.compile("transaction", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","shortRestsInRow":1,"longRestRevision":4,"permanentHp":1,
                 "hope":{"slots":6,"on":3},"hp":{"slots":6,"on":6},
                 "stress":{"slots":6,"on":2},"armorTrack":{"slots":3,"on":0}}
                """);
        ObjectNode state = HOST.projection().state(sheet).put("revision", 9);
        state.withObject("/sources").putObject("ruleset:base").putObject("usage").put("once", 1);
        ObjectNode old = state.putArray("effects").addObject().put("id", "old").put("label", "Old")
                .put("effect", "ruleset:base#old").put("sourceInstanceId", "ruleset:base").put("sourceActorId", "ada")
                .put("restExpiry", "beforeMoves");
        old.putArray("until").add("rest.short").add("rest.long");
        old.putObject("locks").put("hp", 2);
        return sheet;
    }

    private static ObjectNode origin() {
        return AutomationHost.event("action.request", "ada", 1).put("sessionId", "session");
    }

    private static ObjectNode choice(String rule, String binding) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", binding);
    }

    private static ObjectNode request(String kind) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", kind).put("expectedRevision", 9);
        request.putArray("preparation");
        request.putArray("moves");
        request.putArray("completion");
        return request;
    }

    private static void move(ObjectNode request, String rule) {
        ObjectNode move = request.withArray("moves").addObject();
        move.set("selection", choice(rule, "move"));
        move.putArray("options");
    }

    @Test
    void completeSequencePreservesOrderingSnapshotsAndOneRevision() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), request = request("short");
        request.withArray("preparation").add(choice("prepare", "rest"));
        move(request, "mend");
        move(request, "ready");
        request.withArray("completion").add(choice("before", "short")).add(choice("after", "short"));
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), request, StructuredRollTest.faces(4));

        assertEquals(10, HOST.revision(sheet));
        assertEquals(10, result.path("revision").asLong());
        assertEquals(1, sheet.at("/hp/on").asInt());
        assertEquals(2, sheet.at("/hope/on").asInt());
        assertEquals(2, sheet.path("shortRestsInRow").asInt());
        assertEquals(4, sheet.path("longRestRevision").asInt());
        assertEquals(0, sheet.at("/automationState/sources/ruleset:base/usage/once").asInt());
        assertEquals(List.of("Shelter", "New"), sheet.at("/automationState/effects").findValuesAsText("label"));
        assertEquals(List.of("prepare", "mend", "ready", "before"), result.path("actions").findValuesAsText("ruleId"));
        assertEquals(List.of("Prepared", "Move", "Finished"), result.path("outbox").findValuesAsText("label"));
        assertEquals("Cleared 5 HP.", result.at("/actions/1/report").asText());
        assertEquals(4, result.at("/actions/1/diceRolls/0/faces/0").asInt());
        assertEquals(6, result.at("/preparationEvent/rest/start/hitPoints").asInt());
        assertEquals(List.of("mend", "ready"), result.path("moveEvents").findValuesAsText("id"));
        assertEquals(5, result.at("/event/rest/cleared/hitPoints").asInt());
        assertEquals("afterReset", result.at("/event/rest/phase").asText());
        assertEquals("after", result.at("/skipped/0/ruleId").asText());
        assertEquals(2, result.at("/progress/slotsUsed").asInt());
        assertEquals(2, result.at("/progress/count").asInt());
    }

    @Test
    void failuresAfterPreparationAndRollingDiscardEveryStage() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), request = request("short"), before = sheet.deepCopy();
        request.withArray("preparation").add(choice("prepare", "rest"));
        move(request, "mend");
        move(request, "mend");
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request, StructuredRollTest.faces(4))).code());
        assertEquals(before, sheet);

        ObjectNode costly = request("short");
        costly.withArray("preparation").add(choice("prepare", "rest"));
        move(costly, "mend");
        costly.withArray("completion").add(choice("before", "short"));
        sheet.withObject("/hope").put("on", 1);
        before = sheet.deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), costly, StructuredRollTest.faces(4))).code());
        assertEquals(before, sheet, "A final cost failure must not retain expiry, preparation, dice or recovery.");
    }

    @Test
    void staleMalformedAndRecipientRequestsCannotMutateTheSheet() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), before = sheet.deepCopy();
        ObjectNode stale = request("short").put("expectedRevision", 8);
        assertEquals("stale", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), stale, StructuredRollTest.noRandom())).code());
        ObjectNode forged = request("short");
        move(forged, "ready");
        ((ObjectNode) forged.at("/moves/0")).put("slots", 0);
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), forged, StructuredRollTest.noRandom()));
        ObjectNode ally = request("short");
        move(ally, "ally");
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), ally, StructuredRollTest.noRandom())).code());
        ObjectNode missing = request("short");
        missing.remove("completion");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), missing, StructuredRollTest.noRandom()));
        assertEquals(before, sheet);

        sheet.withObject("/automationState").put("revision", "bad");
        ObjectNode malformed = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request("long"), StructuredRollTest.noRandom()));
        assertEquals(malformed, sheet);
    }

    @Test
    void commandsNeedingInterstageHostSettlementAreRejectedAtomically() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"fear","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                 "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"Fear"}],"ui":{"label":"Fear"}}]}
                """);
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:fear", RuleCompiler.compile("features:fear", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("test", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Fear").put("instanceId", "fear")
                .put("automationSource", "features:fear");
        ObjectNode before = sheet.deepCopy();
        var error = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request("short"), StructuredRollTest.noRandom()));
        assertEquals("restCommand", error.code());
        assertTrue(error.getMessage().contains("table.gain"));
        assertEquals(before, sheet);
    }

    @Test
    void longRestWithoutMovesStillCompletesAndAdvancesOneRevision() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), request("long"), StructuredRollTest.noRandom());
        assertEquals(10, result.path("revision").asLong());
        assertEquals(10, HOST.revision(sheet));
        assertEquals(0, sheet.path("shortRestsInRow").asInt());
        assertEquals(5, sheet.path("longRestRevision").asInt());
        assertEquals(0, result.at("/progress/count").asInt());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
    }
}
