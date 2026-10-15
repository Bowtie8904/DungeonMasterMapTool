package dmmt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapExportServiceTest {
    @TempDir
    Path temp;

    @Test
    void exportedMapsCanBeImportedAgain() throws Exception {
        Path root = temp.resolve("library");
        Files.createDirectories(root);
        MapLibraryService library = new MapLibraryService(root, new ProjectService());
        Path first = library.newMapFile(root, "Cave");
        Files.createDirectories(first.getParent().resolve("assets"));
        Files.writeString(first, "{\"id\":\"a\"}");
        Files.writeString(first.getParent().resolve("assets").resolve("x.txt"), "asset");

        MapLibraryService.Entry entry = new MapLibraryService.Entry(MapLibraryService.Kind.MAP, "Cave",
                first.getParent(), first, List.of());
        MapExportService exporter = new MapExportService(library);
        Path pack = temp.resolve("cave" + MapExportService.EXTENSION);
        assertEquals(1, exporter.export(List.of(entry), pack));

        Path target = root.resolve("Imported");
        Files.createDirectories(target);
        List<Path> imported = exporter.importPack(pack, target);
        assertEquals(1, imported.size());
        assertTrue(Files.exists(imported.get(0)));
        assertEquals("Cave.dmmap", imported.get(0).getFileName().toString());
        assertEquals("asset", Files.readString(imported.get(0).getParent().resolve("assets").resolve("x.txt")));

        List<Path> again = exporter.importPack(pack, target);
        assertEquals("Cave (2).dmmap", again.get(0).getFileName().toString());
    }
}
