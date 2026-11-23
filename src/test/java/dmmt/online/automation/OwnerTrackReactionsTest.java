package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class OwnerTrackReactionsTest {
    private static RuleCompiler.Program program(String id, String automation) throws Exception {
        return RuleCompiler.compile(id, RuleJson.parse(automation), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String id, String instance) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"%s","name":"Source"},
                 "actor":{"id":"actor","name":"Actor","hope":{"value":3,"max":6},
                          "armorTrack":{"marked":1,"capacity":3},"stress":{"marked":0,"capacity":6}},
                 "event":{"type":"action.request","origin":"retained","table":{"fear":2}},
                 "revision":0}
                """.formatted(id, instance));
    }

    private static RuleRuntime.Intent select(String rule, String instance) {
        return new RuleRuntime.Intent(rule, "use", instance, 0, RuleJson.JSON.createObjectNode());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "dmmt.benchmark.automation", matches = "true")
    void measureManySourceTrackReactions() throws Exception {
        RuleCompiler.Program active = program("features:active", """
                {"state":{"seen":0},"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"track.clear","track":"actor.armorTrack","amount":1}],"ui":{"label":"Use"}},
                 {"id":"observe","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{}}
                ]}
                """);
        RuleCompiler.Program idle = program("features:idle", """
                {"rules":[{"id":"idle","bindings":[{"id":"auto","event":"roll.finished","surface":"roll","mode":"automatic"}],"ui":{}}]}
                """);
        List<RuleDispatcher.Candidate> sources = new java.util.ArrayList<>();
        sources.add(new RuleDispatcher.Candidate(active, context(active.sourceId(), "active")));
        for (int i = 0; i < 64; i++)
            sources.add(new RuleDispatcher.Candidate(idle, context(idle.sourceId(), "idle-" + i)));
        var programs = Map.of(active.sourceId(), active, idle.sourceId(), idle);
        var allocation = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (allocation == null || !allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("The benchmark requires JVM allocation counters.");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long[] nanos = new long[20], bytes = new long[20];
        long thread = Thread.currentThread().threadId();
        for (int i = -10; i < nanos.length; i++) {
            long before = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
            var result = RuleDispatcher.executeOwnerTrackReactions(sources, List.of(select("use", "active")), noRandom(), programs);
            long elapsed = System.nanoTime() - start, allocated = allocation.getThreadAllocatedBytes(thread) - before;
            assertEquals(4, result.contexts().getFirst().at("/state/seen").asInt());
            if (i >= 0) { nanos[i] = elapsed; bytes[i] = allocated; }
        }
        java.util.Arrays.sort(nanos);
        System.out.printf(java.util.Locale.ROOT,
                "automation-bench isolated track: n=%d p50=%.2fms p95=%.2fms mean=%.2fMiB allocated%n",
                nanos.length, nanos[nanos.length / 2 - 1] / 1e6, nanos[(int) Math.ceil(nanos.length * .95) - 1] / 1e6,
                java.util.Arrays.stream(bytes).average().orElseThrow() / (1024 * 1024));
    }

    @Test
    void aNonreactiveSourcesComputedActorPoolStillClampsBeforeReactionAndLaterSteps() throws Exception {
        RuleCompiler.Program active = program("features:active", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":2}],
                  "do":[{"op":"value","value":{"read":"actor.pools.shared.value"},"as":"remaining"}],
                  "ui":{"label":"Use","report":"Pool {result.remaining}."}},
                 {"id":"unmatched","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.track"},"armorTrack"]},"ui":{}}
                ]}
                """);
        RuleCompiler.Program pool = program("features:pool", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.hope.value"}}}}
                """);
        ObjectNode first = context(active.sourceId(), "active"), second = context(pool.sourceId(), "pool");
        first.withObject("/actor").putObject("pools").putObject("shared").put("value", 3).put("max", 3);
        second.set("actor", first.path("actor").deepCopy());
        var result = RuleDispatcher.executeOwnerTrackReactions(
                List.of(new RuleDispatcher.Candidate(active, first), new RuleDispatcher.Candidate(pool, second)),
                List.of(select("use", "active")), noRandom(), Map.of(active.sourceId(), active, pool.sourceId(), pool));
        assertEquals("Pool 1.", result.actions().getLast().report());
        for (ObjectNode context : result.contexts()) {
            assertEquals(1, context.at("/actor/pools/shared/value").asInt());
            assertEquals(1, context.at("/actor/pools/shared/max").asInt());
            assertEquals(1, context.path("revision").asInt());
        }
    }

    @Test
    void selectedAndDeferredMovesSettleOwnerReactionsWithoutRedispatchingParentRules() throws Exception {
        RuleCompiler.Program move = program("features:move", """
                {"state":{"runs":0},"rules":[
                 {"id":"already","bindings":[{"id":"auto","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                  "do":[{"op":"state.increment","slot":"runs","by":100}],"ui":{}},
                 {"id":"move","bindings":[{"id":"use","event":"downtime.move","surface":"downtime","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"Before"},
                        {"op":"track.clear","track":"actor.armorTrack","amount":2},
                        {"op":"table.spend","resource":"fear","amount":1,"label":"After"},
                        {"op":"state.increment","slot":"runs","by":1}],
                  "ui":{"label":"Move","report":"Fear {event.table.fear}, Hope {actor.hope.value}."}},
                 {"id":"benefit","bindings":[{"id":"use","event":"downtime.move","surface":"downtime","mode":"option"}],
                  "when":{"eq":[{"read":"actor.hope.value"},4]},
                  "after":{"costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                           "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1}],
                           "report":"After Fear {event.table.fear}, Hope {actor.hope.value}."},
                  "ui":{"label":"Benefit"}},
                 {"id":"fail","bindings":[{"id":"use","event":"downtime.move","surface":"downtime","mode":"option"}],
                  "after":{"do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                                 {"op":"table.spend","resource":"fear","amount":99,"label":"Fail"}]},
                  "ui":{"label":"Fail"}}
                ]}
                """);
        RuleCompiler.Program reaction = program("features:reaction", """
                {"state":{"seen":0},"rules":[
                 {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},{"eq":[{"read":"event.change"},"clear"]}]},
                  "do":[{"op":"state.increment","slot":"seen","by":1},
                        {"op":"resource.gain","pool":"actor.hope","amount":1},
                        {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}],
                  "ui":{"report":"Overflow."}}
                ]}
                """);
        ObjectNode one = context(move.sourceId(), "one"), two = context(reaction.sourceId(), "two");
        one.withObject("/event").put("type", "downtime.move");
        two.set("event", one.get("event").deepCopy());
        ObjectNode originalOne = one.deepCopy(), originalTwo = two.deepCopy();
        var candidates = List.of(new RuleDispatcher.Candidate(move, one), new RuleDispatcher.Candidate(reaction, two));
        var programs = Map.of(move.sourceId(), move, reaction.sourceId(), reaction);
        var selected = RuleDispatcher.executeSelectedWithOwnerReactions(candidates, select("move", "one"), noRandom(), programs);
        assertEquals(List.of("overflow", "move"), selected.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Fear 3, Hope 3.", selected.actions().getLast().report());
        assertEquals(List.of("Before", "Overflow", "After"),
                selected.outbox().stream().map(command -> command.path("label").asText()).toList());
        assertEquals(1, selected.contexts().getFirst().at("/state/runs").asInt());
        assertEquals(1, selected.contexts().getLast().at("/state/seen").asInt());
        for (ObjectNode context : selected.contexts()) {
            assertEquals(0, context.at("/actor/armorTrack/marked").asInt());
            assertEquals(1, context.path("revision").asInt());
            assertEquals("downtime.move", context.at("/event/type").asText());
        }

        var deferred = RuleDispatcher.executeDeferredWithOwnerReactions(candidates, select("benefit", "one"), noRandom(), programs);
        assertEquals(List.of("overflow", "benefit"), deferred.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("After Fear 3, Hope 3.", deferred.actions().getLast().report());
        assertEquals(List.of("Overflow"), deferred.outbox().stream().map(command -> command.path("label").asText()).toList());
        assertEquals(1, deferred.contexts().getLast().at("/state/seen").asInt());
        assertEquals(3, deferred.contexts().getFirst().at("/actor/hope/value").asInt());
        assertEquals(1, deferred.contexts().getFirst().path("revision").asInt());
        assertEquals("tableResource", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeDeferredWithOwnerReactions(candidates, select("fail", "one"), noRandom(), programs)).code());
        assertEquals(originalOne, one);
        assertEquals(originalTwo, two);
    }

    @Test
    void deferredBenefitUsesComputedPoolsFromEveryActiveSourceBeforeItsFirstOperation() throws Exception {
        RuleCompiler.Program move = program("features:move", """
                {"rules":[{"id":"benefit","bindings":[{"id":"use","event":"downtime.move","surface":"downtime","mode":"option"}],
                 "after":{"do":[{"op":"value","value":{"read":"actor.pools.shared.value"},"as":"remaining"}],
                          "report":"Pool {result.remaining}."},"ui":{"label":"Benefit"}}]}
                """);
        RuleCompiler.Program pool = program("features:pool", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.hope.value"}}}}
                """);
        ObjectNode first = context(move.sourceId(), "move"), second = context(pool.sourceId(), "pool");
        first.withObject("/actor").putObject("pools").putObject("shared").put("value", 6).put("max", 6);
        first.withObject("/event").put("type", "downtime.move");
        second.set("actor", first.path("actor").deepCopy());
        second.set("event", first.path("event").deepCopy());
        var result = RuleDispatcher.executeDeferredWithOwnerReactions(
                List.of(new RuleDispatcher.Candidate(move, first), new RuleDispatcher.Candidate(pool, second)),
                select("benefit", "move"), noRandom(), Map.of(move.sourceId(), move, pool.sourceId(), pool));
        assertEquals("Pool 3.", result.actions().getLast().report());
        for (ObjectNode context : result.contexts()) {
            assertEquals(3, context.at("/actor/pools/shared/value").asInt());
            assertEquals(3, context.at("/actor/pools/shared/max").asInt());
        }
        assertEquals(6, first.at("/actor/pools/shared/value").asInt());
        assertEquals(6, second.at("/actor/pools/shared/value").asInt());
    }

    @Test
    void nestedSourceReactionsShareStateFearAndOneRevisionInOperationOrder() throws Exception {
        RuleCompiler.Program first = program("features:first", """
                {"state":{"steps":0},"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":3}],
                  "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"Before"},
                        {"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"table.spend","resource":"fear","amount":1,"label":"After"},
                        {"op":"state.increment","slot":"steps","by":10},
                        {"op":"value","value":{"read":"actor.hope.value"},"as":"hope"}],
                  "ui":{"label":"Use","report":"Hope {result.hope}, Fear {event.table.fear}."}},
                 {"id":"zero","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.after.value"},0]}]},
                  "do":[{"op":"state.increment","slot":"steps","by":1},
                        {"op":"track.clear","track":"actor.armorTrack","amount":2},
                        {"op":"table.gain","resource":"fear","amount":1,"label":"Zero"}],
                  "ui":{"report":"Zero."}},
                 {"id":"eligible","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"actor.hope.value"},3]},
                  "do":[{"op":"value","value":{"read":"actor.hope.value"},"as":"hope"}],
                  "ui":{"report":"Initial eligibility, current Hope {result.hope}."}},
                 {"id":"not-eligible","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"actor.hope.value"},0]},
                  "ui":{"report":"Must not run."}}
                ]}
                """);
        RuleCompiler.Program second = program("features:second", """
                {"state":{"overflows":0,"gains":0},"rules":[
                 {"id":"overflow","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.change"},"clear"]},
                                  {"gt":[{"read":"event.requested"},
                                         {"subtract":[{"read":"event.before.marked"},{"read":"event.after.marked"}]}]}]},
                  "do":[{"op":"state.increment","slot":"overflows","by":1},
                        {"op":"resource.gain","pool":"actor.hope","amount":1}],
                  "ui":{"report":"Overflow."}},
                 {"id":"gain","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"gain"]}]},
                  "do":[{"op":"state.increment","slot":"gains","by":1}],"ui":{"report":"Gain."}}
                ]}
                """);
        ObjectNode one = context(first.sourceId(), "one"), two = context(second.sourceId(), "two");
        ObjectNode originalOne = one.deepCopy(), originalTwo = two.deepCopy();
        var result = RuleDispatcher.executeOwnerTrackReactions(
                List.of(new RuleDispatcher.Candidate(first, one), new RuleDispatcher.Candidate(second, two)),
                List.of(select("use", "one")), noRandom(), Map.of(first.sourceId(), first, second.sourceId(), second));
        assertEquals(List.of("gain", "overflow", "zero", "eligible", "gain", "overflow", "use"),
                result.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Initial eligibility, current Hope 1.", result.actions().get(3).report());
        assertEquals("Hope 2, Fear 3.", result.actions().getLast().report());
        assertEquals(List.of("Zero", "Before", "After"),
                result.outbox().stream().map(command -> command.path("label").asText()).toList());
        assertEquals(List.of(2, 3, 4), result.outbox().stream().map(command -> command.path("before").asInt()).toList());
        assertEquals(List.of(3, 4, 3), result.outbox().stream().map(command -> command.path("after").asInt()).toList());
        ObjectNode outOne = result.contexts().get(0), outTwo = result.contexts().get(1);
        assertEquals(11, outOne.at("/state/steps").asInt());
        assertEquals(2, outTwo.at("/state/overflows").asInt());
        assertEquals(2, outTwo.at("/state/gains").asInt());
        for (ObjectNode context : result.contexts()) {
            assertEquals(2, context.at("/actor/hope/value").asInt());
            assertEquals(0, context.at("/actor/armorTrack/marked").asInt());
            assertEquals(1, context.path("revision").asInt());
            assertEquals("action.request", context.at("/event/type").asText());
            assertEquals("retained", context.at("/event/origin").asText());
            assertEquals(3, context.at("/event/table/fear").asInt());
        }
        assertEquals(originalOne, one);
        assertEquals(originalTwo, two);
    }

    @Test
    void nestedReactionsPropagateSharedEventChangesWithoutReplacingParentIdentity() throws Exception {
        RuleCompiler.Program program = program("features:event", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                        {"op":"value","value":{"read":"event.move.bonus"},"as":"bonus"}],
                  "ui":{"label":"Use","report":"Bonus {result.bonus}."}},
                 {"id":"reaction","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "do":[{"op":"plan.adjust","plan":"event.move","field":"bonus","by":1,"label":"Reaction"},
                        {"op":"event.block","reason":"Blocked by reaction"}],"ui":{}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "event");
        ObjectNode event = before.withObject("/event").put("actorId", "original").put("track", "original-track")
                .put("change", "original-change").put("requested", 7);
        event.putObject("before").put("value", 8);
        event.putObject("after").put("value", 9);
        event.putObject("move").put("bonus", 0).putObject("adjustable").putObject("bonus").put("min", 0).put("max", 3);
        ObjectNode original = before.deepCopy();
        var result = RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                List.of(select("use", "event")), noRandom(), Map.of(program.sourceId(), program));
        ObjectNode completed = result.contexts().getFirst().withObject("/event");
        assertEquals("Bonus 1.", result.actions().getLast().report());
        assertEquals(1, completed.at("/move/bonus").asInt());
        assertEquals("Reaction", completed.at("/move/adjustments/0/label").asText());
        assertEquals("reaction", completed.at("/move/adjustments/0/ruleId").asText());
        assertEquals("changed", completed.at("/move/adjustments/0/bindingId").asText());
        assertEquals("do", completed.at("/move/adjustments/0/phase").asText());
        assertEquals(0, completed.at("/move/adjustments/0/stepIndex").asInt());
        assertEquals("Blocked by reaction", completed.at("/blocked/0/reason").asText());
        for (String field : List.of("type", "actorId", "track", "change", "requested", "before", "after"))
            assertEquals(original.path("event").path(field), completed.path(field), field);
        assertEquals(original, before);
    }

    @Test
    void recursiveTrackChangesAreBoundedAndLeaveTheInputUntouched() throws Exception {
        RuleCompiler.Program program = program("features:loop", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Use"}},
                 {"id":"gain","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"spend"]}]},
                  "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}},
                 {"id":"spend","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"gain"]}]},
                  "do":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "loop"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "loop")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("reactionLimit", failure.code());
        assertEquals(original, before);
    }

    @Test
    void sequentialTrackEventsAlsoHaveAWholeTransactionBudget() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"do":[],"ui":{"label":"Use"}}]}
                """);
        ObjectNode rule = (ObjectNode) automation.withArray("/rules").get(0);
        for (int i = 0; i < 128; i++) rule.withArray("/do")
                .addObject().put("op", "track.clear").put("track", "actor.armorTrack").put("amount", 1);
        RuleCompiler.Program program = RuleCompiler.compile("features:budget", automation, fixture("rewrite/recipes"));
        ObjectNode before = context(program.sourceId(), "budget"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "budget")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("reactionLimit", failure.code());
        assertEquals(original, before);
    }

    @Test
    void isolatedReactionsRejectProjectedSnapshotsAndChangingSources() throws Exception {
        RuleCompiler.Program track = program("features:track", """
                {"rules":[{"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1}],"ui":{"label":"Use"}}]}
                """);
        ObjectNode projected = context(track.sourceId(), "track");
        projected.withObject("/actor").putObject("stats").put("armorAvailable", 2);
        ObjectNode original = projected.deepCopy();
        assertEquals("reactionProjection", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(track, projected)),
                        List.of(select("use", "track")), noRandom(), Map.of(track.sourceId(), track))).code());
        assertEquals(original, projected);

        RuleCompiler.Program item = program("items:item", """
                {"rules":[{"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"inventory.consume","amount":1}],"ui":{"label":"Use"}}]}
                """);
        ObjectNode consumed = context(item.sourceId(), "item");
        consumed.withObject("/source").put("quantity", 1);
        ObjectNode before = consumed.deepCopy();
        assertEquals("reactionSource", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(item, consumed)),
                        List.of(select("use", "item")), noRandom(), Map.of(item.sourceId(), item))).code());
        assertEquals(before, consumed);
    }

    @Test
    void isolatedReactionsRejectComputedSourcePoolsBeforeReadingStaleValues() throws Exception {
        RuleCompiler.Program program = program("features:pool", """
                {"pools":{"tokens":{"kind":"number","scope":"source","max":{"read":"actor.hope.value"}}},
                 "rules":[{"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                   "do":[{"op":"resource.spend","pool":"actor.hope","amount":1},
                         {"op":"value","value":{"read":"sourcePools.tokens.max"},"as":"maximum"}],
                   "ui":{"label":"Use","report":"Maximum {result.maximum}."}}]}
                """);
        ObjectNode before = context(program.sourceId(), "pool"), original = before.deepCopy();
        assertEquals("reactionSourcePool", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "pool")), noRandom(), Map.of(program.sourceId(), program))).code());
        assertEquals(original, before);
    }

    @Test
    void armorReactionSettlesBeforeDamageMarksHitPoints() throws Exception {
        RuleCompiler.Program program = program("features:damage", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"damage.resolve","from":{"read":"event.damage"},
                    "thresholds":{"majorAt":5,"severeAt":10},
                    "armor":{"track":"actor.armorTrack","marked":"marked","capacity":"capacity","spend":1,"severitySteps":1},
                    "hitPoints":{"track":"actor.hp","marked":"marked","capacity":"capacity","minor":1,"major":2,"severe":3},
                    "as":"damage"}],
                  "ui":{"label":"Use","report":"Marked {result.damage.hitPoints.applied} HP."}},
                 {"id":"armor-full","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"event.after.marked"},3]}]},
                  "do":[{"op":"track.mark","track":"actor.hp","amount":5}],"ui":{"report":"Armor reaction."}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "damage");
        before.withObject("/actor/armorTrack").put("marked", 2);
        before.withObject("/actor").putObject("hp").put("marked", 0).put("capacity", 6);
        before.withObject("/event").putObject("damage").put("id", "damage").put("stage", "preSeverity")
                .put("baseAmount", 12).put("amount", 12).putArray("adjustments");
        ObjectNode original = before.deepCopy();
        var result = RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                List.of(select("use", "damage")), noRandom(), Map.of(program.sourceId(), program));
        assertEquals(List.of("armor-full", "use"),
                result.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Marked 1 HP.", result.actions().getLast().report());
        assertEquals(6, result.contexts().getFirst().at("/actor/hp/marked").asInt());
        assertEquals(original, before);
    }

    @Test
    void failingNestedReactionDiscardsCostsAndPriorReactionChanges() throws Exception {
        RuleCompiler.Program program = program("features:failure", """
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],"ui":{"label":"Use"}},
                 {"id":"fail","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.track"},"hope"]},
                  "do":[{"op":"track.mark","track":"actor.stress","amount":1},
                        {"op":"table.spend","resource":"fear","amount":3,"label":"Too much"}],"ui":{}}
                ]}
                """);
        ObjectNode before = context(program.sourceId(), "failure"), original = before.deepCopy();
        RuleRuntime.Unavailable failure = assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeOwnerTrackReactions(List.of(new RuleDispatcher.Candidate(program, before)),
                        List.of(select("use", "failure")), noRandom(), Map.of(program.sourceId(), program)));
        assertEquals("tableResource", failure.code());
        assertEquals(original, before);
    }
}
