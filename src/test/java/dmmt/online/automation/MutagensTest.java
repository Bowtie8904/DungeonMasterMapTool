package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Legacy mutagens (rest.ts brewMutagens, blood-hunter.test.ts) as a downtime rule with a ranged choice input. */
class MutagensTest {
    private static final AutomationHost HOST = AutomationHost.bundled();

    private static String legacy(String bonusByFeature, String limitByFeature, String cost) {
        return """
                {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
                 {"id":"hunter","name":"Hunter","features":[{"name":"Mutagens","type":"subclass","automation":{"actions":[
                  {"id":"use","label":"Mutagens","timing":"downtime","downtime":{"additional":true},"mutagens":{
                   "traitBonus":1,"traitBonusByFeature":%s,"maxBenefitsByFeature":%s,%s
                   "benefits":[
                    {"id":"durable","effect":{"name":"Durable","kind":"buff","until":"rest","text":"Armor +2.","modifiers":[{"stat":"armorScore","value":2}]}},
                    {"id":"rapidity","requiresFeature":"Improved Mutagens","effect":{"name":"Rapidity","kind":"buff","until":"rest","text":"Evasion +1.","modifiers":[{"stat":"evasion","value":1}]}},
                    {"id":"steelflesh","requiresFeature":"Mastered Mutagens","effect":{"name":"Steelflesh","kind":"buff","until":"rest","text":"Major +1.","modifiers":[{"stat":"majorThreshold","value":1}]}}]}}]}}]}]}"""
                .formatted(bonusByFeature, limitByFeature, cost);
    }

    private static final String FULL = legacy("{\"Mastered Mutagens\":2}", "{\"Volatile Toxins\":2}", "\"extraBenefitCost\":{\"hp\":1},");

    private static final class Fixture {
        final AutomationHost.Pack pack;
        final String sourceId;

        Fixture(String legacy) throws Exception {
            var converted = LegacyCatalogConverter.convert(legacy);
            assertTrue(converted.diagnostics().isEmpty(), converted.diagnostics().toString());
            pack = AutomationHost.compile("test", Map.of("classes", converted.catalog().toString()),
                    LegacyCatalogConverter.coreRecipes(), LegacyCatalogConverter.coreRuleset());
            sourceId = pack.programs().values().stream().map(RuleCompiler.Program::sourceId)
                    .filter(id -> id.startsWith("classes:")).findFirst().orElseThrow();
        }

        ObjectNode sheet(String... features) throws Exception {
            StringBuilder list = new StringBuilder("{\"name\":\"Mutagens\",\"automationSource\":\"" + sourceId + "\",\"instanceId\":\"f1\"}");
            int n = 0;
            for (String name : features) list.append(",{\"name\":\"").append(name).append("\",\"automationItem\":\"x").append(n++).append("\"}");
            return (ObjectNode) RuleJson.parse("""
                    {"name":"Ada","level":2,"hp":{"slots":6,"on":0},"stress":{"slots":6,"on":0},"armorTrack":{"slots":3,"on":0},
                     "hope":{"slots":6,"on":0},"features":[%s],"heritage":[],"loadout":[],"vault":[],"inventory":[],
                     "activeWeapons":[],"activeArmor":null}""".formatted(list));
        }

        ObjectNode brew(ObjectNode sheet, String increase, String decrease, String... benefits) {
            ObjectNode origin = AutomationHost.event("action.request", "self", 0).put("sessionId", "session");
            ObjectNode prepared = HOST.prepareRest(pack, sheet, origin, "short", 0, RuleJson.JSON.createArrayNode(),
                    StructuredRollTest.noRandom());
            var progress = HOST.restProgress(pack, prepared);
            ObjectNode selection = RuleJson.JSON.createObjectNode().put("sourceInstanceId", "f1")
                    .put("ruleId", "use").put("bindingId", "downtime");
            ObjectNode inputs = selection.putObject("inputs").put("increase", increase).put("decrease", decrease);
            var array = inputs.putArray("benefits");
            for (String benefit : benefits) array.add(benefit);
            HOST.restMove(pack, sheet, (ObjectNode) prepared.get("event"), progress, selection,
                    RuleJson.JSON.createArrayNode(), StructuredRollTest.noRandom());
            return sheet;
        }
    }

    private static JsonNode effect(ObjectNode sheet, String label) {
        for (JsonNode effect : sheet.path("automationState").path("effects"))
            if (label.equals(effect.path("label").asText())) return effect;
        return null;
    }

    private static int traitModifier(JsonNode effect, String trait) {
        for (JsonNode modifier : effect.path("modifiers"))
            if (("trait." + trait).equals(modifier.path("stat").asText())) return modifier.path("value").asInt();
        throw new AssertionError("no " + trait + " modifier in " + effect);
    }

    @Test
    void converterOutputOffersTraitChoicesAndFeatureGatedRangedBenefits() throws Exception {
        var converted = LegacyCatalogConverter.convert(FULL);
        assertEquals(1, converted.converted(), converted.diagnostics().toString());
        JsonNode rule = converted.catalog().at("/entries/0/features/0/automation/rules/0");
        assertEquals("downtime.move", rule.at("/bindings/0/event").asText());
        JsonNode benefits = rule.at("/inputs/2");
        assertEquals("benefits", benefits.path("id").asText());
        assertEquals(2, benefits.path("count").asInt());
        assertEquals(1, benefits.path("min").asInt());
        assertFalse(benefits.at("/options/0").has("when"));
        assertTrue(benefits.at("/options/1").has("when"), "requiresFeature gates the option");
        assertEquals("action.reject", rule.at("/do/0/op").asText());
    }

    @Test
    void oneBenefitCostsNothingAndStartsTheTraitEffectWithTheBaseBonus() throws Exception {
        Fixture f = new Fixture(FULL);
        ObjectNode sheet = f.brew(f.sheet(), "Agility", "Strength", "durable");
        assertEquals(0, sheet.at("/hp/on").asInt());
        JsonNode traits = effect(sheet, "Mutagens");
        assertEquals(1, traitModifier(traits, "Agility"));
        assertEquals(-1, traitModifier(traits, "Strength"));
        assertEquals(0, traitModifier(traits, "Finesse"));
        assertTrue(traits.path("text").asText().contains("Agility"));
        assertNotNull(effect(sheet, "Durable"));
        assertNull(effect(sheet, "Volatile Toxins"));
        assertEquals(2, sheet.at("/automationState/effects").size());
    }

    @Test
    void masteredMutagensDoubleTheBonusAndTwoBenefitsMarkHpAndLockIt() throws Exception {
        Fixture f = new Fixture(FULL);
        ObjectNode sheet = f.brew(f.sheet("Improved Mutagens", "Volatile Toxins", "Mastered Mutagens"),
                "Agility", "Strength", "rapidity", "steelflesh");
        assertEquals(1, sheet.at("/hp/on").asInt(), "the extra benefit marks 1 HP");
        assertEquals(2, traitModifier(effect(sheet, "Mutagens"), "Agility"));
        assertEquals(-2, traitModifier(effect(sheet, "Mutagens"), "Strength"));
        assertNotNull(effect(sheet, "Rapidity"));
        assertNotNull(effect(sheet, "Steelflesh"));
        assertEquals(1, effect(sheet, "Volatile Toxins").at("/locks/hp").asInt());
    }

    @Test
    void invalidBrewsAreRejectedWithoutChangingTheSheet() throws Exception {
        Fixture f = new Fixture(FULL);
        ObjectNode same = f.sheet();
        assertThrows(RuntimeException.class, () -> f.brew(same, "Agility", "Agility", "durable"));
        assertEquals(0, same.path("automationState").path("effects").size());
        ObjectNode gated = f.sheet();
        assertThrows(RuntimeException.class, () -> f.brew(gated, "Agility", "Strength", "rapidity"), "needs Improved Mutagens");
        ObjectNode limited = f.sheet("Improved Mutagens");
        assertThrows(RuntimeException.class, () -> f.brew(limited, "Agility", "Strength", "durable", "rapidity"), "needs Volatile Toxins");
        assertEquals(0, limited.at("/hp/on").asInt());
        ObjectNode full = f.sheet("Improved Mutagens", "Volatile Toxins");
        full.with("hp").put("on", 6);
        assertThrows(RuntimeException.class, () -> f.brew(full, "Agility", "Strength", "durable", "rapidity"), "no free HP");
        assertEquals(0, full.path("automationState").path("effects").size());
    }

    @Test
    void aSecondFixtureWithAnotherBonusAndNoCostUsesTheSamePrimitive() throws Exception {
        Fixture f = new Fixture(legacy("{}", "{\"Volatile Toxins\":3}", ""));
        ObjectNode sheet = f.brew(f.sheet("Improved Mutagens", "Volatile Toxins", "Mastered Mutagens"),
                "Presence", "Knowledge", "durable", "rapidity", "steelflesh");
        assertEquals(0, sheet.at("/hp/on").asInt(), "no extra cost declared");
        assertEquals(1, traitModifier(effect(sheet, "Mutagens"), "Presence"));
        assertNull(effect(sheet, "Volatile Toxins"));
        assertEquals(4, sheet.at("/automationState/effects").size());
    }

    @Test
    void rangedChoiceAcceptsAnyCountInItsRangeAndEffectTextReadsTheChoice() throws Exception {
        RuleCompiler.Program program = RuleCompiler.compile("features:picks", RuleJson.parse("""
                {"rules":[{"id":"pick","bindings":[{"id":"use","event":"action.request","surface":"sheet","mode":"button"}],
                 "inputs":[{"id":"gems","kind":"choice","label":"Gems","count":3,"min":2,
                   "options":[{"id":"ruby","label":"Ruby"},{"id":"opal","label":"Opal"},{"id":"jade","label":"Jade"},{"id":"onyx","label":"Onyx"}]}],
                 "do":[{"op":"value","value":{"size":{"read":"input.gems"}},"as":"n"}],
                 "ui":{"label":"Pick","report":"Picked {result.n}."}}]}
                """), RuleConformanceTest.fixture("rewrite/recipes"));
        ObjectNode facts = (ObjectNode) RuleJson.parse("""
                {"source":{"id":"features:picks","instanceId":"p","name":"Picks"},"actor":{},"revision":0,"event":{"type":"action.request"}}""");
        assertEquals(2, RuleRuntime.describe(program, "pick", "use", facts).at("/inputs/0/min").asInt());
        for (String ids : new String[]{"[\"ruby\",\"opal\"]", "[\"ruby\",\"opal\",\"jade\"]"}) {
            var intent = new RuleRuntime.Intent("pick", "use", "p", 0, (ObjectNode) RuleJson.parse("{\"gems\":" + ids + "}"));
            assertEquals("Picked " + (ids.split(",").length) + ".", RuleRuntime.execute(program, intent, facts, StructuredRollTest.noRandom()).report());
        }
        for (String bad : new String[]{"[\"ruby\"]", "[\"ruby\",\"ruby\"]", "[\"ruby\",\"opal\",\"jade\",\"onyx\"]"})
            assertThrows(IllegalArgumentException.class, () -> RuleRuntime.execute(program,
                    new RuleRuntime.Intent("pick", "use", "p", 0, (ObjectNode) RuleJson.parse("{\"gems\":" + bad + "}")),
                    facts, StructuredRollTest.noRandom()));
    }
}