package dmmt.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import dmmt.render.OverlayTextures;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestSettingsIsolationTest {
    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void implicitLoadsUseAWritableTemporaryCopyOfTheTestResource() throws Exception {
        Path settingsFile = AppSettings.resolveFile();
        assertFalse(settingsFile.startsWith(Path.of("").toAbsolutePath()));
        String fixture;
        try (var input = getClass().getResourceAsStream("test-settings.ini")) {
            assertNotNull(input);
            fixture = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(fixture, Files.readString(settingsFile));
        AppSettings settings = AppSettings.load();
        assertEquals(settingsFile, settings.getFile());
        assertFalse(settings.getBoolean("ui.performanceMode", true));
        settings.putBoolean("ui.performanceMode", true);
        assertTrue(AppSettings.load().getBoolean("ui.performanceMode", false));
        assertNotEquals(fixture, Files.readString(settingsFile));
    }

    @Test
    void anotherTestStartsWithTheUnmodifiedFixture() throws Exception {
        assertTrue(Files.readString(AppSettings.resolveFile()).contains("ui.performanceMode = false"));
        assertFalse(AppSettings.load().getBoolean("ui.performanceMode", true));
    }
}
