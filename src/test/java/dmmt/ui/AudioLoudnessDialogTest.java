package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioEngine;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioTrack;
import dmmt.audio.FakeAudioOutput;
import dmmt.audio.TestAudioFiles;
import dmmt.service.AppSettings;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioLoudnessDialogTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void savedOverrideAndAutomaticResetPersistAndNotifyPlayback() throws Exception {
        Fixture fixture = fixture();
        AudioLoudnessDialog dialog = fixture.dialog();
        AudioLibraryService library = fixture.library();
        AudioTrack track = fixture.track();
        onFx(() -> {
            TextField value = (TextField) dialog.dialog().getDialogPane().lookup("#audioLoudnessValue");
            value.setText("-12.5");
            ((Button) dialog.dialog().getDialogPane().lookup("#audioLoudnessApply")).fire();
            return null;
        });
        assertTrue(fixture.completed().tryAcquire(10, TimeUnit.SECONDS));
        assertEquals(-12.5, track.getGainOverrideDb());
        assertEquals(-12.5, new AudioLibraryService(library.root()).track(track.getId())
                .orElseThrow().getGainOverrideDb());
        assertEquals(1, fixture.changes().get());
        onFx(() -> {
            ((Button) dialog.dialog().getDialogPane().lookup("#audioLoudnessAutomatic")).fire();
            return null;
        });
        assertTrue(fixture.completed().tryAcquire(10, TimeUnit.SECONDS));
        assertNull(track.getGainOverrideDb());
        assertEquals(track.getAutoGainDb(), track.effectiveGainDb());
        assertEquals(2, fixture.changes().get());
        assertNull(new AudioLibraryService(library.root()).track(track.getId())
                .orElseThrow().getGainOverrideDb());
    }

    @Test
    void invalidAndOutOfRangeInputDoesNotChangeTheSavedGain() throws Exception {
        Fixture fixture = fixture();
        onFx(() -> {
            AudioLoudnessDialog dialog = fixture.dialog();
            TextField value = (TextField) dialog.dialog().getDialogPane().lookup("#audioLoudnessValue");
            Button apply = (Button) dialog.dialog().getDialogPane().lookup("#audioLoudnessApply");
            for (String invalid : new String[]{"", "NaN", "Infinity", "-61", "25"}) {
                value.setText(invalid);
                apply.fire();
                assertNull(fixture.track().getGainOverrideDb());
            }

            assertEquals(0, fixture.changes().get());
            assertFalse(((Button) dialog.dialog().getDialogPane().lookup("#audioLoudnessAnalyze")).isManaged());
            return null;
        });
    }

    @Test
    void libraryExposesGainColumnAndSingleFileLoudnessActions() throws Exception {
        Path file = dir.resolve("music.wav");
        TestAudioFiles.writeWav(file, 500, 44100, 0.05);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        library.importFile(file, AudioKind.MUSIC, null);
        library.importFile(file, AudioKind.MUSIC, null);
        FutureTask<Void> task = new FutureTask<>(() -> {
            AudioEngine engine = new AudioEngine(library, new FakeAudioOutput());
            AudioLibraryWindow.show(null, library, engine, new AppSettings(dir.resolve("settings.ini")), () -> { });
            Stage stage = (Stage) Window.getWindows().stream()
                    .filter(window -> window instanceof Stage s && s.getTitle().equals("Audio library"))
                    .findFirst().orElseThrow();
            try {
                TableView<?> table = (TableView<?>) stage.getScene().lookup(".table-view");
                Button loudness = (Button) stage.getScene().lookup("#audioLibraryLoudness");
                assertTrue(table.getColumns().stream().anyMatch(column -> column.getText().equals("Gain")));
                assertTrue(table.getContextMenu().getItems().stream()
                        .anyMatch(item -> "Loudness...".equals(item.getText())));
                assertTrue(loudness.isDisabled());
                table.getSelectionModel().select(0);
                assertFalse(loudness.isDisabled());
                table.getSelectionModel().select(1);
                assertTrue(loudness.isDisabled());
            } finally {
                stage.close();
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    void manualBoostPastPeakHeadroomPreparesLimiterAndSavesInBackground() throws Exception {
        Fixture fixture = fixture(1.0);
        assertTrue(fixture.track().maximumGainDb() < 0);
        Path originalPlayback = fixture.library().playbackFileOf(fixture.track());
        onFx(() -> {
            TextField value = (TextField) fixture.dialog().dialog().getDialogPane().lookup("#audioLoudnessValue");
            value.setText("6");
            Button apply = (Button) fixture.dialog().dialog().getDialogPane().lookup("#audioLoudnessApply");
            apply.fire();
            assertTrue(apply.isDisabled(), "processing must be asynchronous and prevent overlapping changes");
            return null;
        });
        assertTrue(fixture.completed().tryAcquire(10, TimeUnit.SECONDS));
        assertEquals(6.0, fixture.track().getGainOverrideDb());
        assertFalse(originalPlayback.equals(fixture.library().playbackFileOf(fixture.track())));
        onFx(() -> {
            assertFalse(((Button) fixture.dialog().dialog().getDialogPane().lookup("#audioLoudnessApply"))
                    .isDisabled());
            return null;
        });
    }

    private Fixture fixture() throws Exception {
        return fixture(0.01);
    }

    private Fixture fixture(double amplitude) throws Exception {
        Path file = dir.resolve("quiet.wav");
        TestAudioFiles.writeWav(file, 1000, 44100, amplitude);
        AudioLibraryService library = new AudioLibraryService(dir.resolve("audio"));
        AudioTrack track = library.importFile(file, AudioKind.EFFECT, null);
        return onFx(() -> {
            AtomicInteger changes = new AtomicInteger();
            Semaphore completed = new Semaphore(0);
            AudioLoudnessDialog dialog = new AudioLoudnessDialog(null, library, track,
                    () -> {
                        changes.incrementAndGet();
                        completed.release();
                    }, () -> { });
            return new Fixture(dialog, library, track, changes, completed);
        });
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private record Fixture(AudioLoudnessDialog dialog, AudioLibraryService library, AudioTrack track,
                           AtomicInteger changes, Semaphore completed) {
    }
}
