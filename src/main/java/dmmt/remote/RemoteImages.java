package dmmt.remote;

import dmmt.log.Log;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded, first-frame image decoding for remote library imports and verified artwork. Worker only. */
public final class RemoteImages
{
    private static final Log LOG = Log.of(RemoteImages.class);
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private RemoteImages() {}

    public static Image read(Path file, boolean requirePng, int maximumSide) throws IOException
    {
        if (javafx.application.Platform.isFxApplicationThread())
            throw new IllegalStateException("Remote artwork decoding must run off the application thread.");
        if (Files.size(file) > MAX_BYTES)
            throw new IOException("Image exceeds the remote artwork byte limit.");
        try (var input = ImageIO.createImageInputStream(file.toFile()))
        {
            if (input == null)
                throw new IOException("Cannot read this image.");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new IOException("Cannot read this image format.");
            var reader = readers.next();
            try
            {
                reader.setInput(input, true, true);
                inspect(reader, requirePng);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                var parameters = reader.getDefaultReadParam();
                if (maximumSide > 0)
                {
                    int sample = Math.max(1, (Math.max(width, height) + maximumSide - 1) / maximumSide);
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                }
                var bitmap = reader.read(0, parameters);
                if (bitmap == null)
                    throw new IOException("Image could not be decoded.");
                var image = new WritableImage(bitmap.getWidth(), bitmap.getHeight());
                int[] row = new int[bitmap.getWidth()];
                for (int y = 0; y < bitmap.getHeight(); y++)
                {
                    bitmap.getRGB(0, y, row.length, 1, row, 0, row.length);
                        image.getPixelWriter().setPixels(0, y, row.length, 1,
                            PixelFormat.getIntArgbInstance(), row, 0, row.length);
                }
                LOG.debug("Decoded remote image {} ({}x{})", file.getFileName(), bitmap.getWidth(), bitmap.getHeight());
                return image;
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    public static void validatePng(Path file) throws IOException
    {
        if (javafx.application.Platform.isFxApplicationThread())
            throw new IllegalStateException("Remote artwork inspection must run off the application thread.");
        if (Files.size(file) > MAX_BYTES)
            throw new IOException("Image exceeds the remote artwork byte limit.");
        try (var input = ImageIO.createImageInputStream(file.toFile()))
        {
            if (input == null)
                throw new IOException("Cannot read this image.");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new IOException("Cannot read this image format.");
            var reader = readers.next();
            try
            {
                reader.setInput(input, true, true);
                inspect(reader, true);
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private static void inspect(javax.imageio.ImageReader reader, boolean requirePng) throws IOException
    {
        if (requirePng && !"png".equalsIgnoreCase(reader.getFormatName()))
        {
            LOG.warn("Rejected non-PNG token artwork (format {})", reader.getFormatName());
            throw new IOException("Token artwork must be PNG.");
        }
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > 16384 || height > 16384
                || (long)width * height > RemoteHandouts.MAX_IMAGE_PIXELS)
        {
            LOG.warn("Rejected image over the pixel limit ({}x{})", width, height);
            throw new IOException("Image exceeds the remote artwork pixel limit.");
        }
    }
}
