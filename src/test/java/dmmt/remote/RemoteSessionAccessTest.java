package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static dmmt.remote.RemoteSessionAccess.Failure.*;
import static dmmt.remote.RemoteSessionAccess.Phase.AWAITING_APPROVAL;
import static dmmt.remote.RemoteSessionAccess.Phase.CONNECTED;
import static dmmt.remote.RemoteSessionAccess.Phase.DISCONNECTED;
import static dmmt.remote.RemoteSessionAccess.Phase.WAITING;
import static org.junit.jupiter.api.Assertions.*;

class RemoteSessionAccessTest {
    private static final RemoteSessionAccess.Client CLIENT =
            new RemoteSessionAccess.Client("Desktop B", "192.168.1.42");

    @Test
    void hostMustApproveBeforeTheClientCanConnect() {
        try (var access = new RemoteSessionAccess()) {
            var initial = access.status();
            assertEquals(WAITING, initial.phase());
            assertNull(initial.client());
            assertNull(initial.pending());
            var code = access.pairingCode();
            assertTrue(code.value().matches("[0-9]{8}"));
            var pending = access.requestApproval(code.value(), CLIENT);
            assertEquals(AWAITING_APPROVAL, access.status().phase());
            assertEquals(pending, access.status().pending());
            failure(UNAUTHORIZED, () -> access.connect(initial.sessionId(), "x".repeat(43)));

            var grant = access.approve(pending.id());
            assertEquals(initial.sessionId(), grant.sessionId());
            assertEquals(43, grant.credential().length());
            assertEquals(DISCONNECTED, access.status().phase());
            assertEquals(CLIENT, access.status().client());
            assertNull(access.status().pending());
            failure(BUSY, access::pairingCode);
            failure(UNKNOWN_REQUEST, () -> access.approve(pending.id()));

            var lease = access.connect(grant.sessionId(), grant.credential());
            assertEquals(CONNECTED, access.status().phase());
            assertDoesNotThrow(() -> access.authorizeConnection(lease));
            failure(BUSY, () -> access.connect(grant.sessionId(), grant.credential()));
        }
    }

    @Test
    void approvalOnlyRequestsNeedHostApprovalEvenWithExpiredManualCodes() {
        var clock = new TestClock();
        try (var access = new RemoteSessionAccess(clock)) {
            clock.advance(Duration.ofMinutes(6));
            var request = access.requestApproval(CLIENT);
            assertEquals(AWAITING_APPROVAL, access.status().phase());
            failure(UNAUTHORIZED, () -> access.connect(access.status().sessionId(), "x".repeat(43)));
            failure(BUSY, () -> access.requestApproval(CLIENT));
            var grant = access.approve(request.id());
            var lease = access.connect(grant.sessionId(), grant.credential());
            assertDoesNotThrow(() -> access.authorizeConnection(lease));
            access.revoke();
            failure(UNAUTHORIZED, () -> access.authorize(grant.sessionId(), grant.credential()));
            var next = access.requestApproval(CLIENT);
            assertNotEquals(request.id(), next.id());
        }
    }

    @Test
    void approvalOnlyRequestsShareRateLimitsAndExpiredOrRejectedRequestsCannotGainAccess() {
        var clock = new TestClock();
        try (var access = new RemoteSessionAccess(clock)) {
            var expired = access.requestApproval(CLIENT);
            clock.advance(Duration.ofMinutes(1));
            failure(UNKNOWN_REQUEST, () -> access.approve(expired.id()));
            for (int i = 0; i < 5; i++) {
                var rejected = access.requestApproval(CLIENT);
                access.reject(rejected.id());
                failure(UNKNOWN_REQUEST, () -> access.approve(rejected.id()));
            }
            failure(RATE_LIMITED, () -> access.requestApproval(CLIENT));
            failure(RATE_LIMITED, () -> access.requestApproval(access.pairingCode().value(), CLIENT));
            access.revoke();
            failure(RATE_LIMITED, () -> access.requestApproval(CLIENT));
            clock.advance(Duration.ofMinutes(1));
            var cancelled = access.requestApproval(CLIENT);
            access.cancelApproval(cancelled.id());
            failure(UNKNOWN_REQUEST, () -> access.approve(cancelled.id()));
            assertEquals(WAITING, access.status().phase());
        }
    }

    @Test
    void anotherClientCannotReplaceAPendingOrApprovedClient() {
        try (var access = new RemoteSessionAccess()) {
            var code = access.pairingCode().value();
            var first = access.requestApproval(code, CLIENT);
            var second = new RemoteSessionAccess.Client("Second client", "192.168.1.43");
            failure(BUSY, () -> access.requestApproval(code, second));
            assertEquals(first, access.status().pending());
            var grant = access.approve(first.id());
            failure(BUSY, () -> access.requestApproval(code, second));
            failure(BUSY, access::regenerateCode);
            var lease = access.connect(grant.sessionId(), grant.credential());
            access.disconnected(lease);
            failure(BUSY, () -> access.requestApproval(code, second));
            assertEquals(CLIENT, access.status().client());
        }
    }

    @Test
    void reconnectPreservesApprovalButOldConnectionCallbacksCannotDisconnectTheNewOne() {
        try (var access = new RemoteSessionAccess()) {
            var grant = pair(access);
            var oldLease = access.connect(grant.sessionId(), grant.credential());
            access.disconnected(oldLease);
            assertEquals(DISCONNECTED, access.status().phase());
            failure(UNAUTHORIZED, () -> access.authorizeConnection(oldLease));
            var newLease = access.connect(grant.sessionId(), grant.credential());
            assertNotEquals(oldLease, newLease);
            access.disconnected(oldLease);
            access.disconnected(null);
            access.disconnected(UUID.randomUUID());
            assertEquals(CONNECTED, access.status().phase());
            assertDoesNotThrow(() -> access.authorizeConnection(newLease));
            failure(UNAUTHORIZED, () -> access.authorizeConnection(oldLease));
        }
    }

    @Test
    void revokeInvalidatesCredentialsLeasesAndClientIdentityImmediately() {
        try (var access = new RemoteSessionAccess()) {
            var grant = pair(access);
            var lease = access.connect(grant.sessionId(), grant.credential());
            access.revoke();
            assertEquals(WAITING, access.status().phase());
            assertNull(access.status().client());
            assertNull(access.status().pending());
            failure(UNAUTHORIZED, () -> access.authorize(grant.sessionId(), grant.credential()));
            failure(UNAUTHORIZED, () -> access.authorizeConnection(lease));
            failure(UNAUTHORIZED, () -> access.connect(grant.sessionId(), grant.credential()));
            var nextGrant = pair(access);
            assertNotEquals(grant.credential(), nextGrant.credential());
            assertDoesNotThrow(() -> access.connect(nextGrant.sessionId(), nextGrant.credential()));
        }
    }

    @Test
    void stoppedOrRestartedHostsNeverAcceptOldCredentials() {
        var access = new RemoteSessionAccess();
        var grant = pair(access);
        var lease = access.connect(grant.sessionId(), grant.credential());
        access.close();
        access.close();
        assertEquals(RemoteSessionAccess.Phase.STOPPED, access.status().phase());
        assertNull(access.status().client());
        assertNull(access.status().pending());
        failure(STOPPED, access::pairingCode);
        failure(STOPPED, access::regenerateCode);
        failure(STOPPED, access::revoke);
        failure(STOPPED, () -> access.requestApproval("12345678", CLIENT));
        failure(STOPPED, () -> access.authorize(grant.sessionId(), grant.credential()));
        failure(STOPPED, () -> access.authorizeConnection(lease));
        failure(STOPPED, () -> access.connect(grant.sessionId(), grant.credential()));
        try (var restarted = new RemoteSessionAccess()) {
            assertNotEquals(grant.sessionId(), restarted.status().sessionId());
            failure(UNAUTHORIZED, () -> restarted.connect(grant.sessionId(), grant.credential()));
        }
    }

    @Test
    void codesExpireAtTheBoundaryAndRequireExplicitHostRegeneration() {
        var clock = new TestClock();
        try (var access = new RemoteSessionAccess(clock)) {
            var code = access.pairingCode();
            assertEquals(clock.instant().plus(Duration.ofMinutes(5)), code.expiresAt());
            clock.advance(Duration.ofMinutes(5));
            failure(EXPIRED, () -> access.requestApproval(code.value(), CLIENT));
            assertEquals(WAITING, access.status().phase());
            var fresh = access.regenerateCode();
            assertEquals(clock.instant().plus(Duration.ofMinutes(5)), fresh.expiresAt());
            assertNotNull(access.requestApproval(fresh.value(), CLIENT));
        }
    }

    @Test
    void approvalExpiresAndCannotBeApprovedFromAStaleHostDialog() {
        var clock = new TestClock();
        try (var access = new RemoteSessionAccess(clock)) {
            var request = access.requestApproval(access.pairingCode().value(), CLIENT);
            assertEquals(clock.instant().plus(Duration.ofMinutes(1)), request.expiresAt());
            clock.advance(Duration.ofMinutes(1));
            failure(UNKNOWN_REQUEST, () -> access.approve(request.id()));
            assertEquals(WAITING, access.status().phase());
            assertNull(access.status().pending());
            var next = access.requestApproval(access.pairingCode().value(), CLIENT);
            assertNotEquals(request.id(), next.id());
            failure(UNKNOWN_REQUEST, () -> access.reject(request.id()));
            assertEquals(next, access.status().pending());
        }
    }

    @Test
    void rejectedCancelledAndRegeneratedRequestsCannotLaterGainAccess() {
        try (var access = new RemoteSessionAccess()) {
            var rejected = access.requestApproval(access.pairingCode().value(), CLIENT);
            access.reject(rejected.id());
            assertEquals(WAITING, access.status().phase());
            failure(UNKNOWN_REQUEST, () -> access.approve(rejected.id()));
            var cancelled = access.requestApproval(access.pairingCode().value(), CLIENT);
            access.cancelApproval(cancelled.id());
            failure(UNKNOWN_REQUEST, () -> access.approve(cancelled.id()));
            var replaced = access.requestApproval(access.pairingCode().value(), CLIENT);
            access.regenerateCode();
            failure(UNKNOWN_REQUEST, () -> access.approve(replaced.id()));
            assertNull(access.status().pending());
            assertEquals(WAITING, access.status().phase());
        }
    }

    @Test
    void attemptsAreBoundedAcrossRegenerationAndResetOnlyAfterTheWindow() {
        var clock = new TestClock();
        try (var access = new RemoteSessionAccess(clock)) {
            for (int i = 0; i < 5; i++) {
                failure(INVALID_CODE, () -> access.requestApproval("not-code", CLIENT));
                access.regenerateCode();
            }
            var correct = access.pairingCode().value();
            failure(RATE_LIMITED, () -> access.requestApproval(correct, CLIENT));
            clock.advance(Duration.ofSeconds(59));
            failure(RATE_LIMITED, () -> access.requestApproval(correct, CLIENT));
            clock.advance(Duration.ofSeconds(1));
            assertNotNull(access.requestApproval(correct, CLIENT));
        }
    }

    @Test
    void revokeDoesNotBypassTheVerificationRateLimit() {
        try (var access = new RemoteSessionAccess()) {
            for (int i = 0; i < 5; i++) {
                failure(INVALID_CODE, () -> access.requestApproval(null, CLIENT));
            }
            access.revoke();
            failure(RATE_LIMITED, () -> access.requestApproval(access.pairingCode().value(), CLIENT));
        }
    }

    @Test
    void credentialsAreBoundToTheSessionAndNeverExposedInDiagnostics() {
        try (var access = new RemoteSessionAccess()) {
            var code = access.pairingCode();
            assertFalse(code.toString().contains(code.value()));
            var pending = access.requestApproval(code.value(), CLIENT);
            assertFalse(pending.toString().contains(code.value()));
            var grant = access.approve(pending.id());
            assertFalse(grant.toString().contains(grant.credential()));
            assertFalse(access.status().toString().contains(grant.credential()));
            failure(UNAUTHORIZED, () -> access.authorize(UUID.randomUUID(), grant.credential()));
            failure(UNAUTHORIZED, () -> access.authorize(null, grant.credential()));
            failure(UNAUTHORIZED, () -> access.authorize(grant.sessionId(), null));
            failure(UNAUTHORIZED, () -> access.authorize(grant.sessionId(), ""));
            failure(UNAUTHORIZED, () -> access.authorize(grant.sessionId(), "x".repeat(43)));
            failure(UNAUTHORIZED, () -> access.authorizeConnection(null));
        }
    }

    @Test
    void displayClaimsRejectBlankControlCharacterAndOversizedValues() {
        assertEquals("Desktop", new RemoteSessionAccess.Client(" Desktop ", " 192.168.1.2 ").name());
        assertThrows(NullPointerException.class, () -> new RemoteSessionAccess.Client(null, "address"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionAccess.Client("", "address"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionAccess.Client("name\nhost", "address"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionAccess.Client("name", "\0address"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionAccess.Client("n".repeat(101), "address"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionAccess.Client("name", "a".repeat(201)));
        try (var access = new RemoteSessionAccess()) {
            assertThrows(NullPointerException.class, () -> access.requestApproval(access.pairingCode().value(), null));
            assertEquals(WAITING, access.status().phase());
            failure(UNKNOWN_REQUEST, () -> access.approve(null));
        }
    }

    @Test
    void revokeAlsoCancelsApprovalWithoutReusingItsRequest() {
        try (var access = new RemoteSessionAccess()) {
            var pending = access.requestApproval(access.pairingCode().value(), CLIENT);
            access.revoke();
            failure(UNKNOWN_REQUEST, () -> access.approve(pending.id()));
            var next = access.requestApproval(access.pairingCode().value(), CLIENT);
            assertNotEquals(pending.id(), next.id());
        }
    }

    private static RemoteSessionAccess.Grant pair(RemoteSessionAccess access) {
        var request = access.requestApproval(access.pairingCode().value(), CLIENT);
        return access.approve(request.id());
    }

    private static void failure(RemoteSessionAccess.Failure expected, org.junit.jupiter.api.function.Executable action) {
        var exception = assertThrows(RemoteSessionAccess.AccessException.class, action);
        assertEquals(expected, exception.failure());
        assertFalse(exception.getMessage().isBlank());
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
