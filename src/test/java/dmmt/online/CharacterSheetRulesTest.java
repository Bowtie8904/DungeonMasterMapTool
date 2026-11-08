package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterSheetRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void totalProficiencyIncludesActiveAndBorrowedBonusesWithoutTraitBonuses() throws Exception {
        var sheet = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"proficiency":{"on":3},"level":5,
                 "loadout":[{"domain":"Grace","automation":{"conditionalModifiers":[
                    {"stat":"proficiency","value":1,"loadoutDomain":{"domain":"Grace","min":2}}]}},
                    {"name":"Copycat","domain":"Grace","borrowedCard":{"card":{"automation":{"modifiers":[
                    {"stat":"proficiency","value":1}]}}}}],
                 "features":[{"automation":{"modifiers":[{"stat":"traits","value":100}]}}],
                 "inventory":[{"qty":0,"automation":{"modifiers":[{"stat":"proficiency","value":100}]}}],
                 "effects":[{"modifiers":[{"stat":"proficiency","value":1},
                    {"stat":"proficiency","value":100,"requiresEffect":"Expired"}]}]}
                """);
        assertEquals(6, CharacterSheetRules.proficiencyTotal(sheet, 0));
        sheet.withArray("loadout").remove(1);
        assertEquals(4, CharacterSheetRules.proficiencyTotal(sheet, 0));
    }

    @Test
    void spellcastTotalIncludesActivePassiveBonusesButNotCarriedOrExpiredSources() throws Exception {
        var sheet = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"traits":[{"name":"Presence","mod":3,"spellcast":true}],
                 "activeArmor":{"automation":{"modifiers":[{"stat":"traits","value":1}]}},
                 "activeWeapons":[{"automation":{"modifiers":[{"stat":"trait.Presence","value":1}]}}],
                 "carriedWeapons":[{"automation":{"modifiers":[{"stat":"traits","value":100}]}}],
                 "features":[{"automation":{"modifiers":[{"stat":"trait.Presence","value":2},
                    {"stat":"traits","value":100,"requiresEffect":"Expired"}]}}],
                 "inventory":[{"qty":0,"automation":{"modifiers":[{"stat":"traits","value":100}]}}],
                 "effects":[{"name":"Focus","modifiers":[{"stat":"trait.Presence","value":"@tier","requiresEffect":"Focus"}]}],
                 "level":5}
                """);
        assertEquals(10, CharacterSheetRules.spellcastTotal(sheet, 0));
        sheet.withArray("effects").addObject().putObject("weapon").putObject("automation")
                .putArray("modifiers").addObject().put("stat", "trait.Presence").put("value", 3);
        assertEquals(12, CharacterSheetRules.spellcastTotal(sheet, 0));
    }

    @Test
    void conditionalModifiersCountOnlyTheMatchingActiveLoadout() throws Exception {
        var sheet = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"traits":[{"name":"Agility","mod":2,"spellcast":true}],
                 "loadout":[{"domain":"Bone","automation":{"conditionalModifiers":[
                    {"stat":"trait.Agility","value":1,"loadoutDomain":{"domain":"Bone","min":4}}]}},
                    {"domain":"bone"},{"domain":"Bone"}],
                 "vault":[{"domain":"Bone"}]}
                """);
        assertEquals(2, CharacterSheetRules.spellcastTotal(sheet, 0));
        sheet.withArray("loadout").addObject().put("domain", "BONE");
        assertEquals(3, CharacterSheetRules.spellcastTotal(sheet, 0));
        sheet.withArray("loadout").remove(3);
        assertEquals(2, CharacterSheetRules.spellcastTotal(sheet, 0));
    }

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
