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
    void autoTagsAreEditableLiveAndEmptyValuesPersistWithoutRestoringDefaults() throws IOException {
        Path file = dir.resolve("tags.ini");
        Files.writeString(file, "import.autoLabelRooms = false\n");
        AppSettings settings = new AppSettings(file);
        assertTrue(Tuning.IMPORT_AUTO_TAGS.get().contains("TAVERN"));
        assertTrue(Files.readString(file).contains("import.autoTags = TAVERN"));
        AppSettings.SettingInfo info = AppSettings.editableSettings().stream()
                .filter(entry -> entry.key().equals("import.autoTags")).findFirst().orElseThrow();
        assertEquals("Import", info.category());
        assertEquals("Auto tags", info.label());
        assertEquals(Tuning.Kind.LIST, info.kind());
        assertFalse(info.restart());
        assertTrue(info.validator().test(""));
        assertTrue(info.keywords().contains("whitelist"));

        settings.applyEdit(info.key(), " oasis, Haunted Keep, OASIS, , ");
        assertEquals(java.util.List.of("OASIS", "HAUNTED KEEP"), Tuning.IMPORT_AUTO_TAGS.get());
        new AppSettings(file);
        assertEquals(java.util.List.of("OASIS", "HAUNTED KEEP"), Tuning.IMPORT_AUTO_TAGS.get());

        settings.applyEdit(info.key(), "");
        assertTrue(Tuning.IMPORT_AUTO_TAGS.get().isEmpty());
        assertEquals("", settings.get(info.key(), info.defaultValue()));
        new AppSettings(file);
        assertTrue(Tuning.IMPORT_AUTO_TAGS.get().isEmpty());
        settings.applyEdit(info.key(), null);
        assertEquals(Tuning.IMPORT_AUTO_TAGS.defaultValue(), Tuning.IMPORT_AUTO_TAGS.get());
    }

    @Test
    void backgroundWorkModeIsLiveSearchableAndPersists() {
        AppSettings settings = new AppSettings(dir.resolve("work.ini"));
        assertEquals("session", Tuning.WORK_MODE.get());
        AppSettings.SettingInfo info = AppSettings.editableSettings().stream()
                .filter(entry -> entry.key().equals("work.mode")).findFirst().orElseThrow();
        assertEquals("Storage and caches", info.category());
        assertEquals("Work mode", info.label());
        assertEquals(java.util.List.of("session", "preparation"), info.options());
        assertTrue(info.keywords().contains("concurrency"));
        assertFalse(info.restart());
        assertFalse(info.validator().test("unlimited"));
        settings.applyEdit("work.mode", "preparation");
        assertEquals("preparation", Tuning.WORK_MODE.get());
        new AppSettings(settings.getFile());
        assertEquals("preparation", Tuning.WORK_MODE.get());
        settings.applyEdit("work.mode", null);
        assertEquals("session", Tuning.WORK_MODE.get());
    }

    @Test
    void windowDefaultsMatchDoorsWithoutOverwritingExplicitWindowColors() throws IOException {
        AppSettings defaults = new AppSettings(dir.resolve("defaults.ini"));
        assertEquals(Tuning.DOOR_OPEN_COLOR.get(), Tuning.WINDOW_OPEN_COLOR.get());
        assertEquals(Tuning.DOOR_CLOSED_COLOR.get(), Tuning.WINDOW_CLOSED_COLOR.get());
        assertTrue(Files.readString(defaults.getFile()).contains("ui.windowOpenColor = #32CD32"));
        assertTrue(Files.readString(defaults.getFile()).contains("ui.windowClosedColor = #E0473C"));
        Path custom = dir.resolve("custom.ini");
        Files.writeString(custom, "ui.windowOpenColor = #00BFFF\nui.windowClosedColor = #3B6FD8\n");
        AppSettings settings = new AppSettings(custom);
        assertEquals("#00BFFF", Tuning.WINDOW_OPEN_COLOR.get());
        assertEquals("#3B6FD8", Tuning.WINDOW_CLOSED_COLOR.get());
        settings.applyEdit("ui.doorOpenColor", "#123456");
        assertEquals("#00BFFF", Tuning.WINDOW_OPEN_COLOR.get(), "window colors remain independently configurable");
        AppSettings reloaded = new AppSettings(custom);
        assertEquals("#00BFFF", reloaded.get("ui.windowOpenColor", null));
        assertEquals("#3B6FD8", reloaded.get("ui.windowClosedColor", null));
        reloaded.applyEdit("ui.windowOpenColor", null);
        assertEquals("#32CD32", Tuning.WINDOW_OPEN_COLOR.get());
    }

    @Test
    void roomLabelDefaultsAreLiveSearchableSettingsAndAlphaColorsPersist() {
        AppSettings settings = new AppSettings(dir.resolve("labels.ini"));
        assertEquals(30, Tuning.ROOM_LABEL_FONT_SIZE.get());
        assertEquals("#EEEEEE", Tuning.ROOM_LABEL_TEXT_COLOR.get());
        assertEquals("#1E1E1E99", Tuning.ROOM_LABEL_BACKGROUND_COLOR.get());
        assertEquals("#00000000", Tuning.ROOM_LABEL_BORDER_COLOR.get());
        for (String key : java.util.List.of("roomLabel.fontSize", "roomLabel.textColor",
                "roomLabel.backgroundColor", "roomLabel.borderColor")) {
            AppSettings.SettingInfo info = AppSettings.editableSettings().stream()
                    .filter(entry -> entry.key().equals(key)).findFirst().orElseThrow();
            assertEquals("Room label defaults", info.group());
            assertTrue(info.keywords().contains("room label"));
            assertFalse(info.restart());
            assertTrue(info.validator().test(info.defaultValue()));
        }
        settings.applyEdit("roomLabel.fontSize", "27");
        settings.applyEdit("roomLabel.backgroundColor", "#10203040");
        settings.applyEdit("roomLabel.borderColor", "#50607080");
        AppSettings reloaded = new AppSettings(settings.getFile());
        assertEquals(27, Tuning.ROOM_LABEL_FONT_SIZE.get());
        assertEquals("#10203040", Tuning.ROOM_LABEL_BACKGROUND_COLOR.get());
        assertEquals("#50607080", Tuning.ROOM_LABEL_BORDER_COLOR.get());
        assertEquals("#10203040", reloaded.get("roomLabel.backgroundColor", null));
        reloaded.applyEdit("roomLabel.backgroundColor", null);
        assertEquals("#1E1E1E99", Tuning.ROOM_LABEL_BACKGROUND_COLOR.get());
    }

    @Test
    void resolvesRelativeStorageFoldersBesideSettingsAndLeavesAbsoluteFoldersAlone() {
        Path settingsFile = dir.resolve("config").resolve("dmmt-settings.ini");
        Path projectsFolder = AppSettings.resolveConfiguredFolder(settingsFile, "dmmap-projects", "dmmap-projects");
        Path audioFolder = AppSettings.resolveConfiguredFolder(settingsFile, "dmmap-audio", "dmmap-audio");
        assertEquals(dir.resolve("config/dmmap-projects").toAbsolutePath().normalize(), projectsFolder);
        assertEquals(dir.resolve("config").toAbsolutePath().normalize(), projectsFolder.getParent());
        assertEquals(projectsFolder.getParent(), audioFolder.getParent());
        assertEquals(dir.resolve("custom-audio").toAbsolutePath().normalize(),
                AppSettings.resolveConfiguredFolder(settingsFile, "  " + dir.resolve("custom-audio") + "  ",
                        "dmmap-audio"));
        assertEquals(dir.resolve("config/dmmap-projects").toAbsolutePath().normalize(),
                AppSettings.resolveConfiguredFolder(settingsFile, "", "dmmap-projects"));
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
    void playerGridDefaultsOffAndPersistsBothToggleStates() throws IOException {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        assertFalse(settings.getBoolean("player.showGrid", false));
        assertTrue(Files.readString(file).contains("player.showGrid = false"));

        settings.putBoolean("player.showGrid", true);
        AppSettings reloaded = new AppSettings(file);
        assertTrue(reloaded.getBoolean("player.showGrid", false));

        reloaded.putBoolean("player.showGrid", false);
        assertFalse(new AppSettings(file).getBoolean("player.showGrid", true));
    }

    @Test
    void apiUrlMenuOptionsDefaultOffAndApplyImmediately() {
        AppSettings settings = new AppSettings(dir.resolve("settings.ini"));
        assertFalse(Tuning.API_SHOW_URL_OPTIONS.get());
        assertTrue(Files.exists(dir.resolve("settings.ini")));

        settings.applyEdit("api.showUrlOptions", "true");
        assertTrue(Tuning.API_SHOW_URL_OPTIONS.get());
        settings.applyEdit("api.showUrlOptions", null);
        assertFalse(Tuning.API_SHOW_URL_OPTIONS.get());
    }

    @Test
    void gridOpacityEditsApplyImmediatelyAndPersist() {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        for (double opacity : new double[]{0.0, 0.42, 1.0}) {
            settings.applyEdit(Tuning.GRID_OPACITY.key(), String.valueOf(opacity));
            assertEquals(opacity, Tuning.GRID_OPACITY.get(), 1e-9);
            AppSettings reloaded = new AppSettings(file);
            assertEquals(opacity, reloaded.getDouble(Tuning.GRID_OPACITY.key(), -1), 1e-9);
            assertEquals(opacity, Tuning.GRID_OPACITY.get(), 1e-9);
        }
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
    void lightMenuEntriesAreFixedButEditable() {
        Tuning.apply(key -> switch (key) {
            case "lightMenu.color.moonlight" -> "#99CCFF";
            case "lightMenu.flicker.torch.speed" -> "3";
            case "lightMenu.range1" -> "2";
            case "autosave.minuteOptions" -> "not a number";
            default -> null;
        });

        assertEquals(12, Tuning.lightMenuColors().size());
        assertEquals("Moonlight", Tuning.lightMenuColors().get(3).name());
        assertEquals("#99CCFF", Tuning.lightMenuColors().get(3).value());
        assertEquals("Off", Tuning.lightMenuFlicker().get(0).name());
        assertEquals(3, Tuning.lightMenuFlicker().get(2).value()[1], 1e-9);
        assertEquals(10, Tuning.lightMenuRanges().size());
        assertEquals(2.0, Tuning.lightMenuRanges().get(0));
        assertEquals(3, Tuning.lightMenuBrightness().size());
        assertEquals(Tuning.AUTOSAVE_MINUTE_OPTIONS.defaultValue(), Tuning.AUTOSAVE_MINUTE_OPTIONS.get());
    }

    @Test
    void obsoleteLightMenuKeysAreDroppedFromTheFile() throws IOException {
        Path file = dir.resolve("settings.ini");
        Files.writeString(file, "lightMenu.colors = Blood=#AA0000\n");
        new AppSettings(file);
        assertFalse(Files.readString(file).contains("lightMenu.colors"));
    }

    @Test
    void settingsAreGroupedAndRelatedCategoriesAreAdjacent() {
        java.util.List<AppSettings.SettingInfo> infos = AppSettings.editableSettings();
        java.util.List<String> categories = infos.stream().map(AppSettings.SettingInfo::category).distinct().toList();
        assertEquals(categories.size(), categories.stream().distinct().count());
        assertEquals(1, categories.indexOf("Effect textures") - categories.indexOf("Editing, brush and effects"));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("timeOfDay.dawn.red") && "Dawn".equals(i.group())));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("lightPreset.torch.color")
                && "Light tool presets/Torch (Add light tool)".equals(i.group())));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("texture.fire.layer1.speedX") && "Fire/Layer 1".equals(i.group())));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("cache.imageTiles") && "Map image cache".equals(i.group())));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("player.zoom.min") && "Player zoom".equals(i.group())));
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("player.viewportEdgeScroll.enabled")
                && "Viewport edge scrolling".equals(i.group()) && !i.restart()));
    }

    @Test
    void everyGroupHasAtLeastTwoSettings() {
        java.util.Map<String, Long> counts = AppSettings.editableSettings().stream().filter(i -> i.group() != null)
                .collect(java.util.stream.Collectors.groupingBy(i -> i.category() + "/" + i.group(), java.util.stream.Collectors.counting()));
        counts.forEach((group, count) -> assertTrue(count >= 2, group + " has only " + count + " setting"));
    }

    @Test
    void everySettingHasSearchKeywordsFromTheDocumentation() {
        for (AppSettings.SettingInfo info : AppSettings.editableSettings()) {
            assertFalse(info.keywords().isBlank(), "no keywords for " + info.key());
        }
        assertTrue(AppSettings.keywords("texture.fire.layer2.scale").contains("scroll speed"));
        assertTrue(AppSettings.keywords("timeOfDay.dusk.red").contains("sunset"));
    }

    @Test
    void hiddenSectionsArePersistedAndUnknownIdsIgnored() {
        Path file = dir.resolve("settings.ini");
        AppSettings settings = new AppSettings(file);
        assertTrue(settings.hiddenSections().isEmpty());
        settings.setHiddenSections(java.util.Set.of("effects", "performance", "bogus"));
        assertEquals(java.util.Set.of("effects", "performance"), new AppSettings(file).hiddenSections());
        settings.setHiddenSections(java.util.Set.of());
        assertTrue(new AppSettings(file).hiddenSections().isEmpty());
    }

    @Test
    void hiddenControlsPersistIndependentlyOfTabsAndNormalizeHandEdits() throws Exception {
        Path file = dir.resolve("control-settings.ini");
        AppSettings settings = new AppSettings(file);
        assertTrue(settings.hiddenControls().isEmpty());
        java.util.Set<String> hidden = java.util.Set.of("lighting.candle", "player.diagonal", "fog.brushSize", "effects.brushSize");
        settings.setHiddenControls(java.util.Set.of("lighting.candle", "player.diagonal", "fog.brushSize", "effects.brushSize", "bogus"));
        settings.setHiddenSections(java.util.Set.of("lighting"));
        AppSettings restored = new AppSettings(file);
        assertEquals(hidden, restored.hiddenControls());
        restored.setHiddenSections(java.util.Set.of());
        assertEquals(hidden, restored.hiddenControls());
        assertEquals("fog.brushSize,lighting.candle,effects.brushSize,player.diagonal",
                restored.get(AppSettings.HIDDEN_CONTROLS_KEY, ""));
        String text = Files.readString(file);
        Files.writeString(file, text.replace("ui.controls.hidden = fog.brushSize,lighting.candle,effects.brushSize,player.diagonal",
                "ui.controls.hidden = LIGHTING.CANDLE, fog.BRUSHSIZE, unknown, lighting.candle"));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertTrue(restored.pollExternalChange());
        assertEquals(java.util.Set.of("lighting.candle", "fog.brushSize"), restored.hiddenControls());
        restored.setHiddenControls(java.util.Set.of());
        assertTrue(new AppSettings(file).hiddenControls().isEmpty());
    }

    @Test
    void sidebarControlsHaveUniqueIdsAndKnownSections() {
        var controls = AppSettings.SIDEBAR_CONTROLS;
        assertEquals(controls.size(), controls.stream().map(AppSettings.SidebarControl::id).distinct().count());
        for (var control : controls) {
            assertTrue(AppSettings.SIDEBAR_SECTIONS.stream().anyMatch(s -> s.id().equals(control.sectionId())));
            assertTrue(control.id().startsWith(control.sectionId() + "."));
            assertFalse(control.label().isBlank());
        }
    }

    @Test
    void editedSettingsApplyImmediatelyAndResetToDefault() {
        AppSettings settings = new AppSettings(dir.resolve("settings.ini"));
        settings.applyEdit(Tuning.DM_ZOOM_MAX.key(), "3");
        assertEquals(3.0, Tuning.DM_ZOOM_MAX.get());
        settings.applyEdit(Tuning.DM_ZOOM_MAX.key(), null);
        assertEquals(Tuning.DM_ZOOM_MAX.defaultValue(), Tuning.DM_ZOOM_MAX.get());
    }

    @Test
    void viewportEdgeScrollingDefaultsPersistsAndClampsTuning() {
        Path file = dir.resolve("viewport-settings.ini");
        AppSettings settings = new AppSettings(file);
        assertTrue(Tuning.PLAYER_VIEWPORT_EDGE_SCROLL.get());
        assertEquals(40.0, Tuning.PLAYER_VIEWPORT_EDGE_ZONE.get());
        assertEquals(600.0, Tuning.PLAYER_VIEWPORT_EDGE_SPEED.get());
        settings.applyEdit(Tuning.PLAYER_VIEWPORT_EDGE_SCROLL.key(), "false");
        settings.applyEdit(Tuning.PLAYER_VIEWPORT_EDGE_ZONE.key(), "55");
        settings.applyEdit(Tuning.PLAYER_VIEWPORT_EDGE_SPEED.key(), "900");
        new AppSettings(file);
        assertFalse(Tuning.PLAYER_VIEWPORT_EDGE_SCROLL.get());
        assertEquals(55.0, Tuning.PLAYER_VIEWPORT_EDGE_ZONE.get());
        assertEquals(900.0, Tuning.PLAYER_VIEWPORT_EDGE_SPEED.get());
        settings.applyEdit(Tuning.PLAYER_VIEWPORT_EDGE_ZONE.key(), "0");
        settings.applyEdit(Tuning.PLAYER_VIEWPORT_EDGE_SPEED.key(), "99999");
        assertEquals(1.0, Tuning.PLAYER_VIEWPORT_EDGE_ZONE.get());
        assertEquals(3000.0, Tuning.PLAYER_VIEWPORT_EDGE_SPEED.get());
    }

    @Test
    void editableSettingsDescribeEverySettingOnce() {
        java.util.List<AppSettings.SettingInfo> infos = AppSettings.editableSettings();
        assertEquals(infos.size(), infos.stream().map(AppSettings.SettingInfo::key).distinct().count());
        assertTrue(infos.size() > 100);
        for (AppSettings.SettingInfo info : infos) {
            assertTrue(info.validator().test(info.defaultValue()) || info.defaultValue().isEmpty(), info.key());
        }
        assertTrue(infos.stream().anyMatch(i -> i.key().equals("texture.fire.color") && i.kind() == Tuning.Kind.COLOR));
    }
}