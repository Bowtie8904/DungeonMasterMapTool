package dmmt.remote;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded worker-to-owner handoff. The executor must serialize on the application's owning thread. */
public final class RemoteOwnerDispatch {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteOwnerDispatch.class);
    private final Executor executor;
    private final int timeoutMillis;

    public RemoteOwnerDispatch(Executor executor, int timeoutMillis) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("A finite positive dispatch timeout is required.");
        }
        this.timeoutMillis = timeoutMillis;
    }

    /** For transport workers only; never wait on the owner thread. */
    public <T> T call(Callable<T> action) throws IOException {
        FutureTask<T> task = new FutureTask<>(action);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException stopped) {
            LOG.warn("Owner dispatch rejected: the application is no longer accepting remote requests");
            throw new IOException("The application is no longer accepting remote requests.", stopped);
        }
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            LOG.warn("Owner handoff interrupted");
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IOException("The remote application handoff was interrupted.", interrupted);
        } catch (TimeoutException expired) {
            LOG.warn("Owner handoff timed out after {} ms", timeoutMillis);
            // Cancelling a queued task prevents a timed-out request from starting later.
            // An already-running command has an uncertain outcome; its exact receipt must be recovered.
            task.cancel(false);
            throw new SocketTimeoutException("The application did not process the remote request in time.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            LOG.debug("Owner task failed", cause);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("The application could not process the remote request.", cause);
        }
    }

    public CompletableFuture<Void> submit(Runnable action) {
        try {
            return CompletableFuture.runAsync(action, executor);
        } catch (RejectedExecutionException stopped) {
            LOG.warn("Owner submit rejected: the application is shutting down");
            return CompletableFuture.failedFuture(stopped);
        }
    }

    /** Start persistence on the owner, wait only on the transport worker, then resume on the owner. */
    public <T> T callAsync(Callable<CompletableFuture<T>> action) throws IOException {
        CompletableFuture<T> completion = call(action);
        try {
            return completion.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Remote persistence acknowledgement was interrupted.", interrupted);
        } catch (TimeoutException expired) {
            // Persistence may have completed: leave it running and recover the exact receipt on reconnect.
            LOG.warn("Persistence acknowledgement timed out after {} ms", timeoutMillis);
            throw new SocketTimeoutException("The host did not acknowledge persistence in time.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Remote persistence failed.", cause);
        }
    }

    void execute(Runnable action) {
        executor.execute(action);
    }
}
