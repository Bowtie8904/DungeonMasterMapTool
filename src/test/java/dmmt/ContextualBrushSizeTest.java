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
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
        FutureTask<Void> ready = new FutureTask<>(() -> {
            Platform.setImplicitExit(false);
            return null;
        });
        Platform.startup(ready);
        ready.get(10, TimeUnit.SECONDS);
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
