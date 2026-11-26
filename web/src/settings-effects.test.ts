import { beforeEach, describe, expect, it, vi } from 'vitest';
import { REDUCED_MAX_PARTICLES, settings } from './settings';

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
});

describe('performance settings', () => {
  beforeEach(() => store.clear());

  it('defaults to full effects', () => {
    expect(settings.showWeather).toBe(true);
    expect(settings.reduceParticles).toBe(false);
    expect(settings.lightningFlashes).toBe(true);
    expect(settings.lightFlicker).toBe(true);
    expect(settings.animatedTextures).toBe(true);
    expect(settings.lowResource).toBe(false);
    expect(settings.effectiveMaxParticles).toBe(settings.maxParticles);
  });

  it('reduce particles caps the count', () => {
    settings.reduceParticles = true;
    expect(settings.effectiveMaxParticles).toBeLessThanOrEqual(REDUCED_MAX_PARTICLES);
  });

  it('toggles are independent', () => {
    settings.lightFlicker = false;
    expect(settings.animatedTextures).toBe(true);
    expect(settings.showWeather).toBe(true);
  });

  it('low-resource mode halves the effective particle cap without changing other preferences', () => {
    store.set('dmmt.maxParticles', '500');
    settings.lowResource = true;
    expect(settings.effectiveMaxParticles).toBe(250);
    expect(settings.showWeather).toBe(true);
    expect(settings.lightFlicker).toBe(true);
    settings.reduceParticles = true;
    expect(settings.effectiveMaxParticles).toBe(150);
    settings.lowResource = false;
    expect(settings.effectiveMaxParticles).toBe(300);
  });
});
