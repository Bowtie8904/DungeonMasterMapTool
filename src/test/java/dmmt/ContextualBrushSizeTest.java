package dmmt;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.lighting.LightingEngine;
import dmmt.render.HistoryFeedback;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.input.PickResult;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ContextualBrushSizeTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void supportedToolsShareExactSizeWithBothSlidersAndPreviewWithoutEditing() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.project.getOverlays().add(DmProject.OverlayShape.builder().id("existing").strokeWidth(75).build());
            DmProject.CameraState dmBefore = copy(f.project.getViews().getDmCamera());
            DmProject.CameraState playerBefore = copy(f.project.getViews().getPlayerCamera());
            DmProject.FogState fogBefore = f.project.getFog();
            for (String tool : new String[]{"REVEAL_BRUSH", "HIDE_BRUSH", "AOE_BRUSH", "AOE_LINE"}) {
                f.tool(tool);
                f.size.set(2.5);
                assertTrue(f.scroll(40, false, true).isConsumed());
                assertEquals(2.6, f.size.get(), 1e-9);
                assertEquals(2.6, f.fogSlider.getValue(), 1e-9);
                assertEquals(2.6, f.effectSlider.getValue(), 1e-9);
                assertEquals(130.0, (double) invoke(f.app, "brushRadiusWorld"), 1e-9);
                assertEquals("Brush: 2.6 tiles", f.label.getText());
                assertTrue(f.label.isVisible());
                f.scroll(-40, false, true);
                assertEquals(2.5, f.size.get(), 1e-9);
            }
            assertEquals(dmBefore, f.project.getViews().getDmCamera());
            assertEquals(playerBefore, f.project.getViews().getPlayerCamera());
            assertSame(fogBefore, f.project.getFog());
            assertNull(f.project.getFog().getMask());
            assertEquals(1, f.project.getOverlays().size());
            assertEquals(75, f.project.getOverlays().getFirst().getStrokeWidth());
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            assertTrue(((Deque<?>) get(f.app, "redoStack")).isEmpty());
        });
    }

    @Test
    void respectsConfiguredLimitsAndIgnoresUnsupportedToolsAndStrokes() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            Tuning.apply(key -> switch (key) {
                case "brush.minTiles" -> "0.35";
                case "brush.maxTiles" -> "3.25";
                default -> null;
            });
            f.tool("REVEAL_BRUSH");
            f.size.set(0.35);
            f.scroll(-40, false, true);
            assertEquals(0.35, f.size.get(), 1e-9);
            f.scroll(40, false, true);
            assertEquals(0.45, f.size.get(), 1e-9);
            f.size.set(3.25);
            f.scroll(40, false, true);
            assertEquals(3.25, f.size.get(), 1e-9);
            invoke(f.app, "hideBrushSizeLabel");
            double zoom = f.project.getViews().getDmCamera().getZoom();
            for (String tool : new String[]{"SELECT", "AOE_CIRCLE", "AOE_RECT", "AOE_PEN", "REVEAL_RECT",
                    "HIDE_RECT", "REVEAL_ROOM", "TEXT", "WALL_DRAW", "WALL_ERASE", "LIGHT_ADD"}) {
                f.tool(tool);
                assertTrue(f.scroll(-40, false, true).isConsumed());
                assertEquals(3.25, f.size.get(), 1e-9);
                assertFalse(f.label.isVisible());
                assertEquals(zoom, f.project.getViews().getDmCamera().getZoom());
            }
            f.tool("HIDE_BRUSH");
            set(f.app, "fogDragging", true);
            f.scroll(-40, false, true);
            assertEquals(3.25, f.size.get(), 1e-9);
            set(f.app, "fogDragging", false);
            for (String tool : new String[]{"AOE_BRUSH", "AOE_LINE"}) {
                f.tool(tool);
                invoke(f.app, "beginOverlayDraw", new Class<?>[]{double.class, double.class}, 0.0, 0.0);
                DmProject.OverlayShape draft = (DmProject.OverlayShape) get(f.app, "draftOverlay");
                f.scroll(-40, false, true);
                assertEquals(3.25, f.size.get(), 1e-9);
                assertEquals(325, draft.getStrokeWidth(), 1e-9);
                assertFalse(f.label.isVisible());
                set(f.app, "draftOverlay", null);
            }
        });
    }

    @Test
    void selectedLightRadiusChangesByOneGridTileWithoutMovingCamerasOrChangingBrushSize() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("SELECT");
            f.project.getMap().getGrid().setPixelsPerCell(75);
            var light = DmProject.LightSource.builder().id("selected").range(187.5).build();
            var other = DmProject.LightSource.builder().id("other").range(300).build();
            f.project.getLighting().getLights().addAll(java.util.List.of(light, other));
            set(f.app, "selectedLight", light);
            var dmBefore = copy(f.project.getViews().getDmCamera());
            var playerBefore = copy(f.project.getViews().getPlayerCamera());
            double brushBefore = f.size.get();
            assertTrue(f.scroll(40, false, true).isConsumed());
            assertEquals(262.5, light.getRange());
            assertEquals("Light radius: 3.5 tiles", f.label.getText());
            assertTrue(f.label.isVisible());
            f.scroll(-40, false, true);
            assertEquals(187.5, light.getRange());
            f.scroll(-40, false, true);
            assertEquals(112.5, light.getRange());
            f.scroll(-40, false, true);
            assertEquals(75, light.getRange());
            int historySize = ((Deque<?>) get(f.app, "undoStack")).size();
            f.scroll(-40, false, true);
            assertEquals(75, light.getRange());
            assertEquals(historySize, ((Deque<?>) get(f.app, "undoStack")).size());
            light.setRange(16 * 75);
            f.scroll(40, false, true);
            assertEquals(17 * 75, light.getRange());
            assertEquals(300, other.getRange());
            assertEquals(dmBefore, f.project.getViews().getDmCamera());
            assertEquals(playerBefore, f.project.getViews().getPlayerCamera());
            assertEquals(brushBefore, f.size.get());
            invoke(f.app, "undo");
            assertEquals(16 * 75, light.getRange());
            invoke(f.app, "redo");
            assertEquals(17 * 75, light.getRange());
        });
    }

    @Test
    void multipleLightsChangeIndependentlyAsOneUndoStepAndMixedSelectionIsPreserved() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("SELECT");
            f.project.getMap().getGrid().setPixelsPerCell(75);
            var small = DmProject.LightSource.builder().id("small").range(75).build();
            var large = DmProject.LightSource.builder().id("large").range(337.5).build();
            var other = DmProject.LightSource.builder().id("other").range(600).build();
            f.project.getLighting().getLights().addAll(java.util.List.of(small, large, other));
            var layer = DmProject.ImageLayer.builder().id("image").width(100).height(100).build();
            f.project.getImageLayers().add(layer);
            for (var light : java.util.List.of(small, large)) {
                set(f.app, "selectedLight", light);
                invoke(f.app, "seedGroupFromSingleSelection");
            }
            set(f.app, "selectedLayer", layer);
            invoke(f.app, "seedGroupFromSingleSelection");
            invoke(f.app, "clearSingleSelection");
            var selection = java.util.Set.copyOf((java.util.Set<?>) get(f.app, "groupKeys"));
            var dmBefore = copy(f.project.getViews().getDmCamera());
            var playerBefore = copy(f.project.getViews().getPlayerCamera());
            double brushBefore = f.size.get();
            f.scroll(-40, false, true);
            assertEquals(75, small.getRange());
            assertEquals(262.5, large.getRange());
            assertEquals("Light radii: 1.0-3.5 tiles (2 lights)", f.label.getText());
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            invoke(f.app, "undo");
            assertEquals(75, small.getRange());
            assertEquals(337.5, large.getRange());
            invoke(f.app, "redo");
            assertEquals(75, small.getRange());
            assertEquals(262.5, large.getRange());
            f.scroll(40, false, true);
            assertEquals(150, small.getRange());
            assertEquals(337.5, large.getRange());
            assertEquals(2, ((Deque<?>) get(f.app, "undoStack")).size());
            invoke(f.app, "undo");
            assertEquals(75, small.getRange());
            assertEquals(262.5, large.getRange());
            assertEquals(600, other.getRange());
            assertEquals(100, layer.getWidth());
            assertEquals(0, layer.getX());
            assertEquals(selection, get(f.app, "groupKeys"));
            assertEquals(dmBefore, f.project.getViews().getDmCamera());
            assertEquals(playerBefore, f.project.getViews().getPlayerCamera());
            assertEquals(brushBefore, f.size.get());
            set(f.app, "draggingGroup", true);
            int historySize = ((Deque<?>) get(f.app, "undoStack")).size();
            f.scroll(40, false, true);
            assertEquals(75, small.getRange());
            assertEquals(262.5, large.getRange());
            assertEquals(historySize, ((Deque<?>) get(f.app, "undoStack")).size());
        });
    }

    @Test
    void lightWheelIgnoresGesturesAndOtherToolsAndRetainsZoomPrecedence() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("SELECT");
            var light = DmProject.LightSource.builder().id("selected").range(300).build();
            f.project.getLighting().getLights().add(light);
            set(f.app, "selectedLight", light);
            for (String flag : new String[]{"draggingLight", "draggingGroup", "marqueeActive", "fogDragging",
                    "panningDmCamera", "pingArmed", "laserActive", "laserToolActive"}) {
                set(f.app, flag, true);
                assertTrue(f.scroll(40, false, true).isConsumed());
                assertEquals(300, light.getRange());
                assertFalse(f.label.isVisible());
                set(f.app, flag, false);
            }
            f.scroll(0, false, true);
            f.tool("LIGHT_ADD");
            f.scroll(40, false, true);
            assertEquals(300, light.getRange());
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            f.tool("SELECT");
            Slider playerZoom = new Slider(-2, 2, 0);
            set(f.app, "playerZoomSlider", playerZoom);
            f.scroll(40, true, true);
            assertTrue(playerZoom.getValue() > 0);
            assertEquals(300, light.getRange());
            assertFalse(f.label.isVisible());
            double zoom = f.project.getViews().getDmCamera().getZoom();
            f.scroll(40, false, false);
            assertTrue(f.project.getViews().getDmCamera().getZoom() > zoom);
            assertEquals(300, light.getRange());
            f.project.getLighting().getLights().clear();
            f.scroll(40, false, true);
            assertEquals(300, light.getRange());
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
        });
    }

    @Test
    void lightRadiusUndoRedoRestoresPersistentFogRevealsAndLeavesFrozenMapUnchanged() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("SELECT");
            f.project.getMap().getGrid().setPixelsPerCell(50);
            f.project.getImageLayers().add(DmProject.ImageLayer.builder()
                    .id("base").x(-300).y(-300).width(600).height(600).build());
            f.project.getFog().setEnabled(true);
            var light = DmProject.LightSource.builder().id("selected").x(0).y(0).range(100)
                    .revealMode(DmProject.RevealMode.PERSISTENT).build();
            var second = DmProject.LightSource.builder().id("second").x(200).y(0).range(50)
                    .revealMode(DmProject.RevealMode.PERSISTENT).build();
            f.project.getLighting().getLights().addAll(java.util.List.of(light, second));
            set(f.app, "selectedLight", light);
            invoke(f.app, "seedGroupFromSingleSelection");
            set(f.app, "selectedLight", second);
            invoke(f.app, "seedGroupFromSingleSelection");
            invoke(f.app, "clearSingleSelection");
            LightingEngine engine = (LightingEngine) get(f.app, "lightingEngine");
            engine.update(f.project);
            FogMask mask = f.project.getFog().getMask();
            var before = mask.copyBits();
            DmProject frozen = DmProject.builder().build();
            var frozenLight = DmProject.LightSource.builder().id("selected").range(100).build();
            frozen.getLighting().getLights().add(frozenLight);
            set(f.app, "frozenPlayerProject", frozen);
            f.scroll(40, false, true);
            var after = mask.copyBits();
            assertTrue(after.cardinality() > before.cardinality());
            assertEquals(150, light.getRange());
            assertEquals(100, second.getRange());
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            invoke(f.app, "undo");
            assertEquals(100, light.getRange());
            assertEquals(50, second.getRange());
            assertEquals(before, mask.copyBits());
            invoke(f.app, "redo");
            assertEquals(150, light.getRange());
            assertEquals(100, second.getRange());
            assertEquals(after, mask.copyBits());
            assertSame(frozen, get(f.app, "frozenPlayerProject"));
            assertEquals(100, frozenLight.getRange());
        });
    }

    @Test
    void ctrlTakesPrecedenceAndPlainWheelStillZooms() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("AOE_LINE");
            Slider playerZoom = new Slider(-2, 2, 0);
            set(f.app, "playerZoomSlider", playerZoom);
            double size = f.size.get();
            double zoom = f.project.getViews().getDmCamera().getZoom();
            f.scroll(40, true, true);
            assertTrue(playerZoom.getValue() > 0);
            assertEquals(size, f.size.get());
            assertEquals(zoom, f.project.getViews().getDmCamera().getZoom());
            assertFalse(f.label.isVisible());
            f.scroll(40, false, false);
            assertTrue(f.project.getViews().getDmCamera().getZoom() > zoom);
            assertEquals(size, f.size.get());
        });
    }

    @Test
    void feedbackIsDmOnlyMouseTransparentBoundedAndTemporaryAndOtherNodesAreExcluded() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("AOE_BRUSH");
            f.root.applyCss();
            f.scroll(40, false, true);
            assertTrue(f.label.isMouseTransparent());
            assertFalse(f.label.isManaged());
            assertTrue(f.label.getLayoutX() >= 0 && f.label.getLayoutY() >= 0);
            assertTrue(f.label.getBoundsInParent().getMaxX() <= f.canvas.getWidth());
            assertTrue(f.label.getBoundsInParent().getMaxY() <= f.canvas.getHeight());
            Pane playerRoot = new Pane(new Canvas(400, 300));
            assertFalse(playerRoot.getChildren().contains(f.label));
            PauseTransition timeout = (PauseTransition) get(f.app, "brushSizeLabelTimeout");
            assertEquals(900, timeout.getDuration().toMillis());
            timeout.getOnFinished().handle(new ActionEvent());
            assertFalse(f.label.isVisible());
            double size = f.size.get();
            double zoom = f.project.getViews().getDmCamera().getZoom();
            Node sidebar = new Label("Sidebar");
            Node editor = new TextArea();
            f.root.getChildren().addAll(sidebar, editor);
            sidebar.fireEvent(wheel(sidebar, 40, false, true));
            editor.fireEvent(wheel(editor, 40, false, true));
            assertEquals(size, f.size.get());
            assertEquals(zoom, f.project.getViews().getDmCamera().getZoom());
            assertFalse(f.label.isVisible());
            f.scroll(0, false, true);
            assertFalse(f.label.isVisible());
        });
    }

    @Test
    void undoRedoFeedbackPreservesHistorySelectionAndFrozenDataAndUsesResultingScreenBounds() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            Label status = new Label();
            set(f.app, "statusLabel", status);
            var layer = DmProject.ImageLayer.builder().id("image").width(20).height(20).build();
            f.project.getImageLayers().add(layer);
            set(f.app, "selectedLayer", layer);
            DmProject frozen = DmProject.builder().build();
            frozen.getImageLayers().add(DmProject.ImageLayer.builder().id("frozen").x(-40).width(20).height(20).build());
            set(f.app, "frozenPlayerProject", frozen);
            invoke(f.app, "executeWithHistory", new Class<?>[]{String.class, Runnable.class, Runnable.class},
                    "Move layer", (Runnable) () -> layer.setX(100), (Runnable) () -> layer.setX(0));
            HistoryFeedback feedback = (HistoryFeedback) get(f.app, "historyFeedback");
            assertFalse(feedback.active(System.nanoTime()));
            invoke(f.app, "undo");
            assertEquals("Undid: Move layer", status.getText());
            assertEquals(0, layer.getX());
            assertEquals(1, ((Deque<?>) get(f.app, "redoStack")).size());
            invoke(f.app, "redo");
            assertEquals("Redid: Move layer", status.getText());
            assertEquals(100, layer.getX());
            assertSame(layer, get(f.app, "selectedLayer"));
            assertSame(frozen, get(f.app, "frozenPlayerProject"));
            assertEquals(-40, frozen.getImageLayers().getFirst().getX());
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            assertTrue(((Deque<?>) get(f.app, "redoStack")).isEmpty());

            Canvas canvas = new Canvas(400, 300);
            feedback.draw(canvas.getGraphicsContext2D(), 400, 300, f.project.getViews().getDmCamera(), System.nanoTime());
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            var pixels = canvas.snapshot(parameters, null).getPixelReader();
            assertTrue(pixels.getColor(310, 150).getOpacity() > 0.1);
            assertEquals(0, pixels.getColor(210, 150).getOpacity());
            assertEquals(0, pixels.getColor(399, 299).getOpacity());
            assertEquals(0, f.project.getViews().getDmCamera().getX());
            assertEquals(1, f.project.getViews().getDmCamera().getZoom());
            invoke(f.app, "redo"); // An empty redo still replaces the old feedback.
            assertEquals("Nothing to redo.", status.getText());
            assertFalse(feedback.active(System.nanoTime()));
        });
    }

    @Test
    void fogFeedbackDrawsRegionEdgesNotInternalCellsOrMapBoundsAndClipsOffscreenTargets() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            var mask = new FogMask(-200, -150, 10, 40, 30);
            f.project.getFog().setMask(mask);
            var before = HistoryFeedback.capture(f.project, true);
            mask.applyCells(new int[]{5 * 40 + 5, 5 * 40 + 6, 6 * 40 + 5}, true);
            HistoryFeedback feedback = new HistoryFeedback();
            long now = System.nanoTime();
            feedback.show(before, HistoryFeedback.capture(f.project, true), now);
            Canvas canvas = new Canvas(400, 300);
            feedback.draw(canvas.getGraphicsContext2D(), 400, 300, f.project.getViews().getDmCamera(), now);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            var pixels = canvas.snapshot(parameters, null).getPixelReader();
            assertTrue(pixels.getColor(55, 50).getOpacity() > 0.1); // Top boundary.
            assertTrue(pixels.getColor(60, 65).getOpacity() > 0.1); // Concave boundary of the L.
            assertEquals(0, pixels.getColor(60, 55).getOpacity()); // No internal cell edge.
            assertEquals(0, pixels.getColor(200, 150).getOpacity());
            assertEquals(0, pixels.getColor(0, 0).getOpacity()); // No whole-map rectangle.

            before = HistoryFeedback.capture(f.project, true);
            f.project.getLighting().getLights().add(DmProject.LightSource.builder().id("offscreen").x(10000).build());
            feedback.show(before, HistoryFeedback.capture(f.project, true), now);
            canvas.getGraphicsContext2D().clearRect(0, 0, 400, 300);
            feedback.draw(canvas.getGraphicsContext2D(), 400, 300, f.project.getViews().getDmCamera(), now);
            pixels = canvas.snapshot(parameters, null).getPixelReader();
            for (int y = 0; y < 300; y++) {
                for (int x = 0; x < 400; x++) {
                    assertEquals(0, pixels.getColor(x, y).getOpacity());
                }
            }
        });
    }

    private Fixture fixture() throws Exception {
        System.setProperty(AppSettings.SYSTEM_PROPERTY, temp.resolve("settings.ini").toString());
        DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
        DmProject project = DmProject.builder().build();
        set(app, "project", project);
        Canvas canvas = new Canvas(400, 300);
        set(app, "dmCanvas", canvas);
        set(app, "dmBaseCanvas", new Canvas(400, 300));
        set(app, "dmBrightCoreCanvas", new Canvas(400, 300));
        set(app, "dmAmbientCanvas", new Canvas(400, 300));
        Canvas fogCanvas = new Canvas(400, 300);
        fogCanvas.setMouseTransparent(true);
        set(app, "dmFogCanvas", fogCanvas);
        Label label = (Label) invoke(app, "createBrushSizeLabel");
        HBox fogRow = (HBox) invoke(app, "brushSlider");
        HBox effectRow = (HBox) invoke(app, "brushSlider");
        Pane root = new Pane(canvas, fogCanvas, label, fogRow, effectRow);
        new Scene(root, 400, 300);
        invoke(app, "installDmInteractions");
        return new Fixture(app, project, canvas, root, label, (DoubleProperty) get(app, "brushSize"),
                (Slider) fogRow.getChildren().get(1), (Slider) effectRow.getChildren().get(1));
    }

    @Test
    void roomPreviewChangesColorOnShiftWithoutPointerMovementOrFogEdits() throws Exception {
        onFx(() -> {
            for (boolean enclosed : new boolean[]{true, false}) {
                Fixture f = fixture();
                f.tool("REVEAL_ROOM");
                FogMask mask = new FogMask(-200, -150, 10, 40, 30);
                f.project.getFog().setEnabled(true);
                f.project.getFog().setMask(mask);
                if (enclosed) {
                    for (double[] edge : new double[][]{
                            {-100, -100, 100, -100}, {100, -100, 100, 100},
                            {100, 100, -100, 100}, {-100, 100, -100, -100}}) {
                        f.project.getWalls().add(DmProject.WallSegment.builder()
                                .x1(edge[0]).y1(edge[1]).x2(edge[2]).y2(edge[3]).build());
                    }
                }
                set(f.app, "hoverInsideCanvas", true);
                set(f.app, "hoverWorldX", 0.0);
                set(f.app, "hoverWorldY", 0.0);
                FogMask.Snapshot before = mask.snapshot();
                Color revealColor = Color.web(enclosed ? "#7CFFB2" : "#FFB020");
                assertRoomPreviewColor(f, revealColor);
                Object cachedRoom = get(f.app, "roomPreview");
                // Scene filtering must still work when a sidebar control owns keyboard focus.
                f.fogSlider.requestFocus();
                for (int repeat = 0; repeat < 2; repeat++) {
                    javafx.event.Event.fireEvent(f.fogSlider, new KeyEvent(KeyEvent.KEY_PRESSED,
                            "", "", KeyCode.SHIFT, true, false, false, false));
                    assertRoomPreviewColor(f, Color.web("#FF8A7A"));
                }
                javafx.event.Event.fireEvent(f.fogSlider, new KeyEvent(KeyEvent.KEY_RELEASED,
                        "", "", KeyCode.SHIFT, false, false, false, false));
                assertRoomPreviewColor(f, revealColor);
                assertSame(cachedRoom, get(f.app, "roomPreview"));
                assertTrue(before.sameBits(mask.snapshot()));
                assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
                assertTrue(((Deque<?>) get(f.app, "redoStack")).isEmpty());
            }
        });
    }

    @Test
    void roomPreviewRecognizesShiftAlreadyHeldWhenPointerEntersCanvas() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.tool("REVEAL_ROOM");
            f.project.getFog().setEnabled(true);
            f.project.getFog().setMask(new FogMask(-200, -150, 10, 40, 30));
            javafx.event.Event.fireEvent(f.canvas, new MouseEvent(MouseEvent.MOUSE_MOVED,
                    200, 150, 200, 150, MouseButton.NONE, 0, true, false, false, false,
                    false, false, false, false, false, true, new PickResult(f.canvas, 200, 150)));
            assertRoomPreviewColor(f, Color.web("#FF8A7A"));
            javafx.event.Event.fireEvent(f.canvas, mouse(f.canvas, MouseEvent.MOUSE_MOVED, 200, 150, false));
            assertRoomPreviewColor(f, Color.web("#FFB020"));
        });
    }

    private static void assertRoomPreviewColor(Fixture f, Color expected) throws Exception {
        Canvas canvas = (Canvas) get(f.app, "dmFogCanvas");
        canvas.getGraphicsContext2D().clearRect(0, 0, 400, 300);
        invoke(f.app, "drawToolPreview", new Class<?>[]{javafx.scene.canvas.GraphicsContext.class},
                canvas.getGraphicsContext2D());
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        Color actual = canvas.snapshot(parameters, null).getPixelReader().getColor(205, 155);
        assertEquals(expected.getRed(), actual.getRed(), 0.02);
        assertEquals(expected.getGreen(), actual.getGreen(), 0.02);
        assertEquals(expected.getBlue(), actual.getBlue(), 0.02);
        assertTrue(actual.getOpacity() >= 0.29);
    }

    @Test
    void arrowsNudgeEveryMovableKindWithExactStepsIgnoringSnapAndRotation() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            set(f.app, "snapLayersToGrid", true);
            f.project.getMap().getGrid().setPixelsPerCell(75);
            var layer = DmProject.ImageLayer.builder().id("layer").x(13).y(17).width(100).height(100).build();
            var light = DmProject.LightSource.builder().id("light").x(13).y(17).build();
            var text = DmProject.TextBox.builder().id("text").x(13).y(17).build();
            f.project.getImageLayers().add(layer);
            f.project.getLighting().getLights().add(light);
            f.project.getTextBoxes().add(text);
            for (String kind : new String[]{"layer", "light", "text", "circle", "rect", "brush", "pen", "line"}) {
                invoke(f.app, "clearSingleSelection");
                DmProject.OverlayShape shape = null;
                if (kind.equals("layer")) {
                    set(f.app, "selectedLayer", layer);
                } else if (kind.equals("light")) {
                    set(f.app, "selectedLight", light);
                } else if (kind.equals("text")) {
                    set(f.app, "selectedTextId", text.getId());
                } else {
                    shape = DmProject.OverlayShape.builder().id(kind).type(kind).x(13).y(17)
                            .width(30).height(30).radius(15)
                            .points(new java.util.ArrayList<>(java.util.List.of(13.0, 17.0, 43.0, 47.0))).build();
                    f.project.getOverlays().add(shape);
                    set(f.app, "selectedOverlayId", shape.getId());
                }
                String key = (shape == null ? kind : "overlay") + ":" + kind;
                for (int rotation = 0; rotation < 4; rotation++) {
                    f.project.getMap().setRotationQuarterTurns(rotation);
                    assertTrue(f.press(KeyCode.UP, false).isConsumed());
                    f.release(KeyCode.UP);
                    double[] position = (double[]) invoke(f.app, "groupPosition", new Class<?>[]{String.class}, key);
                    assertArrayEquals(new double[]{13, 9.5}, position, 1e-9);
                    invoke(f.app, "undo");
                    assertArrayEquals(new double[]{13, 17},
                            (double[]) invoke(f.app, "groupPosition", new Class<?>[]{String.class}, key), 1e-9);
                }
                f.press(KeyCode.RIGHT, true);
                f.release(KeyCode.RIGHT);
                assertArrayEquals(new double[]{88, 17},
                        (double[]) invoke(f.app, "groupPosition", new Class<?>[]{String.class}, key), 1e-9);
                invoke(f.app, "undo");
                if (shape != null) {
                    assertEquals(java.util.List.of(13.0, 17.0, 43.0, 47.0), shape.getPoints());
                }
            }
        });
    }

    @Test
    void overlappingRepeatedArrowsMoveMixedGroupAsOneGestureAndKeepGeometryAndSelection() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            var layer = DmProject.ImageLayer.builder().id("layer").x(5).y(7).width(100).height(100).build();
            var light = DmProject.LightSource.builder().id("light").x(25).y(37).build();
            var shape = DmProject.OverlayShape.builder().id("effect").type("brush").x(45).y(67)
                    .points(new java.util.ArrayList<>(java.util.List.of(45.0, 67.0, 75.0, 97.0))).build();
            f.project.getImageLayers().add(layer);
            f.project.getLighting().getLights().add(light);
            f.project.getOverlays().add(shape);
            set(f.app, "selectedLayer", layer);
            invoke(f.app, "seedGroupFromSingleSelection");
            set(f.app, "selectedLight", light);
            invoke(f.app, "seedGroupFromSingleSelection");
            set(f.app, "selectedOverlayId", shape.getId());
            invoke(f.app, "seedGroupFromSingleSelection");
            invoke(f.app, "clearSingleSelection");
            var keys = java.util.Set.copyOf((java.util.Set<?>) get(f.app, "groupKeys"));
            for (int i = 0; i < 3; i++) {
                f.press(KeyCode.RIGHT, false);
            }
            assertFalse(f.press(KeyCode.A, false).isConsumed());
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            f.press(KeyCode.DOWN, true);
            f.release(KeyCode.RIGHT);
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            f.press(KeyCode.DOWN, false);
            f.release(KeyCode.DOWN);
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            assertEquals(35, layer.getX());
            assertEquals(117, layer.getY());
            assertEquals(20, light.getX() - layer.getX());
            assertEquals(30, light.getY() - layer.getY());
            assertEquals(java.util.List.of(75.0, 177.0, 105.0, 207.0), shape.getPoints());
            assertEquals(keys, get(f.app, "groupKeys"));
            invoke(f.app, "undo");
            assertEquals(5, layer.getX());
            assertEquals(7, layer.getY());
            assertEquals(25, light.getX());
            assertEquals(java.util.List.of(45.0, 67.0, 75.0, 97.0), shape.getPoints());
            invoke(f.app, "redo");
            assertEquals(35, layer.getX());
            f.press(KeyCode.LEFT, false);
            f.release(KeyCode.LEFT);
            assertEquals(2, ((Deque<?>) get(f.app, "undoStack")).size());
        });
    }

    @Test
    void nudgingLeavesLockedLayersAndNormalControlArrowsAloneAndBlocksActiveGestures() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
            var layer = DmProject.ImageLayer.builder().id("layer").width(100).height(100).build();
            var light = DmProject.LightSource.builder().id("light").build();
            f.project.getImageLayers().add(layer);
            f.project.getLighting().getLights().add(light);
            set(f.app, "selectedLayer", layer);
            f.project.getMap().setImageLayersLocked(true);
            assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
            assertEquals(0, layer.getX());
            set(f.app, "selectedLight", light);
            for (String flag : new String[]{"draggingLight", "draggingLayer", "resizingLayer", "draggingOverlay",
                    "resizingOverlay", "draggingText", "draggingPlayerViewport", "panningDmCamera",
                    "draggingGroup", "marqueeActive", "fogDragging", "canvasMouseDown",
                    "pingArmed", "laserActive", "laserToolActive"}) {
                set(f.app, flag, true);
                assertFalse(f.press(KeyCode.RIGHT, false).isConsumed(), flag);
                assertEquals(0, light.getX(), flag);
                set(f.app, flag, false);
            }
            set(f.app, "resizingTextHandle", 0);
            assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
            set(f.app, "resizingTextHandle", -1);
            set(f.app, "editingTextId", "light");
            assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
            set(f.app, "editingTextId", null);
            for (String draft : new String[]{"draftOverlay", "draftWall", "draftText"}) {
                Object value = switch (draft) {
                    case "draftOverlay" -> DmProject.OverlayShape.builder().build();
                    case "draftWall" -> DmProject.WallSegment.builder().build();
                    default -> DmProject.TextBox.builder().build();
                };
                set(f.app, draft, value);
                assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
                set(f.app, draft, null);
            }
            for (Node control : new Node[]{f.fogSlider, new TextArea(), new javafx.scene.control.ComboBox<>(),
                    new javafx.scene.control.TreeView<>()}) {
                if (control.getParent() == null) {
                    f.root.getChildren().add(control);
                }
                control.requestFocus();
                assertFalse(f.press(KeyCode.RIGHT, false).isConsumed());
                assertEquals(0, light.getX());
            }
            f.canvas.requestFocus();
            assertTrue(f.press(KeyCode.RIGHT, false).isConsumed());
            f.release(KeyCode.RIGHT);
            assertEquals(10, light.getX());
            assertEquals(0, layer.getX());
            assertSame(layer, get(f.app, "selectedLayer"));
        });
    }

    @Test
    void focusLossSelectionChangeAndUndoFinishHeldArrowGestures() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            var first = DmProject.LightSource.builder().id("first").build();
            var second = DmProject.LightSource.builder().id("second").x(50).build();
            f.project.getLighting().getLights().addAll(java.util.List.of(first, second));
            set(f.app, "selectedLight", first);
            f.press(KeyCode.RIGHT, false);
            f.fogSlider.requestFocus();
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            f.canvas.requestFocus();
            f.press(KeyCode.RIGHT, false);
            set(f.app, "selectedLight", second);
            invoke(f.app, "renderDm");
            assertEquals(2, ((Deque<?>) get(f.app, "undoStack")).size());
            f.press(KeyCode.RIGHT, false);
            invoke(f.app, "undo");
            assertEquals(50, second.getX());
            assertEquals(20, first.getX());
            assertNull(get(f.app, "nudgeStartPositions"));
            invoke(f.app, "undo");
            assertEquals(10, first.getX());
        });
    }

    @Test
    void nudgeHistoryIncludesPersistentFogEvenWhenGestureReturnsToStartAndRespectsFreeze() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            f.project.getImageLayers().add(DmProject.ImageLayer.builder()
                    .id("base").x(-300).y(-300).width(600).height(600).build());
            f.project.getFog().setEnabled(true);
            var light = DmProject.LightSource.builder().id("light").range(60)
                    .revealMode(DmProject.RevealMode.PERSISTENT).build();
            f.project.getLighting().getLights().add(light);
            set(f.app, "selectedLight", light);
            LightingEngine engine = (LightingEngine) get(f.app, "lightingEngine");
            engine.update(f.project);
            FogMask mask = f.project.getFog().getMask();
            var before = mask.copyBits();
            DmProject frozen = DmProject.builder().build();
            set(f.app, "frozenPlayerProject", frozen);
            f.press(KeyCode.RIGHT, true);
            var after = mask.copyBits();
            assertTrue(after.cardinality() > before.cardinality());
            f.press(KeyCode.LEFT, true);
            f.release(KeyCode.RIGHT);
            f.release(KeyCode.LEFT);
            assertEquals(0, light.getX());
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            invoke(f.app, "undo");
            assertEquals(before, mask.copyBits());
            engine.update(f.project);
            assertEquals(before, mask.copyBits());
            invoke(f.app, "redo");
            assertEquals(after, mask.copyBits());
            assertSame(frozen, get(f.app, "frozenPlayerProject"));
            assertTrue(frozen.getLighting().getLights().isEmpty());
        });
    }

    @Test
    void routedArrowsRespectCanvasFocusAndMixedLockedSelectionsAndPositionsPersistAfterRotation() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            f.canvas.requestFocus();
            var layer = DmProject.ImageLayer.builder().id("layer").x(12).y(15).width(100).height(200).build();
            var light = DmProject.LightSource.builder().id("light").x(25).y(35).build();
            f.project.getImageLayers().add(layer);
            f.project.getLighting().getLights().add(light);
            set(f.app, "selectedLayer", layer);
            set(f.app, "selectedLight", light);
            invoke(f.app, "seedGroupFromSingleSelection");
            invoke(f.app, "clearSingleSelection");
            invoke(f.app, "setImageLayerLocked", new Class<?>[]{boolean.class}, true);
            var rotations = new dmmt.service.MapRotationService();
            for (int turn = 0; turn < 4; turn++) {
                rotations.rotateClockwise(f.project);
                double x = light.getX();
                double y = light.getY();
                double layerX = layer.getX();
                double layerY = layer.getY();
                javafx.event.Event.fireEvent(f.canvas, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.UP,
                        false, false, false, false));
                assertEquals(x, light.getX(), 1e-9);
                assertEquals(y - 10, light.getY(), 1e-9);
                assertEquals(layerX, layer.getX(), 1e-9);
                assertEquals(layerY, layer.getY(), 1e-9);
                f.release(KeyCode.UP);
            }
            var text = new TextArea();
            f.root.getChildren().add(text);
            text.requestFocus();
            double x = light.getX();
            javafx.event.Event.fireEvent(text, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT,
                    false, false, false, false));
            assertEquals(x, light.getX());
            f.canvas.requestFocus();
            for (KeyEvent modified : new KeyEvent[]{
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, true, false, false),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, true, false),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, true)}) {
                f.canvas.getOnKeyPressed().handle(modified);
                assertFalse(modified.isConsumed());
                assertEquals(x, light.getX());
            }
            var service = new dmmt.service.ProjectService();
            Path file = temp.resolve("nudged.dmmap");
            service.save(file, f.project);
            DmProject loaded = service.load(file);
            assertEquals(light.getX(), loaded.getLighting().getLights().getFirst().getX());
            assertEquals(light.getY(), loaded.getLighting().getLights().getFirst().getY());
            assertEquals(layer.getX(), loaded.getImageLayers().getFirst().getX());
            assertTrue(loaded.getMap().imageLayersLockedOrDefault());
        });
    }

    @Test
    void stationaryViewportDragUsesElapsedTimeAndZoomKeepsAnchorAndFrozenOutputAndOneUndoStep() throws Exception {
        onFx(() -> {
            for (double zoom : new double[]{0.5, 1, 2}) {
                for (int fps : new int[]{10, 30, 60}) {
                    Fixture f = fixture();
                    f.project.getViews().getDmCamera().setZoom(zoom);
                    f.project.getMap().setRotationQuarterTurns(1);
                    beginViewportDrag(f);
                    DmProject frozen = DmProject.builder().build();
                    var frozenCamera = copy(frozen.getViews().getPlayerCamera());
                    set(f.app, "frozenPlayerProject", frozen);
                    set(f.app, "frozenPlayerCamera", frozenCamera);
                    f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, 400, 150, true));
                    tickViewport(f, 1_000_000_000L);
                    double playerStart = f.project.getViews().getPlayerCamera().getX();
                    for (int frame = 1; frame <= fps; frame++) {
                        tickViewport(f, 1_000_000_000L + Math.round(frame * 1_000_000_000.0 / fps));
                        assertViewportAnchor(f, 400, 150);
                    }
                    assertEquals(600 / zoom, f.project.getViews().getDmCamera().getX(), 1e-7);
                    assertEquals(playerStart + 600 / zoom, f.project.getViews().getPlayerCamera().getX(), 1e-7);
                    assertEquals(0, f.project.getViews().getDmCamera().getY());
                    assertEquals(zoom, f.project.getViews().getDmCamera().getZoom());
                    assertEquals(1, f.project.getViews().getPlayerCamera().getZoom());
                    assertEquals(1, f.project.getMap().getRotationQuarterTurns());
                    assertSame(frozen, get(f.app, "frozenPlayerProject"));
                    assertEquals(copy(DmProject.builder().build().getViews().getPlayerCamera()), frozenCamera);
                    assertEquals(frozenCamera, frozen.getViews().getPlayerCamera());
                    assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
                    f.canvas.getOnMouseReleased().handle(mouse(f.canvas, MouseEvent.MOUSE_RELEASED, 400, 150, false));
                    assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
                    assertEquals(0L, get(f.app, "viewportScrollNanos"));
                    assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
                    double finalPlayerX = f.project.getViews().getPlayerCamera().getX();
                    tickViewport(f, 20_000_000_000L);
                    assertEquals(finalPlayerX, f.project.getViews().getPlayerCamera().getX());
                    invoke(f.app, "undo");
                    assertEquals(0, f.project.getViews().getPlayerCamera().getX());
                    invoke(f.app, "redo");
                    assertEquals(finalPlayerX, f.project.getViews().getPlayerCamera().getX());
                    var service = new dmmt.service.ProjectService();
                    Path saved = temp.resolve("viewport.dmmap");
                    service.save(saved, f.project);
                    assertEquals(finalPlayerX, service.load(saved).getViews().getPlayerCamera().getX());
                }
            }
        });
    }

    @Test
    void viewportScrollingIsDragOnlySuspendsAtControlsAndStopsOnCancellationFocusAndClosure() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            set(f.app, "viewportDragSceneX", 400.0);
            set(f.app, "viewportDragSceneY", 150.0);
            tickViewport(f, 1_000_000_000L);
            assertEquals(0, f.project.getViews().getDmCamera().getX());
            beginViewportDrag(f);
            Region controls = new Region();
            controls.resizeRelocate(300, 0, 100, 200);
            f.root.getChildren().add(controls);
            set(f.app, "dmControls", controls);
            HBox chip = new HBox();
            chip.resizeRelocate(120, 0, 100, 40);
            f.root.getChildren().add(chip);
            set(f.app, "toolChip", chip);
            var area = (javafx.geometry.Rectangle2D) invoke(f.app, "playerViewportInteractionArea");
            assertEquals(new javafx.geometry.Rectangle2D(0, 40, 300, 260), area);
            set(f.app, "viewportDragSceneX", 290.0);
            set(f.app, "viewportDragSceneY", 150.0);
            tickViewport(f, 1_000_000_000L);
            tickViewport(f, 2_000_000_000L);
            assertEquals(450, f.project.getViews().getDmCamera().getX(), 1e-9);
            for (double[] point : new double[][]{{310, 150}, {200, 150}}) {
                set(f.app, "viewportDragSceneX", point[0]);
                set(f.app, "viewportDragSceneY", point[1]);
                tickViewport(f, 10_000_000_000L);
                assertEquals(450, f.project.getViews().getDmCamera().getX(), 1e-9);
                assertEquals(0L, get(f.app, "viewportScrollNanos"));
                assertTrue((boolean) get(f.app, "draggingPlayerViewport"));
            }
            set(f.app, "viewportDragSceneX", 290.0);
            tickViewport(f, 20_000_000_000L);
            assertEquals(450, f.project.getViews().getDmCamera().getX(), 1e-9);
            tickViewport(f, 21_000_000_000L);
            assertEquals(900, f.project.getViews().getDmCamera().getX(), 1e-9);
            Tuning.apply(key -> key.equals("player.viewportEdgeScroll.enabled") ? "false" : null);
            tickViewport(f, 22_000_000_000L);
            assertEquals(900, f.project.getViews().getDmCamera().getX(), 1e-9);
            assertEquals(0L, get(f.app, "viewportScrollNanos"));
            Tuning.apply(key -> null);
            set(f.app, "ioBusy", true);
            tickViewport(f, 23_000_000_000L);
            assertEquals(900, f.project.getViews().getDmCamera().getX(), 1e-9);
            set(f.app, "ioBusy", false);
            invoke(f.app, "cancelActiveTool");
            assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            f.canvas.requestFocus();
            beginViewportDrag(f);
            f.fogSlider.requestFocus();
            assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
            f.canvas.requestFocus();
            beginViewportDrag(f);
            invoke(f.app, "closePlayerWindow");
            assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
            assertNull(get(f.app, "playerStage"));
            assertEquals(0L, get(f.app, "viewportScrollNanos"));
        });
    }

    @Test
    void viewportScrollingSuspendsUnderAnOverlayWithoutInputAndClearsOnProjectSwitch() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            beginViewportDrag(f);
            f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, 400, 300, true));
            tickViewport(f, 1_000_000_000L);
            tickViewport(f, 2_000_000_000L);
            var dm = f.project.getViews().getDmCamera();
            assertEquals(600, Math.hypot(dm.getX(), dm.getY()), 1e-9);
            assertViewportAnchor(f, 400, 300);
            double beforeX = dm.getX();
            double beforeY = dm.getY();
            StackPane mapCenter = new StackPane();
            mapCenter.setManaged(false);
            mapCenter.resize(400, 300);
            Region overlay = new Region();
            overlay.setManaged(false);
            overlay.resizeRelocate(360, 260, 40, 40);
            mapCenter.getChildren().add(overlay);
            f.root.getChildren().add(mapCenter);
            set(f.app, "mapCenter", mapCenter);
            tickViewport(f, 10_000_000_000L);
            assertEquals(beforeX, dm.getX());
            assertEquals(beforeY, dm.getY());
            assertTrue((boolean) get(f.app, "draggingPlayerViewport"));
            assertEquals(0L, get(f.app, "viewportScrollNanos"));
            overlay.setVisible(false);
            tickViewport(f, 20_000_000_000L);
            assertEquals(beforeX, dm.getX());
            tickViewport(f, 21_000_000_000L);
            assertEquals(beforeX * 2, dm.getX(), 1e-9);
            assertEquals(beforeY * 2, dm.getY(), 1e-9);
            DmProject next = DmProject.builder().build();
            next.getViews().getDmCamera().setX(50);
            next.getViews().getPlayerCamera().setX(60);
            DmProject frozen = DmProject.builder().build();
            set(f.app, "frozenPlayerProject", frozen);
            invoke(f.app, "switchProject", new Class<?>[]{DmProject.class, Path.class}, next, null);
            assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
            assertEquals(0L, get(f.app, "viewportScrollNanos"));
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            tickViewport(f, 30_000_000_000L);
            assertEquals(50, next.getViews().getDmCamera().getX());
            assertEquals(60, next.getViews().getPlayerCamera().getX());
            assertSame(frozen, get(f.app, "frozenPlayerProject"));
        });
    }

    @Test
    void viewportDragContinuesOutsideTheWindowWithStationaryCursorAndStopsOnOutsideRelease() throws Exception {
        onFx(() -> {
            Fixture f = fixture();
            beginViewportDrag(f);
            long now = 1_000_000_000L;
            for (double[] point : new double[][]{{-200, 150}, {800, 150}, {200, -200}, {200, 600}, {800, 600}}) {
                f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, point[0], point[1], true));
                tickViewport(f, now);
                double beforeX = f.project.getViews().getDmCamera().getX();
                double beforeY = f.project.getViews().getDmCamera().getY();
                tickViewport(f, now + 1_000_000_000L);
                var dm = f.project.getViews().getDmCamera();
                assertEquals(600, Math.hypot(dm.getX() - beforeX, dm.getY() - beforeY), 1e-9);
                assertViewportAnchor(f, point[0], point[1]);
                assertTrue((boolean) get(f.app, "draggingPlayerViewport"));
                now += 2_000_000_000L;
            }
            assertTrue(((Deque<?>) get(f.app, "undoStack")).isEmpty());
            f.canvas.getOnMouseReleased().handle(mouse(f.canvas, MouseEvent.MOUSE_RELEASED, 800, 600, false));
            assertFalse((boolean) get(f.app, "draggingPlayerViewport"));
            assertEquals(1, ((Deque<?>) get(f.app, "undoStack")).size());
            var before = copy(f.project.getViews().getDmCamera());
            tickViewport(f, now);
            assertEquals(before, f.project.getViews().getDmCamera());
        });
    }

    private static void beginViewportDrag(Fixture f) throws Exception {
        f.project.getViews().getPlayerCamera().setX(0);
        f.project.getViews().getPlayerCamera().setY(0);
        set(f.app, "playerStage", new Stage());
        set(f.app, "playerCanvas", new Canvas(160, 100));
        var camera = f.project.getViews().getDmCamera();
        double x = 180 - camera.getX() * camera.getZoom();
        double y = 150 - (50 + camera.getY()) * camera.getZoom() - 10;
        f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, x, y, true));
        assertTrue((boolean) get(f.app, "draggingPlayerViewport"));
    }

    private static MouseEvent mouse(Canvas canvas, javafx.event.EventType<MouseEvent> type, double x, double y, boolean down) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1, false, false, false, false,
                down, false, false, false, false, true, new PickResult(canvas, x, y));
    }

    private static void tickViewport(Fixture f, long now) throws Exception {
        invoke(f.app, "updatePlayerViewportDrag", new Class<?>[]{long.class}, now);
    }

    private static void assertViewportAnchor(Fixture f, double x, double y) throws Exception {
        var rect = (dmmt.render.CanvasMapRenderer.WorldRect) invoke(f.app, "getPlayerViewportRect");
        var renderer = (dmmt.render.CanvasMapRenderer) get(f.app, "renderer");
        var camera = f.project.getViews().getDmCamera();
        assertEquals(x, renderer.worldToScreenX(rect.x() + (double) get(f.app, "viewportDragOffsetX"), 400, camera), 1e-7);
        assertEquals(y, renderer.worldToScreenY(rect.y() + (double) get(f.app, "viewportDragOffsetY"), 300, camera), 1e-7);
    }

    private void onFx(CheckedRunnable action) throws Exception {
        String original = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                action.run();
            } finally {
                if (original == null) {
                    System.clearProperty(AppSettings.SYSTEM_PROPERTY);
                } else {
                    System.setProperty(AppSettings.SYSTEM_PROPERTY, original);
                }
                Tuning.apply(key -> null);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private record Fixture(DungeonMasterMapToolApplication app, DmProject project, Canvas canvas, Pane root,
                           Label label, DoubleProperty size, Slider fogSlider, Slider effectSlider) {
        void tool(String name) throws Exception {
            Field field = DungeonMasterMapToolApplication.class.getDeclaredField("activeTool");
            Object tool = java.util.Arrays.stream(field.getType().getEnumConstants())
                    .filter(value -> ((Enum<?>) value).name().equals(name)).findFirst().orElseThrow();
            set(app, "activeTool", tool);
        }

        ScrollEvent scroll(double delta, boolean ctrl, boolean alt) {
            ScrollEvent event = wheel(canvas, delta, ctrl, alt);
            canvas.getOnScroll().handle(event);
            return event;
        }

        KeyEvent press(KeyCode code, boolean shift) {
            KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false);
            canvas.getOnKeyPressed().handle(event);
            return event;
        }

        void release(KeyCode code) {
            canvas.getOnKeyReleased().handle(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code,
                    false, false, false, false));
        }
    }

    private static ScrollEvent wheel(Node target, double delta, boolean ctrl, boolean alt) {
        return new ScrollEvent(ScrollEvent.SCROLL, 398, 298, 398, 298, false, ctrl, alt, false,
                false, false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(target, 398, 298));
    }

    private static DmProject.CameraState copy(DmProject.CameraState camera) {
        return DmProject.CameraState.builder().x(camera.getX()).y(camera.getY()).zoom(camera.getZoom()).build();
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object invoke(Object object, String name) throws Exception {
        return invoke(object, name, new Class<?>[0]);
    }

    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, args);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
