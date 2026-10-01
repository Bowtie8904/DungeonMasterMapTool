package dmmt.render;

import dmmt.lighting.LightFlicker;
import dmmt.lighting.LightingEngine;
import dmmt.lighting.PolygonRaster;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.lighting.VisibilityService;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.service.FogService;
import dmmt.ui.Icons;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.Paint;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CanvasMapRenderer {
    /** Light map is computed at 1/LIGHT_MAP_SCALE of screen resolution and smoothed when scaled up. */
    private static final int LIGHT_MAP_SCALE = 4;
    private static int lightMapScale() {
        return PerformanceMode.isEnabled() ? PerformanceMode.LIGHT_MAP_SCALE : LIGHT_MAP_SCALE;
    }
    /** Screen-pixel height of the grab bar drawn above the player viewport rectangle in the DM view. */
    public static final double VIEWPORT_TITLE_BAR_HEIGHT = 22;
    private static final Font VIEWPORT_TITLE_FONT = Font.font("System", FontWeight.BOLD, 12);
    private static final double DM_FOG_ALPHA = 0.58;
    private static final double DM_DARKNESS_FACTOR = 0.65;
    private static final Color WALL_COLOR = Color.web("#ff2a2a", 0.9);
    public static final double MIN_LIGHT_TINT = 0.0;
    public static final double MAX_LIGHT_TINT = 0.3;
    public static final double DEFAULT_LIGHT_TINT = 0.08;

    /** Global strength of the light colour tint over lit areas; applies to every light in every project. */
    private static volatile double lightTint = DEFAULT_LIGHT_TINT;

    /** Frame rate of light flicker; the flicker clock advances in steps of 1/fps so it never runs faster than this. */
    private static volatile int animationFps = 30;

    /** Light flicker only runs when the map allows animations and performance mode is off. */
    public static boolean flickerOn(DmProject project) {
        return project.isEffectAnimations() && !PerformanceMode.isEnabled();
    }

    public static void setAnimationFps(int fps) {
        animationFps = Math.max(1, fps);
    }

    public static double getLightTint() {
        return lightTint;
    }

    public static void setLightTint(double value) {
        lightTint = Math.max(MIN_LIGHT_TINT, Math.min(MAX_LIGHT_TINT, value));
    }

    public static final double MAX_FOG_SOFTNESS = 1.0;
    public static final double DEFAULT_FOG_SOFTNESS = 0.3;
    /** Seconds a fog cell needs to fade completely in or out. */
    public static final double FOG_FADE_SECONDS = 0.5;
    /** Fade time when the change comes from a light, so moving lights do not trail behind. */
    public static final double FOG_LIGHT_FADE_SECONDS = 0.16;

    /** Width of the soft fog edge in grid tiles (0 = hard edge); applies to every project. */
    private static volatile double fogSoftness = DEFAULT_FOG_SOFTNESS;
    /** Whether fog fades when it is revealed or hidden; applies to every project. */
    private static volatile boolean fogFadeEnabled = true;

    public static double getFogSoftness() {
        return fogSoftness;
    }

    public static void setFogSoftness(double tiles) {
        fogSoftness = Math.max(0, Math.min(MAX_FOG_SOFTNESS, tiles));
    }

    public static boolean isFogFadeEnabled() {
        return fogFadeEnabled;
    }

    public static void setFogFadeEnabled(boolean enabled) {
        fogFadeEnabled = enabled;
    }

    private final ImagePyramidStore imageStore = ImagePyramidStore.shared();
    private final LightingEngine lightingEngine;
    private final LightBuffer dmLightBuffer = new LightBuffer();
    private final LightBuffer playerLightBuffer = new LightBuffer();

    private WritableImage fogImage;
    private FogMask fogImageMask;
    private long fogImageMaskVersion = -1;
    private long fogImageLiveVersion = -1;
    private int fogImageFactor = 1;
    private int fogImageRadius;
    private double fogImageOriginX;
    private double fogImageOriginY;
    private double fogImageCellSize;
    private float[] fogShown;
    private float[] fogTarget;
    private int[] fogPixels;
    private long fogLastAdvanceNanos;
    private boolean fogAnimating;
    private double fogFadeSeconds = FOG_FADE_SECONDS;
    private long fogLightChangesSeen;
    /** DM-only wall layer: wall lines, door/window lines and their icon badges. */
    private boolean wallLayerVisible = true;

    private final Text measureText = new Text();
    private final Map<Integer, Font> fontCache = new HashMap<>();
    private final Map<String, CachedTextLayout> textLayouts = new HashMap<>();
    /** DM view only: the box being edited in the in-place editor, which draws it itself. */
    private String editingTextBoxId;

    public CanvasMapRenderer(LightingEngine lightingEngine) {
        this.lightingEngine = lightingEngine;
    }

    public void setEditingTextBoxId(String id) {
        this.editingTextBoxId = id;
    }

    public boolean isWallLayerVisible() {
        return wallLayerVisible;
    }

    public void setWallLayerVisible(boolean wallLayerVisible) {
        this.wallLayerVisible = wallLayerVisible;
    }

    /** Remembers what a base canvas currently shows, so it is only redrawn when that changes. */
    public static final class BaseLayerState {
        private long signature;
        private long imageVersion;
        private boolean valid;
    }

    /**
     * Draws background, grid and map images on their own canvas below the animated one. Nothing here moves
     * with light flicker or effect animations, so the canvas is only redrawn when the view, the layers or
     * the loaded image detail change.
     */
    public void renderBase(
            GraphicsContext gc,
            BaseLayerState state,
            DmProject project,
            Path projectFile,
            double width,
            double height,
            DmProject.CameraState camera
    ) {
        long imageVersion = imageStore.changeVersion();
        long signature = baseSignature(project, projectFile, width, height, camera);
        if (state.valid && state.signature == signature && state.imageVersion == imageVersion) {
            return;
        }
        long profileStart = FrameProfiler.start();
        gc.setFill(Color.web("#202125"));
        gc.fillRect(0, 0, width, height);
        boolean complete = true;
        if (project == null) {
            gc.setFill(Color.web("#cccccc"));
            gc.fillText("Open or import a map to begin.", 20, 30);
        } else {
            drawGrid(gc, project, width, height, camera);
            complete = drawLayers(gc, project, projectFile, width, height, camera);
        }
        FrameProfiler.lap("base redraw", profileStart);
        state.signature = signature;
        state.imageVersion = imageVersion;
        // Detail tiles that are still loading need another pass once they arrive.
        state.valid = complete;
    }

    private static long baseSignature(DmProject project, Path projectFile, double width, double height,
                                      DmProject.CameraState camera) {
        long hash = 1125899906842597L;
        hash = 31 * hash + Double.hashCode(width);
        hash = 31 * hash + Double.hashCode(height);
        hash = 31 * hash + (PerformanceMode.isEnabled() ? 1 : 0);
        if (project == null) {
            return hash;
        }
        hash = 31 * hash + System.identityHashCode(project);
        hash = 31 * hash + (projectFile == null ? 0 : projectFile.hashCode());
        hash = 31 * hash + Double.hashCode(camera.getX());
        hash = 31 * hash + Double.hashCode(camera.getY());
        hash = 31 * hash + Double.hashCode(camera.getZoom());
        hash = 31 * hash + Double.hashCode(project.getMap().getGrid().getPixelsPerCell());
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            hash = 31 * hash + (layer.getPath() == null ? 0 : layer.getPath().hashCode());
            hash = 31 * hash + (layer.isVisible() ? 1 : 0);
            hash = 31 * hash + layer.getZIndex();
            hash = 31 * hash + Double.hashCode(layer.getX());
            hash = 31 * hash + Double.hashCode(layer.getY());
            hash = 31 * hash + Double.hashCode(layer.getWidth());
            hash = 31 * hash + Double.hashCode(layer.getHeight());
            hash = 31 * hash + Double.hashCode(layer.getRotationDeg());
        }
        return hash;
    }

    /** Draws everything that sits above the base canvas; the canvas itself stays transparent. */
    public void render(
            GraphicsContext gc,
            DmProject project,
            Path projectFile,
            double width,
            double height,
            DmProject.CameraState camera,
            boolean playerMode,
            WorldRect playerViewportWorld
    ) {
        gc.clearRect(0, 0, width, height);
        if (project == null) {
            return;
        }

        String who = playerMode ? "player " : "dm ";
        long t = FrameProfiler.start();
        drawOverlays(gc, project, width, height, camera, playerMode, false);
        t = FrameProfiler.lap(who + "effects", t);
        drawLighting(gc, project, width, height, camera, playerMode);
        t = FrameProfiler.lap(who + "lighting", t);
        drawOverlays(gc, project, width, height, camera, playerMode, true);
        if (!playerMode) {
            drawHiddenOverlayBadges(gc, project, width, height, camera);
        }
        t = FrameProfiler.lap(who + "effects", t);
        drawWeather(gc, project, width, height);
        t = FrameProfiler.lap(who + "weather", t);
        if (project.isTextLayerVisible()) {
            drawTextBoxes(gc, project, width, height, camera, playerMode);
        }
        t = FrameProfiler.lap(who + "text", t);
        if (!playerMode && wallLayerVisible) {
            drawWalls(gc, project.getWalls(), width, height, camera);
            drawInteractables(gc, project.getInteractables(), width, height, camera);
        }
        FrameProfiler.lap(who + "walls", t);
    }

    /**
     * Renders fog on a separate transparent canvas stacked above the map canvas, then pings
     * and (DM only) light tokens and the player viewport rectangle on top.
     */
    public void renderFogLayer(
            GraphicsContext gc,
            DmProject project,
            double width,
            double height,
            DmProject.CameraState camera,
            boolean playerMode,
            WorldRect playerViewportWorld,
            String selectedLightId,
            String hoveredInteractableId
    ) {
        gc.clearRect(0, 0, width, height);
        if (project == null) {
            return;
        }
        String who = playerMode ? "player " : "dm ";
        long t = FrameProfiler.start();
        drawFog(gc, project, width, height, camera, playerMode);
        FrameProfiler.lap(who + "fog", t);
        drawPings(gc, project, width, height, camera);
        if (!playerMode) {
            drawLightTokens(gc, project, width, height, camera, selectedLightId);
            if (wallLayerVisible) {
                drawInteractableBadges(gc, project.getInteractables(), width, height, camera, hoveredInteractableId);
            }
            if (playerViewportWorld != null) {
                drawViewportRect(gc, playerViewportWorld, width, height, camera);
            }
        }
    }

    private void drawWeather(GraphicsContext gc, DmProject project, double width, double height) {
        DmProject.WeatherState weather = project.getWeather();
        WeatherType type = weather == null ? WeatherType.NONE : WeatherType.from(weather.getType());
        if (type == WeatherType.NONE) {
            return;
        }
        double seconds = project.isEffectAnimations() ? System.nanoTime() / 1_000_000_000.0 : 0;
        if (PerformanceMode.isEnabled()) {
            seconds = Math.floor(seconds * PerformanceMode.TEXTURE_ANIMATION_FPS) / PerformanceMode.TEXTURE_ANIMATION_FPS;
        }
        WeatherEffects.draw(gc, type, weather.getIntensity(), width, height, seconds, PerformanceMode.isEnabled());
    }

    private void drawGrid(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera) {
        double cell = Math.max(5.0, project.getMap().getGrid().getPixelsPerCell());
        gc.setStroke(Color.color(1, 1, 1, 0.08));
        gc.setLineWidth(1);

        WorldPoint topLeft = screenToWorld(0, 0, width, height, camera);
        WorldPoint bottomRight = screenToWorld(width, height, width, height, camera);

        double startX = Math.floor(topLeft.x / cell) * cell;
        double endX = Math.ceil(bottomRight.x / cell) * cell;
        for (double x = startX; x <= endX; x += cell) {
            double sx = worldToScreenX(x, width, camera);
            gc.strokeLine(sx, 0, sx, height);
        }

        double startY = Math.floor(topLeft.y / cell) * cell;
        double endY = Math.ceil(bottomRight.y / cell) * cell;
        for (double y = startY; y <= endY; y += cell) {
            double sy = worldToScreenY(y, height, camera);
            gc.strokeLine(0, sy, width, sy);
        }
    }

    /** Returns false if some detail tiles were not available yet. */
    private boolean drawLayers(GraphicsContext gc, DmProject project, Path projectFile, double width, double height, DmProject.CameraState camera) {
        boolean complete = true;
        List<DmProject.ImageLayer> sorted = project.getImageLayers().stream()
                .filter(DmProject.ImageLayer::isVisible)
                .sorted((a, b) -> Integer.compare(a.getZIndex(), b.getZIndex()))
                .toList();

        for (DmProject.ImageLayer layer : sorted) {
            ImagePyramidStore.MapImage image = resolveImage(layer.getPath(), projectFile);
            if (image == null) {
                continue;
            }
            double sx = worldToScreenX(layer.getX(), width, camera);
            double sy = worldToScreenY(layer.getY(), height, camera);
            double sw = layer.getWidth() * camera.getZoom();
            double sh = layer.getHeight() * camera.getZoom();

            gc.save();
            double centerX = sx + sw / 2.0;
            double centerY = sy + sh / 2.0;
            gc.translate(centerX, centerY);
            gc.rotate(layer.getRotationDeg());
            complete &= image.draw(gc, -sw / 2.0, -sh / 2.0, sw, sh);
            gc.restore();
        }
        return complete;
    }

    private static final double HIDDEN_SHAPE_VISIBILITY = 0.35;

    /**
     * AOE shapes sit above the map and below fog. Shapes that do not emit light are drawn before the lighting
     * pass so darkness dims them; emitting shapes are drawn after it and stay fully bright.
     */
    private void drawOverlays(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera,
                              boolean playerMode, boolean emitting) {
        double zoom = camera.getZoom();
        brushOutlines.keySet().retainAll(project.getOverlays().stream().map(DmProject.OverlayShape::getId).toList());
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            if (playerMode && !shape.isPlayerVisible()) {
                continue;
            }
            if ((shape.isEmitsLight() && OverlayTextures.isAnimated(shape.getTexture())) != emitting) {
                continue;
            }
            boolean dmOnly = !playerMode && !shape.isPlayerVisible();
            Color base;
            try {
                base = Color.web(shape.getColor() == null ? "#55AA33" : shape.getColor());
            } catch (IllegalArgumentException ex) {
                base = Color.web("#55AA33");
            }
            double alpha = Math.max(0.05, Math.min(1.0, shape.getAlpha())) * (dmOnly ? HIDDEN_SHAPE_VISIBILITY : 1.0);
            Color fill = base.deriveColor(0, 1, 1, alpha);
            Color edge = base.deriveColor(0, 1, 1, Math.min(1.0, alpha + 0.35));
            gc.setFill(fill);
            gc.setStroke(edge);
            gc.setLineWidth(2);
            gc.setLineDashes(dmOnly ? new double[]{8, 6} : null);
            String type = shape.getType() == null ? "" : shape.getType();
            String texture = OverlayTextures.normalize(shape.getTexture());
            if (OverlayTextures.isAnimated(texture)) {
                drawTexturedShape(gc, project, shape, type, texture, base, edge, dmOnly ? HIDDEN_SHAPE_VISIBILITY : 1.0, width, height, camera);
                gc.setLineDashes(null);
                continue;
            }
            switch (type) {
                case "circle" -> {
                    double cx = worldToScreenX(shape.getX(), width, camera);
                    double cy = worldToScreenY(shape.getY(), height, camera);
                    double r = shape.getRadius() * zoom;
                    gc.fillOval(cx - r, cy - r, r * 2, r * 2);
                    gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
                }
                case "rect" -> {
                    double sx = worldToScreenX(shape.getX(), width, camera);
                    double sy = worldToScreenY(shape.getY(), height, camera);
                    gc.fillRect(sx, sy, shape.getWidth() * zoom, shape.getHeight() * zoom);
                    gc.strokeRect(sx, sy, shape.getWidth() * zoom, shape.getHeight() * zoom);
                }
                case "brush" -> {
                    drawBrushStroke(gc, shape, fill, width, height, camera);
                    if (shape.isBorder()) {
                        gc.setLineWidth(3);
                        strokeBrushOutline(gc, shape, width, height, camera);
                    }
                }
                case "pen" -> drawBrushStroke(gc, shape, edge, width, height, camera,
                        Math.max(2, shape.getStrokeWidth() * zoom));
                case "line" -> drawBrushStroke(gc, shape, fill, width, height, camera,
                        Math.max(2, shape.getStrokeWidth() * zoom));
                default -> {
                }
            }
            gc.setLineDashes(null);
        }
    }

    /** Small crossed-out eye on every shape that players cannot see, so hidden shapes stand out in the DM view. */
    private void drawHiddenOverlayBadges(GraphicsContext gc, DmProject project, double width, double height,
                                         DmProject.CameraState camera) {
        double r = 11;
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            if (shape.isPlayerVisible()) {
                continue;
            }
            double wx;
            double wy;
            String type = shape.getType() == null ? "" : shape.getType();
            if ("rect".equals(type)) {
                wx = shape.getX() + shape.getWidth() / 2.0;
                wy = shape.getY() + shape.getHeight() / 2.0;
            } else if ("circle".equals(type)) {
                wx = shape.getX();
                wy = shape.getY();
            } else if (shape.getPoints().size() >= 2) {
                List<Double> points = shape.getPoints();
                double minX = Double.MAX_VALUE;
                double minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < points.size(); i += 2) {
                    minX = Math.min(minX, points.get(i));
                    maxX = Math.max(maxX, points.get(i));
                    minY = Math.min(minY, points.get(i + 1));
                    maxY = Math.max(maxY, points.get(i + 1));
                }
                wx = (minX + maxX) / 2.0;
                wy = (minY + maxY) / 2.0;
            } else {
                continue;
            }
            double cx = worldToScreenX(wx, width, camera);
            double cy = worldToScreenY(wy, height, camera);
            if (cx < -r || cy < -r || cx > width + r || cy > height + r) {
                continue;
            }
            gc.setFill(Color.color(0.1, 0.1, 0.12, 0.8));
            gc.fillOval(cx - r, cy - r, r * 2, r * 2);
            Image glyph = Icons.image(MaterialDesignE.EYE_OFF_OUTLINE, 14, Color.web("#dddddd"));
            if (glyph != null) {
                gc.drawImage(glyph, Math.round(cx - glyph.getWidth() / 2.0), Math.round(cy - glyph.getHeight() / 2.0));
            }
        }
    }

    private static double pulseFactor(OverlayTextures.Layer layer, double seconds) {
        if (layer.pulse() <= 0) {
            return 1;
        }
        double wave = 0.5 - 0.5 * Math.cos(2 * Math.PI * layer.pulseHz() * seconds);
        return 1 - layer.pulse() * wave;
    }

    /** Scrolls the texture layers over the shape; each layer is a tiled ImagePattern anchored to world origin plus a drift. */
    private void drawTexturedShape(GraphicsContext gc, DmProject project, DmProject.OverlayShape shape, String type,
                                   String texture, Color base, Color edge, double visibility, double width, double height,
                                   DmProject.CameraState camera) {
        double zoom = camera.getZoom();
        double seconds = project.isEffectAnimations() ? System.nanoTime() / 1_000_000_000.0 : 0;
        if (PerformanceMode.isEnabled()) {
            seconds = Math.floor(seconds * PerformanceMode.TEXTURE_ANIMATION_FPS) / PerformanceMode.TEXTURE_ANIMATION_FPS;
        }
        double tileWorld = project.getMap().getGrid().getPixelsPerCell() * OverlayTextures.tileCells();
        int rgb = ((int) Math.round(base.getRed() * 255) << 16) | ((int) Math.round(base.getGreen() * 255) << 8)
                | (int) Math.round(base.getBlue() * 255);
        Image tile = OverlayTextures.image(texture, rgb);
        double alpha = Math.max(0.05, Math.min(1.0, shape.getAlpha())) * visibility;
        double originX = worldToScreenX(0, width, camera);
        double originY = worldToScreenY(0, height, camera);
        boolean soft = OverlayTextures.isSoft(texture);
        int passes = soft ? OverlayTextures.featherPasses() : 1;
        double ppc = project.getMap().getGrid().getPixelsPerCell();
        double featherWorld = switch (type) {
            case "circle" -> Math.min(OverlayTextures.featherCells() * ppc, 0.4 * shape.getRadius());
            case "rect" -> Math.min(OverlayTextures.featherCells() * ppc, 0.4 * Math.min(shape.getWidth(), shape.getHeight()));
            default -> Math.min(OverlayTextures.featherCells() * ppc, 0.4 * shape.getStrokeWidth());
        };
        double feather = featherWorld * zoom;
        boolean chasm = OverlayTextures.CHASM.equals(texture);
        gc.save();
        if (chasm && "brush".equals(type)) {
            double rimWorld = Math.min(OverlayTextures.featherCells() * 1.6 * ppc, 0.5 * shape.getStrokeWidth());
            OverlayTextures.Layer layer = OverlayTextures.layers(texture).get(0);
            double tileScreen = Math.max(8, tileWorld * layer.scale() * zoom);
            drawChasmBrush(gc, shape, new ImagePattern(tile, originX, originY, tileScreen, tileScreen, false),
                    rimWorld * zoom, alpha, width, height, camera);
            gc.restore();
            drawShapeBorder(gc, shape, type, edge, width, height, camera);
            return;
        }
        for (OverlayTextures.Layer layer : OverlayTextures.layers(texture)) {
            double tileScreen = Math.max(8, tileWorld * layer.scale() * zoom);
            double phaseX = ((seconds * layer.vx()) % 1.0 + 1.0) % 1.0;
            double phaseY = ((seconds * layer.vy()) % 1.0 + 1.0) % 1.0;
            ImagePattern pattern = new ImagePattern(tile, originX + phaseX * tileScreen, originY + phaseY * tileScreen,
                    tileScreen, tileScreen, false);
            double layerAlpha = alpha * layer.alpha() * pulseFactor(layer, seconds);
            gc.setFill(pattern);
            gc.setStroke(pattern);
            // Soft textures are painted in several progressively inset passes whose combined alpha ramps up
            // towards the middle, so the edge fades out instead of ending in a hard cut.
            double previous = 0;
            for (int pass = 0; pass < passes; pass++) {
                double cumulative = soft ? Math.min(0.999, layerAlpha * smooth((pass + 1.0) / passes)) : layerAlpha;
                gc.setGlobalAlpha(soft ? 1 - (1 - cumulative) / (1 - previous) : layerAlpha);
                previous = cumulative;
                fillShapeInset(gc, shape, type, pattern, feather * pass / passes, width, height, camera);
            }
        }
        if (chasm) {
            // The shape is a hole: only a rim of broken earth remains at its edge and the rim breaks up into a
            // black void towards the middle (inset passes whose combined alpha ramps from 0 at the edge to 1).
            double rimWorld = switch (type) {
                case "circle" -> Math.min(OverlayTextures.featherCells() * 1.6 * ppc, 0.5 * shape.getRadius());
                case "rect" -> Math.min(OverlayTextures.featherCells() * 1.6 * ppc, 0.5 * Math.min(shape.getWidth(), shape.getHeight()));
                default -> Math.min(OverlayTextures.featherCells() * 1.6 * ppc, 0.45 * shape.getStrokeWidth());
            };
            double rim = rimWorld * zoom;
            int voidPasses = Math.max(passes, 12);
            double previous = 0;
            for (int pass = 0; pass < voidPasses; pass++) {
                double cumulative = Math.min(0.999, alpha * smooth((pass + 1.0) / voidPasses));
                gc.setGlobalAlpha(1 - (1 - cumulative) / (1 - previous));
                previous = cumulative;
                fillShapeInset(gc, shape, type, Color.web("#050403"), rim * pass / voidPasses, width, height, camera);
            }
        }
        gc.restore();
        drawShapeBorder(gc, shape, type, edge, width, height, camera);
    }

    private void drawShapeBorder(GraphicsContext gc, DmProject.OverlayShape shape, String type, Color edge, double width,
                                 double height, DmProject.CameraState camera) {
        double zoom = camera.getZoom();
        if (shape.isBorder()) {
            gc.save();
            gc.setStroke(edge);
            gc.setLineWidth(3);
            if ("circle".equals(type)) {
                double cx = worldToScreenX(shape.getX(), width, camera);
                double cy = worldToScreenY(shape.getY(), height, camera);
                double r = shape.getRadius() * zoom;
                gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            } else if ("rect".equals(type)) {
                gc.strokeRect(worldToScreenX(shape.getX(), width, camera), worldToScreenY(shape.getY(), height, camera),
                        shape.getWidth() * zoom, shape.getHeight() * zoom);
            } else if ("brush".equals(type)) {
                strokeBrushOutline(gc, shape, width, height, camera);
            }
            gc.restore();
        }
    }

    private void fillShapeInset(GraphicsContext gc, DmProject.OverlayShape shape, String type, Paint paint, double inset,
                                double width, double height, DmProject.CameraState camera) {
        double zoom = camera.getZoom();
        gc.setFill(paint);
        switch (type) {
            case "circle" -> {
                double cx = worldToScreenX(shape.getX(), width, camera);
                double cy = worldToScreenY(shape.getY(), height, camera);
                double r = Math.max(0.5, shape.getRadius() * zoom - inset);
                gc.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
            case "rect" -> {
                double w = shape.getWidth() * zoom - 2 * inset;
                double h = shape.getHeight() * zoom - 2 * inset;
                if (w > 0 && h > 0) {
                    gc.fillRect(worldToScreenX(shape.getX(), width, camera) + inset,
                            worldToScreenY(shape.getY(), height, camera) + inset, w, h);
                }
            }
            case "brush" -> drawBrushStroke(gc, shape, paint, width, height, camera,
                    Math.max(1, shape.getStrokeWidth() * zoom - 2 * inset));
            default -> {
            }
        }
    }

    /**
     * Draws a freehand chasm from the merged final area of the stroke: the area is clipped, filled with the black
     * void, and the earth rim is painted as boundary strokes of decreasing width, so the void depends on the distance
     * to the outline of the whole shape instead of on the path of the brush.
     */
    private void drawChasmBrush(GraphicsContext gc, DmProject.OverlayShape shape, ImagePattern earth, double rim,
                                double alpha, double width, double height, DmProject.CameraState camera) {
        BrushOutline outline = brushOutline(shape);
        if (outline == null || outline.polygons().isEmpty()) {
            return;
        }
        gc.beginPath();
        for (double[] polygon : outline.polygons()) {
            for (int i = 0; i + 1 < polygon.length; i += 2) {
                double sx = worldToScreenX(polygon[i], width, camera);
                double sy = worldToScreenY(polygon[i + 1], height, camera);
                if (i == 0) {
                    gc.moveTo(sx, sy);
                } else {
                    gc.lineTo(sx, sy);
                }
            }
            gc.closePath();
        }
        gc.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        gc.clip();
        gc.setGlobalAlpha(alpha);
        gc.setFill(Color.web("#050403"));
        gc.fillRect(0, 0, width, height);

        int passes = 12;
        gc.setStroke(earth);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        double previous = 0;
        for (int pass = 0; pass < passes; pass++) {
            double half = rim * (passes - pass) / passes;
            double mid = rim * (passes - pass - 0.5) / passes;
            double cumulative = Math.min(0.999, alpha * (1 - smooth(mid / rim)));
            gc.setGlobalAlpha(1 - (1 - cumulative) / (1 - previous));
            previous = cumulative;
            gc.setLineWidth(2 * half);
            for (double[] polygon : outline.polygons()) {
                gc.beginPath();
                for (int i = 0; i + 1 < polygon.length; i += 2) {
                    double sx = worldToScreenX(polygon[i], width, camera);
                    double sy = worldToScreenY(polygon[i + 1], height, camera);
                    if (i == 0) {
                        gc.moveTo(sx, sy);
                    } else {
                        gc.lineTo(sx, sy);
                    }
                }
                gc.closePath();
                gc.stroke();
            }
        }
        gc.setLineJoin(StrokeLineJoin.MITER);
    }

    private record BrushOutline(long key, List<double[]> polygons) {
    }

    private final Map<String, BrushOutline> brushOutlines = new HashMap<>();

    /**
     * Outlines the final area covered by a freehand stroke: the stroked path is merged into one area (so movement
     * inside the stroke and self-crossings leave no lines) and only its boundary is drawn. Cached in world coordinates.
     */
    private BrushOutline brushOutline(DmProject.OverlayShape shape) {
        List<Double> points = shape.getPoints();
        if (points.size() < 2) {
            return null;
        }
        long key = points.size() * 31L + Double.doubleToLongBits(shape.getStrokeWidth())
                + Double.doubleToLongBits(points.get(points.size() - 1)) * 17L;
        String id = shape.getId() == null ? "" : shape.getId();
        BrushOutline outline = brushOutlines.get(id);
        if (outline == null || outline.key() != key) {
            outline = new BrushOutline(key, computeBrushOutline(points, shape.getStrokeWidth()));
            brushOutlines.put(id, outline);
        }
        return outline;
    }

    private void strokeBrushOutline(GraphicsContext gc, DmProject.OverlayShape shape, double width, double height,
                                    DmProject.CameraState camera) {
        BrushOutline outline = brushOutline(shape);
        if (outline == null) {
            return;
        }
        for (double[] polygon : outline.polygons()) {
            gc.beginPath();
            for (int i = 0; i + 1 < polygon.length; i += 2) {
                double sx = worldToScreenX(polygon[i], width, camera);
                double sy = worldToScreenY(polygon[i + 1], height, camera);
                if (i == 0) {
                    gc.moveTo(sx, sy);
                } else {
                    gc.lineTo(sx, sy);
                }
            }
            gc.closePath();
            gc.stroke();
        }
    }

    private static List<double[]> computeBrushOutline(List<Double> points, double strokeWidth) {
        double minGap = Math.max(1, strokeWidth * 0.12);
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        double lastX = points.get(0);
        double lastY = points.get(1);
        path.moveTo(lastX, lastY);
        int count = points.size() / 2;
        for (int i = 1; i < count; i++) {
            double x = points.get(2 * i);
            double y = points.get(2 * i + 1);
            if (i == count - 1 || Math.hypot(x - lastX, y - lastY) >= minGap) {
                path.lineTo(x, y);
                lastX = x;
                lastY = y;
            }
        }
        if (count == 1) {
            path.lineTo(lastX + 0.01, lastY);
        }
        java.awt.Shape stroked = new java.awt.BasicStroke((float) Math.max(1, strokeWidth),
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND).createStrokedShape(path);
        java.awt.geom.Area area = new java.awt.geom.Area(stroked);
        List<double[]> polygons = new java.util.ArrayList<>();
        List<Double> current = new java.util.ArrayList<>();
        double[] coords = new double[6];
        java.awt.geom.PathIterator it = new java.awt.geom.FlatteningPathIterator(area.getPathIterator(null),
                Math.max(0.3, strokeWidth * 0.02));
        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO) {
                flushPolygon(current, polygons);
                current.add(coords[0]);
                current.add(coords[1]);
            } else if (type == java.awt.geom.PathIterator.SEG_LINETO) {
                current.add(coords[0]);
                current.add(coords[1]);
            } else if (type == java.awt.geom.PathIterator.SEG_CLOSE) {
                flushPolygon(current, polygons);
            }
            it.next();
        }
        flushPolygon(current, polygons);
        return polygons;
    }

    private static void flushPolygon(List<Double> current, List<double[]> polygons) {
        if (current.size() >= 6) {
            polygons.add(current.stream().mapToDouble(Double::doubleValue).toArray());
        }
        current.clear();
    }
    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    public static final double TEXT_BOX_PADDING = 12;
    public static final double TEXT_BOX_CORNER_RADIUS = 12;
    public static final double TEXT_BOX_BORDER_WIDTH = 6;

    private record CachedTextLayout(List<DmProject.TextRun> runs, double width, TextLayout.Result result) {
    }

    /** Text boxes sit above the map, lighting and effects but below fog, so fog still hides them from players. */
    private void drawTextBoxes(GraphicsContext gc, DmProject project, double width, double height,
                               DmProject.CameraState camera, boolean playerMode) {
        double zoom = camera.getZoom();
        textLayouts.keySet().retainAll(project.getTextBoxes().stream().map(DmProject.TextBox::getId).toList());
        TextLayout.Metrics metrics = new FxMetrics();
        for (DmProject.TextBox box : project.getTextBoxes()) {
            if (!playerMode && box.getId() != null && box.getId().equals(editingTextBoxId)) {
                continue;
            }
            double sx = worldToScreenX(box.getX(), width, camera);
            double sy = worldToScreenY(box.getY(), height, camera);
            double sw = box.getWidth() * zoom;
            double sh = box.getHeight() * zoom;
            if (sx > width || sy > height || sx + sw < 0 || sy + sh < 0) {
                continue;
            }
            double arc = Math.min(TEXT_BOX_CORNER_RADIUS, Math.min(box.getWidth(), box.getHeight()) / 2.0) * zoom * 2;
            Color background = parseColor(box.getBackgroundColor());
            Color border = parseColor(box.getBorderColor());
            double lineWidth = border.getOpacity() > 0 ? Math.max(1, TEXT_BOX_BORDER_WIDTH * zoom) : 0;
            // Fill and stroke share the border's center line so the fill never shows outside the border.
            double inset = lineWidth / 2;
            double innerArc = Math.max(0, arc - lineWidth);
            if (background.getOpacity() > 0) {
                gc.setFill(background);
                gc.fillRoundRect(sx + inset, sy + inset, sw - lineWidth, sh - lineWidth, innerArc, innerArc);
            }
            if (lineWidth > 0) {
                gc.setStroke(border);
                gc.setLineWidth(lineWidth);
                gc.strokeRoundRect(sx + inset, sy + inset, sw - lineWidth, sh - lineWidth, innerArc, innerArc);
            }
            double innerWidth = box.getWidth() - 2 * TEXT_BOX_PADDING;
            if (innerWidth <= 0) {
                continue;
            }
            TextLayout.Result layout = layoutFor(box, innerWidth, metrics);
            gc.save();
            gc.beginPath();
            gc.rect(sx, sy, sw, sh);
            gc.closePath();
            gc.clip();
            double innerHeight = box.getHeight() - 2 * TEXT_BOX_PADDING;
            double topOffset = Math.max(0, (innerHeight - layout.totalHeight()) / 2);
            gc.translate(sx + TEXT_BOX_PADDING * zoom, (sy + (TEXT_BOX_PADDING + topOffset) * zoom));
            gc.scale(zoom, zoom);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BASELINE);
            for (TextLayout.Line line : layout.lines()) {
                for (TextLayout.Fragment fragment : line.fragments()) {
                    gc.setFont(fontFor(fragment.fontSize()));
                    gc.setFill(parseColor(fragment.color()));
                    gc.fillText(fragment.text(), fragment.x(), line.y() + line.baseline());
                }
            }
            gc.restore();
        }
    }

    private TextLayout.Result layoutFor(DmProject.TextBox box, double innerWidth, TextLayout.Metrics metrics) {
        CachedTextLayout cached = textLayouts.get(box.getId());
        if (cached != null && cached.width == innerWidth && cached.runs.equals(box.getRuns())) {
            return cached.result;
        }
        List<DmProject.TextRun> snapshot = new java.util.ArrayList<>();
        for (DmProject.TextRun run : box.getRuns()) {
            snapshot.add(DmProject.TextRun.builder().text(run.getText()).fontSize(run.getFontSize()).color(run.getColor()).build());
        }
        TextLayout.Result result = TextLayout.layout(snapshot, innerWidth, metrics);
        textLayouts.put(box.getId(), new CachedTextLayout(snapshot, innerWidth, result));
        return result;
    }

    /**
     * Resizes an auto-size box to its text: wrapped at {@code maxWidth}, otherwise as wide as the longest line.
     * An empty box is one line of {@code emptyFontSize} high.
     */
    public void fitTextBox(DmProject.TextBox box, double maxWidth, int emptyFontSize) {
        TextLayout.Metrics metrics = new FxMetrics();
        List<DmProject.TextRun> runs = box.getRuns();
        int lastSize = runs.isEmpty() ? emptyFontSize : runs.get(runs.size() - 1).getFontSize();
        TextLayout.Result layout = TextLayout.layout(runs, Math.max(1, maxWidth - 2 * TEXT_BOX_PADDING), metrics);
        double textHeight = layout.totalHeight();
        String lastText = runs.isEmpty() ? "" : runs.get(runs.size() - 1).getText();
        if (textHeight == 0 || (lastText != null && lastText.endsWith("\n"))) {
            textHeight += metrics.lineHeight(lastSize);
        }
        // Trailing spaces count so the editor never wraps the caret onto a new line before the box has grown.
        double textWidth = layout.extentWidth() > 0 ? layout.extentWidth() : lastSize;
        box.setWidth(Math.ceil(Math.min(maxWidth, textWidth + 2 * TEXT_BOX_PADDING + 4)));
        box.setHeight(Math.ceil(textHeight + 2 * TEXT_BOX_PADDING));
    }

    private Font fontFor(int size) {
        return fontCache.computeIfAbsent(size, s -> Font.font(s));
    }

    /** Parses "#RRGGBB" / "#RRGGBBAA"; unreadable values become transparent. */
    public static Color parseColor(String value) {
        if (value == null || value.isBlank()) {
            return Color.TRANSPARENT;
        }
        try {
            return Color.web(value);
        } catch (IllegalArgumentException ex) {
            return Color.TRANSPARENT;
        }
    }

    private final class FxMetrics implements TextLayout.Metrics {
        @Override
        public double width(String text, int fontSize) {
            measureText.setFont(fontFor(fontSize));
            measureText.setText(text);
            return measureText.getLayoutBounds().getWidth();
        }

        @Override
        public double lineHeight(int fontSize) {
            measureText.setFont(fontFor(fontSize));
            measureText.setText("Ag");
            return measureText.getLayoutBounds().getHeight();
        }

        @Override
        public double ascent(int fontSize) {
            measureText.setFont(fontFor(fontSize));
            measureText.setText("Ag");
            return measureText.getBaselineOffset();
        }
    }
    private void drawBrushStroke(GraphicsContext gc, DmProject.OverlayShape shape, Paint fill, double width, double height, DmProject.CameraState camera) {
        drawBrushStroke(gc, shape, fill, width, height, camera, Math.max(1, shape.getStrokeWidth() * camera.getZoom()));
    }

    private void drawBrushStroke(GraphicsContext gc, DmProject.OverlayShape shape, Paint fill, double width, double height,
                                 DmProject.CameraState camera, double lineWidth) {
        List<Double> points = shape.getPoints();
        int n = points.size() / 2;
        if (n == 0) {
            return;
        }
        if (n == 1) {
            double cx = worldToScreenX(points.get(0), width, camera);
            double cy = worldToScreenY(points.get(1), height, camera);
            gc.fillOval(cx - lineWidth / 2, cy - lineWidth / 2, lineWidth, lineWidth);
            return;
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = worldToScreenX(points.get(2 * i), width, camera);
            ys[i] = worldToScreenY(points.get(2 * i + 1), height, camera);
        }
        // One stroked path keeps the alpha uniform where the stroke overlaps itself.
        gc.setStroke(fill);
        gc.setLineWidth(lineWidth);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.strokePolyline(xs, ys, n);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setLineJoin(StrokeLineJoin.MITER);
    }

    private void drawLighting(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera, boolean playerMode) {
        TimeOfDayPreset preset = TimeOfDayPreset.from(project.getLighting().getTimeOfDayPreset());
        double ambientBrightness = project.getLighting().ambientBrightnessFor(preset.name());
        double darkness = preset.darkness(ambientBrightness) * (playerMode ? 1.0 : DM_DARKNESS_FACTOR);
        if (darkness < 0.01) {
            return;
        }
        int bw = Math.max(1, (int) Math.ceil(width / lightMapScale()));
        int bh = Math.max(1, (int) Math.ceil(height / lightMapScale()));
        LightBuffer buffer = playerMode ? playerLightBuffer : dmLightBuffer;
        buffer.ensureSize(bw, bh);

        List<DmProject.LightSource> lights = project.getLighting().getLights();
        int fps = animationFps;
        long now = (long) (Math.floor(System.currentTimeMillis() * fps / 1000.0) * 1000.0 / fps);
        double zoom = camera.getZoom();
        double[] flickers = new double[lights.size()];
        // Rasterising the light map is the expensive part, so it is redone only when one of its inputs changed.
        long key = 1125899906842597L;
        key = 31 * key + Double.hashCode(darkness);
        key = 31 * key + lightMapScale();
        key = 31 * key + Double.hashCode(lightTint);
        key = 31 * key + preset.name().hashCode();
        key = 31 * key + Double.hashCode(ambientBrightness);
        key = 31 * key + Double.hashCode(camera.getX());
        key = 31 * key + Double.hashCode(camera.getY());
        key = 31 * key + Double.hashCode(zoom);
        for (int li = 0; li < lights.size(); li++) {
            DmProject.LightSource light = lights.get(li);
            boolean relevant = light.isEnabled() && lightRangeTouchesScreen(light, width, height, camera);
            key = 31 * key + (relevant ? 1 : 0);
            if (!relevant) {
                continue;
            }
            flickers[li] = flickerOn(project) ? LightFlicker.amount(light, now) : 0;
            key = 31 * key + Double.hashCode(flickers[li]);
            key = 31 * key + Double.hashCode(light.getRange());
            key = 31 * key + Double.hashCode(light.getIntensity());
            key = 31 * key + (light.getColor() == null ? 0 : light.getColor().hashCode());
            key = 31 * key + System.identityHashCode(lightingEngine.polygonFor(light));
        }
        List<DmProject.OverlayShape> emitters = new java.util.ArrayList<>();
        List<Double> emitterFlickers = new java.util.ArrayList<>();
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            if (shape.isEmitsLight() && OverlayTextures.isAnimated(shape.getTexture())
                    && (!playerMode || shape.isPlayerVisible())) {
                String texture = OverlayTextures.normalize(shape.getTexture());
                double flicker = flickerOn(project)
                        ? LightFlicker.amount(shape.getId(), OverlayTextures.lightFlicker(texture),
                        OverlayTextures.lightFlickerSpeed(texture), now)
                        : 0;
                emitters.add(shape);
                emitterFlickers.add(flicker);
                key = 31 * key + shapeLightKey(shape);
                key = 31 * key + Double.hashCode(flicker);
            }
        }
        if (buffer.valid && buffer.key == key) {
            gc.setImageSmoothing(true);
            gc.drawImage(buffer.image, 0, 0, bw, bh, 0, 0, bw * (double) lightMapScale(), bh * (double) lightMapScale());
            return;
        }
        buffer.key = key;
        buffer.valid = true;
        Arrays.fill(buffer.lit, 0f);

        int[] lightRgb = new int[lights.size() + emitters.size()];
        for (int li = 0; li < lights.size(); li++) {
            DmProject.LightSource light = lights.get(li);
            lightRgb[li] = parseRgb(light.getColor());
            if (!light.isEnabled() || !lightRangeTouchesScreen(light, width, height, camera)) {
                continue;
            }
            double flicker = flickers[li];
            double radius = light.getRange() * zoom * (1.0 - 0.25 * flicker) / lightMapScale();
            if (radius < 0.5) {
                continue;
            }
            double lx = worldToScreenX(light.getX(), width, camera) / lightMapScale();
            double ly = worldToScreenY(light.getY(), height, camera) / lightMapScale();
            if (lx + radius < 0 || ly + radius < 0 || lx - radius > bw || ly - radius > bh) {
                continue;
            }
            double brightness = (0.35 + 0.65 * Math.min(1.0, Math.max(0, light.getIntensity()))) * (1.0 - 0.6 * flicker);
            VisibilityService.Polygon polygon = lightingEngine.polygonFor(light);
            double[] xs = new double[polygon.size()];
            double[] ys = new double[polygon.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = worldToScreenX(polygon.xs()[i], width, camera) / lightMapScale();
                ys[i] = worldToScreenY(polygon.ys()[i], height, camera) / lightMapScale();
            }
            final int lightIndex = li;
            final float[] lit = buffer.lit;
            final int[] source = buffer.source;
            PolygonRaster.fill(xs, ys, bw, bh, (row, colStart, colEnd) -> {
                double dy = row + 0.5 - ly;
                int base = row * bw;
                for (int col = colStart; col <= colEnd; col++) {
                    double dx = col + 0.5 - lx;
                    double d = Math.sqrt(dx * dx + dy * dy) / radius;
                    if (d >= 1.0) {
                        continue;
                    }
                    float value = (float) (falloff(d) * brightness);
                    int idx = base + col;
                    if (value > lit[idx]) {
                        lit[idx] = value;
                        source[idx] = lightIndex;
                    }
                }
            });
        }

        double ppc = project.getMap().getGrid().getPixelsPerCell();
        for (int ei = 0; ei < emitters.size(); ei++) {
            DmProject.OverlayShape shape = emitters.get(ei);
            int index = lights.size() + ei;
            lightRgb[index] = parseRgb(shape.getColor());
            emitShapeLight(buffer, shape, index, ppc, emitterFlickers.get(ei), bw, bh, width, height, camera);
        }

        double glowStrength = lightTint * Math.sqrt(darkness);
        double ambR = preset.red() * 255;
        double ambG = preset.green() * 255;
        double ambB = preset.blue() * 255;
        int[] argb = buffer.argb;
        for (int i = 0; i < argb.length; i++) {
            double l = buffer.lit[i];
            double dark = darkness * (1.0 - l);
            double glow = l > 0 ? glowStrength * l : 0;
            double a = Math.min(1.0, dark + glow);
            if (a <= 0.002) {
                argb[i] = 0;
                continue;
            }
            int rgb = l > 0 ? lightRgb[buffer.source[i]] : 0;
            double r = (ambR * dark + ((rgb >> 16) & 0xFF) * glow) / (dark + glow);
            double g = (ambG * dark + ((rgb >> 8) & 0xFF) * glow) / (dark + glow);
            double b = (ambB * dark + (rgb & 0xFF) * glow) / (dark + glow);
            argb[i] = ((int) Math.round(a * 255) << 24)
                    | ((int) Math.round(r) << 16)
                    | ((int) Math.round(g) << 8)
                    | (int) Math.round(b);
        }
        buffer.image.getPixelWriter().setPixels(0, 0, bw, bh, PixelFormat.getIntArgbInstance(), argb, 0, bw);
        gc.setImageSmoothing(true);
        gc.drawImage(buffer.image, 0, 0, bw, bh, 0, 0, bw * (double) lightMapScale(), bh * (double) lightMapScale());
    }

    private static long shapeLightKey(DmProject.OverlayShape shape) {
        String texture = OverlayTextures.normalize(shape.getTexture());
        long h = Objects.hash(shape.getId(), shape.getType(), shape.getX(), shape.getY(), shape.getWidth(),
                shape.getHeight(), shape.getRadius(), shape.getStrokeWidth(), shape.getColor(),
                OverlayTextures.lightStrength(texture), OverlayTextures.lightRangeCells(texture),
                OverlayTextures.lightFlicker(texture), OverlayTextures.lightFlickerSpeed(texture));
        List<Double> points = shape.getPoints();
        h = 31 * h + points.size();
        if (!points.isEmpty()) {
            h = 31 * h + Double.hashCode(points.get(points.size() - 1));
            h = 31 * h + Double.hashCode(points.get(points.size() - 2));
        }
        return h;
    }

    /**
     * Adds the glow of one emissive effect to the light map: full strength inside the shape, fading to zero over
     * the texture's light range beyond its edge. Walls do not block this light.
     */
    private void emitShapeLight(LightBuffer buffer, DmProject.OverlayShape shape, int sourceIndex, double ppc,
                                double flicker, int bw, int bh, double width, double height, DmProject.CameraState camera) {
        String texture = OverlayTextures.normalize(shape.getTexture());
        double strength = OverlayTextures.lightStrength(texture) * (1.0 - 0.6 * flicker);
        double scale = camera.getZoom() / lightMapScale();
        double glow = Math.max(0.001, OverlayTextures.lightRangeCells(texture) * ppc * scale * (1.0 - 0.25 * flicker));
        if (strength <= 0) {
            return;
        }
        String type = shape.getType() == null ? "" : shape.getType();
        double[] px = null;
        double[] py = null;
        double x0;
        double y0;
        double x1;
        double y1;
        double inner = 0;
        switch (type) {
            case "circle" -> {
                double cx = worldToScreenX(shape.getX(), width, camera) / lightMapScale();
                double cy = worldToScreenY(shape.getY(), height, camera) / lightMapScale();
                double r = shape.getRadius() * scale;
                x0 = cx - r;
                x1 = cx + r;
                y0 = cy - r;
                y1 = cy + r;
                px = new double[]{cx};
                py = new double[]{cy};
                inner = r;
            }
            case "rect" -> {
                x0 = worldToScreenX(shape.getX(), width, camera) / lightMapScale();
                y0 = worldToScreenY(shape.getY(), height, camera) / lightMapScale();
                x1 = x0 + shape.getWidth() * scale;
                y1 = y0 + shape.getHeight() * scale;
            }
            case "brush" -> {
                List<Double> points = shape.getPoints();
                int n = points.size() / 2;
                if (n == 0) {
                    return;
                }
                int step = Math.max(1, (int) Math.ceil((n - 1) / 32.0));
                int count = (int) Math.ceil((n - 1) / (double) step) + 1;
                px = new double[count];
                py = new double[count];
                for (int i = 0; i < count; i++) {
                    int src = Math.min(n - 1, i * step);
                    px[i] = worldToScreenX(points.get(2 * src), width, camera) / lightMapScale();
                    py[i] = worldToScreenY(points.get(2 * src + 1), height, camera) / lightMapScale();
                }
                inner = Math.max(0.5, shape.getStrokeWidth() * scale / 2);
                x0 = Double.MAX_VALUE;
                y0 = Double.MAX_VALUE;
                x1 = -Double.MAX_VALUE;
                y1 = -Double.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    x0 = Math.min(x0, px[i] - inner);
                    x1 = Math.max(x1, px[i] + inner);
                    y0 = Math.min(y0, py[i] - inner);
                    y1 = Math.max(y1, py[i] + inner);
                }
            }
            default -> {
                return;
            }
        }
        int colStart = Math.max(0, (int) Math.floor(x0 - glow));
        int colEnd = Math.min(bw - 1, (int) Math.ceil(x1 + glow));
        int rowStart = Math.max(0, (int) Math.floor(y0 - glow));
        int rowEnd = Math.min(bh - 1, (int) Math.ceil(y1 + glow));
        for (int row = rowStart; row <= rowEnd; row++) {
            double y = row + 0.5;
            for (int col = colStart; col <= colEnd; col++) {
                double x = col + 0.5;
                double d;
                if (px == null) {
                    double dx = Math.max(Math.max(x0 - x, 0), x - x1);
                    double dy = Math.max(Math.max(y0 - y, 0), y - y1);
                    d = Math.hypot(dx, dy);
                } else if (px.length == 1) {
                    d = Math.max(0, Math.hypot(x - px[0], y - py[0]) - inner);
                } else {
                    double best = Double.MAX_VALUE;
                    for (int i = 0; i + 1 < px.length; i++) {
                        best = Math.min(best, segmentDistance(x, y, px[i], py[i], px[i + 1], py[i + 1]));
                    }
                    d = Math.max(0, best - inner);
                }
                if (d >= glow) {
                    continue;
                }
                double t = d / glow;
                float value = (float) (strength * (1 - t * t * (3 - 2 * t)));
                int idx = row * bw + col;
                if (value > buffer.lit[idx]) {
                    buffer.lit[idx] = value;
                    buffer.source[idx] = sourceIndex;
                }
            }
        }
    }

    private static double segmentDistance(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2));
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    /**
     * True if the light's full range circle overlaps the canvas. Flicker only shrinks the radius, so a light that
     * fails this test can never contribute to the light map.
     */
    private boolean lightRangeTouchesScreen(DmProject.LightSource light, double width, double height, DmProject.CameraState camera) {
        double radius = light.getRange() * camera.getZoom();
        double cx = worldToScreenX(light.getX(), width, camera);
        double cy = worldToScreenY(light.getY(), height, camera);
        double dx = cx - Math.max(0, Math.min(width, cx));
        double dy = cy - Math.max(0, Math.min(height, cy));
        return dx * dx + dy * dy <= radius * radius;
    }

    /** Full brightness in the inner half of the radius, smooth fade to zero at the edge. */
    private static double falloff(double normalizedDistance) {
        if (normalizedDistance <= 0.5) {
            return 1.0;
        }
        double t = (normalizedDistance - 0.5) / 0.5;
        return 1.0 - t * t * (3 - 2 * t);
    }

    private static int parseRgb(String hex) {
        try {
            Color c = Color.web(hex == null ? "#FFD9A0" : hex);
            return ((int) Math.round(c.getRed() * 255) << 16)
                    | ((int) Math.round(c.getGreen() * 255) << 8)
                    | (int) Math.round(c.getBlue() * 255);
        } catch (IllegalArgumentException ex) {
            return 0xFFD9A0;
        }
    }

    private void drawLightTokens(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera, String selectedLightId) {
        for (DmProject.LightSource light : project.getLighting().getLights()) {
            double x = worldToScreenX(light.getX(), width, camera);
            double y = worldToScreenY(light.getY(), height, camera);
            boolean selected = light.getId() != null && light.getId().equals(selectedLightId);
            boolean mapLamp = light.getRevealMode() == DmProject.RevealMode.NONE;
            if (selected) {
                double radius = light.getRange() * camera.getZoom();
                gc.setStroke(Color.color(1, 0.9, 0.3, 0.8));
                gc.setLineWidth(1.5);
                gc.setLineDashes(8, 6);
                gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
                gc.setLineDashes();
            }
            double r = mapLamp ? 5 : 8;
            Color color;
            try {
                color = Color.web(light.getColor() == null ? "#FFD9A0" : light.getColor());
            } catch (IllegalArgumentException ex) {
                color = Color.ORANGE;
            }
            if (!light.isEnabled()) {
                gc.setFill(Color.color(0.15, 0.15, 0.15, 0.75));
                gc.fillOval(x - r, y - r, r * 2, r * 2);
                gc.setStroke(color.deriveColor(0, 1, 1, 0.9));
                gc.setLineWidth(selected ? 2.5 : 1.5);
                gc.strokeOval(x - r, y - r, r * 2, r * 2);
                continue;
            }
            gc.setFill(mapLamp ? color.deriveColor(0, 1, 1, 0.7) : color);
            gc.fillOval(x - r, y - r, r * 2, r * 2);
            gc.setStroke(selected ? Color.YELLOW : Color.color(0, 0, 0, 0.8));
            gc.setLineWidth(selected ? 2.5 : 1.5);
            gc.strokeOval(x - r, y - r, r * 2, r * 2);
            if (!mapLamp) {
                gc.setFill(Color.color(0, 0, 0, 0.8));
                gc.fillOval(x - 2, y - 2, 4, 4);
            }
        }
    }

    private void drawWalls(GraphicsContext gc, List<DmProject.WallSegment> walls, double width, double height, DmProject.CameraState camera) {
        gc.setStroke(WALL_COLOR);
        gc.setLineWidth(2);
        for (DmProject.WallSegment wall : walls) {
            gc.strokeLine(
                    worldToScreenX(wall.getX1(), width, camera),
                    worldToScreenY(wall.getY1(), height, camera),
                    worldToScreenX(wall.getX2(), width, camera),
                    worldToScreenY(wall.getY2(), height, camera)
            );
        }
    }

    /** Screen-space radius of the clickable door/window badge drawn at the middle of each interactable (DM only). */
    public static final double INTERACTABLE_BADGE_RADIUS = 12;

    private static boolean isOpen(DmProject.Interactable interactable) {
        return "open".equalsIgnoreCase(interactable.getState());
    }

    private static boolean isWindow(DmProject.Interactable interactable) {
        return "window".equalsIgnoreCase(interactable.getType());
    }

    private static Color interactableColor(DmProject.Interactable interactable) {
        if (isWindow(interactable)) {
            return isOpen(interactable) ? Color.DEEPSKYBLUE : Color.web("#3b6fd8");
        }
        return isOpen(interactable) ? Color.LIMEGREEN : Color.web("#e0473c");
    }

    private void drawInteractableBadges(GraphicsContext gc, List<DmProject.Interactable> interactables, double width, double height,
                                        DmProject.CameraState camera, String hoveredId) {
        double r = INTERACTABLE_BADGE_RADIUS;
        for (DmProject.Interactable interactable : interactables) {
            double sx1 = worldToScreenX(interactable.getX1(), width, camera);
            double sy1 = worldToScreenY(interactable.getY1(), height, camera);
            double sx2 = worldToScreenX(interactable.getX2(), width, camera);
            double sy2 = worldToScreenY(interactable.getY2(), height, camera);
            double cx = (sx1 + sx2) / 2.0;
            double cy = (sy1 + sy2) / 2.0;
            if (cx < -r || cy < -r || cx > width + r || cy > height + r) {
                continue;
            }
            boolean hovered = interactable.getId() != null && interactable.getId().equals(hoveredId);
            Color color = interactableColor(interactable);
            if (hovered) {
                gc.setStroke(color.deriveColor(0, 1, 1.2, 0.9));
                gc.setLineWidth(8);
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.strokeLine(sx1, sy1, sx2, sy2);
                gc.setLineCap(StrokeLineCap.SQUARE);
            }
            double radius = hovered ? r + 2 : r;
            gc.setFill(Color.color(0.1, 0.1, 0.12, hovered ? 0.95 : 0.85));
            gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            gc.setStroke(hovered ? Color.WHITE : color);
            gc.setLineWidth(2);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);
            Ikon ikon = isWindow(interactable)
                    ? (isOpen(interactable) ? MaterialDesignW.WINDOW_OPEN_VARIANT : MaterialDesignW.WINDOW_CLOSED_VARIANT)
                    : (isOpen(interactable) ? MaterialDesignD.DOOR_OPEN : MaterialDesignD.DOOR_CLOSED);
            Image glyph = Icons.image(ikon, 14, color.deriveColor(0, 0.6, 1.4, 1));
            if (glyph != null) {
                gc.drawImage(glyph, Math.round(cx - glyph.getWidth() / 2.0), Math.round(cy - glyph.getHeight() / 2.0));
            }
        }
    }

    private void drawInteractables(GraphicsContext gc, List<DmProject.Interactable> interactables, double width, double height, DmProject.CameraState camera) {
        gc.setLineWidth(4);
        for (DmProject.Interactable interactable : interactables) {
            gc.setStroke(interactableColor(interactable));
            gc.strokeLine(
                    worldToScreenX(interactable.getX1(), width, camera),
                    worldToScreenY(interactable.getY1(), height, camera),
                    worldToScreenX(interactable.getX2(), width, camera),
                    worldToScreenY(interactable.getY2(), height, camera)
            );
        }
    }

    private void drawPings(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera) {
        long now = System.currentTimeMillis();
        project.getActivePings().removeIf(p -> now - p.getCreatedAtMillis() > p.getDurationMillis());
        for (DmProject.PingEvent ping : project.getActivePings()) {
            double progress = Math.min(1.0, (now - ping.getCreatedAtMillis()) / (double) ping.getDurationMillis());
            double alpha = 1.0 - progress;
            double radius = 10 + 50 * progress;
            double x = worldToScreenX(ping.getX(), width, camera);
            double y = worldToScreenY(ping.getY(), height, camera);
            gc.setStroke(Color.color(1, 0.9, 0.2, alpha));
            gc.setLineWidth(3);
            gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
            gc.setFill(Color.color(1, 0.9, 0.2, alpha * 0.7));
            gc.fillOval(x - 6, y - 6, 12, 12);
        }
    }

    /** A recorded laser pointer position in world coordinates. */
    public record LaserPoint(double x, double y, long millis) {
    }

    public static final long LASER_TRAIL_MILLIS = 500;

    /** Draws the laser trail (oldest first) and, if {@code dotActive}, the bright dot at the newest point. */
    public void drawLaser(GraphicsContext gc, List<LaserPoint> trail, boolean dotActive, double dotRadius,
                          double width, double height, DmProject.CameraState camera) {
        if (trail.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        gc.save();
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        for (int i = 1; i < trail.size(); i++) {
            LaserPoint a = trail.get(i - 1);
            LaserPoint b = trail.get(i);
            double alpha = 1.0 - Math.min(1.0, (now - b.millis()) / (double) LASER_TRAIL_MILLIS);
            if (alpha <= 0) {
                continue;
            }
            gc.setStroke(Color.color(1, 0.1, 0.1, alpha * 0.8));
            gc.setLineWidth(Math.max(1.5, dotRadius * 1.4 * alpha));
            gc.strokeLine(worldToScreenX(a.x(), width, camera), worldToScreenY(a.y(), height, camera),
                    worldToScreenX(b.x(), width, camera), worldToScreenY(b.y(), height, camera));
        }
        if (dotActive) {
            LaserPoint p = trail.get(trail.size() - 1);
            double x = worldToScreenX(p.x(), width, camera);
            double y = worldToScreenY(p.y(), height, camera);
            gc.setFill(Color.color(1, 0.1, 0.1, 0.35));
            gc.fillOval(x - dotRadius * 2, y - dotRadius * 2, dotRadius * 4, dotRadius * 4);
            gc.setFill(Color.color(1, 0.12, 0.1, 1));
            gc.fillOval(x - dotRadius, y - dotRadius, dotRadius * 2, dotRadius * 2);
            gc.setFill(Color.color(1, 0.8, 0.8, 0.9));
            gc.fillOval(x - dotRadius * 0.4, y - dotRadius * 0.4, dotRadius * 0.8, dotRadius * 0.8);
        }
        gc.restore();
    }

    private void drawFog(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera, boolean playerMode) {
        if (project.getFog() == null || !project.getFog().isEnabled()) {
            return;
        }
        gc.save();
        gc.setGlobalAlpha(playerMode ? 1.0 : DM_FOG_ALPHA);
        gc.setFill(Color.BLACK);
        FogMask mask = project.getFog().getMask();
        if (mask == null) {
            gc.fillRect(0, 0, width, height);
            gc.restore();
            return;
        }
        refreshFogImage(mask);

        double sx = worldToScreenX(mask.getOriginX(), width, camera);
        double sy = worldToScreenY(mask.getOriginY(), height, camera);
        double sw = mask.getWidth() * camera.getZoom();
        double sh = mask.getHeight() * camera.getZoom();
        // Everything outside the mask is always fogged.
        fillClamped(gc, 0, 0, width, sy);
        fillClamped(gc, 0, sy + sh, width, height - (sy + sh));
        fillClamped(gc, 0, sy, sx, sh);
        fillClamped(gc, sx + sw, sy, width - (sx + sw), sh);
        gc.setImageSmoothing(true);
        int factor = fogImageFactor;
        gc.drawImage(fogImage, 0, 0, Math.min(fogImage.getWidth(), (double) mask.getCols() / factor),
                Math.min(fogImage.getHeight(), (double) mask.getRows() / factor), sx, sy, sw, sh);
        gc.restore();
    }

    private void fillClamped(GraphicsContext gc, double x, double y, double w, double h) {
        if (w > 0 && h > 0) {
            gc.fillRect(x, y, w, h);
        }
    }

    /** Fog cells merged per fog image pixel; performance mode draws the fog at the lowest fog resolution. */
    private static int fogDownsample() {
        return PerformanceMode.isEnabled()
                ? Math.max(1, FogService.getCellsPerGrid() / FogService.MIN_CELLS_PER_GRID)
                : 1;
    }

    /** True while fog cells are still fading; the render loop then keeps a high frame rate. */
    public boolean isFogAnimating() {
        return fogAnimating;
    }

    /** Soft edge width in fog cells; performance mode (merged cells) always uses hard edges. */
    private static int fogSoftRadius(int factor) {
        return factor == 1 ? (int) Math.round(fogSoftness * FogService.getCellsPerGrid()) : 0;
    }

    /** Rebuilds the world-space fog image only when the mask, live light reveals or the fog detail changed. */
    private void refreshFogImage(FogMask mask) {
        long liveVersion = lightingEngine.getLiveRevealVersion();
        int factor = fogDownsample();
        int radius = fogSoftRadius(factor);
        int cols = mask.getCols();
        int rows = mask.getRows();
        int imgCols = (cols + factor - 1) / factor;
        int imgRows = (rows + factor - 1) / factor;
        boolean sameGeometry = fogImage != null
                && fogImageMask == mask
                && fogImageFactor == factor
                && (int) fogImage.getWidth() == imgCols
                && (int) fogImage.getHeight() == imgRows
                && fogImageOriginX == mask.getOriginX()
                && fogImageOriginY == mask.getOriginY()
                && fogImageCellSize == mask.getCellSize();
        if (sameGeometry
                && fogImageMaskVersion == mask.getVersion()
                && fogImageLiveVersion == liveVersion
                && fogImageRadius == radius) {
            advanceFog(cols, rows);
            return;
        }
        if (fogImage == null || (int) fogImage.getWidth() != imgCols || (int) fogImage.getHeight() != imgRows) {
            fogImage = new WritableImage(imgCols, imgRows);
        }
        BitSet revealed = mask.copyBits();
        revealed.or(lightingEngine.getLiveReveal());
        if (factor == 1) {
            float[] target = FogShading.fogOpacity(revealed, cols, rows, radius);
            boolean fade = fogFadeEnabled && !PerformanceMode.isEnabled() && sameGeometry
                    && fogShown != null && fogShown.length == target.length;
            fogTarget = target;
            long lightChanges = lightingEngine.getLightRevealChanges();
            fogFadeSeconds = lightChanges != fogLightChangesSeen ? FOG_LIGHT_FADE_SECONDS : FOG_FADE_SECONDS;
            fogLightChangesSeen = lightChanges;
            if (fade) {
                // Keep the clock of a running fade, otherwise a target that changes every frame never advances.
                if (!fogAnimating) {
                    fogLastAdvanceNanos = System.nanoTime();
                }
                fogAnimating = true;
            } else {
                fogShown = target.clone();
                fogAnimating = false;
                uploadFogRows(cols, 0, rows - 1);
            }
            fogImageMask = mask;
            fogImageMaskVersion = mask.getVersion();
            fogImageLiveVersion = liveVersion;
            fogImageFactor = factor;
            fogImageRadius = radius;
            fogImageOriginX = mask.getOriginX();
            fogImageOriginY = mask.getOriginY();
            fogImageCellSize = mask.getCellSize();
            if (fade) {
                advanceFog(cols, rows);
            }
            return;
        }
        fogShown = null;
        fogTarget = null;
        fogAnimating = false;
        int[] pixels = new int[imgCols * imgRows];
        Arrays.fill(pixels, 0xFF000000);
        {
            int[] counts = new int[pixels.length];
            for (int idx = revealed.nextSetBit(0); idx >= 0 && idx < cols * rows; idx = revealed.nextSetBit(idx + 1)) {
                counts[(idx / cols / factor) * imgCols + (idx % cols) / factor]++;
            }
            for (int r = 0; r < imgRows; r++) {
                int blockRows = Math.min(factor, rows - r * factor);
                for (int c = 0; c < imgCols; c++) {
                    int blockCells = blockRows * Math.min(factor, cols - c * factor);
                    if (counts[r * imgCols + c] * 2 >= blockCells) {
                        pixels[r * imgCols + c] = 0;
                    }
                }
            }
        }
        fogImage.getPixelWriter().setPixels(0, 0, imgCols, imgRows, PixelFormat.getIntArgbInstance(), pixels, 0, imgCols);
        fogImageMask = mask;
        fogImageMaskVersion = mask.getVersion();
        fogImageLiveVersion = liveVersion;
        fogImageFactor = factor;
        fogImageRadius = radius;
        fogImageOriginX = mask.getOriginX();
        fogImageOriginY = mask.getOriginY();
        fogImageCellSize = mask.getCellSize();
    }

    /** Moves the shown fog towards the target by the time elapsed since the last call (idempotent within a frame). */
    private void advanceFog(int cols, int rows) {
        if (fogShown == null || fogTarget == null || !fogAnimating) {
            return;
        }
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - fogLastAdvanceNanos) / 1_000_000_000.0);
        if (seconds <= 0) {
            return;
        }
        fogLastAdvanceNanos = now;
        int[] changed = FogShading.advance(fogShown, fogTarget, (float) (seconds / fogFadeSeconds));
        if (changed == null) {
            fogAnimating = false;
            return;
        }
        uploadFogRows(cols, changed[0] / cols, changed[1] / cols);
        fogAnimating = !FogShading.reached(fogShown, fogTarget);
    }

    private void uploadFogRows(int cols, int firstRow, int lastRow) {
        int count = (lastRow - firstRow + 1) * cols;
        if (fogPixels == null || fogPixels.length < count) {
            fogPixels = new int[count];
        }
        int base = firstRow * cols;
        for (int i = 0; i < count; i++) {
            fogPixels[i] = FogShading.pixel(fogShown[base + i]);
        }
        fogImage.getPixelWriter().setPixels(0, firstRow, cols, lastRow - firstRow + 1,
                PixelFormat.getIntArgbInstance(), fogPixels, 0, cols);
    }
    private void drawViewportRect(GraphicsContext gc, WorldRect rect, double width, double height, DmProject.CameraState camera) {
        double x = worldToScreenX(rect.x(), width, camera);
        double y = worldToScreenY(rect.y(), height, camera);
        double w = rect.width() * camera.getZoom();
        double h = rect.height() * camera.getZoom();
        double barHeight = VIEWPORT_TITLE_BAR_HEIGHT;
        double barY = y - barHeight;

        gc.setFill(Color.color(0.0, 0.55, 0.62, 0.9));
        gc.fillRect(x - 1, barY, w + 2, barHeight);
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(2);
        gc.strokeRect(x, y, w, h);

        gc.save();
        gc.beginPath();
        gc.rect(x, barY, w, barHeight);
        gc.clip();
        gc.setFill(Color.WHITE);
        gc.setFont(VIEWPORT_TITLE_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("Player view", x + 8, barY + barHeight / 2.0);
        gc.restore();
    }

    /** True if the screen point lies on the draggable title bar drawn above the player viewport rectangle. */
    public boolean isOnViewportTitleBar(WorldRect rect, double screenX, double screenY,
                                        double width, double height, DmProject.CameraState camera) {
        if (rect == null) {
            return false;
        }
        double x = worldToScreenX(rect.x(), width, camera);
        double y = worldToScreenY(rect.y(), height, camera);
        double w = rect.width() * camera.getZoom();
        return screenX >= x - 1 && screenX <= x + w + 1
                && screenY >= y - VIEWPORT_TITLE_BAR_HEIGHT && screenY <= y + 1;
    }

    private ImagePyramidStore.MapImage resolveImage(String path, Path projectFile) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path resolved = Path.of(path);
            if (!resolved.isAbsolute() && projectFile != null && projectFile.getParent() != null) {
                resolved = projectFile.getParent().resolve(path).normalize();
            }
            if (!Files.exists(resolved)) {
                return null;
            }
            return imageStore.get(resolved);
        } catch (Exception ex) {
            return null;
        }
    }

    public double worldToScreenX(double worldX, double canvasWidth, DmProject.CameraState camera) {
        return (worldX - camera.getX()) * camera.getZoom() + canvasWidth / 2.0;
    }

    public double worldToScreenY(double worldY, double canvasHeight, DmProject.CameraState camera) {
        return (worldY - camera.getY()) * camera.getZoom() + canvasHeight / 2.0;
    }

    public WorldPoint screenToWorld(double screenX, double screenY, double canvasWidth, double canvasHeight, DmProject.CameraState camera) {
        double worldX = (screenX - canvasWidth / 2.0) / camera.getZoom() + camera.getX();
        double worldY = (screenY - canvasHeight / 2.0) / camera.getZoom() + camera.getY();
        return new WorldPoint(worldX, worldY);
    }

    public record WorldPoint(double x, double y) {
    }

    public record WorldRect(double x, double y, double width, double height) {
    }

    private static final class LightBuffer {
        private int width;
        private int height;
        private float[] lit = new float[0];
        private int[] source = new int[0];
        private int[] argb = new int[0];
        private WritableImage image;
        private long key;
        private boolean valid;

        void ensureSize(int w, int h) {
            if (image != null && w == width && h == height) {
                return;
            }
            valid = false;
            width = w;
            height = h;
            lit = new float[w * h];
            source = new int[w * h];
            argb = new int[w * h];
            image = new WritableImage(w, h);
        }
    }
}





