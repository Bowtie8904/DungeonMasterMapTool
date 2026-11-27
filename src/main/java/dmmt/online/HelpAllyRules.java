package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** One-use assistance; helper dice share a highest-result pool with the acting character's advantage. */
final class HelpAllyRules {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<Integer> DICE = Set.of(4, 6, 8, 10, 12, 20, 100);

    private HelpAllyRules() {
    }

    static int die(JsonNode value) {
        if (!value.isInt() || !DICE.contains(value.asInt()))
            throw new IllegalArgumentException("Choose a Help advantage die: d4, d6, d8, d10, d12, d20 or d100.");
        return value.asInt();
    }

    static ObjectNode effect(String helperId, String helperName, JsonNode rule, JsonNode experiences) {
        int sides = die(rule.path("die"));
        ObjectNode effect = MAPPER.createObjectNode().put("id", "help-" + java.util.UUID.randomUUID())
                .put("name", "Help from " + helperName).put("kind", "buff").put("until", "manual")
                .put("source", "Help an Ally").put("sourceActorId", helperId)
                .put("text", "d" + sides + " advantage on your next action roll, determined by the helper's features. Only the highest advantage die counts.");
        ObjectNode help = effect.putObject("helpAlly").put("die", sides);
        if (rule.path("spellcastOnly").asBoolean()) help.put("spellcastOnly", true);
        String target = help.path("spellcastOnly").asBoolean() ? "spellcast" : "action";
        ArrayNode options = effect.putArray("rollOptions");
        options.addObject().put("id", "help").put("label", "Help an Ally").put("roll", target)
                .put("advantage", "advantage").put("helpAllyDie", sides).put("defaultOn", true).put("hostOwned", true)
                .put("description", "Fixed d" + sides + " helper die; only the highest advantage result is added. Consumed by this action.");
        ((ObjectNode) options.get(0)).putArray("excludeRolls").add("reaction").add("damage");
        int index = 0;
        for (JsonNode experience : experiences) {
            if (!experience.path("name").isTextual() || !experience.path("mod").isInt()) continue;
            ObjectNode option = options.addObject().put("id", "experience-" + index++)
                    .put("label", "Tactician: " + experience.path("name").asText())
                    .put("roll", target).put("value", experience.path("mod").asInt()).put("hostOwned", true)
                    .put("exclusive", "tactician-experience");
            option.putObject("cost").put("hope", 1);
            option.putArray("excludeRolls").add("reaction").add("damage");
        }
        return effect;
    }

    static void validateEffects(JsonNode before, JsonNode next) {
        Map<String, JsonNode> previous = new HashMap<>();
        for (JsonNode effect : before.path("effects"))
            if (effect.has("helpAlly")) previous.put(effect.path("id").asText(), effect);
        Set<String> ids = new HashSet<>();
        for (JsonNode effect : next.path("effects")) {
            String id = effect.path("id").asText();
            if (!effect.has("helpAlly") && !previous.containsKey(id)) continue;
            if (!ids.add(id) || !effect.equals(previous.get(id)))
                throw new IllegalArgumentException("Help buffs and their dice are determined by the host. Remove the buff with X.");
        }
    }

    static void refreshPool(ObjectNode roll) {
        if (!roll.has("helpAlly")) return;
        Set<String> labels = new HashSet<>();
        labels.add("Advantage");
        for (JsonNode label : roll.path("helpAlly").path("labels")) labels.add(label.asText());
        List<ObjectNode> pool = new ArrayList<>();
        ObjectNode winner = null;
        int highest = 0, previous = 0;
        for (JsonNode raw : roll.path("breakdown")) {
            if (!(raw instanceof ObjectNode part) || !labels.contains(part.path("label").asText())
                    || part.path("rolls").size() != 1
                    || !part.path("expression").asText().matches("1d(?:4|6|8|10|12|20|100)")) continue;
            pool.add(part);
            previous = Math.addExact(previous, part.path("total").asInt());
            int value = part.path("rolls").get(0).asInt();
            if (value > highest) { highest = value; winner = part; }
        }
        for (ObjectNode part : pool) {
            part.put("total", part == winner ? highest : 0);
            ArrayNode dropped = part.putArray("dropped");
            if (part != winner) dropped.add(0);
        }
        roll.put("total", Math.addExact(roll.path("total").asInt(), highest - previous));
        ((ObjectNode) roll.path("helpAlly")).put("highest", highest);
    }

    static boolean applyRoll(ObjectNode sheet, JsonNode input, ObjectNode roll, int maxDice, Random random) {
        Map<String, JsonNode> effects = new HashMap<>();
        for (JsonNode effect : sheet.path("effects"))
            if (effect.has("helpAlly")) effects.put("effect:" + effect.path("id").asText(), effect);
        List<JsonNode> selected = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (JsonNode option : input.path("hostOptions")) {
            JsonNode effect = effects.get(option.path("key").asText());
            if (effect == null) {
                if (option.has("helpAllyDie")) throw new IllegalArgumentException("This help buff is no longer active.");
                continue;
            }
            if ("help".equals(option.path("id").asText())) {
                selected.add(option);
                consumed.add(option.path("key").asText());
            }
        }
        for (JsonNode option : input.path("hostOptions")) {
            JsonNode effect = effects.get(option.path("key").asText());
            if (effect == null) continue;
            if (!consumed.contains(option.path("key").asText()))
                throw new IllegalArgumentException("Select the help advantage option to use the helper's Experience.");
        }
        if (selected.isEmpty()) {
            if (input.has("helpAdvantage")) throw new IllegalArgumentException("No Help an Ally option was selected.");
            return false;
        }
        List<String> tags = List.of(input.path("source").asText().split(","));
        if (!"duality".equals(input.path("kind").asText()) || !tags.contains("action") || tags.contains("reaction")
                || tags.contains("damage") || !input.path("helpAdvantage").isBoolean())
            throw new IllegalArgumentException("Help an Ally applies only to an action roll, never a reaction or damage roll.");
        for (JsonNode option : selected)
            if (effects.get(option.path("key").asText()).path("helpAlly").path("spellcastOnly").asBoolean()
                    && !tags.contains("spellcast"))
                throw new IllegalArgumentException("Enchanted Aid can help only a Spellcast action.");
        boolean advantage = input.path("helpAdvantage").asBoolean();
        ArrayNode breakdown = roll.withArray("breakdown");
        ObjectNode own = null;
        int ownResult = 0;
        for (JsonNode part : breakdown) {
            if ("Advantage".equals(part.path("label").asText())) {
                if (own != null || part.path("rolls").size() != 1 || !part.path("expression").asText().matches("1d(?:4|6|8|10|12|20|100)"))
                    throw new IllegalArgumentException("Roll at most one own advantage die.");
                own = (ObjectNode) part;
                ownResult = part.path("total").asInt();
            }
            if (advantage && "Disadvantage".equals(part.path("label").asText()))
                throw new IllegalArgumentException("Advantage and disadvantage must cancel before rolling.");
        }
        if (!advantage && own != null) throw new IllegalArgumentException("The advantage selection changed. Reopen the roll.");
        if (roll.path("rolls").size() + (advantage ? selected.size() : 0) > maxDice)
            throw new IllegalArgumentException("Help dice exceed the host's dice limit.");
        int highest = ownResult;
        ObjectNode winner = own;
        List<ObjectNode> pool = new ArrayList<>();
        if (own != null) pool.add(own);
        for (JsonNode option : selected) {
            JsonNode effect = effects.get(option.path("key").asText());
            int sides = die(effect.path("helpAlly").path("die"));
            ObjectNode part = breakdown.addObject().put("label", effect.path("name").asText())
                    .put("expression", "1d" + sides).put("total", 0);
            ArrayNode values = part.putArray("rolls");
            if (advantage) {
                int result = random.nextInt(sides) + 1;
                values.add(result);
                roll.withArray("rolls").add(result);
                if (result > highest) { highest = result; winner = part; }
            }
            part.putArray("notes").addObject().put("label", "Help an Ally")
                    .put("impact", advantage ? "Only the highest advantage die counts" : "Advantage cancelled or declined; help consumed");
            pool.add(part);
        }
        for (ObjectNode part : pool) {
            part.put("total", part == winner ? highest : 0);
            if (part != winner && !part.path("rolls").isEmpty()) part.putArray("dropped").add(0);
        }
        roll.put("total", Math.addExact(roll.path("total").asInt(), highest - ownResult));
        ObjectNode summary = roll.putObject("helpAlly").put("highest", highest).put("helpers", selected.size());
        ArrayNode sourceKeys = summary.putArray("sourceKeys");
        ArrayNode labels = summary.putArray("labels");
        for (String key : consumed) {
            sourceKeys.add(key);
            String name = effects.get(key).path("name").asText();
            labels.add(name);
            for (JsonNode option : effects.get(key).path("rollOptions"))
                labels.add(OnlineSessionServer.optionContributionLabel(name, option.path("label").asText()));
        }
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode effect : sheet.path("effects"))
            if (!consumed.contains("effect:" + effect.path("id").asText())) kept.add(effect);
        sheet.set("effects", kept);
        return true;
    }
}
