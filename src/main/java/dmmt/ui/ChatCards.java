package dmmt.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders chat and roll messages of the online session as cards that mirror the player browser's chat:
 * header, whisper label, shared cards, rest and damage reports, and dice rolls with their breakdown and source.
 * The DM never takes items or applies damage, so no sheet action buttons are created.
 */
public final class ChatCards {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern KEEP = Pattern.compile("(\\d*)d\\d+(?:kh(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TERM = Pattern.compile("([+-]?)(?:(\\d*)d(\\d+)(?:kh(\\d+))?|(\\d+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REST_HEAD = Pattern.compile("^(?:\uD83C\uDFD5\uFE0F )?(SHORT|LONG) REST$");
    private static final Pattern DAMAGE_STATS = Pattern.compile(
            "^(\\d+) (\\w+) damage(?: \u2192 (\\d+))? \u00B7 (.+) \u00B7 (\\d+) HP marked$");

    public record Note(String label, String impact) {
    }

    public record Detail(String label, String expression, List<Integer> rolls, int total, List<Note> notes) {
    }

    public record RestReport(boolean longRest, String subtitle, List<String> lines) {
    }

    public record DamageReport(String name, int input, int damage, String type, String severity, int hp,
                               List<String> lines) {
    }

    public record ActionReport(String title, String character, List<String> lines) {
    }

    private ChatCards() {
    }

    public static ActionReport parseActionReport(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !"ACTION USED".equals(lines[0])) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (int i = 3; i < lines.length; i++) {
            items.add(lines[i].replaceFirst("^\u2022 ?", ""));
        }
        return new ActionReport(lines[1], lines[2], items);
    }

    // ---- pure helpers, mirroring web/src/chat.ts ----

    /** Which dice count towards the total; "kh" terms drop their lowest dice. */
    public static boolean[] keptRolls(String expression, List<Integer> rolls) {
        boolean[] kept = new boolean[rolls.size()];
        java.util.Arrays.fill(kept, true);
        int index = 0;
        Matcher matcher = KEEP.matcher(expression);
        while (matcher.find()) {
            int count = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
            if (matcher.group(2) != null) {
                List<Integer> indices = new ArrayList<>();
                for (int n = index; n < Math.min(index + count, rolls.size()); n++) {
                    indices.add(n);
                }
                indices.sort((a, b) -> {
                    int byValue = Integer.compare(Math.abs(rolls.get(b)), Math.abs(rolls.get(a)));
                    return byValue != 0 ? byValue : Integer.compare(a, b);
                });
                int keep = Integer.parseInt(matcher.group(2));
                for (int n = keep; n < indices.size(); n++) {
                    kept[indices.get(n)] = false;
                }
            }
            index += count;
        }
        return kept;
    }

    /** The named breakdown sent by the host, or one derived from the expression for plain rolls. */
    public static List<Detail> rollDetails(String expression, List<Integer> rolls, boolean duality, JsonNode breakdown) {
        List<Detail> details = new ArrayList<>();
        if (breakdown != null && breakdown.isArray() && !breakdown.isEmpty()) {
            for (JsonNode d : breakdown) {
                List<Integer> values = new ArrayList<>();
                d.path("rolls").forEach(v -> values.add(v.asInt()));
                List<Note> notes = new ArrayList<>();
                d.path("notes").forEach(n -> notes.add(new Note(n.path("label").asText(), n.path("impact").asText())));
                details.add(new Detail(d.path("label").asText(), d.path("expression").asText(), values,
                        d.path("total").asInt(), notes));
            }
            return details;
        }
        int index = 0;
        boolean doubled = expression.startsWith("(") && expression.endsWith(")*2");
        if (doubled) expression = expression.substring(1, expression.length() - 3);
        Matcher matcher = TERM.matcher(expression);
        while (matcher.find()) {
            String term = matcher.group(0);
            if (matcher.group(3) != null) {
                int count = matcher.group(2).isEmpty() ? 1 : Integer.parseInt(matcher.group(2));
                List<Integer> values = new ArrayList<>(rolls.subList(Math.min(index, rolls.size()),
                        Math.min(index + count, rolls.size())));
                if (duality && index == 0 && count == 2 && matcher.group(3).equals("12")) {
                    details.add(new Detail("Hope die", "1d12", values.subList(0, Math.min(1, values.size())),
                            values.isEmpty() ? 0 : values.get(0), List.of()));
                    details.add(new Detail("Fear die", "1d12",
                            values.size() > 1 ? values.subList(1, 2) : List.of(), values.size() > 1 ? values.get(1) : 0,
                            List.of()));
                } else {
                    boolean advantage = duality && index == 2 && count == 1 && matcher.group(3).equals("6");
                    String label = advantage ? ("-".equals(matcher.group(1)) ? "Disadvantage" : "Advantage")
                            : count + "d" + matcher.group(3) + " dice";
                    boolean[] kept = keptRolls(term, values);
                    int total = 0;
                    for (int i = 0; i < values.size(); i++) {
                        if (kept[i]) {
                            total += values.get(i);
                        }
                    }
                    details.add(new Detail(label, term, values, total, List.of()));
                }
                index += count;
            } else {
                details.add(new Detail("Modifier", term, List.of(), Integer.parseInt(term.replace("+", "")), List.of()));
            }
        }
        return doubled ? details.stream().map(d -> new Detail(d.label(), "(" + d.expression() + ")*2",
                d.rolls(), d.total() * 2, d.notes())).toList() : details;
    }

    public static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    public static RestReport parseRestReport(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2) {
            return null;
        }
        Matcher head = REST_HEAD.matcher(lines[0]);
        if (!head.matches()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            items.add(lines[i].replaceFirst("^\u2022 ?", ""));
        }
        return new RestReport("LONG".equals(head.group(1)), lines[1], items);
    }

    public static DamageReport parseDamageReport(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !"DAMAGE TAKEN".equals(lines[0])) {
            return null;
        }
        Matcher stats = DAMAGE_STATS.matcher(lines[2]);
        if (!stats.matches()) {
            return null;
        }
        int input = Integer.parseInt(stats.group(1));
        List<String> items = new ArrayList<>();
        for (int i = 3; i < lines.length; i++) {
            items.add(lines[i].replaceFirst("^\u2022 ?", ""));
        }
        return new DamageReport(lines[1], input, stats.group(3) == null ? input : Integer.parseInt(stats.group(3)),
                stats.group(2), stats.group(4), Integer.parseInt(stats.group(5)), items);
    }

    // ---- rendering ----

    public static Node render(JsonNode m) {
        return render(m, null);
    }

    /**
     * @param stock looks up the units a transfer offer still has; called off the JavaFX thread. May be null.
     */
    public static Node render(JsonNode m, ToIntFunction<JsonNode> stock) {
        return render(m, stock, null);
    }

    /** Adds a moderation button only when the DM view supplies a delete action. */
    public static Node render(JsonNode m, ToIntFunction<JsonNode> stock, Runnable delete) {
        boolean roll = "roll".equals(m.path("type").asText());
        String text = m.path("text").asText("");
        boolean whisper = !m.path("toId").asText("").isEmpty();
        boolean plain = !roll && !whisper && !m.has("sharedCard");
        RestReport rest = plain ? parseRestReport(text) : null;
        DamageReport damage = plain && rest == null ? parseDamageReport(text) : null;
        ActionReport action = plain && rest == null && damage == null ? parseActionReport(text) : null;

        VBox card = new VBox();
        card.getStyleClass().add("chat-entry");
        if (roll) card.getStyleClass().add("chat-roll");
        if (rest != null) card.getStyleClass().add("chat-rest");
        if (damage != null) card.getStyleClass().add("chat-damage");
        if (action != null) card.getStyleClass().add("chat-action");
        if (whisper) card.getStyleClass().add("chat-whisper");
        card.getChildren().add(header(m, delete));
        if (whisper) {
            Label label = new Label("Whisper to " + ("dm".equals(m.path("toId").asText()) ? "Gamemaster"
                    : m.path("to").asText(m.path("toId").asText())));
            label.getStyleClass().add("chat-whisper-label");
            VBox box = new VBox(label);
            box.setPadding(new Insets(8, 12, 0, 12));
            card.getChildren().add(box);
        }
        if (rest != null) {
            card.getChildren().add(restBody(rest));
            return card;
        }
        if (damage != null) {
            card.getChildren().add(damageBody(damage));
            return card;
        }
        if (action != null) {
            card.getChildren().add(actionBody(action));
            appendRollSource(card, m);
            return card;
        }
        if (roll) {
            card.getChildren().add(rollBody(m));
        } else if (m.has("sharedCard")) {
            Node shared = sharedCard(m.path("sharedCard"), stock);
            card.getChildren().add(shared);
            Object actor = shared.getProperties().get(STOCK_ACTOR);
            if (actor != null) {
                card.getProperties().put(STOCK_ACTOR, actor);
                card.getProperties().put(STOCK_REFRESH, shared.getProperties().get(STOCK_REFRESH));
            }
        } else {
            Label body = wrapped(text, "chat-text");
            card.getChildren().add(body);
        }
        appendRollSource(card, m);
        return card;
    }

    private static void appendRollSource(VBox card, JsonNode m) {
        JsonNode source = m.path("rollSource");
        if (source.isObject()) {
            VBox content = new VBox(4, wrapped(source.path("source").asText(), "chat-shared-source"),
                    wrapped(source.path("name").asText(), "chat-source-name"),
                    markdown(source.path("text").asText(), "chat-source-text"));
            content.getStyleClass().add("chat-source-content");
            Node details = disclosure("Source: " + source.path("name").asText(), content, "chat-roll-source");
            VBox.setMargin(details, new Insets(0, 14, 12, 14));
            card.getChildren().add(details);
        }
    }

    private static Node actionBody(ActionReport report) {
        Label banner = new Label(report.title().startsWith("Death move - ") ? "DEATH MOVE" : "ACTION USED");
        banner.getStyleClass().add("chat-action-banner");
        return new VBox(banner, wrapped(report.title(), "chat-action-title"), bullets(report.lines()));
    }

    private static Node header(JsonNode m, Runnable delete) {
        String from = "dm".equals(m.path("fromId").asText()) ? "Gamemaster" : m.path("from").asText("?");
        Label avatar = new Label(from.isEmpty() ? "?" : from.substring(0, 1).toUpperCase(Locale.ROOT));
        avatar.getStyleClass().add("chat-avatar");
        avatar.setMinSize(25, 25);
        avatar.setMaxSize(25, 25);
        avatar.setAlignment(Pos.CENTER);
        Label sender = new Label(from);
        sender.getStyleClass().add("chat-sender");
        HBox.setHgrow(sender, Priority.ALWAYS);
        sender.setMaxWidth(Double.MAX_VALUE);
        Label time = new Label(TIME.format(Instant.ofEpochMilli(m.path("ts").asLong()).atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("chat-time");
        HBox header = new HBox(8, avatar, sender, time);
        if (delete != null) {
            Button remove = Icons.button(org.kordamp.ikonli.materialdesign2.MaterialDesignD.DELETE_OUTLINE,
                    "Delete message for everyone", delete);
            remove.getStyleClass().add("chat-delete");
            remove.setAccessibleText("Delete message for everyone");
            remove.setFocusTraversable(true);
            header.getChildren().add(remove);
        }
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");
        return header;
    }

    private static Label wrapped(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static final Pattern MARKDOWN_INLINE = Pattern.compile(
            "\\*\\*\\*(.+?)\\*\\*\\*|\\*\\*(.+?)\\*\\*(?!\\*)|\\*(.+?)\\*|(?<![\\w])_(.+?)_(?![\\w])");

    static List<MarkdownRun> markdownRuns(String line) {
        List<MarkdownRun> runs = new ArrayList<>();
        Matcher matcher = MARKDOWN_INLINE.matcher(line);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last)
                runs.add(new MarkdownRun(line.substring(last, matcher.start()), false, false));
            boolean bold = matcher.group(1) != null || matcher.group(2) != null;
            boolean italic = matcher.group(1) != null || matcher.group(3) != null || matcher.group(4) != null;
            String content = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            for (MarkdownRun nested : markdownRuns(content))
                runs.add(new MarkdownRun(nested.text(), bold || nested.bold(), italic || nested.italic()));
            last = matcher.end();
        }
        if (last < line.length())
            runs.add(new MarkdownRun(line.substring(last), false, false));
        return runs;
    }

    record MarkdownRun(String text, boolean bold, boolean italic) {
    }

    static VBox markdown(String value, String styleClass) {
        VBox block = new VBox(3);
        block.getStyleClass().add(styleClass);
        for (String line : value.split("\\R", -1)) {
            if (line.isBlank()) {
                Region gap = new Region();
                gap.setMinHeight(4);
                block.getChildren().add(gap);
                continue;
            }
            TextFlow flow = new TextFlow();
            flow.setMinHeight(Region.USE_PREF_SIZE);
            String content = line.replaceFirst("^\\s*[-*] ", "\u2022 ");
            for (MarkdownRun run : markdownRuns(content)) {
                Text text = new Text(run.text());
                text.getStyleClass().add("chat-markdown-text");
                if (run.bold()) text.getStyleClass().add("chat-markdown-bold");
                if (run.italic()) text.getStyleClass().add("chat-markdown-italic");
                flow.getChildren().add(text);
            }
            block.getChildren().add(flow);
        }
        return block;
    }

    private static Node featureBlock(String name, String text) {
        VBox block = new VBox(4, wrapped(name, "chat-feature-name"), markdown(text, "chat-shared-description"));
        block.getStyleClass().add("chat-feature");
        return block;
    }

    private static Node restBody(RestReport report) {
        Label banner = new Label(report.longRest() ? "LONG REST" : "SHORT REST");
        banner.getStyleClass().add("chat-rest-banner");
        VBox box = new VBox(banner, wrapped(report.subtitle(), "chat-rest-subtitle"), bullets(report.lines()));
        return box;
    }

    private static Node bullets(List<String> lines) {
        VBox list = new VBox(3);
        list.getStyleClass().add("chat-bullets");
        for (String line : lines) {
            list.getChildren().add(wrapped("\u2022 " + line, "chat-bullet"));
        }
        return list;
    }

    private static Node damageBody(DamageReport r) {
        Label title = new Label("DAMAGE TAKEN");
        title.getStyleClass().add("chat-damage-banner");
        Label tag = new Label(r.type());
        tag.getStyleClass().addAll("chat-damage-tag", "chat-damage-" + r.type().toLowerCase(Locale.ROOT));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox banner = new HBox(8, title, spacer, tag);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(8, 12, 0, 12));

        Label hp = new Label(r.hp() > 0 ? "\u2212" + r.hp() : "0");
        hp.getStyleClass().add("chat-damage-hp-value");
        Label hpCaption = new Label("HP MARKED");
        hpCaption.getStyleClass().add("chat-damage-hp-caption");
        VBox hpBox = new VBox(hp, hpCaption);
        hpBox.setAlignment(Pos.CENTER);
        hpBox.getStyleClass().add(r.hp() > 0 ? "chat-damage-hp" : "chat-damage-hp-none");

        Label name = new Label(r.name());
        name.getStyleClass().add("chat-damage-name");
        String amountText = r.damage() != r.input() ? r.input() + " \u2192 " + r.damage() + " damage" : r.input() + " damage";
        Label amount = new Label(amountText);
        amount.getStyleClass().add("chat-damage-amount");
        Label severity = new Label(r.severity().toUpperCase(Locale.ROOT));
        severity.getStyleClass().add("Negated".equals(r.severity()) ? "chat-damage-severity-negated" : "chat-damage-severity");
        VBox facts = new VBox(2, name, amount, severity);
        facts.setAlignment(Pos.CENTER_LEFT);
        HBox hero = new HBox(12, hpBox, facts);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(8, 12, 10, 12));
        VBox box = new VBox(banner, hero);
        if (!r.lines().isEmpty()) {
            Node lines = bullets(r.lines());
            lines.getStyleClass().add("chat-damage-lines");
            box.getChildren().add(lines);
        }
        return box;
    }

    /** Node property holding the id of the actor whose offer a card shows. */
    public static final String STOCK_ACTOR = "stockActor";
    /** Node property holding a {@link Runnable} that re-reads the remaining units of the offer. */
    public static final String STOCK_REFRESH = "stockRefresh";
    private static final ExecutorService STOCK_READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-card-stock");
        thread.setDaemon(true);
        return thread;
    });

    private static Node sharedCard(JsonNode card, ToIntFunction<JsonNode> stock) {
        String category = card.path("category").asText();
        JsonNode item = card.path("item");
        List<String> badges = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        switch (category) {
            case "feature" -> {
                badges.add(item.path("source").asText());
                body.append(item.path("text").asText());
            }
            case "domain" -> {
                badges.add(item.path("domain").asText());
                badges.add("Level " + item.path("level").asText());
                badges.add(item.path("type").asText());
                badges.add("Recall " + item.path("recall").asText());
                body.append(item.path("text").asText());
            }
            case "weapon" -> {
                for (String f : new String[]{"slot", "damageType", "trait", "range", "damage"}) {
                    badges.add(item.path(f).asText());
                }
                badges.add(item.path("hands").asText() + "H");
                badges.add("Tier " + item.path("tier").asText());
                appendFeature(body, item.path("feature"));
            }
            case "armor" -> {
                badges.add("Armor score " + item.path("score").asText());
                badges.add("Tier " + item.path("tier").asText());
                if (item.has("major") && item.has("severe")) {
                    badges.add(item.path("major").asText() + " / " + item.path("severe").asText());
                }
                appendFeature(body, item.path("feature"));
            }
            case "gold" -> badges.add("coins");
            default -> {
                badges.add(item.path("type").asText());
                body.append(item.path("note").asText());
            }
        }
        if (card.path("badges").isArray()) {
            badges.clear();
            card.path("badges").forEach(badge -> badges.add(badge.asText()));
        }
        boolean offer = !"feature".equals(category) && !"domain".equals(category)
                && card.path("offerQuantity").asInt(0) > 0;
        String actor = card.path("actorName").asText();
        VBox root = new VBox(4);
        root.getStyleClass().addAll("chat-shared-card", "chat-shared-" + category);
        if (offer) root.getStyleClass().add("chat-transfer-offer");
        Label source = new Label(offer ? "Transfer offer from " + actor : actor);
        source.getStyleClass().add("chat-shared-source");
        Label name = wrapped(item.path("name").asText(), "chat-shared-name");
        FlowPane badgePane = new FlowPane(4, 4);
        for (String badge : badges) {
            if (!badge.isBlank()) {
                Label label = new Label(badge);
                label.getStyleClass().add("chat-badge");
                badgePane.getChildren().add(label);
            }
        }
        root.getChildren().addAll(source, name, badgePane);
        if (card.path("sections").isArray()) {
            for (JsonNode section : card.path("sections")) {
                String title = section.path("name").asText();
                root.getChildren().add(title.isBlank() ? markdown(section.path("text").asText(), "chat-shared-description")
                        : featureBlock(title, section.path("text").asText()));
            }
        } else if (("weapon".equals(category) || "armor".equals(category)) && item.path("feature").isObject()) {
            root.getChildren().add(featureBlock(item.path("feature").path("name").asText(),
                    item.path("feature").path("text").asText()));
        } else if (!body.isEmpty()) {
            root.getChildren().add(markdown(body.toString(), "chat-shared-description"));
        }
        if (offer) {
            int offered = card.path("offerQuantity").asInt();
            Label amount = new Label(stock == null ? String.valueOf(card.path("available").asInt()) : "\u2026");
            amount.getStyleClass().add("chat-shared-qty-number");
            amount.setMinWidth(Region.USE_PREF_SIZE);
            Label remaining = new Label(stock == null ? "remaining" : "checking stock");
            remaining.getStyleClass().add("chat-shared-remaining");
            Label offeredLabel = new Label("of " + offered + " offered");
            offeredLabel.getStyleClass().add("chat-shared-offered");
            HBox stockRow = new HBox(8, amount, new VBox(remaining, offeredLabel));
            stockRow.setAlignment(Pos.CENTER_LEFT);
            stockRow.getStyleClass().add("chat-shared-stock");
            if (stock == null && card.path("available").asInt() <= 0) {
                amount.getStyleClass().add("empty");
            }
            root.getChildren().add(stockRow);
            if (stock != null) {
                int[] request = {0};
                Runnable refresh = () -> {
                    int current = ++request[0];
                    STOCK_READER.execute(() -> {
                        int count = stock.applyAsInt(card);
                        Platform.runLater(() -> {
                            if (request[0] == current) {
                                amount.setText(String.valueOf(count));
                                remaining.setText("remaining");
                                amount.getStyleClass().remove("empty");
                                if (count <= 0) {
                                    amount.getStyleClass().add("empty");
                                }
                            }
                        });
                    });
                };
                root.getProperties().put(STOCK_ACTOR, card.path("actorId").asText());
                root.getProperties().put(STOCK_REFRESH, refresh);
                refresh.run();
            }
        }
        VBox.setMargin(root, new Insets(10));
        return root;
    }

    private static void appendFeature(StringBuilder body, JsonNode feature) {
        if (feature.isObject()) {
            body.append(feature.path("name").asText()).append('\n').append(feature.path("text").asText());
        }
    }

    private static Node rollBody(JsonNode m) {
        boolean secretToPlayers = m.has("secretReal");
        boolean hidden = m.path("secret").asBoolean(false) && !secretToPlayers;
        JsonNode real = secretToPlayers ? m.path("secretReal") : m;
        String expression = real.path("expression").asText();
        List<Integer> values = new ArrayList<>();
        real.path("rolls").forEach(v -> values.add(v.asInt()));
        int total = real.path("total").asInt();
        boolean duality = !hidden && ("duality".equals(m.path("kind").asText())
                || "risk".equals(m.path("deathMove").asText())) && values.size() >= 2;
        List<Detail> details = rollDetails(expression, values, duality, hidden ? null : m.path("breakdown"));
        boolean[] kept = hidden ? allTrue(values.size()) : keptRolls(expression, values);

        FlowPane dice = new FlowPane(5, 5);
        dice.setMinWidth(0);
        int index = 0;
        for (Detail detail : details) {
            Matcher sides = Pattern.compile("d(\\d+)", Pattern.CASE_INSENSITIVE).matcher(detail.expression());
            String sidesText = sides.find() ? sides.group(1) : "?";
            for (int value : detail.rolls()) {
                int n = index++;
                if (duality && n > 1) {
                    continue;
                }
                String name = duality ? (n == 0 ? "HOPE" : "FEAR") : "d" + sidesText;
                boolean discarded = n < kept.length && !kept[n];
                Label nameLabel = new Label(name);
                nameLabel.getStyleClass().add("chat-die-name");
                Label valueLabel = new Label(hidden ? "?" : String.valueOf(value));
                valueLabel.getStyleClass().add("chat-die-value");
                VBox tile = new VBox(nameLabel, valueLabel);
                tile.setAlignment(Pos.CENTER);
                tile.setMinSize(45, 56);
                tile.setPrefSize(45, 56);
                tile.getStyleClass().add("chat-die");
                if (discarded) tile.getStyleClass().add("chat-die-discarded");
                if (duality) tile.getStyleClass().add(n == 0 ? "chat-die-hope" : "chat-die-fear");
                if (hidden) tile.getStyleClass().add("chat-die-secret");
                dice.getChildren().add(tile);
            }
        }
        int kept_sum = 0;
        if (duality) {
            kept_sum = values.get(0) + values.get(1);
        } else {
            for (int i = 0; i < values.size(); i++) {
                if (kept[i]) kept_sum += values.get(i);
            }
        }
        boolean doubled = !hidden && expression.startsWith("(") && expression.endsWith(")*2");
        int modifier = total / (doubled ? 2 : 1) - kept_sum;
        HBox equation = new HBox(8);
        equation.setAlignment(Pos.CENTER_LEFT);
        dice.setMinWidth(0);
        HBox.setHgrow(dice, Priority.ALWAYS);
        equation.getChildren().add(dice);
        if (!hidden) {
            Label mod = new Label(signed(modifier));
            mod.getStyleClass().add(modifier < 0 ? "chat-roll-modifier-negative" : "chat-roll-modifier");
            mod.setMinWidth(Region.USE_PREF_SIZE);
            equation.getChildren().add(mod);
            if (doubled) {
                Label multiplier = new Label("x2 (all)");
                multiplier.getStyleClass().add("chat-roll-modifier");
                multiplier.setMinWidth(Region.USE_PREF_SIZE);
                equation.getChildren().add(multiplier);
            }
        }
        Label equals = new Label("=");
        equals.getStyleClass().add("chat-roll-equals");
        equals.setMinWidth(Region.USE_PREF_SIZE);
        Label totalCaption = new Label("TOTAL");
        totalCaption.getStyleClass().add("chat-total-caption");
        Label totalValue = new Label(hidden ? "?" : String.valueOf(total));
        totalValue.getStyleClass().add("chat-total-value");
        totalValue.setMinWidth(Region.USE_PREF_SIZE);
        VBox totalBox = new VBox(totalCaption, totalValue);
        totalBox.setAlignment(Pos.CENTER);
        totalBox.setMinWidth(Region.USE_PREF_SIZE);
        totalBox.getStyleClass().add("chat-total");
        equation.getChildren().addAll(equals, totalBox);

        String title = m.has("label") ? m.path("label").asText()
                : hidden || secretToPlayers ? "Secret roll" : duality ? "Duality roll" : "Dice roll";
        if (List.of(m.path("source").asText("").split(",")).contains("reaction")) title += " - Reaction roll";
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("chat-roll-title");
        VBox body = new VBox(12, titleLabel, equation);
        body.setPadding(new Insets(12));
        if (secretToPlayers) {
            Label note = new Label("Secret \u2013 players see ? instead of the numbers");
            note.getStyleClass().add("chat-secret-note");
            body.getChildren().add(note);
        }
        if (hidden) {
            return body;
        }
        if (duality) {
            String outcome = dualityOutcome(m, values);
            FlowPane badges = new FlowPane(5, 5);
            badges.getChildren().add(badge(switch (outcome) {
                case "crit" -> "Critical success";
                case "hope" -> "With Hope";
                default -> "With Fear";
            }, "chat-outcome-" + outcome));
            if (m.path("difficulty").isInt()) {
                int difficulty = m.path("difficulty").asInt();
                boolean success = "crit".equals(outcome) || total >= difficulty;
                badges.getChildren().add(badge((success ? "Success" : "Failure") + " / Difficulty " + difficulty,
                        success ? "chat-outcome-success" : "chat-outcome-failure"));
            }
            body.getChildren().add(badges);
        }
        if (m.path("featureRoll").isObject()) {
            body.getChildren().add(wrapped(m.path("featureRoll").path("consequence").asText(), "chat-feature-result"));
        }
        body.getChildren().add(breakdown(expression, details, total));
        return body;
    }

    static String dualityOutcome(JsonNode message, List<Integer> values) {
        if ("risk".equals(message.path("deathMove").asText())) {
            String outcome = message.path("deathResult").path("outcome").asText();
            if (List.of("hope", "fear", "crit").contains(outcome)) return outcome;
        }
        int hope = values.get(0);
        int fear = values.get(1);
        return hope == fear ? "crit" : switch (message.path("outcome").asText()) {
            case "hope" -> "hope";
            case "fear" -> "fear";
            default -> hope > fear ? "hope" : "fear";
        };
    }

    private static boolean[] allTrue(int size) {
        boolean[] result = new boolean[size];
        java.util.Arrays.fill(result, true);
        return result;
    }

    private static Label badge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("chat-outcome", styleClass);
        return label;
    }

    private static Node breakdown(String expression, List<Detail> details, int total) {
        VBox content = new VBox();
        Label formula = wrapped(expression, "chat-roll-formula");
        content.getChildren().add(formula);
        for (Detail d : details) {
            VBox source = new VBox(2, wrapped(d.label(), "chat-detail-label"));
            if (!d.rolls().isEmpty()) {
                source.getChildren().add(wrapped(d.expression().replaceFirst("^\\+", "") + ": " + d.rolls(),
                        "chat-detail-small"));
            }
            HBox.setHgrow(source, Priority.ALWAYS);
            Label value = new Label(signed(d.total()));
            value.getStyleClass().add(d.total() < 0 ? "chat-detail-negative" : "chat-detail-value");
            content.getChildren().add(detailRow(source, value, "chat-detail"));
            for (Note note : d.notes()) {
                Label noteLabel = wrapped(note.label(), "chat-detail-note");
                HBox.setHgrow(noteLabel, Priority.ALWAYS);
                content.getChildren().add(detailRow(noteLabel, new Label(note.impact()), "chat-detail-note-row"));
            }
        }
        Label result = new Label("Result");
        HBox.setHgrow(result, Priority.ALWAYS);
        result.setMaxWidth(Double.MAX_VALUE);
        content.getChildren().add(detailRow(result, new Label(String.valueOf(total)), "chat-detail-total"));
        return disclosure("Roll breakdown", content, "chat-roll-breakdown");
    }

    private static HBox detailRow(Node left, Label right, String styleClass) {
        HBox row = new HBox(12, left, right);
        right.setMinWidth(Region.USE_PREF_SIZE);
        if (left instanceof Region region) {
            region.setMinWidth(0);
            HBox.setHgrow(region, Priority.ALWAYS);
        }
        row.setAlignment(Pos.BASELINE_LEFT);
        row.getStyleClass().addAll("chat-detail-row", styleClass);
        return row;
    }

    /** A collapsible block like the browser's {@code <details>} element. */
    static Node disclosure(String title, Node content, String styleClass) {
        Label summary = new Label("\u25B8 " + title);
        summary.getStyleClass().add("chat-summary");
        summary.setWrapText(true);
        summary.setMaxWidth(Double.MAX_VALUE);
        content.setVisible(false);
        content.setManaged(false);
        summary.setOnMouseClicked(e -> {
            boolean open = !content.isVisible();
            content.setVisible(open);
            content.setManaged(open);
            summary.setText((open ? "\u25BE " : "\u25B8 ") + title);
        });
        VBox box = new VBox(summary, content);
        box.getStyleClass().addAll("chat-disclosure", styleClass);
        return box;
    }
}
