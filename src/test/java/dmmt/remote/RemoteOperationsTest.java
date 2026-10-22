package dmmt.remote;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteOperationsTest {
    private static final com.fasterxml.jackson.databind.JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

    @Test void exactPendingAndCompletedCommandsNeverRepeatTheMutation() {
        var calls = new AtomicInteger();
        var work = new CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
        var host = host(calls, work);
        var baseline = host.exchange(new RemoteOperations.Query(null, null, null, null), () -> {}).join();
        var command = new RemoteOperations.Command(baseline.sessionId(), UUID.randomUUID(), 1, "test", "edit", EMPTY);
        var query = new RemoteOperations.Query(command, null, null, null);
        var first = host.exchange(query, () -> {});
        var duplicate = host.exchange(query, () -> {});
        assertEquals(1, calls.get());
        assertEquals(RemoteOperations.Status.RUNNING, first.join().status());
        assertEquals(RemoteOperations.Status.RUNNING, duplicate.join().status());
        work.complete(EMPTY);
        var receipt = host.exchange(query, () -> {}).join();
        assertEquals(RemoteOperations.Status.ACCEPTED, receipt.status());
        assertEquals(receipt, host.exchange(query, () -> {}).join());
        assertEquals(2, receipt.nextSequence());
        assertEquals(1, calls.get());
    }

    @Test void failuresConsumeSequenceAndCannotLaterBecomeWrites() {
        var calls = new AtomicInteger();
        var host = host(calls, CompletableFuture.failedFuture(new java.io.IOException("Disk unavailable.")));
        var state = host.exchange(new RemoteOperations.Query(null, null, null, null), () -> {}).join();
        var command = new RemoteOperations.Command(state.sessionId(), UUID.randomUUID(), 1, "test", "edit", EMPTY);
        var query = new RemoteOperations.Query(command, null, null, null);
        var failed = host.exchange(query, () -> {}).join();
        assertEquals(RemoteOperations.Status.FAILED, failed.status());
        assertEquals("Disk unavailable.", failed.error());
        assertNotNull(failed.failure());
        assertTrue(failed.failure().stackTrace().contains("java.io.IOException: Disk unavailable."));
        assertEquals(failed, host.exchange(query, () -> {}).join());
        assertEquals(1, calls.get());
        var replacement = new RemoteOperations.Command(state.sessionId(), UUID.randomUUID(), 1, "test", "edit", EMPTY);
        assertEquals(RemoteOperations.Status.RESYNC_REQUIRED,
                host.exchange(new RemoteOperations.Query(replacement, null, null, null), () -> {}).join().status());
    }

    @Test void revocationAndExpiredReceiptsDoNotExecuteMutations() {
        var calls = new AtomicInteger();
        var host = host(calls, CompletableFuture.completedFuture(EMPTY));
        var state = host.exchange(new RemoteOperations.Query(null, null, null, null), () -> {}).join();
        var first = new RemoteOperations.Command(state.sessionId(), UUID.randomUUID(), 1, "test", "edit", EMPTY);
        assertThrows(IllegalStateException.class, () -> host.exchange(
                new RemoteOperations.Query(first, null, null, null), () -> { throw new IllegalStateException("Revoked"); }));
        assertEquals(0, calls.get());
        for (int sequence = 1; sequence <= 257; sequence++) {
            var request = sequence == 1 ? first : new RemoteOperations.Command(state.sessionId(), UUID.randomUUID(),
                    sequence, "test", "edit", EMPTY);
            assertEquals(RemoteOperations.Status.ACCEPTED,
                    host.exchange(new RemoteOperations.Query(request, null, null, null), () -> {}).join().status());
        }
        assertEquals(RemoteOperations.Status.RESYNC_REQUIRED,
                host.exchange(new RemoteOperations.Query(first, null, null, null), () -> {}).join().status());
        assertEquals(257, calls.get());
    }

    @Test void queryHasNoMutationReceiptAndDoesNotAdvanceSequence() {
        var calls = new AtomicInteger();
        var host = host(calls, CompletableFuture.completedFuture(EMPTY));
        var reply = host.exchange(new RemoteOperations.Query(null, "test", "list", EMPTY), () -> {}).join();
        assertEquals(0, calls.get());
        assertEquals(1, reply.nextSequence());
        assertNull(reply.commandId());
        assertThrows(IllegalArgumentException.class, () -> new RemoteOperations.Query(null, "test", null, EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new RemoteOperations.Query(null, "../test", "edit", EMPTY));
    }

    @Test void clientOperationFailurePreservesHostDetailsAndNotifiesBeforeCompleting() {
        var host = host(new AtomicInteger(), CompletableFuture.failedFuture(
                new java.io.IOException("Preview publication failed.", new java.nio.file.NoSuchFileException("map.png"))));
        var notified = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var link = new RemoteOperationLink(() -> true, query -> host.exchange(query, () -> {}),
                (direction, asset) -> CompletableFuture.completedFuture(asset), null, null, Runnable::run, Runnable::run);
        link.setErrorHandler(notified::set);
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> link.command("test", "edit", EMPTY).join());
        var reported = assertInstanceOf(RemoteFailure.HostException.class, failure.getCause());
        assertSame(reported, notified.get());
        assertTrue(reported.details().stackTrace().contains("NoSuchFileException"));
        assertTrue(reported.details().stackTrace().contains("map.png"));
    }

    @Test void clientRecoversOnlyTheExactInFlightCommandAfterLostReply() {
        var calls = new AtomicInteger();
        var online = new AtomicBoolean(true);
        var loseReply = new AtomicBoolean(true);
        var host = host(calls, CompletableFuture.completedFuture(EMPTY));
        var link = new RemoteOperationLink(online::get, query -> {
            var receipt = host.exchange(query, () -> {});
            if (query.command() != null && loseReply.getAndSet(false)) {
                online.set(false);
                return CompletableFuture.failedFuture(new java.io.IOException("Reply lost"));
            }
            return receipt;
        }, (direction, asset) -> CompletableFuture.completedFuture(asset), null, null, Runnable::run, Runnable::run);
        var result = link.command("test", "edit", EMPTY);
        assertFalse(result.isDone());
        assertEquals(1, calls.get());
        assertTrue(link.command("test", "edit", EMPTY).isCompletedExceptionally());
        online.set(true);
        try { Thread.sleep(260); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        link.tick();
        assertEquals(EMPTY, result.join());
        assertEquals(1, calls.get());
    }

    @Test void closedSessionAndChangedEpochCannotReplayAnUncertainCommand() {
        var calls = new AtomicInteger();
        var host = host(calls, CompletableFuture.completedFuture(EMPTY));
        var old = host.exchange(new RemoteOperations.Query(null, null, null, null), () -> {}).join();
        var command = new RemoteOperations.Command(UUID.randomUUID(), UUID.randomUUID(), 1, "test", "edit", EMPTY);
        assertNotEquals(old.sessionId(), command.sessionId());
        assertEquals(RemoteOperations.Status.RESYNC_REQUIRED,
                host.exchange(new RemoteOperations.Query(command, null, null, null), () -> {}).join().status());
        host.close();
        assertTrue(host.exchange(new RemoteOperations.Query(command, null, null, null), () -> {}).isCompletedExceptionally());
        assertEquals(0, calls.get());
    }

    private static RemoteOperations host(AtomicInteger calls, CompletableFuture<com.fasterxml.jackson.databind.JsonNode> result) {
        var host = new RemoteOperations(Runnable::run);
        host.register("test", new RemoteOperations.Endpoint() {
            @Override public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> query(String action, com.fasterxml.jackson.databind.JsonNode payload) {
                return CompletableFuture.completedFuture(EMPTY);
            }
            @Override public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> execute(String action, com.fasterxml.jackson.databind.JsonNode payload) {
                calls.incrementAndGet();
                return result;
            }
        });
        return host;
    }
}
