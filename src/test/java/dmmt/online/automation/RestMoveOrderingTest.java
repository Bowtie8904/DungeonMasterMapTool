package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestMoveOrderingTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack convertedPack() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"items","entries":[
                 {"id":"camp","name":"Camp","automation":{"rest":{
                   "modifiers":[{"move":"stress","bonus":1,
                     "steps":[{"op":"mark","resource":"stress","amount":1}],"note":"Recover then mark."}],
                   "passives":[{"id":"tea","label":"Tea","move":"stress","cost":{"hope":1},
                     "steps":[{"op":"clear","resource":"hp","amount":1}]},
                     {"id":"pledge","label":"Pledge","move":"prepare-group","cost":{"hope":1}}]}}}]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        return AutomationHost.compile("converted", Map.of("items", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
    }

    private static AutomationHost.Pack customPack() throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                {"id":"drain","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"drain","kind":"any"}}],
                 "do":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Drain"}}
                """));
        ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                {"id":"deferred","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"option"}],
                 "when":{"eq":[{"read":"event.move.id"},"drain"]},
                 "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                   "do":[{"op":"track.clear","track":"actor.stress","amount":1}]},
                 "ui":{"label":"Deferred"}}
                """));
        return AutomationHost.compile("custom", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"hope":{"slots":6,"on":5},"hp":{"slots":6,"on":3},
                 "stress":{"slots":6,"on":6},"armorTrack":{"slots":3,"on":0},
                 "inventory":[{"name":"Camp","qty":1,"instanceId":"camp","automationSource":"items:camp"}]}
                """);
    }

    private static ObjectNode select(String source, String rule, String binding) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", source).put("ruleId", rule).put("bindingId", binding);
    }

    private static ObjectNode prepare(AutomationHost.Pack pack, ObjectNode sheet) {
        return HOST.prepareRest(pack, sheet, AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                "short", 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
    }

    private static AutomationHost.RestMoveResult move(AutomationHost.Pack pack, ObjectNode sheet, ObjectNode prepared,
                                                      String rule, ArrayNode choices, int... faces) {
        return HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", rule, "move"), choices,
                faces.length == 0 ? StructuredRollTest.noRandom() : StructuredRollTest.faces(faces));
    }

    @Test
    void convertedBonusesPrecedeRecoveryButModifierAndPassiveStepsFollowIt() throws Exception {
        var pack = convertedPack();
        ObjectNode sheet = sheet();
        ObjectNode prepared = prepare(pack, sheet);
        ArrayNode options = RuleJson.JSON.createArrayNode().add(select("camp", "tea", "downtime"));
        var result = move(pack, sheet, prepared, "rest-stress", options, 1);
        assertEquals(4, sheet.at("/stress/on").asInt(), "Clear 3 first, then mark 1.");
        assertEquals(2, sheet.at("/hp/on").asInt());
        assertEquals(4, sheet.at("/hope/on").asInt());
        assertEquals(List.of("move-stress", "tea", "rest-stress", "move-stress", "tea"),
                result.response().path("actions").findValuesAsText("ruleId"));
        assertEquals("Recover then mark.", result.response().at("/actions/3/report").asText());
        assertEquals(1, result.response().at("/event/move/bonus").asInt());
        assertTrue(result.response().at("/actions/2/report").asText().contains("cleared 3 Stress"));
    }

    @Test
    void selectedMoveCostsArePaidAfterCappedPreparationAndCheckedBeforeIt() throws Exception {
        var pack = convertedPack();
        ObjectNode sheet = sheet();
        ObjectNode prepared = prepare(pack, sheet);
        ArrayNode pledge = RuleJson.JSON.createArrayNode().add(select("camp", "pledge", "downtime"));
        var result = move(pack, sheet, prepared, "rest-prepare-group", pledge);
        assertEquals(5, sheet.at("/hope/on").asInt(), "Gain the one available Hope, then pay the selected cost.");
        assertTrue(result.response().at("/actions/1/report").asText().contains("gained 1 Hope"));

        ObjectNode unaffordable = sheet();
        unaffordable.withObject("/hope").put("on", 0);
        ObjectNode rest = prepare(pack, unaffordable);
        ObjectNode before = unaffordable.deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(pack, unaffordable, rest, "rest-prepare-group", pledge)).code());
        assertEquals(before, unaffordable);
    }

    @Test
    void aDeferredCostThatBecomesUnaffordableRollsBackTheEntireMove() throws Exception {
        var pack = customPack();
        ObjectNode sheet = sheet();
        sheet.withObject("/hope").put("on", 1);
        ObjectNode prepared = prepare(pack, sheet);
        ObjectNode before = sheet.deepCopy();
        ArrayNode choices = RuleJson.JSON.createArrayNode().add(select("ruleset:base", "deferred", "move"));
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(pack, sheet, prepared, "drain", choices)).code());
        assertEquals(before, sheet, "A failed post-move cost must not retain the move or earlier changes.");
    }

    @Test
    void ordinaryDispatchCannotSilentlySkipDeferredWork() throws Exception {
        var pack = customPack();
        var program = pack.programs().get("ruleset:base");
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"ruleset:base","instanceId":"ruleset:base","name":"Rules"},
                 "actor":{"id":"ada","name":"Ada","hope":{"value":2,"max":6},"stress":{"marked":2,"capacity":6}},
                 "event":{"type":"downtime.move","move":{"id":"drain"}},"revision":0}
                """);
        var intent = new RuleRuntime.Intent("deferred", "move", "ruleset:base", 0, RuleJson.JSON.createObjectNode());
        assertTrue(RuleRuntime.describe(program, "deferred", "move", context).path("available").asBoolean());
        assertEquals("restTransaction", assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.execute(program, intent, context, StructuredRollTest.noRandom())).code());
        assertEquals("restTransaction", assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                        List.of(intent), StructuredRollTest.noRandom())).code());
        context.withObject("/actor/hope").put("value", 0);
        JsonNode unavailable = RuleRuntime.describe(program, "deferred", "move", context);
        assertFalse(unavailable.path("available").asBoolean());
        assertEquals("cost", unavailable.path("reasonCode").asText());
    }

    @Test
    void preMoveEligibilityIsCapturedBeforeRecoveryChangesTheCondition() throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                {"id":"wounded","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "when":{"and":[{"eq":[{"read":"event.move.id"},"stress-all"]},
                                  {"gt":[{"read":"actor.stress.marked"},0]}]},
                 "after":{"do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"report":"Recovered."},
                 "ui":{"label":"Wounded"}}
                """));
        var pack = AutomationHost.compile("conditions", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        ObjectNode prepared = HOST.prepareRest(pack, sheet,
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                "long", 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        var result = move(pack, sheet, prepared, "rest-stress-all", RuleJson.JSON.createArrayNode());
        assertEquals(0, sheet.at("/stress/on").asInt());
        assertEquals(6, sheet.at("/hope/on").asInt());
        assertEquals("Recovered.", result.response().at("/actions/2/report").asText());
    }

    @Test
    void disappearingDeferredSourceRejectsInsteadOfLosingItsBenefit() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"camp","name":"Camp","automation":{"rules":[
                   {"id":"benefit","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                    "after":{"do":[{"op":"resource.gain","pool":"actor.hope","amount":1}]},"ui":{"label":"Benefit"}},
                   {"id":"consume","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"consume","kind":"any"}}],
                    "do":[{"op":"inventory.consume","amount":1}],"ui":{"label":"Consume"}}
                 ]}}]}
                """;
        var pack = AutomationHost.compile("source", Map.of("items", catalog),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        ObjectNode sheet = sheet();
        ObjectNode prepared = prepare(pack, sheet);
        ObjectNode before = sheet.deepCopy();
        var failure = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("camp", "consume", "move"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom()));
        assertEquals("reactionSource", failure.code());
        assertEquals(before, sheet);
    }

    @Test
    void deferredDiceAndBlocksAreOrderedAndAtomic() throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ArrayNode rules = ruleset.withObject("/automation").withArray("rules");
        rules.add(RuleJson.parse("""
                {"id":"follow","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "after":{"do":[{"op":"dice.roll","count":1,"sides":6,"as":"die"}],"report":"After: {result.die}."},
                 "ui":{"label":"Follow"}}
                """));
        var pack = AutomationHost.compile("dice", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        ObjectNode prepared = prepare(pack, sheet);
        var result = move(pack, sheet, prepared, "rest-stress", RuleJson.JSON.createArrayNode(), 2, 5);
        assertEquals(2, result.response().at("/actions/1/diceRolls/0/faces/0").asInt());
        assertEquals(5, result.response().at("/actions/2/diceRolls/0/faces/0").asInt());
        assertEquals("After: 5.", result.response().at("/actions/2/report").asText());

        ((ObjectNode) rules.get(rules.size() - 1)).withObject("/after").putArray("do")
                .addObject().put("op", "event.block").put("reason", "No rest.");
        ((ObjectNode) rules.get(rules.size() - 1)).withObject("/after").remove("report");
        var blockedPack = AutomationHost.compile("blocked", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode blockedSheet = sheet();
        ObjectNode blockedRest = prepare(blockedPack, blockedSheet);
        ObjectNode before = blockedSheet.deepCopy();
        var blocked = assertThrows(RuleRuntime.Unavailable.class, () -> move(blockedPack, blockedSheet, blockedRest,
                "rest-stress", RuleJson.JSON.createArrayNode(), 2));
        assertEquals("blocked", blocked.code());
        assertEquals("No rest.", blocked.getMessage());
        assertEquals(before, blockedSheet);
    }

    @Test
    void compilerValidatesDeferredBodiesAndUnusedRecipes() throws Exception {
        ObjectNode rule = (ObjectNode) RuleJson.parse("""
                {"id":"benefit","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"option"}],
                 "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                   "do":[{"op":"track.clear","track":"actor.stress","amount":1,"as":"clear"}],
                   "report":"Cleared {result.clear}."},"ui":{"label":"Benefit"}}
                """);
        ObjectNode automation = RuleJson.JSON.createObjectNode();
        automation.putArray("rules").add(rule);
        JsonNode recipes = RuleJson.parse(LegacyCatalogConverter.coreRecipes());
        assertDoesNotThrow(() -> RuleCompiler.compile("items:test", automation, recipes));

        for (String invalid : List.of(
                "{\"costs\":[{\"op\":\"track.clear\",\"track\":\"actor.stress\",\"amount\":1}]}",
                "{\"do\":[{\"op\":\"not.an.operation\"}]}",
                "{\"do\":[{\"op\":\"plan.adjust\",\"plan\":\"event.move\",\"field\":\"bonus\",\"by\":1,\"label\":\"Late\"}]}",
                "{\"do\":[{\"op\":\"track.clear\",\"track\":\"actor.hp\",\"amount\":{\"read\":\"input.missing\"}}]}",
                "{\"do\":[{\"op\":\"claim.offer\",\"group\":\"missing\",\"label\":\"Missing\",\"limit\":1}]}",
                "{\"report\":\"{result.missing}\"}",
                "{\"unknown\":true}")) {
            ObjectNode bad = automation.deepCopy();
            ((ObjectNode) bad.at("/rules/0")).set("after", RuleJson.parse(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("items:test", bad, recipes), invalid);
        }
        ObjectNode wrongEvent = automation.deepCopy();
        ((ObjectNode) wrongEvent.at("/rules/0/bindings/0")).put("event", "action.request").put("surface", "sheet");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("items:test", wrongEvent, recipes));
        ObjectNode automaticCost = automation.deepCopy();
        ((ObjectNode) automaticCost.at("/rules/0/bindings/0")).put("mode", "automatic");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("items:test", automaticCost, recipes));

        ObjectNode recipeDocument = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test:deferred","args":{},"ui":{},"rules":[]}]}
                """);
        ((ObjectNode) recipeDocument.withArray("recipes").get(0)).withArray("rules").add(rule.deepCopy());
        String emptyCatalog = "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"items\",\"entries\":[]}";
        assertDoesNotThrow(() -> RuleCatalog.compile(emptyCatalog, recipeDocument.toString()));
        ((ObjectNode) recipeDocument.at("/recipes/0/rules/0/after/do/0")).put("op", "not.an.operation");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(emptyCatalog, recipeDocument.toString()));
    }
}
