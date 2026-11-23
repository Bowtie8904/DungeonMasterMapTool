package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostCatalogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Path directory;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createDirectories(Path.of("target", "host-catalog-tests", UUID.randomUUID().toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private ObjectNode items() throws IOException {
        return (ObjectNode) MAPPER.readTree("""
                {"format":"dmmap-daggerheart","version":1,"category":"items","entries":[
                 {"id":"torch","name":"Torch","text":"Light","automation":{"marker":"old"}}]}
                """);
    }

    @Test
    void warmSnapshotsReuseParsingButDetectSameSizeSameTimestampExternalWrites() throws Exception {
        Path file = directory.resolve("items.json");
        Files.writeString(file, items().toString());
        HostCatalog cache = new HostCatalog();
        HostCatalog.Snapshot first = cache.snapshot(directory);
        String oldRevision = first.revision("items");
        assertEquals(1, cache.diagnostics().parses());
        cache.snapshot(directory);
        assertEquals(1, cache.diagnostics().parses());
        assertEquals(2, cache.diagnostics().fileReads());
        var timestamp = Files.getLastModifiedTime(file);
        Files.writeString(file, Files.readString(file).replace("old", "new"));
        Files.setLastModifiedTime(file, timestamp);
        HostCatalog.Snapshot changed = cache.snapshot(directory);
        assertNotEquals(oldRevision, changed.revision("items"));
        assertEquals("new", changed.automation("item:Torch", "Torch", MAPPER.nullNode()).path("marker").asText());
        assertEquals("old", first.automation("item:Torch", "Torch", MAPPER.nullNode()).path("marker").asText(),
                "An in-flight operation keeps its coherent snapshot.");
        assertEquals(2, cache.diagnostics().parses());
    }

    @Test
    void catalogSavesErrorsDeletionAndDirectoryChangesNeverServeStaleMechanics() throws Exception {
        Path file = directory.resolve("items.json");
        Files.writeString(file, items().toString());
        HostCatalog cache = new HostCatalog();
        cache.snapshot(directory);
        CatalogEditor.Document before = CatalogEditor.read(directory, "items");
        ObjectNode entry = (ObjectNode) before.file().path("entries").get(0);
        entry.withObject("/automation").put("marker", "saved");
        CatalogEditor.save(directory, "items", before.revision(), "torch", entry);
        assertEquals("saved", cache.snapshot(directory).automation("feat:Torch", "Torch", MAPPER.nullNode()).path("marker").asText());
        Files.writeString(file, "{broken");
        HostCatalog.Snapshot broken = cache.snapshot(directory);
        assertTrue(broken.automation("item:Torch", "Torch", MAPPER.nullNode()).isNull());
        assertThrows(IOException.class, () -> broken.refresh(MAPPER.createObjectNode()));
        assertThrows(IOException.class, broken::requireReadable);
        Files.writeString(file, items().toString());
        assertEquals("old", cache.snapshot(directory).automation("item:Torch", "Torch", MAPPER.nullNode()).path("marker").asText());
        Files.delete(file);
        assertNull(cache.snapshot(directory).document("items"));
        assertNull(cache.snapshot(null).card("Torch"));
    }

    @Test
    void indexedLookupsPreservePreferenceNestedSpellsAndCallerIsolation() throws Exception {
        Files.writeString(directory.resolve("ancestries.json"), """
                {"category":"ancestries","entries":[{"name":"Book","automation":{"marker":"ancestry"}},
                 {"features":[{"name":"Nested","automation":{"marker":"nested"}}]}]}
                """);
        Files.writeString(directory.resolve("domain-cards.json"), """
                {"category":"domain-cards","entries":[{"name":"Book","automation":{"marker":"card"},
                 "spells":[{"name":"Spark","automation":{"marker":"spell"}}]}]}
                """);
        HostCatalog.Snapshot snapshot = new HostCatalog().snapshot(directory);
        assertEquals("card", snapshot.automation("card:Book", "Book", MAPPER.nullNode()).path("marker").asText());
        assertEquals("ancestry", snapshot.automation("feat:Book", "Book", MAPPER.nullNode()).path("marker").asText());
        assertEquals("nested", snapshot.automation("Nested").path("marker").asText());
        assertEquals("spell", snapshot.automation("card:Book/Spark", "Spark", MAPPER.nullNode()).path("marker").asText());
        ((ObjectNode) snapshot.card("Book")).put("name", "Changed");
        ((ObjectNode) snapshot.automation("Nested")).put("marker", "Changed");
        assertEquals("Book", snapshot.card("Book").path("name").asText());
        assertEquals("nested", snapshot.automation("Nested").path("marker").asText());
    }

    @Test
    void reservedRuleDocumentsStaySeparateAndSnapshotsTrackBothContents() throws Exception {
        Files.writeString(directory.resolve("items.json"), items().toString());
        Files.writeString(directory.resolve("rules.json"),
                "{\"name\":\"Hidden\",\"automation\":{\"marker\":\"legacy-rule\"}}");
        HostCatalog cache = new HostCatalog();
        HostCatalog.Snapshot legacy = cache.snapshot(directory);
        assertFalse(legacy.sharedRules());
        assertEquals(java.util.List.of("items"), legacy.categories());
        assertNull(legacy.document("rules"));
        assertNull(legacy.automation("Hidden"));
        assertNotNull(legacy.recipeRevision());

        Path rules = Files.createDirectories(directory.resolve("rules"));
        Path recipe = rules.resolve("recipes.json"), ruleset = rules.resolve("ruleset.json");
        Files.writeString(recipe, "{\"name\":\"Hidden\",\"automation\":{\"marker\":\"recipe\"}}");
        Files.writeString(ruleset, "{\"name\":\"Hidden\",\"automation\":{\"marker\":\"first\"}}");
        HostCatalog.Snapshot first = cache.snapshot(directory);
        assertTrue(first.sharedRules());
        assertEquals(java.util.List.of("items"), first.categories());
        assertNull(first.automation("Hidden"));
        assertTrue(first.automation("feat:Hidden", "Hidden", MAPPER.nullNode()).isNull());
        assertNotEquals(legacy.recipeRevision(), first.recipeRevision());
        String oldRuleset = first.rulesetRevision(), oldRecipe = first.recipeRevision();
        var timestamp = Files.getLastModifiedTime(ruleset);
        Files.writeString(ruleset, Files.readString(ruleset).replace("first", "later"));
        Files.setLastModifiedTime(ruleset, timestamp);
        Files.writeString(recipe, Files.readString(recipe).replace("recipe", "edited"));
        HostCatalog.Snapshot changed = cache.snapshot(directory);
        assertNotEquals(oldRuleset, changed.rulesetRevision());
        assertNotEquals(oldRecipe, changed.recipeRevision());
        assertTrue(new String(first.rulesetBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("first"));
        assertEquals(oldRuleset, first.rulesetRevision(), "An earlier snapshot remains pinned.");
        byte[] copy = changed.recipeBytes();
        copy[0] = 0;
        assertNotEquals(0, changed.recipeBytes()[0], "Callers cannot change a cached document.");
        Files.delete(ruleset);
        HostCatalog.Snapshot missing = cache.snapshot(directory);
        assertTrue(missing.sharedRules());
        assertNull(missing.rulesetBytes());
        assertNotNull(missing.recipeBytes());
        Files.delete(recipe);
        assertNull(cache.snapshot(directory).recipeBytes(), "An incomplete rules directory never uses the root recipe.");
        Files.delete(rules);
        assertEquals(legacy.recipeRevision(), cache.snapshot(directory).recipeRevision());
    }

    @Test
    void restPolicyRequiresKnownCatalogFormatsAndDoesNotConfuseSharedRulesWithReplacementCatalogs() throws Exception {
        HostCatalog cache = new HostCatalog();
        assertFalse(cache.snapshot(directory).requiresReplacementAutomation());
        Path legacy = directory.resolve("items.json");
        Files.writeString(legacy, items().toString());
        Path rules = Files.createDirectories(directory.resolve("rules"));
        Path ruleset = rules.resolve("ruleset.json");
        Files.writeString(ruleset, "{\"format\":\"dmmap-daggerheart-ruleset\",\"version\":2}");
        assertFalse(cache.snapshot(directory).requiresReplacementAutomation(),
                "The shipped format-1 catalogs already have a shared rules directory.");

        Path replacement = directory.resolve("classes.json");
        Files.writeString(replacement, """
                {"format":"dmmap-daggerheart","version":2,"category":"classes","entries":[]}
                """);
        assertTrue(cache.snapshot(directory).requiresReplacementAutomation(),
                "An incomplete replacement pack must not fall through to legacy rest.");

        for (String invalid : List.of(
                "{broken",
                "null",
                "{\"format\":\"dmmap-daggerheart\",\"category\":\"classes\",\"entries\":[]}",
                "{\"format\":\"dmmap-daggerheart\",\"version\":3,\"category\":\"classes\",\"entries\":[]}",
                "{\"format\":\"dmmap-daggerheart\",\"version\":\"2\",\"category\":\"classes\",\"entries\":[]}",
                "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"items\",\"entries\":[]}",
                "{\"format\":\"dmmap-daggerheart\",\"version\":2,\"category\":\"classes\"}")) {
            Files.writeString(replacement, invalid);
            assertThrows(IOException.class, () -> cache.snapshot(directory).requiresReplacementAutomation(), invalid);
        }
        Files.delete(replacement);
        Files.delete(legacy);
        assertTrue(cache.snapshot(directory).requiresReplacementAutomation(),
                "A standalone shared ruleset is a replacement candidate.");
        Files.writeString(ruleset, "{\"format\":\"dmmap-daggerheart-ruleset\",\"version\":4294967298}");
        assertThrows(IOException.class, () -> cache.snapshot(directory).requiresReplacementAutomation());
        Files.delete(ruleset);
        assertThrows(IOException.class, () -> cache.snapshot(directory).requiresReplacementAutomation());
    }

    @Test
    void hydrationKeepsIndependentSheetStateAndCatalogDefinitions() throws Exception {
        Files.writeString(directory.resolve("items.json"), items().toString());
        HostCatalog.Snapshot snapshot = new HostCatalog().snapshot(directory);
        ObjectNode first = (ObjectNode) MAPPER.readTree("""
                {"inventory":[{"name":"Torch","note":"Light","qty":2,"automation":{"marker":"old"}}],"resources":{"tokens":3}}
                """);
        ObjectNode second = first.deepCopy();
        snapshot.refresh(first);
        snapshot.refresh(second);
        ((ObjectNode) first.path("inventory").get(0)).withObject("/automation").put("marker", "changed");
        first.withObject("/resources").put("tokens", 0);
        assertEquals("old", second.path("inventory").get(0).path("automation").path("marker").asText());
        assertEquals(3, second.path("resources").path("tokens").asInt());
        assertEquals("old", snapshot.automation("Torch").path("marker").asText());
    }
}
