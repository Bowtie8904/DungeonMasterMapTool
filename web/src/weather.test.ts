import { describe, expect, it } from 'vitest';
import { lightningFlash, particleCount, unit, wrap, weatherKind } from './weather';

describe('weather', () => {
  it('unit matches the Java hash', () => {
    expect(unit(0, 1)).toBe(0.9491980975133871);
    expect(unit(1, 2)).toBe(0.8007812536897415);
    expect(unit(5, 3)).toBe(0.30389293046642485);
    expect(unit(-1, 7)).toBe(0.3649436379548846);
    expect(unit(1000, 84)).toBe(0.016624264490738838);
  });

  it('unit stays in [0,1)', () => {
    for (let i = 0; i < 500; i++) {
      const v = unit(i, 3);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });

  it('wrap handles negatives', () => {
    expect(wrap(-1, 10)).toBe(9);
    expect(wrap(25, 10)).toBe(5);
    expect(wrap(0, 10)).toBe(0);
  });

  it('particle count scales and caps', () => {
    expect(particleCount('none', 1, 1920, 1080)).toBe(0);
    expect(particleCount('rain', 1, 1920, 1080)).toBe(600);
    expect(particleCount('rain', 0.5, 1920, 1080)).toBe(300);
    expect(particleCount('rain', 1, 1920, 1080, 150)).toBe(150);
    expect(particleCount('mist', 0.2, 100, 100)).toBe(70);
    expect(weatherKind('Thunderstorm')).toBe('thunderstorm');
    expect(weatherKind('bogus')).toBe('none');
  });

  it('lightning is periodic, bounded and zero for invalid input', () => {
    expect(lightningFlash(0, 7)).toBe(0);
    expect(lightningFlash(NaN, 7)).toBe(0);
    let max = 0;
    for (let t = 0; t < 60; t += 0.01) {
      const f = lightningFlash(t, 7);
      expect(f).toBeGreaterThanOrEqual(0);
      expect(f).toBeLessThanOrEqual(1);
      max = Math.max(max, f);
    }
    expect(max).toBeGreaterThan(0.5);
  });
});

import { weatherIndoors } from './weather';
describe('weatherIndoors', () => {
  it('defaults rain outdoors-only and mist indoors', () => {
    expect(weatherIndoors({ type: 'RAIN' } as never)).toBe(false);
    expect(weatherIndoors({ type: 'MIST' } as never)).toBe(true);
    expect(weatherIndoors({ type: 'RAIN', indoors: true } as never)).toBe(true);
  });
});
