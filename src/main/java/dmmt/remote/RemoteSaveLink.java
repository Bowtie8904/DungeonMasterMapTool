package dmmt.remote;

import dmmt.log.Log;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** On-demand Save and exact-receipt recovery; no save metadata polling. */
public final class RemoteSaveLink implements AutoCloseable {
    public interface Connection {
        boolean connected();
        CompletableFuture<RemoteSaves.Update> query(RemoteSaves.Query query);
    }
    public record Destination(String name, String folder) { }
    private static final Log LOG = Log.of(RemoteSaveLink.class);
    private final Thread thread = Thread.currentThread();
    private final Connection connection;
    private final Executor owner;
    private final Consumer<String> notices;
    private RemoteSaves.Request pending;
    private boolean preparing, sending, closed, uncertain;

    public RemoteSaveLink(Connection connection, Executor owner, Consumer<String> notices) {
        this.connection = connection;
        this.owner = owner;
        this.notices = notices;
    }

    public boolean busy() {
        requireOwner();
        return preparing || pending != null || uncertain;
    }

    public void save(RemoteWorkspaceAuthority.State workspace, Function<RemoteSaves.Target, Destination> choose) {
        saveAsync(workspace, choose);
    }

    private CompletableFuture<Boolean> completion;

    public CompletableFuture<Boolean> saveAsync(RemoteWorkspaceAuthority.State workspace, Function<RemoteSaves.Target, Destination> choose) {
        requireOwner();
        if (closed || busy() || !connection.connected()) {
            LOG.warn("Host save request ignored: closed={}, busy={}, connected={}", closed, busy(), connection.connected());
            notices.accept("Wait for the current save or reconnect to confirm its outcome.");
            return CompletableFuture.failedFuture(new java.io.IOException("Wait for the current save or reconnect to confirm its outcome."));
        }
        completion = new CompletableFuture<>();
        LOG.info("Requesting save of the host map");
        var result = completion;
        preparing = true;
        connection.query(new RemoteSaves.Query(null)).whenComplete((update, failure) -> owner.execute(() -> {
            if (closed) return;
            try {
                if (failure != null) {
                    LOG.warn("Could not read the host save destination", failure);
                    notices.accept("Could not read the host save destination. Reconnect before saving.");
                    result.completeExceptionally(failure);
                    return;
                }
                var state = update.state();
                if (!state.contextId().equals(workspace.contextId()) || state.revision() != workspace.revision()) {
                    LOG.warn("Host save aborted: the host map changed since synchronization");
                    notices.accept("The host map changed. Synchronize before saving.");
                    result.completeExceptionally(new java.io.IOException("The host map changed before saving."));
                    return;
                }
                var destination = state.target().saved() ? null : choose.apply(state.target());
                if (!state.target().saved() && destination == null) {
                    LOG.info("Host save cancelled before a destination was chosen");
                    result.complete(false);
                    return;
                }
                if (closed || !connection.connected()) {
                    notices.accept("The host disconnected. Save was not sent.");
                    result.completeExceptionally(new java.io.IOException("The host disconnected before saving."));
                    return;
                }
                pending = new RemoteSaves.Request(state.sessionId(), state.contextId(), UUID.randomUUID(),
                        state.nextSequence(), state.revision(), destination == null ? null : destination.name(),
                        destination == null ? null : destination.folder());
                send();
            } catch (IllegalArgumentException invalid) {
                notices.accept("Invalid host save name or destination: " + invalid.getMessage());
                LOG.warn("Invalid host save destination", invalid);
                result.completeExceptionally(invalid);
            } finally {
                preparing = false;
            }
        }));
        return result;
    }

    public void tick() {
        requireOwner();
        if (!closed && pending != null && !sending && !uncertain && connection.connected()) send();
    }

    private void send() {
        sending = true;
        var request = pending;
        connection.query(new RemoteSaves.Query(request)).whenComplete((update, failure) -> owner.execute(() -> {
            if (closed || pending != request) return;
            sending = false;
            if (failure != null) {
                LOG.warn("Host save outcome unconfirmed", failure);
                notices.accept("Host save outcome unconfirmed. Reconnect to recover the exact receipt; do not save again.");
                return;
            }
            var receipt = update.receipt();
            if (receipt == null || !receipt.commandId().equals(request.commandId()) || receipt.sequence() != request.sequence()
                    || receipt.status() == RemotePlayerSession.Status.RESYNC_REQUIRED) {
                uncertain = true;
                LOG.error("Host save receipt unavailable; the saved map must be inspected on the host");
                notices.accept("Host save receipt unavailable. Inspect the saved map on A before returning to local mode.");
                completion.completeExceptionally(new java.io.IOException("Host save receipt unavailable; inspect A."));
                return;
            }
            pending = null;
            LOG.info("Host save receipt {}: {}", receipt.status(), RemoteSessionServer.safe(receipt.message()));
            notices.accept(receipt.message());
            if (receipt.status() == RemotePlayerSession.Status.ACCEPTED) completion.complete(true);
            else completion.completeExceptionally(new java.io.IOException(receipt.message()));
        }));
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        if (pending != null) {
            LOG.warn("Leaving remote mode with an unconfirmed host save");
            notices.accept("Leaving remote mode with an unconfirmed save; inspect the map on A.");
        }
        if (completion != null && !completion.isDone()) completion.completeExceptionally(new java.io.IOException("Remote workspace closed before save confirmation."));
        pending = null;
    }

    private void requireOwner() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Remote saves require the application thread.");
    }
}
