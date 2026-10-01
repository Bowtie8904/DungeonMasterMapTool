package dmmt.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogShadingTest {

    private static BitSet block(int cols, int x0, int y0, int x1, int y1) {
        BitSet bits = new BitSet();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                bits.set(y * cols + x);
            }
        }
        return bits;
    }

    @Test
    void radiusZeroGivesHardEdges() {
        float[] fog = FogShading.fogOpacity(block(10, 3, 3, 6, 6), 10, 10, 0);

        assertEquals(0f, fog[4 * 10 + 4]);
        assertEquals(1f, fog[0]);
        assertEquals(1f, fog[2 * 10 + 3]);
    }

    @Test
    void softEdgesFadeInsideTheRevealedAreaAndNeverLeakIntoTheFog() {
        BitSet revealed = block(40, 10, 10, 29, 29);
        float[] fog = FogShading.fogOpacity(revealed, 40, 40, 4);

        for (int i = 0; i < fog.length; i++) {
            if (!revealed.get(i)) {
                assertEquals(1f, fog[i], "fogged cell " + i + " must stay opaque");
            }
        }
        assertEquals(0f, fog[20 * 40 + 20], 1e-4);
        float edge = fog[20 * 40 + 10];
        float nearEdge = fog[20 * 40 + 12];
        assertTrue(edge > 0.5f && edge < 1f, "edge cell should be mostly fogged: " + edge);
        assertTrue(nearEdge < edge && nearEdge > 0f);
        assertTrue(fog[20 * 40 + 14] < nearEdge);
    }

    @Test
    void diagonalStaircaseEdgeGetsASmoothRamp() {
        BitSet revealed = new BitSet();
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col <= row; col++) {
                revealed.set(row * 30 + col);
            }
        }
        float[] fog = FogShading.fogOpacity(revealed, 30, 30, 4);

        // Moving away from the staircase edge perpendicular to it, the opacity decreases monotonically.
        float previous = 2f;
        for (int k = 0; k < 6; k++) {
            float value = fog[(20 + k) * 30 + (10 - k)];
            assertTrue(value <= previous + 1e-6f);
            previous = value;
        }
    }
    @Test
    void fullyRevealedMaskStaysClearAtTheBorder() {
        BitSet all = new BitSet();
        all.set(0, 100);

        float[] fog = FogShading.fogOpacity(all, 10, 10, 3);

        for (float value : fog) {
            assertEquals(0f, value, 1e-4);
        }
    }

    @Test
    void advanceMovesTowardsTheTargetInSteps() {
        float[] shown = {1f, 0f, 0.5f};
        float[] target = {0f, 1f, 0.5f};

        int[] range = FogShading.advance(shown, target, 0.4f);

        assertNotNull(range);
        assertEquals(0, range[0]);
        assertEquals(1, range[1]);
        assertArrayEquals(new float[]{0.6f, 0.4f, 0.5f}, shown, 1e-6f);
        FogShading.advance(shown, target, 0.4f);
        FogShading.advance(shown, target, 0.4f);
        assertTrue(FogShading.reached(shown, target));
        assertNull(FogShading.advance(shown, target, 0.4f));
    }

    @Test
    void separateRevealAndHideSpeedsAndLinearEasingMatchesStepAdvance() {
        float[] shown = {1f, 0f};
        float[] oldTarget = shown.clone();
        float[] target = {0f, 1f};
        float[] from = shown.clone();
        float[] progress = {1f, 1f};
        FogShading.retarget(shown, oldTarget, target, from, progress);

        FogShading.advance(shown, target, from, progress, 0.5f, 0.25f, false);

        assertArrayEquals(new float[]{0.5f, 0.25f}, shown, 1e-6f);
        FogShading.advance(shown, target, from, progress, 0.5f, 0.25f, false);
        assertEquals(0f, shown[0]);
        assertEquals(0.5f, shown[1], 1e-6f);
    }

    @Test
    void smoothEasingStartsSlowAndTakesTheSameTime() {
        float[] shown = {1f};
        float[] target = {0f};
        float[] from = {1f};
        float[] progress = {0f};

        FogShading.advance(shown, target, from, progress, 0.25f, 0.25f, true);
        assertTrue(shown[0] > 0.75f, "eased fade starts slower than linear: " + shown[0]);
        for (int i = 0; i < 3; i++) {
            FogShading.advance(shown, target, from, progress, 0.25f, 0.25f, true);
        }
        assertTrue(FogShading.reached(shown, target));
    }

    @Test
    void downsampledOpacityClearsPixelsWithMostlyRevealedCells() {
        BitSet revealed = block(4, 0, 0, 1, 1);
        revealed.set(2);
        revealed.set(3);

        float[] fog = FogShading.downsampledOpacity(revealed, 4, 4, 2);

        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, fog);
    }

    @Test
    void pixelEncodesOpacityAsAlpha() {
        assertEquals(0, FogShading.pixel(0f));
        assertEquals(0xFF000000, FogShading.pixel(1f));
        assertEquals(0x80000000, FogShading.pixel(0.5f));
    }
}
