package dmmt.online;

import dmmt.FxTestSupport;
import dmmt.model.DmProject;
import dmmt.service.Tuning;
import dmmt.ui.OnlinePlayPanel;
import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TokenControlTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void grantsPersistPerInstanceAndMapAndFailedSavesRestorePermissions() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Tokens");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        campaign.getPlayers().add(Campaign.Player.builder().id("second").name("Second").build());
        var token = app.project.getTokens().getFirst();
        controller.setTokenControl(campaign, "map", "token", "player", true);
        controller.setTokenControl(campaign, "map", "token", "player", true);
        assertEquals(1, campaign.getControlledTokens().size());
        assertNull(token.getActorId());
        controller.setTokenControl(campaign, "map", "token", "second", true);
        var loaded = controller.store().load(campaign.getId());
        assertNull(MoveValidator.check(app.project, loaded, "map", "player", token, 3, 0));
        assertNull(MoveValidator.check(app.project, loaded, "map", "second", token, 3, 0));
        assertNotNull(MoveValidator.check(app.project, loaded, "other-map", "player", token, 3, 0));
        assertNotNull(MoveValidator.check(app.project, loaded, "map", "player",
                app.project.getTokens().getLast(), 3, 0));
        token.setPlayerVisible(false);
        assertEquals("Token is hidden", MoveValidator.check(app.project, loaded, "map", "player", token, 3, 0));
        token.setPlayerVisible(true);
        var wall = new DmProject.WallSegment();
        wall.setX1(2); wall.setY1(-5); wall.setX2(2); wall.setY2(5);
        app.project.setWalls(List.of(wall));
        assertEquals("Blocked by a wall", MoveValidator.check(app.project, loaded, "map", "player", token, 3, 0));
        app.project.setWalls(List.of());
        var door = new DmProject.Interactable();
        door.setX1(2); door.setY1(-5); door.setX2(2); door.setY2(5);
        app.project.setInteractables(List.of(door));
        assertEquals("Blocked by a closed door", MoveValidator.check(app.project, loaded, "map", "player", token, 3, 0));

        Path blocked = temp.resolve(campaign.getId()).resolve("campaign.json.tmp");
        Files.createDirectory(blocked);
        assertThrows(IOException.class, () -> controller.setTokenControl(campaign, "map", "token", "player", false));
        assertEquals(2, campaign.getControlledTokens().size());
        assertEquals(2, controller.store().load(campaign.getId()).getControlledTokens().size());
        Files.delete(blocked);
        controller.setTokenControl(campaign, "map", "token", "player", false);
        loaded = controller.store().load(campaign.getId());
        assertEquals(java.util.Set.of("second"), OnlineStateBuilder.controllers(token, null, loaded, "map"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setTokenControl(campaign, "map", "token", "missing", true));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setTokenControl(campaign, "other-map", "token", "player", true));
    }

    @Test
    void snapshotsScopeExistingControlLinksToTheMap() throws Exception {
        TestApp app = new TestApp();
        Campaign campaign = new Campaign();
        campaign.getControlledTokens().add(new Campaign.TokenLink("player", "map", "token"));
        campaign.getControlledTokens().add(new Campaign.TokenLink("second", "other-map", "token"));
        var builder = new OnlineStateBuilder(file -> null);
        var assets = new AssetRegistry(temp.resolve("cache"), 2048, new CampaignStore(temp));
        var state = builder.build(app.project, new OnlineStateBuilder.Context("map", "level", "", null, 0, false, false),
                campaign, assets, List.of());
        assertEquals(1, state.path("tokens").get(0).path("controlledBy").size());
        assertEquals("player", state.path("tokens").get(0).path("controlledBy").get(0).asText());
        assertTrue(state.path("tokens").get(1).path("controlledBy").isEmpty());
    }

    @Test
    void contextMenuAssignsAndRevokesExistingBrowserMovementWhileHosting() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Tokens");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        OnlinePlayPanel[] panel = new OnlinePlayPanel[1];
        try {
            onFx(() -> {
                try (var socket = new java.net.ServerSocket(0)) {
                    String port = String.valueOf(socket.getLocalPort());
                    Tuning.apply(key -> "online.port".equals(key) ? port : null);
                }
                controller.start(campaign);
                panel[0] = new OnlinePlayPanel(controller, new OnlinePlayPanel.Actions() {
                    @Override public void placeActor(Campaign c, Campaign.Actor actor) { fail(); }
                    @Override public int foreignActorTokens(Campaign c) { return 0; }
                    @Override public void removeForeignActorTokens(Campaign c) { fail(); }
                });
                var token = app.project.getTokens().getFirst();
                var menu = panel[0].tokenControlMenu("map", token);
                assertEquals("Player movement control", menu.getText());
                CheckMenuItem item = (CheckMenuItem) menu.getItems().getFirst();
                assertFalse(item.isSelected());
                item.setSelected(true);
                item.fire();
                assertTrue(((CheckMenuItem) panel[0].tokenControlMenu("map", token).getItems().getFirst()).isSelected());
                return null;
            });
            assertTrue(controller.moveToken("player", "token", 3, 0).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                CheckMenuItem item = (CheckMenuItem) panel[0]
                        .tokenControlMenu("map", app.project.getTokens().getFirst()).getItems().getFirst();
                item.setSelected(false);
                item.fire();
                return null;
            });
            assertFalse(controller.moveToken("player", "token", 4, 0).get(5, TimeUnit.SECONDS).ok());
            assertTrue(controller.store().load(campaign.getId()).getControlledTokens().isEmpty());
        } finally {
            onFx(() -> {
                controller.stop();
                if (panel[0] != null) panel[0].refresh();
                Tuning.reset();
                return null;
            });
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static final class TestApp implements OnlineSessionController.App {
        private final DmProject project = new DmProject();
        TestApp() {
            project.setTokens(new java.util.ArrayList<>(List.of(
                    DmProject.TokenInstance.builder().id("token").definitionId("image").playerVisible(true).build(),
                    DmProject.TokenInstance.builder().id("copy").definitionId("image").playerVisible(true).build())));
        }
        @Override public DmProject project() { return project; }
        @Override public Path projectFile() { return null; }
        @Override public String mapId() { return "map"; }
        @Override public String levelId() { return "level"; }
        @Override public String mapName() { return "Map"; }
        @Override public boolean showGrid() { return false; }
        @Override public void applyMove(String id, double x, double y) {
            var token = project.getTokens().stream().filter(t -> t.getId().equals(id)).findFirst().orElseThrow();
            token.setX(x); token.setY(y);
        }
        @Override public void applyOverlay(String id, DmProject.OverlayShape shape) { fail(); }
        @Override public void measurementsChanged() { }
        @Override public void status(String text) { }
        @Override public dmmt.audio.AudioEngine audioEngine() { return null; }
        @Override public dmmt.audio.AudioLibraryService audioLibrary() { return null; }
        @Override public void changed() { }
    }
}
