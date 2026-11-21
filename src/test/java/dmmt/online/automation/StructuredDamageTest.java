package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.faces;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class StructuredDamageTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("classes:prayer-example", fixture("rewrite/pools"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(RuleCompiler.Program program, String eventType, int amount) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"instance-one","name":"Ward"},
                 "actor":{"id":"actor","name":"Player","extraDice":1,
                          "stress":{"marked":2,"capacity":6},"hope":{"value":2}},
                 "revision":0,
                 "event":{"type":"%s","damage":{
                   "id":"damage-1","stage":"preSeverity","baseAmount":%d,"amount":%d,"adjustments":[]
                 }}}
                """.formatted(program.sourceId(), eventType, amount, amount));
    }

    private static ObjectNode marksContext(int amount, int armorMarked, int hpMarked) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"actor":{"id":"actor","armor":{"marked":%d,"capacity":3},"hp":{"marked":%d,"capacity":6},
                          "thresholds":{"major":5,"severe":10}},
                 "event":{"type":"damage.marks","damage":{
                   "id":"damage-1","stage":"preSeverity","baseAmount":%d,"amount":%d,"adjustments":[]}}}
                """.formatted(armorMarked, hpMarked, amount, amount));
    }

    private static ObjectNode assess(ObjectNode context, int requested, boolean massive) throws Exception {
        return StructuredDamage.assess(context.path("event").path("damage"), RuleJson.parse("\"physical\""),
                RuleJson.parse("{\"majorAt\":{\"read\":\"actor.thresholds.major\"},\"severeAt\":{\"read\":\"actor.thresholds.severe\"}}"),
                RuleJson.parse(String.valueOf(massive)),
                RuleJson.parse("{\"track\":\"actor.armor\",\"marked\":\"marked\",\"capacity\":\"capacity\",\"requested\":" + requested + "}"),
                RuleJson.parse("{\"track\":\"actor.hp\",\"marked\":\"marked\",\"capacity\":\"capacity\"}"), context);
    }

    @Test
    void marksPlanDerivesSeverityArmorAndHitPointsFromOrderedAdjustments() throws Exception {
        ObjectNode context = marksContext(12, 2, 4);
        ObjectNode plan = assess(context, 3, false);
        assertEquals(3, plan.path("incoming").asInt());
        assertEquals(1, plan.path("armor").path("spent").asInt(), "One slot by default and only one is free.");
        assertEquals(2, plan.path("severity").asInt());
        assertEquals(2, plan.path("hitPoints").path("marks").asInt());
        assertEquals(4, assess(marksContext(20, 0, 0), 0, true).path("incoming").asInt());
        assertEquals(3, assess(marksContext(20, 0, 0), 0, false).path("incoming").asInt());

        ObjectNode steps = StructuredDamage.adjust(plan, "armor.steps", 2, "steps", "features:x", "Heavy plates");
        plan = (ObjectNode) steps.get("marks");
        assertEquals(1, plan.path("severity").asInt());
        plan = (ObjectNode) StructuredDamage.adjust(plan, "hitPoints.bonus", 3, "extra", "features:x", "Fragile").get("marks");
        assertEquals(2, plan.path("hitPoints").path("wouldMark").asInt(), "Capped at the two remaining Hit Points.");
        assertEquals(2, plan.path("hitPoints").path("marks").asInt());
        plan = (ObjectNode) StructuredDamage.adjust(plan, "armor.saved", 1, "save", "features:x", "Lucky").get("marks");
        plan = (ObjectNode) StructuredDamage.adjust(plan, "hitPoints.max", 0, "replace", "features:x", "Instead").get("marks");
        assertEquals(0, plan.path("hitPoints").path("marks").asInt());

        ObjectNode applied = StructuredDamage.mark(plan,
                RuleJson.parse("{\"track\":\"actor.armor\",\"marked\":\"marked\",\"capacity\":\"capacity\"}"),
                RuleJson.parse("{\"track\":\"actor.hp\",\"marked\":\"marked\",\"capacity\":\"capacity\"}"), context);
        assertEquals(2, context.path("actor").path("armor").path("marked").asInt(), "The spent slot was saved.");
        assertEquals(4, context.path("actor").path("hp").path("marked").asInt());
        assertEquals(1, applied.path("armorSaved").asInt());
        assertEquals(0, applied.path("armorMarked").asInt());
        assertEquals(2, applied.path("wouldMark").asInt());
        assertThrows(RuleRuntime.Unavailable.class, () -> StructuredDamage.mark(assess(marksContext(12, 0, 0), 1, false),
                RuleJson.parse("{\"track\":\"actor.armor\",\"marked\":\"marked\",\"capacity\":\"capacity\"}"),
                RuleJson.parse("{\"track\":\"actor.hp\",\"marked\":\"marked\",\"capacity\":\"capacity\"}"), context),
                "Tracks changed after assessment.");
    }

    @Test
    void marksPlanRejectsForgedValuesAndHistory() throws Exception {
        ObjectNode plan = (ObjectNode) StructuredDamage.adjust(assess(marksContext(12, 0, 0), 1, false),
                "severityReduction", 1, "dull", "features:x", "Dull").get("marks");
        StructuredDamage.validatePlan(plan);
        ObjectNode forgedSeverity = plan.deepCopy();
        forgedSeverity.put("severity", 0);
        ObjectNode forgedField = plan.deepCopy();
        forgedField.put("severityReduction", 3);
        ObjectNode forgedHistory = plan.deepCopy();
        ((ObjectNode) forgedHistory.path("adjustments").get(0)).put("before", 2);
        ObjectNode unknown = plan.deepCopy();
        unknown.put("extra", 1);
        for (ObjectNode forged : new ObjectNode[]{forgedSeverity, forgedField, forgedHistory, unknown})
            assertThrows(IllegalArgumentException.class, () -> StructuredDamage.validatePlan(forged), forged.toString());
        assertThrows(IllegalArgumentException.class, () -> StructuredDamage.adjust(plan, "amount", 1, "a", "features:x", "A"));
        assertThrows(IllegalArgumentException.class, () -> StructuredDamage.adjust(plan, "severityReduction", 2, "dull", "features:x", "A"));
    }

    @Test
    void marksStageRunsThroughCompiledRules() throws Exception {
        String tracks = "\"armor\":{\"track\":\"actor.armor\",\"marked\":\"marked\",\"capacity\":\"capacity\"%s},"
                + "\"hitPoints\":{\"track\":\"actor.hp\",\"marked\":\"marked\",\"capacity\":\"capacity\"}";
        RuleCompiler.Program program = RuleCompiler.compile("features:x", RuleJson.parse("""
                {"rules":[
                  {"id":"assess","bindings":[{"id":"b","event":"damage.marks","surface":"damage","mode":"button"}],
                   "do":[{"op":"damage.assess","from":{"read":"event.damage"},"type":"physical","massive":false,
                          "thresholds":{"majorAt":5,"severeAt":10},%s,"as":"plan"},
                         {"op":"marks.publish","marks":{"read":"result.plan"}}],"ui":{"label":"Assess"}},
                  {"id":"soften","bindings":[{"id":"b","event":"damage.marks","surface":"damage","mode":"option"}],
                   "do":[{"op":"damage.adjust","from":{"read":"event.marks"},"field":"severityReduction",
                          "to":{"add":[{"read":"event.marks.severityReduction"},1]},"id":"soften","label":"Soften","as":"plan"},
                         {"op":"marks.publish","marks":{"read":"result.plan"}}],"ui":{"label":"Soften"}},
                  {"id":"apply","bindings":[{"id":"b","event":"damage.marks","surface":"damage","mode":"button"}],
                   "do":[{"op":"damage.mark","from":{"read":"event.marks"},%s}],"ui":{"label":"Apply"}}]}
                """.formatted(tracks.formatted(",\"requested\":1"), tracks.formatted(""))), fixture("rewrite/recipes"));
        ObjectNode context = marksContext(12, 0, 0);
        context.putObject("source").put("id", program.sourceId()).put("instanceId", "one").put("name", "Ward");
        context.put("revision", 0);
        ObjectNode assessed = RuleRuntime.execute(program, new RuleRuntime.Intent("assess", "b", "one", 0,
                (ObjectNode) RuleJson.parse("{}")), context, noRandom()).context();
        assertEquals(2, assessed.path("event").path("marks").path("severity").asInt());
        ObjectNode softened = RuleRuntime.execute(program, new RuleRuntime.Intent("soften", "b", "one",
                assessed.path("revision").asLong(), (ObjectNode) RuleJson.parse("{}")), assessed, noRandom()).context();
        assertEquals(1, softened.path("event").path("marks").path("severity").asInt());
        ObjectNode applied = RuleRuntime.execute(program, new RuleRuntime.Intent("apply", "b", "one",
                softened.path("revision").asLong(), (ObjectNode) RuleJson.parse("{}")), softened, noRandom()).context();
        assertEquals(1, applied.path("actor").path("hp").path("marked").asInt());
        assertEquals(1, applied.path("actor").path("armor").path("marked").asInt());
        assertEquals(1, applied.path("event").path("applied").path("hitPoints").asInt());
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:x", RuleJson.parse("""
                {"rules":[{"id":"r","bindings":[{"id":"b","event":"damage.marks","surface":"damage","mode":"button"}],
                  "do":[{"op":"damage.adjust","from":{"read":"event.marks"},"field":"amount","to":1,"id":"a","label":"A"}],
                  "ui":{"label":"x"}}]}"""), fixture("rewrite/recipes")));
    }

    @Test
    void storedPoolDieReducesPreSeverityDamageByActualCappedAmount() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode before = context(program, "action.request", 4);
        ObjectNode drawn = RuleRuntime.execute(program,
                new RuleRuntime.Intent("gather", "sheet", "instance-one", 0, (ObjectNode) RuleJson.parse("{}")),
                before, faces(2, 6, 6)).context();
        ObjectNode event = (ObjectNode) drawn.path("event");
        event.put("type", "damage.prepare");
        event.set("damage", RuleJson.parse("""
                {"id":"damage-1","stage":"preSeverity","baseAmount":4,"amount":4,"adjustments":[]}
                """));
        ObjectNode descriptor = (ObjectNode) RuleRuntime.describeEach(program, "reduce-damage", "incoming", drawn).get(0);
        assertEquals("Reduce damage by 6", descriptor.path("label").asText());
        RuleRuntime.Intent intent = new RuleRuntime.Intent("reduce-damage", "incoming", "instance-one",
                drawn.path("revision").asLong(), (ObjectNode) RuleJson.parse("{}"), descriptor.path("eachId").asText());

        RuleRuntime.Resolution reduction = RuleRuntime.execute(program, intent, drawn, noRandom());
        ObjectNode resultDamage = (ObjectNode) reduction.context().path("result").path("reduction").path("damage");
        assertEquals(0, resultDamage.path("amount").asInt());
        assertEquals(6, resultDamage.path("adjustments").get(0).path("requested").asInt());
        assertEquals(4, resultDamage.path("adjustments").get(0).path("applied").asInt());
        assertEquals("Ward die d6", resultDamage.path("adjustments").get(0).path("label").asText());
        assertEquals("Ward reduced damage by 4.", reduction.report());
        assertEquals(1, reduction.context().path("sourcePools").path("prayer-dice").path("dice").size());
        assertEquals(4, drawn.path("event").path("damage").path("amount").asInt());
    }

    @Test
    void fixedWardUsesSameReductionPrimitiveAndCommitsItsCost() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode before = context(program, "damage.prepare", 2);
        ObjectNode descriptor = RuleRuntime.describe(program, "fixed-ward", "incoming", before);
        assertTrue(descriptor.path("available").asBoolean());
        RuleRuntime.Intent intent = new RuleRuntime.Intent("fixed-ward", "incoming", "instance-one", 0,
                (ObjectNode) RuleJson.parse("{}"));

        RuleRuntime.Resolution resolution = RuleRuntime.execute(program, intent, before, noRandom());
        ObjectNode damage = (ObjectNode) resolution.context().path("result").path("reduction").path("damage");
        assertEquals(0, damage.path("amount").asInt());
        assertEquals(3, damage.path("adjustments").get(0).path("requested").asInt());
        assertEquals(2, damage.path("adjustments").get(0).path("applied").asInt());
        assertEquals(1, resolution.context().path("actor").path("hope").path("value").asInt());
        assertEquals("Ward reduced damage by 2.", resolution.report());
        assertEquals(2, before.path("actor").path("hope").path("value").asInt());
    }

    @Test
    void zeroDamageHasNoAvailableReductionAndMalformedHistoryIsRejected() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode noDamage = context(program, "damage.prepare", 0);
        assertFalse(RuleRuntime.describe(program, "fixed-ward", "incoming", noDamage).path("available").asBoolean());
        assertTrue(RuleRuntime.describeEach(program, "reduce-damage", "incoming", noDamage).isEmpty());

        ObjectNode malformed = (ObjectNode) RuleJson.parse("""
                {"id":"damage-1","stage":"preSeverity","baseAmount":4,"amount":1,
                 "adjustments":[{"id":"ward-1","sourceId":"classes:ward","label":"Ward",
                   "requested":2,"applied":2,"before":4,"after":2}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> StructuredDamage.validate(malformed));
        malformed.put("stage", "postArmor");
        assertThrows(IllegalArgumentException.class, () -> StructuredDamage.validate(malformed));
    }

    @Test
    void distinctReductionsComposeInOrderWithoutChangingTheBaseAmount() throws Exception {
        ObjectNode initial = (ObjectNode) RuleJson.parse("""
                {"id":"damage-2","stage":"preSeverity","baseAmount":7,"amount":7,"adjustments":[]}
                """);
        ObjectNode first = StructuredDamage.reduce(initial, 2, "ward-one", "features:ward-one", "Ward One");
        ObjectNode second = StructuredDamage.reduce(first.path("damage"), 8, "ward-two", "features:ward-two", "Ward Two");
        ObjectNode damage = (ObjectNode) second.path("damage");

        assertEquals(7, damage.path("baseAmount").asInt());
        assertEquals(0, damage.path("amount").asInt());
        assertEquals(5, damage.path("adjustments").get(1).path("before").asInt());
        assertEquals(5, damage.path("adjustments").get(1).path("applied").asInt());
        assertEquals(damage, StructuredDamage.validate(damage));
    }

    @Test
    void increasesComposeWithReductionsAndForgedIncreasesAreRejected() throws Exception {
        ObjectNode initial = (ObjectNode) RuleJson.parse("""
                {"id":"damage-3","stage":"preSeverity","baseAmount":7,"amount":7,"adjustments":[]}
                """);
        ObjectNode doubled = StructuredDamage.increase(initial, 7, "frail", "features:frail", "Frail");
        ObjectNode reduced = StructuredDamage.reduce(doubled.path("damage"), 4, "ward", "features:ward", "Ward");
        ObjectNode damage = (ObjectNode) reduced.path("damage");
        assertEquals(10, damage.path("amount").asInt());
        assertTrue(damage.at("/adjustments/0/increase").asBoolean());
        assertEquals(damage, StructuredDamage.validate(damage));

        ObjectNode forged = damage.deepCopy();
        ((ObjectNode) forged.at("/adjustments/0")).remove("increase");
        assertThrows(IllegalArgumentException.class, () -> StructuredDamage.validate(forged));
    }

    @Test
    void configuredDamageResolutionAppliesSeverityArmorAndBoundedHitPointMarks() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode before = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"classes:prayer-example","instanceId":"damage-target","name":"Target"},
                 "actor":{"armorTrack":{"slots":3,"on":0},"hp":{"slots":6,"on":2}},
                 "revision":0,"event":{"type":"damage.prepare","damage":{
                   "id":"damage-resolve-1","stage":"preSeverity","baseAmount":7,"amount":7,"adjustments":[]
                 }}}
                """);
        ObjectNode descriptor = RuleRuntime.describe(program, "resolve-damage", "apply", before);
        assertTrue(descriptor.path("available").asBoolean());
        assertEquals(3, descriptor.path("inputs").get(0).path("max").asInt());
        RuleRuntime.Intent intent = new RuleRuntime.Intent("resolve-damage", "apply", "damage-target", 0,
                (ObjectNode) RuleJson.parse("{\"armorSlots\":1}"));

        RuleRuntime.Resolution resolution = RuleRuntime.execute(program, intent, before, noRandom());

        ObjectNode output = (ObjectNode) resolution.context().path("result").path("resolution");
        assertEquals("major", output.path("severityBeforeArmor").asText());
        assertEquals("minor", output.path("severity").asText());
        assertEquals(1, output.path("armor").path("spent").asInt());
        assertEquals(1, resolution.context().path("actor").path("armorTrack").path("on").asInt());
        assertEquals(1, output.path("hitPoints").path("applied").asInt());
        assertEquals(3, resolution.context().path("actor").path("hp").path("on").asInt());
        assertEquals("Damage became minor; marked 1 HP.", resolution.report());
        assertEquals(0, before.path("actor").path("armorTrack").path("on").asInt());
    }

    @Test
    void damageResolutionRejectsArmorSpendWithoutSeverityBenefit() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode before = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"classes:prayer-example","instanceId":"damage-target","name":"Target"},
                 "actor":{"armorTrack":{"slots":3,"on":0},"hp":{"slots":6,"on":0}},
                 "revision":0,"event":{"type":"damage.prepare","damage":{
                   "id":"damage-resolve-2","stage":"preSeverity","baseAmount":2,"amount":2,"adjustments":[]
                 }}}
                """);
        RuleRuntime.Intent intent = new RuleRuntime.Intent("resolve-damage", "apply", "damage-target", 0,
                (ObjectNode) RuleJson.parse("{\"armorSlots\":1}"));

        RuleRuntime.Unavailable error = assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.execute(program, intent, before, noRandom()));
        assertEquals("armorNoBenefit", error.code());
        assertEquals(0, before.path("actor").path("armorTrack").path("on").asInt());
        assertEquals(0, before.path("actor").path("hp").path("on").asInt());
    }
}
