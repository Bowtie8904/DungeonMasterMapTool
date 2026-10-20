package dmmt;

import dmmt.lighting.LightingEngine;
import dmmt.model.DmProject;
import dmmt.render.CanvasMapRenderer;
import dmmt.ui.HandoutWindow;
import dmmt.ui.PlayerViewTransition;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLayerRetentionTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void playerPointersExpireAndHandoutsAndFrozenRendererRestoreLayers() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            DmProject project = DmProject.builder().build();
            project.getLighting().setTimeOfDayPreset("DAY");
            project.getFog().setEnabled(false);
            project.getViews().getPlayerCamera().setX(64);
            project.getViews().getPlayerCamera().setY(48);
            project.getOverlays().add(DmProject.OverlayShape.builder().id("flat").type("circle")
                    .x(64).y(48).radius(20).build());
            project.getTokenDefinitions().put("hero", DmProject.TokenDefinition.builder().id("hero").name("Hero").build());
            project.getTokens().add(DmProject.TokenInstance.builder().id("instance").definitionId("hero")
                    .x(64).y(48).playerVisible(true).build());
            set(app, "project", project);
            ((LightingEngine) get(app, "lightingEngine")).update(project);
            Stage stage = new Stage();
            StackPane root = new StackPane();
            for (String name : List.of("playerBaseCanvas", "playerTokenCanvas", "playerBrightCoreCanvas", "playerAmbientCanvas",
                    "playerCanvas", "playerLightningCanvas", "playerWeatherCanvas", "playerForegroundCanvas",
                    "playerFogMaskCanvas", "playerFogCanvas")) {
                Canvas canvas = new Canvas(128, 96);
                set(app, name, canvas);
                root.getChildren().add(canvas);
            }
            PlayerViewTransition transition = new PlayerViewTransition(root);
            set(app, "playerTransition", transition);
            set(app, "playerStage", stage);
            HandoutWindow handout = new HandoutWindow(stage, List.of(),
                    () -> true, () -> {}, () -> {});
            try {
                @SuppressWarnings("unchecked")
                List<Image> images = (List<Image>) get(handout, "images");
                images.add(new WritableImage(20, 20));
                invoke(handout, "afterImagesChanged", new Class<?>[]{});
                Canvas effects = (Canvas) get(app, "playerCanvas");
                Canvas pointers = (Canvas) get(app, "playerFogCanvas");
                render(app);
                assertTrue(effects.isVisible());
                assertTrue(((Canvas)get(app, "playerTokenCanvas")).isVisible());
                assertFalse(pointers.isVisible());
                project.getActivePings().add(DmProject.PingEvent.builder().x(64).y(48)
                        .createdAtMillis(System.currentTimeMillis()).durationMillis(10000).build());
                render(app);
                assertTrue(pointers.isVisible());
                project.getActivePings().getFirst().setCreatedAtMillis(0);
                render(app);
                assertTrue(project.getActivePings().isEmpty());
                assertFalse(pointers.isVisible());
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                assertEquals(0, pointers.snapshot(parameters, null).getPixelReader().getArgb(64, 48));

                invoke(app, "addLaserPoint", new Class<?>[]{double.class, double.class}, 64.0, 48.0);
                render(app);
                assertTrue(pointers.isVisible());
                DmProject frozen = new dmmt.service.ProjectService().copy(project);
                frozen.getTokens().getFirst().setPlayerVisible(false);
                set(app, "frozenPlayerProject", frozen);
                ((LightingEngine) get(app, "playerLightingEngine")).update(frozen);
                render(app);
                assertFalse(pointers.isVisible(), "Frozen output does not show the live laser");
                assertFalse(((Canvas) get(app, "playerTokenCanvas")).isVisible(), "Frozen token visibility is independent");
                assertTrue(effects.isVisible(), "Switching renderer repopulates effects");
                set(app, "frozenPlayerProject", null);
                render(app);
                assertTrue(pointers.isVisible());
                assertTrue(((Canvas) get(app, "playerTokenCanvas")).isVisible(), "Unfreezing restores live token artwork");
                @SuppressWarnings("unchecked")
                List<CanvasMapRenderer.LaserPoint> trail = (List<CanvasMapRenderer.LaserPoint>) get(app, "laserTrail");
                trail.set(0, new CanvasMapRenderer.LaserPoint(64, 48, 0));
                invoke(app, "pruneLaserTrail", new Class<?>[]{});
                render(app);
                assertFalse(pointers.isVisible());
                set(app, "showScaleTestSquare", true);
                render(app);
                assertTrue(pointers.isVisible());
                set(app, "showScaleTestSquare", false);

                set(app, "handoutWindow", handout);
                ((ToggleButton) get(handout, "showToggle")).fire();
                render(app);
                assertFalse(((Canvas) get(app, "playerBaseCanvas")).isVisible());
                assertFalse(((Canvas) get(app, "playerTokenCanvas")).isVisible());
                assertFalse(pointers.isVisible());
                assertTrue(effects.isVisible(), "The handout uses the effects canvas");
                ((ToggleButton) get(handout, "showToggle")).fire();
                render(app);
                assertTrue(((Canvas) get(app, "playerBaseCanvas")).isVisible());
                assertTrue(((Canvas) get(app, "playerTokenCanvas")).isVisible());
                assertTrue(effects.isVisible());
                assertFalse(pointers.isVisible());
                assertFalse(((Canvas) get(app, "playerWeatherCanvas")).isVisible());
                return null;
            } finally {
                handout.close();
                transition.cancel();
                stage.close();
            }
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private static void render(DungeonMasterMapToolApplication app) throws Exception {
        invoke(app, "renderPlayer", new Class<?>[]{});
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
