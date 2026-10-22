package dmmt.remote;

import dmmt.service.HostSettingsAuthority;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background paired settings transport. SETTINGS_READY is not a usable remote workspace:
 * the application must synchronize assets/state before entering remote UI mode.
 * The UI polls coalesced state and marshals completed command results to its own thread.
 */
public final class RemoteSessionClient implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 32;
    private static final int POLL_MILLIS = 50;
    private final java.util.concurrent.Semaphore workAvailable = new java.util.concurrent.Semaphore(0);
    public enum Phase { CONNECTING, AWAITING_APPROVAL, SETTINGS_READY, DISCONNECTED, CLOSED }
    public record TransferProgress(RemoteAssetTransfer.Direction direction, long transferredBytes, long totalBytes,
                                   String sha256) {
    }

    private record Edit(HostSettingsAuthority.Request request,
                        CompletableFuture<HostSettingsAuthority.Result> result) {
    }

    private record WorkspaceEdit(RemoteWorkspaceAuthority.Request request,
                                 CompletableFuture<RemoteWorkspaceAuthority.Result> result) {
    }

    private record PlayerEdit(RemotePlayerSession.Request request,
                              CompletableFuture<RemotePlayerSession.Update> result) { }
    private record HandoutEdit(RemoteHandouts.Request request,
                               CompletableFuture<RemoteHandouts.Update> result) { }
    private record DirectoryQuery(RemoteDirectories.Query query, CompletableFuture<RemoteDirectories.Listing> result) { }
    private record SaveQuery(RemoteSaves.Query query, CompletableFuture<RemoteSaves.Update> result) { }
    private record OperationQuery(RemoteOperations.Query query, CompletableFuture<RemoteOperations.Reply> result) { }
    private final ArrayBlockingQueue<OperationQuery> operationQueries = new ArrayBlockingQueue<>(8);

    public synchronized CompletableFuture<RemoteOperations.Reply> operation(RemoteOperations.Query query) {
        var result = new CompletableFuture<RemoteOperations.Reply>();
        if (phase != Phase.SETTINGS_READY) {
            result.completeExceptionally(new IOException("Host operations require an approved live connection."));
        } else if (!operationQueries.offer(new OperationQuery(Objects.requireNonNull(query), result))) {
            result.completeExceptionally(new IOException("Too many pending host operation requests."));
        } else {
            workAvailable.release();
        }
        return result;
    }
    private final ArrayBlockingQueue<SaveQuery> saveQueries = new ArrayBlockingQueue<>(4);
    private final ArrayBlockingQueue<CompletableFuture<RemoteMapCatalog.Listing>> mapCatalogQueries = new ArrayBlockingQueue<>(4);

    public synchronized CompletableFuture<RemoteMapCatalog.Listing> mapCatalog() {
        var result = new CompletableFuture<RemoteMapCatalog.Listing>();
        if (phase != Phase.SETTINGS_READY) {
            result.completeExceptionally(new IOException("Host map browsing needs an approved, live connection."));
        } else if (!mapCatalogQueries.offer(result)) {
            result.completeExceptionally(new IOException("Too many host map catalog requests."));
        } else {
            workAvailable.release();
        }
        return result;
    }

    public synchronized CompletableFuture<RemoteSaves.Update> saves(RemoteSaves.Query query) {
        var result = new CompletableFuture<RemoteSaves.Update>();
        if (phase != Phase.SETTINGS_READY) {
            result.completeExceptionally(new IOException("Saving needs an approved, live host connection."));
        } else if (!saveQueries.offer(new SaveQuery(Objects.requireNonNull(query), result))) {
            result.completeExceptionally(new IOException("Too many host save requests."));
        } else {
            workAvailable.release();
        }
        return result;
    }
    private final ArrayBlockingQueue<DirectoryQuery> directoryQueries = new ArrayBlockingQueue<>(4);

    public synchronized CompletableFuture<RemoteDirectories.Listing> directories(RemoteDirectories.Query query) {
        var result = new CompletableFuture<RemoteDirectories.Listing>();
        if (phase != Phase.SETTINGS_READY) {
            result.completeExceptionally(new IOException("Host folder browsing needs an approved, live connection."));
        } else if (!directoryQueries.offer(new DirectoryQuery(Objects.requireNonNull(query), result))) {
            result.completeExceptionally(new IOException("Too many host folder requests; wait for the current listing."));
        } else {
            workAvailable.release();
        }
        return result;
    }
    private record TimedPing(java.util.UUID context, RemotePlayerSession.Point point, long expiresNanos) { }
    private record TimedPointer(RemotePlayerSession.Pointer pointer, long receivedNanos) { }

    private final InetSocketAddress address;
    private final String verifiedFingerprint;
    private final String build;
    private final int timeoutMillis;
    private final ArrayBlockingQueue<Edit> edits = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayBlockingQueue<WorkspaceEdit> workspaceEdits = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayBlockingQueue<PlayerEdit> playerEdits = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayBlockingQueue<TimedPointer> playerPointers = new ArrayBlockingQueue<>(32);
    private final java.util.ArrayDeque<TimedPing> playerPings = new java.util.ArrayDeque<>();
    private volatile boolean playerEnabled;
    private volatile RemotePlayerSession.State latestPlayer;
    private final ArrayBlockingQueue<HandoutEdit> handoutEdits = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean handoutsEnabled;
    private volatile RemoteHandouts.State latestHandouts;

    public void enableHandouts() {
        handoutsEnabled = true;
        workAvailable.release();
    }

    public RemoteHandouts.State latestHandouts() { return latestHandouts; }

    public synchronized CompletableFuture<RemoteHandouts.Update> controlHandouts(RemoteHandouts.Request request) {
        var result = new CompletableFuture<RemoteHandouts.Update>();
        if (phase != Phase.SETTINGS_READY || !handoutsEnabled || latestHandouts == null) {
            result.completeExceptionally(new IOException("The host handouts are not synchronized."));
        } else if (!handoutEdits.offer(new HandoutEdit(Objects.requireNonNull(request), result))) {
            result.completeExceptionally(new IOException("The handout command queue is full."));
        } else {
            workAvailable.release();
        }
        return result;
    }
    private java.util.UUID pointerContext;
    private RemotePlayerSession.Point playerLaser;
    private final java.util.Set<RemoteAssetClient> transfers = new java.util.HashSet<>();
    private record AssetJob(int port, RemoteAssetStore store, RemoteAssetTransfer.Direction direction,
                            RemoteAssetStore.AssetDescriptor asset, CompletableFuture<RemoteAssetStore.AssetDescriptor> result) { }
    private final java.util.ArrayDeque<AssetJob> assetJobs = new java.util.ArrayDeque<>();

    public synchronized CompletableFuture<RemoteAssetStore.AssetDescriptor> transferQueued(
            int port, RemoteAssetStore store, RemoteAssetTransfer.Direction direction,
            RemoteAssetStore.AssetDescriptor asset) {
        var result = new CompletableFuture<RemoteAssetStore.AssetDescriptor>();
        if (phase != Phase.SETTINGS_READY || credential == null) {
            result.completeExceptionally(new IOException("Asset transfer requires a live approved connection."));
        } else if (port < 1 || port > 65535 || assetJobs.size() >= 32768) {
            result.completeExceptionally(new IOException("Asset transfer queue is full or its port is invalid."));
        } else {
            assetJobs.addLast(new AssetJob(port, Objects.requireNonNull(store), Objects.requireNonNull(direction),
                    Objects.requireNonNull(asset), result));
            pumpAssets();
        }
        return result;
    }

    private synchronized void pumpAssets() {
        if (phase != Phase.SETTINGS_READY || credential == null) return;
        while (transfers.size() < 2 && !assetJobs.isEmpty()) {
            var job = assetJobs.removeFirst();
            if (job.result().isCancelled()) continue;
            try {
                var transfer = transferAsset(job.port(), job.store(), job.direction(), job.asset());
                transfer.stopped().whenComplete((ignored, stoppedFailure) ->
                        transfer.result().whenComplete((accepted, failure) -> {
                            if (failure != null) job.result().completeExceptionally(failure);
                            else if (stoppedFailure != null) job.result().completeExceptionally(stoppedFailure);
                            else job.result().complete(accepted);
                        }));
            } catch (RuntimeException failure) {
                job.result().completeExceptionally(failure);
            }
        }
    }
    private volatile Phase phase = Phase.CONNECTING;
    private volatile HostSettingsAuthority.State latestState;
    private volatile RemoteWorkspaceAuthority.State latestWorkspace;
    private volatile boolean workspaceEnabled;
    private volatile boolean workspaceReady;
    private volatile String workspaceStatus;
    private volatile RemoteFailure workspaceFailure;

    public RemoteFailure workspaceFailure() { return workspaceFailure; }
    private volatile RemoteProtocol.Approval approval;
    private volatile String lastError;
    private RemoteProtocol.Connect credential;
    private RemoteTlsChannel active;
    private Thread worker;
    private CompletableFuture<Void> workerStopped = CompletableFuture.completedFuture(null);

    private RemoteSessionClient(InetSocketAddress address, String verifiedFingerprint,
                                String build, int timeoutMillis) {
        this.address = Objects.requireNonNull(address, "address");
        if (verifiedFingerprint == null || !verifiedFingerprint.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("A verified SHA-256 host fingerprint is required.");
        }
        RemoteTlsChannel.validate(build, timeoutMillis);
        this.verifiedFingerprint = verifiedFingerprint;
        this.build = build;
        this.timeoutMillis = timeoutMillis;
    }

    /** Pairing leaves the local application/settings untouched until its workspace transition is ready. */
    public static RemoteSessionClient pair(InetSocketAddress address, String verifiedFingerprint, String build,
                                           int timeoutMillis, String code, String clientName) {
        var pair = new RemoteProtocol.Pair(code, clientName);
        var client = new RemoteSessionClient(address, verifiedFingerprint, build, timeoutMillis);
        client.launch(RemoteWire.message(RemoteWire.Type.PAIR, pair));
        return client;
    }

    /** Discovery supplies an unverified exact certificate pin; A must explicitly approve the request. */
    public static RemoteSessionClient requestControl(InetSocketAddress address, String fingerprint, String build,
                                                      int timeoutMillis, String clientName) {
        var request = new RemoteProtocol.ControlRequest(clientName);
        var client = new RemoteSessionClient(address, fingerprint, build, timeoutMillis);
        client.launch(RemoteWire.message(RemoteWire.Type.REQUEST_CONTROL, request));
        return client;
    }

    public Phase phase() {
        return phase;
    }

    public HostSettingsAuthority.State latestState() {
        return latestState;
    }

    public RemoteProtocol.Approval approval() {
        return approval;
    }

    public String lastError() {
        return lastError;
    }

    /** Enable after choosing the workspace integration; settings-only clients do not request map data. */
    public synchronized void enableWorkspace() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("The remote client is closed.");
        }
        workspaceEnabled = true;
        workAvailable.release();
    }

    public RemoteWorkspaceAuthority.State latestWorkspace() {
        return latestWorkspace;
    }

    public boolean workspaceReady() {
        return phase == Phase.SETTINGS_READY && workspaceReady;
    }

    public String workspaceStatus() {
        return workspaceStatus;
    }

    public void enablePlayer() {
        playerEnabled = true;
        workAvailable.release();
    }

    public RemotePlayerSession.State latestPlayer() {
        return latestPlayer;
    }

    public java.util.List<RemotePlayerSession.Pointer> drainPlayerPointers() {
        var frames = new java.util.ArrayList<TimedPointer>();
        playerPointers.drainTo(frames);
        long now = System.nanoTime();
        return frames.stream().map(frame -> {
            long age = TimeUnit.NANOSECONDS.toMillis(now - frame.receivedNanos());
            var pings = frame.pointer().pings().stream().filter(p -> p.remainingMillis() > age)
                    .map(p -> new RemotePlayerSession.Ping(p.point(), p.remainingMillis() - age)).toList();
            return new RemotePlayerSession.Pointer(frame.pointer().contextId(),
                    age < 500 ? frame.pointer().laser() : null, pings);
        }).toList();
    }

    public synchronized void playerLaser(java.util.UUID context, RemotePlayerSession.Point laser) {
        if (context == null && laser != null) throw new IllegalArgumentException("A laser needs its map context.");
        pointerContext = context;
        playerLaser = phase == Phase.SETTINGS_READY ? laser : null;
    }

    public synchronized void playerPing(java.util.UUID context, RemotePlayerSession.Point point, long durationMillis) {
        Objects.requireNonNull(point, "point");
        if (phase != Phase.SETTINGS_READY || latestPlayer == null || !latestPlayer.contextId().equals(context)) {
            throw new IllegalStateException("The host player view is not synchronized.");
        }
        if (playerPings.size() == RemotePlayerSession.MAX_PINGS) {
            throw new IllegalStateException("Too many pending player pings; wait for the host.");
        }
        if (!context.equals(pointerContext)) {
            pointerContext = context;
            playerLaser = null;
        }
        playerPings.addLast(new TimedPing(context, point, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.clamp(durationMillis, 1, RemotePlayerSession.MAX_PING_MILLIS))));
        workAvailable.release();
    }

    public synchronized CompletableFuture<RemotePlayerSession.Update> controlPlayer(RemotePlayerSession.Request request) {
        var result = new CompletableFuture<RemotePlayerSession.Update>();
        if (phase != Phase.SETTINGS_READY || !playerEnabled || latestPlayer == null) {
            result.completeExceptionally(new IOException("The host player output is not synchronized."));
        } else if (!playerEdits.offer(new PlayerEdit(Objects.requireNonNull(request), result))) {
            result.completeExceptionally(new IOException("The player-output command queue is full."));
        } else {
            workAvailable.release();
        }
        return result;
    }

    public synchronized CompletableFuture<RemoteWorkspaceAuthority.Result> editWorkspace(
            RemoteWorkspaceAuthority.Request request) {
        Objects.requireNonNull(request, "request");
        var result = new CompletableFuture<RemoteWorkspaceAuthority.Result>();
        if (!workspaceReady() || !workspaceEnabled || latestWorkspace == null) {
            result.completeExceptionally(new IOException("The remote workspace is not synchronized."));
        } else if (!workspaceEdits.offer(new WorkspaceEdit(request, result))) {
            result.completeExceptionally(new IOException("The remote workspace command queue is full."));
        } else {
            workAvailable.release();
        }
        return result;
    }

    /** Queue only; this call never waits for the network. Requests/IDs are never rewritten or retried blindly. */
    public synchronized CompletableFuture<HostSettingsAuthority.Result> edit(HostSettingsAuthority.Request request) {
        Objects.requireNonNull(request, "request");
        var result = new CompletableFuture<HostSettingsAuthority.Result>();
        if (phase != Phase.SETTINGS_READY) {
            result.completeExceptionally(new IOException("Remote settings are not connected."));
        } else if (!edits.offer(new Edit(request, result))) {
            result.completeExceptionally(new IOException("The remote command queue is full; wait for the host."));
        } else {
            workAvailable.release();
        }
        return result;
    }

    /** Bulk traffic uses the approved session credential and exact control-channel certificate identity. */
    public synchronized RemoteAssetClient transferAsset(int transferPort, RemoteAssetStore local,
                                                        RemoteAssetTransfer.Direction direction,
                                                        RemoteAssetStore.AssetDescriptor asset) {
        if (phase != Phase.SETTINGS_READY || credential == null) {
            throw new IllegalStateException("Asset transfers require a live approved control connection.");
        }
        if (transferPort < 1 || transferPort > 65535 || transfers.size() >= 2) {
            throw new IllegalArgumentException("Invalid transfer port or too many concurrent transfers.");
        }
        var transfer = RemoteAssetClient.start(new InetSocketAddress(address.getHostString(), transferPort),
                verifiedFingerprint, build, timeoutMillis, credential, local, direction, asset);
        transfers.add(transfer);
        transfer.stopped().whenComplete((result, failure) -> {
            synchronized (RemoteSessionClient.this) {
                transfers.remove(transfer);
                pumpAssets();
            }
        });
        return transfer;
    }

    public synchronized java.util.List<TransferProgress> transferProgress() {
        return transfers.stream().map(transfer -> new TransferProgress(transfer.direction(),
                transfer.transferredBytes(), transfer.totalBytes(), transfer.sha256())).toList();
    }

    /** Explicit retry; the application resumes its coordinator and resends any uncertain request unchanged. */
    public synchronized void reconnect() {
        if (!canReconnect()) {
            throw new IllegalStateException("No disconnected, approved session is available for reconnect.");
        }
        phase = Phase.CONNECTING;
        lastError = null;
        launch(null);
    }

    public synchronized boolean canReconnect() {
        return phase == Phase.DISCONNECTED && credential != null && workerStopped.isDone();
    }

    public synchronized CompletableFuture<Void> stop() {
        var completions = new java.util.ArrayList<CompletableFuture<Void>>();
        completions.add(workerStopped);
        for (var transfer : transfers) {
            completions.add(transfer.stopped());
        }
        close();
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    @Override
    public synchronized void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        credential = null;
        approval = null;
        clearPlayerPointers();
        cancelTransfers();
        closeChannel();
        if (worker != null) {
            worker.interrupt();
        }
        failQueued(new IOException("Remote control was disconnected before these commands were sent."));
    }

    private synchronized void launch(RemoteWire.Message pair) {
        var completion = new CompletableFuture<Void>();
        workerStopped = completion;
        worker = Thread.ofPlatform().daemon().name("dmmt-remote-client").unstarted(() -> {
            try {
                run(pair);
            } finally {
                completion.complete(null);
            }
        });
        worker.start();
    }

    private void run(RemoteWire.Message pair) {
        Edit inFlight = null;
        HandoutEdit handoutInFlight = null;
        WorkspaceEdit workspaceInFlight = null;
        PlayerEdit playerInFlight = null;
        SaveQuery saveInFlight = null;
        try (RemoteTlsChannel channel = RemoteTlsChannel.connect(address,
                RemoteTls.clientContext(verifiedFingerprint), build, timeoutMillis)) {
            synchronized (this) {
                if (phase == Phase.CLOSED) {
                    return;
                }
                active = channel;
            }
            if (pair != null) {
                pair(channel, pair);
            }
            RemoteProtocol.Connect connect;
            synchronized (this) {
                if (phase == Phase.CLOSED) {
                    return;
                }
                connect = Objects.requireNonNull(credential, "Approved session credential");
            }
            var baseline = exchange(channel, RemoteWire.Type.CONNECT, connect);
            requireType(baseline, RemoteWire.Type.STATE);
            publishState(RemoteWire.payload(baseline, HostSettingsAuthority.State.class));
            synchronized (this) {
                if (phase == Phase.CLOSED) {
                    return;
                }
                approval = null;
                phase = Phase.SETTINGS_READY;
            }
            while (phase == Phase.SETTINGS_READY) {
                workAvailable.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS);
                workAvailable.drainPermits();
                var operation = operationQueries.poll();
                if (operation != null && !operation.result().isCancelled()) {
                    try {
                        var response = exchange(channel, RemoteWire.Type.OPERATION_QUERY, operation.query());
                        requireType(response, RemoteWire.Type.OPERATION_REPLY);
                        operation.result().complete(RemoteWire.payload(response, RemoteOperations.Reply.class));
                    } catch (IOException | RuntimeException failure) {
                        operation.result().completeExceptionally(new IOException("Host operation reply was interrupted.", failure));
                        throw failure;
                    }
                }
                var catalog = mapCatalogQueries.poll();
                if (catalog != null && !catalog.isCancelled()) {
                    try {
                        var response = exchange(channel, RemoteWire.Type.MAP_CATALOG_QUERY, new RemoteProtocol.Empty());
                        requireType(response, RemoteWire.Type.MAP_CATALOG);
                        catalog.complete(RemoteWire.payload(response, RemoteMapCatalog.Listing.class));
                    } catch (IOException | RuntimeException failure) {
                        catalog.completeExceptionally(new IOException("Host map catalog request was interrupted."));
                        throw failure;
                    }
                }
                var directory = directoryQueries.poll();
                if (directory != null && !directory.result().isCancelled()) {
                    try {
                        var response = exchange(channel, RemoteWire.Type.DIRECTORY_QUERY, directory.query());
                        requireType(response, RemoteWire.Type.DIRECTORY_LIST);
                        directory.result().complete(RemoteWire.payload(response, RemoteDirectories.Listing.class));
                    } catch (IOException | RuntimeException failure) {
                        directory.result().completeExceptionally(new IOException("Host folder listing was interrupted."));
                        throw failure;
                    }
                }
                inFlight = edits.poll();
                if (inFlight == null) {
                    var state = exchange(channel, RemoteWire.Type.STATE, new RemoteProtocol.StateQuery(latestState));
                    if (state.type() == RemoteWire.Type.UNCHANGED) {
                        RemoteWire.payload(state, RemoteProtocol.Empty.class);
                    } else {
                        requireType(state, RemoteWire.Type.STATE);
                        publishState(RemoteWire.payload(state, HostSettingsAuthority.State.class));
                    }
                } else {
                    var response = exchange(channel, RemoteWire.Type.EDIT, inFlight.request());
                    requireType(response, RemoteWire.Type.RESULT);
                    var result = RemoteWire.payload(response, HostSettingsAuthority.Result.class);
                    validateResult(inFlight.request(), result);
                    publishState(result.state());
                    inFlight.result().complete(result);
                    inFlight = null;
                }
                if (workspaceEnabled) {
                    workspaceInFlight = workspaceEdits.poll();
                    if (workspaceInFlight == null) {
                        var query = latestWorkspace == null ? new RemoteProtocol.WorkspaceQuery(null, null, 0, 0)
                                : new RemoteProtocol.WorkspaceQuery(latestWorkspace);
                        var response = exchange(channel, RemoteWire.Type.WORKSPACE_STATE, query);
                        if (response.type() == RemoteWire.Type.WORKSPACE_WAIT) {
                            workspaceReady = false;
                            var waiting = RemoteWire.payload(response, RemoteProtocol.WorkspaceWait.class);
                            workspaceStatus = waiting.message();
                            workspaceFailure = waiting.failure();
                        } else if (response.type() == RemoteWire.Type.UNCHANGED) {
                            RemoteWire.payload(response, RemoteProtocol.Empty.class);
                            workspaceReady = true;
                            workspaceStatus = null;
                            workspaceFailure = null;
                        } else {
                            requireType(response, RemoteWire.Type.WORKSPACE_STATE);
                            publishWorkspace(RemoteWire.payload(response, RemoteWorkspaceAuthority.State.class));
                        }
                    } else {
                        var response = exchange(channel, RemoteWire.Type.WORKSPACE_EDIT, workspaceInFlight.request());
                        requireType(response, RemoteWire.Type.WORKSPACE_RESULT);
                        var result = RemoteWire.payload(response, RemoteWorkspaceAuthority.Result.class);
                        if (result.sequence() != workspaceInFlight.request().sequence()
                                || !result.commandId().equals(workspaceInFlight.request().commandId())
                                || result.status() == null || result.message() == null) {
                            throw new IllegalArgumentException("Workspace acknowledgement does not match its command.");
                        }
                        publishWorkspace(result.state());
                        workspaceInFlight.result().complete(result);
                        workspaceInFlight = null;
                    }
                }
                saveInFlight = saveQueries.poll();
                if (saveInFlight != null) {
                    var response = exchange(channel, RemoteWire.Type.SAVE_QUERY, saveInFlight.query());
                    requireType(response, RemoteWire.Type.SAVE_STATE);
                    var update = RemoteWire.payload(response, RemoteSaves.Update.class);
                    var request = saveInFlight.query().request();
                    if (request == null ? update.receipt() != null : update.receipt() == null
                            || !update.receipt().commandId().equals(request.commandId())
                            || update.receipt().sequence() != request.sequence()) {
                        throw new IllegalArgumentException("Save acknowledgement does not match its command.");
                    }
                    saveInFlight.result().complete(update);
                    saveInFlight = null;
                }
                if (handoutsEnabled) {
                    handoutInFlight = handoutEdits.poll();
                    var response = exchange(channel, RemoteWire.Type.HANDOUT_QUERY,
                            new RemoteHandouts.Query(handoutInFlight == null ? null : handoutInFlight.request(),
                                    latestHandouts == null ? null : new RemoteHandouts.Stamp(latestHandouts)));
                    if (response.type() == RemoteWire.Type.UNCHANGED && handoutInFlight == null) {
                        RemoteWire.payload(response, RemoteProtocol.Empty.class);
                    } else {
                        requireType(response, RemoteWire.Type.HANDOUT_STATE);
                        var update = RemoteWire.payload(response, RemoteHandouts.Update.class);
                        if (handoutInFlight == null ? update.receipt() != null
                                : update.receipt() == null
                                || !update.receipt().commandId().equals(handoutInFlight.request().commandId())
                                || update.receipt().sequence() != handoutInFlight.request().sequence()) {
                            throw new IllegalArgumentException("Handout acknowledgement does not match its command.");
                        }
                        synchronized (this) {
                            if (phase == Phase.CLOSED) return;
                            var previous = latestHandouts;
                            var next = update.state();
                            if (previous != null && (!previous.sessionId().equals(next.sessionId())
                                    || next.revision() < previous.revision() || next.nextSequence() < previous.nextSequence()
                                    || previous.ready() && next.ready() && previous.revision() == next.revision()
                                    && (!previous.board().equals(next.board()) || !previous.library().equals(next.library())))) {
                                throw new IllegalArgumentException("Inconsistent handout state.");
                            }
                            latestHandouts = next;
                        }
                        if (handoutInFlight != null) {
                            handoutInFlight.result().complete(update);
                            handoutInFlight = null;
                        }
                    }
                }
                if (playerEnabled) {
                    playerInFlight = playerEdits.poll();
                    var response = exchange(channel, RemoteWire.Type.PLAYER_QUERY, new RemotePlayerSession.Query(
                            playerInFlight == null ? null : playerInFlight.request(), takePlayerPointer()));
                    requireType(response, RemoteWire.Type.PLAYER_STATE);
                    var update = RemoteWire.payload(response, RemotePlayerSession.Update.class);
                    if (playerInFlight == null ? update.receipt() != null
                            : update.receipt() == null
                            || !update.receipt().commandId().equals(playerInFlight.request().commandId())
                            || update.receipt().sequence() != playerInFlight.request().sequence()) {
                        throw new IllegalArgumentException("Output acknowledgement does not match its command.");
                    }
                    synchronized (this) {
                        if (phase == Phase.CLOSED) return;
                        var previous = latestPlayer;
                        if (previous != null && (!previous.sessionId().equals(update.state().sessionId())
                                || update.state().revision() < previous.revision()
                                || update.state().nextSequence() < previous.nextSequence()
                                || update.state().revision() == previous.revision()
                                && (!update.state().output().equals(previous.output())
                                || !update.state().contextId().equals(previous.contextId())))) {
                            throw new IllegalArgumentException("Inconsistent player output state.");
                        }
                        latestPlayer = update.state();
                        var pointer = new TimedPointer(update.pointer(), System.nanoTime());
                        if (!playerPointers.offer(pointer)) {
                            playerPointers.poll(); // Transients must not block durable acknowledgements.
                            playerPointers.offer(pointer);
                        }
                    }
                    if (playerInFlight != null) {
                        playerInFlight.result().complete(update);
                        playerInFlight = null;
                    }
                }
            }
        } catch (IOException | GeneralSecurityException failure) {
            // Parser exceptions can embed secret payload text. Expose only safe, actionable descriptions.
            String message = failure instanceof PeerFailure peer ? peer.getMessage()
                    : failure instanceof RemoteTlsChannel.CompatibilityException ? failure.getMessage()
                    : "Remote connection lost or rejected. Verify the host fingerprint, address and remote compatibility versions.";
            disconnected(inFlight, message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            disconnected(inFlight, "Remote connection interrupted; any unconfirmed command needs receipt recovery.");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            disconnected(inFlight, "The host sent inconsistent remote session data; synchronization is required.");
        } finally {
            if (saveInFlight != null) saveInFlight.result().completeExceptionally(
                    new IOException("Host save outcome is unconfirmed; recover its exact receipt."));
            if (handoutInFlight != null) {
                handoutInFlight.result().completeExceptionally(
                        new IOException("The handout outcome is unconfirmed; recover its exact receipt."));
            }
            if (playerInFlight != null) {
                playerInFlight.result().completeExceptionally(
                        new IOException("The player-output outcome is unconfirmed; recover its exact receipt."));
            }
            if (workspaceInFlight != null) {
                workspaceInFlight.result().completeExceptionally(
                        new IOException("The workspace command outcome is unconfirmed; recover its exact receipt."));
            }
            synchronized (this) {
                if (worker == Thread.currentThread()) {
                    active = null;
                    worker = null;
                }
            }
        }
    }

    private void pair(RemoteTlsChannel channel, RemoteWire.Message pair) throws IOException, InterruptedException {
        var response = exchange(channel, pair.type(), pair.payload());
        requireType(response, RemoteWire.Type.APPROVAL);
        var waiting = RemoteWire.payload(response, RemoteProtocol.Approval.class);
        synchronized (this) {
            if (phase == Phase.CLOSED) {
                throw new IOException("Remote pairing cancelled.");
            }
            approval = waiting;
            phase = Phase.AWAITING_APPROVAL;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        while (phase == Phase.AWAITING_APPROVAL) {
            if (System.nanoTime() >= deadline) {
                throw new PeerFailure(new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.EXPIRED,
                        "The host did not decide the pairing request in time."));
            }
            Thread.sleep(POLL_MILLIS);
            var decision = exchange(channel, RemoteWire.Type.APPROVAL,
                    new RemoteProtocol.ApprovalPoll(waiting.requestId()));
            if (decision.type() == RemoteWire.Type.GRANT) {
                var grant = RemoteWire.payload(decision, RemoteProtocol.Connect.class);
                synchronized (this) {
                    if (phase == Phase.CLOSED) {
                        throw new IOException("Remote pairing cancelled.");
                    }
                    credential = grant;
                }
                return;
            }
            requireType(decision, RemoteWire.Type.APPROVAL);
            if (!waiting.equals(RemoteWire.payload(decision, RemoteProtocol.Approval.class))) {
                throw new IOException("The host changed the pending approval identity.");
            }
        }
        throw new IOException("Remote pairing cancelled.");
    }

    private synchronized void publishState(HostSettingsAuthority.State state) {
        RemoteProtocol.validateState(state);
        if (phase == Phase.CLOSED) {
            return;
        }
        if (latestState != null) {
            if (!latestState.sessionId().equals(state.sessionId())) {
                throw new IllegalArgumentException("The host settings epoch changed.");
            }
            if (state.settings().revision() < latestState.settings().revision()) {
                return; // A recovered old receipt must not roll a newer baseline backwards.
            }
            if (state.settings().revision() == latestState.settings().revision()
                    && !state.settings().equals(latestState.settings())) {
                throw new IllegalArgumentException("Different settings for the same host revision.");
            }
            if (state.nextSequence() < latestState.nextSequence()) {
                return;
            }
        }
        latestState = state;
    }

    private synchronized void disconnected(Edit inFlight, String message) {
        clearPlayerPointers();
        cancelTransfers();
        workspaceReady = false;
        IOException failure = new IOException(message);
        if (inFlight != null) {
            inFlight.result().completeExceptionally(failure);
        }
        failQueued(failure);
        if (phase != Phase.CLOSED) {
            phase = Phase.DISCONNECTED;
            lastError = message;
        }
        approval = null;
    }

    private synchronized void publishWorkspace(RemoteWorkspaceAuthority.State state) {
        Objects.requireNonNull(state, "state");
        if (phase == Phase.CLOSED) {
            return;
        }
        if (latestWorkspace != null) {
            if (!latestWorkspace.sessionId().equals(state.sessionId())) {
                throw new IllegalArgumentException("The host workspace session changed.");
            }
            if (state.revision() < latestWorkspace.revision()
                    || state.nextSequence() < latestWorkspace.nextSequence()) {
                return;
            }
            if (state.revision() == latestWorkspace.revision()
                    && (!state.contextId().equals(latestWorkspace.contextId())
                    || !state.document().equals(latestWorkspace.document())
                    || !state.changedAt().equals(latestWorkspace.changedAt())
                    || !state.assets().equals(latestWorkspace.assets())
                    || !state.history().equals(latestWorkspace.history()))) {
                throw new IllegalArgumentException("Different workspace data for the same revision.");
            }
        }
        latestWorkspace = state;
        workspaceReady = true;
        workspaceStatus = null;
        workspaceFailure = null;
    }

    private void failQueued(IOException failure) {
        OperationQuery operation;
        while ((operation = operationQueries.poll()) != null) operation.result().completeExceptionally(failure);
        CompletableFuture<RemoteMapCatalog.Listing> catalog;
        while ((catalog = mapCatalogQueries.poll()) != null) catalog.completeExceptionally(failure);
        SaveQuery save;
        while ((save = saveQueries.poll()) != null) save.result().completeExceptionally(failure);
        DirectoryQuery directory;
        while ((directory = directoryQueries.poll()) != null) directory.result().completeExceptionally(failure);
        Edit edit;
        while ((edit = edits.poll()) != null) {
            edit.result().completeExceptionally(failure);
        }
        WorkspaceEdit workspaceEdit;
        while ((workspaceEdit = workspaceEdits.poll()) != null) {
            workspaceEdit.result().completeExceptionally(failure);
        }
        PlayerEdit playerEdit;
        while ((playerEdit = playerEdits.poll()) != null) {
            playerEdit.result().completeExceptionally(failure);
        }
        HandoutEdit handoutEdit;
        while ((handoutEdit = handoutEdits.poll()) != null) {
            handoutEdit.result().completeExceptionally(failure);
        }
    }

    private synchronized RemotePlayerSession.Pointer takePlayerPointer() {
        long now = System.nanoTime();
        var pings = playerPings.stream().filter(p -> p.expiresNanos() > now && p.context().equals(pointerContext))
                .map(p -> new RemotePlayerSession.Ping(p.point(), Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(p.expiresNanos() - now)))).toList();
        playerPings.clear();
        return new RemotePlayerSession.Pointer(pointerContext, playerLaser, pings);
    }

    private void clearPlayerPointers() {
        playerPointers.clear();
        playerPings.clear();
        playerLaser = null;
        pointerContext = null;
    }

    private void cancelTransfers() {
        while (!assetJobs.isEmpty()) assetJobs.removeFirst().result().completeExceptionally(
                new IOException("Connection ended before this asset transfer started."));
        for (var transfer : java.util.List.copyOf(transfers)) {
            try {
                transfer.close();
            } catch (IOException failure) {
                System.getLogger(RemoteSessionClient.class.getName()).log(
                        System.Logger.Level.WARNING, "Could not cancel a remote asset transfer.", failure);
            }
        }
    }

    private void closeChannel() {
        if (active != null) {
            try {
                active.close();
            } catch (IOException failure) {
                System.getLogger(RemoteSessionClient.class.getName()).log(
                        System.Logger.Level.WARNING, "Could not close the remote client transport.", failure);
            }
        }
    }

    private static RemoteWire.Message exchange(RemoteTlsChannel channel, RemoteWire.Type type, Object payload)
            throws IOException {
        var request = RemoteWire.message(type, payload);
        RemoteMessageStream.send(channel, request);
        var response = RemoteMessageStream.receive(channel);
        if (!request.id().equals(response.id())) {
            throw new IOException("The host response does not match its request.");
        }
        if (response.type() == RemoteWire.Type.ERROR) {
            var failure = RemoteWire.payload(response, RemoteProtocol.Failure.class);
            throw new PeerFailure(failure);
        }
        return response;
    }

    private static void requireType(RemoteWire.Message message, RemoteWire.Type expected) throws IOException {
        if (message.type() != expected) {
            throw new IOException("Unexpected remote session response.");
        }
    }

    private static void validateResult(HostSettingsAuthority.Request request, HostSettingsAuthority.Result result) {
        Objects.requireNonNull(result, "result");
        RemoteProtocol.validateState(result.state());
        if (result.sequence() != request.sequence() || !request.edit().commandId().equals(result.commandId())
                || result.status() == null || result.message() == null) {
            throw new IllegalArgumentException("The host settings acknowledgement does not match its request.");
        }
    }

    private static final class PeerFailure extends IOException {
        PeerFailure(RemoteProtocol.Failure failure) {
            super(switch (failure.code()) {
                case UNAUTHORIZED -> "The host no longer authorizes this session; pair again.";
                case BUSY -> "The host already has an approved or pending remote client.";
                case INVALID_CODE -> "The pairing code is incorrect.";
                case RATE_LIMITED -> "Too many pairing attempts; wait a minute before trying again.";
                case EXPIRED -> "The pairing request or code expired; generate a new code on the host.";
                case REJECTED -> "The host rejected or cancelled this pairing request.";
                case STOPPED -> "The host stopped the remote session.";
                case INVALID_MESSAGE -> "The host rejected an unsupported or malformed remote request.";
            });
        }
    }
}
