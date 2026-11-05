import { DEFAULT_MAX_PARTICLES } from './weather';

function num(key: string, fallback: number): number {
  const v = Number(localStorage.getItem(key));
  return localStorage.getItem(key) !== null && Number.isFinite(v) ? v : fallback;
}

export const settings = {
  get volume(): number {
    return Math.max(0, Math.min(1, num('dmmt.volume', 0.8)));
  },
  set volume(v: number) {
    localStorage.setItem('dmmt.volume', String(v));
  },
  get musicVolume(): number {
    return Math.max(0, Math.min(1, num('dmmt.musicVolume', 0.8)));
  },
  set musicVolume(v: number) {
    localStorage.setItem('dmmt.musicVolume', String(v));
  },
  get effectsVolume(): number {
    return Math.max(0, Math.min(1, num('dmmt.effectsVolume', 0.8)));
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
};

/** Records that the user clicked "Join" (a user gesture), so audio may start later. */
export function markAudioGesture(): void {
  sessionStorage.setItem('dmmt.audioGesture', '1');
}

export function hasAudioGesture(): boolean {
  return sessionStorage.getItem('dmmt.audioGesture') === '1';
}
