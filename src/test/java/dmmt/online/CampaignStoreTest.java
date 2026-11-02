package dmmt.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignStoreTest {
    @TempDir
    Path dir;

    @Test
    void createStoresSystemAndListsSummaries() throws IOException {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("  Frosthaven  ", "Daggerheart");
        campaign.getActorFolders().add("Heroes/Party");
        campaign.getActors().add(Campaign.Actor.builder().name("Mara").folder("Heroes/Party").build());
        store.save(campaign);

        Campaign loaded = store.load(campaign.getId());
        assertEquals("Frosthaven", loaded.getName());
        assertEquals("Daggerheart", loaded.getSystem());
        assertEquals(List.of("Heroes/Party"), loaded.getActorFolders());
        assertEquals("Heroes/Party", loaded.getActors().getFirst().getFolder());

        List<CampaignStore.Summary> summaries = store.list();
        assertEquals(1, summaries.size());
        assertEquals("Daggerheart", summaries.getFirst().system());
        assertEquals(1, summaries.getFirst().actors());
    }

    @Test
    void blankNameAndSystemFallBackToDefaults() throws IOException {
        Campaign campaign = new CampaignStore(dir).create(" ", null);
        assertEquals("New campaign", campaign.getName());
        assertEquals(Campaign.DEFAULT_SYSTEM, campaign.getSystem());
    }

    @Test
    void olderCampaignFilesWithoutSystemOrFoldersStillLoad() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("old"));
        Files.writeString(folder.resolve("campaign.json"), """
                {"id":"old","name":"Legacy","players":[],"actors":[{"id":"a1","name":"Rook"}]}
                """, StandardCharsets.UTF_8);
        CampaignStore store = new CampaignStore(dir);

        Campaign loaded = store.load("old");
        assertNotNull(loaded.getActorFolders());
        assertTrue(loaded.getActorFolders().isEmpty());
        assertNull(loaded.getActors().getFirst().getFolder());
        assertEquals(Campaign.DEFAULT_SYSTEM, store.list().getFirst().system());
    }
}
