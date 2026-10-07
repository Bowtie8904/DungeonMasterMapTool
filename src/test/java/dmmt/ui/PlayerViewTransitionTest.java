package dmmt.ui;

import dmmt.FxTestSupport;
import dmmt.DungeonMasterMapToolApplication;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.service.ProjectService;
import dmmt.service.AppSettings;
import dmmt.service.Tuning;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlayerViewTransitionTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxTestSupport.startJavaFx();
    }

    @Test
    void equalCopiesAndDmOnlyChangesDoNotTransition() throws Exception {
        DmProject before = project();
        DmProject after = new ProjectService().copy(before);
        assertFalse(changed(before, after));
        after.getViews().getDmCamera().setX(500);
        after.getViews().setPlayerFrozen(true);
        after.getMap().setTags(java.util.List.of("DUNGEON"));
        after.getMap().setImageLayersLocked(true);
        after.setLastTextSettings(DmProject.TextSettings.builder().fontSize(40).build());
        after.getOverlays().add(DmProject.OverlayShape.builder().playerVisible(false).build());
        after.getTextBoxes().add(DmProject.TextBox.builder().playerVisible(false).build());
        after.getImageLayers().add(DmProject.ImageLayer.builder().visible(false).build());
        assertFalse(changed(before, after));
        after.getFog().getMask().setRevealedEncoded(before.getFog().getMask().getRevealedEncoded());
        assertFalse(changed(before, after), "Mask version changes alone are not visual changes");
    }

    @Test
    void detectsMapViewportAndEveryPersistentPlayerLayer() throws Exception {
        assertChange(p -> p.getViews().getPlayerCamera().setX(30));
        assertChange(p -> p.getViews().getPlayerCamera().setY(30));
        assertChange(p -> p.getViews().getPlayerCamera().setZoom(2));
        assertChange(p -> p.getMap().setRotationQuarterTurns(1));
        assertChange(p -> p.getMap().setImagePath("new.png"));
        assertChange(p -> p.getImageLayers().add(DmProject.ImageLayer.builder().path("new.png").build()));
        assertChange(p -> p.getFog().setEnabled(false));
        assertChange(p -> p.getFog().getMask().applyCells(new int[]{0}, true));
        assertChange(p -> p.getLighting().setTimeOfDayPreset("NIGHT"));
        assertChange(p -> p.getLighting().getLights().add(DmProject.LightSource.builder().id("torch").build()));
        assertChange(p -> p.getWalls().add(DmProject.WallSegment.builder().x2(100).build()));
        assertChange(p -> p.getInteractables().add(DmProject.Interactable.builder().state("open").build()));
        assertChange(p -> p.getOverlays().add(DmProject.OverlayShape.builder().type("circle").build()));
        assertChange(p -> p.getTextBoxes().add(DmProject.TextBox.builder().build()));
        assertChange(p -> p.setTextLayerVisible(false));
        assertChange(p -> p.getWeather().setType("rain"));
        DmProject before = project();
        assertTrue(PlayerViewTransition.contentChanged(before, Path.of("old.dmmap"),
                before.getViews().getPlayerCamera(), before, Path.of("new.dmmap"),
                before.getViews().getPlayerCamera()));
        assertFalse(PlayerViewTransition.contentChanged(before, Path.of("old.dmmap"),
                before.getViews().getPlayerCamera(), before, Path.of(".\\old.dmmap"),
                before.getViews().getPlayerCamera()));
    }

    @Test
    void ignoresHiddenFogAndTextAndDetectsReturningToOriginalState() throws Exception {
        DmProject before = project();
        before.getFog().setEnabled(false);
        before.setTextLayerVisible(false);
        DmProject after = new ProjectService().copy(before);
        after.getFog().setMask(new FogMask(500, 500, 20, 2, 2));
        after.getTextBoxes().add(DmProject.TextBox.builder().build());
        assertFalse(changed(before, after));
        after.getViews().getPlayerCamera().setX(100);
        assertTrue(changed(before, after));
        after.getViews().getPlayerCamera().setX(before.getViews().getPlayerCamera().getX());
        assertFalse(changed(before, after));
    }

    @Test
    void capturesCompositeWaitsForNewFrameAndHandlesInterruptedFadeAndCleanup() throws Exception {
        onFx(() -> {
            Canvas base = new Canvas(32, 32);
            base.getGraphicsContext2D().setFill(Color.RED);
            base.getGraphicsContext2D().fillRect(0, 0, 32, 32);
            Canvas fog = new Canvas(32, 32);
            fog.getGraphicsContext2D().setFill(Color.BLACK);
            fog.getGraphicsContext2D().fillRect(0, 0, 16, 32);
            StackPane root = new StackPane(base, fog);
            new Scene(root, 32, 32);
            root.resize(32, 32);
            root.layout();
            PlayerViewTransition transition = new PlayerViewTransition(root);
            ImageView outgoing = (ImageView) root.getChildren().getLast();
            var field = PlayerViewTransition.class.getDeclaredField("fade");
            field.setAccessible(true);
            FadeTransition fade = (FadeTransition) field.get(transition);
            transition.capture();
            assertTrue(outgoing.isVisible());
            assertTrue(outgoing.isMouseTransparent());
            assertEquals(Color.BLACK, outgoing.getImage().getPixelReader().getColor(4, 16));
            assertEquals(Color.RED, outgoing.getImage().getPixelReader().getColor(24, 16));
            assertEquals(Animation.Status.STOPPED, fade.getStatus());
            base.getGraphicsContext2D().setFill(Color.BLUE);
            base.getGraphicsContext2D().fillRect(0, 0, 32, 32);
            transition.frameRendered();
            assertEquals(Animation.Status.RUNNING, fade.getStatus());
            fade.jumpTo(Duration.seconds(0.2));
            assertEquals(0.5, outgoing.getOpacity(), 0.01);
            Color displayed = root.snapshot(null, null).getPixelReader().getColor(24, 16);
            transition.capture();
            assertEquals(displayed, outgoing.getImage().getPixelReader().getColor(24, 16));
            assertEquals(1, outgoing.getOpacity());
            transition.frameRendered();
            transition.cancel();
            assertFalse(outgoing.isVisible());
            assertNull(outgoing.getImage());
            assertEquals(Animation.Status.STOPPED, fade.getStatus());
            transition.frameRendered();
            assertEquals(Animation.Status.STOPPED, fade.getStatus());
            transition.capture();
            transition.frameRendered();
            fade.getOnFinished().handle(new javafx.event.ActionEvent());
            assertFalse(outgoing.isVisible());
            assertNull(outgoing.getImage());
        });
    }

    @Test
    void applicationTransitionsLiveSwitchesAndChangedUnfreezeButNotFrozenSwitches() throws Exception {
        onFx(() -> {
            System.setProperty(AppSettings.SYSTEM_PROPERTY, temp.resolve("settings.ini").toString());
            DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
            DmProject current = project();
            set(app, "project", current);
            StackPane root = new StackPane(new Canvas(32, 32));
            new Scene(root, 32, 32);
            root.resize(32, 32);
            root.layout();
            PlayerViewTransition transition = new PlayerViewTransition(root);
            set(app, "playerTransition", transition);
            ImageView outgoing = (ImageView) root.getChildren().getLast();
            freeze(app, true);
            freeze(app, false);
            assertFalse(outgoing.isVisible(), "Unchanged unfreeze should not transition");
            freeze(app, true);
            current.getViews().getDmCamera().setX(300);
            freeze(app, false);
            assertFalse(outgoing.isVisible(), "DM camera changes should not transition");
            freeze(app, true);
            current.getViews().getPlayerCamera().setX(300);
            freeze(app, false);
            assertTrue(outgoing.isVisible(), "Staged player viewport should transition");
            transition.cancel();
            freeze(app, true);
            var switchMap = DungeonMasterMapToolApplication.class.getDeclaredMethod(
                    "switchProject", DmProject.class, Path.class);
            switchMap.setAccessible(true);
            DmProject next = project();
            switchMap.invoke(app, next, temp.resolve("next.dmmap"));
            assertFalse(outgoing.isVisible(), "A frozen map switch must not reach the screen");
            freeze(app, false);
            assertTrue(outgoing.isVisible(), "Unfreezing onto another map should transition");
            transition.cancel();
            switchMap.invoke(app, project(), temp.resolve("another.dmmap"));
            assertTrue(outgoing.isVisible(), "Live map switches should transition");
            transition.cancel();
            set(app, "playerTransition", null);
            freeze(app, true);
            freeze(app, false);
        });
    }

    private static void set(DungeonMasterMapToolApplication app, String name, Object value) throws Exception {
        var field = DungeonMasterMapToolApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(app, value);
    }

    private static void freeze(DungeonMasterMapToolApplication app, boolean frozen) throws Exception {
        var method = DungeonMasterMapToolApplication.class.getDeclaredMethod("setPlayerFrozen", boolean.class);
        method.setAccessible(true);
        method.invoke(app, frozen);
    }

    private static DmProject project() {
        DmProject project = DmProject.builder().build();
        new dmmt.service.FogService().ensureMask(project);
        return project;
    }

    private static boolean changed(DmProject before, DmProject after) {
        return PlayerViewTransition.contentChanged(before, null, before.getViews().getPlayerCamera(),
                after, null, after.getViews().getPlayerCamera());
    }

    private static void assertChange(Consumer<DmProject> edit) throws Exception {
        DmProject before = project();
        DmProject after = new ProjectService().copy(before);
        edit.accept(after);
        assertTrue(changed(before, after));
    }

    private static void onFx(CheckedRunnable action) throws Exception {
        String original = System.getProperty(AppSettings.SYSTEM_PROPERTY);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                action.run();
            } finally {
                if (original == null) {
                    System.clearProperty(AppSettings.SYSTEM_PROPERTY);
                } else {
                    System.setProperty(AppSettings.SYSTEM_PROPERTY, original);
                }
                Tuning.apply(key -> null);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
