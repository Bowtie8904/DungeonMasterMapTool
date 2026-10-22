package dmmt.ui;

import dmmt.DungeonMasterMapToolApplication;
import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.model.MultiLevelManifest;
import dmmt.service.AppSettings;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MultiLevelService;
import dmmt.service.ProjectService;
import dmmt.service.RecentMaps;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.control.TextField;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MapBrowserTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void remoteCatalogReusesTreeSearchWithoutScanningLocalDiskAndRestoresLocalBrowser() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var pending = new CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>();
        var listing = remoteListing();
        var before = new java.util.concurrent.atomic.AtomicReference<String>();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.select(0, 1);
            TextField search = (TextField) f.browser.lookup(".search-field");
            search.setText("Ordinary");
            f.select(0);
            before.set(readSettings(dir.resolve("settings.ini")));
            f.library.forbidLocalScan = true;
            f.browser.beginRemote(() -> pending);
            f.browser.remoteContext(listing.context());
            f.browser.refresh();
            assertNull(f.tree.getRoot());
            pending.complete(listing);
        });
        onFx(() -> {
            var f = fixture.get();
            assertEquals(List.of("Host folder", "Host meadow"), f.tree.getRoot().getChildren().stream()
                    .map(item -> item.getValue().name()).toList());
            assertFalse(f.tree.getRoot().getValue().path().startsWith(f.library.getRoot()));
            TextField search = (TextField) f.browser.lookup(".search-field");
            search.setText("tower");
            assertEquals("Host tower", f.tree.getRoot().getChildren().getFirst().getChildren().getFirst().getValue().name());
            f.tree.getSelectionModel().select(f.tree.getRoot().getChildren().getFirst().getChildren().getFirst());
            f.tree.fireEvent(key(KeyCode.ENTER));
            f.tree.fireEvent(key(KeyCode.F2));
            f.tree.fireEvent(key(KeyCode.DELETE));
            assertNull(f.host.opened);
            assertEquals(0, f.host.operations);
            f.browser.invalidateThumbnails();
            f.browser.updateCurrentMap();
            assertEquals(before.get(), readSettings(dir.resolve("settings.ini")));
            assertTrue(f.browser.lookup("#map-catalog-status") instanceof Label);
            f.library.forbidLocalScan = false;
            f.browser.endRemote();
            assertEquals("Ordinary", ((TextField) f.browser.lookup(".search-field")).getText());
            assertEquals("Ordinary", f.tree.getSelectionModel().getSelectedItem().getValue().name());
            assertEquals("Library", f.tree.getRoot().getValue().name());
        });
    }

    @Test
    void failedHostCatalogCanRetryAndLateCompletionCannotReplaceRestoredTree() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var late = new CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        var queries = new java.util.concurrent.atomic.AtomicInteger();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.beginRemote(() -> queries.incrementAndGet() == 1
                    ? CompletableFuture.failedFuture(new IOException("Disconnected")) : late);
        });
        onFx(() -> {
            var f = fixture.get();
            assertTrue(((Label) f.browser.lookup("#map-catalog-status")).getText().contains("Could not read"));
            f.browser.refresh();
            assertEquals(2, queries.get());
            f.browser.endRemote();
            late.complete(remoteListing());
        });
        onFx(() -> {
            assertEquals("Ordinary", fixture.get().tree.getRoot().getChildren().getFirst().getValue().name());
            assertEquals("", ((Label) fixture.get().browser.lookup("#map-catalog-status")).getText());
        });
    }

    private static String readSettings(Path file) {
        try {
            return java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : "";
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void unchangedRemoteListingPreservesTreeMultiSelectionScrollAndStatus() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var first = remoteListing();
        var nodes = new ArrayList<>(first.nodes());
        for (int i = 0; i < 100; i++)
            nodes.add(new dmmt.remote.RemoteMapCatalog.Node(java.util.UUID.randomUUID(), nodes.getFirst().id(),
                    "Extra map " + i, dmmt.remote.RemoteMapCatalog.Kind.MAP));
        var listing = new dmmt.remote.RemoteMapCatalog.Listing(nodes, first.current(), first.currentLabel(), first.context(), null);
        var next = new CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var stage = new java.util.concurrent.atomic.AtomicReference<Stage>();
        var root = new java.util.concurrent.atomic.AtomicReference<TreeItem<Entry>>();
        var selected = new ArrayList<TreeItem<Entry>>();
        var scrollbar = new java.util.concurrent.atomic.AtomicReference<ScrollBar>();
        var scrollValue = new java.util.concurrent.atomic.AtomicReference<Double>();
        var statuses = new ArrayList<String>();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.beginRemote(() -> requests.incrementAndGet() == 1
                    ? CompletableFuture.completedFuture(listing) : next);
        });
        onFx(() -> {
            var f = fixture.get();
            Stage window = new Stage();
            stage.set(window);
            window.setScene(new Scene(f.browser, 360, 560));
            window.show();
            f.browser.applyCss();
            f.browser.layout();
            root.set(f.tree.getRoot());
            root.get().getChildren().getFirst().setExpanded(true);
            f.select(4, 5);
            selected.addAll(f.tree.getSelectionModel().getSelectedItems());
            f.tree.scrollTo(60);
            f.browser.layout();
            ScrollBar bar = f.tree.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                    .map(ScrollBar.class::cast).filter(b -> b.getOrientation() == Orientation.VERTICAL).findFirst().orElseThrow();
            scrollbar.set(bar);
            bar.setValue(bar.getMax() * .6);
            scrollValue.set(bar.getValue());
            ((Label) f.browser.lookup("#map-catalog-status")).textProperty()
                    .addListener((obs, before, after) -> statuses.add(after));
            f.browser.refresh();
            assertTrue(statuses.isEmpty(), "A background/unchanged refresh must not flash Loading.");
            next.complete(listing);
        });
        onFx(() -> {
            try {
                var f = fixture.get();
                f.browser.layout();
                assertSame(root.get(), f.tree.getRoot());
                assertEquals(selected, f.tree.getSelectionModel().getSelectedItems());
                assertTrue(root.get().getChildren().getFirst().isExpanded());
                assertEquals(scrollValue.get(), scrollbar.get().getValue(), .0001);
                assertTrue(statuses.isEmpty());
            } finally {
                stage.get().close();
                fixture.get().browser.endRemote();
            }
        });
    }

    @Test
    void failedRemotePreviewIsReportedWithOriginalCauseThrottledAndRetryable() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        var failure = new IOException("Preview transfer failed");
        var failures = new ArrayList<Throwable>();
        connection.preview = () -> CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(failure));
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteErrorHandler(failures::add);
            f.browser.beginRemote(connection);
        });
        onFx(() -> requestRemoteThumbnail(fixture.get()));
        onFx(() -> {
            assertEquals(List.of(failure), failures);
            for (int i = 0; i < 20; i++) requestRemoteThumbnail(fixture.get());
            assertEquals(1, connection.previewRequests);
            assertFalse(browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
            connection.preview = () -> CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            requestRemoteThumbnail(fixture.get());
            assertEquals(2, connection.previewRequests);
        });
        awaitFx(() -> browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
        onFx(() -> {
            requestRemoteThumbnail(fixture.get());
            assertEquals(2, connection.previewRequests, "A successful absent preview may be cached.");
            assertEquals(List.of(failure), failures);
            fixture.get().browser.endRemote();
        });
    }

    @Test
    void failedRemoteCatalogPollingIsDeduplicatedAndExplicitRefreshRetries() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        var failure = new IOException("Host library unavailable");
        var failures = new ArrayList<Throwable>();
        connection.catalogFailure = failure;
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteErrorHandler(failures::add);
            f.browser.beginRemote(connection);
        });
        onFx(() -> {
            assertEquals(List.of(failure), failures);
            fixture.get().browser.tickRemote();
        });
        onFx(() -> {
            assertEquals(List.of(failure), failures, "Repeated background polling failures must not flood the error handler.");
            assertTrue(((Label) fixture.get().browser.lookup("#map-catalog-status")).getText().contains("Could not read"));
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            assertEquals(List.of(failure, failure), failures);
            connection.catalogFailure = null;
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            assertNotNull(fixture.get().tree.getRoot());
            fixture.get().browser.endRemote();
        });
    }

    @Test
    void changedRemoteMetadataInvalidatesPreviewAndRejectsStaleCompletion() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        var stale = new CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
        var current = new CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
        var failures = new ArrayList<Throwable>();
        connection.preview = () -> connection.previewRequests == 1 ? stale : current;
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteErrorHandler(failures::add);
            f.browser.beginRemote(connection);
        });
        onFx(() -> {
            requestRemoteThumbnail(fixture.get());
            var old = connection.listing;
            var map = old.nodes().get(1);
            connection.listing = new dmmt.remote.RemoteMaps.Listing(old.context(), old.revision() + 1,
                    List.of(old.nodes().getFirst(), new dmmt.remote.RemoteMaps.Node(map.id(), map.parent(), "Changed map",
                            map.kind(), List.of("New tag"), 0, map.stamp() + 1)), old.current(), "Changed map", old.recent());
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            assertEquals("Changed map", fixture.get().entry(0).name());
            requestRemoteThumbnail(fixture.get());
            assertEquals(2, connection.previewRequests);
            stale.completeExceptionally(new IOException("Obsolete preview"));
        });
        onFx(() -> {
            assertTrue(failures.isEmpty(), "Obsolete preview failures must not reach the active browser.");
            assertFalse(browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
            requestRemoteThumbnail(fixture.get());
            assertEquals(2, connection.previewRequests, "A stale callback must not clear the replacement's loading guard.");
            current.complete(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        });
        awaitFx(() -> browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
        onFx(() -> fixture.get().browser.endRemote());
    }

    @Test
    void asynchronousRemoteOperationFailureUsesErrorHookWithoutLosingCause() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        var failure = new IOException("Host operation rejected");
        var failures = new ArrayList<Throwable>();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteErrorHandler(error -> {
                assertTrue(Platform.isFxApplicationThread());
                failures.add(error);
            });
            f.browser.beginRemote(connection);
        });
        onFx(() -> fixture.get().browser.remoteWorkflow("open", List.of(fixture.get().entry(0)), null, null, null));
        connection.operation.completeExceptionally(new java.util.concurrent.CompletionException(failure));
        onFx(() -> {
            assertEquals(List.of(failure), failures);
            assertTrue(((Label) fixture.get().browser.lookup("#map-catalog-status")).getText().contains("operation failed"));
            fixture.get().browser.endRemote();
        });
    }

    @Test
    void remotePreviewLoadsImageAndRevisionOnlyRefreshKeepsCacheButChangedStampInvalidatesIt() throws Exception {
        Path png = dir.resolve("preview.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                "png", png.toFile());
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        connection.download = png;
        var descriptor = new dmmt.remote.RemoteAssetStore.AssetDescriptor("0".repeat(64), java.nio.file.Files.size(png));
        connection.preview = () -> CompletableFuture.completedFuture(connection.mapper.valueToTree(descriptor));
        var root = new java.util.concurrent.atomic.AtomicReference<TreeItem<Entry>>();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteErrorHandler(failure -> fail("Unexpected preview failure", failure));
            f.browser.beginRemote(connection);
        });
        onFx(() -> requestRemoteThumbnail(fixture.get()));
        awaitFx(() -> browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
        onFx(() -> {
            assertEquals(2, requestRemoteThumbnail(fixture.get()).getWidth());
            root.set(fixture.get().tree.getRoot());
            var old = connection.listing;
            connection.listing = new dmmt.remote.RemoteMaps.Listing(old.context(), old.revision() + 1, old.nodes(),
                    old.current(), old.currentLabel(), old.recent());
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            assertSame(root.get(), fixture.get().tree.getRoot());
            assertNotNull(requestRemoteThumbnail(fixture.get()));
            assertEquals(1, connection.previewRequests);
            var old = connection.listing;
            var map = old.nodes().get(1);
            connection.listing = new dmmt.remote.RemoteMaps.Listing(old.context(), old.revision() + 1,
                    List.of(old.nodes().getFirst(), new dmmt.remote.RemoteMaps.Node(map.id(), map.parent(), map.name(),
                            map.kind(), map.tags(), map.levels(), map.stamp() + 1)),
                    old.current(), old.currentLabel(), old.recent());
            fixture.get().browser.refresh();
        });
        onFx(() -> {
            assertNull(requestRemoteThumbnail(fixture.get()));
            assertEquals(2, connection.previewRequests);
        });
        awaitFx(() -> browserMap(fixture.get().browser, "thumbnails").containsKey(fixture.get().entry(0).mapFile()));
        onFx(() -> {
            assertEquals(3, requestRemoteThumbnail(fixture.get()).getHeight());
            fixture.get().browser.endRemote();
        });
    }

    private static Map<?, ?> browserMap(MapBrowser browser, String name) {
        try {
            Field field = MapBrowser.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<?, ?>) field.get(browser);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static javafx.scene.image.Image requestRemoteThumbnail(Fixture fixture) {
        try {
            Method method = MapBrowser.class.getDeclaredMethod("remoteThumbnail", Path.class);
            method.setAccessible(true);
            return (javafx.scene.image.Image) method.invoke(fixture.browser, fixture.entry(0).mapFile());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitFx(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            onFx(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Remote thumbnail did not finish.");
    }

    private static final class BrowserConnection implements dmmt.remote.RemoteOperations.Connection {
        final com.fasterxml.jackson.databind.ObjectMapper mapper = dmmt.service.JsonMappers.create();
        dmmt.remote.RemoteMaps.Listing listing;
        java.util.function.Supplier<CompletableFuture<com.fasterxml.jackson.databind.JsonNode>> preview;
        final CompletableFuture<com.fasterxml.jackson.databind.JsonNode> operation = new CompletableFuture<>();
        Path download;
        Throwable catalogFailure;
        int previewRequests;
        String commandAction;
        com.fasterxml.jackson.databind.JsonNode commandPayload;

        BrowserConnection() {
            var root = java.util.UUID.randomUUID();
            var map = java.util.UUID.randomUUID();
            listing = new dmmt.remote.RemoteMaps.Listing(java.util.UUID.randomUUID(), 1, List.of(
                    new dmmt.remote.RemoteMaps.Node(root, null, "Host library", dmmt.remote.RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1),
                    new dmmt.remote.RemoteMaps.Node(map, root, "Host map", dmmt.remote.RemoteMapCatalog.Kind.MAP, List.of(), 0, 1)),
                    map, "Host map", List.of(map));
            preview = () -> CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        }

        public boolean connected() { return true; }
        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(String domain, String action, com.fasterxml.jackson.databind.JsonNode payload) {
            if (action.equals("list")) return catalogFailure == null ? CompletableFuture.completedFuture(mapper.valueToTree(listing))
                    : CompletableFuture.failedFuture(catalogFailure);
            assertEquals("preview", action);
            previewRequests++;
            return preview.get();
        }
        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> command(String domain, String action, com.fasterxml.jackson.databind.JsonNode payload) {
            assertEquals("maps", domain);
            commandAction = action;
            commandPayload = payload;
            return operation;
        }
        public CompletableFuture<dmmt.remote.RemoteAssetStore.AssetDescriptor> upload(Path file) { throw new AssertionError("Unexpected upload"); }
        public CompletableFuture<Path> download(dmmt.remote.RemoteAssetStore.AssetDescriptor descriptor) {
            assertNotNull(download, "Unexpected download");
            return CompletableFuture.completedFuture(download);
        }
    }

    @Test
    void libraryDragAllowsLocalAndRemoteControlButNotReadOnlyCatalogOrRoot() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            assertTrue(f.browser.canStartLibraryDrag(f.entry(0), MouseButton.PRIMARY));
            f.browser.beginRemote(() -> CompletableFuture.completedFuture(remoteListing()));
        });
        onFx(() -> {
            var f = fixture.get();
            assertFalse(f.browser.canStartLibraryDrag(f.entry(0), MouseButton.PRIMARY));
            f.browser.endRemote();
            f.browser.beginRemote(new BrowserConnection());
        });
        onFx(() -> {
            var f = fixture.get();
            try {
                assertTrue(f.browser.canStartLibraryDrag(f.entry(0), MouseButton.PRIMARY));
                assertFalse(f.browser.canStartLibraryDrag(f.tree.getRoot().getValue(), MouseButton.PRIMARY));
                assertFalse(f.browser.canStartLibraryDrag(null, MouseButton.PRIMARY));
                assertFalse(f.browser.canStartLibraryDrag(f.entry(0), MouseButton.SECONDARY));
            } finally {
                f.browser.endRemote();
            }
        });
    }

    @Test
    void remoteLibraryMovesMapsMultilevelMapsAndFoldersUsingHostHandlesOnly() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var connection = new BrowserConnection();
        var root = connection.listing.nodes().getFirst().id();
        var folder = java.util.UUID.randomUUID();
        var multi = java.util.UUID.randomUUID();
        var destination = java.util.UUID.randomUUID();
        var nodes = new ArrayList<>(connection.listing.nodes());
        nodes.add(new dmmt.remote.RemoteMaps.Node(folder, root, "Source folder",
                dmmt.remote.RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1));
        nodes.add(new dmmt.remote.RemoteMaps.Node(multi, root, "Tower",
                dmmt.remote.RemoteMapCatalog.Kind.MULTILEVEL, List.of(), 2, 1));
        nodes.add(new dmmt.remote.RemoteMaps.Node(destination, root, "Destination",
                dmmt.remote.RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1));
        connection.listing = new dmmt.remote.RemoteMaps.Listing(connection.listing.context(), 7, nodes,
                connection.listing.current(), connection.listing.currentLabel(), connection.listing.recent());
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.library.forbidLocalScan = true;
            f.browser.beginRemote(connection);
        });
        onFx(() -> {
            var f = fixture.get();
            try {
                var entries = f.tree.getRoot().getChildren().stream().map(TreeItem::getValue)
                        .filter(entry -> !entry.name().equals("Destination")).toList();
                assertEquals(3, entries.size());
                for (var entry : entries)
                    assertTrue(f.browser.canStartLibraryDrag(entry, MouseButton.PRIMARY));
                var target = f.tree.getRoot().getChildren().stream().map(TreeItem::getValue)
                        .filter(entry -> entry.name().equals("Destination")).findFirst().orElseThrow();
                Method move = MapBrowser.class.getDeclaredMethod("moveAll", List.class, Path.class);
                move.setAccessible(true);
                move.invoke(f.browser, entries, target.path());
                assertEquals("move", connection.commandAction);
                var command = connection.mapper.treeToValue(connection.commandPayload, dmmt.remote.RemoteMaps.Command.class);
                assertEquals(connection.listing.context(), command.context());
                assertEquals(7, command.revision());
                assertEquals(destination, command.destination());
                assertEquals(java.util.Set.of(connection.listing.current(), folder, multi),
                        new java.util.HashSet<>(command.entries()));
                assertEquals(0, f.host.operations);
                assertNull(f.host.opened);
            } catch (ReflectiveOperationException | IOException failure) {
                throw new AssertionError(failure);
            } finally {
                f.library.forbidLocalScan = false;
                f.browser.endRemote();
            }
        });
    }

        @Test void remoteMenusDispatchOpaqueCommandsWithoutMutatingLocalLibrary() throws Exception {
            var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
            var commands = new ArrayList<String>();
            var payloads = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            var mapper = dmmt.service.JsonMappers.create();
            var root = java.util.UUID.randomUUID();
            var map = java.util.UUID.randomUUID();
            var context = java.util.UUID.randomUUID();
            var listing = new dmmt.remote.RemoteMaps.Listing(context, 4, List.of(
                    new dmmt.remote.RemoteMaps.Node(root, null, "Host library", dmmt.remote.RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1),
                    new dmmt.remote.RemoteMaps.Node(map, root, "Host meadow", dmmt.remote.RemoteMapCatalog.Kind.MAP, List.of("Forest"), 0, 1)),
                    map, "Host meadow", List.of(map));
            dmmt.remote.RemoteOperations.Connection connection = new dmmt.remote.RemoteOperations.Connection() {
                public boolean connected() { return true; }
                public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(String domain, String action, com.fasterxml.jackson.databind.JsonNode payload) {
                    return CompletableFuture.completedFuture(action.equals("list") ? mapper.valueToTree(listing)
                            : com.fasterxml.jackson.databind.node.NullNode.getInstance());
                }
                public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> command(String domain, String action, com.fasterxml.jackson.databind.JsonNode payload) {
                    assertEquals("maps", domain);
                    commands.add(action);
                    payloads.add(payload);
                    return CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
                }
                public CompletableFuture<dmmt.remote.RemoteAssetStore.AssetDescriptor> upload(Path path) { throw new AssertionError("Unexpected upload"); }
                public CompletableFuture<Path> download(dmmt.remote.RemoteAssetStore.AssetDescriptor asset) { throw new AssertionError("Unexpected download"); }
            };
            onFx(() -> {
                var f = new Fixture();
                fixture.set(f);
                f.library.forbidLocalScan = true;
                f.browser.beginRemote(connection);
            });
            onFx(() -> {
                var f = fixture.get();
                var entry = f.tree.getRoot().getChildren().getFirst().getValue();
                f.tree.getSelectionModel().select(f.tree.getRoot().getChildren().getFirst());
                var menu = f.browser.buildMenu(entry);
                assertTrue(menu.getItems().stream().anyMatch(item -> "Manage tags…".equals(item.getText())));
                menuItem(menu, "Open").fire();
                menuItem(menu, "Duplicate").fire();
                assertEquals(List.of("open", "copy"), commands);
                assertEquals(map.toString(), payloads.getFirst().path("entries").get(0).asText());
                assertEquals(context.toString(), payloads.getFirst().path("context").asText());
                assertEquals(4, payloads.getFirst().path("revision").asInt());
                assertNull(f.host.opened);
                assertEquals(0, f.host.operations);
                assertTrue(f.library.copied.isEmpty());
                f.library.forbidLocalScan = false;
                f.browser.endRemote();
            });
        }

        @Test void initialMultilevelImportPlanPreservesEditedOrderNamesAndEmptyLevels() {
            Path first = dir.resolve("first.dd2vtt"), second = dir.resolve("second.uvtt");
            var result = new LevelListDialog.Result(List.of(
                    new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(second), "Basement"),
                    new MultiLevelService.PlanItem(new MultiLevelService.Empty(), "Landing"),
                    new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(first), "Roof")), List.of());
            var options = dmmt.service.JsonMappers.create().createObjectNode();
            assertEquals(List.of(second, first), MapBrowser.prepareRemoteLevelImport(result, options));
            assertEquals(0, options.path("plan").get(0).path("source").asInt());
            assertEquals(-1, options.path("plan").get(1).path("source").asInt());
            assertEquals("Roof", options.path("plan").get(2).path("name").asText());
            assertEquals(List.of("Basement", "Roof"),
                    dmmt.service.JsonMappers.create().convertValue(options.path("levelNames"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
        }

    private static dmmt.remote.RemoteMapCatalog.Listing remoteListing() {
        var root = java.util.UUID.randomUUID();
        var folder = java.util.UUID.randomUUID();
        var tower = java.util.UUID.randomUUID();
        return new dmmt.remote.RemoteMapCatalog.Listing(List.of(
                new dmmt.remote.RemoteMapCatalog.Node(root, null, "Host library", dmmt.remote.RemoteMapCatalog.Kind.FOLDER),
                new dmmt.remote.RemoteMapCatalog.Node(folder, root, "Host folder", dmmt.remote.RemoteMapCatalog.Kind.FOLDER),
                new dmmt.remote.RemoteMapCatalog.Node(tower, folder, "Host tower", dmmt.remote.RemoteMapCatalog.Kind.MULTILEVEL),
                new dmmt.remote.RemoteMapCatalog.Node(java.util.UUID.randomUUID(), root, "Host meadow", dmmt.remote.RemoteMapCatalog.Kind.MAP)),
                tower, "Host tower", java.util.UUID.randomUUID(), null);
    }

    @Test void remoteLevelMetadataNotifiesSelectorAndOpenLevelUsesGuardedHostCommand() throws Exception {
        var fixture = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var mapper = dmmt.service.JsonMappers.create();
        var root = java.util.UUID.randomUUID();
        var map = java.util.UUID.randomUUID();
        var context = java.util.UUID.randomUUID();
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        var sent = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var listing = new dmmt.remote.RemoteMaps.Listing(context, 9, List.of(
                new dmmt.remote.RemoteMaps.Node(root, null, "Library", dmmt.remote.RemoteMapCatalog.Kind.FOLDER, List.of(), 0, 1),
                new dmmt.remote.RemoteMaps.Node(map, root, "Tower", dmmt.remote.RemoteMapCatalog.Kind.MULTILEVEL, List.of(), 2, 1)),
                map, "Tower", List.of(map));
        var levels = mapper.createObjectNode().put("currentLevelId", "ground");
        levels.putArray("levels").addObject().put("id", "ground").put("name", "Ground");
        levels.withArray("levels").addObject().put("id", "roof").put("name", "Roof");
        dmmt.remote.RemoteOperations.Connection connection = new dmmt.remote.RemoteOperations.Connection() {
            public boolean connected() { return true; }
            public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(String domain, String action, com.fasterxml.jackson.databind.JsonNode body) {
                return CompletableFuture.completedFuture(action.equals("list") ? mapper.valueToTree(listing)
                        : action.equals("levels") ? levels : com.fasterxml.jackson.databind.node.NullNode.getInstance());
            }
            public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> command(String domain, String action, com.fasterxml.jackson.databind.JsonNode body) {
                assertEquals("openLevel", action);
                sent.set(body);
                return CompletableFuture.completedFuture(com.fasterxml.jackson.databind.node.NullNode.getInstance());
            }
            public CompletableFuture<dmmt.remote.RemoteAssetStore.AssetDescriptor> upload(Path file) { throw new AssertionError(); }
            public CompletableFuture<Path> download(dmmt.remote.RemoteAssetStore.AssetDescriptor asset) { throw new AssertionError(); }
        };
        onFx(() -> {
            var f = new Fixture();
            fixture.set(f);
            f.browser.setRemoteLevelsChanged(notifications::incrementAndGet);
            f.browser.beginRemote(connection);
        });
        onFx(() -> {
            var browser = fixture.get().browser;
            assertEquals(List.of("Ground", "Roof"), browser.remoteLevels().stream().map(dmmt.remote.RemoteMaps.Level::name).toList());
            assertEquals("ground", browser.remoteCurrentLevelId());
            assertEquals(1, notifications.get());
            browser.remoteOpenLevel("roof");
            assertEquals("roof", sent.get().path("options").path("id").asText());
            assertEquals(map.toString(), sent.get().path("entries").get(0).asText());
            assertEquals(9, sent.get().path("revision").asInt());
            browser.remoteOpenLevel("missing");
        });
        onFx(() -> {
            assertEquals(1, notifications.get(), "Unchanged accepted metadata must not rebuild the selector.");
            fixture.get().browser.endRemote();
            assertTrue(fixture.get().browser.remoteLevels().isEmpty());
        });
    }

    @Test
    void multipleMapsEnableOnlyTagAddDuplicateAndDeleteForEitherMapKind() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            for (TreeItem<Entry> map : fixture.tree.getRoot().getChildren().subList(0, 2)) {
                ContextMenu menu = fixture.browser.buildMenu(map.getValue());
                for (MenuItem item : menu.getItems()) {
                    if (!(item instanceof SeparatorMenuItem)) {
                        assertEquals(!List.of("Add tags…", "Duplicate", "Export 2 maps…", "Delete…").contains(item.getText()), item.isDisable(),
                                item.getText());
                    }
                }
            }
        });
    }

    @Test
    void singleMapDisablesMultilevelCreationOnly() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            for (int index = 0; index < 2; index++) {
                fixture.select(index);
                ContextMenu menu = fixture.browser.buildMenu(fixture.entry(index));
                for (MenuItem item : menu.getItems()) {
                    assertEquals(item.getText() != null && item.getText().equals("Combine into multilevel map"),
                            item.isDisable());
                }
            }
        });
    }

    @Test
    void multipleOrdinaryMapsEnableMultilevelCreationAndPassTheEntireSelection() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            Path path = dir.resolve("Second");
            Entry second = new Entry(MapLibraryService.Kind.MAP, "Second", path,
                    path.resolve("Second.dmmap"), List.of());
            fixture.tree.getRoot().getChildren().add(new TreeItem<>(second));
            fixture.select(0, 3);
            for (Entry target : List.of(fixture.entry(0), second)) {
                ContextMenu menu = fixture.browser.buildMenu(target);
                for (MenuItem item : menu.getItems()) {
                    if (!(item instanceof SeparatorMenuItem)) {
                        assertEquals(!List.of("Add tags…", "Duplicate", "Export 2 maps…", "Delete…", "Combine into multilevel map")
                                .contains(item.getText()), item.isDisable(), item.getText());
                    }
                }
                menuItem(menu, "Combine into multilevel map").fire();
                assertEquals(List.of(fixture.entry(0), second), fixture.host.merged);
            }
        });
    }

    @Test
    void mixedSelectionDisablesAllActionsIncludingFolderDelete() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 2);
            for (int index : new int[]{0, 2}) {
                assertTrue(fixture.browser.buildMenu(fixture.entry(index)).getItems().stream()
                        .allMatch(MenuItem::isDisable));
            }
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.DELETE));
            assertTrue(fixture.library.deleted.isEmpty());
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void tagMenuPassesSingleAndMultipleSelectionsToHost() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0);
            menuItem(fixture.browser.buildMenu(fixture.entry(0)), "Manage tags…").fire();
            assertEquals(List.of(fixture.entry(0)), fixture.host.tagged);
            fixture.select(0, 1);
            menuItem(fixture.browser.buildMenu(fixture.entry(1)), "Add tags…").fire();
            assertEquals(List.of(fixture.entry(0), fixture.entry(1)), fixture.host.tagged);
        });
    }

    @Test
    void tagDialogSuggestsWithoutAutocompletingAndPreventsCaseInsensitiveDuplicates() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Haven", 1, false,
                    List.of("Forest"), List.of("Tavern", "TAVERN", "Castle"));
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            TextField input = (TextField) pane.lookup("#map-tag-input");
            Button add = (Button) pane.lookup("#map-tag-add");
            dialog.show();
            try {
                input.requestFocus();
                input.setText("tav");
                assertEquals("TAV", input.getText());
                ListView<?> suggested = (ListView<?>) Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .map(window -> window.getScene().lookup("#map-tag-suggestions"))
                        .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertEquals(List.of("TAVERN"), suggested.getItems());
                suggested.getSelectionModel().select(0);
                suggested.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10,
                        MouseButton.PRIMARY, 1, false, false, false, false,
                        false, false, false, false, false, true, null));
                assertEquals("TAVERN", input.getText());
                add.fire();
                input.setText(" TAVERN ");
                add.fire();
                assertEquals(List.of("TAVERN"), tagResult(dialog).additions());
                assertEquals(List.of(), tagResult(dialog).removals());
                assertTrue(((Label) pane.lookup(".error-label")).getText().contains("already"));
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    void overlaySuggestionsLimitToFiveNavigateWithoutCompletingAndDismissCleanly() throws Exception {
        onFx(() -> {
            TextField input = new TextField("T");
            Stage stage = new Stage();
            stage.setScene(new Scene(new javafx.scene.layout.VBox(input), 400, 300));
            TagSuggestions popup = new TagSuggestions(input);
            List<String> matches = List.of("TOWN", "TREE", "TOWER", "TRAIL", "TAVERN", "TEMPLE");
            try {
                stage.show();
                input.requestFocus();
                popup.refresh(matches);
                assertTrue(popup.isShowing());
                ListView<?> list = (ListView<?>) popup.getContent().getFirst();
                for (int count = 1; count <= 5; count++) {
                    popup.refresh(matches.subList(0, count));
                    list.layout();
                    assertEquals(count * list.getFixedCellSize(),
                            list.getHeight() - list.snappedTopInset() - list.snappedBottomInset(), 0.01);
                    assertTrue(list.lookupAll(".scroll-bar").stream()
                            .filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
                            .filter(bar -> bar.getOrientation() == Orientation.VERTICAL)
                            .noneMatch(ScrollBar::isVisible), "Unexpected scrollbar for " + count + " suggestions");
                }
                popup.refresh(matches);
                assertEquals(matches.subList(0, 5), list.getItems());
                assertEquals(input.localToScreen(input.getBoundsInLocal()).getMaxY() + 4, popup.getY(), 1);
                assertTrue(list.getSelectionModel().isEmpty());
                input.fireEvent(key(KeyCode.DOWN));
                assertEquals(0, list.getSelectionModel().getSelectedIndex());
                input.fireEvent(key(KeyCode.DOWN));
                assertEquals(1, list.getSelectionModel().getSelectedIndex());
                input.fireEvent(key(KeyCode.UP));
                assertEquals(0, list.getSelectionModel().getSelectedIndex());
                assertEquals("T", input.getText());
                input.fireEvent(key(KeyCode.ENTER));
                assertEquals("TOWN", input.getText());
                assertFalse(popup.isShowing());
                popup.refresh(matches);
                input.fireEvent(key(KeyCode.ESCAPE));
                assertFalse(popup.isShowing());
                popup.refresh(matches);
                popup.refresh(List.of());
                assertFalse(popup.isShowing());
                assertEquals("TOWN", input.getText());
            } finally {
                popup.hide();
                stage.close();
            }
        });
    }

    @Test
    void suggestionsRankByFitBeforeLimitingToFiveAndRerankAsInputChanges() throws Exception {
        onFx(() -> {
            TextField input = new TextField("tav");
            TagSuggestions popup = new TagSuggestions(input);
            List<String> matches = List.of("OLD TAVERN", "A TAVERN", "TAVERN DISTRICT", "TAVERNS",
                    "TAVERN", "TAV", "TAVA", "TAVB");
            popup.refresh(matches);
            ListView<?> list = (ListView<?>) popup.getContent().getFirst();
            assertEquals(List.of("TAV", "TAVA", "TAVB", "TAVERN", "TAVERNS"), list.getItems());
            input.setText("tavern");
            popup.refresh(matches.stream().filter(tag -> tag.contains("TAVERN")).toList());
            assertEquals(List.of("TAVERN", "TAVERNS", "TAVERN DISTRICT", "A TAVERN", "OLD TAVERN"),
                    list.getItems());
            assertEquals("tavern", input.getText());
        });
    }

    @Test
    void tagRemovalDoesNotAddPendingInputAndCancelDiscardsEdits() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Tower", 1, true,
                    List.of("Tavern", "Forest"), List.of());
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            TextField input = (TextField) pane.lookup("#map-tag-input");
            input.setText("Castle");
            Button remove = (Button) pane.lookupAll(".map-tag-chip .button").stream()
                    .filter(node -> "Remove TAVERN".equals(node.getAccessibleText())).findFirst().orElseThrow();
            remove.fire();
            assertEquals("CASTLE", input.getText());
            assertEquals(List.of("TAVERN"), tagResult(dialog).removals());
            assertEquals(List.of(), tagResult(dialog).additions());
            assertNull(dialog.getResultConverter().call(ButtonType.CANCEL));
        });
    }

    @Test
    void bulkTagDialogCannotRemoveExistingTagsAndApplyAddsPendingInput() throws Exception {
        onFx(() -> {
            Dialog<MapTagsDialog.Result> dialog = MapTagsDialog.create(null, "Haven", 2, false,
                    List.of("Forest"), List.of("Tavern"));
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            assertTrue(pane.lookupAll(".map-tag-chip").isEmpty());
            TextField input = (TextField) pane.lookup("#map-tag-input");
            input.setText(" Tavern ");
            ButtonType apply = pane.getButtonTypes().stream()
                    .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow();
            ((Button) pane.lookupButton(apply)).fire();
            assertEquals("", input.getText());
            assertEquals(List.of("TAVERN"), tagResult(dialog).additions());
            assertEquals(List.of(), tagResult(dialog).removals());
        });
    }

    private static MapTagsDialog.Result tagResult(Dialog<MapTagsDialog.Result> dialog) {
        ButtonType apply = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow();
        return dialog.getResultConverter().call(apply);
    }

    @Test
    void applicationTagEditsPreserveUnsavedContentAndSynchronizeOpenMapAndLevel() throws Exception {
        for (boolean multilevel : List.of(false, true)) {
            CompletableFuture<Void> completed = new CompletableFuture<>();
            FutureTask<ApplicationTagsFixture> setup = new FutureTask<>(() -> {
                ApplicationTagsFixture fixture = new ApplicationTagsFixture(multilevel);
                Timeline responder = new Timeline();
                responder.getKeyFrames().add(new KeyFrame(Duration.millis(20), event -> {
                    Window dialogWindow = Window.getWindows().stream().filter(Window::isShowing)
                            .filter(window -> window.getScene().getRoot() instanceof DialogPane)
                            .findFirst().orElse(null);
                    if (dialogWindow == null) {
                        return;
                    }
                    responder.stop();
                    DialogPane pane = (DialogPane) dialogWindow.getScene().getRoot();
                    try {
                        Button remove = (Button) pane.lookupAll(".map-tag-chip .button").stream()
                                .filter(node -> "Remove TAVERN".equals(node.getAccessibleText()))
                                .findFirst().orElseThrow();
                        remove.fire();
                        ((TextField) pane.lookup("#map-tag-input")).setText("Town");
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                .filter(type -> type.getText().equals("Apply")).findFirst().orElseThrow())).fire();
                    } catch (Throwable failure) {
                        completed.completeExceptionally(failure);
                        ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                    }
                }));
                responder.setCycleCount(Timeline.INDEFINITE);
                fixture.status.textProperty().addListener((obs, before, after) -> {
                    if (after.startsWith("Updated tags for ")) {
                        completed.complete(null);
                    } else if (after.startsWith("Library operation failed") || after.startsWith("Could not read map tags")) {
                        responder.stop();
                        completed.completeExceptionally(new AssertionError(after));
                    }
                });
                responder.play();
                Method manage = DungeonMasterMapToolApplication.class.getDeclaredMethod("handleManageTags", List.class);
                manage.setAccessible(true);
                manage.invoke(fixture.app, List.of(fixture.entry));
                return fixture;
            });
            Platform.runLater(setup);
            ApplicationTagsFixture fixture = setup.get(10, TimeUnit.SECONDS);
            completed.get(15, TimeUnit.SECONDS);
            onFx(() -> {
                assertEquals(List.of("ROOF", "TOWN"), fixture.live.getMap().getTags());
                assertDoesNotThrow(() -> {
                    DmProject persisted = fixture.projects.load(fixture.openFile);
                    assertEquals(fixture.live.getMap().getTags(), persisted.getMap().getTags());
                    assertEquals(1, persisted.getWalls().size());
                    assertEquals(42, persisted.getWalls().getFirst().getX1());
                    if (fixture.manifest != null) {
                        List<String> union = fixture.library.tags().readTags(fixture.manifest);
                        assertEquals(List.of("CELLAR", "TOWN", "ROOF"), union);
                    }
                    fixture.projects.save(fixture.openFile, fixture.live);
                    assertEquals(List.of("ROOF", "TOWN"), fixture.library.tags().readTags(fixture.openFile));
                });
            });
        }
    }

    private final class ApplicationTagsFixture {
        final ProjectService projects = new ProjectService();
        final MapLibraryService library;
        final DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
        final Label status = new Label();
        final Entry entry;
        final Path manifest;
        final Path openFile;
        final DmProject live;

        ApplicationTagsFixture(boolean multilevel) throws Exception {
            library = new MapLibraryService(dir.resolve(multilevel ? "multi" : "single"), projects);
            Path roof = library.getRoot().resolve("Roof").resolve("Roof.dmmap");
            DmProject project = DmProject.builder().build();
            project.getMap().setTags(List.of("Tavern", "Roof"));
            projects.save(roof, project);
            if (multilevel) {
                Path cellar = library.getRoot().resolve("Cellar").resolve("Cellar.dmmap");
                project.getMap().setTags(List.of("Cellar"));
                projects.save(cellar, project);
                manifest = library.multiLevels().create(library.getRoot(), "Tower", List.of(
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(cellar.getParent(), cellar), "Cellar"),
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(roof.getParent(), roof), "Roof")),
                        null).manifestFile();
                MultiLevelManifest levels = library.multiLevels().loadManifest(manifest);
                MultiLevelManifest.Level level = levels.getLevels().get(1);
                openFile = MultiLevelService.levelFile(manifest, level);
                field(app, "multiLevelFile", manifest);
                field(app, "multiLevelManifest", levels);
                field(app, "currentLevelId", level.getId());
            } else {
                manifest = null;
                openFile = roof;
            }
            live = projects.load(openFile);
            live.getWalls().add(DmProject.WallSegment.builder().x1(42).build());
            field(app, "project", live);
            field(app, "projectFile", openFile);
            field(app, "mapLibrary", library);
            field(app, "statusLabel", status);
            Method createHost = DungeonMasterMapToolApplication.class.getDeclaredMethod("createBrowserHost");
            createHost.setAccessible(true);
            MapBrowser browser = new MapBrowser(library, (MapBrowser.Host) createHost.invoke(app),
                    new RecentMaps(new AppSettings(dir.resolve("app-tags.ini"))));
            field(app, "mapBrowser", browser);
            entry = library.scan().children().getFirst();
        }
    }

    private static void field(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    @Test
    void duplicateCopiesEverySelectedMapAndSavesTheSelectedOpenMapFirst() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            Entry ordinary = fixture.entry(0);
            Entry multilevel = fixture.entry(1);
            fixture.host.current = multilevel.mapFile();
            menuItem(fixture.browser.buildMenu(ordinary), "Duplicate").fire();
            assertEquals(List.of(ordinary, multilevel), fixture.library.copied);
            assertEquals(multilevel.path(), fixture.host.affected);
            assertEquals(1, fixture.host.operations);
        });
    }

    @Test
    void deleteMenuAndKeyDeleteAllSelectedMapsWithOneConfirmation() throws Exception {
        for (boolean keyboard : new boolean[]{false, true}) {
            onFx(() -> {
                Fixture fixture = new Fixture();
                fixture.select(0, 1);
                List<Entry> maps = List.of(fixture.entry(0), fixture.entry(1));
                fixture.host.current = maps.get(1).mapFile();
                FutureTask<Void> confirmation = answerDeleteDialog(true);
                if (keyboard) {
                    KeyEvent event = key(KeyCode.DELETE);
                    fixture.tree.getOnKeyPressed().handle(event);
                    assertTrue(event.isConsumed());
                } else {
                    menuItem(fixture.browser.buildMenu(maps.get(0)), "Delete…").fire();
                }
                assertTrue(confirmation.isDone());
                assertDoesNotThrow(() -> confirmation.get());
                assertEquals(maps, fixture.library.deleted);
                assertEquals(maps.get(1).path(), fixture.host.affected);
                assertEquals(1, fixture.host.operations);
            });
        }
    }

    @Test
    void cancellingBatchDeleteLeavesEveryMapUntouched() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            FutureTask<Void> confirmation = answerDeleteDialog(false);
            menuItem(fixture.browser.buildMenu(fixture.entry(0)), "Delete…").fire();
            assertDoesNotThrow(() -> confirmation.get());
            assertTrue(fixture.library.deleted.isEmpty());
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void enterAndRenameDoNotActOnMultipleMaps() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            fixture.select(0, 1);
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.ENTER));
            fixture.tree.getOnKeyPressed().handle(key(KeyCode.F2));
            assertNull(fixture.host.opened);
            assertEquals(0, fixture.host.operations);
        });
    }

    @Test
    void rightClickPreservesSelectedMapsAndReplacesSelectionForAnUnselectedMap() throws Exception {
        onFx(() -> {
            Fixture fixture = new Fixture();
            Stage stage = new Stage();
            stage.setScene(new Scene(fixture.browser, 400, 600));
            try {
                stage.show();
                fixture.browser.applyCss();
                fixture.browser.layout();
                fixture.select(0, 1);
                TreeCell<?> cell = fixture.tree.lookupAll(".tree-cell").stream()
                        .filter(TreeCell.class::isInstance).map(node -> (TreeCell<?>) node)
                        .filter(candidate -> fixture.entry(1).equals(candidate.getItem()))
                        .findFirst().orElseThrow();
                cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 10, 10, 10, 10,
                        MouseButton.SECONDARY, 1, false, false, false, false,
                        false, false, true, false, true, true, null));
                cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        10, 10, 10, 10, false, null));
                assertEquals(2, fixture.tree.getSelectionModel().getSelectedItems().size());
                fixture.select(0);
                cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        10, 10, 10, 10, false, null));
                assertEquals(1, fixture.tree.getSelectionModel().getSelectedItems().size());
                assertEquals(fixture.entry(1), fixture.tree.getSelectionModel().getSelectedItem().getValue());
            } finally {
                stage.close();
            }
        });
    }

    private static FutureTask<Void> answerDeleteDialog(boolean confirm) {
        FutureTask<Void> answer = new FutureTask<>(() -> {
            Window window = Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
            DialogPane pane = (DialogPane) window.getScene().getRoot();
            try {
                assertEquals("Delete 2 maps?", pane.getHeaderText());
                String message = ((Label) pane.getContent()).getText();
                assertTrue(message.contains("Ordinary"));
                assertTrue(message.contains("Multilevel"));
            } finally {
                var type = pane.getButtonTypes().stream()
                        .filter(button -> button.getText().equals(confirm ? "Delete" : "Cancel"))
                        .findFirst().orElseThrow();
                ((Button) pane.lookupButton(type)).fire();
            }
            return null;
        });
        Platform.runLater(answer);
        return answer;
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private final class Fixture {
        final RecordingLibrary library = new RecordingLibrary(dir);
        final RecordingHost host = new RecordingHost();
        final MapBrowser browser = new MapBrowser(library, host, new RecentMaps(new AppSettings(dir.resolve("settings.ini"))));
        final TreeView<Entry> tree;

        @SuppressWarnings("unchecked")
        Fixture() {
            tree = (TreeView<Entry>) browser.getChildren().stream()
                    .filter(TreeView.class::isInstance).findFirst().orElseThrow();
        }

        Entry entry(int index) {
            return tree.getRoot().getChildren().get(index).getValue();
        }

        void select(int... indices) {
            tree.getSelectionModel().clearSelection();
            for (int index : indices) {
                tree.getSelectionModel().select(tree.getRoot().getChildren().get(index));
            }
        }
    }

    @Test void remotePartialImportReportIncludesAcceptedCountsAndEveryFailure() {
        var mapper = dmmt.service.JsonMappers.create();
        var report = mapper.createObjectNode().put("imported", 2).put("total", 4);
        var failures = report.putArray("failures");
        failures.addObject().put("source", "tower.dd2vtt").put("reason", "Invalid image");
        failures.addObject().put("source", "cave.uvtt").put("reason", "Name already exists");
        String message = MapBrowser.remoteImportFailures(report);
        assertTrue(message.contains("Imported 2 of 4 maps on the host"));
        assertTrue(message.contains("tower.dd2vtt: Invalid image"));
        assertTrue(message.contains("cave.uvtt: Name already exists"));
        assertNull(MapBrowser.remoteImportFailures(mapper.createObjectNode().put("imported", 4).put("total", 4)));
        assertNull(MapBrowser.remoteImportFailures(com.fasterxml.jackson.databind.node.NullNode.getInstance()));
    }

    private static final class RecordingLibrary extends MapLibraryService {
        boolean forbidLocalScan;
        final List<Entry> copied = new ArrayList<>();
        final List<Entry> deleted = new ArrayList<>();

        RecordingLibrary(Path root) {
            super(root, new ProjectService());
        }

        @Override
        public Entry scan() {
            assertFalse(forbidLocalScan, "Remote browser must not scan the local library.");
            Path ordinary = getRoot().resolve("Ordinary");
            Path multilevel = getRoot().resolve("Multilevel");
            return new Entry(Kind.FOLDER, "Library", getRoot(), null, List.of(
                    new Entry(Kind.MAP, "Ordinary", ordinary, ordinary.resolve("Ordinary.dmmap"), List.of()),
                    new Entry(Kind.MAP, "Multilevel", multilevel, multilevel.resolve("Multilevel.dmlevels"), List.of()),
                    new Entry(Kind.FOLDER, "Folder", getRoot().resolve("Folder"), null, List.of())));
        }

        @Override
        public Result copy(Entry entry) {
            copied.add(entry);
            return new Result(Map.of(), entry.mapFile());
        }

        @Override
        public void delete(Entry entry) {
            deleted.add(entry);
        }
    }

    private static final class RecordingHost implements MapBrowser.Host {
        Path current;
        Path affected;
        Path opened;
        List<Entry> merged;
        List<Entry> tagged;
        int operations;

        @Override public void newMap() {}
        @Override public void newMapIn(Path folder) {}
        @Override public void importMap(Path folder) {}
        @Override public void importMapFolder(Path folder) {}
        @Override public void saveMap() {}
        @Override public void rotateMap(boolean clockwise) {}
        @Override public void openMap(Path file) { opened = file; }
        @Override public Path currentMapFile() { return current; }
        @Override public void importMultiLevelMap(Path folder) {}
        @Override public void mergeIntoMultiLevelMap(List<Entry> maps) { merged = maps; }
        @Override public void manageLevels(Path file) {}
        @Override public void manageTags(List<Entry> maps) { tagged = maps; }
        @Override public void exportMaps(List<Entry> maps) {}
        @Override public void dropMapOnMap(List<Entry> dragged, Entry target) {}
        @Override public void dissolveMultiLevel(Path file) {}

        @Override
        public void runLibraryOperation(String message, Path affectedPath, MapBrowser.LibraryOperation operation,
                                        Consumer<MapLibraryService.Result> onDone) {
            affected = affectedPath;
            operations++;
            try {
                onDone.accept(operation.run());
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
