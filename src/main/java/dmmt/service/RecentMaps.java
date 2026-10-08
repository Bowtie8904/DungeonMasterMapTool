package dmmt.service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * History of recently opened maps, most recent first, persisted in the settings file.
 */
public final class RecentMaps {
    public static final String KEY = "ui.recentMaps";
    public static final String MAX_KEY = "ui.recentMaps.max";
    public static final int DEFAULT_MAX = 5;
    private static final int MAX_LIMIT = 30;
    private static final String SEPARATOR = "|";

    private final AppSettings settings;
    private final List<Path> entries = new ArrayList<>();
    private Path previousMap;

    public RecentMaps(AppSettings settings) {
        this.settings = settings;
        load();
    }

    /** Moves (or adds) the map to the top of the history and persists it. */
    public void record(Path mapFile) {
        if (mapFile == null) {
            return;
        }
        Path normalized = normalize(mapFile);
        if (!entries.isEmpty() && !entries.getFirst().equals(normalized)) {
            previousMap = entries.getFirst();
        }
        entries.remove(normalized);
        entries.add(0, normalized);
        trim();
        save();
    }

    /** Returns the history without maps that no longer exist on disk; stale entries are dropped for good. */
    public List<Path> existing() {
        boolean removed = entries.removeIf(path -> !Files.isRegularFile(path));
        if (removed) {
            save();
        }
        return List.copyOf(entries);
    }

    /** The last other map; keeps a session fallback when the configured history holds only one map. */
    public Optional<Path> previous(Path currentMap) {
        Path current = currentMap == null ? null : normalize(currentMap);
        Optional<Path> recent = existing().stream().filter(path -> !path.equals(current)).findFirst();
        if (recent.isPresent()) {
            return recent;
        }
        return previousMap != null && !previousMap.equals(current) && Files.isRegularFile(previousMap)
                ? Optional.of(previousMap) : Optional.empty();
    }

    public int max() {
        return Math.max(1, Math.min(MAX_LIMIT, settings.getInt(MAX_KEY, DEFAULT_MAX)));
    }

    private void load() {
        String stored = settings.get(KEY, "");
        for (String part : stored.split("\\" + SEPARATOR)) {
            if (part.isBlank()) {
                continue;
            }
            try {
                Path path = normalize(Path.of(part.trim()));
                if (!entries.contains(path)) {
                    entries.add(path);
                }
            } catch (InvalidPathException ignored) {
                // a hand-edited entry that is not a path is skipped
            }
        }
        trim();
    }

    private void trim() {
        while (entries.size() > max()) {
            entries.remove(entries.size() - 1);
        }
    }

    private void save() {
        StringBuilder value = new StringBuilder();
        for (Path entry : entries) {
            if (value.length() > 0) {
                value.append(SEPARATOR);
            }
            value.append(entry);
        }
        settings.put(KEY, value.toString());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
