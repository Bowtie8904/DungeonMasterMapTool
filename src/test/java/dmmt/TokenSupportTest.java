package dmmt;

import dmmt.api.DmControlApi;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.render.CanvasMapRenderer;
import dmmt.render.TokenArt;
import dmmt.service.ProjectService;
import dmmt.ui.ControlVisibility;
import dmmt.ui.PlayerViewTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TokenSupportTest
{
    @TempDir java.nio.file.Path temp;

    @BeforeAll
    static void startFx() throws Exception
    {
        FxTestSupport.startJavaFx();
    }

    @Test
    void libraryTabsAreExclusiveAndSettingsAndApiControlTheRealTokenBrowser() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            set(f.app, "mapLibrary", new dmmt.service.MapLibraryService(temp.resolve("maps"), new ProjectService()));
            var sidebar = (javafx.scene.layout.VBox)invoke(f.app, "createLibrarySidebar");
            var maps = get(f.app, "mapBrowser");
            var tokens = get(f.app, "tokenBrowser");
            assertSame(maps, sidebar.getChildren().get(1));
            var visibility = (ControlVisibility)get(f.app, "dmControlVisibility");
            DmControlApi api = new DmControlApi(visibility);
            api.execute("tokens.browser", Map.of());
            assertSame(tokens, sidebar.getChildren().get(1));
            assertFalse(sidebar.getChildren().contains(maps));
            api.execute("tokens.browser", Map.of());
            assertSame(maps, sidebar.getChildren().get(1));
            api.execute("tokens.browser", Map.of());
            var settings = (dmmt.service.AppSettings)get(f.app, "preferences");
            settings.setHiddenSections(Set.of("tokens"));
            invoke(f.app, "applySectionVisibility");
            assertSame(maps, sidebar.getChildren().get(1));
            assertFalse(((javafx.scene.Node)get(f.app, "tokenBrowserToggle")).isManaged());
            settings.setHiddenSections(Set.of());
            settings.setHiddenControls(Set.of("tokens.create"));
            invoke(f.app, "applySectionVisibility");
            assertFalse(visibility.registeredNodes().get("tokens.create").getFirst().isVisible());
            assertEquals(6, api.describe().stream().filter(row -> row.get("id").toString().startsWith("tokens.")).count());
            assertTrue(api.keyImage("tokens.browser", null).length > 100);
        });
    }

    @Test
    void placementHalfSnapFreeMoveResizeAndRemovalAreUndoableAndPersist() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 121.0, 112.0);
            var placed = f.project.getTokens().getFirst();
            String id = placed.getId();
            assertEquals(100, placed.getX());
            assertEquals(100, placed.getY());
            assertFalse(placed.isPlayerVisible());
            assertFalse(placed.isNameVisible());
            assertTrue((boolean)invoke(f.app, "hasContent", f.project));
            f.drag(100, 100, 136, 157, false);
            assertEquals(150, token(f, id).getX());
            assertEquals(150, token(f, id).getY());
            f.api.execute("tools.undo", Map.of());
            assertEquals(100, token(f, id).getX());
            f.api.execute("tools.redo", Map.of());
            f.drag(150, 150, 163, 177, true);
            assertEquals(163, token(f, id).getX());
            assertEquals(177, token(f, id).getY());
            f.drag(213, 227, 283, 297, false);
            assertEquals(2.5, token(f, id).getSizeTiles());
            f.api.execute("tools.undo", Map.of());
            assertEquals(1, token(f, id).getSizeTiles());
            invoke(f.app, "removeToken", id);
            assertTrue(f.project.getTokens().isEmpty());
            f.api.execute("tools.undo", Map.of());
            assertEquals(1, f.project.getTokens().size());
            assertEquals(f.project.getTokens(), new ProjectService().copy(f.project).getTokens());
        });
    }

    @Test
    void ctrlSelectionGroupMovementMarqueeCopyPasteAndDeletionIncludeTokens() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 100.0, 100.0);
            String first = f.project.getTokens().getFirst().getId();
            invoke(f.app, "addToken", definition(), 250.0, 150.0);
            String second = f.project.getTokens().getLast().getId();
            f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, 100, 100, false, true));
            Set<?> group = (Set<?>)get(f.app, "groupKeys");
            assertEquals(2, group.size());
            f.drag(100, 100, 146, 163, false);
            assertEquals(150, token(f, first).getX());
            assertEquals(150, token(f, first).getY());
            assertEquals(300, token(f, second).getX());
            assertEquals(200, token(f, second).getY());
            f.api.execute("tools.undo", Map.of());
            assertEquals(100, token(f, first).getX());
            assertEquals(250, token(f, second).getX());
            assertTrue((boolean)invoke(f.app, "copySelection"));
            invoke(f.app, "pasteSelection");
            assertEquals(4, f.project.getTokens().size());
            assertEquals(4, f.project.getTokens().stream().map(DmProject.TokenInstance::getId).distinct().count());
            f.api.execute("tools.undo", Map.of());
            assertEquals(2, f.project.getTokens().size());
            invoke(f.app, "clearGroup");
            invoke(f.app, "clearSingleSelection");
            f.drag(20, 20, 350, 250, false);
            assertEquals(2, group.size());
            invoke(f.app, "deleteGroup");
            assertTrue(f.project.getTokens().isEmpty());
            f.api.execute("tools.undo", Map.of());
            assertEquals(2, f.project.getTokens().size());
        });
    }

    @Test
    void freelyPlacedTokenGroupsSnapAnAnchorWithoutChangingSpacingOnDragOrPaste() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 100.0, 100.0);
            invoke(f.app, "addToken", definition(), 250.0, 150.0);
            var first = f.project.getTokens().getFirst();
            var second = f.project.getTokens().getLast();
            first.setX(113);
            first.setY(117);
            second.setX(267);
            second.setY(171);
            f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, 113, 117, false, true));
            f.drag(113, 117, 147, 158, false);
            assertEquals(154, second.getX() - first.getX());
            assertEquals(54, second.getY() - first.getY());
            assertTrue(f.project.getTokens().stream().anyMatch(t -> t.getX() % 50 == 0 && t.getY() % 50 == 0));
            assertTrue((boolean)invoke(f.app, "copySelection"));
            invoke(f.app, "pasteSelection");
            assertEquals(4, f.project.getTokens().size());
            var pastedFirst = f.project.getTokens().get(2);
            var pastedSecond = f.project.getTokens().get(3);
            assertEquals(154, Math.abs(pastedSecond.getX() - pastedFirst.getX()));
            assertEquals(54, Math.abs(pastedSecond.getY() - pastedFirst.getY()));
        });
    }

    @Test
    void playerRenderingHidesTokensAndNamesAndInvalidatesVisibleArtworkButNotDmMetadata() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            token.setNameVisible(true);
            var renderer = (CanvasMapRenderer)get(f.app, "renderer");
            Canvas canvas = new Canvas(400, 300);
            Canvas base = new Canvas(400, 300);
            var state = new CanvasMapRenderer.TokenLayerState();
            var camera = f.project.getViews().getDmCamera();
            renderer.renderForegroundLayer(canvas.getGraphicsContext2D(), f.project, 400, 300, camera, true);
            assertFalse(canvas.isVisible());
            renderer.renderTokenLayer(base.getGraphicsContext2D(), state, f.project, 400, 300, camera, true);
            Color background = pixel(base, 150, 100);
            token.setPlayerVisible(true);
            renderer.renderForegroundLayer(canvas.getGraphicsContext2D(), f.project, 400, 300, camera, true);
            assertFalse(canvas.isVisible(), "Token artwork belongs below lighting, not on the foreground canvas");
            renderer.renderTokenLayer(base.getGraphicsContext2D(), state, f.project, 400, 300, camera, true);
            assertNotEquals(background, pixel(base, 150, 100));
            assertEquals(0, pixel(canvas, 150, 161).getOpacity(), "DM name tag never reaches players");
            f.project.getFog().setEnabled(false);
            long draws = (long)invoke(renderer, "playerTextRedrawCount");
            DmProject before = new ProjectService().copy(f.project);
            token.setNameVisible(false);
            f.project.getTokenDefinitions().get("hero").setName("Secret DM name");
            renderer.renderForegroundLayer(canvas.getGraphicsContext2D(), f.project, 400, 300, camera, true);
            assertEquals(draws, invoke(renderer, "playerTextRedrawCount"));
            assertFalse(PlayerViewTransition.contentChanged(before, null, camera, f.project, null, camera));
            Color old = pixel(base, 150, 100);
            f.project.getTokenDefinitions().get("hero").setColor("#EE2211");
            renderer.renderTokenLayer(base.getGraphicsContext2D(), state, f.project, 400, 300, camera, true);
            assertNotEquals(old, pixel(base, 150, 100));
            assertTrue(PlayerViewTransition.contentChanged(before, null, camera, f.project, null, camera));
            assertEquals("#3377CC", before.getTokenDefinitions().get("hero").getColor(), "freeze is a deep copy");
        });
    }

    @Test
    void movingTokensRedrawsOnlyTheirLayerAndDmMetadataDoesNotInvalidateArtwork() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            var renderer = (CanvasMapRenderer)get(f.app, "renderer");
            var camera = f.project.getViews().getDmCamera();
            Canvas base = new Canvas(400, 300), artwork = new Canvas(400, 300);
            var baseState = new CanvasMapRenderer.BaseLayerState();
            var tokenState = new CanvasMapRenderer.TokenLayerState();
            renderer.renderBase(base.getGraphicsContext2D(), baseState, f.project, null, 400, 300, camera);
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 400, 300, camera, false);
            long draws = (long)get(tokenState, "redrawCount");
            base.getGraphicsContext2D().setFill(Color.MAGENTA);
            base.getGraphicsContext2D().fillRect(0, 0, 10, 10);
            token.setX(250);
            renderer.renderBase(base.getGraphicsContext2D(), baseState, f.project, null, 400, 300, camera);
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 400, 300, camera, false);
            assertEquals(Color.MAGENTA, pixel(base, 5, 5), "Moving tokens does not redraw the map background");
            assertEquals(draws + 1, get(tokenState, "redrawCount"));
            assertEquals(0, pixel(artwork, 150, 100).getOpacity());
            assertTrue(pixel(artwork, 250, 100).getOpacity() > 0);
            token.setNameVisible(true);
            f.project.getTokenDefinitions().get("hero").setName("New name");
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 400, 300, camera, false);
            assertEquals(draws + 1, get(tokenState, "redrawCount"));
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 400, 300, camera, true);
            assertFalse(artwork.isVisible(), "Changing DM/player mode cannot expose a cached hidden token");
            token.setPlayerVisible(true);
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 400, 300, camera, true);
            assertTrue(artwork.isVisible());
            artwork.setWidth(450);
            renderer.renderTokenLayer(artwork.getGraphicsContext2D(), tokenState, f.project, 450, 300, camera, true);
            assertTrue(pixel(artwork, 275, 100).getOpacity() > 0, "Resized token canvas is repainted");
        });
    }

    @Test
    void decodedArtworkCacheStaysWithinItsPixelBudgetAndOriginalsAreNotCached() throws Exception
    {
        onFx(() -> {
            var cacheField = TokenArt.class.getDeclaredField("IMAGES");
            cacheField.setAccessible(true);
            var cache = (Map<?, ?>)cacheField.get(null);
            cache.clear();
            var bytesField = TokenArt.class.getDeclaredField("imageBytes");
            bytesField.setAccessible(true);
            bytesField.setLong(null, 0);
            Canvas canvas = new Canvas(40, 40);
            for (int i = 0; i < 140; i++)
            {
                WritableImage image = new WritableImage(256, 256);
                image.getPixelWriter().setArgb(0, 0, 0xFF000000 | i);
                var token = definition();
                token.setImage(TokenArt.storeImage(image));
                token.setSourceImage("unread original");
                TokenArt.draw(canvas.getGraphicsContext2D(), token, 20, 20, 36);
                assertTrue(bytesField.getLong(null) <= 32L * 1024 * 1024);
                assertFalse(cache.containsKey(token.getSourceImage()));
            }
            assertEquals(128, cache.size());
        });
    }

    @Test
    void tokenArtworkReceivesAmbientDarknessAndLocalLightWhileHiddenBadgesMatchText() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            var renderer = (CanvasMapRenderer)get(f.app, "renderer");
            var camera = f.project.getViews().getDmCamera();
            Canvas foreground = new Canvas(400, 300);
            renderer.renderForegroundLayer(foreground.getGraphicsContext2D(), f.project, 400, 300, camera, false);
            f.project.getTextBoxes().add(DmProject.TextBox.builder().id("hidden")
                                                          .x(100).y(50).width(100).height(100).playerVisible(false).build());
            Canvas textBadge = new Canvas(400, 300);
            invoke(renderer, "drawHiddenTextBadges", textBadge.getGraphicsContext2D(), f.project, 400.0, 300.0, camera);
            var textPixels = textBadge.snapshot(transparent(), null).getPixelReader();
            var tokenPixels = foreground.snapshot(transparent(), null).getPixelReader();
            for (int y = 89; y < 111; y++)
                for (int x = 139; x < 161; x++)
                    assertEquals(textPixels.getArgb(x, y), tokenPixels.getArgb(x, y));
            f.project.getTextBoxes().clear();
            token.setPlayerVisible(true);
            Canvas base = new Canvas(400, 300);
            Canvas ambient = new Canvas(400, 300);
            ambient.setBlendMode(javafx.scene.effect.BlendMode.MULTIPLY);
            Pane composite = new Pane(base, ambient);
            renderer.renderTokenLayer(base.getGraphicsContext2D(), new CanvasMapRenderer.TokenLayerState(),
                                      f.project, 400, 300, camera, true);
            double unshaded = pixel(base, 150, 100).getBrightness();
            f.project.getLighting().setTimeOfDayPreset("NIGHT");
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), f.project, 400, 300, camera, true);
            double dark = composite.snapshot(transparent(), null).getPixelReader().getColor(150, 100).getBrightness();
            assertTrue(dark < unshaded - .1, "Night darkness shades the token artwork");
            f.project.getLighting().getLights().add(DmProject.LightSource.builder().id("torch")
                                                                         .x(150).y(100).range(300).color("#FFFFFF").build());
            ((dmmt.lighting.LightingEngine)get(f.app, "lightingEngine")).update(f.project);
            renderer.renderAmbientLight(ambient.getGraphicsContext2D(), f.project, 400, 300, camera, true);
            double lit = composite.snapshot(transparent(), null).getPixelReader().getColor(150, 100).getBrightness();
            assertTrue(lit > dark + .1, "A nearby light illuminates the token");
        });
    }

    @Test
    void circularArtworkAndPanZoomCropHaveTransparentCornersAndKeepSourceForRealignment() throws Exception
    {
        onFx(() -> {
            WritableImage source = new WritableImage(200, 100);
            for (int y = 0; y < 100; y++)
                for (int x = 0; x < 200; x++)
                {
                    source.getPixelWriter().setColor(x, y, x < 100 ? Color.RED : Color.BLUE);
                }
            var token = definition();
            token.setSourceImage(TokenArt.storeImage(source));
            token.setZoom(2);
            token.setCenterX(.8);
            token.setCenterY(.9);
            TokenArt.constrain(token, source);
            assertEquals(.9, token.getCenterY());
            Canvas crop = new Canvas(100, 100);
            TokenArt.drawCrop(crop.getGraphicsContext2D(), source, token, 100);
            assertEquals(0, pixel(crop, 50, 99).getOpacity(), "Panning past the source edge leaves transparent space");
            token.setImage(TokenArt.storeImage(crop.snapshot(transparent(), null)));
            Canvas round = new Canvas(100, 100);
            TokenArt.draw(round.getGraphicsContext2D(), token, 50, 50, 96);
            assertEquals(Color.BLUE, pixel(round, 50, 50));
            assertEquals(0, pixel(round, 1, 1).getOpacity());
            assertEquals(200, TokenArt.decode(token.getSourceImage()).getWidth());
            assertThrows(IllegalArgumentException.class, () -> TokenArt.decode("not image data"));
        });
    }

    private static DmProject.TokenDefinition definition()
    {
        return DmProject.TokenDefinition.builder().id("hero").name("Hero").color("#3377CC").build();
    }

    private static DmProject.TokenInstance token(Fixture f, String id)
    {
        return f.project.getTokens().stream().filter(t -> t.getId().equals(id)).findFirst().orElseThrow();
    }

    private static Fixture fixture() throws Exception
    {
        DungeonMasterMapToolApplication app = new DungeonMasterMapToolApplication();
        DmProject project = new DmProject();
        project.getFog().setMask(new FogMask(0, 0, 10, 40, 30));
        project.getViews().getDmCamera().setX(200);
        project.getViews().getDmCamera().setY(150);
        set(app, "project", project);
        Canvas canvas = new Canvas(400, 300);
        set(app, "dmCanvas", canvas);
        for (String field : List.of("dmBaseCanvas", "dmBrightCoreCanvas", "dmAmbientCanvas", "dmFogCanvas",
                                    "dmFogMaskCanvas", "dmWeatherCanvas", "dmLightningCanvas", "dmForegroundCanvas"))
        {
            set(app, field, new Canvas(400, 300));
        }
        Stage owner = new Stage();
        var panel = (javafx.scene.Node)invoke(app, "createControlsPanel", owner);
        owner.close();
        var editor = new dmmt.ui.TextBoxEditor();
        set(app, "textEditor", editor);
        new Scene(new Pane(canvas, panel, editor.node()), 400, 300);
        invoke(app, "initTextEditor");
        invoke(app, "installDmInteractions");
        DmControlApi api = new DmControlApi((ControlVisibility)get(app, "dmControlVisibility"));
        return new Fixture(app, project, canvas, api);
    }

    private record Fixture(DungeonMasterMapToolApplication app, DmProject project, Canvas canvas, DmControlApi api)
    {
        void drag(double x, double y, double toX, double toY, boolean shift)
        {
            canvas.getOnMousePressed().handle(mouse(canvas, MouseEvent.MOUSE_PRESSED, x, y, shift, false));
            canvas.getOnMouseDragged().handle(mouse(canvas, MouseEvent.MOUSE_DRAGGED, toX, toY, shift, false));
            canvas.getOnMouseReleased().handle(mouse(canvas, MouseEvent.MOUSE_RELEASED, toX, toY, shift, false));
        }
    }

    private static MouseEvent mouse(Canvas canvas, javafx.event.EventType<MouseEvent> type,
                                    double x, double y, boolean shift, boolean ctrl)
    {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1, shift, ctrl, false, false,
                              true, false, false, false, false, true, new PickResult(canvas, x, y));
    }

    private static SnapshotParameters transparent()
    {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return parameters;
    }

    private static Color pixel(Canvas canvas, int x, int y)
    {
        return canvas.snapshot(transparent(), null).getPixelReader().getColor(x, y);
    }

    private static Object get(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception
    {
        Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                                        .filter(m -> m.getName().equals(name) && m.getParameterCount() == arguments.length).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void onFx(CheckedAction action) throws Exception
    {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private interface CheckedAction
    {
        void run() throws Exception;
    }
}
