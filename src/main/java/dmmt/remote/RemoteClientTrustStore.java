package dmmt.remote;

import dmmt.log.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-local "allow always" and "block" lists. Clients are only identified by their unauthenticated
 * display name plus the peer IP address, so this is a convenience for a trusted LAN, not proof of identity.
 * The file holds no codes, credentials or host secrets. Callers serialize all access on one thread.
 */
public final class RemoteClientTrustStore {
    private static final Log LOG = Log.of(RemoteClientTrustStore.class);
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 200;

    public enum Decision { ALLOWED, BLOCKED }

    public record Entry(String name, String address, Decision decision) {
        public Entry {
            var client = new RemoteSessionAccess.Client(name, address);
            name = client.name();
            address = client.address();
            Objects.requireNonNull(decision, "decision");
        }

        public String label() {
            return name + " (" + address + ")";
        }

        private String key() {
            return key(name, address);
        }

        private static String key(String name, String address) {
            return name + "\t" + address;
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public RemoteClientTrustStore(Path directory) throws IOException {
        file = directory.toAbsolutePath().normalize().resolve("clients.txt");
        load();
    }

    /** Null when the client has never been remembered. */
    public Decision decision(RemoteSessionAccess.Client client) {
        var entry = entries.get(Entry.key(client.name(), client.address()));
        return entry == null ? null : entry.decision();
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public void remember(RemoteSessionAccess.Client client, Decision decision) throws IOException {
        var entry = new Entry(client.name(), client.address(), decision);
        var previous = new LinkedHashMap<>(entries);
        entries.remove(entry.key());
        if (entries.size() >= MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(entry.key(), entry);
        saveOrRestore(previous);
        LOG.info("Remote client {} ({}) remembered as {}", RemoteSessionServer.safe(entry.name()),
                RemoteSessionServer.safe(entry.address()), decision);
    }

    public void forget(Entry entry) throws IOException {
        var previous = new LinkedHashMap<>(entries);
        if (entries.remove(entry.key()) != null) {
            saveOrRestore(previous);
            LOG.info("Remote client {} ({}) forgotten", RemoteSessionServer.safe(entry.name()),
                    RemoteSessionServer.safe(entry.address()));
        }
    }

    private void saveOrRestore(Map<String, Entry> previous) throws IOException {
        try {
            save();
        } catch (IOException failure) {
            entries.clear();
            entries.putAll(previous);
            throw failure;
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Saved remote client list is not a regular file.");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Saved remote client list exceeds its size limit.");
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.strip().split("\t", -1);
            if (parts.length != 3) continue;
            try {
                var entry = new Entry(parts[1], parts[2], Decision.valueOf(parts[0]));
                entries.put(entry.key(), entry);
            } catch (IllegalArgumentException skipped) {
                LOG.debug("Skipped a malformed remote client list line");
                // Ignore malformed lines rather than refusing to host.
            }
        }
    }

    private void save() throws IOException {
        var text = new StringBuilder();
        for (var entry : new ArrayList<>(entries.values())) {
            text.append(entry.decision().name()).append('\t').append(entry.name()).append('\t')
                    .append(entry.address()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "clients-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
