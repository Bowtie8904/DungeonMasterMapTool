package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class HolderReactionProjectionTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static RuleCompiler.Program program(String id, String automation) throws Exception {
        return RuleCompiler.compile(id, RuleJson.parse(automation), fixture("rewrite/recipes"));
    }

    private static ObjectNode sheet(String source) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Mira","evasion":10,"hope":{"on":0,"slots":6},
                 "armorTrack":{"on":1,"slots":3},
                 "features":[{"name":"Feature","instanceId":"feature","automationSource":"%s"}],
                 "automationState":{"revision":2}}
                """.formatted(source));
    }

    private static ObjectNode recover(AutomationHost.Pack pack, ObjectNode sheet) {
        return HOST.stageHolderRecovery(pack, sheet, AutomationHost.event("downtime.move", "ada", 0),
                "mira", "armorTrack", 1, noRandom());
    }

    @Test
    void reactionsSeeUpdatedStatsDerivedEffectsAndSourceStateBetweenSteps() throws Exception {
        RuleCompiler.Program program = program("features:ready", """
                {"state":{"bonus":0,"seen":0},
                 "modifiers":[{"stat":"evasion","value":{"read":"state.bonus"}}],
                 "effects":{"ready":{"label":"Ready","while":{"eq":[{"read":"actor.armorTrack.marked"},0]},
                                      "modifiers":[{"stat":"evasion","value":2}]}},
                 "rules":[
                  {"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},
                                  {"eq":[{"read":"actor.stats.armorAvailable"},3]},
                                  {"eq":[{"read":"actor.stats.evasion"},12]},
                                  {"eq":[{"read":"effects.ready"},1]}]},
                   "do":[{"op":"state.increment","slot":"bonus","by":1},
                         {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"},
                         {"op":"resource.gain","pool":"actor.hope","amount":1}],
                   "ui":{"report":"Evasion {result.evasion}."}},
                  {"id":"hope","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},
                                  {"eq":[{"read":"actor.stats.evasion"},13]}]},
                   "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{"report":"Hope."}}
                 ]}
                """);
        var pack = new AutomationHost.Pack("reaction", Map.of(program.sourceId(), program));
        ObjectNode sheet = sheet(program.sourceId());
        ObjectNode result = recover(pack, sheet);
        assertEquals(1, result.path("cleared").asInt());
        assertEquals("Hope.", result.at("/actions/0/report").asText());
        assertEquals("Evasion 13.", result.at("/actions/1/report").asText());
        assertEquals(0, sheet.at("/armorTrack/on").asInt());
        assertEquals(1, sheet.at("/hope/on").asInt());
        assertEquals(1, sheet.at("/automationState/sources/feature/state/bonus").asInt());
        assertEquals(1, sheet.at("/automationState/sources/feature/state/seen").asInt());
        assertEquals(3, HOST.revision(sheet));
        assertFalse(sheet.path("automationState").path("effects").elements().hasNext(),
                "Derived effects are not persisted.");
    }

    @Test
    void sourcePoolChangesReprojectDerivedEffectsBeforeLaterSteps() throws Exception {
        RuleCompiler.Program program = program("features:pool", """
                {"pools":{"tokens":{"kind":"number","scope":"source","max":3}},
                 "effects":{"ready":{"label":"Ready","while":{"gt":[{"read":"sourcePools.tokens.value"},0]},
                                      "modifiers":[{"stat":"evasion","value":2}]}},
                 "rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.track"},"armorTrack"]},
                   "do":[{"op":"pool.gain","pool":"tokens","amount":1},
                         {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                   "ui":{"report":"Evasion {result.evasion}."}}]}
                """);
        var pack = new AutomationHost.Pack("pool", Map.of(program.sourceId(), program));
        ObjectNode sheet = sheet(program.sourceId());
        ObjectNode result = recover(pack, sheet);
        assertEquals("Evasion 12.", result.at("/actions/0/report").asText());
        assertEquals(1, sheet.at("/automationState/sources/feature/sourcePools/tokens/value").asInt());
    }

    @Test
    void nestedReactionsReprojectAllSourcesCurrentState() throws Exception {
        RuleCompiler.Program first = program("features:first", """
                {"state":{"bonus":0},"modifiers":[{"stat":"evasion","value":{"read":"state.bonus"}}],
                 "rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.track"},"armorTrack"]},
                   "do":[{"op":"state.increment","slot":"bonus","by":1},
                         {"op":"resource.gain","pool":"actor.hope","amount":1},
                         {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                   "ui":{"report":"Evasion {result.evasion}."}}]}
                """);
        RuleCompiler.Program second = program("features:second", """
                {"state":{"bonus":0},"modifiers":[{"stat":"evasion","value":{"read":"state.bonus"}}],
                 "rules":[{"id":"hope","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},
                                  {"eq":[{"read":"actor.stats.evasion"},11]}]},
                   "do":[{"op":"state.increment","slot":"bonus","by":2}],"ui":{"report":"Hope."}}]}
                """);
        ObjectNode sheet = sheet(first.sourceId());
        sheet.withArray("/features").addObject().put("name", "Second").put("instanceId", "second")
                .put("automationSource", second.sourceId());
        var pack = new AutomationHost.Pack("nested", Map.of(first.sourceId(), first, second.sourceId(), second));
        ObjectNode result = recover(pack, sheet);
        assertEquals("Hope.", result.at("/actions/0/report").asText());
        assertEquals("Evasion 13.", result.at("/actions/1/report").asText());
        assertEquals(1, sheet.at("/automationState/sources/feature/state/bonus").asInt());
        assertEquals(2, sheet.at("/automationState/sources/second/state/bonus").asInt());
    }

    @Test
    void localLimitMutationsAreVisibleToNestedReactions() throws Exception {
        for (String operation : new String[]{
                "{\"op\":\"limit.reset\",\"limit\":\"once\"}",
                "{\"op\":\"limit.modify\",\"limit\":\"once\",\"by\":1}"}) {
            RuleCompiler.Program program = program("features:limits", """
                    {"state":{"seen":0},"limits":{"once":{"max":1,"resetOn":["rest.short"]}},
                     "rules":[
                      {"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                       "when":{"eq":[{"read":"event.track"},"armorTrack"]},
                       "do":[%s,{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}},
                      {"id":"hope","limitRef":"once",
                       "bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                       "when":{"eq":[{"read":"event.track"},"hope"]},
                       "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{"report":"Refreshed."}}
                     ]}
                    """.formatted(operation));
            ObjectNode sheet = sheet(program.sourceId());
            sheet.withObject("/automationState").putObject("sources").putObject("feature")
                    .putObject("usage").put("once", 1);
            var pack = new AutomationHost.Pack("limits", Map.of(program.sourceId(), program));
            ObjectNode result = recover(pack, sheet);
            assertEquals("Refreshed.", result.at("/actions/0/report").asText());
            assertEquals(1, sheet.at("/automationState/sources/feature/state/seen").asInt());
            assertEquals(1, sheet.at("/automationState/sources/feature/usage/once").asInt());
        }
    }

    @Test
    void deferredLimitRefreshCannotLeaveNestedReactionsWithStaleBudgets() throws Exception {
        RuleCompiler.Program program = program("features:refresh", """
                {"rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                  "do":[{"op":"limits.refresh","on":["rest.short"],"label":"Refresh"},
                        {"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}}]}
                """);
        ObjectNode sheet = sheet(program.sourceId()), before = sheet.deepCopy();
        var pack = new AutomationHost.Pack("refresh", Map.of(program.sourceId(), program));
        assertEquals("reactionLimits", assertThrows(RuleRuntime.Unavailable.class, () -> recover(pack, sheet)).code());
        assertEquals(before, sheet);
    }

    @Test
    void deferredHostChangesCannotLeaveLaterReactionStepsWithStaleFacts() throws Exception {
        for (String operation : new String[]{
                "{\"op\":\"limit.restore\",\"to\":\"actor\",\"label\":\"Restore\"}",
                "{\"op\":\"inventory.grant\",\"catalog\":\"items\",\"entry\":\"potion\",\"quantity\":1,\"label\":\"Grant\"}",
                "{\"op\":\"damage.deal\",\"to\":\"actor\",\"amount\":1,\"label\":\"Damage\"}"}) {
            RuleCompiler.Program program = program("features:command", """
                    {"rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                      "do":[%s,{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}}]}
                    """.formatted(operation));
            ObjectNode sheet = sheet(program.sourceId()), before = sheet.deepCopy();
            var pack = new AutomationHost.Pack("command", Map.of(program.sourceId(), program));
            assertEquals("reactionCommand", assertThrows(RuleRuntime.Unavailable.class, () -> recover(pack, sheet)).code());
            assertEquals(before, sheet);
        }
    }

    @Test
    void conditionalEndingsAndUnresolvableEffectOwnersFailWithoutChanges() throws Exception {
        RuleCompiler.Program program = program("features:effect", """
                {"effects":{"guard":{"label":"Guard","endWhen":{"eq":[{"read":"actor.armorTrack.marked"},0]}}},
                 "rules":[{"id":"watch","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "ui":{}}]}
                """);
        var pack = new AutomationHost.Pack("effect", Map.of(program.sourceId(), program));
        ObjectNode sheet = sheet(program.sourceId());
        sheet.withObject("/automationState").putArray("effects").addObject()
                .put("id", "guard").put("effect", "features:effect#guard")
                .put("sourceInstanceId", "feature").put("sourceActorId", "mira");
        ObjectNode before = sheet.deepCopy();
        assertEquals("reactionEffect", assertThrows(RuleRuntime.Unavailable.class, () -> recover(pack, sheet)).code());
        assertEquals(before, sheet);

        ((ObjectNode) sheet.at("/automationState/effects/0")).put("sourceActorId", "someone-else");
        before = sheet.deepCopy();
        assertEquals("reactionEffect", assertThrows(RuleRuntime.Unavailable.class, () -> recover(pack, sheet)).code());
        assertEquals(before, sheet);
    }

    @Test
    void holderEffectChangesReprojectBeforeLaterReactionSteps() throws Exception {
        RuleCompiler.Program effect = program("features:effect", """
                {"effects":{"guard":{"label":"Guard","modifiers":[{"stat":"evasion","value":2}]}},
                 "rules":[{"id":"start","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "do":[{"op":"effect.start","effect":"guard","target":"actor"},
                                 {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                           "ui":{"report":"Evasion {result.evasion}."}}]}
                """);
        ObjectNode sheet = sheet(effect.sourceId());
        var effectPack = new AutomationHost.Pack("effect", Map.of(effect.sourceId(), effect));
        assertEquals("Evasion 12.", recover(effectPack, sheet).at("/actions/0/report").asText());
        assertEquals("mira", sheet.at("/automationState/effects/0/sourceActorId").asText());
        assertEquals("feature", sheet.at("/automationState/effects/0/sourceInstanceId").asText());
    }

    @Test
    void endingAnEffectSettlesBoundPoolsBeforeLaterReactionSteps() throws Exception {
        RuleCompiler.Program program = program("features:ward", """
                {"pools":{"reserve":{"kind":"number","scope":"actor","max":3,"whileEffect":"guard"}},
                 "effects":{"guard":{"label":"Guard","modifiers":[{"stat":"evasion","value":2}]}},
                 "rules":[{"id":"end","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "do":[{"op":"effect.end","effect":"guard","target":"actor"},
                                 {"op":"value","value":{"read":"actor.pools.reserve.value"},"as":"reserve"},
                                 {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                           "ui":{"report":"Pool {result.reserve}, Evasion {result.evasion}."}}]}
                """);
        ObjectNode sheet = sheet(program.sourceId());
        ObjectNode state = sheet.withObject("/automationState");
        state.putObject("pools").putObject("reserve").put("value", 3).put("max", 3);
        state.putArray("effects").addObject().put("id", "ward").put("effect", "features:ward#guard")
                .put("sourceInstanceId", "feature").put("sourceActorId", "mira").put("label", "Guard")
                .putArray("modifiers").addObject().put("stat", "evasion").put("value", 2);
        var pack = new AutomationHost.Pack("ward", Map.of(program.sourceId(), program));
        assertEquals("Pool 0, Evasion 10.", recover(pack, sheet).at("/actions/0/report").asText());
        assertEquals(0, sheet.at("/automationState/pools/reserve/value").asInt());
        assertTrue(sheet.at("/automationState/effects").isEmpty());
    }

    @Test
    void derivedEffectPoolDependenciesSettleBeforeReactionEligibilityAndSteps() throws Exception {
        RuleCompiler.Program program = program("features:derived", """
                {"pools":{"first":{"kind":"number","scope":"actor","max":3,"whileEffect":"armor"},
                          "second":{"kind":"number","scope":"actor","max":3,"whileEffect":"charged"}},
                 "effects":{"armor":{"label":"Armor","while":{"gt":[{"read":"actor.armorTrack.marked"},0]}},
                            "charged":{"label":"Charged","while":{"gt":[{"read":"actor.pools.first.value"},0]}}},
                 "rules":[{"id":"observe","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "when":{"and":[{"eq":[{"read":"actor.pools.first.value"},0]},
                                          {"eq":[{"read":"actor.pools.second.value"},0]},
                                          {"eq":[{"read":"effects.armor"},0]},
                                          {"eq":[{"read":"effects.charged"},0]}]},
                           "ui":{"report":"Both pools settled."}}]}
                """);
        ObjectNode sheet = sheet(program.sourceId());
        ObjectNode pools = sheet.withObject("/automationState").putObject("pools");
        pools.putObject("first").put("value", 2).put("max", 3);
        pools.putObject("second").put("value", 2).put("max", 3);
        var pack = new AutomationHost.Pack("derived", Map.of(program.sourceId(), program));
        ObjectNode result = recover(pack, sheet);
        assertEquals("Both pools settled.", result.at("/actions/0/report").asText());
        assertEquals(0, sheet.at("/automationState/pools/first/value").asInt());
        assertEquals(0, sheet.at("/automationState/pools/second/value").asInt());
        assertTrue(sheet.path("automationState").path("effects").isMissingNode()
                || sheet.path("automationState").path("effects").isEmpty());
    }

    @Test
    void stateChangesSettleDerivedEffectPoolsBeforeLaterOperations() throws Exception {
        RuleCompiler.Program program = program("features:derived", """
                {"state":{"enabled":1},
                 "pools":{"reserve":{"kind":"number","scope":"actor","max":3,"whileEffect":"ready"}},
                 "effects":{"ready":{"label":"Ready","while":{"eq":[{"read":"state.enabled"},1]}}},
                 "rules":[{"id":"change","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "do":[{"op":"state.set","slot":"enabled","value":0},
                                 {"op":"value","value":{"read":"actor.pools.reserve.value"},"as":"reserve"}],
                           "ui":{"report":"Pool {result.reserve}."}}]}
                """);
        ObjectNode sheet = sheet(program.sourceId());
        sheet.withObject("/automationState").putObject("pools").putObject("reserve").put("value", 2).put("max", 3);
        var pack = new AutomationHost.Pack("derived", Map.of(program.sourceId(), program));
        assertEquals("Pool 0.", recover(pack, sheet).at("/actions/0/report").asText());
        assertEquals(0, sheet.at("/automationState/pools/reserve/value").asInt());
    }

    @Test
    void derivedEffectPoolLossIsPersistedEvenWithoutAnAutomaticRule() throws Exception {
        RuleCompiler.Program program = program("features:derived", """
                {"pools":{"reserve":{"kind":"number","scope":"actor","max":3,"whileEffect":"ready"}},
                 "effects":{"ready":{"label":"Ready","while":{"gt":[{"read":"actor.armorTrack.marked"},0]}}}}
                """);
        ObjectNode sheet = sheet(program.sourceId());
        sheet.withObject("/automationState").putObject("pools").putObject("reserve").put("value", 2).put("max", 3);
        var pack = new AutomationHost.Pack("derived", Map.of(program.sourceId(), program));
        ObjectNode result = recover(pack, sheet);
        assertTrue(result.path("actions").isEmpty());
        assertEquals(0, sheet.at("/armorTrack/on").asInt());
        assertEquals(0, sheet.at("/automationState/pools/reserve/value").asInt());
        assertEquals(3, HOST.revision(sheet));
    }

    @Test
    void anotherSourcesEffectPreservesTheSharedPoolWhenOneEffectEnds() throws Exception {
        RuleCompiler.Program first = program("features:first", """
                {"pools":{"reserve":{"kind":"number","scope":"actor","max":3,"whileEffect":"guard"}},
                 "effects":{"guard":{"label":"Guard"}},
                 "rules":[{"id":"end","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "do":[{"op":"effect.end","effect":"guard","target":"actor"},
                                 {"op":"value","value":{"read":"actor.pools.reserve.value"},"as":"reserve"}],
                           "ui":{"report":"Pool {result.reserve}."}}]}
                """);
        RuleCompiler.Program second = program("features:second", """
                {"pools":{"reserve":{"kind":"number","scope":"actor","max":3,"whileEffect":"shield"}},
                 "effects":{"shield":{"label":"Shield","while":true}}}
                """);
        ObjectNode sheet = sheet(first.sourceId());
        sheet.withArray("/features").addObject().put("name", "Second").put("instanceId", "second")
                .put("automationSource", second.sourceId());
        ObjectNode state = sheet.withObject("/automationState");
        state.putObject("pools").putObject("reserve").put("value", 2).put("max", 3);
        state.putArray("effects").addObject().put("id", "ward").put("effect", "features:first#guard")
                .put("sourceInstanceId", "feature").put("sourceActorId", "mira").put("label", "Guard");
        var pack = new AutomationHost.Pack("shared", Map.of(first.sourceId(), first, second.sourceId(), second));
        assertEquals("Pool 2.", recover(pack, sheet).at("/actions/0/report").asText());
        assertEquals(2, sheet.at("/automationState/pools/reserve/value").asInt());
    }

    @Test
    void crossHolderCleanupAndUnsettledComputedSourcePoolsFailAtomically() throws Exception {
        RuleCompiler.Program effect = program("features:effect", """
                {"effects":{"guard":{"label":"Guard","singleHolder":true}},
                 "rules":[{"id":"start","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                           "do":[{"op":"effect.start","effect":"guard","target":"actor"}],"ui":{}}]}
                """);
        ObjectNode sheet = sheet(effect.sourceId()), before = sheet.deepCopy();
        var effectPack = new AutomationHost.Pack("effect", Map.of(effect.sourceId(), effect));
        assertEquals("reactionEffect", assertThrows(RuleRuntime.Unavailable.class, () -> recover(effectPack, sheet)).code());
        assertEquals(before, sheet);

        RuleCompiler.Program pool = program("features:pool", """
                {"pools":{"tokens":{"kind":"number","scope":"source","max":{"read":"actor.stats.armorAvailable"}}}}
                """);
        ObjectNode poolSheet = sheet(pool.sourceId()), poolBefore = poolSheet.deepCopy();
        var poolPack = new AutomationHost.Pack("pool", Map.of(pool.sourceId(), pool));
        assertEquals("reactionSourcePool", assertThrows(RuleRuntime.Unavailable.class, () -> recover(poolPack, poolSheet)).code());
        assertEquals(poolBefore, poolSheet);
    }

    @Test
    void computedSourcePoolsCannotUseStaleTrackOrStateDuringAReaction() throws Exception {
        for (String max : new String[]{"{\"read\":\"actor.hope.value\"}", "{\"read\":\"state.cap\"}"}) {
            RuleCompiler.Program pool = program("features:pool", """
                    {"state":{"cap":2},"pools":{"tokens":{"kind":"number","scope":"source","max":%s}},
                     "rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                       "do":[{"op":"state.increment","slot":"cap","by":1},
                             {"op":"resource.gain","pool":"actor.hope","amount":1},
                             {"op":"value","value":{"read":"sourcePools.tokens.max"},"as":"maximum"}],
                       "ui":{"report":"Maximum {result.maximum}."}}]}
                    """.formatted(max));
            ObjectNode sheet = sheet(pool.sourceId()), before = sheet.deepCopy();
            var pack = new AutomationHost.Pack("pool", Map.of(pool.sourceId(), pool));
            assertEquals("reactionSourcePool", assertThrows(RuleRuntime.Unavailable.class, () -> recover(pack, sheet)).code());
            assertEquals(before, sheet);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "dmmt.benchmark.automation", matches = "true")
    void measureSheetBackedRecovery() throws Exception {
        RuleCompiler.Program reactive = program("features:reactive", """
                {"state":{"seen":0},"modifiers":[{"stat":"evasion","value":{"read":"state.seen"}}],
                 "rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.track"},"armorTrack"]},
                   "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{}}]}
                """);
        RuleCompiler.Program passive = program("features:passive", """
                {"modifiers":[{"stat":"evasion","value":1}]}
                """);
        ObjectNode original = sheet(reactive.sourceId());
        for (int i = 0; i < 32; i++)
            original.withArray("/features").addObject().put("name", "Passive " + i)
                    .put("instanceId", "passive-" + i).put("automationSource", passive.sourceId());
        var pack = new AutomationHost.Pack("benchmark", Map.of(reactive.sourceId(), reactive, passive.sourceId(), passive));
        var allocation = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (allocation == null || !allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("The benchmark requires JVM allocation counters.");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long[] nanos = new long[20], bytes = new long[20];
        long thread = Thread.currentThread().threadId();
        for (int i = -10; i < nanos.length; i++) {
            ObjectNode sheet = original.deepCopy();
            long before = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
            ObjectNode result = recover(pack, sheet);
            long elapsed = System.nanoTime() - start, allocated = allocation.getThreadAllocatedBytes(thread) - before;
            assertEquals(1, result.path("cleared").asInt());
            assertEquals(1, sheet.at("/automationState/sources/feature/state/seen").asInt());
            if (i >= 0) { nanos[i] = elapsed; bytes[i] = allocated; }
        }
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT,
                "automation-bench sheet recovery: n=%d p50=%.2fms p95=%.2fms mean=%.2fMiB allocated%n",
                nanos.length, nanos[nanos.length / 2 - 1] / 1e6,
                nanos[(int) Math.ceil(nanos.length * .95) - 1] / 1e6,
                Arrays.stream(bytes).average().orElseThrow() / (1024 * 1024));
    }
}
