import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { computeVisibility } from './vision';
import { unit, wrap } from './weather';

const file = new URL('../../src/test/resources/online/vectors.json', import.meta.url);
const present = existsSync(file);

interface Vectors {
  weatherUnit?: Array<{ index: number; salt: number; value: number }>;
  wrap?: Array<{ value: number; span: number; result: number }>;
  visibility?: Array<{ light: [number, number]; range: number; segments: number[]; polygon: number[] }>;
}

describe.skipIf(!present)('shared Java/TS vectors', () => {
  const v: Vectors = present ? JSON.parse(readFileSync(file, 'utf8')) : {};

  it('weather unit', () => {
    for (const e of v.weatherUnit ?? []) {
      expect(unit(e.index, e.salt)).toBe(e.value);
    }
  });

  it('wrap', () => {
    for (const e of v.wrap ?? []) {
      expect(wrap(e.value, e.span)).toBeCloseTo(e.result, 9);
    }
  });

  it('visibility polygons', () => {
    for (const e of v.visibility ?? []) {
      const p = computeVisibility(e.light[0], e.light[1], e.range, e.segments);
      expect(p.xs.length * 2).toBe(e.polygon.length);
      for (let i = 0; i < p.xs.length; i++) {
        expect(p.xs[i]).toBeCloseTo(e.polygon[i * 2], 6);
        expect(p.ys[i]).toBeCloseTo(e.polygon[i * 2 + 1], 6);
      }
    }
  });
});
