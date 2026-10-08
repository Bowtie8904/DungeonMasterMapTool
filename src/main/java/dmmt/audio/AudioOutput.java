package dmmt.audio;

import java.nio.file.Path;

/**
 * The thin layer between {@link AudioEngine} and the actual sound output (3.35.7). Keeping it behind an interface
 * lets the engine's playlist, crossfade and volume logic be unit-tested without sound hardware or a media stack.
 */
public interface AudioOutput {

    /**
     * Prepares a file for playback. Implementations load asynchronously; calling {@link Voice#play()} right away is
     * allowed and starts playback as soon as the file is ready.
     *
     * @param loop seamless endless repeat (sound effects)
     * @return the voice, or {@code null} when the file cannot be opened
     */
    Voice open(Path file, boolean loop);

    /** One playing (or loading) sound. */
    interface Voice {
        void play();

        void pause();

        /** Stops playback and releases the file; the voice cannot be used afterwards. */
        void dispose();

        /** Output volume, 0 to 1, already including all channel and master factors. */
        void setVolume(double volume);

        /** Playback position in milliseconds, 0 while still loading. */
        double positionMs();

        /** Length in milliseconds, 0 while still loading. */
        double durationMs();

        /** Called once when a non-looping voice reaches its end. */
        void setOnEnd(Runnable action);

        /** Jumps to a position in milliseconds; outputs that cannot seek ignore this. */
        default void seek(double millis) {
        }
    }

    /** Releases everything the output holds (called when the application shuts down). */
    default void close() {
    }
}
