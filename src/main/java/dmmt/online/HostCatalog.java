package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Set<Path> observed = new HashSet<>();
        Document legacyRecipes = null, recipes = null, ruleset = null;
        boolean sharedRules = false;
        if (directory != null && Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    Document document = read(file, observed);
                    if (document == null) continue;
                    if ("rules.json".equals(file.getFileName().toString())) legacyRecipes = document;
                    else documents.put(file.getFileName().toString(), document);
                }
            }
            Path ruleDirectory = directory.resolve("rules");
            try {
                BasicFileAttributes attributes = Files.readAttributes(ruleDirectory, BasicFileAttributes.class);
                sharedRules = true;
                if (!attributes.isDirectory()) {
                    IOException error = new IOException("The reserved rules path is not a directory: " + ruleDirectory);
                    recipes = new Document(error);
                    ruleset = new Document(error);
                } else {
                    recipes = read(ruleDirectory.resolve("recipes.json"), observed);
                    ruleset = read(ruleDirectory.resolve("ruleset.json"), observed);
                }
            } catch (NoSuchFileException missing) {
                // Root rules.json remains supported for incomplete development fixtures.
            }
        }
        cached.keySet().removeIf(path -> !observed.contains(path));
        return new Snapshot(documents, sharedRules, sharedRules ? recipes : legacyRecipes, ruleset);
    }

    private Document read(Path file, Set<Path> observed) {
        Path key = file.toAbsolutePath().normalize();
        try {
            byte[] bytes = Files.readAllBytes(file);
            observed.add(key);
            fileReads++;
            Document document = cached.get(key);
            if (document == null || !Arrays.equals(document.bytes, bytes)) {
                document = new Document(bytes);
                parses++;
                cached.put(key, document);
            }
            return document;
        } catch (NoSuchFileException missing) {
            cached.remove(key);
            return null;
        } catch (IOException error) {
            cached.remove(key);
            return new Document(error);
        }
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
        private final boolean sharedRules;
        private final Document recipes;
        private final Document ruleset;

        Snapshot(Map<String, Document> documents, boolean sharedRules, Document recipes, Document ruleset) {
            this.documents = documents;
            this.sharedRules = sharedRules;
            this.recipes = recipes;
            this.ruleset = ruleset;
        }

        boolean sharedRules() { return sharedRules; }
        byte[] recipeBytes() throws IOException { return bytes(recipes); }
        byte[] rulesetBytes() throws IOException { return bytes(ruleset); }
        String recipeRevision() throws IOException { return revision(recipes); }
        String rulesetRevision() throws IOException { return revision(ruleset); }

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
            return bytes(documents.get(category + ".json"));
        }

        private static byte[] bytes(Document document) throws IOException {
            if (document == null) return null;
            if (document.bytes == null) document.readable();
            return document.bytes.clone();
        }

        String revision(String category) throws IOException {
            return revision(documents.get(category + ".json"));
        }

        private static String revision(Document document) throws IOException {
            if (document == null) return null;
            if (document.bytes == null) document.readable();
            return document.revision;
        }

        boolean declaresReplacementPack() {
            for (String category : CatalogEditor.CATEGORIES) {
                Document doc = documents.get(category + ".json");
                if (doc != null && doc.file != null && doc.file.path("version").asInt() == 2) return true;
            }
            return false;
        }

        boolean requiresAuthoritativeRest() throws IOException {
            boolean foundCatalog = false, replacement = false;
            for (String category : CatalogEditor.CATEGORIES) {
                Document doc = documents.get(category + ".json");
                if (doc == null) continue;
                foundCatalog = true;
                try {
                    doc.readable();
                } catch (IOException e) {
                    throw new IOException("Cannot read the " + category + " catalog.", e);
                }
                JsonNode file = doc.file;
                if (!(file instanceof ObjectNode) || !"dmmap-daggerheart".equals(file.path("format").asText())
                        || !category.equals(file.path("category").asText()) || !file.path("entries").isArray()
                        || !file.path("version").isIntegralNumber() || !file.path("version").canConvertToInt()
                        || (file.path("version").intValue() != 1 && file.path("version").intValue() != 2))
                    throw new IOException("The " + category + " catalog has an invalid or unsupported format.");
                replacement |= file.path("version").intValue() == 2;
            }
            if (foundCatalog || !sharedRules) return replacement;
            if (ruleset == null) throw new IOException("The shared ruleset is missing.");
            try {
                ruleset.readable();
            } catch (IOException e) {
                throw new IOException("Cannot read the shared ruleset.", e);
            }
            JsonNode file = ruleset.file;
            if (!(file instanceof ObjectNode) || !"dmmap-daggerheart-ruleset".equals(file.path("format").asText())
                    || !file.path("version").isIntegralNumber() || !file.path("version").canConvertToInt()
                    || file.path("version").intValue() != 2)
                throw new IOException("The shared ruleset has an invalid or unsupported format.");
            return true;
        }

        List<String> categories() {
            return documents.keySet().stream().map(name -> name.substring(0, name.length() - 5)).toList();
        }
    }
}
