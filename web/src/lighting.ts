import { Grid, Polygon } from './vision';
import { Overlay } from './protocol';

/** Desktop emitShapeLight geometry and smoothstep, sampled in world space with a transparent border. */
export function rasterShapeGlow(shape: Overlay, range: number): { grid: Grid; pixels: Uint8ClampedArray } | null {
  let x0: number, y0: number, x1: number, y1: number;
  const points: number[] = [];
  let inner = 0;
  if (shape.type === 'circle') {
    inner = shape.radius;
    points.push(shape.x, shape.y);
    x0 = shape.x - inner; x1 = shape.x + inner;
    y0 = shape.y - inner; y1 = shape.y + inner;
  } else if (shape.type === 'rect') {
    x0 = shape.x; y0 = shape.y;
    x1 = x0 + shape.width; y1 = y0 + shape.height;
  } else if (shape.type === 'brush' && shape.points.length >= 2) {
    const n = Math.floor(shape.points.length / 2);
    const step = Math.max(1, Math.ceil((n - 1) / 32));
    for (let i = 0; i <= Math.ceil((n - 1) / step); i++) {
      const src = Math.min(n - 1, i * step) * 2;
      points.push(shape.points[src], shape.points[src + 1]);
    }
    inner = shape.strokeWidth / 2;
    x0 = Infinity; y0 = Infinity; x1 = -Infinity; y1 = -Infinity;
    for (let i = 0; i < points.length; i += 2) {
      x0 = Math.min(x0, points[i] - inner); x1 = Math.max(x1, points[i] + inner);
      y0 = Math.min(y0, points[i + 1] - inner); y1 = Math.max(y1, points[i + 1] + inner);
    }
  } else {
    return null;
  }
  const glow = Math.max(0, range);
  const spanX = x1 - x0 + 2 * glow;
  const spanY = y1 - y0 + 2 * glow;
  const cellSize = Math.max(glow / 16, Math.max(spanX, spanY) / 384, 0.001);
  const grid: Grid = {
    originX: x0 - glow - cellSize, originY: y0 - glow - cellSize, cellSize,
    cols: Math.ceil(spanX / cellSize) + 2, rows: Math.ceil(spanY / cellSize) + 2,
  };
  const pixels = new Uint8ClampedArray(grid.cols * grid.rows * 4);
  for (let row = 0; row < grid.rows; row++) {
    const y = grid.originY + (row + 0.5) * cellSize;
    for (let col = 0; col < grid.cols; col++) {
      const x = grid.originX + (col + 0.5) * cellSize;
      let distance: number;
      if (points.length === 0) {
        distance = Math.hypot(Math.max(x0 - x, 0, x - x1), Math.max(y0 - y, 0, y - y1));
      } else {
        let best = Math.hypot(x - points[0], y - points[1]);
        for (let i = 0; i + 3 < points.length; i += 2) {
          const dx = points[i + 2] - points[i], dy = points[i + 3] - points[i + 1];
          const len2 = dx * dx + dy * dy;
          const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - points[i]) * dx + (y - points[i + 1]) * dy) / len2));
          best = Math.min(best, Math.hypot(x - points[i] - t * dx, y - points[i + 1] - t * dy));
        }
        distance = Math.max(0, best - inner);
      }
      const t = glow > 0 ? Math.min(1, distance / glow) : distance > 0 ? 1 : 0;
      pixels[(row * grid.cols + col) * 4 + 3] = Math.round(255 * (1 - t * t * (3 - 2 * t)));
    }
  }
  return { grid, pixels };
}

export interface TintEnvironment {
  night: boolean;
  darkness: number;
  indoor?: { grid: Grid; inside: Uint8Array; darkness: number };
}

export interface TintLight {
  x: number;
  y: number;
  range: number;
  strength: number;
  color: [number, number, number];
  polygon: Polygon;
}

function isInside(env: TintEnvironment, x: number, y: number): boolean {
  const ind = env.indoor;
  if (!ind) return false;
  const g = ind.grid;
  const col = Math.floor((x - g.originX) / g.cellSize);
  const row = Math.floor((y - g.originY) / g.cellSize);
  return col >= 0 && col < g.cols && row >= 0 && row < g.rows && ind.inside[row * g.cols + col] !== 0;
}

export function lightActive(env: TintEnvironment, x: number, y: number): boolean {
  return env.night || isInside(env, x, y);
}

/** CanvasMapRenderer's bright inner half and windowed inverse-square dim band. */
export function lightFalloff(distance: number): number {
  if (distance <= 0.5) return 1;
  if (distance >= 1) return 0;
  const u = (distance - 0.5) * 2;
  const window = 1 - u ** 4;
  return window * window / (1 + 8 * u * u);
}

export interface TintScratch {
  size: number;
  total: Float32Array;
  red: Float32Array;
  green: Float32Array;
  blue: Float32Array;
}

/** One alpha-blended highlight, never one translucent overlay per light. Pass a scratch object to reuse buffers across frames. */
export function rasterLightTint(grid: Grid, lights: TintLight[], env: TintEnvironment,
  tint: number, darknessFactor: number, scratch?: TintScratch): Uint8ClampedArray {
  const size = grid.cols * grid.rows;
  let buffers: TintScratch;
  if (scratch && scratch.size === size) {
    buffers = scratch;
    buffers.total.fill(0);
    buffers.red.fill(0);
    buffers.green.fill(0);
    buffers.blue.fill(0);
  } else {
    buffers = { size, total: new Float32Array(size), red: new Float32Array(size), green: new Float32Array(size), blue: new Float32Array(size) };
    if (scratch) Object.assign(scratch, buffers);
  }
  const { total, red, green, blue } = buffers;
  const out = new Uint8ClampedArray(size * 4);
  for (const l of lights) {
    if (l.range <= 0 || !lightActive(env, l.x, l.y)) continue;
    const p = l.polygon;
    const first = Math.max(0, Math.ceil((l.y - l.range - grid.originY) / grid.cellSize - 0.5));
    const end = Math.min(grid.rows - 1, Math.floor((l.y + l.range - grid.originY) / grid.cellSize - 0.5));
    for (let row = first; row <= end; row++) {
      const y = grid.originY + (row + 0.5) * grid.cellSize;
      const crossings: number[] = [];
      for (let i = 0, j = p.xs.length - 1; i < p.xs.length; j = i++) {
        if ((p.ys[i] > y) !== (p.ys[j] > y)) {
          crossings.push(p.xs[i] + (y - p.ys[i]) * (p.xs[j] - p.xs[i]) / (p.ys[j] - p.ys[i]));
        }
      }
      crossings.sort((a, b) => a - b);
      for (let c = 0; c + 1 < crossings.length; c += 2) {
        const left = Math.max(0, Math.ceil((Math.max(crossings[c], l.x - l.range) - grid.originX) / grid.cellSize - 0.5));
        const right = Math.min(grid.cols - 1, Math.floor((Math.min(crossings[c + 1], l.x + l.range) - grid.originX) / grid.cellSize - 0.5));
        for (let col = left; col <= right; col++) {
          const x = grid.originX + (col + 0.5) * grid.cellSize;
          const dx = x - l.x;
          const dy = y - l.y;
          const value = l.strength * lightFalloff(Math.sqrt(dx * dx + dy * dy) / l.range);
          const i = row * grid.cols + col;
          total[i] += value;
          red[i] += value * l.color[0];
          green[i] += value * l.color[1];
          blue[i] += value * l.color[2];
        }
      }
    }
  }
  for (let row = 0; row < grid.rows; row++) {
    for (let col = 0; col < grid.cols; col++) {
      const i = row * grid.cols + col;
      if (total[i] <= 0) continue;
      const inside = isInside(env, grid.originX + (col + 0.5) * grid.cellSize, grid.originY + (row + 0.5) * grid.cellSize);
      if (!env.night && !inside) continue;
      const darkness = inside ? env.indoor!.darkness : env.darkness;
      const alpha = Math.min(1, Math.max(0, tint) * Math.sqrt(Math.max(0, darkness * darknessFactor)) * Math.min(1, total[i]));
      if (alpha <= 0.002) continue;
      out[i * 4] = Math.round(red[i] / total[i]);
      out[i * 4 + 1] = Math.round(green[i] / total[i]);
      out[i * 4 + 2] = Math.round(blue[i] / total[i]);
      out[i * 4 + 3] = Math.round(alpha * 255);
    }
  }
  return out;
}
