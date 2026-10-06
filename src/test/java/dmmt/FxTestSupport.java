package dmmt;

import javafx.application.Platform;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class FxTestSupport {
    private FxTestSupport() {
    }

    public static void startJavaFx() throws Exception {
        FutureTask<Void> ready = new FutureTask<>(() -> {
            Platform.setImplicitExit(false);
            return null;
        });
        try {
            Platform.startup(ready);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(ready);
        }
        ready.get(10, TimeUnit.SECONDS);
    }
}
