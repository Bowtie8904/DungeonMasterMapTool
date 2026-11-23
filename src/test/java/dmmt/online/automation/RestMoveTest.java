package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestMoveTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(int moves) {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "rest":{"startFacts":{"hope":"hope.value"},
                         "short":{"moves":%1$d,"maxMoves":%1$d,"maxLongMoves":1,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":2}},
                 "automation":{"rules":[
                   {"id":"long-allowance","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                    "when":{"eq":[{"read":"event.rest.kind"},"short"]},
                    "do":[{"op":"plan.adjust","plan":"event.rest","field":"longMoves","by":1,"label":"Long move"}],
                    "ui":{"label":"Long move"}},
                   {"id":"bonus","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                    "when":{"eq":[{"read":"event.move.id"},"mend"]},
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":2,"label":"Bonus"}],
                    "ui":{"label":"Bonus"}},
                   {"id":"more","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"option"}],
                    "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":1,"label":"More"}],
                    "ui":{"label":"More"}},
                   {"id":"mend","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"short"}}],
                    "do":[{"op":"dice.roll","count":1,"sides":4,"as":"roll"},
                          {"op":"track.clear","track":"actor.stress","amount":{"add":[{"read":"result.roll"},{"read":"event.move.bonus"}]},"as":"clear"}],
                    "ui":{"label":"Mend","report":"Cleared {result.clear} Stress."}},
                   {"id":"mend-ally","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"short","ally":true}}],"ui":{"label":"Mend ally"}},
                   {"id":"free","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"free","kind":"any"}}],
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"slots","by":-1,"label":"Additional move"}],
                    "ui":{"label":"Free"}},
                   {"id":"long-a","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"long-a","kind":"longMove"}}],"ui":{"label":"Long A"}},
                   {"id":"long-b","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"long-b","kind":"longMove"}}],"ui":{"label":"Long B"}},
                   {"id":"long-only","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"long-only","kind":"long"}}],"ui":{"label":"Long only"}}
                 ]}}
                """.formatted(moves);
        return AutomationHost.compile("test", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","hope":{"slots":6,"on":2},"hp":{"slots":6,"on":2},
                 "stress":{"slots":6,"on":6},"armorTrack":{"slots":3,"on":1}}
                """);
    }

    private static ObjectNode origin() {
        return AutomationHost.event("action.request", "ada", 1).put("sessionId", "session");
    }

    private static ObjectNode select(String rule) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base").put("ruleId", rule).put("bindingId", "move");
    }

    private static ArrayNode options(String... rules) {
        ArrayNode choices = RuleJson.JSON.createArrayNode();
        for (String rule : rules) choices.add(select(rule));
        return choices;
    }

    @Test
    void hostDerivesMoveFactsAndAppliesOptionsBeforeItsDiceAndRecovery() throws Exception {
        var pack = pack(2);
        ObjectNode sheet = sheet();
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0, options(), StructuredRollTest.noRandom());
        var progress = HOST.restProgress(pack, prepared);
        progress.plan().withObject("/start").put("hope", 999);
        prepared.withObject("/event/rest/start").put("hope", 999);
        var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, select("mend"),
                options("more"), StructuredRollTest.faces(1));
        assertEquals(2, result.response().at("/event/rest/start/hope").asInt());
        assertEquals(1, result.progress().slotsUsed());
        assertEquals(3, result.response().at("/event/move/bonus").asInt());
        assertEquals("mend", result.response().at("/event/move/id").asText());
        assertFalse(result.response().at("/event/move/ally").asBoolean());
        assertEquals(2, sheet.at("/stress/on").asInt());
        assertEquals(1, sheet.at("/hope/on").asInt());
        assertEquals("bonus", result.response().at("/actions/0/ruleId").asText());
        assertEquals("more", result.response().at("/actions/1/ruleId").asText());
        assertEquals("mend", result.response().at("/actions/2/ruleId").asText());
        assertEquals(1, result.response().at("/actions/2/diceRolls/0/faces/0").asInt());
        assertEquals("Cleared 4 Stress.", result.response().at("/actions/2/report").asText());

        ObjectNode before = sheet.deepCopy();
        var duplicate = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), result.progress(), select("mend-ally"), options(), StructuredRollTest.noRandom()));
        assertEquals("duplicateMove", duplicate.code());
        assertEquals(before, sheet);
        assertEquals(1, result.progress().slotsUsed());
    }

    @Test
    void moveSlotsLongAllowancesAndRestEligibilityAreEnforcedAtomically() throws Exception {
        var pack = pack(1);
        ObjectNode sheet = sheet();
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0, options(), StructuredRollTest.noRandom());
        var progress = HOST.restProgress(pack, prepared);
        var free = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress,
                select("free"), options(), StructuredRollTest.noRandom());
        assertEquals(0, free.progress().slotsUsed());
        assertEquals(0, free.response().at("/event/move/slots").asInt());
        var longMove = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), free.progress(),
                select("long-a"), options(), StructuredRollTest.noRandom());
        assertEquals(1, longMove.progress().slotsUsed());
        assertEquals(1, longMove.progress().longMovesUsed());
        assertTrue(longMove.response().at("/event/move/long").asBoolean());
        ObjectNode before = sheet.deepCopy();
        var exhausted = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), longMove.progress(), select("long-b"), options(), StructuredRollTest.noRandom()));
        assertEquals("longMove", exhausted.code());
        var full = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), longMove.progress(), select("mend"), options("more"), StructuredRollTest.faces(1)));
        assertEquals("restMoves", full.code());
        assertEquals(before, sheet, "Rejected moves cannot spend options or change resources.");
        var wrongRest = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), longMove.progress(), select("long-only"), options(), StructuredRollTest.noRandom()));
        assertEquals("move", wrongRest.code());
        assertEquals(before, sheet);
    }

    @Test
    void untrustedMoveFactsAndWrongOptionsAreRejected() throws Exception {
        var pack = pack(2);
        ObjectNode sheet = sheet();
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0, options(), StructuredRollTest.noRandom());
        var progress = HOST.restProgress(pack, prepared);
        ObjectNode before = sheet.deepCopy();
        ObjectNode forged = select("mend");
        forged.put("bonus", 100);
        assertThrows(IllegalArgumentException.class, () -> HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"),
                progress, forged, options(), StructuredRollTest.noRandom()));
        assertThrows(IllegalArgumentException.class, () -> HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"),
                progress, select("free"), options("mend"), StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
        assertEquals(0, progress.count());
    }

    @Test
    void movesCannotAlterTheAlreadyPreparedRestPlan() throws Exception {
        var base = pack(2);
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"alter","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "do":[{"op":"plan.adjust","plan":"event.rest","field":"moves","by":1,"label":"Extra"}],
                 "ui":{"label":"Alter"}}]}
                """);
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:alter", RuleCompiler.compile("features:alter", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("test", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Alter").put("instanceId", "alter")
                .put("automationSource", "features:alter");
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0, options(), StructuredRollTest.noRandom());
        var progress = HOST.restProgress(pack, prepared);
        ObjectNode before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"),
                progress, select("mend"), options(), StructuredRollTest.faces(1)));
        assertEquals(before, sheet);
        assertEquals(0, progress.count());
    }

    @Test
    void duplicateGroupsAreScopedToTheActiveSourceInstance() throws Exception {
        var base = pack(2);
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"craft","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                 "restMove":{"id":"craft","kind":"any","group":"work"}}],"ui":{"label":"Craft"}}]}
                """);
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:craft", RuleCompiler.compile("features:craft", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("test", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features")
                .addObject().put("name", "Kit A").put("instanceId", "a").put("automationSource", "features:craft");
        sheet.withArray("features")
                .addObject().put("name", "Kit B").put("instanceId", "b").put("automationSource", "features:craft");
        ObjectNode prepared = HOST.prepareRest(pack, sheet, origin(), "short", 0, options(), StructuredRollTest.noRandom());
        var progress = HOST.restProgress(pack, prepared);
        ObjectNode first = select("craft").put("sourceInstanceId", "a");
        ObjectNode second = select("craft").put("sourceInstanceId", "b");
        var usedA = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, first, options(), StructuredRollTest.noRandom());
        var usedB = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), usedA.progress(), second, options(), StructuredRollTest.noRandom());
        assertEquals(2, usedB.progress().slotsUsed());
        assertEquals(2, usedB.progress().groups().size());
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), usedB.progress(), first, options(), StructuredRollTest.noRandom())).code());
    }
}
