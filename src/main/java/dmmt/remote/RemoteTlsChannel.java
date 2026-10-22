package dmmt.remote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking, one-reader/one-writer TLS channel. All IO belongs on transport workers, never the FX thread. */
public final class RemoteTlsChannel implements AutoCloseable {
    public static final class CompatibilityException extends IOException {
        private CompatibilityException(String localBuild, RemoteWire.Hello peer) {
            super("Incompatible remote application: local compatibility version " + localBuild + " (protocol "
                    + RemoteWire.PROTOCOL_VERSION + "), peer compatibility version " + peer.build() + " (protocol "
                    + peer.protocolVersion() + "). Both PCs must use the same remote compatibility version.");
        }
    }

    private static final System.Logger LOG = System.getLogger(RemoteTlsChannel.class.getName());
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final SSLSocket socket;
    private final Socket transport;
    private final Runnable onClose;
    private final int timeoutMillis;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private RemoteTlsChannel(SSLSocket socket, Socket transport, Runnable onClose, int timeoutMillis) {
        this.socket = socket;
        this.transport = transport;
        this.onClose = onClose;
        this.timeoutMillis = timeoutMillis;
    }

    public static RemoteTlsChannel connect(InetSocketAddress address, SSLContext pinnedContext,
                                           String build, int timeoutMillis) throws IOException {
        validate(build, timeoutMillis);
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(pinnedContext, "pinnedContext");
        Socket transport = new Socket();
        try {
            transport.connect(address, timeoutMillis);
            SSLSocket socket = (SSLSocket) pinnedContext.getSocketFactory()
                    .createSocket(transport, address.getHostString(), address.getPort(), true);
            socket.setUseClientMode(true);
            return negotiate(socket, transport, build, timeoutMillis, () -> {});
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(transport, failure);
            throw failure;
        }
    }

    static RemoteTlsChannel negotiate(SSLSocket socket, Socket transport, String build, int timeoutMillis,
                                      Runnable onClose) throws IOException {
        validate(build, timeoutMillis);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setSoTimeout(timeoutMillis);
        socket.setTcpNoDelay(true);
        RemoteTlsChannel channel = new RemoteTlsChannel(socket, transport, onClose, timeoutMillis);
        return channel.withDeadline(() -> {
            socket.startHandshake();
            RemoteWire.write(socket.getOutputStream(), RemoteWire.message(RemoteWire.Type.HELLO,
                    new RemoteWire.Hello(RemoteWire.PROTOCOL_VERSION, build)));
            RemoteWire.Message response = RemoteWire.read(socket.getInputStream());
            if (response.type() != RemoteWire.Type.HELLO) {
                throw new IOException("The remote peer did not negotiate its application version.");
            }
            RemoteWire.Hello hello = RemoteWire.payload(response, RemoteWire.Hello.class);
            if (hello.protocolVersion() != RemoteWire.PROTOCOL_VERSION || !hello.build().equals(build)) {
                throw new CompatibilityException(build, hello);
            }
            return channel;
        });
    }

    public RemoteWire.Message receive() throws IOException {
        try {
            return withDeadline(() -> RemoteWire.read(socket.getInputStream()));
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(failure);
            throw failure;
        }
    }

    public void send(RemoteWire.Message message) throws IOException {
        synchronized (writeLock) {
            try {
                withDeadline(() -> {
                    RemoteWire.write(socket.getOutputStream(), message);
                    return null;
                });
            } catch (IOException | RuntimeException failure) {
                closeOnFailure(failure);
                throw failure;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                // Close TCP directly: TLS close-notify can block behind a stalled reader/writer.
                transport.close();
            } finally {
                onClose.run();
            }
        }
    }

    public String peerAddress() {
        return transport.getInetAddress().getHostAddress();
    }

    static void validate(String build, int timeoutMillis) {
        new RemoteWire.Hello(RemoteWire.PROTOCOL_VERSION, build);
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("A finite positive socket timeout is required.");
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    /** SO_TIMEOUT alone allows drip-fed frames and cannot bound writes to a non-reading peer. */
    private <T> T withDeadline(IoAction<T> action) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        var deadline = DEADLINES.schedule(() -> {
            expired.set(true);
            try {
                close();
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "Could not close an expired remote channel.", failure);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            T value = action.run();
            if (expired.get()) {
                throw new SocketTimeoutException("The remote operation deadline expired.");
            }
            return value;
        } catch (IOException failure) {
            if (expired.get() && !(failure instanceof SocketTimeoutException)) {
                SocketTimeoutException timeout = new SocketTimeoutException("The remote operation deadline expired.");
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        } finally {
            deadline.cancel(false);
        }
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "dmmt-remote-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private void closeOnFailure(Exception failure) {
        try {
            close();
        } catch (IOException closing) {
            failure.addSuppressed(closing);
        }
    }

    static void closeAfterFailure(Socket socket, Exception failure) {
        try {
            socket.close();
        } catch (IOException closing) {
            failure.addSuppressed(closing);
        }
    }
}
