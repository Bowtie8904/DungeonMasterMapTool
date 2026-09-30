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

    /** Approximate distance (in cells) of every cell to the nearest fogged cell; 0 for fogged cells. */
    static float[] distanceToFog(BitSet revealed, int cols, int rows) {
        float[] d = new float[cols * rows];
        for (int i = 0; i < d.length; i++) {
            d[i] = revealed.get(i) ? Float.MAX_VALUE : 0f;
        }
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
        return d;
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

    /** Fog opacity 0..1 as black ARGB pixel. */
    public static int pixel(float opacity) {
        int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f);
        return alpha << 24;
    }
}
