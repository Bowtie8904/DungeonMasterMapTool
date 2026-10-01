package dmmt.ui;

import dmmt.service.ProjectService;
import dmmt.service.ThumbnailService;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small preview images of level files, loaded in the background and cached by file and thumbnail time stamp.
 * Only used on the JavaFX thread.
 */
public final class LevelPreviewCache {
    private record Cached(Image image, long stamp) {
    }

    private final ProjectService projectService;
    private final Map<Path, Cached> cache = new HashMap<>();
    private final Set<Path> loading = new HashSet<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "level-previews");
        thread.setDaemon(true);
        return thread;
    });

    public LevelPreviewCache(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * The preview of {@code levelFile}, or {@code null} while it is loading (then {@code onLoaded} runs on the
     * JavaFX thread once it is there) or if the level has no picture.
     */
    public Image get(Path levelFile, Runnable onLoaded) {
        Path key = levelFile.toAbsolutePath().normalize();
        Cached cached = cache.get(key);
        long stamp = stamp(key);
        if (cached != null && cached.stamp() == stamp) {
            return cached.image();
        }
        if (loading.add(key)) {
            loader.execute(() -> {
                Image image = null;
                try {
                    byte[] png = projectService.loadOrCreateThumbnail(key);
                    if (png != null) {
                        image = new Image(new ByteArrayInputStream(png));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // no preview
                }
                Image loaded = image;
                long loadedStamp = stamp(key);
                Platform.runLater(() -> {
                    loading.remove(key);
                    cache.put(key, new Cached(loaded, loadedStamp));
                    if (onLoaded != null) {
                        onLoaded.run();
                    }
                });
            });
        }
        return cached == null ? null : cached.image();
    }

    /** Forgets all previews (e.g. when another map is opened). */
    public void clear() {
        cache.clear();
    }

    private static long stamp(Path levelFile) {
        try {
            Path thumbnail = ThumbnailService.thumbnailFile(levelFile);
            return Files.isRegularFile(thumbnail) ? Files.getLastModifiedTime(thumbnail).toMillis() : -1;
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }
}
