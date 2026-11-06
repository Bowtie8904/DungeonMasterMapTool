package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatPanelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void goldCommandAcceptsOnlyPositiveWholeAmounts() {
        assertEquals(500, ChatPanel.goldAmount("500"));
        assertEquals(7, ChatPanel.goldAmount(" 7 "));
        for (String invalid : new String[]{"", "0", "-5", "1.5", "abc", "1234567890"})
            assertNull(ChatPanel.goldAmount(invalid), invalid);
    }

    @Test
    void sacredResonanceNotesWrapInsideNarrowDmCardsWithoutChangingTotals() throws Exception {
        JsonNode message = MAPPER.readTree("""
                {"type":"roll","id":9,"ts":10,"from":"Seraph","expression":"4d8","rolls":[4,4,3,2],"total":21,
                 "breakdown":[
                   {"label":"Weapon","expression":"4d8","rolls":[4,4,3,2],"total":13},
                   {"label":"Divine Wielder - Sacred Resonance","expression":"8","rolls":[],"total":8,
                    "notes":[{"label":"Sacred Resonance",
                     "impact":"Matching kept damage dice count double across every damage source, including weapon dice and bonus dice; +8 damage."}]}]}
                """);
        onFx(() -> {
            var card = (javafx.scene.layout.VBox) ChatCards.render(message);
            StackPane root = new StackPane(card);
            Scene scene = new Scene(root, 260, 900);
            scene.getStylesheets().add(Icons.STYLESHEET);
            root.applyCss();
            var disclosure = (javafx.scene.layout.VBox) card.lookup(".chat-roll-breakdown");
            var content = (javafx.scene.layout.VBox) disclosure.getChildren().get(1);
            content.setVisible(true);
            content.setManaged(true);
            for (double width : new double[]{260, 340}) {
                root.resize(width, 900);
                root.layout();
                var note = (javafx.scene.layout.VBox) card.lookup(".chat-detail-note-row");
                Label impact = (Label) note.getChildren().get(1);
                assertTrue(impact.isWrapText());
                assertEquals(0, impact.getMinWidth());
                assertTrue(impact.getHeight() > impact.getFont().getSize() * 2, "Explanation wraps onto multiple lines");
                assertTrue(note.getBoundsInParent().getMaxX() <= content.getWidth() + 1, "Note stays inside breakdown");
                assertTrue(impact.getBoundsInParent().getMaxX() <= note.getWidth() + 1, "Explanation stays inside note");
                assertTrue(card.getWidth() <= width + 1, "Card never expands beyond available chat width");
            }
            assertTrue(card.lookupAll(".chat-detail-value").stream()
                    .map(node -> ((Label) node).getText()).toList().containsAll(List.of("+13", "+8")));
        });
    }

    @Test
    void dmDeleteIsAccessibleAndWaitsForHostEventWithoutMarkingUnread() throws Exception {
        JsonNode message = MAPPER.readTree("{\"type\":\"chat\",\"id\":1,\"ts\":10,\"from\":\"Alice\",\"text\":\"Hello\"}");
        CountDownLatch called = new CountDownLatch(1);
        ChatPanel[] panel = new ChatPanel[1];
        onFx(() -> {
            panel[0] = new ChatPanel(new Backend(message) {
                @Override public String delete(long id, long timestamp) {
                    assertEquals(1, id);
                    assertEquals(10, timestamp);
                    assertFalse(Platform.isFxApplicationThread());
                    return "Could not delete chat message.";
                }
            });
            Scene scene = new Scene(new StackPane(panel[0]));
            scene.getStylesheets().add(Icons.STYLESHEET);
            panel[0].applyCss();
            ((Label) panel[0].lookup(".chat-status")).textProperty().addListener((o, a, b) -> called.countDown());
            Button delete = (Button) panel[0].lookup(".chat-delete");
            assertNotNull(delete);
            assertTrue(delete.isFocusTraversable());
            assertEquals("Delete message for everyone", delete.getAccessibleText());
            delete.fire();
            assertEquals(1, panel[0].lookupAll(".chat-entry").size());
        });
        assertTrue(called.await(5, TimeUnit.SECONDS));
        onFx(() -> {
            assertEquals(1, panel[0].lookupAll(".chat-entry").size(), "Failure keeps the message visible");
            assertEquals("Could not delete chat message.", ((Label) panel[0].lookup(".chat-status")).getText());
            panel[0].onMessage(MAPPER.createObjectNode().put("type", "chatDeleted").put("id", 1).put("ts", 99));
            assertEquals(1, panel[0].lookupAll(".chat-entry").size(), "Legacy repeated ids need matching timestamps");
            panel[0].onMessage(MAPPER.createObjectNode().put("type", "chatDeleted").put("id", 1).put("ts", 10));
            assertTrue(panel[0].lookupAll(".chat-entry").isEmpty());
            assertFalse(panel[0].unreadProperty().get());
            var nonModerated = ChatCards.render(message);
            assertNull(nonModerated.lookup(".chat-delete"), "Rendering without host actions never adds delete controls");
        });
    }

    private static class Backend implements ChatPanel.Backend {
        private final JsonNode message;
        Backend(JsonNode message) { this.message = message; }
        @Override public List<JsonNode> history() { return List.of(message); }
        @Override public void send(String text, String toPlayerId) {}
        @Override public String roll(String expression, String toPlayerId, boolean secret) { return null; }
        @Override public String delete(long id, long timestamp) { return null; }
        @Override public List<ChatPanel.Recipient> recipients() { return List.of(); }
        @Override public int remaining(JsonNode sharedCard) { return 0; }
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
