package dmmt;

import dmmt.model.DmProject;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
