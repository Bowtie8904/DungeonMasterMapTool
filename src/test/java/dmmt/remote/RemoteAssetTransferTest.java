package dmmt.remote;

import dmmt.service.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAssetTransferTest {
    @TempDir
    Path directory;

    @Test
    void uploadsAndDownloadsVerifiedAssetsOnSeparateAuthenticatedTlsConnections() throws Exception {
        try (var host = new Host()) {
            RemoteAssetStore clientStore = store("client");
            byte[] bytes = new byte[RemoteAssetTransfer.CHUNK_BYTES * 3 + 41];
            new java.util.Random(42).nextBytes(bytes);
            Path original = directory.resolve("original.png");
            Files.write(original, bytes);
            var descriptor = clientStore.publishRequiredAsset(original);
            try (var upload = host.transfer(clientStore, RemoteAssetTransfer.Direction.UPLOAD, descriptor)) {
                assertEquals(descriptor, upload.result().get(10, TimeUnit.SECONDS));
                assertEquals(bytes.length, upload.transferredBytes());
                assertEquals(descriptor, host.store.descriptor(descriptor.sha256()));
            }
            Files.delete(original);
            RemoteAssetStore second = store("second-client-cache");
            try (var download = host.transfer(second, RemoteAssetTransfer.Direction.DOWNLOAD, descriptor)) {
                assertEquals(descriptor, download.result().get(10, TimeUnit.SECONDS));
                assertEquals(bytes.length, download.transferredBytes());
                Path cached = second.materialize(descriptor.sha256(), directory.resolve("render-cache"), "png");
                assertArrayEquals(bytes, Files.readAllBytes(cached));
            }
            try (var duplicate = host.transfer(clientStore, RemoteAssetTransfer.Direction.UPLOAD, descriptor)) {
                assertEquals(descriptor, duplicate.result().get(10, TimeUnit.SECONDS));
                assertEquals(0, duplicate.transferredBytes());
            }
            host.server.close();
            try (var cached = host.transfer(second, RemoteAssetTransfer.Direction.DOWNLOAD, descriptor)) {
                assertEquals(descriptor, cached.result().get(10, TimeUnit.SECONDS));
                assertEquals(0, cached.transferredBytes());
            }
        }
    }

    @Test
    void rejectsUnauthenticatedRequestsAndRevokedCredentialsWithoutExposingAssets() throws Exception {
        try (var host = new Host()) {
            var descriptor = host.store.publishRequiredAsset(Files.write(directory.resolve("image"), new byte[]{1, 2, 3}));
            try (var raw = host.raw()) {
                raw.send(RemoteWire.message(RemoteWire.Type.TRANSFER_OPEN,
                        new RemoteAssetTransfer.Open(new RemoteProtocol.Connect(host.credential.sessionId(),
                                "A".repeat(43)), RemoteAssetTransfer.Direction.DOWNLOAD, descriptor)));
                assertEquals(RemoteWire.Type.ERROR, raw.receive().type());
            }
            host.owner.call(() -> {
                host.session.revoke();
                return null;
            });
            try (var rejected = host.transfer(store("denied"), RemoteAssetTransfer.Direction.DOWNLOAD, descriptor)) {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> rejected.result().get(10, TimeUnit.SECONDS));
            }
            assertEquals(descriptor, host.store.descriptor(descriptor.sha256()));
        }
    }

    @Test
    void corruptOrInterruptedUploadsNeverPublishAnObject() throws Exception {
        try (var host = new Host()) {
            var descriptor = new RemoteAssetStore.AssetDescriptor("0".repeat(64), 4);
            try (var raw = host.raw()) {
                var request = RemoteWire.message(RemoteWire.Type.TRANSFER_OPEN,
                        new RemoteAssetTransfer.Open(host.credential, RemoteAssetTransfer.Direction.UPLOAD, descriptor));
                raw.send(request);
                assertEquals(RemoteWire.Type.TRANSFER_READY, raw.receive().type());
                try (var stream = RemoteAssetTransfer.output(raw, request.id(), 4, () -> {}, ignored -> {})) {
                    stream.write(new byte[]{1, 2, 3, 4});
                }
                assertEquals(RemoteWire.Type.ERROR, raw.receive().type());
            }
            assertFalse(host.store.contains(descriptor.sha256()));
            var interrupted = new RemoteAssetStore.AssetDescriptor("1".repeat(64), 100);
            try (var raw = host.raw()) {
                var request = RemoteWire.message(RemoteWire.Type.TRANSFER_OPEN,
                        new RemoteAssetTransfer.Open(host.credential, RemoteAssetTransfer.Direction.UPLOAD, interrupted));
                raw.send(request);
                assertEquals(RemoteWire.Type.TRANSFER_READY, raw.receive().type());
            }
            assertFalse(host.store.contains(interrupted.sha256()));
            // The same quotas remain available after both rejected transfers.
            var valid = host.store.publishRequiredAsset(Files.write(directory.resolve("valid"), new byte[100]));
            assertEquals(100, valid.size());
        }
    }

    @Test
    void revocationInvalidatesAlreadyGrantedTransferChecks() throws Exception {
        try (var host = new Host()) {
            Runnable check = host.owner.call(() -> host.session.authorizeTransfer(host.credential.sessionId(),
                    host.credential.credential()));
            check.run();
            host.owner.call(() -> {
                host.session.revoke();
                return null;
            });
            assertThrows(RemoteSessionAccess.AccessException.class, check::run);
        }
    }

    @Test
    void stopWaitsForAnInterruptedBulkWorkerToReleaseItsSocketAndUnpublishedStagingFile() throws Exception {
        try (var host = new Host(); var raw = host.raw()) {
            int port = host.server.port();
            var descriptor = new RemoteAssetStore.AssetDescriptor("2".repeat(64), 100);
            raw.send(RemoteWire.message(RemoteWire.Type.TRANSFER_OPEN,
                    new RemoteAssetTransfer.Open(host.credential, RemoteAssetTransfer.Direction.UPLOAD, descriptor)));
            assertEquals(RemoteWire.Type.TRANSFER_READY, raw.receive().type());
            host.server.stop().get(5, TimeUnit.SECONDS);
            assertThrows(java.io.IOException.class, raw::receive);
            assertFalse(host.store.contains(descriptor.sha256()));
            try (var files = Files.list(directory.resolve("host-store").resolve("staging"))) {
                assertEquals(0, files.count());
            }
            try (var replacement = new java.net.ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(port, replacement.getLocalPort());
            }
        }
    }

    private RemoteAssetStore store(String name) throws Exception {
        return new RemoteAssetStore(directory.resolve(name), 2_000_000, 8_000_000, 4_000_000);
    }

    private final class Host implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final RemoteOwnerDispatch owner = new RemoteOwnerDispatch(executor, 2000);
        final RemoteIdentity identity = RemoteIdentity.create();
        final RemoteAssetStore store = store("host-store");
        final RemoteHostSession session;
        final RemoteProtocol.Connect credential;
        final RemoteAssetServer server;

        Host() throws Exception {
            session = owner.call(() -> new RemoteHostSession(new AppSettings(directory.resolve("host.ini"))));
            credential = owner.call(() -> {
                var pending = session.requestApproval(session.pairingCode().value(),
                        new RemoteSessionAccess.Client("Desktop", "127.0.0.1"));
                var grant = session.approve(pending.id());
                session.connect(grant.sessionId(), grant.credential());
                return new RemoteProtocol.Connect(grant.sessionId(), grant.credential());
            });
            var listener = new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    identity.context(), "asset-test", 2000, 2);
            server = new RemoteAssetServer(listener, session, owner, store);
            server.start();
        }

        RemoteTlsChannel raw() throws Exception {
            return RemoteTlsChannel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()),
                    RemoteTls.clientContext(identity.fingerprint()), "asset-test", 2000);
        }

        RemoteAssetClient transfer(RemoteAssetStore local, RemoteAssetTransfer.Direction direction,
                                   RemoteAssetStore.AssetDescriptor asset) {
            return RemoteAssetClient.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()),
                    identity.fingerprint(), "asset-test", 2000, credential, local, direction, asset);
        }

        @Override
        public void close() throws Exception {
            server.stop().get(5, TimeUnit.SECONDS);
            owner.call(() -> {
                session.close();
                return null;
            });
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
