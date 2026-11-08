package dmmt.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dmmt.lighting.TimeOfDayPreset;
import dmmt.model.DmProject;
import dmmt.model.FogMask;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON state sent to browser clients (protocol 3.41.4). Hidden tokens are never included. The state is a
 * pure function of the project, so it can be diffed and tested without JavaFX.
 */
public final class OnlineStateBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int VISION_TILES = 8;

    private FogMask cachedMask;
    private long cachedVersion = -1;
    private String cachedEncoded = "";
    private final java.util.function.Function<Path, dmmt.render.ImagePyramidStore.Pyramid> pyramids;

    public OnlineStateBuilder() {
        this(file -> dmmt.render.ImagePyramidStore.shared().pyramid(file));
    }

    /** {@code pyramids} returns the finished desktop tile pyramid of a map image, or null. */
    public OnlineStateBuilder(java.util.function.Function<Path, dmmt.render.ImagePyramidStore.Pyramid> pyramids) {
        this.pyramids = pyramids;
    }

    /** Large images get the desktop tile pyramid so browsers can show them at full resolution. */
    private void putPyramid(ObjectNode node, Path file, AssetRegistry assets) {
        dmmt.render.ImagePyramidStore.Pyramid pyramid = file == null ? null : pyramids.apply(file);
        if (pyramid == null) {
            return;
        }
        String key = assets.registerPyramid(pyramid.dir(), pyramid.meta());
        if (key == null) {
            return;
        }
        dmmt.render.ImagePyramidBuilder.Meta meta = pyramid.meta();
        node.putObject("pyramid").put("key", key).put("width", meta.width()).put("height", meta.height())
                .put("tileSize", meta.tileSize()).put("levels", meta.overviewLevel()).put("format", meta.format());
    }

    public record Context(String mapId, String levelId, String mapName, Path projectFile, int weatherSeed,
                          boolean paused, boolean showGrid) {
    }

    /** Builds the player-independent state; per-client fields ({@code fogMemory}) are added by the server. */
    public synchronized ObjectNode build(DmProject project, Context ctx, Campaign campaign, AssetRegistry assets,
                                         List<JsonNode> chatTail) {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("mapId", ctx.mapId());
        state.put("levelId", ctx.levelId());
        state.put("mapName", ctx.mapName() == null ? "" : ctx.mapName());
        double ppc = project.getMap().getGrid().getPixelsPerCell();
        state.putObject("grid").put("pixelsPerCell", ppc);

        ArrayNode images = state.putArray("images");
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (DmProject.ImageLayer layer : project.getImageLayers()) {
            if (!layer.isVisible() || layer.getPath() == null || layer.getPath().isBlank()) {
                continue;
            }
            String url = assets.register(resolve(layer.getPath(), ctx.projectFile()), true);
            if (url == null) {
                continue;
            }
            ObjectNode node = images.addObject();
            node.put("id", String.valueOf(layer.getId()));
            node.put("hash", url.substring(3, url.indexOf('.')));
            node.put("url", url);
            node.put("x", layer.getX());
            node.put("y", layer.getY());
            node.put("width", layer.getWidth());
            node.put("height", layer.getHeight());
            node.put("rotationDeg", layer.getRotationDeg());
            node.put("zIndex", layer.getZIndex());
            putPyramid(node, resolve(layer.getPath(), ctx.projectFile()), assets);
            minX = Math.min(minX, layer.getX());
            minY = Math.min(minY, layer.getY());
            maxX = Math.max(maxX, layer.getX() + layer.getWidth());
            maxY = Math.max(maxY, layer.getY() + layer.getHeight());
        }

        ArrayNode walls = state.putArray("walls");
        for (DmProject.WallSegment wall : project.getWalls()) {
            walls.add(wall.getX1()).add(wall.getY1()).add(wall.getX2()).add(wall.getY2());
        }
        ArrayNode doors = state.putArray("doors");
        for (DmProject.Interactable door : project.getInteractables()) {
            ObjectNode node = doors.addObject();
            node.put("id", String.valueOf(door.getId()));
            node.put("x1", door.getX1()).put("y1", door.getY1()).put("x2", door.getX2()).put("y2", door.getY2());
            node.put("open", isOpen(door));
            node.put("kind", door.getType());
            node.put("locked", door.isLocked());
        }
        ArrayNode lights = state.putArray("lights");
        for (DmProject.LightSource light : project.effectiveLights()) {
            ObjectNode node = lights.addObject();
            node.put("id", String.valueOf(light.getId()));
            node.put("x", light.getX()).put("y", light.getY()).put("range", light.getRange());
            node.put("brightness", light.getIntensity());
            node.put("color", light.getColor());
            node.put("on", light.isEnabled());
            node.put("revealMode", light.getRevealMode() == null ? "WHILE_LIT" : light.getRevealMode().name());
            if (light.getFlicker() != null && light.getFlicker().isEnabled()) {
                node.putObject("flicker").put("strength", light.getFlicker().getStrength())
                        .put("speed", light.getFlicker().getSpeed());
            }
        }
        TimeOfDayPreset preset = TimeOfDayPreset.from(project.getLighting().getTimeOfDayPreset());
        double ambient = project.getLighting().ambientBrightnessFor(preset.name());
        ObjectNode lighting = state.putObject("lighting");
        lighting.put("ambient", 1.0 - preset.darkness(ambient));
        lighting.put("timeOfDay", preset.name());
        lighting.put("brightCore", dmmt.render.CanvasMapRenderer.clampBrightCore(project.getLighting().getBrightCore()));
        lighting.put("lightTint", dmmt.render.CanvasMapRenderer.clampLightTint(project.getLighting().getLightTint()));
        lighting.put("tintDarknessFactor", dmmt.service.Tuning.DM_DARKNESS_FACTOR.get());
        lighting.put("brightCoreRadius", dmmt.service.Tuning.BRIGHT_CORE_RADIUS.get());
        lighting.put("flicker", dmmt.render.CanvasMapRenderer.flickerOn(project));
        lighting.putArray("tint").add(preset.red()).add(preset.green()).add(preset.blue());
        state.putObject("view").put("gridOpacity", dmmt.service.Tuning.GRID_OPACITY.get());
        ObjectNode view = (ObjectNode) state.get("view");
        view.put("showGrid", ctx.showGrid());
        view.put("fogSoftness", dmmt.render.CanvasMapRenderer.getFogSoftness());
        view.put("fogFade", dmmt.render.CanvasMapRenderer.isFogFadeEnabled());
        view.put("fogEnabled", project.getFog() == null || project.getFog().isEnabled());
        view.put("fogRevealSeconds", dmmt.service.Tuning.FOG_REVEAL_SECONDS.get());
        view.put("fogHideSeconds", dmmt.service.Tuning.FOG_HIDE_SECONDS.get());
        view.putObject("ping").put("color", dmmt.service.Tuning.PING_COLOR.get())
                .put("startRadius", dmmt.service.Tuning.PING_START_RADIUS.get())
                .put("grow", dmmt.service.Tuning.PING_GROW.get());
        ArrayNode pings = state.putArray("pings");
        long nowMillis = System.currentTimeMillis();
        for (DmProject.PingEvent ping : List.copyOf(project.getActivePings())) {
            if (ping.getCreatedAtMillis() + ping.getDurationMillis() > nowMillis) {
                pings.addObject().put("x", ping.getX()).put("y", ping.getY())
                        .put("startMs", ping.getCreatedAtMillis()).put("durationMs", ping.getDurationMillis());
            }
        }
        state.set("indoor", indoor(project, preset));
        ArrayNode textures = state.putArray("effectTextures");
        for (String kind : dmmt.render.OverlayTextures.KINDS) {
            textures.addObject().put("id", kind).put("label", dmmt.render.OverlayTextures.label(kind))
                    .put("color", dmmt.render.OverlayTextures.defaultColor(kind))
                    .put("emits", dmmt.render.OverlayTextures.defaultEmitsLight(kind))
                    .put("above", dmmt.render.OverlayTextures.defaultAboveTokens(kind));
        }
        state.put("penWidthCells", dmmt.service.Tuning.PEN_WIDTH_CELLS.get());
        if (campaign != null) {
            state.put("fear", campaign.getFear());
            ArrayNode countdowns = state.putArray("countdowns");
            for (Campaign.Countdown countdown : campaign.getCountdowns()) {
                countdowns.addObject().put("id", countdown.id()).put("name", countdown.name())
                        .put("initialValue", countdown.initialValue()).put("value", countdown.value());
            }
        }
        ArrayNode overlays = state.putArray("overlays");
        for (DmProject.OverlayShape shape : project.getOverlays()) {
            if (!shape.isPlayerVisible()) {
                continue;
            }
            ObjectNode node = overlays.addObject();
            node.put("id", shape.getId()).put("type", shape.getType());
            node.put("ownerId", shape.getOwnerId());
            node.put("ownerCampaignId", shape.getOwnerCampaignId());
            node.put("x", shape.getX()).put("y", shape.getY()).put("width", shape.getWidth());
            node.put("height", shape.getHeight()).put("radius", shape.getRadius());
            node.put("strokeWidth", shape.getStrokeWidth()).put("color", shape.getColor());
            node.put("alpha", shape.getAlpha()).put("texture", shape.getTexture()).put("border", shape.isBorder());
            node.put("above", dmmt.render.OverlayTextures.isAboveTokens(shape));
            String texture = dmmt.render.OverlayTextures.normalize(shape.getTexture());
            if (dmmt.render.OverlayTextures.isAnimated(texture)) {
                String tile = assets.registerTexture(texture, rgbOf(shape.getColor()));
                if (tile != null && !tile.isEmpty()) {
                    node.put("tile", tile);
                    node.put("soft", dmmt.render.OverlayTextures.isSoft(texture));
                    node.put("tileCells", dmmt.render.OverlayTextures.tileCells());
                    node.put("featherCells", dmmt.render.OverlayTextures.featherCells());
                    node.put("featherPasses", dmmt.render.OverlayTextures.featherPasses());
                    ArrayNode layerNodes = node.putArray("layers");
                    for (dmmt.render.OverlayTextures.Layer layer : dmmt.render.OverlayTextures.layers(texture)) {
                        layerNodes.addArray().add(layer.vx()).add(layer.vy()).add(layer.scale()).add(layer.alpha())
                                .add(layer.pulse()).add(layer.pulseHz());
                    }
                }
            }
            if (shape.isEmitsLight() && !"pen".equals(shape.getType()) && !"line".equals(shape.getType())) {
                node.put("emits", true);
                node.put("lightStrength", dmmt.render.OverlayTextures.lightStrength(texture));
                node.put("lightRange", dmmt.render.OverlayTextures.lightRangeCells(texture));
                node.put("flicker", dmmt.render.OverlayTextures.lightFlicker(texture));
                node.put("flickerSpeed", dmmt.render.OverlayTextures.lightFlickerSpeed(texture));
            }
            ArrayNode points = node.putArray("points");
            if (shape.getPoints() != null) {
                shape.getPoints().forEach(points::add);
            }
        }
        ArrayNode texts = state.putArray("texts");
        if (project.isTextLayerVisible()) {
            for (DmProject.TextBox box : project.getTextBoxes()) {
                if (!box.isPlayerVisible()) {
                    continue;
                }
                ObjectNode node = texts.addObject();
                node.put("id", box.getId());
                node.put("x", box.getX()).put("y", box.getY()).put("width", box.getWidth()).put("height", box.getHeight());
                node.put("background", box.getBackgroundColor()).put("border", box.getBorderColor());
                ArrayNode runs = node.putArray("runs");
                for (DmProject.TextRun run : box.getRuns()) {
                    runs.addObject().put("text", run.getText() == null ? "" : run.getText())
                            .put("size", run.getFontSize()).put("color", run.getColor());
                }
            }
        }
        DmProject.WeatherState weather = project.getWeather();
        ObjectNode weatherNode = state.putObject("weather");
        weatherNode.put("type", weather.getType());
        weatherNode.put("intensity", weather.getIntensity());
        weatherNode.put("lightningIntervalSeconds", weather.getLightningIntervalSeconds());
        weatherNode.put("seed", ctx.weatherSeed());
        dmmt.render.WeatherType weatherType = dmmt.render.WeatherType.from(weather.getType());
        if (weatherType != dmmt.render.WeatherType.NONE) {
            weatherNode.put("indoors", weatherType.indoors());
            weatherNode.put("particles", weatherType.maxParticles());
            weatherNode.put("color", dmmt.service.Tuning.weather(weatherType.key()).color().get());
            weatherNode.put("opacity", weatherType.opacity());
            ObjectNode flash = weatherNode.putObject("flash");
            flash.put("duration", dmmt.service.Tuning.WEATHER_FLASH_DURATION.get());
            flash.put("opacity", dmmt.service.Tuning.WEATHER_FLASH_OPACITY.get());
            flash.put("brightness", dmmt.service.Tuning.WEATHER_FLASH_BRIGHTNESS.get());
            flash.put("color", dmmt.service.Tuning.WEATHER_FLASH_COLOR.get());
        }

        state.set("tokens", tokens(project, campaign, assets, ctx.mapId()));

        FogMask mask = project.getFog() == null ? null : project.getFog().getMask();
        if (mask != null) {
            ObjectNode fog = state.putObject("dmFog");
            fog.put("originX", mask.getOriginX()).put("originY", mask.getOriginY());
            fog.put("cellSize", mask.getCellSize()).put("cols", mask.getCols()).put("rows", mask.getRows());
            fog.put("revealed", encodedFor(mask));
            state.putNull("fogGrid");
        } else {
            state.putNull("dmFog");
            ObjectNode grid = state.putObject("fogGrid");
            double cell = Math.max(1, ppc * 0.25);
            if (minX > maxX) {
                minX = 0;
                minY = 0;
                maxX = ppc * 20;
                maxY = ppc * 20;
            }
            grid.put("originX", minX).put("originY", minY).put("cellSize", cell);
            grid.put("cols", (int) Math.ceil((maxX - minX) / cell)).put("rows", (int) Math.ceil((maxY - minY) / cell));
        }
        state.putNull("fogMemory");
        ArrayNode actors = state.putArray("actors");
        if (campaign != null) {
            for (Campaign.Actor actor : campaign.getActors()) {
                actors.add(actorNode(actor, campaign, assets));
            }
        }
        state.put("paused", ctx.paused());
        state.put("visionTiles", VISION_TILES);
        ArrayNode tail = state.putArray("chatTail");
        if (chatTail != null) {
            chatTail.forEach(tail::add);
        }
        return state;
    }

    public ObjectNode actorNode(Campaign.Actor actor, Campaign campaign, AssetRegistry assets) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", actor.getId());
        node.put("name", actor.getName());
        node.put("ownerId", actor.getOwnerId() == null ? "" : actor.getOwnerId());
        node.put("disabled", actor.isDisabled());
        node.put("testingCharacter", actor.isTestingCharacter());
        String art = artworkUrl(actor, campaign, assets);
        if (art != null) {
            node.put("artwork", art);
        }
        return node;
    }

    private ArrayNode tokens(DmProject project, Campaign campaign, AssetRegistry assets, String mapId) {
        ArrayNode tokens = MAPPER.createArrayNode();
        Map<String, DmProject.TokenDefinition> defs = project.getTokenDefinitions() == null
                ? Map.of() : project.getTokenDefinitions();
        for (DmProject.TokenInstance token : project.getTokens()) {
            if (!token.isPlayerVisible()) {
                continue;
            }
            ObjectNode node = tokens.addObject();
            node.put("id", token.getId());
            node.put("x", token.getX()).put("y", token.getY());
            node.put("sizeTiles", token.getSizeTiles());
            node.put("rotationDeg", token.getRotationDeg());
            DmProject.TokenDefinition def = defs.get(token.getDefinitionId());
            Campaign.Actor actor = actorOf(token, campaign);
            if (token.isNameVisible() && def != null && def.getName() != null) {
                node.put("name", def.getName());
            } else if (actor != null) {
                node.put("name", actor.getName());
            }
            String image = actor != null ? artworkUrl(actor, campaign, assets) : null;
            boolean ownArtwork = image != null;
            if (image == null && def != null && def.getImage() != null) {
                image = assets.register(fileOf(def.getImage()), false);
            }
            if (def != null) {
                node.put("color", def.getColor());
                node.put("shape", def.getShape() == DmProject.TokenShape.SQUARE ? "square" : "circle");
                node.put("border", def.isBorder());
                if (image != null && !ownArtwork) {
                    node.put("zoom", def.getZoom()).put("centerX", def.getCenterX()).put("centerY", def.getCenterY());
                }
            }
            if (image != null) {
                node.put("image", image);
            }
            if (actor != null && actor.getTokenArtwork() != null) {
                node.put("shape", "circle").put("border", false);
                node.put("color", actor.getTokenArtwork().getColor());
            }
            if (actor != null) {
                node.put("actorId", actor.getId());
            }
            ArrayNode by = node.putArray("controlledBy");
            for (String playerId : controllers(token, actor, campaign, mapId)) {
                by.add(playerId);
            }
            ArrayNode conditions = node.putArray("conditions");
            if (token.getConditions() != null) {
                for (DmProject.TokenCondition condition : token.getConditions()) {
                    if (condition == null || condition.getId() == null || condition.getType() == null
                            || condition.getName() == null) continue;
                    conditions.addObject().put("id", condition.getId())
                            .put("type", condition.getType()).put("name", condition.getName());
                }
            }
        }
        return tokens;
    }

    /** The actor of a token when it belongs to the hosted campaign; tokens of other campaigns are plain tokens. */
    public static Campaign.Actor actorOf(DmProject.TokenInstance token, Campaign campaign) {
        if (campaign == null || token.getActorId() == null || !campaign.getId().equals(token.getCampaignId())) {
            return null;
        }
        return campaign.actor(token.getActorId());
    }

    /** Players that may move the token: the actor owner and every player with a control link to the token. */
    public static java.util.Set<String> controllers(DmProject.TokenInstance token, Campaign.Actor actor,
                                                     Campaign campaign, String mapId) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (actor != null && actor.isDisabled()) return result;
        if (actor != null && actor.getOwnerId() != null && !actor.getOwnerId().isBlank()) {
            result.add(actor.getOwnerId());
        }
        if (campaign != null) {
            for (Campaign.TokenLink link : campaign.getControlledTokens()) {
                if (token.getId().equals(link.getTokenId()) && (mapId == null || mapId.equals(link.getMapId()))) {
                    result.add(link.getPlayerId());
                }
            }
        }
        return result;
    }

    private static String artworkUrl(Campaign.Actor actor, Campaign campaign, AssetRegistry assets) {
        return null == actor.getArtwork() || campaign == null ? null : assets.artworkUrl(campaign.getId(), actor.getArtwork());
    }

    public static boolean isOpen(DmProject.Interactable door) {
        return "open".equalsIgnoreCase(door.getState()) || !door.isBlocksSightWhenClosed();
    }

    private static int rgbOf(String color) {
        try {
            return Integer.parseInt(color.trim().replace("#", "").substring(0, 6), 16);
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    private final dmmt.lighting.IndoorLighting indoorLighting = new dmmt.lighting.IndoorLighting();
    private long indoorVersion = -1;
    private FogMask indoorMask;
    private boolean indoorDaylight;
    private ObjectNode indoorGrid;

    /**
     * Room classification and daylight apertures. Always sent while rooms exist because outdoor-only weather and
     * lightning need it at every time of day; {@code lighting} says whether indoor ambient and lights apply. The
     * cell data is cached until the room geometry changes; the cheap scalars are refreshed on every build.
     */
    private JsonNode indoor(DmProject project, TimeOfDayPreset preset) {
        FogMask mask = project.getFog() == null ? null : project.getFog().getMask();
        if (mask == null) {
            return MAPPER.nullNode();
        }
        indoorLighting.update(project);
        if (!indoorLighting.hasRooms()) {
            return MAPPER.nullNode();
        }
        boolean daylight = dmmt.render.PerformanceMode.daylightThroughOpenings();
        if (indoorGrid == null || indoorLighting.version() != indoorVersion || mask != indoorMask
                || daylight != indoorDaylight) {
            int cols = mask.getCols();
            int rows = mask.getRows();
            java.util.BitSet inside = new java.util.BitSet(cols * rows);
            byte[] sun = new byte[cols * rows];
            boolean anySun = false;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double x = mask.getOriginX() + (c + 0.5) * mask.getCellSize();
                    double y = mask.getOriginY() + (r + 0.5) * mask.getCellSize();
                    if (indoorLighting.isInside(x, y)) {
                        inside.set(r * cols + c);
                        int d = (int) Math.round(indoorLighting.daylightAt(x, y) * 255);
                        sun[r * cols + c] = (byte) d;
                        anySun |= d > 0;
                    }
                }
            }
            ObjectNode node = MAPPER.createObjectNode();
            node.put("originX", mask.getOriginX()).put("originY", mask.getOriginY());
            node.put("cellSize", mask.getCellSize()).put("cols", cols).put("rows", rows);
            node.put("inside", java.util.Base64.getEncoder().encodeToString(inside.toByteArray()));
            if (anySun) {
                node.put("daylight", java.util.Base64.getEncoder().encodeToString(sun));
            }
            indoorGrid = node;
            indoorVersion = indoorLighting.version();
            indoorMask = mask;
            indoorDaylight = daylight;
        }
        ObjectNode out = indoorGrid.deepCopy();
        out.put("lighting", preset != TimeOfDayPreset.NIGHT && dmmt.render.PerformanceMode.indoorDarkness());
        TimeOfDayPreset night = TimeOfDayPreset.NIGHT;
        out.put("nightDarkness", night.darkness(project.getLighting().ambientBrightnessFor("NIGHT")));
        out.putArray("night").add(night.red()).add(night.green()).add(night.blue());
        return out;
    }

    private String encodedFor(FogMask mask) {
        if (mask != cachedMask || mask.getVersion() != cachedVersion) {
            cachedEncoded = mask.getRevealedEncoded();
            cachedMask = mask;
            cachedVersion = mask.getVersion();
        }
        return cachedEncoded;
    }

    static Path resolve(String path, Path projectFile) {
        try {
            Path resolved = Path.of(path);
            if (!resolved.isAbsolute() && projectFile != null && projectFile.getParent() != null) {
                resolved = projectFile.getParent().resolve(path).normalize();
            }
            return resolved;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Path fileOf(String uriOrPath) {
        try {
            return uriOrPath.startsWith("file:") ? Path.of(URI.create(uriOrPath)) : Path.of(uriOrPath);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
