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
        assertEquals(2, WeatherType.THUNDERSTORM.ordinal());
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

    @Test
    void mistTexturesHaveSmokyVariationAndTransparentBorders() {
        int size = 64;
        int[] pixels = WeatherEffects.mistTexturePixels(0xDBE3ED, 0, size);
        int[] variant = WeatherEffects.mistTexturePixels(0xDBE3ED, 1, size);
        int[] repeat = WeatherEffects.mistTexturePixels(0xDBE3ED, 0, size);
        int min = 255;
        int max = 0;
        int variantsDiffer = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = pixels[y * size + x];
                assertEquals(p, repeat[y * size + x]);
                assertEquals(0xDBE3ED, p & 0xFFFFFF);
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    assertEquals(0, p >>> 24, "Texture borders must be fully feathered");
                }
                if (x >= size / 3 && x < size * 2 / 3 && y >= size / 3 && y < size * 2 / 3) {
                    min = Math.min(min, p >>> 24);
                    max = Math.max(max, p >>> 24);
                    if (p != variant[y * size + x]) variantsDiffer++;
                }
            }
        }
        assertTrue(max - min > 60, "Smoke must have textured dense and thin patches, not a flat haze");
        assertTrue(variantsDiffer > 100, "Banks should not all repeat the same texture");
        assertTrue(Math.abs((pixels[24 * size + 32] >>> 24) - (pixels[32 * size + 24] >>> 24)) > 10,
                "Equal-radius points should differ, unlike the old radial gradient");
    }

    @Test
    void mistCoverageIsComparableAtEdgesAndCentreAcrossSizesAndTimes() {
        int size = 64;
        int[][] textures = new int[4][];
        for (int i = 0; i < textures.length; i++) {
            textures[i] = WeatherEffects.mistTexturePixels(0xDBE3ED, i, size);
        }
        for (double[] viewport : new double[][]{{1920, 1080}, {800, 1200}, {3440, 1440}, {640, 360}}) {
            double w = viewport[0];
            double h = viewport[1];
            double[] total = new double[5]; // Left, right, top, bottom, centre.
            for (int time = 0; time < 24; time++) {
                WeatherEffects.MistBank[] banks = new WeatherEffects.MistBank[WeatherType.MIST.maxParticles()];
                for (int i = 0; i < banks.length; i++) {
                    banks[i] = WeatherEffects.mistBank(i, w, h, time * 17, 1);
                    assertEquals(banks[i], WeatherEffects.mistBank(i, w, h, time * 17, 1));
                }
                for (int a = 0; a < 12; a++) {
                    for (int b = 0; b < 12; b++) {
                        double u = (a + 0.5) / 12;
                        double v = (b + 0.5) / 12;
                        total[0] += mistOpacity(0.15 * u * w, v * h, w, h, banks, textures, size);
                        total[1] += mistOpacity((0.85 + 0.15 * u) * w, v * h, w, h, banks, textures, size);
                        total[2] += mistOpacity(u * w, 0.15 * v * h, w, h, banks, textures, size);
                        total[3] += mistOpacity(u * w, (0.85 + 0.15 * v) * h, w, h, banks, textures, size);
                        total[4] += mistOpacity((0.3 + 0.4 * u) * w, (0.3 + 0.4 * v) * h,
                                w, h, banks, textures, size);
                    }
                }
                assertEquals(mistOpacity(0, h / 2, w, h, banks, textures, size),
                        mistOpacity(w, h / 2, w, h, banks, textures, size), 1e-9);
                assertEquals(mistOpacity(w / 2, 0, w, h, banks, textures, size),
                        mistOpacity(w / 2, h, w, h, banks, textures, size), 1e-9);
            }
            for (int edge = 0; edge < 4; edge++) {
                double ratio = total[edge] / total[4];
                assertTrue(ratio >= 0.85 && ratio <= 1.15, "Edge/centre coverage ratio: " + ratio);
            }
        }
    }

    private static double mistOpacity(double x, double y, double w, double h,
                                      WeatherEffects.MistBank[] banks, int[][] textures, int size) {
        double opacity = 0;
        for (int i = 0; i < banks.length; i++) {
            WeatherEffects.MistBank bank = banks[i];
            double dx = WeatherEffects.wrap(x - bank.x() + w / 2, w) - w / 2;
            double dy = WeatherEffects.wrap(y - bank.y() + h / 2, h) - h / 2;
            double u = (dx / bank.radiusX() + 1) / 2;
            double v = (dy / bank.radiusY() + 1) / 2;
            if (u < 0 || u >= 1 || v < 0 || v >= 1) continue;
            double alpha = (textures[i % textures.length][(int) (v * size) * size + (int) (u * size)] >>> 24)
                    / 255.0 * WeatherType.MIST.opacity();
            opacity += (1 - opacity) * alpha;
        }
        return opacity;
    }

    @Test
    void mistBankCountRemainsIndependentOfIntensityAndZeroDisablesIt() {
        int banks = WeatherType.MIST.maxParticles();
        assertEquals(banks, WeatherEffects.particleCount(WeatherType.MIST, 0.1, 640, 360));
        assertEquals(banks, WeatherEffects.particleCount(WeatherType.MIST, 1, 1920, 1080));
        Tuning.apply(key -> key.equals("weather.mist.particles") ? "0" : null);
        assertEquals(0, WeatherEffects.particleCount(WeatherType.MIST, 1, 1920, 1080));
    }
}
