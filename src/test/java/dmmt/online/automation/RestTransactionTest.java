package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestTransactionTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static AutomationHost.Pack pack() {
        String ruleset = """
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Rest rules",
                 "rest":{"startFacts":{"hitPoints":"hp.marked","stress":"stress.marked","hope":"hope.value"},
                         "short":{"moves":2,"maxMoves":2,"maxLongMoves":0,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":2}},
                 "automation":{
                   "limits":{"once":{"max":1,"resetOn":["rest.short","rest.long"]}},
                   "effects":{
                     "old":{"label":"Old","locks":{"hp":2},"restExpiry":"beforeMoves",
                            "until":["rest.short","rest.long"]},
                     "shelter":{"label":"Shelter","until":["rest.short","rest.long"]},
                     "new":{"label":"New","until":["rest.short","rest.long"]}
                   },
                   "rules":[
                     {"id":"prepare","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"shelter","target":"actor"},
                            {"op":"countdown.start","value":2,"label":"Prepared"}],
                      "ui":{"label":"Prepare","report":"Prepared."}},
                     {"id":"mend","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"mend","kind":"any"}}],
                      "do":[{"op":"dice.roll","count":1,"sides":4,"as":"roll"},
                            {"op":"track.clear","track":"actor.hp","amount":{"add":[{"read":"result.roll"},1]},"as":"clear"},
                            {"op":"countdown.start","value":2,"label":"Move"}],
                      "ui":{"label":"Mend","report":"Cleared {result.clear} HP."}},
                     {"id":"ready","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"ready","kind":"any"}}],
                      "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"Ready"}},
                     {"id":"ally","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                       "restMove":{"id":"ally","kind":"any","ally":true}}],"ui":{"label":"Ally"}},
                     {"id":"before","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                      "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                      "do":[{"op":"effect.start","effect":"new","target":"actor"},
                            {"op":"countdown.start","value":2,"label":"Finished"}],
                      "ui":{"label":"Before","report":"Finished."}},
                     {"id":"after","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option",
                       "restPhase":"afterReset"}],
                      "when":{"eq":[{"read":"event.rest.cleared.hitPoints"},0]},
                      "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{"label":"After"}}
                   ]}}
                """;
        return AutomationHost.compile("transaction", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset);
    }

    private static ObjectNode sheet() throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","shortRestsInRow":1,"longRestRevision":4,"permanentHp":1,
                 "hope":{"slots":6,"on":3},"hp":{"slots":6,"on":6},
                 "stress":{"slots":6,"on":2},"armorTrack":{"slots":3,"on":0}}
                """);
        ObjectNode state = HOST.projection().state(sheet).put("revision", 9);
        state.withObject("/sources").putObject("ruleset:base").putObject("usage").put("once", 1);
        ObjectNode old = state.putArray("effects").addObject().put("id", "old").put("label", "Old")
                .put("effect", "ruleset:base#old").put("sourceInstanceId", "ruleset:base").put("sourceActorId", "ada")
                .put("restExpiry", "beforeMoves");
        old.putArray("until").add("rest.short").add("rest.long");
        old.putObject("locks").put("hp", 2);
        return sheet;
    }

    private static ObjectNode origin() {
        return AutomationHost.event("action.request", "ada", 1).put("sessionId", "session");
    }

    private static ObjectNode choice(String rule, String binding) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", binding);
    }

    private static ObjectNode request(String kind) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", kind).put("expectedRevision", 9);
        request.putArray("preparation");
        request.putArray("moves");
        request.putArray("completion");
        return request;
    }

    private static void move(ObjectNode request, String rule) {
        ObjectNode move = request.withArray("moves").addObject();
        move.set("selection", choice(rule, "move"));
        move.putArray("options");
    }

    @Test
    void completeSequencePreservesOrderingSnapshotsAndOneRevision() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), request = request("short");
        request.withArray("preparation").add(choice("prepare", "rest"));
        move(request, "mend");
        move(request, "ready");
        request.withArray("completion").add(choice("before", "short")).add(choice("after", "short"));
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), request, StructuredRollTest.faces(4));

        assertEquals(10, HOST.revision(sheet));
        assertEquals(10, result.path("revision").asLong());
        assertEquals(1, sheet.at("/hp/on").asInt());
        assertEquals(2, sheet.at("/hope/on").asInt());
        assertEquals(2, sheet.path("shortRestsInRow").asInt());
        assertEquals(4, sheet.path("longRestRevision").asInt());
        assertEquals(0, sheet.at("/automationState/sources/ruleset:base/usage/once").asInt());
        assertEquals(List.of("Shelter", "New"), sheet.at("/automationState/effects").findValuesAsText("label"));
        assertEquals(List.of("prepare", "mend", "ready", "before"), result.path("actions").findValuesAsText("ruleId"));
        assertEquals(List.of("Prepared", "Move", "Finished"), result.path("outbox").findValuesAsText("label"));
        assertEquals("Cleared 5 HP.", result.at("/actions/1/report").asText());
        assertEquals(4, result.at("/actions/1/diceRolls/0/faces/0").asInt());
        assertEquals(6, result.at("/preparationEvent/rest/start/hitPoints").asInt());
        assertEquals(List.of("mend", "ready"), result.path("moveEvents").findValuesAsText("id"));
        assertEquals(5, result.at("/event/rest/cleared/hitPoints").asInt());
        assertEquals("afterReset", result.at("/event/rest/phase").asText());
        assertEquals("after", result.at("/skipped/0/ruleId").asText());
        assertEquals(2, result.at("/progress/slotsUsed").asInt());
        assertEquals(2, result.at("/progress/count").asInt());
    }

    @Test
    void failuresAfterPreparationAndRollingDiscardEveryStage() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), request = request("short"), before = sheet.deepCopy();
        request.withArray("preparation").add(choice("prepare", "rest"));
        move(request, "mend");
        move(request, "mend");
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request, StructuredRollTest.faces(4))).code());
        assertEquals(before, sheet);

        ObjectNode costly = request("short");
        costly.withArray("preparation").add(choice("prepare", "rest"));
        move(costly, "mend");
        costly.withArray("completion").add(choice("before", "short"));
        sheet.withObject("/hope").put("on", 1);
        before = sheet.deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), costly, StructuredRollTest.faces(4))).code());
        assertEquals(before, sheet, "A final cost failure must not retain expiry, preparation, dice or recovery.");
    }

    @Test
    void stagedReceiptReplaysOnlyTheSameOwnersExactIntentWithoutRollingAgain() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), rest = request("short").put("requestId", "rest-1");
        rest.withArray("preparation").add(choice("prepare", "rest"));
        move(rest, "mend");
        var staged = HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner",
                StructuredRollTest.faces(4), null);
        assertFalse(staged.replayed());
        assertEquals("rest-1", staged.response().path("requestId").asText());
        assertEquals(1, sheet.at("/hp/on").asInt());
        assertEquals(1, sheet.at("/automationState/journal").size());
        assertEquals(staged.response(), sheet.at("/automationState/journal/0/response"));
        ObjectNode saved = sheet.deepCopy();

        ObjectNode reordered = (ObjectNode) RuleJson.parse("""
                {"moves":[{"options":[],"selection":{"bindingId":"move","ruleId":"mend","sourceInstanceId":"ruleset:base"}}],
                 "completion":[],"preparation":[{"bindingId":"rest","ruleId":"prepare","sourceInstanceId":"ruleset:base"}],
                 "expectedRevision":9,"kind":"short","requestId":"rest-1"}
                """);
        var replay = HOST.stageRestWithReceipt(pack, sheet, origin().put("sessionId", "later-session"), reordered,
                "owner", StructuredRollTest.noRandom(), null);
        assertTrue(replay.replayed());
        assertEquals(staged.response(), replay.response());
        assertTrue(replay.recipients().isEmpty());
        assertTrue(replay.readVersions().isEmpty());
        assertEquals(saved, sheet);

        ObjectNode changed = rest.deepCopy().put("kind", "long");
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), changed, "owner",
                        StructuredRollTest.noRandom(), null)).code());
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "other",
                        StructuredRollTest.noRandom(), null)).code());
        ObjectNode action = RuleJson.JSON.createObjectNode().put("requestId", "rest-1").put("expectedRevision", 9);
        action.putArray("selections");
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.execute(pack, sheet, origin(), action, "self", "owner",
                        StructuredRollTest.noRandom())).code());
        assertEquals(saved, sheet);

        ((ObjectNode) sheet.at("/automationState/journal/0")).remove("intentHash");
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner",
                        StructuredRollTest.noRandom(), null)).code());
        ((ObjectNode) sheet.at("/automationState/journal/0")).put("dmPrincipal", true);
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner",
                        StructuredRollTest.noRandom(), null)).code());
    }

    @Test
    void authenticatedRestOfferBindsHostSnapshotAndRejectsChangesBeforeStaging() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), original = sheet.deepCopy(), event = origin();
        String version = HOST.restOfferVersion(pack, sheet, event, "short", "owner", "stored-v1");
        ObjectNode rest = request("short").put("requestId", "offered").put("offerVersion", version);
        move(rest, "mend");
        var roster = new AutomationHost.RestRoster("stored-v1", scope -> {
            fail("A stale offer must not load a roster.");
            return List.of();
        });
        var anotherPack = new AutomationHost.Pack("other-pack", pack.programs(), pack.rulesetName(), pack.rest());
        ObjectNode changedSheet = sheet.deepCopy();
        changedSheet.withObject("/automationState").put("revision", 10);
        ObjectNode changedFear = origin();
        changedFear.withObject("/table").put("fear", 2);
        List<ObjectNode> changedEvents = List.of(origin().put("sessionId", "other-session"),
                origin().put("actorId", "other-actor"), changedFear);
        for (ObjectNode changedEvent : changedEvents) {
            assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                    () -> HOST.stageOfferedRest(pack, sheet, changedEvent, rest, "owner", "stored-v1",
                            StructuredRollTest.noRandom(), roster)).code());
        }
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, rest, "owner", "stored-v2",
                        StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(anotherPack, sheet, event, rest, "owner", "stored-v1",
                        StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, changedSheet, event, rest, "owner", "stored-v1",
                        StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, rest, "other-owner", "stored-v1",
                        StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, rest.deepCopy().put("kind", "long"),
                        "owner", "stored-v1", StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, rest.deepCopy().put("offerVersion", version + "x"),
                        "owner", "stored-v1", StructuredRollTest.noRandom(), roster)).code());
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, rest, "owner", "stored-v1",
                        StructuredRollTest.noRandom(), new AutomationHost.RestRoster("other-version", scope -> {
                            fail("A mismatched roster must not be loaded.");
                            return List.of();
                        }))).code());
        ObjectNode missing = rest.deepCopy();
        missing.remove("offerVersion");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageOfferedRest(pack, sheet, event, missing,
                        "owner", "stored-v1", StructuredRollTest.noRandom(), roster));
        ObjectNode reordered = event.deepCopy();
        reordered.set("type", reordered.remove("type"));
        assertEquals(version, HOST.restOfferVersion(pack, sheet, reordered, "short", "owner", "stored-v1"));
        assertEquals(original, sheet);
    }

    @Test
    void offeredRestReplaysAfterRestartBeforeCheckingAStaleSnapshot() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), rest = request("short").put("requestId", "offered");
        rest.put("offerVersion", HOST.restOfferVersion(pack, sheet, origin(), "short", "owner", "stored-v1"));
        move(rest, "mend");
        var staged = HOST.stageOfferedRest(pack, sheet, origin(), rest, "owner", "stored-v1",
                StructuredRollTest.faces(4), null);
        assertFalse(staged.replayed());
        ObjectNode saved = sheet.deepCopy();
        AutomationHost restarted = AutomationHost.bundled();
        var replay = restarted.stageOfferedRest(pack, sheet, origin().put("sessionId", "later-session"), rest,
                "owner", "stored-v2", StructuredRollTest.noRandom(),
                new AutomationHost.RestRoster("stored-v2", scope -> {
                    fail("A retry must not load recipients.");
                    return List.of();
                }));
        assertTrue(replay.replayed());
        assertEquals(staged.response(), replay.response());
        assertTrue(replay.recipients().isEmpty());
        assertTrue(replay.readVersions().isEmpty());
        assertEquals(saved, sheet);
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> restarted.stageOfferedRest(pack, sheet, origin(), rest.deepCopy().put("offerVersion", "different"),
                        "owner", "stored-v2", StructuredRollTest.noRandom(), null)).code());
        assertEquals("requestId", assertThrows(RuleRuntime.Unavailable.class,
                () -> restarted.stageOfferedRest(pack, sheet, origin(), rest,
                        "other-owner", "stored-v2", StructuredRollTest.noRandom(), null)).code());

        ObjectNode fresh = request("short").put("requestId", "fresh")
                .put("offerVersion", HOST.restOfferVersion(pack, sheet, origin(), "short", "owner", "stored-v2"))
                .put("expectedRevision", HOST.revision(sheet));
        assertEquals("restOffer", assertThrows(RuleRuntime.Unavailable.class,
                () -> restarted.stageOfferedRest(pack, sheet, origin(), fresh,
                        "owner", "stored-v2", StructuredRollTest.noRandom(), null)).code());
        assertEquals(saved, sheet);
    }

    @Test
    void failedStagedRestDoesNotRecordAReceiptAndTheJournalStaysBounded() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), failed = request("short").put("requestId", "failed");
        move(failed, "mend");
        move(failed, "mend");
        ObjectNode before = sheet.deepCopy();
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), failed, "owner",
                        StructuredRollTest.faces(4), null)).code());
        assertEquals(before, sheet);

        var journal = sheet.withObject("/automationState").withArray("journal");
        for (int i = 0; i < AutomationHost.JOURNAL_LIMIT; i++)
            journal.addObject().put("requestId", "old-" + i).put("principalId", "owner");
        ObjectNode successful = request("short").put("requestId", "new");
        HOST.stageRestWithReceipt(pack, sheet, origin(), successful, "owner", StructuredRollTest.noRandom(), null);
        assertEquals(AutomationHost.JOURNAL_LIMIT, sheet.at("/automationState/journal").size());
        assertEquals("old-1", sheet.at("/automationState/journal/0/requestId").asText());
        assertEquals("new", sheet.at("/automationState/journal/31/requestId").asText());
    }

    @Test
    void revisionLimitStillAllowsTheLastReceiptToReplay() throws Exception {
        var pack = pack();
        long limit = 1L << 52;
        ObjectNode sheet = sheet(), rest = request("short").put("requestId", "last").put("expectedRevision", limit - 1);
        sheet.withObject("/automationState").put("revision", limit - 1);
        var last = HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner", StructuredRollTest.noRandom(), null);
        assertEquals(limit, HOST.revision(sheet));
        ObjectNode saved = sheet.deepCopy();
        var replay = HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner", StructuredRollTest.noRandom(), null);
        assertTrue(replay.replayed());
        assertEquals(last.response(), replay.response());
        ObjectNode next = request("long").put("requestId", "next").put("expectedRevision", limit);
        assertEquals("revision", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), next, "owner",
                        StructuredRollTest.noRandom(), null)).code());
        assertEquals(saved, sheet);
    }

    @Test
    void staleMalformedAndRecipientRequestsCannotMutateTheSheet() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet(), before = sheet.deepCopy();
        ObjectNode stale = request("short").put("expectedRevision", 8);
        assertEquals("stale", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), stale, StructuredRollTest.noRandom())).code());
        ObjectNode forged = request("short");
        move(forged, "ready");
        ((ObjectNode) forged.at("/moves/0")).put("slots", 0);
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), forged, StructuredRollTest.noRandom()));
        ObjectNode ally = request("short");
        move(ally, "ally");
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), ally, StructuredRollTest.noRandom())).code());
        ObjectNode missing = request("short");
        missing.remove("completion");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), missing, StructuredRollTest.noRandom()));
        assertEquals(before, sheet);

        sheet.withObject("/automationState").put("revision", "bad");
        ObjectNode malformed = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request("long"), StructuredRollTest.noRandom()));
        assertEquals(malformed, sheet);
    }

    @Test
    void commandsNeedingInterstageHostSettlementAreRejectedAtomically() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"fear","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                 "do":[{"op":"damage.deal","to":"actor","amount":1,"label":"Damage"}],"ui":{"label":"Damage"}}]}
                """);
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:fear", RuleCompiler.compile("features:fear", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("test", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Fear").put("instanceId", "fear")
                .put("automationSource", "features:fear");
        ObjectNode before = sheet.deepCopy();
        var error = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), request("short"), StructuredRollTest.noRandom()));
        assertEquals("restCommand", error.code());
        assertTrue(error.getMessage().contains("damage.deal"));
        assertEquals(before, sheet);
    }

    @Test
    void unsettledPartyRecoveryRejectsSelfMovesWithoutStagingAReceipt() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[
                  {"id":"share","bindings":[{"id":"auto","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.move.id"},"mend"]},
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Share"}],
                   "ui":{"label":"Share"}},
                  {"id":"narrative","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                    "restMove":{"id":"mend","kind":"any","ally":true,"narrative":true}}],
                   "ui":{"label":"Narrative ally"}},
                  {"id":"late","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                    "restMove":{"id":"late","kind":"any"}}],
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Late"}],
                   "ui":{"label":"Late adjustment"}}
                ]}""");
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:sharing", RuleCompiler.compile("features:sharing", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("sharing", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Sharing").put("instanceId", "sharing")
                .put("automationSource", "features:sharing");
        ObjectNode before = sheet.deepCopy(), rest = request("short").put("requestId", "unsettled");
        rest.withArray("preparation").add(choice("prepare", "rest"));
        move(rest, "mend");
        var error = assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner",
                        StructuredRollTest.noRandom(), null));
        assertEquals("restCommand", error.code());
        assertTrue(error.getMessage().contains("party recovery"));
        assertEquals(before, sheet, "Preparation, recovery and the retry receipt must all roll back.");

        ObjectNode late = request("short");
        ObjectNode lateMove = late.withArray("moves").addObject();
        lateMove.set("selection", choice("late", "move").put("sourceInstanceId", "sharing"));
        lateMove.putArray("options");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), late, StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);

        ObjectNode narrative = request("short");
        ObjectNode narrativeMove = narrative.withArray("moves").addObject();
        narrativeMove.set("selection", choice("narrative", "move").put("sourceInstanceId", "sharing"));
        narrativeMove.putArray("options");
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), narrative, StructuredRollTest.noRandom());
        assertTrue(result.at("/moveEvents/0/move/ally").asBoolean());
        assertEquals(1, result.at("/moveEvents/0/move/allies").asInt());
        assertEquals(10, HOST.revision(sheet));
    }

    @Test
    void unsettledAnnotatedRecoveryOnAnotherMoveFieldCannotBypassTheGuard() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"share","bindings":[{"id":"auto","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "when":{"eq":[{"read":"event.move.id"},"mend"]},
                 "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":1,"label":"Share",
                        "partyRecovery":{"track":"stress","scope":"activeCharacters"}},
                       {"op":"plan.adjust","plan":"event.move","field":"bonus","by":-1,"label":"Cancel"}],
                 "ui":{"label":"Share"}}]}
                """);
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:sharing", RuleCompiler.compile("features:sharing", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("sharing", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Sharing").put("instanceId", "sharing")
                .put("automationSource", "features:sharing");
        ObjectNode before = sheet.deepCopy(), rest = request("short");
        move(rest, "mend");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), rest, StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);
    }

    @Test
    void canceledOrClampedPartyRequestsRemainOrderedAndCannotBypassTheGuard() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[
                  {"id":"share","bindings":[{"id":"auto","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.move.id"},"mend"]},
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":128,"label":"First"},
                         {"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Clamped"},
                         {"op":"plan.adjust","plan":"event.move","field":"allies","by":-1000,"label":"Cancel"}],
                   "ui":{"label":"Share"}},
                  {"id":"narrative","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                    "restMove":{"id":"mend","kind":"any","ally":true,"narrative":true}}],
                   "ui":{"label":"Narrative ally"}},
                  {"id":"late","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                    "restMove":{"id":"late","kind":"any"}}],
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Late"},
                         {"op":"plan.adjust","plan":"event.move","field":"allies","by":-1,"label":"Cancel"}],
                   "ui":{"label":"Late adjustment"}}
                ]}""");
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:sharing", RuleCompiler.compile("features:sharing", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("sharing", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        for (String id : List.of("alpha", "beta")) sheet.withArray("features").addObject()
                .put("name", "Sharing").put("instanceId", id).put("automationSource", "features:sharing");
        ObjectNode before = sheet.deepCopy();
        ObjectNode rest = request("short").put("requestId", "canceled");
        rest.withArray("preparation").add(choice("prepare", "rest"));
        move(rest, "mend");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(pack, sheet, origin(), rest, "owner",
                        StructuredRollTest.noRandom(), null)).code());
        assertEquals(before, sheet, "A canceled party request must not leave preparation, a roll, or a receipt.");

        ObjectNode late = request("short");
        ObjectNode lateMove = late.withArray("moves").addObject();
        lateMove.set("selection", choice("late", "move").put("sourceInstanceId", "alpha"));
        lateMove.putArray("options");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(pack, sheet, origin(), late, StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet, "A canceled request inside the move must also roll back.");

        ObjectNode narrative = request("short");
        ObjectNode narrativeMove = narrative.withArray("moves").addObject();
        narrativeMove.set("selection", choice("narrative", "move").put("sourceInstanceId", "alpha"));
        narrativeMove.putArray("options");
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), narrative, StructuredRollTest.noRandom());
        var adjustments = result.at("/moveEvents/0/move/adjustments");
        assertEquals(6, adjustments.size());
        assertEquals(List.of("alpha", "alpha", "alpha", "beta", "beta", "beta"),
                adjustments.findValuesAsText("sourceInstanceId"));
        assertEquals(List.of("First", "Clamped", "Cancel", "First", "Clamped", "Cancel"),
                adjustments.findValuesAsText("label"));
        for (int start : List.of(0, 3)) {
            var first = adjustments.get(start);
            assertEquals(128, first.path("requested").asInt());
            assertEquals(128, first.path("by").asInt());
            assertEquals(0, first.path("before").asInt());
            assertEquals(128, first.path("after").asInt());
            assertEquals("features:sharing", first.path("sourceId").asText());
            var clamped = adjustments.get(start + 1);
            assertEquals(1, clamped.path("requested").asInt());
            assertEquals(0, clamped.path("by").asInt());
            assertEquals(128, clamped.path("before").asInt());
            assertEquals(128, clamped.path("after").asInt());
            var canceled = adjustments.get(start + 2);
            assertEquals(-1000, canceled.path("requested").asInt());
            assertEquals(-128, canceled.path("by").asInt());
            assertEquals(128, canceled.path("before").asInt());
            assertEquals(0, canceled.path("after").asInt());
        }
        assertEquals(0, result.at("/moveEvents/0/move/allies").asInt());
        assertEquals(10, HOST.revision(sheet));
    }

    @Test
    void fearCarriesThroughEveryRestStageAndLifecyclePoolReset() throws Exception {
        var base = pack();
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"pools":{"snapshot":{"kind":"number","scope":"source","max":12,"resetOn":["rest.short"],
                                      "resetTo":{"read":"event.table.fear"}}},
                 "rules":[
                  {"id":"prepare-fear","bindings":[{"id":"auto","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                   "do":[{"op":"table.gain","resource":"fear","amount":2,"label":"Prepare Fear"}],
                   "ui":{"report":"Prepare: {event.table.fear}."}},
                  {"id":"move-fear","bindings":[{"id":"auto","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "do":[{"op":"table.spend","resource":"fear","amount":1,"label":"Move Fear"}],
                   "after":{"do":[{"op":"table.gain","resource":"fear","amount":2,"label":"Deferred Fear"}],
                            "report":"Deferred: {event.table.fear}."},
                   "ui":{"report":"Move: {event.table.fear}."}},
                  {"id":"before-fear","bindings":[{"id":"auto","event":"rest.short","surface":"rest","mode":"automatic"}],
                   "do":[{"op":"table.spend","resource":"fear","amount":2,"label":"Before Fear"}],
                   "ui":{"report":"Before: {event.table.fear}."}},
                  {"id":"after-fear","bindings":[{"id":"auto","event":"rest.short","surface":"rest","mode":"automatic",
                                                  "restPhase":"afterReset"}],
                   "when":{"eq":[{"read":"event.table.fear"},3]},
                   "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"After Fear"}],
                   "ui":{"report":"After: {event.table.fear}."}}
                 ]}""");
        var programs = new java.util.HashMap<>(base.programs());
        programs.put("features:fear", RuleCompiler.compile("features:fear", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var pack = new AutomationHost.Pack("test", programs, base.rulesetName(), base.rest());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Fear").put("instanceId", "fear")
                .put("automationSource", "features:fear");
        ObjectNode before = sheet.deepCopy();
        ObjectNode rest = request("short");
        move(rest, "mend");
        move(rest, "ready");
        ObjectNode origin = origin();
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin, rest, StructuredRollTest.faces(4));

        assertEquals(1, origin.requiredAt("/table/fear").intValue());
        assertEquals(3, result.requiredAt("/preparationEvent/table/fear").intValue());
        assertEquals(4, result.requiredAt("/moveEvents/0/table/fear").intValue());
        assertEquals(5, result.requiredAt("/moveEvents/1/table/fear").intValue());
        assertEquals(4, result.requiredAt("/event/table/fear").intValue());
        assertEquals(3, sheet.requiredAt("/automationState/sources/fear/sourcePools/snapshot/value").intValue());
        assertEquals(List.of(1, 3, 2, 4, 3, 5, 3),
                result.path("outbox").findValues("before").stream().map(node -> node.intValue()).toList());
        assertEquals(List.of(3, 2, 4, 3, 5, 3, 4),
                result.path("outbox").findValues("after").stream().map(node -> node.intValue()).toList());
        assertTrue(result.path("actions").findValuesAsText("report").containsAll(
                List.of("Move: 2.", "Deferred: 4.", "Move: 3.", "Deferred: 5.", "Before: 3.", "After: 4.")));
        assertEquals(10, HOST.revision(sheet));

        ((ObjectNode) automation.at("/rules/3/do/0")).put("op", "table.spend").put("amount", 4);
        programs.put("features:fear", RuleCompiler.compile("features:fear", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        var failingPack = new AutomationHost.Pack("failure", programs, base.rulesetName(), base.rest());
        ObjectNode failingSheet = before.deepCopy();
        assertEquals("tableResource", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageSelfRest(failingPack, failingSheet, origin, rest, StructuredRollTest.faces(4))).code());
        assertEquals(before, failingSheet);
        assertEquals(1, origin.requiredAt("/table/fear").intValue());
    }

    @Test
    void longRestWithoutMovesStillCompletesAndAdvancesOneRevision() throws Exception {
        var pack = pack();
        ObjectNode sheet = sheet();
        ObjectNode result = HOST.stageSelfRest(pack, sheet, origin(), request("long"), StructuredRollTest.noRandom());
        assertEquals(10, result.path("revision").asLong());
        assertEquals(10, HOST.revision(sheet));
        assertEquals(0, sheet.path("shortRestsInRow").asInt());
        assertEquals(5, sheet.path("longRestRevision").asInt());
        assertEquals(0, result.at("/progress/count").asInt());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
    }
}
