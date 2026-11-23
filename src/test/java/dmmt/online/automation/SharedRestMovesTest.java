package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class SharedRestMovesTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack(int bonus, boolean longMove) throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ArrayNode rules = ruleset.withObject("/automation").withArray("rules");
        if (bonus != 0) rules.add(RuleJson.parse("""
                {"id":"test-bonus","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":%d,"label":"Bonus"}],
                 "ui":{"label":"Bonus"}}
                """.formatted(bonus)));
        if (longMove) rules.add(RuleJson.parse("""
                {"id":"test-long","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                 "do":[{"op":"plan.adjust","plan":"event.rest","field":"longMoves","by":1,"label":"Long move"}],
                 "ui":{"label":"Long move"}}
                """));
        return AutomationHost.compile("shared-rest", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet(int level) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":%d,"hope":{"slots":6,"on":5},"hp":{"slots":8,"on":6},
                 "permanentHp":2,"effects":[{"name":"Locked","lockedHp":1}],
                 "stress":{"slots":6,"on":6},"armorTrack":{"slots":4,"on":3}}
                """.formatted(level));
    }

    private static ObjectNode select(String rule) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", "move");
    }

    private static ObjectNode prepare(AutomationHost.Pack pack, ObjectNode sheet, String kind) {
        return HOST.prepareRest(pack, sheet, AutomationHost.event("action.request", "ada", 0).put("sessionId", "session"),
                kind, 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
    }

    private static AutomationHost.RestMoveResult move(AutomationHost.Pack pack, ObjectNode sheet, ObjectNode prepared,
                                                      AutomationHost.RestProgress progress, String rule, RandomGenerator random) {
        return HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, select(rule),
                RuleJson.JSON.createArrayNode(), random);
    }

    private static ObjectNode restRequest(String kind, String... moves) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", kind).put("expectedRevision", 0);
        request.putArray("preparation");
        ArrayNode selected = request.putArray("moves");
        for (String rule : moves) {
            ObjectNode move = selected.addObject();
            move.set("selection", select(rule));
            move.putArray("options");
        }
        request.putArray("completion");
        return request;
    }

    @Test
    void shortRecoveryUsesTierBonusActualClearAndProtectedHp() throws Exception {
        for (int[] entry : List.of(new int[]{1, 1}, new int[]{2, 2}, new int[]{5, 3}, new int[]{8, 4})) {
            var pack = pack(0, false);
            ObjectNode sheet = sheet(entry[0]);
            ((ObjectNode) sheet.get("stress")).put("on", 6);
            ObjectNode prepared = prepare(pack, sheet, "short");
            var result = move(pack, sheet, prepared, HOST.restProgress(pack, prepared),
                    "rest-stress", StructuredRollTest.faces(1));
            assertEquals(6 - (1 + entry[1]), sheet.at("/stress/on").asInt());
            assertEquals(1, result.response().at("/actions/0/diceRolls/0/faces/0").asInt());
            assertTrue(result.response().at("/actions/0/report").asText().contains("cleared " + (1 + entry[1]) + " Stress"));
        }

        var boosted = pack(2, false);
        ObjectNode sheet = sheet(5);
        ObjectNode prepared = prepare(boosted, sheet, "short");
        var healed = move(boosted, sheet, prepared, HOST.restProgress(boosted, prepared),
                "rest-tend", StructuredRollTest.faces(4));
        assertEquals(3, sheet.at("/hp/on").asInt());
        assertEquals(2, healed.response().at("/event/move/bonus").asInt());
        assertTrue(healed.response().at("/actions/1/report").asText().contains("cleared 3 HP"));

        var negative = pack(-8, false);
        ObjectNode armor = sheet(1);
        ObjectNode armorRest = prepare(negative, armor, "short");
        var repaired = move(negative, armor, armorRest, HOST.restProgress(negative, armorRest),
                "rest-repair", StructuredRollTest.faces(1));
        assertEquals(3, armor.at("/armorTrack/on").asInt());
        assertTrue(repaired.response().at("/actions/1/report").asText().contains("repaired 0 Armor Slots"));
    }

    @Test
    void preparationVariantsShareOneSlotAndCapHope() throws Exception {
        var pack = pack(0, false);
        ObjectNode sheet = sheet(1);
        ObjectNode prepared = prepare(pack, sheet, "short");
        var group = move(pack, sheet, prepared, HOST.restProgress(pack, prepared),
                "rest-prepare-group", StructuredRollTest.noRandom());
        assertEquals(6, sheet.at("/hope/on").asInt());
        assertTrue(group.response().at("/actions/0/report").asText().contains("gained 1 Hope"));
        ObjectNode before = sheet.deepCopy();
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(pack, sheet, prepared, group.progress(), "rest-prepare", StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);

        ObjectNode alone = sheet(1);
        ((ObjectNode) alone.get("hope")).put("on", 2);
        ObjectNode longRest = prepare(pack, alone, "long");
        move(pack, alone, longRest, HOST.restProgress(pack, longRest), "rest-prepare", StructuredRollTest.noRandom());
        assertEquals(3, alone.at("/hope/on").asInt());
    }

    @Test
    void longMovesClearRecoverableMarksAndRequireAnAllowanceDuringShortRests() throws Exception {
        var pack = pack(0, false);
        for (String rule : List.of("rest-tend-all", "rest-stress-all", "rest-repair-all", "rest-project")) {
            ObjectNode sheet = sheet(1);
            ObjectNode prepared = prepare(pack, sheet, "long");
            var result = move(pack, sheet, prepared, HOST.restProgress(pack, prepared), rule, StructuredRollTest.noRandom());
            assertEquals(1, result.progress().slotsUsed());
            switch (rule) {
                case "rest-tend-all" -> {
                    assertEquals(3, sheet.at("/hp/on").asInt());
                    assertTrue(result.response().at("/actions/0/report").asText().contains("cleared 3 HP"));
                }
                case "rest-stress-all" -> assertEquals(0, sheet.at("/stress/on").asInt());
                case "rest-repair-all" -> assertEquals(0, sheet.at("/armorTrack/on").asInt());
                case "rest-project" -> assertTrue(result.response().at("/actions/0/report").asText().contains("project"));
            }
        }
        ObjectNode shortSheet = sheet(1);
        ObjectNode shortRest = prepare(pack, shortSheet, "short");
        ObjectNode before = shortSheet.deepCopy();
        assertEquals("longMove", assertThrows(RuleRuntime.Unavailable.class, () -> move(pack, shortSheet, shortRest,
                HOST.restProgress(pack, shortRest), "rest-tend-all", StructuredRollTest.noRandom())).code());
        assertEquals(before, shortSheet);

        var allowed = pack(0, true);
        ObjectNode allowedSheet = sheet(1);
        ObjectNode allowedRest = prepare(allowed, allowedSheet, "short");
        var used = move(allowed, allowedSheet, allowedRest, HOST.restProgress(allowed, allowedRest),
                "rest-tend-all", StructuredRollTest.noRandom());
        assertEquals(3, allowedSheet.at("/hp/on").asInt());
        assertEquals(1, used.progress().longMovesUsed());
        assertEquals("longMove", assertThrows(RuleRuntime.Unavailable.class, () -> move(allowed, allowedSheet, allowedRest,
                used.progress(), "rest-stress-all", StructuredRollTest.noRandom())).code());
        ObjectNode longSheet = sheet(1);
        ObjectNode longRest = prepare(pack, longSheet, "long");
        assertEquals("move", assertThrows(RuleRuntime.Unavailable.class, () -> move(pack, longSheet, longRest,
                HOST.restProgress(pack, longRest), "rest-tend", StructuredRollTest.noRandom())).code());
    }

    @Test
    void allyShortRecoveryIsReportedWithoutChangingAnyRecipient() throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                {"id":"ally-bonus","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "when":{"eq":[{"read":"event.move.ally"},true]},
                 "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":2,"label":"Ally bonus"}],
                 "ui":{"label":"Ally bonus"}}
                """));
        var pack = AutomationHost.compile("narrative-ally", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        for (String rule : List.of("rest-tend-ally", "rest-repair-ally")) {
            ObjectNode sheet = sheet(5);
            ObjectNode beforeHp = sheet.withObject("/hp").deepCopy(), beforeArmor = sheet.withObject("/armorTrack").deepCopy();
            var result = HOST.stageRest(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                    restRequest("short", rule), StructuredRollTest.faces(2), null);
            assertTrue(result.recipients().isEmpty());
            assertTrue(result.readVersions().isEmpty());
            assertEquals(beforeHp, sheet.path("hp"));
            assertEquals(beforeArmor, sheet.path("armorTrack"));
            assertTrue(result.response().at("/moveEvents/0/move/ally").booleanValue());
            assertEquals(2, result.response().at("/moveEvents/0/move/bonus").intValue());
            assertEquals(2, result.response().at("/actions/1/diceRolls/0/faces/0").intValue());
            String report = result.response().at("/actions/1/report").asText();
            assertTrue(report.contains("clear 7 " + (rule.contains("tend") ? "HP" : "Armor Slots")), report);
            assertTrue(report.contains("apply it to the ally's sheet"), report);
        }

        ObjectNode duplicate = sheet(5), before = duplicate.deepCopy();
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRest(pack, duplicate, AutomationHost.event("action.request", "ada", 0),
                        restRequest("short", "rest-tend-ally", "rest-tend"), StructuredRollTest.faces(2), null)).code());
        assertEquals(before, duplicate);

        ObjectNode forged = sheet(5), original = forged.deepCopy(), request = restRequest("short", "rest-tend-ally");
        ((ObjectNode) request.at("/moves/0")).putObject("target").put("recipientId", "other").put("version", "forged");
        var roster = new AutomationHost.RestRoster(forged.toString(), scope -> List.of());
        assertThrows(IllegalArgumentException.class, () -> HOST.stageRest(pack, forged,
                AutomationHost.event("action.request", "ada", 0), request, StructuredRollTest.noRandom(), roster));
        assertEquals(original, forged);
    }

    @Test
    void allyLongRecoveryIsNarrativeAndUsesTheSameRestAllowanceAsSelfRecovery() throws Exception {
        var pack = pack(0, false);
        for (String rule : List.of("rest-tend-all-ally", "rest-repair-all-ally")) {
            ObjectNode sheet = sheet(1), hp = sheet.withObject("/hp").deepCopy(), armor = sheet.withObject("/armorTrack").deepCopy();
            var result = HOST.stageRest(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                    restRequest("long", rule), StructuredRollTest.noRandom(), null);
            assertEquals(hp, sheet.path("hp"));
            assertEquals(armor, sheet.path("armorTrack"));
            assertTrue(result.recipients().isEmpty());
            assertTrue(result.response().at("/moveEvents/0/move/ally").booleanValue());
            assertTrue(result.response().at("/actions/0/report").asText().contains("apply the recovery to the ally's sheet"));
            ObjectNode shortSheet = sheet(1), before = shortSheet.deepCopy();
            assertEquals("longMove", assertThrows(RuleRuntime.Unavailable.class,
                    () -> HOST.stageRest(pack, shortSheet, AutomationHost.event("action.request", "ada", 0),
                            restRequest("short", rule), StructuredRollTest.noRandom(), null)).code());
            assertEquals(before, shortSheet);
        }
        var allowed = pack(0, true);
        ObjectNode sheet = sheet(1);
        var result = HOST.stageRest(allowed, sheet, AutomationHost.event("action.request", "ada", 0),
                restRequest("short", "rest-tend-all-ally"), StructuredRollTest.noRandom(), null);
        assertEquals(1, result.response().at("/progress/longMovesUsed").intValue());
    }

    @Test
    void matchesLegacyNarrativeAllyRestFixture() throws Exception {
        JsonNode fixture = fixture("parity/narrative-ally-rest");
        for (JsonNode sample : fixture.path("cases")) {
            ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
            ruleset.withObject("/automation").withArray("rules").add(RuleJson.parse("""
                    {"id":"ally-bonus","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                     "when":{"eq":[{"read":"event.move.ally"},true]},
                     "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":%d,"label":"Ally bonus"}],
                     "ui":{"label":"Ally bonus"}}
                    """.formatted(sample.path("bonus").asInt())));
            var pack = AutomationHost.compile("narrative-parity", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
            ObjectNode sheet = sheet(fixture.path("level").asInt());
            sheet.withObject("/hp").put("on", fixture.path("markedHp").asInt());
            sheet.withObject("/armorTrack").put("on", fixture.path("markedArmor").asInt());
            int hp = sheet.at("/hp/on").asInt(), armor = sheet.at("/armorTrack/on").asInt();
            var result = HOST.stageRest(pack, sheet, AutomationHost.event("action.request", "ada", 0),
                    restRequest(sample.path("kind").asText(), sample.path("rule").asText()),
                    sample.has("face") ? StructuredRollTest.faces(sample.path("face").asInt()) : StructuredRollTest.noRandom(), null);
            assertEquals(hp, sheet.at("/hp/on").asInt(), sample.toString());
            assertEquals(armor, sheet.at("/armorTrack/on").asInt(), sample.toString());
            assertTrue(result.recipients().isEmpty(), sample.toString());
            assertTrue(result.readVersions().isEmpty(), sample.toString());
            String report = result.response().at("/actions/1/report").asText();
            if (sample.has("amount"))
                assertTrue(report.contains("clear " + sample.path("amount").asInt() + " " + sample.path("unit").asText()), report);
            else assertTrue(report.contains("apply the recovery to the ally's sheet"), report);
        }
    }
}
