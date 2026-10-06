package dmmt.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Asks whether files that are already in the library (same original file name as an existing map or multilevel
 * level, see {@link dmmt.service.DuplicateCheckService}) should be imported again. Every duplicate has its own
 * checkbox (ticked by default) plus a "Select none" shortcut; "Don't import duplicates" skips every duplicate
 * regardless of the checkboxes, "Import selected" imports only the ticked ones (non-duplicate files are always
 * imported and are not shown here).
 */
public final class DuplicateMapsDialog {

    /** The subset of the offered duplicates that should still be imported. */
    public record Result(Set<Path> accepted) {
    }

    private DuplicateMapsDialog() {
    }

    /** Empty when the user cancelled the whole import. */
    public static Optional<Result> show(Window owner, List<Path> duplicates) {
        Dialog<Result> dialog = new Dialog<>();
        String header = duplicates.size() == 1
                ? "1 file is already in the library"
                : duplicates.size() + " files are already in the library";
        Dialogs.style(dialog, owner, "Duplicate maps found", header, MaterialDesignA.ALERT_CIRCLE_OUTLINE, false);

        Label intro = new Label("These files have the same original file name as a map already in the library. "
                + "Untick any you don't want to import again:");
        intro.setWrapText(true);
        intro.setMaxWidth(420);

        List<CheckBox> boxes = new ArrayList<>();
        VBox list = new VBox(4);
        for (Path duplicate : duplicates) {
            CheckBox box = new CheckBox(duplicate.getFileName().toString());
            box.setSelected(true);
            box.setUserData(duplicate);
            boxes.add(box);
            list.getChildren().add(box);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("duplicate-list-scroll");
        scroll.setPrefHeight(Math.min(260, 28 * duplicates.size() + 16));

        Button selectAll = new Button("Select all");
        selectAll.setOnAction(e -> boxes.forEach(b -> b.setSelected(true)));
        Button selectNone = new Button("Select none");
        selectNone.setOnAction(e -> boxes.forEach(b -> b.setSelected(false)));
        HBox toolbar = new HBox(8, selectAll, selectNone);

        VBox content = new VBox(10, intro, toolbar, scroll);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);

        ButtonType skip = new ButtonType("Don't import duplicates", ButtonBar.ButtonData.OTHER);
        ButtonType importSelected = new ButtonType("Import selected", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, skip, importSelected);
        ((Button) dialog.getDialogPane().lookupButton(importSelected)).setDefaultButton(true);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == importSelected) {
                Set<Path> accepted = new LinkedHashSet<>();
                for (CheckBox box : boxes) {
                    if (box.isSelected()) {
                        accepted.add((Path) box.getUserData());
                    }
                }
                return new Result(accepted);
            }
            if (buttonType == skip) {
                return new Result(Set.of());
            }
            return null;
        });
        return dialog.showAndWait();
    }
}
