package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.StructuredRollTest.faces;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy dicePool / rollDicePool (engine.ts poolDice, rollDicePool; seraph.test.ts) as pool rules with a replacing roll. */
class DicePoolTest {
    private static final String LEGACY = """
            {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[{"id":"seraph","name":"Seraph","features":[
             {"name":"Prayer Dice","automation":{"dicePool":{"id":"prayer","label":"Prayer Dice","count":"@spellcast","sides":4,
               "extraByFeature":{"Devout":1}},"uses":{"max":1,"per":"session"},
              "actions":[{"id":"roll","label":"Roll Prayer Dice","rollDicePool":true}]}}]}]}""";

    private static RuleCompiler.Program program(String legacy) throws Exception {
        var converted = LegacyCatalogConverter.convert(legacy);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        return RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
    }

    private static ObjectNode context(RuleCompiler.Program program, int spellcast, boolean devout, int hope) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"p","name":"Prayer Dice"},
                 "actor":{"name":"Seraph","spellcastTrait":{"value":%d},"features":[%s],"heritage":[],
                          "hope":{"value":%d,"max":6}},
                 "revision":0,"event":{"type":"action.request"}}""".formatted(program.sourceId(), spellcast,
                devout ? "{\"label\":\"Devout\"}" : "", hope));
    }

    private static RuleRuntime.Intent roll(ObjectNode context, String rule, String binding, String eachId) {
        return new RuleRuntime.Intent(rule, binding, "p", context.path("revision").asLong(), RuleJson.JSON.createObjectNode(), eachId);
    }

    private static JsonNode dice(ObjectNode context) {
        return context.at("/sourcePools/prayer/dice");
    }

    @Test
    void converterOutputDeclaresADiceSessionPoolAndOneRulePerWayToSpendADie() throws Exception {
        JsonNode automation = LegacyCatalogConverter.convert(LEGACY).catalog().at("/entries/0/features/0/automation");
        assertEquals("dice", automation.at("/pools/prayer/kind").asText());
        assertEquals("session.start", automation.at("/pools/prayer/resetOn/0").asText());
        assertEquals("pool.roll", automation.at("/rules/4/do/0/op").asText(), automation.toString());
        assertTrue(automation.at("/rules/4/do/0/replace").asBoolean());
        assertEquals("any", automation.at("/rules/2/bindings/0/audience").asText());
        assertEquals("damage.prepare", automation.at("/rules/3/bindings/0/event").asText());
    }

    @Test
    void devoutAddsOneDieAndTheHighestSpellcastDiceAreKept() throws Exception {
        // Legacy seraph.test.ts: spellcast 3 with Devout rolls four d4 (2,4,1,2) and keeps 4,2,2.
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 3, true, 0);
        var result = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(2, 4, 1, 2));
        assertEquals("[2,4,1,2]", result.diceRolls().getFirst().path("faces").toString());
        assertEquals(3, dice(result.context()).size());
        assertEquals("[4,2,2]", kept(dice(result.context())));
        assertTrue(result.report().contains("2, 4, 1, 2"), result.report());
    }

    @Test
    void withoutDevoutOnlySpellcastDiceAreRolledAndANonPositiveSpellcastRollsNothing() throws Exception {
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 0);
        var result = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(3, 1));
        assertEquals("[3,1]", kept(dice(result.context())));
        ObjectNode none = context(program, 0, true, 0);
        var empty = RuleRuntime.execute(program, roll(none, "roll", "sheet", null), none, noRandom());
        assertEquals(0, dice(empty.context()).size());
        assertEquals(0, empty.diceRolls().getFirst().path("faces").size());
    }

    @Test
    void rollingReplacesEveryStoredDieAndRepeatedFacesStayDistinct() throws Exception {
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 0);
        ObjectNode first = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(2, 2)).context();
        String firstIds = dice(first).get(0).path("id").asText() + dice(first).get(1).path("id").asText();
        assertNotEquals(dice(first).get(0).path("id").asText(), dice(first).get(1).path("id").asText());
        first = RuleRuntime.resetLimits(program, first, "session.start");
        assertEquals(0, dice(first).size());
        ObjectNode second = RuleRuntime.execute(program, roll(first, "roll", "sheet", null), first, faces(4, 1)).context();
        assertEquals("[4,1]", kept(dice(second)));
        assertEquals(2, dice(second).size());
        assertNotEquals(firstIds, dice(second).get(0).path("id").asText() + dice(second).get(1).path("id").asText());
    }
    @Test
    void hopeSpendAddsTheFaceUpToTheOpenSlotsAndIsOfferedOnlyWhileHopeIsNotFull() throws Exception {
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 4);
        ObjectNode drawn = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(4, 3)).context();
        var buttons = RuleRuntime.describeEach(program, "spend-for-hope", "per-die", drawn);
        assertEquals(2, buttons.size());
        var spent = RuleRuntime.execute(program, roll(drawn, "spend-for-hope", "per-die", buttons.get(0).path("eachId").asText()), drawn, noRandom());
        assertEquals(6, spent.context().at("/actor/hope/value").asInt());
        assertEquals(1, dice(spent.context()).size());
        ObjectNode full = spent.context().deepCopy();
        assertFalse(RuleRuntime.describeEach(program, "spend-for-hope", "per-die", full).get(0).path("available").asBoolean());
    }

    @Test
    void manualRemovalDropsOnlyTheChosenDie() throws Exception {
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 0);
        ObjectNode drawn = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(4, 3)).context();
        var buttons = RuleRuntime.describeEach(program, "remove-die", "per-die", drawn);
        var removed = RuleRuntime.execute(program, roll(drawn, "remove-die", "per-die", buttons.get(1).path("eachId").asText()), drawn, noRandom());
        assertEquals("[4]", kept(dice(removed.context())));
    }

    @Test
    void aStoredDieAddsItsFaceToAnyRollAndIsConsumed() throws Exception {
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 0);
        ObjectNode drawn = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(4, 3)).context();
        ObjectNode rollContext = drawn.deepCopy();
        ObjectNode weapon = (ObjectNode) RuleJson.parse("""
                {"id":"result","contributions":[{"id":"weapon-dice","kind":"dice","role":"weaponBase","sourceId":"weapon:sword",
                 "label":"Sword","dice":[{"id":"w1","sides":8,"face":5,"kept":true,"origin":"rolled"}]}]}""");
        rollContext.putObject("event").put("type", "roll.finished").set("roll", StructuredRoll.validate(weapon));
        var buttons = RuleRuntime.describeEach(program, "aid-roll", "roll-result", rollContext);
        assertEquals(2, buttons.size());
        var result = RuleRuntime.execute(program, roll(rollContext, "aid-roll", "roll-result", buttons.get(0).path("eachId").asText()), rollContext, noRandom());
        assertEquals(9, result.publishedRolls().getFirst().path("total").asInt());
        assertEquals(1, dice(result.context()).size());
    }

    @Test
    void aStoredDieReducesIncomingDamageByItsFaceAndIsConsumed() throws Exception {
        // Legacy seraph.test.ts: 10 damage, dice 2 and 2 -> 6.
        RuleCompiler.Program program = program(LEGACY);
        ObjectNode before = context(program, 2, false, 0);
        ObjectNode drawn = RuleRuntime.execute(program, roll(before, "roll", "sheet", null), before, faces(2, 2)).context();
        ObjectNode damage = drawn.deepCopy();
        damage.set("event", RuleJson.parse("{\"type\":\"damage.prepare\",\"damageType\":\"physical\",\"damage\":{\"id\":\"hit\",\"stage\":\"preSeverity\",\"baseAmount\":10,\"amount\":10,\"adjustments\":[]}}"));
        var buttons = RuleRuntime.describeEach(program, "reduce-damage", "incoming", damage);
        assertEquals(2, buttons.size());
        var result = RuleRuntime.execute(program, roll(damage, "reduce-damage", "incoming", buttons.get(0).path("eachId").asText()), damage, noRandom());
        assertEquals(1, dice(result.context()).size());
        assertEquals(8, result.context().at("/event/damage/amount").asInt());
        ObjectNode second = result.context().deepCopy();
        var next = RuleRuntime.describeEach(program, "reduce-damage", "incoming", second);
        var both = RuleRuntime.execute(program, roll(second, "reduce-damage", "incoming", next.get(0).path("eachId").asText()), second, noRandom());
        assertEquals(6, both.context().at("/event/damage/amount").asInt());
        assertEquals(0, dice(both.context()).size());
    }

    @Test
    void rollOptionallyReplacesAnotherPoolWithAnEmptyOrSmallerDraw() throws Exception {
        // A second mechanic: an omen pool that is redrawn each dawn; a count of zero clears it.
        RuleCompiler.Program program = RuleCompiler.compile("features:omens", RuleJson.parse("""
                {"pools":{"omens":{"kind":"dice","scope":"source","max":4}},
                 "rules":[{"id":"dawn","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"pool.roll","pool":"omens","count":{"read":"actor.omens"},"sides":6,"keepHighest":{"read":"actor.keep"},"replace":true,"as":"draw"}],
                  "ui":{"label":"Dawn"}}]}"""), RuleConformanceTest.fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:omens","instanceId":"p","name":"Omens"},"actor":{"omens":4,"keep":2},"revision":0,"event":{"type":"action.request"}}""");
        var firstIntent = new RuleRuntime.Intent("dawn", "use", "p", 0, RuleJson.JSON.createObjectNode());
        ObjectNode full = RuleRuntime.execute(program, firstIntent, context, faces(1, 6, 3, 5)).context();
        var intent = new RuleRuntime.Intent("dawn", "use", "p", full.path("revision").asLong(), RuleJson.JSON.createObjectNode());
        assertEquals("[6,5]", kept(full.at("/sourcePools/omens/dice")));
        full.withObject("/actor").put("omens", 0).put("keep", 0);
        assertEquals(0, RuleRuntime.execute(program, intent, full, noRandom()).context().at("/sourcePools/omens/dice").size());
    }

    private static String kept(JsonNode dice) {
        var out = RuleJson.JSON.createArrayNode();
        dice.forEach(die -> out.add(die.path("face").asInt()));
        var sorted = new java.util.ArrayList<Integer>();
        out.forEach(n -> sorted.add(n.asInt()));
        sorted.sort(java.util.Comparator.reverseOrder());
        out.removeAll();
        sorted.forEach(out::add);
        return out.toString();
    }
}
