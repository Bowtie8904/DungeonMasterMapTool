package dmmt.ui;

import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/** A colour-tinted, scalable view for one of the custom audio landmark icons. */
final class AudioCustomIconView extends StackPane {
    private final AudioCustomIkon icon;
    private final Color color;

    AudioCustomIconView(AudioCustomIkon icon, Color color, int size) {
        this.icon = icon;
        this.color = color;
        getStyleClass().add(AudioIcons.TINTED_CLASS);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        SVGPath path = new SVGPath();
        path.setContent(icon.path());
        path.setFill(color);
        path.setFillRule(FillRule.EVEN_ODD);
        path.setScaleX(size / 24.0);
        path.setScaleY(size / 24.0);
        getChildren().add(path);
    }

    String iconDescription() {
        return icon.getDescription();
    }

    Color iconColor() {
        return color;
    }
}
