package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts project-relative map and token artwork references to wire-safe content hashes and
 * materializes those references into an independent local cache.
 */
public final class RemoteProjectAssets
{
    private static final Log LOG = Log.of(RemoteProjectAssets.class);
    public static final String ASSET_REFERENCE_PREFIX = "asset:";

    private static final Pattern ASSET_REFERENCE =
            Pattern.compile("^asset:([0-9a-f]{64})$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RemoteAssetStore store;

    public RemoteProjectAssets(RemoteAssetStore store)
    {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Copies a project into a wire snapshot without modifying the project file. References must
     * resolve beneath the project directory. External/runtime references must be explicitly
     * allow-listed with the overload that accepts {@code explicitlyPublishedReferences}.
     */
    public ProjectSnapshot packageProject(Path projectFile) throws IOException
    {
        return packageProject(projectFile, Map.of());
    }

    /**
     * Copies a project into a wire snapshot. The map is a host-side allow-list from the exact
     * reference text in the project to a host-selected file; these paths are never serialized.
     */
    public ProjectSnapshot packageProject(Path projectFile, Map<String, Path> explicitlyPublishedReferences)
            throws IOException
    {
        Objects.requireNonNull(projectFile, "projectFile");
        Objects.requireNonNull(explicitlyPublishedReferences, "explicitlyPublishedReferences");
        Path source = projectFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Project file is missing or is not a regular file.");
        Path projectRoot = source.getParent().toRealPath();
        return packageProject(Files.readString(source), projectRoot, explicitlyPublishedReferences);
    }

    /**
     * Packages an in-memory project copy, including runtime token artwork references. Any reference
     * that is not project-relative must map exactly to a host-selected path in the explicit allow-list.
     */
    public ProjectSnapshot packageProject(String projectJson, Path projectDirectory,
                                          Map<String, Path> explicitlyPublishedReferences) throws IOException
    {
        Objects.requireNonNull(projectJson, "projectJson");
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(explicitlyPublishedReferences, "explicitlyPublishedReferences");
        Path projectRoot = projectDirectory.toAbsolutePath().normalize().toRealPath();
        ObjectNode project = MAPPER.readValue(projectJson, ObjectNode.class);
        TreeMap<String, RemoteAssetStore.AssetDescriptor> descriptors = new TreeMap<>();
        rewriteReferences(project, projectRoot, explicitlyPublishedReferences, descriptors, false, null);
        String packagedProjectJson = MAPPER.writeValueAsString(project);
        validatePortableProject(packagedProjectJson);
        LOG.debug("Packaged project snapshot with {} asset(s)", descriptors.size());
        return new ProjectSnapshot(packagedProjectJson, List.copyOf(descriptors.values()));
    }

    /**
     * Publishes a host-selected dependency independently of project path resolution.
     */
    public RemoteAssetStore.AssetDescriptor publishRequiredAsset(Path selectedHostFile) throws IOException
    {
        return store.publishRequiredAsset(selectedHostFile);
    }

    public static String referenceFor(RemoteAssetStore.AssetDescriptor descriptor)
    {
        Objects.requireNonNull(descriptor, "descriptor");
        return reference(descriptor.sha256());
    }

    /**
     * Verifies that every recognized asset field is either absent/blank or a hash-only portable reference.
     */
    public static void validatePortableProject(String projectJson) throws IOException
    {
        ObjectNode project = MAPPER.readValue(projectJson, ObjectNode.class);
        validateReferenceFields(project);
    }

    /**
     * Resolves a portable project against assets already present in this local store.
     */
    public MaterializedProject materializeProject(String portableProjectJson, Path cacheDirectory) throws IOException
    {
        ObjectNode project = MAPPER.readValue(portableProjectJson, ObjectNode.class);
        validateReferenceFields(project);
        TreeMap<String, RemoteAssetStore.AssetDescriptor> descriptors = new TreeMap<>();
        collectDescriptors(project, descriptors);
        return materializeProject(new ProjectSnapshot(MAPPER.writeValueAsString(project),
                                                        List.copyOf(descriptors.values())), cacheDirectory);
    }

    /**
     * Materializes a snapshot into a local hash cache and returns a rewritten JSON copy. It does
     * not modify the source snapshot or any live project object.
     */
    public MaterializedProject materializeProject(ProjectSnapshot snapshot, Path cacheDirectory) throws IOException
    {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        validatePortableProject(snapshot.projectJson());
        ObjectNode project = MAPPER.readValue(snapshot.projectJson(), ObjectNode.class);
        Map<String, RemoteAssetStore.AssetDescriptor> manifest = new HashMap<>();
        for (RemoteAssetStore.AssetDescriptor descriptor : snapshot.assets())
        {
            RemoteAssetStore.AssetDescriptor old = manifest.putIfAbsent(descriptor.sha256(), descriptor);
            if (old != null && !old.equals(descriptor))
                throw new IOException("Asset manifest contains conflicting descriptors.");
        }
        Map<String, Path> materialized = new HashMap<>();
        rewriteReferences(project, null, Map.of(), new TreeMap<>(), true, new MaterializationContext()
        {
            @Override
            Path resolve(String reference) throws IOException
            {
                AssetReference assetReference = parseReference(reference);
                RemoteAssetStore.AssetDescriptor descriptor = manifest.get(assetReference.sha256());
                if (descriptor == null)
                    throw new IOException("Project references an asset missing from its manifest.");
                RemoteAssetStore.AssetDescriptor actual = store.descriptor(assetReference.sha256());
                if (actual.size() != descriptor.size())
                    throw new IOException("Asset size does not match its manifest.");
                return materialized.computeIfAbsent(reference, key -> {
                    try
                    {
                        return store.materialize(assetReference.sha256(), cacheDirectory, "bin");
                    }
                    catch (IOException ex)
                    {
                        LOG.warn("Could not materialize remote asset {}", assetReference.sha256(), ex);
                        throw new MaterializationFailure(ex);
                    }
                });
            }
        });
        for (Map.Entry<String, RemoteAssetStore.AssetDescriptor> entry : manifest.entrySet())
        {
            if (!store.descriptor(entry.getKey()).equals(entry.getValue()))
                throw new IOException("Asset manifest does not match locally verified content.");
        }
        return new MaterializedProject(MAPPER.writeValueAsString(project), List.copyOf(materialized.values()));
    }

    private void rewriteReferences(ObjectNode project, Path projectRoot, Map<String, Path> explicitReferences,
                                   Map<String, RemoteAssetStore.AssetDescriptor> descriptors,
                                   boolean materialize, MaterializationContext context) throws IOException
    {
        ObjectNode map = object(project.get("map"));
        if (map != null)
            rewriteField(map, "imagePath", projectRoot, explicitReferences, descriptors, materialize, context, false);

        JsonNode layersNode = project.get("imageLayers");
        if (layersNode != null && layersNode.isArray())
        {
            for (JsonNode layerNode : layersNode)
            {
                if (layerNode instanceof ObjectNode layer)
                    rewriteField(layer, "path", projectRoot, explicitReferences, descriptors, materialize, context,
                                 false);
            }
        }

        JsonNode definitionsNode = project.get("tokenDefinitions");
        if (definitionsNode != null && definitionsNode.isObject())
        {
            var definitions = definitionsNode.fields();
            while (definitions.hasNext())
            {
                JsonNode tokenNode = definitions.next().getValue();
                if (tokenNode instanceof ObjectNode token)
                {
                    rewriteField(token, "sourceImage", projectRoot, explicitReferences, descriptors, materialize,
                                 context, true);
                    rewriteField(token, "image", projectRoot, explicitReferences, descriptors, materialize, context,
                                 true);
                }
            }
        }
    }

    private void rewriteField(ObjectNode object, String field, Path projectRoot, Map<String, Path> explicitReferences,
                              Map<String, RemoteAssetStore.AssetDescriptor> descriptors, boolean materialize,
                              MaterializationContext context, boolean tokenArtwork) throws IOException
    {
        JsonNode node = object.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank())
            return;
        String reference = node.asText();
        String replacement;
        if (materialize)
        {
            Path localPath;
            try
            {
                localPath = context.resolve(reference);
            }
            catch (MaterializationFailure ex)
            {
                throw ex.getCause();
            }
            replacement = tokenArtwork ? localPath.toUri().toString() : localPath.toString();
        }
        else
        {
            AssetReference existing = tryParseReference(reference);
            RemoteAssetStore.AssetDescriptor descriptor;
            if (existing != null)
            {
                descriptor = store.descriptor(existing.sha256());
            }
            else
            {
                Path asset = explicitReferences.get(reference);
                if (asset == null)
                    asset = resolveProjectReference(projectRoot, reference);
                descriptor = store.publishRequiredAsset(asset);
            }
            descriptors.put(descriptor.sha256(), descriptor);
            replacement = reference(descriptor.sha256());
        }
        object.put(field, replacement);
    }

    private static Path resolveProjectReference(Path projectRoot, String reference) throws IOException
    {
        if (reference.startsWith(ASSET_REFERENCE_PREFIX))
            throw new IOException("Invalid content-addressed project reference.");
        Path relative;
        try
        {
            relative = Path.of(reference);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("Invalid project asset reference '{}'", reference, ex);
            throw new IOException("Invalid project asset reference.", ex);
        }
        if (relative.isAbsolute())
            throw new IOException("Absolute project asset paths require explicit host publication.");
        for (Path component : relative)
        {
            if (component.toString().equals(".."))
                throw new IOException("Project asset path escapes the project directory.");
        }
        Path candidate = projectRoot.resolve(relative).normalize();
        if (!candidate.startsWith(projectRoot) || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Project asset is missing or outside the project directory.");
        Path real = candidate.toRealPath();
        if (!real.startsWith(projectRoot))
            throw new IOException("Project asset path escapes through a symbolic link.");
        return real;
    }

    private static ObjectNode object(JsonNode node)
    {
        return node instanceof ObjectNode object ? object : null;
    }

    private static String reference(String hash)
    {
        return ASSET_REFERENCE_PREFIX + hash;
    }

    private static AssetReference tryParseReference(String reference) throws IOException
    {
        if (!reference.startsWith(ASSET_REFERENCE_PREFIX))
            return null;
        return parseReference(reference);
    }

    private static AssetReference parseReference(String reference) throws IOException
    {
        Matcher matcher = ASSET_REFERENCE.matcher(reference);
        if (!matcher.matches())
            throw new IOException("Invalid content-addressed project asset reference.");
        return new AssetReference(matcher.group(1));
    }

    private static void validateReferenceFields(ObjectNode project) throws IOException
    {
        JsonNode mapNode = project.get("map");
        if (mapNode != null && !mapNode.isNull() && !mapNode.isObject())
            throw new IOException("Project map data must be an object.");
        validateField(object(mapNode), "imagePath");
        JsonNode layers = project.get("imageLayers");
        if (layers != null && !layers.isNull())
        {
            if (!layers.isArray())
                throw new IOException("Project image layers must be an array.");
            for (JsonNode layer : layers)
            {
                if (!layer.isObject())
                    throw new IOException("Project image layer data must be an object.");
                validateField(object(layer), "path");
            }
        }
        JsonNode definitions = project.get("tokenDefinitions");
        if (definitions != null && !definitions.isNull())
        {
            if (!definitions.isObject())
                throw new IOException("Project token definitions must be an object.");
            var fields = definitions.fields();
            while (fields.hasNext())
            {
                JsonNode tokenNode = fields.next().getValue();
                if (!tokenNode.isObject())
                    throw new IOException("Token definition data must be an object.");
                ObjectNode token = object(tokenNode);
                validateField(token, "sourceImage");
                validateField(token, "image");
            }
        }
    }

    private static void validateField(ObjectNode object, String field) throws IOException
    {
        if (object == null || !object.has(field) || object.get(field).isNull())
            return;
        JsonNode value = object.get(field);
        if (!value.isTextual())
            throw new IOException("Project asset references must be strings.");
        String reference = value.asText();
        if (!reference.isBlank())
            parseReference(reference);
    }

    private void collectDescriptors(ObjectNode project,
                                    Map<String, RemoteAssetStore.AssetDescriptor> descriptors) throws IOException
    {
        collectField(project.get("map"), "imagePath", descriptors);
        JsonNode layers = project.get("imageLayers");
        if (layers != null && layers.isArray())
        {
            for (JsonNode layer : layers)
                collectField(layer, "path", descriptors);
        }
        JsonNode definitions = project.get("tokenDefinitions");
        if (definitions != null && definitions.isObject())
        {
            var fields = definitions.fields();
            while (fields.hasNext())
            {
                JsonNode token = fields.next().getValue();
                collectField(token, "sourceImage", descriptors);
                collectField(token, "image", descriptors);
            }
        }
    }

    private void collectField(JsonNode object, String field,
                              Map<String, RemoteAssetStore.AssetDescriptor> descriptors) throws IOException
    {
        if (object == null || !object.isObject())
            return;
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank())
            return;
        AssetReference reference = parseReference(value.asText());
        descriptors.put(reference.sha256(), store.descriptor(reference.sha256()));
    }

    public record ProjectSnapshot(String projectJson, List<RemoteAssetStore.AssetDescriptor> assets)
    {
        public ProjectSnapshot
        {
            Objects.requireNonNull(projectJson, "projectJson");
            assets = List.copyOf(assets);
        }
    }

    public record MaterializedProject(String projectJson, List<Path> cachedFiles)
    {
        public MaterializedProject
        {
            Objects.requireNonNull(projectJson, "projectJson");
            cachedFiles = List.copyOf(cachedFiles);
        }
    }

    private record AssetReference(String sha256)
    {
    }

    private abstract static class MaterializationContext
    {
        abstract Path resolve(String reference) throws IOException;
    }

    private static final class MaterializationFailure extends RuntimeException
    {
        private MaterializationFailure(IOException cause)
        {
            super(cause);
        }

        @Override
        public synchronized IOException getCause()
        {
            return (IOException)super.getCause();
        }
    }
}
