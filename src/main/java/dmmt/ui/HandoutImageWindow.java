package dmmt.ui;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.ArrayList;
import java.util.List;

/** A draggable, resizable image viewer floating over the DM map, reusing the compendium window's look. */
public final class HandoutImageWindow {
    private final List<BorderPane> frames = new ArrayList<>();

    /** Opens the image; {@code showToPlayers} (nullable) adds a button that shows it to every player. */
    public void open(StackPane desktop, String title, Image image, Runnable showToPlayers) {
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Pane holder = new Pane(view);
        holder.setMinSize(0, 0);
        holder.setStyle("-fx-background-color: #0e1115;");
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(holder.widthProperty());
        clip.heightProperty().bind(holder.heightProperty());
        holder.setClip(clip);
        double imageWidth = Math.max(1, image.getWidth());
        double imageHeight = Math.max(1, image.getHeight());
        ChangeListener<Number> fitImage = (o, a, b) -> {
            double w = Math.max(1, holder.getWidth());
            double h = Math.max(1, holder.getHeight());
            double scale = Math.min(w / imageWidth, h / imageHeight);
            double shownW = imageWidth * scale;
            double shownH = imageHeight * scale;
            view.setFitWidth(shownW);
            view.setFitHeight(shownH);
            view.setLayoutX((w - shownW) / 2);
            view.setLayoutY((h - shownH) / 2);
        };
        holder.widthProperty().addListener(fitImage);
        holder.heightProperty().addListener(fitImage);
        Label label = new Label(title);
        label.setMaxWidth(Double.MAX_VALUE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, label, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 4, 12));
        header.setCursor(Cursor.MOVE);
        if (showToPlayers != null) {
            Button show = Icons.button(MaterialDesignM.MONITOR_SHARE, "Show this image to all players", showToPlayers);
            header.getChildren().add(show);
        }
        BorderPane frame = new BorderPane(holder);
        Button close = Icons.button(MaterialDesignC.CLOSE, "Close", () -> {
            desktop.getChildren().remove(frame);
            frames.remove(frame);
        });
        header.getChildren().add(close);
        frame.setTop(header);
        Label grip = new Label("\u25E2");
        grip.setMinSize(18, 18);
        grip.setCursor(Cursor.SE_RESIZE);
        HBox bottom = new HBox(grip);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        frame.setBottom(bottom);
        frame.getStyleClass().add("compendium-window");
        frame.setManaged(false);
        frame.setPickOnBounds(true);

        double ratio = image.getHeight() <= 0 ? 1 : image.getWidth() / image.getHeight();
        double height = Math.min(Math.max(240, image.getHeight() + 60), Math.max(240, desktop.getHeight() - 60));
        double width = Math.min(Math.max(320, (height - 60) * ratio), Math.max(320, desktop.getWidth() - 60));
        int offset = (frames.size() % 6) * 26;
        frame.resizeRelocate(Math.max(0, (desktop.getWidth() - width) / 2 + offset),
                Math.max(0, (desktop.getHeight() - height) / 2 + offset), width, height);

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
            frame.relocate(Math.clamp(drag[2] + event.getSceneX() - drag[0], 0, Math.max(0, desktop.getWidth() - 80)),
                    Math.clamp(drag[3] + event.getSceneY() - drag[1], 0, Math.max(0, desktop.getHeight() - 40)));
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
            frame.resize(Math.max(240, drag[2] + event.getSceneX() - drag[0]), Math.max(180, drag[3] + event.getSceneY() - drag[1]));
            event.consume();
        });
        frame.setOnMousePressed(event -> frame.toFront());
        frames.add(frame);
        desktop.getChildren().add(frame);
        frame.applyCss();
        frame.layout();
        fitImage.changed(null, null, null);
    }

    public void closeAll(StackPane desktop) {
        if (desktop != null) desktop.getChildren().removeAll(frames);
        frames.clear();
    }
}
