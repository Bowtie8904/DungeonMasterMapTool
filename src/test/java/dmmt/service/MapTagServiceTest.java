package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTagServiceTest {
    @TempDir
    Path tempDir;

    private Path root;
    private Path sources;
    private ProjectService projectService;
    private MapLibraryService library;
    private MapTagService tags;

    @BeforeEach
    void setUp() throws IOException {
        Tuning.reset();
        root = tempDir.resolve("library");
        sources = tempDir.resolve("sources");
        Files.createDirectories(root);
        Files.createDirectories(sources);
        projectService = new ProjectService();
        library = new MapLibraryService(root, projectService);
        tags = new MapTagService(library, projectService);
    }

    @AfterEach
    void resetTuning() {
        Tuning.reset();
    }

    private Path map(String name, String... mapTags) throws IOException {
        DmProject project = DmProject.builder().build();
        project.getMap().setTags(List.of(mapTags));
        Path file = root.resolve(name).resolve(name + ".dmmap");
        projectService.save(file, project);
        return file;
    }

    private Path dd2vtt(String name) throws IOException {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        Path file = sources.resolve(name + ".dd2vtt");
        Files.writeString(file, "{\"image\":\"" + Base64.getEncoder().encodeToString(png.toByteArray())
                + "\",\"resolution\":{\"pixels_per_grid\":10}}");
        return file;
    }

    private static MultiLevelService.PlanItem item(MultiLevelService.Source source, String name) {
        return new MultiLevelService.PlanItem(source, name);
    }

    private Path tower() throws IOException {
        return library.multiLevels().create(root, "Tower", List.of(
                item(new MultiLevelService.Dd2vtt(dd2vtt("tower_00")), "Ground"),
                item(new MultiLevelService.Dd2vtt(dd2vtt("tower_01")), "First")), null).manifestFile();
    }

    private List<String> levelTags(Path manifestFile) throws IOException {
        List<String> result = new ArrayList<>();
        for (MultiLevelManifest.Level level : library.multiLevels().loadManifest(manifestFile).getLevels()) {
            result.add(String.join(",", projectService.load(MultiLevelService.levelFile(manifestFile, level))
                    .getMap().getTags()));
        }
        return result;
    }

    // ---- Model and normalization ----

    @Test
    void normalizeTrimsDropsBlanksAndDeduplicatesIgnoringCase() {
        assertEquals(List.of("TAVERN", "CITY"),
                MapTagService.normalize(Arrays.asList(" Tavern ", "", null, "   ", "tavern", "city", "CITY ")));
        assertTrue(MapTagService.normalize(null).isEmpty());
        assertEquals(List.of("INN", "\u0130STANBUL"),
                MapTagService.normalize(List.of("Inn", "INN", "\u0130stanbul", "istanbul")));
    }

    @Test
    void tagsPersistAndOlderOrNullTagsLoadAsEmpty() throws IOException {
        Path file = map("Inn", " Tavern", "tavern", "Town ");
        assertEquals(List.of("TAVERN", "TOWN"), projectService.load(file).getMap().getTags());

        Path legacy = root.resolve("legacy.dmmap");
        Files.writeString(legacy, "{\"schemaVersion\":1,\"map\":{\"sourceType\":\"custom\"}}");
        assertEquals(List.of(), projectService.load(legacy).getMap().getTags());
        assertEquals(List.of(), tags.readTags(legacy));

        Path nullTags = root.resolve("null.dmmap");
        Files.writeString(nullTags, "{\"map\":{\"tags\":null}}");
        assertNotNull(projectService.load(nullTags).getMap().getTags());
        assertEquals(List.of(), projectService.load(nullTags).getMap().getTags());
        assertEquals(List.of(), tags.readTags(nullTags));

        Path messy = root.resolve("messy.dmmap");
        Files.writeString(messy, "{\"map\":{\"tags\":[\" a \",\"A\",null,\"\",\"b\"]}}");
        assertEquals(List.of("A", "B"), projectService.load(messy).getMap().getTags());
        assertEquals(List.of("A", "B"), tags.readTags(messy));

        DmProject built = DmProject.builder().map(DmProject.MapInfo.builder().tags(null).build()).build();
        assertEquals(List.of(), built.getMap().getTags());
        built.setMap(DmProject.MapInfo.builder().tags(List.of(" Tavern ", "TAVERN")).build());
        assertEquals(List.of("TAVERN"), built.getMap().getTags());
    }

    // ---- Known tags and scanning ----

    @Test
    void knownTagsAreTheDeduplicatedUnionOfTheLibraryIncludingLevels() throws IOException {
        map("Inn", "Tavern", "town");
        Files.createDirectories(root.resolve("Folder"));
        DmProject nested = DmProject.builder().build();
        nested.getMap().setTags(List.of("TAVERN", "Forest"));
        projectService.save(root.resolve("Folder").resolve("Woods").resolve("Woods.dmmap"), nested);
        Path manifest = tower();
        tags.updateTags(List.of(manifest), List.of("castle"), List.of());

        List<String> known = tags.knownTags();
        assertEquals(4, known.size(), known.toString());
        for (String tag : List.of("castle", "forest", "tavern", "town")) {
            assertTrue(MapTagService.containsTag(known, tag), tag);
        }
        assertEquals("CASTLE", known.get(0));
    }

    @Test
    void scanFillsEntryTagsAndMultilevelUnion() throws IOException {
        map("Inn", "Tavern");
        Path manifest = tower();
        MultiLevelManifest levels = library.multiLevels().loadManifest(manifest);
        tags.updateTags(List.of(MultiLevelService.levelFile(manifest, levels.getLevels().get(0))),
                List.of("cellar", "Shared"), List.of());
        tags.updateTags(List.of(MultiLevelService.levelFile(manifest, levels.getLevels().get(1))),
                List.of("shared", "roof"), List.of());

        MapLibraryService.Entry scanned = library.scan();
        MapLibraryService.Entry inn = scanned.children().stream().filter(e -> e.name().equals("Inn")).findFirst().orElseThrow();
        MapLibraryService.Entry tower = scanned.children().stream().filter(e -> e.name().equals("Tower")).findFirst().orElseThrow();
        assertEquals(List.of("TAVERN"), inn.tags());
        assertEquals(List.of("CELLAR", "SHARED", "ROOF"), tower.tags());
        assertEquals(List.of("CELLAR", "SHARED", "ROOF"), tags.readTags(manifest));
        assertEquals(List.of(), scanned.tags());
    }

    @Test
    void unreadableMapReportsErrorsForScanReadAndEdit() throws IOException {
        map("Inn", "Tavern");
        Path broken = root.resolve("Broken").resolve("Broken.dmmap");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "not json");

        assertThrows(IOException.class, tags::knownTags);
        assertThrows(IOException.class, () -> tags.readTags(broken));
        assertThrows(IOException.class, () -> tags.updateTags(List.of(broken), List.of("x"), List.of()));
    }

    // ---- Editing ----

    @Test
    void incompleteTagArrayReportsAnError() throws IOException {
        Path broken = root.resolve("truncated.dmmap");
        Files.writeString(broken, "{\"map\":{\"tags\":[\"Tavern\"");
        assertThrows(IOException.class, () -> tags.readTags(broken));
    }

    @Test
    void failedBulkWriteRestoresAlreadyChangedMapsAndRemovesTemporaryFiles() throws IOException {
        Path inn = map("Inn", "Tavern");
        Path cave = map("Cave", "Dark");
        byte[] innBefore = Files.readAllBytes(inn);
        byte[] caveBefore = Files.readAllBytes(cave);
        MapTagService failing = new MapTagService(library, projectService) {
            private int writes;

            @Override
            void moveIntoPlace(Path prepared, Path target) throws IOException {
                if (++writes == 2) {
                    throw new IOException("Simulated disk failure");
                }
                super.moveIntoPlace(prepared, target);
            }
        };

        assertThrows(IOException.class,
                () -> failing.updateTags(List.of(inn, cave), List.of("Town"), List.of()));
        assertArrayEquals(innBefore, Files.readAllBytes(inn));
        assertArrayEquals(caveBefore, Files.readAllBytes(cave));
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void bulkAddKeepsExistingTagsAndOtherContent() throws IOException {
        Path inn = map("Inn", "Tavern");
        Path cave = map("Cave");
        DmProject before = projectService.load(inn);
        before.getWalls().add(DmProject.WallSegment.builder().x1(1).y1(2).x2(3).y2(4).build());
        before.getMap().setOriginalFileName("inn_original");
        projectService.save(inn, before);
        byte[] thumbnailBefore = {1, 2, 3};
        Files.write(ThumbnailService.thumbnailFile(inn), thumbnailBefore);

        tags.updateTags(List.of(inn, cave), List.of(" tavern ", "Night", "night"), List.of());

        DmProject after = projectService.load(inn);
        assertEquals(List.of("TAVERN", "NIGHT"), after.getMap().getTags());
        assertEquals(List.of("TAVERN", "NIGHT"), tags.readTags(cave));
        assertEquals(1, after.getWalls().size());
        assertEquals("inn_original", after.getMap().getOriginalFileName());
        before.getMap().setTags(List.of("Tavern", "Night"));
        assertEquals(projectService.fingerprint(before), projectService.fingerprint(after));
        assertArrayEquals(thumbnailBefore, Files.readAllBytes(ThumbnailService.thumbnailFile(inn)));
    }

    @Test
    void removalIgnoresCaseAndUnknownJsonFieldsSurvive() throws IOException {
        Path file = root.resolve("custom.dmmap");
        Files.writeString(file, "{\"futureField\":{\"x\":1},\"map\":{\"tags\":[\"Tavern\",\"Town\"],\"extra\":true}}");

        tags.updateTags(List.of(file), List.of(), List.of("TAVERN"));

        String json = Files.readString(file);
        assertTrue(json.contains("futureField"));
        assertTrue(json.contains("extra"));
        assertEquals(List.of("TOWN"), tags.readTags(file));
    }

    @Test
    void multilevelAddAndRemoveApplyToEveryLevel() throws IOException {
        Path manifest = tower();
        MultiLevelManifest levels = library.multiLevels().loadManifest(manifest);
        tags.updateTags(List.of(MultiLevelService.levelFile(manifest, levels.getLevels().get(0))),
                List.of("cellar"), List.of());

        tags.updateTags(List.of(manifest), List.of("Wizard"), List.of());
        assertEquals(List.of("CELLAR,WIZARD", "WIZARD"), levelTags(manifest));
        assertEquals(List.of("CELLAR", "WIZARD"), tags.readTags(manifest));

        tags.updateTags(List.of(manifest), List.of(), List.of("CELLAR"));
        assertEquals(List.of("WIZARD", "WIZARD"), levelTags(manifest));
        assertEquals(List.of("WIZARD"), tags.readTags(manifest));
    }

    @Test
    void levelTagsSurviveCombineExtractAndDissolve() throws IOException {
        Path inn = map("Inn", "Tavern");
        Path cellar = map("Cellar", "Dark");
        MultiLevelService levels = library.multiLevels();
        Path manifest = levels.create(root, "Combined", List.of(
                item(new MultiLevelService.LibraryMap(inn.getParent(), inn), "Ground"),
                item(new MultiLevelService.LibraryMap(cellar.getParent(), cellar), "Cellar")), null).manifestFile();
        assertEquals(List.of("TAVERN", "DARK"), tags.readTags(manifest));

        tags.updateTags(List.of(manifest), List.of("Town"), List.of());
        MultiLevelManifest loaded = levels.loadManifest(manifest);
        String cellarId = loaded.getLevels().get(1).getId();
        MultiLevelService.ApplyResult extracted = levels.apply(manifest,
                List.of(item(new MultiLevelService.Existing(loaded.getLevels().get(0).getId()), "Ground")),
                List.of(new MultiLevelService.Extraction(cellarId, "Cellar")), null);
        Path extractedCellar = extracted.movedMaps().values().stream()
                .filter(p -> p.getFileName().toString().equals("Cellar.dmmap")).findFirst().orElseThrow();
        assertEquals(List.of("DARK", "TOWN"), tags.readTags(extractedCellar));
        assertNull(extracted.manifestFile());
        assertEquals(List.of("TAVERN", "TOWN"), tags.readTags(extracted.collapsedMap()));

        Path tower = tower();
        tags.updateTags(List.of(tower), List.of("Wizard"), List.of());
        MultiLevelManifest towerLevels = levels.loadManifest(tower);
        tags.updateTags(List.of(MultiLevelService.levelFile(tower, towerLevels.getLevels().get(1))),
                List.of("roof"), List.of());
        MultiLevelService.ApplyResult dissolved = levels.dissolve(tower, null);
        List<List<String>> dissolvedTags = new ArrayList<>();
        for (Path file : dissolved.movedMaps().values()) {
            dissolvedTags.add(tags.readTags(file));
        }
        assertTrue(dissolvedTags.contains(List.of("WIZARD")));
        assertTrue(dissolvedTags.contains(List.of("WIZARD", "ROOF")));
    }

    // ---- Import matching ----

    @Test
    void defaultAutoTagsWorkInAnEmptyLibraryAndPreserveProjectTags() throws IOException {
        assertTrue(tags.knownTags().isEmpty());
        DmProject project = DmProject.builder().build();
        project.getMap().setTags(List.of("custom"));
        tags.applyKnownTags(project, Path.of("Dark_Forest_Tavern.uvtt"));
        assertEquals(List.of("CUSTOM", "FOREST", "TAVERN"), project.getMap().getTags());
        assertTrue(tags.knownTags().isEmpty(), "configured tags do not become library suggestions");
        DmProject addedThemes = DmProject.builder().build();
        tags.applyKnownTags(addedThemes, Path.of("shop_by_the_river_swamp.dd2vtt"));
        assertEquals(List.of("RIVER", "SHOP", "SWAMP"), addedThemes.getMap().getTags());
    }

    @Test
    void configuredAutoTagsSupplementLibraryTagsAndCanBeClearedLive() throws IOException {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.ini"));
        map("Other", "Unique", "Haunted Keep");
        settings.applyEdit("import.autoTags", " haunted keep, OASIS, haunted KEEP, , ");
        assertEquals(List.of("HAUNTED KEEP", "OASIS", "UNIQUE"), tags.importTags());
        DmProject project = DmProject.builder().build();
        tags.applyKnownTags(project, Path.of("Unique Haunted Keep Oasis Forest.dd2vtt"));
        assertEquals(List.of("HAUNTED KEEP", "OASIS", "UNIQUE"), project.getMap().getTags());

        settings.applyEdit("import.autoTags", "");
        DmProject next = DmProject.builder().build();
        tags.applyKnownTags(next, Path.of("Unique Oasis Forest.dd2vtt"));
        assertEquals(List.of("UNIQUE"), next.getMap().getTags());
        assertEquals(List.of("HAUNTED KEEP", "OASIS", "UNIQUE"), project.getMap().getTags(),
                "editing settings does not retroactively change maps");
    }

    @Test
    void whitelistAppliesToFolderBatchGroupedMapsAndAddedLevels() throws IOException {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.ini"));
        settings.applyEdit("import.autoTags", "OASIS, HAUNTED KEEP");
        BatchImportService batch = new BatchImportService(library, projectService, new Dd2vttImportService());
        BatchImportService.Result result = batch.importAll(
                List.of(dd2vtt("oasis"), dd2vtt("haunted keep_01"), dd2vtt("haunted keep_02")),
                root, sources, null);
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        Path ordinary = result.imported().stream().filter(p -> !MultiLevelService.isMultiLevelFile(p))
                .findFirst().orElseThrow();
        assertEquals(List.of("OASIS"), tags.readTags(ordinary));
        Path manifest = result.imported().stream().filter(MultiLevelService::isMultiLevelFile)
                .findFirst().orElseThrow();
        assertEquals(List.of("HAUNTED KEEP", "HAUNTED KEEP"), levelTags(manifest));

        MultiLevelManifest loaded = library.multiLevels().loadManifest(manifest);
        List<MultiLevelService.PlanItem> plan = new ArrayList<>();
        for (MultiLevelManifest.Level level : loaded.getLevels()) {
            plan.add(item(new MultiLevelService.Existing(level.getId()), level.getName()));
        }
        plan.add(item(new MultiLevelService.Dd2vtt(dd2vtt("oasis_roof")), "Roof"));
        library.multiLevels().apply(manifest, plan, null);
        assertEquals(List.of("HAUNTED KEEP", "HAUNTED KEEP", "OASIS"), levelTags(manifest));
    }

    @Test
    void matchingUsesFullTagNamesInTheFileNameIgnoringCase() {
        List<String> known = List.of("Tavern", "town", "inn", "dd2vtt", "long tag");
        assertEquals(List.of("TAVERN", "TOWN", "INN"),
                MapTagService.matchingTags(known, Path.of("Old_TAVERN_Downtown_Inn.dd2vtt")));
        assertEquals(List.of(), MapTagService.matchingTags(known, Path.of("cave.dd2vtt")));
        assertEquals(List.of("LONG TAG"), MapTagService.matchingTags(known, Path.of("a LONG TAG b.uvtt")));
    }

    @Test
    void applyKnownTagsAddsLibraryTagsFoundInTheFileName() throws IOException {
        map("Inn", "Tavern", "Forest");
        DmProject project = DmProject.builder().build();
        project.getMap().setTags(List.of("tavern"));

        tags.applyKnownTags(project, Path.of("dark_forest_tavern.dd2vtt"));

        assertEquals(List.of("TAVERN", "FOREST"), project.getMap().getTags());
    }

    @Test
    void batchImportAppliesKnownTagsToOrdinaryMapsAndAutoGroupedLevels() throws IOException {
        map("Inn", "Tavern", "Tower");
        Path single = dd2vtt("old_tavern");
        Path level0 = dd2vtt("wizard_tower_01");
        Path level1 = dd2vtt("wizard_tower_02");
        BatchImportService batch = new BatchImportService(library, projectService, new Dd2vttImportService());

        BatchImportService.Result result = batch.importAll(List.of(single, level0, level1), root, null);

        assertTrue(result.failures().isEmpty(), result.failures().toString());
        Path importedMap = result.imported().stream()
                .filter(p -> p.getFileName().toString().startsWith("old_tavern")).findFirst().orElseThrow();
        assertEquals(List.of("TAVERN"), tags.readTags(importedMap));
        boolean grouped = Tuning.IMPORT_AUTO_MERGE_MULTILEVEL.get();
        if (grouped) {
            Path manifest = result.imported().stream().filter(MultiLevelService::isMultiLevelFile).findFirst().orElseThrow();
            assertEquals(List.of("TOWER", "TOWER"), levelTags(manifest));
        } else {
            for (Path imported : result.imported()) {
                if (imported.getFileName().toString().startsWith("wizard")) {
                    assertEquals(List.of("TOWER"), tags.readTags(imported));
                }
            }
        }
    }

    @Test
    void newMultilevelLevelsFromImportsGetKnownTags() throws IOException {
        map("Inn", "tower", "Ground");
        Path manifest = tower();
        assertEquals(List.of("TOWER", "TOWER"), levelTags(manifest));

        MultiLevelManifest loaded = library.multiLevels().loadManifest(manifest);
        List<MultiLevelService.PlanItem> plan = new ArrayList<>();
        for (MultiLevelManifest.Level level : loaded.getLevels()) {
            plan.add(item(new MultiLevelService.Existing(level.getId()), level.getName()));
        }
        plan.add(item(new MultiLevelService.Dd2vtt(dd2vtt("ground_floor")), "Roof"));
        library.multiLevels().apply(manifest, plan, null);
        assertEquals(List.of("TOWER", "TOWER", "GROUND"), levelTags(manifest));
    }
}