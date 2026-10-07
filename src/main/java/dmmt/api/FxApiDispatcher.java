package dmmt.api;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Serializes remote actions with ordinary JavaFX input. */
public final class FxApiDispatcher {
    private FxApiDispatcher() {
    }

    public static <T> T call(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new LocalApiServer.ApiException(503, "Application is stopping.");
        } catch (TimeoutException ex) {
            task.cancel(false);
            throw new LocalApiServer.ApiException(503, "DM controls are busy; the request timed out.");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("DM API action failed.", ex.getCause());
        }
    }
}
