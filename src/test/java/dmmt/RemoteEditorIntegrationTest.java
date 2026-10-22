package dmmt;

import dmmt.model.DmProject;
import dmmt.remote.RemoteAssetStore;
import dmmt.remote.RemoteProjectDocument;
import dmmt.remote.RemoteWorkspaceAuthority;
import dmmt.remote.RemoteWorkspaceHost;
import dmmt.remote.RemoteWorkspaceReplica;
import dmmt.remote.RemoteSettingsLink;
import dmmt.remote.RemoteAssetTransfer;
import dmmt.remote.RemotePlayerSession;
import dmmt.remote.RemotePlayerLink;
import dmmt.service.AppSettings;
import dmmt.service.FogService;
import dmmt.service.HostSettingsAuthority;
import dmmt.service.ProjectService;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteEditorIntegrationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void applicationClientMapBrowserLoadsOnlyHostCatalogAndRestoresTheLocalTree() throws Exception {
        var fixture = new AtomicReference<ClientFixture>();
        onFx(() -> fixture.set(clientFixture(false, false, true)));
        try {
            awaitFx(() -> {
                var browser = (dmmt.ui.MapBrowser) get(fixture.get().client, "mapBrowser");
                var tree = (javafx.scene.control.TreeView<?>) browser.getChildren().stream()
                        .filter(javafx.scene.control.TreeView.class::isInstance).findFirst().orElseThrow();
                return tree.getRoot() != null && tree.getRoot().getChildren().size() == 1
                        && ((dmmt.service.MapLibraryService.Entry) tree.getRoot().getChildren().getFirst().getValue()).name().equals("Host-only map");
            });
            onFx(() -> {
                var f = fixture.get();
                var browser = (dmmt.ui.MapBrowser) get(f.client, "mapBrowser");
                assertTrue(((javafx.scene.control.TextField) browser.lookup(".search-field")).getText().isEmpty());
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                assertEquals("Local-only map", ((javafx.scene.control.TextField) browser.lookup(".search-field")).getText());
                var tree = (javafx.scene.control.TreeView<?>) browser.getChildren().stream()
                        .filter(javafx.scene.control.TreeView.class::isInstance).findFirst().orElseThrow();
                assertEquals("Local-only map",
                        ((dmmt.service.MapLibraryService.Entry) tree.getRoot().getChildren().getFirst().getValue()).name());
                assertEquals(13, f.local.getTokens().getFirst().getX());
            });
        } finally {
            onFx(() -> {
                var f = fixture.get();
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            });
        }
    }

    @Test
    void clientNewMapSaveDialogUsesTheHostLibraryAndKeepsBWithoutAProjectFile() throws Exception {
        Path root = java.nio.file.Files.createDirectory(directory.resolve("dialog-host-library"));
        var fixture = new AtomicReference<ClientFixture>();
        var dialogFailure = new AtomicReference<Throwable>();
        onFx(() -> {
            var f = clientFixture();
            fixture.set(f);
            set(f.host.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()));
            invoke(f.client, "handleSave");
            Platform.runLater(() -> {
                try {
                    var window = javafx.stage.Window.getWindows().stream()
                            .filter(candidate -> candidate instanceof javafx.stage.Stage stage
                                    && stage.getTitle().equals("Save map on host A"))
                            .findFirst().orElseThrow();
                    var pane = (javafx.scene.control.DialogPane) window.getScene().getRoot();
                    var name = (javafx.scene.control.TextField) pane.lookup("#remote-save-name");
                    var folder = (javafx.scene.control.TextField) pane.lookup("#remote-save-folder");
                    assertEquals(root.toString(), folder.getText());
                    name.setText("Created from B");
                    var save = pane.getButtonTypes().stream().filter(button ->
                            button.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
                    ((javafx.scene.control.Button) pane.lookupButton(save)).fire();
                } catch (Throwable failure) {
                    dialogFailure.set(failure);
                    for (var window : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
                        if (window instanceof javafx.stage.Stage stage && stage.getTitle().equals("Save map on host A")) window.hide();
                    }
                }
            });
        });
        try {
            Path file = root.resolve("Created from B").resolve("Created from B.dmmap");
            awaitFx(() -> {
                if (dialogFailure.get() != null) throw new AssertionError("Host save dialog failed", dialogFailure.get());
                var f = fixture.get();
                invoke(f.client, "remoteReplicaFrameBoundary");
                return java.nio.file.Files.isRegularFile(file) && !(boolean) get(f.host.app, "ioBusy")
                        && !((dmmt.remote.RemoteSaveLink) get(f.client, "remoteSaveLink")).busy();
            });
            onFx(() -> {
                assertEquals(file, get(fixture.get().host.app, "projectFile"));
                assertNull(get(fixture.get().client, "projectFile"));
            });
        } finally {
            onFx(() -> {
                var f = fixture.get();
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            });
        }
    }

    @Test
    void clientSaveWaitsForItsCompletedEditAndNeverWritesItsLocalReplica() throws Exception {
        Path root = java.nio.file.Files.createDirectory(directory.resolve("client-save-host-library"));
        Path file = root.resolve("Dungeon").resolve("Dungeon.dmmap");
        var fixture = new AtomicReference<ClientFixture>();
        onFx(() -> {
            var f = clientFixture();
            fixture.set(f);
            set(f.host.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()));
            set(f.host.app, "projectFile", file);
            assertTrue(f.replica.canEdit());
            var project = (DmProject) get(f.client, "project");
            var token = project.getTokens().getFirst();
            invoke(f.client, "executeWithHistory", "Client move before Save",
                    (Runnable) () -> token.setX(55), (Runnable) () -> token.setX(0));
            invoke(f.client, "handleSave");
        });
        try {
            awaitFx(() -> {
                var f = fixture.get();
                invoke(f.client, "remoteReplicaFrameBoundary");
                var link = (dmmt.remote.RemoteSaveLink) get(f.client, "remoteSaveLink");
                return !(boolean) get(f.client, "remoteSaveQueued") && !link.busy()
                        && !(boolean) get(f.host.app, "ioBusy") && java.nio.file.Files.isRegularFile(file);
            });
            assertEquals(55, new dmmt.service.ProjectService().load(file).getTokens().getFirst().getX());
            onFx(() -> {
                var f = fixture.get();
                assertNull(get(f.client, "projectFile"), "B must not adopt or persist A's filesystem target.");
                assertNull(get(f.client, "mapLibrary"), "Save must not require B's local map library.");
                assertFalse((boolean) get(f.client, "ioBusy"));
                assertEquals(13, f.local.getTokens().getFirst().getX(), "The retained local map stays untouched.");
            });
        } finally {
            onFx(() -> {
                var f = fixture.get();
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            });
        }
    }

    @Test
    void invalidNewMapDestinationFailsWithoutChangingHostTarget() throws Exception {
        Path root = java.nio.file.Files.createDirectory(directory.resolve("valid-library"));
        Path outside = java.nio.file.Files.createDirectory(directory.resolve("outside-library"));
        var fixture = new AtomicReference<Fixture>();
        var result = new AtomicReference<CompletableFuture<dmmt.remote.RemoteSaves.Update>>();
        onFx(() -> {
            var f = fixture();
            fixture.set(f);
            set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()));
            var state = f.host.saves().state();
            result.set(f.host.saves().exchange(new dmmt.remote.RemoteSaves.Query(new dmmt.remote.RemoteSaves.Request(
                    state.sessionId(), state.contextId(), UUID.randomUUID(), state.nextSequence(), state.revision(),
                    "Dungeon", outside.toString())), () -> {}));
        });
        try {
            assertEquals(dmmt.remote.RemotePlayerSession.Status.FAILED,
                    result.get().get(30, TimeUnit.SECONDS).receipt().status());
            assertFalse(java.nio.file.Files.exists(outside.resolve("Dungeon")));
            onFx(() -> assertNull(get(fixture.get().app, "projectFile")));
        } finally {
            onFx(() -> fixture.get().app.endRemoteWorkspace());
        }
    }

    @Test
    void hostSavePersistsTheAcceptedSnapshotAndKeepsNewerEditsDirty() throws Exception {
        var fixture = new AtomicReference<Fixture>();
        var result = new AtomicReference<CompletableFuture<dmmt.remote.RemoteSaves.Update>>();
        Path root = java.nio.file.Files.createDirectory(directory.resolve("saved-library"));
        Path file = root.resolve("Dungeon").resolve("Dungeon.dmmap");
        onFx(() -> {
            var f = fixture();
            fixture.set(f);
            set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()));
            set(f.app, "projectFile", file);
            var state = f.host.saves().state();
            result.set(f.host.saves().exchange(new dmmt.remote.RemoteSaves.Query(new dmmt.remote.RemoteSaves.Request(
                    state.sessionId(), state.contextId(), UUID.randomUUID(), state.nextSequence(), state.revision(), null, null)), () -> {}));
            assertFalse(result.get().isDone());
            f.project.getTokens().getFirst().setX(91);
            set(f.app, "historyVersion", 1L);
        });
        try {
            assertEquals(dmmt.remote.RemotePlayerSession.Status.ACCEPTED,
                    result.get().get(30, TimeUnit.SECONDS).receipt().status());
            var saved = new dmmt.service.ProjectService().load(file);
            assertEquals(0, saved.getTokens().getFirst().getX());
            onFx(() -> {
                var f = fixture.get();
                assertEquals(91, f.project.getTokens().getFirst().getX());
                assertNotEquals(get(f.app, "savedFingerprint"),
                        new dmmt.service.ProjectService().fingerprint(f.project));
            });
        } finally {
            onFx(() -> fixture.get().app.endRemoteWorkspace());
        }
    }

    @Test
    void newHostMapSaveValidatesLibraryDestinationAndRetainsTheCreatedFileReceipt() throws Exception {
        var fixture = new AtomicReference<Fixture>();
        var result = new AtomicReference<CompletableFuture<dmmt.remote.RemoteSaves.Update>>();
        var request = new AtomicReference<dmmt.remote.RemoteSaves.Request>();
        Path root = java.nio.file.Files.createDirectory(directory.resolve("new-map-library"));
        onFx(() -> {
            var f = fixture();
            fixture.set(f);
            set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new dmmt.service.ProjectService()));
            var state = f.host.saves().state();
            request.set(new dmmt.remote.RemoteSaves.Request(state.sessionId(), state.contextId(), UUID.randomUUID(),
                    state.nextSequence(), state.revision(), "New dungeon", root.toString()));
            result.set(f.host.saves().exchange(new dmmt.remote.RemoteSaves.Query(request.get()), () -> {}));
        });
        try {
            var accepted = result.get().get(30, TimeUnit.SECONDS);
            assertEquals(dmmt.remote.RemotePlayerSession.Status.ACCEPTED, accepted.receipt().status());
            Path file = root.resolve("New dungeon").resolve("New dungeon.dmmap");
            assertTrue(java.nio.file.Files.isRegularFile(file));
            onFx(() -> {
                var f = fixture.get();
                assertEquals(file, get(f.app, "projectFile"));
                assertEquals(accepted.receipt(),
                        f.host.saves().exchange(new dmmt.remote.RemoteSaves.Query(request.get()), () -> {}).join().receipt());
            });
        } finally {
            onFx(() -> fixture.get().app.endRemoteWorkspace());
        }
    }

    @Test
    void remoteMultilevelSavePersistsSharedSettingsInTheManifest() throws Exception {
        Path root = java.nio.file.Files.createDirectory(directory.resolve("multilevel-save-library"));
        var service = new dmmt.service.ProjectService();
        var library = new dmmt.service.MapLibraryService(root, service);
        var multi = library.multiLevels();
        var created = multi.create(root, "Tower", java.util.List.of(
                new dmmt.service.MultiLevelService.PlanItem(new dmmt.service.MultiLevelService.Empty(), "Ground"),
                new dmmt.service.MultiLevelService.PlanItem(new dmmt.service.MultiLevelService.Empty(), "Upper")), null);
        var loaded = multi.loadLevel(created.manifestFile(), null);
        var fixture = new AtomicReference<Fixture>();
        var result = new AtomicReference<CompletableFuture<dmmt.remote.RemoteSaves.Update>>();
        onFx(() -> {
            var f = fixture();
            fixture.set(f);
            set(f.app, "mapLibrary", library);
            set(f.app, "project", loaded.project());
            set(f.app, "projectFile", loaded.levelFile());
            set(f.app, "multiLevelFile", created.manifestFile());
            set(f.app, "multiLevelManifest", loaded.manifest());
            set(f.app, "currentLevelId", loaded.level().getId());
            loaded.project().getLighting().setTimeOfDayPreset("NIGHT");
            f.host.switchedContext();
            var state = f.host.saves().state();
            result.set(f.host.saves().exchange(new dmmt.remote.RemoteSaves.Query(new dmmt.remote.RemoteSaves.Request(
                    state.sessionId(), state.contextId(), UUID.randomUUID(), state.nextSequence(), state.revision(), null, null)), () -> {}));
        });
        try {
            assertEquals(dmmt.remote.RemotePlayerSession.Status.ACCEPTED,
                    result.get().get(30, TimeUnit.SECONDS).receipt().status());
            var manifest = multi.loadManifest(created.manifestFile());
            String other = manifest.getLevels().stream().filter(level -> !level.getId().equals(loaded.level().getId()))
                    .findFirst().orElseThrow().getId();
            assertEquals("NIGHT", multi.loadLevelForPreparation(created.manifestFile(), other).project()
                    .getLighting().getTimeOfDayPreset());
        } finally {
            onFx(() -> fixture.get().app.endRemoteWorkspace());
        }
    }

    @Test
    void standaloneFramesAndEditsNeverInitializeRemoteServicesOrInspectRemoteState() throws Exception {
        onFx(() -> {
            var app = application("standalone-opt-in.ini");
            // A missing project makes accidental synchronization capture fail rather than hiding its cost.
            set(app, "project", null);
            for (int i = 0; i < 100; i++) {
                invoke(app, "remoteFrameBoundary");
                invoke(app, "requestRemoteWorkspaceCommit");
                invoke(app, "handoutsChanged");
            }
            for (String name : java.util.List.of("remoteSessionWorker", "remoteHostingController",
                    "remoteConnectionController", "remoteWorkspaceHost", "remoteWorkspaceReplica",
                    "remoteHandoutHost", "remoteHandoutLibrary", "remoteSettingsLink", "remoteViewStamp", "remoteSaveLink")) {
                assertNull(get(app, name), name + " must remain uninitialized in standalone mode.");
            }
            assertFalse((boolean) get(app, "remoteCommitPending"));
            assertFalse((boolean) get(app, "remoteCommitScheduled"));
        });
    }

    @Test
    void remotePreparationWorkersAreLazyAndReleaseTheirThreadsAfterExplicitWork() throws Exception {
        var executor = new AtomicReference<java.util.concurrent.ThreadPoolExecutor>();
        onFx(() -> {
            var app = application("worker-opt-in.ini");
            assertNull(get(app, "remoteSessionWorker"));
            var worker = (java.util.concurrent.ThreadPoolExecutor) invoke(app, "remoteSessionWorker");
            executor.set(worker);
            assertEquals(0, worker.getPoolSize(), "Even opening setup must not start preparation threads by itself.");
            assertTrue(worker.allowsCoreThreadTimeOut());
        });
        try {
            executor.get().submit(() -> {}).get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.get().getPoolSize() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0, executor.get().getPoolSize(), "No idle remote preparation threads may remain.");
            executor.get().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } finally {
            executor.get().shutdown();
            assertTrue(executor.get().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppedHostReturnsToInertStandaloneBoundariesAndCanHostAgain() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                f.app.endRemoteWorkspace();
                set(f.app, "project", null);
                invoke(f.app, "remoteFrameBoundary");
                invoke(f.app, "requestRemoteWorkspaceCommit");
                invoke(f.app, "handoutsChanged");
                for (String name : java.util.List.of("remoteWorkspaceHost", "remoteHandoutHost",
                        "remoteHandoutLibrary", "remotePlayerHost", "remoteViewStamp")) {
                    assertNull(get(f.app, name), name + " must be detached on stop.");
                }
                assertFalse((boolean) get(f.app, "remoteCommitPending"));
                set(f.app, "project", f.project);
                var restarted = f.app.beginRemoteWorkspace(f.store, directory.resolve("cache"), Runnable::run);
                assertTrue(restarted.ready());
                assertNotEquals(f.host, restarted);
                f.project.getTokens().getFirst().setX(42);
                invoke(f.app, "requestRemoteWorkspaceCommit");
                assertEquals(42, restarted.state().document().materialize(new DmProject.CameraState())
                        .getTokens().getFirst().getX());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void sameHandoutWindowControlsHostPresentationAndDisconnectRestoresLocalBoardWithoutHidingHost() throws Exception {
        var fixture = new AtomicReference<ClientFixture>();
        onFx(() -> {
            var f = clientFixture(true, true);
            fixture.set(f);
            set(f.host.app, "playerStage", new javafx.stage.Stage());
            set(f.host.app, "playerCanvas", new javafx.scene.canvas.Canvas(1920, 1080));
        });
        try {
            awaitFx(() -> {
                var f = fixture.get();
                invoke(f.client, "remoteReplicaFrameBoundary");
                var link = (dmmt.remote.RemoteHandoutLink) get(f.client, "remoteHandoutLink");
                return link.canEdit();
            });
            onFx(() -> {
                var f = fixture.get();
                var window = (dmmt.ui.HandoutWindow) get(f.client, "handoutWindow");
                assertNull(get(window, "recents"), "Remote board must not open the standalone client recent library.");
                window.applySnapshot(new dmmt.ui.HandoutWindow.Snapshot(
                        java.util.List.of(new javafx.scene.image.WritableImage(12, 8)), 90, true, true, java.util.List.of(0)));
                invoke(f.client, "handoutsChanged");
            });
            awaitFx(() -> {
                var f = fixture.get();
                invoke(f.client, "remoteReplicaFrameBoundary");
                return ((dmmt.remote.RemoteHandoutLink) get(f.client, "remoteHandoutLink")).canEdit();
            });
            onFx(() -> {
                var f = fixture.get();
                var host = (dmmt.ui.HandoutWindow) get(f.host.app, "handoutWindow");
                assertNotNull(host, () -> f.host.host.handouts().exchange(new dmmt.remote.RemoteHandouts.Query(null), () -> {}).join().toString());
                assertTrue(host.isShownToPlayers());
                assertEquals(90, host.getRotation());
                assertTrue(host.isMirrored());
                assertEquals(1, host.getImages().size());
                var local = get(f.client, "retainedLocalHandoutWindow");
                assertNotSame(local, get(f.client, "handoutWindow"));
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                assertTrue(host.isShownToPlayers(), "Disconnect must leave A's handout output running.");
                assertSame(local, get(f.client, "handoutWindow"));
                assertEquals(7, ((dmmt.ui.HandoutWindow) local).getImages().getFirst().getWidth());
                assertNull(get(f.client, "playerStage"));
            });
        } finally {
            onFx(() -> {
                var f = fixture.get();
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
                invoke(f.host.app, "closePlayerWindow");
                var hostWindow = (dmmt.ui.HandoutWindow) get(f.host.app, "handoutWindow");
                if (hostWindow != null) hostWindow.close();
                var local = (dmmt.ui.HandoutWindow) get(f.client, "handoutWindow");
                if (local != null) local.close();
            });
        }
    }

    private static void awaitFx(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        var result = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            onFx(() -> result.set(condition.call()));
            if (result.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Remote JavaFX state did not become ready.");
    }

    @Test
    void acceptedClientCalibrationRefreshesHostControlsWithoutWaitingForAFocusChange() throws Exception {
        onFx(() -> {
            var f = clientFixture();
            try {
                var tile = new javafx.scene.control.Spinner<Double>(0.1, 10, 1, 0.05);
                set(f.host.app, "tileInchesSpinner", tile);
                ((AppSettings) get(f.client, "preferences")).putDouble("player.tileInches", 2);
                invoke(f.client, "remoteReplicaFrameBoundary");
                assertEquals(2, ((AppSettings) get(f.host.app, "preferences")).getDouble("player.tileInches", 0));
                assertEquals(1, tile.getValue());
                invoke(f.host.app, "remoteSessionLifecycleBoundary");
                assertEquals(2, tile.getValue());
            } finally {
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void clientUsesHostPlayerGeometryAndControlsOutputAndPointersWithoutCreatingLocalCanvases() throws Exception {
        var fixture = new AtomicReference<ClientFixture>();
        onFx(() -> {
            var f = clientFixture(true);
            fixture.set(f);
            assertFalse(f.replica.canEdit());
            set(f.host.app, "playerStage", new javafx.stage.Stage());
            set(f.host.app, "playerCanvas", new javafx.scene.canvas.Canvas(1920, 1080));
            invoke(f.client, "remoteReplicaFrameBoundary");
            invoke(f.client, "remoteReplicaFrameBoundary");
            assertTrue(f.replica.canEdit());
            var rect = (dmmt.render.CanvasMapRenderer.WorldRect) invoke(f.client, "getPlayerViewportRect");
            double zoom = ((DmProject) get(f.client, "project")).getViews().getPlayerCamera().getZoom();
            assertEquals(1920 / zoom, rect.width());
            assertEquals(1080 / zoom, rect.height());
            assertTrue((boolean) invoke(f.client, "playerOutputVisible"));
            assertNull(get(f.client, "playerCanvas"));
            assertNull(get(f.client, "playerStage"));
            invoke(f.client, "controlRemotePlayer", RemotePlayerSession.Action.SCALE_TEST, true, 0);
            assertTrue((boolean) get(f.host.app, "showScaleTestSquare"));
        });
        onFx(() -> {
            var f = fixture.get();
            invoke(f.client, "remoteReplicaFrameBoundary");
            assertTrue((boolean) get(f.client, "showScaleTestSquare"));
            int history = ((Deque<?>) get(f.host.app, "undoStack")).size();
            invoke(f.client, "addPing", 125.0, 200.0);
            assertEquals(1, f.host.project.getActivePings().size());
            assertEquals(125, f.host.project.getActivePings().getFirst().getX());
            assertEquals(1, ((DmProject) get(f.client, "project")).getActivePings().size());
            invoke(f.host.app, "addPing", 600.0, 700.0);
            invoke(f.client, "remoteReplicaFrameBoundary");
            assertEquals(2, ((DmProject) get(f.client, "project")).getActivePings().size());
            assertEquals(history, ((Deque<?>) get(f.host.app, "undoStack")).size());

            set(f.client, "hoverInsideCanvas", true);
            set(f.client, "laserToolActive", true);
            invoke(f.client, "addLaserPoint", 40.0, 50.0);
            set(f.host.app, "hoverInsideCanvas", true);
            set(f.host.app, "laserToolActive", true);
            invoke(f.host.app, "addLaserPoint", 80.0, 90.0);
            invoke(f.client, "remoteReplicaFrameBoundary");
            var receivedHost = (java.util.List<?>) get(f.host.app, "remoteLaserTrail");
            var receivedClient = (java.util.List<?>) get(f.client, "remoteLaserTrail");
            assertEquals(40, ((dmmt.render.CanvasMapRenderer.LaserPoint) receivedHost.getLast()).x());
            assertEquals(80, ((dmmt.render.CanvasMapRenderer.LaserPoint) receivedClient.getLast()).x());
            assertEquals(1, ((java.util.List<?>) get(f.host.app, "laserTrail")).size());
            var hostSettings = (AppSettings) get(f.host.app, "preferences");
            int priorTrail = dmmt.service.Tuning.LASER_TRAIL_MS.get();
            try {
                hostSettings.applyEdit("laser.trailMs", "0");
                Thread.sleep(2);
                invoke(f.client, "pruneLaserTrail");
                assertFalse(((java.util.List<?>) get(f.client, "remoteLaserTrail")).isEmpty());
                set(f.client, "remoteLaserReceivedMillis", System.currentTimeMillis() - 600);
                invoke(f.client, "pruneLaserTrail");
                assertFalse((boolean) get(f.client, "remoteLaserActive"));
                assertTrue(((java.util.List<?>) get(f.client, "remoteLaserTrail")).isEmpty());
            } finally {
                hostSettings.applyEdit("laser.trailMs", String.valueOf(priorTrail));
            }
            invoke(f.client, "closePlayerWindow");
            assertNull(get(f.host.app, "playerStage"));
            assertNull(get(f.client, "playerStage"));
        });
        onFx(() -> {
            var f = fixture.get();
            try {
                invoke(f.client, "remoteReplicaFrameBoundary");
                assertNull(invoke(f.client, "getPlayerViewportRect"));
                f.connected.set(false);
                invoke(f.client, "remoteReplicaFrameBoundary");
                assertTrue(((java.util.List<?>) get(f.client, "remoteLaserTrail")).isEmpty());
            } finally {
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
                invoke(f.host.app, "closePlayerWindow");
            }
        });
    }

    @Test
    void realClientEditorSubmitsToHostHistoryAndReturnsToItsOriginalObjectsAndPreferences() throws Exception {
        var context = new AtomicReference<ClientFixture>();
        onFx(() -> {
            var f = clientFixture();
            context.set(f);
            assertNotSame(f.local, get(f.client, "project"));
            assertTrue(f.replica.canEdit());
            assertEquals("0.4", ((AppSettings) get(f.client, "preferences")).get("ui.gridOpacity", null));
            var live = (DmProject) get(f.client, "project");
            var token = live.getTokens().getFirst();
            invoke(f.client, "executeWithHistory", "Client move",
                    (Runnable) () -> token.setX(88), (Runnable) () -> token.setX(0));
            assertEquals(88, f.host.project.getTokens().getFirst().getX());
            assertTrue(((Deque<?>) get(f.client, "undoStack")).isEmpty());
            assertEquals(RemoteWorkspaceAuthority.Origin.REMOTE, f.host.host.state().history().undo().origin());
            assertEquals(13, f.local.getTokens().getFirst().getX());
        });
        onFx(() -> {
            var f = context.get();
            assertTrue(f.replica.canEdit());
            invoke(f.client, "undo");
            assertEquals(0, f.host.project.getTokens().getFirst().getX());
        });
        onFx(() -> {
            var f = context.get();
            try {
                assertTrue(f.replica.canEdit());
                assertEquals(0, ((DmProject) get(f.client, "project")).getTokens().getFirst().getX());
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                assertSame(f.local, get(f.client, "project"));
                assertEquals("local-token", get(f.client, "selectedTokenId"));
                assertEquals(1, ((Deque<?>) get(f.client, "undoStack")).size());
                assertEquals("0.9", ((AppSettings) get(f.client, "preferences")).get("ui.gridOpacity", null));
                invoke(f.client, "undo");
                assertEquals(3, f.local.getTokens().getFirst().getX());
            } finally {
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void clientLossCancelsUnfinishedGestureAndNeverRunsPersistentRevealOrCreatesPlayerOutput() throws Exception {
        onFx(() -> {
            var f = clientFixture();
            try {
                var live = (DmProject) get(f.client, "project");
                var mask = live.getFog().getMask();
                var before = mask.copyBits();
                live.getLighting().getLights().add(DmProject.LightSource.builder().id("local-preview-light")
                        .x(300).y(300).range(140).castsShadows(false)
                        .revealMode(DmProject.RevealMode.PERSISTENT).build());
                invoke(f.client, "updateWorkspaceLighting");
                assertEquals(before, mask.copyBits());
                invoke(f.client, "setPlayerFrozen", true);
                assertNull(get(f.client, "frozenPlayerProject"));
                invoke(f.client, "openPlayerWindow");
                assertNull(get(f.client, "playerStage"));
                // The accepted freeze request is delivered asynchronously. Do not start a second edit while pending.
                set(f.client, "draggingToken", true);
                live.getTokens().getFirst().setX(999);
                f.connected.set(false);
                invoke(f.client, "remoteReplicaFrameBoundary");
                assertFalse((boolean) get(f.client, "draggingToken"));
                assertFalse(f.replica.canEdit());
                assertEquals(0, ((DmProject) get(f.client, "project")).getTokens().getFirst().getX());
                assertEquals(0, f.host.project.getTokens().getFirst().getX());
                assertEquals(13, f.local.getTokens().getFirst().getX());
            } finally {
                set(f.client, "stopping", true);
                f.client.endRemoteReplica();
                f.settings.close();
                f.host.app.endRemoteWorkspace();
            }
        });
    }

    private ClientFixture clientFixture() throws Exception {
        return clientFixture(false);
    }

    private ClientFixture clientFixture(boolean includePlayer) throws Exception {
        return clientFixture(includePlayer, false);
    }

    private ClientFixture clientFixture(boolean includePlayer, boolean includeHandouts) throws Exception {
        return clientFixture(includePlayer, includeHandouts, false);
    }

    private ClientFixture clientFixture(boolean includePlayer, boolean includeHandouts, boolean includeMapBrowser) throws Exception {
        var host = fixture();
        var client = application("client.ini");
        var local = new DmProject();
        var definition = new DmProject.TokenDefinition();
        definition.setId("local-definition");
        local.getTokenDefinitions().put(definition.getId(), definition);
        var token = DmProject.TokenInstance.builder().id("local-token").definitionId(definition.getId()).x(3).build();
        local.getTokens().add(token);
        local.getViews().getDmCamera().setX(800);
        set(client, "project", local);
        set(client, "selectedTokenId", token.getId());
        invoke(client, "executeWithHistory", "Original local move",
                (Runnable) () -> token.setX(13), (Runnable) () -> token.setX(3));
        var preferences = (AppSettings) get(client, "preferences");
        if (includeMapBrowser) {
            var service = new dmmt.service.ProjectService();
            Path hostRoot = java.nio.file.Files.createDirectory(directory.resolve("host-map-browser"));
            java.nio.file.Files.writeString(hostRoot.resolve("Host-only map.dmmap"), "{}");
            set(host.app, "mapLibrary", new dmmt.service.MapLibraryService(hostRoot, service));
            Path localRoot = java.nio.file.Files.createDirectory(directory.resolve("local-map-browser"));
            java.nio.file.Files.writeString(localRoot.resolve("Local-only map.dmmap"), "{}");
            var localLibrary = new dmmt.service.MapLibraryService(localRoot, service);
            set(client, "mapLibrary", localLibrary);
            var browser = new dmmt.ui.MapBrowser(localLibrary, (dmmt.ui.MapBrowser.Host) invoke(client, "createBrowserHost"),
                    new dmmt.service.RecentMaps(preferences));
            set(client, "mapBrowser", browser);
            ((javafx.scene.control.TextField) browser.lookup(".search-field")).setText("Local-only map");
        }
        preferences.applyEdit("ui.gridOpacity", "0.9");
        var hostPreferences = (AppSettings) get(host.app, "preferences");
        hostPreferences.applyEdit("ui.gridOpacity", "0.4");
        var settings = new HostSettingsAuthority(hostPreferences);
        var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        RemoteSettingsLink.Connection settingsWire = new RemoteSettingsLink.Connection() {
            @Override public boolean connected() { return connected.get(); }
            @Override public HostSettingsAuthority.State latest() { return settings.stateInMemory(); }
            @Override public CompletableFuture<HostSettingsAuthority.Result> send(HostSettingsAuthority.Request request) {
                return CompletableFuture.completedFuture(settings.execute(request));
            }
        };
        RemoteWorkspaceReplica.Connection wire = new RemoteWorkspaceReplica.Connection() {
            @Override public CompletableFuture<dmmt.remote.RemoteOperations.Reply> operation(
                    dmmt.remote.RemoteOperations.Query query) {
                return host.host.operations().exchange(query, () -> {});
            }
            @Override public java.util.function.Supplier<CompletableFuture<dmmt.remote.RemoteMapCatalog.Listing>> maps() {
                return () -> host.host.maps().query();
            }
            @Override public dmmt.remote.RemoteSaveLink.Connection saves() {
                return new dmmt.remote.RemoteSaveLink.Connection() {
                    @Override public boolean connected() { return connected.get(); }
                    @Override public CompletableFuture<dmmt.remote.RemoteSaves.Update> query(dmmt.remote.RemoteSaves.Query query) {
                        return host.host.saves().exchange(query, () -> {});
                    }
                };
            }
            @Override public dmmt.remote.RemoteHandoutLink.Connection handouts() {
                if (!includeHandouts) return null;
                return new dmmt.remote.RemoteHandoutLink.Connection() {
                    @Override public boolean connected() { return connected.get(); }
                    @Override public dmmt.remote.RemoteHandouts.State latest() {
                        return host.host.handouts().exchange(new dmmt.remote.RemoteHandouts.Query(null), () -> {}).join().state();
                    }
                    @Override public CompletableFuture<dmmt.remote.RemoteHandouts.Update> send(
                            dmmt.remote.RemoteHandouts.Request request) {
                        return host.host.handouts().exchange(new dmmt.remote.RemoteHandouts.Query(request), () -> {});
                    }
                    @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(
                            RemoteAssetTransfer.Direction direction, RemoteAssetStore.AssetDescriptor asset) {
                        return CompletableFuture.completedFuture(asset);
                    }
                };
            }
            @Override public RemotePlayerLink.Connection player() {
                if (!includePlayer) return null;
                return new RemotePlayerLink.Connection() {
                    UUID context;
                    RemotePlayerSession.Point laser;
                    final java.util.List<RemotePlayerSession.Pointer> received = new java.util.ArrayList<>();
                    private RemotePlayerSession.Update exchange(RemotePlayerSession.Request request,
                                                                 java.util.List<RemotePlayerSession.Ping> pings) {
                        var response = host.host.player().exchange(new RemotePlayerSession.Query(request,
                                new RemotePlayerSession.Pointer(context, laser, pings)));
                        received.add(response.pointer());
                        return response;
                    }
                    @Override public boolean connected() { return connected.get(); }
                    @Override public RemotePlayerSession.State latest() { return host.host.player().state(); }
                    @Override public java.util.List<RemotePlayerSession.Pointer> pointers() {
                        var frames = java.util.List.copyOf(received);
                        received.clear();
                        return frames;
                    }
                    @Override public CompletableFuture<RemotePlayerSession.Update> send(RemotePlayerSession.Request request) {
                        return CompletableFuture.completedFuture(exchange(request, java.util.List.of()));
                    }
                    @Override public void laser(UUID map, RemotePlayerSession.Point point) {
                        context = map;
                        laser = point;
                        exchange(null, java.util.List.of());
                    }
                    @Override public void ping(UUID map, RemotePlayerSession.Point point, long durationMillis) {
                        context = map;
                        exchange(null, java.util.List.of(new RemotePlayerSession.Ping(point, durationMillis)));
                    }
                };
            }
            @Override public boolean connected() { return connected.get(); }
            @Override public boolean ready() { return host.host.ready(); }
            @Override public RemoteWorkspaceAuthority.State latest() { return host.host.state(); }
            @Override public void close() { connected.set(false); }
            @Override public CompletableFuture<RemoteWorkspaceAuthority.Result> send(RemoteWorkspaceAuthority.Request request) {
                return host.host.executeAsync(request);
            }
            @Override public CompletableFuture<RemoteAssetStore.AssetDescriptor> transfer(
                    RemoteAssetTransfer.Direction direction, RemoteAssetStore.AssetDescriptor asset) {
                return CompletableFuture.failedFuture(new AssertionError("This fixture has no artwork."));
            }
        };
        if (includeHandouts) {
            var localHandout = new dmmt.ui.HandoutWindow(null, java.util.List.of(), () -> true, () -> {}, () -> {});
            localHandout.applySnapshot(new dmmt.ui.HandoutWindow.Snapshot(
                    java.util.List.of(new javafx.scene.image.WritableImage(7, 5)), 0, false, false, null));
            set(client, "handoutWindow", localHandout);
        }
        var replica = client.beginRemoteReplica(wire, settingsWire, host.store,
                directory.resolve("client-cache"), Runnable::run);
        return new ClientFixture(client, local, host, settings, replica, connected);
    }

    private record ClientFixture(DungeonMasterMapToolApplication client, DmProject local, Fixture host,
                                 HostSettingsAuthority settings, RemoteWorkspaceReplica replica,
                                 java.util.concurrent.atomic.AtomicBoolean connected) {
    }

    @Test
    void remoteUndoAndRedoOperateTheHostsSharedHistoryAndRejectStaleDescriptions() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var token = f.project.getTokens().getFirst();
                invoke(f.app, "executeWithHistory", "Host move",
                        (Runnable) () -> token.setX(25), (Runnable) () -> token.setX(0));
                var baseline = f.host.state();
                assertEquals("Host move", baseline.history().undo().label());
                assertEquals(RemoteWorkspaceAuthority.Origin.HOST, baseline.history().undo().origin());
                var undo = RemoteWorkspaceAuthority.historyRequest(baseline, RemoteWorkspaceAuthority.Operation.UNDO);
                var result = f.host.executeAsync(undo).join();
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
                assertEquals(0, token.getX());
                assertSame(result, f.host.executeAsync(undo).join());
                assertEquals("Host move", result.state().history().redo().label());
                var redo = RemoteWorkspaceAuthority.historyRequest(result.state(), RemoteWorkspaceAuthority.Operation.REDO);
                invoke(f.app, "executeWithHistory", "New host move",
                        (Runnable) () -> token.setX(70), (Runnable) () -> token.setX(0));
                assertEquals(RemoteWorkspaceAuthority.Status.CONFLICT, f.host.executeAsync(redo).join().status());
                assertEquals(70, token.getX());
                baseline = f.host.state();
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, f.host.executeAsync(
                        RemoteWorkspaceAuthority.historyRequest(baseline, RemoteWorkspaceAuthority.Operation.UNDO))
                        .join().status());
                baseline = f.host.state();
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, f.host.executeAsync(
                        RemoteWorkspaceAuthority.historyRequest(baseline, RemoteWorkspaceAuthority.Operation.REDO))
                        .join().status());
                assertEquals(70, token.getX());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void remoteEditsUseExistingHostHistoryPreservingCapturedObjectReferencesAndUnrelatedChanges() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var token = f.project.getTokens().getFirst();
                invoke(f.app, "executeWithHistory", "Host move",
                        (Runnable) () -> token.setX(25), (Runnable) () -> token.setX(0));
                assertTrue(f.host.ready());
                var baseline = f.host.state();
                var client = baseline.document().materialize(new DmProject.CameraState());
                client.getTokens().getFirst().setX(80);
                var result = f.host.executeAsync(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Move token")).join();
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
                assertSame(f.project, get(f.app, "project"));
                assertSame(token, f.project.getTokens().getFirst());
                var history = (Deque<?>) get(f.app, "undoStack");
                assertEquals(2, history.size());
                assertEquals(RemoteWorkspaceAuthority.Origin.REMOTE, get(history.getFirst(), "origin"));
                f.project.getWeather().setType("RAIN");
                invoke(f.app, "requestRemoteWorkspaceCommit");
                assertTrue(f.host.ready());
                invoke(f.app, "undo");
                assertEquals(25, token.getX());
                assertEquals("RAIN", f.project.getWeather().getType());
                assertTrue(f.host.ready());
                invoke(f.app, "undo");
                assertEquals(0, token.getX(), "The original host history closure still refers to the live token.");
                invoke(f.app, "redo");
                assertEquals(25, token.getX());
                invoke(f.app, "redo");
                assertEquals(80, token.getX());
                assertEquals("RAIN", f.project.getWeather().getType());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void hostDragLocksOnlyItsTargetsAndNeverPublishesTheUnfinishedPreview() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var token = f.project.getTokens().getFirst();
                set(f.app, "selectedTokenId", token.getId());
                set(f.app, "draggingToken", true);
                token.setX(999);
                var baseline = f.host.state();
                var client = baseline.document().materialize(new DmProject.CameraState());
                client.getWeather().setType("SNOW");
                var result = f.host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Snow"));
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
                assertEquals(999, token.getX());
                assertEquals(0, f.host.state().document().materialize(
                        new DmProject.CameraState()).getTokens().getFirst().getX());
                baseline = f.host.state();
                client = baseline.document().materialize(new DmProject.CameraState());
                client.getTokens().getFirst().setX(17);
                assertEquals(RemoteWorkspaceAuthority.Status.BUSY, f.host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Conflicting drag")).status());
                set(f.app, "draggingToken", false);
                invoke(f.app, "recordHistory", "Host drag",
                        (Runnable) () -> token.setX(999), (Runnable) () -> token.setX(0));
                assertTrue(f.host.ready());
                assertEquals(999, f.host.state().document().materialize(
                        new DmProject.CameraState()).getTokens().getFirst().getX());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void hostCompletesPersistentRevealInTheSameRemoteHistoryEntryAndUndoDoesNotRevealItAgain() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                f.app.endRemoteWorkspace();
                f.project.getLighting().getLights().add(DmProject.LightSource.builder()
                        .id("persistent").x(300).y(300).range(140).castsShadows(false).enabled(true)
                        .revealMode(DmProject.RevealMode.PERSISTENT).build());
                var host = f.app.beginRemoteWorkspace(f.store, directory.resolve("cache"), Runnable::run);
                var before = f.project.getFog().getMask().copyBits();
                var baseline = host.state();
                var client = baseline.document().materialize(new DmProject.CameraState());
                client.getLighting().getLights().getFirst().setX(700);
                set(f.app, "fogDragging", true);
                assertEquals(RemoteWorkspaceAuthority.Status.BUSY, host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Move while host paints fog")).status());
                assertEquals(300, f.project.getLighting().getLights().getFirst().getX());
                assertEquals(before, f.project.getFog().getMask().copyBits());
                set(f.app, "fogDragging", false);
                baseline = host.state();
                var result = host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Move persistent light"));
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, result.status());
                assertNotEquals(before, f.project.getFog().getMask().copyBits());
                assertEquals(f.project.getFog().getMask().copyBits(),
                        host.state().document().materialize(new DmProject.CameraState()).getFog().getMask().copyBits());
                invoke(f.app, "undo");
                assertTrue(host.ready());
                invoke(f.app, "remoteWorkspaceFrameBoundary");
                assertEquals(before, f.project.getFog().getMask().copyBits());
                assertEquals(300, f.project.getLighting().getLights().getFirst().getX());
                invoke(f.app, "redo");
                assertTrue(host.ready());
                assertEquals(700, f.project.getLighting().getLights().getFirst().getX());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void unrelatedRemoteEditsDoNotSuppressTheHostsUnfinishedPersistentLightReveal() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                f.app.endRemoteWorkspace();
                var light = DmProject.LightSource.builder().id("host-light").x(300).y(300).range(100)
                        .castsShadows(false).enabled(true).revealMode(DmProject.RevealMode.PERSISTENT).build();
                f.project.getLighting().getLights().add(light);
                var host = f.app.beginRemoteWorkspace(f.store, directory.resolve("cache"), Runnable::run);
                var before = f.project.getFog().getMask().copyBits();
                set(f.app, "selectedLight", light);
                set(f.app, "draggingLight", true);
                light.setX(700);
                var baseline = host.state();
                var client = baseline.document().materialize(new DmProject.CameraState());
                client.getWeather().setType("RAIN");
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Rain")).status());
                ((dmmt.lighting.LightingEngine) get(f.app, "lightingEngine")).update(f.project);
                assertNotEquals(before, f.project.getFog().getMask().copyBits());
                assertEquals(before, host.state().document().materialize(
                        new DmProject.CameraState()).getFog().getMask().copyBits());
                set(f.app, "draggingLight", false);
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void freezingChangesRealPlayerOutputAndIsUndoableWithoutOpeningALocalPlayerWindow() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var baseline = f.host.state();
                var client = baseline.document().materialize(new DmProject.CameraState());
                client.getViews().setPlayerFrozen(true);
                assertEquals(RemoteWorkspaceAuthority.Status.ACCEPTED, f.host.execute(RemoteWorkspaceAuthority.diff(
                        baseline, RemoteProjectDocument.capture(client), "Freeze player view")).status());
                assertNotNull(get(f.app, "frozenPlayerProject"));
                assertNull(get(f.app, "playerStage"));
                invoke(f.app, "undo");
                assertNull(get(f.app, "frozenPlayerProject"));
                assertFalse(f.project.getViews().isPlayerFrozen());
                invoke(f.app, "redo");
                assertNotNull(get(f.app, "frozenPlayerProject"));
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void realMapSwitchRotatesContextEvenWhenReturningToTheSameMap() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var initial = f.host.state().contextId();
                var other = new DmProject();
                invoke(f.app, "switchProject", other, null);
                var second = f.host.state().contextId();
                assertNotEquals(initial, second);
                invoke(f.app, "switchProject", f.project, null);
                assertNotEquals(initial, f.host.state().contextId());
                assertNotEquals(second, f.host.state().contextId());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    @Test
    void hostMapOperationsCreateRenameAndRejectStaleWorkspaceConfirmations() throws Exception {
        Fixture f = fxValue(this::fixture);
        Path root = Files.createDirectory(directory.resolve("remote-map-actions"));
        onFx(() -> set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new ProjectService())));
        var mapper = dmmt.service.JsonMappers.create();
        try {
            var listing = mapper.treeToValue(operationQuery(f, "maps", "list", mapper.nullNode()),
                    dmmt.remote.RemoteMaps.Listing.class);
            var options = mapper.createObjectNode().put("discardUnsaved", true)
                    .put("workspaceRevision", fxValue(() -> f.host.state().revision()));
            var create = new dmmt.remote.RemoteMaps.Command(listing.context(), listing.revision(), List.of(),
                    listing.nodes().getFirst().id(), "Remote cave", options);
            assertEquals(dmmt.remote.RemoteOperations.Status.ACCEPTED,
                    operationCommand(f, "maps", "new", mapper.valueToTree(create)).status());
            Path file = fxValue(() -> (Path) get(f.app, "projectFile"));
            assertTrue(Files.isRegularFile(file));
            assertTrue(file.startsWith(root));
            awaitFx(f.host::ready);
            DmProject beforeRename = fxValue(() -> (DmProject) get(f.app, "project"));
            onFx(() -> invoke(f.app, "executeWithHistory", "Before rename",
                    (Runnable) () -> beforeRename.getViews().setPlayerZoomStep(1),
                    (Runnable) () -> beforeRename.getViews().setPlayerZoomStep(0)));
            awaitFx(f.host::ready);
            var beforeRenameHistory = fxValue(() -> f.host.state().history());
            listing = mapper.treeToValue(operationQuery(f, "maps", "list", mapper.nullNode()),
                    dmmt.remote.RemoteMaps.Listing.class);
            options = mapper.createObjectNode().put("workspaceRevision", fxValue(() -> f.host.state().revision()));
            var rename = new dmmt.remote.RemoteMaps.Command(listing.context(), listing.revision(),
                    List.of(listing.current()), null, "Renamed on A", options);
            assertEquals(dmmt.remote.RemoteOperations.Status.ACCEPTED,
                    operationCommand(f, "maps", "rename", mapper.valueToTree(rename)).status());
            Path renamed = fxValue(() -> (Path) get(f.app, "projectFile"));
            assertFalse(Files.exists(file));
            assertTrue(Files.isRegularFile(renamed));
            assertEquals("Renamed on A.dmmap", renamed.getFileName().toString());
            awaitFx(f.host::ready);
            assertSame(beforeRename, fxValue(() -> get(f.app, "project")));
            assertEquals(beforeRenameHistory, fxValue(() -> f.host.state().history()));
            listing = mapper.treeToValue(operationQuery(f, "maps", "list", mapper.nullNode()),
                    dmmt.remote.RemoteMaps.Listing.class);
            long staleRevision = fxValue(() -> f.host.state().revision());
            onFx(() -> {
                DmProject live = (DmProject) get(f.app, "project");
                invoke(f.app, "executeWithHistory", "Host viewport change",
                        (Runnable) () -> live.getViews().setPlayerZoomStep(2),
                        (Runnable) () -> live.getViews().setPlayerZoomStep(0));
            });
            awaitFx(f.host::ready);
            var stale = new dmmt.remote.RemoteMaps.Command(listing.context(), listing.revision(),
                    List.of(listing.current()), null, "Should not be renamed",
                    mapper.createObjectNode().put("workspaceRevision", staleRevision));
            assertEquals(dmmt.remote.RemoteOperations.Status.FAILED,
                    operationCommand(f, "maps", "rename", mapper.valueToTree(stale)).status());
            assertTrue(Files.isRegularFile(renamed));
            assertEquals(renamed, fxValue(() -> get(f.app, "projectFile")));
        } finally {
            onFx(f.app::endRemoteWorkspace);
        }
    }

    @Test
    void importingOneFileAsAFolderDoesNotLeaveTheUnsavedHostWorkspace() throws Exception {
        Fixture f = fxValue(this::fixture);
        Path root = Files.createDirectory(directory.resolve("remote-folder-import"));
        onFx(() -> set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(root, new ProjectService())));
        Path source = Files.writeString(directory.resolve("FolderSource.uvtt"), "{\"pixels_per_grid\":100}");
        var asset = f.store.publishRequiredAsset(source);
        Files.delete(source);
        var mapper = dmmt.service.JsonMappers.create();
        try {
            var listing = mapper.treeToValue(operationQuery(f, "maps", "list", mapper.nullNode()),
                    dmmt.remote.RemoteMaps.Listing.class);
            var options = mapper.valueToTree(new dmmt.remote.RemoteMapFiles.Import(List.of(
                    new dmmt.remote.RemoteMapFiles.Input("FolderSource.uvtt", asset, "Subfolder")), false, null, null));
            ((com.fasterxml.jackson.databind.node.ObjectNode) options)
                    .put("workspaceRevision", fxValue(() -> f.host.state().revision()));
            var checkOptions = mapper.createObjectNode();
            checkOptions.putArray("names").add("FolderSource.uvtt");
            var preflight = operationQuery(f, "maps", "importPreflight", mapper.valueToTree(
                    new dmmt.remote.RemoteMaps.Command(listing.context(), listing.revision(), List.of(), null, null, checkOptions)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) options).set("preflight", preflight.path("preflight"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) options).putArray("duplicateAccepted");
            var command = new dmmt.remote.RemoteMaps.Command(listing.context(), listing.revision(), List.of(),
                    listing.nodes().getFirst().id(), null, options);
            assertEquals(dmmt.remote.RemoteOperations.Status.ACCEPTED,
                    operationCommand(f, "maps", "importFolder", mapper.valueToTree(command)).status());
            assertSame(f.project, fxValue(() -> get(f.app, "project")));
            assertNull(fxValue(() -> get(f.app, "projectFile")));
            assertTrue(Files.isRegularFile(root.resolve("Subfolder").resolve("FolderSource").resolve("FolderSource.dmmap")));
        } finally {
            onFx(f.app::endRemoteWorkspace);
        }
    }

    @Test
    void unavailableAudioRemovesItsApiGroupWithoutRemovingOtherControls() throws Exception {
        onFx(() -> {
            var f = fixture();
            try {
                var api = new dmmt.api.DmControlApi(new dmmt.ui.ControlVisibility());
                api.add("audio.play", new javafx.scene.control.Button("Play"));
                api.add("ui.settings", new javafx.scene.control.Button("Settings"));
                set(f.app, "controlApi", api);
                assertEquals(1, api.describe(List.of("audio.play")).size());
                invoke(f.app, "registerAudioApiControls");
                assertTrue(api.describe(List.of("audio.play")).isEmpty());
                assertEquals(1, api.describe(List.of("ui.settings")).size());
            } finally {
                f.app.endRemoteWorkspace();
            }
        });
    }

    private com.fasterxml.jackson.databind.JsonNode operationQuery(Fixture fixture, String domain, String action,
                                                                    com.fasterxml.jackson.databind.JsonNode payload) throws Exception {
        var result = fxValue(() -> fixture.host.operations().exchange(
                new dmmt.remote.RemoteOperations.Query(null, domain, action, payload), () -> {}));
        return result.get(10, TimeUnit.SECONDS).value();
    }

    private dmmt.remote.RemoteOperations.Reply operationCommand(Fixture fixture, String domain, String action,
                                                                com.fasterxml.jackson.databind.JsonNode payload) throws Exception {
        var baseline = fxValue(() -> fixture.host.operations().exchange(
                new dmmt.remote.RemoteOperations.Query(null, null, null, null), () -> {}).join());
        var command = new dmmt.remote.RemoteOperations.Command(baseline.sessionId(), java.util.UUID.randomUUID(),
                baseline.nextSequence(), domain, action, payload);
        var query = new dmmt.remote.RemoteOperations.Query(command, null, null, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        dmmt.remote.RemoteOperations.Reply reply;
        do {
            reply = fxValue(() -> fixture.host.operations().exchange(query, () -> {}).join());
            if (reply.status() != dmmt.remote.RemoteOperations.Status.RUNNING) return reply;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("The host map operation did not finish.");
        return reply;
    }

    private static <T> T fxValue(java.util.concurrent.Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private Fixture fixture() throws Exception {
        var app = application("settings.ini");
        var project = new DmProject();
        var definition = new DmProject.TokenDefinition();
        definition.setId("definition");
        project.getTokenDefinitions().put(definition.getId(), definition);
        project.getTokens().add(DmProject.TokenInstance.builder().id("token").definitionId("definition").build());
        set(app, "project", project);
        new FogService().ensureMask(project);
        var store = new RemoteAssetStore(directory.resolve("store"), 1024, 8192, 4096);
        var host = app.beginRemoteWorkspace(store, directory.resolve("cache"), Runnable::run);
        assertTrue(host.ready());
        return new Fixture(app, project, store, host);
    }

    private DungeonMasterMapToolApplication application(String settingsFile) {
        String prior = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        System.setProperty(AppSettings.SYSTEM_PROPERTY, directory.resolve(settingsFile).toString());
        try {
            return new DungeonMasterMapToolApplication();
        } finally {
            if (prior == null) {
                System.clearProperty(AppSettings.SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppSettings.SYSTEM_PROPERTY, prior);
            }
        }
    }

    private record Fixture(DungeonMasterMapToolApplication app, DmProject project,
                           RemoteAssetStore store, RemoteWorkspaceHost host) {
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void onFx(CheckedAction action) throws Exception {
        var task = new FutureTask<Void>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
