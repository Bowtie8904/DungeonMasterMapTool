package dmmt.online;

import dmmt.render.ImagePyramidBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetRegistryTest {
    @TempDir
    Path temp;

    @Test
    void servesOnlyExistingTilesInsideThePyramid() throws Exception {
        Path dir = temp.resolve("pyramid");
        ImagePyramidBuilder.Meta meta = new ImagePyramidBuilder.Meta(3000, 1500, 1, 1024, "jpg");
        Files.createDirectories(dir.resolve("L0"));
        Files.writeString(ImagePyramidBuilder.tilePath(dir, meta, 0, 2, 1), "tile");
        Files.writeString(ImagePyramidBuilder.overviewPath(dir, meta), "overview");
        AssetRegistry assets = new AssetRegistry(temp.resolve("cache"), 4096, null);

        String key = assets.registerPyramid(dir, meta);
        assertNotNull(key);
        assertEquals(key, assets.registerPyramid(dir, meta));

        AssetRegistry.Asset tile = assets.resolveTile(key, 0, 2, 1);
        assertNotNull(tile);
        assertEquals("image/jpeg", tile.contentType());
        assertEquals(ImagePyramidBuilder.overviewPath(dir, meta), assets.resolveTile(key, 1, 0, 0).file());
        assertNull(assets.resolveTile(key, 0, 0, 0), "missing tile file");
        assertNull(assets.resolveTile(key, 0, 3, 0), "column outside the level");
        assertNull(assets.resolveTile(key, 1, 1, 0), "overview has a single tile");
        assertNull(assets.resolveTile(key, 2, 0, 0), "level beyond the overview");
        assertNull(assets.resolveTile("unknown", 0, 2, 1));
    }

    @Test
    void downscalesOversizedImagesToTheEdgeLimit() throws Exception {
        Path source = temp.resolve("map.bmp");
        BufferedImage image = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 1000; x++) {
            image.setRGB(x, 0, (x % 2 == 0) ? 0xFFFFFF : 0);
        }
        ImageIO.write(image, "bmp", source.toFile());
        AssetRegistry assets = new AssetRegistry(temp.resolve("cache"), 300, null);

        String url = assets.register(source, true);
        AssetRegistry.Asset asset = assets.resolve(url.substring(3));
        BufferedImage out = ImageIO.read(asset.file().toFile());
        assertEquals(300, out.getWidth());
        assertEquals(120, out.getHeight());
        assertTrue(asset.file().toString().endsWith(".jpg"));
    }
}
