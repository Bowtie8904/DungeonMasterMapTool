package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.faces;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class PoolRuntimeTest {
    private static RuleCompiler.Program program(String sourceId) throws Exception {
        return RuleCompiler.compile(sourceId, fixture("rewrite/pools"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String sourceId, String name) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"instance-one","name":"%s"},
                 "actor":{"id":"actor","name":"Player","extraDice":1,
                          "stress":{"marked":4,"capacity":6},"hope":{"value":2}},
                 "revision":0,"event":{"type":"action.request"}}
                """.formatted(sourceId, name));
    }

    private static RuleRuntime.Intent intent(String rule, ObjectNode context, String input, String eachId) throws Exception {
        return new RuleRuntime.Intent(rule, "sheet", context.path("source").path("instanceId").asText(),
                context.path("revision").asLong(), (ObjectNode) RuleJson.parse(input), eachId);
    }

    @Test
    void poolRollStoresDistinctKeptDiceAndCreatesOneTemplateDrivenButtonPerDie() throws Exception {
        RuleCompiler.Program program = program("classes:prayer-example");
        ObjectNode before = context(program.sourceId(), "Anything the author names it");
        assertTrue(RuleRuntime.describeEach(program, "spend-die", "per-die", before).isEmpty());
        ObjectNode changed = before.deepCopy();
        RuleRuntime.Resolution draw = RuleRuntime.execute(program, intent("gather", before, "{}", null), before, faces(2, 6, 6));
        assertEquals(changed, before);
        assertEquals("[2,6,6]", draw.diceRolls().getFirst().path("faces").toString());
        assertEquals(6, draw.context().path("sourcePools").path("prayer-dice").path("dice").get(0).path("face").asInt());
        assertEquals(6, draw.context().path("sourcePools").path("prayer-dice").path("dice").get(1).path("face").asInt());
        assertEquals("d1", draw.context().path("sourcePools").path("prayer-dice").path("dice").get(0).path("id").asText());
        assertEquals("d2", draw.context().path("sourcePools").path("prayer-dice").path("dice").get(1).path("id").asText());
        ObjectNode otherInstance = draw.context().deepCopy();
        otherInstance.withObject("/source").put("instanceId", "a-copy");
        otherInstance.remove("sourcePools");
        assertTrue(RuleRuntime.describeEach(program, "spend-die", "per-die", otherInstance).isEmpty());
        var descriptors = RuleRuntime.describeEach(program, "spend-die", "per-die", draw.context());
        assertEquals(2, descriptors.size());
        assertEquals("Spend d6 showing 6", descriptors.get(0).path("label").asText());
        assertEquals("d1", descriptors.get(0).path("eachId").asText());
        assertEquals("d2", descriptors.get(1).path("eachId").asText());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.describe(program, "spend-die", "per-die", draw.context()));
    }

    @Test
    void spendingOneDieUsesItsIdentityAndSessionResetDoesNotReuseIds() throws Exception {
        RuleCompiler.Program program = program("classes:prayer-example");
        ObjectNode before = context(program.sourceId(), "Prayer");
        ObjectNode drawn = RuleRuntime.execute(program, intent("gather", before, "{}", null), before, faces(2, 6, 3)).context();
        var firstButton = RuleRuntime.describeEach(program, "spend-die", "per-die", drawn).get(0);
        RuleRuntime.Intent spend = new RuleRuntime.Intent("spend-die", "per-die", drawn.path("source").path("instanceId").asText(),
                drawn.path("revision").asLong(), (ObjectNode) RuleJson.parse("{}"), firstButton.path("eachId").asText());
        var result = RuleRuntime.execute(program, spend, drawn, noRandom());
        assertEquals(0, result.context().path("actor").path("stress").path("marked").asInt());
        assertEquals("Spent d6 showing 6; cleared 4 Stress.", result.report());
        assertEquals(1, result.context().path("sourcePools").path("prayer-dice").path("dice").size());
        assertEquals("d2", result.context().path("sourcePools").path("prayer-dice").path("dice").get(0).path("id").asText());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program, spend, result.context(), noRandom()));

        ObjectNode rest = result.context().deepCopy();
        rest.putObject("event").put("type", "session.start");
        ObjectNode reset = RuleRuntime.resetLimits(program, rest, "session.start");
        assertTrue(reset.path("sourcePools").path("prayer-dice").path("dice").isEmpty());
        reset.putObject("event").put("type", "action.request");
        ObjectNode nextDraw = RuleRuntime.execute(program, intent("gather", reset, "{}", null), reset, faces(4, 5, 6)).context();
        assertEquals("d3", nextDraw.path("sourcePools").path("prayer-dice").path("dice").get(0).path("id").asText());
        assertEquals("d4", nextDraw.path("sourcePools").path("prayer-dice").path("dice").get(1).path("id").asText());
    }

    @Test
    void storedPoolDieCanAidADerivedRollAndIsConsumedAtomically() throws Exception {
        RuleCompiler.Program program = program("classes:prayer-example");
        ObjectNode before = context(program.sourceId(), "Aid");
        ObjectNode drawn = RuleRuntime.execute(program, intent("gather", before, "{}", null), before, faces(2, 6, 6)).context();
        ObjectNode roll = (ObjectNode) RuleJson.parse("""
                {"id":"weapon-result","contributions":[{
                  "id":"weapon-dice","kind":"dice","role":"weaponBase","sourceId":"weapon:shortsword",
                  "label":"Shortsword","dice":[
                    {"id":"weapon-d1","sides":8,"face":7,"kept":true,"origin":"rolled"}
                  ]
                }]}
                """);
        ObjectNode rollContext = (ObjectNode) drawn.deepCopy();
        rollContext.putObject("event").put("type", "roll.finished").set("roll", StructuredRoll.validate(roll));
        ObjectNode descriptor = (ObjectNode) RuleRuntime.describeEach(program, "aid-roll", "roll-result", rollContext).get(0);
        assertEquals("Aid with d6 showing 6", descriptor.path("label").asText());

        RuleRuntime.Intent aid = new RuleRuntime.Intent("aid-roll", "roll-result",
                rollContext.path("source").path("instanceId").asText(), rollContext.path("revision").asLong(),
                (ObjectNode) RuleJson.parse("{}"), descriptor.path("eachId").asText());
        RuleRuntime.Resolution result = RuleRuntime.execute(program, aid, rollContext, noRandom());
        ObjectNode added = result.publishedRolls().getFirst();
        assertEquals(13, added.path("total").asInt());
        assertEquals("Aid from Aid: d6 showing 6", added.path("contributions").get(1).path("label").asText());
        var addedDie = StructuredRoll.dice(added, "aid", true).get(0);
        assertEquals("prayer-dice", addedDie.path("poolId").asText());
        assertEquals("d1", addedDie.path("poolDieId").asText());
        assertEquals("stored", addedDie.path("origin").asText());
        assertEquals(7, rollContext.path("event").path("roll").path("total").asInt());
        assertEquals(1, result.context().path("sourcePools").path("prayer-dice").path("dice").size());
        assertEquals("d2", result.context().path("sourcePools").path("prayer-dice").path("dice").get(0).path("id").asText());

        ObjectNode invalid = rollContext.deepCopy();
        invalid.withObject("/event").set("roll", RuleJson.parse("{\"id\":\"bad\",\"contributions\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, aid, invalid, noRandom()));
        assertEquals("d1", invalid.path("sourcePools").path("prayer-dice").path("dice").get(0).path("id").asText());
    }

    @Test
    void actorScopedNumericPoolIsSharedByIndependentSourceProgramsAndCostsAreAtomic() throws Exception {
        RuleCompiler.Program first = program("classes:source-a");
        RuleCompiler.Program second = program("classes:source-b");
        ObjectNode before = context(first.sourceId(), "First Source");
        ObjectNode gained = RuleRuntime.execute(first, intent("gain-favor", before, "{}", null), before, noRandom()).context();
        assertEquals(3, gained.path("actor").path("pools").path("warlock-favor").path("value").asInt());

        ObjectNode secondContext = gained.deepCopy();
        secondContext.withObject("/source").put("id", second.sourceId());
        RuleRuntime.Resolution spending = RuleRuntime.execute(second, intent("spend-favor", secondContext, "{}", null), secondContext, noRandom());
        ObjectNode spent = spending.context();
        assertEquals(1, spent.path("actor").path("pools").path("warlock-favor").path("value").asInt());
        assertEquals("Spent 2 Favor.", spending.report());
        ObjectNode unchanged = spent.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.execute(second, intent("spend-favor", spent, "{}", null), spent, noRandom()));
        assertEquals(unchanged, spent);
        ObjectNode rested = spent.deepCopy();
        RuleRuntime.resetLimits(second, rested, "rest.long");
        assertEquals(1, rested.path("actor").path("pools").path("warlock-favor").path("value").asInt());
    }

    @Test
    void poolCapacityAndNumericGainAreCappedBeforeMutationOrRandomSampling() throws Exception {
        RuleCompiler.Program program = program("classes:capacity-test");
        ObjectNode before = context(program.sourceId(), "Pool");
        ObjectNode first = RuleRuntime.execute(program, intent("gather", before, "{}", null), before, faces(1, 2, 3)).context();
        ObjectNode unchanged = first.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.execute(program, intent("gather", first, "{}", null), first, noRandom()));
        assertEquals(unchanged, first);

        ObjectNode gained = RuleRuntime.execute(program, intent("gain-favor", before, "{}", null), before, noRandom()).context();
        gained = RuleRuntime.execute(program, intent("gain-favor", gained, "{}", null), gained, noRandom()).context();
        RuleRuntime.Resolution capped = RuleRuntime.execute(program, intent("gain-favor", gained, "{}", null), gained, noRandom());
        assertEquals(6, capped.context().path("actor").path("pools").path("warlock-favor").path("value").asInt());
        assertEquals("Gained 1 Favor.", capped.report());
    }

    @Test
    void poolDeclarationsAndPerDieBindingsAreStrictlyTyped() throws Exception {
        ObjectNode automation = fixture("rewrite/pools");
        automation.withObject("/pools/prayer-dice").put("scope", "global");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("classes:test", automation, fixture("rewrite/recipes")));
        automation.withObject("/pools/prayer-dice").put("scope", "source");
        ((ObjectNode) automation.withArray("/rules").get(1).withArray("/bindings").get(0)).put("forEach", "missing-pool");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("classes:test", automation, fixture("rewrite/recipes")));
        ((ObjectNode) automation.withArray("/rules").get(1).withArray("/bindings").get(0)).put("forEach", "warlock-favor");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("classes:test", automation, fixture("rewrite/recipes")));
        ObjectNode wrongPool = fixture("rewrite/pools");
        ((ObjectNode) wrongPool.withArray("/rules").get(2).withArray("/do").get(0)).put("pool", "warlock-favor");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("classes:test", wrongPool, fixture("rewrite/recipes")));
    }
}
