package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class RestPartyRecoveryOrderingTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private record Fixture(AutomationHost.Pack pack, ObjectNode sheet) {}

    private static Fixture fixture() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","entries":[
                 {"id":"first","name":"First","automation":{"rest":{"modifiers":[
                   {"move":"stress","repairAllies":2},{"move":"tend","repairAllies":1},
                   {"move":"stress","repairAllies":3}]}}},
                 {"id":"second","name":"Second","automation":{"rest":{"modifiers":[
                   {"move":"tend","repairAllies":4},{"move":"stress","repairAllies":5}]}}}
                ]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        var pack = AutomationHost.compile("ordered", Map.of("domain-cards", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"loadout":[
                 {"name":"First","automationSource":"domain-cards:first","instanceId":"z-first"},
                 {"name":"Second","automationSource":"domain-cards:second","instanceId":"a-second"}]}
                """);
        return new Fixture(pack, sheet);
    }

    private static ObjectNode move(Fixture fixture, String id, boolean ally) {
        ObjectNode event = AutomationHost.event("downtime.move", "ada", 0);
        ObjectNode move = event.putObject("move").put("id", id).put("rest", "short")
                .put("ally", ally).put("allies", 0).put("bonus", 0);
        move.putObject("adjustable").putObject("allies").put("min", 0).put("max", 3);
        List<RuleDispatcher.Candidate> candidates = new ArrayList<>();
        for (var source : HOST.sources(fixture.pack(), fixture.sheet())) {
            ObjectNode context = RuleJson.JSON.createObjectNode().put("revision", 0);
            context.putObject("source").put("id", source.sourceId()).put("instanceId", source.instanceId())
                    .put("name", source.name());
            context.putObject("actor").put("id", "ada").putArray("effects");
            context.set("event", event.deepCopy());
            candidates.add(new RuleDispatcher.Candidate(fixture.pack().programs().get(source.sourceId()), context));
        }
        var result = RuleDispatcher.executeStagedMove(candidates, List.of(), noRandom(), fixture.pack().programs());
        return ((ObjectNode) result.contexts().getFirst().get("event")).deepCopy();
    }

    private static RuleCompiler.Program reaction(String steps) throws Exception {
        return RuleCompiler.compile("armor:listener", RuleJson.parse("""
                {"rules":[{"id":"reaction","bindings":[
                 {"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                 "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.change"},"clear"]}]},
                 "do":[%s],"ui":{"report":"{actor.name}: {event.move.id}, Fear {event.table.fear}."}}]}
                """.formatted(steps)), RuleJson.parse(LegacyCatalogConverter.coreRecipes()));
    }

    private static AutomationHost.Pack withReaction(Fixture fixture, RuleCompiler.Program reaction) {
        Map<String, RuleCompiler.Program> programs = new HashMap<>(fixture.pack().programs());
        programs.put(reaction.sourceId(), reaction);
        return new AutomationHost.Pack("with-reaction", programs);
    }

    private static ObjectNode recipient(String name, int armor, long revision) throws Exception {
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"%s","hope":{"slots":6,"on":0},"hp":{"slots":6,"on":0},
                 "stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":%d},
                 "activeArmor":{"name":"Listener","instanceId":"armor","automationSource":"armor:listener"}}
                """.formatted(name, armor));
        HOST.projection().state(sheet).put("revision", revision);
        return sheet;
    }

    private static AutomationHost.RestMember member(String id, ObjectNode sheet) {
        return new AutomationHost.RestMember(id, sheet.path("name").asText(), "stored-" + id,
                HOST.revision(sheet), () -> sheet);
    }

    private static ObjectNode completed() {
        ObjectNode event = AutomationHost.event("rest.short", "ada", 1);
        event.putObject("rest").put("kind", "short").put("phase", "afterReset");
        return event;
    }

    @Test
    void followsInitialLoadoutThenModifierThenPickOrderRatherThanDispatchOrder() throws Exception {
        Fixture fixture = fixture();
        List<ObjectNode> events = List.of(move(fixture, "tend", false), move(fixture, "stress", false),
                move(fixture, "tend", false));
        List<ObjectNode> before = events.stream().map(ObjectNode::deepCopy).toList();
        assertEquals(List.of("a-second", "z-first"),
                events.getFirst().at("/move/adjustments").findValuesAsText("sourceInstanceId"));
        assertEquals(0, events.getFirst().at("/move/adjustments/1/by").asInt(),
                "A clamped positive request still needs a distinct recovery.");

        var ordered = HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", events);
        assertEquals(List.of("z-first", "z-first", "z-first", "z-first", "a-second", "a-second", "a-second"),
                ordered.stream().map(item -> item.request().request().sourceInstanceId()).toList());
        assertEquals(List.of(2, 1, 1, 3, 4, 4, 5),
                ordered.stream().map(item -> item.request().request().requested()).toList());
        assertEquals(List.of(1, 0, 2, 1, 0, 2, 1),
                ordered.stream().map(AutomationHost.RestPartyRecovery::moveIndex).toList());
        assertTrue(ordered.stream().allMatch(item -> "activePlayerCharacters".equals(item.request().scope())));
        assertEquals(before, events, "Ordering must not mutate the host's staged move events.");
        assertThrows(UnsupportedOperationException.class, () -> ordered.add(ordered.getFirst()));
    }

    @Test
    void skipsNarrativeAllyPicksAndRejectsMissingOrChangedOrigins() throws Exception {
        Fixture fixture = fixture();
        ObjectNode self = move(fixture, "tend", false);
        ObjectNode ally = move(fixture, "tend", true);
        assertTrue(HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(ally)).isEmpty());
        assertEquals(2, HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(ally, self)).size());

        List<Consumer<ObjectNode>> invalid = List.of(
                node -> node.remove("ruleId"),
                node -> node.put("ruleId", "missing"),
                node -> node.put("bindingId", "missing"),
                node -> node.put("phase", "after"),
                node -> node.put("stepIndex", 99),
                node -> node.put("field", "bonus"),
                node -> node.withObject("/partyRecovery").put("track", "stress"));
        for (Consumer<ObjectNode> corrupt : invalid) {
            ObjectNode changed = self.deepCopy();
            corrupt.accept((ObjectNode) changed.at("/move/adjustments/0"));
            assertThrows(IllegalArgumentException.class,
                    () -> HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(changed)));
        }
        for (String field : List.of("sourceId", "sourceInstanceId")) {
            ObjectNode changed = self.deepCopy();
            ((ObjectNode) changed.at("/move/adjustments/0")).put(field, "different");
            assertEquals("source", assertThrows(RuleRuntime.Unavailable.class,
                    () -> HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(changed))).code());
        }
        ObjectNode wrongActor = self.deepCopy().put("actorId", "other");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(wrongActor)));
        assertThrows(IllegalArgumentException.class, () ->
                HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", java.util.Collections.nCopies(65, self)));
    }

    @Test
    void ordersDistinctStepsBeforeLaterPicks() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"domain-cards","entries":[
                 {"id":"first","name":"First","automation":{"rules":[
                  {"id":"both","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"First",
                          "partyRecovery":{"track":"armorTrack","scope":"activePlayerCharacters"}},
                         {"op":"plan.adjust","plan":"event.move","field":"allies","by":2,"label":"Second",
                          "partyRecovery":{"track":"armorTrack","scope":"activePlayerCharacters"}}],
                   "ui":{"label":"Both"}}]}}]}
                """;
        var pack = AutomationHost.compile("steps", Map.of("domain-cards", catalog), LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"loadout":[{"name":"First","automationSource":"domain-cards:first","instanceId":"z-first"}]}
                """);
        Fixture fixture = new Fixture(pack, sheet);
        var ordered = HOST.orderRestPartyRecoveries(pack, sheet, "ada",
                List.of(move(fixture, "tend", false), move(fixture, "stress", false)));
        assertEquals(List.of(1, 1, 2, 2), ordered.stream().map(item -> item.request().request().requested()).toList());
        assertEquals(List.of(0, 1, 0, 1), ordered.stream().map(AutomationHost.RestPartyRecovery::moveIndex).toList());
        assertEquals(List.of(0, 0, 1, 1), ordered.stream().map(AutomationHost.RestPartyRecovery::adjustmentIndex).toList());
    }

    @Test
    void schedulesInitialLoadoutRequestsDespiteMoveFiltersAndMissingLaterSources() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","entries":[
                 {"id":"rest","name":"Rest gate","automation":{"rest":{"modifiers":[
                  {"move":"tend","rest":"long","repairAllies":2}]}}},
                 {"id":"target","name":"Target gate","automation":{"rest":{"modifiers":[
                  {"move":"tend","target":"ally","repairAllies":3}]}}},
                 {"id":"effect","name":"Effect gate","automation":{"rest":{"requiresEffect":"Lit","modifiers":[
                  {"move":"tend","repairAllies":4}]}}},
                 {"id":"normal","name":"Normal","automation":{"rest":{"modifiers":[
                  {"move":"tend","repairAllies":5}]}}}
                ]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        var pack = AutomationHost.compile("filters", Map.of("domain-cards", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes());
        ObjectNode initial = (ObjectNode) RuleJson.parse("""
                {"loadout":[
                  {"name":"Rest gate","automationSource":"domain-cards:rest","instanceId":"z-rest"},
                  {"name":"Target gate","automationSource":"domain-cards:target","instanceId":"y-target"},
                  {"name":"Effect gate","automationSource":"domain-cards:effect","instanceId":"x-effect"},
                  {"name":"Normal","automationSource":"domain-cards:normal","instanceId":"w-normal"}],
                 "inventory":[{"name":"Normal","automationSource":"domain-cards:normal","instanceId":"inventory","qty":1}]}
                """);
        Fixture fixture = new Fixture(pack, initial);
        ObjectNode self = move(fixture, "tend", false), ally = move(fixture, "tend", true);
        assertEquals(List.of("inventory", "w-normal"), self.at("/move/adjustments").findValuesAsText("sourceInstanceId"));
        List<ObjectNode> events = List.of(self, move(fixture, "other", false), ally, self.deepCopy());
        var ordered = HOST.orderRestPartyRecoveries(pack, initial, "ada", events);
        assertEquals(List.of("z-rest", "z-rest", "y-target", "y-target", "x-effect", "x-effect", "w-normal", "w-normal"),
                ordered.stream().map(item -> item.request().request().sourceInstanceId()).toList());
        assertEquals(List.of(2, 2, 3, 3, 4, 4, 5, 5),
                ordered.stream().map(item -> item.request().request().requested()).toList());
        assertEquals(List.of(0, 3, 0, 3, 0, 3, 0, 3),
                ordered.stream().map(AutomationHost.RestPartyRecovery::moveIndex).toList());
        assertEquals(List.of(-1, -1, -1, -1, -1, -1, 1, 1),
                ordered.stream().map(AutomationHost.RestPartyRecovery::adjustmentIndex).toList());

        ObjectNode later = initial.deepCopy();
        later.putArray("loadout");
        later.putArray("inventory");
        ObjectNode missingSourcesMove = AutomationHost.event("downtime.move", "ada", 0);
        missingSourcesMove.putObject("move").put("id", "tend").put("rest", "short")
                .put("ally", false).put("allies", 0).put("bonus", 0);
        var missing = HOST.orderRestPartyRecoveries(pack, initial, "ada", List.of(missingSourcesMove));
        assertEquals(List.of("z-rest", "y-target", "x-effect", "w-normal"),
                missing.stream().map(item -> item.request().request().sourceInstanceId()).toList());
        assertTrue(missing.stream().allMatch(item -> item.adjustmentIndex() == -1));
        assertTrue(HOST.orderRestPartyRecoveries(pack, later, "ada", List.of(missingSourcesMove)).isEmpty());

        ObjectNode recipient = recipient("Mira", 0, 2);
        var reactionPack = withReaction(fixture, reaction("""
                {"op":"resource.gain","pool":"actor.hope","amount":1}"""));
        var staged = HOST.stageRestPartyRecoveries(reactionPack, initial, completed(), List.of(missingSourcesMove),
                new AutomationHost.RestRoster("source", scope -> List.of(member("mira", recipient))), noRandom());
        assertEquals(List.of(2, 3, 4, 5),
                staged.response().path("clears").findValues("requested").stream().map(JsonNode::asInt).toList());
        assertEquals(List.of(-1, -1, -1, -1),
                staged.response().path("clears").findValues("adjustmentIndex").stream().map(JsonNode::asInt).toList());
        assertEquals(4, staged.recipients().get("mira").at("/hope/on").asInt());
        assertEquals(0, recipient.at("/hope/on").asInt());
    }

    @Test
    void scheduledAdjustmentsCannotChangeTheirCompiledAmountsOrBeDuplicated() throws Exception {
        Fixture fixture = fixture();
        ObjectNode self = move(fixture, "tend", false);
        for (Consumer<ObjectNode> corrupt : List.<Consumer<ObjectNode>>of(
                node -> ((ObjectNode) node.at("/move/adjustments/0")).put("requested", 9),
                node -> ((ObjectNode) node.at("/move/adjustments/0")).put("requested", 0),
                node -> ((ObjectNode) node.at("/move/adjustments/0")).put("requested", -1),
                node -> ((ObjectNode) node.at("/move/adjustments/0")).put("label", "Changed"),
                node -> ((ObjectNode) node.at("/move/adjustments/0/partyRecovery/afterRest")).put("move", "other"),
                node -> ((com.fasterxml.jackson.databind.node.ArrayNode) node.at("/move/adjustments"))
                        .add(node.at("/move/adjustments/0").deepCopy()))) {
            ObjectNode changed = self.deepCopy();
            corrupt.accept(changed);
            assertThrows(IllegalArgumentException.class,
                    () -> HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada", List.of(changed)));
        }
        assertEquals(128, HOST.orderRestPartyRecoveries(fixture.pack(), fixture.sheet(), "ada",
                java.util.Collections.nCopies(64, self)).size());
        assertThrows(IllegalArgumentException.class, () -> HOST.orderRestPartyRecoveries(
                fixture.pack(), fixture.sheet(), "ada", java.util.Collections.nCopies(65, self)));
    }

    @Test
    void rejectsScheduledLocationsAbsentFromTheSheetProjection() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"domain-cards","entries":[
                 {"id":"first","name":"First","automation":{"rules":[
                  {"id":"share","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "when":false,"do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Share",
                     "partyRecovery":{"track":"armorTrack","scope":"activeCharacters",
                                      "afterRest":{"sourcePath":"unknown","move":"tend"}}}],"ui":{}}]}}]}
                """;
        var pack = AutomationHost.compile("unknown-path", Map.of("domain-cards", catalog), LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"loadout":[{"name":"First","automationSource":"domain-cards:first","instanceId":"first"}]}""");
        var fixture = new Fixture(pack, sheet);
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                HOST.orderRestPartyRecoveries(pack, sheet, "ada", List.of(move(fixture, "tend", false))))
                .getMessage().contains("sheet projection"));
    }

    @Test
    void interleavesScheduledAndOrdinaryStepsWithoutApplyingWrongMoveSchedules() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"domain-cards","entries":[
                 {"id":"first","name":"First","automation":{"rules":[
                  {"id":"both","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                   "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Scheduled",
                          "partyRecovery":{"track":"armorTrack","scope":"activeCharacters",
                                           "afterRest":{"sourcePath":"loadout","move":"tend"}}},
                         {"op":"plan.adjust","plan":"event.move","field":"allies","by":2,"label":"Ordinary",
                          "partyRecovery":{"track":"stress","scope":"activeCharacters"}}],"ui":{}}]}}]}
                """;
        var pack = AutomationHost.compile("mixed", Map.of("domain-cards", catalog), LegacyCatalogConverter.coreRecipes());
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"loadout":[{"name":"First","automationSource":"domain-cards:first","instanceId":"first"}]}""");
        var fixture = new Fixture(pack, sheet);
        var ordered = HOST.orderRestPartyRecoveries(pack, sheet, "ada", List.of(move(fixture, "tend", false),
                move(fixture, "stress", false), move(fixture, "tend", false)));
        assertEquals(List.of(1, 1, 2, 2, 2), ordered.stream().map(item -> item.request().request().requested()).toList());
        assertEquals(List.of(0, 2, 0, 1, 2), ordered.stream().map(AutomationHost.RestPartyRecovery::moveIndex).toList());
        assertEquals(List.of(0, 0, 1, 1, 1), ordered.stream().map(AutomationHost.RestPartyRecovery::adjustmentIndex).toList());
    }

    @Test
    void stagesWholeRestRecoveriesInCardModifierPickAndHostRecipientOrder() throws Exception {
        Fixture fixture = fixture();
        RuleCompiler.Program listener = reaction("""
                {"op":"resource.gain","pool":"actor.hope","amount":1,
                 "if":{"eq":[{"read":"event.move.id"},"tend"]}},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Reaction"}
                """);
        var pack = withReaction(fixture, listener);
        ObjectNode zara = recipient("Zara", 0, 4), mira = recipient("Mira", 3, 2);
        ObjectNode sourceBefore = fixture.sheet().deepCopy(), zaraBefore = zara.deepCopy(), miraBefore = mira.deepCopy();
        var source = new AutomationHost.RestMember("ada", "Ada", "stored-ada", 1,
                () -> { throw new AssertionError("The resting character is excluded."); });
        var roster = new AutomationHost.RestRoster("stored-ada", scope -> List.of(member("zara", zara), source, member("mira", mira)));
        List<ObjectNode> moves = List.of(move(fixture, "tend", false), move(fixture, "stress", false));
        ObjectNode completed = completed(), beforeCompleted = completed.deepCopy();
        var result = HOST.stageRestPartyRecoveries(pack, fixture.sheet(), completed, moves, roster, noRandom());

        JsonNode clears = result.response().path("clears");
        assertEquals(List.of("zara", "mira", "zara", "mira", "zara", "mira", "zara", "mira", "zara", "mira"),
                clears.findValuesAsText("holderId"));
        assertEquals(List.of(2, 2, 1, 1, 3, 3, 4, 4, 5, 5),
                clears.findValues("requested").stream().map(JsonNode::asInt).toList());
        assertEquals(List.of(1, 1, 0, 0, 1, 1, 0, 0, 1, 1),
                clears.findValues("moveIndex").stream().map(JsonNode::asInt).toList());
        assertEquals(List.of(0, 2, 0, 1, 0, 0, 0, 0, 0, 0),
                clears.findValues("cleared").stream().map(JsonNode::asInt).toList());
        assertEquals(List.of("Zara: stress, Fear 2.", "Mira: stress, Fear 3.",
                        "Zara: tend, Fear 4.", "Mira: tend, Fear 5.",
                        "Zara: stress, Fear 6.", "Mira: stress, Fear 7.",
                        "Zara: tend, Fear 8.", "Mira: tend, Fear 9.",
                        "Zara: stress, Fear 10.", "Mira: stress, Fear 11."),
                result.response().path("actions").findValuesAsText("report"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                result.response().path("outbox").findValues("before").stream().map(JsonNode::asInt).toList());
        assertEquals(11, result.response().at("/event/table/fear").asInt());
        assertEquals("rest.short", result.response().at("/event/type").asText());
        assertEquals(2, result.recipients().get("zara").at("/hope/on").asInt());
        assertEquals(2, result.recipients().get("mira").at("/hope/on").asInt());
        assertEquals(5, HOST.revision(result.recipients().get("zara")));
        assertEquals(3, HOST.revision(result.recipients().get("mira")));
        assertEquals(Map.of("zara", "stored-zara", "mira", "stored-mira"), result.readVersions());
        assertEquals(Map.of("activePlayerCharacters", List.of("mira", "zara")), result.scopeMembership());
        assertEquals(Map.of("activePlayerCharacters", List.of("zara", "mira")), result.scopeOrder());
        assertEquals(beforeCompleted, completed);
        assertEquals(sourceBefore, fixture.sheet());
        assertEquals(zaraBefore, zara);
        assertEquals(miraBefore, mira);
    }

    @Test
    void completedEventChangesAndBlocksRollBackEveryRecovery() throws Exception {
        Fixture fixture = fixture();
        List<ObjectNode> moves = List.of(move(fixture, "tend", false));
        ObjectNode zara = recipient("Zara", 0, 4), before = zara.deepCopy();
        var roster = new AutomationHost.RestRoster("source", scope -> List.of(member("zara", zara)));
        var blockedPack = withReaction(fixture, reaction("""
                {"op":"event.block","reason":"No recovery."}"""));
        var blocked = assertThrows(RuleRuntime.Unavailable.class, () -> HOST.stageRestPartyRecoveries(
                blockedPack, fixture.sheet(), completed(), moves, roster, noRandom()));
        assertEquals("blocked", blocked.code());
        assertEquals("No recovery.", blocked.getMessage());
        assertEquals(before, zara);

        var latePack = withReaction(fixture, reaction("""
                {"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Too late"}"""));
        assertEquals("restEvent", assertThrows(RuleRuntime.Unavailable.class, () ->
                HOST.stageRestPartyRecoveries(latePack, fixture.sheet(), completed(), moves, roster, noRandom())).code());
        assertEquals(before, zara);
        ObjectNode wrongKind = completed().put("type", "rest.long");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageRestPartyRecoveries(latePack, fixture.sheet(), wrongKind, moves, roster, noRandom()));
        ObjectNode wrongSession = completed().put("sessionId", "other");
        assertThrows(IllegalArgumentException.class,
                () -> HOST.stageRestPartyRecoveries(latePack, fixture.sheet(), wrongSession, moves, roster, noRandom()));
        assertEquals(before, zara);
    }

    @Test
    void wholeRestApplicationLimitIsCheckedBeforeReadingAnySheet() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger loads = new AtomicInteger();
        List<AutomationHost.RestMember> members = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            String id = "target" + i;
            members.add(new AutomationHost.RestMember(id, id, "version-" + i, 0, () -> {
                loads.incrementAndGet();
                return fixture.sheet();
            }));
        }
        var roster = new AutomationHost.RestRoster("source", scope -> members);
        ObjectNode tend = move(fixture, "tend", false);
        var error = assertThrows(IllegalArgumentException.class, () -> HOST.stageRestPartyRecoveries(fixture.pack(),
                fixture.sheet(), completed(), java.util.Collections.nCopies(6, tend), roster, noRandom()));
        assertTrue(error.getMessage().contains("1024"));
        assertEquals(0, loads.get());
    }
}
