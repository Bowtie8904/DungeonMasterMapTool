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
import javafx.scene.SnapshotParameters;
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
            assertTrue(defaultBrightness / (160 * 96) > 0.1, "Default mist must remain visible");
            assertTrue(defaultBrightness / (160 * 96) < 0.3, "Lower default opacity should produce lighter mist");
            assertTrue(maxDefault - minDefault > 0.05, "Intermediate intensity must show textured smoke");
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
                protected double weatherSeconds() {
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
    void retainedLayersAndOutdoorWeatherClippingInvalidatePerView() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            Tuning.apply(key -> key.equals("weather.rain.particles") ? "10000" : null);
            DmProject project = DmProject.builder().build();
            project.getImageLayers().add(DmProject.ImageLayer.builder().width(1000).height(1000).build());
            project.getLighting().setTimeOfDayPreset("NIGHT");
            project.getWeather().setType("rain");
            project.getWeather().setIntensity(1);
            project.getWalls().addAll(java.util.List.of(
                    DmProject.WallSegment.builder().x1(200).y1(200).x2(800).y2(200).build(),
                    DmProject.WallSegment.builder().x1(800).y1(200).x2(800).y2(800).build(),
                    DmProject.WallSegment.builder().x1(800).y1(800).x2(200).y2(800).build(),
                    DmProject.WallSegment.builder().x1(200).y1(800).x2(200).y2(200).build()));
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            assertTrue(!engine.getIndoorLighting().indoorRegions().isEmpty(),
                    "The enclosed-room fixture must provide outdoor-weather clipping geometry");
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            renderer.setWeatherFrozen(true);
            DmProject.CameraState insideRoom = DmProject.CameraState.builder().x(500).y(500).zoom(1).build();
            Canvas brightCore = new Canvas(128, 96);
            Canvas ambient = new Canvas(128, 96);
            Canvas fog = new Canvas(128, 96);
            Canvas weather = new Canvas(128, 96);
            Canvas lightning = new Canvas(128, 96);

            renderer.renderBrightCore(brightCore.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.renderFogMaskLayer(fog.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, insideRoom, true);
            SnapshotParameters transparentSnapshot = new SnapshotParameters();
            transparentSnapshot.setFill(Color.TRANSPARENT);
            var insidePixels = weather.snapshot(transparentSnapshot, null).getPixelReader();
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 128; x++) {
                    assertEquals(0, insidePixels.getArgb(x, y) >>> 24,
                            "Outdoor rain must not be visible inside a fully enclosed room");
                }
            }
            assertEquals(1, renderer.brightCoreRedrawCount(true));
            assertEquals(1, renderer.ambientRedrawCount(true));
            assertEquals(1, renderer.fogRedrawCount(true));
            assertEquals(1, renderer.weatherRedrawCount(true));
            assertEquals(1, renderer.outdoorClipBuildCount(true));

            renderer.renderBrightCore(brightCore.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.renderFogMaskLayer(fog.getGraphicsContext2D(), project, 128, 96, insideRoom, true);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, insideRoom, true);
            assertEquals(1, renderer.brightCoreRedrawCount(true));
            assertEquals(1, renderer.ambientRedrawCount(true));
            assertEquals(1, renderer.fogRedrawCount(true));
            assertEquals(1, renderer.weatherRedrawCount(true));
            assertEquals(1, renderer.outdoorClipBuildCount(true));

            DmProject.CameraState outdoors = DmProject.CameraState.builder().x(100).y(100).zoom(1).build();
            renderer.renderBrightCore(brightCore.getGraphicsContext2D(), project, 128, 96, outdoors, true);
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), project, 128, 96, outdoors, true);
            renderer.renderFogMaskLayer(fog.getGraphicsContext2D(), project, 128, 96, outdoors, true);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, outdoors, true);
            var outdoorPixels = weather.snapshot(transparentSnapshot, null).getPixelReader();
            boolean rainVisible = false;
            for (int y = 0; y < 96 && !rainVisible; y++) {
                for (int x = 0; x < 128; x++) {
                    if ((outdoorPixels.getArgb(x, y) >>> 24) != 0) {
                        rainVisible = true;
                        break;
                    }
                }
            }
            assertTrue(rainVisible, "Outdoor rain remains visible beyond enclosed rooms");
            assertEquals(2, renderer.brightCoreRedrawCount(true));
            assertEquals(2, renderer.ambientRedrawCount(true));
            assertEquals(2, renderer.fogRedrawCount(true));
            assertEquals(2, renderer.weatherRedrawCount(true));
            assertEquals(2, renderer.outdoorClipBuildCount(true));

            long clipsBeforeGeometryChange = renderer.outdoorClipBuildCount(true);
            project.getWalls().add(DmProject.WallSegment.builder().x1(900).y1(900).x2(950).y2(900).build());
            engine.update(project);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, outdoors, true);
            assertEquals(clipsBeforeGeometryChange + 1, renderer.outdoorClipBuildCount(true));

            Tuning.apply(key -> key.equals("weather.rain.indoors") ? "true" : null);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, outdoors, true);
            Tuning.apply(key -> key.equals("weather.rain.indoors") ? "false" : null);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(weather, lightning, project, 128, 96, outdoors, true);
            assertEquals(clipsBeforeGeometryChange + 2, renderer.outdoorClipBuildCount(true));

            Canvas dmWeather = new Canvas(128, 96);
            renderer.beginWeatherFrame();
            renderer.renderWeatherLayer(dmWeather, new Canvas(128, 96), project, 128, 96, outdoors, false);
            assertEquals(1, renderer.weatherRedrawCount(false), "DM and player weather caches must remain independent");
            assertEquals(1, renderer.outdoorClipBuildCount(false));

            project.getFog().getMask().applyRect(10, 10, 10, 10, true);
            renderer.renderFogMaskLayer(fog.getGraphicsContext2D(), project, 128, 96, outdoors, true);
            assertEquals(3, renderer.fogRedrawCount(true), "Fog edits invalidate the retained fog image");

            weather.setWidth(160);
            lightning.setWidth(160);
            long redraws = renderer.weatherRedrawCount(true);
            long clips = renderer.outdoorClipBuildCount(true);
            renderer.renderWeatherLayer(weather, lightning, project, 160, 96, outdoors, true);
            assertEquals(redraws + 1, renderer.weatherRedrawCount(true));
            assertEquals(clips + 1, renderer.outdoorClipBuildCount(true), "Resizing invalidates screen-space clipping");
            Tuning.apply(key -> key.equals("weather.rain.color") ? "#FF0000" : null);
            renderer.renderWeatherLayer(weather, lightning, project, 160, 96, outdoors, true);
            assertEquals(redraws + 2, renderer.weatherRedrawCount(true), "Live colors invalidate frozen weather");
            assertEquals(clips + 1, renderer.outdoorClipBuildCount(true), "Color changes reuse geometry");
            renderer.invalidatePlayerLayerCaches();
            renderer.renderWeatherLayer(weather, lightning, project, 160, 96, outdoors, true);
            assertEquals(redraws + 3, renderer.weatherRedrawCount(true), "Returning from a handout restores weather");

            Canvas replacement = new Canvas(128, 96);
            renderer.renderAmbientLight(replacement.getGraphicsContext2D(), project, 128, 96, outdoors, true);
            assertEquals(3, renderer.ambientRedrawCount(true), "A replacement canvas needs its own retained content");
            project.getWeather().setType("none");
            renderer.renderWeatherLayer(weather, lightning, project, 160, 96, outdoors, true);
            var cleared = weather.snapshot(transparentSnapshot, null).getPixelReader();
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 160; x++) assertEquals(0, cleared.getArgb(x, y) >>> 24);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    @Test
    void weatherCadenceReusesClipAndFrozenFramesButRespondsToLiveChanges() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            CanvasMapRenderer.setAnimationFps(30);
            DmProject project = DmProject.builder().build();
            project.getWeather().setType("rain");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            double[] time = {10};
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine) {
                @Override
                protected double weatherSeconds() {
                    return time[0];
                }
            };
            Canvas particles = new Canvas(160, 96);
            Canvas lightning = new Canvas(160, 96);
            DmProject.CameraState camera = DmProject.CameraState.builder().zoom(1).build();
            renderer.renderWeatherLayer(particles, lightning, project, 160, 96, camera, true);
            time[0] += 0.01;
            renderer.renderWeatherLayer(particles, lightning, project, 160, 96, camera, true);
            assertEquals(1, renderer.weatherRedrawCount(true), "Interaction frames reuse the same animation frame");
            time[0] += 0.05;
            renderer.renderWeatherLayer(particles, lightning, project, 160, 96, camera, true);
            assertEquals(2, renderer.weatherRedrawCount(true));
            assertEquals(1, renderer.outdoorClipBuildCount(true), "Animation does not rebuild the clip");
            renderer.renderWeatherLayer(particles, lightning, project, 160, 96, camera, true);
            assertEquals(2, renderer.weatherRedrawCount(true), "A frozen clock retains the same frame");
            project.getWeather().setIntensity(0.8);
            renderer.renderWeatherLayer(particles, lightning, project, 160, 96, camera, true);
            assertEquals(3, renderer.weatherRedrawCount(true), "Intensity updates even when the clock is frozen");
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
