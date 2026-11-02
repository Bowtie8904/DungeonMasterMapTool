package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import dmmt.model.DmProject;
import dmmt.render.OverlayTextures;
import dmmt.service.Tuning;

import java.util.ArrayList;
import java.util.Set;

/** Validates the complete editable shape payload without accepting client-supplied ownership or visibility. */
public final class OnlineDrawingEdits {
    private OnlineDrawingEdits() {
    }

    public static boolean owns(DmProject.OverlayShape shape, String campaignId, String playerId) {
        return playerId != null && playerId.equals(shape.getOwnerId())
                && campaignId != null && campaignId.equals(shape.getOwnerCampaignId());
    }

    public static DmProject.OverlayShape prepare(DmProject project, String campaignId, String playerId, JsonNode command) {
        String id = command.path("id").asText("");
        if (id.isBlank() || id.length() > 100) throw new IllegalArgumentException("Invalid drawing id");
        var existing = project.getOverlays().stream().filter(o -> id.equals(o.getId())).findFirst().orElse(null);
        String action = command.path("action").asText();
        if ("create".equals(action)) {
            if (existing != null || project.getOverlays().size() >= 5000) {
                throw new IllegalArgumentException("Drawing already exists or map drawing limit reached");
            }
        } else if (existing == null || !existing.isPlayerVisible() || !owns(existing, campaignId, playerId)) {
            throw new IllegalArgumentException("Only the owner or DM may edit this drawing");
        }
        if ("delete".equals(action)) return null;
        if (!"create".equals(action) && !"update".equals(action)) throw new IllegalArgumentException("Unknown drawing action");
        return parse(command.path("shape"), id, campaignId, playerId, project.getMap().getGrid().getPixelsPerCell());
    }

    public static DmProject.OverlayShape parse(JsonNode node, String id, String campaignId, String playerId,
                                             double pixelsPerCell) {
        String type = node.path("type").asText();
        if (!Set.of("pen", "brush", "line", "circle", "rect").contains(type)) {
            throw new IllegalArgumentException("Unknown drawing mode");
        }
        String texture = node.path("texture").asText("none");
        if (!OverlayTextures.KINDS.contains(texture)) {
            throw new IllegalArgumentException("Unknown effect texture");
        }
        String color = node.path("color").asText();
        if (!color.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Invalid drawing color");
        }
        double x = number(node, "x", -1e7, 1e7);
        double y = number(node, "y", -1e7, 1e7);
        double width = number(node, "width", 0, 1e7);
        double height = number(node, "height", 0, 1e7);
        double radius = number(node, "radius", 0, 1e7);
        double stroke = number(node, "strokeWidth", 0.01, Math.max(1, pixelsPerCell * 100));
        double alpha = number(node, "alpha", 0, 1);
        JsonNode points = node.path("points");
        boolean path = Set.of("pen", "brush", "line").contains(type);
        if (!points.isArray() || points.size() > 4096 || points.size() % 2 != 0
                || path && points.size() < 2 || "line".equals(type) && points.size() != 4) {
            throw new IllegalArgumentException("Invalid drawing points (maximum 2048)");
        }
        ArrayList<Double> coords = new ArrayList<>();
        for (JsonNode p : points) {
            double value = p.asDouble(Double.NaN);
            if (!p.isNumber() || !Double.isFinite(value) || Math.abs(value) > 1e7) {
                throw new IllegalArgumentException("Invalid drawing coordinate");
            }
            coords.add(value);
        }
        boolean ink = "pen".equals(type) || "line".equals(type);
        return DmProject.OverlayShape.builder().id(id).ownerId(playerId).ownerCampaignId(campaignId)
                .type(type).x(x).y(y).width(width).height(height).radius(radius)
                .strokeWidth("pen".equals(type) ? Tuning.PEN_WIDTH_CELLS.get() * pixelsPerCell : stroke)
                .points(coords).color(color).alpha(alpha).texture(ink ? "none" : texture)
                .border(!ink && node.path("border").asBoolean()).playerVisible(true)
                .emitsLight(!ink && node.path("emits").asBoolean())
                .aboveTokens(ink || node.path("above").asBoolean(OverlayTextures.defaultAboveTokens(texture))).build();
    }

    static double number(JsonNode node, String key, double min, double max) {
        JsonNode value = node.path(key);
        double n = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(n) || n < min || n > max) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return n;
    }
}
