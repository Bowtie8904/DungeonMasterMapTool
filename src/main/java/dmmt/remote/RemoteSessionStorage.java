package dmmt.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Machine-local, quota-bounded session storage; never populated from peer filesystem paths. */
public record RemoteSessionStorage(Path directory, long maxAssetBytes, long maxStoredBytes, long maxStagingBytes) {
    public RemoteSessionStorage {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maxAssetBytes < 0 || maxStoredBytes < maxAssetBytes || maxStagingBytes < maxAssetBytes) {
            throw new IllegalArgumentException("Storage and staging must accommodate the per-asset limit.");
        }
    }

    public RemoteAssetStore open() throws IOException {
        return new RemoteAssetStore(directory.resolve("assets"), maxAssetBytes, maxStoredBytes, maxStagingBytes);
    }

    public Path cache() {
        return directory.resolve("artwork");
    }
}
