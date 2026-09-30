package dmmt.render;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.transform.Affine;
import javafx.scene.transform.NonInvertibleTransformException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

/**
 * Shared (DM, player and frozen player renderer) cache of map images. Images up to
 * {@link ImagePyramidBuilder#MAX_OVERVIEW_SIZE} px are drawn directly; larger images are drawn
 * from a downscaled overview plus full-resolution tiles of a disk-cached pyramid, loaded on demand.
 * All public methods must be called on the JavaFX application thread.
 */
public final class ImagePyramidStore {
    private static final int MAX_CACHED_TILES = 96;
    private static final int MAX_QUEUED_TILE_LOADS = 48;
    private static final Duration CACHE_RETENTION = Duration.ofDays(60);
    private static ImagePyramidStore shared;

    private final Path cacheRoot;
    private final Map<String, MapImage> images = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final LinkedHashMap<TileKey, Image> tiles = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
    private final Set<TileKey> broken = new HashSet<>();
    private final ConcurrentLinkedQueue<LoadedTile> loaded = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingDeque<TileKey> loadQueue = new LinkedBlockingDeque<>();
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> daemon(r, "dmmt-pyramid-builder", Thread.MIN_PRIORITY));

    public static synchronized ImagePyramidStore shared() {
        if (shared == null) {
            shared = new ImagePyramidStore(defaultCacheRoot());
        }
        return shared;
    }

    ImagePyramidStore(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
        for (int i = 0; i < 2; i++) {
            daemon(this::loadTilesForever, "dmmt-tile-loader-" + i, Thread.NORM_PRIORITY - 1).start();
        }
        builder.submit(this::pruneOldCaches);
    }

    private static Thread daemon(Runnable runnable, String name, int priority) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(priority);
        return thread;
    }

    private static Path defaultCacheRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "DungeonMasterMapTool", "image-cache");
        }
        return Path.of(System.getProperty("user.home"), ".dmmt", "image-cache");
    }

    /** Increases whenever a tile finished loading or a pyramid became available, so cached drawings can be refreshed. */
    public long changeVersion() {
        drainLoaded();
        return changes.get();
    }

    /** Returns the drawable image for {@code file}, or null if it cannot be loaded. */
    public MapImage get(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        MapImage image = images.get(key);
        if (image != null || failed.contains(key)) {
            return image;
        }
        image = load(file);
        if (image == null) {
            failed.add(key);
        } else {
            images.put(key, image);
        }
        return image;
    }

    private MapImage load(Path file) {
        int[] size = ImagePyramidBuilder.readDimensions(file);
        if (size != null && ImagePyramidBuilder.overviewLevel(size[0], size[1]) == 0) {
            Image plain = new Image(file.toUri().toString(), false);
            return plain.isError() || plain.getWidth() <= 0 ? null : new MapImage(plain, null);
        }
        if (size == null) {
            Image capped = loadCapped(file);
            return capped == null ? null : new MapImage(capped, null);
        }
        Path dir = cacheRoot.resolve(cacheKey(file));
        ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.readMeta(dir);
        Image overview = null;
        if (meta != null) {
            touch(dir);
            overview = new Image(ImagePyramidBuilder.overviewPath(dir, meta).toUri().toString(), false);
            if (overview.isError() || overview.getWidth() <= 0) {
                overview = null;
                meta = null;
            }
        }
        if (overview == null) {
            overview = loadCapped(file);
            if (overview == null) {
                return null;
            }
        }
        MapImage image = new MapImage(overview, dir);
        image.meta = meta;
        if (meta == null) {
            builder.submit(() -> buildPyramid(file, dir, image));
        }
        return image;
    }

    private void buildPyramid(Path file, Path dir, MapImage image) {
        long start = System.nanoTime();
        try {
            ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.build(file, dir);
            image.meta = meta;
            changes.incrementAndGet();
            System.out.printf("Built image pyramid for %s (%dx%d) in %d ms%n",
                    file.getFileName(), meta.width(), meta.height(), (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable ex) {
            // Includes OutOfMemoryError on very large images: the overview keeps working.
            System.err.println("Could not build image pyramid for " + file + ": " + ex);
            deleteQuietly(dir);
        }
    }

    /** Downscales oversized images so they fit into a GPU texture (a null texture crashes the canvas). */
    private static Image loadCapped(Path file) {
        Image image = new Image(file.toUri().toString(), ImagePyramidBuilder.MAX_OVERVIEW_SIZE,
                ImagePyramidBuilder.MAX_OVERVIEW_SIZE, true, true, false);
        return image.isError() || image.getWidth() <= 0 ? null : image;
    }

    static String cacheKey(Path file) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            String id = abs + "|" + Files.size(abs) + "|" + Files.getLastModifiedTime(abs).toMillis();
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (IOException | NoSuchAlgorithmException ex) {
            return Integer.toHexString(file.toAbsolutePath().toString().hashCode());
        }
    }

    private static void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir.resolve(ImagePyramidBuilder.META_FILE), FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Only used for pruning.
        }
    }

    private void pruneOldCaches() {
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(CACHE_RETENTION);
        try (Stream<Path> dirs = Files.list(cacheRoot)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    Path meta = dir.resolve(ImagePyramidBuilder.META_FILE);
                    Path stamp = Files.exists(meta) ? meta : dir;
                    if (Files.getLastModifiedTime(stamp).toInstant().isBefore(cutoff)) {
                        deleteQuietly(dir);
                    }
                } catch (IOException ignored) {
                    // Best effort.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    // ---- Tile cache (FX thread) and loader threads ----

    private Image tile(MapImage image, int level, int tx, int ty) {
        drainLoaded();
        TileKey key = new TileKey(image, level, tx, ty);
        Image tile = tiles.get(key);
        if (tile != null || broken.contains(key)) {
            return tile;
        }
        if (pending.add(key)) {
            loadQueue.offerFirst(key);
            // Newest requests win; drop stale ones (e.g. after fast panning).
            while (loadQueue.size() > MAX_QUEUED_TILE_LOADS) {
                TileKey stale = loadQueue.pollLast();
                if (stale == null) {
                    break;
                }
                pending.remove(stale);
            }
        }
        return null;
    }

    private void drainLoaded() {
        LoadedTile done;
        while ((done = loaded.poll()) != null) {
            pending.remove(done.key());
            if (done.image() == null) {
                broken.add(done.key());
                continue;
            }
            tiles.put(done.key(), done.image());
            changes.incrementAndGet();
            if (tiles.size() > MAX_CACHED_TILES) {
                var it = tiles.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    private void loadTilesForever() {
        while (true) {
            TileKey key;
            try {
                key = loadQueue.takeFirst();
            } catch (InterruptedException ex) {
                return;
            }
            Image image = null;
            try {
                ImagePyramidBuilder.Meta meta = key.image().meta;
                Path path = ImagePyramidBuilder.tilePath(key.image().dir, meta, key.level(), key.tx(), key.ty());
                image = new Image(path.toUri().toString(), false);
                if (image.isError() || image.getWidth() <= 0) {
                    image = null;
                }
            } catch (RuntimeException ex) {
                image = null;
            }
            // Picked up by the next frame of the render loop.
            loaded.add(new LoadedTile(key, image));
        }
    }

    private record TileKey(MapImage image, int level, int tx, int ty) {
    }

    private record LoadedTile(TileKey key, Image image) {
    }

    /** A map image that can be drawn at any zoom level with as much detail as the screen can show. */
    public final class MapImage {
        private final Image base;
        private final Path dir;
        private volatile ImagePyramidBuilder.Meta meta;

        private MapImage(Image base, Path dir) {
            this.base = base;
            this.dir = dir;
        }

        /**
         * Draws the whole image into the rectangle (in the current GC transform). Returns false while
         * detail tiles are still loading, so callers that cache the result know to draw again.
         */
        public boolean draw(GraphicsContext gc, double dx, double dy, double dw, double dh) {
            gc.drawImage(base, dx, dy, dw, dh);
            ImagePyramidBuilder.Meta meta = this.meta;
            if (meta == null || dw <= 0 || dh <= 0) {
                return true;
            }
            Affine transform = gc.getTransform();
            double transformScale = Math.sqrt(Math.abs(transform.determinant()));
            double screenPixelsPerSourcePixel = Math.max(dw / meta.width(), dh / meta.height())
                    * transformScale * outputScale(gc);
            int level = screenPixelsPerSourcePixel >= 1.0
                    ? 0
                    : (int) Math.floor(Math.log(1.0 / screenPixelsPerSourcePixel) / Math.log(2));
            if (PerformanceMode.isEnabled()) {
                level += PerformanceMode.IMAGE_LEVEL_BIAS;
            }
            if (level >= meta.overviewLevel()) {
                return true;
            }

            double[] visible = visibleLocalBounds(gc, transform);
            if (visible == null) {
                return true;
            }
            double u0 = Math.max(0, (visible[0] - dx) / dw * meta.width());
            double v0 = Math.max(0, (visible[1] - dy) / dh * meta.height());
            double u1 = Math.min(meta.width(), (visible[2] - dx) / dw * meta.width());
            double v1 = Math.min(meta.height(), (visible[3] - dy) / dh * meta.height());
            if (u1 <= u0 || v1 <= v0) {
                return true;
            }
            int span = meta.tileSize() << level;
            int tx0 = (int) Math.floor(u0 / span);
            int ty0 = (int) Math.floor(v0 / span);
            int tx1 = Math.min(meta.tileColumns(level) - 1, (int) Math.floor((u1 - 1e-6) / span));
            int ty1 = Math.min(meta.tileRows(level) - 1, (int) Math.floor((v1 - 1e-6) / span));
            double levelScale = 1 << level;
            boolean complete = true;
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    Image tile = tile(this, level, tx, ty);
                    if (tile == null) {
                        complete &= broken.contains(new TileKey(this, level, tx, ty));
                        continue;
                    }
                    double pu = (double) tx * span;
                    double pv = (double) ty * span;
                    double coveredW = Math.min(span, meta.width() - pu);
                    double coveredH = Math.min(span, meta.height() - pv);
                    gc.drawImage(tile,
                            0, 0, coveredW / levelScale, coveredH / levelScale,
                            dx + pu / meta.width() * dw, dy + pv / meta.height() * dh,
                            coveredW / meta.width() * dw, coveredH / meta.height() * dh);
                }
            }
            return complete;
        }

        private double[] visibleLocalBounds(GraphicsContext gc, Affine transform) {
            double cw = gc.getCanvas().getWidth();
            double ch = gc.getCanvas().getHeight();
            try {
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                for (double[] corner : new double[][]{{0, 0}, {cw, 0}, {0, ch}, {cw, ch}}) {
                    Point2D p = transform.inverseTransform(corner[0], corner[1]);
                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxX = Math.max(maxX, p.getX());
                    maxY = Math.max(maxY, p.getY());
                }
                return new double[]{minX, minY, maxX, maxY};
            } catch (NonInvertibleTransformException ex) {
                return null;
            }
        }

        private double outputScale(GraphicsContext gc) {
            var scene = gc.getCanvas().getScene();
            if (scene == null || scene.getWindow() == null) {
                return 1.0;
            }
            return Math.max(1.0, Math.max(scene.getWindow().getOutputScaleX(), scene.getWindow().getOutputScaleY()));
        }
    }
}
