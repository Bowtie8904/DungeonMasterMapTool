/** Port of dmmt.lighting.VisibilityService plus helpers for rasterising visibility into a cell grid. */

export const SHADOW_RAYS = 96;
const ANGLE_EPSILON = 0.0001;

export interface Polygon {
  xs: number[];
  ys: number[];
}

export interface Bounds {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

function distanceToSegmentSq(px: number, py: number, x1: number, y1: number, x2: number, y2: number): number {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const lenSq = dx * dx + dy * dy;
  let t = lenSq === 0 ? 0 : ((px - x1) * dx + (py - y1) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  const sx = x1 + t * dx - px;
  const sy = y1 + t * dy - py;
  return sx * sx + sy * sy;
}

function filterSegments(lx: number, ly: number, range: number, segments: ArrayLike<number>): number[] {
  const out: number[] = [];
  const rangeSq = range * range;
  for (let i = 0; i + 3 < segments.length; i += 4) {
    if (distanceToSegmentSq(lx, ly, segments[i], segments[i + 1], segments[i + 2], segments[i + 3]) <= rangeSq) {
      out.push(segments[i], segments[i + 1], segments[i + 2], segments[i + 3]);
    }
  }
  return out;
}

function castRay(ox: number, oy: number, dx: number, dy: number, maxDistance: number, segments: number[]): number {
  let best = maxDistance;
  for (let i = 0; i < segments.length; i += 4) {
    const x1 = segments[i];
    const y1 = segments[i + 1];
    const ex = segments[i + 2] - x1;
    const ey = segments[i + 3] - y1;
    const denom = dx * ey - dy * ex;
    if (Math.abs(denom) < 1e-12) {
      continue;
    }
    const wx = x1 - ox;
    const wy = y1 - oy;
    const t = (wx * ey - wy * ex) / denom;
    const u = (wx * dy - wy * dx) / denom;
    if (t >= 0 && t < best && u >= 0 && u <= 1) {
      best = t;
    }
  }
  return best;
}

/** Visibility polygon of a point against flat segments [x1,y1,x2,y2,...]. */
export function computeVisibility(
  lightX: number,
  lightY: number,
  range: number,
  segments: ArrayLike<number>,
  rays: number = SHADOW_RAYS,
): Polygon {
  if (!(range > 0)) {
    return { xs: [], ys: [] };
  }
  const nearby = filterSegments(lightX, lightY, range, segments);
  const angles: number[] = [];
  for (let i = 0; i < rays; i++) {
    angles.push(-Math.PI + (2 * Math.PI * i) / rays);
  }
  for (let s = 0; s < nearby.length / 4; s++) {
    for (let p = 0; p < 2; p++) {
      const px = nearby[s * 4 + p * 2];
      const py = nearby[s * 4 + p * 2 + 1];
      const angle = Math.atan2(py - lightY, px - lightX);
      angles.push(angle - ANGLE_EPSILON, angle, angle + ANGLE_EPSILON);
    }
  }
  const sorted = Float64Array.from(angles).sort();
  const xs: number[] = [];
  const ys: number[] = [];
  for (const a of sorted) {
    const dx = Math.cos(a);
    const dy = Math.sin(a);
    const t = castRay(lightX, lightY, dx, dy, range, nearby);
    xs.push(lightX + dx * t);
    ys.push(lightY + dy * t);
  }
  return { xs, ys };
}

export function circlePolygon(x: number, y: number, range: number, rays: number = SHADOW_RAYS): Polygon {
  const xs: number[] = [];
  const ys: number[] = [];
  for (let i = 0; i < rays; i++) {
    const a = (2 * Math.PI * i) / rays;
    xs.push(x + Math.cos(a) * range);
    ys.push(y + Math.sin(a) * range);
  }
  return { xs, ys };
}

/** Walls plus closed doors as one flat segment array. */
export function blockingSegments(walls: ArrayLike<number>, doors: Array<{ x1: number; y1: number; x2: number; y2: number; open: boolean }>): number[] {
  const out: number[] = [];
  for (let i = 0; i + 3 < walls.length; i += 4) {
    out.push(walls[i], walls[i + 1], walls[i + 2], walls[i + 3]);
  }
  for (const d of doors) {
    if (!d.open) {
      out.push(d.x1, d.y1, d.x2, d.y2);
    }
  }
  return out;
}

export function polygonBounds(p: Polygon): Bounds {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (let i = 0; i < p.xs.length; i++) {
    minX = Math.min(minX, p.xs[i]);
    maxX = Math.max(maxX, p.xs[i]);
    minY = Math.min(minY, p.ys[i]);
    maxY = Math.max(maxY, p.ys[i]);
  }
  return { minX, minY, maxX, maxY };
}

export function pointInPolygon(x: number, y: number, p: Polygon): boolean {
  let inside = false;
  const n = p.xs.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = p.xs[i];
    const yi = p.ys[i];
    const xj = p.xs[j];
    const yj = p.ys[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

export interface Grid {
  originX: number;
  originY: number;
  cellSize: number;
  cols: number;
  rows: number;
}

export interface VisionSource {
  x: number;
  y: number;
  /** Line-of-sight polygon (range = how far the token can see at all). */
  sight: Polygon;
}

export interface VisionInput {
  grid: Grid;
  sources: VisionSource[];
  /** Radius within which the token sees without light; null = ambient light, everything in sight is visible. */
  darkVisionRadius: number | null;
  lightPolygons: Polygon[];
}

/** Marks cells (row*cols+col) whose centre is currently visible. */
/** Cells whose centre lies inside any of the polygons. */
export function cellsInPolygons(grid: Grid, polygons: Polygon[]): Uint8Array {
  const out = new Uint8Array(grid.cols * grid.rows);
  for (const p of polygons) {
    const b = polygonBounds(p);
    if (!Number.isFinite(b.minX)) continue;
    const c0 = Math.max(0, Math.floor((b.minX - grid.originX) / grid.cellSize));
    const c1 = Math.min(grid.cols - 1, Math.floor((b.maxX - grid.originX) / grid.cellSize));
    const r0 = Math.max(0, Math.floor((b.minY - grid.originY) / grid.cellSize));
    const r1 = Math.min(grid.rows - 1, Math.floor((b.maxY - grid.originY) / grid.cellSize));
    for (let r = r0; r <= r1; r++) {
      const cy = grid.originY + (r + 0.5) * grid.cellSize;
      for (let c = c0; c <= c1; c++) {
        const idx = r * grid.cols + c;
        if (!out[idx] && pointInPolygon(grid.originX + (c + 0.5) * grid.cellSize, cy, p)) out[idx] = 1;
      }
    }
  }
  return out;
}

export function computeVisibleCells(input: VisionInput): Uint8Array {
  const { grid } = input;
  const visible = new Uint8Array(grid.cols * grid.rows);
  const lights = input.lightPolygons.map((p) => ({ p, b: polygonBounds(p) }));
  for (const src of input.sources) {
    const b = polygonBounds(src.sight);
    if (!Number.isFinite(b.minX)) {
      continue;
    }
    const c0 = Math.max(0, Math.floor((b.minX - grid.originX) / grid.cellSize));
    const c1 = Math.min(grid.cols - 1, Math.floor((b.maxX - grid.originX) / grid.cellSize));
    const r0 = Math.max(0, Math.floor((b.minY - grid.originY) / grid.cellSize));
    const r1 = Math.min(grid.rows - 1, Math.floor((b.maxY - grid.originY) / grid.cellSize));
    for (let r = r0; r <= r1; r++) {
      const cy = grid.originY + (r + 0.5) * grid.cellSize;
      for (let c = c0; c <= c1; c++) {
        const idx = r * grid.cols + c;
        if (visible[idx]) {
          continue;
        }
        const cx = grid.originX + (c + 0.5) * grid.cellSize;
        if (!pointInPolygon(cx, cy, src.sight)) {
          continue;
        }
        if (input.darkVisionRadius === null) {
          visible[idx] = 1;
          continue;
        }
        const dx = cx - src.x;
        const dy = cy - src.y;
        if (dx * dx + dy * dy <= input.darkVisionRadius * input.darkVisionRadius) {
          visible[idx] = 1;
          continue;
        }
        for (const l of lights) {
          if (cx >= l.b.minX && cx <= l.b.maxX && cy >= l.b.minY && cy <= l.b.maxY && pointInPolygon(cx, cy, l.p)) {
            visible[idx] = 1;
            break;
          }
        }
      }
    }
  }
  return visible;
}

/** Adds visible cells to `explored`; returns the newly explored ones as a flag array or null if none. */
export function mergeExplored(explored: Uint8Array, visible: Uint8Array): Uint8Array | null {
  let fresh: Uint8Array | null = null;
  for (let i = 0; i < visible.length; i++) {
    if (visible[i] && !explored[i]) {
      explored[i] = 1;
      fresh ??= new Uint8Array(visible.length);
      fresh[i] = 1;
    }
  }
  return fresh;
}
