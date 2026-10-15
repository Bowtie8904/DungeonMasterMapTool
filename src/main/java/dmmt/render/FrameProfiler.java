package dmmt.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Accumulates how long named parts of the render loop take, so the metrics readout can show what is slow.
 * Only used from the JavaFX application thread.
 */
public final class FrameProfiler {
    private static final Map<String, Long> NANOS = new HashMap<>();
    private static final Deque<Section> SECTIONS = new ArrayDeque<>();

    private FrameProfiler() {
    }

    public static long start() {
        return System.nanoTime();
    }

    /** Adds the time since {@code startNanos} to the section and returns the current time for the next section. */
    public static long lap(String section, long startNanos) {
        long now = System.nanoTime();
        long elapsed = now - startNanos;
        NANOS.merge(section, elapsed, Long::sum);
        Section parent = SECTIONS.peek();
        if (parent != null) {
            parent.childrenNanos += elapsed;
        }
        return now;
    }

    /** Records a section excluding any profiled child work, so the reported parts do not overlap. */
    public static void measure(String section, Runnable action) {
        measure(section, action, System::nanoTime);
    }

    static void measure(String section, Runnable action, LongSupplier nanoTime) {
        Section current = new Section(section, nanoTime.getAsLong());
        SECTIONS.push(current);
        try {
            action.run();
        } finally {
            long elapsed = nanoTime.getAsLong() - current.startedNanos;
            SECTIONS.pop();
            NANOS.merge(current.name, Math.max(0, elapsed - current.childrenNanos), Long::sum);
            Section parent = SECTIONS.peek();
            if (parent != null) {
                parent.childrenNanos += elapsed;
            }
        }
    }

    /** Returns the time accumulated per section since the last call and resets it. */
    public static Map<String, Long> drain() {
        Map<String, Long> copy = new HashMap<>(NANOS);
        NANOS.clear();
        return copy;
    }

    private static final class Section {
        private final String name;
        private final long startedNanos;
        private long childrenNanos;

        private Section(String name, long startedNanos) {
            this.name = name;
            this.startedNanos = startedNanos;
        }
    }
}
