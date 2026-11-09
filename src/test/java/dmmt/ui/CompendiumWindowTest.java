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
                {"format":"dmmap-daggerheart","version":1,"category":"items","label":"Items","entries":[{
                  "id":"torch","name":"Embedded torch","text":"Light.","editorExtension":{"note":"Keep me"},
                  "features":[{"name":"Illuminating","text":"A named feature.","automation":{"actions":[%s]}}]
                }]}
                """.formatted(java.util.stream.IntStream.range(0, 20).mapToObj(index -> """
                {"id":"light%d","label":"Light %d","timing":"action","confirm":"Confirm the target.",
                 "cost":{"hope":1},"steps":[{"op":"clear","resource":"stress","amount":1}]}
                """.formatted(index, index)).collect(java.util.stream.Collectors.joining(","))));
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
            assertReadableCatalogControls(web, ".compendium");
            for (String category : java.util.List.of("ancestries", "armor", "classes", "communities", "consumables",
                    "domain-cards", "transformations", "weapons"))
                Files.writeString(data.resolve(category + ".json"), "{\"format\":\"dmmap-daggerheart\",\"version\":1,\"category\":\""
                        + category + "\",\"label\":\"" + category + "\",\"entries\":[]}");
            fx(() -> {
                web.getEngine().executeScript("document.querySelectorAll('.catalog-tabs button')[1].click()");
                return null;
            });
            for (int attempt = 0; attempt < 100; attempt++) {
                if ("false".equals(fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelector('.catalog-nav select').disabled"))))) break;
                Thread.sleep(100);
            }
            fx(() -> {
                web.getEngine().executeScript("""
                        const category = document.querySelector('.catalog-nav select');
                        category.value = 'items'; category.dispatchEvent(new Event('change'));
                        document.querySelector('.catalog-entry-list button').click();
                        const text = document.querySelector('[data-field="items.text"] textarea');
                        text.value = 'Edited using guided fields.'; text.dispatchEvent(new Event('input'));
                        """);
                return null;
            });
            assertReadableCatalogControls(web, ".catalog-editor");
            assertEquals("4", fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelectorAll('.catalog-view-tabs button').length"))));
            assertEquals("0", fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelectorAll('.catalog-editor details').length"))),
                    "The editor must not expose nested accordions");
            assertEquals("true", fx(() -> String.valueOf(web.getEngine().executeScript(
                    "document.querySelector('.catalog-preview').textContent.includes('Edited using guided fields.')"))));
            fx(() -> {
                web.getEngine().executeScript("""
                        document.querySelectorAll('.catalog-view-tabs button')[1].click();
                        const featureText = document.querySelector('.catalog-feature-detail textarea');
                        featureText.value = 'Edited named feature'; featureText.dispatchEvent(new Event('input'));
                        document.querySelector('.catalog-feature-detail > button').click();
                        """);
                return null;
            });
            assertEquals("items.features[0]", fx(() -> String.valueOf(web.getEngine().executeScript(
                    "document.querySelector('.catalog-source select').value"))), "Feature automation must retain its owner");
            fx(() -> {
                web.getEngine().executeScript("""
                        const main = document.querySelector('.catalog-main');
                        main.scrollTop = 240;
                        window.scrollBeforeCatalogEdit = main.scrollTop;
                        const addField = document.querySelector('.catalog-automation > .catalog-fields > .catalog-add-field');
                        addField.querySelector('select').value = 'uses'; addField.querySelector('button').click();
                        """);
                return null;
            });
            assertEquals("true", fx(() -> String.valueOf(web.getEngine().executeScript("""
                    window.scrollBeforeCatalogEdit > 200 &&
                    Math.abs(document.querySelector('.catalog-main').scrollTop - window.scrollBeforeCatalogEdit) < 2
                    """))), "Adding a rule must preserve the actual WebView scroll offset");
            assertEquals("true", fx(() -> String.valueOf(web.getEngine().executeScript("""
                    document.querySelector('.catalog-save-bar').getBoundingClientRect().bottom <=
                    document.querySelector('.catalog-main').getBoundingClientRect().top
                    """))), "Save must remain outside the scrolling pane");
            fx(() -> {
                web.getEngine().executeScript("""
                        document.querySelector('[data-field="items.features[0].automation.actions"] > button:nth-of-type(2)').click();
                        """);
                return null;
            });
            assertEquals("true", fx(() -> String.valueOf(web.getEngine().executeScript("""
                    ['When / activation', 'Conditions', 'Costs & limits', 'Effects / results'].every(title =>
                      Array.from(document.querySelectorAll('.catalog-rule-section h4')).some(h => h.textContent === title))
                    """))), "Action editors must organize activation, conditions, costs and effects");
            fx(() -> {
                web.getEngine().executeScript("""
                        document.querySelector('[data-field="items.features[0].automation.actions[0].cost"] > button').click();
                        const hopeCost = document.querySelector('[data-field="items.features[0].automation.actions[0].cost.hope"] input');
                        hopeCost.value = '2'; hopeCost.dispatchEvent(new Event('input'));
                        document.querySelectorAll('.catalog-breadcrumbs button')[1].click();
                        document.querySelector('[data-field="items.features[0].automation.actions[0].steps"] > button').click();
                        const operation = document.querySelector('.catalog-list > .catalog-list-bar select');
                        operation.value = String(operation.options.length - 1);
                        document.querySelector('.catalog-list > .catalog-list-bar button').click();
                        document.querySelector('[data-field="items.features[0].automation.actions[0].steps[1]"] > button').click();
                        document.querySelector('[data-field="items.features[0].automation.actions[0].steps[1].effect"] > button').click();
                        const effectName = document.querySelector('[data-field="items.features[0].automation.actions[0].steps[1].effect.name"] input');
                        effectName.value = 'Guided protection'; effectName.dispatchEvent(new Event('input'));
                        document.querySelector('.catalog-breadcrumbs button').click();
                        """);
                return null;
            });
            assertEquals("true", fx(() -> String.valueOf(web.getEngine().executeScript("""
                    Math.abs(document.querySelector('.catalog-main').scrollTop - window.scrollBeforeCatalogEdit) < 2
                    """))), "Returning through breadcrumbs must restore the source panel's scroll position");
            assertReadableCatalogControls(web, ".catalog-editor");
            fx(() -> {
                web.getEngine().executeScript("""
                        document.querySelectorAll('.catalog-view-tabs button')[3].click();
                        document.querySelector('[data-field="items.editorExtension"] > button').click();
                        """);
                return null;
            });
            assertEquals("Keep me", fx(() -> String.valueOf(web.getEngine().executeScript(
                    "document.querySelector('[data-field=\"items.editorExtension.note\"] input').value"))),
                    "Unknown nested fields must remain accessible in Advanced");
            fx(() -> { web.getEngine().executeScript("document.querySelector('.catalog-save-bar button[type=\"submit\"]').click()"); return null; });
            for (int attempt = 0; attempt < 100 && !Files.readString(data.resolve("items.json")).contains("Edited using guided fields."); attempt++)
                Thread.sleep(100);
            assertTrue(Files.readString(data.resolve("items.json")).contains("Edited using guided fields."),
                    "The real DM editor must write the JSON, not merely change browser state");
            var edited = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data.resolve("items.json").toFile()).path("entries").get(0);
            assertEquals("Edited named feature", edited.path("features").get(0).path("text").asText());
            assertEquals(2, edited.path("features").get(0).path("automation").path("actions").get(0).path("cost").path("hope").asInt());
            var addedEffect = edited.path("features").get(0).path("automation").path("actions").get(0).path("steps").get(1).path("effect");
            assertEquals("Guided protection", addedEffect.path("name").asText());
            assertEquals("rest", addedEffect.path("until").asText());
            assertEquals(1, addedEffect.path("automation").path("modifiers").get(0).path("value").asInt());
            assertEquals("Keep me", edited.path("editorExtension").path("note").asText());
            assertFalse(edited.path("features").get(0).path("_catalogFeatureId").asText().isBlank());
            for (int attempt = 0; attempt < 100; attempt++) {
                if ("false".equals(fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelector('.catalog-save-bar button[type=\"submit\"]').disabled"))))) break;
                Thread.sleep(100);
            }
            fx(() -> {
                web.getEngine().executeScript("""
                        document.querySelector('.catalog-nav > button').click();
                        const idInput = document.querySelector('[data-field="items.id"] input');
                        idInput.value = 'new-entry'; idInput.dispatchEvent(new Event('input'));
                        const nameInput = document.querySelector('[data-field="items.name"] input');
                        nameInput.value = 'Created in the DM editor'; nameInput.dispatchEvent(new Event('input'));
                        document.querySelectorAll('.catalog-view-tabs button')[2].click();
                        document.querySelector('.catalog-automation > button').click();
                        """);
                return null;
            });
            for (int attempt = 0; attempt < 100; attempt++) {
                if ("1".equals(fx(() -> String.valueOf(web.getEngine().executeScript(
                        "document.querySelectorAll('.catalog-automation > .catalog-fields > .catalog-add-field > select').length"))))) break;
                Thread.sleep(100);
            }
            fx(() -> {
                web.getEngine().executeScript("""
                        const automationSelect = document.querySelector('.catalog-automation > .catalog-fields > .catalog-add-field > select');
                        automationSelect.value = 'uses';
                        document.querySelector('.catalog-automation > .catalog-fields > .catalog-add-field > button').click();
                        document.querySelector('[data-field="items.automation.uses"] > button').click();
                        """);
                return null;
            });
            assertReadableCatalogControls(web, ".catalog-editor");
            fx(() -> { web.getEngine().executeScript("document.querySelector('.catalog-save-bar button[type=\"submit\"]').click()"); return null; });
            for (int attempt = 0; attempt < 100 && !Files.readString(data.resolve("items.json")).contains("new-entry"); attempt++)
                Thread.sleep(100);
            var editorData = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data.resolve("items.json").toFile());
            assertEquals(2, editorData.path("entries").size());
            assertEquals(1, editorData.path("entries").get(1).path("automation").path("uses").path("max").asInt(),
                    "Nested automation must be editable without raw JSON");
            for (int attempt = 0; attempt < 100; attempt++) {
                if ("false".equals(fx(() -> String.valueOf(web.getEngine().executeScript("document.querySelector('.catalog-save-bar button[type=\"submit\"]').disabled"))))) break;
                Thread.sleep(100);
            }
            fx(() -> {
                web.getEngine().executeScript("""
                        const originalConfirm = window.confirm;
                        window.confirm = () => false;
                        document.querySelectorAll('.catalog-view-tabs button')[0].click();
                        const pendingName = document.querySelector('[data-field="items.name"] input');
                        pendingName.value = 'Unsaved draft'; pendingName.dispatchEvent(new Event('input'));
                        """);
                window.requestClose();
                assertTrue(window.isShowing(), "Cancelling discard must keep the editor and its draft open");
                web.getEngine().executeScript("""
                        pendingName.value = 'Created in the DM editor'; pendingName.dispatchEvent(new Event('input'));
                        window.confirm = originalConfirm;
                        """);
                return null;
            });
            fx(() -> {
                web.getEngine().executeScript("document.querySelectorAll('.catalog-tabs button')[0].click()");
                return null;
            });
            assertTrue(fx(() -> String.valueOf(web.getEngine().executeScript("document.body.textContent"))).contains("Embedded torch"));
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
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                fx(() -> { sheetWeb.getEngine().executeScript("document.querySelector('[data-kind=\"hope\"] button').click()"); return null; });
                for (int attempt = 0; attempt < 100
                        && mapper.readTree(store.loadSheet(campaign.getId(), "hero")).path("hope").path("on").asInt() != 1; attempt++)
                    Thread.sleep(100);
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

    private static void assertReadableCatalogControls(WebView web, String selector) throws Exception {
        String problems = fx(() -> String.valueOf(web.getEngine().executeScript("""
                (() => {
                  const root = document.querySelector('%s');
                  const inputs = Array.from(root.querySelectorAll('input:not([type="checkbox"]), textarea, select, option'));
                  const rgb = color => (color.match(/[\\d.]+/g) || []).slice(0, 3).map(Number);
                  const luminance = color => rgb(color).map(v => {
                    v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
                  }).reduce((n, v, i) => n + v * [0.2126, 0.7152, 0.0722][i], 0);
                  const failures = [];
                  for (const input of inputs) {
                    const style = getComputedStyle(input);
                    const background = style.backgroundColor;
                    const check = (color, kind) => {
                      const a = luminance(color), b = luminance(background);
                      const contrast = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
                      if (contrast < 4.5 || !Number.isFinite(contrast))
                        failures.push(input.tagName + ' ' + input.type + ' ' + kind + ': ' + color + ' on ' + background);
                    };
                    check(style.color, 'text');
                    const fill = style.getPropertyValue('-webkit-text-fill-color');
                    if (fill && fill !== 'currentcolor') check(fill, 'text fill');
                    if (input.hasAttribute('placeholder')) {
                      const placeholder = getComputedStyle(input, '::placeholder');
                      check(placeholder.color, 'placeholder');
                    }
                  }
                  return inputs.length ? failures.join('; ') : 'No controls found';
                })()
                """.formatted(selector))));
        assertEquals("", problems, "Compendium/editor controls must have at least 4.5:1 text contrast in the actual WebView");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
