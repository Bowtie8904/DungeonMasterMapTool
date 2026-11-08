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
    void assignsEightDistinctPlayerColorsAndPreservesThemThroughReloadAndAccountChanges() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Colors");
        for (int i = 0; i < 8; i++) {
            campaign.getPlayers().add(Campaign.Player.builder().id("player" + i).name("Player " + i).build());
            store.save(campaign);
        }
        List<String> colors = campaign.getPlayers().stream().map(Campaign.Player::getColor).toList();
        assertEquals(Campaign.PLAYER_COLORS, colors);
        assertEquals(8, colors.stream().distinct().count());
        assertTrue(colors.stream().noneMatch(Campaign.DEFAULT_DM_COLOR::equals));
        assertEquals("#e53935", campaign.getDmColor());
        Campaign reopened = new CampaignStore(dir).load(campaign.getId());
        assertEquals(colors, reopened.getPlayers().stream().map(Campaign.Player::getColor).toList());
        reopened.getPlayers().add(Campaign.Player.builder().id("extra").name("Extra").build());
        store.save(reopened);
        assertEquals(colors.getFirst(), reopened.player("extra").getColor());
        reopened.getPlayers().removeIf(p -> p.getId().equals("player3"));
        reopened.player("player1").setName("Renamed");
        reopened.getPlayers().add(Campaign.Player.builder().id("replacement").name("Replacement").build());
        store.save(reopened);
        assertEquals(colors.get(3), reopened.player("replacement").getColor());
        assertEquals(colors.get(1), store.load(campaign.getId()).player("player1").getColor());
    }

    @Test
    void legacyColorsAreBackfilledOnDiskWithoutReplacingSavedPlayerOrDmColors() throws Exception {
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Legacy");
        Path file = dir.resolve(campaign.getId()).resolve("campaign.json");
        Files.writeString(file, """
                {"id":"%s","name":"Legacy","dmColor":"#123456","players":[
                  {"id":"new","name":"Missing"},{"id":"saved","name":"Saved","color":"#2979FF"},
                  {"id":"blank","name":"Blank","color":""}]}
                """.formatted(campaign.getId()));
        Campaign loaded = store.load(campaign.getId());
        assertEquals("#123456", loaded.getDmColor());
        assertEquals("#2979FF", loaded.player("saved").getColor());
        assertEquals("#00c853", loaded.player("new").getColor());
        assertEquals("#ffd600", loaded.player("blank").getColor());
        var disk = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
        assertEquals("#00c853", disk.path("players").get(0).path("color").asText());
        assertEquals(loaded, new CampaignStore(dir).load(campaign.getId()));
        Files.writeString(file, "{\"id\":\"" + campaign.getId() + "\",\"name\":\"Empty legacy\"}");
        assertEquals(Campaign.DEFAULT_DM_COLOR, store.load(campaign.getId()).getDmColor());
        assertEquals(Campaign.DEFAULT_DM_COLOR,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile()).path("dmColor").asText());
    }

    @Test
    void interruptedActorRenameRecoversCampaignAndSheetTogether() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Names");
        Campaign.Actor actor = Campaign.Actor.builder().id("hero").name("Before").build();
        campaign.getActors().add(actor);
        campaign.getActors().add(Campaign.Actor.builder().id("new").name("New").build());
        store.save(campaign);
        var transaction = mapper.createObjectNode();
        transaction.putObject("$actorName").put("id", "hero").put("name", "After");
        transaction.putObject("hero").put("name", "After").put("notes", "Keep");
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals("After", reopened.load(campaign.getId()).actor("hero").getName());
        assertEquals("After", mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("name").asText());
        assertEquals("Keep", mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("notes").asText());
        assertTrue(Files.notExists(journal));
        Campaign recovered = reopened.load(campaign.getId());
        reopened.renameActor(recovered, recovered.actor("new"), "Without sheet", null);
        assertEquals("Without sheet", reopened.load(campaign.getId()).actor("new").getName());
        assertNull(reopened.loadSheet(campaign.getId(), "new"));
    }

    @Test
    void fearRewardRecoveryJournalsCampaignFearAndClaimTogether() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Fear reward");
        campaign.setFear(5);
        store.save(campaign);
        var roll = mapper.createObjectNode().put("id", 1).put("ts", 10).put("rewardClaimed", false);
        store.appendHistoryChecked(campaign.getId(), roll);
        roll.put("rewardClaimed", true);
        var transaction = mapper.createObjectNode().put("$fear", 4);
        transaction.set("$roll", roll);
        var report = mapper.createObjectNode().put("id", 2).put("ts", 11)
                .put("text", "Removed 1 GM Fear instead of gaining Hope.");
        transaction.set("$message", report);
        transaction.set("hero", mapper.createObjectNode().put("featureRevision", 1));
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(4, reopened.load(campaign.getId()).getFear());
        assertEquals(List.of(roll, report), reopened.historyTail(campaign.getId(), 20));
        assertTrue(reopened.historyTail(campaign.getId(), 20).getFirst().path("rewardClaimed").asBoolean());
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("featureRevision").asInt());
        assertTrue(Files.notExists(journal));
        assertEquals(4, reopened.load(campaign.getId()).getFear());
    }

    @Test
    void sessionResourcesArePersistedLikeOtherResources() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Session resources");
        store.saveSheet(campaign.getId(), "hero", "{\"resources\":{\"tide\":3,\"favor\":4}}");
        var disk = mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero"));
        assertEquals(3, disk.path("resources").path("tide").asInt());
        assertEquals(4, disk.path("resources").path("favor").asInt());
        var hero = (com.fasterxml.jackson.databind.node.ObjectNode) disk;
        ((com.fasterxml.jackson.databind.node.ObjectNode) hero.path("resources")).put("tide", 2);
        store.saveSheets(campaign.getId(), java.util.Map.of("hero", hero));
        assertEquals(2, mapper.readTree(new CampaignStore(dir).loadSheet(campaign.getId(), "hero")).path("resources").path("tide").asInt());
    }

    @Test
    void newDeathMoveMessageAndScarsAreJournaledWithoutExistingHistoryAndReplayIsIdempotent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Death moves");
        var sheet = mapper.createObjectNode().put("scars", 1);
        var message = mapper.createObjectNode().put("id", 1).put("ts", 10).put("deathMove", "avoid");
        store.saveSheetsAndMessage(campaign.getId(), java.util.Map.of("hero", sheet), message);
        assertEquals(List.of(message), store.historyTail(campaign.getId(), 20));
        assertEquals(1, mapper.readTree(store.loadSheet(campaign.getId(), "hero")).path("scars").asInt());
        var transaction = mapper.createObjectNode();
        transaction.set("hero", sheet);
        transaction.set("$message", message);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(List.of(message), reopened.historyTail(campaign.getId(), 20));
        assertTrue(Files.notExists(journal));
        var second = mapper.createObjectNode().put("id", 2).put("ts", 11).put("deathMove", "risk");
        reopened.saveSheetsAndMessage(campaign.getId(), java.util.Map.of("hero", sheet), second);
        assertEquals(List.of(message, second), reopened.historyTail(campaign.getId(), 20));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> reopened.replaceHistoryEntry(campaign.getId(), 3, 12, second));
    }

    @Test
    void multipleDeathReportsAndRecoveryClaimReplayWithoutDuplicates() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CampaignStore store = new CampaignStore(dir);
        Campaign campaign = store.create("Atomic death results");
        var original = mapper.createObjectNode().put("id", 1).put("ts", 10).put("type", "roll");
        original.putObject("deathResult").put("claimed", false);
        store.appendHistoryChecked(campaign.getId(), original);
        var claimed = original.deepCopy();
        claimed.withObject("/deathResult").put("claimed", true);
        var expiration = mapper.createObjectNode().put("id", 2).put("ts", 11).put("text", "Spectral Form ended");
        var ward = mapper.createObjectNode().put("id", 3).put("ts", 12).put("text", "Life Ward consumed");
        var sheet = mapper.createObjectNode().put("permanentHp", 1);
        store.saveSheetsAndResults(campaign.getId(), java.util.Map.of("hero", sheet), claimed, List.of(expiration, ward));
        assertEquals(List.of(claimed, expiration, ward), store.historyTail(campaign.getId(), 20));
        var transaction = mapper.createObjectNode();
        transaction.set("hero", sheet);
        transaction.set("$roll", claimed);
        transaction.putArray("$messages").add(expiration).add(ward);
        Path journal = dir.resolve(campaign.getId()).resolve("sheet-transfer.json");
        Files.writeString(journal, transaction.toString());
        CampaignStore reopened = new CampaignStore(dir);
        assertEquals(List.of(claimed, expiration, ward), reopened.historyTail(campaign.getId(), 20));
        assertEquals(1, mapper.readTree(reopened.loadSheet(campaign.getId(), "hero")).path("permanentHp").asInt());
        assertTrue(Files.notExists(journal));
    }

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
