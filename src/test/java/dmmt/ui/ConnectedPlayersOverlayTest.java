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
                    new OnlineSessionServer.RosterEntry("p1", "Alice", 2, 42, "Aria", "#2979ff", "they/them"),
                    new OnlineSessionServer.RosterEntry("p2", "Bob", 1, -1, "", "#00c853"),
                    new OnlineSessionServer.RosterEntry("p3", "Offline", 0, -1, "", "#ffffff")), false);
            root.applyCss();
            root.layout();
            assertTrue(overlay.isVisible());
            assertTrue(overlay.isManaged());
            assertEquals(4, overlay.getChildren().size());
            assertEquals("CONNECTED PLAYERS", text(overlay, 0, 0));
            assertEquals("live", text(overlay, 0, 1));
            assertRow(overlay, 1, "Gamemaster", "Host", "#e53935");
            assertRow(overlay, 2, "Alice (Aria [they/them])", "42 ms", "#2979ff");
            assertRow(overlay, 3, "Bob", "--", "#00c853");
            assertTrue(overlay.getWidth() >= 215);
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
            assertRow(overlay, 1, "Gamemaster", "Host", "#aa00ff");
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void growsAndShrinksWithRosterAndAvailableMapWidth() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var overlay = new ConnectedPlayersOverlay();
            var root = new StackPane(overlay);
            StackPane.setAlignment(overlay, javafx.geometry.Pos.BOTTOM_LEFT);
            new Scene(root, 1000, 700).getStylesheets().add(
                    ConnectedPlayersOverlay.class.getResource("/dmmt/ui/dark.css").toExternalForm());
            Campaign campaign = Campaign.builder().build();
            overlay.update(campaign, List.of(), false);
            root.resize(1000, 700);
            root.applyCss();
            root.layout();
            double shortWidth = overlay.getWidth();
            assertEquals(215, shortWidth, 0.1);
            String longName = "An especially long character name with a lengthy account name";
            overlay.update(campaign, List.of(
                    new OnlineSessionServer.RosterEntry("p1", "Account", 1, 21, longName, "#2979ff", "they/them")), false);
            root.applyCss();
            root.layout();
            double longWidth = overlay.getWidth();
            assertTrue(longWidth > shortWidth, "The roster grows for a long identity");
            Label player = (Label) ((HBox) overlay.getChildren().get(2)).getChildren().get(1);
            double singleLineHeight = player.getHeight();
            root.resize(250, 700);
            root.layout();
            assertTrue(overlay.getWidth() <= 250, "The roster stays within the map width");
            assertTrue(player.getHeight() > singleLineHeight, "Long names wrap on a narrow map");
            root.resize(1000, 700);
            root.layout();
            assertEquals(longWidth, overlay.getWidth(), 0.1, "The roster expands again with the map");
            overlay.update(campaign, List.of(), false);
            root.applyCss();
            root.layout();
            assertEquals(shortWidth, overlay.getWidth(), 0.1, "The roster shrinks after a long name leaves");
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
