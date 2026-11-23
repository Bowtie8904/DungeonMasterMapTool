package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestOwnerReactionsTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static ObjectNode sheet() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"name":"Ada","level":1,"evasion":10,"hope":{"slots":6,"on":3},
                 "hp":{"slots":6,"on":3},"stress":{"slots":6,"on":2},"armorTrack":{"slots":3,"on":1}}
                """);
    }

    private static ObjectNode select(String source, String rule) {
        return RuleJson.JSON.createObjectNode().put("sourceInstanceId", source).put("ruleId", rule).put("bindingId", "move");
    }

    private static ObjectNode prepare(AutomationHost.Pack pack, ObjectNode sheet, String kind) {
        return HOST.prepareRest(pack, sheet, AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                kind, 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
    }

    private static ObjectNode addRules(String... rules) throws Exception {
        ObjectNode ruleset = (ObjectNode) RuleJson.parse(LegacyCatalogConverter.coreRuleset());
        ArrayNode list = ruleset.withObject("/automation").withArray("rules");
        for (String rule : rules) list.add(RuleJson.parse(rule));
        return ruleset;
    }

    private static AutomationHost.Pack pack(ObjectNode ruleset, String armorAutomation) {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"armor","entries":[
                 {"id":"plate","name":"Plate","automation":%s}]}
                """.formatted(armorAutomation);
        return AutomationHost.compile("owner-rest", Map.of("armor", catalog),
                LegacyCatalogConverter.coreRecipes(), ruleset.toString());
    }

    private static void wearArmor(ObjectNode sheet) {
        sheet.putObject("activeArmor").put("name", "Plate").put("instanceId", "plate")
                .put("automationSource", "armor:plate");
    }

    @Test
    void convertedArmorOverflowRunsForOverRepairAndPositiveZeroClears() throws Exception {
        var converted = LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"armor","entries":[
                 {"id":"plate","name":"Plate","automation":{"overflowHope":1}}]}
                """);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        var pack = AutomationHost.compile("overflow", Map.of("armor", converted.catalog().toString()),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        for (int marked : List.of(0, 1, 2)) {
            ObjectNode sheet = sheet();
            wearArmor(sheet);
            sheet.withObject("/armorTrack").put("on", marked);
            ObjectNode prepared = prepare(pack, sheet, "short");
            var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                    select("ruleset:base", "rest-repair"), RuleJson.JSON.createArrayNode(), StructuredRollTest.faces(1));
            assertEquals(0, sheet.at("/armorTrack/on").asInt());
            assertEquals(marked < 2 ? 4 : 3, sheet.at("/hope/on").asInt());
            assertEquals(marked < 2 ? List.of("overflow-hope", "rest-repair") : List.of("rest-repair"),
                    result.response().path("actions").findValuesAsText("ruleId"));
        }
    }

    @Test
    void automaticSelectedAndDeferredPhasesShareProjectedStateAndChronologicalCommands() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"practice","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"practice","kind":"any"}}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"},
                       {"op":"table.gain","resource":"fear","amount":1,"label":"Selected"}],
                 "ui":{"label":"Practice","report":"Selected evasion {result.evasion}."}}
                """, """
                {"id":"adjust","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "when":{"eq":[{"read":"event.move.id"},"practice"]},
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"table.gain","resource":"fear","amount":1,"label":"Automatic"}],
                 "after":{"do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                                  {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"},
                                  {"op":"table.gain","resource":"fear","amount":1,"label":"Deferred"}],
                          "report":"Deferred evasion {result.evasion}."},
                 "ui":{"label":"Adjust"}}
                """);
        var pack = pack(ruleset, """
                {"state":{"seen":0,"spent":0},"modifiers":[{"stat":"evasion","value":{"read":"state.seen"}}],
                 "rules":[
                  {"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.track"},"armorTrack"]},{"eq":[{"read":"event.change"},"clear"]}]},
                   "do":[{"op":"state.increment","slot":"seen","by":1},
                         {"op":"resource.gain","pool":"actor.hope","amount":1},
                         {"op":"table.gain","resource":"fear","amount":1,"label":"Overflow"}],
                   "ui":{"report":"Overflow."}},
                  {"id":"spent","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"and":[{"eq":[{"read":"event.track"},"hope"]},{"eq":[{"read":"event.change"},"spend"]}]},
                   "do":[{"op":"state.increment","slot":"spent","by":1}],"ui":{"report":"Spent."}}
                 ]}
                """);
        ObjectNode sheet = sheet();
        wearArmor(sheet);
        ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", "short").put("expectedRevision", 0);
        request.putArray("preparation");
        ObjectNode move = request.putArray("moves").addObject();
        move.set("selection", select("ruleset:base", "practice"));
        move.putArray("options");
        request.putArray("completion");
        ObjectNode result = HOST.stageSelfRest(pack, sheet,
                AutomationHost.event("action.request", "ada", 1).put("sessionId", "session"),
                request, StructuredRollTest.noRandom());
        assertEquals(1, HOST.revision(sheet));
        assertEquals(0, sheet.at("/armorTrack/on").asInt());
        assertEquals(5, sheet.at("/hope/on").asInt());
        assertEquals(3, sheet.at("/automationState/sources/plate/state/seen").asInt());
        assertEquals(1, sheet.at("/automationState/sources/plate/state/spent").asInt());
        assertEquals(List.of("armor", "adjust", "spent", "armor", "practice", "armor", "adjust"),
                result.path("actions").findValuesAsText("ruleId"));
        assertEquals("Selected evasion 12.", result.at("/actions/4/report").asText());
        assertEquals("Deferred evasion 13.", result.at("/actions/6/report").asText());
        assertEquals(List.of("Overflow", "Automatic", "Overflow", "Selected", "Overflow", "Deferred"),
                result.path("outbox").findValuesAsText("label"));
        assertEquals(7, result.at("/event/table/fear").asInt());
    }

    @Test
    void trackChangesReprojectAPreviouslyInactiveDerivedEffectWithoutListeners() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"look","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"look","kind":"any"}}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                 "ui":{"label":"Look","report":"Evasion {result.evasion}."}}
                """);
        var pack = pack(ruleset, """
                {"effects":{"ready":{"label":"Ready","while":{"eq":[{"read":"actor.armorTrack.marked"},0]},
                                     "modifiers":[{"stat":"evasion","value":2}]}}}
                """);
        ObjectNode sheet = sheet();
        wearArmor(sheet);
        ObjectNode prepared = prepare(pack, sheet, "short");
        var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", "look"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals("Evasion 12.", result.response().at("/actions/0/report").asText());
        assertEquals(0, sheet.at("/armorTrack/on").asInt());
        assertFalse(sheet.path("automationState").path("effects").elements().hasNext());
    }

    @Test
    void trackDependentStatsRefreshWithoutListenersOrPassiveEffects() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"inspect","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"inspect","kind":"any"}}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"value","value":{"read":"actor.stats.armorAvailable"},"as":"available"}],
                 "ui":{"label":"Inspect","report":"Available {result.available}; now {actor.stats.armorAvailable}."}}
                """);
        var pack = AutomationHost.compile("track-stats", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet(), prepared = prepare(pack, sheet, "short");
        var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", "inspect"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals("Available 3; now 3.", result.response().at("/actions/0/report").asText());
    }

    @Test
    void templateOnlyReadsOfTransitivelyDependentStatsAlsoReproject() throws Exception {
        ObjectNode projection;
        try (var input = AutomationHost.class.getResourceAsStream("/dmmt/online/automation/daggerheart-projection.json")) {
            assertNotNull(input);
            projection = (ObjectNode) RuleJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        projection.withArray("/stats").add(RuleJson.parse("""
                {"id":"armorPower","value":{"add":[{"read":"actor.stats.armorAvailable"},2]}}
                """));
        AutomationHost host = new AutomationHost(SheetProjection.parse(projection.toString()));
        ObjectNode ruleset = addRules("""
                {"id":"inspect","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"inspect","kind":"any"}}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1}],
                 "ui":{"label":"Inspect","report":"Power {actor.stats.armorPower}."}}
                """);
        var pack = AutomationHost.compile("dependent-stats", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        ObjectNode prepared = host.prepareRest(pack, sheet, AutomationHost.event("action.request", "ada", 1),
                "short", 0, RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        var result = host.restMove(pack, sheet, (ObjectNode) prepared.get("event"), host.restProgress(pack, prepared),
                select("ruleset:base", "inspect"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals("Power 5.", result.response().at("/actions/0/report").asText());
    }

    @Test
    void passiveSourcesRemainProjectedAndConditionalEffectsRemainGuardedWhenNotDispatched() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"inspect","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"inspect","kind":"any"}}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"value","value":{"read":"actor.stats.evasion"},"as":"evasion"}],
                 "ui":{"label":"Inspect","report":"Evasion {result.evasion}."}}
                """);
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"boost","name":"Boost","automation":{
                   "state":{"bonus":2},"modifiers":[{"stat":"evasion","value":{"read":"state.bonus"}}],
                   "effects":{"guard":{"label":"Guard","endWhen":{"lt":[{"read":"state.bonus"},1]}}}}},
                 {"id":"observer","name":"Observer","automation":{"rules":[
                  {"id":"watch","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "when":{"eq":[{"read":"event.track"},"armorTrack"]},"ui":{"report":"Observed."}}]}}
                ]}
                """;
        var pack = AutomationHost.compile("passive", Map.of("items", catalog),
                LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        ArrayNode features = sheet.putArray("features");
        features.addObject().put("name", "Boost").put("instanceId", "boost").put("automationSource", "items:boost");
        features.addObject().put("name", "Observer").put("instanceId", "observer").put("automationSource", "items:observer");
        ObjectNode state = HOST.projection().state(sheet);
        state.withObject("/sources").putObject("boost").putObject("state").put("bonus", 3);
        state.putArray("effects").addObject().put("id", "guard").put("effect", "items:boost#guard")
                .put("sourceInstanceId", "boost").put("sourceActorId", "ada");
        ObjectNode prepared = prepare(pack, sheet, "short");
        var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", "inspect"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals("Evasion 13.", result.response().at("/actions/1/report").asText());
        assertEquals(3, sheet.at("/automationState/sources/boost/state/bonus").asInt());

        ObjectNode invalid = sheet();
        invalid.set("features", features.deepCopy());
        ObjectNode invalidState = HOST.projection().state(invalid);
        invalidState.withObject("/sources").putObject("boost").putObject("state").put("bonus", 0);
        invalidState.set("effects", state.path("effects").deepCopy());
        ObjectNode invalidRest = prepare(pack, invalid, "short"), before = invalid.deepCopy();
        assertEquals("reactionEffect", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, invalid,
                (ObjectNode) invalidRest.get("event"), HOST.restProgress(pack, invalidRest),
                select("ruleset:base", "inspect"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom())).code());
        assertEquals(before, invalid);
    }

    @Test
    void nonreactiveComputedPoolSourcesRemainInTheIndexedTransaction() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"spend","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"spend","kind":"any"}}],
                 "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                 "do":[{"op":"value","value":{"read":"actor.pools.shared.value"},"as":"remaining"}],
                 "ui":{"label":"Spend","report":"Pool {result.remaining}."}}
                """);
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"pool","name":"Pool","automation":{
                   "pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.hope.value"}}}}}]}
                """;
        var pack = AutomationHost.compile("pool", Map.of("items", catalog),
                LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        sheet.putArray("features").addObject().put("name", "Pool").put("instanceId", "pool")
                .put("automationSource", "items:pool");
        HOST.projection().state(sheet).putObject("pools").putObject("shared").put("value", 3).put("max", 3);
        ObjectNode prepared = prepare(pack, sheet, "short");
        var result = HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", "spend"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
        assertEquals("Pool 2.", result.response().at("/actions/0/report").asText());
        assertEquals(2, sheet.at("/automationState/pools/shared/value").asInt());
        assertEquals(2, sheet.at("/automationState/pools/shared/max").asInt());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "dmmt.benchmark.automation", matches = "true")
    void measurePrivateSelfRestWithAndWithoutTrackReactions() throws Exception {
        String features = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"idle","name":"Idle","automation":{"rules":[
                  {"id":"idle","bindings":[{"id":"auto","event":"roll.finished","surface":"roll","mode":"automatic"}],"ui":{}}]}}]}
                """;
        String armor = """
                {"format":"dmmap-daggerheart","version":2,"category":"armor","entries":[
                 {"id":"plate","name":"Plate","automation":%s}]}
                """;
        var allocation = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        if (allocation == null || !allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("The benchmark requires JVM allocation counters.");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (boolean reactive : List.of(false, true)) {
            String automation = reactive ? """
                    {"rules":[{"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                     "when":{"eq":[{"read":"event.track"},"armorTrack"]},
                     "do":[{"op":"resource.gain","pool":"actor.hope","amount":1}],"ui":{}}]}
                    """ : """
                    {"rules":[{"id":"idle","bindings":[{"id":"auto","event":"roll.finished","surface":"roll","mode":"automatic"}],"ui":{}}]}
                    """;
            var pack = AutomationHost.compile("bench", Map.of("items", features, "armor", armor.formatted(automation)),
                    LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
            ObjectNode original = sheet();
            wearArmor(original);
            original.withObject("/armorTrack").put("on", 0);
            ArrayNode sources = original.putArray("features");
            for (int i = 0; i < 32; i++) sources.addObject().put("name", "Idle").put("instanceId", "idle-" + i)
                    .put("automationSource", "items:idle");
            ObjectNode request = RuleJson.JSON.createObjectNode().put("kind", "short").put("expectedRevision", 0);
            request.putArray("preparation");
            ObjectNode move = request.putArray("moves").addObject();
            move.set("selection", select("ruleset:base", "rest-repair"));
            move.putArray("options");
            request.putArray("completion");
            long[] nanos = new long[20], bytes = new long[20];
            for (int i = -8; i < nanos.length; i++) {
                ObjectNode current = original.deepCopy();
                long before = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
                HOST.stageSelfRest(pack, current, AutomationHost.event("action.request", "ada", 1),
                        request, StructuredRollTest.faces(1));
                long elapsed = System.nanoTime() - start, allocated = allocation.getThreadAllocatedBytes(thread) - before;
                assertEquals(reactive ? 4 : 3, current.at("/hope/on").asInt());
                if (i >= 0) { nanos[i] = elapsed; bytes[i] = allocated; }
            }
            Arrays.sort(nanos);
            System.out.printf(Locale.ROOT,
                    "automation-bench private self rest reactive=%s n=%d p50=%.2fms p95=%.2fms mean=%.2fMiB allocated%n",
                    reactive, nanos.length, nanos[nanos.length / 2 - 1] / 1e6,
                    nanos[(int) Math.ceil(nanos.length * .95) - 1] / 1e6,
                    Arrays.stream(bytes).average().orElseThrow() / (1024 * 1024));
        }
    }

    @Test
    void failuresAfterReactionAndChangesToActiveSourcesRollbackTheMove() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"blocked","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"blocked","kind":"any"}}],
                 "do":[{"op":"track.clear","track":"actor.armorTrack","amount":1},
                       {"op":"event.block","reason":"Stop."}],"ui":{"label":"Blocked"}}
                """);
        var pack = pack(ruleset, """
                {"state":{"seen":0},"rules":[
                  {"id":"armor","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                   "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{}}
                ]}
                """);
        ObjectNode sheet = sheet();
        wearArmor(sheet);
        ObjectNode prepared = prepare(pack, sheet, "short");
        ObjectNode before = sheet.deepCopy();
        var progress = HOST.restProgress(pack, prepared);
        assertEquals("blocked", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), progress, select("ruleset:base", "blocked"),
                RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);
        assertEquals(0, progress.count());

        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"camp","name":"Camp","automation":{"rules":[
                   {"id":"consume","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                     "restMove":{"id":"consume","kind":"any"}}],
                    "do":[{"op":"inventory.consume","amount":1},
                          {"op":"track.clear","track":"actor.armorTrack","amount":1}],"ui":{"label":"Consume"}},
                   {"id":"watch","bindings":[{"id":"changed","event":"track.changed","surface":"resource","mode":"automatic"}],
                    "ui":{}}
                 ]}}]}
                """;
        var consuming = AutomationHost.compile("consuming", Map.of("items", catalog),
                LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
        ObjectNode itemSheet = sheet();
        itemSheet.putArray("inventory").addObject().put("name", "Camp").put("qty", 1)
                .put("instanceId", "camp").put("automationSource", "items:camp");
        ObjectNode itemRest = prepare(consuming, itemSheet, "short"), itemBefore = itemSheet.deepCopy();
        assertEquals("reactionSource", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(consuming,
                itemSheet, (ObjectNode) itemRest.get("event"), HOST.restProgress(consuming, itemRest),
                select("camp", "consume"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom())).code());
        assertEquals(itemBefore, itemSheet);
    }

    @Test
    void addingASourceBearingCollectionIsGuardedEvenWithoutAnExistingListener() throws Exception {
        ObjectNode ruleset = addRules("""
                {"id":"add","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"button",
                  "restMove":{"id":"add","kind":"any"}}],
                 "do":[{"op":"collection.add","target":"actor","collection":"features",
                        "item":{"literal":{"id":"new","label":"New"}}},
                       {"op":"track.clear","track":"actor.armorTrack","amount":1}],"ui":{"label":"Add"}}
                """);
        var pack = AutomationHost.compile("addition", Map.of(), LegacyCatalogConverter.coreRecipes(), ruleset.toString());
        ObjectNode sheet = sheet();
        ObjectNode prepared = prepare(pack, sheet, "short"), before = sheet.deepCopy();
        assertEquals("reactionSource", assertThrows(RuleRuntime.Unavailable.class, () -> HOST.restMove(pack, sheet,
                (ObjectNode) prepared.get("event"), HOST.restProgress(pack, prepared),
                select("ruleset:base", "add"), RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom())).code());
        assertEquals(before, sheet);
    }
}
