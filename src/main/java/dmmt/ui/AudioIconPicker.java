package dmmt.ui;

import dmmt.audio.AudioCategory;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.function.Consumer;

/** A small grid of the icons a music category or a sound effect can use (3.35.2). */
final class AudioIconPicker {
    private AudioIconPicker() {
    }

    /** Shows the picker for a category and reports the chosen Ikonli description. */
    static void show(Window owner, AudioCategory category, Consumer<String> onChosen) {
        show(owner, "Icon of \"" + category.getName() + "\"", category.getColor(), category.getIcon(),
                AudioIcons.choices(), onChosen);
    }

    /** Shows the picker for a sound effect and reports the chosen Ikonli description. */
    static void show(Window owner, dmmt.audio.AudioTrack effect, Consumer<String> onChosen) {
        show(owner, "Icon of \"" + effect.getName() + "\"", effect.getColor(), effect.getIcon(),
                AudioIcons.effectChoices(), onChosen);
    }

    private static final int MAX_RESULTS = 300;

    private static void show(Window owner, String header, String color, String current,
                             java.util.List<Ikon> suggested, Consumer<String> onChosen) {
        Dialog<ButtonType> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, "Icon", header, MaterialDesignS.SHAPE_OUTLINE, false);
        TilePane grid = new TilePane(6, 6);
        grid.setPrefColumns(10);
        grid.setPadding(new Insets(4));

        ScrollPane scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(320);
        scroller.setPrefViewportWidth(420);

        Label hint = new Label();
        hint.getStyleClass().add("muted");

        TextField search = new TextField();
        search.setPromptText("Search icons, e.g. mountain, cave, tree");
        Icons.tooltip(search, "Type part of an icon name to search the complete icon catalogue.");

        Runnable refresh = () -> {
            java.util.List<Ikon> icons = AudioIcons.search(search.getText(), suggested, MAX_RESULTS);
            grid.getChildren().clear();
            for (Ikon icon : icons) {
                Button button = new Button();
                button.setGraphic(AudioIcons.tinted(icon.getDescription(), color, 20));
                button.getStyleClass().add("icon-button");
                Icons.tooltip(button, AudioIcons.searchName(icon));
                if (icon.getDescription().equals(current)) {
                    button.getStyleClass().add("selected");
                }
                button.setOnAction(event -> {
                    onChosen.accept(icon.getDescription());
                    dialog.setResult(ButtonType.CLOSE);
                    dialog.close();
                });
                grid.getChildren().add(button);
            }
            boolean searching = search.getText() != null && !search.getText().isBlank();
            if (!searching) {
                hint.setText("Suggested icons - search to browse all "
                        + AudioIcons.allChoices().size() + " icons.");
            } else if (icons.isEmpty()) {
                hint.setText("No icon matches \"" + search.getText().trim() + "\".");
            } else if (icons.size() >= MAX_RESULTS) {
                hint.setText("First " + MAX_RESULTS + " matches - refine your search to see more.");
            } else {
                hint.setText(icons.size() + (icons.size() == 1 ? " match" : " matches"));
            }
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run();

        VBox box = new VBox(8, search, scroller, hint);
        box.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        javafx.application.Platform.runLater(search::requestFocus);
        dialog.showAndWait();
    }
}
