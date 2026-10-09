package dmmt.render;

import dmmt.service.Tuning;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Subtle screen-space weather. Particles are stateless: every position is a pure function of the particle
 * index and the clock, so the DM and player canvases show the same weather without sharing any state.
 * Opacities are deliberately low so the map always stays readable.
 */
public final class WeatherEffects {
    public static final double MIN_INTENSITY = 0.1;
    public static final double MAX_INTENSITY = 1.0;
    public static final double MIN_LIGHTNING_INTERVAL = 2;
    public static final double MAX_LIGHTNING_INTERVAL = 120;

    private static final double REFERENCE_AREA = 1920.0 * 1080.0;
    private static final double EDGE_MARGIN = 40;

    private WeatherEffects() {
    }

    /** Intensity of new maps (setting weather.defaultIntensity). */
    public static double defaultIntensity() {
        return Tuning.WEATHER_DEFAULT_INTENSITY.get();
    }

    public static double clampIntensity(double intensity) {
        return Math.max(MIN_INTENSITY, Math.min(MAX_INTENSITY, intensity));
    }

    public static double clampLightningInterval(double seconds) {
        return Math.max(MIN_LIGHTNING_INTERVAL, Math.min(MAX_LIGHTNING_INTERVAL, seconds));
    }

    /** One irregular strike per interval, shared by both views and independent of rain intensity. */
    public static double lightningFlash(double seconds, double intervalSeconds) {
        if (!Double.isFinite(seconds) || seconds <= 0 || !Double.isFinite(intervalSeconds)) {
            return 0;
        }
        double interval = clampLightningInterval(intervalSeconds);
        long cycle = (long) Math.floor(seconds / interval);
        return Math.max(flashInCycle(seconds, interval, cycle), flashInCycle(seconds, interval, cycle - 1));
    }

    private static double flashInCycle(double seconds, double interval, long cycle) {
        if (cycle < 0) {
            return 0;
        }
        double strike = (0.25 + 0.5 * unit((int) cycle, 83)) * interval;
        double age = seconds - cycle * interval - strike;
        double duration = Tuning.WEATHER_FLASH_DURATION.get();
        if (age < 0 || age >= duration) {
            return 0;
        }
        int seed = (int) cycle;
        int pulses = 3 + (int) (unit(seed, 84) * 3);
        double totalWeight = 0;
        for (int pulse = 0; pulse < pulses; pulse++) {
            totalWeight += 0.7 + unit(seed, 90 + pulse);
        }
        double start = 0;
        double flash = 0;
        for (int pulse = 0; pulse < pulses; pulse++) {
            double segment = duration * 0.4 * (0.7 + unit(seed, 90 + pulse)) / totalWeight;
            if (age >= start) {
                double strength = pulse == 0 ? 1 : 0.35 + 0.5 * unit(seed, 110 + pulse);
                double progress = (age - start) / (duration - start);
                double pulseBrightness = strength * (1 - progress) * (1 - progress);
                flash += (1 - flash) * pulseBrightness;
            }
            start += segment;
        }
        return flash;
    }

    public static void drawLightning(GraphicsContext gc, double width, double height, double flash) {
        if (flash <= 0) {
            return;
        }
        gc.save();
        gc.setGlobalAlpha(flash * Tuning.WEATHER_FLASH_OPACITY.get());
        gc.setFill(Color.web(Tuning.WEATHER_FLASH_COLOR.get()));
        gc.fillRect(0, 0, width, height);
        gc.restore();
    }

    /** Number of particles for the screen size; scales with intensity and area, at least one when active. */
    public static int particleCount(WeatherType type, double intensity, double width, double height) {
        if (type == null || type == WeatherType.NONE || type.maxParticles() <= 0) {
            return 0;
        }
        if (type == WeatherType.MIST) {
            return type.maxParticles();
        }
        double area = Math.max(0.3, Math.min(2.0, width * height / REFERENCE_AREA));
        return Math.max(1, (int) Math.round(type.maxParticles() * clampIntensity(intensity) * area));
    }

    /** Deterministic pseudo-random value in [0, 1) for particle {@code index} and property {@code salt}. */
    public static double unit(int index, int salt) {
        long z = (index * 0x9E3779B97F4A7C15L) ^ (salt * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** Wraps {@code value} into [0, span). */
    static double wrap(double value, double span) {
        double result = value % span;
        return result < 0 ? result + span : result;
    }

    public static void draw(GraphicsContext gc, WeatherType type, double intensity, double width, double height,
                            double seconds, boolean reduced) {
        if (type == null || type == WeatherType.NONE || width <= 0 || height <= 0) {
            return;
        }
        double level = clampIntensity(intensity);
        int count = particleCount(type, level, width, height);
        if (count <= 0) {
            return;
        }
        if (reduced && type != WeatherType.MIST) {
            count = Math.max(1, count / 2);
        }
        double scale = Math.max(0.6, Math.min(2.0, Math.min(width, height) / 900.0));
        double previousAlpha = gc.getGlobalAlpha();
        switch (type) {
            case RAIN, THUNDERSTORM -> drawRain(gc, type, count, width, height, seconds, scale);
            case SNOW -> drawSnow(gc, count, width, height, seconds, scale);
            case DUST -> drawDust(gc, count, width, height, seconds, scale);
            case EMBERS -> drawEmbers(gc, count, width, height, seconds, scale);
            case MIST -> drawMist(gc, level, width, height, seconds, scale);
            default -> {
            }
        }
        gc.setGlobalAlpha(previousAlpha);
    }

    private static void drawRain(GraphicsContext gc, WeatherType type, int count, double w, double h, double t, double scale) {
        gc.setStroke(type.color());
        gc.setLineWidth(Math.max(1.0, scale));
        gc.setGlobalAlpha(type.opacity());
        double spanX = w + 2 * EDGE_MARGIN;
        double spanY = h + 2 * EDGE_MARGIN;
        for (int i = 0; i < count; i++) {
            double speed = (650 + 350 * unit(i, 2)) * scale;
            double length = (9 + 12 * unit(i, 3)) * scale;
            double vx = speed * 0.22;
            double x = wrap(unit(i, 1) * spanX + vx * t, spanX) - EDGE_MARGIN;
            double y = wrap(unit(i, 4) * spanY + speed * t, spanY) - EDGE_MARGIN;
            double norm = Math.hypot(vx, speed);
            gc.strokeLine(x, y, x - length * vx / norm, y - length * speed / norm);
        }
    }

    private static void drawSnow(GraphicsContext gc, int count, double w, double h, double t, double scale) {
        gc.setFill(WeatherType.SNOW.color());
        gc.setGlobalAlpha(WeatherType.SNOW.opacity());
        double spanX = w + 2 * EDGE_MARGIN;
        double spanY = h + 2 * EDGE_MARGIN;
        for (int i = 0; i < count; i++) {
            double radius = (1.1 + 1.7 * unit(i, 3)) * scale;
            double fall = (22 + 34 * unit(i, 2)) * scale;
            double sway = Math.sin(t * (0.4 + unit(i, 5)) + unit(i, 6) * Math.PI * 2) * 16 * scale;
            double x = wrap(unit(i, 1) * spanX, spanX) - EDGE_MARGIN + sway;
            double y = wrap(unit(i, 4) * spanY + fall * t, spanY) - EDGE_MARGIN;
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private static void drawDust(GraphicsContext gc, int count, double w, double h, double t, double scale) {
        gc.setFill(WeatherType.DUST.color());
        double opacity = WeatherType.DUST.opacity();
        for (int i = 0; i < count; i++) {
            double radius = (0.8 + 1.2 * unit(i, 3)) * scale;
            double vx = (5 + 8 * unit(i, 2)) * scale;
            double vy = -(2 + 5 * unit(i, 7)) * scale;
            double x = wrap(unit(i, 1) * w + vx * t, w);
            double y = wrap(unit(i, 4) * h + vy * t, h);
            double twinkle = 0.5 + 0.5 * Math.sin(t * (0.3 + unit(i, 5)) + unit(i, 6) * Math.PI * 2);
            gc.setGlobalAlpha(opacity * (0.125 + 0.875 * twinkle));
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private static void drawEmbers(GraphicsContext gc, int count, double w, double h, double t, double scale) {
        gc.setFill(WeatherType.EMBERS.color());
        double opacity = WeatherType.EMBERS.opacity();
        double spanY = h + 2 * EDGE_MARGIN;
        for (int i = 0; i < count; i++) {
            double radius = (1.0 + 1.5 * unit(i, 3)) * scale;
            double rise = (28 + 42 * unit(i, 2)) * scale;
            double sway = Math.sin(t * (0.6 + unit(i, 5)) + unit(i, 6) * Math.PI * 2) * 20 * scale;
            double x = wrap(unit(i, 1) * w + sway, w);
            double y = spanY - wrap(unit(i, 4) * spanY + rise * t, spanY) - EDGE_MARGIN;
            double flicker = 0.5 + 0.5 * Math.sin(t * (2 + 3 * unit(i, 7)) + unit(i, 6) * 9);
            gc.setGlobalAlpha(opacity * (0.2 + 0.8 * flicker));
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private static void drawMist(GraphicsContext gc, double level, double w, double h, double t, double scale) {
        int blobs = WeatherType.MIST.maxParticles();
        double alpha = WeatherType.MIST.opacity() * (1.0 / 3.0 + 2.0 / 3.0 * level);
        Color color = WeatherType.MIST.color();
        double longest = Math.max(w, h);
        for (int i = 0; i < blobs; i++) {
            double radius = (0.22 + 0.22 * unit(i, 3)) * longest;
            double span = w + 2 * radius;
            double drift = (5 + 9 * unit(i, 2)) * scale;
            double x = wrap(unit(i, 1) * span + drift * t, span) - radius;
            double y = (0.1 + 0.8 * unit(i, 4)) * h + Math.sin(t * 0.05 + unit(i, 6) * Math.PI * 2) * 0.04 * h;
            RadialGradient gradient = new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha)),
                    new Stop(1, Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0)));
            gc.setGlobalAlpha(1.0);
            gc.setFill(gradient);
            gc.fillRect(x - radius, y - radius, radius * 2, radius * 2);
        }
    }
}
