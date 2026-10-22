package dmmt.remote;

import dmmt.model.DmProject;
import dmmt.render.OverlayTextures;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionLifecycleTest {
    @TempDir Path directory;

    @AfterEach
    void resetGlobals() {
        Tuning.reset();
        OverlayTextures.applySettings(key -> null);
    }

    @Test
    void discoveryExistsOnlyWithinExplicitHostingAndIsJoinedBeforeStopCompletes() throws Exception {
        try (var host = new Host()) {
            assertNull(host.discovery.get());
            var details = host.start().get(10, TimeUnit.SECONDS);
            int port = host.discoveryPort.get();
            assertTrue(port > 0);
            try (var search = new RemoteDiscovery.Search()) {
                var results = search.discover(java.util.List.of(new RemoteDiscovery.Target(details.bindAddress(),
                        new java.net.InetSocketAddress(details.bindAddress(), port))), 200);
                assertEquals(java.util.List.of(new RemoteDiscovery.Host(details.name(), details.bindAddress(),
                        details.controlPort(), details.assetPort(), details.build(), details.fingerprint())), results);
            }
            var responder = host.discovery.get();
            host.stop().get(10, TimeUnit.SECONDS);
            assertTrue(responder.stopped().isDone());
            assertFalse(host.owner.call(host.controller::active));
            try (var rebound = new java.net.DatagramSocket(new java.net.InetSocketAddress(details.bindAddress(), port))) {
                assertEquals(port, rebound.getLocalPort());
            }
            host.start().get(10, TimeUnit.SECONDS);
            assertNotSame(responder, host.discovery.get());
        }
    }

    @Test
    void discoveryFailureIsVisibleButDoesNotStopTheEncryptedHostOrManualPairing() throws Exception {
        try (var host = new Host(true); var client = new Client()) {
            var details = host.start().get(10, TimeUnit.SECONDS);
            assertTrue(host.owner.call(host.controller::discoveryMessage).contains("unavailable"));
            assertEquals(RemoteHostingController.Phase.WAITING, host.owner.call(() -> host.controller.status().phase()));
            var ready = client.connect(details, host.owner.call(() -> host.controller.pairingCode().value()));
            await(() -> host.owner.call(() -> host.controller.status().phase())
                    == RemoteHostingController.Phase.AWAITING_APPROVAL);
            host.owner.call(() -> {
                host.controller.approve(host.controller.status().access().pending().id());
                return null;
            });
            await(() -> { client.pulse(); return ready.isDone(); });
            ready.get(10, TimeUnit.SECONDS);
            assertEquals(RemoteConnectionController.Phase.CONNECTED, client.phase());
        }
    }

    @Test
    void discoveredApprovalOnlyLifecycleRepeatsAfterHostRestartWithoutCodesOrStoredTrust() throws Exception {
        try (var host = new Host(); var client = new Client()) {
            byte[] originalIni = Files.readAllBytes(client.preferences.getFile());
            String previousFingerprint = null;
            for (int iteration = 0; iteration < 2; iteration++) {
                var details = host.start().get(10, TimeUnit.SECONDS);
                if (previousFingerprint != null) assertNotEquals(previousFingerprint, details.fingerprint());
                previousFingerprint = details.fingerprint();
                try (var search = new RemoteDiscovery.Search()) {
                    var found = search.discover(java.util.List.of(new RemoteDiscovery.Target(details.bindAddress(),
                            new java.net.InetSocketAddress(details.bindAddress(), host.discoveryPort.get()))), 200);
                    assertEquals(details.fingerprint(), found.getFirst().fingerprint());
                }
                int installsBefore = client.installs();
                var ready = client.connect(details, null);
                await(() -> {
                    client.pulse();
                    return host.owner.call(() -> host.controller.status().phase())
                            == RemoteHostingController.Phase.AWAITING_APPROVAL;
                });
                assertEquals(installsBefore, client.installs());
                assertFalse(client.owner.call(() -> client.preferences.usesHostSettings()));
                host.owner.call(() -> {
                    host.controller.approve(host.controller.status().access().pending().id());
                    return null;
                });
                await(() -> { client.pulse(); return ready.isDone(); });
                ready.get(10, TimeUnit.SECONDS);
                assertEquals(RemoteConnectionController.Phase.CONNECTED, client.phase());
                client.disconnect().get(10, TimeUnit.SECONDS);
                assertSame(client.local, client.owner.call(client.project::get));
                assertArrayEquals(originalIni, Files.readAllBytes(client.preferences.getFile()));
                host.stop().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void pairedLifecycleLoadsAssetsBeforeConnectedAndRevokePausesWithoutReplacingTheLocalWorkspace() throws Exception {
        try (var host = new Host(); var client = new Client()) {
            Files.write(directory.resolve("map.png"), new byte[]{3, 5, 8});
            host.owner.call(() -> {
                host.project.getMap().setImagePath(directory.resolve("map.png").toString());
                return null;
            });
            var details = host.start().get(10, TimeUnit.SECONDS);
            String code = host.owner.call(() -> host.controller.pairingCode().value());
            byte[] originalIni = Files.readAllBytes(client.preferences.getFile());
            var ready = client.connect(details, code);
            await(() -> {
                client.pulse();
                return host.owner.call(() -> host.controller.status().phase())
                        == RemoteHostingController.Phase.AWAITING_APPROVAL;
            });
            assertEquals(0, client.installs());
            assertFalse(client.owner.call(() -> client.preferences.usesHostSettings()));
            host.owner.call(() -> {
                var access = host.controller.status().access();
                host.controller.approve(access.pending().id());
                return null;
            });
            await(() -> {
                client.pulse();
                return ready.isDone();
            });
            ready.get(10, TimeUnit.SECONDS);
            assertEquals(RemoteConnectionController.Phase.CONNECTED, client.phase());
            assertEquals(1, client.installs());
            assertTrue(client.owner.call(() -> Files.isRegularFile(Path.of(client.project.get().getMap().getImagePath()))));
            assertEquals(800, client.owner.call(() -> client.project.get().getViews().getDmCamera().getX()));
            client.owner.call(() -> {
                client.project.get().getWeather().setType("SNOW");
                client.replica.committed("Snow");
                return null;
            });
            await(() -> {
                client.pulse();
                return host.owner.call(() -> "SNOW".equals(host.project.getWeather().getType()));
            });
            await(() -> {
                client.pulse();
                return client.owner.call(() -> client.replica.canEdit());
            });
            client.owner.call(() -> {
                client.preferences.applyEdit("ui.gridOpacity", "0.6");
                return null;
            });
            await(() -> {
                client.pulse();
                return host.owner.call(() -> "0.6".equals(host.preferences.get("ui.gridOpacity", null)));
            });
            host.owner.call(() -> {
                host.controller.revoke();
                return null;
            });
            await(() -> {
                client.pulse();
                return client.phase() == RemoteConnectionController.Phase.PAUSED;
            });
            assertFalse(client.owner.call(() -> client.replica.canEdit()));
            assertNotSame(client.local, client.owner.call(client.project::get));
            client.disconnect().get(10, TimeUnit.SECONDS);
            assertSame(client.local, client.owner.call(client.project::get));
            assertFalse(client.owner.call(() -> client.preferences.usesHostSettings()));
            assertArrayEquals(originalIni, Files.readAllBytes(client.preferences.getFile()));
            assertEquals("SNOW", host.owner.call(() -> host.project.getWeather().getType()));
            assertTrue(host.owner.call(() -> host.controller.active()));
            host.stop().get(10, TimeUnit.SECONDS);
            assertFalse(host.owner.call(() -> host.controller.active()));
            assertPortsReleased(details);
        }
    }

    @Test
    void cancellingDuringApprovalNeverInstallsAWorkspaceOrChangesLocalSettings() throws Exception {
        try (var host = new Host(); var client = new Client()) {
            var details = host.start().get(10, TimeUnit.SECONDS);
            var ready = client.connect(details, host.owner.call(() -> host.controller.pairingCode().value()));
            await(() -> {
                client.pulse();
                return host.owner.call(() -> host.controller.status().phase())
                        == RemoteHostingController.Phase.AWAITING_APPROVAL;
            });
            client.disconnect().get(10, TimeUnit.SECONDS);
            assertTrue(ready.isCompletedExceptionally());
            assertEquals(0, client.installs());
            assertSame(client.local, client.owner.call(client.project::get));
            assertFalse(client.owner.call(() -> client.preferences.usesHostSettings()));
            await(() -> host.owner.call(() -> host.controller.status().access().pending() == null));
        }
    }

    @Test
    void droppedControlChannelReconnectsToTheSameReplicaAndObservesHostEditsMadeWhileDisconnected() throws Exception {
        try (var host = new Host(); var client = new Client()) {
            var details = host.start().get(10, TimeUnit.SECONDS);
            var ready = client.connect(details, host.owner.call(() -> host.controller.pairingCode().value()));
            await(() -> host.owner.call(() -> host.controller.status().phase())
                    == RemoteHostingController.Phase.AWAITING_APPROVAL);
            host.owner.call(() -> {
                host.controller.approve(host.controller.status().access().pending().id());
                return null;
            });
            await(() -> {
                client.pulse();
                return ready.isDone();
            });
            ready.get(10, TimeUnit.SECONDS);
            var originalReplica = client.owner.call(() -> client.replica);
            host.owner.call(() -> {
                // Drop the actual TCP transport without revoking the session credential.
                var attempt = field(host.controller, "attempt");
                var server = field(attempt, "control");
                var channel = (AtomicReference<?>) field(server, "active");
                ((RemoteTlsChannel) channel.get()).close();
                return null;
            });
            await(() -> {
                client.pulse();
                return client.phase() == RemoteConnectionController.Phase.PAUSED
                        && client.owner.call(() -> client.controller.status().canReconnect());
            });
            host.owner.call(() -> {
                host.project.getWeather().setType("RAIN");
                host.bridge.committed();
                host.preferences.applyEdit("ui.gridOpacity", "0.7");
                return null;
            });
            client.owner.call(() -> {
                client.controller.reconnect();
                return null;
            });
            await(() -> {
                client.pulse();
                return client.phase() == RemoteConnectionController.Phase.CONNECTED
                        && client.owner.call(() -> "RAIN".equals(client.project.get().getWeather().getType())
                        && "0.7".equals(client.preferences.get("ui.gridOpacity", null)));
            });
            assertSame(originalReplica, client.owner.call(() -> client.replica));
            assertEquals(1, client.installs());
            assertEquals(0, client.owner.call(() -> client.restores));
        }
    }

    @Test
    void secondListenerFailureClosesTheFirstPortWithoutInstallingTheHostWorkspace() throws Exception {
        try (var host = new Host();
             var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int controlPort;
            try (var candidate = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                controlPort = candidate.getLocalPort();
            }
            int selected = controlPort;
            var failed = host.owner.call(() -> host.controller.start(host.configuration(selected, occupied.getLocalPort())));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
            await(() -> !host.owner.call(() -> host.controller.active()));
            assertEquals(0, host.owner.call(() -> host.installs));
            try (var probe = new ServerSocket(selected, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(selected, probe.getLocalPort());
            }
            var replacement = host.start().get(10, TimeUnit.SECONDS);
            host.stop().get(10, TimeUnit.SECONDS);
            assertPortsReleased(replacement);
        }
    }

    @Test
    void stopBeforeQueuedPreparationPreventsLateListenerOrReplicaInstallation() throws Exception {
        var worker = new ArrayDeque<Runnable>();
        var owner = new ArrayDeque<Runnable>();
        var settings = new AppSettings(directory.resolve("cancelled.ini"));
        var installs = new java.util.concurrent.atomic.AtomicInteger();
        var host = new RemoteHostingController(settings, new RemoteHostingController.Workspace() {
            @Override public RemoteWorkspaceEndpoint open(RemoteAssetStore store, Path cache, Executor executor) {
                installs.incrementAndGet();
                throw new AssertionError("A cancelled startup must not install a workspace.");
            }
            @Override public void close() { fail("No workspace was installed."); }
        }, owner::addLast, worker::addLast);
        var ready = host.start(new RemoteHostingController.Configuration("Cancelled", InetAddress.getLoopbackAddress(),
                0, 0, 2000, storage("cancelled-host")));
        var stopped = host.stop();
        assertFalse(stopped.isDone());
        runWorker(worker.removeFirst());
        while (!owner.isEmpty()) owner.removeFirst().run();
        assertTrue(stopped.isDone());
        assertTrue(ready.isCompletedExceptionally());
        assertEquals(0, installs.get());
        assertEquals(RemoteHostingController.Phase.STOPPED, host.status().phase());

        var client = new RemoteConnectionController(new RemoteConnectionController.Workspace() {
            @Override public RemoteWorkspaceReplica open(RemoteWorkspaceReplica.Connection connection,
                    RemoteSettingsLink.Connection link, RemoteAssetStore store, Path cache, Executor executor) {
                throw new AssertionError("A cancelled client must not install a replica.");
            }
            @Override public void close() { fail("No replica was installed."); }
        }, owner::addLast, worker::addLast);
        var clientReady = client.connect(new RemoteConnectionController.Configuration("127.0.0.1", 12345, 12346,
                "a".repeat(64), "B", "A", 2000, storage("cancelled-client")), "12345678");
        var clientStopped = client.disconnect();
        runWorker(worker.removeFirst());
        while (!owner.isEmpty()) owner.removeFirst().run();
        assertTrue(clientStopped.isDone());
        assertTrue(clientReady.isCompletedExceptionally());
        assertEquals(RemoteConnectionController.Phase.STANDALONE, client.status().phase());
    }

    @Test
    void failedClientInstallationRestoresItsContextAndClosesItsTransport() throws Exception {
        try (var host = new Host(); var client = new Client()) {
            client.failInstall = true;
            var details = host.start().get(10, TimeUnit.SECONDS);
            var ready = client.connect(details, host.owner.call(() -> host.controller.pairingCode().value()));
            await(() -> host.owner.call(() -> host.controller.status().phase())
                    == RemoteHostingController.Phase.AWAITING_APPROVAL);
            host.owner.call(() -> {
                host.controller.approve(host.controller.status().access().pending().id());
                return null;
            });
            await(() -> {
                client.pulse();
                return ready.isDone() && !client.owner.call(() -> client.controller.active());
            });
            assertTrue(ready.isCompletedExceptionally());
            assertSame(client.local, client.owner.call(client.project::get));
            assertFalse(client.owner.call(() -> client.preferences.usesHostSettings()));
            assertEquals(1, client.owner.call(() -> client.restores));
            assertEquals(RemoteConnectionController.Phase.ERROR, client.phase());
            assertTrue(client.owner.call(() -> client.controller.status().message().contains("local workspace changed")));
        }
    }

    private RemoteSessionStorage storage(String name) {
        return new RemoteSessionStorage(directory.resolve(name), 1024, 8192, 2048);
    }

    private final class Host implements AutoCloseable {
        final Owner owner = new Owner("test-host-owner");
        final java.util.concurrent.ExecutorService worker = Executors.newFixedThreadPool(2);
        final AppSettings preferences;
        final DmProject project = new DmProject();
        final RemoteHostingController controller;
        final AtomicReference<RemoteDiscovery.Responder> discovery = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger discoveryPort = new java.util.concurrent.atomic.AtomicInteger();
        RemoteWorkspaceHost bridge;
        int installs;

        Host() throws Exception {
            this(false);
        }

        Host(boolean failDiscovery) throws Exception {
            preferences = owner.call(() -> new AppSettings(directory.resolve("host.ini")));
            controller = owner.call(() -> new RemoteHostingController(preferences, new RemoteHostingController.Workspace() {
                @Override public RemoteWorkspaceEndpoint open(RemoteAssetStore store, Path cache, Executor executor) {
                    installs++;
                    bridge = new RemoteWorkspaceHost(() -> project, () -> null, java.util.Set::of,
                            commit -> RemoteProjectChanges.apply(project, commit.after(), commit.targets()),
                            store, cache, owner.executor, executor, message -> fail(message));
                    return bridge;
                }
                @Override public void close() {
                    bridge.close();
                    bridge = null;
                }
            }, owner.executor, worker, details -> {
                if (failDiscovery) throw new IOException("Simulated blocked UDP discovery.");
                var socket = new java.net.DatagramSocket(new java.net.InetSocketAddress(details.bindAddress(), 0));
                discoveryPort.set(socket.getLocalPort());
                var responder = new RemoteDiscovery.Responder(socket, new RemoteDiscovery.Host(
                        details.name(), details.bindAddress(), details.controlPort(), details.assetPort(), details.build(), details.fingerprint()));
                discovery.set(responder);
                return responder;
            }));
        }

        RemoteHostingController.Configuration configuration(int control, int assets) {
            return new RemoteHostingController.Configuration("Table A", InetAddress.getLoopbackAddress(),
                    control, assets, 3000, storage("host-store"));
        }
        CompletableFuture<RemoteHostingController.Details> start() throws Exception {
            return owner.call(() -> controller.start(configuration(0, 0)));
        }
        CompletableFuture<Void> stop() throws Exception { return owner.call(controller::stop); }
        @Override public void close() throws Exception {
            try { stop().get(10, TimeUnit.SECONDS); }
            finally {
                worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
                owner.close();
            }
        }
    }

    private final class Client implements AutoCloseable {
        final Owner owner = new Owner("test-client-owner");
        final java.util.concurrent.ExecutorService worker = Executors.newFixedThreadPool(2);
        final DmProject local = new DmProject();
        final AtomicReference<DmProject> project = new AtomicReference<>(local);
        final AppSettings preferences;
        final RemoteConnectionController controller;
        RemoteSettingsLink settings;
        RemoteWorkspaceReplica replica;
        int installs;
        int restores;
        volatile boolean failInstall;

        Client() throws Exception {
            local.getViews().getDmCamera().setX(800);
            preferences = owner.call(() -> new AppSettings(directory.resolve("client.ini")));
            controller = owner.call(() -> new RemoteConnectionController(new RemoteConnectionController.Workspace() {
                @Override public RemoteWorkspaceReplica open(RemoteWorkspaceReplica.Connection connection,
                        RemoteSettingsLink.Connection link, RemoteAssetStore store, Path cache, Executor executor) {
                    installs++;
                    settings = new RemoteSettingsLink(preferences, link, () -> {}, message -> {}, owner.executor);
                    if (failInstall) throw new RemoteConnectionController.LocalWorkspaceChangedException();
                    replica = new RemoteWorkspaceReplica(connection, store, cache, project::get, () -> false, () -> {},
                            shown -> project.set(shown.project()), message -> {}, owner.executor, executor);
                    return replica;
                }
                @Override public void close() {
                    restores++;
                    if (replica != null) { replica.close(); replica = null; }
                    if (settings != null) { settings.close(); settings = null; }
                    project.set(local);
                }
            }, owner.executor, worker));
        }

        CompletableFuture<Void> connect(RemoteHostingController.Details details, String code) throws Exception {
            return owner.call(() -> controller.connect(new RemoteConnectionController.Configuration(
                    details.bindAddress().getHostAddress(), details.controlPort(), details.assetPort(),
                    details.fingerprint(), "Desktop B", details.name(), 3000, storage("client-store")), code));
        }
        void pulse() throws Exception {
            owner.call(() -> {
                if (settings != null) settings.tick();
                controller.tick();
                return null;
            });
        }
        int installs() throws Exception { return owner.call(() -> installs); }
        RemoteConnectionController.Phase phase() throws Exception { return owner.call(() -> controller.status().phase()); }
        CompletableFuture<Void> disconnect() throws Exception { return owner.call(controller::disconnect); }
        @Override public void close() throws Exception {
            try { disconnect().get(10, TimeUnit.SECONDS); }
            finally {
                worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
                owner.close();
            }
        }
    }

    private static final class Owner implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor;
        Owner(String name) { executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name).factory()); }
        <T> T call(Callable<T> action) throws Exception { return executor.submit(action).get(10, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void assertPortsReleased(RemoteHostingController.Details details) throws IOException {
        try (var control = new ServerSocket(details.controlPort(), 1, details.bindAddress());
             var assets = new ServerSocket(details.assetPort(), 1, details.bindAddress())) {
            assertEquals(details.controlPort(), control.getLocalPort());
            assertEquals(details.assetPort(), assets.getLocalPort());
        }
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void runWorker(Runnable work) throws InterruptedException {
        var thread = Thread.ofPlatform().start(work);
        thread.join(10_000);
        assertFalse(thread.isAlive());
    }

    private static void await(Condition condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.get()) {
            if (System.nanoTime() > end) fail("Remote lifecycle did not reach the expected state.");
            Thread.sleep(10);
        }
    }

    private interface Condition { boolean get() throws Exception; }
}
