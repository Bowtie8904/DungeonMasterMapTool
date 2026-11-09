package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content-revision cache. Snapshots pin one operation's catalog data, never its mutable sheets. */
final class HostCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<Path, Document> cached = new LinkedHashMap<>();
    private long fileReads;
    private long parses;

    record Diagnostics(long fileReads, long parses) { }

    synchronized Diagnostics diagnostics() {
        return new Diagnostics(fileReads, parses);
    }

    synchronized Snapshot snapshot(Path directory) throws IOException {
        Map<String, Document> documents = new LinkedHashMap<>();
        if (directory != null && Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    Path key = file.toAbsolutePath().normalize();
                    Document document;
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        fileReads++;
                        document = cached.get(key);
                        if (document == null || !Arrays.equals(document.bytes, bytes)) {
                            document = new Document(bytes);
                            parses++;
                            cached.put(key, document);
                        }
                    } catch (IOException e) {
                        cached.remove(key);
                        document = new Document(e);
                    }
                    documents.put(file.getFileName().toString(), document);
                }
            }
        }
        cached.keySet().removeIf(path -> directory == null
                || !path.getParent().equals(directory.toAbsolutePath().normalize())
                || !documents.containsKey(path.getFileName().toString()));
        return new Snapshot(documents);
    }

    private static final class Document {
        final byte[] bytes;
        final String revision;
        final JsonNode file;
        final IOException error;
        final Map<String, JsonNode> automation = new LinkedHashMap<>();
        final Map<String, JsonNode> cards = new LinkedHashMap<>();
        final Map<String, JsonNode> spells = new LinkedHashMap<>();

        Document(IOException error) {
            this.bytes = null;
            this.revision = null;
            this.file = null;
            this.error = error;
        }

        Document(byte[] bytes) {
            this.bytes = bytes;
            this.revision = CampaignStore.sha256(bytes);
            JsonNode parsed = null;
            IOException failure = null;
            try {
                parsed = MAPPER.readTree(bytes);
                indexAutomation(parsed);
                if (parsed != null) for (JsonNode card : parsed.path("entries")) {
                    cards.putIfAbsent(card.path("name").asText(), card);
                    for (JsonNode spell : card.path("spells"))
                        spells.putIfAbsent(card.path("name").asText() + "/" + spell.path("name").asText(),
                                spell.path("automation"));
                }
            } catch (IOException e) {
                failure = e;
            }
            file = parsed;
            error = failure;
        }

        private void indexAutomation(JsonNode node) {
            if (node == null) return;
            if (node.isObject()) {
                if (node.path("automation").isObject())
                    automation.putIfAbsent(node.path("name").asText(), node.path("automation"));
                node.elements().forEachRemaining(this::indexAutomation);
            } else if (node.isArray()) node.forEach(this::indexAutomation);
        }

        void readable() throws IOException {
            if (error != null) throw error;
        }
    }

    static final class Snapshot {
        private final Map<String, Document> documents;

        Snapshot(Map<String, Document> documents) {
            this.documents = documents;
        }

        void refresh(ObjectNode sheet) throws IOException {
            List<ObjectNode> files = new ArrayList<>();
            for (String category : CatalogEditor.CATEGORIES) {
                Document document = documents.get(category + ".json");
                if (document == null) continue;
                document.readable();
                if (document.bytes.length > CatalogEditor.MAX_BYTES)
                    throw new CatalogEditor.Rejected(413, "Catalog file is too large.");
                if (!(document.file instanceof ObjectNode object) || !object.path("entries").isArray())
                    throw new CatalogEditor.Rejected(400, "Catalog must be an object with an entries list.");
                files.add(object);
            }
            CatalogSheets.refresh(sheet, files);
        }

        JsonNode automation(String key, String name, JsonNode fallback) {
            if (key.startsWith("card:Copycat/") || name.isBlank()) return fallback;
            String preferred = key.startsWith("card:") ? "domain-cards.json"
                    : key.startsWith("item:") ? "items.json"
                    : key.startsWith("weapon:") ? "weapons.json"
                    : key.startsWith("armor:") ? "armor.json" : "";
            List<String> ordered = new ArrayList<>(documents.keySet());
            if (ordered.remove(preferred)) ordered.addFirst(preferred);
            for (String category : ordered) {
                Document document = documents.get(category);
                if (document.error != null) return fallback;
                if (key.startsWith("card:") && key.contains("/") && category.equals(preferred)) {
                    JsonNode spell = document.spells.get(key.substring(5));
                    if (spell != null) return spell.deepCopy();
                }
                JsonNode automation = document.automation.get(name);
                if (automation != null) return automation.deepCopy();
            }
            return fallback;
        }

        void requireReadable() throws IOException {
            for (Document document : documents.values()) document.readable();
        }

        JsonNode automation(String name) {
            for (Document document : documents.values()) {
                JsonNode automation = document.automation.get(name);
                if (automation != null) return automation.deepCopy();
            }
            return null;
        }

        JsonNode card(String name) throws IOException {
            Document document = documents.get("domain-cards.json");
            if (document == null) return null;
            document.readable();
            JsonNode card = document.cards.get(name);
            return card == null ? null : card.deepCopy();
        }

        JsonNode document(String category) throws IOException {
            Document document = documents.get(category + ".json");
            if (document == null) return null;
            document.readable();
            return document.file == null ? null : document.file.deepCopy();
        }

        ObjectNode summary(String category) throws IOException {
            Document document = documents.get(category + ".json");
            if (document == null) return null;
            document.readable();
            if (document.file == null) return null;
            return MAPPER.createObjectNode().put("category", category)
                    .put("label", document.file.path("label").asText(category))
                    .put("count", document.file.path("entries").size()).put("revision", document.revision);
        }

        byte[] bytes(String category) throws IOException {
            Document document = documents.get(category + ".json");
            if (document == null) return null;
            if (document.bytes == null) document.readable();
            return document.bytes.clone();
        }

        String revision(String category) throws IOException {
            Document document = documents.get(category + ".json");
            if (document == null) return null;
            if (document.bytes == null) document.readable();
            return document.revision;
        }

        List<String> categories() {
            return documents.keySet().stream().map(name -> name.substring(0, name.length() - 5)).toList();
        }
    }
}
