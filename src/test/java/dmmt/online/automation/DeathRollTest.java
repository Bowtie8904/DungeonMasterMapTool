package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class DeathRollTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(String rules) throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        JsonNode parsed = RuleJson.parse(rules);
        if (parsed.isEmpty()) automation.remove("rules");
        else automation.set("rules", parsed);
        return AutomationHost.compile("death-test", Map.of("classes", classes.toString()),
                fixture("rewrite/recipes").toString(), LegacyCatalogConverter.coreRuleset());
    }

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":2,"hp":{"slots":6,"total":12,"on":6},"permanentHp":1,
                 "stress":{"slots":6,"total":12,"on":4},"armorTrack":{"slots":3,"total":12,"on":0},
                 "hope":{"slots":6,"total":6,"on":3},"proficiency":{"slots":6,"total":6,"on":1},
                 "traits":[{"name":"Agility","mod":1}],"experiences":[{"name":"Expert","mod":2}],
                 "features":[{"name":"Steady","automationSource":"classes:tester:features:steady","instanceId":"steady"}],
                 "effects":[{"name":"Locked","lockedHp":1}],"inventory":[],"activeWeapons":[]}
                """);
    }

    private static ObjectNode event() {
        return AutomationHost.event("action.request", "hero", 0).put("sessionId", "session");
    }

    private static ObjectNode rollRequest(String id, long revision) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections");
        request.putObject("deathRoll").put("move", "risk");
        return request;
    }

    private static ObjectNode resultRequest(String id, long revision, String resultId, String source, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision)
                .put("resultId", resultId);
        request.putArray("selections").addObject().put("sourceInstanceId", source).put("ruleId", rule).put("bindingId", "claim");
        return request;
    }

    @Test
    void riskRollUsesTrustedOptionsAndAtomicBoundedRecovery() throws Exception {
        var pack = pack("""
                [{"id":"bonus","bindings":[{"id":"auto","event":"roll.prepare","surface":"roll","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.check.kind"},"death"]},
                  "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"as":"edited",
                    "contribution":{"id":"bonus","kind":"flat","role":"modifier","label":"Bonus","value":2}},
                    {"op":"roll.publish","roll":{"read":"result.edited"}}],"ui":{}},
                 {"id":"paid","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],
                  "when":{"and":[{"eq":[{"read":"event.check.kind"},"death"]},{"eq":[{"read":"event.check.move"},"risk"]}]},
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"as":"edited",
                    "contribution":{"id":"paid","kind":"flat","role":"modifier","label":"Paid","value":2}},
                    {"op":"roll.publish","roll":{"read":"result.edited"}}],"ui":{"label":"Paid"}},
                 {"id":"after","bindings":[{"id":"auto","event":"roll.finished","surface":"roll","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.check.kind"},"death"]},
                    {"eq":[{"read":"event.check.outcome"},"success"]}]},
                  "do":[{"op":"track.mark","track":"actor.stress","amount":1}],"ui":{}}]
                """);
        ObjectNode sheet = sheet(), original = sheet.deepCopy();
        JsonNode options = HOST.describeDeath(pack, sheet, event(), "self").requiredAt("/rolls/risk/options");
        assertEquals(java.util.List.of("paid"), options.findValuesAsText("ruleId"));
        assertEquals(original, sheet);
        assertThrows(SecurityException.class, () -> HOST.describeDeath(pack, sheet, event(), "other"));

        ObjectNode roll = rollRequest("roll", 0);
        roll.withArray("selections").addObject().put("sourceInstanceId", "steady").put("ruleId", "paid").put("bindingId", "use");
        ObjectNode receipt = HOST.execute(pack, sheet, event(), roll, "self", "owner", StructuredRollTest.faces(2, 5));
        assertEquals("success", receipt.requiredAt("/event/check/outcome").asText());
        assertEquals("hope", receipt.requiredAt("/event/check/dominant").asText());
        assertEquals(6, receipt.requiredAt("/event/check/adjustedHope").asInt());
        assertEquals(4, receipt.requiredAt("/event/check/recoverableHp").asInt());
        assertEquals(2, sheet.at("/hope/on").asInt());
        assertEquals(5, sheet.at("/stress/on").asInt());
        String resultId = receipt.requiredAt("/event/resultId").asText();
        assertEquals(resultId, sheet.at("/pendingDeathRoll/id").asText());
        assertEquals(receipt, HOST.execute(pack, sheet, event(), roll, "self", "owner", StructuredRollTest.noRandom()));
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, event(), rollRequest("again", 1),
                "self", "owner", StructuredRollTest.noRandom()));

        ObjectNode tooMuch = resultRequest("bad", 1, resultId, "ruleset:base", "death-recover");
        ((ObjectNode) tooMuch.at("/selections/0")).putObject("inputs").put("hp", 4).put("stress", 5);
        ObjectNode beforeBad = sheet.deepCopy();
        assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, event(), tooMuch, "self", "owner", StructuredRollTest.noRandom()));
        assertEquals(beforeBad, sheet);

        ObjectNode recover = resultRequest("recover", 1, resultId, "ruleset:base", "death-recover");
        ((ObjectNode) recover.at("/selections/0")).putObject("inputs").put("hp", 3).put("stress", 3);
        ObjectNode recovered = HOST.execute(pack, sheet, event(), recover, "self", "owner", StructuredRollTest.noRandom());
        assertEquals(3, sheet.at("/hp/on").asInt());
        assertEquals(2, sheet.at("/stress/on").asInt());
        assertFalse(sheet.has("pendingDeathRoll"));
        assertEquals(recovered, HOST.execute(pack, sheet, event(), recover, "self", "owner", StructuredRollTest.noRandom()));
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, event(),
                resultRequest("stale", 2, resultId, "ruleset:base", "death-recover"), "self", "owner", StructuredRollTest.noRandom()));
    }

    @Test
    void criticalUsesRawDiceAndClearsOnlyRecoverableMarks() throws Exception {
        var pack = pack("""
                [{"id":"bonus","bindings":[{"id":"auto","event":"roll.prepare","surface":"roll","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.check.kind"},"death"]},
                  "do":[{"op":"roll.contribute","from":{"read":"event.roll"},"as":"edited",
                    "contribution":{"id":"bonus","kind":"flat","role":"modifier","label":"Bonus","value":-3}},
                    {"op":"roll.publish","roll":{"read":"result.edited"}}],"ui":{}}]""");
        ObjectNode sheet = sheet();
        ObjectNode rolled = HOST.execute(pack, sheet, event(), rollRequest("critical", 0),
                "self", "owner", StructuredRollTest.faces(4, 4));
        assertTrue(rolled.at("/event/check/critical").asBoolean());
        assertEquals(1, rolled.at("/event/check/adjustedHope").asInt());
        assertEquals("success", rolled.at("/event/check/outcome").asText());
        String id = rolled.requiredAt("/event/resultId").asText();
        HOST.execute(pack, sheet, event(), resultRequest("recover", 1, id, "ruleset:base", "death-recover-critical"),
                "self", "owner", StructuredRollTest.noRandom());
        assertEquals(2, sheet.at("/hp/on").asInt());
        assertEquals(0, sheet.at("/stress/on").asInt());
    }

    @Test
    void failedRiskCanReviseToHopeOnceAndInvalidateTheOldWindow() throws Exception {
        var pack = pack("""
                [{"id":"undying","bindings":[{"id":"claim","event":"roll.finished","surface":"roll","mode":"button",
                  "claimGroup":"death-outcome","audience":"self","claimScope":{"recipients":"sourceActor"}}],
                  "when":{"and":[{"eq":[{"read":"event.check.kind"},"death"]},{"eq":[{"read":"event.check.move"},"risk"]},
                    {"eq":[{"read":"event.check.dominant"},"fear"]},{"ne":[{"read":"event.check.critical"},true]}]},
                  "do":[{"op":"track.capacity","track":"actor.hp","by":-1},
                    {"op":"roll.annotate","from":{"read":"event.roll"},"field":"deathOutcome","value":"hope","as":"roll"},
                    {"op":"roll.publish","roll":{"read":"result.roll"}}],"ui":{"label":"Undying"}}]""");
        ObjectNode sheet = sheet();
        ObjectNode failed = HOST.execute(pack, sheet, event(), rollRequest("failure", 0),
                "self", "owner", StructuredRollTest.faces(2, 8));
        assertEquals("failure", failed.at("/event/check/outcome").asText());
        String oldId = failed.requiredAt("/event/resultId").asText();
        ObjectNode use = resultRequest("undying", 1, oldId, "steady", "undying");
        ObjectNode revised = HOST.execute(pack, sheet, event(), use, "self", "owner", StructuredRollTest.noRandom());
        assertEquals("success", revised.at("/event/check/outcome").asText());
        assertEquals("hope", revised.at("/event/check/dominant").asText());
        assertEquals(5, sheet.at("/hp/slots").asInt());
        String newId = revised.requiredAt("/event/resultId").asText();
        assertNotEquals(oldId, newId);
        assertEquals(newId, sheet.at("/pendingDeathRoll/id").asText());
        assertEquals(revised, HOST.execute(pack, sheet, event(), use, "self", "owner", StructuredRollTest.noRandom()));
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, event(),
                resultRequest("old", 2, oldId, "steady", "undying"), "self", "owner", StructuredRollTest.noRandom()));
        ObjectNode recover = resultRequest("recover", 2, newId, "ruleset:base", "death-recover");
        ((ObjectNode) recover.at("/selections/0")).putObject("inputs").put("hp", 2).put("stress", 0);
        HOST.execute(pack, sheet, event(), recover, "self", "owner", StructuredRollTest.noRandom());
        assertEquals(3, sheet.at("/hp/on").asInt());
    }

    @Test
    void finalHitPointRevisionIsRejectedWithoutCommitting() throws Exception {
        var pack = pack("""
                [{"id":"undying","bindings":[{"id":"claim","event":"roll.finished","surface":"roll","mode":"button",
                  "claimGroup":"death-outcome","audience":"self","claimScope":{"recipients":"sourceActor"}}],
                  "when":{"and":[{"eq":[{"read":"event.check.kind"},"death"]},{"eq":[{"read":"event.check.dominant"},"fear"]}]},
                  "do":[{"op":"track.capacity","track":"actor.hp","by":-1},
                    {"op":"roll.annotate","from":{"read":"event.roll"},"field":"deathOutcome","value":"hope","as":"roll"},
                    {"op":"roll.publish","roll":{"read":"result.roll"}}],"ui":{"label":"Undying"}}]""");
        ObjectNode sheet = sheet();
        sheet.withObject("/hp").put("slots", 2).put("on", 2);
        ObjectNode failed = HOST.execute(pack, sheet, event(), rollRequest("failure", 0),
                "self", "owner", StructuredRollTest.faces(2, 8));
        ObjectNode before = sheet.deepCopy();
        var error = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, event(),
                resultRequest("undying", 1, failed.at("/event/resultId").asText(), "steady", "undying"),
                "self", "owner", StructuredRollTest.noRandom()));
        assertTrue(error.getMessage().contains("last recoverable Hit Point"));
        assertEquals(before, sheet);
    }

    @Test
    void blockedDeathRollRollsBackItsCostsAndReactions() throws Exception {
        var pack = pack("""
                [{"id":"block","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],
                  "when":{"eq":[{"read":"event.check.kind"},"death"]},
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"event.block","reason":"Death roll blocked."}],"ui":{"label":"Block"}}]""");
        ObjectNode sheet = sheet(), before = sheet.deepCopy();
        ObjectNode request = rollRequest("blocked", 0);
        request.withArray("selections").addObject().put("sourceInstanceId", "steady").put("ruleId", "block").put("bindingId", "use");
        var error = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet, event(), request,
                "self", "owner", StructuredRollTest.faces(2, 8)));
        assertTrue(error.getMessage().contains("Death roll blocked."));
        assertEquals(before, sheet);
    }

    @Test
    void avoidDeathScarsBlocksMovementAndEndsOnHealingWithReactions() throws Exception {
        var pack = pack("""
                [{"id":"heal","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.hp","amount":1}],"ui":{"label":"Heal"}},
                 {"id":"increase","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.capacity","track":"actor.hp","by":1}],"ui":{"label":"Increase HP"}},
                 {"id":"wake","bindings":[{"id":"auto","event":"effect.ended","surface":"effect","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.effect.label"},"Unconscious"]},
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"report":"Awake."}}]""");
        ObjectNode sheet = sheet();
        ObjectNode avoid = rollRequest("avoid", 0);
        avoid.withObject("/deathRoll").put("move", "avoid");
        ObjectNode result = HOST.execute(pack, sheet, event(), avoid, "self", "owner", StructuredRollTest.faces(1));
        assertEquals("avoid", result.at("/event/check/move").asText());
        assertEquals(1, result.at("/event/check/hopeDie").asInt());
        assertTrue(result.at("/event/check/fearDie").isNull());
        assertEquals(1, sheet.path("scars").asInt());
        assertEquals(5, sheet.at("/hope/slots").asInt());
        JsonNode effect = sheet.at("/automationState/effects/0");
        assertEquals("Unconscious", effect.path("label").asText());
        assertTrue(effect.path("blocksMovement").asBoolean());
        assertTrue(effect.path("blocksDeathMoves").asBoolean());
        assertEquals(result, HOST.execute(pack, sheet, event(), avoid, "self", "owner", StructuredRollTest.noRandom()));
        assertThrows(RuleRuntime.Unavailable.class, () -> HOST.describeDeath(pack, sheet, event(), "self"));
        ObjectNode increase = RuleJson.JSON.createObjectNode().put("requestId", "increase").put("expectedRevision", 1);
        increase.putArray("selections").addObject().put("sourceInstanceId", "steady").put("ruleId", "increase").put("bindingId", "use");
        HOST.execute(pack, sheet, event(), increase, "self", "owner", StructuredRollTest.noRandom());
        assertEquals(7, sheet.at("/hp/slots").asInt());
        assertEquals(6, sheet.at("/hp/on").asInt());
        assertEquals(1, sheet.at("/automationState/effects").size());
        ObjectNode heal = RuleJson.JSON.createObjectNode().put("requestId", "heal").put("expectedRevision", 2);
        heal.putArray("selections").addObject().put("sourceInstanceId", "steady").put("ruleId", "heal").put("bindingId", "use");
        JsonNode healed = HOST.execute(pack, sheet, event(), heal, "self", "owner", StructuredRollTest.noRandom());
        assertEquals(5, sheet.at("/hp/on").asInt());
        assertEquals(4, sheet.at("/hope/on").asInt());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
        assertTrue(healed.path("actions").findValuesAsText("report").contains("Awake."));
    }

    @Test
    void avoidDeathWithoutScarExpiresAtLongRestAndBlazeReportsOnce() throws Exception {
        var pack = pack("[]");
        ObjectNode sheet = sheet();
        ObjectNode avoid = rollRequest("avoid", 0);
        avoid.withObject("/deathRoll").put("move", "avoid");
        HOST.execute(pack, sheet, event(), avoid, "self", "owner", StructuredRollTest.faces(10));
        assertEquals(0, sheet.path("scars").asInt());
        assertEquals(6, sheet.at("/hope/slots").asInt());
        assertEquals(1, sheet.at("/automationState/effects").size());
        var snapshot = HOST.restEffectSnapshot(sheet);
        ObjectNode preparation = HOST.prepareRest(pack, sheet, event(), "long", HOST.revision(sheet),
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        HOST.completeRest(pack, sheet, event(), HOST.restProgress(pack, preparation), snapshot,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertTrue(sheet.at("/automationState/effects").isEmpty());

        ObjectNode blazeSheet = sheet();
        ObjectNode blaze = RuleJson.JSON.createObjectNode().put("requestId", "blaze").put("expectedRevision", 0)
                .put("deathAction", true);
        blaze.putArray("selections").addObject().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", "death-blaze").put("bindingId", "choose");
        ObjectNode report = HOST.execute(pack, blazeSheet, event(), blaze, "self", "owner", StructuredRollTest.noRandom());
        assertTrue(report.at("/actions/0/report").asText().contains("one final action"));
        assertEquals(report, HOST.execute(pack, blazeSheet, event(), blaze, "self", "owner", StructuredRollTest.noRandom()));
    }
}
