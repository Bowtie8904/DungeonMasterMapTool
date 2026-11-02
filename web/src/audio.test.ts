import { describe, expect, it } from 'vitest';
import { busGains, dbToGain, musicPositionSeconds, oneShotsToStart, volumeCurve, MusicInfo } from './audio';

const player = { master: 1, music: 1, effects: 1, muted: false };
const music = (o: Partial<MusicInfo> = {}): MusicInfo => ({
  id: 'a', name: 'a', url: '/a/x.mp3', gainDb: 0, durationMs: 60000, playing: true, positionMs: 10000, loop: false, ...o,
});

describe('online audio helpers', () => {
  it('converts decibels and clamps', () => {
    expect(dbToGain(0)).toBeCloseTo(1);
    expect(dbToGain(-6.0206)).toBeCloseTo(0.5, 3);
    expect(dbToGain(NaN)).toBe(1);
    expect(dbToGain(100)).toBeCloseTo(dbToGain(24));
  });

  it('applies the host volume curve', () => {
    expect(volumeCurve(0.5)).toBeCloseTo(0.5 ** 2.2);
    expect(volumeCurve(2)).toBe(1);
  });

  it('uses only the player volumes', () => {
    const g = busGains({}, { ...player, music: 0.5, effects: 0.5 });
    expect(g.master).toBe(1);
    expect(g.music).toBeCloseTo(0.5 ** 2.2);
    expect(g.effects).toBeCloseTo(0.5 ** 2.2);
    expect(busGains({ panic: true }, player).master).toBe(0);
    expect(busGains({}, { ...player, muted: true }).master).toBe(0);
    expect(busGains({ effectsPaused: true }, player).effects).toBe(0);
  });

  it('computes the music position', () => {
    expect(musicPositionSeconds(music(), 1000, 3000)).toBeCloseTo(12);
    expect(musicPositionSeconds(music({ playing: false }), 1000, 3000)).toBeCloseTo(10);
    expect(musicPositionSeconds(music({ positionMs: 59000 }), 0, 5000)).toBeNull();
    expect(musicPositionSeconds(music({ positionMs: 59000, loop: true }), 0, 5000)).toBeCloseTo(4);
  });

  it('starts only newly added one-shots', () => {
    expect(oneShotsToStart(undefined, 3)).toBe(0);
    expect(oneShotsToStart(1, 3)).toBe(2);
    expect(oneShotsToStart(3, 1)).toBe(0);
  });
});
