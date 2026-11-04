package dmmt.ui;

import dmmt.log.Log;
import dmmt.online.Campaign;
import dmmt.online.CampaignHandouts;
import dmmt.online.CampaignHandouts.FileEntry;
import dmmt.online.OnlineSessionServer;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The DM's handout browser: folders and images of the hosted campaign, with sharing and "show to everyone". */
public final class HandoutsPanel extends VBox {
    private static final Log LOG = Log.of(HandoutsPanel.class);
    private static final DataFormat FILE_ID = new DataFormat("application/x-dmmt-handout");
    private static final DataFormat FOLDER = new DataFormat("application/x-dmmt-handout-folder");
    private static final javafx.css.PseudoClass DROP_TARGET = javafx.css.PseudoClass.getPseudoClass("drop-target");

    private record Item(String folder, FileEntry file) {
        String name() {
            return file != null ? file.name : folder.isEmpty() ? "Handouts" : CampaignHandouts.leaf(folder);
        }
    }

    private final Map<String, javafx.scene.Node> controls = new LinkedHashMap<>();
    private final TreeView<Item> tree = new TreeView<>();
    private final TextField search = new TextField();
    private final Label hint = new Label();
    private final Set<String> expanded = new HashSet<>(Set.of(""));
    private final Map<String, Image> thumbnails = new HashMap<>();
    private final HandoutImageWindow imageWindow = new HandoutImageWindow();
    private final Supplier<StackPane> desktop;
    private final Consumer<String> status;
    private OnlineSessionServer server;
    private CampaignHandouts handouts;
    private final Consumer<String> listener = id -> Platform.runLater(this::refresh);
    private CampaignHandouts.Listing listing = new CampaignHandouts.Listing(List.of(), List.of());

    public HandoutsPanel(Supplier<StackPane> desktop, Consumer<String> status) {
        this.desktop = desktop;
        this.status = status;
        getStyleClass().add("sidebar");
        setSpacing(6);
        Button importFile = Icons.button(MaterialDesignF.FILE_IMAGE_PLUS_OUTLINE, "Add images from disk", this::importFiles);
        Button paste = Icons.button(MaterialDesignC.CONTENT_PASTE, "Add an image from the clipboard (Ctrl+V)", this::pasteImage);
        Button folder = Icons.button(MaterialDesignF.FOLDER_PLUS_OUTLINE, "New folder", this::newFolder);
        Button reload = Icons.button(MaterialDesignR.REFRESH, "Reload handouts", this::refresh);
        controls.put("online.files.import", importFile);
        controls.put("online.files.paste", paste);
        controls.put("online.files.folder", folder);
        controls.put("online.files.refresh", reload);
        HBox actions = new HBox(importFile, paste, folder, reload);
        actions.getStyleClass().add("toolbar-row");
        search.setPromptText("Search handouts and folders");
        search.textProperty().addListener((o, a, b) -> rebuild());
        tree.setCellFactory(view -> new HandoutCell());
        tree.setPrefHeight(320);
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnKeyPressed(e -> {
            Item selected = selected();
            if (e.getCode() == KeyCode.ENTER && selected != null && selected.file != null) open(selected.file);
            else if (e.getCode() == KeyCode.F2 && selected != null) rename(selected);
            else if (e.getCode() == KeyCode.DELETE && selected != null) delete(selected);
            else if (e.isControlDown() && e.getCode() == KeyCode.V) pasteImage();
            else return;
            e.consume();
        });
        tree.setOnContextMenuRequested(e -> {
            if (e.getTarget() == tree) {
                menu(new Item("", null)).show(tree, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        tree.setOnDragOver(e -> {
            if (accepts(e.getDragboard())) e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        });
        tree.setOnDragDropped(e -> {
            e.setDropCompleted(drop(e.getDragboard(), "", e.getTransferMode()));
            e.consume();
        });
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setText("Double-click to open. Right-click to share or show to players. Drag to move (Ctrl to copy). "
                + "Drop image files here or press Ctrl+V to add from the clipboard.");
        getChildren().addAll(actions, search, tree, hint);
    }

    public Map<String, javafx.scene.Node> controls() {
        return Collections.unmodifiableMap(controls);
    }

    /** Follows the hosted session; {@code null} detaches (not hosting). */
    public void bind(OnlineSessionServer next) {
        CampaignHandouts library = next == null || next.campaign() == null ? null : next.handouts();
        if (library == handouts) return;
        if (handouts != null) handouts.removeListener(listener);
        server = next;
        handouts = library;
        thumbnails.clear();
        if (library != null) library.addListener(listener);
        else imageWindow.closeAll(desktop.get());
        refresh();
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void refresh() {
        if (handouts == null) {
            listing = new CampaignHandouts.Listing(List.of(), List.of());
        } else {
            try {
                listing = handouts.list();
            } catch (IOException e) {
                LOG.warn("Could not read handouts", e);
                status.accept("Could not read the handouts: " + e.getMessage());
            }
        }
        rebuild();
    }

    private String parent(String folder) {
        return CampaignHandouts.parentOf(folder);
    }

    private static boolean matches(String name, String query) {
        return query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }

    private void rebuild() {
        String query = search.getText() == null ? "" : search.getText();
        Map<String, TreeItem<Item>> folders = new LinkedHashMap<>();
        TreeItem<Item> root = new TreeItem<>(new Item("", null));
        folders.put("", root);
        for (String folder : listing.folders()) {
            TreeItem<Item> item = new TreeItem<>(new Item(folder, null));
            folders.put(folder, item);
        }
        for (String folder : listing.folders()) {
            TreeItem<Item> parent = folders.get(parent(folder));
            (parent == null ? root : parent).getChildren().add(folders.get(folder));
        }
        listing.files().stream().sorted(CampaignHandouts.byName()).forEach(file -> {
            if (matches(file.name, query) || matches(file.folder, query)) {
                TreeItem<Item> parent = folders.getOrDefault(file.folder, root);
                parent.getChildren().add(new TreeItem<>(new Item(file.folder, file)));
            }
        });
        if (!query.isBlank()) prune(root, query);
        folders.forEach((path, item) -> {
            item.setExpanded(!query.isBlank() || expanded.contains(path));
            item.expandedProperty().addListener((o, was, value) -> {
                if (search.getText() == null || search.getText().isBlank()) {
                    if (value) expanded.add(path);
                    else expanded.remove(path);
                }
            });
        });
        tree.setRoot(root);
    }

    private void prune(TreeItem<Item> folder, String query) {
        folder.getChildren().removeIf(item -> {
            if (item.getValue().file != null) return false;
            prune(item, query);
            return item.getChildren().isEmpty() && !matches(item.getValue().folder, query);
        });
    }

    private Item selected() {
        TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getValue();
    }

    private String selectedFolder() {
        Item item = selected();
        return item == null ? "" : item.folder;
    }

    // ---- actions ----

    @FunctionalInterface
    private interface Operation {
        void run() throws IOException;
    }

    private void attempt(String failure, Operation action) {
        try {
            action.run();
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn(failure, e);
            Dialogs.error(window(), failure, e.getMessage());
        }
    }

    private void importFiles() {
        if (handouts == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add handout images");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(window());
        if (files != null) addFiles(files, selectedFolder());
    }

    private void addFiles(List<File> files, String folder) {
        for (File file : files) {
            if (file.isDirectory()) continue;
            attempt("Could not add " + file.getName(), () -> {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                handouts.addImage(folder, dot > 0 ? name.substring(0, dot) : name, Files.readAllBytes(file.toPath()));
                expanded.add(folder);
            });
        }
    }

    private void pasteImage() {
        if (handouts == null) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasFiles()) {
            addFiles(clipboard.getFiles(), selectedFolder());
            return;
        }
        Image image = clipboard.getImage();
        if (image == null) {
            Dialogs.error(window(), "Could not paste", "The clipboard does not contain an image.");
            return;
        }
        String folder = selectedFolder();
        attempt("Could not paste the image", () -> {
            var name = Dialogs.askText(window(), "Pasted image", "Name the pasted image", MaterialDesignC.CONTENT_PASTE, "Add",
                    "Pasted image", value -> {
                        try {
                            CampaignHandouts.checkName(value);
                            return null;
                        } catch (IllegalArgumentException e) {
                            return e.getMessage();
                        }
                    });
            if (name.isEmpty()) return;
            handouts.addImage(folder, name.get(), png(image));
            expanded.add(folder);
        });
    }

    static byte[] png(Image image) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (width <= 0 || height <= 0) throw new IOException("The image is empty.");
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(buffered, "png", out)) throw new IOException("The image could not be converted.");
        return out.toByteArray();
    }

    private Optional<String> askName(String title, String header, String action, String initial) {
        return Dialogs.askText(window(), title, header, MaterialDesignF.FOLDER_OUTLINE, action, initial, value -> {
            try {
                CampaignHandouts.checkName(value);
                return null;
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        });
    }

    private void newFolder() {
        if (handouts == null) return;
        String parent = selectedFolder();
        askName("New folder", "Folder name", "Create", "New folder").ifPresent(name -> attempt("Could not create the folder", () -> {
            expanded.add(parent);
            expanded.add(handouts.createFolder(parent, name));
        }));
    }

    private void rename(Item item) {
        if (handouts == null || item.file == null && item.folder.isEmpty()) return;
        askName("Rename", "New name", "Rename", item.name()).ifPresent(name -> attempt("Could not rename", () -> {
            if (item.file != null) handouts.renameFile(item.file.id, name);
            else {
                String renamed = handouts.renameFolder(item.folder, name);
                if (expanded.remove(item.folder)) expanded.add(renamed);
            }
        }));
    }

    private void delete(Item item) {
        if (handouts == null || item.file == null && item.folder.isEmpty()) return;
        String message = item.file != null ? "Delete \"" + item.name() + "\"? Players lose access to it."
                : "Delete the folder \"" + item.name() + "\" and every image inside it? Players lose access to them.";
        if (!Dialogs.confirmDanger(window(), "Delete", "Delete " + item.name() + "?", message, "Delete")) return;
        attempt("Could not delete", () -> {
            if (item.file != null) handouts.deleteFile(item.file.id);
            else handouts.deleteFolder(item.folder);
        });
    }

    private void duplicate(FileEntry file) {
        attempt("Could not copy", () -> handouts.copyFile(file.id, file.folder));
    }

    private void open(FileEntry file) {
        StackPane pane = desktop.get();
        if (handouts == null || pane == null) return;
        attempt("Could not open the image", () -> {
            Image image = new Image(handouts.open(file.id, null, true).toUri().toString());
            if (image.isError()) throw new IOException("The image could not be loaded.");
            imageWindow.open(pane, file.name, image, () -> show(file));
        });
    }

    private void show(FileEntry file) {
        if (handouts == null || server == null) return;
        attempt("Could not show the image", () -> {
            handouts.show(file.id);
            status.accept("Showing \"" + file.name + "\" to all players.");
        });
    }

    private List<Campaign.Player> players() {
        Campaign campaign = server == null ? null : server.campaign();
        return campaign == null ? List.of() : List.copyOf(campaign.getPlayers());
    }

    private void share(Item item) {
        if (handouts == null) return;
        List<String> ids = item.file != null ? List.of(item.file.id) : handouts_ids(item.folder);
        if (ids.isEmpty()) {
            status.accept("There are no images to share here.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        Dialogs.style(dialog, window(), "Share", "Who can see \"" + item.name() + "\"?", MaterialDesignS.SHARE_VARIANT_OUTLINE, false);
        CheckBox everyone = new CheckBox("All players (including players who join later)");
        VBox box = new VBox(8, everyone);
        Map<String, CheckBox> boxes = new LinkedHashMap<>();
        for (Campaign.Player player : players()) {
            CheckBox check = new CheckBox(player.getName());
            if (item.file != null) check.setSelected(item.file.players.contains(player.getId()));
            check.disableProperty().bind(everyone.selectedProperty());
            boxes.put(player.getId(), check);
            box.getChildren().add(check);
        }
        if (item.file != null) everyone.setSelected(item.file.allPlayers);
        if (item.file == null) {
            Label note = new Label("This replaces the sharing of every image in the folder.");
            note.getStyleClass().add("muted");
            box.getChildren().add(note);
        }
        box.setPrefWidth(340);
        dialog.getDialogPane().setContent(box);
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, apply);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != apply) return;
        Set<String> chosen = new LinkedHashSet<>();
        boxes.forEach((id, check) -> {
            if (check.isSelected()) chosen.add(id);
        });
        attempt("Could not change sharing", () -> handouts.setAccess(ids, everyone.isSelected(), everyone.isSelected() ? Set.of() : chosen));
    }

    private List<String> handouts_ids(String folder) {
        List<String> ids = new ArrayList<>();
        for (FileEntry file : listing.files())
            if (folder.isEmpty() || file.folder.equals(folder) || file.folder.startsWith(folder + "/")) ids.add(file.id);
        return ids;
    }

    private ContextMenu menu(Item item) {
        ContextMenu menu = new ContextMenu();
        if (item.file != null) {
            menu.getItems().add(action("Open", () -> open(item.file)));
            menu.getItems().add(action("Show to all players", () -> show(item.file)));
            menu.getItems().add(action("Share...", () -> share(item)));
            menu.getItems().add(action("Duplicate", () -> duplicate(item.file)));
        } else {
            menu.getItems().add(action("Add images...", this::importFiles));
            menu.getItems().add(action("Paste image", this::pasteImage));
            menu.getItems().add(action("New folder...", this::newFolder));
            if (!item.folder.isEmpty()) menu.getItems().add(action("Share all images here...", () -> share(item)));
        }
        if (item.file != null || !item.folder.isEmpty()) {
            menu.getItems().addAll(new SeparatorMenuItem(), action("Rename...", () -> rename(item)), action("Delete...", () -> delete(item)));
        }
        return menu;
    }

    private static MenuItem action(String name, Runnable action) {
        MenuItem item = new MenuItem(name);
        item.setOnAction(e -> action.run());
        return item;
    }

    private static boolean accepts(Dragboard board) {
        return board.hasContent(FILE_ID) || board.hasContent(FOLDER) || board.hasFiles();
    }

    private boolean drop(Dragboard board, String folder, TransferMode mode) {
        if (handouts == null) return false;
        boolean[] done = {false};
        attempt("Could not move the item", () -> {
            if (board.hasContent(FILE_ID)) {
                String id = (String) board.getContent(FILE_ID);
                if (mode == TransferMode.COPY) handouts.copyFile(id, folder);
                else handouts.moveFile(id, folder);
            } else if (board.hasContent(FOLDER)) {
                expanded.add(handouts.moveFolder((String) board.getContent(FOLDER), folder));
            } else if (board.hasFiles()) {
                addFiles(board.getFiles(), folder);
            } else {
                return;
            }
            expanded.add(folder);
            done[0] = true;
        });
        return done[0];
    }

    private Image thumbnail(FileEntry file) {
        return thumbnails.computeIfAbsent(file.id, id -> {
            try {
                return new Image(handouts.open(id, null, true).toUri().toString(), 40, 40, true, true, true);
            } catch (IOException | IllegalArgumentException e) {
                return null;
            }
        });
    }

    private static String access(FileEntry file) {
        if (file.allPlayers) return "All players";
        if (file.players.isEmpty()) return "Private";
        return file.players.size() == 1 ? "1 player" : file.players.size() + " players";
    }

    private final class HandoutCell extends TreeCell<Item> {
        HandoutCell() {
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null && getItem().file != null)
                    open(getItem().file);
            });
            setOnContextMenuRequested(e -> {
                if (getItem() == null) return;
                tree.getSelectionModel().select(getTreeItem());
                menu(getItem()).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            setOnDragDetected(e -> {
                Item item = getItem();
                if (item == null || item.file == null && item.folder.isEmpty()) return;
                Dragboard board = startDragAndDrop(item.file == null ? new TransferMode[]{TransferMode.MOVE} : TransferMode.COPY_OR_MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(item.file == null ? FOLDER : FILE_ID, item.file == null ? item.folder : item.file.id);
                board.setContent(content);
                e.consume();
            });
            setOnDragOver(e -> {
                if (getItem() != null && accepts(e.getDragboard())) {
                    e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                    pseudoClassStateChanged(DROP_TARGET, true);
                }
                e.consume();
            });
            setOnDragEntered(e -> {
                if (getItem() != null && getItem().file == null && accepts(e.getDragboard())) getTreeItem().setExpanded(true);
            });
            setOnDragExited(e -> pseudoClassStateChanged(DROP_TARGET, false));
            setOnDragDropped(e -> {
                pseudoClassStateChanged(DROP_TARGET, false);
                boolean success = getItem() != null && drop(e.getDragboard(), getItem().folder, e.getTransferMode());
                e.setDropCompleted(success);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            setTooltip(null);
            if (empty || item == null) return;
            if (item.file == null) {
                setText(item.name());
                setGraphic(Icons.icon(MaterialDesignF.FOLDER_OUTLINE));
                return;
            }
            Image thumb = thumbnail(item.file);
            ImageView view = new ImageView(thumb);
            view.setFitWidth(36);
            view.setFitHeight(36);
            view.setPreserveRatio(true);
            Label name = new Label(item.file.name);
            Label detail = new Label(access(item.file));
            detail.getStyleClass().add("muted");
            VBox text = new VBox(name, detail);
            HBox row = new HBox(8, view, text);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (item.file.allPlayers || !item.file.players.isEmpty()) {
                var icon = Icons.icon(item.file.allPlayers ? MaterialDesignM.MONITOR_SHARE : MaterialDesignS.SHARE_VARIANT_OUTLINE);
                row.getChildren().add(icon);
            }
            setGraphic(row);
            setTooltip(new Tooltip(item.file.name + " (" + access(item.file) + ")"));
        }
    }
}
