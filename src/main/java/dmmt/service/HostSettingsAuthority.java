package dmmt.service;

import dmmt.log.Log;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Host-side settings commands and retry receipts, owned by the constructing application thread. */
public final class HostSettingsAuthority implements AutoCloseable {
    private static final Log LOG = Log.of(HostSettingsAuthority.class);
    private static final int DEFAULT_RECEIPTS = 256;
    private static final Executor PERSISTENCE = task -> Thread.ofVirtual().name("host-settings-save").start(task);

    public enum Status { ACCEPTED, CONFLICT, INVALID, SAVE_FAILED, RESYNC_REQUIRED }

    public record State(UUID sessionId, AppSettings.HostSnapshot settings, long nextSequence) {
    }

    public record Request(UUID sessionId, long sequence, AppSettings.RemoteEdit edit) {
        public Request {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(edit, "edit");
            if (sequence < 1) {
                throw new IllegalArgumentException("Command sequence must be positive.");
            }
        }
    }

    public record Result(UUID commandId, long sequence, Status status, String message, State state) {
    }

    private record Receipt(Request request, Result result) {
    }

    private record Pending(Request request, AppSettings.PreparedSharedEdit prepared, CompletableFuture<Result> future) {
    }

    private final AppSettings settings;
    private final UUID sessionId = UUID.randomUUID();
    private final Thread owner = Thread.currentThread();
    private final int receiptLimit;
    private final Map<Long, Receipt> receipts = new LinkedHashMap<>();
    private long lastSequence;
    private boolean closed;
    private Pending pending;

    public HostSettingsAuthority(AppSettings settings) {
        this(settings, DEFAULT_RECEIPTS);
    }

    HostSettingsAuthority(AppSettings settings, int receiptLimit) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (receiptLimit < 1) {
            throw new IllegalArgumentException("At least one command receipt must be retained.");
        }
        this.receiptLimit = receiptLimit;
        settings.sharedStateInMemory();
    }

    public State state() {
        requireActive();
        return new State(sessionId, settings.sharedState().snapshot(), lastSequence + 1);
    }

    /** No filesystem access; external changes appear after ordinary polling or an async command. */
    public State stateInMemory() {
        requireActive();
        return memoryState();
    }

    private State memoryState() {
        return new State(sessionId, settings.sharedStateInMemory().snapshot(), lastSequence + 1);
    }

    /**
     * Synchronous standalone compatibility path. New remote transport callers should use executeAsync.
     * Expired receipts require resynchronization rather than executing a command twice.
     */
    public Result execute(Request request) {
        Objects.requireNonNull(request, "request");
        requireActive();
        if (pending != null) {
            return memoryResult(request, Status.RESYNC_REQUIRED, "A settings command is still being saved.");
        }
        synchronized (settings) {
            Result previous = previous(request, false);
            if (previous != null) {
                return previous;
            }
            Status status;
            String message;
            try {
                settings.applySharedEdit(request.edit());
                status = Status.ACCEPTED;
                message = "Settings saved on the host.";
            } catch (ConcurrentModificationException conflict) {
                status = Status.CONFLICT;
                message = conflict.getMessage();
            } catch (IllegalArgumentException invalid) {
                status = Status.INVALID;
                message = invalid.getMessage();
            } catch (IllegalStateException busy) {
                return memoryResult(request, Status.RESYNC_REQUIRED, busy.getMessage());
            } catch (IOException | SecurityException failure) {
                status = Status.SAVE_FAILED;
                message = "The host could not save the setting; the previous value was retained.";
                logFailure(request, failure);
            }
            return remember(request, status, message);
        }
    }

    /**
     * Prepare on the owner, persist on a worker, then commit/runtime-apply on the owner. The supplied
     * executor must reliably dispatch onto the constructing thread (for example Platform::runLater).
     * Cancelling a caller's future cannot cancel the host save or its retry receipt.
     */
    public CompletableFuture<Result> executeAsync(Request request, Executor ownerExecutor) {
        return executeAsync(request, ownerExecutor, PERSISTENCE);
    }

    /** Worker override for hosts that already own an IO executor, and deterministic tests. */
    public CompletableFuture<Result> executeAsync(Request request, Executor ownerExecutor, Executor workerExecutor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        requireActive();
        Result previous = previous(request, true);
        if (previous != null) {
            return CompletableFuture.completedFuture(previous);
        }
        if (pending != null) {
            return pending.request().equals(request) ? pending.future().copy()
                    : CompletableFuture.completedFuture(memoryResult(request, Status.RESYNC_REQUIRED,
                            "A settings command is still being saved."));
        }
        AppSettings.PreparedSharedEdit prepared;
        try {
            prepared = settings.prepareSharedEdit(request.edit());
        } catch (ConcurrentModificationException conflict) {
            return CompletableFuture.completedFuture(remember(request, Status.CONFLICT, conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(remember(request, Status.INVALID, invalid.getMessage()));
        } catch (IllegalStateException busy) {
            return CompletableFuture.completedFuture(memoryResult(request, Status.RESYNC_REQUIRED, busy.getMessage()));
        }
        Pending operation = new Pending(request, prepared, new CompletableFuture<>());
        pending = operation;
        work(operation, ownerExecutor, workerExecutor, () -> settings.readSharedExternalState(prepared), external -> {
            settings.mergeSharedExternalState(prepared, external);
            persist(operation, ownerExecutor, workerExecutor);
        });
        return operation.future().copy();
    }

    private Result previous(Request request, boolean inMemory) {
        Status status = Status.RESYNC_REQUIRED;
        String message;
        if (!sessionId.equals(request.sessionId())) {
            message = "The host settings session has changed.";
        } else {
            Receipt receipt = receipts.get(request.sequence());
            if (receipt != null) {
                if (receipt.request().equals(request)) {
                    return receipt.result();
                }
                message = "Command sequence was reused with different content.";
            } else if (request.sequence() != lastSequence + 1) {
                message = request.sequence() <= lastSequence ? "The command receipt has expired; its outcome is unknown."
                        : "A preceding command is missing.";
            } else if (receipts.values().stream().anyMatch(previous ->
                    previous.request().edit().commandId().equals(request.edit().commandId()))) {
                message = "Command ID was reused with a new sequence.";
            } else {
                return null;
            }
        }
        return new Result(request.edit().commandId(), request.sequence(), status, message,
                inMemory ? memoryState() : state());
    }

    private void persist(Pending operation, Executor ownerExecutor, Executor workerExecutor) {
        var attempt = settings.sharedEditAttempt(operation.prepared());
        work(operation, ownerExecutor, workerExecutor, () -> settings.persistSharedEdit(attempt), modified -> {
            if (!settings.commitSharedEdit(operation.prepared(), attempt, modified)) {
                persist(operation, ownerExecutor, workerExecutor);
                return;
            }
            Status status = closed || operation.prepared().cancelled ? Status.RESYNC_REQUIRED
                    : operation.prepared().conflict ? Status.CONFLICT : Status.ACCEPTED;
            finish(operation, status, status == Status.ACCEPTED ? "Settings saved on the host."
                    : status == Status.CONFLICT ? "This setting changed on the host: " + operation.request().edit().key()
                    : "The host settings session has closed.");
        });
    }

    @FunctionalInterface
    private interface IOAction<T> {
        T run() throws IOException;
    }

    private <T> void work(Pending operation, Executor ownerExecutor, Executor workerExecutor,
                          IOAction<T> action, Consumer<T> commit) {
        try {
            workerExecutor.execute(() -> {
                T value;
                try {
                    value = action.run();
                } catch (IOException | RuntimeException failure) {
                    dispatch(ownerExecutor, operation, () -> failed(operation, failure));
                    return;
                }
                dispatch(ownerExecutor, operation, () -> commit.accept(value));
            });
        } catch (RuntimeException rejected) {
            failed(operation, rejected);
        }
    }

    private void dispatch(Executor ownerExecutor, Pending operation, Runnable continuation) {
        try {
            ownerExecutor.execute(() -> {
                requireOwner();
                continuation.run();
            });
        } catch (RuntimeException rejected) {
            operation.future().completeExceptionally(rejected);
        }
    }

    private void failed(Pending operation, Exception failure) {
        requireOwner();
        settings.failSharedEdit(operation.prepared());
        logFailure(operation.request(), failure);
        finish(operation, Status.SAVE_FAILED, "The host could not save the setting; the current host value was retained.");
    }

    private static void logFailure(Request request, Exception failure) {
        LOG.warn("Could not persist settings command {}", request.edit().commandId(), failure);
    }

    private void finish(Pending operation, Status status, String message) {
        pending = null;
        Result result = closed
                ? memoryResult(operation.request(), Status.RESYNC_REQUIRED, "The host settings session has closed.")
                : remember(operation.request(), status, message);
        operation.future().complete(result);
    }

    private Result memoryResult(Request request, Status status, String message) {
        return new Result(request.edit().commandId(), request.sequence(), status, message, memoryState());
    }

    private Result remember(Request request, Status status, String message) {
        lastSequence = request.sequence();
        Result result = memoryResult(request, status, message);
        receipts.put(request.sequence(), new Receipt(request, result));
        if (receipts.size() > receiptLimit) {
            receipts.remove(receipts.keySet().iterator().next());
        }
        return result;
    }

    @Override
    public void close() {
        requireOwner();
        closed = true;
        receipts.clear();
        if (pending != null) {
            settings.cancelSharedEdit(pending.prepared());
        }
    }

    private void requireActive() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("The host settings session is closed.");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Host settings commands must run on their owning application thread.");
        }
    }
}
