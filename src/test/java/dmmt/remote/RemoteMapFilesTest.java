package dmmt.remote;

import dmmt.model.DmProject;
import dmmt.service.MapExportService;
import dmmt.service.MapLibraryService;
import dmmt.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMapFilesTest {
    @TempDir Path directory;

    @Test void importsVerifiedUploadedPackAndExportsOnlyPublishedObjects() throws Exception {
        var projects = new ProjectService();
        Path sourceRoot = Files.createDirectory(directory.resolve("source"));
        var source = new MapLibraryService(sourceRoot, projects);
        Path map = source.newMapFile(sourceRoot, "Cave");
        projects.save(map, new DmProject());
        var entry = new MapLibraryService.Entry(MapLibraryService.Kind.MAP, "Cave", map.getParent(), map, List.of());
        Path pack = directory.resolve("source.dmmpack");
        new MapExportService(source).export(List.of(entry), pack);
        var store = store();
        var asset = store.publishRequiredAsset(pack);
        Path hostRoot = Files.createDirectory(directory.resolve("host"));
        var host = new MapLibraryService(hostRoot, projects);
        Path cache = directory.resolve("jobs");
        var files = new RemoteMapFiles(store, cache, host, projects);
        var imported = files.importFiles(new RemoteMapFiles.Import(
                List.of(new RemoteMapFiles.Input("Cave.dmmpack", asset, null)), false, null, null), hostRoot);
        assertEquals(1, imported.imported().size());
        assertTrue(imported.failures().isEmpty());
        assertTrue(imported.imported().getFirst().startsWith(hostRoot));
        try (var paths = Files.list(cache)) { assertEquals(0, paths.count(), "Import staging must be removed."); }
        var output = files.export(host.scan().children());
        assertEquals(output, store.descriptor(output.sha256()));
        assertTrue(output.size() > 0);
        try (var paths = Files.list(cache)) { assertEquals(0, paths.count(), "Export staging must be removed."); }
    }

    @Test void invalidSourcesAndEscapingPacksNeverWriteOutsideTheLibrary() throws Exception {
        var store = store();
        var descriptor = new RemoteAssetStore.AssetDescriptor("a".repeat(64), 1);
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapFiles.Input("..\\outside.uvtt", descriptor, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapFiles.Input("file.uvtt", descriptor, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapFiles.Input("file.txt", descriptor, null));
        Path malicious = directory.resolve("malicious.dmmpack");
        try (var zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
            zip.putNextEntry(new ZipEntry("Cave/../../outside.dmmap"));
            zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var asset = store.publishRequiredAsset(malicious);
        Path root = Files.createDirectory(directory.resolve("host"));
        var library = new MapLibraryService(root, new ProjectService());
        var files = new RemoteMapFiles(store, directory.resolve("jobs"), library, new ProjectService());
        assertThrows(IOException.class, () -> files.importFiles(new RemoteMapFiles.Import(
                List.of(new RemoteMapFiles.Input("malicious.dmmpack", asset, null)), false, null, null), root));
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
        assertFalse(Files.exists(directory.resolve("outside.dmmap")));
        assertThrows(IOException.class, () -> files.importFiles(new RemoteMapFiles.Import(
                List.of(new RemoteMapFiles.Input("missing.uvtt", descriptor, null)), false, null, null), directory));
    }

    @Test void emptyMultilevelPlanIsAppliedOnTheHostWithoutClientSourcePaths() throws Exception {
        Path root = Files.createDirectory(directory.resolve("host"));
        var library = new MapLibraryService(root, new ProjectService());
        var files = new RemoteMapFiles(store(), directory.resolve("jobs"), library, new ProjectService());
        var request = new RemoteMapFiles.Import(List.of(), true, "Tower", null, List.of(
                new RemoteMapFiles.ImportLevel(-1, "Cellar"), new RemoteMapFiles.ImportLevel(-1, "Ground floor")));
        var result = files.importFiles(request, root);
        assertEquals(1, result.imported().size());
        assertEquals(List.of("Cellar", "Ground floor"), library.multiLevels().loadManifest(
                result.imported().getFirst()).getLevels().stream().map(level -> level.getName()).toList());
    }

    @Test void singleImportUsesChosenNameAndRetainsOriginalFilenameOnHost() throws Exception {
        Path source = Files.writeString(directory.resolve("Original.uvtt"), "{\"pixels_per_grid\":100}");
        var store = store();
        var asset = store.publishRequiredAsset(source);
        Files.delete(source);
        Path root = Files.createDirectory(directory.resolve("named-host"));
        var projects = new ProjectService();
        var library = new MapLibraryService(root, projects);
        var files = new RemoteMapFiles(store, directory.resolve("jobs"), library, projects);
        var result = files.importFiles(new RemoteMapFiles.Import(
                List.of(new RemoteMapFiles.Input("Original.uvtt", asset, null)), false, "Chosen on B", null), root);
        Path map = result.imported().getFirst();
        assertEquals("Chosen on B.dmmap", map.getFileName().toString());
        assertEquals("Original", projects.load(map).getMap().getOriginalFileName());
        assertThrows(IOException.class, () -> files.importFiles(new RemoteMapFiles.Import(
                List.of(new RemoteMapFiles.Input("Original.uvtt", asset, null)), false, "Chosen on B", null), root));
        assertTrue(Files.isRegularFile(map), "A rejected name collision must not delete the existing host map.");
    }

    private RemoteAssetStore store() throws IOException {
        return new RemoteAssetStore(directory.resolve("store"), 4 * 1024 * 1024, 32 * 1024 * 1024, 4 * 1024 * 1024);
    }
}
