package dmmt.audio;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** An {@link AudioOutput} that records what the engine does, so playback logic can be tested without sound. */
public final class FakeAudioOutput implements AudioOutput {
    final List<FakeVoice> opened = new ArrayList<>();
    boolean readyOnOpen = true;

    @Override
    public Voice open(Path file, boolean loop) {
        FakeVoice voice = new FakeVoice(file, loop);
        voice.ready = readyOnOpen;
        opened.add(voice);
        return voice;
    }

    /** The voices that have not been disposed yet. */
    List<FakeVoice> live() {
        return opened.stream().filter(voice -> !voice.disposed).toList();
    }

    List<FakeVoice> playing() {
        return live().stream().filter(voice -> voice.playing).toList();
    }

    FakeVoice last() {
        return opened.isEmpty() ? null : opened.get(opened.size() - 1);
    }

    public static final class FakeVoice implements Voice {
        final Path file;
        final boolean loop;
        boolean playing;
        boolean disposed;
        boolean ready = true;
        double volume;
        double position;
        double duration = 60_000;
        private Runnable onEnd;

        private FakeVoice(Path file, boolean loop) {
            this.file = file;
            this.loop = loop;
        }

        String name() {
            return file.getFileName().toString();
        }

        /** Lets the test pretend that the track has been running for a while. */
        void advanceTo(double millis) {
            position = millis;
        }

        void reachEnd() {
            position = duration;
            if (onEnd != null) {
                onEnd.run();
            }
        }

        @Override
        public void play() {
            playing = true;
        }

        @Override
        public void pause() {
            playing = false;
        }

        @Override
        public void dispose() {
            playing = false;
            disposed = true;
        }

        @Override
        public void setVolume(double volume) {
            this.volume = volume;
        }

        @Override
        public double positionMs() {
            return position;
        }

        @Override
        public double durationMs() {
            return duration;
        }

        @Override
        public boolean isReady() {
            return ready && !disposed;
        }

        @Override
        public void setOnEnd(Runnable action) {
            this.onEnd = action;
        }

        @Override
        public void seek(double millis) {
            position = millis;
        }
    }
}
