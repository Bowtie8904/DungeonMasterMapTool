package dmmt.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Labeled;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Renders the 144x144 key artwork that control devices use for their keys; served over the API (3.36.1). */
public final class ControlKeyImages {
    public static final int SIZE = 144;

    private ControlKeyImages() {
    }

    /**
     * Renders the key image and returns the PNG bytes (3.36.1). Must run on the JavaFX application thread
     * because it snapshots live controls.
     */
    public static byte[] png(String id, List<Node> sources, String operation) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                || operation != null && !List.of("increment", "decrement").contains(operation)) {
            throw new IllegalArgumentException("Invalid key image identifier or operation.");
        }
        Ikon icon = selectIcon(id, sources);
        Color color = selectColor(sources);
        Node glyph = icon instanceof AudioCustomIkon
                ? AudioIcons.tinted(icon.getDescription(), AudioColorPicker.toHex(color), 82)
                : new FontIcon(icon);
        if (glyph instanceof FontIcon fontIcon) {
            fontIcon.setIconSize(82);
            fontIcon.setIconColor(color);
        }
        StackPane pane = new StackPane(glyph);
        pane.setStyle("-fx-background-color: transparent;");
        new Scene(pane, Color.TRANSPARENT);
        pane.applyCss();
        pane.layout();
        SnapshotParameters transparent = new SnapshotParameters();
        transparent.setFill(Color.TRANSPARENT);
        WritableImage glyphImage = pane.snapshot(transparent, null);

        Canvas canvas = new Canvas(SIZE, SIZE);
        var graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web("#18212D"));
        graphics.fillRect(0, 0, SIZE, SIZE);
        double scale = Math.min(82 / glyphImage.getWidth(), 82 / glyphImage.getHeight());
        double width = glyphImage.getWidth() * scale;
        double height = glyphImage.getHeight() * scale;
        graphics.drawImage(glyphImage, (SIZE - width) / 2, (SIZE - height) / 2, width, height);
        if (operation != null) {
            graphics.setFill(Color.web(operation.equals("increment") ? "#218653" : "#BA4747"));
            graphics.fillOval(94, 94, 44, 44);
            graphics.setFill(Color.WHITE);
            graphics.setFont(Font.font("Arial", 34));
            graphics.fillText(operation.equals("increment") ? "+" : "-", 105, 128);
        }
        WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
        BufferedImage png = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[SIZE * SIZE];
        image.getPixelReader().getPixels(0, 0, SIZE, SIZE,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, SIZE);
        png.setRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(png, "png", bytes)) {
            throw new IOException("No PNG encoder is available.");
        }
        return bytes.toByteArray();
    }

    static Ikon selectIcon(String id, List<Node> sources) {
        if (id.equals("player.screen")) {
            return MaterialDesignM.MONITOR;
        }
        // Dropdown skins contain arrow glyphs, not artwork for the action.
        if (sources.stream().anyMatch(node -> node instanceof javafx.scene.control.ComboBox<?>)) {
            return sectionIcon(id);
        }
        Ikon specific = switch (id) {
            case "effects.color", "text.color", "text.background", "text.border" -> MaterialDesignP.PALETTE_OUTLINE;
            case "effects.opacity", "player.gridOpacity" -> MaterialDesignC.CIRCLE_OPACITY;
            case "weather.lightningInterval" -> MaterialDesignW.WEATHER_LIGHTNING;
            case "fog.brushSize", "effects.brushSize" -> MaterialDesignF.FORMAT_PAINT;
            case "text.size" -> MaterialDesignF.FORMAT_SIZE;
            case "player.diagonal", "player.tileSize" -> MaterialDesignR.RULER_SQUARE;
            case "player.zoom" -> MaterialDesignM.MAGNIFY;
            case "performance.target", "performance.animation", "performance.idle" -> MaterialDesignS.SPEEDOMETER;
            // The volume sliders are registered on their own, so the icon beside them in the overlay is not
            // among this entry's nodes; mirror it here to avoid the generic fallback glyph.
            case "audio.masterVolume" -> MaterialDesignV.VOLUME_HIGH;
            case "audio.musicVolume" -> MaterialDesignM.MUSIC_NOTE;
            case "audio.effectsVolume" -> MaterialDesignW.WAVES;
            default -> null;
        };
        if (specific != null) {
            return specific;
        }
        for (Node source : sources) {
            Ikon icon = findIcon(source);
            if (icon != null) {
                return icon;
            }
        }
        return sectionIcon(id);
    }

    private static Ikon sectionIcon(String id) {
        return switch (id.split("\\.", 2)[0]) {
            case "tools" -> MaterialDesignC.CURSOR_DEFAULT;
            case "fog" -> MaterialDesignW.WEATHER_FOG;
            case "lighting" -> MaterialDesignL.LIGHTBULB_OUTLINE;
            case "weather" -> MaterialDesignW.WEATHER_PARTLY_RAINY;
            case "effects" -> MaterialDesignF.FORMAT_PAINT;
            case "text" -> MaterialDesignT.TEXT_BOX_OUTLINE;
            case "building" -> MaterialDesignW.WALL;
            case "player" -> MaterialDesignP.PROJECTOR;
            case "performance" -> MaterialDesignS.SPEEDOMETER;
            case "levels" -> MaterialDesignL.LAYERS_TRIPLE_OUTLINE;
            default -> MaterialDesignT.TUNE_VARIANT;
        };
    }

    /**
     * Short tag identifying the artwork {@link #png} would produce for this control, derived from exactly the two
     * things that determine it: the glyph and its colour. Clients cache key images, so they need a cheap way to
     * notice that a music category was given a new icon or colour (3.36.1). Must run on the JavaFX thread.
     */
    public static String fingerprint(String id, List<Node> sources) {
        Ikon icon = selectIcon(id, sources);
        return Integer.toHexString(Objects.hash(icon.getDescription(), selectColor(sources)));
    }

    private static Ikon findIcon(Node node) {
        AudioCustomIconView customIcon = findCustomIcon(node);
        if (customIcon != null) {
            return AudioIcons.byDescription(customIcon.iconDescription());
        }
        FontIcon icon = findFontIcon(node, false);
        return icon == null ? null : icon.getIconCode();
    }

    /**
     * Colour of the exported glyph. Music categories and sound effects carry their own colour (3.35.6); every
     * other control keeps the plain white glyph of the standard key image.
     */
    static Color selectColor(List<Node> sources) {
        for (Node source : sources) {
            AudioCustomIconView customIcon = findCustomIcon(source);
            if (customIcon != null) {
                return readable(customIcon.iconColor());
            }
            FontIcon icon = findFontIcon(source, true);
            if (icon != null && icon.getIconColor() instanceof Color color) {
                return readable(color);
            }
        }
        return Color.WHITE;
    }

    private static AudioCustomIconView findCustomIcon(Node node) {
        if (node instanceof AudioCustomIconView icon) {
            return icon;
        }
        if (node instanceof Labeled labeled && labeled.getGraphic() != null) {
            AudioCustomIconView icon = findCustomIcon(labeled.getGraphic());
            if (icon != null) {
                return icon;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                AudioCustomIconView icon = findCustomIcon(child);
                if (icon != null) {
                    return icon;
                }
            }
        }
        return null;
    }

    /** Lightens very dark colours until the glyph is legible on the dark key background. */
    private static Color readable(Color color) {
        Color result = color;
        for (int i = 0; i < 8 && result.getBrightness() < 0.45; i++) {
            result = result.brighter();
        }
        return result;
    }

    /** The first icon of the node tree, optionally only one that carries a user-chosen audio colour. */
    private static FontIcon findFontIcon(Node node, boolean tintedOnly) {
        if (node instanceof FontIcon icon) {
            return !tintedOnly || icon.getStyleClass().contains(AudioIcons.TINTED_CLASS) ? icon : null;
        }
        if (node instanceof Labeled labeled && labeled.getGraphic() != null) {
            return findFontIcon(labeled.getGraphic(), tintedOnly);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                FontIcon icon = findFontIcon(child, tintedOnly);
                if (icon != null) {
                    return icon;
                }
            }
        }
        return null;
    }
}
