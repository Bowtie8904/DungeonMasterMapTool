package dmmt.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherEffectsTest {
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
