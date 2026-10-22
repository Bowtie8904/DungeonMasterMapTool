package dmmt.remote;

import java.util.concurrent.CompletableFuture;

/** Application-owner-thread domain boundary, installed only by a host workspace integration. */
public interface RemoteWorkspaceEndpoint {
    default RemoteFailure failure() { return null; }
    default RemoteOperations operations() { return null; }
    default RemoteMapCatalog maps() { return null; }
    default RemoteSaves saves() { return null; }

    default RemotePlayerSession player() {
        return null;
    }

    default RemoteHandoutSession handouts() {
        return null;
    }

    default boolean ready() {
        return true;
    }

    RemoteWorkspaceAuthority.State state();

    RemoteWorkspaceAuthority.Result execute(RemoteWorkspaceAuthority.Request request);

    default CompletableFuture<RemoteWorkspaceAuthority.Result> executeAsync(RemoteWorkspaceAuthority.Request request) {
        return CompletableFuture.completedFuture(execute(request));
    }

    default CompletableFuture<RemoteWorkspaceAuthority.Result> executeAsync(
            RemoteWorkspaceAuthority.Request request, Runnable authorize) {
        authorize.run();
        return executeAsync(request);
    }
}
