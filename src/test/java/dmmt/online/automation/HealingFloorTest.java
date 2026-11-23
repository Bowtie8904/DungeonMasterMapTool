package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class HealingFloorTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","permanentHp":1,"hp":{"slots":6,"total":6,"on":6},
                 "hope":{"slots":6,"on":2},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                 "effects":[{"lockedHp":2},{"lockedHp":-4}],
                 "features":[{"name":"Steady","instanceId":"steady","automationSource":"classes:tester:features:steady"}]}
                """);
    }

    private static AutomationHost.Pack pack() throws Exception {
        ObjectNode classes = fixture("rewrite/host-classes");
        ObjectNode automation = (ObjectNode) classes.at("/entries/0/features/2/automation");
        automation.putObject("effects").putObject("locked").put("label", "Lock");
        automation.withArray("rules").addAll((com.fasterxml.jackson.databind.node.ArrayNode) RuleJson.parse("""
                [
                 {"id":"heal","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.hp","amount":10,"as":"clear"}],
                  "ui":{"label":"Heal","report":"Cleared {result.clear} HP."}},
                 {"id":"end-and-heal","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"effect.end","effect":"locked","target":"actor"},
                        {"op":"track.clear","track":"actor.hp","amount":10,"as":"clear"}],
                  "ui":{"label":"End and heal","report":"Cleared {result.clear} HP."}},
                 {"id":"shrink","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.capacity","track":"actor.hp","by":-6}],"ui":{"label":"Shrink"}}
                ]
                """));
        return AutomationHost.compile("floor", Map.of("classes", classes.toString()), fixture("rewrite/recipes").toString());
    }

    private static void engineLock(ObjectNode sheet) {
        HOST.projection().state(sheet).putArray("effects").addObject().put("id", "lock")
                .put("effect", "classes:tester:features:steady#locked").put("sourceInstanceId", "steady")
                .put("sourceActorId", "ada").put("lockedHp", 1);
    }

    private static ObjectNode request(String id, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", 0);
        request.putArray("selections").addObject().put("sourceInstanceId", "steady").put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    @Test
    void healingClampsToPermanentAndTemporaryLocksAndReportsTheActualAmount() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        engineLock(sheet);
        ObjectNode response = HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("heal", "heal"), "self", StructuredRollTest.noRandom());
        assertEquals(4, sheet.at("/hp/on").asInt());
        assertEquals("Cleared 2 HP.", response.at("/actions/0/report").asText());
        assertEquals(1, sheet.path("permanentHp").asInt());
        assertEquals(1, sheet.at("/automationState/effects").size());
    }

    @Test
    void endingAnEngineLockBeforeHealingRecalculatesTheFloorWithinTheSameAction() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        engineLock(sheet);
        ObjectNode response = HOST.execute(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                request("end", "end-and-heal"), "self", StructuredRollTest.noRandom());
        assertEquals(3, sheet.at("/hp/on").asInt());
        assertEquals("Cleared 3 HP.", response.at("/actions/0/report").asText());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
    }

    @Test
    void permanentCapacityAndMalformedLocksFailWithoutChangingTheSheet() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        engineLock(sheet);
        ObjectNode before = sheet.deepCopy();
        var error = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.execute(pack, sheet,
                AutomationHost.event("action.request", "ada", 0), request("shrink", "shrink"), "self", StructuredRollTest.noRandom()));
        assertEquals("capacity", error.code());
        assertEquals(before, sheet);

        ((ObjectNode) sheet.at("/effects/0")).put("lockedHp", "bad");
        before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                AutomationHost.event("action.request", "ada", 0), request("heal", "heal"), "self", StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
        sheet.put("permanentHp", 7);
        before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.execute(pack, sheet,
                AutomationHost.event("action.request", "ada", 0), request("heal", "heal"), "self", StructuredRollTest.noRandom()));
        assertEquals(before, sheet);
    }

    @Test
    void targetedHealingUsesTheRecipientsOwnProtectedFloor() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"heal","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button",
                 "targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":true}}],
                 "do":[{"op":"track.clear","track":"recipient.hp","amount":10,"as":"clear"}],
                 "ui":{"label":"Heal","report":"Cleared {result.clear} HP."}}]}
                """);
        var program = RuleCompiler.compile("features:heal", automation, fixture("rewrite/recipes"));
        ObjectNode sourceSheet = sheet(), recipientSheet = sheet().put("name", "Mira").put("permanentHp", 2);
        sourceSheet.remove("effects");
        ObjectNode sourceActor = HOST.projection().actor(sourceSheet).put("id", "ada");
        ObjectNode recipientActor = HOST.projection().actor(recipientSheet).put("id", "mira");
        ObjectNode context = RuleJson.JSON.createObjectNode().put("revision", 0);
        context.putObject("source").put("id", "features:heal").put("instanceId", "healer").put("name", "Healer");
        context.set("actor", sourceActor);
        context.set("event", AutomationHost.event("action.request", "ada", 0));
        var candidate = new RuleDispatcher.Candidate(program, context);
        var intent = new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("heal", "sheet", "healer", 0,
                RuleJson.JSON.createObjectNode()), "mira", 0L);
        var result = RuleDispatcher.executeTargets(List.of(candidate), intent,
                List.of(new RuleDispatcher.RecipientCandidate("mira", recipientActor, 0)), StructuredRollTest.noRandom());
        assertEquals(4, result.recipients().getFirst().snapshot().at("/hp/marked").asInt());
        assertEquals("Cleared 2 HP.", result.actions().getFirst().action().report());
        HOST.projection().write(recipientSheet, recipientActor, result.recipients().getFirst().snapshot());
        assertEquals(4, recipientSheet.at("/hp/on").asInt());
        assertEquals(6, sourceSheet.at("/hp/on").asInt());
    }

    @Test
    void projectionRejectsUnknownLiveLockCollections() throws Exception {
        String text;
        try (var input = HealingFloorTest.class.getResourceAsStream("/dmmt/online/automation/daggerheart-projection.json")) {
            assertNotNull(input);
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ObjectNode projection = (ObjectNode) RuleJson.parse(text);
        ((ObjectNode) projection.at("/actor/hp/markedFloor/actorSum/0")).put("path", "missing");
        assertThrows(IllegalArgumentException.class, () -> SheetProjection.parse(projection.toString()));
    }
}
