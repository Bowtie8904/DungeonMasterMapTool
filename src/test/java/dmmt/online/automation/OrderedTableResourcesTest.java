package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class OrderedTableResourcesTest {
    private static RuleCompiler.Program program(String rules) throws Exception {
        return RuleCompiler.compile("features:table", RuleJson.parse(rules), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(int fear, int hope) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:table","instanceId":"table","name":"Table"},
                 "actor":{"id":"owner","name":"Owner","hope":{"value":%d,"max":6}},
                 "event":{"type":"action.request","table":{"fear":%d}},"revision":0}
                """.formatted(hope, fear));
    }

    private static RuleRuntime.Intent select(String rule) {
        return new RuleRuntime.Intent(rule, "use", "table", 0, RuleJson.JSON.createObjectNode());
    }

    @Test
    void operationsSeeCappedAndSpentFearImmediatelyAndFailuresDiscardEverything() throws Exception {
        var program = program("""
                {"rules":[
                 {"id":"use","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"table.gain","resource":"fear","amount":5,"label":"Gain"},
                        {"op":"value","value":{"read":"event.table.fear"},"as":"capped"},
                        {"op":"table.spend","resource":"fear","amount":3,"label":"Spend","as":"spent"},
                        {"op":"value","value":{"read":"event.table.fear"},"as":"remaining"}],
                  "ui":{"label":"Use","report":"{result.capped}, {result.spent.amount}, {result.remaining}"}},
                 {"id":"insufficient","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"table.gain","resource":"fear","amount":1,"label":"Gain"},
                        {"op":"table.spend","resource":"fear","amount":12,"label":"Spend"}],"ui":{"label":"Fail"}},
                 {"id":"unknown","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "do":[{"op":"table.gain","resource":"unknown","amount":1,"label":"Unknown"}],"ui":{"label":"Unknown"}}
                ]}""");
        ObjectNode original = context(10, 2);
        var candidate = new RuleDispatcher.Candidate(program, original);
        var result = RuleDispatcher.executeEvent(List.of(candidate), List.of(select("use")), StructuredRollTest.noRandom());
        assertEquals("12, 3, 9", result.actions().getFirst().report());
        assertEquals(9, result.contexts().getFirst().at("/event/table/fear").intValue());
        assertEquals(1, result.contexts().getFirst().at("/actor/hope/value").intValue());
        assertEquals(List.of(10, 12), result.outbox().stream().map(item -> item.path("before").intValue()).toList());
        assertEquals(List.of(12, 9), result.outbox().stream().map(item -> item.path("after").intValue()).toList());
        assertEquals(10, original.at("/event/table/fear").intValue());
        assertEquals(2, original.at("/actor/hope/value").intValue());

        var unavailable = assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeEvent(
                List.of(candidate), List.of(select("insufficient")), StructuredRollTest.noRandom()));
        assertEquals("tableResource", unavailable.code());
        assertEquals(10, original.at("/event/table/fear").intValue());
        assertEquals(2, original.at("/actor/hope/value").intValue());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeEvent(
                List.of(candidate), List.of(select("unknown")), StructuredRollTest.noRandom()));
        for (String invalid : List.of("null", "-1", "13", "\"3\"")) {
            ObjectNode bad = context(0, 2);
            ((ObjectNode) bad.at("/event/table")).set("fear", RuleJson.parse(invalid));
            assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeEvent(
                    List.of(new RuleDispatcher.Candidate(program, bad)), List.of(select("use")), StructuredRollTest.noRandom()));
        }
        ObjectNode missing = context(1, 2);
        ((ObjectNode) missing.get("event")).remove("table");
        assertThrows(IllegalArgumentException.class, () -> RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(program, missing)), List.of(select("use")), StructuredRollTest.noRandom()));
    }

    @Test
    void automaticEligibilityAndAggregateCostsUseTheInitialSnapshotButBodiesSeeOrderedFear() throws Exception {
        var program = program("""
                {"rules":[
                 {"id":"first","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic","priority":10}],
                  "do":[{"op":"table.gain","resource":"fear","amount":2,"label":"Gain"}],"ui":{}},
                 {"id":"second","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic"}],
                  "when":{"eq":[{"read":"event.table.fear"},1]},
                  "do":[{"op":"value","value":{"read":"event.table.fear"},"as":"seen"}],
                  "ui":{"report":"Saw {result.seen} Fear."}},
                 {"id":"later","bindings":[{"id":"auto","event":"action.request","surface":"sheet","mode":"automatic","priority":-1}],
                  "when":{"eq":[{"read":"event.table.fear"},3]},
                  "do":[{"op":"value","value":true}],"ui":{"report":"Must not run."}},
                 {"id":"selected","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"table.spend","resource":"fear","amount":1,"label":"Spend"}],"ui":{"label":"Selected"}},
                 {"id":"selected-two","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                  "costs":[{"op":"resource.spend","pool":"actor.hope","amount":1}],
                  "do":[{"op":"value","value":true}],"ui":{"label":"Selected two"}}
                ]}""");
        ObjectNode poor = context(1, 1);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeEvent(
                List.of(new RuleDispatcher.Candidate(program, poor)), List.of(select("selected"), select("selected-two")),
                StructuredRollTest.noRandom()));
        assertEquals(1, poor.at("/actor/hope/value").intValue());
        assertEquals(1, poor.at("/event/table/fear").intValue());

        var result = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context(1, 2))),
                List.of(select("selected"), select("selected-two")), StructuredRollTest.noRandom());
        assertEquals(List.of("first", "second", "selected", "selected-two"),
                result.actions().stream().map(RuleDispatcher.ActionResult::ruleId).toList());
        assertEquals("Saw 3 Fear.", result.actions().get(1).report());
        assertEquals(0, result.contexts().getFirst().at("/actor/hope/value").intValue());
        assertEquals(2, result.contexts().getFirst().at("/event/table/fear").intValue());
    }

    @Test
    void sequentialClaimsShareTableStateAndAnUnaffordableLaterClaimRollsBack() throws Exception {
        var program = program("""
                {"rules":[{"id":"claim","bindings":[{"id":"use","event":"claim.accept","surface":"chat",
                  "mode":"button","claimGroup":"boon","claimScope":{"recipients":"playerCharacters"}}],
                  "do":[{"op":"table.spend","resource":"fear","amount":1,"label":"Spend"},
                        {"op":"resource.gain","pool":"recipient.hope","amount":1}],
                  "ui":{"label":"Claim","report":"{recipient.name}: {event.table.fear} Fear."}}]}""");
        ObjectNode source = context(4, 2);
        ((ObjectNode) source.get("event")).put("type", "claim.accept");
        List<RuleDispatcher.RecipientCandidate> recipients = List.of(
                new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"mira\",\"name\":\"Mira\",\"hope\":{\"value\":1,\"max\":6}}"), 0),
                new RuleDispatcher.RecipientCandidate("noor", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"noor\",\"name\":\"Noor\",\"hope\":{\"value\":1,\"max\":6}}"), 0));
        List<RuleDispatcher.ClaimIntent> claims = List.of(
                new RuleDispatcher.ClaimIntent(select("claim"), "mira", 0, 0),
                new RuleDispatcher.ClaimIntent(select("claim"), "noor", 0, 0));
        var ledger = new RuleDispatcher.ClaimLedger(0, Set.of());
        var result = RuleDispatcher.executeClaims(List.of(new RuleDispatcher.Candidate(program, source)),
                claims, recipients, ledger, StructuredRollTest.noRandom());
        assertEquals(List.of("Mira: 3 Fear.", "Noor: 2 Fear."),
                result.actions().stream().map(item -> item.action().report()).toList());
        assertEquals(2, result.sourceContexts().getFirst().at("/event/table/fear").intValue());
        assertEquals(List.of(4, 3), result.outbox().stream().map(item -> item.path("before").intValue()).toList());
        assertEquals(List.of(2, 2), result.recipients().stream()
                .map(item -> item.snapshot().at("/hope/value").intValue()).toList());

        ((ObjectNode) source.at("/event/table")).put("fear", 1);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(
                List.of(new RuleDispatcher.Candidate(program, source)), claims, recipients, ledger, StructuredRollTest.noRandom()));
        assertEquals(1, source.at("/event/table/fear").intValue());
        assertEquals(1, recipients.getFirst().snapshot().at("/hope/value").intValue());
        assertTrue(ledger.claims().isEmpty());
    }
}
