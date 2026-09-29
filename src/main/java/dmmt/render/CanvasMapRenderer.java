package dmmt.render;

import dmmt.lighting.LightFlicker;
import dmmt.lighting.LightingEngine;
import dmmt.lighting.PolygonRaster;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.lighting.VisibilityService;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanvasMapRenderer {
    /** Light map is computed at 1/LIGHT_MAP_SCALE of screen resolution and smoothed when scaled up. */
    private static final int LIGHT_MAP_SCALE = 4;
    private static final double DM_FOG_ALPHA = 0.58;
    private static final double DM_DARKNESS_FACTOR = 0.45;

    private final Map<String, Image> imageCache = new HashMap<>();
    private final LightingEngine lightingEngine;
    private final LightBuffer dmLightBuffer = new LightBuffer();
    private final LightBuffer playerLightBuffer = new LightBuffer();

    private WritableImage fogImage;
    private FogMask fogImageMask;
    private long fogImageMaskVersion = -1;
    private long fogImageLiveVersion = -1;

    public CanvasMapRenderer(LightingEngine lightingEngine) {
        this.lightingEngine = lightingEngine;
    }

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
        gc.setFill(Color.web("#202125"));
        gc.fillRect(0, 0, width, height);

        if (project == null) {
            gc.setFill(Color.web("#cccccc"));
            gc.fillText("Open or import a map to begin.", 20, 30);
            return;
        }

        drawGrid(gc, project, width, height, camera);
        drawLayers(gc, project, projectFile, width, height, camera);
        drawLighting(gc, project, width, height, camera, playerMode);
        drawOverlays(gc, project, width, height, camera, playerMode);
        if (!playerMode) {
            drawWalls(gc, project.getWalls(), width, height, camera);
            drawInteractables(gc, project.getInteractables(), width, height, camera);
        }
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
            String selectedLightId
    ) {
        gc.clearRect(0, 0, width, height);
        if (project == null) {
            return;
        }
        drawFog(gc, project, width, height, camera, playerMode);
        drawPings(gc, project, width, height, camera);
        if (!playerMode) {
            drawLightTokens(gc, project, width, height, camera, selectedLightId);
            if (playerViewportWorld != null) {
                drawViewportRect(gc, playerViewportWorld, width, height, camera);
            }
        }
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

    private void drawLayers(GraphicsContext gc, DmProject project, Path projectFile, double width, double height, DmProject.CameraState camera) {
        List<DmProject.ImageLayer> sorted = project.getImageLayers().stream()
                .filter(DmProject.ImageLayer::isVisible)
                .sorted((a, b) -> Integer.compare(a.getZIndex(), b.getZIndex()))
                .toList();

        for (DmProject.ImageLayer layer : sorted) {
            Image image = resolveImage(layer.getPath(), projectFile);
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
            gc.drawImage(image, -sw / 2.0, -sh / 2.0, sw, sh);
            gc.restore();
        }
    }

    /** AOE shapes sit above the map and lighting but below fog, so fog still hides them from players. */
    private void drawOverlays(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera, boolean playerMode) {
        double zoom = camera.getZoom();
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            if (playerMode && !shape.isPlayerVisible()) {
                continue;
            }
            boolean dmOnly = !playerMode && !shape.isPlayerVisible();
            Color base;
            try {
                base = Color.web(shape.getColor() == null ? "#55AA33" : shape.getColor());
            } catch (IllegalArgumentException ex) {
                base = Color.web("#55AA33");
            }
            double alpha = Math.max(0.05, Math.min(1.0, shape.getAlpha())) * (dmOnly ? 0.6 : 1.0);
            Color fill = base.deriveColor(0, 1, 1, alpha);
            Color edge = base.deriveColor(0, 1, 1, Math.min(1.0, alpha + 0.35));
            gc.setFill(fill);
            gc.setStroke(edge);
            gc.setLineWidth(2);
            gc.setLineDashes(dmOnly ? new double[]{8, 6} : null);
            String type = shape.getType() == null ? "" : shape.getType();
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
                case "brush" -> drawBrushStroke(gc, shape, fill, width, height, camera);
                default -> {
                }
            }
            gc.setLineDashes(null);
        }
    }

    private void drawBrushStroke(GraphicsContext gc, DmProject.OverlayShape shape, Color fill, double width, double height, DmProject.CameraState camera) {
        List<Double> points = shape.getPoints();
        int n = points.size() / 2;
        if (n == 0) {
            return;
        }
        double lineWidth = Math.max(1, shape.getStrokeWidth() * camera.getZoom());
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
        double darkness = preset.darkness() * (playerMode ? 1.0 : DM_DARKNESS_FACTOR);
        if (darkness < 0.01) {
            return;
        }
        int bw = Math.max(1, (int) Math.ceil(width / LIGHT_MAP_SCALE));
        int bh = Math.max(1, (int) Math.ceil(height / LIGHT_MAP_SCALE));
        LightBuffer buffer = playerMode ? playerLightBuffer : dmLightBuffer;
        buffer.ensureSize(bw, bh);
        Arrays.fill(buffer.lit, 0f);

        List<DmProject.LightSource> lights = project.getLighting().getLights();
        int[] lightRgb = new int[lights.size()];
        long now = System.currentTimeMillis();
        double zoom = camera.getZoom();
        for (int li = 0; li < lights.size(); li++) {
            DmProject.LightSource light = lights.get(li);
            lightRgb[li] = parseRgb(light.getColor());
            if (!light.isEnabled()) {
                continue;
            }
            double flicker = LightFlicker.amount(light, now);
            double radius = light.getRange() * zoom * (1.0 - 0.25 * flicker) / LIGHT_MAP_SCALE;
            if (radius < 0.5) {
                continue;
            }
            double lx = worldToScreenX(light.getX(), width, camera) / LIGHT_MAP_SCALE;
            double ly = worldToScreenY(light.getY(), height, camera) / LIGHT_MAP_SCALE;
            if (lx + radius < 0 || ly + radius < 0 || lx - radius > bw || ly - radius > bh) {
                continue;
            }
            double brightness = (0.35 + 0.65 * Math.min(1.0, Math.max(0, light.getIntensity()))) * (1.0 - 0.6 * flicker);
            VisibilityService.Polygon polygon = lightingEngine.polygonFor(light);
            double[] xs = new double[polygon.size()];
            double[] ys = new double[polygon.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = worldToScreenX(polygon.xs()[i], width, camera) / LIGHT_MAP_SCALE;
                ys[i] = worldToScreenY(polygon.ys()[i], height, camera) / LIGHT_MAP_SCALE;
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

        double glowStrength = 0.22 * Math.sqrt(darkness);
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
        gc.drawImage(buffer.image, 0, 0, bw, bh, 0, 0, bw * (double) LIGHT_MAP_SCALE, bh * (double) LIGHT_MAP_SCALE);
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
        gc.setStroke(Color.color(1, 1, 1, 0.55));
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

    private void drawInteractables(GraphicsContext gc, List<DmProject.Interactable> interactables, double width, double height, DmProject.CameraState camera) {
        gc.setLineWidth(4);
        for (DmProject.Interactable interactable : interactables) {
            Color color;
            if ("window".equalsIgnoreCase(interactable.getType())) {
                color = "open".equalsIgnoreCase(interactable.getState()) ? Color.DEEPSKYBLUE : Color.DARKBLUE;
            } else {
                color = "open".equalsIgnoreCase(interactable.getState()) ? Color.LIMEGREEN : Color.FIREBRICK;
            }
            gc.setStroke(color);
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
        gc.drawImage(fogImage, sx, sy, sw, sh);
        gc.restore();
    }

    private void fillClamped(GraphicsContext gc, double x, double y, double w, double h) {
        if (w > 0 && h > 0) {
            gc.fillRect(x, y, w, h);
        }
    }

    /** Rebuilds the world-space fog image only when the mask or live light reveals changed. */
    private void refreshFogImage(FogMask mask) {
        long liveVersion = lightingEngine.getLiveRevealVersion();
        if (fogImage != null
                && fogImageMask == mask
                && fogImageMaskVersion == mask.getVersion()
                && fogImageLiveVersion == liveVersion
                && (int) fogImage.getWidth() == mask.getCols()
                && (int) fogImage.getHeight() == mask.getRows()) {
            return;
        }
        int cols = mask.getCols();
        int rows = mask.getRows();
        if (fogImage == null || (int) fogImage.getWidth() != cols || (int) fogImage.getHeight() != rows) {
            fogImage = new WritableImage(cols, rows);
        }
        BitSet revealed = mask.copyBits();
        revealed.or(lightingEngine.getLiveReveal());
        int[] pixels = new int[cols * rows];
        Arrays.fill(pixels, 0xFF000000);
        for (int idx = revealed.nextSetBit(0); idx >= 0 && idx < pixels.length; idx = revealed.nextSetBit(idx + 1)) {
            pixels[idx] = 0;
        }
        fogImage.getPixelWriter().setPixels(0, 0, cols, rows, PixelFormat.getIntArgbInstance(), pixels, 0, cols);
        fogImageMask = mask;
        fogImageMaskVersion = mask.getVersion();
        fogImageLiveVersion = liveVersion;
    }

    private void drawViewportRect(GraphicsContext gc, WorldRect rect, double width, double height, DmProject.CameraState camera) {
        double x = worldToScreenX(rect.x(), width, camera);
        double y = worldToScreenY(rect.y(), height, camera);
        double w = rect.width() * camera.getZoom();
        double h = rect.height() * camera.getZoom();
        gc.setStroke(Color.CYAN);
        gc.setLineWidth(2);
        gc.strokeRect(x, y, w, h);
        gc.setFill(Color.color(0.0, 1.0, 1.0, 0.1));
        gc.fillRect(x, y, w, h);
    }

    private Image resolveImage(String path, Path projectFile) {
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
            Path finalResolved = resolved;
            String key = finalResolved.toAbsolutePath().toString();
            return imageCache.computeIfAbsent(key, k -> new Image(finalResolved.toUri().toString()));
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

        void ensureSize(int w, int h) {
            if (image != null && w == width && h == height) {
                return;
            }
            width = w;
            height = h;
            lit = new float[w * h];
            source = new int[w * h];
            argb = new int[w * h];
            image = new WritableImage(w, h);
        }
    }
}
