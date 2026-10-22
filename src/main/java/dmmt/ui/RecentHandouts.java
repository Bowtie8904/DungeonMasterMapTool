package dmmt.ui;

import dmmt.log.Log;
import dmmt.service.AppSettings;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;

/**
 * Persistent list of recently pasted handout images: one PNG per image (named by a hash of its pixels, so the same
 * image is stored only once) and an index file listing the file names, most recent first.
 */
public final class RecentHandouts {
    private static final Log LOG = Log.of(RecentHandouts.class);
    public static final String DEFAULT_FOLDER = "dmmap-handouts";
    static final String INDEX_FILE = "recent.txt";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16,64}");
    private static final class WorkerHolder {
        private static final ExecutorService WORKER = new java.util.concurrent.ThreadPoolExecutor(0, 1, 1,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                    var thread = new Thread(runnable, "recent-handouts");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public static ExecutorService worker() {
        return WorkerHolder.WORKER;
    }

    private final Path folder;
    private final List<String> ids = new ArrayList<>();
    private long version;

    public synchronized long version() {
        return version;
    }

    public RecentHandouts(Path folder) {
        this.folder = folder;
        load();
    }

    /** The store next to the settings file, like the map and audio libraries. */
    public static RecentHandouts openDefault() {
        return open(AppSettings.resolveFile());
    }

    public static RecentHandouts open(Path settingsFile) {
        return new RecentHandouts(AppSettings.resolveConfiguredFolder(settingsFile, DEFAULT_FOLDER,
                DEFAULT_FOLDER));
    }

    /** Ids of the stored images, most recent first. */
    public synchronized List<String> ids() {
        return List.copyOf(ids);
    }

    public Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid recent handout ID.");
        return folder.resolve(id + ".png");
    }

    /**
     * Stores the image (or moves an identical stored image to the top) and trims the list to {@code max} entries.
     * Returns the id of the image, or null if nothing is stored ({@code max <= 0}).
     */
    public String add(Image image, int max) throws IOException {
        if (max <= 0) {
            trim(0);
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        if (width <= 0 || height <= 0 || reader == null) {
            return null;
        }
        MessageDigest digest = sha256();
        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        ByteBuffer rowBytes = ByteBuffer.allocate(width * 4);
        for (int y = 0; y < height; y++) {
            reader.getPixels(0, y, width, 1, PixelFormat.getIntArgbInstance(), row, 0, width);
            rowBytes.clear();
            rowBytes.asIntBuffer().put(row);
            digest.update(rowBytes.array());
            png.setRGB(0, y, width, 1, row, 0, width);
        }
        String id = HexFormat.of().formatHex(digest.digest(), 0, 16);
        Path target = file(id);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(folder);
            Path temp = folder.resolve(id + ".png.tmp");
            if (!ImageIO.write(png, "png", temp.toFile())) {
                Files.deleteIfExists(temp);
                throw new IOException("No PNG encoder is available.");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (this) {
            ids.remove(id);
            ids.addFirst(id);
            version++;
        }
        trim(max);
        return id;
    }

    /** Moves a stored image to the top of the list. */
    public void moveToTop(String id) throws IOException {
        synchronized (this) {
            if (!ids.remove(id)) {
                return;
            }
            ids.addFirst(id);
            version++;
        }
        save();
    }

    /** Removes an image from the list and deletes its file. */
    public void remove(String id) throws IOException {
        synchronized (this) {
            if (!ids.remove(id)) {
                return;
            }
            version++;
        }
        Files.deleteIfExists(file(id));
        LOG.info("Removed recent handout {}", id);
        save();
    }

    /** Drops the oldest entries (and their files) beyond {@code max}. */
    public void trim(int max) throws IOException {
        List<String> dropped = new ArrayList<>();
        synchronized (this) {
            while (ids.size() > Math.max(0, max)) {
                dropped.add(ids.removeLast());
            }
            if (!dropped.isEmpty()) version++;
        }
        for (String id : dropped) {
            Files.deleteIfExists(file(id));
        }
        if (!dropped.isEmpty() || Files.isDirectory(folder)) {
            save();
        }
    }

    private void load() {
        Path index = folder.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String id = line.trim();
                if (ID.matcher(id).matches() && !ids.contains(id) && Files.isRegularFile(file(id))) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read recent handouts index {}", index, e);
            ids.clear();
        }
    }

    private void save() throws IOException {
        List<String> snapshot = ids();
        if (snapshot.isEmpty() && !Files.isDirectory(folder)) {
            return;
        }
        Files.createDirectories(folder);
        Path index = folder.resolve(INDEX_FILE);
        Path temp = folder.resolve(INDEX_FILE + ".tmp");
        Files.write(temp, snapshot, StandardCharsets.UTF_8);
        Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
