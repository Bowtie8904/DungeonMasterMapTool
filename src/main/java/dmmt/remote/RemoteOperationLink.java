package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.log.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Client owner-thread queue; only the exact uncertain in-flight mutation survives a connection loss. */
public final class RemoteOperationLink implements RemoteOperations.Connection, AutoCloseable {
    private static final Log LOG = Log.of(RemoteOperationLink.class);
    private record Queued(String domain, String action, JsonNode payload, CompletableFuture<JsonNode> result) { }
    private final Thread ownerThread = Thread.currentThread();
    private final BooleanSupplier connected;
    private final Function<RemoteOperations.Query, CompletableFuture<RemoteOperations.Reply>> transport;
    private final BiFunction<RemoteAssetTransfer.Direction, RemoteAssetStore.AssetDescriptor,
            CompletableFuture<RemoteAssetStore.AssetDescriptor>> transfer;
    private final RemoteAssetStore store;
    private final Path cache;
    private final Executor worker, owner;
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private final java.util.Map<String, String> transferLabels = new java.util.concurrent.ConcurrentHashMap<>();
    private Queued active;
    private RemoteOperations.Command command;
    private UUID session;
    private long sequence;
    private boolean sending, closed, uncertain;
    private long nextAttempt;
    private java.util.function.Consumer<Throwable> errorHandler = failure -> { };
    private java.util.function.Function<Path, Runnable> uploadObserver = file -> () -> { };
    private java.util.function.Supplier<java.util.List<RemoteSessionClient.TransferProgress>> progressSource =
            () -> java.util.List.of();

    /** Supplies live asset transfer progress for download popups. */
    public void setProgressSource(java.util.function.Supplier<java.util.List<RemoteSessionClient.TransferProgress>> source) {
        progressSource = Objects.requireNonNull(source);
    }

    @Override public java.util.List<RemoteSessionClient.TransferProgress> transferProgress() {
        return progressSource.get();
    }

    /** Called on the owner thread when an upload starts; the returned handle runs when it completes. */
    public void setUploadObserver(java.util.function.Function<Path, Runnable> observer) {
        requireOwner();
        uploadObserver = Objects.requireNonNull(observer);
    }

    public void setErrorHandler(java.util.function.Consumer<Throwable> handler) {
        requireOwner();
        errorHandler = Objects.requireNonNull(handler);
    }

    private IOException rejected(RemoteOperations.Reply reply) {
        IOException failure = reply.failure() == null ? new IOException(reply.error()) : reply.failure().exception();
        LOG.warn("Host rejected an operation: {}", RemoteSessionServer.safe(failure.getMessage()));
        errorHandler.accept(failure);
        return failure;
    }

    public RemoteOperationLink(BooleanSupplier connected,
                               Function<RemoteOperations.Query, CompletableFuture<RemoteOperations.Reply>> transport,
                               BiFunction<RemoteAssetTransfer.Direction, RemoteAssetStore.AssetDescriptor,
                                       CompletableFuture<RemoteAssetStore.AssetDescriptor>> transfer,
                               RemoteAssetStore store, Path cache, Executor worker, Executor owner) {
        this.connected = connected;
        this.transport = transport;
        this.transfer = transfer;
        this.store = store;
        this.cache = cache;
        this.worker = worker;
        this.owner = owner;
    }

    @Override public boolean connected() { return !closed && !uncertain && connected.getAsBoolean(); }

    @Override public CompletableFuture<JsonNode> query(String domain, String action, JsonNode payload) {
        requireOwner();
        if (!connected()) return CompletableFuture.failedFuture(new IOException("Host operations are disconnected."));
        var result = new CompletableFuture<JsonNode>();
        var request = new RemoteOperations.Query(null, domain, action, payload);
        transport.apply(request).whenCompleteAsync((reply, failure) -> {
            if (closed) result.completeExceptionally(new IOException("Remote workspace closed."));
            else if (failure != null) result.completeExceptionally(failure);
            else if (reply.status() != RemoteOperations.Status.ACCEPTED) result.completeExceptionally(rejected(reply));
            else result.complete(reply.value());
        }, owner);
        return result;
    }

    @Override public CompletableFuture<JsonNode> command(String domain, String action, JsonNode payload) {
        requireOwner();
        if (!connected()) return CompletableFuture.failedFuture(new IOException("Host operations are disconnected; this edit was not queued."));
        if (queue.size() >= 8) {
            LOG.warn("Host operation {}/{} dropped: too many pending operations", domain, action);
            return CompletableFuture.failedFuture(new IOException("Too many pending host operations."));
        }
        var result = new CompletableFuture<JsonNode>();
        new RemoteOperations.Query(null, domain, action, payload);
        queue.addLast(new Queued(domain, action, payload.deepCopy(), result));
        LOG.debug("Queued host operation {}/{}", domain, action);
        tick();
        return result;
    }

    public void tick() {
        requireOwner();
        if (closed) return;
        if (!connected.getAsBoolean()) {
            while (!queue.isEmpty()) queue.removeFirst().result().completeExceptionally(
                    new IOException("Connection lost before this host operation was sent."));
            return;
        }
        if (sending || uncertain || System.nanoTime() < nextAttempt) return;
        if (active == null) active = queue.pollFirst();
        if (active == null) return;
        sending = true;
        if (session == null) {
            transport.apply(new RemoteOperations.Query(null, null, null, null)).whenCompleteAsync((reply, failure) -> {
                sending = false;
                if (closed) return;
                if (failure != null) {
                    active.result().completeExceptionally(failure);
                    active = null;
                } else {
                    session = reply.sessionId();
                    sequence = reply.nextSequence();
                    tick();
                }
            }, owner);
            return;
        }
        if (command == null) command = new RemoteOperations.Command(session, UUID.randomUUID(), sequence,
                active.domain(), active.action(), active.payload());
        var exact = command;
        transport.apply(new RemoteOperations.Query(exact, null, null, null)).whenCompleteAsync((reply, failure) -> {
            sending = false;
            if (closed || command != exact) return;
            if (failure != null) {
                nextAttempt = System.nanoTime() + 250_000_000L;
                LOG.debug("Host operation {}/{} will be retried", exact.domain(), exact.action(), failure);
                return; // The exact request remains pending until the transport reconnects.
            }
            if (!session.equals(reply.sessionId()) || !exact.id().equals(reply.commandId())
                    || exact.sequence() != reply.sequence() || reply.status() == RemoteOperations.Status.RESYNC_REQUIRED) {
                uncertain = true;
                LOG.error("Host operation {}/{} outcome is uncertain; inspect the host before repeating it",
                          exact.domain(), exact.action());
                active.result().completeExceptionally(new IOException(
                        "Host operation outcome is uncertain. Inspect A before returning to local mode or repeating it."));
                while (!queue.isEmpty()) queue.removeFirst().result().completeExceptionally(new IOException("Host operation synchronization required."));
                return;
            }
            if (reply.status() == RemoteOperations.Status.RUNNING) {
                nextAttempt = System.nanoTime() + 250_000_000L;
                return;
            }
            nextAttempt = 0;
            sequence = reply.nextSequence();
            var result = active.result();
            active = null;
            command = null;
            if (reply.status() == RemoteOperations.Status.ACCEPTED) result.complete(reply.value());
            else result.completeExceptionally(rejected(reply));
            tick();
        }, owner);
    }

    @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file) {
        requireOwner();
        if (!connected()) return CompletableFuture.failedFuture(new IOException("Host asset upload requires a connected workspace."));
        var done = uploadObserver.apply(file);
        return CompletableFuture.supplyAsync(() -> {
            try { return store.publishRequiredAsset(file); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, worker).thenComposeAsync(asset -> {
            if (!connected()) return CompletableFuture.failedFuture(new IOException("Host disconnected before upload."));
            if (file.getFileName() != null) transferLabels.put(asset.sha256(), file.getFileName().toString());
            return transfer.apply(RemoteAssetTransfer.Direction.UPLOAD, asset).whenComplete(
                    (accepted, failure) -> transferLabels.remove(asset.sha256()));
        }, owner).whenCompleteAsync((asset, failure) -> done.run(), owner).thenApplyAsync(Function.identity(), owner);
    }

    @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset, String label) {
        if (label != null && !label.isBlank()) transferLabels.put(asset.sha256(), label);
        return download(asset).whenComplete((path, failure) -> transferLabels.remove(asset.sha256()));
    }

    /** Human-readable name of an in-flight transfer, or null when unknown. */
    public String transferLabel(String sha256) {
        return sha256 == null ? null : transferLabels.get(sha256);
    }

    @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset) {
        requireOwner();
        Objects.requireNonNull(asset);
        if (!connected()) return CompletableFuture.failedFuture(new IOException("Host asset download requires a connected workspace."));
        return transfer.apply(RemoteAssetTransfer.Direction.DOWNLOAD, asset).thenApplyAsync(accepted -> {
            try { return store.materialize(accepted.sha256(), cache, "bin"); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, worker).thenApplyAsync(Function.identity(), owner);
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        if (active != null) active.result().completeExceptionally(new IOException(
                command == null ? "Remote workspace closed before the operation was sent."
                        : "Remote workspace closed with an unconfirmed host operation; inspect A."));
        while (!queue.isEmpty()) queue.removeFirst().result().completeExceptionally(new IOException("Remote workspace closed."));
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Client operations require the application thread.");
    }
}
