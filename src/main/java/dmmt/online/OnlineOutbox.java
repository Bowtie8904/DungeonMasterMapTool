package dmmt.online;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Ordered, bounded outgoing queue of one browser connection. Writes happen on the executor so a slow socket never
 * blocks the caller (the server lock) or the other connections. When a client falls too far behind, the queued
 * messages are replaced by a single fresh snapshot instead of letting memory and latency grow.
 */
final class OnlineOutbox {
    private record QueuedMessage(String json, String coalesceKey) { }

    interface Sink {
        void send(String json) throws Exception;

        void close(int code, String reason);
    }

    private final Sink sink;
    private final Executor executor;
    private final int maxMessages;
    private final long maxBytes;
    private final Supplier<String> resync;
    private static final long RESYNC_RETRY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final String SLOW_CLIENT_NOTICE =
            "{\"type\":\"kicked\",\"reason\":\"Your connection cannot keep up. Reconnect on a faster network.\"}";
    private final ArrayDeque<QueuedMessage> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean draining;
    private boolean overflowed;
    private boolean resyncing;
    private long lastResyncNanos;
    private boolean slowNoticePending;
    private boolean closed;
    private int closeCode;
    private String closeReason;
    private boolean closeRequested;

    /** {@code resync} runs on the executor, outside this outbox's lock, and returns a snapshot or null. */
    OnlineOutbox(Sink sink, Executor executor, int maxMessages, long maxBytes, Supplier<String> resync) {
        this.sink = sink;
        this.executor = executor;
        this.maxMessages = Math.max(1, maxMessages);
        this.maxBytes = Math.max(1, maxBytes);
        this.resync = resync;
    }

    void send(String json) {
        enqueue(json, null);
    }

    void sendLatest(String key, String json) {
        enqueue(json, key);
    }

    private void enqueue(String json, String coalesceKey) {
        synchronized (this) {
            if (closed || closeRequested) return;
            if (overflowed) {
                schedule();
                return;
            }
            if (coalesceKey != null && !queue.isEmpty() && coalesceKey.equals(queue.peekLast().coalesceKey())) {
                QueuedMessage replaced = queue.removeLast();
                queuedBytes -= replaced.json().length();
            }
            if (!overflowed && !queue.isEmpty()
                    && (queue.size() >= maxMessages || queuedBytes + json.length() > maxBytes)) {
                queue.clear();
                queuedBytes = 0;
                if (resyncing || lastResyncNanos != 0
                        && System.nanoTime() - lastResyncNanos < RESYNC_RETRY_WINDOW_NANOS) {
                    closeRequested = true;
                    closeCode = 1008;
                    closeReason = "Connection too slow";
                    slowNoticePending = true;
                } else {
                    overflowed = true;
                }
            }
            // While overflowed the snapshot sent on resync already contains this change.
            if (!overflowed && !closeRequested) {
                queue.add(new QueuedMessage(json, coalesceKey));
                queuedBytes += json.length();
            }
            schedule();
        }
    }

    /** Closes the connection once everything queued before this call has been written. */
    synchronized void close(int code, String reason) {
        if (closed || closeRequested) return;
        closeRequested = true;
        closeCode = code;
        closeReason = reason;
        schedule();
    }

    /** Drops anything still queued; used when the connection is gone. */
    synchronized void discard() {
        closed = true;
        queue.clear();
        queuedBytes = 0;
    }

    synchronized int pending() {
        return queue.size();
    }

    private void schedule() {
        if (draining) return;
        draining = true;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException rejected) {
            draining = false;
            closed = true;
            queue.clear();
            queuedBytes = 0;
        }
    }

    private void drain() {
        while (true) {
            String json = null;
            QueuedMessage message = null;
            boolean snapshot = false;
            boolean slowNotice = false;
            boolean close = false;
            synchronized (this) {
                if (closed) {
                    draining = false;
                    return;
                }
                if (overflowed) {
                    overflowed = false;
                    resyncing = true;
                    snapshot = true;
                } else if (slowNoticePending) {
                    slowNoticePending = false;
                    slowNotice = true;
                } else if ((message = queue.poll()) != null) {
                    queuedBytes -= message.json().length();
                    json = message.json();
                } else if (closeRequested) {
                    close = true;
                    closed = true;
                } else {
                    draining = false;
                    return;
                }
            }
            try {
                if (close) {
                    sink.close(closeCode, closeReason);
                    return;
                }
                if (slowNotice) {
                    sink.send(SLOW_CLIENT_NOTICE);
                    continue;
                }
                if (snapshot) {
                    json = resync.get();
                    if (json != null) sink.send(json);
                    synchronized (this) {
                        resyncing = false;
                        if (json != null) lastResyncNanos = System.nanoTime();
                    }
                } else if (json != null) {
                    sink.send(json);
                }
            } catch (Exception failure) {
                synchronized (this) {
                    closed = true;
                    draining = false;
                    queue.clear();
                    queuedBytes = 0;
                }
                return;
            }
        }
    }
}
