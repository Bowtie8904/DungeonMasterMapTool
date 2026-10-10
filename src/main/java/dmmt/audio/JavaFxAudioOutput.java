package dmmt.audio;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * {@link AudioOutput} on top of JavaFX Media. Decoding and mixing happen in the platform's native media pipeline on
 * its own threads, so several simultaneous streams cost almost no JavaFX frame time (3.35.7).
 */
public final class JavaFxAudioOutput implements AudioOutput {
    private final Set<MediaVoice> voices = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public synchronized Voice open(Path file, boolean loop) {
        try {
            Media media = new Media(file.toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            player.setCycleCount(loop ? MediaPlayer.INDEFINITE : 1);
            player.setVolume(0);
            // A failed stream must not keep the engine waiting for a track that will never end.
            player.setOnError(() -> System.err.println("Audio error for " + file.getFileName() + ": " + player.getError()));
            MediaVoice voice = new MediaVoice(player);
            voices.add(voice);
            return voice;
        } catch (RuntimeException e) {
            System.err.println("Could not open audio file " + file + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized void close() {
        for (MediaVoice voice : Set.copyOf(voices)) {
            voice.dispose();
        }
        voices.clear();
    }

    private final class MediaVoice implements Voice {
        private final MediaPlayer player;
        private boolean disposed;
        private boolean playing;
        /** A seek that arrived before the player was initialised; JavaFX would silently drop it. */
        private double pendingSeekMs = -1;
        private boolean playWhenReady;

        private MediaVoice(MediaPlayer player) {
            this.player = player;
        }

        @Override
        public void play() {
            if (!disposed) {
                playing = true;
                if (pendingSeekMs >= 0) {
                    playWhenReady = true;
                    return;
                }
                player.play();
            }
        }

        @Override
        public void pause() {
            if (!disposed) {
                playing = false;
                playWhenReady = false;
                player.pause();
            }
        }

        @Override
        public void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            playing = false;
            try {
                player.stop();
                player.dispose();
            } catch (RuntimeException e) {
                // the player is being torn down anyway
            }
            synchronized (JavaFxAudioOutput.this) {
                voices.remove(this);
            }
        }

        @Override
        public void setVolume(double volume) {
            if (!disposed) {
                player.setVolume(Math.max(0, Math.min(1, volume)));
            }
        }

        @Override
        public double positionMs() {
            if (disposed) {
                return 0;
            }
            Duration time = player.getCurrentTime();
            return time == null || time.isUnknown() ? 0 : time.toMillis();
        }

        @Override
        public double durationMs() {
            if (disposed) {
                return 0;
            }
            Duration total = player.getMedia().getDuration();
            return total == null || total.isUnknown() ? 0 : total.toMillis();
        }

        @Override
        public boolean isReady() {
            return !disposed && player.getError() == null && pendingSeekMs < 0
                    && switch (player.getStatus()) {
                case READY, PLAYING, PAUSED, STOPPED -> true;
                default -> false;
            };
        }

        @Override
        public void setOnEnd(Runnable action) {
            if (!disposed) {
                player.setOnEndOfMedia(() -> {
                    if (playing && action != null) {
                        action.run();
                    }
                });
            }
        }

        @Override
        public void seek(double millis) {
            if (disposed) {
                return;
            }
            double target = Math.max(0, millis);
            if (player.getStatus() == MediaPlayer.Status.UNKNOWN) {
                // A seek on an uninitialised player is ignored by JavaFX, so it is replayed once the media is ready.
                pendingSeekMs = target;
                player.setOnReady(this::applyPendingSeek);
                return;
            }
            pendingSeekMs = -1;
            player.seek(Duration.millis(target));
        }

        private void applyPendingSeek() {
            if (disposed || pendingSeekMs < 0) {
                return;
            }
            double target = pendingSeekMs;
            pendingSeekMs = -1;
            player.seek(Duration.millis(target));
            if (playWhenReady) {
                playWhenReady = false;
                if (playing) {
                    player.play();
                }
            }
        }
    }
}
