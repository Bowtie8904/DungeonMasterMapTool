import { DEFAULT_MAX_PARTICLES } from './weather';

export const REDUCED_MAX_PARTICLES = 300;

function flag(key: string, fallback: boolean): boolean {
  const v = localStorage.getItem(key);
  return v === null ? fallback : v === '1';
}

function setFlag(key: string, on: boolean): void {
  localStorage.setItem(key, on ? '1' : '0');
}

function num(key: string, fallback: number): number {
  const v = Number(localStorage.getItem(key));
  return localStorage.getItem(key) !== null && Number.isFinite(v) ? v : fallback;
}

export const settings = {
  get volume(): number {
    return Math.max(0, Math.min(1, num('dmmt.volume', 0.5)));
  },
  set volume(v: number) {
    localStorage.setItem('dmmt.volume', String(v));
  },
  get musicVolume(): number {
    return Math.max(0, Math.min(1, num('dmmt.musicVolume', 0.5)));
  },
  set musicVolume(v: number) {
    localStorage.setItem('dmmt.musicVolume', String(v));
  },
  get effectsVolume(): number {
    return Math.max(0, Math.min(1, num('dmmt.effectsVolume', 0.5)));
  },
  set effectsVolume(v: number) {
    localStorage.setItem('dmmt.effectsVolume', String(v));
  },
  get muted(): boolean {
    return localStorage.getItem('dmmt.muted') === '1';
  },
  set muted(m: boolean) {
    localStorage.setItem('dmmt.muted', m ? '1' : '0');
  },
  get maxParticles(): number {
    return Math.max(0, num('dmmt.maxParticles', DEFAULT_MAX_PARTICLES));
  },
  get smoothTokens(): boolean {
    return localStorage.getItem('dmmt.smoothTokens') !== '0';
  },
  set smoothTokens(on: boolean) {
    localStorage.setItem('dmmt.smoothTokens', on ? '1' : '0');
  },
  get tokenResources(): boolean {
    return localStorage.getItem('dmmt.tokenResources') === '1';
  },
  set tokenResources(on: boolean) {
    localStorage.setItem('dmmt.tokenResources', on ? '1' : '0');
  },
  /** Performance toggles, each independent; they only change this browser's drawing. */
  get showWeather(): boolean {
    return flag('dmmt.showWeather', true);
  },
  set showWeather(on: boolean) {
    setFlag('dmmt.showWeather', on);
  },
  get reduceParticles(): boolean {
    return flag('dmmt.reduceParticles', false);
  },
  set reduceParticles(on: boolean) {
    setFlag('dmmt.reduceParticles', on);
  },
  get lightningFlashes(): boolean {
    return flag('dmmt.lightningFlashes', true);
  },
  set lightningFlashes(on: boolean) {
    setFlag('dmmt.lightningFlashes', on);
  },
  get lightFlicker(): boolean {
    return flag('dmmt.lightFlicker', true);
  },
  set lightFlicker(on: boolean) {
    setFlag('dmmt.lightFlicker', on);
  },
  get simpleLighting(): boolean {
    return flag('dmmt.simpleLighting', false);
  },
  set simpleLighting(on: boolean) {
    setFlag('dmmt.simpleLighting', on);
  },
  get animatedTextures(): boolean {
    return flag('dmmt.animatedTextures', true);
  },
  set animatedTextures(on: boolean) {
    setFlag('dmmt.animatedTextures', on);
  },
  /** Particle cap after "Reduce weather particles". */
  get effectiveMaxParticles(): number {
    return this.reduceParticles ? Math.min(this.maxParticles, REDUCED_MAX_PARTICLES) : this.maxParticles;
  },
};

/** Records that the user clicked "Join" (a user gesture), so audio may start later. */
export function markAudioGesture(): void {
  sessionStorage.setItem('dmmt.audioGesture', '1');
}

export function hasAudioGesture(): boolean {
  return sessionStorage.getItem('dmmt.audioGesture') === '1';
}
