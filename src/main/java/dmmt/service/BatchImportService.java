package dmmt.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Imports several dd2vtt maps into a library folder, one after another. */
public class BatchImportService {

    public record Failure(Path source, String reason) {
    }

    public record Result(List<Path> imported, List<Failure> failures, int total) {
    }

    public interface Progress {
        void report(int index, int total, String name);
    }

    private final MapLibraryService library;
    private final ProjectService projectService;
    private final Dd2vttImportService importService;

    public BatchImportService(MapLibraryService library, ProjectService projectService,
                              Dd2vttImportService importService) {
        this.library = library;
        this.projectService = projectService;
        this.importService = importService;
    }

    public static boolean isSuitable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(file) && (name.endsWith(".dd2vtt") || name.endsWith(".uvtt"));
    }

    /** All importable maps in {@code folder} and its sub-folders, sorted by path. */
    public static List<Path> findMaps(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(BatchImportService::isSuitable).sorted().toList();
        }
    }

    public Result importAll(List<Path> sources, Path targetFolder, Progress progress) {
        List<Path> imported = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int total = sources.size();
        for (int i = 0; i < total; i++) {
            Path source = sources.get(i);
            String baseName = MapLibraryService.stripExtension(source.getFileName().toString());
            if (progress != null) {
                progress.report(i + 1, total, baseName);
            }
            try {
                imported.add(importOne(source, targetFolder, baseName));
            } catch (IOException | RuntimeException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                failures.add(new Failure(source, reason));
            }
        }
        return new Result(imported, failures, total);
    }

    private Path importOne(Path source, Path targetFolder, String baseName) throws IOException {
        Path mapFile = uniqueMapFile(targetFolder, baseName);
        Path projectDir = mapFile.getParent();
        Files.createDirectories(projectDir);
        try {
            projectService.save(mapFile, importService.importToProject(source, projectDir));
            return mapFile;
        } catch (IOException | RuntimeException ex) {
            try {
                MapLibraryService.deleteRecursive(projectDir);
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    /** {@code Name}, or {@code Name (2)}, {@code Name (3)}, ... when the name is already taken. */
    Path uniqueMapFile(Path folder, String rawName) throws IOException {
        String name = MapLibraryService.cleanName(sanitize(rawName));
        String candidate = name;
        for (int n = 2; ; n++) {
            try {
                return library.newMapFile(folder, candidate);
            } catch (IOException taken) {
                if (n > 9999 || !taken.getMessage().contains("already exists")) {
                    throw taken;
                }
                candidate = name + " (" + n + ")";
            }
        }
    }

    private static String sanitize(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            out.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String text = out.toString().trim();
        return text.length() > 100 ? text.substring(0, 100).trim() : text;
    }
}
