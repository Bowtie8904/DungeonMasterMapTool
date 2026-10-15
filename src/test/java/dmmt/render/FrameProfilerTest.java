package dmmt.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FrameProfilerTest {
    @AfterEach
    void clearSections() {
        FrameProfiler.drain();
    }

    @Test
    void nestedSectionsAreReportedAsExclusiveNonOverlappingTimes() {
        FrameProfiler.drain();
        AtomicLong clock = new AtomicLong();
        FrameProfiler.measure("player overhead", () -> {
            clock.addAndGet(3);
            FrameProfiler.measure("player bright-core", () -> {
                clock.addAndGet(5);
                FrameProfiler.measure("light engine", () -> clock.addAndGet(20), clock::get);
                clock.addAndGet(7);
            }, clock::get);
            clock.addAndGet(4);
        }, clock::get);

        Map<String, Long> sections = FrameProfiler.drain();
        assertEquals(Map.of("player overhead", 7L, "player bright-core", 12L, "light engine", 20L), sections);
        assertEquals(39L, sections.values().stream().mapToLong(Long::longValue).sum());
        assertTrue(FrameProfiler.drain().isEmpty());
    }

    @Test
    void repeatedSectionsAccumulateAndExceptionsDoNotLeaveAnActiveParent() {
        FrameProfiler.drain();
        AtomicLong clock = new AtomicLong();
        IllegalStateException failure = new IllegalStateException("failed render");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                FrameProfiler.measure("parent", () -> {
                    clock.addAndGet(2);
                    FrameProfiler.measure("child", () -> {
                        clock.addAndGet(11);
                        throw failure;
                    }, clock::get);
                }, clock::get)));
        FrameProfiler.measure("child", () -> clock.addAndGet(13), clock::get);
        assertEquals(Map.of("parent", 2L, "child", 24L), FrameProfiler.drain());
    }

    @Test
    void productionClockRecordsSections() {
        FrameProfiler.drain();
        FrameProfiler.measure("render", () -> {});
        assertTrue(FrameProfiler.drain().get("render") >= 0);
    }
}
