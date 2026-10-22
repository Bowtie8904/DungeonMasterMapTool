package dmmt.remote;

import dmmt.FxTestSupport;
import dmmt.ui.HandoutWindow;
import dmmt.ui.RecentHandouts;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHandoutLibraryTest
{
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception
    {
        FxTestSupport.startJavaFx();
    }

    @Test
    void remotePastesPersistOnHostAndRecentReuseRemovalAndReceiptsAreDurable() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            var board = f.artwork.publish(new HandoutWindow.Snapshot(
                    List.of(new WritableImage(200, 100)), 90, true, false, null)).board();
            var paste = f.request(RemoteHandouts.Operation.BOARD, null, board);
            var pending = f.host.exchange(new RemoteHandouts.Query(paste), () -> {
            });
            // Poll while the persistence worker has finished but its owner callback has not.
            f.workers.remove().run();
            f.owners.remove().run();
            f.workers.remove().run();
            f.state();
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, pending.join().receipt().status());
            assertEquals(1, f.recents.ids().size());
            var originalId = f.recents.ids().getFirst();
            assertEquals(f.recents.ids(), new RecentHandouts(directory.resolve("recents")).ids());
            var library = f.state().library();
            assertEquals(originalId, library.entries().getFirst().id());
            var thumbnail = f.artwork.loadThumbnail(library.entries().getFirst().thumbnail());
            assertTrue(thumbnail.getWidth() <= RemoteHandouts.THUMBNAIL_WIDTH);
            assertTrue(thumbnail.getHeight() <= RemoteHandouts.THUMBNAIL_HEIGHT);
            assertEquals(pending.join().receipt(), f.host.exchange(new RemoteHandouts.Query(paste), () -> {
            }).join().receipt());
            assertEquals(1, f.applied);

            var add = f.request(RemoteHandouts.Operation.ADD_RECENT, originalId, f.state().board());
            var added = f.host.exchange(new RemoteHandouts.Query(add), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, added.join().receipt().status());
            assertEquals(2, f.snapshot.get().images().size());
            var remove = f.request(RemoteHandouts.Operation.REMOVE_RECENT, originalId, f.state().board());
            var removed = f.host.exchange(new RemoteHandouts.Query(remove), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, removed.join().receipt().status());
            assertTrue(f.recents.ids().isEmpty());
            assertTrue(new RecentHandouts(directory.resolve("recents")).ids().isEmpty());
            assertEquals(2, f.snapshot.get().images().size(), "Removing a recent must not remove board images.");
            assertEquals(2, f.applied, "Recent removal must not reapply or hide the player board.");
            assertEquals(removed.join().receipt(), f.host.exchange(new RemoteHandouts.Query(remove), () -> {
            }).join().receipt());
        }
    }

    @Test
    void localRecentChangesIncludingAwayAndBackAndSettingTrimsRejectStaleCommands() throws Exception
    {
        try (var f = new Fixture())
        {
            var first = new WritableImage(20, 10);
            var second = new WritableImage(10, 20);
            String a = f.recents.add(first, 100), b = f.recents.add(second, 100);
            f.drain();
            f.state();
            f.drain();
            var stale = f.request(RemoteHandouts.Operation.REMOVE_RECENT, a, f.state().board());
            f.recents.moveToTop(a);
            f.recents.moveToTop(b);
            f.state();
            f.drain();
            assertEquals(RemotePlayerSession.Status.CONFLICT,
                         f.host.exchange(new RemoteHandouts.Query(stale), () -> {
                         }).join().receipt().status());
            var beforeTrim = f.request(RemoteHandouts.Operation.ADD_RECENT, a, f.state().board());
            f.max = 0;
            f.state();
            f.drain();
            assertTrue(f.state().library().entries().isEmpty());
            assertTrue(f.recents.ids().isEmpty());
            assertEquals(RemotePlayerSession.Status.CONFLICT,
                         f.host.exchange(new RemoteHandouts.Query(beforeTrim), () -> {
                         }).join().receipt().status());
        }
    }

    @Test
    void presentationChangesDoNotReorderRecentsAndDuplicatePastesStillTouchTheirEntry() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            var image = new WritableImage(20, 10);
            var board = f.artwork.publish(new HandoutWindow.Snapshot(List.of(image), 0, false, false, null)).board();
            var paste = f.host.exchange(new RemoteHandouts.Query(f.request(RemoteHandouts.Operation.BOARD, null, board)), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, paste.join().receipt().status());
            long version = f.recents.version();
            var rotated = new RemoteHandouts.Board(board.images(), 90, true, false, null);
            var rotation = f.host.exchange(new RemoteHandouts.Query(f.request(RemoteHandouts.Operation.BOARD, null, rotated)), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, rotation.join().receipt().status());
            assertEquals(version, f.recents.version());
            var repeated = new RemoteHandouts.Board(List.of(board.images().getFirst(), board.images().getFirst()), 90, true, false, null);
            var duplicate = f.host.exchange(new RemoteHandouts.Query(f.request(RemoteHandouts.Operation.BOARD, null, repeated)), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, duplicate.join().receipt().status());
            assertTrue(f.recents.version() > version);
            assertEquals(1, f.recents.ids().size());
        }
    }

    @Test
    void hostMutationAfterPersistenceAndRevocationBeforeApplyNeverOverwriteTheBoardOrReplayDiskWork() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            var board = f.artwork.publish(new HandoutWindow.Snapshot(List.of(new WritableImage(5, 5)), 0, false, false, null)).board();
            var request = f.request(RemoteHandouts.Operation.BOARD, null, board);
            var pending = f.host.exchange(new RemoteHandouts.Query(request), () -> {
            });
            f.workers.remove().run();
            f.owners.remove().run();
            f.workers.remove().run();
            String local = f.recents.add(new WritableImage(6, 6), 100);
            f.drain();
            assertEquals(RemotePlayerSession.Status.CONFLICT, pending.join().receipt().status());
            assertTrue(f.snapshot.get().images().isEmpty());
            assertEquals(local, f.recents.ids().getFirst());
            var lease = new java.util.concurrent.atomic.AtomicBoolean(true);
            var retry = f.request(RemoteHandouts.Operation.BOARD, null, board);
            var revoked = f.host.exchange(new RemoteHandouts.Query(retry), () -> {
                if (!lease.get())
                    throw new IllegalStateException("Revoked.");
            });
            f.workers.remove().run();
            f.owners.remove().run();
            f.workers.remove().run();
            long version = f.recents.version();
            lease.set(false);
            f.drain();
            assertEquals(RemotePlayerSession.Status.FAILED, revoked.join().receipt().status());
            assertTrue(f.snapshot.get().images().isEmpty());
            assertEquals(revoked.join().receipt(), f.host.exchange(new RemoteHandouts.Query(retry), () -> {
            }).join().receipt());
            assertEquals(version, f.recents.version());
        }
    }

    @Test
    void localMutationQueuedBeforeRemotePersistenceWinsAndDiskFailuresNeverReturnSuccess() throws Exception
    {
        try (var f = new Fixture())
        {
            f.drain();
            var board = f.artwork.publish(new HandoutWindow.Snapshot(List.of(new WritableImage(5, 5)), 0, false, false, null)).board();
            var request = f.request(RemoteHandouts.Operation.BOARD, null, board);
            var pending = f.host.exchange(new RemoteHandouts.Query(request), () -> {
            });
            f.workers.remove().run();
            f.owners.remove().run();
            f.recents.add(new WritableImage(6, 6), 100);
            f.drain();
            assertEquals(RemotePlayerSession.Status.CONFLICT, pending.join().receipt().status());
            assertTrue(f.snapshot.get().images().isEmpty());
            f.drain();
            var failed = f.request(RemoteHandouts.Operation.BOARD, null, board);
            Files.createDirectory(directory.resolve("recents").resolve("recent.txt.tmp"));
            var result = f.host.exchange(new RemoteHandouts.Query(failed), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.FAILED, result.join().receipt().status());
            assertTrue(f.snapshot.get().images().isEmpty());
            assertFalse(f.errors.isEmpty());
            assertEquals(result.join().receipt(), f.host.exchange(new RemoteHandouts.Query(failed), () -> {
            }).join().receipt());
        }
    }

    @Test
    void wireBoundsIdsThumbnailDimensionsAndLibrarySize() throws Exception
    {
        var f = new Fixture();
        try (f)
        {
            f.drain();
            assertThrows(IllegalArgumentException.class, () -> f.recents.file("..\\private"));
            var descriptor = f.artwork.publish(new HandoutWindow.Snapshot(
                    List.of(new WritableImage(200, 200)), 0, false, false, null)).board().images().getFirst();
            assertThrows(java.io.IOException.class, () -> f.artwork.loadThumbnail(descriptor));
            assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Recent("../private", descriptor, descriptor));
            var entry = new RemoteHandouts.Recent("0".repeat(32), descriptor, descriptor);
            assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Library(1, 1, List.of(entry, entry)));
            assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Library(1, 1001, List.of()));
            var request = f.request(RemoteHandouts.Operation.ADD_RECENT, entry.id(), f.state().board());
            assertEquals(request, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.HANDOUT_QUERY,
                                                                        new RemoteHandouts.Query(request)), RemoteHandouts.Query.class).request());
            assertEquals(f.state(), RemoteWire.payload(RemoteWire.message(RemoteWire.Type.HANDOUT_STATE,
                                                                          new RemoteHandouts.Update(f.state(), null)), RemoteHandouts.Update.class).state());
        }
    }

    private final class Fixture implements AutoCloseable
    {
        final ArrayDeque<Runnable> workers = new ArrayDeque<>(), owners = new ArrayDeque<>();
        final List<String> errors = new ArrayList<>();
        final RecentHandouts recents = new RecentHandouts(directory.resolve("recents"));
        final RemoteAssetStore assets = new RemoteAssetStore(directory.resolve("assets"), 1_048_576, 16_777_216, 1_048_576);
        final RemoteHandouts.Artwork artwork = new RemoteHandouts.Artwork(assets, directory.resolve("artwork"));
        final AtomicReference<HandoutWindow.Snapshot> snapshot = new AtomicReference<>(
                new HandoutWindow.Snapshot(List.of(), 0, false, false, null));
        int max = 100, applied;
        final RemoteHandoutSession host = new RemoteHandoutSession(snapshot::get, value -> {
            applied++;
            snapshot.set(value);
        }, artwork, workers::add, owners::add, errors::add);

        Fixture() throws Exception
        {
            host.configureLibrary(new RemoteHandoutLibrary(() -> recents, assets, directory.resolve("library")), () -> max);
        }

        RemoteHandouts.State state()
        {
            return host.exchange(new RemoteHandouts.Query(null), () -> {
            }).join().state();
        }

        RemoteHandouts.Request request(RemoteHandouts.Operation operation, String id, RemoteHandouts.Board board)
        {
            var state = state();
            return new RemoteHandouts.Request(state.sessionId(), UUID.randomUUID(), state.nextSequence(), state.revision(), board, operation, id);
        }

        void drain()
        {
            int remaining = 100;
            while (!workers.isEmpty() || !owners.isEmpty())
            {
                assertTrue(remaining-- > 0);
                while (!workers.isEmpty())
                    workers.remove().run();
                while (!owners.isEmpty())
                    owners.remove().run();
            }
        }

        @Override
        public void close()
        {
            host.close();
        }
    }
}
