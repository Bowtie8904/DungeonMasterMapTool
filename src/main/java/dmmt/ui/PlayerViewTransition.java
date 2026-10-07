package dmmt.ui;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Objects;

/** Holds the outgoing composite above the live canvases until their first new frame is ready. */
public final class PlayerViewTransition {
    private final StackPane root;
    private final ImageView outgoing = new ImageView();
    private final FadeTransition fade = new FadeTransition(Duration.seconds(0.4), outgoing);
    private boolean pending;

    public PlayerViewTransition(StackPane root) {
        this.root = root;
        outgoing.setMouseTransparent(true);
        outgoing.setManaged(false);
        outgoing.setVisible(false);
        outgoing.fitWidthProperty().bind(root.widthProperty());
        outgoing.fitHeightProperty().bind(root.heightProperty());
        root.getChildren().add(outgoing);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_BOTH);
        fade.setOnFinished(event -> cancel());
    }

    public void capture() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.BLACK);
        // Include an in-flight fade so another switch starts from exactly what the players see.
        var image = root.snapshot(parameters, null);
        fade.stop();
        outgoing.setImage(image);
        outgoing.setOpacity(1);
        outgoing.setVisible(true);
        pending = true;
    }

    public void frameRendered() {
        if (pending) {
            pending = false;
            fade.playFromStart();
        }
    }

    public void cancel() {
        fade.stop();
        pending = false;
        outgoing.setVisible(false);
        outgoing.setImage(null);
    }

    public static boolean contentChanged(DmProject before, Path beforeFile, DmProject.CameraState beforeCamera,
                                         DmProject after, Path afterFile, DmProject.CameraState afterCamera) {
        if (!Objects.equals(normalize(beforeFile), normalize(afterFile))
                || !Objects.equals(beforeCamera, afterCamera)) {
            return true;
        }
        return !Objects.equals(before.getMap().getImagePath(), after.getMap().getImagePath())
                || !Objects.equals(before.getMap().getGrid(), after.getMap().getGrid())
                || before.getMap().getRotationQuarterTurns() != after.getMap().getRotationQuarterTurns()
                || !Objects.equals(before.getImageLayers().stream().filter(DmProject.ImageLayer::isVisible).toList(),
                                  after.getImageLayers().stream().filter(DmProject.ImageLayer::isVisible).toList())
                || !Objects.equals(before.getWalls(), after.getWalls())
                || !Objects.equals(before.getInteractables(), after.getInteractables())
                || before.getFog().isEnabled() != after.getFog().isEnabled()
                || (before.getFog().isEnabled() && !Objects.equals(mask(before), mask(after)))
                || !Objects.equals(before.getLighting(), after.getLighting())
                || !Objects.equals(before.getOverlays().stream().filter(DmProject.OverlayShape::isPlayerVisible).toList(),
                                  after.getOverlays().stream().filter(DmProject.OverlayShape::isPlayerVisible).toList())
                || before.isTextLayerVisible() != after.isTextLayerVisible()
                || (before.isTextLayerVisible()
                    && !Objects.equals(before.getTextBoxes().stream().filter(DmProject.TextBox::isPlayerVisible).toList(),
                                       after.getTextBoxes().stream().filter(DmProject.TextBox::isPlayerVisible).toList()))
                || !Objects.equals(before.getWeather(), after.getWeather());
    }

    private static Path normalize(Path file) {
        return file == null ? null : file.toAbsolutePath().normalize();
    }

    private static FogMask.Snapshot mask(DmProject project) {
        FogMask mask = project.getFog().getMask();
        return mask == null ? null : mask.snapshot();
    }
}
