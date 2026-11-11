import { Grid, Polygon } from './vision';

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
