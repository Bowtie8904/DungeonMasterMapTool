package dmmt.audio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One audio file in the library: a music track of a category, or a sound effect loop (3.35.1). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioTrack {
    public static final String DEFAULT_EFFECT_COLOR = "#7BC67E";
    /** Ikonli description of the default sound effect icon, see {@link dmmt.ui.AudioIcons}. */
    public static final String DEFAULT_EFFECT_ICON = "mdi2w-waveform";

    @Builder.Default
    private String id = java.util.UUID.randomUUID().toString();
    /** Display name; independent of the file name and freely renameable. */
    @Builder.Default
    private String name = "";
    /** File name inside the library's {@code files/} folder. */
    @Builder.Default
    private String file = "";
    @Builder.Default
    private AudioKind kind = AudioKind.MUSIC;
    /** Category of a music track; ignored for sound effects. */
    @Builder.Default
    private String categoryId = AudioCategory.UNCATEGORISED_ID;
    /** Length in milliseconds, 0 when it could not be determined. */
    @Builder.Default
    private long durationMs = 0;
    /** Name of the imported file without extension, for the library view. */
    private String originalFileName;
    /** Set on clips cut out of a longer track (3.35.3); informational only, the clip is a standalone file. */
    private String sourceTrackId;
    private long sourceStartMs;
    private long sourceEndMs;
    @Builder.Default
    private long addedAt = System.currentTimeMillis();
    /**
     * Colour of a sound effect's overlay button, {@code #RRGGBB}; music tracks use the colour of their category
     * (3.35.2).
     */
    @Builder.Default
    private String color = DEFAULT_EFFECT_COLOR;
    /** Ikonli icon description of a sound effect, see {@link dmmt.ui.AudioIcons}. */
    @Builder.Default
    private String icon = DEFAULT_EFFECT_ICON;
    /** Hidden sound effects keep working but are left out of the audio overlay (3.35.2). */
    @Builder.Default
    private boolean hidden = false;
    /** Measured integrated loudness; {@code null} for legacy entries and digital silence. */
    private Double loudnessLufs;
    /** Measured sample peak in dBFS; {@code null} when the file is silent or has not been analyzed. */
    private Double samplePeakDbfs;
    /** Configured sample-peak ceiling in dBFS. */
    private Double peakCeilingDbfs;
    /** Remaining sample-peak headroom at the original level, in decibels. */
    private Double peakHeadroomDb;
    /** Automatic absolute gain relative to the original audio. */
    @Builder.Default
    private double autoGainDb = 0;
    /** Greatest peak-safe absolute gain relative to the original audio. */
    @Builder.Default
    private double maxGainDb = 0;
    /** Decoded PCM format used to prepare peak-safe and limited playback sources. */
    @Builder.Default
    private int audioChannels = 0;
    @Builder.Default
    private int audioSampleRate = 0;
    /** Optional absolute gain override; {@code null} selects {@link #autoGainDb}. */
    private Double gainOverrideDb;
    /** Bare PCM copy name, resolved in {@code files/playback/} or {@code files/limited/} by the service. */
    private String playbackFile;
    /** Gain already baked into {@code playbackFile}, relative to the original. */
    @Builder.Default
    private double playbackGainDb = 0;
    /** Bare copy name in {@code files/playback/}, used for automatic gain or a peak-safe override. */
    private String peakSafePlaybackFile;
    /** Gain already baked into {@code peakSafePlaybackFile}, relative to the original. */
    @Builder.Default
    private double peakSafePlaybackGainDb = 0;

    public boolean isMusic() {
        return kind == AudioKind.MUSIC;
    }

    public double effectiveGainDb() {
        return gainOverrideDb == null ? autoGainDb : gainOverrideDb;
    }

    public double maximumGainDb() {
        return maxGainDb;
    }

    /** Manual override range is independent of measured peak-safe headroom. */
    public double manualMaximumGainDb() {
        return AudioLibraryService.MANUAL_MAXIMUM_GAIN_DB;
    }

    public boolean isLoudnessAnalyzed() {
        return peakCeilingDbfs != null;
    }

    /**
     * JavaFX volume multiplier for {@link #getPlaybackFile()}: {@code 10^((effectiveGainDb - playbackGainDb) / 20)}
     * clamped to 0..1, because the prepared copy already contains {@code playbackGainDb}.
     */
    public double playbackVolumeFactor() {
        return playbackVolumeFactor(playbackGainDb);
    }

    /** Volume multiplier when a caller still holds a voice prepared with an earlier baked gain. */
    public double playbackVolumeFactor(double bakedGainDb) {
        double factor = Math.pow(10, (effectiveGainDb() - bakedGainDb) / 20);
        return Double.isFinite(factor) ? Math.max(0, Math.min(1, factor)) : 0;
    }

    /** {@code 3:07}, or {@code -} when the length is unknown. */
    public String durationText() {
        return formatDuration(durationMs);
    }

    /** {@code h:mm:ss} / {@code m:ss} for a duration in milliseconds; {@code -} for 0 or negative values. */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "-";
        }
        long totalSeconds = Math.round(millis / 1000.0);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
