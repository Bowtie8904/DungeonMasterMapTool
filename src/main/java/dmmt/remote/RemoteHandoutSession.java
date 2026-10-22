package dmmt.remote;

import dmmt.ui.HandoutWindow;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.IntSupplier;

/**
 * Owner-thread board authority; image preparation never blocks the JavaFX thread.
 */
public final class RemoteHandoutSession implements AutoCloseable
{
    private record Stored(RemoteHandouts.Request request, RemotePlayerSession.Receipt receipt)
    {
    }

    private final Thread owner = Thread.currentThread();
    private final UUID sessionId = UUID.randomUUID();
    private final Supplier<HandoutWindow.Snapshot> capture;
    private final Consumer<HandoutWindow.Snapshot> apply;
    private final Consumer<String> errors;
    private final RemoteHandouts.Artwork artwork;
    private final Executor worker;
    private final Executor dispatcher;
    private final LinkedHashMap<Long, Stored> receipts = new LinkedHashMap<>();
    private RemoteHandouts.Board board = RemoteHandouts.Board.empty();
    private HandoutWindow.Snapshot observed;
    private long revision;
    private long nextSequence = 1;
    private boolean publishing;
    private boolean applying;
    private boolean persisting;
    private volatile boolean closed;
    private String error;
    private CompletableFuture<RemoteHandouts.Update> preparing;
    private RemoteHandoutLibrary library;
    private IntSupplier recentMax = () -> 0;
    private RemoteHandouts.Library recentState = RemoteHandouts.Library.empty();
    private long observedLibraryVersion = -1;
    private int observedMax;

    public void configureLibrary(RemoteHandoutLibrary library, IntSupplier max)
    {
        requireOwner();
        if (this.library != null)
            throw new IllegalStateException("Handout library already configured.");
        this.library = Objects.requireNonNull(library);
        recentMax = Objects.requireNonNull(max);
        observed = null;
        changed();
    }

    private record Publication(RemoteHandouts.Prepared prepared, RemoteHandouts.Library library)
    {
    }

    public RemoteHandoutSession(Supplier<HandoutWindow.Snapshot> capture, Consumer<HandoutWindow.Snapshot> apply,
                                RemoteHandouts.Artwork artwork, Executor worker, Executor dispatcher,
                                Consumer<String> errors)
    {
        this.capture = Objects.requireNonNull(capture);
        this.apply = Objects.requireNonNull(apply);
        this.artwork = Objects.requireNonNull(artwork);
        this.worker = Objects.requireNonNull(worker);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.errors = Objects.requireNonNull(errors);
        changed();
    }

    public void changed()
    {
        requireOwner();
        if (closed || applying)
            return;
        var snapshot = capture.get();
        long libraryVersion = library == null ? -1 : persisting ? observedLibraryVersion : library.version();
        int max = recentMax.getAsInt();
        if (snapshot.equals(observed) && libraryVersion == observedLibraryVersion && max == observedMax)
            return;
        observed = snapshot;
        observedLibraryVersion = libraryVersion;
        observedMax = max;
        revision++;
        error = null;
        if (!publishing)
            publish(snapshot, revision);
    }

    private void publish(HandoutWindow.Snapshot snapshot, long generation)
    {
        publishing = true;
        int max = observedMax;
        var currentLibrary = library;
        prepare(() -> new Publication(artwork.publish(snapshot),
                                      currentLibrary == null ? RemoteHandouts.Library.empty() : currentLibrary.publish(max)))
                .whenComplete((publication, failure) -> dispatcher.execute(() -> {
                    if (closed)
                        return;
                    publishing = false;
                    if (generation != revision)
                    {
                        publish(observed, revision);
                    }
                    else if (failure != null)
                    {
                        error = "Handouts could not be prepared for remote control. Reduce image size and try again.";
                        errors.accept(error);
                    }
                    else
                    {
                        board = publication.prepared().board();
                        recentState = publication.library();
                        observedLibraryVersion = library == null ? -1 : recentState.version();
                    }
                }));
    }

    public CompletableFuture<RemoteHandouts.Update> exchange(RemoteHandouts.Query query, Runnable authorize)
    {
        requireOwner();
        if (closed)
            return CompletableFuture.failedFuture(new IOException("Handout session is closed."));
        authorize.run();
        changed();
        var request = query.request();
        if (request == null)
            return CompletableFuture.completedFuture(new RemoteHandouts.Update(state(), null));
        if (!sessionId.equals(request.sessionId()))
            return completed(request, RemotePlayerSession.Status.RESYNC_REQUIRED,
                             "The host handout session changed; synchronize again.");
        var stored = receipts.get(request.sequence());
        if (stored != null)
        {
            return CompletableFuture.completedFuture(stored.request().equals(request)
                                                     ? new RemoteHandouts.Update(state(), stored.receipt())
                                                     : result(request, RemotePlayerSession.Status.RESYNC_REQUIRED, "The handout receipt does not match."));
        }
        if (request.sequence() != nextSequence || receipts.values().stream()
                                                          .anyMatch(s -> s.request().commandId().equals(request.commandId())))
        {
            return completed(request, RemotePlayerSession.Status.RESYNC_REQUIRED, "The handout receipt is unavailable.");
        }
        if (publishing || preparing != null)
            return completed(request, RemotePlayerSession.Status.BUSY,
                             "The host is preparing handouts; retry after synchronization.");
        if (request.expectedRevision() != revision || error != null)
        {
            return remember(request, RemotePlayerSession.Status.CONFLICT, "Host handouts changed; the host board was kept.");
        }
        long generation = revision;
        var before = observed;
        var acceptedBoard = board;
        var expectedLibrary = recentState;
        int max = observedMax;
        var completion = new CompletableFuture<RemoteHandouts.Update>();
        preparing = completion;
        prepare(() -> {
            var requested = request.board();
            if (request.operation() != RemoteHandouts.Operation.BOARD)
            {
                if (library == null || !requested.equals(acceptedBoard))
                    throw new RemoteHandoutLibrary.Conflict();
                var entry = expectedLibrary.entries().stream().filter(e -> e.id().equals(request.recentId()))
                                           .findFirst().orElseThrow(RemoteHandoutLibrary.Conflict::new);
                if (request.operation() == RemoteHandouts.Operation.ADD_RECENT)
                {
                    var images = new java.util.ArrayList<>(requested.images());
                    images.add(entry.original());
                    requested = new RemoteHandouts.Board(images, requested.rotation(), requested.mirrored(),
                                                         requested.shown(), requested.showOnly());
                }
            }
            return artwork.load(requested);
        }).whenComplete((prepared, failure) -> dispatcher.execute(() -> {
            if (closed)
                return;
            try
            {
                authorize.run();
                changed();
                if (generation != revision)
                {
                    preparing = null;
                    completion.complete(rememberResult(request, RemotePlayerSession.Status.CONFLICT,
                                                       "The host changed handouts during preparation; the host board was kept."));
                }
                else if (failure != null)
                {
                    preparing = null;
                    String message = "The host could not decode the uploaded handout. Check PNG format and image limits.";
                    errors.accept(message);
                    completion.complete(rememberResult(request, RemotePlayerSession.Status.FAILED, message));
                }
                else
                {
                    if (library == null)
                        finish(request, prepared, completion, authorize, generation);
                    else
                    {
                        persisting = true;
                        prepare(() -> {
                            library.persist(request, expectedLibrary, before, prepared.snapshot(), max);
                            return library.publish(max);
                        }).whenComplete((persisted, persistenceFailure) -> dispatcher.execute(() -> {
                            if (closed)
                                return;
                            persisting = false;
                            preparing = null;
                            if (persistenceFailure != null)
                            {
                                changed();
                                var cause = persistenceFailure.getCause();
                                String message = cause instanceof RemoteHandoutLibrary.Conflict
                                                 ? "Host recent handouts changed; the host state was kept."
                                                 : "Could not persist host recent handouts. The board was not applied; synchronize before retrying.";
                                errors.accept(message);
                                completion.complete(rememberResult(request, cause instanceof RemoteHandoutLibrary.Conflict
                                                                            ? RemotePlayerSession.Status.CONFLICT : RemotePlayerSession.Status.FAILED, message));
                            }
                            else
                            {
                                long completionGeneration = generation;
                                if (!recentState.equals(persisted))
                                {
                                    revision++;
                                    completionGeneration++;
                                }
                                recentState = persisted;
                                observedLibraryVersion = persisted.version();
                                finish(request, prepared, completion, authorize, completionGeneration);
                            }
                        }));
                    }
                }
            }
            catch (RuntimeException denied)
            {
                preparing = null;
                completion.completeExceptionally(denied);
            }
        }));
        return completion.copy();
    }

    private void finish(RemoteHandouts.Request request, RemoteHandouts.Prepared prepared,
                        CompletableFuture<RemoteHandouts.Update> completion, Runnable authorize, long generation)
    {
        preparing = null;
        try
        {
            authorize.run();
            changed();
            if (revision != generation)
            {
                completion.complete(rememberResult(request, RemotePlayerSession.Status.CONFLICT,
                                                   "The host changed handouts during persistence; the host board was kept. Uploaded recents may have been saved."));
                return;
            }
            applying = true;
            if (request.operation() != RemoteHandouts.Operation.REMOVE_RECENT)
                apply.accept(prepared.snapshot());
            observed = capture.get();
            board = prepared.board();
            revision++;
            completion.complete(rememberResult(request, RemotePlayerSession.Status.ACCEPTED, "Handouts updated."));
        }
        catch (RuntimeException failure)
        {
            errors.accept("The handout operation could not finish. Recent images may already have been saved.");
            completion.complete(rememberResult(request, RemotePlayerSession.Status.FAILED,
                                               "The handout operation could not finish. Recent images may already have been saved; synchronize before retrying."));
        }
        finally
        {
            applying = false;
            changed();
        }
    }

    private <T> CompletableFuture<T> prepare(Preparation<T> task)
    {
        try
        {
            return CompletableFuture.supplyAsync(() -> {
                try
                {
                    if (closed)
                        throw new IOException("The handout session closed before preparation.");
                    return task.run();
                }
                catch (IOException failure)
                {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, worker);
        }
        catch (java.util.concurrent.RejectedExecutionException rejected)
        {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @FunctionalInterface
    private interface Preparation<T>
    {
        T run() throws IOException;
    }

    private RemoteHandouts.State state()
    {
        return new RemoteHandouts.State(sessionId, revision, nextSequence, board,
                                        !publishing && preparing == null && error == null,
                                        error != null ? error : publishing || preparing != null ? "Preparing handouts..." : "Handouts ready.",
                                        recentState);
    }

    private RemoteHandouts.Update result(RemoteHandouts.Request request, RemotePlayerSession.Status status, String message)
    {
        return new RemoteHandouts.Update(state(), new RemotePlayerSession.Receipt(request.commandId(), request.sequence(), status, message));
    }

    private CompletableFuture<RemoteHandouts.Update> completed(RemoteHandouts.Request request,
                                                               RemotePlayerSession.Status status, String message)
    {
        return CompletableFuture.completedFuture(result(request, status, message));
    }

    private RemoteHandouts.Update rememberResult(RemoteHandouts.Request request, RemotePlayerSession.Status status, String message)
    {
        var receipt = new RemotePlayerSession.Receipt(request.commandId(), request.sequence(), status, message);
        receipts.put(request.sequence(), new Stored(request, receipt));
        nextSequence++;
        while (receipts.size() > 256)
            receipts.remove(receipts.keySet().iterator().next());
        return new RemoteHandouts.Update(state(), receipt);
    }

    private CompletableFuture<RemoteHandouts.Update> remember(RemoteHandouts.Request request,
                                                              RemotePlayerSession.Status status, String message)
    {
        return CompletableFuture.completedFuture(rememberResult(request, status, message));
    }

    @Override
    public void close()
    {
        requireOwner();
        closed = true;
        if (preparing != null)
            preparing.completeExceptionally(new IOException("Handout session closed."));
        receipts.clear();
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Handout authority accessed off its owner.");
    }
}
