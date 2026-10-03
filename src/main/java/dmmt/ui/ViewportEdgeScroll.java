package dmmt.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

/** Screen-space velocity for the unobstructed player-viewport drag area. */
public final class ViewportEdgeScroll {
    private ViewportEdgeScroll() {
    }

    public static Point2D velocity(Rectangle2D area, double x, double y, double zone, double maxSpeed) {
        if (area.getWidth() <= 0 || area.getHeight() <= 0) {
            return Point2D.ZERO;
        }
        double vx = axis(x, area.getMinX(), area.getMaxX(), zone);
        double vy = axis(y, area.getMinY(), area.getMaxY(), zone);
        double scale = maxSpeed / Math.max(1, Math.hypot(vx, vy));
        return new Point2D(vx * scale, vy * scale);
    }

    private static double axis(double position, double min, double max, double zone) {
        double width = Math.min(zone, (max - min) / 2);
        if (position < min + width) {
            return -Math.min(1, (min + width - position) / width);
        }
        if (position > max - width) {
            return Math.min(1, (position - (max - width)) / width);
        }
        return 0;
    }
}
