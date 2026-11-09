package dmmt.ui;

import dmmt.online.OnlineTokenConditions;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.Optional;

public final class OnlineConditionDialog {
    public record Selection(String name, String iconId) { }

    private OnlineConditionDialog() {
    }

    public static Optional<Selection> show(Window owner) {
        Dialog<Selection> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, "Custom condition", "Choose a name and icon",
                MaterialDesignP.PLUS, false);

        TextField name = new TextField();
        name.setPromptText("Condition name");
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);

        ToggleGroup icons = new ToggleGroup();
        TilePane choices = new TilePane(6, 6);
        choices.setPrefColumns(7);
        choices.setPadding(new Insets(2));
        for (String iconId : OnlineTokenConditions.customIconIds()) {
            ToggleButton choice = new ToggleButton();
            choice.getStyleClass().add("condition-icon-choice");
            Image image = new Image(OnlineConditionDialog.class
                    .getResource("/condition-icons/" + iconId + "_round.png").toExternalForm(), 28, 28, true, true);
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(28);
            imageView.setFitHeight(28);
            imageView.setPreserveRatio(true);
            choice.setGraphic(imageView);
            choice.setAccessibleText(iconId);
            choice.setToggleGroup(icons);
            choice.setPrefSize(36, 36);
            if (OnlineTokenConditions.DEFAULT_CUSTOM_ICON_ID.equals(iconId)) {
                choice.setSelected(true);
            }
            choices.getChildren().add(choice);
        }

        VBox content = new VBox(8, name, choices, error);
        content.setPrefWidth(330);
        dialog.getDialogPane().setContent(content);

        ButtonType add = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, add);
        Button addButton = (Button) dialog.getDialogPane().lookupButton(add);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = OnlineTokenConditions.validationProblem("custom", name.getText());
            if (problem != null) {
                error.setText(problem);
                event.consume();
            }
        });
        name.textProperty().addListener((observable, oldValue, newValue) -> error.setText(""));
        dialog.setResultConverter(buttonType -> {
            if (buttonType != add) return null;
            String selectedIconId = icons.getSelectedToggle() instanceof ToggleButton selected
                    ? (String) selected.getAccessibleText() : OnlineTokenConditions.DEFAULT_CUSTOM_ICON_ID;
            return new Selection(name.getText().trim(), selectedIconId);
        });
        Platform.runLater(name::requestFocus);
        return dialog.showAndWait();
    }
}
