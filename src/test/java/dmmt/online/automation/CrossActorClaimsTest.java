package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class CrossActorClaimsTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:devout", fixture("rewrite/claims"), fixture("rewrite/recipes"));
    }

    private static ObjectNode ownerContext(RuleCompiler.Program program) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:devout","instanceId":"devout-instance","name":"Devout"},
                 "actor":{"id":"owner","hope":{"value":3,"max":6}},
                 "revision":0,"event":{"type":"claim.accept","id":"claim-1"}}
                """);
    }

    private static ObjectNode recipient(String id, String name, int hope, int stress) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"id":"%s","name":"%s","hope":{"value":%d,"max":6},
                 "stress":{"marked":%d,"capacity":6}}
                """.formatted(id, name, hope, stress));
    }

    private static RuleDispatcher.ClaimIntent claim(String ruleId, String recipientId,
                                                     long recipientRevision, long claimRevision) {
        return new RuleDispatcher.ClaimIntent(
                new RuleRuntime.Intent(ruleId, "claim", "devout-instance", 0, RuleJson.JSON.createObjectNode()),
                recipientId, recipientRevision, claimRevision);
    }

    @Test
    void oneRecipientCanClaimOnlyOneAlternativeAndOfflineRecipientUpdatesAreReturned() throws Exception {
        RuleCompiler.Program program = program();
        RuleDispatcher.Candidate source = new RuleDispatcher.Candidate(program, ownerContext(program));
        RuleDispatcher.RecipientCandidate recipient = new RuleDispatcher.RecipientCandidate(
                "recipient-a", recipient("recipient-a", "Mira", 1, 2), 5);
        RuleDispatcher.ClaimLedger emptyLedger = new RuleDispatcher.ClaimLedger(8, Set.of());

        RuleDispatcher.ClaimResolution reward = RuleDispatcher.executeClaims(List.of(source),
                List.of(claim("hope-reward", "recipient-a", 5, 8)), List.of(recipient), emptyLedger,
                RandomGenerator.getDefault());

        assertEquals(3, reward.recipients().getFirst().snapshot().path("hope").path("value").asInt());
        assertEquals(6, reward.recipients().getFirst().revision());
        assertEquals("Mira gained 2 Hope.", reward.actions().getFirst().action().report());
        assertEquals(Set.of(new RuleDispatcher.ClaimKey("devout-reward", "recipient-a")),
                reward.ledger().claims());
        assertEquals(9, reward.ledger().revision());
        assertEquals(3, source.context().path("actor").path("hope").path("value").asInt());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(List.of(source),
                List.of(claim("stress-reward", "recipient-a", 6, 9)),
                List.of(new RuleDispatcher.RecipientCandidate("recipient-a",
                        reward.recipients().getFirst().snapshot(), 6)),
                reward.ledger(), RandomGenerator.getDefault()));
    }

    @Test
    void claimRevisionGuardsConcurrentRequestsAndDistinctRecipientsMayClaimAtomically() throws Exception {
        RuleCompiler.Program program = program();
        RuleDispatcher.Candidate source = new RuleDispatcher.Candidate(program, ownerContext(program));
        RuleDispatcher.ClaimLedger ledger = new RuleDispatcher.ClaimLedger(2, Set.of());
        List<RuleDispatcher.RecipientCandidate> recipients = List.of(
                new RuleDispatcher.RecipientCandidate("recipient-a", recipient("recipient-a", "Mira", 1, 1), 3),
                new RuleDispatcher.RecipientCandidate("recipient-b", recipient("recipient-b", "Noor", 4, 4), 7));

        RuleDispatcher.ClaimResolution result = RuleDispatcher.executeClaims(List.of(source),
                List.of(claim("hope-reward", "recipient-a", 3, 2),
                        claim("stress-reward", "recipient-b", 7, 2)),
                recipients, ledger, RandomGenerator.getDefault());

        assertEquals(List.of("recipient-a", "recipient-b"),
                result.recipients().stream().map(RuleDispatcher.RecipientUpdate::actorId).toList());
        assertEquals(3, result.recipients().getFirst().snapshot().path("hope").path("value").asInt());
        assertEquals(3, result.recipients().get(1).snapshot().path("stress").path("marked").asInt());
        assertEquals(Set.of(new RuleDispatcher.ClaimKey("devout-reward", "recipient-a"),
                new RuleDispatcher.ClaimKey("devout-reward", "recipient-b")), result.ledger().claims());
        assertEquals(3, result.ledger().revision());

        RuleDispatcher.RecipientUpdate updated = result.recipients().getFirst();
        RuleDispatcher.ClaimIntent staleIntent = claim("hope-reward", "recipient-a",
                updated.revision(), 2);
        RuleRuntime.Unavailable stale = assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleDispatcher.executeClaims(List.of(source), List.of(staleIntent), List.of(
                        new RuleDispatcher.RecipientCandidate(updated.actorId(), updated.snapshot(), updated.revision())),
                        result.ledger(), RandomGenerator.getDefault()));
        assertEquals("staleClaim", stale.code());
    }

    private static final String OFFER = """
            {"rules":[
              {"id":"offer","bindings":[{"id":"sheet","event":"action.request","surface":"sheet","mode":"button"}],
               "do":[{"op":"claim.offer","group":"boons","label":"{source.name} offers a boon","limit":{"subtract":[{"read":"input.amount"},1]}}],
               "inputs":[{"id":"amount","kind":"integer","label":"Amount","min":1,"max":3}],
               "ui":{"label":"Offer"}},
              {"id":"party-hope","bindings":[{"id":"claim","event":"claim.accept","surface":"chat","mode":"button",
                "claimGroup":"boons","claimScope":{"recipients":"playerCharacters"}}],
               "do":[{"op":"resource.gain","pool":"recipient.hope","amount":1}],
               "ui":{"label":"Gain Hope","report":"{recipient.name} gained Hope."}},
              {"id":"source-stress","bindings":[{"id":"claim","event":"claim.accept","surface":"chat","mode":"button",
                "claimGroup":"boons","claimScope":{"recipients":"sourceActor"}}],
               "do":[{"op":"track.clear","track":"recipient.stress","amount":1}],
               "ui":{"label":"Clear Stress","report":"{recipient.name} cleared Stress."}}
            ]}""";

    @Test
    void offersMustLinkToAnAcceptanceBindingRatherThanAnUnrelatedClaim() throws Exception {
        ObjectNode automation = (ObjectNode) RuleJson.parse(OFFER);
        assertDoesNotThrow(() -> RuleCompiler.compile("features:offer", automation, fixture("rewrite/recipes")));
        ObjectNode wrongEvent = automation.deepCopy();
        for (int i = 1; i <= 2; i++)
            ((ObjectNode) wrongEvent.at("/rules/" + i + "/bindings/0")).put("event", "roll.finished").put("surface", "roll");
        var noAcceptance = assertThrows(IllegalArgumentException.class,
                () -> RuleCompiler.compile("features:offer", wrongEvent, fixture("rewrite/recipes")));
        assertTrue(noAcceptance.getMessage().contains("claim.accept chat group"), noAcceptance.getMessage());

        ObjectNode wrongSurface = automation.deepCopy();
        for (int i = 1; i <= 2; i++)
            ((ObjectNode) wrongSurface.at("/rules/" + i + "/bindings/0")).put("surface", "sheet");
        assertThrows(IllegalArgumentException.class,
                () -> RuleCompiler.compile("features:offer", wrongSurface, fixture("rewrite/recipes")));

        ObjectNode differentGroup = automation.deepCopy();
        for (int i = 1; i <= 2; i++)
            ((ObjectNode) differentGroup.at("/rules/" + i + "/bindings/0")).put("claimGroup", "elsewhere");
        assertThrows(IllegalArgumentException.class,
                () -> RuleCompiler.compile("features:offer", differentGroup, fixture("rewrite/recipes")));
    }

    @Test
    void claimOffersPublishLimitsAndScopesAllowSourceSelfClaims() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:offer", RuleJson.parse(OFFER), fixture("rewrite/recipes"));
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:offer","instanceId":"devout-instance","name":"Beacon"},
                 "actor":{"id":"owner","name":"Ash","hope":{"value":3,"max":6},"stress":{"marked":2,"capacity":6}},
                 "revision":0,"event":{"type":"action.request","id":"use-1"}}
                """);
        RuleDispatcher.EventResolution offered = RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent("offer", "sheet", "devout-instance", 0,
                        (ObjectNode) RuleJson.parse("{\"amount\":2}"))), RandomGenerator.getDefault());
        var request = offered.contexts().getFirst().path("outbox").get(0);
        assertEquals("claim.offer", request.path("kind").asText());
        assertEquals("boons", request.path("group").asText());
        assertEquals("Beacon offers a boon", request.path("label").asText());
        assertEquals(1, request.path("limit").asInt());

        ObjectNode claimContext = context.deepCopy();
        claimContext.set("event", RuleJson.parse("{\"type\":\"claim.accept\",\"id\":\"claim-1\"}"));
        RuleDispatcher.Candidate source = new RuleDispatcher.Candidate(program, claimContext);
        RuleDispatcher.ClaimLedger ledger = new RuleDispatcher.ClaimLedger(0, Set.of(), java.util.Map.of("boons", 1));
        List<RuleDispatcher.RecipientCandidate> recipients = List.of(
                new RuleDispatcher.RecipientCandidate("owner", claimContext.path("actor").deepCopy(), 0),
                new RuleDispatcher.RecipientCandidate("recipient-a", recipient("recipient-a", "Mira", 1, 2), 4));

        RuleDispatcher.ClaimResolution self = RuleDispatcher.executeClaims(List.of(source),
                List.of(claim("source-stress", "owner", 0, 0)), recipients, ledger, RandomGenerator.getDefault());
        assertTrue(self.recipients().isEmpty());
        assertEquals(1, self.sourceContexts().getFirst().path("actor").path("stress").path("marked").asInt());
        assertEquals("Ash cleared Stress.", self.actions().getFirst().action().report());
        assertEquals(java.util.Map.of("boons", 1), self.ledger().limits());

        RuleRuntime.Unavailable limit = assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(
                List.of(source), List.of(claim("party-hope", "recipient-a", 4, 1)), recipients, self.ledger(),
                RandomGenerator.getDefault()));
        assertEquals("claimLimit", limit.code());
        RuleRuntime.Unavailable scope = assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(
                List.of(source), List.of(claim("source-stress", "recipient-a", 4, 0)), recipients, ledger,
                RandomGenerator.getDefault()));
        assertEquals("recipient", scope.code());
        RuleDispatcher.ClaimResolution party = RuleDispatcher.executeClaims(List.of(source),
                List.of(claim("party-hope", "owner", 0, 0)), recipients, ledger, RandomGenerator.getDefault());
        assertEquals(4, party.sourceContexts().getFirst().path("actor").path("hope").path("value").asInt());

        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("features:offer",
                RuleJson.parse(OFFER.replace("\"group\":\"boons\"", "\"group\":\"other\"")), fixture("rewrite/recipes")));
    }
}