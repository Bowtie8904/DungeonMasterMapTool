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
    void theCacheRoundTripsAndIsRejectedForOtherBucketCounts() {
        WaveformPeaks peaks = peaks(new int[][]{{30, 50}});
        Path cache = dir.resolve("peaks.bin");

        peaks.writeCache(cache);

        WaveformPeaks loaded = WaveformPeaks.readCache(cache, 100);
        assertNotNull(loaded);
        assertEquals(peaks.durationMs(), loaded.durationMs());
        assertEquals(peaks.buckets(), loaded.buckets());
        assertEquals(peaks.amplitude(40), loaded.amplitude(40), 0.001);
        assertEquals(peaks.amplitude(10), loaded.amplitude(10), 0.001);
        assertNull(WaveformPeaks.readCache(cache, 200), "a cache for another resolution must be ignored");
        assertNull(WaveformPeaks.readCache(dir.resolve("missing.bin"), 100));
    }
}
