package dmmt.service;

import dmmt.model.DmProject;

public class MapRotationService {
    public void rotateClockwise(DmProject project) {
        rotate(project, true);
    }

    public void rotateCounterClockwise(DmProject project) {
        rotate(project, false);
    }

    private void rotate(DmProject project, boolean clockwise) {
        Bounds bounds = computeBounds(project);
        double cx = (bounds.minX + bounds.maxX) / 2.0;
        double cy = (bounds.minY + bounds.maxY) / 2.0;

        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            double centerX = layer.getX() + layer.getWidth() / 2.0;
            double centerY = layer.getY() + layer.getHeight() / 2.0;
            Point rotated = rotatePoint(centerX, centerY, cx, cy, clockwise);
            double oldWidth = layer.getWidth();
            double oldHeight = layer.getHeight();
            layer.setWidth(oldHeight);
            layer.setHeight(oldWidth);
            layer.setX(rotated.x - layer.getWidth() / 2.0);
            layer.setY(rotated.y - layer.getHeight() / 2.0);
            layer.setRotationDeg(normalizeDegrees(layer.getRotationDeg() + (clockwise ? 90 : -90)));
        }

        for (DmProject.WallSegment wall : project.getWalls()) {
            Point a = rotatePoint(wall.getX1(), wall.getY1(), cx, cy, clockwise);
            Point b = rotatePoint(wall.getX2(), wall.getY2(), cx, cy, clockwise);
            wall.setX1(a.x);
            wall.setY1(a.y);
            wall.setX2(b.x);
            wall.setY2(b.y);
        }

        for (DmProject.Interactable interactable : project.getInteractables()) {
            Point a = rotatePoint(interactable.getX1(), interactable.getY1(), cx, cy, clockwise);
            Point b = rotatePoint(interactable.getX2(), interactable.getY2(), cx, cy, clockwise);
            interactable.setX1(a.x);
            interactable.setY1(a.y);
            interactable.setX2(b.x);
            interactable.setY2(b.y);
        }

        for (DmProject.LightSource light : project.getLighting().getLights()) {
            Point p = rotatePoint(light.getX(), light.getY(), cx, cy, clockwise);
            light.setX(p.x);
            light.setY(p.y);
        }

        for (DmProject.OverlayShape overlay : project.getOverlays()) {
            rotateOverlay(overlay, cx, cy, clockwise);
        }

        for (DmProject.TextBox box : project.getTextBoxes()) {
            // Text boxes keep their own (global) rotation, so only their position follows the map.
            Point center = rotatePoint(box.getX() + box.getWidth() / 2.0, box.getY() + box.getHeight() / 2.0, cx, cy, clockwise);
            box.setX(center.x - box.getWidth() / 2.0);
            box.setY(center.y - box.getHeight() / 2.0);
        }

        if (project.getFog() != null && project.getFog().getMask() != null) {
            project.getFog().getMask().rotateQuarter(cx, cy, clockwise);
        }

        Point dm = rotatePoint(project.getViews().getDmCamera().getX(), project.getViews().getDmCamera().getY(), cx, cy, clockwise);
        project.getViews().getDmCamera().setX(dm.x);
        project.getViews().getDmCamera().setY(dm.y);

        Point player = rotatePoint(project.getViews().getPlayerCamera().getX(), project.getViews().getPlayerCamera().getY(), cx, cy, clockwise);
        project.getViews().getPlayerCamera().setX(player.x);
        project.getViews().getPlayerCamera().setY(player.y);

        int delta = clockwise ? 1 : -1;
        int turns = Math.floorMod(project.getMap().getRotationQuarterTurns() + delta, 4);
        project.getMap().setRotationQuarterTurns(turns);
    }

    private void rotateOverlay(DmProject.OverlayShape overlay, double cx, double cy, boolean clockwise) {
        if ("rect".equals(overlay.getType())) {
            Point center = rotatePoint(overlay.getX() + overlay.getWidth() / 2.0, overlay.getY() + overlay.getHeight() / 2.0, cx, cy, clockwise);
            double width = overlay.getHeight();
            double height = overlay.getWidth();
            overlay.setWidth(width);
            overlay.setHeight(height);
            overlay.setX(center.x - width / 2.0);
            overlay.setY(center.y - height / 2.0);
        } else if ("brush".equals(overlay.getType())) {
            java.util.List<Double> points = overlay.getPoints();
            for (int i = 0; i + 1 < points.size(); i += 2) {
                Point p = rotatePoint(points.get(i), points.get(i + 1), cx, cy, clockwise);
                points.set(i, p.x);
                points.set(i + 1, p.y);
            }
        } else {
            Point p = rotatePoint(overlay.getX(), overlay.getY(), cx, cy, clockwise);
            overlay.setX(p.x);
            overlay.setY(p.y);
        }
    }

    private Bounds computeBounds(DmProject project) {
        Bounds bounds = new Bounds();
        if (project.getImageLayers().isEmpty()) {
            bounds.add(0, 0);
            bounds.add(1920, 1080);
            return bounds;
        }

        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            bounds.add(layer.getX(), layer.getY());
            bounds.add(layer.getX() + layer.getWidth(), layer.getY() + layer.getHeight());
        }
        return bounds;
    }

    private Point rotatePoint(double x, double y, double cx, double cy, boolean clockwise) {
        double dx = x - cx;
        double dy = y - cy;
        return clockwise
                ? new Point(cx - dy, cy + dx)
                : new Point(cx + dy, cy - dx);
    }

    private double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private void add(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
    }

    private record Point(double x, double y) {
    }
}
