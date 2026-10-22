package dmmt.remote;

import dmmt.service.AppSettings;
import dmmt.service.HostSettingsAuthority;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Application-thread boundary between host approval and remote settings operations. No network listeners
 * are opened here; the eventual transport must dispatch calls to this owner and protect pairing traffic.
 */
public final class RemoteHostSession implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final AppSettings settings;
    private final RemoteSessionAccess access = new RemoteSessionAccess();
    private HostSettingsAuthority authority;

    public RemoteHostSession(AppSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.usesHostSettings()) {
            throw new IllegalStateException("A remote client cannot host another session.");
        }
    }

    public RemoteSessionAccess.Status status() {
        requireOwner();
        return access.status();
    }

    public RemoteSessionAccess.PairingCode pairingCode() {
        requireOwner();
        return access.pairingCode();
    }

    public RemoteSessionAccess.PairingCode regenerateCode() {
        requireOwner();
        return access.regenerateCode();
    }

    public RemoteSessionAccess.ApprovalRequest requestApproval(String code, RemoteSessionAccess.Client client) {
        requireOwner();
        return access.requestApproval(code, client);
    }

    public RemoteSessionAccess.ApprovalRequest requestApproval(RemoteSessionAccess.Client client) {
        requireOwner();
        return access.requestApproval(client);
    }

    /** Host UI only; the transport delivers the grant to the original approved pairing connection. */
    public RemoteSessionAccess.Grant approve(UUID requestId) {
        requireOwner();
        if (settings.usesHostSettings()) {
            throw new IllegalStateException("A remote client cannot host another session.");
        }
        var grant = access.approve(requestId);
        authority = new HostSettingsAuthority(settings);
        return grant;
    }

    public void reject(UUID requestId) {
        requireOwner();
        access.reject(requestId);
    }

    public void cancelApproval(UUID requestId) {
        requireOwner();
        access.cancelApproval(requestId);
    }

    public UUID connect(UUID sessionId, String credential) {
        requireOwner();
        return access.connect(sessionId, credential);
    }

    public void disconnected(UUID lease) {
        requireOwner();
        access.disconnected(lease);
    }

    /** Validates a live connection before dispatching other host-owned workspace operations. */
    public void authorizeConnection(UUID lease) {
        requireOwner();
        access.authorizeConnection(lease);
    }

    /** Obtain a revocation-aware check for a separate authenticated bulk channel. */
    public Runnable authorizeTransfer(UUID sessionId, String credential) {
        requireOwner();
        UUID lease = access.transferLease(sessionId, credential);
        return () -> access.authorizeConnection(lease);
    }

    public HostSettingsAuthority.State settingsState(UUID lease) {
        requireOwner();
        access.authorizeConnection(lease);
        return authority.stateInMemory();
    }

    public HostSettingsAuthority.Result editSettings(UUID lease, HostSettingsAuthority.Request request) {
        requireOwner();
        access.authorizeConnection(lease);
        return authority.execute(request);
    }

    /** Authorization/prepare run on this owner; completion and runtime application return via ownerExecutor. */
    public CompletableFuture<HostSettingsAuthority.Result> editSettingsAsync(
            UUID lease, HostSettingsAuthority.Request request, Executor ownerExecutor) {
        requireOwner();
        access.authorizeConnection(lease);
        return authority.executeAsync(request, ownerExecutor);
    }

    public CompletableFuture<HostSettingsAuthority.Result> editSettingsAsync(
            UUID lease, HostSettingsAuthority.Request request, Executor ownerExecutor, Executor workerExecutor) {
        requireOwner();
        access.authorizeConnection(lease);
        return authority.executeAsync(request, ownerExecutor, workerExecutor);
    }

    /** Host UI only; revokes access before disposing of the command history. */
    public void revoke() {
        requireOwner();
        access.revoke();
        closeAuthority();
    }

    @Override
    public void close() {
        requireOwner();
        access.close();
        closeAuthority();
    }

    private void closeAuthority() {
        if (authority != null) {
            authority.close();
            authority = null;
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Remote host operations must run on their owning application thread.");
        }
    }
}
