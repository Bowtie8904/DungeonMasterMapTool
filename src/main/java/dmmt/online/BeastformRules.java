package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-side Beastform assumption (the legacy {@code buildBeastform} and {@code addEffect}): builds the active-form effect
 * from the class catalog's {@code beastforms} and stores it in the sheet's effect list, replacing any other effect of
 * the {@code beastform} group. The weapon replacement, trait rolls, advantages and form features are then served by the
 * existing sheet effect machinery.
 */
final class BeastformRules {
    private static final List<String> TRAITS = List.of("Agility", "Strength", "Finesse", "Instinct", "Presence", "Knowledge");
    private static final int[] DIE_SIZES = {4, 6, 8, 10, 12, 20};
    private static final Pattern DIE = Pattern.compile("d(\\d+)");

    private BeastformRules() {}

    /** The ordinary and special Beastform categories of every class feature in the classes catalog. */
    static List<JsonNode> catalog(JsonNode classes) {
        List<JsonNode> forms = new ArrayList<>();
        for (JsonNode entry : classes.path("entries"))
            for (JsonNode feature : entry.path("features"))
                for (JsonNode form : feature.path("automation").path("beastforms")) forms.add(form);
        return forms;
    }

    static int tierOf(int level) {
        return level >= 8 ? 4 : level >= 5 ? 3 : level >= 2 ? 2 : 1;
    }

    /** Adds the chosen form to the sheet. Throws {@link IllegalArgumentException} with the legacy message for an invalid choice. */
    static void assume(ObjectNode sheet, JsonNode classes, JsonNode choice, boolean evolution, boolean anyTier, String until) {
        List<JsonNode> catalog = catalog(classes);
        ObjectNode effect = build(sheet.path("level").asInt(1), catalog, choice, evolution, anyTier);
        if (!until.isBlank()) effect.put("until", until);
        effect.put("until", effect.path("until").asText("manual"));
        effect.put("id", "e" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(sheet.path("effects").size(), 36));
        effect.put("source", "Beastform");
        ArrayNode effects = sheet.withArray("effects");
        for (int i = effects.size() - 1; i >= 0; i--) {
            JsonNode existing = effects.get(i);
            if (!existing.path("dmManaged").asBoolean() && (effect.path("group").asText().equals(existing.path("group").asText())
                    || effect.path("name").asText().equals(existing.path("name").asText()))) effects.remove(i);
        }
        effects.add(effect);
    }

    /** Maps the slug ids sent by the hybrid choice inputs back to the catalog's category, advantage and feature names. */
    static JsonNode canonical(List<JsonNode> catalog, JsonNode choice) {
        if (!(choice instanceof ObjectNode source)) return choice;
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode form : catalog) {
            names.putIfAbsent(slug(form.path("name").asText()), form.path("name").asText());
            form.path("advantages").forEach(a -> names.putIfAbsent(slug(a.asText()), a.asText()));
            form.path("features").forEach(f -> names.putIfAbsent(slug(f.path("name").asText()), f.path("name").asText()));
        }
        ObjectNode copy = source.deepCopy();
        for (String field : List.of("categories", "advantages", "features")) {
            if (!source.path(field).isArray()) continue;
            ArrayNode mapped = copy.putArray(field);
            for (JsonNode value : source.path(field)) mapped.add(names.getOrDefault(value.asText(), value.asText()));
        }
        return copy;
    }

    private static String slug(String text) {
        String slug = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "item";
        return slug.length() > 96 ? slug.substring(0, 96) : slug;
    }

    static ObjectNode build(int level, List<JsonNode> catalog, JsonNode choice, boolean evolution, boolean anyTier) {
        choice = canonical(catalog, choice);
        String formName = choice.path("form").asText("");
        JsonNode form = null;
        for (JsonNode candidate : catalog)
            if (candidate.path("name").asText().equals(formName) && (anyTier || candidate.path("tier").asInt() <= tierOf(level))) { form = candidate; break; }
        if (form == null) throw new IllegalArgumentException("Choose a Beastform of your tier or lower.");
        if (evolution && !TRAITS.contains(choice.path("trait").asText()))
            throw new IllegalArgumentException("Choose the trait raised by Evolution.");
        JsonNode base = form;
        if (form.has("evolved")) {
            int max = form.path("evolved").path("maxTier").asInt();
            JsonNode selected = ordinary(catalog, choice.path("base").asText(""), max);
            if (selected == null) throw new IllegalArgumentException("Choose an ordinary category of tier " + max + " or lower.");
            base = selected;
        }
        JsonNode advantages = base.path("advantages");
        List<JsonNode> features = new ArrayList<>();
        base.path("features").forEach(features::add);
        if (form.has("hybrid")) {
            JsonNode hybrid = form.path("hybrid");
            int count = hybrid.path("count").asInt();
            List<String> categories = strings(choice.path("categories"));
            if (categories.size() != count || new LinkedHashSet<>(categories).size() != count)
                throw new IllegalArgumentException("Choose " + count + " different ordinary categories of tier " + hybrid.path("maxTier").asInt() + " or lower.");
            for (String name : categories)
                if (ordinary(catalog, name, hybrid.path("maxTier").asInt()) == null)
                    throw new IllegalArgumentException("Choose " + count + " different ordinary categories of tier " + hybrid.path("maxTier").asInt() + " or lower.");
            Set<String> partAdvantages = new LinkedHashSet<>();
            Map<String, JsonNode> partFeatures = new LinkedHashMap<>();
            for (JsonNode candidate : catalog) {
                if (!categories.contains(candidate.path("name").asText())) continue;
                candidate.path("advantages").forEach(a -> partAdvantages.add(a.asText()));
                for (JsonNode feature : candidate.path("features")) partFeatures.putIfAbsent(feature.path("name").asText(), feature);
            }
            List<String> chosen = strings(choice.path("advantages"));
            int wanted = hybrid.path("advantages").asInt();
            if (chosen.size() != wanted || new LinkedHashSet<>(chosen).size() != wanted || !partAdvantages.containsAll(chosen))
                throw new IllegalArgumentException("Choose " + wanted + " different advantages from the selected categories.");
            List<String> names = strings(choice.path("features"));
            int wantedFeatures = hybrid.path("features").asInt();
            if (names.size() != wantedFeatures || new LinkedHashSet<>(names).size() != wantedFeatures || !partFeatures.keySet().containsAll(names))
                throw new IllegalArgumentException("Choose " + wantedFeatures + " different features from the selected categories.");
            ArrayNode picked = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            chosen.forEach(picked::add);
            advantages = picked;
            features = new ArrayList<>();
            for (var part : partFeatures.entrySet()) if (names.contains(part.getKey())) features.add(part.getValue());
        }
        if (base.path("trait").asText("").isEmpty() || base.path("damage").asText("").isEmpty())
            throw new IllegalArgumentException("The selected Beastform has no attack statistics.");
        String damage = base.path("damage").asText();
        if (form.path("evolved").path("increaseDie").asBoolean()) {
            Matcher matcher = DIE.matcher(damage);
            if (matcher.find()) {
                int index = 0;
                for (int i = 0; i < DIE_SIZES.length; i++) if (DIE_SIZES[i] == Integer.parseInt(matcher.group(1))) index = i;
                int next = DIE_SIZES[Math.min(DIE_SIZES.length - 1, index + 1)];
                damage = damage.substring(0, matcher.start()) + "d" + next + damage.substring(matcher.end());
            }
        }
        List<String> advantageNames = strings(advantages);
        String name = "Beastform: " + form.path("name").asText() + (form.has("evolved") ? " (" + base.path("name").asText() + ")" : "");
        ObjectNode effect = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        effect.put("name", name).put("kind", "buff").put("group", "beastform").put("beastform", true);
        ObjectNode weapon = effect.putObject("weapon");
        weapon.put("name", name).put("generated", "beastform").put("trait", base.path("trait").asText()).put("damage", damage)
                .put("damageType", "phys").put("range", base.path("range").asText("Melee")).put("slot", "Primary")
                .put("hands", 2).put("tier", form.path("tier").asInt());
        String list = String.join(", ", advantageNames);
        ArrayNode temporary = effect.putArray("features");
        ObjectNode advantage = temporary.addObject();
        advantage.put("source", name).put("name", "Beastform Advantages").put("text", "Gain advantage on: " + list + ".");
        ObjectNode option = advantage.putObject("automation").putArray("rollOptions").addObject();
        option.put("id", "advantages").put("label", form.path("name").asText() + ": " + list)
                .put("description", "Only a roll related to " + list + ".").put("roll", "duality").put("advantage", "advantage");
        for (JsonNode feature : features) {
            ObjectNode copy = feature.deepCopy();
            copy.put("source", name);
            temporary.add(copy);
        }
        effect.put("text", form.path("name").asText() + (form.has("evolved") ? " - " + base.path("name").asText() : "")
                + ". Gain advantage on: " + list + ".");
        ArrayNode modifiers = effect.putArray("modifiers");
        modifiers.addObject().put("stat", "trait." + base.path("trait").asText())
                .put("value", base.path("bonus").asInt(0) + form.path("evolved").path("trait").asInt(0));
        modifiers.addObject().put("stat", "evasion").put("value", base.path("evasion").asInt(0) + form.path("evolved").path("evasion").asInt(0));
        int damageBonus = form.path("evolved").path("damage").asInt(0);
        if (damageBonus != 0) modifiers.addObject().put("roll", "damage").put("value", damageBonus);
        if (evolution) modifiers.addObject().put("stat", "trait." + choice.path("trait").asText()).put("value", 1);
        boolean fragile = false;
        JsonNode damageRule = null;
        for (JsonNode feature : features) {
            fragile |= "Fragile".equals(feature.path("name").asText());
            if (damageRule == null && feature.path("automation").has("damage")) damageRule = feature.path("automation").get("damage");
        }
        if (fragile) effect.put("endOnDamage", 2);
        if (damageRule != null) effect.set("damage", damageRule.deepCopy());
        return effect;
    }

    private static JsonNode ordinary(List<JsonNode> catalog, String name, int maxTier) {
        for (JsonNode form : catalog)
            if (form.path("name").asText().equals(name) && form.path("tier").asInt() <= maxTier && !form.has("evolved") && !form.has("hybrid")) return form;
        return null;
    }

    private static List<String> strings(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value.isArray()) value.forEach(item -> out.add(item.asText()));
        return out;
    }
}
