package dmmt.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Non-modal DM window for pasting an image from the clipboard and showing it to the players.
 * The image and the "shown" state are session-only; closing the window discards them.
 */
public final class HandoutWindow {

    private static final double FIT_FRACTION = 0.9;
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");

    // Remembered for the whole session and reused for the next handout.
    private static int lastRotation;

    private final Stage stage = new Stage();
    private final Canvas preview = new Canvas();
    private final Label hint = new Label();
    private final ToggleButton showToggle;
    private final BooleanSupplier playerWindowOpen;
    private final Runnable onChange;
    private final Runnable onClosed;
    private Image image;
    private int rotation = lastRotation;
    private boolean shown;

    public HandoutWindow(Stage owner, java.util.List<Image> icons, BooleanSupplier playerWindowOpen,
                         Runnable onChange, Runnable onClosed) {
        this.playerWindowOpen = playerWindowOpen;
        this.onChange = onChange;
        this.onClosed = onClosed;

        Label empty = new Label("Press Ctrl+V to paste an image");
        empty.getStyleClass().add("muted");
        empty.setMouseTransparent(true);
        StackPane previewPane = new StackPane(preview, empty);
        previewPane.setMinSize(0, 0);
        previewPane.setStyle("-fx-background-color: #111111;");
        preview.widthProperty().bind(previewPane.widthProperty());
        preview.heightProperty().bind(previewPane.heightProperty());
        preview.widthProperty().addListener((o, a, b) -> drawPreview());
        preview.heightProperty().addListener((o, a, b) -> drawPreview());
        VBox.setVgrow(previewPane, Priority.ALWAYS);
        emptyLabel = empty;

        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        showToggle = Icons.toggle(MaterialDesignE.EYE, "Show to players");
        showToggle.setOnAction(e -> setShown(showToggle.isSelected()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6,
                Icons.button(MaterialDesignC.CONTENT_PASTE, "Paste an image from the clipboard (Ctrl+V)", this::paste),
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
            }
        });
        stage.setTitle("Handout");
        stage.getIcons().setAll(icons);
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.setOnHidden(event -> {
            image = null;
            shown = false;
            onClosed.run();
        });
        refreshState();
    }

    private final Label emptyLabel;

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
        return shown && image != null;
    }

    public Image getImage() {
        return image;
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
        shown = value && image != null && playerWindowOpen.getAsBoolean();
        refreshState();
        onChange.run();
    }

    private void rotate(int steps) {
        rotation = Math.floorMod(rotation + steps * 90, 360);
        lastRotation = rotation;
        drawPreview();
        onChange.run();
    }

    private void paste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        Image pasted = null;
        if (clipboard.hasFiles()) {
            for (File file : clipboard.getFiles()) {
                if (isImageFile(file)) {
                    Image candidate = new Image(file.toURI().toString(), false);
                    if (!candidate.isError() && candidate.getWidth() > 0) {
                        pasted = candidate;
                        break;
                    }
                }
            }
        }
        if (pasted == null && clipboard.hasImage()) {
            pasted = clipboard.getImage();
        }
        if (pasted == null || pasted.getWidth() <= 0 || pasted.getHeight() <= 0) {
            hint.setText("The clipboard does not contain an image. Copy an image (or an image file) and try again.");
            return;
        }
        image = pasted;
        refreshState();
        onChange.run();
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private void refreshState() {
        boolean hasImage = image != null;
        boolean playerOpen = playerWindowOpen.getAsBoolean();
        showToggle.setDisable(!hasImage || !playerOpen);
        showToggle.setSelected(shown);
        Icons.tooltip(showToggle, !hasImage ? "Paste an image first"
                : !playerOpen ? "Open the player window first"
                : "Show the handout to the players (off returns to the map)");
        emptyLabel.setVisible(!hasImage);
        if (hasImage) {
            hint.setText("");
        }
        drawPreview();
    }

    private void drawPreview() {
        GraphicsContext gc = preview.getGraphicsContext2D();
        gc.clearRect(0, 0, preview.getWidth(), preview.getHeight());
        if (image != null) {
            drawRotated(gc, image, rotation, preview.getWidth(), preview.getHeight(), 1.0);
        }
    }

    /** Draws the image centred and scaled to fit {@code fraction} of the area, rotated in 90-degree steps. */
    public static void drawRotated(GraphicsContext gc, Image image, int rotation, double width, double height,
                                   double fraction) {
        boolean swapped = rotation % 180 != 0;
        double iw = swapped ? image.getHeight() : image.getWidth();
        double ih = swapped ? image.getWidth() : image.getHeight();
        double scale = Math.min(width * fraction / iw, height * fraction / ih);
        gc.save();
        gc.translate(width / 2, height / 2);
        gc.rotate(rotation);
        gc.drawImage(image, -image.getWidth() * scale / 2, -image.getHeight() * scale / 2,
                image.getWidth() * scale, image.getHeight() * scale);
        gc.restore();
    }

    public static double fitFraction() {
        return FIT_FRACTION;
    }
}
