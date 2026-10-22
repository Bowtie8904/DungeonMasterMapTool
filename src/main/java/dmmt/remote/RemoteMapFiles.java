package dmmt.remote;

import dmmt.log.Log;
import dmmt.service.BatchImportService;
import dmmt.service.Dd2vttImportService;
import dmmt.service.MapExportService;
import dmmt.service.MapLibraryService;
import dmmt.service.MultiLevelService;
import dmmt.service.ProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Worker-only import/export of explicitly uploaded objects, never peer-selected host source paths. */
public final class RemoteMapFiles {
    public record Input(String name, RemoteAssetStore.AssetDescriptor asset, String relative) {
        public Input {
            Objects.requireNonNull(asset);
            if (name == null || name.isBlank() || name.length() > 200
                    || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Choose a valid map source filename.");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".dd2vtt") && !lower.endsWith(".uvtt") && !lower.endsWith(MapExportService.EXTENSION)) {
                throw new IllegalArgumentException("Upload a Universal VTT file or a DMMT map pack.");
            }
            if (relative != null) {
                relative = relative.replace('\\', '/');
                String[] parts = relative.split("/", -1);
                if (relative.length() > 1000 || parts.length > 16) throw new IllegalArgumentException("Map source folders are too deeply nested.");
                for (String part : parts) {
                    if (part.isBlank() || part.equals(".") || part.equals("..")
                            || part.matches(".*[\\\\:*?\"<>|\\p{Cntrl}].*")) {
                        throw new IllegalArgumentException("Map source folder is invalid.");
                    }
                }
            }
        }
    }

    public record ImportLevel(int source, String name) {
        public ImportLevel {
            if (source < -1 || name == null || name.isBlank() || name.length() > 200) {
                throw new IllegalArgumentException("Invalid imported level.");
            }
        }
    }

    public record Import(List<Input> sources, boolean multiLevel, String name, List<String> levelNames,
                         List<ImportLevel> plan) {
        public Import(List<Input> sources, boolean multiLevel, String name, List<String> levelNames) {
            this(sources, multiLevel, name, levelNames, List.of());
        }
        public Import {
            sources = List.copyOf(sources);
            levelNames = levelNames == null ? List.of() : List.copyOf(levelNames);
            plan = plan == null ? List.of() : List.copyOf(plan);
            if ((sources.isEmpty() && plan.isEmpty()) || sources.size() > 512 || plan.size() > 512) {
                throw new IllegalArgumentException("Import between 1 and 512 map files or levels at a time.");
            }
            if (multiLevel && plan.isEmpty() && (sources.size() != levelNames.size() || levelNames.stream()
                    .anyMatch(value -> value == null || value.isBlank() || value.length() > 200))) {
                throw new IllegalArgumentException("Give each imported level a bounded name.");
            }
            int count = sources.size();
            if (plan.stream().anyMatch(level -> level.source() >= count)) throw new IllegalArgumentException("Invalid uploaded level reference.");
            if (!multiLevel && !plan.isEmpty()) throw new IllegalArgumentException("A level plan requires multilevel import.");
            if (name != null && (name.isBlank() || name.length() > 100)) throw new IllegalArgumentException("Give the imported map a valid name.");
            if (multiLevel && (name == null || name.isBlank() || name.length() > 100)) throw new IllegalArgumentException("Give the multilevel map a valid name.");
        }
    }

    private static final Log LOG = Log.of(RemoteMapFiles.class);
    private final RemoteAssetStore store;
    private final Path cache;
    private final MapLibraryService library;
    private final ProjectService projects;

    public RemoteMapFiles(RemoteAssetStore store, Path cache, MapLibraryService library, ProjectService projects) {
        this.store = Objects.requireNonNull(store);
        this.cache = Objects.requireNonNull(cache);
        this.library = Objects.requireNonNull(library);
        this.projects = Objects.requireNonNull(projects);
    }

    public BatchImportService.Result importFiles(Import request, Path destination) throws IOException {
        library.validateDirectory(destination);
        Files.createDirectories(cache);
        Path staging = Files.createTempDirectory(cache, "map-import-");
        try {
            List<Path> sources = new ArrayList<>();
            Set<Path> used = new HashSet<>();
            boolean preserveFolders = request.sources().stream().anyMatch(source -> source.relative() != null);
            for (var input : request.sources()) {
                var actual = store.descriptor(input.asset().sha256());
                if (!actual.equals(input.asset())) throw new IOException("Uploaded map source does not match its declared size.");
                Path parent = input.relative() == null ? staging : staging.resolve(input.relative()).normalize();
                Path target = parent.resolve(input.name()).normalize();
                if (!target.startsWith(staging) || !used.add(target)) throw new IOException("Two imported sources have the same destination name.");
                Files.createDirectories(parent);
                try (var output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    store.transferTo(actual.sha256(), output);
                }
                LOG.info("Received uploaded map source '{}' ({} bytes)", RemoteSessionServer.safe(input.name()), actual.size());
                if (MapExportService.isPackFile(target)) validatePack(target);
                sources.add(target);
            }
            if (request.multiLevel()) {
                if (sources.stream().anyMatch(MapExportService::isPackFile)) throw new IOException("Multilevel creation requires Universal VTT files.");
                List<MultiLevelService.PlanItem> items = new ArrayList<>();
                if (request.plan().isEmpty()) {
                    for (int i = 0; i < sources.size(); i++) {
                        items.add(new MultiLevelService.PlanItem(new MultiLevelService.Dd2vtt(sources.get(i)), request.levelNames().get(i)));
                    }
                } else for (var level : request.plan()) {
                    items.add(new MultiLevelService.PlanItem(level.source() < 0
                            ? new MultiLevelService.Empty() : new MultiLevelService.Dd2vtt(sources.get(level.source())), level.name()));
                }
                var result = library.multiLevels().create(destination, request.name(), items, null);
                LOG.info("Imported multilevel map '{}' from {} uploaded file(s)", RemoteSessionServer.safe(request.name()), sources.size());
                return new BatchImportService.Result(List.of(result.manifestFile()), List.of(), sources.size());
            }
            if (request.name() != null && sources.size() == 1 && !MapExportService.isPackFile(sources.getFirst())) {
                Path map = library.newMapFile(destination, request.name());
                Files.createDirectories(map.getParent());
                try {
                    var imported = new Dd2vttImportService().importToProject(sources.getFirst(), map.getParent());
                    new dmmt.service.MapTagService(library, projects).applyKnownTags(imported, sources.getFirst());
                    projects.save(map, imported);
                    LOG.info("Imported map '{}' from an uploaded file", RemoteSessionServer.safe(request.name()));
                    return new BatchImportService.Result(List.of(map), List.of(), 1);
                } catch (IOException | RuntimeException failure) {
                    try {
                        MapLibraryService.deleteRecursive(map.getParent());
                    } catch (IOException cleanup) {
                        LOG.warn("Could not clean up failed map import at {}", map.getParent(), cleanup);
                        failure.addSuppressed(cleanup);
                    }
                    LOG.warn("Remote map import '{}' failed", RemoteSessionServer.safe(request.name()), failure);
                    throw failure;
                }
            }
            return new BatchImportService(library, projects, new Dd2vttImportService())
                    .importAll(sources, destination, preserveFolders ? staging : null, null);
        } finally {
            MapLibraryService.deleteRecursive(staging);
        }
    }

    public RemoteAssetStore.AssetDescriptor export(List<MapLibraryService.Entry> entries) throws IOException {
        if (entries.isEmpty() || entries.size() > 512) throw new IOException("Export between 1 and 512 maps at a time.");
        Files.createDirectories(cache);
        Path temporary = Files.createTempFile(cache, "map-export-", MapExportService.EXTENSION);
        try {
            new MapExportService(library).export(entries, temporary);
            var asset = store.publishRequiredAsset(temporary);
            LOG.info("Exported {} map(s) as a pack for remote download ({} bytes)", entries.size(), asset.size());
            return asset;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validatePack(Path source) throws IOException {
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[65536];
        try (var zip = new ZipInputStream(Files.newInputStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 65536) throw new IOException("Map pack contains too many files.");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains(":") || List.of(name.split("/")).contains("..")) {
                    throw new IOException("Map pack contains an invalid path.");
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 8L * 1024 * 1024 * 1024) throw new IOException("Expanded map pack exceeds the import limit.");
                }
            }
        }
    }
}
