package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
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
    void secretToggleAppliesToAllComposerRollsButNotMessagesOrGold() throws Exception {
        onFx(() -> {
            RecordingBackend backend = new RecordingBackend();
            ChatPanel panel = new ChatPanel(backend);
            HBox composer = (HBox) panel.getChildren().get(2);
            TextField input = (TextField) composer.getChildren().get(0);
            Button send = (Button) composer.getChildren().get(1);
            Button roll = (Button) composer.getChildren().get(2);
            ToggleButton secret = (ToggleButton) composer.getChildren().get(3);
            assertFalse(secret.isSelected());
            assertTrue(secret.isFocusTraversable());
            input.setText("2d6+3");
            secret.fire();
            assertTrue(secret.isSelected());
            assertEquals("2d6+3", input.getText(), "Toggling does not submit or clear input");
            assertTrue(backend.rolls.isEmpty());
            roll.fire();
            assertTrue(input.getText().isEmpty());
            for (String command : List.of("/r", "/roll", "/sr", "/secret")) {
                input.setText(command + " 2d6+3");
                input.fireEvent(new ActionEvent());
                assertTrue(secret.isSelected());
            }
            assertEquals(java.util.Collections.nCopies(5, "2d6+3:true"), backend.rolls);
            input.setText("Hello everyone");
            send.fire();
            assertEquals(List.of("Hello everyone"), backend.messages);
            input.setText("/gold 500");
            input.fireEvent(new ActionEvent());
            assertEquals(List.of(500), backend.gold);
            assertTrue(secret.isSelected());
            assertEquals(5, backend.rolls.size());
            secret.fire();
            input.setText("/r 1d20");
            send.fire();
            input.setText("1d20");
            roll.fire();
            assertEquals(List.of("1d20:false", "1d20:false"), backend.rolls.subList(5, 7));
        });
    }

    @Test
    void secretCommandsOverrideOffToggleForSendEnterAndRollAndKeepWhisperTarget() throws Exception {
        onFx(() -> {
            RecordingBackend backend = new RecordingBackend();
            ChatPanel panel = new ChatPanel(backend);
            HBox composer = (HBox) panel.getChildren().get(2);
            TextField input = (TextField) composer.getChildren().get(0);
            Button send = (Button) composer.getChildren().get(1);
            Button roll = (Button) composer.getChildren().get(2);
            ToggleButton secret = (ToggleButton) composer.getChildren().get(3);
            ComboBox<?> recipients = (ComboBox<?>) panel.getChildren().get(1);
            recipients.getSelectionModel().select(1);
            for (String command : List.of("/sr", "/secret", "/SR")) {
                for (Runnable submit : List.<Runnable>of(send::fire, roll::fire,
                        () -> input.fireEvent(new ActionEvent()))) {
                    input.setText(command + " 1d20+2");
                    submit.run();
                    assertEquals("1d20+2:true", backend.rolls.getLast());
                    assertEquals("alice", backend.lastTarget);
                    assertTrue(input.getText().isEmpty());
                    assertFalse(secret.isSelected(), "An explicit secret command does not change the toggle");
                }
            }
            input.setText("/sroll is a normal message");
            send.fire();
            assertEquals(List.of("/sroll is a normal message"), backend.messages);
            assertEquals("alice", backend.lastTarget);
            secret.fire();
            backend.error = "Invalid dice expression";
            input.setText("/r invalid");
            send.fire();
            assertEquals("invalid:true", backend.rolls.getLast());
            assertEquals("/r invalid", input.getText());
            assertEquals(backend.error, ((Label) panel.lookup(".chat-status")).getText());
            assertTrue(secret.isSelected());
            int calls = backend.rolls.size();
            input.setText("/sr");
            send.fire();
            assertEquals(calls, backend.rolls.size(), "Missing dice must not reach the backend");
            assertTrue(secret.isSelected());
        });
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
    void rollAndChatUpdatesPreserveDisclosureStateWhileRenderingNewContent() throws Exception {
        JsonNode roll = MAPPER.readTree("""
                {"type":"roll","id":9,"ts":10,"from":"Alice","expression":"1d6","rolls":[4],"total":4,
                 "rollSource":{"name":"Spell","source":"Codex","text":"Original"}}
                """);
        JsonNode chat = MAPPER.readTree("""
                {"type":"chat","id":11,"ts":12,"from":"Alice","text":"Hello",
                 "rollSource":{"name":"Feature","source":"Class","text":"Original"}}
                """);
        onFx(() -> {
            ChatPanel panel = new ChatPanel(new Backend(roll));
            panel.onMessage(chat);
            Scene scene = new Scene(new StackPane(panel));
            scene.getStylesheets().add(Icons.STYLESHEET);
            panel.applyCss();
            var breakdown = (javafx.scene.layout.VBox) panel.lookup(".chat-roll-breakdown");
            var source = (javafx.scene.layout.VBox) panel.lookup(".chat-roll-source");
            ((Label) breakdown.getChildren().get(0)).getOnMouseClicked().handle(null);
            ((Label) source.getChildren().get(0)).getOnMouseClicked().handle(null);
            panel.onMessage(MAPPER.createObjectNode().put("type", "rollUpdate").put("id", 9).put("ts", 10).put("total", 7));
            var nextBreakdown = (javafx.scene.layout.VBox) panel.lookup(".chat-roll-breakdown");
            var nextSource = (javafx.scene.layout.VBox) panel.lookup(".chat-roll-source");
            assertNotSame(breakdown, nextBreakdown);
            assertTrue(nextBreakdown.getChildren().get(1).isVisible());
            assertTrue(nextBreakdown.getChildren().get(1).isManaged());
            assertTrue(((Label) nextBreakdown.getChildren().get(0)).getText().startsWith("\u25BE"));
            assertTrue(nextSource.getChildren().get(1).isVisible());
            assertEquals("7", ((Label) panel.lookup(".chat-total-value")).getText());
            ((Label) nextBreakdown.getChildren().get(0)).getOnMouseClicked().handle(null);
            panel.onMessage(MAPPER.createObjectNode().put("type", "rollUpdate").put("id", 9).put("ts", 10).put("total", 8));
            assertFalse(((javafx.scene.layout.VBox) panel.lookup(".chat-roll-breakdown")).getChildren().get(1).isVisible());

            var chatCard = panel.lookupAll(".chat-entry").stream()
                    .filter(card -> card.getUserData() == chat).findFirst().orElseThrow();
            var chatSource = (javafx.scene.layout.VBox) chatCard.lookup(".chat-roll-source");
            ((Label) chatSource.getChildren().get(0)).getOnMouseClicked().handle(null);
            var updated = ((com.fasterxml.jackson.databind.node.ObjectNode) chat.deepCopy()).put("text", "Updated");
            panel.onMessage(MAPPER.createObjectNode().put("type", "chatUpdate").set("message", updated));
            var updatedCard = panel.lookupAll(".chat-entry").stream()
                    .filter(card -> card.getUserData() == updated).findFirst().orElseThrow();
            var updatedSource = (javafx.scene.layout.VBox) updatedCard.lookup(".chat-roll-source");
            assertTrue(updatedSource.getChildren().get(1).isVisible());
            assertEquals("Updated", ((Label) updatedCard.lookup(".chat-text")).getText());
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

    private static class RecordingBackend extends Backend {
        final List<String> rolls = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Integer> gold = new ArrayList<>();
        String lastTarget;
        String error;

        RecordingBackend() { super(null); }
        @Override public List<JsonNode> history() { return List.of(); }
        @Override public List<ChatPanel.Recipient> recipients() {
            return List.of(new ChatPanel.Recipient("alice", "Alice"));
        }
        @Override public void send(String text, String toPlayerId) {
            messages.add(text);
            lastTarget = toPlayerId;
        }
        @Override public String roll(String expression, String toPlayerId, boolean secret) {
            rolls.add(expression + ":" + secret);
            lastTarget = toPlayerId;
            return error;
        }
        @Override public String offerGold(int amount, String toPlayerId) {
            gold.add(amount);
            lastTarget = toPlayerId;
            return null;
        }
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
