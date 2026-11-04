package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Session-only world-space annotation. Distances are expressed in map grid tiles, not system-specific feet/metres. */
public record OnlineMeasurement(String id, String ownerId, String kind, double x1, double y1, double x2, double y2) {
    public static OnlineMeasurement parse(JsonNode node, String ownerId) {
        String id = node.path("id").asText("");
        String kind = node.path("kind").asText("");
        if (id.isBlank() || id.length() > 100 || !Set.of("distance", "radius", "cone", "rect").contains(kind)) {
            throw new IllegalArgumentException("Invalid measurement");
        }
        return new OnlineMeasurement(id, ownerId, kind,
                OnlineDrawingEdits.number(node, "x1", -1e7, 1e7),
                OnlineDrawingEdits.number(node, "y1", -1e7, 1e7),
                OnlineDrawingEdits.number(node, "x2", -1e7, 1e7),
                OnlineDrawingEdits.number(node, "y2", -1e7, 1e7));
    }

    /** Daggerheart SRD defined ranges on a one-inch grid: Melee 1, Very Close 3, Close 6, Far 12 squares, then Very Far. */
    public static String daggerheartRange(double tiles) {
        if (tiles <= 1) {
            return "Melee";
        }
        if (tiles <= 3) {
            return "Very Close";
        }
        if (tiles <= 6) {
            return "Close";
        }
        return tiles <= 12 ? "Far" : "Very Far";
    }

    public String label(double pixelsPerCell) {
        double p = Math.max(0.01, pixelsPerCell);
        double length = Math.hypot(x2 - x1, y2 - y1) / p;
        String range = daggerheartRange(Double.parseDouble(String.format(java.util.Locale.ROOT, "%.1f", length)));
        if ("rect".equals(kind)) {
            return String.format(java.util.Locale.ROOT, "%.1f x %.1f tiles \u00B7 %s", Math.abs(x2 - x1) / p,
                    Math.abs(y2 - y1) / p, range);
        }
        return String.format(java.util.Locale.ROOT, "%s%.1f tiles \u00B7 %s", "radius".equals(kind) ? "r = " : "",
                length, range);
    }

    public OnlineMeasurement translate(double dx, double dy) {
        return new OnlineMeasurement(id, ownerId, kind, x1 + dx, y1 + dy, x2 + dx, y2 + dy);
    }
}
