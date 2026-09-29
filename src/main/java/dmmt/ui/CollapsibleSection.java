package dmmt.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import dmmt.service.AppSettings;

/**
 * A compact, collapsible panel section. The expanded state is remembered in the given settings.
 */
public class CollapsibleSection extends VBox {

    private final BooleanProperty expanded = new SimpleBooleanProperty(true);

    public CollapsibleSection(String title, Ikon ikon, AppSettings preferences, String id, Node... content) {
        getStyleClass().add("dm-section");

        FontIcon sectionIcon = Icons.icon(ikon);
        Label label = new Label(title.toUpperCase());
        label.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        FontIcon chevron = Icons.icon(MaterialDesignC.CHEVRON_DOWN);
        HBox header = new HBox(sectionIcon, label, spacer, chevron);
        header.getStyleClass().add("section-header");
        header.setOnMouseClicked(event -> setExpanded(!isExpanded()));

        VBox body = new VBox(content);
        body.getStyleClass().add("section-body");

        String key = "ui.section." + id + ".expanded";
        expanded.addListener((observable, oldValue, newValue) -> {
            body.setVisible(newValue);
            body.setManaged(newValue);
            chevron.setIconCode(newValue ? MaterialDesignC.CHEVRON_DOWN : MaterialDesignC.CHEVRON_RIGHT);
            if (preferences != null) {
                preferences.putBoolean(key, newValue);
            }
        });
        setExpanded(preferences == null || preferences.getBoolean(key, true));

        getChildren().addAll(header, body);
    }

    public boolean isExpanded() {
        return expanded.get();
    }

    public void setExpanded(boolean value) {
        expanded.set(value);
    }

    public BooleanProperty expandedProperty() {
        return expanded;
    }
}
