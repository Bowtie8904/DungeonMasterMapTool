package dmmt.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Minimum/maximum sample value per time bucket of an audio file - the data behind the waveform in the cut window
 * (3.35.3). Computing peaks decodes the whole file once, which is why the result is cached on disk and why callers
 * must do it on a background thread.
 */
public final class WaveformPeaks {
    private static final String MAGIC = "DMPK1";

    private final float[] minimums;
    private final float[] maximums;
    private final long durationMs;

    private WaveformPeaks(float[] minimums, float[] maximums, long durationMs) {
        this.minimums = minimums;
        this.maximums = maximums;
        this.durationMs = durationMs;
    }

    public int buckets() {
        return minimums.length;
    }

    public long durationMs() {
        return durationMs;
    }

    /** Lowest sample value of a bucket, -1 to 0. */
    public float minimum(int bucket) {
        return minimums[Math.max(0, Math.min(minimums.length - 1, bucket))];
    }

    /** Highest sample value of a bucket, 0 to 1. */
    public float maximum(int bucket) {
        return maximums[Math.max(0, Math.min(maximums.length - 1, bucket))];
    }

    /** Loudest absolute value of a bucket, 0 to 1. */
    public float amplitude(int bucket) {
        return Math.max(Math.abs(minimum(bucket)), Math.abs(maximum(bucket)));
    }

    public long bucketStartMs(int bucket) {
        return buckets() == 0 ? 0 : Math.round((double) bucket / buckets() * durationMs);
    }

    public int bucketAt(long millis) {
        if (durationMs <= 0) {
            return 0;
        }
        int bucket = (int) Math.floor(millis / (double) durationMs * buckets());
        return Math.max(0, Math.min(buckets() - 1, bucket));
    }

    // ---- Analysis ----

    /**
     * Decodes {@code file} and returns {@code buckets} peak pairs. {@code progress} (optional) is called with 0..1
     * from the decoding thread.
     */
    public static WaveformPeaks analyze(Path file, int buckets, DoubleConsumer progress) throws IOException {
        int count = Math.max(16, buckets);
        long duration = Math.max(1, AudioFormats.durationMs(file));
        float[] minimums = new float[count];
        float[] maximums = new float[count];
        Accumulator accumulator = new Accumulator(minimums, maximums, duration, progress);
        if (AudioFormats.isMp3(file)) {
            decodeMp3(file, accumulator);
        } else {
            decodeWav(file, accumulator);
        }
        if (progress != null) {
            progress.accept(1);
        }
        return new WaveformPeaks(minimums, maximums, duration);
    }

    /** Collects peaks while samples arrive, mapping sample time to buckets. */
    private static final class Accumulator {
        private final float[] minimums;
        private final float[] maximums;
        private final long durationMs;
        private final DoubleConsumer progress;
        private long frames;
        private int lastReported = -1;

        Accumulator(float[] minimums, float[] maximums, long durationMs, DoubleConsumer progress) {
            this.minimums = minimums;
            this.maximums = maximums;
            this.durationMs = durationMs;
            this.progress = progress;
        }

        /** Adds one sample frame (already mixed down to one value) recorded at {@code sampleRate}. */
        void add(float value, int sampleRate) {
            double millis = sampleRate <= 0 ? 0 : frames * 1000.0 / sampleRate;
            int bucket = (int) (millis / durationMs * minimums.length);
            bucket = Math.max(0, Math.min(minimums.length - 1, bucket));
            if (value < minimums[bucket]) {
                minimums[bucket] = value;
            }
            if (value > maximums[bucket]) {
                maximums[bucket] = value;
            }
            frames++;
            if (progress != null && bucket != lastReported && bucket % 32 == 0) {
                lastReported = bucket;
                progress.accept(Math.min(1, bucket / (double) minimums.length));
            }
        }
    }

    private static void decodeMp3(Path file, Accumulator accumulator) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] buffer = output.getBuffer();
                int length = output.getBufferLength();
                int channels = Math.max(1, output.getChannelCount());
                int rate = output.getSampleFrequency();
                for (int i = 0; i + channels <= length; i += channels) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += buffer[i + c];
                    }
                    accumulator.add(sum / (float) channels / 32768f, rate);
                }
                bitstream.closeFrame();
            }
            bitstream.close();
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("Could not decode \"" + file.getFileName() + "\": " + e.getMessage(), e);
        }
    }

    private static void decodeWav(Path file, Accumulator accumulator) throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    raw.getFormat().getSampleRate(), 16, raw.getFormat().getChannels(),
                    raw.getFormat().getChannels() * 2, raw.getFormat().getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.isConversionSupported(target, raw.getFormat())
                    ? AudioSystem.getAudioInputStream(target, raw)
                    : raw) {
                AudioFormat format = pcm.getFormat();
                int channels = Math.max(1, format.getChannels());
                int rate = (int) format.getSampleRate();
                boolean bigEndian = format.isBigEndian();
                byte[] buffer = new byte[1 << 16];
                int carry = 0;
                byte[] leftovers = new byte[channels * 2];
                int read;
                while ((read = pcm.read(buffer)) > 0) {
                    int at = 0;
                    while (carry > 0 && at < read) {
                        leftovers[carry++] = buffer[at++];
                        if (carry == leftovers.length) {
                            accumulator.add(frameValue(leftovers, 0, channels, bigEndian), rate);
                            carry = 0;
                        }
                    }
                    int frameBytes = channels * 2;
                    int usable = (read - at) / frameBytes * frameBytes;
                    for (int i = at; i < at + usable; i += frameBytes) {
                        accumulator.add(frameValue(buffer, i, channels, bigEndian), rate);
                    }
                    for (int i = at + usable; i < read; i++) {
                        leftovers[carry++] = buffer[i];
                    }
                }
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Could not read \"" + file.getFileName() + "\": " + e.getMessage(), e);
        }
    }

    private static float frameValue(byte[] data, int offset, int channels, boolean bigEndian) {
        int sum = 0;
        for (int c = 0; c < channels; c++) {
            int at = offset + c * 2;
            int sample = bigEndian
                    ? (data[at] << 8) | (data[at + 1] & 0xFF)
                    : (data[at + 1] << 8) | (data[at] & 0xFF);
            sum += (short) sample;
        }
        return sum / (float) channels / 32768f;
    }

    // ---- Silence detection ----

    /** A time range of the analysed file. */
    public record Range(long startMs, long endMs) {
        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    /**
     * Ranges that stay quieter than {@code thresholdDb} (e.g. -45) for at least {@code minSeconds}. These are the
     * gaps between the songs of a long recording.
     */
    public List<Range> silentRanges(double thresholdDb, double minSeconds) {
        double threshold = Math.pow(10, thresholdDb / 20);
        long minMillis = Math.round(Math.max(0, minSeconds) * 1000);
        List<Range> result = new ArrayList<>();
        int start = -1;
        for (int bucket = 0; bucket <= buckets(); bucket++) {
            boolean quiet = bucket < buckets() && amplitude(bucket) <= threshold;
            if (quiet && start < 0) {
                start = bucket;
            } else if (!quiet && start >= 0) {
                long from = bucketStartMs(start);
                long to = bucketStartMs(bucket);
                if (to - from >= minMillis) {
                    result.add(new Range(from, to));
                }
                start = -1;
            }
        }
        return result;
    }

    /**
     * The songs of a long recording: everything between the silent ranges, with the silence trimmed off. Ranges
     * shorter than {@code minTrackSeconds} are dropped so a short applause or click does not become a clip.
     */
    public List<Range> detectTracks(double thresholdDb, double minSilenceSeconds, double minTrackSeconds) {
        List<Range> silences = silentRanges(thresholdDb, minSilenceSeconds);
        List<Range> tracks = new ArrayList<>();
        long cursor = 0;
        long minTrack = Math.round(Math.max(0, minTrackSeconds) * 1000);
        for (Range silence : silences) {
            if (silence.startMs() - cursor >= minTrack) {
                tracks.add(new Range(cursor, silence.startMs()));
            }
            cursor = silence.endMs();
        }
        if (durationMs - cursor >= minTrack) {
            tracks.add(new Range(cursor, durationMs));
        }
        return tracks;
    }

    // ---- Disk cache ----

    /** Reads cached peaks, or {@code null} when there is no usable cache for this bucket count. */
    public static WaveformPeaks readCache(Path cacheFile, int buckets) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            byte[] magic = in.readNBytes(MAGIC.length());
            if (!MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
                return null;
            }
            int count = in.readInt();
            long duration = in.readLong();
            if (count != buckets || count <= 0 || count > 1 << 22) {
                return null;
            }
            float[] minimums = new float[count];
            float[] maximums = new float[count];
            for (int i = 0; i < count; i++) {
                minimums[i] = in.readShort() / 32768f;
                maximums[i] = in.readShort() / 32768f;
            }
            return new WaveformPeaks(minimums, maximums, duration);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the peaks next to the other cached peaks; failures are ignored because this is derived data. */
    public void writeCache(Path cacheFile) {
        Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(cacheFile.getParent());
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.write(MAGIC.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                out.writeInt(buckets());
                out.writeLong(durationMs);
                for (int i = 0; i < buckets(); i++) {
                    out.writeShort(Math.round(minimum(i) * 32767));
                    out.writeShort(Math.round(maximum(i) * 32767));
                }
            }
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // derived data, nothing to recover
            }
        }
    }

    /** Peaks built directly from values; for tests and for previews of ranges. */
    static WaveformPeaks of(float[] minimums, float[] maximums, long durationMs) {
        return new WaveformPeaks(minimums, maximums, durationMs);
    }
}
