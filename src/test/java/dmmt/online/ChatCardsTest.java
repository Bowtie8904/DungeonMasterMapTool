package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatCardsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode sheet(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
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
    void identicalEquipmentAcrossActiveAndCarriedSlotsSharesStockAndArrivesUnequipped() throws Exception {
        ObjectNode source = sheet("""
                {"activeWeapons":[{"name":"Dagger","damage":"d8"}],"carriedWeapons":[{"name":"Dagger","damage":"d8"}],
                 "activeArmor":{"name":"Leather","score":3},"carriedArmor":[]}
                """);
        ObjectNode target = sheet("{}");
        var weapon = source.path("activeWeapons").get(0).deepCopy();
        var armor = source.get("activeArmor").deepCopy();
        assertEquals(2, ChatCards.available(source, "weapon", weapon));
        ChatCards.transfer(source, target, "weapon", weapon);
        assertEquals(1, source.path("activeWeapons").size());
        ChatCards.transfer(source, target, "weapon", weapon);
        assertEquals(0, source.path("activeWeapons").size());
        assertEquals(2, target.path("carriedWeapons").size());
        ChatCards.transfer(source, target, "armor", armor);
        assertFalse(source.has("activeArmor"));
        assertEquals(armor, target.path("carriedArmor").get(0));
        assertFalse(target.has("activeArmor"));
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
