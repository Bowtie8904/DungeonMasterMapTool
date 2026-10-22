package dmmt.remote;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

/** Separate, bounded TLS bulk listener. No filenames or filesystem paths are accepted from a peer. */
public final class RemoteAssetServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RemoteAssetServer.class.getName());
    private final RemoteTlsListener listener;
    private final RemoteHostSession session;
    private final RemoteOwnerDispatch owner;
    private final RemoteAssetStore store;
    private final Semaphore slots;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean servingEnded = new AtomicBoolean();
    private final AtomicInteger peers = new AtomicInteger();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile IOException stopFailure;
    private volatile String lastError;

    public RemoteAssetServer(RemoteTlsListener listener, RemoteHostSession session,
                             RemoteOwnerDispatch owner, RemoteAssetStore store) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        slots = new Semaphore(listener.connectionLimit());
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("This remote asset listener cannot be started.");
        }
        Thread.ofPlatform().daemon().name("dmmt-remote-assets").start(this::serve);
    }

    public int port() {
        return listener.port();
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    public CompletableFuture<Void> stop() {
        if (closed.compareAndSet(false, true)) {
            try {
                listener.close();
            } catch (IOException failure) {
                lastError = "Could not close the remote asset listener.";
                LOG.log(System.Logger.Level.WARNING, lastError, failure);
                stopFailure = failure;
            }
            if (!started.get()) {
                servingEnded.set(true);
                completeStop();
            }
        }
        return stopped.copy();
    }

    private void serve() {
        try {
            acceptTransfers();
        } finally {
            servingEnded.set(true);
            completeStop();
        }
    }

    private void completeStop() {
        if (servingEnded.get() && peers.get() == 0) {
            if (stopFailure == null) stopped.complete(null);
            else stopped.completeExceptionally(stopFailure);
        }
    }

    private void acceptTransfers() {
        while (!closed.get()) {
            boolean acquired = false;
            try {
                slots.acquire();
                acquired = true;
                if (closed.get()) {
                    slots.release();
                    return;
                }
                var channel = listener.accept();
                peers.incrementAndGet();
                Thread.ofPlatform().daemon().name("dmmt-remote-asset-transfer").start(() -> {
                    try (channel) {
                        transfer(channel);
                    } catch (IOException | RemoteSessionAccess.AccessException | IllegalArgumentException failure) {
                        if (!closed.get()) {
                            lastError = "A remote asset transfer failed or was cancelled; unpublished data was discarded.";
                            LOG.log(System.Logger.Level.DEBUG, "Remote asset transfer failed: {0}",
                                    failure.getClass().getSimpleName());
                        }
                    } finally {
                        slots.release();
                        peers.decrementAndGet();
                        completeStop();
                    }
                });
            } catch (SocketTimeoutException idle) {
                if (acquired) {
                    slots.release();
                }
            } catch (IOException failure) {
                if (acquired) {
                    slots.release();
                }
                if (!closed.get()) {
                    lastError = "The remote asset connection failed. Verify address, fingerprint and build.";
                    LOG.log(System.Logger.Level.DEBUG, "Remote asset connection failed: {0}",
                            failure.getClass().getSimpleName());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void transfer(RemoteTlsChannel channel) throws IOException {
        var message = channel.receive();
        try {
            RemoteAssetTransfer.requireType(message, RemoteWire.Type.TRANSFER_OPEN);
            var open = RemoteWire.payload(message, RemoteAssetTransfer.Open.class);
            Runnable authorize = owner.call(() -> session.authorizeTransfer(open.access().sessionId(),
                    open.access().credential()));
            if (open.asset().size() > store.maxAssetBytes()) {
                throw new IOException("Asset exceeds the host transfer quota.");
            }
            authorize.run();
            if (open.direction() == RemoteAssetTransfer.Direction.DOWNLOAD) {
                var descriptor = store.descriptor(open.asset().sha256());
                if (!descriptor.equals(open.asset())) {
                    throw new IOException("The requested asset manifest does not match.");
                }
                channel.send(RemoteWire.reply(message, RemoteWire.Type.TRANSFER_READY, descriptor));
                try (var output = RemoteAssetTransfer.output(channel, message.id(), descriptor.size(), authorize,
                        ignored -> {})) {
                    store.transferTo(descriptor.sha256(), output);
                }
            } else if (store.contains(open.asset().sha256())) {
                var descriptor = store.descriptor(open.asset().sha256());
                if (!descriptor.equals(open.asset())) {
                    throw new IOException("The uploaded asset manifest does not match.");
                }
                channel.send(RemoteWire.reply(message, RemoteWire.Type.TRANSFER_PRESENT, descriptor));
            } else {
                channel.send(RemoteWire.reply(message, RemoteWire.Type.TRANSFER_READY, open.asset()));
                var input = RemoteAssetTransfer.input(channel, message.id(), open.asset().size(), authorize,
                        ignored -> {});
                var descriptor = store.ingest(input, open.asset().size(), open.asset().sha256());
                authorize.run();
                channel.send(RemoteWire.reply(message, RemoteWire.Type.TRANSFER_RESULT, descriptor));
            }
        } catch (IOException | RemoteSessionAccess.AccessException | IllegalArgumentException failure) {
            channel.send(RemoteWire.reply(message, RemoteWire.Type.ERROR,
                    new RemoteProtocol.Failure(RemoteProtocol.ErrorCode.INVALID_MESSAGE,
                            "Asset transfer rejected. Check session access, integrity and transfer/storage quotas.")));
            throw failure;
        }
    }
}
