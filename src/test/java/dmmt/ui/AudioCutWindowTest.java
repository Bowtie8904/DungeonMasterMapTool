package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioOutput;
import dmmt.audio.TestAudioFiles;
import dmmt.audio.WaveformPeaks;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCutWindowTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void mediaEndRestartsWholeFileAndSelectionDespiteRoundedPosition() throws Exception {
        withWindow((window, voice) -> {
            invoke(window, "togglePlay");
            voice.endAt(999);
            assertEquals(0, voice.position);
            assertEquals(2, voice.plays);

            select(window, 600, 1000);
            voice.endAt(999);
            assertEquals(600, voice.position);
            assertEquals(3, voice.plays);
            voice.endAt(999);
            assertEquals(600, voice.position);
            assertEquals(4, voice.plays);
        });
    }

    @Test
    void tickerLoopsVisibleRangeAndShortSelectionAtExactBoundary() throws Exception {
        withWindow((window, voice) -> {
            field("viewStartMs").setLong(window, 100);
            field("viewSpanMs").setLong(window, 500);
            invoke(window, "togglePlay");
            voice.position = 599.9;
            invoke(window, "updatePlayback");
            assertEquals(1, voice.plays);
            voice.position = 600;
            invoke(window, "updatePlayback");
            assertEquals(100, voice.position);
            assertEquals(2, voice.plays);

            select(window, 200, 300);
            voice.position = 300;
            invoke(window, "updatePlayback");
            assertEquals(200, voice.position);
            assertEquals(3, voice.plays);
        });
    }

    @Test
    void disablingLoopStopsAtSelectionAndMediaEndAndPauseDoesNotRestart() throws Exception {
        withWindow((window, voice) -> {
            ((CheckBox) field("loop").get(window)).setSelected(false);
            select(window, 200, 300);
            invoke(window, "togglePlay");
            voice.position = 300;
            invoke(window, "updatePlayback");
            assertFalse(field("playing").getBoolean(window));
            assertTrue(voice.paused);
            assertEquals(200, voice.position);

            invoke(window, "clearSelection");
            invoke(window, "togglePlay");
            voice.endAt(999);
            assertFalse(field("playing").getBoolean(window));
            assertEquals(1000, voice.position);
            assertEquals(2, voice.plays);

            ((CheckBox) field("loop").get(window)).setSelected(true);
            invoke(window, "togglePlay");
            invoke(window, "togglePlay");
            voice.endAt(999);
            assertEquals(3, voice.plays);
            assertFalse(field("playing").getBoolean(window));
        });
    }

    @Test
    void mergingNonAdjacentProposalsKeepsOthersAndSelectsTheFullSpan() throws Exception {
        withWindow((window, voice) -> {
            ListView<WaveformPeaks.Range> list = detectedList(window);
            assertEquals(SelectionMode.MULTIPLE, list.getSelectionModel().getSelectionMode());
            WaveformPeaks.Range middle = new WaveformPeaks.Range(300, 400);
            list.getItems().setAll(new WaveformPeaks.Range(100, 200), middle,
                    new WaveformPeaks.Range(500, 800), new WaveformPeaks.Range(900, 1000));
            list.getSelectionModel().selectIndices(2, 0);
            button(window, "audioCutMerge").fire();

            WaveformPeaks.Range merged = new WaveformPeaks.Range(100, 800);
            assertEquals(List.of(merged, middle, new WaveformPeaks.Range(900, 1000)), list.getItems());
            assertEquals(List.of(merged), list.getSelectionModel().getSelectedItems());
            assertEquals(100, field("selectionStartMs").getLong(window));
            assertEquals(800, field("selectionEndMs").getLong(window));
            assertTrue(button(window, "audioCutMerge").isDisabled());
            assertFalse(button(window, "audioCutCreateSelected").isDisabled());
            assertRequests(window, true, new long[][]{{100, 800}}, new int[]{1});
            assertRequests(window, false, new long[][]{{100, 800}, {300, 400}, {900, 1000}}, new int[]{1, 2, 3});
        });
    }

    @Test
    void selectedExportUsesOnlySelectedProposalsAndDeletionRemovesThemFromAllExports() throws Exception {
        withWindow((window, voice) -> {
            ListView<WaveformPeaks.Range> list = detectedList(window);
            WaveformPeaks.Range remaining = new WaveformPeaks.Range(300, 400);
            list.getItems().setAll(new WaveformPeaks.Range(100, 200), remaining,
                    new WaveformPeaks.Range(500, 800));
            list.getSelectionModel().selectIndices(0, 2);
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{1, 3});
            button(window, "audioCutDelete").fire();
            assertEquals(List.of(remaining), list.getItems());
            assertTrue(list.getSelectionModel().isEmpty());
            assertEquals(-1, field("selectionStartMs").getLong(window));
            assertEquals(-1, field("selectionEndMs").getLong(window));
            assertRequests(window, false, new long[][]{{300, 400}}, new int[]{1});
            assertRequests(window, true, new long[][]{}, new int[]{});

            list.getSelectionModel().select(0);
            list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE,
                    false, false, false, false));
            assertTrue(list.getItems().isEmpty());
            assertTrue(button(window, "audioCutCreateAll").isDisabled());
            invoke(window, "createAllClips");
            assertTrue(list.getItems().isEmpty());
            assertRequests(window, false, new long[][]{}, new int[]{});
        });
    }

    @Test
    void proposalActionsAreDisabledWhileCuttingAndRestoreSelectionDependentAvailability() throws Exception {
        withWindow((window, voice) -> {
            ListView<WaveformPeaks.Range> list = detectedList(window);
            assertTrue(button(window, "audioCutDelete").isDisabled());
            assertTrue(button(window, "audioCutCreateSelected").isDisabled());
            list.getItems().setAll(new WaveformPeaks.Range(0, 300), new WaveformPeaks.Range(500, 1000));
            list.getSelectionModel().selectAll();
            Method busy = AudioCutWindow.class.getDeclaredMethod("setCuttingBusy", boolean.class);
            busy.setAccessible(true);
            busy.invoke(window, true);
            for (String id : List.of("audioCutDetect", "audioCutMerge", "audioCutDelete",
                    "audioCutCreateSelected", "audioCutCreateAll")) {
                assertTrue(button(window, id).isDisabled(), id);
            }
            assertTrue(list.isDisabled());
            assertTrue(button(window, "audioCutUpdateBounds").isDisabled());
            invoke(window, "mergeSelectedTracks");
            invoke(window, "deleteSelectedTracks");
            assertEquals(2, list.getItems().size());
            busy.invoke(window, false);
            for (String id : List.of("audioCutDetect", "audioCutMerge", "audioCutDelete",
                    "audioCutCreateSelected", "audioCutCreateAll")) {
                assertFalse(button(window, id).isDisabled(), id);
            }
            assertTrue(button(window, "audioCutUpdateBounds").isDisabled());
            list.getSelectionModel().clearAndSelect(0);
            assertFalse(button(window, "audioCutUpdateBounds").isDisabled());
            busy.invoke(window, true);
            select(window, 50, 250);
            invoke(window, "updateSelectedTrackBounds");
            assertEquals(new WaveformPeaks.Range(0, 300), list.getItems().getFirst());
            busy.invoke(window, false);
            list.getSelectionModel().selectAll();
            list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE,
                    false, false, false, false));
            assertTrue(list.getItems().isEmpty());
        });
    }

    @Test
    void batchNamesUseEnteredBaseAndSelectionAndExportCompletionKeepIt() throws Exception {
        withWindow((window, voice) -> {
            TextField name = (TextField) field("nameField").get(window);
            name.setText("  Forest  ");
            ListView<WaveformPeaks.Range> list = detectedList(window);
            list.getItems().setAll(new WaveformPeaks.Range(100, 200), new WaveformPeaks.Range(300, 400),
                    new WaveformPeaks.Range(500, 800));
            list.getSelectionModel().selectIndices(0, 2);
            assertEquals("  Forest  ", name.getText());
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{1, 3});
            assertRequests(window, false, new long[][]{{100, 200}, {300, 400}, {500, 800}}, new int[]{1, 2, 3});
            Method finish = AudioCutWindow.class.getDeclaredMethod("finishCutting", String.class);
            finish.setAccessible(true);
            finish.invoke(window, "Clips added");
            assertEquals("  Forest  ", name.getText());
            name.setText(" ");
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{1, 3});
        });
    }

    @Test
    void batchNumberingContinuesAfterHighestExactBaseAcrossLibraryAndRefreshesEachBatch() throws Exception {
        withWindow((window, voice) -> {
            TextField name = (TextField) field("nameField").get(window);
            name.setText("Combat");
            AudioLibraryService library = (AudioLibraryService) field("library").get(window);
            dmmt.audio.AudioTrack source = (dmmt.audio.AudioTrack) field("track").get(window);
            library.renameTrack(source.getId(), "combat 02");
            Path file = library.fileOf(source);
            dmmt.audio.AudioTrack highest = library.importFile(file, AudioKind.MUSIC, null);
            library.renameTrack(highest.getId(), "COMBAT 12");
            for (String unrelated : List.of("Combat extended 99", "Combat 80 extra", "Combat",
                    "Combat 1.5", "PreCombat 90")) {
                dmmt.audio.AudioTrack imported = library.importFile(file, AudioKind.EFFECT, null);
                library.renameTrack(imported.getId(), unrelated);
            }
            ListView<WaveformPeaks.Range> list = detectedList(window);
            list.getItems().setAll(new WaveformPeaks.Range(100, 200), new WaveformPeaks.Range(300, 400),
                    new WaveformPeaks.Range(500, 800));
            list.getSelectionModel().selectIndices(0, 2);
            assertRequests(window, false, new long[][]{{100, 200}, {300, 400}, {500, 800}}, new int[]{13, 14, 15});
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{13, 15});
            library.renameTrack(highest.getId(), "Combat 99");
            assertRequests(window, false, new long[][]{{100, 200}, {300, 400}, {500, 800}},
                    new int[]{100, 101, 102});
            name.setText("Combat (night)+");
            library.renameTrack(highest.getId(), "combat (night)+ 005");
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{6, 8});
            name.setText("Forest");
            assertRequests(window, true, new long[][]{{100, 200}, {500, 800}}, new int[]{1, 3});
        });
    }

    @Test
    void updateBoundsAppliesTimeFieldsAndWaveformRangeToOnlyOneProposalAndExports() throws Exception {
        withWindow((window, voice) -> {
            ListView<WaveformPeaks.Range> list = detectedList(window);
            WaveformPeaks.Range other = new WaveformPeaks.Range(800, 1000);
            list.getItems().setAll(new WaveformPeaks.Range(300, 700), other);
            assertTrue(button(window, "audioCutUpdateBounds").isDisabled());
            list.getSelectionModel().select(0);
            TextField start = (TextField) field("startField").get(window);
            TextField end = (TextField) field("endField").get(window);
            start.setText("0:00.125");
            end.setText("0:00.750");
            button(window, "audioCutUpdateBounds").fire();
            assertEquals(List.of(new WaveformPeaks.Range(125, 750), other), list.getItems());
            assertEquals(125, field("selectionStartMs").getLong(window));
            assertEquals(750, field("selectionEndMs").getLong(window));
            assertRequests(window, true, new long[][]{{125, 750}}, new int[]{1});
            assertRequests(window, false, new long[][]{{125, 750}, {800, 1000}}, new int[]{1, 2});

            select(window, 0, 799);
            assertEquals("0:00.000", start.getText());
            assertEquals("0:00.799", end.getText());
            button(window, "audioCutUpdateBounds").fire();
            assertEquals(List.of(new WaveformPeaks.Range(0, 799), other), list.getItems());
            list.getSelectionModel().clearAndSelect(1);
            list.getSelectionModel().clearAndSelect(0);
            assertEquals("0:00.799", end.getText());
            assertRequests(window, true, new long[][]{{0, 799}}, new int[]{1});
        });
    }

    @Test
    void invalidOrAmbiguousBoundsLeaveProposalsUnchanged() throws Exception {
        withWindow((window, voice) -> {
            ListView<WaveformPeaks.Range> list = detectedList(window);
            List<WaveformPeaks.Range> original = List.of(new WaveformPeaks.Range(100, 400),
                    new WaveformPeaks.Range(500, 1000));
            list.getItems().setAll(original);
            list.getSelectionModel().select(0);
            TextField start = (TextField) field("startField").get(window);
            TextField end = (TextField) field("endField").get(window);
            for (String[] times : List.of(new String[]{"bad", "0:00.400"},
                    new String[]{"-0.100", "0:00.400"}, new String[]{"NaN", "0:00.400"},
                    new String[]{"0:00.400", "0:00.400"}, new String[]{"0:00.500", "0:00.400"},
                    new String[]{"0:00.100", "0:01.001"})) {
                start.setText(times[0]);
                end.setText(times[1]);
                button(window, "audioCutUpdateBounds").fire();
                assertEquals(original, list.getItems());
                assertTrue(((javafx.scene.control.Label) field("status").get(window)).getText()
                        .startsWith("Invalid bounds"));
            }
            list.getSelectionModel().selectAll();
            select(window, 0, 700);
            invoke(window, "updateSelectedTrackBounds");
            assertEquals(original, list.getItems());
        });
    }

    @SuppressWarnings("unchecked")
    private static ListView<WaveformPeaks.Range> detectedList(AudioCutWindow window) throws Exception {
        return (ListView<WaveformPeaks.Range>) field("detectedList").get(window);
    }

    private static Button button(AudioCutWindow window, String id) throws Exception {
        return (Button) ((Stage) field("stage").get(window)).getScene().lookup("#" + id);
    }

    private static void assertRequests(AudioCutWindow window, boolean selectedOnly, long[][] ranges,
                                       int[] numbers) throws Exception {
        Method method = AudioCutWindow.class.getDeclaredMethod("detectedClipRequests", boolean.class);
        method.setAccessible(true);
        List<?> requests = (List<?>) method.invoke(window, selectedOnly);
        assertEquals(ranges.length, requests.size());
        dmmt.audio.AudioTrack track = (dmmt.audio.AudioTrack) field("track").get(window);
        String baseName = ((TextField) field("nameField").get(window)).getText();
        baseName = baseName == null || baseName.isBlank() ? track.getName() : baseName.trim();
        for (int index = 0; index < ranges.length; index++) {
            Object request = requests.get(index);
            for (String property : List.of("startMs", "endMs", "name")) {
                Method accessor = request.getClass().getDeclaredMethod(property);
                accessor.setAccessible(true);
                Object expected = switch (property) {
                    case "startMs" -> ranges[index][0];
                    case "endMs" -> ranges[index][1];
                    default -> baseName + " " + String.format(java.util.Locale.ROOT, "%02d", numbers[index]);
                };
                assertEquals(expected, accessor.invoke(request));
            }
        }
    }

    private void withWindow(Scenario scenario) throws Exception {
        Path file = dir.resolve("effect.wav");
        TestAudioFiles.writeWav(file, 1000, 8000);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        library.importFile(file, AudioKind.EFFECT, null);
        FutureTask<Void> task = new FutureTask<>(() -> {
            PreviewVoice voice = new PreviewVoice();
            AudioOutput output = (path, loop) -> voice;
            AudioCutWindow window = new AudioCutWindow(null, library, library.effects().get(0), null, output, () -> 0);
            try {
                scenario.run(window, voice);
            } finally {
                invoke(window, "dispose");
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void editorOverlapsAtSelectionStartWithEqualPowerAndPreloadsLaterRepetitions() throws Exception {
        withCrossfade((window, output) -> {
            select(window, 200, 1000);
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            assertEquals(200, next.position);
            assertEquals(0, next.plays);
            assertEquals(0, next.volume);
            first.position = 750;
            invoke(window, "updatePlayback");
            assertEquals(1, next.plays);
            assertEquals(200, field("playheadMs").getLong(window));
            assertEquals(previewVolume(window), first.volume);
            assertEquals(0, next.volume);

            next.position = 325;
            invoke(window, "updatePlayback");
            assertEquals(previewVolume(window) * Math.sqrt(0.5), first.volume, 0.0001);
            assertEquals(previewVolume(window) * Math.sqrt(0.5), next.volume, 0.0001);
            first.endAt(999);
            assertEquals(1, next.plays, "the outgoing media-end event must not restart the new voice");
            next.position = 450;
            invoke(window, "updatePlayback");
            assertTrue(first.disposed);
            assertEquals(previewVolume(window), next.volume, 0.0001);
            invoke(window, "updatePlayback");
            assertEquals(3, output.voices.size());
            assertEquals(200, output.voices.get(2).position);
            next.position = 750;
            invoke(window, "updatePlayback");
            assertEquals(1, output.voices.get(2).plays);
        });
    }

    @Test
    void editorLoopsVisibleRangeAndCapsShortRangeOverlap() throws Exception {
        withCrossfade((window, output) -> {
            field("viewStartMs").setLong(window, 100);
            field("viewSpanMs").setLong(window, 500);
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            first.position = 349;
            invoke(window, "updatePlayback");
            assertEquals(0, output.voices.get(1).plays);
            first.position = 350;
            invoke(window, "updatePlayback");
            PreviewVoice next = output.voices.get(1);
            assertEquals(100, next.position);
            assertEquals(1, next.plays);
            next.position = 350;
            invoke(window, "updatePlayback");
            assertTrue(first.disposed);

            select(window, 200, 300);
            next.position = 200;
            invoke(window, "updatePlayback");
            PreviewVoice prepared = output.voices.get(2);
            next.position = 249;
            invoke(window, "updatePlayback");
            assertEquals(0, prepared.plays);
            next.position = 250;
            invoke(window, "updatePlayback");
            assertEquals(1, prepared.plays);
            assertEquals(200, prepared.position);
        });
    }

    @Test
    void pausingFreezesBothPreviewVoicesAndSeekingCancelsTheBlend() throws Exception {
        withCrossfade((window, output) -> {
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            first.position = 750;
            invoke(window, "updatePlayback");
            next.position = 125;
            invoke(window, "updatePlayback");
            double before = next.volume;
            invoke(window, "togglePlay");
            assertTrue(first.paused);
            assertTrue(next.paused);
            invoke(window, "updatePlayback");
            assertEquals(before, next.volume);
            invoke(window, "togglePlay");
            invoke(window, "updatePlayback");
            assertFalse(first.paused);
            assertFalse(next.paused);
            assertEquals(before, next.volume, 0.0001);
            Method seek = AudioCutWindow.class.getDeclaredMethod("seek", long.class);
            seek.setAccessible(true);
            seek.invoke(window, 500L);
            assertTrue(first.disposed);
            assertEquals(500, next.position);
            assertEquals(previewVolume(window), next.volume);
            assertEquals(null, field("outgoingVoice").get(window));
        });
    }

    @Test
    void rangeChangesLoopDisableAndCloseReleaseAdditionalVoices() throws Exception {
        withCrossfade((window, output) -> {
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice prepared = output.voices.get(1);
            select(window, 200, 1000);
            assertTrue(prepared.disposed);
            invoke(window, "updatePlayback");
            PreviewVoice next = output.voices.get(2);
            first.position = 750;
            invoke(window, "updatePlayback");
            ((CheckBox) field("loop").get(window)).setSelected(false);
            assertTrue(first.disposed);
            assertEquals(previewVolume(window), next.volume);
            next.position = 1000;
            invoke(window, "updatePlayback");
            assertFalse(field("playing").getBoolean(window));
            assertEquals(200, next.position);

            ((CheckBox) field("loop").get(window)).setSelected(true);
            invoke(window, "togglePlay");
            invoke(window, "dispose");
            assertTrue(output.voices.stream().allMatch(voice -> voice.disposed));
        });
    }

    @Test
    void unreadyPreviewVoiceFallsBackToBoundaryRestart() throws Exception {
        withCrossfade((window, output) -> {
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            next.ready = false;
            first.position = 750;
            invoke(window, "updatePlayback");
            assertEquals(0, next.plays);
            assertEquals(previewVolume(window), first.volume);
            first.endAt(999);
            assertEquals(0, first.position);
            assertEquals(2, first.plays);
            assertTrue(next.disposed);
        });
    }

    @Test
    void closingDuringAnOverlapDisposesBothVoicesAndIgnoresLateEndEvents() throws Exception {
        withCrossfade((window, output) -> {
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            first.position = 750;
            invoke(window, "updatePlayback");
            invoke(window, "dispose");
            assertTrue(first.disposed);
            assertTrue(next.disposed);
            first.endAt(1000);
            next.endAt(1000);
            assertFalse(field("playing").getBoolean(window));
            assertEquals(2, output.voices.size());
        });
    }

    private void withCrossfade(CrossfadeScenario scenario) throws Exception {
        Path file = dir.resolve("effect.wav");
        TestAudioFiles.writeWav(file, 1000, 8000);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        library.importFile(file, AudioKind.EFFECT, null);
        FutureTask<Void> task = new FutureTask<>(() -> {
            PreviewOutput output = new PreviewOutput();
            AudioCutWindow window = new AudioCutWindow(null, library, library.effects().get(0), null, output, () -> 0.5);
            try {
                scenario.run(window, output);
            } finally {
                invoke(window, "dispose");
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void overridesUpdateBothPreviewVoicesWithoutRestartingAndResetToAutomatic() throws Exception {
        withCrossfade((window, output) -> {
            dmmt.audio.AudioTrack track = (dmmt.audio.AudioTrack) field("track").get(window);
            AudioLibraryService library = (AudioLibraryService) field("library").get(window);
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            first.position = 750;
            invoke(window, "updatePlayback");
            next.position = 125;
            invoke(window, "updatePlayback");
            ((dmmt.audio.LoopCrossfade) field("blend").get(window)).resume();
            double original = next.volume;
            library.setGainOverride(track.getId(), track.effectiveGainDb() - 6);
            invoke(window, "updatePlayback");
            assertEquals(original * Math.pow(10, -6.0 / 20), next.volume, 0.0001);
            assertEquals(next.volume, first.volume, 0.0001);
            assertEquals(1, first.plays);
            assertEquals(1, next.plays);
            ((dmmt.audio.LoopCrossfade) field("blend").get(window)).resume();
            library.setGainOverride(track.getId(), null);
            invoke(window, "updatePlayback");
            assertEquals(original, next.volume, 0.0001);
        });
    }

    @Test
    void boostedPreviewOpensPreparedAudioInsteadOfTheQuietOriginal() throws Exception {
        Path file = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(file, 1000, 8000, 0.01);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        dmmt.audio.AudioTrack track = library.importFile(file, AudioKind.EFFECT, null);
        FutureTask<Void> task = new FutureTask<>(() -> {
            List<Path> opened = new ArrayList<>();
            AudioOutput output = (path, loop) -> {
                opened.add(path);
                return new PreviewVoice();
            };
            AudioCutWindow window = new AudioCutWindow(null, library, track, null, output, () -> 0);
            try {
                invoke(window, "togglePlay");
                assertEquals(List.of(library.playbackFileOf(track)), opened);
                assertFalse(opened.get(0).equals(library.fileOf(track)));
            } finally {
                invoke(window, "dispose");
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    private static double previewVolume(AudioCutWindow window) throws Exception {
        dmmt.audio.AudioTrack track = (dmmt.audio.AudioTrack) field("track").get(window);
        return 0.8 * track.playbackVolumeFactor();
    }

    @Test
    void limiterCopySwitchPreservesPreviewOverlapPositionAndIgnoresOldEndEvents() throws Exception {
        withCrossfade((window, output) -> {
            dmmt.audio.AudioTrack track = (dmmt.audio.AudioTrack) field("track").get(window);
            AudioLibraryService library = (AudioLibraryService) field("library").get(window);
            invoke(window, "togglePlay");
            PreviewVoice first = output.voices.get(0);
            PreviewVoice next = output.voices.get(1);
            first.position = 750;
            invoke(window, "updatePlayback");
            next.position = 125;
            invoke(window, "updatePlayback");
            ((dmmt.audio.LoopCrossfade) field("blend").get(window)).resume();
            library.setGainOverride(track.getId(), 6.0);
            invoke(window, "updatePlayback");
            assertTrue(first.disposed);
            assertTrue(next.disposed);
            PreviewVoice newIncoming = (PreviewVoice) field("voice").get(window);
            PreviewVoice newOutgoing = (PreviewVoice) field("outgoingVoice").get(window);
            assertEquals(125, newIncoming.position);
            assertEquals(750, newOutgoing.position);
            assertEquals(0.8 * Math.sqrt(0.5), newIncoming.volume, 0.0001);
            assertEquals(0.8 * Math.sqrt(0.5), newOutgoing.volume, 0.0001);
            next.endAt(1000);
            assertEquals(newIncoming, field("voice").get(window));
            assertEquals(125, newIncoming.position);
            invoke(window, "togglePlay");
            library.setGainOverride(track.getId(), null);
            invoke(window, "updatePlayback");
            PreviewVoice reset = (PreviewVoice) field("voice").get(window);
            assertEquals(125, reset.position);
            assertEquals(0, reset.plays, "a source replacement must not resume paused preview playback");
            assertTrue(newIncoming.disposed);
        });
    }

    @Test
    void previewKeepsOldSourceUntilReplacementLoadsAndReleasesPendingCopiesOnClose() throws Exception {
        withCrossfade((window, output) -> {
            dmmt.audio.AudioTrack track = (dmmt.audio.AudioTrack) field("track").get(window);
            AudioLibraryService library = (AudioLibraryService) field("library").get(window);
            invoke(window, "togglePlay");
            PreviewVoice old = output.voices.get(0);
            old.position = 300;
            library.setGainOverride(track.getId(), 6.0);
            output.readyOnOpen = false;
            invoke(window, "updatePlayback");
            assertFalse(old.disposed);
            PreviewVoice replacement = output.voices.get(2);
            replacement.ready = true;
            output.voices.get(3).ready = true;
            old.position = 500;
            invoke(window, "updatePlayback");
            assertTrue(old.disposed);
            assertEquals(500, replacement.position);
            assertEquals(1, replacement.plays);
            library.setGainOverride(track.getId(), 12.0);
            invoke(window, "updatePlayback");
            invoke(window, "dispose");
            assertTrue(output.voices.stream().allMatch(voice -> voice.disposed));
        });
    }

    private interface CrossfadeScenario {
        void run(AudioCutWindow window, PreviewOutput output) throws Exception;
    }

    private static final class PreviewOutput implements AudioOutput {
        private final List<PreviewVoice> voices = new ArrayList<>();
        private boolean readyOnOpen = true;

        @Override
        public Voice open(Path file, boolean loop) {
            PreviewVoice voice = new PreviewVoice();
            voice.ready = readyOnOpen;
            voices.add(voice);
            return voice;
        }
    }

    private static Field field(String name) throws Exception {
        Field field = AudioCutWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void invoke(AudioCutWindow window, String name) throws Exception {
        Method method = AudioCutWindow.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(window);
    }

    private static void select(AudioCutWindow window, long start, long end) throws Exception {
        Method method = AudioCutWindow.class.getDeclaredMethod("setSelection", long.class, long.class);
        method.setAccessible(true);
        method.invoke(window, start, end);
    }

    private interface Scenario {
        void run(AudioCutWindow window, PreviewVoice voice) throws Exception;
    }

    private static final class PreviewVoice implements AudioOutput.Voice {
        private double position;
        private int plays;
        private boolean paused;
        private boolean disposed;
        private boolean ready = true;
        private double volume;
        private Runnable onEnd;

        void endAt(double position) {
            this.position = position;
            onEnd.run();
        }

        @Override
        public void play() {
            plays++;
            paused = false;
        }

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void dispose() {
            disposed = true;
            paused = true;
        }

        @Override
        public void setVolume(double volume) {
            this.volume = volume;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public double positionMs() {
            return position;
        }

        @Override
        public double durationMs() {
            return 1000;
        }

        @Override
        public void setOnEnd(Runnable action) {
            onEnd = action;
        }

        @Override
        public void seek(double millis) {
            position = millis;
        }
    }
}
