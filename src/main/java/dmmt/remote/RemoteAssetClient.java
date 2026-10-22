package dmmt.remote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single background transfer, with byte progress and TCP-level cancellation. */
public final class RemoteAssetClient implements AutoCloseable {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteAssetClient.class);
    private final RemoteAssetStore local;
    private final RemoteAssetTransfer.Open request;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<RemoteAssetStore.AssetDescriptor> result = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile RemoteTlsChannel active;
    private volatile long transferred;

    private RemoteAssetClient(InetSocketAddress address, String fingerprint, String build, int timeoutMillis,
                              RemoteProtocol.Connect credential, RemoteAssetStore local,
                              RemoteAssetTransfer.Direction direction, RemoteAssetStore.AssetDescriptor asset) {
        this.local = Objects.requireNonNull(local, "local");
        Objects.requireNonNull(address, "address");
        RemoteTlsChannel.validate(build, timeoutMillis);
        if (fingerprint == null || !fingerprint.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("A verified host certificate fingerprint is required.");
        }
        request = new RemoteAssetTransfer.Open(credential, direction, asset);
        Thread.ofPlatform().daemon().name("dmmt-remote-asset-client").start(
                () -> run(address, fingerprint, build, timeoutMillis));
    }

    public static RemoteAssetClient start(InetSocketAddress address, String fingerprint, String build, int timeoutMillis,
                                          RemoteProtocol.Connect credential, RemoteAssetStore local,
                                          RemoteAssetTransfer.Direction direction,
                                          RemoteAssetStore.AssetDescriptor asset) {
        return new RemoteAssetClient(address, fingerprint, build, timeoutMillis, credential, local, direction, asset);
    }

    public CompletableFuture<RemoteAssetStore.AssetDescriptor> result() {
        return result.copy();
    }

    public long transferredBytes() {
        return transferred;
    }

    public long totalBytes() {
        return request.asset().size();
    }

    public RemoteAssetTransfer.Direction direction() {
        return request.direction();
    }

    public String sha256() {
        return request.asset().sha256();
    }

    public CompletableFuture<Void> stopped() {
        return stopped.copy();
    }

    @Override
    public void close() throws IOException {
        cancelled.set(true);
        var channel = active;
        if (channel != null) {
            channel.close();
        }
    }

    private void run(InetSocketAddress address, String fingerprint, String build, int timeout) {
        try {
            checkCancelled();
            LOG.info("Asset {} started: {} bytes, sha256 {}... via {}", request.direction(),
                    request.asset().size(), request.asset().sha256().substring(0, 12), address);
            if (request.direction() == RemoteAssetTransfer.Direction.DOWNLOAD && local.contains(request.asset().sha256())) {
                if (!local.descriptor(request.asset().sha256()).equals(request.asset())) {
                    throw new IOException("The cached asset manifest does not match.");
                }
                checkCancelled();
                LOG.debug("Asset {} satisfied from the local cache", request.direction());
                result.complete(request.asset());
                return;
            }
            try (var channel = RemoteTlsChannel.connect(address, RemoteTls.clientContext(fingerprint), build, timeout)) {
                active = channel;
                checkCancelled();
                var message = RemoteWire.message(RemoteWire.Type.TRANSFER_OPEN, request);
                channel.send(message);
                var response = channel.receive();
                RemoteAssetTransfer.requireId(response, message.id());
                if (request.direction() == RemoteAssetTransfer.Direction.UPLOAD
                        && response.type() == RemoteWire.Type.TRANSFER_PRESENT) {
                    verifyDescriptor(response);
                } else {
                    RemoteAssetTransfer.requireType(response, RemoteWire.Type.TRANSFER_READY);
                    verifyDescriptor(response);
                    if (request.direction() == RemoteAssetTransfer.Direction.UPLOAD) {
                        try (var output = RemoteAssetTransfer.output(channel, message.id(), request.asset().size(),
                                this::checkCancelled, bytes -> transferred = bytes)) {
                            local.transferTo(request.asset().sha256(), output);
                        }
                        var published = channel.receive();
                        RemoteAssetTransfer.requireId(published, message.id());
                        RemoteAssetTransfer.requireType(published, RemoteWire.Type.TRANSFER_RESULT);
                        verifyDescriptor(published);
                    } else {
                        local.ingest(RemoteAssetTransfer.input(channel, message.id(), request.asset().size(),
                                        this::checkCancelled, bytes -> transferred = bytes),
                                request.asset().size(), request.asset().sha256());
                    }
                }
                checkCancelled();
                LOG.info("Asset {} completed: {} bytes, sha256 {}...", request.direction(),
                        request.asset().size(), request.asset().sha256().substring(0, 12));
                result.complete(request.asset());
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            if (cancelled.get()) {
                LOG.info("Asset {} cancelled", request.direction());
            } else {
                LOG.warn("Asset {} failed (sha256 {}...)", request.direction(),
                        request.asset().sha256().substring(0, 12), failure);
            }
            result.completeExceptionally(new IOException(cancelled.get() ? "Asset transfer cancelled."
                    : "Asset transfer failed; verify host access, content integrity and storage quotas.", failure));
        } finally {
            active = null;
            stopped.complete(null);
        }
    }

    private void verifyDescriptor(RemoteWire.Message message) throws IOException {
        if (!request.asset().equals(RemoteWire.payload(message, RemoteAssetStore.AssetDescriptor.class))) {
            throw new IOException("The host returned a different asset manifest.");
        }
    }

    private void checkCancelled() {
        if (cancelled.get()) {
            throw new IllegalArgumentException("Asset transfer cancelled.");
        }
    }
}
