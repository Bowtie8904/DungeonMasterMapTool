package dmmt.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import dmmt.model.DmProject;
import dmmt.model.FogMask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Apply only accepted domain targets; preserve unrelated object identities and the local DM camera. */
public final class RemoteProjectChanges {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RemoteProjectChanges() {
    }

    public static void apply(DmProject target, DmProject source, Set<String> targets) {
        if (!Objects.equals(target.getId(), source.getId())) {
            throw new IllegalArgumentException("A remote edit cannot replace the current map.");
        }
        for (String key : targets) {
            RemoteProjectDocument.validateTarget(key);
            String[] split = key.split(":", 2);
            String group = split[0], id = split[1];
            switch (group) {
                case "map" -> applyMap(target.getMap(), source.getMap(), id);
                case "views" -> applyView(target.getViews(), source.getViews(), id);
                case "lighting" -> applyLighting(target.getLighting(), source.getLighting(), id);
                case "project" -> applyProject(target, source, id);
                case "fog" -> {
                    switch (id) {
                        case "enabled" -> target.getFog().setEnabled(source.getFog().isEnabled());
                        case "maskResolution" -> target.getFog().setMaskResolution(source.getFog().getMaskResolution());
                        case "geometry" -> target.getFog().setMask(copy(source.getFog().getMask(), FogMask.class));
                        default -> throw new IllegalArgumentException("Unknown fog target.");
                    }
                }
                case "fogBits" -> {
                    if (!targets.contains("fog:geometry")) {
                        applyFogBlock(target.getFog().getMask(), source.getFog().getMask(), Integer.parseInt(id));
                    }
                }
                case "tokens" -> merge(target.getTokens(), source.getTokens(), id, DmProject.TokenInstance::getId,
                        DmProject.TokenInstance.class);
                case "imageLayers" -> merge(target.getImageLayers(), source.getImageLayers(), id,
                        DmProject.ImageLayer::getId, DmProject.ImageLayer.class);
                case "interactables" -> merge(target.getInteractables(), source.getInteractables(), id,
                        DmProject.Interactable::getId, DmProject.Interactable.class);
                case "overlays" -> merge(target.getOverlays(), source.getOverlays(), id, DmProject.OverlayShape::getId,
                        DmProject.OverlayShape.class);
                case "textBoxes" -> merge(target.getTextBoxes(), source.getTextBoxes(), id, DmProject.TextBox::getId,
                        DmProject.TextBox.class);
                case "lights" -> merge(target.getLighting().getLights(), source.getLighting().getLights(), id,
                        DmProject.LightSource::getId, DmProject.LightSource.class);
                case "tokenDefinitions" -> {
                    var definition = source.getTokenDefinitions().get(id);
                    if (definition == null) {
                        target.getTokenDefinitions().remove(id);
                    } else {
                        var existing = target.getTokenDefinitions().get(id);
                        if (existing == null) {
                            target.getTokenDefinitions().put(id, copy(definition, DmProject.TokenDefinition.class));
                        } else {
                            update(existing, definition);
                        }
                    }
                }
                case "walls" -> applyWall(target, source, id);
                default -> throw new IllegalArgumentException("Unknown project target.");
            }
        }
    }

    private static void applyMap(DmProject.MapInfo target, DmProject.MapInfo source, String field) {
        switch (field) {
            case "sourceType" -> target.setSourceType(source.getSourceType());
            case "imagePath" -> target.setImagePath(source.getImagePath());
            case "originalFileName" -> target.setOriginalFileName(source.getOriginalFileName());
            case "grid" -> update(target.getGrid(), source.getGrid());
            case "rotationQuarterTurns" -> target.setRotationQuarterTurns(source.getRotationQuarterTurns());
            case "imageLayersLocked" -> target.setImageLayersLocked(source.getImageLayersLocked());
            case "tags" -> target.setTags(new ArrayList<>(source.getTags()));
            default -> throw new IllegalArgumentException("Unknown map target.");
        }
    }

    private static void applyView(DmProject.ViewState target, DmProject.ViewState source, String field) {
        switch (field) {
            case "playerCamera" -> update(target.getPlayerCamera(), source.getPlayerCamera());
            case "playerFrozen" -> target.setPlayerFrozen(source.isPlayerFrozen());
            case "playerZoomStep" -> target.setPlayerZoomStep(source.getPlayerZoomStep());
            default -> throw new IllegalArgumentException("Unknown shared player-view target.");
        }
    }

    private static void applyLighting(DmProject.LightingState target, DmProject.LightingState source, String field) {
        switch (field) {
            case "timeOfDayPreset" -> target.setTimeOfDayPreset(source.getTimeOfDayPreset());
            case "lightTint" -> target.setLightTint(source.getLightTint());
            case "brightCore" -> target.setBrightCore(source.getBrightCore());
            case "ambientBrightness" -> target.setAmbientBrightness(new java.util.TreeMap<>(source.getAmbientBrightness()));
            default -> throw new IllegalArgumentException("Unknown lighting target.");
        }
    }

    private static void applyProject(DmProject target, DmProject source, String field) {
        switch (field) {
            case "schemaVersion" -> target.setSchemaVersion(source.getSchemaVersion());
            case "pings" -> target.setPings(copy(source.getPings(), DmProject.PingSettings.class));
            case "weather" -> update(target.getWeather(), source.getWeather());
            case "textLayerVisible" -> target.setTextLayerVisible(source.isTextLayerVisible());
            case "lastTextSettings" -> target.setLastTextSettings(copy(source.getLastTextSettings(), DmProject.TextSettings.class));
            default -> throw new IllegalArgumentException("Unknown project target.");
        }
    }

    private static <T> void merge(List<T> target, List<T> source, String id, Function<T, String> identity, Class<T> type) {
        T replacement = source.stream().filter(item -> id.equals(identity.apply(item))).findFirst().orElse(null);
        T existing = target.stream().filter(item -> id.equals(identity.apply(item))).findFirst().orElse(null);
        if (replacement == null) {
            target.removeIf(item -> id.equals(identity.apply(item)));
        } else if (existing == null) {
            target.add(copy(replacement, type));
        } else {
            update(existing, replacement);
        }
    }

    private static void applyWall(DmProject target, DmProject source, String id) {
        int separator = id.lastIndexOf('-');
        String content = id.substring(0, separator);
        List<DmProject.WallSegment> existing = target.getWalls().stream()
                .filter(wall -> RemoteProjectDocument.wallIdentity(wall).equals(content)).toList();
        long wanted = source.getWalls().stream()
                .filter(wall -> RemoteProjectDocument.wallIdentity(wall).equals(content)).count();
        for (int index = existing.size() - 1; index >= wanted; index--) {
            target.getWalls().remove(existing.get(index));
        }
        if (existing.size() < wanted) {
            var replacement = source.getWalls().stream()
                    .filter(wall -> RemoteProjectDocument.wallIdentity(wall).equals(content)).findFirst().orElseThrow();
            for (int index = existing.size(); index < wanted; index++) {
                target.getWalls().add(copy(replacement, DmProject.WallSegment.class));
            }
        }
    }

    private static void applyFogBlock(FogMask target, FogMask source, int block) {
        if (target == null || source == null || target.getCols() != source.getCols() || target.getRows() != source.getRows()
                || target.getCellSize() != source.getCellSize() || target.getOriginX() != source.getOriginX()
                || target.getOriginY() != source.getOriginY()) {
            throw new IllegalArgumentException("Fog geometry changed before the block could be applied.");
        }
        int start = Math.multiplyExact(block, RemoteProjectDocument.FOG_BLOCK_BITS);
        int end = (int) Math.min((long) start + RemoteProjectDocument.FOG_BLOCK_BITS, (long) target.getCols() * target.getRows());
        int[] revealed = java.util.stream.IntStream.range(start, end).filter(source::isRevealedCell).toArray();
        int[] hidden = java.util.stream.IntStream.range(start, end).filter(cell -> !source.isRevealedCell(cell)).toArray();
        target.applyCells(hidden, false);
        target.applyCells(revealed, true);
    }

    private static <T> T copy(T value, Class<T> type) {
        return value == null ? null : JSON.convertValue(value, type);
    }

    private static void update(Object existing, Object replacement) {
        try {
            JSON.readerForUpdating(existing).readValue(JSON.writeValueAsBytes(replacement));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Cannot apply the validated project target.", invalid);
        }
    }
}
