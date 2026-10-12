package dmmt.render;

import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightMapBudgetTest {
    @AfterEach
    void reset() {
        PerformanceMode.setEnabled(false);
        Tuning.reset();
    }

    @Test
    void roundedLightMapsRespectBudgetsAtAllCanvasSizes() {
        for (double[] size : new double[][]{{1920, 1080}, {3840, 2160}, {1919.5, 1079.5},
                {1, 50000}, {50000, 1}, {0, 0}}) {
            int normal = CanvasMapRenderer.lightMapScaleFor(size[0], size[1]);
            assertFits(size, normal, 20000);
            PerformanceMode.setEnabled(true);
            int reduced = CanvasMapRenderer.lightMapScaleFor(size[0], size[1]);
            assertFits(size, reduced, 5000);
            assertTrue(reduced >= normal);
            assertEquals(20000, Tuning.LIGHT_MAP_MAX_PIXELS.get());
            assertEquals(4, Tuning.LIGHT_MAP_SCALE.get());
            PerformanceMode.setEnabled(false);
            assertEquals(normal, CanvasMapRenderer.lightMapScaleFor(size[0], size[1]));
        }
    }

    @Test
    void performanceBudgetNeverReplacesAStricterNormalBudget() {
        Tuning.apply(key -> switch (key) {
            case "lighting.lightMapMaxPixels" -> "1000";
            case "performance.lightMapMaxPixels" -> "8000000";
            default -> null;
        });
        PerformanceMode.setEnabled(true);
        assertEquals(1000, PerformanceMode.lightMapMaxPixels());
        double[] size = {3840, 2160};
        assertFits(size, CanvasMapRenderer.lightMapScaleFor(size[0], size[1]), 1000);
    }

    private static void assertFits(double[] size, int scale, int budget) {
        double pixels = Math.max(1, Math.ceil(size[0] / scale)) * Math.max(1, Math.ceil(size[1] / scale));
        assertTrue(pixels <= budget, pixels + " pixels exceeds budget " + budget);
    }
}
