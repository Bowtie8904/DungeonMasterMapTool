package dmmt.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure grid arrangement for the handout: places several images (keeping their aspect ratios) in rows so that the
 * total covered area of the available space is as large as possible.
 */
public final class HandoutLayout {

    public record Size(double width, double height) {
    }

    public record Rect(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }

        double area() {
            return width * height;
        }
    }

    private HandoutLayout() {
    }

    /** Space in which the arrangement is computed: width and height swap for 90/270 degree rotations. */
    public static Size layoutSize(double width, double height, int rotation) {
        return rotation % 180 != 0 ? new Size(height, width) : new Size(width, height);
    }

    /**
     * Rectangles (one per image, same order) inside a {@code width x height} area. Every row holds up to
     * {@code columns} images that share a common height; the number of columns with the largest covered area wins.
     */
    public static List<Rect> compute(List<Size> images, double width, double height) {
        if (images.isEmpty() || width <= 0 || height <= 0) {
            return List.of();
        }
        double gap = Math.min(width, height) * 0.01;
        List<Rect> best = null;
        double bestArea = -1;
        for (int columns = 1; columns <= images.size(); columns++) {
            List<Rect> candidate = arrange(images, columns, width, height, gap);
            double area = candidate.stream().mapToDouble(Rect::area).sum();
            if (area > bestArea + 1e-6) {
                bestArea = area;
                best = candidate;
            }
        }
        return best;
    }

    private static double aspect(Size size) {
        return size.width() / size.height();
    }

    private static List<Rect> arrange(List<Size> images, int columns, double width, double height, double gap) {
        double availW = width - 2 * gap;
        double availH = height - 2 * gap;
        int rows = (images.size() + columns - 1) / columns;

        // Row height at which the row spans the full available width.
        double[] rowHeights = new double[rows];
        double heightSum = 0;
        for (int row = 0; row < rows; row++) {
            int from = row * columns;
            int to = Math.min(images.size(), from + columns);
            double aspectSum = 0;
            for (int i = from; i < to; i++) {
                aspectSum += aspect(images.get(i));
            }
            rowHeights[row] = Math.max(0, availW - gap * (to - from - 1)) / aspectSum;
            heightSum += rowHeights[row];
        }
        double scale = Math.min(1.0, Math.max(0, availH - gap * (rows - 1)) / heightSum);

        double totalHeight = gap * (rows - 1);
        for (int row = 0; row < rows; row++) {
            rowHeights[row] *= scale;
            totalHeight += rowHeights[row];
        }

        List<Rect> result = new ArrayList<>();
        double y = (height - totalHeight) / 2;
        for (int row = 0; row < rows; row++) {
            int from = row * columns;
            int to = Math.min(images.size(), from + columns);
            double rowWidth = gap * (to - from - 1);
            for (int i = from; i < to; i++) {
                rowWidth += rowHeights[row] * aspect(images.get(i));
            }
            double x = (width - rowWidth) / 2;
            for (int i = from; i < to; i++) {
                double w = rowHeights[row] * aspect(images.get(i));
                result.add(new Rect(x, y, w, rowHeights[row]));
                x += w + gap;
            }
            y += rowHeights[row] + gap;
        }
        return result;
    }

    /**
     * Maps a point of the {@code width x height} screen area to the layout space of a board that is displayed
     * rotated clockwise by {@code rotation} degrees around the centre of the area.
     */
    public static double[] toLayoutSpace(double px, double py, int rotation, double width, double height) {
        Size layout = layoutSize(width, height, rotation);
        double dx = px - width / 2;
        double dy = py - height / 2;
        double x;
        double y;
        switch (Math.floorMod(rotation, 360)) {
            case 90 -> {
                x = dy;
                y = -dx;
            }
            case 180 -> {
                x = -dx;
                y = -dy;
            }
            case 270 -> {
                x = -dy;
                y = dx;
            }
            default -> {
                x = dx;
                y = dy;
            }
        }
        return new double[]{x + layout.width() / 2, y + layout.height() / 2};
    }

    /** Index of the image under the screen point, or -1. */
    public static int hitTest(List<Rect> rects, double px, double py, int rotation, double width, double height) {
        double[] p = toLayoutSpace(px, py, rotation, width, height);
        for (int i = rects.size() - 1; i >= 0; i--) {
            if (rects.get(i).contains(p[0], p[1])) {
                return i;
            }
        }
        return -1;
    }
}
