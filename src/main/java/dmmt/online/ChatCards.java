package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves shared descriptions against live inventory, never against client-supplied quantities.
 */
final class ChatCards {
    private ChatCards() {
    }

    static boolean transferable(String category) {
        return List.of("weapon", "armor", "item").contains(category);
    }

    static ObjectNode compendiumItem(JsonNode document, String entryId) {
        if (document == null || !document.isObject() || !document.path("entries").isArray())
            throw new IllegalArgumentException("Invalid compendium data.");
        if (entryId.isBlank())
            throw new IllegalArgumentException("Choose a compendium entry.");
        for (JsonNode entry : document.path("entries")) {
            if (!entryId.equals(entry.path("id").asText()))
                continue;
            ObjectNode item = identity(entry).retain("name");
            List<String> text = new ArrayList<>();
            ArrayNode sections = item.putArray("sections");
            ArrayNode badges = item.putArray("badges");
            String category = document.path("category").asText();
            if ("items".equals(category) || "consumables".equals(category))
                badges.add("consumables".equals(category) ? "consumable" : "gear");
            for (String field : List.of("tier", "rarity", "domain", "level", "cardType", "recall", "parent", "stage",
                    "spellcastTrait", "startingHp", "startingEvasion")) {
                if (entry.hasNonNull(field)) {
                    String label = switch (field) {
                        case "cardType" -> "Type";
                        case "spellcastTrait" -> "Spellcast";
                        case "startingHp" -> "Starting HP";
                        case "startingEvasion" -> "Starting Evasion";
                        case "recall" -> "Recall";
                        default -> Character.toUpperCase(field.charAt(0)) + field.substring(1);
                    };
                    String value = entry.path(field).asText();
                    badges.add(switch (field) {
                        case "rarity", "domain", "cardType", "parent", "stage" -> value;
                        case "tier", "level", "recall" -> label + " " + value;
                        default -> label + ": " + value;
                    });
                }
            }
            if (entry.path("domains").isArray()) {
                List<String> domains = new ArrayList<>();
                entry.path("domains").forEach(domain -> domains.add(domain.asText()));
                badges.add(String.join(" & ", domains));
            }
            JsonNode armor = entry.path("armor");
            if (armor.isObject()) {
                badges.removeAll();
                badges.add("Armor score " + armor.path("score").asText());
                badges.add("Tier " + entry.path("tier").asInt(1));
                badges.add(armor.path("major").asText() + " / " + armor.path("severe").asText());
            }
            JsonNode weapon = entry.path("weapon");
            if (weapon.isObject()) {
                JsonNode damage = weapon.path("damage");
                int bonus = damage.path("bonus").asInt();
                badges.removeAll();
                badges.add("secondary".equals(weapon.path("slot").asText()) ? "Secondary" : "Primary");
                badges.add("magic".equals(damage.path("type").asText()) ? "magic" : "phys");
                badges.add(weapon.path("trait").asText());
                badges.add(weapon.path("range").asText());
                badges.add(damage.path("dice").asText() + (bonus == 0 ? "" : bonus > 0 ? "+" + bonus : "" + bonus));
                badges.add(weapon.path("burden").asText().toLowerCase(java.util.Locale.ROOT).contains("two") ? "2H" : "1H");
                badges.add("Tier " + entry.path("tier").asInt(1));
            }
            if (!entry.path("text").asText().isBlank()) {
                text.add(entry.path("text").asText());
                if (!entry.path("spells").isArray() || entry.path("spells").isEmpty())
                    sections.addObject().put("text", entry.path("text").asText());
            }
            List<JsonNode> features = new ArrayList<>();
            if (entry.path("feature").isObject())
                features.add(entry.path("feature"));
            entry.path("features").forEach(features::add);
            for (JsonNode feature : features) {
                text.add("**" + feature.path("name").asText() + "**\n" + feature.path("text").asText());
                sections.addObject().put("name", feature.path("name").asText()).put("text", feature.path("text").asText());
            }
            for (JsonNode spell : entry.path("spells")) {
                if (entry.path("text").asText().isBlank())
                    text.add("**" + spell.path("name").asText() + "**\n" + spell.path("text").asText());
                sections.addObject().put("name", spell.path("name").asText()).put("text", spell.path("text").asText());
            }
            item.put("source", document.path("label").asText("Compendium"));
            item.put("text", String.join("\n\n", text));
            return item;
        }
        throw new IllegalArgumentException("Unknown compendium entry.");
    }

    static ObjectNode identity(JsonNode item) {
        if (!(item instanceof ObjectNode object) || object.path("name").asText("").isBlank()) {
            throw new IllegalArgumentException("Invalid shared entry");
        }
        ObjectNode copy = object.deepCopy();
        copy.remove("qty");
        return copy;
    }

    static List<JsonNode> entries(JsonNode sheet, String category) {
        List<JsonNode> entries = new ArrayList<>();
        List<String> fields = switch (category) {
            case "weapon" -> List.of("carriedWeapons", "activeWeapons");
            case "armor" -> List.of("carriedArmor");
            case "item" -> List.of("inventory");
            case "feature" -> List.of("features", "heritage");
            case "domain" -> List.of("loadout", "vault");
            default -> throw new IllegalArgumentException("Unknown shared entry category");
        };
        for (String field : fields)
            sheet.path(field).forEach(entries::add);
        if ("armor".equals(category) && sheet.path("activeArmor").isObject())
            entries.add(sheet.get("activeArmor"));
        return entries;
    }

    static int available(JsonNode sheet, String category, JsonNode item) {
        ObjectNode key = identity(item);
        if (!transferable(category))
            return 0;
        String field = switch (category) {
            case "weapon" -> "carriedWeapons";
            case "armor" -> "carriedArmor";
            default -> "inventory";
        };
        List<JsonNode> stock = new ArrayList<>();
        sheet.path(field).forEach(stock::add);
        return stock.stream().filter(e -> identity(e).equals(key))
                                       .mapToInt(e -> "item".equals(category) ? Math.max(0, e.path("qty").asInt()) : 1).sum();
    }

    static int remaining(JsonNode sheet, JsonNode card) {
        int offered = card.path("offerQuantity").asInt(0);
        if (offered <= 0)
            return 0;
        int claimed = sheet.path("transferClaims").path(card.path("cardId").asText()).asInt(0);
        return Math.min(Math.max(0, offered - claimed),
                        available(sheet, card.path("category").asText(), card.path("item")));
    }

    static ObjectNode resolve(JsonNode sheet, String category, JsonNode item) {
        ObjectNode key = identity(item);
        return entries(sheet, category).stream().filter(e -> identity(e).equals(key))
                                       .findFirst().map(ChatCards::identity)
                                       .orElseThrow(() -> new IllegalArgumentException("Entry is no longer on the source sheet. Save it before sharing."));
    }

    static void transfer(ObjectNode source, ObjectNode target, String category, JsonNode item) {
        transfer(source, target, category, item, 1);
    }

    static void transfer(ObjectNode source, ObjectNode target, String category, JsonNode item, int quantity) {
        if (!transferable(category) || quantity <= 0 || available(source, category, item) < quantity) {
            throw new IllegalArgumentException("There is no more to grab.");
        }
        ObjectNode key = identity(item);
        String destination = switch (category) {
            case "weapon" -> "carriedWeapons";
            case "armor" -> "carriedArmor";
            default -> "inventory";
        };
        if (!target.path(destination).isMissingNode() && !target.path(destination).isArray()) {
            throw new IllegalArgumentException("Receiving inventory is invalid.");
        }
        if ("item".equals(category)) {
            int cap = "consumable".equals(key.path("type").asText()) ? 5 : 99;
            int held = entries(target, category).stream()
                    .filter(e -> e.path("name").equals(key.path("name")) && e.path("type").equals(key.path("type")))
                    .mapToInt(e -> Math.max(0, e.path("qty").asInt())).sum();
            if (quantity > cap - held) {
                throw new IllegalArgumentException("Not enough room: receiving character has " + held
                        + " of " + cap + " allowed; the transfer needs room for " + quantity + ".");
            }
        }
        List<String> fields = switch (category) {
            case "weapon" -> List.of("carriedWeapons");
            case "armor" -> List.of("carriedArmor");
            default -> List.of("inventory");
        };
        int left = quantity;
        for (String field : fields) {
            if (!(source.path(field) instanceof ArrayNode array))
                continue;
            for (int i = 0; i < array.size() && left > 0; i++) {
                JsonNode entry = array.get(i);
                if (!identity(entry).equals(key))
                    continue;
                if ("item".equals(category)) {
                    if (entry.path("qty").asInt() <= 0)
                        continue;
                    int take = Math.min(left, entry.path("qty").asInt());
                    int remaining = entry.path("qty").asInt() - take;
                    left -= take;
                    if (remaining <= 0)
                        array.remove(i--);
                    else
                        ((ObjectNode)entry).put("qty", remaining);
                }
                else {
                    array.remove(i--);
                    left--;
                }
            }
            if (left == 0)
                break;
        }
        ArrayNode array = target.withArray(destination);
        if ("item".equals(category)) {
            for (JsonNode entry : array) {
                if (identity(entry).equals(key)) {
                    ((ObjectNode)entry).put("qty", Math.max(0, entry.path("qty").asInt()) + quantity);
                    return;
                }
            }
            key.put("qty", quantity);
        }
        for (int i = 0; i < ("item".equals(category) ? 1 : quantity); i++)
            array.add(key.deepCopy());
    }
}
