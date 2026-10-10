package dmmt.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waveform peaks, song detection and the peak cache (3.35.3). */
class WaveformPeaksTest {
    @TempDir
    Path dir;

    /** 100 buckets = 10 seconds; loud unless the bucket is inside one of the given silent bucket ranges. */
    private WaveformPeaks peaks(int[][] silentRanges) {
        float[] minimums = new float[100];
        float[] maximums = new float[100];
        for (int i = 0; i < 100; i++) {
            boolean silent = false;
            for (int[] range : silentRanges) {
                silent |= i >= range[0] && i < range[1];
            }
            minimums[i] = silent ? -0.0001f : -0.8f;
            maximums[i] = silent ? 0.0001f : 0.8f;
        }
        return WaveformPeaks.of(minimums, maximums, 10_000);
    }

    @Test
    void silentRangesLongerThanTheMinimumAreFound() {
        WaveformPeaks peaks = peaks(new int[][]{{30, 50}, {70, 72}});

        List<WaveformPeaks.Range> silences = peaks.silentRanges(-45, 1.0);

        assertEquals(1, silences.size(), "the 0.2 s gap is too short");
        assertEquals(3_000, silences.get(0).startMs());
        assertEquals(5_000, silences.get(0).endMs());
    }

    @Test
    void songsAreTheRangesBetweenTheSilences() {
        WaveformPeaks peaks = peaks(new int[][]{{30, 50}});

        List<WaveformPeaks.Range> tracks = peaks.detectTracks(-45, 1.0, 1.0);

        assertEquals(List.of(new WaveformPeaks.Range(0, 3_000), new WaveformPeaks.Range(5_000, 10_000)), tracks);
    }

    @Test
    void shortPiecesAreDropped() {
        WaveformPeaks peaks = peaks(new int[][]{{10, 30}});

        List<WaveformPeaks.Range> tracks = peaks.detectTracks(-45, 1.0, 2.0);

        assertEquals(List.of(new WaveformPeaks.Range(3_000, 10_000)), tracks,
                "the 1 s intro before the gap is shorter than the minimum");
    }

    @Test
    void anEntirelyQuietFileHasNoSongs() {
        assertTrue(peaks(new int[][]{{0, 100}}).detectTracks(-45, 1.0, 1.0).isEmpty());
    }

    @Test
    void analysingAWavFileFindsTheLoudAndTheSilentPart() throws IOException {
        int sampleRate = 8000;
        byte[] pcm = new byte[sampleRate * 2 * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleRate * 2; i++) {
            // First second loud, second second silent.
            double value = i < sampleRate ? Math.sin(2 * Math.PI * 220 * i / sampleRate) * 0.9 : 0;
            buffer.putShort((short) Math.round(value * Short.MAX_VALUE));
        }
        Path file = dir.resolve("half.wav");
        TestAudioFiles.writeWav(file, pcm, sampleRate);
        DoubleAdder lastProgress = new DoubleAdder();

        WaveformPeaks peaks = WaveformPeaks.analyze(file, 100, value -> {
            lastProgress.reset();
            lastProgress.add(value);
        });

        assertEquals(2_000, peaks.durationMs());
        assertTrue(peaks.amplitude(peaks.bucketAt(500)) > 0.5, "the first second must be loud");
        assertTrue(peaks.amplitude(peaks.bucketAt(1_500)) < 0.01, "the second second must be silent");
        assertEquals(1.0, lastProgress.sum(), 0.0001);
        assertEquals(List.of(new WaveformPeaks.Range(0, 1_000)), peaks.detectTracks(-45, 0.5, 0.5));
    }

    @Test
    void theCacheRoundTripsAndIsRejectedForOtherFrameLengths() {
        WaveformPeaks peaks = peaks(new int[][]{{30, 50}});
        Path cache = dir.resolve("peaks.bin");

        peaks.writeCache(cache);

        WaveformPeaks loaded = WaveformPeaks.readCache(cache, 100);
        assertNotNull(loaded);
        assertEquals(peaks.durationMs(), loaded.durationMs());
        assertEquals(peaks.buckets(), loaded.buckets());
        assertEquals(peaks.amplitude(40), loaded.amplitude(40), 0.001);
        assertEquals(peaks.amplitude(10), loaded.amplitude(10), 0.001);
        assertEquals(peaks.rms(10), loaded.rms(10), 0.001);
        assertNull(WaveformPeaks.readCache(cache, 200), "a cache for another frame length must be ignored");
        assertNull(WaveformPeaks.readCache(dir.resolve("missing.bin"), 100));
    }

    // ---- Automatic detection ----

    /** 100 ms frames of the given levels, repeated {@code repeat} times each. */
    private WaveformPeaks levels(float[] levels) {
        float[] minimums = new float[levels.length];
        float[] maximums = new float[levels.length];
        float[] rms = new float[levels.length];
        for (int i = 0; i < levels.length; i++) {
            minimums[i] = -levels[i];
            maximums[i] = levels[i];
            rms[i] = levels[i];
        }
        return WaveformPeaks.of(minimums, maximums, rms, levels.length * 100L);
    }

    private static float[] fill(int frames, float loud, int[][] quietRanges, float quiet) {
        float[] values = new float[frames];
        for (int i = 0; i < frames; i++) {
            boolean isQuiet = false;
            for (int[] range : quietRanges) {
                isQuiet |= i >= range[0] && i < range[1];
            }
            values[i] = isQuiet ? quiet : loud;
        }
        return values;
    }

    private static WaveformPeaks.DetectionOptions auto(double minGapDropDb) {
        return new WaveformPeaks.DetectionOptions(true, -45, 30, 1.0, 2.0, minGapDropDb, false, 24, 4, false);
    }

    @Test
    void aQuietRecordingStillSplitsBecauseTheThresholdFollowsTheMusic() {
        // Music at -50 dBFS, gaps at -90 dBFS: the fixed -45 dB threshold would call the whole file silent.
        WaveformPeaks peaks = levels(fill(600, 0.00316f, new int[][]{{200, 220}, {400, 420}}, 0.00003f));

        WaveformPeaks.Detection detection = peaks.detect(auto(18));

        assertEquals(List.of(new WaveformPeaks.Range(0, 20_000), new WaveformPeaks.Range(22_000, 40_000),
                new WaveformPeaks.Range(42_000, 60_000)), detection.tracks());
        assertTrue(detection.thresholdDb() < -50, "the threshold must follow the quiet music: " + detection.thresholdDb());
    }

    @Test
    void quietPassagesInsideOnePieceDoNotSplitIt() {
        // A long piece whose middle drops to -62 dB for 4 s: 12 dB below the music, far from a real gap.
        float[] values = fill(600, 0.05f, new int[][]{{200, 240}}, 0.0125f);

        List<WaveformPeaks.Range> tracks = levels(values).detect(auto(18)).tracks();

        assertEquals(List.of(new WaveformPeaks.Range(0, 60_000)), tracks, "a dip of 12 dB is not a song border");
    }

    @Test
    void aClickInsideAGapDoesNotBreakItApart() {
        float[] values = fill(600, 0.5f, new int[][]{{200, 240}}, 0.0005f);
        values[220] = 0.5f; // a single 100 ms click in the middle of the 4 s gap

        List<WaveformPeaks.Range> tracks = levels(values).detect(auto(18)).tracks();

        assertEquals(List.of(new WaveformPeaks.Range(0, 20_000), new WaveformPeaks.Range(24_000, 60_000)), tracks,
                "the click must be merged into the gap instead of creating a 2 s fragment");
    }

    @Test
    void aContinuousPieceWithoutGapsStaysOneTrack() {
        float[] values = new float[600];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (0.2 + 0.1 * Math.sin(i / 7.0));
        }

        List<WaveformPeaks.Range> tracks = levels(values).detect(auto(18)).tracks();

        assertEquals(List.of(new WaveformPeaks.Range(0, 60_000)), tracks);
    }

    @Test
    void peakBasedNoiseInAGapNoLongerHidesIt() {
        // The gap carries quiet hiss whose peaks reach -20 dBFS, but its RMS stays low.
        float[] values = fill(600, 0.5f, new int[][]{{200, 240}}, 0.002f);
        float[] minimums = new float[values.length];
        float[] maximums = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            minimums[i] = -Math.max(values[i], 0.1f);
            maximums[i] = Math.max(values[i], 0.1f);
        }
        WaveformPeaks peaks = WaveformPeaks.of(minimums, maximums, values, values.length * 100L);

        assertEquals(List.of(new WaveformPeaks.Range(0, 20_000), new WaveformPeaks.Range(24_000, 60_000)),
                peaks.detect(auto(18)).tracks());
    }

    // ---- Change detection ----

    /** A loudness-independent fingerprint: mean removed and scaled to unit length, like the analysis produces. */
    private static float[] fingerprint(double colour, java.util.Random noise) {
        float[] values = new float[24];
        double mean = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (Math.sin(colour + i * 0.7) + (noise == null ? 0 : noise.nextGaussian() * 0.01));
            mean += values[i];
        }
        mean /= values.length;
        double norm = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] -= (float) mean;
            norm += (double) values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < values.length; i++) {
            values[i] /= (float) norm;
        }
        return values;
    }

    /** Loud audio of {@code seconds} length whose fingerprint colour is chosen per second. */
    private static WaveformPeaks coloured(int seconds, java.util.function.IntToDoubleFunction colour) {
        int frames = seconds * 10;
        float[] minimums = new float[frames];
        float[] maximums = new float[frames];
        float[] rms = new float[frames];
        for (int i = 0; i < frames; i++) {
            minimums[i] = -0.5f;
            maximums[i] = 0.5f;
            rms[i] = 0.5f;
        }
        java.util.Random noise = new java.util.Random(7);
        float[][] features = new float[seconds][];
        for (int i = 0; i < seconds; i++) {
            features[i] = fingerprint(colour.applyAsDouble(i), noise);
        }
        return WaveformPeaks.of(minimums, maximums, rms, features, seconds * 1000L);
    }

    @Test
    void aChangeOfCharacterIsFoundWhereThereIsNoGapAtAll() {
        // Two pieces crossfaded into each other: constant loudness, so silence detection can never split this.
        WaveformPeaks peaks = coloured(240, second -> second < 120 ? 0 : 2.5);

        List<Long> borders = peaks.changeBorders(20, 6, 30);

        assertEquals(1, borders.size(), "exactly one border expected, got " + borders);
        assertEquals(120_000, borders.getFirst(), 2_000);
    }

    @Test
    void uniformAudioHasNoChangeBorders() {
        assertTrue(coloured(240, second -> 1.0).changeBorders(20, 6, 30).isEmpty());
    }

    @Test
    void aPieceLoopedSeveralTimesIsProposedOnlyOnce() {
        // Three identical 60 s pieces, each followed by a 4 s gap, like an hour-long ambience upload.
        int seconds = 192;
        float[] rms = new float[seconds * 10];
        float[] minimums = new float[rms.length];
        float[] maximums = new float[rms.length];
        float[][] features = new float[seconds][];
        for (int second = 0; second < seconds; second++) {
            boolean gap = second % 64 >= 60;
            for (int frame = second * 10; frame < second * 10 + 10; frame++) {
                rms[frame] = gap ? 0.0002f : 0.5f;
                minimums[frame] = -rms[frame];
                maximums[frame] = rms[frame];
            }
            features[second] = fingerprint(second % 64 * 0.05, null);
        }
        WaveformPeaks peaks = WaveformPeaks.of(minimums, maximums, rms, features, seconds * 1000L);

        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                true, -45, 30, 1.0, 20, 18, true, 20, 6, true));

        assertEquals(List.of(new WaveformPeaks.Range(0, 60_000)), detection.tracks(),
                "the second and third pass of the same piece must be dropped");
        assertEquals(2, detection.repeatsRemoved());
    }

    /** Colours that repeat every {@code period} seconds and are unrelated within it, the way looped music behaves. */
    /** Like {@link #coloured(int, java.util.function.IntToDoubleFunction)}, but silent wherever {@code quiet} holds. */
    private static WaveformPeaks coloured(int seconds, java.util.function.IntToDoubleFunction colour,
            java.util.function.IntPredicate quiet) {
        int frames = seconds * 10;
        float[] minimums = new float[frames];
        float[] maximums = new float[frames];
        float[] rms = new float[frames];
        for (int i = 0; i < frames; i++) {
            float level = quiet.test(i / 10) ? 0.0005f : 0.5f;
            minimums[i] = -level;
            maximums[i] = level;
            rms[i] = level;
        }
        java.util.Random noise = new java.util.Random(7);
        float[][] features = new float[seconds][];
        for (int i = 0; i < seconds; i++) {
            features[i] = fingerprint(colour.applyAsDouble(i), noise);
        }
        return WaveformPeaks.of(minimums, maximums, rms, features, seconds * 1000L);
    }

    @Test
    void aLoopedPieceWithQuietPassagesOfItsOwnIsStillProposedOnce() {
        // The piece has a two second rest in the middle, so the silence detection splits every repetition in two and
        // no single proposal spans a whole period. The repetitions after the first must still be dropped, and the
        // kept pass must start at the beginning of the file rather than somewhere inside the first repetition.
        WaveformPeaks peaks = coloured(720, loop(60, 11), second -> second % 60 == 30 || second % 60 == 31);

        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                true, -45, 30, 1.0, 20, 18, true, 20, 6, true));

        assertTrue(detection.loopRepetitions() >= 10,
                "the twelve repetitions must be found, got " + detection.loopRepetitions());
        assertEquals(2, detection.tracks().size(),
                "only the two halves of the first repetition may be proposed: " + detection.tracks());
        assertEquals(0, detection.tracks().getFirst().startMs(),
                "the pass must start at the beginning of the loop: " + detection.tracks());
        assertEquals(60_000, detection.tracks().getLast().endMs(), 5_000,
                "the pass must stop at the first repetition border: " + detection.tracks());
    }

    private static java.util.function.IntToDoubleFunction loop(int period, long seed) {
        java.util.Random random = new java.util.Random(seed);
        double[] pattern = new double[period];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = random.nextDouble() * 4;
        }
        return second -> pattern[second % period];
    }

    @Test
    void aRecordingThatIsOnePieceLoopedIsProposedOnce() {
        // One 40 s piece repeated eight times without a single gap: every border is equally strong, so the novelty
        // peaks cannot see any of them and the whole file would otherwise stay a single proposal.
        WaveformPeaks peaks = coloured(320, loop(40, 11));

        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                true, -45, 30, 1.0, 20, 18, true, 20, 6, true));

        assertTrue(detection.loopRepetitions() >= 7,
                "the eight repetitions must be found, got " + detection.loopRepetitions());
        assertEquals(1, detection.tracks().size(), "only one pass of the piece may be proposed: "
                + detection.tracks());
        assertEquals(0, detection.tracks().getFirst().startMs(),
                "the pass must start at the beginning of the loop, not inside the first repetition: "
                        + detection.tracks());
        assertEquals(40_000, detection.tracks().getFirst().durationMs(), 3_000);
    }

    @Test
    void loopDetectionCanBeTurnedOff() {
        WaveformPeaks peaks = coloured(320, loop(40, 11));

        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                true, -45, 30, 1.0, 20, 18, true, 20, 6, false));

        assertEquals(0, detection.loopRepetitions());
        assertEquals(List.of(new WaveformPeaks.Range(0, 320_000)), detection.tracks());
    }

    @Test
    void aRecordingThatIsNotLoopedHasNoPeriod() {
        assertTrue(coloured(320, loop(320, 3)).loopPeriodSeconds(20).isEmpty(),
                "music that never repeats must not be mistaken for a loop");
    }

    @Test
    void aShortGapBetweenTwoSongsIsFound() {
        // A playlist that cuts from one song to the next with a 300 ms fade, far shorter than minSilenceSeconds.
        float[] values = fill(1800, 0.5f, new int[][]{{900, 903}}, 0.0005f);

        List<WaveformPeaks.Range> tracks = levels(values).detect(auto(18)).tracks();

        assertEquals(List.of(new WaveformPeaks.Range(0, 90_000), new WaveformPeaks.Range(90_300, 180_000)), tracks,
                "an isolated short gap is a song border");
    }

    @Test
    void shortRestsInsidePercussiveMusicDoNotSplitIt() {
        // The same 300 ms gaps, but several of them within a few seconds, the way battle music rests between hits.
        float[] values = fill(1800, 0.5f, new int[][]{{900, 903}, {1000, 1003}, {1100, 1103}}, 0.0005f);

        List<WaveformPeaks.Range> tracks = levels(values).detect(auto(18)).tracks();

        assertEquals(List.of(new WaveformPeaks.Range(0, 180_000)), tracks,
                "short gaps that come in a cluster are rests inside one piece, not song borders");
    }

    @Test
    void changeDetectionCanBeTurnedOff() {        WaveformPeaks peaks = coloured(240, second -> second < 120 ? 0 : 2.5);

        WaveformPeaks.Detection detection = peaks.detect(new WaveformPeaks.DetectionOptions(
                true, -45, 30, 1.0, 20, 18, false, 20, 6, false));

        assertEquals(List.of(new WaveformPeaks.Range(0, 240_000)), detection.tracks());
        assertEquals(0, detection.changeBorders());
    }
}
