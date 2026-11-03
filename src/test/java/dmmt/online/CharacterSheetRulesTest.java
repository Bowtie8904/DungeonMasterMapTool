package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CharacterSheetRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalLoadoutKeepsItsLimitAndRespectsLegitimateCapacityBonuses() throws Exception {
        JsonNode sheet = mapper.readTree("""
                {"loadout":[{},{},{},{},{},{}]}
                """);
        assertNotNull(CharacterSheetRules.problem(sheet, mapper.createObjectNode(), false, 0));
        ((com.fasterxml.jackson.databind.node.ObjectNode) sheet).putObject("activeArmor")
                .putObject("automation").putArray("modifiers").addObject().put("stat", "loadoutMax").put("value", 1);
        assertNull(CharacterSheetRules.problem(sheet, mapper.createObjectNode(), false, 0));
    }

    @Test
    void onlyTestingCharactersCanFreelyChangeIdentityAndMulticlass() throws Exception {
        JsonNode previous = mapper.readTree("""
                {"className":"Ranger","subclass":"Wayfinder","ancestry":"Human","community":"Wildborne"}
                """);
        JsonNode next = previous.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).put("className", "Warrior");
        assertNotNull(CharacterSheetRules.problem(next, previous, false, 0));
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).put("testingCharacter", true);
        assertNull(CharacterSheetRules.problem(next, previous, true, 0));
        JsonNode legitimate = mapper.readTree("""
                {"levelUps":[{"level":5,"advancements":[{"type":"multiclass"}]}]}
                """);
        assertNull(CharacterSheetRules.problem(legitimate, mapper.createObjectNode(), false, 0));
        JsonNode early = mapper.readTree("""
                {"levelUps":[{"level":2,"advancements":[{"type":"multiclass"}]}]}
                """);
        assertNotNull(CharacterSheetRules.problem(early, mapper.createObjectNode(), false, 0));
        JsonNode repeated = mapper.readTree("""
                {"levelUps":[{"level":5,"advancements":[{"type":"multiclass"},{"type":"multiclass"}]}]}
                """);
        assertNotNull(CharacterSheetRules.problem(repeated, mapper.createObjectNode(), false, 0));
        assertNull(CharacterSheetRules.problem(previous, previous, false, 0));
    }

    @Test
    void expressionsMatchBrowserCapacityIncludingInactiveInventoryAndEffects() throws Exception {
        JsonNode sheet = mapper.readTree("""
                {"level":5,"loadout":[{},{},{},{},{},{},{}],
                 "inventory":[{"qty":0,"automation":{"modifiers":[{"stat":"loadoutMax","value":-20}]}}],
                 "effects":[{"modifiers":[{"stat":"loadoutMax","value":"@tier"}]}]}
                """);
        assertNull(CharacterSheetRules.problem(sheet, mapper.createObjectNode(), false, 0));
    }
}
