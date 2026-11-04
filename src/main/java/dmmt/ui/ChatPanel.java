package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

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
    private final VBox messages = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(messages);
    private final ComboBox<Recipient> recipient = new ComboBox<>();
    private final TextField input = new TextField();
    private final Label status = new Label();
    private final Button jump = new Button("Jump to latest");
    private final BooleanProperty unread = new SimpleBooleanProperty();
    private boolean stickToBottom = true;
    private boolean adjusting;
    private double offset;
    private double knownHeight;

    public ChatPanel(Backend backend) {
        this.backend = backend;
        getStyleClass().add("dm-chat");
        setSpacing(8);
        setPadding(new Insets(4));

        messages.setPadding(new Insets(4));
        messages.getStyleClass().add("chat-messages");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("chat-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        scroll.vvalueProperty().addListener((o, a, b) -> {
            // Only trust changes made while the content height is stable; the ScrollPane rewrites vvalue itself
            // while content grows, which must not overwrite the offset the reader was at.
            if (!adjusting && messages.getHeight() == knownHeight) {
                offset = b.doubleValue() * Math.max(0, knownHeight - scroll.getViewportBounds().getHeight());
            }
            updateJump();
        });
        scroll.viewportBoundsProperty().addListener((o, a, b) -> updateJump());
        messages.heightProperty().addListener((o, a, b) -> {
            double height = b.doubleValue();
            double range = Math.max(0, height - scroll.getViewportBounds().getHeight());
            boolean stick = stickToBottom;
            stickToBottom = false;
            double target = stick ? range : Math.min(offset, range);
            knownHeight = height;
            applyOffset(target, range);
            // The ScrollPane may adjust vvalue again in the same pulse; restore afterwards.
            Platform.runLater(() -> {
                double r = Math.max(0, messages.getHeight() - scroll.getViewportBounds().getHeight());
                applyOffset(Math.min(target, r), r);
            });
        });

        jump.getStyleClass().add("chat-jump");
        jump.setVisible(false);
        jump.setOnAction(e -> scrollToEnd());
        StackPane.setAlignment(jump, Pos.BOTTOM_CENTER);
        StackPane.setMargin(jump, new Insets(0, 0, 8, 0));
        StackPane viewport = new StackPane(scroll, jump);
        viewport.setPickOnBounds(false);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        viewport.setMinHeight(0);
        scroll.setMinHeight(0);
        setMinHeight(0);

        recipient.setMaxWidth(Double.MAX_VALUE);
        recipient.setOnShowing(e -> refreshRecipients());
        refreshRecipients();
        input.setPromptText("Message, /roll 2d6+3 or /secret 1d20");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> submit());
        Button send = Icons.button(MaterialDesignS.SEND, "Send message", this::submit);
        Button roll = Icons.button(MaterialDesignD.DICE_MULTIPLE, "Roll the dice in the box (visible to the recipient)",
                () -> rollFromInput(false));
        Button secret = Icons.button(MaterialDesignE.EYE_OFF,
                "Roll secretly: players see ? instead of the numbers", () -> rollFromInput(true));
        HBox composer = new HBox(4, input, send, roll, secret);
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
        messages.getChildren().clear();
        for (JsonNode m : backend.history()) {
            addCard(m);
        }
        stickToBottom = true;
        scrollToEnd();
    }

    public void clear() {
        messages.getChildren().clear();
        unread.set(false);
    }

    /** A live message; marks the chat unread unless it is currently on screen. */
    public void onMessage(JsonNode message) {
        if ("chatDeleted".equals(message.path("type").asText())) {
            messages.getChildren().removeIf(card -> card.getUserData() instanceof JsonNode m
                    && m.path("id").asLong() == message.path("id").asLong()
                    && m.path("ts").asLong() == message.path("ts").asLong());
            return;
        }
        if ("chatUpdate".equals(message.path("type").asText())) {
            JsonNode updated = message.path("message");
            for (int i = 0; i < messages.getChildren().size(); i++) {
                javafx.scene.Node card = messages.getChildren().get(i);
                if (!(card.getUserData() instanceof JsonNode previous)
                        || !previous.path("id").equals(updated.path("id")) || !previous.path("ts").equals(updated.path("ts"))) continue;
                javafx.scene.Node replacement = ChatCards.render(updated, backend::remaining, () -> deleteMessage(updated));
                replacement.setUserData(updated);
                messages.getChildren().set(i, replacement);
                break;
            }
            return;
        }
        if ("rollUpdate".equals(message.path("type").asText())) {
            for (int i = 0; i < messages.getChildren().size(); i++) {
                javafx.scene.Node card = messages.getChildren().get(i);
                if (!(card.getUserData() instanceof JsonNode previous)
                        || !"roll".equals(previous.path("type").asText())
                        || previous.path("id").asLong() != message.path("id").asLong()
                        || previous.path("ts").asLong() != message.path("ts").asLong()) continue;
                ObjectNode updated = (ObjectNode) previous.deepCopy();
                if (message.has("outcome")) updated.set("outcome", message.get("outcome"));
                else updated.remove("outcome");
                if (message.has("featureUses")) updated.set("featureUses", message.get("featureUses"));
                if (message.has("rewardClaimed")) updated.set("rewardClaimed", message.get("rewardClaimed"));
                javafx.scene.Node replacement = ChatCards.render(updated, backend::remaining, () -> deleteMessage(updated));
                replacement.setUserData(updated);
                messages.getChildren().set(i, replacement);
                return;
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

    private double distanceFromBottom() {
        double range = messages.getHeight() - scroll.getViewportBounds().getHeight();
        return range <= 0 ? 0 : range * (1 - scroll.getVvalue());
    }

    private void applyOffset(double target, double range) {
        adjusting = true;
        try {
            offset = target;
            scroll.setVvalue(range <= 0 ? 0 : target / range);
        } finally {
            adjusting = false;
        }
        updateJump();
    }

    private void updateJump() {
        jump.setVisible(distanceFromBottom() > 40);
    }

    /** Re-reads the remaining units of every transfer offer card of the given actor. */
    public void refreshStock(String actorId) {
        for (javafx.scene.Node card : messages.getChildren()) {
            if (actorId.equals(card.getProperties().get(ChatCards.STOCK_ACTOR))
                    && card.getProperties().get(ChatCards.STOCK_REFRESH) instanceof Runnable refresh) {
                refresh.run();
            }
        }
    }

    private void addCard(JsonNode m) {
        stickToBottom = distanceFromBottom() < 4;
        var card = ChatCards.render(m, backend::remaining, () -> deleteMessage(m));
        card.setUserData(m);
        messages.getChildren().add(card);
        while (messages.getChildren().size() > MAX_CARDS) {
            messages.getChildren().remove(0);
        }
    }

    private void deleteMessage(JsonNode message) {
        Thread.startVirtualThread(() -> {
            String error = backend.delete(message.path("id").asLong(), message.path("ts").asLong());
            Platform.runLater(() -> showStatus(error));
        });
    }

    private void scrollToEnd() {
        stickToBottom = false;
        scroll.applyCss();
        scroll.layout();
        scroll.setVvalue(1);
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

    private void rollFromInput(boolean secret) {
        String text = input.getText().trim();
        for (String command : new String[]{"/secret", "/sr", "/roll", "/r"}) {
            if (text.toLowerCase(Locale.ROOT).startsWith(command + " ") || text.equalsIgnoreCase(command)) {
                text = text.substring(command.length()).trim();
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
        String error = backend.roll(expression, target(), secret);
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
