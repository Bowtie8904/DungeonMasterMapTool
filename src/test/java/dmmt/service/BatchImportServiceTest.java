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
    void folderImportRecreatesSubFoldersButSkipsEmptyOnes() throws Exception {
        write(source, "cave.dd2vtt", "{\"pixels_per_grid\":100}");
        write(source.resolve("dungeon").resolve("level1"), "hall.dd2vtt", "{\"pixels_per_grid\":100}");
        Files.createDirectories(source.resolve("empty"));
        List<Path> maps = BatchImportService.findMaps(source);

        BatchImportService.Result result = service.importAll(maps, libraryRoot, source, null);

        assertTrue(result.failures().isEmpty());
        assertTrue(Files.exists(libraryRoot.resolve("cave").resolve("cave.dmmap")));
        assertTrue(Files.exists(libraryRoot.resolve("dungeon").resolve("level1").resolve("hall")
                .resolve("hall.dmmap")));
        assertFalse(Files.exists(libraryRoot.resolve("empty")));
    }

    @Test
    void replacesInvalidCharactersInNames() throws Exception {
        Path file = service.uniqueMapFile(libraryRoot, "a:b*c");

        assertEquals("a_b_c.dmmap", file.getFileName().toString());
    }

    @Test
    void filesNumberedLikeLevelsBecomeOneMultilevelMap() throws Exception {
        Path ground = write(source, "inn_00.dd2vtt", "{\"pixels_per_grid\":100}");
        Path hall = write(source, "hall.dd2vtt", "{\"pixels_per_grid\":100}");
        Path upper = write(source, "inn_01.dd2vtt", "{\"pixels_per_grid\":100}");
        List<Integer> steps = new ArrayList<>();

        BatchImportService.Result result = service.importAll(List.of(upper, hall, ground), libraryRoot,
                (index, total, name) -> steps.add(index));

        assertEquals(3, result.total());
        assertTrue(result.failures().isEmpty());
        Path manifest = libraryRoot.resolve("inn").resolve("inn.dmlevels");
        assertEquals(List.of(manifest, libraryRoot.resolve("hall").resolve("hall.dmmap")), result.imported());
        assertEquals(List.of(1, 2, 3), steps);
        ProjectService projectService = new ProjectService();
        MultiLevelService levels = new MapLibraryService(libraryRoot, projectService).multiLevels();
        assertEquals(List.of("inn_00", "inn_01"), levels.loadManifest(manifest).getLevels().stream()
                .map(dmmt.model.MultiLevelManifest.Level::getOriginalName).toList());
    }

    @Test
    void autoMergeCanBeDisabled() throws Exception {
        Path ground = write(source, "inn_00.dd2vtt", "{\"pixels_per_grid\":100}");
        Path upper = write(source, "inn_01.dd2vtt", "{\"pixels_per_grid\":100}");
        Tuning.apply(key -> "import.autoMergeMultiLevel".equals(key) ? "false" : null);
        try {
            BatchImportService.Result result = service.importAll(List.of(ground, upper), libraryRoot, null);

            assertEquals(List.of(libraryRoot.resolve("inn_00").resolve("inn_00.dmmap"),
                    libraryRoot.resolve("inn_01").resolve("inn_01.dmmap")), result.imported());
        } finally {
            Tuning.apply(key -> null);
        }
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
