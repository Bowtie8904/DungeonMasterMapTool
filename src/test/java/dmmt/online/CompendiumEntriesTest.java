package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompendiumEntriesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode sheet() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"className":"Bard","inventory":[],"carriedArmor":[],"carriedWeapons":[],"loadout":[],"vault":[],
                "traits":[{"name":"Presence","spellcast":true}],"domains":["Grace"]}
                """);
    }

    @Test
    void convertsEquipmentLikeTheBrowserAndPreservesAutomation() throws Exception {
        ObjectNode sheet = sheet();
        JsonNode armor = MAPPER.readTree("""
                {"id":"a","name":"Armor","tier":2,"armor":{"score":4,"major":7,"severe":15},
                "feature":{"name":"Flexible","text":"+1 evasion"},"automation":{"modifiers":[{"stat":"evasion","value":1}]}}
                """);
        assertEquals("carried armor", CompendiumEntries.add(sheet, "armor", armor));
        JsonNode saved = sheet.path("carriedArmor").get(0);
        assertEquals(4, saved.path("score").asInt());
        assertEquals(7, saved.path("major").asInt());
        assertEquals(armor.path("automation"), saved.path("automation"));
        assertEquals(armor.path("feature"), saved.path("feature"));
        JsonNode weapon = MAPPER.readTree("""
                {"id":"w","name":"Staff","weapon":{"slot":"primary","trait":"Presence","range":"Far",
                "damage":{"dice":"d8","bonus":-1,"type":"magic"},"burden":"Two-Handed"}}
                """);
        assertEquals("carried weapons", CompendiumEntries.add(sheet, "weapons", weapon));
        saved = sheet.path("carriedWeapons").get(0);
        assertEquals("Primary", saved.path("slot").asText());
        assertEquals("magic", saved.path("damageType").asText());
        assertEquals("d8-1", saved.path("damage").asText());
        assertEquals(2, saved.path("hands").asInt());
        assertFalse(saved.has("id"));
    }

    @Test
    void compendiumAddsNeverMergeIntoCustomItems() throws Exception {
        ObjectNode sheet = sheet();
        sheet.withArray("inventory").addObject().put("name", "Potion").put("type", "consumable").put("qty", 5).put("custom", true);
        JsonNode potion = MAPPER.readTree("{\"id\":\"p\",\"name\":\"Potion\",\"text\":\"Heal\"}");
        assertEquals("inventory", CompendiumEntries.add(sheet, "consumables", potion));
        assertEquals(2, sheet.path("inventory").size());
        assertEquals(5, sheet.path("inventory").get(0).path("qty").asInt());
        assertFalse(sheet.path("inventory").get(1).path("custom").asBoolean());
    }

    @Test
    void rejectsQuantityCapsAndUncreatedCharactersWithoutChangingTheirSheet() throws Exception {
        ObjectNode sheet = sheet();
        JsonNode potion = MAPPER.readTree("{\"id\":\"p\",\"name\":\"Potion\",\"text\":\"Heal\"}");
        for (int i = 0; i < 5; i++)
            assertEquals("inventory", CompendiumEntries.add(sheet, "consumables", potion));
        assertEquals(1, sheet.path("inventory").size());
        assertEquals(5, sheet.path("inventory").get(0).path("qty").asInt());
        ObjectNode before = sheet.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> CompendiumEntries.add(sheet, "consumables", potion));
        assertEquals(before, sheet);
        assertThrows(IllegalArgumentException.class, () -> CompendiumEntries.add(MAPPER.createObjectNode(), "items", potion));
        assertThrows(IllegalArgumentException.class, () -> CompendiumEntries.add(sheet, "ancestries", potion));
    }

    @Test
    void domainCardsUseTheVaultOrAccessibleTestingLoadoutWithSpellcastAndSpells() throws Exception {
        ObjectNode sheet = sheet();
        JsonNode card = MAPPER.readTree("""
                {"id":"c","name":"Grimoire","domain":"Grace","level":2,"cardType":"Grimoire","recall":1,
                "spells":[{"name":"Spell","text":"Magic"}]}
                """);
        assertEquals("domain card vault", CompendiumEntries.add(sheet, "domain-cards", card));
        JsonNode saved = sheet.path("vault").get(0);
        assertEquals("Presence", saved.path("spellcast").asText());
        assertEquals(card.path("spells"), saved.path("spells"));
        assertEquals("Grimoire", saved.path("type").asText());
        sheet.put("testingCharacter", true);
        assertEquals("testing loadout", CompendiumEntries.add(sheet, "domain-cards", card));
        assertEquals(1, sheet.path("loadout").size());
        assertThrows(IllegalArgumentException.class, () -> CompendiumEntries.add(sheet, "domain-cards", card));
        ((ObjectNode) card).put("name", "Other").put("domain", "Bone");
        assertThrows(IllegalArgumentException.class, () -> CompendiumEntries.add(sheet, "domain-cards", card));
    }
}
