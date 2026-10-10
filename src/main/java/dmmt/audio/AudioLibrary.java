package dmmt.audio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Content of the audio library index file ({@code library.json}, 3.35.1). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioLibrary {
    @Builder.Default
    private int schemaVersion = AudioLibraryService.SCHEMA_VERSION;
    @Builder.Default
    private List<AudioCategory> categories = new ArrayList<>();
    @Builder.Default
    private List<AudioTrack> tracks = new ArrayList<>();
    /** Replaced prepared playback copies that could not be deleted yet (e.g. still open in a playing voice). */
    @Builder.Default
    private List<String> stalePlaybackFiles = new ArrayList<>();
    /** Retryable copy failures have no track yet because no managed source was published. */
    @Builder.Default
    private List<CopyFailure> copyFailures = new ArrayList<>();

    public record CopyFailure(String id, String source, AudioKind kind, String categoryId, String folderCategory,
                              boolean cancelled, String error) {
    }
}
