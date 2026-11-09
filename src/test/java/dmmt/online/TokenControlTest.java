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
    void freshActorTokensInheritPersistedConditionsAndRevisitedTokensFollowSheetRemovals() throws Exception {
        var controller = new OnlineSessionController(temp, new TestApp());
        Campaign campaign = controller.store().create("Conditions");
        campaign.getActors().add(Campaign.Actor.builder().id("actor").name("Actor").build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var sheet = mapper.createObjectNode();
        sheet.put("notes", "Preserve unrelated character data");
        var effects = sheet.putArray("effects");
        effects.add(OnlineTokenConditions.effect(mapper.createObjectNode(), "old-map-token",
                new DmProject.TokenCondition("restrained", "restrained", "Restrained")));
        effects.add(OnlineTokenConditions.effect(mapper.createObjectNode(), "old-map-token",
                new DmProject.TokenCondition("custom-fire", "custom", "On Fire")));
        controller.store().saveSheet(campaign.getId(), "actor", sheet.toString());
        var fresh = DmProject.TokenInstance.builder().id("new-map-token")
                .actorId("actor").campaignId(campaign.getId()).build();
        controller.inheritActorConditions(campaign, fresh);
        assertEquals(List.of("restrained", "custom-fire"),
                fresh.getConditions().stream().map(DmProject.TokenCondition::getId).toList());
        assertEquals(sheet, mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor")));
        var reopened = new OnlineSessionController(temp, new TestApp());
        var revisited = DmProject.TokenInstance.builder().id("old-map-token")
                .actorId("actor").campaignId(campaign.getId()).build();
        reopened.inheritActorConditions(campaign, revisited);
        assertEquals("On Fire", revisited.getConditions().getLast().getName());
        effects.remove(0);
        controller.store().saveSheet(campaign.getId(), "actor", sheet.toString());
        reopened.inheritActorConditions(campaign, revisited);
        assertEquals(List.of("custom-fire"),
                revisited.getConditions().stream().map(DmProject.TokenCondition::getId).toList());
        var regular = DmProject.TokenInstance.builder().id("regular")
                .conditions(new java.util.ArrayList<>(fresh.getConditions())).build();
        reopened.inheritActorConditions(campaign, regular);
        assertEquals(2, regular.getConditions().size());
        var foreign = DmProject.TokenInstance.builder().id("foreign").actorId("actor").campaignId("foreign")
                .conditions(new java.util.ArrayList<>(fresh.getConditions())).build();
        reopened.inheritActorConditions(campaign, foreign);
        assertEquals(2, foreign.getConditions().size());
    }

    @Test
    void liveSessionProjectsActorConditionsOntoEveryTokenAndRemovesLegacyEffects() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Shared conditions");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        campaign.getActors().add(Campaign.Actor.builder().id("actor").name("Actor").ownerId("player").build());
        for (var token : app.project.getTokens()) {
            token.setActorId("actor");
            token.setCampaignId(campaign.getId());
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var sheet = mapper.createObjectNode();
        sheet.putArray("effects").add(OnlineTokenConditions.effect(mapper.createObjectNode(), "previous-map",
                new DmProject.TokenCondition("restrained", "restrained", "Restrained")));
        controller.store().saveSheet(campaign.getId(), "actor", sheet.toString());
        try {
            onFx(() -> {
                try (var socket = new java.net.ServerSocket(0)) {
                    String port = String.valueOf(socket.getLocalPort());
                    Tuning.apply(key -> "online.port".equals(key) ? port : null);
                }
                controller.start(campaign);
                for (var token : app.project.getTokens()) {
                    assertEquals("restrained", token.getConditions().getFirst().getId());
                }
                return null;
            });
            assertFalse(controller.moveToken("player", "token", 3, 0).get(5, TimeUnit.SECONDS).ok());
            assertTrue(controller.setTokenCondition("player", "token", "map", "level",
                    "restrained", "Restrained", "restrained", "cond_restrained", false).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                assertTrue(app.project.getTokens().stream().allMatch(token -> token.getConditions().isEmpty()));
                return null;
            });
            assertTrue(mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor")).path("effects").isEmpty());
            assertTrue(controller.setTokenCondition("token", "hidden", "Hidden", "hidden", "cond_invisible", true)
                    .get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.setTokenCondition("token", "custom", "On Fire", null, "cond_invisible", true)
                    .get(5, TimeUnit.SECONDS).ok());
            assertTrue(controller.setTokenCondition("token", "custom", "On Fire", null, "cond_burning", true)
                    .get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                assertTrue(app.project.getTokens().stream().allMatch(token ->
                        token.getConditions().stream().anyMatch(condition -> "hidden".equals(condition.getId())
                                && "cond_invisible".equals(condition.getIconId()))
                                && token.getConditions().stream().anyMatch(condition -> "custom".equals(condition.getType())
                                && "cond_burning".equals(condition.getIconId()))));
                return null;
            });
            assertEquals("buff", mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor"))
                    .path("effects").get(0).path("kind").asText());
            var full = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor"));
            full.putObject("stress").put("slots", 6).put("on", 6);
            controller.store().saveSheet(campaign.getId(), "actor", full.toString());
            onFx(() -> {
                controller.campaignEdited(campaign);
                assertTrue(app.project.getTokens().stream().allMatch(token ->
                        token.getConditions().stream().anyMatch(condition -> "vulnerable".equals(condition.getId()))));
                return null;
            });
            var generated = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor"));
            generated.withObject("/stress").put("on", 5);
            controller.store().saveSheet(campaign.getId(), "actor", generated.toString());
            onFx(() -> {
                controller.campaignEdited(campaign);
                assertTrue(app.project.getTokens().stream().allMatch(token ->
                        token.getConditions().stream().noneMatch(condition -> "vulnerable".equals(condition.getId()))));
                return null;
            });
            full.withObject("/stress").put("on", 6);
            controller.store().saveSheet(campaign.getId(), "actor", full.toString());
            onFx(() -> { controller.campaignEdited(campaign); return null; });
            assertTrue(controller.setTokenCondition("token", "vulnerable", "Vulnerable", "vulnerable",
                    "cond_concentrating", true)
                    .get(5, TimeUnit.SECONDS).ok());
            var manual = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(controller.store().loadSheet(campaign.getId(), "actor"));
            manual.withObject("/stress").put("on", 0);
            controller.store().saveSheet(campaign.getId(), "actor", manual.toString());
            onFx(() -> {
                controller.campaignEdited(campaign);
                assertTrue(app.project.getTokens().stream().allMatch(token ->
                        token.getConditions().stream().anyMatch(condition -> "vulnerable".equals(condition.getId()))));
                return null;
            });
        } finally {
            onFx(() -> { controller.stop(); Tuning.reset(); return null; });
        }
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
                    @Override public void openSheet(Campaign c, Campaign.Actor actor) { fail(); }
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

    @Test
    void rotationRequiresLiveControlAndCurrentMapAndPublishesNormalizedAngles() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Rotation");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        campaign.getControlledTokens().add(new Campaign.TokenLink("player", "map", "token"));
        try {
            onFx(() -> {
                try (var socket = new java.net.ServerSocket(0)) {
                    String port = String.valueOf(socket.getLocalPort());
                    Tuning.apply(key -> "online.port".equals(key) ? port : null);
                }
                controller.start(campaign);
                return null;
            });
            assertTrue(controller.supportsTokenRotation());
            assertTrue(controller.rotateToken("player", "token", "map", "level", -30).get(5, TimeUnit.SECONDS).ok());
            assertEquals(330, app.project.getTokens().getFirst().getRotationDeg());
            assertFalse(controller.rotateToken("second", "token", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "missing", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "copy", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "token", "wrong", "level", 40).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "token", "map", "wrong", 40).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "token", "map", "level", Double.NaN).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.rotateToken("player", "token", "map", "level", Double.POSITIVE_INFINITY).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> { app.project.getTokens().getFirst().setPlayerVisible(false); return null; });
            assertFalse(controller.rotateToken("player", "token", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                app.project.getTokens().getFirst().setPlayerVisible(true);
                controller.server().setPaused(true);
                return null;
            });
            assertFalse(controller.rotateToken("player", "token", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
            assertEquals(330, app.project.getTokens().getFirst().getRotationDeg());
            assertEquals(330, new dmmt.service.ProjectService().copy(app.project).getTokens().getFirst().getRotationDeg());
            onFx(() -> {
                controller.server().setPaused(false);
                controller.setTokenControl(campaign, "map", "token", "player", false);
                return null;
            });
            assertFalse(controller.rotateToken("player", "token", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
        } finally {
            onFx(() -> { controller.stop(); Tuning.reset(); return null; });
        }
        assertFalse(controller.rotateToken("player", "token", "map", "level", 40).get(5, TimeUnit.SECONDS).ok());
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    @Test
    void portalControlsRecheckLocksPauseAndMapAndPersistLocks() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Portals");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        try {
            onFx(() -> {
                try (var socket = new java.net.ServerSocket(0)) {
                    String port = String.valueOf(socket.getLocalPort());
                    Tuning.apply(key -> "online.port".equals(key) ? port : null);
                }
                app.project.getInteractables().add(DmProject.Interactable.builder().id("door").build());
                app.project.getInteractables().add(DmProject.Interactable.builder().id("window").type("window").build());
                controller.start(campaign);
                return null;
            });
            assertTrue(controller.supportsPortalControls());
            for (var portal : app.project.getInteractables()) {
                assertFalse(portal.isLocked());
                assertTrue(controller.togglePortal("player", portal.getId(), "map", "level").get(5, TimeUnit.SECONDS).ok());
                assertEquals("open", portal.getState());
                assertTrue(controller.togglePortal("player", portal.getId(), "map", "level").get(5, TimeUnit.SECONDS).ok());
                assertEquals("closed", portal.getState());
                onFx(() -> { portal.setLocked(true); return null; });
                assertEquals("locked", controller.togglePortal("player", portal.getId(), "map", "level")
                        .get(5, TimeUnit.SECONDS).reason());
                assertEquals("closed", portal.getState());
                onFx(() -> { portal.setState("open"); return null; });
                assertFalse(controller.togglePortal("player", portal.getId(), "map", "level").get(5, TimeUnit.SECONDS).ok());
                assertEquals("open", portal.getState());
                Path file = temp.resolve(portal.getId() + ".dmmap");
                new dmmt.service.ProjectService().save(file, app.project);
                assertTrue(new dmmt.service.ProjectService().load(file).getInteractables().stream()
                        .filter(p -> p.getId().equals(portal.getId())).findFirst().orElseThrow().isLocked());
                assertTrue(new dmmt.service.ProjectService().copy(app.project).getInteractables().stream()
                        .filter(p -> p.getId().equals(portal.getId())).findFirst().orElseThrow().isLocked());
                onFx(() -> { portal.setLocked(false); controller.server().setPaused(true); return null; });
                assertFalse(controller.togglePortal("player", portal.getId(), "map", "level").get(5, TimeUnit.SECONDS).ok());
                assertEquals("open", portal.getState());
                onFx(() -> { controller.server().setPaused(false); return null; });
                assertFalse(controller.togglePortal("player", portal.getId(), "wrong", "level").get(5, TimeUnit.SECONDS).ok());
                assertFalse(controller.togglePortal("player", portal.getId(), "map", "wrong").get(5, TimeUnit.SECONDS).ok());
                assertEquals("open", portal.getState());
            }
            assertFalse(controller.togglePortal("player", "missing", "map", "level").get(5, TimeUnit.SECONDS).ok());
            var state = new OnlineStateBuilder(file -> null).build(app.project,
                    new OnlineStateBuilder.Context("map", "level", "", null, 0, false, false), campaign,
                    new AssetRegistry(temp.resolve("cache"), 2048, controller.store()), List.of());
            assertFalse(state.path("doors").get(0).path("locked").asBoolean(true));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            assertFalse(mapper.readValue("{\"id\":\"legacy\",\"type\":\"window\"}", DmProject.Interactable.class).isLocked());
        } finally {
            onFx(() -> { controller.stop(); Tuning.reset(); return null; });
        }
        assertFalse(controller.togglePortal("player", "door", "map", "level").get(5, TimeUnit.SECONDS).ok());
    }

    @Test
    void mapPingsAreTransientSharedAndValidatePlayerMapPauseAndCoordinates() throws Exception {
        TestApp app = new TestApp();
        var controller = new OnlineSessionController(temp, app);
        Campaign campaign = controller.store().create("Pings");
        campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
        try {
            onFx(() -> {
                try (var socket = new java.net.ServerSocket(0)) {
                    String port = String.valueOf(socket.getLocalPort());
                    Tuning.apply(key -> "online.port".equals(key) ? port : null);
                }
                controller.start(campaign);
                return null;
            });
            assertTrue(controller.supportsMapPings());
            assertTrue(controller.mapPing("player", "map", "level", 123, 456).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                var ping = app.project.getActivePings().getFirst();
                assertEquals(123, ping.getX());
                assertEquals(456, ping.getY());
                assertEquals(Tuning.PING_DURATION_MS.get().longValue(), ping.getDurationMillis());
                var state = new OnlineStateBuilder(file -> null).build(app.project,
                        new OnlineStateBuilder.Context("map", "level", "", null, 0, false, false), campaign,
                        new AssetRegistry(temp.resolve("cache"), 2048, controller.store()), List.of());
                assertEquals(123, state.path("pings").get(0).path("x").asDouble());
                assertEquals(456, state.path("pings").get(0).path("y").asDouble());
                return null;
            });
            assertFalse(controller.mapPing("player", "wrong", "level", 0, 0).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.mapPing("player", "map", "wrong", 0, 0).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.mapPing("unknown", "map", "level", 0, 0).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.mapPing("player", "map", "level", Double.NaN, 0).get(5, TimeUnit.SECONDS).ok());
            assertFalse(controller.mapPing("player", "map", "level", 0, Double.POSITIVE_INFINITY).get(5, TimeUnit.SECONDS).ok());
            onFx(() -> { controller.server().setPaused(true); return null; });
            assertFalse(controller.mapPing("player", "map", "level", 0, 0).get(5, TimeUnit.SECONDS).ok());
        } finally {
            onFx(() -> { controller.stop(); Tuning.reset(); return null; });
        }
        assertFalse(controller.mapPing("player", "map", "level", 0, 0).get(5, TimeUnit.SECONDS).ok());
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
        @Override public void applyRotation(String id, double rotationDeg) {
            project.getTokens().stream().filter(t -> t.getId().equals(id)).findFirst().orElseThrow().setRotationDeg(rotationDeg);
        }
        @Override public void applyOverlay(String id, DmProject.OverlayShape shape) { fail(); }
        @Override public void togglePortal(DmProject.Interactable portal) {
            portal.setState("open".equals(portal.getState()) ? "closed" : "open");
        }
        @Override public void measurementsChanged() { }
        @Override public void status(String text) { }
        @Override public dmmt.audio.AudioEngine audioEngine() { return null; }
        @Override public dmmt.audio.AudioLibraryService audioLibrary() { return null; }
        @Override public void changed() { }
    }
}
