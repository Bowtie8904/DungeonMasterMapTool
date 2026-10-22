package dmmt.remote;

import dmmt.log.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Pairing does not alter the local workspace. Only an approved, usable baseline may install a replica. */
public final class RemoteConnectionController implements AutoCloseable {
    private static final Log LOG = Log.of(RemoteConnectionController.class);
    public static final class LocalWorkspaceChangedException extends IllegalStateException {
        public LocalWorkspaceChangedException() {
            super("The local workspace changed while pairing. Resolve its current changes and connect again.");
        }
    }

    public enum Phase {
        STANDALONE, PREPARING, CONNECTING, AWAITING_APPROVAL, SYNCHRONIZING, CONNECTED, PAUSED, STOPPING, ERROR
    }

    public record Configuration(String host, int controlPort, int assetPort, String verifiedFingerprint,
                                String clientName, String sessionName, int timeoutMillis, RemoteSessionStorage storage) {
        public Configuration {
            host = Objects.requireNonNull(host, "host").strip();
            if (host.isEmpty() || host.length() > 253 || host.codePoints().anyMatch(Character::isISOControl)
                    || controlPort < 1 || controlPort > 65535 || assetPort < 1 || assetPort > 65535
                    || controlPort == assetPort || timeoutMillis < 1
                    || verifiedFingerprint == null || !verifiedFingerprint.matches("[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException("A host certificate pin and distinct control/artwork ports are required.");
            }
            clientName = new RemoteSessionAccess.Client(clientName, "client").name();
            sessionName = new RemoteSessionAccess.Client(sessionName, "host").name();
            Objects.requireNonNull(storage, "storage");
        }
    }

    public record Status(Phase phase, String message, String sessionName, String host, boolean canReconnect) {
    }

    public interface Workspace {
        /** The caller has resolved unsaved local work before starting this controller. */
        RemoteWorkspaceReplica open(RemoteWorkspaceReplica.Connection workspace, RemoteSettingsLink.Connection settings,
                                    RemoteAssetStore store, java.nio.file.Path cache, Executor worker);
        void close();
    }

    private final Thread owner = Thread.currentThread();
    private final Workspace workspace;
    private final Executor ownerExecutor;
    private final Executor workerExecutor;
    private Phase phase = Phase.STANDALONE;
    private String message = "Standalone workspace.";
    private Attempt attempt;

    private static final class Attempt {
        final Configuration configuration;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CompletableFuture<Void> stopped = new CompletableFuture<>();
        CompletableFuture<Prepared> preparation;
        RemoteSessionClient client;
        RemoteWorkspaceReplica replica;
        boolean cancelled;
        boolean installed;
        boolean connectedOnce;

        Attempt(Configuration configuration) {
            this.configuration = configuration;
        }
    }

    private record Prepared(InetAddress address, String build, RemoteAssetStore store) {
    }

    public RemoteConnectionController(Workspace workspace, Executor ownerExecutor, Executor workerExecutor) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    }

    /** Null code requests host approval using a discovery pin; a supplied code uses the manual verified route. */
    public CompletableFuture<Void> connect(Configuration configuration, String pairingCode) {
        requireOwner();
        Objects.requireNonNull(configuration, "configuration");
        if (pairingCode == null) new RemoteProtocol.ControlRequest(configuration.clientName());
        else new RemoteProtocol.Pair(pairingCode, configuration.clientName());
        if (attempt != null) throw new IllegalStateException("Disconnect the previous session before pairing.");
        var current = new Attempt(configuration);
        attempt = current;
        phase = Phase.PREPARING;
        message = "Preparing the remote connection and artwork cache.";
        try {
            current.preparation = CompletableFuture.supplyAsync(() -> {
                try {
                    return new Prepared(InetAddress.getByName(configuration.host()), RemoteCompatibility.VERSION,
                                        configuration.storage().open());
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, workerExecutor);
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            failSetup(current, unavailable);
            return current.ready.copy();
        }
        current.preparation.whenComplete((prepared, failure) -> ownerExecutor.execute(() -> {
            requireOwner();
            if (current.cancelled) {
                finishStopped(current, null);
                return;
            }
            if (failure != null) {
                failSetup(current, failure);
                return;
            }
            try {
                var address = new InetSocketAddress(prepared.address(), configuration.controlPort());
                current.client = pairingCode == null
                        ? RemoteSessionClient.requestControl(address, configuration.verifiedFingerprint(), prepared.build(),
                                configuration.timeoutMillis(), configuration.clientName())
                        : RemoteSessionClient.pair(address, configuration.verifiedFingerprint(), prepared.build(),
                                configuration.timeoutMillis(), pairingCode, configuration.clientName());
                current.client.enableWorkspace();
                phase = Phase.CONNECTING;
                message = pairingCode == null ? "Requesting control; approve this connection on the intended host."
                        : "Connecting to the verified host.";
            } catch (RuntimeException invalid) {
                failSetup(current, invalid);
            }
        }));
        return current.ready.copy();
    }

    /** Owner pulse. No file or network IO; the application continues rendering while pairing/preparing. */
    public void tick() {
        requireOwner();
        var current = attempt;
        if (current == null || current.cancelled || current.client == null) return;
        try {
            switch (current.client.phase()) {
                case CONNECTING -> {
                    phase = Phase.CONNECTING;
                    message = "Connecting to the selected host; host approval is required.";
                }
                case AWAITING_APPROVAL -> {
                    phase = Phase.AWAITING_APPROVAL;
                    message = "Waiting for approval on " + current.configuration.sessionName() + ".";
                }
                case DISCONNECTED, CLOSED -> {
                    if (current.replica != null) current.replica.tick();
                    phase = current.installed ? Phase.PAUSED : Phase.ERROR;
                    message = current.client.lastError() == null ? "Disconnected - editing paused."
                            : current.client.lastError();
                    if (!current.installed) {
                        failSetup(current, new IOException(message));
                    }
                }
                case SETTINGS_READY -> synchronize(current);
            }
        } catch (RuntimeException failure) {
            if (current.connectedOnce) {
                current.client.close();
                current.replica.tick();
                phase = Phase.ERROR;
                message = "Remote synchronization failed. Disconnect and synchronize again.";
                LOG.error(message, failure);
            } else {
                failSetup(current, failure);
            }
        }
    }

    private void synchronize(Attempt current) {
        if (current.replica == null) {
            phase = Phase.SYNCHRONIZING;
            message = current.client.workspaceStatus() == null ? "Waiting for the host workspace baseline."
                    : current.client.workspaceStatus();
            if (!current.client.workspaceReady() || current.client.latestState() == null
                    || current.client.latestWorkspace() == null) return;
            Prepared prepared = current.preparation.getNow(null);
            current.installed = true;
            current.replica = workspace.open(RemoteWorkspaceReplica.connection(current.client,
                            current.configuration.assetPort(), prepared.store()),
                    RemoteSettingsLink.connection(current.client), prepared.store(),
                    current.configuration.storage().cache(), workerExecutor);
        }
        current.replica.tick();
        message = current.replica.status();
        switch (current.replica.phase()) {
            case READY -> {
                current.connectedOnce = true;
                phase = Phase.CONNECTED;
                current.ready.complete(null);
            }
            case LOADING -> phase = Phase.SYNCHRONIZING;
            case PENDING -> phase = current.connectedOnce ? Phase.CONNECTED : Phase.SYNCHRONIZING;
            case PAUSED -> phase = Phase.PAUSED;
            case ERROR, RESYNC_REQUIRED, CLOSED -> {
                if (!current.connectedOnce) failSetup(current, new IOException(message));
                else phase = Phase.ERROR;
            }
        }
    }

    public RemoteFailure workspaceFailure() {
        requireOwner();
        return attempt == null || attempt.client == null ? null : attempt.client.workspaceFailure();
    }

    public Status status() {
        requireOwner();
        return new Status(phase, message, attempt == null ? null : attempt.configuration.sessionName(),
                attempt == null ? null : attempt.configuration.host(),
                attempt != null && !attempt.cancelled && attempt.client != null && attempt.client.canReconnect());
    }

    public boolean active() {
        requireOwner();
        return attempt != null;
    }

    public java.util.List<RemoteSessionClient.TransferProgress> transferProgress() {
        requireOwner();
        return attempt == null || attempt.client == null ? java.util.List.of() : attempt.client.transferProgress();
    }

    public void reconnect() {
        requireOwner();
        if (attempt == null || attempt.cancelled || attempt.client == null || !attempt.client.canReconnect()) {
            throw new IllegalStateException("This session cannot reconnect; disconnect and pair again.");
        }
        attempt.client.reconnect();
        phase = Phase.CONNECTING;
        message = "Reconnecting; unconfirmed commands retain their exact identities.";
    }

    public CompletableFuture<Void> disconnect() {
        requireOwner();
        if (attempt == null) return CompletableFuture.completedFuture(null);
        var current = attempt;
        if (!current.cancelled) {
            phase = Phase.STOPPING;
            message = "Returning to the local workspace.";
            current.ready.completeExceptionally(new CancellationException("Remote connection cancelled."));
            cancel(current);
        }
        return current.stopped.copy();
    }

    @Override
    public void close() {
        disconnect();
    }

    private void failSetup(Attempt current, Throwable failure) {
        phase = Phase.ERROR;
        message = "Could not enter remote control. " + (failure instanceof LocalWorkspaceChangedException
                ? failure.getMessage() : current.client != null && current.client.lastError() != null
                ? current.client.lastError() : "Check verified connection details, compatible remote versions and storage access.");
        LOG.warn("Remote connection setup failed.", failure);
        current.ready.completeExceptionally(new IOException(message, failure));
        cancel(current);
    }

    private void cancel(Attempt current) {
        current.cancelled = true;
        var transportStopped = current.client == null ? CompletableFuture.<Void>completedFuture(null)
                : current.client.stop();
        RuntimeException cleanupFailure = null;
        if (current.installed) {
            current.installed = false;
            try {
                workspace.close();
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
                LOG.error("Could not restore the standalone workspace.", failure);
            }
            current.replica = null;
        }
        if (current.preparation == null || current.preparation.isDone()) {
            RuntimeException finalCleanupFailure = cleanupFailure;
            transportStopped.whenComplete((unused, failure) ->
                    ownerExecutor.execute(() -> finishStopped(current, failure == null ? finalCleanupFailure : failure)));
        }
        // A pending preparation finishes via its own owner callback; no workspace has been installed yet.
    }

    private void finishStopped(Attempt current, Throwable failure) {
        requireOwner();
        if (attempt == current) {
            attempt = null;
            if (phase != Phase.ERROR) {
                phase = failure == null ? Phase.STANDALONE : Phase.ERROR;
                message = failure == null ? "Returned to the local workspace; local output remains stopped."
                        : "The remote connection could not shut down cleanly; see application logs.";
            }
        }
        if (failure == null) current.stopped.complete(null);
        else current.stopped.completeExceptionally(failure);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Remote connection setup must run on its application owner thread.");
        }

    }

    public CompletableFuture<RemoteDirectories.Listing> directories(RemoteDirectories.Query query) {
        requireOwner();
        if (attempt == null || attempt.client == null || phase != Phase.CONNECTED) {
            return CompletableFuture.failedFuture(new IOException("Host folder browsing requires a connected workspace."));
        }
        return attempt.client.directories(query);
    }
}
