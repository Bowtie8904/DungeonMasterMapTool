package dmmt.service;

import dmmt.render.OverlayTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetTextures() {
        OverlayTextures.applySettings(key -> null);
    }

    @Test
    void createsCommentedFileWithAllDefaults() throws IOException {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        String text = Files.readString(file);
        assertTrue(text.contains("texture.fire.color = #FF4A1A"));
        assertTrue(text.contains("texture.fire.layer1.speedY = -0.75"));
        assertTrue(text.contains("autosave.minutes = "));
        assertTrue(text.contains("# "));
        assertEquals(7, settings.getInt("missing", 7));
    }

    @Test
    void valuesPersistAndInvalidOnesFallBack() throws IOException {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        settings.putInt("autosave.minutes", 9);
        settings.putBoolean("ui.sidebarVisible", false);
        settings.put("import.lastDirectory", "C:\\maps\\here");
        settings.put("player.tileInches", "abc");

        AppSettings again = new AppSettings(file);
        assertEquals(9, again.getInt("autosave.minutes", 2));
        assertFalse(again.getBoolean("ui.sidebarVisible", true));
        assertEquals("C:\\maps\\here", again.get("import.lastDirectory", null));
        assertEquals(1.0, again.getDouble("player.tileInches", 1.0));
    }

    @Test
    void handEditsAreMergedAndTexturesOverridden() throws IOException {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        String text = Files.readString(file)
                .replace("texture.water.color = #2A7BE0", "texture.water.color = #FF8800")
                .replace("texture.fire.opacity = 0.9", "texture.fire.opacity = 0.5")
                .replace("texture.fire.layer1.speedY = -0.75", "texture.fire.layer1.speedY = -2")
                .replace("texture.smoke.color = #9A9A9A", "texture.smoke.color = notacolor");
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertTrue(settings.pollExternalChange());
        assertFalse(settings.pollExternalChange());
        settings.applyTextureSettings();
        assertEquals("#FF8800", OverlayTextures.defaultColor(OverlayTextures.WATER));
        assertEquals(0.5, OverlayTextures.defaultAlpha(OverlayTextures.FIRE), 1e-9);
        assertEquals(-2, OverlayTextures.layers(OverlayTextures.FIRE).get(0).vy(), 1e-9);
        assertEquals("#9A9A9A", OverlayTextures.defaultColor(OverlayTextures.SMOKE));

        settings.putInt("autosave.minutes", 4);
        assertTrue(Files.readString(file).contains("texture.water.color = #FF8800"));
    }
}
