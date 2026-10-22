package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class RemoteCompatibilityTest {
    @Test
    void explicitVersionIsAcceptedByHandshakeDiscoveryAndManualConnectionDetails() throws Exception {
        assertTrue(RemoteCompatibility.VERSION.matches("[1-9][0-9]*"));
        assertEquals(RemoteCompatibility.VERSION,
                new RemoteWire.Hello(RemoteWire.PROTOCOL_VERSION, RemoteCompatibility.VERSION).build());
        String pin = "a".repeat(64);
        var host = new RemoteDiscovery.Host("Table", InetAddress.getLoopbackAddress(), 7072, 7073,
                RemoteCompatibility.VERSION, pin);
        String nonce = "b".repeat(32);
        byte[] encoded = RemoteDiscovery.encode(host, nonce);
        assertEquals(host, RemoteDiscovery.decode(
                new java.net.DatagramPacket(encoded, encoded.length, host.address(), 7074), nonce));
        var details = new RemoteConnectionDetails("Table", "127.0.0.1", 7072, 7073,
                pin, RemoteCompatibility.VERSION);
        assertEquals(details, RemoteConnectionDetails.parse(details.format()));
    }
}
