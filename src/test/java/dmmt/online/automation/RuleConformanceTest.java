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
    void inputParsingRejectsDuplicatesTrailingTokensAndReservedFields() {
        assertThrows(IOException.class, () -> RuleJson.parse("{\"rules\":[],\"rules\":[]}"));
        assertThrows(IOException.class, () -> RuleJson.parse("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> RuleJson.parse("{\"constructor\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> RuleJson.parse("1e999"));
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
