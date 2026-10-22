package dmmt.remote;

import dmmt.log.Log;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Host persistence commands are separate from project history and have exact, bounded retry receipts. */
public final class RemoteSaves implements AutoCloseable {
    private static final Log LOG = Log.of(RemoteSaves.class);
    public record Target(@JsonProperty(required = true) boolean saved, String label, String libraryFolder) {
        public Target {
            if (label == null || label.isBlank() || label.length() > 512
                    || libraryFolder == null || libraryFolder.isBlank() || libraryFolder.length() > 4096) {
                throw new IllegalArgumentException("Invalid host save target.");
            }
        }
    }

    public record State(UUID sessionId, UUID contextId, @JsonProperty(required = true) long revision,
                        @JsonProperty(required = true) long nextSequence, Target target) {
        public State {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(contextId);
            Objects.requireNonNull(target);
            if (revision < 0 || nextSequence < 1) throw new IllegalArgumentException("Invalid host save state.");
        }
    }

    public record Request(UUID sessionId, UUID contextId, UUID commandId,
                          @JsonProperty(required = true) long sequence,
                          @JsonProperty(required = true) long revision, String name, String folder) {
        public Request {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(contextId);
            Objects.requireNonNull(commandId);
            if (sequence < 1 || revision < 0 || (name == null) != (folder == null)
                    || name != null && (name.isBlank() || name.length() > 200
                    || name.codePoints().anyMatch(Character::isISOControl)
                    || folder.isBlank() || folder.length() > 4096 || folder.codePoints().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Invalid host save request.");
            }
        }
    }

    public record Query(Request request) { }
    public record Update(State state, RemotePlayerSession.Receipt receipt) {
        public Update { Objects.requireNonNull(state); }
    }
    private record Stored(Request request, RemotePlayerSession.Receipt receipt) { }

    private final Thread owner = Thread.currentThread();
    private final UUID session = UUID.randomUUID();
    private final Supplier<RemoteWorkspaceAuthority.State> workspace;
    private final Supplier<Target> target;
    private final BooleanSupplier ready;
    private final Function<Request, CompletableFuture<String>> save;
    private final Executor dispatcher;
    private final LinkedHashMap<Long, Stored> receipts = new LinkedHashMap<>();
    private long nextSequence = 1;
    private Request running;
    private CompletableFuture<Update> completion;
    private boolean closed;

    public RemoteSaves(Supplier<RemoteWorkspaceAuthority.State> workspace, Supplier<Target> target,
                       BooleanSupplier ready, Function<Request, CompletableFuture<String>> save, Executor dispatcher) {
        this.workspace = workspace;
        this.target = target;
        this.ready = ready;
        this.save = save;
        this.dispatcher = dispatcher;
    }

    public State state() {
        requireOwner();
        var current = workspace.get();
        return new State(session, current.contextId(), current.revision(), nextSequence, target.get());
    }

    public CompletableFuture<Update> exchange(Query query, Runnable authorize) {
        requireOwner();
        if (closed) return CompletableFuture.failedFuture(new IOException("Host save session is closed."));
        authorize.run();
        var request = query.request();
        if (request == null) return CompletableFuture.completedFuture(new Update(state(), null));
        if (!request.sessionId().equals(session)) return result(request, RemotePlayerSession.Status.RESYNC_REQUIRED, "The save session changed.");
        var stored = receipts.get(request.sequence());
        if (stored != null) return stored.request().equals(request)
                ? CompletableFuture.completedFuture(new Update(state(), stored.receipt()))
                : result(request, RemotePlayerSession.Status.RESYNC_REQUIRED, "The save receipt does not match.");
        if (request.equals(running)) return completion.copy();
        if (request.sequence() != nextSequence || running != null || receipts.values().stream()
                .anyMatch(value -> value.request().commandId().equals(request.commandId()))) {
            return result(request, RemotePlayerSession.Status.RESYNC_REQUIRED, "The save receipt is unavailable; do not repeat the save blindly.");
        }
        if (!ready.getAsBoolean()) return remember(request, RemotePlayerSession.Status.BUSY,
                "The host is finishing an operation. Save was not started; try again when ready.");
        var current = state();
        if (!current.contextId().equals(request.contextId()) || current.revision() != request.revision()) {
            return remember(request, RemotePlayerSession.Status.CONFLICT, "The host map changed. Synchronize before saving.");
        }
        if (current.target().saved() ? request.name() != null : request.name() == null) {
            return remember(request, RemotePlayerSession.Status.CONFLICT, "The host save destination changed. Open Save again.");
        }
        running = request;
        completion = new CompletableFuture<>();
        var pending = completion;
        LOG.info("Remote host save requested ({})", request.name() == null ? "current destination" : "save as");
        try {
            save.apply(request).whenComplete((message, failure) -> dispatcher.execute(() -> {
                requireOwner();
                if (closed) return;
                running = null;
                var status = failure == null ? RemotePlayerSession.Status.ACCEPTED : RemotePlayerSession.Status.FAILED;
                if (failure != null) LOG.warn("Host save failed.", failure);
                else LOG.info("Remote host save completed");
                String description = failure == null ? message
                        : failureMessage(failure);
                pending.complete(remember(request, status, description).join());
                completion = null;
            }));
        } catch (RuntimeException failure) {
            LOG.warn("Host save could not start.", failure);
            running = null;
            pending.complete(remember(request, RemotePlayerSession.Status.FAILED, "Host save could not start; see host logs.").join());
            completion = null;
        }
        return pending.copy();
    }

    private CompletableFuture<Update> remember(Request request, RemotePlayerSession.Status status, String message) {
        var receipt = new RemotePlayerSession.Receipt(request.commandId(), request.sequence(), status, message);
        receipts.put(request.sequence(), new Stored(request, receipt));
        nextSequence++;
        while (receipts.size() > 256) receipts.remove(receipts.keySet().iterator().next());
        return CompletableFuture.completedFuture(new Update(state(), receipt));
    }

    private CompletableFuture<Update> result(Request request, RemotePlayerSession.Status status, String message) {
        return CompletableFuture.completedFuture(new Update(state(),
                new RemotePlayerSession.Receipt(request.commandId(), request.sequence(), status, message)));
    }

    private static String failureMessage(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof IOException && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            String detail = failure.getMessage().replaceAll("\\p{Cntrl}", " ");
            return "Save on host failed: " + detail.substring(0, Math.min(detail.length(), 350))
                    + " Check the host destination before retrying.";
        }
        return "Save on host failed; inspect the host status and destination before retrying.";
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        receipts.clear();
        if (completion != null) completion.completeExceptionally(new IOException("Hosting stopped; the accepted save may still finish on A."));
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Host save commands require the application thread.");
    }
}
