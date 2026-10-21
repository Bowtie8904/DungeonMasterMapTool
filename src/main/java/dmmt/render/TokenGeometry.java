package dmmt.render;

import dmmt.model.DmProject;
import javafx.geometry.Point2D;

public final class TokenGeometry {
    private TokenGeometry() {}

    public static double normalizeDegrees(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    public static Point2D toLocal(DmProject.TokenInstance token, double x, double y) {
        double angle = Math.toRadians(token.getRotationDeg());
        double dx = x - token.getX(), dy = y - token.getY();
        return new Point2D(dx * Math.cos(angle) + dy * Math.sin(angle),
                -dx * Math.sin(angle) + dy * Math.cos(angle));
    }

    public static Point2D toWorld(DmProject.TokenInstance token, double x, double y) {
        double angle = Math.toRadians(token.getRotationDeg());
        return new Point2D(token.getX() + x * Math.cos(angle) - y * Math.sin(angle),
                token.getY() + x * Math.sin(angle) + y * Math.cos(angle));
    }

    public static boolean contains(DmProject project, DmProject.TokenInstance token, double x, double y) {
        Point2D local = toLocal(token, x, y);
        double r = token.getSizeTiles() * project.getMap().getGrid().getPixelsPerCell() / 2;
        var definition = project.getTokenDefinitions().get(token.getDefinitionId());
        return definition != null && definition.getShape() == DmProject.TokenShape.SQUARE
                ? Math.abs(local.getX()) <= r && Math.abs(local.getY()) <= r
                : local.magnitude() <= r;
    }

    public static double halfExtent(DmProject project, DmProject.TokenInstance token) {
        double r = token.getSizeTiles() * project.getMap().getGrid().getPixelsPerCell() / 2;
        var definition = project.getTokenDefinitions().get(token.getDefinitionId());
        if (definition == null || definition.getShape() != DmProject.TokenShape.SQUARE)
            return r;
        double angle = Math.toRadians(token.getRotationDeg());
        return r * (Math.abs(Math.cos(angle)) + Math.abs(Math.sin(angle)));
    }
}
