package dmmt.render;

import dmmt.service.Tuning;
import dmmt.service.WorkScheduler;
import dmmt.model.DmProject;
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
import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

/**
 * Shared (DM, player and frozen player renderer) cache of map images. Images up to
 * {@link ImagePyramidBuilder#maxOverviewSize()} px are drawn directly; larger images are drawn
 * from a downscaled overview plus full-resolution tiles of a disk-cached pyramid, loaded on demand.
 * Drawing/cache adoption use the FX thread; preparation is explicitly off-thread.
 */
public final class ImagePyramidStore
{
    private static ImagePyramidStore shared;

    private final Path cacheRoot;
    private final Map<String, MapImage> images = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, java.util.concurrent.Future<?>> preparing = new HashMap<>();
    private final ConcurrentLinkedQueue<PreparedImage> prepared = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<TileKey, Image> tiles = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
    private final Set<TileKey> broken = new HashSet<>();
    private final ConcurrentLinkedQueue<LoadedTile> loaded = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingDeque<TileKey> loadQueue = new LinkedBlockingDeque<>();
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();

    public static synchronized ImagePyramidStore shared()
    {
        if (shared == null)
        {
            shared = new ImagePyramidStore(defaultCacheRoot());
        }
        return shared;
    }

    ImagePyramidStore(Path cacheRoot)
    {
        this.cacheRoot = cacheRoot;
        for (int i = 0; i < 2; i++)
        {
            daemon(this::loadTilesForever, "dmmt-tile-loader-" + i, Thread.NORM_PRIORITY - 1).start();
        }
        WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, false, () -> {
            pruneOldCaches();
            return null;
        });
    }

    private static Thread daemon(Runnable runnable, String name, int priority)
    {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(priority);
        return thread;
    }

    private static Path defaultCacheRoot()
    {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank())
        {
            return Path.of(localAppData, "DungeonMasterMapTool", "image-cache");
        }
        return Path.of(System.getProperty("user.home"), ".dmmt", "image-cache");
    }

    /**
     * Increases whenever a tile finished loading or a pyramid became available, so cached drawings can be refreshed.
     */
    public long changeVersion()
    {
        drainPrepared();
        drainLoaded();
        return changes.get();
    }

    /**
     * Returns the drawable image for {@code file}, or null if it cannot be loaded.
     */
    public MapImage get(Path file)
    {
        drainPrepared();
        String key = file.toAbsolutePath().normalize().toString();
        MapImage image = images.get(key);
        if (image != null)
        {
            schedulePyramid(file, image);
            return image;
        }
        if (failed.contains(key))
        {
            return null;
        }
        java.util.concurrent.Future<?> active = preparing.get(key);
        // A scheduler shutdown cancels queued work; such a task never reports back, so queue it again.
        if (active == null || active.isCancelled())
        {
            try {
                preparing.put(key, WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, true, () -> {
                try
                {
                    prepared.add(new PreparedImage(key, load(file, false), null, false));
                }
                catch (IOException | RuntimeException | OutOfMemoryError ex)
                {
                    prepared.add(new PreparedImage(key, null, ex, interrupted(ex)));
                }
                changes.incrementAndGet();
                return null;
                }));
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                preparing.remove(key);
                System.err.println("Could not queue map image " + file + ": " + ex.getMessage());
            }
        }
        return null;
    }

    private MapImage load(Path file, boolean speculative) throws IOException
    {
        int[] size = ImagePyramidBuilder.requireDimensions(file);
        if (ImagePyramidBuilder.overviewLevel(size[0], size[1]) == 0)
        {
            Image plain = new Image(file.toUri().toString(), false);
            if (plain.isError() || plain.getWidth() <= 0)
            {
                throw new IOException("Cannot decode image: " + file, plain.getException());
            }
            return new MapImage(plain, null);
        }
        Path dir = cacheRoot.resolve(cacheKey(file));
        ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.readMeta(dir);
        if (meta == null)
        {
            Path legacy = cacheRoot.resolve(legacyCacheKey(file));
            if (!legacy.equals(dir) && ImagePyramidBuilder.readMeta(legacy) != null)
            {
                try
                {
                    Files.move(legacy, dir);
                    meta = ImagePyramidBuilder.readMeta(dir);
                }
                catch (IOException ignored)
                {
                    // Another instance may have migrated it; rebuilding is safe.
                    meta = ImagePyramidBuilder.readMeta(dir);
                }
            }
        }
        Image overview = null;
        if (meta != null)
        {
            touch(dir);
            overview = new Image(ImagePyramidBuilder.overviewPath(dir, meta).toUri().toString(), false);
            if (overview.isError() || overview.getWidth() <= 0)
            {
                overview = null;
                meta = null;
            }
        }
        if (overview == null)
        {
            overview = loadCapped(file);
            if (overview == null)
            {
                throw new IOException("Cannot decode image: " + file);
            }
        }
        MapImage image = new MapImage(overview, dir);
        image.meta = meta;
        if (meta == null)
        {
            if (speculative)
            {
                image.meta = ImagePyramidBuilder.build(file, dir);
            }
            else
            {
                image.buildNeeded = true;
                schedulePyramid(file, image);
            }
        }
        return image;
    }

    /**
     * Queues the background pyramid build unless one is queued/running. Work dropped or interrupted by a scheduler
     * shutdown leaves {@code buildNeeded} set, so the next use (for example after reopening the app in the same JVM)
     * queues it on the current scheduler again.
     */
    private void schedulePyramid(Path file, MapImage image)
    {
        synchronized (image)
        {
            java.util.concurrent.Future<?> build = image.build;
            if (!image.buildNeeded || image.meta != null || (build != null && !build.isDone()))
            {
                return;
            }
            try
            {
                image.build = WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, true, () -> {
                    buildPyramid(file, image.dir, image);
                    return null;
                });
            }
            catch (java.util.concurrent.RejectedExecutionException ex)
            {
                image.build = null;
                System.err.println("Could not queue image pyramid " + file + ": " + ex.getMessage());
            }
        }
    }

    private void buildPyramid(Path file, Path dir, MapImage image)
    {
        long start = System.nanoTime();
        try
        {
            ImagePyramidBuilder.Meta meta = ImagePyramidBuilder.build(file, dir);
            image.meta = meta;
            image.buildNeeded = false;
            changes.incrementAndGet();
            System.out.printf("Built image pyramid for %s (%dx%d) in %d ms%n",
                              file.getFileName(), meta.width(), meta.height(), (System.nanoTime() - start) / 1_000_000);
        }
        catch (Throwable ex)
        {
            if (!interrupted(ex))
            {
                // Includes OutOfMemoryError on very large images: the overview keeps working.
                image.buildNeeded = false;
                System.err.println("Could not build image pyramid for " + file + ": " + ex);
            }
            deleteQuietly(dir);
        }
    }

    private static boolean interrupted(Throwable ex)
    {
        return Thread.currentThread().isInterrupted()
               || ex instanceof java.io.InterruptedIOException
               || ex instanceof java.nio.channels.ClosedByInterruptException;
    }

    /**
     * Downscales oversized images so they fit into a GPU texture (a null texture crashes the canvas).
     */
    private static Image loadCapped(Path file)
    {
        Image image = new Image(file.toUri().toString(), ImagePyramidBuilder.maxOverviewSize(),
                                ImagePyramidBuilder.maxOverviewSize(), true, true, false);
        return image.isError() || image.getWidth() <= 0 ? null : image;
    }

    static String cacheKey(Path file) throws IOException
    {
        return ImageCacheIdentity.key(file, buildOptions());
    }

    private static String legacyCacheKey(Path file)
    {
        try
        {
            Path abs = file.toAbsolutePath().normalize();
            String id = abs + "|" + Files.size(abs) + "|" + Files.getLastModifiedTime(abs).toMillis() + buildOptions();
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        }
        catch (IOException | NoSuchAlgorithmException ex)
        {
            return Integer.toHexString(file.toAbsolutePath().toString().hashCode());
        }
    }

    /**
     * Non-default pyramid settings become part of the cache key, so changing them builds a new cache.
     */
    private static String buildOptions()
    {
        StringBuilder options = new StringBuilder();
        for (Tuning.Setting<?> setting : java.util.List.of(Tuning.CACHE_TILE_SIZE, Tuning.CACHE_OVERVIEW_SIZE, Tuning.CACHE_JPEG_QUALITY))
        {
            if (!setting.get().equals(setting.defaultValue()))
            {
                options.append('|').append(setting.key()).append('=').append(setting.get());
            }
        }
        return options.toString();
    }

    private static void touch(Path dir)
    {
        try
        {
            Files.setLastModifiedTime(dir.resolve(ImagePyramidBuilder.META_FILE), FileTime.from(Instant.now()));
        }
        catch (IOException ignored)
        {
            // Only used for pruning.
        }
    }

    private void pruneOldCaches()
    {
        if (!Files.isDirectory(cacheRoot))
        {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(Tuning.CACHE_RETENTION_DAYS.get()));
        try (Stream<Path> dirs = Files.list(cacheRoot))
        {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try
                {
                    Path meta = dir.resolve(ImagePyramidBuilder.META_FILE);
                    Path stamp = Files.exists(meta) ? meta : dir;
                    if (Files.getLastModifiedTime(stamp).toInstant().isBefore(cutoff))
                    {
                        deleteQuietly(dir);
                    }
                }
                catch (IOException ignored)
                {
                    // Best effort.
                }
            });
        }
        catch (IOException ignored)
        {
            // Best effort.
        }
    }

    private static void deleteQuietly(Path dir)
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // Best effort.
                }
            });
        }
        catch (IOException ignored)
        {
            // Best effort.
        }
    }

    // ---- Tile cache (FX thread) and loader threads ----

    private Image tile(MapImage image, int level, int tx, int ty)
    {
        drainLoaded();
        TileKey key = new TileKey(image, level, tx, ty);
        Image tile = tiles.get(key);
        if (tile != null || broken.contains(key))
        {
            return tile;
        }
        if (pending.add(key))
        {
            loadQueue.offerFirst(key);
            // Newest requests win; drop stale ones (e.g. after fast panning).
            while (loadQueue.size() > Tuning.CACHE_TILE_QUEUE.get())
            {
                TileKey stale = loadQueue.pollLast();
                if (stale == null)
                {
                    break;
                }
                pending.remove(stale);
            }
        }
        return null;
    }

    private void drainLoaded()
    {
        LoadedTile done;
        while ((done = loaded.poll()) != null)
        {
            pending.remove(done.key());
            if (done.image() == null)
            {
                broken.add(done.key());
                continue;
            }
            tiles.put(done.key(), done.image());
            changes.incrementAndGet();
            while (tiles.size() > Tuning.CACHE_IMAGE_TILES.get())
            {
                var it = tiles.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    private void drainPrepared()
    {
        PreparedImage done;
        while ((done = prepared.poll()) != null)
        {
            preparing.remove(done.key());
            if (done.retry())
            {
                // Interrupted by shutdown/cancellation, not a broken image: the next get() queues it again.
                continue;
            }
            if (done.image() == null)
            {
                failed.add(done.key());
                System.err.println("Could not prepare map image " + done.key() + ": " + done.failure());
            }
            else
            {
                images.put(done.key(), done.image());
            }
        }
    }

    private record PreparedImage(String key, MapImage image, Throwable failure, boolean retry)
    {
    }

    public record Viewport(double width, double height, double outputScale)
    {
    }

    public final class PreparedImages
    {
        private final Map<String, MapImage> images = new LinkedHashMap<>();
        private final Map<String, String> versions = new LinkedHashMap<>();
        private final List<LoadedTile> tiles = new ArrayList<>();
        private final Set<TileKey> tileKeys = new HashSet<>();
        private long retainedBytes;
        private final String options = buildOptions();
        private final List<Placement> placements;
        private final CameraPosition dmCamera;
        private final CameraPosition playerCamera;
        private final Viewport dmViewport;
        private final Viewport playerViewport;
        private final int levelBias = PerformanceMode.isEnabled() ? PerformanceMode.imageLevelBias() : 0;

        private PreparedImages(DmProject project, Viewport dm, Viewport player) {
            placements = placements(project);
            dmCamera = CameraPosition.of(project.getViews().getDmCamera());
            playerCamera = CameraPosition.of(project.getViews().getPlayerCamera());
            dmViewport = dm;
            playerViewport = player;
        }

        public boolean matches(DmProject project, Path projectFile, Viewport dm, Viewport player) throws IOException
        {
            if (!placements.equals(placements(project)) || !dmCamera.equals(CameraPosition.of(project.getViews().getDmCamera()))
                    || !playerCamera.equals(CameraPosition.of(project.getViews().getPlayerCamera()))
                    || !java.util.Objects.equals(dmViewport, dm) || !java.util.Objects.equals(playerViewport, player)
                    || levelBias != (PerformanceMode.isEnabled() ? PerformanceMode.imageLevelBias() : 0)) {
                return false;
            }
            Set<String> paths = new HashSet<>();
            for (DmProject.ImageLayer layer : project.getImageLayers())
            {
                if (layer.isVisible() && layer.getWidth() > 0 && layer.getHeight() > 0
                        && layer.getPath() != null && !layer.getPath().isBlank())
                {
                    Path asset = Path.of(layer.getPath());
                    if (!asset.isAbsolute())
                    {
                        asset = projectFile.toAbsolutePath().getParent().resolve(asset);
                    }
                    paths.add(asset.toAbsolutePath().normalize().toString());
                }
            }
            if (!paths.equals(images.keySet()) || !options.equals(buildOptions()))
            {
                return false;
            }
            for (var version : versions.entrySet())
            {
                if (!version.getValue().equals(assetStamp(Path.of(version.getKey()))))
                {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Blocking image stage. Speculative results are detached and bounded until explicitly adopted.
     */
    public PreparedImages prepareProject(DmProject project, Path projectFile, Viewport dm, Viewport player,
                                         boolean speculative, BooleanSupplier cancelled) throws IOException
    {
        PreparedImages result = new PreparedImages(project, dm, player);
        int imageLimit = speculative ? 8 : Integer.MAX_VALUE;
        int tileLimit = Math.min(64, Math.max(0, Tuning.CACHE_IMAGE_TILES.get() / 2));
        long byteLimit = speculative ? 96L * 1024 * 1024 : Long.MAX_VALUE;
        for (DmProject.ImageLayer layer : project.getImageLayers())
        {
            checkCancelled(cancelled);
            if (!layer.isVisible() || layer.getPath() == null || layer.getPath().isBlank()
                    || layer.getWidth() <= 0 || layer.getHeight() <= 0)
            {
                continue;
            }
            if (result.images.size() >= imageLimit)
            {
                break;
            }
            Path file = Path.of(layer.getPath());
            if (!file.isAbsolute() && projectFile != null)
            {
                file = projectFile.toAbsolutePath().getParent().resolve(file);
            }
            Path asset = file.toAbsolutePath().normalize();
            String key = asset.toString();
            try {
                WorkScheduler.shared().run(WorkScheduler.Kind.IMAGE, () -> {
                checkCancelled(cancelled);
                String before = assetStamp(asset);
                MapImage image = result.images.get(key);
                if (image == null)
                {
                    if (speculative) {
                        int[] dimensions = ImagePyramidBuilder.requireDimensions(asset);
                        int level = ImagePyramidBuilder.overviewLevel(dimensions[0], dimensions[1]);
                        long bytes = 4L * ImagePyramidBuilder.levelSize(dimensions[0], level)
                                * ImagePyramidBuilder.levelSize(dimensions[1], level);
                        if (bytes + result.retainedBytes > byteLimit) {
                            return null;
                        }
                    }
                    image = load(asset, speculative);
                    result.images.put(key, image);
                    result.versions.put(key, before);
                    result.retainedBytes += (long) image.base.getWidth() * (long) image.base.getHeight() * 4;
                }
                if (image.meta != null)
                {
                    Set<InitialTile> requests = new java.util.LinkedHashSet<>();
                    requests.addAll(initialTiles(image.meta, layer, project.getViews().getDmCamera(), dm, tileLimit));
                    requests.addAll(initialTiles(image.meta, layer, project.getViews().getPlayerCamera(), player, tileLimit));
                    for (InitialTile request : requests)
                    {
                        checkCancelled(cancelled);
                        long tileBytes = 4L * image.meta.tileSize() * image.meta.tileSize();
                        if (result.tiles.size() >= tileLimit || result.retainedBytes + tileBytes > byteLimit)
                        {
                            break;
                        }
                        TileKey tileKey = new TileKey(image, request.level(), request.x(), request.y());
                        if (!result.tileKeys.add(tileKey)) {
                            continue;
                        }
                        Image tile = readTile(tileKey);
                        if (tile != null)
                        {
                            result.tiles.add(new LoadedTile(tileKey, tile));
                            result.retainedBytes += (long) tile.getWidth() * (long) tile.getHeight() * 4;
                        }
                    }
                }
                if (!before.equals(assetStamp(asset)))
                {
                    throw new IOException("Map image changed during preparation: " + asset);
                }
                return null;
                });
            } catch (OutOfMemoryError ex) {
                throw new IOException("Not enough memory to prepare map image: " + asset, ex);
            }
        }
        checkCancelled(cancelled);
        return result;
    }

    /**
     * Called only on FX after the project has successfully loaded. Frozen projects are never modified.
     */
    public void adopt(PreparedImages result)
    {
        images.putAll(result.images);
        failed.removeAll(result.images.keySet());
        loaded.addAll(result.tiles);
        changes.incrementAndGet();
        drainLoaded();
    }

    private static String assetStamp(Path file) throws IOException
    {
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        return attributes.size() + "|" + attributes.lastModifiedTime() + "|" + attributes.fileKey();
    }

    private record Placement(String path, double x, double y, double width, double height, double rotation, int z) {
    }

    private static List<Placement> placements(DmProject project) {
        return project.getImageLayers().stream().filter(DmProject.ImageLayer::isVisible)
                .map(layer -> new Placement(layer.getPath(), layer.getX(), layer.getY(), layer.getWidth(),
                        layer.getHeight(), layer.getRotationDeg(), layer.getZIndex())).toList();
    }

    private record CameraPosition(double x, double y, double zoom) {
        static CameraPosition of(DmProject.CameraState camera) {
            return new CameraPosition(camera.getX(), camera.getY(), camera.getZoom());
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws java.io.InterruptedIOException
    {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
        {
            throw new java.io.InterruptedIOException("Image preparation cancelled");
        }
    }

    record InitialTile(int level, int x, int y)
    {
    }

    static List<InitialTile> initialTiles(ImagePyramidBuilder.Meta meta, DmProject.ImageLayer layer,
                                          DmProject.CameraState camera, Viewport viewport, int limit)
    {
        if (viewport == null || viewport.width() <= 0 || viewport.height() <= 0 || camera.getZoom() <= 0 || limit <= 0)
        {
            return List.of();
        }
        boolean sideways = Math.round(layer.getRotationDeg() / 90.0) % 2 != 0;
        double dw = sideways ? layer.getHeight() : layer.getWidth();
        double dh = sideways ? layer.getWidth() : layer.getHeight();
        double scale = Math.max(dw / meta.width(), dh / meta.height()) * camera.getZoom() * viewport.outputScale();
        int level = scale >= 1 ? 0 : (int)Math.floor(Math.log(1 / scale) / Math.log(2));
        if (PerformanceMode.isEnabled())
        {
            level += PerformanceMode.imageLevelBias();
        }
        if (level >= meta.overviewLevel())
        {
            return List.of();
        }
        double cx = layer.getX() + layer.getWidth() / 2;
        double cy = layer.getY() + layer.getHeight() / 2;
        double halfW = viewport.width() / camera.getZoom() / 2;
        double halfH = viewport.height() / camera.getZoom() / 2;
        double angle = Math.toRadians(-layer.getRotationDeg());
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        for (int x : new int[] { -1, 1 })
        {
            for (int y : new int[] { -1, 1 })
            {
                double px = camera.getX() + x * halfW - cx;
                double py = camera.getY() + y * halfH - cy;
                double u = ((px * cos - py * sin) / dw + 0.5) * meta.width();
                double v = ((px * sin + py * cos) / dh + 0.5) * meta.height();
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
        }
        minU = Math.max(0, minU);
        minV = Math.max(0, minV);
        maxU = Math.min(meta.width(), maxU);
        maxV = Math.min(meta.height(), maxV);
        if (maxU <= minU || maxV <= minV)
        {
            return List.of();
        }
        int span = meta.tileSize() << level;
        List<InitialTile> result = new ArrayList<>();
        int x1 = Math.min(meta.tileColumns(level) - 1, (int)Math.floor((maxU - 1e-6) / span));
        int y1 = Math.min(meta.tileRows(level) - 1, (int)Math.floor((maxV - 1e-6) / span));
        for (int ty = (int)Math.floor(minV / span); ty <= y1 && result.size() < limit; ty++)
        {
            for (int tx = (int)Math.floor(minU / span); tx <= x1 && result.size() < limit; tx++)
            {
                result.add(new InitialTile(level, tx, ty));
            }
        }
        return result;
    }

    private void loadTilesForever()
    {
        while (true)
        {
            TileKey key;
            try
            {
                key = loadQueue.takeFirst();
            }
            catch (InterruptedException ex)
            {
                return;
            }
            Image image = readTile(key);
            // Picked up by the next frame of the render loop.
            loaded.add(new LoadedTile(key, image));
        }
    }

    private static Image readTile(TileKey key)
    {
        try
        {
            ImagePyramidBuilder.Meta meta = key.image().meta;
            Path path = ImagePyramidBuilder.tilePath(key.image().dir, meta, key.level(), key.tx(), key.ty());
            Image image = new Image(path.toUri().toString(), false);
            return image.isError() || image.getWidth() <= 0 ? null : image;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private record TileKey(MapImage image, int level, int tx, int ty)
    {
    }

    private record LoadedTile(TileKey key, Image image)
    {
    }

    /**
     * A map image that can be drawn at any zoom level with as much detail as the screen can show.
     */
    public final class MapImage
    {
        private final Image base;
        private final Path dir;
        private volatile ImagePyramidBuilder.Meta meta;
        private volatile boolean buildNeeded;
        private java.util.concurrent.Future<?> build;

        private MapImage(Image base, Path dir)
        {
            this.base = base;
            this.dir = dir;
        }

        /**
         * Draws the whole image into the rectangle (in the current GC transform). Returns false while
         * detail tiles are still loading, so callers that cache the result know to draw again.
         */
        public boolean draw(GraphicsContext gc, double dx, double dy, double dw, double dh)
        {
            gc.drawImage(base, dx, dy, dw, dh);
            ImagePyramidBuilder.Meta meta = this.meta;
            if (meta == null || dw <= 0 || dh <= 0)
            {
                return true;
            }
            Affine transform = gc.getTransform();
            double transformScale = Math.sqrt(Math.abs(transform.determinant()));
            double screenPixelsPerSourcePixel = Math.max(dw / meta.width(), dh / meta.height())
                    * transformScale * outputScale(gc);
            int level = screenPixelsPerSourcePixel >= 1.0
                        ? 0
                        : (int)Math.floor(Math.log(1.0 / screenPixelsPerSourcePixel) / Math.log(2));
            if (PerformanceMode.isEnabled())
            {
                level += PerformanceMode.imageLevelBias();
            }
            if (level >= meta.overviewLevel())
            {
                return true;
            }

            double[] visible = visibleLocalBounds(gc, transform);
            if (visible == null)
            {
                return true;
            }
            double u0 = Math.max(0, (visible[0] - dx) / dw * meta.width());
            double v0 = Math.max(0, (visible[1] - dy) / dh * meta.height());
            double u1 = Math.min(meta.width(), (visible[2] - dx) / dw * meta.width());
            double v1 = Math.min(meta.height(), (visible[3] - dy) / dh * meta.height());
            if (u1 <= u0 || v1 <= v0)
            {
                return true;
            }
            int span = meta.tileSize() << level;
            int tx0 = (int)Math.floor(u0 / span);
            int ty0 = (int)Math.floor(v0 / span);
            int tx1 = Math.min(meta.tileColumns(level) - 1, (int)Math.floor((u1 - 1e-6) / span));
            int ty1 = Math.min(meta.tileRows(level) - 1, (int)Math.floor((v1 - 1e-6) / span));
            double levelScale = 1 << level;
            boolean complete = true;
            for (int ty = ty0; ty <= ty1; ty++)
            {
                for (int tx = tx0; tx <= tx1; tx++)
                {
                    Image tile = tile(this, level, tx, ty);
                    if (tile == null)
                    {
                        complete &= broken.contains(new TileKey(this, level, tx, ty));
                        continue;
                    }
                    double pu = (double)tx * span;
                    double pv = (double)ty * span;
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

        private double[] visibleLocalBounds(GraphicsContext gc, Affine transform)
        {
            double cw = gc.getCanvas().getWidth();
            double ch = gc.getCanvas().getHeight();
            try
            {
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                for (double[] corner : new double[][] { { 0, 0 }, { cw, 0 }, { 0, ch }, { cw, ch } })
                {
                    Point2D p = transform.inverseTransform(corner[0], corner[1]);
                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxX = Math.max(maxX, p.getX());
                    maxY = Math.max(maxY, p.getY());
                }
                return new double[] { minX, minY, maxX, maxY };
            }
            catch (NonInvertibleTransformException ex)
            {
                return null;
            }
        }

        private double outputScale(GraphicsContext gc)
        {
            var scene = gc.getCanvas().getScene();
            if (scene == null || scene.getWindow() == null)
            {
                return 1.0;
            }
            return Math.max(1.0, Math.max(scene.getWindow().getOutputScaleX(), scene.getWindow().getOutputScaleY()));
        }
    }
}
