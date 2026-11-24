package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chained dice (until lower), the tally projection kind and projection captures. Legacy: DiceRoller.combo,
 * brawler.comboDie, rollOptionDice/secondaryWeaponDie in engine.ts.
 */
class ChainedDiceTest {
    private static final String COMBO = """
            {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
              {"id":"combo","name":"Combo Strike","automation":{"rollOptions":[
                {"id":"stress","label":"Combo Strike","roll":"damage","weaponRange":"Melee","combo":true,"cost":{"stress":1}},
                {"id":"focus","label":"Flow State: Combo Strike","roll":"damage","weaponRange":"Melee","combo":true,"cost":{"hope":1}}]}}]}""";
    private static final String SECONDARY = """
            {"format":"dmmap-daggerheart","version":1,"category":"classes","label":"Fixture","entries":[
              {"id":"spec","name":"Weapon Specialist","automation":{"rollOptions":[
                {"id":"secondary","label":"Secondary die","roll":"damage","secondaryWeaponDie":true,"cost":{"hope":1}}]}}]}""";
    private static final String DAMAGE = """
            {"type":"roll.prepare","check":{"kind":"damage","range":"%s","trait":null},"roll":{"id":"d","contributions":[
              {"id":"base","kind":"flat","role":"damage","sourceId":"core","label":"Base","value":2}]}}""";

    private static LegacyCatalogConverter.Result convert(String legacy) throws Exception {
        var result = LegacyCatalogConverter.convert(legacy);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        return result;
    }

    private static JsonNode damage(String legacy, String actor, String range, String rule, java.util.random.RandomGenerator random) throws Exception {
        var result = convert(legacy);
        RuleCompiler.Program program = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"source\":{\"id\":\"" + program.sourceId()
                + "\",\"instanceId\":\"fixture\",\"name\":\"Fixture\"},\"actor\":" + actor + ",\"revision\":0}");
        context.set("event", RuleJson.parse(DAMAGE.formatted(range)));
        return RuleDispatcher.executeEvent(List.of(new RuleDispatcher.Candidate(program, context)),
                List.of(new RuleRuntime.Intent(rule, "use", "fixture", 0, RuleJson.JSON.createObjectNode())), random)
                .contexts().getFirst().at("/event/roll");
    }

    @Test
    void comboStrikeRollsTheComboDieUntilAResultIsLowerThanThePreviousOne() throws Exception {
        String actor = "{\"stats\":{\"comboDie\":4},\"hope\":{\"value\":2,\"max\":6},\"stress\":{\"marked\":1,\"capacity\":6},\"effects\":[]}";
        JsonNode roll = damage(COMBO, actor, "melee", "stress", StructuredRollTest.faces(2, 3, 1));
        assertEquals(8, roll.path("total").asInt(), roll.toString());
        JsonNode combo = roll.at("/contributions/1");
        assertEquals(List.of(2, 3, 1), combo.path("dice").findValuesAsText("face").stream().map(Integer::valueOf).toList());
        assertEquals(4, combo.at("/dice/0/sides").asInt());
        assertFalse(combo.path("editable").asBoolean(true), "Combo results cannot be modified.");
        assertEquals(13, damage(COMBO, actor, "melee", "focus", StructuredRollTest.faces(3, 3, 3, 2)).path("total").asInt(),
                "Equal results continue the chain; 3+3+3+2 plus the base 2.");
    }

    @Test
    void comboStrikeOffersOneCostAtATimeOnMeleeDamageOnly() throws Exception {
        var result = convert(COMBO);
        JsonNode rules = result.catalog().at("/entries/0/automation/rules");
        assertEquals("combo-strike", rules.get(0).at("/bindings/0/exclusiveGroup").asText(), rules.toString());
        assertEquals("combo-strike", rules.get(1).at("/bindings/0/exclusiveGroup").asText());
        assertTrue(rules.toString().contains("\"until\":\"lower\""), rules.toString());
        assertTrue(rules.toString().contains("actor.stats.comboDie"), rules.toString());
        RuleCompiler.Program program = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"source\":{\"id\":\"" + program.sourceId()
                + "\",\"instanceId\":\"fixture\",\"name\":\"Fixture\"},\"actor\":{\"stats\":{\"comboDie\":4},\"hope\":{\"value\":2,\"max\":6},\"stress\":{\"marked\":1,\"capacity\":6},\"effects\":[]},\"revision\":0}");
        context.set("event", RuleJson.parse(DAMAGE.formatted("far")));
        assertTrue(RuleDispatcher.describeRollOptions(List.of(new RuleDispatcher.Candidate(program, context)), audience -> true).isEmpty(), "Ranged damage offers no combo.");
    }

    @Test
    void theComboDieGrowsWithEachComboAdvancementUpToD12() throws Exception {
        AutomationHost host = AutomationHost.bundled();
        for (int[] expected : new int[][]{{0, 4}, {1, 6}, {2, 8}, {3, 10}, {4, 12}, {6, 12}}) {
            StringBuilder levels = new StringBuilder("[");
            for (int i = 0; i < expected[0]; i++) levels.append(i > 0 ? "," : "").append("{\"level\":").append(i + 2)
                    .append(",\"advancements\":[{\"type\":\"combo\"},{\"type\":\"evasion\"}]}");
            levels.append((expected[0] > 0 ? "," : "") + "{\"level\":9,\"advancements\":[{\"type\":\"hp\"}]}]");
            ObjectNode sheet = (ObjectNode) RuleJson.parse("{\"name\":\"Ada\",\"level\":9,\"levelUps\":" + levels + "}");
            ObjectNode actor = host.projection().actor(sheet);
            assertEquals(expected[1], host.projection().stats(actor, List.of()).path("comboDie").asInt(), actor.toString());
        }
        assertEquals(4, host.projection().stats(host.projection().actor((ObjectNode) RuleJson.parse("{\"name\":\"Ada\"}")), List.of())
                .path("comboDie").asInt(), "Sheets without level-ups keep the d4.");
    }

    @Test
    void secondaryWeaponDieAddsOneDieOfTheSecondaryWeaponsSize() throws Exception {
        for (String[] weapon : new String[][]{{"d8+2", "8"}, {"1d10", "10"}, {"2d6+1", "6"}}) {
            String actor = "{\"secondaryWeapon\":{\"name\":\"Dagger\",\"damage\":\"" + weapon[0] + "\",\"dieSides\":" + weapon[1] + "},\"hope\":{\"value\":2,\"max\":6},\"effects\":[]}";
            JsonNode roll = damage(SECONDARY, actor, "melee", "secondary", StructuredRollTest.faces(3));
            assertEquals(5, roll.path("total").asInt(), roll.toString());
            assertEquals(Integer.parseInt(weapon[1]), roll.at("/contributions/1/dice/0/sides").asInt());
        }
        var result = convert(SECONDARY);
        RuleCompiler.Program program = RuleCatalog.compile(result.catalog().toString(), LegacyCatalogConverter.coreRecipes()).getFirst().program();
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"source\":{\"id\":\"" + program.sourceId()
                + "\",\"instanceId\":\"fixture\",\"name\":\"Fixture\"},\"actor\":{\"effects\":[]},\"revision\":0}");
        context.set("event", RuleJson.parse(DAMAGE.formatted("melee")));
        assertTrue(RuleDispatcher.describeRollOptions(List.of(new RuleDispatcher.Candidate(program, context)), audience -> true).isEmpty(), "Without a secondary weapon die the option is unavailable.");
    }

    @Test
    void theProjectionCapturesTheSecondaryDieSizeFromTheSheet() throws Exception {
        AutomationHost host = AutomationHost.bundled();
        ObjectNode sheet = (ObjectNode) RuleJson.parse("""
                {"name":"Ada","activeWeapons":[{"name":"Sword","slot":"Primary","damage":"d10+3"},{"name":"Dagger","slot":"Secondary","damage":"d6+1"}]}""");
        assertEquals(6, host.projection().actor(sheet).at("/secondaryWeapon/dieSides").asInt());
        ObjectNode flat = (ObjectNode) RuleJson.parse("{\"name\":\"Ada\",\"activeWeapons\":[{\"name\":\"Fist\",\"slot\":\"Secondary\",\"damage\":\"+3\"}]}");
        assertTrue(host.projection().actor(flat).at("/secondaryWeapon/dieSides").isMissingNode());
    }

    @Test
    void aSecondMechanicChainsAFixedRollPlanUntilLower() throws Exception {
        ObjectNode plan = (ObjectNode) RuleJson.parse("""
                {"id":"chain","contributions":[
                  {"id":"chain","kind":"dice","role":"damage","sourceId":"core","label":"Chain","sides":{"read":"actor.n"},"until":"lower"},
                  {"id":"bonus","kind":"flat","role":"damage","sourceId":"core","label":"Bonus","value":1}]}""");
        ObjectNode context = (ObjectNode) RuleJson.parse("{\"actor\":{\"n\":6}}");
        ObjectNode roll = StructuredRoll.resolve(plan, context, StructuredRollTest.faces(5, 6, 6, 4));
        assertEquals(22, roll.path("total").asInt(), roll.toString());
        assertEquals(4, roll.at("/contributions/0/dice").size());
        ObjectNode invalid = (ObjectNode) RuleJson.parse("""
                {"id":"x","contributions":[{"id":"c","kind":"dice","role":"damage","sourceId":"core","label":"C","count":2,"sides":6,"until":"lower"}]}""");
        assertThrows(RuntimeException.class, () -> StructuredRoll.resolve(invalid, context, StructuredRollTest.noRandom()));
    }
}
