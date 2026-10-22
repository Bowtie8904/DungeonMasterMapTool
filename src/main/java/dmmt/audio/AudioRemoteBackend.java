package dmmt.audio;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Owner-thread bridge used by the existing audio UI while controlling another machine. */
public interface AudioRemoteBackend {
    CompletableFuture<JsonNode> command(String action, Object... arguments);
    CompletableFuture<JsonNode> query(String action, Object... arguments);
    CompletableFuture<JsonNode> upload(Path source, AudioKind kind, String categoryId, String folderCategory);
    CompletableFuture<WaveformPeaks> waveform(String trackId, int frameMs);
    default long revision() { return -1; }
    default void atRevision(long revision, Runnable edit) { edit.run(); }
    default CompletableFuture<JsonNode> commandAt(long revision, String action, Object... arguments) {
        return command(action, arguments);
    }
}
