package dmmt.ui;

import dmmt.online.Campaign;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Dark-themed multi-field dialogs of the Online Play tab (campaign creation, players, passwords). */
public final class OnlineDialogs {
    public static final int MIN_PASSWORD_LENGTH = 4;

    public record NewCampaign(String name, String system) {
    }

    public record NewPlayer(String name, String password) {
    }

    public record NewCountdown(String name, int initialValue) {
    }

    private OnlineDialogs() {
    }

    public static Optional<NewCountdown> createCountdown(Window owner) {
        TextField name = new TextField();
        name.setPromptText("e.g. Reinforcements arrive");
        TextField initial = new TextField("6");
        Label hint = new Label("Starts at the initial value. Step between 0 and that value; players can only view it.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        return form(owner, "New countdown", "Create a countdown", MaterialDesignS.SWORD_CROSS, "Create",
                new String[]{"Name", "Initial value"}, new javafx.scene.Node[]{name, initial}, hint, name,
                () -> {
                    try {
                        new Campaign.Countdown("validation", name.getText(), Integer.parseInt(initial.getText().trim()),
                                Integer.parseInt(initial.getText().trim()));
                        return null;
                    } catch (NumberFormatException ex) {
                        return "Initial value must be a whole number between 0 and " + Integer.MAX_VALUE + ".";
                    } catch (IllegalArgumentException ex) {
                        return ex.getMessage();
                    }
                }, () -> new NewCountdown(name.getText().trim(), Integer.parseInt(initial.getText().trim())));
    }

    /** Name plus game system. {@code nameProblem} returns an error text or {@code null}. */
    public static Optional<NewCampaign> createCampaign(Window owner, Function<String, String> nameProblem) {
        TextField name = new TextField();
        name.setPromptText("e.g. The Witherwild");
        ComboBox<String> system = new ComboBox<>();
        system.getItems().setAll(Campaign.SYSTEMS);
        system.setValue(Campaign.DEFAULT_SYSTEM);
        system.setMaxWidth(Double.MAX_VALUE);
        Label hint = new Label("The game system will shape character sheets in a later version.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        return form(owner, "New campaign", "Create a campaign", MaterialDesignS.SWORD_CROSS, "Create",
                new String[]{"Name", "Game system"}, new javafx.scene.Node[]{name, system}, hint, name,
                () -> nameProblem == null ? null : nameProblem.apply(name.getText().trim()),
                () -> new NewCampaign(name.getText().trim(), system.getValue()));
    }

    /** Username plus password (entered twice). */
    public static Optional<NewPlayer> addPlayer(Window owner, Function<String, String> nameProblem) {
        TextField name = new TextField();
        name.setPromptText("Login name");
        PasswordField password = new PasswordField();
        PasswordField repeat = new PasswordField();
        Label hint = new Label("Players sign in with this username and password. Share them privately.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        return form(owner, "Add player", "Add a player", MaterialDesignA.ACCOUNT_PLUS_OUTLINE, "Add player",
                new String[]{"Username", "Password", "Repeat"}, new javafx.scene.Node[]{name, password, repeat}, hint,
                name, () -> {
                    String problem = nameProblem == null ? null : nameProblem.apply(name.getText().trim());
                    return problem != null ? problem : passwordProblem(password.getText(), repeat.getText());
                }, () -> new NewPlayer(name.getText().trim(), password.getText()));
    }

    /** New password (entered twice) for an existing player. */
    public static Optional<String> setPassword(Window owner, String playerName) {
        PasswordField password = new PasswordField();
        PasswordField repeat = new PasswordField();
        Label hint = new Label("Connected browsers of this player are signed out and must log in again.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        return form(owner, "Set password", "New password for " + playerName, MaterialDesignL.LOCK_OUTLINE,
                "Set password", new String[]{"Password", "Repeat"}, new javafx.scene.Node[]{password, repeat}, hint,
                password, () -> passwordProblem(password.getText(), repeat.getText()), password::getText);
    }

    static String passwordProblem(String password, String repeat) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "The password needs at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        if (!password.equals(repeat)) {
            return "The passwords do not match.";
        }
        return null;
    }

    private static <T> Optional<T> form(Window owner, String title, String header, Ikon ikon, String actionLabel,
                                        String[] labels, javafx.scene.Node[] fields, Label hint,
                                        javafx.scene.Node focus, Supplier<String> validator, Supplier<T> result) {
        Dialog<T> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, title, header, ikon, false);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-form");
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labelColumn = new ColumnConstraints();
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        for (int i = 0; i < labels.length; i++) {
            grid.add(new Label(labels[i]), 0, i);
            grid.add(fields[i], 1, i);
            if (fields[i] instanceof TextField text) {
                text.setPrefColumnCount(22);
                text.textProperty().addListener((o, a, b) -> error.setText(""));
            }
        }
        VBox content = new VBox(10, grid);
        if (hint != null) {
            content.getChildren().add(hint);
        }
        content.getChildren().add(error);
        content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, action);
        Button actionButton = (Button) dialog.getDialogPane().lookupButton(action);
        actionButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = validator.get();
            if (problem != null) {
                error.setText(problem);
                event.consume();
            }
        });
        dialog.setResultConverter(type -> type == action ? result.get() : null);
        Platform.runLater(focus::requestFocus);
        return dialog.showAndWait();
    }
}
