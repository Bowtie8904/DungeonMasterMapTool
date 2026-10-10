package dmmt.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoudnessSinglePassTest {
    @TempDir Path dir;

    @Test
    void bothGatesMatchLegacyTwoPassReferenceIncludingShortSilentAndTrailingBlocks() throws Exception {
        for (int frames : new int[]{0, 80, 3200, 3333, 88000}) {
            short[] samples = new short[frames];
            ByteBuffer pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                double amplitude = i < frames / 3 ? 0 : i < frames * 2 / 3 ? 0.001 : 0.3;
                samples[i] = (short) Math.round(Math.sin(2 * Math.PI * 220 * i / 8000) * amplitude * 32767);
                pcm.putShort(samples[i]);
            }
            Path source = dir.resolve(frames + ".wav");
            TestAudioFiles.writeWav(source, pcm.array(), 8000);
            Double expected = reference(samples, 8000);
            LoudnessAnalyzer.Measurement actual = LoudnessAnalyzer.analyze(source);
            if (expected == null) {
                assertNull(actual.loudnessLufs());
            } else {
                assertEquals(expected, actual.loudnessLufs(), 1e-12);
                double peak = 0;
                for (short sample : samples) {
                    peak = Math.max(peak, Math.abs(sample / 32768f));
                }
                double peakDb = 20 * Math.log10(peak);
                double maximum = Math.min(24, -1 - peakDb);
                assertEquals(peakDb, actual.samplePeakDbfs(), 1e-12);
                assertEquals(maximum, actual.maximumGainDb(), 1e-12);
                assertEquals(Math.max(-60, Math.min(maximum, -23 - expected)), actual.autoGainDb(), 1e-12);
            }
        }
    }

    @Test
    void sourceIsNeverReopenedForRelativeGateAndStatisticsAreCleaned() throws Exception {
        Path source = dir.resolve("once.wav");
        TestAudioFiles.writeWav(source, 1100, 8000, 0.05);
        LoudnessAnalyzer.Measurement measured = LoudnessAnalyzer.analyze(source, progress -> {
            if (progress == 1) {
                try {
                    Files.delete(source);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }, null, 50);
        assertNotNull(measured.loudnessLufs());
        assertFalse(Files.exists(source));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "bounded statistics spool must be cleaned after analysis");
        }
    }

    @Test
    void combinedDecodeProducesByteIdenticalWaveformAndTimbreCaches() throws Exception {
        for (boolean stereo : new boolean[]{false, true}) {
            Path source = dir.resolve(stereo ? "stereo.wav" : "mono.wav");
            if (stereo) {
                short[] left = new short[12345];
                short[] right = new short[left.length];
                for (int i = 0; i < left.length; i++) {
                    left[i] = (short) Math.round(Math.sin(i * 0.31) * 25000);
                    right[i] = (short) Math.round(Math.cos(i * 0.13) * 14000);
                }
                TestAudioFiles.writeStereoWav(source, left, right, 8000);
            } else {
                TestAudioFiles.writeWav(source, 1789, 8000, 0.3);
            }
            Path legacy = dir.resolve(stereo + "-legacy.peaks");
            Path combined = dir.resolve(stereo + "-combined.peaks");
            WaveformPeaks.analyze(source, 50, null).writeCache(legacy);
            LoudnessAnalyzer.analyze(source, null, combined, 50);
            assertArrayEquals(Files.readAllBytes(legacy), Files.readAllBytes(combined));
        }
    }

    @Test
    void mp3SilenceProducesCompatibleWaveformAndTimbreCacheInCombinedDecode() throws Exception {
        Path source = dir.resolve("silence.mp3");
        byte[] mp3 = new byte[TestAudioFiles.MP3_FRAME_BYTES * 100];
        for (int offset = 0; offset < mp3.length; offset += TestAudioFiles.MP3_FRAME_BYTES) {
            mp3[offset] = (byte) 0xff;
            mp3[offset + 1] = (byte) 0xfb;
            mp3[offset + 2] = (byte) 0x90;
        }
        Files.write(source, mp3);
        Path legacy = dir.resolve("mp3-legacy.peaks");
        Path combined = dir.resolve("mp3-combined.peaks");
        WaveformPeaks.analyze(source, 50, null).writeCache(legacy);
        LoudnessAnalyzer.Measurement measurement = LoudnessAnalyzer.analyze(source, null, combined, 50);
        assertEquals(2, measurement.channels());
        assertNull(measurement.loudnessLufs());
        assertArrayEquals(Files.readAllBytes(legacy), Files.readAllBytes(combined));
    }

    /** Independent direct-window reference: the previous analyzer filtered all PCM again for each gate. */
    private static Double reference(short[] samples, int rate) {
        Filter shelf = Filter.shelf(rate);
        Filter highPass = Filter.highPass(rate);
        double[] energy = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            double weighted = highPass.process(shelf.process(samples[i] / 32768f));
            energy[i] = weighted * weighted;
        }
        int window = (int) Math.round(rate * 0.4);
        int hop = (int) Math.round(rate * 0.1);
        List<Double> blocks = new ArrayList<>();
        if (energy.length > 0 && energy.length < window) {
            blocks.add(mean(energy, 0, energy.length));
        } else {
            int last = 0;
            for (int end = window; end <= energy.length; end += hop) {
                blocks.add(mean(energy, end - window, end));
                last = end;
            }
            if (energy.length >= window && last < energy.length) {
                blocks.add(mean(energy, energy.length - window, energy.length));
            }
        }
        double absolute = Math.pow(10, (-70 + 0.691) / 10);
        double first = blocks.stream().filter(value -> value >= absolute).mapToDouble(Double::doubleValue)
                .average().orElse(0);
        double second = blocks.stream().filter(value -> value >= Math.max(absolute, first * 0.1))
                .mapToDouble(Double::doubleValue).average().orElse(0);
        return second == 0 ? null : -0.691 + 10 * Math.log10(second);
    }

    private static double mean(double[] values, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += values[i];
        }
        return sum / (end - start);
    }

    private static final class Filter {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;
        Filter(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2;
        }
        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = input; y2 = y1; y1 = output;
            return output;
        }
        static Filter shelf(int rate) {
            double k = Math.tan(Math.PI * 1681.974450955533 / rate);
            double q = 0.7071752369554196;
            double vh = Math.pow(10, 3.999843853973347 / 20);
            double vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1 + k / q + k * k;
            return new Filter((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
        }
        static Filter highPass(int rate) {
            double k = Math.tan(Math.PI * 38.13547087602444 / rate);
            double q = 0.5003270373238773;
            double a0 = 1 + k / q + k * k;
            return new Filter(1 / a0, -2 / a0, 1 / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
        }
    }
}
