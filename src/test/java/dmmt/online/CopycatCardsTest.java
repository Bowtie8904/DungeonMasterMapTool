package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CopycatCardsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode borrower() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"traits":[{"name":"Presence","mod":2,"spellcast":true}],"loadout":[{"name":"Copycat",
                "borrowedCard":{"sourceActorId":"source","card":{"name":"Test Card","automation":{"modifiers":[{"stat":"traits","value":1}]}}}}],
                "vault":[],"effects":[],"resources":{"Copycat/Test Card/tokens":3},"usage":{"card:Copycat":{"used":1,"per":"longRest"},
                "card:Copycat/Test Card":{"used":1,"per":"longRest"}}}
                """);
    }

    @Test
    void printedHopeCostRoundsUpAndRejectsIneligibleLevels() {
        for (int level = 1; level <= 8; level++) assertEquals((level + 1) / 2, CopycatCards.cost(level));
        assertThrows(IllegalArgumentException.class, () -> CopycatCards.cost(0));
        assertThrows(IllegalArgumentException.class, () -> CopycatCards.cost(9));
    }

    @Test
    void canonicalCatalogPoolsEffectsAndNestedRollSourcesAreNamespaced() throws Exception {
        ObjectNode catalog = (ObjectNode) JSON.readTree("""
                {"name":"Test Card","cardType":"Grimoire","level":3,"domain":"Codex",
                 "automation":{"resources":[{"id":"tokens","initial":2}],
                 "actions":[{"id":"use","cost":{"tokens":1},"steps":[{"op":"effect","effect":{"name":"Bonus","group":"buffs"}}]}],
                 "rollTriggers":[{"id":"roll","on":"featureAction:card:Test Card:use"}]},
                 "spells":[{"name":"Spell","automation":{"rollTriggers":[{"id":"cast","on":"card:Test Card/Spell"}]}}]}
                """);
        ObjectNode copy = CopycatCards.prepare(catalog, borrower());
        assertEquals("Grimoire", copy.path("type").asText());
        assertEquals("Presence", copy.path("spellcast").asText());
        assertEquals("Copycat/Test Card/tokens", copy.path("automation").path("resources").get(0).path("id").asText());
        assertTrue(copy.path("automation").path("actions").get(0).path("cost").has("Copycat/Test Card/tokens"));
        assertEquals("Copycat/Test Card: Bonus", copy.path("automation").path("actions").get(0).path("steps").get(0).path("effect").path("name").asText());
        assertEquals("featureAction:card:Copycat/Test Card:use", copy.path("automation").path("rollTriggers").get(0).path("on").asText());
        assertEquals("card:Copycat/Test Card/Spell", copy.path("spells").get(0).path("automation").path("rollTriggers").get(0).path("on").asText());
        assertEquals("tokens", catalog.path("automation").path("resources").get(0).path("id").asText());
    }

    @Test
    void eitherRestClearsBorrowedAutomationAndPoolsButShortRestKeepsCopycatLongRestUse() throws Exception {
        for (String rest : new String[]{"shortRestsInRow", "longRestRevision"}) {
            ObjectNode sheet = borrower();
            ObjectNode previous = sheet.deepCopy();
            sheet.put(rest, 1);
            ObjectNode source = JSON.createObjectNode();
            source.putArray("loadout").addObject().put("name", "Test Card");
            CopycatCards.synchronize(new LinkedHashMap<>(Map.of("borrower", sheet, "source", source)), Map.of("borrower", previous));
            assertFalse(sheet.path("loadout").get(0).has("borrowedCard"));
            assertFalse(sheet.path("resources").has("Copycat/Test Card/tokens"));
            assertFalse(sheet.path("usage").has("card:Copycat/Test Card"));
            assertTrue(sheet.path("usage").has("card:Copycat"));
        }
    }

    @Test
    void sourceVaultingEndsCopyAndBorrowerOwnedAllyEffectsWithoutTouchingOtherCasters() throws Exception {
        ObjectNode sheet = borrower();
        ObjectNode source = JSON.createObjectNode();
        source.putArray("loadout");
        source.putArray("vault").addObject().put("name", "Test Card");
        ObjectNode ally = JSON.createObjectNode();
        ally.putArray("effects").addObject().put("id", "copied").put("sourceActorId", "borrower").put("sourceKey", "card:Copycat/Test Card");
        ally.withArray("effects").addObject().put("id", "other").put("sourceActorId", "other").put("sourceKey", "card:Test Card");
        Map<String, ObjectNode> party = new LinkedHashMap<>(Map.of("borrower", sheet, "source", source, "ally", ally));
        CopycatCards.synchronize(party, Map.of());
        assertFalse(sheet.path("loadout").get(0).has("borrowedCard"));
        assertEquals(1, ally.path("effects").size());
        assertEquals("other", ally.path("effects").get(0).path("id").asText());
    }

    @Test
    void sheetSavesCannotForgeChangeOrRemoveAnActiveBorrowedCard() throws Exception {
        ObjectNode previous = borrower();
        ObjectNode saved = previous.deepCopy();
        ((ObjectNode) saved.path("loadout").get(0)).putObject("borrowedCard").put("sourceActorId", "forged")
                .putObject("card").put("name", "Forged Card");
        CopycatCards.preserveSave(saved, previous);
        assertEquals(previous.path("loadout"), saved.path("loadout"));
        saved.putArray("loadout").addObject().put("name", "Copycat");
        CopycatCards.preserveSave(saved, previous);
        assertEquals(previous.path("loadout"), saved.path("loadout"));
        assertEquals(3, CharacterSheetRules.spellcastTotal(saved, 0));
        assertEquals(1, saved.path("loadout").size());
    }

    @Test
    void copiedInvisibilitySpendsOnlyItsLinkedPoolAndRejectsMismatchedHints() throws Exception {
        ObjectNode caster = borrower();
        ((ObjectNode) caster.path("loadout").get(0).path("borrowedCard").path("card")).put("name", "Invisibility");
        caster.withArray("loadout").addObject().put("name", "Invisibility");
        caster.withObject("/resources").put("invisibility", 5);
        InvisibilityEffects.prepareCast(caster, "card:Copycat/Invisibility", 2);
        ObjectNode recipient = JSON.createObjectNode();
        ObjectNode effect = recipient.putArray("effects").addObject().put("id", "copied")
                .put("linkedTokenResource", "Copycat/Invisibility/invisibility").put("sourceActorId", "borrower")
                .put("sourceKey", "card:Copycat/Invisibility");
        effect.putArray("rollOptions").addObject().put("id", "spend-token").put("roll", "action");
        Map<String, ObjectNode> party = new LinkedHashMap<>(Map.of("borrower", caster, "recipient", recipient));
        ObjectNode roll = JSON.createObjectNode().put("kind", "duality").put("source", "action,option:effect:copied:spend-token");
        assertTrue(InvisibilityEffects.isCast("card:Copycat/Invisibility", JSON.createObjectNode().put("id", "cast")));
        assertTrue(InvisibilityEffects.spendSelected(party, "recipient", roll));
        assertEquals(1, caster.path("resources").path("Copycat/Invisibility/invisibility").asInt());
        assertEquals(5, caster.path("resources").path("invisibility").asInt());
        effect.put("linkedTokenResource", "invisibility");
        assertThrows(IllegalArgumentException.class, () -> InvisibilityEffects.spendSelected(party, "recipient", roll));
        assertEquals(1, caster.path("resources").path("Copycat/Invisibility/invisibility").asInt());
        effect.put("linkedTokenResource", "Copycat/Invisibility/invisibility");
        caster.withArray("vault").add(caster.withArray("loadout").remove(0));
        InvisibilityEffects.synchronize(party);
        assertEquals(0, caster.path("resources").path("Copycat/Invisibility/invisibility").asInt());
        assertEquals(5, caster.path("resources").path("invisibility").asInt());
        assertTrue(recipient.path("effects").isEmpty());
    }
}
