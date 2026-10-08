package dmmt.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The circular button layout of the audio overlay (3.35.6). */
class RingLayoutTest {
    private static final double ITEM = 86;
    private static final double GAP = 16;
    private static final double CENTRE = 210;

    @Test
    void anEmptyRingHasNoSlots() {
        assertTrue(RingLayout.place(0, ITEM, GAP, CENTRE).isEmpty());
    }

    @Test
    void buttonsKeepTheCentreFreeAndNeverOverlap() {
        List<RingLayout.Slot> slots = RingLayout.place(6, ITEM, GAP, CENTRE);

        assertEquals(6, slots.size());
        for (RingLayout.Slot slot : slots) {
            double radius = Math.hypot(slot.x(), slot.y());
            assertTrue(radius >= CENTRE / 2, "button inside the transport area: " + radius);
        }
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                double distance = Math.hypot(slots.get(i).x() - slots.get(j).x(),
                        slots.get(i).y() - slots.get(j).y());
                assertTrue(distance >= ITEM, "buttons " + i + " and " + j + " overlap: " + distance);
            }
        }
    }

    @Test
    void theFirstButtonSitsAtTheTop() {
        RingLayout.Slot first = RingLayout.place(4, ITEM, GAP, CENTRE).get(0);

        assertEquals(0, first.x(), 0.0001);
        assertTrue(first.y() < 0, "the first button must be above the centre");
    }

    @Test
    void crowdedLibrariesGrowOntoFurtherRings() {
        List<RingLayout.Slot> many = RingLayout.place(40, ITEM, GAP, CENTRE);

        assertEquals(40, many.size());
        long rings = many.stream().map(slot -> Math.round(Math.hypot(slot.x(), slot.y()))).distinct().count();
        assertTrue(rings > 1, "40 buttons must use more than one circle");
        assertTrue(RingLayout.diameter(many, ITEM) > RingLayout.diameter(RingLayout.place(4, ITEM, GAP, CENTRE), ITEM));
    }

    @Test
    void theDiameterCoversEveryButton() {
        List<RingLayout.Slot> slots = RingLayout.place(9, ITEM, GAP, CENTRE);
        double radius = RingLayout.diameter(slots, ITEM) / 2;

        for (RingLayout.Slot slot : slots) {
            assertTrue(Math.abs(slot.x()) + ITEM / 2 <= radius + 0.001, "button sticks out horizontally");
            assertTrue(Math.abs(slot.y()) + ITEM / 2 <= radius + 0.001, "button sticks out vertically");
        }
    }
}
