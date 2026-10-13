package dmmt.render;

import dmmt.FxTestSupport;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherParticleRenderTest {
    private static WritableImage snowSprite;
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void particleCandidatesPreserveCoverageAndColor() throws Exception {
        onFx(() -> {
            Tuning.reset();
            for (boolean snow : new boolean[]{false, true}) {
                WritableImage original = snapshot(snow, false, 2.3);
                WritableImage candidate = snapshot(snow, true, 2.3);
                double originalAlpha = alphaSum(original);
                double candidateAlpha = alphaSum(candidate);
                double ratio = candidateAlpha / originalAlpha;
                assertTrue(ratio >= 0.9 && ratio <= 1.1,
                        "Particle coverage must remain within 10%: " + ratio);
            }
            if (Boolean.getBoolean("dmmt.weatherBenchmark")) {
                for (boolean snow : new boolean[]{false, true}) {
                    System.out.printf("%s snapshot median, original/candidate: %.3f/%.3f ms%n",
                            snow ? "snow" : "rain", benchmark(snow, false), benchmark(snow, true));
                }
            }
        });
    }

    private static WritableImage snapshot(boolean snow, boolean candidate, double time) {
        Canvas canvas = new Canvas(1920, 1080);
        draw(canvas.getGraphicsContext2D(), snow, candidate, time);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }

    private static void draw(GraphicsContext gc, boolean snow, boolean candidate, double time) {
        if (snow) {
            if (candidate) drawSnowSprites(gc, 610, time);
            else WeatherEffects.drawSnow(gc, 610, 1920, 1080, time, 1.2);
        } else {
            if (candidate) drawRainBatched(gc, 600, time);
            else WeatherEffects.drawRain(gc, WeatherType.RAIN, 600, 1920, 1080, time, 1.2);
        }
    }

    private static void drawRainBatched(GraphicsContext gc, int count, double time) {
        gc.setStroke(WeatherType.RAIN.color());
        gc.setLineWidth(1.2);
        gc.setGlobalAlpha(WeatherType.RAIN.opacity());
        double direction = Math.sqrt(1 + 0.22 * 0.22);
        gc.beginPath();
        for (int i = 0; i < count; i++) {
            double speed = (650 + 350 * WeatherEffects.unit(i, 2)) * 1.2;
            double length = (9 + 12 * WeatherEffects.unit(i, 3)) * 1.2;
            double x = WeatherEffects.wrap(WeatherEffects.unit(i, 1) * 2000 + speed * 0.22 * time, 2000) - 40;
            double y = WeatherEffects.wrap(WeatherEffects.unit(i, 4) * 1160 + speed * time, 1160) - 40;
            gc.moveTo(x, y);
            gc.lineTo(x - length * 0.22 / direction, y - length / direction);
        }
        gc.stroke();
    }

    private static void drawSnowSprites(GraphicsContext gc, int count, double time) {
        Color color = WeatherType.SNOW.color();
        if (snowSprite == null) {
            int size = 32;
            snowSprite = new WritableImage(size, size);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double distance = Math.hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0);
                    double alpha = Math.max(0, Math.min(1, size / 2.0 - distance + 0.5)) * color.getOpacity();
                    snowSprite.getPixelWriter().setColor(x, y,
                            Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
                }
            }
        }
        gc.setGlobalAlpha(WeatherType.SNOW.opacity());
        for (int i = 0; i < count; i++) {
            double radius = (1.1 + 1.7 * WeatherEffects.unit(i, 3)) * 1.2;
            double fall = (22 + 34 * WeatherEffects.unit(i, 2)) * 1.2;
            double sway = Math.sin(time * (0.4 + WeatherEffects.unit(i, 5))
                    + WeatherEffects.unit(i, 6) * Math.PI * 2) * 16 * 1.2;
            double x = WeatherEffects.wrap(WeatherEffects.unit(i, 1) * 2000, 2000) - 40 + sway;
            double y = WeatherEffects.wrap(WeatherEffects.unit(i, 4) * 1160 + fall * time, 1160) - 40;
            gc.drawImage(snowSprite, x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private static double alphaSum(WritableImage image) {
        double alpha = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                alpha += image.getPixelReader().getArgb(x, y) >>> 24;
            }
        }
        return alpha;
    }

    private static double benchmark(boolean snow, boolean candidate) {
        Canvas canvas = new Canvas(1920, 1080);
        WritableImage output = new WritableImage(1920, 1080);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        long[] samples = new long[60];
        for (int i = -30; i < samples.length; i++) {
            long start = System.nanoTime();
            canvas.getGraphicsContext2D().clearRect(0, 0, 1920, 1080);
            draw(canvas.getGraphicsContext2D(), snow, candidate, 10 + i / 30.0);
            canvas.snapshot(parameters, output);
            if (i >= 0) samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2] / 1e6;
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(60, TimeUnit.SECONDS);
    }
}
