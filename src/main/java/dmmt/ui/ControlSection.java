package dmmt.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;

import java.util.Locale;

/**
 * One section of the DM controls. It is shown as an overlay (header and body) when its rail icon is selected.
 */
public class ControlSection extends VBox {

    private final String id;
    private final ToggleButton railButton;
    private final HBox header;
    private final VBox body;

    public ControlSection(String title, Ikon ikon, String id, Node... content) {
        this.id = id;
        getStyleClass().add("dm-section");

        Label label = new Label(title.toUpperCase(Locale.ROOT));
        label.getStyleClass().add("section-title");
        header = new HBox(Icons.icon(ikon), label);
        header.getStyleClass().add("section-header");

        body = new VBox(content);
        body.getStyleClass().add("section-body");
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(header, body);

        railButton = Icons.toggle(ikon, title);
        railButton.getStyleClass().add("rail-toggle");
        railButton.setUserData(id);
    }

    public String id() {
        return id;
    }

    /** Adds a small control to the right end of the section header. */
    public void addHeaderAction(Node action) {
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        header.getChildren().addAll(spacer, action);
    }

    /** The section's content area. */
    public VBox body() {
        return body;
    }

    /** The icon on the rail that opens and closes this section. */
    public ToggleButton railButton() {
        return railButton;
    }
}
