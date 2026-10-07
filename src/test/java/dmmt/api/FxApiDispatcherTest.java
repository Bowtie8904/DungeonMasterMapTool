package dmmt.api;

import dmmt.FxTestSupport;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FxApiDispatcherTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void workerCommandsRunOnFxThreadAndReturnTheirResult() {
        assertEquals("done", FxApiDispatcher.call(() -> {
            assertTrue(Platform.isFxApplicationThread());
            return "done";
        }));
    }

    @Test
    void alreadyOnFxThreadDoesNotDeadlock() {
        assertEquals(42, FxApiDispatcher.call(() -> FxApiDispatcher.call(() -> 42)));
    }

    @Test
    void preservesCommandErrors() {
        LocalApiServer.ApiException error = new LocalApiServer.ApiException(409, "Disabled control");
        assertSame(error, assertThrows(LocalApiServer.ApiException.class,
                () -> FxApiDispatcher.call(() -> { throw error; })));
    }
}
