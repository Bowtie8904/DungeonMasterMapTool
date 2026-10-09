package dmmt.render;

import dmmt.model.DmProject;
import dmmt.FxTestSupport;
import dmmt.lighting.LightingEngine;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasMapRendererTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @AfterEach
    void resetTuning() {
        Tuning.reset();
        CanvasMapRenderer.setEffectAnimationsEnabled(true);
    }

    @Test
    void frozenWeatherClockSurvivesGlobalAnimationChangesUntilUnfreeze() {
        CanvasMapRenderer renderer = new CanvasMapRenderer(new LightingEngine());
        renderer.setWeatherFrozen(true);
        double frozen = renderer.weatherSeconds();
        assertTrue(frozen > 0);
        CanvasMapRenderer.setEffectAnimationsEnabled(false);
        assertEquals(frozen, renderer.weatherSeconds());
        renderer.setWeatherFrozen(false);
        assertEquals(0, renderer.weatherSeconds());
    }

    @Test
    void lightningLightsVisibleMapButLeavesFogOpaqueAndMaskUnchanged() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            Tuning.apply(key -> key.equals("weather.thunderstorm.particles") ? "0" : null);
            DmProject project = DmProject.builder().build();
            project.getImageLayers().add(DmProject.ImageLayer.builder().width(128).height(128).build());
            project.getLighting().setTimeOfDayPreset("NIGHT");
            project.getWeather().setType("thunderstorm");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            project.getFog().getMask().applyRect(0, 0, 64, 128, true);
            var maskBefore = project.getFog().getMask().copyBits();
            DmProject.CameraState camera = DmProject.CameraState.builder().x(64).y(64).zoom(1).build();
            double[] seconds = {0};
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine) {
                @Override
                double weatherSeconds() {
                    return seconds[0];
                }
            };
            Canvas base = new Canvas(128, 128);
            base.getGraphicsContext2D().setFill(Color.WHITE);
            base.getGraphicsContext2D().fillRect(0, 0, 128, 128);
            Canvas ambient = new Canvas(128, 128);
            ambient.setBlendMode(BlendMode.MULTIPLY);
            Canvas weather = new Canvas(128, 128);
            Canvas fog = new Canvas(128, 128);
            StackPane root = new StackPane(base, ambient, weather, fog);
            new Scene(root, 128, 128);
            root.resize(128, 128);
            root.applyCss();
            root.layout();
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), project, 128, 128, camera, true);
            renderer.render(weather.getGraphicsContext2D(), project, null, 128, 128, camera, true, null);
            renderer.renderFogLayer(fog.getGraphicsContext2D(), project, 128, 128, camera, true, null, null, null);
            Color dark = root.snapshot(null, null).getPixelReader().getColor(32, 64);
            seconds[0] = (0.25 + 0.5 * WeatherEffects.unit(0, 83))
                    * project.getWeather().getLightningIntervalSeconds();
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), project, 128, 128, camera, true);
            renderer.render(weather.getGraphicsContext2D(), project, null, 128, 128, camera, true, null);
            renderer.renderFogLayer(fog.getGraphicsContext2D(), project, 128, 128, camera, true, null, null, null);
            var lit = root.snapshot(null, null).getPixelReader();
            assertTrue(lit.getColor(32, 64).getBrightness() > dark.getBrightness() + 0.4);
            assertEquals(Color.BLACK, lit.getColor(96, 64));
            assertEquals(maskBefore, project.getFog().getMask().copyBits());
            assertTrue(engine.getLiveReveal().isEmpty());
            CanvasMapRenderer.setEffectAnimationsEnabled(false);
            assertEquals(0, new CanvasMapRenderer(engine).weatherSeconds());
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void gridOpacityInvalidatesCachedBase() {
        DmProject project = DmProject.builder().build();
        DmProject.CameraState camera = DmProject.CameraState.builder().build();
        long before = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY);

        Tuning.apply(key -> key.equals(Tuning.GRID_OPACITY.key()) ? "0.5" : null);

        assertNotEquals(before, CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY));
    }

    @Test
    void gridModeInvalidatesCachedBaseEvenWithoutCameraOrMapChanges() {
        DmProject project = DmProject.builder().build();
        DmProject.CameraState camera = DmProject.CameraState.builder().build();

        long hidden = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.HIDDEN);
        long overlay = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.OVERLAY);
        long background = CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.BACKGROUND);

        assertNotEquals(hidden, overlay);
        assertNotEquals(background, overlay);
        assertNotEquals(background, hidden);
        assertEquals(hidden, CanvasMapRenderer.baseSignature(project, null, 800, 600, camera,
                CanvasMapRenderer.GridMode.HIDDEN));
    }
}
