package dmmt.ui;

import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MapTreeFilter;
import dmmt.service.RecentMaps;
import dmmt.service.ThumbnailService;
import dmmt.service.Tuning;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.application.Platform;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Left sidebar: map actions plus a file-browser style tree of the map library (folders mirror folders on disk).
 */
public class MapBrowser extends VBox {

    /** Callbacks into the application. */
    public interface Host {
        void newMap();

        void newMapIn(Path folder);

        void importMap(Path suggestedFolder);

        void importMapFolder(Path suggestedFolder);

        void saveMap();

        void rotateMap(boolean clockwise);

        void openMap(Path mapFile);

        Path currentMapFile();

        /**
         * Runs a library operation in the background. The host saves the open map first if it lives under
         * {@code affectedPath} and fixes up its own references afterwards. {@code onDone} runs on the FX thread.
         */
        void runLibraryOperation(String busyMessage, Path affectedPath, LibraryOperation operation,
                                 Consumer<MapLibraryService.Result> onDone);
    }

    @FunctionalInterface
    public interface LibraryOperation {
        MapLibraryService.Result run() throws IOException;
    }

    /** A loaded thumbnail (or {@code null} image if the map has none) and the file stamp it was loaded for. */
    private record Thumbnail(Image image, long stamp) {
    }

    private static final double THUMB_WIDTH = 48;
    private static final double THUMB_HEIGHT = 32;
    private static final double RECENT_CELL_HEIGHT = 26;
    private static final int RECENT_VISIBLE_ROWS = 5;
    private static final PseudoClass OPEN_MAP = PseudoClass.getPseudoClass("open-map");
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    private final MapLibraryService library;
    private final Host host;
    private final TreeView<Entry> tree = new TreeView<>();
    private final Set<Path> expandedFolders = new HashSet<>();
    private final Label currentMapName = new Label();
    private final TextField searchField = new TextField();
    private final Label noResults = new Label("No maps or folders found");
    private final Map<Path, Thumbnail> thumbnails = new HashMap<>();
    private final Set<Path> loadingThumbnails = new HashSet<>();
    private final ExecutorService thumbnailLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private HBox actions;
    private final RecentMaps recentMaps;
    private final ListView<Path> recentList = new ListView<>();
    private Entry scannedRoot;
    private TreeItem<Entry> draggedItem;
    private ContextMenu openMenu;

    public MapBrowser(MapLibraryService library, Host host, RecentMaps recentMaps) {
        this.library = library;
        this.host = host;
        this.recentMaps = recentMaps;
        getStyleClass().add("sidebar");
        setPrefWidth(270);
        setMinWidth(200);

        Label title = new Label("Map Library");
        title.getStyleClass().add("sidebar-title");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(4, title, titleSpacer,
                Icons.button(MaterialDesignF.FOLDER_PLUS_OUTLINE, "New folder in the selected folder",
                        () -> createFolder(selectedFolder())),
                Icons.button(MaterialDesignR.REFRESH, "Reload the library from disk", this::refresh));
        titleRow.getStyleClass().add("toolbar-row");

        HBox actions = new HBox(
                Icons.button(MaterialDesignM.MAP_PLUS, "New empty map (drop images onto it to build a custom map)",
                        host::newMap),
                Icons.button(MaterialDesignF.FILE_IMPORT_OUTLINE,
                        "Import one or more .dd2vtt maps (e.g. from Dungeon Alchemist)",
                        () -> host.importMap(selectedFolder())),
                Icons.button(MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE, "Import all maps from a folder",
                        () -> host.importMapFolder(selectedFolder())),
                Icons.button(MaterialDesignC.CONTENT_SAVE_OUTLINE, "Save the open map (Ctrl+S)", host::saveMap));
        actions.getStyleClass().add("toolbar-row");
        this.actions = actions;

        Label caption = new Label("OPEN MAP");
        caption.getStyleClass().add("caption");
        currentMapName.getStyleClass().add("current-map-name");
        currentMapName.setMaxWidth(Double.MAX_VALUE);
        VBox nameBox = new VBox(1, caption, currentMapName);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        nameBox.setMinWidth(0);
        HBox currentCard = new HBox(4, nameBox,
                Icons.button(MaterialDesignR.ROTATE_LEFT, "Rotate the map 90° counter-clockwise",
                        () -> host.rotateMap(false)),
                Icons.button(MaterialDesignR.ROTATE_RIGHT, "Rotate the map 90° clockwise",
                        () -> host.rotateMap(true)));
        currentCard.getStyleClass().add("current-map-card");
        currentCard.setStyle("-fx-alignment: center-left;");

        tree.setShowRoot(true);
        tree.setCellFactory(view -> new LibraryCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnKeyPressed(event -> {
            TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
            Entry entry = item == null ? null : item.getValue();
            if (entry == null) {
                return;
            }
            if (event.getCode() == KeyCode.ENTER && entry.isMap()) {
                host.openMap(entry.mapFile());
                event.consume();
            } else if (event.getCode() == KeyCode.F2 && !isRoot(entry)) {
                rename(entry);
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && !isRoot(entry)) {
                delete(entry);
                event.consume();
            }
        });

        Label hint = new Label("Double-click to open · drag to move · right-click for more");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        HBox searchBox = buildSearchBox();
        noResults.getStyleClass().add("muted");
        noResults.setVisible(false);
        noResults.setManaged(false);

        getChildren().addAll(titleRow, actions, currentCard, searchBox, noResults, tree, hint, buildRecentBox());
        refresh();
    }

    private VBox buildRecentBox() {
        Label caption = new Label("RECENT MAPS");
        caption.getStyleClass().add("caption");
        recentList.getStyleClass().add("recent-maps");
        recentList.setFixedCellSize(RECENT_CELL_HEIGHT);
        recentList.setPlaceholder(new Label("No maps opened yet"));
        recentList.setCellFactory(view -> new RecentCell());
        recentList.setOnKeyPressed(event -> {
            Path selected = recentList.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && selected != null) {
                host.openMap(selected);
                event.consume();
            }
        });
        VBox box = new VBox(4, caption, recentList);
        box.getStyleClass().add("recent-maps-box");
        return box;
    }

    private void updateRecentList() {
        recentList.getItems().setAll(recentMaps.existing());
        recentList.refresh();
        int rows = Math.max(1, Math.min(recentList.getItems().size(), RECENT_VISIBLE_ROWS));
        double height = rows * RECENT_CELL_HEIGHT + 2;
        recentList.setPrefHeight(height);
        recentList.setMinHeight(height);
        recentList.setMaxHeight(height);
    }

    /** Adds a control to the row of map actions (new / import / save). */
    public void addAction(javafx.scene.Node node) {
        actions.getChildren().add(node);
    }

    private HBox buildSearchBox() {
        searchField.setPromptText("Search maps and folders");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button clear = Icons.button(MaterialDesignC.CLOSE_CIRCLE_OUTLINE, "Clear the search (Esc)", this::clearSearch);
        clear.visibleProperty().bind(searchField.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> rebuildTree());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                clearSearch();
                event.consume();
            }
        });
        HBox box = new HBox(4, Icons.icon(MaterialDesignM.MAGNIFY), searchField, clear);
        box.getStyleClass().addAll("toolbar-row", "search-row");
        box.setStyle("-fx-alignment: center-left;");
        return box;
    }

    private void clearSearch() {
        searchField.clear();
        tree.requestFocus();
    }

    // ---- Public API ----

    public void refresh() {
        try {
            scannedRoot = library.scan();
        } catch (IOException exception) {
            Dialogs.error(window(), "Could not read the map library", exception.getMessage());
            return;
        }
        invalidateThumbnails();
        rebuildTree();
    }

    /** Rebuilds the tree from the last scan, applying the current search filter. */
    private void rebuildTree() {
        if (scannedRoot == null) {
            return;
        }
        Path selected = selectedPath();
        boolean filtering = MapTreeFilter.isActive(searchField.getText());
        Entry root = MapTreeFilter.filter(scannedRoot, searchField.getText());
        TreeItem<Entry> rootItem = build(root, filtering);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        boolean empty = filtering && root.children().isEmpty();
        noResults.setVisible(empty);
        noResults.setManaged(empty);
        if (selected != null) {
            select(selected);
        }
        updateCurrentMap();
    }

    /** Drops cached thumbnails whose file changed on disk (e.g. after a save) so they are reloaded. */
    public void invalidateThumbnails() {
        thumbnails.entrySet().removeIf(entry -> stamp(entry.getKey()) != entry.getValue().stamp());
        tree.refresh();
    }

    /** Selects (and reveals) a folder, map package or map file. */
    public void select(Path path) {
        if (path == null || tree.getRoot() == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        TreeItem<Entry> item = findItem(tree.getRoot(), normalized);
        if (item == null) {
            return;
        }
        for (TreeItem<Entry> parent = item.getParent(); parent != null; parent = parent.getParent()) {
            parent.setExpanded(true);
        }
        tree.getSelectionModel().select(item);
        int row = tree.getRow(item);
        if (row >= 0) {
            tree.scrollTo(Math.max(0, row - 3));
        }
    }

    public void updateCurrentMap() {
        Path current = host.currentMapFile();
        currentMapName.getStyleClass().remove("unsaved");
        if (current == null) {
            currentMapName.setText("Unsaved new map");
            currentMapName.getStyleClass().add("unsaved");
        } else {
            currentMapName.setText(displayName(current));
            recentMaps.record(current);
        }
        updateRecentList();
        tree.refresh();
    }

    /** The folder new maps should go to by default: the selected folder, or the folder of the selected map. */
    public Path selectedFolder() {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) {
            return library.getRoot();
        }
        Entry entry = item.getValue();
        return entry.isFolder() ? entry.path() : entry.containingFolder();
    }

    public static String displayName(Path mapFile) {
        String stem = MapLibraryService.stripExtension(mapFile.getFileName().toString());
        return stem;
    }

    // ---- Tree building ----

    /** While filtering, all folders are expanded and the user's own expand/collapse state is left untouched. */
    private TreeItem<Entry> build(Entry entry, boolean filtering) {
        TreeItem<Entry> item = new TreeItem<>(entry);
        if (entry.isFolder()) {
            Path key = entry.path().toAbsolutePath().normalize();
            if (filtering) {
                item.setExpanded(true);
            } else {
                item.setExpanded(expandedFolders.contains(key));
                item.expandedProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue) {
                        expandedFolders.add(key);
                    } else {
                        expandedFolders.remove(key);
                    }
                });
            }
            for (Entry child : entry.children()) {
                item.getChildren().add(build(child, filtering));
            }
        }
        return item;
    }

    // ---- Thumbnails ----

    /** Modification stamp of what the thumbnail is derived from (thumbnail file if present, else the map file). */
    private static long stamp(Path mapFile) {
        try {
            Path file = ThumbnailService.thumbnailFile(mapFile);
            if (ThumbnailService.isPackage(mapFile) && Files.isRegularFile(file)) {
                return Files.getLastModifiedTime(file).toMillis();
            }
            return Files.getLastModifiedTime(mapFile).toMillis();
        } catch (IOException exception) {
            return -1;
        }
    }

    /** The cached thumbnail, or {@code null} while it is loading (a background load is started) or missing. */
    private Image thumbnailFor(Path mapFile) {
        Path key = mapFile.toAbsolutePath().normalize();
        Thumbnail cached = thumbnails.get(key);
        if (cached != null) {
            return cached.image();
        }
        if (loadingThumbnails.add(key)) {
            thumbnailLoader.execute(() -> {
                Image image = null;
                try {
                    byte[] png = library.loadOrCreateThumbnail(key);
                    if (png != null) {
                        image = new Image(new ByteArrayInputStream(png));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // No thumbnail: the placeholder stays.
                }
                Image loaded = image;
                long stamp = stamp(key);
                Platform.runLater(() -> {
                    loadingThumbnails.remove(key);
                    thumbnails.put(key, new Thumbnail(loaded, stamp));
                    tree.refresh();
                    recentList.refresh();
                });
            });
        }
        return null;
    }

    /** Tooltip with the map name and a larger preview of its thumbnail (when loaded). */
    private static Tooltip previewTooltip(String text, Image image) {
        Tooltip tooltip = Icons.tooltip(text);
        if (image != null) {
            ImageView large = new ImageView(image);
            large.setPreserveRatio(true);
            large.setSmooth(true);
            large.setFitWidth(Math.min(256, image.getWidth()));
            large.setFitHeight(Math.min(256, image.getHeight()));
            tooltip.setGraphic(large);
            tooltip.setContentDisplay(ContentDisplay.TOP);
        }
        return tooltip;
    }

    private static TreeItem<Entry> findItem(TreeItem<Entry> item, Path path) {
        Entry entry = item.getValue();
        if (entry != null && (entry.path().toAbsolutePath().normalize().equals(path)
                || (entry.mapFile() != null && entry.mapFile().toAbsolutePath().normalize().equals(path)))) {
            return item;
        }
        for (TreeItem<Entry> child : item.getChildren()) {
            TreeItem<Entry> found = findItem(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Path selectedPath() {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        return item == null || item.getValue() == null ? null : item.getValue().path();
    }

    private boolean isRoot(Entry entry) {
        return entry.path().toAbsolutePath().normalize().equals(library.getRoot());
    }

    private boolean isOpenMap(Entry entry) {
        Path current = host.currentMapFile();
        return entry.isMap() && current != null
                && entry.mapFile().toAbsolutePath().normalize().equals(current.toAbsolutePath().normalize());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    // ---- Actions ----

    private void createFolder(Path parent) {
        Optional<String> name = Dialogs.askText(window(), "New folder",
                "Create a folder in \"" + folderLabel(parent) + "\"", MaterialDesignF.FOLDER_PLUS_OUTLINE, "Create", "",
                MapLocationDialog::nameProblem);
        if (name.isEmpty()) {
            return;
        }
        Path[] created = new Path[1];
        host.runLibraryOperation("Creating folder…", null, () -> {
            created[0] = library.createFolder(parent, name.get());
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            expandedFolders.add(parent.toAbsolutePath().normalize());
            refresh();
            select(created[0]);
        });
    }

    private void rename(Entry entry) {
        String kind = entry.isFolder() ? "folder" : "map";
        Optional<String> name = Dialogs.askText(window(), "Rename " + kind, "Rename \"" + entry.name() + "\"",
                MaterialDesignR.RENAME_BOX, "Rename", entry.name(), MapLocationDialog::nameProblem);
        if (name.isEmpty() || name.get().equals(entry.name())) {
            return;
        }
        host.runLibraryOperation("Renaming…", entry.path(), () -> library.rename(entry, name.get()), result -> {
            Path renamed = entry.containingFolder().resolve(name.get().trim());
            if (entry.isFolder()) {
                remapExpanded(entry.path(), renamed);
            }
            refresh();
            Path newMapFile = result.movedMaps().get(entry.mapFile());
            select(entry.isMap() && newMapFile != null ? newMapFile : renamed);
        });
    }

    private void copy(Entry entry) {
        host.runLibraryOperation("Copying map…", null, () -> library.copy(entry), result -> {
            refresh();
            select(result.createdMap());
        });
    }

    private void delete(Entry entry) {
        String message;
        if (entry.isMap()) {
            message = "The map \"" + entry.name() + "\" and all of its images and saved state will be permanently "
                    + "deleted from disk.";
        } else {
            int maps = MapLibraryService.countMaps(entry);
            message = "The folder \"" + entry.name() + "\" " + (maps == 0 ? "(empty) "
                    : "and the " + maps + (maps == 1 ? " map" : " maps") + " inside it ")
                    + "will be permanently deleted from disk.";
        }
        boolean confirmed = Dialogs.confirmDanger(window(), "Delete " + (entry.isMap() ? "map" : "folder"),
                "Delete \"" + entry.name() + "\"?", message + "\n\nThis cannot be undone.", "Delete");
        if (!confirmed) {
            return;
        }
        host.runLibraryOperation("Deleting…", entry.path(), () -> {
            library.delete(entry);
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> refresh());
    }

    private void move(Entry entry, Path targetFolder) {
        host.runLibraryOperation("Moving…", entry.path(), () -> library.move(entry, targetFolder), result -> {
            Path destination = targetFolder.resolve(entry.path().getFileName());
            if (entry.isFolder()) {
                remapExpanded(entry.path(), destination);
            }
            expandedFolders.add(targetFolder.toAbsolutePath().normalize());
            refresh();
            Path newMapFile = entry.isMap() ? result.movedMaps().get(entry.mapFile()) : null;
            select(newMapFile != null ? newMapFile : destination);
        });
    }

    private void remapExpanded(Path oldFolder, Path newFolder) {
        Path oldNormalized = oldFolder.toAbsolutePath().normalize();
        Path newNormalized = newFolder.toAbsolutePath().normalize();
        Set<Path> updated = new HashSet<>();
        for (Path path : expandedFolders) {
            updated.add(path.startsWith(oldNormalized) ? newNormalized.resolve(oldNormalized.relativize(path)) : path);
        }
        expandedFolders.clear();
        expandedFolders.addAll(updated);
    }

    private String folderLabel(Path folder) {
        return folder.toAbsolutePath().normalize().equals(library.getRoot()) ? "Library"
                : folder.getFileName().toString();
    }

    private ContextMenu buildMenu(Entry entry) {
        ContextMenu menu = new ContextMenu();
        if (entry == null) {
            entry = tree.getRoot().getValue();
        }
        Entry target = entry;
        if (entry.isMap()) {
            menu.getItems().addAll(
                    item("Open", MaterialDesignM.MAP_OUTLINE, () -> host.openMap(target.mapFile())),
                    new SeparatorMenuItem(),
                    item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                    item("Duplicate", MaterialDesignC.CONTENT_COPY, () -> copy(target)),
                    new SeparatorMenuItem(),
                    danger(item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> delete(target))));
        } else {
            menu.getItems().addAll(
                    item("New map here…", MaterialDesignM.MAP_PLUS, () -> host.newMapIn(target.path())),
                    item("Import maps here…", MaterialDesignF.FILE_IMPORT_OUTLINE, () -> host.importMap(target.path())),
                    item("Import folder here…", MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE,
                            () -> host.importMapFolder(target.path())),
                    item("New folder…", MaterialDesignF.FOLDER_PLUS_OUTLINE, () -> createFolder(target.path())));
            if (!isRoot(entry)) {
                menu.getItems().addAll(
                        new SeparatorMenuItem(),
                        item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                        danger(item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> delete(target))));
            }
        }
        return menu;
    }

    private static MenuItem item(String text, Ikon ikon, Runnable action) {
        MenuItem item = new MenuItem(text, Icons.icon(ikon));
        item.setOnAction(event -> action.run());
        return item;
    }

    private static MenuItem danger(MenuItem item) {
        item.getStyleClass().add("danger");
        return item;
    }

    // ---- Drag & drop ----

    private Path dropFolder(Entry target) {
        if (target == null) {
            return library.getRoot();
        }
        return target.isFolder() ? target.path() : target.containingFolder();
    }

    private boolean canDrop(Entry dragged, Path targetFolder) {
        Path target = targetFolder.toAbsolutePath().normalize();
        if (target.equals(dragged.containingFolder().toAbsolutePath().normalize())) {
            return false;
        }
        return !dragged.isFolder() || !target.startsWith(dragged.path().toAbsolutePath().normalize());
    }

    private final class RecentCell extends ListCell<Path> {
        RecentCell() {
            setOnMouseClicked(event -> {
                if (getItem() != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    host.openMap(getItem());
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            if (empty || path == null) {
                setText(null);
                setTooltip(null);
                pseudoClassStateChanged(OPEN_MAP, false);
                return;
            }
            setText(displayName(path));
            Path current = host.currentMapFile();
            boolean open = current != null && current.toAbsolutePath().normalize().equals(path);
            setTooltip(previewTooltip(displayName(path) + (open ? " (open)" : "") + "\nDouble-click to open",
                    thumbnailFor(path)));
            pseudoClassStateChanged(OPEN_MAP, open);
        }
    }

    private final class LibraryCell extends TreeCell<Entry> {
        private final FontIcon icon = new FontIcon();
        private final FontIcon placeholder = new FontIcon(MaterialDesignM.MAP_OUTLINE);
        private final ImageView thumbnailView = new ImageView();
        private final StackPane thumbnailBox = new StackPane(placeholder, thumbnailView);
        private final PauseTransition autoExpand = new PauseTransition(Duration.millis(Tuning.LIBRARY_AUTO_EXPAND_MS.get()));
        private TreeItem<Entry> observedItem;
        private final javafx.beans.value.ChangeListener<Boolean> expandedListener =
                (observable, oldValue, newValue) -> updateFolderIcon();

        LibraryCell() {
            thumbnailBox.getStyleClass().add("thumbnail-box");
            thumbnailBox.setMinSize(THUMB_WIDTH, THUMB_HEIGHT);
            thumbnailBox.setPrefSize(THUMB_WIDTH, THUMB_HEIGHT);
            thumbnailBox.setMaxSize(THUMB_WIDTH, THUMB_HEIGHT);
            placeholder.getStyleClass().add("map-icon");
            thumbnailView.setFitWidth(THUMB_WIDTH);
            thumbnailView.setFitHeight(THUMB_HEIGHT);
            thumbnailView.setPreserveRatio(true);
            thumbnailView.setSmooth(true);
            setOnMouseClicked(event -> {
                Entry entry = getItem();
                if (entry != null && entry.isMap() && event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2) {
                    host.openMap(entry.mapFile());
                    event.consume();
                }
            });
            setOnContextMenuRequested(event -> {
                if (openMenu != null) {
                    openMenu.hide();
                }
                if (getTreeItem() != null) {
                    tree.getSelectionModel().select(getTreeItem());
                }
                openMenu = buildMenu(isEmpty() ? null : getItem());
                openMenu.show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            });

            setOnDragDetected(event -> {
                Entry entry = getItem();
                if (entry == null || isRoot(entry) || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                draggedItem = getTreeItem();
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(entry.path().toString());
                dragboard.setContent(content);
                dragboard.setDragView(snapshot(null, null));
                event.consume();
            });
            setOnDragOver(event -> {
                if (draggedItem != null && canDrop(draggedItem.getValue(), dropFolder(isEmpty() ? null : getItem()))) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragEntered(event -> {
                if (draggedItem != null && canDrop(draggedItem.getValue(), dropFolder(isEmpty() ? null : getItem()))) {
                    pseudoClassStateChanged(DROP_TARGET, true);
                    TreeItem<Entry> item = getTreeItem();
                    if (item != null && item.getValue() != null && item.getValue().isFolder() && !item.isExpanded()) {
                        autoExpand.setOnFinished(finished -> item.setExpanded(true));
                        autoExpand.playFromStart();
                    }
                }
            });
            setOnDragExited(event -> {
                pseudoClassStateChanged(DROP_TARGET, false);
                autoExpand.stop();
            });
            setOnDragDropped(event -> {
                boolean success = false;
                if (draggedItem != null) {
                    Entry dragged = draggedItem.getValue();
                    Path target = dropFolder(isEmpty() ? null : getItem());
                    if (canDrop(dragged, target)) {
                        move(dragged, target);
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
            setOnDragDone(event -> draggedItem = null);
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (observedItem != null) {
                observedItem.expandedProperty().removeListener(expandedListener);
                observedItem = null;
            }
            pseudoClassStateChanged(OPEN_MAP, false);
            pseudoClassStateChanged(DROP_TARGET, false);
            icon.getStyleClass().removeAll("folder-icon", "map-icon");
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(entry.name());
            if (entry.isFolder()) {
                icon.getStyleClass().add("folder-icon");
                observedItem = getTreeItem();
                if (observedItem != null) {
                    observedItem.expandedProperty().addListener(expandedListener);
                }
                updateFolderIcon();
                setTooltip(null);
            } else {
                pseudoClassStateChanged(OPEN_MAP, isOpenMap(entry));
                Image image = thumbnailFor(entry.mapFile());
                thumbnailView.setImage(image);
                placeholder.setVisible(image == null);
                setTooltip(previewTooltip(entry.name() + (isOpenMap(entry) ? " (open)" : "")
                        + "\nDouble-click to open", image));
                setGraphic(thumbnailBox);
                return;
            }
            setGraphic(icon);
        }

        private void updateFolderIcon() {
            TreeItem<Entry> item = getTreeItem();
            if (item == null || item.getValue() == null || !item.getValue().isFolder()) {
                return;
            }
            if (isRoot(item.getValue())) {
                icon.setIconCode(MaterialDesignF.FOLDER_HOME_OUTLINE);
            } else {
                icon.setIconCode(item.isExpanded() ? MaterialDesignF.FOLDER_OPEN_OUTLINE : MaterialDesignF.FOLDER_OUTLINE);
            }
        }
    }
}
