package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class PartyRecoveryTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static RuleCompiler.Program program(String id, String costs, String operations) throws Exception {
        return RuleCompiler.compile(id, RuleJson.parse("""
                {"state":{"count":0},"rules":[
                 {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                 {"eq":[{"read":"event.change"},"clear"]},
                                 {"gt":[{"read":"event.requested"},
                                        {"subtract":[{"read":"event.before.marked"},{"read":"event.after.marked"}]}]}]},
                  "costs":[%s],"do":[{"op":"state.increment","slot":"count","by":1},%s],
                  "ui":{"report":"Recovered for {actor.name}."}}
                ]}
                """.formatted(costs, operations)), fixture("rewrite/recipes"));
    }

    private static ObjectNode sheet(String name, int armor, int hope, long revision) throws Exception {
        ObjectNode result = (ObjectNode) RuleJson.parse("""
                {"name":"%s","hope":{"slots":6,"on":%d},"hp":{"slots":6,"on":1},
                 "stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":%d}}
                """.formatted(name, hope, armor));
        HOST.projection().state(result).put("revision", revision);
        return result;
    }

    private static void equip(ObjectNode sheet, String program) {
        sheet.putObject("activeArmor").put("name", "Armor").put("instanceId", "armor")
                .put("automationSource", program);
    }

    private static AutomationHost.RestMember member(String id, ObjectNode sheet) {
        return new AutomationHost.RestMember(id, sheet.path("name").asText(), "stored-" + id,
                HOST.revision(sheet), () -> sheet);
    }

    private static AutomationHost.PartyRecoveryRequest request(String source, String track, int amount) {
        return new AutomationHost.PartyRecoveryRequest("features:repair", source, track, amount, "Repair");
    }

    private static AutomationHost.ScopedPartyRecoveryRequest scoped(String scope, String source, String track, int amount) {
        return new AutomationHost.ScopedPartyRecoveryRequest(scope, request(source, track, amount));
    }

    @Test
    void extractsOnlyPositiveAuthoredRequestsWithoutCombiningOrGuessing() throws Exception {
        ObjectNode event = (ObjectNode) RuleJson.parse("""
                {"type":"downtime.move","actorId":"ada","move":{"ally":false,"allies":0,"adjustments":[
                 {"field":"allies","requested":128,"by":128,"sourceId":"features:repair","sourceInstanceId":"one","label":"First",
                  "partyRecovery":{"track":"armorTrack","scope":"activePlayerCharacters"}},
                 {"field":"allies","requested":1,"by":0,"sourceId":"features:repair","sourceInstanceId":"two","label":"Clamped",
                  "partyRecovery":{"track":"armorTrack","scope":"connectedPlayerCharacters"}},
                 {"field":"allies","requested":-1000,"by":-128,"sourceId":"features:repair","sourceInstanceId":"one","label":"Cancel"},
                 {"field":"bonus","requested":2,"by":2,"sourceId":"features:care","sourceInstanceId":"three","label":"Care",
                  "partyRecovery":{"track":"stress","scope":"activeCharacters"}},
                 {"field":"bonus","requested":3,"by":3,"sourceId":"features:other","sourceInstanceId":"four","label":"Bonus"}
                ]}}
                """);
        var requests = AutomationHost.partyRecoveryRequests(event);
        assertEquals(List.of("activePlayerCharacters", "connectedPlayerCharacters", "activeCharacters"),
                requests.stream().map(AutomationHost.ScopedPartyRecoveryRequest::scope).toList());
        assertEquals(List.of(128, 1, 2), requests.stream().map(item -> item.request().requested()).toList());
        assertEquals(List.of("one", "two", "three"), requests.stream().map(item -> item.request().sourceInstanceId()).toList());
        assertEquals(List.of("armorTrack", "armorTrack", "stress"), requests.stream().map(item -> item.request().track()).toList());
        ObjectNode unannotated = event.deepCopy();
        ((ObjectNode) unannotated.at("/move/adjustments/0")).remove("partyRecovery");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> AutomationHost.partyRecoveryRequests(unannotated)).code());
        ObjectNode wrongScope = event.deepCopy();
        ((ObjectNode) wrongScope.at("/move/adjustments/0/partyRecovery")).put("scope", "everyone");
        assertThrows(IllegalArgumentException.class, () -> AutomationHost.partyRecoveryRequests(wrongScope));
        ObjectNode unattributed = event.deepCopy();
        ((ObjectNode) unattributed.at("/move/adjustments/1")).remove("sourceInstanceId");
        assertThrows(IllegalArgumentException.class, () -> AutomationHost.partyRecoveryRequests(unattributed));
        event.withObject("/move").put("ally", true);
        assertTrue(AutomationHost.partyRecoveryRequests(event).isEmpty(), "Narrative ally moves do not fan out.");
        ObjectNode orphan = (ObjectNode) RuleJson.parse("""
                {"type":"downtime.move","move":{"ally":false,"allies":1}}""");
        assertEquals("restCommand", assertThrows(RuleRuntime.Unavailable.class,
                () -> AutomationHost.partyRecoveryRequests(orphan)).code());
    }

    @Test
    void mixedScopesRemainInRequestOrderAndAreInspectedOnlyOnce() throws Exception {
        RuleCompiler.Program overflow = program("armor:overflow", "", """
                {"op":"resource.gain","pool":"actor.hope","amount":1},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}
                """);
        var pack = new AutomationHost.Pack("party", Map.of(overflow.sourceId(), overflow));
        ObjectNode mira = sheet("Mira", 0, 0, 2), nia = sheet("Nia", 0, 0, 3), zara = sheet("Zara", 0, 0, 4);
        for (ObjectNode sheet : List.of(mira, nia, zara)) equip(sheet, overflow.sourceId());
        AtomicInteger activeLoads = new AtomicInteger(), connectedLoads = new AtomicInteger();
        var roster = new AutomationHost.RestRoster("source", scope -> {
            if ("activePlayerCharacters".equals(scope)) {
                activeLoads.incrementAndGet();
                return List.of(member("zara", zara), member("mira", mira));
            }
            if ("connectedPlayerCharacters".equals(scope)) {
                connectedLoads.incrementAndGet();
                return List.of(member("zara", zara), member("nia", nia));
            }
            fail("Unexpected scope " + scope);
            return List.of();
        });
        var result = HOST.stagePartyRecoveries(pack, AutomationHost.event("downtime.move", "ada", 0), roster, List.of(
                scoped("activePlayerCharacters", "one", "armorTrack", 1),
                scoped("connectedPlayerCharacters", "two", "armorTrack", 1),
                scoped("activePlayerCharacters", "three", "armorTrack", 1)), noRandom());
        assertEquals(1, activeLoads.get());
        assertEquals(1, connectedLoads.get());
        assertEquals(List.of("activePlayerCharacters", "connectedPlayerCharacters"),
                result.response().path("scopeSnapshots").findValuesAsText("scope"));
        assertEquals(List.of("mira", "zara"), result.response().at("/scopeSnapshots/0/membership").findValuesAsText("id"));
        assertEquals(List.of("nia", "zara"), result.response().at("/scopeSnapshots/1/membership").findValuesAsText("id"));
        assertEquals(List.of("mira", "zara", "nia", "zara", "mira", "zara"),
                result.response().path("clears").findValuesAsText("holderId"));
        assertEquals(List.of("one", "one", "two", "two", "three", "three"),
                result.response().path("clears").findValuesAsText("sourceInstanceId"));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), result.response().path("outbox").findValues("before").stream()
                .map(node -> node.asInt()).toList());
        assertEquals(6, result.response().at("/event/table/fear").asInt());
        assertEquals(2, result.recipients().get("mira").at("/hope/on").asInt());
        assertEquals(1, result.recipients().get("nia").at("/hope/on").asInt());
        assertEquals(3, result.recipients().get("zara").at("/hope/on").asInt());
        assertEquals(5, HOST.revision(result.recipients().get("zara")));
        assertEquals(Map.of("mira", "stored-mira", "nia", "stored-nia", "zara", "stored-zara"), result.readVersions());
        assertEquals(Map.of("activePlayerCharacters", List.of("mira", "zara"),
                "connectedPlayerCharacters", List.of("nia", "zara")), result.scopeMembership());
        assertThrows(UnsupportedOperationException.class, () -> result.scopeMembership().get("activePlayerCharacters").add("new"));
        assertDoesNotThrow(() -> AutomationHost.requireRestScopeMembership("ada", result.scopeMembership(), roster));
        assertEquals(0, zara.at("/hope/on").asInt());
    }

    @Test
    void rosterOrderedRecoveriesRetainAndRecheckTheHostOrderWithoutLoadingSheets() throws Exception {
        RuleCompiler.Program overflow = program("armor:overflow", "", """
                {"op":"resource.gain","pool":"actor.hope","amount":1},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}
                """);
        var pack = new AutomationHost.Pack("party", Map.of(overflow.sourceId(), overflow));
        ObjectNode zara = sheet("Zara", 0, 0, 4), mira = sheet("Mira", 0, 0, 2), nia = sheet("Nia", 0, 0, 3);
        for (ObjectNode sheet : List.of(zara, mira, nia)) equip(sheet, overflow.sourceId());
        var source = new AutomationHost.RestMember("ada", "Ada", "stored-ada", 1,
                () -> { throw new AssertionError("The source is excluded."); });
        List<AutomationHost.RestMember> members = List.of(member("zara", zara), source,
                member("mira", mira), member("nia", nia));
        var roster = new AutomationHost.RestRoster("stored-ada", scope -> members);
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0);
        var result = HOST.stagePartyRecoveriesInRosterOrder(pack, event, roster, List.of(
                scoped("activePlayerCharacters", "one", "armorTrack", 1),
                scoped("activePlayerCharacters", "two", "armorTrack", 1)), noRandom());

        assertEquals(List.of("zara", "mira", "nia", "zara", "mira", "nia"),
                result.response().path("clears").findValuesAsText("holderId"));
        assertEquals(List.of("zara", "mira", "nia"),
                result.response().at("/scopeSnapshots/0/membership").findValuesAsText("id"));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), result.response().path("outbox").findValues("before").stream()
                .map(node -> node.asInt()).toList());
        assertEquals(Map.of("activePlayerCharacters", List.of("mira", "nia", "zara")), result.scopeMembership());
        assertEquals(Map.of("activePlayerCharacters", List.of("zara", "mira", "nia")), result.scopeOrder());
        assertEquals(Map.of("zara", "stored-zara", "mira", "stored-mira", "nia", "stored-nia"), result.readVersions());
        assertEquals(2, result.recipients().get("zara").at("/hope/on").asInt());
        assertEquals(5, HOST.revision(result.recipients().get("zara")));
        assertEquals(0, zara.at("/hope/on").asInt());
        assertThrows(UnsupportedOperationException.class, () -> result.scopeOrder().get("activePlayerCharacters").add("new"));

        var noLoads = new AutomationHost.RestRoster("stored-ada", scope -> List.of(
                new AutomationHost.RestMember("zara", "Zara", "other-version", 4,
                        () -> { throw new AssertionError("Order comparison must not load a sheet."); }),
                new AutomationHost.RestMember("mira", "Mira", "other-version", 2,
                        () -> { throw new AssertionError("Order comparison must not load a sheet."); }),
                new AutomationHost.RestMember("nia", "Nia", "other-version", 3,
                        () -> { throw new AssertionError("Order comparison must not load a sheet."); })));
        assertDoesNotThrow(() -> AutomationHost.requireRestScopeOrder("ada", result.scopeOrder(), noLoads));
        var reordered = new AutomationHost.RestRoster("stored-ada", scope -> List.of(members.get(2), members.get(0), members.get(3)));
        assertDoesNotThrow(() -> AutomationHost.requireRestScopeMembership("ada", result.scopeMembership(), reordered));
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                () -> AutomationHost.requireRestScopeOrder("ada", result.scopeOrder(), reordered)).code());
    }

    @Test
    void restTransactionsRetainImmutableScopeOrderAndRecheckEachScopeOnce() {
        String scope = "activePlayerCharacters";
        Map<String, List<String>> membership = new java.util.LinkedHashMap<>();
        Map<String, List<String>> order = new java.util.LinkedHashMap<>();
        membership.put(scope, new ArrayList<>(List.of("mira", "zara")));
        order.put(scope, new ArrayList<>(List.of("zara", "mira")));
        var transaction = new AutomationHost.RestTransaction(RuleJson.JSON.createObjectNode(), Map.of(), Map.of(),
                membership, order, false);
        membership.get(scope).clear();
        order.get(scope).clear();
        assertEquals(List.of("mira", "zara"), transaction.scopeMembership().get(scope));
        assertEquals(List.of("zara", "mira"), transaction.scopeOrder().get(scope));
        assertThrows(UnsupportedOperationException.class, () -> transaction.scopeOrder().put("other", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> transaction.scopeOrder().get(scope).add("other"));

        var zara = new AutomationHost.RestMember("zara", "Zara", "new-version", 1,
                () -> { throw new AssertionError("Scope validation must not load a sheet."); });
        var mira = new AutomationHost.RestMember("mira", "Mira", "new-version", 2,
                () -> { throw new AssertionError("Scope validation must not load a sheet."); });
        var source = new AutomationHost.RestMember("ada", "Ada", "new-version", 3,
                () -> { throw new AssertionError("Scope validation must not load a sheet."); });
        AtomicInteger scopes = new AtomicInteger();
        var current = new AutomationHost.RestRoster("source", requested -> {
            assertEquals(scope, requested);
            scopes.incrementAndGet();
            return List.of(zara, source, mira);
        });
        AutomationHost.requireRestScopeSnapshots("ada", transaction.scopeMembership(), transaction.scopeOrder(), current);
        assertEquals(1, scopes.get(), "Membership and order must use the same roster snapshot.");

        var reordered = new AutomationHost.RestRoster("source", requested -> List.of(mira, zara));
        var reorderedFailure = assertThrows(RuleRuntime.Unavailable.class, () ->
                AutomationHost.requireRestScopeSnapshots("ada", transaction.scopeMembership(), transaction.scopeOrder(), reordered));
        assertEquals("staleRecipient", reorderedFailure.code());
        assertTrue(reorderedFailure.getMessage().contains("order"));
        var removed = new AutomationHost.RestRoster("source", requested -> List.of(zara));
        var membershipFailure = assertThrows(RuleRuntime.Unavailable.class, () ->
                AutomationHost.requireRestScopeSnapshots("ada", transaction.scopeMembership(), transaction.scopeOrder(), removed));
        assertEquals("staleRecipient", membershipFailure.code());
        assertTrue(membershipFailure.getMessage().contains("eligible"));
        assertTrue(new AutomationHost.RestTransaction(RuleJson.JSON.createObjectNode(), Map.of(), Map.of(), false)
                .scopeOrder().isEmpty());
    }

    @Test
    void appliesEachRequestInOrderUsingRecipientReactionsAndOneRevision() throws Exception {
        RuleCompiler.Program overflow = program("armor:overflow", "", """
                {"op":"resource.gain","pool":"actor.hope","amount":1},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}
                """);
        var pack = new AutomationHost.Pack("party", Map.of(overflow.sourceId(), overflow));
        ObjectNode mira = sheet("Mira", 0, 0, 2), nia = sheet("Nia", 1, 0, 4), zara = sheet("Zara", 0, 0, 5);
        equip(mira, overflow.sourceId());
        equip(nia, overflow.sourceId());
        ObjectNode beforeMira = mira.deepCopy(), beforeNia = nia.deepCopy(), beforeZara = zara.deepCopy();
        AtomicInteger sourceLoads = new AtomicInteger();
        var source = new AutomationHost.RestMember("ada", "Ada", "stored-ada", 1, () -> {
            sourceLoads.incrementAndGet();
            return mira;
        });
        List<String> scopes = new ArrayList<>();
        var roster = new AutomationHost.RestRoster("stored-ada", scope -> {
            scopes.add(scope);
            return List.of(member("zara", zara), member("nia", nia), source, member("mira", mira));
        });
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0).put("sessionId", "session");
        var staged = HOST.stagePartyRecovery(pack, event, roster, "activePlayerCharacters",
                List.of(request("first", "armorTrack", 1), request("second", "armorTrack", 1)), noRandom());

        assertEquals(List.of("activePlayerCharacters"), scopes);
        assertEquals(0, sourceLoads.get());
        assertTrue(staged.response().path("scopeInspected").asBoolean());
        assertEquals(List.of("mira", "nia", "zara"), staged.response().path("membership").findValuesAsText("id"));
        assertEquals(List.of("stored-mira", "stored-nia", "stored-zara"),
                staged.response().path("membership").findValuesAsText("sheetVersion"));
        assertEquals(List.of("mira", "nia", "zara", "mira", "nia", "zara"),
                staged.response().path("clears").findValuesAsText("holderId"));
        assertEquals(List.of("first", "first", "first", "second", "second", "second"),
                staged.response().path("clears").findValuesAsText("sourceInstanceId"));
        assertEquals(List.of(0, 1, 0, 0, 0, 0),
                staged.response().path("clears").findValues("cleared").stream().map(node -> node.asInt()).toList());
        assertEquals(List.of("mira", "mira", "nia"), staged.response().path("actions").findValuesAsText("actorId"));
        assertEquals(List.of("mira", "mira", "nia"), staged.response().path("outbox").findValuesAsText("actorId"));
        assertEquals(List.of(0, 1, 2), staged.response().path("outbox").findValues("before").stream()
                .map(node -> node.asInt()).toList());
        assertEquals(3, staged.response().at("/event/table/fear").asInt());
        assertEquals("ada", staged.response().at("/event/actorId").asText());
        assertEquals(2, staged.recipients().get("mira").at("/hope/on").asInt());
        assertEquals(2, staged.recipients().get("mira").at("/automationState/sources/armor/state/count").asInt());
        assertEquals(3, HOST.revision(staged.recipients().get("mira")));
        assertEquals(1, staged.recipients().get("nia").at("/hope/on").asInt());
        assertEquals(0, staged.recipients().get("nia").at("/armorTrack/on").asInt());
        assertEquals(5, HOST.revision(staged.recipients().get("nia")));
        assertFalse(staged.recipients().containsKey("zara"));
        assertEquals(Map.of("mira", "stored-mira", "nia", "stored-nia", "zara", "stored-zara"), staged.readVersions());
        assertEquals(beforeMira, mira);
        assertEquals(beforeNia, nia);
        assertEquals(beforeZara, zara);
        staged.recipients().get("mira").withObject("/hope").put("on", 6);
        assertEquals(2, staged.recipients().get("mira").at("/hope/on").asInt());
    }

    @Test
    void rejectsConsumptionThatWouldInvalidateSourcesDuringAReaction() throws Exception {
        RuleCompiler.Program item = program("items:ward", "", """
                {"op":"inventory.consume","amount":1},
                {"op":"resource.gain","pool":"actor.hope","amount":1}
                """);
        var pack = new AutomationHost.Pack("party", Map.of(item.sourceId(), item));
        ObjectNode mira = sheet("Mira", 0, 0, 7);
        mira.putArray("inventory").addObject().put("name", "Ward").put("qty", 1)
                .put("instanceId", "ward").put("automationSource", item.sourceId());
        var roster = new AutomationHost.RestRoster("source", scope -> List.of(member("mira", mira)));
        ObjectNode original = mira.deepCopy();
        assertEquals("reactionSource", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stagePartyRecovery(pack, AutomationHost.event("downtime.move", "ada", 0), roster,
                        "activePlayerCharacters", List.of(request("one", "armorTrack", 1),
                                request("two", "armorTrack", 1)), noRandom())).code());
        assertEquals(original, mira);
        assertEquals(1, mira.at("/inventory/0/qty").asInt());
    }

    @Test
    void laterFailuresAndStaleOrUnsupportedRecipientsDiscardAllChanges() throws Exception {
        RuleCompiler.Program good = program("armor:good", "", "{\"op\":\"resource.gain\",\"pool\":\"actor.hope\",\"amount\":1}");
        RuleCompiler.Program bad = program("armor:bad", "", "{\"op\":\"table.spend\",\"resource\":\"fear\",\"amount\":2,\"label\":\"Fail\"}");
        RuleCompiler.Program unsupported = program("armor:unsupported", "",
                "{\"op\":\"damage.deal\",\"to\":\"actor\",\"amount\":1,\"label\":\"Damage\"}");
        var pack = new AutomationHost.Pack("party", Map.of(good.sourceId(), good, bad.sourceId(), bad,
                unsupported.sourceId(), unsupported));
        ObjectNode mira = sheet("Mira", 0, 0, 1), nia = sheet("Nia", 0, 0, 2);
        equip(mira, good.sourceId());
        equip(nia, bad.sourceId());
        ObjectNode beforeMira = mira.deepCopy(), beforeNia = nia.deepCopy();
        var roster = new AutomationHost.RestRoster("source", scope -> List.of(member("mira", mira), member("nia", nia)));
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0);
        var requests = List.of(request("one", "armorTrack", 1));
        assertEquals("tableResource", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stagePartyRecovery(pack, event, roster, "activePlayerCharacters", requests, noRandom())).code());
        assertEquals(beforeMira, mira);
        assertEquals(beforeNia, nia);

        equip(nia, unsupported.sourceId());
        beforeNia = nia.deepCopy();
        assertEquals("reactionCommand", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stagePartyRecovery(pack, event, roster, "activePlayerCharacters", requests, noRandom())).code());
        assertEquals(beforeMira, mira);
        assertEquals(beforeNia, nia);

        var stale = new AutomationHost.RestMember("nia", "Nia", "stored-nia", 3, () -> nia);
        var staleRoster = new AutomationHost.RestRoster("source", scope -> List.of(member("mira", mira), stale));
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stagePartyRecovery(pack, event, staleRoster, "activePlayerCharacters", requests, noRandom())).code());
        assertEquals(beforeMira, mira);
        assertEquals(beforeNia, nia);
    }

    @Test
    void enforcesTheExactApplicationLimitBeforeLoadingSheets() throws Exception {
        ObjectNode sheet = sheet("Recipient", 0, 0, 1);
        AtomicInteger loads = new AtomicInteger();
        List<AutomationHost.RestMember> members = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            String id = "actor-" + i;
            members.add(new AutomationHost.RestMember(id, "Recipient", "stored-" + id, 1, () -> {
                loads.incrementAndGet();
                return sheet;
            }));
        }
        var roster = new AutomationHost.RestRoster("source", scope -> members);
        var pack = new AutomationHost.Pack("empty", Map.of());
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0);
        var request = request("first", "armorTrack", 1);
        var accepted = HOST.stagePartyRecovery(pack, event, roster, "activePlayerCharacters",
                java.util.Collections.nCopies(8, request), noRandom());
        assertEquals(1024, accepted.response().path("clears").size());
        assertEquals(128, accepted.readVersions().size());
        assertTrue(accepted.recipients().isEmpty());
        assertEquals(128, loads.get());
        loads.set(0);
        var error = assertThrows(IllegalArgumentException.class, () -> HOST.stagePartyRecovery(pack, event, roster,
                "activePlayerCharacters", java.util.Collections.nCopies(9, request), noRandom()));
        assertTrue(error.getMessage().contains("1024"));
        assertEquals(0, loads.get());

        List<AutomationHost.ScopedPartyRecoveryRequest> mixed = new ArrayList<>();
        for (int i = 0; i < 5; i++) mixed.add(scoped("activePlayerCharacters", "one", "armorTrack", 1));
        for (int i = 0; i < 4; i++) mixed.add(scoped("activeCharacters", "two", "armorTrack", 1));
        assertThrows(IllegalArgumentException.class, () -> HOST.stagePartyRecoveries(pack, event, roster, mixed, noRandom()));
        assertEquals(0, loads.get(), "The bound covers all scopes before any recipient sheet is loaded.");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stagePartyRecoveriesInRosterOrder(pack, event, roster, mixed, noRandom()));
        assertEquals(0, loads.get(), "Host ordering does not bypass the pre-load application bound.");
    }

    @Test
    void rejectsInvalidRequestsAndDoesNotLoadAnUnusedRoster() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> request("source", "recipient.hp", 1));
        assertThrows(IllegalArgumentException.class, () -> request("source", "hp", 0));
        assertThrows(IllegalArgumentException.class, () -> request("source", "hp", 1_000_001));
        var pack = new AutomationHost.Pack("empty", Map.of());
        var roster = new AutomationHost.RestRoster("source", scope -> {
            fail("No requests should not load the roster.");
            return List.of();
        });
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0);
        var empty = HOST.stagePartyRecovery(pack, event, roster, "activePlayerCharacters", List.of(), noRandom());
        assertTrue(empty.recipients().isEmpty());
        assertTrue(empty.readVersions().isEmpty());
        assertFalse(empty.response().path("scopeInspected").asBoolean());
        assertEquals(event, empty.response().path("event"));
        assertThrows(IllegalArgumentException.class, () ->
                HOST.stagePartyRecovery(pack, event, roster, "untrusted", List.of(), noRandom()));
    }
}
