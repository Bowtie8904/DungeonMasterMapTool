package dmmt.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteConnectionDetailsTest {
    @Test
    void clipboardMetadataRoundTripsIpv4Ipv6AndNamesWithoutAnyCredentialOrPairingCode() {
        for (var host : java.util.List.of("192.168.1.20", "2001:db8::42", "session-laptop.local")) {
            var details = new RemoteConnectionDetails("DM's table = A", host, 7072, 7073,
                    "A".repeat(64), "B".repeat(64));
            String clipboard = details.format();
            assertEquals(details, RemoteConnectionDetails.parse(clipboard));
            assertEquals(details, RemoteConnectionDetails.parse(clipboard.replace("\n", "\r\n")));
            assertEquals("a".repeat(64), details.fingerprint());
            assertFalse(clipboard.toLowerCase(java.util.Locale.ROOT).contains("credential"));
            assertFalse(clipboard.contains("code="));
            assertFalse(clipboard.contains("password"));
        }
    }

    @Test
    void rejectsDuplicateUnknownSecretFieldsControlsOversizeAndInvalidIdentity() {
        var details = new RemoteConnectionDetails("A", "192.168.1.20", 7072, 7073, "a".repeat(64), "b".repeat(64));
        String valid = details.format();
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid + "\ncode=12345678"));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(
                valid.replace("build=" + "b".repeat(64), "host=192.168.1.30")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("assets=", "secret=")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("7073", "7072")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("7073", "65536")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("name=A", "name=A\tB")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("1.20", "1.20\u0000")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(valid.replace("a".repeat(64), "trust me")));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse("x".repeat(2049)));
        assertThrows(IllegalArgumentException.class, () -> RemoteConnectionDetails.parse(null));
    }
}
