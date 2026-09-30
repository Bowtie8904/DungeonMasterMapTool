package dmmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps dmmt-settings.default.ini in the repository in sync with the built-in defaults. After changing a default or
 * adding a setting or texture, regenerate it with: mvn test -Dtest=DefaultSettingsFileTest -Dupdate.default.settings=true
 */
class DefaultSettingsFileTest {
    private static final Path DEFAULT_FILE = Path.of("dmmt-settings.default.ini");

    @Test
    void defaultSettingsFileMatchesBuiltInDefaults() throws IOException {
        String expected = AppSettings.renderDefaults();
        if (Boolean.getBoolean("update.default.settings")) {
            Files.writeString(DEFAULT_FILE, expected, StandardCharsets.UTF_8);
        }
        assertTrue(Files.isRegularFile(DEFAULT_FILE), DEFAULT_FILE + " is missing; regenerate with -Dupdate.default.settings=true");
        String actual = Files.readString(DEFAULT_FILE, StandardCharsets.UTF_8);
        assertEquals(normalize(expected), normalize(actual),
                DEFAULT_FILE + " is out of date; regenerate with -Dupdate.default.settings=true");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
