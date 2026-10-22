package dmmt.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject.TokenDefinition;
import dmmt.model.DmProject.TokenShape;
import dmmt.render.TokenArt;
import dmmt.service.JsonMappers;
import dmmt.service.TokenLibraryService;
import javafx.scene.image.Image;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Host-owned token library operations. Only concrete metadata and verified asset descriptors cross the wire. */
public final class RemoteTokens implements RemoteOperations.Endpoint, AutoCloseable
{
    public static final String DOMAIN = "tokens";
    private static final ObjectMapper MAPPER = JsonMappers.create();
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_FOLDERS = 2_000;

    public enum Operation { PUT, DELETE, MOVE, CREATE_FOLDER, MOVE_FOLDER, DELETE_FOLDER }

    public record Definition(String id, String name, String color,
                             @JsonProperty(required = true) double sizeTiles, TokenShape shape,
                             @JsonProperty(required = true) boolean border,
                             @JsonProperty(required = true) double zoom,
                             @JsonProperty(required = true) double centerX,
                             @JsonProperty(required = true) double centerY,
                             RemoteAssetStore.AssetDescriptor source, RemoteAssetStore.AssetDescriptor crop)
    {
        public Definition
        {
            text(id, "Token identity", 256, false);
            text(name, "Token name", 256, false);
            if (color == null || !color.matches("#[0-9a-fA-F]{6}") || shape == null
                    || !Double.isFinite(sizeTiles) || sizeTiles < .5 || sizeTiles > 1_000_000
                    || sizeTiles * 2 != Math.rint(sizeTiles * 2)
                    || !Double.isFinite(zoom) || zoom < 1 || zoom > 10
                    || !Double.isFinite(centerX) || centerX < 0 || centerX > 1
                    || !Double.isFinite(centerY) || centerY < 0 || centerY > 1)
                throw new IllegalArgumentException("Invalid token appearance or crop.");
            if (source != null && source.size() > RemoteImages.MAX_BYTES
                    || crop != null && crop.size() > RemoteImages.MAX_BYTES)
                throw new IllegalArgumentException("Token artwork exceeds the remote byte limit.");
        }

        public TokenDefinition materialize(String sourceImage, String image)
        {
            return TokenDefinition.builder().id(id).name(name).color(color).sizeTiles(sizeTiles)
                    .shape(shape).border(border).zoom(zoom).centerX(centerX).centerY(centerY)
                    .sourceImage(sourceImage).image(image).build();
        }
    }

    public record Entry(String folder, Definition token)
    {
        public Entry
        {
            RemoteTokens.folder(folder);
            Objects.requireNonNull(token, "token");
        }
    }

    public record State(@JsonProperty(required = true) long revision, List<String> folders, List<Entry> entries)
    {
        public State
        {
            folders = List.copyOf(folders);
            entries = List.copyOf(entries);
            if (revision < 0 || folders.size() > MAX_FOLDERS || entries.size() > MAX_ENTRIES
                    || new HashSet<>(folders).size() != folders.size()
                    || entries.stream().map(e -> e.token.id).distinct().count() != entries.size())
                throw new IllegalArgumentException("Invalid token library snapshot.");
            Set<String> known = new HashSet<>(folders);
            known.add("");
            for (String path : folders)
            {
                folder(path);
                if (path.isEmpty() || !known.contains(parent(path)))
                    throw new IllegalArgumentException("Invalid token folder hierarchy.");
            }
            if (entries.stream().anyMatch(e -> !known.contains(e.folder)))
                throw new IllegalArgumentException("Token refers to a missing folder.");
        }
    }

    public record Stamp(@JsonProperty(required = true) long revision)
    {
        public Stamp
        {
            if (revision < -1)
                throw new IllegalArgumentException("Invalid token library revision.");
        }
    }

    public record Request(@JsonProperty(required = true) long revision, Operation operation,
                          Definition token, String id, String folder, String parent, String name)
    {
        public Request
        {
            if (revision < 0 || operation == null)
                throw new IllegalArgumentException("Token library is not ready.");
            switch (operation)
            {
                case PUT -> {
                    Objects.requireNonNull(token, "token");
                    RemoteTokens.folder(folder);
                    if (id != null || parent != null || name != null) invalidFields();
                }
                case DELETE -> {
                    text(id, "Token identity", 256, false);
                    if (token != null || folder != null || parent != null || name != null) invalidFields();
                }
                case MOVE -> {
                    text(id, "Token identity", 256, false);
                    RemoteTokens.folder(folder);
                    if (token != null || parent != null || name != null) invalidFields();
                }
                case CREATE_FOLDER -> {
                    RemoteTokens.folder(parent);
                    folderName(name);
                    if (token != null || id != null || folder != null) invalidFields();
                }
                case MOVE_FOLDER -> {
                    RemoteTokens.folder(folder);
                    RemoteTokens.folder(parent);
                    folderName(name);
                    if (token != null || id != null) invalidFields();
                }
                case DELETE_FOLDER -> {
                    RemoteTokens.folder(folder);
                    if (token != null || id != null || parent != null || name != null) invalidFields();
                }
            }
        }
    }

    private final Supplier<TokenLibraryService> libraries;
    private final RemoteAssetStore store;
    private final Path cache;
    private final Executor worker;
    private final Executor owner;
    private final Consumer<TokenDefinition> changed;
    private final Thread ownerThread = Thread.currentThread();
    private volatile boolean closed;
    private Runnable libraryChanged = () -> {};

    public RemoteTokens(Supplier<TokenLibraryService> libraries, RemoteAssetStore store, Path cache,
                        Executor worker, Executor owner, Consumer<TokenDefinition> changed)
    {
        this.libraries = Objects.requireNonNull(libraries);
        this.store = Objects.requireNonNull(store);
        this.cache = Objects.requireNonNull(cache);
        this.worker = Objects.requireNonNull(worker);
        this.owner = Objects.requireNonNull(owner);
        this.changed = Objects.requireNonNull(changed);
    }

    public RemoteOperations.Endpoint endpoint() { return this; }

    public void setLibraryChanged(Runnable listener)
    {
        requireOwner();
        libraryChanged = Objects.requireNonNull(listener);
    }

    @Override
    public CompletableFuture<JsonNode> query(String action, JsonNode payload)
    {
        requireOwner();
        try
        {
            requireActive();
            if (!"list".equals(action))
                throw new IOException("Unknown token library query.");
            Stamp stamp = MAPPER.treeToValue(payload, Stamp.class);
            TokenLibraryService service = libraries.get();
            if (stamp.revision == service.revision())
                return CompletableFuture.completedFuture(MAPPER.nullNode());
            return snapshot(service);
        }
        catch (Exception error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<JsonNode> execute(String action, JsonNode payload)
    {
        requireOwner();
        try
        {
            requireActive();
            if (!"change".equals(action))
                throw new IOException("Unknown token library command.");
            Request request = MAPPER.treeToValue(payload, Request.class);
            TokenLibraryService service = libraries.get();
            checkRevision(service, request.revision);
            return work(() -> {
                        Incoming prepared = request.token == null ? null : incoming(request.token);
                        synchronized (service)
                        {
                            requireActive();
                            if (libraries.get() != service)
                                throw new IOException("Host token library changed. Reload and retry.");
                            checkRevision(service, request.revision);
                            TokenDefinition token = prepared == null ? null : prepared.token;
                            switch (request.operation)
                            {
                                case PUT -> service.put(token, request.folder);
                                case DELETE -> service.delete(request.id);
                                case MOVE -> service.move(request.id, request.folder);
                                case CREATE_FOLDER -> service.createFolder(request.parent, request.name);
                                case MOVE_FOLDER -> service.moveFolder(request.folder, request.parent, request.name);
                                case DELETE_FOLDER -> service.deleteFolder(request.folder);
                            }
                            return new Committed(prepared, service.revision());
                        }
                    })
                    .thenComposeAsync(committed -> {
                        try
                        {
                            requireActive();
                            if (libraries.get() != service)
                                throw new IOException("Host token library changed after the edit was saved. Reload to inspect it.");
                            Incoming prepared = committed.prepared;
                            TokenDefinition token = prepared == null ? null : prepared.token;
                            if (service.revision() != committed.revision)
                            {
                                return notifyLatest(service, token == null ? null : token.getId())
                                        .thenComposeAsync(ignored -> {
                                            libraryChanged.run();
                                            return snapshot(service);
                                        }, owner);
                            }
                            if (token != null)
                            {
                                if (prepared.crop != null)
                                    TokenArt.cacheRemoteImage(token.getImage(), prepared.crop);
                                changed.accept(TokenLibraryService.copy(token));
                            }
                            libraryChanged.run();
                            return snapshot(service);
                        }
                        catch (Exception error)
                        {
                            return CompletableFuture.failedFuture(error);
                        }
                    }, owner).handleAsync(RemoteTokens::completed, owner);
        }
        catch (Exception error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }

    private record Latest(TokenDefinition token, long revision) {}

    private CompletableFuture<Void> notifyLatest(TokenLibraryService service, String id)
    {
        if (id == null)
            return CompletableFuture.completedFuture(null);
        return work(() -> {
            synchronized (service)
            {
                requireActive();
                if (libraries.get() != service)
                    throw new IOException("Host token library changed. Reload to inspect the accepted edit.");
                TokenDefinition token = service.load().getEntries().stream()
                        .filter(entry -> !entry.isDeleted() && id.equals(entry.getToken().getId()))
                        .map(entry -> TokenLibraryService.copy(entry.getToken())).findFirst().orElse(null);
                return new Latest(token, service.revision());
            }
        }).thenComposeAsync(latest -> {
            try
            {
                requireActive();
                if (libraries.get() != service)
                    throw new IOException("Host token library changed. Reload to inspect the accepted edit.");
                if (service.revision() != latest.revision)
                    return notifyLatest(service, id);
                if (latest.token != null)
                    changed.accept(latest.token);
                return CompletableFuture.completedFuture(null);
            }
            catch (IOException error)
            {
                return CompletableFuture.failedFuture(error);
            }
        }, owner);
    }

    private CompletableFuture<JsonNode> snapshot(TokenLibraryService service)
    {
        return work(() -> {
            TokenLibraryService.Library library;
            long revision;
            synchronized (service)
            {
                library = service.load();
                revision = service.revision();
            }
            var entries = new ArrayList<Entry>();
            for (var entry : library.getEntries())
                if (!entry.isDeleted())
                    entries.add(new Entry(entry.getFolder(), publish(entry.getToken())));
            requireActive();
            return MAPPER.<JsonNode>valueToTree(new State(revision, library.getFolders(), entries));
        }).handleAsync(RemoteTokens::completed, owner);
    }

    private Definition publish(TokenDefinition token) throws IOException
    {
        return definition(token, publish(token.getSourceImage()), publish(token.getImage()));
    }

    private RemoteAssetStore.AssetDescriptor publish(String reference) throws IOException
    {
        if (reference == null)
            return null;
        Path file = localArtwork(reference);
        RemoteImages.validatePng(file);
        requireActive();
        return store.publishRequiredAsset(file);
    }

    private record Incoming(TokenDefinition token, Image crop) {}
    private record Committed(Incoming prepared, long revision) {}

    private Incoming incoming(Definition token) throws IOException
    {
        String source = materialize(token.source);
        String crop = materialize(token.crop);
        return new Incoming(token.materialize(source, crop),
                crop == null ? null : RemoteImages.read(localArtwork(crop), true, 256));
    }

    private String materialize(RemoteAssetStore.AssetDescriptor asset) throws IOException
    {
        if (asset == null)
            return null;
        if (!store.descriptor(asset.sha256()).equals(asset))
            throw new IOException("Token artwork does not match its verified upload.");
        Path file = store.materialize(asset.sha256(), cache, "png");
        RemoteImages.read(file, true, 256);
        return file.toUri().toString();
    }

    private void checkRevision(TokenLibraryService service, long revision) throws IOException
    {
        if (service.revision() != revision)
            throw new IOException("The host token library changed while you were editing. Reload and retry.");
    }

    private <T> CompletableFuture<T> work(CheckedSupplier<T> action)
    {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireActive();
                return action.get();
            }
            catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }

    private void requireOwner()
    {
        if (Thread.currentThread() != ownerThread)
            throw new IllegalStateException("Token operations must start on their application owner.");
    }

    private void requireActive() throws IOException
    {
        if (closed)
            throw new IOException("Host token library operations have stopped.");
    }

    @Override
    public void close()
    {
        requireOwner();
        closed = true;
    }

    /** Uploads only after the local dialog has completed; no client library is read or written. */
    public static CompletableFuture<Definition> upload(TokenDefinition token, RemoteOperations.Connection connection)
    {
        TokenDefinition copy = TokenLibraryService.copy(token);
        try
        {
            var source = upload(copy.getSourceImage(), connection);
            var crop = upload(copy.getImage(), connection);
            return source.thenCombine(crop, (original, rendered) -> definition(copy, original, rendered));
        }
        catch (Exception error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static CompletableFuture<RemoteAssetStore.AssetDescriptor> upload(
            String reference, RemoteOperations.Connection connection) throws IOException
    {
        return reference == null ? CompletableFuture.completedFuture(null) : connection.upload(localArtwork(reference));
    }

    public record Prepared(State state, TokenLibraryService.Library library, Map<String, Image> crops)
    {
        public void acceptArtwork()
        {
            library.getEntries().forEach(entry -> TokenArt.registerRemoteImage(entry.getToken().getImage()));
            crops.forEach(TokenArt::cacheRemoteImage);
        }
    }

    /** Downloads are asynchronous; PNG inspection/decoding occurs solely on the supplied worker. */
    public static CompletableFuture<Prepared> download(State state, RemoteOperations.Connection connection,
                                                       Executor worker, Executor owner)
    {
        Map<RemoteAssetStore.AssetDescriptor, CompletableFuture<Path>> downloads = new LinkedHashMap<>();
        for (Entry entry : state.entries)
        {
            if (entry.token.source != null)
                downloads.computeIfAbsent(entry.token.source, connection::download);
            if (entry.token.crop != null)
                downloads.computeIfAbsent(entry.token.crop, connection::download);
        }
        return CompletableFuture.allOf(downloads.values().toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    try
                    {
                        var library = new TokenLibraryService.Library();
                        library.setFolders(new ArrayList<>(state.folders));
                        Map<String, Image> crops = new LinkedHashMap<>();
                        Set<Path> validated = new HashSet<>();
                        for (Entry entry : state.entries)
                        {
                            String source = downloaded(entry.token.source, downloads, validated);
                            String crop = downloaded(entry.token.crop, downloads, validated);
                            if (crop != null && crops.size() < 128 && !crops.containsKey(crop))
                                crops.put(crop, RemoteImages.read(localArtwork(crop), true, 256));
                            var local = new TokenLibraryService.Entry();
                            local.setFolder(entry.folder);
                            local.setToken(entry.token.materialize(source, crop));
                            library.getEntries().add(local);
                        }
                        return new Prepared(state, library, Map.copyOf(crops));
                    }
                    catch (IOException error)
                    {
                        throw new CompletionException(error);
                    }
                }, worker).handleAsync(RemoteTokens::completed, owner);
    }

    private static String downloaded(RemoteAssetStore.AssetDescriptor asset,
                                     Map<RemoteAssetStore.AssetDescriptor, CompletableFuture<Path>> downloads,
                                     Set<Path> validated) throws IOException
    {
        if (asset == null)
            return null;
        Path file = downloads.get(asset).join();
        if (validated.add(file))
            RemoteImages.validatePng(file);
        return file.toUri().toString();
    }

    public static JsonNode json(Object value) { return MAPPER.valueToTree(value); }
    public static State state(JsonNode value) throws IOException { return MAPPER.treeToValue(value, State.class); }

    private static Definition definition(TokenDefinition token, RemoteAssetStore.AssetDescriptor source,
                                          RemoteAssetStore.AssetDescriptor crop)
    {
        return new Definition(token.getId(), token.getName(), token.getColor(), token.getSizeTiles(),
                token.getShape(), token.isBorder(), token.getZoom(), token.getCenterX(), token.getCenterY(), source, crop);
    }

    public static Path localArtwork(String reference) throws IOException
    {
        try
        {
            if (!reference.startsWith("file:"))
                throw new IOException("Token artwork must be a local immutable PNG.");
            return Path.of(URI.create(reference));
        }
        catch (IllegalArgumentException invalid)
        {
            throw new IOException("Unsupported token artwork reference.", invalid);
        }
    }

    private static void text(String value, String label, int limit, boolean empty)
    {
        if (value == null || value.length() > limit || !empty && value.isBlank()
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + " is invalid.");
    }

    private static void folder(String path)
    {
        text(path, "Token folder", 1024, true);
        if (!path.isEmpty())
            for (String part : path.split("/", -1))
                folderName(part);
    }

    private static void folderName(String name)
    {
        text(name, "Token folder name", 128, false);
        if (!name.equals(name.trim()) || name.equals(".") || name.equals("..")
                || name.matches(".*[<>:\"/\\\\|?*].*"))
            throw new IllegalArgumentException("Invalid token folder name.");
    }

    private static String parent(String folder)
    {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? "" : folder.substring(0, slash);
    }

    private static void invalidFields() { throw new IllegalArgumentException("Unexpected token command fields."); }

    private static <T> T completed(T value, Throwable error)
    {
        if (error != null)
            throw new CompletionException(error);
        return value;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
