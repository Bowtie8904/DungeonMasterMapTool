package dmmt.ui;

import dmmt.log.Log;
import dmmt.remote.RemoteDirectories;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Folder selection against the host; never interprets a host path using the client filesystem. */
public final class RemoteDirectoryWindow {
    private static final Log LOG = Log.of(RemoteDirectoryWindow.class);
    private final Stage stage = new Stage();
    private final TextField address = new TextField();
    private final ListView<RemoteDirectories.Entry> entries = new ListView<>();
    private final Label status = new Label();
    private final Button roots = new Button("Host roots");
    private final Button up = new Button("Parent");
    private final Button go = new Button("Open host path");
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final Button choose = new Button("Use this host folder");
    private final Function<RemoteDirectories.Query, CompletableFuture<RemoteDirectories.Listing>> query;
    private final Consumer<String> selected;
    private RemoteDirectories.Listing listing;
    private CompletableFuture<RemoteDirectories.Listing> pending;
    private long generation;

    private RemoteDirectoryWindow(Window owner, Function<RemoteDirectories.Query,
            CompletableFuture<RemoteDirectories.Listing>> query, Consumer<String> selected) {
        this.query = query;
        this.selected = selected;
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Choose folder on host");
        Dialogs.inheritIcons(stage, owner);
        address.setPromptText("Absolute directory path on host A");
        address.setId("remote-directory-path");
        entries.setId("remote-directory-entries");
        choose.setId("remote-directory-choose");
        status.setId("remote-directory-status");
        status.setWrapText(true);
        entries.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(RemoteDirectories.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        entries.setOnMouseClicked(event -> {
            var item = entries.getSelectionModel().getSelectedItem();
            if (item != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) load(item.path(), 0);
        });
        entries.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                var item = entries.getSelectionModel().getSelectedItem();
                if (item != null) load(item.path(), 0);
                event.consume();
            }
        });
        roots.setOnAction(event -> load(null, 0));
        up.setOnAction(event -> { if (listing != null) load(listing.parent(), 0); });
        go.setOnAction(event -> openAddress());
        address.setOnAction(event -> openAddress());
        previous.setOnAction(event -> load(listing.path(), listing.offset() - RemoteDirectories.PAGE_SIZE));
        next.setOnAction(event -> load(listing.path(), listing.offset() + RemoteDirectories.PAGE_SIZE));
        choose.setOnAction(event -> {
            if (listing != null && listing.error() == null && listing.path() != null && pending == null) {
                LOG.info("Host folder chosen: {}", listing.path());
                selected.accept(listing.path());
                stage.close();
            }
        });
        var cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        var pathRow = new HBox(8, address, go);
        HBox.setHgrow(address, Priority.ALWAYS);
        var content = new VBox(10, new Label("Folders on host A. No files are downloaded or directories created."),
                pathRow, new HBox(8, roots, up), entries, status, new HBox(8, previous, next, choose, cancel));
        VBox.setVgrow(entries, Priority.ALWAYS);
        content.setStyle("-fx-padding: 16;");
        var scene = new Scene(content, 760, 480);
        scene.getStylesheets().add(Icons.STYLESHEET);
        stage.setScene(scene);
        stage.setOnHidden(event -> {
            generation++;
            if (pending != null) pending.cancel(false);
            pending = null;
        });
    }

    public static void show(Window owner, Function<RemoteDirectories.Query,
            CompletableFuture<RemoteDirectories.Listing>> query, Consumer<String> selected) {
        var window = new RemoteDirectoryWindow(owner, query, selected);
        LOG.info("Opening host folder chooser");
        window.stage.show();
        window.load(null, 0);
    }

    private void openAddress() {
        load(address.getText().isBlank() ? null : address.getText().strip(), 0);
    }

    private void load(String path, int offset) {
        long request = ++generation;
        if (pending != null) pending.cancel(false);
        listing = null;
        entries.getItems().clear();
        status.setText("Loading host folders...");
        setBusy(true);
        try {
            pending = query.apply(new RemoteDirectories.Query(path, offset));
            pending.whenComplete((result, failure) -> Platform.runLater(() -> {
                if (request != generation || !stage.isShowing()) return;
                pending = null;
                listing = result;
                setBusy(false);
                if (failure != null) {
                    LOG.warn("Could not load host folders: {}", path, failure);
                    status.setText("Could not load host folders. Check the connection and try again.");
                } else {
                    address.setText(result.path() == null ? "" : result.path());
                    entries.getItems().setAll(result.entries());
                    status.setText(result.error() != null ? result.error()
                            : result.path() == null ? "Open a host root to choose a folder."
                            : "Host: " + result.path() + " (page " + (result.offset() / RemoteDirectories.PAGE_SIZE + 1) + ")");
                }
            }));
        } catch (IllegalArgumentException invalid) {
            pending = null;
            setBusy(false);
            status.setText("Enter a valid absolute host path (at most 4096 characters).");
        }
    }

    private void setBusy(boolean busy) {
        go.setDisable(busy);
        roots.setDisable(busy);
        up.setDisable(busy || listing == null || listing.path() == null);
        entries.setDisable(busy);
        previous.setDisable(busy || listing == null || listing.error() != null || listing.offset() == 0);
        next.setDisable(busy || listing == null || listing.error() != null || !listing.more());
        choose.setDisable(busy || listing == null || listing.error() != null || listing.path() == null);
    }
}
