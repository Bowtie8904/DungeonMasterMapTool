package dmmt.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatCardsTest {
    @Test
    void rendersMarkdownAsStyledNativeTextWithListsAndLineBreaks() {
        var block = ChatCards.markdown("**Bold** and *italic*\n\n- ***Both***", "chat-shared-description");
        assertEquals(3, block.getChildren().size());
        var first = (javafx.scene.text.TextFlow) block.getChildren().get(0);
        var bold = (javafx.scene.text.Text) first.getChildren().get(0);
        assertEquals("Bold", bold.getText());
        assertEquals(true, bold.getStyleClass().contains("chat-markdown-bold"));
        var italic = (javafx.scene.text.Text) first.getChildren().get(2);
        assertEquals("italic", italic.getText());
        assertEquals(true, italic.getStyleClass().contains("chat-markdown-italic"));
        var list = (javafx.scene.text.TextFlow) block.getChildren().get(2);
        assertEquals("\u2022 ", ((javafx.scene.text.Text) list.getChildren().get(0)).getText());
        var both = (javafx.scene.text.Text) list.getChildren().get(1);
        assertEquals("Both", both.getText());
        assertEquals(true, both.getStyleClass().containsAll(List.of("chat-markdown-bold", "chat-markdown-italic")));
    }

    @Test
    void parsesMarkdownWithoutLeakingMarkersAndRetainsNestedEmphasis() {
        assertEquals(List.of(
                new ChatCards.MarkdownRun("Bold", true, false),
                new ChatCards.MarkdownRun(" and ", false, false),
                new ChatCards.MarkdownRun("italic", false, true),
                new ChatCards.MarkdownRun(" and ", false, false),
                new ChatCards.MarkdownRun("both", true, true)),
                ChatCards.markdownRuns("**Bold** and _italic_ and ***both***"));
        assertEquals(List.of(new ChatCards.MarkdownRun("Bold ", true, false),
                        new ChatCards.MarkdownRun("italic", true, true)),
                ChatCards.markdownRuns("**Bold *italic***"));
        assertEquals(List.of(new ChatCards.MarkdownRun("unmatched ** and file_name", false, false)),
                ChatCards.markdownRuns("unmatched ** and file_name"));
    }

    @Test
    void keepHighestDropsLowestDice() {
        assertArrayEquals(new boolean[]{true, false, true}, ChatCards.keptRolls("3d20kh2", List.of(15, 3, 9)));
    }

    @Test
    void parsesRestReport() {
        var report = ChatCards.parseRestReport("\uD83C\uDFD5\uFE0F LONG REST\nParty\n\u2022 Healed 2 HP");
        assertNotNull(report);
        assertEquals(true, report.longRest());
        assertEquals(List.of("Healed 2 HP"), report.lines());
        assertNull(ChatCards.parseRestReport("just chatting"));
    }

    @Test
    void parsesDamageReport() {
        var report = ChatCards.parseDamageReport("DAMAGE TAKEN\nAria\n9 physical damage \u2192 6 \u00B7 Major \u00B7 2 HP marked\n\u2022 Armor used");
        assertNotNull(report);
        assertEquals(6, report.damage());
        assertEquals(2, report.hp());
        assertEquals("Major", report.severity());
    }

    @Test
    void parsesActionReport() {
        var report = ChatCards.parseActionReport("ACTION USED\nMinor Health Potion - Drink\nJitta\n\u2022 cleared 2 HP (1d4)");
        assertNotNull(report);
        assertEquals("Minor Health Potion - Drink", report.title());
        assertEquals("Jitta", report.character());
        assertEquals(List.of("cleared 2 HP (1d4)"), report.lines());
        assertNull(ChatCards.parseActionReport("Jitta: Potion - Drink: cleared 2 HP"));
    }

    @Test
    void derivesDetailsFromExpression() {
        var details = ChatCards.rollDetails("2d6+3", List.of(4, 5), false, null);
        assertEquals(2, details.size());
        assertEquals(9, details.get(0).total());
        assertEquals(3, details.get(1).total());
    }
}
