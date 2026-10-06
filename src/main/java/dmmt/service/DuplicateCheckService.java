package dmmt.service;

import dmmt.model.MultiLevelManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects import files that are already present in the library, based on the <b>original file name</b> stored in
 * each map (ordinary maps: {@code DmProject.MapInfo.originalFileName}) or level (multilevel maps:
 * {@code MultiLevelManifest.Level.originalName}) - never the current library map name, which the user may have
 * renamed. Maps/levels without a stored original file name (saved before this field existed) are skipped: they are
 * never matched as duplicates and never match anything.
 */
public class DuplicateCheckService {
    private final MapLibraryService library;
    private final ProjectService projectService;

    public DuplicateCheckService(MapLibraryService library, ProjectService projectService) {
        this.library = library;
        this.projectService = projectService;
    }

    /** The subset of {@code sources} whose original file name already exists somewhere in the library. */
    public List<Path> findDuplicates(List<Path> sources) throws IOException {
        if (sources.isEmpty()) {
            return List.of();
        }
        Set<String> existing = existingOriginalNames();
        if (existing.isEmpty()) {
            return List.of();
        }
        List<Path> duplicates = new ArrayList<>();
        for (Path source : sources) {
            String normalized = normalize(source.getFileName().toString());
            if (normalized != null && existing.contains(normalized)) {
                duplicates.add(source);
            }
        }
        return duplicates;
    }

    /** Normalized (extension stripped, trimmed, lower-cased) original file names already in the library. */
    private Set<String> existingOriginalNames() throws IOException {
        Set<String> names = new HashSet<>();
        collect(library.scan(), names);
        return names;
    }

    private void collect(MapLibraryService.Entry entry, Set<String> names) {
        if (entry.isFolder()) {
            for (MapLibraryService.Entry child : entry.children()) {
                collect(child, names);
            }
            return;
        }
        if (entry.isMultiLevel()) {
            MultiLevelManifest manifest;
            try {
                manifest = library.multiLevels().loadManifest(entry.mapFile());
            } catch (IOException | RuntimeException ex) {
                return;
            }
            for (MultiLevelManifest.Level level : manifest.getLevels()) {
                add(names, level.getOriginalName());
            }
            return;
        }
        projectService.readOriginalFileName(entry.mapFile()).ifPresent(name -> add(names, name));
    }

    private void add(Set<String> names, String rawName) {
        String normalized = normalize(rawName);
        if (normalized != null) {
            names.add(normalized);
        }
    }

    /** {@code null} for a blank/absent name; extension stripped, trimmed and lower-cased otherwise. */
    private static String normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String stripped = MapLibraryService.stripExtension(rawName.trim());
        return stripped.isBlank() ? null : stripped.toLowerCase(Locale.ROOT);
    }
}
