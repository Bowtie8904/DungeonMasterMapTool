package dmmt.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Colour chooser for a music category or a sound effect: a few presets plus a free colour picker (3.35.2). */
final class AudioColorPicker {
    /** Presets that stay readable on the dark overlay background. */
    private static final List<String> PRESETS = List.of(
            "#8AB4F8", "#5FA8D3", "#4DD0E1", "#4DB6AC", "#81C995", "#AED581",
            "#FDD663", "#F7B267", "#F28B82", "#E57373", "#F06292", "#CE93D8",
            "#B39DDB", "#9FA8DA", "#BCAAA4", "#C9CED7");

    private AudioColorPicker() {
    }

    /**
     * Shows the chooser and reports the picked colour as {@code #RRGGBB}.
     *
     * @param icon the entry's icon, so the swatches preview the actual button
     */
    static void show(Window owner, String header, String current, Ikon icon, Consumer<String> onChosen) {
        Dialog<ButtonType> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, "Colour", header, MaterialDesignP.PALETTE, false);

        TilePane grid = new TilePane(6, 6);
        grid.setPrefColumns(8);
        grid.setPadding(new Insets(4));
        for (String preset : PRESETS) {
            Button button = new Button();
            button.setGraphic(AudioIcons.tinted(icon == null ? null : icon.getDescription(), preset, 20));
            button.getStyleClass().add("icon-button");
            Icons.tooltip(button, preset);
            if (preset.equalsIgnoreCase(current)) {
                button.getStyleClass().add("selected");
            }
            button.setOnAction(event -> {
                onChosen.accept(preset);
                dialog.setResult(ButtonType.CLOSE);
                dialog.close();
            });
            grid.getChildren().add(button);
        }

        ColorPicker custom = new ColorPicker(AudioIcons.color(current));
        Icons.tooltip(custom, "Pick any colour.");
        custom.setOnAction(event -> {
            onChosen.accept(toHex(custom.getValue()));
            dialog.setResult(ButtonType.CLOSE);
            dialog.close();
        });
        HBox customRow = new HBox(8, new Label("Custom"), custom);
        customRow.setPadding(new Insets(4, 0, 0, 4));

        VBox box = new VBox(8, grid, customRow);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /** {@code #RRGGBB} of a JavaFX colour. */
    static String toHex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }
}
