package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDiscoveryTest {
    private static final String NONCE = "a".repeat(32);
    private static final String BUILD = RemoteCompatibility.VERSION;
    private static final String PIN = "d".repeat(64);
    private final InetAddress loopback = InetAddress.getLoopbackAddress();

    @Test
    void protocolContainsOnlyPublicHintsAndCorrelatesRepliesToTheCurrentSearch() {
        var host = new RemoteDiscovery.Host("DM \u00e4 session", loopback, 7072, 7073, BUILD, PIN);
        byte[] encoded = RemoteDiscovery.encode(host, NONCE);
        String wire = new String(encoded, StandardCharsets.US_ASCII);
        assertTrue(encoded.length <= 1024);
        assertTrue(wire.contains(PIN), "The certificate pin is a public, unverified connection hint.");
        assertFalse(wire.contains("credential"));
        assertFalse(wire.contains("pairing"));
        var packet = new DatagramPacket(encoded, encoded.length, loopback, 1234);
        assertEquals(host, RemoteDiscovery.decode(packet, NONCE));
        assertNull(RemoteDiscovery.decode(packet, "c".repeat(32)));
        assertNull(RemoteDiscovery.decode(packet(wire + "\nextra"), NONCE));
        assertNull(RemoteDiscovery.decode(packet(wire.replace("\n7073\n", "\n7072\n")), NONCE));
        assertNull(RemoteDiscovery.decode(packet(wire.replace("\n7072\n", "\n65536\n")), NONCE));
        assertNull(RemoteDiscovery.decode(packet(wire.replace("\n" + BUILD + "\n", "\ninvalid version\n")), NONCE));
        for (String invalid : List.of("", "d".repeat(63), "d".repeat(65), "g".repeat(64))) {
            assertNull(RemoteDiscovery.decode(packet(wire.replace(PIN, invalid)), NONCE));
            assertThrows(IllegalArgumentException.class, () ->
                    new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, invalid));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, null));
        assertNull(RemoteDiscovery.decode(packet(wire.replace("DMMT-HOST-2", "DMMT-HOST-1")), NONCE));
        assertNull(RemoteDiscovery.decode(packet("x".repeat(1025)), NONCE));
        assertNull(RemoteDiscovery.decode(packet("DMMT-HOST-2\n" + NONCE + "\n7072\n7073\n" + BUILD
                        + "\n/w==\n" + PIN), NONCE),
                "Malformed UTF-8 names must not be replacement-decoded.");
    }

    @Test
    void explicitSearchFindsResponderAndStopJoinsItsThreadAndReleasesTheSocket() throws Exception {
        var socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        int port = socket.getLocalPort();
        var host = new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, PIN);
        try (var responder = new RemoteDiscovery.Responder(socket, host);
             var search = new RemoteDiscovery.Search()) {
            responder.start();
            assertEquals(List.of(host), search.discover(
                    List.of(new RemoteDiscovery.Target(loopback, new InetSocketAddress(loopback, port))), 200));
            assertNull(responder.error());
            responder.close();
            responder.stopped().get(2, TimeUnit.SECONDS);
            assertTrue(socket.isClosed());
            try (var rebound = new DatagramSocket(new InetSocketAddress(loopback, port))) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test
    void searchDeduplicatesRepliesAndRejectsRepliesWithAnotherNonce() throws Exception {
        try (var peer = new DatagramSocket(new InetSocketAddress(loopback, 0));
             var worker = Executors.newSingleThreadExecutor();
             var search = new RemoteDiscovery.Search()) {
            peer.setSoTimeout(2000);
            var reply = worker.submit(() -> {
                var query = new DatagramPacket(new byte[1024], 1024);
                peer.receive(query);
                String text = new String(query.getData(), query.getOffset(), query.getLength(), StandardCharsets.US_ASCII);
                String nonce = text.substring(text.indexOf('\n') + 1);
                var host = new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, PIN);
                for (String candidate : List.of("f".repeat(32), nonce, nonce)) {
                    byte[] encoded = RemoteDiscovery.encode(host, candidate);
                    peer.send(new DatagramPacket(encoded, encoded.length, query.getSocketAddress()));
                }
                return host;
            });
            var results = search.discover(List.of(new RemoteDiscovery.Target(loopback,
                    new InetSocketAddress(loopback, peer.getLocalPort()))), 200);
            assertEquals(List.of(reply.get(2, TimeUnit.SECONDS)), results);
        }
    }

    @Test
    void closingBeforeResponderStartsCreatesNoThreadAndCompletesCleanup() throws Exception {
        var socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        var responder = new RemoteDiscovery.Responder(socket,
                new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, PIN));
        responder.close();
        responder.stopped().get(2, TimeUnit.SECONDS);
        assertTrue(socket.isClosed());
        assertThrows(IllegalStateException.class, responder::start);
    }

    @Test
    void closeCancelsAnActiveSearchWithoutWaitingForItsDeadlineAndCancelledQueuedSearchDoesNoWork() throws Exception {
        try (var silent = new DatagramSocket(new InetSocketAddress(loopback, 0));
             var worker = Executors.newSingleThreadExecutor();
             var search = new RemoteDiscovery.Search()) {
            silent.setSoTimeout(2000);
            var pending = worker.submit(() -> search.discover(List.of(new RemoteDiscovery.Target(
                    loopback, new InetSocketAddress(loopback, silent.getLocalPort()))), 60_000));
            var query = new DatagramPacket(new byte[1024], 1024);
            silent.receive(query);
            search.close();
            assertTrue(pending.get(2, TimeUnit.SECONDS).isEmpty());
        }
        try (var cancelled = new RemoteDiscovery.Search()) {
            cancelled.close();
            assertTrue(cancelled.discover().isEmpty());
        }
    }

    @Test
    void searchStopsAtExactly64ResultsOr256DatagramsRatherThanWaitingForItsDeadline() throws Exception {
        for (boolean valid : List.of(true, false)) {
            try (var peer = new DatagramSocket(new InetSocketAddress(loopback, 0));
                 var worker = Executors.newSingleThreadExecutor();
                 var search = new RemoteDiscovery.Search()) {
                peer.setSoTimeout(2000);
                var pending = worker.submit(() -> search.discover(List.of(new RemoteDiscovery.Target(
                        loopback, new InetSocketAddress(loopback, peer.getLocalPort()))), 60_000));
                var query = new DatagramPacket(new byte[1024], 1024);
                peer.receive(query);
                String text = new String(query.getData(), query.getOffset(), query.getLength(), StandardCharsets.US_ASCII);
                String nonce = text.substring(text.indexOf('\n') + 1);
                int count = valid ? 64 : 256;
                for (int index = 0; index < count; index++) {
                    byte[] encoded = valid ? RemoteDiscovery.encode(new RemoteDiscovery.Host(
                            "Table", loopback, 10000 + index, 20000, BUILD, PIN), nonce) : new byte[]{0};
                    peer.send(new DatagramPacket(encoded, encoded.length, query.getSocketAddress()));
                }
                assertEquals(valid ? 64 : 0, pending.get(3, TimeUnit.SECONDS).size());
            }
        }
    }

    @Test
    void responderIgnoresMalformedAndOversizedRequestsAndCapsReplies() throws Exception {
        var socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        try (var responder = new RemoteDiscovery.Responder(socket, socket,
                new RemoteDiscovery.Host("Table", loopback, 7072, 7073, BUILD, PIN), () -> 0);
             var client = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            responder.start();
            client.setSoTimeout(200);
            send(client, socket.getLocalPort(), "DMMT-DISCOVER-2\nshort");
            send(client, socket.getLocalPort(), "DMMT-DISCOVER-2\n" + NONCE + "x".repeat(1100));
            send(client, socket.getLocalPort(), "DMMT-DISCOVER-2\n" + NONCE);
            var reply = new DatagramPacket(new byte[1025], 1025);
            client.receive(reply);
            assertNotNull(RemoteDiscovery.decode(reply, NONCE));
            for (int i = 0; i < 10; i++) send(client, socket.getLocalPort(), "DMMT-DISCOVER-2\n" + NONCE);
            assertThrows(java.net.SocketTimeoutException.class, () -> client.receive(reply));
        }
    }

    private DatagramPacket packet(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return new DatagramPacket(bytes, bytes.length, loopback, 1234);
    }

    private void send(DatagramSocket client, int port, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        client.send(new DatagramPacket(bytes, bytes.length, loopback, port));
    }
}
