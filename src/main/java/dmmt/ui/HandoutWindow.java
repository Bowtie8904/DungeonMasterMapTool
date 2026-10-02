package dmmt.ui;

import lombok.Getter;
import dmmt.ui.HandoutLayout.Rect;
import dmmt.ui.HandoutLayout.Size;
import dmmt.ui.HandoutLayout.Panel;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Non-modal DM window for pasting images from the clipboard and showing them together to the players.
 * The images and the "shown" state are session-only; closing the window discards them.
 */
public final class HandoutWindow {

    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");
    private static final Color SELECTION_COLOR = Color.web("#4da3ff");
    private static final Color SHOWN_COLOR = Color.web("#46c46b");

    // Remembered for the whole session and reused for the next handout.
    private static int lastRotation;

    private final Stage stage = new Stage();
    private final Canvas preview = new Canvas();
    private final Label hint = new Label();
    private final Label emptyLabel;
    private final ToggleButton showToggle;
    private final Button deleteButton;
    private final ToggleButton showSelectedToggle;
    private final Button clearButton;
    private final BooleanSupplier playerWindowOpen;
    private final Runnable onChange;
    private final List<Image> images = new ArrayList<>();
    private final Set<Image> selection = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Images the players see while shown; null means all of them. */
    private Set<Image> showOnly;
    @Getter
    private int rotation = lastRotation;
    @Getter
    private boolean mirrored;
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
            if (event.getButton() == MouseButton.PRIMARY) {
                click(hit, event.isShortcutDown() || event.isShiftDown());
            } else if (event.getButton() == MouseButton.SECONDARY && hit >= 0) {
                if (!selection.contains(images.get(hit))) {
                    click(hit, false);
                }
                showContextMenu(event.getScreenX(), event.getScreenY());
            }
        });
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        showToggle = Icons.toggle(MaterialDesignE.EYE, "Show to players");
        showToggle.setOnAction(e -> setShown(showToggle.isSelected()));
        deleteButton = Icons.button(MaterialDesignD.DELETE_OUTLINE, "Delete the selected images (Delete)",
                this::deleteSelected);
        showSelectedToggle = Icons.toggle(MaterialDesignE.EYE_CHECK_OUTLINE, "Show only the selected images to the players");
        showSelectedToggle.setOnAction(e -> {
            if (showSelectedToggle.isSelected()) {
                showSelectedOnly();
            } else {
                setShown(false);
            }
        });
        clearButton = Icons.button(MaterialDesignD.DELETE_SWEEP_OUTLINE, "Remove all images", this::clearAll);

        ToggleButton mirrorToggle = Icons.toggle(MaterialDesignS.SWAP_VERTICAL,
                "Mirror for opposite side: show two copies facing opposite sides of the table (player view only)");
        mirrorToggle.setOnAction(e -> {
            mirrored = mirrorToggle.isSelected();
            onChange.run();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6,
                Icons.button(MaterialDesignC.CONTENT_PASTE, "Add an image from the clipboard (Ctrl+V)", this::paste),
                deleteButton, clearButton,
                Icons.separator(),
                Icons.button(MaterialDesignR.ROTATE_LEFT, "Rotate the handout left by 90 degrees (player view only)", () -> rotate(-1)),
                Icons.button(MaterialDesignR.ROTATE_RIGHT, "Rotate the handout right by 90 degrees (player view only)", () -> rotate(1)),
                mirrorToggle, spacer, showSelectedToggle, showToggle);
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
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                selection.addAll(images);
                refreshState();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && !selection.isEmpty()) {
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
            selection.clear();
            showOnly = null;
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
        return shown && !getImages().isEmpty();
    }

    /** Copy of the images the players currently see, in display order. */
    public List<Image> getImages() {
        if (showOnly == null) {
            return List.copyOf(images);
        }
        List<Image> visible = new ArrayList<>();
        for (Image image : images) {
            if (showOnly.contains(image)) {
                visible.add(image);
            }
        }
        return visible;
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
        showOnly = null;
        refreshState();
        onChange.run();
    }

    private void rotate(int steps) {
        rotation = Math.floorMod(rotation + steps * 90, 360);
        lastRotation = rotation;
        drawPreview();
        onChange.run();
    }

    /** Plain click selects only the clicked image (or nothing); Ctrl/Shift+click adds or removes it. */
    private void click(int index, boolean additive) {
        if (index < 0) {
            if (!additive) {
                selection.clear();
            }
        } else {
            Image image = images.get(index);
            if (additive) {
                if (!selection.remove(image)) {
                    selection.add(image);
                }
            } else {
                selection.clear();
                selection.add(image);
            }
        }
        refreshState();
    }

    private void showContextMenu(double screenX, double screenY) {
        int count = selection.size();
        MenuItem showOnlyItem = new MenuItem(count == 1 ? "Show only this image to players"
                : "Show only the " + count + " selected images to players", Icons.icon(MaterialDesignE.EYE_CHECK_OUTLINE));
        showOnlyItem.setDisable(!playerWindowOpen.getAsBoolean());
        showOnlyItem.setOnAction(e -> showSelectedOnly());
        MenuItem delete = new MenuItem(count == 1 ? "Delete" : "Delete " + count + " images",
                Icons.icon(MaterialDesignD.DELETE_OUTLINE));
        delete.setOnAction(e -> deleteSelected());
        ContextMenu menu = new ContextMenu(showOnlyItem, delete);
        menu.show(preview, screenX, screenY);
    }

    /** Shows exactly the selected images to the players, hiding the rest of the handout. */
    private void showSelectedOnly() {
        if (selection.isEmpty() || !playerWindowOpen.getAsBoolean()) {
            return;
        }
        Set<Image> subset = Collections.newSetFromMap(new IdentityHashMap<>());
        subset.addAll(selection);
        showOnly = subset.size() == images.size() ? null : subset;
        shown = true;
        refreshState();
        onChange.run();
    }

    private void deleteSelected() {
        if (selection.isEmpty()) {
            return;
        }
        images.removeAll(selection);
        if (showOnly != null) {
            showOnly.removeAll(selection);
            if (showOnly.isEmpty()) {
                showOnly = null;
                shown = false;
            }
        }
        selection.clear();
        afterImagesChanged();
    }

    private void clearAll() {
        if (images.isEmpty()) {
            return;
        }
        images.clear();
        selection.clear();
        showOnly = null;
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
        selection.clear();
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
        deleteButton.setDisable(selection.isEmpty());
        boolean subsetShown = shown && showOnly != null;
        showSelectedToggle.setDisable(!subsetShown && (selection.isEmpty() || !playerOpen));
        showSelectedToggle.setSelected(subsetShown);
        Icons.tooltip(showSelectedToggle, subsetShown ? "Stop showing the selected images (returns to the map)"
                : selection.isEmpty() ? "Select one or more images first"
                : !playerOpen ? "Open the player window first"
                : "Show only the selected images to the players (the rest of the handout stays hidden)");
        clearButton.setDisable(!hasImages);
        emptyLabel.setVisible(!hasImages);
        if (hasImages) {
            String base = images.size() == 1 ? "Paste more images to show them together. Click an image to select it."
                    : images.size() + " images. Click to select, Ctrl+click for several; right-click for more, Delete removes.";
            hint.setText(shown && showOnly != null
                    ? "Showing " + getImages().size() + " of " + images.size() + " images to the players (green frames). " + base
                    : base);
        }
        drawPreview();
    }

    private int hitTest(double x, double y) {
        List<Rect> rects = layout(preview.getWidth(), preview.getHeight(), images, 0);
        return HandoutLayout.hitTest(rects, x, y, 0, preview.getWidth(), preview.getHeight());
    }

    private void drawPreview() {
        GraphicsContext gc = preview.getGraphicsContext2D();
        gc.clearRect(0, 0, preview.getWidth(), preview.getHeight());
        Set<Integer> selectedIndexes = new java.util.HashSet<>();
        Set<Integer> shownIndexes = new java.util.HashSet<>();
        for (int i = 0; i < images.size(); i++) {
            if (selection.contains(images.get(i))) {
                selectedIndexes.add(i);
            }
            if (shown && showOnly != null && showOnly.contains(images.get(i))) {
                shownIndexes.add(i);
            }
        }
        draw(gc, images, 0, preview.getWidth(), preview.getHeight(), selectedIndexes, shownIndexes);
    }

    private static List<Rect> layout(double width, double height, List<Image> images, int rotation) {
        Size space = HandoutLayout.layoutSize(width, height, rotation);
        List<Size> sizes = new ArrayList<>();
        for (Image image : images) {
            sizes.add(new Size(image.getWidth(), image.getHeight()));
        }
        return HandoutLayout.compute(sizes, space.width(), space.height());
    }

    /** Draws the arrangement with an optional selection border (-1 means none). */
    public static void drawBoard(GraphicsContext gc, List<Image> images, int rotation, double width, double height,
                                 int selectedIndex) {
        draw(gc, images, rotation, width, height, selectedIndex < 0 ? Set.of() : Set.of(selectedIndex), Set.of());
    }

    /** Reflows the player arrangement for each half before drawing opposite-facing copies. */
    public static void drawBoard(GraphicsContext gc, List<Image> images, int rotation, double width, double height,
                                 boolean mirrored) {
        List<Panel> panels = HandoutLayout.outputPanels(width, height, rotation, mirrored);
        if (images.isEmpty() || panels.isEmpty()) {
            return;
        }
        Panel first = panels.getFirst();
        List<Rect> rects = layout(first.bounds().width(), first.bounds().height(), images, first.rotation());
        for (Panel panel : panels) {
            gc.save();
            gc.translate(panel.bounds().x(), panel.bounds().y());
            drawArrangement(gc, images, panel.rotation(), panel.bounds().width(), panel.bounds().height(),
                    rects, Set.of(), Set.of());
            gc.restore();
        }
    }

    private static void draw(GraphicsContext gc, List<Image> images, int rotation, double width, double height,
                             Set<Integer> selectedIndexes, Set<Integer> shownIndexes) {
        if (images.isEmpty() || width <= 0 || height <= 0) {
            return;
        }
        List<Rect> rects = layout(width, height, images, rotation);
        drawArrangement(gc, images, rotation, width, height, rects, selectedIndexes, shownIndexes);
    }

    private static void drawArrangement(GraphicsContext gc, List<Image> images, int rotation, double width,
                                        double height, List<Rect> rects, Set<Integer> selectedIndexes,
                                        Set<Integer> shownIndexes) {
        Size space = HandoutLayout.layoutSize(width, height, rotation);
        gc.save();
        gc.translate(width / 2, height / 2);
        gc.rotate(rotation);
        gc.translate(-space.width() / 2, -space.height() / 2);
        for (int i = 0; i < images.size() && i < rects.size(); i++) {
            Rect r = rects.get(i);
            gc.drawImage(images.get(i), r.x(), r.y(), r.width(), r.height());
        }
        gc.setLineWidth(3);
        for (int i : shownIndexes) {
            if (i < rects.size()) {
                Rect r = rects.get(i);
                gc.setStroke(SHOWN_COLOR);
                gc.strokeRect(r.x() + 1.5, r.y() + 1.5, r.width() - 3, r.height() - 3);
            }
        }
        for (int i : selectedIndexes) {
            if (i < rects.size()) {
                Rect r = rects.get(i);
                gc.setStroke(SELECTION_COLOR);
                gc.setLineDashes(shownIndexes.contains(i) ? new double[]{8, 6} : null);
                gc.strokeRect(r.x() + 1.5, r.y() + 1.5, r.width() - 3, r.height() - 3);
            }
        }
        gc.setLineDashes((double[]) null);
        gc.restore();
    }
}
