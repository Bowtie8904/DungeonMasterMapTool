package dmmt.ui;

import dmmt.online.Campaign;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Bottom-right overlay of the DM view showing the Fear tokens the GM holds in the hosted campaign. It is visible only
 * while an online session runs; the minus and plus buttons change the count. Small End scene and New session buttons
 * to its left reset the per-scene and per-session limits of the campaign's characters.
 */
public final class FearOverlay extends StackPane {
    private static final int PER_ROW = 6;

    private final Label count = new Label();
    private final Circle[] tokens = new Circle[Campaign.MAX_FEAR];
    private final Button down;
    private final Button up;
    private final Button endScene;
    private final Button newSession;
    /** The Fear card together with the reset buttons beside it. */
    private final HBox card;

    /** Height from the bottom of the view that the counter occupies, including its margin. */
    public double reservedHeight() {
        return isVisible() ? card.getHeight() + 14 + 8 : 14;
    }

    /** Runs the action whenever the space taken by the counter may have changed. */
    public void onReservedHeightChanged(Runnable action) {
        card.heightProperty().addListener((o, a, b) -> action.run());
        visibleProperty().addListener((o, a, b) -> action.run());
    }

    public void setLeadingControls(Node controls) {
        card.getChildren().addFirst(controls);
    }

    public FearOverlay(IntConsumer adjust) {
        this(adjust, () -> { }, () -> { });
    }

    public FearOverlay(IntConsumer adjust, Runnable endScene, Runnable newSession) {
        down = Icons.button(MaterialDesignM.MINUS, "Remove one Fear", () -> adjust.accept(-1));
        up = Icons.button(MaterialDesignP.PLUS, "Add one Fear", () -> adjust.accept(1));
        this.endScene = resetButton("End scene", MaterialDesignF.FLAG_CHECKERED,
                "End the scene: refresh per-scene uses and remove scene effects on every character", endScene);
        this.newSession = resetButton("New session", MaterialDesignC.CALENDAR_PLUS,
                "Start a new session: refresh per-session and per-scene uses, effects and session tokens", newSession);
        TilePane grid = new TilePane(5, 5);
        grid.setPrefColumns(PER_ROW);
        grid.setMaxWidth(TilePane.USE_PREF_SIZE);
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = new Circle(7);
            tokens[i].getStyleClass().add("fear-token");
            grid.getChildren().add(tokens[i]);
        }
        Label title = new Label("FEAR");
        title.getStyleClass().add("fear-title");
        count.getStyleClass().add("fear-count");
        HBox head = new HBox(6, title, count);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(6, head, grid);
        HBox card = new HBox(8, down, body, up);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("fear-overlay");
        card.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        VBox resets = new VBox(4, this.endScene, this.newSession);
        resets.setAlignment(Pos.BOTTOM_RIGHT);
        resets.setFillWidth(true);
        HBox row = new HBox(8, resets, card);
        this.card = row;
        row.setAlignment(Pos.BOTTOM_RIGHT);
        row.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        row.setPickOnBounds(false);
        getChildren().add(row);
        setAlignment(Pos.BOTTOM_RIGHT);
        setPadding(new Insets(0, 14, 14, 0));
        setPickOnBounds(false);
        setId("fearOverlay");
        setVisible(false);
        managedProperty().bind(visibleProperty());
        setFear(0);
    }

    /** The buttons, keyed by control id, so they can be hidden in the settings and driven by the local API. */
    public Map<String, Node> controls() {
        return Map.of("online.fearDown", down, "online.fearUp", up,
                "online.endScene", endScene, "online.newSession", newSession);
    }

    private static Button resetButton(String text, org.kordamp.ikonli.Ikon ikon, String tooltip, Runnable action) {
        Button button = Icons.button(ikon, tooltip, action);
        button.setText(text);
        button.getStyleClass().add("fear-reset-button");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    /** Shows or hides the overlay; it is only meant to be visible while a campaign is hosted. */
    public void setHosted(boolean hosted) {
        setVisible(hosted);
    }

    public void setFear(int fear) {
        int value = Math.max(0, Math.min(tokens.length, fear));
        count.setText(value + " / " + tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            tokens[i].getStyleClass().remove("fear-token-filled");
            if (i < value) {
                tokens[i].getStyleClass().add("fear-token-filled");
            }
        }
        down.setDisable(value <= 0);
        up.setDisable(value >= tokens.length);
    }
}
