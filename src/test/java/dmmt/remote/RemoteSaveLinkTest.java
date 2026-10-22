package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSaveLinkTest {
    @Test
    void noSavePollingAndReconnectRecoversExactPendingReceiptWithoutSavingTwice() {
        var f = new Fixture();
        for (int i = 0; i < 100; i++) f.link.tick();
        assertTrue(f.queries.isEmpty());
        f.loseReceipt = true;
        f.link.save(f.host.workspace.state(), target -> fail("Existing maps must not ask for a destination."));
        assertTrue(f.link.busy());
        f.host.persistence.complete("Saved on A.");
        assertEquals(1, f.host.writes);
        assertTrue(f.link.busy());
        f.connected = false;
        f.link.tick();
        f.connected = true;
        f.loseReceipt = false;
        f.link.tick();
        assertFalse(f.link.busy());
        assertEquals(1, f.host.writes);
        assertEquals(f.queries.get(1), f.queries.get(2));
        assertEquals("Saved on A.", f.notices.getLast());
        int calls = f.queries.size();
        for (int i = 0; i < 100; i++) f.link.tick();
        assertEquals(calls, f.queries.size());
    }

    @Test
    void lateTransportFailureAfterReconnectStillRecoversTheExactSave() {
        var host = new RemoteSavesTest.Fixture();
        var replies = new java.util.ArrayDeque<Runnable>();
        var first = new CompletableFuture<RemoteSaves.Update>();
        var requests = new ArrayList<RemoteSaves.Request>();
        var live = new java.util.concurrent.atomic.AtomicBoolean(true);
        var notices = new ArrayList<String>();
        var link = new RemoteSaveLink(new RemoteSaveLink.Connection() {
            @Override public boolean connected() { return live.get(); }
            @Override public CompletableFuture<RemoteSaves.Update> query(RemoteSaves.Query query) {
                if (query.request() == null) return host.saves.exchange(query, () -> {});
                requests.add(query.request());
                var saved = host.saves.exchange(query, () -> {});
                return requests.size() == 1 ? first : saved;
            }
        }, replies::add, notices::add);
        link.save(host.workspace.state(), target -> null);
        replies.removeFirst().run();
        host.persistence.complete("Saved.");
        first.completeExceptionally(new IOException("Lost receipt"));
        live.set(false);
        link.tick();
        live.set(true);
        link.tick();
        replies.removeFirst().run();
        link.tick();
        replies.removeFirst().run();
        assertFalse(link.busy());
        assertEquals(requests.getFirst(), requests.getLast());
        assertEquals(1, host.writes);
    }

    @Test
    void cancelledDestinationOrDisconnectDuringDialogCannotSubmitASave() {
        var f = new Fixture();
        f.host.target = new RemoteSaves.Target(false, "New map", "E:\\Maps");
        f.link.save(f.host.workspace.state(), target -> null);
        assertFalse(f.link.busy());
        assertEquals(0, f.host.writes);
        f.link.save(f.host.workspace.state(), target -> {
            f.connected = false;
            return new RemoteSaveLink.Destination("Dungeon", target.libraryFolder());
        });
        assertFalse(f.link.busy());
        assertEquals(0, f.host.writes);
    }

    @Test
    void staleMapInvalidDestinationAndLateCallbacksAreExplicitAndDoNotWrite() {
        var f = new Fixture();
        var old = f.host.workspace.state();
        f.host.project.getViews().setPlayerFrozen(true);
        f.host.workspace.observeHostCommit();
        f.link.save(old, target -> fail("Stale maps cannot open a save dialog."));
        assertTrue(f.notices.getLast().contains("changed"));
        assertEquals(0, f.host.writes);
        f.host.target = new RemoteSaves.Target(false, "New map", "E:\\Maps");
        f.link.save(f.host.workspace.state(), target -> new RemoteSaveLink.Destination(" ", "E:\\Maps"));
        assertTrue(f.notices.getLast().contains("Invalid"));
        assertFalse(f.link.busy());
        f.link.save(f.host.workspace.state(), target -> {
            f.link.close();
            return new RemoteSaveLink.Destination("Dungeon", "E:\\Maps");
        });
        assertEquals(0, f.host.writes);
    }

    @Test
    void asynchronousContinuationWaitsForPersistenceAndDistinguishesCancellationAndFailure() {
        var accepted = new Fixture();
        var completion = accepted.link.saveAsync(accepted.host.workspace.state(), target -> null);
        assertFalse(completion.isDone());
        accepted.host.persistence.complete("Persisted on A.");
        assertTrue(completion.join());

        var cancelled = new Fixture();
        cancelled.host.target = new RemoteSaves.Target(false, "New map", "E:\\Maps");
        assertFalse(cancelled.link.saveAsync(cancelled.host.workspace.state(), target -> null).join());
        assertEquals(0, cancelled.host.writes);

        var failed = new Fixture();
        var failure = failed.link.saveAsync(failed.host.workspace.state(), target -> null);
        failed.host.persistence.completeExceptionally(new IOException("Disk full"));
        assertTrue(failure.isCompletedExceptionally());
        assertThrows(java.util.concurrent.CompletionException.class, failure::join);

        var closed = new Fixture();
        var interrupted = closed.link.saveAsync(closed.host.workspace.state(), target -> null);
        closed.link.close();
        assertTrue(interrupted.isCompletedExceptionally());
    }

    private static final class Fixture {
        final RemoteSavesTest.Fixture host = new RemoteSavesTest.Fixture();
        final List<String> notices = new ArrayList<>();
        final List<RemoteSaves.Query> queries = new ArrayList<>();
        boolean connected = true, loseReceipt;
        final RemoteSaveLink link = new RemoteSaveLink(new RemoteSaveLink.Connection() {
            @Override public boolean connected() { return connected; }
            @Override public CompletableFuture<RemoteSaves.Update> query(RemoteSaves.Query query) {
                queries.add(query);
                var result = host.saves.exchange(query, () -> {});
                if (loseReceipt && query.request() != null) {
                    return result.thenCompose(update -> CompletableFuture.failedFuture(new IOException("Ack lost")));
                }
                return result;
            }
        }, Runnable::run, notices::add);
    }
}
