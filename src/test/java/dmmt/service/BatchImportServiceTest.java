package dmmt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchImportServiceTest {
    @TempDir
    Path tempDir;

    private Path libraryRoot;
    private Path source;
    private BatchImportService service;

    @BeforeEach
    void setUp() throws Exception {
        libraryRoot = tempDir.resolve("library");
        source = tempDir.resolve("source");
        Files.createDirectories(libraryRoot);
        Files.createDirectories(source);
        ProjectService projectService = new ProjectService();
        service = new BatchImportService(new MapLibraryService(libraryRoot, projectService), projectService,
                new Dd2vttImportService());
    }

    private Path write(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void findsMapsRecursivelyAndIgnoresOtherFiles() throws Exception {
        write(source, "a.dd2vtt", "{}");
        write(source.resolve("sub"), "b.UVTT", "{}");
        write(source, "notes.txt", "x");
        write(source, "c.png", "x");

        List<Path> maps = BatchImportService.findMaps(source);

        assertEquals(2, maps.size());
        assertTrue(maps.stream().anyMatch(p -> p.getFileName().toString().equals("a.dd2vtt")));
        assertTrue(maps.stream().anyMatch(p -> p.getFileName().toString().equals("b.UVTT")));
    }

    @Test
    void importsAllMapsWithUniqueNames() throws Exception {
        Path a = write(source, "cave.dd2vtt", "{\"pixels_per_grid\":100}");
        Path b = write(source.resolve("other"), "cave.dd2vtt", "{\"pixels_per_grid\":100}");
        Path c = write(source, "hall.dd2vtt", "{\"pixels_per_grid\":100}");
        List<Integer> steps = new ArrayList<>();

        BatchImportService.Result result = service.importAll(List.of(a, b, c), libraryRoot,
                (index, total, name) -> steps.add(index));

        assertEquals(3, result.total());
        assertEquals(3, result.imported().size());
        assertTrue(result.failures().isEmpty());
        assertEquals(List.of(1, 2, 3), steps);
        assertTrue(Files.exists(libraryRoot.resolve("cave").resolve("cave.dmmap")));
        assertTrue(Files.exists(libraryRoot.resolve("cave (2)").resolve("cave (2).dmmap")));
        assertTrue(Files.exists(libraryRoot.resolve("hall").resolve("hall.dmmap")));
    }

    @Test
    void replacesInvalidCharactersInNames() throws Exception {
        Path file = service.uniqueMapFile(libraryRoot, "a:b*c");

        assertEquals("a_b_c.dmmap", file.getFileName().toString());
    }

    @Test
    void failedMapIsRemovedAndDoesNotStopTheBatch() throws Exception {
        Path bad = write(source, "bad.dd2vtt", "this is not json");
        Path good = write(source, "good.dd2vtt", "{\"pixels_per_grid\":100}");

        BatchImportService.Result result = service.importAll(List.of(bad, good), libraryRoot, null);

        assertEquals(1, result.imported().size());
        assertEquals(1, result.failures().size());
        assertEquals(bad, result.failures().get(0).source());
        assertFalse(Files.exists(libraryRoot.resolve("bad")));
        assertTrue(Files.exists(libraryRoot.resolve("good").resolve("good.dmmap")));
    }
}
