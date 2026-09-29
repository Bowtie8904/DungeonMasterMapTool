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

        BitSet filled = new BitSet(cols * rows);
        int[] stack = new int[1024];
        int size = 0;
        int start = row * cols + col;
        filled.set(start);
        stack[size++] = start;
        boolean leaked = false;
        while (size > 0) {
            int idx = stack[--size];
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
                if (filled.get(next) || barrier.get(next)) {
                    continue;
                }
                filled.set(next);
                if (size == stack.length) {
                    stack = java.util.Arrays.copyOf(stack, size * 2);
                }
                stack[size++] = next;
            }
        }

        BitSet cells = (BitSet) filled.clone();
        for (int idx = filled.nextSetBit(0); idx >= 0; idx = filled.nextSetBit(idx + 1)) {
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
        return new Result(cells, leaked);
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
