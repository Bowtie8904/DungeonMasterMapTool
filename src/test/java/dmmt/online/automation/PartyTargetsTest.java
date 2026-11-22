package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PartyTargetsTest {
    private final ObjectNode context = RuleJson.JSON.createObjectNode();
    private final ObjectNode binding = RuleJson.JSON.createObjectNode().put("id", "give");
    private final RuleRuntime.Intent action = new RuleRuntime.Intent("gift", "give", "source", 0, RuleJson.JSON.createObjectNode());
    private final AtomicInteger projected = new AtomicInteger();

    PartyTargetsTest() {
        context.putObject("actor").put("id", "self");
        context.putObject("source").put("instanceId", "source");
        binding.putObject("targetSelection").put("excludeSource", true).put("scope", "activeCharacters").put("cardinality", "all");
    }

    private PartyTargets.Member member(String id, String sheetVersion) {
        return new PartyTargets.Member(id, id, sheetVersion, 0, () -> {
            projected.incrementAndGet();
            return RuleJson.JSON.createObjectNode().put("id", id);
        });
    }

    private PartyTargets party(PartyTargets.Member... members) {
        return new PartyTargets("pack", "source sheet", scope -> List.of(members));
    }

    @Test
    void allChecksTheSameEligibleSheetsNotRosterOrderBeforeProjectingOrCharging() {
        var a = member("a", "v1");
        var b = member("b", "v2");
        var offer = party(a, b, member("self", "old")).describe(binding, "gift", context);
        var request = RuleJson.JSON.createObjectNode().put("version", offer.required("version").asText());
        for (var changed : List.of(party(a), party(a, b, member("c", "v1")), party(a, member("b", "ordinary edit")))) {
            assertEquals("staleRecipient", assertThrows(RuleRuntime.Unavailable.class,
                    () -> changed.resolve(binding, context, action, request)).code());
        }
        assertEquals(0, projected.get());
        assertEquals(List.of("a", "b"), party(b, a).resolve(binding, context, action, request).recipients().stream()
                .map(RuleDispatcher.RecipientCandidate::actorId).toList());
        request.put("recipientId", "a");
        assertThrows(IllegalArgumentException.class, () -> party(a, b).resolve(binding, context, action, request));
    }

    @Test
    void singleTargetDoesNotBecomeStaleBecauseAnUnselectedSheetChanged() {
        ((ObjectNode) binding.get("targetSelection")).put("cardinality", "one");
        var a = member("a", "v1");
        var offer = party(a, member("b", "v2")).describe(binding, "gift", context);
        var request = RuleJson.JSON.createObjectNode().put("recipientId", "a").set("version", offer.at("/options/0/version"));
        assertEquals("a", party(a).resolve(binding, context, action, request).intent().recipientId());
        assertThrows(RuleRuntime.Unavailable.class, () -> party(member("a", "edited")).resolve(binding, context, action, request));
    }
}
