package dmmt.ui;

import dmmt.service.MapLibraryService;
import dmmt.service.MapLibraryService.Entry;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Picks a folder inside the map library plus a map name. Replaces the system file chooser for saving and importing
 * maps, so maps always end up inside the library.
 */
public final class MapLocationDialog {

    public record Selection(Path folder, String name) {
    }

    private MapLocationDialog() {
    }

    public static Optional<Selection> show(Window owner, MapLibraryService library, String title, Ikon ikon,
                                           String actionLabel, String initialName, Path initialFolder) {
        return show(owner, library, title, ikon, actionLabel, initialName, initialFolder, true);
    }

    /** Folder-only variant (no name field) for batch imports. */
    public static Optional<Path> showFolder(Window owner, MapLibraryService library, String title, Ikon ikon,
                                            String actionLabel, Path initialFolder) {
        return show(owner, library, title, ikon, actionLabel, null, initialFolder, false).map(Selection::folder);
    }

    private static Optional<Selection> show(Window owner, MapLibraryService library, String title, Ikon ikon,
                                            String actionLabel, String initialName, Path initialFolder,
                                            boolean askName) {
        Dialog<Selection> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, title, askName ? "Choose a name and a folder in your map library"
                : "Choose the folder in your map library to import the maps into", ikon, false);

        TextField nameField = new TextField(initialName == null ? "" : initialName);
        nameField.setPromptText("Map name");

        TreeView<Entry> tree = new TreeView<>();
        tree.getStyleClass().add("location-tree");
        tree.setPrefHeight(280);
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(entry.name());
                FontIcon icon = Icons.icon(getTreeItem() == view.getRoot() ? MaterialDesignF.FOLDER_HOME_OUTLINE
                        : MaterialDesignF.FOLDER_OUTLINE);
                icon.getStyleClass().add("folder-icon");
                setGraphic(icon);
            }
        });

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        Label preview = new Label();
        preview.getStyleClass().add("muted");
        preview.setWrapText(true);

        Runnable updatePreview = () -> {
            Path folder = selectedFolder(tree, library);
            String relative = library.getRoot().relativize(folder).toString().replace('\\', '/');
            String path = "Library/" + (relative.isEmpty() ? "" : relative + "/");
            if (!askName) {
                preview.setText("Imported into: " + path);
                return;
            }
            String name = nameField.getText().trim().isEmpty() ? "…" : nameField.getText().trim();
            preview.setText("Saved as: " + path + name);
        };

        Button newFolder = new Button("New folder", Icons.icon(MaterialDesignF.FOLDER_PLUS_OUTLINE));
        newFolder.setOnAction(event -> {
            Path parent = selectedFolder(tree, library);
            Optional<String> folderName = Dialogs.askText(dialog.getDialogPane().getScene().getWindow(), "New folder",
                    "Create a folder in \"" + displayName(parent, library) + "\"", MaterialDesignF.FOLDER_PLUS_OUTLINE,
                    "Create", "", MapLocationDialog::nameProblem);
            if (folderName.isEmpty()) {
                return;
            }
            try {
                Path created = library.createFolder(parent, folderName.get());
                load(tree, library, created);
            } catch (IOException exception) {
                error.setText(exception.getMessage());
            }
        });

        Label nameLabel = new Label("NAME");
        nameLabel.getStyleClass().add("caption");
        Label folderLabel = new Label("FOLDER");
        folderLabel.getStyleClass().add("caption");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox folderHeader = new HBox(8, folderLabel, spacer, newFolder);
        folderHeader.setStyle("-fx-alignment: bottom-left;");

        VBox content = askName ? new VBox(6, nameLabel, nameField, new Region(), folderHeader, tree, preview, error)
                : new VBox(6, folderHeader, tree, preview, error);
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);

        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
        actionButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!askName) {
                return;
            }
            try {
                library.newMapFile(selectedFolder(tree, library), nameField.getText());
            } catch (IOException exception) {
                error.setText(exception.getMessage());
                event.consume();
            }
        });

        nameField.textProperty().addListener((observable, oldValue, newValue) -> {
            error.setText("");
            updatePreview.run();
        });
        tree.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            error.setText("");
            updatePreview.run();
        });

        load(tree, library, initialFolder);
        updatePreview.run();

        dialog.setResultConverter(buttonType -> {
            if (buttonType != action) {
                return null;
            }
            if (!askName) {
                return new Selection(selectedFolder(tree, library), null);
            }
            try {
                return new Selection(selectedFolder(tree, library), MapLibraryService.cleanName(nameField.getText()));
            } catch (IOException exception) {
                return null;
            }
        });
        if (askName) {
            Platform.runLater(() -> {
                nameField.requestFocus();
                nameField.selectAll();
            });
        }
        return dialog.showAndWait();
    }

    static String nameProblem(String text) {
        try {
            MapLibraryService.cleanName(text);
            return null;
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    private static String displayName(Path folder, MapLibraryService library) {
        return folder.equals(library.getRoot()) ? "Library" : folder.getFileName().toString();
    }

    private static Path selectedFolder(TreeView<Entry> tree, MapLibraryService library) {
        TreeItem<Entry> selected = tree.getSelectionModel().getSelectedItem();
        return selected == null || selected.getValue() == null ? library.getRoot() : selected.getValue().path();
    }

    private static void load(TreeView<Entry> tree, MapLibraryService library, Path select) {
        Entry root;
        try {
            root = library.scanFolders();
        } catch (IOException exception) {
            root = new Entry(MapLibraryService.Kind.FOLDER, "Library", library.getRoot(), null, java.util.List.of());
        }
        TreeItem<Entry> rootItem = build(root);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);
        TreeItem<Entry> target = select == null ? null : find(rootItem, select.toAbsolutePath().normalize());
        if (target == null) {
            target = rootItem;
        }
        for (TreeItem<Entry> parent = target.getParent(); parent != null; parent = parent.getParent()) {
            parent.setExpanded(true);
        }
        tree.getSelectionModel().select(target);
        int row = tree.getRow(target);
        if (row > 3) {
            tree.scrollTo(row - 3);
        }
    }

    private static TreeItem<Entry> build(Entry entry) {
        TreeItem<Entry> item = new TreeItem<>(entry);
        for (Entry child : entry.children()) {
            if (child.isFolder()) {
                item.getChildren().add(build(child));
            }
        }
        return item;
    }

    static TreeItem<Entry> find(TreeItem<Entry> item, Path path) {
        if (item.getValue() != null && item.getValue().path().toAbsolutePath().normalize().equals(path)) {
            return item;
        }
        for (TreeItem<Entry> child : item.getChildren()) {
            TreeItem<Entry> found = find(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
