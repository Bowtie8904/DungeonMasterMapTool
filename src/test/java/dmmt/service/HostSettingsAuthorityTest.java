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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dmmt.service.HostSettingsAuthority.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class HostSettingsAuthorityTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetGlobals() {
        OverlayTextures.applySettings(key -> null);
        Tuning.reset();
    }

    @Test
    void acceptedRequestsPersistBeforeReturningAnAuthoritativeSnapshot() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var before = authority.state();
            var request = request(before, "ui.gridOpacity", "0.65");
            var result = authority.execute(request);

            assertEquals(ACCEPTED, result.status());
            assertEquals(request.edit().commandId(), result.commandId());
            assertEquals(request.sequence(), result.sequence());
            assertEquals(before.sessionId(), result.state().sessionId());
            assertEquals(2, result.state().nextSequence());
            assertTrue(result.state().settings().revision() > before.settings().revision());
            assertEquals("0.65", result.state().settings().values().get("ui.gridOpacity"));
            assertEquals(0.65, Tuning.GRID_OPACITY.get());
            assertTrue(Files.readString(settings.getFile()).contains("ui.gridOpacity = 0.65"));
            assertTrue(settings.pollExternalChange(), "the host UI must be notified of accepted remote settings");
            assertFalse(settings.pollExternalChange());
        }
    }

    @Test
    void unrelatedLocalChangesDoNotRejectARequestBasedOnAnOlderGlobalRevision() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            settings.putBoolean("player.showGrid", true);
            var result = authority.execute(request);
            assertEquals(ACCEPTED, result.status());
            assertEquals("true", result.state().settings().values().get("player.showGrid"));
            assertEquals("0.6", result.state().settings().values().get("ui.gridOpacity"));
        }
    }

    @Test
    void conflictingLocalChangeWinsAndTheRejectedEditDoesNotWriteTheFile() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            settings.applyEdit("ui.gridOpacity", "0.7");
            byte[] hostFile = Files.readAllBytes(settings.getFile());
            var result = authority.execute(request);
            assertEquals(CONFLICT, result.status());
            assertTrue(result.message().contains("ui.gridOpacity"));
            assertEquals("0.7", result.state().settings().values().get("ui.gridOpacity"));
            assertEquals(0.7, Tuning.GRID_OPACITY.get());
            assertArrayEquals(hostFile, Files.readAllBytes(settings.getFile()));
            assertEquals(2, result.state().nextSequence(), "a rejection has an acknowledged outcome");
        }
    }

    @Test
    void aLocalChangeAwayAndBackStillInvalidatesAnOldRemoteEdit() {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            settings.applyEdit("ui.gridOpacity", "0.7");
            settings.applyEdit("ui.gridOpacity", "0.2");
            assertEquals(CONFLICT, authority.execute(request).status());
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void removedKeysRetainConflictRevisionsEvenWhenTheirRawValueReturnsToNull() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "audio.lastCategory", "remote-category");
            settings.put("audio.lastCategory", "local-category");
            settings.put("audio.lastCategory", null);
            assertEquals(CONFLICT, authority.execute(request).status());
            assertNull(settings.get("audio.lastCategory", null));
        }
    }

    @Test
    void externalHostEditsParticipateInConflictsAndSnapshots() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            externalEdit(settings, "ui.gridOpacity", "0.8");
            var result = authority.execute(request);
            assertEquals(CONFLICT, result.status());
            assertEquals("0.8", result.state().settings().values().get("ui.gridOpacity"));
            assertEquals(0.8, Tuning.GRID_OPACITY.get());
            assertTrue(settings.pollExternalChange(), "authority capture must not consume host UI notification");
        }
    }

    @Test
    void externalEditToADifferentSettingDoesNotCreateAConflict() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            externalEdit(settings, "player.showGrid", "true");
            var result = authority.execute(request);
            assertEquals(ACCEPTED, result.status());
            assertEquals("true", result.state().settings().values().get("player.showGrid"));
            assertTrue(Files.readString(settings.getFile()).contains("player.showGrid = true"));
        }
    }

    @Test
    void retryReturnsTheOriginalReceiptWithoutOverwritingLaterHostWork() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            var accepted = authority.execute(request);
            settings.applyEdit("ui.gridOpacity", "0.8");
            byte[] laterHostFile = Files.readAllBytes(settings.getFile());
            assertSame(accepted, authority.execute(request));
            assertEquals("0.6", accepted.state().settings().values().get("ui.gridOpacity"));
            assertEquals("0.8", authority.state().settings().values().get("ui.gridOpacity"));
            assertEquals(2, authority.state().nextSequence());
            assertArrayEquals(laterHostFile, Files.readAllBytes(settings.getFile()));
        }
    }

    @Test
    void retryOfAConflictDoesNotBecomeAcceptedWhenTheHostChangesBack() {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            settings.applyEdit("ui.gridOpacity", "0.8");
            var rejected = authority.execute(request);
            assertEquals(CONFLICT, rejected.status());
            settings.applyEdit("ui.gridOpacity", "0.2");
            assertSame(rejected, authority.execute(request));
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void missingAndReusedSequencesNeverMutateTheHost() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            var gap = new HostSettingsAuthority.Request(request.sessionId(), 2, request.edit());
            assertEquals(RESYNC_REQUIRED, authority.execute(gap).status());
            assertEquals(1, authority.state().nextSequence());
            assertEquals(ACCEPTED, authority.execute(request).status());
            var altered = new HostSettingsAuthority.Request(request.sessionId(), 1,
                    new AppSettings.RemoteEdit(request.edit().commandId(), request.edit().baseRevision(),
                            "ui.gridOpacity", request.edit().expectedValue(), "0.9"));
            assertEquals(RESYNC_REQUIRED, authority.execute(altered).status());
            assertEquals(0.6, Tuning.GRID_OPACITY.get());

            var reusedId = new HostSettingsAuthority.Request(request.sessionId(), 2, request.edit());
            assertEquals(RESYNC_REQUIRED, authority.execute(reusedId).status());
            assertEquals(2, authority.state().nextSequence());
        }
    }

    @Test
    void expiredReceiptsRequireResynchronizationRatherThanReexecution() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings, 1)) {
            var first = request(authority.state(), "ui.gridOpacity", "0.6");
            authority.execute(first);
            var second = request(authority.state(), "ui.gridOpacity", "0.7");
            var secondReceipt = authority.execute(second);
            var expired = authority.execute(first);
            assertEquals(RESYNC_REQUIRED, expired.status());
            assertTrue(expired.message().contains("unknown"));
            assertEquals(3, expired.state().nextSequence());
            assertEquals(0.7, Tuning.GRID_OPACITY.get());
            assertSame(secondReceipt, authority.execute(second));
        }
    }

    @Test
    void aDifferentSessionRejectsAnOldCommandEvenIfItsSequenceIsTheNextOne() {
        AppSettings settings = settings();
        HostSettingsAuthority.Request old;
        try (var authority = new HostSettingsAuthority(settings)) {
            old = request(authority.state(), "ui.gridOpacity", "0.6");
        }
        try (var restarted = new HostSettingsAuthority(settings)) {
            assertNotEquals(old.sessionId(), restarted.state().sessionId());
            assertEquals(RESYNC_REQUIRED, restarted.execute(old).status());
            assertEquals(1, restarted.state().nextSequence());
            assertEquals(Tuning.GRID_OPACITY.defaultValue(), Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void saveFailureRetainsPreviousMemoryRuntimeAndFileAndHasARetryReceipt() throws IOException {
        AppSettings settings = settings();
        settings.applyEdit("ui.gridOpacity", "0.2");
        byte[] before = Files.readAllBytes(settings.getFile());
        Path blockedTemp = settings.getFile().resolveSibling(settings.getFile().getFileName() + ".tmp");
        try (var authority = new HostSettingsAuthority(settings)) {
            var request = request(authority.state(), "ui.gridOpacity", "0.6");
            Files.createDirectory(blockedTemp);
            var result = authority.execute(request);
            assertEquals(SAVE_FAILED, result.status());
            assertEquals(0.2, Tuning.GRID_OPACITY.get());
            assertEquals("0.2", settings.get("ui.gridOpacity", null));
            assertEquals(request.edit().baseRevision(), result.state().settings().revision());
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
            assertSame(result, authority.execute(request));

            Files.delete(blockedTemp);
            var retryAsNewCommand = request(authority.state(), "ui.gridOpacity", "0.6");
            assertEquals(ACCEPTED, authority.execute(retryAsNewCommand).status());
        }
    }

    @Test
    void invalidRequestsHaveExplicitResultsAndLeaveTheHostFileUntouched() throws IOException {
        AppSettings settings = settings();
        byte[] before = Files.readAllBytes(settings.getFile());
        try (var authority = new HostSettingsAuthority(settings)) {
            for (var value : Map.of(
                    "unknown.setting", "true",
                    "import.lastDirectory", "B:\\maps",
                    "audio.lastDirectory", "B:\\music",
                    "ui.gridOpacity", "NaN",
                    "api.port", "Infinity",
                    "player.showGrid", "maybe",
                    "texture.fire.color", "not-a-color",
                    "ui.controls.hidden", "bogus").entrySet()) {
                var result = authority.execute(request(authority.state(), value.getKey(), value.getValue()));
                assertEquals(INVALID, result.status(), value.getKey());
                assertFalse(result.message().isBlank());
            }
            var newline = request(authority.state(), "audio.lastCategory", "name\napi.enabled = true");
            assertEquals(INVALID, authority.execute(newline).status());
            var unknownReset = request(authority.state(), "unknown.setting", null);
            assertEquals(INVALID, authority.execute(unknownReset).status());
            assertArrayEquals(before, Files.readAllBytes(settings.getFile()));
        }
    }

    @Test
    void wrongExpectedValuesAndFutureRevisionsCannotOverwriteHostSettings() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            var state = authority.state();
            var wrongExpected = new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                    new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                            "ui.gridOpacity", "not-the-host-value", "0.9"));
            assertEquals(CONFLICT, authority.execute(wrongExpected).status());
            state = authority.state();
            var future = new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                    new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision() + 1,
                            "ui.gridOpacity", state.settings().values().get("ui.gridOpacity"), "0.9"));
            assertEquals(INVALID, authority.execute(future).status());
            assertEquals(Tuning.GRID_OPACITY.defaultValue(), Tuning.GRID_OPACITY.get());
        }
    }

    @Test
    void resetToDefaultAndAcceptedNoOpKeepRevisionTrackingAccurate() {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            authority.execute(request(authority.state(), "ui.gridOpacity", "0.6"));
            var beforeNoOp = authority.state();
            var noOp = authority.execute(request(beforeNoOp, "ui.gridOpacity", "0.6"));
            assertEquals(ACCEPTED, noOp.status());
            assertEquals(beforeNoOp.settings().revision(), noOp.state().settings().revision());
            var reset = authority.execute(request(authority.state(), "ui.gridOpacity", null));
            assertEquals(ACCEPTED, reset.status());
            assertEquals(Tuning.GRID_OPACITY.defaultText(), reset.state().settings().values().get("ui.gridOpacity"));
            assertEquals(Tuning.GRID_OPACITY.defaultValue(), Tuning.GRID_OPACITY.get());
            assertTrue(reset.state().settings().revision() > beforeNoOp.settings().revision());
        }
    }

    @Test
    void pickerDirectoriesDoNotAdvanceTheSharedRevision() {
        AppSettings settings = settings();
        var before = settings.sharedState();
        settings.put("import.lastDirectory", "A:\\maps");
        settings.put("audio.lastDirectory", "A:\\music");
        assertEquals(before, settings.sharedState());
        assertFalse(settings.sharedState().snapshot().values().containsKey("import.lastDirectory"));
    }

    @Test
    void reloadingExplicitDefaultsAfterAResetDoesNotCreatePhantomConflicts() throws IOException {
        AppSettings settings = settings();
        try (var authority = new HostSettingsAuthority(settings)) {
            authority.execute(request(authority.state(), "ui.gridOpacity", "0.6"));
            authority.execute(request(authority.state(), "ui.gridOpacity", null));
            var beforeReload = authority.state();
            var request = request(beforeReload, "ui.gridOpacity", "0.7");
            externalEdit(settings, "player.showGrid", "true");
            var reloaded = settings.sharedState();
            assertEquals(beforeReload.settings().revision() + 1, reloaded.snapshot().revision());
            assertTrue(reloaded.changedAt().get("ui.gridOpacity") <= beforeReload.settings().revision());
            assertEquals(ACCEPTED, authority.execute(request).status());
        }
    }

    @Test
    void closedAndWrongThreadAccessAreRejectedWithoutMutation() {
        AppSettings settings = settings();
        var authority = new HostSettingsAuthority(settings);
        var request = request(authority.state(), "ui.gridOpacity", "0.9");
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, authority::state);
            assertThrows(IllegalStateException.class, () -> authority.execute(request));
            assertThrows(IllegalStateException.class, authority::close);
        }).join();
        authority.close();
        authority.close();
        assertThrows(IllegalStateException.class, authority::state);
        assertThrows(IllegalStateException.class, () -> authority.execute(request));
        assertEquals(Tuning.GRID_OPACITY.defaultValue(), Tuning.GRID_OPACITY.get());
    }

    @Test
    void hostAuthorityCannotBeCreatedOnARemoteClientContext() {
        AppSettings settings = settings();
        try (var context = settings.useHostSettings(new AppSettings.HostSnapshot(0, Map.of()), edit -> fail())) {
            assertThrows(IllegalStateException.class, settings::sharedState);
            assertThrows(IllegalStateException.class, () -> {
                try (var unexpected = new HostSettingsAuthority(settings)) {
                    fail("A client must not become a settings authority");
                }
            });
        }
    }

    @Test
    void aClientRequestCanRoundTripThroughHostAcceptanceWithoutWritingClientSettings() throws IOException {
        AppSettings host = new AppSettings(dir.resolve("host.ini"));
        AppSettings client = new AppSettings(dir.resolve("client.ini"));
        client.applyEdit("ui.gridOpacity", "0.9");
        byte[] clientFile = Files.readAllBytes(client.getFile());
        var requests = new ArrayList<AppSettings.RemoteEdit>();
        try (var authority = new HostSettingsAuthority(host);
             var context = client.useHostSettings(authority.state().settings(), requests::add)) {
            client.applyEdit("ui.gridOpacity", "0.6");
            assertEquals(1, requests.size());
            var state = authority.state();
            var result = authority.execute(new HostSettingsAuthority.Request(
                    state.sessionId(), state.nextSequence(), requests.getFirst()));
            assertEquals(ACCEPTED, result.status());
            assertTrue(context.accept(result.state().settings()));
            assertEquals("0.6", client.get("ui.gridOpacity", null));
            assertEquals("0.6", host.get("ui.gridOpacity", null));
            assertArrayEquals(clientFile, Files.readAllBytes(client.getFile()));
            assertTrue(Files.readString(host.getFile()).contains("ui.gridOpacity = 0.6"));
        }
        assertEquals("0.9", client.get("ui.gridOpacity", null));
        assertEquals(0.9, Tuning.GRID_OPACITY.get());
    }

    @Test
    void everyEditableSettingAcceptsItsOwnDefaultAndRejectsUnknownResetKeys() {
        for (var setting : AppSettings.editableSettings()) {
            assertDoesNotThrow(() -> AppSettings.validateSharedEdit(setting.key(), setting.defaultValue()), setting.key());
            assertDoesNotThrow(() -> AppSettings.validateSharedEdit(setting.key(), null), setting.key());
        }
        assertDoesNotThrow(() -> AppSettings.validateSharedEdit("player.screenDiagonalInches.2", "27"));
        assertDoesNotThrow(() -> AppSettings.validateSharedEdit("ui.controls.hidden", ""));
        assertDoesNotThrow(() -> AppSettings.validateSharedEdit("ui.sections.hidden", "effects, lighting"));
        assertThrows(IllegalArgumentException.class, () -> AppSettings.validateSharedEdit("api.port", "2.5"));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("autosave.minuteOptions", "1, NaN, 5"));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("player.tileInches", "1,5"));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("player.screenDiagonalInches.2", "-1"));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("player.screenDiagonalInches.2", "27,5"));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("player.screenDiagonalInches.other", null));
        assertThrows(IllegalArgumentException.class,
                () -> AppSettings.validateSharedEdit("ui.sections.hidden", "effects,"));
        assertThrows(IllegalArgumentException.class, () -> AppSettings.validateSharedEdit("unknown.setting", null));
    }

    @Test
    void sharedRevisionMetadataIsImmutableAndDoesNotConsumeExternalChangeNotifications() {
        AppSettings settings = settings();
        var before = settings.sharedState();
        settings.putBoolean("player.showGrid", true);
        var after = settings.sharedState();
        assertTrue(after.snapshot().revision() > before.snapshot().revision());
        assertEquals(after.snapshot().revision(), after.changedAt().get("player.showGrid"));
        assertThrows(UnsupportedOperationException.class, () -> after.changedAt().clear());
        assertNotEquals(before.snapshot().values(), after.snapshot().values());
    }

    @Test
    void requestEnvelopesRejectMissingIdentityAndNonpositiveSequences() {
        var edit = new AppSettings.RemoteEdit(UUID.randomUUID(), 0, "player.showGrid", null, "true");
        assertThrows(NullPointerException.class, () -> new HostSettingsAuthority.Request(null, 1, edit));
        assertThrows(NullPointerException.class, () -> new HostSettingsAuthority.Request(UUID.randomUUID(), 1, null));
        assertThrows(IllegalArgumentException.class, () -> new HostSettingsAuthority.Request(UUID.randomUUID(), 0, edit));
        AppSettings settings = settings();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var unexpected = new HostSettingsAuthority(settings, 0)) {
                fail("Receipt cache cannot be empty");
            }
        });
    }

    private AppSettings settings() {
        return new AppSettings(dir.resolve("settings.ini"));
    }

    private static HostSettingsAuthority.Request request(HostSettingsAuthority.State state, String key, String value) {
        return new HostSettingsAuthority.Request(state.sessionId(), state.nextSequence(),
                new AppSettings.RemoteEdit(UUID.randomUUID(), state.settings().revision(),
                        key, state.settings().values().get(key), value));
    }

    private static void externalEdit(AppSettings settings, String key, String value) throws IOException {
        Files.writeString(settings.getFile(), Files.readString(settings.getFile())
                .replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + " = [^\\r\\n]*",
                        java.util.regex.Matcher.quoteReplacement(key + " = " + value)));
        Files.setLastModifiedTime(settings.getFile(), FileTime.fromMillis(System.currentTimeMillis() + 10000));
    }
}
