package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;
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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelServiceTest {
    @TempDir
    Path tempDir;

    private Path root;
    private Path sources;
    private ProjectService projectService;
    private MapLibraryService library;
    private MultiLevelService service;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("library");
        sources = tempDir.resolve("sources");
        Files.createDirectories(root);
        Files.createDirectories(sources);
        projectService = new ProjectService();
        library = new MapLibraryService(root, projectService);
        service = library.multiLevels();
    }

    private Path dd2vtt(String name, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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

    private MultiLevelService.ApplyResult createTower() throws IOException {
        return service.create(root, "Tower", List.of(
                item(new MultiLevelService.Dd2vtt(dd2vtt("tower_00", 40, 20)), "Ground"),
                item(new MultiLevelService.Dd2vtt(dd2vtt("tower_01", 30, 30)), "First")), null);
    }

    private List<MultiLevelService.PlanItem> keepAll(MultiLevelManifest manifest) {
        List<MultiLevelService.PlanItem> plan = new ArrayList<>();
        for (MultiLevelManifest.Level level : manifest.getLevels()) {
            plan.add(item(new MultiLevelService.Existing(level.getId()), level.getName()));
        }
        return plan;
    }

    @Test
    void createImportsLevelsAndAppearsAsOneMapInTheLibrary() throws IOException {
        Path manifestFile = createTower().manifestFile();

        assertEquals(root.resolve("Tower").resolve("Tower.dmlevels"), manifestFile);
        MultiLevelManifest manifest = service.loadManifest(manifestFile);
        assertEquals(List.of("Ground", "First"), manifest.getLevels().stream().map(MultiLevelManifest.Level::getName).toList());
        assertNull(manifest.getCurrentLevelId());
        assertNotNull(manifest.getShared());
        for (MultiLevelManifest.Level level : manifest.getLevels()) {
            assertTrue(Files.isRegularFile(MultiLevelService.levelFile(manifestFile, level)));
        }
        assertTrue(Files.isRegularFile(manifestFile.resolveSibling(ThumbnailService.FILE_NAME)));

        MapLibraryService.Entry scanned = library.scan();
        assertEquals(1, scanned.children().size());
        MapLibraryService.Entry entry = scanned.children().get(0);
        assertTrue(entry.isMap());
        assertTrue(entry.isMultiLevel());
        assertEquals("Tower", entry.name());
        assertEquals(manifestFile, entry.mapFile());
    }

    @Test
    void failedImportCreatesNothing() throws IOException {
        Path broken = sources.resolve("broken.dd2vtt");
        Files.writeString(broken, "not json");
        assertThrows(IOException.class, () -> service.create(root, "Tower", List.of(
                item(new MultiLevelService.Dd2vtt(dd2vtt("ok", 10, 10)), "Ok"),
                item(new MultiLevelService.Dd2vtt(broken), "Broken")), null));
        assertFalse(Files.exists(root.resolve("Tower")));
    }

    @Test
    void firstOpenStartsOnLowestLevelAndSavingRemembersTheLevel() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest manifest = service.loadManifest(manifestFile);
        String ground = manifest.getLevels().get(0).getId();
        String first = manifest.getLevels().get(1).getId();

        MultiLevelService.LoadedLevel loaded = service.loadLevel(manifestFile, null);
        assertEquals(ground, loaded.level().getId());

        MultiLevelService.LoadedLevel upper = service.loadLevel(manifestFile, first);
        service.saveLevel(manifestFile, first, upper.project());

        assertEquals(first, service.loadManifest(manifestFile).getCurrentLevelId());
        assertEquals(first, service.loadLevel(manifestFile, null).level().getId());
    }

    @Test
    void sharedSettingsFollowToOtherLevelsButFogAndCameraStayPerLevel() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest manifest = service.loadManifest(manifestFile);
        String ground = manifest.getLevels().get(0).getId();
        String first = manifest.getLevels().get(1).getId();

        DmProject groundProject = service.loadLevel(manifestFile, ground).project();
        groundProject.getLighting().setTimeOfDayPreset("NIGHT");
        groundProject.getLighting().putAmbientBrightness("NIGHT", 0.3);
        groundProject.setWeather(DmProject.WeatherState.builder().type("rain").intensity(0.7).build());
        groundProject.getMap().setImageLayersLocked(false);
        groundProject.getFog().setEnabled(false);
        groundProject.getViews().setPlayerZoomStep(1.0);
        groundProject.setTextLayerVisible(false);
        groundProject.getViews().getDmCamera().setX(1234);
        new MapRotationService().rotateClockwise(groundProject);
        service.saveLevel(manifestFile, ground, groundProject);

        DmProject firstProject = service.loadLevel(manifestFile, first).project();
        assertEquals("NIGHT", firstProject.getLighting().getTimeOfDayPreset());
        assertEquals(0.3, firstProject.getLighting().ambientBrightnessFor("NIGHT"), 1e-9);
        assertEquals("rain", firstProject.getWeather().getType());
        assertEquals(0.7, firstProject.getWeather().getIntensity(), 1e-9);
        assertFalse(firstProject.getMap().imageLayersLockedOrDefault());
        assertFalse(firstProject.getFog().isEnabled());
        assertEquals(1.0, firstProject.getViews().getPlayerZoomStep(), 1e-9);
        assertFalse(firstProject.isTextLayerVisible());
        assertEquals(1, firstProject.getMap().getRotationQuarterTurns());
        // 30x30 level rotated by 90 degrees: the layer keeps its square footprint, its rotation follows the map.
        assertEquals(90, firstProject.getImageLayers().get(0).getRotationDeg(), 1e-9);
        assertTrue(firstProject.getViews().getDmCamera().getX() != 1234);
    }

    @Test
    void applyInsertsAnywhereRenamesReordersAndRemoves() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest manifest = service.loadManifest(manifestFile);
        MultiLevelManifest.Level ground = manifest.getLevels().get(0);
        MultiLevelManifest.Level first = manifest.getLevels().get(1);

        service.apply(manifestFile, List.of(
                item(new MultiLevelService.Existing(ground.getId()), "Cellar"),
                item(new MultiLevelService.Empty(), "Middle"),
                item(new MultiLevelService.Existing(first.getId()), "Top")), null);
        MultiLevelManifest changed = service.loadManifest(manifestFile);
        assertEquals(List.of("Cellar", "Middle", "Top"), changed.getLevels().stream().map(MultiLevelManifest.Level::getName).toList());
        assertEquals(ground.getId(), changed.getLevels().get(0).getId());
        assertEquals(first.getId(), changed.getLevels().get(2).getId());

        MultiLevelManifest.Level middle = changed.getLevels().get(1);
        MultiLevelService.ApplyResult result = service.apply(manifestFile, List.of(
                item(new MultiLevelService.Existing(first.getId()), "Top"),
                item(new MultiLevelService.Existing(middle.getId()), "Middle")), null);
        assertEquals(List.of(ground.getId()), result.removedLevelIds());
        assertFalse(Files.exists(manifestFile.getParent().resolve(ground.getFolder())));
        assertEquals(List.of(first.getId(), middle.getId()),
                service.loadManifest(manifestFile).getLevels().stream().map(MultiLevelManifest.Level::getId).toList());
    }

    @Test
    void removingTheCurrentLevelPicksTheNearestRemainingLevel() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest manifest = service.loadManifest(manifestFile);
        MultiLevelManifest.Level ground = manifest.getLevels().get(0);
        MultiLevelManifest.Level first = manifest.getLevels().get(1);
        service.saveLevel(manifestFile, first.getId(), service.loadLevel(manifestFile, first.getId()).project());

        service.apply(manifestFile, List.of(item(new MultiLevelService.Existing(ground.getId()), "Ground")), null);

        assertEquals(ground.getId(), service.loadManifest(manifestFile).getCurrentLevelId());
    }

    @Test
    void removingAllLevelsDeletesTheMap() throws IOException {
        Path manifestFile = createTower().manifestFile();

        MultiLevelService.ApplyResult result = service.apply(manifestFile, List.of(), null);

        assertNull(result.manifestFile());
        assertFalse(Files.exists(manifestFile.getParent()));
    }

    @Test
    void mergingLibraryMapsMovesThemIntoTheMultilevelMap() throws IOException {
        Path cellar = library.newMapFile(root, "Inn cellar");
        DmProject cellarProject = DmProject.builder().build();
        cellarProject.getLighting().setTimeOfDayPreset("DUSK");
        projectService.save(cellar, cellarProject);
        Files.createDirectories(cellar.getParent().resolve("assets"));
        Files.writeString(cellar.getParent().resolve("assets").resolve("tile.txt"), "asset");
        Path ground = library.newMapFile(root, "Inn ground floor");
        projectService.save(ground, DmProject.builder().build());

        MultiLevelService.ApplyResult result = service.create(root, "Inn", List.of(
                item(new MultiLevelService.LibraryMap(cellar.getParent(), cellar), "cellar"),
                item(new MultiLevelService.LibraryMap(ground.getParent(), ground), "ground floor")), null);

        assertFalse(Files.exists(cellar.getParent()));
        assertFalse(Files.exists(ground.getParent()));
        Path movedCellar = result.movedMaps().get(cellar.toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(movedCellar));
        assertEquals(MultiLevelService.LEVEL_FILE, movedCellar.getFileName().toString());
        assertTrue(Files.isRegularFile(movedCellar.getParent().resolve("assets").resolve("tile.txt")));
        // Shared settings start from the lowest level.
        assertEquals("DUSK", service.loadManifest(result.manifestFile()).getShared().getTimeOfDayPreset());
        assertEquals(1, library.scan().children().size());
    }

    @Test
    void unreadableLowestMapFailsTheMergeWithoutMovingAnything() throws IOException {
        Path broken = library.newMapFile(root, "Broken");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{ not json");
        Path ground = library.newMapFile(root, "Ground");
        projectService.save(ground, DmProject.builder().build());

        assertThrows(IOException.class, () -> service.create(root, "Inn", List.of(
                item(new MultiLevelService.LibraryMap(broken.getParent(), broken), "cellar"),
                item(new MultiLevelService.LibraryMap(ground.getParent(), ground), "ground")), null));

        assertTrue(Files.isRegularFile(broken));
        assertTrue(Files.isRegularFile(ground));
        assertFalse(Files.exists(root.resolve("Inn")));
    }

    @Test
    void failureAfterMovingMapsPutsThemBack() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest before = service.loadManifest(manifestFile);
        Path attic = library.newMapFile(root, "Attic");
        projectService.save(attic, DmProject.builder().build());
        // Blocks writing the manifest, the last step of the change.
        Files.createDirectories(manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp").resolve("x"));

        List<MultiLevelService.PlanItem> plan = new java.util.ArrayList<>(keepAll(before));
        plan.add(item(new MultiLevelService.LibraryMap(attic.getParent(), attic), "attic"));
        assertThrows(IOException.class, () -> service.apply(manifestFile, plan, null));

        assertTrue(Files.isRegularFile(attic));
        MultiLevelManifest after = service.loadManifest(manifestFile);
        assertEquals(before.getLevels().size(), after.getLevels().size());
        try (var dirs = Files.list(manifestFile.resolveSibling(MultiLevelService.LEVELS_DIR))) {
            assertEquals(before.getLevels().size(), dirs.count());
        }
    }

    @Test
    void multilevelMapsCannotBeMergedIntoAnotherOne() throws IOException {
        Path tower = createTower().manifestFile();
        assertThrows(IOException.class, () -> service.create(root, "Castle", List.of(
                item(new MultiLevelService.LibraryMap(tower.getParent(), tower), "Tower")), null));
        assertTrue(Files.exists(tower));
    }

    @Test
    void libraryRenameCopyAndMoveKeepTheMultilevelPackageIntact() throws IOException {
        Path manifestFile = createTower().manifestFile();
        MultiLevelManifest.Level level = service.loadManifest(manifestFile).getLevels().get(1);
        Path levelFile = MultiLevelService.levelFile(manifestFile, level);

        MapLibraryService.Entry entry = library.scan().children().get(0);
        MapLibraryService.Result renamed = library.rename(entry, "Keep");
        Path renamedManifest = root.resolve("Keep").resolve("Keep.dmlevels");
        assertEquals(renamedManifest, renamed.movedMaps().get(manifestFile));
        assertEquals(MultiLevelService.levelFile(renamedManifest, level), renamed.movedMaps().get(levelFile));
        assertTrue(Files.isRegularFile(renamedManifest));

        MapLibraryService.Entry keep = library.scan().children().get(0);
        Path copy = library.copy(keep).createdMap();
        assertEquals(root.resolve("Keep (Copy)").resolve("Keep (Copy).dmlevels"), copy);
        assertEquals(2, service.loadManifest(copy).getLevels().size());

        Path folder = library.createFolder(root, "Buildings");
        MapLibraryService.Entry toMove = library.scan().children().stream()
                .filter(e -> e.name().equals("Keep")).findFirst().orElseThrow();
        MapLibraryService.Result movedResult = library.move(toMove, folder);
        Path movedManifest = folder.resolve("Keep").resolve("Keep.dmlevels");
        assertEquals(movedManifest, movedResult.movedMaps().get(renamedManifest));
        assertTrue(Files.isRegularFile(MultiLevelService.levelFile(movedManifest, level)));
    }

    @Test
    void thumbnailShowsTheLastOpenedLevel() throws IOException {
        Path manifestFile = createTower().manifestFile();
        byte[] initial = library.loadOrCreateThumbnail(manifestFile);
        assertNotNull(initial);
        BufferedImage lowest = ImageIO.read(new java.io.ByteArrayInputStream(initial));
        assertTrue(lowest.getWidth() > lowest.getHeight(), "lowest level is 40x20");

        MultiLevelManifest.Level first = service.loadManifest(manifestFile).getLevels().get(1);
        service.saveLevel(manifestFile, first.getId(), service.loadLevel(manifestFile, first.getId()).project());

        BufferedImage current = ImageIO.read(new java.io.ByteArrayInputStream(library.loadOrCreateThumbnail(manifestFile)));
        assertEquals(current.getWidth(), current.getHeight(), "first level is 30x30");
    }

    @Test
    void naturalOrderComparesNumbersByValue() {
        List<String> names = new ArrayList<>(List.of("tower_10", "tower_2", "Tower_1", "tower"));
        names.sort(MultiLevelService.NATURAL_ORDER);
        assertEquals(List.of("tower", "Tower_1", "tower_2", "tower_10"), names);
    }

    @Test
    void suggestedNamesDropTheSharedPart() {
        List<String> house = List.of("haus der sieben waagen_00", "haus der sieben waagen_01");
        assertEquals("haus der sieben waagen", MultiLevelService.commonName(house));
        assertEquals(List.of("Level 00", "Level 01"), MultiLevelService.defaultLevelNames(house));

        List<String> tower = List.of("gottloser turm", "gottloser_turm_upper_levels_02_barracks",
                "gottloser_turm_upper_levels_10");
        assertEquals("gottloser turm", MultiLevelService.commonName(tower));
        assertEquals(List.of("Level 1", "upper levels 02 barracks", "upper levels 10"),
                MultiLevelService.defaultLevelNames(tower));

        assertEquals("Cave", MultiLevelService.commonName(List.of("Cave", "Tavern")));
        assertEquals(List.of("Cave", "Tavern"), MultiLevelService.defaultLevelNames(List.of("Cave", "Tavern")));
        assertEquals(List.of("Tavern"), MultiLevelService.defaultLevelNames(List.of("Tavern")));
    }
}
