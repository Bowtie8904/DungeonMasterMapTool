package dmmt.service;

import dmmt.model.DmProject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Renders map thumbnails straight from the image layers (never from the canvas), so fog of war, lighting, walls and
 * other overlays are not part of the picture.
 */
public class ThumbnailService {
    public static final String FILE_NAME = "thumbnail.png";
    public static final int SIZE = 256;

    private static final Color BACKGROUND = new Color(0x1c, 0x1e, 0x24);

    /** The thumbnail file that belongs to a map package ({@code thumbnail.png} next to the {@code .dmmap}). */
    public static Path thumbnailFile(Path mapFile) {
        return mapFile.toAbsolutePath().getParent().resolve(FILE_NAME);
    }

    /** True if the map file is alone in its directory, i.e. it is a package that may own a thumbnail file. */
    public static boolean isPackage(Path mapFile) {
        Path dir = mapFile.toAbsolutePath().getParent();
        if (dir == null) {
            return false;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(MapLibraryService::isMapFile).count() == 1;
        } catch (IOException exception) {
            return false;
        }
    }

    /** Writes {@code thumbnail.png} for a packaged map. Loose map files are skipped. Returns whether it was written. */
    public boolean write(Path mapFile, DmProject project) throws IOException {
        if (!isPackage(mapFile)) {
            return false;
        }
        byte[] png = renderPng(project, mapFile);
        if (png == null) {
            Files.deleteIfExists(thumbnailFile(mapFile));
            return false;
        }
        Files.write(thumbnailFile(mapFile), png);
        return true;
    }

    /** PNG bytes of the map's image layers at the map's current rotation, or {@code null} if nothing can be drawn. */
    public byte[] renderPng(DmProject project, Path mapFile) throws IOException {
        BufferedImage image = render(project, mapFile, SIZE);
        if (image == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    public BufferedImage render(DmProject project, Path mapFile, int maxSize) {
        List<DmProject.ImageLayer> layers = project.getImageLayers().stream()
                .filter(DmProject.ImageLayer::isVisible)
                .filter(layer -> layer.getWidth() > 0 && layer.getHeight() > 0)
                .sorted((a, b) -> Integer.compare(a.getZIndex(), b.getZIndex()))
                .toList();
        if (layers.isEmpty()) {
            return null;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (DmProject.ImageLayer layer : layers) {
            double centerX = layer.getX() + layer.getWidth() / 2.0;
            double centerY = layer.getY() + layer.getHeight() / 2.0;
            AffineTransform rotation = AffineTransform.getRotateInstance(Math.toRadians(layer.getRotationDeg()),
                    centerX, centerY);
            double dw = drawWidth(layer);
            double dh = drawHeight(layer);
            double left = centerX - dw / 2.0;
            double top = centerY - dh / 2.0;
            double[] corners = {left, top, left + dw, top, left + dw, top + dh, left, top + dh};
            rotation.transform(corners, 0, corners, 0, 4);
            for (int i = 0; i < corners.length; i += 2) {
                minX = Math.min(minX, corners[i]);
                maxX = Math.max(maxX, corners[i]);
                minY = Math.min(minY, corners[i + 1]);
                maxY = Math.max(maxY, corners[i + 1]);
            }
        }
        double worldWidth = maxX - minX;
        double worldHeight = maxY - minY;
        if (worldWidth <= 0 || worldHeight <= 0) {
            return null;
        }

        double scale = maxSize / Math.max(worldWidth, worldHeight);
        int width = Math.max(1, (int) Math.round(worldWidth * scale));
        int height = Math.max(1, (int) Math.round(worldHeight * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);
            g.scale(scale, scale);
            g.translate(-minX, -minY);
            boolean drawn = false;
            for (DmProject.ImageLayer layer : layers) {
                double dw = drawWidth(layer);
                double dh = drawHeight(layer);
                BufferedImage source = readScaled(resolve(layer.getPath(), mapFile), dw * scale, dh * scale);
                if (source == null) {
                    continue;
                }
                double centerX = layer.getX() + layer.getWidth() / 2.0;
                double centerY = layer.getY() + layer.getHeight() / 2.0;
                AffineTransform saved = g.getTransform();
                g.rotate(Math.toRadians(layer.getRotationDeg()), centerX, centerY);
                g.drawImage(source, (int) Math.round(centerX - dw / 2.0), (int) Math.round(centerY - dh / 2.0),
                        (int) Math.round(dw), (int) Math.round(dh), null);
                g.setTransform(saved);
                drawn = true;
            }
            return drawn ? result : null;
        } finally {
            g.dispose();
        }
    }

    /** The layer rectangle is the on-screen footprint; at 90/270 degrees the unrotated image has swapped sides. */
    private static boolean sideways(DmProject.ImageLayer layer) {
        return Math.round(layer.getRotationDeg() / 90.0) % 2 != 0;
    }

    private static double drawWidth(DmProject.ImageLayer layer) {
        return sideways(layer) ? layer.getHeight() : layer.getWidth();
    }

    private static double drawHeight(DmProject.ImageLayer layer) {
        return sideways(layer) ? layer.getWidth() : layer.getHeight();
    }

    private static Path resolve(String path, Path mapFile) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute() && mapFile != null && mapFile.toAbsolutePath().getParent() != null) {
            resolved = mapFile.toAbsolutePath().getParent().resolve(path).normalize();
        }
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    /** Reads an image, sub-sampling it while decoding so huge maps never have to be held at full resolution. */
    private static BufferedImage readScaled(Path file, double targetWidth, double targetHeight) {
        if (file == null) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                int factor = (int) Math.max(1, Math.floor(Math.min(
                        reader.getWidth(0) / Math.max(1.0, targetWidth),
                        reader.getHeight(0) / Math.max(1.0, targetHeight))));
                param.setSourceSubsampling(factor, factor, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            return null;
        }
    }
}
