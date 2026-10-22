package dmmt.remote;

import dmmt.service.MapLibraryService;
import dmmt.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMapCatalogTest {
    @TempDir Path directory;

    @Test
    void browsingUsesOpaqueStableSessionIdentitiesWithoutReadingOrWritingMapContents() throws Exception {
        Path root = Files.createDirectory(directory.resolve("host-library"));
        Path folder = Files.createDirectory(root.resolve("Adventure"));
        Path packaged = Files.createDirectory(folder.resolve("Tower"));
        Path multi = Files.writeString(packaged.resolve("Tower.dmlevels"), "Not parsed while browsing");
        Path loose = Files.writeString(root.resolve("Loose.dmmap"), "Not parsed while browsing");
        Files.createDirectory(root.resolve(".hidden"));
        Path ignored = Files.createDirectory(root.resolve("assets"));
        Files.writeString(ignored.resolve("hidden.png"), "Not a map");
        var library = new MapLibraryService(root, new ProjectService());
        var source = new RemoteMapCatalog.Source(library, multi, "Tower", UUID.randomUUID());
        var workers = new ArrayDeque<Runnable>();
        var owners = new ArrayDeque<Runnable>();
        try (var catalog = new RemoteMapCatalog(() -> source, workers::add, owners::add)) {
            assertTrue(workers.isEmpty(), "Construction must not scan a library.");
            var first = catalog.query();
            var coalesced = catalog.query();
            assertEquals(1, workers.size());
            drain(workers, owners);
            var listing = first.join();
            assertNull(listing.error());
            assertEquals(listing, coalesced.join());
            assertEquals(List.of("Library", "Adventure", "Tower", "Loose"), listing.nodes().stream()
                    .map(RemoteMapCatalog.Node::name).toList());
            assertEquals(RemoteMapCatalog.Kind.MULTILEVEL, listing.nodes().get(2).kind());
            assertEquals(listing.nodes().get(2).id(), listing.current());
            var payload = RemoteWire.message(RemoteWire.Type.MAP_CATALOG, listing).payload().toString();
            assertFalse(payload.contains(directory.toString().replace("\\", "\\\\")));
            assertFalse(payload.contains("Not parsed"));
            var refreshed = catalog.query();
            drain(workers, owners);
            assertEquals(listing, refreshed.join(), "Unchanged paths retain handles for this session.");
            assertEquals("Not parsed while browsing", Files.readString(multi));
            assertEquals("Not parsed while browsing", Files.readString(loose));
            try (var next = new RemoteMapCatalog(() -> source, Runnable::run, Runnable::run)) {
                assertNotEquals(listing.nodes().getFirst().id(), next.query().join().nodes().getFirst().id());
            }
        }
    }

    @Test
    void stoppingBeforeQueuedWorkSkipsAllFilesystemAccess() throws Exception {
        var scans = new AtomicInteger();
        var library = new MapLibraryService(directory, new ProjectService()) {
            @Override public Entry scanRemote() {
                scans.incrementAndGet();
                throw new AssertionError("Closed queued catalog must not scan.");
            }
        };
        var workers = new ArrayDeque<Runnable>();
        var owners = new ArrayDeque<Runnable>();
        var catalog = new RemoteMapCatalog(
                () -> new RemoteMapCatalog.Source(library, null, "Unsaved new map", UUID.randomUUID()),
                workers::add, owners::add);
        var request = catalog.query();
        catalog.close();
        assertTrue(request.isCompletedExceptionally());
        drain(workers, owners);
        assertEquals(0, scans.get());
    }

    @Test
    void changedLibraryAndMissingDirectoryAreExplicitErrorsNotEmptySuccess() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        var source = new java.util.concurrent.atomic.AtomicReference<>(new RemoteMapCatalog.Source(
                new MapLibraryService(first, new ProjectService()), null, "New map", UUID.randomUUID()));
        var workers = new ArrayDeque<Runnable>();
        var owners = new ArrayDeque<Runnable>();
        try (var catalog = new RemoteMapCatalog(source::get, workers::add, owners::add)) {
            var request = catalog.query();
            source.set(new RemoteMapCatalog.Source(new MapLibraryService(directory.resolve("missing"), new ProjectService()),
                    null, "New map", UUID.randomUUID()));
            drain(workers, owners);
            assertTrue(request.join().error().contains("changed"));
            var missing = catalog.query();
            drain(workers, owners);
            assertNotNull(missing.join().error());
            assertTrue(missing.join().nodes().isEmpty());
            assertFalse(missing.join().error().contains(directory.toString()), "Failure responses must not disclose paths.");
            assertFalse(Files.exists(directory.resolve("missing")), "Browsing must not create a configured library.");
        }
    }

    @Test
    void boundedScannerAcceptsExactItemAndDepthLimitsAndRejectsOneBeyond() throws Exception {
        Path root = Files.createDirectory(directory.resolve("bounded"));
        var library = new MapLibraryService(root, new ProjectService());
        for (int i = 0; i < 1023; i++) Files.writeString(root.resolve(i + ".dmmap"), "{}");
        assertEquals(1023, library.scanRemote().children().size());
        Files.writeString(root.resolve("extra.dmmap"), "{}");
        assertTrue(assertThrows(java.io.IOException.class, library::scanRemote).getMessage().contains("1024"));
        Path deep = Files.createDirectory(directory.resolve("deep"));
        Path leaf = deep;
        for (int i = 0; i < 24; i++) leaf = Files.createDirectory(leaf.resolve("folder"));
        var nested = new MapLibraryService(deep, new ProjectService());
        assertNotNull(nested.scanRemote());
        Files.createDirectory(leaf.resolve("too-deep"));
        assertTrue(assertThrows(java.io.IOException.class, nested::scanRemote).getMessage().contains("24-folder"));
    }

    @Test
    void largestAcceptedCatalogFitsTheActualControlWireEnvelope() throws Exception {
        UUID root = UUID.randomUUID();
        var nodes = new java.util.ArrayList<RemoteMapCatalog.Node>();
        nodes.add(new RemoteMapCatalog.Node(root, null, "Root", RemoteMapCatalog.Kind.FOLDER));
        for (int i = 1; i < 1024; i++) {
            nodes.add(new RemoteMapCatalog.Node(UUID.randomUUID(), root, "\\\"".repeat(32), RemoteMapCatalog.Kind.MAP));
        }
        var listing = new RemoteMapCatalog.Listing(nodes, null, "Unsaved new map", UUID.randomUUID(), null);
        var message = RemoteWire.message(RemoteWire.Type.MAP_CATALOG, listing);
        byte[] bytes = RemoteWire.encode(message, RemoteWire.MAX_MESSAGE_BYTES);
        assertTrue(bytes.length <= RemoteWire.MAX_MESSAGE_BYTES);
        assertEquals(listing, RemoteWire.payload(message, RemoteMapCatalog.Listing.class));
    }

    @Test
    void listingRejectsCyclesDuplicateIdsUnknownCurrentAndExcessMetadata() {
        UUID root = UUID.randomUUID(), child = UUID.randomUUID(), context = UUID.randomUUID();
        var top = new RemoteMapCatalog.Node(root, null, "Library", RemoteMapCatalog.Kind.FOLDER);
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Listing(List.of(top,
                new RemoteMapCatalog.Node(root, root, "Duplicate", RemoteMapCatalog.Kind.MAP)), null, "Map", context, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Listing(List.of(top,
                new RemoteMapCatalog.Node(child, child, "Cycle", RemoteMapCatalog.Kind.FOLDER)), null, "Map", context, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Listing(List.of(top), child, "Map", context, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Listing(List.of(), null, "Map", context, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Node(child, root, "x".repeat(257), RemoteMapCatalog.Kind.MAP));
        var nodes = new java.util.ArrayList<RemoteMapCatalog.Node>();
        nodes.add(top);
        for (int i = 0; i < 256; i++) nodes.add(new RemoteMapCatalog.Node(UUID.randomUUID(), root, "x".repeat(256), RemoteMapCatalog.Kind.MAP));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMapCatalog.Listing(nodes, null, "Map", context, null));
    }

    private static void drain(ArrayDeque<Runnable> workers, ArrayDeque<Runnable> owners) throws Exception {
        while (!workers.isEmpty()) {
            Thread thread = Thread.ofPlatform().start(workers.removeFirst());
            thread.join();
        }
        while (!owners.isEmpty()) owners.removeFirst().run();
    }
}
