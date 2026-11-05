package dmmt.online;

import dmmt.model.DmProject;

/** Host-side rules for a player-requested token move: ownership, visibility, walls and closed doors. */
public final class MoveValidator {
    private MoveValidator() {
    }

    /** Returns {@code null} when the move is allowed, otherwise a short reason. */
    public static String check(DmProject project, Campaign campaign, String mapId, String playerId,
                               DmProject.TokenInstance token, double x, double y) {
        return check(project, campaign, mapId, playerId, token, x, y, false);
    }

    public static String check(DmProject project, Campaign campaign, String mapId, String playerId,
                               DmProject.TokenInstance token, double x, double y, boolean phaseMovement) {
        if (token == null) {
            return "Unknown token";
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return "Invalid position";
        }
        if (!token.isPlayerVisible()) {
            return "Token is hidden";
        }
        Campaign.Actor actor = OnlineStateBuilder.actorOf(token, campaign);
        if (!OnlineStateBuilder.controllers(token, actor, campaign, mapId).contains(playerId)) {
            return "You do not control this token";
        }
        if (phaseMovement) {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (DmProject.ImageLayer image : project.getImageLayers()) {
                if (!image.isVisible() || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
                minX = Math.min(minX, image.getX());
                minY = Math.min(minY, image.getY());
                maxX = Math.max(maxX, image.getX() + image.getWidth());
                maxY = Math.max(maxY, image.getY() + image.getHeight());
            }
            if (Double.isFinite(minX) && (x < minX || y < minY || x > maxX || y > maxY))
                return "Outside the map";
            return null;
        }
        double x0 = token.getX();
        double y0 = token.getY();
        for (DmProject.WallSegment wall : project.getWalls()) {
            if (crosses(x0, y0, x, y, wall.getX1(), wall.getY1(), wall.getX2(), wall.getY2())) {
                return "Blocked by a wall";
            }
        }
        for (DmProject.Interactable door : project.getInteractables()) {
            if (!OnlineStateBuilder.isOpen(door)
                    && crosses(x0, y0, x, y, door.getX1(), door.getY1(), door.getX2(), door.getY2())) {
                return "Blocked by a closed door";
            }
        }
        return null;
    }

    /** Proper segment intersection (touching end points count as crossing). */
    static boolean crosses(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return d1 == 0 && within(cx, cy, dx, dy, ax, ay) || d2 == 0 && within(cx, cy, dx, dy, bx, by)
                || d3 == 0 && within(ax, ay, bx, by, cx, cy) || d4 == 0 && within(ax, ay, bx, by, dx, dy);
    }

    private static double cross(double x1, double y1, double x2, double y2, double px, double py) {
        return (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
    }

    private static boolean within(double x1, double y1, double x2, double y2, double px, double py) {
        return px >= Math.min(x1, x2) && px <= Math.max(x1, x2) && py >= Math.min(y1, y2) && py <= Math.max(y1, y2);
    }
}
