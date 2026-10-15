package dmmt.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports library maps into one {@code .dmmpack} archive (a zip with one top-level folder per map, holding the
 * map package exactly as it lives in the library) and imports such archives into a library folder.
 */
public class MapExportService {
    public static final String EXTENSION = ".dmmpack";

    private final MapLibraryService library;

    public MapExportService(MapLibraryService library) {
        this.library = library;
    }

    public static boolean isPackFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** Writes {@code maps} to {@code target}; returns the number of exported maps. */
    public int export(List<MapLibraryService.Entry> maps, Path target) throws IOException {
        if (maps.isEmpty()) {
            throw new IOException("There is nothing to export.");
        }
        Path temp = Files.createTempDirectory("dmmt-export");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            Set<String> usedNames = new HashSet<>();
            for (MapLibraryService.Entry map : maps) {
                String top = uniqueName(usedNames, map.name());
                Path source;
                if (Files.isDirectory(map.path())) {
                    source = map.path();
                } else {
                    source = temp.resolve(top);
                    Files.createDirectories(source);
                    library.packageLooseMap(map, source);
                }
                addDirectory(zip, source, top);
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(target);
            throw ex;
        } finally {
            MapLibraryService.deleteRecursive(temp);
        }
        return maps.size();
    }

    private static String uniqueName(Set<String> used, String name) {
        String candidate = name;
        for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = name + " (" + n + ")";
        }
        return candidate;
    }

    private static void addDirectory(ZipOutputStream zip, Path source, String top) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            String relative = source.relativize(file).toString().replace('\\', '/');
            zip.putNextEntry(new ZipEntry(top + "/" + relative));
            Files.copy(file, zip);
            zip.closeEntry();
        }
    }

    /** Extracts every map of {@code pack} into {@code folder}; returns the new map files. */
    public List<Path> importPack(Path pack, Path folder) throws IOException {
        List<Path> created = new ArrayList<>();
        Set<String> tops = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (entry.isDirectory() || slash <= 0 || name.contains("..")) {
                    continue;
                }
                tops.add(name.substring(0, slash));
            }
        }
        if (tops.isEmpty()) {
            throw new IOException("\"" + pack.getFileName() + "\" does not contain any maps.");
        }
        java.util.Map<String, Path> destinations = new java.util.LinkedHashMap<>();
        try {
            for (String top : tops.stream().sorted().toList()) {
                Path dir = library.uniqueNewMapFile(folder, top).getParent();
                Files.createDirectories(dir);
                destinations.put(top, dir);
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    int slash = name.indexOf('/');
                    if (entry.isDirectory() || slash <= 0 || name.contains("..")) {
                        continue;
                    }
                    Path destination = destinations.get(name.substring(0, slash));
                    Path file = destination.resolve(name.substring(slash + 1)).normalize();
                    if (!file.startsWith(destination)) {
                        throw new IOException("The archive contains an invalid path: " + name);
                    }
                    Files.createDirectories(file.getParent());
                    try (OutputStream out = Files.newOutputStream(file)) {
                        copy(zip, out);
                    }
                }
            }
            for (var destination : destinations.entrySet()) {
                created.add(finishMap(destination.getKey(), destination.getValue()));
            }
        } catch (IOException | RuntimeException ex) {
            for (Path dir : destinations.values()) {
                try {
                    if (Files.exists(dir)) {
                        MapLibraryService.deleteRecursive(dir);
                    }
                } catch (IOException cleanup) {
                    ex.addSuppressed(cleanup);
                }
            }
            throw ex;
        }
        return created;
    }

    /** Renames the map file to match the (possibly renamed) package folder and gives the map fresh ids. */
    private Path finishMap(String originalName, Path dir) throws IOException {
        Path mapFile = null;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String lower = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(child)
                        && (lower.endsWith(MapLibraryService.EXTENSION) || lower.endsWith(MultiLevelService.EXTENSION))) {
                    mapFile = child;
                    break;
                }
            }
        }
        if (mapFile == null) {
            throw new IOException("\"" + originalName + "\" in the archive is not a valid map.");
        }
        String extension = MultiLevelService.isMultiLevelFile(mapFile) ? MultiLevelService.EXTENSION
                : MapLibraryService.EXTENSION;
        Path renamed = dir.resolve(dir.getFileName() + extension);
        if (!renamed.equals(mapFile)) {
            Files.move(mapFile, renamed);
        }
        library.regenerateCopyIds(renamed);
        return renamed;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
