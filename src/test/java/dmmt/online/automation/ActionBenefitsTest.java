package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy {@code actionBenefits}: a feature's benefits attach to the claims of another feature's action message. */
class ActionBenefitsTest {
    private static String catalog(String name, String effect, String benefits) {
        return """
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"band","name":"Band","features":[
                  {"name":"%s","automation":{"actions":[{"id":"use","label":"%s","target":"allActors",
                    "steps":[{"op":"effect","effect":{"name":"%s","kind":"buff","until":"session"}}]}]}},
                  {"name":"Leader","automation":{"actionBenefits":{"feat:%s":%s}}}]}]}
                """.formatted(name, name, effect, name, benefits);
    }

    private static final String RALLY = """
            [{"id":"hope","label":"Maestro: gain 1 Hope","steps":[{"op":"gain","resource":"hope","amount":1}]},
             {"id":"stress","label":"Maestro: clear 1 Stress","steps":[{"op":"clear","resource":"stress","amount":1}]}]""";

    private static List<RuleCatalog.Source> sources(LegacyCatalogConverter.Result result) throws Exception {
        assertEquals(result.blocks(), result.converted(), result.diagnostics().toString());
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes());
    }

    private static ObjectNode context(RuleCompiler.Program program, String actor, String event) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"leader","name":"Leader"},"actor":%s,"revision":0}
                """.formatted(program.sourceId(), actor));
        context.set("event", RuleJson.parse(event));
        return context;
    }

    private static RuleDispatcher.ClaimIntent claim(String rule, String recipient) {
        return new RuleDispatcher.ClaimIntent(new RuleRuntime.Intent(rule, "claim", "leader", 0,
                RuleJson.JSON.createObjectNode()), recipient, 0, 0);
    }

    @Test
    void keyedBenefitsBecomeAnEffectOfferAndScopedClaims() throws Exception {
        var result = LegacyCatalogConverter.convert(catalog("Rally", "Rally Die", RALLY));
        JsonNode block = result.catalog().at("/entries/0/features/1/automation");
        assertEquals("effect.started", block.at("/rules/0/bindings/0/event").asText());
        assertEquals("automatic", block.at("/rules/0/bindings/0/mode").asText());
        assertEquals("Rally Die", block.at("/rules/0/when/and/0/eq/1").asText());
        assertEquals("claim.offer", block.at("/rules/0/do/0/op").asText());
        assertEquals("others", block.at("/rules/1/bindings/0/audience").asText());
        assertEquals("otherPlayersCharacters", block.at("/rules/1/bindings/0/claimScope/recipients").asText());
        assertEquals("Maestro: gain 1 Hope", block.at("/rules/1/ui/label").asText());

        var missing = LegacyCatalogConverter.convert(catalog("Rally", "Rally Die", RALLY).replace("feat:Rally", "feat:Nothing"));
        assertTrue(missing.diagnostics().stream().anyMatch(d -> d.message().contains("No feature 'Nothing'")), missing.diagnostics().toString());
    }

    @Test
    void theOfferOpensWhenTheOwnersEffectStartsAndClaimsMatchLegacy() throws Exception {
        var sources = sources(LegacyCatalogConverter.convert(catalog("Rally", "Rally Die", RALLY)));
        RuleCompiler.Program rally = sources.get(0).program(), leader = sources.get(1).program();
        var pack = new AutomationHost.Pack("fixture", Map.of(rally.sourceId(), rally, leader.sourceId(), leader));
        var host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ari","hope":{"on":3,"slots":6},"stress":{"on":2,"slots":6},
                 "hp":{"on":0,"slots":6},"armorTrack":{"on":0,"slots":3},
                 "features":[{"name":"Rally","automationSource":"%s","instanceId":"rally"},
                             {"name":"Leader","automationSource":"%s","instanceId":"leader"}],
                 "automationState":{"revision":4}}
                """.formatted(rally.sourceId(), leader.sourceId()));
        ObjectNode event = AutomationHost.event("action.request", "owner", 0);
        var member = new PartyTargets.Member("owner", "Ari", "sheet-v1", 4, ignored -> null);
        var party = new PartyTargets(pack.revision(), "sheet-v1", ignored -> List.of(member));
        var offered = host.describe(pack, sheet, event, "sheet", "self", party);
        ObjectNode request = RuleJson.JSON.createObjectNode().put("requestId", "r1").put("expectedRevision", 4);
        request.putArray("selections").addObject().put("sourceInstanceId", "rally").put("ruleId", "use")
                .put("bindingId", "sheet").putObject("inputs");
        request.putObject("target").set("version", offered.at("/actions/0/targetSelection/version"));
        var response = host.execute(pack, sheet, event, request, "self", "owner", noRandom(), party);
        assertEquals("claim.offer", response.at("/outbox/0/kind").asText());
        assertEquals("leader", response.at("/outbox/0/sourceInstanceId").asText());
        assertEquals("owner", response.at("/outbox/0/sourceActorId").asText());

        String claimEvent = "{\"type\":\"claim.accept\",\"id\":\"c\"}";
        ObjectNode owner = context(leader, "{\"id\":\"owner\",\"name\":\"Ari\"}", claimEvent);
        var source = new RuleDispatcher.Candidate(leader, owner);
        var recipients = List.of(
                new RuleDispatcher.RecipientCandidate("mira", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"mira\",\"name\":\"Mira\",\"hope\":{\"value\":1,\"max\":6},\"stress\":{\"marked\":4,\"capacity\":6}}"), 0),
                new RuleDispatcher.RecipientCandidate("full", (ObjectNode) RuleJson.parse(
                        "{\"id\":\"full\",\"name\":\"Fen\",\"hope\":{\"value\":6,\"max\":6},\"stress\":{\"marked\":0,\"capacity\":6}}"), 0));
        String hope = "offer-rally-hope";
        String stress = "offer-rally-stress";
        var gained = RuleDispatcher.executeClaims(List.of(source), List.of(claim(hope, "mira")), recipients,
                new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault());
        assertEquals(2, gained.recipients().getFirst().snapshot().at("/hope/value").asInt());
        var cleared = RuleDispatcher.executeClaims(List.of(source), List.of(claim(stress, "mira")), recipients,
                new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault());
        assertEquals(3, cleared.recipients().getFirst().snapshot().at("/stress/marked").asInt());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(List.of(source),
                List.of(claim(hope, "full")), recipients, new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault()));
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleDispatcher.executeClaims(List.of(source),
                List.of(claim(hope, "owner")), recipients, new RuleDispatcher.ClaimLedger(0, java.util.Set.of()), RandomGenerator.getDefault()));
    }

    @Test
    void benefitsMayNameAFeatureOfAnotherCatalog() throws Exception {
        String leader = """
                {"format":"dmmap-daggerheart","version":1,"category":"subclasses","label":"Other","entries":[
                 {"id":"lead","name":"Lead","features":[
                  {"name":"Leader","automation":{"actionBenefits":{"feat:Rally":%s}}}]}]}""".formatted(RALLY);
        String home = catalog("Rally", "Rally Die", RALLY);
        var alone = LegacyCatalogConverter.convert(leader);
        assertTrue(alone.diagnostics().stream().anyMatch(d -> d.message().contains("No feature 'Rally'")));
        var joined = LegacyCatalogConverter.convert(leader, List.of(home));
        assertEquals(joined.blocks(), joined.converted(), joined.diagnostics().toString());
        assertEquals("Rally Die", joined.catalog().at("/entries/0/features/0/automation/rules/0/when/and/0/eq/1").asText());
    }

    @Test
    void anotherFeaturePairAttachesItsBenefitToItsOwnEffect() throws Exception {
        String armor = """
                [{"id":"armor","label":"Rouse: clear 1 Armor","steps":[{"op":"clear","resource":"armor","amount":1}]}]""";
        var result = LegacyCatalogConverter.convert(catalog("Warcry", "Roused", armor));
        JsonNode block = result.catalog().at("/entries/0/features/1/automation");
        assertEquals("Roused", block.at("/rules/0/when/and/0/eq/1").asText());
        assertEquals("track.clear", block.at("/rules/1/do/0/op").asText());
        assertEquals("recipient.armorTrack", block.at("/rules/1/do/0/track").asText());
        sources(result);
    }
}
