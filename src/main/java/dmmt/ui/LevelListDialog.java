package dmmt.ui;

import dmmt.service.MapLibraryService;
import dmmt.service.MultiLevelService;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Edits the level list of a multilevel map (lowest level at the top): reorder, rename, remove and insert levels from
 * dd2vtt files, library maps or as empty levels. Nothing is changed on disk; the result is a level plan.
 */
public final class LevelListDialog {

    /** One row of the list. {@code detail} is a muted hint (source file, folder, "open"). */
    public static final class Row {
        private final MultiLevelService.Source source;
        private String name;
        private final String detail;

        public Row(MultiLevelService.Source source, String name, String detail) {
            this.source = source;
            this.name = name;
            this.detail = detail;
        }

        public MultiLevelService.Source source() {
            return source;
        }

        public String name() {
            return name;
        }

        boolean existing() {
            return source instanceof MultiLevelService.Existing;
        }
    }

    private LevelListDialog() {
    }

    /**
     * @param managing      {@code true} for an existing multilevel map: removing every level is allowed (after a
     *                      confirmation it deletes the map and the dialog closes with an empty plan)
     * @param dd2vttPicker  asks for dd2vtt files (system file chooser), empty if cancelled
     */
    public static Optional<List<MultiLevelService.PlanItem>> show(Window owner, MapLibraryService library, String title,
                                                                  String header, Ikon ikon, String actionLabel,
                                                                  List<Row> initial, boolean managing,
                                                                  Function<Window, List<Path>> dd2vttPicker) {
        Dialog<List<MultiLevelService.PlanItem>> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, title, header, ikon, false);

        ListView<Row> list = new ListView<>();
        list.getItems().setAll(initial);
        list.getStyleClass().add("level-list");
        list.setPrefSize(380, 320);
        list.setCellFactory(view -> new RowCell());

        Label lowest = new Label("Lowest level");
        lowest.getStyleClass().add("caption");
        Label highest = new Label("Highest level");
        highest.getStyleClass().add("caption");
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);

        Window[] dialogWindow = new Window[1];
        boolean[] deleteWholeMap = new boolean[1];

        Runnable moveUp = () -> move(list, -1);
        Runnable moveDown = () -> move(list, 1);
        Runnable rename = () -> {
            Row row = list.getSelectionModel().getSelectedItem();
            if (row == null) {
                return;
            }
            Optional<String> name = Dialogs.askText(dialogWindow[0], "Rename level", "Rename \"" + row.name + "\"",
                    MaterialDesignR.RENAME_BOX, "Rename", row.name, MultiLevelService::levelNameProblem);
            name.ifPresent(value -> {
                row.name = value.trim();
                list.refresh();
            });
        };
        Runnable remove = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (index < 0) {
                return;
            }
            if (managing && list.getItems().size() == 1) {
                boolean confirmed = Dialogs.confirmDanger(dialogWindow[0], "Delete multilevel map",
                        "Remove the last level?", "A multilevel map without levels is deleted. The whole map "
                                + "and all of its images and saved state will be permanently deleted from disk."
                                + "\n\nThis cannot be undone.", "Delete map");
                if (confirmed) {
                    deleteWholeMap[0] = true;
                    dialog.setResult(List.of());
                    dialog.close();
                }
                return;
            }
            list.getItems().remove(index);
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(Math.min(index, list.getItems().size() - 1));
            }
        };
        Runnable addFiles = () -> {
            List<Path> files = dd2vttPicker.apply(dialogWindow[0]);
            if (files == null || files.isEmpty()) {
                return;
            }
            List<Path> sorted = new ArrayList<>(files);
            sorted.sort((a, b) -> MultiLevelService.NATURAL_ORDER.compare(stem(a), stem(b)));
            List<String> names = MultiLevelService.defaultLevelNames(sorted.stream().map(LevelListDialog::stem).toList());
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                rows.add(new Row(new MultiLevelService.Dd2vtt(sorted.get(i)), names.get(i),
                        "Import " + sorted.get(i).getFileName()));
            }
            insert(list, rows);
        };
        Runnable addLibraryMaps = () -> {
            Set<Path> taken = new HashSet<>();
            for (Row row : list.getItems()) {
                if (row.source instanceof MultiLevelService.LibraryMap map) {
                    taken.add(map.mapFile().toAbsolutePath().normalize());
                }
            }
            List<MapLibraryService.Entry> picked = LibraryMapPicker.show(dialogWindow[0], library, taken);
            if (picked.isEmpty()) {
                return;
            }
            List<MapLibraryService.Entry> sorted = new ArrayList<>(picked);
            sorted.sort((a, b) -> MultiLevelService.NATURAL_ORDER.compare(a.name(), b.name()));
            List<Row> rows = new ArrayList<>();
            for (MapLibraryService.Entry entry : sorted) {
                rows.add(libraryRow(library, entry, entry.name()));
            }
            insert(list, rows);
        };
        Runnable addEmpty = () -> insert(list, List.of(new Row(new MultiLevelService.Empty(),
                "Level " + (list.getItems().size() + 1), "New empty level")));

        Button up = Icons.button(MaterialDesignC.CHEVRON_UP, "Move up in the list (one level lower) — Alt+Up", moveUp);
        Button down = Icons.button(MaterialDesignC.CHEVRON_DOWN, "Move down in the list (one level higher) — Alt+Down", moveDown);
        Button renameButton = Icons.button(MaterialDesignR.RENAME_BOX, "Rename the selected level (F2 or double-click)", rename);
        Button removeButton = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Remove the selected level (Delete)", remove);
        Button files = new Button("dd2vtt files…", Icons.icon(MaterialDesignF.FILE_IMPORT_OUTLINE));
        Icons.tooltip(files, "Import .dd2vtt / .uvtt files as new levels below the selected level");
        files.setOnAction(event -> addFiles.run());
        Button maps = new Button("Library maps…", Icons.icon(MaterialDesignM.MAP_OUTLINE));
        Icons.tooltip(maps, "Move maps from your library in as new levels below the selected level "
                + "(they disappear as separate maps)");
        maps.setOnAction(event -> addLibraryMaps.run());
        Button empty = new Button("Empty level", Icons.icon(MaterialDesignP.PLUS_BOX_OUTLINE));
        Icons.tooltip(empty, "Add an empty level below the selected level (drop images onto it later)");
        empty.setOnAction(event -> addEmpty.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label addLabel = new Label("ADD");
        addLabel.getStyleClass().add("caption");
        HBox editRow = new HBox(4, up, down, renameButton, removeButton);
        editRow.getStyleClass().add("toolbar-row");
        HBox addRow = new HBox(6, addLabel, files, maps, empty);
        addRow.setStyle("-fx-alignment: center-left;");
        Label hint = new Label("New levels are inserted below the selected level.");
        hint.getStyleClass().add("muted");
        VBox content = new VBox(6, editRow, lowest, list, highest, addRow, hint, error);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);

        Runnable updateButtons = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            up.setDisable(index <= 0);
            down.setDisable(index < 0 || index >= list.getItems().size() - 1);
            renameButton.setDisable(index < 0);
            removeButton.setDisable(index < 0);
            error.setText("");
        };
        list.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> updateButtons.run());
        list.getItems().addListener((javafx.collections.ListChangeListener<Row>) change -> updateButtons.run());
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null) {
                rename.run();
            }
        });
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.F2) {
                rename.run();
            } else if (event.getCode() == KeyCode.DELETE) {
                remove.run();
            } else if (event.isAltDown() && event.getCode() == KeyCode.UP) {
                moveUp.run();
            } else if (event.isAltDown() && event.getCode() == KeyCode.DOWN) {
                moveDown.run();
            } else {
                return;
            }
            event.consume();
        });

        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
        actionButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (list.getItems().isEmpty()) {
                error.setText(managing ? "Remove the last level with the remove button to delete the whole map."
                        : "Add at least one level.");
                event.consume();
                return;
            }
            if (managing) {
                Set<String> kept = new HashSet<>();
                for (Row row : list.getItems()) {
                    if (row.source instanceof MultiLevelService.Existing existing) {
                        kept.add(existing.levelId());
                    }
                }
                List<String> removed = initial.stream()
                        .filter(row -> row.source instanceof MultiLevelService.Existing existing
                                && !kept.contains(existing.levelId()))
                        .map(row -> row.name).toList();
                if (!removed.isEmpty() && !Dialogs.confirmDanger(dialogWindow[0], "Delete levels",
                        "Delete " + removed.size() + (removed.size() == 1 ? " level?" : " levels?"),
                        "These levels and all of their images and saved state will be permanently deleted:\n\n• "
                                + String.join("\n• ", removed) + "\n\nThis cannot be undone.", "Delete")) {
                    event.consume();
                }
            }
        });
        dialog.setResultConverter(buttonType -> {
            if (deleteWholeMap[0]) {
                return List.of();
            }
            if (buttonType != action) {
                return null;
            }
            List<MultiLevelService.PlanItem> plan = new ArrayList<>();
            for (Row row : list.getItems()) {
                plan.add(new MultiLevelService.PlanItem(row.source, row.name));
            }
            return plan;
        });
        Platform.runLater(() -> {
            dialogWindow[0] = dialog.getDialogPane().getScene().getWindow();
            list.requestFocus();
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
            }
            updateButtons.run();
        });
        updateButtons.run();
        return dialog.showAndWait();
    }

    /** A row for a library map that is moved in as a level. */
    public static Row libraryRow(MapLibraryService library, MapLibraryService.Entry entry, String levelName) {
        return new Row(new MultiLevelService.LibraryMap(entry.path(), entry.mapFile()), levelName,
                "Map " + libraryPath(library, entry));
    }

    static String libraryPath(MapLibraryService library, MapLibraryService.Entry entry) {
        Path folder = entry.containingFolder();
        String relative = library.getRoot().relativize(folder.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return "Library/" + (relative.isEmpty() ? "" : relative + "/") + entry.name();
    }

    public static String stem(Path file) {
        return MapLibraryService.stripExtension(file.getFileName().toString());
    }

    private static void move(ListView<Row> list, int delta) {
        int index = list.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= list.getItems().size()) {
            return;
        }
        Row row = list.getItems().remove(index);
        list.getItems().add(target, row);
        list.getSelectionModel().clearAndSelect(target);
        list.scrollTo(Math.max(0, target - 2));
    }

    private static void insert(ListView<Row> list, List<Row> rows) {
        int index = list.getSelectionModel().getSelectedIndex();
        int at = index < 0 ? list.getItems().size() : index + 1;
        list.getItems().addAll(at, rows);
        list.getSelectionModel().clearAndSelect(at + rows.size() - 1);
        list.scrollTo(Math.max(0, at - 1));
    }

    private static final class RowCell extends ListCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label number = new Label((getIndex() + 1) + ".");
            number.getStyleClass().add("muted");
            number.setMinWidth(24);
            Label name = new Label(row.name);
            Label detail = new Label(row.detail == null ? "" : row.detail);
            detail.getStyleClass().add("muted");
            detail.setMinWidth(0);
            HBox.setHgrow(detail, Priority.ALWAYS);
            HBox box = new HBox(8, number, Icons.icon(row.existing() ? MaterialDesignL.LAYERS_OUTLINE
                    : row.source instanceof MultiLevelService.Dd2vtt ? MaterialDesignF.FILE_IMPORT_OUTLINE
                    : row.source instanceof MultiLevelService.LibraryMap ? MaterialDesignM.MAP_OUTLINE
                    : MaterialDesignP.PLUS_BOX_OUTLINE), name, detail);
            box.setStyle("-fx-alignment: center-left;");
            setText(null);
            setGraphic(box);
        }
    }

    /** Multi-select list of the ordinary maps of the library (multilevel maps cannot become levels). */
    static final class LibraryMapPicker {
        private LibraryMapPicker() {
        }

        static List<MapLibraryService.Entry> show(Window owner, MapLibraryService library, Set<Path> excluded) {
            List<MapLibraryService.Entry> all = new ArrayList<>();
            try {
                collect(library.scan(), excluded, all);
            } catch (IOException exception) {
                Dialogs.error(owner, "Could not read the map library", exception.getMessage());
                return List.of();
            }
            all.sort((a, b) -> MultiLevelService.NATURAL_ORDER.compare(libraryPath(library, a), libraryPath(library, b)));

            Dialog<List<MapLibraryService.Entry>> dialog = new Dialog<>();
            Dialogs.style(dialog, owner, "Add library maps", "Choose maps to move into the multilevel map",
                    MaterialDesignM.MAP_OUTLINE, false);
            TextField search = new TextField();
            search.setPromptText("Search maps");
            ListView<MapLibraryService.Entry> list = new ListView<>();
            list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            list.setPrefSize(420, 320);
            list.setPlaceholder(new Label(all.isEmpty() ? "No other maps in the library" : "No maps found"));
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(MapLibraryService.Entry entry, boolean empty) {
                    super.updateItem(entry, empty);
                    if (empty || entry == null) {
                        setText(null);
                        setGraphic(null);
                        return;
                    }
                    Label name = new Label(entry.name());
                    Label path = new Label(libraryPath(library, entry));
                    path.getStyleClass().add("muted");
                    setText(null);
                    setGraphic(new VBox(0, name, path));
                }
            });
            list.getItems().setAll(all);
            search.textProperty().addListener((observable, oldValue, text) -> {
                String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
                list.getItems().setAll(all.stream()
                        .filter(entry -> needle.isEmpty()
                                || libraryPath(library, entry).toLowerCase(Locale.ROOT).contains(needle))
                        .toList());
            });
            Label hint = new Label("Ctrl/Shift+click to choose several maps. They are moved into the multilevel map "
                    + "and disappear as separate maps.");
            hint.getStyleClass().add("muted");
            hint.setWrapText(true);
            VBox content = new VBox(6, search, list, hint);
            content.setPrefWidth(440);
            dialog.getDialogPane().setContent(content);
            ButtonType action = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
            Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
            actionButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
            list.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                        && list.getSelectionModel().getSelectedItem() != null) {
                    actionButton.fire();
                }
            });
            dialog.setResultConverter(buttonType -> buttonType == action
                    ? List.copyOf(list.getSelectionModel().getSelectedItems()) : null);
            Platform.runLater(search::requestFocus);
            return dialog.showAndWait().orElse(List.of());
        }

        private static void collect(MapLibraryService.Entry entry, Set<Path> excluded, List<MapLibraryService.Entry> out) {
            if (entry.isMap()) {
                if (!entry.isMultiLevel() && !excluded.contains(entry.mapFile().toAbsolutePath().normalize())) {
                    out.add(entry);
                }
                return;
            }
            for (MapLibraryService.Entry child : entry.children()) {
                collect(child, excluded, out);
            }
        }
    }
}
