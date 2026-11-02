package dmmt.ui;

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
    void passwordsNeedMinimumLengthAndConfirmation() {
        assertNotNull(OnlineDialogs.passwordProblem("abc", "abc"));
        assertNotNull(OnlineDialogs.passwordProblem("secret", "secreT"));
        assertNull(OnlineDialogs.passwordProblem("secret", "secret"));
    }
}
