package dmmt.remote;

import dmmt.log.Log;
import dmmt.ui.HandoutWindow;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

import javafx.scene.image.Image;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Client board coordinator. A lost acknowledgement retains the identical command for receipt recovery.
 */
public final class RemoteHandoutLink implements AutoCloseable
{
    public interface Connection
    {
        boolean connected();

        RemoteHandouts.State latest();

        CompletableFuture<RemoteHandouts.Update> send(RemoteHandouts.Request request);

        CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(RemoteAssetTransfer.Direction direction,
                                                                     RemoteAssetStore.AssetDescriptor asset);
    }

    private static final Log LOG = Log.of(RemoteHandoutLink.class);

    private final Thread owner = Thread.currentThread();
    private final Connection connection;
    private final RemoteAssetStore store;
    private final RemoteHandouts.Artwork artwork;
    private final Executor worker;
    private final Executor dispatcher;
    private final Consumer<HandoutWindow.Snapshot> display;
    private final BiConsumer<Boolean, String> status;
    private final Consumer<String> errors;
    private RemoteHandouts.State displayed;
    private RemoteHandouts.Request pending;
    private boolean preparing;
    private boolean sending;
    private boolean closed;
    private boolean resync;
    private RemoteHandouts.State failedState;
    private HandoutWindow.Snapshot displayedSnapshot;
    private boolean baseline;
    private BiConsumer<RemoteHandouts.Library, Map<String, Image>> displayLibrary = (library, images) -> {
    };
    private final Map<RemoteAssetStore.AssetDescriptor, Image> thumbnails = new LinkedHashMap<>();

    public void configureLibrary(BiConsumer<RemoteHandouts.Library, Map<String, Image>> display)
    {
        requireOwner();
        displayLibrary = Objects.requireNonNull(display);
    }

    public void recent(RemoteHandouts.Operation operation, String id)
    {
        requireOwner();
        if (operation == RemoteHandouts.Operation.BOARD)
            throw new IllegalArgumentException("A recent operation is required.");
        if (!canEdit())
        {
            LOG.warn("Recent handout operation {} not sent: host state not synchronized", operation);
            errors.accept("Host recent handouts are not synchronized; this operation was not sent.");
            tick();
            return;
        }
        pending = new RemoteHandouts.Request(displayed.sessionId(), UUID.randomUUID(), displayed.nextSequence(),
                                             displayed.revision(), displayed.board(), operation, id);
        tick();
    }

    public RemoteHandoutLink(Connection connection, RemoteAssetStore store, RemoteHandouts.Artwork artwork,
                             Executor worker, Executor dispatcher, Consumer<HandoutWindow.Snapshot> display,
                             BiConsumer<Boolean, String> status, Consumer<String> errors)
    {
        this.connection = Objects.requireNonNull(connection);
        this.store = Objects.requireNonNull(store);
        this.artwork = Objects.requireNonNull(artwork);
        this.worker = Objects.requireNonNull(worker);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.display = Objects.requireNonNull(display);
        this.status = Objects.requireNonNull(status);
        this.errors = Objects.requireNonNull(errors);
    }

    public void tick()
    {
        requireOwner();
        if (closed)
            return;
        var latest = connection.latest();
        if (!connection.connected())
        {
            status.accept(true, "Handouts paused - reconnect to the host.");
            return;
        }
        if (resync)
            return;
        if (pending != null)
        {
            status.accept(true, "Waiting for the host handout acknowledgement...");
            if (!sending && !preparing)
                send();
        }
        else if (!preparing && latest != null && latest.ready() && !latest.equals(displayed)
                && !latest.equals(failedState))
        {
            load(latest);
        }
        else if (!preparing)
        {
            status.accept(!canEdit(), latest == null ? "Loading host handouts..."
                                                     : failedState != null ? "Handout download failed. Reopen the board to retry."
                                                                           : latest.message());
        }
    }

    public boolean canEdit()
    {
        requireOwner();
        var latest = connection.latest();
        return !closed && !resync && !preparing && pending == null && connection.connected()
                && displayed != null && displayed.equals(latest) && latest.ready();
    }

    public boolean hasBaseline()
    {
        requireOwner();
        return baseline;
    }

    public void retryDownload()
    {
        requireOwner();
        failedState = null;
        tick();
    }

    public void changed(HandoutWindow.Snapshot snapshot)
    {
        upload(() -> artwork.publish(snapshot));
    }

    public void pasteFiles(java.util.List<java.nio.file.Path> files)
    {
        requireOwner();
        var paths = java.util.List.copyOf(files);
        var base = displayedSnapshot;
        upload(() -> artwork.publish(artwork.appendFiles(base, paths)));
    }

    private void upload(Preparation<RemoteHandouts.Prepared> publication)
    {
        requireOwner();
        if (!canEdit())
        {
            errors.accept("Host handouts are not synchronized; this change was not sent.");
            displayed = null;
            tick();
            return;
        }
        var base = displayed;
        preparing = true;
        status.accept(true, "Preparing and uploading handouts...");
        prepare(() -> {
            var prepared = publication.run();
            for (var asset : prepared.board().images().stream().distinct().toList())
            {
                connection.transfer(RemoteAssetTransfer.Direction.UPLOAD, asset).join();
            }
            return prepared;
        }).whenComplete((prepared, failure) -> dispatcher.execute(() -> {
            if (closed)
                return;
            preparing = false;
            if (failure != null)
            {
                LOG.warn("Handout upload to the host failed", failure);
                errors.accept("Handout upload failed. Check image limits, connection and available host storage.");
                displayed = null;
                tick();
                return;
            }
            // Preserve the revision seen when the edit began. A host edit during upload must win.
            LOG.info("Handouts uploaded to the host: {} image(s), shown={}", prepared.board().images().size(),
                     prepared.board().shown());
            pending = new RemoteHandouts.Request(base.sessionId(), UUID.randomUUID(), base.nextSequence(),
                                                 base.revision(), prepared.board());
            tick();
        }));
    }

    private void send()
    {
        sending = true;
        var request = pending;
        connection.send(request).whenComplete((update, failure) -> dispatcher.execute(() -> {
            if (closed || pending != request)
                return;
            sending = false;
            if (failure != null)
            {
                LOG.warn("Handout outcome unconfirmed", failure);
                errors.accept("Handout outcome unconfirmed; reconnect to recover its exact receipt.");
                return;
            }
            var receipt = update.receipt();
            if (receipt.status() == RemotePlayerSession.Status.BUSY)
                return;
            if (receipt.status() == RemotePlayerSession.Status.RESYNC_REQUIRED)
            {
                resync = true;
                LOG.warn("Host requires handout resynchronization: {}", RemoteSessionServer.safe(receipt.message()));
                status.accept(true, receipt.message());
                errors.accept(receipt.message());
                return;
            }
            pending = null;
            displayed = null;
            if (receipt.status() != RemotePlayerSession.Status.ACCEPTED)
            {
                LOG.warn("Host handout request {}: {}", receipt.status(), RemoteSessionServer.safe(receipt.message()));
                errors.accept(receipt.message());
            }
            else
                LOG.info("Host accepted the handout update");
            tick();
        }));
    }

    private void load(RemoteHandouts.State state)
    {
        preparing = true;
        status.accept(true, "Downloading host handouts...");
        var previousThumbnails = Map.copyOf(thumbnails);
        prepare(() -> {
            for (var asset : state.board().images().stream().distinct().toList())
            {
                if (!store.contains(asset.sha256()))
                    connection.transfer(RemoteAssetTransfer.Direction.DOWNLOAD, asset).join();
            }
            var prepared = artwork.load(state.board());
            var images = new LinkedHashMap<RemoteAssetStore.AssetDescriptor, Image>();
            for (var entry : state.library().entries())
            {
                var asset = entry.thumbnail();
                Image image = previousThumbnails.get(asset);
                if (image == null)
                {
                    if (!store.contains(asset.sha256()))
                        connection.transfer(RemoteAssetTransfer.Direction.DOWNLOAD, asset).join();
                    image = artwork.loadThumbnail(asset);
                }
                images.put(asset, image);
            }
            return new Loaded(prepared, images);
        }).whenComplete((loaded, failure) -> dispatcher.execute(() -> {
            if (closed)
                return;
            preparing = false;
            if (failure != null)
            {
                failedState = state;
                LOG.warn("Host handouts could not be downloaded or decoded", failure);
                errors.accept("Host handouts could not be downloaded or decoded. Reopen the board to retry.");
            }
            else if (connection.connected() && state.equals(connection.latest()))
            {
                failedState = null;
                display.accept(loaded.prepared().snapshot());
                thumbnails.clear();
                thumbnails.putAll(loaded.thumbnails());
                var byId = new LinkedHashMap<String, Image>();
                for (var entry : state.library().entries())
                    byId.put(entry.id(), thumbnails.get(entry.thumbnail()));
                displayLibrary.accept(state.library(), byId);
                displayed = state;
                displayedSnapshot = loaded.prepared().snapshot();
                baseline = true;
                LOG.info("Host handouts downloaded: {} image(s), shown={}", state.board().images().size(),
                         state.board().shown());
            }

            tick();
        }));
    }

    private record Loaded(RemoteHandouts.Prepared prepared, Map<RemoteAssetStore.AssetDescriptor, Image> thumbnails)
    {
    }

    @FunctionalInterface
    private interface Preparation<T>
    {
        T run() throws IOException;
    }

    private <T> CompletableFuture<T> prepare(Preparation<T> operation)
    {
        try
        {
            return CompletableFuture.supplyAsync(() -> {
                try
                {
                    return operation.run();
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

    @Override
    public void close()
    {
        requireOwner();
        closed = true;
        LOG.info("Remote handout board closed");
        thumbnails.clear();
        displayedSnapshot = null;
        status.accept(true, "Remote handout board closed.");
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Handout link accessed off its owner.");
    }
}
