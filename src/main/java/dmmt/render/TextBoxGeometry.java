package dmmt.render;

import dmmt.model.DmProject;

/**
 * Geometry of text boxes under the global text rotation. A box keeps an unrotated rectangle (x, y, width, height);
 * on screen it is turned clockwise by {@code quarterTurns} x 90 degrees around its center.
 */
public final class TextBoxGeometry {
    private TextBoxGeometry() {
    }

    public static int normalize(int quarterTurns) {
        return Math.floorMod(quarterTurns, 4);
    }

    /** Rotates an offset from the box center clockwise by the given quarter turns (screen coordinates, y down). */
    private static double[] turn(double dx, double dy, int quarterTurns) {
        return switch (normalize(quarterTurns)) {
            case 1 -> new double[]{-dy, dx};
            case 2 -> new double[]{-dx, -dy};
            case 3 -> new double[]{dy, -dx};
            default -> new double[]{dx, dy};
        };
    }

    public static double centerX(DmProject.TextBox box) {
        return box.getX() + box.getWidth() / 2.0;
    }

    public static double centerY(DmProject.TextBox box) {
        return box.getY() + box.getHeight() / 2.0;
    }

    /** Maps a point of the unrotated box rectangle to where it appears in the world. */
    public static double[] toWorld(DmProject.TextBox box, int quarterTurns, double localX, double localY) {
        double cx = centerX(box);
        double cy = centerY(box);
        double[] d = turn(localX - cx, localY - cy, quarterTurns);
        return new double[]{cx + d[0], cy + d[1]};
    }

    /** Maps a world point into the unrotated box rectangle's space. */
    public static double[] toLocal(DmProject.TextBox box, int quarterTurns, double worldX, double worldY) {
        double cx = centerX(box);
        double cy = centerY(box);
        double[] d = turn(worldX - cx, worldY - cy, -quarterTurns);
        return new double[]{cx + d[0], cy + d[1]};
    }

    public static boolean contains(DmProject.TextBox box, int quarterTurns, double worldX, double worldY) {
        double[] p = toLocal(box, quarterTurns, worldX, worldY);
        return p[0] >= box.getX() && p[0] <= box.getX() + box.getWidth()
                && p[1] >= box.getY() && p[1] <= box.getY() + box.getHeight();
    }

    /** Axis-aligned footprint in the world: {minX, minY, maxX, maxY}. */
    public static double[] bounds(DmProject.TextBox box, int quarterTurns) {
        boolean swapped = normalize(quarterTurns) % 2 == 1;
        double w = swapped ? box.getHeight() : box.getWidth();
        double h = swapped ? box.getWidth() : box.getHeight();
        double cx = centerX(box);
        double cy = centerY(box);
        return new double[]{cx - w / 2.0, cy - h / 2.0, cx + w / 2.0, cy + h / 2.0};
    }

    /** Moves the box so its footprint's top-left corner is at the given world point. */
    public static void setFootprintTopLeft(DmProject.TextBox box, int quarterTurns, double left, double top) {
        double[] b = bounds(box, quarterTurns);
        box.setX(box.getX() + left - b[0]);
        box.setY(box.getY() + top - b[1]);
    }
}
