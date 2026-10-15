package dmmt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import dmmt.render.OverlayTextures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies generated defaults independently of the editable repository reference. Explicitly regenerate that
 * reference with: mvn test -Dtest=DefaultSettingsFileTest -Dupdate.default.settings=true
 */
class DefaultSettingsFileTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void generatedDefaultsRoundTripWithoutChanges() throws IOException {
        String expected = AppSettings.renderDefaults();
        if (Boolean.getBoolean("update.default.settings")) {
            Files.writeString(Path.of("dmmt-settings.default.ini"), expected, StandardCharsets.UTF_8);
        }
        Path file = dir.resolve("defaults.ini");
        Files.writeString(file, expected, StandardCharsets.UTF_8);
        AppSettings settings = new AppSettings(file);
        for (String key : AppSettings.knownKeys()) {
            assertTrue(expected.lines().anyMatch(line -> line.startsWith(key + " = ")),
                    "Missing generated default: " + key);
        }
        settings.put("render.targetFps", settings.get("render.targetFps", null));
        assertEquals(normalize(expected), normalize(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
