package dmmt.lighting;

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
    private final VisibilityService visibilityService = new VisibilityService();
    private final FogService fogService = new FogService();
    private final Map<String, CachedVisibility> cache = new HashMap<>();
    private final Map<String, String> lastPersistentKey = new HashMap<>();

    private DmProject currentProject;
    private long geometrySignature = Long.MIN_VALUE;
    private double[] blockingSegments = new double[0];

    private BitSet liveReveal = new BitSet();
    private String liveRevealKey = "";
    private long liveRevealVersion;

    public void update(DmProject project) {
        if (project == null) {
            return;
        }
        if (project != currentProject) {
            reset();
            currentProject = project;
        }
        fogService.ensureMask(project);
        refreshGeometry(project);

        Set<String> activeIds = new HashSet<>();
        FogMask mask = project.getFog().getMask();
        boolean fogEnabled = project.getFog().isEnabled();
        StringBuilder liveKey = new StringBuilder();

        for (DmProject.LightSource light : project.getLighting().getLights()) {
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
            if (mode == DmProject.RevealMode.PERSISTENT && fogEnabled && mask != null) {
                if (!vis.key.equals(lastPersistentKey.get(light.getId()))) {
                    mask.applyPolygon(vis.polygon.xs(), vis.polygon.ys(), true);
                    lastPersistentKey.put(light.getId(), vis.key);
                }
            } else if (mode == DmProject.RevealMode.WHILE_LIT) {
                liveKey.append(vis.key).append('|');
            }
        }
        cache.keySet().retainAll(activeIds);
        lastPersistentKey.keySet().retainAll(activeIds);

        String maskKey = mask == null ? "" : mask.getOriginX() + "," + mask.getOriginY() + "," + mask.getCols() + "," + mask.getRows();
        String nextLiveKey = liveKey + "#" + maskKey;
        if (!nextLiveKey.equals(liveRevealKey)) {
            rebuildLiveReveal(project, mask);
            liveRevealKey = nextLiveKey;
            liveRevealVersion++;
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
        for (DmProject.LightSource light : project.getLighting().getLights()) {
            if (light.getId() != null && light.isEnabled()) {
                lastPersistentKey.put(light.getId(), visibility(light).key);
            }
        }
    }

    public void reset() {
        cache.clear();
        lastPersistentKey.clear();
        geometrySignature = Long.MIN_VALUE;
        blockingSegments = new double[0];
        liveReveal = new BitSet();
        liveRevealKey = "";
        liveRevealVersion++;
        currentProject = null;
    }

    public VisibilityService.Polygon polygonFor(DmProject.LightSource light) {
        return visibility(light).polygon;
    }

    public BitSet getLiveReveal() {
        return liveReveal;
    }

    public long getLiveRevealVersion() {
        return liveRevealVersion;
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

    private void rebuildLiveReveal(DmProject project, FogMask mask) {
        BitSet next = new BitSet();
        if (mask != null) {
            for (DmProject.LightSource light : project.getLighting().getLights()) {
                if (light.getId() == null || !light.isEnabled() || light.getRevealMode() != DmProject.RevealMode.WHILE_LIT) {
                    continue;
                }
                VisibilityService.Polygon polygon = visibility(light).polygon;
                for (int cell : mask.cellsInPolygon(polygon.xs(), polygon.ys())) {
                    next.set(cell);
                }
            }
        }
        liveReveal = next;
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
}
