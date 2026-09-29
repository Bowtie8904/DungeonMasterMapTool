package dmmt.lighting;

import java.util.Arrays;

/**
 * Scanline polygon fill over a regular grid. Coordinates are given in cell units;
 * a cell (col,row) is inside when its center (col+0.5,row+0.5) is inside the polygon.
 */
public final class PolygonRaster {
    private PolygonRaster() {
    }

    @FunctionalInterface
    public interface SpanConsumer {
        /** Called for each inclusive column span [colStart, colEnd] in the given row. */
        void span(int row, int colStart, int colEnd);
    }

    public static void fill(double[] xs, double[] ys, int cols, int rows, SpanConsumer consumer) {
        int n = xs.length;
        if (n < 3 || cols <= 0 || rows <= 0) {
            return;
        }
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int rowStart = Math.max(0, (int) Math.floor(minY));
        int rowEnd = Math.min(rows - 1, (int) Math.floor(maxY));
        double[] crossings = new double[n];
        for (int row = rowStart; row <= rowEnd; row++) {
            double y = row + 0.5;
            int count = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double yi = ys[i];
                double yj = ys[j];
                if ((yi <= y && yj > y) || (yj <= y && yi > y)) {
                    crossings[count++] = xs[i] + (y - yi) * (xs[j] - xs[i]) / (yj - yi);
                }
            }
            Arrays.sort(crossings, 0, count);
            for (int k = 0; k + 1 < count; k += 2) {
                int colStart = Math.max(0, (int) Math.ceil(crossings[k] - 0.5));
                int colEnd = Math.min(cols - 1, (int) Math.floor(crossings[k + 1] - 0.5));
                if (colStart <= colEnd) {
                    consumer.span(row, colStart, colEnd);
                }
            }
        }
    }
}
