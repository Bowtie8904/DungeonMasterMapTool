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
    void interruptedRewardReplaysSheetAndHistoryTogetherBeforeHistoryReads() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Rewards");
        var roll = mapper.createObjectNode().put("id", 1).put("ts", 10).put("rewardClaimed", false);
        store.appendHistoryChecked(campaign.getId(), roll);
        store.saveSheet(campaign.getId(), "hero", "{\"hope\":{\"on\":0}}");
        roll.put("rewardClaimed", true);
        var transaction = mapper.createObjectNode();
        transaction.set("hero", mapper.readTree("{\"hope\":{\"on\":1}}"));
        transaction.set("$roll", roll);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertTrue(reopened.historyTail(campaign.getId(), 20).getFirst().path("rewardClaimed").asBoolean());
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt());
        assertTrue(Files.notExists(journal));
        assertEquals(1, mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt());
    }

    @Test
    void deletingHistoryPersistsAndDoesNotDeleteReusedLegacyIds() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Chat");
        var first = mapper.createObjectNode().put("id", 1).put("ts", 10).put("text", "older session");
        var second = mapper.createObjectNode().put("id", 1).put("ts", 20).put("text", "newer session");
        store.appendHistoryChecked(campaign.getId(), first);
        store.appendHistoryChecked(campaign.getId(), second);
        store.deleteHistoryEntry(campaign.getId(), 1, 20);
        assertEquals(List.of(first), new CampaignStore(dir).historyTail(campaign.getId(), 100));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> store.deleteHistoryEntry(campaign.getId(), 1, 20));
        assertEquals(List.of(first), store.historyTail(campaign.getId(), 100));
    }

    @Test
    void damagedHistoryReportsDeletionFailureWithoutChangingTheFile() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Chat");
        Path history = dir.resolve(campaign.getId()).resolve("history.jsonl");
        String original = "{\"id\":1,\"ts\":10}\nnot json\n";
        Files.writeString(history, original);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> store.deleteHistoryEntry(campaign.getId(), 1, 10));
        assertEquals(original, Files.readString(history));
    }

    @Test
    void interruptedTransferReplaysBothSheetsBeforeReadsAndSaves() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Transfer");
        store.saveSheet(campaign.getId(), "source", "{\"qty\":0}");
        store.saveSheet(campaign.getId(), "target", "{\"qty\":0}");
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, "{\"source\":{\"qty\":0},\"target\":{\"qty\":1}}");
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals("{\"qty\":1}", reopened.loadSheet(campaign.getId(), "target"));
        assertEquals("{\"qty\":0}", reopened.loadSheet(campaign.getId(), "source"));
        assertTrue(Files.notExists(journal));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        reopened.transferSheets(campaign.getId(), "source", mapper.createObjectNode().put("qty", 1),
                "target", mapper.createObjectNode().put("qty", 0));
        assertEquals("{\"qty\":1}", reopened.loadSheet(campaign.getId(), "source"));
        assertEquals("{\"qty\":0}", reopened.loadSheet(campaign.getId(), "target"));
    }

    @Test
    void createStoresSystemAndListsSummaries() throws IOException {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("  Frosthaven  ", "Daggerheart");
        campaign.getActorFolders().add("Heroes/Party");
        campaign.getActors().add(Campaign.Actor.builder().name("Mara").folder("Heroes/Party").build());
        campaign.getActors().getFirst().setTestingCharacter(true);
        store.save(campaign);

        Campaign loaded = store.load(campaign.getId());
        assertEquals("Frosthaven", loaded.getName());
        assertEquals("Daggerheart", loaded.getSystem());
        assertEquals(List.of("Heroes/Party"), loaded.getActorFolders());
        assertEquals("Heroes/Party", loaded.getActors().getFirst().getFolder());
        assertTrue(loaded.getActors().getFirst().isTestingCharacter());

        List<CampaignStore.Summary> summaries = store.list();
        assertEquals(1, summaries.size());
        assertEquals("Daggerheart", summaries.getFirst().system());
        assertEquals(1, summaries.getFirst().actors());
    }

    @Test
    void fearIsClampedAndPersisted() throws IOException {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Fear", null);
        assertEquals(0, campaign.getFear());
        campaign.setFear(99);
        assertEquals(Campaign.MAX_FEAR, campaign.getFear());
        campaign.setFear(-3);
        assertEquals(0, campaign.getFear());
        campaign.setFear(7);
        store.save(campaign);
        assertEquals(7, store.load(campaign.getId()).getFear());
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
                {"id":"old","name":"Legacy","players":[{"id":"p1","name":"Alice"}],"actors":[{"id":"a1","name":"Rook"}]}
                """, StandardCharsets.UTF_8);
        CampaignStore store = new CampaignStore(dir);

        Campaign loaded = store.load("old");
        assertEquals("", loaded.player("p1").getChatIdentity());
        assertNotNull(loaded.getActorFolders());
        assertTrue(loaded.getActorFolders().isEmpty());
        assertNull(loaded.getActors().getFirst().getFolder());
        org.junit.jupiter.api.Assertions.assertFalse(loaded.getActors().getFirst().isTestingCharacter());
        assertEquals(Campaign.DEFAULT_SYSTEM, store.list().getFirst().system());
    }
}
