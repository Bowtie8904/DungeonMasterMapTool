package dmmt.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates how long named parts of the render loop take, so the metrics readout can show what is slow.
 * Only used from the JavaFX application thread.
 */
public final class FrameProfiler {
    private static final Map<String, Long> NANOS = new HashMap<>();

    private FrameProfiler() {
    }

    public static long start() {
        return System.nanoTime();
    }

    /** Adds the time since {@code startNanos} to the section and returns the current time for the next section. */
    public static long lap(String section, long startNanos) {
        long now = System.nanoTime();
        NANOS.merge(section, now - startNanos, Long::sum);
        return now;
    }

    /** Returns the time accumulated per section since the last call and resets it. */
    public static Map<String, Long> drain() {
        Map<String, Long> copy = new HashMap<>(NANOS);
        NANOS.clear();
        return copy;
    }
}
