package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.model.DmProject.TokenDefinition;
import dmmt.model.DmProject.TokenShape;
import dmmt.service.TokenLibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RemoteTokensTest
{
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeAll
    static void startFx() throws Exception
    {
        dmmt.FxTestSupport.startJavaFx();
    }

    @Test
    void fullLibraryOperationsUseHostPersistenceAndPortableArtwork() throws Exception
    {
        try (Harness h = new Harness())
        {
            long revision = h.state().revision();
            h.change(new RemoteTokens.Request(revision, RemoteTokens.Operation.CREATE_FOLDER,
                    null, null, null, "", "Party"));
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.CREATE_FOLDER,
                    null, null, null, "Party", "Heroes"));
            Path png = png("client-source.png");
            TokenDefinition token = TokenDefinition.builder().id("ranger").name("Ranger").color("#123456")
                    .shape(TokenShape.SQUARE).border(false).sizeTiles(1.5).zoom(2).centerX(.25).centerY(.75)
                    .sourceImage(png.toUri().toString()).image(png.toUri().toString()).build();
            var uploaded = h.await(RemoteTokens.upload(token, h.connection));
            var state = h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.PUT,
                    uploaded, null, "Party/Heroes", null, null));
            assertEquals(1, state.entries().size());
            assertEquals(uploaded, state.entries().getFirst().token());
            String wire = RemoteTokens.json(state).toString();
            assertFalse(wire.contains(directory.toString()));
            assertFalse(wire.contains("file:"));
            assertFalse(wire.contains("sourceImage"));
            assertEquals("Ranger", h.accepted.getLast().getName());
            assertEquals(Thread.currentThread(), h.callbackThread);

            var prepared = h.await(RemoteTokens.download(state, h.connection, h.worker, h.owner::add));
            var local = prepared.library().getEntries().getFirst().getToken();
            assertTrue(RemoteTokens.localArtwork(local.getImage())
                    .startsWith(directory.resolve("client-cache").toRealPath()));
            assertEquals(token.getShape(), local.getShape());
            assertFalse(local.isBorder());
            assertEquals(2, local.getZoom());
            assertEquals(.25, local.getCenterX());
            assertFalse(Files.exists(directory.resolve("client-library").resolve("tokens.json")));

            var duplicate = TokenLibraryService.duplicate(local);
            var duplicated = h.await(RemoteTokens.upload(duplicate, h.connection));
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.PUT,
                    duplicated, null, "Party", null, null));
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.MOVE,
                    null, duplicate.getId(), "Party/Heroes", null, null));
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.MOVE_FOLDER,
                    null, null, "Party", "", "Characters"));
            assertTrue(h.state().entries().stream().allMatch(e -> e.folder().equals("Characters/Heroes")));

            var renamed = new RemoteTokens.Definition(uploaded.id(), "Scout", "#ABCDEF", 2,
                    TokenShape.CIRCLE, true, 1, .5, .5, null, null);
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.PUT,
                    renamed, null, "Characters/Heroes", null, null));
            assertNull(h.service.load().getEntries().stream().filter(e -> e.getToken().getId().equals("ranger"))
                    .findFirst().orElseThrow().getToken().getImage(), "Image removal persists on A");
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.DELETE,
                    null, duplicate.getId(), null, null, null));
            h.change(new RemoteTokens.Request(h.state().revision(), RemoteTokens.Operation.DELETE_FOLDER,
                    null, null, "Characters", null, null));
            assertTrue(h.service.load().getEntries().isEmpty());
            assertTrue(h.service.load().getFolders().isEmpty());
            assertTrue(h.await(h.host.query("list", RemoteTokens.json(new RemoteTokens.Stamp(h.state().revision())))).isNull(),
                    "An unchanged poll performs no library/artwork scan");
            assertFalse(Files.exists(directory.resolve("client-library").resolve("tokens.json")));
        }
    }

    @Test
    void localHostChangesInvalidateBothAlreadyStaleAndInFlightClientCommands() throws Exception
    {
        var service = new TokenLibraryService(directory.resolve("host-library"));
        var store = store("host-assets");
        var preparation = new LinkedBlockingQueue<Runnable>();
        var owner = new LinkedBlockingQueue<Runnable>();
        var host = new RemoteTokens(() -> service, store, directory.resolve("cache"),
                preparation::add, owner::add, ignored -> fail("Rejected edits must not update the map"));
        var token = plain("hero", "Client");
        var pending = host.execute("change", RemoteTokens.json(new RemoteTokens.Request(0,
                RemoteTokens.Operation.PUT, token, null, "", null, null)));
        assertFalse(pending.isDone());
        service.put(token.materialize(null, null), "");
        service.createFolder("", "Host change");
        Thread worker = new Thread(preparation.remove(), "token-test-preparation");
        worker.start();
        worker.join(5000);
        while (!pending.isDone())
        {
            Runnable completion = owner.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion, "Token operation completion must return to the owner");
            completion.run();
        }
        assertTrue(pending.isCompletedExceptionally());
        assertTrue(assertThrows(CompletionException.class, pending::join).getCause().getMessage().contains("changed"));
        assertEquals("Client", service.load().getEntries().getFirst().getToken().getName());
        assertEquals(List.of("Host change"), service.load().getFolders());
        var stale = host.execute("change", RemoteTokens.json(new RemoteTokens.Request(0,
                RemoteTokens.Operation.DELETE, null, "hero", null, null, null)));
        assertTrue(stale.isCompletedExceptionally());
        assertEquals(1, service.load().getEntries().size());
    }

    @Test
    void rejectsForgedAssetsPathsUnexpectedActionsAndInvalidDefinitionsWithoutPersistence() throws Exception
    {
        try (Harness h = new Harness())
        {
            var missing = new RemoteAssetStore.AssetDescriptor("a".repeat(64), 10);
            var invalid = new RemoteTokens.Definition("hero", "Hero", "#123456", 1, TokenShape.CIRCLE,
                    true, 1, .5, .5, missing, missing);
            assertThrows(ExecutionException.class, () -> h.await(h.host.execute("change", RemoteTokens.json(
                    new RemoteTokens.Request(0, RemoteTokens.Operation.PUT, invalid, null, "", null, null)))));
            assertFalse(Files.exists(directory.resolve("host-library").resolve("tokens.json")));
            assertThrows(ExecutionException.class, () -> h.await(h.host.execute("deleteAll", RemoteTokens.json(new RemoteTokens.Stamp(0)))));
            assertThrows(ExecutionException.class, () -> h.await(h.host.query("change", RemoteTokens.json(new RemoteTokens.Stamp(0)))));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.Definition("hero", "Hero", "#123456",
                    Double.NaN, TokenShape.CIRCLE, true, 1, .5, .5, null, null));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.Definition("hero", "Hero", "#123456",
                    1, TokenShape.CIRCLE, true, Double.POSITIVE_INFINITY, .5, .5, null, null));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.Request(0,
                    RemoteTokens.Operation.CREATE_FOLDER, null, null, null, "", ".."));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.Request(0,
                    RemoteTokens.Operation.DELETE, null, "hero", "unexpected", null, null));
            var pathInjection = RemoteTokens.json(new RemoteTokens.Request(0, RemoteTokens.Operation.PUT,
                    plain("hero", "Hero"), null, "", null, null));
            ((com.fasterxml.jackson.databind.node.ObjectNode)pathInjection.path("token"))
                    .put("sourceImage", "file:///C:/private.png");
            assertThrows(ExecutionException.class, () -> h.await(h.host.execute("change", pathInjection)));
            assertEquals(0, h.service.revision());

            Path text = directory.resolve("not-an-image.png");
            Files.writeString(text, "Not PNG");
            var asset = h.store.publishRequiredAsset(text);
            var badImage = new RemoteTokens.Definition("hero", "Hero", "#123456", 1, TokenShape.CIRCLE,
                    true, 1, .5, .5, asset, asset);
            assertThrows(ExecutionException.class, () -> h.await(h.host.execute("change", RemoteTokens.json(
                    new RemoteTokens.Request(0, RemoteTokens.Operation.PUT, badImage, null, "", null, null)))));
            assertEquals(0, h.service.revision());
        }
    }

    @Test
    void snapshotsAndCommandsRejectMissingFieldsAndInvalidFolderHierarchy() throws Exception
    {
        try (Harness h = new Harness())
        {
            var request = RemoteTokens.json(new RemoteTokens.Request(0, RemoteTokens.Operation.PUT,
                    plain("hero", "Hero"), null, "", null, null));
            ((com.fasterxml.jackson.databind.node.ObjectNode)request).remove("revision");
            assertThrows(ExecutionException.class, () -> h.await(h.host.execute("change", request)));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.State(0,
                    List.of("Missing/Child"), List.of()));
            assertThrows(IllegalArgumentException.class, () -> new RemoteTokens.State(0, List.of(),
                    List.of(new RemoteTokens.Entry("Gone", plain("hero", "Hero")))));
        }

    }

    @Test
    void stoppingHostingCancelsPreparedButUncommittedLibraryEdits() throws Exception
    {
        var service = new TokenLibraryService(directory.resolve("host-library"));
        var work = new LinkedBlockingQueue<Runnable>();
        var completions = new LinkedBlockingQueue<Runnable>();
        var host = new RemoteTokens(() -> service, store("assets"), directory.resolve("cache"),
                work::add, completions::add, ignored -> fail("Stopped hosting must not update the map"));
        var pending = host.execute("change", RemoteTokens.json(new RemoteTokens.Request(0,
                RemoteTokens.Operation.CREATE_FOLDER, null, null, null, "", "Too late")));
        host.close();
        work.remove().run();
        while (!pending.isDone())
        {
            Runnable completion = completions.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
        }
        assertTrue(pending.isCompletedExceptionally());
        assertTrue(service.load().getFolders().isEmpty());
        assertEquals(0, service.revision());
        assertTrue(host.query("list", RemoteTokens.json(new RemoteTokens.Stamp(-1))).isCompletedExceptionally());
    }

    @Test
    void persistenceCompletesOnWorkerBeforeOwnerCallbacksAndDoesNotReplayOverANewerHostEdit() throws Exception
    {
        var service = new TokenLibraryService(directory.resolve("host-library"));
        var work = new LinkedBlockingQueue<Runnable>();
        var completions = new LinkedBlockingQueue<Runnable>();
        var callbacks = new ArrayList<Thread>();
        var callbackNames = new ArrayList<String>();
        var refreshes = new ArrayList<Thread>();
        Thread applicationOwner = Thread.currentThread();
        var host = new RemoteTokens(() -> service, store("assets"), directory.resolve("cache"),
                work::add, completions::add, token -> {
                    callbacks.add(Thread.currentThread());
                    callbackNames.add(token.getName());
                });
        host.setLibraryChanged(() -> refreshes.add(Thread.currentThread()));
        var pending = host.execute("change", RemoteTokens.json(new RemoteTokens.Request(0,
                RemoteTokens.Operation.PUT, plain("hero", "Remote edit"), null, "", null, null)));
        Thread persistence = new Thread(work.remove(), "token-persistence-worker");
        persistence.start();
        persistence.join(5000);
        assertFalse(persistence.isAlive());
        assertEquals("Remote edit", service.load().getEntries().getFirst().getToken().getName(),
                "Persistence must have finished without pumping the application-owner queue");
        assertEquals(1, service.revision());
        assertTrue(callbacks.isEmpty(), "Map updates remain owner-dispatched");
        assertTrue(refreshes.isEmpty(), "Browser updates remain owner-dispatched");
        assertFalse(pending.isDone());

        service.put(TokenDefinition.builder().id("hero").name("Newer host edit").build(), "");
        completions.remove().run();
        assertTrue(callbacks.isEmpty(), "A delayed remote completion must not overwrite a newer local map edit");
        while (!pending.isDone())
        {
            Runnable preparation = work.poll();
            if (preparation != null)
            {
                Thread background = new Thread(preparation, "token-reconcile-worker");
                background.start();
                background.join(5000);
                assertFalse(background.isAlive());
            }
            Runnable completion = completions.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
        }
        assertEquals(List.of(applicationOwner), callbacks);
        assertEquals(List.of("Newer host edit"), callbackNames);
        assertEquals(List.of(applicationOwner), refreshes);
        assertEquals("Newer host edit", RemoteTokens.state(pending.join()).entries().getFirst().token().name());
        host.close();
    }

    @Test
    void workerRechecksReplacedLibraryBeforePersistence() throws Exception
    {
        var original = new TokenLibraryService(directory.resolve("original"));
        var replacement = new TokenLibraryService(directory.resolve("replacement"));
        var current = new java.util.concurrent.atomic.AtomicReference<>(original);
        var work = new LinkedBlockingQueue<Runnable>();
        var completions = new LinkedBlockingQueue<Runnable>();
        var host = new RemoteTokens(current::get, store("assets"), directory.resolve("cache"),
                work::add, completions::add, ignored -> fail("Replaced libraries cannot update the current map"));
        var pending = host.execute("change", RemoteTokens.json(new RemoteTokens.Request(0,
                RemoteTokens.Operation.CREATE_FOLDER, null, null, null, "", "Old library")));
        current.set(replacement);
        Thread persistence = new Thread(work.remove(), "token-replaced-library-worker");
        persistence.start();
        persistence.join(5000);
        while (!pending.isDone())
        {
            Runnable completion = completions.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
        }
        assertTrue(pending.isCompletedExceptionally());
        assertEquals(0, original.revision());
        assertEquals(0, replacement.revision());
        assertFalse(Files.exists(directory.resolve("original").resolve("tokens.json")));
        assertFalse(Files.exists(directory.resolve("replacement").resolve("tokens.json")));
        host.close();
    }

    private RemoteTokens.Definition plain(String id, String name)
    {
        return new RemoteTokens.Definition(id, name, "#123456", 1, TokenShape.CIRCLE, true, 1, .5, .5, null, null);
    }

    private Path png(String name) throws IOException
    {
        Path file = directory.resolve(name);
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0xFFFF0000);
        assertTrue(javax.imageio.ImageIO.write(image, "png", file.toFile()));
        return file;
    }

    private RemoteAssetStore store(String name) throws IOException
    {
        return new RemoteAssetStore(directory.resolve(name), 1024 * 1024, 8 * 1024 * 1024, 8 * 1024 * 1024);
    }

    private final class Harness implements AutoCloseable
    {
        private final TokenLibraryService service = new TokenLibraryService(directory.resolve("host-library"));
        private final RemoteAssetStore store = store("host-assets");
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final LinkedBlockingQueue<Runnable> owner = new LinkedBlockingQueue<>();
        private final List<TokenDefinition> accepted = new ArrayList<>();
        private Thread callbackThread;
        private final RemoteTokens host = new RemoteTokens(() -> service, store, directory.resolve("host-cache"),
                worker, owner::add, token -> {
                    callbackThread = Thread.currentThread();
                    accepted.add(token);
                });
        private final RemoteOperations.Connection connection = new RemoteOperations.Connection()
        {
            @Override public boolean connected() { return true; }
            @Override public CompletableFuture<JsonNode> query(String domain, String action, JsonNode payload)
            {
                assertEquals("tokens", domain);
                return host.query(action, payload);
            }
            @Override public CompletableFuture<JsonNode> command(String domain, String action, JsonNode payload)
            {
                assertEquals("tokens", domain);
                return host.execute(action, payload);
            }
            @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(Path file)
            {
                return io(() -> store.publishRequiredAsset(file));
            }
            @Override public CompletableFuture<Path> download(RemoteAssetStore.AssetDescriptor asset)
            {
                return io(() -> {
                    assertEquals(asset, store.descriptor(asset.sha256()));
                    return store.materialize(asset.sha256(), directory.resolve("client-cache"), "png");
                });
            }
        };

        private Harness() throws IOException {}

        private <T> CompletableFuture<T> io(Callable<T> action)
        {
            return CompletableFuture.supplyAsync(() -> {
                try { return action.call(); }
                catch (Exception error) { throw new CompletionException(error); }
            }, worker).thenApplyAsync(value -> value, owner::add);
        }

        private RemoteTokens.State state() throws Exception
        {
            return RemoteTokens.state(await(host.query("list", RemoteTokens.json(new RemoteTokens.Stamp(-1)))));
        }

        private RemoteTokens.State change(RemoteTokens.Request request) throws Exception
        {
            return RemoteTokens.state(await(host.execute("change", RemoteTokens.json(request))));
        }

        private <T> T await(CompletableFuture<T> future) throws Exception
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < deadline)
            {
                Runnable task = owner.poll(20, TimeUnit.MILLISECONDS);
                if (task != null)
                    task.run();
            }
            return future.get(1, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception
        {
            host.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
