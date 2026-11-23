package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompanionRestRecoveryTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(boolean markAfter) throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"classes","entries":[
                 {"id":"pet","name":"Companion","automation":{"companion":{
                   "evasion":10,"stress":3,"maxStress":6,"options":[]}}}]}
                """);
        assertEquals(1, converted.converted(), converted.diagnostics().toString());
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        if (markAfter) ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                {"id":"after-stress","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "when":{"eq":[{"read":"event.move.id"},"stress"]},
                 "after":{"do":[{"op":"track.mark","track":"actor.stress","amount":1}]},
                 "ui":{"label":"After Stress"}}
                """));
        return AutomationHost.compile("companion", Map.of("classes", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet(boolean unavailable) throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"hp":{"slots":6,"on":1},"stress":{"slots":6,"on":5},
                 "armorTrack":{"slots":3,"on":0},"hope":{"slots":6,"on":1},
                 "companion":{"name":"Fox","stress":{"slots":4,"on":3},"unavailable":false},
                 "features":[{"name":"Companion","instanceId":"pet","automationSource":"classes:pet"}],
                 "automationState":{"pools":{"companion-stress-slots":{"max":6,"value":6}}}}
                """);
        sheet.withObject("/companion").put("unavailable", unavailable);
        return sheet;
    }

    private static ObjectNode rest(AutomationHost.Pack pack, ObjectNode sheet, String kind, String rule, int... faces) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", kind).put("expectedRevision", 0);
        request.putArray("preparation");
        ObjectNode move = request.putArray("moves").addObject();
        move.putObject("selection").put("sourceInstanceId", "ruleset:base").put("ruleId", rule).put("bindingId", "move");
        move.putArray("options");
        request.putArray("completion");
        return HOST.stageSelfRest(pack, sheet,
                AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                request, faces.length == 0 ? StructuredRollTest.noRandom() : StructuredRollTest.faces(faces));
    }

    @Test
    void companionRecoveryUsesNetOwnerStressClearedAfterDeferredBenefits() throws Exception {
        ObjectNode sheet = sheet(false);
        ObjectNode result = rest(pack(true), sheet, "short", "rest-stress", 1);
        assertEquals(4, sheet.at("/stress/on").asInt(), "Clear two, then mark one.");
        assertEquals(2, sheet.at("/companion/stress/on").asInt(), "Recover only the net one Stress.");
        assertEquals(4, sheet.at("/automationState/pools/companion-stress-slots/max").asInt());
        assertEquals(2, sheet.at("/automationState/pools/companion-stress-slots/value").asInt());
        assertTrue(result.path("actions").findValuesAsText("ruleId").contains("companion-recovers"));
    }

    @Test
    void unavailableCompanionsDoNotRecoverOnShortRestsAndReturnBeforeLongRestMoves() throws Exception {
        ObjectNode shortSheet = sheet(true);
        rest(pack(false), shortSheet, "short", "rest-stress", 1);
        assertEquals(3, shortSheet.at("/companion/stress/on").asInt());
        assertTrue(shortSheet.at("/companion/unavailable").asBoolean());

        ObjectNode longSheet = sheet(true);
        ObjectNode result = rest(pack(false), longSheet, "long", "rest-stress-all");
        assertFalse(longSheet.at("/companion/unavailable").asBoolean());
        assertEquals(0, longSheet.at("/companion/stress/on").asInt());
        assertEquals(0, longSheet.at("/stress/on").asInt());
        var rules = result.path("actions").findValuesAsText("ruleId");
        assertTrue(rules.indexOf("companion-returns") < rules.indexOf("rest-stress-all"), rules.toString());
        assertTrue(rules.indexOf("rest-stress-all") < rules.indexOf("companion-recovers"), rules.toString());
    }
}
