package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemotePlayerLinkTest
{
    @Test
    void reconnectRecoversAcceptedButUnconfirmedCommandWithoutExecutingAgain()
    {
        var f = new Fixture();
        f.link.tick(f.host.context, null);
        f.loseAcknowledgement = true;
        assertTrue(f.link.control(RemotePlayerSession.Action.WINDOW, true, 0));
        assertTrue(f.host.visible);
        assertEquals(1, f.host.applied);
        f.connected = false;
        f.link.tick(f.host.context, null);
        assertFalse(f.link.control(RemotePlayerSession.Action.WINDOW, false, 0));
        f.connected = true;
        f.loseAcknowledgement = false;
        f.link.tick(f.host.context, null);
        assertEquals(2, f.requests.size());
        assertEquals(f.requests.getFirst(), f.requests.getLast());
        assertEquals(1, f.host.applied);
        f.link.tick(f.host.context, null);
        assertTrue(f.link.state().output().visible());
        assertTrue(f.link.control(RemotePlayerSession.Action.WINDOW, false, 0));
        assertFalse(f.host.visible);
    }

    @Test
    void pointersAreNeverRetriedAndLateCommandCallbacksCannotMutateClosedLink()
    {
        var f = new Fixture();
        f.link.tick(f.host.context, new RemotePlayerSession.Point(10, 20));
        assertTrue(f.link.ping(f.host.context, 50, 60, 500));
        assertEquals(1, f.pings);
        assertFalse(f.link.ping(UUID.randomUUID(), 50, 60, 500));
        f.connected = false;
        f.link.tick(f.host.context, null);
        f.connected = true;
        f.link.tick(f.host.context, null);
        assertEquals(1, f.pings);
        f.deferred = new CompletableFuture<>();
        assertTrue(f.link.control(RemotePlayerSession.Action.WINDOW, true, 0));
        int notices = f.notices.size();
        f.link.close();
        f.deferred.complete(f.host.exchange(f.requests.getLast()));
        f.link.tick(f.host.context, null);
        assertEquals(notices, f.notices.size());
        assertNull(f.laser);
    }

    private static final class Fixture
    {
        final RemotePlayerSessionTest.Fixture host = new RemotePlayerSessionTest.Fixture();
        boolean connected = true, loseAcknowledgement;
        int pings;
        RemotePlayerSession.Point laser;
        CompletableFuture<RemotePlayerSession.Update> deferred;
        final List<String> notices = new ArrayList<>();
        final List<RemotePlayerSession.Request> requests = new ArrayList<>();
        final RemotePlayerLink link = new RemotePlayerLink(new RemotePlayerLink.Connection()
        {
            @Override
            public boolean connected()
            {
                return connected;
            }

            @Override
            public RemotePlayerSession.State latest()
            {
                return host.session.state();
            }

            @Override
            public List<RemotePlayerSession.Pointer> pointers()
            {
                return List.of();
            }

            @Override
            public CompletableFuture<RemotePlayerSession.Update> send(RemotePlayerSession.Request request)
            {
                requests.add(request);
                if (deferred != null)
                    return deferred;
                var response = host.exchange(request);
                return loseAcknowledgement ? CompletableFuture.failedFuture(new IOException("Lost reply"))
                                           : CompletableFuture.completedFuture(response);
            }

            @Override
            public void laser(UUID context, RemotePlayerSession.Point point)
            {
                laser = point;
            }

            @Override
            public void ping(UUID context, RemotePlayerSession.Point point, long durationMillis)
            {
                pings++;
            }
        }, state -> {
        }, pointer -> {
        }, notices::add, Runnable::run);
    }
}
