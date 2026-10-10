package dmmt.lighting;

import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.render.CanvasMapRenderer;
import dmmt.render.PerformanceMode;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IndoorLightingRenderTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @AfterEach
    void reset() {
        Tuning.reset();
        CanvasMapRenderer.setLightFlickerEnabled(true);
        PerformanceMode.setEnabled(false);
    }

    @Test
    void enclosedDarknessMatchesNightInAllPresetsAndDaylightIsNotPresentAtNight() throws Exception {
        runFx(() -> {
            DmProject project = IndoorLightingTest.room("door", "closed");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            project.getLighting().putAmbientBrightness("NIGHT", 0.2);
            project.getLighting().setTimeOfDayPreset("NIGHT");
            int night = ambient(renderer, project, true).getPixelReader().getArgb(100, 100);
            for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
                project.getLighting().setTimeOfDayPreset(preset.name());
                var pixels = ambient(renderer, project, true).getPixelReader();
                assertEquals(night, pixels.getArgb(100, 100), preset.name());
                if (preset == TimeOfDayPreset.DAY) {
                    assertTrue(pixels.getColor(20, 20).getBrightness() > 0.99);
                }
            }
            project.getInteractables().getFirst().setType("window");
            project.getInteractables().getFirst().setState("open");
            engine.update(project);
            project.getLighting().setTimeOfDayPreset("DAY");
            double sun = ambient(renderer, project, true).getPixelReader().getColor(60, 100).getBrightness();
            project.getLighting().setTimeOfDayPreset("NIGHT");
            double dark = ambient(renderer, project, true).getPixelReader().getColor(60, 100).getBrightness();
            assertTrue(sun > dark + 0.4);
            assertTrue(ambient(renderer, project, false).getPixelReader().getColor(100, 100).getBrightness()
                    > ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness());
        });
    }

    @Test
    void indoorLightsIlluminateAndFlickerDuringDayButOutdoorLightsStayOff() throws Exception {
        runFx(() -> {
            DmProject project = IndoorLightingTest.room("door", "closed");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            project.getLighting().setTimeOfDayPreset("DAY");
            double unlit = ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness();
            DmProject.LightSource light = DmProject.LightSource.builder().id("torch")
                    .x(500).y(500).range(400).flicker(DmProject.Flicker.builder()
                            .enabled(true).strength(1).speed(1).build()).build();
            project.getLighting().getLights().add(light);
            engine.update(project);
            CanvasMapRenderer.setLightFlickerEnabled(false);
            double steady = ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness();
            assertTrue(steady > unlit + 0.5);
            assertTrue(coreOpacity(renderer, project, 100, 100) > 0.05, "Indoor bright cores remain visible at Day");
            CanvasMapRenderer.setLightFlickerEnabled(true);
            double flicker = ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness();
            assertTrue(flicker < steady - 0.01);
            PerformanceMode.setEnabled(true);
            assertEquals(steady, ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness(), 0.01);
            PerformanceMode.setEnabled(false);
            light.setX(100);
            light.setY(100);
            light.setCastsShadows(false);
            light.setRange(1000);
            engine.update(project);
            for (String preset : new String[]{"DAY", "DAWN", "DUSK"}) {
                project.getLighting().setTimeOfDayPreset(preset);
                assertEquals(unlit, ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness(), 0.01);
                assertEquals(0, coreOpacity(renderer, project, 20, 20));
            }
            project.getLighting().setTimeOfDayPreset("NIGHT");
            assertTrue(ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness() > unlit);
        });
    }

    private static DmProject.CameraState camera() {
        return DmProject.CameraState.builder().x(500).y(500).zoom(0.2).build();
    }

    private static WritableImage ambient(CanvasMapRenderer renderer, DmProject project, boolean player) {
        Canvas canvas = new Canvas(200, 200);
        renderer.renderAmbientLight(canvas.getGraphicsContext2D(), project, 200, 200, camera(), player);
        return canvas.snapshot(null, null);
    }

    private static double coreOpacity(CanvasMapRenderer renderer, DmProject project, int x, int y) {
        Canvas canvas = new Canvas(200, 200);
        renderer.renderBrightCore(canvas.getGraphicsContext2D(), project, 200, 200, camera(), true);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null).getPixelReader().getColor(x, y).getOpacity();
    }

    private static void runFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
