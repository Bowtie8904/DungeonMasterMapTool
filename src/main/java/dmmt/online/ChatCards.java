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
        return entries(sheet, category).stream().filter(e -> identity(e).equals(key))
                                       .mapToInt(e -> "item".equals(category) ? Math.max(0, e.path("qty").asInt()) : 1).sum();
    }

    static ObjectNode resolve(JsonNode sheet, String category, JsonNode item) {
        ObjectNode key = identity(item);
        return entries(sheet, category).stream().filter(e -> identity(e).equals(key))
                                       .findFirst().map(ChatCards::identity)
                                       .orElseThrow(() -> new IllegalArgumentException("Entry is no longer on the source sheet. Save it before sharing."));
    }

    static void transfer(ObjectNode source, ObjectNode target, String category, JsonNode item) {
        if (!transferable(category) || available(source, category, item) <= 0) {
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
            if (held >= cap) {
                throw new IllegalArgumentException("Receiving character already has the maximum quantity (" + cap + ").");
            }
        }
        List<String> fields = switch (category) {
            case "weapon" -> List.of("carriedWeapons", "activeWeapons");
            case "armor" -> List.of("carriedArmor");
            default -> List.of("inventory");
        };
        boolean removed = false;
        for (String field : fields) {
            if (!(source.path(field) instanceof ArrayNode array))
                continue;
            for (int i = 0; i < array.size(); i++) {
                JsonNode entry = array.get(i);
                if (!identity(entry).equals(key))
                    continue;
                if ("item".equals(category)) {
                    if (entry.path("qty").asInt() <= 0)
                        continue;
                    ((ObjectNode)entry).put("qty", entry.path("qty").asInt() - 1);
                }
                else
                    array.remove(i);
                removed = true;
                break;
            }
            if (removed)
                break;
        }
        if (!removed && "armor".equals(category))
            source.remove("activeArmor");
        ArrayNode array = target.withArray(destination);
        if ("item".equals(category)) {
            for (JsonNode entry : array) {
                if (identity(entry).equals(key)) {
                    ((ObjectNode)entry).put("qty", Math.max(0, entry.path("qty").asInt()) + 1);
                    return;
                }
            }
            key.put("qty", 1);
        }
        array.add(key);
    }
}
