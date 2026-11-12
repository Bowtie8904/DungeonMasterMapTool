import { describe, expect, it } from 'vitest';
import { lightActive, lightFalloff, rasterLightTint, rasterShapeGlow, TintEnvironment, TintLight } from './lighting';
import { Overlay } from './protocol';
import { Grid } from './vision';

const grid: Grid = { originX: 0, originY: 0, cols: 2, rows: 1, cellSize: 1 };
const night: TintEnvironment = { night: true, darkness: 0.9 };
const light = (color: TintLight['color'] = [255, 0, 0], strength = 1): TintLight => ({
  x: 0.5, y: 0.5, range: 10, strength, color,
  polygon: { xs: [-10, 10, 10, -10], ys: [-10, -10, 10, 10] },
});
const indoor: TintEnvironment['indoor'] = { grid, inside: Uint8Array.of(1, 0), darkness: 0.9 };

describe('desktop shape glow parity', () => {
  const shape: Overlay = {
    id: 'effect', type: 'rect', x: -10, y: -10, width: 20, height: 20, radius: 10,
    strokeWidth: 20, color: '#ff0000', alpha: 1, texture: 'fire', border: false, points: [0, 0],
  };
  function raster(o: Overlay, range = 10) {
    const result = rasterShapeGlow(o, range);
    if (!result) throw new Error('Expected shape glow');
    const { grid: g, pixels } = result;
    return {
      ...result,
      at(x: number, y: number) {
        const col = Math.floor((x - g.originX) / g.cellSize);
        const row = Math.floor((y - g.originY) / g.cellSize);
        return {
          alpha: pixels[(row * g.cols + col) * 4 + 3],
          x: g.originX + (col + 0.5) * g.cellSize,
          y: g.originY + (row + 0.5) * g.cellSize,
        };
      },
    };
  }

  it.each(['circle', 'rect', 'brush'])('fades continuously beyond the %s edge with no brightness step', type => {
    const glow = raster({ ...shape, type, x: type === 'circle' ? 0 : shape.x, y: type === 'circle' ? 0 : shape.y });
    expect(glow.at(0, 0).alpha).toBe(255);
    const edge = glow.at(10, 0);
    expect(edge.alpha).toBeGreaterThan(250);
    const halfway = glow.at(15, 0);
    const distance = type === 'rect' ? halfway.x - 10 : Math.hypot(halfway.x, halfway.y) - 10;
    const t = distance / 10;
    expect(halfway.alpha).toBe(Math.round(255 * (1 - t * t * (3 - 2 * t))));
    expect(glow.at(19, 0).alpha).toBeLessThan(12);
    expect(glow.at(20, 0).alpha).toBe(0);
    expect(glow.pixels[3]).toBe(0);
  });

  it('uses Euclidean distance around box corners', () => {
    const sample = raster(shape).at(15, 15);
    const t = Math.hypot(sample.x - 10, sample.y - 10) / 10;
    expect(sample.alpha).toBe(Math.round(255 * (1 - t * t * (3 - 2 * t))));
  });

  it('lights the union of brush segments without seams or double-strength crossings', () => {
    const glow = raster({ ...shape, type: 'brush', points: [-20, 0, 20, 0, 0, -20, 0, 20, 0, 20] });
    expect(glow.at(0, 0).alpha).toBe(255);
    expect(glow.at(0, 15).alpha).toBe(255);
    expect(glow.at(15, 0).alpha).toBe(255);
  });

  it('shrinks reach with flicker without reducing the inside mask', () => {
    const full = raster(shape);
    const flickering = raster(shape, 7.5);
    expect(flickering.at(0, 0).alpha).toBe(255);
    expect(flickering.at(17, 0).alpha).toBeLessThan(full.at(17, 0).alpha);
  });

  it('supports zero range and bounds memory even for very large, thin shapes', () => {
    const zero = raster(shape, 0);
    expect(zero.at(0, 0).alpha).toBe(255);
    expect(zero.pixels[3]).toBe(0);
    const large = raster({ ...shape, width: 1e7, height: 1 });
    expect(large.grid.cols).toBeLessThanOrEqual(386);
    expect(large.grid.rows).toBeLessThanOrEqual(386);
  });

  it('never treats annotation strokes or empty brushes as emitters', () => {
    for (const type of ['pen', 'line', 'unknown', 'brush']) {
      expect(rasterShapeGlow({ ...shape, type, points: [] }, 10)).toBeNull();
    }
  });
});

describe('desktop light tint parity', () => {
  it('caps an 8% tint once, regardless of how many lights overlap', () => {
    const expected = Math.round(0.08 * Math.sqrt(0.9 * 0.65) * 255);
    const one = rasterLightTint(grid, [light()], night, 0.08, 0.65);
    const many = rasterLightTint(grid, Array.from({ length: 10 }, () => light()), night, 0.08, 0.65);
    expect(one[3]).toBe(expected);
    expect(many).toEqual(one);
    expect(many[3] / 255).toBeLessThanOrEqual(0.08);
  });

  it('blends colours by contribution, independent of source order', () => {
    const lights = [light([255, 0, 0], 0.75), light([0, 0, 255], 0.25)];
    const pixels = rasterLightTint(grid, lights, night, 0.08, 1);
    expect(Array.from(pixels.slice(0, 3))).toEqual([191, 0, 64]);
    expect(rasterLightTint(grid, lights.reverse(), night, 0.08, 1)).toEqual(pixels);
  });

  it('accumulates dim contributions before clamping brightness', () => {
    const one = rasterLightTint(grid, [light([255, 0, 0], 0.25)], night, 0.08, 1);
    const two = rasterLightTint(grid, [light([255, 0, 0], 0.25), light([255, 0, 0], 0.25)], night, 0.08, 1);
    expect(two[3]).toBe(Math.round(0.08 * Math.sqrt(0.9) * 0.5 * 255));
    expect(two[3]).toBeGreaterThan(one[3]);
  });

  it.each(['DAY', 'DAWN', 'DUSK'])('disables outdoor sources at %s even with dark rooms', () => {
    const env: TintEnvironment = { night: false, darkness: 0.4, indoor };
    const outside = { ...light(), x: 1.5 };
    expect(lightActive(env, outside.x, outside.y)).toBe(false);
    expect(rasterLightTint(grid, [outside], env, 0.08, 0.65)).toEqual(new Uint8ClampedArray(8));
  });

  it('keeps indoor lights at Day but never tints outdoor pixels', () => {
    const env: TintEnvironment = { night: false, darkness: 0, indoor };
    expect(lightActive(env, 0.5, 0.5)).toBe(true);
    const pixels = rasterLightTint(grid, [light()], env, 0.08, 0.65);
    expect(pixels[3]).toBe(Math.round(0.08 * Math.sqrt(0.9 * 0.65) * 255));
    expect(pixels[7]).toBe(0);
  });

  it('removes all daytime tint with indoor darkness disabled or no rooms', () => {
    const env: TintEnvironment = { night: false, darkness: 0 };
    expect(lightActive(env, 0.5, 0.5)).toBe(false);
    expect(rasterLightTint(grid, [light()], env, 0.08, 0.65)).toEqual(new Uint8ClampedArray(8));
  });

  it('honours zero tint, zero darkness, zero DM factor and no lights', () => {
    for (const [darkness, tint, factor, lights] of [
      [0.9, 0, 0.65, [light()]],
      [0, 0.08, 0.65, [light()]],
      [0.9, 0.08, 0, [light()]],
      [0.9, 0.08, 0.65, []],
    ] as const) {
      expect(rasterLightTint(grid, [...lights], { night: true, darkness }, tint, factor)).toEqual(new Uint8ClampedArray(8));
    }
  });

  it('clips to the visibility polygon and light radius', () => {
    const clipped = { ...light(), polygon: { xs: [0, 1, 1, 0], ys: [0, 0, 1, 1] } };
    expect(rasterLightTint(grid, [clipped], night, 0.08, 1)[7]).toBe(0);
    expect(rasterLightTint(grid, [{ ...light(), range: 0.5 }], night, 0.08, 1)[7]).toBe(0);
  });

  it('uses the desktop bright/dim falloff instead of a linear gradient', () => {
    expect(lightFalloff(0)).toBe(1);
    expect(lightFalloff(0.5)).toBe(1);
    expect(lightFalloff(0.75)).toBe(0.29296875);
    expect(lightFalloff(1)).toBe(0);
    expect(lightFalloff(2)).toBe(0);
    const dim = { ...light(), range: 4 / 3 };
    expect(rasterLightTint(grid, [dim], night, 0.08, 1)[7])
      .toBe(Math.round(0.08 * Math.sqrt(0.9) * lightFalloff(0.75) * 255));
  });
});
