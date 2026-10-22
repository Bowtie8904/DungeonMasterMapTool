package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemotePlayerSessionTest
{
    @Test
    void outputCommandsRecoverExactReceiptsAndProtectLocalHostChanges()
    {
        var f = new Fixture();
        var initial = f.session.state();
        var command = request(initial, RemotePlayerSession.Action.WINDOW, true, 0);
        var accepted = f.exchange(command);
        assertEquals(RemotePlayerSession.Status.ACCEPTED, accepted.receipt().status());
        assertTrue(accepted.state().output().visible());
        assertEquals(1, f.applied);
        assertEquals(accepted.receipt(), f.exchange(command).receipt());
        assertEquals(1, f.applied);
        f.scaleTest = true;
        var stale = f.exchange(request(accepted.state(), RemotePlayerSession.Action.SCALE_TEST, false, 0));
        assertEquals(RemotePlayerSession.Status.CONFLICT, stale.receipt().status());
        assertTrue(stale.state().output().scaleTest());
        assertEquals(1, f.applied);
        var reused = new RemotePlayerSession.Request(initial.sessionId(), command.commandId(),
                                                     stale.state().nextSequence(), stale.state().revision(), command.action(), false, 0);
        assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED, f.exchange(reused).receipt().status());
        assertEquals(1, f.applied);
    }

    @Test
    void busyDoesNotConsumeSequenceAndMissingOrChangedMonitorsNeverUseClientHardware()
    {
        var f = new Fixture();
        var command = request(f.session.state(), RemotePlayerSession.Action.SCREEN, false, 1);
        f.available = false;
        var busy = f.exchange(command);
        assertEquals(RemotePlayerSession.Status.BUSY, busy.receipt().status());
        assertEquals(command.sequence(), busy.state().nextSequence());
        f.available = true;
        assertEquals(RemotePlayerSession.Status.ACCEPTED, f.exchange(command).receipt().status());
        assertEquals(1, f.screen);
        var invalid = f.exchange(request(f.session.state(), RemotePlayerSession.Action.SCREEN, false, 2));
        assertEquals(RemotePlayerSession.Status.CONFLICT, invalid.receipt().status());
        assertEquals(1, f.screen);
        var old = f.session.state();
        f.monitors = List.of(f.monitors.getFirst());
        f.screen = 0;
        assertEquals(RemotePlayerSession.Status.CONFLICT,
                     f.exchange(request(old, RemotePlayerSession.Action.SCREEN, false, 1)).receipt().status());
    }

    @Test
    void evictedReceiptCannotRepeatASideEffect()
    {
        var f = new Fixture();
        var first = request(f.session.state(), RemotePlayerSession.Action.WINDOW, false, 0);
        f.exchange(first);
        for (int i = 0; i < 256; i++)
        {
            f.exchange(request(f.session.state(), RemotePlayerSession.Action.WINDOW, false, 0));
        }
        assertEquals(RemotePlayerSession.Status.RESYNC_REQUIRED, f.exchange(first).receipt().status());
        assertEquals(257, f.applied);
    }

    @Test
    void transientsAreBoundedContextScopedConsumedOnceAndClearedForReconnect()
    {
        var f = new Fixture();
        var point = new RemotePlayerSession.Point(42, 60);
        for (int i = 0; i < 100; i++)
            f.session.ping(point, 500);
        var frame = new RemotePlayerSession.Pointer(f.context, point, List.of(new RemotePlayerSession.Ping(point, 100)));
        var response = f.session.exchange(new RemotePlayerSession.Query(null, frame));
        assertEquals(RemotePlayerSession.MAX_PINGS, response.pointer().pings().size());
        assertEquals(frame, f.received.getLast());
        assertTrue(f.exchange(null).pointer().pings().isEmpty());
        f.session.ping(point, 500);
        f.context = UUID.randomUUID();
        var switched = f.session.exchange(new RemotePlayerSession.Query(null, frame));
        assertNull(f.received.getLast().laser());
        assertTrue(f.received.getLast().pings().isEmpty());
        assertTrue(switched.pointer().pings().isEmpty());
        f.session.ping(point, 500);
        f.session.resetPointers();
        assertTrue(f.exchange(null).pointer().pings().isEmpty());
        assertNull(f.received.getLast().laser());
    }

    @Test
    void localAwayAndBackChangesAndFailedSideEffectsCannotBeOverwrittenOrRetried()
    {
        var f = new Fixture();
        var initial = f.session.state();
        f.visible = true;
        f.session.outputChanged();
        f.visible = false;
        f.session.outputChanged();
        assertEquals(RemotePlayerSession.Status.CONFLICT,
                     f.exchange(request(initial, RemotePlayerSession.Action.WINDOW, true, 0)).receipt().status());
        assertFalse(f.visible);
        f.fail = true;
        var failed = request(f.session.state(), RemotePlayerSession.Action.WINDOW, true, 0);
        var receipt = f.exchange(failed).receipt();
        assertEquals(RemotePlayerSession.Status.FAILED, receipt.status());
        assertTrue(f.visible);
        assertEquals(receipt, f.exchange(failed).receipt());
        assertEquals(1, f.applied);
    }

    @Test
    void unpublishedExpiredPingsAreNotDeliveredLater() throws Exception
    {
        var f = new Fixture();
        f.session.ping(new RemotePlayerSession.Point(0, 0), 1);
        Thread.sleep(5);
        assertTrue(f.exchange(null).pointer().pings().isEmpty());
    }

    @Test
    void wireValidationRejectsUnboundedCoordinatesInvalidShapesAndUnknownFields() throws Exception
    {
        var query = new RemotePlayerSession.Query(null, RemotePlayerSession.Pointer.empty(null));
        var message = RemoteWire.message(RemoteWire.Type.PLAYER_QUERY, query);
        assertEquals(query, RemoteWire.payload(message, RemotePlayerSession.Query.class));
        assertThrows(IllegalArgumentException.class, () -> new RemotePlayerSession.Point(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new RemotePlayerSession.Point(0, 1e10));
        assertThrows(IllegalArgumentException.class, () -> new RemotePlayerSession.Pointer(null,
                                                                                           new RemotePlayerSession.Point(0, 0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemotePlayerSession.Ping(
                new RemotePlayerSession.Point(0, 0), 60_001));
        assertEquals(60_000, new RemotePlayerSession.Ping(new RemotePlayerSession.Point(0, 0), 60_000).remainingMillis());
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)message.payload();
        payload.put("path", "not-a-host-file");
        assertThrows(java.io.IOException.class, () -> RemoteWire.payload(new RemoteWire.Message(message.id(),
                                                                                                message.type(), payload), RemotePlayerSession.Query.class));
    }

    static RemotePlayerSession.Request request(RemotePlayerSession.State state, RemotePlayerSession.Action action,
                                               boolean enabled, int screen)
    {
        return new RemotePlayerSession.Request(state.sessionId(), UUID.randomUUID(), state.nextSequence(),
                                               state.revision(), action, enabled, screen);
    }

    static class Fixture
    {
        UUID context = UUID.randomUUID();
        boolean visible, scaleTest;
        boolean available = true;
        boolean fail;
        int screen, applied;
        List<RemotePlayerSession.Monitor> monitors = List.of(
                new RemotePlayerSession.Monitor("Laptop", 1920, 1080, 1, 1, 96),
                new RemotePlayerSession.Monitor("Projector", 1280, 720, 1, 1, 96));
        final List<RemotePlayerSession.Pointer> received = new ArrayList<>();
        final RemotePlayerSession session = new RemotePlayerSession(() -> context,
                                                                    () -> new RemotePlayerSession.Output(visible, scaleTest, visible ? 1280 : 0, visible ? 720 : 0,
                                                                                                         screen, monitors), () -> null, received::add, request -> {
            applied++;
            switch (request.action())
            {
                case WINDOW -> visible = request.enabled();
                case SCALE_TEST -> scaleTest = request.enabled();
                case SCREEN -> screen = request.screenIndex();
            }
            if (fail)
                throw new IllegalStateException("Simulated output failure after applying the command.");
        }, () -> available);

        RemotePlayerSession.Update exchange(RemotePlayerSession.Request request)
        {
            return session.exchange(new RemotePlayerSession.Query(request, RemotePlayerSession.Pointer.empty(context)));
        }
    }
}
