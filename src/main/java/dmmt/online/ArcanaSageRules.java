package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Additive catalog rules shared by sheet actions and persisted roll reactions. */
final class ArcanaSageRules {
    private ArcanaSageRules() {}

    static int available(JsonNode sheet, String resource) {
        if (List.of("hope", "stress", "hp", "armor").contains(resource)) {
            JsonNode track = sheet.path("armor".equals(resource) ? "armorTrack" : resource);
            return Math.max(0, "hope".equals(resource) ? track.path("on").asInt()
                    : track.path("slots").asInt() - track.path("on").asInt());
        }
        return Math.max(0, sheet.path("resources").path(resource).asInt());
    }

    static ObjectNode selectAmount(JsonNode definition, JsonNode request, ObjectNode sheet) {
        ObjectNode resolved = definition.deepCopy();
        JsonNode spec = definition.has("selectAmount") ? definition.path("selectAmount")
                : definition.has("castTokens") ? definition.path("castTokens") : definition.path("resourceRoll");
        if (!spec.isObject()) return resolved;
        String resource = spec.path("resource").asText();
        int minimum = spec.path("min").asInt(1);
        JsonNode input = request.path("amount");
        boolean heal = spec.path("heal").asBoolean();
        int maximum = heal ? Integer.MAX_VALUE : available(sheet, resource);
        if (!input.isInt() || input.asInt() < minimum || input.asInt() > maximum)
            throw new IllegalArgumentException("Choose a whole number from " + minimum + " to " + maximum + " " + resource + ".");
        int amount = input.asInt();
        if (heal) {
            if (!"hp".equals(resource)) throw new IllegalArgumentException("Only marked Hit Points can be healed.");
            resolved.withArray("steps").addObject().put("op", "clear").put("resource", "hp").put("amount", amount);
        } else resolved.withObject("/cost").put(resource, Math.addExact(amount, resolved.path("cost").path(resource).asInt()));
        resolved.put("selectedAmount", amount);
        if (definition.path("benefitsPerAmount").asBoolean()) resolved.put("chatBenefitLimit", Math.max(0, amount - 1));
        if (spec.has("store")) {
            ArrayNode steps = resolved.withArray("steps");
            ObjectNode clear = steps.objectNode().put("op", "clear").put("resource", spec.path("store").asText()).put("amount", "all");
            ObjectNode gain = steps.objectNode().put("op", "gain").put("resource", spec.path("store").asText())
                    .put("amount", Math.addExact(amount, spec.path("base").asInt()));
            steps.insert(0, gain);
            steps.insert(0, clear);
        }
        if (spec.path("addToRoll").asBoolean()) resolved.put("addDice", String.valueOf(amount));
        if (definition.has("resourceRoll")) {
            JsonNode roll = definition.path("resourceRoll");
            resolved.putObject("damage").put("dice", amount + "d" + roll.path("sides").asInt()
                    + (roll.path("bonusPerDie").asInt() == 0 ? "" : "+" + Math.multiplyExact(amount, roll.path("bonusPerDie").asInt())))
                    .put("fixed", true).put("type", roll.path("type").asText("magic"));
        }
        return resolved;
    }

    static void validateDomain(JsonNode sheet, JsonNode trigger, JsonNode roll) {
        if (!trigger.has("spellDomain")) return;
        List<String> tags = List.of(roll.path("source").asText().split(","));
        for (JsonNode card : sheet.path("loadout")) {
            JsonNode actual = "Copycat".equals(card.path("name").asText()) ? card.path("borrowedCard").path("card") : card;
            String key = actual == card ? "card:" + card.path("name").asText() : "card:Copycat/" + actual.path("name").asText();
            if (trigger.path("spellDomain").asText().equals(actual.path("domain").asText()) && tags.contains(key)) return;
        }
        throw new IllegalArgumentException("This critical reward requires a spell from the " + trigger.path("spellDomain").asText() + " domain.");
    }

    static List<String> finishForms(ObjectNode sheet, JsonNode roll) {
        List<String> tags = List.of(roll.path("source").asText().split(","));
        List<String> lines = new ArrayList<>();
        if (!(sheet.path("effects") instanceof ArrayNode effects)) return lines;
        for (int i = effects.size() - 1; i >= 0; i--) {
            JsonNode effect = effects.path(i);
            boolean end = tags.contains("attack") && effect.path("endOnAttack").asBoolean()
                    || tags.contains("action") && effect.path("endOnAction").asBoolean();
            if (tags.contains("action") && effect.has("maintainOnAction")
                    && !tags.contains("option:effect:" + effect.path("id").asText() + ":" + effect.path("maintainOnAction").asText()))
                end = true;
            String resource = effect.path("surgeResource").asText();
            if (!resource.isBlank() && sheet.path("resources").path(resource).asInt() > 6) {
                ObjectNode stress = sheet.withObject("/stress");
                stress.put("on", Math.min(stress.path("slots").asInt(), stress.path("on").asInt() + 1));
                sheet.withObject("/resources").put(resource, 0);
                lines.add("Wild Surge exceeded 6: the form ends and you mark an additional Stress.");
                end = true;
            }
            if (end) {
                effects.remove(i);
                if (resource.isBlank()) lines.add(effect.path("name").asText() + " ended.");
            }
        }
        return lines;
    }
}
