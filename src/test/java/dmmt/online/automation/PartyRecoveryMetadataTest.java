package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartyRecoveryMetadataTest {
    private static ObjectNode automation() throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"rules":[{"id":"share","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                 "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":128,"label":"First",
                        "partyRecovery":{"track":"armorTrack","scope":"activePlayerCharacters"}},
                       {"op":"plan.adjust","plan":"event.move","field":"allies","by":1,"label":"Clamped",
                        "partyRecovery":{"track":"stress","scope":"activeCharacters"}},
                       {"op":"plan.adjust","plan":"event.move","field":"allies","by":-1000,"label":"Cancel"}],
                 "ui":{"label":"Share"}}]}
                """);
    }

    private static RuleCompiler.Program compile(ObjectNode automation) throws Exception {
        return RuleCompiler.compile("items:share", automation, RuleJson.parse(LegacyCatalogConverter.coreRecipes()));
    }

    @Test
    void requestedAmountsAndDistinctRecoveryDeclarationsSurviveClampingAndCancellation() throws Exception {
        var program = compile(automation());
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"items:share","instanceId":"share-1","name":"Share"},
                 "actor":{"id":"ada"},"revision":0,"event":{"type":"downtime.move","actorId":"ada",
                 "move":{"allies":0,"adjustable":{"allies":{"min":0,"max":128}}}}}
                """);
        var result = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(), StructuredRollTest.noRandom());
        JsonNode move = result.contexts().getFirst().at("/event/move");
        assertEquals(0, move.path("allies").asInt());
        JsonNode adjustments = move.path("adjustments");
        assertEquals(3, adjustments.size());
        assertEquals(128, adjustments.at("/0/requested").asInt());
        assertEquals("armorTrack", adjustments.at("/0/partyRecovery/track").asText());
        assertEquals("activePlayerCharacters", adjustments.at("/0/partyRecovery/scope").asText());
        assertEquals(1, adjustments.at("/1/requested").asInt());
        assertEquals(0, adjustments.at("/1/by").asInt());
        assertEquals("stress", adjustments.at("/1/partyRecovery/track").asText());
        assertEquals("activeCharacters", adjustments.at("/1/partyRecovery/scope").asText());
        assertEquals(-1000, adjustments.at("/2/requested").asInt());
        assertFalse(adjustments.get(2).has("partyRecovery"));
        assertEquals("items:share", adjustments.at("/1/sourceId").asText());
        assertEquals("share-1", adjustments.at("/1/sourceInstanceId").asText());
    }

    @Test
    void compilerRejectsInvalidOrMisplacedRecoveryDeclarations() throws Exception {
        ObjectNode valid = automation();
        assertDoesNotThrow(() -> compile(valid));
        ObjectNode absent = valid.deepCopy();
        ((ObjectNode) absent.at("/rules/0/do/0")).remove("partyRecovery");
        assertDoesNotThrow(() -> compile(absent), "Existing plan adjustments remain valid.");

        for (String invalid : List.of(
                "{}",
                "{\"track\":\"armorTrack\"}",
                "{\"scope\":\"activePlayerCharacters\"}",
                "{\"track\":\"actor.armorTrack\",\"scope\":\"activePlayerCharacters\"}",
                "{\"track\":\"armorTrack\",\"scope\":\"everyone\"}",
                "{\"track\":\"armorTrack\",\"scope\":\"activePlayerCharacters\",\"extra\":true}",
                "null", "\"armor\"")) {
            ObjectNode bad = valid.deepCopy();
            ((ObjectNode) bad.at("/rules/0/do/0")).set("partyRecovery", RuleJson.parse(invalid));
            assertThrows(IllegalArgumentException.class, () -> compile(bad), invalid);
        }
        ObjectNode wrongPlan = valid.deepCopy();
        ((ObjectNode) wrongPlan.at("/rules/0/do/0")).put("plan", "event.rest");
        assertThrows(IllegalArgumentException.class, () -> compile(wrongPlan));
    }

    @Test
    void unusedRecipesValidateConcreteMetadataAndExpandedArguments() throws Exception {
        String catalog = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[]}""";
        ObjectNode recipes = (ObjectNode) RuleJson.parse("""
                {"format":"dmmap-daggerheart-rules","version":2,"recipes":[{"id":"test:share",
                 "args":{"amount":{"type":"integer"},"track":{"type":"string"}},"ui":{},"rules":[
                   {"id":"share","bindings":[{"id":"move","event":"downtime.move","surface":"downtime","mode":"automatic"}],
                    "do":[{"op":"plan.adjust","plan":"event.move","field":"allies","by":{"$arg":"amount"},"label":"Share",
                           "partyRecovery":{"track":{"$arg":"track"},"scope":"activePlayerCharacters"}}],
                    "ui":{"label":"Share"}}]}]}
                """);
        assertDoesNotThrow(() -> RuleCatalog.compile(catalog, recipes.toString()));
        ObjectNode invalidScope = recipes.deepCopy();
        ((ObjectNode) invalidScope.at("/recipes/0/rules/0/do/0/partyRecovery")).put("scope", "everyone");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidScope.toString()));
        ObjectNode invalidPlan = recipes.deepCopy();
        ((ObjectNode) invalidPlan.at("/recipes/0/rules/0/do/0")).put("plan", "event.rest");
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.compile(catalog, invalidPlan.toString()));

        String expanded = """
                {"format":"dmmap-daggerheart","version":2,"category":"items","entries":[
                 {"id":"share","name":"Share","automation":{"rules":[
                  {"id":"use","useRecipe":"test:share","args":{"amount":1,"track":"armorTrack"}}]}}]}""";
        assertDoesNotThrow(() -> RuleCatalog.compile(expanded, recipes.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> RuleCatalog.compile(expanded.replace("armorTrack", "actor.armorTrack"), recipes.toString()));
    }
}
