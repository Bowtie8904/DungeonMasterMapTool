package dmmt.remote;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionClientTest {
    @TempDir
    Path directory;

    @AfterEach
    void resetGlobals() {
        dmmt.service.Tuning.reset();
        dmmt.render.OverlayTextures.applySettings(key -> null);
    }

    @Test
    void lostAcknowledgementReconnectsWithSameCredentialAndRecoversExactRequestWithoutRollingBackHostState()
            throws Exception {
        var identity = RemoteIdentity.create();
        UUID epoch = UUID.randomUUID();
        var baseline = state(epoch, 0, "0.5", 1);
        var accepted = state(epoch, 1, "0.6", 2);
        var newer = state(epoch, 2, "0.8", 2);
        var credential = new RemoteProtocol.Connect(UUID.randomUUID(), "A".repeat(43));
        try (var listener = new RemoteTlsListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity.context(), "reconnect-test", 3000);
             var workers = Executors.newSingleThreadExecutor()) {
            var recovery = workers.submit(() -> {
                HostSettingsAuthority.Request original;
                try (var channel = listener.accept()) {
                    var pair = channel.receive();
                    assertEquals(RemoteWire.Type.PAIR, pair.type());
                    UUID approvalId = UUID.randomUUID();
                    channel.send(RemoteWire.reply(pair, RemoteWire.Type.APPROVAL,
                            new RemoteProtocol.Approval(approvalId, System.currentTimeMillis() + 60_000)));
                    var poll = channel.receive();
                    assertEquals(approvalId, RemoteWire.payload(poll, RemoteProtocol.ApprovalPoll.class).requestId());
                    // This scripted host explicitly approves only this integration-test pairing.
                    channel.send(RemoteWire.reply(poll, RemoteWire.Type.GRANT, credential));
                    var connect = channel.receive();
                    assertEquals(credential, RemoteWire.payload(connect, RemoteProtocol.Connect.class));
                    channel.send(RemoteWire.reply(connect, RemoteWire.Type.STATE, baseline));
                    original = RemoteWire.payload(receiveEdit(channel, baseline), HostSettingsAuthority.Request.class);
                    // The command was accepted; lose the channel before its acknowledgement is delivered.
                }
                try (var channel = listener.accept()) {
                    var connect = channel.receive();
                    assertEquals(RemoteWire.Type.CONNECT, connect.type());
                    assertEquals(credential, RemoteWire.payload(connect, RemoteProtocol.Connect.class));
                    channel.send(RemoteWire.reply(connect, RemoteWire.Type.STATE, newer));
                    var retryMessage = receiveEdit(channel, newer);
                    var retry = RemoteWire.payload(retryMessage, HostSettingsAuthority.Request.class);
                    assertEquals(original, retry);
                    var result = new HostSettingsAuthority.Result(original.edit().commandId(), original.sequence(),
                            HostSettingsAuthority.Status.ACCEPTED, "Saved on host.", accepted);
                    channel.send(RemoteWire.reply(retryMessage, RemoteWire.Type.RESULT, result));
                    return retry;
                }
            });
            var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.port());
            try (var client = RemoteSessionClient.pair(address, identity.fingerprint(),
                    "reconnect-test", 3000, "12345678", "Desktop B")) {
                await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                var settings = new AppSettings(directory.resolve("client.ini"));
                try (var coordinator = new RemoteSettingsClient(settings, client.latestState(), notice -> {})) {
                    settings.putDouble("ui.gridOpacity", 0.6);
                    var original = coordinator.request();
                    var unknown = client.edit(original);
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> unknown.get(5, TimeUnit.SECONDS));
                    await(client::canReconnect);
                    coordinator.pause();
                    assertThrows(IllegalStateException.class, () -> settings.putDouble("ui.gridOpacity", 0.4));
                    client.reconnect();
                    await(() -> client.phase() == RemoteSessionClient.Phase.SETTINGS_READY);
                    assertTrue(coordinator.resume(client.latestState()));
                    assertEquals(0.8, settings.getDouble("ui.gridOpacity", 0));
                    assertSame(original, coordinator.request());
                    var result = client.edit(coordinator.request()).get(5, TimeUnit.SECONDS);
                    assertTrue(coordinator.receive(result));
                    assertEquals(0, coordinator.pendingCount());
                    assertEquals(0.8, settings.getDouble("ui.gridOpacity", 0));
                    assertEquals(newer, client.latestState());
                    assertEquals(original, recovery.get(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static RemoteWire.Message receiveEdit(RemoteTlsChannel channel,
                                                 HostSettingsAuthority.State state) throws Exception {
        while (true) {
            var message = channel.receive();
            if (message.type() == RemoteWire.Type.EDIT) {
                return message;
            }
            assertEquals(RemoteWire.Type.STATE, message.type());
            channel.send(RemoteWire.reply(message, RemoteWire.Type.STATE, state));
        }
    }

    private static HostSettingsAuthority.State state(UUID epoch, long revision, String value, long sequence) {
        return new HostSettingsAuthority.State(epoch,
                new AppSettings.HostSnapshot(revision, Map.of("ui.gridOpacity", value)), sequence);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                fail("Remote client did not reach the expected phase in time.");
            }
            Thread.sleep(10);
        }
    }
}
