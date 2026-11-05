import { describe, expect, it } from 'vitest';
import { TokenMotion } from './motion';

describe('TokenMotion', () => {
  it('shows new tokens at their position without animating', () => {
    const m = new TokenMotion();
    expect(m.position('a', 10, 20, 50, 0)).toEqual({ x: 10, y: 20 });
    expect(m.animating).toBe(false);
  });

  it('glides a single move by distance and settles on the target', () => {
    const m = new TokenMotion();
    m.position('a', 0, 0, 50, 0);
    // First update after a long idle: 2 cells -> 120 ms glide.
    expect(m.position('a', 100, 0, 50, 1000)).toEqual({ x: 0, y: 0 });
    expect(m.animating).toBe(true);
    expect(m.position('a', 100, 0, 50, 1060).x).toBeCloseTo(50);
    m.animating = false;
    expect(m.position('a', 100, 0, 50, 1120)).toEqual({ x: 100, y: 0 });
    expect(m.animating).toBe(false);
  });

  it('spans the update interval while a stream of moves arrives and continues from the shown position', () => {
    const m = new TokenMotion();
    m.position('a', 0, 0, 50, 0);
    m.position('a', 25, 0, 50, 1000);
    // Next update 50 ms later: glide 62.5 ms from the currently shown point.
    const shown = m.position('a', 25, 0, 50, 1050);
    m.position('a', 50, 0, 50, 1050);
    expect(m.position('a', 50, 0, 50, 1050).x).toBeCloseTo(shown.x);
    expect(m.position('a', 50, 0, 50, 1050 + 62.5).x).toBeCloseTo(50);
  });

  it('snaps long jumps and settled tokens', () => {
    const m = new TokenMotion();
    m.position('a', 0, 0, 50, 0);
    expect(m.position('a', 50 * 41, 0, 50, 1000)).toEqual({ x: 2050, y: 0 });
    m.settle('a', 7, 8, 1000);
    expect(m.position('a', 7, 8, 50, 1001)).toEqual({ x: 7, y: 8 });
  });

  it('forgets removed tokens', () => {
    const m = new TokenMotion();
    m.position('a', 0, 0, 50, 0);
    m.position('b', 0, 0, 50, 0);
    m.retain(new Set(['b']));
    expect(m.position('a', 100, 0, 50, 1000)).toEqual({ x: 100, y: 0 });
    expect(m.position('b', 100, 0, 50, 1000)).toEqual({ x: 0, y: 0 });
  });
});
