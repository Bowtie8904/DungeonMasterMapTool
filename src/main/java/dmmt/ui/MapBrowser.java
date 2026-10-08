package dmmt.ui;

import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import dmmt.service.MapTreeFilter;
import dmmt.service.MultiLevelService;
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
import javafx.scene.control.SelectionMode;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

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
import java.util.function.Function;

/**
 * Left sidebar: map actions plus a file-browser style tree of the map library (folders mirror folders on disk).
 */
public class MapBrowser extends VBox {
    private Function<Path, String> apiUrlProvider;
    private boolean apiUrlOptionsVisible;

    /** Supplies the map-switch URL; failures are displayed rather than silently copying an invalid URL. */
    public void setApiUrlProvider(Function<Path, String> apiUrlProvider) {
        this.apiUrlProvider = apiUrlProvider;
    }

    public void setApiUrlOptionsVisible(boolean visible) {
        apiUrlOptionsVisible = visible;
    }

    private void copyApiUrl(Path mapFile) {
        try {
            String url = apiUrlProvider.apply(mapFile);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("The local API URL is not available.");
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(url);
            if (!Clipboard.getSystemClipboard().setContent(content)) {
                throw new IllegalStateException("Could not write to the clipboard.");
            }
        } catch (RuntimeException ex) {
            Dialogs.error(window(), "Could not copy API URL", ex.getMessage());
        }
    }

    /** Callbacks into the application. */
    public interface Host {
        void newMap();

        void newMapIn(Path folder);

        void importMap(Path suggestedFolder);

        void importMapFolder(Path suggestedFolder);

        void saveMap();

        void rotateMap(boolean clockwise);

        void openMap(Path mapFile);

        /** The open map: the {@code .dmmap} file, or the {@code .dmlevels} manifest of an open multilevel map. */
        Path currentMapFile();

        /** Name of the open level of a multilevel map, {@code null} for ordinary maps. */
        default String currentLevelName() {
            return null;
        }

        /** Imports several dd2vtt files as the levels of one multilevel map. */
        void importMultiLevelMap(Path suggestedFolder);

        /** Merges library maps into a new multilevel map (the maps are moved into it). */
        void mergeIntoMultiLevelMap(java.util.List<Entry> maps);

        /** Opens the level dialog of a multilevel map. */
        void manageLevels(Path manifestFile);

        /** Edits one map's tags, or adds tags to every selected map. */
        void manageTags(java.util.List<Entry> maps);

        /**
         * Maps were dropped onto another map ({@code dragged} never contains {@code target}): ordinary maps only
         * create a multilevel map; otherwise everything is added to / merged into the target if it is a multilevel
         * map, else into the first dragged multilevel map. The host shows the level dialog first.
         */
        void dropMapOnMap(java.util.List<Entry> dragged, Entry target);

        /** Splits a multilevel map into separate maps (after a confirmation). */
        void dissolveMultiLevel(Path manifestFile);

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

    /** A loaded thumbnail (or {@code null} image if the map has none), the file stamp it was loaded for and, for multilevel maps, the number of levels. */
    private record Thumbnail(Image image, long stamp, int levels) {
    }

    private static final double THUMB_WIDTH = 48;
    private static final double THUMB_HEIGHT = 32;
    private static final double RECENT_CELL_HEIGHT = 26;
    private static final int RECENT_VISIBLE_ROWS = 5;
    private static final PseudoClass OPEN_MAP = PseudoClass.getPseudoClass("open-map");
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass MERGE_TARGET = PseudoClass.getPseudoClass("merge-target");

    /** What dropping a dragged library entry onto a cell does. */
    private enum DropAction {
        MOVE, CREATE_MULTILEVEL, ADD_TO_MULTILEVEL, MERGE_MULTILEVEL
    }

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
    private java.util.List<Entry> draggedEntries;
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
                        "Import one or more .dd2vtt or .uvtt maps",
                        () -> host.importMap(selectedFolder())),
                Icons.button(MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE, "Import all maps from a folder",
                        () -> host.importMapFolder(selectedFolder())),
                Icons.button(MaterialDesignL.LAYERS_PLUS,
                        "Import a multilevel map: several .dd2vtt or .uvtt levels (e.g. building floors) as one map",
                        () -> host.importMultiLevelMap(selectedFolder())),
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
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(view -> new LibraryCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnKeyPressed(event -> {
            TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
            Entry entry = item == null ? null : item.getValue();
            if (entry == null) {
                return;
            }
            boolean single = tree.getSelectionModel().getSelectedItems().size() == 1;
            if (event.getCode() == KeyCode.ENTER && single && entry.isMap()) {
                host.openMap(entry.mapFile());
                event.consume();
            } else if (event.getCode() == KeyCode.F2 && single && !isRoot(entry)) {
                rename(entry);
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && !isRoot(entry)) {
                java.util.List<Entry> maps = selectedMaps();
                if (!maps.isEmpty()) {
                    deleteMaps(maps);
                } else if (single) {
                    delete(entry);
                }
                event.consume();
            }
        });

        Label hint = new Label("Double-click to open · drag to move · Ctrl+click to select several · right-click for more");
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
        tree.getSelectionModel().clearSelection();
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
            String level = host.currentLevelName();
            currentMapName.setText(displayName(current) + (level == null ? "" : " · " + level));
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
            if (MultiLevelService.isMultiLevelFile(mapFile)) {
                // Level names/order live in the manifest, the picture in the thumbnail: either may change.
                long manifest = Files.getLastModifiedTime(mapFile).toMillis();
                return Files.isRegularFile(file) ? Math.max(manifest, Files.getLastModifiedTime(file).toMillis()) : manifest;
            }
            if (ThumbnailService.isPackage(mapFile) && Files.isRegularFile(file)) {
                return Files.getLastModifiedTime(file).toMillis();
            }
            return Files.getLastModifiedTime(mapFile).toMillis();
        } catch (IOException exception) {
            return -1;
        }
    }

    /** Number of levels of a multilevel map (once its thumbnail was loaded), else 0. */
    private int levelCount(Path mapFile) {
        Thumbnail cached = thumbnails.get(mapFile.toAbsolutePath().normalize());
        return cached == null ? 0 : cached.levels();
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
                int levels = 0;
                try {
                    byte[] png = library.loadOrCreateThumbnail(key);
                    if (png != null) {
                        image = new Image(new ByteArrayInputStream(png));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // No thumbnail: the placeholder stays.
                }
                if (MultiLevelService.isMultiLevelFile(key)) {
                    try {
                        levels = library.multiLevels().loadManifest(key).getLevels().size();
                    } catch (IOException | RuntimeException ignored) {
                        // the tooltip just has no level count
                    }
                }
                Image loaded = image;
                int levelCount = levels;
                long stamp = stamp(key);
                Platform.runLater(() -> {
                    loadingThumbnails.remove(key);
                    thumbnails.put(key, new Thumbnail(loaded, stamp, levelCount));
                    tree.refresh();
                    recentList.refresh();
                });
            });
        }
        return null;
    }

    /** Tooltip with the map name and a larger preview of its thumbnail (when loaded). */
    public static Tooltip previewTooltip(String text, Image image) {
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

    /** All selected maps in tree order, or none if the selection includes a folder. */
    private java.util.List<Entry> selectedMaps() {
        java.util.List<Entry> maps = new java.util.ArrayList<>();
        for (TreeItem<Entry> item : tree.getSelectionModel().getSelectedItems()) {
            if (item == null || item.getValue() == null || !item.getValue().isMap()) {
                return java.util.List.of();
            }
            maps.add(item.getValue());
        }
        return java.util.List.copyOf(maps);
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

    private void copyMaps(java.util.List<Entry> maps) {
        java.util.List<Path> created = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        host.runLibraryOperation("Copying " + maps.size() + " maps…", affectedOpenMap(maps), () -> {
            for (Entry map : maps) {
                try {
                    created.add(library.copy(map).createdMap());
                } catch (IOException exception) {
                    failures.add(map.name() + ": " + exception.getMessage());
                }
            }
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            refresh();
            tree.getSelectionModel().clearSelection();
            for (Path path : created) {
                TreeItem<Entry> item = findItem(tree.getRoot(), path.toAbsolutePath().normalize());
                if (item != null) {
                    tree.getSelectionModel().select(item);
                }
            }
            showBatchFailures("duplicated", failures);
        });
    }

    private void deleteMaps(java.util.List<Entry> maps) {
        if (maps.size() == 1) {
            delete(maps.get(0));
            return;
        }
        String names = String.join("\n", maps.stream().map(Entry::name).toList());
        boolean confirmed = Dialogs.confirmDanger(window(), "Delete maps", "Delete " + maps.size() + " maps?",
                "These maps and all of their images and saved state will be permanently deleted from disk:\n\n"
                        + names + "\n\nThis cannot be undone.", "Delete");
        if (!confirmed) {
            return;
        }
        java.util.List<String> failures = new java.util.ArrayList<>();
        host.runLibraryOperation("Deleting " + maps.size() + " maps…", affectedOpenMap(maps), () -> {
            for (Entry map : maps) {
                try {
                    library.delete(map);
                } catch (IOException exception) {
                    failures.add(map.name() + ": " + exception.getMessage());
                }
            }
            return new MapLibraryService.Result(java.util.Map.of(), null);
        }, result -> {
            refresh();
            showBatchFailures("deleted", failures);
        });
    }

    private Path affectedOpenMap(java.util.List<Entry> maps) {
        return maps.stream().filter(this::isOpenMap).map(Entry::path).findFirst().orElse(null);
    }

    private void showBatchFailures(String action, java.util.List<String> failures) {
        if (!failures.isEmpty()) {
            Dialogs.error(window(), failures.size() + (failures.size() == 1 ? " map was" : " maps were")
                    + " not " + action, String.join("\n", failures));
        }
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

    ContextMenu buildMenu(Entry entry) {
        ContextMenu menu = new ContextMenu();
        if (entry == null) {
            entry = tree.getRoot().getValue();
        }
        Entry target = entry;
        java.util.List<Entry> maps = selectedMaps();
        if (maps.isEmpty() && tree.getSelectionModel().getSelectedItems().size() <= 1 && entry.isMap()) {
            maps = java.util.List.of(entry);
        }
        java.util.List<Entry> selected = maps;
        MenuItem duplicate = item("Duplicate", MaterialDesignC.CONTENT_COPY, () -> copyMaps(selected));
        MenuItem delete = danger(item("Delete…", MaterialDesignD.DELETE_OUTLINE, () -> {
            if (target.isMap()) {
                deleteMaps(selected);
            } else {
                delete(target);
            }
        }));
        duplicate.setDisable(selected.isEmpty());
        delete.setDisable(target.isMap() && selected.isEmpty());
        MenuItem merge = item("Combine into multilevel map", MaterialDesignL.LAYERS_PLUS,
                () -> host.mergeIntoMultiLevelMap(selected));
        merge.setDisable(selected.size() < 2 || selected.stream().anyMatch(Entry::isMultiLevel));
        MenuItem tags = item(selected.size() > 1 ? "Add tags…" : "Manage tags…",
                MaterialDesignT.TAG_MULTIPLE_OUTLINE, () -> host.manageTags(selected));
        tags.setDisable(selected.isEmpty());
        if (entry.isMultiLevel()) {
            menu.getItems().addAll(
                    item("Open", MaterialDesignM.MAP_OUTLINE, () -> host.openMap(target.mapFile())),
                    tags,
                    item("Manage levels…", MaterialDesignL.LAYERS_TRIPLE_OUTLINE, () -> host.manageLevels(target.mapFile())),
                    item("Dissolve into separate maps…", MaterialDesignL.LAYERS_OFF_OUTLINE,
                            () -> host.dissolveMultiLevel(target.mapFile())),
                    new SeparatorMenuItem(),
                    item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                    duplicate,
                    new SeparatorMenuItem(),
                    delete);
        } else if (entry.isMap()) {
            menu.getItems().addAll(
                    item("Open", MaterialDesignM.MAP_OUTLINE, () -> host.openMap(target.mapFile())),
                    tags,
                    new SeparatorMenuItem(),
                    item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                    duplicate,
                    merge,
                    new SeparatorMenuItem(),
                    delete);
        } else {
            menu.getItems().addAll(
                    item("New map here…", MaterialDesignM.MAP_PLUS, () -> host.newMapIn(target.path())),
                    item("Import maps here…", MaterialDesignF.FILE_IMPORT_OUTLINE, () -> host.importMap(target.path())),
                    item("Import folder here…", MaterialDesignF.FOLDER_DOWNLOAD_OUTLINE,
                            () -> host.importMapFolder(target.path())),
                    item("Import multilevel map here…", MaterialDesignL.LAYERS_PLUS,
                            () -> host.importMultiLevelMap(target.path())),
                    item("New folder…", MaterialDesignF.FOLDER_PLUS_OUTLINE, () -> createFolder(target.path())));
            if (!isRoot(entry)) {
                menu.getItems().addAll(
                        new SeparatorMenuItem(),
                        item("Rename…", MaterialDesignR.RENAME_BOX, () -> rename(target)),
                        delete);
            }
        }
        if (entry.isMap() && apiUrlProvider != null && apiUrlOptionsVisible) {
            menu.getItems().add(item("Copy API URL", MaterialDesignC.CONTENT_COPY,
                    () -> copyApiUrl(target.mapFile())));
        }
        if (tree.getSelectionModel().getSelectedItems().size() > 1) {
            for (MenuItem action : menu.getItems()) {
                if (action != duplicate && action != merge && action != tags
                        && !(action == delete && target.isMap())) {
                    action.setDisable(true);
                }
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

    /** Dropping maps onto another map merges them; anything else dropped somewhere moves it to that folder. */
    private DropAction dropAction(java.util.List<Entry> dragged, Entry target) {
        if (dragged == null || dragged.isEmpty()) {
            return null;
        }
        boolean onlyMaps = dragged.stream().allMatch(Entry::isMap);
        if (target != null && target.isMap() && onlyMaps) {
            java.util.List<Entry> others = othersThan(dragged, target);
            if (others.isEmpty()) {
                return null;
            }
            boolean anyMulti = others.stream().anyMatch(Entry::isMultiLevel);
            if (!anyMulti && !target.isMultiLevel()) {
                return DropAction.CREATE_MULTILEVEL;
            }
            return anyMulti && (target.isMultiLevel() || others.stream().filter(Entry::isMultiLevel).count() > 1)
                    ? DropAction.MERGE_MULTILEVEL : DropAction.ADD_TO_MULTILEVEL;
        }
        Path folder = dropFolder(target);
        return dragged.stream().anyMatch(entry -> canDrop(entry, folder)) ? DropAction.MOVE : null;
    }

    /** The dragged maps without {@code target}. */
    private static java.util.List<Entry> othersThan(java.util.List<Entry> dragged, Entry target) {
        if (target == null || target.mapFile() == null) {
            return dragged;
        }
        Path targetFile = target.mapFile().toAbsolutePath().normalize();
        return dragged.stream()
                .filter(entry -> entry.mapFile() == null || !entry.mapFile().toAbsolutePath().normalize().equals(targetFile))
                .toList();
    }

    private static String dropHint(DropAction action, java.util.List<Entry> dragged, Entry target) {
        int count = othersThan(dragged, target).size();
        String maps = count > 1 ? " (" + count + " maps)" : "";
        return switch (action) {
            case CREATE_MULTILEVEL -> "Create a multilevel map" + maps;
            case ADD_TO_MULTILEVEL -> "Add to the multilevel map" + maps;
            case MERGE_MULTILEVEL -> "Merge into a multilevel map" + maps;
            case MOVE -> null;
        };
    }

    /**
     * What a drag starting at {@code item} carries: all selected entries if {@code item} is selected (in tree order,
     * without the library root and without entries inside another selected folder), else just {@code item}.
     */
    private java.util.List<Entry> draggedSelection(TreeItem<Entry> item) {
        java.util.List<TreeItem<Entry>> selected = new java.util.ArrayList<>(tree.getSelectionModel().getSelectedItems());
        if (!selected.contains(item)) {
            return item.getValue() == null || isRoot(item.getValue()) ? java.util.List.of() : java.util.List.of(item.getValue());
        }
        selected.removeIf(candidate -> candidate == null || candidate.getValue() == null || isRoot(candidate.getValue()));
        selected.sort(java.util.Comparator.comparingInt(tree::getRow));
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        for (TreeItem<Entry> candidate : selected) {
            Path path = candidate.getValue().path().toAbsolutePath().normalize();
            boolean insideSelectedFolder = selected.stream().anyMatch(other -> other != candidate
                    && other.getValue().isFolder()
                    && path.startsWith(other.getValue().path().toAbsolutePath().normalize()));
            if (!insideSelectedFolder) {
                entries.add(candidate.getValue());
            }
        }
        return entries;
    }

    /** Moves several entries into {@code targetFolder}; ones that cannot be moved are skipped and listed afterwards. */
    private void moveAll(java.util.List<Entry> entries, Path targetFolder) {
        java.util.List<Entry> movable = entries.stream().filter(entry -> canDrop(entry, targetFolder)).toList();
        if (movable.size() == 1 && entries.size() == 1) {
            move(movable.get(0), targetFolder);
            return;
        }
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (Entry entry : entries) {
            if (!movable.contains(entry) && entry.isFolder()
                    && targetFolder.toAbsolutePath().normalize().startsWith(entry.path().toAbsolutePath().normalize())) {
                skipped.add(entry.name() + ": a folder cannot be moved into itself.");
            }
        }
        java.util.List<Path> destinations = new java.util.ArrayList<>();
        // The open map may be among them: the library root makes the host save it first and follow the move.
        host.runLibraryOperation("Moving " + movable.size() + " items…", library.getRoot(), () -> {
            java.util.Map<Path, Path> moved = new java.util.LinkedHashMap<>();
            for (Entry entry : movable) {
                try {
                    moved.putAll(library.move(entry, targetFolder).movedMaps());
                    destinations.add(targetFolder.resolve(entry.path().getFileName()));
                } catch (IOException | RuntimeException ex) {
                    skipped.add(entry.name() + ": " + ex.getMessage());
                }
            }
            return new MapLibraryService.Result(moved, null);
        }, result -> {
            for (Entry entry : movable) {
                if (entry.isFolder()) {
                    remapExpanded(entry.path(), targetFolder.resolve(entry.path().getFileName()));
                }
            }
            expandedFolders.add(targetFolder.toAbsolutePath().normalize());
            refresh();
            if (!destinations.isEmpty()) {
                Entry first = movable.get(0);
                Path firstMap = first.isMap() ? result.movedMaps().get(first.mapFile().toAbsolutePath().normalize()) : null;
                select(firstMap != null ? firstMap : destinations.get(0));
            }
            if (!skipped.isEmpty()) {
                Dialogs.error(window(), skipped.size() + (skipped.size() == 1 ? " item was" : " items were")
                        + " not moved", String.join("\n", skipped));
            }
        });
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
        private final FontIcon multiLevelBadge = new FontIcon(MaterialDesignL.LAYERS_TRIPLE);
        private final FontIcon dropBadge = new FontIcon(MaterialDesignL.LAYERS_PLUS);
        private final StackPane thumbnailBox = new StackPane(placeholder, thumbnailView, multiLevelBadge, dropBadge);
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
            multiLevelBadge.getStyleClass().add("multilevel-badge");
            StackPane.setAlignment(multiLevelBadge, javafx.geometry.Pos.BOTTOM_RIGHT);
            multiLevelBadge.setVisible(false);
            dropBadge.getStyleClass().add("merge-drop-badge");
            dropBadge.setVisible(false);
            thumbnailView.setFitWidth(THUMB_WIDTH);
            thumbnailView.setFitHeight(THUMB_HEIGHT);
            thumbnailView.setPreserveRatio(true);
            thumbnailView.setSmooth(true);
            addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == MouseButton.SECONDARY && getTreeItem() != null
                        && tree.getSelectionModel().getSelectedItems().contains(getTreeItem())) {
                    tree.requestFocus();
                    event.consume();
                }
            });
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
                if (getTreeItem() != null && !tree.getSelectionModel().getSelectedItems().contains(getTreeItem())) {
                    tree.getSelectionModel().clearSelection();
                    tree.getSelectionModel().select(getTreeItem());
                } else if (isEmpty()) {
                    tree.getSelectionModel().clearSelection();
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
                draggedEntries = draggedSelection(getTreeItem());
                if (draggedEntries.isEmpty()) {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(entry.path().toString());
                dragboard.setContent(content);
                if (draggedEntries.size() > 1) {
                    Label count = new Label(draggedEntries.size() + " items");
                    count.setStyle("-fx-background-color: #3d3424; -fx-text-fill: white; -fx-padding: 4 10;"
                            + " -fx-background-radius: 10;");
                    dragboard.setDragView(count.snapshot(null, null));
                } else {
                    dragboard.setDragView(snapshot(null, null));
                }
                event.consume();
            });
            setOnDragOver(event -> {
                if (draggedEntries != null && dropAction(draggedEntries, isEmpty() ? null : getItem()) != null) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragEntered(event -> {
                DropAction action = draggedEntries == null ? null : dropAction(draggedEntries, isEmpty() ? null : getItem());
                if (action == DropAction.MOVE) {
                    pseudoClassStateChanged(DROP_TARGET, true);
                    TreeItem<Entry> item = getTreeItem();
                    if (item != null && item.getValue() != null && item.getValue().isFolder() && !item.isExpanded()) {
                        autoExpand.setOnFinished(finished -> item.setExpanded(true));
                        autoExpand.playFromStart();
                    }
                } else if (action != null) {
                    pseudoClassStateChanged(MERGE_TARGET, true);
                    dropBadge.setIconCode(action == DropAction.MERGE_MULTILEVEL ? MaterialDesignC.CALL_MERGE
                            : MaterialDesignL.LAYERS_PLUS);
                    dropBadge.setVisible(true);
                    setText(getItem().name() + "  —  " + dropHint(action, draggedEntries, getItem()));
                }
            });
            setOnDragExited(event -> {
                pseudoClassStateChanged(DROP_TARGET, false);
                clearMergeMark();
                autoExpand.stop();
            });
            setOnDragDropped(event -> {
                boolean success = false;
                if (draggedEntries != null) {
                    java.util.List<Entry> dragged = draggedEntries;
                    Entry targetEntry = isEmpty() ? null : getItem();
                    DropAction action = dropAction(dragged, targetEntry);
                    if (action == DropAction.MOVE) {
                        moveAll(dragged, dropFolder(targetEntry));
                        success = true;
                    } else if (action != null) {
                        clearMergeMark();
                        java.util.List<Entry> others = othersThan(dragged, targetEntry);
                        // After the drag gesture has finished: the host opens a modal dialog.
                        Platform.runLater(() -> host.dropMapOnMap(others, targetEntry));
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
            setOnDragDone(event -> draggedEntries = null);
        }

        private void clearMergeMark() {
            pseudoClassStateChanged(MERGE_TARGET, false);
            if (dropBadge.isVisible()) {
                dropBadge.setVisible(false);
                if (getItem() != null) {
                    setText(getItem().name());
                }
            }
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
            pseudoClassStateChanged(MERGE_TARGET, false);
            dropBadge.setVisible(false);
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
                boolean multiLevel = entry.isMultiLevel();
                multiLevelBadge.setVisible(multiLevel);
                String kind = "";
                if (multiLevel) {
                    int levels = levelCount(entry.mapFile());
                    kind = "\nMultilevel map" + (levels > 0 ? " · " + levels + (levels == 1 ? " level" : " levels") : "");
                }
                setTooltip(previewTooltip(entry.name() + (isOpenMap(entry) ? " (open)" : "") + kind
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
