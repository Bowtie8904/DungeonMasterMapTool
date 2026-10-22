package dmmt.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteOwnerDispatchTest {
    @Test
    void dispatchesToOneOwnerAndPropagatesApplicationFailures() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var dispatch = new RemoteOwnerDispatch(executor, 2000);
            Thread owner = dispatch.call(Thread::currentThread);
            assertNotEquals(Thread.currentThread(), owner);
            assertSame(owner, dispatch.call(Thread::currentThread));
            assertThrows(IllegalArgumentException.class, () -> dispatch.call(() -> {
                throw new IllegalArgumentException("test");
            }));
            assertThrows(IOException.class, () -> dispatch.call(() -> {
                throw new IOException("test");
            }));
        }
    }

    @Test
    void timeoutCancelsQueuedCommandSoItCannotExecuteAfterCallerGivesUp() throws Exception {
        var pending = new AtomicReference<Runnable>();
        var dispatch = new RemoteOwnerDispatch(pending::set, 20);
        var applied = new AtomicBoolean();
        assertThrows(SocketTimeoutException.class, () -> dispatch.call(() -> {
            applied.set(true);
            return null;
        }));
        assertNotNull(pending.get());
        pending.get().run();
        assertFalse(applied.get());
    }

    @Test
    void closedOwnerRejectsExplicitlyRatherThanLeavingAnUncompletedRequest() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var dispatch = new RemoteOwnerDispatch(executor, 1000);
        assertThrows(IOException.class, () -> dispatch.call(() -> null));
    }
}
