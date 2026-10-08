package dmmt.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Which audio files the library accepts (MP3 and WAV, 3.35.1) and how long they are. */
public final class AudioFormats {
    public static final List<String> EXTENSIONS = List.of("mp3", "wav");
    /** Description and glob patterns for file choosers. */
    public static final String FILTER_DESCRIPTION = "Audio files (*.mp3, *.wav)";
    public static final List<String> FILTER_PATTERNS = List.of("*.mp3", "*.MP3", "*.wav", "*.WAV");

    private AudioFormats() {
    }

    /** Lower-case extension without the dot, or an empty string. */
    public static String extensionOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** File name without its extension. */
    public static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    public static boolean isMp3(Path file) {
        return "mp3".equals(extensionOf(file));
    }

    public static boolean isWav(Path file) {
        return "wav".equals(extensionOf(file));
    }

    /** True when the extension is supported; the content is only checked when the file is actually read. */
    public static boolean isSupported(Path file) {
        return EXTENSIONS.contains(extensionOf(file));
    }

    /** Length of a supported file in milliseconds, 0 when it cannot be determined. */
    public static long durationMs(Path file) {
        try {
            if (isMp3(file)) {
                return Mp3FrameIndex.read(file).durationMs();
            }
            if (isWav(file)) {
                return WavFile.read(file).durationMs();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        return 0;
    }

    /**
     * Smallest share of an MP3 that must really consist of MPEG frames. Encrypted or damaged files only reach a few
     * percent by accident, a real MP3 is close to 1.0 even with tags at both ends.
     */
    private static final double MIN_MP3_COVERAGE = 0.5;

    /** Throws when the file is not a readable MP3 or WAV; used before copying an import into the library. */
    public static void validate(Path file) throws IOException {
        if (!isSupported(file)) {
            throw new IOException("\"" + file.getFileName() + "\" is not an MP3 or WAV file.");
        }
        if (isMp3(file)) {
            Mp3FrameIndex index = Mp3FrameIndex.read(file);
            if (index.frameCoverage() < MIN_MP3_COVERAGE) {
                throw new IOException("\"" + file.getFileName() + "\" has the extension .mp3 but does not contain MP3"
                        + " audio (only " + Math.round(index.frameCoverage() * 100) + "% of it looks like MP3 frames)."
                        + " It is damaged, or stored in the encrypted library format of another application -"
                        + " import the original audio file instead.");
            }
        } else {
            WavFile wav = WavFile.read(file);
            if (!wav.isPcm()) {
                throw new IOException("\"" + file.getFileName() + "\" uses a compressed WAV format that is not supported.");
            }
        }
    }
}
