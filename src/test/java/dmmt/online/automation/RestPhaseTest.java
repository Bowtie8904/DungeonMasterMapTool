package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestPhaseTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "automation":{
                   "effects":{
                     "before":{"label":"Before","until":["rest.short","rest.long"]},
                     "after":{"label":"After","until":["rest.short","rest.long"]}
                   },
                   "rules":[
                     {"id":"before","bindings":[
                       {"id":"short","event":"rest.short","surface":"rest","mode":"automatic"},
                       {"id":"long","event":"rest.long","surface":"rest","mode":"automatic"}],
                      "do":[{"op":"resource.gain","pool":"actor.hope","amount":1},
                            {"op":"effect.start","effect":"before","target":"actor"}],
                      "ui":{"label":"Before"}},
                     {"id":"after","bindings":[
                       {"id":"short","event":"rest.short","surface":"rest","mode":"automatic","restPhase":"afterReset"},
                       {"id":"long","event":"rest.long","surface":"rest","mode":"automatic","restPhase":"afterReset"}],
                      "do":[{"op":"track.clear","track":"actor.stress","amount":1},
                            {"op":"effect.start","effect":"after","target":"actor"}],
                      "ui":{"label":"After"}},
                     {"id":"choice-before","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "ui":{"label":"Before option"}},
                     {"id":"choice-after","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option","restPhase":"afterReset"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "ui":{"label":"After option"}}
                   ]}}
                """;
        return AutomationHost.compile("phase", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","hope":{"slots":6,"on":2},"hp":{"slots":6,"on":1},
                 "stress":{"slots":6,"on":2},"armorTrack":{"slots":3,"on":0}}
                """);
        HOST.projection().state(sheet).putArray("effects").addObject().put("id", "old").putArray("until").add("rest.short").add("rest.long");
        return sheet;
    }

    private static ObjectNode event(String type, String phase) {
        ObjectNode event = AutomationHost.event(type, "ada", 0);
        if (phase != null) event.putObject("rest").put("phase", phase);
        return event;
    }

    private static ObjectNode context(String phase) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"ruleset:base","instanceId":"ruleset:base","name":"Rules"},
                 "actor":{"id":"ada","name":"Ada","hope":{"value":2,"max":6},"stress":{"marked":2,"capacity":6},"effects":[]},
                 "revision":0,"event":{"type":"rest.short"}}
                """);
        if (phase != null) context.withObject("/event").putObject("rest").put("phase", phase);
        return context;
    }

    @Test
    void automaticRulesRunOnceAroundTheSnapshotReset() throws Exception {
        var pack = pack();
        for (String type : List.of("rest.short", "rest.long")) {
            ObjectNode sheet = sheet();
            var initial = HOST.restEffectSnapshot(sheet);
            ObjectNode before = HOST.automatic(pack, sheet, event(type, null), StructuredRollTest.noRandom());
            assertEquals(List.of("before"), before.path("actions").findValuesAsText("ruleId"));
            assertEquals(3, sheet.at("/hope/on").asInt());
            assertEquals(2, sheet.at("/stress/on").asInt());
            assertTrue(HOST.resetRest(pack, sheet, event(type, "beforeReset"), initial));
            assertFalse(sheet.at("/automationState/effects").findValuesAsText("id").contains("old"));
            ObjectNode after = HOST.automatic(pack, sheet, event(type, "afterReset"), StructuredRollTest.noRandom());
            assertEquals(List.of("after"), after.path("actions").findValuesAsText("ruleId"));
            assertEquals(3, sheet.at("/hope/on").asInt());
            assertEquals(1, sheet.at("/stress/on").asInt());
            assertEquals(List.of("Before", "After"), sheet.at("/automationState/effects").findValuesAsText("label"));
        }
    }

    @Test
    void selectedBindingsExposeAndEnforceTheirPhaseWithoutSpending() throws Exception {
        var program = pack().programs().get("ruleset:base");
        ObjectNode before = context(null);
        ObjectNode after = context("afterReset");
        assertEquals("beforeReset", RuleRuntime.describe(program, "choice-before", "short", before).path("restPhase").asText());
        assertEquals("afterReset", RuleRuntime.describe(program, "choice-after", "short", before).path("restPhase").asText());
        assertFalse(RuleRuntime.describe(program, "choice-after", "short", before).path("available").asBoolean());
        assertEquals("restPhase", RuleRuntime.describe(program, "choice-after", "short", before).path("reasonCode").asText());
        assertTrue(RuleRuntime.describe(program, "choice-after", "short", after).path("available").asBoolean());
        assertFalse(RuleRuntime.describe(program, "choice-before", "short", after).path("available").asBoolean());
        var intent = new RuleRuntime.Intent("choice-after", "short", "ruleset:base", 0, RuleJson.JSON.createObjectNode());
        ObjectNode original = before.deepCopy();
        assertEquals("restPhase", assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.execute(program, intent, before, StructuredRollTest.noRandom())).code());
        assertEquals(original, before);
        assertEquals(1, RuleRuntime.execute(program, intent, after, StructuredRollTest.noRandom())
                .context().at("/actor/hope/value").asInt());
    }

    @Test
    void malformedPhasesAreRejectedEvenWithoutAnEligibleBinding() throws Exception {
        var program = pack().programs().get("ruleset:base");
        ObjectNode malformed = context("duringReset");
        var candidate = new RuleDispatcher.Candidate(program, malformed);
        assertThrows(IllegalArgumentException.class,
                () -> RuleDispatcher.executeEvent(List.of(candidate), List.of(), StructuredRollTest.noRandom()));
        malformed.withObject("/event").put("rest", "bad");
        assertThrows(IllegalArgumentException.class,
                () -> RuleRuntime.describe(program, "choice-before", "short", malformed));
        malformed.withObject("/event").putObject("rest").putNull("phase");
        assertThrows(IllegalArgumentException.class,
                () -> RuleRuntime.describe(program, "choice-before", "short", malformed));
    }
}
