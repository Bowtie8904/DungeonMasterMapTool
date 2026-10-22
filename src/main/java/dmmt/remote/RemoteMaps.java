package dmmt.remote;

import dmmt.log.Log;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.service.JsonMappers;
import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Host map-library projection. Only session-scoped handles, never host paths, cross the wire.
 */
public final class RemoteMaps implements RemoteOperations.Endpoint, AutoCloseable
{
    private static final Log LOG = Log.of(RemoteMaps.class);
    public record Level(String id, String name)
    {
    }

    public record Node(UUID id, UUID parent, String name, RemoteMapCatalog.Kind kind,
                       List<String> tags, int levels, long stamp)
    {
        public Node
        {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            if (name == null || name.isBlank() || name.length() > 256 || levels < 0
                    || name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid map metadata.");
            tags = List.copyOf(tags);
            if (tags.size() > 128 || tags.stream().anyMatch(t -> t == null || t.length() > 256))
                throw new IllegalArgumentException("Map tags exceed the metadata limit.");
        }
    }

    public record Listing(UUID context, long revision, List<Node> nodes, UUID current,
                          String currentLabel, List<UUID> recent)
    {
        public Listing
        {
            Objects.requireNonNull(context);
            nodes = List.copyOf(nodes);
            recent = List.copyOf(recent);
            if (revision < 1 || nodes.isEmpty() || nodes.size() > 1024 || recent.size() > 100
                    || currentLabel == null || currentLabel.length() > 256)
                throw new IllegalArgumentException("Invalid map library snapshot.");
            Set<UUID> folders = new HashSet<>(), identities = new HashSet<>();
            Map<UUID, Integer> depths = new HashMap<>();
            long text = 0;
            for (Node node : nodes)
            {
                if (!identities.add(node.id()) || (identities.size() == 1
                                                   ? node.parent() != null || node.kind() != RemoteMapCatalog.Kind.FOLDER
                                                   : !folders.contains(node.parent())))
                    throw new IllegalArgumentException("Invalid map hierarchy.");
                int depth = node.parent() == null ? 0 : depths.get(node.parent()) + 1;
                if (depth > 25)
                    throw new IllegalArgumentException("Map hierarchy is too deep.");
                depths.put(node.id(), depth);
                if (node.kind() == RemoteMapCatalog.Kind.FOLDER)
                    folders.add(node.id());
                text += node.name().length() + node.tags().stream().mapToLong(String::length).sum();
            }
            if (text > 65536 || current != null && (!identities.contains(current) || folders.contains(current))
                    || !identities.containsAll(recent) || recent.stream().anyMatch(folders::contains))
                throw new IllegalArgumentException("Invalid map metadata references.");
        }
    }

    public record Command(UUID context, long revision, List<UUID> entries, UUID destination,
                          String name, JsonNode options)
    {
        public Command
        {
            Objects.requireNonNull(context);
            entries = List.copyOf(entries);
            if (revision < 1 || entries.size() > 1024 || new HashSet<>(entries).size() != entries.size()
                    || name != null && (name.length() > 256 || name.codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid map operation.");
            options = options == null || options.isNull() ? null : options.deepCopy();
        }
        @Override public JsonNode options() { return options == null ? null : options.deepCopy(); }
    }

    /**
     * Resolved host-only request. Execute through the application's existing save/lifecycle guards.
     */
    public record Request(String action, UUID context, long revision, List<Entry> entries,
                          Entry destination, String name, JsonNode options)
    {
    }

    @FunctionalInterface
    public interface Host
    {
        CompletableFuture<JsonNode> execute(Request request);
    }

    private record Scan(List<Node> nodes, Map<UUID, Entry> entries, Map<Path, UUID> paths)
    {
    }

    private final Thread thread = Thread.currentThread();
    private final UUID salt = UUID.randomUUID();
    private final Supplier<RemoteMapCatalog.Source> source;
    private final Supplier<List<Path>> recents;
    private final RemoteAssetStore assets;
    private final Executor worker, owner;
    private final Host host;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = JsonMappers.create();
    private Map<UUID, Entry> entries = Map.of();
    private List<Node> previous = List.of();
    private MapLibraryService previousLibrary;
    private long revision;
    private long invalidation;
    private Scan cached;
    private CompletableFuture<Listing> scanning;
    private final Map<UUID, JsonNode> levelMetadata = new HashMap<>();
    private UUID levelMetadataContext;
    private record ImportPreflight(UUID id, UUID context, long revision, String behavior,
                                   List<String> names, Set<String> duplicates) { }
    private final LinkedHashMap<UUID, ImportPreflight> importPreflights = new LinkedHashMap<>();
    private volatile boolean closed;

    public RemoteOperations.Endpoint endpoint() { return this; }

    /** Invalidates confirmations even when a host-local change is subsequently reversed. */
    public void invalidate() {
        requireOwner();
        revision++;
        invalidation++;
        cached = null;
        levelMetadata.clear();
        previous = List.of();
    }

    public RemoteMaps(Supplier<RemoteMapCatalog.Source> source, Supplier<List<Path>> recents,
                      RemoteAssetStore assets, Executor worker, Executor owner, Host host)
    {
        this.source = Objects.requireNonNull(source);
        this.recents = Objects.requireNonNull(recents);
        this.assets = Objects.requireNonNull(assets);
        this.worker = Objects.requireNonNull(worker);
        this.owner = Objects.requireNonNull(owner);
        this.host = Objects.requireNonNull(host);
    }

    @Override
    public CompletableFuture<JsonNode> query(String action, JsonNode payload)
    {
        requireOwner();
        if (closed)
            return failed("Host map library is closed.");
        if ("list".equals(action))
            return listing(payload != null && payload.path("refresh").asBoolean(false)).thenApply(mapper::valueToTree);
        if ("importPreflight".equals(action)) {
            try {
                Command command = mapper.treeToValue(payload, Command.class);
                return validate(command, true).thenCompose(list -> {
                    List<String> names = new ArrayList<>();
                    for (JsonNode item : command.options().path("names")) {
                        String name = item.asText();
                        if (name.isBlank() || name.length() > 200 || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"))
                            return failed("Choose valid map source filenames.");
                        names.add(name);
                    }
                    if (names.isEmpty() || names.size() > 512) return failed("Choose between 1 and 512 map sources.");
                    var captured = source.get();
                    long generation = invalidation;
                    String behavior = dmmt.service.Tuning.IMPORT_DUPLICATE_BEHAVIOR.get();
                    Map<UUID, Entry> snapshot = entries;
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            Entry root = snapshot.values().stream().filter(e -> e.path().equals(captured.library().getRoot())).findFirst().orElseThrow();
                            var projection = new MapLibraryService(captured.library().getRoot(), new dmmt.service.ProjectService()) {
                                @Override public Entry scan() { return root; }
                                @Override public dmmt.service.MultiLevelService multiLevels() { return captured.library().multiLevels(); }
                            };
                            return new dmmt.service.DuplicateCheckService(projection, new dmmt.service.ProjectService())
                                    .findDuplicates(names.stream().map(Path::of).toList()).stream()
                                    .map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
                        } catch (IOException failure) {
                            throw new java.util.concurrent.CompletionException(new IOException("Could not check original map filenames on the host."));
                        }
                    }, worker).thenApplyAsync(duplicates -> {
                        requireOwner();
                        if (closed || generation != invalidation || !captured.context().equals(source.get().context()))
                            throw new IllegalStateException("Host maps changed while checking duplicate imports. Retry.");
                        var token = new ImportPreflight(UUID.randomUUID(), command.context(), list.revision(), behavior, List.copyOf(names), Set.copyOf(duplicates));
                        while (importPreflights.size() >= 16) importPreflights.remove(importPreflights.keySet().iterator().next());
                        importPreflights.put(token.id(), token);
                        var result = mapper.createObjectNode().put("preflight", token.id().toString()).put("behavior", behavior);
                        result.set("duplicates", mapper.valueToTree(duplicates));
                        return (JsonNode) result;
                    }, owner);
                });
            } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        }
        if ("levels".equals(action))
        {
            try
            {
                Command command = mapper.treeToValue(payload, Command.class);
                return validate(command, false).thenCompose(ignored -> {
                    if (command.entries().size() != 1)
                        return failed("Choose one multilevel map.");
                    Entry entry = entries.get(command.entries().getFirst());
                    if (!entry.isMultiLevel())
                        return failed("Choose a multilevel map.");
                    var library = source.get().library();
                    if (!command.context().equals(levelMetadataContext)) {
                        levelMetadata.clear();
                        levelMetadataContext = command.context();
                    }
                    UUID identity = command.entries().getFirst();
                    JsonNode accepted = levelMetadata.get(identity);
                    if (accepted != null) return CompletableFuture.completedFuture(accepted.deepCopy());
                    long generation = invalidation;
                    return CompletableFuture.supplyAsync(() -> {
                        try
                        {
                            var value = mapper.createObjectNode();
                            var levels = value.putArray("levels");
                            var manifest = library.multiLevels().loadManifest(entry.mapFile());
                            value.put("currentLevelId", manifest.getCurrentLevelId());
                            for (var level : manifest.getLevels())
                                levels.addObject().put("id", level.getId()).put("name", level.getName());
                            return (JsonNode)value;
                        }
                        catch (IOException failure)
                        {
                            throw new java.util.concurrent.CompletionException(new IOException("Could not read the host level metadata. Check the host log."));
                        }
                    }, worker).thenApplyAsync(value -> {
                        requireOwner();
                        if (closed || generation != invalidation || !source.get().context().equals(command.context()))
                            throw new IllegalStateException("Host levels changed. Refresh and reopen the dialog.");
                        levelMetadata.put(identity, value.deepCopy());
                        return value;
                    }, owner);
                });
            }
            catch (Exception failure)
            {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if ("preview".equals(action))
        {
            try
            {
                Command command = mapper.treeToValue(payload, Command.class);
                return validate(command, false).thenCompose(ignored -> {
                    if (command.entries().size() != 1)
                        return failed("Choose one map preview.");
                    Entry entry = entries.get(command.entries().getFirst());
                    if (!entry.isMap())
                        return failed("Folders have no preview.");
                    RemoteMapCatalog.Source captured = source.get();
                    return CompletableFuture.<JsonNode>supplyAsync(() -> {
                        try
                        {
                            byte[] png = captured.library().loadOrCreateThumbnail(entry.mapFile());
                            if (png == null)
                                return com.fasterxml.jackson.databind.node.NullNode.getInstance();
                            if (png.length > 8 * 1024 * 1024)
                                throw new IOException("Map preview exceeds its limit.");
                            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
                            return mapper.<JsonNode>valueToTree(assets.ingest(new ByteArrayInputStream(png), png.length, hash));
                        }
                        catch (Exception failure)
                        {
                            throw new java.util.concurrent.CompletionException(new IOException(
                                    "Could not prepare the host map preview for " + entry.name() + ".", failure));
                        }
                    }, worker).thenApplyAsync(result -> {
                        requireOwner();
                        if (closed)
                            throw new IllegalStateException("Host map library closed while loading its preview.");
                        // A stale preview is cosmetic; the client simply shows none and can re-request it.
                        if (!source.get().context().equals(captured.context()))
                            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
                        return result;
                    }, owner);
                });
            }
            catch (Exception failure)
            {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return failed("Unknown host map query.");
    }

    @Override
    public CompletableFuture<JsonNode> execute(String action, JsonNode payload)
    {
        requireOwner();
        if (!Set.of("open", "openLevel", "new", "import", "importFolder", "importMultiLevel", "export", "merge",
                    "drop", "manageLevels", "dissolve", "tags", "rename", "copy", "delete", "move",
                    "createFolder").contains(action))
            return failed("Unknown host map operation.");
        try
        {
            Command command = mapper.treeToValue(payload, Command.class);
            return validate(command, true).thenCompose(ignored -> {
                List<Entry> selected = command.entries().stream().map(entries::get).toList();
                Entry destination = command.destination() == null ? null : entries.get(command.destination());
                if (Set.of("open", "openLevel", "dissolve", "rename").contains(action) && selected.size() != 1)
                    return failed("Choose exactly one map or folder.");
                if (Set.of("createFolder", "move", "new", "import", "importFolder", "importMultiLevel").contains(action)
                        && (destination == null || !destination.isFolder())) return failed("Choose a destination folder.");
                if (Set.of("copy", "delete", "tags", "export", "merge", "drop", "manageLevels").contains(action)
                        && selected.isEmpty()) return failed("Choose maps first.");
                if (Set.of("open", "openLevel", "manageLevels", "dissolve", "tags", "export", "merge", "drop", "copy").contains(action)
                        && selected.stream().anyMatch(entry -> !entry.isMap())) return failed("Choose maps, not folders.");
                if (action.equals("drop") && (destination == null || !destination.isMap())) return failed("Choose a target map.");
                if (Set.of("rename", "copy", "delete", "move").contains(action)
                        && selected.stream().anyMatch(e -> e.path().equals(source.get().library().getRoot())))
                    return failed("The library root cannot be changed.");
                if (Set.of("import", "importFolder", "importMultiLevel").contains(action)) validateImport(command);
                return host.execute(new Request(action, command.context(), command.revision(), selected,
                                                destination, command.name(), command.options())).thenApplyAsync(value -> {
                    requireOwner();
                    if (!action.equals("export")) invalidate();
                    return value;
                }, owner);
            });
        }
        catch (Exception failure)
        {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Listing> validate(Command command, boolean refresh)
    {
        if (closed)
            return CompletableFuture.failedFuture(new IOException("Host map library is closed."));
        return listing(refresh).thenApply(list -> {
            if (!list.context().equals(command.context()) || list.revision() != command.revision())
                throw new IllegalStateException("The host map or library changed. Refresh and repeat the action.");
            if (!entries.keySet().containsAll(command.entries())
                    || command.destination() != null && !entries.containsKey(command.destination()))
                throw new IllegalArgumentException("The map selection no longer exists.");
            return list;
        });
    }

    private void validateImport(Command command) {
        JsonNode options = command.options();
        List<String> actual = new ArrayList<>();
        for (JsonNode item : options.path("sources")) actual.add(item.path("name").asText());
        if (actual.isEmpty() && options.path("plan").isArray()
                && java.util.stream.StreamSupport.stream(options.path("plan").spliterator(), false)
                .allMatch(item -> item.path("source").asInt(-2) == -1)) return;
        UUID identity;
        try { identity = UUID.fromString(options.path("preflight").asText()); }
        catch (IllegalArgumentException missing) { throw new IllegalArgumentException("Check duplicate imports against the host before importing."); }
        var preflight = importPreflights.remove(identity);
        if (preflight == null || !preflight.context().equals(command.context()) || preflight.revision() != command.revision()
                || !preflight.behavior().equals(dmmt.service.Tuning.IMPORT_DUPLICATE_BEHAVIOR.get()))
            throw new IllegalStateException("The host duplicate-import check expired. Retry the import.");
        Set<String> accepted = new HashSet<>();
        options.path("duplicateAccepted").forEach(n -> accepted.add(n.asText()));
        var available = new ArrayList<>(preflight.names());
        for (String name : actual) {
            if (!available.remove(name)) throw new IllegalArgumentException("The uploaded source list changed after duplicate confirmation.");
            if (preflight.duplicates().contains(name) && ("never".equals(preflight.behavior())
                    || !"always".equals(preflight.behavior()) && !accepted.contains(name)))
                throw new IllegalStateException("This duplicate source was not approved for import.");
        }
    }

    private CompletableFuture<Listing> listing(boolean refresh)
    {
        RemoteMapCatalog.Source captured = source.get();
        if (!refresh && cached != null && previousLibrary == captured.library())
            return CompletableFuture.completedFuture(snapshot(captured, cached));
        if (scanning != null) return scanning.copy();
        long generation = invalidation;
        var work = CompletableFuture.supplyAsync(() -> {
            try
            {
                if (closed) throw new IOException("Host map library is closed.");
                List<Node> nodes = new ArrayList<>();
                Map<UUID, Entry> found = new LinkedHashMap<>();
                Map<Path, UUID> paths = new HashMap<>();
                flatten(captured.library().scanRemote(), null, captured.library(), nodes, found, paths);
                return new Scan(nodes, found, paths);
            }
            catch (IOException failure)
            {
                throw new java.util.concurrent.CompletionException(new IOException("Could not read the host map library. Check its folder and permissions on the host."));
            }
        }, worker).thenApplyAsync(scan -> {
            requireOwner();
            RemoteMapCatalog.Source now = source.get();
            if (closed || generation != invalidation || now.library() != captured.library() || !now.context().equals(captured.context()))
                throw new IllegalStateException("The host map changed while reading the library. Refresh again.");
            if (previousLibrary != captured.library() || !previous.equals(scan.nodes()))
            {
                revision++;
                levelMetadata.clear();
                previous = List.copyOf(scan.nodes());
                previousLibrary = captured.library();
            }
            entries = Map.copyOf(scan.entries());
            cached = scan;
            return snapshot(now, scan);
        }, owner);
        scanning = work;
        work.whenComplete((value, failure) -> scanning = null);
        return work.copy();
    }

    private Listing snapshot(RemoteMapCatalog.Source now, Scan scan) {
        return new Listing(now.context(), revision, scan.nodes(),
                now.current() == null ? null : scan.paths().get(now.current().toAbsolutePath().normalize()),
                now.label(), recents.get().stream().map(p -> scan.paths().get(p.toAbsolutePath().normalize()))
                .filter(Objects::nonNull).distinct().limit(100).toList());
    }

    private void flatten(Entry entry, UUID parent, MapLibraryService library, List<Node> nodes,
                         Map<UUID, Entry> found, Map<Path, UUID> paths) throws IOException
    {
        Path path = entry.path().toAbsolutePath().normalize();
        UUID id = UUID.nameUUIDFromBytes((salt + "\n" + path).getBytes(StandardCharsets.UTF_8));
        List<String> tags = List.of();
        int levels = 0;
        if (entry.isMap()) {
            try { tags = library.tags().readTags(entry.mapFile()); }
            catch (IOException | IllegalArgumentException unreadable) {
                LOG.warn("Could not read host map tags for {}", entry.mapFile(), unreadable);
            }
        }
        if (entry.isMultiLevel()) {
            try { levels = library.multiLevels().loadManifest(entry.mapFile()).getLevels().size(); }
            catch (IOException | IllegalArgumentException unreadable) {
                LOG.warn("Could not read host multilevel metadata for {}", entry.mapFile(), unreadable);
            }
        }
        long stamp = Files.getLastModifiedTime(entry.isMap() ? entry.mapFile() : entry.path()).toMillis();
        if (entry.isMap())
        {
            Path thumbnail = dmmt.service.ThumbnailService.thumbnailFile(entry.mapFile());
            if (Files.isRegularFile(thumbnail))
                stamp = 31 * stamp + Files.getLastModifiedTime(thumbnail).toMillis();
        }
        nodes.add(new Node(id, parent, entry.name(), entry.isFolder() ? RemoteMapCatalog.Kind.FOLDER
                                                                      : entry.isMultiLevel() ? RemoteMapCatalog.Kind.MULTILEVEL : RemoteMapCatalog.Kind.MAP, tags, levels, stamp));
        found.put(id, new Entry(entry.kind(), entry.name(), entry.path(), entry.mapFile(), entry.children(), tags));
        paths.put(path, id);
        if (entry.mapFile() != null)
            paths.put(entry.mapFile().toAbsolutePath().normalize(), id);
        for (Entry child : entry.children())
            flatten(child, id, library, nodes, found, paths);
    }

    private static <T> CompletableFuture<T> failed(String text)
    {
        return CompletableFuture.failedFuture(new IOException(text));
    }

    /**
     * Worker-only service operation; the Host must save affected maps first and reconcile this result.
     */
    public static MapLibraryService.Result mutate(MapLibraryService library, Request request) throws IOException
    {
        return mutate(library, request, asset -> {
            throw new IOException("The host must materialize imported level assets.");
        });
    }

    @FunctionalInterface
    public interface ImportResolver
    {
        Path resolve(JsonNode asset) throws IOException;
    }

    public static MapLibraryService.Result mutate(MapLibraryService library, Request request, ImportResolver imports) throws IOException
    {
        Map<Path, Path> moved = new LinkedHashMap<>();
        Path created = null;
        switch (request.action())
        {
            case "createFolder" -> library.createFolder(request.destination().path(), request.name());
            case "rename" ->
            {
                if (request.entries().size() != 1)
                    throw new IOException("Choose one entry to rename.");
                return library.rename(request.entries().getFirst(), request.name());
            }
            case "copy", "move", "delete" ->
            {
                if (request.entries().isEmpty())
                    throw new IOException("Choose maps or folders first.");
                for (Entry entry : request.entries())
                {
                    if (request.action().equals("delete"))
                        library.delete(entry);
                    else
                    {
                        var result = request.action().equals("copy") ? library.copy(entry)
                                                                     : library.move(entry, request.destination().path());
                        moved.putAll(result.movedMaps());
                        if (result.createdMap() != null)
                            created = result.createdMap();
                    }
                }
            }
            case "tags" ->
            {
                JsonNode options = request.options();
                List<String> additions = new ArrayList<>(), removals = new ArrayList<>();
                options.path("additions").forEach(n -> additions.add(n.asText()));
                options.path("removals").forEach(n -> removals.add(n.asText()));
                library.tags().updateTags(request.entries().stream().map(Entry::mapFile).toList(), additions, removals);
            }
            case "dissolve" ->
            {
                var result = library.multiLevels().dissolve(request.entries().getFirst().mapFile(), (i, total, name) -> {
                });
                return new MapLibraryService.Result(result.movedMaps(), result.collapsedMap());
            }
            case "manageLevels" ->
            {
                List<dmmt.service.MultiLevelService.PlanItem> plan = new ArrayList<>();
                for (JsonNode item : request.options().path("plan"))
                {
                    dmmt.service.MultiLevelService.Source source;
                    if (item.hasNonNull("id"))
                        source = new dmmt.service.MultiLevelService.Existing(item.path("id").asText());
                    else if (item.hasNonNull("entry"))
                    {
                        int index = item.path("entry").asInt(-1);
                        if (index < 1 || index >= request.entries().size())
                            throw new IOException("Invalid source map.");
                        Entry entry = request.entries().get(index);
                        if (!entry.isMap() || entry.isMultiLevel())
                            throw new IOException("Choose an ordinary source map.");
                        source = new dmmt.service.MultiLevelService.LibraryMap(entry.path(), entry.mapFile());
                    }
                    else if (item.hasNonNull("asset"))
                        source = new dmmt.service.MultiLevelService.Dd2vtt(imports.resolve(item));
                    else
                        source = new dmmt.service.MultiLevelService.Empty();
                    plan.add(new dmmt.service.MultiLevelService.PlanItem(source, item.path("name").asText()));
                }
                List<dmmt.service.MultiLevelService.Extraction> extractions = new ArrayList<>();
                for (JsonNode item : request.options().path("extractions"))
                    extractions.add(new dmmt.service.MultiLevelService.Extraction(item.path("levelId").asText(), item.path("mapName").asText()));
                var result = library.multiLevels().apply(request.entries().getFirst().mapFile(), plan, extractions, (i, total, name) -> {
                });
                return new MapLibraryService.Result(result.movedMaps(), result.collapsedMap() != null ? result.collapsedMap() : result.manifestFile());
            }
            case "merge", "drop" ->
            {
                List<Entry> selected = new ArrayList<>(request.entries());
                if (request.destination() != null)
                    selected.addFirst(request.destination());
                Entry target = selected.stream().filter(Entry::isMultiLevel).findFirst().orElse(null);
                List<dmmt.service.MultiLevelService.PlanItem> plan = new ArrayList<>();
                if (request.options() != null && request.options().has("plan")) {
                    for (JsonNode item : request.options().path("plan")) {
                        dmmt.service.MultiLevelService.Source levelSource;
                        if (item.hasNonNull("id")) {
                            if (target == null) throw new IOException("The target multilevel map changed.");
                            levelSource = new dmmt.service.MultiLevelService.Existing(item.path("id").asText());
                        } else if (item.hasNonNull("entry")) {
                            int index = item.path("entry").asInt(-1);
                            if (index < 0 || index >= selected.size()) throw new IOException("Invalid source map.");
                            Entry entry = selected.get(index);
                            if (item.hasNonNull("levelId")) levelSource = new dmmt.service.MultiLevelService.ForeignLevel(
                                    entry.mapFile(), item.path("levelId").asText());
                            else levelSource = new dmmt.service.MultiLevelService.LibraryMap(entry.path(), entry.mapFile());
                        } else if (item.hasNonNull("asset")) levelSource = new dmmt.service.MultiLevelService.Dd2vtt(imports.resolve(item));
                        else levelSource = new dmmt.service.MultiLevelService.Empty();
                        plan.add(new dmmt.service.MultiLevelService.PlanItem(levelSource, item.path("name").asText()));
                    }
                } else for (Entry entry : selected)
                {
                    if (entry.isMultiLevel())
                    {
                        for (var level : library.multiLevels().loadManifest(entry.mapFile()).getLevels())
                        {
                            dmmt.service.MultiLevelService.Source source = entry == target
                                                                           ? new dmmt.service.MultiLevelService.Existing(level.getId())
                                                                           : new dmmt.service.MultiLevelService.ForeignLevel(entry.mapFile(), level.getId());
                            plan.add(new dmmt.service.MultiLevelService.PlanItem(source, level.getName()));
                        }
                    }
                    else
                        plan.add(new dmmt.service.MultiLevelService.PlanItem(
                                new dmmt.service.MultiLevelService.LibraryMap(entry.path(), entry.mapFile()), entry.name()));
                }
                        List<dmmt.service.MultiLevelService.Extraction> extractions = new ArrayList<>();
                        if (request.options() != null)
                            for (JsonNode item : request.options().path("extractions"))
                             extractions.add(new dmmt.service.MultiLevelService.Extraction(item.path("levelId").asText(), item.path("mapName").asText()));
                        var result = target == null
                             ? library.multiLevels().create(selected.getFirst().containingFolder(),
                             request.name() == null ? selected.getFirst().name() + " multilevel" : request.name(), plan, (i, total, name) -> {
                        })
                             : library.multiLevels().apply(target.mapFile(), plan, extractions, (i, total, name) -> {
                });
                return new MapLibraryService.Result(result.movedMaps(), result.collapsedMap() != null ? result.collapsedMap() : result.manifestFile());
            }
            default -> throw new IOException("This map workflow requires the application host.");
        }
        return new MapLibraryService.Result(moved, created);
    }

    @Override
    public void close()
    {
        requireOwner();
        closed = true;
        entries = Map.of();
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("Map library requires the application thread.");
    }
}
