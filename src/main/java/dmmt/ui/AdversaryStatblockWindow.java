package dmmt.ui;

import dmmt.model.DaggerheartAdversary;
import dmmt.model.DmProject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdversaryStatblockWindow {
    @FunctionalInterface
    public interface ChangeHandler {
        void change(String label, Consumer<DmProject.TokenInstance> mutation);
    }

    @FunctionalInterface
    public interface RollHandler {
        /** Attack rolls ask for advantage and an Experience; all other rolls are made directly. */
        void roll(String label, String expression, boolean attack, List<String> experiences);
    }

    @FunctionalInterface
    public interface ReactionHandler {
        void prompt(String label, String trait);
    }

    @FunctionalInterface
    public interface FeatureHandler {
        /** Links one feature, as plain text without roll buttons, in the chat. */
        void link(String adversary, String name, String classification, String text);
    }

    private static final double ROLL_HEIGHT = 18;
    private static final double SLOT = 22;

    private static final String DICE_EXPRESSION = "(?:\\d*d\\d+)(?:\\s*[+-]\\s*(?:\\d*d\\d+|\\d+))*";
    private static final Pattern INLINE_ACTION = Pattern.compile(
            "(?i)(" + DICE_EXPRESSION + "(?:\\s+(?:direct\\s+)?(?:physical|magic)\\s+damage)?|"
                    + "make an attack|"
                    + "(?:Strength|Agility|Finesse|Instinct|Presence|Knowledge)\\s+Reaction\\s+Roll)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern REACTION = Pattern.compile(
            "(?i)(Strength|Agility|Finesse|Instinct|Presence|Knowledge)\\s+Reaction\\s+Roll");

    private final Stage stage = new Stage();
    private final Supplier<DmProject.TokenInstance> tokenSupplier;
    private final Supplier<DmProject.TokenDefinition> definitionSupplier;
    private final ChangeHandler changes;
    private final RollHandler rolls;
    private final ReactionHandler reactions;
    private final FeatureHandler featureLinks;
    private final VBox content = new VBox(10);
    private final ScrollPane scroll = new ScrollPane(content);

    public AdversaryStatblockWindow(Window owner, Supplier<DmProject.TokenInstance> tokenSupplier,
                                    Supplier<DmProject.TokenDefinition> definitionSupplier,
                                    ChangeHandler changes, RollHandler rolls,                                     ReactionHandler reactions,
                                                                        FeatureHandler featureLinks) {
                                            this.featureLinks = featureLinks;
                                            this.tokenSupplier = tokenSupplier;
        this.definitionSupplier = definitionSupplier;
        this.changes = changes;
        this.rolls = rolls;
        this.reactions = reactions;
        stage.initOwner(owner);
        stage.setTitle("Adversary");
        stage.setMinWidth(440);
        stage.setWidth(560);
        stage.setHeight(720);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adversary-content");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("adversary-scroll");
        Scene scene = new Scene(scroll);
        var stylesheet = AdversaryStatblockWindow.class.getResource("/dmmt/ui/dark.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        refresh();
        stage.show();
    }

    public void focus() {
        stage.toFront();
        stage.requestFocus();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void refresh() {
        DmProject.TokenInstance token = tokenSupplier.get();
        DmProject.TokenDefinition definition = definitionSupplier.get();
        if (token == null || definition == null || definition.getAdversary() == null) {
            stage.close();
            return;
        }
        DaggerheartAdversary adversary = definition.getAdversary();
        double scrollPosition = scroll.getVvalue();
        content.getChildren().clear();
        stage.setTitle(adversary.getName());
        DaggerheartAdversary.CombatState state = token.getAdversaryState();

        VBox header = new VBox(4, heading(adversary.getName()));
        Label tier = new Label("Tier " + adversary.getTier() + optional(" · ", adversary.getCategoryDisplayText()));
        tier.getStyleClass().add("adversary-subtitle");
        header.getChildren().add(tier);
        content.getChildren().add(header);
        if (has(adversary.getDescription())) {
            Label description = wrapped(adversary.getDescription(), "adversary-description");
            content.getChildren().add(description);
        }
        if (has(adversary.getMotivesAndTactics())) {
            Label motives = wrapped(adversary.getMotivesAndTactics(), "adversary-description");
            Label caption = new Label("MOTIVES & TACTICS");
            caption.getStyleClass().add("adversary-caption");
            content.getChildren().addAll(caption, motives);
        }
        content.getChildren().add(new Separator());

        HBox stats = new HBox(8, stat("Difficulty", Integer.toString(adversary.getDifficulty())),
                stat("Major", threshold(adversary, 0)), stat("Severe", threshold(adversary, 1)));
        content.getChildren().add(stats);

        VBox vitals = new VBox(8);
        vitals.getStyleClass().add("adversary-card");
        vitals.getChildren().add(resourceRow("HP", adversary.getMaxHp(), state == null ? 0 : state.getMarkedHp(), true));
        vitals.getChildren().add(resourceRow("Stress", adversary.getMaxStress(),
                state == null ? 0 : state.getMarkedStress(), false));
        vitals.getChildren().add(new Separator());
        vitals.getChildren().add(tokensRow(state));
        content.getChildren().add(vitals);

        addSection("Standard Attack");
        VBox attackCard = new VBox(8);
        attackCard.getStyleClass().add("adversary-card");
        String attackName = adversary.getDefaultAttackName() == null ? "Attack" : adversary.getDefaultAttackName();
        String attackRange = adversary.getDefaultAttackRange() == null ? "" : " · " + adversary.getDefaultAttackRange();
        attackCard.getChildren().add(wrapped(attackName + attackRange, "adversary-feature-name"));
        FlowPane attackRolls = new FlowPane(8, 6);
        attackRolls.getChildren().add(rollButton("Attack " + modifier(adversary.getAttackModifier()),
                "1d20" + signed(adversary.getAttackModifier()), adversary, true));
        if (has(adversary.getDefaultAttackDamage())) {
            attackRolls.getChildren().add(rollButton(adversary.getDefaultAttackDamage(),
                    diceExpression(adversary.getDefaultAttackDamage()), adversary, false));
        }
        attackCard.getChildren().add(attackRolls);
        content.getChildren().add(attackCard);

        if (adversary.getFeatures() != null && !adversary.getFeatures().isEmpty()) {
            addSection("Features");
            for (DaggerheartAdversary.Feature feature : adversary.getFeatures()) {
                VBox card = new VBox(6);
                card.getStyleClass().add("adversary-card");
                HBox title = new HBox(8);
                title.setAlignment(Pos.CENTER_LEFT);
                Label name = wrapped(feature.getName(), "adversary-feature-name");
                name.setMinWidth(0);
                title.getChildren().add(name);
                if (has(feature.getClassification())) {
                    Label kind = new Label(feature.getClassification());
                    kind.getStyleClass().add("adversary-chip");
                    title.getChildren().add(kind);
                }
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                title.getChildren().addAll(spacer, linkButton(adversary, feature));
                card.getChildren().addAll(title, inlineText(feature.getDescription(), adversary));
                content.getChildren().add(card);
            }
        }
        if (adversary.getExperiences() != null && !adversary.getExperiences().isEmpty()) {
            addSection("Experiences");
            VBox card = new VBox(wrapped(String.join(", ", adversary.getExperiences()), "adversary-description"));
            card.getStyleClass().add("adversary-card");
            content.getChildren().add(card);
        }
        Platform.runLater(() -> scroll.setVvalue(scrollPosition));
    }

    private Node resourceRow(String name, int maximum, int marked, boolean hp) {
        Label label = new Label(name + " " + marked + "/" + maximum);
        label.getStyleClass().add("adversary-resource-label");
        label.setMinWidth(78);
        label.setPrefWidth(78);
        label.setMaxWidth(78);
        FlowPane slots = new FlowPane(4, 4);
        HBox.setHgrow(slots, Priority.ALWAYS);
        for (int i = 0; i < maximum; i++) {
            final int slot = i;
            ToggleButton mark = new ToggleButton();
            mark.setSelected(i < marked);
            mark.setAccessibleText(name + " slot " + (i + 1));
            mark.getStyleClass().addAll("adversary-resource", hp ? "hp" : "stress");
            mark.setMinSize(SLOT, SLOT);
            mark.setPrefSize(SLOT, SLOT);
            mark.setMaxSize(SLOT, SLOT);
            mark.setOnAction(event -> updateState(hp ? "Mark HP" : "Mark Stress", state -> {
                if (hp) state.setMarkedHp(slot < marked ? slot : slot + 1);
                else state.setMarkedStress(slot < marked ? slot : slot + 1);
            }));
            slots.getChildren().add(mark);
        }
        HBox row = new HBox(10, label, slots);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private Node tokensRow(DaggerheartAdversary.CombatState state) {
        int count = state == null ? 0 : state.getTokens();
        Label value = new Label("Tokens " + count);
        value.getStyleClass().add("adversary-resource-label");
        value.setMinWidth(78);
        Button minus = new Button("−");
        Button plus = new Button("+");
        for (Button button : List.of(minus, plus)) {
            button.getStyleClass().add("adversary-step");
            button.setMinSize(SLOT, SLOT);
            button.setPrefSize(SLOT, SLOT);
        }
        minus.setAccessibleText("Remove adversary token");
        plus.setAccessibleText("Add adversary token");
        minus.setOnAction(event -> updateState("Remove adversary token",
                token -> token.setTokens(Math.max(0, token.getTokens() - 1))));
        plus.setOnAction(event -> updateState("Add adversary token",
                token -> token.setTokens(token.getTokens() + 1)));
        ToggleButton defeated = new ToggleButton("Defeated");
        defeated.getStyleClass().add("adversary-defeated");
        defeated.setSelected(state != null && state.isDead());
        defeated.setOnAction(event -> updateState("Change defeated state",
                token -> token.setDead(defeated.isSelected())));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, value, minus, plus, spacer, defeated);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void updateState(String label, Consumer<DaggerheartAdversary.CombatState> mutation) {
        changes.change(label, token -> {
            DaggerheartAdversary.CombatState state = token.getAdversaryState();
            if (state == null) state = new DaggerheartAdversary.CombatState(0, 0, 0, false);
            else state = new DaggerheartAdversary.CombatState(
                    state.getMarkedHp(), state.getMarkedStress(), state.getTokens(), state.isDead());
            mutation.accept(state);
            DaggerheartAdversary adversary = definitionSupplier.get().getAdversary();
            state.setMarkedHp(Math.clamp(state.getMarkedHp(), 0, adversary.getMaxHp()));
            state.setMarkedStress(Math.clamp(state.getMarkedStress(), 0, adversary.getMaxStress()));
            token.setAdversaryState(state);
        });
        refresh();
    }

    private Button linkButton(DaggerheartAdversary adversary, DaggerheartAdversary.Feature feature) {
        SVGPath icon = new SVGPath();
        icon.setContent("M4 4h16v12H9l-5 5ZM8 8h8M8 12h5");
        icon.setFill(null);
        icon.setStroke(Color.web("#d8dee9"));
        icon.setStrokeWidth(1.6);
        icon.setScaleX(0.7);
        icon.setScaleY(0.7);
        Button button = new Button();
        button.setGraphic(new Group(icon));
        button.getStyleClass().add("adversary-roll");
        button.setMinHeight(ROLL_HEIGHT + 4);
        button.setPrefHeight(ROLL_HEIGHT + 4);
        button.setMaxHeight(ROLL_HEIGHT + 4);
        button.setTooltip(new Tooltip("Link " + feature.getName() + " in chat"));
        button.setAccessibleText("Link " + feature.getName() + " in chat");
        button.setOnAction(event -> featureLinks.link(adversary.getName(), feature.getName(),
                feature.getClassification(), feature.getDescription()));
        return button;
    }

    private Node inlineText(String value, DaggerheartAdversary adversary) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("adversary-flow");
        flow.setLineSpacing(4);
        if (!has(value)) return flow;
        Matcher links = MARKDOWN_LINK.matcher(value);
        int linkEnd = 0;
        while (links.find()) {
            addActions(flow, value.substring(linkEnd, links.start()), adversary);
            String label = links.group(1);
            String url = links.group(2);
            if (LinkOpener.isWebLink(url)) {
                Hyperlink link = new Hyperlink(label);
                link.getStyleClass().add("adversary-link");
                link.setPadding(Insets.EMPTY);
                link.setTooltip(new Tooltip(url));
                link.setAccessibleText("Open " + label);
                link.setOnAction(event -> LinkOpener.open(stage, url));
                flow.getChildren().add(link);
            } else {
                flow.getChildren().add(text(label));
            }
            linkEnd = links.end();
        }
        addActions(flow, value.substring(linkEnd), adversary);
        return flow;
    }

    private void addActions(TextFlow flow, String value, DaggerheartAdversary adversary) {
        if (value.isEmpty()) return;
        Matcher matcher = INLINE_ACTION.matcher(value);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) flow.getChildren().add(text(value.substring(last, matcher.start())));
            String action = matcher.group();
            Matcher reaction = REACTION.matcher(action);
            if (reaction.matches()) {
                String trait = reaction.group(1);
                Button button = styledRollButton(action);
                button.getStyleClass().add("reaction");
                button.setAccessibleText("Prompt players to roll a " + trait + " reaction");
                button.setOnAction(event -> reactions.prompt(adversary.getName() + " · " + trait + " Reaction", trait));
                flow.getChildren().add(button);
            } else if ("make an attack".equalsIgnoreCase(action)) {
                flow.getChildren().add(rollButton(action, "1d20" + signed(adversary.getAttackModifier()),
                        adversary, true));
            } else {
                flow.getChildren().add(rollButton(action, diceExpression(action), adversary, false));
            }
            last = matcher.end();
        }
        if (last < value.length()) flow.getChildren().add(text(value.substring(last)));
    }

    private static Text text(String value) {
        Text text = new Text(value);
        text.getStyleClass().add("adversary-text");
        return text;
    }

    private static Button styledRollButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("adversary-roll");
        button.setMinHeight(ROLL_HEIGHT);
        button.setPrefHeight(ROLL_HEIGHT);
        button.setMaxHeight(ROLL_HEIGHT);
        button.setAccessibleText("Roll " + text);
        return button;
    }

    private Button rollButton(String text, String expression, DaggerheartAdversary adversary, boolean attack) {
        Button button = styledRollButton(text);
        button.setOnAction(event -> rolls.roll(text, expression, attack,
                attack ? adversary.getExperiences() : List.of()));
        return button;
    }

    private void addSection(String title) {
        Label label = new Label(title.toUpperCase(java.util.Locale.ROOT));
        label.getStyleClass().add("adversary-section");
        content.getChildren().add(label);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static Label wrapped(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Label heading(String title) {
        Label label = new Label(title);
        label.setWrapText(true);
        label.getStyleClass().add("adversary-heading");
        return label;
    }

    private static VBox stat(String name, String value) {
        Label caption = new Label(name.toUpperCase(java.util.Locale.ROOT));
        caption.getStyleClass().add("adversary-caption");
        Label number = new Label(value);
        number.getStyleClass().add("adversary-stat-value");
        VBox box = new VBox(2, caption, number);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("adversary-stat");
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPrefWidth(0);
        return box;
    }

    private static String threshold(DaggerheartAdversary adversary, int part) {
        String value = adversary.getDamageThresholds();
        if (value == null) return "—";
        String[] parts = value.split("/", -1);
        return parts.length > part ? parts[part].trim() : value;
    }

    private static String diceExpression(String text) {
        Matcher match = Pattern.compile("(?i)" + DICE_EXPRESSION).matcher(text);
        return match.find() ? match.group().replaceAll("\\s+", "")
                .replaceAll("(?i)(?<!\\d)d(\\d+)", "1d$1") : text;
    }

    private static String modifier(String adversary) {
        return adversary == null || adversary.isBlank() ? "+0" : adversary;
    }

    private static String signed(String value) {
        if (value == null || value.isBlank()) return "+0";
        String trimmed = value.trim();
        return trimmed.startsWith("+") || trimmed.startsWith("-") ? trimmed : "+" + trimmed;
    }

    private static String optional(String separator, String value) {
        return value == null || value.isBlank() ? "" : separator + value;
    }
}
