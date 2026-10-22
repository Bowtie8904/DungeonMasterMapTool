package dmmt.remote;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.model.DmProject;
import dmmt.model.FogMask;

import java.io.IOException;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Portable committed map data split by domain target. DM navigation, selection and transient pings
 * are never part of a document. Fog is uncompressed bounded blocks, not peer-supplied zip streams.
 */
public final class RemoteProjectDocument {
    public static final int MAX_TARGETS = 100_000;
    public static final int MAX_FOG_CELLS = 64 * 1024 * 1024;
    public static final int FOG_BLOCK_BITS = 4096;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final Set<String> ENTITY_COLLECTIONS = Set.of(
            "imageLayers", "interactables", "overlays", "textBoxes", "tokens", "lights", "tokenDefinitions", "walls");
    private static final Map<String, Set<String>> PROPERTIES = Map.of(
            "map", Set.of("sourceType", "imagePath", "originalFileName", "grid", "rotationQuarterTurns",
                    "imageLayersLocked", "tags"),
            "views", Set.of("playerCamera", "playerFrozen", "playerZoomStep"),
            "fog", Set.of("enabled", "maskResolution", "geometry"),
            "lighting", Set.of("timeOfDayPreset", "lightTint", "brightCore", "ambientBrightness"),
            "project", Set.of("schemaVersion", "pings", "weather", "textLayerVisible", "lastTextSettings"));

    private final String projectId;
    private final Map<String, JsonNode> parts;

    @JsonCreator
    public RemoteProjectDocument(@JsonProperty("projectId") String projectId,
                                 @JsonProperty("parts") Map<String, JsonNode> parts) {
        this.projectId = requireId(projectId);
        Objects.requireNonNull(parts, "parts");
        if (parts.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("The remote project exceeds the target limit.");
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        parts.forEach((key, value) -> {
            validateTarget(key);
            Objects.requireNonNull(value, "target value");
            validateJson(value, 0);
            if (key.startsWith("fogBits:")) {
                requireFogBlock(value);
            }
            copy.put(key, value.deepCopy());
        });
        this.parts = Collections.unmodifiableMap(copy);
    }

    @JsonProperty
    public String projectId() {
        return projectId;
    }

    @JsonProperty
    public Map<String, JsonNode> parts() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        parts.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    JsonNode value(String key) {
        JsonNode value = parts.get(key);
        return value == null ? null : value.deepCopy();
    }

    Set<String> targets() {
        return parts.keySet();
    }

    public static RemoteProjectDocument capture(DmProject project) {
        Objects.requireNonNull(project, "project");
        Map<String, JsonNode> parts = new LinkedHashMap<>();
        addProperties(parts, "map", JSON.valueToTree(project.getMap()));
        addProperties(parts, "views", JSON.valueToTree(project.getViews()));
        parts.put("lighting:timeOfDayPreset", JSON.valueToTree(project.getLighting().getTimeOfDayPreset()));
        parts.put("lighting:lightTint", JSON.valueToTree(project.getLighting().getLightTint()));
        parts.put("lighting:brightCore", JSON.valueToTree(project.getLighting().getBrightCore()));
        parts.put("lighting:ambientBrightness", JSON.valueToTree(project.getLighting().getAmbientBrightness()));
        parts.put("fog:enabled", JSON.valueToTree(project.getFog().isEnabled()));
        parts.put("fog:maskResolution", JSON.valueToTree(project.getFog().getMaskResolution()));
        parts.put("project:schemaVersion", JSON.valueToTree(project.getSchemaVersion()));
        parts.put("project:pings", JSON.valueToTree(project.getPings()));
        parts.put("project:weather", JSON.valueToTree(project.getWeather()));
        parts.put("project:textLayerVisible", JSON.valueToTree(project.isTextLayerVisible()));
        parts.put("project:lastTextSettings", JSON.valueToTree(project.getLastTextSettings()));
        addEntities(parts, "imageLayers", project.getImageLayers(), DmProject.ImageLayer::getId);
        addEntities(parts, "interactables", project.getInteractables(), DmProject.Interactable::getId);
        addEntities(parts, "overlays", project.getOverlays(), DmProject.OverlayShape::getId);
        addEntities(parts, "textBoxes", project.getTextBoxes(), DmProject.TextBox::getId);
        addEntities(parts, "tokens", project.getTokens(), DmProject.TokenInstance::getId);
        addEntities(parts, "lights", project.getLighting().getLights(), DmProject.LightSource::getId);
        project.getTokenDefinitions().forEach((id, definition) -> putUnique(parts, "tokenDefinitions:" + requireId(id),
                JSON.valueToTree(definition)));
        // Legacy walls have no IDs. Content identities let independent additions/removals merge safely.
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (var wall : project.getWalls()) {
            String key = wallIdentity(wall);
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            putUnique(parts, "walls:" + key + "-" + occurrence, JSON.valueToTree(wall));
        }
        FogMask mask = project.getFog().getMask();
        if (mask != null) {
            validateFogGeometry(mask.getCols(), mask.getRows(), mask.getCellSize(), mask.getOriginX(), mask.getOriginY());
            parts.put("fog:geometry", JSON.valueToTree(new FogGeometry(mask.getOriginX(), mask.getOriginY(),
                    mask.getCellSize(), mask.getCols(), mask.getRows())));
            BitSet bits = mask.copyBits();
            int blocks = (int) (((long) mask.getCols() * mask.getRows() + FOG_BLOCK_BITS - 1) / FOG_BLOCK_BITS);
            for (int block = 0; block < blocks; block++) {
                BitSet content = bits.get(block * FOG_BLOCK_BITS, (block + 1) * FOG_BLOCK_BITS);
                if (!content.isEmpty()) {
                    parts.put("fogBits:" + block, JSON.valueToTree(
                            Base64.getEncoder().encodeToString(content.toByteArray())));
                }
            }
        }
        return new RemoteProjectDocument(project.getId(), parts);
    }

    /** Rebuild a validated candidate. Caller merges it into live state only after all checks succeed. */
    public DmProject materialize(DmProject.CameraState localDmCamera) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", projectId);
        ObjectNode map = root.putObject("map");
        ObjectNode views = root.putObject("views");
        views.set("dmCamera", JSON.valueToTree(Objects.requireNonNull(localDmCamera, "localDmCamera")));
        ObjectNode lighting = root.putObject("lighting");
        ObjectNode fog = root.putObject("fog");
        Map<String, Set<Integer>> wallOccurrences = new LinkedHashMap<>();
        for (var entry : parts.entrySet()) {
            String[] target = entry.getKey().split(":", 2);
            String group = target[0], id = target[1];
            JsonNode value = entry.getValue();
            switch (group) {
                case "map" -> map.set(id, value);
                case "views" -> views.set(id, value);
                case "lighting" -> lighting.set(id, value);
                case "project" -> root.set(id, value);
                case "fog" -> {
                    if (!id.equals("geometry")) {
                        fog.set(id, value);
                    }
                }
                case "fogBits" -> { }
                case "tokenDefinitions" -> {
                    requireEntityIdentity(id, value);
                    root.withObject("/tokenDefinitions").set(id, value);
                }
                default -> {
                    if (group.equals("walls")) {
                        DmProject.WallSegment wall = JSON.treeToValue(value, DmProject.WallSegment.class);
                        if (wall == null) {
                            throw new IllegalArgumentException("A wall target must contain geometry.");
                        }
                        String identity = wallIdentity(wall);
                        if (!id.startsWith(identity + "-")) {
                            throw new IllegalArgumentException("The wall geometry does not match its target.");
                        }
                        wallOccurrences.computeIfAbsent(identity, key -> new java.util.HashSet<>())
                                .add(Integer.parseInt(id.substring(identity.length() + 1)));
                    } else {
                        requireEntityIdentity(id, value);
                    }
                    ObjectNode owner = group.equals("lights") ? lighting : root;
                    String collection = group.equals("lights") ? "lights" : group;
                    owner.withArray(collection).add(value);
                }
            }
        }
        wallOccurrences.values().forEach(occurrences -> {
            for (int occurrence = 1; occurrence <= occurrences.size(); occurrence++) {
                if (!occurrences.contains(occurrence)) {
                    throw new IllegalArgumentException("Legacy wall occurrence identities must be contiguous.");
                }
            }
        });
        DmProject project = JSON.treeToValue(root, DmProject.class);
        requireProjectStructure(project);
        JsonNode geometry = parts.get("fog:geometry");
        if (geometry != null && !geometry.isNull()) {
            FogGeometry bounds = JSON.treeToValue(geometry, FogGeometry.class);
            validateFogGeometry(bounds.cols(), bounds.rows(), bounds.cellSize(), bounds.originX(), bounds.originY());
            FogMask mask = new FogMask(bounds.originX(), bounds.originY(), bounds.cellSize(), bounds.cols(), bounds.rows());
            int cells = Math.multiplyExact(bounds.cols(), bounds.rows());
            for (var entry : parts.entrySet()) {
                if (!entry.getKey().startsWith("fogBits:")) {
                    continue;
                }
                int block = Integer.parseInt(entry.getKey().substring("fogBits:".length()));
                int offset = Math.multiplyExact(block, FOG_BLOCK_BITS);
                BitSet revealed = BitSet.valueOf(requireFogBlock(entry.getValue()));
                if (offset >= cells || offset + revealed.length() > cells) {
                    throw new IllegalArgumentException("Remote fog content exceeds its geometry.");
                }
                mask.applyCells(revealed.stream().map(bit -> bit + offset).toArray(), true);
            }
            project.getFog().setMask(mask);
        } else if (parts.keySet().stream().anyMatch(key -> key.startsWith("fogBits:"))) {
            throw new IllegalArgumentException("Remote fog content has no geometry.");
        }
        return project;
    }

    public RemoteProjectDocument with(Map<String, JsonNode> replacements, Set<String> removals) {
        Map<String, JsonNode> next = new LinkedHashMap<>(parts);
        removals.forEach(next::remove);
        next.putAll(replacements);
        return new RemoteProjectDocument(projectId, next);
    }

    public Set<String> assetReferences() {
        Set<String> references = new java.util.LinkedHashSet<>();
        rewriteAssets(reference -> {
            references.add(reference);
            return reference;
        });
        return Set.copyOf(references);
    }

    /** Rewrite only recognized artwork fields, never unrelated user text or source metadata. */
    public RemoteProjectDocument rewriteAssets(java.util.function.UnaryOperator<String> rewrite) {
        Objects.requireNonNull(rewrite, "rewrite");
        Map<String, JsonNode> next = parts();
        Map<String, JsonNode> replacements = new LinkedHashMap<>();
        next.forEach((target, value) -> {
            if (target.equals("map:imagePath")) {
                if (!value.isNull() && !value.isTextual()) {
                    throw new IllegalArgumentException("A map artwork reference must be text.");
                }
                if (value.isTextual() && !value.textValue().isBlank()) {
                    replacements.put(target, JSON.valueToTree(Objects.requireNonNull(rewrite.apply(value.textValue()))));
                }
            } else if (target.startsWith("imageLayers:") || target.startsWith("tokenDefinitions:")) {
                if (!(value instanceof ObjectNode entity)) {
                    throw new IllegalArgumentException("An artwork-bearing entity must be an object.");
                }
                for (String field : target.startsWith("imageLayers:") ? List.of("path") : List.of("image", "sourceImage")) {
                    JsonNode path = entity.get(field);
                    if (path != null && !path.isNull() && !path.isTextual()) {
                        throw new IllegalArgumentException("An artwork reference must be text.");
                    }
                    if (path != null && path.isTextual() && !path.textValue().isBlank()) {
                        entity.put(field, Objects.requireNonNull(rewrite.apply(path.textValue())));
                    }
                }
                replacements.put(target, entity);
            }
        });
        return with(replacements, Set.of());
    }

    public void validatePortableAssets() {
        for (String reference : assetReferences()) {
            if (!reference.matches("asset:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Remote artwork must use a published content-addressed asset.");
            }
        }
    }

    public record FogGeometry(double originX, double originY, double cellSize, int cols, int rows) {
    }

    public static void validateTarget(String target) {
        if (target == null || target.length() > 300 || target.indexOf(':') < 1) {
            throw new IllegalArgumentException("A bounded domain target is required.");
        }
        String[] split = target.split(":", 2);
        String group = split[0], key = split[1];
        if (group.equals("walls")) {
            if (!key.matches("(?:[0-9a-f]{1,16}\\.){3}[0-9a-f]{1,16}-[1-9][0-9]{0,5}")) {
                throw new IllegalArgumentException("Invalid legacy wall identity.");
            }
        } else if (ENTITY_COLLECTIONS.contains(group)) {
            requireId(key);
        } else if (group.equals("fogBits")) {
            if (!key.matches("0|[1-9][0-9]{0,4}") || Integer.parseInt(key) >= MAX_FOG_CELLS / FOG_BLOCK_BITS) {
                throw new IllegalArgumentException("Invalid fog block target.");
            }
        } else if (!PROPERTIES.getOrDefault(group, Set.of()).contains(key)) {
            throw new IllegalArgumentException("Unknown remote project target: " + target);
        }
    }

    private static void addProperties(Map<String, JsonNode> parts, String group, JsonNode source) {
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("Missing remote project component: " + group);
        }
        for (String field : PROPERTIES.get(group)) {
            parts.put(group + ":" + field, source.path(field));
        }
    }

    private static <T> void addEntities(Map<String, JsonNode> parts, String group, List<T> entities,
                                        java.util.function.Function<T, String> identity) {
        Objects.requireNonNull(entities, group);
        for (T entity : entities) {
            putUnique(parts, group + ":" + requireId(identity.apply(entity)), JSON.valueToTree(entity));
        }
    }

    private static void putUnique(Map<String, JsonNode> parts, String key, JsonNode value) {
        if (parts.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("Duplicate remote project identity: " + key);
        }
    }

    private static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,200}")) {
            throw new IllegalArgumentException("A valid stable project/object ID is required.");
        }
        return value;
    }

    private static void requireEntityIdentity(String id, JsonNode value) {
        if (!value.isObject() || !id.equals(value.path("id").asText())) {
            throw new IllegalArgumentException("The object ID does not match its remote target.");
        }
    }

    private static void validateJson(JsonNode node, int depth) {
        if (depth > 24 || node.isMissingNode() || node.isBinary() || node.isPojo()) {
            throw new IllegalArgumentException("Unsupported remote project value.");
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("Remote project numbers must be finite.");
        }
        if (node.isTextual() && node.textValue().length() > RemoteWire.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Remote project text exceeds the limit.");
        }
        node.forEach(child -> validateJson(child, depth + 1));
    }

    private static byte[] requireFogBlock(JsonNode value) {
        if (!value.isTextual() || value.textValue().length() > 684) {
            throw new IllegalArgumentException("Remote fog block exceeds its limit.");
        }
        byte[] bits = Base64.getDecoder().decode(value.textValue());
        if (bits.length > FOG_BLOCK_BITS / 8) {
            throw new IllegalArgumentException("Remote fog block exceeds its limit.");
        }
        return bits;
    }

    private static void validateFogGeometry(int cols, int rows, double size, double x, double y) {
        if (cols < 1 || rows < 1 || (long) cols * rows > MAX_FOG_CELLS
                || !Double.isFinite(size) || size <= 0 || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Invalid or oversized remote fog geometry.");
        }
    }

    private static void requireProjectStructure(DmProject project) {
        if (project.getMap() == null || project.getMap().getGrid() == null || project.getViews() == null
                || project.getViews().getPlayerCamera() == null || project.getFog() == null
                || project.getLighting() == null || project.getLighting().getLights() == null
                || project.getWeather() == null || project.getPings() == null
                || project.getLighting().getAmbientBrightness() == null
                || project.getMap().getGrid().getPixelsPerCell() <= 0
                || project.getMap().getGrid().getCellSizeFeet() <= 0
                || project.getFog().getMaskResolution() <= 0
                || project.getViews().getPlayerCamera().getZoom() <= 0) {
            throw new IllegalArgumentException("Invalid remote project structure or geometry.");
        }
        for (var layer : project.getImageLayers()) {
            if (layer.getWidth() < 0 || layer.getHeight() < 0) {
                throw new IllegalArgumentException("Remote image dimensions cannot be negative.");
            }
        }
        for (var token : project.getTokens()) {
            if (token.getDefinitionId() == null || !project.getTokenDefinitions().containsKey(token.getDefinitionId())
                    || token.getSizeTiles() <= 0) {
                throw new IllegalArgumentException("Remote token references an unavailable definition or invalid size.");
            }
        }
        for (var definition : project.getTokenDefinitions().values()) {
            if (definition.getShape() == null || definition.getSizeTiles() <= 0 || definition.getZoom() <= 0) {
                throw new IllegalArgumentException("Invalid remote token artwork geometry.");
            }
        }
        for (var shape : project.getOverlays()) {
            if (shape.getPoints() == null || shape.getPoints().size() % 2 != 0
                    || shape.getPoints().stream().anyMatch(Objects::isNull)
                    || shape.getWidth() < 0 || shape.getHeight() < 0 || shape.getRadius() < 0
                    || shape.getStrokeWidth() < 0) {
                throw new IllegalArgumentException("Invalid remote effect geometry.");
            }
        }
        for (var text : project.getTextBoxes()) {
            if (text.getRuns() == null || text.getWidth() < 0 || text.getHeight() < 0
                    || text.getRuns().stream().anyMatch(run -> run == null || run.getFontSize() < 1)) {
                throw new IllegalArgumentException("Invalid remote text geometry.");
            }
        }
        if (project.getLighting().getAmbientBrightness().values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid remote ambient brightness.");
        }
    }

    static String wallIdentity(DmProject.WallSegment wall) {
        // Preserve exact doubles, without locale-dependent text or order-dependent indexes.
        return Long.toHexString(Double.doubleToLongBits(wall.getX1())) + "."
                + Long.toHexString(Double.doubleToLongBits(wall.getY1())) + "."
                + Long.toHexString(Double.doubleToLongBits(wall.getX2())) + "."
                + Long.toHexString(Double.doubleToLongBits(wall.getY2()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemoteProjectDocument document
                && projectId.equals(document.projectId) && parts.equals(document.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, parts);
    }
}
