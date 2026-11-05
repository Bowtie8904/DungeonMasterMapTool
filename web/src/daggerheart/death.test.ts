import { describe, expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import { canMakeDeathMove, syncDeathState } from './death';
import { actionBlocker, applyRest, runActionDetailed, syncDerived } from './engine';
import { availableMitigations } from './damage';
import { applyFeatureState } from './persist';
import { resolveRest } from './rest';

function unconscious() {
  const c = emptyCharacter('Aria');
  c.hp.slots = c.hp.on = 6;
  c.effects.push({ id: 'unconscious', name: 'Unconscious', kind: 'debuff', until: 'longRest', blocksMovement: true, unconsciousHp: 6 });
  c.scars = 1;
  syncDeathState(c);
  return c;
}

describe('death moves and scars', () => {
  it('disables death moves only when every HP is permanently marked, not ordinary table-controlled death', () => {
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    c.permanentHp = 5;
    expect(canMakeDeathMove(c)).toBe(true);
    c.permanentHp = 6;
    expect(canMakeDeathMove(c)).toBe(false);
    expect(actionBlocker(c, 'feat', {}, { id: 'risk', label: 'Risk', deathMove: 'risk' }))
      .toBe('All Hit Points are permanently marked; this character has crossed the veil of death.');
    c.permanentHp = 0;
    expect(canMakeDeathMove(c)).toBe(true);
  });
  it('preserves permanent HP through manual clipping, action healing and repeated long rests', () => {
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6; c.permanentHp = 2;
    const action = { id: 'heal', label: 'Heal', steps: [{ op: 'clear' as const, resource: 'hp', amount: 'all' }] };
    expect(runActionDetailed(c, 'heal', {}, action, 'Heal')).toEqual(['cleared 4 HP']);
    expect(c.hp.on).toBe(2);
    const reloaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    expect(reloaded.permanentHp).toBe(2);
    expect(reloaded.hp.on).toBe(2);
    c.hp.on = 0;
    syncDeathState(c);
    expect(c.hp.on).toBe(2);
    for (let i = 1; i <= 2; i++) {
      c.hp.on = 6;
      const result = resolveRest(c, 'long', [{ move: 'tend-all' }]);
      expect(result.lines.join()).toContain('clears 4 HP');
      expect(c.hp.on).toBe(2);
      expect(c.longRestRevision).toBe(i);
    }
    c.hp.on = 6;
    const result = resolveRest(c, 'short', [{ move: 'tend' }], {}, () => 12);
    expect(result.dice![0]).toMatchObject({ resource: 'hp', recovery: 2, cleared: 4 });
    expect(c.hp.on).toBe(2);
  });

  it('gates Spectral resistance and ends the form on further recovery or full HP', () => {
    const c = emptyCharacter('Aria');
    c.hp.on = 5; c.hp.slots = 6;
    c.features.push({ name: 'Spectral', source: 'test', text: '', automation: { mitigation: [{ id: 'spectral', label: 'Spectral', requiresEffect: 'Spectral Form', halve: true }] } });
    const effect = { id: 'spectral', name: 'Spectral Form', kind: 'buff' as const, spectralHp: 5, phaseMovement: true };
    expect(availableMitigations(c)).toHaveLength(0);
    c.effects.push(effect);
    expect(availableMitigations(c)).toHaveLength(1);
    c.hp.on = 4;
    syncDeathState(c);
    expect(c.effects).toHaveLength(0);
    c.hp.on = c.hp.slots = 6;
    c.effects.push(effect);
    syncDeathState(c);
    expect(c.effects).toHaveLength(0);
    expect(c.spectralBlocked).toBe(true);
    c.hp.on = 5;
    syncDeathState(c);
    expect(c.spectralBlocked).toBe(false);
  });

  it('blocks another death move while recovery is pending and clears pending state from host', () => {
    const c = emptyCharacter('Aria');
    c.hp.on = 6;
    c.pendingDeathRoll = { id: 1, ts: 2 };
    expect(canMakeDeathMove(c)).toBe(false);
    applyFeatureState(c, { actorId: 'a', featureRevision: 1, permanentHp: 1, spectralBlocked: true, deathNarrative: 'Egg form', pendingDeathRoll: null, inventoryRevision: 1, inventory: [], compendiumRevision: 1, loadout: [], vault: [] });
    expect(c.pendingDeathRoll).toBeUndefined();
    expect(c.permanentHp).toBe(1);
    expect(c.deathNarrative).toBe('Egg form');
    expect(c.inventoryRevision).toBe(1);
    expect(c.compendiumRevision).toBe(1);
  });

  it('keeps manual Life Wards across target rests', () => {
    const c = emptyCharacter('Aria');
    c.effects.push({ id: 'ward', name: 'Life Ward', kind: 'buff', until: 'manual', lifeWard: true, sourceActorId: 'other' });
    resolveRest(c, 'long', []);
    expect(c.effects[0].lifeWard).toBe(true);
  });
  it('only offers a death move at fully marked nonzero HP while conscious', () => {
    const c = emptyCharacter('Aria');
    expect(canMakeDeathMove(c)).toBe(false);
    c.hp.on = c.hp.slots = 6;
    expect(canMakeDeathMove(c)).toBe(true);
    c.hp.on = 5;
    expect(canMakeDeathMove(c)).toBe(false);
    expect(canMakeDeathMove(unconscious())).toBe(false);
    c.hp.on = c.hp.slots = 0;
    expect(canMakeDeathMove(c)).toBe(false);
  });

  it('retains scars across reload, derived updates and both rests, including Prepare', () => {
    const c = unconscious();
    c.hope.on = 6;
    syncDerived(c);
    expect(c.hope).toEqual({ total: 6, slots: 5, on: 5 });
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    for (const kind of ['short', 'long'] as const) {
      resolveRest(loaded, kind, [{ move: 'prepare-group' }]);
      expect(loaded.scars).toBe(1);
      expect(loaded.hope.slots).toBe(5);
      expect(loaded.hope.on).toBe(5);
    }
    loaded.level++;
    syncDerived(loaded);
    expect(loaded.hope.slots).toBe(5);
  });

  it('keeps unconscious after a short rest without healing and ends it on a long rest', () => {
    const c = unconscious();
    applyRest(c, 'short');
    expect(c.effects).toHaveLength(1);
    applyRest(c, 'long');
    expect(c.effects).toHaveLength(0);
    expect(c.scars).toBe(1);
  });

  it('ends unconscious after manual or downtime HP recovery, not Stress recovery', () => {
    const c = unconscious();
    c.stress.on--;
    syncDerived(c);
    expect(c.effects).toHaveLength(1);
    c.hp.on--;
    syncDerived(c);
    expect(c.effects).toHaveLength(0);
    const rested = unconscious();
    resolveRest(rested, 'short', [{ move: 'tend' }], {}, () => 1);
    syncDerived(rested);
    expect(rested.effects).toHaveLength(0);
  });

  it('synchronizes scars and unconsciousness from host updates in open sheets', () => {
    const c = emptyCharacter('Aria');
    const host = unconscious();
    expect(applyFeatureState(c, { actorId: 'a', featureRevision: 1, scars: host.scars, hope: host.hope, hp: host.hp, effects: host.effects })).toBe(true);
    syncDerived(c);
    expect(c.scars).toBe(1);
    expect(c.effects[0].blocksMovement).toBe(true);
    expect(c.hope.slots).toBe(5);
    c.scars = 0;
    syncDerived(c);
    expect(c.hope.slots).toBe(6);
    expect(c.hope.on).toBe(2);
  });
});
