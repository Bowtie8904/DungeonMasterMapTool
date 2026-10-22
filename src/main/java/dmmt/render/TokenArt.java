package dmmt.render;

import dmmt.model.DmProject.TokenDefinition;
import dmmt.model.DmProject.TokenShape;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TokenArt
{
    private static final long IMAGE_BUDGET = 32L * 1024 * 1024;
    private static final Map<String, Image> IMAGES = new LinkedHashMap<>(16, 0.75f, true);
    private static final java.util.Set<String> REMOTE_IMAGES = new java.util.HashSet<>();
    private static final java.util.Set<String> REMOTE_HASHES = new java.util.HashSet<>();
    private static long imageBytes;

    private TokenArt()
    {
    }

    public static Image decode(String data)
    {
        Image image = data.startsWith("file:") ? new Image(data)
                                               : new Image(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0)
        {
            throw new IllegalArgumentException("This image format cannot be read.", image.getException());
        }
        return image;
    }

    public static String storeImage(Image image) throws IOException
    {
        int width = (int)image.getWidth(), height = (int)image.getHeight();
        if (width <= 0 || height <= 0 || image.getPixelReader() == null)
            throw new IOException("The image has no pixels.");
        int[] pixels = new int[Math.multiplyExact(width, height)];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(png, "png", out))
            throw new IOException("PNG encoding is unavailable.");
        return dmmt.service.TokenAssets.store(out.toByteArray());
    }

    private static Image croppedImage(String reference)
    {
        Image image = IMAGES.get(reference);
        if (image != null)
            return image;
        if (REMOTE_IMAGES.contains(reference) || !REMOTE_HASHES.isEmpty() && REMOTE_HASHES.contains(imageHash(reference)))
        {
            try
            {
                image = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try
                    {
                        return dmmt.remote.RemoteImages.read(dmmt.remote.RemoteTokens.localArtwork(reference), true, 256);
                    }
                    catch (IOException error)
                    {
                        throw new java.util.concurrent.CompletionException(error);
                    }
                }).join();
            }
            catch (java.util.concurrent.CompletionException error)
            {
                throw new IllegalArgumentException("Token artwork cannot be read.", error.getCause());
            }
        }
        else
            image = reference.startsWith("file:") ? new Image(reference, 256, 256, true, true) : decode(reference);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0)
            throw new IllegalArgumentException("Token artwork cannot be read.", image.getException());
        cacheImage(reference, image);
        return image;
    }

    /** Installs an already decoded remote crop on the FX owner, avoiding image IO during rendering. */
    public static void cacheImage(String reference, Image image)
    {
        Image previous = IMAGES.remove(reference);
        if (previous != null)
            imageBytes -= (long)previous.getWidth() * (long)previous.getHeight() * 4;
        long bytes = (long)image.getWidth() * (long)image.getHeight() * 4;
        if (bytes <= IMAGE_BUDGET)
        {
            var entries = IMAGES.entrySet().iterator();
            while (imageBytes + bytes > IMAGE_BUDGET && entries.hasNext())
            {
                Image removed = entries.next().getValue();
                imageBytes -= (long)removed.getWidth() * (long)removed.getHeight() * 4;
                entries.remove();
            }
            IMAGES.put(reference, image);
            imageBytes += bytes;
        }
    }

    public static void registerRemoteImage(String reference)
    {
        if (reference != null)
        {
            REMOTE_IMAGES.add(reference);
            String hash = imageHash(reference);
            if (hash != null)
                REMOTE_HASHES.add(hash);
        }
    }

    public static void cacheRemoteImage(String reference, Image image)
    {
        registerRemoteImage(reference);
        cacheImage(reference, image);
    }

    private static String imageHash(String reference)
    {
        if (!reference.startsWith("file:"))
            return null;
        try
        {
            String name = java.nio.file.Path.of(java.net.URI.create(reference)).getFileName().toString();
            return name.matches("[0-9a-f]{64}\\.[a-z0-9]+") ? name.substring(0, 64) : null;
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
    }

    public static double cropSide(Image image, double zoom)
    {
        return Math.min(image.getWidth(), image.getHeight()) / zoom;
    }

    public static void constrain(TokenDefinition token, Image source)
    {
        token.setCenterX(Math.clamp(token.getCenterX(), 0, 1));
        token.setCenterY(Math.clamp(token.getCenterY(), 0, 1));
    }

    public static void drawCrop(GraphicsContext gc, Image image, TokenDefinition token, double size)
    {
        double side = cropSide(image, token.getZoom());
        double left = token.getCenterX() * image.getWidth() - side / 2;
        double top = token.getCenterY() * image.getHeight() - side / 2;
        double x = Math.max(0, left), y = Math.max(0, top);
        double width = Math.min(image.getWidth(), left + side) - x;
        double height = Math.min(image.getHeight(), top + side) - y;
        gc.drawImage(image, x, y, width, height, (x - left) * size / side,
                     (y - top) * size / side, width * size / side, height * size / side);
    }

    public static void draw(GraphicsContext gc, TokenDefinition token, double x, double y, double diameter)
    {
        double radius = diameter / 2;
        Color color = Color.web(token.getColor());
        gc.save();
        gc.beginPath();
        if (token.getShape() == TokenShape.SQUARE)
            gc.rect(x - radius, y - radius, diameter, diameter);
        else
            gc.arc(x, y, radius, radius, 0, 360);
        gc.closePath();
        gc.clip();
        if (token.isBorder())
        {
            gc.setFill(color.darker().darker());
            gc.fillRect(x - radius, y - radius, diameter, diameter);
        }
        if (token.getImage() != null)
        {
            Image image = croppedImage(token.getImage());
            gc.drawImage(image, x - radius, y - radius, diameter, diameter);
        }
        else
        {
            gc.setFill(color.brighter());
            gc.fillOval(x - diameter * .14, y - diameter * .30, diameter * .28, diameter * .28);
            gc.fillOval(x - diameter * .31, y + diameter * .03, diameter * .62, diameter * .55);
        }
        gc.restore();
        if (!token.isBorder())
            return;
        gc.save();
        gc.setLineDashes();
        gc.setStroke(color);
        gc.setLineWidth(Math.max(2, diameter * .055));
        if (token.getShape() == TokenShape.SQUARE)
            gc.strokeRect(x - radius + 1, y - radius + 1, diameter - 2, diameter - 2);
        else
            gc.strokeOval(x - radius + 1, y - radius + 1, diameter - 2, diameter - 2);
        gc.restore();
    }
}
