package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The recipient's action spends the caster's pool in the same persisted roll transaction. */
final class InvisibilityEffects {
    static final String SOURCE_KEY = "card:Invisibility";
    static final String RESOURCE = "invisibility";
    static final String COPIED_SOURCE_KEY = "card:Copycat/Invisibility";
    static final String COPIED_RESOURCE = "Copycat/Invisibility/invisibility";

    private InvisibilityEffects() {}

    static boolean isCast(String key, JsonNode definition) {
        return (SOURCE_KEY.equals(key) || COPIED_SOURCE_KEY.equals(key)) && "cast".equals(definition.path("id").asText());
    }

    static void prepareCast(ObjectNode caster, int tokens) {
        prepareCast(caster, SOURCE_KEY, tokens);
    }

    static void prepareCast(ObjectNode caster, String key, int tokens) {
        caster.withObject("/resources").put(COPIED_SOURCE_KEY.equals(key) ? COPIED_RESOURCE : RESOURCE, Math.max(0, tokens));
    }

    private static boolean linked(JsonNode effect) {
        return RESOURCE.equals(effect.path("linkedTokenResource").asText()) || COPIED_RESOURCE.equals(effect.path("linkedTokenResource").asText());
    }

    private static boolean activeSource(JsonNode sheet, String key) {
        if (sheet == null) return false;
        if (COPIED_SOURCE_KEY.equals(key)) {
            JsonNode copycat = CopycatCards.activeCard(sheet, "Copycat");
            return copycat != null && "Invisibility".equals(copycat.path("borrowedCard").path("card").path("name").asText());
        }
        if (!SOURCE_KEY.equals(key)) return false;
        for (JsonNode card : sheet.path("loadout"))
            if ("Invisibility".equals(card.path("name").asText())) return true;
        return false;
    }

    private static boolean validSource(JsonNode effect) {
        return SOURCE_KEY.equals(effect.path("sourceKey").asText()) && RESOURCE.equals(effect.path("linkedTokenResource").asText())
                || COPIED_SOURCE_KEY.equals(effect.path("sourceKey").asText()) && COPIED_RESOURCE.equals(effect.path("linkedTokenResource").asText());
    }

    static void validateSave(ObjectNode saved, ObjectNode previous) {
        for (JsonNode effect : saved.path("effects")) {
            if (!linked(effect)) continue;
            boolean known = false;
            for (JsonNode old : previous.path("effects")) {
                if (linked(old) && old.equals(effect)) known = true;
            }
            if (!known)
                throw new IllegalArgumentException("Invisibility recipients can only be assigned by a successful cast. Refresh the sheet.");
        }
    }

    static void synchronize(Map<String, ObjectNode> sheets) {
        Set<String> sources = new HashSet<>();
        sheets.values().forEach(sheet -> {
            for (JsonNode effect : sheet.path("effects"))
                if (linked(effect)) sources.add(effect.path("sourceActorId").asText());
        });
        sheets.forEach((id, sheet) -> {
            boolean vaulted = false;
            for (JsonNode card : sheet.path("vault"))
                if ("Invisibility".equals(card.path("name").asText())) vaulted = true;
            if (!activeSource(sheet, SOURCE_KEY) && (vaulted || sources.contains(id)) && sheet.path("resources").path(RESOURCE).asInt() > 0)
                sheet.withObject("/resources").put(RESOURCE, 0);
            if (!activeSource(sheet, COPIED_SOURCE_KEY) && sheet.path("resources").path(COPIED_RESOURCE).asInt() > 0)
                sheet.withObject("/resources").put(COPIED_RESOURCE, 0);
            if (!(sheet.path("effects") instanceof ArrayNode effects)) return;
            for (int i = effects.size() - 1; i >= 0; i--) {
                JsonNode effect = effects.get(i);
                if (!linked(effect)) continue;
                JsonNode caster = sheets.get(effect.path("sourceActorId").asText());
                if (!validSource(effect) || !activeSource(caster, effect.path("sourceKey").asText())
                        || caster.path("resources").path(effect.path("linkedTokenResource").asText()).asInt() <= 0)
                    effects.remove(i);
            }
        });
    }

    static boolean spendSelected(Map<String, ObjectNode> sheets, String actorId, ObjectNode roll) {
        List<String> tags = List.of(roll.path("source").asText("").split(","));
        List<String> requested = tags.stream().filter(tag -> tag.startsWith("option:effect:")
                && tag.endsWith(":spend-token")).toList();
        if (requested.isEmpty()) return false;
        if (!"duality".equals(roll.path("kind").asText()) || !tags.contains("action") || tags.contains("reaction"))
            throw new IllegalArgumentException("Invisibility tokens can only be spent on an action roll.");
        ObjectNode recipient = sheets.get(actorId);
        if (recipient == null) throw new IllegalArgumentException("The Invisibility recipient is unavailable.");
        record Spend(ObjectNode caster, String resource) {}
        List<Spend> casters = new ArrayList<>();
        Set<String> spentSources = new HashSet<>();
        Set<String> resolvedTags = new HashSet<>();
        for (String tag : requested) {
            if (!resolvedTags.add(tag)) throw new IllegalArgumentException("Invisibility was selected more than once.");
            JsonNode selected = null;
            for (JsonNode effect : recipient.path("effects")) {
                if (linked(effect) && tag.equals("option:effect:" + effect.path("id").asText() + ":spend-token"))
                    selected = effect;
            }
            if (selected == null || !validSource(selected))
                throw new IllegalArgumentException("The selected Invisibility effect has ended. Refresh the sheet.");
            String sourceId = selected.path("sourceActorId").asText();
            ObjectNode caster = sheets.get(sourceId);
            String resource = selected.path("linkedTokenResource").asText();
            if (!activeSource(caster, selected.path("sourceKey").asText()) || caster.path("resources").path(resource).asInt() < 1)
                throw new IllegalArgumentException("The caster has no active Invisibility tokens. Refresh the sheet.");
            boolean optionExists = false;
            for (JsonNode option : selected.path("rollOptions"))
                if ("spend-token".equals(option.path("id").asText()) && "action".equals(option.path("roll").asText()))
                    optionExists = true;
            if (!optionExists || !spentSources.add(sourceId + ":" + resource))
                throw new IllegalArgumentException("Invalid Invisibility token option. Refresh the sheet.");
            casters.add(new Spend(caster, resource));
        }
        // Validate the entire selection before changing any sheet.
        for (Spend spend : casters) {
            int before = spend.caster().path("resources").path(spend.resource()).asInt();
            spend.caster().withObject("/resources").put(spend.resource(), before - 1);
        }
        synchronize(sheets);
        return true;
    }
}
