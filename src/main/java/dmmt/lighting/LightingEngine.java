package dmmt.lighting;

import lombok.Getter;
import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.service.FogService;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-frame lighting state: caches visibility polygons per light, applies persistent
 * fog reveals, and tracks transient reveals for WHILE_LIT lights. Call {@link #update}
 * once per frame before rendering.
 */
public class LightingEngine {
    @Getter
    private final IndoorLighting indoorLighting = new IndoorLighting();
    private final VisibilityService visibilityService = new VisibilityService();
    private final FogService fogService = new FogService();
    private final Map<String, CachedVisibility> cache = new HashMap<>();
    private final Map<String, CachedVisibility> actorSightCache = new HashMap<>();
    private final Map<String, CachedReveal> liveLightCache = new HashMap<>();
    private final Map<String, CachedReveal> liveActorCache = new HashMap<>();
    private final Map<String, String> lastPersistentKey = new HashMap<>();

    private DmProject currentProject;
    private long geometrySignature = Long.MIN_VALUE;
    private double[] blockingSegments = new double[0];
    private double lastMaskCellSize = Double.NaN;

    @Getter
    private BitSet liveReveal = new BitSet();
    private String liveRevealKey = "";
    @Getter
    private long liveRevealVersion;
    /** Counts fog changes caused by lights (live or persistent reveals); lets the renderer fade them faster. */
    @Getter
    private long lightRevealChanges;

    /** Compute host-owned reveal effects without re-revealing unchanged persistent lights after a fog edit. */
    public void completeCommittedEdit(DmProject before, DmProject after) {
        reset();
        currentProject = after;
        FogMask mask = before.getFog().getMask();
        lastMaskCellSize = mask == null ? Double.NaN : mask.getCellSize();
        markPersistentRevealsApplied(before);
        for (var light : before.effectiveLights()) {
            if (light.getRevealMode() != DmProject.RevealMode.PERSISTENT || !before.getFog().isEnabled()) {
                lastPersistentKey.remove(light.getId());
            }
        }
        update(after);
    }

    public void acceptCommittedState(DmProject project) {
        reset();
        currentProject = project;
        FogMask mask = project.getFog().getMask();
        lastMaskCellSize = mask == null ? Double.NaN : mask.getCellSize();
        markPersistentRevealsApplied(project);
        update(project);
    }

    public void update(DmProject project) {
        update(project, true);
    }

    public void updateReplica(DmProject project) {
        update(project, false);
    }

    private void update(DmProject project, boolean authoritative) {
        if (project == null) {
            return;
        }
        if (project != currentProject) {
            reset();
            currentProject = project;
        }
        if (authoritative) {
            fogService.ensureMask(project);
        }
        refreshGeometry(project);
        indoorLighting.update(project);

        Set<String> activeIds = new HashSet<>();
        FogMask mask = project.getFog().getMask();
        if (mask != null && mask.getCellSize() != lastMaskCellSize) {
            // Re-apply persistent reveals so their edges use the new fog resolution.
            lastMaskCellSize = mask.getCellSize();
            lastPersistentKey.clear();
        }
        boolean fogEnabled = project.getFog().isEnabled();
        StringBuilder liveKey = new StringBuilder();

        for (DmProject.LightSource light : project.effectiveLights()) {
            if (light.getId() == null) {
                continue;
            }
            activeIds.add(light.getId());
            if (!light.isEnabled()) {
                lastPersistentKey.remove(light.getId());
                continue;
            }
            CachedVisibility vis = visibility(light);
            DmProject.RevealMode mode = light.getRevealMode() == null ? DmProject.RevealMode.WHILE_LIT : light.getRevealMode();
            if (mode == DmProject.RevealMode.PERSISTENT && fogEnabled && mask != null && authoritative) {
                if (!vis.key.equals(lastPersistentKey.get(light.getId()))) {
                    mask.applyPolygon(vis.polygon.xs(), vis.polygon.ys(), true);
                    lightRevealChanges++;
                    lastPersistentKey.put(light.getId(), vis.key);
                }
            } else if (mode == DmProject.RevealMode.WHILE_LIT) {
                liveKey.append(vis.key).append('|');
            }
        }
        Set<String> activeActors = new HashSet<>();
        if (fogEnabled && mask != null) {
            for (DmProject.TokenInstance token : project.getTokens()) {
                if (token.getId() == null || token.getActorId() == null || !token.isPlayerVisible()) {
                    continue;
                }
                activeActors.add(token.getId());
                liveKey.append("actor:").append(token.getId()).append(':')
                        .append(actorSight(project, token).key).append('|');
            }
        }
        actorSightCache.keySet().retainAll(activeActors);
        liveActorCache.keySet().retainAll(activeActors);
        cache.keySet().retainAll(activeIds);
        lastPersistentKey.keySet().retainAll(activeIds);

        String maskKey = mask == null ? "" : mask.getOriginX() + "," + mask.getOriginY() + "," + mask.getCellSize() + "," + mask.getCols() + "," + mask.getRows();
        String nextLiveKey = liveKey + "#" + maskKey;
        if (!nextLiveKey.equals(liveRevealKey)) {
            rebuildLiveReveal(project, mask);
            liveRevealKey = nextLiveKey;
            liveRevealVersion++;
            lightRevealChanges++;
        }
    }

    /**
     * Makes persistent lights re-apply their reveal on the next update (e.g. after the DM
     * explicitly re-enables a light's reveal mode).
     */
    public void invalidatePersistentReveal(String lightId) {
        lastPersistentKey.remove(lightId);
    }

    /**
     * Marks all current persistent-light positions as already applied, so restoring a fog
     * snapshot (undo) is not immediately overwritten by lights that have not moved.
     */
    public void markPersistentRevealsApplied(DmProject project) {
        if (project == null) {
            return;
        }
        refreshGeometry(project);
        for (DmProject.LightSource light : project.effectiveLights()) {
            if (light.getId() != null && light.isEnabled()) {
                lastPersistentKey.put(light.getId(), visibility(light).key);
            }
        }
    }

    public void reset() {
        cache.clear();
        actorSightCache.clear();
        liveLightCache.clear();
        liveActorCache.clear();
        lastPersistentKey.clear();
        geometrySignature = Long.MIN_VALUE;
        blockingSegments = new double[0];
        lastMaskCellSize = Double.NaN;
        liveReveal = new BitSet();
        liveRevealKey = "";
        liveRevealVersion++;
        currentProject = null;
    }

    public VisibilityService.Polygon polygonFor(DmProject.LightSource light) {
        return visibility(light).polygon;
    }

    private CachedVisibility visibility(DmProject.LightSource light) {
        String key = light.getX() + "," + light.getY() + "," + light.getRange() + "," + light.isCastsShadows() + "," + geometrySignature;
        CachedVisibility cached = cache.get(light.getId());
        if (cached != null && cached.key.equals(key)) {
            return cached;
        }
        VisibilityService.Polygon polygon = light.isCastsShadows()
                ? visibilityService.compute(light.getX(), light.getY(), light.getRange(), blockingSegments)
                : visibilityService.circle(light.getX(), light.getY(), light.getRange());
        CachedVisibility fresh = new CachedVisibility(key, polygon);
        if (light.getId() != null) {
            cache.put(light.getId(), fresh);
        }
        return fresh;
    }

    private CachedVisibility actorSight(DmProject project, DmProject.TokenInstance token) {
        double range = dmmt.service.Tuning.FOG_ACTOR_SIGHT_TILES.get() * project.getMap().getGrid().getPixelsPerCell();
        String key = token.getX() + "," + token.getY() + "," + range + "," + geometrySignature;
        CachedVisibility cached = actorSightCache.get(token.getId());
        if (cached != null && cached.key.equals(key)) {
            return cached;
        }
        CachedVisibility fresh = new CachedVisibility(key,
                visibilityService.compute(token.getX(), token.getY(), range, blockingSegments));
        actorSightCache.put(token.getId(), fresh);
        return fresh;
    }

    private void rebuildLiveReveal(DmProject project, FogMask mask) {
        BitSet next = new BitSet();
        Set<String> activeLights = new HashSet<>();
        if (mask != null) {
            for (DmProject.LightSource light : project.effectiveLights()) {
                if (light.getId() == null || !light.isEnabled() || light.getRevealMode() != DmProject.RevealMode.WHILE_LIT) {
                    continue;
                }
                activeLights.add(light.getId());
                next.or(rasterized(liveLightCache, light.getId(), visibility(light), mask));
            }
            if (project.getFog().isEnabled()) {
                for (DmProject.TokenInstance token : project.getTokens()) {
                    if (token.getId() == null || token.getActorId() == null || !token.isPlayerVisible()) {
                        continue;
                    }
                    next.or(rasterized(liveActorCache, token.getId(), actorSight(project, token), mask));
                }
            }
        }
        liveLightCache.keySet().retainAll(activeLights);
        liveReveal = next;
    }

    private BitSet rasterized(Map<String, CachedReveal> rasters, String id, CachedVisibility visibility, FogMask mask) {
        String geometry = mask.getOriginX() + "," + mask.getOriginY() + "," + mask.getCellSize()
                + "," + mask.getCols() + "," + mask.getRows();
        CachedReveal cached = rasters.get(id);
        if (cached != null && cached.visibilityKey.equals(visibility.key) && cached.maskGeometry.equals(geometry)) {
            return cached.cells;
        }
        BitSet cells = new BitSet();
        mask.addPolygonTo(cells, visibility.polygon.xs(), visibility.polygon.ys());
        rasters.put(id, new CachedReveal(visibility.key, geometry, cells));
        return cells;
    }

    private void refreshGeometry(DmProject project) {
        long signature = computeGeometrySignature(project);
        if (signature == geometrySignature) {
            return;
        }
        geometrySignature = signature;
        int wallCount = project.getWalls().size();
        double[] segments = new double[(wallCount + project.getInteractables().size()) * 4];
        int i = 0;
        for (DmProject.WallSegment wall : project.getWalls()) {
            segments[i++] = wall.getX1();
            segments[i++] = wall.getY1();
            segments[i++] = wall.getX2();
            segments[i++] = wall.getY2();
        }
        for (DmProject.Interactable interactable : project.getInteractables()) {
            if (blocksSight(interactable)) {
                segments[i++] = interactable.getX1();
                segments[i++] = interactable.getY1();
                segments[i++] = interactable.getX2();
                segments[i++] = interactable.getY2();
            }
        }
        blockingSegments = java.util.Arrays.copyOf(segments, i);
        cache.clear();
        actorSightCache.clear();
    }

    private static boolean blocksSight(DmProject.Interactable interactable) {
        return interactable.isBlocksSightWhenClosed() && !"open".equalsIgnoreCase(interactable.getState());
    }

    private static long computeGeometrySignature(DmProject project) {
        long hash = 1125899906842597L;
        for (DmProject.WallSegment wall : project.getWalls()) {
            hash = 31 * hash + Double.hashCode(wall.getX1());
            hash = 31 * hash + Double.hashCode(wall.getY1());
            hash = 31 * hash + Double.hashCode(wall.getX2());
            hash = 31 * hash + Double.hashCode(wall.getY2());
        }
        for (DmProject.Interactable interactable : project.getInteractables()) {
            hash = 31 * hash + Double.hashCode(interactable.getX1());
            hash = 31 * hash + Double.hashCode(interactable.getY1());
            hash = 31 * hash + Double.hashCode(interactable.getX2());
            hash = 31 * hash + Double.hashCode(interactable.getY2());
            hash = 31 * hash + (blocksSight(interactable) ? 1 : 0);
        }
        return hash;
    }

    private record CachedVisibility(String key, VisibilityService.Polygon polygon) {
    }

    private record CachedReveal(String visibilityKey, String maskGeometry, BitSet cells) {
    }
}
