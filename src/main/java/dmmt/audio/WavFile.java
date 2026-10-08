package dmmt.audio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * The format and data chunk of a RIFF/WAVE file, read from its header only. Used to determine the length of an
 * imported WAV and to copy a sample range into a new WAV file without decoding (3.35.3).
 */
public record WavFile(int formatCode, int channels, int sampleRate, int bitsPerSample, int blockAlign,
                      long dataOffset, long dataLength, byte[] formatChunk) {

    public long durationMs() {
        long bytesPerSecond = (long) sampleRate * blockAlign;
        return bytesPerSecond <= 0 ? 0 : Math.round(dataLength * 1000.0 / bytesPerSecond);
    }

    /** Byte offset inside the data chunk of the given time, snapped down to a whole sample frame. */
    public long byteOffsetOf(long millis) {
        long bytesPerSecond = (long) sampleRate * blockAlign;
        long offset = Math.round(Math.max(0, millis) / 1000.0 * bytesPerSecond);
        if (blockAlign > 0) {
            offset -= offset % blockAlign;
        }
        return Math.max(0, Math.min(dataLength, offset));
    }

    public boolean isPcm() {
        return formatCode == 1 || formatCode == 3 || formatCode == 0xFFFE;
    }

    /** Reads the {@code fmt } and {@code data} chunks of a WAV file. */
    public static WavFile read(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] riff = new byte[12];
            if (raf.length() < 12) {
                throw new IOException("\"" + file.getFileName() + "\" is too short to be a WAV file.");
            }
            raf.readFully(riff);
            if (!tagIs(riff, 0, "RIFF") || !tagIs(riff, 8, "WAVE")) {
                throw new IOException("\"" + file.getFileName() + "\" is not a WAV file.");
            }
            byte[] formatChunk = null;
            int formatCode = 0;
            int channels = 0;
            int sampleRate = 0;
            int bits = 0;
            int blockAlign = 0;
            long position = 12;
            while (position + 8 <= raf.length()) {
                raf.seek(position);
                byte[] head = new byte[8];
                raf.readFully(head);
                String id = new String(head, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = readIntLe(head, 4) & 0xFFFFFFFFL;
                long body = position + 8;
                if ("fmt ".equals(id) && size >= 16) {
                    formatChunk = new byte[(int) Math.min(size, 64)];
                    raf.seek(body);
                    raf.readFully(formatChunk);
                    formatCode = readShortLe(formatChunk, 0);
                    channels = readShortLe(formatChunk, 2);
                    sampleRate = readIntLe(formatChunk, 4);
                    blockAlign = readShortLe(formatChunk, 12);
                    bits = readShortLe(formatChunk, 14);
                } else if ("data".equals(id)) {
                    long length = Math.min(size, raf.length() - body);
                    if (formatChunk == null) {
                        throw new IOException("\"" + file.getFileName() + "\" has no WAV format chunk.");
                    }
                    if (blockAlign <= 0) {
                        blockAlign = Math.max(1, channels * Math.max(8, bits) / 8);
                    }
                    return new WavFile(formatCode, channels, sampleRate, bits, blockAlign, body, length, formatChunk);
                }
                position = body + size + (size % 2); // chunks are word aligned
            }
            throw new IOException("\"" + file.getFileName() + "\" has no WAV data chunk.");
        }
    }

    public static boolean looksLikeWav(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 12) {
                return false;
            }
            byte[] riff = new byte[12];
            raf.readFully(riff);
            return tagIs(riff, 0, "RIFF") && tagIs(riff, 8, "WAVE");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean tagIs(byte[] data, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            if ((char) (data[offset + i] & 0xFF) != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static int readIntLe(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    static int readShortLe(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
