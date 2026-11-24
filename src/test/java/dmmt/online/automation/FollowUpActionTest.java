package dmmt.online.automation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static dmmt.online.automation.StructuredRollTest.faces;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy followUpAction / chatOnly (engine.ts chatOnly; Thought Delver) as a paid report plus an ordinary roll action. */
class FollowUpActionTest {
    private static final String DELVER = """
            {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","label":"Fixture","entries":[{"id":"thought-delver","name":"Thought Delver","automation":{
             "hideSpellcast":true,"actions":[
              {"id":"use","label":"Read","hostOwned":true,"followUpAction":"delve","cost":{"hope":1},"report":{"text":"Read the surface thoughts."}},
              {"id":"delve","label":"Delve","chatOnly":true,"actionRoll":{"trait":"spellcast"}}]}}]}""";
    private static final String OTHER = """
            {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","label":"Fixture","entries":[{"id":"probe","name":"Probe","automation":{
             "actions":[
              {"id":"use","label":"Probe","followUpAction":"press","cost":{"hope":2},"report":{"text":"Probed."}},
              {"id":"press","label":"Press","chatOnly":true,"actionRoll":{"trait":"Agility"}}]}}]}""";

    private static RuleCompiler.Program program(String legacy) throws Exception {
        var converted = LegacyCatalogConverter.convert(legacy);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        return RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
    }

    private static ObjectNode context(RuleCompiler.Program program) throws Exception {
        return (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"p","name":"Card"},
                 "actor":{"name":"Mind","spellcastTrait":{"value":2},"traits":{"Agility":1},"features":[],"heritage":[],
                          "hope":{"value":3,"max":6}},
                 "revision":0,"event":{"type":"action.request"}}""".formatted(program.sourceId()));
    }

    private static RuleRuntime.Resolution run(RuleCompiler.Program program, String rule, ObjectNode context) {
        return RuleRuntime.execute(program, new RuleRuntime.Intent(rule, "sheet", "p", context.path("revision").asLong(), RuleJson.JSON.createObjectNode(), null),
                context, faces(7, 4));
    }

    @Test
    void theReportSpendsOneHopeAndTheFollowUpIsAFreeSpellcastRoll() throws Exception {
        RuleCompiler.Program program = program(DELVER);
        ObjectNode before = context(program);
        var read = run(program, "use", before);
        assertEquals(2, read.context().at("/actor/hope/value").asInt());
        assertEquals("Read the surface thoughts.", read.report());
        var delve = run(program, "delve", read.context());
        assertEquals(2, delve.context().at("/actor/hope/value").asInt());
        assertEquals("Roll total: 13.", delve.report());
        // Legacy keeps the follow-up repeatable with no extra cost.
        assertEquals("Roll total: 13.", run(program, "delve", delve.context()).report());
    }

    @Test
    void aTwoHopeReportOffersAnAgilityFollowUp() throws Exception {
        RuleCompiler.Program program = program(OTHER);
        ObjectNode before = context(program);
        var probed = run(program, "use", before);
        assertEquals(1, probed.context().at("/actor/hope/value").asInt());
        assertEquals("Roll total: 12.", run(program, "press", probed.context()).report());
    }
}
