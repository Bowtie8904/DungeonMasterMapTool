package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** No JavaFX startup: import detection and initial text sizing run on ordinary worker threads. */
class ImportedRoomLabelsTest {
    @TempDir Path directory;

    @AfterEach
    void reset() {
        Tuning.reset();
    }

    @Test
    void sharedImporterLabelsEveryEnclosedRoomWithOpenDoorsAndWindowsButNotOutsideOnWorkerThread() throws Exception {
        DmProject geometry = threeRooms();
        Path input = writeMap("rooms.uvtt", geometry);
        try (var worker = Executors.newSingleThreadExecutor()) {
            DmProject imported = worker.submit(() -> new Dd2vttImportService()
                    .importToProject(input, directory.resolve("imported"))).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("Room 01", "Room 02", "Room 03"), names(imported));
            assertEquals(30, imported.getTextBoxes().getFirst().getRuns().getFirst().getFontSize());
            assertEquals("#EEEEEE", imported.getTextBoxes().getFirst().getRuns().getFirst().getColor());
            assertEquals("#1E1E1E99", imported.getTextBoxes().getFirst().getBackgroundColor());
            assertEquals("#00000000", imported.getTextBoxes().getFirst().getBorderColor());
            assertNull(imported.getLastTextSettings());
            assertTrue(imported.getFog().getMask().copyBits().isEmpty());
            List<double[]> centers = RoomFillService.enclosedRoomCenters(imported.getFog().getMask(),
                    RoomFillService.buildBarrier(imported.getFog().getMask(), imported.getWalls(), imported.getInteractables()));
            assertEquals(3, centers.size());
            for (int i = 0; i < centers.size(); i++) {
                assertLabel(imported.getTextBoxes().get(i), centers.get(i));
            }
            assertTrue(imported.getTextBoxes().get(0).getRoomLabelCenterX()
                    < imported.getTextBoxes().get(1).getRoomLabelCenterX());
            assertTrue(imported.getTextBoxes().get(2).getRoomLabelCenterY()
                    > imported.getTextBoxes().get(1).getRoomLabelCenterY());
            var mask = imported.getFog().getMask();
            var barrier = RoomFillService.buildBarrier(mask, imported.getWalls(), imported.getInteractables());
            for (DmProject.TextBox label : imported.getTextBoxes()) {
                var room = RoomFillService.fill(mask, barrier, label.getRoomLabelCenterX(), label.getRoomLabelCenterY());
                assertFalse(room.leaked());
                assertArrayEquals(new double[]{label.getRoomLabelCenterX(), label.getRoomLabelCenterY()},
                        RoomFillService.labelPosition(mask, barrier, room, -1, -1));
            }
            ProjectService projects = new ProjectService();
            Path file = directory.resolve("imported").resolve("rooms.dmmap");
            projects.save(file, imported);
            assertEquals(imported.getTextBoxes(), projects.load(file).getTextBoxes());
            Tuning.apply(key -> "import.autoLabelRooms".equals(key) ? "false" : null);
            assertEquals(imported.getTextBoxes(), projects.load(file).getTextBoxes(),
                    "loading existing projects does not relabel them even when settings change");
        }
    }

    @Test
    void liveSettingControlsSubsequentImportsAndUsesCurrentDedicatedStyleOnly() throws Exception {
        AppSettings settings = new AppSettings(directory.resolve("settings.ini"));
        assertTrue(Tuning.IMPORT_AUTO_LABEL_ROOMS.get());
        var info = AppSettings.editableSettings().stream()
                .filter(entry -> entry.key().equals("import.autoLabelRooms")).findFirst().orElseThrow();
        assertEquals(Tuning.Kind.BOOLEAN, info.kind());
        assertEquals("Auto label rooms", info.label());
        assertFalse(info.restart());
        assertTrue(info.keywords().contains("room label"));
        ProjectService projects = new ProjectService();
        DmProject oldProject = threeRooms();
        new FogService().ensureMask(oldProject);
        Path existing = directory.resolve("old.dmmap");
        projects.save(existing, oldProject);
        assertTrue(projects.load(existing).getTextBoxes().isEmpty(), "existing maps are never auto-labelled");
        Path input = writeMap("rooms.dd2vtt", threeRooms());
        settings.applyEdit("import.autoLabelRooms", "false");
        DmProject disabled = new Dd2vttImportService().importToProject(input, directory.resolve("disabled"));
        assertTrue(disabled.getTextBoxes().isEmpty());
        assertEquals("false", new AppSettings(directory.resolve("settings.ini"))
                .get("import.autoLabelRooms", null));
        assertTrue(disabled.getFog().getMask().copyBits().isEmpty());
        settings.applyEdit("import.autoLabelRooms", "true");
        settings.applyEdit("roomLabel.fontSize", "45");
        settings.applyEdit("roomLabel.textColor", "#123456");
        settings.applyEdit("roomLabel.backgroundColor", "#01020355");
        settings.applyEdit("roomLabel.borderColor", "#04050677");
        DmProject enabled = new Dd2vttImportService().importToProject(input, directory.resolve("enabled"));
        assertEquals(3, enabled.getTextBoxes().size());
        for (DmProject.TextBox label : enabled.getTextBoxes()) {
            assertEquals(45, label.getRuns().getFirst().getFontSize());
            assertEquals("#123456", label.getRuns().getFirst().getColor());
            assertEquals("#01020355", label.getBackgroundColor());
            assertEquals("#04050677", label.getBorderColor());
            assertLabel(label, new double[]{label.getRoomLabelCenterX(), label.getRoomLabelCenterY()});
        }
        assertTrue(disabled.getTextBoxes().isEmpty());
        assertEquals("true", new AppSettings(directory.resolve("settings.ini"))
                .get("import.autoLabelRooms", null));
    }

    @Test
    void roomEnumerationExcludesOutsideAndBarrierOnlyCellsAndKeepsConcaveCenterInside() {
        DmProject project = DmProject.builder().build();
        project.getMap().getGrid().setPixelsPerCell(25);
        project.getFog().setMask(new FogMask(0, 0, 10, 30, 30));
        polygon(project, new double[][]{{30,30},{170,30},{170,70},{70,70},{70,170},{30,170},{30,30}});
        FogMask mask = project.getFog().getMask();
        BitSet before = mask.copyBits();
        BitSet barrier = RoomFillService.buildBarrier(mask, project.getWalls(), project.getInteractables());
        List<double[]> centers = RoomFillService.enclosedRoomCenters(mask, barrier);
        assertEquals(1, centers.size());
        int index = (int) (centers.getFirst()[1] / 10) * mask.getCols() + (int) (centers.getFirst()[0] / 10);
        assertFalse(barrier.get(index));
        assertTrue(RoomFillService.fill(mask, barrier, centers.getFirst()[0], centers.getFirst()[1]).cells().get(index));
        RoomLabelService.addImportedRoomLabels(project);
        assertLabel(project.getTextBoxes().getFirst(), centers.getFirst());
        assertEquals(before, mask.copyBits());
        BitSet allBlocked = new BitSet(900);
        allBlocked.set(0, 900);
        assertTrue(RoomFillService.enclosedRoomCenters(mask, allBlocked).isEmpty());
        assertTrue(RoomFillService.enclosedRoomCenters(mask, new BitSet()).isEmpty());
    }

    @Test
    void numberingIsDeterministicBeyond99AndEachComponentIsEnumeratedOnceInBoundedTime() throws Exception {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(1600).height(1600).build());
        for (int row = 0; row < 11; row++) {
            for (int col = 0; col < 10; col++) {
                rect(project, 20 + col * 140, 20 + row * 140, 140 + col * 140, 140 + row * 140);
            }
        }
        Path input = writeMap("many.dd2vtt", project);
        DmProject imported = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                new Dd2vttImportService().importToProject(input, directory.resolve("many")));
        assertEquals(110, imported.getTextBoxes().size());
        assertEquals("Room 01", names(imported).getFirst());
        assertEquals("Room 99", names(imported).get(98));
        assertEquals("Room 100", names(imported).get(99));
        assertEquals("Room 110", names(imported).getLast());
        DmProject second = new Dd2vttImportService().importToProject(input, directory.resolve("many-again"));
        for (int i = 0; i < 110; i++) {
            assertEquals(imported.getTextBoxes().get(i).getRoomLabelCenterX(),
                    second.getTextBoxes().get(i).getRoomLabelCenterX());
            assertEquals(imported.getTextBoxes().get(i).getRoomLabelCenterY(),
                    second.getTextBoxes().get(i).getRoomLabelCenterY());
        }
        assertEquals(110, names(imported).stream().distinct().count());
        assertTrue(imported.getFog().getMask().copyBits().isEmpty());
    }

    @Test
    void importSkipsNarrowAndShortByproductsWithoutNumberingGaps() throws Exception {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(900).height(600).build());
        rect(project, 20, 20, 110, 400);
        rect(project, 150, 20, 530, 110);
        rect(project, 570, 20, 660, 110);
        rect(project, 150, 150, 290, 290);
        rect(project, 330, 150, 470, 290);
        DmProject imported = new Dd2vttImportService().importToProject(
                writeMap("byproducts.dd2vtt", project), directory.resolve("byproducts"));
        assertEquals(List.of("Room 01", "Room 02"), names(imported));
        assertTrue(imported.getTextBoxes().stream().allMatch(label -> label.getRoomLabelCenterY() > 150));
        assertTrue(imported.getFog().getMask().copyBits().isEmpty());
        BitSet barrier = RoomFillService.buildBarrier(imported.getFog().getMask(),
                imported.getWalls(), imported.getInteractables());
        assertEquals(5, RoomFillService.enclosedRoomCenters(imported.getFog().getMask(), barrier).size(),
                "the size filter must not change ordinary room detection");
    }

    @Test
    void sizeMinimumIsInclusiveOnBothAxesAndUsesWorldUnits() {
        FogMask mask = new FogMask(-500, -300, 2.5, 50, 50);
        BitSet barrier = new BitSet(2500);
        barrier.set(0, 2500);
        clearInterior(barrier, 50, 2, 2, 9, 15);
        clearInterior(barrier, 50, 20, 2, 15, 9);
        clearInterior(barrier, 50, 2, 22, 10, 10);
        clearInterior(barrier, 50, 20, 22, 11, 11);
        assertEquals(4, RoomFillService.enclosedRoomCenters(mask, barrier).size());
        assertEquals(2, RoomFillService.enclosedRoomCenters(mask, barrier, 25).size(),
                "one grid square exactly is allowed, even though narrow regions have larger areas");
        assertEquals(1, RoomFillService.enclosedRoomCenters(mask, barrier, 25.1).size());
        assertEquals(4, RoomFillService.enclosedRoomCenters(mask, barrier, 20).size(),
                "use the map's grid size rather than a fixed pixel or cell-count limit");
        assertTrue(mask.copyBits().isEmpty());
    }

    private static void clearInterior(BitSet barrier, int cols, int x, int y, int width, int height) {
        for (int row = y; row < y + height; row++) {
            barrier.clear(row * cols + x, row * cols + x + width);
        }
    }

    @Test
    void thinLShapedRegionsAreSkippedButLShapedRoomsWithFullSquareInteriorQualify() throws Exception {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(600).height(600).build());
        polygon(project, new double[][]{{20,20},{220,20},{220,60},{60,60},{60,220},{20,220},{20,20}});
        polygon(project, new double[][]{{270,270},{550,270},{550,410},{410,410},{410,550},{270,550},{270,270}});
        DmProject imported = new Dd2vttImportService().importToProject(
                writeMap("l-shapes.uvtt", project), directory.resolve("l-shapes"));
        assertEquals(List.of("Room 01"), names(imported), "thin L must not consume a room number");
        assertTrue(imported.getTextBoxes().getFirst().getRoomLabelCenterX() > 270);
        assertTrue(imported.getTextBoxes().getFirst().getRoomLabelCenterY() > 270);
        BitSet barrier = RoomFillService.buildBarrier(imported.getFog().getMask(),
                imported.getWalls(), imported.getInteractables());
        assertEquals(2, RoomFillService.enclosedRoomCenters(imported.getFog().getMask(), barrier).size(),
                "manual room detection still includes the thin L");
        assertTrue(imported.getFog().getMask().copyBits().isEmpty());
    }

    @Test
    void fullSquareMustNotCrossAnInteriorHoleAndNeedNotAlignWithTileLines() {
        FogMask mask = new FogMask(-500, -300, 2.5, 40, 40);
        BitSet barrier = new BitSet(1600);
        barrier.set(0, 1600);
        clearInterior(barrier, 40, 3, 3, 10, 10);
        barrier.set(7 * 40 + 7);
        clearInterior(barrier, 40, 19, 19, 10, 10);
        assertEquals(2, RoomFillService.enclosedRoomCenters(mask, barrier).size());
        List<double[]> centers = RoomFillService.enclosedRoomCenters(mask, barrier, 25);
        assertEquals(1, centers.size(), "bounding box dimensions are not enough if the full square has a hole");
        assertTrue(centers.getFirst()[0] > -455);
        assertTrue(centers.getFirst()[1] > -255);
    }

    @Test
    void enumerationHandlesTenThousandTinyComponentsWithoutRepeatedWholeMaskScans() {
        FogMask mask = new FogMask(-500, -300, 2, 202, 202);
        BitSet barrier = new BitSet(202 * 202);
        barrier.set(0, 202 * 202);
        for (int row = 1; row < 201; row += 2) {
            for (int col = 1; col < 201; col += 2) {
                barrier.clear(row * 202 + col);
            }
        }
        List<double[]> centers = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> RoomFillService.enclosedRoomCenters(mask, barrier));
        assertEquals(10_000, centers.size());
        assertArrayEquals(new double[]{-497, -297}, centers.getFirst());
        assertArrayEquals(new double[]{-101, 99}, centers.getLast());
        assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> RoomFillService.enclosedRoomCenters(mask, barrier, 4)).isEmpty());
        assertTrue(mask.copyBits().isEmpty());
    }

    @Test
    void folderBatchAndMultilevelImportUseSameSharedAutomaticLabels() throws Exception {
        ProjectService projects = new ProjectService();
        Path libraryDir = directory.resolve("library");
        MapLibraryService library = new MapLibraryService(libraryDir, projects);
        BatchImportService batch = new BatchImportService(library, projects, new Dd2vttImportService());
        Path a = writeMap("floor_01.dd2vtt", threeRooms());
        Path b = writeMap("floor_02.uvtt", threeRooms());
        BatchImportService.Result result = batch.importAll(List.of(a, b), libraryDir, null);
        assertTrue(result.failures().isEmpty());
        assertEquals(1, result.imported().size());
        Path manifest = result.imported().getFirst();
        var levels = library.multiLevels().loadManifest(manifest).getLevels();
        assertEquals(2, levels.size());
        for (var level : levels) {
            assertEquals(List.of("Room 01", "Room 02", "Room 03"),
                    names(projects.load(MultiLevelService.levelFile(manifest, level))));
        }
        Tuning.apply(key -> "import.autoMergeMultiLevel".equals(key) ? "false" : null);
        Path folderLibrary = directory.resolve("folder-library");
        BatchImportService folderBatch = new BatchImportService(new MapLibraryService(folderLibrary, projects),
                projects, new Dd2vttImportService());
        BatchImportService.Result folder = folderBatch.importAll(List.of(a, b), folderLibrary, a.getParent(), null);
        assertTrue(folder.failures().isEmpty(), folder.failures().toString());
        assertEquals(2, folder.imported().size());
        for (Path file : folder.imported()) {
            assertEquals(List.of("Room 01", "Room 02", "Room 03"), names(projects.load(file)));
        }
    }

    @Test
    void importRunsHeadlesslyWithJavaFxCompletelyAbsentFromClasspath() throws Exception {
        Path input = writeMap("headless.dd2vtt", threeRooms());
        String classpath = Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(path -> !path.toLowerCase(java.util.Locale.ROOT).contains("javafx"))
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Djava.awt.headless=true", "-cp", classpath,
                HeadlessImport.class.getName(), input.toString(), directory.resolve("headless").toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "headless import must complete");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("3 labels without JavaFX"), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static class HeadlessImport {
        public static void main(String[] args) throws Exception {
            try {
                Class.forName("javafx.application.Platform");
                throw new AssertionError("JavaFX must not be available");
            } catch (ClassNotFoundException expected) {
                DmProject imported = new Dd2vttImportService().importToProject(Path.of(args[0]), Path.of(args[1]));
                if (imported.getTextBoxes().size() != 3) {
                    throw new AssertionError("Expected three labels");
                }
                for (var label : imported.getTextBoxes()) {
                    assertLabel(label, new double[]{label.getRoomLabelCenterX(), label.getRoomLabelCenterY()});
                }
                System.out.println("3 labels without JavaFX");
            }
        }
    }

    private DmProject threeRooms() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(500).height(500).build());
        rect(project, 30, 30, 170, 170);
        project.getWalls().removeLast();
        project.getInteractables().add(DmProject.Interactable.builder().id("door").type("door")
                .x1(30).y1(170).x2(30).y2(30).state("open").build());
        rect(project, 230, 30, 370, 170);
        project.getWalls().removeLast();
        project.getInteractables().add(DmProject.Interactable.builder().id("window").type("window")
                .x1(230).y1(170).x2(230).y2(30).state("open").build());
        polygon(project, new double[][]{{30,230},{250,230},{250,360},{160,360},{160,450},{30,450},{30,230}});
        return project;
    }

    private Path writeMap(String name, DmProject geometry) throws Exception {
        Path inputDir = directory.resolve("source");
        Files.createDirectories(inputDir);
        Path image = inputDir.resolve(name + ".png");
        DmProject.ImageLayer base = geometry.getImageLayers().getFirst();
        ImageIO.write(new BufferedImage((int) base.getWidth(), (int) base.getHeight(), BufferedImage.TYPE_INT_RGB),
                "png", image.toFile());
        List<List<Double>> walls = geometry.getWalls().stream()
                .map(w -> List.of(w.getX1() / 100, w.getY1() / 100, w.getX2() / 100, w.getY2() / 100)).toList();
        List<Map<String, Object>> portals = new ArrayList<>();
        for (var portal : geometry.getInteractables()) {
            portals.add(Map.of("id", portal.getId(), "type", portal.getType(),
                    "bounds", List.of(portal.getX1() / 100, portal.getY1() / 100, portal.getX2() / 100, portal.getY2() / 100),
                    "closed", "closed".equals(portal.getState())));
        }
        Path input = inputDir.resolve(name);
        JsonMappers.create().writeValue(input.toFile(), Map.of("image", image.getFileName().toString(),
                "pixels_per_grid", 100, "line_of_sight", walls, "portals", portals));
        return input;
    }

    private static void rect(DmProject project, double x1, double y1, double x2, double y2) {
        polygon(project, new double[][]{{x1,y1},{x2,y1},{x2,y2},{x1,y2},{x1,y1}});
    }

    private static void polygon(DmProject project, double[][] points) {
        for (int i = 1; i < points.length; i++) {
            project.getWalls().add(DmProject.WallSegment.builder().x1(points[i - 1][0]).y1(points[i - 1][1])
                    .x2(points[i][0]).y2(points[i][1]).build());
        }
    }

    private static List<String> names(DmProject project) {
        return project.getTextBoxes().stream().map(box -> box.getRuns().getFirst().getText()).toList();
    }

    private static void assertLabel(DmProject.TextBox label, double[] center) {
        assertTrue(label.isRoomLabel());
        assertTrue(label.isRoomLabelAnchored());
        assertTrue(label.isAutoSize());
        assertFalse(label.isPlayerVisible());
        label.setPlayerVisible(true);
        assertFalse(label.isPlayerVisible(), "even malformed imported visibility cannot expose labels");
        label.setPlayerVisible(false);
        assertTrue(label.getWidth() > 0);
        assertTrue(label.getHeight() > 0);
        assertEquals(center[0], label.getX() + label.getWidth() / 2, 1e-9);
        assertEquals(center[1], label.getY() + label.getHeight() / 2, 1e-9);
    }
}
