package dmmt.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CampaignNotesTest {
    @TempDir Path temp;

    @Test
    void privateTreesAndSharedNotePersistAcrossPlayersCampaignsAndRestarts() throws Exception {
        CampaignNotes notes = new CampaignNotes(temp);
        assertEquals(1, notes.list("campaign", "alice").size());
        var folder = notes.create("campaign", "alice", "private", "Sessions", true);
        var nested = notes.create("campaign", "alice", folder.path(), "Session 1", true);
        var note = notes.create("campaign", "alice", nested.path(), "Clues", false);
        var content = notes.read("campaign", "alice", note.path());
        notes.save("campaign", "alice", note.path(), "First clue\nSecond clue: \u00e9\n", content.revision());
        assertEquals("First clue\nSecond clue: \u00e9\n",
                Files.readString(temp.resolve("campaign").resolve("notes").resolve("players").resolve("alice")
                        .resolve("Sessions").resolve("Session 1").resolve("Clues.txt")));
        assertEquals(1, notes.list("campaign", "bob").size());
        assertEquals(404, assertThrows(CampaignNotes.Rejected.class,
                () -> notes.read("campaign", "bob", note.path())).status());
        var group = notes.read("campaign", "alice", CampaignNotes.GROUP);
        notes.save("campaign", "alice", group.path(), "Meet at the tavern", group.revision());
        CampaignNotes restarted = new CampaignNotes(temp);
        assertEquals("Meet at the tavern", restarted.read("campaign", "later-player", CampaignNotes.GROUP).text());
        assertEquals(4, restarted.list("campaign", "alice").size());
        assertEquals("", restarted.read("other-campaign", "alice", CampaignNotes.GROUP).text());
    }

    @Test
    void staleGroupAndPrivateSavesNeverOverwriteSavedText() throws Exception {
        CampaignNotes notes = new CampaignNotes(temp);
        var original = notes.read("c", "alice", CampaignNotes.GROUP);
        notes.save("c", "alice", original.path(), "Alice's text", original.revision());
        assertEquals(409, assertThrows(CampaignNotes.Rejected.class,
                () -> notes.save("c", "bob", original.path(), "Stale Bob draft", original.revision())).status());
        assertEquals("Alice's text", notes.read("c", "bob", original.path()).text());
        var note = notes.create("c", "alice", "private", "Personal", false);
        var personal = notes.read("c", "alice", note.path());
        notes.save("c", "alice", note.path(), "Other tab", personal.revision());
        assertThrows(CampaignNotes.Rejected.class, () -> notes.save("c", "alice", note.path(), "Old tab", personal.revision()));
    }

    @Test
    void foldersRenameAndDeleteWithContentsButGroupRemainsPermanent() throws Exception {
        CampaignNotes notes = new CampaignNotes(temp);
        notes.create("c", "p", "private", "Empty folder", true);
        notes.create("c", "p", "private/Empty folder", "Text", false);
        var renamed = notes.rename("c", "p", "private/Empty folder", "Session notes");
        assertTrue(renamed.folder());
        assertEquals("", notes.read("c", "p", "private/Session notes/Text.txt").text());
        notes.rename("c", "p", "private/Session notes/Text.txt", "Clues.txt");
        assertTrue(Files.isRegularFile(temp.resolve("c").resolve("notes").resolve("players").resolve("p")
                .resolve("Session notes").resolve("Clues.txt")));
        assertThrows(CampaignNotes.Rejected.class, () -> notes.rename("c", "p", CampaignNotes.GROUP, "Hidden"));
        assertThrows(CampaignNotes.Rejected.class, () -> notes.delete("c", "p", CampaignNotes.GROUP));
        notes.delete("c", "p", renamed.path());
        assertEquals(1, notes.list("c", "p").size());
        assertThrows(CampaignNotes.Rejected.class, () -> notes.read("c", "p", "private/Session notes/Clues.txt"));
    }

    @Test
    void traversalReservedNamesDuplicatesAndInvalidParentsAreRejected() throws Exception {
        CampaignNotes notes = new CampaignNotes(temp);
        for (String path : new String[]{"../bob", "private/../bob/Secret.txt", "private/..\\bob\\Secret.txt",
                "players/bob/Secret.txt", "private", "private//Secret.txt", "private/C:/Secret.txt", "private/A./x.txt"}) {
            assertThrows(CampaignNotes.Rejected.class, () -> notes.read("c", "alice", path), path);
        }
        for (String name : new String[]{"../outside", "A/B", "A\\B", "CON", "nul.txt", "COM1", "", " .", "A:", "A\u0000"}) {
            assertThrows(CampaignNotes.Rejected.class, () -> notes.create("c", "alice", "private", name, false), name);
        }
        notes.create("c", "alice", "private", "One", false);
        notes.create("c", "alice", "private", "Two", false);
        assertThrows(CampaignNotes.Rejected.class, () -> notes.create("c", "alice", "private", "One", false));
        assertThrows(CampaignNotes.Rejected.class, () -> notes.rename("c", "alice", "private/One.txt", "Two"));
        assertThrows(CampaignNotes.Rejected.class, () -> notes.create("c", "alice", "private/One.txt", "Child", false));
        assertThrows(CampaignNotes.Rejected.class, () -> notes.create("c", "alice", "private/missing", "Child", true));
    }

    @Test
    void textLimitCountsUtf8BytesAndFailedWritesKeepExistingContent() throws Exception {
        CampaignNotes notes = new CampaignNotes(temp);
        var initial = notes.read("c", "p", CampaignNotes.GROUP);
        String maximum = "\u00e9".repeat(CampaignNotes.MAX_TEXT_BYTES / 2);
        var saved = notes.save("c", "p", initial.path(), maximum, initial.revision());
        assertEquals(maximum, notes.read("c", "p", initial.path()).text());
        assertEquals(413, assertThrows(CampaignNotes.Rejected.class,
                () -> notes.save("c", "p", initial.path(), maximum + "!", saved.revision())).status());
        assertEquals(maximum, notes.read("c", "p", initial.path()).text());
    }
}
