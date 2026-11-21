package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleRuntimeTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:lifecycle", fixture("rewrite/lifecycle"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(String event) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:lifecycle","instanceId":"inst-1","name":"Grove"},
                 "actor":{"id":"actor","name":"Player","proficiency":2,"stress":{"marked":6,"capacity":6},
                          "vault":[{"id":"card-b","quantity":1}],"loadout":[{"id":"card-a"}]},
                 "revision":0,"event":{"type":"%s","offers":[{"id":"card-b","label":"B"},{"id":"card-c","label":"C"}]}}
                """.formatted(event));
    }

    private static RuleRuntime.Resolution run(String rule, String binding, ObjectNode context, String inputs) throws Exception {
        return RuleRuntime.execute(program(), new RuleRuntime.Intent(rule, binding, "inst-1", 0,
                (ObjectNode) RuleJson.parse(inputs)), context, noRandom());
    }

    @Test
    void downtimeMovesChangeTrackCapacityAndMergeCollectionEntries() throws Exception {
        assertEquals("Fortify (6 stress)", RuleRuntime.describe(program(), "fortify", "rest", context("downtime.move")).path("label").asText());
        var result = run("fortify", "rest", context("downtime.move"), "{\"card\":\"card-b\"}");
        assertEquals("Gained 1 stress slot and card-b", result.report());
        assertEquals(7, result.context().path("actor").path("stress").path("capacity").asInt());
        assertEquals(2, result.context().path("actor").path("vault").get(0).path("quantity").asInt());
        var added = run("fortify", "rest", context("downtime.move"), "{\"card\":\"card-c\"}");
        JsonNode card = added.context().path("actor").path("vault").get(1);
        assertEquals("card-c", card.path("id").asText());
        assertEquals("inst-1", card.path("grantedBy").asText());
        ObjectNode spent = result.context().put("revision", 0);
        assertEquals("limit", assertThrows(RuleRuntime.Unavailable.class,
                () -> run("fortify", "rest", spent, "{\"card\":\"card-c\"}")).code());
    }

    @Test
    void collectionRemovalReportsMissingEntries() throws Exception {
        var result = run("stow", "sheet", context("action.request"), "{}");
        assertEquals("Stowed card-a", result.report());
        assertEquals(0, result.context().path("actor").path("loadout").size());
        assertEquals("missing", assertThrows(RuleRuntime.Unavailable.class,
                () -> run("stow", "sheet", result.context().put("revision", 0), "{}")).code());
    }

    @Test
    void limitRefundsAreBoundedAndHostRequestsGoToTheOutbox() throws Exception {
        ObjectNode context = context("death.options");
        context.putObject("usage").put("once", 1);
        assertEquals("party", RuleRuntime.describe(program(), "refresh", "death", context).path("audience").asText());
        var result = run("refresh", "death", context, "{}");
        assertEquals("Restored 1 use", result.report());
        assertEquals(0, result.context().path("usage").path("once").asInt());
        JsonNode outbox = result.context().path("outbox");
        assertEquals("{\"kind\":\"damage.deal\",\"sourceInstanceId\":\"inst-1\",\"label\":\"Backlash from Grove\",\"to\":\"attacker\",\"amount\":2}",
                outbox.get(0).toString());
        assertEquals(3, outbox.get(1).path("value").asInt());
    }

    @Test
    void effectsFreezeAlternateAttackProfiles() throws Exception {
        var result = run("shift", "sheet", context("action.request"), "{}");
        JsonNode effect = result.context().path("actor").path("effects").get(0);
        assertEquals("Claws", effect.path("profile").path("label").asText());
        assertEquals(8, effect.path("profile").path("damage").path("sides").asInt());
    }

    @Test
    void lifecycleEventsDispatchAutomaticRules() throws Exception {
        ObjectNode context = context("track.changed");
        context.withObject("/event").put("track", "stress");
        var resolution = RuleDispatcher.executeEvent(java.util.List.of(new RuleDispatcher.Candidate(program(), context)),
                java.util.List.of(), noRandom());
        assertEquals(1, resolution.actions().size());
    }

    @Test
    void compilerRejectsUnknownCollectionsAudiencesAndProfiles() throws Exception {
        String rule = """
                {%s"rules":[{"id":"x","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"%s}],
                 "ui":{"label":"X"},"do":[%s]}]}""";
        for (String[] bad : new String[][]{
                {"", "", "{\"op\":\"collection.add\",\"target\":\"actor\",\"collection\":\"secrets\",\"item\":{\"literal\":{\"id\":\"a\"}}}"},
                {"", ",\"audience\":\"everyone\"", "{\"op\":\"value\",\"value\":1}"},
                {"", "", "{\"op\":\"damage.deal\",\"to\":\"gm\",\"amount\":1,\"label\":\"x\"}"},
                {"\"effects\":{\"f\":{\"label\":\"F\",\"profile\":{\"label\":\"P\",\"trait\":\"Luck\"}}},", "", "{\"op\":\"value\",\"value\":1}"},
                {"", "", "{\"op\":\"value\",\"value\":{\"object\":{\"read\":1}}}"}}) {
            assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:bad",
                    RuleJson.parse(rule.formatted(bad[0], bad[1], bad[2])), fixture("rewrite/recipes")), bad[2]);
        }
    }
}
