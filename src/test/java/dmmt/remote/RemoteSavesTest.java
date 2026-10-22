package dmmt.remote;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSavesTest {
    @Test
    void acceptedSaveWaitsForPersistenceAndDuplicateRequestsJoinTheSameWork() {
        var f = new Fixture();
        var request = request(f.saves.state());
        var first = f.exchange(request);
        var retry = f.exchange(request);
        assertFalse(first.isDone());
        assertFalse(retry.isDone());
        assertEquals(1, f.writes);
        f.persistence.complete("Saved on A.");
        assertEquals(RemotePlayerSession.Status.ACCEPTED, first.join().receipt().status());
        assertEquals(first.join(), retry.join());
        assertEquals(first.join().receipt(), f.exchange(request).join().receipt());
        assertEquals(1, f.writes);
        assertEquals(2, first.join().state().nextSequence());
    }

    @Test
    void authorizationContextRevisionAndBusyChecksCannotStartFileWork() {
        var f = new Fixture();
        var request = request(f.saves.state());
        assertThrows(SecurityException.class, () -> f.saves.exchange(new RemoteSaves.Query(request),
                () -> { throw new SecurityException("Revoked"); }));
        assertEquals(0, f.writes);
        f.ready = false;
        var busy = f.exchange(request).join();
        assertEquals(RemotePlayerSession.Status.BUSY, busy.receipt().status());
        f.ready = true;
        assertEquals(busy.receipt(), f.exchange(request).join().receipt());
        assertEquals(0, f.writes, "An exact busy receipt must not later turn into a save.");
        var state = f.saves.state();
        var stale = new RemoteSaves.Request(state.sessionId(), UUID.randomUUID(), UUID.randomUUID(),
                state.nextSequence(), state.revision(), null, null);
        assertEquals(RemotePlayerSession.Status.CONFLICT, f.exchange(stale).join().receipt().status());
        var beforeFlush = request(f.saves.state());
        f.onReady = () -> {
            f.project.getViews().setPlayerFrozen(true);
            f.workspace.observeHostCommit();
        };
        assertEquals(RemotePlayerSession.Status.CONFLICT, f.exchange(beforeFlush).join().receipt().status());
        assertEquals(0, f.writes, "The revision must be checked after the readiness gate flushes host changes.");
    }

    @Test
    void failuresAreRetainedAndEvictedOrAlteredReceiptsNeverRepeatWrites() {
        var f = new Fixture();
        var first = request(f.saves.state());
        var result = f.exchange(first);
        f.persistence.completeExceptionally(new IOException("Disk full"));
        assertEquals(RemotePlayerSession.Status.FAILED, result.join().receipt().status());
        assertTrue(result.join().receipt().message().contains("Disk full"));
        assertEquals(result.join().receipt(), f.exchange(first).join().receipt());
        assertEquals(1, f.writes);
        var changed = new RemoteSaves.Request(first.sessionId(), first.contextId(), UUID.randomUUID(),
                first.sequence(), first.revision(), null, null);
        assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED, f.exchange(changed).join().receipt().status());
        f.ready = false;
        for (int i = 0; i < 256; i++) f.exchange(request(f.saves.state())).join();
        assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED, f.exchange(first).join().receipt().status());
        assertEquals(1, f.writes);
    }

    @Test
    void newDestinationMustMatchHostStateAndCloseDoesNotCancelAcceptedDiskWork() {
        var f = new Fixture();
        var prior = request(f.saves.state());
        f.target = new RemoteSaves.Target(false, "New map", "E:\\Maps");
        assertEquals(RemotePlayerSession.Status.CONFLICT, f.exchange(prior).join().receipt().status());
        var next = f.saves.state();
        var create = new RemoteSaves.Request(next.sessionId(), next.contextId(), UUID.randomUUID(),
                next.nextSequence(), next.revision(), "Dungeon", "E:\\Maps");
        var pending = f.exchange(create);
        f.saves.close();
        assertTrue(pending.isCompletedExceptionally());
        assertFalse(f.persistence.isCancelled(), "Stopping hosting must not interrupt an accepted file write.");
        f.persistence.complete("Saved.");
        assertEquals(1, f.writes);
    }

    @Test
    void wireRejectsMissingRequiredSaveFieldsAndUnboundedNames() throws Exception {
        var f = new Fixture();
        var message = RemoteWire.message(RemoteWire.Type.SAVE_QUERY, new RemoteSaves.Query(request(f.saves.state())));
        assertEquals(request(f.saves.state()).contextId(),
                RemoteWire.payload(message, RemoteSaves.Query.class).request().contextId());
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) message.payload();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.get("request")).remove("revision");
        var invalid = new RemoteWire.Message(message.id(), message.type(), payload);
        assertThrows(IOException.class, () -> RemoteWire.payload(invalid, RemoteSaves.Query.class));
        var state = f.saves.state();
        assertThrows(IllegalArgumentException.class, () -> new RemoteSaves.Request(state.sessionId(), state.contextId(),
                UUID.randomUUID(), 1, 0, "x".repeat(201), "E:\\Maps"));
    }

    static RemoteSaves.Request request(RemoteSaves.State state) {
        return new RemoteSaves.Request(state.sessionId(), state.contextId(), UUID.randomUUID(),
                state.nextSequence(), state.revision(), null, null);
    }

    static final class Fixture {
        final DmProject project = new DmProject();
        final RemoteWorkspaceAuthority workspace = new RemoteWorkspaceAuthority(() -> project, commit -> {});
        RemoteSaves.Target target = new RemoteSaves.Target(true, "Dungeon", "E:\\Maps");
        final CompletableFuture<String> persistence = new CompletableFuture<>();
        boolean ready = true;
        Runnable onReady = () -> {};
        int writes;
        final RemoteSaves saves = new RemoteSaves(workspace::state, () -> target,
                () -> { onReady.run(); return ready; },
                request -> { writes++; return persistence; }, Runnable::run);

        CompletableFuture<RemoteSaves.Update> exchange(RemoteSaves.Request request) {
            return saves.exchange(new RemoteSaves.Query(request), () -> {});
        }
    }
}
