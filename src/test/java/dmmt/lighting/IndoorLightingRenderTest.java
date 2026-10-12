package dmmt.lighting;

import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.render.CanvasMapRenderer;
import dmmt.render.PerformanceMode;
import dmmt.render.WeatherEffects;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
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
    void weatherClipFollowsPanningZoomAndEntirelyIndoorViewports() throws Exception {
        runFx(() -> {
            Tuning.apply(key -> key.equals("weather.rain.particles") ? "10000" : null);
            DmProject project = IndoorLightingTest.room("door", "open");
            project.getWeather().setType("rain");
            project.getWeather().setIntensity(1);
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            TimedRenderer renderer = new TimedRenderer(engine);
            for (DmProject.CameraState camera : new DmProject.CameraState[]{
                    DmProject.CameraState.builder().x(550).y(450).zoom(0.3).build(),
                    DmProject.CameraState.builder().x(500).y(500).zoom(1).build()}) {
                Canvas canvas = new Canvas(200, 200);
                renderer.render(canvas.getGraphicsContext2D(), project, null, 200, 200, camera, true, null);
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setFill(Color.TRANSPARENT);
                var pixels = canvas.snapshot(parameters, null).getPixelReader();
                double exterior = 0;
                for (int y = 0; y < 200; y++) {
                    for (int x = 0; x < 200; x++) {
                        var point = renderer.screenToWorld(x + 0.5, y + 0.5, 200, 200, camera);
                        // Leave a screen-pixel margin around the anti-aliased room boundary.
                        if (point.x() > 220 && point.x() < 780 && point.y() > 220 && point.y() < 780) {
                            assertEquals(0, pixels.getColor(x, y).getOpacity());
                        } else {
                            exterior += pixels.getColor(x, y).getOpacity();
                        }
                    }
                }
                if (camera.getZoom() == 1) {
                    assertEquals(0, exterior, "Fully enclosed viewport has no precipitation");
                } else {
                    assertTrue(exterior > 1, "Panned viewport still has outdoor rain");
                }
            }
        });
    }

    @Test
    void weatherIsClippedToEnclosuresAndIndoorFlagsApplyLiveInBothViews() throws Exception {
        runFx(() -> {
            DmProject project = IndoorLightingTest.room("door", "open");
            project.getWeather().setIntensity(1);
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            TimedRenderer renderer = new TimedRenderer(engine);
            for (String type : new String[]{"rain", "snow", "thunderstorm"}) {
                project.getWeather().setType(type);
                for (boolean reduced : new boolean[]{false, true}) {
                    PerformanceMode.setEnabled(reduced);
                    for (boolean player : new boolean[]{false, true}) {
                        Tuning.apply(key -> key.equals("weather." + type + ".particles") ? "10000" : null);
                        var outsideOnly = weather(renderer, project, player).getPixelReader();
                        double exterior = 0;
                        for (int y = 0; y < 30; y++) {
                            for (int x = 0; x < 200; x++) exterior += outsideOnly.getColor(x, y).getOpacity();
                        }
                        assertTrue(exterior > 1, type + " still renders outdoors");
                        for (int y = 75; y < 125; y++) {
                            for (int x = 75; x < 125; x++) {
                                assertEquals(0, outsideOnly.getColor(x, y).getOpacity(), type + " cannot enter a room");
                            }
                        }
                        Tuning.apply(key -> key.equals("weather." + type + ".particles") ? "10000"
                                : key.equals("weather." + type + ".indoors") ? "true" : null);
                        var indoor = weather(renderer, project, player).getPixelReader();
                        double coverage = 0;
                        for (int y = 75; y < 125; y++) {
                            for (int x = 75; x < 125; x++) coverage += indoor.getColor(x, y).getOpacity();
                        }
                        assertTrue(coverage > 1, type + " indoor override applies live");
                    }
                }
            }
        });
    }

    @Test
    void lightningThroughOpeningsTracksOutdoorFlashEvenAtNightAndClosedRoomsStayDark() throws Exception {
        runFx(() -> {
            Tuning.apply(key -> key.equals("weather.thunderstorm.particles") ? "0" : null);
            DmProject project = IndoorLightingTest.room("window", "open");
            project.getWeather().setType("thunderstorm");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            TimedRenderer renderer = new TimedRenderer(engine);
            double interval = project.getWeather().getLightningIntervalSeconds();
            double strike = (0.25 + 0.5 * WeatherEffects.unit(0, 83)) * interval;
            var beforeFog = project.getFog().getMask().copyBits();
            for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
                project.getLighting().setTimeOfDayPreset(preset.name());
                for (boolean player : new boolean[]{false, true}) {
                    renderer.seconds = 0;
                    var baseline = ambient(renderer, project, player).getPixelReader();
                    renderer.seconds = strike;
                    var peak = ambient(renderer, project, player).getPixelReader();
                    if (preset != TimeOfDayPreset.DAY) {
                        assertTrue(peak.getColor(60, 100).getBrightness() > baseline.getColor(60, 100).getBrightness() + 0.1);
                    }
                    assertEquals(baseline.getArgb(60, 50), peak.getArgb(60, 50), "Wall shadow is not flashed");
                    var highlight = weather(renderer, project, player).getPixelReader();
                    assertEquals(highlight.getColor(20, 20).getOpacity(), highlight.getColor(60, 100).getOpacity(), 0.01);
                    assertTrue(highlight.getColor(60, 100).getOpacity() > 0.2);
                    assertEquals(0, highlight.getColor(60, 50).getOpacity());
                    renderer.seconds = strike + Tuning.WEATHER_FLASH_DURATION.get() * 0.6;
                    var fade = weather(renderer, project, player).getPixelReader();
                    double ratio = fade.getColor(60, 100).getOpacity() / highlight.getColor(60, 100).getOpacity();
                    assertEquals(WeatherEffects.lightningFlash(renderer.seconds, interval), ratio, 0.03);
                }
            }
            project.getLighting().setTimeOfDayPreset("NIGHT");
            for (String type : new String[]{"window", "door"}) {
                project.getInteractables().getFirst().setType(type);
                project.getInteractables().getFirst().setState("closed");
                engine.update(project);
                renderer.seconds = 0;
                int dark = ambient(renderer, project, true).getPixelReader().getArgb(60, 100);
                renderer.seconds = strike;
                assertEquals(dark, ambient(renderer, project, true).getPixelReader().getArgb(60, 100));
                assertEquals(0, weather(renderer, project, true).getPixelReader().getColor(60, 100).getOpacity());
                project.getInteractables().getFirst().setState("open");
                engine.update(project);
                assertTrue(ambient(renderer, project, true).getPixelReader().getColor(60, 100).getBrightness() > 0.95);
            }
            project.getInteractables().getFirst().setState("closed");
            engine.update(project);
            Tuning.apply(key -> key.equals("weather.thunderstorm.indoors") ? "true"
                    : key.equals("weather.thunderstorm.particles") ? "0" : null);
            assertTrue(ambient(renderer, project, true).getPixelReader().getColor(60, 50).getBrightness() > 0.95);
            assertTrue(weather(renderer, project, true).getPixelReader().getColor(60, 50).getOpacity() > 0.2);
            assertEquals(beforeFog, project.getFog().getMask().copyBits());
            assertTrue(engine.getLiveReveal().isEmpty());
        });
    }

    private static class TimedRenderer extends CanvasMapRenderer {
        private double seconds;

        TimedRenderer(LightingEngine engine) {
            super(engine);
            setWallLayerVisible(false);
        }

        @Override
        protected double weatherSeconds() {
            return seconds;
        }
    }

    private static WritableImage weather(CanvasMapRenderer renderer, DmProject project, boolean player) {
        Canvas particles = new Canvas(200, 200);
        Canvas lightning = new Canvas(200, 200);
        renderer.renderWeatherLayer(particles, lightning, project, 200, 200, camera(), player);
        StackPane layers = new StackPane(lightning, particles);
        layers.setStyle("-fx-background-color: transparent;");
        new Scene(layers, 200, 200, Color.TRANSPARENT);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return layers.snapshot(parameters, null);
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
            engine.update(project);
            assertTrue(ambient(renderer, project, true).getPixelReader().getColor(100, 100).getBrightness() > 0.99);
            assertEquals(0, coreOpacity(renderer, project, 100, 100));
            PerformanceMode.setEnabled(false);
            engine.update(project);
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

    @Test
    void indoorDarknessSwitchAndPerformanceModeApplyToBothViewsAndRestoreCachedLighting() throws Exception {
        runFx(() -> {
            DmProject project = IndoorLightingTest.room("door", "closed");
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            for (boolean player : new boolean[]{false, true}) {
                for (TimeOfDayPreset preset : TimeOfDayPreset.values()) {
                    project.getLighting().setTimeOfDayPreset(preset.name());
                    int indoor = ambient(renderer, project, player).getPixelReader().getArgb(100, 100);
                    Tuning.apply(key -> key.equals("lighting.indoorDarkness") ? "false" : null);
                    var switched = ambient(renderer, project, player).getPixelReader();
                    assertEquals(switched.getArgb(20, 20), switched.getArgb(100, 100),
                            "Rooms follow outdoor ambient: " + preset);
                    if (preset == TimeOfDayPreset.NIGHT) {
                        assertEquals(indoor, switched.getArgb(100, 100));
                    }
                    Tuning.reset();
                    assertEquals(indoor, ambient(renderer, project, player).getPixelReader().getArgb(100, 100));

                    PerformanceMode.setEnabled(true);
                    engine.update(project);
                    var reduced = ambient(renderer, project, player).getPixelReader();
                    assertEquals(reduced.getArgb(20, 20), reduced.getArgb(100, 100));
                    if (preset == TimeOfDayPreset.NIGHT) {
                        assertEquals(indoor, reduced.getArgb(100, 100));
                    }
                    PerformanceMode.setEnabled(false);
                    engine.update(project);
                    assertEquals(indoor, ambient(renderer, project, player).getPixelReader().getArgb(100, 100));
                }
            }

            project.getInteractables().getFirst().setState("open");
            project.getLighting().setTimeOfDayPreset("DAY");
            engine.update(project);
            for (boolean player : new boolean[]{false, true}) {
                double sun = ambient(renderer, project, player).getPixelReader().getColor(60, 100).getBrightness();
                Tuning.apply(key -> key.equals("lighting.daylightThroughOpenings") ? "false" : null);
                engine.update(project);
                double dark = ambient(renderer, project, player).getPixelReader().getColor(60, 100).getBrightness();
                assertTrue(sun > dark + 0.2);
                Tuning.reset();
                engine.update(project);
                assertEquals(sun, ambient(renderer, project, player).getPixelReader().getColor(60, 100).getBrightness());
            }
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
