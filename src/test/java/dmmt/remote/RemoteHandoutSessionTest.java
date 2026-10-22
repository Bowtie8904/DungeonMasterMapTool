package dmmt.remote;

import dmmt.FxTestSupport;
import dmmt.ui.HandoutWindow;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHandoutSessionTest
{
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception
    {
        FxTestSupport.startJavaFx();
    }

    @Test
    void clipboardFilesAreDecodedWithinPixelLimitsBeforeBeingPublishedAsPng() throws Exception
    {
        var store = store("clipboard");
        var artwork = new RemoteHandouts.Artwork(store, directory.resolve("clipboard-cache"));
        var base = new HandoutWindow.Snapshot(List.of(), 90, true, false, null);
        Path jpeg = directory.resolve("image.jpg");
        var bitmap = new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(bitmap, "jpg", jpeg.toFile());
        var snapshot = artwork.appendFiles(base, List.of(jpeg));
        assertEquals(90, snapshot.rotation());
        assertTrue(snapshot.mirrored());
        assertEquals(20, snapshot.images().getFirst().getWidth());
        assertEquals(1, artwork.publish(snapshot).board().images().size());
        assertThrows(java.io.IOException.class, () -> artwork.appendFiles(base,
                                                                          List.of(Files.writeString(directory.resolve("invalid.png"), "invalid"))));
        assertThrows(java.io.IOException.class, () -> artwork.appendFiles(base, java.util.Collections.nCopies(65, jpeg)));

        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bitmap, "png", png);
        byte[] oversized = png.toByteArray();
        java.nio.ByteBuffer.wrap(oversized, 16, 4).putInt(65536);
        var crc = new java.util.zip.CRC32();
        crc.update(oversized, 12, 17);
        java.nio.ByteBuffer.wrap(oversized, 29, 4).putInt((int)crc.getValue());
        Path huge = Files.write(directory.resolve("huge.png"), oversized);
        assertThrows(java.io.IOException.class, () -> artwork.appendFiles(base, List.of(huge)));
        var descriptor = store.publishRequiredAsset(huge);
        assertThrows(java.io.IOException.class, () -> artwork.load(
                new RemoteHandouts.Board(List.of(descriptor), 0, false, true, null)));
    }

    @Test
    void publishedPngsRoundTripPixelsPresentationAndSubsetWithoutPaths() throws Exception
    {
        var store = store("assets");
        var publisher = new RemoteHandouts.Artwork(store, directory.resolve("encoded"));
        var first = new WritableImage(8, 6);
        first.getPixelWriter().setArgb(3, 2, 0x7fabcdef);
        var second = new WritableImage(2, 3);
        var source = new HandoutWindow.Snapshot(List.of(first, second), 270, true, true, List.of(1));
        var published = publisher.publish(source);
        assertEquals(2, published.board().images().size());
        assertTrue(store.contains(published.board().images().getFirst().sha256()));
        var decoded = new RemoteHandouts.Artwork(store, directory.resolve("decoded")).load(published.board()).snapshot();
        assertEquals(0x7fabcdef, decoded.images().getFirst().getPixelReader().getArgb(3, 2));
        assertEquals(270, decoded.rotation());
        assertTrue(decoded.mirrored());
        assertTrue(decoded.shown());
        assertEquals(List.of(1), decoded.showOnly());
        assertEquals(published.board(), publisher.publish(decoded).board());
    }

    @Test
    void exactReceiptRecoveryNeverRepeatsBoardApplicationAndHostEditWins() throws Exception
    {
        var f = new Fixture();
        try (var authority = f.authority())
        {
            f.drain();
            var baseline = state(authority);
            var request = request(baseline, f.imageBoard());
            var pending = authority.exchange(new RemoteHandouts.Query(request), () -> {
            });
            assertFalse(pending.isDone());
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, pending.join().receipt().status());
            assertEquals(1, f.applied);
            assertEquals(pending.join().receipt(),
                         authority.exchange(new RemoteHandouts.Query(request), () -> {
                         }).join().receipt());
            assertEquals(1, f.applied);
            var stale = request(state(authority), RemoteHandouts.Board.empty());
            f.board.set(new HandoutWindow.Snapshot(f.board.get().images(), 90, false, false, null));
            authority.changed();
            f.drain();
            assertEquals(RemotePlayerSession.Status.CONFLICT,
                         authority.exchange(new RemoteHandouts.Query(stale), () -> {
                         }).join().receipt().status());
            assertEquals(90, state(authority).board().rotation());
        }
    }

    @Test
    void authorizationAndHostRevisionAreRecheckedAfterAsynchronousDecode() throws Exception
    {
        var f = new Fixture();
        try (var authority = f.authority())
        {
            f.drain();
            var request = request(state(authority), f.imageBoard());
            var waiting = authority.exchange(new RemoteHandouts.Query(request), () -> {
            });
            f.board.set(new HandoutWindow.Snapshot(List.of(), 180, true, false, null));
            authority.changed();
            f.drain();
            assertEquals(RemotePlayerSession.Status.CONFLICT, waiting.join().receipt().status());
            assertEquals(0, f.applied);
            var authorized = new java.util.concurrent.atomic.AtomicBoolean(true);
            var next = request(state(authority), f.imageBoard());
            var revoked = authority.exchange(new RemoteHandouts.Query(next), () -> {
                if (!authorized.get())
                    throw new IllegalStateException("Lease revoked.");
            });
            authorized.set(false);
            f.drain();
            assertTrue(revoked.isCompletedExceptionally());
            assertEquals(0, f.applied);
            assertEquals(next.sequence(), state(authority).nextSequence());
        }
    }

    @Test
    void malformedImagesAndOversizedDecodedDimensionsFailWithoutChangingTheBoard() throws Exception
    {
        var f = new Fixture();
        try (var authority = f.authority())
        {
            f.drain();
            var bytes = Files.writeString(directory.resolve("not-a-png.png"), "not a PNG");
            var bad = new RemoteHandouts.Board(List.of(f.store.publishRequiredAsset(bytes)), 0, false, true, null);
            var request = request(state(authority), bad);
            var result = authority.exchange(new RemoteHandouts.Query(request), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.FAILED, result.join().receipt().status());
            assertFalse(f.errors.isEmpty());
            assertTrue(f.board.get().images().isEmpty());
            assertEquals(result.join().receipt(), authority.exchange(new RemoteHandouts.Query(request), () -> {
            }).join().receipt());
            assertThrows(java.io.IOException.class, () -> new RemoteHandouts.Artwork(f.store, directory.resolve("pixels"))
                    .publish(new HandoutWindow.Snapshot(List.of(new WritableImage(16385, 1)), 0, false, false, null)));
        }
    }

    @Test
    void busyDoesNotConsumeSequenceAndAlteredOrEvictedReceiptsNeverReplay() throws Exception
    {
        var f = new Fixture();
        try (var authority = f.authority())
        {
            var initial = state(authority);
            var waiting = request(initial, RemoteHandouts.Board.empty());
            assertEquals(RemotePlayerSession.Status.BUSY,
                         authority.exchange(new RemoteHandouts.Query(waiting), () -> {
                         }).join().receipt().status());
            assertEquals(1, state(authority).nextSequence());
            f.drain();
            var request = request(state(authority), RemoteHandouts.Board.empty());
            var result = authority.exchange(new RemoteHandouts.Query(request), () -> {
            });
            f.drain();
            assertEquals(RemotePlayerSession.Status.ACCEPTED, result.join().receipt().status());
            var altered = new RemoteHandouts.Request(request.sessionId(), request.commandId(), request.sequence(),
                                                     request.expectedRevision(), new RemoteHandouts.Board(List.of(), 90, false, false, null));
            assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED,
                         authority.exchange(new RemoteHandouts.Query(altered), () -> {
                         }).join().receipt().status());
            for (int i = 0; i < 256; i++)
            {
                var receipt = authority.exchange(new RemoteHandouts.Query(request(state(authority),
                                                                                  RemoteHandouts.Board.empty())), () -> {
                });
                f.drain();
                assertEquals(RemotePlayerSession.Status.ACCEPTED, receipt.join().receipt().status());
            }
            assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED,
                         authority.exchange(new RemoteHandouts.Query(request), () -> {
                         }).join().receipt().status());
            assertEquals(257, f.applied);
        }
    }

    @Test
    void wireRejectsInvalidSubsetRotationUnknownFieldsAndNullPrimitiveFlags() throws Exception
    {
        var board = new RemoteHandouts.Board(List.of(), 0, false, false, null);
        var request = new RemoteHandouts.Request(UUID.randomUUID(), UUID.randomUUID(), 1, 0, board);
        var message = RemoteWire.message(RemoteWire.Type.HANDOUT_QUERY, new RemoteHandouts.Query(request));
        assertEquals(request, RemoteWire.payload(message, RemoteHandouts.Query.class).request());
        assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Board(List.of(), 1, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Board(List.of(), 0, false, false, List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new RemoteHandouts.Board(List.of(), 0, false, true, null));
        var payload = message.payload().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.get("request").get("board")).putNull("shown");
        assertThrows(Exception.class, () -> RemoteWire.payload(
                new RemoteWire.Message(message.id(), message.type(), payload), RemoteHandouts.Query.class));
        var unknown = message.payload();
        ((com.fasterxml.jackson.databind.node.ObjectNode)unknown).put("path", "private.png");
        assertThrows(Exception.class, () -> RemoteWire.payload(
                new RemoteWire.Message(message.id(), message.type(), unknown), RemoteHandouts.Query.class));
    }

    private RemoteAssetStore store(String name) throws Exception
    {
        return new RemoteAssetStore(directory.resolve(name), 1_048_576, 16_777_216, 1_048_576);
    }

    private static RemoteHandouts.State state(RemoteHandoutSession authority)
    {
        return authority.exchange(new RemoteHandouts.Query(null), () -> {
        }).join().state();
    }

    private static RemoteHandouts.Request request(RemoteHandouts.State state, RemoteHandouts.Board board)
    {
        return new RemoteHandouts.Request(state.sessionId(), UUID.randomUUID(), state.nextSequence(), state.revision(), board);
    }

    private final class Fixture
    {
        final RemoteAssetStore store = store("store-" + UUID.randomUUID());
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        final ArrayDeque<Runnable> owners = new ArrayDeque<>();
        final List<String> errors = new ArrayList<>();
        final AtomicReference<HandoutWindow.Snapshot> board = new AtomicReference<>(
                new HandoutWindow.Snapshot(List.of(), 0, false, false, null));
        int applied;

        Fixture() throws Exception
        {
        }

        RemoteHandoutSession authority()
        {
            return new RemoteHandoutSession(board::get, value -> {
                applied++;
                board.set(value);
            },
                                            new RemoteHandouts.Artwork(store, directory.resolve("host-cache")), workers::add, owners::add, errors::add);
        }

        RemoteHandouts.Board imageBoard() throws Exception
        {
            return new RemoteHandouts.Artwork(store, directory.resolve("publish")).publish(
                    new HandoutWindow.Snapshot(List.of(new WritableImage(8, 6)), 180, true, true, List.of(0))).board();
        }

        void drain()
        {
            while (!workers.isEmpty() || !owners.isEmpty())
            {
                while (!workers.isEmpty())
                    workers.remove().run();
                while (!owners.isEmpty())
                    owners.remove().run();
            }
        }
    }
}
