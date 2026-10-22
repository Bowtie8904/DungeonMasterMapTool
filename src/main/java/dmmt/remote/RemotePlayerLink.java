package dmmt.remote;

import dmmt.log.Log;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Owner-thread output controls. Durable requests recover receipts; pointers never retry.
 */
public final class RemotePlayerLink implements AutoCloseable
{
    public interface Connection
    {
        boolean connected();

        RemotePlayerSession.State latest();

        List<RemotePlayerSession.Pointer> pointers();

        CompletableFuture<RemotePlayerSession.Update> send(RemotePlayerSession.Request request);

        void laser(UUID context, RemotePlayerSession.Point point);

        void ping(UUID context, RemotePlayerSession.Point point, long durationMillis);
    }

    public static Connection connection(RemoteSessionClient client)
    {
        client.enablePlayer();
        return new Connection()
        {
            @Override
            public boolean connected()
            {
                return client.phase() == RemoteSessionClient.Phase.SETTINGS_READY;
            }

            @Override
            public RemotePlayerSession.State latest()
            {
                return client.latestPlayer();
            }

            @Override
            public List<RemotePlayerSession.Pointer> pointers()
            {
                return client.drainPlayerPointers();
            }

            @Override
            public CompletableFuture<RemotePlayerSession.Update> send(RemotePlayerSession.Request request)
            {
                return client.controlPlayer(request);
            }

            @Override
            public void laser(UUID context, RemotePlayerSession.Point point)
            {
                client.playerLaser(context, point);
            }

            @Override
            public void ping(UUID context, RemotePlayerSession.Point point, long durationMillis)
            {
                client.playerPing(context, point, durationMillis);
            }
        };
    }

    private final Thread owner = Thread.currentThread();
    private static final Log LOG = Log.of(RemotePlayerLink.class);
    private final Connection connection;
    private final Consumer<RemotePlayerSession.State> display;
    private final Consumer<RemotePlayerSession.Pointer> pointers;
    private final Consumer<String> notices;
    private final Executor ownerExecutor;
    private RemotePlayerSession.State state;
    private RemotePlayerSession.Request pending;
    private boolean sending;
    private boolean uncertain;
    private boolean closed;

    public RemotePlayerLink(Connection connection, Consumer<RemotePlayerSession.State> display,
                            Consumer<RemotePlayerSession.Pointer> pointers, Consumer<String> notices, Executor ownerExecutor)
    {
        this.connection = Objects.requireNonNull(connection);
        this.display = Objects.requireNonNull(display);
        this.pointers = Objects.requireNonNull(pointers);
        this.notices = Objects.requireNonNull(notices);
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor);
    }

    public RemotePlayerSession.State state()
    {
        requireOwner();
        return state;
    }

    public void tick(UUID displayedContext, RemotePlayerSession.Point laser)
    {
        requireOwner();
        if (closed)
            return;
        if (!connection.connected())
        {
            pointers.accept(RemotePlayerSession.Pointer.empty(null));
            return;
        }
        var latest = connection.latest();
        if (latest != null && !latest.equals(state))
        {
            state = latest;
            display.accept(state);
        }
        connection.laser(displayedContext, laser);
        for (var pointer : connection.pointers())
        {
            if (Objects.equals(displayedContext, pointer.contextId()))
                pointers.accept(pointer);
        }
        if (pending != null && !sending && !uncertain && state != null)
        {
            if (!pending.sessionId().equals(state.sessionId()))
            {
                uncertain = true;
                LOG.warn("The host output session changed; synchronization is required");
                notices.accept("The host output session changed; disconnect and synchronize again.");
                return;
            }
            send();
        }
    }

    public boolean control(RemotePlayerSession.Action action, boolean enabled, int screenIndex)
    {
        requireOwner();
        if (closed || uncertain || !connection.connected() || state == null || pending != null)
        {
            notices.accept("Wait for the host player-output state or pending acknowledgement.");
            return false;
        }
        pending = new RemotePlayerSession.Request(state.sessionId(), UUID.randomUUID(), state.nextSequence(),
                                                  state.revision(), action, enabled, screenIndex);
        LOG.info("Requesting host player output {} (enabled={}, screen {})", action, enabled, screenIndex);
        notices.accept("Updating player output on the host...");
        send();
        return true;
    }

    public boolean ping(UUID context, double x, double y, long durationMillis)
    {
        requireOwner();
        if (closed || !connection.connected() || state == null || !Objects.equals(state.contextId(), context))
        {
            notices.accept("The host player view is not synchronized; the ping was not sent.");
            return false;
        }
        try
        {
            connection.ping(context, new RemotePlayerSession.Point(x, y), durationMillis);
            return true;
        }
        catch (IllegalStateException busy)
        {
            LOG.debug("Ping not sent", busy);
            notices.accept(busy.getMessage());
            return false;
        }
    }

    private void send()
    {
        sending = true;
        var request = pending;
        connection.send(request).whenComplete((update, failure) -> ownerExecutor.execute(() -> {
            if (closed || pending != request)
                return;
            sending = false;
            if (failure != null)
            {
                LOG.warn("Player-output outcome unconfirmed", failure);
                notices.accept("Player-output outcome unconfirmed; reconnect to recover its exact receipt.");
                return;
            }
            var receipt = update.receipt();
            var latest = connection.latest();
            if (latest != null && request.sessionId().equals(latest.sessionId())
                    && (state == null || latest.revision() >= state.revision()
                    && latest.nextSequence() >= state.nextSequence()) && !latest.equals(state))
            {
                state = latest;
                display.accept(state);
            }
            if (receipt == null || !receipt.commandId().equals(request.commandId())
                    || receipt.sequence() != request.sequence())
            {
                uncertain = true;
                LOG.warn("The host output acknowledgement was inconsistent");
                notices.accept("The host output acknowledgement was inconsistent; synchronize again.");
            }
            else if (receipt.status() == RemotePlayerSession.Status.RESYNC_REQUIRED)
            {
                uncertain = true;
                notices.accept(receipt.message());
            }
            else if (receipt.status() != RemotePlayerSession.Status.BUSY)
            {
                pending = null;
                notices.accept(receipt.message());
            }
        }));
    }

    @Override
    public void close()
    {
        requireOwner();
        closed = true;
        pending = null;
        connection.laser(null, null);
        pointers.accept(RemotePlayerSession.Pointer.empty(null));
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Player controls require their owner thread.");
    }
}
