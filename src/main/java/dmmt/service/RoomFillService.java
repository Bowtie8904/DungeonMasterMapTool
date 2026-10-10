package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.FogMask;

import java.util.BitSet;
import java.util.List;

/**
 * Finds the fog cells of the room around a world point. Walls and doors/windows (in any state)
 * are rasterised into the fog grid as barrier cells and the room is a 4-connected flood fill
 * that cannot cross them and never leaves the mask.
 */
public final class RoomFillService {
    private RoomFillService() {
    }

    /**
     * @param cells  room cells plus the barrier cells touching them (so the walls themselves get revealed)
     * @param leaked true when the fill reached the mask border, i.e. the area is not closed off
     */
    public record Result(BitSet cells, boolean leaked) {
        public boolean isEmpty() {
            return cells.isEmpty();
        }
    }

    public static long geometrySignature(FogMask mask, List<DmProject.WallSegment> walls,
                                         List<DmProject.Interactable> interactables) {
        long hash = Double.hashCode(mask.getOriginX());
        hash = hash * 31 + Double.hashCode(mask.getOriginY());
        hash = hash * 31 + Double.hashCode(mask.getCellSize());
        hash = hash * 31 + mask.getCols();
        hash = hash * 31 + mask.getRows();
        for (DmProject.WallSegment w : walls) {
            hash = hash * 31 + Double.hashCode(w.getX1());
            hash = hash * 31 + Double.hashCode(w.getY1());
            hash = hash * 31 + Double.hashCode(w.getX2());
            hash = hash * 31 + Double.hashCode(w.getY2());
        }
        for (DmProject.Interactable i : interactables) {
            hash = hash * 31 + Double.hashCode(i.getX1());
            hash = hash * 31 + Double.hashCode(i.getY1());
            hash = hash * 31 + Double.hashCode(i.getX2());
            hash = hash * 31 + Double.hashCode(i.getY2());
        }
        return hash;
    }

    public static BitSet buildBarrier(FogMask mask, List<DmProject.WallSegment> walls,
                                      List<DmProject.Interactable> interactables) {
        BitSet barrier = new BitSet(mask.getCols() * mask.getRows());
        for (DmProject.WallSegment w : walls) {
            rasterise(mask, barrier, w.getX1(), w.getY1(), w.getX2(), w.getY2());
        }
        for (DmProject.Interactable i : interactables) {
            rasterise(mask, barrier, i.getX1(), i.getY1(), i.getX2(), i.getY2());
        }
        return barrier;
    }

    public static Result fill(FogMask mask, BitSet barrier, double worldX, double worldY) {
        int cols = mask.getCols();
        int rows = mask.getRows();
        int col = (int) Math.floor((worldX - mask.getOriginX()) / mask.getCellSize());
        int row = (int) Math.floor((worldY - mask.getOriginY()) / mask.getCellSize());
        if (col < 0 || row < 0 || col >= cols || row >= rows || barrier.get(row * cols + col)) {
            return new Result(new BitSet(), false);
        }

        int start = row * cols + col;
        Component component = flood(mask, barrier, new BitSet(cols * rows), start);
        BitSet cells = new BitSet(cols * rows);
        for (int idx : component.cells()) {
            cells.set(idx);
            int r = idx / cols;
            int c = idx % cols;
            for (int nr = Math.max(0, r - 1); nr <= Math.min(rows - 1, r + 1); nr++) {
                for (int nc = Math.max(0, c - 1); nc <= Math.min(cols - 1, c + 1); nc++) {
                    int next = nr * cols + nc;
                    if (barrier.get(next)) {
                        cells.set(next);
                    }
                }
            }
        }
        return new Result(cells, component.leaked());
    }

    private record Component(int[] cells, boolean leaked) {
    }

    private static Component flood(FogMask mask, BitSet barrier, BitSet visited, int start) {
        int cols = mask.getCols();
        int rows = mask.getRows();
        int[] queue = new int[64];
        int size = 1;
        queue[0] = start;
        visited.set(start);
        boolean leaked = false;
        for (int head = 0; head < size; head++) {
            int idx = queue[head];
            int r = idx / cols;
            int c = idx % cols;
            if (r == 0 || c == 0 || r == rows - 1 || c == cols - 1) {
                leaked = true;
            }
            for (int d = 0; d < 4; d++) {
                int nr = r + (d == 0 ? -1 : d == 1 ? 1 : 0);
                int nc = c + (d == 2 ? -1 : d == 3 ? 1 : 0);
                if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) {
                    continue;
                }
                int next = nr * cols + nc;
                if (visited.get(next) || barrier.get(next)) {
                    continue;
                }
                visited.set(next);
                if (size == queue.length) {
                    queue = java.util.Arrays.copyOf(queue, size * 2);
                }
                queue[size++] = next;
            }
        }

        return new Component(java.util.Arrays.copyOf(queue, size), leaked);
    }

    /** One interior centre per enclosed component in row-major seed order; every grid cell is visited once. */
    public static List<double[]> enclosedRoomCenters(FogMask mask, BitSet barrier) {
        return enclosedRoomCenters(mask, barrier, 0);
    }

    public static List<double[]> enclosedRoomCenters(FogMask mask, BitSet barrier, double minimumDimension) {
        List<double[]> centers = new java.util.ArrayList<>();
        BitSet visited = (BitSet) barrier.clone();
        int total = mask.getCols() * mask.getRows();
        BitSet squareEnds = minimumDimension > 0 ? fullSquareEnds(mask, barrier, minimumDimension) : null;
        for (int seed = visited.nextClearBit(0); seed < total; seed = visited.nextClearBit(seed + 1)) {
            Component room = flood(mask, barrier, visited, seed);
            if (!room.leaked() && containsFullSquare(room.cells(), squareEnds)) {
                centers.add(interiorCenter(mask, room.cells()));
            }
        }
        return centers;
    }

    private static BitSet fullSquareEnds(FogMask mask, BitSet barrier, double minimumDimension) {
        int cols = mask.getCols();
        BitSet ends = new BitSet(cols * mask.getRows());
        int[] sizes = new int[cols];
        for (int row = 0; row < mask.getRows(); row++) {
            int diagonal = 0;
            for (int col = 0; col < cols; col++) {
                int above = sizes[col];
                int index = row * cols + col;
                // Largest clear square ending here depends on the squares above, left and diagonally above-left.
                sizes[col] = barrier.get(index) ? 0
                        : 1 + Math.min(above, Math.min(col > 0 ? sizes[col - 1] : 0, diagonal));
                if (sizes[col] * mask.getCellSize() >= minimumDimension) {
                    ends.set(index);
                }
                diagonal = above;
            }
        }
        return ends;
    }

    private static boolean containsFullSquare(int[] cells, BitSet squareEnds) {
        if (squareEnds == null) {
            return true;
        }
        for (int cell : cells) {
            if (squareEnds.get(cell)) {
                return true;
            }
        }
        return false;
    }

    /** Nearest interior cell to the room centroid, even when that centroid lies outside a concave room. */
    public static double[] labelPosition(FogMask mask, BitSet barrier, Result room, double x, double y) {
        if (room == null || room.leaked() || room.isEmpty()) {
            return new double[]{x, y};
        }
        BitSet interior = (BitSet) room.cells().clone();
        interior.andNot(barrier);
        if (interior.isEmpty()) {
            return new double[]{x, y};
        }
        return interiorCenter(mask, interior.stream().toArray());
    }

    private static double[] interiorCenter(FogMask mask, int[] interior) {
        double cx = 0;
        double cy = 0;
        for (int i : interior) {
            cx += i % mask.getCols();
            cy += i / mask.getCols();
        }
        cx /= interior.length;
        cy /= interior.length;
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i : interior) {
            double dx = i % mask.getCols() - cx;
            double dy = i / mask.getCols() - cy;
            double distance = dx * dx + dy * dy;
            if (distance < best || (distance == best && i < nearest)) {
                best = distance;
                nearest = i;
            }
        }
        return new double[]{mask.getOriginX() + (nearest % mask.getCols() + 0.5) * mask.getCellSize(),
                mask.getOriginY() + (nearest / mask.getCols() + 0.5) * mask.getCellSize()};
    }

    // Segments are extended by half a cell at both ends so tiny gaps where walls meet do not leak.
    private static void rasterise(FogMask mask, BitSet barrier, double x1, double y1, double x2, double y2) {
        double cell = mask.getCellSize();
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        double ux = length > 0 ? dx / length : 0;
        double uy = length > 0 ? dy / length : 0;
        double extend = cell * 0.5;
        double sx = x1 - ux * extend;
        double sy = y1 - uy * extend;
        double total = length + extend * 2;
        int steps = Math.max(1, (int) Math.ceil(total / (cell * 0.5)));
        for (int i = 0; i <= steps; i++) {
            double t = total * i / steps;
            int col = (int) Math.floor((sx + ux * t - mask.getOriginX()) / cell);
            int row = (int) Math.floor((sy + uy * t - mask.getOriginY()) / cell);
            if (col >= 0 && row >= 0 && col < mask.getCols() && row < mask.getRows()) {
                barrier.set(row * mask.getCols() + col);
            }
        }
    }
}
