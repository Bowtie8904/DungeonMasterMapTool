package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class ComputedPoolSnapshotTest {
    private static RuleCompiler.Program program(String id, String json) throws Exception {
        return RuleCompiler.compile(id, RuleJson.parse(json), fixture("rewrite/recipes"));
    }

    private static RuleCompiler.Program pool() throws Exception {
        return program("features:pool", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.hope.value"}}}}
                """);
    }

    private static ObjectNode context(String id, String instance, String event) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"%s","name":"Source"},
                 "actor":{"id":"owner","name":"Ari","hope":{"value":3,"max":6},
                          "stress":{"marked":2,"capacity":6},"pools":{"shared":{"value":6,"max":6}}},
                 "event":{"type":"%s"},"revision":4}
                """.formatted(id, instance, event));
    }

    private static RuleRuntime.Intent intent(String rule, String instance) {
        return new RuleRuntime.Intent(rule, "use", instance, 4, RuleJson.JSON.createObjectNode());
    }

    @Test
    void descriptionsAndAutomaticRulesUseEveryActiveSourcesClampedActor() throws Exception {
        RuleCompiler.Program action = program("features:action", """
                {"state":{"seen":0},"rules":[
                 {"id":"automatic","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"actor.pools.shared.value"},3]},
                  "do":[{"op":"state.increment","slot":"seen","by":1}],"ui":{"report":"Automatic."}},
                 {"id":"button","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "when":{"eq":[{"read":"actor.pools.shared.value"},3]},
                  "ui":{"label":"Pool {actor.pools.shared.value}"}}
                ]}
                """);
        RuleCompiler.Program pool = pool();
        ObjectNode first = context(action.sourceId(), "action", "action.request");
        ObjectNode second = context(pool.sourceId(), "pool", "action.request");
        ObjectNode originalFirst = first.deepCopy(), originalSecond = second.deepCopy();
        var candidates = List.of(new RuleDispatcher.Candidate(action, first), new RuleDispatcher.Candidate(pool, second));

        var descriptions = RuleDispatcher.describe(candidates, "sheet");
        assertEquals(1, descriptions.size());
        assertTrue(descriptions.get(0).path("available").asBoolean());
        assertEquals("Pool 3", descriptions.get(0).path("label").asText());
        var resolved = RuleDispatcher.executeEvent(candidates, List.of(intent("button", "action")),
                noRandom(), Map.of(action.sourceId(), action, pool.sourceId(), pool));
        assertEquals(List.of("automatic", "button"),
                resolved.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals(1, resolved.contexts().getFirst().at("/state/seen").asInt());
        assertEquals(3, resolved.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(originalFirst, first);
        assertEquals(originalSecond, second);
    }

    @Test
    void computedPoolsAreClampedInSourceInstanceOrderRegardlessOfInputOrder() throws Exception {
        RuleCompiler.Program first = pool();
        RuleCompiler.Program second = program("features:second-pool", """
                {"pools":{"next":{"kind":"number","scope":"actor","max":{"read":"actor.pools.shared.value"}}}}
                """);
        ObjectNode one = context(first.sourceId(), "a", "action.request");
        one.withObject("/actor/pools").putObject("next").put("value", 6).put("max", 6);
        ObjectNode two = context(second.sourceId(), "z", "action.request");
        two.set("actor", one.path("actor").deepCopy());
        var a = new RuleDispatcher.Candidate(first, one);
        var z = new RuleDispatcher.Candidate(second, two);
        var forward = RuleDispatcher.executeEvent(List.of(a, z), List.of(), noRandom());
        var reverse = RuleDispatcher.executeEvent(List.of(z, a), List.of(), noRandom());
        assertEquals(forward.contexts(), reverse.contexts());
        assertEquals(3, forward.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(3, forward.contexts().getFirst().at("/actor/pools/next/value").asInt());
        assertEquals(6, one.at("/actor/pools/next/value").asInt());
    }

    @Test
    void backwardsPoolDependenciesAndUnreprojectedActorsFailWithoutChangingSnapshots() throws Exception {
        RuleCompiler.Program later = pool();
        RuleCompiler.Program earlier = program("features:earlier", """
                {"pools":{"next":{"kind":"number","scope":"actor","max":{"read":"actor.pools.shared.value"}}}}
                """);
        ObjectNode one = context(earlier.sourceId(), "a", "action.request");
        one.withObject("/actor/pools").putObject("next").put("value", 6).put("max", 6);
        ObjectNode two = context(later.sourceId(), "z", "action.request");
        two.set("actor", one.path("actor").deepCopy());
        ObjectNode original = one.deepCopy();
        var candidates = List.of(new RuleDispatcher.Candidate(earlier, one), new RuleDispatcher.Candidate(later, two));
        assertEquals("computedPoolDependencies", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeEvent(candidates, List.of(), noRandom())).code());
        assertEquals(original, one);

        one = context(later.sourceId(), "pool", "action.request");
        one.withObject("/actor").putObject("stats").put("evasion", 10);
        var projected = new RuleDispatcher.Candidate(later, one);
        assertEquals("computedPoolProjection", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.describe(List.of(projected), "sheet")).code());
        assertEquals(6, one.at("/actor/pools/shared/value").asInt());
        one.withObject("/actor").remove("stats");
        one.withObject("/actor").putArray("effects").addObject().put("id", "derived").put("derived", true);
        var derived = new RuleDispatcher.Candidate(later, one);
        assertEquals("computedPoolProjection", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.describe(List.of(derived), "sheet")).code());
    }

    @Test
    void computedPoolCapacitiesUseDefaultAndStoredSourceState() throws Exception {
        RuleCompiler.Program pool = program("features:state-pool", """
                {"state":{"cap":2},
                 "pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"state.cap"}}}}
                """);
        ObjectNode first = context(pool.sourceId(), "pool", "action.request");
        var defaults = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(pool, first)), List.of(), noRandom());
        assertEquals(2, defaults.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        first.putObject("state").put("cap", 4);
        var stored = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(pool, first)), List.of(), noRandom());
        assertEquals(4, stored.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(6, first.at("/actor/pools/shared/value").asInt());
    }

    @Test
    void computedActorCapacityUsesInitializedSourcePoolsWithoutWritingThePreview() throws Exception {
        RuleCompiler.Program pool = program("features:source-pool", """
                {"pools":{"reserve":{"kind":"number","scope":"source","max":10,"initial":4},
                          "shared":{"kind":"number","scope":"actor","max":{"read":"sourcePools.reserve.value"}}}}
                """);
        ObjectNode first = context(pool.sourceId(), "pool", "action.request");
        ObjectNode original = first.deepCopy();
        var defaults = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(pool, first)), List.of(), noRandom());
        assertEquals(4, defaults.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(original, first);
        first.putObject("sourcePools").putObject("reserve").put("value", 2).put("max", 10);
        var stored = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(pool, first)), List.of(), noRandom());
        assertEquals(2, stored.contexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(6, first.at("/actor/pools/shared/value").asInt());
    }

    private static RuleCompiler.Program target() throws Exception {
        return program("features:target", """
                {"rules":[{"id":"target",
                 "bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button",
                  "targetSelection":{"cardinality":"one","scope":"activeCharacters",
                   "where":{"and":[{"eq":[{"read":"actor.pools.shared.value"},3]},
                                   {"eq":[{"read":"recipient.pools.shared.value"},3]}]}}}],
                 "do":[{"op":"track.clear","track":"recipient.stress","amount":1}],
                 "ui":{"label":"Help","report":"{recipient.name} recovered."}}]}
                """);
    }

    @Test
    void selfTargetUsesTheCanonicalActorButRejectsDifferentOrStaleSnapshots() throws Exception {
        RuleCompiler.Program target = target(), pool = pool();
        ObjectNode first = context(target.sourceId(), "target", "action.request");
        ObjectNode second = context(pool.sourceId(), "pool", "action.request");
        var candidates = List.of(new RuleDispatcher.Candidate(target, first), new RuleDispatcher.Candidate(pool, second));
        var selection = new RuleDispatcher.TargetIntent(intent("target", "target"), "owner", 4L);
        ObjectNode rawActor = first.path("actor").deepCopy();
        var self = new RuleDispatcher.RecipientCandidate("owner", rawActor, 4);
        var resolved = RuleDispatcher.executeTargets(candidates, selection, List.of(self), noRandom(),
                Map.of(target.sourceId(), target, pool.sourceId(), pool));
        assertTrue(resolved.recipients().isEmpty());
        assertEquals(1, resolved.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(3, resolved.sourceContexts().getFirst().at("/actor/pools/shared/value").asInt());
        assertEquals(6, rawActor.at("/pools/shared/value").asInt());

        ObjectNode different = rawActor.deepCopy();
        different.withObject("/hope").put("value", 2);
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeTargets(candidates, selection,
                        List.of(new RuleDispatcher.RecipientCandidate("owner", different, 4)), noRandom())).code());
        var stale = new RuleDispatcher.TargetIntent(intent("target", "target"), "owner", 3L);
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeTargets(candidates, stale,
                        List.of(new RuleDispatcher.RecipientCandidate("owner", rawActor, 3)), noRandom())).code());
    }

    @Test
    void selfClaimRequiresTheSameSourceSnapshotAndRevision() throws Exception {
        RuleCompiler.Program claim = program("features:claim", """
                {"rules":[{"id":"claim","bindings":[{"id":"use","event":"claim.accept","surface":"chat","mode":"button",
                 "claimGroup":"recovery","claimScope":{"recipients":"sourceActor"}}],
                 "when":{"eq":[{"read":"actor.pools.shared.value"},3]},
                 "do":[{"op":"track.clear","track":"recipient.stress","amount":1}],"ui":{"label":"Recover"}}]}
                """);
        RuleCompiler.Program pool = pool();
        ObjectNode first = context(claim.sourceId(), "claim", "claim.accept");
        ObjectNode second = context(pool.sourceId(), "pool", "claim.accept");
        var candidates = List.of(new RuleDispatcher.Candidate(claim, first), new RuleDispatcher.Candidate(pool, second));
        var selected = new RuleDispatcher.ClaimIntent(intent("claim", "claim"), "owner", 4, 0);
        var ledger = new RuleDispatcher.ClaimLedger(0, Set.of());
        ObjectNode rawActor = first.path("actor").deepCopy();
        var result = RuleDispatcher.executeClaims(candidates, List.of(selected),
                List.of(new RuleDispatcher.RecipientCandidate("owner", rawActor, 4)), ledger, noRandom());
        assertEquals(1, result.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        ObjectNode changed = rawActor.deepCopy();
        changed.withObject("/stress").put("marked", 1);
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeClaims(candidates, List.of(selected),
                        List.of(new RuleDispatcher.RecipientCandidate("owner", changed, 4)), ledger, noRandom())).code());
        var stale = new RuleDispatcher.ClaimIntent(intent("claim", "claim"), "owner", 3, 0);
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                RuleDispatcher.executeClaims(candidates, List.of(stale),
                        List.of(new RuleDispatcher.RecipientCandidate("owner", rawActor, 3)), ledger, noRandom())).code());
    }

    @Test
    void hostTargetOffersAndExecutionShareTheClampedProjectionWithoutWritingOnDescription() throws Exception {
        RuleCompiler.Program target = target(), pool = pool();
        var pack = new AutomationHost.Pack("computed-pool", Map.of(target.sourceId(), target, pool.sourceId(), pool));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","hope":{"on":3,"slots":6},"stress":{"on":2,"slots":6},
                 "hp":{"on":0,"slots":6},"armorTrack":{"on":0,"slots":3},
                 "features":[{"name":"Target","automationSource":"features:target","instanceId":"target"},
                             {"name":"Pool","automationSource":"features:pool","instanceId":"pool"}],
                 "automationState":{"revision":4,"pools":{"shared":{"value":6,"max":6}}}}
                """);
        ObjectNode original = sheet.deepCopy();
        ObjectNode event = AutomationHost.event("action.request", "owner", 0);
        var member = new PartyTargets.Member("owner", "Ari", "sheet-v1", 4, ignored -> {
            fail("A self target uses the source projection.");
            return null;
        });
        var party = new PartyTargets(pack.revision(), "sheet-v1", ignored -> List.of(member));
        var offered = host.describe(pack, sheet, event, "sheet", "self", party);
        assertEquals(original, sheet);
        assertTrue(offered.at("/actions/0/available").asBoolean());
        assertEquals("owner", offered.at("/actions/0/targetSelection/options/0/id").asText());

        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "computed-target").put("expectedRevision", 4);
        request.putArray("selections").addObject().put("sourceInstanceId", "target").put("ruleId", "target")
                .put("bindingId", "use").putObject("inputs");
        request.putObject("target").put("recipientId", "owner")
                .set("version", offered.at("/actions/0/targetSelection/options/0/version"));
        ObjectNode stale = request.deepCopy();
        stale.withObject("/target").put("version", "stale");
        assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class, () ->
                host.execute(pack, sheet, event, stale, "self", "owner", noRandom(), party)).code());
        assertEquals(original, sheet);
        var response = host.execute(pack, sheet, event, request, "self", "owner", noRandom(), party);
        assertEquals("Ari recovered.", response.at("/actions/0/report").asText());
        assertEquals(1, sheet.at("/stress/on").asInt());
        assertEquals(3, sheet.at("/automationState/pools/shared/value").asInt());
        assertEquals(5, host.revision(sheet));
    }

    @Test
    void hostReprojectsPoolDependentPassivesAndDerivedEffectsForOffersAndResultGuards() throws Exception {
        RuleCompiler.Program pool = pool();
        RuleCompiler.Program projection = program("features:projection", """
                {"modifiers":[{"stat":"evasion","value":2,
                   "when":{"eq":[{"read":"actor.pools.shared.value"},3]}}],
                 "effects":{"form":{"label":"Form {actor.pools.shared.value}",
                   "while":{"eq":[{"read":"actor.pools.shared.value"},3]},
                   "modifiers":[{"stat":"evasion","value":4}]}}}
                """);
        var pack = new AutomationHost.Pack("pool-projection", Map.of(pool.sourceId(), pool, projection.sourceId(), projection));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","evasion":10,"hope":{"on":3,"slots":6},
                 "features":[{"name":"Pool","automationSource":"features:pool","instanceId":"pool"},
                             {"name":"Projection","automationSource":"features:projection","instanceId":"projection"}],
                 "automationState":{"revision":4,"pools":{"shared":{"value":6,"max":6}}}}
                """);
        ObjectNode event = AutomationHost.event("action.request", "owner", 0);
        ObjectNode state = host.projection().state(sheet);
        ObjectNode original = sheet.deepCopy();
        ObjectNode actor = host.actor(pack, sheet, state, event);
        assertEquals(3, actor.at("/pools/shared/value").asInt());
        assertEquals(16, actor.at("/stats/evasion").asInt());
        assertEquals("Form 3", actor.at("/effects/0/label").asText());
        assertEquals("Form 3", host.describe(pack, sheet, event, "sheet", "self")
                .at("/derivedEffects/0/label").asText());
        assertEquals(original, sheet);

        var sources = host.sources(pack, sheet);
        var buttons = RuleJson.JSON.createArrayNode();
        buttons.addObject().put("sourceInstanceId", "projection").put("ruleId", "after").put("bindingId", "use");
        ObjectNode resultEvent = RuleJson.JSON.createObjectNode().put("sourceInstanceId", "projection");
        String result = ResultWindows.publish(state, pack.revision(), "owner", resultEvent, buttons,
                ResultWindows.guards(sources, actor.path("effects"), "owner"));
        host.pruneResultWindows(pack, sheet, event);
        assertNotNull(ResultWindows.lookup(state, pack.revision(), "owner", result));
        assertEquals(6, sheet.at("/automationState/pools/shared/value").asInt());
    }

    @Test
    void hostRejectsUnstablePoolStatCyclesWithoutPersistingAPreviewOrAction() throws Exception {
        RuleCompiler.Program pool = program("features:pool-cycle", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.stats.evasion"}}}}
                """);
        RuleCompiler.Program modifier = program("features:cycle-modifier", """
                {"modifiers":[{"stat":"evasion","value":-5,
                  "when":{"gt":[{"read":"actor.pools.shared.value"},10]}}]}
                """);
        var pack = new AutomationHost.Pack("pool-cycle", Map.of(pool.sourceId(), pool, modifier.sourceId(), modifier));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","evasion":10,
                 "features":[{"name":"Pool","automationSource":"features:pool-cycle","instanceId":"pool"},
                             {"name":"Modifier","automationSource":"features:cycle-modifier","instanceId":"modifier"}],
                 "automationState":{"revision":4,"pools":{"shared":{"value":15,"max":15}}}}
                """);
        ObjectNode event = AutomationHost.event("action.request", "owner", 0);
        ObjectNode original = sheet.deepCopy();
        assertEquals("computedPoolDependencies", assertThrows(RuleRuntime.Unavailable.class, () ->
                host.describe(pack, sheet, event, "sheet", "self")).code());
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "cycle").put("expectedRevision", 4);
        request.putArray("selections");
        assertEquals("computedPoolDependencies", assertThrows(RuleRuntime.Unavailable.class, () ->
                host.execute(pack, sheet, event, request, "self", "owner", noRandom())).code());
        assertEquals(original, sheet);
    }

    @Test
    void projectionDoesNotReclampItsOwnPoolAgainstPreliminaryStats() throws Exception {
        RuleCompiler.Program source = program("features:projected-pool", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.stats.evasion"}}},
                 "modifiers":[{"stat":"evasion","value":5},
                              {"stat":"roll.attack","value":1,
                               "when":{"gt":[{"read":"actor.pools.shared.value"},10]}}],
                 "effects":{"form":{"label":"Form",
                   "while":{"gt":[{"read":"actor.pools.shared.value"},10]},
                   "modifiers":[{"stat":"evasion","value":2}]}}}
                """);
        var pack = new AutomationHost.Pack("projected-pool", Map.of(source.sourceId(), source));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","evasion":10,
                 "features":[{"name":"Pool","automationSource":"features:projected-pool","instanceId":"pool"}],
                 "automationState":{"revision":4,"pools":{"shared":{"value":12,"max":15}}}}
                """);
        ObjectNode state = host.projection().state(sheet);
        ObjectNode original = sheet.deepCopy();
        ObjectNode actor = host.actor(pack, sheet, state, AutomationHost.event("action.request", "owner", 0));
        assertEquals(12, actor.at("/pools/shared/value").asInt());
        assertEquals(17, actor.at("/pools/shared/max").asInt());
        assertEquals(17, actor.at("/stats/evasion").asInt());
        assertEquals("Form", actor.at("/effects/0/label").asText());
        assertEquals(original, sheet);
    }

    @Test
    void stagedRecoveryReprojectsPoolChangesDuringTheTrackMutation() throws Exception {
        RuleCompiler.Program pool = program("features:stress-pool", """
                {"pools":{"shared":{"kind":"number","scope":"actor","max":{"read":"actor.stress.marked"}}}}
                """);
        var pack = new AutomationHost.Pack("stress-pool", Map.of(pool.sourceId(), pool));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","stress":{"on":3,"slots":6},
                 "features":[{"name":"Pool","automationSource":"features:stress-pool","instanceId":"pool"}],
                 "automationState":{"revision":4,"pools":{"shared":{"value":3,"max":3}}}}
                """);
        ObjectNode result = host.stageHolderRecovery(pack, sheet, AutomationHost.event("downtime.move", "source", 0),
                "owner", "stress", 1, noRandom());
        assertEquals(1, result.path("cleared").asInt());
        assertEquals(2, sheet.at("/stress/on").asInt());
        assertEquals(2, sheet.at("/automationState/pools/shared/value").asInt());
        assertEquals(2, sheet.at("/automationState/pools/shared/max").asInt());
        assertEquals(5, host.revision(sheet));
    }
}
