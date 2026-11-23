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
        return pack(bonus, longMove, false);
    }

    private static AutomationHost.Pack pack(int bonus, boolean longMove, boolean narrative) throws Exception {
        ObjectNode ruleset = narrative ? narrativeRuleset() : (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
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

    private static ObjectNode narrativeRuleset() throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        for (JsonNode definition : ruleset.at("/automation/rules")) {
            String id = definition.path("id").asText();
            if (!List.of("rest-tend-ally", "rest-repair-ally", "rest-tend-all-ally", "rest-repair-all-ally").contains(id))
                continue;
            ObjectNode rule = (ObjectNode) definition;
            ObjectNode binding = (ObjectNode) rule.at("/bindings/0");
            binding.remove("targetSelection");
            ((ObjectNode) binding.get("restMove")).put("narrative", true);
            if (id.contains("-all-")) {
                rule.remove("do");
                ((ObjectNode) rule.get("ui")).put("report", id.contains("tend")
                        ? "{actor.name} tends to all wounds on an ally; apply the recovery to the ally's sheet."
                        : "{actor.name} repairs all Armor Slots on an ally; apply the recovery to the ally's sheet.");
            } else {
                ObjectNode value = (ObjectNode) rule.at("/do/1");
                JsonNode amount = value.path("amount").path("max").get(1).deepCopy();
                value.remove(List.of("track", "amount"));
                value.put("op", "value").put("as", "recovery").set("value", amount);
                ((ArrayNode) rule.get("do")).remove(2);
                ((ObjectNode) rule.get("ui")).put("report", id.contains("tend")
                        ? "{actor.name} tends to an ally: clear {result.recovery} HP (1d4: {result.roll}, tier: {actor.stats.tier}); apply it to the ally's sheet."
                        : "{actor.name} repairs an ally's armor: clear {result.recovery} Armor Slots (1d4: {result.roll}, tier: {actor.stats.tier}); apply it to the ally's sheet.");
            }
        }
        return ruleset;
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

    private record TargetedRest(ObjectNode source, ObjectNode recipient, ObjectNode origin,
                                AutomationHost.RestRoster roster, PartyTargets party) {}

    private static TargetedRest targetedRest(AutomationHost.Pack pack) throws Exception {
        ObjectNode source = sheet(5), recipient = sheet(1).put("name", "Mira");
        ObjectNode origin = AutomationHost.event("action.request", "ada", 0).put("sessionId", "session");
        String sourceVersion = source.toString(), recipientVersion = recipient.toString();
        var roster = new AutomationHost.RestRoster(sourceVersion, scope -> {
            assertEquals("activeCharacters", scope);
            return List.of(
                    new AutomationHost.RestMember("ada", "Ada", sourceVersion, 0, () -> source),
                    new AutomationHost.RestMember("mira", "Mira", recipientVersion, 0, () -> recipient));
        });
        var party = new PartyTargets(pack.revision(), sourceVersion, scope -> {
            assertEquals("activeCharacters", scope);
            return List.of(
                    new PartyTargets.Member("ada", "Ada", sourceVersion, 0,
                            event -> HOST.recipientSnapshot(pack, source, event, "ada")),
                    new PartyTargets.Member("mira", "Mira", recipientVersion, 0,
                            event -> HOST.recipientSnapshot(pack, recipient, event, "mira")));
        });
        return new TargetedRest(source, recipient, origin, roster, party);
    }

    private static ObjectNode targetedRequest(AutomationHost.Pack pack, TargetedRest rest, String kind, String rule) {
        ObjectNode offer = HOST.describeRest(pack, rest.source(), rest.origin(), kind, "owner",
                rest.source().toString(), rest.party());
        JsonNode descriptor = null;
        for (JsonNode move : offer.path("moves"))
            if (rule.equals(move.path("ruleId").asText())) descriptor = move;
        assertNotNull(descriptor, offer.toString());
        JsonNode options = descriptor.at("/targetSelection/options");
        assertEquals(List.of("mira"), options.findValuesAsText("id"));
        ObjectNode request = restRequest(kind, rule);
        ((ObjectNode) request.at("/moves/0")).putObject("target").put("recipientId", "mira")
                .set("version", options.get(0).get("version"));
        return request;
    }

    @Test
    void bundledAllyMovesRecoverTheSelectedRecipientAndRespectTheirHealingFloor() throws Exception {
        for (String rule : List.of("rest-tend-ally", "rest-repair-ally", "rest-tend-all-ally", "rest-repair-all-ally")) {
            var pack = pack(2, false);
            TargetedRest rest = targetedRest(pack);
            String kind = rule.contains("-all-") ? "long" : "short";
            ObjectNode beforeSource = rest.source().deepCopy(), beforeRecipient = rest.recipient().deepCopy();
            ObjectNode request = targetedRequest(pack, rest, kind, rule);
            var transaction = HOST.stageRest(pack, rest.source(), rest.origin(), request,
                    "short".equals(kind) ? StructuredRollTest.faces(2) : StructuredRollTest.noRandom(), rest.roster());
            ObjectNode saved = transaction.recipients().get("mira");
            assertNotNull(saved, rule);
            assertEquals(beforeRecipient, rest.recipient(), "Recipient changes remain staged until the host commits.");
            assertEquals(beforeSource.path("hp"), rest.source().path("hp"));
            assertEquals(beforeSource.path("armorTrack"), rest.source().path("armorTrack"));
            if (rule.contains("tend")) {
                assertEquals(3, saved.at("/hp/on").asInt(), "Permanent and locked HP belong to the recipient.");
                assertEquals(3, saved.at("/armorTrack/on").asInt());
            } else {
                assertEquals(6, saved.at("/hp/on").asInt());
                assertEquals(0, saved.at("/armorTrack/on").asInt());
            }
            String report = transaction.response().path("actions").findValuesAsText("report").getLast();
            assertTrue(report.contains("3 " + (rule.contains("tend") ? "HP" : "Armor Slots")), report);
            assertTrue(report.contains("Mira"), report);
            assertEquals(List.of("mira"), transaction.scopeMembership().get("activeCharacters"));
        }
    }

    @Test
    void bundledAllyLongMoveUsesTheSameShortRestAllowanceAsItsSelfVariant() throws Exception {
        var denied = pack(0, false);
        TargetedRest unavailable = targetedRest(denied);
        ObjectNode before = unavailable.source().deepCopy();
        ObjectNode request = targetedRequest(denied, unavailable, "short", "rest-tend-all-ally");
        assertEquals("longMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRest(denied, unavailable.source(), unavailable.origin(), request,
                        StructuredRollTest.noRandom(), unavailable.roster())).code());
        assertEquals(before, unavailable.source());
        var allowed = pack(0, true);
        TargetedRest rest = targetedRest(allowed);
        var result = HOST.stageRest(allowed, rest.source(), rest.origin(),
                targetedRequest(allowed, rest, "short", "rest-tend-all-ally"),
                StructuredRollTest.noRandom(), rest.roster());
        assertEquals(1, result.response().at("/progress/longMovesUsed").asInt());
        assertEquals(3, result.recipients().get("mira").at("/hp/on").asInt());
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
        ObjectNode ruleset = narrativeRuleset();
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
        var pack = pack(0, false, true);
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
        var allowed = pack(0, true, true);
        ObjectNode sheet = sheet(1);
        var result = HOST.stageRest(allowed, sheet, AutomationHost.event("action.request", "ada", 0),
                restRequest("short", "rest-tend-all-ally"), StructuredRollTest.noRandom(), null);
        assertEquals(1, result.response().at("/progress/longMovesUsed").intValue());
    }

    @Test
    void matchesLegacyNarrativeAllyRestFixture() throws Exception {
        JsonNode fixture = fixture("parity/narrative-ally-rest");
        for (JsonNode sample : fixture.path("cases")) {
            ObjectNode ruleset = narrativeRuleset();
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
