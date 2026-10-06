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

    /**
     * Imports the files into {@code targetFolder}, flat (no sub-folders created). Files that look like the levels of
     * one building become one multilevel map each (see {@link MultiLevelService#groupLevelFiles}); the rest become
     * ordinary maps. {@code imported} lists the created map / multilevel map files.
     */
    public Result importAll(List<Path> sources, Path targetFolder, Progress progress) {
        return importAll(sources, targetFolder, null, progress);
    }

    /**
     * Imports the files into {@code targetFolder}. When {@code sourceRoot} is given (folder import), each file's
     * sub-folder path relative to {@code sourceRoot} is re-created under {@code targetFolder} (sub-folders with no
     * suitable maps are never created); otherwise every file is imported directly into {@code targetFolder}.
     * Files that look like the levels of one building become one multilevel map each
     * (see {@link MultiLevelService#groupLevelFiles}); the rest become ordinary maps. {@code imported} lists the
     * created map / multilevel map files.
     */
    public Result importAll(List<Path> sources, Path targetFolder, Path sourceRoot, Progress progress) {
        List<Path> imported = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int total = sources.size();
        int[] index = {0};
        boolean autoMerge = Tuning.IMPORT_AUTO_MERGE_MULTILEVEL.get();
        List<MultiLevelService.ImportGroup> groups = new ArrayList<>();
        if (autoMerge) {
            groups.addAll(MultiLevelService.groupLevelFiles(sources));
        } else {
            for (Path source : sources) {
                String name = MapLibraryService.stripExtension(source.getFileName().toString());
                groups.add(new MultiLevelService.ImportGroup(name, List.of(source), List.of(name)));
            }
        }
        for (MultiLevelService.ImportGroup group : groups) {
            Path groupFolder;
            try {
                groupFolder = resolveTargetFolder(targetFolder, sourceRoot, group.files().get(0));
            } catch (IOException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                for (Path file : group.files()) {
                    failures.add(new Failure(file, reason));
                }
                index[0] += group.files().size();
                continue;
            }
            if (group.multiLevel()) {
                int first = index[0];
                try {
                    imported.add(importMultiLevel(group, groupFolder, (levelIndex, levelTotal, levelName) -> {
                        index[0] = first + levelIndex;
                        if (progress != null) {
                            progress.report(index[0], total, group.name() + " · " + levelName);
                        }
                    }));
                } catch (IOException | RuntimeException ex) {
                    String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    for (Path file : group.files()) {
                        failures.add(new Failure(file, reason));
                    }
                }
                index[0] = first + group.files().size();
                continue;
            }
            Path source = group.files().get(0);
            String baseName = MapLibraryService.stripExtension(source.getFileName().toString());
            if (progress != null) {
                progress.report(++index[0], total, baseName);
            }
            try {
                imported.add(importOne(source, groupFolder, baseName));
            } catch (IOException | RuntimeException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                failures.add(new Failure(source, reason));
            }
        }
        return new Result(imported, failures, total);
    }

    /**
     * {@code targetFolder} itself when {@code sourceRoot} is {@code null}, otherwise {@code targetFolder} plus the
     * sub-folder path of {@code source} relative to {@code sourceRoot} (created on demand, so a sub-folder only
     * appears once a suitable map is actually imported from it).
     */
    private static Path resolveTargetFolder(Path targetFolder, Path sourceRoot, Path source) throws IOException {
        if (sourceRoot == null) {
            return targetFolder;
        }
        Path sourceDir = source.toAbsolutePath().normalize().getParent();
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path relative = root.relativize(sourceDir);
        Path resolved = relative.toString().isEmpty() ? targetFolder : targetFolder.resolve(relative.toString());
        Files.createDirectories(resolved);
        return resolved;
    }

    private Path importMultiLevel(MultiLevelService.ImportGroup group, Path targetFolder,
                                  MultiLevelService.Progress progress) throws IOException {
        List<String> levelNames = group.levelNames();
        List<MultiLevelService.PlanItem> plan = new ArrayList<>();
        for (int i = 0; i < group.files().size(); i++) {
            plan.add(new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(group.files().get(i)), levelNames.get(i)));
        }
        String name = uniqueMapFile(targetFolder, group.name()).getParent().getFileName().toString();
        return library.multiLevels().create(targetFolder, name, plan, progress).manifestFile();
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
        return library.uniqueNewMapFile(folder, rawName);
    }
}
