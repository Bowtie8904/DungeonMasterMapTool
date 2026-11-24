package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Shapeshifter's ancestryChoice (rest.ts 306-311, rules.ts replaceAncestry) as heritage.replace downtime rules. */
class ShapeshifterTest {
    private static String read(String name) throws Exception {
        return Files.readString(Path.of("dmmap-daggerheart", name));
    }

    private static final String MOVE = """
            {"type":"downtime.move","move":{"id":"%s","rest":"short","long":false,"ally":false,"bonus":0,"allies":0,"adjustable":{}}}""";

    private static RuleCompiler.Program shapeshifter(String rule) throws Exception {
        var result = LegacyCatalogConverter.convert(read("transformations.json"), java.util.Map.of(), read("ancestries.json"));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        return RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).stream()
                .map(RuleCatalog.Source::program).filter(program -> program.sourceId().endsWith("change-ancestry".equals(rule) ? ":features:change-shape" : ":features:only-skin-deep")).findFirst().orElseThrow();
    }

    private static RuleDispatcher.EventResolution run(RuleCompiler.Program program, String ancestry, String feature,
                                                      String rule, String pick) throws Exception {
        ObjectNode context = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"%s","instanceId":"fixture","name":"Fixture"},
                 "actor":{"id":"me","name":"Ash","ancestry":"%s","ancestryFeature":"%s"},"revision":0}""".formatted(program.sourceId(), ancestry, feature));
        context.set("event", RuleJson.parse(MOVE.formatted(rule)));
        ObjectNode inputs = RuleJson.JSON.createObjectNode();
        if (pick != null) inputs.set("pick", RuleJson.parse(pick));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent(rule, "downtime", "fixture", 0, inputs)), StructuredRollTest.noRandom());
    }

    @Test
    void converterOffersEveryAncestryFeatureAndFiltersTheSkinDeepOptions() throws Exception {
        assertNotNull(shapeshifter("change-ancestry"));
        var result = LegacyCatalogConverter.convert(read("transformations.json"), java.util.Map.of(), read("ancestries.json"));
        JsonNode change = result.catalog().at("/entries/3/features/0/automation/rules/0");
        assertEquals("record.select", change.at("/inputs/0/kind").asText());
        assertEquals(54, change.at("/inputs/0/from/literal").size());
        assertFalse(change.at("/inputs/0").has("where"));
        JsonNode skin = result.catalog().at("/entries/3/features/1/automation/rules/0");
        assertTrue(skin.at("/inputs/0").has("where"));
        assertEquals("heritage.replace", skin.at("/do/0/op").asText());
    }

    @Test
    void changeShapeTakesAnyAncestryFeature() throws Exception {
        var out = run(shapeshifter("change-ancestry"), "Giant", "Endurance", "change-ancestry",
                "{\"ancestry\":\"Elf\",\"feature\":\"Celestial Trance\",\"slot\":1}").contexts().getFirst();
        JsonNode request = out.at("/outbox/0");
        assertEquals("heritage.replace", request.path("kind").asText(), out.toString());
        assertEquals("Elf", request.path("ancestry").asText());
        assertEquals("Celestial Trance", request.path("feature").asText());
        assertEquals("Elf", out.at("/actor/ancestry").asText());
        assertEquals("Celestial Trance", out.at("/actor/ancestryFeature").asText());
    }

    @Test
    void onlySkinDeepKeepsTheAncestryAndRequiresADifferentFeatureOfIt() throws Exception {
        RuleCompiler.Program program = shapeshifter("change-feature");
        var ok = run(program, "Giant", "Endurance", "change-feature", "{\"ancestry\":\"Giant\",\"feature\":\"Reach\",\"slot\":1}").contexts().getFirst();
        assertEquals("Reach", ok.at("/outbox/0/feature").asText(), ok.toString());
        assertEquals("Giant", ok.at("/outbox/0/ancestry").asText());

        for (String bad : java.util.Arrays.asList("{\"ancestry\":\"Giant\",\"feature\":\"Endurance\",\"slot\":0}",
                "{\"ancestry\":\"Elf\",\"feature\":\"Celestial Trance\",\"slot\":1}", null))
            assertThrows(RuntimeException.class, () -> run(program, "Giant", "Endurance", "change-feature", bad), "rejected: " + bad);
    }

    @Test
    void mixedAncestryAllowsOnlyTheFirstFeatureOfTheFirstAndSecondOfTheSecond() throws Exception {
        RuleCompiler.Program program = shapeshifter("change-feature");
        var second = run(program, "Giant / Human", "Endurance", "change-feature", "{\"ancestry\":\"Human\",\"feature\":\"Adaptability\",\"slot\":1}").contexts().getFirst();
        assertEquals("Adaptability", second.at("/outbox/0/feature").asText(), second.toString());
        assertEquals("Giant / Human", second.at("/outbox/0/ancestry").asText(), "the full ancestry string is passed through");
        assertThrows(RuleRuntime.Unavailable.class, () -> run(program, "Giant / Human", "Endurance", "change-feature",
                "{\"ancestry\":\"Human\",\"feature\":\"High Stamina\",\"slot\":0}"));
    }

    /** A different mechanic: a sheet-action rule (not a downtime move) that rewrites the heritage from literals, guarded by split. */
    @Test
    void secondFixtureUsesHeritageReplaceFromAnActionWithASplitGuard() throws Exception {
        String legacy = """
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"druid","name":"Druid","features":[{"name":"Wild Dye","automation":{"actions":[{"id":"dye","label":"Dye"}]}}]}]}""";
        var converted = LegacyCatalogConverter.convert(legacy);
        ObjectNode rule = (ObjectNode) converted.catalog().at("/entries/0/features/0/automation/rules/0");
        ObjectNode step = ((com.fasterxml.jackson.databind.node.ArrayNode) rule.putArray("do")).addObject().put("op", "heritage.replace");
        step.putObject("ancestry").put("literal", "Dwarf");
        step.set("feature", RuleJson.parse("{\"if\":[{\"eq\":[{\"size\":{\"split\":[{\"read\":\"actor.ancestry\"},\" / \"]}},2]},\"Increased Fortitude\",\"Thick Skin\"]}"));
        RuleCompiler.Program program = RuleCatalog.compile(converted.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        for (var expected : java.util.Map.of("Giant / Human", "Increased Fortitude", "Giant", "Thick Skin").entrySet()) {
            ObjectNode context = (ObjectNode) RuleJson.parse("""
                    {"source":{"id":"%s","instanceId":"fixture","name":"Fixture"},"actor":{"id":"me","ancestry":"%s","ancestryFeature":""},"revision":0}"""
                    .formatted(program.sourceId(), expected.getKey()));
            context.set("event", RuleJson.parse("{\"type\":\"action.request\"}"));
            var out = RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                    List.of(new RuleRuntime.Intent("dye", "sheet", "fixture", 0, RuleJson.JSON.createObjectNode())), StructuredRollTest.noRandom()).contexts().getFirst();
            assertEquals(expected.getValue(), out.at("/outbox/0/feature").asText(), out.toString());
            assertEquals("Dwarf", out.at("/actor/ancestry").asText());
        }
    }}
