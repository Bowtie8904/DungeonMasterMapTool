import { describe, expect, it } from 'vitest';
import { flickerAmount, javaHash } from './flicker';

describe('flicker', () => {
  it('matches Java String.hashCode', () => {
    expect(javaHash('hello')).toBe(99162322);
    expect(javaHash('')).toBe(0);
  });

  it('stays within range and is steady without strength', () => {
    expect(flickerAmount('a', 0, 1, 1234)).toBe(0);
    for (let t = 0; t < 5000; t += 37) {
      const v = flickerAmount('light-1', 0.6, 1, t);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
    }
  });

  it('is deterministic per id', () => {
    expect(flickerAmount('x', 0.5, 1, 1000)).toBe(flickerAmount('x', 0.5, 1, 1000));
    expect(flickerAmount('x', 0.5, 1, 1000)).not.toBe(flickerAmount('y', 0.5, 1, 1000));
  });
});
