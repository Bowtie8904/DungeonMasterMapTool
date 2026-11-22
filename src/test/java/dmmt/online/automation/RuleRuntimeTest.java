package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.faces;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class RuleRuntimeTest {
    private static RuleCompiler.Program program(JsonNode entry) throws Exception {
        return RuleCompiler.compile("domain-cards:" + entry.path("id").asText(), entry.path("automation"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String id, String name) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"domain-cards:%s","instanceId":"owned-%s","name":"%s"},
                 "actor":{"id":"actor","name":"Player","proficiency":1,
                          "hope":{"value":6,"max":6},"stress":{"marked":0,"capacity":6},"hp":{"marked":4,"capacity":6}},
                 "revision":0,"event":{"type":"action.request"}}
                """.formatted(id, id, name));
    }

    private static RuleRuntime.Intent intent(String rule, String binding, ObjectNode context, String input) throws Exception {
        return new RuleRuntime.Intent(rule, binding, context.path("source").path("instanceId").asText(),
                context.path("revision").asLong(), (ObjectNode) RuleJson.parse(input));
    }

    private static void firstDamage(ObjectNode context) throws Exception {
        context.set("event", RuleJson.parse("""
                {"type":"roll.prepare","role":"weaponDamage","weaponId":"primary",
                 "damagePlan":{"id":"damage","contributions":[
                  {"id":"a","kind":"dice","role":"weaponBase","sourceId":"primary","label":"Mixed eight",
                   "count":{"read":"result.effectiveProficiency"},"sides":8},
                  {"id":"b","kind":"dice","role":"weaponBase","sourceId":"primary","label":"Mixed six",
                   "count":{"read":"result.effectiveProficiency"},"sides":6},
                  {"id":"flat","kind":"flat","role":"bonus","sourceId":"primary","label":"Flat","value":4},
                  {"id":"double","kind":"scale","role":"multiplier","sourceId":"custom",
                   "label":"Freely renamed multiplier","of":["a","b","flat"],"factor":2}
                 ]}}
                """));
    }

    private static void resultEvent(ObjectNode context) {
        context.putObject("event").put("type", "roll.finished").put("sourceInstanceId", context.path("source").path("instanceId").asText())
                .put("activation", context.path("state").path("activation").asInt())
                .put("sequenceStep", context.path("state").path("uses").asInt());
    }

    @Test
    void savedRollSequenceExecutesAndPreviewsWithoutNameDispatchOrNewDice() throws Exception {
        JsonNode entry = fixture("rewrite/domain-cards").path("entries").get(0);
        RuleCompiler.Program program = program(entry);
        ObjectNode before = context("deathrun", "Anything the DM calls it");
        ObjectNode started = RuleRuntime.execute(program, intent("run.start", "sheet", before, "{}"), before, noRandom()).context();
        assertEquals(6, before.path("actor").path("hope").path("value").asInt());
        assertEquals(3, started.path("actor").path("hope").path("value").asInt());
        firstDamage(started);
        RuleRuntime.Resolution first = RuleRuntime.execute(program, intent("run.first", "damage", started, "{}"), started, faces(8, 3, 6, 2));
        assertEquals(46, first.publishedRolls().getFirst().path("total").asInt());
        ObjectNode current = first.context();
        resultEvent(current);
        ObjectNode descriptor = RuleRuntime.describe(program, "run.continue", "result", current);
        assertTrue(descriptor.path("available").asBoolean());
        assertEquals("Continue Anything the DM calls it", descriptor.path("label").asText());
        assertEquals("b.d2", descriptor.path("inputs").get(0).path("default").get(0).asText());
        assertEquals("d8: 8", descriptor.path("inputs").get(0).path("choices").get(0).path("label").asText());
        ObjectNode unchanged = current.deepCopy();
        RuleRuntime.Intent request = intent("run.continue", "result", current, "{\"remove\":[\"a.d2\"]}");
        RuleRuntime.Resolution preview = RuleRuntime.preview(program, request, current);
        assertEquals(40, preview.publishedRolls().getFirst().path("total").asInt());
        assertEquals("Remaining damage: 40. No new dice are rolled.", preview.report());
        assertEquals(unchanged, current);
        RuleRuntime.Resolution committed = RuleRuntime.execute(program, request, current, noRandom());
        assertEquals(preview.context(), committed.context());
        assertEquals(46, committed.context().path("state").path("firstDamage").path("total").asInt());
        assertEquals(3, committed.context().path("state").path("remainingDice").size());
        assertTrue(committed.diceRolls().isEmpty());
        ObjectNode next = committed.context();
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, request, next, noRandom()));
        assertFalse(RuleRuntime.describe(program, "run.continue", "result", next).path("available").asBoolean());
        resultEvent(next);
        RuleRuntime.Resolution again = RuleRuntime.execute(program, intent("run.continue", "result", next, "{\"remove\":[\"b.d2\"]}"), next, noRandom());
        assertEquals(36, again.publishedRolls().getFirst().path("total").asInt());
        assertEquals(3, again.context().path("actor").path("hope").path("value").asInt());
    }

    @Test
    void homebrewChangesCostsBonusSelectionCountAndTextOnlyInJson() throws Exception {
        JsonNode entry = fixture("rewrite/domain-cards").path("entries").get(1);
        RuleCompiler.Program program = program(entry);
        ObjectNode before = context("splinter-trail", "Splinter Trail");
        ObjectNode started = RuleRuntime.execute(program, intent("trail.start", "sheet", before, "{}"), before, noRandom()).context();
        assertEquals(6, started.path("actor").path("hope").path("value").asInt());
        assertEquals(1, started.path("actor").path("stress").path("marked").asInt());
        firstDamage(started);
        ObjectNode current = RuleRuntime.execute(program, intent("trail.first", "damage", started, "{}"), started, faces(8, 3, 1, 6, 2, 1)).context();
        assertEquals(6, current.path("state").path("remainingDice").size());
        assertEquals(50, current.path("state").path("firstDamage").path("total").asInt());
        resultEvent(current);
        ObjectNode descriptor = RuleRuntime.describe(program, "trail.continue", "result", current);
        assertEquals("Strike again (1 hits so far)", descriptor.path("label").asText());
        assertEquals(2, descriptor.path("inputs").get(0).path("count").asInt());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent("trail.continue", "result", current, "{\"remove\":[\"a.d2\"]}"), current, noRandom()));
        RuleRuntime.Resolution next = RuleRuntime.execute(program, intent("trail.continue", "result", current, "{\"remove\":[\"a.d3\",\"b.d3\"]}"), current, noRandom());
        assertEquals(46, next.publishedRolls().getFirst().path("total").asInt());
        assertEquals("ready", started.path("state").path("phase").asText());
        assertFalse(next.context().toString().contains("Deathrun"));
    }

    @Test
    void cancellationFailuresExhaustionAndDifferentInstancesNeverSpendOrAdvance() throws Exception {
        RuleCompiler.Program program = program(fixture("rewrite/domain-cards").path("entries").get(0));
        ObjectNode before = context("deathrun", "Deathrun");
        ((ObjectNode) before.path("actor").path("hope")).put("value", 2);
        ObjectNode copy = before.deepCopy();
        assertFalse(RuleRuntime.describe(program, "run.start", "sheet", before).path("available").asBoolean());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("run.start", "sheet", before, "{}"), before, noRandom()));
        assertEquals(copy, before);
        ((ObjectNode) before.path("actor").path("hope")).put("value", 6);
        ObjectNode started = RuleRuntime.execute(program, intent("run.start", "sheet", before, "{}"), before, noRandom()).context();
        firstDamage(started);
        ObjectNode current = RuleRuntime.execute(program, intent("run.first", "damage", started, "{}"), started, faces(8, 3, 6, 2)).context();
        resultEvent(current);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program,
                new RuleRuntime.Intent("run.continue", "result", "other-instance", current.path("revision").asLong(), RuleJson.JSON.createObjectNode()), current, noRandom()));
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent("run.continue", "result", current, "{\"remove\":[\"a.d1\",\"a.d1\"]}"), current, noRandom()));
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent("run.continue", "result", current, "{\"remove\":[\"unknown\"]}"), current, noRandom()));
        ObjectNode next = current;
        for (String id : new String[]{"a.d1", "a.d2", "b.d1"}) {
            resultEvent(next);
            next = RuleRuntime.execute(program, intent("run.continue", "result", next, "{\"remove\":[\"" + id + "\"]}"), next, noRandom()).context();
        }
        resultEvent(next);
        assertFalse(RuleRuntime.describe(program, "run.continue", "result", next).path("available").asBoolean());
        ObjectNode exhausted = next;
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("run.continue", "result", exhausted, "{\"remove\":[\"b.d2\"]}"), exhausted, noRandom()));
        assertTrue(RuleRuntime.describe(program, "run.end", "result", next).path("available").asBoolean());
        next.putObject("event").put("type", "roll.prepare").put("role", "weaponDamage");
        ObjectNode cleared = RuleRuntime.execute(program, intent("run.exhaust", "damage", next, "{}"), next, noRandom()).context();
        assertEquals("inactive", cleared.path("state").path("phase").asText());
        assertTrue(cleared.path("state").path("firstDamage").isNull());
        assertTrue(cleared.path("state").path("remainingDice").isEmpty());
    }

    @Test
    void olderEndActionCannotEndAnotherActivationAndWrongWeaponCannotContinue() throws Exception {
        RuleCompiler.Program program = program(fixture("rewrite/domain-cards").path("entries").get(0));
        ObjectNode before = context("deathrun", "Deathrun");
        ObjectNode started = RuleRuntime.execute(program, intent("run.start", "sheet", before, "{}"), before, noRandom()).context();
        firstDamage(started);
        ObjectNode current = RuleRuntime.execute(program, intent("run.first", "damage", started, "{}"), started, faces(8, 3, 6, 2)).context();
        current.putObject("event").put("type", "roll.prepare").put("role", "weaponDamage").put("weaponId", "other-weapon");
        assertFalse(RuleRuntime.describe(program, "run.continue", "damage", current).path("available").asBoolean());
        resultEvent(current);
        ObjectNode oldEvent = current.withObject("/event").deepCopy();
        ObjectNode ended = RuleRuntime.execute(program, intent("run.end", "result", current, "{}"), current, noRandom()).context();
        ended.putObject("event").put("type", "action.request");
        ObjectNode restarted = RuleRuntime.execute(program, intent("run.start", "sheet", ended, "{}"), ended, noRandom()).context();
        restarted.set("event", oldEvent);
        assertFalse(RuleRuntime.describe(program, "run.end", "result", restarted).path("available").asBoolean());
    }

    @Test
    void convertedPotionIsAtomicAndReportsActualCappedHealing() throws Exception {
        JsonNode entry = fixture("rewrite/consumables").path("entries").get(0);
        RuleCompiler.Program program = RuleCompiler.compile("consumables:minor-health-potion", entry.path("automation"), fixture("rewrite/recipes"));
        ObjectNode before = context("unused", "Potion");
        before.putObject("source").put("id", program.sourceId()).put("instanceId", "potion-one").put("name", "Minor Health Potion").put("quantity", 2);
        ((ObjectNode) before.path("actor").path("hp")).put("marked", 1);
        ObjectNode unchanged = before.deepCopy();
        RuleRuntime.Resolution result = RuleRuntime.execute(program, intent("drink", "sheet", before, "{}"), before, faces(4));
        assertEquals(unchanged, before);
        assertEquals(1, result.context().path("source").path("quantity").asInt());
        assertEquals(0, result.context().path("actor").path("hp").path("marked").asInt());
        assertEquals("Player drank Minor Health Potion and cleared 1 HP (rolled 4).", result.report());
        assertEquals("[4]", result.diceRolls().getFirst().path("faces").toString());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("drink", "sheet", result.context(), "{}"), result.context(), noRandom()));
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.preview(program, intent("drink", "sheet", before, "{}"), before));
        assertEquals(unchanged, before);
    }

    @Test
    void limitOverridesRaiseTheUseBudgetOfTheNamedFeatureUsingTheHighestOverride() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"limits":{"uses":{"max":1,"resetOn":["rest.long"]}},"rules":[
                  {"id":"touch","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "limitRef":"uses","do":[{"op":"track.clear","track":"actor.stress","amount":1}],
                   "ui":{"label":"Touch","report":"Touched."}}
                ]}
                """);
        RuleCompiler.Program program = RuleCompiler.compile("domain-cards:example", automation, fixture("rewrite/recipes"));
        ObjectNode context = context("example", "Example");
        ((ObjectNode) context.path("actor").path("stress")).put("marked", 3);
        for (int use = 0; use < 2; use++) {
            if (use == 1) context.withObject("/actor").putObject("limitOverrides").put("Example:uses", 2).put("Other:uses", 9);
            context = RuleRuntime.execute(program, intent("touch", "sheet", context, "{}"), context, noRandom()).context();
        }
        assertEquals(2, context.at("/usage/uses").asInt());
        ObjectNode spent = context;
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("touch", "sheet", spent, "{}"), spent, noRandom()));
    }

    @Test
    void independentUseBudgetsResetOnlyOnDeclaredEventsAndAggregateCostsRollback() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"limits":{"recover":{"max":1,"resetOn":["rest.short","rest.long"]}},"rules":[
                  {"id":"steady","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                   "limitRef":"recover","costs":[{"op":"resource.spend","pool":"actor.hope","amount":2},{"op":"resource.spend","pool":"actor.hope","amount":2}],
                   "do":[{"op":"track.clear","track":"actor.stress","amount":2,"as":"cleared"}],
                   "ui":{"label":"Steady","report":"Cleared {result.cleared} Stress."}}
                ]}
                """);
        RuleCompiler.Program program = RuleCompiler.compile("domain-cards:example", automation, fixture("rewrite/recipes"));
        ObjectNode before = context("example", "Example");
        assertEquals(0, RuleRuntime.resetLimits(program, before, "rest.short").path("usage").path("recover").asInt());
        assertFalse(before.has("usage"));
        ((ObjectNode) before.path("actor").path("stress")).put("marked", 3);
        ((ObjectNode) before.path("actor").path("hope")).put("value", 3);
        ObjectNode copy = before.deepCopy();
        assertFalse(RuleRuntime.describe(program, "steady", "sheet", before).path("available").asBoolean());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("steady", "sheet", before, "{}"), before, noRandom()));
        assertEquals(copy, before);
        ((ObjectNode) before.path("actor").path("hope")).put("value", 6);
        ObjectNode next = RuleRuntime.execute(program, intent("steady", "sheet", before, "{}"), before, noRandom()).context();
        assertEquals(1, next.path("usage").path("recover").asInt());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.resetLimits(program, next, "roll.finished"));
        ObjectNode corrupt = next.deepCopy();
        corrupt.withObject("/usage").put("recover", -1);
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.resetLimits(program, corrupt, "rest.short"));
        corrupt.withObject("/usage").put("recover", 1).put("unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.resetLimits(program, corrupt, "rest.short"));
        corrupt.withObject("/usage").remove("unknown");
        corrupt.withObject("/source").put("id", "another:source");
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.resetLimits(program, corrupt, "rest.short"));
        assertFalse(RuleRuntime.describe(program, "steady", "sheet", next).path("available").asBoolean());
        assertEquals(1, RuleRuntime.resetLimits(program, next, "scene.end").path("usage").path("recover").asInt());
        assertEquals(0, RuleRuntime.resetLimits(program, next, "rest.short").path("usage").path("recover").asInt());
        assertEquals(1, next.path("usage").path("recover").asInt());
    }

    @Test
    void compilerRejectsOldFormatsUnknownFlagsArgumentsAndInvalidReferences() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.parseDocument("{\"format\":\"dmmap-daggerheart\",\"version\":1}", "dmmap-daggerheart"));
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.parseDocument("{\"format\":\"dmmap-daggerheart\",\"version\":4294967298}", "dmmap-daggerheart"));
        ObjectNode entry = (ObjectNode) fixture("rewrite/domain-cards").path("entries").get(0);
        ObjectNode automation = (ObjectNode) entry.get("automation");
        automation.put("deathrun", true);
        assertThrows(IllegalArgumentException.class, () -> program(entry));
        automation.remove("deathrun");
        ObjectNode use = (ObjectNode) automation.path("rules").get(0);
        use.withObject("/args").put("typo", 1);
        assertThrows(IllegalArgumentException.class, () -> program(entry));
        use.withObject("/args").remove("typo");
        use.withObject("/ui").put("continue", "Continue {state.typo}");
        assertThrows(IllegalArgumentException.class, () -> program(entry));
        use.withObject("/ui").put("continue", "Continue {result.notYetComputed}");
        assertThrows(IllegalArgumentException.class, () -> program(entry));
        use.withObject("/ui").put("continue", "Continue {source.name}");
        RuleCompiler.Program compiled = program(entry);
        use.withObject("/args").put("removeEachTime", 99);
        assertEquals(1, compiled.rule("run.continue").path("args").path("removeEachTime").asInt());
    }

    @Test
    void inputDependentCostsAreDeferredThenCheckedWithoutChangingTheOriginalState() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"trade","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "inputs":[{"id":"amount","kind":"integer","min":1,"max":10,"label":"Amount"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":{"read":"input.amount"},"as":"spent"}],
                  "do":[{"op":"resource.gain","pool":"actor.other","amount":{"read":"result.spent"}}],
                  "ui":{"label":"Trade","report":"Spent {result.spent} Hope."}}]}
                """);
        RuleCompiler.Program program = RuleCompiler.compile("domain-cards:example", automation, fixture("rewrite/recipes"));
        ObjectNode before = context("example", "Example");
        before.withObject("/actor").putObject("other").put("value", 1_000_000);
        ObjectNode descriptor = RuleRuntime.describe(program, "trade", "sheet", before);
        assertTrue(descriptor.path("available").asBoolean());
        assertTrue(descriptor.path("costsRequireInput").asBoolean());
        ObjectNode copy = before.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, intent("trade", "sheet", before, "{\"amount\":7}"), before, noRandom()));
        assertEquals(copy, before);
        var result = RuleRuntime.execute(program, intent("trade", "sheet", before, "{\"amount\":6}"), before, noRandom());
        assertEquals(1_000_006, result.context().path("actor").path("other").path("value").asInt());
        assertEquals(0, result.context().path("actor").path("hope").path("value").asInt());
        before.withObject("/actor/other").put("value", 9_007_199_254_740_991L);
        copy = before.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent("trade", "sheet", before, "{\"amount\":1}"), before, noRandom()));
        assertEquals(copy, before);
    }

    @Test
    void simpleAndStructuredRollsShareOneActionDiceBudget() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"many","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                 "do":[{"op":"dice.roll","count":1,"sides":6},
                       {"op":"roll.resolve","plan":{"read":"event.damagePlan"}}],
                 "ui":{"label":"Roll"}}]}
                """);
        RuleCompiler.Program program = RuleCompiler.compile("domain-cards:example", automation, fixture("rewrite/recipes"));
        ObjectNode before = context("example", "Example");
        before.withObject("/event").set("damagePlan", RuleJson.parse("""
                {"id":"roll","contributions":[{"id":"pool","kind":"dice","role":"base","sourceId":"weapon",
                  "label":"Pool","count":1024,"sides":6}]}
                """));
        ObjectNode copy = before.deepCopy();
        var error = assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, intent("many", "sheet", before, "{}"), before, faces(1)));
        assertTrue(error.getMessage().contains("limit"));
        assertEquals(copy, before);
    }

    @Test
    void tableRollsPickOutcomesByFaceAndGrantsBecomeHostRequests() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"table","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                 "do":[{"op":"dice.table","count":2,"outcomes":["Calm","Storm","Fog"],"as":"weather"},
                       {"op":"inventory.grant","catalog":"consumables","label":"Gift","quantity":1,"max":5,"scope":"party","tag":"gift",
                        "entry":{"at":[{"literal":["a","b","c"]},{"subtract":[{"at":[{"read":"result.weather.faces"},1]},1]}]}}],
                 "ui":{"label":"Weather","report":"{result.weather.faces|list}: {result.weather.outcomes|list}"}}]}
                """);
        RuleCompiler.Program program = RuleCompiler.compile("domain-cards:example", automation, fixture("rewrite/recipes"));
        ObjectNode before = context("example", "Example");
        var result = RuleRuntime.execute(program, intent("table", "sheet", before, "{}"), before, faces(2, 3));
        assertEquals("2, 3: Storm, Fog", result.report());
        JsonNode grant = result.context().at("/outbox/0");
        assertEquals("inventory.grant", grant.path("kind").asText());
        assertEquals("c", grant.path("entry").asText());
        assertEquals("party", grant.path("scope").asText());
        assertEquals("gift", grant.path("tag").asText());

        for (String invalid : java.util.List.of(
                "{\"op\":\"dice.table\",\"count\":1,\"outcomes\":[\"Only\"]}",
                "{\"op\":\"inventory.grant\",\"catalog\":\"nowhere\",\"entry\":\"a\",\"quantity\":1,\"label\":\"X\"}",
                "{\"op\":\"inventory.grant\",\"catalog\":\"consumables\",\"entry\":\"a\",\"quantity\":1,\"label\":\"X\",\"scope\":\"party\"}",
                "{\"op\":\"inventory.grant\",\"catalog\":\"consumables\",\"entry\":\"a\",\"quantity\":1,\"label\":\"X\",\"to\":\"target\"}")) {
            ObjectNode broken = (ObjectNode) RuleJson.parse("""
                    {"rules":[{"id":"x","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                     "do":[%s],"ui":{"label":"X"}}]}""".formatted(invalid));
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("domain-cards:example", broken, fixture("rewrite/recipes")), invalid);
        }
        ObjectNode outOfRange = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"x","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                 "do":[{"op":"resource.gain","pool":"actor.hope","amount":{"at":[{"literal":[1]},3]}}],"ui":{"label":"X"}}]}""");
        RuleCompiler.Program bad = RuleCompiler.compile("domain-cards:example", outOfRange, fixture("rewrite/recipes"));
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(bad, intent("x", "sheet", before, "{}"), before, noRandom()));
    }
}
