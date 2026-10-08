package dmmt.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the round category and sound effect buttons of the audio overlay on concentric circles (3.35.6).
 *
 * <p>The innermost circle keeps enough room free for the transport controls in its centre. Once a circle is full
 * the next entries move outwards onto a wider circle, so a library with three categories looks as tidy as one with
 * forty. The maths is kept free of JavaFX so it can be unit-tested.</p>
 */
public final class RingLayout {
    private RingLayout() {
    }

    /** The centre of one button, relative to the centre of the ring; x grows right, y grows down. */
    public record Slot(double x, double y) {
    }

    /**
     * Positions for {@code count} buttons.
     *
     * @param count    number of buttons, may be zero
     * @param itemSize diameter of one button
     * @param gap      smallest gap between two neighbouring buttons
     * @param centre   diameter that stays free in the middle for the transport controls
     */
    public static List<Slot> place(int count, double itemSize, double gap, double centre) {
        List<Slot> slots = new ArrayList<>();
        if (count <= 0) {
            return slots;
        }
        double step = Math.max(1, itemSize + gap);
        double innerRadius = Math.max(centre / 2 + step * 0.75, step);
        int placed = 0;
        for (int ring = 0; placed < count; ring++) {
            double radius = innerRadius + ring * step;
            int capacity = Math.max(1, (int) Math.floor(2 * Math.PI * radius / step));
            int here = Math.min(capacity, count - placed);
            for (int i = 0; i < here; i++) {
                double angle = -Math.PI / 2 + 2 * Math.PI * i / here;
                slots.add(new Slot(Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
            placed += here;
        }
        return slots;
    }

    /** Diameter of the area the given slots need, including the buttons themselves. */
    public static double diameter(List<Slot> slots, double itemSize) {
        double max = 0;
        for (Slot slot : slots) {
            max = Math.max(max, Math.max(Math.abs(slot.x()), Math.abs(slot.y())));
        }
        return max * 2 + itemSize;
    }
}
