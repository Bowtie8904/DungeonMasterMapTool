package dmmt.online;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps hashed URLs ({@code /a/<key>.<ext>}) to files. The key is derived from path, size, modification time and the
 * size limit, so a URL never changes content and can be cached forever. Oversized images are downscaled lazily on
 * first request and kept in the disk cache.
 */
public final class AssetRegistry {
    public record Asset(Path file, String contentType) {
    }

    private record Source(Path file, boolean downscale) {
    }

    private record PyramidSource(Path dir, dmmt.render.ImagePyramidBuilder.Meta meta) {
    }

    private final Map<String, PyramidSource> pyramids = new ConcurrentHashMap<>();

    private final Path cacheDir;
    private final int maxEdge;
    private final Map<String, Source> sources = new ConcurrentHashMap<>();

    private final Map<String, String> textureUrls = new ConcurrentHashMap<>();

    private final CampaignStore store;

    public AssetRegistry(Path cacheDir, int maxEdge, CampaignStore store) {
        this.cacheDir = cacheDir;
        this.maxEdge = maxEdge;
        this.store = store;
    }

    /** URL of an actor's artwork, or {@code null} when the file is missing. */
    public String artworkUrl(String campaignId, String hash) {
        if (store == null || !CampaignStore.safeId(hash)) {
            return null;
        }
        return registerHashed(store.artworkPath(campaignId, hash), hash);
    }

    /** Registers a map or token image and returns its URL path, or {@code null} when the file does not exist. */
    public String register(Path file, boolean downscale) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            Path abs = file.toAbsolutePath().normalize();
            String seed = abs + "|" + Files.size(abs) + "|" + Files.getLastModifiedTime(abs).toMillis() + "|" + maxEdge
                    + "|" + downscale + "|" + CACHE_VERSION;
            String key = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
            sources.putIfAbsent(key, new Source(abs, downscale));
            return "/a/" + key + ".img";
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Registers a content-hashed file (such as actor artwork) whose name is already its hash. */
    public String registerHashed(Path file, String hash) {
        if (file == null || !Files.isRegularFile(file) || !CampaignStore.safeId(hash)) {
            return null;
        }
        sources.putIfAbsent(hash, new Source(file.toAbsolutePath().normalize(), false));
        return "/a/" + hash + ".img";
    }

    /** Registers the procedural overlay texture tile for a kind and colour as a content-hashed PNG. */
    public String registerTexture(String kind, int rgb) {
        String id = kind + "|" + (rgb & 0xFFFFFF);
        return textureUrls.computeIfAbsent(id, k -> {
            try {
                int size = dmmt.render.OverlayTextures.TILE_SIZE;
                int[] pixels = dmmt.render.OverlayTextures.generate(kind, rgb & 0xFFFFFF, size);
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(pixels.length * 4);
                bytes.asIntBuffer().put(pixels);
                String key = "tx" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes.array())).substring(0, 30);
                Path file = cacheDir.resolve(key + ".png");
                if (!Files.isRegularFile(file)) {
                    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, size, size, pixels, 0, size);
                    Files.createDirectories(cacheDir);
                    ImageIO.write(image, "png", file.toFile());
                }
                return registerHashed(file, key);
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                return "";
            }
        });
    }

    /**
     * Registers a desktop tile pyramid so browsers can load full-resolution tiles from {@code /t/<key>/<level>/<x>_<y>}.
     * The key covers the pyramid directory (itself keyed by source path, size and mtime) and its geometry.
     */
    public String registerPyramid(Path dir, dmmt.render.ImagePyramidBuilder.Meta meta) {
        try {
            Path abs = dir.toAbsolutePath().normalize();
            String seed = abs + "|" + meta.width() + "x" + meta.height() + "|" + meta.overviewLevel() + "|"
                    + meta.tileSize() + "|" + meta.format();
            String key = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
            pyramids.putIfAbsent(key, new PyramidSource(abs, meta));
            return key;
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Resolves one pyramid tile; the level equal to the overview level is the whole-image overview. */
    public Asset resolveTile(String key, int level, int tx, int ty) {
        PyramidSource source = pyramids.get(key);
        if (source == null || level < 0 || tx < 0 || ty < 0) {
            return null;
        }
        dmmt.render.ImagePyramidBuilder.Meta meta = source.meta();
        Path file;
        if (level == meta.overviewLevel()) {
            if (tx != 0 || ty != 0) {
                return null;
            }
            file = dmmt.render.ImagePyramidBuilder.overviewPath(source.dir(), meta);
        } else if (level < meta.overviewLevel() && tx < meta.tileColumns(level) && ty < meta.tileRows(level)) {
            file = dmmt.render.ImagePyramidBuilder.tilePath(source.dir(), meta, level, tx, ty);
        } else {
            return null;
        }
        return Files.isRegularFile(file) ? new Asset(file, contentType(file)) : null;
    }

    /** Resolves {@code <key>.<ext>}; may generate the downscaled cache file. */
    public Asset resolve(String name) throws IOException {
        int dot = name.indexOf('.');
        String key = dot < 0 ? name : name.substring(0, dot);
        Source source = sources.get(key);
        if (source == null || !Files.isRegularFile(source.file)) {
            return null;
        }
        Path file = source.file;
        if (source.downscale) {
            file = downscaled(key, source.file);
        }
        return new Asset(file, contentType(file));
    }

    private synchronized Path downscaled(String key, Path original) throws IOException {
        Path png = cacheDir.resolve(key + ".png");
        Path jpg = cacheDir.resolve(key + ".jpg");
        if (Files.isRegularFile(png)) {
            return png;
        }
        if (Files.isRegularFile(jpg)) {
            return jpg;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(original.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return original;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (Math.max(w, h) <= maxEdge && isBrowserFormat(original)) {
                    return original;
                }
                int sub = Math.max(1, Math.max(w, h) / (maxEdge * 4));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sub, sub, 0, 0);
                BufferedImage image = reader.read(0, param);
                double scale = Math.min(1.0, (double) maxEdge / Math.max(image.getWidth(), image.getHeight()));
                int tw = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int th = Math.max(1, (int) Math.round(image.getHeight() * scale));
                boolean alpha = image.getColorModel().hasAlpha();
                BufferedImage out = downscale(image, tw, th, alpha);
                Files.createDirectories(cacheDir);
                Path target = alpha ? png : jpg;
                if (alpha) {
                    ImageIO.write(out, "png", target.toFile());
                } else {
                    writeJpeg(out, target);
                }
                return target;
            } finally {
                reader.dispose();
            }
        }
    }

    private static final int JPEG_QUALITY_PERCENT = 95;
    /** Bumped when the downscaling output changes so stale cached copies are regenerated. */
    private static final String CACHE_VERSION = "2";

    private static BufferedImage downscale(BufferedImage source, int tw, int th, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        // Halve in steps first: a single bicubic pass skips source pixels when shrinking by more than 2x.
        do {
            w = Math.max(tw, w / 2);
            h = Math.max(th, h / 2);
            if (w < tw * 2 && h < th * 2) {
                w = tw;
                h = th;
            }
            BufferedImage next = new BufferedImage(w, h, type);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (w != tw || h != th);
        return current;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", target.toFile());
            return;
        }
        javax.imageio.ImageWriter writer = writers.next();
        try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY_PERCENT / 100f);
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static boolean isBrowserFormat(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")
                || name.endsWith(".gif");
    }

    /** Registers an original audio file (never a prepared playback copy) and returns its URL path. */
    public String registerAudio(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            Path abs = file.toAbsolutePath().normalize();
            String name = abs.getFileName().toString().toLowerCase(Locale.ROOT);
            String ext = name.endsWith(".wav") ? "wav" : "mp3";
            String seed = "audio|" + abs + "|" + Files.size(abs) + "|" + Files.getLastModifiedTime(abs).toMillis();
            String key = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
            sources.putIfAbsent(key, new Source(abs, false));
            return "/a/" + key + "." + ext;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }
}
