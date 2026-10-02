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

    public record Panel(Rect bounds, int rotation) {
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

    /** Player output regions; the second copy faces the opposite side without reflecting the images. */
    public static List<Panel> outputPanels(double width, double height, int rotation, boolean mirrored) {
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        int angle = Math.floorMod(rotation, 360);
        if (!mirrored) {
            return List.of(new Panel(new Rect(0, 0, width, height), angle));
        }
        Rect first;
        Rect second;
        switch (angle) {
            case 90 -> {
                first = new Rect(0, 0, width / 2, height);
                second = new Rect(width / 2, 0, width / 2, height);
            }
            case 180 -> {
                first = new Rect(0, 0, width, height / 2);
                second = new Rect(0, height / 2, width, height / 2);
            }
            case 270 -> {
                first = new Rect(width / 2, 0, width / 2, height);
                second = new Rect(0, 0, width / 2, height);
            }
            default -> {
                first = new Rect(0, height / 2, width, height / 2);
                second = new Rect(0, 0, width, height / 2);
            }
        }
        return List.of(new Panel(first, angle), new Panel(second, (angle + 180) % 360));
    }

    private static final int MAX_EXACT_IMAGES = 12;
    private static final int MAX_TREE_IMAGES = 8;

    /** Slicing tree node: a leaf image, or two parts placed side by side or stacked. */
    private record Node(int index, Node first, Node second, boolean sideBySide, double aspect) {
        static Node leaf(int index, double aspect) {
            return new Node(index, null, null, false, aspect);
        }

        static Node join(Node first, Node second, boolean sideBySide) {
            double aspect = sideBySide ? first.aspect + second.aspect
                    : 1 / (1 / first.aspect + 1 / second.aspect);
            return new Node(-1, first, second, sideBySide, aspect);
        }
    }

    /**
     * Tries every slicing tree over the images in their given order (side by side / stacked at every level, so an
     * image can sit next to a whole stack) and keeps the one with the largest smallest image.
     */
    private static List<Rect> computeTree(List<Size> images, double width, double height, double gap) {
        int n = images.size();
        List<List<List<Node>>> memo = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            memo.add(new ArrayList<>());
            for (int j = 0; j < n; j++) {
                memo.get(i).add(null);
            }
        }
        double availW = width - 2 * gap;
        double availH = height - 2 * gap;
        List<Rect> best = null;
        double bestMin = -1;
        double bestTotal = -1;
        for (Node root : trees(images, 0, n - 1, memo)) {
            double w = availW;
            double h = w / root.aspect;
            if (h > availH) {
                h = availH;
                w = h * root.aspect;
            }
            Rect[] rects = new Rect[n];
            place(root, (width - w) / 2, (height - h) / 2, w, h, gap / 2, rects);
            double min = Double.MAX_VALUE;
            double total = 0;
            for (Rect rect : rects) {
                min = Math.min(min, rect.area());
                total += rect.area();
            }
            if (min > bestMin * (1 + 1e-9) || (Math.abs(min - bestMin) <= bestMin * 1e-9 && total > bestTotal)) {
                bestMin = min;
                bestTotal = total;
                best = List.of(rects);
            }
        }
        return best;
    }

    private static List<Node> trees(List<Size> images, int from, int to, List<List<List<Node>>> memo) {
        List<Node> cached = memo.get(from).get(to);
        if (cached != null) {
            return cached;
        }
        List<Node> result = new ArrayList<>();
        if (from == to) {
            result.add(Node.leaf(from, aspect(images.get(from))));
        } else {
            for (int split = from; split < to; split++) {
                for (Node a : trees(images, from, split, memo)) {
                    for (Node b : trees(images, split + 1, to, memo)) {
                        result.add(Node.join(a, b, true));
                        result.add(Node.join(a, b, false));
                    }
                }
            }
        }
        memo.get(from).set(to, result);
        return result;
    }

    private static void place(Node node, double x, double y, double w, double h, double inset, Rect[] out) {
        if (node.first == null) {
            double f = Math.max(0.5, 1 - 2 * inset / Math.min(w, h));
            out[node.index] = new Rect(x + w * (1 - f) / 2, y + h * (1 - f) / 2, w * f, h * f);
        } else if (node.sideBySide) {
            double wa = w * node.first.aspect / (node.first.aspect + node.second.aspect);
            place(node.first, x, y, wa, h, inset, out);
            place(node.second, x + wa, y, w - wa, h, inset, out);
        } else {
            double ia = 1 / node.first.aspect;
            double ib = 1 / node.second.aspect;
            double ha = h * ia / (ia + ib);
            place(node.first, x, y, w, ha, inset, out);
            place(node.second, x, y + ha, w, h - ha, inset, out);
        }
    }

    /**
     * Rectangles (one per image, same order) inside a {@code width x height} area. Images are placed in rows of
     * consecutive images; every row spans the full width with one shared height. Among all possible row splits the
     * one whose smallest image is the largest wins (so no image is shrunk to unreadable size just to make another
     * one bigger); the total covered area breaks ties.
     */
    public static List<Rect> compute(List<Size> images, double width, double height) {
        if (images.isEmpty() || width <= 0 || height <= 0) {
            return List.of();
        }
        double gap = Math.min(width, height) * 0.01;
        if (images.size() <= MAX_TREE_IMAGES) {
            return computeTree(images, width, height, gap);
        }
        List<Rect> best = null;
        double bestMin = -1;
        double bestTotal = -1;
        for (int[] rowSizes : rowSplits(images.size())) {
            List<Rect> candidate = arrange(images, rowSizes, width, height, gap);
            double min = Double.MAX_VALUE;
            double total = 0;
            for (Rect rect : candidate) {
                min = Math.min(min, rect.area());
                total += rect.area();
            }
            if (min > bestMin * (1 + 1e-9) || (Math.abs(min - bestMin) <= bestMin * 1e-9 && total > bestTotal)) {
                bestMin = min;
                bestTotal = total;
                best = candidate;
            }
        }
        return best;
    }

    /** All ways to cut {@code count} consecutive images into rows (a fixed column count per split for big sets). */
    private static List<int[]> rowSplits(int count) {
        List<int[]> splits = new ArrayList<>();
        if (count <= MAX_EXACT_IMAGES) {
            for (int mask = 0; mask < (1 << (count - 1)); mask++) {
                List<Integer> rows = new ArrayList<>();
                int run = 1;
                for (int i = 0; i < count - 1; i++) {
                    if ((mask & (1 << i)) != 0) {
                        rows.add(run);
                        run = 1;
                    } else {
                        run++;
                    }
                }
                rows.add(run);
                splits.add(rows.stream().mapToInt(Integer::intValue).toArray());
            }
        } else {
            for (int columns = 1; columns <= count; columns++) {
                int rows = (count + columns - 1) / columns;
                int[] sizes = new int[rows];
                for (int r = 0; r < rows; r++) {
                    sizes[r] = Math.min(columns, count - r * columns);
                }
                splits.add(sizes);
            }
        }
        return splits;
    }
    private static double aspect(Size size) {
        return size.width() / size.height();
    }

    private static List<Rect> arrange(List<Size> images, int[] rowSizes, double width, double height, double gap) {
        double availW = width - 2 * gap;
        double availH = height - 2 * gap;
        int rows = rowSizes.length;

        int[] start = new int[rows];
        for (int row = 1; row < rows; row++) {
            start[row] = start[row - 1] + rowSizes[row - 1];
        }
        // Row height at which the row spans the full available width.
        double[] rowHeights = new double[rows];
        double heightSum = 0;
        for (int row = 0; row < rows; row++) {
            int from = start[row];
            int to = from + rowSizes[row];
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
            int from = start[row];
            int to = from + rowSizes[row];
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
