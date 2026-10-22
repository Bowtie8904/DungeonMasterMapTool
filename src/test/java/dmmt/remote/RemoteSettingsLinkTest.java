package dmmt.remote;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import dmmt.service.Tuning;
import dmmt.render.OverlayTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSettingsLinkTest {
    @TempDir Path directory;

    @AfterEach
    void resetSettings() {
        Tuning.reset();
        OverlayTextures.applySettings(key -> null);
    }

    @Test
    void pulseSynchronizesBothDirectionsWithoutWritingTheClientIniAndCloseRestoresIt() throws Exception {
        var local = new AppSettings(directory.resolve("local.ini"));
        local.applyEdit("ui.gridOpacity", "0.9");
        var original = Files.readAllBytes(local.getFile());
        var host = new AppSettings(directory.resolve("host.ini"));
        host.applyEdit("ui.gridOpacity", "0.4");
        try (var authority = new HostSettingsAuthority(host)) {
            var wire = new Wire(authority);
            var notices = new ArrayList<String>();
            try (var link = new RemoteSettingsLink(local, wire, () -> {}, notices::add, wire.owner::addLast)) {
                assertEquals("0.4", local.get("ui.gridOpacity", null));
                local.applyEdit("ui.gridOpacity", "0.6");
                assertEquals("0.4", local.get("ui.gridOpacity", null));
                link.tick();
                assertEquals(1, wire.requests.size());
                wire.complete();
                wire.drain();
                assertEquals("0.6", local.get("ui.gridOpacity", null));
                host.putBoolean("player.showGrid", true);
                link.tick();
                assertTrue(local.getBoolean("player.showGrid", false));
                assertEquals(1, wire.requests.size(), "Host updates must not echo as settings commands.");
                assertTrue(link.usable());
                assertArrayEquals(original, Files.readAllBytes(local.getFile()));
            }
            assertEquals("0.9", local.get("ui.gridOpacity", null));
            assertArrayEquals(original, Files.readAllBytes(local.getFile()));
        }
    }

    @Test
    void reconnectRecoversExactLostReceiptAndLateCallbacksCannotRestoreTheOverlayAfterClose() {
        var local = new AppSettings(directory.resolve("local.ini"));
        var host = new AppSettings(directory.resolve("host.ini"));
        try (var authority = new HostSettingsAuthority(host)) {
            var wire = new Wire(authority);
            var link = new RemoteSettingsLink(local, wire, () -> {}, message -> {}, wire.owner::addLast);
            local.applyEdit("ui.gridOpacity", "0.6");
            link.tick();
            var request = wire.requests.getFirst();
            var receipt = authority.execute(request);
            wire.connected = false;
            link.tick();
            assertFalse(link.usable());
            wire.reply.completeExceptionally(new IllegalStateException("Connection lost"));
            wire.drain();
            wire.connected = true;
            link.tick();
            assertSame(request, wire.requests.getLast());
            wire.reply.complete(receipt);
            wire.drain();
            assertEquals("0.6", local.get("ui.gridOpacity", null));
            local.putBoolean("player.showGrid", true);
            link.tick();
            link.close();
            wire.complete();
            wire.drain();
            assertFalse(local.usesHostSettings());
            assertFalse(link.usable());
        }
    }

    @Test
    void changedHostEpochPausesWithoutAttemptingToSendQueuedSettings() {
        var local = new AppSettings(directory.resolve("local.ini"));
        var host = new AppSettings(directory.resolve("host.ini"));
        try (var authority = new HostSettingsAuthority(host)) {
            var wire = new Wire(authority);
            var notices = new ArrayList<String>();
            try (var link = new RemoteSettingsLink(local, wire, () -> {}, notices::add, wire.owner::addLast)) {
                local.applyEdit("ui.gridOpacity", "0.6");
                var previous = wire.latest();
                wire.override = new HostSettingsAuthority.State(java.util.UUID.randomUUID(), previous.settings(), 1);
                assertDoesNotThrow(link::tick);
                assertFalse(link.usable());
                assertTrue(wire.requests.isEmpty());
                assertFalse(notices.isEmpty());
            }
        }
    }

    private static final class Wire implements RemoteSettingsLink.Connection {
        final HostSettingsAuthority authority;
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final List<HostSettingsAuthority.Request> requests = new ArrayList<>();
        CompletableFuture<HostSettingsAuthority.Result> reply;
        boolean connected = true;
        HostSettingsAuthority.State override;

        Wire(HostSettingsAuthority authority) { this.authority = authority; }
        @Override public boolean connected() { return connected; }
        @Override public HostSettingsAuthority.State latest() {
            return override == null ? authority.stateInMemory() : override;
        }
        @Override public CompletableFuture<HostSettingsAuthority.Result> send(HostSettingsAuthority.Request request) {
            requests.add(request);
            reply = new CompletableFuture<>();
            return reply;
        }
        void complete() { reply.complete(authority.execute(requests.getLast())); }
        void drain() { while (!owner.isEmpty()) owner.removeFirst().run(); }
    }
}
