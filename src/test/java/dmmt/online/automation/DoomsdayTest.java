package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Legacy rollDoomsday (engine.ts) and items-consumables.test.ts as a {@code dice.sequence} rule. */
class DoomsdayTest {
    private static final String LEGACY = """
            {"format":"dmmap-daggerheart","version":1,"category":"consumables","label":"Fixture","entries":[
             {"id":"doomsday-potion","name":"Doomsday Potion","automation":{"actions":[{"id":"drink","label":"Drink","doomsday":{"effect":
              {"name":"Invincible","kind":"buff","until":"scene","text":"Immune to all damage.",
               "mitigation":[{"id":"invincible","label":"Invincible","passive":true,"negate":true}]}}}]}}]}""";

    private static final String ACTOR = """
            {"hope":{"value":%d,"max":%d},"scars":0,"hp":{"marked":%d,"capacity":6,"total":6},
             "stress":{"marked":%d,"capacity":6,"total":6},"effects":[]}""";

    private static RuleDispatcher.EventResolution run(String legacy, String actor, java.util.random.RandomGenerator random) throws Exception {
        var converted = LegacyCatalogConverter.convert(legacy);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        RuleCompiler.Program program = RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"source\":{\"id\":\"" + program.sourceId()
                + "\",\"instanceId\":\"p\",\"name\":\"Doomsday Potion\",\"quantity\":1},\"actor\":" + actor + ",\"revision\":0}");
        context.set("event", AutomationHost.event("action.request", "self", 0));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent("drink", "sheet", "p", 0, RuleJson.JSON.createObjectNode())), random);
    }

    @Test
    void converterOutputRollsOncePerHopeSlotAndGatesTheStopOutcome() throws Exception {
        JsonNode rule = LegacyCatalogConverter.convert(LEGACY).catalog().at("/entries/0/automation/rules/0");
        assertEquals("dice.sequence", rule.at("/do/0/op").asText());
        assertEquals(12, rule.at("/do/0/stopAt").asInt());
        assertEquals("track.capacity", rule.at("/do/1/op").asText());
        assertEquals("effect.start", rule.at("/do/4/op").asText(), rule.toString());
    }

    @Test
    void scarsMissedSlotsAndGrantsTheEffectOnTheFirstTwelve() throws Exception {
        // Legacy test: hope.on = 1, rolls 3,5 (marked, keep 5), 7, 12 -> two scars, HP and Stress cleared, Invincible.
        var result = run(LEGACY, ACTOR.formatted(1, 6, 3, 2), StructuredRollTest.faces(3, 5, 7, 12));
        JsonNode actor = result.contexts().getFirst().path("actor");
        assertEquals(2, actor.path("scars").asInt());
        assertEquals(4, actor.at("/hope/max").asInt());
        assertEquals(1, actor.at("/hope/value").asInt());
        assertEquals(0, actor.at("/hp/marked").asInt());
        assertEquals(0, actor.at("/stress/marked").asInt());
        assertEquals("Invincible", actor.at("/effects/0/label").asText(), actor.toString());
        assertTrue(result.actions().getFirst().report().contains("5, 7, 12"), result.actions().getFirst().report());
    }

    @Test
    void everySlotMissingScarsAllOfThemAndLeavesHpAndStressAlone() throws Exception {
        var result = run(LEGACY, ACTOR.formatted(0, 3, 3, 2), StructuredRollTest.faces(1, 2, 11));
        JsonNode actor = result.contexts().getFirst().path("actor");
        assertEquals(3, actor.path("scars").asInt());
        assertEquals(3, actor.at("/hp/marked").asInt());
        assertEquals(2, actor.at("/stress/marked").asInt());
        assertEquals(0, actor.path("effects").size());
    }

    @Test
    void aSequenceStopsAtItsFirstStopValueWhateverTheDie() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:seq", RuleJson.parse("""
                {"rules":[{"id":"seq","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                 "do":[{"op":"dice.sequence","count":5,"sides":6,"stopAt":6,"as":"run"}],
                 "ui":{"label":"Seq","report":"{result.run.faces|list}: {result.run.misses} missed, stopped {result.run.stopped}."}}]}
                """), RuleConformanceTest.fixture("rewrite/recipes"));
        ObjectNode facts = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:seq","instanceId":"p","name":"Seq"},"actor":{},"revision":0,"event":{"type":"action.request"}}""");
        var intent = new RuleRuntime.Intent("seq", "use", "p", 0, RuleJson.JSON.createObjectNode());
        assertEquals("2, 6: 1 missed, stopped true.", RuleRuntime.execute(program, intent, facts, StructuredRollTest.faces(2, 6, 1)).report());
        assertEquals("1, 2, 3, 4, 5: 5 missed, stopped false.",
                RuleRuntime.execute(program, intent, facts, StructuredRollTest.faces(1, 2, 3, 4, 5)).report());
    }
}
