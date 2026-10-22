package dmmt.remote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Explicitly started, single-connection listener; caller owns the background accept/read workers. */
public final class RemoteTlsListener implements AutoCloseable {
    private final ServerSocket server;
    private final SSLContext context;
    private final String build;
    private final int timeoutMillis;
    private final int connectionLimit;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();

    public RemoteTlsListener(InetSocketAddress address, SSLContext hostContext,
                             String build, int timeoutMillis) throws IOException {
        this(address, hostContext, build, timeoutMillis, 1);
    }

    /** Two slots allow the server to reject a second client without interrupting the approved one. */
    public RemoteTlsListener(InetSocketAddress address, SSLContext hostContext,
                             String build, int timeoutMillis, int connectionLimit) throws IOException {
        RemoteTlsChannel.validate(build, timeoutMillis);
        if (connectionLimit < 1 || connectionLimit > 2) {
            throw new IllegalArgumentException("Remote listeners support one or two bounded transport slots.");
        }
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(hostContext, "hostContext");
        this.build = build;
        this.timeoutMillis = timeoutMillis;
        this.connectionLimit = connectionLimit;
        context = hostContext;
        server = new ServerSocket();
        try {
            server.setSoTimeout(timeoutMillis);
            server.bind(address, 1);
        } catch (IOException | RuntimeException failure) {
            try {
                server.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public int connectionLimit() {
        return connectionLimit;
    }

    public RemoteTlsChannel accept() throws IOException {
        if (!accepting.compareAndSet(false, true)) {
            throw new IllegalStateException("A remote accept operation is already in progress.");
        }
        Socket socket = null;
        try {
            if (closed.get()) {
                throw new IOException("Remote listener is closed.");
            }
            if (active.size() >= connectionLimit) {
                throw new IllegalStateException("A remote transport connection is already active.");
            }
            socket = server.accept();
            active.add(socket);
            if (closed.get()) {
                throw new IOException("Remote listener stopped while accepting a connection.");
            }
            Socket accepted = socket;
            SSLSocket tls = (SSLSocket) context.getSocketFactory()
                    .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
            tls.setUseClientMode(false);
            return RemoteTlsChannel.negotiate(tls, socket, build, timeoutMillis,
                    () -> active.remove(accepted));
        } catch (IOException | RuntimeException failure) {
            if (socket != null) {
                RemoteTlsChannel.closeAfterFailure(socket, failure);
                active.remove(socket);
            }
            throw failure;
        } finally {
            accepting.set(false);
        }
    }

    @Override
    public void close() throws IOException {
        closed.set(true);
        IOException failure = null;
        try {
            server.close();
        } catch (IOException closing) {
            failure = closing;
        }
        for (Socket socket : active) {
            try {
                socket.close();
            } catch (IOException closing) {
                if (failure == null) {
                    failure = closing;
                } else {
                    failure.addSuppressed(closing);
                }
            }
        }
        active.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
