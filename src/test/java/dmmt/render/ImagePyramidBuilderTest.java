package dmmt.render;

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

class ImagePyramidBuilderTest {

    @Test
    void overviewLevelHalvesUntilImageFitsIntoTexture() {
        assertEquals(0, ImagePyramidBuilder.overviewLevel(4096, 1000));
        assertEquals(1, ImagePyramidBuilder.overviewLevel(4097, 10));
        assertEquals(1, ImagePyramidBuilder.overviewLevel(7800, 7800));
        assertEquals(2, ImagePyramidBuilder.overviewLevel(15300, 15300));
    }

    @Test
    void averageMixesEachChannelSeparately() {
        assertEquals(0xFF804020, ImagePyramidBuilder.average(0xFF804020, 0xFF804020, 0xFF804020, 0xFF804020));
        assertEquals(0x80808080, ImagePyramidBuilder.average(0xFFFFFFFF, 0xFFFFFFFF, 0x00000000, 0x00000000));
    }

    @Test
    void buildsTilesAndOverviewForLargeImage(@TempDir Path temp) throws Exception {
        int width = 4500;
        int height = 1100;
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        // Left half red, right half blue: survives JPEG compression well enough to verify tile placement.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                source.setRGB(x, y, x < width / 2 ? 0xFF0000 : 0x0000FF);
            }
        }
        Path file = temp.resolve("big.png");
        ImageIO.write(source, "png", file.toFile());
        Path dir = temp.resolve("cache");

        assertNull(ImagePyramidBuilder.readMeta(dir));
        ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.build(file, dir);

        assertEquals(new ImagePyramidBuilder.Meta(width, height, 1, 1024, "jpg"), meta);
        assertEquals(meta, ImagePyramidBuilder.readMeta(dir));
        assertEquals(5, meta.tileColumns(0));
        assertEquals(2, meta.tileRows(0));

        BufferedImage lastTile = ImageIO.read(ImagePyramidBuilder.tilePath(dir, meta, 0, 4, 1).toFile());
        assertEquals(4500 - 4096, lastTile.getWidth());
        assertEquals(1100 - 1024, lastTile.getHeight());
        assertTrue((lastTile.getRGB(10, 10) & 0xFF) > 200, "right side must be blue");

        BufferedImage firstTile = ImageIO.read(ImagePyramidBuilder.tilePath(dir, meta, 0, 0, 0).toFile());
        assertTrue(((firstTile.getRGB(10, 10) >> 16) & 0xFF) > 200, "left side must be red");

        BufferedImage overview = ImageIO.read(ImagePyramidBuilder.overviewPath(dir, meta).toFile());
        assertNotNull(overview);
        assertEquals(2250, overview.getWidth());
        assertEquals(550, overview.getHeight());
        assertTrue(Files.isRegularFile(dir.resolve(ImagePyramidBuilder.META_FILE)));
    }

    @Test
    void keepsAlphaImagesLossless(@TempDir Path temp) throws Exception {
        BufferedImage source = new BufferedImage(5000, 20, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1030, 5, 0x7F123456);
        Path file = temp.resolve("alpha.png");
        ImageIO.write(source, "png", file.toFile());

        ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.build(file, temp.resolve("cache"));

        assertEquals("png", meta.format());
        BufferedImage tile = ImageIO.read(ImagePyramidBuilder.tilePath(temp.resolve("cache"), meta, 0, 1, 0).toFile());
        assertEquals(0x7F123456, tile.getRGB(6, 5));
    }
}
