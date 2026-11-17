package dmmt.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemArtworkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyMaintainedItemHasBundledArtworkAndOnlySelectedIconsAreIncluded() throws Exception {
        Set<String> selected = new HashSet<>();
        for (String category : List.of("armor", "weapons", "items", "consumables")) {
            var file = MAPPER.readTree(Path.of("dmmap-daggerheart", category + ".json").toFile());
            for (var entry : file.path("entries")) {
                String image = entry.path("image").asText();
                assertTrue(ItemArtwork.validPath(image), category + ": " + entry.path("name"));
                assertNotNull(ItemArtwork.resource(image), image);
                selected.add(image);
                String originalImage = entry.path("_editorOriginal").path("image").asText();
                if (!originalImage.isEmpty()) selected.add(originalImage);
            }
        }
        Set<String> bundled = new HashSet<>();
        Path root = Path.of("web", "public");
        try (var files = Files.walk(root.resolve("item-icons"))) {
            files.filter(path -> path.toString().endsWith(".svg"))
                    .forEach(path -> bundled.add("/" + root.relativize(path).toString().replace('\\', '/')));
        }
        assertEquals(selected, bundled, "Bundle only icons referenced by the maintained catalogs.");
    }

    @Test
    void catalogImagesAreValidatedAndOldCatalogsRemainValid() throws Exception {
        ObjectNode file = (ObjectNode) MAPPER.readTree("""
                {"format":"dmmap-daggerheart","version":1,"category":"items",
                 "entries":[{"id":"test","name":"Test"}]}
                """);
        ObjectNode entry = (ObjectNode) file.path("entries").get(0);
        assertDoesNotThrow(() -> CatalogEditor.validate(file, "items"));
        entry.put("image", "");
        assertDoesNotThrow(() -> CatalogEditor.validate(file, "items"));
        for (String image : List.of("https://example.org/a.svg", "file:///a.svg", "data:image/svg+xml,x",
                "/item-icons/../a.svg", "/item-icons/missing/icon.svg")) {
            entry.put("image", image);
            assertThrows(CatalogEditor.Rejected.class, () -> CatalogEditor.validate(file, "items"), image);
        }
        entry.put("image", 123);
        assertThrows(CatalogEditor.Rejected.class, () -> CatalogEditor.validate(file, "items"));
    }

    @Test
    void imageMetadataRefreshesLegacyGearAndPreservesOverridesAndMutableState() throws Exception {
        ObjectNode file = (ObjectNode) MAPPER.readTree("""
                {"category":"items","entries":[{"id":"torch","name":"Torch","image":"/item-icons/lorc/torch.svg"}]}
                """);
        ObjectNode sheet = (ObjectNode) MAPPER.readTree("""
                {"inventory":[{"name":"Torch","type":"gear","qty":3},
                 {"name":"Torch","type":"gear","qty":1,"custom":true},
                 {"name":"Torch","type":"gear","qty":2,"image":"/item-icons/lorc/fire.svg"}]}
                """);
        CatalogSheets.refresh(sheet, List.of(file));
        assertEquals("/item-icons/lorc/torch.svg", sheet.path("inventory").get(0).path("image").asText());
        assertEquals(3, sheet.path("inventory").get(0).path("qty").asInt());
        assertFalse(sheet.path("inventory").get(1).has("image"));
        assertEquals("/item-icons/lorc/fire.svg", sheet.path("inventory").get(2).path("image").asText());
        ((ObjectNode) file.path("entries").get(0)).put("image", "/item-icons/lorc/new.svg");
        CatalogSheets.refresh(sheet, List.of(file));
        assertEquals("/item-icons/lorc/new.svg", sheet.path("inventory").get(0).path("image").asText());
        assertEquals("/item-icons/lorc/fire.svg", sheet.path("inventory").get(2).path("image").asText());
    }
}
