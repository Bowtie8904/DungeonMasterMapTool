package dmmt.audio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * Imports copy the picked file into {@code files/}, so the library keeps working when the original is gone.
 * Index changes are synchronized; decoding and loudness analysis run outside the library lock.
 */
public class AudioLibraryService {
    /** Version of {@code library.json}; 4 distinguishes peak-safe sources from limiter renders (3.35.5). */
    public static final int SCHEMA_VERSION = 4;
    /** Highest manual absolute gain, independent of the file's measured peak-safe headroom. */
    public static final double MANUAL_MAXIMUM_GAIN_DB = 24;
    public static final String INDEX_FILE = "library.json";
    public static final String FILES_FOLDER = "files";
    public static final String PEAKS_FOLDER = "peaks";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path root;
    private AudioLibrary library;
    private final Set<Path> clipsBeingIndexed = new HashSet<>();

    public AudioLibraryService(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.library = load();
        try {
            deleteStalePlaybackFiles();
        } catch (IOException e) {
            System.err.println("Could not clean up replaced audio playback copies: " + e.getMessage());
        }
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

    /** File to play; prepared PCM copies are separate from originals and remain inside {@code files/}. */
    public synchronized Path playbackFileOf(AudioTrack track) {
        return track.getPlaybackFile() == null ? fileOf(track) : filesFolder().resolve(track.getPlaybackFile());
    }

    /** The playback file and the gain baked into it, read atomically so a voice never mixes two analyses. */
    public record PlaybackSource(Path file, double bakedGainDb) {
    }

    public synchronized PlaybackSource playbackSourceOf(AudioTrack track) {
        return new PlaybackSource(playbackFileOf(track), track.getPlaybackGainDb());
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
        if (loaded.getStalePlaybackFiles() == null) {
            loaded.setStalePlaybackFiles(new ArrayList<>());
        }
        for (AudioTrack track : loaded.getTracks()) {
            if (track.getPeakSafePlaybackFile() == null && track.getPlaybackFile() != null
                    && !track.getPlaybackFile().endsWith(".limited.wav")) {
                track.setPeakSafePlaybackFile(track.getPlaybackFile());
                track.setPeakSafePlaybackGainDb(track.getPlaybackGainDb());
            }
        }
        if (loaded.getCategories().stream().noneMatch(AudioCategory::isUncategorised)) {
            loaded.getCategories().add(AudioCategory.uncategorised());
        }
        if (loaded.getSchemaVersion() < SCHEMA_VERSION) {
            // Schema 2 hides "Uncategorised" from the overlay; older libraries are migrated once (3.35.2).
            if (loaded.getSchemaVersion() < 2) {
                loaded.getCategories().stream().filter(AudioCategory::isUncategorised)
                        .forEach(category -> category.setHidden(true));
            }
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
    public AudioTrack importFile(Path source, AudioKind kind, String categoryId) throws IOException {
        AudioFormats.validate(source);
        Path target;
        synchronized (this) {
            target = reserveImportFile(source, source.getFileName().toString());
        }
        String original = AudioFormats.stripExtension(source.getFileName().toString());
        String id = java.util.UUID.randomUUID().toString();
        Path prepared = null;
        try {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            LoudnessAnalyzer.Measurement measurement = LoudnessAnalyzer.analyze(target);
            String playbackFile = null;
            double playbackGainDb = 0;
            if (measurement.maximumGainDb() > 0) {
                playbackFile = id + ".playback.wav";
                prepared = filesFolder().resolve(playbackFile);
                LoudnessAnalyzer.writePrepared(target, prepared, measurement.maximumGainDb(),
                        measurement.channels(), measurement.sampleRate());
                playbackGainDb = measurement.maximumGainDb();
            }
            checkImportInterrupted();
            AudioTrack track = createTrack(id, target, original, original, kind, categoryId, null, 0, 0,
                    measurement, playbackFile, playbackGainDb);
            synchronized (this) {
                checkImportInterrupted();
                track.setCategoryId(resolveCategory(kind, categoryId));
                library.getTracks().add(track);
                try {
                    save();
                } catch (IOException | RuntimeException e) {
                    library.getTracks().remove(track);
                    throw e;
                }
            }
            return track;
        } catch (IOException | RuntimeException e) {
            cleanupOnFailure(e, target, prepared);
            throw e;
        }
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
    public AudioTrack addClip(Path fileInLibrary, String name, AudioKind kind, String categoryId,
                              AudioTrack sourceTrack, long startMs, long endMs) throws IOException {
        Path clip = fileInLibrary.toAbsolutePath().normalize();
        if (!clip.startsWith(filesFolder())) {
            throw new IOException("A clip must be written into the library's files folder.");
        }
        synchronized (this) {
            if (library.getTracks().stream().anyMatch(track -> fileOf(track).equals(clip))) {
                throw new IOException("This audio file is already in the library.");
            }
            if (!clipsBeingIndexed.add(clip)) {
                throw new IOException("This clip is already being added to the library.");
            }
        }
        String original = sourceTrack == null ? name : sourceTrack.getName();
        String id = java.util.UUID.randomUUID().toString();
        Path prepared = null;
        try {
            LoudnessAnalyzer.Measurement measurement = LoudnessAnalyzer.analyze(clip);
            String playbackFile = null;
            double playbackGainDb = 0;
            if (measurement.maximumGainDb() > 0) {
                playbackFile = id + ".playback.wav";
                prepared = filesFolder().resolve(playbackFile);
                LoudnessAnalyzer.writePrepared(clip, prepared, measurement.maximumGainDb(),
                        measurement.channels(), measurement.sampleRate());
                playbackGainDb = measurement.maximumGainDb();
            }
            checkImportInterrupted();
            AudioTrack track = createTrack(id, clip, name, original, kind, categoryId,
                    sourceTrack == null ? null : sourceTrack.getId(), startMs, endMs,
                    measurement, playbackFile, playbackGainDb);
            synchronized (this) {
                checkImportInterrupted();
                if (library.getTracks().stream().anyMatch(existing -> fileOf(existing).equals(clip))) {
                    throw new IOException("This clip is already in the library.");
                }
                track.setCategoryId(resolveCategory(kind, categoryId));
                library.getTracks().add(track);
                try {
                    save();
                } catch (IOException | RuntimeException e) {
                    library.getTracks().remove(track);
                    throw e;
                }
            }
            return track;
        } catch (IOException | RuntimeException e) {
            cleanupOnFailure(e, clip, prepared);
            throw e;
        } finally {
            synchronized (this) {
                clipsBeingIndexed.remove(clip);
            }
        }
    }

    private AudioTrack createTrack(String id, Path file, String name, String originalFileName,
                                   AudioKind kind, String categoryId, String sourceTrackId,
                                   long startMs, long endMs, LoudnessAnalyzer.Measurement measurement,
                                   String playbackFile, double playbackGainDb) {
        return AudioTrack.builder()
                .id(id)
                .name(cleanName(name, "Audio"))
                .file(file.getFileName().toString())
                .kind(kind == null ? AudioKind.MUSIC : kind)
                .categoryId(resolveCategory(kind, categoryId))
                .durationMs(AudioFormats.durationMs(file))
                .originalFileName(originalFileName)
                .sourceTrackId(sourceTrackId)
                .sourceStartMs(startMs)
                .sourceEndMs(endMs)
                .loudnessLufs(measurement.loudnessLufs())
                .samplePeakDbfs(measurement.samplePeakDbfs())
                .peakCeilingDbfs(LoudnessAnalyzer.PEAK_CEILING_DBFS)
                .peakHeadroomDb(measurement.peakHeadroomDb())
                .autoGainDb(measurement.autoGainDb())
                .maxGainDb(measurement.maximumGainDb())
                .audioChannels(measurement.channels())
                .audioSampleRate(measurement.sampleRate())
                .playbackFile(playbackFile)
                .playbackGainDb(playbackGainDb)
                .peakSafePlaybackFile(playbackFile)
                .peakSafePlaybackGainDb(playbackGainDb)
                .build();
    }

    /** Re-measures a legacy entry or refreshes the analysis of an existing one. */
    public void analyzeLoudness(String id) throws IOException {
        TrackAnalysis snapshot;
        Path original;
        synchronized (this) {
            AudioTrack current = track(id).orElseThrow(() -> new IOException("Unknown audio track."));
            snapshot = TrackAnalysis.of(current);
            original = fileOf(current);
        }
        LoudnessAnalyzer.Measurement measurement = LoudnessAnalyzer.analyze(original);
        PreparedSources sources = prepareSources(id, original, measurement, snapshot.gainOverrideDb);
        boolean committed = false;
        try {
            checkImportInterrupted();
            synchronized (this) {
                checkImportInterrupted();
                AudioTrack current = track(id).orElseThrow(() -> new IOException("The audio track was deleted."));
                if (!snapshot.matches(current)) {
                    throw new IOException("The audio track changed while it was being analyzed; try again.");
                }
                List<String> staleBefore = new ArrayList<>(library.getStalePlaybackFiles());
                current.setLoudnessLufs(measurement.loudnessLufs());
                current.setSamplePeakDbfs(measurement.samplePeakDbfs());
                current.setPeakCeilingDbfs(LoudnessAnalyzer.PEAK_CEILING_DBFS);
                current.setPeakHeadroomDb(measurement.peakHeadroomDb());
                current.setAutoGainDb(measurement.autoGainDb());
                current.setMaxGainDb(measurement.maximumGainDb());
                current.setAudioChannels(measurement.channels());
                current.setAudioSampleRate(measurement.sampleRate());
                current.setPeakSafePlaybackFile(sources.peakSafeFile);
                current.setPeakSafePlaybackGainDb(sources.peakSafeGainDb);
                current.setPlaybackFile(sources.playbackFile);
                current.setPlaybackGainDb(sources.playbackGainDb);
                addObsoletePlaybackFiles(current, snapshot);
                try {
                    save();
                } catch (IOException | RuntimeException e) {
                    snapshot.restore(current);
                    library.setStalePlaybackFiles(staleBefore);
                    throw e;
                }
                committed = true;
            }
        } catch (IOException | RuntimeException e) {
            if (!committed) {
                cleanupOnFailure(e, sources.createdFiles.toArray(Path[]::new));
            }
            throw e;
        }
    }

    private record PreparedSources(String peakSafeFile, double peakSafeGainDb,
                                   String playbackFile, double playbackGainDb, List<Path> createdFiles) {
    }

    private PreparedSources prepareSources(String id, Path original, LoudnessAnalyzer.Measurement measurement,
                                           Double overrideDb) throws IOException {
        List<Path> createdFiles = new ArrayList<>();
        try {
            String safeFile = null;
            double safeGainDb = 0;
            String playbackFile;
            double playbackGainDb;
            if (measurement.maximumGainDb() > 0) {
                safeFile = id + "." + java.util.UUID.randomUUID() + ".peak-safe.wav";
                Path safePath = filesFolder().resolve(safeFile);
                createdFiles.add(safePath);
                LoudnessAnalyzer.writePrepared(original, safePath, measurement.maximumGainDb(),
                        measurement.channels(), measurement.sampleRate());
                safeGainDb = measurement.maximumGainDb();
            }
            double selectedGain = overrideDb == null ? measurement.autoGainDb() : overrideDb;
            if (overrideDb != null && overrideDb > measurement.maximumGainDb()) {
                playbackFile = id + "." + java.util.UUID.randomUUID() + ".limited.wav";
                Path limitedPath = filesFolder().resolve(playbackFile);
                createdFiles.add(limitedPath);
                LoudnessAnalyzer.writeLimitedPrepared(original, limitedPath, overrideDb,
                        measurement.channels(), measurement.sampleRate());
                playbackGainDb = overrideDb;
            } else {
                playbackFile = safeFile;
                playbackGainDb = safeGainDb;
                if (selectedGain > measurement.maximumGainDb()) {
                    throw new IOException("The requested gain exceeds the analyzed peak-safe limit.");
                }
            }
            return new PreparedSources(safeFile, safeGainDb, playbackFile, playbackGainDb, createdFiles);
        } catch (IOException | RuntimeException e) {
            cleanupOnFailure(e, createdFiles.toArray(Path[]::new));
            throw e;
        }
    }

    private void addObsoletePlaybackFiles(AudioTrack current, TrackAnalysis previous) {
        Set<String> activeFiles = new HashSet<>();
        if (current.getPlaybackFile() != null) {
            activeFiles.add(current.getPlaybackFile());
        }
        if (current.getPeakSafePlaybackFile() != null) {
            activeFiles.add(current.getPeakSafePlaybackFile());
        }
        addStaleFile(previous.playbackFile, activeFiles);
        addStaleFile(previous.peakSafePlaybackFile, activeFiles);
    }

    private void addStaleFile(String name, Set<String> activeFiles) {
        if (name != null && !activeFiles.contains(name) && !library.getStalePlaybackFiles().contains(name)) {
            library.getStalePlaybackFiles().add(name);
        }
    }

    /**
     * Deletes playback copies recorded as stale. A copy that is still open (Windows locks playing files) stays
     * listed in the index and is retried later; the number of copies that remain is returned.
     */
    public synchronized int deleteStalePlaybackFiles() throws IOException {
        List<String> remaining = new ArrayList<>();
        for (String name : library.getStalePlaybackFiles()) {
            Path file = filesFolder().resolve(name).normalize();
            if (!file.startsWith(filesFolder())) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
            } catch (java.nio.file.FileSystemException e) {
                remaining.add(name);
            }
        }
        if (remaining.size() != library.getStalePlaybackFiles().size()) {
            library.setStalePlaybackFiles(remaining);
            save();
        }
        return remaining.size();
    }

    private record TrackAnalysis(String file, Double loudnessLufs, Double samplePeakDbfs,
                                 Double peakCeilingDbfs, Double peakHeadroomDb, double autoGainDb,
                                 double maxGainDb, int audioChannels, int audioSampleRate,
                                 Double gainOverrideDb, String playbackFile,
                                 double playbackGainDb, String peakSafePlaybackFile,
                                 double peakSafePlaybackGainDb) {
        static TrackAnalysis of(AudioTrack track) {
            return new TrackAnalysis(track.getFile(), track.getLoudnessLufs(), track.getSamplePeakDbfs(),
                    track.getPeakCeilingDbfs(), track.getPeakHeadroomDb(), track.getAutoGainDb(),
                    track.getMaxGainDb(), track.getAudioChannels(), track.getAudioSampleRate(),
                    track.getGainOverrideDb(), track.getPlaybackFile(),
                    track.getPlaybackGainDb(), track.getPeakSafePlaybackFile(),
                    track.getPeakSafePlaybackGainDb());
        }

        boolean matches(AudioTrack track) {
            return Objects.equals(file, track.getFile())
                    && Objects.equals(loudnessLufs, track.getLoudnessLufs())
                    && Objects.equals(samplePeakDbfs, track.getSamplePeakDbfs())
                    && Objects.equals(peakCeilingDbfs, track.getPeakCeilingDbfs())
                    && Objects.equals(peakHeadroomDb, track.getPeakHeadroomDb())
                    && Double.compare(autoGainDb, track.getAutoGainDb()) == 0
                    && Double.compare(maxGainDb, track.getMaxGainDb()) == 0
                    && audioChannels == track.getAudioChannels()
                    && audioSampleRate == track.getAudioSampleRate()
                    && Objects.equals(gainOverrideDb, track.getGainOverrideDb())
                    && Objects.equals(playbackFile, track.getPlaybackFile())
                    && Double.compare(playbackGainDb, track.getPlaybackGainDb()) == 0
                    && Objects.equals(peakSafePlaybackFile, track.getPeakSafePlaybackFile())
                    && Double.compare(peakSafePlaybackGainDb, track.getPeakSafePlaybackGainDb()) == 0;
        }

        void restore(AudioTrack track) {
            track.setLoudnessLufs(loudnessLufs);
            track.setSamplePeakDbfs(samplePeakDbfs);
            track.setPeakCeilingDbfs(peakCeilingDbfs);
            track.setPeakHeadroomDb(peakHeadroomDb);
            track.setAutoGainDb(autoGainDb);
            track.setMaxGainDb(maxGainDb);
            track.setAudioChannels(audioChannels);
            track.setAudioSampleRate(audioSampleRate);
            track.setGainOverrideDb(gainOverrideDb);
            track.setPlaybackFile(playbackFile);
            track.setPlaybackGainDb(playbackGainDb);
            track.setPeakSafePlaybackFile(peakSafePlaybackFile);
            track.setPeakSafePlaybackGainDb(peakSafePlaybackGainDb);
        }
    }

    /** Sets an absolute gain override; {@code null} restores the automatic recommendation. */
    public void setGainOverride(String id, Double gainDb) throws IOException {
        checkImportInterrupted();
        if (gainDb != null && (!Double.isFinite(gainDb) || gainDb < LoudnessAnalyzer.MIN_GAIN_DB
                || gainDb > MANUAL_MAXIMUM_GAIN_DB)) {
            throw new IOException("Gain must be between " + LoudnessAnalyzer.MIN_GAIN_DB + " dB and "
                    + MANUAL_MAXIMUM_GAIN_DB + " dB.");
        }
        TrackAnalysis snapshot;
        Path original;
        synchronized (this) {
            AudioTrack current = track(id).orElseThrow(() -> new IOException("Unknown audio track."));
            snapshot = TrackAnalysis.of(current);
            original = fileOf(current);
            if (Objects.equals(snapshot.gainOverrideDb, gainDb) && current.isLoudnessAnalyzed()) {
                return;
            }
        }
        LoudnessAnalyzer.Measurement measurement = null;
        if (gainDb != null && (snapshot.peakCeilingDbfs == null
                || (gainDb > snapshot.maxGainDb
                && (snapshot.audioChannels <= 0 || snapshot.audioSampleRate <= 0)))) {
            measurement = LoudnessAnalyzer.analyze(original);
        }
        PreparedSources sources = null;
        List<Path> createdFiles = new ArrayList<>();
        try {
            String safeFile = snapshot.peakSafePlaybackFile;
            double safeGainDb = snapshot.peakSafePlaybackGainDb;
            String playbackFile;
            double playbackGainDb;
            double maximumGainDb = snapshot.maxGainDb;
            double automaticGainDb = snapshot.autoGainDb;
            int audioChannels = snapshot.audioChannels;
            int audioSampleRate = snapshot.audioSampleRate;
            if (measurement != null) {
                sources = prepareSources(id, original, measurement, gainDb);
                createdFiles.addAll(sources.createdFiles);
                safeFile = sources.peakSafeFile;
                safeGainDb = sources.peakSafeGainDb;
                playbackFile = sources.playbackFile;
                playbackGainDb = sources.playbackGainDb;
                maximumGainDb = measurement.maximumGainDb();
                automaticGainDb = measurement.autoGainDb();
                audioChannels = measurement.channels();
                audioSampleRate = measurement.sampleRate();
            } else {
                double selectedGainDb = gainDb == null ? snapshot.autoGainDb : gainDb;
                if (gainDb != null && gainDb > snapshot.maxGainDb
                        && audioChannels > 0 && audioSampleRate > 0) {
                    playbackFile = id + "." + java.util.UUID.randomUUID() + ".limited.wav";
                    Path limitedPath = filesFolder().resolve(playbackFile);
                    createdFiles.add(limitedPath);
                    LoudnessAnalyzer.writeLimitedPrepared(original, limitedPath, gainDb,
                            snapshot.audioChannels, snapshot.audioSampleRate);
                    playbackGainDb = gainDb;
                } else {
                    playbackFile = safeFile;
                    playbackGainDb = safeGainDb;
                    if (selectedGainDb > snapshot.maxGainDb) {
                        throw new IOException("The requested gain exceeds the analyzed peak-safe limit.");
                    }
                }
            }
            checkImportInterrupted();
            synchronized (this) {
                checkImportInterrupted();
                AudioTrack current = track(id).orElseThrow(() -> new IOException("The audio track was deleted."));
                if (!snapshot.matches(current)) {
                    throw new IOException("The audio track changed while gain was being prepared; try again.");
                }
                List<String> staleBefore = new ArrayList<>(library.getStalePlaybackFiles());
                if (measurement != null) {
                    current.setLoudnessLufs(measurement.loudnessLufs());
                    current.setSamplePeakDbfs(measurement.samplePeakDbfs());
                    current.setPeakCeilingDbfs(LoudnessAnalyzer.PEAK_CEILING_DBFS);
                    current.setPeakHeadroomDb(measurement.peakHeadroomDb());
                    current.setAutoGainDb(automaticGainDb);
                    current.setMaxGainDb(maximumGainDb);
                    current.setAudioChannels(audioChannels);
                    current.setAudioSampleRate(audioSampleRate);
                    current.setPeakSafePlaybackFile(safeFile);
                    current.setPeakSafePlaybackGainDb(safeGainDb);
                }
                current.setGainOverrideDb(gainDb);
                current.setPlaybackFile(playbackFile);
                current.setPlaybackGainDb(playbackGainDb);
                addObsoletePlaybackFiles(current, snapshot);
                try {
                    save();
                } catch (IOException | RuntimeException e) {
                    snapshot.restore(current);
                    library.setStalePlaybackFiles(staleBefore);
                    throw e;
                }
            }
        } catch (IOException | RuntimeException e) {
            cleanupOnFailure(e, createdFiles.toArray(Path[]::new));
            throw e;
        }
    }

    /** A free file name inside {@code files/} for the given name, keeping the extension. */
    public synchronized Path reserveClipFile(String name, String extension) throws IOException {
        Files.createDirectories(filesFolder());
        return uniqueFile(sanitizeFileName(name) + "." + extension.toLowerCase(Locale.ROOT));
    }

    private Path reserveImportFile(Path source, String fileName) throws IOException {
        Files.createDirectories(filesFolder());
        Path target = uniqueFile(sanitizeFileName(AudioFormats.stripExtension(fileName))
                + "." + AudioFormats.extensionOf(source));
        Files.createFile(target);
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

    /** Removes a track from the index and deletes its audio file, prepared playback and cached peaks. */
    public synchronized void deleteTrack(String id) throws IOException {
        AudioTrack track = track(id).orElseThrow(() -> new IOException("Unknown track."));
        List<String> staleBefore = new ArrayList<>(library.getStalePlaybackFiles());
        List<String> preparedFiles = new ArrayList<>();
        if (track.getPlaybackFile() != null) {
            preparedFiles.add(track.getPlaybackFile());
        }
        if (track.getPeakSafePlaybackFile() != null
                && !preparedFiles.contains(track.getPeakSafePlaybackFile())) {
            preparedFiles.add(track.getPeakSafePlaybackFile());
        }
        preparedFiles.addAll(staleBefore.stream().filter(name -> name.startsWith(id + "."))
                .filter(name -> !preparedFiles.contains(name)).toList());
        for (String name : preparedFiles) {
            if (!library.getStalePlaybackFiles().contains(name)) {
                library.getStalePlaybackFiles().add(name);
            }
        }
        library.getTracks().remove(track);
        try {
            save();
        } catch (IOException | RuntimeException e) {
            library.getTracks().add(track);
            library.setStalePlaybackFiles(staleBefore);
            throw e;
        }
        Files.deleteIfExists(fileOf(track));
        List<String> staleAfterDelete = new ArrayList<>(library.getStalePlaybackFiles());
        for (String preparedFile : preparedFiles) {
            try {
                Files.deleteIfExists(filesFolder().resolve(preparedFile));
                staleAfterDelete.remove(preparedFile);
            } catch (java.nio.file.FileSystemException lockedFile) {
                // Keep locked prepared files indexed for cleanup on startup or a later explicit cleanup.
            }
        }
        Files.deleteIfExists(peaksFileOf(track));
        if (!staleAfterDelete.equals(library.getStalePlaybackFiles())) {
            library.setStalePlaybackFiles(staleAfterDelete);
            save();
        }
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

    private static void cleanupOnFailure(Throwable failure, Path... paths) {
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void checkImportInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Audio processing was interrupted.");
        }
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
