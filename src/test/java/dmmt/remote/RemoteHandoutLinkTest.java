package dmmt.remote;

import dmmt.FxTestSupport;
import dmmt.ui.HandoutWindow;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHandoutLinkTest
{
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception
    {
        FxTestSupport.startJavaFx();
    }

    @Test
    void recentListDownloadsOnlyThumbnailsUntilAnImageIsAddedAndRemovalKeepsTheBoard() throws Exception
    {
        try (var f = new Fixture())
        {
            var recents = new dmmt.ui.RecentHandouts(directory.resolve("recents"));
            String id = recents.add(new WritableImage(300, 200), 100);
            f.host.configureLibrary(new RemoteHandoutLibrary(() -> recents, f.hostStore, directory.resolve("library")), () -> 100);
            var displayed = new AtomicReference<java.util.Map<String, javafx.scene.image.Image>>();
            f.link.configureLibrary((library, images) -> displayed.set(images));
            f.drain();
            f.link.tick();
            f.drain();
            var entry = f.host.exchange(new RemoteHandouts.Query(null), () -> {
            }).join().state().library().entries().getFirst();
            assertNotNull(displayed.get().get(id));
            assertEquals(List.of(entry.thumbnail()), f.downloads);
            assertFalse(f.clientStore.contains(entry.original().sha256()), "Don't preload full-resolution recent images.");
            f.link.recent(RemoteHandouts.Operation.ADD_RECENT, id);
            f.drain();
            assertEquals(1, f.clientBoard.get().images().size());
            assertEquals(300, f.clientBoard.get().images().getFirst().getWidth());
            assertTrue(f.clientStore.contains(entry.original().sha256()));
            assertEquals(0, f.uploads, "Reusing a recent is a host operation, not a new client upload.");
            f.link.recent(RemoteHandouts.Operation.REMOVE_RECENT, id);
            f.drain();
            assertTrue(displayed.get().isEmpty());
            assertTrue(recents.ids().isEmpty());
            assertEquals(1, f.clientBoard.get().images().size());
            assertTrue(f.link.canEdit());
        }
    }

    @Test
    void uploadsImageBeforeCommandAndRecoversTheExactLostAcknowledgement() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            f.link.tick();
            f.drain();
            assertTrue(f.link.canEdit());
            f.loseAcknowledgement = true;
            f.link.changed(imageSnapshot());
            assertFalse(f.link.canEdit());
            f.drain();
            assertEquals(1, f.applied);
            assertEquals(1, f.uploads);
            assertEquals(List.of(0), f.hostBoard.get().showOnly());
            assertTrue(f.notices.stream().anyMatch(s -> s.contains("unconfirmed")));
            assertFalse(f.link.canEdit());
            f.connected = false;
            f.link.tick();
            f.connected = true;
            f.link.tick();
            f.drain();
            assertEquals(1, f.applied);
            assertEquals(2, f.sent.size());
            assertSame(f.sent.getFirst(), f.sent.getLast());
            assertTrue(f.link.canEdit());
            assertEquals(90, f.clientBoard.get().rotation());
            assertTrue(f.clientBoard.get().shown());
            assertTrue(f.clientBoard.get().mirrored());
        }
    }

    @Test
    void hostEditsWhileClientUploadsWinAndClientRollsBackWithoutASecondApplication() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            f.link.tick();
            f.drain();
            f.duringUpload = () -> {
                f.hostBoard.set(new HandoutWindow.Snapshot(List.of(), 270, false, false, null));
                f.host.changed();
            };
            f.link.changed(imageSnapshot());
            f.drain();
            f.link.tick();
            f.drain();
            assertEquals(0, f.applied);
            assertEquals(270, f.clientBoard.get().rotation());
            assertTrue(f.clientBoard.get().images().isEmpty());
            assertTrue(f.notices.stream().anyMatch(s -> s.contains("host board was kept")));
            assertTrue(f.link.canEdit());
        }
    }

    @Test
    void uploadAndDownloadFailuresAreVisibleAndNeverApplyAnEmptySuccessBoard() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            f.link.tick();
            f.drain();
            f.transferFailure = true;
            f.link.changed(imageSnapshot());
            f.drain();
            assertEquals(0, f.applied);
            assertTrue(f.sent.isEmpty());
            assertTrue(f.notices.stream().anyMatch(s -> s.contains("upload failed")));
            f.hostBoard.set(new HandoutWindow.Snapshot(List.of(new WritableImage(4, 4)), 0, false, true, null));
            f.host.changed();
            f.drain();
            f.link.tick();
            f.drain();
            assertTrue(f.notices.stream().anyMatch(s -> s.contains("could not be downloaded")));
            assertFalse(f.link.canEdit());
            assertTrue(f.clientBoard.get().images().isEmpty());
            f.transferFailure = false;
            f.link.retryDownload();
            f.drain();
            assertTrue(f.link.canEdit());
            assertEquals(1, f.clientBoard.get().images().size());
        }
    }

    @Test
    void closedLinkIgnoresLatePreparationWithoutHidingHostPresentation() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            f.link.tick();
            f.drain();
            f.hostBoard.set(imageSnapshot());
            f.host.changed();
            f.drain();
            f.link.tick();
            f.link.close();
            f.drain();
            assertTrue(f.hostBoard.get().shown());
            assertTrue(f.clientBoard.get().images().isEmpty());
            assertTrue(f.sent.isEmpty());
        }
    }

    private static HandoutWindow.Snapshot imageSnapshot()
    {
        return new HandoutWindow.Snapshot(List.of(new WritableImage(8, 6)), 90, true, true, List.of(0));
    }

    private final class Fixture implements AutoCloseable
    {
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        final ArrayDeque<Runnable> owners = new ArrayDeque<>();
        final List<String> notices = new ArrayList<>();
        final List<RemoteHandouts.Request> sent = new ArrayList<>();
        final List<RemoteAssetStore.AssetDescriptor> downloads = new ArrayList<>();
        final AtomicReference<HandoutWindow.Snapshot> hostBoard = new AtomicReference<>(
                new HandoutWindow.Snapshot(List.of(), 0, false, false, null));
        final AtomicReference<HandoutWindow.Snapshot> clientBoard = new AtomicReference<>(hostBoard.get());
        final RemoteAssetStore hostStore = new RemoteAssetStore(directory.resolve("host"), 1_048_576, 16_777_216, 1_048_576);
        final RemoteAssetStore clientStore = new RemoteAssetStore(directory.resolve("client"), 1_048_576, 16_777_216, 1_048_576);
        int applied, uploads;
        final RemoteHandoutSession host = new RemoteHandoutSession(hostBoard::get, value -> {
            applied++;
            hostBoard.set(value);
        }, new RemoteHandouts.Artwork(hostStore, directory.resolve("host-cache")), workers::add, owners::add, notices::add);
        boolean connected = true, loseAcknowledgement, transferFailure;
        Runnable duringUpload = () -> {
        };
        final RemoteHandoutLink link = new RemoteHandoutLink(new RemoteHandoutLink.Connection()
        {
            @Override
            public boolean connected()
            {
                return connected;
            }

            @Override
            public RemoteHandouts.State latest()
            {
                return host.exchange(new RemoteHandouts.Query(null), () -> {
                }).join().state();
            }

            @Override
            public CompletableFuture<RemoteHandouts.Update> send(RemoteHandouts.Request request)
            {
                sent.add(request);
                return host.exchange(new RemoteHandouts.Query(request), () -> {
                }).thenCompose(result -> {
                    if (loseAcknowledgement)
                    {
                        loseAcknowledgement = false;
                        return CompletableFuture.failedFuture(new IOException("Lost acknowledgement."));
                    }
                    return CompletableFuture.completedFuture(result);
                });
            }

            @Override
            public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(
                    RemoteAssetTransfer.Direction direction, RemoteAssetStore.AssetDescriptor asset)
            {
                if (transferFailure)
                    return CompletableFuture.failedFuture(new IOException("Transfer failed."));
                try
                {
                    if (direction == RemoteAssetTransfer.Direction.UPLOAD)
                    {
                        uploads++;
                        var bytes = new java.io.ByteArrayOutputStream();
                        clientStore.transferTo(asset.sha256(), bytes);
                        hostStore.ingest(new java.io.ByteArrayInputStream(bytes.toByteArray()), asset.size(), asset.sha256());
                        duringUpload.run();
                    }
                    else
                    {
                        downloads.add(asset);
                        var bytes = new java.io.ByteArrayOutputStream();
                        hostStore.transferTo(asset.sha256(), bytes);
                        clientStore.ingest(new java.io.ByteArrayInputStream(bytes.toByteArray()), asset.size(), asset.sha256());
                    }
                    return CompletableFuture.completedFuture(asset);
                }
                catch (IOException failure)
                {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        }, clientStore, new RemoteHandouts.Artwork(clientStore, directory.resolve("client-cache")),
                                                             workers::add, owners::add, clientBoard::set, (readOnly, message) -> {
        }, notices::add);

        Fixture() throws IOException
        {
        }

        void drain()
        {
            int remaining = 100;
            while (!workers.isEmpty() || !owners.isEmpty())
            {
                assertTrue(remaining-- > 0, "Coordinator must not spin endlessly.");
                while (!workers.isEmpty())
                    workers.remove().run();
                while (!owners.isEmpty())
                    owners.remove().run();
            }
        }

        @Override
        public void close()
        {
            link.close();
            host.close();
        }
    }
}
