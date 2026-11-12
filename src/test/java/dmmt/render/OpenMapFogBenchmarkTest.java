package dmmt.render;

import dmmt.lighting.VisibilityService;
import dmmt.model.FogMask;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in comparison of the old and current work for a small movement in a large open area. */
class OpenMapFogBenchmarkTest {
    @Test
    void benchmarkOpenMapMovement() {
        assumeTrue(Boolean.getBoolean("benchmark.fog"));
        int cols = 1200, rows = 1200, radius = 3, tileSize = 64;
        FogMask mask = new FogMask(0, 0, 1, cols, rows);
        VisibilityService service = new VisibilityService();
        var old = service.circle(600, 600, 400);
        var moved = service.circle(603, 600, 400);
        BitSet before = new BitSet(), after = new BitSet();
        mask.addPolygonTo(before, old.xs(), old.ys());
        mask.addPolygonTo(after, moved.xs(), moved.ys());
        BitSet diff = (BitSet) before.clone();
        diff.xor(after);
        BitSet tiles = FogShading.dirtyTiles(diff, cols, rows, radius, tileSize);
        int tileCols = (cols + tileSize - 1) / tileSize;
        for (int warmup = 0; warmup < 4; warmup++) {
            runOld(mask, moved, after, cols, rows, radius);
            runNew(mask, moved, after, cols, rows, radius, tileSize, tileCols, tiles);
        }
        int iterations = 20;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) runOld(mask, moved, after, cols, rows, radius);
        long oldTime = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) runNew(mask, moved, after, cols, rows, radius, tileSize, tileCols, tiles);
        long newTime = System.nanoTime() - start;
        System.out.printf("Open-map fog raster and shading: old %.2f ms, tiled %.2f ms, %d dirty tiles, %d iterations%n",
                oldTime / 1e6 / iterations, newTime / 1e6 / iterations, tiles.cardinality(), iterations);
    }

    private static void runOld(FogMask mask, VisibilityService.Polygon polygon, BitSet bits,
                               int cols, int rows, int radius) {
        BitSet out = new BitSet();
        for (int cell : mask.cellsInPolygon(polygon.xs(), polygon.ys())) out.set(cell);
        FogShading.fogOpacityRegion(bits, cols, rows, radius, 195, 195, 1008, 1005);
    }

    private static void runNew(FogMask mask, VisibilityService.Polygon polygon, BitSet bits, int cols, int rows,
                               int radius, int tileSize, int tileCols, BitSet tiles) {
        BitSet out = new BitSet();
        mask.addPolygonTo(out, polygon.xs(), polygon.ys());
        for (int tile = tiles.nextSetBit(0); tile >= 0; tile = tiles.nextSetBit(tile + 1)) {
            int c0 = tile % tileCols * tileSize, r0 = tile / tileCols * tileSize;
            FogShading.fogOpacityRegion(bits, cols, rows, radius, c0, r0,
                    Math.min(cols - 1, c0 + tileSize - 1), Math.min(rows - 1, r0 + tileSize - 1));
        }
    }
}
