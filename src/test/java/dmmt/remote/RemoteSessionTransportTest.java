package dmmt.remote;

import dmmt.render.OverlayTextures;
import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionTransportTest {
    static final String BUILD = RemoteCompatibility.VERSION;
    static RemoteIdentity identity;
    @TempDir
    Path directory;

    @BeforeAll
    static void createIdentity() throws Exception {
        identity = RemoteIdentity.create();
    }

    @AfterEach
    void resetGlobals() {
        Tuning.reset();
        OverlayTextures.applySettings(key -> null);
    }

    @Test
    void newApprovalOnlyRequestReplacesAConnectedClientOnlyAfterHostApproval() throws Exception {
        try (var host = new Host(directory.resolve("replace.ini"));
             var first = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                     2000, "First desktop")) {
            host.approveWhenPending();
            await(() -> first.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            try (var second = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Second desktop")) {
                await(() -> host.pending() != null);
                assertEquals("Second desktop", host.pending().client().name());
                assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, first.phase());
                host.approve(host.pending().id());
                await(() -> second.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                await(() -> first.phase() != RemoteSessionClient.Phase.SETTINGS_READY);
            }
        }
    }

    @Test
    void approvalOnlyControlRequestExposesNoStateUntilApprovedAndUsesExactPinnedTls() throws Exception {
        try (var host = new Host(directory.resolve("approval-only.ini"));
             var client = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                     2000, "Discovered desktop")) {
            client.enableWorkspace();
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertNull(client.latestState());
            assertNull(client.latestWorkspace());
            assertFalse(client.canReconnect());
            var request = host.pending();
            assertEquals("Discovered desktop", request.client().name());
            assertEquals(InetAddress.getLoopbackAddress().getHostAddress(), request.client().address());
            try (var second = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Second desktop")) {
                await(() -> second.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertNull(second.latestState());
                assertEquals(request.id(), host.pending().id());
            }
            host.approve(request.id());
            await(() -> client.latestWorkspace() != null);
            assertEquals(HostSettingsAuthority.Status.ACCEPTED,
                    client.edit(request(client.latestState(), "0.6")).get(5, TimeUnit.SECONDS).status());
            var active = RemoteSessionClient.class.getDeclaredField("active");
            active.setAccessible(true);
            ((RemoteTlsChannel) active.get(client)).close();
            await(client::canReconnect);
            client.reconnect();
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            assertNull(host.pending(), "Reconnect reuses only this running session's existing grant.");
            host.owner.call(() -> { host.server.revoke(); return null; });
            await(client::canReconnect);
            client.reconnect();
            await(() -> client.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            assertTrue(client.lastError().contains("pair again"));
        }
        try (var host = new Host(directory.resolve("approval-wrong-pin.ini"));
             var client = RemoteSessionClient.requestControl(host.address(), "0".repeat(64), BUILD,
                     2000, "Wrong pin")) {
            await(() -> client.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            assertNull(host.pending());
            assertNull(client.latestState());
        }
    }

    @Test
    void approvalOnlyRejectsDifferentCompatibilityVersionBeforeApprovalOrStateTransfer() throws Exception {
        try (var host = new Host(directory.resolve("incompatible-version.ini"));
             var client = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(),
                     BUILD + "-incompatible", 2000, "Incompatible desktop")) {
            client.enableWorkspace();
            await(() -> client.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            assertNull(host.pending());
            assertNull(client.latestState());
            assertNull(client.latestWorkspace());
            assertFalse(client.canReconnect());
            assertTrue(client.lastError().contains("local compatibility version " + BUILD + "-incompatible"));
            assertTrue(client.lastError().contains("peer compatibility version " + BUILD));
        }
    }

    @Test
    void rememberedClientsAreAutoApprovedOrBlockedAndPersist() throws Exception {
        var trustDirectory = Files.createDirectory(directory.resolve("trust"));
        var trust = new RemoteClientTrustStore(trustDirectory);
        try (var host = new Host(directory.resolve("trust.ini"), trust)) {
            try (var first = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Always desktop")) {
                await(() -> host.pending() != null);
                var id = host.pending().id();
                host.owner.call(() -> { host.server.approveAlways(id); return null; });
                await(() -> first.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            }
            host.owner.call(() -> { host.server.revoke(); return null; });
            try (var second = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Always desktop")) {
                await(() -> second.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                assertNull(host.pending(), "A remembered client needs no host approval.");
            }
            host.owner.call(() -> { host.server.revoke(); return null; });
            try (var other = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Blocked desktop")) {
                await(() -> host.pending() != null);
                var id = host.pending().id();
                host.owner.call(() -> { host.server.block(id); return null; });
                await(() -> other.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            }
            try (var again = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Blocked desktop")) {
                await(() -> again.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertNull(host.pending(), "A blocked client never reaches the approval prompt.");
            }
        }
        var reloaded = new RemoteClientTrustStore(trustDirectory).entries();
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.stream().anyMatch(entry -> entry.name().equals("Always desktop")
                && entry.decision() == RemoteClientTrustStore.Decision.ALLOWED));
        assertTrue(reloaded.stream().anyMatch(entry -> entry.name().equals("Blocked desktop")
                && entry.decision() == RemoteClientTrustStore.Decision.BLOCKED));
        var store = new RemoteClientTrustStore(trustDirectory);
        store.forget(reloaded.get(0));
        assertEquals(1, new RemoteClientTrustStore(trustDirectory).entries().size());
    }

    @Test
    void approvalOnlyRequestsCanBeRejectedCancelledAndRequestedAgainWithoutTransferringSecrets() throws Exception {
        try (var host = new Host(directory.resolve("approval-reject.ini"))) {
            try (var rejected = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Rejected")) {
                await(() -> host.pending() != null);
                host.owner.call(() -> { host.server.reject(host.session.status().pending().id()); return null; });
                await(() -> rejected.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertNull(rejected.latestState());
            }
            UUID cancelled;
            try (var client = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Cancelled")) {
                await(() -> host.pending() != null);
                cancelled = host.pending().id();
            }
            await(() -> host.pending() == null);
            assertThrows(IllegalStateException.class, () -> host.approve(cancelled));
            try (var approved = RemoteSessionClient.requestControl(host.address(), identity.fingerprint(), BUILD,
                    2000, "Approved")) {
                host.approveWhenPending();
                await(() -> approved.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                assertNotNull(approved.latestState());
            }
        }
    }

    @Test
    void actualCoordinatorsPairWaitForHostApprovalAndSynchronizeSettingsWithoutTouchingClientIni() throws Exception {
        Path clientFile = directory.resolve("client.ini");
        Files.writeString(clientFile, "ui.gridOpacity = 0.3\n");
        var local = new AppSettings(clientFile);
        byte[] localBefore = Files.readAllBytes(clientFile);
        try (var host = new Host(directory.resolve("host.ini"));
             var connection = host.client(host.code())) {
            await(() -> connection.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertNull(connection.latestState());
            assertFalse(local.usesHostSettings());
            assertEquals(0.3, local.getDouble("ui.gridOpacity", 0));
            var pending = host.pending();
            assertEquals("Desktop B", pending.client().name());
            assertEquals(InetAddress.getLoopbackAddress().getHostAddress(), pending.client().address());
            host.approve(pending.id());
            await(() -> connection.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            try (var coordinator = new RemoteSettingsClient(local, connection.latestState(), notice -> {})) {
                local.putDouble("ui.gridOpacity", 0.6);
                assertEquals(1, coordinator.pendingCount());
                var result = connection.edit(coordinator.request()).get(5, TimeUnit.SECONDS);
                assertTrue(coordinator.receive(result));
                assertEquals(HostSettingsAuthority.Status.ACCEPTED, result.status());
                assertEquals(0.6, local.getDouble("ui.gridOpacity", 0));
                assertTrue(Files.readString(host.settings.getFile()).contains("ui.gridOpacity = 0.6"));
                assertArrayEquals(localBefore, Files.readAllBytes(clientFile));
                host.owner.call(() -> {
                    host.settings.applyEdit("ui.gridOpacity", "0.8");
                    return null;
                });
                await(() -> "0.8".equals(connection.latestState().settings().values().get("ui.gridOpacity")));
                coordinator.update(connection.latestState());
                assertEquals(0.8, local.getDouble("ui.gridOpacity", 0));
            }

            assertFalse(local.usesHostSettings());
            assertEquals(0.3, local.getDouble("ui.gridOpacity", 0));
            assertArrayEquals(localBefore, Files.readAllBytes(clientFile));
        }
    }

    @Test
    void authenticatedOperationsRecoverExactReceiptsOverTlsWithoutIdleQueries() throws Exception {
                try (var host = new Host(directory.resolve("operation-unapproved.ini")); var channel = host.raw()) {
                    channel.send(RemoteWire.message(RemoteWire.Type.OPERATION_QUERY,
                            new RemoteOperations.Query(null, "test", "list", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())));
                    assertEquals(RemoteWire.Type.ERROR, channel.receive().type());
                    assertEquals(0, host.operationQueries.get());
                }
                try (var host = new Host(directory.resolve("operation-approved.ini")); var client = host.client(host.code())) {
                    await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
                    host.approve(host.pending().id());
                    await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                    Thread.sleep(100);
                    assertEquals(0, host.operationQueries.get());
                    var state = client.operation(new RemoteOperations.Query(null, null, null, null)).get(5, TimeUnit.SECONDS);
                    var command = new RemoteOperations.Command(state.sessionId(), UUID.randomUUID(), state.nextSequence(),
                            "test", "write", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
                    var request = new RemoteOperations.Query(command, null, null, null);
                    var receipt = client.operation(request).get(5, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (receipt.status() == RemoteOperations.Status.RUNNING && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                        receipt = client.operation(request).get(5, TimeUnit.SECONDS);
                    }
                    assertEquals(RemoteOperations.Status.ACCEPTED, receipt.status());
                    assertTrue(Files.isRegularFile(host.operationFile));
                    var active = RemoteSessionClient.class.getDeclaredField("active");
                    active.setAccessible(true);
                    ((RemoteTlsChannel) active.get(client)).close();
                    await(client::canReconnect);
                    client.reconnect();
                    await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                    assertEquals(receipt, client.operation(request).get(5, TimeUnit.SECONDS));
                    assertEquals(1, host.operationsPerformed.get());
                    var query = client.operation(new RemoteOperations.Query(null, "test", "list",
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())).get(5, TimeUnit.SECONDS);
                    assertEquals(RemoteOperations.Status.ACCEPTED, query.status());
                    assertEquals(1, host.operationQueries.get());
                    assertEquals(receipt.nextSequence(), query.nextSequence());
                }
    }

    @Test
    void playerStateCannotBeQueriedBeforeAuthenticationAndApproval() throws Exception {
        try (var host = new Host(directory.resolve("unauthorized-player.ini")); var channel = host.raw()) {
            channel.send(RemoteWire.message(RemoteWire.Type.PLAYER_QUERY,
                    new RemotePlayerSession.Query(null, RemotePlayerSession.Pointer.empty(null))));
            var response = channel.receive();
            assertEquals(RemoteWire.Type.ERROR, response.type());
            assertEquals(RemoteProtocol.ErrorCode.INVALID_MESSAGE,
                    RemoteWire.payload(response, RemoteProtocol.Failure.class).code());
            assertEquals(0, host.playerCommands.get());
        }
    }

    @Test
    void handoutsCannotBeQueriedBeforeAuthenticationAndApproval() throws Exception {
        try (var host = new Host(directory.resolve("unauthorized-handouts.ini")); var channel = host.raw()) {
            channel.send(RemoteWire.message(RemoteWire.Type.HANDOUT_QUERY, new RemoteHandouts.Query(null)));
            assertEquals(RemoteWire.Type.ERROR, channel.receive().type());
            assertEquals(0, host.handoutCommands.get());
        }
    }

    @Test
    void mapCatalogRequiresApprovalAndReturnsOnlyBoundedHostMetadataOnDemand() throws Exception {
        try (var host = new Host(directory.resolve("unapproved-catalog.ini")); var channel = host.raw()) {
            channel.send(RemoteWire.message(RemoteWire.Type.MAP_CATALOG_QUERY, new RemoteProtocol.Empty()));
            assertEquals(RemoteWire.Type.ERROR, channel.receive().type());
            assertEquals(0, host.mapScans.get());
        }
        try (var host = new Host(directory.resolve("approved-catalog.ini")); var client = host.client(host.code())) {
            Files.writeString(host.mapRoot.resolve("Meadow.dmmap"), "Map contents must not be parsed or transferred");
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.mapCatalog().get(5, TimeUnit.SECONDS));
            assertEquals(0, host.mapScans.get());
            host.approve(host.pending().id());
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            assertEquals(0, host.mapScans.get(), "The idle connected transport must not poll or scan the catalog.");
            var listing = client.mapCatalog().get(5, TimeUnit.SECONDS);
            assertNull(listing.error());
            assertEquals(List.of("Library", "Meadow"), listing.nodes().stream().map(RemoteMapCatalog.Node::name).toList());
            assertEquals(1, host.mapScans.get());
            String json = RemoteWire.message(RemoteWire.Type.MAP_CATALOG, listing).payload().toString();
            assertFalse(json.contains("Map contents"));
            assertFalse(json.contains(host.mapRoot.getFileName().toString()));
            Files.delete(host.mapRoot.resolve("Meadow.dmmap"));
            Files.delete(host.mapRoot);
            var failed = client.mapCatalog().get(5, TimeUnit.SECONDS);
            assertNotNull(failed.error());
            assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, client.phase(), "A listing IO error must not disconnect the editor.");
        }
    }

    @Test
    void savesRequireApprovalPersistOnlyOnHostAndRecoverExactReceiptsOverTls() throws Exception {
        try (var host = new Host(directory.resolve("unapproved-save.ini")); var channel = host.raw()) {
            channel.send(RemoteWire.message(RemoteWire.Type.SAVE_QUERY, new RemoteSaves.Query(null)));
            assertEquals(RemoteWire.Type.ERROR, channel.receive().type());
            assertFalse(Files.exists(host.saveFile));
        }
        try (var host = new Host(directory.resolve("approved-save.ini")); var client = host.client(host.code())) {
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.saves(new RemoteSaves.Query(null)).get(5, TimeUnit.SECONDS));
            host.approve(host.pending().id());
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            var state = client.saves(new RemoteSaves.Query(null)).get(5, TimeUnit.SECONDS).state();
            var request = RemoteSavesTest.request(state);
            var saved = client.saves(new RemoteSaves.Query(request)).get(5, TimeUnit.SECONDS);
            assertEquals(RemotePlayerSession.Status.ACCEPTED, saved.receipt().status());
            assertTrue(Files.isRegularFile(host.saveFile));
            var active = RemoteSessionClient.class.getDeclaredField("active");
            active.setAccessible(true);
            ((RemoteTlsChannel) active.get(client)).close();
            await(client::canReconnect);
            client.reconnect();
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            var retry = client.saves(new RemoteSaves.Query(request)).get(5, TimeUnit.SECONDS);
            assertEquals(saved.receipt(), retry.receipt());
            assertEquals(1, host.savesPerformed.get());
            host.owner.call(() -> {
                host.project.getViews().setPlayerFrozen(true);
                host.workspace.observeHostCommit();
                return null;
            });
            var stale = new RemoteSaves.Request(state.sessionId(), state.contextId(), UUID.randomUUID(),
                    saved.state().nextSequence(), state.revision(), null, null);
            assertEquals(RemotePlayerSession.Status.CONFLICT,
                    client.saves(new RemoteSaves.Query(stale)).get(5, TimeUnit.SECONDS).receipt().status());
            assertEquals(1, host.savesPerformed.get());
        }
    }

    @Test
    void directoryMetadataRequiresApprovalAndUsesTheActualHostFilesystemWithoutFileContents() throws Exception {
        try (var host = new Host(directory.resolve("directory-host.ini")); var channel = host.raw()) {
            channel.send(RemoteWire.message(RemoteWire.Type.DIRECTORY_QUERY, new RemoteDirectories.Query(null, 0)));
            assertEquals(RemoteWire.Type.ERROR, channel.receive().type());
        }
        Path folders = Files.createDirectory(directory.resolve("host-folders"));
        Files.createDirectory(folders.resolve("Maps on A"));
        Files.writeString(folders.resolve("secret.txt"), "Do not transfer this content");
        try (var host = new Host(directory.resolve("directory-approved.ini")); var client = host.client(host.code())) {
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.directories(new RemoteDirectories.Query(folders.toString(), 0)).get(5, TimeUnit.SECONDS));
            host.approve(host.pending().id());
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            var result = client.directories(new RemoteDirectories.Query(folders.toString(), 0)).get(5, TimeUnit.SECONDS);
            assertNull(result.error());
            assertEquals(List.of("Maps on A"), result.entries().stream().map(RemoteDirectories.Entry::name).toList());
            assertEquals(folders.toRealPath().toString(), result.path());
            var missing = client.directories(new RemoteDirectories.Query(folders.resolve("missing").toString(), 0))
                    .get(5, TimeUnit.SECONDS);
            assertNotNull(missing.error());
            assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, client.phase(), "IO errors must not disconnect the editor.");
            client.close();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.directories(new RemoteDirectories.Query(null, 0)).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void folderConfirmationKeepsItsOriginalRevisionAcrossNewHostSnapshotsAndConflictsOverTls() throws Exception {
        var local = new AppSettings(directory.resolve("folder-client.ini"));
        byte[] before = Files.readAllBytes(local.getFile());
        try (var host = new Host(directory.resolve("folder-host.ini")); var client = host.client(host.code())) {
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            host.approve(host.pending().id());
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            try (var coordinator = new RemoteSettingsClient(local, client.latestState(), notice -> {})) {
                String original = local.get("library.folder", null);
                var confirmation = local.prepareHostEdit("library.folder");
                long revision = client.latestState().settings().revision();
                host.owner.call(() -> {
                    host.settings.applyEdit("library.folder", "temporary-host-folder");
                    host.settings.applyEdit("library.folder", original);
                    return null;
                });
                await(() -> client.latestState().settings().revision() > revision);
                coordinator.update(client.latestState());
                confirmation.accept(directory.resolve("chosen-on-host").toString());
                var result = client.edit(coordinator.request()).get(5, TimeUnit.SECONDS);
                assertEquals(HostSettingsAuthority.Status.CONFLICT, result.status());
                assertTrue(coordinator.receive(result));
                assertEquals(original, local.get("library.folder", null));
                assertEquals(original, host.owner.call(() -> host.settings.get("library.folder", null)));
                assertFalse(Files.exists(directory.resolve("chosen-on-host")));
            }
        }
        assertArrayEquals(before, Files.readAllBytes(local.getFile()));
    }

    @Test
    void handoutPngUploadsPresentationAndReceiptRecoveryUseApprovedTlsConnections() throws Exception {
        dmmt.FxTestSupport.startJavaFx();
        try (var host = new Host(directory.resolve("handouts.ini")); var client = host.client(host.code())) {
            client.enableHandouts();
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertNull(client.latestHandouts());
            host.approveWhenPending();
            await(() -> client.latestHandouts() != null && client.latestHandouts().ready());
            var localStore = new RemoteAssetStore(directory.resolve("handout-client"), 1_048_576, 16_777_216, 1_048_576);
            var image = new javafx.scene.image.WritableImage(20, 30);
            image.getPixelWriter().setArgb(2, 3, 0xffabcdef);
            var board = new RemoteHandouts.Artwork(localStore, directory.resolve("handout-publish")).publish(
                    new dmmt.ui.HandoutWindow.Snapshot(java.util.List.of(image), 270, true, true, java.util.List.of(0))).board();
            var listener = new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    identity.context(), BUILD, 2000, 2);
            try (var assets = new RemoteAssetServer(listener, host.session, host.owner, host.handoutStore)) {
                assets.start();
                var asset = board.images().getFirst();
                var transfer = client.transferAsset(assets.port(), localStore, RemoteAssetTransfer.Direction.UPLOAD, asset);
                assertEquals(asset, transfer.result().get(5, TimeUnit.SECONDS));
                transfer.stopped().get(5, TimeUnit.SECONDS);
                var state = client.latestHandouts();
                var command = new RemoteHandouts.Request(state.sessionId(), UUID.randomUUID(),
                        state.nextSequence(), state.revision(), board);
                var result = client.controlHandouts(command).get(5, TimeUnit.SECONDS);
                assertEquals(RemotePlayerSession.Status.ACCEPTED, result.receipt().status());
                assertEquals(board, client.latestHandouts().board());
                assertEquals(1, host.handoutCommands.get());
                assertEquals(0xffabcdef, host.owner.call(() -> host.handoutBoard.images().getFirst()
                        .getPixelReader().getArgb(2, 3)));
                var recent = client.latestHandouts().library().entries().getFirst();
                var stable = client.latestHandouts();
                Thread.sleep(150);
                assertSame(stable, client.latestHandouts(), "Unchanged heartbeats must not resend the recent library.");
                assertEquals(List.of(recent.id()), new dmmt.ui.RecentHandouts(
                        directory.resolve("handouts.ini-recents")).ids());
                var download = client.transferAsset(assets.port(), localStore, RemoteAssetTransfer.Direction.DOWNLOAD, recent.thumbnail());
                download.result().get(5, TimeUnit.SECONDS);
                download.stopped().get(5, TimeUnit.SECONDS);
                assertNotNull(new RemoteHandouts.Artwork(localStore, directory.resolve("thumbnail-cache")).loadThumbnail(recent.thumbnail()));
                var queuedDownloads = new java.util.ArrayList<java.util.concurrent.CompletableFuture<RemoteAssetStore.AssetDescriptor>>();
                for (int index = 0; index < 20; index++) {
                    queuedDownloads.add(client.transferQueued(assets.port(), localStore,
                            RemoteAssetTransfer.Direction.DOWNLOAD, recent.thumbnail()));
                }
                java.util.concurrent.CompletableFuture.allOf(
                        queuedDownloads.toArray(java.util.concurrent.CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
                for (var queued : queuedDownloads) assertEquals(recent.thumbnail(), queued.join());
                await(() -> client.transferProgress().isEmpty());
                var addState = client.latestHandouts();
                var add = new RemoteHandouts.Request(addState.sessionId(), UUID.randomUUID(), addState.nextSequence(),
                        addState.revision(), addState.board(), RemoteHandouts.Operation.ADD_RECENT, recent.id());
                assertEquals(RemotePlayerSession.Status.ACCEPTED, client.controlHandouts(add).get(5, TimeUnit.SECONDS).receipt().status());
                assertEquals(2, host.owner.call(() -> host.handoutBoard.images().size()));
                var removeState = client.latestHandouts();
                var remove = new RemoteHandouts.Request(removeState.sessionId(), UUID.randomUUID(), removeState.nextSequence(),
                        removeState.revision(), removeState.board(), RemoteHandouts.Operation.REMOVE_RECENT, recent.id());
                var removed = client.controlHandouts(remove).get(5, TimeUnit.SECONDS);
                assertEquals(RemotePlayerSession.Status.ACCEPTED, removed.receipt().status());
                assertTrue(client.latestHandouts().library().entries().isEmpty());
                assertEquals(2, host.owner.call(() -> host.handoutBoard.images().size()));
                assertTrue(new dmmt.ui.RecentHandouts(directory.resolve("handouts.ini-recents")).ids().isEmpty());
                var active = RemoteSessionClient.class.getDeclaredField("active");
                active.setAccessible(true);
                ((RemoteTlsChannel) active.get(client)).close();
                await(client::canReconnect);
                assertTrue(host.owner.call(() -> host.handoutBoard.shown()));
                client.reconnect();
                await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                assertEquals(result.receipt(), client.controlHandouts(command).get(5, TimeUnit.SECONDS).receipt());
                assertEquals(2, host.handoutCommands.get());
                assertEquals(removed.receipt(), client.controlHandouts(remove).get(5, TimeUnit.SECONDS).receipt());
                assets.stop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void playerOutputAndTransientPointersUseOnlyTheApprovedControlConnection() throws Exception {
        try (var host = new Host(directory.resolve("player-host.ini")); var client = host.client(host.code())) {
            client.enablePlayer();
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            assertNull(client.latestPlayer());
            host.approveWhenPending();
            await(() -> client.latestPlayer() != null);
            var state = client.latestPlayer();
            var command = RemotePlayerSessionTest.request(state, RemotePlayerSession.Action.WINDOW, true, 0);
            var result = client.controlPlayer(command).get(5, TimeUnit.SECONDS);
            assertEquals(RemotePlayerSession.Status.ACCEPTED, result.receipt().status());
            assertTrue(result.state().output().visible());
            assertEquals(result.receipt(), client.controlPlayer(command).get(5, TimeUnit.SECONDS).receipt());
            assertEquals(1, host.playerCommands.get());
            assertEquals("Host projector", result.state().output().monitors().getFirst().label());

            var point = new RemotePlayerSession.Point(125, 300);
            client.playerLaser(state.contextId(), point);
            client.playerPing(state.contextId(), point, 1000);
            await(() -> host.receivedPlayerPings.get() == 1);
            await(() -> point.equals(host.lastPointer.get().laser()));
            client.drainPlayerPointers();
            host.owner.call(() -> {
                host.player.ping(new RemotePlayerSession.Point(500, 600), 1000);
                return null;
            });
            var frames = new java.util.ArrayList<RemotePlayerSession.Pointer>();
            await(() -> {
                frames.addAll(client.drainPlayerPointers());
                return frames.stream().anyMatch(frame -> !frame.pings().isEmpty());
            });
            assertEquals(500, frames.stream().flatMap(frame -> frame.pings().stream()).findFirst().orElseThrow().point().x());

            java.lang.reflect.Field channel = RemoteSessionClient.class.getDeclaredField("active");
            channel.setAccessible(true);
            ((RemoteTlsChannel) channel.get(client)).close();
            await(client::canReconnect);
            await(() -> host.lastPointer.get().laser() == null);
            assertTrue(client.drainPlayerPointers().isEmpty());
            client.reconnect();
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            client.controlPlayer(command).get(5, TimeUnit.SECONDS);
            assertEquals(1, host.playerCommands.get());
            assertEquals(1, host.receivedPlayerPings.get());
            assertNull(host.lastPointer.get().laser());
        }
    }

    @Test
    void rejectsIncorrectCodeAndKeepsHostAvailableForANewPairingAttempt() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            String wrong = host.code().equals("00000000") ? "11111111" : "00000000";
            try (var rejected = host.client(wrong)) {
                await(() -> rejected.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertTrue(rejected.lastError().contains("incorrect"));
                assertNull(rejected.latestState());
                assertNull(host.pending());
            }
            try (var accepted = host.client(host.code())) {
                host.approveWhenPending();
                await(() -> accepted.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            }
        }
    }

    @Test
    void explicitHostRejectionAndCodeRegenerationNeverPublishSettings() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            try (var rejected = host.client(host.code())) {
                await(() -> host.pending() != null);
                host.owner.call(() -> {
                    host.server.reject(host.session.status().pending().id());
                    return null;
                });
                await(() -> rejected.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertTrue(rejected.lastError().contains("rejected"));
                assertNull(rejected.latestState());
            }
            try (var cancelled = host.client(host.code())) {
                await(() -> host.pending() != null);
                host.owner.call(host.session::regenerateCode);
                await(() -> cancelled.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertNull(cancelled.latestState());
            }
        }
    }

    @Test
    void cancelledPairingCannotBeApprovedLaterOrStealANewPairingChannel() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            UUID oldRequest;
            try (var client = host.client(host.code())) {
                await(() -> host.pending() != null);
                oldRequest = host.pending().id();
            }
            await(() -> host.pending() == null);
            assertThrows(IllegalStateException.class, () -> host.approve(oldRequest));
            try (var replacement = host.client(host.code())) {
                await(() -> host.pending() != null);
                assertThrows(IllegalStateException.class, () -> host.approve(oldRequest));
                host.approve(host.pending().id());
                await(() -> replacement.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            }
        }
    }

    @Test
    void additionalClientReceivesExplicitBusyWhileTheApprovedClientRemainsUsable() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"));
             var first = host.client(host.code())) {
            host.approveWhenPending();
            await(() -> first.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            try (var second = host.client("12345678")) {
                await(() -> second.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertTrue(second.lastError().contains("already"));
                assertNull(second.latestState());
                assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, first.phase());
                assertEquals(HostSettingsAuthority.Status.ACCEPTED,
                        first.edit(request(first.latestState(), "0.6")).get(5, TimeUnit.SECONDS).status());
            }
        }
    }

    @Test
    void settingsAndEditsAreNotReadableBeforeAuthenticationAndBadCredentialsDoNotStopTheServer() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            for (var type : new RemoteWire.Type[]{RemoteWire.Type.STATE, RemoteWire.Type.EDIT,
                    RemoteWire.Type.WORKSPACE_STATE, RemoteWire.Type.WORKSPACE_EDIT}) {
                try (var channel = host.raw()) {
                    var request = RemoteWire.message(type, new RemoteProtocol.Empty());
                    channel.send(request);
                    var response = channel.receive();
                    assertEquals(request.id(), response.id());
                    assertEquals(RemoteWire.Type.ERROR, response.type());
                    assertEquals(RemoteProtocol.ErrorCode.INVALID_MESSAGE,
                            RemoteWire.payload(response, RemoteProtocol.Failure.class).code());
                    assertFalse(response.payload().has("settings"));
                }
            }
            try (var channel = host.raw()) {
                channel.send(RemoteWire.message(RemoteWire.Type.CONNECT,
                        new RemoteProtocol.Connect(UUID.randomUUID(), "A".repeat(43))));
                var response = channel.receive();
                assertEquals(RemoteWire.Type.ERROR, response.type());
                assertEquals(RemoteProtocol.ErrorCode.UNAUTHORIZED,
                        RemoteWire.payload(response, RemoteProtocol.Failure.class).code());
            }
            try (var client = host.client(host.code())) {
                host.approveWhenPending();
                await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            }
        }
    }

    @Test
    void reconnectRetainsExactCommandReceiptsWithoutRollingBackNewerHostEdits() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            RemoteProtocol.Connect credential;
            HostSettingsAuthority.Request request;
            HostSettingsAuthority.Result original;
            try (var channel = host.raw()) {
                channel.send(RemoteWire.message(RemoteWire.Type.PAIR,
                        new RemoteProtocol.Pair(host.code(), "Desktop B")));
                var approval = RemoteWire.payload(channel.receive(), RemoteProtocol.Approval.class);
                host.approve(approval.requestId());
                channel.send(RemoteWire.message(RemoteWire.Type.APPROVAL,
                        new RemoteProtocol.ApprovalPoll(approval.requestId())));
                credential = RemoteWire.payload(channel.receive(), RemoteProtocol.Connect.class);
                channel.send(RemoteWire.message(RemoteWire.Type.CONNECT, credential));
                var baseline = RemoteWire.payload(channel.receive(), HostSettingsAuthority.State.class);
                channel.send(RemoteWire.message(RemoteWire.Type.STATE, new RemoteProtocol.StateQuery(baseline)));
                assertEquals(RemoteWire.Type.UNCHANGED, channel.receive().type());
                request = request(baseline, "0.6");
                channel.send(RemoteWire.message(RemoteWire.Type.EDIT, request));
                original = RemoteWire.payload(channel.receive(), HostSettingsAuthority.Result.class);
                assertEquals(HostSettingsAuthority.Status.ACCEPTED, original.status());
            }
            await(() -> host.owner.call(host.session::status).phase() == RemoteSessionAccess.Phase.DISCONNECTED);
            host.owner.call(() -> {
                host.settings.applyEdit("ui.gridOpacity", "0.8");
                return null;
            });
            try (var reconnected = host.raw()) {
                reconnected.send(RemoteWire.message(RemoteWire.Type.CONNECT, credential));
                assertEquals("0.8", RemoteWire.payload(reconnected.receive(), HostSettingsAuthority.State.class)
                        .settings().values().get("ui.gridOpacity"));
                reconnected.send(RemoteWire.message(RemoteWire.Type.EDIT, request));
                assertEquals(original, RemoteWire.payload(reconnected.receive(), HostSettingsAuthority.Result.class));
                reconnected.send(RemoteWire.message(RemoteWire.Type.STATE, new RemoteProtocol.StateQuery(null, 0, 0)));
                assertEquals("0.8", RemoteWire.payload(reconnected.receive(), HostSettingsAuthority.State.class)
                        .settings().values().get("ui.gridOpacity"));
            }
        }
    }

    @Test
    void revokeAndStopDisconnectWithoutResettingHostSettings() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"));
             var client = host.client(host.code())) {
            host.approveWhenPending();
            await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
            assertEquals(HostSettingsAuthority.Status.ACCEPTED,
                    client.edit(request(client.latestState(), "0.6")).get(5, TimeUnit.SECONDS).status());
            byte[] saved = Files.readAllBytes(host.settings.getFile());
            host.owner.call(() -> {
                host.server.revoke();
                return null;
            });
            await(() -> client.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            await(client::canReconnect);
            client.reconnect();
            await(() -> client.phase() == RemoteSessionClient.Phase.DISCONNECTED);
            assertTrue(client.lastError().contains("pair again"));
            assertArrayEquals(saved, Files.readAllBytes(host.settings.getFile()));
            host.server.stop().get(5, TimeUnit.SECONDS);
            assertEquals(RemoteSessionAccess.Phase.STOPPED, host.owner.call(host.session::status).phase());
            assertArrayEquals(saved, Files.readAllBytes(host.settings.getFile()));
        }
    }

    @Test
    void wrongPinsAndBuildsNeverTriggerHostApprovalOrReplaceLocalSettings() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"))) {
            try (var wrongPin = RemoteSessionClient.pair(host.address(), "0".repeat(64), BUILD, 2000,
                    host.code(), "Desktop B");
                 var wrongBuild = RemoteSessionClient.pair(host.address(), identity.fingerprint(), "other-build", 2000,
                         host.code(), "Desktop B")) {
                await(() -> wrongPin.phase() == RemoteSessionClient.Phase.DISCONNECTED
                        && wrongBuild.phase() == RemoteSessionClient.Phase.DISCONNECTED);
                assertNull(wrongPin.latestState());
                assertEquals("Remote connection lost or rejected. Verify the host fingerprint, address and remote compatibility versions.",
                        wrongPin.lastError());
                assertNull(wrongBuild.latestState());
                assertTrue(wrongBuild.lastError().contains("Incompatible remote application"));
                assertTrue(wrongBuild.lastError().contains("other-build"));
                assertTrue(wrongBuild.lastError().contains(BUILD));
                assertTrue(wrongBuild.lastError().contains("protocol"));
                assertNull(host.pending());
            }
        }
    }

    @Test
    void closeDuringApprovalEndsAccessAndDoesNotLeaveTheClientEditable() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"));
             var client = host.client(host.code())) {
            await(() -> client.phase() == RemoteSessionClient.Phase.AWAITING_APPROVAL);
            client.close();
            assertEquals(RemoteSessionClient.Phase.CLOSED, client.phase());
            assertNull(client.latestState());
            await(() -> host.pending() == null);
            assertThrows(IllegalStateException.class, client::reconnect);
        }
    }

    @Test
    void authenticatedWorkspaceFailureReachesClientAndClearsAfterHostRecovery() throws Exception {
        try (var host = new Host(directory.resolve("workspace-failure.ini"));
             var client = host.client(host.code())) {
            client.enableWorkspace();
            host.approveWhenPending();
            await(client::workspaceReady);
            var failure = RemoteFailure.capture("Host map artwork failed.",
                    new IOException("Cannot publish image.png", new java.nio.file.NoSuchFileException("image.png")));
            host.owner.call(() -> {
                host.workspaceReady = false;
                host.workspaceFailure = failure;
                return null;
            });
            await(() -> client.workspaceFailure() != null);
            assertEquals(failure, client.workspaceFailure());
            assertFalse(client.workspaceReady());
            assertTrue(client.workspaceStatus().contains("failed"));
            assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, client.phase());
            host.owner.call(() -> {
                host.workspaceFailure = null;
                host.workspaceReady = true;
                return null;
            });
            await(client::workspaceReady);
            assertNull(client.workspaceFailure());
        }
    }

    @Test
    void workspacePreparationPausesOnlyWorkspaceEditingAndThenResumesWithoutReconnecting() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"));
             var client = host.client(host.code())) {
            host.owner.call(() -> {
                host.workspaceReady = false;
                return null;
            });
            client.enableWorkspace();
            host.approveWhenPending();
            await(() -> client.workspaceStatus() != null);
            assertEquals(RemoteSessionClient.Phase.SETTINGS_READY, client.phase());
            assertFalse(client.workspaceReady());
            assertNull(client.latestWorkspace());
            assertEquals(HostSettingsAuthority.Status.ACCEPTED,
                    client.edit(request(client.latestState(), "0.6")).get(5, TimeUnit.SECONDS).status());
            host.owner.call(() -> {
                host.workspaceReady = true;
                return null;
            });
            await(client::workspaceReady);
            assertNull(client.workspaceStatus());
            assertNotNull(client.latestWorkspace());
            host.owner.call(() -> {
                host.workspaceReady = false;
                return null;
            });
            await(() -> !client.workspaceReady());
            var state = client.latestWorkspace();
            var edited = state.document().materialize(new dmmt.model.DmProject.CameraState());
            edited.getWeather().setType("SNOW");
            var paused = client.editWorkspace(
                    RemoteWorkspaceAuthority.diff(state, RemoteProjectDocument.capture(edited), "Weather"));
            assertTrue(paused.isCompletedExceptionally());
            host.owner.call(() -> {
                host.workspaceReady = true;
                return null;
            });
            await(client::workspaceReady);
            assertSame(state, client.latestWorkspace(), "An unchanged prepared baseline does not require a new snapshot.");
        }
    }

    @Test
    void completedShapesSynchronizeAndHostConflictsAreReportedThroughActualTransport() throws Exception {
        try (var host = new Host(directory.resolve("host.ini"));
             var client = host.client(host.code())) {
            client.enableWorkspace();
            host.approveWhenPending();
            await(() -> client.latestWorkspace() != null);
            var baseline = client.latestWorkspace();
            var project = baseline.document().materialize(dmmt.model.DmProject.CameraState.builder().build());
            project.getOverlays().add(dmmt.model.DmProject.OverlayShape.builder().id("remote-circle").x(40).build());
            var request = RemoteWorkspaceAuthority.diff(baseline, RemoteProjectDocument.capture(project), "Draw circle");
            var result = client.editWorkspace(request).get(5, TimeUnit.SECONDS);
            assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
            assertEquals(40, host.owner.call(() -> host.project.getOverlays().getFirst().getX()));
            assertEquals(result.state(), client.latestWorkspace());
            project.getOverlays().getFirst().setX(50);
            var stale = RemoteWorkspaceAuthority.diff(result.state(), RemoteProjectDocument.capture(project), "Move circle");
            host.owner.call(() -> {
                host.project.getOverlays().getFirst().setX(80);
                host.workspace.observeHostCommit();
                return null;
            });
            var rejected = client.editWorkspace(stale).get(5, TimeUnit.SECONDS);
            assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, rejected.status());
            assertEquals(80, host.owner.call(() -> host.project.getOverlays().getFirst().getX()));
            assertEquals(80, client.latestWorkspace().document().materialize(project.getViews().getDmCamera())
                    .getOverlays().getFirst().getX());
        }
    }

    private static HostSettingsAuthority.Request request(HostSettingsAuthority.State state, String value) {
        return new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                        "ui.gridOpacity", state.settings().values().get("ui.gridOpacity"), value));
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                fail("Remote session did not reach the expected state in time.");
            }
            Thread.sleep(10);
        }
    }

    static final class Host implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final RemoteOwnerDispatch owner = new RemoteOwnerDispatch(executor, 2000);
        final AppSettings settings;
        final RemoteHostSession session;
        final RemoteSessionServer server;
        final dmmt.model.DmProject project = dmmt.model.DmProject.builder().build();
        final RemoteWorkspaceAuthority workspace;
        final RemoteSaves saves;
        final RemoteMapCatalog maps;
        final RemoteOperations operations;
        final Path operationFile;
        final java.util.concurrent.atomic.AtomicInteger operationQueries = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger operationsPerformed = new java.util.concurrent.atomic.AtomicInteger();
        final Path mapRoot;
        final java.util.concurrent.atomic.AtomicInteger mapScans = new java.util.concurrent.atomic.AtomicInteger();
        final Path saveFile;
        final java.util.concurrent.atomic.AtomicInteger savesPerformed = new java.util.concurrent.atomic.AtomicInteger();
        final RemotePlayerSession player;
        final java.util.concurrent.ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
        final RemoteAssetStore handoutStore;
        final RemoteHandoutSession handouts;
        final java.util.concurrent.atomic.AtomicInteger handoutCommands = new java.util.concurrent.atomic.AtomicInteger();
        dmmt.ui.HandoutWindow.Snapshot handoutBoard = new dmmt.ui.HandoutWindow.Snapshot(
                java.util.List.of(), 0, false, false, null);
        final java.util.concurrent.atomic.AtomicReference<RemotePlayerSession.Pointer> lastPointer =
                new java.util.concurrent.atomic.AtomicReference<>(RemotePlayerSession.Pointer.empty(null));
        final java.util.concurrent.atomic.AtomicInteger receivedPlayerPings = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger playerCommands = new java.util.concurrent.atomic.AtomicInteger();
        boolean playerVisible, scaleTest;
        boolean workspaceReady = true;
        RemoteFailure workspaceFailure;

        Host(Path settingsFile) throws Exception {
            this(settingsFile, null);
        }

        Host(Path settingsFile, RemoteClientTrustStore trust) throws Exception {
            settings = owner.call(() -> new AppSettings(settingsFile));
            session = owner.call(() -> new RemoteHostSession(settings));
            handoutStore = new RemoteAssetStore(settingsFile.resolveSibling(settingsFile.getFileName() + "-handouts"),
                    1_048_576, 16_777_216, 1_048_576);
            handouts = owner.call(() -> new RemoteHandoutSession(() -> handoutBoard, snapshot -> {
                handoutCommands.incrementAndGet();
                handoutBoard = snapshot;
            }, new RemoteHandouts.Artwork(handoutStore, settingsFile.resolveSibling(settingsFile.getFileName() + "-cache")),
                    artworkWorker, executor, message -> fail(message)));
            owner.call(() -> {
                handouts.configureLibrary(new RemoteHandoutLibrary(
                        () -> new dmmt.ui.RecentHandouts(settingsFile.resolveSibling(settingsFile.getFileName() + "-recents")),
                        handoutStore, settingsFile.resolveSibling(settingsFile.getFileName() + "-recent-cache")), () -> 100);
                return null;
            });
            workspace = owner.call(() -> new RemoteWorkspaceAuthority(() -> project,
                    commit -> RemoteProjectChanges.apply(project, commit.after(), commit.targets())));
            operationFile = settingsFile.resolveSibling(settingsFile.getFileName() + "-operation");
            operations = owner.call(() -> {
                var endpoint = new RemoteOperations(executor);
                endpoint.register("test", new RemoteOperations.Endpoint() {
                    @Override public java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(
                            String action, com.fasterxml.jackson.databind.JsonNode payload) {
                        operationQueries.incrementAndGet();
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
                    }
                    @Override public java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode> execute(
                            String action, com.fasterxml.jackson.databind.JsonNode payload) {
                        operationsPerformed.incrementAndGet();
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                Files.writeString(operationFile, "Written on A");
                                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                            } catch (IOException failure) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                        }, artworkWorker);
                    }
                });
                return endpoint;
            });
            mapRoot = Files.createDirectory(settingsFile.resolveSibling(settingsFile.getFileName() + "-maps"));
            var mapLibrary = new dmmt.service.MapLibraryService(mapRoot, new dmmt.service.ProjectService()) {
                @Override public Entry scanRemote() throws IOException {
                    mapScans.incrementAndGet();
                    return super.scanRemote();
                }
            };
            maps = owner.call(() -> new RemoteMapCatalog(() -> new RemoteMapCatalog.Source(
                    mapLibrary, null, "Unsaved new map", workspace.state().contextId()), artworkWorker, executor));
            saveFile = settingsFile.resolveSibling(settingsFile.getFileName() + "-saved.dmmap");
            saves = owner.call(() -> new RemoteSaves(workspace::state,
                    () -> new RemoteSaves.Target(true, "Host map", settingsFile.getParent().toString()),
                    () -> workspaceReady, request -> {
                        savesPerformed.incrementAndGet();
                        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                new dmmt.service.ProjectService().save(saveFile, new dmmt.model.DmProject());
                                return "Saved on A.";
                            } catch (IOException failure) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                        }, artworkWorker);
                    }, executor));
            player = owner.call(() -> new RemotePlayerSession(() -> workspace.state().contextId(),
                    () -> new RemotePlayerSession.Output(playerVisible, scaleTest,
                            playerVisible ? 1920 : 0, playerVisible ? 1080 : 0, 0,
                            java.util.List.of(new RemotePlayerSession.Monitor("Host projector", 1920, 1080, 1, 1, 96))),
                    () -> null, pointer -> {
                        lastPointer.set(pointer);
                        receivedPlayerPings.addAndGet(pointer.pings().size());
                    }, request -> {
                        playerCommands.incrementAndGet();
                        switch (request.action()) {
                            case WINDOW -> playerVisible = request.enabled();
                            case SCALE_TEST -> scaleTest = request.enabled();
                            case SCREEN -> assertEquals(0, request.screenIndex());
                        }
                    }, () -> true));
            var listener = new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    identity.context(), BUILD, 2000, 2);
            server = new RemoteSessionServer(listener, session, owner, new RemoteWorkspaceEndpoint() {
                @Override public RemoteFailure failure() { return workspaceFailure; }
                @Override public RemoteOperations operations() { return operations; }
                @Override public RemoteMapCatalog maps() { return maps; }
                @Override public RemoteSaves saves() { return saves; }
                @Override public RemotePlayerSession player() { return player; }
                @Override public RemoteHandoutSession handouts() { return handouts; }
                @Override
                public boolean ready() {
                    return workspaceReady;
                }

                @Override
                public RemoteWorkspaceAuthority.State state() {
                    return workspace.state();
                }

                @Override
                public RemoteWorkspaceAuthority.Result execute(RemoteWorkspaceAuthority.Request request) {
                    return workspace.execute(request);
                }
            }, trust);
            server.start();
        }

        String code() throws IOException {
            return owner.call(() -> session.pairingCode().value());
        }

        RemoteSessionAccess.ApprovalRequest pending() throws IOException {
            return owner.call(() -> session.status().pending());
        }

        void approve(UUID id) throws IOException {
            owner.call(() -> {
                server.approve(id);
                return null;
            });
        }

        void approveWhenPending() throws Exception {
            await(() -> pending() != null);
            approve(pending().id());
        }

        RemoteSessionClient client(String code) {
            return RemoteSessionClient.pair(address(), identity.fingerprint(), BUILD, 2000, code, "Desktop B");
        }

        RemoteTlsChannel raw() throws Exception {
            return RemoteTlsChannel.connect(address(), RemoteTls.clientContext(identity.fingerprint()), BUILD, 2000);
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port());
        }

        @Override
        public void close() throws Exception {
            try {
                server.stop().get(5, TimeUnit.SECONDS);
                owner.call(() -> { operations.close(); handouts.close(); saves.close(); maps.close(); return null; });
            } finally {
                artworkWorker.shutdown();
                assertTrue(artworkWorker.awaitTermination(5, TimeUnit.SECONDS));
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
