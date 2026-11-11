package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The DM's chat window, shown while an online campaign is hosted. Mirrors the player browser chat. */
public final class ChatPanel extends VBox {
    public interface Backend {
        List<JsonNode> history();

        void send(String text, String toPlayerId);

        /** Returns an error message, or null on success. */
        String roll(String expression, String toPlayerId, boolean secret);

        /** Returns an error message, or null once the removal has been persisted. */
        String delete(long id, long timestamp);

        List<Recipient> recipients();

        /** Units a transfer offer still has; may block, called off the FX thread. */
        int remaining(JsonNode sharedCard);

        default String artwork(String url) { return null; }

        /** Posts a gold transfer offer; returns an error message, or null on success. */
        default String offerGold(int amount, String toPlayerId) {
            return "Gold offers are not supported.";
        }
    }

    public record Recipient(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Recipient EVERYONE = new Recipient(null, "Everyone");
    private static final int MAX_CARDS = 300;

    private final Backend backend;
    private final ObservableList<JsonNode> messages = FXCollections.observableArrayList();
    private final ListView<JsonNode> messageList = new ListView<>(messages);
    private final Map<JsonNode, ChatCards.DisclosureState> disclosureStates = new IdentityHashMap<>();
    private final ComboBox<Recipient> recipient = new ComboBox<>();
    private final TextField input = new TextField();
    private final Label status = new Label();
    private final Button jump = new Button("Jump to latest");
    private final ToggleButton secretRoll = Icons.toggle(MaterialDesignE.EYE_OFF,
            "Secret rolls: when enabled, players see ? instead of the numbers");
    private final BooleanProperty unread = new SimpleBooleanProperty();
    private final ChangeListener<Number> verticalScrollListener = (o, a, b) -> updateJump();
    private ScrollBar verticalScrollBar;

    public ChatPanel(Backend backend) {
        this.backend = backend;
        getStyleClass().add("dm-chat");
        setSpacing(8);
        setPadding(new Insets(4));

        messageList.setId("chat-scroll");
        messageList.getStyleClass().add("chat-scroll");
        messageList.setPadding(new Insets(4));
        messageList.setMinHeight(0);
        messageList.setSelectionModel(null);
        messageList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(JsonNode item, boolean empty) {
                JsonNode previous = getItem();
                javafx.scene.Node previousCard = getGraphic();
                if (!empty && item != null && previous == item && previousCard != null) {
                    super.updateItem(item, false);
                    setUserData(item);
                    return;
                }
                if (previous != null && previousCard != null) {
                    if (containsMessageIdentity(previous)) {
                        disclosureStates.put(previous, ChatCards.disclosureState(previousCard));
                    } else {
                        disclosureStates.remove(previous);
                    }
                }
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setUserData(null);
                    return;
                }
                javafx.scene.Node card = ChatCards.render(item, backend::remaining,
                        () -> deleteMessage(item), backend::artwork);
                ChatCards.DisclosureState state = disclosureStates.remove(item);
                if (state != null) {
                    ChatCards.applyDisclosureState(card, state);
                }
                if (card instanceof javafx.scene.layout.Region region) {
                    region.maxWidthProperty().bind(widthProperty());
                }
                card.setUserData(item);
                setText(null);
                setUserData(item);
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                setGraphic(card);
            }
        });
        messageList.skinProperty().addListener((o, oldSkin, newSkin) -> Platform.runLater(this::observeVerticalScrollBar));
        messages.addListener((javafx.collections.ListChangeListener<JsonNode>) change -> Platform.runLater(this::updateJump));
        jump.getStyleClass().add("chat-jump");
        jump.setVisible(false);
        jump.setOnAction(e -> scrollToEnd());
        StackPane.setAlignment(jump, Pos.BOTTOM_CENTER);
        StackPane.setMargin(jump, new Insets(0, 0, 8, 0));
        StackPane viewport = new StackPane(messageList, jump);
        viewport.setPickOnBounds(false);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        viewport.setMinHeight(0);
        setMinHeight(0);

        recipient.setMaxWidth(Double.MAX_VALUE);
        recipient.setOnShowing(e -> refreshRecipients());
        refreshRecipients();
        input.setPromptText("Message, /r 2d6+3, /sr 1d20 or /gold 500");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> submit());
        Button send = Icons.button(MaterialDesignS.SEND, "Send message", this::submit);
        Button roll = Icons.button(MaterialDesignD.DICE_MULTIPLE, "Roll the dice in the box (uses the secret-roll toggle)",
                this::rollFromInput);
        secretRoll.setFocusTraversable(true);
        HBox composer = new HBox(4, input, send, roll, secretRoll);
        composer.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("chat-status");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        getChildren().addAll(viewport, recipient, composer, status);
        reload();
    }

    /** True while there are live messages the DM has not seen. */
    public BooleanProperty unreadProperty() {
        return unread;
    }

    /** Replaces the shown messages with the session history. */
    public void reload() {
        disclosureStates.clear();
        List<JsonNode> history = backend.history();
        messages.setAll(history.size() > MAX_CARDS
                ? history.subList(history.size() - MAX_CARDS, history.size()) : history);
        scrollToEnd();
    }

    public void clear() {
        disclosureStates.clear();
        messages.clear();
        unread.set(false);
    }

    /** A live message; marks the chat unread unless it is currently on screen. */
    public void onMessage(JsonNode message) {
        if ("chatActions".equals(message.path("type").asText())) {
            int index = findMessage(message.path("id").asLong(), message.path("ts").asLong());
            if (index >= 0 && messages.get(index) instanceof ObjectNode previous) {
                previous.set("dmActions", message.path("players").deepCopy());
                ListCell<?> cell = visibleCell(previous);
                if (cell != null && cell.getGraphic() != null) {
                    ChatCards.updateActionSummary(cell.getGraphic(), message.path("players"));
                }
            }
            return;
        }
        if ("chatDeleted".equals(message.path("type").asText())) {
            int index = findMessage(message.path("id").asLong(), message.path("ts").asLong());
            if (index >= 0) {
                disclosureStates.remove(messages.remove(index));
            }
            return;
        }
        if ("chatUpdate".equals(message.path("type").asText())) {
            JsonNode updated = message.path("message");
            int index = findMessage(updated.path("id").asLong(), updated.path("ts").asLong());
            if (index >= 0) {
                JsonNode previous = messages.get(index);
                if (updated instanceof ObjectNode) {
                    updated = updated.deepCopy();
                    if (previous.has("dmActions")) {
                        ((ObjectNode) updated).set("dmActions", previous.path("dmActions").deepCopy());
                    }
                }
                transferDisclosureState(previous, updated);
                messages.set(index, updated);
            }
            return;
        }
        if ("rollUpdate".equals(message.path("type").asText())) {
            int index = findMessage(message.path("id").asLong(), message.path("ts").asLong());
            if (index >= 0 && "roll".equals(messages.get(index).path("type").asText())) {
                JsonNode previous = messages.get(index);
                ObjectNode updated = (ObjectNode) previous.deepCopy();
                if (message.has("outcome")) updated.set("outcome", message.get("outcome"));
                else updated.remove("outcome");
                if (message.has("featureUses")) updated.set("featureUses", message.get("featureUses"));
                if (message.has("rewardClaimed")) updated.set("rewardClaimed", message.get("rewardClaimed"));
                if (message.has("deathResult")) updated.set("deathResult", message.get("deathResult"));
                if (message.has("featureRoll")) updated.set("featureRoll", message.get("featureRoll"));
                if (message.has("total")) updated.set("total", message.get("total"));
                if (message.has("expression")) updated.set("expression", message.get("expression"));
                if (message.has("rolls")) updated.set("rolls", message.get("rolls"));
                if (message.has("breakdown")) updated.set("breakdown", message.get("breakdown"));
                transferDisclosureState(previous, updated);
                messages.set(index, updated);
            }
            return;
        }
        addCard(message);
        if (!isShowing()) {
            unread.set(true);
        }
    }

    public boolean isShowing() {
        return getScene() != null && isVisible() && getParent() != null;
    }

    public void markRead() {
        unread.set(false);
        scrollToEnd();
    }

    private int findMessage(long id, long timestamp) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode item = messages.get(i);
            if (item.path("id").asLong() == id && item.path("ts").asLong() == timestamp) {
                return i;
            }
        }
        return -1;
    }

    private boolean containsMessageIdentity(JsonNode item) {
        for (JsonNode message : messages) {
            if (message == item) {
                return true;
            }
        }
        return false;
    }

    private void transferDisclosureState(JsonNode previous, JsonNode updated) {
        ChatCards.DisclosureState state = disclosureStates.remove(previous);
        if (state == null) {
            ListCell<?> cell = visibleCell(previous);
            if (cell != null && cell.getGraphic() != null) {
                state = ChatCards.disclosureState(cell.getGraphic());
            }
        }
        if (state != null) {
            disclosureStates.put(updated, state);
        }
    }

    private ListCell<?> visibleCell(JsonNode item) {
        for (javafx.scene.Node node : messageList.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getItem() == item) {
                return cell;
            }
        }
        return null;
    }

    private boolean isAtBottom() {
        if (messages.isEmpty()) {
            return true;
        }
        int lastVisible = -1;
        for (javafx.scene.Node node : messageList.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                lastVisible = Math.max(lastVisible, cell.getIndex());
            }
        }
        return lastVisible < 0 || lastVisible >= messages.size() - 1;
    }

    private void updateJump() {
        jump.setVisible(!isAtBottom());
    }

    private void observeVerticalScrollBar() {
        if (verticalScrollBar != null) {
            verticalScrollBar.valueProperty().removeListener(verticalScrollListener);
        }
        if (messageList.lookup(".scroll-bar:vertical") instanceof ScrollBar bar) {
            verticalScrollBar = bar;
            bar.valueProperty().addListener(verticalScrollListener);
        } else {
            verticalScrollBar = null;
        }
        if (messageList.lookup(".scroll-bar:horizontal") instanceof ScrollBar horizontal) {
            horizontal.setVisible(false);
            horizontal.setManaged(false);
        }
        updateJump();
    }

    /** Re-reads the remaining units of every transfer offer card of the given actor. */
    public void refreshStock(String actorId) {
        for (javafx.scene.Node node : messageList.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.getGraphic() == null) continue;
            javafx.scene.Node card = cell.getGraphic();
            if (actorId.equals(card.getProperties().get(ChatCards.STOCK_ACTOR))
                    && card.getProperties().get(ChatCards.STOCK_REFRESH) instanceof Runnable refresh) {
                refresh.run();
            }
        }
    }

    private void addCard(JsonNode m) {
        boolean stickToBottom = isAtBottom();
        messages.add(m);
        while (messages.size() > MAX_CARDS) {
            disclosureStates.remove(messages.removeFirst());
        }
        if (stickToBottom) {
            scrollToEnd();
        }
    }

    private void deleteMessage(JsonNode message) {
        Thread.startVirtualThread(() -> {
            String error = backend.delete(message.path("id").asLong(), message.path("ts").asLong());
            Platform.runLater(() -> showStatus(error));
        });
    }

    private void scrollToEnd() {
        Platform.runLater(() -> {
            if (messages.isEmpty()) {
                return;
            }
            messageList.applyCss();
            messageList.layout();
            messageList.scrollTo(messages.size() - 1);
            messageList.layout();
            if (verticalScrollBar != null) {
                verticalScrollBar.setValue(verticalScrollBar.getMax());
            }
            messageList.layout();
            updateJump();
        });
    }

    private void refreshRecipients() {
        Recipient selected = recipient.getValue();
        List<Recipient> all = new ArrayList<>();
        all.add(EVERYONE);
        all.addAll(backend.recipients());
        recipient.getItems().setAll(all);
        Recipient keep = all.stream().filter(r -> selected != null && java.util.Objects.equals(r.id(), selected.id()))
                .findFirst().orElse(EVERYONE);
        recipient.setValue(keep);
    }

    private String target() {
        Recipient r = recipient.getValue();
        return r == null ? null : r.id();
    }

    private void submit() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("/gold") || lower.startsWith("/gold ")) {
            offerGold(text.substring("/gold".length()).trim());
            return;
        }
        for (String command : new String[]{"/secret", "/sr", "/roll", "/r"}) {
            if (lower.equals(command) || lower.startsWith(command + " ")) {
                runRoll(text.substring(command.length()).trim(), command.equals("/secret") || command.equals("/sr"));
                return;
            }
        }
        backend.send(text, target());
        input.clear();
        showStatus(null);
    }

    static Integer goldAmount(String argument) {
        argument = argument == null ? "" : argument.strip();
        if (!argument.matches("\\d{1,9}"))
            return null;
        int amount = Integer.parseInt(argument);
        return amount > 0 ? amount : null;
    }

    private void offerGold(String argument) {
        Integer amount = goldAmount(argument);
        if (amount == null) {
            showStatus("Enter a whole amount of gold, such as /gold 500.");
            return;
        }
        String error = backend.offerGold(amount, target());
        if (error == null) {
            input.clear();
        }
        showStatus(error);
    }

    private void rollFromInput() {
        String text = input.getText().trim();
        boolean secret = false;
        for (String command : new String[]{"/secret", "/sr", "/roll", "/r"}) {
            if (text.toLowerCase(Locale.ROOT).startsWith(command + " ") || text.equalsIgnoreCase(command)) {
                text = text.substring(command.length()).trim();
                secret = command.equals("/secret") || command.equals("/sr");
                break;
            }
        }
        runRoll(text, secret);
    }

    private void runRoll(String expression, boolean secret) {
        if (expression.isEmpty()) {
            showStatus("Enter dice such as 2d6+3 first.");
            return;
        }
        String error = backend.roll(expression, target(), secret || secretRoll.isSelected());
        if (error == null) {
            input.clear();
        }
        showStatus(error);
    }

    private void showStatus(String text) {
        status.setText(text == null ? "" : text);
        status.setVisible(text != null);
        status.setManaged(text != null);
    }
}
