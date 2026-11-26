package dmmt.online;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineOutboxTest {
    private static final class Recorder implements OnlineOutbox.Sink {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile CountDownLatch entered = new CountDownLatch(0);

        @Override
        public void send(String json) throws Exception {
            entered.countDown();
            gate.await(5, TimeUnit.SECONDS);
            sent.add(json);
        }

        @Override
        public void close(int code, String reason) {
            closed.add(code + ":" + reason);
        }
    }

    private static void await(Runnable check) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError failure) {
                if (System.nanoTime() > end) throw failure;
                Thread.sleep(5);
            }
        }
    }

    @Test
    void deliversInOrderAndClosesAfterTheQueue() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Recorder sink = new Recorder();
            OnlineOutbox outbox = new OnlineOutbox(sink, executor, 100, 1 << 20, () -> null);
            for (int i = 0; i < 50; i++) outbox.send("m" + i);
            outbox.close(1008, "bye");
            await(() -> assertEquals(List.of("1008:bye"), sink.closed));
            assertEquals(50, sink.sent.size());
            for (int i = 0; i < 50; i++) assertEquals("m" + i, sink.sent.get(i));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aSlowConnectionNeverBlocksTheCaller() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Recorder sink = new Recorder();
            sink.gate = new CountDownLatch(1);
            OnlineOutbox outbox = new OnlineOutbox(sink, executor, 100, 1 << 20, () -> null);
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) outbox.send("m" + i);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
            sink.gate.countDown();
            await(() -> assertEquals(20, sink.sent.size()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aClientTooFarBehindReceivesOneFreshSnapshotInsteadOfTheBacklog() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Recorder sink = new Recorder();
            sink.gate = new CountDownLatch(1);
            OnlineOutbox outbox = new OnlineOutbox(sink, executor, 5, 1 << 20, () -> "snapshot");
            for (int i = 0; i < 50; i++) outbox.send("m" + i);
            sink.gate.countDown();
            await(() -> assertTrue(sink.sent.contains("snapshot")));
            await(() -> assertEquals(0, outbox.pending()));
            assertTrue(sink.sent.size() < 50);
            assertEquals("snapshot", sink.sent.get(sink.sent.size() - 1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void discardedOutboxesSendNothingMore() throws Exception {
        Executor direct = Runnable::run;
        Recorder sink = new Recorder();
        OnlineOutbox outbox = new OnlineOutbox(sink, direct, 10, 1 << 20, () -> null);
        outbox.discard();
        outbox.send("late");
        assertTrue(sink.sent.isEmpty());
    }

    @Test
    void coalescesOnlyAdjacentMovesForTheSameToken() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Recorder sink = new Recorder();
            sink.gate = new CountDownLatch(1);
            sink.entered = new CountDownLatch(1);
            OnlineOutbox outbox = new OnlineOutbox(sink, executor, 100, 1 << 20, () -> null);
            outbox.send("first");
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
            outbox.sendLatest("map/token-a", "move-a1");
            outbox.sendLatest("map/token-a", "move-a2");
            outbox.send("delta");
            outbox.sendLatest("map/token-a", "move-a3");
            outbox.sendLatest("map/token-b", "move-b1");
            sink.gate.countDown();
            await(() -> assertEquals(5, sink.sent.size()));
            assertEquals(List.of("first", "move-a2", "delta", "move-a3", "move-b1"), sink.sent);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedOverflowSoonAfterResyncNotifiesAndClosesSlowClient() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Recorder sink = new Recorder();
            sink.gate = new CountDownLatch(1);
            OnlineOutbox outbox = new OnlineOutbox(sink, executor, 2, 1 << 20, () -> "snapshot");
            outbox.send("initial");
            outbox.send("queued-1");
            outbox.send("queued-2");
            outbox.send("queued-3");
            sink.gate.countDown();
            await(() -> assertTrue(sink.sent.contains("snapshot")));

            sink.gate = new CountDownLatch(1);
            sink.entered = new CountDownLatch(1);
            outbox.send("hold");
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
            outbox.send("overflow-1");
            outbox.send("overflow-2");
            outbox.send("overflow-3");
            sink.gate.countDown();

            await(() -> assertEquals(1, sink.closed.size()));
            assertTrue(sink.sent.stream().anyMatch(message -> message.contains("\"type\":\"kicked\"")
                    && message.contains("cannot keep up")));
            assertTrue(sink.closed.get(0).startsWith("1008:Connection too slow"));
            assertEquals(1, sink.sent.stream().filter("snapshot"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }
}
