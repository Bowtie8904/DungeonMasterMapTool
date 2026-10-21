package dmmt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

@ResourceLock(SYSTEM_PROPERTIES)
class TextureMemoryTest {
    @Test
    void defaultsToOneGiBWithoutOverridingExplicitLimits() {
        String original = System.getProperty("prism.maxvram");
        try {
            System.clearProperty("prism.maxvram");
            DungeonMasterMapToolLauncher.configureTextureMemory();
            assertEquals("1g", System.getProperty("prism.maxvram"));

            System.setProperty("prism.maxvram", "256m");
            DungeonMasterMapToolLauncher.configureTextureMemory();
            assertEquals("256m", System.getProperty("prism.maxvram"));

            System.setProperty("prism.maxvram", "2g");
            DungeonMasterMapToolLauncher.configureTextureMemory();
            assertEquals("2g", System.getProperty("prism.maxvram"));
        } finally {
            if (original == null) {
                System.clearProperty("prism.maxvram");
            } else {
                System.setProperty("prism.maxvram", original);
            }
        }
    }
}
