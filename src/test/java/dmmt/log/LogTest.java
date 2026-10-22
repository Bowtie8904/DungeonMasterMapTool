package dmmt.log;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogTest {
    @Test
    void substitutesPlaceholdersInOrder() {
        assertEquals("Saved a in 5 ms", Log.format("Saved {} in {} ms", new Object[]{"a", 5}, 2));
    }

    @Test
    void keepsUnmatchedPlaceholdersAndIgnoresExtraArguments() {
        assertEquals("x {} y", Log.format("{} {} y", new Object[]{"x"}, 1));
        assertEquals("only", Log.format("only", new Object[]{"extra"}, 1));
    }

    @Test
    void parsesLevelsLeniently() {
        assertEquals(Level.INFO, LogSetup.parseLevel(null));
        assertEquals(Level.INFO, LogSetup.parseLevel("nonsense"));
        assertEquals(Level.FINE, LogSetup.parseLevel(" debug "));
        assertEquals(Level.WARNING, LogSetup.parseLevel("WARN"));
        assertEquals(Level.SEVERE, LogSetup.parseLevel("error"));
        assertEquals(Level.FINER, LogSetup.parseLevel("trace"));
    }
}
