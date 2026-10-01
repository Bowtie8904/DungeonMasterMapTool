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
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
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

    @Test
    void tuningValuesAreWrittenAndHandEditsApplyOnReload() throws IOException {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        String text = Files.readString(file);
        assertTrue(text.contains("fog.revealSeconds = 0.5"));
        assertTrue(text.contains("lightPreset.torch.color = #FFB35C"));
        assertTrue(text.contains("# ---- Frame rates ----"));

        Files.writeString(file, text
                .replace("fog.revealSeconds = 0.5", "fog.revealSeconds = 2.5")
                .replace("dm.zoom.max = 6", "dm.zoom.max = 99999")
                .replace("ping.color = #FFE633", "ping.color = yellow"));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertTrue(settings.pollExternalChange());
        assertEquals(2.5, Tuning.FOG_REVEAL_SECONDS.get(), 1e-9);
        assertEquals(100, Tuning.DM_ZOOM_MAX.get(), 1e-9, "numbers are clamped to their range");
        assertEquals("#FFE633", Tuning.PING_COLOR.get(), "invalid values fall back to the default");
    }

    @Test
    void missingKeysAreAddedToAnExistingFileOnStartup() throws IOException {
        Path file = dir.resolve("settings.ini");
        Files.writeString(file, "autosave.minutes = 5\nfog.hideSeconds = 1.25\n");

        AppSettings settings = new AppSettings(file);

        String text = Files.readString(file);
        assertTrue(text.contains("autosave.minutes = 5"));
        assertTrue(text.contains("fog.hideSeconds = 1.25"));
        assertTrue(text.contains("fog.revealSeconds = 0.5"));
        assertTrue(text.contains("texture.fire.color = #FF4A1A"));
        assertEquals(1.25, Tuning.FOG_HIDE_SECONDS.get(), 1e-9);
        assertEquals(5, settings.getInt("autosave.minutes", 2));
    }

    @Test
    void listSettingsParseNamedChoices() {
        Tuning.apply(key -> switch (key) {
            case "lightMenu.colors" -> "Blood=#AA0000; Ice=#99CCFF";
            case "lightMenu.flicker" -> "Off=0/0; Wild=0.9/3";
            case "lightMenu.rangeTiles" -> "2, 5";
            case "autosave.minuteOptions" -> "not a number";
            default -> null;
        });

        assertEquals(2, Tuning.LIGHT_MENU_COLORS.get().size());
        assertEquals("Ice", Tuning.LIGHT_MENU_COLORS.get().get(1).name());
        assertEquals("#99CCFF", Tuning.LIGHT_MENU_COLORS.get().get(1).value());
        assertEquals(3, Tuning.LIGHT_MENU_FLICKER.get().get(1).value()[1], 1e-9);
        assertEquals(java.util.List.of(2.0, 5.0), Tuning.LIGHT_MENU_RANGES.get());
        assertEquals(Tuning.AUTOSAVE_MINUTE_OPTIONS.defaultValue(), Tuning.AUTOSAVE_MINUTE_OPTIONS.get());
    }
}