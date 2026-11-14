package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.UUID;

/** Builds the restricted effect definitions authored by the DM, never by a player. */
public final class DmCustomEffects {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STATS = Set.of("trait.Agility", "trait.Strength", "trait.Finesse",
            "trait.Instinct", "trait.Presence", "trait.Knowledge", "traits", "proficiency", "evasion",
            "armorScore", "majorThreshold", "severeThreshold", "thresholds", "hpSlots", "stressSlots");
    private static final Set<String> ROLLS = Set.of("action", "reaction", "attack", "spellcast", "damage",
            "magicdamage", "spelldamage", "trait:Agility", "trait:Strength", "trait:Finesse",
            "trait:Instinct", "trait:Presence", "trait:Knowledge");
    private static final Set<String> DURATIONS = Set.of("manual", "scene", "rest", "shortRest", "longRest", "session");

    private DmCustomEffects() { }

    /** Mirrors the browser's capacity reconciliation so host-side apply, removal and expiry keep slots exact. */
    public static void reconcileCapacity(ObjectNode sheet) {
        for (String kind : new String[]{"hp", "stress"}) {
            int desired = 0;
            for (JsonNode effect : sheet.path("effects"))
                for (JsonNode modifier : effect.path("modifiers"))
                    if ((kind + "Slots").equals(modifier.path("stat").asText())) desired += modifier.path("value").asInt();
            int applied = sheet.path("capacityApplied").path(kind).asInt(0);
            int delta = desired - applied;
            if (delta == 0 || !(sheet.get(kind) instanceof ObjectNode track)) continue;
            int slots = track.path("slots").asInt();
            int total = track.path("total").asInt(slots);
            int floor = kind.equals("hp") ? Math.max(1, Math.min(slots, sheet.path("permanentHp").asInt(0))) : 1;
            int target = Math.max(Math.min(floor, slots), Math.min(total, slots + delta));
            ObjectNode capacity = sheet.withObject("/capacityApplied");
            capacity.put("hp", capacity.path("hp").asInt(0)).put("stress", capacity.path("stress").asInt(0));
            capacity.put(kind, applied + target - slots);
            track.put("slots", target).put("on", Math.min(track.path("on").asInt(), target));
            if (capacity.path("hp").asInt() == 0 && capacity.path("stress").asInt() == 0) sheet.remove("capacityApplied");
        }
    }

    public static ObjectNode create(JsonNode definition) {
        String name = definition.path("name").asText("").trim();
        String kind = definition.path("kind").asText();
        String until = definition.path("until").asText("manual");
        String text = definition.path("text").asText("").trim();
        if (name.isEmpty() || name.length() > 100 || text.length() > 2000
                || !Set.of("buff", "debuff").contains(kind) || !DURATIONS.contains(until))
            throw new IllegalArgumentException("Enter a valid name, effect type and duration.");
        JsonNode parts = definition.path("parts");
        if (!parts.isArray() || parts.isEmpty() || parts.size() > 40)
            throw new IllegalArgumentException("Add between 1 and 40 effect presets.");
        int uses = definition.has("uses") ? integer(definition, "uses", 0, 100) : 0;
        String consumeOn = definition.path("consumeOn").asText("selected");
        if (!"selected".equals(consumeOn) && !ROLLS.contains(consumeOn))
            throw new IllegalArgumentException("Choose a valid use expiration trigger.");
        if (!"selected".equals(consumeOn) && uses == 0)
            throw new IllegalArgumentException("Roll-based expiration requires a positive use limit.");
        ObjectNode effect = MAPPER.createObjectNode().put("id", "dm-" + UUID.randomUUID())
                .put("name", name).put("kind", kind).put("until", until)
                .put("dmManaged", true).put("text", text);
        if (uses > 0) effect.put("dmUsesRemaining", uses).put("dmUsesMax", uses);
        if (uses > 0 && !"selected".equals(consumeOn)) effect.put("dmConsumeOnRoll", consumeOn);
        ArrayNode modifiers = effect.putArray("modifiers");
        ArrayNode options = effect.putArray("rollOptions");
        ArrayNode mitigation = effect.putArray("mitigation");
        ArrayNode actions = effect.putArray("actions");
        int index = 0;
        for (JsonNode part : parts) {
            String type = part.path("type").asText();
            String target = part.path("target").asText();
            switch (type) {
                case "stat", "roll" -> {
                    if (!(type.equals("stat") ? STATS : ROLLS).contains(target))
                        throw new IllegalArgumentException("Choose a valid modifier target.");
                    int value = integer(part, "value", -100, 100);
                    String dice = "roll".equals(type) ? dice(part) : "";
                    if (value == 0 && dice.isEmpty())
                        throw new IllegalArgumentException("A modifier needs a nonzero value or die.");
                    ObjectNode modifier = modifiers.addObject().put(type.equals("stat") ? "stat" : "roll", target)
                            .put("value", value);
                    if (!dice.isEmpty()) modifier.put("dice", dice);
                }
                case "option" -> {
                    if (!ROLLS.contains(target)) throw new IllegalArgumentException("Choose a valid roll target.");
                    int value = integer(part, "value", -100, 100);
                    String dice = dice(part);
                    String advantage = part.path("advantage").asText("");
                    if (!Set.of("", "advantage", "disadvantage").contains(advantage))
                        throw new IllegalArgumentException("Choose a valid advantage option.");
                    if (value == 0 && dice.isEmpty() && advantage.isEmpty())
                        throw new IllegalArgumentException("A roll option needs a modifier, die or advantage.");
                    ObjectNode option = options.addObject().put("id", "part-" + index).put("label", name)
                            .put("roll", target).put("value", value).put("hostOwned", true);
                    if (!dice.isEmpty()) option.put("dice", dice);
                    if (!advantage.isEmpty()) option.put("advantage", advantage);
                    addCost(part, option);
                    if (part.path("defaultOn").asBoolean()) option.put("defaultOn", true);
                    if (uses > 0) option.put("exclusive", "dm-effect:" + effect.path("id").asText());
                }
                case "action" -> {
                    String resource = part.path("target").asText();
                    String operation = part.path("operation").asText();
                    if (!Set.of("hp", "stress", "hope", "armor").contains(resource)
                            || !Set.of("clear", "mark", "gain").contains(operation)
                            || "hope".equals(resource) && "mark".equals(operation)
                            || !"hope".equals(resource) && "gain".equals(operation))
                        throw new IllegalArgumentException("Choose a valid resource operation.");
                    int amount = integer(part, "value", 1, 100);
                    String label = part.path("label").asText("").trim();
                    if (label.isEmpty() || label.length() > 100)
                        throw new IllegalArgumentException("Enter an action label (1–100 characters).");
                    ObjectNode action = actions.addObject().put("id", "part-" + index)
                            .put("label", label).put("hostOwned", true);
                    addCost(part, action);
                    action.putArray("steps").addObject().put("op", operation).put("resource", resource).put("amount", amount);
                }
                case "narrative" -> {
                    String note = part.path("text").asText("").trim();
                    if (note.isEmpty() || note.length() > 1000)
                        throw new IllegalArgumentException("Enter a narrative effect description.");
                    effect.put("text", effect.path("text").asText().isEmpty() ? note : effect.path("text").asText() + "\n" + note);
                }
                case "mitigation" -> {
                    if (!Set.of("physical", "magic", "any").contains(target))
                        throw new IllegalArgumentException("Choose a valid damage type.");
                    String mode = part.path("mode").asText();
                    if (!Set.of("halve", "double", "negate", "reduce", "severity").contains(mode))
                        throw new IllegalArgumentException("Choose a valid damage rule.");
                    ObjectNode item = mitigation.addObject().put("id", "part-" + index).put("label", name).put("passive", true);
                    if (!target.equals("any")) item.putObject("when").put("type", target);
                    if (mode.equals("reduce") || mode.equals("severity")) item.put(mode, integer(part, "value", 1, 100));
                    else item.put(mode, true);
                }
                default -> throw new IllegalArgumentException("Unknown effect preset: " + type);
            }
            index++;
        }
        if (modifiers.isEmpty()) effect.remove("modifiers");
        if (options.isEmpty()) effect.remove("rollOptions");
        if (mitigation.isEmpty()) effect.remove("mitigation");
        if (actions.isEmpty()) effect.remove("actions");
        if (uses > 0 && "selected".equals(consumeOn) && options.isEmpty() && actions.isEmpty())
            throw new IllegalArgumentException("Use-based expiry requires a roll option or resource action.");
        return effect;
    }

    private static String dice(JsonNode part) {
        String dice = part.path("dice").asText("").trim();
        if (!dice.isEmpty() && !dice.matches("-?(?:[1-9]|[1-9][0-9])?d(?:4|6|8|10|12|20|100)"))
            throw new IllegalArgumentException("Enter dice such as 1d6, 2d8 or -1d6.");
        if (dice.startsWith("d")) return "1" + dice;
        if (dice.startsWith("-d")) return "-1" + dice.substring(1);
        return dice;
    }

    private static void addCost(JsonNode part, ObjectNode action) {
        int hope = part.has("hopeCost") ? integer(part, "hopeCost", 0, 100) : 0;
        int stress = part.has("stressCost") ? integer(part, "stressCost", 0, 100) : 0;
        if (hope > 0 || stress > 0) {
            ObjectNode cost = action.putObject("cost");
            if (hope > 0) cost.put("hope", hope);
            if (stress > 0) cost.put("stress", stress);
        }
    }

    static void consume(ObjectNode sheet, Set<String> effectIds) {
        ArrayNode effects = sheet.withArray("effects");
        for (String id : effectIds) {
            boolean found = false;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode raw = effects.get(i);
                if (!raw.path("dmManaged").asBoolean() || !id.equals(raw.path("id").asText())) continue;
                found = true;
                if (!raw.has("dmUsesRemaining")) continue;
                int remaining = raw.path("dmUsesRemaining").asInt();
                if (remaining <= 0) throw new IllegalArgumentException("This effect has no uses remaining.");
                if (remaining == 1) effects.remove(i);
                else ((ObjectNode) raw).put("dmUsesRemaining", remaining - 1);
            }
            if (!found) throw new IllegalArgumentException("This effect is no longer active.");
        }
    }

    static Set<String> matchingRolls(JsonNode sheet, java.util.List<String> tags, boolean duality) {
        Set<String> result = new java.util.HashSet<>();
        for (JsonNode effect : sheet.path("effects")) {
            String trigger = effect.path("dmConsumeOnRoll").asText("");
            if (effect.path("dmManaged").asBoolean() && effect.path("dmUsesRemaining").asInt() > 0
                    && tags.contains(trigger)
                    && (duality && !Set.of("damage", "physicaldamage", "magicdamage", "spelldamage").contains(trigger)
                    || !duality && tags.contains("damage") && Set.of("damage", "physicaldamage", "magicdamage", "spelldamage").contains(trigger)))
                result.add(effect.path("id").asText());
        }
        return result;
    }

    private static int integer(JsonNode node, String field, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.isInt() || value.asInt() < min || value.asInt() > max)
            throw new IllegalArgumentException("Enter a whole number between " + min + " and " + max + ".");
        return value.asInt();
    }
}
