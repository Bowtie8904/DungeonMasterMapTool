package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/** Host-side equivalent of the browser's compendium-to-sheet conversions. */
final class CompendiumEntries {
    private CompendiumEntries() { }

    static JsonNode entry(JsonNode document, String id) {
        if (id.isBlank())
            throw new IllegalArgumentException("Choose a compendium entry.");
        for (JsonNode entry : document.path("entries")) {
            if (id.equals(entry.path("id").asText()))
                return entry;
        }
        throw new IllegalArgumentException("Unknown compendium entry.");
    }

    static String add(ObjectNode sheet, String category, JsonNode entry) {
        if (sheet.path("className").asText().isBlank() || !sheet.path("inventory").isArray()
                || !sheet.path("traits").isArray() || sheet.path("mancer").path("done").isBoolean()
                && !sheet.path("mancer").path("done").asBoolean())
            throw new IllegalArgumentException("Create this character's sheet before adding compendium entries.");
        ObjectNode item = sheet.objectNode().put("name", entry.path("name").asText());
        copy(entry, item, "automation");
        switch (category) {
            case "armor" -> {
                if (!entry.path("armor").isObject())
                    throw new IllegalArgumentException("Invalid compendium armor.");
                copy(entry.path("armor"), item, "score", "major", "severe");
                copy(entry, item, "feature");
                item.put("tier", entry.path("tier").asInt(1));
                sheet.withArray("carriedArmor").add(item);
                return "carried armor";
            }
            case "weapons" -> {
                JsonNode weapon = entry.path("weapon");
                if (!weapon.isObject())
                    throw new IllegalArgumentException("Invalid compendium weapon.");
                item.put("slot", "secondary".equals(weapon.path("slot").asText()) ? "Secondary" : "Primary");
                item.put("damageType", "magic".equals(weapon.path("damage").path("type").asText()) ? "magic" : "phys");
                copy(weapon, item, "trait", "range");
                int bonus = weapon.path("damage").path("bonus").asInt();
                item.put("damage", weapon.path("damage").path("dice").asText()
                        + (bonus == 0 ? "" : bonus > 0 ? "+" + bonus : String.valueOf(bonus)));
                item.put("hands", weapon.path("burden").asText().toLowerCase(Locale.ROOT).contains("two") ? 2 : 1);
                item.put("tier", entry.path("tier").asInt(1));
                copy(entry, item, "feature");
                sheet.withArray("carriedWeapons").add(item);
                return "carried weapons";
            }
            case "items", "consumables" -> {
                boolean consumable = "consumables".equals(category);
                String type = consumable ? "consumable" : "gear";
                int cap = consumable ? 5 : 99;
                int held = 0;
                ObjectNode existing = null;
                for (JsonNode row : sheet.withArray("inventory")) {
                    if (entry.path("name").equals(row.path("name")) && type.equals(row.path("type").asText())) {
                        held += Math.max(0, row.path("qty").asInt());
                        if (row instanceof ObjectNode object) existing = object;
                    }
                }
                if (held >= cap)
                    throw new IllegalArgumentException("This character already holds the maximum of " + cap + " " + entry.path("name").asText() + ".");
                if (existing != null) existing.put("qty", existing.path("qty").asInt() + 1);
                else {
                    if (entry.has("text")) item.set("note", entry.get("text").deepCopy());
                    item.put("type", type).put("qty", 1).put("capped", consumable);
                    sheet.withArray("inventory").add(item);
                }
                return "inventory";
            }
            case "domain-cards" -> {
                if (entry.path("domain").asText().isBlank())
                    throw new IllegalArgumentException("Invalid compendium domain card.");
                boolean testing = sheet.path("testingCharacter").asBoolean();
                if (testing) {
                    boolean accessible = false;
                    for (JsonNode domain : sheet.path("domains"))
                        accessible |= domain.asText().equals(entry.path("domain").asText());
                    if (!accessible) throw new IllegalArgumentException("This domain is not accessible to the testing character.");
                }
                String field = testing ? "loadout" : "vault";
                if (testing) {
                    for (JsonNode card : sheet.path(field))
                        if (card.path("name").equals(entry.path("name")))
                            throw new IllegalArgumentException("This card is already in the testing loadout.");
                }
                item.put("level", entry.path("level").asInt(1));
                item.put("domain", entry.path("domain").asText());
                item.put("type", entry.path("cardType").asText("Ability"));
                item.put("text", entry.path("text").asText());
                item.put("recall", entry.path("recall").asInt());
                copy(entry, item, "spells");
                if (List.of("Spell", "Grimoire").contains(item.path("type").asText())) {
                    for (JsonNode trait : sheet.path("traits")) {
                        if (trait.path("spellcast").asBoolean()) {
                            item.put("spellcast", trait.path("name").asText());
                            break;
                        }
                    }
                }
                sheet.withArray(field).add(item);
                return testing ? "testing loadout" : "domain card vault";
            }
            default -> throw new IllegalArgumentException("This category cannot be added to a character.");
        }
    }

    private static void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields)
            if (source.hasNonNull(field)) target.set(field, source.get(field).deepCopy());
    }
}
