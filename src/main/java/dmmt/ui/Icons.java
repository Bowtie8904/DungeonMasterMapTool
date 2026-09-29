package dmmt.ui;

import javafx.geometry.Insets;
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
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
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
        control.setTooltip(tooltip(text));
    }

    public static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(300));
        tooltip.setShowDuration(Duration.seconds(12));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(300);
        return tooltip;
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
        return CURSOR_CACHE.computeIfAbsent(key, ignored -> createCursor(ikon, hotspotFractionX, hotspotFractionY));
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

    private static Cursor createCursor(Ikon ikon, double hotspotFractionX, double hotspotFractionY) {
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
            return new ImageCursor(image, image.getWidth() * hotspotFractionX, image.getHeight() * hotspotFractionY);
        } catch (RuntimeException exception) {
            return Cursor.CROSSHAIR;
        }
    }
}
