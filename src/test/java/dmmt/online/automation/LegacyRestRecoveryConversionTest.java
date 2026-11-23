package dmmt.online.automation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyRestRecoveryConversionTest {
    private static LegacyCatalogConverter.Result convert(String automation) throws Exception {
        return LegacyCatalogConverter.convert("""
                {"format":"dmmap-daggerheart","version":1,"category":"domain-cards","entries":[
                 {"id":"camp","name":"Camp","automation":%s}]}
                """.formatted(automation));
    }

    private static RuleCompiler.Program program(LegacyCatalogConverter.Result result) {
        return AutomationHost.compile("legacy-rest", Map.of("domain-cards", result.catalog().toString()),
                LegacyCatalogConverter.coreRecipes()).programs().get("domain-cards:camp");
    }

    @Test
    void convertsOnlyPositiveFixedSourceRestRecoveries() throws Exception {
        var positive = convert("""
                {"rest":{"modifiers":[{"move":"tend","repairAllies":1000}]}}""");
        assertTrue(positive.diagnostics().isEmpty(), positive.diagnostics().toString());
        var scheduled = program(positive).restRecoveries("tend");
        assertEquals(1, scheduled.size());
        assertEquals(1000, scheduled.getFirst().amount());
        assertEquals("loadout", scheduled.getFirst().sourcePath());
        assertEquals("armorTrack", scheduled.getFirst().track());
        assertEquals("activePlayerCharacters", scheduled.getFirst().scope());

        for (String value : List.of("0", "-1", "-1001")) {
            var ignored = convert("""
                    {"rest":{"modifiers":[{"move":"tend","repairAllies":%s}]}}""".formatted(value));
            assertTrue(ignored.diagnostics().isEmpty(), ignored.diagnostics().toString());
            assertTrue(program(ignored).restRecoveries("tend").isEmpty());
            JsonNode rules = ignored.catalog().at("/entries/0/automation/rules");
            assertFalse(rules.toString().contains("plan.adjust"));
        }

        for (String value : List.of("1001", "1.5", "\"2\"", "\"@tier\"", "true", "2147483648")) {
            var invalid = convert("""
                    {"rest":{"modifiers":[{"move":"tend","repairAllies":%s}]}}""".formatted(value));
            assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.path().endsWith("/repairAllies")),
                    invalid.diagnostics().toString());
            assertEquals(0, invalid.converted());
        }
    }

    @Test
    void effectCarriedRestModifiersAreNotSearchedByTheLegacyPartyRecoveryLoop() throws Exception {
        var result = convert("""
                {"actions":[{"id":"open","label":"Open","steps":[{"op":"effect","effect":{
                 "name":"Lantern","kind":"buff","until":"rest",
                 "rest":{"modifiers":[{"move":"tend","bonus":2,"repairAllies":3}]}}}]}]}
                """);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        var program = program(result);
        assertTrue(program.restRecoveries("tend").isEmpty());
        assertTrue(result.catalog().toString().contains("\"field\":\"bonus\""));
        assertFalse(result.catalog().toString().contains("partyRecovery"));
    }
}
