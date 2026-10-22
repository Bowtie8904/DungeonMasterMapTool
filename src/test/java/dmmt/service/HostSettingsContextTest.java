package dmmt.service;

import dmmt.render.OverlayTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostSettingsContextTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void dialogEditRetainsItsOriginalHostRevisionAndCannotEscapeItsContext() throws Exception {
        var settings = localSettings();
        byte[] local = Files.readAllBytes(settings.getFile());
        var sent = new ArrayList<AppSettings.RemoteEdit>();
        java.util.function.Consumer<String> confirm;
        try (var context = settings.useHostSettings(snapshot(4, "library.folder", "original"), sent::add)) {
            confirm = settings.prepareHostEdit("library.folder");
            context.accept(snapshot(5, "library.folder", "changed"));
            context.accept(snapshot(6, "library.folder", "original"));
            confirm.accept("C:\\Maps");
            assertEquals(1, sent.size());
            assertEquals(4, sent.getFirst().baseRevision(), "Away-and-back changes must still conflict at the host.");
            assertEquals("original", sent.getFirst().expectedValue());
            assertEquals("C:\\Maps", sent.getFirst().value());
            assertEquals("original", settings.get("library.folder", null), "Unacknowledged paths are not effective.");
            context.pause();
            assertThrows(IllegalStateException.class, () -> confirm.accept("C:\\Other"));
        }
        assertThrows(IllegalStateException.class, () -> confirm.accept("C:\\Other"));
        assertThrows(IllegalStateException.class, () -> settings.prepareHostEdit("library.folder"));
        assertArrayEquals(local, Files.readAllBytes(settings.getFile()));
    }

    @Test
    void hostContextReplacesEffectiveValuesWithoutTouchingTheStandaloneFile() throws IOException {
        AppSettings settings = localSettings();
        settings.applyEdit("ui.gridOpacity", "0.25");
        settings.putBoolean("ui.performanceMode", true);
        byte[] before = Files.readAllBytes(settings.getFile());
        var snapshot = snapshot(1, "ui.gridOpacity", "0.75");

        try (var context = settings.useHostSettings(snapshot, edit -> fail("No edit was requested"))) {
            assertTrue(settings.usesHostSettings());
            assertEquals(0.75, settings.getDouble("ui.gridOpacity", 0));
            assertEquals(0.75, Tuning.GRID_OPACITY.get());
            assertFalse(settings.getBoolean("ui.performanceMode", false), "no fallback to B's local override");
            assertTrue(settings.pollExternalChange());
            assertFalse(settings.pollExternalChange());
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }

        assertFalse(settings.usesHostSettings());
        assertEquals(0.25, Tuning.GRID_OPACITY.get());
        assertTrue(settings.getBoolean("ui.performanceMode", false));
        assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        assertTrue(settings.pollExternalChange());
        assertFalse(settings.pollExternalChange());
    }

    @Test
    void editsAreRequestsUntilTheHostPublishesAnAcceptedSnapshot() throws IOException {
        AppSettings settings = localSettings();
        byte[] before = Files.readAllBytes(settings.getFile());
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();

        try (var context = settings.useHostSettings(snapshot(4, "ui.gridOpacity", "0.2"), sent::add)) {
            settings.pollExternalChange();
            settings.applyEdit("ui.gridOpacity", "0.6");

            assertEquals(1, sent.size());
            var edit = sent.getFirst();
            assertNotNull(edit.commandId());
            assertEquals(4, edit.baseRevision());
            assertEquals("ui.gridOpacity", edit.key());
            assertEquals("0.2", edit.expectedValue());
            assertEquals("0.6", edit.value());
            assertEquals(0.2, settings.getDouble("ui.gridOpacity", 0));
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
            assertFalse(settings.pollExternalChange());

            assertTrue(context.accept(snapshot(5, "ui.gridOpacity", "0.6")));
            assertEquals(0.6, Tuning.GRID_OPACITY.get());
            assertTrue(settings.pollExternalChange());
            assertEquals(1, sent.size(), "publishing a host update must not echo an edit");
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }
    }

    @Test
    void allTypedAndVisibilitySettersRouteThroughTheSameHostContext() {
        AppSettings settings = localSettings();
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();
        try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(8, Map.of()), sent::add)) {
            settings.putInt("autosave.minutes", 7);
            settings.putDouble("audio.masterVolume", 0.4);
            settings.putBoolean("player.showGrid", true);
            settings.setHiddenControls(java.util.Set.of("lighting.candle"));
            settings.setHiddenSections(java.util.Set.of("effects"));

            assertEquals(List.of("autosave.minutes", "audio.masterVolume", "player.showGrid",
                    AppSettings.HIDDEN_CONTROLS_KEY, AppSettings.HIDDEN_SECTIONS_KEY),
                    sent.stream().map(AppSettings.RemoteEdit::key).toList());
            assertEquals(List.of("7", "0.4", "true", "lighting.candle", "effects"),
                    sent.stream().map(AppSettings.RemoteEdit::value).toList());
            assertEquals(5, sent.stream().map(AppSettings.RemoteEdit::commandId).distinct().count());
            assertTrue(sent.stream().allMatch(edit -> edit.baseRevision() == 8 && edit.expectedValue() == null));
            assertTrue(settings.hiddenControls().isEmpty());
            assertTrue(settings.hiddenSections().isEmpty());

            context.accept(new AppSettings.HostSnapshot(9, Map.of(
                    AppSettings.HIDDEN_CONTROLS_KEY, "lighting.candle",
                    AppSettings.HIDDEN_SECTIONS_KEY, "effects")));
            assertEquals(java.util.Set.of("lighting.candle"), settings.hiddenControls());
            assertEquals(java.util.Set.of("effects"), settings.hiddenSections());
        }
    }

    @Test
    void restoringDefaultsIsANullRequestAndAcceptedMissingKeysUseBuiltIns() {
        AppSettings settings = localSettings();
        settings.applyEdit("ui.gridOpacity", "0.9");
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();
        try (var context = settings.useHostSettings(snapshot(1, "ui.gridOpacity", "0.4"), sent::add)) {
            settings.applyEdit("ui.gridOpacity", null);
            assertNull(sent.getFirst().value());
            assertEquals("0.4", sent.getFirst().expectedValue());
            assertEquals(0.4, Tuning.GRID_OPACITY.get());
            context.accept(new AppSettings.HostSnapshot(2, Map.of()));
            assertEquals(Tuning.GRID_OPACITY.defaultValue(), Tuning.GRID_OPACITY.get());
            assertNull(settings.get("ui.gridOpacity", null));
        }
        assertEquals(0.9, Tuning.GRID_OPACITY.get());
    }

    @Test
    void revisionsPreventStaleOrInconsistentSnapshots() {
        AppSettings settings = localSettings();
        var baseline = snapshot(7, "ui.gridOpacity", "0.7");
        try (var context = settings.useHostSettings(baseline, edit -> fail())) {
            settings.pollExternalChange();
            assertFalse(context.accept(baseline));
            assertFalse(context.accept(snapshot(6, "ui.gridOpacity", "0.6")));
            assertThrows(IllegalArgumentException.class,
                    () -> context.accept(snapshot(7, "ui.gridOpacity", "0.5")));
            assertEquals(0.7, Tuning.GRID_OPACITY.get());
            assertFalse(settings.pollExternalChange());
            assertTrue(context.accept(snapshot(8, "ui.gridOpacity", "0.8")));
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void pausingBlocksEditsAndRequiresAValidBaselineBeforeResuming() throws IOException {
        AppSettings settings = localSettings();
        byte[] before = Files.readAllBytes(settings.getFile());
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();
        try (var context = settings.useHostSettings(snapshot(3, "ui.gridOpacity", "0.3"), sent::add)) {
            context.pause();
            assertThrows(IllegalStateException.class, () -> settings.applyEdit("ui.gridOpacity", "0.9"));
            assertThrows(IllegalStateException.class, () -> context.accept(snapshot(4, "ui.gridOpacity", "0.4")));
            assertThrows(IllegalArgumentException.class, () -> context.resume(snapshot(2, "ui.gridOpacity", "0.2")));
            assertThrows(IllegalArgumentException.class, () -> context.resume(snapshot(3, "ui.gridOpacity", "0.9")));
            assertThrows(IllegalStateException.class, () -> settings.put("ui.gridOpacity", "0.8"));
            assertEquals(0.3, Tuning.GRID_OPACITY.get());
            assertTrue(sent.isEmpty());

            context.resume(snapshot(4, "ui.gridOpacity", "0.4"));
            settings.applyEdit("ui.gridOpacity", "0.5");
            assertEquals(4, sent.getFirst().baseRevision());
            assertEquals("0.4", sent.getFirst().expectedValue());
            assertEquals(0.4, Tuning.GRID_OPACITY.get());
            assertThrows(IllegalStateException.class, () -> context.resume(snapshot(5, "ui.gridOpacity", "0.5")));
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }
    }

    @Test
    void reconnectingAtTheSameRevisionPreservesAcceptedState() {
        AppSettings settings = localSettings();
        var baseline = snapshot(2, "ui.gridOpacity", "0.2");
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();
        try (var context = settings.useHostSettings(baseline, sent::add)) {
            context.pause();
            context.resume(baseline);
            settings.putBoolean("player.showGrid", true);
            assertEquals(1, sent.size());
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void failedEnqueueSurfacesTheErrorWithoutApplyingOrPersistingTheEdit() throws IOException {
        AppSettings settings = localSettings();
        byte[] before = Files.readAllBytes(settings.getFile());
        var failure = new IllegalStateException("Remote command queue is full.");
        try (var context = settings.useHostSettings(snapshot(1, "ui.gridOpacity", "0.1"), edit -> {
            throw failure;
        })) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> settings.applyEdit("ui.gridOpacity", "0.9")));
            assertEquals(0.1, Tuning.GRID_OPACITY.get());
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }
    }

    @Test
    void filePickerDirectoriesRemainLocalAndSessionOnlyEvenWhenDisconnected() throws IOException {
        AppSettings settings = localSettings();
        settings.put("import.lastDirectory", "B:\\maps");
        settings.put("audio.lastDirectory", "B:\\music");
        byte[] before = Files.readAllBytes(settings.getFile());

        try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(1, Map.of(
                "import.lastDirectory", "A:\\maps", "audio.lastDirectory", "A:\\music")),
                edit -> fail("Picker directories must not be sent to A"))) {
            assertEquals("B:\\maps", settings.get("import.lastDirectory", null));
            assertEquals("B:\\music", settings.get("audio.lastDirectory", null));
            settings.put("import.lastDirectory", "B:\\new-maps");
            context.pause();
            settings.put("audio.lastDirectory", "B:\\new-music");
            assertEquals("B:\\new-maps", settings.get("import.lastDirectory", null));
            assertEquals("B:\\new-music", settings.get("audio.lastDirectory", null));
            assertFalse(settings.sharedValues().containsKey("import.lastDirectory"));
            assertFalse(settings.sharedValues().containsKey("audio.lastDirectory"));
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }

        assertEquals("B:\\maps", settings.get("import.lastDirectory", null));
        assertEquals("B:\\music", settings.get("audio.lastDirectory", null));
        assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
    }

    @Test
    void localFileEditsAreDeferredUntilTheHostContextCloses() throws IOException {
        AppSettings settings = localSettings();
        settings.applyEdit("ui.gridOpacity", "0.25");
        try (var context = settings.useHostSettings(snapshot(1, "ui.gridOpacity", "0.75"), edit -> fail())) {
            settings.pollExternalChange();
            String changed = Files.readString(settings.getFile()).replace("ui.gridOpacity = 0.25", "ui.gridOpacity = 0.5");
            Files.writeString(settings.getFile(), changed);
            Files.setLastModifiedTime(settings.getFile(), FileTime.fromMillis(System.currentTimeMillis() + 10000));
            assertFalse(settings.pollExternalChange());
            assertEquals(0.75, Tuning.GRID_OPACITY.get());
            assertEquals("0.75", settings.sharedValues().get("ui.gridOpacity"));
            assertEquals(changed, Files.readString(settings.getFile()));
        }
        assertEquals(0.5, Tuning.GRID_OPACITY.get());
        assertEquals(0.5, settings.getDouble("ui.gridOpacity", 0));
        assertTrue(settings.pollExternalChange());
    }

    @Test
    void closedContextsCannotApplyLateUpdatesOrCloseANewConnection() {
        AppSettings settings = localSettings();
        var old = settings.useHostSettings(snapshot(1, "ui.gridOpacity", "0.1"), edit -> fail());
        assertThrows(IllegalStateException.class, () -> {
            try (var unexpected = settings.useHostSettings(snapshot(2, "ui.gridOpacity", "0.2"), edit -> fail())) {
                fail("Nested host contexts must be rejected");
            }
        });
        old.close();
        try (var current = settings.useHostSettings(snapshot(1, "ui.gridOpacity", "0.8"), edit -> fail())) {
            old.close();
            assertThrows(IllegalStateException.class, () -> old.accept(snapshot(100, "ui.gridOpacity", "0.9")));
            assertThrows(IllegalStateException.class, old::pause);
            assertThrows(IllegalStateException.class, () -> old.resume(snapshot(101, "ui.gridOpacity", "0.9")));
            assertTrue(settings.usesHostSettings());
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
        }
        settings.applyEdit("ui.gridOpacity", "0.6");
        assertEquals("0.6", new AppSettings(settings.getFile()).get("ui.gridOpacity", null));
    }

    @Test
    void hostSnapshotsApplyTexturesAndPreserveExistingParsingRules() {
        AppSettings settings = localSettings();
        settings.applyEdit("texture.fire.color", "#123456");
        try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(1, Map.of(
                "texture.fire.color", "#ABCDEF",
                "import.autoTags", "",
                "dm.zoom.max", "99999",
                "ping.color", "invalid")), edit -> fail())) {
            assertEquals("#ABCDEF", OverlayTextures.defaultColor(OverlayTextures.FIRE));
            assertTrue(Tuning.IMPORT_AUTO_TAGS.get().isEmpty());
            assertEquals("", settings.get("import.autoTags", "fallback"));
            assertEquals(100, Tuning.DM_ZOOM_MAX.get());
            assertEquals(Tuning.PING_COLOR.defaultValue(), Tuning.PING_COLOR.get());
            context.accept(new AppSettings.HostSnapshot(2, Map.of()));
            assertEquals("#FF4A1A", OverlayTextures.defaultColor(OverlayTextures.FIRE));
        }
        assertEquals("#123456", OverlayTextures.defaultColor(OverlayTextures.FIRE));
    }

    @Test
    void snapshotsAreImmutableAndRejectInvalidEnvelopesBeforeContextEntry() {
        AppSettings settings = localSettings();
        Map<String, String> source = new HashMap<>(Map.of("ui.gridOpacity", "0.4"));
        var baseline = new AppSettings.HostSnapshot(1, source);
        source.put("ui.gridOpacity", "0.8");
        assertEquals("0.4", baseline.values().get("ui.gridOpacity"));
        assertThrows(UnsupportedOperationException.class, () -> baseline.values().clear());
        assertThrows(IllegalArgumentException.class, () -> new AppSettings.HostSnapshot(-1, Map.of()));
        assertThrows(NullPointerException.class, () -> new AppSettings.HostSnapshot(0, null));
        assertThrows(NullPointerException.class, () -> {
            try (var unexpected = settings.useHostSettings(null, edit -> fail())) {
                fail("A host snapshot is required");
            }
        });
        assertThrows(NullPointerException.class, () -> {
            try (var unexpected = settings.useHostSettings(baseline, null)) {
                fail("An edit sender is required");
            }
        });
        assertFalse(settings.usesHostSettings());
        assertThrows(IllegalArgumentException.class,
                () -> new AppSettings.RemoteEdit(UUID.randomUUID(), -1, "key", null, null));
        assertThrows(NullPointerException.class,
                () -> new AppSettings.RemoteEdit(null, 0, "key", null, null));
        assertThrows(NullPointerException.class,
                () -> new AppSettings.RemoteEdit(UUID.randomUUID(), 0, null, null, null));
    }

    @Test
    void sharedValuesAreDetachedAndIncludeExternalHostEditsButNotPickerPaths() throws IOException {
        AppSettings settings = localSettings();
        settings.put("import.lastDirectory", "B:\\maps");
        settings.applyEdit("ui.gridOpacity", "0.3");
        var old = settings.sharedValues();
        assertFalse(old.containsKey("import.lastDirectory"));
        assertFalse(old.containsKey("audio.lastDirectory"));
        assertThrows(UnsupportedOperationException.class, () -> old.put("ui.gridOpacity", "0.9"));
        Files.writeString(settings.getFile(),
                Files.readString(settings.getFile()).replace("ui.gridOpacity = 0.3", "ui.gridOpacity = 0.4"));
        Files.setLastModifiedTime(settings.getFile(), FileTime.fromMillis(System.currentTimeMillis() + 10000));
        assertEquals("0.4", settings.sharedValues().get("ui.gridOpacity"));
        assertEquals("0.3", old.get("ui.gridOpacity"));
        assertTrue(settings.pollExternalChange());
    }

    @Test
    void remoteValuesUseTheSameSingleLineNormalizationAsStandaloneWrites() {
        AppSettings settings = localSettings();
        List<AppSettings.RemoteEdit> sent = new ArrayList<>();
        try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(1, Map.of()), sent::add)) {
            settings.put("custom.example", "one\r\ntwo");
            assertEquals("one  two", sent.getFirst().value());
            assertNull(settings.get("custom.example", null));
        }
    }

    private AppSettings localSettings() {
        return new AppSettings(dir.resolve("client.ini"));
    }

    private static AppSettings.HostSnapshot snapshot(long revision, String key, String value) {
        return new AppSettings.HostSnapshot(revision, Map.of(key, value));
    }
}
