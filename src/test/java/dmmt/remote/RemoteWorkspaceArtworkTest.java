package dmmt.remote;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorkspaceArtworkTest {
    @TempDir
    Path directory;

    @Test
    void publishesRelativeMapAndTokenUriThenConvertsFromMemoryAfterSourceDeletion() throws Exception {
        Path source = Files.write(directory.resolve("map.png"), new byte[]{1, 2, 3});
        Path token = Files.write(directory.resolve("token.png"), new byte[]{4, 5, 6});
        Path file = directory.resolve("level.dmmap");
        var project = new DmProject();
        project.getMap().setImagePath("map.png");
        var definition = new DmProject.TokenDefinition();
        definition.setId("definition");
        definition.setImage(token.toUri().toString());
        definition.setSourceImage(token.toUri().toString());
        project.getTokenDefinitions().put(definition.getId(), definition);
        var artwork = artwork(new AtomicReference<>(file));
        var local = RemoteProjectDocument.capture(project);
        assertFalse(artwork.canCapture(local, file));
        var prepared = prepare(artwork, local, file);
        artwork.accept(prepared);
        Files.delete(source);
        Files.delete(token);
        assertTrue(artwork.canCapture(local, file));
        var portable = artwork.capture(project);
        portable.validatePortableAssets();
        assertEquals(2, artwork.assets(portable).size());
        assertEquals(prepared.document(), portable);
        var restored = artwork.materialize(portable, project.getViews().getDmCamera());
        assertTrue(Files.isRegularFile(Path.of(restored.getMap().getImagePath())));
        assertTrue(restored.getTokenDefinitions().get("definition").getImage().startsWith("file:"));
        assertEquals(portable, artwork.capture(restored));
        try (var authority = new RemoteWorkspaceAuthority(() -> restored, commit -> fail("Unexpected edit"), artwork)) {
            assertEquals(2, authority.state().assets().size());
        }
    }

    @Test
    void identicalRelativeNamesInDifferentMapDirectoriesNeverAlias() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.write(first.resolve("image.png"), new byte[]{1});
        Files.write(second.resolve("image.png"), new byte[]{2});
        var file = new AtomicReference<>(first.resolve("level.dmmap"));
        var artwork = artwork(file);
        var project = new DmProject();
        project.getMap().setImagePath("image.png");
        var local = RemoteProjectDocument.capture(project);
        artwork.accept(prepare(artwork, local, file.get()));
        var baseline = artwork.capture(project);
        file.set(second.resolve("level.dmmap"));
        assertFalse(artwork.canCapture(local, file.get()));
        artwork.accept(prepare(artwork, local, file.get()));
        assertNotEquals(baseline, artwork.capture(project));
        file.set(first.resolve("level.dmmap"));
        assertEquals(baseline, artwork.capture(project));
    }

    @Test
    void publishesUnicodeMapWithExistingSharedArtworkNamespaces() throws Exception {
        Path mapDirectory = Files.createDirectories(directory.resolve("der_\u00f6ltopf_taverne"));
        Path source = Files.write(mapDirectory.resolve("map-image.jpg"), new byte[]{1, 2, 3});
        Path map = mapDirectory.resolve("der_\u00f6ltopf_taverne.dmmap");
        Files.createDirectories(directory.resolve("cache").resolve("handouts"));
        Files.createDirectories(directory.resolve("cache").resolve("operations"));
        var project = new DmProject();
        project.getMap().setImagePath(source.getFileName().toString());
        var artwork = artwork(new AtomicReference<>(map));

        var prepared = prepare(artwork, RemoteProjectDocument.capture(project), map);
        artwork.accept(prepared);

        assertTrue(artwork.canCapture(RemoteProjectDocument.capture(project), map));
        assertEquals(1, prepared.assets().size());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(
                prepared.localFiles().values().iterator().next()));
    }

    @Test
    void missingArtworkFailureIdentifiesReferenceAndMapAndRetainsCause() throws Exception {
        Path map = directory.resolve("level.dmmap");
        var artwork = artwork(new AtomicReference<>(map));
        var project = new DmProject();
        project.getMap().setImagePath("missing-map.png");

        var failure = assertThrows(CompletionException.class,
                () -> prepare(artwork, RemoteProjectDocument.capture(project), map));
        var cause = assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("missing-map.png"));
        assertTrue(cause.getMessage().contains(map.toString()));
        assertInstanceOf(IOException.class, cause.getCause());
        assertFalse(artwork.canCapture(RemoteProjectDocument.capture(project), map));
    }

    @Test
    void cacheFailureStillIdentifiesOriginalArtworkReference() throws Exception {
        Path map = directory.resolve("level.dmmap");
        Files.write(directory.resolve("map.png"), new byte[]{1, 2, 3});
        Path namespace = Files.createDirectories(directory.resolve("cache").resolve("operations"));
        Files.writeString(namespace.resolve("unexpected.txt"), "invalid");
        var artwork = artwork(new AtomicReference<>(map));
        var project = new DmProject();
        project.getMap().setImagePath("map.png");

        var failure = assertThrows(CompletionException.class,
                () -> prepare(artwork, RemoteProjectDocument.capture(project), map));
        var cause = assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("map.png"));
        assertTrue(cause.getMessage().contains(map.toString()));
        assertTrue(cause.getMessage().contains("unexpected.txt"));
        assertInstanceOf(IOException.class, cause.getCause());
        assertFalse(artwork.canCapture(RemoteProjectDocument.capture(project), map));
    }

    @Test
    void rejectsEscapingPathsMissingUploadsAndOwnerThreadDiskAccess() throws Exception {
        Path map = Files.createDirectory(directory.resolve("map")).resolve("level.dmmap");
        Files.write(directory.resolve("outside.png"), new byte[]{1});
        var artwork = artwork(new AtomicReference<>(map));
        var project = new DmProject();
        project.getMap().setImagePath("..\\outside.png");
        var local = RemoteProjectDocument.capture(project);
        var failure = assertThrows(CompletionException.class, () -> prepare(artwork, local, map));
        assertInstanceOf(IOException.class, failure.getCause());
        assertThrows(IllegalStateException.class, () -> artwork.prepare(local, map));
        project.getMap().setImagePath("asset:" + "a".repeat(64));
        var missing = RemoteProjectDocument.capture(project);
        assertThrows(CompletionException.class, () -> prepare(artwork, missing, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteWorkspaceAuthority.State(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 0, 1, missing, java.util.Map.of()));
        var ownerFailure = CompletableFuture.supplyAsync(() ->
                assertThrows(IllegalStateException.class, () -> artwork.portable(local, map))).join();
        assertNotNull(ownerFailure);
    }

    private RemoteWorkspaceArtwork artwork(AtomicReference<Path> file) throws IOException {
        return new RemoteWorkspaceArtwork(new RemoteAssetStore(directory.resolve("store"), 1024, 8192, 4096),
                directory.resolve("cache"), file::get);
    }

    private static RemoteWorkspaceArtwork.Prepared prepare(RemoteWorkspaceArtwork artwork,
                                                           RemoteProjectDocument local, Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return artwork.prepare(local, file);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).join();
    }
}
