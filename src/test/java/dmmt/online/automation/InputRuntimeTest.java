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
    void recordChoicesUseCurrentHostRecordsWithoutDependingOnTheirOrder() throws Exception {
        RuleCompiler.Program tools = RuleCompiler.compile("features:tools", RuleJson.parse("""
                {"rules":[{"id":"work","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                 "inputs":[{"id":"tool","kind":"record.select","label":"Choose a tool",
                   "from":{"read":"actor.tools"},"choiceLabel":"{input.option.name}: {input.option.bonus|signed}"}],
                 "do":[{"op":"value","value":{"read":"input.tool.bonus"},"as":"bonus"}],
                 "ui":{"label":"Work","report":"Used {input.tool.name} for {result.bonus}."}}]}
                """), fixture("rewrite/recipes"));
        ObjectNode facts = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:tools","instanceId":"tools","name":"Tools"},
                 "actor":{"tools":[{"name":"Rope kit","bonus":2},{"name":"Lock kit","bonus":3}]},
                 "revision":0,"event":{"type":"action.request"}}
                """);
        JsonNode options = RuleRuntime.describe(tools, "work", "use", facts).at("/inputs/0/options");
        assertEquals("Rope kit: +2", options.get(0).path("label").asText());
        assertEquals("Lock kit: +3", options.get(1).path("label").asText());
        ObjectNode inputs = (ObjectNode) RuleJson.parse("{\"tool\":{\"name\":\"Lock kit\",\"bonus\":3}}");
        RuleRuntime.Intent intent = new RuleRuntime.Intent("work", "use", "tools", 0, inputs);
        assertEquals("Used Lock kit for 3.", RuleRuntime.execute(tools, intent, facts, noRandom()).report());
        ((com.fasterxml.jackson.databind.node.ArrayNode) facts.at("/actor/tools")).insert(0,
                facts.at("/actor/tools/1").deepCopy());
        assertEquals("Used Lock kit for 3.", RuleRuntime.execute(tools, intent, facts, noRandom()).report());
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(tools,
                new RuleRuntime.Intent("work", "use", "tools", 0,
                        (ObjectNode) RuleJson.parse("{\"tool\":{\"name\":\"Lock kit\",\"bonus\":9}}")), facts, noRandom()));
        ((ObjectNode) facts.at("/actor/tools/0")).put("bonus", 4);
        ((ObjectNode) facts.at("/actor/tools/2")).put("bonus", 4);
        assertThrows(RuleRuntime.Unavailable.class, () -> RuleRuntime.execute(tools, intent, facts, noRandom()));
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
    void optionGroupsAllowAtMostOneSelectionAndEffectValuesMayReadInputs() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:groups", RuleJson.parse("""
                {"effects":{"boost":{"label":"Boost","until":["rest.long"],"value":{"initial":{"read":"input.amount"}}}},
                 "rules":[{"id":"pick","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                 "inputs":[{"id":"amount","kind":"choice","label":"Amount","count":1,"options":[{"id":"1","label":"One"},{"id":"2","label":"Two"}]},
                   {"id":"pick","kind":"choice","label":"Pick","count":2,"options":[
                   {"id":"a-x","label":"A x","group":"a"},{"id":"a-y","label":"A y","group":"a"},{"id":"b","label":"B"}]}],
                 "do":[{"op":"value","value":1,"as":"v"}],"ui":{"label":"Pick"}}]}
                """), fixture("rewrite/recipes"));
        ObjectNode facts = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:groups","instanceId":"g","name":"G"},"actor":{},"revision":0,"event":{"type":"action.request"}}""");
        assertEquals("a", RuleRuntime.describe(program, "pick", "use", facts).at("/inputs/1/options/0/group").asText());
        assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program, new RuleRuntime.Intent("pick", "use", "g", 0,
                (ObjectNode) RuleJson.parse("{\"amount\":\"1\",\"pick\":[\"a-x\",\"a-y\"]}")), facts, noRandom()));
        RuleRuntime.execute(program, new RuleRuntime.Intent("pick", "use", "g", 0,
                (ObjectNode) RuleJson.parse("{\"amount\":\"2\",\"pick\":[\"a-y\",\"b\"]}")), facts, noRandom());
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
                "{\"id\":\"a\",\"kind\":\"record.select\",\"label\":\"A\",\"from\":{\"read\":\"actor.allies\"},\"choiceLabel\":\"{input.unknown.name}\"}",
                "{\"id\":\"a\",\"kind\":\"record.select\",\"label\":\"A\",\"from\":{\"read\":\"actor.allies\"},\"choiceLabel\":\"{die.face}\"}",
                "{\"id\":\"a\",\"kind\":\"allocation\",\"label\":\"A\",\"optionsFrom\":{\"read\":\"secret.list\"},\"total\":1}",
                "{\"id\":\"a\",\"kind\":\"allocation\",\"label\":\"A\",\"options\":[{\"id\":\"o\",\"label\":\"O\"}]}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RuleCompiler.compile("features:bad", RuleJson.parse(base.formatted(input)), fixture("rewrite/recipes")), input);
        }
    }
}
