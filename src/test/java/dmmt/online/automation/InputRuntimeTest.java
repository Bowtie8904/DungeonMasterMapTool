package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

class InputRuntimeTest {
    private static RuleCompiler.Program program() throws Exception {
        return RuleCompiler.compile("features:inputs", fixture("rewrite/inputs"), fixture("rewrite/recipes"));
    }

    private static ObjectNode context(int stress) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:inputs","instanceId":"inst-1","name":"Leader"},
                 "actor":{"id":"actor","name":"Player","proficiency":3,"stress":%d,
                          "allies":[{"id":"ally-1","label":"Ari"},{"id":"ally-2","label":"Bo"}]},
                 "revision":0,"event":{"type":"action.request"}}
                """.formatted(stress));
    }

    private static RuleRuntime.Resolution run(String rule, ObjectNode context, String inputs) throws Exception {
        return RuleRuntime.execute(program(), new RuleRuntime.Intent(rule, "sheet", "inst-1", 0,
                (ObjectNode) RuleJson.parse(inputs)), context, noRandom());
    }

    @Test
    void dynamicOptionsComeFromHostFactsAndOnlyThoseIdsAreAccepted() throws Exception {
        JsonNode input = RuleRuntime.describe(program(), "inspire", "sheet", context(0)).path("inputs").get(0);
        assertEquals("[{\"id\":\"ally-1\",\"label\":\"Ari\"},{\"id\":\"ally-2\",\"label\":\"Bo\"}]", input.path("options").toString());
        assertEquals("Inspired ally-2", run("inspire", context(0), "{\"ally\":\"ally-2\"}").report());
        assertThrows(IllegalArgumentException.class, () -> run("inspire", context(0), "{\"ally\":\"ally-9\"}"));
        ObjectNode alone = context(0);
        alone.withObject("/actor").putArray("allies");
        assertThrows(RuleRuntime.Unavailable.class, () -> run("inspire", alone, "{\"ally\":\"ally-1\"}"));
    }

    @Test
    void allocationsMustSumExactlyRespectPerOptionMaximaAndAreNormalized() throws Exception {
        JsonNode input = RuleRuntime.describe(program(), "split", "sheet", context(1)).path("inputs").get(0);
        assertEquals(3, input.path("total").asInt());
        assertEquals(2, input.path("max").asInt());
        assertEquals(3, input.path("options").size());
        ObjectNode inputs = (ObjectNode) RuleJson.parse("{\"split\":{\"armor\":2,\"stress\":1}}");
        var result = RuleRuntime.execute(program(), new RuleRuntime.Intent("split", "sheet", "inst-1", 0, inputs), context(1), noRandom());
        assertEquals("2/0/1", result.report());
        assertEquals("{\"split\":{\"armor\":2,\"stress\":1}}", inputs.toString(), "Intent inputs are not mutated.");
        assertThrows(IllegalArgumentException.class, () -> run("split", context(1), "{\"split\":{\"armor\":3}}"));
        assertThrows(IllegalArgumentException.class, () -> run("split", context(1), "{\"split\":{\"armor\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> run("split", context(0), "{\"split\":{\"armor\":2,\"stress\":1}}"),
                "Options hidden by when cannot receive an allocation.");
        assertThrows(IllegalArgumentException.class, () -> run("split", context(1), "{\"split\":{\"armor\":-1,\"hope\":2,\"stress\":2}}"));
    }

    @Test
    void compilerRequiresExactlyOneOptionSource() throws Exception {
        String base = """
                {"rules":[{"id":"x","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                 "ui":{"label":"X"},"inputs":[%s],"do":[{"op":"value","value":1,"as":"v"}]}]}""";
        for (String input : new String[]{
                "{\"id\":\"a\",\"kind\":\"choice\",\"label\":\"A\"}",
                "{\"id\":\"a\",\"kind\":\"choice\",\"label\":\"A\",\"options\":[{\"id\":\"o\",\"label\":\"O\"}],\"optionsFrom\":{\"read\":\"actor.allies\"}}",
                "{\"id\":\"a\",\"kind\":\"choice\",\"label\":\"A\",\"optionsFrom\":{\"read\":\"actor.allies\"},\"count\":65}",
                "{\"id\":\"a\",\"kind\":\"allocation\",\"label\":\"A\",\"optionsFrom\":{\"read\":\"secret.list\"},\"total\":1}",
                "{\"id\":\"a\",\"kind\":\"allocation\",\"label\":\"A\",\"options\":[{\"id\":\"o\",\"label\":\"O\"}]}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("features:bad", RuleJson.parse(base.formatted(input)), fixture("rewrite/recipes")), input);
        }
    }
}
