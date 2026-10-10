package dmmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkSchedulerTest {
    @Test
    void newApplicationLifecycleGetsUsableSharedWorkersAfterShutdown() throws Exception {
        WorkScheduler first = WorkScheduler.shared();
        WorkScheduler.shutdownShared();
        WorkScheduler next = WorkScheduler.shared();
        assertNotSame(first, next);
        assertEquals("ready", next.submit(WorkScheduler.Kind.COPY, true, () -> "ready")
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void resourceConcurrencyIsBoundedInBothModes() throws Exception {
        for (String mode : List.of("session", "preparation")) {
            for (WorkScheduler.Kind kind : WorkScheduler.Kind.values()) {
                int bound = kind == WorkScheduler.Kind.COPY ? 2
                        : kind == WorkScheduler.Kind.AUDIO && mode.equals("preparation") ? 2 : 1;
                try (WorkScheduler scheduler = new WorkScheduler(() -> mode)) {
                    CountDownLatch started = new CountDownLatch(bound);
                    CountDownLatch release = new CountDownLatch(1);
                    AtomicInteger active = new AtomicInteger();
                    AtomicInteger maximum = new AtomicInteger();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < 12; i++) {
                        futures.add(scheduler.submit(kind, true, () -> {
                            int count = active.incrementAndGet();
                            maximum.accumulateAndGet(count, Math::max);
                            started.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } finally {
                                active.decrementAndGet();
                            }
                            return null;
                        }));
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertEquals(bound, maximum.get());
                    release.countDown();
                    for (Future<?> future : futures) {
                        future.get(5, TimeUnit.SECONDS);
                    }
                    assertEquals(bound, maximum.get());
                }
            }
        }
    }

    @Test
    void foregroundAndPrioritizedJobsPrecedeSpeculativeWork() throws Exception {
        try (WorkScheduler scheduler = new WorkScheduler(() -> "session")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> running = scheduler.submit(WorkScheduler.Kind.IMAGE, false, () -> {
                started.countDown();
                release.await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            Future<?> low = scheduler.submit(WorkScheduler.Kind.IMAGE, false, () -> order.add("low"));
            Future<?> promoted = scheduler.submit(WorkScheduler.Kind.IMAGE, false, () -> order.add("promoted"));
            Future<?> high = scheduler.submit(WorkScheduler.Kind.IMAGE, true, () -> order.add("high"));
            assertTrue(scheduler.prioritize(promoted));
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            low.get(5, TimeUnit.SECONDS);
            promoted.get(5, TimeUnit.SECONDS);
            high.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("promoted", "high", "low"), order);
            assertFalse(scheduler.prioritize(high));
        }
    }

    @Test
    void queuedJobsAreBoundedAndCancellationFreesSpace() throws Exception {
        try (WorkScheduler scheduler = new WorkScheduler(() -> "session")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            scheduler.submit(WorkScheduler.Kind.IMAGE, true, () -> {
                started.countDown();
                release.await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            List<Future<?>> queued = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                queued.add(scheduler.submit(WorkScheduler.Kind.IMAGE, false, () -> null));
            }
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.submit(WorkScheduler.Kind.IMAGE, false, () -> null));
            queued.getFirst().cancel(false);
            scheduler.submit(WorkScheduler.Kind.IMAGE, true, () -> null);
            scheduler.close();
            assertTrue(queued.stream().allMatch(Future::isCancelled));
        }
    }

    @Test
    void preparationModeAdmitsSecondAudioWorkerWithoutWaitingForFirst() throws Exception {
        AtomicReference<String> mode = new AtomicReference<>("session");
        try (WorkScheduler scheduler = new WorkScheduler(mode::get)) {
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> a = scheduler.submit(WorkScheduler.Kind.AUDIO, true, () -> {
                first.countDown();
                release.await();
                return null;
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            Future<?> b = scheduler.submit(WorkScheduler.Kind.AUDIO, true, () -> {
                second.countDown();
                release.await();
                return null;
            });
            assertEquals(1, second.getCount());
            mode.set("preparation");
            scheduler.refreshPolicy();
            assertTrue(second.await(5, TimeUnit.SECONDS));
            mode.set("session");
            scheduler.refreshPolicy();
            release.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockingStagePropagatesFailuresAndSameLaneDoesNotDeadlock() throws Exception {
        try (WorkScheduler scheduler = new WorkScheduler(() -> "session")) {
            IOException error = new IOException("Cannot read source.");
            assertSame(error, assertThrows(IOException.class,
                    () -> scheduler.run(WorkScheduler.Kind.COPY, () -> { throw error; })));
            Future<String> nested = scheduler.submit(WorkScheduler.Kind.IMAGE, true,
                    () -> scheduler.run(WorkScheduler.Kind.IMAGE, () -> "ready"));
            assertEquals("ready", nested.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedWaitCancelsWorkerAndPreservesInterrupt() throws Exception {
        try (WorkScheduler scheduler = new WorkScheduler(() -> "session")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    scheduler.run(WorkScheduler.Kind.AUDIO, () -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return null;
                        } catch (InterruptedException ex) {
                            interrupted.countDown();
                            throw new InterruptedIOException("Cancelled analysis.");
                        }
                    });
                } catch (Throwable ex) {
                    failure.set(ex);
                    interruptFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedIOException.class, failure.get());
            assertEquals(Boolean.TRUE, interruptFlag.get());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }
}
