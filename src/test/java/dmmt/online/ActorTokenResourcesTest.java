package dmmt.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ActorTokenResourcesTest {
    @TempDir Path temp;

    @Test
    void readerReusesUnchangedDerivedValuesAndInvalidatesWritesAndExternalEdits() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("campaign").system("daggerheart").build();
        campaign.getActors().add(Campaign.Actor.builder().id("hero").testingCharacter(true).name("Hero").build());
        var reader = new ActorTokenResources.Reader();
        String original = "{\"hp\":{\"slots\":6,\"on\":1},\"stress\":{\"slots\":6,\"on\":0}}";
        store.saveSheet("campaign", "hero", original);
        var first = reader.load(campaign, store).get("hero");
        assertSame(first, reader.load(campaign, store).get("hero"));
        store.saveSheet("campaign", "hero", original.replace("\"on\":1", "\"on\":2"));
        assertEquals(2, reader.load(campaign, store).get("hero").hp());
        java.nio.file.Files.writeString(temp.resolve("campaign").resolve("sheets").resolve("hero.json"),
                original.replace("\"on\":1", "\"on\":3"));
        assertEquals(3, reader.load(campaign, store).get("hero").hp());
        campaign.getActors().getFirst().setTestingCharacter(false);
        assertTrue(reader.load(campaign, store).isEmpty());
        campaign.getActors().getFirst().setTestingCharacter(true);
        assertEquals(3, reader.load(campaign, store).get("hero").hp());
        campaign.setSystem("other");
        assertTrue(reader.load(campaign, store).isEmpty());
        assertTrue(reader.load(null, store).isEmpty());
    }

    @Test
    void readerDoesNotReuseAnotherCampaignOrInvalidTrack() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("first").system("daggerheart").build();
        campaign.getActors().add(Campaign.Actor.builder().id("hero").testingCharacter(true).name("Hero").build());
        var reader = new ActorTokenResources.Reader();
        store.saveSheet("first", "hero", "{\"hp\":{\"slots\":6,\"on\":1},\"stress\":{\"slots\":6,\"on\":0}}");
        assertEquals(1, reader.load(campaign, store).get("hero").hp());
        campaign.setId("second");
        assertTrue(reader.load(campaign, store).isEmpty());
        store.saveSheet("second", "hero", "{\"hp\":{\"slots\":6,\"on\":\"bad\"},\"stress\":{\"slots\":6,\"on\":0}}");
        assertThrows(IOException.class, () -> reader.load(campaign, store));
    }

    @Test
    void loadsSavedPlayerTracksAndRefreshesWithoutIncludingOtherActors() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("campaign").system("daggerheart").build();
        campaign.getPlayers().add(Campaign.Player.builder().id("player").build());
        for (String id : new String[] {"hero", "disabled", "testing", "testing-unowned", "unowned", "missing", "unfinished"}) {
            var actor = Campaign.Actor.builder().id(id).ownerId("player").build();
            actor.setDisabled(id.equals("disabled"));
            actor.setTestingCharacter(id.startsWith("testing"));
            if (id.equals("unowned") || id.equals("testing-unowned")) actor.setOwnerId(null);
            campaign.getActors().add(actor);
            if (!id.equals("missing")) store.saveSheet("campaign", id,
                    id.equals("unfinished") ? "{\"hp\":{\"slots\":0,\"on\":0},\"stress\":{\"slots\":6,\"on\":0}}"
                            : "{\"hp\":{\"slots\":6,\"on\":2},\"stress\":{\"slots\":8,\"on\":3}}");
        }
        var values = ActorTokenResources.load(campaign, store);
        assertEquals(java.util.Set.of("hero", "disabled", "testing", "testing-unowned"), values.keySet());
        assertEquals(new ActorTokenResources(2, 6, 3, 8, null, null), values.get("hero"));
        assertEquals(values.get("hero"), values.get("testing"));
        assertEquals(values.get("hero"), values.get("testing-unowned"));
        store.saveSheet("campaign", "hero", "{\"hp\":{\"slots\":7,\"on\":7},\"stress\":{\"slots\":0,\"on\":0}}");
        assertEquals(new ActorTokenResources(7, 7, 0, 0, null, null), ActorTokenResources.load(campaign, store).get("hero"));
        store.saveSheet("campaign", "hero", "{\"hp\":{\"slots\":7,\"on\":0},\"stress\":{\"slots\":6,\"on\":0}}");
        assertEquals(new ActorTokenResources(0, 7, 0, 6, null, null), ActorTokenResources.load(campaign, store).get("hero"));
        campaign.setSystem("other");
        assertTrue(ActorTokenResources.load(campaign, store).isEmpty());
        assertTrue(ActorTokenResources.load(null, store).isEmpty());
    }

    @Test
    void armorCountsUnmarkedSlotsAndHopeCountsCurrentPoints() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("campaign").system("daggerheart").build();
        campaign.getActors().add(Campaign.Actor.builder().id("hero").testingCharacter(true).name("Hero").build());
        String tracks = "{\"hp\":{\"slots\":6,\"on\":0},\"stress\":{\"slots\":6,\"on\":0},";
        store.saveSheet("campaign", "hero", tracks + "\"armorTrack\":{\"slots\":3,\"on\":1},\"hope\":{\"slots\":6,\"on\":4}}");
        assertEquals(new ActorTokenResources(0, 6, 0, 6, 2, 4), ActorTokenResources.load(campaign, store).get("hero"));
        store.saveSheet("campaign", "hero", tracks + "\"armorTrack\":{\"slots\":3,\"on\":3},\"hope\":{\"slots\":6,\"on\":0}}");
        assertEquals(new ActorTokenResources(0, 6, 0, 6, 0, 0), ActorTokenResources.load(campaign, store).get("hero"));
        store.saveSheet("campaign", "hero", tracks + "\"armorTrack\":{\"slots\":2,\"on\":4},\"hope\":{\"slots\":6,\"on\":6}}");
        assertEquals(new ActorTokenResources(0, 6, 0, 6, 0, 6), ActorTokenResources.load(campaign, store).get("hero"));
        store.saveSheet("campaign", "hero", tracks + "\"armorTrack\":{\"slots\":3,\"on\":1},\"hope\":{\"on\":\"bad\"}}");
        assertThrows(IOException.class, () -> ActorTokenResources.load(campaign, store));
    }

    @Test
    void rejectsInvalidTracksInsteadOfShowingInventedValues() throws Exception {
        var store = new CampaignStore(temp);
        var campaign = Campaign.builder().id("campaign").system("daggerheart").build();
        campaign.getPlayers().add(Campaign.Player.builder().id("player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("hero").ownerId("player").name("Hero").build());
        store.saveSheet("campaign", "hero", "{\"hp\":{\"slots\":6,\"on\":\"bad\"},\"stress\":{\"slots\":6,\"on\":0}}");
        assertThrows(IOException.class, () -> ActorTokenResources.load(campaign, store));
    }
}
