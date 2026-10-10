package dmmt;

import dmmt.api.DmControlApi;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.render.CanvasMapRenderer;
import dmmt.service.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.service.MapRotationService;
import dmmt.service.ProjectService;
import dmmt.service.RoomFillService;
import dmmt.ui.ControlVisibility;
import dmmt.ui.PlayerViewTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapBuildingToolsTest {
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void portalsDrawWithWallSnappingAndShiftOverrideAndUndoRedo() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            for (String tool : new String[]{"building.drawDoor", "building.drawWindow"}) {
                f.api.execute(tool, Map.of());
                assertTrue(((CanvasMapRenderer) get(f.app, "renderer")).isWallLayerVisible());
                f.drag(23, 28, 176, 131, false);
                DmProject.Interactable portal = f.project.getInteractables().getLast();
                assertEquals(tool.endsWith("Door") ? "door" : "window", portal.getType());
                assertEquals(0, portal.getX1());
                assertEquals(50, portal.getY1());
                assertEquals(200, portal.getX2());
                assertEquals(150, portal.getY2());
                assertEquals("closed", portal.getState());
                assertTrue(portal.isBlocksSightWhenClosed());
                assertTrue(f.project.getWalls().isEmpty());
                int count = f.project.getInteractables().size();
                f.api.execute("tools.undo", Map.of());
                assertEquals(count - 1, f.project.getInteractables().size());
                f.api.execute("tools.redo", Map.of());
                assertEquals(portal, f.project.getInteractables().getLast());
                f.drag(23, 28, 176, 131, true);
                portal = f.project.getInteractables().getLast();
                assertEquals(23, portal.getX1());
                assertEquals(28, portal.getY1());
                assertEquals(176, portal.getX2());
                assertEquals(131, portal.getY2());
                count = f.project.getInteractables().size();
                f.drag(10, 10, 10, 10, false);
                assertEquals(count, f.project.getInteractables().size(), "zero-length segments are discarded");
                invoke(f.app, "cancelActiveTool");
                f.press((portal.getX1() + portal.getX2()) / 2,
                        (portal.getY1() + portal.getY2()) / 2, MouseButton.PRIMARY, false);
                assertEquals("open", portal.getState(), "drawn portals use ordinary click-to-toggle behavior");
                f.api.execute("tools.undo", Map.of());
                assertEquals("closed", portal.getState());
            }
        });
    }

    @Test
    void typeMenuPreservesEndpointsOpenStateAndSightBehaviorAndHonorsWallLayer() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.Interactable portal = DmProject.Interactable.builder().id("p")
                    .x1(20).y1(20).x2(180).y2(120).state("open").blocksSightWhenClosed(false).build();
            f.project.getInteractables().add(portal);
            DmProject.Interactable before = new ObjectMapper().readValue(
                    new ObjectMapper().writeValueAsBytes(portal), DmProject.Interactable.class);
            invoke(f.app, "changeInteractableType", portal, "window");
            assertEquals("window", portal.getType());
            portal.setType("door");
            assertEquals(before, portal, "only the type may change");
            portal.setType("window");
            f.api.execute("tools.undo", Map.of());
            assertEquals(before, portal);
            f.api.execute("tools.redo", Map.of());
            assertEquals("window", portal.getType());
            assertEquals("open", portal.getState());
            Stage stage = new Stage();
            stage.setScene(f.canvas.getScene());
            stage.show();
            try {
                f.press(100, 70, MouseButton.SECONDARY, false);
                ContextMenu menu = (ContextMenu) get(f.app, "activeLightMenu");
                assertNotNull(menu);
                assertEquals(java.util.List.of("Door", "Window", "Delete"),
                        menu.getItems().stream().filter(item -> !(item instanceof javafx.scene.control.SeparatorMenuItem))
                                .map(javafx.scene.control.MenuItem::getText).toList());
                menu.getItems().getFirst().fire();
                assertEquals("door", portal.getType());
                assertEquals("open", portal.getState());
                f.api.execute("tools.undo", Map.of());
                assertEquals("window", portal.getType());
                menu.hide();
                set(f.app, "activeLightMenu", null);
                f.api.execute("building.wallLayer", Map.of());
                f.press(100, 70, MouseButton.SECONDARY, false);
                assertNull(get(f.app, "activeLightMenu"));
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void roomNamesUseEnclosedInteriorOrClickFallbackWithoutFogChangesAndHaveNoVisibilityOption() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            f.project.getFog().setEnabled(false);
            FogMask.Snapshot before = f.project.getFog().getMask().snapshot();
            f.api.execute("building.roomLabel", Map.of());
            f.press(60, 60, MouseButton.PRIMARY, false);
            DmProject.TextBox box = f.project.getTextBoxes().getFirst();
            assertTrue(box.isRoomLabel());
            assertTrue(box.isAutoSize());
            assertFalse(box.isPlayerVisible());
            assertTrue(box.getRoomLabelCenterX() > 80 && box.getRoomLabelCenterX() < 120);
            assertTrue(box.getRoomLabelCenterY() > 80 && box.getRoomLabelCenterY() < 120);
            assertTrue(before.sameBits(f.project.getFog().getMask().snapshot()));
            assertFalse(f.project.getFog().isEnabled());
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty(), "text creation commits when editing finishes");
            ToggleButton visibility = (ToggleButton) get(f.app, "textPlayerToggle");
            assertFalse(visibility.isVisible());
            assertFalse(visibility.isManaged());
            assertTrue(visibility.isDisabled());
            assertThrows(dmmt.api.LocalApiServer.ApiException.class,
                    () -> f.api.execute("text.players", Map.of()));
            invoke(f.app, "applySectionVisibility");
            assertFalse(visibility.isVisible());
            invoke(f.app, "commitTextEdit"); // Empty new names are discarded like ordinary text.
            assertTrue(f.project.getTextBoxes().isEmpty());
            f.project.getWalls().clear();
            f.press(55, 73, MouseButton.PRIMARY, false);
            box = f.project.getTextBoxes().getFirst();
            assertEquals(55, box.getX() + box.getWidth() / 2);
            assertEquals(73, box.getY() + box.getHeight() / 2);
            assertTrue(before.sameBits(f.project.getFog().getMask().snapshot()));
            invoke(f.app, "commitTextEdit");
        });
    }

    @Test
    void roomLabelsSurviveEditCopyPasteMovementDeleteUndoRotationAndSaveWithoutPlayerLeaks() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.TextBox box = DmProject.TextBox.builder().id("name").x(50).y(60).width(80).height(40)
                    .roomLabel(true).playerVisible(true).autoSize(true)
                    .runs(java.util.List.of(DmProject.TextRun.builder().text("Library").build())).build();
            f.project.getTextBoxes().add(box);
            set(f.app, "selectedTextId", box.getId());
            invoke(f.app, "syncTextControls", box);
            assertFalse(box.isPlayerVisible(), "roomLabel wins even over malformed saved playerVisible=true");
            invoke(f.app, "executeTextChange", "Move room name", "name",
                    (java.util.function.Consumer<DmProject.TextBox>) b -> b.setX(90));
            assertEquals(90, f.project.getTextBoxes().getFirst().getX());
            f.api.execute("tools.undo", Map.of());
            assertEquals(50, f.project.getTextBoxes().getFirst().getX());
            f.api.execute("tools.redo", Map.of());
            assertTrue((boolean) invoke(f.app, "copySelection"));
            invoke(f.app, "pasteSelection");
            assertEquals(2, f.project.getTextBoxes().size());
            DmProject.TextBox pasted = f.project.getTextBoxes().getLast();
            assertNotEquals("name", pasted.getId());
            assertTrue(pasted.isRoomLabel());
            assertFalse(pasted.isPlayerVisible());
            invoke(f.app, "deleteSelectedText");
            assertEquals(1, f.project.getTextBoxes().size());
            f.api.execute("tools.undo", Map.of());
            assertEquals(2, f.project.getTextBoxes().size());
            assertTrue(f.project.getTextBoxes().getLast().isRoomLabel());
            ProjectService service = new ProjectService();
            DmProject frozen = service.copy(f.project);
            Path mapFile = directory.resolve("labels.dmmap");
            service.save(mapFile, f.project);
            DmProject loaded = service.load(mapFile);
            assertEquals(f.project.getTextBoxes(), loaded.getTextBoxes());
            loaded.getTextBoxes().getFirst().getRuns().getFirst().setText("Secret room");
            assertFalse(PlayerViewTransition.contentChanged(frozen, mapFile, frozen.getViews().getPlayerCamera(),
                    loaded, mapFile, loaded.getViews().getPlayerCamera()));
            assertEquals("Library", frozen.getTextBoxes().getFirst().getRuns().getFirst().getText());
            new MapRotationService().rotateClockwise(loaded);
            assertTrue(loaded.getTextBoxes().stream().allMatch(b -> b.isRoomLabel() && !b.isPlayerVisible()));
            assertFalse(new ObjectMapper().readValue(
                    "{\"roomLabel\":true,\"playerVisible\":true}", DmProject.TextBox.class).isPlayerVisible());
            assertTrue(new ObjectMapper().readValue("{}", DmProject.TextBox.class).isPlayerVisible(),
                    "legacy ordinary text stays player-visible");
        });
    }

    @Test
    void mapBuildingControlsAreIndividuallyHiddenYetApiSelectableWithSpecificArtwork() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            ControlVisibility controls = (ControlVisibility) get(f.app, "dmControlVisibility");
            Set<String> ids = Set.of("building.drawDoor", "building.drawWindow", "building.roomLabel");
            controls.apply(ids);
            for (String id : ids) {
                assertFalse(controls.registeredNodes().get(id).getFirst().isVisible());
                Map<String, Object> result = f.api.execute(id, Map.of());
                assertEquals(true, result.get("value"));
                byte[] png = f.api.keyImage(id, null);
                assertEquals(0x89, Byte.toUnsignedInt(png[0]));
                assertEquals('P', png[1]);
            }
            controls.apply(Set.of());
            ids.forEach(id -> assertTrue(controls.registeredNodes().get(id).getFirst().isVisible()));
        });
    }

    @Test
    void concaveRoomPositionNeverLandsOnBarrierOrOutsideFill() {
        FogMask mask = new FogMask(0, 0, 10, 20, 20);
        BitSet barrier = new BitSet();
        BitSet cells = new BitSet();
        for (int y = 2; y < 16; y++) {
            for (int x = 2; x < 16; x++) {
                if (x < 5 || y > 12) cells.set(y * 20 + x);
            }
        }
        barrier.set(3 * 20 + 3);
        double[] point = RoomFillService.labelPosition(mask, barrier, new RoomFillService.Result(cells, false), 22, 22);
        int index = (int) (point[1] / 10) * 20 + (int) (point[0] / 10);
        assertTrue(cells.get(index));
        assertFalse(barrier.get(index));
        assertArrayEquals(new double[]{22, 22}, RoomFillService.labelPosition(
                mask, barrier, new RoomFillService.Result(cells, true), 22, 22));
    }

    @Test
    void labelFloodFillHonorsDoorsAndWindowsInBothStatesIncludingRotatedMaps() throws Exception {
        onFx(() -> {
            for (String type : new String[]{"door", "window"}) {
                for (String state : new String[]{"open", "closed"}) {
                    Fixture f = fixture();
                    enclose(f.project);
                    f.project.getWalls().removeLast();
                    f.project.getInteractables().add(DmProject.Interactable.builder().id("boundary")
                            .type(type).state(state).x1(30).y1(170).x2(30).y2(30).build());
                    f.api.execute("building.roomLabel", Map.of());
                    invoke(f.app, "placeRoomLabel", 60.0, 60.0);
                    DmProject.TextBox label = f.project.getTextBoxes().getFirst();
                    assertTrue(label.getRoomLabelCenterX() > 80 && label.getRoomLabelCenterX() < 120);
                    assertTrue(label.getRoomLabelCenterY() > 80 && label.getRoomLabelCenterY() < 120);
                    invoke(f.app, "commitTextEdit");
                    new MapRotationService().rotateClockwise(f.project);
                    FogMask.Snapshot before = f.project.getFog().getMask().snapshot();
                    invoke(f.app, "placeRoomLabel", 100.0, 100.0);
                    label = f.project.getTextBoxes().getFirst();
                    assertTrue(label.getRoomLabelCenterX() > 80 && label.getRoomLabelCenterX() < 120);
                    assertTrue(label.getRoomLabelCenterY() > 80 && label.getRoomLabelCenterY() < 120);
                    assertTrue(before.sameBits(f.project.getFog().getMask().snapshot()));
                    invoke(f.app, "commitTextEdit");
                }
            }
        });
    }

    @Test
    void committingRoomNameCreatesOneUndoableStyledTextAndNeverEditsFog() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            FogMask.Snapshot before = f.project.getFog().getMask().snapshot();
            f.api.execute("building.roomLabel", Map.of());
            f.press(60, 60, MouseButton.PRIMARY, false);
            dmmt.ui.TextBoxEditor editor = (dmmt.ui.TextBoxEditor) get(f.app, "textEditor");
            editor.node().replaceText("Library");
            editor.applyFontSize(40);
            editor.applyTextColor("#123456");
            invoke(f.app, "commitTextEdit");
            DmProject.TextBox name = f.project.getTextBoxes().getFirst();
            assertTrue(name.isRoomLabel());
            assertEquals("Library", name.getRuns().stream().map(DmProject.TextRun::getText).reduce("", String::concat));
            assertTrue(((Deque<?>) get(f.app, "undoStack")).size() == 1);
            assertTrue(before.sameBits(f.project.getFog().getMask().snapshot()));
            f.api.execute("tools.undo", Map.of());
            assertTrue(f.project.getTextBoxes().isEmpty());
            f.api.execute("tools.redo", Map.of());
            assertEquals(name, f.project.getTextBoxes().getFirst());
            invoke(f.app, "beginTextEdit", f.project.getTextBoxes().getFirst(), false);
            editor.node().replaceText("Archive");
            invoke(f.app, "commitTextEdit");
            assertEquals("Archive", f.project.getTextBoxes().getFirst().getRuns().getFirst().getText());
            f.api.execute("tools.undo", Map.of());
            assertEquals(name, f.project.getTextBoxes().getFirst());
            assertTrue(before.sameBits(f.project.getFog().getMask().snapshot()));
            f.api.execute("text.add", Map.of());
            DmProject.TextBox existing = f.project.getTextBoxes().getFirst();
            f.press(existing.getX() + 1, existing.getY() + 1, MouseButton.PRIMARY, false);
            editor.node().replaceText("Library edited through Text");
            invoke(f.app, "commitTextEdit");
            assertEquals(1, f.project.getTextBoxes().size());
            assertTrue(f.project.getTextBoxes().getFirst().isRoomLabel());
            assertFalse(f.project.getTextBoxes().getFirst().isPlayerVisible());
        });
    }

    @Test
    void roomNameRendersFullyForDmWithoutEyeButNeverForPlayerEvenWithBadVisibilityFlag() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.TextBox label = DmProject.TextBox.builder().id("label").x(50).y(50).width(100).height(60)
                    .roomLabel(true).playerVisible(true).backgroundColor("#FF0000").build();
            f.project.getTextBoxes().add(label);
            CanvasMapRenderer renderer = (CanvasMapRenderer) get(f.app, "renderer");
            Canvas canvas = new Canvas(400, 300);
            var camera = f.project.getViews().getDmCamera();
            invoke(renderer, "drawTextBoxes", canvas.getGraphicsContext2D(), f.project, 400.0, 300.0, camera, false);
            javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
            parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
            var pixels = canvas.snapshot(parameters, null).getPixelReader();
            assertEquals(javafx.scene.paint.Color.RED, pixels.getColor(100, 80),
                    "DM-only room names have neither hidden-text dimming nor the center eye badge");
            canvas.getGraphicsContext2D().clearRect(0, 0, 400, 300);
            invoke(renderer, "drawTextBoxes", canvas.getGraphicsContext2D(), f.project, 400.0, 300.0, camera, true);
            assertEquals(0, canvas.snapshot(parameters, null).getPixelReader().getColor(100, 80).getOpacity());
            label.setRoomLabel(false);
            label.setPlayerVisible(false);
            invoke(renderer, "drawTextBoxes", canvas.getGraphicsContext2D(), f.project, 400.0, 300.0, camera, false);
            assertNotEquals(javafx.scene.paint.Color.RED, canvas.snapshot(parameters, null).getPixelReader().getColor(100, 80),
                    "ordinary hidden text retains its existing dimming/badge");
        });
    }

    @Test
    void thumbnailPixelsAndDimensionsIgnoreRoomLabelsAndTheirEdits() throws Exception {
        java.awt.image.BufferedImage source = new java.awt.image.BufferedImage(40, 30,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(java.awt.Color.GREEN);
        graphics.fillRect(0, 0, 40, 30);
        graphics.dispose();
        Path image = directory.resolve("map.png");
        javax.imageio.ImageIO.write(source, "png", image.toFile());
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().path(image.toString()).width(40).height(30).build());
        dmmt.service.ThumbnailService thumbnails = new dmmt.service.ThumbnailService();
        byte[] before = thumbnails.renderPng(project, directory.resolve("map.dmmap"));
        project.getTextBoxes().add(DmProject.TextBox.builder().id("label").roomLabel(true).playerVisible(true)
                .x(-500).y(-500).width(1000).height(1000).backgroundColor("#FF0000")
                .runs(java.util.List.of(DmProject.TextRun.builder().text("Secret").build())).build());
        assertArrayEquals(before, thumbnails.renderPng(project, directory.resolve("map.dmmap")));
        project.getTextBoxes().getFirst().getRuns().getFirst().setText("Edited secret");
        assertArrayEquals(before, thumbnails.renderPng(project, directory.resolve("map.dmmap")));
    }

    @Test
    void roomLabelHoverIsBlueWithFogOffAndShiftAndUsesSameGeometryCacheWithoutChangingProject() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            f.project.getFog().setEnabled(false);
            f.api.execute("building.roomLabel", Map.of());
            String before = new ProjectService().fingerprint(f.project);
            f.move(60, 60);
            javafx.scene.paint.Color blue = previewPixel(f, 100, 100);
            assertTrue(blue.getOpacity() > 0);
            assertTrue(blue.getBlue() > blue.getRed() && blue.getBlue() > blue.getGreen());
            Object cached = get(f.app, "roomPreview");
            Object barrier = get(f.app, "roomBarrier");
            f.move(80, 80);
            assertEquals(blue, previewPixel(f, 100, 100));
            assertSame(cached, get(f.app, "roomPreview"));
            assertSame(barrier, get(f.app, "roomBarrier"));
            javafx.event.Event.fireEvent(f.canvas, new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.SHIFT,
                    true, false, false, false));
            assertEquals(blue, previewPixel(f, 100, 100));
            assertEquals(before, new ProjectService().fingerprint(f.project));
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            f.project.getWalls().removeFirst();
            assertEquals(blue, previewPixel(f, 100, 100), "leaking regions remain blue, never amber or red");
            assertNotSame(barrier, get(f.app, "roomBarrier"));
            assertNotSame(cached, get(f.app, "roomPreview"));
            assertTrue(((RoomFillService.Result) get(f.app, "roomPreview")).leaked());
        });
    }

    @Test
    void geometryPreviewsClearOnCanvasExitToolChangeEditingAndProjectSwitch() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            f.api.execute("building.roomLabel", Map.of());
            f.move(60, 60);
            assertTrue(previewPixel(f, 100, 100).getOpacity() > 0);
            f.canvas.getOnMouseExited().handle(f.mouse(MouseEvent.MOUSE_EXITED, 401, 60, MouseButton.NONE, false));
            assertNull(get(f.app, "roomPreview"));
            assertEquals(0, previewPixel(f, 100, 100).getOpacity());
            f.move(60, 60);
            previewPixel(f, 100, 100);
            f.api.execute("tools.select", Map.of());
            assertNull(get(f.app, "roomPreview"));
            assertEquals(0, previewPixel(f, 100, 100).getOpacity());
            f.api.execute("building.roomLabel", Map.of());
            assertEquals(0, previewPixel(f, 100, 100).getOpacity(), "rearming requires fresh hover");
            f.move(60, 60);
            f.press(60, 60, MouseButton.PRIMARY, false);
            assertNull(get(f.app, "roomPreview"));
            f.move(60, 60);
            assertEquals(0, previewPixel(f, 100, 100).getOpacity(), "editing suppresses hover");
            invoke(f.app, "commitTextEdit");
            f.move(60, 60);
            previewPixel(f, 100, 100);
            invoke(f.app, "switchProject", DmProject.builder().build(), null);
            assertNull(get(f.app, "roomPreview"));
            assertNull(get(f.app, "roomBarrier"));
            assertEquals(0, previewPixel(f, 100, 100).getOpacity());
        });
    }

    @Test
    void erasureHighlightsExactlySharedWallOrPortalTargetAndHonorsBadgesLayerAndZoom() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.WallSegment wall = DmProject.WallSegment.builder().x1(30).y1(80).x2(170).y2(80).build();
            DmProject.Interactable portal = DmProject.Interactable.builder().id("portal").type("window")
                    .x1(30).y1(100).x2(170).y2(100).build();
            f.project.getWalls().add(wall);
            f.project.getInteractables().add(portal);
            f.project.getLighting().getLights().add(DmProject.LightSource.builder().id("light")
                    .x(50).y(80).revealMode(DmProject.RevealMode.NONE).build());
            f.api.execute("building.eraseWall", Map.of());
            f.move(50, 80);
            assertTrue(previewPixel(f, 50, 80).getOpacity() > 0, "lights do not interfere with erasure");
            assertEquals(0, previewPixel(f, 50, 100).getOpacity());
            f.press(50, 80, MouseButton.PRIMARY, false);
            assertTrue(f.project.getWalls().isEmpty());
            assertEquals(1, f.project.getInteractables().size());
            assertEquals(0, previewPixel(f, 50, 80).getOpacity());
            f.api.execute("tools.undo", Map.of());
            assertEquals(wall, f.project.getWalls().getFirst());
            f.move(100, 87);
            assertTrue(previewPixel(f, 50, 100).getOpacity() > 0, "badge overrides nearby wall");
            assertEquals(0, previewPixel(f, 50, 80).getOpacity());
            f.press(100, 87, MouseButton.PRIMARY, false);
            assertTrue(f.project.getInteractables().isEmpty());
            assertEquals(1, f.project.getWalls().size());
            f.api.execute("tools.undo", Map.of());
            assertEquals(portal, f.project.getInteractables().getFirst());
            f.api.execute("building.wallLayer", Map.of());
            assertEquals(0, previewPixel(f, 50, 100).getOpacity());
            f.press(100, 87, MouseButton.PRIMARY, false);
            assertEquals(1, f.project.getInteractables().size());
            f.api.execute("building.wallLayer", Map.of());
            f.project.getViews().getDmCamera().setZoom(2);
            assertNull(invoke(f.app, "pickEraseTarget", 50.0, 106.0, 2.0), "line pick radius is screen-scaled");
            assertNotNull(invoke(f.app, "pickEraseTarget", 50.0, 102.0, 2.0));
            f.move(10, 10);
            assertEquals(0, previewPixel(f, 50, 100).getOpacity());
        });
    }

    @Test
    void erasingWallDoorAndWindowRefreshesPersistentRevealsAndUndoRestoresFogAndOrdering() throws Exception {
        onFx(() -> {
            for (String type : new String[]{"wall", "door", "window"}) {
                Fixture f = fixture();
                f.project.getImageLayers().add(DmProject.ImageLayer.builder().id("base").width(300).height(300).build());
                DmProject.WallSegment boundary = DmProject.WallSegment.builder()
                        .x1(150).y1(0).x2(150).y2(300).build();
                DmProject.Interactable portal = DmProject.Interactable.builder().id("boundary").type(type)
                        .x1(150).y1(0).x2(150).y2(300).build();
                if (type.equals("wall")) f.project.getWalls().add(boundary);
                else f.project.getInteractables().add(portal);
                f.project.getLighting().getLights().add(DmProject.LightSource.builder().id("persistent")
                        .x(80).y(150).range(200).revealMode(DmProject.RevealMode.PERSISTENT).build());
                dmmt.lighting.LightingEngine engine = (dmmt.lighting.LightingEngine) get(f.app, "lightingEngine");
                engine.update(f.project);
                FogMask mask = f.project.getFog().getMask();
                int behind = (int) ((150 - mask.getOriginY()) / mask.getCellSize()) * mask.getCols()
                        + (int) ((200 - mask.getOriginX()) / mask.getCellSize());
                assertFalse(mask.isRevealedCell(behind));
                FogMask.Snapshot before = mask.snapshot();
                invoke(f.app, "eraseWallAt", 150.0, 150.0, 1.0);
                assertTrue(mask.isRevealedCell(behind), type + " removal updates persistent lights immediately");
                FogMask.Snapshot after = mask.snapshot();
                assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
                f.api.execute("tools.undo", Map.of());
                engine.update(f.project);
                assertTrue(before.sameBits(mask.snapshot()));
                assertFalse(mask.isRevealedCell(behind));
                if (type.equals("wall")) assertEquals(boundary, f.project.getWalls().getFirst());
                else assertEquals(portal, f.project.getInteractables().getFirst());
                f.api.execute("tools.redo", Map.of());
                engine.update(f.project);
                assertTrue(after.sameBits(mask.snapshot()));
                Path file = directory.resolve("removed-" + type + ".dmmap");
                new ProjectService().save(file, f.project);
                DmProject loaded = new ProjectService().load(file);
                assertTrue(loaded.getWalls().isEmpty());
                assertTrue(loaded.getInteractables().isEmpty());
            }
        });
    }

    @Test
    void portalContextDeleteIsUndoablePreservesOtherObjectsAndInvalidatesRoomBoundaries() throws Exception {
        onFx(() -> {
            for (String type : new String[]{"door", "window"}) {
                Fixture f = fixture();
                enclose(f.project);
                f.project.getWalls().removeLast();
                DmProject.Interactable before = DmProject.Interactable.builder().id("before").x1(220).x2(230).build();
                DmProject.Interactable target = DmProject.Interactable.builder().id("target").type(type)
                        .x1(30).y1(170).x2(30).y2(30).state("open").build();
                DmProject.Interactable after = DmProject.Interactable.builder().id("after").x1(250).x2(260).build();
                f.project.getInteractables().addAll(java.util.List.of(before, target, after));
                assertFalse(((RoomFillService.Result) invoke(f.app, "roomAt", 60.0, 60.0)).leaked());
                Object oldBarrier = get(f.app, "roomBarrier");
                Stage stage = new Stage();
                stage.setScene(f.canvas.getScene());
                stage.show();
                try {
                    f.press(30, 100, MouseButton.SECONDARY, false);
                    ContextMenu menu = (ContextMenu) get(f.app, "activeLightMenu");
                    menu.getItems().stream().filter(item -> "Delete".equals(item.getText())).findFirst().orElseThrow().fire();
                    assertEquals(java.util.List.of(before, after), f.project.getInteractables());
                    assertTrue(((RoomFillService.Result) invoke(f.app, "roomAt", 60.0, 60.0)).leaked());
                    assertNotSame(oldBarrier, get(f.app, "roomBarrier"));
                    f.api.execute("tools.undo", Map.of());
                    assertEquals(java.util.List.of(before, target, after), f.project.getInteractables());
                    assertEquals("open", target.getState());
                    assertFalse(((RoomFillService.Result) invoke(f.app, "roomAt", 60.0, 60.0)).leaked());
                    f.api.execute("tools.redo", Map.of());
                    assertEquals(java.util.List.of(before, after), f.project.getInteractables());
                } finally {
                    stage.close();
                }
            }
        });
    }

    @Test
    void hoverPreviewsNeverAppearInPlayerRenderingOrSavedProjectAndDoNotAddUndo() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            f.project.getFog().setEnabled(false);
            DmProject.CameraState camera = f.project.getViews().getDmCamera();
            CanvasMapRenderer renderer = (CanvasMapRenderer) get(f.app, "renderer");
            Canvas player = new Canvas(400, 300);
            for (String tool : new String[]{"building.roomLabel", "building.eraseWall"}) {
                String fingerprint = new ProjectService().fingerprint(f.project);
                f.api.execute(tool, Map.of());
                f.move(tool.endsWith("roomLabel") ? 60 : 30, 60);
                assertTrue(previewPixel(f, tool.endsWith("roomLabel") ? 100 : 30, 60).getOpacity() > 0);
                renderer.render(player.getGraphicsContext2D(), f.project, null, 400, 300, camera, true, null);
                javafx.scene.SnapshotParameters transparent = new javafx.scene.SnapshotParameters();
                transparent.setFill(javafx.scene.paint.Color.TRANSPARENT);
                var pixels = player.snapshot(transparent, null).getPixelReader();
                assertEquals(0, pixels.getColor(100, 60).getOpacity());
                assertEquals(0, pixels.getColor(30, 60).getOpacity());
                assertEquals(fingerprint, new ProjectService().fingerprint(f.project));
                assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            }
        });
    }

    @Test
    void builtInWindowAndDoorLinesAndBadgesRenderIdenticalStateColors() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            dmmt.service.Tuning.reset();
            CanvasMapRenderer renderer = (CanvasMapRenderer) get(f.app, "renderer");
            Canvas canvas = new Canvas(400, 300);
            var camera = f.project.getViews().getDmCamera();
            DmProject.Interactable door = DmProject.Interactable.builder().id("door")
                    .x1(20).y1(50).x2(180).y2(50).build();
            DmProject.Interactable window = DmProject.Interactable.builder().id("window").type("window")
                    .x1(20).y1(100).x2(180).y2(100).build();
            for (String state : new String[]{"open", "closed"}) {
                door.setState(state);
                window.setState(state);
                var portals = java.util.List.of(door, window);
                canvas.getGraphicsContext2D().clearRect(0, 0, 400, 300);
                invoke(renderer, "drawInteractables", canvas.getGraphicsContext2D(), portals, 400.0, 300.0, camera);
                invoke(renderer, "drawInteractableBadges", canvas.getGraphicsContext2D(), portals,
                        400.0, 300.0, camera, null);
                javafx.scene.SnapshotParameters transparent = new javafx.scene.SnapshotParameters();
                transparent.setFill(javafx.scene.paint.Color.TRANSPARENT);
                var pixels = canvas.snapshot(transparent, null).getPixelReader();
                assertEquals(pixels.getColor(30, 50), pixels.getColor(30, 100));
                assertEquals(javafx.scene.paint.Color.web(state.equals("open") ? "#32CD32" : "#E0473C"),
                        pixels.getColor(30, 100));
                assertEquals(pixels.getColor(88, 50), pixels.getColor(88, 100));
            }
        });
    }

    @Test
    void roomStyleDefaultsAreIndependentAndTypingDeletingAndFontChangesKeepEditorCentered() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            enclose(f.project);
            DmProject.TextSettings ordinary = DmProject.TextSettings.builder().fontSize(72).textColor("#112233")
                    .backgroundColor("#AABBCC").borderColor("#445566").build();
            f.project.setLastTextSettings(ordinary);
            AppSettings preferences = (AppSettings) get(f.app, "preferences");
            preferences.putInt("text.fontSize", 72);
            preferences.put("text.textColor", "#112233");
            f.api.execute("text.add", Map.of());
            f.api.execute("building.roomLabel", Map.of());
            invoke(f.app, "placeRoomLabel", 60.0, 60.0);
            DmProject.TextBox label = f.project.getTextBoxes().getFirst();
            assertEquals("#1E1E1E99", label.getBackgroundColor());
            assertEquals("#00000000", label.getBorderColor());
            assertTrue(label.isRoomLabelAnchored());
            dmmt.ui.TextBoxEditor editor = (dmmt.ui.TextBoxEditor) get(f.app, "textEditor");
            assertEquals(30, editor.typingSize());
            assertEquals("#EEEEEE", editor.typingColor());
            double cx = label.getRoomLabelCenterX();
            double cy = label.getRoomLabelCenterY();
            for (String text : new String[]{"A", "A much longer room name", "A\nsecond line", "", "Library"}) {
                editor.node().replaceText(text);
                assertCentered(label, cx, cy);
                assertEquals(label.getX(), editor.node().getLayoutX(), 1e-9);
                assertEquals(label.getY(), editor.node().getLayoutY(), 1e-9);
            }
            editor.node().selectAll();
            invoke(f.app, "applyTextFontSize", 40);
            assertCentered(label, cx, cy);
            assertEquals(label.getX(), editor.node().getLayoutX(), 1e-9);
            assertEquals(label.getY(), editor.node().getLayoutY(), 1e-9);
            invoke(f.app, "commitTextEdit");
            assertEquals(ordinary, f.project.getLastTextSettings());
            assertEquals(72, preferences.getInt("text.fontSize", -1));
            assertEquals("#112233", preferences.get("text.textColor", null));
            invoke(f.app, "applyTextFontSize", 55);
            label = f.project.getTextBoxes().getFirst();
            assertCentered(label, cx, cy);
            invoke(f.app, "beginTextEdit", label, false);
            editor.node().replaceText("Later edited library");
            assertCentered(label, cx, cy);
            invoke(f.app, "commitTextEdit");
            f.api.execute("text.add", Map.of());
            assertEquals(72, ((javafx.scene.control.Spinner<?>) get(f.app, "textSizeSpinner")).getValue());
            assertEquals(ordinary, f.project.getLastTextSettings());
        });
    }

    @Test
    void customDefaultsApplyOnlyToNewLabelsAndSettingsProvideSearchableAlphaColorsWithoutDmControls() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            AppSettings settings = (AppSettings) get(f.app, "preferences");
            settings.applyEdit("roomLabel.fontSize", "23");
            settings.applyEdit("roomLabel.textColor", "#ABCDEF");
            settings.applyEdit("roomLabel.backgroundColor", "#01020344");
            settings.applyEdit("roomLabel.borderColor", "#05060788");
            invoke(f.app, "placeRoomLabel", 60.0, 60.0);
            dmmt.ui.TextBoxEditor editor = (dmmt.ui.TextBoxEditor) get(f.app, "textEditor");
            assertEquals(23, editor.typingSize());
            assertEquals("#ABCDEF", editor.typingColor());
            editor.node().replaceText("Custom");
            invoke(f.app, "commitTextEdit");
            DmProject.TextBox existing = f.project.getTextBoxes().getFirst();
            assertEquals("#01020344", existing.getBackgroundColor());
            assertEquals("#05060788", existing.getBorderColor());
            settings.applyEdit("roomLabel.backgroundColor", "#AA000099");
            settings.applyEdit("roomLabel.fontSize", "19");
            assertEquals("#01020344", existing.getBackgroundColor());
            assertEquals(23, existing.getRuns().getFirst().getFontSize());
            invoke(f.app, "placeRoomLabel", 200.0, 200.0);
            assertEquals(19, editor.typingSize());
            assertEquals("#AA000099", f.project.getTextBoxes().getLast().getBackgroundColor());
            invoke(f.app, "commitTextEdit");
            assertFalse(((ControlVisibility) get(f.app, "dmControlVisibility")).registeredIds().stream()
                    .anyMatch(id -> id.startsWith("roomLabel.")));
            for (int size : new int[]{1, 2000}) {
                settings.applyEdit("roomLabel.fontSize", Integer.toString(size));
                invoke(f.app, "placeRoomLabel", 200.0, 200.0);
                assertEquals(size, editor.typingSize(), "dedicated defaults are not clamped to normal Text controls");
                invoke(f.app, "commitTextEdit");
            }
        });
    }

    @Test
    void singleDragDetachesOnlyWhenMovedAndUndoRestoresAnchorWhileResizeRetainsCenter() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.TextBox box = createNamedLabel(f, 100, 100);
            f.api.execute("tools.select", Map.of());
            f.press(100, 100, MouseButton.PRIMARY, false);
            f.canvas.getOnMouseReleased().handle(f.mouse(MouseEvent.MOUSE_RELEASED, 100, 100, MouseButton.PRIMARY, false));
            assertTrue(box.isRoomLabelAnchored());
            f.drag(100, 100, 130, 120, false);
            assertFalse(box.isRoomLabelAnchored());
            double x = box.getX();
            double y = box.getY();
            invoke(f.app, "beginTextEdit", box, false);
            ((dmmt.ui.TextBoxEditor) get(f.app, "textEditor")).node().replaceText("Detached much longer label");
            assertEquals(x, box.getX());
            assertEquals(y, box.getY());
            invoke(f.app, "commitTextEdit");
            f.api.execute("tools.undo", Map.of()); // Edit.
            f.api.execute("tools.undo", Map.of()); // Move.
            box = f.project.getTextBoxes().getFirst();
            assertTrue(box.isRoomLabelAnchored());
            assertCentered(box, 100, 100);
            f.api.execute("tools.redo", Map.of());
            assertFalse(f.project.getTextBoxes().getFirst().isRoomLabelAnchored());
            f.api.execute("tools.undo", Map.of());
            box = f.project.getTextBoxes().getFirst();
            DmProject.TextBox original = (DmProject.TextBox) invoke(f.app, "cloneText", box);
            invoke(f.app, "resizeText", box, original, 4, 190.0, 160.0);
            assertTrue(box.isRoomLabelAnchored());
            assertFalse(box.isAutoSize());
            assertCentered(box, 100, 100);
            invoke(f.app, "setTextAutoSize", true);
            assertCentered(f.project.getTextBoxes().getFirst(), 100, 100);
        });
    }

    @Test
    void groupMovesAndActualArrowNudgesCaptureDetachmentAndUndoRedoIncludingRoundTripGesture() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            DmProject.TextBox box = createNamedLabel(f, 100, 100);
            f.api.execute("tools.select", Map.of());
            @SuppressWarnings("unchecked")
            Set<String> keys = (Set<String>) get(f.app, "groupKeys");
            keys.add("text:" + box.getId());
            f.project.getTextBoxes().add(DmProject.TextBox.builder().id("other").x(200).y(150).width(80).height(40).build());
            keys.add("text:other");
            invoke(f.app, "pressGroupSelection", 100.0, 100.0, 1.0, false);
            invoke(f.app, "moveGroupBy", 0.0, 0.0);
            assertTrue(box.isRoomLabelAnchored());
            invoke(f.app, "moveGroupBy", 20.0, 10.0);
            assertFalse(box.isRoomLabelAnchored());
            invoke(f.app, "finishGroupDrag");
            f.api.execute("tools.undo", Map.of());
            assertTrue(box.isRoomLabelAnchored());
            assertCentered(box, 100, 100);
            assertEquals(200, f.project.getTextBoxes().getLast().getX());
            f.api.execute("tools.redo", Map.of());
            assertFalse(box.isRoomLabelAnchored());
            f.api.execute("tools.undo", Map.of());
            invoke(f.app, "clearGroup");
            set(f.app, "selectedTextId", box.getId());
            f.canvas.requestFocus();
            for (javafx.scene.input.KeyCode code : new javafx.scene.input.KeyCode[]{
                    javafx.scene.input.KeyCode.RIGHT, javafx.scene.input.KeyCode.LEFT}) {
                javafx.event.Event.fireEvent(f.canvas, new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            }
            assertFalse(box.isRoomLabelAnchored(), "translation detaches even if gesture returns to initial position");
            assertCentered(box, 100, 100);
            invoke(f.app, "finishNudge");
            f.api.execute("tools.undo", Map.of());
            assertTrue(box.isRoomLabelAnchored());
            f.api.execute("tools.redo", Map.of());
            assertFalse(box.isRoomLabelAnchored());
        });
    }

    @Test
    void anchorPersistsRotatesCopiesThroughHistoryAndPastesDetachedWhileLegacyLabelsKeepPlacement() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.project.getImageLayers().add(DmProject.ImageLayer.builder().width(300).height(200).build());
            DmProject.TextBox label = createNamedLabel(f, 80, 60);
            ProjectService service = new ProjectService();
            Path file = directory.resolve("anchored.dmmap");
            service.save(file, f.project);
            DmProject loaded = service.load(file);
            assertEquals(label, loaded.getTextBoxes().getFirst());
            new MapRotationService().rotateClockwise(loaded);
            DmProject.TextBox rotated = loaded.getTextBoxes().getFirst();
            assertCentered(rotated, 190, 30);
            assertTrue(rotated.isRoomLabelAnchored());
            rotated.getRuns().getFirst().setText("Longer rotated room name");
            ((CanvasMapRenderer) get(f.app, "renderer")).fitTextBox(rotated, 1200, 18);
            assertCentered(rotated, 190, 30);
            set(f.app, "selectedTextId", label.getId());
            assertTrue((boolean) invoke(f.app, "copySelection"));
            invoke(f.app, "pasteSelection");
            DmProject.TextBox pasted = f.project.getTextBoxes().getLast();
            assertFalse(pasted.isRoomLabelAnchored());
            assertTrue(pasted.isRoomLabel());
            assertFalse(pasted.isPlayerVisible());
            f.api.execute("tools.undo", Map.of());
            f.api.execute("tools.redo", Map.of());
            assertFalse(f.project.getTextBoxes().getLast().isRoomLabelAnchored());
            DmProject.TextBox legacy = new ObjectMapper().readValue(
                    "{\"roomLabel\":true,\"x\":42,\"y\":73,\"width\":90,\"height\":40}", DmProject.TextBox.class);
            assertFalse(legacy.isRoomLabelAnchored());
            ((CanvasMapRenderer) get(f.app, "renderer")).fitTextBox(legacy, 1200, 18);
            assertEquals(42, legacy.getX());
            assertEquals(73, legacy.getY());
            DmProject frozen = service.copy(f.project);
            label.setRoomLabelCenterX(150);
            label.recenterRoomLabel();
            assertFalse(PlayerViewTransition.contentChanged(frozen, file, frozen.getViews().getPlayerCamera(),
                    f.project, file, f.project.getViews().getPlayerCamera()));
        });
    }

    private static DmProject.TextBox createNamedLabel(Fixture f, double x, double y) throws Exception {
        invoke(f.app, "placeRoomLabel", x, y);
        ((dmmt.ui.TextBoxEditor) get(f.app, "textEditor")).node().replaceText("Room");
        invoke(f.app, "commitTextEdit");
        return f.project.getTextBoxes().getLast();
    }

    private static void assertCentered(DmProject.TextBox box, double x, double y) {
        assertEquals(x, box.getX() + box.getWidth() / 2, 1e-9);
        assertEquals(y, box.getY() + box.getHeight() / 2, 1e-9);
        assertEquals(x, box.getRoomLabelCenterX(), 1e-9);
        assertEquals(y, box.getRoomLabelCenterY(), 1e-9);
    }

    private static javafx.scene.paint.Color previewPixel(Fixture f, int x, int y) throws Exception {
        Canvas canvas = (Canvas) get(f.app, "dmFogCanvas");
        canvas.getGraphicsContext2D().clearRect(0, 0, 400, 300);
        invoke(f.app, "drawToolPreview", canvas.getGraphicsContext2D());
        javafx.scene.SnapshotParameters transparent = new javafx.scene.SnapshotParameters();
        transparent.setFill(javafx.scene.paint.Color.TRANSPARENT);
        return canvas.snapshot(transparent, null).getPixelReader().getColor(x, y);
    }

    private Fixture fixture() throws Exception {
        String prior = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        System.setProperty(AppSettings.SYSTEM_PROPERTY, directory.resolve("settings.ini").toString());
        try {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            DmProject project = DmProject.builder().build();
            project.getFog().setMask(new FogMask(0, 0, 10, 30, 30));
            project.getViews().getDmCamera().setX(200);
            project.getViews().getDmCamera().setY(150);
            set(app, "project", project);
            Canvas canvas = new Canvas(400, 300);
            set(app, "dmCanvas", canvas);
            for (String field : new String[]{"dmBaseCanvas", "dmBrightCoreCanvas", "dmAmbientCanvas", "dmFogCanvas"}) {
                set(app, field, new Canvas(400, 300));
            }
            Stage owner = new Stage();
            javafx.scene.Node panel = (javafx.scene.Node) invoke(app, "createControlsPanel", owner);
            owner.close();
            set(app, "textEditor", new dmmt.ui.TextBoxEditor());
            javafx.scene.Node editor = ((dmmt.ui.TextBoxEditor) get(app, "textEditor")).node();
            new Scene(new Pane(canvas, panel, editor), 400, 300);
            invoke(app, "initTextEditor");
            invoke(app, "installDmInteractions");
            DmControlApi api = new DmControlApi((ControlVisibility) get(app, "dmControlVisibility"));
            set(app, "controlApi", api);
            return new Fixture(app, project, canvas, api);
        } finally {
            if (prior == null) System.clearProperty(AppSettings.SYSTEM_PROPERTY);
            else System.setProperty(AppSettings.SYSTEM_PROPERTY, prior);
        }
    }

    private static void enclose(DmProject project) {
        for (double[] edge : new double[][]{{30, 30, 170, 30}, {170, 30, 170, 170},
                {170, 170, 30, 170}, {30, 170, 30, 30}}) {
            project.getWalls().add(DmProject.WallSegment.builder()
                    .x1(edge[0]).y1(edge[1]).x2(edge[2]).y2(edge[3]).build());
        }
    }

    private record Fixture(DungeonMasterMapToolApplication app, DmProject project, Canvas canvas, DmControlApi api) {
        void move(double x, double y) {
            canvas.getOnMouseMoved().handle(mouse(MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE, false));
        }
        void press(double x, double y, MouseButton button, boolean shift) {
            canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, x, y, button, shift));
        }

        void drag(double x1, double y1, double x2, double y2, boolean shift) {
            press(x1, y1, MouseButton.PRIMARY, shift);
            canvas.getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, x2, y2, MouseButton.PRIMARY, shift));
            canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, x2, y2, MouseButton.PRIMARY, shift));
        }

        MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y, MouseButton button, boolean shift) {
            return new MouseEvent(type, x, y, x, y, button, 1, shift, false, false, false,
                    button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY,
                    false, false, true, new PickResult(canvas, x, y));
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == arguments.length).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void onFx(CheckedAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction { void run() throws Exception; }
}
