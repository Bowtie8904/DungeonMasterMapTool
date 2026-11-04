package dmmt.ui;

import dmmt.online.Campaign;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Hosting-only countdown controls beside the right DM rail/flyout. */
public final class CountdownOverlay extends VBox {
    private final Button add;
    private final VBox rows = new VBox(4);
    private final BiConsumer<String, Integer> step;
    private final Consumer<String> delete;
    private final Map<String, Node> controls = new LinkedHashMap<>();
    private List<Campaign.Countdown> shown = List.of();

    public CountdownOverlay(Runnable create, BiConsumer<String, Integer> step, Consumer<String> delete) {
        super(4);
        this.step = step;
        this.delete = delete;
        Label title = new Label("COUNTDOWNS");
        title.getStyleClass().add("countdown-title");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        add = Icons.button(MaterialDesignP.PLUS, "Create countdown", create);
        add.getStyleClass().add("countdown-action");
        HBox header = new HBox(6, title, add);
        header.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(header, rows);
        getStyleClass().add("countdowns-overlay");
        setId("countdownOverlay");
        setPrefWidth(270);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setPickOnBounds(false);
        setVisible(false);
        setDisable(true);
        managedProperty().bind(visibleProperty());
        controls.put("online.countdowns.add", add);
    }

    public Button addButton() {
        return add;
    }

    public Map<String, Node> apiControls() {
        return Map.copyOf(controls);
    }

    public void setHosted(boolean hosted, boolean hidden) {
        setDisable(!hosted);
        setVisible(hosted && !hidden);
    }

    /** Returns whether the API's dynamic row controls need replacing. */
    public boolean setCountdowns(List<Campaign.Countdown> countdowns) {
        if (shown.equals(countdowns)) return false;
        shown = List.copyOf(countdowns);
        rows.getChildren().clear();
        controls.clear();
        controls.put("online.countdowns.add", add);
        for (Campaign.Countdown countdown : shown) {
            Label name = new Label(countdown.name());
            name.setWrapText(true);
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label value = new Label(countdown.value() + " / " + countdown.initialValue());
            value.getStyleClass().add("countdown-value");
            value.setMinWidth(USE_PREF_SIZE);
            Button down = action(MaterialDesignM.MINUS, "Step down: " + countdown.name(),
                    () -> step.accept(countdown.id(), -1));
            Button up = action(MaterialDesignP.PLUS, "Step up: " + countdown.name(),
                    () -> step.accept(countdown.id(), 1));
            Button remove = action(MaterialDesignC.CLOSE, "Delete countdown: " + countdown.name(),
                    () -> delete.accept(countdown.id()));
            down.setDisable(countdown.value() == 0);
            up.setDisable(countdown.value() == countdown.initialValue());
            HBox row = new HBox(4, name, down, value, up, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("countdown-row");
            rows.getChildren().add(row);
            String prefix = "online.countdowns." + countdown.id();
            controls.put(prefix + ".down", down);
            controls.put(prefix + ".up", up);
            controls.put(prefix + ".delete", remove);
        }
        return true;
    }

    private static Button action(org.kordamp.ikonli.Ikon icon, String tooltip, Runnable handler) {
        Button button = Icons.button(icon, tooltip, handler);
        button.getStyleClass().add("countdown-action");
        return button;
    }
}
