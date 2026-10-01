package dmmt.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.Optional;
import java.util.function.Function;

/**
 * Dark-themed replacements for the standard JavaFX dialogs.
 */
public final class Dialogs {

    public enum SaveChoice { SAVE, DISCARD, CANCEL }

    private Dialogs() {
    }

    static void style(Dialog<?> dialog, Window owner, String title, String header, Ikon ikon, boolean danger) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        FontIcon icon = Icons.icon(ikon);
        icon.getStyleClass().add("dialog-icon");
        if (danger) {
            icon.getStyleClass().add("danger");
        }
        dialog.setGraphic(icon);
        dialog.getDialogPane().getStylesheets().add(Icons.STYLESHEET);
    }

    /**
     * Asks for a single line of text. The validator returns an error message, or {@code null} if the input is fine;
     * the dialog stays open while the input is invalid.
     */
    public static Optional<String> askText(Window owner, String title, String header, Ikon ikon, String actionLabel,
                                           String initialValue, Function<String, String> validator) {
        Dialog<String> dialog = new Dialog<>();
        style(dialog, owner, title, header, ikon, false);

        TextField field = new TextField(initialValue == null ? "" : initialValue);
        field.setPrefColumnCount(26);
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        VBox content = new VBox(8, field, error);
        content.setPrefWidth(360);
        dialog.getDialogPane().setContent(content);

        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
        actionButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = validator == null ? null : validator.apply(field.getText());
            if (problem != null) {
                error.setText(problem);
                event.consume();
            }
        });
        field.textProperty().addListener((observable, oldValue, newValue) -> error.setText(""));
        dialog.setResultConverter(buttonType -> buttonType == action ? field.getText().trim() : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
    }

    /** Confirmation for non-destructive but significant actions; the action is the default button. */
    public static boolean confirm(Window owner, String title, String header, Ikon ikon, String message, String actionLabel) {
        Dialog<ButtonType> dialog = new Dialog<>();
        style(dialog, owner, title, header, ikon, false);
        Label content = new Label(message);
        content.setWrapText(true);
        content.setMaxWidth(380);
        dialog.getDialogPane().setContent(content);
        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == action;
    }

    /** Confirmation for destructive actions; Cancel is the default button. */
    public static boolean confirmDanger(Window owner, String title, String header, String message, String actionLabel) {
        Dialog<ButtonType> dialog = new Dialog<>();
        style(dialog, owner, title, header, MaterialDesignA.ALERT_CIRCLE_OUTLINE, true);
        Label content = new Label(message);
        content.setWrapText(true);
        content.setMaxWidth(380);
        dialog.getDialogPane().setContent(content);

        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancel, action);
        Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
        actionButton.setDefaultButton(false);
        actionButton.getStyleClass().add("danger");
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        Platform.runLater(cancelButton::requestFocus);
        return dialog.showAndWait().orElse(cancel) == action;
    }

    public static SaveChoice askSaveChanges(Window owner, String header, String message) {
        Dialog<ButtonType> dialog = new Dialog<>();
        style(dialog, owner, "Unsaved map", header, MaterialDesignC.CONTENT_SAVE_OUTLINE, false);
        Label content = new Label(message);
        content.setWrapText(true);
        content.setMaxWidth(380);
        dialog.getDialogPane().setContent(content);

        ButtonType save = new ButtonType("Save…", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, discard, cancel);
        ((Button) dialog.getDialogPane().lookupButton(save)).setDefaultButton(true);
        ((Button) dialog.getDialogPane().lookupButton(discard)).getStyleClass().add("danger");
        ButtonType result = dialog.showAndWait().orElse(cancel);
        if (result == save) {
            return SaveChoice.SAVE;
        }
        return result == discard ? SaveChoice.DISCARD : SaveChoice.CANCEL;
    }

    public static void error(Window owner, String header, String message) {
        Dialog<ButtonType> dialog = new Dialog<>();
        style(dialog, owner, "Something went wrong", header, MaterialDesignA.ALERT_CIRCLE_OUTLINE, true);
        Label content = new Label(message);
        content.setWrapText(true);
        content.setMaxWidth(380);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        dialog.showAndWait();
    }
}
