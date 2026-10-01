package dmmt.render;

import dmmt.service.Tuning;

/**
 * Temporary in-memory override that trades visual quality for speed. It never changes a project or a
 * setting: every consumer asks this class for the effective value and falls back to the configured one
 * when the mode is off. The reduced values come from the {@code performance.*} settings ({@link Tuning}).
 */
public final class PerformanceMode {
    private static volatile boolean enabled;

    private PerformanceMode() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Light map divisor while active (normally {@code lighting.lightMapScale}). */
    public static int lightMapScale() {
        return Tuning.PERF_LIGHT_MAP_SCALE.get();
    }

    /** Soft texture edges use at most this many feather passes while active. */
    public static int maxFeatherPasses() {
        return Tuning.PERF_MAX_FEATHER_PASSES.get();
    }

    /** Map images are drawn from tiles this many pyramid levels coarser (each level halves the resolution). */
    public static int imageLevelBias() {
        return Tuning.PERF_IMAGE_LEVEL_BIAS.get();
    }

    /** Frame rate of moving effect textures while active; light flicker is switched off entirely. */
    public static int textureAnimationFps() {
        return Tuning.PERF_ANIMATION_FPS.get();
    }

    /** Frame rate cap when nothing moves; the configured idle FPS is used instead if it is lower. */
    public static int idleFps() {
        return Tuning.PERF_IDLE_FPS.get();
    }

    /** Frame rate while the user is interacting (dragging, panning, ...), so tools do not feel choppy. */
    public static int interactionFps() {
        return Tuning.PERF_INTERACTION_FPS.get();
    }

    /** Whether fog still fades in and out while active. */
    public static boolean fogFade() {
        return Tuning.PERF_FOG_FADE.get();
    }
}