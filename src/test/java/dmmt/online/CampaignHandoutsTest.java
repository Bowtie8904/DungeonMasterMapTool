package dmmt.online;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CampaignHandoutsTest {
    @TempDir Path temp;
    CampaignHandouts handouts;
    final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        handouts = new CampaignHandouts(temp, "camp1");
        handouts.addListener(events::add);
    }

    static byte[] image(String format) throws IOException {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test
    void addsImagesAndPersists() throws IOException {
        handouts.createFolder("", "Maps");
        var entry = handouts.addImage("Maps", "Cave", image("png"));
        assertEquals("png", entry.ext);
        assertTrue(Files.isRegularFile(handouts.open(entry.id, null, true)));

        var reloaded = new CampaignHandouts(temp, "camp1").list();
        assertEquals(List.of("Maps"), reloaded.folders());
        assertEquals("Cave", reloaded.files().get(0).name);
    }

    @Test
    void convertsBmpToPngAndRejectsNonImages() throws IOException {
        assertEquals("png", handouts.addImage("", "Bmp", image("bmp")).ext);
        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.addImage("", "Text", "hello".getBytes()));
        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.addImage("", "Empty", new byte[0]));
    }

    @Test
    void duplicateNamesGetSuffixAndRenameConflictsAreRejected() throws IOException {
        var a = handouts.addImage("", "Map", image("png"));
        var b = handouts.addImage("", "map", image("png"));
        assertEquals("map (2)", b.name);
        var e = assertThrows(CampaignHandouts.Rejected.class, () -> handouts.renameFile(b.id, "MAP"));
        assertEquals(409, e.status());
        handouts.renameFile(a.id, "Renamed");
        assertEquals("Renamed", handouts.find(a.id).name);
    }

    @Test
    void rejectsUnsafeNames() {
        for (String bad : new String[]{"", " ", "a/b", "..", "a\\b", "x:y", "a\u0001"})
            assertThrows(CampaignHandouts.Rejected.class, () -> handouts.createFolder("", bad), bad);
        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.createFolder("../x", "ok"));
    }

    @Test
    void movesAndRenamesFolders() throws IOException {
        handouts.createFolder("", "A");
        handouts.createFolder("", "B");
        handouts.createFolder("A", "Sub");
        var file = handouts.addImage("A/Sub", "Pic", image("png"));

        assertEquals("B/A", handouts.moveFolder("A", "B"));
        assertEquals("B/A/Sub", handouts.find(file.id).folder);
        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.moveFolder("B", "B/A/Sub"));

        assertEquals("B/Z", handouts.renameFolder("B/A", "Z"));
        assertEquals("B/Z/Sub", handouts.find(file.id).folder);

        handouts.moveFile(file.id, "");
        assertEquals("", handouts.find(file.id).folder);
    }

    @Test
    void copyGetsOwnBlobAndNoGrants() throws IOException {
        var file = handouts.addImage("", "Pic", image("png"));
        handouts.setAccess(List.of(file.id), true, Set.of());
        var copy = handouts.copyFile(file.id, "");
        assertEquals("Pic copy", copy.name);
        assertNotEquals(file.id, copy.id);
        assertFalse(copy.allPlayers);
        handouts.deleteFile(file.id);
        assertTrue(Files.isRegularFile(handouts.open(copy.id, null, true)));
    }

    @Test
    void playersOnlySeeGrantedFilesWithTheirFolders() throws IOException {
        handouts.createFolder("", "A");
        handouts.createFolder("A", "Deep");
        handouts.createFolder("", "Hidden");
        var shared = handouts.addImage("A/Deep", "Shared", image("png"));
        var secret = handouts.addImage("Hidden", "Secret", image("png"));
        var everyone = handouts.addImage("", "All", image("png"));
        handouts.setAccess(List.of(shared.id), false, Set.of("p1"));
        handouts.setAccess(List.of(everyone.id), true, Set.of());

        var forP1 = handouts.listFor("p1");
        assertEquals(List.of("A", "A/Deep"), forP1.folders());
        assertEquals(2, forP1.files().size());
        var forP2 = handouts.listFor("p2");
        assertEquals(1, forP2.files().size());
        assertTrue(forP2.folders().isEmpty());

        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.open(shared.id, "p2", false));
        assertThrows(CampaignHandouts.Rejected.class, () -> handouts.open(secret.id, "p1", false));
        assertDoesNotThrow(() -> handouts.open(shared.id, "p1", false));
    }

    @Test
    void showGrantsEveryoneAndNotifiesWithTheId() throws IOException {
        var file = handouts.addImage("", "Pic", image("png"));
        events.clear();
        handouts.show(file.id);
        assertTrue(handouts.find(file.id).allPlayers);
        assertEquals(List.of(file.id), events);
    }

    @Test
    void deletingFolderRemovesContents() throws IOException {
        handouts.createFolder("", "A");
        var file = handouts.addImage("A", "Pic", image("png"));
        Path blob = handouts.open(file.id, null, true);
        handouts.deleteFolder("A");
        assertTrue(handouts.list().files().isEmpty());
        assertFalse(Files.exists(blob));
    }

    @Test
    void rejectsInvalidCampaignId() {
        assertThrows(CampaignHandouts.Rejected.class, () -> new CampaignHandouts(temp, "../x"));
    }
}
