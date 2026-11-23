package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            context.putObject("actor").put("id", "ada");
            context.set("event", event.deepCopy());
            candidates.add(new RuleDispatcher.Candidate(fixture.pack().programs().get(source.sourceId()), context));
        }
        var result = RuleDispatcher.executeStagedMove(candidates, List.of(), noRandom(), fixture.pack().programs());
        return ((ObjectNode) result.contexts().getFirst().get("event")).deepCopy();
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
}
