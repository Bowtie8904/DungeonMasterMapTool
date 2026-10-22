package dmmt.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDirectoriesTest {
    @TempDir Path directory;

    @Test
    void rootsAndPagedSortedDirectoriesNeverExposeFilesOrCreateFolders() throws Exception {
        for (int index = 0; index < 130; index++) Files.createDirectory(directory.resolve("folder-%03d".formatted(index)));
        Files.writeString(directory.resolve("private.txt"), "not directory metadata");
        var roots = RemoteDirectories.list(new RemoteDirectories.Query(null, 0));
        assertNull(roots.error());
        assertNull(roots.path());
        assertFalse(roots.entries().isEmpty());
        var first = RemoteDirectories.list(new RemoteDirectories.Query(directory.toString(), 0));
        assertNull(first.error());
        assertEquals(128, first.entries().size());
        assertEquals("folder-000", first.entries().getFirst().name());
        assertEquals(directory.toRealPath().toString(), first.path());
        assertTrue(first.more());
        var second = RemoteDirectories.list(new RemoteDirectories.Query(directory.toString(), 128));
        assertEquals(List.of("folder-128", "folder-129"), second.entries().stream().map(RemoteDirectories.Entry::name).toList());
        assertFalse(second.more());
        assertEquals(directory.toRealPath().getParent().toString(), second.parent());
        var missing = directory.resolve("not-created");
        assertNotNull(RemoteDirectories.list(new RemoteDirectories.Query(missing.toString(), 0)).error());
        assertFalse(Files.exists(missing));
        assertNotNull(RemoteDirectories.list(new RemoteDirectories.Query("relative", 0)).error());
        assertNotNull(RemoteDirectories.list(new RemoteDirectories.Query(directory.resolve("private.txt").toString(), 0)).error());
    }

    @Test
    void excessiveChildrenReturnExplicitLimitInsteadOfAFalseCompleteListing() throws Exception {
        for (int index = 0; index < 4097; index++) Files.createDirectory(directory.resolve("d" + index));
        var result = RemoteDirectories.list(new RemoteDirectories.Query(directory.toString(), 0));
        assertNotNull(result.error());
        assertTrue(result.error().contains("limit"));
        assertTrue(result.entries().isEmpty());
        assertFalse(result.more());
    }

    @Test
    void queryAndWireValidationRejectMissingPrimitivesAndUnboundedPages() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new RemoteDirectories.Query(null, -1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDirectories.Query(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDirectories.Query(null, 4224));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDirectories.Query("x".repeat(4097), 0));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDirectories.Entry("line\nbreak", "C:\\"));
        var query = new RemoteDirectories.Query(directory.toString(), 128);
        assertEquals(query, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.DIRECTORY_QUERY, query),
                RemoteDirectories.Query.class));
        var message = RemoteWire.message(RemoteWire.Type.DIRECTORY_QUERY, query);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) message.payload();
        payload.remove("offset");
        assertThrows(java.io.IOException.class, () -> RemoteWire.payload(
                new RemoteWire.Message(message.id(), message.type(), payload), RemoteDirectories.Query.class));
        var listing = RemoteDirectories.list(new RemoteDirectories.Query(directory.toString(), 0));
        assertEquals(listing, RemoteWire.payload(RemoteWire.message(RemoteWire.Type.DIRECTORY_LIST, listing),
                RemoteDirectories.Listing.class));
    }
}
