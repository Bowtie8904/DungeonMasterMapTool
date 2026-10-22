package dmmt.remote;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;

/** Machine-local configuration only. Deliberately has no identity, permission or session fields. */
public final class RemoteSetupStore {
    private static final dmmt.log.Log LOG = dmmt.log.Log.of(RemoteSetupStore.class);
    private static final int MAX_BYTES = 8192;
    private static final Set<String> KEYS = Set.of("version", "host.name", "host.address", "host.control", "host.assets",
            "client.host", "client.session", "client.name", "client.control", "client.assets");
    private final Path file;

    public record Host(String name, String address, int controlPort, int assetPort) {
        public Host {
            name = new RemoteSessionAccess.Client(name, "host").name();
            address = validateAddress(address);
            ports(controlPort, assetPort);
        }
    }

    public record Client(String host, String sessionName, String name, int controlPort, int assetPort) {
        public Client {
            host = validateAddress(host);
            sessionName = new RemoteSessionAccess.Client(sessionName, "host").name();
            name = new RemoteSessionAccess.Client(name, "client").name();
            ports(controlPort, assetPort);
        }
    }

    public record Setup(Host host, Client client) {
        public Setup {
            if (host == null && client == null) throw new IllegalArgumentException("Saved setup is empty.");
        }
    }

    public RemoteSetupStore(Path directory) {
        file = directory.toAbsolutePath().normalize().resolve("setup.properties");
    }

    /** Caller serializes all reads/writes off the UI thread. */
    public Setup load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Saved remote setup is not a regular file.");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Saved remote setup exceeds its size limit.");
        var values = new Properties();
        try {
            values.load(new StringReader(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()));
            if (!KEYS.containsAll(values.stringPropertyNames()) || !"1".equals(values.getProperty("version"))) {
                throw new IllegalArgumentException("Unknown saved setup fields or version.");
            }
            Host host = hasSection(values, "host.") ? new Host(required(values, "host.name"),
                    required(values, "host.address"), port(values, "host.control"), port(values, "host.assets")) : null;
            Client client = hasSection(values, "client.") ? new Client(required(values, "client.host"),
                    required(values, "client.session"), required(values, "client.name"),
                    port(values, "client.control"), port(values, "client.assets")) : null;
            LOG.info("Loaded saved remote setup from {} (host={}, client={})", file, host != null, client != null);
            return new Setup(host, client);
        } catch (IllegalArgumentException invalid) {
            LOG.warn("Saved remote setup {} is invalid", file, invalid);
            throw new IOException("Saved remote setup is invalid.", invalid);
        }
    }

    public void save(Setup setup) throws IOException {
        var values = new Properties();
        values.setProperty("version", "1");
        if (setup.host() != null) {
            var host = setup.host();
            values.setProperty("host.name", host.name());
            values.setProperty("host.address", host.address());
            values.setProperty("host.control", Integer.toString(host.controlPort()));
            values.setProperty("host.assets", Integer.toString(host.assetPort()));
        }
        if (setup.client() != null) {
            var client = setup.client();
            values.setProperty("client.host", client.host());
            values.setProperty("client.session", client.sessionName());
            values.setProperty("client.name", client.name());
            values.setProperty("client.control", Integer.toString(client.controlPort()));
            values.setProperty("client.assets", Integer.toString(client.assetPort()));
        }
        var text = new StringWriter();
        values.store(text, "DMMT non-secret local remote setup");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Remote setup exceeds its size limit.");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "setup-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                LOG.debug("Atomic move unsupported; falling back to a plain replace for {}", file);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        LOG.info("Saved remote setup to {} (host={}, client={})", file, setup.host() != null, setup.client() != null);
    }

    private static boolean hasSection(Properties values, String prefix) {
        return values.stringPropertyNames().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing saved setup field: " + key);
        return value;
    }

    private static int port(Properties values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static void ports(int control, int assets) {
        if (control < 1 || control > 65535 || assets < 1 || assets > 65535 || control == assets) {
            throw new IllegalArgumentException("Choose distinct listening ports from 1 to 65535.");
        }
    }

    private static String validateAddress(String value) {
        if (value == null || value.isBlank() || value.length() > 253
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A bounded host/interface address is required.");
        }
        return value.strip();
    }
}
