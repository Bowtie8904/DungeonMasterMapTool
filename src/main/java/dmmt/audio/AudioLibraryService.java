package dmmt.audio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The global audio library (3.35.1): an app-managed folder with the imported audio files, the index that gives them
 * names, kinds and categories, and the cached waveform peaks.
 *
 * <pre>
 * &lt;root&gt;/library.json   index (atomically rewritten after every change)
 * &lt;root&gt;/files/         the imported and cut audio files
 * &lt;root&gt;/peaks/         cached waveform peaks (derived, may be deleted)
 * </pre>
 *
 * Imports copy the picked file into {@code files/}, so the library keeps working when the original is gone. All
 * methods are synchronized; file IO is the caller's responsibility to keep off the JavaFX thread.
 */
public class AudioLibraryService {
    /** Version of {@code library.json}; 2 hides "Uncategorised" from the overlay (3.35.2). */
    public static final int SCHEMA_VERSION = 2;
    public static final String INDEX_FILE = "library.json";
    public static final String FILES_FOLDER = "files";
    public static final String PEAKS_FOLDER = "peaks";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path root;
    private AudioLibrary library;

    public AudioLibraryService(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.library = load();
    }

    public Path root() {
        return root;
    }

    public Path filesFolder() {
        return root.resolve(FILES_FOLDER);
    }

    public Path peaksFolder() {
        return root.resolve(PEAKS_FOLDER);
    }

    /** Full path of a track's audio file. */
    public Path fileOf(AudioTrack track) {
        return filesFolder().resolve(track.getFile());
    }

    /** Cache file of a track's waveform peaks. */
    public Path peaksFileOf(AudioTrack track) {
        return peaksFolder().resolve(track.getId() + ".peaks");
    }

    // ---- Index ----

    /** Reads the index; a missing or unreadable index starts an empty library instead of failing the app. */
    private AudioLibrary load() {
        Path index = root.resolve(INDEX_FILE);
        AudioLibrary loaded = null;
        if (Files.isRegularFile(index)) {
            try {
                loaded = mapper.readValue(index.toFile(), AudioLibrary.class);
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not read the audio library " + index + ": " + e.getMessage());
            }
        }
        if (loaded == null) {
            loaded = AudioLibrary.builder().build();
        }
        if (loaded.getCategories() == null) {
            loaded.setCategories(new ArrayList<>());
        }
        if (loaded.getTracks() == null) {
            loaded.setTracks(new ArrayList<>());
        }
        if (loaded.getCategories().stream().noneMatch(AudioCategory::isUncategorised)) {
            loaded.getCategories().add(AudioCategory.uncategorised());
        }
        if (loaded.getSchemaVersion() < SCHEMA_VERSION) {
            // Schema 2 hides "Uncategorised" from the overlay; older libraries are migrated once (3.35.2).
            loaded.getCategories().stream().filter(AudioCategory::isUncategorised)
                    .forEach(category -> category.setHidden(true));
            loaded.setSchemaVersion(SCHEMA_VERSION);
        }
        return loaded;
    }

    /** Writes the index to a temporary file and moves it into place, so a crash cannot truncate the library. */
    public synchronized void save() throws IOException {
        Files.createDirectories(root);
        Path index = root.resolve(INDEX_FILE);
        Path temp = index.resolveSibling(INDEX_FILE + ".tmp");
        mapper.writeValue(temp.toFile(), library);
        try {
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Re-reads the index from disk, e.g. after the library folder setting changed. */
    public synchronized void reload() {
        library = load();
    }

    // ---- Categories ----

    public synchronized List<AudioCategory> categories() {
        List<AudioCategory> categories = new ArrayList<>(library.getCategories());
        categories.sort(Comparator.comparing((AudioCategory c) -> c.isUncategorised() ? 1 : 0)
                .thenComparing(c -> c.getName() == null ? "" : c.getName(), String.CASE_INSENSITIVE_ORDER));
        return categories;
    }

    public synchronized Optional<AudioCategory> category(String id) {
        return library.getCategories().stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    /** Creates a category with a unique name; returns the stored category. */
    public synchronized AudioCategory createCategory(String name, String color, String icon) throws IOException {
        AudioCategory category = AudioCategory.builder()
                .name(uniqueCategoryName(cleanName(name, "New category"), null))
                .color(color == null || color.isBlank() ? AudioCategory.DEFAULT_COLOR : color)
                .icon(icon == null || icon.isBlank() ? AudioCategory.DEFAULT_ICON : icon)
                .build();
        library.getCategories().add(category);
        save();
        return category;
    }

    /** Finds a category by name, ignoring case (3.35.2). */
    public synchronized Optional<AudioCategory> categoryByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return library.getCategories().stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(wanted))
                .findFirst();
    }

    /**
     * The category with this name, created with exactly this spelling when none matches case-insensitively.
     * Used by the folder import, where subfolder names become categories (3.35.1).
     */
    public synchronized AudioCategory categoryForName(String name) throws IOException {
        Optional<AudioCategory> existing = categoryByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createCategory(name, null, null);
    }

    /** Renames a category; the built-in "Uncategorised" category cannot be renamed. */
    public synchronized void renameCategory(String id, String name) throws IOException {
        AudioCategory category = category(id).orElseThrow(() -> new IOException("Unknown category."));
        if (category.isUncategorised()) {
            throw new IOException("The " + AudioCategory.UNCATEGORISED_NAME + " category cannot be renamed.");
        }
        category.setName(uniqueCategoryName(cleanName(name, category.getName()), id));
        save();
    }

    public synchronized void styleCategory(String id, String color, String icon) throws IOException {
        AudioCategory category = category(id).orElseThrow(() -> new IOException("Unknown category."));
        if (color != null && !color.isBlank()) {
            category.setColor(color);
        }
        if (icon != null && !icon.isBlank()) {
            category.setIcon(icon);
        }
        save();
    }

    /** Deletes a category; its tracks move to "Uncategorised" so no audio is ever lost. */
    public synchronized void deleteCategory(String id) throws IOException {
        AudioCategory category = category(id).orElseThrow(() -> new IOException("Unknown category."));
        if (category.isUncategorised()) {
            throw new IOException("The " + AudioCategory.UNCATEGORISED_NAME + " category cannot be deleted.");
        }
        library.getCategories().remove(category);
        for (AudioTrack track : library.getTracks()) {
            if (id.equals(track.getCategoryId())) {
                track.setCategoryId(AudioCategory.UNCATEGORISED_ID);
            }
        }
        save();
    }

    private String uniqueCategoryName(String wanted, String ownId) {
        Set<String> taken = new LinkedHashSet<>();
        for (AudioCategory category : library.getCategories()) {
            if (!category.getId().equals(ownId)) {
                taken.add(category.getName().toLowerCase(Locale.ROOT));
            }
        }
        String name = wanted;
        int index = 2;
        while (taken.contains(name.toLowerCase(Locale.ROOT))) {
            name = wanted + " " + index++;
        }
        return name;
    }

    // ---- Tracks ----

    public synchronized List<AudioTrack> tracks() {
        return List.copyOf(library.getTracks());
    }

    public synchronized Optional<AudioTrack> track(String id) {
        return library.getTracks().stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    /** Music tracks of a category, ordered by name. */
    public synchronized List<AudioTrack> musicOf(String categoryId) {
        return library.getTracks().stream()
                .filter(AudioTrack::isMusic)
                .filter(t -> Objects.equals(categoryId, t.getCategoryId()))
                .sorted(Comparator.comparing(AudioTrack::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** All sound effects, ordered by name. */
    public synchronized List<AudioTrack> effects() {
        return library.getTracks().stream()
                .filter(t -> t.getKind() == AudioKind.EFFECT)
                .sorted(Comparator.comparing(AudioTrack::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Categories that the audio overlay shows: not hidden and not empty. An empty category has nothing to play,
     * so its ring button would only take up a slot (3.35.2).
     */
    public synchronized List<AudioCategory> visibleCategories() {
        return categories().stream()
                .filter(c -> !c.isHidden())
                .filter(c -> !musicOf(c.getId()).isEmpty())
                .toList();
    }

    /** Sound effects that the audio overlay shows, i.e. everything that is not hidden (3.35.2). */
    public synchronized List<AudioTrack> visibleEffects() {
        return effects().stream().filter(t -> !t.isHidden()).toList();
    }

    /** Hides a category from the overlay, or shows it again; it keeps playing and keeps its API endpoint. */
    public synchronized void setCategoryHidden(String id, boolean hidden) throws IOException {
        AudioCategory category = category(id).orElseThrow(() -> new IOException("Unknown category."));
        category.setHidden(hidden);
        save();
    }

    /** Hides a sound effect from the overlay, or shows it again. */
    public synchronized void setTrackHidden(String id, boolean hidden) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown audio track."));
        track.setHidden(hidden);
        save();
    }

    /** Colour and/or icon of a sound effect's overlay button; {@code null} keeps the current value. */
    public synchronized void styleTrack(String id, String color, String icon) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown audio track."));
        if (color != null && !color.isBlank()) {
            track.setColor(color);
        }
        if (icon != null && !icon.isBlank()) {
            track.setIcon(icon);
        }
        save();
    }

    /**
     * Copies {@code source} into the library and indexes it.
     *
     * @param categoryId category of a music track; ignored for sound effects
     */
    public synchronized AudioTrack importFile(Path source, AudioKind kind, String categoryId) throws IOException {
        AudioFormats.validate(source);
        Path target = copyIntoLibrary(source, source.getFileName().toString());
        String original = AudioFormats.stripExtension(source.getFileName().toString());
        return index(target, original, original, kind, categoryId, null, 0, 0);
    }

    /**
     * One file found by {@link #scanFolder(Path)}: the file itself and the name of the subfolder it lies in, which
     * becomes its music category. {@code folderCategory} is {@code null} for files directly in the picked folder.
     */
    public record FolderFile(Path file, String folderCategory) {
    }

    /**
     * Collects the supported audio files of a folder and all of its subfolders (3.35.1), in a stable order.
     * A file inside a subfolder carries the name of its <em>direct</em> parent folder, however deeply it is
     * nested; a file lying directly in {@code root} carries {@code null}. Hidden files and folders are skipped.
     */
    public static List<FolderFile> scanFolder(Path root) throws IOException {
        Path start = root.toAbsolutePath().normalize();
        List<FolderFile> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(start)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(AudioFormats::isSupported)
                    .filter(path -> !isHidden(start, path))
                    .sorted()
                    .toList();
            for (Path file : files) {
                Path parent = file.getParent();
                boolean inRoot = parent == null || parent.equals(start);
                found.add(new FolderFile(file, inRoot || parent.getFileName() == null
                        ? null : parent.getFileName().toString()));
            }
        }
        return found;
    }

    /** True when the file itself or one of the folders between it and {@code root} starts with a dot. */
    private static boolean isHidden(Path root, Path file) {
        for (Path path = file; path != null && !path.equals(root); path = path.getParent()) {
            Path name = path.getFileName();
            if (name != null && name.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a file that has already been written into the library's {@code files/} folder (a cut clip, 3.35.3).
     * {@code sourceTrack} is only recorded for information; the new track is fully standalone.
     */
    public synchronized AudioTrack addClip(Path fileInLibrary, String name, AudioKind kind, String categoryId,
                                           AudioTrack sourceTrack, long startMs, long endMs) throws IOException {
        if (!fileInLibrary.toAbsolutePath().normalize().startsWith(filesFolder())) {
            throw new IOException("A clip must be written into the library's files folder.");
        }
        String original = sourceTrack == null ? name : sourceTrack.getName();
        return index(fileInLibrary, name, original, kind, categoryId,
                sourceTrack == null ? null : sourceTrack.getId(), startMs, endMs);
    }

    private AudioTrack index(Path file, String name, String originalFileName, AudioKind kind, String categoryId,
                             String sourceTrackId, long startMs, long endMs) throws IOException {
        AudioTrack track = AudioTrack.builder()
                .name(cleanName(name, "Audio"))
                .file(file.getFileName().toString())
                .kind(kind == null ? AudioKind.MUSIC : kind)
                .categoryId(resolveCategory(kind, categoryId))
                .durationMs(AudioFormats.durationMs(file))
                .originalFileName(originalFileName)
                .sourceTrackId(sourceTrackId)
                .sourceStartMs(startMs)
                .sourceEndMs(endMs)
                .build();
        library.getTracks().add(track);
        save();
        return track;
    }

    /** A free file name inside {@code files/} for the given name, keeping the extension. */
    public synchronized Path reserveClipFile(String name, String extension) throws IOException {
        Files.createDirectories(filesFolder());
        return uniqueFile(sanitizeFileName(name) + "." + extension.toLowerCase(Locale.ROOT));
    }

    private Path copyIntoLibrary(Path source, String fileName) throws IOException {
        Files.createDirectories(filesFolder());
        Path target = uniqueFile(sanitizeFileName(AudioFormats.stripExtension(fileName))
                + "." + AudioFormats.extensionOf(source));
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private Path uniqueFile(String fileName) {
        Path target = filesFolder().resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        String base = AudioFormats.stripExtension(fileName);
        String extension = fileName.substring(base.length());
        for (int i = 2; ; i++) {
            Path candidate = filesFolder().resolve(base + " " + i + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    public synchronized void renameTrack(String id, String name) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown track."));
        track.setName(cleanName(name, track.getName()));
        save();
    }

    /** Moves a music track into another category (ignored for sound effects). */
    public synchronized void moveTrack(String id, String categoryId) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown track."));
        if (track.isMusic()) {
            track.setCategoryId(resolveCategory(AudioKind.MUSIC, categoryId));
            save();
        }
    }

    /** Switches a track between music and sound effect. */
    public synchronized void changeKind(String id, AudioKind kind, String categoryId) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown track."));
        track.setKind(kind);
        track.setCategoryId(resolveCategory(kind, categoryId == null ? track.getCategoryId() : categoryId));
        save();
    }

    /** Removes a track from the index and deletes its audio file and cached peaks. */
    public synchronized void deleteTrack(String id) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown track."));
        library.getTracks().remove(track);
        save();
        Files.deleteIfExists(fileOf(track));
        Files.deleteIfExists(peaksFileOf(track));
    }

    /** Tracks whose index entry has no file on disk any more (e.g. deleted outside the app). */
    public synchronized List<AudioTrack> missingFiles() {
        return library.getTracks().stream().filter(t -> !Files.isRegularFile(fileOf(t))).toList();
    }

    private String resolveCategory(AudioKind kind, String categoryId) {
        if (kind == AudioKind.EFFECT) {
            return null;
        }
        return categoryId != null && category(categoryId).isPresent() ? categoryId : AudioCategory.UNCATEGORISED_ID;
    }

    static String cleanName(String raw, String fallback) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        return name.isEmpty() ? fallback : name;
    }

    /** File-system safe name; the display name is kept separately in the index. */
    static String sanitizeFileName(String raw) {
        String name = cleanName(raw, "audio").replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() > 80) {
            name = name.substring(0, 80).trim();
        }
        return name.isEmpty() ? "audio" : name;
    }
}
