package dmmt.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemoteImagesTest
{
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeAll
    static void startFx() throws Exception
    {
        dmmt.FxTestSupport.startJavaFx();
    }

    @Test
    void readsFirstRasterFrameAndBoundsRemotePngBeforeDecoding() throws Exception
    {
        Path png = directory.resolve("source.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB),
                "png", png.toFile()));
        assertEquals(300, RemoteImages.read(png, true, 0).getWidth());
        assertTrue(RemoteImages.read(png, true, 64).getWidth() <= 64);
        RemoteImages.validatePng(png);

        byte[] oversized = Files.readAllBytes(png);
        ByteBuffer.wrap(oversized).putInt(16, 20_000);
        var crc = new java.util.zip.CRC32();
        crc.update(oversized, 12, 17);
        ByteBuffer.wrap(oversized).putInt(29, (int)crc.getValue());
        Path invalid = directory.resolve("oversized.png");
        Files.write(invalid, oversized);
        IOException error = assertThrows(IOException.class, () -> RemoteImages.read(invalid, true, 0));
        assertTrue(error.getMessage().contains("pixel limit"));

        Path jpeg = directory.resolve("source.jpg");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(17, 11, BufferedImage.TYPE_INT_RGB),
                "jpeg", jpeg.toFile()));
        assertEquals(17, RemoteImages.read(jpeg, false, 0).getWidth());
        assertThrows(IOException.class, () -> RemoteImages.read(jpeg, true, 0));
        Files.writeString(directory.resolve("bad.png"), "invalid image");
        assertThrows(IOException.class, () -> RemoteImages.read(directory.resolve("bad.png"), true, 0));
    }

    @Test
    void rejectsArtworkOverByteLimitBeforeOpeningAnImageReader() throws Exception
    {
        Path file = directory.resolve("large.png");
        try (var output = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE))
        {
            output.position(RemoteImages.MAX_BYTES);
            output.write(ByteBuffer.wrap(new byte[] { 0 }));
        }
        IOException error = assertThrows(IOException.class, () -> RemoteImages.read(file, true, 256));
        assertTrue(error.getMessage().contains("byte limit"));
    }
}
