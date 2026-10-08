package dmmt.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cutting standalone clips out of a long recording (3.35.3). */
class AudioClipCutterTest {
    @TempDir
    Path dir;

    @Test
    void wavClipContainsExactlyTheSelectedRange() throws IOException {
        Path source = dir.resolve("long.wav");
        TestAudioFiles.writeWav(source, 10_000, 8000);
        Path target = dir.resolve("clip.wav");

        AudioClipCutter.Cut cut = AudioClipCutter.cut(source, 2_000, 5_000, target);

        assertEquals(2_000, cut.startMs());
        assertEquals(5_000, cut.endMs());
        WavFile clip = WavFile.read(target);
        assertEquals(3_000, clip.durationMs());
        assertEquals(8000, clip.sampleRate());
        assertEquals(1, clip.channels());
        assertEquals(44 + 3_000 * 8000 / 1000 * 2, Files.size(target));
    }

    @Test
    void wavClipHoldsTheSamplesOfTheSelectedRange() throws IOException {
        Path source = dir.resolve("ramp.wav");
        int sampleRate = 1000;
        byte[] pcm = new byte[sampleRate * 2 * 2];
        for (int i = 0; i < pcm.length / 2; i++) {
            // Each sample carries its own index, so the cut offset can be verified exactly.
            pcm[i * 2] = (byte) (i & 0xFF);
            pcm[i * 2 + 1] = (byte) ((i >> 8) & 0x7F);
        }
        TestAudioFiles.writeWav(source, pcm, sampleRate);
        Path target = dir.resolve("ramp-clip.wav");

        AudioClipCutter.cut(source, 500, 1500, target);

        byte[] clip = Files.readAllBytes(target);
        int firstSample = (clip[44] & 0xFF) | ((clip[45] & 0x7F) << 8);
        assertEquals(500, firstSample, "the clip must start at sample 500");
        assertEquals(44 + 1000 * 2, clip.length);
    }

    @Test
    void mp3ClipKeepsWholeFramesAndDropsTheId3Tag() throws IOException {
        Path source = dir.resolve("set.mp3");
        TestAudioFiles.writeMp3(source, 400, true);
        Path target = dir.resolve("song.mp3");

        AudioClipCutter.Cut cut = AudioClipCutter.cut(source, 2_000, 6_000, target);

        assertEquals(0, Files.size(target) % TestAudioFiles.MP3_FRAME_BYTES,
                "a clip must consist of whole MPEG frames");
        byte[] start = Files.readAllBytes(target);
        assertEquals((byte) 0xFF, start[0]);
        assertTrue(Math.abs(cut.startMs() - 2_000) <= 27, "start snapped to " + cut.startMs());
        assertTrue(Math.abs(cut.durationMs() - 4_000) <= 54, "length " + cut.durationMs());
        Mp3FrameIndex clip = Mp3FrameIndex.read(target);
        assertEquals(Files.size(target) / TestAudioFiles.MP3_FRAME_BYTES, clip.frameCount());
        assertTrue(Math.abs(clip.durationMs() - cut.durationMs()) <= 27);
    }

    @Test
    void theXingHeaderOfTheSourceIsNeverCopiedIntoAClip() throws IOException {
        Path source = dir.resolve("download.mp3");
        TestAudioFiles.writeMp3WithXingHeader(source, 400, 414832);
        Path target = dir.resolve("first-song.mp3");

        Mp3FrameIndex index = Mp3FrameIndex.read(source);
        assertEquals(400, index.frameCount(), "the Xing header frame is metadata, not audio");
        assertEquals(TestAudioFiles.MP3_FRAME_BYTES, index.audioStart());

        AudioClipCutter.Cut cut = AudioClipCutter.cut(source, 0, 4_000, target);

        byte[] clip = Files.readAllBytes(target);
        assertEquals(-1, new String(clip, 0, Math.min(200, clip.length),
                java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("Info"),
                "a clip must not declare the length of the file it was cut from");
        assertTrue(Math.abs(cut.durationMs() - 4_000) <= 54, "length " + cut.durationMs());
        assertTrue(Math.abs(Mp3FrameIndex.read(target).durationMs() - cut.durationMs()) <= 27);
    }

    @Test
    void theRangeIsClampedToTheFileAndMustNotBeEmpty() throws IOException {
        Path source = dir.resolve("short.wav");
        TestAudioFiles.writeWav(source, 1_000, 8000);

        AudioClipCutter.Cut cut = AudioClipCutter.cut(source, 500, 99_000, dir.resolve("tail.wav"));
        assertEquals(1_000, cut.endMs());

        assertThrows(IOException.class, () -> AudioClipCutter.cut(source, 400, 400, dir.resolve("empty.wav")));
        assertThrows(IOException.class, () -> AudioClipCutter.cut(source, 900, 100, dir.resolve("reversed.wav")));
    }

    @Test
    void aFailedCutLeavesNoHalfWrittenFile() throws IOException {
        Path source = dir.resolve("broken.wav");
        Files.writeString(source, "RIFFnot really a wave file");
        Path target = dir.resolve("never.wav");

        assertThrows(IOException.class, () -> AudioClipCutter.cut(source, 0, 1000, target));
        assertTrue(Files.notExists(target));
    }
}
