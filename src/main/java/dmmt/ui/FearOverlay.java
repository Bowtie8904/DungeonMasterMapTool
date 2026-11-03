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
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Bottom-right overlay of the DM view showing the Fear tokens the GM holds in the hosted campaign. It is visible only
 * while an online session runs; the minus and plus buttons change the count.
 */
public final class FearOverlay extends StackPane {
    private static final int PER_ROW = 6;

    private final Label count = new Label();
    private final Circle[] tokens = new Circle[Campaign.MAX_FEAR];
    private final Button down;
    private final Button up;

    public FearOverlay(IntConsumer adjust) {
        down = Icons.button(MaterialDesignM.MINUS, "Remove one Fear", () -> adjust.accept(-1));
        up = Icons.button(MaterialDesignP.PLUS, "Add one Fear", () -> adjust.accept(1));
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
        getChildren().add(card);
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
        return Map.of("online.fearDown", down, "online.fearUp", up);
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
