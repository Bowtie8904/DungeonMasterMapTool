package dmmt.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogMaskTest {
    private static boolean revealedAt(FogMask mask, double x, double y) {
        int col = (int) Math.floor((x - mask.getOriginX()) / mask.getCellSize());
        int row = (int) Math.floor((y - mask.getOriginY()) / mask.getCellSize());
        return mask.isRevealedCell(row * mask.getCols() + col);
    }

    @Test
    void rectRevealAndHide() {
        FogMask mask = new FogMask(0, 0, 10, 20, 20);
        mask.applyRect(20, 20, 50, 50, true);
        assertTrue(revealedAt(mask, 45, 45));
        assertFalse(revealedAt(mask, 5, 5));
        assertFalse(revealedAt(mask, 95, 95));

        mask.applyRect(40, 40, 20, 20, false);
        assertFalse(revealedAt(mask, 45, 45));
        assertTrue(revealedAt(mask, 25, 25));
    }

    @Test
    void circleRevealsOnlyInsideRadius() {
        FogMask mask = new FogMask(0, 0, 10, 20, 20);
        mask.applyCircle(100, 100, 30, true);
        assertTrue(revealedAt(mask, 100, 100));
        assertTrue(revealedAt(mask, 120, 100));
        assertFalse(revealedAt(mask, 125, 125));
        assertFalse(revealedAt(mask, 150, 100));
    }

    @Test
    void polygonReveal() {
        FogMask mask = new FogMask(0, 0, 10, 20, 20);
        mask.applyPolygon(new double[]{10, 100, 10}, new double[]{10, 10, 100}, true);
        assertTrue(revealedAt(mask, 25, 25));
        assertFalse(revealedAt(mask, 90, 90));
    }

    @Test
    void jsonRoundTripPreservesBits() throws Exception {
        FogMask mask = new FogMask(-50, 30, 8, 64, 40);
        mask.applyCircle(100, 150, 60, true);
        mask.applyRect(-50, 30, 40, 40, true);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(mask);
        FogMask loaded = mapper.readValue(json, FogMask.class);

        assertEquals(mask.getCols(), loaded.getCols());
        assertEquals(mask.getRows(), loaded.getRows());
        assertEquals(mask.getOriginX(), loaded.getOriginX());
        assertEquals(mask.getOriginY(), loaded.getOriginY());
        assertEquals(mask.copyBits(), loaded.copyBits());
    }

    @Test
    void resizeToCoverKeepsExistingReveals() {
        FogMask mask = new FogMask(0, 0, 10, 10, 10);
        mask.applyRect(30, 30, 20, 20, true);
        assertTrue(mask.resizeToCover(-35, -20, 150, 100));
        assertTrue(mask.getOriginX() <= -35);
        assertTrue(mask.getOriginY() <= -20);
        assertTrue(mask.getOriginX() + mask.getWidth() >= 150);
        assertTrue(revealedAt(mask, 40, 40));
        assertFalse(revealedAt(mask, 10, 10));
        assertFalse(mask.resizeToCover(0, 0, 50, 50));
    }

    @Test
    void rotateQuarterMatchesPointRotationAndIsReversible() {
        FogMask mask = new FogMask(0, 0, 10, 10, 5);
        mask.applyRect(0, 0, 10, 10, true); // cell with center (5, 5)
        BitSet original = mask.copyBits();
        double cx = 50;
        double cy = 25;

        mask.rotateQuarter(cx, cy, true);
        // Clockwise point transform used by MapRotationService: (x, y) -> (cx - (y - cy), cy + (x - cx))
        double rx = cx - (5 - cy);
        double ry = cy + (5 - cx);
        assertEquals(5, mask.getCols());
        assertEquals(10, mask.getRows());
        assertTrue(revealedAt(mask, rx, ry));

        mask.rotateQuarter(cx, cy, false);
        assertEquals(0, mask.getOriginX(), 1e-9);
        assertEquals(0, mask.getOriginY(), 1e-9);
        assertEquals(original, mask.copyBits());
    }

    @Test
    void snapshotRestore() {
        FogMask mask = new FogMask(0, 0, 10, 10, 10);
        FogMask.Snapshot before = mask.snapshot();
        mask.applyCircle(50, 50, 20, true);
        FogMask.Snapshot after = mask.snapshot();
        assertFalse(before.sameBits(after));
        mask.restore(before);
        assertTrue(before.sameBits(mask.snapshot()));
        mask.restore(after);
        assertTrue(after.sameBits(mask.snapshot()));
    }
}
