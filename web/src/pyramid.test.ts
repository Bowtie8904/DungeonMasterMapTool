import { describe, expect, it } from 'vitest';
import { chooseLevel, levelSize, PyramidInfo, tileUrl, visibleTiles } from './pyramid';

const p: PyramidInfo = { key: 'k', width: 10000, height: 5000, tileSize: 1024, levels: 2, format: 'jpg' };

describe('pyramid', () => {
  it('halves sizes rounding up like the desktop builder', () => {
    expect(levelSize(10001, 1)).toBe(5001);
    expect(levelSize(10001, 2)).toBe(2501);
  });

  it('picks the finest level that still meets screen resolution', () => {
    expect(chooseLevel(p, 1)).toBe(0);
    expect(chooseLevel(p, 2)).toBe(0);
    expect(chooseLevel(p, 0.6)).toBe(0);
    expect(chooseLevel(p, 0.5)).toBe(1);
    expect(chooseLevel(p, 0.3)).toBe(1);
    expect(chooseLevel(p, 0.1)).toBe(2);
  });

  it('lists only overlapping tiles with source-pixel bounds', () => {
    const tiles = visibleTiles(p, 0, 1000, 0, 2100, 100);
    expect(tiles.map((t) => [t.tx, t.ty])).toEqual([[0, 0], [1, 0], [2, 0]]);
    const last = visibleTiles(p, 0, 9999, 4999, 20000, 20000);
    expect(last).toHaveLength(1);
    expect(last[0].x + last[0].w).toBe(10000);
    expect(last[0].y + last[0].h).toBe(5000);
    const coarse = visibleTiles(p, 1, 0, 0, 10000, 5000);
    expect(coarse).toHaveLength(5 * 3);
    expect(coarse[0].w).toBe(2048);
  });

  it('builds tile urls', () => {
    expect(tileUrl(p, 1, 3, 4)).toBe('/t/k/1/3_4.jpg');
  });
});
