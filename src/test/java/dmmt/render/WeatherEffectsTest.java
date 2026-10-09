package dmmt.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import dmmt.service.Tuning;

class WeatherEffectsTest {
    @AfterEach
    void resetTuning() {
        Tuning.reset();
    }

    @Test
    void thunderstormIsAppendedAndRainScalesIndependentlyOfLightning() {
        assertEquals(WeatherType.THUNDERSTORM, WeatherType.from("thunderstorm"));
        assertEquals(6, WeatherType.THUNDERSTORM.ordinal());
        assertEquals(WeatherEffects.particleCount(WeatherType.RAIN, 0.2, 1920, 1080),
                WeatherEffects.particleCount(WeatherType.THUNDERSTORM, 0.2, 1920, 1080));
        assertTrue(WeatherEffects.particleCount(WeatherType.THUNDERSTORM, 1, 1920, 1080)
                > WeatherEffects.particleCount(WeatherType.THUNDERSTORM, 0.2, 1920, 1080));
        Tuning.apply(key -> key.equals("weather.thunderstorm.particles") ? "0" : null);
        double strike = (0.25 + 0.5 * WeatherEffects.unit(0, 83)) * 12;
        assertEquals(0, WeatherEffects.particleCount(WeatherType.THUNDERSTORM, 1, 1920, 1080));
        assertEquals(1, WeatherEffects.lightningFlash(strike, 12), 1e-9);
    }

    @Test
    void lightningIsDeterministicOccasionalAndCracklesWithoutRainInput() {
        assertEquals(7, Tuning.WEATHER_LIGHTNING_INTERVAL.get());
        double interval = 12;
        double strike = (0.25 + 0.5 * WeatherEffects.unit(0, 83)) * interval;
        double duration = Tuning.WEATHER_FLASH_DURATION.get();
        assertEquals(0, WeatherEffects.lightningFlash(0, interval));
        assertEquals(0, WeatherEffects.lightningFlash(strike - 0.01, interval));
        assertEquals(1, WeatherEffects.lightningFlash(strike, interval), 1e-9);
        assertEquals(0, WeatherEffects.lightningFlash(strike + duration + 0.01, interval));
        for (double time = 0; time < 120; time += 0.1) {
            double flash = WeatherEffects.lightningFlash(time, interval);
            assertTrue(flash >= 0 && flash <= 1);
            assertEquals(flash, WeatherEffects.lightningFlash(time, interval));
        }
        for (int cycle = 0; cycle < 20; cycle++) {
            double start = (cycle + 0.25 + 0.5 * WeatherEffects.unit(cycle, 83)) * interval;
            int flashes = 1;
            double previous = 1;
            for (int sample = 0; sample <= 1000; sample++) {
                double flash = WeatherEffects.lightningFlash(start + duration * sample / 1000, interval);
                if (flash > previous + 0.01) {
                    flashes++;
                    assertTrue(sample < 400, "Return flashes should be clustered in the first 40% of the burst");
                }
                if (sample > 400 && sample < 1000) {
                    assertTrue(flash > 0, "The fading glow should linger until the burst ends");
                    assertTrue(flash <= previous, "The tail should fade smoothly without further flashes");
                }
                previous = flash;
            }
            assertTrue(flashes >= 3 && flashes <= 5, "Strike must have 3-5 distinct flashes: " + flashes);
            assertTrue(WeatherEffects.lightningFlash(start + duration * 0.6, interval) > 0.1,
                    "The fade-out should remain visible well after the clustered flashes");
        }
    }

    @Test
    void shorterIntervalsProduceMoreStrikesAndLongFlashesCrossCycleBoundaries() {
        int fast = 0;
        int slow = 0;
        boolean wasFast = false;
        boolean wasSlow = false;
        for (double time = 0; time < 120; time += 0.01) {
            boolean isFast = WeatherEffects.lightningFlash(time, 2) > 0;
            boolean isSlow = WeatherEffects.lightningFlash(time, 12) > 0;
            if (isFast && !wasFast) fast++;
            if (isSlow && !wasSlow) slow++;
            wasFast = isFast;
            wasSlow = isSlow;
        }
        assertTrue(fast > slow);
        Tuning.apply(key -> key.equals(Tuning.WEATHER_FLASH_DURATION.key()) ? "1.5" : null);
        double strike = (0.25 + 0.5 * WeatherEffects.unit(0, 83)) * 2;
        boolean returnFlashAfterBoundary = false;
        for (double time = 2; time < strike + 1.5; time += 0.001) {
            returnFlashAfterBoundary |= WeatherEffects.lightningFlash(time, 2) > 0;
        }
        assertTrue(returnFlashAfterBoundary, "A burst starting in the previous interval must finish normally");
        assertTrue(WeatherEffects.lightningFlash(strike, 2) > 0);
        assertEquals(2, WeatherEffects.clampLightningInterval(-1));
        assertEquals(120, WeatherEffects.clampLightningInterval(500));
    }

    @Test
    void unknownTypeMeansNone() {
        assertEquals(WeatherType.NONE, WeatherType.from(null));
        assertEquals(WeatherType.NONE, WeatherType.from("hail"));
        assertEquals(WeatherType.RAIN, WeatherType.from("rain"));
        assertEquals("snow", WeatherType.SNOW.key());
    }

    @Test
    void particleCountScalesWithIntensityAndIsZeroForNone() {
        assertEquals(0, WeatherEffects.particleCount(WeatherType.NONE, 1, 1920, 1080));
        int low = WeatherEffects.particleCount(WeatherType.RAIN, 0.2, 1920, 1080);
        int high = WeatherEffects.particleCount(WeatherType.RAIN, 1.0, 1920, 1080);
        assertTrue(low >= 1 && low < high);
        assertTrue(high <= WeatherType.RAIN.maxParticles());
    }

    @Test
    void unitIsDeterministicAndInRange() {
        for (int i = 0; i < 500; i++) {
            double v = WeatherEffects.unit(i, 3);
            assertTrue(v >= 0 && v < 1);
            assertEquals(v, WeatherEffects.unit(i, 3));
        }
        assertTrue(WeatherEffects.unit(1, 1) != WeatherEffects.unit(2, 1));
    }

    @Test
    void wrapStaysInsideSpan() {
        assertEquals(10, WeatherEffects.wrap(-90, 100), 1e-9);
        assertEquals(5, WeatherEffects.wrap(105, 100), 1e-9);
    }

    @Test
    void intensityIsClamped() {
        assertEquals(WeatherEffects.MIN_INTENSITY, WeatherEffects.clampIntensity(-1));
        assertEquals(WeatherEffects.MAX_INTENSITY, WeatherEffects.clampIntensity(5));
    }
}
