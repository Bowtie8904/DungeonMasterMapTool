package dmmt.service;

import lombok.Getter;
import dmmt.model.DmProject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The map library is a folder tree on disk. Every map is a "package" directory holding exactly one
 * {@code .dmmap} file plus its assets; every other directory is a user folder used for grouping.
 * Loose {@code .dmmap} files (older saves) are listed too and converted to packages when touched.
 */
public class MapLibraryService {
    public static final String EXTENSION = ".dmmap";

    private static final Pattern COPY_SUFFIX = Pattern.compile("^(.*) \\(Copy(?: \\d+)?\\)$");
    private static final Set<String> ASSET_FOLDERS = Set.of("assets", "imports");
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    @Getter
    private final Path root;
    private final ProjectService projectService;
    private MultiLevelService multiLevels;

    public enum Kind { FOLDER, MAP }

    /**
     * @param path    folder directory, map package directory, or loose map file
     * @param mapFile the {@code .dmmap} file for maps, {@code null} for folders
     */
    public record Entry(Kind kind, String name, Path path, Path mapFile, List<Entry> children) {
        public boolean isFolder() {
            return kind == Kind.FOLDER;
        }

        public boolean isMap() {
            return kind == Kind.MAP;
        }

        /** A multilevel map ({@code .dmlevels} package). */
        public boolean isMultiLevel() {
            return kind == Kind.MAP && MultiLevelService.isMultiLevelFile(mapFile);
        }

        /** The folder this entry lives in (for the library root: the root itself). */
        public Path containingFolder() {
            return path.getParent();
        }
    }

    /** Old map file -> new map file for every map whose location changed. */
    public record Result(Map<Path, Path> movedMaps, Path createdMap) {
        static Result moved(Map<Path, Path> moved) {
            return new Result(moved, null);
        }

        static Result none() {
            return new Result(Map.of(), null);
        }
    }

    public MapLibraryService(Path root, ProjectService projectService) {
        this.root = root.toAbsolutePath().normalize();
        this.projectService = projectService;
    }

    public synchronized MultiLevelService multiLevels() {
        if (multiLevels == null) {
            multiLevels = new MultiLevelService(this, projectService, new Dd2vttImportService());
        }
        return multiLevels;
    }

    public byte[] loadOrCreateThumbnail(Path mapFile) throws IOException {
        if (MultiLevelService.isMultiLevelFile(mapFile)) {
            return multiLevels().loadOrCreateThumbnail(mapFile);
        }
        return projectService.loadOrCreateThumbnail(mapFile);
    }

    // ---- Scanning ----

    public Entry scan() throws IOException {
        Files.createDirectories(root);
        return new Entry(Kind.FOLDER, "Library", root, null, scanChildren(root, true));
    }

    /** Folders only (for the location picker). */
    public Entry scanFolders() throws IOException {
        Files.createDirectories(root);
        return new Entry(Kind.FOLDER, "Library", root, null, scanChildren(root, false));
    }

    private List<Entry> scanChildren(Path dir, boolean includeMaps) throws IOException {
        List<Entry> folders = new ArrayList<>();
        List<Entry> maps = new ArrayList<>();
        boolean hasLooseMaps;
        try (Stream<Path> list = Files.list(dir)) {
            hasLooseMaps = list.anyMatch(MapLibraryService::isMapFile);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String fileName = child.getFileName().toString();
                if (fileName.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    // Asset folders next to loose map files belong to those maps, not to the library structure.
                    if (hasLooseMaps && ASSET_FOLDERS.contains(fileName.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    Path multiLevel = MultiLevelService.findManifest(child);
                    Path packagedMap = multiLevel != null ? multiLevel : findPackagedMap(child);
                    if (packagedMap != null) {
                        maps.add(new Entry(Kind.MAP, fileName, child, packagedMap, List.of()));
                    } else {
                        folders.add(new Entry(Kind.FOLDER, fileName, child, null, scanChildren(child, includeMaps)));
                    }
                } else if (isMapFile(child)) {
                    maps.add(new Entry(Kind.MAP, stripExtension(fileName), child, child, List.of()));
                }
            }
        }
        Comparator<Entry> byName = Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
        folders.sort(byName);
        maps.sort(byName);
        List<Entry> all = new ArrayList<>(folders);
        if (includeMaps) {
            all.addAll(maps);
        }
        return all;
    }

    /**
     * A directory is a map package if it directly contains exactly one {@code .dmmap} file and none of its
     * subdirectories contain maps (those would make it a user folder that happens to hold a loose map).
     */
    private Path findPackagedMap(Path dir) throws IOException {
        Path found = null;
        List<Path> subdirectories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (isMapFile(child)) {
                    if (found != null) {
                        return null;
                    }
                    found = child;
                } else if (Files.isDirectory(child)) {
                    subdirectories.add(child);
                }
            }
        }
        if (found == null) {
            return null;
        }
        for (Path sub : subdirectories) {
            try (Stream<Path> walk = Files.walk(sub, 4)) {
                if (walk.anyMatch(path -> isMapFile(path) || MultiLevelService.isMultiLevelFile(path))) {
                    return null;
                }
            }
        }
        return found;
    }

    public static boolean isMapFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    public static int countMaps(Entry entry) {
        if (entry.isMap()) {
            return 1;
        }
        int count = 0;
        for (Entry child : entry.children()) {
            count += countMaps(child);
        }
        return count;
    }

    // ---- Names ----

    /** Validates a user-entered name and returns the trimmed version. */
    public static String cleanName(String raw) throws IOException {
        String name = raw == null ? "" : raw.trim();
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.isEmpty()) {
            throw new IOException("Please enter a name.");
        }
        if (name.length() > 100) {
            throw new IOException("The name is too long (max. 100 characters).");
        }
        for (char c : name.toCharArray()) {
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IOException("Names cannot contain < > : \" / \\ | ? *");
            }
        }
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            throw new IOException("\"" + name + "\" is reserved by Windows.");
        }
        return name;
    }

    private static boolean nameTaken(Path folder, String name) {
        return Files.exists(folder.resolve(name)) || Files.exists(folder.resolve(name + EXTENSION));
    }

    public String uniqueCopyName(Path folder, String name) {
        Matcher matcher = COPY_SUFFIX.matcher(name);
        String base = matcher.matches() ? matcher.group(1) : name;
        String candidate = base + " (Copy)";
        int n = 2;
        while (nameTaken(folder, candidate)) {
            candidate = base + " (Copy " + n++ + ")";
        }
        return candidate;
    }

    /** Where a new map named {@code name} inside {@code folder} is saved. Fails if the name is taken. */
    public Path newMapFile(Path folder, String name) throws IOException {
        String clean = cleanName(name);
        requireInsideLibrary(folder);
        if (nameTaken(folder, clean)) {
            throw new IOException("\"" + clean + "\" already exists in this folder.");
        }
        return folder.resolve(clean).resolve(clean + EXTENSION);
    }

    /** Where a new multilevel map named {@code name} inside {@code folder} is saved. Fails if the name is taken. */
    public Path newMultiLevelFile(Path folder, String name) throws IOException {
        Path mapFile = newMapFile(folder, name);
        String clean = mapFile.getParent().getFileName().toString();
        return mapFile.getParent().resolve(clean + MultiLevelService.EXTENSION);
    }

    /** Like {@link #newMapFile}, but a taken name becomes {@code Name (2)}, {@code Name (3)}, ...; invalid characters are replaced. */
    public Path uniqueNewMapFile(Path folder, String rawName) throws IOException {
        String name = cleanName(sanitizeName(rawName));
        String candidate = name;
        for (int n = 2; ; n++) {
            try {
                return newMapFile(folder, candidate);
            } catch (IOException taken) {
                if (n > 9999 || taken.getMessage() == null || !taken.getMessage().contains("already exists")) {
                    throw taken;
                }
                candidate = name + " (" + n + ")";
            }
        }
    }

    private static String sanitizeName(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            out.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String text = out.toString().trim();
        return text.length() > 100 ? text.substring(0, 100).trim() : text;
    }

    /** {@code .dmmap} or {@code .dmlevels}, depending on the kind of map. */
    private static String extensionOf(Path mapFile) {
        return MultiLevelService.isMultiLevelFile(mapFile) ? MultiLevelService.EXTENSION : EXTENSION;
    }

    // ---- Operations ----

    public Path createFolder(Path parent, String name) throws IOException {
        String clean = cleanName(name);
        requireInsideLibrary(parent);
        Path target = parent.resolve(clean);
        if (nameTaken(parent, clean)) {
            throw new IOException("\"" + clean + "\" already exists in this folder.");
        }
        return Files.createDirectories(target);
    }

    public Result move(Entry entry, Path targetFolder) throws IOException {
        requireNotRoot(entry);
        requireInsideLibrary(targetFolder);
        Path target = targetFolder.toAbsolutePath().normalize();
        if (target.equals(entry.containingFolder().toAbsolutePath().normalize())) {
            return Result.none();
        }
        if (entry.isFolder() && target.startsWith(entry.path().toAbsolutePath().normalize())) {
            throw new IOException("A folder cannot be moved into itself.");
        }
        if (entry.isMap() && !Files.isDirectory(entry.path())) {
            return relocateLooseMap(entry, target, entry.name(), true);
        }
        if (nameTaken(target, entry.name())) {
            throw new IOException("\"" + entry.name() + "\" already exists in the target folder.");
        }
        Path destination = target.resolve(entry.path().getFileName());
        Map<Path, Path> moved = mapFilesUnder(entry, entry.path(), destination);
        Files.move(entry.path(), destination);
        return Result.moved(moved);
    }

    public Result rename(Entry entry, String newName) throws IOException {
        requireNotRoot(entry);
        String clean = cleanName(newName);
        // String comparison on purpose: case-only renames must still happen.
        if (clean.equals(entry.name())) {
            return Result.none();
        }
        Path folder = entry.containingFolder();
        boolean caseOnly = clean.equalsIgnoreCase(entry.name());
        if (!caseOnly && nameTaken(folder, clean)) {
            throw new IOException("\"" + clean + "\" already exists in this folder.");
        }
        if (entry.isMap() && !Files.isDirectory(entry.path())) {
            return relocateLooseMap(entry, folder, clean, true);
        }
        Path destination = folder.resolve(clean);
        Map<Path, Path> moved = mapFilesUnder(entry, entry.path(), destination);
        moveAllowingCaseChange(entry.path(), destination);
        if (entry.isMap()) {
            Path oldFileInNewDir = destination.resolve(entry.mapFile().getFileName());
            Path renamedFile = destination.resolve(clean + extensionOf(entry.mapFile()));
            moveAllowingCaseChange(oldFileInNewDir, renamedFile);
            Map<Path, Path> renamed = new LinkedHashMap<>(moved);
            renamed.put(entry.mapFile(), renamedFile);
            moved = renamed;
        }
        return Result.moved(moved);
    }

    public Result copy(Entry entry) throws IOException {
        if (!entry.isMap()) {
            throw new IOException("Only maps can be copied.");
        }
        Path folder = entry.containingFolder();
        String copyName = uniqueCopyName(folder, entry.name());
        if (!Files.isDirectory(entry.path())) {
            Result result = relocateLooseMap(entry, folder, copyName, false);
            return new Result(Map.of(), result.movedMaps().get(entry.mapFile()));
        }
        Path destination = folder.resolve(copyName);
        copyRecursive(entry.path(), destination);
        Path copiedFile = destination.resolve(entry.mapFile().getFileName());
        Path renamedFile = destination.resolve(copyName + extensionOf(entry.mapFile()));
        Files.move(copiedFile, renamedFile);
        return new Result(Map.of(), renamedFile);
    }

    public void delete(Entry entry) throws IOException {
        requireNotRoot(entry);
        if (Files.isDirectory(entry.path())) {
            deleteRecursive(entry.path());
        } else {
            Files.deleteIfExists(entry.path());
        }
    }

    // ---- Helpers ----

    /**
     * Loose map files share their folder with other files, so they are re-saved as a proper package:
     * relative asset paths are made absolute and {@link ProjectService#save} copies them into the package.
     */
    private Result relocateLooseMap(Entry entry, Path targetFolder, String name, boolean deleteOriginal) throws IOException {
        String clean = cleanName(name);
        requireInsideLibrary(targetFolder);
        Path packageDir = targetFolder.resolve(clean);
        Path looseTwin = targetFolder.resolve(clean + EXTENSION);
        if (Files.exists(packageDir) || (Files.exists(looseTwin) && !Files.isSameFile(looseTwin, entry.mapFile()))) {
            throw new IOException("\"" + clean + "\" already exists in this folder.");
        }
        Path newFile = packageDir.resolve(clean + EXTENSION);
        DmProject project = projectService.load(entry.mapFile());
        Path base = entry.mapFile().getParent();
        if (project.getMap() != null) {
            project.getMap().setImagePath(absolutize(base, project.getMap().getImagePath()));
        }
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            layer.setPath(absolutize(base, layer.getPath()));
        }
        projectService.save(newFile, project);
        if (deleteOriginal) {
            Files.delete(entry.mapFile());
        }
        return Result.moved(Map.of(entry.mapFile(), newFile));
    }

    private static String absolutize(Path base, String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        Path p = Path.of(path);
        if (p.isAbsolute()) {
            return path;
        }
        Path resolved = base.resolve(p).normalize();
        return Files.exists(resolved) ? resolved.toString() : path;
    }

    private Map<Path, Path> mapFilesUnder(Entry entry, Path from, Path to) throws IOException {
        Map<Path, Path> moved = new LinkedHashMap<>();
        if (entry.isMap() && !entry.isMultiLevel()) {
            moved.put(entry.mapFile(), to.resolve(from.relativize(entry.mapFile())));
            return moved;
        }
        // Folders and multilevel maps: every map file below (incl. the levels) moves along.
        try (Stream<Path> walk = Files.walk(from)) {
            walk.filter(path -> isMapFile(path) || MultiLevelService.isMultiLevelFile(path) && Files.isRegularFile(path))
                    .forEach(file -> moved.put(file, to.resolve(from.relativize(file))));
        }
        return moved;
    }

    /** Windows paths are case-insensitive, so a case-only rename goes through a temporary name. */
    private static void moveAllowingCaseChange(Path source, Path target) throws IOException {
        if (source.toString().equals(target.toString())) {
            return;
        }
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            Path temp = source.resolveSibling(source.getFileName() + ".renaming-" + System.nanoTime());
            Files.move(source, temp);
            Files.move(temp, target);
            return;
        }
        Files.move(source, target);
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void requireInsideLibrary(Path folder) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !Files.isDirectory(normalized)) {
            throw new IOException("The target folder is not part of the map library.");
        }
    }

    private void requireNotRoot(Entry entry) throws IOException {
        if (entry.path().toAbsolutePath().normalize().equals(root)) {
            throw new IOException("The library root cannot be changed.");
        }
    }

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
