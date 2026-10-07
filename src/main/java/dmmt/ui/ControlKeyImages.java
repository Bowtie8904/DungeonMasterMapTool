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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports a PNG and a browser page, avoiding the OS image-file association (often a photo viewer). */
public final class ControlKeyImages {
    public static final int SIZE = 144;
    private final Path directory;

    public ControlKeyImages(Path directory) {
        this.directory = directory;
    }

    public Path create(String id, List<Node> sources, String operation) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                || operation != null && !List.of("increment", "decrement").contains(operation)) {
            throw new IllegalArgumentException("Invalid key image identifier or operation.");
        }
        Ikon icon = selectIcon(id, sources);
        FontIcon glyph = new FontIcon(icon);
        glyph.setIconSize(82);
        glyph.setIconColor(Color.WHITE);
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
        Files.createDirectories(directory);
        String name = id + (operation == null ? "" : "-" + operation);
        Path file = directory.resolve(name + ".png");
        if (!ImageIO.write(png, "png", file.toFile())) {
            throw new IOException("No PNG encoder is available.");
        }
        Path page = directory.resolve(name + ".html");
        Files.writeString(page, "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
                + name + " - Control key image</title></head><body style=\"margin:0;min-height:100vh;"
                + "display:grid;place-items:center;background:#10151d\"><img width=\"144\" height=\"144\" src=\""
                + name + ".png\" alt=\"" + name + "\"></body></html>", StandardCharsets.UTF_8);
        return page;
    }

    static Ikon selectIcon(String id, List<Node> sources) {
        // Dropdown skins contain arrow glyphs, not artwork for the action.
        if (sources.stream().anyMatch(node -> node instanceof javafx.scene.control.ComboBox<?>)) {
            return sectionIcon(id);
        }
        Ikon specific = switch (id) {
            case "effects.color", "text.color", "text.background", "text.border" -> MaterialDesignP.PALETTE_OUTLINE;
            case "effects.opacity", "player.gridOpacity" -> MaterialDesignC.CIRCLE_OPACITY;
            case "fog.brushSize", "effects.brushSize" -> MaterialDesignF.FORMAT_PAINT;
            case "text.size" -> MaterialDesignF.FORMAT_SIZE;
            case "player.diagonal", "player.tileSize" -> MaterialDesignR.RULER_SQUARE;
            case "player.zoom" -> MaterialDesignM.MAGNIFY;
            case "performance.target", "performance.animation", "performance.idle" -> MaterialDesignS.SPEEDOMETER;
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

    private static Ikon findIcon(Node node) {
        if (node instanceof FontIcon icon) {
            return icon.getIconCode();
        }
        if (node instanceof Labeled labeled && labeled.getGraphic() != null) {
            return findIcon(labeled.getGraphic());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Ikon icon = findIcon(child);
                if (icon != null) {
                    return icon;
                }
            }
        }
        return null;
    }
}
