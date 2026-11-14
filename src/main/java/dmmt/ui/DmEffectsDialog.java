package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.online.Campaign;
import dmmt.online.OnlineSessionServer;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DM editor for character effects; changes are committed by the online host. */
public final class DmEffectsDialog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> STATS = targets(
            "All traits|traits", "Agility|trait.Agility", "Strength|trait.Strength", "Finesse|trait.Finesse",
            "Instinct|trait.Instinct", "Presence|trait.Presence", "Knowledge|trait.Knowledge",
            "Proficiency|proficiency", "Evasion|evasion", "Armor slots (armor score)|armorScore",
            "HP slots|hpSlots", "Stress slots|stressSlots",
            "Major threshold|majorThreshold", "Severe threshold|severeThreshold", "Both thresholds|thresholds");
    private static final Map<String, String> ROLLS = targets(
            "Action rolls|action", "Reaction rolls|reaction", "Attack rolls|attack", "Spellcast rolls|spellcast",
            "Damage rolls|damage", "Magic damage rolls|magicdamage", "Spell damage rolls|spelldamage",
            "Agility rolls|trait:Agility", "Strength rolls|trait:Strength", "Finesse rolls|trait:Finesse",
            "Instinct rolls|trait:Instinct", "Presence rolls|trait:Presence", "Knowledge rolls|trait:Knowledge");
    private static final Map<String, String> DURATIONS = targets(
            "Until removed by the DM|manual", "End of scene|scene", "Any rest|rest",
            "Short rest|shortRest", "Long rest|longRest", "End of session|session");
    private static final Map<String, String> DAMAGE_TYPES = targets(
            "Any damage|any", "Physical damage|physical", "Magic damage|magic");
    private static final Map<String, String> MITIGATION = targets(
            "Resistance (half damage)|halve", "Vulnerability (double damage)|double",
            "Negate damage|negate", "Reduce damage by value|reduce", "Reduce severity by value|severity");
    private static final Map<String, String> RESOURCE_ACTIONS = targets(
            "Heal HP|clear:hp", "Mark HP|mark:hp", "Clear Stress|clear:stress", "Mark Stress|mark:stress",
            "Gain Hope|gain:hope", "Spend Hope|clear:hope", "Clear Armor|clear:armor", "Mark Armor|mark:armor");
    private static final Map<String, String> ADVANTAGE = targets(
            "No advantage change|", "Suggest advantage|advantage", "Suggest disadvantage|disadvantage");

    private record Recipient(String id, String label) {
        @Override public String toString() { return label; }
    }

    private record Existing(String id, String label) {
        @Override public String toString() { return label; }
    }

    private DmEffectsDialog() { }

    public static void show(Window owner, Campaign campaign, OnlineSessionServer server) {
        Dialog<Void> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, "Custom effects", "Create and manage player effects", MaterialDesignS.SWORD_CROSS, false);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        List<Recipient> recipients = new ArrayList<>();
        List<Recipient> manageable = new ArrayList<>();
        for (Campaign.Actor actor : campaign.getActors()) {
            Campaign.Player player = campaign.player(actor.getOwnerId());
            if (player != null) {
                Recipient recipient = new Recipient(actor.getId(), actor.getName() + "  —  " + player.getName()
                        + (actor.isDisabled() ? " (disabled)" : ""));
                manageable.add(recipient);
                if (!actor.isDisabled()) recipients.add(recipient);
            }
        }
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab management = new Tab("Manage effects");
        Runnable[] refresh = new Runnable[1];
        management.setContent(manage(server, manageable, refresh));
        management.setOnSelectionChanged(event -> {
            if (management.isSelected()) refresh[0].run();
        });
        tabs.getTabs().addAll(new Tab("Create effect", create(server, recipients)), management);
        tabs.setPrefSize(760, 650);
        dialog.getDialogPane().setContent(tabs);
        dialog.showAndWait();
    }

    private static VBox create(OnlineSessionServer server, List<Recipient> recipients) {
        TextField name = new TextField();
        name.setPromptText("e.g. Rally Die");
        ComboBox<String> kind = combo("Buff", "Debuff");
        ComboBox<String> duration = combo(DURATIONS.keySet().toArray(String[]::new));
        TextField uses = new TextField("0");
        uses.setPromptText("0 = unlimited");
        Map<String, String> useTriggers = new LinkedHashMap<>();
        useTriggers.put("Selected option or action", "selected");
        ROLLS.forEach((label, target) -> useTriggers.put("Any " + label.toLowerCase(java.util.Locale.ROOT), target));
        ComboBox<String> consumeOn = combo(useTriggers.keySet().toArray(String[]::new));
        TextArea notes = new TextArea();
        notes.setPromptText("Visible instructions and situational rules");
        notes.setPrefRowCount(2);
        CheckBox replace = new CheckBox("Replace DM effects with the same name on each selected character");
        VBox recipientsBox = new VBox(4);
        Map<Recipient, CheckBox> checks = new LinkedHashMap<>();
        for (Recipient recipient : recipients) {
            CheckBox check = new CheckBox(recipient.label());
            checks.put(recipient, check);
            recipientsBox.getChildren().add(check);
        }
        if (recipients.isEmpty()) recipientsBox.getChildren().add(new Label("No enabled player-owned characters."));
        ScrollPane recipientsScroll = scroll(recipientsBox, 105);
        Button selectAll = new Button("Select all");
        selectAll.setOnAction(e -> checks.values().forEach(check -> check.setSelected(true)));
        Button selectNone = new Button("Clear");
        selectNone.setOnAction(e -> checks.values().forEach(check -> check.setSelected(false)));
        VBox parts = new VBox(8);
        Button add = new Button("Add preset");
        add.setOnAction(e -> parts.getChildren().add(partRow(parts)));
        parts.getChildren().add(partRow(parts));
        Label error = new Label();
        error.setWrapText(true);
        Label hint = new Label("Each character tracks uses independently. Selectable-use effects spend a use only "
                + "when an option or action is submitted; roll-triggered effects spend one on each matching roll.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        Button apply = new Button("Apply to selected characters");
        apply.getStyleClass().add("primary");
        apply.setOnAction(e -> {
            try {
                ObjectNode definition = MAPPER.createObjectNode().put("name", name.getText())
                        .put("kind", kind.getValue().equals("Buff") ? "buff" : "debuff")
                        .put("until", DURATIONS.get(duration.getValue())).put("text", notes.getText())
                        .put("uses", Integer.parseInt(uses.getText().trim()))
                        .put("consumeOn", useTriggers.get(consumeOn.getValue()));
                var list = definition.putArray("parts");
                for (var node : parts.getChildren()) list.add(((PartRow) node.getUserData()).definition());
                List<String> ids = checks.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                        .map(entry -> entry.getKey().id()).toList();
                if (ids.isEmpty()) { status(error, "Select at least one character.", false); return; }
                server.applyDmEffect(ids, definition, replace.isSelected());
                status(error, "Applied \"" + name.getText().trim() + "\" to " + ids.size() + " character(s).", true);
            } catch (NumberFormatException ex) {
                status(error, "Uses, costs and modifier values must be whole numbers.", false);
            } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
                status(error, String.valueOf(ex.getMessage()), false);
            }
        });
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(8);
        fields.addRow(0, new Label("Name"), name, new Label("Type"), kind);
        fields.addRow(1, new Label("Expires"), duration, new Label("Uses (0 = unlimited)"), uses);
        fields.addRow(2, new Label("Spend uses on"), consumeOn);
        GridPane.setHgrow(name, Priority.ALWAYS);
        GridPane.setHgrow(duration, Priority.ALWAYS);
        HBox recipientHeader = new HBox(8, heading("Recipients"), selectAll, selectNone);
        recipientHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        apply.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, fields, new Separator(), recipientHeader, recipientsScroll, new Separator(),
                heading("Automation presets"), parts, add, new Separator(), heading("Description"), notes, hint, replace,
                apply, error);
        content.setPadding(new Insets(14));
        ScrollPane scroll = scroll(content, 0);
        VBox page = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return page;
    }

    private static VBox partRow(VBox parts) {
        ComboBox<String> type = combo("Stat modifier", "Roll modifier", "Selectable roll bonus", "Damage mitigation", "Resource action", "Narrative rule");
        ComboBox<String> target = combo(STATS.keySet().toArray(String[]::new));
        ComboBox<String> mitigation = combo(MITIGATION.keySet().toArray(String[]::new));
        ComboBox<String> advantage = combo(ADVANTAGE.keySet().toArray(String[]::new));
        TextField value = new TextField("1");
        value.setPrefColumnCount(4);
        TextField dice = new TextField();
        dice.setPromptText("Dice, e.g. -1d6");
        dice.setPrefColumnCount(8);
        TextField text = new TextField();
        text.setPromptText("Describe the rule");
        TextField hopeCost = new TextField("0");
        hopeCost.setPrefColumnCount(3);
        TextField stressCost = new TextField("0");
        stressCost.setPrefColumnCount(3);
        Label hopeLabel = new Label("Hope");
        Label stressLabel = new Label("Stress");
        CheckBox preselected = new CheckBox("Pre-selected on roll");
        Button remove = new Button("Remove");
        value.setTooltip(new Tooltip("Modifier or amount"));
        text.setPrefWidth(280);
        FlowPane primary = new FlowPane(8, 6, type, target, mitigation, value, remove);
        FlowPane secondary = new FlowPane(8, 6, dice, advantage, preselected, text, hopeLabel, hopeCost, stressLabel, stressCost);
        VBox row = new VBox(6, primary, secondary);
        row.setStyle("-fx-border-color: derive(-fx-base, -25%); -fx-border-radius: 6; -fx-padding: 8;");
        Runnable update = () -> {
            String selected = type.getValue();
            boolean narrative = selected.equals("Narrative rule");
            boolean option = selected.equals("Selectable roll bonus");
            boolean damage = selected.equals("Damage mitigation");
            boolean action = selected.equals("Resource action");
            target.getItems().setAll((selected.equals("Stat modifier") ? STATS : damage ? DAMAGE_TYPES
                    : action ? RESOURCE_ACTIONS : ROLLS).keySet());
            target.getSelectionModel().selectFirst();
            setShown(target, !narrative);
            setShown(mitigation, damage);
            setShown(value, !narrative && (!damage || "reduce".equals(MITIGATION.get(mitigation.getValue()))
                    || "severity".equals(MITIGATION.get(mitigation.getValue()))));
            setShown(dice, option || selected.equals("Roll modifier"));
            setShown(advantage, option);
            setShown(preselected, option);
            text.setPromptText(action ? "Action label" : "Describe the rule");
            setShown(text, narrative || action);
            for (var node : List.of(hopeLabel, hopeCost, stressLabel, stressCost)) setShown(node, option || action);
            setShown(secondary, option || narrative || action || selected.equals("Roll modifier"));
        };
        type.valueProperty().addListener((o, a, b) -> update.run());
        mitigation.valueProperty().addListener((o, a, b) -> setShown(value, type.getValue().equals("Damage mitigation")
                && ("reduce".equals(MITIGATION.get(b)) || "severity".equals(MITIGATION.get(b)))));
        update.run();
        remove.setOnAction(e -> parts.getChildren().remove(row));
        row.setUserData(new PartRow(type, target, mitigation, advantage, value, dice, text, hopeCost, stressCost, preselected));
        return row;
    }

    private record PartRow(ComboBox<String> type, ComboBox<String> target, ComboBox<String> mitigation,
                           ComboBox<String> advantage, TextField value, TextField dice, TextField text,
                           TextField hopeCost, TextField stressCost, CheckBox preselected) {
        ObjectNode definition() {
            ObjectNode part = MAPPER.createObjectNode();
            switch (type.getValue()) {
                case "Narrative rule" -> part.put("type", "narrative").put("text", text.getText());
                case "Stat modifier" -> part.put("type", "stat").put("target", STATS.get(target.getValue()))
                        .put("value", Integer.parseInt(value.getText().trim()));
                case "Roll modifier" -> part.put("type", "roll").put("target", ROLLS.get(target.getValue()))
                        .put("value", Integer.parseInt(value.getText().trim())).put("dice", dice.getText());
                case "Selectable roll bonus" -> part.put("type", "option").put("target", ROLLS.get(target.getValue()))
                        .put("value", Integer.parseInt(value.getText().trim())).put("dice", dice.getText())
                        .put("advantage", ADVANTAGE.get(advantage.getValue()))
                        .put("defaultOn", preselected.isSelected())
                        .put("hopeCost", Integer.parseInt(hopeCost.getText().trim()))
                        .put("stressCost", Integer.parseInt(stressCost.getText().trim()));
                case "Resource action" -> {
                    String[] operation = RESOURCE_ACTIONS.get(target.getValue()).split(":", 2);
                    part.put("type", "action").put("operation", operation[0]).put("target", operation[1])
                            .put("label", text.getText()).put("value", Integer.parseInt(value.getText().trim()))
                            .put("hopeCost", Integer.parseInt(hopeCost.getText().trim()))
                            .put("stressCost", Integer.parseInt(stressCost.getText().trim()));
                }
                case "Damage mitigation" -> {
                    String mode = MITIGATION.get(mitigation.getValue());
                    part.put("type", "mitigation").put("target", DAMAGE_TYPES.get(target.getValue())).put("mode", mode);
                    if (mode.equals("reduce") || mode.equals("severity")) part.put("value", Integer.parseInt(value.getText().trim()));
                }
                default -> throw new IllegalArgumentException("Choose a preset.");
            }
            return part;
        }
    }

    private static VBox manage(OnlineSessionServer server, List<Recipient> recipients, Runnable[] refreshOnSelect) {
        ComboBox<Recipient> character = new ComboBox<>();
        character.getItems().setAll(recipients);
        character.setMaxWidth(Double.MAX_VALUE);
        ListView<Existing> effects = new ListView<>();
        Label error = new Label();
        error.setWrapText(true);
        effects.setPlaceholder(new Label("No DM-managed effects on this character."));
        Runnable refresh = () -> {
            effects.getItems().clear();
            if (character.getValue() == null) return;
            try {
                for (JsonNode effect : server.dmEffects(character.getValue().id())) {
                    String remaining = effect.has("dmUsesRemaining") ? "  ·  " + effect.path("dmUsesRemaining").asInt() + " uses left" : "";
                    effects.getItems().add(new Existing(effect.path("id").asText(),
                            effect.path("name").asText() + "  ·  " + effect.path("until").asText() + remaining));
                }
                error.setText("");
            } catch (IllegalArgumentException | IllegalStateException | IOException ex) { status(error, String.valueOf(ex.getMessage()), false); }
        };
        refreshOnSelect[0] = refresh;
        character.valueProperty().addListener((o, a, b) -> refresh.run());
        Button reload = new Button("Refresh");
        reload.setOnAction(e -> refresh.run());
        Button remove = new Button("Remove selected effect");
        remove.setOnAction(e -> {
            Existing selected = effects.getSelectionModel().getSelectedItem();
            if (character.getValue() == null || selected == null) { status(error, "Select a character and an effect.", false); return; }
            try {
                server.removeDmEffect(character.getValue().id(), selected.id());
                refresh.run();
                status(error, "Effect removed.", true);
            } catch (IllegalArgumentException | IllegalStateException | IOException ex) { status(error, String.valueOf(ex.getMessage()), false); }
        });
        VBox page = new VBox(10, heading("Character"), character, heading("DM-managed effects"), effects,
                new HBox(8, reload, remove), error);
        page.setPadding(new Insets(14));
        VBox.setVgrow(effects, Priority.ALWAYS);
        if (!recipients.isEmpty()) character.getSelectionModel().selectFirst();
        return page;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em;");
        return label;
    }

    private static void status(Label label, String message, boolean ok) {
        label.setText(message);
        label.getStyleClass().remove("error-label");
        if (ok) label.setStyle("-fx-text-fill: #3a9d5d;");
        else { label.setStyle(""); label.getStyleClass().add("error-label"); }
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static ScrollPane scroll(javafx.scene.Node content, double height) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        if (height > 0) scroll.setPrefHeight(height);
        return scroll;
    }

    private static ComboBox<String> combo(String... values) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().setAll(values);
        combo.getSelectionModel().selectFirst();
        return combo;
    }

    private static Map<String, String> targets(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : entries) {
            String[] pair = entry.split("\\|", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }
}
