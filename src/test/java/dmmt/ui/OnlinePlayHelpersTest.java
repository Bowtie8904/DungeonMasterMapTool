package dmmt.ui;

import dmmt.online.Campaign;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OnlinePlayHelpersTest {
    @Test
    void folderPathsAreNormalized() {
        assertEquals("", OnlinePlayPanel.normalize(null));
        assertEquals("Heroes/Party", OnlinePlayPanel.normalize(" /Heroes\\Party/ "));
        assertEquals("Heroes", OnlinePlayPanel.parentOf("Heroes/Party"));
        assertEquals("", OnlinePlayPanel.parentOf("Heroes"));
        assertEquals("Heroes/Party", OnlinePlayPanel.join("Heroes", "Party"));
        assertEquals("Party", OnlinePlayPanel.join("", "Party"));
    }

    @Test
    void passwordsMayBeEmptyOrShortButNeedConfirmation() {
        assertNull(OnlineDialogs.passwordProblem("", ""));
        assertNull(OnlineDialogs.passwordProblem("a", "a"));
        assertNull(OnlineDialogs.passwordProblem("abc", "abc"));
        assertNotNull(OnlineDialogs.passwordProblem("", "a"));
        assertNotNull(OnlineDialogs.passwordProblem("a", ""));
        assertNotNull(OnlineDialogs.passwordProblem(null, null));
        assertNotNull(OnlineDialogs.passwordProblem("secret", "secreT"));
        assertNull(OnlineDialogs.passwordProblem("secret", "secret"));
    }

    @Test
    void playerNamesRequireANonWhitespaceCharacterAndRemainUnique() {
        Campaign campaign = Campaign.builder().build();
        Campaign.Player player = Campaign.Player.builder().id("existing").name("Alice").build();
        campaign.getPlayers().add(player);
        var add = OnlinePlayPanel.playerNameProblem(campaign, null);
        assertNotNull(add.apply(""));
        assertNotNull(add.apply("   "));
        assertNotNull(add.apply(null));
        assertNotNull(add.apply(" alice "));
        assertNull(add.apply("a"));
        var rename = OnlinePlayPanel.playerNameProblem(campaign, "existing");
        assertNull(rename.apply("Alice"));
        assertNotNull(rename.apply(" "));
    }
}
