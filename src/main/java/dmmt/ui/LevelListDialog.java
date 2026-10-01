package dmmt.ui;

import dmmt.service.MapLibraryService;
import dmmt.service.MultiLevelService;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

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
 * Edits the level list of a multilevel map (lowest level at the top): reorder by drag and drop, and rename, remove or
 * move out levels from the right-click menu; insert levels from dd2vtt files, library maps or as empty levels.
 * Nothing is changed on disk; the result is a level plan plus the levels that become separate maps.
 */
public final class LevelListDialog {

    private static final PseudoClass DROP_BEFORE = PseudoClass.getPseudoClass("drop-before");
    private static final PseudoClass DROP_AFTER = PseudoClass.getPseudoClass("drop-after");
    private static final PseudoClass MOVED_OUT = PseudoClass.getPseudoClass("moved-out");
    private static final String DRAG_KEY = "dmmt.levelRow";

    /** One row of the list. {@code detail} is a muted hint (source file, folder, "open"). */
    public static final class Row {
        private final MultiLevelService.Source source;
        private String name;
        private final String detail;
        private final String suggestedMapName;
        /** Non-null: the level leaves the multilevel map and becomes a separate map of this name. */
        private String movedOutAs;

        public Row(MultiLevelService.Source source, String name, String detail) {
            this(source, name, detail, null);
        }

        /** @param suggestedMapName prefilled name when the level is moved out as a separate map */
        public Row(MultiLevelService.Source source, String name, String detail, String suggestedMapName) {
            this.source = source;
            this.name = name;
            this.detail = detail;
            this.suggestedMapName = suggestedMapName;
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

        boolean movedOut() {
            return movedOutAs != null;
        }
    }

    /**
     * The edited level list. Both lists empty means the user confirmed deleting the whole multilevel map.
     */
    public record Result(List<MultiLevelService.PlanItem> plan, List<MultiLevelService.Extraction> extractions) {
        public boolean deletesMap() {
            return plan.isEmpty() && extractions.isEmpty();
        }
    }

    private LevelListDialog() {
    }

    /**
     * @param managing      {@code true} for an existing multilevel map: levels can be moved out as separate maps and
     *                      removing every level is allowed (after a confirmation it deletes the map); {@code false}
     *                      for a new multilevel map, which needs at least two levels
     * @param dd2vttPicker  asks for dd2vtt files (system file chooser), empty if cancelled
     */
    public static Optional<Result> show(Window owner, MapLibraryService library, String title, String header, Ikon ikon,
                                        String actionLabel, List<Row> initial, boolean managing,
                                        Function<Window, List<Path>> dd2vttPicker) {
        Dialog<Result> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, title, header, ikon, false);

        ListView<Row> list = new ListView<>();
        list.getItems().setAll(initial);
        list.getStyleClass().add("level-list");
        list.setPrefSize(400, 320);
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
            if (row.movedOut()) {
                Optional<String> name = Dialogs.askText(dialogWindow[0], "Rename map", "Name of the separate map",
                        MaterialDesignR.RENAME_BOX, "Rename", row.movedOutAs, MultiLevelService::levelNameProblem);
                name.ifPresent(value -> {
                    row.movedOutAs = value.trim();
                    list.refresh();
                });
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
            Row row = list.getItems().get(index);
            if (managing && !row.movedOut() && list.getItems().size() == 1) {
                boolean confirmed = Dialogs.confirmDanger(dialogWindow[0], "Delete multilevel map",
                        "Remove the last level?", "A multilevel map without levels is deleted. The whole map "
                                + "and all of its images and saved state will be permanently deleted from disk."
                                + "\n\nThis cannot be undone.", "Delete map");
                if (confirmed) {
                    deleteWholeMap[0] = true;
                    dialog.setResult(new Result(List.of(), List.of()));
                    dialog.close();
                }
                return;
            }
            list.getItems().remove(index);
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(Math.min(index, list.getItems().size() - 1));
            }
        };
        Runnable moveOut = () -> {
            Row row = list.getSelectionModel().getSelectedItem();
            if (row == null || !managing || !row.existing() || row.movedOut()) {
                return;
            }
            String suggestion = row.suggestedMapName == null || row.suggestedMapName.isBlank()
                    ? row.name : row.suggestedMapName;
            Optional<String> name = Dialogs.askText(dialogWindow[0], "Move out as separate map",
                    "\"" + row.name + "\" becomes a separate map next to the multilevel map", MaterialDesignL.LAYERS_MINUS,
                    "Move out", suggestion, MultiLevelService::levelNameProblem);
            name.ifPresent(value -> {
                row.movedOutAs = value.trim();
                list.refresh();
            });
        };
        Runnable keepIn = () -> {
            Row row = list.getSelectionModel().getSelectedItem();
            if (row != null && row.movedOut()) {
                row.movedOutAs = null;
                list.refresh();
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
                "Level " + (activeCount(list.getItems()) + 1), "New empty level")));

        MenuItem renameItem = menuItem("Rename…", MaterialDesignR.RENAME_BOX, new KeyCodeCombination(KeyCode.F2), rename);
        MenuItem upItem = menuItem("Move up (one level lower)", MaterialDesignC.CHEVRON_UP,
                new KeyCodeCombination(KeyCode.UP, KeyCombination.ALT_DOWN), moveUp);
        MenuItem downItem = menuItem("Move down (one level higher)", MaterialDesignC.CHEVRON_DOWN,
                new KeyCodeCombination(KeyCode.DOWN, KeyCombination.ALT_DOWN), moveDown);
        MenuItem moveOutItem = menuItem("Move out as separate map…", MaterialDesignL.LAYERS_MINUS, null, moveOut);
        MenuItem keepInItem = menuItem("Keep in the multilevel map", MaterialDesignU.UNDO, null, keepIn);
        MenuItem removeItem = menuItem("Remove", MaterialDesignD.DELETE_OUTLINE, new KeyCodeCombination(KeyCode.DELETE), remove);
        ContextMenu menu = new ContextMenu(renameItem, upItem, downItem, new SeparatorMenuItem(), moveOutItem,
                keepInItem, removeItem);
        menu.setOnShowing(event -> {
            int index = list.getSelectionModel().getSelectedIndex();
            Row row = index < 0 ? null : list.getItems().get(index);
            renameItem.setText((row != null && row.movedOut() ? "Rename map…" : "Rename…") + "   (F2)");
            renameItem.setDisable(row == null);
            upItem.setDisable(index <= 0);
            downItem.setDisable(index < 0 || index >= list.getItems().size() - 1);
            moveOutItem.setVisible(managing);
            moveOutItem.setDisable(row == null || !row.existing() || row.movedOut());
            keepInItem.setVisible(row != null && row.movedOut());
            removeItem.setText((row != null && row.existing() && !row.movedOut() ? "Delete level" : "Remove from the list")
                    + "   (Delete)");
            removeItem.setDisable(row == null);
        });
        list.setContextMenu(menu);

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

        Label addLabel = new Label("ADD");
        addLabel.getStyleClass().add("caption");
        HBox addRow = new HBox(6, addLabel, files, maps, empty);
        addRow.setStyle("-fx-alignment: center-left;");
        Label hint = new Label("Drag levels to reorder them. Right-click a level to rename, "
                + (managing ? "remove or move it out as a separate map." : "reorder or remove it.")
                + " New levels are inserted below the selected level.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        VBox content = new VBox(6, lowest, list, highest, addRow, hint, error);
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);

        list.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> error.setText(""));
        list.getItems().addListener((javafx.collections.ListChangeListener<Row>) change -> error.setText(""));
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
            int active = activeCount(list.getItems());
            List<Row> movedOut = list.getItems().stream().filter(Row::movedOut).toList();
            if (!managing && active < 2) {
                error.setText("A multilevel map needs at least two levels.");
                event.consume();
                return;
            }
            if (managing && active == 0 && movedOut.isEmpty()) {
                error.setText("Remove the last level (right-click → Delete level) to delete the whole map.");
                event.consume();
                return;
            }
            if (managing && !confirmChanges(dialogWindow[0], initial, list.getItems(), active, movedOut)) {
                event.consume();
            }
        });
        dialog.setResultConverter(buttonType -> {
            if (deleteWholeMap[0]) {
                return new Result(List.of(), List.of());
            }
            if (buttonType != action) {
                return null;
            }
            List<MultiLevelService.PlanItem> plan = new ArrayList<>();
            List<MultiLevelService.Extraction> extractions = new ArrayList<>();
            for (Row row : list.getItems()) {
                if (row.movedOut() && row.source instanceof MultiLevelService.Existing existing) {
                    extractions.add(new MultiLevelService.Extraction(existing.levelId(), row.movedOutAs));
                } else {
                    plan.add(new MultiLevelService.PlanItem(row.source, row.name));
                }
            }
            return new Result(plan, extractions);
        });
        Platform.runLater(() -> {
            dialogWindow[0] = dialog.getDialogPane().getScene().getWindow();
            list.requestFocus();
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
            }
        });
        return dialog.showAndWait();
    }

    /** Summarizes what applying the list does to the existing levels; {@code true} to go ahead. */
    private static boolean confirmChanges(Window owner, List<Row> initial, List<Row> rows, int active, List<Row> movedOut) {
        Set<String> listed = new HashSet<>();
        for (Row row : rows) {
            if (row.source instanceof MultiLevelService.Existing existing) {
                listed.add(existing.levelId());
            }
        }
        List<String> deleted = initial.stream()
                .filter(row -> row.source instanceof MultiLevelService.Existing existing
                        && !listed.contains(existing.levelId()))
                .map(row -> row.name).toList();
        StringBuilder message = new StringBuilder();
        if (!deleted.isEmpty()) {
            message.append("These levels and all of their images and saved state will be permanently deleted:\n\n• ")
                    .append(String.join("\n• ", deleted)).append("\n\nThis cannot be undone.");
        }
        if (!movedOut.isEmpty()) {
            if (!message.isEmpty()) {
                message.append("\n\n");
            }
            message.append("These levels become separate maps next to the multilevel map:\n\n• ");
            message.append(String.join("\n• ", movedOut.stream()
                    .map(row -> row.name + " → \"" + row.movedOutAs + "\"").toList()));
        }
        String collapse = active == 1 ? "Only one level is left, so the multilevel map becomes an ordinary map."
                : active == 0 ? "No level is left, so the multilevel map is removed." : null;
        if (collapse != null) {
            message.append(message.isEmpty() ? "" : "\n\n").append(collapse);
        }
        if (message.isEmpty()) {
            return true;
        }
        if (!deleted.isEmpty()) {
            return Dialogs.confirmDanger(owner, "Delete levels",
                    "Delete " + deleted.size() + (deleted.size() == 1 ? " level?" : " levels?"), message.toString(), "Delete");
        }
        return Dialogs.confirm(owner, "Change levels", movedOut.isEmpty() ? "Turn into an ordinary map?"
                : "Move " + movedOut.size() + (movedOut.size() == 1 ? " level" : " levels") + " out?",
                MaterialDesignL.LAYERS_MINUS, message.toString(), "Apply");
    }

    private static int activeCount(List<Row> rows) {
        int count = 0;
        for (Row row : rows) {
            if (!row.movedOut()) {
                count++;
            }
        }
        return count;
    }

    private static MenuItem menuItem(String text, Ikon ikon, KeyCombination accelerator, Runnable action) {
        // The list handles the keys itself; a real accelerator would also fire while other controls have focus.
        MenuItem item = new MenuItem(accelerator == null ? text : text + "   (" + accelerator.getDisplayText() + ")",
                Icons.icon(ikon));
        item.setOnAction(event -> action.run());
        return item;
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

    /** A level row; rows can be dragged within the list to reorder them. */
    private static final class RowCell extends ListCell<Row> {
        RowCell() {
            setOnDragDetected(event -> {
                if (isEmpty() || getItem() == null || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                getListView().getProperties().put(DRAG_KEY, getItem());
                Dragboard board = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(getItem().name);
                board.setContent(content);
                board.setDragView(snapshot(null, null), event.getX(), event.getY());
                event.consume();
            });
            setOnDragOver(event -> {
                if (draggedRow() == null) {
                    return;
                }
                event.acceptTransferModes(TransferMode.MOVE);
                boolean after = isEmpty() || event.getY() > getHeight() / 2;
                pseudoClassStateChanged(DROP_BEFORE, !after);
                pseudoClassStateChanged(DROP_AFTER, after && !isEmpty());
                event.consume();
            });
            setOnDragExited(event -> clearDropMarks());
            setOnDragDropped(event -> {
                Row dragged = draggedRow();
                if (dragged == null) {
                    return;
                }
                List<Row> items = getListView().getItems();
                int from = items.indexOf(dragged);
                int target = isEmpty() || getItem() == null ? items.size()
                        : getIndex() + (event.getY() > getHeight() / 2 ? 1 : 0);
                if (from >= 0) {
                    items.remove(from);
                    if (from < target) {
                        target--;
                    }
                    target = Math.max(0, Math.min(target, items.size()));
                    items.add(target, dragged);
                    getListView().getSelectionModel().clearAndSelect(target);
                }
                clearDropMarks();
                event.setDropCompleted(true);
                event.consume();
            });
            setOnDragDone(event -> {
                if (getListView() != null) {
                    getListView().getProperties().remove(DRAG_KEY);
                }
            });
        }

        private Row draggedRow() {
            if (getListView() == null) {
                return null;
            }
            Object row = getListView().getProperties().get(DRAG_KEY);
            return row instanceof Row value ? value : null;
        }

        private void clearDropMarks() {
            pseudoClassStateChanged(DROP_BEFORE, false);
            pseudoClassStateChanged(DROP_AFTER, false);
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            pseudoClassStateChanged(MOVED_OUT, !empty && row != null && row.movedOut());
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label number = new Label(row.movedOut() ? "–" : (positionOf(row) + 1) + ".");
            number.getStyleClass().add("muted");
            number.setMinWidth(24);
            Label name = new Label(row.name);
            String detailText = row.movedOut() ? "Becomes the separate map \"" + row.movedOutAs + "\""
                    : row.detail == null ? "" : row.detail;
            Label detail = new Label(detailText);
            detail.getStyleClass().add("muted");
            detail.setMinWidth(0);
            HBox.setHgrow(detail, Priority.ALWAYS);
            Ikon ikon = row.movedOut() ? MaterialDesignL.LAYERS_MINUS
                    : row.existing() ? MaterialDesignL.LAYERS_OUTLINE
                    : row.source instanceof MultiLevelService.ForeignLevel ? MaterialDesignL.LAYERS_PLUS
                    : row.source instanceof MultiLevelService.Dd2vtt ? MaterialDesignF.FILE_IMPORT_OUTLINE
                    : row.source instanceof MultiLevelService.LibraryMap ? MaterialDesignM.MAP_OUTLINE
                    : MaterialDesignP.PLUS_BOX_OUTLINE;
            HBox box = new HBox(8, number, Icons.icon(ikon), name, detail);
            box.setStyle("-fx-alignment: center-left;");
            setText(null);
            setGraphic(box);
        }

        /** Level number among the rows that stay in the multilevel map. */
        private int positionOf(Row row) {
            int position = 0;
            for (Row other : getListView().getItems()) {
                if (other == row) {
                    return position;
                }
                if (!other.movedOut()) {
                    position++;
                }
            }
            return position;
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
