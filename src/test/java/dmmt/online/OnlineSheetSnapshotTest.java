package dmmt.online;

import dmmt.model.DmProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OnlineSheetSnapshotTest {
    @TempDir Path temp;

    @Test
    void cachedConditionsRemainIndependentOfTokensAndFollowSavesAndExternalEdits() throws Exception {
        var controller = new OnlineSessionController(temp, null);
        var campaign = Campaign.builder().id("campaign").build();
        campaign.getActors().add(Campaign.Actor.builder().id("hero").build());
        var token = DmProject.TokenInstance.builder().id("token").actorId("hero").campaignId("campaign").build();
        String hidden = "{\"effects\":[{\"name\":\"Hidden\",\"onlineCondition\":{\"id\":\"hidden\"}}]}";
        controller.store().saveSheet("campaign", "hero", hidden);
        controller.inheritActorConditions(campaign, token);
        assertEquals("Hidden", token.getConditions().getFirst().getName());
        token.getConditions().getFirst().setName("Changed token");
        token.getConditions().clear();
        controller.inheritActorConditions(campaign, token);
        assertEquals("Hidden", token.getConditions().getFirst().getName());
        controller.store().saveSheet("campaign", "hero", hidden.replace("Hidden", "Restrained").replace("hidden", "restrained"));
        controller.inheritActorConditions(campaign, token);
        assertEquals("Restrained", token.getConditions().getFirst().getName());
        Path file = temp.resolve("campaign").resolve("sheets").resolve("hero.json");
        Files.writeString(file, hidden);
        controller.inheritActorConditions(campaign, token);
        assertEquals("Hidden", token.getConditions().getFirst().getName());
        Files.writeString(file, "[]");
        assertThrows(IOException.class, () -> controller.inheritActorConditions(campaign, token));
    }

    @Test
    void snapshotsDoNotLeakBetweenCampaigns() throws Exception {
        var controller = new OnlineSessionController(temp, null);
        var first = Campaign.builder().id("first").build();
        var second = Campaign.builder().id("second").build();
        first.getActors().add(Campaign.Actor.builder().id("hero").build());
        second.getActors().add(Campaign.Actor.builder().id("hero").build());
        var token = DmProject.TokenInstance.builder().id("token").actorId("hero").campaignId("first").build();
        controller.store().saveSheet("first", "hero", "{\"effects\":[{\"name\":\"Hidden\",\"onlineCondition\":{\"id\":\"hidden\"}}]}");
        controller.store().saveSheet("second", "hero", "{\"effects\":[]}");
        controller.inheritActorConditions(first, token);
        assertEquals(1, token.getConditions().size());
        token.setCampaignId("second");
        controller.inheritActorConditions(second, token);
        assertTrue(token.getConditions().isEmpty());
    }
}
