package dmmt.service;

import dmmt.model.DmProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapLibraryServiceTest {
    @Test
    void apiIndexPersistsLegacyIdentityWithoutChangingUnknownContent() throws IOException {
        Path file = root.resolve("Legacy.dmmap");
        Files.writeString(file, "{\"schemaVersion\":1,\"unknown\":{\"keep\":42},\"map\":{\"tags\":[\"forest\"]}}");
        library.scan();
        assertFalse(Files.readString(file).contains("\"id\""));
        MapLibraryService.ApiMap indexed = library.listApiMaps().getFirst();
        assertEquals(file, indexed.path());
        assertEquals("Legacy", indexed.name());
        assertFalse(indexed.multilevel());
        assertTrue(ProjectService.isValidId(indexed.id()));
        assertEquals(42, JsonMappers.create().readTree(file.toFile()).path("unknown").path("keep").asInt());
        assertEquals(indexed, library.resolveApiMap(indexed.id()).orElseThrow());
        assertEquals(indexed.id(), new MapLibraryService(root, new ProjectService()).listApiMaps().getFirst().id());
    }

    @Test
    void apiIdentityTracksMovesAndRenameButCopiesAreDistinct() throws IOException {
        Path original = createMap(root, "Original");
        String id = projectService.load(original).getId();
        assertTrue(ProjectService.isValidId(id));
        Path renamed = library.rename(find(library.scan(), "Original"), "Renamed").movedMaps().get(original);
        Path folder = library.createFolder(root, "Folder");
        Path moved = library.move(find(library.scan(), "Renamed"), folder).movedMaps().get(renamed);
        assertEquals(moved, library.resolveApiMap(id).orElseThrow().path());
        Path copied = library.copy(find(library.scan(), "Renamed")).createdMap();
        String copyId = projectService.load(copied).getId();
        assertFalse(id.equals(copyId));
        assertEquals(copied, library.resolveApiMap(copyId).orElseThrow().path());
        library.delete(find(library.scan(), "Renamed"));
        assertTrue(library.resolveApiMap(id).isEmpty());
    }

    @Test
    void apiLookupRejectsArbitraryPathsInvalidIdsAndAmbiguousIds() throws IOException {
        Path first = createMap(root, "First");
        String id = projectService.load(first).getId();
        assertTrue(library.findApiMap(tempDir.resolve("outside.dmmap")).isEmpty());
        assertTrue(library.resolveApiMap(first.toString()).isEmpty());
        assertTrue(library.resolveApiMap("1-1-1-1-1").isEmpty());
        assertTrue(library.resolveApiMap(null).isEmpty());
        assertTrue(library.resolveApiMap(java.util.UUID.randomUUID().toString()).isEmpty());
        Path second = createMap(root, "Second");
        DmProject clone = projectService.load(second);
        clone.setId(id);
        projectService.save(second, clone);
        assertTrue(assertThrows(IOException.class, library::listApiMaps).getMessage().contains("Duplicate map UUID"));
        assertThrows(IOException.class, () -> library.resolveApiMap(id));
        assertThrows(IOException.class, () -> library.findApiMap(first));
    }

    @Test
    void copyingLegacyLooseMapsGeneratesIndependentIds() throws IOException {
        Path file = root.resolve("Legacy.dmmap");
        Files.writeString(file, "{\"map\":{}}");
        String id = library.listApiMaps().getFirst().id();
        Path copy = library.copy(find(library.scan(), "Legacy")).createdMap();
        assertFalse(id.equals(projectService.load(copy).getId()));
        assertEquals(id, projectService.load(file).getId());
    }

    @TempDir
    Path tempDir;

    private Path root;
    private ProjectService projectService;
    private MapLibraryService library;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("dmmap-projects");
        Files.createDirectories(root);
        projectService = new ProjectService();
        library = new MapLibraryService(root, projectService);
    }

    private Path createMap(Path folder, String name) throws IOException {
        Path file = library.newMapFile(folder, name);
        projectService.save(file, DmProject.builder().build());
        Files.createDirectories(file.getParent().resolve("assets"));
        Files.writeString(file.getParent().resolve("assets").resolve("tile.txt"), "asset");
        return file;
    }

    private MapLibraryService.Entry find(MapLibraryService.Entry entry, String name) {
        if (entry.name().equals(name)) {
            return entry;
        }
        for (MapLibraryService.Entry child : entry.children()) {
            MapLibraryService.Entry hit = find(child, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void scanShowsFoldersAndMapsByNameOnly() throws IOException {
        Path cities = library.createFolder(root, "Cities");
        createMap(cities, "Harbor Town");
        createMap(root, "Cave");

        MapLibraryService.Entry tree = library.scan();
        List<MapLibraryService.Entry> top = tree.children();
        assertEquals(2, top.size());
        assertEquals("Cities", top.get(0).name());
        assertTrue(top.get(0).isFolder());
        assertEquals("Cave", top.get(1).name());
        assertTrue(top.get(1).isMap());

        MapLibraryService.Entry harbor = top.get(0).children().get(0);
        assertEquals("Harbor Town", harbor.name());
        assertTrue(harbor.isMap());
        assertTrue(harbor.children().isEmpty(), "assets must not be listed");
        assertEquals(1, MapLibraryService.countMaps(top.get(0)));

        assertEquals(1, library.scanFolders().children().size());
    }

    @Test
    void assetFoldersOfLooseMapsAreHidden() throws IOException {
        projectService.save(root.resolve("old.dmmap"), DmProject.builder().build());
        Files.createDirectories(root.resolve("assets").resolve("custom"));
        Files.createDirectories(root.resolve("imports"));
        library.createFolder(root, "Dungeons");

        List<MapLibraryService.Entry> top = library.scan().children();
        assertEquals(2, top.size());
        assertEquals("Dungeons", top.get(0).name());
        assertEquals("old", top.get(1).name());
    }

    @Test
    void moveMapAndFolderOnDisk() throws IOException {
        Path cities = library.createFolder(root, "Cities");
        Path wild = library.createFolder(root, "Wilderness");
        Path mapFile = createMap(root, "Forest");

        MapLibraryService.Result result = library.move(find(library.scan(), "Forest"), wild);
        Path moved = wild.resolve("Forest").resolve("Forest.dmmap");
        assertTrue(Files.exists(moved));
        assertFalse(Files.exists(mapFile));
        assertEquals(moved, result.movedMaps().get(mapFile));

        MapLibraryService.Result folderMove = library.move(find(library.scan(), "Wilderness"), cities);
        Path expected = cities.resolve("Wilderness").resolve("Forest").resolve("Forest.dmmap");
        assertTrue(Files.exists(expected));
        assertEquals(expected, folderMove.movedMaps().get(moved));
    }

    @Test
    void cannotMoveFolderIntoItself() throws IOException {
        Path cities = library.createFolder(root, "Cities");
        Path inner = library.createFolder(cities, "Inner");
        assertThrows(IOException.class, () -> library.move(find(library.scan(), "Cities"), inner));
        assertThrows(IOException.class, () -> library.move(find(library.scan(), "Cities"), cities));
    }

    @Test
    void renameMapRenamesFolderAndFile() throws IOException {
        Path mapFile = createMap(root, "Old Name");
        MapLibraryService.Result result = library.rename(find(library.scan(), "Old Name"), "New Name");
        Path renamed = root.resolve("New Name").resolve("New Name.dmmap");
        assertTrue(Files.exists(renamed));
        assertTrue(Files.exists(root.resolve("New Name").resolve("assets").resolve("tile.txt")));
        assertFalse(Files.exists(root.resolve("Old Name")));
        assertEquals(renamed, result.movedMaps().get(mapFile));

        library.rename(find(library.scan(), "New Name"), "new name");
        assertEquals("new name", library.scan().children().get(0).name());
    }

    @Test
    void renameRejectsDuplicatesAndInvalidNames() throws IOException {
        createMap(root, "A");
        createMap(root, "B");
        assertThrows(IOException.class, () -> library.rename(find(library.scan(), "A"), "B"));
        assertThrows(IOException.class, () -> library.rename(find(library.scan(), "A"), "bad/name"));
        assertThrows(IOException.class, () -> library.rename(find(library.scan(), "A"), "  "));
        assertThrows(IOException.class, () -> library.rename(find(library.scan(), "A"), "CON"));
    }

    @Test
    void copyCreatesRecognizableUniqueNames() throws IOException {
        createMap(root, "Dungeon");
        MapLibraryService.Result first = library.copy(find(library.scan(), "Dungeon"));
        assertEquals(root.resolve("Dungeon (Copy)").resolve("Dungeon (Copy).dmmap"), first.createdMap());
        assertTrue(Files.exists(first.createdMap()));
        assertTrue(Files.exists(root.resolve("Dungeon (Copy)").resolve("assets").resolve("tile.txt")));

        MapLibraryService.Result second = library.copy(find(library.scan(), "Dungeon"));
        assertEquals("Dungeon (Copy 2).dmmap", second.createdMap().getFileName().toString());

        MapLibraryService.Result third = library.copy(find(library.scan(), "Dungeon (Copy)"));
        assertEquals("Dungeon (Copy 3).dmmap", third.createdMap().getFileName().toString());
        assertNotNull(projectService.load(third.createdMap()));
    }

    @Test
    void deleteRemovesMapPackageAndFolders() throws IOException {
        Path cities = library.createFolder(root, "Cities");
        createMap(cities, "Harbor");
        library.delete(find(library.scan(), "Harbor"));
        assertFalse(Files.exists(cities.resolve("Harbor")));
        createMap(cities, "Market");
        library.delete(find(library.scan(), "Cities"));
        assertFalse(Files.exists(cities));
        assertThrows(IOException.class, () -> library.delete(library.scan()));
    }

    @Test
    void looseMapIsConvertedToPackageWithAssetsWhenMoved() throws IOException {
        Path image = root.resolve("floor.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder().id("l1").path("floor.png").width(8).height(8).build());
        Path loose = root.resolve("legacy.dmmap");
        projectService.save(loose, project);
        Path target = library.createFolder(root, "Generic");

        MapLibraryService.Result result = library.move(find(library.scan(), "legacy"), target);
        Path newFile = target.resolve("legacy").resolve("legacy.dmmap");
        assertEquals(newFile, result.movedMaps().get(loose));
        assertFalse(Files.exists(loose));
        DmProject reloaded = projectService.load(newFile);
        Path layerFile = newFile.getParent().resolve(reloaded.getImageLayers().get(0).getPath());
        assertTrue(Files.exists(layerFile), "asset must be copied into the new package");
    }
}
