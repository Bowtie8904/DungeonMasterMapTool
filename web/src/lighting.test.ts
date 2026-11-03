import { describe, expect, it } from 'vitest';
import { lightActive, lightFalloff, rasterLightTint, TintEnvironment, TintLight } from './lighting';
import { Grid } from './vision';

const grid: Grid = { originX: 0, originY: 0, cols: 2, rows: 1, cellSize: 1 };
const night: TintEnvironment = { night: true, darkness: 0.9 };
const light = (color: TintLight['color'] = [255, 0, 0], strength = 1): TintLight => ({
  x: 0.5, y: 0.5, range: 10, strength, color,
  polygon: { xs: [-10, 10, 10, -10], ys: [-10, -10, 10, 10] },
});
const indoor: TintEnvironment['indoor'] = { grid, inside: Uint8Array.of(1, 0), darkness: 0.9 };

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
