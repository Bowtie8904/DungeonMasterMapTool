package dmmt.render;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Properties;

/**
 * Builds a multi-resolution tile pyramid for images that are too large for a single GPU texture.
 * Level 0 is full resolution; each further level halves width and height (2x2 box filter).
 * Levels below {@link Meta#overviewLevel()} are stored as {@value #TILE_SIZE} px tiles, the overview
 * level (first level that fits into {@value #MAX_OVERVIEW_SIZE} px) is stored as a single image.
 */
public final class ImagePyramidBuilder {
    public static final int TILE_SIZE = 1024;
    public static final int MAX_OVERVIEW_SIZE = 4096;
    static final int FORMAT_VERSION = 1;
    static final String META_FILE = "pyramid.properties";
    private static final float JPEG_QUALITY = 0.92f;

    private ImagePyramidBuilder() {
    }

    public record Meta(int width, int height, int overviewLevel, int tileSize, String format) {
        public int levelWidth(int level) {
            return levelSize(width, level);
        }

        public int levelHeight(int level) {
            return levelSize(height, level);
        }

        public int tileColumns(int level) {
            return (levelWidth(level) + tileSize - 1) / tileSize;
        }

        public int tileRows(int level) {
            return (levelHeight(level) + tileSize - 1) / tileSize;
        }
    }

    static int levelSize(int size, int level) {
        for (int i = 0; i < level; i++) {
            size = (size + 1) / 2;
        }
        return size;
    }

    /** Number of halvings needed until the image fits into {@link #MAX_OVERVIEW_SIZE}; 0 = no pyramid needed. */
    public static int overviewLevel(int width, int height) {
        int level = 0;
        while (Math.max(width, height) > MAX_OVERVIEW_SIZE) {
            width = (width + 1) / 2;
            height = (height + 1) / 2;
            level++;
        }
        return level;
    }

    public static Path tilePath(Path dir, Meta meta, int level, int tx, int ty) {
        return dir.resolve("L" + level).resolve(tx + "_" + ty + "." + meta.format());
    }

    public static Path overviewPath(Path dir, Meta meta) {
        return dir.resolve("overview." + meta.format());
    }

    /** Reads only the image header; returns {width, height} or null if the format is unsupported. */
    public static int[] readDimensions(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** Returns the metadata of a complete pyramid in {@code dir}, or null if there is none. */
    public static Meta readMeta(Path dir) {
        Path metaFile = dir.resolve(META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
            if (Integer.parseInt(props.getProperty("version", "0")) != FORMAT_VERSION) {
                return null;
            }
            return new Meta(
                    Integer.parseInt(props.getProperty("width")),
                    Integer.parseInt(props.getProperty("height")),
                    Integer.parseInt(props.getProperty("overviewLevel")),
                    Integer.parseInt(props.getProperty("tileSize")),
                    props.getProperty("format"));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    public static Meta build(Path source, Path dir) throws IOException {
        BufferedImage image = ImageIO.read(source.toFile());
        if (image == null) {
            throw new IOException("Unsupported image format: " + source);
        }
        boolean alpha = image.getColorModel().hasAlpha();
        Meta meta = new Meta(image.getWidth(), image.getHeight(),
                overviewLevel(image.getWidth(), image.getHeight()), TILE_SIZE, alpha ? "png" : "jpg");
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve(META_FILE));

        RowSource level = new ImageRows(image);
        image = null;
        for (int l = 0; l < meta.overviewLevel(); l++) {
            level = writeLevel(level, dir, meta, l, alpha);
        }
        ArrayRows overview = level instanceof ArrayRows rows ? rows : ArrayRows.copyOf(level);
        writeImage(overview.pixels, overview.width, overview.height, alpha, meta.format(), overviewPath(dir, meta));

        Properties props = new Properties();
        props.setProperty("version", String.valueOf(FORMAT_VERSION));
        props.setProperty("width", String.valueOf(meta.width()));
        props.setProperty("height", String.valueOf(meta.height()));
        props.setProperty("overviewLevel", String.valueOf(meta.overviewLevel()));
        props.setProperty("tileSize", String.valueOf(meta.tileSize()));
        props.setProperty("format", meta.format());
        props.setProperty("source", source.toAbsolutePath().toString());
        Path tmp = dir.resolve(META_FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "DMMT image pyramid");
        }
        Files.move(tmp, dir.resolve(META_FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return meta;
    }

    /** Writes the tiles of one level and returns the next (half size) level. */
    private static ArrayRows writeLevel(RowSource source, Path dir, Meta meta, int level, boolean alpha) throws IOException {
        int w = source.width();
        int h = source.height();
        int nw = (w + 1) / 2;
        int nh = (h + 1) / 2;
        int[] next = new int[Math.multiplyExact(nw, nh)];
        int t = meta.tileSize();
        int[] band = new int[Math.multiplyExact(w, t)];
        int[] tile = new int[t * t];
        Files.createDirectories(dir.resolve("L" + level));
        for (int by = 0; by < h; by += t) {
            int rows = Math.min(t, h - by);
            source.read(by, rows, band);
            for (int bx = 0; bx < w; bx += t) {
                int cols = Math.min(t, w - bx);
                for (int r = 0; r < rows; r++) {
                    System.arraycopy(band, r * w + bx, tile, r * cols, cols);
                }
                writeImage(tile, cols, rows, alpha, meta.format(), tilePath(dir, meta, level, bx / t, by / t));
            }
            // Tile size is even, so row pairs never straddle two bands.
            for (int r = 0; r < rows; r += 2) {
                int row1 = r * w;
                int row2 = Math.min(r + 1, rows - 1) * w;
                int out = ((by + r) / 2) * nw;
                for (int ox = 0; ox < nw; ox++) {
                    int x1 = ox * 2;
                    int x2 = Math.min(x1 + 1, w - 1);
                    next[out + ox] = average(band[row1 + x1], band[row1 + x2], band[row2 + x1], band[row2 + x2]);
                }
            }
        }
        return new ArrayRows(next, nw, nh);
    }

    static int average(int a, int b, int c, int d) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = ((a >>> shift) & 0xFF) + ((b >>> shift) & 0xFF) + ((c >>> shift) & 0xFF) + ((d >>> shift) & 0xFF);
            result |= ((sum + 2) >> 2) << shift;
        }
        return result;
    }

    private static void writeImage(int[] argb, int width, int height, boolean alpha, String format, Path target) throws IOException {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(argb, 0, data, 0, width * height);
        Files.deleteIfExists(target);
        if ("jpg".equals(format)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
        } else if (!ImageIO.write(image, format, target.toFile())) {
            throw new IOException("No image writer for " + format);
        }
    }

    private interface RowSource {
        int width();

        int height();

        /** Reads {@code rows} full rows starting at {@code y} as ARGB into {@code out}. */
        void read(int y, int rows, int[] out);
    }

    private record ArrayRows(int[] pixels, int width, int height) implements RowSource {
        static ArrayRows copyOf(RowSource source) {
            int[] pixels = new int[Math.multiplyExact(source.width(), source.height())];
            source.read(0, source.height(), pixels);
            return new ArrayRows(pixels, source.width(), source.height());
        }

        @Override
        public void read(int y, int rows, int[] out) {
            System.arraycopy(pixels, y * width, out, 0, rows * width);
        }
    }

    /** Row reader with fast paths for the raster layouts ImageIO produces for JPEG/PNG. */
    private record ImageRows(BufferedImage image) implements RowSource {
        @Override
        public int width() {
            return image.getWidth();
        }

        @Override
        public int height() {
            return image.getHeight();
        }

        @Override
        public void read(int y, int rows, int[] out) {
            int w = image.getWidth();
            Raster raster = image.getRaster();
            boolean plain = raster.getParent() == null
                    && raster.getSampleModelTranslateX() == 0
                    && raster.getSampleModelTranslateY() == 0;
            if (plain && raster.getDataBuffer() instanceof DataBufferByte bytes
                    && raster.getSampleModel() instanceof ComponentSampleModel sm
                    && image.getColorModel() instanceof ComponentColorModel cm
                    && cm.getColorSpace().isCS_sRGB()
                    && !cm.isAlphaPremultiplied()
                    && (sm.getNumBands() == 3 || sm.getNumBands() == 4)
                    && sm.getNumBands() == cm.getNumComponents()
                    && raster.getTransferType() == java.awt.image.DataBuffer.TYPE_BYTE) {
                byte[] data = bytes.getData();
                int[] offsets = sm.getBandOffsets();
                int stride = sm.getScanlineStride();
                int pixelStride = sm.getPixelStride();
                int base = bytes.getOffset();
                boolean hasAlpha = sm.getNumBands() == 4;
                for (int r = 0; r < rows; r++) {
                    int p = base + (y + r) * stride;
                    int o = r * w;
                    for (int x = 0; x < w; x++, p += pixelStride) {
                        int a = hasAlpha ? data[p + offsets[3]] & 0xFF : 0xFF;
                        out[o + x] = (a << 24)
                                | ((data[p + offsets[0]] & 0xFF) << 16)
                                | ((data[p + offsets[1]] & 0xFF) << 8)
                                | (data[p + offsets[2]] & 0xFF);
                    }
                }
                return;
            }
            int type = image.getType();
            if (plain && raster.getDataBuffer() instanceof DataBufferInt ints
                    && raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm
                    && (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB)) {
                int[] data = ints.getData();
                int stride = sm.getScanlineStride();
                int base = ints.getOffset();
                int alphaMask = type == BufferedImage.TYPE_INT_RGB ? 0xFF000000 : 0;
                for (int r = 0; r < rows; r++) {
                    int p = base + (y + r) * stride;
                    int o = r * w;
                    for (int x = 0; x < w; x++) {
                        out[o + x] = data[p + x] | alphaMask;
                    }
                }
                return;
            }
            image.getRGB(0, y, w, rows, out, 0, w);
        }
    }
}
