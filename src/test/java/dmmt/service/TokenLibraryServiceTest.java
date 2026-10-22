package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenLibraryServiceTest
{
    @TempDir Path temp;

    @Test
    void mutationRevisionTracksTheCommonServiceWithoutMakingReadsIntoMutations() throws Exception
    {
        TokenLibraryService service = new TokenLibraryService(temp);
        assertEquals(0, service.revision());
        service.load();
        assertEquals(0, service.revision());
        service.createFolder("", "Party");
        assertEquals(1, service.revision());
        service.put(DmProject.TokenDefinition.builder().id("hero").name("Hero").build(), "Party");
        assertEquals(2, service.revision());
        service.move("hero", "");
        assertEquals(3, service.revision());
        service.delete("hero");
        assertEquals(4, service.revision());
        service.load();
        assertEquals(4, service.revision());
        assertThrows(java.io.IOException.class, () -> service.move("missing", ""));
        assertEquals(4, service.revision());
        service.deleteFolder("Party");
        assertEquals(5, service.revision());
    }

    private DmProject.TokenDefinition definition()
    {
        return DmProject.TokenDefinition.builder().id("character").name("Ranger").color("#55AA33")
                                        .shape(DmProject.TokenShape.SQUARE).border(false)
                                        .sizeTiles(1.5).sourceImage(imageData(0xFF112233)).image(imageData(0xFF445566)).zoom(2).centerX(.4).centerY(.6).build();
    }

    private static String imageData(int argb)
    {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        var out = new java.io.ByteArrayOutputStream();
        try
        {
            javax.imageio.ImageIO.write(image, "png", out);
            return TokenAssets.store(out.toByteArray());
        }
        catch (java.io.IOException ex)
        {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private DmProject map(DmProject.TokenDefinition token)
    {
        DmProject map = new DmProject();
        map.getTokenDefinitions().put(token.getId(), TokenLibraryService.copy(token));
        map.getTokens().add(DmProject.TokenInstance.builder().id("instance").definitionId(token.getId())
                                                   .x(250).y(350).sizeTiles(2).rotationDeg(17.5)
                                                   .playerVisible(true).nameVisible(true).build());
        return map;
    }

    @Test
    void shapeBorderAndInstanceRotationPersistCopyAndDefaultForLegacyTokens() throws Exception
    {
        var token = definition();
        token.setShape(DmProject.TokenShape.SQUARE);
        token.setBorder(false);
        assertEquals(token.getShape(), TokenLibraryService.duplicate(token).getShape());
        assertFalse(TokenLibraryService.duplicate(token).isBorder());
        var library = new TokenLibraryService(temp.resolve("library"));
        library.put(token, "");
        assertEquals(token, library.load().getEntries().getFirst().getToken());
        var map = map(token);
        map.getTokens().getFirst().setRotationDeg(32.75);
        var projects = new ProjectService();
        var frozen = projects.copy(map);
        Path file = temp.resolve("shape.dmmap");
        projects.save(file, map);
        var loaded = projects.load(file);
        assertEquals(map.getTokenDefinitions(), loaded.getTokenDefinitions());
        assertEquals(map.getTokens(), loaded.getTokens());
        token.setBorder(true);
        token.setShape(DmProject.TokenShape.CIRCLE);
        library.put(token, "");
        library.resolve(loaded);
        assertTrue(loaded.getTokenDefinitions().get(token.getId()).isBorder());
        assertEquals(32.75, loaded.getTokens().getFirst().getRotationDeg(), "Library edits preserve instance rotation");
        assertFalse(frozen.getTokenDefinitions().get(token.getId()).isBorder());
        var legacy = JsonMappers.create().readValue("{\"id\":\"old\",\"name\":\"Old\"}", DmProject.TokenDefinition.class);
        assertTrue(legacy.isBorder());
        assertEquals(DmProject.TokenShape.CIRCLE, legacy.getShape());
        assertEquals(0, JsonMappers.create().readValue("{}", DmProject.TokenInstance.class).getRotationDeg());
    }

    @Test
    void nestedFoldersSearchMoveDuplicateAndDeletionPreservePlacedTokens() throws Exception
    {
        TokenLibraryService service = new TokenLibraryService(temp);
        String party = service.createFolder("", "Party");
        String heroes = service.createFolder(party, "Heroes");
        var token = definition();
        service.put(token, heroes);
        assertTrue(TokenLibraryService.matches("Ranger", heroes, "party ranger"));
        assertFalse(TokenLibraryService.matches("Ranger", heroes, "villain"));
        var copy = TokenLibraryService.duplicate(token);
        assertNotEquals(token.getId(), copy.getId());
        assertEquals("Ranger (Copy)", copy.getName());
        service.put(copy, party);
        service.move(copy.getId(), heroes);
        service.moveFolder(party, "", "Characters");
        assertTrue(service.load().getEntries().stream().allMatch(e -> e.getFolder().equals("Characters/Heroes")));
        assertThrows(java.io.IOException.class, () -> service.moveFolder("Characters", "Characters/Heroes", "Bad"));
        DmProject map = map(token);
        service.deleteFolder("Characters");
        assertTrue(service.load().getEntries().isEmpty());
        try (var images = Files.list(temp.resolve("images")))
        {
            assertEquals(0, images.count());
        }
        service.resolve(map);
        assertEquals(token, map.getTokenDefinitions().get(token.getId()));
        assertEquals(1, map.getTokens().size());
    }

    @Test
    void deletionPreservesEachMapsOwnAppearanceWithoutChangingInstances() throws Exception
    {
        TokenLibraryService service = new TokenLibraryService(temp);
        var token = definition();
        service.put(token, "");
        DmProject first = map(token), second = map(token);
        var instance = new ProjectService().copy(first).getTokens().getFirst();
        token.setName("Scout");
        token.setColor("#123456");
        token.setImage(imageData(0xFF778899));
        token.setSizeTiles(3);
        service.put(token, "");
        service.resolve(first);
        service.delete(token.getId());
        service.resolve(second);
        assertEquals(token, first.getTokenDefinitions().get(token.getId()));
        assertEquals(definition(), second.getTokenDefinitions().get(token.getId()));
        assertEquals(instance, first.getTokens().getFirst());
        assertEquals(instance, second.getTokens().getFirst());
        assertThrows(java.io.IOException.class, () -> service.move(token.getId(), ""));
    }

    @Test
    void saveLoadAndFreezeCarryIndependentEmbeddedDefinitionsAndLegacyMapsStayEmpty() throws Exception
    {
        ProjectService projects = new ProjectService();
        DmProject map = map(definition());
        DmProject frozen = projects.copy(map);
        map.getTokenDefinitions().get("character").setImage(imageData(0xFF778899));
        map.getTokens().getFirst().setX(500);
        assertEquals(definition().getImage(), frozen.getTokenDefinitions().get("character").getImage());
        assertEquals(250, frozen.getTokens().getFirst().getX());
        Path file = temp.resolve("map.dmmap");
        projects.save(file, map);
        var stored = JsonMappers.create().readTree(file.toFile()).path("tokenDefinitions").path("character");
        assertTrue(stored.path("image").asText().startsWith("assets\\tokens\\"));
        assertTrue(Files.isRegularFile(file.getParent().resolve(stored.path("image").asText())));
        assertFalse(Files.readString(file).contains(map.getTokenDefinitions().get("character").getImage()));
        DmProject loaded = projects.load(file);
        assertEquals(map.getTokens(), loaded.getTokens());
        assertEquals(map.getTokenDefinitions(), loaded.getTokenDefinitions());
        Files.writeString(file, "{}");
        assertTrue(projects.load(file).getTokens().isEmpty());
        assertTrue(projects.load(file).getTokenDefinitions().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void exportRestoresLatestLinkedArtworkOrLastSavedArtworkAfterDeletion(boolean delete) throws Exception
    {
        TokenLibraryService tokens = TokenLibraryService.configured();
        var token = definition();
        tokens.put(token, "");
        ProjectService projects = new ProjectService();
        Path root = temp.resolve("maps");
        Files.createDirectories(root);
        MapLibraryService library = new MapLibraryService(root, projects);
        Path file = library.newMapFile(root, "Encounter");
        projects.save(file, map(token));
        token.setImage(imageData(0xFF778899));
        token.setName("Updated ranger");
        tokens.put(token, "");
        if (delete)
            tokens.delete(token.getId());
        MapExportService exporter = new MapExportService(library);
        Path pack = temp.resolve("tokens.dmmpack");
        byte[] sourceJson = Files.readAllBytes(file);
        exporter.export(List.of(new MapLibraryService.Entry(MapLibraryService.Kind.MAP, "Encounter",
                                                            file.getParent(), file, List.of())), pack);
        assertArrayEquals(sourceJson, Files.readAllBytes(file), "Export never rewrites the saved source map");
        try (var zip = new java.util.zip.ZipFile(pack.toFile()))
        {
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().contains("/assets/tokens/") && entry.getName().endsWith(".png")));
            var mapEntry = zip.stream().filter(entry -> entry.getName().endsWith(".dmmap")).findFirst().orElseThrow();
            try (var input = zip.getInputStream(mapEntry))
            {
                var exported = JsonMappers.create().readTree(input).path("tokenDefinitions").path("character");
                assertTrue(exported.path("image").asText().startsWith("assets\\tokens\\"));
                assertNotEquals(token.getImage(), exported.path("image").asText());
            }
        }
        String previous = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        System.setProperty(AppSettings.SYSTEM_PROPERTY, temp.resolve("elsewhere").resolve("settings.ini").toString());
        try
        {
            Path importedRoot = root.resolve("Imported");
            Files.createDirectories(importedRoot);
            Path imported = exporter.importPack(pack, importedRoot).getFirst();
            DmProject loaded = projects.load(imported);
            assertEquals(delete ? definition() : token, loaded.getTokenDefinitions().get("character"));
            assertEquals(map(token).getTokens(), loaded.getTokens());
            assertTrue(TokenLibraryService.configured().load().getEntries().isEmpty());
        }
        finally
        {
            System.setProperty(AppSettings.SYSTEM_PROPERTY, previous);
        }
    }

    @Test
    void diskAssetsDeduplicateAndLegacyEmbeddedImagesMigrateWithoutChangingRuntimeState() throws Exception
    {
        var token = definition();
        token.setSourceImage(token.getImage());
        TokenLibraryService library = new TokenLibraryService(temp);
        library.put(token, "");
        var json = JsonMappers.create().readTree(temp.resolve("tokens.json").toFile());
        var stored = json.path("entries").get(0).path("token");
        assertEquals(stored.path("image"), stored.path("sourceImage"));
        assertTrue(stored.path("image").asText().startsWith("images\\"));
        assertFalse(Files.readString(temp.resolve("tokens.json")).contains(token.getImage()));
        assertEquals(token, library.load().getEntries().getFirst().getToken());
        String originalAsset = stored.path("image").asText();
        token.setImage(imageData(0xFFABCDEF));
        library.put(token, "");
        assertTrue(Files.isRegularFile(temp.resolve(originalAsset)), "Artwork edits do not overwrite old assets");

        DmProject legacy = map(definition());
        Path file = temp.resolve("legacy.dmmap");
        DmProject embedded = projectsWithEmbeddedArtwork(legacy);
        JsonMappers.create().writeValue(file.toFile(), embedded);
        ProjectService projects = new ProjectService();
        DmProject loaded = projects.load(file);
        String fingerprint = projects.fingerprint(loaded);
        projects.save(file, loaded);
        assertEquals(fingerprint, projects.fingerprint(loaded));
        assertEquals(legacy.getTokenDefinitions(), projects.load(file).getTokenDefinitions());
        assertFalse(Files.readString(file).contains(definition().getSourceImage()));

        Files.delete(temp.resolve(JsonMappers.create().readTree(file.toFile())
                                             .path("tokenDefinitions").path("character").path("image").asText()));
        assertThrows(java.io.IOException.class, () -> projects.load(file), "Missing artwork is reported, not silently replaced");
    }

    private DmProject projectsWithEmbeddedArtwork(DmProject project) throws Exception
    {
        DmProject embedded = new ProjectService().copy(project);
        for (var token : embedded.getTokenDefinitions().values())
        {
            token.setSourceImage(java.util.Base64.getEncoder().encodeToString(
                    Files.readAllBytes(Path.of(java.net.URI.create(token.getSourceImage())))));
            token.setImage(java.util.Base64.getEncoder().encodeToString(
                    Files.readAllBytes(Path.of(java.net.URI.create(token.getImage())))));
        }
        return embedded;
    }

    @Test
    void runtimeArtworkIsFileBackedAndSurvivesLibraryDeletionAndMapRelocation() throws Exception
    {
        TokenLibraryService library = new TokenLibraryService(temp.resolve("library"));
        var token = definition();
        library.put(token, "");
        var runtime = library.load().getEntries().getFirst().getToken();
        assertTrue(runtime.getSourceImage().startsWith("file:"));
        assertTrue(runtime.getSourceImage().length() < 256);
        DmProject active = map(runtime);
        DmProject frozen = new ProjectService().copy(active);
        library.delete(token.getId());
        assertTrue(Files.isRegularFile(Path.of(java.net.URI.create(runtime.getSourceImage()))));
        assertEquals(active.getTokenDefinitions(), frozen.getTokenDefinitions());
        ProjectService projects = new ProjectService();
        Path first = temp.resolve("first").resolve("map.dmmap");
        projects.save(first, active);
        DmProject opened = projects.load(first);
        MapLibraryService.deleteRecursive(first.getParent());
        Path second = temp.resolve("second").resolve("map.dmmap");
        projects.save(second, opened);
        assertEquals(frozen.getTokenDefinitions(), projects.load(second).getTokenDefinitions());
        assertTrue(JsonMappers.create().writeValueAsBytes(opened.getTokenDefinitions()).length < 1024,
                   "Snapshot serialization contains compact artwork references, not the image bytes");
    }

    @Test
    void permanentDeletionKeepsSharedAssetsAndCleansObsoleteArtworkAndLegacyTombstones() throws Exception
    {
        TokenLibraryService library = new TokenLibraryService(temp);
        var token = definition();
        library.put(token, "");
        var duplicate = TokenLibraryService.duplicate(token);
        library.put(duplicate, "");
        library.delete(token.getId());
        assertEquals(duplicate, library.load().getEntries().getFirst().getToken());
        var stored = JsonMappers.create().readTree(temp.resolve("tokens.json").toFile());
        assertEquals(1, stored.path("entries").size());
        String oldCrop = stored.path("entries").get(0).path("token").path("image").asText();
        duplicate.setImage(imageData(0xFFABCDEF));
        library.put(duplicate, "");
        assertFalse(Files.exists(temp.resolve(oldCrop)), "Unreferenced earlier crops are reclaimed");
        Files.writeString(temp.resolve("images").resolve("unmanaged.txt"), "keep");
        var legacy = JsonMappers.create().readTree(temp.resolve("tokens.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy.path("entries").get(0)).put("deleted", true);
        JsonMappers.create().writeValue(temp.resolve("tokens.json").toFile(), legacy);
        library.createFolder("", "Cleanup");
        assertTrue(library.load().getEntries().isEmpty(), "Next library save purges old tombstones");
        try (var images = Files.list(temp.resolve("images")))
        {
            assertEquals(List.of("unmanaged.txt"), images.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void rotationMovesTokenCentersAndFourTurnsRestorePositions()
    {
        DmProject map = map(definition());
        map.getImageLayers().add(DmProject.ImageLayer.builder().width(1000).height(600).build());
        MapRotationService rotation = new MapRotationService();
        rotation.rotateClockwise(map);
        assertEquals(450, map.getTokens().getFirst().getX());
        assertEquals(50, map.getTokens().getFirst().getY());
        for (int i = 0; i < 3; i++)
            rotation.rotateClockwise(map);
        assertEquals(250, map.getTokens().getFirst().getX());
        assertEquals(350, map.getTokens().getFirst().getY());
        assertEquals(2, map.getTokens().getFirst().getSizeTiles());
    }

    @Test
    void rejectsInvalidNamesSizesColorsAndFolderCyclesWithoutPersistingChanges() throws Exception
    {
        TokenLibraryService service = new TokenLibraryService(temp);
        var token = definition();
        for (double size : new double[] { 0, -.5, .25, Double.NaN, Double.POSITIVE_INFINITY })
        {
            token.setSizeTiles(size);
            assertThrows(java.io.IOException.class, () -> service.put(token, ""));
        }
        token.setSizeTiles(1);
        token.setName(" ");
        assertThrows(java.io.IOException.class, () -> service.put(token, ""));
        assertTrue(service.load().getEntries().isEmpty());
        assertThrows(java.io.IOException.class, () -> service.createFolder("", "../bad"));
        service.createFolder("", "Party");
        assertThrows(java.io.IOException.class, () -> service.createFolder("", "party"));
        token.setName("Valid");
        token.setColor("invalid");
        assertThrows(java.io.IOException.class, () -> service.put(token, ""));
    }
}
