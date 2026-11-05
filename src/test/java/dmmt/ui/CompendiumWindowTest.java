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
        store.saveSheet(campaign.getId(), "hero", """
                {"name":"Hero","className":"Bard","subclass":"","ancestry":"","community":"","inventory":[],"traits":[
                {"name":"Agility","mod":0},{"name":"Strength","mod":0},{"name":"Finesse","mod":0},
                {"name":"Instinct","mod":0},{"name":"Presence","mod":0},{"name":"Knowledge","mod":0}]}
                """);
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
            CompendiumWindow sheet = new CompendiumWindow();
            String sheetUrl = server.dmSheetUrl("hero");
            AtomicBoolean sheetClosed = new AtomicBoolean();
            fx(() -> { sheet.showSheet(desktop, server, "Hero", sheetUrl, () -> {
                server.revokeDmSheet(sheetUrl);
                sheetClosed.set(true);
            }); return null; });
            WebView sheetWeb = fx(() -> (WebView) desktop.lookupAll(".web-view").stream().filter(node -> node != web).findFirst().orElseThrow());
            try {
                String sheetText = "";
                for (int attempt = 0; attempt < 150; attempt++) {
                    sheetText = fx(() -> String.valueOf(sheetWeb.getEngine().executeScript("document.body.textContent")));
                    if (sheetText.contains("Background") && sheetText.contains("Hero")) break;
                    Thread.sleep(100);
                }
                assertTrue(sheetText.contains("Background") && sheetText.contains("Hero"),
                        "Real character sheet must render in WebView without a player login: " + sheetText);
                assertTrue(server.roster().isEmpty(), "DM sheets must not connect offline owners");
                assertEquals("0", fx(() -> String.valueOf(sheetWeb.getEngine().executeScript("location.hash.length"))));
                fx(() -> { sheetWeb.getEngine().executeScript("document.querySelector('[data-kind=\"hope\"] button').click()"); return null; });
                for (int attempt = 0; attempt < 100 && !store.loadSheet(campaign.getId(), "hero").contains("\"on\":1"); attempt++)
                    Thread.sleep(100);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var saved = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(store.loadSheet(campaign.getId(), "hero"));
                assertEquals(1, saved.path("hope").path("on").asInt(), "DM resource edits must persist: "
                        + fx(() -> String.valueOf(sheetWeb.getEngine().executeScript("document.body.textContent"))));
                ((com.fasterxml.jackson.databind.node.ObjectNode) saved.path("hope")).put("on", 3);
                String dmToken = java.net.URI.create(sheetUrl).getFragment();
                var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/sheet/hero"))
                        .header("Authorization", "Bearer " + dmToken)
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(saved.toString())).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                String marked = "";
                for (int attempt = 0; attempt < 100; attempt++) {
                    marked = fx(() -> String.valueOf(sheetWeb.getEngine().executeScript(
                            "document.querySelectorAll('[data-kind=\"hope\"] button.on').length")));
                    if ("3".equals(marked)) break;
                    Thread.sleep(100);
                }
                assertEquals("3", marked, "Saved edits from another client must appear live in the DM's WebView");
                fx(() -> {
                    sheetWeb.getEngine().executeScript("document.querySelectorAll('[data-kind=\"hope\"] button')[1].click()");
                    sheet.requestClose();
                    return null;
                });
                for (int attempt = 0; attempt < 100 && !sheetClosed.get(); attempt++) Thread.sleep(100);
                assertTrue(sheetClosed.get(), "Closing must flush pending edits before disposing the WebView");
                assertEquals(2, mapper.readTree(store.loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt());
            } finally {
                fx(() -> { sheet.close(); return null; });
            }
            fx(() -> {
                window.show(desktop, server, () -> closed.set(true));
                assertEquals(1, desktop.lookupAll(".compendium-window").size());
                desktop.resize(500, 300);
                var frame = (javafx.scene.layout.Region) desktop.lookup(".compendium-window");
                assertTrue(frame.getLayoutX() + frame.getWidth() <= 500);
                assertTrue(frame.getLayoutY() + frame.getHeight() <= 300);
                frame.relocate(10.5, 20.25);
                desktop.resize(501, 301);
                double scale = stage[0].getOutputScaleX();
                for (double value : new double[] {frame.getLayoutX(), frame.getLayoutY(), frame.getWidth(), frame.getHeight()}) {
                    assertEquals(Math.rint(value * scale), value * scale, 1e-9, "Embedded WebView must sit on device pixels");
                }
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
