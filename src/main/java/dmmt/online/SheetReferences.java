package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Storage-only references; the wire representation remains a complete character sheet. */
final class SheetReferences {
    private static final String FIELDS = "_storedFields";
    private static final String BASELINES = "_storedBaselines";
    private static final List<String> LISTS = List.of("features", "heritage", "loadout", "vault");
    private static final List<String> CONTENT = List.of("text", "automation", "spells");

    private SheetReferences() { }

    static final class MissingReference extends IOException {
        MissingReference(String message) { super(message); }
    }

    static void compact(ObjectNode sheet) {
        for (String list : LISTS) for (JsonNode node : sheet.path(list)) {
            if (!(node instanceof ObjectNode row) || !(row.get("catalog") instanceof ObjectNode link)
                    || !(link.get("values") instanceof ObjectNode values)
                    || link.path("id").asText().isBlank() || link.path("category").asText().isBlank()
                    || link.has(FIELDS) || link.has(BASELINES)) continue;
            boolean card = "loadout".equals(list) || "vault".equals(list);
            if (card != "domain-cards".equals(link.path("category").asText())) continue;
            ArrayNode stored = row.arrayNode();
            ArrayNode baselines = row.arrayNode();
            for (String field : CONTENT) {
                if (values.has(field)) {
                    if (Objects.equals(values.get(field), row.get(field))) {
                        stored.add(field);
                        row.remove(field);
                    } else if ("automation".equals(field)) {
                        // Nested automation overrides need their old baseline for field-by-field merging.
                        continue;
                    } else baselines.add(field);
                    values.remove(field);
                }
            }
            if (!stored.isEmpty()) link.set(FIELDS, stored);
            if (!baselines.isEmpty()) link.set(BASELINES, baselines);
        }
    }

    static void resolve(ObjectNode sheet, List<ObjectNode> files) throws IOException {
        for (String list : LISTS) for (JsonNode node : sheet.path(list)) {
            if (!(node instanceof ObjectNode row) || !(row.get("catalog") instanceof ObjectNode link)
                    || !link.has(FIELDS) && !link.has(BASELINES)) continue;
            String category = link.path("category").asText();
            String id = link.path("id").asText();
            boolean card = "loadout".equals(list) || "vault".equals(list);
            if (link.has(FIELDS) && !link.path(FIELDS).isArray()
                    || link.has(BASELINES) && !link.path(BASELINES).isArray()
                    || id.isBlank() || card != "domain-cards".equals(category))
                throw new IOException("Invalid catalog reference on character sheet: " + row.path("name").asText());
            JsonNode entry = null;
            for (ObjectNode file : files) if (category.equals(file.path("category").asText()))
                for (JsonNode candidate : file.path("entries"))
                    if (id.equals(candidate.path("id").asText())) { entry = candidate; break; }
            if (entry == null) throw missing(category, id, row);
            JsonNode definition = entry;
            if (link.has("featureId") || link.has("feature")) {
                definition = null;
                int index = 0;
                for (JsonNode feature : entry.path("features")) {
                    boolean match = link.has("featureId")
                            ? link.path("featureId").asText().equals(CatalogEditor.featureId(entry, feature, "features", index))
                            : link.path("feature").asInt(-1) == index;
                    if (match) { definition = feature; break; }
                    index++;
                }
                if (definition == null) throw missing(category, id, row);
            }
            ObjectNode values = link.get("values") instanceof ObjectNode existing ? existing : link.putObject("values");
            resolveFields(row, values, definition, link.path(FIELDS), true);
            resolveFields(row, values, definition, link.path(BASELINES), false);
            link.remove(List.of(FIELDS, BASELINES));
        }
    }

    private static void resolveFields(ObjectNode row, ObjectNode values, JsonNode definition,
                                      JsonNode fields, boolean replace) throws IOException {
        for (JsonNode fieldNode : fields) {
            String field = fieldNode.asText();
            if (!fieldNode.isTextual() || !CONTENT.contains(field))
                throw new IOException("Invalid stored catalog field on character sheet: " + field);
            if (definition.has(field)) {
                if (replace) row.set(field, definition.get(field).deepCopy());
                values.set(field, definition.get(field).deepCopy());
            } else {
                if (replace) row.remove(field);
                values.remove(field);
            }
        }
    }

    private static MissingReference missing(String category, String id, ObjectNode row) {
        return new MissingReference("Missing catalog reference " + category + "/" + id + " for "
                + row.path("name").asText() + "; restore the catalog entry before opening this sheet.");
    }
}
