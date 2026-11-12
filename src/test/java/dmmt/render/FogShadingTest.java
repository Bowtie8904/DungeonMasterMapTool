package dmmt.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogShadingTest {

    @Test
    void tiledUpdatesMatchFullShadingForMovingAndOverlappingReveals() {
        int cols = 263, rows = 201, tileSize = 32;
        var old = block(cols, 10, 15, 190, 160);
        var next = block(cols, 16, 20, 195, 164);
        old.or(block(cols, 145, 70, 250, 190));
        next.or(block(cols, 145, 70, 250, 190));
        next.flip(63 * cols + 63);
        next.flip(64 * cols + 64);
        for (int radius : new int[]{0, 3, 10}) {
            float[] updated = FogShading.fogOpacity(old, cols, rows, radius);
            float[] expected = FogShading.fogOpacity(next, cols, rows, radius);
            BitSet diff = (BitSet) old.clone();
            diff.xor(next);
            BitSet tiles = FogShading.dirtyTiles(diff, cols, rows, radius, tileSize);
            int tileCols = (cols + tileSize - 1) / tileSize;
            for (int tile = tiles.nextSetBit(0); tile >= 0; tile = tiles.nextSetBit(tile + 1)) {
                int c0 = tile % tileCols * tileSize, r0 = tile / tileCols * tileSize;
                int c1 = Math.min(cols - 1, c0 + tileSize - 1), r1 = Math.min(rows - 1, r0 + tileSize - 1);
                float[] region = FogShading.fogOpacityRegion(next, cols, rows, radius, c0, r0, c1, r1);
                for (int r = r0; r <= r1; r++) {
                    System.arraycopy(region, (r - r0) * (c1 - c0 + 1), updated, r * cols + c0, c1 - c0 + 1);
                }
            }
            assertArrayEquals(expected, updated, 1e-6f, "radius " + radius);
        }
    }

    @Test
    void regionOpacityMatchesFullComputation() {
        BitSet revealed = block(60, 10, 12, 45, 40);
        float[] full = FogShading.fogOpacity(revealed, 60, 60, 4);
        float[] region = FogShading.fogOpacityRegion(revealed, 60, 60, 4, 8, 10, 30, 25);

        for (int r = 0; r < 16; r++) {
            for (int c = 0; c < 23; c++) {
                assertEquals(full[(10 + r) * 60 + 8 + c], region[r * 23 + c], 1e-6f);
            }
        }
    }

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
