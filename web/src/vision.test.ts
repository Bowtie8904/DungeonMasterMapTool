import { describe, expect, it } from 'vitest';
import {
  blockingSegments,
  circlePolygon,
  computeVisibility,
  computeVisibleCells,
  mergeExplored,
  pointInPolygon,
} from './vision';

// Room 0..1000 square walls, one vertical wall at x=600 from y=0..700 (gap at the bottom).
const room = [0, 0, 1000, 0, 1000, 0, 1000, 1000, 1000, 1000, 0, 1000, 0, 1000, 0, 0];
const partition = [600, 0, 600, 700];

describe('visibility', () => {
  it('open square room: polygon stays inside the room and reaches the walls', () => {
    const poly = computeVisibility(500, 500, 2000, room);
    expect(poly.xs.length).toBeGreaterThan(96);
    for (let i = 0; i < poly.xs.length; i++) {
      expect(poly.xs[i]).toBeGreaterThanOrEqual(-1e-6);
      expect(poly.xs[i]).toBeLessThanOrEqual(1000 + 1e-6);
      expect(poly.ys[i]).toBeGreaterThanOrEqual(-1e-6);
      expect(poly.ys[i]).toBeLessThanOrEqual(1000 + 1e-6);
    }
    expect(pointInPolygon(900, 900, poly)).toBe(true);
    expect(pointInPolygon(1100, 500, poly)).toBe(false);
  });

  it('a wall hides the area behind it', () => {
    const poly = computeVisibility(300, 300, 2000, [...room, ...partition]);
    expect(pointInPolygon(500, 300, poly)).toBe(true);
    expect(pointInPolygon(800, 300, poly)).toBe(false);
    expect(pointInPolygon(700, 980, poly)).toBe(true); // around the gap
    // every point of the polygon on the right half hugs the wall or lies below y=700
    for (let i = 0; i < poly.xs.length; i++) {
      if (poly.xs[i] > 600.01) expect(poly.ys[i]).toBeGreaterThan(699);
    }
  });

  it('a closed door blocks, an open one does not', () => {
    const door = { x1: 600, y1: 0, x2: 600, y2: 1000, open: false };
    const closed = computeVisibility(300, 500, 2000, blockingSegments(room, [door]));
    const open = computeVisibility(300, 500, 2000, blockingSegments(room, [{ ...door, open: true }]));
    expect(pointInPolygon(800, 500, closed)).toBe(false);
    expect(pointInPolygon(800, 500, open)).toBe(true);
  });

  it('range limits the polygon and zero range yields nothing', () => {
    const poly = computeVisibility(500, 500, 100, []);
    for (let i = 0; i < poly.xs.length; i++) {
      expect(Math.hypot(poly.xs[i] - 500, poly.ys[i] - 500)).toBeCloseTo(100, 6);
    }
    expect(computeVisibility(0, 0, 0, room).xs.length).toBe(0);
    expect(circlePolygon(0, 0, 10, 8).xs.length).toBe(8);
  });
});

describe('visible cells', () => {
  const grid = { originX: 0, originY: 0, cellSize: 100, cols: 10, rows: 10 };
  const sight = computeVisibility(150, 150, 600, [...room, ...partition]);

  it('uses dark vision radius or lights when ambient is dark', () => {
    const dark = computeVisibleCells({ grid, sources: [{ x: 150, y: 150, sight }], darkVisionRadius: 120, lightPolygons: [] });
    expect(dark[1 * 10 + 1]).toBe(1);
    expect(dark[1 * 10 + 4]).toBe(0);
    const lit = computeVisibleCells({
      grid,
      sources: [{ x: 150, y: 150, sight }],
      darkVisionRadius: 120,
      lightPolygons: [computeVisibility(450, 150, 200, [...room, ...partition])],
    });
    expect(lit[1 * 10 + 4]).toBe(1);
  });

  it('everything in sight is visible in ambient light, but not behind walls', () => {
    const v = computeVisibleCells({ grid, sources: [{ x: 150, y: 150, sight }], darkVisionRadius: null, lightPolygons: [] });
    expect(v[1 * 10 + 4]).toBe(1);
    expect(v[1 * 10 + 8]).toBe(0);
  });

  it('mergeExplored reports only new cells', () => {
    const explored = new Uint8Array(4);
    explored[0] = 1;
    const fresh = mergeExplored(explored, Uint8Array.from([1, 1, 0, 0]));
    expect([...fresh!]).toEqual([0, 1, 0, 0]);
    expect(mergeExplored(explored, Uint8Array.from([1, 1, 0, 0]))).toBeNull();
  });
});
