package dmmt.remote;

import dmmt.log.Log;

import dmmt.model.DmProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Application-owned bridge: committed snapshots in, targeted existing-history operations out. */
public final class RemoteWorkspaceHost implements RemoteWorkspaceEndpoint, AutoCloseable {
    private static final Log LOG = Log.of(RemoteWorkspaceHost.class);
    private static final int MAX_PENDING_COMMITS = 128;

    private record Publication(RemoteProjectDocument document, Path file, boolean contextSwitch) {
    }

    private final Thread owner = Thread.currentThread();
    private final Supplier<DmProject> project;
    private final Supplier<Path> file;
    private final Supplier<Set<String>> protectedTargets;
    private final BooleanSupplier commitGate;
    private final Consumer<RemoteWorkspaceAuthority.Commit> apply;
    private final Consumer<String> errors;
    private final Executor ownerExecutor;
    private final Executor workerExecutor;
    private final RemoteWorkspaceArtwork artwork;
    private final ArrayDeque<Publication> publications = new ArrayDeque<>();
    private RemoteWorkspaceAuthority authority;
    private RemoteProjectDocument lastCaptured;
    private boolean publishing;
    private boolean applying;
    private volatile boolean closed;
    private String error;
    private RemoteFailure failure;
    private long contextGeneration;
    private CompletableFuture<RemoteWorkspaceAuthority.Result> preparingCommand;
    private Supplier<RemoteWorkspaceAuthority.History> history;
    private Consumer<RemoteWorkspaceAuthority.Operation> applyHistory;
    private RemotePlayerSession player;
    private RemoteHandoutSession handouts;
    private RemoteSaves saves;
    private RemoteMapCatalog maps;
    private RemoteOperations operations;

    public void configureOperations(RemoteOperations operations) {
        requireActive();
        this.operations = Objects.requireNonNull(operations);
    }

    @Override public RemoteOperations operations() {
        requireActive();
        return operations;
    }

    public void configureMaps(RemoteMapCatalog maps) {
        requireActive();
        this.maps = Objects.requireNonNull(maps);
    }

    @Override public RemoteMapCatalog maps() {
        requireActive();
        return maps;
    }

    public void configureSaves(RemoteSaves saves) {
        requireActive();
        this.saves = Objects.requireNonNull(saves);
    }

    @Override public RemoteSaves saves() {
        requireActive();
        return saves;
    }

    public void configureHandouts(RemoteHandoutSession handouts) {
        requireActive();
        this.handouts = Objects.requireNonNull(handouts);
    }

    @Override
    public RemoteHandoutSession handouts() {
        requireActive();
        return handouts;
    }

    public void configurePlayer(RemotePlayerSession player) {
        requireActive();
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public RemotePlayerSession player() {
        requireActive();
        return player;
    }

    public void configureHistory(Supplier<RemoteWorkspaceAuthority.History> history,
                                 Consumer<RemoteWorkspaceAuthority.Operation> applyHistory) {
        requireActive();
        this.history = Objects.requireNonNull(history, "history");
        this.applyHistory = Objects.requireNonNull(applyHistory, "applyHistory");
        if (authority != null) {
            authority.configureHistory(history, applyHistory);
        }
    }

    public RemoteWorkspaceHost(Supplier<DmProject> project, Supplier<Path> file,
                               Supplier<Set<String>> protectedTargets, Consumer<RemoteWorkspaceAuthority.Commit> apply,
                               RemoteAssetStore store, Path cache, Executor ownerExecutor, Executor workerExecutor,
                               Consumer<String> errors) {
        this(project, file, protectedTargets, apply, store, cache, ownerExecutor, workerExecutor, errors, () -> true);
    }

    public RemoteWorkspaceHost(Supplier<DmProject> project, Supplier<Path> file,
                               Supplier<Set<String>> protectedTargets, Consumer<RemoteWorkspaceAuthority.Commit> apply,
                               RemoteAssetStore store, Path cache, Executor ownerExecutor, Executor workerExecutor,
                               Consumer<String> errors, BooleanSupplier commitGate) {
        this(project, file, protectedTargets, apply, store, cache, ownerExecutor, workerExecutor, errors, commitGate,
                (before, after) -> { });
    }

    public RemoteWorkspaceHost(Supplier<DmProject> project, Supplier<Path> file,
                               Supplier<Set<String>> protectedTargets, Consumer<RemoteWorkspaceAuthority.Commit> apply,
                               RemoteAssetStore store, Path cache, Executor ownerExecutor, Executor workerExecutor,
                               Consumer<String> errors, BooleanSupplier commitGate,
                               java.util.function.BiConsumer<DmProject, DmProject> completion) {
        this.project = Objects.requireNonNull(project, "project");
        this.file = Objects.requireNonNull(file, "file");
        this.protectedTargets = Objects.requireNonNull(protectedTargets, "protectedTargets");
        this.commitGate = Objects.requireNonNull(commitGate, "commitGate");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        artwork = new RemoteWorkspaceArtwork(store, cache, file, completion);
        committed();
    }

    @Override
    public boolean ready() {
        requireOwner();
        return !closed && commitGate.getAsBoolean() && error == null && authority != null
                && !publishing && publications.isEmpty();
    }

    public String error() {
        requireOwner();
        return error;
    }

    @Override
    public RemoteFailure failure() {
        requireOwner();
        return failure;
    }

    public void committed() {
        requireActive();
        if (!applying) {
            enqueue(false);
        }
    }

    public void switchedContext() {
        requireActive();
        contextGeneration++;
        enqueue(true);
    }

    @Override
    public RemoteWorkspaceAuthority.State state() {
        requireActive();
        if (authority == null) {
            throw new IllegalStateException("The initial workspace is still being prepared.");
        }
        return authority.state();
    }

    @Override
    public RemoteWorkspaceAuthority.Result execute(RemoteWorkspaceAuthority.Request request) {
        requireActive();
        if (!ready()) {
            return stateBusy(request);
        }
        if (!artwork.canCapture(RemoteProjectDocument.capture(project.get()), file.get())) {
            return authority.busy(request, "The host has unfinished artwork changes; retry after they are committed.");
        }
        // The async entry point prepares uploaded assets first. The sync path accepts cached artwork only.
        authority.protectHostTargets(protectedTargets.get());
        return apply(request);
    }

    @Override
    public CompletableFuture<RemoteWorkspaceAuthority.Result> executeAsync(RemoteWorkspaceAuthority.Request request) {
        return executeAsync(request, () -> { });
    }

    @Override
    public CompletableFuture<RemoteWorkspaceAuthority.Result> executeAsync(RemoteWorkspaceAuthority.Request request,
                                                                           Runnable authorize) {
        requireActive();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorize, "authorize").run();
        if (!ready() || preparingCommand != null) {
            return CompletableFuture.completedFuture(stateBusy(request));
        }
        if (!state().sessionId().equals(request.sessionId()) || !state().contextId().equals(request.contextId())
                || request.sequence() < state().nextSequence()) {
            return CompletableFuture.completedFuture(execute(request));
        }
        var replacements = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var change : request.changes()) {
            if (change.replacement() != null) {
                replacements.put(change.target(), change.replacement());
            }
        }
        var incoming = new RemoteProjectDocument(state().document().projectId(), replacements);
        incoming.validatePortableAssets();
        if (artwork.canCapture(incoming, null)) {
            return CompletableFuture.completedFuture(execute(request));
        }
        long generation = contextGeneration;
        var completion = new CompletableFuture<RemoteWorkspaceAuthority.Result>();
        preparingCommand = completion;
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (closed) throw new IOException("Remote workspace closed before artwork preparation.");
                    return artwork.prepareIncoming(incoming);
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, workerExecutor).whenComplete((prepared, failure) -> dispatch(completion, () -> {
                preparingCommand = null;
                if (closed) {
                    completion.completeExceptionally(new IOException("Remote workspace closed during asset preparation."));
                    return;
                }
                try {
                    authorize.run();
                } catch (RemoteSessionAccess.AccessException denied) {
                    completion.complete(authority.reject(request,
                            "The control connection ended during artwork preparation; this edit was not applied."));
                    return;
                }
                if (failure != null) {
                    String message = "The host could not prepare uploaded artwork; this edit was not applied.";
                    errors.accept(message);
                    completion.complete(authority.reject(request, message));
                    return;
                }
                artwork.accept(prepared);
                completion.complete(generation == contextGeneration ? execute(request)
                        : authority.busy(request, "The host changed maps while preparing artwork; synchronize before retrying."));
            }));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            preparingCommand = null;
            errors.accept("The remote artwork worker is unavailable; this edit was not applied.");
            completion.complete(authority.reject(request, "The host artwork worker is unavailable."));
        }
        return completion.copy();
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        if (operations != null) operations.close();
        if (maps != null) maps.close();
        if (saves != null) saves.close();
        if (handouts != null) handouts.close();
        contextGeneration++;
        publications.clear();
        if (authority != null) {
            authority.close();
        }
        if (preparingCommand != null) {
            preparingCommand.completeExceptionally(new IOException("The remote workspace was closed."));
            preparingCommand = null;
        }
    }

    private RemoteWorkspaceAuthority.Result apply(RemoteWorkspaceAuthority.Request request) {
        applying = true;
        try {
            var result = authority.execute(request);
            if (result.status() == RemoteWorkspaceAuthority.Status.ACCEPTED) {
                lastCaptured = null;
            }
            return result;
        } finally {
            applying = false;
        }
    }

    private RemoteWorkspaceAuthority.Result stateBusy(RemoteWorkspaceAuthority.Request request) {
        if (authority == null) {
            throw new IllegalStateException("The initial workspace is still being prepared.");
        }
        return authority.busy(request, error == null
                ? "The host is finishing a local operation or preparing artwork; this edit was not applied." : error);
    }

    private void enqueue(boolean contextSwitch) {
        var local = RemoteProjectDocument.capture(project.get());
        if (error != null) {
            error = null;
            failure = null;
            publications.clear();
            lastCaptured = null;
            contextSwitch = true;
        }
        if (!contextSwitch && local.equals(lastCaptured)) {
            return;
        }
        lastCaptured = local;
        if (publications.size() >= MAX_PENDING_COMMITS) {
            fail("Remote synchronization paused: too many committed edits are waiting for artwork preparation.");
            return;
        }
        publications.addLast(new Publication(local, file.get(), contextSwitch));
        publishNext();
    }

    private void publishNext() {
        if (closed || error != null || publishing || publications.isEmpty()) {
            return;
        }
        var next = publications.removeFirst();
        if (artwork.canCapture(next.document(), next.file())) {
            publish(next, artwork.portable(next.document(), next.file()));
            publishNext();
            return;
        }
        publishing = true;
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (closed) throw new IOException("Remote workspace closed before artwork publication.");
                    return artwork.prepare(next.document(), next.file());
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, workerExecutor).whenComplete((prepared, failure) -> dispatch(null, () -> {
                publishing = false;
                if (closed) {
                    return;
                }
                if (failure != null) {
                    fail("Remote synchronization paused: required map artwork could not be published.", failure);
                    return;
                }
                artwork.accept(prepared);
                publish(next, prepared.document());
                publishNext();
            }));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            publishing = false;
            fail("Remote synchronization paused: the artwork worker is unavailable.", rejected);
        }
    }

    private void publish(Publication publication, RemoteProjectDocument portable) {
        if (authority == null) {
            authority = new RemoteWorkspaceAuthority(project, apply, artwork, portable);
            if (history != null) {
                authority.configureHistory(history, applyHistory);
            }
        } else if (publication.contextSwitch()) {
            authority.switchContext(portable);
        } else {
            authority.observeHostDocument(portable);
        }
    }

    private void dispatch(CompletableFuture<?> completion, Runnable action) {
        try {
            ownerExecutor.execute(() -> {
                requireOwner();
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    LOG.error("Could not apply prepared remote artwork.", failure);
                    if (!closed) {
                        fail("Remote synchronization paused: prepared artwork could not be applied.", failure);
                    }
                    if (completion != null) {
                        completion.completeExceptionally(failure);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (completion != null) {
                completion.completeExceptionally(rejected);
            }
            LOG.error("Could not deliver remote artwork preparation to its application owner.", rejected);
        }
    }

    private void fail(String message) {
        fail(message, new IOException(message));
    }

    private void fail(String message, Throwable cause) {
        error = message;
        failure = RemoteFailure.capture(message, cause);
        LOG.error(message, cause);
        errors.accept(message);
    }

    private void requireActive() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("The remote workspace is closed.");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("The remote workspace bridge must run on the application owner thread.");
        }
    }
}
