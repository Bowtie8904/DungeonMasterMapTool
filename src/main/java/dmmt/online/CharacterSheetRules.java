package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Keeps testing-only privileges out of ordinary browser sheet saves. */
final class CharacterSheetRules {
    private CharacterSheetRules() {
    }

    static String problem(JsonNode sheet, JsonNode previous, boolean testing, int fear) {
        JsonNode marker = sheet.get("testingCharacter");
        if ((marker != null && !marker.isBoolean())
                || sheet.path("testingCharacter").asBoolean(false) != testing
                || (!testing && sheet.hasNonNull("testingBuild"))) {
            return "Only the DM can create a testing character. Reload this actor's sheet.";
        }
        if (testing) return null;
        if (!previous.path("className").asText("").isBlank()) {
            for (String field : List.of("className", "subclass", "ancestry", "community")) {
                if (!sheet.path(field).equals(previous.path(field))) {
                    return "Free class and heritage changes require a DM-marked testing character.";
                }
            }
        }
        int multiclasses = 0;
        for (JsonNode level : sheet.path("levelUps")) {
            for (JsonNode advancement : level.path("advancements")) {
                if ("multiclass".equals(advancement.path("type").asText())) {
                    if (++multiclasses > 1 || level.path("level").asInt() < 5) {
                        return "Unrestricted multiclassing requires a DM-marked testing character.";
                    }
                }
            }
        }
        double capacity = 5;
        List<JsonNode> sources = new ArrayList<>();
        sources.add(sheet.path("activeArmor").path("automation"));
        for (String field : List.of("activeWeapons", "features", "heritage", "loadout", "inventory")) {
            for (JsonNode entry : sheet.path(field)) {
                if (!"inventory".equals(field) || entry.path("qty").asInt() > 0) sources.add(entry.path("automation"));
            }
        }
        for (JsonNode effect : sheet.path("effects")) sources.add(effect);
        sources.addAll(CopycatCards.borrowedAutomation(sheet));
        for (JsonNode source : sources) {
            for (JsonNode modifier : source.path("modifiers")) {
                if ("loadoutMax".equals(modifier.path("stat").asText())) {
                    capacity += value(modifier.path("value"), sheet, fear);
                }
            }
        }
        return sheet.path("loadout").size() > Math.max(0, capacity)
                ? "The loadout exceeds this character's capacity. Only testing characters have unlimited active cards."
                : null;
    }

    static int spellcastTotal(com.fasterxml.jackson.databind.node.ObjectNode sheet, int fear) {
        JsonNode spellcast = null;
        for (JsonNode trait : sheet.path("traits"))
            if (trait.path("spellcast").asBoolean()) { spellcast = trait; break; }
        if (spellcast == null) return 0;
        String stat = "trait." + spellcast.path("name").asText();
        return statTotal(sheet, stat, spellcast.path("mod").asDouble(), fear);
    }

    static int proficiencyTotal(com.fasterxml.jackson.databind.node.ObjectNode sheet, int fear) {
        return Math.max(0, statTotal(sheet, "proficiency", sheet.path("proficiency").path("on").asDouble(), fear));
    }

    static int traitTotal(com.fasterxml.jackson.databind.node.ObjectNode sheet, String name, int fear) {
        for (JsonNode trait : sheet.path("traits"))
            if (name.equals(trait.path("name").asText())) return statTotal(sheet, "trait." + name, trait.path("mod").asDouble(), fear);
        return 0;
    }

    private static int statTotal(com.fasterxml.jackson.databind.node.ObjectNode sheet, String stat, double base, int fear) {
        List<JsonNode> sources = new ArrayList<>();
        sources.add(sheet.path("activeArmor").path("automation"));
        JsonNode replacementWeapon = null;
        for (JsonNode effect : sheet.path("effects"))
            if (effect.path("weapon").isObject()) { replacementWeapon = effect.path("weapon"); break; }
        if (replacementWeapon != null) sources.add(replacementWeapon.path("automation"));
        else for (JsonNode weapon : sheet.path("activeWeapons")) sources.add(weapon.path("automation"));
        for (String field : List.of("features", "heritage", "loadout", "inventory")) {
            for (JsonNode entry : sheet.path(field))
                if (!"inventory".equals(field) || entry.path("qty").asInt() > 0) sources.add(entry.path("automation"));
        }
        for (JsonNode effect : sheet.path("effects")) {
            sources.add(effect);
            for (JsonNode feature : effect.path("features")) sources.add(feature.path("automation"));
        }
        double total = base;
        sources.addAll(CopycatCards.borrowedAutomation(sheet));
        List<JsonNode> modifiers = new ArrayList<>();
        for (JsonNode source : sources) {
            for (JsonNode modifier : source.path("modifiers")) modifiers.add(modifier);
            for (JsonNode modifier : source.path("conditionalModifiers")) modifiers.add(modifier);
        }
        for (JsonNode modifier : modifiers) {
            if (!stat.equals(modifier.path("stat").asText())
                    && !(stat.startsWith("trait.") && "traits".equals(modifier.path("stat").asText()))) continue;
            if (modifier.has("requiresEffect")
                    && !DruidEffects.hasEffect(sheet, modifier.path("requiresEffect").asText())) continue;
            JsonNode required = modifier.path("loadoutDomain");
            if (required.isObject()) {
                int count = 0;
                for (JsonNode card : sheet.path("loadout"))
                    if (card.path("domain").asText().equalsIgnoreCase(required.path("domain").asText())) count++;
                if (count < required.path("min").asInt()) continue;
            }
            double amount = modifier.has("traitValue") ? traitTotal(sheet, modifier.path("traitValue").asText(), fear)
                    : value(modifier.path("value"), sheet, fear);
            if (modifier.path("divisor").asDouble() > 0) {
                amount /= modifier.path("divisor").asDouble();
                amount = "up".equals(modifier.path("round").asText()) ? Math.ceil(amount) : Math.floor(amount);
            }
            total += amount;
        }
        return (int) total;
    }

    private static double value(JsonNode value, JsonNode sheet, int fear) {
        if (value.isNumber()) return value.asDouble();
        String expression = value.asText("").trim().toLowerCase(Locale.ROOT);
        int sign = expression.startsWith("-") ? -1 : 1;
        String key = expression.replaceFirst("^[+-]?@", "");
        if (!expression.matches("[+-]?@\\w+")) {
            try {
                double numeric = Double.parseDouble(expression);
                return Double.isFinite(numeric) ? numeric : 0;
            } catch (NumberFormatException ignored) {
                return 0; // Same unsupported-value semantics as the browser's evalValue.
            }
        }
        int level = sheet.path("level").asInt();
        double amount = switch (key) {
            case "level" -> level;
            case "tier" -> level <= 1 ? 1 : level <= 4 ? 2 : level <= 7 ? 3 : 4;
            case "proficiency" -> sheet.path("proficiency").path("on").asInt();
            case "fear" -> fear;
            default -> 0;
        };
        for (JsonNode trait : sheet.path("traits")) {
            if (key.equals(trait.path("name").asText("").toLowerCase(Locale.ROOT))
                    || ("spellcast".equals(key) && trait.path("spellcast").asBoolean())) {
                amount = trait.path("mod").asDouble();
                break;
            }
        }
        if (key.startsWith("cards_")) {
            for (String field : List.of("loadout", "vault")) {
                for (JsonNode card : sheet.path(field)) {
                    if (key.substring(6).equals(card.path("domain").asText("").toLowerCase(Locale.ROOT))) amount++;
                }
            }
        }
        return sign * amount;
    }
}
