package dmmt.ui;

import dmmt.service.MapTagService;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Stages tag edits until Apply; multiple-map selections only support additions. */
public final class MapTagsDialog {
    public record Result(List<String> additions, List<String> removals) {
    }

    private MapTagsDialog() {
    }

    public static Optional<Result> show(Window owner, String mapName, int mapCount, boolean multiLevel,
                                        List<String> existing, List<String> known) {
        return create(owner, mapName, mapCount, multiLevel, existing, known).showAndWait();
    }

    static Dialog<Result> create(Window owner, String mapName, int mapCount, boolean multiLevel,
                                  List<String> existing, List<String> known) {
        boolean bulk = mapCount > 1;
        List<String> original = bulk ? List.of() : MapTagService.normalize(existing);
        List<String> tags = new ArrayList<>(original);
        List<String> suggestions = MapTagService.normalize(known);
        Dialog<Result> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, bulk ? "Add tags" : "Manage tags",
                bulk ? "Add tags to " + mapCount + " maps" : "Tags for \"" + mapName + "\"",
                MaterialDesignT.TAG_MULTIPLE_OUTLINE, false);

        Label intro = new Label(bulk ? "Tags added here will be applied to every selected map. Existing tags are kept."
                : multiLevel ? "All levels' tags are shown together. Adding or removing a tag affects every level."
                : "Add tags to find this map using the library search.");
        intro.setWrapText(true);
        Label caption = new Label(bulk ? "TAGS TO ADD" : "MAP TAGS");
        caption.getStyleClass().add("caption");
        FlowPane chips = new FlowPane(6, 6);
        chips.getStyleClass().add("map-tag-chips");
        ScrollPane tagScroll = new ScrollPane(chips);
        tagScroll.setFitToWidth(true);
        tagScroll.setPrefHeight(125);
        Label empty = new Label(bulk ? "No tags queued yet" : "No tags on this map");
        empty.getStyleClass().add("muted-label");

        TextField field = new TextField();
        field.setId("map-tag-input");
        field.setPromptText("Enter a tag");
        HBox.setHgrow(field, Priority.ALWAYS);
        Button add = new Button("Add");
        add.setId("map-tag-add");
        HBox input = new HBox(8, field, add);
        input.setAlignment(Pos.CENTER_LEFT);
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        TagSuggestions suggested = new TagSuggestions(field);

        VBox content = new VBox(10, intro, caption, empty, tagScroll, input, error);
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, apply);
        Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);

        class TagsView {
            void refresh() {
                chips.getChildren().clear();
                for (String tag : tags) {
                    Label name = new Label(tag);
                    Button remove = Icons.button(MaterialDesignC.CLOSE,
                            bulk ? "Do not add \"" + tag + "\"" : "Remove \"" + tag + "\"", null);
                    remove.setAccessibleText("Remove " + tag);
                    HBox chip = new HBox(4, name, remove);
                    chip.setAlignment(Pos.CENTER_LEFT);
                    chip.getStyleClass().add("map-tag-chip");
                    remove.setOnAction(event -> {
                        tags.remove(tag);
                        refresh();
                    });
                    chips.getChildren().add(chip);
                }
                empty.setVisible(tags.isEmpty());
                empty.setManaged(tags.isEmpty());
                String typed = field.getText().trim().toLowerCase(Locale.ROOT);
                add.setDisable(typed.isEmpty());
                suggested.refresh(typed.isEmpty() ? List.of() : suggestions.stream()
                        .filter(tag -> tag.toLowerCase(Locale.ROOT).contains(typed) && !contains(tags, tag))
                        .toList());
                applyButton.setDisable(tags.equals(original) && typed.isEmpty());
            }
        }
        TagsView view = new TagsView();
        Runnable addTag = () -> {
            String tag = field.getText().trim().toUpperCase(Locale.ROOT);
            if (tag.isEmpty()) {
                error.setText("Enter a tag name.");
            } else if (contains(tags, tag)) {
                error.setText("This tag is already in the list.");
            } else {
                tags.add(tag);
                field.clear();
                error.setText("");
                view.refresh();
                field.requestFocus();
            }
        };
        add.setOnAction(event -> addTag.run());
        field.setOnAction(event -> {
            if (!field.getText().isBlank()) {
                addTag.run();
            }
            event.consume();
        });
        field.textProperty().addListener((obs, before, after) -> {
            String uppercase = after.toUpperCase(Locale.ROOT);
            if (!after.equals(uppercase)) {
                field.setText(uppercase);
                return;
            }
            error.setText("");
            view.refresh();
        });
        applyButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!field.getText().isBlank()) {
                addTag.run();
                if (!field.getText().isBlank()) {
                    event.consume();
                }
            }
        });
        dialog.setResultConverter(button -> button == apply
                ? new Result(tags.stream().filter(tag -> !contains(original, tag)).toList(),
                original.stream().filter(tag -> !contains(tags, tag)).toList()) : null);
        view.refresh();
        dialog.setOnShown(event -> Platform.runLater(field::requestFocus));
        dialog.setOnHidden(event -> suggested.hide());
        return dialog;
    }

    private static boolean contains(List<String> tags, String tag) {
        return tags.stream().anyMatch(existing -> existing.equalsIgnoreCase(tag));
    }
}
