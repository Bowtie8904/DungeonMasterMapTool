package dmmt.service;

import dmmt.model.MultiLevelManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdjacentLevelPrefetchTest {
    @Test
    void rejectedSpeculationReportsFailureWithoutEscapingIntoMapSwitch() {
        AtomicInteger failures = new AtomicInteger();
        AdjacentLevelPrefetch<String> prefetch = new AdjacentLevelPrefetch<>((file, cancelled) -> "unused",
                work -> { throw new java.util.concurrent.RejectedExecutionException("full"); },
                (file, failure) -> failures.incrementAndGet());
        assertDoesNotThrow(() -> prefetch.update(List.of(Path.of("a"), Path.of("b"))));
        assertEquals(2, failures.get());
        assertNull(prefetch.take(Path.of("a")));
    }
    @Test
    void selectsOnlyImmediateNeighboursAndHandlesEdgesAndUnknownLevel() {
        MultiLevelManifest manifest = new MultiLevelManifest();
        for (int i = 0; i < 5; i++) {
            manifest.getLevels().add(MultiLevelManifest.Level.builder().id("floor" + i)
                    .folder("levels\\floor" + i).build());
        }
        Path file = Path.of("building.dmlevels").toAbsolutePath();
        assertEquals(List.of(MultiLevelService.levelFile(file, manifest.getLevels().get(1)),
                        MultiLevelService.levelFile(file, manifest.getLevels().get(3))),
                AdjacentLevelPrefetch.adjacentFiles(file, manifest, "floor2"));
        assertEquals(1, AdjacentLevelPrefetch.adjacentFiles(file, manifest, "floor0").size());
        assertEquals(1, AdjacentLevelPrefetch.adjacentFiles(file, manifest, "floor4").size());
        assertTrue(AdjacentLevelPrefetch.adjacentFiles(file, manifest, "deleted").isEmpty());
    }

    @Test
    void boundsRequestsHandsOffCompletedOnlyAndCancelsSwitchAndClosure() {
        List<FutureTask<String>> tasks = new ArrayList<>();
        AdjacentLevelPrefetch<String> prefetch = new AdjacentLevelPrefetch<>(
                (file, cancelled) -> file.getFileName().toString(),
                work -> {
                    FutureTask<String> task = new FutureTask<>(work);
                    tasks.add(task);
                    return task;
                }, (file, failure) -> fail(failure));
        prefetch.update(List.of(Path.of("a"), Path.of("b"), Path.of("c")));
        assertEquals(2, tasks.size());
        tasks.getFirst().run();
        assertEquals("a", prefetch.take(Path.of("a")));
        assertNull(prefetch.take(Path.of("b")));
        assertTrue(tasks.get(1).isCancelled());
        prefetch.update(List.of(Path.of("d")));
        prefetch.update(List.of(Path.of("e")));
        assertTrue(tasks.get(2).isCancelled());
        prefetch.close();
        assertTrue(tasks.getLast().isCancelled());
        prefetch.update(List.of(Path.of("f")));
        assertEquals(4, tasks.size());
    }

    @Test
    void staleCompletionAndErrorsCannotRepopulateOrNotifyAfterClear() {
        List<FutureTask<String>> tasks = new ArrayList<>();
        AtomicReference<AdjacentLevelPrefetch<String>> reference = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        AdjacentLevelPrefetch<String> prefetch = new AdjacentLevelPrefetch<>((file, cancelled) -> {
            reference.get().clear();
            assertTrue(cancelled.getAsBoolean());
            if (file.endsWith("error")) {
                throw new java.io.IOException("obsolete");
            }
            return "obsolete";
        }, work -> {
            FutureTask<String> task = new FutureTask<>(work);
            tasks.add(task);
            return task;
        }, (file, failure) -> failures.incrementAndGet());
        reference.set(prefetch);
        prefetch.update(List.of(Path.of("old")));
        tasks.getLast().run();
        Thread.interrupted();
        assertNull(prefetch.take(Path.of("old")));
        prefetch.update(List.of(Path.of("error")));
        tasks.getLast().run();
        Thread.interrupted();
        assertEquals(0, failures.get());
        assertNull(prefetch.take(Path.of("error")));
    }
}
