package dmmt.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds small but valid WAV and MP3 files, so the audio tests need no binary fixtures. */
public final class TestAudioFiles {
    /** MPEG1 Layer III, 128 kbit/s, 44100 Hz, stereo: 417 bytes and 1152 samples per frame. */
    public static final int MP3_FRAME_BYTES = 417;
    public static final double MP3_FRAME_MS = 1152 * 1000.0 / 44100.0;

    private TestAudioFiles() {
    }

    /** A 16-bit mono WAV of the given length holding a 220 Hz tone. */
    public static void writeWav(Path file, int durationMs, int sampleRate) throws IOException {
        writeWav(file, durationMs, sampleRate, 1.0);
    }

    /**
     * A 16-bit mono WAV where the sample value is the tone multiplied by {@code amplitude}; an amplitude of 0
     * produces digital silence.
     */
    public static void writeWav(Path file, int durationMs, int sampleRate, double amplitude) throws IOException {
        int frames = (int) Math.round(sampleRate * (durationMs / 1000.0));
        byte[] samples = new byte[frames * 2];
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double value = Math.sin(2 * Math.PI * 220 * i / sampleRate) * amplitude;
            buffer.putShort((short) Math.round(value * Short.MAX_VALUE));
        }
        writeWav(file, samples, sampleRate);
    }

    /** Wraps ready-made 16-bit mono PCM data in a WAV container. */
    public static void writeWav(Path file, byte[] pcm, int sampleRate) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        int byteRate = sampleRate * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcm.length);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            out.write(pcm);
        }
    }

    /** Writes interleaved 16-bit stereo PCM for loudness/channel-isolation tests. */
    public static void writeStereoWav(Path file, short[] left, short[] right, int sampleRate) throws IOException {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Both stereo channels must have the same number of samples.");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        ByteBuffer pcm = ByteBuffer.allocate(left.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < left.length; i++) {
            pcm.putShort(left[i]);
            pcm.putShort(right[i]);
        }
        byte[] samples = pcm.array();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + samples.length);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 2);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 4);
        header.putShort((short) 4);
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(samples.length);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            out.write(samples);
        }
    }

    /** An MP3 of whole 128 kbit/s frames; the audio data itself is filler, only the frame headers matter. */
    public static void writeMp3(Path file, int frames) throws IOException {
        writeMp3(file, frames, false);
    }

    /** As {@link #writeMp3(Path, int)}, optionally with a leading ID3v2 tag that has to be skipped. */
    public static void writeMp3(Path file, int frames, boolean withId3) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] id3 = withId3 ? id3Tag(64) : new byte[0];
        byte[] data = new byte[id3.length + frames * MP3_FRAME_BYTES];
        System.arraycopy(id3, 0, data, 0, id3.length);
        for (int frame = 0; frame < frames; frame++) {
            int at = id3.length + frame * MP3_FRAME_BYTES;
            data[at] = (byte) 0xFF;
            data[at + 1] = (byte) 0xFB;
            data[at + 2] = (byte) 0x90;
            data[at + 3] = (byte) 0x00;
            for (int i = 4; i < MP3_FRAME_BYTES; i++) {
                // Never 0xFF, so the resynchronisation cannot mistake payload for a frame header.
                data[at + i] = (byte) ((frame + i) % 0xF0);
            }
        }
        Files.write(file, data);
    }

    /**
     * An MP3 that starts with a LAME {@code Info} (Xing) header frame declaring {@code declaredFrames} frames, as
     * every downloaded MP3 does. The header describes the whole original file and must never end up in a clip.
     */
    public static void writeMp3WithXingHeader(Path file, int frames, int declaredFrames) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] header = new byte[MP3_FRAME_BYTES];
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xFB;
        header[2] = (byte) 0x90;
        header[3] = 0;
        // Side information of an MPEG1 stereo frame is 32 bytes, so the tag starts at 4 + 32.
        header[36] = 'I';
        header[37] = 'n';
        header[38] = 'f';
        header[39] = 'o';
        header[43] = 0x0F; // flags: frames, bytes, TOC, quality
        header[44] = (byte) (declaredFrames >>> 24);
        header[45] = (byte) (declaredFrames >>> 16);
        header[46] = (byte) (declaredFrames >>> 8);
        header[47] = (byte) declaredFrames;

        Path audio = file.resolveSibling(file.getFileName() + ".audio.tmp");
        writeMp3(audio, frames);
        byte[] body = Files.readAllBytes(audio);
        Files.delete(audio);
        byte[] data = new byte[header.length + body.length];
        System.arraycopy(header, 0, data, 0, header.length);
        System.arraycopy(body, 0, data, header.length, body.length);
        Files.write(file, data);
    }

    /**
     * A file that only looks like an MP3 by its extension: deterministic pseudo-random bytes, as produced by the
     * encrypted library formats of other audio tools. The MP3 parser finds a few accidental frame headers in it.
     */
    public static void writeEncryptedLookingMp3(Path file, int bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] data = new byte[bytes];
        new java.util.Random(4711).nextBytes(data);
        Files.write(file, data);
    }

    private static byte[] id3Tag(int payload) {
        byte[] tag = new byte[10 + payload];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[6] = (byte) ((payload >> 21) & 0x7F);
        tag[7] = (byte) ((payload >> 14) & 0x7F);
        tag[8] = (byte) ((payload >> 7) & 0x7F);
        tag[9] = (byte) (payload & 0x7F);
        return tag;
    }
}
