package dmmt.remote;

import dmmt.render.OverlayTextures;
import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;
import dmmt.service.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHostSessionTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void settingsRequireHostApprovalAndALiveConnectionLease() throws Exception {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        try (var session = new RemoteHostSession(settings)) {
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.settingsState(null));
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.settingsState(UUID.randomUUID()));
            var grant = pair(session);
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.settingsState(UUID.randomUUID()));
            var lease = session.connect(grant.sessionId(), grant.credential());
            var state = session.settingsState(lease);
            var request = request(state, "0.6");
            var result = session.editSettings(lease, request);
            assertEquals(HostSettingsAuthority.Status.ACCEPTED, result.status());
            assertEquals("0.6", result.state().settings().values().get("ui.gridOpacity"));
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.6"));
            session.disconnected(lease);
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.settingsState(lease));
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.editSettings(lease, request));
        }
    }

    @Test
    void reconnectRetainsReceiptsAndCannotAccidentallyReapplyAnAcknowledgedCommand() {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        try (var session = new RemoteHostSession(settings)) {
            var grant = pair(session);
            var oldLease = session.connect(grant.sessionId(), grant.credential());
            var request = request(session.settingsState(oldLease), "0.6");
            var original = session.editSettings(oldLease, request);
            settings.applyEdit("ui.gridOpacity", "0.8");
            session.disconnected(oldLease);

            var currentLease = session.connect(grant.sessionId(), grant.credential());
            session.disconnected(oldLease);
            assertSame(original, session.editSettings(currentLease, request));
            assertEquals("0.8", session.settingsState(currentLease).settings().values().get("ui.gridOpacity"));
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.editSettings(oldLease, request));
        }
    }

    @Test
    void revokeDropsAccessAndTheOldSettingsEpochButDoesNotChangeHostState() {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        try (var session = new RemoteHostSession(settings)) {
            var oldGrant = pair(session);
            var oldLease = session.connect(oldGrant.sessionId(), oldGrant.credential());
            var oldState = session.settingsState(oldLease);
            var oldRequest = request(oldState, "0.9");
            settings.applyEdit("ui.gridOpacity", "0.7");
            session.revoke();
            assertEquals(0.7, Tuning.GRID_OPACITY.get());
            assertThrows(RemoteSessionAccess.AccessException.class, () -> session.editSettings(oldLease, oldRequest));
            assertThrows(RemoteSessionAccess.AccessException.class,
                    () -> session.connect(oldGrant.sessionId(), oldGrant.credential()));

            var newGrant = pair(session);
            var newLease = session.connect(newGrant.sessionId(), newGrant.credential());
            assertNotEquals(oldState.sessionId(), session.settingsState(newLease).sessionId());
            assertEquals(HostSettingsAuthority.Status.RESYNC_REQUIRED,
                    session.editSettings(newLease, oldRequest).status());
            assertEquals(0.7, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void stoppedSessionHasNoAccessAndDoesNotPersistOrResetAnyWorkspaceValues() throws Exception {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        settings.applyEdit("ui.gridOpacity", "0.4");
        byte[] before = Files.readAllBytes(settings.getFile());
        var session = new RemoteHostSession(settings);
        var grant = pair(session);
        var lease = session.connect(grant.sessionId(), grant.credential());
        var request = request(session.settingsState(lease), "0.9");
        session.close();
        session.close();
        assertThrows(RemoteSessionAccess.AccessException.class, () -> session.settingsState(lease));
        assertThrows(RemoteSessionAccess.AccessException.class, () -> session.editSettings(lease, request));
        assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        assertEquals(0.4, Tuning.GRID_OPACITY.get());
    }

    @Test
    void networkThreadsMustNotAccessTheApplicationOwnedCoordinator() {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        try (var session = new RemoteHostSession(settings)) {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, session::status);
                assertThrows(IllegalStateException.class, session::pairingCode);
                assertThrows(IllegalStateException.class, session::close);
                assertThrows(IllegalStateException.class, () -> session.settingsState(null));
            }).join();
            assertEquals(RemoteSessionAccess.Phase.WAITING, session.status().phase());
        }
    }

    @Test
    void aRemoteClientContextCannotHostOrApproveAConnection() {
        AppSettings settings = new AppSettings(dir.resolve("client.ini"));
        try (var session = new RemoteHostSession(settings)) {
            var pending = session.requestApproval(session.pairingCode().value(),
                    new RemoteSessionAccess.Client("B", "192.168.1.2"));
            try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(1, Map.of()), edit -> fail())) {
                assertThrows(IllegalStateException.class, () -> session.approve(pending.id()));
                assertThrows(IllegalStateException.class, () -> {
                    try (var unexpected = new RemoteHostSession(settings)) {
                        fail("Remote clients cannot host");
                    }
                });
            }
            assertEquals(pending, session.status().pending());
        }
    }

    private static RemoteSessionAccess.Grant pair(RemoteHostSession session) {
        var pending = session.requestApproval(session.pairingCode().value(),
                new RemoteSessionAccess.Client("Desktop B", "192.168.1.42"));
        return session.approve(pending.id());
    }

    private static HostSettingsAuthority.Request request(HostSettingsAuthority.State state, String value) {
        return new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                        "ui.gridOpacity", state.settings().values().get("ui.gridOpacity"), value));
    }
}
