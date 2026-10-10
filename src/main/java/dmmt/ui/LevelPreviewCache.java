package dmmt.ui;

import dmmt.service.ProjectService;
import dmmt.service.ThumbnailService;
import dmmt.service.WorkScheduler;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Small preview images of level files, loaded in the background and cached by file and thumbnail time stamp.
 * Only used on the JavaFX thread.
 */
public final class LevelPreviewCache implements AutoCloseable
{
    private record Cached(Image image, long stamp)
    {
    }

    private final ProjectService projectService;
    private final Map<Path, Cached> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<Path> loading = new HashSet<>();
    private final Map<Path, Future<?>> tasks = new LinkedHashMap<>();
    private long generation;
    private boolean closed;
    private static final int LIMIT = 24;

    public LevelPreviewCache(ProjectService projectService)
    {
        this.projectService = projectService;
    }

    /**
     * The preview of {@code levelFile}, or {@code null} while it is loading (then {@code onLoaded} runs on the
     * JavaFX thread once it is there) or if the level has no picture.
     */
    public Image get(Path levelFile, Runnable onLoaded)
    {
        Path key = levelFile.toAbsolutePath().normalize();
        Cached cached = cache.get(key);
        long stamp = stamp(key);
        if (cached != null && cached.stamp() == stamp)
        {
            return cached.image();
        }
        if (!closed && loading.size() < LIMIT && loading.add(key))
        {
            long requestGeneration = generation;
            try {
                Future<?> task = WorkScheduler.shared().submit(WorkScheduler.Kind.IMAGE, false, () -> {
                Image image = null;
                try
                {
                    byte[] png = projectService.loadOrCreateThumbnail(key);
                    if (png != null)
                    {
                        image = new Image(new ByteArrayInputStream(png));
                    }
                }
                catch (IOException | RuntimeException | OutOfMemoryError ignored)
                {
                    // no preview
                }
                Image loaded = image;
                long loadedStamp = stamp(key);
                Platform.runLater(() -> {
                    if (closed || generation != requestGeneration)
                    {
                        return;
                    }
                    loading.remove(key);
                    tasks.remove(key);
                    cache.put(key, new Cached(loaded, loadedStamp));
                    while (cache.size() > LIMIT)
                    {
                        cache.remove(cache.keySet().iterator().next());
                    }
                    if (onLoaded != null)
                    {
                        onLoaded.run();
                    }
                });
                return null;
                });
                tasks.put(key, task);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                loading.remove(key);
            }
        }
        return cached == null ? null : cached.image();
    }

    /**
     * Forgets all previews (e.g. when another map is opened).
     */
    public void clear()
    {
        generation++;
        tasks.values().forEach(task -> task.cancel(true));
        tasks.clear();
        loading.clear();
        cache.clear();
    }

    @Override
    public void close()
    {
        closed = true;
        clear();
    }

    private static long stamp(Path levelFile)
    {
        try
        {
            Path thumbnail = ThumbnailService.thumbnailFile(levelFile);
            return Files.isRegularFile(thumbnail) ? Files.getLastModifiedTime(thumbnail).toMillis() : -1;
        }
        catch (IOException | RuntimeException ex)
        {
            return -1;
        }
    }
}
