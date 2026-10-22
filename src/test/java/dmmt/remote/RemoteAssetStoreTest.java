package dmmt.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAssetStoreTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void ingestsAndStreamsContentAddressedAssetsWithIntegrityVerification() throws Exception
    {
        byte[] content = "remote map bytes".getBytes(StandardCharsets.UTF_8);
        String hash = hash(content);
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);

        RemoteAssetStore.AssetDescriptor descriptor =
                store.ingest(new ByteArrayInputStream(content), content.length, hash);
        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        store.transferTo(hash, downloaded);

        assertEquals(new RemoteAssetStore.AssetDescriptor(hash, content.length), descriptor);
        assertTrue(store.contains(hash));
        assertArrayEquals(content, downloaded.toByteArray());
        assertEquals(descriptor, store.ingest(new ByteArrayInputStream(content), content.length, hash));
    }

    @Test
    void rejectsUnsafeHashesSizeMismatchAndQuotaExhaustion() throws Exception
    {
        byte[] bytes = new byte[]{1, 2, 3};
        String hash = hash(bytes);
        RemoteAssetStore store = store(temporary.resolve("store"), 3, 4, 3);

        assertThrows(IOException.class, () -> store.ingest(new ByteArrayInputStream(bytes), bytes.length, "../" + hash));
        assertThrows(IOException.class, () -> store.ingest(new ByteArrayInputStream(bytes), bytes.length - 1, hash));
        assertThrows(IOException.class, () -> store.ingest(new ByteArrayInputStream(bytes), bytes.length + 1, hash));
        store.ingest(new ByteArrayInputStream(bytes), bytes.length, hash);
        byte[] second = new byte[]{4, 5};
        assertThrows(IOException.class,
                     () -> store.ingest(new ByteArrayInputStream(second), second.length, hash(second)));
    }

    @Test
    void detectsMissingAndTamperedStoredAssets() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);
        byte[] content = "immutable".getBytes(StandardCharsets.UTF_8);
        String hash = hash(content);

        assertFalse(store.contains(hash));
        assertThrows(IOException.class, () -> store.descriptor(hash));
        store.ingest(new ByteArrayInputStream(content), content.length, hash);
        Path object = temporary.resolve("store").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
        Files.writeString(object, "tampered", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.descriptor(hash));
        assertThrows(IOException.class, () -> store.transferTo(hash, new ByteArrayOutputStream()));
    }

    @Test
    void rejectsProjectTraversalAndMissingReferencedAssets() throws Exception
    {
        Path projectDirectory = Files.createDirectories(temporary.resolve("project"));
        Path outside = temporary.resolve("outside.png");
        Files.write(outside, new byte[]{1});
        Path projectFile = projectDirectory.resolve("map.json");
        RemoteProjectAssets assets = new RemoteProjectAssets(store(temporary.resolve("store"), 1024, 4096, 2048));

        Files.writeString(projectFile, "{\"map\":{\"imagePath\":\"../outside.png\"}}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> assets.packageProject(projectFile));
        Files.writeString(projectFile, "{\"map\":{\"imagePath\":\"missing.png\"}}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> assets.packageProject(projectFile));
    }

    @Test
    void packagesMapLayersAndTokenArtworkAsHashesThenMaterializesIndependentCacheCopies() throws Exception
    {
        Path projectDirectory = Files.createDirectories(temporary.resolve("project"));
        Path mapImage = write(projectDirectory.resolve("assets/map/main.png"), "map-image");
        Path layerImage = write(projectDirectory.resolve("assets/layers/overlay.jpg"), "layer-image");
        Path tokenSource = write(projectDirectory.resolve("assets/tokens/source.png"), "token-source");
        Path tokenCrop = write(projectDirectory.resolve("assets/tokens/crop.png"), "token-crop");
        Path projectFile = projectDirectory.resolve("project.json");
        String original = """
                {
                  "map": {"imagePath": "assets/map/main.png"},
                  "imageLayers": [{"path": "assets/layers/overlay.jpg"}],
                  "tokenDefinitions": {
                    "goblin": {
                      "sourceImage": "assets/tokens/source.png",
                      "image": "assets/tokens/crop.png"
                    }
                  }
                }
                """;
        Files.writeString(projectFile, original, StandardCharsets.UTF_8);
        RemoteAssetStore store = store(temporary.resolve("host-store"), 1024, 8192, 4096);
        RemoteProjectAssets projectAssets = new RemoteProjectAssets(store);

        RemoteProjectAssets.ProjectSnapshot snapshot = projectAssets.packageProject(projectFile);
        JsonNode wireProject = MAPPER.readTree(snapshot.projectJson());
        assertTrue(wireProject.path("map").path("imagePath").asText().startsWith("asset:"));
        assertTrue(wireProject.path("imageLayers").get(0).path("path").asText().startsWith("asset:"));
        assertTrue(wireProject.path("tokenDefinitions").path("goblin").path("sourceImage").asText()
                           .startsWith("asset:"));
        assertTrue(wireProject.path("tokenDefinitions").path("goblin").path("image").asText()
                           .startsWith("asset:"));
        assertFalse(snapshot.projectJson().contains(projectDirectory.toString()));
        assertEquals(original, Files.readString(projectFile, StandardCharsets.UTF_8));
        assertEquals(4, snapshot.assets().size());

        Path clientCache = temporary.resolve("client-cache");
        RemoteProjectAssets.MaterializedProject materialized =
                projectAssets.materializeProject(snapshot, clientCache);
        JsonNode localProject = MAPPER.readTree(materialized.projectJson());
        Path cachedMap = Path.of(localProject.path("map").path("imagePath").asText());
        Path cachedLayer = Path.of(localProject.path("imageLayers").get(0).path("path").asText());
        Path cachedSource = Path.of(java.net.URI.create(localProject.path("tokenDefinitions").path("goblin")
                                                                .path("sourceImage").asText()));
        Path cachedCrop = Path.of(java.net.URI.create(localProject.path("tokenDefinitions").path("goblin")
                                                              .path("image").asText()));

        assertArrayEquals(Files.readAllBytes(mapImage), Files.readAllBytes(cachedMap));
        assertArrayEquals(Files.readAllBytes(layerImage), Files.readAllBytes(cachedLayer));
        assertArrayEquals(Files.readAllBytes(tokenSource), Files.readAllBytes(cachedSource));
        assertArrayEquals(Files.readAllBytes(tokenCrop), Files.readAllBytes(cachedCrop));
        assertEquals(4, materialized.cachedFiles().size());
        assertEquals(original, Files.readString(projectFile, StandardCharsets.UTF_8));
    }

    @Test
    void supportsExplicitHostPublicationForAnExternalProjectDependency() throws Exception
    {
        Path projectDirectory = Files.createDirectories(temporary.resolve("project"));
        Path external = write(temporary.resolve("selected-host-file.png"), "explicit");
        Path projectFile = projectDirectory.resolve("project.json");
        Files.writeString(projectFile, "{\"map\":{\"imagePath\":\"host-selected.png\"}}", StandardCharsets.UTF_8);
        RemoteProjectAssets assets = new RemoteProjectAssets(store(temporary.resolve("store"), 1024, 4096, 2048));

        RemoteProjectAssets.ProjectSnapshot snapshot =
                assets.packageProject(projectFile, Map.of("host-selected.png", external));

        assertTrue(snapshot.projectJson().contains("asset:" + hash(Files.readAllBytes(external))));
        assertFalse(snapshot.projectJson().contains(external.toString()));
    }

    @Test
    void materializesWorkspaceAndPreviewAssetsAlongsideSharedCacheNamespaces() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);
        Path cache = temporary.resolve("artwork");
        Files.createDirectories(cache.resolve("handouts"));
        Files.createDirectories(cache.resolve("operations").resolve("previews"));
        Path source = write(temporary.resolve("map.png"), "workspace");
        var asset = store.publishRequiredAsset(source);
        Path nested = store.materialize(asset.sha256(), cache.resolve("operations").resolve("previews"), "png");
        Path workspace = store.materialize(asset.sha256(), cache, "png");

        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(nested));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(workspace));
        assertEquals(workspace, store.materialize(asset.sha256(), cache, "png"));
    }

    @Test
    void countsNestedCacheFilesAgainstQuota() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 3, 5, 3);
        byte[] bytes = {1, 2, 3};
        var asset = store.ingest(new ByteArrayInputStream(bytes), bytes.length, hash(bytes));
        Path cache = temporary.resolve("artwork");
        Path nested = store.materialize(asset.sha256(), cache.resolve("handouts"), "png");

        var failure = assertThrows(IOException.class, () -> store.materialize(asset.sha256(), cache, "png"));
        assertTrue(failure.getMessage().contains("quota"));
        assertArrayEquals(bytes, Files.readAllBytes(nested));
        assertFalse(Files.exists(cache.resolve(asset.sha256() + ".png")));
    }

    @Test
    void rejectsMalformedNestedCacheFiles() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);
        var asset = store.publishRequiredAsset(write(temporary.resolve("map.png"), "map"));
        Path cache = temporary.resolve("artwork");
        write(cache.resolve("operations").resolve("unexpected.txt"), "invalid");

        var failure = assertThrows(IOException.class, () -> store.materialize(asset.sha256(), cache, "png"));
        assertTrue(failure.getMessage().contains("unexpected.txt"));
        assertFalse(Files.exists(cache.resolve(asset.sha256() + ".png")));
    }

    @Test
    void rejectsNestedCacheSymbolicLinksWhenSupported() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);
        var asset = store.publishRequiredAsset(write(temporary.resolve("map.png"), "map"));
        Path cache = Files.createDirectories(temporary.resolve("artwork"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        try
        {
            Files.createSymbolicLink(cache.resolve("handouts"), outside);
        }
        catch (UnsupportedOperationException | IOException | SecurityException ex)
        {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + ex.getMessage());
        }

        assertThrows(IOException.class, () -> store.materialize(asset.sha256(), cache, "png"));
        try (var entries = Files.list(outside))
        {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void publicationFailureIdentifiesSelectedSourceAndRetainsCause() throws Exception
    {
        RemoteAssetStore store = store(temporary.resolve("store"), 1024, 4096, 2048);
        Path missing = temporary.resolve("missing-map.png");

        var failure = assertThrows(IOException.class, () -> store.publishRequiredAsset(missing));
        assertTrue(failure.getMessage().contains(missing.toString()));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void packagesOpaqueRuntimeTokenArtworkThroughExplicitHostSelection() throws Exception
    {
        Path projectDirectory = Files.createDirectories(temporary.resolve("project"));
        Path runtimeImage = write(temporary.resolve("runtime-token.png"), "runtime-token");
        String runtimeReference = runtimeImage.toUri().toString();
        String projectJson = MAPPER.writeValueAsString(Map.of(
                "tokenDefinitions", Map.of("runtime", Map.of("sourceImage", runtimeReference, "image", ""))));
        RemoteProjectAssets assets = new RemoteProjectAssets(store(temporary.resolve("store"), 1024, 4096, 2048));

        RemoteProjectAssets.ProjectSnapshot snapshot = assets.packageProject(
                projectJson, projectDirectory, Map.of(runtimeReference, runtimeImage));

        JsonNode packaged = MAPPER.readTree(snapshot.projectJson());
        assertTrue(packaged.path("tokenDefinitions").path("runtime").path("sourceImage").asText()
                           .matches("asset:[0-9a-f]{64}"));
        assertFalse(snapshot.projectJson().contains(runtimeImage.toString()));
    }

    @Test
    void portableValidationRejectsLocalPathsAndMalformedHashReferences() throws Exception
    {
        String validHash = "a".repeat(64);
        RemoteProjectAssets.validatePortableProject("""
                {"map":{"imagePath":"asset:%s"},
                 "imageLayers":[{"path":"asset:%s"}],
                 "tokenDefinitions":{"x":{"sourceImage":"asset:%s","image":"asset:%s"}}}
                """.formatted(validHash, validHash, validHash, validHash));
        for (String value : new String[]{"C:\\\\maps\\\\secret.png", "/home/user/map.png", "file:///secret.png",
                                         "asset:../../secret", "asset:" + "A".repeat(64)})
        {
            String project = "{\"map\":{\"imagePath\":\"" + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"}}";
            assertThrows(IOException.class, () -> RemoteProjectAssets.validatePortableProject(project), value);
            String layerProject = "{\"imageLayers\":[{\"path\":\"" + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"}]}";
            assertThrows(IOException.class, () -> RemoteProjectAssets.validatePortableProject(layerProject), value);
            String tokenProject = "{\"tokenDefinitions\":{\"x\":{\"sourceImage\":\""
                    + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}}";
            assertThrows(IOException.class, () -> RemoteProjectAssets.validatePortableProject(tokenProject), value);
        }
    }

    @Test
    void rejectsSymbolicLinkEscapesWhenSupported() throws Exception
    {
        Path projectDirectory = Files.createDirectories(temporary.resolve("project"));
        Path outside = write(temporary.resolve("outside.png"), "outside");
        Path link = projectDirectory.resolve("linked.png");
        try
        {
            Files.createSymbolicLink(link, outside);
        }
        catch (UnsupportedOperationException | IOException | SecurityException ex)
        {
            return;
        }
        Path projectFile = projectDirectory.resolve("project.json");
        Files.writeString(projectFile, "{\"map\":{\"imagePath\":\"linked.png\"}}", StandardCharsets.UTF_8);
        RemoteProjectAssets assets = new RemoteProjectAssets(store(temporary.resolve("store"), 1024, 4096, 2048));

        assertThrows(IOException.class, () -> assets.packageProject(projectFile));
    }

    @Test
    void rejectsSymbolicLinkInsideManagedStoreWhenSupported() throws Exception
    {
        Path root = temporary.resolve("store");
        RemoteAssetStore store = store(root, 1024, 4096, 2048);
        byte[] content = "outside".getBytes(StandardCharsets.UTF_8);
        String hash = hash(content);
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path prefix = root.resolve("objects").resolve(hash.substring(0, 2));
        try
        {
            Files.createSymbolicLink(prefix, outside);
        }
        catch (UnsupportedOperationException | IOException | SecurityException ex)
        {
            return;
        }

        assertThrows(IOException.class, () -> store.ingest(new ByteArrayInputStream(content), content.length, hash));
        try (var entries = Files.list(outside))
        {
            assertEquals(0, entries.count());
        }
    }

    private static Path write(Path path, String value) throws IOException
    {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static RemoteAssetStore store(Path root, long maxAsset, long maxStored, long maxStaging) throws IOException
    {
        return new RemoteAssetStore(root, maxAsset, maxStored, maxStaging);
    }

    private static String hash(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
