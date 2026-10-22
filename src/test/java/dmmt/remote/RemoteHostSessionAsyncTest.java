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
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHostSessionAsyncTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void reconnectDuringSaveReusesThePendingCommandAndOriginalReceipt() {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        ArrayDeque<Runnable> owner = new ArrayDeque<>();
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        Executor ownerExecutor = owner::add;
        Executor workerExecutor = worker::add;
        try (var session = new RemoteHostSession(settings)) {
            var grant = pair(session);
            var firstLease = session.connect(grant.sessionId(), grant.credential());
            var request = request(session.settingsState(firstLease));
            session.authorizeConnection(firstLease);
            assertThrows(RemoteSessionAccess.AccessException.class,
                    () -> session.authorizeConnection(UUID.randomUUID()));
            assertThrows(RemoteSessionAccess.AccessException.class,
                    () -> session.editSettingsAsync(UUID.randomUUID(), request, ownerExecutor, workerExecutor));
            var first = session.editSettingsAsync(firstLease, request, ownerExecutor, workerExecutor);
            session.disconnected(firstLease);
            assertThrows(RemoteSessionAccess.AccessException.class,
                    () -> session.authorizeConnection(firstLease));
            assertThrows(RemoteSessionAccess.AccessException.class,
                    () -> session.editSettingsAsync(firstLease, request, ownerExecutor, workerExecutor));
            var nextLease = session.connect(grant.sessionId(), grant.credential());
            var retry = session.editSettingsAsync(nextLease, request, ownerExecutor, workerExecutor);
            assertEquals(1, worker.size());
            assertFalse(first.isDone());
            drain(owner, worker);
            assertSame(first.join(), retry.join());
            assertEquals(HostSettingsAuthority.Status.ACCEPTED, first.join().status());
            settings.applyEdit("ui.gridOpacity", "0.8");
            assertSame(first.join(),
                    session.editSettingsAsync(nextLease, request, ownerExecutor, workerExecutor).join());
            assertEquals("0.8", session.settingsState(nextLease).settings().values().get("ui.gridOpacity"));
        }
    }

    @Test
    void revocationWhileIoIsQueuedCannotApplyAnOldSessionsValue() throws Exception {
        AppSettings settings = new AppSettings(dir.resolve("host.ini"));
        settings.applyEdit("ui.gridOpacity", "0.2");
        ArrayDeque<Runnable> owner = new ArrayDeque<>();
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        try (var session = new RemoteHostSession(settings)) {
            var grant = pair(session);
            var lease = session.connect(grant.sessionId(), grant.credential());
            var future = session.editSettingsAsync(lease, request(session.settingsState(lease)),
                    owner::add, worker::add);
            session.revoke();
            settings.applyEdit("ui.gridOpacity", "0.9");
            drain(owner, worker);
            assertEquals(HostSettingsAuthority.Status.RESYNC_REQUIRED, future.join().status());
            assertEquals(0.9, Tuning.GRID_OPACITY.get());
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.9"));
            var nextGrant = pair(session);
            var nextLease = session.connect(nextGrant.sessionId(), nextGrant.credential());
            assertEquals("0.9", session.settingsState(nextLease).settings().values().get("ui.gridOpacity"));
        }
    }

    private static void drain(ArrayDeque<Runnable> owner, ArrayDeque<Runnable> worker) {
        for (int round = 0; round < 30 && (!worker.isEmpty() || !owner.isEmpty()); round++) {
            if (!worker.isEmpty()) {
                worker.remove().run();
            }
            if (!owner.isEmpty()) {
                owner.remove().run();
            }
        }
        assertTrue(owner.isEmpty());
        assertTrue(worker.isEmpty());
    }

    private static RemoteSessionAccess.Grant pair(RemoteHostSession session) {
        var pending = session.requestApproval(session.pairingCode().value(),
                new RemoteSessionAccess.Client("Desktop B", "192.168.1.42"));
        return session.approve(pending.id());
    }

    private static HostSettingsAuthority.Request request(HostSettingsAuthority.State state) {
        return new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                        "ui.gridOpacity", state.settings().values().get("ui.gridOpacity"), "0.6"));
    }
}
