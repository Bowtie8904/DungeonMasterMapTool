package dmmt.remote;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** On-demand host directory metadata only; no file-content or filesystem-write operations. */
public final class RemoteDirectories {
    public static final int PAGE_SIZE = 128;
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CHARACTERS = 524288;

    public record Query(String path, @JsonProperty(required = true) int offset) {
        public Query {
            if (path != null) text(path);
            if (offset < 0 || offset > MAX_CHILDREN || offset % PAGE_SIZE != 0) {
                throw new IllegalArgumentException("Invalid host directory page.");
            }
        }
    }

    public record Entry(String name, String path) {
        public Entry {
            text(name);
            text(path);
        }
    }

    public record Listing(String path, String parent, List<Entry> entries,
                          @JsonProperty(required = true) int offset,
                          @JsonProperty(required = true) boolean more, String error) {
        public Listing {
            if (path != null) text(path);
            if (parent != null) text(parent);
            entries = List.copyOf(entries);
            long characters = entries.stream().mapToLong(entry -> entry.name().length() + entry.path().length()).sum();
            if (entries.size() > PAGE_SIZE || offset < 0 || offset > MAX_CHILDREN || offset % PAGE_SIZE != 0
                    || characters > 131072
                    || error != null && (error.isBlank() || error.length() > 512)) {
                throw new IllegalArgumentException("Invalid host directory listing.");
            }
        }
    }

    private RemoteDirectories() { }

    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteDirectories.class);

    /** Run on the server connection worker, never on the application thread. */
    public static Listing list(Query query) {
        try {
            Path folder = query.path() == null ? null : Path.of(query.path());
            if (folder != null && !folder.isAbsolute()) {
                return failed(query, "Enter an absolute path on the host, not a path on this PC.");
            }
            var entries = new ArrayList<Entry>();
            if (folder == null) {
                for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                    entries.add(new Entry(root.toString(), root.toString()));
                }
            } else {
                folder = folder.toRealPath();
                int characters = 0;
                int visited = 0;
                try (var stream = Files.newDirectoryStream(folder)) {
                    for (Path child : stream) {
                        if (++visited > 65536) return failed(query, "This directory is too large to browse. Enter a narrower host path.");
                        if (!Files.isDirectory(child)) continue;
                        String path = child.toAbsolutePath().normalize().toString();
                        String name = child.getFileName().toString();
                        characters += path.length() + name.length();
                        if (entries.size() == MAX_CHILDREN || characters > MAX_CHARACTERS) {
                            return failed(query, "This directory exceeds the listing limit. Enter a narrower host path.");
                        }
                        entries.add(new Entry(name, path));
                    }
                }
            }
            entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::path));
            int start = Math.min(query.offset(), entries.size());
            int end = Math.min(start + PAGE_SIZE, entries.size());
            if (entries.subList(start, end).stream().mapToLong(entry -> entry.name().length() + entry.path().length())
                    .sum() > 131072) {
                return failed(query, "This directory page exceeds the path-size limit. Enter a narrower host path.");
            }
            return new Listing(folder == null ? null : folder.toString(),
                    folder == null || folder.getParent() == null ? null : folder.getParent().toString(),
                    entries.subList(start, end), query.offset(), end < entries.size(), null);
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException failure) {
            LOG.warn("Host directory listing failed for '{}'", RemoteSessionServer.safe(query.path()), failure);
            return failed(query, "The host could not read this directory. Check that it exists and is accessible.");
        } catch (java.nio.file.InvalidPathException invalid) {
            LOG.warn("Invalid host directory path '{}'", RemoteSessionServer.safe(query.path()));
            return failed(query, "This is not a valid host directory path.");
        } catch (IllegalArgumentException unsupported) {
            LOG.warn("Host directory '{}' has names that cannot be listed", RemoteSessionServer.safe(query.path()), unsupported);
            return failed(query, "This directory contains names that cannot be displayed safely. Enter a narrower host path.");
        }
    }

    private static Listing failed(Query query, String error) {
        LOG.debug("Host directory listing rejected for '{}': {}", RemoteSessionServer.safe(query.path()), error);
        return new Listing(query.path(), null, List.of(), query.offset(), false, error);
    }

    private static void text(String text) {
        Objects.requireNonNull(text);
        if (text.isBlank() || text.length() > 4096 || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid host directory text.");
        }
    }
}
