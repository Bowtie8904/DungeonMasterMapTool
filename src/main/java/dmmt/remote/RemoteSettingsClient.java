package dmmt.remote;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Application-thread settings protocol on B. The transport takes {@link #request()} off-thread and
 * dispatches responses back to {@link #receive}. No network IO or optimistic settings edits happen here.
 */
public final class RemoteSettingsClient implements AutoCloseable {
    private static final int DEFAULT_CAPACITY = 32;

    public enum Phase { CONNECTED, PAUSED, RESYNC_REQUIRED, CLOSED }

    public enum Outcome { ACCEPTED, CONFLICT, INVALID, SAVE_FAILED, CANCELLED, UNKNOWN }

    /** UI notification; callbacks must not perform network IO or throw. */
    public record Notice(UUID commandId, String key, Outcome outcome, String message) {
    }

    private final Thread owner = Thread.currentThread();
    private final UUID sessionId;
    private final AppSettings.HostContext context;
    private final Consumer<Notice> notices;
    private final int capacity;
    private final ArrayDeque<AppSettings.RemoteEdit> queued = new ArrayDeque<>();
    private HostSettingsAuthority.Request inFlight;
    private Phase phase = Phase.CONNECTED;
    private long nextSequence;

    public RemoteSettingsClient(AppSettings settings, HostSettingsAuthority.State baseline, Consumer<Notice> notices) {
        this(settings, baseline, notices, DEFAULT_CAPACITY);
    }

    RemoteSettingsClient(AppSettings settings, HostSettingsAuthority.State baseline,
                         Consumer<Notice> notices, int capacity) {
        Objects.requireNonNull(settings, "settings");
        validateState(baseline);
        this.notices = Objects.requireNonNull(notices, "notices");
        if (capacity < 1) {
            throw new IllegalArgumentException("The pending settings capacity must be positive.");
        }
        this.capacity = capacity;
        sessionId = baseline.sessionId();
        nextSequence = baseline.nextSequence();
        context = settings.useHostSettings(baseline.settings(), this::enqueue);
    }

    public Phase phase() {
        requireOwner();
        return phase;
    }

    public int pendingCount() {
        requireOwner();
        return queued.size() + (inFlight == null ? 0 : 1);
    }

    /**
     * Returns the same immutable request until its outcome is known. The transport decides when to send
     * or retry it, and must not wrap it in a different sequence/command ID after a lost acknowledgement.
     */
    public HostSettingsAuthority.Request request() {
        requireConnected();
        if (inFlight == null && !queued.isEmpty()) {
            inFlight = new HostSettingsAuthority.Request(sessionId, nextSequence, queued.removeFirst());
        }
        return inFlight;
    }

    /** False for old duplicate acknowledgements; unknown/future or mismatched responses are protocol errors. */
    public boolean receive(HostSettingsAuthority.Result result) {
        requireConnected();
        Objects.requireNonNull(result, "result");
        validateState(result.state());
        Objects.requireNonNull(result.commandId(), "result.commandId");
        if (result.sequence() < 1) {
            throw new IllegalArgumentException("The settings acknowledgement sequence must be positive.");
        }
        if (result.sequence() < nextSequence) {
            return false;
        }
        if (inFlight == null || result.sequence() != inFlight.sequence()
                || !result.commandId().equals(inFlight.edit().commandId())
                || result.status() == null || result.message() == null) {
            throw new IllegalArgumentException("The settings acknowledgement does not match the pending command.");
        }
        if (result.status() == HostSettingsAuthority.Status.RESYNC_REQUIRED) {
            requireResync(result.message());
            return true;
        }
        if (!sessionId.equals(result.state().sessionId())
                || result.state().nextSequence() != nextSequence + 1
                || result.state().settings().revision() < inFlight.edit().baseRevision()) {
            throw new IllegalArgumentException("The settings acknowledgement has inconsistent session/sequence state.");
        }
        context.accept(result.state().settings());
        var edit = inFlight.edit();
        inFlight = null;
        nextSequence++;
        Outcome outcome = switch (result.status()) {
            case ACCEPTED -> Outcome.ACCEPTED;
            case CONFLICT -> Outcome.CONFLICT;
            case INVALID -> Outcome.INVALID;
            case SAVE_FAILED -> Outcome.SAVE_FAILED;
            case RESYNC_REQUIRED -> throw new IllegalStateException("Resynchronization was handled above.");
        };
        notices.accept(new Notice(edit.commandId(), edit.key(), outcome, result.message()));
        return true;
    }

    /** Push host-only changes without consuming the acknowledgement for an outstanding command. */
    public boolean update(HostSettingsAuthority.State state) {
        requireConnected();
        validateState(state);
        if (!sessionId.equals(state.sessionId())
                || state.nextSequence() < nextSequence
                || state.nextSequence() > nextSequence + (inFlight == null ? 0 : 1)) {
            requireResync("The host settings session or command sequence changed; reconnect is required.");
            return false;
        }
        return context.accept(state.settings());
    }

    /** Keep an in-flight request for receipt recovery, but explicitly cancel all unsent queued edits. */
    public void pause() {
        requireOwner();
        if (phase == Phase.CLOSED || phase == Phase.RESYNC_REQUIRED || phase == Phase.PAUSED) {
            return;
        }
        context.pause();
        phase = Phase.PAUSED;
        cancelQueued("The connection was lost before this setting was sent.");
    }

    /**
     * The same host epoch and at most one unacknowledged command are required for continuity.
     * An acknowledged-but-unreceived command is retried unchanged to retrieve its original receipt.
     */
    public boolean resume(HostSettingsAuthority.State baseline) {
        requireOwner();
        validateState(baseline);
        if (phase != Phase.PAUSED) {
            throw new IllegalStateException("Only a paused settings client can resume.");
        }
        if (!sessionId.equals(baseline.sessionId())
                || baseline.nextSequence() < nextSequence
                || baseline.nextSequence() > nextSequence + (inFlight == null ? 0 : 1)) {
            requireResync("Settings continuity could not be verified; start a fresh synchronized session.");
            return false;
        }
        context.resume(baseline.settings());
        phase = Phase.CONNECTED;
        return true;
    }

    @Override
    public void close() {
        requireOwner();
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        context.close();
        cancelQueued("Remote settings closed before this setting was sent.");
        reportUnknown("The remote settings session closed before the command outcome was confirmed.");
    }

    private void enqueue(AppSettings.RemoteEdit edit) {
        requireConnected();
        if (pendingCount() >= capacity) {
            throw new IllegalStateException("The remote settings queue is full; wait for the host.");
        }
        queued.addLast(edit);
    }

    private void requireResync(String message) {
        if (phase == Phase.CONNECTED) {
            context.pause();
        }
        phase = Phase.RESYNC_REQUIRED;
        cancelQueued("Resynchronization is required; this setting was not sent.");
        reportUnknown(message);
    }

    private void cancelQueued(String message) {
        while (!queued.isEmpty()) {
            var edit = queued.removeFirst();
            notices.accept(new Notice(edit.commandId(), edit.key(), Outcome.CANCELLED, message));
        }
    }

    private void reportUnknown(String message) {
        if (inFlight != null) {
            var edit = inFlight.edit();
            inFlight = null;
            notices.accept(new Notice(edit.commandId(), edit.key(), Outcome.UNKNOWN, message));
        }
    }

    private static void validateState(HostSettingsAuthority.State state) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(state.sessionId(), "state.sessionId");
        Objects.requireNonNull(state.settings(), "state.settings");
        if (state.nextSequence() < 1) {
            throw new IllegalArgumentException("Host settings next sequence must be positive.");
        }
    }

    private void requireConnected() {
        requireOwner();
        if (phase != Phase.CONNECTED) {
            throw new IllegalStateException("Remote settings are not connected: " + phase);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Remote client settings must run on their owning application thread.");
        }
    }
}
