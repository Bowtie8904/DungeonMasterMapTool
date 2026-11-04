package dmmt.ui;

import dmmt.online.OnlineSessionServer;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

/** Uses the browser's real compendium DOM and CSS inside the DM map, without another OS window. */
public final class CompendiumWindow {
    private StackPane desktop;
    private BorderPane frame;
    private WebView view;
    private OnlineSessionServer session;
    private Runnable closed;
    private final ChangeListener<Number> resized = (o, before, after) -> fit();

    public void show(StackPane desktop, OnlineSessionServer server, Runnable closed) {
        if (frame != null && session == server) {
            frame.toFront();
            return;
        }
        close();
        this.desktop = desktop;
        this.session = server;
        this.closed = closed;
        view = new WebView();
        view.setContextMenuEnabled(false);
        Label title = new Label("Compendium");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var close = Icons.button(MaterialDesignC.CLOSE, "Close compendium", this::close);
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 4, 12));
        header.setCursor(Cursor.MOVE);
        Label status = new Label();
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);
        Label grip = new Label("\u25E2");
        grip.setMinSize(18, 18);
        grip.setCursor(Cursor.SE_RESIZE);
        HBox bottom = new HBox(status, spacer());
        bottom.getChildren().add(grip);
        frame = new BorderPane(view, header, null, bottom, null);
        frame.getStyleClass().add("compendium-window");
        frame.setManaged(false);
        frame.setPickOnBounds(true);
        double width = Math.min(1180, desktop.getWidth() - 30);
        double height = Math.min(800, desktop.getHeight() - 30);
        frame.resizeRelocate((desktop.getWidth() - width) / 2, (desktop.getHeight() - height) / 2, width, height);
        double[] drag = new double[4];
        header.setOnMousePressed(event -> {
            drag[0] = event.getSceneX();
            drag[1] = event.getSceneY();
            drag[2] = frame.getLayoutX();
            drag[3] = frame.getLayoutY();
            frame.toFront();
            event.consume();
        });
        header.setOnMouseDragged(event -> {
            frame.relocate(drag[2] + event.getSceneX() - drag[0], drag[3] + event.getSceneY() - drag[1]);
            fit();
            event.consume();
        });
        grip.setOnMousePressed(event -> {
            drag[0] = event.getSceneX();
            drag[1] = event.getSceneY();
            drag[2] = frame.getWidth();
            drag[3] = frame.getHeight();
            event.consume();
        });
        grip.setOnMouseDragged(event -> {
            frame.resize(Math.max(650, drag[2] + event.getSceneX() - drag[0]),
                    Math.max(400, drag[3] + event.getSceneY() - drag[1]));
            fit();
            event.consume();
        });
        view.getEngine().getLoadWorker().stateProperty().addListener((o, before, state) -> {
            if (state == Worker.State.FAILED) {
                status.setText("Could not load the compendium. Close it and reopen it while hosting.");
                status.setVisible(true);
                status.setManaged(true);
            }
        });
        desktop.getChildren().add(frame);
        desktop.widthProperty().addListener(resized);
        desktop.heightProperty().addListener(resized);
        fit();
        view.getEngine().load(server.dmCompendiumUrl());
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private void fit() {
        if (frame == null) return;
        double width = Math.min(frame.getWidth(), Math.max(1, desktop.getWidth()));
        double height = Math.min(frame.getHeight(), Math.max(1, desktop.getHeight()));
        frame.resizeRelocate(Math.clamp(frame.getLayoutX(), 0, Math.max(0, desktop.getWidth() - width)),
                Math.clamp(frame.getLayoutY(), 0, Math.max(0, desktop.getHeight() - height)), width, height);
    }

    public void syncSession(OnlineSessionServer server) {
        if (session != server) close();
    }

    public boolean containsFocus() {
        if (frame == null || frame.getScene() == null) return false;
        for (var node = frame.getScene().getFocusOwner(); node != null; node = node.getParent())
            if (node == frame) return true;
        return false;
    }

    public void close() {
        if (frame == null) return;
        desktop.widthProperty().removeListener(resized);
        desktop.heightProperty().removeListener(resized);
        desktop.getChildren().remove(frame);
        view.getEngine().load(null);
        frame = null;
        view = null;
        session = null;
        desktop = null;
        Runnable callback = closed;
        closed = null;
        callback.run();
    }
}
