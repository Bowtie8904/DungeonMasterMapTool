package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static dmmt.online.automation.RuleConformanceTest.fixture;

class StructuredRollTest {
    @Test
    void sharedContinuationsPreserveOriginalDiceAndRecalculateDependencies() throws Exception {
        ObjectNode fixture = fixture("rolls");
        ObjectNode roll = (ObjectNode) fixture.get("roll");
        ObjectNode before = roll.deepCopy();
        assertEquals(46, StructuredRoll.validate(roll).path("total").asInt());
        assertEquals(4, StructuredRoll.dice(roll, "weaponBase", true).size());
        for (JsonNode example : fixture.path("derivations")) {
            if (example.path("error").asBoolean())
                assertThrows(IllegalArgumentException.class, () -> StructuredRoll.derive(roll, fixture.get("eligible"), example.get("keep")));
            else assertEquals(example.path("expected").asInt(), StructuredRoll.derive(roll, fixture.get("eligible"), example.get("keep")).path("total").asInt());
        }
        assertEquals(before, roll);
    }

    @Test
    void labelsAndSourceNamesDoNotSelectArithmetic() throws Exception {
        ObjectNode data = fixture("rolls");
        ObjectNode roll = (ObjectNode) data.get("roll");
        for (JsonNode part : roll.path("contributions")) ((ObjectNode) part).put("label", "Not the name of any known feature");
        assertEquals(40, StructuredRoll.derive(roll, data.get("eligible"), data.path("derivations").get(0).get("keep")).path("total").asInt());
        ((ObjectNode) roll.path("contributions").get(3)).put("factor", 3);
        assertEquals(60, StructuredRoll.derive(roll, data.get("eligible"), data.path("derivations").get(0).get("keep")).path("total").asInt());
    }

    @Test
    void checkSummarisesDualityDiceAndOutcomeAgainstDifficulty() throws Exception {
        String template = """
                {"id":"spell","metadata":{"difficulty":%s},"contributions":[
                 {"id":"hope","kind":"dice","role":"hope","sourceId":"core","label":"Hope","dice":[{"id":"h","sides":12,"face":%d,"kept":true,"origin":"rolled"}]},
                 {"id":"fear","kind":"dice","role":"fear","sourceId":"core","label":"Fear","dice":[{"id":"f","sides":12,"face":%d,"kept":true,"origin":"rolled"}]},
                 {"id":"trait","kind":"flat","role":"modifier","sourceId":"core","label":"Trait","value":1}]}""";
        JsonNode win = StructuredRoll.check(RuleJson.parse(template.formatted("10", 8, 3)));
        assertEquals(12, win.path("total").asInt());
        assertEquals("hope", win.path("dominant").asText());
        assertFalse(win.path("critical").asBoolean());
        assertEquals("success", win.path("outcome").asText());
        JsonNode miss = StructuredRoll.check(RuleJson.parse(template.formatted("20", 2, 9)));
        assertEquals("fear", miss.path("dominant").asText());
        assertEquals("failure", miss.path("outcome").asText());
        JsonNode critical = StructuredRoll.check(RuleJson.parse(template.formatted("30", 4, 4)));
        assertTrue(critical.path("critical").asBoolean());
        assertEquals("success", critical.path("outcome").asText());
        JsonNode open = StructuredRoll.check(RuleJson.parse(template.formatted("null", 4, 5)));
        assertTrue(open.path("outcome").isNull());
    }

    @Test
    void bonusDiceAndLocalMultipliersRemainSeparate() throws Exception {
        ObjectNode data = fixture("rolls");
        ObjectNode roll = (ObjectNode) data.get("roll");
        roll.withArray("contributions").add(RuleJson.parse("""
                {"id":"extra","kind":"dice","role":"bonus","sourceId":"another","label":"Extra",
                 "sign":-1,"dice":[{"id":"extra1","sides":6,"face":5,"kept":true,"origin":"stored"}]}
                """));
        assertEquals(41, StructuredRoll.validate(roll).path("total").asInt());
        assertEquals(35, StructuredRoll.derive(roll, data.get("eligible"), data.path("derivations").get(0).get("keep")).path("total").asInt());
    }

    @Test
    void resolvedRollsPreserveGenericMetadata() throws Exception {
        ObjectNode data = fixture("rolls");
        ObjectNode roll = ((ObjectNode) data.get("roll")).deepCopy();
        roll.set("metadata", RuleJson.parse("{\"kind\":\"reaction\",\"trait\":\"Agility\",\"difficulty\":12}"));
        assertEquals(roll.path("metadata"), StructuredRoll.validate(roll).path("metadata"));
        assertEquals(roll.path("metadata"), StructuredRoll.derive(roll, data.get("eligible"), data.path("derivations").get(0).path("keep")).path("metadata"));
        roll.set("metadata", RuleJson.JSON.createArrayNode());
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.validate(roll));
    }

    @Test
    void corruptTotalsCyclesAndDroppedDiceAreRejected() throws Exception {
        ObjectNode data = fixture("rolls");
        ObjectNode roll = StructuredRoll.validate(data.get("roll"));
        roll.put("total", 999);
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.validate(roll));
        roll.remove("total");
        ((ObjectNode) roll.path("contributions").get(3)).putArray("of").add("double");
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.validate(roll));
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.derive(data.get("roll"), RuleJson.parse("[\"a3\"]"), RuleJson.parse("[]")));
    }

    @Test
    void scaleOfAllMultipliesTheWholeEarlierRoll() throws Exception {
        ObjectNode plan = (ObjectNode) RuleJson.parse("""
                {"id":"roll","contributions":[
                  {"id":"bonus","kind":"flat","role":"bonus","sourceId":"weapon","label":"Flat","value":4},
                  {"id":"half","kind":"scale","role":"scale","sourceId":"other","label":"Half","of":["bonus"],"factor":2},
                  {"id":"extra","kind":"flat","role":"bonus","sourceId":"weapon","label":"Extra","value":1},
                  {"id":"triple","kind":"scale","role":"multiplier","sourceId":"other","label":"Triple","of":"all","factor":3}
                ]}
                """);
        ObjectNode roll = StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), faces());
        assertEquals((4 + 4 + 1) * 3, roll.path("total").asInt(), roll.toString());
        assertEquals("all", roll.path("contributions").get(3).path("of").asText());
        StructuredRoll.validate(roll);
    }

    @Test
    void resolvesMixedPoolsKeptFacesAndMaximizedDiceFromExpressions() throws Exception {
        ObjectNode plan = (ObjectNode) RuleJson.parse("""
                {"id":"roll","contributions":[
                  {"id":"d8","kind":"dice","role":"base","sourceId":"weapon","label":"Eight",
                   "count":{"add":[{"read":"actor.proficiency"},1]},"sides":8,"keepHighest":2,"maximize":1},
                  {"id":"d6","kind":"dice","role":"base","sourceId":"weapon","label":"Six","count":2,"sides":6},
                  {"id":"bonus","kind":"flat","role":"bonus","sourceId":"weapon","label":"Flat","value":4},
                  {"id":"double","kind":"scale","role":"scale","sourceId":"other","label":"Anything","of":["d8","d6","bonus"],"factor":2}
                ]}
                """);
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"actor\":{\"proficiency\":2}}");
        ObjectNode roll = StructuredRoll.resolve(plan, context, faces(3, 1, 6, 2));
        assertEquals(46, roll.path("total").asInt());
        assertEquals("maximized", roll.path("contributions").get(0).path("dice").get(0).path("origin").asText());
        assertFalse(roll.path("contributions").get(0).path("dice").get(2).path("kept").asBoolean());
        assertEquals(5, StructuredRoll.dice(roll, null, false).size());
        assertEquals(4, StructuredRoll.dice(roll, "base", true).size());
    }

    @Test
    void perPoolEditsValidateBeforeSamplingAndKeepMixedDiceSeparate() throws Exception {
        ObjectNode roll = (ObjectNode) RuleJson.parse("""
                {"id":"mixed","contributions":[
                 {"id":"d8","kind":"dice","role":"damage","sourceId":"weapon","label":"Eight","dice":[
                   {"id":"e1","sides":8,"face":2,"kept":true,"origin":"rolled"},
                   {"id":"e2","sides":8,"face":7,"kept":true,"origin":"rolled"}]},
                 {"id":"d6","kind":"dice","role":"damage","sourceId":"weapon","label":"Six","dice":[
                   {"id":"s1","sides":6,"face":3,"kept":true,"origin":"rolled"},
                   {"id":"s2","sides":6,"face":6,"kept":true,"origin":"rolled"}]}]}""");
        JsonNode selected = RuleJson.parse("[\"e1\",\"e2\",\"s1\",\"s2\"]");
        ObjectNode before = roll.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.addDicePerPool(
                roll, RuleJson.parse("[\"e1\",\"unknown\"]"), 1, "feature", noRandom(), 1024));
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.addDicePerPool(
                roll, selected, 2, "feature", noRandom(), 3));
        assertEquals(before, roll);
        JsonNode added = StructuredRoll.addDicePerPool(roll, selected, 1, "feature", faces(4, 5), 2);
        assertEquals(8, added.at("/contributions/0/dice/2/sides").intValue());
        assertEquals(6, added.at("/contributions/1/dice/2/sides").intValue());
        assertEquals("[\"feature\"]", added.at("/contributions/1/dice/2/editedBy").toString());
        JsonNode all = RuleJson.parse("[\"e1\",\"e2\",\"d8.a1\",\"s1\",\"s2\",\"d6.a1\"]");
        JsonNode dropped = StructuredRoll.dropLowestPerPool(added, all, 1);
        assertEquals(7 + 4 + 6 + 5, dropped.path("total").intValue());
        assertEquals(2, dropped.path("excludedDice").size());
        assertEquals(before, roll);
    }

    @Test
    void zeroCountDicePreserveFlatContributionsWithoutSamplingRandomness() throws Exception {
        ObjectNode plan = (ObjectNode) RuleJson.parse("""
                {"id":"zero","contributions":[
                  {"id":"scaled","kind":"dice","role":"damage","sourceId":"feature","label":"Scaled","count":0,"sides":8},
                  {"id":"flat","kind":"flat","role":"damage","sourceId":"feature","label":"Bonus","value":6}
                ]}
                """);
        ObjectNode roll = StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), noRandom());
        assertEquals(6, roll.path("total").asInt());
        assertTrue(roll.at("/contributions/0/dice").isEmpty());
        assertEquals(0, StructuredRoll.dice(roll, "damage", false).size());
    }

    @Test
    void invalidPlansDoNotSampleRandomness() throws Exception {
        ObjectNode plan = (ObjectNode) RuleJson.parse("""
                {"id":"roll","contributions":[
                 {"id":"pool","kind":"dice","role":"base","sourceId":"weapon","label":"Pool","count":2,"sides":8},
                 {"id":"unknown","kind":"featureSpecific","label":"Forbidden"}
                ]}
                """);
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.resolve(plan, RuleJson.JSON.createObjectNode(), noRandom()));
    }

    static Random faces(int... faces) {
        return new Random(0) {
            int index;
            @Override public int nextInt(int bound) {
                assertTrue(index < faces.length, "Unexpected random sample");
                int face = faces[index++];
                assertTrue(face > 0 && face <= bound);
                return face - 1;
            }
        };
    }

    static Random noRandom() {
        return new Random(0) {
            @Override public int nextInt(int bound) { fail("Unexpected random sample"); return 0; }
        };
    }
}
