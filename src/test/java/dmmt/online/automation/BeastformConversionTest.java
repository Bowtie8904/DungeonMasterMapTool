package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Druid Evolution/Beastform, Puddleduck's Fins and Yeti Coat (druid.ts transformBeastform/assumeBeastform) as beastform.assume rules. */
class BeastformConversionTest {
    private static String read(String name) throws Exception {
        return Files.readString(Path.of("dmmap-daggerheart", name));
    }

    private static LegacyCatalogConverter.Result convertEntries(String catalog, String... ids) throws Exception {
        ObjectNode root = (ObjectNode) RuleJson.parse(read(catalog + ".json"));
        com.fasterxml.jackson.databind.node.ArrayNode kept = RuleJson.JSON.createArrayNode();
        for (JsonNode entry : root.path("entries")) if (List.of(ids).contains(entry.path("id").asText())) kept.add(entry);
        root.set("entries", kept);
        return LegacyCatalogConverter.convert(root.toString());
    }

    private static RuleCompiler.Program program(String catalog, String idSuffix) throws Exception {
        String[] ids = "classes".equals(catalog) ? new String[]{"druid"} : new String[]{"puddleducks-fins", "yeti-coat"};
        var result = convertEntries(catalog, ids);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).stream()
                .map(RuleCatalog.Source::program).filter(p -> p.sourceId().endsWith(idSuffix)).findFirst().orElseThrow();
    }

    private static RuleDispatcher.EventResolution run(RuleCompiler.Program program, String rule, String actorJson, String inputs) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"fixture","name":"Fixture"},"actor":%s,"revision":0}""".formatted(program.sourceId(), actorJson));
        context.set("event", RuleJson.parse("{\"type\":\"action.request\"}"));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent(rule, "sheet", "fixture", 0, (ObjectNode) RuleJson.parse(inputs))), StructuredRollTest.noRandom());
    }

    private static String actor(int tier, String provides) {
        return "{\"id\":\"me\",\"name\":\"Ash\",\"stats\":{\"tier\":" + tier + "},\"hope\":{\"value\":6,\"max\":6},\"stress\":{\"marked\":0,\"capacity\":12},\"provides\":" + provides + "}";
    }

    @Test
    void convertedBeastformOffersOrdinaryFormsAndOneRulePerSpecialForm() throws Exception {
        var result = convertEntries("classes", "druid");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        JsonNode rules = result.catalog().at("/entries/0/features/1/automation/rules");
        assertEquals(5, rules.size(), "ordinary + Legendary/Mythic Beast + Legendary/Mythic Hybrid");
        assertEquals(20, rules.at("/0/inputs/0/from/literal").size());
        assertEquals("beastform.assume", rules.at("/0/do/0/op").asText());
        JsonNode evolution = result.catalog().at("/entries/0/features/0/automation/rules/0");
        assertTrue(evolution.at("/do/0/evolution").asBoolean());
        assertEquals(6, evolution.at("/inputs/1/from/literal").size(), "the six traits");
    }

    @Test
    void ordinaryBeastformRequestsTheFormAndPaysOneStress() throws Exception {
        RuleCompiler.Program p = program("classes", ":features:beastform");
        var out = run(p, "use", actor(2, "{}"), "{\"form\":{\"name\":\"Powerful Beast\",\"tier\":2}}").contexts().getFirst();
        JsonNode request = out.at("/outbox/0");
        assertEquals("beastform.assume", request.path("kind").asText(), out.toString());
        assertEquals("Powerful Beast", request.path("form").asText());
        assertFalse(request.path("evolution").asBoolean());
        assertEquals(1, out.at("/actor/stress/marked").asInt());
    }

    @Test
    void puddleduckFinsMakeTheAquaticScoutFreeButNotOtherForms() throws Exception {
        RuleCompiler.Program p = program("classes", ":features:beastform");
        String free = "{\"beastformFree\":[\"Aquatic Scout\"]}";
        var aquatic = run(p, "use", actor(1, free), "{\"form\":{\"name\":\"Aquatic Scout\",\"tier\":1}}").contexts().getFirst();
        assertEquals(0, aquatic.at("/actor/stress/marked").asInt(), aquatic.toString());
        var other = run(p, "use", actor(1, free), "{\"form\":{\"name\":\"Agile Scout\",\"tier\":1}}").contexts().getFirst();
        assertEquals(1, other.at("/actor/stress/marked").asInt());
        RuleCompiler.Program fins = program("items", ":puddleducks-fins");
        assertEquals("Aquatic Scout", fins.provides().at("/beastformFree/0").asText());
    }

    @Test
    void evolutionRaisesATraitAndPaysHopeNotBaseStress() throws Exception {
        RuleCompiler.Program p = program("classes", ":features:evolution");
        var out = run(p, "use", actor(2, "{}"), "{\"form\":{\"name\":\"Agile Scout\",\"tier\":1},\"trait\":{\"name\":\"Knowledge\"}}").contexts().getFirst();
        assertTrue(out.at("/outbox/0/evolution").asBoolean(), out.toString());
        assertEquals("Knowledge", out.at("/outbox/0/trait").asText());
        assertEquals(0, out.at("/actor/stress/marked").asInt());
    }

    @Test
    void aboveTierFormsAreRejected() throws Exception {
        RuleCompiler.Program p = program("classes", ":features:beastform");
        assertThrows(RuntimeException.class, () -> run(p, "use", actor(1, "{}"), "{\"form\":{\"name\":\"Powerful Beast\",\"tier\":2}}"));
    }

    @Test
    void yetiCoatAssumesTheFormForTheSceneAtAnyTier() throws Exception {
        RuleCompiler.Program p = program("items", ":yeti-coat");
        var out = run(p, "use", actor(1, "{}"), "{}").contexts().getFirst();
        JsonNode request = out.at("/outbox/0");
        assertEquals("Powerful Beast", request.path("form").asText(), out.toString());
        assertTrue(request.path("anyTier").asBoolean());
        assertEquals("scene", request.path("until").asText());
        assertEquals(3, out.at("/actor/stress/marked").asInt());
    }

    /** A different mechanic: a custom rule with a hand-written provides flag and a long-rest form from a text input. */
    @Test
    void secondFixtureUsesProvidesAndBeastformAssumeOutsideTheDruidCatalog() throws Exception {
        String legacy = """
                {"format":"dmmap-daggerheart","version":1,"category":"items","label":"Fixture","entries":[
                 {"id":"charm","name":"Charm","automation":{"beastformFree":["Agile Scout","Pack Predator"],
                  "actions":[{"id":"shift","label":"Shift","assumeBeastform":{"form":"Agile Scout","until":"longRest"}}]}}]}""";
        var converted = LegacyCatalogConverter.convert(legacy);
        assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
        RuleCompiler.Program p = RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        assertEquals(2, p.provides().path("beastformFree").size());
        var out = run(p, "shift", actor(1, "{}"), "{}").contexts().getFirst();
        assertEquals("longRest", out.at("/outbox/0/until").asText(), out.toString());
        assertEquals("Agile Scout", out.at("/outbox/0/form").asText());
    }
}
