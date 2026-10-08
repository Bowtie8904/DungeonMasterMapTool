package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.audio.AudioKind;
import dmmt.audio.AudioLibraryService;
import dmmt.audio.AudioOutput;
import dmmt.audio.TestAudioFiles;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
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
            assertEquals(0.8, first.volume);
            assertEquals(0, next.volume);

            next.position = 325;
            invoke(window, "updatePlayback");
            assertEquals(0.8 * Math.sqrt(0.5), first.volume, 0.0001);
            assertEquals(0.8 * Math.sqrt(0.5), next.volume, 0.0001);
            first.endAt(999);
            assertEquals(1, next.plays, "the outgoing media-end event must not restart the new voice");
            next.position = 450;
            invoke(window, "updatePlayback");
            assertTrue(first.disposed);
            assertEquals(0.8, next.volume, 0.0001);
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
            assertEquals(0.8, next.volume);
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
            assertEquals(0.8, next.volume);
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
            assertEquals(0.8, first.volume);
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

    private interface CrossfadeScenario {
        void run(AudioCutWindow window, PreviewOutput output) throws Exception;
    }

    private static final class PreviewOutput implements AudioOutput {
        private final List<PreviewVoice> voices = new ArrayList<>();

        @Override
        public Voice open(Path file, boolean loop) {
            PreviewVoice voice = new PreviewVoice();
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
