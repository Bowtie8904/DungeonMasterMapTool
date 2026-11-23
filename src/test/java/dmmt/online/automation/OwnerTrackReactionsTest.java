package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class OwnerTrackReactionsTest {
    private static RuleCompiler.Program program(String id, String automation) throws Exception {
        return RuleCompiler.compile(id, RuleJson.parse(automation), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String id, String instance) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"%s","name":"Source"},
                 "actor":{"id":"actor","name":"Actor","hope":{"value":3,"max":6},
                          "armorTrack":{"marked":1,"capacity":3},"stress":{"marked":0,"capacity":6}},
                 "event":{"type":"action.request","origin":"retained","table":{"fear":2}},
                 "revision":0}
                """.formatted(id, instance));
    }

    private static RuleRuntime.Intent select(String rule, String instance) {
        return new RuleRuntime.Intent(rule, "use", instance, 0, RuleJson.JSON.createObjectNode());
    }

    @Test
    void nestedSourceReactionsShareStateFearAndOneRevisionInOperationOrder() throws Exception {
        RuleCompiler.Program first = program("features:first", """
                {"state":{"steps":0},"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":3}],
                  "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"Before"},
                        {"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"table.spend","resource":"fear","amount":1,"label":"After"},
                        {"op":"state.increment","slot":"steps","by":10},
                        {"op":"value","value":{"read":"actor.hope.value"},"as":"hope"}],
                  "ui":{"label":"Use","report":"Hope {result.hope}, Fear {event.table.fear}."}},
                 {"id":"zero","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.after.value"},0]}]},
                  "do":[{"op":"state.increment","slot":"steps","by":1},
                        {"op":"track.clear","track":"actor.armorTrack","amount":2},
                        {"op":"table.gain","resource":"fear","amount":1,"label":"Zero"}],
                  "ui":{"report":"Zero."}},
                 {"id":"eligible","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"actor.hope.value"},3]},
                  "do":[{"op":"value","value":{"read":"actor.hope.value"},"as":"hope"}],
                  "ui":{"report":"Initial eligibility, current Hope {result.hope}."}},
                 {"id":"not-eligible","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"actor.hope.value"},0]},
                  "ui":{"report":"Must not run."}}
                ]}
                """);
        RuleCompiler.Program second = program("features:second", """
                {"state":{"overflows":0,"gains":0},"rules":[
                 {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.change"},"clear"]},
                                  {"gt":[{"read":"event.requested"},
                                         {"subtract":[{"read":"event.before.marked"},{"read":"event.after.marked"}]}]}]},
                  "do":[{"op":"state.increment","slot":"overflows","by":1},
                        {"op":"resource.gain","pool":"actor.hope","amount":1}],
                  "ui":{"report":"Overflow."}},
                 {"id":"gain","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"gain"]}]},
                  "do":[{"op":"state.increment","slot":"gains","by":1}],"ui":{"report":"Gain."}}
                ]}
                """);
        ObjectNode one = context(first.sourceId(), "one"), two = context(second.sourceId(), "two");
        ObjectNode originalOne = one.deepCopy(), originalTwo = two.deepCopy();
        var result = RuleDispatcher.executeOwnerTrackReactions(
                List.of(new RuleDispatcher.Candidate(first, one), new RuleDispatcher.Candidate(second, two)),
                List.of(select("use", "one")), noRandom(), Map.of(first.sourceId(), first, second.sourceId(), second));
        assertEquals(List.of("gain", "overflow", "zero", "eligible", "gain", "overflow", "use"),
                result.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Initial eligibility, current Hope 1.", result.actions().get(3).report());
        assertEquals("Hope 2, Fear 3.", result.actions().getLast().report());
        assertEquals(List.of("Zero", "Before", "After"),
                result.outbox().stream().map(command -> command.path("label").asText()).toList());
        assertEquals(List.of(2, 3, 4), result.outbox().stream().map(command -> command.path("before").asInt()).toList());
        assertEquals(List.of(3, 4, 3), result.outbox().stream().map(command -> command.path("after").asInt()).toList());
        ObjectNode outOne = result.contexts().get(0), outTwo = result.contexts().get(1);
        assertEquals(11, outOne.at("/state/steps").asInt());
        assertEquals(2, outTwo.at("/state/overflows").asInt());
        assertEquals(2, outTwo.at("/state/gains").asInt());
        for (ObjectNode context : result.contexts()) {
            assertEquals(2, context.at("/actor/hope/value").asInt());
            assertEquals(0, context.at("/actor/armorTrack/marked").asInt());
            assertEquals(1, context.path("revision").asInt());
            assertEquals("action.request", context.at("/event/type").asText());
            assertEquals("retained", context.at("/event/origin").asText());
            assertEquals(3, context.at("/event/table/fear").asInt());
        }
        assertEquals(originalOne, one);
        assertEquals(originalTwo, two);
    }

    @Test
    void recursiveTrackChangesAreBoundedAndLeaveTheInputUntouched() throws Exception {
        RuleCompiler.Program program = program("features:loop", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Use"}},
                 {"id":"gain","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"spend"]}]},
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}},
                 {"id":"spend","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"gain"]}]},
                  "do":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "loop"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "loop")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("reactionLimit", failure.code());
        assertEquals(original, before);
    }

    @Test
    void sequentialTrackEventsAlsoHaveAWholeTransactionBudget() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"do":[],"ui":{"label":"Use"}}]}
                """);
        ObjectNode rule = (ObjectNode) automation.withArray("/rules").get(0);
        for (int i = 0; i < 128; i++) rule.withArray("/do")
                .addObject().put("op", "track.clear").put("track", "actor.armorTrack").put("amount", 1);
        RuleCompiler.Program program = RuleCompiler.compile("features:budget", automation, fixture("rewrite/recipes"));
        ObjectNode before = context(program.sourceId(), "budget"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "budget")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("reactionLimit", failure.code());
        assertEquals(original, before);
    }

    @Test
    void armorReactionSettlesBeforeDamageMarksHitPoints() throws Exception {
        RuleCompiler.Program program = program("features:damage", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"damage.resolve","from":{"read":"event.damage"},
                    "thresholds":{"majorAt":5,"severeAt":10},
                    "armor":{"track":"actor.armorTrack","marked":"marked","capacity":"capacity","spend":1,"severitySteps":1},
                    "hitPoints":{"track":"actor.hp","marked":"marked","capacity":"capacity","minor":1,"major":2,"severe":3},
                    "as":"damage"}],
                  "ui":{"label":"Use","report":"Marked {result.damage.hitPoints.applied} HP."}},
                 {"id":"armor-full","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.after.marked"},3]}]},
                  "do":[{"op":"track.mark","track":"actor.hp","amount":5}],"ui":{"report":"Armor reaction."}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "damage");
        before.withObject("/actor/armorTrack").put("marked", 2);
        before.withObject("/actor").putObject("hp").put("marked", 0).put("capacity", 6);
        before.withObject("/event").putObject("damage").put("id", "damage").put("stage", "preSeverity")
                .put("baseAmount", 12).put("amount", 12).putArray("adjustments");
        ObjectNode original = before.deepCopy();
        var result = RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                List.of(select("use", "damage")), noRandom(), Map.of(program.sourceId(), program));
        assertEquals(List.of("armor-full", "use"),
                result.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Marked 1 HP.", result.actions().getLast().report());
        assertEquals(6, result.contexts().getFirst().at("/actor/hp/marked").asInt());
        assertEquals(original, before);
    }

    @Test
    void failingNestedReactionDiscardsCostsAndPriorReactionChanges() throws Exception {
        RuleCompiler.Program program = program("features:failure", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Use"}},
                 {"id":"fail","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.track"},"hope"]},
                  "do":[{"op":"track.mark","track":"actor.stress","amount":1},
                        {"op":"table.spend","resource":"fear","amount":3,"label":"Too much"}],"ui":{}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "failure"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "failure")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("tableResource", failure.code());
        assertEquals(original, before);
    }
}
