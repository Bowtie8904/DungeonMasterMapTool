package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.FxTestSupport;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
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
    void mixedDiceCardsLabelEachPoolIncludingLegacyAndSecretRolls() throws Exception {
        for (String message : List.of(
                """
                {"type":"roll","from":"Alice","expression":"2d8+2d6+1","rolls":[7,3,5,2],"total":18,
                 "breakdown":[{"label":"Brawler damage","expression":"2d8+2d6","rolls":[7,3,5,2],"total":17}]}
                """,
                """
                {"type":"roll","from":"Alice","expression":"2d8+2d6+1","rolls":[7,3,5,2],"total":18}
                """,
                """
                {"type":"roll","from":"Alice","expression":"(2d8kh1+2d6)*2","rolls":[7,3,5,2],"total":28,
                 "breakdown":[{"label":"Brawler damage","expression":"2d8kh1+2d6","rolls":[7,3,5,2],"total":14}]}
                """,
                """
                {"type":"roll","from":"Alice","secret":true,"expression":"2d8+2d6","rolls":[0,0,0,0],"total":0}
                """)) {
            JsonNode parsed = MAPPER.readTree(message);
            onFx(() -> {
                VBox card = (VBox) ChatCards.render(parsed);
                var first = card.lookup(".chat-die");
                assertNotNull(first);
                var dice = (javafx.scene.layout.FlowPane) first.getParent();
                assertEquals(List.of("d8", "d8", "d6", "d6"), dice.getChildren().stream()
                        .map(tile -> ((Label) ((VBox) tile).getChildren().getFirst()).getText()).toList());
                if (parsed.path("expression").asText().contains("kh1"))
                    assertTrue(dice.getChildren().get(1).getStyleClass().contains("chat-die-discarded"));
                if (parsed.path("secret").asBoolean())
                    assertEquals(List.of("?", "?", "?", "?"), dice.getChildren().stream()
                            .map(tile -> ((Label) ((VBox) tile).getChildren().get(1)).getText()).toList());
            });
        }
    }

    @Test
    void breakdownSourcePreviewOpensOnKeyboardFocusAndEscapeClosesIt() throws Exception {
        assertSourcePreview("Combo feature rules", false);
    }

    @Test
    void comboStrikePreviewFitsItsFullDescriptionWithoutScrolling() throws Exception {
        JsonNode classes = MAPPER.readTree(java.nio.file.Path.of("dmmap-daggerheart", "classes.json").toFile());
        List<JsonNode> features = classes.findParents("name").stream()
                .filter(feature -> "Combo Strike".equals(feature.path("name").asText())
                        && feature.has("text")).toList();
        assertEquals(1, features.size());
        assertSourcePreview(features.getFirst().path("text").asText(), false);
    }

    @Test
    void exceptionallyLongSourcePreviewScrollsWithoutCoveringItsRow() throws Exception {
        assertSourcePreview("A lengthy feature description with **important rules** and extra damage.\n".repeat(120),
                true);
    }

    private void assertSourcePreview(String text, boolean scrolling) throws Exception {
        JsonNode message = MAPPER.readTree("""
                {"type":"roll","from":"Alice","expression":"2d4","rolls":[4,1],"total":5,
                 "breakdown":[{"label":"Combo Strike rolls","expression":"2d4","rolls":[4,1],"total":5,
                 "rollSource":{"name":"Combo Strike","text":"Combo feature rules","source":"Brawler"}}]}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) message.path("breakdown").get(0).path("rollSource"))
                .put("text", text);
        Stage[] stage = new Stage[1];
        javafx.scene.Node[] row = new javafx.scene.Node[1];
        javafx.stage.Window[] popup = new javafx.stage.Window[1];
        try {
            onFx(() -> {
                VBox card = (VBox) ChatCards.render(message);
                VBox breakdown = (VBox) card.lookup(".chat-roll-breakdown");
                breakdown.getChildren().get(1).setVisible(true);
                row[0] = card.lookup(".chat-detail");
                assertTrue(row[0].isFocusTraversable());
                assertEquals("Combo Strike rolls. Source: Combo Strike", row[0].getAccessibleText());
                stage[0] = new Stage();
                Scene scene = new Scene(new StackPane(card), 600, 500);
                scene.getStylesheets().add(Icons.STYLESHEET);
                stage[0].setScene(scene);
                stage[0].show();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                stage[0].requestFocus();
            });
            onFx(() -> {
                row[0].requestFocus();
                var previewWindow = javafx.stage.Window.getWindows().stream()
                        .filter(window -> window.isShowing() && window.getScene() != null
                                && window.getScene().getRoot().lookup(".chat-source-preview") != null)
                        .findFirst().orElseThrow();
                var preview = (VBox) previewWindow.getScene().getRoot().lookup(".chat-source-preview");
                assertEquals("Combo Strike", ((Label) preview.getChildren().get(1)).getText());
                popup[0] = previewWindow;
            });
            onFx(() -> {
                var root = popup[0].getScene().getRoot();
                root.applyCss();
                root.layout();
                var preview = (VBox) root.lookup(".chat-source-preview");
                assertTrue(popup[0].getWidth() >= 490, "Preview must have a comfortable reading width");
                assertEquals(scrolling, root.lookup(".scroll-pane") != null);
                if (!scrolling)
                    assertTrue(preview.getHeight() >= preview.prefHeight(preview.getWidth()) - 1,
                            "Styled text must fit without clipping");
                var bounds = row[0].localToScreen(row[0].getBoundsInLocal());
                var popupBounds = new javafx.geometry.BoundingBox(popup[0].getX(), popup[0].getY(),
                        popup[0].getWidth(), popup[0].getHeight());
                assertFalse(popupBounds.intersects(bounds),
                        "Popup must not intercept the hovered row: " + popupBounds + " row: " + bounds);
                var screen = javafx.stage.Screen.getScreensForRectangle(bounds.getMinX(), bounds.getMinY(), 1, 1)
                        .getFirst().getVisualBounds();
                assertTrue(popupBounds.getMinX() >= screen.getMinX());
                assertTrue(popupBounds.getMaxX() <= screen.getMaxX());
                assertTrue(popupBounds.getMinY() >= screen.getMinY());
                assertTrue(popupBounds.getMaxY() <= screen.getMaxY(),
                        "Popup exceeds screen: " + popupBounds + " screen: " + screen);
                row[0].fireEvent(mouseEvent(javafx.scene.input.MouseEvent.MOUSE_EXITED));
                root.fireEvent(mouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED));
            });
            CountDownLatch settled = new CountDownLatch(1);
            onFx(() -> {
                PauseTransition delay = new PauseTransition(Duration.millis(220));
                delay.setOnFinished(event -> settled.countDown());
                delay.play();
            });
            assertTrue(settled.await(5, TimeUnit.SECONDS));
            onFx(() -> {
                assertTrue(popup[0].isShowing(), "Moving into the preview must not hide it");
                row[0].fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
                assertFalse(popup[0].isShowing());
            });
            if (!scrolling && "Combo feature rules".equals(text)) {
                CountDownLatch hovered = new CountDownLatch(1);
                onFx(() -> {
                    row[0].fireEvent(mouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED));
                    PauseTransition delay = new PauseTransition(
                            Duration.millis(dmmt.service.Tuning.TOOLTIP_DELAY_MS.get() + 100));
                    delay.setOnFinished(event -> hovered.countDown());
                    delay.play();
                });
                assertTrue(hovered.await(5, TimeUnit.SECONDS));
                onFx(() -> {
                    assertTrue(popup[0].isShowing(), "Hover must open the source preview after its delay");
                    double x = popup[0].getX();
                    double y = popup[0].getY();
                    row[0].fireEvent(mouseEvent(javafx.scene.input.MouseEvent.MOUSE_MOVED));
                    assertEquals(x, popup[0].getX());
                    assertEquals(y, popup[0].getY());
                    row[0].fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
                    assertFalse(popup[0].isShowing());
                });
            }
        } finally {
            onFx(() -> { if (stage[0] != null) stage[0].close(); });
        }
    }

    private static javafx.scene.input.MouseEvent mouseEvent(javafx.event.EventType<javafx.scene.input.MouseEvent> type) {
        return new javafx.scene.input.MouseEvent(type, 0, 0, 0, 0, javafx.scene.input.MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, false, null);
    }

    @Test
    void goldCommandAcceptsOnlyPositiveWholeAmounts() {
        assertEquals(500, ChatPanel.goldAmount("500"));
        assertEquals(7, ChatPanel.goldAmount(" 7 "));
        for (String invalid : new String[]{"", "0", "-5", "1.5", "abc", "1234567890"})
            assertNull(ChatPanel.goldAmount(invalid), invalid);
    }

    @Test
    void chatRendersOnlyVisibleCardsAndMarkReadScrollsToTheLatestMessage() throws Exception {
        List<JsonNode> history = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            history.add(MAPPER.createObjectNode().put("type", "chat").put("from", "Alice")
                    .put("text", ("Message " + i + " with enough text to wrap across the chat card. ").repeat(8)));
        }
        ChatPanel[] panel = new ChatPanel[1];
        Stage[] stage = new Stage[1];
        onFx(() -> {
            panel[0] = new ChatPanel(new Backend(null) {
                @Override public List<JsonNode> history() { return history; }
            });
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(panel[0]), 320, 240);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage[0].setScene(scene);
            stage[0].show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ListView<?> list = (ListView<?>) panel[0].lookup(".chat-scroll");
            assertNotNull(list);
            assertEquals(history.size(), list.getItems().size());
            long renderedCards = panel[0].lookupAll(".chat-entry").size();
            assertTrue(renderedCards > 0, "The visible list cells render their messages");
            assertTrue(renderedCards < history.size(), "Offscreen messages must not build chat-card UI");
            list.scrollTo(0);
            list.layout();
            panel[0].markRead();
            assertEquals(0, visibleCellIndexes(list).stream().mapToInt(Integer::intValue).min().orElse(-1),
                    "Mark-read scrolling must be deferred past the current event");
        });

        CountDownLatch deferredScroll = new CountDownLatch(1);
        Platform.runLater(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(100));
            pause.setOnFinished(event -> deferredScroll.countDown());
            pause.play();
        });
        assertTrue(deferredScroll.await(5, TimeUnit.SECONDS));
        onFx(() -> {
            ListView<?> list = (ListView<?>) panel[0].lookup(".chat-scroll");
            list.applyCss();
            list.layout();
            assertTrue(visibleCellIndexes(list).contains(history.size() - 1),
                    "Latest message should be visible after mark-read, visible indexes: " + visibleCellIndexes(list));
            stage[0].close();
        });
    }

    @Test
    void liveMessageAtBottomScrollsFullyToLatestCard() throws Exception {
        List<JsonNode> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(MAPPER.createObjectNode().put("type", "chat").put("from", "Alice")
                    .put("text", ("Message " + i + " ").repeat(12)));
        }
        ChatPanel[] panel = new ChatPanel[1];
        Stage[] stage = new Stage[1];
        onFx(() -> {
            panel[0] = new ChatPanel(new Backend(null) {
                @Override public List<JsonNode> history() { return history; }
            });
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(panel[0]), 320, 240);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage[0].setScene(scene);
            stage[0].show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ListView<?> list = (ListView<?>) panel[0].lookup(".chat-scroll");
            list.scrollTo(history.size() - 1);
            list.layout();
        });
        waitForFxPulse();

        JsonNode latest = MAPPER.createObjectNode().put("type", "chat").put("from", "Alice")
                .put("text", "Latest message with extra content ".repeat(20));
        onFx(() -> panel[0].onMessage(latest));
        waitForFxPulse();
        onFx(() -> {
            ListView<?> list = (ListView<?>) panel[0].lookup(".chat-scroll");
            assertTrue(visibleCellIndexes(list).contains(history.size()),
                    "The live message should be fully visible at the bottom");
            javafx.scene.control.ScrollBar bar = (javafx.scene.control.ScrollBar) list.lookup(".scroll-bar:vertical");
            assertEquals(bar.getMax(), bar.getValue(), 0.01, "The vertical scrollbar should reach its maximum");
            stage[0].close();
        });
    }

    @Test
    void chatUsesAvailableSectionHeightAndSeparatesMessageCards() throws Exception {
        onFx(() -> {
            JsonNode message = MAPPER.createObjectNode().put("type", "chat").put("from", "Alice").put("text", "Hello");
            ChatPanel panel = new ChatPanel(new Backend(message));
            VBox.setVgrow(panel, Priority.ALWAYS);
            ControlSection section = new ControlSection("Chat", MaterialDesignC.CHAT_OUTLINE, "chat", panel);
            Stage stage = new Stage();
            StackPane root = new StackPane(section);
            Scene scene = new Scene(root, 320, 640);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();

            ListView<?> list = (ListView<?>) panel.lookup(".chat-scroll");
            assertTrue(list.getHeight() > 400, "Chat history should use the available section height");
            ListCell<?> cell = visibleCellIndexes(list).stream().findFirst()
                    .flatMap(index -> list.lookupAll(".list-cell").stream()
                            .filter(node -> node instanceof ListCell<?> candidate
                                    && !candidate.isEmpty() && candidate.getIndex() == index)
                            .map(node -> (ListCell<?>) node).findFirst())
                    .orElseThrow();
            assertEquals(8, cell.getPadding().getBottom(), "Message cards should have visible separation");
            stage.close();
        });
    }

    private static List<Integer> visibleCellIndexes(ListView<?> list) {
        List<Integer> indexes = new ArrayList<>();
        for (javafx.scene.Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                indexes.add(cell.getIndex());
            }
        }
        return indexes;
    }

    private static void waitForFxPulse() throws Exception {
        CountDownLatch pulse = new CountDownLatch(1);
        Platform.runLater(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(100));
            pause.setOnFinished(event -> pulse.countDown());
            pause.play();
        });
        assertTrue(pulse.await(5, TimeUnit.SECONDS));
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
    void dmRollCardsKeepCollapsedDisclosuresAndDiceCompact() throws Exception {
        JsonNode message = MAPPER.readTree("""
                {"type":"roll","from":"Alice","kind":"duality","expression":"2d12+3",
                 "rolls":[8,4],"total":15,"difficulty":12,
                 "rollSource":{"name":"Spell","source":"Codex","text":"Detailed spell rules."}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) message.path("rollSource"))
                .put("text", "Detailed spell rules.\n".repeat(20));
        onFx(() -> {
            VBox card = (VBox) ChatCards.render(message);
            card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            StackPane root = new StackPane(card);
            Scene scene = new Scene(root, 260, 900);
            scene.getStylesheets().add(Icons.STYLESHEET);
            root.applyCss();
            VBox breakdown = (VBox) card.lookup(".chat-roll-breakdown");
            VBox source = (VBox) card.lookup(".chat-roll-source");
            for (double width : new double[]{260, 340}) {
                root.resize(width, 900);
                root.layout();
                double collapsedHeight = card.getHeight();
                assertTrue(collapsedHeight <= 220, "A collapsed duality roll with source fits within 220px");
                for (VBox disclosure : List.of(breakdown, source)) {
                    assertFalse(disclosure.getChildren().get(1).isManaged());
                    assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, disclosure.getMaxHeight());
                    Label summary = (Label) disclosure.getChildren().get(0);
                    assertTrue(disclosure.getHeight() - summary.getHeight() <= 6,
                            "Collapsed disclosures reserve no expanded-content padding");
                }
                for (var die : card.lookupAll(".chat-die")) {
                    assertEquals(38, die.getLayoutBounds().getWidth(), 1);
                    assertEquals(44, die.getLayoutBounds().getHeight(), 1);
                }
                assertEquals("15", ((Label) card.lookup(".chat-total-value")).getText());
                for (VBox disclosure : List.of(breakdown, source))
                    ((Label) disclosure.getChildren().get(0)).getOnMouseClicked().handle(null);
                root.applyCss();
                root.layout();
                assertTrue(card.getHeight() > collapsedHeight + 80, "Expanded rules remain fully laid out");
                assertEquals(12, breakdown.getPadding().getBottom());
                assertEquals(8, source.getPadding().getBottom());
                for (VBox disclosure : List.of(breakdown, source))
                    ((Label) disclosure.getChildren().get(0)).getOnMouseClicked().handle(null);
                root.applyCss();
                root.layout();
                assertEquals(collapsedHeight, card.getHeight(), 1, "Collapsing removes all expanded space");
            }
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
            Stage stage = showPanel(panel, 320, 900);
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
            stage.close();

            ChatPanel chatPanel = new ChatPanel(new Backend(chat));
            Stage chatStage = showPanel(chatPanel, 320, 900);
            var chatCard = chatPanel.lookup(".chat-entry");
            var chatSource = (javafx.scene.layout.VBox) chatCard.lookup(".chat-roll-source");
            ((Label) chatSource.getChildren().get(0)).getOnMouseClicked().handle(null);
            var updated = ((com.fasterxml.jackson.databind.node.ObjectNode) chat.deepCopy()).put("text", "Updated");
            chatPanel.onMessage(MAPPER.createObjectNode().put("type", "chatUpdate").set("message", updated));
            var updatedCard = chatPanel.lookup(".chat-entry");
            assertEquals(updated, updatedCard.getUserData());
            var updatedSource = (javafx.scene.layout.VBox) updatedCard.lookup(".chat-roll-source");
            assertTrue(updatedSource.getChildren().get(1).isVisible());
            assertEquals("Updated", ((Label) updatedCard.lookup(".chat-text")).getText());
            chatStage.close();
        });
    }

    @Test
    void playerActionLabelsStayCompactReadOnlyAndRefreshWithoutReplacingTheMessage() throws Exception {
        var message = MAPPER.createObjectNode().put("type", "roll").put("id", 1).put("ts", 10)
                .put("from", "Alice").put("expression", "1d6").put("total", 4);
        message.putArray("rolls").add(4);
        var event = MAPPER.createObjectNode().put("type", "chatActions").put("id", 1).put("ts", 10);
        var players = event.putArray("players");
        for (String name : List.of("Alice", "Bob", "Carol")) {
            var player = players.addObject().put("name", name);
            var actions = player.putArray("actions");
            for (int i = 0; i < 30; i++) actions.addObject()
                    .put("label", i == 0 ? name.equals("Alice") ? "Gain 1 Hope" : name.equals("Bob") ? "Reroll" : "Claim"
                            : "A longer feature action label " + i)
                    .put("disabled", i == 1).put("detail", i == 1 ? "Not enough Hope" : "");
        }
        onFx(() -> {
            ChatPanel panel = new ChatPanel(new Backend(message));
            Stage stage = new Stage();
            StackPane root = new StackPane(panel);
            Scene scene = new Scene(root, 260, 900);
            scene.getStylesheets().add(Icons.STYLESHEET);
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            var card = panel.lookup(".chat-entry");
            panel.markRead();
            panel.onMessage(event);
            assertSame(card, panel.lookup(".chat-entry"));
            assertFalse(panel.unreadProperty().get(), "Presentation updates are not new messages");
            var footer = (javafx.scene.layout.VBox) panel.lookup(".chat-player-actions");
            assertTrue(footer.lookupAll(".button").isEmpty(), "No executable player actions");
            assertEquals(90, footer.lookupAll(".chat-player-action-label").size());
            assertEquals(3, footer.lookupAll(".unavailable").size());
            for (double width : new double[]{260, 340}) {
                root.resize(width, 900);
                root.layout();
                assertTrue(footer.getHeight() < 60, "Collapsed action summary stays bounded even with 90 actions");
                assertTrue(card.localToScene(card.getBoundsInLocal()).getMaxX() <= width + 1,
                        "Card right edge should fit at width " + width + " but was "
                                + card.localToScene(card.getBoundsInLocal()).getMaxX());
            }
            Label toggle = (Label) footer.getChildren().get(0);
            assertTrue(toggle.isFocusTraversable());
            toggle.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            assertTrue(footer.getChildren().get(2).isVisible());
            panel.onMessage(MAPPER.createObjectNode().put("type", "rollUpdate").put("id", 1).put("ts", 10).put("total", 5));
            footer = (javafx.scene.layout.VBox) panel.lookup(".chat-player-actions");
            assertTrue(footer.getChildren().get(2).isVisible(), "Roll updates preserve expanded action list");
            assertEquals(90, footer.lookupAll(".chat-player-action-label").size());
            event.set("players", MAPPER.createArrayNode());
            panel.onMessage(event);
            assertFalse(footer.isManaged());
            assertFalse(footer.isVisible());
            assertEquals(1, panel.lookupAll(".chat-entry").size());
            stage.close();
        });
    }

    @Test
    void dmDeleteIsAccessibleAndWaitsForHostEventWithoutMarkingUnread() throws Exception {
        JsonNode message = MAPPER.readTree("{\"type\":\"chat\",\"id\":1,\"ts\":10,\"from\":\"Alice\",\"text\":\"Hello\"}");
        CountDownLatch called = new CountDownLatch(1);
        ChatPanel[] panel = new ChatPanel[1];
        Stage[] stage = new Stage[1];
        onFx(() -> {
            panel[0] = new ChatPanel(new Backend(message) {
                @Override public String delete(long id, long timestamp) {
                    assertEquals(1, id);
                    assertEquals(10, timestamp);
                    assertFalse(Platform.isFxApplicationThread());
                    return "Could not delete chat message.";
                }
            });
            stage[0] = showPanel(panel[0], 320, 400);
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
            stage[0].close();
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

    private static Stage showPanel(ChatPanel panel, double width, double height) {
        Stage stage = new Stage();
        StackPane root = new StackPane(panel);
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(Icons.STYLESHEET);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }
}
