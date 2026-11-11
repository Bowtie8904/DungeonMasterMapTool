package dmmt.online;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OnlineSessionControllerBackoffTest {
    @Test
    void cheapTicksKeepTheNormalInterval() {
        assertEquals(0, OnlineSessionController.backoffTicks(0));
        assertEquals(0, OnlineSessionController.backoffTicks(50));
    }

    @Test
    void heavyTicksBackOffUpToOneSecond() {
        assertEquals(1, OnlineSessionController.backoffTicks(100));
        assertEquals(3, OnlineSessionController.backoffTicks(200));
        assertEquals(3, OnlineSessionController.backoffTicks(5_000));
    }
}
