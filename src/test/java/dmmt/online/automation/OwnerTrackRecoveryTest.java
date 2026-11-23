package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class OwnerTrackRecoveryTest {
    private static RuleCompiler.Program overflow(String operation) throws Exception {
        return RuleCompiler.compile("features:overflow", RuleJson.parse("""
                {"state":{"triggered":0},"rules":[
                 {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.change"},"clear"]},
                                  {"gt":[{"read":"event.requested"},
                                         {"subtract":[{"read":"event.before.marked"},{"read":"event.after.marked"}]}]}]},
                  "do":[{"op":"state.increment","slot":"triggered","by":1},%s],
                  "ui":{"report":"Recovered for {actor.name}."}}
                ]}
                """.formatted(operation)), fixture("rewrite/recipes"));
    }

    private static ObjectNode actor(int marked) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"id":"recipient","name":"Recipient","hope":{"value":0,"max":6},
                 "armorTrack":{"marked":%d,"capacity":6}}
                """.formatted(marked));
    }

    private static ObjectNode event() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"type":"downtime.move","actorId":"source","move":{"id":"repair"},"table":{"fear":1}}
                """);
    }

    private static RuleDispatcher.Candidate source(RuleCompiler.Program program, ObjectNode actor, ObjectNode event, long revision) {
        ObjectNode context = RuleJson.JSON.createObjectNode().put("revision", revision);
        context.putObject("source").put("id", program.sourceId()).put("instanceId", "recipient-armor").put("name", "Armor");
        context.set("actor", actor.deepCopy());
        context.set("event", event.deepCopy());
        return new RuleDispatcher.Candidate(program, context);
    }

    @Test
    void clearsAHolderWithoutSourcesAndRespectsItsProtectedFloor() throws Exception {
        ObjectNode before = actor(3), event = event();
        before.withObject("/armorTrack").put("minimumMarkedBase", 1);
        ObjectNode original = before.deepCopy();
        var result = RuleDispatcher.clearOwnerTrack(List.of(), before, event, 5, "armorTrack", 9, noRandom(), Map.of());
        assertEquals(9, result.requested());
        assertEquals(2, result.cleared());
        assertEquals(1, result.actor().at("/armorTrack/marked").asInt());
        assertEquals(6, result.revision());
        assertTrue(result.contexts().isEmpty());
        assertTrue(result.actions().isEmpty());
        assertEquals(original, before);
        assertEquals(event, result.event());

        var unchanged = RuleDispatcher.clearOwnerTrack(List.of(), result.actor(), event, result.revision(),
                "armorTrack", 4, noRandom(), Map.of());
        assertEquals(0, unchanged.cleared());
        assertEquals(6, unchanged.revision());
        result.actor().withObject("/armorTrack").put("marked", 6);
        assertEquals(1, result.actor().at("/armorTrack/marked").asInt(), "Returned snapshots are defensive copies.");
    }

    @Test
    void repeatedEmptyClearsRunTheHoldersOwnReactionsSeparately() throws Exception {
        RuleCompiler.Program program = overflow("""
                {"op":"resource.gain","pool":"actor.hope","amount":1},
                {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}
                """);
        RuleCompiler.Program listener = RuleCompiler.compile("features:listener", RuleJson.parse("""
                {"state":{"gains":0},"rules":[
                 {"id":"gain","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.track"},"hope"]},
                  "do":[{"op":"state.increment","slot":"gains","by":1}],
                  "ui":{"report":"Gained Hope."}}
                ]}
                """), fixture("rewrite/recipes"));
        ObjectNode before = actor(0), event = event();
        RuleDispatcher.Candidate initial = source(program, before, event, 4);
        ObjectNode listenerContext = source(listener, before, event, 4).context();
        listenerContext.withObject("/source").put("instanceId", "recipient-listener");
        var first = RuleDispatcher.clearOwnerTrack(List.of(initial, new RuleDispatcher.Candidate(listener, listenerContext)),
                before, event, 4, "armorTrack", 1, noRandom(),
                Map.of(program.sourceId(), program, listener.sourceId(), listener));
        assertEquals(0, first.cleared());
        assertEquals(1, first.actor().at("/hope/value").asInt());
        assertEquals(1, first.contexts().getFirst().at("/state/triggered").asInt());
        assertEquals(1, first.contexts().getLast().at("/state/gains").asInt());
        assertEquals(List.of("Gained Hope.", "Recovered for Recipient."),
                first.actions().stream().map(RuleDispatcher.ActionResult::report).toList());
        assertEquals(5, first.revision());
        assertEquals(2, first.event().at("/table/fear").asInt());
        assertEquals("source", first.event().path("actorId").asText());
        assertEquals("repair", first.event().at("/move/id").asText());
        assertEquals(1, first.outbox().getFirst().path("before").asInt());
        assertEquals(2, first.outbox().getFirst().path("after").asInt());

        RuleDispatcher.Candidate updated = new RuleDispatcher.Candidate(program, first.contexts().getFirst());
        RuleDispatcher.Candidate updatedListener = new RuleDispatcher.Candidate(listener, first.contexts().getLast());
        var second = RuleDispatcher.clearOwnerTrack(List.of(updated, updatedListener), first.actor(), first.event(),
                first.revision(), "armorTrack", 1, noRandom(),
                Map.of(program.sourceId(), program, listener.sourceId(), listener));
        assertEquals(0, second.cleared());
        assertEquals(2, second.actor().at("/hope/value").asInt());
        assertEquals(2, second.contexts().getFirst().at("/state/triggered").asInt());
        assertEquals(2, second.contexts().getLast().at("/state/gains").asInt());
        assertEquals(6, second.revision());
        assertEquals(3, second.event().at("/table/fear").asInt());
        assertEquals(0, before.at("/hope/value").asInt());
        assertEquals(1, event.at("/table/fear").asInt());
        assertFalse(initial.context().has("state"));
    }

    @Test
    void reclampsComputedActorPoolsEvenWhenNoSourceHasAReaction() throws Exception {
        RuleCompiler.Program pool = RuleCompiler.compile("features:pool", RuleJson.parse("""
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.armorTrack.marked"}}}}
                """), fixture("rewrite/recipes"));
        ObjectNode before = actor(3), event = event();
        before.putObject("pools").putObject("shared").put("value", 3).put("max", 3);
        RuleDispatcher.Candidate candidate = source(pool, before, event, 2);
        var result = RuleDispatcher.clearOwnerTrack(List.of(candidate), before, event, 2,
                "armorTrack", 2, noRandom(), Map.of(pool.sourceId(), pool));
        assertEquals(1, result.actor().at("/armorTrack/marked").asInt());
        assertEquals(1, result.actor().at("/pools/shared/value").asInt());
        assertEquals(1, result.actor().at("/pools/shared/max").asInt());
        assertEquals(3, result.revision());
        assertTrue(result.actions().isEmpty());
        assertEquals(3, before.at("/pools/shared/value").asInt());
    }

    @Test
    void rejectsMismatchedSnapshotsInvalidRequestsAndReactionFailureAtomically() throws Exception {
        RuleCompiler.Program program = overflow("{\"op\":\"table.spend\",\"resource\":\"fear\",\"amount\":2,\"label\":\"Fail\"}");
        ObjectNode before = actor(0), event = event(), original = before.deepCopy();
        RuleDispatcher.Candidate source = source(program, before, event, 1);
        var failure = assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(source), before, event, 1, "armorTrack", 1, noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("tableResource", failure.code());
        assertEquals(original, before);
        assertFalse(source.context().has("state"));
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(source), before, event, 2, "armorTrack", 1, noRandom(), Map.of(program.sourceId(), program)));
        ObjectNode staleEvent = event.deepCopy();
        staleEvent.withObject("/table").put("fear", 2);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(source), before, staleEvent, 1, "armorTrack", 1, noRandom(), Map.of(program.sourceId(), program)));
        ObjectNode other = before.deepCopy().put("id", "other");
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(source), other, event, 1, "armorTrack", 1, noRandom(), Map.of(program.sourceId(), program)));
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(), before, event, 1, "recipient.armorTrack", 1, noRandom(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(), before, event, 1, "armorTrack", -1, noRandom(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(), before, event, 1, "armorTrack", 1_000_001, noRandom(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(), before, event, -1, "armorTrack", 1, noRandom(), Map.of()));
        var unchangedAtLimit = RuleDispatcher.clearOwnerTrack(List.of(), before, event, 9_007_199_254_740_991L,
                "armorTrack", 0, noRandom(), Map.of());
        assertEquals(9_007_199_254_740_991L, unchangedAtLimit.revision());
        ObjectNode marked = actor(1);
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.clearOwnerTrack(
                List.of(), marked, event, 9_007_199_254_740_991L, "armorTrack", 1, noRandom(), Map.of()));
        assertEquals(1, marked.at("/armorTrack/marked").asInt());
    }
}
