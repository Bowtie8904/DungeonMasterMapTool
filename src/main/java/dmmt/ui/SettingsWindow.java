package dmmt.ui;

import dmmt.service.AppSettings;
import dmmt.service.AppSettings.SettingInfo;
import dmmt.service.AppSettings.SidebarSection;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Window with every setting that has no control in the DM controls panel. Settings are grouped by category and can
 * be found with the search field; each change is written to the settings file and applied immediately.
 */
public final class SettingsWindow {
    private static final String TABS_CATEGORY = "DM controls tabs";

    private static Stage open;

    private record Row(String category, String group, String searchText, Node node) {
    }

    private final AppSettings settings;
    private final Runnable onSectionsChanged;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, CheckBox> tabBoxes = new LinkedHashMap<>();
    private final Map<String, CheckBox> controlBoxes = new LinkedHashMap<>();
    private final ListView<String> categories = new ListView<>();
    private final TextField search = new TextField();
    private final StackPane holder = new StackPane();
    private final Map<String, Boolean> expandedGroups = new java.util.HashMap<>();
    private final Label summary = new Label();

    private SettingsWindow(AppSettings settings, Runnable onSectionsChanged) {
        this.settings = settings;
        this.onSectionsChanged = onSectionsChanged;
    }

    /** Opens the settings window, or brings it to the front if it is already open. */
    public static void show(Window owner, AppSettings settings, Runnable onSectionsChanged) {
        if (open != null) {
            open.toFront();
            open.requestFocus();
            return;
        }
        SettingsWindow window = new SettingsWindow(settings, onSectionsChanged);
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Settings");
        if (owner instanceof Stage ownerStage) {
            stage.getIcons().setAll(ownerStage.getIcons());
        }
        stage.setScene(window.createScene(stage));
        stage.setOnHidden(e -> open = null);
        open = stage;
        stage.show();
        Platform.runLater(window.search::requestFocus);
    }

    private Scene createScene(Stage stage) {
        buildRows();

        List<String> names = new ArrayList<>();
        for (Row row : rows) {
            if (!names.contains(row.category())) {
                names.add(row.category());
            }
        }
        categories.getItems().setAll(names);
        categories.setPrefWidth(190);
        categories.setMinWidth(190);
        categories.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> render());

        search.setPromptText("Search settings by name, key or description…");
        HBox.setHgrow(search, Priority.ALWAYS);
        Button clear = Icons.button(MaterialDesignC.CLOSE, "Clear the search", () -> search.clear());
        // Rebuilding the result list is expensive, so it only runs once typing pauses.
        javafx.animation.PauseTransition searchDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        searchDelay.setOnFinished(e -> render());
        search.textProperty().addListener((obs, was, now) -> {
            categories.setDisable(!now.isBlank());
            if (now.isBlank()) {
                searchDelay.stop();
                render();
            } else {
                searchDelay.playFromStart();
            }
        });
        HBox searchRow = new HBox(8, Icons.icon(MaterialDesignM.MAGNIFY), search, clear);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("search-row");

        summary.getStyleClass().add("muted");
        Label hint = new Label("Changes are saved to dmmt-settings.ini and applied immediately, except entries marked "
                + "\"restart required\". Hover a setting for details.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        VBox.setVgrow(holder, Priority.ALWAYS);

        VBox right = new VBox(6, hint, summary, holder);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(10, categories, right);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(10, searchRow, body);
        root.setPadding(new Insets(12));
        root.getStyleClass().add("settings-window");

        categories.getSelectionModel().selectFirst();
        Scene scene = new Scene(root, 940, 680);
        scene.getStylesheets().add(Icons.STYLESHEET);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            } else if (e.isControlDown() && e.getCode() == KeyCode.F) {
                search.requestFocus();
                search.selectAll();
            }
        });
        return scene;
    }

    // ---- rows ----

    private void buildRows() {
        Set<String> hidden = settings.hiddenSections();
        Set<String> hiddenControls = settings.hiddenControls();
        for (SidebarSection section : AppSettings.SIDEBAR_SECTIONS) {
            CheckBox box = new CheckBox("Show \"" + section.title() + "\"");
            box.setSelected(!hidden.contains(section.id()));
            box.setOnAction(e -> {
                settings.setHiddenSections(tabBoxes.entrySet().stream().filter(t -> !t.getValue().isSelected())
                        .map(Map.Entry::getKey).collect(Collectors.toSet()));
                onSectionsChanged.run();
            });
            tabBoxes.put(section.id(), box);
            Label description = description(section.description() + " Hidden tabs disappear from the DM controls overlay "
                    + "(the settings stay unchanged).");
            VBox node = new VBox(3, box, description);
            node.getStyleClass().add("settings-row");
            Icons.tooltip(box, "Show or hide the \"" + section.title() + "\" tab of the DM controls.\nSetting: "
                    + AppSettings.HIDDEN_SECTIONS_KEY + " (" + section.id() + ")");
            rows.add(new Row(TABS_CATEGORY, null, (TABS_CATEGORY + " tab show hide sidebar overlay " + section.title() + " "
                    + section.description() + " " + AppSettings.HIDDEN_SECTIONS_KEY).toLowerCase(Locale.ROOT), node));
            for (AppSettings.SidebarControl control : AppSettings.SIDEBAR_CONTROLS) {
                if (!control.sectionId().equals(section.id())) {
                    continue;
                }
                CheckBox controlBox = new CheckBox("Show " + control.label());
                controlBox.setSelected(!hiddenControls.contains(control.id()));
                controlBox.setOnAction(e -> {
                    settings.setHiddenControls(controlBoxes.entrySet().stream().filter(c -> !c.getValue().isSelected())
                            .map(Map.Entry::getKey).collect(Collectors.toSet()));
                    onSectionsChanged.run();
                });
                controlBoxes.put(control.id(), controlBox);
                Icons.tooltip(controlBox, "Show or hide this control without changing its value or keyboard shortcuts.\nSetting: "
                        + AppSettings.HIDDEN_CONTROLS_KEY + " (" + control.id() + ")");
                VBox controlNode = new VBox(controlBox);
                controlNode.getStyleClass().add("settings-row");
                rows.add(new Row(TABS_CATEGORY, section.title() + " controls",
                        (TABS_CATEGORY + " show hide individual control sidebar overlay " + section.title() + " "
                                + control.label() + " " + control.id() + " " + AppSettings.HIDDEN_CONTROLS_KEY)
                                .toLowerCase(Locale.ROOT), controlNode));
            }
        }
        for (SettingInfo info : AppSettings.editableSettings()) {
            rows.add(new Row(info.category(), info.group(), (info.category() + " " + info.group() + " " + info.key() + " "
                    + info.label() + " " + info.description() + " " + info.keywords()).toLowerCase(Locale.ROOT), settingRow(info)));
        }
    }

    private static Label description(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    private Node settingRow(SettingInfo info) {
        Label title = new Label(info.label());
        title.getStyleClass().add("settings-title");
        Label key = new Label(info.key() + (info.restart() ? "  ·  restart required" : ""));
        key.getStyleClass().add("settings-key");
        VBox names = new VBox(1, title, key);
        HBox.setHgrow(names, Priority.ALWAYS);
        names.setMinWidth(180);

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);

        Button reset = Icons.button(MaterialDesignB.BACKUP_RESTORE, "Restore the default (" + info.defaultValue() + ")", null);
        reset.setFocusTraversable(false);

        String current = settings.get(info.key(), info.defaultValue());
        Editor editor = createEditor(info, current, error);
        reset.setDisable(sameValue(info, current, info.defaultValue()));
        editor.onCommitted = value -> {
            boolean isDefault = sameValue(info, value, info.defaultValue());
            settings.applyEdit(info.key(), isDefault ? null : value);
            reset.setDisable(isDefault);
            if (info.key().startsWith("api.")) {
                onSectionsChanged.run();
            }
        };
        reset.setOnAction(e -> {
            editor.set(info.defaultValue());
            settings.applyEdit(info.key(), null);
            error.setVisible(false);
            error.setManaged(false);
            reset.setDisable(true);
            if (info.key().startsWith("api.")) {
                onSectionsChanged.run();
            }
        });

        String tip = info.description() + "\nDefault: " + (info.defaultValue().isEmpty() ? "(empty)" : info.defaultValue())
                + "\nKey: " + info.key();
        Icons.tooltip(title, tip);
        Icons.tooltip(key, tip);
        Icons.tooltip(editor.control, tip);

        HBox top = new HBox(10, names, editor.control, reset);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(3, top, description(info.description()), error);
        row.getStyleClass().add("settings-row");
        return row;
    }

    private static boolean sameValue(SettingInfo info, String a, String b) {
        if (a.trim().equalsIgnoreCase(b.trim())) {
            return true;
        }
        if (info.kind() == Tuning.Kind.INTEGER || info.kind() == Tuning.Kind.DECIMAL) {
            try {
                return Double.parseDouble(a.trim().replace(',', '.')) == Double.parseDouble(b.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** The control of one setting together with the code to push a new value into it. */
    private static final class Editor {
        final Control control;
        final java.util.function.Consumer<String> setter;
        java.util.function.Consumer<String> onCommitted = value -> {
        };
        boolean updating;

        Editor(Control control, java.util.function.Consumer<String> setter) {
            this.control = control;
            this.setter = setter;
        }

        void set(String value) {
            updating = true;
            try {
                setter.accept(value);
            } finally {
                updating = false;
            }
        }

        void commit(String value) {
            if (!updating) {
                onCommitted.accept(value);
            }
        }
    }

    private Editor createEditor(SettingInfo info, String current, Label error) {
        switch (info.kind()) {
            case BOOLEAN: {
                CheckBox box = new CheckBox();
                box.setSelected("true".equalsIgnoreCase(current.trim()));
                Editor editor = new Editor(box, v -> box.setSelected("true".equalsIgnoreCase(v.trim())));
                box.setOnAction(e -> editor.commit(String.valueOf(box.isSelected())));
                return editor;
            }
            case COLOR: {
                ColorPicker picker = new ColorPicker(parseColor(current, info.defaultValue()));
                picker.setPrefWidth(150);
                Editor editor = new Editor(picker, v -> picker.setValue(parseColor(v, info.defaultValue())));
                picker.setOnAction(e -> editor.commit(formatColor(picker.getValue())));
                return editor;
            }
            case CHOICE: {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().setAll(info.options());
                combo.setValue(info.options().stream().filter(o -> o.equalsIgnoreCase(current.trim())).findFirst()
                        .orElse(info.defaultValue()));
                combo.setPrefWidth(150);
                Editor editor = new Editor(combo, v -> combo.setValue(v));
                combo.setOnAction(e -> {
                    if (combo.getValue() != null) {
                        editor.commit(combo.getValue());
                    }
                });
                return editor;
            }
            case INTEGER:
            case DECIMAL:
                return numberEditor(info, current);
            default: {
                TextField field = new TextField(current);
                field.setPrefWidth(300);
                field.setMinWidth(220);
                Editor editor = new Editor(field, field::setText);
                Runnable commit = () -> {
                    if (editor.updating) {
                        return;
                    }
                    String text = field.getText().trim();
                    if (!info.validator().test(text)) {
                        error.setText("Not a valid value. See the description above for the format.");
                        error.setVisible(true);
                        error.setManaged(true);
                        field.setStyle("-fx-border-color: #ff7b6b; -fx-border-radius: 4;");
                        return;
                    }
                    error.setVisible(false);
                    error.setManaged(false);
                    field.setStyle(null);
                    editor.commit(text);
                };
                field.setOnAction(e -> commit.run());
                field.focusedProperty().addListener((obs, was, focused) -> {
                    if (!focused && !field.getText().trim().equals(settings.get(info.key(), info.defaultValue()).trim())) {
                        commit.run();
                    }
                });
                return editor;
            }
        }
    }

    private Editor numberEditor(SettingInfo info, String current) {
        boolean integer = info.kind() == Tuning.Kind.INTEGER;
        double def = parseNumber(info.defaultValue(), 0);
        double min = Double.isNaN(info.min()) ? -1e6 : info.min();
        double max = Double.isNaN(info.max()) ? 1e6 : info.max();
        double range = max - min;
        double step;
        if (integer) {
            step = range > 1e5 ? 1000 : range > 1e4 ? 100 : range > 1e3 ? 10 : 1;
        } else if (Double.isNaN(info.min()) || Double.isNaN(info.max())) {
            step = Math.abs(def) <= 1 ? 0.05 : 0.5;
        } else {
            step = range <= 1.0001 ? 0.01 : range <= 10 ? 0.1 : range <= 100 ? 1 : 10;
        }
        SpinnerValueFactory.DoubleSpinnerValueFactory factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(
                min, max, Math.max(min, Math.min(max, parseNumber(current, def))), step);
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : formatDecimal(Math.round(value * 1e6) / 1e6);
            }

            @Override
            public Double fromString(String text) {
                return parseNumber(text, factory.getValue());
            }
        });
        Spinner<Double> spinner = new Spinner<>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(125);
        Editor editor = new Editor(spinner, v -> {
            factory.setValue(Math.max(min, Math.min(max, parseNumber(v, def))));
            spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
        });
        factory.valueProperty().addListener((obs, was, now) -> {
            if (now != null) {
                editor.commit(integer ? String.valueOf(Math.round(now)) : formatDecimal(Math.round(now * 1e6) / 1e6));
            }
        });
        Runnable commitText = () -> {
            double value = Math.max(min, Math.min(max, parseNumber(spinner.getEditor().getText(), factory.getValue())));
            factory.setValue(integer ? Math.round(value) : value);
            spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
        };
        spinner.getEditor().setOnAction(e -> commitText.run());
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitText.run();
            }
        });
        return editor;
    }

    private static double parseNumber(String text, double fallback) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
    private static String formatDecimal(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Color parseColor(String text, String fallback) {
        try {
            return Color.web(text.trim());
        } catch (RuntimeException e) {
            return Color.web(fallback);
        }
    }

    private static String formatColor(Color color) {
        String rgb = String.format("#%02X%02X%02X", Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
        return color.getOpacity() < 0.999 ? rgb + String.format("%02X", Math.round(color.getOpacity() * 255)) : rgb;
    }

    // ---- filtering ----

    private void render() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            renderCategory(categories.getSelectionModel().getSelectedItem());
        } else {
            renderSearch(query);
        }
    }

    private void renderCategory(String category) {
        List<Row> inCategory = rows.stream().filter(r -> r.category().equals(category)).toList();
        summary.setText(inCategory.size() + (inCategory.size() == 1 ? " setting" : " settings") + " in \"" + category + "\"");
        holder.getChildren().setAll(scrollOf(level(inCategory, 0, category)));
    }

    private static ScrollPane scrollOf(Node node) {
        ScrollPane scroll = new ScrollPane(node);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private static String[] path(Row row) {
        return row.group() == null ? new String[0] : row.group().split("/");
    }

    /**
     * One level of the group tree: settings that belong directly to this level first, then one collapsible block
     * (collapsed by default) per sub-group.
     */
    private VBox level(List<Row> levelRows, int depth, String key) {
        VBox box = new VBox(6);
        box.setPadding(depth == 0 ? new Insets(6, 12, 12, 6) : Insets.EMPTY);
        for (Row row : levelRows) {
            if (path(row).length <= depth) {
                box.getChildren().add(row.node());
            }
        }
        List<String> groups = new ArrayList<>();
        for (Row row : levelRows) {
            if (path(row).length > depth && !groups.contains(path(row)[depth])) {
                groups.add(path(row)[depth]);
            }
        }
        for (String group : groups) {
            List<Row> inGroup = levelRows.stream().filter(r -> path(r).length > depth && path(r)[depth].equals(group)).toList();
            box.getChildren().add(groupBlock(key + "/" + group, group, inGroup.size(), level(inGroup, depth + 1, key + "/" + group)));
        }
        return box;
    }
    private VBox groupBlock(String key, String title, int count, VBox inner) {
        inner.setPadding(new Insets(4, 0, 4, 14));
        FontIcon chevron = Icons.icon(MaterialDesignC.CHEVRON_RIGHT);
        Label name = new Label(title + "  (" + count + ")");
        name.getStyleClass().add("settings-group-title");
        HBox header = new HBox(6, chevron, name);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("settings-group-header");
        Runnable apply = () -> {
            boolean open = expandedGroups.getOrDefault(key, false);
            inner.setVisible(open);
            inner.setManaged(open);
            chevron.setIconCode(open ? MaterialDesignC.CHEVRON_DOWN : MaterialDesignC.CHEVRON_RIGHT);
        };
        header.setOnMouseClicked(e -> {
            expandedGroups.put(key, !expandedGroups.getOrDefault(key, false));
            apply.run();
        });
        apply.run();
        return new VBox(2, header, inner);
    }

    private void renderSearch(String query) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(6, 12, 12, 6));
        String[] terms = query.split("\\s+");
        String lastPath = null;
        int count = 0;
        for (Row row : rows) {
            boolean match = true;
            for (String term : terms) {
                if (!row.searchText().contains(term)) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            String path = row.group() == null ? row.category() : row.category() + " › " + row.group().replace("/", " › ");
            if (!path.equals(lastPath)) {
                Label header = new Label(path);
                header.getStyleClass().add("settings-category");
                box.getChildren().add(header);
                lastPath = path;
            }
            box.getChildren().add(row.node());
            count++;
        }
        summary.setText(count == 0 ? "No settings match \"" + search.getText().trim() + "\"."
                : count + (count == 1 ? " setting found" : " settings found"));
        holder.getChildren().setAll(scrollOf(box));
    }
}