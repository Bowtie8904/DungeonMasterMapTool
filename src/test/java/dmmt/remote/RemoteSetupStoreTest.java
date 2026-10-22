package dmmt.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSetupStoreTest {
    @TempDir Path directory;

    @Test
    void missingSetupIsLazyAndRoundTripContainsOnlyTheAllowedNonSecretFields() throws Exception {
        Path local = directory.resolve("not-created");
        var store = new RemoteSetupStore(local);
        assertNull(store.load());
        assertFalse(Files.exists(local), "Reading absent setup must not initialize remote storage.");
        var setup = new RemoteSetupStore.Setup(new RemoteSetupStore.Host("Table", "192.168.1.8", 7072, 7073),
                new RemoteSetupStore.Client("desktop.local", "Table", "Remote DM", 7172, 7173));
        store.save(setup);
        assertEquals(setup, new RemoteSetupStore(local).load());
        var fields = new java.util.Properties();
        try (var reader = Files.newBufferedReader(local.resolve("setup.properties"))) { fields.load(reader); }
        assertEquals(Set.of("version", "host.name", "host.address", "host.control", "host.assets", "client.host",
                "client.session", "client.name", "client.control", "client.assets"), fields.stringPropertyNames());
        try (var files = Files.list(local)) {
            assertEquals(java.util.List.of("setup.properties"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void hostOnlyAndClientOnlyAreValidAndReplacementDoesNotKeepObsoleteFields() throws Exception {
        var store = new RemoteSetupStore(directory);
        var host = new RemoteSetupStore.Setup(new RemoteSetupStore.Host("Table", "::1", 1, 65535), null);
        store.save(host);
        assertEquals(host, store.load());
        var client = new RemoteSetupStore.Setup(null,
                new RemoteSetupStore.Client("192.168.1.8", "Table", "DM", 7072, 7073));
        store.save(client);
        assertEquals(client, store.load());
        assertFalse(Files.readString(directory.resolve("setup.properties")).contains("host.address"));
    }

    @Test
    void invalidUnknownAndOversizedFilesAreExplicitErrorsNotSilentDefaults() throws Exception {
        var store = new RemoteSetupStore(directory);
        Path file = directory.resolve("setup.properties");
        for (String invalid : java.util.List.of("version=2", "version=1", "version=1\nfingerprint=abc",
                "version=1\nhost.name=Only a name", "version=1\nclient.host=host\nclient.session=Table\nclient.name=DM"
                        + "\nclient.control=7072\nclient.assets=7072", "x".repeat(8193))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, store::load, invalid);
        }
        Files.write(file, new byte[]{(byte) 0xff});
        assertThrows(IOException.class, store::load);
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void failedReplacementDoesNotLeaveTemporaryFilesAndValidationPrecedesDiskWork() throws Exception {
        var store = new RemoteSetupStore(directory);
        Path occupied = Files.createDirectory(directory.resolve("setup.properties"));
        Files.writeString(occupied.resolve("retain.txt"), "keep");
        assertThrows(IOException.class, () -> store.save(new RemoteSetupStore.Setup(
                new RemoteSetupStore.Host("Table", "127.0.0.1", 7072, 7073), null)));
        assertEquals("keep", Files.readString(occupied.resolve("retain.txt")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        assertThrows(IllegalArgumentException.class, () -> new RemoteSetupStore.Host("Table", "x\nhost", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSetupStore.Host("Table", "host", 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSetupStore.Client("host", "Table", "DM", 1, 65536));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSetupStore.Setup(null, null));
    }
}
