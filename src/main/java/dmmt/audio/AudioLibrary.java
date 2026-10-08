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
}
