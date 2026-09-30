package dmmt.ui;

import dmmt.ui.HandoutLayout.Rect;
import dmmt.ui.HandoutLayout.Size;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Non-modal DM window for pasting images from the clipboard and showing them together to the players.
 * The images and the "shown" state are session-only; closing the window discards them.
 */
public final class HandoutWindow {

    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");
    private static final Color SELECTION_COLOR = Color.web("#4da3ff");

    // Remembered for the whole session and reused for the next handout.
    private static int lastRotation;

    private final Stage stage = new Stage();
    private final Canvas preview = new Canvas();
    private final Label hint = new Label();
    private final Label emptyLabel;
    private final ToggleButton showToggle;
    private final Button deleteButton;
    private final Button clearButton;
    private final BooleanSupplier playerWindowOpen;
    private final Runnable onChange;
    private final List<Image> images = new ArrayList<>();
    private int selected = -1;
    private int rotation = lastRotation;
    private boolean shown;

    public HandoutWindow(Stage owner, java.util.List<Image> icons, BooleanSupplier playerWindowOpen,
                         Runnable onChange, Runnable onClosed) {
        this.playerWindowOpen = playerWindowOpen;
        this.onChange = onChange;

        emptyLabel = new Label("Press Ctrl+V to paste an image");
        emptyLabel.getStyleClass().add("muted");
        emptyLabel.setMouseTransparent(true);
        StackPane previewPane = new StackPane(preview, emptyLabel);
        previewPane.setMinSize(0, 0);
        previewPane.setStyle("-fx-background-color: #111111;");
        preview.widthProperty().bind(previewPane.widthProperty());
        preview.heightProperty().bind(previewPane.heightProperty());
        preview.widthProperty().addListener((o, a, b) -> drawPreview());
        preview.heightProperty().addListener((o, a, b) -> drawPreview());
        preview.setOnMousePressed(event -> {
            preview.requestFocus();
            int hit = hitTest(event.getX(), event.getY());
            if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.SECONDARY) {
                select(hit);
            }
            if (event.getButton() == MouseButton.SECONDARY && hit >= 0) {
                showContextMenu(event.getScreenX(), event.getScreenY());
            }
        });
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        showToggle = Icons.toggle(MaterialDesignE.EYE, "Show to players");
        showToggle.setOnAction(e -> setShown(showToggle.isSelected()));
        deleteButton = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected image (Delete)",
                this::deleteSelected);
        clearButton = Icons.button(MaterialDesignD.DELETE_SWEEP_OUTLINE, "Remove all images", this::clearAll);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6,
                Icons.button(MaterialDesignC.CONTENT_PASTE, "Add an image from the clipboard (Ctrl+V)", this::paste),
                deleteButton, clearButton,
                Icons.separator(),
                Icons.button(MaterialDesignR.ROTATE_LEFT, "Rotate left by 90 degrees", () -> rotate(-1)),
                Icons.button(MaterialDesignR.ROTATE_RIGHT, "Rotate right by 90 degrees", () -> rotate(1)),
                spacer, showToggle);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, previewPane, hint, controls);
        root.setPadding(new Insets(10));
        root.getStyleClass().add("handout-root");

        Scene scene = new Scene(root, 520, 460);
        scene.getStylesheets().add(Icons.STYLESHEET);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.V) {
                paste();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && selected >= 0) {
                deleteSelected();
                event.consume();
            }
        });
        stage.setTitle("Handout");
        stage.getIcons().setAll(icons);
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.setOnHidden(event -> {
            images.clear();
            selected = -1;
            shown = false;
            onClosed.run();
        });
        refreshState();
    }

    public void show() {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            stage.show();
        }
    }

    public void close() {
        stage.close();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public boolean isShownToPlayers() {
        return shown && !images.isEmpty();
    }

    /** Copy of the current images in display order. */
    public List<Image> getImages() {
        return List.copyOf(images);
    }

    public int getRotation() {
        return rotation;
    }

    /** Called by the application when the player window opens or closes. */
    public void playerWindowChanged() {
        if (!playerWindowOpen.getAsBoolean()) {
            shown = false;
        }
        refreshState();
        onChange.run();
    }

    private void setShown(boolean value) {
        shown = value && !images.isEmpty() && playerWindowOpen.getAsBoolean();
        refreshState();
        onChange.run();
    }

    private void rotate(int steps) {
        rotation = Math.floorMod(rotation + steps * 90, 360);
        lastRotation = rotation;
        drawPreview();
        onChange.run();
    }

    private void select(int index) {
        selected = index;
        refreshState();
    }

    private void showContextMenu(double screenX, double screenY) {
        MenuItem delete = new MenuItem("Delete", Icons.icon(MaterialDesignD.DELETE_OUTLINE));
        delete.setOnAction(e -> deleteSelected());
        ContextMenu menu = new ContextMenu(delete);
        menu.show(preview, screenX, screenY);
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= images.size()) {
            return;
        }
        images.remove(selected);
        selected = -1;
        afterImagesChanged();
    }

    private void clearAll() {
        if (images.isEmpty()) {
            return;
        }
        images.clear();
        selected = -1;
        afterImagesChanged();
    }

    private void afterImagesChanged() {
        if (images.isEmpty()) {
            shown = false;
        }
        refreshState();
        onChange.run();
    }

    private void paste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        List<Image> pasted = new ArrayList<>();
        if (clipboard.hasFiles()) {
            for (File file : clipboard.getFiles()) {
                if (isImageFile(file)) {
                    Image candidate = new Image(file.toURI().toString(), false);
                    if (!candidate.isError() && candidate.getWidth() > 0) {
                        pasted.add(candidate);
                    }
                }
            }
        }
        if (pasted.isEmpty() && clipboard.hasImage()) {
            Image candidate = clipboard.getImage();
            if (candidate != null && candidate.getWidth() > 0 && candidate.getHeight() > 0) {
                pasted.add(candidate);
            }
        }
        if (pasted.isEmpty()) {
            hint.setText("The clipboard does not contain an image. Copy an image (or an image file) and try again.");
            return;
        }
        images.addAll(pasted);
        selected = -1;
        refreshState();
        onChange.run();
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private void refreshState() {
        boolean hasImages = !images.isEmpty();
        boolean playerOpen = playerWindowOpen.getAsBoolean();
        showToggle.setDisable(!hasImages || !playerOpen);
        showToggle.setSelected(shown && hasImages);
        Icons.tooltip(showToggle, !hasImages ? "Paste an image first"
                : !playerOpen ? "Open the player window first"
                : "Show the handout to the players (off returns to the map)");
        deleteButton.setDisable(selected < 0);
        clearButton.setDisable(!hasImages);
        emptyLabel.setVisible(!hasImages);
        if (hasImages) {
            hint.setText(images.size() == 1 ? "Paste more images to show them together. Click an image to select it."
                    : images.size() + " images. Click an image to select it; Delete or right-click removes it.");
        }
        drawPreview();
    }

    private int hitTest(double x, double y) {
        List<Rect> rects = layout(preview.getWidth(), preview.getHeight(), images, rotation);
        return HandoutLayout.hitTest(rects, x, y, rotation, preview.getWidth(), preview.getHeight());
    }

    private void drawPreview() {
        GraphicsContext gc = preview.getGraphicsContext2D();
        gc.clearRect(0, 0, preview.getWidth(), preview.getHeight());
        drawBoard(gc, images, rotation, preview.getWidth(), preview.getHeight(), selected);
    }

    private static List<Rect> layout(double width, double height, List<Image> images, int rotation) {
        Size space = HandoutLayout.layoutSize(width, height, rotation);
        List<Size> sizes = new ArrayList<>();
        for (Image image : images) {
            sizes.add(new Size(image.getWidth(), image.getHeight()));
        }
        return HandoutLayout.compute(sizes, space.width(), space.height());
    }

    /**
     * Draws all images in the best-fitting grid, rotated in 90-degree steps as a whole. {@code selectedIndex}
     * (or -1) gets a highlight border; the player screen passes -1.
     */
    public static void drawBoard(GraphicsContext gc, List<Image> images, int rotation, double width, double height,
                                 int selectedIndex) {
        if (images.isEmpty()) {
            return;
        }
        Size space = HandoutLayout.layoutSize(width, height, rotation);
        List<Rect> rects = layout(width, height, images, rotation);
        gc.save();
        gc.translate(width / 2, height / 2);
        gc.rotate(rotation);
        gc.translate(-space.width() / 2, -space.height() / 2);
        for (int i = 0; i < images.size() && i < rects.size(); i++) {
            Rect r = rects.get(i);
            gc.drawImage(images.get(i), r.x(), r.y(), r.width(), r.height());
        }
        if (selectedIndex >= 0 && selectedIndex < rects.size()) {
            Rect r = rects.get(selectedIndex);
            gc.setStroke(SELECTION_COLOR);
            gc.setLineWidth(3);
            gc.strokeRect(r.x() + 1.5, r.y() + 1.5, r.width() - 3, r.height() - 3);
        }
        gc.restore();
    }
}
