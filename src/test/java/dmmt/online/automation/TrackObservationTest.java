package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class TrackObservationTest {
    private static RuleCompiler.Program program(String rule) throws Exception {
        return RuleCompiler.compile("features:test", RuleJson.parse("{\"rules\":[" + rule + "]}"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String event) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:test","instanceId":"instance","name":"Test"},
                 "actor":{"id":"actor","name":"Actor","hope":{"value":3,"max":6},
                          "armor":{"marked":0,"capacity":3},"stress":{"marked":0,"capacity":6},"reserve":0},
                 "recipient":{"id":"ally","name":"Ally","armor":{"marked":0,"capacity":3}},
                 "revision":0,"event":{"type":"%s","origin":"retained","table":{"fear":2}}}
                """.formatted(event));
    }

    private static RuleRuntime.Intent intent(String rule, ObjectNode context) {
        return new RuleRuntime.Intent(rule, "b", "instance", context.path("revision").asLong(),
                RuleJson.JSON.createObjectNode());
    }

    @Test
    void observesCostsAndStepsInOrderOnThePrivateContextIncludingEmptyClears() throws Exception {
        RuleCompiler.Program program = program("""
                {"id":"act","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                 "do":[{"op":"track.clear","track":"actor.armor","amount":1},
                       {"op":"track.mark","track":"actor.stress","amount":{"read":"actor.hope.value"}},
                       {"op":"track.clear","track":"actor.armor","amount":1},
                       {"op":"resource.gain","pool":"actor.hope","amount":1}],
                 "ui":{"label":"Act"}}
                """);
        ObjectNode before = context("action.request");
        ObjectNode original = before.deepCopy();
        List<ObjectNode> events = new ArrayList<>();
        RuleRuntime.TrackObserver observer = (staged, holder, event) -> {
            assertEquals("actor", holder);
            assertEquals("track.changed", event.path("type").asText());
            assertEquals("actor", event.path("actorId").asText());
            assertEquals("retained", event.path("origin").asText());
            assertEquals(2, event.at("/table/fear").asInt());
            events.add(event.deepCopy());
            ObjectNode hope = staged.withObject("/actor/hope");
            if ("spend".equals(event.path("change").asText())) hope.put("value", 2);
            if ("clear".equals(event.path("change").asText())) hope.put("value", hope.path("value").asInt() + 1);
        };
        RuleRuntime.Resolution result = RuleRuntime.executeObserved(program, intent("act", before), before,
                noRandom(), Map.of(program.sourceId(), program), observer);
        assertEquals(original, before);
        assertEquals(List.of("spend", "clear", "mark", "clear", "gain"),
                events.stream().map(event -> event.path("change").asText()).toList());
        assertEquals(3, events.get(0).at("/before/value").asInt());
        assertEquals(1, events.get(0).at("/after/value").asInt());
        assertEquals(1, events.get(1).path("requested").asInt());
        assertEquals(0, events.get(1).at("/before/marked").asInt());
        assertEquals(0, events.get(1).at("/after/marked").asInt());
        assertEquals(3, result.context().at("/actor/stress/marked").asInt());
        assertEquals(5, result.context().at("/actor/hope/value").asInt());
        assertEquals("action.request", result.context().at("/event/type").asText());

        events.clear();
        RuleRuntime.Prepared prepared = RuleRuntime.prepare(program, intent("act", before), before);
        RuleRuntime.executePrepared(program, prepared, null, true, Map.of(program.sourceId(), program), false, observer);
        assertTrue(events.isEmpty(), "Preview and its cost preflight never call the observer.");
        assertEquals(original, before);
    }

    @Test
    void identifiesRecipientAndCapacityChangesAndIgnoresUnchangedOrdinaryOperations() throws Exception {
        RuleCompiler.Program program = program("""
                {"id":"act","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button",
                  "targetSelection":{"cardinality":"one","scope":"activeCharacters"}}],
                 "do":[{"op":"track.clear","track":"recipient.armor","amount":2},
                       {"op":"track.capacity","track":"actor.armor","counter":"actor.reserve","by":-1},
                       {"op":"track.mark","track":"actor.stress","amount":0},
                       {"op":"resource.gain","pool":"actor.hope","amount":3},
                       {"op":"resource.gain","pool":"actor.hope","amount":1}],
                 "ui":{"label":"Act"}}
                """);
        ObjectNode before = context("action.request");
        List<String> holders = new ArrayList<>();
        List<ObjectNode> events = new ArrayList<>();
        RuleRuntime.Resolution result = RuleRuntime.executeObserved(program, intent("act", before), before, noRandom(),
                Map.of(program.sourceId(), program), (staged, holder, event) -> {
                    holders.add(holder);
                    events.add(event.deepCopy());
                });
        assertEquals(List.of("recipient", "actor", "actor"), holders);
        assertEquals("ally", events.get(0).path("actorId").asText());
        assertEquals("armor", events.get(0).path("track").asText());
        assertEquals(2, events.get(0).path("requested").asInt());
        assertEquals("capacity", events.get(1).path("change").asText());
        assertEquals(-1, events.get(1).path("requested").asInt());
        assertEquals(3, events.get(1).at("/before/capacity").asInt());
        assertEquals(2, events.get(1).at("/after/capacity").asInt());
        assertEquals(1, result.context().at("/actor/reserve").asInt());
        assertEquals(6, result.context().at("/actor/hope/value").asInt());
    }

    @Test
    void reactionFailureDoesNotMutateTheInputSnapshot() throws Exception {
        RuleCompiler.Program program = program("""
                {"id":"act","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                 "do":[{"op":"track.mark","track":"actor.stress","amount":1}],
                 "ui":{"label":"Act"}}
                """);
        ObjectNode before = context("action.request");
        ObjectNode original = before.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.executeObserved(program, intent("act", before),
                before, noRandom(), Map.of(program.sourceId(), program), (staged, holder, event) -> {
                    staged.withObject("/actor").put("reaction", true);
                    if ("stress".equals(event.path("track").asText()))
                        throw new RuleRuntime.Unavailable("reaction", "Reaction failed.");
                }));
        assertEquals(original, before);
    }

    @Test
    void selfTargetAliasesShareTheReactedActorAndNumericResourcesAreObserved() throws Exception {
        RuleCompiler.Program program = program("""
                {"id":"act","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button",
                  "targetSelection":{"cardinality":"one","scope":"activeCharacters"}}],
                 "do":[{"op":"track.clear","track":"recipient.armor","amount":1},
                       {"op":"resource.gain","pool":"actor.gold","amount":2},
                       {"op":"track.mark","track":"recipient.stress","amount":{"read":"actor.hope.value"}}],
                 "ui":{"label":"Act"}}
                """);
        ObjectNode before = context("action.request");
        before.withObject("/actor").put("gold", 4);
        before.set("recipient", before.get("actor"));
        List<ObjectNode> events = new ArrayList<>();
        RuleRuntime.Resolution result = RuleRuntime.executeObserved(program, intent("act", before), before, noRandom(),
                Map.of(program.sourceId(), program), (staged, holder, event) -> {
                    events.add(event.deepCopy());
                    if ("clear".equals(event.path("change").asText())) staged.withObject("/actor/hope").put("value", 2);
                });
        assertSame(result.context().get("actor"), result.context().get("recipient"));
        assertEquals(2, result.context().at("/recipient/stress/marked").asInt());
        assertEquals(4, events.get(1).at("/before/value").asInt());
        assertEquals(6, events.get(1).at("/after/value").asInt());
        assertEquals("actor", events.get(0).path("actorId").asText());
        assertEquals(3, before.at("/actor/hope/value").asInt());
    }

    @Test
    void observesDeferredCostsAndBenefitsBeforeTheNextDeferredStep() throws Exception {
        RuleCompiler.Program program = program("""
                {"id":"benefit","bindings":[{"id":"b","event":"downtime.move","surface":"downtime","mode":"option"}],
                 "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                   "do":[{"op":"track.clear","track":"actor.armor","amount":1},
                         {"op":"track.mark","track":"actor.stress","amount":{"read":"actor.hope.value"}}]},
                 "ui":{"label":"Benefit"}}
                """);
        ObjectNode before = context("downtime.move");
        ObjectNode original = before.deepCopy();
        List<String> changes = new ArrayList<>();
        RuleRuntime.Resolution result = RuleRuntime.executeAfter(program, intent("benefit", before), before, noRandom(),
                Map.of(program.sourceId(), program), (staged, holder, event) -> {
                    changes.add(event.path("change").asText());
                    if ("clear".equals(event.path("change").asText())) staged.withObject("/actor/hope").put("value", 4);
                });
        assertEquals(List.of("spend", "clear", "mark"), changes);
        assertEquals(4, result.context().at("/actor/stress/marked").asInt());
        assertEquals(original, before);
    }
}
