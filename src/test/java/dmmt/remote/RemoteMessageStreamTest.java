package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMessageStreamTest {
    @Test
    void largeUncompressedWorkspacePayloadRoundTripsWithoutIncreasingFrameLimit() throws Exception {
        var identity = RemoteIdentity.create();
        try (var listener = new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity.context(), "large-test", 5000);
             var executor = Executors.newSingleThreadExecutor()) {
            var message = RemoteWire.message(RemoteWire.Type.WORKSPACE_STATE,
                    Map.of("parts", Collections.nCopies(400, "x".repeat(4096))));
            assertThrows(IOException.class, () -> RemoteWire.write(new java.io.ByteArrayOutputStream(), message));
            var host = executor.submit(() -> {
                try (var peer = listener.accept()) {
                    var received = RemoteMessageStream.receive(peer);
                    assertEquals(message, received);
                    RemoteMessageStream.send(peer, received);
                }
                return null;
            });
            try (var client = RemoteTlsChannel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    listener.port()), RemoteTls.clientContext(identity.fingerprint()), "large-test", 5000)) {
                RemoteMessageStream.send(client, message);
                assertEquals(message, RemoteMessageStream.receive(client));
            }
            host.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsOversizedOrNonWorkspaceCompositePayloadBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteMessageStream.Begin(RemoteWire.Type.STATE,
                RemoteWire.MAX_MESSAGE_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMessageStream.Begin(RemoteWire.Type.WORKSPACE_STATE,
                RemoteMessageStream.MAX_PAYLOAD_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMessageStream.Begin(RemoteWire.Type.WORKSPACE_STATE, 1));
    }

    @Test
    void boundedEncoderRejectsOversizedDocumentRatherThanPublishingATruncatedSnapshot() {
        var message = RemoteWire.message(RemoteWire.Type.WORKSPACE_STATE, Map.of("text", "x".repeat(8192)));
        assertThrows(IOException.class, () -> RemoteWire.encode(message, 4096));
    }
}
