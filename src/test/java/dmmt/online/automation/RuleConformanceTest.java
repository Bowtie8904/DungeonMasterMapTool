package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleConformanceTest {
    static ObjectNode fixture(String name) throws IOException {
        try (InputStream input = RuleConformanceTest.class.getResourceAsStream("/dmmt/automation/" + name + ".json")) {
            assertNotNull(input, name);
            return (ObjectNode) RuleJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @TestFactory
    List<DynamicTest> sharedExpressionsAndText() throws Exception {
        JsonNode data = fixture("values");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode example : data.path("expressions")) tests.add(DynamicTest.dynamicTest(example.path("name").asText(), () -> {
            if (example.path("error").asBoolean())
                assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate(example.get("expression"), example.get("context")));
            else assertTrue(RuleExpression.equal(example.get("expected"), RuleExpression.evaluate(example.get("expression"), example.get("context"))), example.toString());
        }));
        for (JsonNode example : data.path("templates")) tests.add(DynamicTest.dynamicTest(example.path("name").asText(), () -> {
            if (example.path("error").asBoolean())
                assertThrows(IllegalArgumentException.class, () -> RuleTemplate.render(example.path("template").asText(), example.get("context")));
            else assertEquals(example.path("expected").asText(), RuleTemplate.render(example.path("template").asText(), example.get("context")));
        }));
        return tests;
    }

    @Test
    void preRollChoicesCannotDependOnUnseenDiceButTheirEffectsMayEditThem() throws Exception {
        ObjectNode rule = (ObjectNode) RuleJson.parse("""
            {"id":"boost","bindings":[{"id":"use","event":"roll.prepare","surface":"roll","mode":"option"}],
             "do":[{"op":"roll.check","from":{"read":"event.roll"},"as":"seen"}],"ui":{"label":"Boost"}}""");
        ObjectNode definition = RuleJson.JSON.createObjectNode();
        definition.putArray("rules").add(rule);
        ObjectNode recipes = fixture("rewrite/recipes");
        assertDoesNotThrow(() -> RuleCompiler.compile("test:boost", definition, recipes));
        rule.set("when", RuleJson.parse("{\"exists\":\"event.check.dominant\"}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RuleCompiler.compile("test:boost", definition, recipes)).getMessage().contains("roll.finished"));
        rule.remove("when");
        rule.set("costs", RuleJson.parse("""
            [{"op":"resource.spend","pool":"actor.hope","amount":{"read":"event.check.hopeDie"}}]"""));
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test:boost", definition, recipes));
        rule.remove("costs");
        ((ObjectNode) rule.get("ui")).put("description", "Total {event.check.total}");
        assertThrows(IllegalArgumentException.class, () -> RuleCompiler.compile("test:boost", definition, recipes));
        ((ObjectNode) rule.at("/bindings/0")).put("event", "roll.finished").put("mode", "button");
        assertDoesNotThrow(() -> RuleCompiler.compile("test:boost", definition, recipes));
    }

    @Test
    void inputParsingRejectsDuplicatesTrailingTokensAndReservedFields() {
        assertThrows(IOException.class, () -> RuleJson.parse("{\"rules\":[],\"rules\":[]}"));
        assertThrows(IOException.class, () -> RuleJson.parse("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> RuleJson.parse("{\"constructor\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> RuleJson.parse("1e999"));
    }

    @Test
    void boundedTreeValidationRetainsPreciseLocationsAndLimits() {
        ObjectNode tree = RuleJson.JSON.createObjectNode();
        ObjectNode item = tree.putArray("items").addObject();
        item.put("value", 9_007_199_254_740_991L);
        assertDoesNotThrow(() -> RuleJson.checkTree(tree, "/root", 0, new int[]{0}, 4));
        item.put("value", Double.POSITIVE_INFINITY);
        assertEquals("/root/items/0/value: Number exceeds the safe numeric range.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(tree, "/root", 0, new int[]{0})).getMessage());
        item.put("value", "x".repeat(16_001));
        assertEquals("/root/items/0/value: Text is too long.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(tree, "/root", 0, new int[]{0})).getMessage());
        item.remove("value");
        item.put("constructor", true);
        assertEquals("/root/items/0/constructor: Reserved field name.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(tree, "/root", 0, new int[]{0})).getMessage());
        item.remove("constructor");
        item.put("value", 1);
        int[] count = {0};
        assertEquals("/root/items/0/value: Rule data exceeds its complexity limit.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(tree, "/root", 0, count, 3)).getMessage());
        assertEquals(4, count[0]);
        item.set("value", com.fasterxml.jackson.databind.node.MissingNode.getInstance());
        assertEquals("/root/items/0/value: Missing JSON value.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(tree, "/root", 0, new int[]{0})).getMessage());
        ObjectNode deep = RuleJson.JSON.createObjectNode(), cursor = deep;
        for (int i = 0; i <= RuleJson.MAX_DEPTH; i++) cursor = cursor.putObject("next");
        assertEquals("/root" + "/next".repeat(RuleJson.MAX_DEPTH + 1) + ": Rule data exceeds its complexity limit.",
                assertThrows(IllegalArgumentException.class,
                        () -> RuleJson.checkTree(deep, "/root", 0, new int[]{0})).getMessage());
    }

    @Test
    void identifierAndContextPathValidationRemainStrict() {
        for (String id : List.of("a", "A0_.:-", "9" + "x".repeat(127), "constructor"))
            assertEquals(id, RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/id"));
        for (String id : List.of("", "_a", "a b", "a/", "a" + "x".repeat(128), "a\u00e9"))
            assertThrows(IllegalArgumentException.class,
                    () -> RuleJson.id(RuleJson.JSON.getNodeFactory().textNode(id), "/id"), id);
        for (String path : List.of("actor.hp", "event._x-1", "state" + ".a".repeat(11)))
            assertDoesNotThrow(() -> RuleExpression.validateRead(path, "/read"), path);
        for (String path : List.of("actor", "other.hp", "state" + ".a".repeat(12), "actor.",
                "actor.9", "actor.a/b", "actor.constructor", "actor.prototype", "actor.__proto__", "actor.\u00e9"))
            assertThrows(IllegalArgumentException.class, () -> RuleExpression.validateRead(path, "/read"), path);
    }

    @Test
    void expressionsAndTemplatesHaveBoundsAndNeverCoerceOrEvaluateText() {
        ObjectNode node = RuleJson.JSON.createObjectNode().put("read", "actor.value");
        for (int i = 0; i < 40; i++) node = RuleJson.JSON.createObjectNode().set("negate", node);
        ObjectNode deep = node;
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.evaluate(deep, RuleJson.JSON.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> RuleTemplate.validate("a".repeat(8001)));
        assertThrows(IllegalArgumentException.class, () -> RuleTemplate.render("{source.name}{source.name}", RuleJson.JSON.createObjectNode()
                .set("source", RuleJson.JSON.createObjectNode().put("name", "a".repeat(9000)))));
    }
}
