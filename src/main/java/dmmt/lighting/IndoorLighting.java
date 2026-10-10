package dmmt.lighting;

import dmmt.model.DmProject;
import dmmt.model.FogMask;
import dmmt.service.RoomFillService;
import dmmt.service.Tuning;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Cached room classification and steady, occluded daylight from exterior apertures; never edits fog. */
public final class IndoorLighting {
    private DmProject project;
    private FogMask mask;
    private long signature = Long.MIN_VALUE;
    private BitSet interior = new BitSet();
    private BitSet shaded = new BitSet();
    private BitSet barrier = new BitSet();
    private BitSet outsideCells = new BitSet();
    private float[] daylight = new float[0];
    private long version;

    public long version() {
        return version;
    }

    public boolean hasRooms() {
        return !interior.isEmpty();
    }

    public boolean isInside(double x, double y) {
        int cell = cellAt(x, y);
        return cell >= 0 && shaded.get(cell);
    }

    public double daylightAt(double x, double y) {
        int cell = cellAt(x, y);
        return cell >= 0 ? daylight[cell] : 0;
    }

    public boolean lightActive(TimeOfDayPreset preset, double x, double y) {
        return preset == TimeOfDayPreset.NIGHT || isInside(x, y);
    }

    public void update(DmProject candidate) {
        FogMask nextMask = candidate.getFog().getMask();
        if (nextMask == null) {
            return;
        }
        long next = RoomFillService.geometrySignature(nextMask, candidate.getWalls(), candidate.getInteractables());
        next = next * 31 + Double.hashCode(candidate.getMap().getGrid().getPixelsPerCell());
        next = next * 31 + Tuning.SHADOW_RAYS.get();
        for (DmProject.Interactable portal : candidate.getInteractables()) {
            next = next * 31 + (isOpen(portal) ? 1 : 0);
        }
        if (candidate == project && nextMask == mask && next == signature) {
            return;
        }
        project = candidate;
        mask = nextMask;
        signature = next;
        version++;
        barrier = RoomFillService.buildBarrier(mask, candidate.getWalls(), candidate.getInteractables());
        interior = RoomFillService.enclosedCells(mask, barrier);
        outsideCells = new BitSet(mask.getCols() * mask.getRows());
        outsideCells.set(0, mask.getCols() * mask.getRows());
        outsideCells.andNot(barrier);
        outsideCells.andNot(interior);
        shaded = (BitSet) interior.clone();
        // Include room boundary pixels to avoid bright grid-width seams along walls.
        int cols = mask.getCols();
        int rows = mask.getRows();
        for (int cell = interior.nextSetBit(0); cell >= 0; cell = interior.nextSetBit(cell + 1)) {
            int row = cell / cols;
            int col = cell % cols;
            for (int r = Math.max(0, row - 1); r <= Math.min(rows - 1, row + 1); r++) {
                for (int c = Math.max(0, col - 1); c <= Math.min(cols - 1, col + 1); c++) {
                    if (barrier.get(r * cols + c)) {
                        shaded.set(r * cols + c);
                    }
                }
            }
        }
        daylight = new float[cols * rows];
        if (!hasRooms()) {
            return;
        }
        List<Double> segments = new ArrayList<>();
        for (DmProject.WallSegment wall : candidate.getWalls()) {
            addSegment(segments, wall.getX1(), wall.getY1(), wall.getX2(), wall.getY2());
        }
        for (DmProject.Interactable portal : candidate.getInteractables()) {
            if (!isOpen(portal)) {
                addSegment(segments, portal.getX1(), portal.getY1(), portal.getX2(), portal.getY2());
            }
        }
        double[] blockers = segments.stream().mapToDouble(Double::doubleValue).toArray();
        VisibilityService visibility = new VisibilityService();
        for (DmProject.Interactable portal : candidate.getInteractables()) {
            if (!isOpen(portal)) {
                continue;
            }
            double dx = portal.getX2() - portal.getX1();
            double dy = portal.getY2() - portal.getY1();
            double length = Math.hypot(dx, dy);
            if (length <= 0) {
                continue;
            }
            double mx = (portal.getX1() + portal.getX2()) / 2;
            double my = (portal.getY1() + portal.getY2()) / 2;
            // Stay just inside the inclusive one-tile clearance, not inside a distant neighbouring room.
            double offset = Math.nextDown(candidate.getMap().getGrid().getPixelsPerCell());
            double nx = -dy / length * offset;
            double ny = dx / length * offset;
            for (int side : new int[]{-1, 1}) {
                double sx = mx + nx * side;
                double sy = my + ny * side;
                double probe = mask.getCellSize() * 1.5;
                int outside = cellAt(mx - dy / length * probe * side, my + dx / length * probe * side);
                int inside = cellAt(mx + dy / length * probe * side, my - dx / length * probe * side);
                if ((outside < 0 || outsideCells.get(outside))
                        && inside >= 0 && interior.get(inside)) {
                    double range = Math.max(candidate.getMap().getGrid().getPixelsPerCell() * 8, length * 8);
                    VisibilityService.Polygon polygon = visibility.compute(sx, sy, range + offset, blockers);
                    for (int cell : mask.cellsInPolygon(polygon.xs(), polygon.ys())) {
                        if (!shaded.get(cell)) {
                            continue;
                        }
                        double x = mask.getOriginX() + (cell % cols + 0.5) * mask.getCellSize();
                        double y = mask.getOriginY() + (cell / cols + 0.5) * mask.getCellSize();
                        if (!passesThroughAperture(sx, sy, x, y, portal)) {
                            continue;
                        }
                        double distance = Math.max(0, Math.hypot(x - sx, y - sy) - offset);
                        double fade = Math.max(0, Math.min(1, (distance / range - 0.3) / 0.7));
                        double strength = 1 - fade * fade * (3 - 2 * fade);
                        daylight[cell] = Math.max(daylight[cell], (float) strength);
                    }
                }
            }
        }
    }

    private int cellAt(double x, double y) {
        if (mask == null) {
            return -1;
        }
        int col = (int) Math.floor((x - mask.getOriginX()) / mask.getCellSize());
        int row = (int) Math.floor((y - mask.getOriginY()) / mask.getCellSize());
        return col < 0 || row < 0 || col >= mask.getCols() || row >= mask.getRows()
                ? -1 : row * mask.getCols() + col;
    }

    /** A visible point only receives this source if its ray crosses this specific opening first. */
    private static boolean passesThroughAperture(double sx, double sy, double x, double y,
                                                DmProject.Interactable portal) {
        double rx = x - sx;
        double ry = y - sy;
        double ex = portal.getX2() - portal.getX1();
        double ey = portal.getY2() - portal.getY1();
        double denominator = rx * ey - ry * ex;
        if (Math.abs(denominator) < 1e-12) {
            return false;
        }
        double dx = portal.getX1() - sx;
        double dy = portal.getY1() - sy;
        double t = (dx * ey - dy * ex) / denominator;
        double u = (dx * ry - dy * rx) / denominator;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }

    private static boolean isOpen(DmProject.Interactable portal) {
        return "open".equalsIgnoreCase(portal.getState());
    }

    private static void addSegment(List<Double> segments, double x1, double y1, double x2, double y2) {
        segments.add(x1);
        segments.add(y1);
        segments.add(x2);
        segments.add(y2);
    }
}
