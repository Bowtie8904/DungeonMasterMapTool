package dmmt.render;

import dmmt.model.DmProject;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanvasMapRenderer {
    private final Map<String, Image> imageCache = new HashMap<>();

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
        drawLights(gc, project, width, height, camera, playerMode);
        if (!playerMode) {
            drawWalls(gc, project.getWalls(), width, height, camera);
            drawInteractables(gc, project.getInteractables(), width, height, camera);
        }
        drawPings(gc, project, width, height, camera);

        if (!playerMode && playerViewportWorld != null) {
            drawViewportRect(gc, playerViewportWorld, width, height, camera);
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

    private void drawLights(GraphicsContext gc, DmProject project, double width, double height, DmProject.CameraState camera, boolean playerMode) {
        for (DmProject.LightSource light : project.getLighting().getLights()) {
            double x = worldToScreenX(light.getX(), width, camera);
            double y = worldToScreenY(light.getY(), height, camera);
            double radius = light.getRange() * camera.getZoom();
            gc.setFill(Color.color(1.0, 0.85, 0.45, playerMode ? 0.20 : 0.12));
            gc.fillOval(x - radius, y - radius, radius * 2.0, radius * 2.0);
            if (!playerMode) {
                gc.setFill(Color.ORANGE);
                gc.fillOval(x - 4, y - 4, 8, 8);
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
}
