package dmmt.remote;

import dmmt.service.AppSettings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit hosting lifecycle. The application installs its workspace only on the owner thread. */
public final class RemoteHostingController implements AutoCloseable {
    public enum Phase { STOPPED, STARTING, WAITING, AWAITING_APPROVAL, CONNECTED, DISCONNECTED, STOPPING, ERROR }

    public record Configuration(String name, InetAddress bindAddress, int controlPort, int assetPort,
                                int timeoutMillis, RemoteSessionStorage storage) {
        public Configuration {
            name = new RemoteSessionAccess.Client(name, "host").name();
            Objects.requireNonNull(bindAddress, "bindAddress");
            Objects.requireNonNull(storage, "storage");
            if (bindAddress.isMulticastAddress() || controlPort < 0 || controlPort > 65535
                    || assetPort < 0 || assetPort > 65535 || controlPort != 0 && controlPort == assetPort
                    || timeoutMillis < 1) {
                throw new IllegalArgumentException("Choose a local interface and distinct, valid listening ports.");
            }
        }
    }

    /** Public identity information only; pairing codes and reconnect credentials are deliberately separate. */
    public record Details(String name, InetAddress bindAddress, int controlPort, int assetPort,
                          String fingerprint, String build) {
    }

    public record Status(Phase phase, String message, Details details, RemoteSessionAccess.Status access) {
    }

    public interface Workspace {
        RemoteWorkspaceEndpoint open(RemoteAssetStore store, java.nio.file.Path cache, Executor worker);
        void close();
    }

    @FunctionalInterface
    interface DiscoveryFactory {
        RemoteDiscovery.Responder open(Details details) throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(RemoteHostingController.class.getName());
    private final Thread owner = Thread.currentThread();
    private final AppSettings preferences;
    private final Workspace workspace;
    private final Executor ownerExecutor;
    private final Executor workerExecutor;
    private final DiscoveryFactory discoveryFactory;
    private Phase phase = Phase.STOPPED;
    private String message = "Hosting is stopped.";
    private Details details;
    private Attempt attempt;

    private static final class Attempt {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CompletableFuture<Details> ready = new CompletableFuture<>();
        final CompletableFuture<Void> stopped = new CompletableFuture<>();
        CompletableFuture<Prepared> preparation;
        Prepared prepared;
        RemoteHostSession session;
        RemoteSessionServer control;
        RemoteAssetServer assets;
        boolean workspaceInstalled;
    }

    private record Prepared(RemoteIdentity identity, String build, RemoteAssetStore store,
                            RemoteTlsListener control, RemoteTlsListener assets,
                            RemoteDiscovery.Responder discovery, String discoveryMessage,
                            RemoteClientTrustStore trust) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (discovery != null) discovery.close();
            IOException failure = null;
            try {
                control.close();
            } catch (IOException closing) {
                failure = closing;
            }
            try {
                assets.close();
            } catch (IOException closing) {
                if (failure == null) failure = closing;
                else failure.addSuppressed(closing);
            }
            if (failure != null) throw failure;
        }
    }

    public RemoteHostingController(AppSettings preferences, Workspace workspace,
                                   Executor ownerExecutor, Executor workerExecutor) {
        this(preferences, workspace, ownerExecutor, workerExecutor, RemoteDiscovery::advertise);
    }

    RemoteHostingController(AppSettings preferences, Workspace workspace,
                            Executor ownerExecutor, Executor workerExecutor, DiscoveryFactory discoveryFactory) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.discoveryFactory = Objects.requireNonNull(discoveryFactory, "discoveryFactory");
    }

    public CompletableFuture<Details> start(Configuration configuration) {
        requireOwner();
        Objects.requireNonNull(configuration, "configuration");
        if (attempt != null || preferences.usesHostSettings()) {
            throw new IllegalStateException("Stop the current session before starting another host.");
        }
        var current = new Attempt();
        attempt = current;
        details = null;
        phase = Phase.STARTING;
        message = "Preparing encrypted control and artwork listeners.";
        try {
            current.preparation = CompletableFuture.supplyAsync(() -> prepare(configuration, current), workerExecutor);
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            startupFailed(current, unavailable);
            return current.ready.copy();
        }
        current.preparation.whenComplete((prepared, failure) -> ownerExecutor.execute(() -> {
            requireOwner();
            if (failure != null) {
                if (current.cancelled.get()) finishStopped(current, null);
                else startupFailed(current, failure);
                return;
            }
            current.prepared = prepared;
            if (current.cancelled.get()) {
                dispose(current, null);
                return;
            }
            try {
                current.session = new RemoteHostSession(preferences);
                current.workspaceInstalled = true;
                var endpoint = workspace.open(prepared.store(), configuration.storage().cache(), workerExecutor);
                var dispatcher = new RemoteOwnerDispatch(ownerExecutor, configuration.timeoutMillis());
                current.control = new RemoteSessionServer(prepared.control(), current.session, dispatcher, endpoint,
                        prepared.trust());
                current.assets = new RemoteAssetServer(prepared.assets(), current.session, dispatcher, prepared.store());
                current.control.start();
                current.assets.start();
                if (prepared.discovery() != null) prepared.discovery().start();
                details = new Details(configuration.name(), configuration.bindAddress(),
                        current.control.port(), current.assets.port(), prepared.identity().fingerprint(), prepared.build());
                phase = Phase.WAITING;
                message = "Hosting - waiting for an approved remote client.";
                current.ready.complete(details);
            } catch (RuntimeException invalid) {
                startupFailed(current, invalid);
            }
        }));
        return current.ready.copy();
    }

    public Status status() {
        requireOwner();
        RemoteSessionAccess.Status access = null;
        if (attempt != null && attempt.session != null && !attempt.cancelled.get()) {
            access = attempt.session.status();
            phase = switch (access.phase()) {
                case WAITING -> Phase.WAITING;
                case AWAITING_APPROVAL -> Phase.AWAITING_APPROVAL;
                case CONNECTED -> Phase.CONNECTED;
                case DISCONNECTED -> Phase.DISCONNECTED;
                case STOPPED -> phase;
            };
            if (access.pending() != null) phase = Phase.AWAITING_APPROVAL; // A replacement request while a client is approved.
            message = switch (phase) {
                case WAITING -> "Hosting - waiting for a remote client.";
                case AWAITING_APPROVAL -> "Hosting - approval required for " + access.pending().client().name() + ".";
                case CONNECTED -> "Hosting - " + access.client().name() + " connected.";
                case DISCONNECTED -> "Hosting - client disconnected; access retained for reconnect.";
                default -> message;
            };
            if (attempt.control.lastError() != null) message = attempt.control.lastError();
            if (attempt.assets.lastError() != null) message = attempt.assets.lastError();
        }
        return new Status(phase, message, details, access);
    }

    public boolean active() {
        requireOwner();
        return attempt != null;
    }

    public String discoveryMessage() {
        requireOwner();
        if (attempt == null || attempt.prepared == null) return "";
        var prepared = attempt.prepared;
        return prepared.discovery() != null && prepared.discovery().error() != null
                ? prepared.discovery().error() : prepared.discoveryMessage();
    }

    public RemoteSessionAccess.PairingCode pairingCode() {
        return running().session.pairingCode();
    }

    public RemoteSessionAccess.PairingCode regenerateCode() {
        return running().session.regenerateCode();
    }

    public void approve(UUID requestId) {
        running().control.approve(requestId);
    }

    public void reject(UUID requestId) {
        running().control.reject(requestId);
    }

    /** Approve and remember this client so its future control requests are granted without asking. */
    public void approveAlways(UUID requestId) {
        try {
            running().control.approveAlways(requestId);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the remembered client list.", failure);
        }
    }

    /** Reject and remember this client so its future control requests are refused. */
    public void block(UUID requestId) {
        try {
            running().control.block(requestId);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the remembered client list.", failure);
        }
    }

    public java.util.List<RemoteClientTrustStore.Entry> rememberedClients() {
        requireOwner();
        if (attempt == null || attempt.session == null || attempt.cancelled.get()) return java.util.List.of();
        var trust = attempt.control.trust();
        return trust == null ? java.util.List.of() : trust.entries();
    }

    public void forgetClient(RemoteClientTrustStore.Entry entry) {
        var trust = running().control.trust();
        try {
            if (trust != null) trust.forget(entry);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the remembered client list.", failure);
        }
    }

    public void revoke() {
        running().control.revoke();
    }

    public CompletableFuture<Void> stop() {
        requireOwner();
        if (attempt == null) return CompletableFuture.completedFuture(null);
        var current = attempt;
        if (current.cancelled.compareAndSet(false, true)) {
            phase = Phase.STOPPING;
            message = "Stopping hosting and revoking remote access.";
            current.ready.completeExceptionally(new CancellationException("Hosting was stopped."));
            if (current.prepared != null) dispose(current, null);
            // An unfinished preparation owns its listeners until its owner-thread completion disposes them.
        }
        return current.stopped.copy();
    }

    @Override
    public void close() {
        stop();
    }

    private Prepared prepare(Configuration configuration, Attempt current) {
        RemoteTlsListener control = null;
        RemoteTlsListener assets = null;
        RemoteDiscovery.Responder discovery = null;
        try {
            checkCancelled(current);
            var identity = RemoteIdentity.create();
            String build = RemoteCompatibility.VERSION;
            var store = configuration.storage().open();
            var trust = new RemoteClientTrustStore(configuration.storage().directory());
            checkCancelled(current);
            control = new RemoteTlsListener(new InetSocketAddress(configuration.bindAddress(), configuration.controlPort()),
                    identity.context(), build, configuration.timeoutMillis(), 2);
            assets = new RemoteTlsListener(new InetSocketAddress(configuration.bindAddress(), configuration.assetPort()),
                    identity.context(), build, configuration.timeoutMillis(), 2);
            String discoveryMessage;
            try {
                discovery = discoveryFactory.open(new Details(configuration.name(), configuration.bindAddress(),
                        control.port(), assets.port(), identity.fingerprint(), build));
                discoveryMessage = "LAN discovery is available while hosting (UDP " + RemoteDiscovery.PORT + ").";
            } catch (IOException unavailable) {
                discoveryMessage = "LAN discovery unavailable: " + unavailable.getMessage();
                LOG.log(System.Logger.Level.INFO, discoveryMessage);
            }
            checkCancelled(current);
            return new Prepared(identity, build, store, control, assets, discovery, discoveryMessage, trust);
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException failure) {
            if (discovery != null) discovery.close();
            closeListener(control, failure);
            closeListener(assets, failure);
            throw new CompletionException(failure);
        }
    }

    private void startupFailed(Attempt current, Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, "Remote host startup failed.", failure);
        current.cancelled.set(true);
        current.ready.completeExceptionally(new IOException(
                "Could not start hosting. Check the selected local interface, both ports and storage access.", failure));
        phase = Phase.ERROR;
        message = "Hosting could not start. Check the local interface, both ports and storage access.";
        if (current.prepared == null) finishStopped(current, failure);
        else dispose(current, failure);
    }

    private void dispose(Attempt current, Throwable startupFailure) {
        if (current.session != null) current.session.close();
        if (current.prepared.discovery() != null) current.prepared.discovery().close();
        CompletableFuture<Void> discoveryStopped = current.prepared.discovery() == null
                ? CompletableFuture.completedFuture(null) : current.prepared.discovery().stopped();
        CompletableFuture<Void> controlStopped = current.control == null
                ? CompletableFuture.completedFuture(null) : current.control.stop();
        CompletableFuture<Void> assetsStopped = current.assets == null
                ? CompletableFuture.completedFuture(null) : current.assets.stop();
        Throwable cleanupFailure = startupFailure;
        if (current.workspaceInstalled) {
            current.workspaceInstalled = false;
            try {
                workspace.close();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Could not dispose the host workspace bridge.", failure);
                cleanupFailure = failure;
            }
        }
        if (current.control == null || current.assets == null) {
            try {
                current.prepared.close();
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "Could not close prepared remote listeners.", failure);
                assetsStopped = CompletableFuture.failedFuture(failure);
            }
        }
        Throwable finalCleanupFailure = cleanupFailure;
        CompletableFuture.allOf(controlStopped, assetsStopped, discoveryStopped).whenComplete((unused, failure) ->
                ownerExecutor.execute(() -> finishStopped(current, failure == null ? finalCleanupFailure : failure)));
    }

    private void finishStopped(Attempt current, Throwable failure) {
        requireOwner();
        if (attempt == current) {
            attempt = null;
            details = null;
            if (phase != Phase.ERROR) {
                phase = failure == null ? Phase.STOPPED : Phase.ERROR;
                message = failure == null ? "Hosting stopped; the local workspace and output remain open."
                        : "Remote hosting stopped with a cleanup error; see application logs.";
            }
        }
        if (failure == null) current.stopped.complete(null);
        else current.stopped.completeExceptionally(failure);
    }

    private Attempt running() {
        requireOwner();
        if (attempt == null || attempt.session == null || attempt.cancelled.get()) {
            throw new IllegalStateException("No running remote host is available.");
        }
        return attempt;
    }

    private static void checkCancelled(Attempt current) {
        if (current.cancelled.get()) throw new CancellationException("Host startup was cancelled.");
    }

    private static void closeListener(RemoteTlsListener listener, Throwable original) {
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException failure) {
                original.addSuppressed(failure);
            }
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Hosting must be controlled by its application owner thread.");
        }
    }
}
