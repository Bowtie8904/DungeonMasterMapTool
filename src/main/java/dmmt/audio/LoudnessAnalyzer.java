package dmmt.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Streaming BS.1770-style K-weighted loudness analysis and peak-safe PCM playback preparation. */
final class LoudnessAnalyzer {
    static final double TARGET_LUFS = -23;
    static final double PEAK_CEILING_DBFS = -1;
    static final double MAX_BOOST_DB = 24;
    static final double MIN_GAIN_DB = -60;
    private static final double ABSOLUTE_GATE_LUFS = -70;
    private static final double RELATIVE_GATE_LU = -10;
    private static final double LIMITER_LOOKAHEAD_SECONDS = 0.005;
    private static final double LIMITER_RELEASE_SECONDS = 0.1;
    private static final double[] CHANNEL_WEIGHTS = {1, 1, 1, 0, 1.41, 1.41};

    record Measurement(Double loudnessLufs, Double samplePeakDbfs, Double peakHeadroomDb,
                       double maximumGainDb, double autoGainDb, int channels, int sampleRate) {
    }

    private LoudnessAnalyzer() {
    }

    static Measurement analyze(Path file) throws IOException {
        return analyze(file, null, null, 50);
    }

    static Measurement analyze(Path file, java.util.function.DoubleConsumer progress, Path waveformCache,
                               int waveformFrameMs) throws IOException {
        Path statistics = file.resolveSibling(file.getFileName() + "." + java.util.UUID.randomUUID() + ".energy");
        try {
            return collect(file, progress, waveformCache, waveformFrameMs, statistics);
        } finally {
            Files.deleteIfExists(statistics);
        }
    }

    private static Measurement collect(Path file, java.util.function.DoubleConsumer progress, Path waveformCache,
                                       int waveformFrameMs, Path statistics) throws IOException {
        WindowStats absolute = new WindowStats();
        PeakStats peak = new PeakStats();
        long duration = Math.max(1, AudioFormats.durationMs(file));
        WaveformPeaks.Accumulator waveform = waveformCache == null ? null
                : WaveformPeaks.accumulator(duration, waveformFrameMs, null);
        long[] frames = {0};
        // Only gated block energies are spooled, never PCM; even long recordings use bounded heap.
        try (java.io.DataOutputStream energies = new java.io.DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(statistics)))) {
            absolute.energies = energies;
            decode(file, (samples, channels, rate) -> {
                peak.accept(samples, channels, rate);
                absolute.accept(samples, channels, rate);
                if (waveform != null) {
                    int sum = 0;
                    for (float sample : samples) {
                        sum += (int) (sample * 32768);
                    }
                    waveform.add(sum / (float) channels / 32768f, rate);
                }
                if (++frames[0] % 4096 == 0 && progress != null) {
                    progress.accept(Math.min(0.99, frames[0] * 1000.0 / rate / duration));
                }
            });
            absolute.finish();
        }
        if (waveform != null) {
            waveform.finish(waveformFrameMs).writeCache(waveformCache);
        }
        if (progress != null) {
            progress.accept(1);
        }
        if (peak.maximum == 0) {
            return new Measurement(null, null, null, 0, 0, peak.channels, peak.sampleRate);
        }
        double peakDbfs = 20 * Math.log10(peak.maximum);
        double headroom = PEAK_CEILING_DBFS - peakDbfs;
        double maximumGain = Math.min(MAX_BOOST_DB, headroom);
        if (absolute.count == 0) {
            return new Measurement(null, peakDbfs, headroom, maximumGain, 0, peak.channels, peak.sampleRate);
        }
        double relativeGate = absolute.energy / absolute.count
                * Math.pow(10, RELATIVE_GATE_LU / 10);
        double energy = 0;
        long count = 0;
        try (java.io.DataInputStream energies = new java.io.DataInputStream(
                new BufferedInputStream(Files.newInputStream(statistics)))) {
            for (long i = 0; i < absolute.count; i++) {
                double value = energies.readDouble();
                if (value >= relativeGate) {
                    energy += value;
                    count++;
                }
            }
        }
        double lufs = count == 0 ? Double.NEGATIVE_INFINITY
                : -0.691 + 10 * Math.log10(energy / count);
        double automaticGain = count == 0 ? 0
                : Math.max(MIN_GAIN_DB, Math.min(maximumGain, TARGET_LUFS - lufs));
        return new Measurement(Double.isFinite(lufs) ? lufs : null, peakDbfs, headroom,
                maximumGain, automaticGain, peak.channels, peak.sampleRate);
    }

    static void writePrepared(Path source, Path target, double gainDb, int channels, int sampleRate)
            throws IOException {
        writePrepared(source, target, gainDb, channels, sampleRate, null);
    }

    static void writePrepared(Path source, Path target, double gainDb, int channels, int sampleRate,
                              java.util.function.DoubleConsumer progress) throws IOException {
        long duration = Math.max(1, AudioFormats.durationMs(source));
        long[] frames = {0};
        writePreparedFile(target, channels, sampleRate, writer -> {
            double gain = Math.pow(10, gainDb / 20);
            double ceiling = Math.pow(10, PEAK_CEILING_DBFS / 20);
            decode(source, (samples, count, rate) -> {
                if (count != channels || rate != sampleRate) {
                    throw new IOException("Audio format changed while preparing playback.");
                }
                for (int channel = 0; channel < count; channel++) {
                    samples[channel] = (float) Math.max(-ceiling, Math.min(ceiling, samples[channel] * gain));
                }
                writer.accept(samples);
                if (++frames[0] % 4096 == 0 && progress != null) {
                    progress.accept(Math.min(0.99, frames[0] * 1000.0 / rate / duration));
                }
            }, channels, sampleRate);
        });
        if (progress != null) {
            progress.accept(1);
        }
    }

    static void writeLimitedPrepared(Path source, Path target, double gainDb, int channels, int sampleRate)
            throws IOException {
        writePreparedFile(target, channels, sampleRate, writer -> {
            Limiter limiter = new Limiter(channels, sampleRate, gainDb, writer);
            decode(source, (samples, count, rate) -> {
                if (count != channels || rate != sampleRate) {
                    throw new IOException("Audio format changed while preparing playback.");
                }
                limiter.accept(samples);
            }, channels, sampleRate);
            limiter.finish();
        });
    }

    private static void writePreparedFile(Path target, int channels, int sampleRate, AudioPreparation preparation)
            throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        long[] dataBytes = {0};
        try {
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16)) {
                output.write(new byte[44]);
                byte[] buffer = new byte[1 << 16];
                int[] bufferedBytes = {0};
                preparation.write(samples -> {
                    int frameSize = channels * 2;
                    if (bufferedBytes[0] + frameSize > buffer.length) {
                        output.write(buffer, 0, bufferedBytes[0]);
                        bufferedBytes[0] = 0;
                    }
                    for (int channel = 0; channel < channels; channel++) {
                        if (!Float.isFinite(samples[channel]) || Math.abs(samples[channel]) > 1) {
                            throw new IOException("Prepared audio exceeded the PCM sample range.");
                        }
                        int pcm = (int) Math.round(samples[channel] * 32767);
                        buffer[bufferedBytes[0]++] = (byte) pcm;
                        buffer[bufferedBytes[0]++] = (byte) (pcm >>> 8);
                    }
                    dataBytes[0] += frameSize;
                });
                output.write(buffer, 0, bufferedBytes[0]);
            }
            try (RandomAccessFile output = new RandomAccessFile(temp.toFile(), "rw")) {
                if (dataBytes[0] > 0xFFFFFFFFL - 36) {
                    throw new IOException("The prepared WAV is too large for the supported PCM format.");
                }
                output.seek(0);
                writeHeader(output, channels, sampleRate, dataBytes[0]);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                atomicMoveFailure.addSuppressed(moveFailure);
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailure) {
                    atomicMoveFailure.addSuppressed(cleanupFailure);
                }
                throw atomicMoveFailure;
            }
        }
    }

    @FunctionalInterface
    private interface AudioPreparation {
        void write(PreparedFrameWriter writer) throws IOException;
    }

    @FunctionalInterface
    private interface PreparedFrameWriter {
        void accept(float[] samples) throws IOException;
    }

    private static final class Limiter {
        private final int channels;
        private final int lookaheadFrames;
        private final float[][] delayed;
        private final long[] peakIndices;
        private final double[] peakValues;
        private final double inputGain;
        private final double ceiling = Math.pow(10, PEAK_CEILING_DBFS / 20);
        private final double releaseCoefficient;
        private final PreparedFrameWriter writer;
        private long frames;
        private long emitted;
        private int dequeHead;
        private int dequeSize;
        private double envelope = 1;

        private Limiter(int channels, int sampleRate, double gainDb, PreparedFrameWriter writer) {
            this.channels = channels;
            this.lookaheadFrames = Math.max(1, (int) Math.round(sampleRate * LIMITER_LOOKAHEAD_SECONDS));
            int capacity = lookaheadFrames + 1;
            delayed = new float[capacity][channels];
            peakIndices = new long[capacity];
            peakValues = new double[capacity];
            inputGain = Math.pow(10, gainDb / 20);
            releaseCoefficient = 1 - Math.exp(-1.0 / (sampleRate * LIMITER_RELEASE_SECONDS));
            this.writer = writer;
        }

        private void accept(float[] samples) throws IOException {
            checkInterrupted();
            long earliestInWindow = Math.max(0, frames - lookaheadFrames);
            discardBefore(earliestInWindow);
            int slot = (int) (frames % delayed.length);
            System.arraycopy(samples, 0, delayed[slot], 0, channels);
            double peak = 0;
            for (int channel = 0; channel < channels; channel++) {
                peak = Math.max(peak, Math.abs(samples[channel] * inputGain));
            }
            while (dequeSize > 0 && dequeValue(dequeSize - 1) <= peak) {
                dequeSize--;
            }
            int tail = (dequeHead + dequeSize) % peakIndices.length;
            peakIndices[tail] = frames;
            peakValues[tail] = peak;
            dequeSize++;
            frames++;
            if (frames > lookaheadFrames) {
                writeNext(emitted);
            }
        }

        private void finish() throws IOException {
            while (emitted < frames) {
                checkInterrupted();
                writeNext(emitted);
            }
        }

        private void discardBefore(long frameIndex) {
            while (dequeSize > 0 && peakIndices[dequeHead] < frameIndex) {
                dequeHead = (dequeHead + 1) % peakIndices.length;
                dequeSize--;
            }
        }

        private void writeNext(long frameIndex) throws IOException {
            discardBefore(frameIndex);
            double maximumPeak = dequeSize == 0 ? 0 : peakValues[dequeHead];
            double targetEnvelope = maximumPeak == 0 ? 1 : Math.min(1, ceiling / maximumPeak);
            if (targetEnvelope < envelope) {
                envelope = targetEnvelope;
            } else {
                envelope += (targetEnvelope - envelope) * releaseCoefficient;
            }
            float[] output = delayed[(int) (frameIndex % delayed.length)];
            for (int channel = 0; channel < channels; channel++) {
                output[channel] = (float) (output[channel] * inputGain * envelope);
            }
            writer.accept(output);
            emitted++;
        }

        private double dequeValue(int offset) {
            return peakValues[(dequeHead + offset) % peakValues.length];
        }
    }

    private static void writeHeader(RandomAccessFile out, int channels, int sampleRate, long dataBytes)
            throws IOException {
        out.writeBytes("RIFF");
        writeIntLe(out, (int) (36 + dataBytes));
        out.writeBytes("WAVEfmt ");
        writeIntLe(out, 16);
        writeShortLe(out, 1);
        writeShortLe(out, channels);
        writeIntLe(out, sampleRate);
        writeIntLe(out, sampleRate * channels * 2);
        writeShortLe(out, channels * 2);
        writeShortLe(out, 16);
        out.writeBytes("data");
        writeIntLe(out, (int) dataBytes);
    }

    private static void writeIntLe(RandomAccessFile out, int value) throws IOException {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeShortLe(RandomAccessFile out, int value) throws IOException {
        out.write(value);
        out.write(value >>> 8);
    }

    @FunctionalInterface
    private interface FrameConsumer {
        void accept(float[] samples, int channels, int sampleRate) throws IOException;
    }

    private static void decode(Path file, FrameConsumer consumer) throws IOException {
        decode(file, consumer, -1, -1);
    }

    private static void decode(Path file, FrameConsumer consumer, int expectedChannels, int expectedRate)
            throws IOException {
        if (AudioFormats.isMp3(file)) {
            decodeMp3(file, consumer);
        } else {
            decodeWav(file, consumer, expectedChannels, expectedRate);
        }
    }

    private static void decodeMp3(Path file, FrameConsumer consumer) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bitstream = new Bitstream(input);
            Decoder decoder = new Decoder();
            int frameCount = 0;
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                checkInterrupted();
                SampleBuffer decoded;
                try {
                    decoded = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IOException("Could not decode \"" + file.getFileName()
                            + "\": the MP3 frame is malformed.", e);
                }
                short[] buffer = decoded.getBuffer();
                int channels = Math.max(1, decoded.getChannelCount());
                int rate = decoded.getSampleFrequency();
                float[] frame = new float[channels];
                for (int at = 0; at + channels <= decoded.getBufferLength(); at += channels) {
                    for (int channel = 0; channel < channels; channel++) {
                        frame[channel] = buffer[at + channel] / 32768f;
                    }
                    consumer.accept(frame, channels, rate);
                }
                bitstream.closeFrame();
                frameCount++;
            }
            bitstream.close();
            if (frameCount == 0) {
                throw new IOException("\"" + file.getFileName() + "\" contains no decodable MP3 frames.");
            }
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("Could not decode \"" + file.getFileName() + "\": " + e.getMessage(), e);
        }
    }

    private static void decodeWav(Path file, FrameConsumer consumer, int expectedChannels, int expectedRate)
            throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat source = raw.getFormat();
            int channels = expectedChannels > 0 ? expectedChannels : source.getChannels();
            float sampleRate = expectedRate > 0 ? expectedRate : source.getSampleRate();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16,
                    channels, channels * 2, sampleRate, false);
            if (!AudioSystem.isConversionSupported(target, source)) {
                if (source.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                        && source.getSampleSizeInBits() == 16 && source.getChannels() == channels
                        && source.getSampleRate() == sampleRate && !source.isBigEndian()) {
                    readPcm16(raw, channels, (int) sampleRate, consumer);
                    return;
                }
                throw new IOException("Unsupported WAV sample format in \"" + file.getFileName() + "\".");
            }
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, raw)) {
                readPcm16(pcm, channels, (int) sampleRate, consumer);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Could not decode \"" + file.getFileName() + "\": " + e.getMessage(), e);
        }
    }

    private static void readPcm16(AudioInputStream pcm, int channels, int sampleRate, FrameConsumer consumer)
            throws IOException {
        int frameSize = channels * 2;
        byte[] bytes = new byte[1 << 16];
        byte[] partial = new byte[frameSize];
        float[] frame = new float[channels];
        int carry = 0;
        int read;
        while ((read = pcm.read(bytes)) >= 0) {
            checkInterrupted();
            if (read == 0) {
                continue;
            }
            int offset = 0;
            if (carry > 0) {
                int copied = Math.min(frameSize - carry, read);
                System.arraycopy(bytes, 0, partial, carry, copied);
                carry += copied;
                offset += copied;
                if (carry == frameSize) {
                    deliverPcmFrame(partial, frame, channels, sampleRate, consumer);
                    carry = 0;
                } else {
                    continue;
                }
            }
            while (offset + frameSize <= read) {
                deliverPcmFrame(bytes, offset, frame, channels, sampleRate, consumer);
                offset += frameSize;
            }
            carry = read - offset;
            if (carry > 0) {
                System.arraycopy(bytes, offset, partial, 0, carry);
            }
        }
    }

    private static void deliverPcmFrame(byte[] bytes, float[] frame, int channels, int sampleRate,
                                        FrameConsumer consumer) throws IOException {
        deliverPcmFrame(bytes, 0, frame, channels, sampleRate, consumer);
    }

    private static void deliverPcmFrame(byte[] bytes, int offset, float[] frame, int channels, int sampleRate,
                                        FrameConsumer consumer) throws IOException {
        for (int channel = 0; channel < channels; channel++) {
            int at = offset + channel * 2;
            frame[channel] = (short) ((bytes[at] & 0xFF) | (bytes[at + 1] << 8)) / 32768f;
        }
        consumer.accept(frame, channels, sampleRate);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Audio analysis was interrupted.");
        }
    }

    private static final class PeakStats {
        private double maximum;
        private int channels;
        private int sampleRate;

        void accept(float[] samples, int channels, int sampleRate) {
            if (this.channels == 0) {
                this.channels = channels;
                this.sampleRate = sampleRate;
            }
            for (int i = 0; i < channels; i++) {
                maximum = Math.max(maximum, Math.abs(samples[i]));
            }
        }
    }

    private static final class WindowStats {
        private java.io.DataOutputStream energies;
        private double energy;
        private long count;
        private int channels;
        private int sampleRate;
        private int windowFrames;
        private int hopFrames;
        private long frames;
        private long lastWindowEnd;
        private double rollingEnergy;
        private double[] ring;
        private Biquad[] shelf;
        private Biquad[] highPass;

        void accept(float[] samples, int channelCount, int rate) throws IOException {
            if (channels == 0) {
                channels = channelCount;
                sampleRate = rate;
                windowFrames = Math.max(1, (int) Math.round(rate * 0.4));
                hopFrames = Math.max(1, (int) Math.round(rate * 0.1));
                ring = new double[windowFrames];
                shelf = new Biquad[channels];
                highPass = new Biquad[channels];
                for (int channel = 0; channel < channels; channel++) {
                    shelf[channel] = Biquad.highShelf(rate);
                    highPass[channel] = Biquad.highPass(rate);
                }
            } else if (channels != channelCount || sampleRate != rate) {
                throw new IOException("Audio changes channel count or sample rate while decoding.");
            }
            double frameEnergy = 0;
            for (int channel = 0; channel < channels; channel++) {
                double weighted = highPass[channel].process(shelf[channel].process(samples[channel]));
                double weight = channel < CHANNEL_WEIGHTS.length ? CHANNEL_WEIGHTS[channel] : 1.41;
                frameEnergy += weighted * weighted * weight;
            }
            int slot = (int) (frames % windowFrames);
            if (frames >= windowFrames) {
                rollingEnergy -= ring[slot];
            }
            ring[slot] = frameEnergy;
            rollingEnergy += frameEnergy;
            frames++;
            if (frames >= windowFrames && (frames - windowFrames) % hopFrames == 0) {
                addWindow(rollingEnergy / windowFrames);
                lastWindowEnd = frames;
            }
        }

        private void addWindow(double meanEnergy) throws IOException {
            double absoluteThreshold = Math.pow(10, (ABSOLUTE_GATE_LUFS + 0.691) / 10);
            if (meanEnergy >= absoluteThreshold) {
                energy += meanEnergy;
                count++;
                if (energies != null) {
                    energies.writeDouble(meanEnergy);
                }
            }
        }

        void finish() throws IOException {
            if (frames > 0 && frames < windowFrames) {
                addWindow(rollingEnergy / frames);
            } else if (frames >= windowFrames && lastWindowEnd < frames) {
                addWindow(rollingEnergy / windowFrames);
            }
        }
    }

    private static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        private Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            return output;
        }

        static Biquad highShelf(int rate) {
            double k = Math.tan(Math.PI * 1681.974450955533 / rate);
            double q = 0.7071752369554196;
            double vh = Math.pow(10, 3.999843853973347 / 20);
            double vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1 + k / q + k * k;
            return new Biquad((vh + vb * k / q + k * k) / a0,
                    2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
        }

        static Biquad highPass(int rate) {
            double k = Math.tan(Math.PI * 38.13547087602444 / rate);
            double q = 0.5003270373238773;
            double a0 = 1 + k / q + k * k;
            return new Biquad(1 / a0, -2 / a0, 1 / a0, 2 * (k * k - 1) / a0,
                    (1 - k / q + k * k) / a0);
        }
    }
}
