package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class TargetActionsTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:target-fixture", fixture("rewrite/targets"), fixture("rewrite/recipes"));
    }

    private static ObjectNode sourceContext() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:target-fixture","instanceId":"rally-source","name":"Rally"},
                 "actor":{"id":"owner","name":"Ari","hope":{"value":3,"max":6},
                           "stress":{"marked":1,"capacity":6}},
                 "revision":4,"event":{"type":"action.request","table":{"fear":3}},
                 "targetOptions":{"connectedPlayerCharacters":[
                   {"id":"owner","label":"Ari","revision":4},
                   {"id":"mira","label":"Mira","revision":8},
                   {"id":"noor","label":"Noor","revision":3}]}}
                """);
    }

    private static RuleDispatcher.RecipientCandidate recipient(String id, String name, int stress, long revision) throws Exception {
        return new RuleDispatcher.RecipientCandidate(id, (ObjectNode) RuleJson.parse("""
                {"id":"%s","name":"%s","stress":{"marked":%d,"capacity":6}}
                """.formatted(id, name, stress)), revision);
    }

    private static RuleDispatcher.Candidate source(RuleCompiler.Program program) throws Exception {
        return new RuleDispatcher.Candidate(program, sourceContext());
    }

    @Test
    void fansOutToTheHostSuppliedEligibleSetAndChargesSourceOnce() throws Exception {
        RuleCompiler.Program program = program();
        ObjectNode descriptor = RuleDispatcher.describe(List.of(source(program)), "sheet").get(0).deepCopy();
        assertEquals("all", descriptor.at("/targetSelection/cardinality").asText());
        assertEquals("connectedPlayerCharacters", descriptor.at("/targetSelection/scope").asText());
        assertTrue(descriptor.at("/targetSelection/excludeSource").asBoolean());
        assertEquals(List.of("mira", "noor"), descriptor.at("/targetSelection/options").findValuesAsText("id"));

        RuleDispatcher.TargetResolution result = RuleDispatcher.executeTargets(List.of(source(program)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), null, null),
                List.of(
                        new RuleDispatcher.RecipientCandidate("owner", (ObjectNode) sourceContext().path("actor").deepCopy(), 4),
                        recipient("mira", "Mira", 2, 8),
                        recipient("noor", "Noor", 1, 3)), RandomGenerator.getDefault());

        assertEquals(2, result.recipients().size());
        assertEquals(List.of("mira", "noor"), result.recipients().stream().map(RuleDispatcher.RecipientUpdate::actorId).toList());
        assertEquals(1, result.recipients().get(0).snapshot().at("/stress/marked").asInt());
        assertEquals(0, result.recipients().get(1).snapshot().at("/stress/marked").asInt());
        assertEquals(1, result.recipients().get(0).snapshot().at("/pools/favor/value").asInt());
        assertEquals(1, result.recipients().get(1).snapshot().at("/pools/favor/value").asInt());
        assertEquals("Mira cleared 1 Stress and gained 1 Favor.", result.actions().get(0).action().report());
        assertEquals("Noor cleared 1 Stress and gained 1 Favor.", result.actions().get(1).action().report());
        assertEquals(2, result.actions().size());
        ObjectNode updatedSource = result.sourceContexts().getFirst();
        assertEquals(2, updatedSource.at("/actor/hope/value").asInt());
        assertEquals(1, updatedSource.at("/usage/uses").asInt());
        assertEquals(5, updatedSource.path("revision").asLong());
    }

    @Test
    void retainsEachRecipientsCommandsInExecutionOrder() throws Exception {
        ObjectNode automation = fixture("rewrite/targets");
        var steps = (com.fasterxml.jackson.databind.node.ArrayNode) automation.at("/rules/0/do");
        steps.addObject().put("op", "table.spend").put("resource", "fear").put("amount", 1).put("label", "Spend");
        steps.addObject().put("op", "countdown.start").put("value", 2).put("label", "{recipient.name}");
        ((ObjectNode) automation.at("/rules/0/ui")).put("report", "{recipient.name}: {event.table.fear} Fear.");
        var program = RuleCompiler.compile("features:target-fixture", automation, fixture("rewrite/recipes"));
        var result = RuleDispatcher.executeTargets(List.of(source(program)), new RuleDispatcher.TargetIntent(
                new RuleRuntime.Intent("rally", "sheet", "rally-source", 4, RuleJson.JSON.createObjectNode()), null, null),
                List.of(recipient("noor", "Noor", 1, 3), recipient("mira", "Mira", 2, 8)), RandomGenerator.getDefault());
        assertEquals(List.of("mira:Spend", "mira:Mira", "noor:Spend", "noor:Noor"), result.outbox().stream()
                .map(command -> command.path("recipientId").asText() + ":" + command.path("label").asText()).toList());
        assertEquals(List.of("Mira: 2 Fear.", "Noor: 1 Fear."),
                result.actions().stream().map(action -> action.action().report()).toList());
        assertEquals(1, result.sourceContexts().getFirst().at("/event/table/fear").intValue());
        assertEquals(List.of(3, 2), result.outbox().stream().filter(command -> command.has("before"))
                .map(command -> command.path("before").intValue()).toList());
        assertFalse(result.sourceContexts().getFirst().has("outbox"));
        result.outbox().getFirst().put("label", "mutated");
        assertEquals("Spend", result.outbox().getFirst().path("label").asText());
    }

    @Test
    void sourceCanBeIncludedInFanOutAndReceivesBothSourceCostAndTargetMutation() throws Exception {
        ObjectNode automation = fixture("rewrite/targets");
        ((ObjectNode) automation.at("/rules/0/bindings/0/targetSelection")).put("excludeSource", false);
        RuleCompiler.Program program = RuleCompiler.compile("features:target-fixture", automation, fixture("rewrite/recipes"));
        RuleDispatcher.TargetResolution result = RuleDispatcher.executeTargets(List.of(source(program)),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), null, null),
                List.of(
                        new RuleDispatcher.RecipientCandidate("owner", (ObjectNode) sourceContext().path("actor").deepCopy(), 4),
                        recipient("mira", "Mira", 2, 8)), RandomGenerator.getDefault());

        assertEquals(2, result.actions().size());
        assertEquals(2, result.sourceContexts().getFirst().at("/actor/hope/value").asInt());
        assertEquals(0, result.sourceContexts().getFirst().at("/actor/stress/marked").asInt());
        assertEquals(1, result.sourceContexts().getFirst().at("/actor/pools/favor/value").asInt());
        assertEquals(List.of("mira"), result.recipients().stream().map(RuleDispatcher.RecipientUpdate::actorId).toList());
    }

    @Test
    void selfTargetReadsWritesAndCostsAsOneCharacterInRuleOrder() throws Exception {
        ObjectNode automation = fixture("rewrite/targets");
        ((ObjectNode) automation.at("/rules/0/bindings/0/targetSelection")).put("excludeSource", false).put("cardinality", "one");
        var rule = (ObjectNode) automation.at("/rules/0");
        rule.set("do", RuleJson.parse("""
                [{"op":"resource.gain","pool":"actor.hope","amount":2},
                 {"op":"resource.spend","pool":"recipient.hope","amount":3},
                 {"op":"track.mark","track":"actor.stress","amount":{"read":"recipient.hope.value"}}]"""));
        ((ObjectNode) rule.get("ui")).put("report", "{actor.hope.value}/{recipient.hope.value} Hope");
        var program = RuleCompiler.compile("features:target-fixture", automation, fixture("rewrite/recipes"));
        var source = source(program);
        var result = RuleDispatcher.executeTargets(List.of(source), new RuleDispatcher.TargetIntent(
                new RuleRuntime.Intent("rally", "sheet", "rally-source", 4, RuleJson.JSON.createObjectNode()), "owner", 4L),
                List.of(new RuleDispatcher.RecipientCandidate("owner", source.context().path("actor").deepCopy(), 4)),
                RandomGenerator.getDefault());
        assertTrue(result.recipients().isEmpty());
        assertEquals("1/1 Hope", result.actions().getFirst().action().report());
        var saved = result.sourceContexts().getFirst();
        assertEquals(1, saved.at("/actor/hope/value").asInt());
        assertEquals(2, saved.at("/actor/stress/marked").asInt());
        assertEquals(3, source.context().at("/actor/hope/value").asInt());
    }

    @Test
    void singleTargetRequiresAnEligibleCurrentTargetAndRejectsStaleRecipientRevision() throws Exception {
        ObjectNode automation = fixture("rewrite/targets");
        ((ObjectNode) automation.at("/rules/0/bindings/0/targetSelection")).put("cardinality", "one");
        RuleCompiler.Program program = RuleCompiler.compile("features:target-fixture", automation, fixture("rewrite/recipes"));
        RuleDispatcher.Candidate source = source(program);
        List<RuleDispatcher.RecipientCandidate> eligible = List.of(recipient("mira", "Mira", 2, 8));

        RuleDispatcher.TargetResolution result = RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), "mira", 8L), eligible, RandomGenerator.getDefault());
        assertEquals(1, result.recipients().size());

        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), "mira", 7L), eligible, RandomGenerator.getDefault()));
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), "offline", 1L), eligible, RandomGenerator.getDefault()));
    }

    @Test
    void allTargetActionsRejectClientChosenSubsetsAndEmptyEligibleSets() throws Exception {
        RuleDispatcher.Candidate source = source(program());
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), "mira", 8L),
                List.of(recipient("mira", "Mira", 2, 8)), RandomGenerator.getDefault()));
        RuleRuntime.Unavailable empty = assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleDispatcher.executeTargets(List.of(source),
                        new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                                RuleJson.JSON.createObjectNode()), null, null), List.of(), RandomGenerator.getDefault()));
        assertEquals("recipient", empty.code());
    }

    @Test
    void fanOutFailureDoesNotCommitCostsOrPartialRecipientChanges() throws Exception {
        ObjectNode automation = fixture("rewrite/targets");
        ObjectNode spend = (ObjectNode) automation.at("/rules/0/do/1");
        spend.put("op", "pool.spend");
        RuleCompiler.Program program = RuleCompiler.compile("features:target-fixture", automation, fixture("rewrite/recipes"));
        RuleDispatcher.Candidate source = source(program);
        ObjectNode originalSource = source.context();
        ObjectNode mira = recipient("mira", "Mira", 2, 8).snapshot();
        mira.putObject("pools").putObject("favor").put("value", 1).put("max", 5);
        ObjectNode noor = recipient("noor", "Noor", 1, 3).snapshot();
        noor.putObject("pools").putObject("favor").put("value", 0).put("max", 5);
        List<RuleDispatcher.RecipientCandidate> eligible = List.of(
                new RuleDispatcher.RecipientCandidate("mira", mira, 8),
                new RuleDispatcher.RecipientCandidate("noor", noor, 3));

        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), null, null), eligible, RandomGenerator.getDefault()));
        assertEquals(3, source.context().at("/actor/hope/value").asInt());
        assertEquals(originalSource, source.context());
        assertEquals(1, eligible.get(0).snapshot().at("/pools/favor/value").asInt());
        assertEquals(0, eligible.get(1).snapshot().at("/pools/favor/value").asInt());
    }

    @Test
    void recipientSnapshotsMustMatchTheirHostActorIdsAndSourceSnapshot() throws Exception {
        RuleDispatcher.Candidate source = source(program());
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), null, null),
                List.of(new RuleDispatcher.RecipientCandidate("mira", recipient("wrong", "Mira", 2, 8).snapshot(), 8)),
                RandomGenerator.getDefault()));
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeTargets(List.of(source),
                new RuleDispatcher.TargetIntent(new RuleRuntime.Intent("rally", "sheet", "rally-source", 4,
                        RuleJson.JSON.createObjectNode()), null, null),
                List.of(new RuleDispatcher.RecipientCandidate("owner", recipient("owner", "Changed", 1, 4).snapshot(), 4)),
                RandomGenerator.getDefault()));
    }
}
