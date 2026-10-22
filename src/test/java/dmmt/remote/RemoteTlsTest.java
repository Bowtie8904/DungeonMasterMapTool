package dmmt.remote;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteTlsTest {
    @TempDir
    Path directory;

    private static final String BUILD = RemoteCompatibility.VERSION;
    private static SSLContext host;
    private static SSLContext client;
    private static String pin;

    @BeforeAll
    static void createTestIdentity() throws Exception {
        var identity = RemoteIdentity.create();
        pin = identity.fingerprint();
        host = identity.context();
        client = RemoteTls.clientContext(pin);
    }

    @Test
    void realLoopbackTlsNegotiatesBuildAndExchangesFramedControlMessages() throws Exception {
        try (var listener = listener(BUILD, 3000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RemoteWire.Message> received = workers.submit(() -> {
                try (var channel = listener.accept()) {
                    var message = channel.receive();
                    channel.send(RemoteWire.message(RemoteWire.Type.APPROVAL, Map.of("pending", true)));
                    return message;
                }
            });
            try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 3000)) {
                var message = RemoteWire.message(RemoteWire.Type.PAIR, Map.of("code", "12345678"));
                channel.send(message);
                assertEquals(RemoteWire.Type.APPROVAL, channel.receive().type());
                assertEquals(message, received.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pairingApprovalAndHostSettingsCommandsRoundTripOverTls() throws Exception {
        try (var listener = listener(BUILD, 5000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var code = new java.util.concurrent.CompletableFuture<String>();
            Path hostFile = directory.resolve("host.ini");
            try {
                Future<String> hostResult = workers.submit(() -> {
                    var settings = new dmmt.service.AppSettings(hostFile);
                    try (var session = new RemoteHostSession(settings);
                         var channel = listener.accept()) {
                        code.complete(session.pairingCode().value());
                        var pairing = channel.receive();
                        assertEquals(RemoteWire.Type.PAIR, pairing.type());
                        var approval = session.requestApproval(pairing.payload().get("code").textValue(),
                                new RemoteSessionAccess.Client("Desktop B", "127.0.0.1"));
                        // Explicit host approval in this integration scenario, not production auto-approval.
                        var grant = session.approve(approval.id());
                        channel.send(RemoteWire.message(RemoteWire.Type.GRANT,
                                Map.of("sessionId", grant.sessionId(), "credential", grant.credential())));
                        var connect = channel.receive();
                        assertEquals(RemoteWire.Type.CONNECT, connect.type());
                        var lease = session.connect(UUID.fromString(connect.payload().get("sessionId").textValue()),
                                connect.payload().get("credential").textValue());
                        channel.send(RemoteWire.message(RemoteWire.Type.STATE, session.settingsState(lease)));
                        var edit = channel.receive();
                        assertEquals(RemoteWire.Type.EDIT, edit.type());
                        var request = RemoteWire.payload(edit, dmmt.service.HostSettingsAuthority.Request.class);
                        channel.send(RemoteWire.message(RemoteWire.Type.RESULT, session.editSettings(lease, request)));
                        return settings.get("ui.gridOpacity", null);
                    }
                });
                try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 5000)) {
                    channel.send(RemoteWire.message(RemoteWire.Type.PAIR, Map.of("code", code.get(5, TimeUnit.SECONDS))));
                    var grant = channel.receive();
                    assertEquals(RemoteWire.Type.GRANT, grant.type());
                    channel.send(RemoteWire.message(RemoteWire.Type.CONNECT,
                            Map.of("sessionId", grant.payload().get("sessionId").textValue(),
                                    "credential", grant.payload().get("credential").textValue())));
                    var state = RemoteWire.payload(channel.receive(), dmmt.service.HostSettingsAuthority.State.class);
                    var edit = new dmmt.service.AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                            "ui.gridOpacity", state.settings().values().get("ui.gridOpacity"), "0.6");
                    channel.send(RemoteWire.message(RemoteWire.Type.EDIT,
                            new dmmt.service.HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(), edit)));
                    var result = RemoteWire.payload(channel.receive(), dmmt.service.HostSettingsAuthority.Result.class);
                    assertEquals(dmmt.service.HostSettingsAuthority.Status.ACCEPTED, result.status());
                    assertEquals("0.6", result.state().settings().values().get("ui.gridOpacity"));
                    assertEquals("0.6", hostResult.get(5, TimeUnit.SECONDS));
                    assertTrue(Files.readString(hostFile).contains("ui.gridOpacity = 0.6"));
                }
            } finally {
                dmmt.service.Tuning.reset();
                dmmt.render.OverlayTextures.applySettings(key -> null);
            }
        }
    }

    @Test
    void wrongFingerprintFailsBeforeAnyApplicationMessagesCanBeSent() throws Exception {
        SSLContext wrongPin = RemoteTls.clientContext("0".repeat(64));
        try (var listener = listener(BUILD, 3000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IOException> rejected = workers.submit(() -> assertThrows(IOException.class, () -> {
                try (var unexpected = listener.accept()) {
                    fail("An untrusted host handshake must not produce a channel");
                }
            }));
            assertThrows(SSLException.class, () -> {
                try (var unexpected = RemoteTlsChannel.connect(address(listener), wrongPin, BUILD, 3000)) {
                    fail("A mismatched fingerprint must be rejected");
                }
            });
            assertNotNull(rejected.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mismatchedBuildsAreRejectedOnBothSidesBeforePairing() throws Exception {
        try (var listener = listener(BUILD, 3000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var serverFailure = workers.submit(failedAccept(listener));
            var failure = assertThrows(RemoteTlsChannel.CompatibilityException.class, () -> {
                try (var unexpected = RemoteTlsChannel.connect(address(listener), client, "different-build", 3000)) {
                    fail("Mismatched builds must not produce an application channel");
                }
            });
            assertTrue(failure.getMessage().contains("different-build"));
            assertTrue(failure.getMessage().contains(BUILD));
            assertTrue(failure.getMessage().contains("protocol " + RemoteWire.PROTOCOL_VERSION));
            var rejected = serverFailure.get(5, TimeUnit.SECONDS);
            assertInstanceOf(RemoteTlsChannel.CompatibilityException.class, rejected);
            assertTrue(rejected.getMessage().contains("same remote compatibility version"));
        }
    }

    @Test
    void stopClosesActiveChannelsAndPreventsFurtherAccepts() throws Exception {
        var listener = listener(BUILD, 3000);
        try (listener; var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted = workers.submit(listener::accept);
            try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 3000);
                 var server = accepted.get(5, TimeUnit.SECONDS)) {
                assertThrows(IllegalStateException.class, listener::accept);
                listener.close();
                assertThrows(IOException.class, channel::receive);
                assertThrows(IOException.class, server::receive);
                assertThrows(IOException.class, listener::accept);
            }
        }
    }

    @Test
    void listenerCanAcceptANewChannelAfterThePreviousChannelCloses() throws Exception {
        try (var listener = listener(BUILD, 3000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                var accepted = workers.submit(listener::accept);
                try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 3000);
                     var server = accepted.get(5, TimeUnit.SECONDS)) {
                    channel.send(RemoteWire.message(RemoteWire.Type.STATE, Map.of("revision", i)));
                    assertEquals(i, server.receive().payload().get("revision").intValue());
                }
            }
        }
    }

    @Test
    void silentHandshakeAndSilentAcceptAreBounded() throws Exception {
        try (var listener = listener(BUILD, 500);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var handshake = workers.submit(failedAccept(listener));
            try (var socket = new Socket()) {
                socket.connect(address(listener), 1000);
                assertNotNull(handshake.get(3, TimeUnit.SECONDS));
            }
            assertThrows(java.net.SocketTimeoutException.class, listener::accept);
        }
    }

    @Test
    void silentEstablishedChannelTimesOutAndReleasesItsListenerSlot() throws Exception {
        try (var listener = listener(BUILD, 1000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted = workers.submit(listener::accept);
            try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 1000);
                 var server = accepted.get(5, TimeUnit.SECONDS)) {
                assertThrows(java.net.SocketTimeoutException.class, server::receive);
                assertThrows(IOException.class, channel::receive);
            }
        }
    }

    @Test
    void malformedEncryptedFramesCloseTheChannelAndReleaseTheListenerSlot() throws Exception {
        try (var listener = listener(BUILD, 3000);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted = workers.submit(listener::accept);
            try (var raw = (javax.net.ssl.SSLSocket) client.getSocketFactory().createSocket()) {
                raw.connect(address(listener), 3000);
                raw.setSoTimeout(3000);
                raw.startHandshake();
                RemoteWire.write(raw.getOutputStream(), RemoteWire.message(RemoteWire.Type.HELLO,
                        new RemoteWire.Hello(RemoteWire.PROTOCOL_VERSION, BUILD)));
                assertEquals(RemoteWire.Type.HELLO, RemoteWire.read(raw.getInputStream()).type());
                try (var server = accepted.get(5, TimeUnit.SECONDS)) {
                    var output = new java.io.DataOutputStream(raw.getOutputStream());
                    output.writeInt(RemoteWire.MAX_MESSAGE_BYTES + 1);
                    output.flush();
                    assertThrows(IOException.class, server::receive);
                    assertThrows(IOException.class, () -> RemoteWire.read(raw.getInputStream()));
                }
            }
            var next = workers.submit(listener::accept);
            try (var channel = RemoteTlsChannel.connect(address(listener), client, BUILD, 3000);
                 var server = next.get(5, TimeUnit.SECONDS)) {
                channel.send(RemoteWire.message(RemoteWire.Type.STATE, Map.of()));
                assertEquals(RemoteWire.Type.STATE, server.receive().type());
            }
        }
    }

    @Test
    void invalidPinsAndUnboundedTimeoutsAreRejectedBeforeOpeningSockets() {
        for (String value : new String[]{"", "abc", "x".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> RemoteTls.clientContext(value));
        }
        assertThrows(IllegalArgumentException.class, () -> RemoteTls.clientContext(null));
        assertThrows(IllegalArgumentException.class, () -> {
            try (var unexpected = listener(BUILD, 0)) {
                fail("An unbounded socket timeout must be rejected");
            }
        });
        assertThrows(IllegalArgumentException.class, () -> {
            try (var unexpected = RemoteTlsChannel.connect(new InetSocketAddress("127.0.0.1", 1), client, "", 1000)) {
                fail("Missing build identity must be rejected");
            }
        });
    }

    private static Callable<IOException> failedAccept(RemoteTlsListener listener) {
        return () -> assertThrows(IOException.class, () -> {
            try (var unexpected = listener.accept()) {
                fail("The connection must fail");
            }
        });
    }

    private static RemoteTlsListener listener(String build, int timeout) throws IOException {
        return new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), host, build, timeout);
    }

    private static InetSocketAddress address(RemoteTlsListener listener) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.port());
    }
}
