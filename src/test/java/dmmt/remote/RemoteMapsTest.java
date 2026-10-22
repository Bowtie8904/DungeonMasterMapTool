package dmmt.remote;

import dmmt.service.JsonMappers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMapsTest
{
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void endpointCachesPollsButRescansExplicitRefreshAndRejectsInvalidatedConfirmations() throws Exception {
        var root = java.nio.file.Files.createDirectories(directory.resolve("library"));
        var projects = new dmmt.service.ProjectService();
        var scans = new java.util.concurrent.atomic.AtomicInteger();
        var library = new dmmt.service.MapLibraryService(root, projects) {
            @Override public Entry scanRemote() throws java.io.IOException {
                scans.incrementAndGet();
                return super.scanRemote();
            }
        };
        var assetStore = new RemoteAssetStore(directory.resolve("assets"), 1024 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024);
        var mapper = JsonMappers.create();
        var context = UUID.randomUUID();
        var captured = new java.util.concurrent.atomic.AtomicReference<RemoteMaps.Request>();
        try (var endpoint = new RemoteMaps(() -> new RemoteMapCatalog.Source(library, null, "New map", context),
                List::of, assetStore, Runnable::run, Runnable::run, request -> {
                    captured.set(request);
                    try {
                        RemoteMaps.mutate(library, request);
                        return java.util.concurrent.CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
                    } catch (Exception failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
                })) {
            var listing = mapper.treeToValue(endpoint.query("list", null).join(), RemoteMaps.Listing.class);
            endpoint.query("list", null).join();
            assertEquals(1, scans.get());
            endpoint.query("list", mapper.createObjectNode().put("refresh", true)).join();
            assertEquals(2, scans.get());
            var command = new RemoteMaps.Command(context, listing.revision(), List.of(), listing.nodes().getFirst().id(), "Adventure", null);
            endpoint.execute("createFolder", mapper.valueToTree(command)).join();
            assertTrue(java.nio.file.Files.isDirectory(root.resolve("Adventure")));
            assertEquals(root, captured.get().destination().path());
            endpoint.invalidate();
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> endpoint.execute("createFolder", mapper.valueToTree(command)).join());
            var refreshed = mapper.treeToValue(endpoint.query("list", null).join(), RemoteMaps.Listing.class);
            var folder = refreshed.nodes().stream().filter(node -> node.name().equals("Adventure")).findFirst().orElseThrow();
            endpoint.execute("rename", mapper.valueToTree(new RemoteMaps.Command(context, refreshed.revision(),
                    List.of(folder.id()), null, "Renamed", null))).join();
            assertTrue(java.nio.file.Files.isDirectory(root.resolve("Renamed")));
            assertFalse(java.nio.file.Files.exists(root.resolve("Adventure")));
            assertFalse(mapper.writeValueAsString(refreshed).contains(root.toString().replace("\\", "\\\\")));
        }
    }
    @Test void previewReturnsPublishedThumbnailAsset() throws Exception {
        var root = java.nio.file.Files.createDirectories(directory.resolve("library"));
        java.nio.file.Files.writeString(root.resolve("Weltmap.dmmap"), "{}");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        var library = new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()) {
            @Override public byte[] loadOrCreateThumbnail(java.nio.file.Path mapFile) { return png; }
        };
        var assetStore = new RemoteAssetStore(directory.resolve("assets"), 1024 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024);
        var mapper = JsonMappers.create();
        var context = UUID.randomUUID();
        try (var endpoint = new RemoteMaps(() -> new RemoteMapCatalog.Source(library, null, "New map", context),
                List::of, assetStore, Runnable::run, Runnable::run,
                request -> java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException()))) {
            var listing = mapper.treeToValue(endpoint.query("list", null).join(), RemoteMaps.Listing.class);
            var map = listing.nodes().stream().filter(node -> node.name().equals("Weltmap")).findFirst().orElseThrow();
            var preview = endpoint.query("preview", mapper.valueToTree(new RemoteMaps.Command(context,
                    listing.revision(), List.of(map.id()), null, null, null))).join();
            assertTrue(preview.isObject(), preview.toString());
        }
    }

    private static RemoteMaps.Node folder(UUID id, UUID parent)
    {
        return new RemoteMaps.Node(id, parent, "Library", RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1);
    }

    @Test void importPreflightMatchesOriginalFilenameAfterRenameAndRequiresExplicitDuplicateApproval() throws Exception {
        var root = java.nio.file.Files.createDirectories(directory.resolve("duplicate-library"));
        var projects = new dmmt.service.ProjectService();
        var library = new dmmt.service.MapLibraryService(root, projects);
        var input = java.nio.file.Files.writeString(directory.resolve("cave.dd2vtt"), "{\"pixels_per_grid\":100}");
        new dmmt.service.BatchImportService(library, projects, new dmmt.service.Dd2vttImportService())
                .importAll(List.of(input), root, null);
        library.rename(library.scan().children().getFirst(), "Renamed dungeon");
        var store = new RemoteAssetStore(directory.resolve("duplicate-assets"), 1024 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024);
        var context = UUID.randomUUID();
        var mapper = JsonMappers.create();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var endpoint = new RemoteMaps(() -> new RemoteMapCatalog.Source(library, null, "New map", context),
                List::of, store, Runnable::run, Runnable::run, request -> {
                    calls.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
                })) {
            var listing = mapper.treeToValue(endpoint.query("list", null).join(), RemoteMaps.Listing.class);
            var names = mapper.createObjectNode();
            names.putArray("names").add("CAVE.uvtt").add("hall.dd2vtt");
            var check = new RemoteMaps.Command(context, listing.revision(), List.of(), null, null, names);
            var report = endpoint.query("importPreflight", mapper.valueToTree(check)).join();
            assertTrue(report.path("duplicates").toString().contains("CAVE.uvtt"));
            var options = mapper.createObjectNode();
            options.set("preflight", report.path("preflight"));
            options.putArray("sources").addObject().put("name", "CAVE.uvtt");
            var importCommand = new RemoteMaps.Command(context, listing.revision(), List.of(),
                    listing.nodes().getFirst().id(), null, options);
            if (!"always".equals(report.path("behavior").asText())) {
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> endpoint.execute("import", mapper.valueToTree(importCommand)).join());
                assertEquals(0, calls.get());
            }
            report = endpoint.query("importPreflight", mapper.valueToTree(check)).join();
            options.set("preflight", report.path("preflight"));
            options.putArray("duplicateAccepted").add("CAVE.uvtt");
            var approved = new RemoteMaps.Command(context, listing.revision(), List.of(),
                    listing.nodes().getFirst().id(), null, options);
            if ("never".equals(report.path("behavior").asText()))
                assertThrows(java.util.concurrent.CompletionException.class, () -> endpoint.execute("import", mapper.valueToTree(approved)).join());
            else {
                endpoint.execute("import", mapper.valueToTree(approved)).join();
                assertEquals(1, calls.get());
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> endpoint.execute("import", mapper.valueToTree(approved)).join());
            }
        }
    }

    @Test
    void hierarchyRejectsOrphansDuplicateHandlesAndUnknownRecents()
    {
        UUID root = UUID.randomUUID(), map = UUID.randomUUID(), context = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Listing(context, 1,
                                                                                  List.of(folder(root, null), folder(map, UUID.randomUUID())), null, "New map", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Listing(context, 1,
                                                                                  List.of(folder(root, null), folder(root, root)), null, "New map", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Listing(context, 1,
                                                                                  List.of(folder(root, null)), null, "New map", List.of(map)));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Listing(context, 1,
                                                                                  List.of(folder(root, null)), root, "New map", List.of()));
    }

    @Test
    void metadataAndCommandRoundTripCarryOpaqueHandlesRatherThanFilesystemPaths() throws Exception
    {
        UUID context = UUID.randomUUID(), root = UUID.randomUUID(), map = UUID.randomUUID();
        var node = new RemoteMaps.Node(map, root, "Tower", RemoteMapCatalog.Kind.MULTILEVEL,
                                       List.of("Dungeon", "Night"), 3, 1234);
        var listing = new RemoteMaps.Listing(context, 8, List.of(folder(root, null), node), map, "Tower · Roof", List.of(map));
        var mapper = JsonMappers.create();
        var json = mapper.valueToTree(listing);
        assertEquals(listing, mapper.treeToValue(json, RemoteMaps.Listing.class));
        assertFalse(json.toString().contains("path"));
        var command = new RemoteMaps.Command(context, 8, List.of(map), root, "Tower copy", null);
        assertEquals(command, mapper.treeToValue(mapper.valueToTree(command), RemoteMaps.Command.class));
        assertEquals(command, new RemoteMaps.Command(context, 8, List.of(map), root, "Tower copy",
                com.fasterxml.jackson.databind.node.NullNode.getInstance()));
    }

    @Test
    void confirmationCommandRequiresRevisionAndUniqueSelection()
    {
        UUID context = UUID.randomUUID(), id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Command(context, 0, List.of(id), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Command(context, 1, List.of(id, id), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaps.Node(id, null, "Bad\nname",
                                                                               RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1));
    }

    @Test void queuedDialogOptionsAreImmutable() {
        var options = JsonMappers.create().createObjectNode().put("id", "original");
        var command = new RemoteMaps.Command(UUID.randomUUID(), 1, List.of(UUID.randomUUID()), null, null, options);
        options.put("id", "changed-after-confirmation");
        assertEquals("original", command.options().path("id").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) command.options()).put("id", "changed-through-accessor");
        assertEquals("original", command.options().path("id").asText());
    }
}
