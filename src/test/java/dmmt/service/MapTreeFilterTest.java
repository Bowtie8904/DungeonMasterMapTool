package dmmt.service;

import dmmt.model.DmProject;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MapLibraryService.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTreeFilterTest {
    @TempDir
    Path tempDir;

    private static Entry map(String name) {
        Path dir = Path.of("lib", name);
        return new Entry(Kind.MAP, name, dir, dir.resolve(name + ".dmmap"), List.of());
    }

    private static Entry folder(String name, Entry... children) {
        return new Entry(Kind.FOLDER, name, Path.of("lib", name), null, List.of(children));
    }

    private static Entry sampleRoot() {
        return folder("Library",
                folder("Dungeons", map("Crypt"), map("Goblin Cave")),
                folder("Cities", folder("Harbor", map("Docks")), map("Market")),
                map("Tavern"));
    }

    @Test
    void blankQueryKeepsTree() {
        Entry root = sampleRoot();
        assertSame(root, MapTreeFilter.filter(root, "  "));
        assertSame(root, MapTreeFilter.filter(root, null));
    }

    @Test
    void matchingMapKeepsParentFolders() {
        Entry result = MapTreeFilter.filter(sampleRoot(), "docks");
        assertEquals(1, result.children().size());
        Entry cities = result.children().get(0);
        assertEquals("Cities", cities.name());
        assertEquals(1, cities.children().size());
        Entry harbor = cities.children().get(0);
        assertEquals("Harbor", harbor.name());
        assertEquals(List.of("Docks"), harbor.children().stream().map(Entry::name).toList());
    }

    @Test
    void matchingFolderKeepsFullContents() {
        Entry result = MapTreeFilter.filter(sampleRoot(), "CITIES");
        assertEquals(1, result.children().size());
        Entry cities = result.children().get(0);
        assertEquals(2, cities.children().size());
        assertEquals(1, MapLibraryService.countMaps(cities.children().get(0)));
    }

    @Test
    void nonMatchingEntriesAreHiddenAndNoMatchIsEmpty() {
        Entry result = MapTreeFilter.filter(sampleRoot(), "tav");
        assertEquals(List.of("Tavern"), result.children().stream().map(Entry::name).toList());
        assertTrue(MapTreeFilter.filter(sampleRoot(), "zzz").children().isEmpty());
    }

    @Test
    void thumbnailIsWrittenOnSaveWithoutFog() throws IOException {
        Path image = tempDir.resolve("wide.png");
        BufferedImage wide = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 200; x++) {
            for (int y = 0; y < 100; y++) {
                wide.setRGB(x, y, 0xFF0000);
            }
        }
        ImageIO.write(wide, "png", image.toFile());

        DmProject project = DmProject.builder().build();
        project.getImageLayers().add(DmProject.ImageLayer.builder()
                .id("a").path(image.toString()).width(200).height(100).build());
        Path mapFile = tempDir.resolve("pkg").resolve("pkg.dmmap");
        new ProjectService().save(mapFile, project);

        Path thumbnail = ThumbnailService.thumbnailFile(mapFile);
        assertTrue(Files.isRegularFile(thumbnail));
        BufferedImage read = ImageIO.read(thumbnail.toFile());
        assertNotNull(read);
        assertEquals(256, read.getWidth());
        assertEquals(128, read.getHeight());
        assertEquals(0xFF0000, read.getRGB(128, 64) & 0xFFFFFF);

    }
}
