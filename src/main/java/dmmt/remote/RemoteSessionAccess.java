package dmmt.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * In-memory access lifecycle for one host and one remote client. This is not a transport or a TLS
 * implementation. Calls must come from an authenticated host UI or the eventual pairing-bound
 * encrypted transport; client-supplied names/addresses are display claims, never identity proof.
 */
public final class RemoteSessionAccess implements AutoCloseable {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteSessionAccess.class);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration APPROVAL_LIFETIME = Duration.ofMinutes(1);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Phase { WAITING, AWAITING_APPROVAL, CONNECTED, DISCONNECTED, STOPPED }

    public enum Failure { STOPPED, BUSY, RATE_LIMITED, INVALID_CODE, EXPIRED, UNKNOWN_REQUEST, UNAUTHORIZED }

    public static final class AccessException extends RuntimeException {
        private final Failure failure;

        private AccessException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** Host-only display value. Never advertise via discovery, logs or copyable permanent URLs. */
    public static final class PairingCode {
        private final String value;
        private final Instant expiresAt;

        private PairingCode(String value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        public String value() {
            return value;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        @Override
        public String toString() {
            return "PairingCode[redacted]";
        }
    }

    public record Client(String name, String address) {
        public Client {
            name = displayClaim(name, 100, "client name");
            address = displayClaim(address, 200, "client address");
        }

        private static String displayClaim(String value, int limit, String label) {
            Objects.requireNonNull(value, label);
            String trimmed = value.strip();
            if (trimmed.isEmpty() || trimmed.length() > limit
                    || trimmed.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid " + label + ".");
            }
            return trimmed;
        }
    }

    public record ApprovalRequest(UUID id, Client client, Instant expiresAt) {
    }

    /** Deliver only to the approved client through its original protected pairing connection. */
    public static final class Grant {
        private final UUID sessionId;
        private final String credential;

        private Grant(UUID sessionId, String credential) {
            this.sessionId = sessionId;
            this.credential = credential;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public String credential() {
            return credential;
        }

        @Override
        public String toString() {
            return "Grant[sessionId=" + sessionId + ", credential=redacted]";
        }
    }

    public record Status(UUID sessionId, Phase phase, Client client, ApprovalRequest pending) {
    }

    private final Clock clock;
    private final UUID sessionId = UUID.randomUUID();
    private Phase phase = Phase.WAITING;
    private PairingCode code;
    private ApprovalRequest pending;
    private Client approved;
    private byte[] credentialDigest;
    private UUID connectionLease;
    private Instant windowStart;
    private int attempts;

    public RemoteSessionAccess() {
        this(Clock.systemUTC());
    }

    RemoteSessionAccess(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        newCode();
    }

    /** Host UI only. A code is hidden while a client is approved, even while it is disconnected. */
    public synchronized PairingCode pairingCode() {
        requireRunning();
        expireApproval();
        if (approved != null) {
            throw error(Failure.BUSY, "Revoke the current client before pairing another.");
        }
        return code;
    }

    /** Host UI only. Cancels pending approval but does not reset rate-limit counters. */
    public synchronized PairingCode regenerateCode() {
        requireRunning();
        if (approved != null) {
            throw error(Failure.BUSY, "Revoke the current client before pairing another.");
        }
        pending = null;
        phase = Phase.WAITING;
        newCode();
        LOG.info("Pairing code regenerated");
        return code;
    }

    public synchronized ApprovalRequest requestApproval(String suppliedCode, Client client) {
        beginApprovalAttempt(client);
        Instant now = clock.instant();
        if (code == null) {
            throw error(Failure.BUSY, "Pairing codes are unavailable while a client is approved.");
        }
        if (!now.isBefore(code.expiresAt())) {
            throw error(Failure.EXPIRED, "The pairing code has expired; regenerate it on the host.");
        }
        if (suppliedCode == null || suppliedCode.length() != 8
                || !MessageDigest.isEqual(code.value().getBytes(StandardCharsets.US_ASCII),
                suppliedCode.getBytes(StandardCharsets.UTF_8))) {
            throw error(Failure.INVALID_CODE, "The pairing code is incorrect.");
        }
        return createApproval(client, now);
    }

    /** Trusted-LAN route: no access is granted until A approves this specific live request. */
    public synchronized ApprovalRequest requestApproval(Client client) {
        beginApprovalAttempt(client);
        return createApproval(client, clock.instant());
    }

    private void beginApprovalAttempt(Client client) {
        Objects.requireNonNull(client, "client");
        requireRunning();
        expireApproval();
        if (pending != null) {
            throw error(Failure.BUSY, "Another client is already awaiting host approval.");
        }
        Instant now = clock.instant();
        if (windowStart == null || !now.isBefore(windowStart.plus(ATTEMPT_WINDOW))) {
            windowStart = now;
            attempts = 0;
        }
        if (attempts >= MAX_ATTEMPTS) {
            throw error(Failure.RATE_LIMITED, "Too many pairing attempts; wait before trying again.");
        }
        attempts++;
    }

    private ApprovalRequest createApproval(Client client, Instant now) {
        pending = new ApprovalRequest(UUID.randomUUID(), client, now.plus(APPROVAL_LIFETIME));
        LOG.info("Access requested by '{}' ({}); awaiting host approval",
                RemoteSessionServer.safe(client.name()), RemoteSessionServer.safe(client.address()));
        // An approved client keeps its live connection until the host actually allows the replacement.
        if (approved == null) phase = Phase.AWAITING_APPROVAL;
        return pending;
    }

    /** Host UI only. Approval consumes the code, but does not mark the client connected yet. */
    public synchronized Grant approve(UUID requestId) {
        requirePending(requestId);
        approved = pending.client();
        pending = null;
        code = null;
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        credentialDigest = digest(credential);
        connectionLease = null; // Any previous client connection is superseded.
        phase = Phase.DISCONNECTED;
        LOG.info("Access granted to '{}'; credential issued", RemoteSessionServer.safe(approved.name()));
        return new Grant(sessionId, credential);
    }

    /** Host UI only. Rejecting a request invalidates the code used by that request. */
    public synchronized void reject(UUID requestId) {
        requirePending(requestId);
        LOG.info("Access request rejected by the host");
        dropPending();
    }

    /** Called only when the original protected pairing channel has been lost, before host approval. */
    public synchronized void cancelApproval(UUID requestId) {
        requirePending(requestId);
        LOG.info("Pending access request cancelled (pairing channel lost)");
        dropPending();
    }

    /**
     * Starts or resumes the one approved client connection. The returned lease identifies that particular
     * connection, so a late disconnect callback cannot terminate a later reconnect.
     */
    public synchronized UUID connect(UUID hostSession, String credential) {
        authorize(hostSession, credential);
        if (phase == Phase.CONNECTED) {
            throw error(Failure.BUSY, "The approved client is already connected.");
        }
        connectionLease = UUID.randomUUID();
        phase = Phase.CONNECTED;
        LOG.info("Approved client '{}' connected", RemoteSessionServer.safe(approved == null ? "" : approved.name()));
        return connectionLease;
    }

    public synchronized void authorize(UUID hostSession, String credential) {
        requireRunning();
        if (!sessionId.equals(hostSession) || credentialDigest == null || credential == null
                || credential.length() != 43 || !MessageDigest.isEqual(credentialDigest, digest(credential))) {
            throw error(Failure.UNAUTHORIZED, "Remote session access is not authorized.");
        }
    }

    /** Validate a live connection, not merely possession of a reconnect credential. */
    public synchronized void authorizeConnection(UUID lease) {
        requireRunning();
        if (phase != Phase.CONNECTED || lease == null || !lease.equals(connectionLease)) {
            throw error(Failure.UNAUTHORIZED, "This remote connection is no longer active.");
        }
    }

    public synchronized UUID transferLease(UUID hostSession, String credential) {
        authorize(hostSession, credential);
        authorizeConnection(connectionLease);
        return connectionLease;
    }

    public synchronized void disconnected(UUID lease) {
        requireRunning();
        if (lease != null && lease.equals(connectionLease)) {
            connectionLease = null;
            phase = Phase.DISCONNECTED;
            LOG.info("Approved client disconnected");
        }
    }

    /** Host UI only. Invalidates all existing credentials, leases and pending approvals immediately. */
    public synchronized void revoke() {
        requireRunning();
        clearClient();
        phase = Phase.WAITING;
        newCode();
        LOG.info("Client access revoked; credential invalidated and a new pairing code issued");
    }

    /** Host UI only; do not expose pending request IDs to unauthenticated discovery. */
    public synchronized Status status() {
        expireApproval();
        return new Status(sessionId, phase, approved, pending);
    }

    @Override
    public synchronized void close() {
        if (phase != Phase.STOPPED) {
            LOG.info("Session access stopped");
        }
        clearClient();
        code = null;
        phase = Phase.STOPPED;
    }

    private void requirePending(UUID requestId) {
        requireRunning();
        expireApproval();
        if (pending == null || !pending.id().equals(requestId)) {
            throw error(Failure.UNKNOWN_REQUEST, "The approval request expired, was cancelled or is unknown.");
        }
    }

    private void expireApproval() {
        if (pending != null && !clock.instant().isBefore(pending.expiresAt())) {
            dropPending();
        }
    }

    private void dropPending() {
        LOG.debug("Pending access request dropped");
        pending = null;
        if (approved == null) {
            phase = Phase.WAITING;
            newCode();
        } else {
            phase = connectionLease != null ? Phase.CONNECTED : Phase.DISCONNECTED;
        }
    }

    private void newCode() {
        code = new PairingCode(String.format(java.util.Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000)),
                clock.instant().plus(CODE_LIFETIME));
    }

    private void clearClient() {
        pending = null;
        approved = null;
        connectionLease = null;
        if (credentialDigest != null) {
            java.util.Arrays.fill(credentialDigest, (byte) 0);
            credentialDigest = null;
        }
    }

    private void requireRunning() {
        if (phase == Phase.STOPPED) {
            throw error(Failure.STOPPED, "Remote hosting has stopped.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The runtime does not support SHA-256.", impossible);
        }
    }

    private static AccessException error(Failure failure, String message) {
        LOG.warn("Access check failed: {}", failure);
        return new AccessException(failure, message);
    }
}
