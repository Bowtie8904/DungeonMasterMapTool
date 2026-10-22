package dmmt.remote;

import dmmt.log.Log;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Host-only player output and ephemeral pointers, independent of map persistence/history.
 */
public final class RemotePlayerSession
{
    private static final Log LOG = Log.of(RemotePlayerSession.class);
    public static final int MAX_PINGS = 16;
    public static final int MAX_PING_MILLIS = 60_000;
    private static final int RECEIPTS = 256;

    public enum Action
    {
        WINDOW,
        SCALE_TEST,
        SCREEN
    }

    public enum Status
    {
        ACCEPTED,
        CONFLICT,
        BUSY,
        FAILED,
        RESYNC_REQUIRED
    }

    public record Point(double x, double y)
    {
        public Point
        {
            if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1e9 || Math.abs(y) > 1e9)
            {
                throw new IllegalArgumentException("A bounded map position is required.");
            }
        }
    }

    public record Ping(Point point, long remainingMillis)
    {
        public Ping
        {
            Objects.requireNonNull(point, "point");
            if (remainingMillis < 1 || remainingMillis > MAX_PING_MILLIS)
            {
                throw new IllegalArgumentException("A bounded ping lifetime is required.");
            }
        }
    }

    public record Pointer(UUID contextId, Point laser, List<Ping> pings)
    {
        public Pointer
        {
            pings = List.copyOf(pings);
            if (pings.size() > MAX_PINGS || contextId == null && (laser != null || !pings.isEmpty()))
            {
                throw new IllegalArgumentException("Invalid transient pointer frame.");
            }
        }

        public static Pointer empty(UUID context)
        {
            return new Pointer(context, null, List.of());
        }
    }

    public record Monitor(String label, double width, double height, double scaleX, double scaleY, double dpi)
    {
        public Monitor
        {
            if (label == null || label.isBlank() || label.length() > 200
                    || label.codePoints().anyMatch(Character::isISOControl)
                    || !positive(width) || !positive(height) || width > 1e6 || height > 1e6
                    || !positive(scaleX) || !positive(scaleY) || scaleX > 32 || scaleY > 32
                    || !positive(dpi) || dpi > 10_000)
            {
                throw new IllegalArgumentException("Invalid host monitor metadata.");
            }
        }
    }

    public record Output(boolean visible, boolean scaleTest, double width, double height,
                         int screenIndex, List<Monitor> monitors)
    {
        public Output
        {
            monitors = List.copyOf(monitors);
            if (monitors.size() > 64 || screenIndex < 0 || screenIndex >= monitors.size()
                    || !Double.isFinite(width) || !Double.isFinite(height) || width < 0 || height < 0
                    || width > 1e6 || height > 1e6 || visible && (width == 0 || height == 0))
            {
                throw new IllegalArgumentException("Invalid host player output.");
            }
        }
    }

    public record State(UUID sessionId, UUID contextId, long revision, long nextSequence, Output output)
    {
        public State
        {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(output, "output");
            if (revision < 0 || nextSequence < 1)
                throw new IllegalArgumentException("Invalid output revision.");
        }
    }

    public record Request(UUID sessionId, UUID commandId, long sequence, long expectedRevision,
                          Action action, boolean enabled, int screenIndex)
    {
        public Request
        {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(action, "action");
            if (sequence < 1 || expectedRevision < 0 || screenIndex < 0 || screenIndex >= 64
                    || action != Action.SCREEN && screenIndex != 0 || action == Action.SCREEN && enabled)
            {
                throw new IllegalArgumentException("Invalid player-output command.");
            }
        }
    }

    public record Receipt(UUID commandId, long sequence, Status status, String message)
    {
        public Receipt
        {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(status, "status");
            if (sequence < 1 || message == null || message.isBlank() || message.length() > 500)
            {
                throw new IllegalArgumentException("Invalid player-output receipt.");
            }
        }
    }

    public record Query(Request request, Pointer pointer)
    {
        public Query
        {
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    public record Update(State state, Receipt receipt, Pointer pointer)
    {
        public Update
        {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(pointer, "pointer");
            if (!state.contextId().equals(pointer.contextId()))
            {
                throw new IllegalArgumentException("Output pointers belong to another map.");
            }
        }
    }

    private record Stored(Request request, Receipt receipt)
    {
    }

    private record TimedPing(Point point, long expiresNanos)
    {
    }

    private final Thread owner = Thread.currentThread();
    private final UUID sessionId = UUID.randomUUID();
    private final Supplier<UUID> context;
    private final Supplier<Output> output;
    private final Supplier<Point> laser;
    private final Consumer<Pointer> receive;
    private final Consumer<Request> apply;
    private final BooleanSupplier available;
    private final LinkedHashMap<Long, Stored> receipts = new LinkedHashMap<>();
    private final ArrayDeque<TimedPing> pings = new ArrayDeque<>();
    private UUID lastContext;
    private Output lastOutput;
    private long revision;
    private long nextSequence = 1;

    public RemotePlayerSession(Supplier<UUID> context, Supplier<Output> output, Supplier<Point> laser,
                               Consumer<Pointer> receive, Consumer<Request> apply, BooleanSupplier available)
    {
        this.context = Objects.requireNonNull(context, "context");
        this.output = Objects.requireNonNull(output, "output");
        this.laser = Objects.requireNonNull(laser, "laser");
        this.receive = Objects.requireNonNull(receive, "receive");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.available = Objects.requireNonNull(available, "available");
    }

    public State state()
    {
        requireOwner();
        UUID current = Objects.requireNonNull(context.get(), "contextId");
        Output shown = output.get();
        if (lastOutput != null && (!shown.equals(lastOutput) || !current.equals(lastContext)))
            revision++;
        if (!current.equals(lastContext))
            pings.clear();
        lastContext = current;
        lastOutput = shown;
        return new State(sessionId, current, revision, nextSequence, shown);
    }

    public void outputChanged()
    {
        requireOwner();
        revision++;
    }

    /**
     * Called only for a new, live, authorized control lease. Old transients must never reconnect.
     */
    public void resetPointers()
    {
        requireOwner();
        pings.clear();
        receive.accept(Pointer.empty(null));
    }

    public void ping(Point point, long durationMillis)
    {
        Objects.requireNonNull(point, "point");
        state();
        if (pings.size() == MAX_PINGS)
            pings.removeFirst();
        pings.addLast(new TimedPing(point, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.clamp(durationMillis, 1, MAX_PING_MILLIS))));
    }

    public Update exchange(Query query)
    {
        State before = state();
        Receipt receipt = query.request() == null ? null : execute(query.request(), before);
        State after = state();
        if (after.contextId().equals(query.pointer().contextId()))
            receive.accept(query.pointer());
        else
            receive.accept(Pointer.empty(after.contextId()));
        long now = System.nanoTime();
        List<Ping> outgoing = pings.stream().filter(p -> p.expiresNanos() > now)
                                   .map(p -> new Ping(p.point(), Math.max(1,
                                                                          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(p.expiresNanos() - now)))).toList();
        pings.clear();
        return new Update(after, receipt, new Pointer(after.contextId(), laser.get(), outgoing));
    }

    private Receipt execute(Request request, State before)
    {
        for (var stored : receipts.values())
        {
            if (stored.request().commandId().equals(request.commandId()) && !stored.request().equals(request))
            {
                return receipt(request, Status.RESYNC_REQUIRED, "The output command identity was reused; synchronize again.");
            }
        }
        if (!request.sessionId().equals(sessionId) || request.sequence() != nextSequence)
        {
            Stored stored = receipts.get(request.sequence());
            if (stored != null && stored.request().equals(request))
                return stored.receipt();
            return receipt(request, Status.RESYNC_REQUIRED, "The output command receipt cannot be recovered; synchronize again.");
        }
        Receipt result;
        if (!available.getAsBoolean())
        {
            return receipt(request, Status.BUSY, "The host is finishing a local output operation; retry shortly.");
        }
        else if (request.expectedRevision() != before.revision())
        {
            result = receipt(request, Status.CONFLICT, "The host output changed; this control was not applied.");
        }
        else if (request.action() == Action.SCREEN && request.screenIndex() >= before.output().monitors().size())
        {
            result = receipt(request, Status.CONFLICT, "The selected host monitor is no longer available.");
        }
        else
        {
            try
            {
                apply.accept(request);
                LOG.info("Remote player output action {} applied", request.action());
                result = receipt(request, Status.ACCEPTED, "Player output updated on the host.");
            }
            catch (RuntimeException failure)
            {
                LOG.error("Player-output command {} failed.", request.commandId(), failure);
                result = receipt(request, Status.FAILED, "Player output failed; inspect the host status and application logs.");
            }
            // Advance even for a no-op or failed attempt; never execute an uncertain side effect twice.
            revision++;
        }
        receipts.put(nextSequence, new Stored(request, result));
        nextSequence++;
        if (receipts.size() > RECEIPTS)
            receipts.remove(receipts.keySet().iterator().next());
        return result;
    }

    private static Receipt receipt(Request request, Status status, String message)
    {
        return new Receipt(request.commandId(), request.sequence(), status, message);
    }

    private static boolean positive(double number)
    {
        return Double.isFinite(number) && number > 0;
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Player output requires its owner thread.");
    }
}
