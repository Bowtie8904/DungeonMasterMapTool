package dmmt.ui;

import dmmt.service.Tuning;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory helpers for the dark, icon-based UI: icons, icon buttons, tooltips and icon cursors.
 */
public final class Icons {

    public static final String STYLESHEET = Icons.class.getResource("/dmmt/ui/dark.css").toExternalForm();

    private static final Map<String, Cursor> CURSOR_CACHE = new HashMap<>();
    private static final Map<String, Image> IMAGE_CACHE = new HashMap<>();
    private static final double TOOLTIP_CURSOR_GAP = 4;

    private Icons() {
    }

    public static FontIcon icon(Ikon ikon) {
        return new FontIcon(ikon);
    }

    public static FontIcon icon(Ikon ikon, int size, String... styleClasses) {
        FontIcon icon = new FontIcon(ikon);
        icon.setIconSize(size);
        icon.getStyleClass().addAll(styleClasses);
        return icon;
    }

    public static Button button(Ikon ikon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon(ikon));
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        tooltip(button, tooltip);
        if (action != null) {
            button.setOnAction(event -> action.run());
        }
        return button;
    }

    public static ToggleButton toggle(Ikon ikon, String tooltip) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(icon(ikon));
        button.getStyleClass().add("icon-toggle");
        button.setFocusTraversable(false);
        tooltip(button, tooltip);
        return button;
    }

    public static void tooltip(Control control, String text) {
        Tooltip tooltip = tooltip(text);
        PauseTransition delay = new PauseTransition(Duration.millis(Tuning.TOOLTIP_DELAY_MS.get()));
        PauseTransition duration = new PauseTransition(Duration.seconds(Tuning.TOOLTIP_DURATION_SECONDS.get()));
        duration.setOnFinished(event -> tooltip.hide());
        delay.setOnFinished(event -> {
            if (!control.isHover() || control.getScene() == null) {
                return;
            }
            Bounds bounds = control.localToScreen(control.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            tooltip.show(control, bounds.getMinX(), bounds.getMaxY() + TOOLTIP_CURSOR_GAP);
            Platform.runLater(() -> placeBesideControl(tooltip, bounds));
            duration.playFromStart();
        });
        Runnable hide = () -> {
            delay.stop();
            duration.stop();
            tooltip.hide();
        };
        control.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> delay.playFromStart());
        control.addEventHandler(MouseEvent.MOUSE_EXITED, event -> hide.run());
        control.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> hide.run());
    }

    public static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(Tuning.TOOLTIP_DELAY_MS.get()));
        tooltip.setShowDuration(Duration.seconds(Tuning.TOOLTIP_DURATION_SECONDS.get()));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(300);
        return tooltip;
    }

    /**
     * The tooltip is positioned relative to the control's bounds (never relative to the cursor) so it can not end up
     * under the cursor, which would trigger MOUSE_EXITED and cause a show/hide flicker. It is placed below the control,
     * or above when there is no room, and clamped horizontally to the screen.
     */
    private static void placeBesideControl(Tooltip tooltip, Bounds bounds) {
        if (!tooltip.isShowing()) {
            return;
        }
        Rectangle2D visual = Screen.getScreensForRectangle(bounds.getMinX(), bounds.getMinY(), 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        double width = tooltip.getWidth();
        double height = tooltip.getHeight();
        double x = Math.max(visual.getMinX(), Math.min(bounds.getMinX(), visual.getMaxX() - width));
        double y = bounds.getMaxY() + TOOLTIP_CURSOR_GAP;
        if (y + height > visual.getMaxY()) {
            y = bounds.getMinY() - TOOLTIP_CURSOR_GAP - height;
        }
        tooltip.setAnchorX(x);
        tooltip.setAnchorY(y);
    }

    public static Region separator() {
        Region separator = new Region();
        separator.getStyleClass().add("vsep");
        return separator;
    }

    /**
     * Builds (and caches) a mouse cursor from an icon. The hotspot is given as a fraction of the cursor image,
     * e.g. (0.5, 0.5) for the center or (0.1, 0.9) for a pen tip in the lower left corner.
     */
    public static Cursor cursor(Ikon ikon, double hotspotFractionX, double hotspotFractionY) {
        String key = ikon.getDescription() + "@" + hotspotFractionX + "," + hotspotFractionY;
        return CURSOR_CACHE.computeIfAbsent(key, ignored -> createCursor(ikon, hotspotFractionX, hotspotFractionY, false));
    }

    /**
     * Like {@link #cursor}, but for pen-like tools: the hotspot is moved onto the glyph pixel closest to the given
     * fraction, so it lands exactly on the drawn tip (e.g. the pencil point) instead of the padding/shadow around it.
     */
    public static Cursor tipCursor(Ikon ikon, double tipFractionX, double tipFractionY) {
        String key = ikon.getDescription() + "@tip" + tipFractionX + "," + tipFractionY;
        return CURSOR_CACHE.computeIfAbsent(key, ignored -> createCursor(ikon, tipFractionX, tipFractionY, true));
    }

    /**
     * Renders (and caches) an icon glyph to an image so it can be drawn onto a canvas. Returns {@code null}
     * if the glyph cannot be rendered. Must be called on the JavaFX application thread.
     */
    public static Image image(Ikon ikon, int size, Color color) {
        String key = ikon.getDescription() + "@" + size + "," + color;
        if (IMAGE_CACHE.containsKey(key)) {
            return IMAGE_CACHE.get(key);
        }
        Image image;
        try {
            FontIcon glyph = new FontIcon(ikon);
            glyph.setIconSize(size);
            glyph.setIconColor(color);
            StackPane pane = new StackPane(glyph);
            pane.setStyle("-fx-background-color: transparent;");
            new Scene(pane, Color.TRANSPARENT);
            pane.applyCss();
            pane.layout();
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            image = pane.snapshot(parameters, null);
        } catch (RuntimeException exception) {
            image = null;
        }
        IMAGE_CACHE.put(key, image);
        return image;
    }

    private static Cursor createCursor(Ikon ikon, double hotspotFractionX, double hotspotFractionY, boolean snapToGlyph) {
        try {
            FontIcon glyph = new FontIcon(ikon);
            glyph.setIconSize(22);
            glyph.setIconColor(Color.WHITE);
            glyph.setEffect(new DropShadow(BlurType.GAUSSIAN, Color.rgb(0, 0, 0, 0.95), 4, 0.75, 0, 0));
            StackPane pane = new StackPane(glyph);
            pane.setPadding(new Insets(3));
            pane.setStyle("-fx-background-color: transparent;");
            new Scene(pane, Color.TRANSPARENT);
            pane.applyCss();
            pane.layout();

            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            WritableImage image = pane.snapshot(parameters, null);
            double hotspotX = image.getWidth() * hotspotFractionX;
            double hotspotY = image.getHeight() * hotspotFractionY;
            if (snapToGlyph) {
                int[] tip = nearestGlyphPixel(image, hotspotX, hotspotY);
                if (tip != null) {
                    hotspotX = tip[0];
                    hotspotY = tip[1];
                }
            }
            return new ImageCursor(padToCursorSize(image), hotspotX, hotspotY);
        } catch (RuntimeException exception) {
            return Cursor.CROSSHAIR;
        }
    }

    /** Nearest pixel of the (white) glyph itself, ignoring the dark drop shadow and transparent padding. */
    private static int[] nearestGlyphPixel(Image image, double targetX, double targetY) {
        PixelReader reader = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                int alpha = argb >>> 24;
                int red = (argb >> 16) & 0xFF;
                if (alpha < 128 || red < 128) {
                    continue;
                }
                double dx = x + 0.5 - targetX;
                double dy = y + 0.5 - targetY;
                double d = dx * dx + dy * dy;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /**
     * Pads the image (anchored top-left, so hotspot coordinates stay valid) to the platform's cursor size.
     * Otherwise the OS rescales the image without moving the hotspot, which shifts it off the drawn tip.
     */
    private static Image padToCursorSize(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        Dimension2D best = ImageCursor.getBestSize(w, h);
        int bw = (int) best.getWidth();
        int bh = (int) best.getHeight();
        if (bw < w || bh < h || (bw == w && bh == h)) {
            return image;
        }
        WritableImage padded = new WritableImage(bw, bh);
        padded.getPixelWriter().setPixels(0, 0, w, h, image.getPixelReader(), 0, 0);
        return padded;
    }
}
