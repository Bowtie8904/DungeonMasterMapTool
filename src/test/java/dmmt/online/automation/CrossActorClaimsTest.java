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
}
