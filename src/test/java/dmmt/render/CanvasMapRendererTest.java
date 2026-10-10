package dmmt.render;

import dmmt.model.DmProject;
import dmmt.FxTestSupport;
import dmmt.lighting.LightingEngine;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.WritableImage;
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
    void mistRendersEvenCoverageAndKeepsLiveSettingsAndReducedFramesConsistent() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            double[] totals = new double[5];
            for (int t = 0; t < 24; t++) {
                var pixels = mistSnapshot(1, t * 17, false).getPixelReader();
                for (int y = 0; y < 96; y++) {
                    for (int x = 0; x < 160; x++) {
                        double brightness = pixels.getColor(x, y).getRed();
                        if (x < 24) totals[0] += brightness / (24 * 96);
                        if (x >= 136) totals[1] += brightness / (24 * 96);
                        if (y < 14) totals[2] += brightness / (160 * 14);
                        if (y >= 82) totals[3] += brightness / (160 * 14);
                        if (x >= 48 && x < 112 && y >= 29 && y < 67) {
                            totals[4] += brightness / (64 * 38);
                        }
                    }
                }
            }
            assertTrue(totals[4] / 24 > 0.4, "Full-intensity mist should form thick fog");
            for (int edge = 0; edge < 4; edge++) {
                double ratio = totals[edge] / totals[4];
                assertTrue(ratio >= 0.85 && ratio <= 1.15, "Rendered edge/centre ratio: " + ratio);
            }
            var full = mistSnapshot(1, 42, false).getPixelReader();
            var whiteBackground = mistSnapshot(1, 42, false, Color.WHITE).getPixelReader();
            var reduced = mistSnapshot(1, 42, true).getPixelReader();
            var low = mistSnapshot(0.1, 42, false).getPixelReader();
            double fullBrightness = 0;
            double lowBrightness = 0;
            double minBrightness = 1;
            double maxBrightness = 0;
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 160; x++) {
                    assertEquals(full.getArgb(x, y), reduced.getArgb(x, y));
                    assertEquals(full.getArgb(x, y), whiteBackground.getArgb(x, y),
                            "100% mist must completely hide the underlying map at every pixel");
                    fullBrightness += full.getColor(x, y).getRed();
                    lowBrightness += low.getColor(x, y).getRed();
                    minBrightness = Math.min(minBrightness, full.getColor(x, y).getRed());
                    maxBrightness = Math.max(maxBrightness, full.getColor(x, y).getRed());
                }
            }
            assertTrue(fullBrightness > lowBrightness * 1.3);
            assertTrue(maxBrightness - minBrightness > 0.2, "Mist must retain billows, not just a flat tint");
            double defaultBrightness = 0;
            double minDefault = 1;
            double maxDefault = 0;
            var defaults = mistSnapshot(WeatherEffects.defaultIntensity(), 42, false).getPixelReader();
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 160; x++) {
                    defaultBrightness += defaults.getColor(x, y).getRed();
                    minDefault = Math.min(minDefault, defaults.getColor(x, y).getRed());
                    maxDefault = Math.max(maxDefault, defaults.getColor(x, y).getRed());
                }
            }
            assertTrue(defaultBrightness / (160 * 96) > 0.3, "Default intensity must form dense fog");
            assertTrue(maxDefault - minDefault > 0.2, "Intermediate intensity must show textured smoke");
            var whiteDefault = mistSnapshot(WeatherEffects.defaultIntensity(), 42, false, Color.WHITE).getPixelReader();
            assertNotEquals(defaults.getArgb(80, 48), whiteDefault.getArgb(80, 48),
                    "Intermediate mist should still allow the map to show through");
            Tuning.apply(key -> key.equals("weather.mist.color") ? "#FF0000" : null);
            Color red = mistSnapshot(1, 42, false).getPixelReader().getColor(80, 48);
            assertTrue(red.getRed() > 0.05 && red.getBlue() == 0);
            Tuning.apply(key -> key.equals("weather.mist.color") ? "#0000FF" : null);
            Color blue = mistSnapshot(1, 42, false).getPixelReader().getColor(80, 48);
            assertTrue(blue.getBlue() > 0.05 && blue.getRed() == 0);
            Tuning.apply(key -> key.equals("weather.mist.opacity") ? "0" : null);
            assertEquals(Color.BLACK, mistSnapshot(1, 42, false).getPixelReader().getColor(80, 48));
            Tuning.apply(key -> key.equals("weather.mist.particles") ? "0" : null);
            assertEquals(Color.BLACK, mistSnapshot(1, 42, false).getPixelReader().getColor(80, 48));
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }

    private static WritableImage mistSnapshot(double intensity, double seconds, boolean reduced) {
        return mistSnapshot(intensity, seconds, reduced, Color.BLACK);
    }

    private static WritableImage mistSnapshot(double intensity, double seconds, boolean reduced, Color background) {
        Canvas canvas = new Canvas(160, 96);
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(background);
        gc.fillRect(0, 0, 160, 96);
        gc.setGlobalAlpha(0.65);
        WeatherEffects.draw(gc, WeatherType.MIST, intensity, 160, 96, seconds, reduced);
        assertEquals(0.65, gc.getGlobalAlpha());
        return canvas.snapshot(null, null);
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
