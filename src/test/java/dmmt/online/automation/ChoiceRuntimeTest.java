package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.RuleConformanceTest.fixture;
import static dmmt.online.automation.StructuredRollTest.noRandom;
import static org.junit.jupiter.api.Assertions.*;

/** Independent (non-shipped) fixture for the persisted actor-scoped named choice. */
class ChoiceRuntimeTest {
    private static final String PACK = """
            {"rules":[
              {"id":"vow","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
               "inputs":[{"id":"mood","kind":"choice","label":"Mood","count":1,"options":[{"id":"calm","label":"Calm"},{"id":"fierce","label":"Fierce"}]}],
               "do":[{"op":"choice.set","key":"mood","value":{"read":"input.mood"},"as":"picked"}],"ui":{"label":"Vow","report":"Vowed"}},
              {"id":"relax","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
               "do":[{"op":"choice.clear","key":"mood","as":"cleared"}],"ui":{"label":"Relax"}},
              {"id":"echo","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
               "do":[{"op":"value","value":{"read":"actor.choices.mood"},"as":"mood"}],"ui":{"label":"Echo","report":"Mood {result.mood}"}}]}""";

    private static RuleCompiler.Program program(String pack) throws Exception {
        return RuleCompiler.compile("features:vow", RuleJson.parse(pack), fixture("rewrite/recipes"));
    }

    private static ObjectNode facts(String choices) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:vow","instanceId":"v","name":"Vow"},"actor":{"choices":%s},
                 "revision":0,"event":{"type":"action.request"}}""".formatted(choices));
    }

    private static RuleRuntime.Resolution run(RuleCompiler.Program program, String rule, ObjectNode facts, String inputs) throws Exception {
        return RuleRuntime.execute(program, new RuleRuntime.Intent(rule, "use", "v", facts.path("revision").asLong(), (ObjectNode) RuleJson.parse(inputs)), facts, noRandom());
    }

    @Test
    void choicesPersistAreReadableAndClearable() throws Exception {
        RuleCompiler.Program program = program(PACK);
        var set = run(program, "vow", facts("{}"), "{\"mood\":\"fierce\"}");
        assertEquals("fierce", set.context().at("/actor/choices/mood").asText());
        assertEquals("Mood fierce", run(program, "echo", set.context(), "{}").report());
        var cleared = run(program, "relax", set.context(), "{}");
        assertFalse(cleared.context().at("/actor/choices").has("mood"));
        var replaced = run(program, "vow", set.context(), "{\"mood\":\"calm\"}");
        assertEquals("calm", replaced.context().at("/actor/choices/mood").asText());
    }

    @Test
    void invalidKeysAndValuesAreRejected() throws Exception {
        for (String op : new String[]{
                "{\"op\":\"choice.set\",\"key\":\"Bad Key\",\"value\":\"x\"}",
                "{\"op\":\"choice.clear\",\"key\":\"\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> program(
                    "{\"rules\":[{\"id\":\"x\",\"bindings\":[{\"id\":\"b\",\"event\":\"action.request\",\"surface\":\"sheet\",\"mode\":\"button\"}],"
                            + "\"ui\":{\"label\":\"X\"},\"do\":[" + op + "]}]}"), op);
        }
        RuleCompiler.Program program = program("""
                {"rules":[{"id":"x","bindings":[{"id":"b","event":"action.request","surface":"sheet","mode":"button"}],
                 "ui":{"label":"X"},"do":[{"op":"choice.set","key":"k","value":{"read":"actor.n"}}]}]}""");
        ObjectNode facts = facts("{}");
        facts.withObject("/actor").put("n", 3);
        assertThrows(RuntimeException.class, () -> RuleRuntime.execute(program,
                new RuleRuntime.Intent("x", "b", "v", 0, (ObjectNode) RuleJson.parse("{}")), facts, noRandom()));
    }
}
