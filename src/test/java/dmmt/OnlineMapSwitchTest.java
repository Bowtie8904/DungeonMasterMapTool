package dmmt;

import dmmt.model.DmProject;
import dmmt.api.DmControlApi;
import dmmt.online.Campaign;
import dmmt.online.OnlineSessionController;
import dmmt.service.FogService;
import dmmt.service.MapLibraryService;
import dmmt.service.MultiLevelService;
import dmmt.service.ProjectService;
import dmmt.service.Tuning;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OnlineMapSwitchTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void playerMapControlsSitLeftOfSceneAndSessionButtonsAtBottomRight() throws Exception {
        onFx(() -> {
            var overlay = new dmmt.ui.FearOverlay(delta -> { });
            HBox mapControls = new HBox(new Button("Pull players"), new Button("Return to players' map"));
            overlay.setLeadingControls(mapControls);
            overlay.setHosted(true);
            Stage stage = new Stage();
            try {
                var root = new javafx.scene.layout.StackPane(overlay);
                stage.setScene(new Scene(root, 900, 600));
                stage.show();
                root.applyCss();
                root.layout();
                var bounds = mapControls.localToScene(mapControls.getBoundsInLocal());
                for (String id : java.util.List.of("online.endScene", "online.newSession")) {
                    var button = overlay.controls().get(id);
                    var buttonBounds = button.localToScene(button.getBoundsInLocal());
                    assertTrue(bounds.getMaxX() < buttonBounds.getMinX());
                }
                assertTrue(bounds.getMinX() > 0);
                assertEquals(586, bounds.getMaxY(), 1);
                assertTrue(overlay.reservedHeight() >= mapControls.getHeight());
            } finally {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void manualSwitchReturnsToLiveMapAndPersistsEdits() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            try {
                DmProject playerMap = f.initial;
                f.switchTo(f.other, f.otherFile);
                assertSame(playerMap, f.parkedProject());
                assertSame(f.other, f.get("project"));
                playerMap.getMap().setTags(java.util.List.of("Changed while away"));
                f.switchTo(f.projects.load(f.initialFile), f.initialFile);
                assertSame(playerMap, f.get("project"), "return must not replace the live map with a stale load");
                assertNull(f.get("onlinePlayerMap"));
                assertEquals(java.util.List.of("CHANGED WHILE AWAY"), f.projects.load(f.initialFile).getMap().getTags());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    void automaticSwitchPersistsLatestSourceAndSaveFailureKeepsOriginalMap() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(true);
            try {
                f.initial.getMap().setTags(java.util.List.of("Latest state"));
                f.switchTo(f.other, f.otherFile);
                assertNull(f.get("onlinePlayerMap"));
                assertSame(f.other, f.get("project"));
                assertEquals(java.util.List.of("LATEST STATE"), f.projects.load(f.initialFile).getMap().getTags());

                Files.delete(f.otherFile);
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> f.switchTo(f.initial, f.initialFile));
                assertTrue(failure.getCause().getMessage().contains("players' map"));
                assertSame(f.other, f.get("project"));
                assertNull(f.get("onlinePlayerMap"), "a failed switch must not leave the current map parked");
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    void pullControlIsHideableAndCallableThroughApiAndSavesPlayerEdits() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            try {
                var create = DungeonMasterMapToolApplication.class.getDeclaredMethod("createOnlineMapControls");
                create.setAccessible(true);
                f.set("onlineMapControls", (HBox) create.invoke(f.app));
                var visibility = (dmmt.ui.ControlVisibility) f.get("dmControlVisibility");
                var api = new DmControlApi(visibility);
                f.switchTo(f.other, f.otherFile);
                f.initial.getMap().setTags(java.util.List.of("Player edit"));
                Button pull = (Button) f.get("pullPlayersButton");
                assertEquals("Pull players", pull.getText());
                assertNull(pull.getGraphic());
                Button returnToPlayers = (Button) f.get("returnToPlayersButton");
                assertEquals("Return to players' map", returnToPlayers.getText());
                assertNull(returnToPlayers.getGraphic());
                assertFalse(returnToPlayers.isDisabled());
                visibility.apply(java.util.Set.of("online.pullPlayers"));
                assertFalse(pull.isManaged());
                assertFalse(pull.isDisabled());
                assertTrue(api.describe().stream().anyMatch(value -> "online.pullPlayers".equals(value.get("id"))));
                api.execute("online.pullPlayers", java.util.Map.of());
                assertNull(f.get("onlinePlayerMap"));
                assertSame(f.other, f.get("project"));
                assertTrue(pull.isDisabled());
                assertTrue(returnToPlayers.isDisabled());
                assertEquals(java.util.List.of("PLAYER EDIT"), f.projects.load(f.initialFile).getMap().getTags());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    void failedPullKeepsThePlayerMapLive() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            try {
                f.switchTo(f.other, f.otherFile);
                Files.delete(f.initialFile);
                var pull = DungeonMasterMapToolApplication.class.getDeclaredMethod("pullOnlinePlayers");
                pull.setAccessible(true);
                pull.invoke(f.app);
                assertSame(f.initial, f.parkedProject());
                assertSame(f.other, f.get("project"));
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    void emptyStartupWorkspaceAllowsExplicitAndAutomaticPulls() throws Exception {
        onFx(() -> {
            for (boolean automatic : new boolean[]{false, true}) {
                Fixture f = new Fixture(automatic);
                try {
                    f.set("startupProjectId", f.initial.getId());
                    f.set("projectFile", null);
                    f.switchTo(f.other, f.otherFile);
                    if (!automatic) {
                        assertSame(f.initial, f.parkedProject());
                        f.call("saveOnlinePlayerMap");
                        f.call("pullOnlinePlayers");
                    }
                    assertSame(f.other, f.get("project"));
                    assertNull(f.get("onlinePlayerMap"));
                } finally {
                    f.close();
                }
            }
            return null;
        });
    }

    @Test
    void startupWorkspaceWithContentAndOtherUnsavedWorkspacesRemainProtected() throws Exception {
        onFx(() -> {
            for (boolean startup : new boolean[]{false, true}) {
                Fixture f = new Fixture(false);
                try {
                    f.set("projectFile", null);
                    if (startup) {
                        f.set("startupProjectId", f.initial.getId());
                        f.initial.getTokens().add(DmProject.TokenInstance.builder().id("new-token").build());
                    }
                    f.switchTo(f.other, f.otherFile);
                    InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                            () -> f.call("saveOnlinePlayerMap"));
                    assertTrue(failure.getCause().getMessage().contains("Return to it and save it first"));
                    f.call("pullOnlinePlayers");
                    assertSame(f.initial, f.parkedProject());
                } finally {
                    f.close();
                }
            }
            return null;
        });
    }

    @Test
    void failedPullShowsActionableDialogAndApiReturnsErrorWithoutDialog() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new HBox(), 300, 100));
                stage.show();
                f.set("primaryStage", stage);
                f.set("projectFile", null);
                f.switchTo(f.other, f.otherFile);
                FutureTask<String> dismiss = new FutureTask<>(() -> {
                    Window window = Window.getWindows().stream().filter(Window::isShowing)
                            .filter(w -> w.getScene().getRoot() instanceof DialogPane).findFirst().orElseThrow();
                    DialogPane pane = (DialogPane) window.getScene().getRoot();
                    try {
                        assertEquals("Could not pull players", pane.getHeaderText());
                        return ((Label) pane.getContent()).getText();
                    } finally {
                        ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                    }
                });
                Platform.runLater(dismiss);
                f.call("pullOnlinePlayers");
                assertTrue(dismiss.get(5, TimeUnit.SECONDS).contains("Return to it and save it first"));
                assertSame(f.initial, f.parkedProject());
                f.set("apiActionRunning", true);
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> f.call("pullOnlinePlayers"));
                assertInstanceOf(dmmt.api.LocalApiServer.ApiException.class, failure.getCause());
                assertSame(f.initial, f.parkedProject());
            } finally {
                stage.close();
                f.close();
            }
            return null;
        });
    }

    @Test
    void emptyStartupWorkspaceDoesNotBlockSessionStop() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            OnlineSessionController live = null;
            try {
                f.controller.stop();
                f.set("startupProjectId", f.initial.getId());
                f.set("projectFile", null);
                f.call("createOnlinePlay");
                live = (OnlineSessionController) f.get("onlineController");
                live.start(live.store().create("Startup workspace"));
                f.switchTo(f.other, f.otherFile);
                live.stop();
                assertFalse(live.running());
                assertNull(f.get("onlinePlayerMap"));
            } finally {
                if (live != null && live.running()) live.stop();
                f.close();
            }
            return null;
        });
    }

    @Test
    void inactivePlayerLevelSaveDoesNotOverwriteDmLevelOrSharedSettings() throws Exception {
        onFx(() -> {
            Fixture f = new Fixture(false);
            try {
                var library = (MapLibraryService) f.get("mapLibrary");
                Path root = library.getRoot();
                Path ground = root.resolve("Ground").resolve("Ground.dmmap");
                Path upper = root.resolve("Upper").resolve("Upper.dmmap");
                f.projects.save(ground, project());
                f.projects.save(upper, project());
                MultiLevelService levels = library.multiLevels();
                Path manifest = levels.create(root, "Tower", java.util.List.of(
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(ground.getParent(), ground), "Ground"),
                        new MultiLevelService.PlanItem(new MultiLevelService.LibraryMap(upper.getParent(), upper), "Upper")),
                        null).manifestFile();
                var metadata = levels.loadManifest(manifest);
                String groundId = metadata.getLevels().getFirst().getId();
                String upperId = metadata.getLevels().get(1).getId();
                var first = levels.loadLevel(manifest, groundId);
                f.set("project", first.project());
                f.set("projectFile", first.levelFile());
                f.set("multiLevelFile", manifest);
                f.set("multiLevelManifest", first.manifest());
                f.set("currentLevelId", groundId);
                var second = levels.loadLevelForPreparation(manifest, upperId);
                levels.activateLevel(manifest, second);
                f.switchToLevel(manifest, second);
                first.project().getMap().setTags(java.util.List.of("Player edit"));
                second.project().getLighting().setTimeOfDayPreset("NIGHT");
                levels.saveLevel(manifest, upperId, second.project());
                byte[] before = Files.readAllBytes(manifest);
                var save = DungeonMasterMapToolApplication.class.getDeclaredMethod("saveOnlinePlayerMap");
                save.setAccessible(true);
                save.invoke(f.app);
                assertArrayEquals(before, Files.readAllBytes(manifest));
                assertEquals(upperId, levels.loadManifest(manifest).getCurrentLevelId());
                var returning = levels.loadLevelForPreparation(manifest, groundId);
                assertEquals("NIGHT", returning.project().getLighting().getTimeOfDayPreset());
                levels.activateLevel(manifest, returning);
                f.switchToLevel(manifest, returning);
                assertSame(first.project(), f.get("project"));
                assertEquals("NIGHT", first.project().getLighting().getTimeOfDayPreset());
                assertEquals(java.util.List.of("PLAYER EDIT"),
                        levels.loadLevelForPreparation(manifest, groundId).project().getMap().getTags());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    void applicationRoutesPlayerMovesToParkedMapAndSavesOnSessionStop() throws Exception {
        Fixture f = onFx(() -> {
            Fixture fixture = new Fixture(false);
            fixture.controller.stop();
            fixture.initial.setTokens(new java.util.ArrayList<>(java.util.List.of(
                    DmProject.TokenInstance.builder().id("token").definitionId("image").playerVisible(true).build())));
            fixture.projects.save(fixture.initialFile, fixture.initial);
            var create = DungeonMasterMapToolApplication.class.getDeclaredMethod("createOnlinePlay");
            create.setAccessible(true);
            create.invoke(fixture.app);
            var controller = (OnlineSessionController) fixture.get("onlineController");
            Campaign campaign = controller.store().create("Live player map");
            campaign.getPlayers().add(Campaign.Player.builder().id("player").name("Player").build());
            campaign.getControlledTokens().add(new Campaign.TokenLink("player", fixture.initial.getId(), "token"));
            controller.start(campaign);
            fixture.switchTo(fixture.other, fixture.otherFile);
            return fixture;
        });
        OnlineSessionController live = (OnlineSessionController) f.get("onlineController");
        try {
            String key = f.initial.getId() + "/" + f.initial.getId();
            assertTrue(live.moveToken("player", "token", 3, 0, key).get(5, TimeUnit.SECONDS).ok());
            assertTrue(live.rotateToken("player", "token", f.initial.getId(), f.initial.getId(), 90)
                    .get(5, TimeUnit.SECONDS).ok());
            onFx(() -> {
                assertEquals(3, f.initial.getTokens().getFirst().getX());
                assertEquals(90, f.initial.getTokens().getFirst().getRotationDeg());
                assertTrue(f.other.getTokens().isEmpty());
                f.set("ioBusy", true);
                live.stop();
                assertTrue(live.running(), "stop must wait for an in-flight file operation");
                f.set("ioBusy", false);
                Files.delete(f.initialFile);
                live.stop();
                assertTrue(live.running(), "a failed save must not discard the live map");
                assertSame(f.initial, f.parkedProject());
                f.projects.save(f.initialFile, f.initial);
                live.stop();
                assertFalse(live.running());
                assertEquals(3, f.projects.load(f.initialFile).getTokens().getFirst().getX());
                assertEquals(90, f.projects.load(f.initialFile).getTokens().getFirst().getRotationDeg());
                return null;
            });
        } finally {
            onFx(() -> {
                if (live.running()) live.stop();
                f.close();
                return null;
            });
        }
    }

    private final class Fixture {
        final ProjectService projects = new ProjectService();
        final DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
        final Path initialFile = temp.resolve("initial.dmmap");
        final Path otherFile = temp.resolve("other.dmmap");
        final DmProject initial = project();
        final DmProject other = project();
        final OnlineSessionController controller;

        Fixture(boolean autoPull) throws Exception {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Tuning.apply(key -> switch (key) {
                case "online.port" -> Integer.toString(port);
                case "online.autoPullPlayers" -> autoPull ? "true" : null;
                default -> null;
            });
            assertEquals(autoPull, Tuning.ONLINE_AUTO_PULL_PLAYERS.get());
            projects.save(initialFile, initial);
            projects.save(otherFile, other);
            set("project", initial);
            set("projectFile", initialFile);
            set("mapLibrary", new MapLibraryService(temp.resolve("library"), projects));
            controller = new OnlineSessionController(temp.resolve("campaigns"), new TestApp());
            set("onlineController", controller);
            Campaign campaign = controller.store().create("Test");
            controller.start(campaign);
        }

        DmProject parkedProject() throws Exception {
            Object parked = get("onlinePlayerMap");
            if (parked == null) return null;
            var accessor = parked.getClass().getDeclaredMethod("project");
            accessor.setAccessible(true);
            return (DmProject) accessor.invoke(parked);
        }

        void switchTo(DmProject next, Path path) throws Exception {
            var method = DungeonMasterMapToolApplication.class.getDeclaredMethod("switchProject", DmProject.class, Path.class);
            method.setAccessible(true);
            method.invoke(app, next, path);
        }

        void call(String name) throws Exception {
            var method = DungeonMasterMapToolApplication.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(app);
        }

        void switchToLevel(Path manifest, MultiLevelService.LoadedLevel loaded) throws Exception {
            Class<?> type = Class.forName("dmmt.DungeonMasterMapToolApplication$LevelContext");
            var of = type.getDeclaredMethod("of", Path.class, MultiLevelService.LoadedLevel.class);
            of.setAccessible(true);
            Object level = of.invoke(null, manifest, loaded);
            var method = DungeonMasterMapToolApplication.class.getDeclaredMethod(
                    "switchProject", DmProject.class, Path.class, type);
            method.setAccessible(true);
            method.invoke(app, loaded.project(), loaded.levelFile(), level);
        }

        Object get(String name) throws Exception {
            Field field = DungeonMasterMapToolApplication.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(app);
        }

        void set(String name, Object value) throws Exception {
            Field field = DungeonMasterMapToolApplication.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(app, value);
        }

        void close() {
            controller.stop();
            Tuning.reset();
        }

        final class TestApp implements OnlineSessionController.App {
            @Override public DmProject project() {
                try { return (DmProject) get("project"); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            }
            @Override public Path projectFile() {
                try { return (Path) get("projectFile"); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            }
            @Override public String mapId() { return project().getId(); }
            @Override public String levelId() { return project().getId(); }
            @Override public String mapName() { return "Test"; }
            @Override public boolean showGrid() { return true; }
            @Override public void applyMove(String id, double x, double y) { }
            @Override public void applyRotation(String id, double rotation) { }
            @Override public void togglePortal(DmProject.Interactable portal) { }
            @Override public void applyOverlay(String id, DmProject.OverlayShape shape) { }
            @Override public void measurementsChanged() { }
            @Override public void status(String text) { }
            @Override public dmmt.audio.AudioEngine audioEngine() { return null; }
            @Override public dmmt.audio.AudioLibraryService audioLibrary() { return null; }
            @Override public void changed() { }
        }
    }

    private static DmProject project() {
        DmProject project = DmProject.builder().build();
        new FogService().ensureMask(project);
        return project;
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(45, TimeUnit.SECONDS);
    }
}
