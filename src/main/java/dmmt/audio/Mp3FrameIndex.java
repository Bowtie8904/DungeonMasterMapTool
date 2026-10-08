package dmmt.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame table of an MPEG audio (MP3) file: the byte offset and length of every frame plus the samples it holds.
 * Frames are the smallest addressable unit of an MP3, so cutting on frame boundaries copies the encoded bytes
 * unchanged (no decode, no re-encode, no quality loss, 3.35.3). Parsing a two-hour file reads only the headers.
 */
public final class Mp3FrameIndex {
    /** Bitrates in kbit/s per version/layer, index 0 and 15 are invalid. */
    private static final int[][] BITRATES = {
            // MPEG 2/2.5 Layer III, Layer II, Layer I
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
            // MPEG 1 Layer III, Layer II, Layer I
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0}};
    private static final int[][] SAMPLE_RATES = {
            {11025, 12000, 8000},   // MPEG 2.5
            {0, 0, 0},
            {22050, 24000, 16000},  // MPEG 2
            {44100, 48000, 32000}}; // MPEG 1

    private final long[] offsets;
    private final int[] lengths;
    private final int sampleRate;
    private final int channels;
    private final long totalSamples;
    private final long audioStart;
    private final long fileSize;

    private Mp3FrameIndex(long[] offsets, int[] lengths, int sampleRate, int channels, long totalSamples,
                          long audioStart, long fileSize) {
        this.offsets = offsets;
        this.lengths = lengths;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.totalSamples = totalSamples;
        this.audioStart = audioStart;
        this.fileSize = fileSize;
    }

    public int frameCount() {
        return offsets.length;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public long durationMs() {
        return sampleRate <= 0 ? 0 : Math.round(totalSamples * 1000.0 / sampleRate);
    }

    /** Byte offset of the first audio frame; everything before it is an ID3v2 tag or leading garbage. */
    public long audioStart() {
        return audioStart;
    }

    /**
     * Share of the file behind the ID3 tag that is really covered by MPEG frames (1.0 = every byte belongs to a
     * frame). Because the parser resynchronises on any {@code 0xFF} byte, random or encrypted data always produces
     * a handful of accidental "frames"; those cover only a few percent of the file, while a real MP3 is close to
     * 1.0. Used to tell a damaged or non-MP3 file apart from a slightly dirty download (3.35.1).
     */
    public double frameCoverage() {
        long payload = fileSize - audioStart;
        if (payload <= 0) {
            return 0;
        }
        long covered = 0;
        for (int length : lengths) {
            covered += length;
        }
        return Math.min(1.0, covered / (double) payload);
    }

    public long frameOffset(int index) {
        return offsets[index];
    }

    public int frameLength(int index) {
        return lengths[index];
    }

    /** Index of the first frame that starts at or after {@code millis}, clamped to the file. */
    public int frameAt(long millis) {
        if (frameCount() == 0 || millis <= 0) {
            return 0;
        }
        double perFrame = durationMs() / (double) frameCount();
        int index = perFrame <= 0 ? 0 : (int) Math.round(millis / perFrame);
        return Math.max(0, Math.min(frameCount(), index));
    }

    /** Start time of a frame in milliseconds; {@code frameCount()} returns the file duration. */
    public long frameStartMs(int index) {
        if (frameCount() == 0) {
            return 0;
        }
        double perFrame = durationMs() / (double) frameCount();
        return Math.round(Math.max(0, Math.min(frameCount(), index)) * perFrame);
    }

    /**
     * Reads the frame table of {@code file}. Frames with an unreadable header are skipped by resynchronising on the
     * next {@code 0xFF} byte, which is what every player does with slightly damaged downloads.
     */
    public static Mp3FrameIndex read(Path file) throws IOException {
        List<Long> offsetList = new ArrayList<>();
        List<Integer> lengthList = new ArrayList<>();
        int sampleRate = 0;
        int channels = 2;
        long samplesPerFrameTotal = 0;
        long audioStart = -1;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long position = skipId3(raf);
            long size = raf.length();
            byte[] header = new byte[4];
            while (position + 4 <= size) {
                raf.seek(position);
                raf.readFully(header);
                Frame frame = parseHeader(header);
                if (frame == null) {
                    position++;
                    continue;
                }
                if (position + frame.length > size) {
                    break;
                }
                if (offsetList.isEmpty() && isVbrHeaderFrame(raf, position, frame.length)) {
                    // Xing/Info/VBRI metadata, not audio: it describes the whole original file, so copying it into
                    // a clip would make every player believe the clip is as long as its source (3.35.3).
                    position += frame.length;
                    continue;
                }
                if (audioStart < 0) {
                    audioStart = position;
                    sampleRate = frame.sampleRate;
                    channels = frame.channels;
                }
                offsetList.add(position);
                lengthList.add(frame.length);
                samplesPerFrameTotal += frame.samples;
                position += frame.length;
            }
        }
        long[] offsets = new long[offsetList.size()];
        int[] lengths = new int[lengthList.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = offsetList.get(i);
            lengths[i] = lengthList.get(i);
        }
        if (offsets.length == 0) {
            throw new IOException("\"" + file.getFileName() + "\" does not contain MP3 audio frames.");
        }
        return new Mp3FrameIndex(offsets, lengths, sampleRate, channels, samplesPerFrameTotal,
                Math.max(0, audioStart), Files.size(file));
    }

    /**
     * True when the frame at {@code offset} is a VBR header frame (LAME/Xing {@code Xing} or {@code Info}, or
     * Fraunhofer {@code VBRI}) instead of audio. Such a frame holds the frame count, byte count and seek table of
     * the file it was written for; it sits in the side-information area right behind the frame header.
     */
    private static boolean isVbrHeaderFrame(RandomAccessFile raf, long offset, int frameLength) throws IOException {
        int length = Math.min(frameLength, 64);
        if (length < 8) {
            return false;
        }
        byte[] body = new byte[length];
        raf.seek(offset);
        raf.readFully(body);
        for (int at = 4; at <= length - 4; at++) {
            if (matches(body, at, "Xing") || matches(body, at, "Info") || matches(body, at, "VBRI")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            if (data[offset + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** True if the file starts with an ID3v2 tag or an MPEG frame header. */    public static boolean looksLikeMp3(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] start = in.readNBytes(3);
            if (start.length < 3) {
                return false;
            }
            if (start[0] == 'I' && start[1] == 'D' && start[2] == '3') {
                return true;
            }
            return (start[0] & 0xFF) == 0xFF && (start[1] & 0xE0) == 0xE0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Size of a leading ID3v2 tag, so the audio frames can be found behind it. */
    private static long skipId3(RandomAccessFile raf) throws IOException {
        if (raf.length() < 10) {
            return 0;
        }
        raf.seek(0);
        byte[] tag = new byte[10];
        raf.readFully(tag);
        if (tag[0] != 'I' || tag[1] != 'D' || tag[2] != '3') {
            return 0;
        }
        // Syncsafe integer: 7 bits per byte.
        long size = ((tag[6] & 0x7FL) << 21) | ((tag[7] & 0x7FL) << 14) | ((tag[8] & 0x7FL) << 7) | (tag[9] & 0x7FL);
        boolean footer = (tag[5] & 0x10) != 0;
        return Math.min(raf.length(), 10 + size + (footer ? 10 : 0));
    }

    private record Frame(int length, int samples, int sampleRate, int channels) {
    }

    /** Decodes one 4-byte MPEG audio header, or {@code null} when these bytes are not a valid header. */
    private static Frame parseHeader(byte[] header) {
        int b0 = header[0] & 0xFF;
        int b1 = header[1] & 0xFF;
        int b2 = header[2] & 0xFF;
        int b3 = header[3] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }
        int versionBits = (b1 >> 3) & 0x03; // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
        int layerBits = (b1 >> 1) & 0x03;   // 1 = Layer III, 2 = Layer II, 3 = Layer I
        if (versionBits == 1 || layerBits == 0) {
            return null;
        }
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null;
        }
        boolean mpeg1 = versionBits == 3;
        int layer = 4 - layerBits; // 1, 2 or 3
        int bitrateRow = (mpeg1 ? 3 : 0) + (3 - layer);
        int bitrate = BITRATES[bitrateRow][bitrateIndex] * 1000;
        int sampleRate = SAMPLE_RATES[versionBits][sampleRateIndex];
        if (bitrate <= 0 || sampleRate <= 0) {
            return null;
        }
        int padding = (b2 >> 1) & 0x01;
        int samples = layer == 1 ? 384 : (layer == 2 || mpeg1 ? 1152 : 576);
        int length = layer == 1
                ? (12 * bitrate / sampleRate + padding) * 4
                : samples / 8 * bitrate / sampleRate + padding;
        if (length <= 4) {
            return null;
        }
        int channels = ((b3 >> 6) & 0x03) == 3 ? 1 : 2;
        return new Frame(length, samples, sampleRate, channels);
    }
}
