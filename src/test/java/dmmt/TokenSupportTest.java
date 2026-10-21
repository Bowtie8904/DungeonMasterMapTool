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
import javafx.scene.input.ScrollEvent;
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
    void resizingShowsLiveDiameterInTilesAndClearsTheRetainedLabelOnRelease() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            Canvas overlay = (Canvas)get(f.app, "dmFogCanvas");
            invoke(f.app, "renderDmPointerOverlay");
            assertEquals(0, pixel(overlay, 150, 100).getOpacity());
            f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, 200, 150, false, false));
            invoke(f.app, "renderDmPointerOverlay");
            assertTrue(pixel(overlay, 150, 100).getOpacity() > 0, "Label appears before the size changes");

            for (double size : new double[]{.5, 1, 2.5})
            {
                double x = 150 + size * 50, y = 100 + size * 50;
                f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, x, y, false, false));
                assertEquals(size, f.project.getTokens().getFirst().getSizeTiles());
                Canvas actual = new Canvas(400, 300), expected = new Canvas(400, 300);
                invoke(f.app, "drawToolPreview", actual.getGraphicsContext2D());
                String text = size == 1 ? "1 tiles" : size + " tiles";
                invoke(f.app, "drawSizeLabel", expected.getGraphicsContext2D(), text, 150.0, 100.0);
                int[] actualPixels = new int[400 * 300], expectedPixels = new int[400 * 300];
                actual.snapshot(transparent(), null).getPixelReader().getPixels(0, 0, 400, 300,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(), actualPixels, 0, 400);
                expected.snapshot(transparent(), null).getPixelReader().getPixels(0, 0, 400, 300,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(), expectedPixels, 0, 400);
                assertArrayEquals(expectedPixels, actualPixels, "Label must show diameter, not radius");
                invoke(f.app, "renderDmPointerOverlay");
            }

            f.canvas.getOnMouseReleased().handle(mouse(f.canvas, MouseEvent.MOUSE_RELEASED, 275, 225, false, false));
            invoke(f.app, "renderDmPointerOverlay");
            assertEquals(0, pixel(overlay, 150, 100).getOpacity(), "Release clears the cached label");
            f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, 150, 100, false, false));
            f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, 200, 150, false, false));
            invoke(f.app, "renderDmPointerOverlay");
            assertEquals(0, pixel(overlay, 200, 150).getOpacity(), "Moving does not show a size label");
            f.canvas.getOnMouseReleased().handle(mouse(f.canvas, MouseEvent.MOUSE_RELEASED, 200, 150, false, false));
        });
    }

    @Test
    void rotationHandleAndWheelAreSmoothUndoableAndDoNotMoveOrZoomTheToken() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            String id = f.project.getTokens().getFirst().getId();
            f.canvas.getOnMousePressed().handle(mouse(f.canvas, MouseEvent.MOUSE_PRESSED, 150, 26, false, false));
            assertTrue((boolean)get(f.app, "rotatingToken"), "Handle must not start a marquee or move");
            double angle = Math.toRadians(33.25);
            double x = 150 + 74 * Math.sin(angle), y = 100 - 74 * Math.cos(angle);
            f.canvas.getOnMouseDragged().handle(mouse(f.canvas, MouseEvent.MOUSE_DRAGGED, x, y, false, false));
            assertEquals(33.25, token(f, id).getRotationDeg(), 1e-9);
            assertEquals(150, token(f, id).getX());
            assertEquals(100, token(f, id).getY());
            assertEquals(1, token(f, id).getSizeTiles());
            f.canvas.getOnMouseReleased().handle(mouse(f.canvas, MouseEvent.MOUSE_RELEASED, x, y, false, false));
            assertFalse((boolean)get(f.app, "rotatingToken"));
            f.api.execute("tools.undo", Map.of());
            assertEquals(0, token(f, id).getRotationDeg());
            f.api.execute("tools.redo", Map.of());
            assertEquals(33.25, token(f, id).getRotationDeg(), 1e-9);
            var dmBefore = new ProjectService().copy(f.project).getViews().getDmCamera();
            var playerBefore = new ProjectService().copy(f.project).getViews().getPlayerCamera();
            ScrollEvent wheel = wheel(f.canvas, 150, 100, .8, false, false);
            f.canvas.getOnScroll().handle(wheel);
            assertTrue(wheel.isConsumed());
            assertEquals(33.45, token(f, id).getRotationDeg(), 1e-9, "High-resolution wheel deltas are not angle-snapped");
            assertEquals(dmBefore, f.project.getViews().getDmCamera());
            assertEquals(playerBefore, f.project.getViews().getPlayerCamera());
            f.api.execute("tools.undo", Map.of());
            assertEquals(33.25, token(f, id).getRotationDeg(), 1e-9);
            f.api.execute("tools.redo", Map.of());
            assertEquals(33.45, token(f, id).getRotationDeg(), 1e-9);
            f.canvas.getOnScroll().handle(wheel(f.canvas, 350, 250, 40, false, false));
            assertNotEquals(dmBefore.getZoom(), f.project.getViews().getDmCamera().getZoom(), "Wheel elsewhere still zooms");
            invoke(f.app, "copySelection");
            invoke(f.app, "pasteSelection");
            assertEquals(33.45, f.project.getTokens().getLast().getRotationDeg(), 1e-9);
        });
    }

    @Test
    void wheelRotationPreservesModifierShortcutsAndDoesNotInterruptOtherInteractions() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            invoke(f.app, "addToken", definition(), 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            var camera = f.project.getViews().getDmCamera();
            double zoom = camera.getZoom();
            for (String state : new String[]{"draggingToken", "resizingToken", "rotatingToken", "draggingGroup", "marqueeActive"})
            {
                set(f.app, state, true);
                f.canvas.getOnScroll().handle(wheel(f.canvas, 150, 100, 40, false, false));
                assertEquals(0, token.getRotationDeg(), state + " must block wheel rotation");
                set(f.app, state, false);
            }
            f.canvas.getOnScroll().handle(wheel(f.canvas, 150, 100, 40, true, false));
            f.canvas.getOnScroll().handle(wheel(f.canvas, 150, 100, 40, false, true));
            assertEquals(0, token.getRotationDeg());
            assertEquals(zoom, camera.getZoom());
            for (String state : new String[]{"pingArmed", "laserActive", "laserToolActive"})
            {
                set(f.app, state, true);
                f.canvas.getOnScroll().handle(wheel(f.canvas, 150, 100, 40, false, false));
                assertEquals(0, token.getRotationDeg(), state + " must not rotate a token");
                set(f.app, state, false);
            }
        });
    }

    @Test
    void rotatedSquareIsNotCulledWhileACornerIsStillOnScreen() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            var definition = definition();
            definition.setShape(DmProject.TokenShape.SQUARE);
            invoke(f.app, "addToken", definition, 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            token.setPlayerVisible(true);
            token.setX(-60);
            token.setRotationDeg(45);
            Canvas canvas = new Canvas(400, 300);
            var renderer = (CanvasMapRenderer)get(f.app, "renderer");
            renderer.renderTokenLayer(canvas.getGraphicsContext2D(), new CanvasMapRenderer.TokenLayerState(),
                    f.project, 400, 300, f.project.getViews().getDmCamera(), true);
            assertTrue(pixel(canvas, 3, 100).getOpacity() > 0, "Rotated corner remains visible beyond the unrotated bounds");
        });
    }

    @Test
    void rotatedSquareHitTestingResizeAndGroupBoundsUseItsLocalAxes() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            var definition = definition();
            definition.setShape(DmProject.TokenShape.SQUARE);
            invoke(f.app, "addToken", definition, 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            assertSame(token, invoke(f.app, "pickToken", 195.0, 145.0), "Square corners are selectable");
            token.setRotationDeg(45);
            assertNull(invoke(f.app, "pickToken", 195.0, 145.0), "Rotated-away corners are not selectable");
            assertSame(token, invoke(f.app, "pickToken", 215.0, 100.0), "Rotated square extends beyond its unrotated bounds");
            double[] bounds = (double[])invoke(f.app, "groupBounds", "token:" + token.getId());
            assertEquals(150 + 50 * Math.sqrt(2), bounds[2], 1e-9);
            var handle = dmmt.render.TokenGeometry.toWorld(token, 50, 50);
            var target = dmmt.render.TokenGeometry.toWorld(token, 100, 100);
            f.drag(handle.getX(), handle.getY(), target.getX(), target.getY(), false);
            assertEquals(2, token.getSizeTiles());
            assertEquals(45, token.getRotationDeg());
        });
    }

    @Test
    void artworkShapeBorderAndRotationInvalidateBothViewsAndPreserveTransparency() throws Exception
    {
        onFx(() -> {
            Fixture f = fixture();
            var definition = definition();
            Canvas source = new Canvas(100, 100);
            source.getGraphicsContext2D().setFill(Color.RED);
            source.getGraphicsContext2D().fillRect(0, 0, 50, 100);
            definition.setImage(TokenArt.storeImage(source.snapshot(transparent(), null)));
            definition.setBorder(false);
            definition.setShape(DmProject.TokenShape.SQUARE);
            invoke(f.app, "addToken", definition, 150.0, 100.0);
            var token = f.project.getTokens().getFirst();
            token.setPlayerVisible(true);
            var renderer = (CanvasMapRenderer)get(f.app, "renderer");
            var camera = f.project.getViews().getDmCamera();
            for (boolean player : new boolean[]{false, true})
            {
                Canvas canvas = new Canvas(400, 300);
                var state = new CanvasMapRenderer.TokenLayerState();
                renderer.renderTokenLayer(canvas.getGraphicsContext2D(), state, f.project, 400, 300, camera, player);
                assertEquals(Color.RED, pixel(canvas, 110, 60), "Square artwork includes corners");
                assertEquals(0, pixel(canvas, 180, 100).getOpacity(), "Borderless images have no added background");
                var frozen = new ProjectService().copy(f.project);
                token.setRotationDeg(90);
                assertTrue(PlayerViewTransition.contentChanged(frozen, null, camera, f.project, null, camera));
                renderer.renderTokenLayer(canvas.getGraphicsContext2D(), state, f.project, 400, 300, camera, player);
                assertEquals(Color.RED, pixel(canvas, 180, 70));
                assertEquals(0, pixel(canvas, 120, 130).getOpacity());
                token.setRotationDeg(0);
                var placedDefinition = f.project.getTokenDefinitions().get(definition.getId());
                placedDefinition.setShape(DmProject.TokenShape.CIRCLE);
                assertTrue(PlayerViewTransition.contentChanged(frozen, null, camera, f.project, null, camera));
                renderer.renderTokenLayer(canvas.getGraphicsContext2D(), state, f.project, 400, 300, camera, player);
                assertEquals(0, pixel(canvas, 110, 60).getOpacity(), "Circle clips artwork corners");
                frozen = new ProjectService().copy(f.project);
                placedDefinition.setBorder(true);
                assertTrue(PlayerViewTransition.contentChanged(frozen, null, camera, f.project, null, camera));
                renderer.renderTokenLayer(canvas.getGraphicsContext2D(), state, f.project, 400, 300, camera, player);
                assertTrue(pixel(canvas, 180, 100).getOpacity() > 0, "Border on restores the background");
                placedDefinition.setBorder(false);
                placedDefinition.setShape(DmProject.TokenShape.SQUARE);
            }
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

    private static ScrollEvent wheel(Canvas canvas, double x, double y, double delta, boolean ctrl, boolean alt)
    {
        return new ScrollEvent(ScrollEvent.SCROLL, x, y, x, y, false, ctrl, alt, false,
                false, false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(canvas, x, y));
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
