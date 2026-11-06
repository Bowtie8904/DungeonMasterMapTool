package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Refreshes catalog-owned fields, keeping overrides and all mutable character state. */
final class CatalogSheets {
    private CatalogSheets() { }

    static void refresh(ObjectNode sheet, List<ObjectNode> files) {
        for (ObjectNode file : files) {
            String category = file.path("category").asText();
            switch (category) {
                case "weapons" -> equipment(sheet, file, "activeWeapons", "carriedWeapons");
                case "armor" -> {
                    equipment(sheet, file, "carriedArmor");
                    if (sheet.get("activeArmor") instanceof ObjectNode armor) refreshItem(sheet, armor, file);
                }
                case "items", "consumables" -> {
                    for (JsonNode item : sheet.path("inventory"))
                        if (item instanceof ObjectNode row && !row.path("custom").asBoolean()
                                && "consumables".equals(category) == "consumable".equals(row.path("type").asText()))
                            refreshItem(sheet, row, file);
                }
                case "domain-cards" -> equipment(sheet, file, "loadout", "vault");
                case "classes", "ancestries", "communities", "transformations" -> {
                    for (String list : List.of("features", "heritage"))
                        for (JsonNode feature : sheet.path(list))
                            if (feature instanceof ObjectNode row) refreshFeature(sheet, row, file);
                    if (!"ancestries".equals(category)) syncLearnedBlocks(sheet, file);
                }
                default -> { }
            }
            for (JsonNode entry : file.path("entries")) renameSelections(sheet, category, entry);
        }
    }

    private static void syncLearnedBlocks(ObjectNode sheet, ObjectNode file) {
        String category = file.path("category").asText();
        String list = "classes".equals(category) ? "features" : "heritage";
        if (!sheet.path(list).isArray()) return;
        for (JsonNode entry : file.path("entries")) {
            if (!entry.has("_editorOriginal")) continue;
            ObjectNode learned = null;
            for (JsonNode row : sheet.path(list))
                if (row instanceof ObjectNode object && category.equals(row.path("catalog").path("category").asText())
                        && row.path("catalog").path("id").equals(entry.path("id"))) { learned = object; break; }
            if (learned == null) continue;
            int index = 0;
            for (JsonNode feature : entry.path("features")) {
                String id = CatalogEditor.featureId(entry, feature, "features", index++);
                boolean existedBefore = false;
                for (JsonNode old : entry.path("_editorOriginal").path("features"))
                    existedBefore |= old.path("_catalogFeatureId").asText().equals(id) || old.path("name").equals(feature.path("name"));
                if (existedBefore) continue;
                boolean exists = false;
                for (JsonNode row : sheet.path(list))
                    exists |= entry.path("id").equals(row.path("catalog").path("id"))
                            && (id.equals(row.path("catalog").path("featureId").asText())
                            || row.path("name").equals(feature.path("name")));
                if (exists) continue;
                String source = learned.path("source").asText();
                boolean hope = "hope".equals(feature.path("type").asText());
                if ("class".equals(entry.path("kind").asText())) {
                    if (source.startsWith("Multiclass") && hope) continue;
                    if (!source.startsWith("Multiclass")) source = hope ? "Hope feature" : "Class feature - " + entry.path("name").asText();
                }
                ObjectNode row = sheet.objectNode().put("source", source);
                ObjectNode values = sheet.objectNode(); copy(feature, values, "name", "text", "automation");
                copy(feature, row, "name", "text", "automation");
                if (hope) row.put("hope", true).put("cost", 3);
                merge(sheet, row, values, values, category, entry.path("id").asText());
                ((ObjectNode) row.get("catalog")).put("featureId", id);
                sheet.withArray(list).add(row);
            }
        }
    }

    private static void renameSelections(ObjectNode sheet, String category, JsonNode entry) {
        String old = entry.path("_editorOriginal").path("name").asText();
        String name = entry.path("name").asText();
        if (old.isBlank() || old.equals(name)) return;
        String fullOld = old, fullName = name;
        List<String> fields = switch (category) {
            case "classes" -> "subclass".equals(entry.path("kind").asText()) ? List.of("subclass") : List.of("className");
            case "ancestries" -> List.of("ancestry", "ancestry2");
            case "communities" -> List.of("community");
            case "transformations" -> List.of("transformation");
            case "weapons" -> List.of("primary", "secondary");
            case "armor" -> List.of("armor");
            default -> List.of();
        };
        if ("classes".equals(category) && "subclass".equals(entry.path("kind").asText())) {
            old = old.replaceFirst("^[^:]+:\\s*", "");
            name = name.replaceFirst("^[^:]+:\\s*", "");
        }
        renameFields(sheet, fields, old, name);
        if (sheet.path("mancer").get("choices") instanceof ObjectNode choices) {
            renameFields(choices, fields, fullOld, fullName);
            renameFields(choices, fields, old, name);
        }
        for (JsonNode level : sheet.path("levelUps"))
            for (JsonNode advancement : level.path("advancements"))
                if (advancement.get("multiclass") instanceof ObjectNode multi) {
                    renameFields(multi, fields, fullOld, fullName);
                    renameFields(multi, fields, old, name);
                }
        if (sheet.get("testingBuild") instanceof ObjectNode build) {
            renameFields(build, fields, fullOld, fullName);
            renameFields(build, fields, old, name);
        }
        for (String list : List.of("features", "heritage"))
            for (JsonNode feature : sheet.path(list))
                if (feature instanceof ObjectNode row && category.equals(row.path("catalog").path("category").asText())
                        && row.path("catalog").path("id").equals(entry.path("id"))) {
                    String source = row.path("source").asText();
                    if (source.startsWith("Multiclass ")) {
                        int split = source.indexOf(" - ");
                        if (split >= 0) row.put("source", "subclass".equals(entry.path("kind").asText())
                                ? source.substring(0, split) + " - " + name + " " + entry.path("stage").asText()
                                : "Multiclass " + name + " - class feature");
                    } else if (source.contains(" - ")) {
                        String suffix = source.endsWith(" (first)") ? " (first)" : source.endsWith(" (second)") ? " (second)" : "";
                        row.put("source", source.substring(0, source.indexOf(" - ") + 3) + name + suffix);
                    }
                }
    }

    private static void renameFields(ObjectNode object, List<String> fields, String old, String name) {
        for (String field : fields) {
            if (object.path(field).asText().equals(old)) object.put(field, name);
            else if ("ancestry".equals(field) && object.path(field).isTextual()) {
                String[] names = object.path(field).asText().split(" / ");
                for (int i = 0; i < names.length; i++) if (names[i].equals(old)) names[i] = name;
                object.put(field, String.join(" / ", names));
            }
        }
    }

    private static void equipment(ObjectNode sheet, ObjectNode file, String... lists) {
        for (String list : lists)
            for (JsonNode row : sheet.path(list))
                if (row instanceof ObjectNode item && !item.has("generated")) refreshItem(sheet, item, file);
    }

    private static boolean matches(ObjectNode row, JsonNode entry, String category) {
        JsonNode link = row.path("catalog");
        if (link.has("id"))
            return category.equals(link.path("category").asText()) && link.path("id").equals(entry.path("id"));
        return row.path("name").equals(entry.path("name"))
                || row.path("name").equals(entry.path("_editorOriginal").path("name"));
    }

    private static void refreshItem(ObjectNode sheet, ObjectNode row, ObjectNode file) {
        for (JsonNode entry : file.path("entries")) {
            String category = file.path("category").asText();
            if (!matches(row, entry, category)) continue;
            ObjectNode values = values(sheet, entry, category);
            JsonNode original = entry.path("_editorOriginal");
            ObjectNode baseline = values(sheet, original.isObject() ? original : entry, category);
            merge(sheet, row, values, baseline, category, entry.path("id").asText());
            return;
        }
    }

    private static ObjectNode values(ObjectNode sheet, JsonNode entry, String category) {
        ObjectNode values = sheet.objectNode();
        copy(entry, values, "name", "automation");
        switch (category) {
            case "armor" -> {
                copy(entry.path("armor"), values, "score", "major", "severe");
                copy(entry, values, "tier", "feature");
            }
            case "weapons" -> {
                JsonNode weapon = entry.path("weapon");
                values.put("slot", "secondary".equals(weapon.path("slot").asText()) ? "Secondary" : "Primary");
                values.put("damageType", "magic".equals(weapon.path("damage").path("type").asText()) ? "magic" : "phys");
                copy(weapon, values, "trait", "range");
                int bonus = weapon.path("damage").path("bonus").asInt();
                values.put("damage", weapon.path("damage").path("dice").asText()
                        + (bonus == 0 ? "" : bonus > 0 ? "+" + bonus : String.valueOf(bonus)));
                values.put("hands", weapon.path("burden").asText().toLowerCase(Locale.ROOT).contains("two") ? 2 : 1);
                copy(entry, values, "tier", "feature");
            }
            case "items", "consumables" -> {
                if (entry.has("text")) values.set("note", entry.get("text").deepCopy());
            }
            case "domain-cards" -> {
                copy(entry, values, "level", "domain", "text", "recall", "spells");
                if (entry.has("cardType")) values.set("type", entry.get("cardType"));
            }
            default -> { }
        }
        return values;
    }

    private static void refreshFeature(ObjectNode sheet, ObjectNode row, ObjectNode file) {
        String category = file.path("category").asText();
        for (JsonNode entry : file.path("entries")) {
            JsonNode original = entry.has("_editorOriginal") ? entry.get("_editorOriginal") : entry;
            JsonNode link = row.path("catalog");
            boolean linked = category.equals(link.path("category").asText()) && link.path("id").equals(entry.path("id"));
            String source = row.path("source").asText();
            String oldName = original.path("name").asText();
            boolean candidate = source.contains(oldName) || source.contains(entry.path("name").asText());
            if ("classes".equals(category)) {
                if ("subclass".equals(entry.path("kind").asText())) {
                    String shortName = oldName.replaceFirst("^[^:]+:\\s*", "");
                    candidate = source.contains(shortName) && source.toLowerCase(Locale.ROOT).contains(entry.path("stage").asText());
                } else candidate |= source.equals("Hope feature") && sheet.path("className").asText().equals(oldName);
            }
            if (!linked && (link.has("id") || !candidate)) continue;
            if (row.path("name").equals(entry.path("name")) || row.path("name").equals(original.path("name"))) {
                ObjectNode values = row.objectNode(); copy(entry, values, "name", "text", "automation");
                ObjectNode baseline = row.objectNode(); copy(original, baseline, "name", "text", "automation");
                merge(sheet, row, values, baseline, category, entry.path("id").asText());
                return;
            }
            int index = 0;
            for (JsonNode feature : entry.path("features")) {
                JsonNode old = original.path("features").path(index++);
                for (JsonNode candidateFeature : original.path("features"))
                    if (feature.has("_catalogFeatureId") && candidateFeature.path("_catalogFeatureId").equals(feature.path("_catalogFeatureId")))
                        old = candidateFeature;
                JsonNode linkedOriginal = original.path("features").path(link.path("feature").asInt(-1));
                boolean selected = linked && link.has("featureId")
                        ? link.path("featureId").asText().equals(CatalogEditor.featureId(entry, feature, "features", index - 1))
                        : linked && link.has("feature") ? linkedOriginal.has("_catalogFeatureId")
                            ? linkedOriginal.path("_catalogFeatureId").equals(feature.path("_catalogFeatureId"))
                            : link.path("feature").asInt(-1) == index - 1
                        : row.path("name").equals(feature.path("name")) || row.path("name").equals(old.path("name"));
                if (!selected) continue;
                ObjectNode values = row.objectNode(); copy(feature, values, "name", "text", "automation");
                ObjectNode baseline = row.objectNode(); copy(old.isObject() ? old : feature, baseline, "name", "text", "automation");
                merge(sheet, row, values, baseline, category, entry.path("id").asText());
                ((ObjectNode) row.get("catalog")).put("featureId", CatalogEditor.featureId(entry, feature, "features", index - 1));
                return;
            }
            if (linked && (link.has("featureId") || link.has("feature"))) {
                // A removed learned block must not continue executing its old catalog mechanics.
                row.remove("automation");
            }
        }
    }

    private static void merge(ObjectNode sheet, ObjectNode row, ObjectNode values, ObjectNode legacy, String category, String id) {
        String oldName = row.path("name").asText();
        JsonNode previous = row.path("catalog").path("values");
        if (!previous.isObject()) previous = legacy;
        var keys = new java.util.LinkedHashSet<String>();
        previous.fieldNames().forEachRemaining(keys::add);
        values.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            // Names are identities used by rules/actions; the stable link keeps renames attached.
            if ("name".equals(key) || Objects.equals(row.get(key), previous.get(key))) {
                if (values.has(key)) row.set(key, values.get(key).deepCopy());
                else row.remove(key);
            } else if (row.get(key) instanceof ObjectNode nested && previous.get(key) instanceof ObjectNode old
                    && values.get(key) instanceof ObjectNode next) {
                mergeFields(nested, old, next);
            }
        }
        ObjectNode link = row.objectNode().put("category", category).put("id", id);
        link.set("values", values.deepCopy());
        row.set("catalog", link);
        String name = row.path("name").asText();
        if (!oldName.equals(name)) {
            String kind = switch (category) {
                case "armor" -> "armor";
                case "weapons" -> "weapon";
                case "items", "consumables" -> "item";
                case "domain-cards" -> "card";
                default -> "feat";
            };
            String oldKey = kind + ":" + oldName, newKey = kind + ":" + name;
            if (sheet.get("usage") instanceof ObjectNode usage) {
                var usageKeys = new java.util.ArrayList<String>(); usage.fieldNames().forEachRemaining(usageKeys::add);
                for (String key : usageKeys)
                    if (key.equals(oldKey) || key.startsWith(oldKey + ":")) {
                        String target = newKey + key.substring(oldKey.length());
                        if (!usage.has(target)) usage.set(target, usage.get(key));
                        else if (usage.get(target) instanceof ObjectNode counter)
                            counter.put("used", Math.max(counter.path("used").asInt(), usage.path(key).path("used").asInt()));
                        usage.remove(key);
                    }
                    for (JsonNode effect : sheet.path("effects"))
                        if (effect instanceof ObjectNode object) {
                            String source = effect.path("source").asText();
                            if (source.equals(oldName) || source.startsWith(oldName + " - "))
                                object.put("source", name + source.substring(oldName.length()));
                            String sourceKey = effect.path("sourceKey").asText();
                            if (sourceKey.equals(oldKey) || sourceKey.startsWith(oldKey + ":"))
                                object.put("sourceKey", newKey + sourceKey.substring(oldKey.length()));
                        }
            }
        }
    }

    private static void copy(JsonNode from, ObjectNode to, String... keys) {
        for (String key : keys) if (from.has(key)) to.set(key, from.get(key).deepCopy());
    }

    private static void mergeFields(ObjectNode object, ObjectNode previous, ObjectNode next) {
        var fields = new java.util.LinkedHashSet<String>();
        previous.fieldNames().forEachRemaining(fields::add);
        next.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            if (Objects.equals(object.get(field), previous.get(field))) {
                if (next.has(field)) object.set(field, next.get(field).deepCopy());
                else object.remove(field);
            } else if (object.get(field) instanceof ObjectNode child && previous.get(field) instanceof ObjectNode old
                    && next.get(field) instanceof ObjectNode updated) mergeFields(child, old, updated);
        }
    }
}
