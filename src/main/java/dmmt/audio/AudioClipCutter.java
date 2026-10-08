package dmmt.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the part of a long recording between two times into a new, standalone audio file (3.35.3).
 * <p>
 * MP3 ranges are copied frame by frame without decoding, so the clip has exactly the quality of the source and even
 * a two-hour file is cut in well under a second. WAV ranges copy the PCM bytes of the sample range and get a fresh
 * header. The source file is never modified; the result is written to a temporary file first and only then moved
 * into place, so an interrupted cut cannot leave a half-written clip in the library.
 */
public final class AudioClipCutter {
    /** Copy buffer; large enough to keep the copy sequential without holding a whole song in memory. */
    private static final int BUFFER = 1 << 16;

    private AudioClipCutter() {
    }

    /** The exact range that was written, after snapping to frame/sample boundaries. */
    public record Cut(long startMs, long endMs) {
        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    /**
     * Writes {@code [startMs, endMs)} of {@code source} to {@code target} (same format as the source).
     *
     * @throws IOException if the range is empty, the format is unsupported or the file cannot be read/written
     */
    public static Cut cut(Path source, long startMs, long endMs, Path target) throws IOException {
        if (endMs <= startMs) {
            throw new IOException("The selected range is empty.");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".cut-" + System.nanoTime() + ".tmp");
        try {
            Cut cut = AudioFormats.isMp3(source)
                    ? cutMp3(source, startMs, endMs, temp)
                    : cutWav(source, startMs, endMs, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return cut;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Copies whole MPEG frames; the range snaps to frame starts (at most ~26 ms off). */
    private static Cut cutMp3(Path source, long startMs, long endMs, Path target) throws IOException {
        Mp3FrameIndex index = Mp3FrameIndex.read(source);
        int firstFrame = index.frameAt(startMs);
        int lastFrame = Math.max(firstFrame + 1, index.frameAt(endMs));
        lastFrame = Math.min(lastFrame, index.frameCount());
        if (firstFrame >= index.frameCount()) {
            throw new IOException("The selected range starts after the end of the file.");
        }
        long from = index.frameOffset(firstFrame);
        long to = lastFrame >= index.frameCount()
                ? index.frameOffset(index.frameCount() - 1) + index.frameLength(index.frameCount() - 1)
                : index.frameOffset(lastFrame);
        try (RandomAccessFile in = new RandomAccessFile(source.toFile(), "r");
             OutputStream out = Files.newOutputStream(target)) {
            copyRange(in, from, to - from, out);
        }
        return new Cut(index.frameStartMs(firstFrame), index.frameStartMs(lastFrame));
    }

    /** Copies the PCM sample range and writes a new 44-byte canonical WAV header in front of it. */
    private static Cut cutWav(Path source, long startMs, long endMs, Path target) throws IOException {
        WavFile wav = WavFile.read(source);
        if (!wav.isPcm()) {
            throw new IOException("This WAV file uses a compressed format that cannot be cut.");
        }
        long from = wav.byteOffsetOf(startMs);
        long to = wav.byteOffsetOf(endMs);
        if (to <= from) {
            throw new IOException("The selected range is empty.");
        }
        long length = to - from;
        try (RandomAccessFile in = new RandomAccessFile(source.toFile(), "r");
             OutputStream out = Files.newOutputStream(target)) {
            out.write(header(wav, length));
            copyRange(in, wav.dataOffset() + from, length, out);
        }
        long bytesPerSecond = (long) wav.sampleRate() * wav.blockAlign();
        long startExact = Math.round(from * 1000.0 / bytesPerSecond);
        long endExact = Math.round(to * 1000.0 / bytesPerSecond);
        return new Cut(startExact, endExact);
    }

    private static byte[] header(WavFile wav, long dataLength) {
        byte[] format = wav.formatChunk();
        int formatSize = Math.min(format.length, 16);
        byte[] header = new byte[12 + 8 + formatSize + 8];
        int at = 0;
        at = writeTag(header, at, "RIFF");
        at = writeIntLe(header, at, (int) Math.min(Integer.MAX_VALUE, 4 + 8 + formatSize + 8 + dataLength));
        at = writeTag(header, at, "WAVE");
        at = writeTag(header, at, "fmt ");
        at = writeIntLe(header, at, formatSize);
        System.arraycopy(format, 0, header, at, formatSize);
        at += formatSize;
        at = writeTag(header, at, "data");
        writeIntLe(header, at, (int) Math.min(Integer.MAX_VALUE, dataLength));
        return header;
    }

    private static void copyRange(RandomAccessFile in, long offset, long length, OutputStream out) throws IOException {
        FileChannel channel = in.getChannel();
        channel.position(offset);
        byte[] buffer = new byte[BUFFER];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static int writeTag(byte[] target, int offset, String tag) {
        byte[] bytes = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
        return offset + bytes.length;
    }

    private static int writeIntLe(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
        return offset + 4;
    }
}
