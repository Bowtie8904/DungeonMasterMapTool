package dmmt.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewportEdgeScrollTest {
    private final Rectangle2D area = new Rectangle2D(0, 70, 1000, 700);

    @Test
    void rampsLinearlyInEachEdgeZoneAndStopsAtTheInnerBoundary() {
        assertEquals(Point2D.ZERO, velocity(40, 400));
        assertEquals(Point2D.ZERO, velocity(960, 400));
        assertEquals(-300, velocity(20, 400).getX(), 1e-9);
        assertEquals(300, velocity(980, 400).getX(), 1e-9);
        assertEquals(-600, velocity(0, 400).getX(), 1e-9);
        assertEquals(600, velocity(1000, 400).getX(), 1e-9);
        assertEquals(-300, velocity(500, 90).getY(), 1e-9);
        assertEquals(300, velocity(500, 750).getY(), 1e-9);
        assertEquals(Point2D.ZERO, velocity(500, 400));
    }

    @Test
    void capsCombinedCornerSpeedWithoutChangingDirection() {
        Point2D corner = velocity(1000, 770);
        assertEquals(600, corner.magnitude(), 1e-9);
        assertEquals(corner.getX(), corner.getY(), 1e-9);
        Point2D partial = velocity(980, 750);
        assertEquals(300, partial.getX(), 1e-9);
        assertEquals(300, partial.getY(), 1e-9);
    }

    @Test
    void continuesBeyondEdgesAtCappedSpeedAndHandlesSmallOrEmptyAreas() {
        assertEquals(new Point2D(-600, 0), velocity(-500, 400));
        assertEquals(new Point2D(600, 0), velocity(1500, 400));
        assertEquals(new Point2D(0, -600), velocity(500, -500));
        assertEquals(new Point2D(0, 600), velocity(500, 1500));
        Point2D corner = velocity(-500, -1000);
        assertEquals(600, corner.magnitude(), 1e-9);
        assertEquals(corner.getX(), corner.getY(), 1e-9);
        assertTrue(corner.getX() < 0);
        Point2D mixed = velocity(2000, 90);
        assertEquals(600, mixed.magnitude(), 1e-9);
        assertEquals(-2, mixed.getX() / mixed.getY(), 1e-9);
        assertEquals(Point2D.ZERO, ViewportEdgeScroll.velocity(new Rectangle2D(0, 0, 0, 0), 0, 0, 40, 600));
        assertEquals(Point2D.ZERO, ViewportEdgeScroll.velocity(new Rectangle2D(0, 0, 20, 20), 10, 10, 40, 600));
        assertEquals(-300, ViewportEdgeScroll.velocity(new Rectangle2D(0, 0, 20, 20), 5, 10, 40, 600).getX(), 1e-9);
    }

    private Point2D velocity(double x, double y) {
        return ViewportEdgeScroll.velocity(area, x, y, 40, 600);
    }
}
