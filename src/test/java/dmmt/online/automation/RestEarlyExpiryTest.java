package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RestEarlyExpiryTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(String... rests) throws Exception {
        return packWithLock(null, rests);
    }

    private static AutomationHost.Pack packWithLock(JsonNode lock, String... rests) throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ObjectNode automation = ruleset.withObject("/automation");
        ObjectNode effect = automation.withObject("/effects").putObject("locked")
                .put("label", "Lock").put("stacking", "stack").put("restExpiry", "beforeMoves");
        var until = effect.putArray("until");
        for (String rest : rests) until.add(rest);
        ObjectNode locks = effect.putObject("locks");
        if (lock == null) locks.putObject("hp").put("read", "actor.hope.value");
        else locks.set("hp", lock);
        automation.putObject("limits").putObject("once").put("max", 1)
                .putArray("resetOn").add("rest.short").add("rest.long");
        automation.putObject("pools").putObject("charge").put("kind", "number").put("scope", "actor")
                .put("max", 4).put("whileEffect", "locked");
        automation.withArray("rules").addAll((com.fasterxml.jackson.databind.node.ArrayNode) RuleJson.parse("""
                [
                 {"id":"start","limitRef":"once",
                  "bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.mark","track":"actor.hp","amount":2},
                        {"op":"effect.start","effect":"locked","target":"actor"}],"ui":{"label":"Start"}},
                 {"id":"spawn","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"effect.start","effect":"locked","target":"actor"}],"ui":{"label":"Spawn"}},
                 {"id":"heal","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.hp","amount":10,"as":"clear"}],
                  "ui":{"label":"Heal","report":"Cleared {result.clear} HP."}}
                ]
                """));
        return AutomationHost.compile("early", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"permanentHp":1,"hope":{"slots":6,"on":2},
                 "hp":{"slots":6,"on":4},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0}}
                """);
        ObjectNode state = HOST.projection().state(sheet);
        state.putArray("effects").addObject().put("id", "ordinary")
                .putArray("until").add("rest.short").add("rest.long");
        state.putObject("pools").putObject("charge").put("value", 3).put("max", 4);
        return sheet;
    }

    private static ObjectNode request(String id, long revision, String rule) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", id).put("expectedRevision", revision);
        request.putArray("selections").addObject().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", "sheet");
        return request;
    }

    private static ObjectNode origin() { return AutomationHost.event("action.request", "ada", 0); }

    private static ObjectNode rest(String kind) { return AutomationHost.event("rest." + kind, "ada", 0); }

    private static void start(AutomationHost.Pack pack, ObjectNode sheet, String id, String rule) {
        HOST.execute(pack, sheet, origin(), request(id, HOST.revision(sheet), rule), "self", StructuredRollTest.noRandom());
    }

    private static ObjectNode prepare(AutomationHost.Pack pack, ObjectNode sheet, String kind, Set<String> initial) {
        return HOST.prepareRest(pack, sheet, origin(), kind, HOST.revision(sheet),
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom(), initial);
    }

    @Test
    void legacyRestLocksEndBeforeDowntimeLikeEarlyExpiryLocks() throws Exception {
        var pack = pack("rest.short", "rest.long");
        ObjectNode sheet = sheet();
        sheet.withObject("/hp").put("on", 4);
        sheet.putArray("effects").addObject().put("id", "legacy-lock")
                .put("lockedHp", 1).put("until", "rest");
        sheet.withArray("/effects").addObject().put("id", "kept").put("lockedHp", 1).put("until", "manual");
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        long revision = HOST.revision(sheet);
        assertEquals("restExpiry", assertThrows(RuleRuntime.Unavailable.class,
                () -> prepare(pack, sheet.deepCopy(), "short", initial)).code());
        assertTrue(HOST.expireRestBeforeMoves(pack, sheet, rest("short"), initial));
        assertEquals(List.of("kept"), sheet.path("effects").findValuesAsText("id"));
        assertEquals(revision + 1, HOST.revision(sheet));
        assertFalse(HOST.expireRestBeforeMoves(pack, sheet, rest("short"), initial));
        ObjectNode prepared = prepare(pack, sheet, "short", initial);
        assertEquals("short", HOST.restProgress(pack, prepared).kind());
    }


    @Test
    void initialLocksEndBeforeHealingWithoutRefreshingUsesOrOtherEffectsEarly() throws Exception {
        var pack = pack("rest.short", "rest.long");
        ObjectNode sheet = sheet();
        start(pack, sheet, "start", "start");
        assertEquals(6, sheet.at("/hp/on").asInt());
        assertEquals(2, sheet.at("/automationState/effects/1/locks/hp").asInt());
        assertEquals("beforeMoves", sheet.at("/automationState/effects/1/restExpiry").asText());
        sheet.withObject("/hope").put("on", 1);
        assertEquals(2, sheet.at("/automationState/effects/1/locks/hp").asInt(), "A started lock is frozen.");
        ObjectNode healed = sheet.deepCopy();
        ObjectNode result = HOST.execute(pack, healed, origin(), request("heal", HOST.revision(healed), "heal"),
                "self", StructuredRollTest.noRandom());
        assertEquals(3, healed.at("/hp/on").asInt());
        assertEquals("Cleared 3 HP.", result.at("/actions/0/report").asText());
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        ObjectNode before = sheet.deepCopy();
        assertEquals("restExpiry", assertThrows(RuleRuntime.Unavailable.class,
                () -> prepare(pack, sheet, "long", initial)).code());
        assertEquals(before, sheet);

        assertTrue(HOST.expireRestBeforeMoves(pack, sheet, rest("long"), initial));
        assertEquals(List.of("ordinary"), sheet.at("/automationState/effects").findValuesAsText("id"));
        assertEquals(0, sheet.at("/automationState/pools/charge/value").asInt());
        assertEquals(1, sheet.at("/automationState/sources/ruleset:base/usage/once").asInt());
        ObjectNode prepared = prepare(pack, sheet, "long", initial);
        var progress = HOST.restProgress(pack, prepared);
        ObjectNode selection = RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", "rest-tend-all").put("bindingId", "move");
        var move = HOST.restMove(pack, sheet, origin(), progress, selection,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals(1, sheet.at("/hp/on").asInt());
        assertTrue(move.response().at("/actions/0/report").asText().contains("cleared 5 HP"));
        HOST.completeRest(pack, sheet, origin(), move.progress(), initial,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
        assertEquals(0, sheet.at("/automationState/sources/ruleset:base/usage/once").asInt());
    }

    @Test
    void laterLocksSurviveTheCurrentRestAndKeepTheirSharedPool() throws Exception {
        var pack = pack("rest.short", "rest.long");
        ObjectNode sheet = sheet();
        start(pack, sheet, "start", "start");
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        start(pack, sheet, "spawn", "spawn");
        String laterId = sheet.at("/automationState/effects/2/id").asText();
        assertFalse(initial.contains(laterId));
        assertTrue(HOST.expireRestBeforeMoves(pack, sheet, rest("long"), initial));
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
        assertTrue(sheet.at("/automationState/effects").findValuesAsText("id").contains(laterId));
        ObjectNode prepared = prepare(pack, sheet, "long", initial);
        HOST.completeRest(pack, sheet, origin(), HOST.restProgress(pack, prepared), initial,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals(List.of(laterId), sheet.at("/automationState/effects").findValuesAsText("id"));
        assertEquals(3, sheet.at("/automationState/pools/charge/value").asInt());
    }

    @Test
    void earlyExpiryHonorsTheSpecificRestEvent() throws Exception {
        var pack = pack("rest.long");
        ObjectNode sheet = sheet();
        start(pack, sheet, "start", "start");
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        assertFalse(HOST.expireRestBeforeMoves(pack, sheet, rest("short"), initial));
        ObjectNode prepared = prepare(pack, sheet, "short", initial);
        HOST.completeRest(pack, sheet, origin(), HOST.restProgress(pack, prepared), initial,
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals(1, sheet.at("/automationState/effects").size());
        assertEquals("Lock", sheet.at("/automationState/effects/0/label").asText());
        Set<String> next = HOST.restEffectSnapshot(sheet);
        assertTrue(HOST.expireRestBeforeMoves(pack, sheet, rest("long"), next));
        assertTrue(sheet.at("/automationState/effects").isEmpty());
    }

    @Test
    void malformedOrUnresolvedEffectsFailWithoutPartialChanges() throws Exception {
        var pack = pack("rest.long");
        ObjectNode sheet = sheet();
        start(pack, sheet, "start", "start");
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        ((ObjectNode) sheet.at("/automationState/effects/1/locks")).put("hp", -1);
        ObjectNode before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.restEffectSnapshot(sheet));
        assertThrows(IllegalArgumentException.class, () -> HOST.expireRestBeforeMoves(pack, sheet, rest("long"), initial));
        assertEquals(before, sheet);

        ((ObjectNode) sheet.at("/automationState/effects/1/locks")).put("hp", 1);
        ((ObjectNode) sheet.at("/automationState/effects/1")).put("until", "rest.long");
        before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> HOST.expireRestBeforeMoves(pack, sheet, rest("long"), initial));
        assertEquals(before, sheet);

        ((ObjectNode) sheet.at("/automationState/effects/1")).putArray("until").add("rest.long");
        ((ObjectNode) sheet.at("/automationState/effects/1")).put("effect", "missing#lock");
        before = sheet.deepCopy();
        var unresolved = assertThrows(IllegalArgumentException.class,
                () -> HOST.expireRestBeforeMoves(pack, sheet, rest("long"), initial));
        assertTrue(unresolved.getMessage().contains("missing#lock"));
        assertEquals(before, sheet);
        assertThrows(IllegalArgumentException.class, () -> HOST.expireRestBeforeMoves(pack, sheet,
                AutomationHost.event("scene.end", "ada", 0), initial));
        assertEquals(before, sheet);
    }

    @Test
    void invalidFrozenLockValuesAndStoredNamesLeaveTheSheetUnchanged() throws Exception {
        for (String value : List.of("-1", "1.5", "1001")) {
            var pack = packWithLock(RuleJson.parse(value), "rest.long");
            ObjectNode sheet = sheet(), before = sheet.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> start(pack, sheet, "start", "start"), value);
            assertEquals(before, sheet);
        }

        var pack = pack("rest.long");
        ObjectNode sheet = sheet();
        start(pack, sheet, "start", "start");
        Set<String> initial = HOST.restEffectSnapshot(sheet);
        for (String name : List.of("hp.extra", "hp-extra", "hp:extra", "1hp", "a".repeat(65))) {
            ObjectNode malformed = sheet.deepCopy();
            ObjectNode locks = (ObjectNode) malformed.at("/automationState/effects/1/locks");
            locks.remove("hp");
            locks.put(name, 1);
            ObjectNode before = malformed.deepCopy();
            assertThrows(IllegalArgumentException.class, () -> HOST.restEffectSnapshot(malformed), name);
            assertThrows(IllegalArgumentException.class,
                    () -> HOST.expireRestBeforeMoves(pack, malformed, rest("long"), initial), name);
            assertEquals(before, malformed);
        }
    }

    @Test
    void compilerRejectsInvalidLocksAndEarlyExpiryDeclarations() throws Exception {
        JsonNode recipes = RuleJson.parse(LegacyCatalogConverter.coreRecipes());
        for (String fields : List.of(
                "\"locks\":{}",
                "\"locks\":{\"bad name\":1}",
                "\"locks\":{\"hp.extra\":1}",
                "\"locks\":{\"hp-extra\":1}",
                "\"locks\":{\"hp:extra\":1}",
                "\"locks\":{\"1hp\":1}",
                "\"locks\":{\"hp\":{\"read\":\"event.type\"}}",
                "\"locks\":{\"hp\":{\"read\":\"state.missing\"}}",
                "\"locks\":{\"hp\":1},\"while\":true",
                "\"restExpiry\":\"afterReset\",\"until\":[\"rest.long\"]",
                "\"restExpiry\":\"beforeMoves\"",
                "\"restExpiry\":\"beforeMoves\",\"until\":[\"scene.end\"]",
                "\"restExpiry\":\"beforeMoves\",\"until\":[\"rest.long\"],\"while\":true")) {
            JsonNode automation = RuleJson.parse("{\"effects\":{\"locked\":{\"label\":\"Lock\"," + fields + "}}}");
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("features:test", automation, recipes), fields);
        }
    }
}
