package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Revision-checked edits to the actual catalog, never to a browser-local copy. */
final class CatalogEditor {
    static final List<String> CATEGORIES = List.of("ancestries", "armor", "classes", "communities",
            "consumables", "domain-cards", "items", "transformations", "weapons");
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Document(ObjectNode file, String revision) { }

    static final class Rejected extends IllegalArgumentException {
        private final int status;
        Rejected(int status, String message) { super(message); this.status = status; }
        int status() { return status; }
    }

    private static Path path(Path directory, String category) {
        if (directory == null) throw new Rejected(404, "Configure the Daggerheart data directory first.");
        if (!CATEGORIES.contains(category)) throw new Rejected(404, "Unknown Daggerheart catalog.");
        return directory.resolve(category + ".json");
    }

    static Document read(Path directory, String category) throws IOException {
        Path path = path(directory, category);
        if (!Files.isRegularFile(path)) throw new Rejected(404, "Catalog file is missing: " + category + ".json");
        if (Files.size(path) > MAX_BYTES) throw new Rejected(413, "Catalog file is too large.");
        byte[] bytes = Files.readAllBytes(path);
        JsonNode file = MAPPER.readTree(bytes);
        if (!(file instanceof ObjectNode object) || !file.path("entries").isArray())
            throw new Rejected(400, "Catalog must be an object with an entries list.");
        return new Document(object, CampaignStore.sha256(bytes));
    }

    static synchronized Document save(Path directory, String category, String revision, String existingId,
                                      ObjectNode entry) throws IOException {
        Document before = read(directory, category);
        if (!before.revision().equals(revision))
            throw new Rejected(409, "This JSON file changed since you opened it. Your draft is retained; reload the catalog before saving.");
        ObjectNode file = before.file().deepCopy();
        var entries = file.withArray("entries");
        int index = -1;
        for (int i = 0; i < entries.size(); i++)
            if (existingId.equals(entries.get(i).path("id").asText())) index = i;
        if (!existingId.isEmpty() && index < 0) throw new Rejected(409, "This entry no longer exists. Reload the catalog.");
        ObjectNode saved = entry.deepCopy();
        saved.remove("_editorOriginal");
        if (index >= 0) {
            ObjectNode previous = ((ObjectNode) entries.get(index)).deepCopy();
            identifyFeatures(previous, null);
            if (!existingId.equals(saved.path("id").asText()))
                throw new Rejected(400, "An existing entry's stable ID cannot change.");
            saved.set("_editorOriginal", previous.has("_editorOriginal")
                    ? previous.get("_editorOriginal").deepCopy() : previous.deepCopy());
            identifyFeatures(saved, previous);
            entries.set(index, saved);
        } else {
            identifyFeatures(saved, null);
            entries.add(saved);
        }
        validate(file, category);
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(file);
        if (bytes.length > MAX_BYTES) throw new Rejected(413, "Catalog would exceed the file size limit.");
        Path target = path(directory, category);
        Path temporary = Files.createTempFile(target.getParent(), category + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            // Recheck after validation/staging so external edits are not knowingly overwritten.
            if (!CampaignStore.sha256(Files.readAllBytes(target)).equals(revision))
                throw new Rejected(409, "The JSON file changed while saving. Reload before retrying.");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Document(file, CampaignStore.sha256(bytes));
    }

    static ObjectNode editable(ObjectNode file) {
        ObjectNode copy = file.deepCopy();
        for (JsonNode entry : copy.path("entries"))
            if (entry instanceof ObjectNode object) identifyFeatures(object, null);
        return copy;
    }

    static String featureId(JsonNode entry, JsonNode feature, String field, int index) {
        String id = feature.path("_catalogFeatureId").asText();
        return id.isBlank() ? java.util.UUID.nameUUIDFromBytes((entry.path("id").asText() + ":" + field + ":" + index
                + ":" + feature.path("name").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString() : id;
    }

    private static void identifyFeatures(ObjectNode entry, JsonNode previous) {
        for (String field : List.of("features", "spells")) {
            Set<String> used = new HashSet<>();
            int index = 0;
            for (JsonNode node : entry.path(field)) {
                if (!(node instanceof ObjectNode feature)) continue;
                String id = feature.path("_catalogFeatureId").asText();
                if (id.isBlank() && previous != null)
                    for (JsonNode old : previous.path(field))
                        if (old.path("name").equals(feature.path("name"))) id = old.path("_catalogFeatureId").asText();
                if (id.isBlank() || !used.add(id)) {
                    id = featureId(entry, feature, field, index);
                    if (used.contains(id)) id = java.util.UUID.randomUUID().toString();
                    used.add(id);
                }
                feature.put("_catalogFeatureId", id);
                index++;
            }
        }
    }

    static void validate(ObjectNode file, String category) {
        if (!"dmmap-daggerheart".equals(file.path("format").asText()) || file.path("version").asInt() != 1
                || !category.equals(file.path("category").asText()))
            throw new Rejected(400, "Expected dmmap-daggerheart version 1 with category " + category + ".");
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode entry : file.path("entries")) {
            if (!entry.isObject()) throw new Rejected(400, "Every catalog entry must be an object.");
            text(entry, "id"); text(entry, "name");
            if (!entry.path("id").asText().matches("[a-zA-Z0-9_-]{1,128}"))
                throw new Rejected(400, "Entry ID must use 1-128 letters, digits, underscores or hyphens.");
            if (!ids.add(entry.path("id").asText())) throw new Rejected(400, "Entry IDs must be unique.");
            String nameKey = entry.path("name").asText() + ("classes".equals(category) ? ":" + entry.path("stage").asText() : "");
            if (!names.add(nameKey)) throw new Rejected(400, "Entry names must be unique (subclasses may repeat at different stages).");
            switch (category) {
                case "armor" -> {
                    object(entry, "armor");
                    integer(entry.path("armor"), "score", 0, 100);
                    integer(entry.path("armor"), "major", 0, 1000);
                    integer(entry.path("armor"), "severe", 0, 1000);
                    if (entry.path("armor").path("severe").asInt() < entry.path("armor").path("major").asInt())
                        throw new Rejected(400, "Severe threshold cannot be below Major threshold.");
                }
                case "weapons" -> {
                    object(entry, "weapon"); object(entry.path("weapon"), "damage");
                    oneOf(entry.path("weapon"), "slot", "primary", "secondary");
                    oneOf(entry.path("weapon").path("damage"), "type", "physical", "magic");
                    text(entry.path("weapon"), "trait"); text(entry.path("weapon"), "range");
                    text(entry.path("weapon"), "burden"); text(entry.path("weapon").path("damage"), "dice");
                    integer(entry.path("weapon").path("damage"), "bonus", -1000, 1000);
                }
                case "domain-cards" -> {
                    text(entry, "domain"); oneOf(entry, "cardType", "Ability", "Spell", "Grimoire");
                    integer(entry, "level", 1, 10); integer(entry, "recall", 0, 100);
                }
                case "classes" -> {
                    oneOf(entry, "kind", "class", "subclass");
                    if ("class".equals(entry.path("kind").asText())) {
                        integer(entry, "startingHp", 1, 100); integer(entry, "startingEvasion", 0, 100);
                        if (!entry.path("domains").isArray()) throw new Rejected(400, "Class domains must be a list.");
                    } else {
                        text(entry, "parent"); oneOf(entry, "stage", "foundation", "specialization", "mastery");
                    }
                }
                default -> { }
            }
            validateTree(entry, entry.path("name").asText(), 0);
        }
    }

    private static void validateTree(JsonNode node, String path, int depth) {
        if (depth > 40) throw new Rejected(400, "Automation nesting is too deep: " + path);
        if (node.isObject()) {
            boolean namedMap = path.matches(".*\\.(cost|extraBenefitCost|usesOverrides|diceByLevel|diceByFeature|maxByLevel|traitBonusByFeature|maxBenefitsByFeature|actionBenefits)$");
            boolean ruleFields = !namedMap && (depth == 0 || path.contains(".automation")
                    || path.endsWith(".features[]") || path.endsWith(".spells[]"));
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("_editorOriginal".equals(key)) continue;
                if (List.of("__proto__", "prototype", "constructor").contains(key))
                    throw new Rejected(400, "Reserved field name: " + key);
                if (ruleFields && "automation".equals(key) && !value.isObject())
                    throw new Rejected(400, path + ".automation must be a group.");
                if (ruleFields && List.of("actions", "steps", "modifiers", "resources", "rollOptions", "rollTriggers", "features", "spells",
                        "mitigation", "stances", "beastforms", "manual", "domains", "grantsFeatures").contains(key)
                        && !("features".equals(key) && path.endsWith(".hybrid")) && !value.isArray())
                    throw new Rejected(400, path + "." + key + " must be a list.");
                if (ruleFields && List.of("actions", "rollOptions", "rollTriggers", "resources").contains(key) && value.isArray())
                    for (JsonNode child : value) {
                        if (!child.isObject()) throw new Rejected(400, path + "." + key + " items must be groups.");
                        text(child, "id"); text(child, "label");
                    }
                if (ruleFields && "op".equals(key)) oneOf(node, "op", "clear", "gain", "mark", "effect");
                if (ruleFields && "uses".equals(key) && !"source".equals(value.asText())) {
                    object(node, key); integer(value, "max", 1, 1000);
                    oneOf(value, "per", "shortRest", "longRest", "rest", "scene", "session");
                }
                validateTree(value, path + "." + key, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) validateTree(child, path + "[]", depth + 1);
        }
    }

    private static void text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
            throw new Rejected(400, "Missing nonblank text field: " + key);
    }
    private static void object(JsonNode node, String key) {
        if (!node.path(key).isObject()) throw new Rejected(400, key + " must be a group.");
    }
    private static void integer(JsonNode node, String key, int min, int max) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max)
            throw new Rejected(400, key + " must be a whole number between " + min + " and " + max + ".");
    }
    private static void oneOf(JsonNode node, String key, String... choices) {
        if (!List.of(choices).contains(node.path(key).asText()))
            throw new Rejected(400, key + " must be one of: " + String.join(", ", choices) + ".");
    }
}
