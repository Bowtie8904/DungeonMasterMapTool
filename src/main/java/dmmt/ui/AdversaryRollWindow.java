package dmmt.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdversaryRollWindow {
    private static final Pattern BONUS = Pattern.compile("([+-]\\d+)\\s*$");

    private AdversaryRollWindow() {
    }

    public static Optional<String> show(Window owner, String title, String expression, List<String> experiences) {
        Dialog<String> dialog = new Dialog<>();
        Dialogs.style(dialog, owner, title, "Choose advantage and an applicable Experience", MaterialDesignD.DICE_D20, false);
        ToggleGroup mode = new ToggleGroup();
        ToggleButton advantage = modeButton("Advantage (2d20 high)", "adv", mode);
        ToggleButton normal = modeButton("Normal", "norm", mode);
        ToggleButton disadvantage = modeButton("Disadvantage (2d20 low)", "dis", mode);
        normal.setSelected(true);
        mode.selectedToggleProperty().addListener((o, previous, next) -> {
            if (next == null && previous != null) previous.setSelected(true);
        });
        HBox modes = new HBox(6, advantage, normal, disadvantage);
        for (ToggleButton button : List.of(advantage, normal, disadvantage)) {
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setMaxWidth(Double.MAX_VALUE);
        }
        ComboBox<String> experience = new ComboBox<>();
        experience.getItems().add("None");
        if (experiences != null) experience.getItems().addAll(experiences);
        experience.getSelectionModel().selectFirst();
        experience.setMaxWidth(Double.MAX_VALUE);
        Label note = new Label("When adding an Experience, spend Fear as usual.");
        note.setWrapText(true);
        note.getStyleClass().add("muted-text");
        Label rollLabel = new Label("Roll: " + expression);
        rollLabel.getStyleClass().add("muted-text");
        VBox content = new VBox(8, rollLabel, new Label("Roll with"), modes, new Label("Experience"), experience, note);
        content.setPadding(new Insets(8, 0, 0, 0));
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        ButtonType roll = new ButtonType("Roll to chat", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, roll);
        Button rollButton = (Button) dialog.getDialogPane().lookupButton(roll);
        rollButton.setDefaultButton(true);
        dialog.setResultConverter(button -> {
            if (button != roll) return null;
            String selected = experience.getValue();
            String result = selected == null || "None".equals(selected)
                    ? expression : addExperience(expression, selected);
            if (advantage.isSelected()) return result.replaceFirst("^1d20", "2d20kh1");
            if (disadvantage.isSelected()) return result.replaceFirst("^1d20", "2d20kl1");
            return result;
        });
        return dialog.showAndWait();
    }

    private static ToggleButton modeButton(String text, String style, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.getStyleClass().addAll("adversary-adv-btn", style);
        return button;
    }

    static String addExperience(String expression, String experience) {
        Matcher bonus = BONUS.matcher(experience);
        if (!bonus.find()) return expression;
        int amount = Integer.parseInt(bonus.group(1));
        String base = expression == null ? "" : expression.replaceAll("\\s+", "");
        return base + (amount >= 0 ? "+" : "") + amount;
    }
}
