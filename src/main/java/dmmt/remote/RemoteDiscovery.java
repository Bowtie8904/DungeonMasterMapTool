package dmmt.remote;

import dmmt.log.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Unauthenticated hints only. Exact certificate pinning is not proof of host identity. */
public final class RemoteDiscovery {
    public static final int PORT = 7074;
    private static final String GROUP = "239.255.77.84";
    private static final String QUERY = "DMMT-DISCOVER-2\n";
    private static final String REPLY = "DMMT-HOST-2\n";
    private static final int MAX_PACKET = 1024;
    private static final Log LOG = Log.of(RemoteDiscovery.class);

    private RemoteDiscovery() { }

    public record Host(String name, InetAddress address, int controlPort, int assetPort, String build,
                       String fingerprint) {
        public Host {
            name = new RemoteSessionAccess.Client(name, "host").name();
            Objects.requireNonNull(address);
            if (address.isAnyLocalAddress() || address.isMulticastAddress()
                    || controlPort < 1 || controlPort > 65535 || assetPort < 1 || assetPort > 65535
                    || controlPort == assetPort || build == null || !build.matches("[A-Za-z0-9._-]{1,128}")
                    || fingerprint == null || !fingerprint.matches("[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException("Invalid discovered host metadata.");
            }
        }
    }

    public static Responder advertise(RemoteHostingController.Details details) throws IOException {
        if (!(details.bindAddress() instanceof Inet4Address) || details.bindAddress().isAnyLocalAddress()) {
            throw new IOException("LAN discovery needs a specific IPv4 interface; use manual connection details.");
        }
        var network = NetworkInterface.getByInetAddress(details.bindAddress());
        if (network == null || !network.isUp() || !network.supportsMulticast()) {
            throw new IOException("The selected interface does not support multicast; use manual connection details.");
        }
        var socket = new MulticastSocket(null);
        DatagramSocket replies = null;
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setNetworkInterface(network);
            socket.setTimeToLive(1);
            socket.joinGroup(new InetSocketAddress(InetAddress.getByName(GROUP), PORT), network);
            replies = new DatagramSocket(new InetSocketAddress(details.bindAddress(), 0));
            return new Responder(socket, replies, new Host(details.name(), details.bindAddress(),
                    details.controlPort(), details.assetPort(), details.build(), details.fingerprint()));
        } catch (IOException | RuntimeException failure) {
            socket.close();
            if (replies != null) replies.close();
            throw failure;
        }
    }

    public static final class Responder implements AutoCloseable {
        private final DatagramSocket socket;
        private final DatagramSocket replies;
        private final Host host;
        private final java.util.function.LongSupplier clock;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private boolean started;
        private volatile String error;

        Responder(DatagramSocket socket, Host host) {
            this(socket, socket, host);
        }

        Responder(DatagramSocket socket, DatagramSocket replies, Host host) {
            this(socket, replies, host, System::nanoTime);
        }

        Responder(DatagramSocket socket, DatagramSocket replies, Host host, java.util.function.LongSupplier clock) {
            this.socket = socket;
            this.replies = replies;
            this.host = host;
            this.clock = clock;
        }

        public void start() {
            if (started || socket.isClosed()) throw new IllegalStateException("Discovery responder is started or closed.");
            Thread.ofPlatform().daemon().name("dmmt-remote-discovery").start(this::respond);
            started = true;
        }

        private void respond() {
            long lastReply = Long.MIN_VALUE;
            try {
                while (!socket.isClosed()) {
                    var packet = new DatagramPacket(new byte[MAX_PACKET + 1], MAX_PACKET + 1);
                    socket.receive(packet);
                    String text = ascii(packet);
                    if (text == null || !text.startsWith(QUERY)) continue;
                    String nonce = text.substring(QUERY.length());
                    if (!nonce.matches("[a-f0-9]{32}")) continue;
                    long now = clock.getAsLong();
                    if (lastReply != Long.MIN_VALUE && now - lastReply < TimeUnit.MILLISECONDS.toNanos(100)) continue;
                    lastReply = now;
                    byte[] reply = encode(host, nonce);
                    replies.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                }
            } catch (IOException failure) {
                if (!socket.isClosed()) {
                    error = "LAN discovery stopped; enter host connection details manually.";
                    LOG.warn("LAN discovery stopped; manual connection remains available.", failure);
                }
            } finally {
                socket.close();
                replies.close();
                stopped.complete(null);
            }
        }

        public CompletableFuture<Void> stopped() { return stopped.copy(); }
        public String error() { return error; }

        @Override public void close() {
            socket.close();
            replies.close();
            if (!started) stopped.complete(null);
        }
    }

    public static final class Search implements AutoCloseable {
        private DatagramSocket socket;
        private boolean closed;

        public List<Host> discover() throws IOException {
            synchronized (this) {
                if (closed) return List.of();
            }
            var targets = new ArrayList<Target>();
            for (var network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
                for (var address : java.util.Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        targets.add(new Target(address, new InetSocketAddress(InetAddress.getByName(GROUP), PORT)));
                        break;
                    }
                }
            }
            if (targets.isEmpty()) throw new IOException("No multicast-capable IPv4 LAN interface; enter host details manually.");
            return discover(targets, 1500);
        }

        List<Host> discover(List<Target> targets, int timeoutMillis) throws IOException {
            try (var udp = new MulticastSocket(null)) {
                synchronized (this) {
                    if (closed) return List.of();
                    socket = udp;
                }
                udp.bind(new InetSocketAddress(0));
                udp.setTimeToLive(1);
                String nonce = UUID.randomUUID().toString().replace("-", "");
                byte[] query = (QUERY + nonce).getBytes(StandardCharsets.US_ASCII);
                int sent = 0;
                IOException sendFailure = null;
                for (var target : targets) {
                    if (udp.isClosed()) return List.of();
                    try {
                        udp.setNetworkInterface(NetworkInterface.getByInetAddress(target.local()));
                        udp.send(new DatagramPacket(query, query.length, target.destination()));
                        sent++;
                    } catch (IOException failure) {
                        sendFailure = failure;
                        LOG.debug("Could not search one LAN interface.", failure);
                    }
                }
                if (sent == 0) throw new IOException("LAN search could not send; enter host details manually.", sendFailure);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                var results = new LinkedHashMap<String, Host>();
                for (int received = 0; received < 256 && results.size() < 64 && !udp.isClosed(); received++) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    udp.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    var packet = new DatagramPacket(new byte[MAX_PACKET + 1], MAX_PACKET + 1);
                    try {
                        udp.receive(packet);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    Host found = decode(packet, nonce);
                    if (found != null) results.putIfAbsent(found.address().getHostAddress() + ":" + found.controlPort(), found);
                }
                return results.values().stream().sorted(Comparator.comparing(Host::name)
                        .thenComparing(host -> host.address().getHostAddress()).thenComparingInt(Host::controlPort)).toList();
            } catch (IOException failure) {
                synchronized (this) {
                    if (closed) return List.of();
                }
                throw failure;
            } finally {
                synchronized (this) { socket = null; }
            }
        }

        @Override public synchronized void close() {
            closed = true;
            if (socket != null) socket.close();
        }
    }

    record Target(InetAddress local, InetSocketAddress destination) { }

    static byte[] encode(Host host, String nonce) {
        return (REPLY + nonce + "\n" + host.controlPort() + "\n" + host.assetPort() + "\n" + host.build() + "\n"
                + Base64.getEncoder().encodeToString(host.name().getBytes(StandardCharsets.UTF_8)) + "\n"
                + host.fingerprint())
                .getBytes(StandardCharsets.US_ASCII);
    }

    static Host decode(DatagramPacket packet, String nonce) {
        String text = ascii(packet);
        if (text == null || !text.startsWith(REPLY)) return null;
        String[] fields = text.substring(REPLY.length()).split("\n", -1);
        if (fields.length != 6 || !fields[0].equals(nonce)) return null;
        try {
            byte[] name = Base64.getDecoder().decode(fields[4]);
            String decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(name)).toString();
            return new Host(decoded, packet.getAddress(), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), fields[3], fields[5]);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            return null;
        }
    }

    private static String ascii(DatagramPacket packet) {
        if (packet.getLength() > MAX_PACKET) return null;
        for (int index = packet.getOffset(); index < packet.getOffset() + packet.getLength(); index++) {
            if (packet.getData()[index] < 0) return null;
        }
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
    }
}
