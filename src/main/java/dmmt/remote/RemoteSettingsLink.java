package dmmt.remote;

import dmmt.log.Log;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Drives the effective host-settings context without blocking its application owner. */
public final class RemoteSettingsLink implements AutoCloseable {
    private static final Log LOG = Log.of(RemoteSettingsLink.class);
    public interface Connection {
        boolean connected();
        HostSettingsAuthority.State latest();
        CompletableFuture<HostSettingsAuthority.Result> send(HostSettingsAuthority.Request request);
    }

    public static Connection connection(RemoteSessionClient client) {
        return new Connection() {
            @Override public boolean connected() { return client.phase() == RemoteSessionClient.Phase.SETTINGS_READY; }
            @Override public HostSettingsAuthority.State latest() { return client.latestState(); }
            @Override public CompletableFuture<HostSettingsAuthority.Result> send(HostSettingsAuthority.Request request) {
                return client.edit(request);
            }
        };
    }

    private final Thread owner = Thread.currentThread();
    private final Connection connection;
    private final RemoteSettingsClient settings;
    private final Runnable refresh;
    private final Consumer<String> notices;
    private final Executor ownerExecutor;
    private HostSettingsAuthority.State displayed;
    private boolean sending;
    private boolean closed;
    private boolean failed;
    private long generation;

    public RemoteSettingsLink(AppSettings preferences, Connection connection, Runnable refresh,
                              Consumer<String> notices, Executor ownerExecutor) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        if (!connection.connected() || connection.latest() == null) {
            throw new IllegalStateException("Host settings must be synchronized before entering remote mode.");
        }
        displayed = connection.latest();
        settings = new RemoteSettingsClient(preferences, displayed, notice -> notices.accept(notice.message()));
        boolean installed = false;
        try {
            refresh.run();
            installed = true;
        } finally {
            if (!installed) {
                settings.close();
            }
        }
    }

    public boolean usable() {
        requireOwner();
        return !closed && !failed && connection.connected()
                && settings.phase() == RemoteSettingsClient.Phase.CONNECTED;
    }

    public void tick() {
        requireOwner();
        if (closed || failed || settings.phase() == RemoteSettingsClient.Phase.RESYNC_REQUIRED) {
            return;
        }
        try {
            synchronize();
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void synchronize() {
        if (!connection.connected()) {
            if (settings.phase() != RemoteSettingsClient.Phase.PAUSED) {
                generation++;
                settings.pause();
            }
            return;
        }
        if (sending) {
            return;
        }
        var latest = connection.latest();
        if (latest == null) {
            return;
        }
        if (settings.phase() == RemoteSettingsClient.Phase.PAUSED && !settings.resume(latest)) {
            return;
        }
        if (!latest.equals(displayed)) {
            settings.update(latest);
            displayed = latest;
            refresh.run();
        }
        if (settings.phase() != RemoteSettingsClient.Phase.CONNECTED) {
            return;
        }
        var request = settings.request();
        if (request == null) {
            return;
        }
        sending = true;
        long token = generation;
        CompletableFuture<HostSettingsAuthority.Result> response;
        try {
            response = connection.send(request);
        } catch (IllegalStateException unavailable) {
            sending = false;
            settings.pause();
            notices.accept("Remote settings paused; reconnect to recover the unconfirmed change.");
            return;
        }
        response.whenComplete((result, failure) -> ownerExecutor.execute(() -> {
            requireOwner();
            sending = false;
            if (closed || generation != token) {
                return;
            }
            if (failure != null || !connection.connected()) {
                generation++;
                settings.pause();
                notices.accept("Remote settings paused; reconnect to recover the unconfirmed change.");
                return;
            }
            try {
                settings.receive(result);
                // A retained acknowledgement may predate a newer polled host state; update() never rolls it back.
                displayed = result.state();
                refresh.run();
            } catch (RuntimeException invalid) {
                fail(invalid);
            }
        }));
    }

    private void fail(RuntimeException failure) {
        failed = true;
        generation++;
        settings.pause();
        LOG.error("Remote settings synchronization failed.", failure);
        notices.accept("Host settings could not be synchronized; editing paused. Disconnect and synchronize again.");
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        generation++;
        settings.close();
        refresh.run();
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Remote settings must be driven by their application owner thread.");
        }
    }
}
