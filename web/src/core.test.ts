import { describe, expect, it } from 'vitest';
import { ClockSync } from './clock';
import { Store } from './store';
import { backoffDelay } from './net';

describe('reconnect backoff', () => {
  it('grows and is capped', () => {
    expect(backoffDelay(0)).toBe(500);
    expect(backoffDelay(2)).toBe(2000);
    expect(backoffDelay(50)).toBe(15000);
  });
});
import { GameState, type RollMessage } from './protocol';

describe('ClockSync', () => {
  it('keeps the offset of the lowest round trip', () => {
    const c = new ClockSync();
    c.addSample(1000, 5100, 1200); // rtt 200 -> offset 5100-1100 = 4000
    expect(c.offset).toBe(4000);
    c.addSample(2000, 6050, 2100); // rtt 100 -> offset 6050-2050 = 4000
    c.addSample(3000, 9000, 3500); // rtt 500, ignored
    expect(c.bestRtt).toBe(100);
    expect(c.offset).toBe(4000);
    expect(c.serverNow(10)).toBe(4010);
  });

  it('ignores invalid samples', () => {
    const c = new ClockSync();
    c.addSample(100, 0, 50);
    expect(c.samples).toBe(0);
  });
});

const snapshot = (): Partial<GameState> => ({
  mapId: 'm',
  levelId: 'l',
  tokens: [{ id: 't1', x: 1, y: 2, sizeTiles: 1 }],
  doors: [],
});

describe('Store', () => {
  it('merges after-roll dice additions while retaining original Duality dice and source', () => {
    const s = new Store();
    const roll: RollMessage = { id: 4, ts: 5, fromId: 'p', from: 'Player', kind: 'duality',
      expression: '2d12', rolls: [4, 5], total: 9, source: 'action,trait:Presence' };
    s.applySnapshot(1, { ...snapshot(), chatTail: [{ ...roll, type: 'roll' }] });
    expect(s.applyRollUpdate({ type: 'rollUpdate', id: 4, ts: 5, expression: '2d12+1d4',
      rolls: [4, 5, 3], total: 12, breakdown: [{ label: 'Heart of a Poet', expression: '1d4', rolls: [3], total: 3 }] })).toBe(true);
    expect(s.state!.chatTail![0]).toMatchObject({ source: 'action,trait:Presence', expression: '2d12+1d4',
      rolls: [4, 5, 3], total: 12, breakdown: [{ total: 3 }] });
  });

  it('merges death recovery and feature consequences without losing original dice or omitted annotations', () => {
    const s = new Store();
    const roll: RollMessage = { id: 4, ts: 5, fromId: 'p', from: 'Player', kind: 'dice', deathMove: 'risk',
      expression: '2d12', rolls: [4, 5], total: 9,
      deathResult: { outcome: 'hope', hope: 4, fear: 5, adjustedHope: 5, bonus: 1, budget: 5, claimed: false, undying: false } };
    s.applySnapshot(1, { ...snapshot(), chatTail: [{ ...roll, type: 'roll' }] });
    const consequence = { name: 'Risk It All', result: 5, consequence: 'Cleared 2 HP and 3 Stress.' };
    expect(s.applyRollUpdate({ type: 'rollUpdate', id: 4, ts: 5, featureRoll: consequence,
      deathResult: { ...roll.deathResult!, claimed: true } })).toBe(true);
    const saved = s.state!.chatTail![0] as RollMessage;
    expect(saved.featureRoll).toEqual(consequence);
    expect(saved.deathResult!.claimed).toBe(true);
    expect(saved.rolls).toEqual([4, 5]);
    expect(saved.total).toBe(9);
    s.applyRollUpdate({ type: 'rollUpdate', id: 4, ts: 5, featureUses: ['a'] });
    expect(saved.featureRoll).toEqual(consequence);
    expect(saved.deathResult!.claimed).toBe(true);
    expect(s.applyRollUpdate({ type: 'rollUpdate', id: 4, ts: 6, featureRoll: consequence })).toBe(false);
  });
  it('replicates countdown creation, steps and last deletion while supporting older hosts', () => {
    const s = new Store();
    s.applySnapshot(1, snapshot());
    expect(s.state!.countdowns).toBeUndefined();
    const countdown = { id: 'one', name: 'Reinforcements', initialValue: 6, value: 6 };
    s.applyDelta(2, [{ op: 'set', path: 'countdowns', value: [countdown] }]);
    expect(s.state!.countdowns).toEqual([countdown]);
    expect(s.changed.has('countdowns')).toBe(true);
    s.applyDelta(3, [{ op: 'set', path: 'countdowns', value: [{ ...countdown, value: 0 }] }]);
    expect(s.state!.countdowns![0].value).toBe(0);
    s.applySnapshot(4, { ...snapshot(), countdowns: [{ ...countdown, value: 3 }] });
    expect(s.state!.countdowns![0].value).toBe(3);
    s.applyDelta(5, [{ op: 'set', path: 'countdowns', value: [] }]);
    expect(s.state!.countdowns).toEqual([]);
    s.applySnapshot(6, snapshot());
    expect(s.state!.countdowns).toBeUndefined();
  });

  it('applies snapshot then sequential deltas', () => {
    const s = new Store();
    s.applySnapshot(5, snapshot());
    expect(s.state!.visionTiles).toBe(8);
    expect(s.applyDelta(6, [{ op: 'set', path: 'paused', value: true }])).toBe('ok');
    expect(s.state!.paused).toBe(true);
    expect(s.changed.has('paused')).toBe(true);
    expect(
      s.applyDelta(7, [{ op: 'tokens', upsert: [{ id: 't1', x: 9, y: 9, sizeTiles: 1 }, { id: 't2', x: 0, y: 0, sizeTiles: 2 }], remove: [] }]),
    ).toBe('ok');
    expect(s.state!.tokens.map((t) => [t.id, t.x])).toEqual([['t1', 9], ['t2', 0]]);
    expect(s.applyDelta(8, [{ op: 'tokens', remove: ['t1'] }])).toBe('ok');
    expect(s.state!.tokens.map((t) => t.id)).toEqual(['t2']);
    expect(s.lastSeq).toBe(8);
  });

  it('detects gaps and stale deltas without changing state', () => {
    const s = new Store();
    expect(s.applyDelta(1, [])).toBe('gap');
    s.applySnapshot(5, snapshot());
    expect(s.applyDelta(7, [{ op: 'set', path: 'paused', value: true }])).toBe('gap');
    expect(s.state!.paused).toBe(false);
    expect(s.applyDelta(5, [])).toBe('stale');
    expect(s.lastSeq).toBe(5);
  });

  it('merges partial lighting/weather values and ignores unknown paths', () => {
    const s = new Store();
    s.applySnapshot(1, snapshot());
    s.applyDelta(2, [
      { op: 'set', path: 'lighting', value: { ambient: 0.2 } },
      { op: 'set', path: 'weather', value: { type: 'rain', seed: 4 } },
      { op: 'set', path: 'bogus', value: 1 },
    ]);
    expect(s.state!.lighting.ambient).toBe(0.2);
    expect(s.state!.weather.type).toBe('rain');
    expect(s.state!.weather.seed).toBe(4);
    expect(s.state!.weather.lightningIntervalSeconds).toBe(7);
    expect((s.state as unknown as Record<string, unknown>).bogus).toBeUndefined();
  });

  it('preserves optional DM tint scaling in snapshots and lighting updates', () => {
    const s = new Store();
    s.applySnapshot(1, { ...snapshot(), lighting: { ambient: 0.1, lightTint: 0.08, tintDarknessFactor: 0.65 } });
    expect(s.state!.lighting.tintDarknessFactor).toBe(0.65);
    s.applyDelta(2, [{ op: 'set', path: 'lighting', value: { ambient: 1, lightTint: 0.08, tintDarknessFactor: 0.4, timeOfDay: 'DAY' } }]);
    expect(s.state!.lighting.tintDarknessFactor).toBe(0.4);
    expect(s.state!.lighting.timeOfDay).toBe('DAY');
    s.applySnapshot(3, { ...snapshot(), lighting: { ambient: 0.1, lightTint: 0.08 } });
    expect(s.state!.lighting.tintDarknessFactor).toBeUndefined();
  });
});
