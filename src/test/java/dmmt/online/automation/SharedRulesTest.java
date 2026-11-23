package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class SharedRulesTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private AutomationHost.Pack pack(ObjectNode classes) {
        return AutomationHost.compile("shared-test", Map.of("classes", classes.toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
    }

    private ObjectNode sheet(int hope, int stress) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"hope":{"slots":6,"on":%d},"stress":{"slots":6,"on":%d},
                 "proficiency":{"slots":6,"on":2},"traits":[{"name":"Finesse","mod":1}],
                 "features":[{"name":"Forage","instanceId":"f-forage","automationSource":"classes:tester:features:forage"},
                             {"name":"Steady","instanceId":"f-steady","automationSource":"classes:tester:features:steady"}]}
                """.formatted(hope, stress));
    }

    private ObjectNode origin() {
        return AutomationHost.event("action.request", "actor", 2).put("sessionId", "session");
    }

    private ObjectNode rollRequest(String id, long revision) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections");
        request.putObject("profileRoll").put("sourceInstanceId", "f-steady").put("profileId", "thrown").put("kind", "attack");
        return request;
    }

    private ObjectNode claim(String id, long revision, String resultId, String source, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision)
                .put("resultId", resultId);
        request.putArray("selections").addObject().put("sourceInstanceId", source).put("ruleId", rule).put("bindingId", "claim");
        return request;
    }

    @Test
    void baseRewardAllowsFailureButExcludesReactionDeathAndNoBenefit() throws Exception {
        var program = pack(fixture("rewrite/host-classes")).programs().get("ruleset:base");
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"ruleset:base","instanceId":"ruleset:base","name":"Rules"},
                 "actor":{"id":"actor","name":"Ada","hope":{"value":1,"max":6},"stress":{"marked":2,"capacity":6}},
                 "revision":0,"event":{"type":"roll.finished","check":{"kind":"action","duality":true,
                  "dominant":"hope","critical":false,"outcome":"failure"}}}
                """);
        assertTrue(RuleRuntime.describe(program, "hope-reward", "claim", context).path("available").asBoolean(),
                "A failed action roll with Hope still grants its base reward.");
        ObjectNode check = context.withObject("/event/check");
        check.put("kind", "reaction");
        assertFalse(RuleRuntime.describe(program, "hope-reward", "claim", context).path("available").asBoolean());
        check.put("kind", "death").put("critical", true);
        assertFalse(RuleRuntime.describe(program, "critical-reward", "claim", context).path("available").asBoolean());
        check.put("kind", "action").put("critical", false).put("dominant", "fear");
        assertFalse(RuleRuntime.describe(program, "hope-reward", "claim", context).path("available").asBoolean());
        check.put("dominant", "hope").put("duality", false);
        assertFalse(RuleRuntime.describe(program, "hope-reward", "claim", context).path("available").asBoolean());
        check.put("duality", true);
        context.withObject("/actor/hope").put("value", 6);
        assertFalse(RuleRuntime.describe(program, "hope-reward", "claim", context).path("available").asBoolean());
        check.put("critical", true);
        assertTrue(RuleRuntime.describe(program, "critical-reward", "claim", context).path("available").asBoolean());
        context.withObject("/actor/stress").put("marked", 0);
        assertFalse(RuleRuntime.describe(program, "critical-reward", "claim", context).path("available").asBoolean());
    }

    @Test
    void baseRewardsPersistCapsAndRejectDuplicateClaims() throws Exception {
        var pack = pack(fixture("rewrite/host-classes"));
        for (int[] values : List.of(new int[]{1, 2, 8, 3, 2, 2}, new int[]{6, 2, 5, 5, 6, 1},
                new int[]{1, 0, 5, 5, 2, 0})) {
            ObjectNode sheet = sheet(values[0], values[1]);
            ObjectNode result = HOST.execute(pack, sheet, origin(), rollRequest("roll", 0), "self", "owner",
                    StructuredRollTest.faces(values[2], values[3]));
            String rule = values[2] == values[3] ? "critical-reward" : "hope-reward";
            assertEquals(List.of(rule), result.required("resultActions").findValuesAsText("ruleId"));
            String resultId = result.requiredAt("/event/resultId").textValue();
            ObjectNode request = claim("reward", 1, resultId, "ruleset:base", rule);
            ObjectNode receipt = HOST.execute(pack, sheet, origin(), request, "self", "owner", null);
            assertEquals(receipt, HOST.execute(pack, sheet, origin(), request, "self", "owner", null));
            assertEquals(values[4], sheet.requiredAt("/hope/on").intValue());
            assertEquals(values[5], sheet.requiredAt("/stress/on").intValue());
            assertThrows(RuleRuntime.Unavailable.class,
                    () -> HOST.execute(pack, sheet, origin(), claim("again", 2, resultId, "ruleset:base", rule), "self", "owner", null));
        }
        for (int[] values : List.of(new int[]{6, 0, 5, 5}, new int[]{6, 2, 8, 3}, new int[]{1, 2, 2, 9})) {
            ObjectNode result = HOST.execute(pack, sheet(values[0], values[1]), origin(), rollRequest("roll", 0),
                    "self", "owner", StructuredRollTest.faces(values[2], values[3]));
            assertFalse(result.has("resultActions"));
        }
    }

    @Test
    void convertedCriticalAlternativeAndBaseAreExclusiveAcrossRevisions() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","entries":[
                 {"id":"brawler","name":"Brawler","automation":{"criticalMelee":{"hope":1,"stress":1}}}]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        ((ObjectNode) classes.at("/entries/0/features/1")).set("automation", converted.catalog().at("/entries/0/automation"));
        ObjectNode steady = (ObjectNode) classes.at("/entries/0/features/2/automation");
        ((ObjectNode) steady.at("/profiles/0")).put("range", "melee");
        steady.withArray("rules").add(RuleJson.parse("""
                {"id":"revise","bindings":[{"id":"use","event":"roll.finished","surface":"roll","mode":"button"}],
                 "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"as":"edited",
                   "contribution":{"id":"bonus","kind":"flat","role":"modifier","label":"Bonus","value":2}},
                   {"op":"roll.publish","roll":{"read":"result.edited"}}],"ui":{"label":"Revise"}}
                """));
        var pack = pack(classes);
        for (boolean chooseBase : List.of(true, false)) {
            ObjectNode sheet = sheet(1, 3);
            ObjectNode result = HOST.execute(pack, sheet, origin(), rollRequest("roll", 0), "self", "owner",
                    StructuredRollTest.faces(5, 5));
            assertEquals(List.of("critical-melee-reward", "revise", "critical-reward"),
                    result.required("resultActions").findValuesAsText("ruleId"));
            String resultId = result.requiredAt("/event/resultId").textValue();
            String source = chooseBase ? "ruleset:base" : "f-forage";
            String selected = chooseBase ? "critical-reward" : "critical-melee-reward";
            ObjectNode request = claim("reward", 1, resultId, source, selected);
            HOST.execute(pack, sheet, origin(), request, "self", "owner", null);
            assertEquals(chooseBase ? 2 : 3, sheet.requiredAt("/hope/on").intValue());
            assertEquals(chooseBase ? 2 : 1, sheet.requiredAt("/stress/on").intValue());
            assertEquals(List.of("revise"), HOST.describeResult(pack, sheet, resultId, "self", "owner", origin())
                    .required("actions").findValuesAsText("ruleId"));
            ObjectNode revise = RuleJson.JSON.createObjectNode().put("requestId", "revise").put("expectedRevision", 2)
                    .put("resultId", resultId);
            revise.putArray("selections").addObject().put("sourceInstanceId", "f-steady").put("ruleId", "revise").put("bindingId", "use");
            ObjectNode revised = HOST.execute(pack, sheet, origin(), revise, "self", "owner", null);
            assertFalse(revised.has("resultActions"), "A revision must not revive the spent group.");
            assertEquals(revised, HOST.execute(pack, sheet, origin(), revise, "self", "owner", null));
        }
        ObjectNode full = sheet(6, 0);
        ObjectNode result = HOST.execute(pack, full, origin(), rollRequest("full", 0), "self", "owner",
                StructuredRollTest.faces(5, 5));
        assertEquals(List.of("revise"), result.required("resultActions").findValuesAsText("ruleId"),
                "The converted alternative must not consume a no-op critical reward.");
    }
}
