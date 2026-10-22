package dmmt.remote;

import dmmt.service.HostSettingsAuthority;

import java.util.Objects;
import java.util.UUID;

/** Explicit payload types. Access secrets deliberately have redacted string representations. */
public final class RemoteProtocol {
    public record WorkspaceWait(String message, RemoteFailure failure) {
        public WorkspaceWait(String message) {
            this(message, null);
        }

        public WorkspaceWait {
            if (message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("A bounded workspace synchronization status is required.");
            }
        }
    }

    public enum ErrorCode {
        UNAUTHORIZED, BUSY, INVALID_CODE, RATE_LIMITED, EXPIRED, REJECTED, STOPPED, INVALID_MESSAGE
    }

    public record Empty() {
    }

    public record ControlRequest(String name) {
        public ControlRequest {
            name = new RemoteSessionAccess.Client(name, "pending").name();
        }
    }

    public record Pair(String code, String name) {
        public Pair {
            if (code == null || !code.matches("[0-9]{8}")) {
                throw new IllegalArgumentException("An eight-digit pairing code is required.");
            }
            name = new RemoteSessionAccess.Client(name, "pending").name();
        }

        @Override
        public String toString() {
            return "Pair[redacted]";
        }
    }

    public record Approval(UUID requestId, long expiresAtEpochMillis) {
        public Approval {
            Objects.requireNonNull(requestId, "requestId");
            if (expiresAtEpochMillis < 1) {
                throw new IllegalArgumentException("An approval expiration is required.");
            }
        }
    }

    public record ApprovalPoll(UUID requestId) {
        public ApprovalPoll {
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    /** A null epoch asks for a full baseline; known revisions permit a small unchanged heartbeat. */
    public record StateQuery(UUID settingsSessionId, long knownRevision, long nextSequence) {
        public StateQuery {
            if (settingsSessionId == null) {
                if (knownRevision != 0 || nextSequence != 0) {
                    throw new IllegalArgumentException("A full-state request must not claim a known revision.");
                }
            } else if (knownRevision < 0 || nextSequence < 1) {
                throw new IllegalArgumentException("A valid known settings revision and sequence are required.");
            }
        }

        public StateQuery(HostSettingsAuthority.State state) {
            this(state.sessionId(), state.settings().revision(), state.nextSequence());
        }

        public boolean matches(HostSettingsAuthority.State state) {
            return state.sessionId().equals(settingsSessionId)
                    && state.settings().revision() == knownRevision && state.nextSequence() == nextSequence;
        }
    }

    public record WorkspaceQuery(UUID sessionId, UUID contextId, long revision, long nextSequence) {
        public WorkspaceQuery {
            if ((sessionId == null) != (contextId == null) || revision < 0 || nextSequence < 0
                    || (sessionId == null && (revision != 0 || nextSequence != 0))
                    || (sessionId != null && nextSequence < 1)) {
                throw new IllegalArgumentException("Invalid workspace state query.");
            }
        }

        public WorkspaceQuery(RemoteWorkspaceAuthority.State state) {
            this(state.sessionId(), state.contextId(), state.revision(), state.nextSequence());
        }

        public boolean matches(RemoteWorkspaceAuthority.State state) {
            return state.sessionId().equals(sessionId) && state.contextId().equals(contextId)
                    && revision == state.revision() && nextSequence == state.nextSequence();
        }
    }

    public record Connect(UUID sessionId, String credential) {
        public Connect {
            Objects.requireNonNull(sessionId, "sessionId");
            if (credential == null || !credential.matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("A valid session credential is required.");
            }
        }

        @Override
        public String toString() {
            return "Connect[sessionId=" + sessionId + ", credential=redacted]";
        }
    }

    public record Failure(ErrorCode code, String message) {
        public Failure {
            Objects.requireNonNull(code, "code");
            if (message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("A bounded remote error description is required.");
            }
        }
    }

    private RemoteProtocol() {
    }

    static void validateState(HostSettingsAuthority.State state) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(state.sessionId(), "state.sessionId");
        Objects.requireNonNull(state.settings(), "state.settings");
        if (state.nextSequence() < 1) {
            throw new IllegalArgumentException("The host settings command sequence must be positive.");
        }
    }
}
