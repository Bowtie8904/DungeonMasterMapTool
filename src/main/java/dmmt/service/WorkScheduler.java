package dmmt.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Separate resource lanes keep large image decodes and audio analysis from spawning unlimited workers. */
public final class WorkScheduler implements AutoCloseable {
    public enum Kind { COPY, IMAGE, AUDIO }

    @FunctionalInterface
    public interface IoCallable<T> {
        T run() throws IOException;
    }

    private static volatile WorkScheduler shared;
    private static final ThreadLocal<Kind> CURRENT_LANE = new ThreadLocal<>();
    private final Map<Kind, Lane> lanes = new EnumMap<>(Kind.class);
    private final Supplier<String> mode;
    private final AtomicLong sequence = new AtomicLong();

    public static synchronized WorkScheduler shared() {
        if (shared == null) {
            shared = new WorkScheduler(() -> Tuning.WORK_MODE.get());
        }
        return shared;
    }

    static void refreshSharedPolicy() {
        WorkScheduler scheduler = shared;
        if (scheduler != null) {
            scheduler.refreshPolicy();
        }
    }

    public static synchronized void shutdownShared() {
        if (shared != null) {
            shared.close();
            shared = null;
        }
    }

    WorkScheduler(Supplier<String> mode) {
        this.mode = mode;
        for (Kind kind : Kind.values()) {
            lanes.put(kind, new Lane(kind, kind == Kind.COPY ? 2 : 1));
        }
        refreshPolicy();
    }

    public synchronized void refreshPolicy() {
        Lane audio = lanes.get(Kind.AUDIO);
        int workers = "preparation".equals(mode.get()) ? 2 : 1;
        if (workers > audio.getMaximumPoolSize()) {
            audio.setMaximumPoolSize(workers);
            audio.setCorePoolSize(workers);
        } else {
            audio.setCorePoolSize(workers);
            audio.setMaximumPoolSize(workers);
        }
    }

    public ExecutorService executor(Kind kind) {
        refreshPolicy();
        return lanes.get(kind);
    }

    public <T> Future<T> submit(Kind kind, boolean foreground, Callable<T> work) {
        refreshPolicy();
        Lane lane = lanes.get(kind);
        PriorityTask<T> task = new PriorityTask<>(lane, foreground, work);
        lane.execute(task);
        return task;
    }

    /** Moves queued work ahead of background jobs; an already-running or finished job is left alone. */
    public boolean prioritize(Future<?> future) {
        if (!(future instanceof WorkScheduler.PriorityTask<?> task)) {
            return false;
        }
        Lane lane = task.lane;
        synchronized (lane) {
            if (!lane.getQueue().remove(task)) {
                return false;
            }
            task.foreground = true;
            try {
                lane.execute(task);
            } catch (RejectedExecutionException ex) {
                task.cancel(false);
                throw ex;
            }
            return true;
        }
    }

    /** Waits off the UI thread and propagates cancellation to the resource worker. */
    public <T> T run(Kind kind, IoCallable<T> work) throws IOException {
        if (CURRENT_LANE.get() == kind) {
            return work.run();
        }
        Future<T> future;
        try {
            future = submit(kind, true, work::run);
        } catch (RejectedExecutionException ex) {
            throw new IOException("Background " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + " queue is full or shutting down.", ex);
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Background work interrupted.");
            interrupted.initCause(ex);
            throw interrupted;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Background work failed.", cause);
        }
    }

    @Override
    public void close() {
        for (Lane lane : lanes.values()) {
            List<Runnable> waiting = lane.shutdownNow();
            waiting.forEach(task -> {
                if (task instanceof Future<?> future) {
                    future.cancel(false);
                }
            });
        }
    }

    private final class Lane extends ThreadPoolExecutor {
        Lane(Kind kind, int workers) {
            super(workers, workers, 0, TimeUnit.SECONDS, new WorkQueue(), runnable -> {
                Thread thread = new Thread(() -> {
                    CURRENT_LANE.set(kind);
                    try {
                        runnable.run();
                    } finally {
                        CURRENT_LANE.remove();
                    }
                }, "dmmt-work-" + kind.name().toLowerCase(java.util.Locale.ROOT));
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return new PriorityTask<>(this, true, callable);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            return newTaskFor(java.util.concurrent.Executors.callable(runnable, value));
        }

        @Override
        public void execute(Runnable command) {
            super.execute(command instanceof WorkScheduler.PriorityTask<?> ? command
                    : newTaskFor(command, null));
        }
    }

    private final class PriorityTask<T> extends FutureTask<T> implements Comparable<PriorityTask<?>> {
        private final Lane lane;
        private final long order = sequence.getAndIncrement();
        private volatile boolean foreground;

        PriorityTask(Lane lane, boolean foreground, Callable<T> callable) {
            super(callable);
            this.lane = lane;
            this.foreground = foreground;
        }

        @Override
        public int compareTo(PriorityTask<?> other) {
            int priority = Boolean.compare(other.foreground, foreground);
            return priority == 0 ? Long.compare(order, other.order) : priority;
        }

        @Override
        protected void done() {
            if (isCancelled()) {
                lane.remove(this);
            }
        }
    }

    private static final class WorkQueue extends PriorityBlockingQueue<Runnable> {
        @Override
        public synchronized boolean offer(Runnable task) {
            return size() < 256 && super.offer(task);
        }
    }
}
