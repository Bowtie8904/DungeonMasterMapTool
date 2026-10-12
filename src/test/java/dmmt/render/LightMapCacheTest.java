package dmmt.render;

import dmmt.FxTestSupport;
import dmmt.lighting.LightingEngine;
import dmmt.model.DmProject;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LightMapCacheTest {
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
    void staticSamplesAndPolygonsSurviveDynamicLightChangesAndInvalidateWithTheirInputs() throws Exception {
        onFx(() -> {
            CanvasMapRenderer.setLightFlickerEnabled(false);
            DmProject project = project();
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            Canvas canvas = new Canvas(400, 300);
            DmProject.CameraState camera = camera();
            render(renderer, canvas, project, camera);
            long transforms = renderer.polygonTransformCount(true);
            assertTrue(transforms > 0);
            assertEquals(1, renderer.environmentSampleCount(true));
            assertEquals(1, renderer.lightningImageUpdateCount(true));
            project.getLighting().getLights().getFirst().setIntensity(0.3);
            render(renderer, canvas, project, camera);
            assertEquals(transforms, renderer.polygonTransformCount(true));
            assertEquals(1, renderer.environmentSampleCount(true));
            assertEquals(1, renderer.lightningImageUpdateCount(true));
            CanvasMapRenderer.setLightFlickerEnabled(true);
            render(renderer, canvas, project, camera);
            assertEquals(transforms, renderer.polygonTransformCount(true), "Flicker reuses transformed geometry");
            assertEquals(1, renderer.environmentSampleCount(true));
            assertEquals(1, renderer.lightningImageUpdateCount(true), "Flicker does not rewrite lightning");

            Tuning.apply(key -> key.equals(Tuning.WEATHER_FLASH_COLOR.key()) ? "#FF0000" : null);
            render(renderer, canvas, project, camera);
            assertEquals(1, renderer.environmentSampleCount(true), "Color does not resample geometry");
            assertEquals(2, renderer.lightningImageUpdateCount(true));

            camera.setX(510);
            render(renderer, canvas, project, camera);
            assertEquals(2, renderer.environmentSampleCount(true));
            assertTrue(renderer.polygonTransformCount(true) > transforms);
            long movedTransforms = renderer.polygonTransformCount(true);
            project.getLighting().getLights().getFirst().setX(520);
            engine.update(project);
            render(renderer, canvas, project, camera);
            assertEquals(2, renderer.environmentSampleCount(true), "Moving a light does not change rooms");
            assertTrue(renderer.polygonTransformCount(true) > movedTransforms);

            project.getWalls().add(DmProject.WallSegment.builder().x1(450).y1(100).x2(450).y2(900).build());
            engine.update(project);
            render(renderer, canvas, project, camera);
            assertEquals(3, renderer.environmentSampleCount(true));
            Tuning.apply(key -> key.equals("lighting.daylightThroughOpenings") ? "false" : null);
            engine.update(project);
            render(renderer, canvas, project, camera);
            assertEquals(4, renderer.environmentSampleCount(true));
            canvas.setWidth(420);
            render(renderer, canvas, project, camera);
            assertEquals(5, renderer.environmentSampleCount(true));
            renderer.renderAmbientLight(new Canvas(400, 300).getGraphicsContext2D(), project, 400, 300, camera, false);
            assertEquals(1, renderer.environmentSampleCount(false), "Each view has its own environment");
            DmProject replacement = project();
            engine.update(replacement);
            render(renderer, canvas, replacement, camera);
            assertEquals(6, renderer.environmentSampleCount(true), "Switching projects invalidates static samples");
        });
    }

    @Test
    void openingsStormSettingsAndPerformanceModeInvalidateStaticSamples() throws Exception {
        onFx(() -> {
            CanvasMapRenderer.setLightFlickerEnabled(false);
            DmProject project = project();
            project.getWalls().addAll(java.util.List.of(
                    wall(200, 200, 800, 200), wall(800, 200, 800, 800),
                    wall(800, 800, 200, 800), wall(200, 800, 200, 550),
                    wall(200, 450, 200, 200)));
            project.getInteractables().add(DmProject.Interactable.builder().type("window").state("closed")
                    .x1(200).y1(450).x2(200).y2(550).build());
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            assertTrue(engine.getIndoorLighting().hasRooms());
            CanvasMapRenderer renderer = new CanvasMapRenderer(engine);
            Canvas canvas = new Canvas(400, 300);
            render(renderer, canvas, project, camera());
            assertEquals(1, renderer.environmentSampleCount(true));
            project.getInteractables().getFirst().setState("open");
            engine.update(project);
            render(renderer, canvas, project, camera());
            assertEquals(2, renderer.environmentSampleCount(true));
            assertEquals(2, renderer.lightningImageUpdateCount(true));
            Tuning.apply(key -> key.equals("weather.thunderstorm.indoors") ? "true" : null);
            render(renderer, canvas, project, camera());
            assertEquals(3, renderer.environmentSampleCount(true));
            assertEquals(3, renderer.lightningImageUpdateCount(true));
            PerformanceMode.setEnabled(true);
            engine.update(project);
            render(renderer, canvas, project, camera());
            assertEquals(4, renderer.environmentSampleCount(true));
            PerformanceMode.setEnabled(false);
            engine.update(project);
            render(renderer, canvas, project, camera());
            assertEquals(5, renderer.environmentSampleCount(true));
            project.getLighting().setTimeOfDayPreset("DAY");
            render(renderer, canvas, project, camera());
            assertEquals(5, renderer.environmentSampleCount(true), "Preset changes reuse classification and daylight");
        });
    }

    private static DmProject.WallSegment wall(double x1, double y1, double x2, double y2) {
        return DmProject.WallSegment.builder().x1(x1).y1(y1).x2(x2).y2(y2).build();
    }

    @Test
    void serialAndParallelLightingProduceIdenticalPixels() throws Exception {
        onFx(() -> {
            CanvasMapRenderer.setLightFlickerEnabled(false);
            Tuning.apply(key -> key.equals("lighting.lightMapMaxPixels") ? "200000" : null);
            DmProject project = project();
            LightingEngine engine = new LightingEngine();
            engine.update(project);
            CanvasMapRenderer serial = forcedRenderer(engine, false);
            CanvasMapRenderer parallel = forcedRenderer(engine, true);
            Canvas a = new Canvas(800, 600);
            Canvas b = new Canvas(800, 600);
            for (String preset : new String[]{"DAY", "NIGHT"}) {
                project.getLighting().setTimeOfDayPreset(preset);
                for (boolean player : new boolean[]{false, true}) {
                    serial.renderAmbientLight(a.getGraphicsContext2D(), project, 800, 600, camera(), player);
                    parallel.renderAmbientLight(b.getGraphicsContext2D(), project, 800, 600, camera(), player);
                    assertSamePixels(snapshot(a), snapshot(b));
                    serial.render(a.getGraphicsContext2D(), project, null, 800, 600, camera(), player, null);
                    parallel.render(b.getGraphicsContext2D(), project, null, 800, 600, camera(), player, null);
                    assertSamePixels(snapshot(a), snapshot(b));
                }
            }
        });
    }

    @Test
    void benchmarkLightingSchedulingWhenRequested() throws Exception {
        if (!Boolean.getBoolean("dmmt.lightingBenchmark")) return;
        onFx(() -> {
            CanvasMapRenderer.setLightFlickerEnabled(false);
            for (int lightCount : new int[]{0, 1, 19}) {
                DmProject project = project();
                project.getLighting().getLights().subList(lightCount, 19).clear();
                LightingEngine engine = new LightingEngine();
                engine.update(project);
                for (int side : new int[]{32, 64, 128, 256, 512}) {
                    Tuning.apply(key -> key.equals("lighting.lightMapMaxPixels") ? "8000000" : null);
                    double parallel = benchmark(engine, project, side, true);
                    double serial = benchmark(engine, project, side, false);
                    System.out.printf("%d lights, raster %,d pixels, serial/parallel median CPU: %.3f/%.3f ms%n",
                            lightCount, side * side / 16, serial, parallel);
                }
            }
        });
    }

    private static double benchmark(LightingEngine engine, DmProject project, int side, boolean parallel) {
        CanvasMapRenderer renderer = forcedRenderer(engine, parallel);
        Canvas canvas = new Canvas(side, side);
        WritableImage output = new WritableImage(side, side);
        long[] samples = new long[80];
        for (int i = -100; i < samples.length; i++) {
            if (project.getLighting().getLights().isEmpty()) {
                project.getLighting().putAmbientBrightness("NIGHT", 0.4 + (i & 1) * 0.1);
            } else {
                project.getLighting().getLights().getFirst().setIntensity(0.4 + (i & 1) * 0.1);
            }
            long start = System.nanoTime();
            render(renderer, canvas, project, camera());
            if (i >= 0) samples[i] = System.nanoTime() - start;
            canvas.snapshot(null, output);
        }
        Arrays.sort(samples);
        return samples[samples.length / 2] / 1e6;
    }

    private static CanvasMapRenderer forcedRenderer(LightingEngine engine, boolean parallel) {
        return new CanvasMapRenderer(engine) {
            @Override
            protected boolean parallelLighting(int pixels) {
                return parallel;
            }
        };
    }

    private static DmProject project() {
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().width(1000).height(1000).build());
        project.getLighting().setTimeOfDayPreset("NIGHT");
        for (int i = 0; i < 19; i++) {
            project.getLighting().getLights().add(DmProject.LightSource.builder().id("light-" + i)
                    .x(300 + i % 5 * 80).y(300 + i / 5 * 80).range(400)
                    .color(i % 2 == 0 ? "#FF8844" : "#4488FF")
                    .flicker(DmProject.Flicker.builder().enabled(true).strength(0.2).speed(1).build()).build());
        }
        return project;
    }

    private static DmProject.CameraState camera() {
        return DmProject.CameraState.builder().x(500).y(500).zoom(1).build();
    }

    private static void render(CanvasMapRenderer renderer, Canvas canvas, DmProject project, DmProject.CameraState camera) {
        renderer.renderAmbientLight(canvas.getGraphicsContext2D(), project, canvas.getWidth(), canvas.getHeight(), camera, true);
    }

    private static WritableImage snapshot(Canvas canvas) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }

    private static void assertSamePixels(WritableImage a, WritableImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertEquals(a.getPixelReader().getArgb(x, y), b.getPixelReader().getArgb(x, y));
            }
        }
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(120, TimeUnit.SECONDS);
    }
}
