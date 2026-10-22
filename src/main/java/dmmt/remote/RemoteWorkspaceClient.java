package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Owner-thread workspace reconciliation. Transport IO never runs from this coordinator. */
public final class RemoteWorkspaceClient implements AutoCloseable {
    public enum Phase { CONNECTED, PAUSED, RESYNC_REQUIRED, CLOSED }

    public record Notice(UUID commandId, RemoteWorkspaceAuthority.Status outcome, String message) {
    }

    private final Thread owner = Thread.currentThread();
    private final UUID sessionId;
    private final Consumer<RemoteProjectDocument> display;
    private final Consumer<Notice> notices;
    private RemoteWorkspaceAuthority.State accepted;
    private RemoteWorkspaceAuthority.Request inFlight;
    private Phase phase = Phase.CONNECTED;
    private long nextSequence;

    public RemoteWorkspaceClient(RemoteWorkspaceAuthority.State baseline, Consumer<RemoteProjectDocument> display,
                                 Consumer<Notice> notices) {
        accepted = Objects.requireNonNull(baseline, "baseline");
        sessionId = baseline.sessionId();
        nextSequence = baseline.nextSequence();
        this.display = Objects.requireNonNull(display, "display");
        this.notices = Objects.requireNonNull(notices, "notices");
    }

    public Phase phase() {
        requireOwner();
        return phase;
    }

    public boolean canEdit() {
        requireOwner();
        return phase == Phase.CONNECTED && inFlight == null;
    }

    public RemoteWorkspaceAuthority.State accepted() {
        requireOwner();
        return accepted;
    }

    public RemoteWorkspaceAuthority.Request request() {
        requireConnected();
        return inFlight;
    }

    /** One completed gesture at a time. Local camera/tools remain available while it is acknowledged. */
    public RemoteWorkspaceAuthority.Request submit(RemoteProjectDocument edited, String label) {
        requireConnected();
        if (inFlight != null) {
            throw new IllegalStateException("Wait for the host to confirm the previous edit.");
        }
        inFlight = RemoteWorkspaceAuthority.diff(accepted, edited, label);
        return inFlight;
    }

    public boolean update(RemoteWorkspaceAuthority.State state) {
        requireConnected();
        if (!validateContinuity(state)) {
            return false;
        }
        if (state.revision() < accepted.revision() || state.nextSequence() < accepted.nextSequence()) {
            return false;
        }
        if (state.revision() == accepted.revision()
                && (!state.contextId().equals(accepted.contextId()) || !state.document().equals(accepted.document())
                || !state.changedAt().equals(accepted.changedAt()) || !state.assets().equals(accepted.assets())
                || !state.history().equals(accepted.history()))) {
            requireResync("The host sent inconsistent workspace revisions.");
            return false;
        }

        boolean changed = !accepted.document().equals(state.document())
                || !accepted.contextId().equals(state.contextId());
        accepted = state;
        if (changed) {
            display.accept(preview());
        }
        return changed;
    }

    public RemoteWorkspaceAuthority.Request submitHistory(RemoteWorkspaceAuthority.Operation operation) {
        requireConnected();
        if (inFlight != null) {
            throw new IllegalStateException("Wait for the host to confirm the previous operation.");
        }
        inFlight = RemoteWorkspaceAuthority.historyRequest(accepted, operation);
        return inFlight;
    }

    public boolean receive(RemoteWorkspaceAuthority.Result result) {
        requireConnected();
        Objects.requireNonNull(result, "result");
        if (result.sequence() < nextSequence) {
            return false;
        }
        if (inFlight == null || result.sequence() != inFlight.sequence()
                || !Objects.equals(result.commandId(), inFlight.commandId()) || result.status() == null
                || result.message() == null) {
            throw new IllegalArgumentException("The workspace acknowledgement does not match the pending command.");
        }
        if (result.status() == RemoteWorkspaceAuthority.Status.RESYNC_REQUIRED) {
            requireResync(result.message());
            return true;
        }
        if (!validateContinuity(result.state()) || result.state().nextSequence() != nextSequence + 1) {
            requireResync("The workspace command sequence could not be verified.");
            return true;
        }
        if (result.state().revision() >= accepted.revision()) {
            accepted = result.state();
        }
        UUID command = inFlight.commandId();
        inFlight = null;
        nextSequence++;
        display.accept(accepted.document());
        notices.accept(new Notice(command, result.status(), result.message()));
        return true;
    }

    public void pause() {
        requireOwner();
        if (phase == Phase.CONNECTED) {
            phase = Phase.PAUSED;
            display.accept(accepted.document());
        }
    }

    public boolean resume(RemoteWorkspaceAuthority.State baseline) {
        requireOwner();
        if (phase != Phase.PAUSED) {
            throw new IllegalStateException("Only a paused workspace can reconnect.");
        }
        if (!validateContinuity(baseline) || baseline.revision() < accepted.revision()) {
            requireResync("The host workspace continuity could not be verified.");
            return false;
        }
        accepted = baseline;
        phase = Phase.CONNECTED;
        display.accept(preview());
        return true;
    }

    @Override
    public void close() {
        requireOwner();
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        if (inFlight != null) {
            reportUnconfirmed("Disconnected before the host confirmed this edit; its outcome is unknown.");
        }
    }

    private RemoteProjectDocument preview() {
        if (inFlight == null || !accepted.contextId().equals(inFlight.contextId())
                || accepted.nextSequence() > inFlight.sequence()) {
            return accepted.document();
        }
        Map<String, JsonNode> replacements = new LinkedHashMap<>();
        Set<String> removals = new LinkedHashSet<>();
        for (var change : inFlight.changes()) {
            if (change.replacement() == null) {
                removals.add(change.target());
            } else {
                replacements.put(change.target(), change.replacement());
            }
        }
        return accepted.document().with(replacements, removals);
    }

    private boolean validateContinuity(RemoteWorkspaceAuthority.State state) {
        Objects.requireNonNull(state, "state");
        if (!sessionId.equals(state.sessionId()) || state.nextSequence() < nextSequence
                || state.nextSequence() > nextSequence + (inFlight == null ? 0 : 1)) {
            requireResync("The host workspace session or command sequence changed.");
            return false;
        }
        return true;
    }

    private void requireResync(String message) {
        phase = Phase.RESYNC_REQUIRED;
        display.accept(accepted.document());
        reportUnconfirmed(message);
    }

    private void reportUnconfirmed(String message) {
        UUID commandId = inFlight == null ? null : inFlight.commandId();
        inFlight = null;
        notices.accept(new Notice(commandId, RemoteWorkspaceAuthority.Status.RESYNC_REQUIRED, message));
    }

    private void requireConnected() {
        requireOwner();
        if (phase != Phase.CONNECTED) {
            throw new IllegalStateException("The remote workspace is not connected: " + phase);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Remote workspace reconciliation must run on its owning application thread.");
        }
    }
}
