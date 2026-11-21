package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class RuleDispatcherTest {
    private static RuleCompiler.Program program(String sourceId) throws Exception {
        return RuleCompiler.compile(sourceId, fixture("rewrite/pools"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String sourceId, String instanceId, String name) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"%s","name":"%s"},
                 "actor":{"id":"actor","name":"Player","extraDice":1,
                          "stress":{"marked":2,"capacity":6},"hope":{"value":2}},
                 "revision":0,"event":{"type":"action.request"}}
                """.formatted(sourceId, instanceId, name));
    }

    @Test
    void descriptionsAcrossActiveSourcesAreFilteredAndDeterministicallyOrdered() throws Exception {
        RuleCompiler.Program first = program("classes:first");
        RuleCompiler.Program second = program("classes:second");
        RuleDispatcher.Candidate firstSource = new RuleDispatcher.Candidate(first, context(first.sourceId(), "z-instance", "First"));
        RuleDispatcher.Candidate secondSource = new RuleDispatcher.Candidate(second, context(second.sourceId(), "a-instance", "Second"));

        var forward = RuleDispatcher.describe(List.of(firstSource, secondSource), "sheet");
        var reverse = RuleDispatcher.describe(List.of(secondSource, firstSource), "sheet");
        assertEquals(forward, reverse);
        assertEquals(6, forward.size());
        assertEquals("a-instance", forward.get(0).path("sourceInstanceId").asText());
        assertEquals("gain-favor", forward.get(0).path("ruleId").asText());
        assertEquals("z-instance", forward.get(3).path("sourceInstanceId").asText());
        assertEquals("gain-favor", forward.get(3).path("ruleId").asText());
        assertTrue(forward.findValuesAsText("surface").stream().allMatch("sheet"::equals));
        assertTrue(forward.findValuesAsText("ruleId").stream().noneMatch("aid-roll"::equals));
    }

    @Test
    void rejectsMixedEventSnapshotsAndDuplicateActiveInstances() throws Exception {
        RuleCompiler.Program first = program("classes:first");
        RuleCompiler.Program second = program("classes:second");
        ObjectNode firstContext = context(first.sourceId(), "same-instance", "First");
        ObjectNode secondContext = context(second.sourceId(), "other-instance", "Second");
        secondContext.putObject("event").put("type", "roll.finished");
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.describe(List.of(
                new RuleDispatcher.Candidate(first, firstContext),
                new RuleDispatcher.Candidate(second, secondContext)), "sheet"));

        secondContext.putObject("event").put("type", "action.request");
        secondContext.withObject("/source").put("instanceId", "same-instance");
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.describe(List.of(
                new RuleDispatcher.Candidate(first, firstContext),
                new RuleDispatcher.Candidate(second, secondContext)), "sheet"));
    }

    @Test
    void optionBindingsAreDiscoveredSeparatelyFromAutomaticBindings() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:option", RuleJson.parse("""
                {"rules":[
                  {"id":"selected",
                   "bindings":[{"id":"attack","event":"roll.prepare","surface":"roll","mode":"option","defaultOn":true,"group":"result"}],
                   "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                   "do":[{"op":"value","value":true,"as":"applied"}],
                   "ui":{"label":"Use the option","report":"Applied."}},
                  {"id":"automatic",
                   "bindings":[{"id":"apply","event":"roll.prepare","surface":"roll","mode":"automatic"}],
                   "do":[{"op":"value","value":true,"as":"applied"}],
                   "ui":{}}
                ]}"""), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:option","instanceId":"option-instance","name":"Option"},
                 "actor":{"hope":{"value":2}},"revision":0,"event":{"type":"roll.prepare"}}
                """);
        var descriptions = RuleDispatcher.describe(List.of(new RuleDispatcher.Candidate(program, context)), "roll");
        assertEquals(1, descriptions.size());
        assertEquals("option", descriptions.get(0).path("mode").asText());
    }

    @Test
    void eventExecutionRunsAutomaticRulesAndSelectedActionsAsOneRevision() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:transaction", RuleJson.parse("""
                {"state":{"steps":0},"limits":{"once":{"max":1,"resetOn":["rest.long"]}},
                 "rules":[
                   {"id":"automatic","bindings":[{"id":"apply","event":"action.request","surface":"sheet","mode":"automatic","priority":-2}],
                    "do":[{"op":"state.increment","slot":"steps","by":1}],"ui":{"report":"Automatic."}},
                   {"id":"selected","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button","priority":-10}],
                    "limitRef":"once","costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"state.increment","slot":"steps","by":1}],"ui":{"label":"Use it","report":"Selected."}}
                 ]}"""), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:transaction","instanceId":"transaction","name":"Transaction"},
                 "actor":{"hope":{"value":2,"max":6}},"revision":0,"event":{"type":"action.request"}}
                """);
        RuleRuntime.Intent selection = new RuleRuntime.Intent("selected", "use", "transaction", 0,
                RuleJson.JSON.createObjectNode());

        RuleDispatcher.EventResolution resolution = RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(program, context)), List.of(selection), RandomGenerator.getDefault());

        assertEquals(List.of("automatic", "selected"),
                resolution.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        ObjectNode updated = resolution.contexts().getFirst();
        assertEquals(2, updated.path("state").path("steps").asInt());
        assertEquals(1, updated.path("usage").path("once").asInt());
        assertEquals(1, updated.path("actor").path("hope").path("value").asInt());
        assertEquals(1, updated.path("revision").asInt());
        assertEquals(2, context.path("actor").path("hope").path("value").asInt());
    }

    @Test
    void eventPreflightRejectsAggregateCostsWithoutReturningPartialUpdates() throws Exception {
        RuleCompiler.Program first = optionProgram("features:first-cost");
        RuleCompiler.Program second = optionProgram("features:second-cost");
        ObjectNode firstContext = context(first.sourceId(), "first-instance", "First");
        ObjectNode secondContext = context(second.sourceId(), "second-instance", "Second");
        firstContext.withObject("/actor").withObject("hope").put("value", 3);
        firstContext.withObject("/actor").withObject("hope").put("max", 6);
        secondContext.set("actor", firstContext.path("actor").deepCopy());
        RuleDispatcher.Candidate firstCandidate = new RuleDispatcher.Candidate(first, firstContext);
        RuleDispatcher.Candidate secondCandidate = new RuleDispatcher.Candidate(second, secondContext);

        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeEvent(
                List.of(firstCandidate, secondCandidate),
                List.of(new RuleRuntime.Intent("spend", "use", "first-instance", 0, RuleJson.JSON.createObjectNode()),
                        new RuleRuntime.Intent("spend", "use", "second-instance", 0, RuleJson.JSON.createObjectNode())),
                RandomGenerator.getDefault()));
        assertEquals(3, firstCandidate.context().path("actor").path("hope").path("value").asInt());
        assertEquals(3, secondCandidate.context().path("actor").path("hope").path("value").asInt());
        assertEquals(0, firstCandidate.context().path("revision").asInt());
    }

    @Test
    void groupedInputsAreValidatedAgainstTheSharedEventSnapshotBeforeCostsAreReserved() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:preflight", RuleJson.parse("""
                {"rules":[
                  {"id":"spend","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"option","priority":2}],
                   "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                   "do":[{"op":"value","value":true}],"ui":{"label":"Spend"}},
                  {"id":"inspect","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"option","priority":1}],
                   "inputs":[{"id":"amount","kind":"integer","min":0,"max":{"read":"actor.hope.value"},"label":"Amount"}],
                   "do":[{"op":"value","value":{"read":"input.amount"},"as":"selected"}],
                   "ui":{"label":"Inspect","report":"Selected {result.selected}."}}
                ]}"""), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:preflight","instanceId":"preflight","name":"Preflight"},
                 "actor":{"id":"actor","hope":{"value":2,"max":6}},
                 "revision":0,"event":{"type":"action.request"}}
                """);

        RuleDispatcher.EventResolution result = RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent("spend", "use", "preflight", 0, RuleJson.JSON.createObjectNode()),
                        new RuleRuntime.Intent("inspect", "use", "preflight", 0,
                                (ObjectNode) RuleJson.parse("{\"amount\":2}"))),
                RandomGenerator.getDefault());

        assertEquals("Selected 2.", result.actions().get(1).report());
        assertEquals(1, result.contexts().getFirst().path("actor").path("hope").path("value").asInt());
    }

    @Test
    void eventExecutionAllowsIndependentButtonsAndRejectsExclusiveOptions() throws Exception {
        RuleCompiler.Program buttons = RuleCompiler.compile("features:buttons", RuleJson.parse("""
                {"rules":[
                  {"id":"one","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],"do":[{"op":"value","value":true}],"ui":{"label":"One"}},
                  {"id":"two","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],"do":[{"op":"value","value":true}],"ui":{"label":"Two"}}
                ]}"""), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:buttons","instanceId":"buttons","name":"Buttons"},
                 "actor":{},"revision":0,"event":{"type":"action.request"}}
                """);
        RuleDispatcher.EventResolution buttonsResult = RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(buttons, context)),
                List.of(new RuleRuntime.Intent("one", "use", "buttons", 0, RuleJson.JSON.createObjectNode()),
                        new RuleRuntime.Intent("two", "use", "buttons", 0, RuleJson.JSON.createObjectNode())),
                RandomGenerator.getDefault());
        assertEquals(2, buttonsResult.actions().size());

        RuleCompiler.Program options = RuleCompiler.compile("features:exclusive", RuleJson.parse("""
                {"rules":[
                  {"id":"one","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option","exclusiveGroup":"result"}],"do":[{"op":"value","value":true}],"ui":{"label":"One"}},
                  {"id":"two","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option","exclusiveGroup":"result"}],"do":[{"op":"value","value":true}],"ui":{"label":"Two"}}
                ]}"""), fixture("rewrite/recipes"));
        ObjectNode exclusiveContext = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:exclusive","instanceId":"exclusive","name":"Exclusive"},
                 "actor":{},"revision":0,"event":{"type":"roll.prepare"}}
                """);
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(options, exclusiveContext)),
                List.of(new RuleRuntime.Intent("one", "use", "exclusive", 0, RuleJson.JSON.createObjectNode()),
                        new RuleRuntime.Intent("two", "use", "exclusive", 0, RuleJson.JSON.createObjectNode())),
                RandomGenerator.getDefault()));
    }

    private static RuleCompiler.Program optionProgram(String sourceId) throws Exception {
        return RuleCompiler.compile(sourceId, RuleJson.parse("""
                {"rules":[{"id":"spend","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"option"}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                 "do":[{"op":"value","value":true}],"ui":{"label":"Spend"}}]}
                """), fixture("rewrite/recipes"));
    }

    @Test
    void publishedRollsAndDamageChainBetweenSelectedOptions() throws Exception {
        RuleCompiler.Program options = RuleCompiler.compile("features:chain", RuleJson.parse("""
                {"rules":[
                  {"id":"a","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],"ui":{"label":"A"},
                   "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"contribution":{"id":"a","kind":"flat","role":"modifier","label":"A","value":2},"as":"r"},
                         {"op":"roll.publish","roll":{"read":"result.r"}}]},
                  {"id":"b","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],"ui":{"label":"B"},
                   "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"contribution":{"id":"b","kind":"flat","role":"modifier","label":"B","value":3},"as":"r"},
                         {"op":"roll.publish","roll":{"read":"result.r"}}]}
                ]}"""), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:chain","instanceId":"chain","name":"Chain"},"actor":{},"revision":0}
                """);
        context.putObject("event").put("type", "roll.prepare").set("roll", StructuredRoll.validate(RuleJson.parse("""
                {"id":"check","contributions":[{"id":"base","kind":"flat","role":"modifier","sourceId":"core","label":"Base","value":1}]}
                """)));
        RuleDispatcher.EventResolution result = RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(options, context)),
                List.of(new RuleRuntime.Intent("a", "use", "chain", 0, RuleJson.JSON.createObjectNode()),
                        new RuleRuntime.Intent("b", "use", "chain", 0, RuleJson.JSON.createObjectNode())),
                RandomGenerator.getDefault());
        assertEquals(6, result.actions().get(1).publishedRolls().getFirst().path("total").asInt());
        assertEquals(6, result.contexts().getFirst().path("event").path("roll").path("total").asInt());
    }
}
