package dmmt.online;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Ordered, bounded outgoing queue of one browser connection. Writes happen on the executor so a slow socket never
 * blocks the caller (the server lock) or the other connections. When a client falls too far behind, the queued
 * messages are replaced by a single fresh snapshot instead of letting memory and latency grow.
 */
final class OnlineOutbox {
    interface Sink {
        void send(String json) throws Exception;

        void close(int code, String reason);
    }

    private final Sink sink;
    private final Executor executor;
    private final int maxMessages;
    private final long maxBytes;
    private final Supplier<String> resync;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean draining;
    private boolean overflowed;
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
        synchronized (this) {
            if (closed || closeRequested) return;
            if (!overflowed && !queue.isEmpty()
                    && (queue.size() >= maxMessages || queuedBytes + json.length() > maxBytes)) {
                queue.clear();
                queuedBytes = 0;
                overflowed = true;
            }
            // While overflowed the snapshot sent on resync already contains this change.
            if (!overflowed) {
                queue.add(json);
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
            boolean snapshot = false;
            boolean close = false;
            synchronized (this) {
                if (closed) {
                    draining = false;
                    return;
                }
                if (overflowed) {
                    overflowed = false;
                    snapshot = true;
                } else if ((json = queue.poll()) != null) {
                    queuedBytes -= json.length();
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
                if (snapshot) json = resync.get();
                if (json != null) sink.send(json);
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
