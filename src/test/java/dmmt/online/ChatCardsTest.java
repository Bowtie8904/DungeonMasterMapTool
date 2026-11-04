package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatCardsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode sheet(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void compendiumDescriptionsPreserveStatsAndFeaturesWithoutChangingSource() throws Exception {
        ObjectNode document = sheet("""
                {"label":"Classes","entries":[{"id":"bard","name":"Bard","domains":["Grace","Codex"],
                "features":[{"name":"Rally","text":"Gain **Hope**."}],"startingHp":5},
                {"id":"armor","name":"Leather","tier":1,"armor":{"score":3,"major":5,"severe":11},
                "feature":{"name":"Flexible","text":"Move freely."}},
                {"id":"weapon","name":"Blade","weapon":{"slot":"primary","trait":"Agility","range":"Melee",
                "burden":"Two-handed","damage":{"dice":"d8","bonus":-1,"type":"physical"}}}]}
                """);
        ObjectNode original = document.deepCopy();
        ObjectNode bard = ChatCards.compendiumItem(document, "bard");
        assertEquals("Classes", bard.path("source").asText());
        assertTrue(bard.path("badges").toString().contains("Grace & Codex"));
        assertFalse(bard.path("text").asText().contains("Domains:"));
        assertTrue(bard.path("text").asText().contains("**Rally**\nGain **Hope**."));
        assertEquals("Rally", bard.path("sections").get(0).path("name").asText());
        assertEquals("Gain **Hope**.", bard.path("sections").get(0).path("text").asText());
        ObjectNode armor = ChatCards.compendiumItem(document, "armor");
        assertEquals(MAPPER.readTree("[\"Armor score 3\",\"Tier 1\",\"5 / 11\"]"), armor.path("badges"));
        assertEquals("**Flexible**\nMove freely.", armor.path("text").asText());
        ObjectNode weapon = ChatCards.compendiumItem(document, "weapon");
        assertEquals(MAPPER.readTree("[\"Primary\",\"phys\",\"Agility\",\"Melee\",\"d8-1\",\"2H\",\"Tier 1\"]"),
                weapon.path("badges"));
        assertEquals("", weapon.path("text").asText());
        assertEquals(original, document);
        assertThrows(IllegalArgumentException.class, () -> ChatCards.compendiumItem(document, ""));
        assertThrows(IllegalArgumentException.class, () -> ChatCards.compendiumItem(document, "missing"));
    }

    @Test
    void everyCompendiumCategoryPreservesNamedFeatureBlocksAndDescriptions() throws Exception {
        for (String category : List.of("weapons", "armor", "domain-cards", "items", "consumables",
                "classes", "ancestries", "communities", "transformations")) {
            ObjectNode document = sheet("""
                    {"label":"Category","entries":[{"id":"entry","name":"Entry","text":"**Description**",
                    "feature":{"name":"Equipment","text":"*First*"},
                    "features":[{"name":"Feature one","text":"- **Second**"},{"name":"Feature two","text":"Third"}]}]}
                    """);
            document.put("category", category);
            ObjectNode item = ChatCards.compendiumItem(document, "entry");
            assertEquals(4, item.path("sections").size(), category);
            assertEquals("**Description**", item.path("sections").get(0).path("text").asText(), category);
            assertEquals("Equipment", item.path("sections").get(1).path("name").asText(), category);
            assertEquals("Feature two", item.path("sections").get(3).path("name").asText(), category);
        }
        ObjectNode grimoire = sheet("""
                {"label":"Domain Cards","entries":[{"id":"book","name":"Book","text":"Combined spell text",
                "spells":[{"name":"Spell one","text":"**Cast**"},{"name":"Spell two","text":"*Again*"}]}]}
                """);
        ObjectNode book = ChatCards.compendiumItem(grimoire, "book");
        assertEquals(2, book.path("sections").size());
        assertEquals("Spell one", book.path("sections").get(0).path("name").asText());
        assertTrue(book.path("text").asText().contains("Combined spell text"));
    }

    @Test
    void compendiumDomainAndItemBadgesMatchInventoryAndRetainRarity() throws Exception {
        ObjectNode cards = sheet("""
                {"category":"domain-cards","label":"Domain Cards","entries":[
                {"id":"card","name":"Spell","domain":"Codex","level":2,"cardType":"Spell","recall":1,"text":"Cast it."}]}
                """);
        ObjectNode card = ChatCards.compendiumItem(cards, "card");
        assertEquals(MAPPER.readTree("[\"Codex\",\"Level 2\",\"Spell\",\"Recall 1\"]"), card.path("badges"));
        assertEquals("Cast it.", card.path("text").asText());
        ObjectNode items = sheet("""
                {"category":"consumables","label":"Consumables","entries":[
                {"id":"potion","name":"Potion","rarity":"Rare","text":"Drink it."}]}
                """);
        ObjectNode potion = ChatCards.compendiumItem(items, "potion");
        assertEquals(MAPPER.readTree("[\"consumable\",\"Rare\"]"), potion.path("badges"));
        assertEquals("Drink it.", potion.path("text").asText());
    }

    @Test
    void transfersOneConsumableAtATimeWithoutChangingDescriptionOrAutomation() throws Exception {
        ObjectNode source = sheet("""
                {"inventory":[{"name":"Potion","type":"consumable","note":"Clear **HP**","qty":2,
                "automation":{"actions":[{"id":"drink"}]}}]}
                """);
        ObjectNode target = sheet("{\"inventory\":[]}");
        var item = source.path("inventory").get(0).deepCopy();
        ChatCards.transfer(source, target, "item", item);
        assertEquals(1, source.path("inventory").get(0).path("qty").asInt());
        assertEquals(1, target.path("inventory").get(0).path("qty").asInt());
        assertEquals(item.path("automation"), target.path("inventory").get(0).path("automation"));
        ChatCards.transfer(source, target, "item", item);
        assertEquals(0, ChatCards.available(source, "item", item));
        assertEquals(2, target.path("inventory").get(0).path("qty").asInt());
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, target, "item", item));
        assertEquals(2, item.path("qty").asInt(), "Posted snapshot is immutable");
    }

    @Test
    void capsConsumablesBeforeRemovingAnythingAndDoesNotMergeDifferentItems() throws Exception {
        ObjectNode source = sheet("{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":2}]}");
        ObjectNode full = sheet("{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":5}]}");
        var item = source.path("inventory").get(0);
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, full, "item", item));
        assertEquals(2, ChatCards.available(source, "item", item));
        ObjectNode target = sheet("{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"note\":\"Different\",\"qty\":4}]}");
        ChatCards.transfer(source, target, "item", item);
        assertEquals(2, target.path("inventory").size());
        assertEquals(4, target.path("inventory").get(0).path("qty").asInt());
    }

    @Test
    void onlyUnequippedStockCanTransferAndItArrivesUnequipped() throws Exception {
        ObjectNode source = sheet("""
                {"activeWeapons":[{"name":"Dagger","damage":"d8"}],"carriedWeapons":[{"name":"Dagger","damage":"d8"}],
                 "activeArmor":{"name":"Leather","score":3},"carriedArmor":[]}
                """);
        ObjectNode target = sheet("{}");
        var weapon = source.path("activeWeapons").get(0).deepCopy();
        var armor = source.get("activeArmor").deepCopy();
        assertEquals(1, ChatCards.available(source, "weapon", weapon));
        ChatCards.transfer(source, target, "weapon", weapon);
        assertEquals(1, source.path("activeWeapons").size());
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, target, "weapon", weapon));
        assertEquals(1, source.path("activeWeapons").size());
        assertEquals(1, target.path("carriedWeapons").size());
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, target, "armor", armor));
        assertEquals(armor, source.get("activeArmor"));
        assertFalse(target.has("activeArmor"));
    }

    @Test
    void bulkTransfersAreAllOrNothingAndOffersKeepTheirOwnBudget() throws Exception {
        ObjectNode source = sheet("{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":4}]}");
        ObjectNode target = sheet("{\"inventory\":[{\"name\":\"Potion\",\"type\":\"consumable\",\"qty\":3}]}");
        var item = source.path("inventory").get(0).deepCopy();
        ObjectNode before = source.deepCopy();
        ObjectNode targetBefore = target.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, target, "item", item, 3));
        assertEquals(before, source);
        assertEquals(targetBefore, target);
        ChatCards.transfer(source, target, "item", item, 2);
        assertEquals(2, ChatCards.available(source, "item", item));
        assertEquals(5, target.path("inventory").get(0).path("qty").asInt());
        ObjectNode card = sheet("{\"cardId\":\"offer\",\"category\":\"item\",\"offerQuantity\":2}");
        card.set("item", item);
        assertEquals(2, ChatCards.remaining(source, card));
        source.putObject("transferClaims").put("offer", 2);
        assertEquals(0, ChatCards.remaining(source, card));
        card.remove("offerQuantity");
        assertEquals(0, ChatCards.remaining(before, card), "Legacy links never grant transfer permission");
    }

    @Test
    void descriptionsMustMatchTheSavedSheetAndNonInventoryCardsCannotTransfer() throws Exception {
        ObjectNode source = sheet("""
                {"features":[{"name":"Feature","source":"Class","text":"Description"}],
                 "loadout":[{"name":"Spell","text":"Spell text","spellcast":"Instinct"}]}
                """);
        var feature = source.path("features").get(0);
        assertEquals(feature, ChatCards.resolve(source, "feature", feature));
        assertEquals(source.path("loadout").get(0), ChatCards.resolve(source, "domain", source.path("loadout").get(0)));
        assertThrows(IllegalArgumentException.class, () -> ChatCards.resolve(source, "feature", sheet("{\"name\":\"Feature\",\"text\":\"Forged\"}")));
        assertThrows(IllegalArgumentException.class, () -> ChatCards.transfer(source, sheet("{}"), "feature", feature));
    }
}
