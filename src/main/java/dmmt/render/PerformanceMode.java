package dmmt.render;

/**
 * Temporary in-memory override that trades visual quality for speed. It never changes a project or a
 * setting: every consumer asks this class for the effective value and falls back to the configured one
 * when the mode is off.
 */
public final class PerformanceMode {
    /** Light map divisor while active (normally 4): a quarter of the light map pixels to rasterise. */
    public static final int LIGHT_MAP_SCALE = 8;
    /** Soft texture edges use at most this many feather passes while active. */
    public static final int MAX_FEATHER_PASSES = 6;
    /** Map images are drawn from tiles this many pyramid levels coarser (each level halves the resolution). */
    public static final int IMAGE_LEVEL_BIAS = 2;

    /** Frame rate of moving effect textures while active; light flicker is switched off entirely. */
    public static final int TEXTURE_ANIMATION_FPS = 10;
    /** Frame rate cap when nothing moves; the configured idle FPS is used instead if it is lower. */
    public static final int IDLE_FPS = 5;
    /** Frame rate while the user is interacting (dragging, panning, ...), so tools do not feel choppy. */
    public static final int INTERACTION_FPS = 20;

    private static volatile boolean enabled;

    private PerformanceMode() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
