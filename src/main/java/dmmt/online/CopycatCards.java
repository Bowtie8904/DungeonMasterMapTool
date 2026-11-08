package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copycat's embedded card is host-owned and its automation is always rebuilt from the catalog. */
final class CopycatCards {
    private CopycatCards() {}

    static int cost(int level) {
        if (level < 1 || level > 8) throw new IllegalArgumentException("Copycat can only copy printed levels 1 through 8.");
        return (level + 1) / 2;
    }

    static JsonNode activeCard(JsonNode sheet, String name) {
        for (JsonNode card : sheet.path("loadout"))
            if (name.equals(card.path("name").asText())) return card;
        return null;
    }

    static boolean rested(JsonNode current, JsonNode previous) {
        return current.path("longRestRevision").asLong() > previous.path("longRestRevision").asLong()
                || current.path("shortRestsInRow").asInt() > previous.path("shortRestsInRow").asInt();
    }

    static ObjectNode prepare(JsonNode catalog, JsonNode borrower) {
        ObjectNode card = catalog.deepCopy();
        card.remove(List.of("borrowedCard", "catalog"));
        String type = catalog.path("cardType").asText(catalog.path("type").asText("ability"));
        card.put("type", type.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + type.substring(1).toLowerCase(java.util.Locale.ROOT));
        for (JsonNode trait : borrower.path("traits"))
            if (trait.path("spellcast").asBoolean()) { card.put("spellcast", trait.path("name").asText()); break; }
        String prefix = "Copycat/" + card.path("name").asText();
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("card:" + card.path("name").asText(), "card:" + prefix);
        for (JsonNode spell : card.path("spells")) {
            replacements.put("card:" + spell.path("name").asText(), "card:" + prefix + "/" + spell.path("name").asText());
            replacements.put("card:" + card.path("name").asText() + "/" + spell.path("name").asText(), "card:" + prefix + "/" + spell.path("name").asText());
        }
        discover(card.path("automation"), prefix, replacements);
        for (JsonNode spell : card.path("spells")) discover(spell.path("automation"), prefix, replacements);
        if (card.has("automation")) card.set("automation", rewrite(card.get("automation"), replacements));
        for (JsonNode spell : card.path("spells"))
            if (spell instanceof ObjectNode object && object.has("automation"))
                object.set("automation", rewrite(object.get("automation"), replacements));
        return card;
    }

    private static void discover(JsonNode value, String prefix, Map<String, String> replacements) {
        if (value.isObject()) {
            for (JsonNode resource : value.path("resources"))
                replacements.put(resource.path("id").asText(), prefix + "/" + resource.path("id").asText());
            JsonNode effect = value.path("effect");
            if (effect.has("name")) replacements.put(effect.path("name").asText(), prefix + ": " + effect.path("name").asText());
            if (effect.has("group")) replacements.put(effect.path("group").asText(), prefix + "/" + effect.path("group").asText());
            if (value.path("dicePool").has("id")) {
                String id = value.path("dicePool").path("id").asText();
                replacements.put(id, prefix + "/" + id);
            }
        }
        for (JsonNode child : value) discover(child, prefix, replacements);
    }

    private static JsonNode rewrite(JsonNode value, Map<String, String> replacements) {
        if (value.isTextual()) {
            String text = value.asText();
            for (var replacement : replacements.entrySet())
                if (replacement.getKey().startsWith("card:"))
                    for (String kind : List.of("featureAction", "option"))
                        if (text.startsWith(kind + ":" + replacement.getKey() + ":"))
                            return TextNode.valueOf(text.replace(kind + ":" + replacement.getKey() + ":", kind + ":" + replacement.getValue() + ":"));
            return TextNode.valueOf(replacements.getOrDefault(text, text));
        }
        if (value instanceof ObjectNode object) {
            ObjectNode out = object.objectNode();
            object.fields().forEachRemaining(field -> out.set(replacements.getOrDefault(field.getKey(), field.getKey()), rewrite(field.getValue(), replacements)));
            return out;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode out = array.arrayNode();
            array.forEach(child -> out.add(rewrite(child, replacements)));
            return out;
        }
        return value;
    }

    static boolean clear(ObjectNode sheet, ObjectNode copycat) {
        JsonNode borrowed = copycat.path("borrowedCard");
        if (!borrowed.isObject()) return false;
        String prefix = "Copycat/" + borrowed.path("card").path("name").asText();
        String key = "card:" + prefix;
        if (sheet.path("effects") instanceof ArrayNode effects) {
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (effect.path("name").asText().startsWith(prefix + ": ")
                        || effect.path("source").asText().startsWith(prefix)
                        || effect.path("sourceKey").asText().startsWith(key)) effects.remove(i);
            }
        }
        if (sheet.path("resources") instanceof ObjectNode resources) {
            List<String> keys = new java.util.ArrayList<>();
            resources.fieldNames().forEachRemaining(id -> { if (id.startsWith(prefix + "/")) keys.add(id); });
            resources.remove(keys);
        }
        if (sheet.path("usage") instanceof ObjectNode usage) {
            List<String> keys = new java.util.ArrayList<>();
            usage.fieldNames().forEachRemaining(id -> { if (id.equals(key) || id.startsWith(key + "/") || id.startsWith(key + ":")) keys.add(id); });
            usage.remove(keys);
        }
        copycat.remove("borrowedCard");
        sheet.put("compendiumRevision", sheet.path("compendiumRevision").asLong() + 1);
        return true;
    }

    static void preserveSave(ObjectNode saved, JsonNode previous) {
        for (String location : List.of("loadout", "vault"))
            for (JsonNode entry : saved.path(location)) {
                if (!(entry instanceof ObjectNode card)) continue;
                JsonNode editedBorrowed = card.path("borrowedCard").deepCopy();
                card.remove("borrowedCard");
                if (!"Copycat".equals(card.path("name").asText())) continue;
                JsonNode original = null;
                for (String priorLocation : List.of("loadout", "vault"))
                    for (JsonNode prior : previous.path(priorLocation))
                        if ("Copycat".equals(prior.path("name").asText())) original = prior;
                if (original != null && original.path("borrowedCard").isObject()) {
                    card.set("borrowedCard", original.path("borrowedCard").deepCopy());
                    JsonNode retained = card.path("borrowedCard").path("card");
                    if (retained instanceof ObjectNode copied && copied.path("automation").path("banishDifficulty").asBoolean()
                            && copied.path("name").equals(editedBorrowed.path("card").path("name"))) {
                        JsonNode difficulty = editedBorrowed.path("card").get("banishDifficulty");
                        if (difficulty == null) copied.remove("banishDifficulty");
                        else if (difficulty.isInt()) copied.set("banishDifficulty", difficulty);
                        else throw new IllegalArgumentException("Copied Banish difficulty must be a whole number.");
                    }
                    if (rested(saved, previous)) clear(saved, card);
                }
            }
    }

    static void synchronize(Map<String, ObjectNode> party, Map<String, ObjectNode> previousParty) {
        for (var entry : party.entrySet()) {
            ObjectNode sheet = entry.getValue();
            for (String location : List.of("loadout", "vault"))
                for (JsonNode node : sheet.path(location)) {
                    if (!(node instanceof ObjectNode card) || !"Copycat".equals(card.path("name").asText()) || !card.path("borrowedCard").isObject()) continue;
                    JsonNode source = party.get(card.path("borrowedCard").path("sourceActorId").asText());
                    if (rested(sheet, previousParty.getOrDefault(entry.getKey(), sheet))
                            || source == null || activeCard(source, card.path("borrowedCard").path("card").path("name").asText()) == null)
                        clear(sheet, card);
                }
        }
        for (ObjectNode sheet : party.values()) {
            if (!(sheet.path("effects") instanceof ArrayNode effects)) continue;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                String key = effect.path("sourceKey").asText();
                if (!key.startsWith("card:Copycat/")) continue;
                JsonNode caster = party.get(effect.path("sourceActorId").asText());
                JsonNode copycat = caster == null ? null : activeCard(caster, "Copycat");
                String activeKey = copycat == null || !copycat.path("borrowedCard").isObject() ? ""
                        : "card:Copycat/" + copycat.path("borrowedCard").path("card").path("name").asText();
                if (activeKey.isEmpty() || !(key.equals(activeKey) || key.startsWith(activeKey + "/"))) effects.remove(i);
            }
        }
    }

    static List<JsonNode> borrowedAutomation(JsonNode sheet) {
        List<JsonNode> out = new java.util.ArrayList<>();
        JsonNode copycat = activeCard(sheet, "Copycat");
        if (copycat != null && copycat.path("borrowedCard").isObject())
            out.add(copycat.path("borrowedCard").path("card").path("automation"));
        return out;
    }
}
