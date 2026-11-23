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
        assertEquals("actor", effect.path("sourceActorId").asText());
    }

    @Test
    void uniqueAndReplacementStackingScopeMatchingInstancesToTheirSourceActor() throws Exception {
        ObjectNode original = context("action.request");
        original.withObject("/actor").putArray("effects").addObject().put("id", "foreign")
                .put("effect", "features:lifecycle#wildForm").put("sourceInstanceId", "inst-1")
                .put("sourceActorId", "other");
        var started = run("shift", "sheet", original, "{}").context();
        assertEquals(2, started.at("/actor/effects").size());
        assertEquals(1, started.at("/effects/wildForm").asInt());
        assertEquals("effectActive", assertThrows(RuleRuntime.Unavailable.class,
                () -> run("shift", "sheet", started.put("revision", 0), "{}")).code());
        RuleEffects.end(program(), "wildForm", "actor", started);
        assertEquals(1, started.at("/actor/effects").size());
        assertEquals("foreign", started.at("/actor/effects/0/id").asText());
        assertEquals(0, started.at("/effects/wildForm").asInt());

        ObjectNode replacement = fixture("rewrite/lifecycle");
        ((ObjectNode) replacement.at("/effects/wildForm")).put("stacking", "replace");
        var replaceProgram = RuleCompiler.compile("features:lifecycle", replacement, fixture("rewrite/recipes"));
        ObjectNode mixed = context("action.request");
        var effects = mixed.withObject("/actor").putArray("effects");
        effects.addObject().put("id", "foreign").put("effect", "features:lifecycle#wildForm")
                .put("sourceInstanceId", "inst-1").put("sourceActorId", "other");
        effects.addObject().put("id", "own").put("effect", "features:lifecycle#wildForm")
                .put("sourceInstanceId", "inst-1").put("sourceActorId", "actor");
        var replaced = RuleRuntime.execute(replaceProgram, new RuleRuntime.Intent("shift", "sheet", "inst-1", 0,
                RuleJson.JSON.createObjectNode()), mixed, noRandom()).context();
        assertEquals(2, replaced.at("/actor/effects").size());
        assertEquals("foreign", replaced.at("/actor/effects/0/id").asText());
        assertEquals("actor", replaced.at("/actor/effects/1/sourceActorId").asText());
        assertNotEquals("own", replaced.at("/actor/effects/1/id").asText());
    }

    @Test
    void singleHolderRequestsRequireStableSourceAndHolderIdentity() throws Exception {
        ObjectNode document = fixture("rewrite/lifecycle");
        ((ObjectNode) document.at("/effects/wildForm")).put("singleHolder", true);
        var single = RuleCompiler.compile("features:lifecycle", document, fixture("rewrite/recipes"));
        var intent = new RuleRuntime.Intent("shift", "sheet", "inst-1", 0, RuleJson.JSON.createObjectNode());
        ObjectNode missing = context("action.request");
        ((ObjectNode) missing.get("actor")).remove("id");
        ObjectNode before = missing.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(single, intent, missing, noRandom()));
        assertEquals(before, missing);
        var started = RuleRuntime.execute(single, intent, context("action.request"), noRandom()).context();
        assertEquals("actor", started.at("/actor/effects/0/sourceActorId").asText());
        assertEquals("actor", started.at("/outbox/0/sourceActorId").asText());
        assertEquals("actor", started.at("/outbox/0/holder").asText());

        ObjectNode recipient = context("action.request");
        recipient.putObject("recipient").put("id", "other");
        RuleEffects.start(single, "wildForm", "recipient", recipient);
        assertEquals("actor", recipient.at("/recipient/effects/0/sourceActorId").asText());
        assertEquals("other", recipient.at("/outbox/0/holder").asText());

        ObjectNode ambiguous = context("action.request");
        ambiguous.withObject("/actor").putArray("effects").addObject().put("id", "old")
                .put("effect", "features:lifecycle#wildForm").put("sourceInstanceId", "inst-1");
        ObjectNode ambiguousBefore = ambiguous.deepCopy();
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> RuleRuntime.execute(single, intent, ambiguous, noRandom()));
        assertTrue(refusal.getMessage().contains("Migrate or repair"));
        assertEquals(ambiguousBefore, ambiguous);
        assertThrows(IllegalArgumentException.class, () -> RuleEffects.end(single, "wildForm", "actor", ambiguous));
        assertEquals(ambiguousBefore, ambiguous);

        ObjectNode crossActor = context("action.request");
        crossActor.putObject("recipient").put("id", "other").putArray("effects").addObject().put("id", "old")
                .put("effect", "features:lifecycle#wildForm").put("sourceInstanceId", "inst-1");
        ObjectNode crossBefore = crossActor.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> RuleEffects.end(program(), "wildForm", "recipient", crossActor));
        assertEquals(crossBefore, crossActor);

        ((ObjectNode) document.at("/effects/wildForm")).put("endWhen", true);
        var conditional = RuleCompiler.compile("features:lifecycle", document, fixture("rewrite/recipes"));
        RuleEffects.refreshCounts(conditional, ambiguous);
        ObjectNode beforeSettlement = ambiguous.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> RuleEffects.settle(conditional, ambiguous));
        assertEquals(beforeSettlement, ambiguous);
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
