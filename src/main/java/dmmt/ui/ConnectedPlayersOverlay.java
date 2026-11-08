package dmmt.ui;

import dmmt.online.Campaign;
import dmmt.online.OnlineSessionServer;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;

/** Read-only browser-style roster on the DM map. Updated on the JavaFX thread. */
public final class ConnectedPlayersOverlay extends VBox {
    public ConnectedPlayersOverlay() {
        super(7);
        getStyleClass().add("connected-players-overlay");
        setId("connectedPlayersOverlay");
        setPrefWidth(215);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMouseTransparent(true);
        setVisible(false);
        managedProperty().bind(visibleProperty());
        setAccessibleText("Connected players and ping to host");
    }

    public void update(Campaign campaign, List<OnlineSessionServer.RosterEntry> players, boolean paused) {
        getChildren().clear();
        setVisible(campaign != null);
        if (campaign == null) return;

        Label title = new Label("CONNECTED PLAYERS");
        title.getStyleClass().add("connected-players-heading");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        Label status = new Label(paused ? "paused" : "live");
        status.getStyleClass().add("connected-players-status");
        HBox heading = new HBox(6, title, status);
        heading.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(heading, row(campaign.getDmColor(), "Dungeon Master", "Host"));
        for (OnlineSessionServer.RosterEntry player : players) {
            if (player.connections() <= 0) continue;
            String identity = player.chatIdentity();
            String name = player.playerName() + (identity == null || identity.isEmpty() ? "" : " (" + identity + ")");
            getChildren().add(row(player.color(), name, player.pingMs() < 0 ? "--" : player.pingMs() + " ms"));
        }
    }

    private static HBox row(String color, String name, String ping) {
        Circle dot = new Circle(4.5, Color.web(color != null && color.matches("#[0-9a-fA-F]{6}")
                ? color : "#9da6b2"));
        dot.getStyleClass().add("connected-player-color");
        Label player = new Label(name);
        player.getStyleClass().add("connected-player-name");
        player.setWrapText(true);
        player.setMinWidth(0);
        player.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(player, Priority.ALWAYS);
        Label latency = new Label(ping);
        latency.getStyleClass().add("connected-player-ping");
        latency.setMinWidth(USE_PREF_SIZE);
        HBox row = new HBox(9, dot, player, latency);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
