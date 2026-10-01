package dmmt.render;

import java.util.Arrays;
import java.util.BitSet;

/** Pure helpers for fog shading: soft edges and step-wise fading towards a target. */
public final class FogShading {

    private FogShading() {
    }

    private static final float DIAGONAL = 1.4142135f;

    /**
     * Fog opacity (0 = clear, 1 = fully fogged) per mask cell. Fogged cells are always fully opaque, so soft edges
     * never reveal anything beyond the real edge of the revealed area (no peeking past walls). With
     * {@code radius > 0} revealed cells fade from opaque at the edge to clear over about {@code radius} cells, which
     * hides the cell staircase along diagonal edges. Cells beyond the mask border do not count as fogged, so a
     * fully revealed mask stays fully clear.
     */
    public static float[] fogOpacity(BitSet revealed, int cols, int rows, int radius) {
        float[] values = new float[cols * rows];
        if (radius <= 0) {
            Arrays.fill(values, 1f);
            for (int idx = revealed.nextSetBit(0); idx >= 0 && idx < values.length; idx = revealed.nextSetBit(idx + 1)) {
                values[idx] = 0f;
            }
            return values;
        }
        float[] distance = distanceToFog(revealed, cols, rows);
        for (int i = 0; i < values.length; i++) {
            float d = distance[i];
            if (d <= 0f) {
                values[i] = 1f;
            } else {
                float t = Math.min(1f, Math.max(0f, (d - 0.5f) / radius));
                values[i] = 1f - t * t * (3f - 2f * t);
            }
        }
        return values;
    }

    /**
     * Same values as {@link #fogOpacity} but only for the inclusive cell rectangle {@code c0..c1 x r0..r1}, returned
     * row by row. Only works on a window around the rectangle, so small changes stay cheap on large masks.
     */
    public static float[] fogOpacityRegion(BitSet revealed, int cols, int rows, int radius,
                                           int c0, int r0, int c1, int r1) {
        int w = c1 - c0 + 1;
        int h = r1 - r0 + 1;
        float[] out = new float[w * h];
        if (radius <= 0) {
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    out[r * w + c] = revealed.get((r0 + r) * cols + c0 + c) ? 0f : 1f;
                }
            }
            return out;
        }
        int margin = radius + 2;
        int wc0 = Math.max(0, c0 - margin);
        int wr0 = Math.max(0, r0 - margin);
        int wc1 = Math.min(cols - 1, c1 + margin);
        int wr1 = Math.min(rows - 1, r1 + margin);
        int ww = wc1 - wc0 + 1;
        int wh = wr1 - wr0 + 1;
        float[] d = new float[ww * wh];
        for (int r = 0; r < wh; r++) {
            for (int c = 0; c < ww; c++) {
                d[r * ww + c] = revealed.get((wr0 + r) * cols + wc0 + c) ? Float.MAX_VALUE : 0f;
            }
        }
        chamfer(d, ww, wh);
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                out[r * w + c] = opacityForDistance(d[(r0 + r - wr0) * ww + (c0 + c - wc0)], radius);
            }
        }
        return out;
    }

    private static float opacityForDistance(float d, int radius) {
        if (d <= 0f) {
            return 1f;
        }
        float t = Math.min(1f, Math.max(0f, (d - 0.5f) / radius));
        return 1f - t * t * (3f - 2f * t);
    }

    /** Approximate distance (in cells) of every cell to the nearest fogged cell; 0 for fogged cells. */
    static float[] distanceToFog(BitSet revealed, int cols, int rows) {
        float[] d = new float[cols * rows];
        for (int i = 0; i < d.length; i++) {
            d[i] = revealed.get(i) ? Float.MAX_VALUE : 0f;
        }
        chamfer(d, cols, rows);
        return d;
    }

    private static void chamfer(float[] d, int cols, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                float best = d[i];
                if (col > 0) {
                    best = Math.min(best, d[i - 1] + 1f);
                }
                if (row > 0) {
                    best = Math.min(best, d[i - cols] + 1f);
                    if (col > 0) {
                        best = Math.min(best, d[i - cols - 1] + DIAGONAL);
                    }
                    if (col < cols - 1) {
                        best = Math.min(best, d[i - cols + 1] + DIAGONAL);
                    }
                }
                d[i] = best;
            }
        }
        for (int row = rows - 1; row >= 0; row--) {
            for (int col = cols - 1; col >= 0; col--) {
                int i = row * cols + col;
                float best = d[i];
                if (col < cols - 1) {
                    best = Math.min(best, d[i + 1] + 1f);
                }
                if (row < rows - 1) {
                    best = Math.min(best, d[i + cols] + 1f);
                    if (col < cols - 1) {
                        best = Math.min(best, d[i + cols + 1] + DIAGONAL);
                    }
                    if (col > 0) {
                        best = Math.min(best, d[i + cols - 1] + DIAGONAL);
                    }
                }
                d[i] = best;
            }
        }
    }

    public static boolean reached(float[] shown, float[] target, int from, int to) {
        for (int i = from; i < to; i++) {
            if (shown[i] != target[i]) {
                return false;
            }
        }
        return true;
    }
    /**
     * Moves every value of {@code shown} towards {@code target} by at most {@code step}.
     *
     * @return {@code {firstChangedIndex, lastChangedIndex}} or {@code null} when nothing changed
     */
    public static int[] advance(float[] shown, float[] target, float step) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < shown.length; i++) {
            float s = shown[i];
            float t = target[i];
            if (s == t) {
                continue;
            }
            shown[i] = s < t ? Math.min(t, s + step) : Math.max(t, s - step);
            if (first < 0) {
                first = i;
            }
            last = i;
        }
        return first < 0 ? null : new int[]{first, last};
    }

    public static boolean reached(float[] shown, float[] target) {
        return Arrays.equals(shown, target);
    }

    /**
     * Starts a new fade for every cell whose target changes: it fades from its currently shown value. Cells whose
     * target stays the same keep their running fade.
     */
    public static void retarget(float[] shown, float[] oldTarget, float[] newTarget, float[] from, float[] progress) {
        for (int i = 0; i < shown.length; i++) {
            if (newTarget[i] != oldTarget[i]) {
                from[i] = shown[i];
                progress[i] = 0f;
            }
        }
    }

    /**
     * Advances every fading cell from {@code from} towards {@code target}. A step of 1 is a complete fade between
     * clear and fully fogged; {@code revealStep} applies when a cell gets clearer, {@code hideStep} when it gets
     * darker. Without easing the result equals {@link #advance(float[], float[], float)}; with {@code smooth} the
     * fade starts and ends slowly (smoothstep) but takes the same time.
     *
     * @return {@code {firstChangedIndex, lastChangedIndex}} or {@code null} when nothing changed
     */
    public static int[] advance(float[] shown, float[] target, float[] from, float[] progress,
                                float revealStep, float hideStep, boolean smooth) {
        return advance(shown, target, from, progress, revealStep, hideStep, smooth, 0, shown.length);
    }

    /** Like the full variant but only touches indices {@code start} (inclusive) to {@code end} (exclusive). */
    public static int[] advance(float[] shown, float[] target, float[] from, float[] progress,
                                float revealStep, float hideStep, boolean smooth, int start, int end) {
        int first = -1;
        int last = -1;
        for (int i = start; i < end; i++) {
            float t = target[i];
            if (shown[i] == t) {
                continue;
            }
            float f = from[i];
            float distance = Math.abs(t - f);
            float p = distance <= 0f ? 1f : progress[i] + (t < f ? revealStep : hideStep) / distance;
            if (p >= 1f) {
                progress[i] = 1f;
                shown[i] = t;
            } else {
                progress[i] = p;
                float eased = smooth ? p * p * (3f - 2f * p) : p;
                shown[i] = f + (t - f) * eased;
            }
            if (first < 0) {
                first = i;
            }
            last = i;
        }
        return first < 0 ? null : new int[]{first, last};
    }

    /**
     * Hard-edged fog opacity of a fog image whose pixels each merge {@code factor x factor} mask cells: a pixel is
     * clear when at least half of its cells are revealed.
     */
    public static float[] downsampledOpacity(BitSet revealed, int cols, int rows, int factor) {
        int imgCols = (cols + factor - 1) / factor;
        int imgRows = (rows + factor - 1) / factor;
        int[] counts = new int[imgCols * imgRows];
        for (int idx = revealed.nextSetBit(0); idx >= 0 && idx < cols * rows; idx = revealed.nextSetBit(idx + 1)) {
            counts[(idx / cols / factor) * imgCols + (idx % cols) / factor]++;
        }
        float[] values = new float[counts.length];
        for (int r = 0; r < imgRows; r++) {
            int blockRows = Math.min(factor, rows - r * factor);
            for (int c = 0; c < imgCols; c++) {
                int blockCells = blockRows * Math.min(factor, cols - c * factor);
                values[r * imgCols + c] = counts[r * imgCols + c] * 2 >= blockCells ? 0f : 1f;
            }
        }
        return values;
    }

    /** Fog opacity 0..1 as black ARGB pixel. */
    public static int pixel(float opacity) {
        int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f);
        return alpha << 24;
    }
}
