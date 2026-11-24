package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class LinkedTokensTest {
    static final String LEGACY = """
            {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","label":"Fixture","entries":[
             {"id":"invisibility","name":"Invisibility","automation":{"spellcastDifficulty":10,
              "resources":[{"id":"invisibility","label":"Invisibility tokens","initial":0}],
              "rollTriggers":[{"id":"cast","label":"Choose Invisibility recipient","on":"card:Invisibility","when":"success",
               "difficulty":10,"target":"anyActor","cost":{"stress":1},"steps":[{"op":"effect","effect":{
                "name":"Invisibility","kind":"buff","until":"manual","exclusiveSource":true,"linkedTokenResource":"invisibility",
                "text":"Invisible.","rollOptions":[{"id":"spend-token","label":"Invisibility: spend token","roll":"action",
                 "defaultOn":true}]}}]}]}}]}""";

    static final String SHIELD = LEGACY.replace("\"Invisibility\"", "\"Shield\"").replace("card:Invisibility", "card:Shield")
            .replace("\"invisibility\"", "\"charges\"").replace("spend-token", "spend-charge");

    private static RuleCompiler.Program program(String legacy) throws Exception {
        var converted = LegacyCatalogConverter.convert(legacy);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        return RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
    }

    private static ObjectNode cast(RuleCompiler.Program program, String origin, int spellcast, int pool) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"card","name":"Card"},
                 "actor":{"id":"caster","name":"Caster","stress":{"marked":0,"capacity":6},"hope":{"value":2,"max":6},
                  "stats":{"spellcast":{"total":%d}},"effects":[]},
                 "recipient":{"id":"friend","name":"Friend","effects":[]},
                 "sourcePools":{"%s":{"value":%d,"max":1000000}},
                 "revision":0,
                 "event":{"type":"roll.finished","check":{"origin":"%s","total":12,"critical":false,"kind":"spellcast"}}}
                """.formatted(program.sourceId(), spellcast, program.pools().fieldNames().next(), pool, origin));
        return context;
    }

    private void parity(String legacy, String origin, String pool, String effect, String option) throws Exception {
        var program = program(legacy);
        ObjectNode before = cast(program, origin, 3, 5);
        var intent = new RuleRuntime.Intent("cast", "use", "card", 0, RuleJson.JSON.createObjectNode());
        var result = RuleRuntime.execute(program, intent, before, noRandom());
        assertEquals(3, result.context().at("/sourcePools/" + pool + "/value").asInt(), "pool is replaced by the Spellcast total");
        assertEquals(1, result.context().at("/actor/stress/marked").asInt());
        String effects = result.context().at("/actor/effects").toString() + result.context().at("/recipient/effects");
        assertTrue(effects.contains(effect), effects);

        ObjectNode low = cast(program, origin, -2, 5);
        assertEquals(0, RuleRuntime.execute(program, intent, low, noRandom()).context().at("/sourcePools/" + pool + "/value").asInt());

        ObjectNode roll = cast(program, origin, 3, 2);
        roll.set("event", RuleJson.parse("{\"type\":\"roll.prepare\",\"check\":{\"kind\":\"action\"}}"));
        roll.withArray("/actor/effects").addObject().put("id", "e1").put("label", effect.contains("Shield") ? "Shield" : "Invisibility");
        var spend = RuleRuntime.execute(program, new RuleRuntime.Intent(option, "use", "card", 0, RuleJson.JSON.createObjectNode()),
                roll, noRandom());
        assertEquals(1, spend.context().at("/sourcePools/" + pool + "/value").asInt());
        roll.withObject("/sourcePools/" + pool).put("value", 0);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(program,
                new RuleRuntime.Intent(option, "use", "card", 0, RuleJson.JSON.createObjectNode()), roll, noRandom()));
    }

    @Test
    void invisibilityMatchesLegacy() throws Exception {
        parity(LEGACY, "card:Invisibility", "invisibility", "Invisibility", "spend-token");
    }

    @Test
    void shieldChargesUseTheSameLinkedTokenPrimitive() throws Exception {
        parity(SHIELD, "card:Shield", "charges", "Shield", "spend-charge");
    }
}