package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Host-side replacement of a character's ancestry heritage rows (the legacy {@code replaceAncestry}): the chosen
 * ancestry feature row replaces every "Ancestry - " row, stat allowances are re-balanced and the ancestry fields follow.
 */
final class HeritageRules {
    private static final String PREFIX = "Ancestry - ";

    private HeritageRules() {}

    /** Throws {@link IllegalArgumentException} for an invalid pick; otherwise rewrites the sheet and returns the dropped feature names. */
    static List<String> replace(ObjectNode sheet, JsonNode ancestries, String ancestry, String feature) {
        List<ObjectNode> choices = choices(ancestries, ancestry);
        ObjectNode chosen = null;
        for (ObjectNode choice : choices) if (choice.path("name").asText().equals(feature)) chosen = choice;
        if (chosen == null) throw new IllegalArgumentException("Choose a valid ancestry and feature.");
        ArrayNode heritage = sheet.withArray("heritage");
        List<JsonNode> old = new ArrayList<>();
        ArrayNode kept = sheet.arrayNode();
        for (JsonNode row : heritage) {
            if (row.path("source").asText().startsWith(PREFIX)) old.add(row);
            else kept.add(row);
        }
        List<String> removed = new ArrayList<>();
        for (JsonNode row : old) if (!row.path("name").asText().equals(chosen.path("name").asText())) removed.add(row.path("name").asText());
        for (String stat : List.of("hp", "stress", "evasion")) {
            int delta = grant(chosen.path("automation"), stat);
            for (JsonNode row : old) delta -= grant(row.path("automation"), stat);
            if ("evasion".equals(stat)) sheet.put("evasion", sheet.path("evasion").asInt() + delta);
            else if (sheet.path(stat).isObject()) {
                ObjectNode track = (ObjectNode) sheet.get(stat);
                int slots = Math.max(1, Math.min(track.path("total").asInt(), track.path("slots").asInt() + delta));
                track.put("slots", slots).put("on", Math.min(track.path("on").asInt(), slots));
            }
        }
        kept.add(chosen.deepCopy());
        heritage.removeAll();
        heritage.addAll(kept);
        sheet.put("ancestry", ancestry).put("ancestryFeature", feature);
        return removed;
    }

    /** Feature rows offered for an ancestry, splitting a mixed "A / B" ancestry into its first and second feature. */
    static List<ObjectNode> choices(JsonNode ancestries, String ancestry) {
        List<ObjectNode> result = new ArrayList<>();
        String[] names = ancestry.split(" / ", -1);
        for (int i = 0; i < names.length; i++) {
            JsonNode entry = null;
            for (JsonNode candidate : ancestries.path("entries"))
                if (candidate.path("name").asText().equals(names[i])) entry = candidate;
            if (entry == null) continue;
            int j = 0;
            for (JsonNode feature : entry.path("features")) {
                int index = j++;
                if (names.length > 1 && i != index) continue;
                ObjectNode row = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                row.put("source", PREFIX + names[i] + " (" + (index == 0 ? "first" : "second") + ")");
                row.put("name", feature.path("name").asText());
                row.put("text", feature.path("text").asText(""));
                if (feature.has("automation")) row.set("automation", feature.get("automation").deepCopy());
                row.putObject("catalog").put("category", "ancestries").put("id", entry.path("id").asText())
                        .put("featureId", CatalogEditor.featureId(entry, feature, "features", index));
                if (feature.path("id").isTextual() && !entry.path("id").asText().isBlank()) {
                    row.put("automationSource", "ancestries:" + entry.path("id").asText() + ":features:" + feature.path("id").asText());
                    row.put("instanceId", UUID.randomUUID().toString());
                }
                result.add(row);
            }
        }
        return result;
    }

    /** Creation allowance for a stat, from either the converted creation.apply plan adjustments or the legacy creation block. */
    private static int grant(JsonNode automation, String stat) {
        int total = automation.path("creation").path(stat).asInt(0);
        for (JsonNode rule : automation.path("rules"))
            if ("creation".equals(rule.path("id").asText()))
                for (JsonNode step : rule.path("do"))
                    if ("plan.adjust".equals(step.path("op").asText()) && "event.creation".equals(step.path("plan").asText())
                            && stat.equals(step.path("field").asText())) total += step.path("by").asInt();
        return total;
    }
}
