package dmmt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateCheckServiceTest {
    @TempDir
    Path tempDir;

    private Path libraryRoot;
    private Path source;
    private ProjectService projectService;
    private MapLibraryService library;
    private DuplicateCheckService duplicateCheck;

    @BeforeEach
    void setUp() throws Exception {
        libraryRoot = tempDir.resolve("library");
        source = tempDir.resolve("source");
        Files.createDirectories(libraryRoot);
        Files.createDirectories(source);
        projectService = new ProjectService();
        library = new MapLibraryService(libraryRoot, projectService);
        duplicateCheck = new DuplicateCheckService(library, projectService);
    }

    private Path writeSource(String name) throws Exception {
        return Files.writeString(source.resolve(name), "{\"pixels_per_grid\":100}");
    }

    private void importIntoLibrary(String fileName) throws Exception {
        Path src = writeSource(fileName);
        BatchImportService batch = new BatchImportService(library, projectService, new Dd2vttImportService());
        batch.importAll(List.of(src), libraryRoot, null);
    }

    @Test
    void detectsDuplicateByOriginalFileNameNotLibraryMapName() throws Exception {
        importIntoLibrary("cave.dd2vtt");
        // Rename the imported map in the library; the original file name is unaffected.
        MapLibraryService.Entry root = library.scan();
        MapLibraryService.Entry caveEntry = root.children().stream()
                .filter(e -> e.name().equalsIgnoreCase("cave")).findFirst().orElseThrow();
        library.rename(caveEntry, "Renamed Dungeon");

        Path reimport = writeSource("cave.dd2vtt");
        List<Path> duplicates = duplicateCheck.findDuplicates(List.of(reimport));

        assertEquals(1, duplicates.size());
        assertTrue(duplicates.contains(reimport));
    }

    @Test
    void nonDuplicateFileIsNotFlagged() throws Exception {
        importIntoLibrary("cave.dd2vtt");
        Path other = writeSource("hall.dd2vtt");

        List<Path> duplicates = duplicateCheck.findDuplicates(List.of(other));

        assertTrue(duplicates.isEmpty());
    }

    @Test
    void olderMapWithoutOriginalFileNameIsNeverADuplicate() throws Exception {
        // Simulate an older map saved before the originalFileName field existed: a plain empty-map package whose
        // library name happens to match the import file name.
        Path folder = libraryRoot.resolve("cave");
        Files.createDirectories(folder);
        projectService.save(folder.resolve("cave.dmmap"), dmmt.model.DmProject.builder().build());

        Path reimport = writeSource("cave.dd2vtt");
        List<Path> duplicates = duplicateCheck.findDuplicates(List.of(reimport));

        assertTrue(duplicates.isEmpty());
    }

    @Test
    void detectsDuplicateLevelInsideMultiLevelMap() throws Exception {
        Path level1 = writeSource("tower_1.dd2vtt");
        Path level2 = writeSource("tower_2.dd2vtt");
        List<MultiLevelService.PlanItem> plan = List.of(
                new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(level1), "Level 1"),
                new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(level2), "Level 2"));
        library.multiLevels().create(libraryRoot, "Tower", plan, null);

        Path reimport = writeSource("tower_2.dd2vtt");
        List<Path> duplicates = duplicateCheck.findDuplicates(List.of(reimport));

        assertEquals(1, duplicates.size());
        assertTrue(duplicates.contains(reimport));
    }
}
