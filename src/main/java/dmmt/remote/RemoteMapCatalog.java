package dmmt.remote;

import dmmt.log.Log;

import dmmt.service.MapLibraryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Explicit, bounded host metadata browsing. Paths and map contents never leave the host. */
public final class RemoteMapCatalog implements AutoCloseable {
    private static final Log LOG = Log.of(RemoteMapCatalog.class);
    public enum Kind { FOLDER, MAP, MULTILEVEL }

    public record Node(UUID id, UUID parent, String name, Kind kind) {
        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            text(name);
        }
    }

    public record Listing(List<Node> nodes, UUID current, String currentLabel, UUID context, String error) {
        public Listing {
            nodes = List.copyOf(nodes);
            text(currentLabel);
            Objects.requireNonNull(context);
            if (nodes.size() > 1024 || error != null && (error.isBlank() || error.length() > 500)
                    || nodes.stream().mapToLong(node -> node.name().length()).sum() > 65536) {
                throw new IllegalArgumentException("Host map catalog exceeds its metadata limit.");
            }
            var folders = new HashSet<UUID>();
            var identities = new HashSet<UUID>();
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i);
                if (!identities.add(node.id()) || (i == 0 ? node.parent() != null || node.kind() != Kind.FOLDER
                        : node.parent() == null || !folders.contains(node.parent()))) {
                    throw new IllegalArgumentException("Invalid host map catalog tree.");
                }
                if (node.kind() == Kind.FOLDER) folders.add(node.id());
            }
            if (error == null && nodes.isEmpty() || error != null && !nodes.isEmpty()
                    || current != null && nodes.stream().noneMatch(node -> node.id().equals(current) && node.kind() != Kind.FOLDER)) {
                throw new IllegalArgumentException("Invalid host map catalog state.");
            }
            var depths = new java.util.HashMap<UUID, Integer>();
            for (var node : nodes) {
                int depth = node.parent() == null ? 0 : depths.get(node.parent()) + 1;
                if (depth > 25) throw new IllegalArgumentException("Host map catalog is too deep.");
                depths.put(node.id(), depth);
            }
        }
    }

    public record Source(MapLibraryService library, Path current, String label, UUID context) {
        public Source {
            Objects.requireNonNull(library);
            Objects.requireNonNull(context);
            text(label);
        }
    }

    private final Thread thread = Thread.currentThread();
    private final UUID salt = UUID.randomUUID();
    private final Supplier<Source> source;
    private final Executor worker, owner;
    private CompletableFuture<Listing> pending;
    private volatile boolean closed;

    public RemoteMapCatalog(Supplier<Source> source, Executor worker, Executor owner) {
        this.source = Objects.requireNonNull(source);
        this.worker = Objects.requireNonNull(worker);
        this.owner = Objects.requireNonNull(owner);
    }

    public CompletableFuture<Listing> query() {
        requireOwner();
        if (closed) return CompletableFuture.failedFuture(new IOException("Host map catalog is closed."));
        if (pending != null) return pending.copy();
        var captured = source.get();
        var completion = new CompletableFuture<Listing>();
        pending = completion;
        try {
            CompletableFuture.supplyAsync(() -> scan(captured), worker).whenComplete((listing, failure) -> owner.execute(() -> {
                requireOwner();
                pending = null;
                if (closed) return;
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else if (source.get().library() != captured.library()) {
                    completion.complete(failed(captured, "The host map-library folder changed. Refresh again."));
                } else {
                    completion.complete(listing);
                }
            }));
        } catch (RejectedExecutionException unavailable) {
            pending = null;
            completion.completeExceptionally(new IOException("Host map catalog worker is unavailable.", unavailable));
        }
        return completion.copy();
    }

    private Listing scan(Source captured) {
        if (closed) throw new java.util.concurrent.CancellationException("Host map catalog closed before scanning.");
        try {
            var nodes = new ArrayList<Node>();
            var current = new UUID[1];
            flatten(captured.library().scanRemote(), null, captured.current(), current, nodes);
            return new Listing(nodes, current[0], captured.label(), captured.context(), null);
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("Could not browse host map library.", failure);
            return failed(captured, failure instanceof IOException ? safeMessage(failure)
                    : "The host library contains unsupported or oversized names. Check the host log.");
        }
    }

    private void flatten(MapLibraryService.Entry entry, UUID parent, Path open, UUID[] current, List<Node> nodes) {
        UUID id = UUID.nameUUIDFromBytes((salt + "\n" + entry.path().toAbsolutePath().normalize()).getBytes(StandardCharsets.UTF_8));
        nodes.add(new Node(id, parent, entry.name(), entry.isFolder() ? Kind.FOLDER
                : entry.isMultiLevel() ? Kind.MULTILEVEL : Kind.MAP));
        if (entry.mapFile() != null && open != null
                && entry.mapFile().toAbsolutePath().normalize().equals(open.toAbsolutePath().normalize())) current[0] = id;
        for (var child : entry.children()) flatten(child, id, open, current, nodes);
    }

    private static Listing failed(Source source, String message) {
        return new Listing(List.of(), null, source.label(), source.context(), message);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("Host map library exceeds")) return message;
        return "Could not read the host map library. Check its folder, permissions and host log.";
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid host map catalog label.");
        }
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        if (pending != null) pending.completeExceptionally(new IOException("Host map catalog closed."));
    }

    private void requireOwner() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Host map catalog requires the application thread.");
    }
}
