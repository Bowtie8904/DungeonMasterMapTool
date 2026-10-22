package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.remote.RemoteAssetTransfer;
import dmmt.remote.RemoteSessionClient;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteTransferOverlayTest {
    @BeforeAll static void startFx() throws Exception { FxTestSupport.startJavaFx(); }

    @Test void showsAtBottomCenterWhileTransferringAndHidesWhenIdle() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var overlay = new RemoteTransferOverlay();
            var root = new StackPane(new Pane(), overlay);
            new Scene(root, 800, 600);
            root.applyCss();
            root.layout();
            assertFalse(overlay.isVisible());

            overlay.update(List.of(new RemoteSessionClient.TransferProgress(
                    RemoteAssetTransfer.Direction.DOWNLOAD, 5_242_880, 10_485_760, "abc")),
                    sha -> "Goblin Theme.mp3");
            root.applyCss();
            root.layout();
            assertTrue(overlay.isVisible());
            assertEquals("Downloading: Goblin Theme.mp3", ((javafx.scene.control.Label)
                    ((javafx.scene.layout.VBox) overlay.getChildren().getFirst()).getChildren().getFirst()).getText());
            var card = overlay.getChildren().getFirst();
            var bounds = card.localToScene(card.getBoundsInLocal());
            assertTrue(bounds.getWidth() > 100, "card width " + bounds.getWidth());
            assertEquals(400, (bounds.getMinX() + bounds.getMaxX()) / 2, 1);
            assertTrue(bounds.getMaxY() < 600 && bounds.getMaxY() > 500, "card bottom " + bounds.getMaxY());

            overlay.update(List.of());
            assertFalse(overlay.isVisible());
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
