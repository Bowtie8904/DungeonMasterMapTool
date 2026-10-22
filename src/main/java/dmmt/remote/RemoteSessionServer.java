package dmmt.remote;

import dmmt.log.Log;
import dmmt.service.HostSettingsAuthority;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-peer control router. There is no filesystem/API bridge and no unauthenticated state endpoint.
 * Workspace operations execute on the owner; settings persistence runs away from the application thread.
 */
public final class RemoteSessionServer implements AutoCloseable {
    private static final Log LOG = Log.of(RemoteSessionServer.class);

    /** Bounded single-line form of peer-supplied text for logging. */
    static String safe(String value) {
        if (value == null) return "";
        String flat = value.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > 100 ? flat.substring(0, 100) + "..." : flat;
    }
    private final RemoteTlsListener listener;
    private final RemoteHostSession session;
    private final RemoteOwnerDispatch owner;
    private final RemoteWorkspaceEndpoint workspace;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<RemoteTlsChannel> active = new AtomicReference<>();
    private final AtomicReference<RemoteTlsChannel> challenger = new AtomicReference<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile CompletableFuture<Void> peerStopped = CompletableFuture.completedFuture(null);
    private volatile String lastError;
    // Accessed only through the owner dispatcher or host UI on that same thread.
    private Pending pending;
    private final RemoteClientTrustStore trust;

    public RemoteClientTrustStore trust() {
        return trust;
    }

    private static final class Pending {
        final RemoteTlsChannel channel;
        final RemoteSessionAccess.ApprovalRequest request;
        RemoteSessionAccess.Grant grant;
        boolean rejected;

        Pending(RemoteTlsChannel channel, RemoteSessionAccess.ApprovalRequest request) {
            this.channel = channel;
            this.request = request;
        }
    }

    public RemoteSessionServer(RemoteTlsListener listener, RemoteHostSession session, RemoteOwnerDispatch owner) {
        this(listener, session, owner, null);
    }

    public RemoteSessionServer(RemoteTlsListener listener, RemoteHostSession session, RemoteOwnerDispatch owner,
                               RemoteWorkspaceEndpoint workspace) {
        this(listener, session, owner, workspace, null);
    }

    public RemoteSessionServer(RemoteTlsListener listener, RemoteHostSession session, RemoteOwnerDispatch owner,
                               RemoteWorkspaceEndpoint workspace, RemoteClientTrustStore trust) {
        this.trust = trust;
        this.listener = Objects.requireNonNull(listener, "listener");
        if (listener.connectionLimit() != 2) {
            throw new IllegalArgumentException("The remote server needs a second bounded transport slot for busy replies.");
        }
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.workspace = workspace;
    }

    /** Explicit lifecycle only: construction alone never starts accepting requests. */
    public synchronized void start() {
        if (stopping.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("This remote listener cannot be started.");
        }
        Thread.ofPlatform().daemon().name("dmmt-remote-host").start(this::serve);
        LOG.info("Remote control listener started on port {}", listener.port());
    }

    public int port() {
        return listener.port();
    }

    public String lastError() {
        return lastError;
    }

    /** Host owner thread only. A stale approval cannot grant access on another pairing connection. */
    public void approve(UUID requestId) {
        session.status(); // Enforce the same owner as the application session.
        Pending target = requirePending(requestId);
        target.grant = session.approve(requestId);
    }

    /** Host owner thread only. Approves now and auto-approves this client's future control requests. */
    public void approveAlways(UUID requestId) throws IOException {
        session.status();
        Pending target = requirePending(requestId);
        if (trust != null) trust.remember(target.request.client(), RemoteClientTrustStore.Decision.ALLOWED);
        target.grant = session.approve(requestId);
    }

    /** Host owner thread only. Rejects now and silently refuses this client's future requests. */
    public void block(UUID requestId) throws IOException {
        session.status();
        Pending target = requirePending(requestId);
        if (trust != null) trust.remember(target.request.client(), RemoteClientTrustStore.Decision.BLOCKED);
        session.reject(requestId);
        target.rejected = true;
    }

    /** Host owner thread only. The peer receives the rejection on its next bounded approval poll. */
    public void reject(UUID requestId) {
        session.status();
        Pending target = requirePending(requestId);
        session.reject(requestId);
        target.rejected = true;
    }

    /** Host owner thread only; revocation precedes closing the channel. */
    public void revoke() {
        session.revoke();
        pending = null;
        closeActive();
    }

    public synchronized CompletableFuture<Void> stop() {
        if (stopping.compareAndSet(false, true)) {
            try {
                listener.close();
            } catch (IOException failure) {
                lastError = "Could not close the remote listener.";
                LOG.warn("{}", lastError, failure);
            }
            closeActive();
            if (!started.get()) {
                finishStopping();
            }
        }
        return stopped.copy();
    }

    @Override
    public void close() {
        stop();
    }

    private void serve() {
        try {
            while (!stopping.get()) {
                try {
                    RemoteTlsChannel channel = listener.accept();
                    if (stopping.get()) {
                        channel.close();
                        break;
                    }
                    if (active.compareAndSet(null, channel)) {
                        var completion = new CompletableFuture<Void>();
                        peerStopped = completion;
                        Thread.ofPlatform().daemon().name("dmmt-remote-peer").start(() -> servePeer(channel, completion, false));
                    } else if (challenger.compareAndSet(null, channel)) {
                        // A new request while a client is (or seems to be) connected: ask the host; on approval it replaces the old one.
                        var completion = new CompletableFuture<Void>();
                        peerStopped = CompletableFuture.allOf(peerStopped, completion);
                        Thread.ofPlatform().daemon().name("dmmt-remote-challenger").start(() -> servePeer(channel, completion, true));
                    } else {
                        try (channel) {
                            var request = channel.receive();
                            fail(channel, request, new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.BUSY,
                                    "The host already has an approved or pending remote client."));
                        }
                    }
                } catch (SocketTimeoutException timeout) {
                    // An idle accept timeout is normal; connected peer timeouts are reported in route().
                } catch (IllegalStateException transportsInUse) {
                    // Both transport slots are busy (connected client plus a request awaiting approval); wait for one.
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (RemoteTlsChannel.CompatibilityException incompatible) {
                    if (!stopping.get()) {
                        lastError = "A remote connection failed. Check the verified host details and remote compatibility versions.";
                        LOG.warn("Rejected remote peer: {}", safe(incompatible.getMessage()));
                    }
                } catch (IOException failure) {
                    if (!stopping.get()) {
                        lastError = "A remote connection failed. Check the verified host details and remote compatibility versions.";
                        // Parser/TLS exceptions may include peer-controlled payloads; never log their messages.
                        LOG.debug("Remote connection failed: {}", failure.getClass().getSimpleName());
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } finally {
            finishStopping();
        }
    }

    private void servePeer(RemoteTlsChannel channel, CompletableFuture<Void> completion, boolean replacement) {
        try (channel) {
            route(channel, replacement);
        } catch (IOException failure) {
            if (!stopping.get()) {
                lastError = "The remote connection ended; reconnect to recover any unconfirmed command.";
                LOG.debug("Remote peer disconnected: {}", failure.getClass().getSimpleName());
            }
        } finally {
            active.compareAndSet(channel, null);
            challenger.compareAndSet(channel, null);
            completion.complete(null);
        }
    }

    private void route(RemoteTlsChannel channel, boolean replacement) throws IOException {
        UUID lease = null;
        RemoteWire.Message connecting = null;
        try {
            RemoteWire.Message first = channel.receive();
            if (first.type() == RemoteWire.Type.PAIR || first.type() == RemoteWire.Type.REQUEST_CONTROL) {
                pair(channel, first, replacement);
                first = channel.receive();
            } else if (replacement) {
                // Only a pairing/approval request may contend with the current client.
                throw new ProtocolFailure(first, new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.BUSY,
                        "The host already has a connected remote client."));
            }
            requireType(first, RemoteWire.Type.CONNECT);
            connecting = first;
            var connect = RemoteWire.payload(first, RemoteProtocol.Connect.class);
            lease = onOwner(() -> {
                UUID connected = session.connect(connect.sessionId(), connect.credential());
                if (pending != null && pending.channel == channel) {
                    pending = null; // The host retains only the credential digest after authentication.
                }
                return connected;
            });
            UUID connectionLease = lease;
            onOwner(() -> {
                if (workspace != null && workspace.player() != null) workspace.player().resetPointers();
                return null;
            });
            reply(channel, first, RemoteWire.Type.STATE, onOwner(() -> session.settingsState(connectionLease)));
            lastError = null;
            LOG.info("Remote client connected from {}", safe(channel.peerAddress()));
            while (!stopping.get()) {
                RemoteWire.Message message = RemoteMessageStream.receive(channel);
                try {
                    switch (message.type()) {
                        case DIRECTORY_QUERY -> {
                            var query = RemoteWire.payload(message, RemoteDirectories.Query.class);
                            onOwner(() -> { session.authorizeConnection(connectionLease); return null; });
                            var listing = RemoteDirectories.list(query);
                            onOwner(() -> { session.authorizeConnection(connectionLease); return null; });
                            reply(channel, message, RemoteWire.Type.DIRECTORY_LIST, listing);
                        }
                        case HANDOUT_QUERY -> {
                            var query = RemoteWire.payload(message, RemoteHandouts.Query.class);
                            var update = owner.callAsync(() -> {
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                if (workspace.handouts() == null) {
                                    throw new IllegalArgumentException("Handout integration is unavailable.");
                                }
                                return workspace.handouts().exchange(query, () -> {
                                    if (stopping.get()) throw new IllegalStateException("Hosting stopped.");
                                    session.authorizeConnection(connectionLease);
                                });
                            });
                            if (query.unchanged(update.state())) {
                                reply(channel, message, RemoteWire.Type.UNCHANGED, new RemoteProtocol.Empty());
                            } else {
                                reply(channel, message, RemoteWire.Type.HANDOUT_STATE, update);
                            }
                        }
                        case PLAYER_QUERY -> {
                            var query = RemoteWire.payload(message, RemotePlayerSession.Query.class);
                            var update = onOwner(() -> {
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                if (workspace.player() == null) {
                                    throw new IllegalArgumentException("Player-output integration is unavailable.");
                                }
                                return workspace.player().exchange(query);
                            });
                            reply(channel, message, RemoteWire.Type.PLAYER_STATE, update);
                        }
                        case SAVE_QUERY -> {
                            var query = RemoteWire.payload(message, RemoteSaves.Query.class);
                            var update = owner.callAsync(() -> {
                                if (stopping.get()) throw new IOException("Remote hosting has stopped.");
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                if (workspace.saves() == null) throw new IllegalArgumentException("Host save is unavailable.");
                                return workspace.saves().exchange(query, () -> session.authorizeConnection(connectionLease));
                            });
                            onOwner(() -> {
                                session.authorizeConnection(connectionLease);
                                return null;
                            });
                            reply(channel, message, RemoteWire.Type.SAVE_STATE, update);
                        }
                        case MAP_CATALOG_QUERY -> {
                            RemoteWire.payload(message, RemoteProtocol.Empty.class);
                            var listing = owner.callAsync(() -> {
                                if (stopping.get()) throw new IOException("Remote hosting has stopped.");
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                if (workspace.maps() == null) throw new IllegalArgumentException("Host map catalog is unavailable.");
                                return workspace.maps().query();
                            });
                            onOwner(() -> {
                                session.authorizeConnection(connectionLease);
                                return null;
                            });
                            reply(channel, message, RemoteWire.Type.MAP_CATALOG, listing);
                        }
                        case OPERATION_QUERY -> {
                            var query = RemoteWire.payload(message, RemoteOperations.Query.class);
                            var update = owner.callAsync(() -> {
                                if (stopping.get()) throw new IOException("Remote hosting has stopped.");
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                if (workspace.operations() == null) throw new IllegalArgumentException("Host operations are unavailable.");
                                return workspace.operations().exchange(query, () -> session.authorizeConnection(connectionLease));
                            });
                            onOwner(() -> {
                                session.authorizeConnection(connectionLease);
                                return null;
                            });
                            reply(channel, message, RemoteWire.Type.OPERATION_REPLY, update);
                        }
                        case STATE -> {
                            var query = RemoteWire.payload(message, RemoteProtocol.StateQuery.class);
                            var state = onOwner(() -> session.settingsState(connectionLease));
                            if (query.matches(state)) {
                                reply(channel, message, RemoteWire.Type.UNCHANGED, new RemoteProtocol.Empty());
                            } else {
                                reply(channel, message, RemoteWire.Type.STATE, state);
                            }
                        }
                        case EDIT -> {
                            var edit = RemoteWire.payload(message, HostSettingsAuthority.Request.class);
                            reply(channel, message, RemoteWire.Type.RESULT,
                                    owner.callAsync(() -> {
                                        if (stopping.get()) {
                                            throw new IOException("Remote hosting has stopped.");
                                        }
                                        return session.editSettingsAsync(connectionLease, edit, owner::execute);
                                    }));
                        }
                        case WORKSPACE_STATE -> {
                            var query = RemoteWire.payload(message, RemoteProtocol.WorkspaceQuery.class);
                            var unavailable = new java.util.concurrent.atomic.AtomicReference<RemoteFailure>();
                            var state = onOwner(() -> {
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                unavailable.set(workspace.failure());
                                return workspace.ready() ? workspace.state() : null;
                            });
                            if (state == null) {
                                reply(channel, message, RemoteWire.Type.WORKSPACE_WAIT,
                                        new RemoteProtocol.WorkspaceWait(unavailable.get() == null
                                                ? "The host is finishing a local operation or preparing map artwork."
                                                : "Host workspace synchronization failed. See error details.", unavailable.get()));
                            } else if (query.matches(state)) {
                                reply(channel, message, RemoteWire.Type.UNCHANGED, new RemoteProtocol.Empty());
                            } else {
                                state.document().validatePortableAssets();
                                reply(channel, message, RemoteWire.Type.WORKSPACE_STATE, state);
                            }
                        }
                        case WORKSPACE_EDIT -> {
                            var edit = RemoteWire.payload(message, RemoteWorkspaceAuthority.Request.class);
                            Map<String, com.fasterxml.jackson.databind.JsonNode> replacements = new java.util.LinkedHashMap<>();
                            for (var change : edit.changes()) {
                                if (change.replacement() != null) {
                                    replacements.put(change.target(), change.replacement());
                                }
                            }
                            new RemoteProjectDocument("validation", replacements).validatePortableAssets();
                            var result = owner.callAsync(() -> {
                                if (stopping.get()) {
                                    throw new IOException("Remote hosting has stopped.");
                                }
                                session.authorizeConnection(connectionLease);
                                requireWorkspace();
                                return workspace.executeAsync(edit, () -> session.authorizeConnection(connectionLease));
                            });
                            result.state().document().validatePortableAssets();
                            reply(channel, message, RemoteWire.Type.WORKSPACE_RESULT, result);
                        }
                        case DISCONNECT -> {
                            RemoteWire.payload(message, RemoteProtocol.Empty.class);
                            return;
                        }
                        default -> throw new IllegalArgumentException("Unexpected authenticated message type.");
                    }
                } catch (RemoteSessionAccess.AccessException denied) {
                    fail(channel, message, accessFailure(denied));
                    return;
                } catch (IllegalArgumentException invalid) {
                    LOG.warn("Malformed remote request {} from {}", message.type(), safe(channel.peerAddress()));
                    fail(channel, message, malformed());
                    return;
                }
            }
        } catch (ProtocolFailure rejected) {
            LOG.warn("Remote request rejected: {}", rejected.failure.code());
            fail(channel, rejected.request, rejected.failure);
        } catch (RemoteSessionAccess.AccessException denied) {
            LOG.warn("Remote access denied: {}", denied.failure());
            if (connecting != null) {
                fail(channel, connecting, accessFailure(denied));
            } else {
                throw denied;
            }
        } catch (SocketTimeoutException timeout) {
            if (!stopping.get()) {
                lastError = "The remote connection timed out; its last command may need receipt recovery.";
            }
            throw timeout;
        } finally {
            UUID disconnectedLease = lease;
            owner.call(() -> {
                cleanup(channel, disconnectedLease);
                return null;
            });
        }
    }

    private void pair(RemoteTlsChannel channel, RemoteWire.Message first, boolean replacement) throws IOException {
        RemoteSessionAccess.ApprovalRequest request;
        try {
            boolean approvalOnly = first.type() == RemoteWire.Type.REQUEST_CONTROL;
            var pair = approvalOnly ? null : RemoteWire.payload(first, RemoteProtocol.Pair.class);
            String name = approvalOnly ? RemoteWire.payload(first, RemoteProtocol.ControlRequest.class).name() : pair.name();
            request = onOwner(() -> {
                var client = new RemoteSessionAccess.Client(name, channel.peerAddress());
                var remembered = trust == null ? null : trust.decision(client);
                if (remembered == RemoteClientTrustStore.Decision.BLOCKED) return null;
                var approval = approvalOnly ? session.requestApproval(client) : session.requestApproval(pair.code(), client);
                pending = new Pending(channel, approval);
                if (approvalOnly && remembered == RemoteClientTrustStore.Decision.ALLOWED) {
                    pending.grant = session.approve(approval.id());
                }
                return approval;
            });
            if (request == null) {
                LOG.warn("Refused pairing request from blocked client {} ({})", safe(name), safe(channel.peerAddress()));
                throw new ProtocolFailure(first, new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.REJECTED,
                        "The host rejected or cancelled this pairing request."));
            }
            LOG.info("Remote {} request from {} ({})", approvalOnly ? "control" : "pairing", safe(name),
                    safe(channel.peerAddress()));
        } catch (RemoteSessionAccess.AccessException denied) {
            LOG.warn("Remote pairing request denied: {}", denied.failure());
            throw new ProtocolFailure(first, accessFailure(denied));
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolFailure(first, malformed());
        }
        var waiting = new RemoteProtocol.Approval(request.id(), request.expiresAt().toEpochMilli());
        reply(channel, first, RemoteWire.Type.APPROVAL, waiting);
        while (!stopping.get()) {
            RemoteWire.Message poll = channel.receive();
            requireType(poll, RemoteWire.Type.APPROVAL);
            var id = RemoteWire.payload(poll, RemoteProtocol.ApprovalPoll.class);
            if (!request.id().equals(id.requestId())) {
                throw new ProtocolFailure(poll, malformed());
            }
            var decision = onOwner(() -> {
                session.status(); // Expires requests before checking a host decision.
                if (pending == null || pending.channel != channel || pending.rejected) {
                    return new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.REJECTED,
                            "The host rejected or cancelled this pairing request.");
                }
                if (pending.grant != null) {
                    return new RemoteProtocol.Connect(pending.grant.sessionId(), pending.grant.credential());
                }
                var current = session.status().pending();
                if (current == null || !request.id().equals(current.id())) {
                    return new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.EXPIRED,
                            "The pairing request expired or was cancelled; start pairing again.");
                }
                return waiting;
            });
            if (decision instanceof RemoteProtocol.Failure failure) {
                throw new ProtocolFailure(poll, failure);
            }
            if (decision instanceof RemoteProtocol.Connect grant) {
                reply(channel, poll, RemoteWire.Type.GRANT, grant);
                if (replacement) takeOver(channel);
                return;
            }
            reply(channel, poll, RemoteWire.Type.APPROVAL, waiting);
        }
        throw new IOException("Remote hosting stopped while waiting for approval.");
    }

    /** The host allowed a replacement: it becomes the active peer and the previous connection is closed. */
    private void takeOver(RemoteTlsChannel channel) {
        RemoteTlsChannel previous = active.getAndSet(channel);
        challenger.compareAndSet(channel, null);
        if (previous != null && previous != channel) {
            try {
                previous.close();
            } catch (IOException failure) {
                LOG.warn("Could not close the replaced remote connection.", failure);
            }
            LOG.info("Remote client replaced by an approved new connection");
        }
    }

    private <T> T onOwner(Callable<T> action) throws IOException {
        return owner.call(() -> {
            if (stopping.get()) {
                throw new IOException("Remote hosting has stopped.");
            }
            return action.call();
        });
    }

    private void requireWorkspace() {
        if (workspace == null) {
            throw new IllegalArgumentException("This host has no remote workspace integration.");
        }
    }

    private void cleanup(RemoteTlsChannel channel, UUID lease) {
        var status = session.status();
        if (status.phase() == RemoteSessionAccess.Phase.STOPPED) {
            pending = null;
            return;
        }
        if (pending != null && pending.channel == channel) {
            if (status.pending() != null && status.pending().id().equals(pending.request.id())) {
                session.cancelApproval(pending.request.id());
            }
            pending = null;
        }
        session.disconnected(lease);
        if (lease != null) LOG.info("Remote client disconnected from {}", safe(channel.peerAddress()));
        if (workspace != null && workspace.player() != null) workspace.player().resetPointers();
    }

    private Pending requirePending(UUID id) {
        if (pending == null || !pending.request.id().equals(id) || pending.grant != null || pending.rejected) {
            throw new IllegalStateException("This pairing connection is no longer awaiting approval.");
        }
        return pending;
    }

    private void finishStopping() {
        stopping.set(true);
        try {
            listener.close();
        } catch (IOException failure) {
            LOG.warn("Could not close the remote listener.", failure);
        }
        peerStopped.thenCompose(unused -> owner.submit(() -> {
            pending = null;
            session.close();
        })).whenComplete((unused, failure) -> {
            if (failure == null) {
                stopped.complete(null);
            } else {
                stopped.completeExceptionally(failure);
                LOG.error("Could not stop remote session ownership.", failure);
            }
        });
    }

    private void closeActive() {
        RemoteTlsChannel waiting = challenger.get();
        if (waiting != null) {
            try {
                waiting.close();
            } catch (IOException failure) {
                LOG.warn("Could not close the pending remote connection.", failure);
            }
        }
        RemoteTlsChannel channel = active.get();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException failure) {
                LOG.warn("Could not close the remote connection.", failure);
            }
        }
    }

    private static void requireType(RemoteWire.Message message, RemoteWire.Type expected) throws ProtocolFailure {
        if (message.type() != expected) {
            throw new ProtocolFailure(message, malformed());
        }
    }

    private static void reply(RemoteTlsChannel channel, RemoteWire.Message request,
                              RemoteWire.Type type, Object payload) throws IOException {
        RemoteMessageStream.send(channel, RemoteWire.reply(request, type, payload));
    }

    private static void fail(RemoteTlsChannel channel, RemoteWire.Message request,
                             RemoteProtocol.Failure failure) throws IOException {
        reply(channel, request, RemoteWire.Type.ERROR, failure);
    }

    private static RemoteProtocol.Failure malformed() {
        return new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.INVALID_MESSAGE,
                "Unsupported or malformed remote request.");
    }

    private static RemoteProtocol.Failure accessFailure(RemoteSessionAccess.AccessException exception) {
        var code = switch (exception.failure()) {
            case STOPPED -> RemoteProtocol.ErrorCode.STOPPED;
            case BUSY -> RemoteProtocol.ErrorCode.BUSY;
            case RATE_LIMITED -> RemoteProtocol.ErrorCode.RATE_LIMITED;
            case INVALID_CODE -> RemoteProtocol.ErrorCode.INVALID_CODE;
            case EXPIRED, UNKNOWN_REQUEST -> RemoteProtocol.ErrorCode.EXPIRED;
            case UNAUTHORIZED -> RemoteProtocol.ErrorCode.UNAUTHORIZED;
        };
        return new RemoteProtocol.Failure(code, exception.getMessage());
    }

    private static final class ProtocolFailure extends IOException {
        final RemoteWire.Message request;
        final RemoteProtocol.Failure failure;

        ProtocolFailure(RemoteWire.Message request, RemoteProtocol.Failure failure) {
            super(failure.message());
            this.request = request;
            this.failure = failure;
        }
    }
}
