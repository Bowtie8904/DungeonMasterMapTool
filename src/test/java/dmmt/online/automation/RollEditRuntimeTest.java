package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.faces;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class RollEditRuntimeTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:roll-edits", fixture("rewrite/roll-edits"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context() throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:roll-edits","instanceId":"inst-1","name":"Radiance"},
                 "actor":{"id":"actor","name":"Player","proficiency":3},
                 "revision":0}
                """);
        context.putObject("event").put("type", "roll.finished").set("roll", StructuredRoll.validate(RuleJson.parse("""
                {"id":"attack","contributions":[
                  {"id":"weapon","kind":"dice","role":"damage","sourceId":"weapon:axe","label":"Axe","dice":[
                    {"id":"w1","sides":8,"face":1,"kept":true,"origin":"rolled"},
                    {"id":"w2","sides":8,"face":8,"kept":true,"origin":"rolled"},
                    {"id":"w3","sides":8,"face":2,"kept":false,"origin":"rolled"}]},
                  {"id":"locked","kind":"dice","role":"damage","sourceId":"x:curse","label":"Curse","editable":false,"dice":[
                    {"id":"c1","sides":4,"face":1,"kept":true,"origin":"rolled"}]},
                  {"id":"hope","kind":"dice","role":"hope","sourceId":"core","label":"Hope","dice":[
                    {"id":"h1","sides":12,"face":2,"kept":true,"origin":"rolled"}]}]}
                """)));
        return context;
    }

    private static RuleRuntime.Resolution run(String rule, ObjectNode context, RandomGenerator random) throws Exception {
        return RuleRuntime.execute(program(), new RuleRuntime.Intent(rule, "result", "inst-1", 0,
                (ObjectNode) RuleJson.parse("{}")), context, random);
    }

    private static JsonNode die(JsonNode roll, String id) {
        for (JsonNode part : roll.path("contributions")) for (JsonNode die : part.path("dice"))
            if (id.equals(die.path("id").asText())) return die;
        return fail("Missing die " + id);
    }

    @Test
    void rerollFiltersByPerDieConditionAndKeepsHistory() throws Exception {
        var result = run("reroll-low", context(), faces(5));
        JsonNode roll = result.publishedRolls().getFirst();
        JsonNode edited = die(roll, "w1");
        assertEquals(5, edited.path("face").asInt());
        assertEquals("rerolled", edited.path("origin").asText());
        assertEquals("[1]", edited.path("previous").toString());
        assertEquals("[\"features:roll-edits\"]", edited.path("editedBy").toString());
        assertEquals(1, die(roll, "c1").path("face").asInt(), "Locked contributions are never edited.");
        assertEquals(2, die(roll, "w3").path("face").asInt(), "Dropped dice are never edited.");
        assertEquals(5 + 8 + 1 + 2, roll.path("total").asInt());
        assertEquals("Rerolled to 16", result.report());
        assertEquals("[5]", result.diceRolls().getFirst().path("faces").toString());
        assertEquals(12, context().path("event").path("roll").path("total").asInt(), "The original roll is not mutated.");
    }

    @Test
    void rerollCanKeepTheHigherFaceAndStillRecordsTheAttempt() throws Exception {
        JsonNode roll = run("lucky", context(), faces(3, 4)).publishedRolls().getFirst();
        assertEquals(3, die(roll, "w1").path("face").asInt());
        assertEquals(8, die(roll, "w2").path("face").asInt());
        assertEquals("[8]", die(roll, "w2").path("previous").toString());
    }

    @Test
    void setFacesMapsOnlyMatchingEditableDiceDeterministically() throws Exception {
        JsonNode roll = run("exacting", context(), noRandom()).publishedRolls().getFirst();
        assertEquals(8, die(roll, "w1").path("face").asInt());
        assertEquals("set", die(roll, "w1").path("origin").asText());
        assertEquals(1, die(roll, "c1").path("face").asInt());
        assertEquals(2, die(roll, "h1").path("face").asInt());
    }

    @Test
    void explodingChainsUntilTheConfiguredLimit() throws Exception {
        JsonNode roll = run("brutal", context(), faces(8, 8)).publishedRolls().getFirst();
        assertEquals("exploded", die(roll, "w2.x1").path("origin").asText());
        assertEquals(8, die(roll, "w2.x1.x1").path("face").asInt());
        assertEquals(1 + 8 + 8 + 8 + 1 + 2, roll.path("total").asInt());
        JsonNode dice = roll.path("contributions").get(0).path("dice");
        assertEquals("w2.x1", dice.get(2).path("id").asText(), "Exploded dice follow their source die.");
    }

    @Test
    void contributionsAddLockedDiceAndFlatBonusesWithRenderedLabels() throws Exception {
        JsonNode roll = run("smite", context(), faces(4)).publishedRolls().getFirst();
        JsonNode dice = roll.path("contributions").get(3);
        assertEquals("Radiance", dice.path("label").asText());
        assertEquals("features:roll-edits", dice.path("sourceId").asText());
        assertFalse(dice.path("editable").asBoolean());
        JsonNode flat = roll.path("contributions").get(4);
        assertEquals("Radiance bonus", flat.path("label").asText());
        assertEquals(3, flat.path("total").asInt());
        assertEquals(12 + 4 + 3, roll.path("total").asInt());
    }

    @Test
    void flatContributionsPreviewWithoutRandomnessButDiceDoNot() throws Exception {
        var intent = new RuleRuntime.Intent("flat-preview", "result", "inst-1", 0, (ObjectNode) RuleJson.parse("{}"));
        assertEquals("New total 14", RuleRuntime.preview(program(), intent, context()).report());
        var smite = new RuleRuntime.Intent("smite", "result", "inst-1", 0, (ObjectNode) RuleJson.parse("{}"));
        var error = assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.preview(program(), smite, context()));
        assertEquals("randomPreview", error.code());
    }

    @Test
    void resizeRerollsSelectedDiceWithNewSidesAndKeepsHistory() throws Exception {
        var result = run("grow-hope", context(), faces(17));
        JsonNode die = die(result.publishedRolls().getFirst(), "h1");
        assertEquals(20, die.path("sides").asInt());
        assertEquals(17, die.path("face").asInt());
        assertEquals("resized", die.path("origin").asText());
        assertEquals("[2]", die.path("previous").toString());
        assertDoesNotThrow(() -> StructuredRoll.validate(result.publishedRolls().getFirst()));
        assertEquals("[17]", result.diceRolls().getFirst().path("faces").toString());
        var intent = new RuleRuntime.Intent("grow-hope", "result", "inst-1", 0, (ObjectNode) RuleJson.parse("{}"));
        assertEquals("randomPreview", assertThrows(RuleRuntime.Unavailable.class,
                () -> RuleRuntime.preview(program(), intent, context())).code());
    }

    @Test
    void editedRollsRevalidateAndRejectForgedHistory() throws Exception {
        JsonNode roll = run("reroll-low", context(), faces(5)).publishedRolls().getFirst();
        assertDoesNotThrow(() -> StructuredRoll.validate(roll));
        ObjectNode forged = roll.deepCopy();
        ((ObjectNode) forged.path("contributions").get(0).path("dice").get(0)).putArray("previous").add(9);
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.validate(forged));
        ObjectNode unknown = roll.deepCopy();
        ((ObjectNode) unknown.path("contributions").get(0).path("dice").get(0)).put("origin", "wished");
        assertThrows(IllegalArgumentException.class, () -> StructuredRoll.validate(unknown));
    }

    @Test
    void compilerRejectsAmbiguousOrUnsafeEditDefinitions() throws Exception {
        String base = """
                {"rules":[{"id":"x","bindings":[{"id":"b","event":"roll.finished","surface":"roll","mode":"button"}],
                 "ui":{"label":"X"},"do":[%s]}]}""";
        for (String op : new String[]{
                "{\"op\":\"roll.reroll\",\"from\":{\"read\":\"event.roll\"},\"dice\":[],\"choose\":\"best\"}",
                "{\"op\":\"roll.explode\",\"from\":{\"read\":\"event.roll\"},\"dice\":[],\"when\":true,\"maxDice\":99}",
                "{\"op\":\"roll.selectDice\",\"from\":{\"read\":\"event.roll\"},\"where\":{\"eq\":[{\"read\":\"die.secret\"},1]}}",
                "{\"op\":\"roll.contribute\",\"from\":{\"read\":\"event.roll\"},\"contribution\":{\"id\":\"a\",\"kind\":\"flat\",\"role\":\"damage\",\"label\":\"A\",\"value\":1,\"sides\":6}}",
                "{\"op\":\"roll.contribute\",\"from\":{\"read\":\"event.roll\"},\"contribution\":{\"id\":\"a\",\"kind\":\"dice\",\"role\":\"damage\",\"label\":\"A\",\"count\":1}}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("features:bad", RuleJson.parse(base.formatted(op)), fixture("rewrite/recipes")), op);
        }
    }
}
