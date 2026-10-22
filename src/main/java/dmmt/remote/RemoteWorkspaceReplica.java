package dmmt.remote;

import dmmt.model.DmProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owner-thread client workspace with worker-only asset IO and exact command recovery. */
public final class RemoteWorkspaceReplica implements AutoCloseable {
    public enum Phase { LOADING, READY, PENDING, PAUSED, ERROR, RESYNC_REQUIRED, CLOSED }

    public interface Connection extends AutoCloseable {
        default CompletableFuture<RemoteOperations.Reply> operation(RemoteOperations.Query query) {
            return CompletableFuture.failedFuture(new java.io.IOException("Host operations are unavailable."));
        }
        default java.util.function.Supplier<CompletableFuture<RemoteMapCatalog.Listing>> maps() { return null; }
        default RemoteSaveLink.Connection saves() { return null; }
        default RemotePlayerLink.Connection player() { return null; }
        default RemoteHandoutLink.Connection handouts() { return null; }
        boolean connected();
        boolean ready();
        default String unavailableReason() { return "The host is finishing an operation; editing paused."; }
        RemoteWorkspaceAuthority.State latest();
        CompletableFuture<RemoteWorkspaceAuthority.Result> send(RemoteWorkspaceAuthority.Request request);
        CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(RemoteAssetTransfer.Direction direction,
                                                                     RemoteAssetStore.AssetDescriptor asset);
        @Override void close();
    }

    public record Display(DmProject project, UUID contextId) {
    }

    public static Connection connection(RemoteSessionClient client, int transferPort, RemoteAssetStore store) {
        Objects.requireNonNull(client, "client");
        if (transferPort < 1 || transferPort > 65535) {
            throw new IllegalArgumentException("A valid host asset-transfer port is required.");
        }
        return new Connection() {
            @Override public CompletableFuture<RemoteOperations.Reply> operation(RemoteOperations.Query query) {
                return client.operation(query);
            }
            @Override public java.util.function.Supplier<CompletableFuture<RemoteMapCatalog.Listing>> maps() {
                return client::mapCatalog;
            }
            @Override public RemoteSaveLink.Connection saves() {
                return new RemoteSaveLink.Connection() {
                    @Override public boolean connected() { return client.phase() == RemoteSessionClient.Phase.SETTINGS_READY; }
                    @Override public CompletableFuture<RemoteSaves.Update> query(RemoteSaves.Query query) {
                        return client.saves(query);
                    }
                };
            }
            @Override
            public RemoteHandoutLink.Connection handouts() {
                client.enableHandouts();
                return new RemoteHandoutLink.Connection() {
                    @Override public boolean connected() { return client.phase() == RemoteSessionClient.Phase.SETTINGS_READY; }
                    @Override public RemoteHandouts.State latest() { return client.latestHandouts(); }
                    @Override public CompletableFuture<RemoteHandouts.Update> send(RemoteHandouts.Request request) {
                        return client.controlHandouts(request);
                    }
                    @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(
                            RemoteAssetTransfer.Direction direction, RemoteAssetStore.AssetDescriptor asset) {
                        return client.transferQueued(transferPort, store, direction, asset);
                    }
                };
            }

            @Override
            public RemotePlayerLink.Connection player() {
                return RemotePlayerLink.connection(client);
            }

            @Override
            public boolean connected() {
                return client.phase() == RemoteSessionClient.Phase.SETTINGS_READY;
            }

            @Override
            public boolean ready() {
                return client.workspaceReady();
            }

            @Override
            public String unavailableReason() {
                return client.workspaceStatus() == null ? "The host is finishing an operation; editing paused."
                        : client.workspaceStatus();
            }

            @Override
            public RemoteWorkspaceAuthority.State latest() {
                return client.latestWorkspace();
            }

            @Override
            public CompletableFuture<RemoteWorkspaceAuthority.Result> send(RemoteWorkspaceAuthority.Request request) {
                return client.editWorkspace(request);
            }

            @Override
            public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(RemoteAssetTransfer.Direction direction,
                                                                                RemoteAssetStore.AssetDescriptor asset) {
                return client.transferQueued(transferPort, store, direction, asset);
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    private final Thread owner = Thread.currentThread();
    private final Connection connection;
    private final RemoteAssetStore store;
    private final RemoteWorkspaceArtwork artwork;
    private final Supplier<DmProject> project;
    private final BooleanSupplier gesture;
    private final Runnable cancelGesture;
    private final Consumer<Display> display;
    private final Consumer<String> notices;
    private final Executor ownerExecutor;
    private final Executor workerExecutor;
    private RemoteWorkspaceClient reconciliation;
    private Phase phase = Phase.LOADING;
    private String status = "Loading the host workspace.";
    private RemoteWorkspaceAuthority.State preparedState;
    private RemoteWorkspaceAuthority.Result pendingResult;
    private CompletableFuture<?> work;
    private boolean sending;
    private long generation;
    private Consumer<Throwable> errorHandler = failure -> { };

    public void setErrorHandler(Consumer<Throwable> handler) {
        requireOwner();
        errorHandler = Objects.requireNonNull(handler);
    }

    public RemoteWorkspaceReplica(Connection connection, RemoteAssetStore store, Path cache,
                                  Supplier<DmProject> project, BooleanSupplier gesture, Runnable cancelGesture,
                                  Consumer<Display> display, Consumer<String> notices,
                                  Executor ownerExecutor, Executor workerExecutor) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.store = Objects.requireNonNull(store, "store");
        this.project = Objects.requireNonNull(project, "project");
        this.gesture = Objects.requireNonNull(gesture, "gesture");
        this.cancelGesture = Objects.requireNonNull(cancelGesture, "cancelGesture");
        this.display = Objects.requireNonNull(display, "display");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        artwork = new RemoteWorkspaceArtwork(store, cache, () -> null);
    }

    public Phase phase() {
        requireOwner();
        return phase;
    }

    public String status() {
        requireOwner();
        return status;
    }

    public RemoteWorkspaceAuthority.State accepted() {
        requireOwner();
        return reconciliation == null ? null : reconciliation.accepted();
    }

    public boolean canEdit() {
        requireOwner();
        return phase == Phase.READY && connection.connected() && connection.ready() && work == null
                && reconciliation != null && reconciliation.canEdit();
    }

    /** Called by the application pulse; no disk or network IO is performed on the owner. */
    public void tick() {
        requireOwner();
        if (phase == Phase.CLOSED || phase == Phase.ERROR || phase == Phase.RESYNC_REQUIRED) {
            return;
        }
        try {
            synchronize();
        } catch (RuntimeException failure) {
            System.getLogger(RemoteWorkspaceReplica.class.getName()).log(System.Logger.Level.ERROR,
                    "Remote workspace synchronization failed.", failure);
            fail("The host workspace could not be synchronized; editing paused.", failure);
        }
    }

    private void synchronize() {
        if (!connection.connected()) {
            pause("Disconnected - editing paused. Reconnect to recover any unconfirmed operation.");
            return;
        }
        if (work != null || sending) {
            return;
        }
        if (gesture.getAsBoolean()) {
            var latest = connection.latest();
            if (reconciliation == null || latest == null
                    || latest.contextId().equals(reconciliation.accepted().contextId())) {
                return;
            }
            cancelGesture.run();
            notices.accept("The host switched maps; the unfinished local edit was cancelled.");
        }
        if (!connection.ready()) {
            phase = Phase.PAUSED;
            status = connection.unavailableReason();
            return;
        }
        if (pendingResult != null) {
            prepare(pendingResult.state());
            return;
        }
        var latest = connection.latest();
        if (latest == null) {
            return;
        }
        if (preparedState != null && preparedState.equals(latest)) {
            acceptPrepared(preparedState);
            return;
        }
        if (reconciliation != null && reconciliation.phase() == RemoteWorkspaceClient.Phase.CONNECTED
                && reconciliation.accepted().equals(latest)) {
            phase = reconciliation.canEdit() ? Phase.READY : Phase.PENDING;
            status = phase == Phase.READY ? "Connected to the host workspace." : "Waiting for the host acknowledgement.";
            return;
        }
        prepare(latest);
    }

    /** The caller has finished its gesture. No local undo entry is created. */
    public void committed(String label) {
        requireOwner();
        if (!canEdit()) {
            throw new IllegalStateException("The remote workspace is not ready for an edit.");
        }
        var local = RemoteProjectDocument.capture(project.get());
        if (artwork.canCapture(local, null)) {
            submit(artwork.portable(local, null), label);
            return;
        }
        phase = Phase.PENDING;
        status = "Preparing and uploading artwork to the host.";
        long token = generation;
        CompletableFuture<RemoteWorkspaceArtwork.Prepared> preparation;
        try {
            preparation = CompletableFuture.supplyAsync(() -> prepareLocal(local), workerExecutor)
                    .thenCompose(prepared -> upload(prepared).thenApply(ignored -> prepared));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            unsentFailure("The artwork worker is unavailable; this edit was not sent.");
            return;
        }
        work = preparation;
        preparation.whenComplete((prepared, failure) -> dispatch(() -> {
            work = null;
            if (!current(token)) {
                return;
            }
            if (failure != null) {
                unsentFailure("Artwork upload failed; this edit was not sent to the host.");
                return;
            }
            artwork.accept(prepared);
            submit(prepared.document(), label);
        }));
    }

    public void history(RemoteWorkspaceAuthority.Operation operation) {
        requireOwner();
        if (!canEdit() || gesture.getAsBoolean()) {
            throw new IllegalStateException("Finish the current operation before changing shared history.");
        }
        send(reconciliation.submitHistory(operation));
    }

    public void discardUnsentEdit() {
        requireOwner();
        cancelGesture.run();
        if (reconciliation != null) {
            show(reconciliation.accepted().document());
        }
        notices.accept("Editing is paused; the additional local edit was not sent to the host.");
    }

    /** Retry preparation only; submitted commands retain their original IDs and sequence. */
    public void retry() {
        requireOwner();
        if (phase != Phase.ERROR || work != null) {
            throw new IllegalStateException("There is no failed workspace preparation to retry.");
        }
        phase = Phase.LOADING;
        tick();
    }

    @Override
    public void close() {
        requireOwner();
        if (phase == Phase.CLOSED) {
            return;
        }
        generation++;
        phase = Phase.CLOSED;
        try {
            connection.close();
        } finally {
            if (reconciliation != null) {
                reconciliation.close();
            }
            pendingResult = null;
            preparedState = null;
        }
    }

    private void prepare(RemoteWorkspaceAuthority.State state) {
        if (preparedState != null && preparedState.equals(state)) {
            acceptPrepared(state);
            return;
        }
        state.document().validatePortableAssets();
        if (artwork.canCapture(state.document(), null)) {
            preparedState = state;
            acceptPrepared(state);
            return;
        }
        phase = Phase.LOADING;
        status = "Downloading and verifying the host's active-map artwork.";
        long token = generation;
        CompletableFuture<RemoteWorkspaceArtwork.Prepared> preparation;
        try {
            preparation = CompletableFuture.runAsync(() -> { }, workerExecutor)
                    .thenComposeAsync(ignored -> download(state), workerExecutor)
                    .thenApplyAsync(ignored -> {
                        try {
                            return artwork.prepareIncoming(state.document());
                        } catch (IOException failure) {
                            throw new CompletionException(failure);
                        }
                    }, workerExecutor);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            fail("The workspace artwork worker is unavailable.", rejected);
            return;
        }
        work = preparation;
        preparation.whenComplete((prepared, failure) -> dispatch(() -> {
            work = null;
            if (!current(token)) {
                return;
            }
            if (failure != null) {
                fail("Could not download or verify the host artwork. Check access, storage and transfer status.", failure);
                return;
            }
            artwork.accept(prepared);
            preparedState = state;
            // A superseded baseline can leave useful verified assets, but must not briefly replace the newer map.
            if (pendingResult != null || state.equals(connection.latest())) {
                acceptPrepared(state);
            }
            tick();
        }));
    }

    private CompletableFuture<Void> download(RemoteWorkspaceAuthority.State state) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var reference : state.document().assetReferences()) {
            var descriptor = Objects.requireNonNull(state.assets().get(reference), "asset manifest");
            chain = chain.thenComposeAsync(ignored -> {
                try {
                    if (store.contains(descriptor.sha256())) {
                        if (!descriptor.equals(store.descriptor(descriptor.sha256()))) {
                            throw new IOException("The asset manifest differs from the verified cache.");
                        }
                        return CompletableFuture.completedFuture(null);
                    }
                    return connection.transfer(RemoteAssetTransfer.Direction.DOWNLOAD, descriptor)
                            .thenAccept(received -> requireAsset(descriptor, received));
                } catch (IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }, workerExecutor);
        }
        return chain;
    }

    private CompletableFuture<Void> upload(RemoteWorkspaceArtwork.Prepared prepared) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var descriptor : prepared.assets().values()) {
            chain = chain.thenCompose(ignored ->
                    connection.transfer(RemoteAssetTransfer.Direction.UPLOAD, descriptor)
                            .thenAccept(received -> requireAsset(descriptor, received)));
        }
        return chain;
    }

    private RemoteWorkspaceArtwork.Prepared prepareLocal(RemoteProjectDocument local) {
        try {
            return artwork.prepare(local, null);
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
    }

    private void acceptPrepared(RemoteWorkspaceAuthority.State state) {
        if (gesture.getAsBoolean() || !connection.connected() || !connection.ready()) {
            return;
        }
        if (reconciliation == null) {
            reconciliation = new RemoteWorkspaceClient(state, this::show, notice -> {
                notices.accept(notice.message());
                if (phase != Phase.CLOSED && notice.outcome() == RemoteWorkspaceAuthority.Status.RESYNC_REQUIRED) {
                    phase = Phase.RESYNC_REQUIRED;
                    status = notice.message();
                }
            });
            show(state.document());
        } else if (reconciliation.phase() == RemoteWorkspaceClient.Phase.PAUSED) {
            if (!reconciliation.resume(state)) {
                phase = Phase.RESYNC_REQUIRED;
                return;
            }
            if (reconciliation.request() != null && pendingResult == null) {
                send(reconciliation.request());
                return;
            }
        }
        if (pendingResult != null) {
            var result = pendingResult;
            pendingResult = null;
            reconciliation.receive(result);
        } else {
            reconciliation.update(state);
        }
        if (reconciliation.phase() == RemoteWorkspaceClient.Phase.RESYNC_REQUIRED) {
            phase = Phase.RESYNC_REQUIRED;
            return;
        }
        phase = reconciliation.canEdit() ? Phase.READY : Phase.PENDING;
        status = phase == Phase.READY ? "Connected to the host workspace." : "Waiting for the host acknowledgement.";
    }

    private void submit(RemoteProjectDocument portable, String label) {
        if (portable.equals(reconciliation.accepted().document())) {
            phase = Phase.READY;
            status = "No workspace changes to send.";
            return;
        }
        send(reconciliation.submit(portable, label));
    }

    private void send(RemoteWorkspaceAuthority.Request request) {
        phase = Phase.PENDING;
        status = "Waiting for the host to confirm: " + request.label();
        sending = true;
        long token = generation;
        CompletableFuture<RemoteWorkspaceAuthority.Result> response;
        try {
            response = connection.send(request);
        } catch (IllegalStateException unavailable) {
            sending = false;
            pause("The operation outcome is unconfirmed. Reconnect to recover its exact receipt.");
            return;
        }
        response.whenComplete((result, failure) -> dispatch(() -> {
            sending = false;
            if (!current(token)) {
                return;
            }
            if (failure != null) {
                pause("The operation outcome is unconfirmed. Reconnect to recover its exact receipt.");
                return;
            }
            pendingResult = Objects.requireNonNull(result, "result");
            tick();
        }));
    }

    private void show(RemoteProjectDocument document) {
        try {
            display.accept(new Display(artwork.materialize(document, project.get().getViews().getDmCamera()),
                    reconciliation.accepted().contextId()));
        } catch (IOException failure) {
            throw new IllegalArgumentException("The prepared workspace could not be materialized.", failure);
        }
    }

    private void unsentFailure(String message) {
        if (reconciliation != null) {
            show(reconciliation.accepted().document());
        }
        phase = Phase.READY;
        status = message;
        notices.accept(message);
    }

    private void pause(String message) {
        if (phase == Phase.PAUSED) {
            return;
        }
        generation++;
        cancelGesture.run();
        if (reconciliation != null) {
            reconciliation.pause();
        }
        preparedState = null;
        phase = Phase.PAUSED;
        status = message;
        notices.accept(message);
    }

    private boolean current(long token) {
        if (phase == Phase.CLOSED || token != generation) {
            return false;
        }
        if (!connection.connected()) {
            pause("Disconnected - editing paused.");
            return false;
        }
        return true;
    }

    private static void requireAsset(RemoteAssetStore.AssetDescriptor expected,
                                     RemoteAssetStore.AssetDescriptor received) {
        if (!expected.equals(received)) {
            throw new IllegalArgumentException("The transferred asset does not match the workspace manifest.");
        }
    }

    private void dispatch(Runnable action) {
        try {
            ownerExecutor.execute(() -> {
                requireOwner();
                if (phase == Phase.CLOSED) {
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    System.getLogger(RemoteWorkspaceReplica.class.getName()).log(System.Logger.Level.ERROR,
                            "Remote workspace reconciliation failed.", failure);
                    fail("Remote workspace reconciliation failed; editing paused.", failure);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            System.getLogger(RemoteWorkspaceReplica.class.getName()).log(System.Logger.Level.ERROR,
                    "Could not deliver remote workspace completion to its owner.", rejected);
        }
    }

    private void fail(String message) {
        phase = Phase.ERROR;
        status = message;
        notices.accept(message);
    }

    private void fail(String message, Throwable cause) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, message, cause);
        fail(message);
        errorHandler.accept(RemoteFailure.capture(message, cause).exception());
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("The remote workspace replica must run on its application owner thread.");
        }
    }
}
