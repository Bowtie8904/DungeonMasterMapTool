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
 * Minimum/maximum sample value, RMS level and a timbre fingerprint per time bucket of an audio file - the data
 * behind the waveform and the song detection of the cut window (3.35.3). Computing peaks decodes the whole file
 * once, which is why the result is cached on disk and why callers must do it on a background thread.
 */
public final class WaveformPeaks {
    private static final String MAGIC = "DMPK3";
    /** Level reported for digital silence, so dB values stay finite. */
    private static final double SILENCE_FLOOR_DB = -100;
    /** A frame leaves silence only this far above the threshold, so borderline frames do not flicker. */
    private static final double HYSTERESIS_DB = 6;
    /** Silences separated by less noise than this are one gap with a click in it. */
    private static final long MERGE_GAP_MS = 300;
    /** How much audio around a gap is inspected to decide whether it really separates two songs. */
    private static final long CONTRAST_WINDOW_MS = 5_000;
    /** Frame level that represents "the music" of a file. */
    private static final double MUSIC_PERCENTILE = 0.90;
    /** The automatic threshold never comes closer than this to the music level. */
    private static final double MIN_HEADROOM_DB = 10;
    /** dB added to the automatic threshold while retrying a file that did not split. */
    private static final double[] RELAXATIONS = {0, 4, 8, 12, 16};

    /** One timbre fingerprint per second of audio; the resolution the change detection works on. */
    static final int FEATURE_MS = 1000;
    /** Samples per spectrum; about 23 ms at 44.1 kHz. */
    private static final int FFT_SIZE = 1024;
    /** Log-spaced frequency bands of a fingerprint. */
    private static final int BANDS = 24;
    private static final double MIN_BAND_HZ = 50;
    private static final double MAX_BAND_HZ = 16_000;
    /** A change point this close to a silent gap is the same border found twice. */
    private static final long CHANGE_NEAR_SILENCE_MS = 5_000;
    /** Shortest gap that can be a song border at all, when nothing else interrupts the music around it. */
    private static final long ISOLATED_GAP_MS = 150;
    /** Music has to run this long either side of a short gap before it is believed to be a song border. */
    private static final long ISOLATION_MS = 60_000;

    /** Fingerprint similarity above which two proposals are the same music played twice. */
    private static final double REPEAT_SIMILARITY = 0.95;

    /** Longest loop the detector looks for, in seconds. */
    private static final int LOOP_MAX_SECONDS = 600;
    /** How far a loop lag has to stand out from the detrended self-similarity to count. */
    private static final double LOOP_Z = 6;
    /** Lags this many seconds either side of a multiple of the period confirm it. */
    private static final int LOOP_TOLERANCE = 2;
    /** Lags over which the self-similarity is detrended; wider than a loop peak, narrower than the drift. */
    private static final int LOOP_TREND_LAGS = 31;
    /** A loop has to fit this often into the file before it is believed. */
    private static final int LOOP_MIN_REPEATS = 3;
    /** Fingerprint similarity a second needs with the one a period later to belong to the looped run. */
    private static final double LOOP_REGION_SIMILARITY = 0.95;

    private final float[] minimums;
    private final float[] maximums;
    private final float[] rms;
    /** Normalised band energies per {@link #FEATURE_MS}; empty when the file was not analysed for changes. */
    private final float[][] features;
    private final long durationMs;
    private final int frameMs;

    private WaveformPeaks(float[] minimums, float[] maximums, float[] rms, float[][] features,
                          long durationMs, int frameMs) {
        this.minimums = minimums;
        this.maximums = maximums;
        this.rms = rms;
        this.features = features;
        this.durationMs = durationMs;
        this.frameMs = frameMs;
    }

    public int buckets() {
        return minimums.length;
    }

    public long durationMs() {
        return durationMs;
    }

    /** Length of one analysed frame in milliseconds. */
    public int frameMs() {
        return frameMs;
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

    /** Root mean square of a bucket, 0 to 1 - the perceived level, unaffected by single clicks. */
    public float rms(int bucket) {
        return rms[Math.max(0, Math.min(rms.length - 1, bucket))];
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
     * Decodes {@code file} into frames of {@code frameMs} milliseconds and returns their peaks and RMS levels.
     * {@code progress} (optional) is called with 0..1 from the decoding thread.
     */
    public static WaveformPeaks analyze(Path file, int frameMs, DoubleConsumer progress) throws IOException {
        int frame = Math.max(1, frameMs);
        long duration = Math.max(1, AudioFormats.durationMs(file));
        int count = (int) Math.max(16, Math.min(1 << 22, (duration + frame - 1) / frame));
        Accumulator accumulator = new Accumulator(count, duration, progress);
        if (AudioFormats.isMp3(file)) {
            decodeMp3(file, accumulator);
        } else {
            decodeWav(file, accumulator);
        }
        if (progress != null) {
            progress.accept(1);
        }
        return accumulator.finish(frame);
    }

    /** Collects peaks, squared sums and timbre fingerprints while samples arrive. */
    private static final class Accumulator {
        private final float[] minimums;
        private final float[] maximums;
        private final double[] squares;
        private final long[] counts;
        private final double[][] bands;
        private final long[] bandCounts;
        private final long durationMs;
        private final DoubleConsumer progress;
        private final double[] window = new double[FFT_SIZE];
        private final double[] real = new double[FFT_SIZE];
        private final double[] imaginary = new double[FFT_SIZE];
        private final double[] hann = new double[FFT_SIZE];
        private int[] bandEdges;
        private int windowFill;
        private long frames;
        private int lastReported = -1;

        Accumulator(int buckets, long durationMs, DoubleConsumer progress) {
            this.minimums = new float[buckets];
            this.maximums = new float[buckets];
            this.squares = new double[buckets];
            this.counts = new long[buckets];
            int featureFrames = (int) Math.max(1, (durationMs + FEATURE_MS - 1) / FEATURE_MS);
            this.bands = new double[featureFrames][BANDS];
            this.bandCounts = new long[featureFrames];
            this.durationMs = durationMs;
            this.progress = progress;
            for (int i = 0; i < FFT_SIZE; i++) {
                hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
            }
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
            squares[bucket] += (double) value * value;
            counts[bucket]++;
            frames++;
            window[windowFill++] = value;
            if (windowFill == FFT_SIZE) {
                windowFill = 0;
                addSpectrum(millis, sampleRate);
            }
            if (progress != null && bucket != lastReported && bucket % 32 == 0) {
                lastReported = bucket;
                progress.accept(Math.min(1, bucket / (double) minimums.length));
            }
        }

        /** Turns the collected window into band energies of the feature frame it belongs to. */
        private void addSpectrum(double millis, int sampleRate) {
            if (sampleRate <= 0) {
                return;
            }
            if (bandEdges == null) {
                bandEdges = bandEdges(sampleRate);
            }
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = window[i] * hann[i];
                imaginary[i] = 0;
            }
            fft(real, imaginary);
            int frame = (int) Math.max(0, Math.min(bands.length - 1, millis / FEATURE_MS));
            double[] target = bands[frame];
            for (int band = 0; band < BANDS; band++) {
                double energy = 0;
                for (int bin = bandEdges[band]; bin < bandEdges[band + 1]; bin++) {
                    energy += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                }
                target[band] += energy / Math.max(1, bandEdges[band + 1] - bandEdges[band]);
            }
            bandCounts[frame]++;
        }

        /** Log-spaced bin boundaries of the bands, clamped to the available spectrum. */
        private static int[] bandEdges(int sampleRate) {
            int[] edges = new int[BANDS + 1];
            double top = Math.min(MAX_BAND_HZ, sampleRate / 2.0);
            for (int i = 0; i <= BANDS; i++) {
                double hz = MIN_BAND_HZ * Math.pow(top / MIN_BAND_HZ, i / (double) BANDS);
                int bin = (int) Math.round(hz * FFT_SIZE / sampleRate);
                edges[i] = Math.max(1, Math.min(FFT_SIZE / 2 - 1, bin));
                if (i > 0 && edges[i] <= edges[i - 1]) {
                    edges[i] = Math.min(FFT_SIZE / 2 - 1, edges[i - 1] + 1);
                }
            }
            return edges;
        }

        WaveformPeaks finish(int frameMs) {
            float[] levels = new float[minimums.length];
            for (int i = 0; i < levels.length; i++) {
                levels[i] = counts[i] == 0 ? 0 : (float) Math.sqrt(squares[i] / counts[i]);
            }
            float[][] fingerprints = new float[bands.length][];
            for (int i = 0; i < bands.length; i++) {
                fingerprints[i] = normalize(bands[i], bandCounts[i]);
            }
            return new WaveformPeaks(minimums, maximums, levels, fingerprints, durationMs, frameMs);
        }

        /**
         * Turns raw band energies into a loudness-independent fingerprint: logarithmic, mean removed and scaled to
         * unit length, so comparing two of them measures the colour of the sound rather than its volume.
         */
        private static float[] normalize(double[] energies, long count) {
            float[] result = new float[BANDS];
            if (count == 0) {
                return result;
            }
            double[] logarithmic = new double[BANDS];
            double mean = 0;
            for (int i = 0; i < BANDS; i++) {
                logarithmic[i] = Math.log1p(energies[i] / count);
                mean += logarithmic[i];
            }
            mean /= BANDS;
            double norm = 0;
            for (int i = 0; i < BANDS; i++) {
                logarithmic[i] -= mean;
                norm += logarithmic[i] * logarithmic[i];
            }
            norm = Math.sqrt(norm);
            if (norm <= 1e-9) {
                return result;
            }
            for (int i = 0; i < BANDS; i++) {
                result[i] = (float) (logarithmic[i] / norm);
            }
            return result;
        }
    }

    /** In-place iterative radix-2 FFT; {@code real} and {@code imaginary} must have a power-of-two length. */
    private static void fft(double[] real, double[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tempReal = real[i];
                real[i] = real[j];
                real[j] = tempReal;
                double tempImaginary = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = tempImaginary;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            for (int i = 0; i < n; i += length) {
                double factorReal = 1;
                double factorImaginary = 0;
                for (int j = 0; j < length / 2; j++) {
                    int a = i + j;
                    int b = a + length / 2;
                    double productReal = real[b] * factorReal - imaginary[b] * factorImaginary;
                    double productImaginary = real[b] * factorImaginary + imaginary[b] * factorReal;
                    real[b] = real[a] - productReal;
                    imaginary[b] = imaginary[a] - productImaginary;
                    real[a] += productReal;
                    imaginary[a] += productImaginary;
                    double nextReal = factorReal * stepReal - factorImaginary * stepImaginary;
                    factorImaginary = factorReal * stepImaginary + factorImaginary * stepReal;
                    factorReal = nextReal;
                }
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
     * Everything the song detection needs besides the audio. With {@code automaticThreshold} the silence level is
     * derived from the file instead of taken from {@code thresholdDb}; with {@code detectChanges} borders are also
     * proposed where the music changes character, which is the only way to split a crossfaded recording.
     */
    public record DetectionOptions(boolean automaticThreshold, double thresholdDb, double dropDb,
                                   double minSilenceSeconds, double minTrackSeconds, double minGapDropDb,
                                   boolean detectChanges, double changeWindowSeconds, double changeSensitivity,
                                   boolean detectLoops) {
    }

    /** The proposed songs plus how they were found, so the UI can report it. */
    public record Detection(List<Range> tracks, double thresholdDb, int changeBorders, int repeatsRemoved,
                            int loopRepetitions) {
    }

    /** A stretch of the file that is one piece repeated back to back, and the borders of its repetitions. */
    public record Loop(long startMs, long endMs, double periodSeconds, List<Long> borders) {
    }

    /** Level of a bucket in dBFS, measured as RMS and floored at {@value #SILENCE_FLOOR_DB}. */
    public double levelDb(int bucket) {
        return toDb(rms(bucket));
    }

    private static double toDb(double amplitude) {
        return amplitude <= 0 ? SILENCE_FLOOR_DB : Math.max(SILENCE_FLOOR_DB, 20 * Math.log10(amplitude));
    }

    private double[] frameLevels() {
        double[] levels = new double[buckets()];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = toDb(rms[i]);
        }
        return levels;
    }

    /**
     * Ranges that stay quieter than {@code thresholdDb} (e.g. -45) for at least {@code minSeconds}. These are the
     * gaps between the songs of a long recording.
     */
    public List<Range> silentRanges(double thresholdDb, double minSeconds) {
        return silentRanges(frameLevels(), thresholdDb, minSeconds);
    }

    private List<Range> silentRanges(double[] levels, double thresholdDb, double minSeconds) {
        double exit = thresholdDb + HYSTERESIS_DB;
        List<Range> merged = new ArrayList<>();
        int start = -1;
        boolean quiet = false;
        for (int bucket = 0; bucket <= levels.length; bucket++) {
            quiet = bucket < levels.length && (quiet ? levels[bucket] <= exit : levels[bucket] <= thresholdDb);
            if (quiet && start < 0) {
                start = bucket;
            } else if (!quiet && start >= 0) {
                Range range = new Range(bucketStartMs(start), bucketStartMs(bucket));
                if (!merged.isEmpty() && range.startMs() - merged.getLast().endMs() <= MERGE_GAP_MS) {
                    merged.set(merged.size() - 1, new Range(merged.getLast().startMs(), range.endMs()));
                } else {
                    merged.add(range);
                }
                start = -1;
            }
        }
        long minMillis = Math.round(Math.max(0, minSeconds) * 1000);
        return merged.stream().filter(range -> range.durationMs() >= minMillis).toList();
    }

    /**
     * The songs of a long recording: everything between the silent ranges, with the silence trimmed off. Ranges
     * shorter than {@code minTrackSeconds} are dropped so a short applause or click does not become a clip.
     */
    public List<Range> detectTracks(double thresholdDb, double minSilenceSeconds, double minTrackSeconds) {
        return detect(new DetectionOptions(false, thresholdDb, 30, minSilenceSeconds, minTrackSeconds, 0,
                false, 24, 2, false)).tracks();
    }

    /**
     * The songs of a long recording. With {@link DetectionOptions#automaticThreshold()} the silence level starts at
     * {@code dropDb} below the level of the music in this very file and is relaxed in steps while the file does not
     * split, which makes the detection work without per-file tuning. With
     * {@link DetectionOptions#detectChanges()} the silent gaps are complemented by the points where the sound
     * changes character, so recordings whose songs are crossfaded into each other still split.
     */
    public Detection detect(DetectionOptions options) {
        double[] levels = frameLevels();
        double threshold = options.thresholdDb();
        List<Range> silences;
        if (options.automaticThreshold()) {
            double[] sorted = levels.clone();
            java.util.Arrays.sort(sorted);
            double music = percentile(sorted, MUSIC_PERCENTILE);
            double base = music - Math.max(1, options.dropDb());
            double ceiling = Math.max(base, music - MIN_HEADROOM_DB);
            threshold = base;
            silences = gaps(levels, base, options);
            for (double relaxation : RELAXATIONS) {
                if (!silences.isEmpty()) {
                    break;
                }
                double relaxed = Math.min(base + relaxation, ceiling);
                threshold = relaxed;
                silences = gaps(levels, relaxed, options);
                if (relaxed >= ceiling) {
                    break;
                }
            }
        } else {
            silences = gaps(levels, threshold, options);
        }
        List<Long> borders = options.detectChanges()
                ? changeBorders(options.changeWindowSeconds(), options.changeSensitivity(), options.minTrackSeconds())
                : List.of();
        java.util.Optional<Loop> loop = options.detectChanges() && options.detectLoops()
                ? detectLoop(options.changeWindowSeconds(), options.minTrackSeconds())
                : java.util.Optional.empty();
        if (loop.isPresent()) {
            borders = withLoopBorders(borders, loop.get(), options);
        }
        List<Range> found = silences;
        borders = borders.stream().filter(border -> found.stream().noneMatch(
                silence -> border >= silence.startMs() - CHANGE_NEAR_SILENCE_MS
                        && border <= silence.endMs() + CHANGE_NEAR_SILENCE_MS)).toList();
        List<Range> tracks = tracks(found, borders, options.minTrackSeconds());
        List<Range> unique = options.detectChanges() ? withoutRepeats(tracks, loop) : tracks;
        return new Detection(unique, threshold, borders.size(), tracks.size() - unique.size(),
                loop.map(repeated -> repeated.borders().size() + 1).orElse(0));
    }

    /**
     * Adds the repetitions of a looped piece to the borders found by the novelty peaks, which cannot see a loop
     * because all of its borders are equally strong. Inside the looped stretch the loop wins: novelty borders there
     * would only break the regular spacing that makes the repetitions recognisable as copies of each other.
     */
    private List<Long> withLoopBorders(List<Long> borders, Loop loop, DetectionOptions options) {
        long spacing = Math.round(Math.max(1, options.minTrackSeconds()) * 1000);
        List<Long> merged = new ArrayList<>(loop.borders());
        if (loop.startMs() > 0) {
            merged.add(loop.startMs());
        }
        if (loop.endMs() < durationMs) {
            merged.add(loop.endMs());
        }
        for (long border : borders) {
            if (border < loop.startMs() || border > loop.endMs()) {
                merged.add(border);
            }
        }
        List<Long> kept = new ArrayList<>();
        for (long border : merged.stream().sorted().toList()) {
            if (kept.isEmpty() || border - kept.get(kept.size() - 1) >= spacing) {
                kept.add(border);
            }
        }
        return List.copyOf(kept);
    }

    /** The silence level below which this file is considered quiet, derived from its own music level. */
    public double automaticSilenceDb(double dropDb) {
        double[] sorted = frameLevels();
        java.util.Arrays.sort(sorted);
        return percentile(sorted, MUSIC_PERCENTILE) - Math.max(1, dropDb);
    }

    /**
     * The gaps that separate songs. A gap of at least {@code minSilenceSeconds} always counts. A shorter one counts
     * only when it is <em>isolated</em>: playlists often cut from one song to the next with a fade of a few hundred
     * milliseconds, while the short rests inside a piece of percussive music come in dense clusters. Requiring the
     * music to run uninterrupted for a whole track length either side tells the two apart without a setting.
     */
    private List<Range> gaps(double[] levels, double thresholdDb, DetectionOptions options) {
        long required = Math.round(Math.max(0, options.minSilenceSeconds()) * 1000);
        double floor = Math.min(required, ISOLATED_GAP_MS) / 1000.0;
        List<Range> candidates = silentRanges(levels, thresholdDb, floor);
        List<Range> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).durationMs() >= required || isolated(candidates, i, options.minTrackSeconds())) {
                kept.add(candidates.get(i));
            }
        }
        return kept.stream().filter(silence -> separatesSongs(levels, silence, options.minGapDropDb())).toList();
    }

    /** Whether the music runs uninterrupted for a good while either side of this gap. */
    private static boolean isolated(List<Range> gaps, int index, double minTrackSeconds) {
        long spacing = Math.max(ISOLATION_MS, Math.round(Math.max(1, minTrackSeconds) * 1000));
        Range gap = gaps.get(index);
        if (index > 0 && gap.startMs() - gaps.get(index - 1).endMs() < spacing) {
            return false;
        }
        return index + 1 >= gaps.size() || gaps.get(index + 1).startMs() - gap.endMs() >= spacing;
    }

    /** Cuts the file at the silent gaps (which are removed) and at the change borders (which are not). */
    private List<Range> tracks(List<Range> silences, List<Long> borders, double minTrackSeconds) {
        long minTrack = Math.round(Math.max(0, minTrackSeconds) * 1000);
        List<Range> segments = new ArrayList<>();
        long cursor = 0;
        for (Range silence : silences) {
            if (silence.startMs() > cursor) {
                segments.add(new Range(cursor, silence.startMs()));
            }
            cursor = silence.endMs();
        }
        if (durationMs > cursor) {
            segments.add(new Range(cursor, durationMs));
        }
        List<Range> tracks = new ArrayList<>();
        for (Range segment : segments) {
            long start = segment.startMs();
            for (long border : borders) {
                if (border - start >= minTrack && segment.endMs() - border >= minTrack) {
                    tracks.add(new Range(start, border));
                    start = border;
                }
            }
            tracks.add(new Range(start, segment.endMs()));
        }
        return tracks.stream().filter(track -> track.durationMs() >= minTrack).toList();
    }

    // ---- Change detection ----

    /**
     * Drops proposals that are a verbatim repetition of the one before them. Hour-long ambience uploads are often a
     * single piece looped ten times; without this the detection would propose ten identical clips.
     */
    private List<Range> withoutRepeats(List<Range> tracks, java.util.Optional<Loop> loop) {
        List<Range> pass = loop.map(found -> withoutLoopRepetitions(tracks, found)).orElse(tracks);
        List<Range> unique = new ArrayList<>();
        for (int i = 0; i < pass.size(); i++) {
            if (i == 0 || !repeats(pass.get(i - 1), pass.get(i))) {
                unique.add(pass.get(i));
            }
        }
        return List.copyOf(unique);
    }

    /**
     * Keeps a single pass of a looped piece. The loop detection has already established that this stretch is the
     * same audio over and over, so the repetitions are dropped without comparing them again - the period is not a
     * whole number of seconds, so a fingerprint comparison would drift out of alignment and miss copies. The
     * truncated repetition an upload begins or ends with goes the same way.
     *
     * <p>A looped piece usually has quiet passages of its own, so a repetition is often split into several
     * proposals and none of them spans a whole period. The kept pass is therefore every proposal inside the first
     * repetition - from the start of the looped stretch to the first repetition border - however many that is;
     * keeping those splits is deliberate, because a loop of two songs played in turn should stay two proposals.</p>
     */
    private List<Range> withoutLoopRepetitions(List<Range> tracks, Loop loop) {
        if (loop.borders().isEmpty()) {
            return tracks;
        }
        long passEnd = loop.borders().getFirst();
        List<Range> kept = new ArrayList<>();
        boolean keptPass = false;
        for (Range track : tracks) {
            if (!insideLoop(track, loop)) {
                kept.add(track);
            } else if (track.startMs() < passEnd - FEATURE_MS) {
                kept.add(track);
                keptPass = true;
            }
        }
        return keptPass ? kept : tracks;
    }

    /** Whether a proposal belongs to the looped stretch, judged by where it starts. */
    private static boolean insideLoop(Range track, Loop loop) {
        return track.startMs() >= loop.startMs() - FEATURE_MS && track.startMs() < loop.endMs();
    }

    /** Whether {@code later} is the same music as {@code earlier}, judged by the timbre fingerprints. */
    private boolean repeats(Range earlier, Range later) {
        long length = Math.min(earlier.durationMs(), later.durationMs());
        if (features.length == 0 || length < FEATURE_MS * 4L
                || Math.abs(earlier.durationMs() - later.durationMs()) > length * 0.1) {
            return false;
        }
        int seconds = (int) (length / FEATURE_MS);
        int from = (int) (earlier.startMs() / FEATURE_MS);
        int to = (int) (later.startMs() / FEATURE_MS);
        double sum = 0;
        int compared = 0;
        for (int i = 0; i < seconds; i++) {
            if (from + i >= features.length || to + i >= features.length) {
                break;
            }
            sum += dot(features[from + i], features[to + i]);
            compared++;
        }
        return compared > 0 && sum / compared >= REPEAT_SIMILARITY;
    }

    /**
     * The moments where the sound changes character, found with a checkerboard kernel over the similarity of the
     * timbre fingerprints (Foote novelty). A border is a peak of that curve that stands out {@code sensitivity}
     * robust deviations from the rest of the file and keeps {@code minTrackSeconds} distance from stronger peaks.
     */
    public List<Long> changeBorders(double windowSeconds, double sensitivity, double minTrackSeconds) {
        double[] novelty = noveltyCurve(windowSeconds);
        if (novelty.length == 0) {
            return List.of();
        }
        double[] sorted = novelty.clone();
        java.util.Arrays.sort(sorted);
        double median = percentile(sorted, 0.5);
        double[] deviations = new double[novelty.length];
        for (int i = 0; i < novelty.length; i++) {
            deviations[i] = Math.abs(novelty[i] - median);
        }
        java.util.Arrays.sort(deviations);
        double spread = 1.4826 * percentile(deviations, 0.5);
        if (spread <= 1e-9) {
            return List.of();
        }
        int spacing = (int) Math.max(1, Math.round(
                Math.max(Math.max(1, minTrackSeconds), Math.max(1, windowSeconds)) * 1000 / (double) FEATURE_MS));
        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < novelty.length - 1; i++) {
            if ((novelty[i] - median) / spread < sensitivity
                    || novelty[i] < novelty[i - 1] || novelty[i] < novelty[i + 1]) {
                continue;
            }
            candidates.add(i);
        }
        candidates.sort((left, right) -> Double.compare(novelty[right], novelty[left]));
        List<Integer> accepted = new ArrayList<>();
        for (int candidate : candidates) {
            boolean crowded = accepted.stream().anyMatch(other -> Math.abs(other - candidate) < spacing);
            if (!crowded) {
                accepted.add(candidate);
            }
        }
        return accepted.stream().sorted().map(frame -> (long) frame * FEATURE_MS).toList();
    }

    // ---- Loop detection ----

    /**
     * The length of the piece this file loops, if it is one. Outlier-based peak picking is blind to such a file:
     * when every border is equally strong and evenly spaced, none of them stands out from the rest. The period is
     * instead read off the fingerprint self-similarity, which has a sharp spike at the loop length and at every
     * multiple of it.
     */
    public java.util.OptionalDouble loopPeriodSeconds(double minTrackSeconds) {
        int count = features.length;
        int minLag = (int) Math.max(5, Math.round(Math.max(1, minTrackSeconds)));
        int maxLag = Math.min(LOOP_MAX_SECONDS, count / LOOP_MIN_REPEATS);
        if (maxLag < minLag + LOOP_TREND_LAGS) {
            return java.util.OptionalDouble.empty();
        }
        double[] similarity = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < count; i++) {
                sum += dot(features[i], features[i + lag]);
            }
            similarity[lag] = sum / (count - lag);
        }
        double[] residual = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            int from = Math.max(minLag, lag - LOOP_TREND_LAGS / 2);
            int to = Math.min(maxLag, lag + LOOP_TREND_LAGS / 2);
            residual[lag] = similarity[lag] - median(similarity, from, to + 1);
        }
        double[] deviations = new double[maxLag + 1 - minLag];
        for (int lag = minLag; lag <= maxLag; lag++) {
            deviations[lag - minLag] = Math.abs(residual[lag]);
        }
        java.util.Arrays.sort(deviations);
        double spread = 1.4826 * percentile(deviations, 0.5);
        if (spread <= 1e-9) {
            return java.util.OptionalDouble.empty();
        }
        List<Integer> candidates = new ArrayList<>();
        for (int lag = minLag + 1; lag < maxLag; lag++) {
            if (residual[lag] / spread >= LOOP_Z
                    && residual[lag] >= residual[lag - 1] && residual[lag] >= residual[lag + 1]) {
                candidates.add(lag);
            }
        }
        for (int candidate : candidates) {
            boolean confirmed = 2 * candidate > maxLag;
            for (int multiple = 2 * candidate; multiple <= maxLag && !confirmed; multiple += candidate) {
                int at = multiple;
                confirmed = candidates.stream().anyMatch(other -> Math.abs(other - at) <= LOOP_TOLERANCE);
            }
            if (confirmed) {
                return java.util.OptionalDouble.of(candidate);
            }
        }
        return java.util.OptionalDouble.empty();
    }

    /**
     * Borders at every repetition of the looped piece. They are emitted only across the stretch where the loop
     * really holds, so a mix that merely ends in a loop keeps its varied beginning. The repetitions are anchored to
     * the start of that stretch, because that is the one repetition border the file tells us about - a seamless
     * loop sounds the same at every phase, so searching for the phase only drifted the borders off it. The period
     * is refined against the novelty curve so the borders land on the audible seam rather than on a whole second.
     */
    public java.util.Optional<Loop> detectLoop(double windowSeconds, double minTrackSeconds) {
        java.util.OptionalDouble found = loopPeriodSeconds(minTrackSeconds);
        if (found.isEmpty()) {
            return java.util.Optional.empty();
        }
        int lag = (int) Math.round(found.getAsDouble());
        int[] region = loopRegion(lag);
        if (region == null) {
            return java.util.Optional.empty();
        }
        double start = region[0];
        double end = region[1];
        double[] novelty = noveltyCurve(Math.min(Math.max(1, windowSeconds), lag / 3.0));
        double period = lag;
        if (novelty.length > 0) {
            double best = Double.NEGATIVE_INFINITY;
            for (double step = -LOOP_TOLERANCE; step <= LOOP_TOLERANCE + 1e-9; step += 0.05) {
                double candidate = lag + step;
                double score = 0;
                int borders = 0;
                for (double at = start + candidate; at <= end; at += candidate) {
                    score += noveltyAt(novelty, at);
                    borders++;
                }
                if (borders > 0 && score / borders > best) {
                    best = score / borders;
                    period = candidate;
                }
            }
        }
        List<Long> borders = new ArrayList<>();
        for (double at = start + period; at <= end; at += period) {
            long millis = Math.round(at * FEATURE_MS);
            if (millis < durationMs && (borders.isEmpty()
                    || millis - borders.get(borders.size() - 1) >= FEATURE_MS)) {
                borders.add(millis);
            }
        }
        return java.util.Optional.of(new Loop(Math.round(start * FEATURE_MS),
                Math.min(durationMs, Math.round(end * FEATURE_MS)), period, List.copyOf(borders)));
    }

    /** The longest run of seconds that each sound like the second a period later, or {@code null} if there is none. */
    private int[] loopRegion(int lag) {
        int count = features.length;
        if (lag <= 0 || count <= lag + 2) {
            return null;
        }
        boolean[] matching = new boolean[count - lag];
        for (int i = 0; i < matching.length; i++) {
            double sum = 0;
            int compared = 0;
            for (int offset = -2; offset <= 2; offset++) {
                int at = i + offset;
                if (at >= 0 && at + lag < count) {
                    sum += dot(features[at], features[at + lag]);
                    compared++;
                }
            }
            matching[i] = compared > 0 && sum / compared >= LOOP_REGION_SIMILARITY;
        }
        int bestFrom = -1;
        int bestLength = 0;
        int from = -1;
        for (int i = 0; i <= matching.length; i++) {
            if (i < matching.length && matching[i]) {
                from = from < 0 ? i : from;
            } else if (from >= 0) {
                if (i - from > bestLength) {
                    bestLength = i - from;
                    bestFrom = from;
                }
                from = -1;
            }
        }
        if (bestFrom < 0 || bestLength + lag < lag * LOOP_MIN_REPEATS) {
            return null;
        }
        return new int[] {bestFrom, bestFrom + bestLength - 1 + lag};
    }

    private static double noveltyAt(double[] novelty, double seconds) {
        if (seconds <= 0 || seconds >= novelty.length - 1) {
            return 0;
        }
        int index = (int) Math.floor(seconds);
        double fraction = seconds - index;
        return novelty[index] * (1 - fraction) + novelty[index + 1] * fraction;
    }

    private static double median(double[] values, int from, int to) {
        double[] window = java.util.Arrays.copyOfRange(values, from, to);
        java.util.Arrays.sort(window);
        return percentile(window, 0.5);
    }

    /**
     * How differently the audio before and after each second sounds: high where two self-similar blocks meet that do
     * not resemble each other, which is exactly a song border, with or without a gap.
     */
    private double[] noveltyCurve(double windowSeconds) {        int half = (int) Math.max(2, Math.round(Math.max(1, windowSeconds) * 1000 / (double) FEATURE_MS));
        int count = features.length;
        if (count < 4 * half) {
            return new double[0];
        }
        int reach = 2 * half;
        double[][] similarity = new double[count][reach + 1];
        for (int i = 0; i < count; i++) {
            for (int lag = 0; lag <= reach && i + lag < count; lag++) {
                similarity[i][lag] = dot(features[i], features[i + lag]);
            }
        }
        double[] novelty = new double[count];
        for (int i = half; i < count - half; i++) {
            double past = 0;
            double future = 0;
            double across = 0;
            for (int a = 1; a <= half; a++) {
                for (int b = 1; b <= half; b++) {
                    past += similar(similarity, i - a, i - b, count);
                    future += similar(similarity, i + a - 1, i + b - 1, count);
                    across += similar(similarity, i - a, i + b - 1, count);
                }
            }
            novelty[i] = ((past + future) / 2 - across) / ((double) half * half);
        }
        return novelty;
    }

    private static double similar(double[][] similarity, int left, int right, int count) {
        int from = Math.min(left, right);
        int lag = Math.abs(left - right);
        if (from < 0 || from >= count || lag >= similarity[from].length || from + lag >= count) {
            return 0;
        }
        return similarity[from][lag];
    }

    private static double dot(float[] left, float[] right) {
        double sum = 0;
        for (int i = 0; i < left.length && i < right.length; i++) {
            sum += (double) left[i] * right[i];
        }
        return sum;
    }


    /**
     * Whether {@code silence} is a real gap rather than a quiet passage: its own level has to be {@code minDropDb}
     * below the music next to it. Without this a whole quiet ambient piece falls apart into dozens of fragments.
     */
    private boolean separatesSongs(double[] levels, Range silence, double minDropDb) {
        if (minDropDb <= 0) {
            return true;
        }
        int from = bucketAt(silence.startMs());
        int to = Math.max(from + 1, bucketAt(Math.max(silence.startMs(), silence.endMs() - 1)) + 1);
        int window = Math.max(1, (int) Math.round(CONTRAST_WINDOW_MS / (double) Math.max(1, frameMs)));
        double inside = quantile(levels, from, to, 0.5);
        double before = quantile(levels, from - window, from, 0.75);
        double after = quantile(levels, to, to + window, 0.75);
        return Math.max(before, after) - inside >= minDropDb;
    }

    /** The {@code fraction} quantile of {@code levels} between {@code from} (inclusive) and {@code to}. */
    private static double quantile(double[] levels, int from, int to, double fraction) {
        int start = Math.max(0, from);
        int end = Math.min(levels.length, to);
        if (start >= end) {
            return SILENCE_FLOOR_DB;
        }
        double[] window = java.util.Arrays.copyOfRange(levels, start, end);
        java.util.Arrays.sort(window);
        return percentile(window, fraction);
    }

    private static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return SILENCE_FLOOR_DB;
        }
        int index = (int) Math.round(fraction * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    // ---- Disk cache ----

    /** Reads cached peaks, or {@code null} when there is no usable cache for this frame length. */
    public static WaveformPeaks readCache(Path cacheFile, int frameMs) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            byte[] magic = in.readNBytes(MAGIC.length());
            if (!MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
                return null;
            }
            int frame = in.readInt();
            int count = in.readInt();
            int featureCount = in.readInt();
            long duration = in.readLong();
            if (frame != Math.max(1, frameMs) || count <= 0 || count > 1 << 22
                    || featureCount < 0 || featureCount > 1 << 22) {
                return null;
            }
            float[] minimums = new float[count];
            float[] maximums = new float[count];
            float[] levels = new float[count];
            for (int i = 0; i < count; i++) {
                minimums[i] = in.readShort() / 32768f;
                maximums[i] = in.readShort() / 32768f;
                levels[i] = in.readShort() / 32768f;
            }
            float[][] features = new float[featureCount][BANDS];
            for (int i = 0; i < featureCount; i++) {
                for (int band = 0; band < BANDS; band++) {
                    features[i][band] = in.readShort() / 32768f;
                }
            }
            return new WaveformPeaks(minimums, maximums, levels, features, duration, frame);
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
                out.writeInt(frameMs);
                out.writeInt(buckets());
                out.writeInt(features.length);
                out.writeLong(durationMs);
                for (int i = 0; i < buckets(); i++) {
                    out.writeShort(Math.round(minimum(i) * 32767));
                    out.writeShort(Math.round(maximum(i) * 32767));
                    out.writeShort(Math.round(rms(i) * 32767));
                }
                for (float[] feature : features) {
                    for (int band = 0; band < BANDS; band++) {
                        out.writeShort(Math.round((band < feature.length ? feature[band] : 0) * 32767));
                    }
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

    /** Peaks built directly from values; for tests and for previews of ranges. The RMS is taken from the peaks. */
    static WaveformPeaks of(float[] minimums, float[] maximums, long durationMs) {
        float[] levels = new float[minimums.length];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(Math.abs(minimums[i]), Math.abs(maximums[i]));
        }
        return of(minimums, maximums, levels, durationMs);
    }

    /** Peaks built directly from values including their RMS levels; for tests. */
    static WaveformPeaks of(float[] minimums, float[] maximums, float[] rms, long durationMs) {
        int frame = minimums.length == 0 ? 1 : (int) Math.max(1, Math.round(durationMs / (double) minimums.length));
        return new WaveformPeaks(minimums, maximums, rms, new float[0][], durationMs, frame);
    }

    /** Peaks with timbre fingerprints, one per {@link #FEATURE_MS}; for tests of the change detection. */
    static WaveformPeaks of(float[] minimums, float[] maximums, float[] rms, float[][] features, long durationMs) {
        int frame = minimums.length == 0 ? 1 : (int) Math.max(1, Math.round(durationMs / (double) minimums.length));
        return new WaveformPeaks(minimums, maximums, rms, features, durationMs, frame);
    }
}
