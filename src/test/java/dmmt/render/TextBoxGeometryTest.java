package dmmt.render;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBoxGeometryTest {
    private static DmProject.TextBox box() {
        return DmProject.TextBox.builder().id("t").x(10).y(20).width(40).height(10).build();
    }

    @Test
    void footprintSwapsSizeAtOddTurns() {
        assertArrayEquals(new double[]{10, 20, 50, 30}, TextBoxGeometry.bounds(box(), 0), 1e-9);
        assertArrayEquals(new double[]{10, 20, 50, 30}, TextBoxGeometry.bounds(box(), 2), 1e-9);
        assertArrayEquals(new double[]{25, 5, 35, 45}, TextBoxGeometry.bounds(box(), 1), 1e-9);
        assertArrayEquals(new double[]{25, 5, 35, 45}, TextBoxGeometry.bounds(box(), 3), 1e-9);
    }

    @Test
    void localAndWorldAreInverse() {
        for (int turns = -1; turns < 5; turns++) {
            double[] world = TextBoxGeometry.toWorld(box(), turns, 12, 22);
            assertArrayEquals(new double[]{12, 22}, TextBoxGeometry.toLocal(box(), turns, world[0], world[1]), 1e-9);
        }
    }

    @Test
    void clockwiseQuarterTurnMovesTopLeftToTopRight() {
        // The unrotated top-left corner ends up at the top-right of the footprint after one clockwise turn.
        assertArrayEquals(new double[]{35, 5}, TextBoxGeometry.toWorld(box(), 1, 10, 20), 1e-9);
    }

    @Test
    void containsUsesRotatedShape() {
        assertTrue(TextBoxGeometry.contains(box(), 1, 30, 14));
        assertFalse(TextBoxGeometry.contains(box(), 1, 15, 25));
        assertTrue(TextBoxGeometry.contains(box(), 0, 15, 25));
    }

    @Test
    void footprintTopLeftCanBeSet() {
        DmProject.TextBox b = box();
        TextBoxGeometry.setFootprintTopLeft(b, 1, 100, 200);
        assertArrayEquals(new double[]{100, 200, 110, 240}, TextBoxGeometry.bounds(b, 1), 1e-9);
    }
}
