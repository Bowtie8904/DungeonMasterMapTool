package dmmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

public class Dd2vttImportService {
    private final ObjectMapper mapper = JsonMappers.create();

    public DmProject importToProject(Path dd2vttFile, Path projectDirectory) throws IOException {
        JsonNode root = mapper.readTree(dd2vttFile.toFile());
        String baseName = stripExtension(dd2vttFile.getFileName().toString());
        Path importDir = projectDirectory.resolve("imports").resolve(baseName);
        Files.createDirectories(importDir);

        DmProject project = DmProject.builder().build();
        project.getMap().setSourceType("dd2vtt");
        project.getMap().setImageLayersLocked(true);
        project.getMap().setOriginalFileName(baseName);
        double pixelsPerGrid = resolvePixelsPerGrid(root);
        project.getMap().setGrid(DmProject.GridSpec.builder()
                .pixelsPerCell(pixelsPerGrid)
                .cellSizeFeet(resolveDouble(root, "cellSizeFeet", "cell_size_feet", 5))
                .build());

        Path imagePath = importImage(root, dd2vttFile, importDir);
        if (imagePath != null) {
            String rel = projectDirectory.relativize(imagePath).toString().replace('/', '\\');
            project.getMap().setImagePath(rel);
            addBaseLayer(project, imagePath, rel);
        }

        parseWalls(root, project, pixelsPerGrid);
        parseLights(root, project, pixelsPerGrid);
        parsePortals(root, project, pixelsPerGrid);
        new FogService().ensureMask(project);
        if (Tuning.IMPORT_AUTO_LABEL_ROOMS.get()) {
            RoomLabelService.addImportedRoomLabels(project);
        }
        return project;
    }

    private void addBaseLayer(DmProject project, Path imagePath, String relPath) {
        double width = 1920;
        double height = 1080;
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ignored) {
        }
        project.getImageLayers().add(DmProject.ImageLayer.builder()
                .id("base-" + UUID.randomUUID())
                .path(relPath)
                .width(width)
                .height(height)
                .build());
    }

    private Path importImage(JsonNode root, Path sourceDd2vtt, Path importDir) throws IOException {
        JsonNode imageNode = root.path("image");
        if (imageNode.isMissingNode() || imageNode.isNull() || !imageNode.isTextual()) {
            return null;
        }

        String raw = imageNode.asText();
        if (raw.startsWith("data:image/") && raw.contains(";base64,")) {
            return decodeDataUrl(raw, importDir);
        }

        Path imagePath = Path.of(raw);
        if (!imagePath.isAbsolute()) {
            imagePath = sourceDd2vtt.getParent() != null ? sourceDd2vtt.getParent().resolve(imagePath).normalize() : imagePath;
        }
        if (!Files.exists(imagePath)) {
            if (isLikelyRawBase64(raw)) {
                return decodeBase64Image(raw, importDir);
            }
            return null;
        }

        Path target = importDir.resolve(imagePath.getFileName());
        Files.copy(imagePath, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private Path decodeDataUrl(String dataUrl, Path outputDir) throws IOException {
        String[] parts = dataUrl.split(",", 2);
        String meta = parts[0];
        String payload = parts.length > 1 ? parts[1] : "";
        String ext = "png";
        if (meta.contains("image/webp")) {
            ext = "webp";
        } else if (meta.contains("image/jpeg")) {
            ext = "jpg";
        }
        byte[] data = Base64.getDecoder().decode(payload);
        Path out = outputDir.resolve("map-image." + ext);
        Files.write(out, data);
        return out;
    }

    private Path decodeBase64Image(String base64Data, Path outputDir) throws IOException {
        byte[] data = Base64.getDecoder().decode(base64Data);
        String ext = guessImageExtension(data);
        Path out = outputDir.resolve("map-image." + ext);
        Files.write(out, data);
        return out;
    }

    private boolean isLikelyRawBase64(String value) {
        if (value == null || value.length() < 64 || value.contains("\\")) {
            return false;
        }
        if (value.matches("^[A-Za-z]:.*")) {
            return false;
        }
        return value.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }

    private String guessImageExtension(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 0x50
                && data[2] == 0x4E
                && data[3] == 0x47) {
            return "png";
        }
        if (data.length >= 12
                && data[0] == 'R'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == 'F'
                && data[8] == 'W'
                && data[9] == 'E'
                && data[10] == 'B'
                && data[11] == 'P') {
            return "webp";
        }
        return "png";
    }

    private void parseWalls(JsonNode root, DmProject project, double pixelsPerGrid) {
        JsonNode los = root.path("line_of_sight");
        if (!los.isArray()) {
            return;
        }

        for (JsonNode node : los) {
            if (node.isArray() && node.size() == 4 && node.get(0).isNumber()) {
                project.getWalls().add(DmProject.WallSegment.builder()
                        .x1(toPixels(node.get(0).asDouble(), pixelsPerGrid))
                        .y1(toPixels(node.get(1).asDouble(), pixelsPerGrid))
                        .x2(toPixels(node.get(2).asDouble(), pixelsPerGrid))
                        .y2(toPixels(node.get(3).asDouble(), pixelsPerGrid))
                        .build());
                continue;
            }
            if (node.isArray() && node.size() >= 2 && node.get(0).isObject()) {
                JsonNode first = node.get(0);
                JsonNode second = node.get(1);
                project.getWalls().add(DmProject.WallSegment.builder()
                        .x1(toPixels(first.path("x").asDouble(), pixelsPerGrid))
                        .y1(toPixels(first.path("y").asDouble(), pixelsPerGrid))
                        .x2(toPixels(second.path("x").asDouble(), pixelsPerGrid))
                        .y2(toPixels(second.path("y").asDouble(), pixelsPerGrid))
                        .build());
                if (node.size() > 2) {
                    JsonNode prev = second;
                    for (int i = 2; i < node.size(); i++) {
                        JsonNode cur = node.get(i);
                        project.getWalls().add(DmProject.WallSegment.builder()
                                .x1(toPixels(prev.path("x").asDouble(), pixelsPerGrid))
                                .y1(toPixels(prev.path("y").asDouble(), pixelsPerGrid))
                                .x2(toPixels(cur.path("x").asDouble(), pixelsPerGrid))
                                .y2(toPixels(cur.path("y").asDouble(), pixelsPerGrid))
                                .build());
                        prev = cur;
                    }
                }
            }
        }
    }

    private void parseLights(JsonNode root, DmProject project, double pixelsPerGrid) {
        JsonNode lights = root.path("lights");
        if (!lights.isArray()) {
            return;
        }
        for (JsonNode node : lights) {
            JsonNode position = node.path("position");
            double x = position.path("x").isNumber() ? position.path("x").asDouble() : node.path("x").asDouble();
            double y = position.path("y").isNumber() ? position.path("y").asDouble() : node.path("y").asDouble();
            project.getLighting().getLights().add(DmProject.LightSource.builder()
                    .id(node.path("id").asText("light-" + UUID.randomUUID()))
                    .x(toPixels(x, pixelsPerGrid))
                    .y(toPixels(y, pixelsPerGrid))
                    .range(toPixels(resolveDouble(node, "range", "distance", 2.0), pixelsPerGrid))
                    .color(parseDd2vttColor(node.path("color").asText(null)))
                    .intensity(resolveDouble(node, "intensity", "brightness", 1.0))
                    .castsShadows(node.path("shadows").asBoolean(true))
                    // Map lamps light the scene but must not uncover fog on their own.
                    .revealMode(DmProject.RevealMode.NONE)
                    // dd2vtt has no flicker data; map lamps default to the Torch flicker preset (depth 0 = off).
                    .flicker(DmProject.Flicker.builder().enabled(Tuning.DD2VTT_LIGHT_FLICKER.get() > 0)
                            .strength(Tuning.DD2VTT_LIGHT_FLICKER.get())
                            .speed(Tuning.DD2VTT_LIGHT_FLICKER_SPEED.get()).build())
                    .build());
        }
    }

    /** dd2vtt colors are AARRGGBB hex strings (e.g. "ffFFEDCF"). Returns "#RRGGBB". */
    static String parseDd2vttColor(String raw) {
        if (raw == null) {
            return "#FFD9A0";
        }
        String hex = raw.trim().replace("#", "");
        if (hex.length() == 8) {
            hex = hex.substring(2);
        }
        if (hex.length() != 6 || !hex.matches("[0-9a-fA-F]{6}")) {
            return "#FFD9A0";
        }
        return "#" + hex.toUpperCase(Locale.ROOT);
    }

    private void parsePortals(JsonNode root, DmProject project, double pixelsPerGrid) {
        JsonNode portals = root.path("portals");
        if (!portals.isArray()) {
            return;
        }
        Iterator<JsonNode> iterator = portals.iterator();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            String type = node.path("type").asText("door");
            JsonNode bounds = node.path("bounds");
            if (bounds.isArray() && bounds.size() == 2 && bounds.get(0).isObject()) {
                JsonNode a = bounds.get(0);
                JsonNode b = bounds.get(1);
                project.getInteractables().add(DmProject.Interactable.builder()
                        .id(node.path("id").asText("portal-" + UUID.randomUUID()))
                        .type(type)
                        .x1(toPixels(a.path("x").asDouble(), pixelsPerGrid))
                        .y1(toPixels(a.path("y").asDouble(), pixelsPerGrid))
                        .x2(toPixels(b.path("x").asDouble(), pixelsPerGrid))
                        .y2(toPixels(b.path("y").asDouble(), pixelsPerGrid))
                        .state(node.path("closed").asBoolean(true) ? "closed" : "open")
                        .blocksSightWhenClosed(true)
                        .build());
            } else if (bounds.isArray() && bounds.size() == 4) {
                project.getInteractables().add(DmProject.Interactable.builder()
                        .id(node.path("id").asText("portal-" + UUID.randomUUID()))
                        .type(type)
                        .x1(toPixels(bounds.get(0).asDouble(), pixelsPerGrid))
                        .y1(toPixels(bounds.get(1).asDouble(), pixelsPerGrid))
                        .x2(toPixels(bounds.get(2).asDouble(), pixelsPerGrid))
                        .y2(toPixels(bounds.get(3).asDouble(), pixelsPerGrid))
                        .state(node.path("closed").asBoolean(true) ? "closed" : "open")
                        .blocksSightWhenClosed(true)
                        .build());
            }
        }
    }

    private double resolvePixelsPerGrid(JsonNode root) {
        JsonNode resolutionNode = root.path("resolution");
        if (resolutionNode.isObject()) {
            JsonNode nested = resolutionNode.path("pixels_per_grid");
            if (nested.isNumber()) {
                return nested.asDouble();
            }
            nested = resolutionNode.path("pixelsPerGrid");
            if (nested.isNumber()) {
                return nested.asDouble();
            }
        }
        return resolveDouble(root, "pixels_per_grid", "pixelsPerGrid", 100);
    }

    private double toPixels(double gridUnits, double pixelsPerGrid) {
        return gridUnits * pixelsPerGrid;
    }

    private double resolveDouble(JsonNode node, String firstKey, String secondKey, double fallback) {
        JsonNode first = node.path(firstKey);
        if (first.isNumber()) {
            return first.asDouble();
        }
        JsonNode second = node.path(secondKey);
        if (second.isNumber()) {
            return second.asDouble();
        }
        return fallback;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return filename;
        }
        return filename.substring(0, dot);
    }
}
