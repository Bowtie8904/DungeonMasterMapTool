package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RestTargetTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode ruleset() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-ruleset","version":2,"name":"Target rest",
                 "rest":{"startFacts":{"hope":"hope.value"},
                         "short":{"moves":2,"maxMoves":2,"maxLongMoves":0,"consecutiveLimit":3},
                         "long":{"moves":2,"maxMoves":2}},
                 "automation":{"effects":{"ward":{"label":"Ward","locks":{"hp":1}}},"rules":[
                   {"id":"observe","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                    "when":{"and":[{"eq":[{"read":"event.move.ally"},true]},{"gt":[{"read":"recipient.hp.marked"},5]}]},
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":2,"label":"Observed"}],
                    "after":{"do":[{"op":"track.clear","track":"actor.stress","amount":{"read":"recipient.hp.marked"}}],
                             "report":"{recipient.name} now has {recipient.hp.marked} HP marked."},
                    "ui":{"label":"Observe"}},
                   {"id":"care","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"option"}],
                    "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":1,"label":"Care"}],
                    "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                             "report":"Care for {recipient.name}."},"ui":{"label":"Care"}},
                   {"id":"heal","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"any","ally":true},
                     "targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":true,
                       "where":{"gt":[{"read":"recipient.hp.marked"},0]}}}],
                    "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                    "do":[{"op":"dice.roll","count":1,"sides":4,"as":"die"},
                          {"op":"track.clear","track":"recipient.hp",
                           "amount":{"add":[{"read":"result.die"},{"read":"event.move.bonus"}]},"as":"clear"},
                          {"op":"countdown.start","value":2,"label":"Healing"}],
                    "ui":{"label":"Heal","report":"Healed {recipient.name} for {result.clear}."}},
                   {"id":"self","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"mend","kind":"any"}}],
                    "do":[{"op":"track.clear","track":"actor.hp","amount":1}],"ui":{"label":"Self"}},
                   {"id":"repair","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"repair","kind":"any","ally":true},
                     "targetSelection":{"cardinality":"one","scope":"activeCharacters","excludeSource":true}}],
                    "do":[{"op":"track.clear","track":"recipient.armorTrack","amount":1}],"ui":{"label":"Repair"}},
                   {"id":"brace","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"brace","kind":"any"}}],"ui":{"label":"Brace"}}
                 ]}}
                """);
    }

    private static AutomationHost.Pack pack(ObjectNode ruleset) {
        return AutomationHost.compile("target-rest", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static ObjectNode sheet(String name, int hp, int hope) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"%s","permanentHp":0,"hope":{"slots":6,"on":%d},"hp":{"slots":6,"on":%d},
                 "stress":{"slots":6,"on":3},"armorTrack":{"slots":3,"on":2}}
                """.formatted(name, hope, hp));
    }

    private static ObjectNode choice(String rule) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", rule).put("bindingId", "move");
    }

    private static ArrayNode options(String... rules) {
        ArrayNode result = RuleJson.JSON.createArrayNode();
        for (String rule : rules) result.add(choice(rule));
        return result;
    }

    private record Setup(AutomationHost.Pack pack, ObjectNode source, ObjectNode recipient,
                         ObjectNode healthy, ObjectNode preparation, AutomationHost.RestProgress progress) {}

    private static Setup setup(ObjectNode ruleset, int hope) throws Exception {
        var pack = pack(ruleset);
        ObjectNode source = sheet("Ada", 6, hope);
        ObjectNode recipient = sheet("Mira", 6, 2).put("permanentHp", 2);
        recipient.putArray("effects").addObject().put("lockedHp", 1);
        ObjectNode state = HOST.projection().state(recipient).put("revision", 7);
        ObjectNode effect = state.putArray("effects").addObject().put("id", "ward").put("label", "Ward")
                .put("effect", "ruleset:base#ward").put("sourceInstanceId", "ruleset:base")
                .put("sourceActorId", "mira");
        effect.putObject("locks").put("hp", 1);
        ObjectNode healthy = sheet("Healthy", 0, 2);
        HOST.projection().state(healthy).put("revision", 2);
        ObjectNode preparation = HOST.prepareRest(pack, source,
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                "short", 0, options(), StructuredRollTest.noRandom());
        return new Setup(pack, source, recipient, healthy, preparation, HOST.restProgress(pack, preparation));
    }

    private static PartyTargets party(Setup setup, ObjectNode recipient, AtomicReference<String> scope) {
        List<PartyTargets.Member> members = new ArrayList<>();
        for (var entry : Map.of("ada", setup.source(), "mira", recipient, "healthy", setup.healthy()).entrySet()) {
            String id = entry.getKey();
            ObjectNode sheet = entry.getValue();
            members.add(new PartyTargets.Member(id, sheet.path("name").asText(), sheet.toString(), HOST.revision(sheet),
                    event -> HOST.recipientSnapshot(setup.pack(), sheet, event, id)));
        }
        return new PartyTargets(setup.pack().revision(), setup.source().toString(), selected -> {
            scope.set(selected);
            return members;
        });
    }

    private static ObjectNode targetRequest(Setup setup, PartyTargets party, String rule) {
        return targetRequest(setup, party, rule, "mira");
    }

    private static ObjectNode targetRequest(Setup setup, PartyTargets party, String rule, String recipientId) {
        ObjectNode event = ((ObjectNode) setup.preparation().get("event")).deepCopy().put("type", "downtime.move");
        event.putObject("move").put("id", "mend").put("ally", true).put("long", false).put("slots", 1).put("bonus", 0);
        ObjectNode context = RuleJson.JSON.createObjectNode();
        context.set("actor", HOST.recipientSnapshot(setup.pack(), setup.source(), event, "ada"));
        context.putObject("source").put("instanceId", "ruleset:base");
        context.set("event", event);
        JsonNode binding = setup.pack().programs().get("ruleset:base").rule(rule).path("bindings").get(0);
        JsonNode offer = party.describe(binding, rule, context);
        JsonNode option = null;
        for (JsonNode candidate : offer.path("options"))
            if (recipientId.equals(candidate.path("id").asText())) option = candidate;
        assertNotNull(option, offer.toString());
        ObjectNode request = RuleJson.JSON.createObjectNode().put("recipientId", recipientId);
        request.set("version", option.get("version"));
        return request;
    }

    private static AutomationHost.RestRoster roster(Setup setup, Map<String, AtomicInteger> loads) {
        List<AutomationHost.RestMember> members = new ArrayList<>();
        for (var entry : Map.of("ada", setup.source(), "mira", setup.recipient(), "healthy", setup.healthy()).entrySet()) {
            String id = entry.getKey();
            ObjectNode sheet = entry.getValue();
            members.add(new AutomationHost.RestMember(id, sheet.path("name").asText(), sheet.toString(), HOST.revision(sheet), () -> {
                loads.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                return sheet;
            }));
        }
        return new AutomationHost.RestRoster(setup.source().toString(), scope -> members);
    }

    private static ObjectNode transaction(Setup setup) {
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", "short")
                .put("expectedRevision", HOST.revision(setup.source()));
        request.putArray("preparation");
        request.putArray("moves");
        request.putArray("completion");
        return request;
    }

    private static void transactionMove(ObjectNode request, String rule, ObjectNode target) {
        ObjectNode move = request.withArray("moves").addObject();
        move.set("selection", choice(rule));
        move.putArray("options");
        if (target != null) move.set("target", target.deepCopy());
    }

    private static AutomationHost.RestTransaction stage(Setup setup, ObjectNode request, AutomationHost.RestRoster roster,
                                                         int... faces) {
        return HOST.stageRest(setup.pack(), setup.source(),
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                request, faces.length == 0 ? StructuredRollTest.noRandom() : StructuredRollTest.faces(faces), roster);
    }

    private static AutomationHost.RestMoveResult move(Setup setup, AutomationHost.RestProgress progress,
                                                       String rule, ArrayNode choices, PartyTargets party,
                                                       ObjectNode target, int... faces) {
        return HOST.restMove(setup.pack(), setup.source(), (ObjectNode) setup.preparation().get("event"),
                progress, choice(rule), choices, faces.length == 0 ? StructuredRollTest.noRandom() : StructuredRollTest.faces(faces),
                party == null ? null : new AutomationHost.RestTarget(party, target, (update, event) -> {
                    ObjectNode original = "mira".equals(update.actorId()) ? setup.recipient() : setup.healthy();
                    ObjectNode changed = original.deepCopy();
                    HOST.writeRecipient(changed, update);
                    return HOST.recipientSnapshot(setup.pack(), changed, event, update.actorId());
                }));
    }

    @Test
    void allyMoveUsesVersionedHostRecipientAndPostMoveDeferredFacts() throws Exception {
        Setup setup = setup(ruleset(), 4);
        AtomicReference<String> loadedScope = new AtomicReference<>();
        PartyTargets party = party(setup, setup.recipient(), loadedScope);
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeRecipient = setup.recipient().deepCopy();
        var result = move(setup, setup.progress(), "heal", options("care"), party, target, 4);

        assertEquals("activeCharacters", loadedScope.get());
        assertEquals(0, setup.source().at("/hope/on").asInt());
        assertEquals(0, setup.source().at("/stress/on").asInt(), "Deferred body reads the healed recipient.");
        assertEquals(beforeRecipient, setup.recipient(), "The recipient sheet is not written until the caller applies the update.");
        assertEquals(1, party.updates().size());
        var update = party.updates().getFirst();
        assertEquals("mira", update.actorId());
        assertEquals(8, update.revision());
        assertEquals(4, update.snapshot().at("/hp/marked").asInt(), "The recipient's permanent and both temporary locks apply.");
        assertEquals(List.of("observe", "care", "heal", "observe", "care"), result.response().path("actions").findValuesAsText("ruleId"));
        assertEquals(3, result.response().at("/event/move/bonus").asInt());
        assertEquals("Healed Mira for 2.", result.response().at("/actions/2/report").asText());
        assertEquals("mira", result.response().at("/actions/2/recipientId").asText());
        assertEquals("Mira now has 4 HP marked.", result.response().at("/actions/3/report").asText());
        assertEquals("Care for Mira.", result.response().at("/actions/4/report").asText());
        assertEquals("Healing", result.response().at("/outbox/0/label").asText());
        assertEquals(1, result.progress().slotsUsed());
        HOST.writeRecipient(setup.recipient(), update);
        assertEquals(4, setup.recipient().at("/hp/on").asInt());
        assertEquals(8, HOST.revision(setup.recipient()));
    }

    @Test
    void missingStaleSelfIneligibleAndForgedTargetsFailWithoutStaging() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), null, null)).code());
        assertThrows(IllegalArgumentException.class,
                () -> move(setup, setup.progress(), "self", options(), party, target));
        for (String id : List.of("ada", "healthy", "offline-missing")) {
            ObjectNode forged = target.deepCopy().put("recipientId", id);
            assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                    () -> move(setup, setup.progress(), "heal", options(), party, forged)).code());
        }
        ObjectNode malformed = target.deepCopy().put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> move(setup, setup.progress(), "heal", options(), party, malformed));

        ObjectNode changed = setup.recipient().deepCopy();
        changed.withObject("/hope").put("on", 3);
        PartyTargets changedParty = party(setup, changed, new AtomicReference<>());
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), changedParty, target)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
        assertTrue(changedParty.updates().isEmpty());
        assertEquals(0, setup.progress().count());
    }

    @Test
    void deferredFailureRollsBackTheSourceAndNeverStagesTheRecipient() throws Exception {
        Setup setup = setup(ruleset(), 3);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options("care"), party, target, 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
        assertEquals(0, setup.progress().count());
    }

    @Test
    void targetEligibilityIsRecheckedAfterMoveAdjustmentsBeforeRolling() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/2/bindings/0/targetSelection")).set("where", RuleJson.parse("""
                {"and":[{"gt":[{"read":"recipient.hp.marked"},0]},
                        {"eq":[{"read":"event.move.bonus"},0]}]}
                """));
        Setup setup = setup(ruleset, 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, setup.progress(), "heal", options(), party, target)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
    }

    @Test
    void commandsRequiringSettlementAreRejectedAtEveryTargetedStage() throws Exception {
        for (String path : List.of("/automation/rules/0/do", "/automation/rules/2/do", "/automation/rules/0/after/do")) {
            ObjectNode ruleset = ruleset();
            ((ArrayNode) ruleset.at(path)).addObject().put("op", "damage.deal").put("to", "actor")
                    .put("amount", 1).put("label", "Damage");
            Setup setup = setup(ruleset, 4);
            PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
            ObjectNode target = targetRequest(setup, party, "heal");
            ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
            var failure = assertThrows(RuleRuntime.Unavailable.class,
                    () -> move(setup, setup.progress(), "heal", options(), party, target, 4), path);
            assertEquals("restCommand", failure.code());
            assertTrue(failure.getMessage().contains("damage.deal"));
            assertEquals(beforeSource, setup.source());
            assertEquals(beforeRecipient, setup.recipient());
            assertTrue(party.updates().isEmpty());
        }
    }

    @Test
    void allyMoveAndLaterMoveSeeSettledFearFromAdjustmentsAndDeferredBenefits() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ArrayNode) ruleset.at("/automation/rules/0/do")).addObject()
                .put("op", "table.gain").put("resource", "fear").put("amount", 2).put("label", "Adjustment");
        ((ArrayNode) ruleset.at("/automation/rules/2/do")).addObject()
                .put("op", "table.spend").put("resource", "fear").put("amount", 1).put("label", "Move");
        ((ArrayNode) ruleset.at("/automation/rules/0/after/do")).addObject()
                .put("op", "table.gain").put("resource", "fear").put("amount", 3).put("label", "Deferred");
        ((ObjectNode) ruleset.at("/automation/rules/4/ui")).put("report", "Repair at {event.table.fear} Fear.");
        Setup setup = setup(ruleset, 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode healTarget = targetRequest(setup, party, "heal");
        ObjectNode repairTarget = targetRequest(setup, party, "repair");
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", healTarget);
        transactionMove(request, "repair", repairTarget);
        ObjectNode originalRecipient = setup.recipient().deepCopy();
        var result = stage(setup, request, roster(setup, new java.util.HashMap<>()), 4);

        assertEquals(5, result.response().requiredAt("/moveEvents/0/table/fear").intValue());
        assertEquals(5, result.response().requiredAt("/moveEvents/1/table/fear").intValue());
        assertEquals(5, result.response().requiredAt("/event/table/fear").intValue());
        assertEquals(List.of(1, 3, 2),
                result.response().path("outbox").findValues("before").stream().map(JsonNode::intValue).toList());
        assertEquals(List.of(3, 2, 5),
                result.response().path("outbox").findValues("after").stream().map(JsonNode::intValue).toList());
        assertTrue(result.response().path("actions").findValuesAsText("report").contains("Repair at 5 Fear."));
        assertEquals(4, result.recipients().get("mira").requiredAt("/hp/on").intValue(),
                "Permanent and temporary locks still protect the recipient.");
        assertEquals(1, result.recipients().get("mira").requiredAt("/armorTrack/on").intValue());
        assertEquals(originalRecipient, setup.recipient());

        ((ArrayNode) ruleset.at("/automation/rules/4/do")).addObject()
                .put("op", "table.spend").put("resource", "fear").put("amount", 6).put("label", "Too much");
        Setup failing = setup(ruleset, 4);
        PartyTargets failingParty = party(failing, failing.recipient(), new AtomicReference<>());
        ObjectNode failingRequest = transaction(failing);
        transactionMove(failingRequest, "heal", targetRequest(failing, failingParty, "heal"));
        transactionMove(failingRequest, "repair", targetRequest(failing, failingParty, "repair"));
        ObjectNode beforeSource = failing.source().deepCopy(), beforeRecipient = failing.recipient().deepCopy();
        assertEquals("tableResource", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(failing, failingRequest, roster(failing, new java.util.HashMap<>()), 4)).code());
        assertEquals(beforeSource, failing.source());
        assertEquals(beforeRecipient, failing.recipient());
    }

    @Test
    void subsequentMovesRequireFreshRosterAndRetainTheAppliedRecipientUpdate() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets firstParty = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode firstTarget = targetRequest(setup, firstParty, "heal");
        var first = move(setup, setup.progress(), "heal", options(), firstParty, firstTarget, 4);
        ObjectNode sourceAfterFirst = setup.source().deepCopy();
        assertEquals("duplicateMove", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, first.progress(), "self", options(), null, null)).code());
        assertEquals("restTarget", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, first.progress(), "repair", options(), firstParty, firstTarget)).code());
        assertEquals(sourceAfterFirst, setup.source());
        assertEquals(1, firstParty.updates().size());

        ObjectNode stagedRecipient = setup.recipient().deepCopy();
        HOST.writeRecipient(stagedRecipient, firstParty.updates().getFirst());
        PartyTargets secondParty = party(setup, stagedRecipient, new AtomicReference<>());
        ObjectNode secondTarget = targetRequest(setup, secondParty, "repair");
        var second = move(setup, first.progress(), "repair", options(), secondParty, secondTarget);
        HOST.writeRecipient(stagedRecipient, secondParty.updates().getFirst());
        assertEquals(4, stagedRecipient.at("/hp/on").asInt());
        assertEquals(1, stagedRecipient.at("/armorTrack/on").asInt());
        assertEquals(9, HOST.revision(stagedRecipient));
        assertEquals(2, second.progress().slotsUsed());
        ObjectNode beforeFull = setup.source().deepCopy();
        assertEquals("restMoves", assertThrows(RuleRuntime.Unavailable.class,
                () -> move(setup, second.progress(), "brace", options(), null, null)).code());
        assertEquals(beforeFull, setup.source());
        assertEquals(6, setup.recipient().at("/hp/on").asInt(), "Neither staged update writes the original recipient.");
    }

    @Test
    void fullStagingReprojectsEarlierMovesAndAdvancesEachParticipantOnce() throws Exception {
        Setup setup = setup(ruleset(), 4);
        long sourceRevision = HOST.revision(setup.source());
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        transactionMove(request, "repair", targetRequest(setup, offers, "repair"));
        ObjectNode originalRecipient = setup.recipient().deepCopy();
        var result = stage(setup, request, roster(setup, new java.util.HashMap<>()), 4);

        assertEquals(sourceRevision + 1, HOST.revision(setup.source()));
        assertEquals(sourceRevision + 1, result.response().path("revision").asLong());
        assertEquals(3, setup.source().at("/hope/on").asInt());
        assertEquals(0, setup.source().at("/stress/on").asInt());
        assertEquals(List.of("observe", "heal", "observe", "repair"), result.response().path("actions").findValuesAsText("ruleId"),
                "The second move must see that the first move already healed the recipient.");
        assertEquals(originalRecipient, setup.recipient());
        assertEquals(List.of("mira"), result.recipients().keySet().stream().toList());
        ObjectNode staged = result.recipients().get("mira");
        assertEquals(4, staged.at("/hp/on").asInt());
        assertEquals(1, staged.at("/armorTrack/on").asInt());
        assertEquals(8, HOST.revision(staged), "Two changes still produce one published revision.");
        staged.withObject("/hp").put("on", 0);
        assertEquals(4, result.recipients().get("mira").at("/hp/on").asInt());
        assertEquals(Map.of("mira", originalRecipient.toString(), "healthy", setup.healthy().toString()),
                result.readVersions(), "An unchanged character used by target eligibility is a read dependency.");
        assertThrows(UnsupportedOperationException.class, () -> result.readVersions().put("other", "version"));
    }

    @Test
    void targetedRestReceiptReplaysWithoutReadingOrStagingRecipientsAgain() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup).put("requestId", "ally-rest");
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        var first = HOST.stageRestWithReceipt(setup.pack(), setup.source(),
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                request, "owner", StructuredRollTest.faces(4), roster(setup, new java.util.HashMap<>()));
        assertFalse(first.replayed());
        assertEquals(Set.of("mira"), first.recipients().keySet());
        assertEquals(Set.of("mira", "healthy"), first.readVersions().keySet());
        ObjectNode saved = setup.source().deepCopy();
        var forbiddenRoster = new AutomationHost.RestRoster("unchanged", scope -> {
            fail("A retry must not load a roster.");
            return List.of();
        });
        var replay = HOST.stageRestWithReceipt(setup.pack(), setup.source(),
                AutomationHost.event("action.request", "ada", 0), request, "owner",
                StructuredRollTest.noRandom(), forbiddenRoster);
        assertTrue(replay.replayed());
        assertEquals(first.response(), replay.response());
        assertTrue(replay.recipients().isEmpty());
        assertTrue(replay.readVersions().isEmpty());
        assertEquals(saved, setup.source());
        assertEquals(6, setup.recipient().at("/hp/on").asInt());
    }

    @Test
    void recipientAtRevisionLimitRejectsTheEntireRest() throws Exception {
        Setup setup = setup(ruleset(), 4);
        setup.recipient().withObject("/automationState").put("revision", 1L << 52);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup).put("requestId", "limit");
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        ObjectNode source = setup.source().deepCopy(), recipient = setup.recipient().deepCopy();
        assertEquals("revision", assertThrows(RuleRuntime.Unavailable.class,
                () -> HOST.stageRestWithReceipt(setup.pack(), setup.source(),
                        AutomationHost.event("action.request", "ada", 1), request, "owner",
                        StructuredRollTest.faces(4), roster(setup, new java.util.HashMap<>()))).code());
        assertEquals(source, setup.source());
        assertEquals(recipient, setup.recipient());
    }

    @Test
    void deferredBenefitsReadFreshlyRecomputedRecipientStatistics() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/0/after")).put("report",
                "{recipient.name} has {recipient.stats.armorAvailable} Armor available.");
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "repair", targetRequest(setup, offers, "repair"));
        var result = stage(setup, request, roster(setup, new java.util.HashMap<>()));
        assertEquals("Mira has 2 Armor available.", result.response().at("/actions/2/report").asText());
        assertEquals(1, result.recipients().get("mira").at("/armorTrack/on").asInt());
        assertEquals(2, setup.recipient().at("/armorTrack/on").asInt());
    }

    @Test
    void invalidRecipientReprojectionCannotPublishASourceOrTargetUpdate() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets party = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode target = targetRequest(setup, party, "heal");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        var invalid = new AutomationHost.RestTarget(party, target,
                (update, event) -> RuleJson.JSON.createObjectNode().put("id", "other"));
        assertThrows(IllegalArgumentException.class, () -> HOST.restMove(setup.pack(), setup.source(),
                (ObjectNode) setup.preparation().get("event"), setup.progress(), choice("heal"), options(),
                StructuredRollTest.faces(4), invalid));
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
        assertTrue(party.updates().isEmpty());
    }

    @Test
    void fullStagingCanChangeTwoRecipientsWithoutWritingTheirOriginalSheets() throws Exception {
        Setup setup = setup(ruleset(), 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal", "mira"));
        transactionMove(request, "repair", targetRequest(setup, offers, "repair", "healthy"));
        ObjectNode originalMira = setup.recipient().deepCopy(), originalHealthy = setup.healthy().deepCopy();
        var result = stage(setup, request, roster(setup, new java.util.HashMap<>()), 4);
        assertEquals(Set.of("mira", "healthy"), result.recipients().keySet());
        assertEquals(4, result.recipients().get("mira").at("/hp/on").asInt());
        assertEquals(1, result.recipients().get("healthy").at("/armorTrack/on").asInt());
        assertEquals(8, HOST.revision(result.recipients().get("mira")));
        assertEquals(3, HOST.revision(result.recipients().get("healthy")));
        assertEquals(originalMira, setup.recipient());
        assertEquals(originalHealthy, setup.healthy());
    }

    @Test
    void failedLaterTargetOrCompletionDiscardsEveryPrivateStage() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ArrayNode) ruleset.at("/automation/rules")).add(RuleJson.parse("""
                {"id":"costly","bindings":[{"id":"short","event":"rest.short","surface":"rest","mode":"option"}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":6}],"ui":{"label":"Costly"}}
                """));
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode heal = targetRequest(setup, offers, "heal");
        ObjectNode repair = targetRequest(setup, offers, "repair");
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        ObjectNode stale = transaction(setup);
        transactionMove(stale, "heal", heal);
        transactionMove(stale, "repair", repair.deepCopy().put("version", "stale"));
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(setup, stale, roster(setup, new java.util.HashMap<>()), 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());

        ObjectNode late = transaction(setup);
        transactionMove(late, "heal", heal);
        late.withArray("completion").addObject().put("sourceInstanceId", "ruleset:base")
                .put("ruleId", "costly").put("bindingId", "short");
        assertEquals("cost", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(setup, late, roster(setup, new java.util.HashMap<>()), 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
    }

    @Test
    void laterTargetEligibilityUsesTheEarlierStagedRecipient() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/4/bindings/0/targetSelection")).set("where",
                RuleJson.parse("{\"gt\":[{\"read\":\"recipient.hp.marked\"},5]}"));
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        transactionMove(request, "repair", targetRequest(setup, offers, "repair"));
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(setup, request, roster(setup, new java.util.HashMap<>()), 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
    }

    @Test
    void unfilteredTargetsLoadOnlyTheirSelectedSheetsAndValidateSavedRevisions() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/2/bindings/0/targetSelection")).remove("where");
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        Map<String, AtomicInteger> loads = new java.util.HashMap<>();
        var result = stage(setup, request, roster(setup, loads), 4);
        assertEquals(1, loads.get("mira").get());
        assertFalse(loads.containsKey("ada"));
        assertFalse(loads.containsKey("healthy"));
        assertEquals(Map.of("mira", setup.recipient().toString()), result.readVersions());

        Setup stale = setup(ruleset, 4);
        PartyTargets staleOffers = party(stale, stale.recipient(), new AtomicReference<>());
        ObjectNode staleRequest = transaction(stale);
        transactionMove(staleRequest, "heal", targetRequest(stale, staleOffers, "heal"));
        ObjectNode changed = stale.recipient().deepCopy();
        changed.withObject("/automationState").put("revision", 8);
        var member = new AutomationHost.RestMember("mira", "Mira", stale.recipient().toString(), 7, () -> changed);
        var staleRoster = new AutomationHost.RestRoster(stale.source().toString(), scope -> List.of(member));
        ObjectNode before = stale.source().deepCopy();
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(stale, staleRequest, staleRoster, 4)).code());
        assertEquals(before, stale.source());
        assertEquals(7, HOST.revision(stale.recipient()));
    }

    @Test
    void changedScopeMetadataAndOversizedOrDuplicateRostersFailAtomically() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/rules/4/bindings/0/targetSelection")).put("scope", "activePlayerCharacters");
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        transactionMove(request, "repair", targetRequest(setup, offers, "repair"));
        var current = new AutomationHost.RestMember("mira", "Mira", setup.recipient().toString(), 7, setup::recipient);
        var changed = new AutomationHost.RestMember("mira", "Mira", "changed", 7, setup::recipient);
        var shifting = new AutomationHost.RestRoster(setup.source().toString(),
                scope -> List.of("activeCharacters".equals(scope) ? current : changed));
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(setup, request, shifting, 4)).code());
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());

        ObjectNode single = transaction(setup);
        transactionMove(single, "heal", targetRequest(setup, offers, "heal"));
        var duplicate = new AutomationHost.RestRoster(setup.source().toString(), scope -> List.of(current, current));
        assertThrows(IllegalArgumentException.class, () -> stage(setup, single, duplicate, 4));
        List<AutomationHost.RestMember> tooMany = new ArrayList<>();
        for (int i = 0; i < 129; i++)
            tooMany.add(new AutomationHost.RestMember("member" + i, "Member", "v1", 0, setup::recipient));
        var oversized = new AutomationHost.RestRoster(setup.source().toString(), scope -> tooMany);
        assertThrows(IllegalArgumentException.class, () -> stage(setup, single, oversized, 4));
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());
    }

    @Test
    void targetedRestRejectsCrossHolderCleanupBeforeItCanAffectALaterMove() throws Exception {
        ObjectNode ruleset = ruleset();
        ((ObjectNode) ruleset.at("/automation/effects/ward")).put("singleHolder", true);
        ((ArrayNode) ruleset.at("/automation/rules")).add(RuleJson.parse("""
                {"id":"start-ward","bindings":[{"id":"rest","event":"rest.prepare","surface":"rest","mode":"automatic"}],
                 "do":[{"op":"effect.start","effect":"ward","target":"actor"}],"ui":{"label":"Ward"}}
                """));
        Setup setup = setup(ruleset, 4);
        PartyTargets offers = party(setup, setup.recipient(), new AtomicReference<>());
        ObjectNode request = transaction(setup);
        transactionMove(request, "heal", targetRequest(setup, offers, "heal"));
        ObjectNode beforeSource = setup.source().deepCopy(), beforeRecipient = setup.recipient().deepCopy();
        var failure = assertThrows(RuleRuntime.Unavailable.class,
                () -> stage(setup, request, roster(setup, new java.util.HashMap<>()), 4));
        assertEquals("restCommand", failure.code());
        assertTrue(failure.getMessage().contains("effect.endElsewhere"));
        assertEquals(beforeSource, setup.source());
        assertEquals(beforeRecipient, setup.recipient());

        ObjectNode self = transaction(setup);
        assertEquals("effect.endElsewhere", HOST.stageSelfRest(setup.pack(), setup.source(),
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                self, StructuredRollTest.noRandom()).at("/outbox/0/kind").asText());
    }

    @Test
    void compilerRestrictsTargetedDowntimeAndDefersOnlyUnresolvedRecipeFields() throws Exception {
        ObjectNode automation = (ObjectNode) ruleset().path("automation").deepCopy();
        assertDoesNotThrow(() -> RuleCompiler.compile("ruleset:test", automation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));
        String path = "/rules/2/bindings/0";
        for (String mutation : List.of("noMove", "notAlly", "all", "includesSelf", "missingExclusion", "option", "wrongEvent")) {
            ObjectNode invalid = automation.deepCopy();
            ObjectNode binding = (ObjectNode) invalid.at(path);
            switch (mutation) {
                case "noMove" -> binding.remove("restMove");
                case "notAlly" -> ((ObjectNode) binding.get("restMove")).put("ally", false);
                case "all" -> ((ObjectNode) binding.get("targetSelection")).put("cardinality", "all");
                case "includesSelf" -> ((ObjectNode) binding.get("targetSelection")).put("excludeSource", false);
                case "missingExclusion" -> ((ObjectNode) binding.get("targetSelection")).remove("excludeSource");
                case "option" -> binding.put("mode", "option");
                case "wrongEvent" -> binding.put("event", "rest.short").put("surface", "rest");
            }
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("ruleset:test", invalid,
                    RuleJson.parse(LegacyCatalogConverter.coreRecipes())), mutation);
        }
        ObjectNode afterMutation = automation.deepCopy();
        ((ArrayNode) afterMutation.at("/rules/0/after/do")).addObject().put("op", "track.clear")
                .put("track", "recipient.hp").put("amount", 1);
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("ruleset:test", afterMutation,
                RuleJson.parse(LegacyCatalogConverter.coreRecipes())));

        ObjectNode recipes = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-rules","version":2,"recipes":[
                 {"id":"test:ally","args":{"cardinality":{"type":"string","default":"all"}},"ui":{},"rules":[]}]}
                """);
        ObjectNode recipeRule = (ObjectNode) automation.at("/rules/2").deepCopy();
        ((ObjectNode) recipeRule.at("/bindings/0/targetSelection")).putObject("cardinality").put("$arg", "cardinality");
        ((ArrayNode) recipes.at("/recipes/0/rules")).add(recipeRule);
        String empty = "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"items\",\"entries\":[]}";
        String used = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"kit","name":"Kit","automation":{"rules":[{"id":"heal","useRecipe":"test:ally"}]}}]}
                """;
        assertTrue(RuleCatalog.compile(empty, recipes.toString()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(used, recipes.toString()));
        ((ObjectNode) recipes.at("/recipes/0/args/cardinality")).put("default", "one");
        assertDoesNotThrow(() -> RuleCatalog.compile(used, recipes.toString()));
        ((ObjectNode) recipes.at("/recipes/0/rules/0/bindings/0/targetSelection")).put("excludeSource", false);
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(empty, recipes.toString()));
    }
}
