package dmmt.render;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameProfilerTest {
    @Test
    void nestedSectionsAreReportedAsExclusiveNonOverlappingTimes() {
        FrameProfiler.drain();
        FrameProfiler.measure("player overhead", () -> FrameProfiler.measure("player bright-core",
                () -> LockSupport.parkNanos(20_000_000)));

        Map<String, Long> sections = FrameProfiler.drain();
        assertTrue(sections.get("player bright-core") >= 20_000_000);
        assertTrue(sections.get("player overhead") < sections.get("player bright-core"),
                "The parent section must exclude its measured child work");
    }
}
