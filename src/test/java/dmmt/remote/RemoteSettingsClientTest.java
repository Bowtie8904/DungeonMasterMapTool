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
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dmmt.remote.RemoteSettingsClient.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteSettingsClientTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void settingsRoundTripThroughApprovedHostWithoutChangingTheClientFile() throws Exception {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        settings.applyEdit("ui.gridOpacity", "0.9");
        byte[] localFile = Files.readAllBytes(settings.getFile());
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var session = new RemoteHostSession(host)) {
            var lease = connect(session);
            try (var client = new RemoteSettingsClient(settings, session.settingsState(lease), notices::add)) {
                settings.applyEdit("ui.gridOpacity", "0.6");
                assertEquals(Tuning.GRID_OPACITY.defaultValue(), settings.getDouble("ui.gridOpacity", 0));
                assertEquals(1, client.pendingCount());
                var request = client.request();
                assertSame(request, client.request());
                var receipt = session.editSettings(lease, request);
                assertTrue(client.receive(receipt));
                assertEquals("0.6", settings.get("ui.gridOpacity", null));
                assertEquals("0.6", host.get("ui.gridOpacity", null));
                assertEquals(ACCEPTED, notices.getFirst().outcome());
                assertEquals(0, client.pendingCount());
                assertNull(client.request());
                assertFalse(client.receive(receipt));
                assertEquals(1, notices.size());
                assertArrayEquals(localFile, Files.readAllBytes(settings.getFile()));
            }
        }
        assertEquals("0.9", settings.get("ui.gridOpacity", null));
        assertArrayEquals(localFile, Files.readAllBytes(settings.getFile()));
    }

    @Test
    void queuedUnrelatedCommandsAreSerializedAndDoNotEchoHostUpdates() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            settings.putBoolean("player.showGrid", true);
            var first = client.request();
            assertEquals(1, first.sequence());
            assertEquals(2, client.pendingCount());
            assertSame(first, client.request());
            client.receive(authority.execute(first));
            var second = client.request();
            assertEquals(2, second.sequence());
            assertNotEquals(first.edit().commandId(), second.edit().commandId());
            client.receive(authority.execute(second));
            assertEquals("0.6", settings.get("ui.gridOpacity", null));
            assertTrue(settings.getBoolean("player.showGrid", false));
            assertEquals(2, notices.size());
            assertTrue(notices.stream().allMatch(notice -> notice.outcome() == ACCEPTED));
            host.putBoolean("player.showGrid", false);
            assertTrue(client.update(authority.state()));
            assertFalse(settings.getBoolean("player.showGrid", true));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void boundedQueueRejectsExtraEditsWithoutSendingOrApplyingThem() {
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var client = new RemoteSettingsClient(settings, baseline(), notices::add, 2)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            client.request();
            settings.putBoolean("player.showGrid", true);
            assertThrows(IllegalStateException.class, () -> settings.applyEdit("audio.masterVolume", "0.2"));
            assertEquals(2, client.pendingCount());
            assertNull(settings.get("audio.masterVolume", null));
        }
        assertEquals(2, notices.size());
        assertTrue(notices.stream().anyMatch(notice -> notice.outcome() == CANCELLED));
        assertTrue(notices.stream().anyMatch(notice -> notice.outcome() == UNKNOWN));
    }

    @Test
    void pauseCancelsUnsentEditsButRetainsAnExactRequestForReceiptRecovery() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            settings.putBoolean("player.showGrid", true);
            var request = client.request();
            var receipt = authority.execute(request);
            client.pause();
            client.pause();
            assertEquals(RemoteSettingsClient.Phase.PAUSED, client.phase());
            assertEquals(1, client.pendingCount());
            assertEquals(CANCELLED, notices.getFirst().outcome());
            assertThrows(IllegalStateException.class, client::request);
            assertThrows(IllegalStateException.class, () -> settings.putBoolean("player.showGrid", false));
            assertThrows(IllegalStateException.class, () -> client.receive(receipt));
            assertTrue(client.resume(authority.state()));
            assertSame(request, client.request());
            assertSame(receipt, authority.execute(client.request()));
            client.receive(receipt);
            assertEquals(ACCEPTED, notices.getLast().outcome());
            assertEquals(0, client.pendingCount());
            assertFalse(settings.getBoolean("player.showGrid", false));
        }
    }

    @Test
    void reconnectBeforeHostExecutionRetriesTheOriginalUnmodifiedCommand() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notice -> {})) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            var request = client.request();
            client.pause();
            assertTrue(client.resume(authority.state()));
            assertSame(request, client.request());
            assertTrue(client.receive(authority.execute(request)));
            assertEquals("0.6", settings.get("ui.gridOpacity", null));
        }
    }

    @Test
    void anOldReceiptAfterResyncCannotOverwriteNewerHostSettings() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notice -> {})) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            var request = client.request();
            var receipt = authority.execute(request);
            host.applyEdit("ui.gridOpacity", "0.8");
            client.pause();
            assertTrue(client.resume(authority.state()));
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            client.receive(receipt);
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void hostConflictIsReportedAndAuthoritativeValueReplacesTheClientValue() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            host.applyEdit("ui.gridOpacity", "0.8");
            client.receive(authority.execute(client.request()));
            assertEquals(CONFLICT, notices.getFirst().outcome());
            assertEquals("0.8", settings.get("ui.gridOpacity", null));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void saveFailuresAndInvalidRequestsAreNotReportedAsAccepted() throws Exception {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notices::add)) {
            settings.applyEdit("ui.gridOpacity", "invalid");
            client.receive(authority.execute(client.request()));
            assertEquals(INVALID, notices.getLast().outcome());
            settings.applyEdit("ui.gridOpacity", "0.6");
            Path blocked = host.getFile().resolveSibling("host.ini.tmp");
            Files.createDirectory(blocked);
            client.receive(authority.execute(client.request()));
            assertEquals(SAVE_FAILED, notices.getLast().outcome());
            assertEquals(Tuning.GRID_OPACITY.defaultValue(), settings.getDouble("ui.gridOpacity", 0));
            Files.delete(blocked);
        }
    }

    @Test
    void changedEpochRequiresFreshContextInsteadOfReplayingPendingEdits() {
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        var baseline = baseline();
        try (var client = new RemoteSettingsClient(settings, baseline, notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            client.request();
            settings.putBoolean("player.showGrid", true);
            client.pause();
            assertFalse(client.resume(new HostSettingsAuthority.State(UUID.randomUUID(), baseline.settings(), 1)));
            assertEquals(RemoteSettingsClient.Phase.RESYNC_REQUIRED, client.phase());
            assertEquals(0, client.pendingCount());
            assertEquals(CANCELLED, notices.getFirst().outcome());
            assertEquals(UNKNOWN, notices.getLast().outcome());
            assertThrows(IllegalStateException.class, () -> settings.applyEdit("ui.gridOpacity", "0.9"));
            assertThrows(IllegalStateException.class, client::request);
        }
        assertFalse(settings.usesHostSettings());
    }

    @Test
    void expiredReceiptRequiresResyncAndNeverAllocatesANewCommandId() {
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        var baseline = baseline();
        try (var client = new RemoteSettingsClient(settings, baseline, notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            var request = client.request();
            var expired = new HostSettingsAuthority.Result(request.edit().commandId(), request.sequence(),
                    HostSettingsAuthority.Status.RESYNC_REQUIRED, "Receipt expired.",
                    new HostSettingsAuthority.State(baseline.sessionId(), baseline.settings(), 8));
            assertTrue(client.receive(expired));
            assertEquals(RemoteSettingsClient.Phase.RESYNC_REQUIRED, client.phase());
            assertEquals(request.edit().commandId(), notices.getFirst().commandId());
            assertEquals(UNKNOWN, notices.getFirst().outcome());
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void aPushCanPrecedeItsAcknowledgementWithoutConsumingThePendingRequest() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notice -> {})) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            var request = client.request();
            var result = authority.execute(request);
            assertTrue(client.update(authority.state()));
            assertEquals("0.6", settings.get("ui.gridOpacity", null));
            assertSame(request, client.request());
            assertEquals(1, client.pendingCount());
            assertTrue(client.receive(result));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void mismatchedAcknowledgementsCannotConsumeThePendingRequest() {
        AppSettings settings = clientSettings();
        var baseline = baseline();
        try (var client = new RemoteSettingsClient(settings, baseline, notice -> {})) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            var request = client.request();
            var next = new HostSettingsAuthority.State(baseline.sessionId(), baseline.settings(), 2);
            assertThrows(IllegalArgumentException.class, () -> client.receive(new HostSettingsAuthority.Result(
                    UUID.randomUUID(), 1, HostSettingsAuthority.Status.ACCEPTED, "Wrong command", next)));
            assertThrows(IllegalArgumentException.class, () -> client.receive(new HostSettingsAuthority.Result(
                    request.edit().commandId(), 2, HostSettingsAuthority.Status.ACCEPTED, "Future command", next)));
            assertThrows(IllegalArgumentException.class, () -> client.receive(new HostSettingsAuthority.Result(
                    request.edit().commandId(), 1, HostSettingsAuthority.Status.ACCEPTED, "Wrong sequence", baseline)));
            assertSame(request, client.request());
            assertEquals(1, client.pendingCount());
        }
    }

    @Test
    void invalidContinuityAndLateCallbacksDoNotAffectStandaloneOrNewContexts() {
        AppSettings settings = clientSettings();
        var baseline = baseline();
        var client = new RemoteSettingsClient(settings, baseline, notice -> {});
        client.pause();
        assertFalse(client.resume(new HostSettingsAuthority.State(baseline.sessionId(), baseline.settings(), 3)));
        client.close();
        client.close();
        assertEquals(RemoteSettingsClient.Phase.CLOSED, client.phase());
        try (var replacement = new RemoteSettingsClient(settings, baseline, notice -> {})) {
            assertThrows(IllegalStateException.class, () -> client.update(baseline));
            assertThrows(IllegalStateException.class, () -> client.resume(baseline));
            client.close();
            assertTrue(settings.usesHostSettings());
            assertEquals(RemoteSettingsClient.Phase.CONNECTED, replacement.phase());
        }
    }

    @Test
    void idleClientCanResumeTheSameSessionButUnexpectedPushSequencesPauseEditing() {
        AppSettings settings = clientSettings();
        var baseline = baseline();
        try (var client = new RemoteSettingsClient(settings, baseline, notice -> {})) {
            client.pause();
            assertTrue(client.resume(baseline));
            assertFalse(client.update(new HostSettingsAuthority.State(baseline.sessionId(), baseline.settings(), 2)));
            assertEquals(RemoteSettingsClient.Phase.RESYNC_REQUIRED, client.phase());
            assertThrows(IllegalStateException.class, () -> settings.putBoolean("player.showGrid", true));
        }
    }

    @Test
    void closingWithOnlyUnsentEditsReportsCancellationInsteadOfUnknownOutcome() {
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        var client = new RemoteSettingsClient(settings, baseline(), notices::add);
        settings.applyEdit("ui.gridOpacity", "0.6");
        client.close();
        assertEquals(1, notices.size());
        assertEquals(CANCELLED, notices.getFirst().outcome());
        assertEquals(0, client.pendingCount());
        assertFalse(settings.usesHostSettings());
    }

    @Test
    void queuedSameTargetEditsDoNotSilentlyOverwriteTheFirstCommittedEdit() {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings settings = clientSettings();
        var notices = new ArrayList<RemoteSettingsClient.Notice>();
        try (var authority = new HostSettingsAuthority(host);
             var client = new RemoteSettingsClient(settings, authority.state(), notices::add)) {
            settings.applyEdit("ui.gridOpacity", "0.6");
            settings.applyEdit("ui.gridOpacity", "0.8");
            client.receive(authority.execute(client.request()));
            client.receive(authority.execute(client.request()));
            assertEquals(ACCEPTED, notices.getFirst().outcome());
            assertEquals(CONFLICT, notices.getLast().outcome());
            assertEquals("0.6", settings.get("ui.gridOpacity", null));
        }
    }

    @Test
    void clientOperationsMustRunOnTheirOwnerThreadAndValidateTheBaselineBeforeEntry() {
        AppSettings settings = clientSettings();
        var baseline = baseline();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var unexpected = new RemoteSettingsClient(settings, baseline, notice -> {}, 0)) {
                fail("An empty queue is not allowed");
            }
        });
        assertThrows(IllegalArgumentException.class, () -> {
            try (var unexpected = new RemoteSettingsClient(settings,
                    new HostSettingsAuthority.State(baseline.sessionId(), baseline.settings(), 0), notice -> {})) {
                fail("Invalid sequence must be rejected");
            }
        });
        assertFalse(settings.usesHostSettings());
        try (var client = new RemoteSettingsClient(settings, baseline, notice -> {})) {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, client::request);
                assertThrows(IllegalStateException.class, client::pause);
                assertThrows(IllegalStateException.class, client::close);
                assertThrows(IllegalStateException.class, () -> settings.applyEdit("ui.gridOpacity", "0.6"));
            }).join();
            assertEquals(0, client.pendingCount());
        }
    }

    private AppSettings clientSettings() {
        return new AppSettings(dir.resolve("client.ini"));
    }

    private static HostSettingsAuthority.State baseline() {
        return new HostSettingsAuthority.State(UUID.randomUUID(), new AppSettings.HostSnapshot(0, Map.of()), 1);
    }

    private static UUID connect(RemoteHostSession session) {
        var approval = session.requestApproval(session.pairingCode().value(),
                new RemoteSessionAccess.Client("Desktop B", "192.168.1.42"));
        var grant = session.approve(approval.id());
        return session.connect(grant.sessionId(), grant.credential());
    }
}
