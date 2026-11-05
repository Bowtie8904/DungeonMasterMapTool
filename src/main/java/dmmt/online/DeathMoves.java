package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

final class DeathMoves {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DeathMoves() {}

    record Source(String key, String name, JsonNode automation, JsonNode entry) {
        String kind() { return automation.path("deathMove").path("kind").asText(); }
        boolean applies(String move) {
            for (JsonNode value : automation.path("deathMove").path("moves"))
                if (move.equals(value.asText())) return true;
            return false;
        }
    }

    record Result(String title, String consequence, DiceRoller.Result dice,
                  List<DiceRoller.Contribution> contributions, ObjectNode deathResult) {
        Result(String title, String consequence, DiceRoller.Result dice) {
            this(title, consequence, dice, List.of(), null);
        }
    }

    static Result take(ObjectNode sheet, String move, Random random) {
        return take(sheet, MAPPER.createObjectNode().put("move", move), List.of(), random);
    }

    static ObjectNode options(ObjectNode sheet, List<Source> sources) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode options = result.putArray("options");
        for (String move : List.of("blaze", "avoid", "risk"))
            options.addObject().put("move", move).put("label", title(move));
        for (Source source : sources) {
            if (!List.of("spectral", "egg", "battleHardened", "unbreakable").contains(source.kind())) continue;
            ObjectNode option = options.addObject().put("move", source.kind()).put("label", title(source.kind())).put("key", source.key());
            String reason = blocker(sheet, source);
            if (reason != null) option.put("disabled", reason);
        }
        ArrayNode bonuses = result.putArray("bonuses");
        java.util.Set<String> added = new java.util.HashSet<>();
        for (Source source : sources) {
            if (!List.of("hopeBonus", "hopeSpend", "mask").contains(source.kind()) || !added.add(source.key())) continue;
            ObjectNode bonus = bonuses.addObject().put("key", source.key()).put("label", source.name()).put("kind", source.kind());
            bonus.set("moves", source.automation().path("deathMove").path("moves"));
            bonus.put("bonus", source.automation().path("deathMove").path("bonus").asInt());
            String reason = blocker(sheet, source);
            if (reason != null) bonus.put("disabled", reason);
        }
        result.put("undying", sources.stream().anyMatch(s -> "undying".equals(s.kind())));
        return result;
    }

    private static String title(String move) {
        return switch (move) {
            case "blaze" -> "Blaze of Glory";
            case "avoid" -> "Avoid Death";
            case "risk" -> "Risk It All";
            case "spectral" -> "Spectral Form";
            case "egg" -> "Break Cathartoid Egg";
            case "battleHardened" -> "Battle-Hardened";
            case "unbreakable" -> "Unbreakable";
            default -> throw new IllegalArgumentException("Choose a death move.");
        };
    }

    private static String blocker(ObjectNode sheet, Source source) {
        JsonNode uses = source.automation().path("uses");
        if (uses.isObject() && sheet.path("usage").path(source.key()).path("used").asInt() >= uses.path("max").asInt(1))
            return "No uses remaining until your next " + ("longRest".equals(uses.path("per").asText()) ? "long rest." : "rest.");
        if (List.of("hopeSpend", "battleHardened").contains(source.kind()) && sheet.path("hope").path("on").asInt() < 1)
            return "Not enough Hope.";
        if ("mask".equals(source.kind()) && sheet.path("resources").path("death-mask").asInt() < 1)
            return "No Mask tokens.";
        if ("spectral".equals(source.kind()) && sheet.path("spectralBlocked").asBoolean())
            return "Choose a different death move after Spectral Form ends at your last HP.";
        if (List.of("spectral", "battleHardened", "unbreakable").contains(source.kind()) && recoverableHp(sheet) == 0)
            return "All Hit Points are permanently marked.";
        return null;
    }

    private static void use(ObjectNode sheet, Source source) {
        String reason = blocker(sheet, source);
        if (reason != null) throw new IllegalArgumentException(reason);
        JsonNode uses = source.automation().path("uses");
        if (!uses.isObject()) return;
        ObjectNode counter = sheet.withObject("/usage").withObjectProperty(source.key());
        counter.put("used", counter.path("used").asInt() + 1).put("per", uses.path("per").asText());
    }

    static Result take(ObjectNode sheet, JsonNode request, List<Source> sources, Random random) {
        String move = request.path("move").asText();
        if (!List.of("blaze", "avoid", "risk", "spectral", "egg", "battleHardened", "unbreakable").contains(move))
            throw new IllegalArgumentException("Choose a death move.");
        int hp = sheet.path("hp").path("on").asInt();
        int slots = sheet.path("hp").path("slots").asInt();
        if (slots <= 0 || hp != slots)
            throw new IllegalArgumentException("A death move requires all Hit Points to be marked.");
        if (sheet.path("permanentHp").asInt() >= slots)
            throw new IllegalArgumentException("All Hit Points are permanently marked; this character has crossed the veil of death.");
        if (sheet.path("pendingDeathRoll").isObject())
            throw new IllegalArgumentException("Resolve the previous Risk It All roll in chat before making another death move.");
        for (JsonNode effect : sheet.path("effects"))
            if (effect.has("unconsciousHp"))
                throw new IllegalArgumentException("This character has already avoided death and is unconscious.");
        int blessed = integer(request, "blessedHope", 0);
        boolean mask = request.path("mask").asBoolean();
        if (request.has("mask") && !request.path("mask").isBoolean())
            throw new IllegalArgumentException("Invalid Mask selection.");
        if ((blessed != 0 || mask) && !List.of("risk", "avoid").contains(move))
            throw new IllegalArgumentException("Hope bonuses only apply to Risk It All or Avoid Death.");
        if (blessed > 0 && !"risk".equals(move))
            throw new IllegalArgumentException("Blessed only applies to Risk It All.");
        if ("blaze".equals(move))
            return new Result("Blaze of Glory",
                    "Take one final action at the GM's discretion. It critically succeeds, then cross through the veil of death.", null);
        if (!List.of("risk", "avoid").contains(move)) {
            Source source = sources.stream().filter(s -> move.equals(s.kind())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("This death move is not available on the character."));
            use(sheet, source);
            if ("spectral".equals(move)) {
                clearHp(sheet, 1);
                ObjectNode effect = sheet.withArray("effects").addObject();
                effect.put("id", "death-spectral").put("name", "Spectral Form").put("kind", "buff")
                        .put("source", source.name()).put("until", "manual").put("phaseMovement", true)
                        .put("spectralHp", sheet.path("hp").path("on").asInt())
                        .put("text", "Resistance to physical damage; pass through objects. Ends when you clear more HP or mark your last HP. "
                                + "If inside an object when it ends, the GM must move you to the nearest open space.");
                return new Result(title(move), "Cleared 1 HP and became spectral. Physical resistance and passage through walls/doors are active. "
                        + "Further HP healing or marking the last HP ends this form; choose a different death move at last HP. "
                        + "The GM handles shunting from inside solid objects.", null);
            }
            if ("egg".equals(move)) {
                if (!(source.entry() instanceof ObjectNode item) || item.path("qty").asInt() < 1)
                    throw new IllegalArgumentException("No Cathartoid Egg remains.");
                item.put("qty", item.path("qty").asInt() - 1);
                String narrative = "Cathartoid Egg: returned as an ancient black-winged avian, retaining memories, now desiring to reside in the Sanctum Mortalis.";
                sheet.put("deathNarrative", sheet.path("deathNarrative").asText("").isBlank() ? narrative
                        : sheet.path("deathNarrative").asText() + "\n" + narrative);
                sheet.put("inventoryRevision", sheet.path("inventoryRevision").asLong() + 1);
                return new Result(title(move), narrative + " One egg consumed; resolve the new form with the GM. No build or HP changes applied.", null);
            }
            if ("battleHardened".equals(move)) {
                ObjectNode hope = (ObjectNode) sheet.path("hope");
                hope.put("on", hope.path("on").asInt() - 1);
                int cleared = clearHp(sheet, 1);
                return new Result(title(move), "Spent 1 Hope and cleared " + cleared + " HP instead of a death move (once per long rest).", null);
            }
            DiceRoller.Result roll = DiceRoller.roll("1d6", 1, random);
            int cleared = clearHp(sheet, roll.total());
            ArrayNode loadout = sheet.withArray("loadout");
            for (int i = 0; i < loadout.size(); i++) {
                if (loadout.get(i) == source.entry()) {
                    sheet.withArray("vault").add(loadout.remove(i));
                    sheet.put("compendiumRevision", sheet.path("compendiumRevision").asLong() + 1);
                    sheet.put("inventoryRevision", sheet.path("inventoryRevision").asLong() + 1);
                    return new Result(title(move), "Cleared " + cleared + " HP instead of a death move; moved Unbreakable to the vault.", roll,
                            List.of(new DiceRoller.Contribution("Unbreakable", "1d6")), null);
                }
            }
            throw new IllegalArgumentException("Unbreakable must be in the active loadout.");
        }
        List<DiceRoller.Contribution> contributions = new ArrayList<>();
        contributions.add(new DiceRoller.Contribution("Hope die", "1d12"));
        if ("risk".equals(move)) contributions.add(new DiceRoller.Contribution("Fear die", "1d12"));
        int bonus = 0;
        java.util.Set<String> applied = new java.util.HashSet<>();
        for (Source source : sources) {
            if (!"hopeBonus".equals(source.kind()) || !source.applies(move) || !applied.add(source.key())) continue;
            int amount = source.automation().path("deathMove").path("bonus").asInt();
            bonus += amount;
            contributions.add(new DiceRoller.Contribution(source.name(), String.valueOf(amount)));
        }
        if (blessed > 0) {
            Source source = sources.stream().filter(s -> "hopeSpend".equals(s.kind()) && s.applies(move)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Blessed is not available on equipped armor."));
            if (blessed > sheet.path("hope").path("on").asInt())
                throw new IllegalArgumentException("Not enough Hope for Blessed.");
            use(sheet, source);
            ((ObjectNode) sheet.path("hope")).put("on", sheet.path("hope").path("on").asInt() - blessed);
            bonus += blessed;
            contributions.add(new DiceRoller.Contribution(source.name() + " (Hope spent)", String.valueOf(blessed)));
        }
        if (mask) {
            Source source = sources.stream().filter(s -> "mask".equals(s.kind()) && s.applies(move)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("The Mask is not carried."));
            if (!request.path("wearingMask").asBoolean())
                throw new IllegalArgumentException("Confirm that you are wearing the Mask.");
            use(sheet, source);
            ObjectNode resources = sheet.withObject("/resources");
            resources.put("death-mask", resources.path("death-mask").asInt() - 1);
            int amount = source.automation().path("deathMove").path("bonus").asInt(1);
            bonus += amount;
            contributions.add(new DiceRoller.Contribution(source.name() + " (1 token)", String.valueOf(amount)));
        }
        DiceRoller.Result dice = DiceRoller.roll("avoid".equals(move) ? "1d12" : "2d12", 2, random);
        if (bonus != 0) dice = new DiceRoller.Result(dice.expression() + (bonus > 0 ? "+" : "") + bonus, dice.rolls(), dice.total() + bonus);
        if ("risk".equals(move)) {
            int hope = dice.rolls().get(0);
            int fear = dice.rolls().get(1);
            int adjusted = hope + bonus;
            String outcome = hope == fear ? "crit" : adjusted >= fear ? "hope" : "fear";
            String result = hope == fear ? "Critical success: stay on your feet and clear all Hit Points and Stress."
                    : adjusted >= fear ? "Adjusted Hope succeeds: stay on your feet and clear " + adjusted
                    + " Hit Points and/or Stress, divided however you prefer."
                    : "Fear is higher: cross through the veil of death.";
            ObjectNode recovery = MAPPER.createObjectNode().put("outcome", outcome).put("hope", hope).put("fear", fear)
                    .put("adjustedHope", adjusted).put("bonus", bonus).put("budget", adjusted).put("claimed", false)
                    .put("undying", "fear".equals(outcome) && sources.stream().anyMatch(s -> "undying".equals(s.kind())));
            return new Result("Risk It All", result, dice, contributions, recovery);
        }
        ObjectNode effect = sheet.withArray("effects").addObject();
        effect.put("id", "death-unconscious").put("name", "Unconscious").put("kind", "debuff")
                .put("source", "Avoid Death").put("until", "longRest").put("blocksMovement", true)
                .put("unconsciousHp", hp)
                .put("text", "You cannot move or act and cannot be targeted by an attack. Work with the GM to describe how the situation worsens. "
                        + "Wake when an ally clears at least 1 marked HP or the party finishes a long rest. Only movement is enforced by the tool.");
        effect.putArray("modifiers");
        boolean scar = dice.total() <= sheet.path("level").asInt(1);
        if (scar) sheet.put("scars", Math.min(hopeTotal(sheet), sheet.path("scars").asInt() + 1));
        sync(sheet);
        return new Result("Avoid Death", "Fall unconscious; work with the GM to describe how the situation worsens. Adjusted Hope "
                + dice.total() + (scar ? " is at or below your level: gain a permanent scar and cross out one Hope slot."
                : " is above your level: no scar.")
                + " Wake when an ally clears at least 1 marked HP or the party finishes a long rest.", dice, contributions, null);
    }

    static int integer(JsonNode request, String field, int fallback) {
        JsonNode value = request.path(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isInt() || value.asInt() < 0) throw new IllegalArgumentException("Choose a nonnegative whole number for " + field + ".");
        return value.asInt();
    }

    static int recoverableHp(JsonNode sheet) {
        return Math.max(0, sheet.path("hp").path("on").asInt() - hpHealingFloor(sheet));
    }

    static int hpHealingFloor(JsonNode sheet) {
        int floor = sheet.path("permanentHp").asInt();
        for (JsonNode effect : sheet.path("effects")) floor += Math.max(0, effect.path("lockedHp").asInt());
        return Math.min(sheet.path("hp").path("slots").asInt(), floor);
    }

    static int clearHp(ObjectNode sheet, int amount) {
        ObjectNode hp = (ObjectNode) sheet.path("hp");
        int cleared = Math.min(Math.max(0, amount), recoverableHp(sheet));
        hp.put("on", hp.path("on").asInt() - cleared);
        return cleared;
    }

    static void recover(ObjectNode sheet, ObjectNode roll, JsonNode request, boolean undyingAvailable) {
        if (!"risk".equals(roll.path("deathMove").asText()) || !(roll.path("deathResult") instanceof ObjectNode result))
            throw new IllegalArgumentException("This is not a supported Risk It All recovery.");
        if (result.path("claimed").asBoolean())
            throw new IllegalArgumentException("This recovery has already been applied.");
        if (request.path("undying").asBoolean()) {
            if (!"fear".equals(result.path("outcome").asText()) || result.path("converted").asBoolean()
                    || !result.path("undying").asBoolean() || !undyingAvailable)
                throw new IllegalArgumentException("Won't Stay Dead is not available for this roll.");
            int permanent = sheet.path("permanentHp").asInt() + 1;
            int slots = sheet.path("hp").path("slots").asInt();
            if (slots <= 0 || permanent > slots) throw new IllegalArgumentException("All HP are already permanent.");
            sheet.put("permanentHp", permanent);
            result.put("converted", true).put("undying", false);
            if (permanent == slots) {
                result.put("claimed", true);
                roll.withObject("/featureRoll").put("consequence", "Permanently marked the last HP: cross through the veil of death.");
                sheet.remove("pendingDeathRoll");
            } else {
                result.put("outcome", "hope");
                roll.put("outcome", "hope");
                roll.withObject("/featureRoll").put("consequence", "Won't Stay Dead: permanently marked 1 HP to succeed. Clear "
                        + result.path("budget").asInt() + " HP and/or Stress using Apply recovery.");
            }
            sync(sheet);
            return;
        }
        if ("fear".equals(result.path("outcome").asText()))
            throw new IllegalArgumentException("A failed Risk It All roll cannot recover HP or Stress.");
        int hp = integer(request, "hp", 0);
        int stress = integer(request, "stress", 0);
        if ("crit".equals(result.path("outcome").asText())) {
            hp = recoverableHp(sheet);
            stress = sheet.path("stress").path("on").asInt();
        } else if ((long) hp + stress > result.path("budget").asInt()) {
            throw new IllegalArgumentException("Recovery exceeds the Hope result.");
        }
        if (hp > recoverableHp(sheet) || stress > sheet.path("stress").path("on").asInt())
            throw new IllegalArgumentException("Choose no more than the currently recoverable HP and Stress.");
        clearHp(sheet, hp);
        if (sheet.path("stress") instanceof ObjectNode track) track.put("on", track.path("on").asInt() - stress);
        result.put("claimed", true);
        sheet.remove("pendingDeathRoll");
        roll.withObject("/featureRoll").put("consequence", (result.path("converted").asBoolean() ? "Won't Stay Dead; " : "")
                + "Applied recovery: cleared " + hp + " HP and " + stress + " Stress.");
        sync(sheet);
    }

    private static int hopeTotal(JsonNode sheet) {
        return Math.max(0, sheet.path("hope").path("total").asInt(6));
    }

    static void sync(ObjectNode sheet) {
        if (sheet.path("hp") instanceof ObjectNode hp)
            hp.put("on", Math.max(hpHealingFloor(sheet), hp.path("on").asInt()));
        if (sheet.has("scars") && sheet.path("hope") instanceof ObjectNode hope) {
            int slots = Math.max(0, hopeTotal(sheet) - sheet.path("scars").asInt());
            hope.put("slots", slots);
            hope.put("on", Math.min(hope.path("on").asInt(), slots));
        }
        if (sheet.path("effects") instanceof ArrayNode effects) {
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                int on = sheet.path("hp").path("on").asInt();
                if (effect.has("spectralHp") && (on < effect.path("spectralHp").asInt()
                        || on >= sheet.path("hp").path("slots").asInt())) {
                    if (on >= sheet.path("hp").path("slots").asInt()) sheet.put("spectralBlocked", true);
                    effects.remove(i);
                } else if (effect.has("unconsciousHp") && on < effect.path("unconsciousHp").asInt())
                    effects.remove(i);
            }
        }
        if (sheet.path("hp").path("on").asInt() < sheet.path("hp").path("slots").asInt())
            sheet.put("spectralBlocked", false);
    }
}
