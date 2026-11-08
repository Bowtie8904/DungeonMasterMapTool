package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.online.Campaign;
import dmmt.online.OnlineSessionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectedPlayersOverlayTest {
    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void mirrorsRosterColorsIdentitiesAndLivePingWithoutInterceptingTheMap() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var overlay = new ConnectedPlayersOverlay();
            var root = new StackPane(overlay);
            StackPane.setAlignment(overlay, javafx.geometry.Pos.BOTTOM_LEFT);
            new Scene(root, 1000, 700).getStylesheets().add(
                    ConnectedPlayersOverlay.class.getResource("/dmmt/ui/dark.css").toExternalForm());
            assertFalse(overlay.isVisible());
            assertFalse(overlay.isManaged());
            assertTrue(overlay.isMouseTransparent());

            Campaign campaign = Campaign.builder().dmColor("#e53935").build();
            overlay.update(campaign, List.of(
                    new OnlineSessionServer.RosterEntry("p1", "Alice", 2, 42, "Aria", "#2979ff"),
                    new OnlineSessionServer.RosterEntry("p2", "Bob", 1, -1, "", "#00c853"),
                    new OnlineSessionServer.RosterEntry("p3", "Offline", 0, -1, "", "#ffffff")), false);
            root.applyCss();
            root.layout();
            assertTrue(overlay.isVisible());
            assertTrue(overlay.isManaged());
            assertEquals(4, overlay.getChildren().size());
            assertEquals("CONNECTED PLAYERS", text(overlay, 0, 0));
            assertEquals("live", text(overlay, 0, 1));
            assertRow(overlay, 1, "Dungeon Master", "Host", "#e53935");
            assertRow(overlay, 2, "Alice (Aria)", "42 ms", "#2979ff");
            assertRow(overlay, 3, "Bob", "--", "#00c853");
            assertEquals(215, overlay.getWidth(), 0.1);
            assertTrue(overlay.getHeight() < 150, "The roster remains a compact map overlay");

            overlay.update(campaign, List.of(
                    new OnlineSessionServer.RosterEntry("p1", "Alice", 1, 17, "New character", "#2979ff")), true);
            assertEquals("paused", text(overlay, 0, 1));
            assertEquals(3, overlay.getChildren().size());
            assertRow(overlay, 2, "Alice (New character)", "17 ms", "#2979ff");

            overlay.update(campaign, List.of(), false);
            assertEquals(2, overlay.getChildren().size(), "The host remains visible with no players");
            overlay.update(null, List.of(), false);
            assertFalse(overlay.isVisible());
            assertFalse(overlay.isManaged());
            assertTrue(overlay.getChildren().isEmpty(), "Stopping hosting clears the old roster");

            Campaign nextCampaign = Campaign.builder().dmColor("#aa00ff").build();
            overlay.update(nextCampaign, List.of(), false);
            assertRow(overlay, 1, "Dungeon Master", "Host", "#aa00ff");
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private static String text(ConnectedPlayersOverlay overlay, int row, int child) {
        return ((Label) ((HBox) overlay.getChildren().get(row)).getChildren().get(child)).getText();
    }

    private static void assertRow(ConnectedPlayersOverlay overlay, int row, String name, String ping, String color) {
        HBox entry = (HBox) overlay.getChildren().get(row);
        assertEquals(Color.web(color), ((Circle) entry.getChildren().getFirst()).getFill());
        assertEquals(name, text(overlay, row, 1));
        assertEquals(ping, text(overlay, row, 2));
    }
}
