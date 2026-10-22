package dmmt.remote;

import dmmt.log.Log;
import dmmt.model.DmProject;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Worker preparation and owner-thread conversion for the application's runtime artwork paths. */
public final class RemoteWorkspaceArtwork implements RemoteWorkspaceAuthority.ProjectCodec {
    public record Prepared(RemoteProjectDocument document, Map<String, String> published,
                           Map<String, Path> localFiles, Map<String, RemoteAssetStore.AssetDescriptor> assets) {
        public Prepared {
            Objects.requireNonNull(document, "document");
            published = Map.copyOf(published);
            localFiles = Map.copyOf(localFiles);
            assets = Map.copyOf(assets);
        }
    }

    private static final Log LOG = Log.of(RemoteWorkspaceArtwork.class);
    private final Thread owner = Thread.currentThread();
    private final RemoteAssetStore store;
    private final Path cache;
    private final java.util.function.Supplier<Path> currentFile;
    private final java.util.function.BiConsumer<DmProject, DmProject> completion;
    private final Map<String, String> published = new LinkedHashMap<>();
    private final Map<String, Path> localFiles = new LinkedHashMap<>();
    private final Map<String, RemoteAssetStore.AssetDescriptor> descriptors = new LinkedHashMap<>();

    public RemoteWorkspaceArtwork(RemoteAssetStore store, Path cache, java.util.function.Supplier<Path> currentFile) {
        this(store, cache, currentFile, (before, after) -> { });
    }

    public RemoteWorkspaceArtwork(RemoteAssetStore store, Path cache, java.util.function.Supplier<Path> currentFile,
                                  java.util.function.BiConsumer<DmProject, DmProject> completion) {
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.currentFile = Objects.requireNonNull(currentFile, "currentFile");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /** Worker only. The document and file are owner-captured snapshots, not live application objects. */
    public Prepared prepare(RemoteProjectDocument local, Path projectFile) throws IOException {
        if (Thread.currentThread() == owner) {
            throw new IllegalStateException("Workspace artwork IO must run away from the application owner thread.");
        }
        Map<String, String> references = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        Map<String, RemoteAssetStore.AssetDescriptor> assets = new LinkedHashMap<>();
        for (String reference : local.assetReferences()) {
            try {
                RemoteAssetStore.AssetDescriptor asset;
                if (reference.matches("asset:[0-9a-f]{64}")) {
                    asset = store.descriptor(reference.substring("asset:".length()));
                } else {
                    Path source = localPath(reference, projectFile);
                    asset = store.publishRequiredAsset(source);
                }
                String portable = RemoteProjectAssets.referenceFor(asset);
                Path file = store.materialize(asset.sha256(), cache, "png");
                references.put(key(reference, projectFile), portable);
                references.put(key(file.toString(), projectFile), portable);
                references.put(key(file.toUri().toString(), projectFile), portable);
                files.put(portable, file);
                assets.put(portable, asset);
                LOG.debug("Prepared workspace artwork {} ({} bytes)", portable, asset.size());
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("Could not prepare workspace artwork '{}'", reference, failure);
                throw new IOException("Could not prepare workspace artwork '" + reference + "'"
                        + (projectFile == null ? "" : " for map '" + projectFile + "'")
                        + ": " + failure.getMessage(), failure);
            }
        }
        var portable = local.rewriteAssets(reference -> references.get(key(reference, projectFile)));
        portable.validatePortableAssets();
        return new Prepared(portable, references, files, assets);
    }

    /** Worker only. Only hash references are accepted; uploaded content must already be verified in the store. */
    public Prepared prepareIncoming(RemoteProjectDocument portable) throws IOException {
        portable.validatePortableAssets();
        return prepare(portable, null);
    }

    public void accept(Prepared prepared) {
        requireOwner();
        published.putAll(prepared.published());
        localFiles.putAll(prepared.localFiles());
        descriptors.putAll(prepared.assets());
    }

    public boolean canCapture(RemoteProjectDocument local, Path projectFile) {
        requireOwner();
        return local.assetReferences().stream().allMatch(reference ->
                localFiles.containsKey(reference) || published.containsKey(key(reference, projectFile)));
    }

    public RemoteProjectDocument portable(RemoteProjectDocument local, Path projectFile) {
        requireOwner();
        return local.rewriteAssets(reference -> {
            if (localFiles.containsKey(reference)) {
                return reference;
            }
            String result = published.get(key(reference, projectFile));
            if (result == null) {
                throw new IllegalStateException("Workspace artwork has not finished preparation.");
            }
            return result;
        });
    }

    @Override
    public RemoteProjectDocument capture(DmProject project) {
        return portable(RemoteProjectDocument.capture(project), currentFile.get());
    }

    @Override
    public void complete(DmProject before, DmProject after) {
        requireOwner();
        completion.accept(before, after);
    }

    @Override
    public DmProject materialize(RemoteProjectDocument document, DmProject.CameraState dmCamera) throws IOException {
        requireOwner();
        document.validatePortableAssets();
        DmProject project = document.rewriteAssets(reference -> {
            Path file = localFiles.get(reference);
            if (file == null) {
                throw new IllegalArgumentException("Workspace artwork is not published in this session.");
            }
            return file.toString();
        }).materialize(dmCamera);
        for (var definition : project.getTokenDefinitions().values()) {
            if (definition.getImage() != null && !definition.getImage().isBlank()) {
                definition.setImage(Path.of(definition.getImage()).toUri().toString());
            }
            if (definition.getSourceImage() != null && !definition.getSourceImage().isBlank()) {
                definition.setSourceImage(Path.of(definition.getSourceImage()).toUri().toString());
            }
        }
        return project;
    }

    @Override
    public Map<String, RemoteAssetStore.AssetDescriptor> assets(RemoteProjectDocument document) {
        requireOwner();
        Map<String, RemoteAssetStore.AssetDescriptor> manifest = new LinkedHashMap<>();
        for (String reference : document.assetReferences()) {
            var descriptor = descriptors.get(reference);
            if (descriptor == null) {
                throw new IllegalStateException("Workspace artwork manifest is not ready.");
            }
            manifest.put(reference, descriptor);
        }
        return Map.copyOf(manifest);
    }

    private static String key(String reference, Path projectFile) {
        if (reference.startsWith("asset:") || reference.startsWith("file:") || Path.of(reference).isAbsolute()) {
            return reference;
        }
        return (projectFile == null ? "" : projectFile.toAbsolutePath().normalize().getParent().toString())
                + "\n" + reference;
    }

    private static Path localPath(String reference, Path projectFile) throws IOException {
        try {
            if (reference.startsWith("file:")) {
                return Path.of(URI.create(reference));
            }
            Path path = Path.of(reference);
            if (path.isAbsolute()) {
                return path;
            }
            if (projectFile == null || projectFile.toAbsolutePath().getParent() == null) {
                throw new IOException("Relative artwork cannot be published without a saved map directory.");
            }
            Path directory = projectFile.toAbsolutePath().normalize().getParent();
            Path resolved = directory.resolve(path).normalize();
            if (!resolved.startsWith(directory) || !resolved.toRealPath().startsWith(directory.toRealPath())) {
                throw new IOException("Relative workspace artwork escapes its map directory.");
            }
            return resolved;
        } catch (IllegalArgumentException invalid) {
            LOG.warn("Unsupported workspace artwork reference '{}'", reference, invalid);
            throw new IOException("Unsupported runtime workspace artwork reference.", invalid);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Workspace artwork conversion must run on the application owner thread.");
        }
    }
}
