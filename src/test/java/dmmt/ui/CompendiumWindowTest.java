package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.online.AssetRegistry;
import dmmt.online.Campaign;
import dmmt.online.CampaignStore;
import dmmt.online.OnlineSessionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompendiumWindowTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void rendersActualBrowserCompendiumInsideTheAppAndDisposesWithTheSession() throws Exception {
        CampaignStore store = new CampaignStore(temp.resolve("campaigns"));
        Campaign campaign = store.create("Test");
        Campaign.Player player = Campaign.Player.builder().name("Offline player").build();
        campaign.getPlayers().add(player);
        campaign.getActors().add(Campaign.Actor.builder().id("hero").name("Hero").ownerId(player.getId()).build());
        store.saveSheet(campaign.getId(), "hero", "{\"className\":\"Bard\",\"traits\":[],\"inventory\":[]}");
        Path data = temp.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("items.json"), """
                {"category":"items","label":"Items","entries":[{"id":"torch","name":"Embedded torch","text":"Light."}]}
                """);
        var host = new OnlineSessionServer.Host() {
            @Override public Path compendiumDirectory() { return data; }
            @Override public CompletableFuture<OnlineSessionServer.MoveResult> moveToken(String playerId, String tokenId, double x, double y) {
                return CompletableFuture.completedFuture(OnlineSessionServer.MoveResult.accepted());
            }
            @Override public void rosterChanged() { }
            @Override public void campaignChanged(Campaign c) { }
            @Override public void dmMessage(com.fasterxml.jackson.databind.JsonNode message) { }
        };
        var config = new OnlineSessionServer.Config(0, "Test", 4, 65536, 50, 100, 3, 60);
        var server = new OnlineSessionServer(config, campaign, store,
                new AssetRegistry(temp.resolve("cache"), 2048, store), host);
        server.start();
        CompendiumWindow window = new CompendiumWindow();
        AtomicBoolean closed = new AtomicBoolean();
        Stage[] stage = new Stage[1];
        StackPane desktop = fx(() -> {
            StackPane pane = new StackPane();
            stage[0] = new Stage();
            stage[0].setScene(new Scene(pane, 1250, 900));
            stage[0].show();
            window.show(pane, server, () -> closed.set(true));
            pane.layout();
            return pane;
        });
        try {
            WebView web = fx(() -> (WebView) desktop.lookup(".web-view"));
            assertNotNull(web);
            String text = "";
            for (int attempt = 0; attempt < 150; attempt++) {
                text = fx(() -> String.valueOf(web.getEngine().executeScript("document.body.textContent")));
                if (text.contains("Embedded torch")) break;
                Thread.sleep(100);
            }
            assertTrue(text.contains("Embedded torch"), "Real browser bundle must load in JavaFX WebView: " + text);
            assertEquals("0", fx(() -> String.valueOf(web.getEngine().executeScript("location.hash.length"))));
            assertEquals("1", fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelectorAll('.cmp-table').length"))));
            assertEquals("0", fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelectorAll('.dh-win-minimize, .dh-win-popout').length"))));
            fx(() -> { web.getEngine().executeScript("document.querySelector('.cmp-add-icon').click()"); return null; });
            for (int attempt = 0; attempt < 50 && !store.loadSheet(campaign.getId(), "hero").contains("Embedded torch"); attempt++)
                Thread.sleep(100);
            assertTrue(store.loadSheet(campaign.getId(), "hero").contains("Embedded torch"), "Real embedded Add must persist for an offline owner");
            fx(() -> { web.getEngine().executeScript("document.querySelector('.cmp-action-buttons button:not(.cmp-add-icon)').click()"); return null; });
            for (int attempt = 0; attempt < 50 && server.dmHistory(20).isEmpty(); attempt++)
                Thread.sleep(100);
            assertEquals("dm", server.dmHistory(20).getFirst().path("fromId").asText());
            fx(() -> {
                window.show(desktop, server, () -> closed.set(true));
                assertEquals(1, desktop.lookupAll(".compendium-window").size());
                desktop.resize(500, 300);
                var frame = (javafx.scene.layout.Region) desktop.lookup(".compendium-window");
                assertTrue(frame.getLayoutX() + frame.getWidth() <= 500);
                assertTrue(frame.getLayoutY() + frame.getHeight() <= 300);
                window.syncSession(null);
                assertTrue(closed.get());
                assertTrue(desktop.getChildren().isEmpty());
                return null;
            });
        } finally {
            fx(() -> { window.close(); stage[0].close(); return null; });
            server.stop();
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
