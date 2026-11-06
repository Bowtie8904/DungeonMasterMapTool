import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import type { CompendiumFile } from './compendium';
import {
  applyRest, availableRollOptions, availableRollTriggers, poolDice, rollDicePool,
  rollLocal, rollOptionDice, runActionDetailed, syncDerived, usesLeft, usesMax,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function seraph(subclass = 'divine-wielder') {
  const c = emptyCharacter('Seraph');
  c.className = 'Seraph';
  c.subclass = subclass === 'divine-wielder' ? 'Divine Wielder' : 'Winged Sentinel';
  c.features = catalog.entries.filter(e => e.id === 'seraph' || e.id.startsWith(`seraph-${subclass}-`))
    .flatMap(e => (e.features ?? []).map(f => ({
      ...structuredClone(f), source: e.kind === 'class' ? 'Class feature - Seraph' : `Subclass ${e.stage} - ${c.subclass}`,
    })));
  c.traits.find(t => t.name === 'Strength')!.mod = 3;
  c.traits.find(t => t.name === 'Strength')!.spellcast = true;
  c.activeWeapons = [{ name: 'Blade', slot: 'Primary', range: 'Melee', damage: 'd8+2', damageType: 'phys', trait: 'Strength', hands: 1, tier: 1 }];
  syncDerived(c);
  return c;
}

afterEach(() => vi.restoreAllMocks());

describe('Seraph catalog automation', () => {
  it('counts one Prayer Dice action per session, survives rests and reloads, and never confirms', () => {
    const c = seraph();
    const auto = c.features.find(f => f.name === 'Prayer Dice')!.automation!;
    const action = auto.actions![0];
    expect(action.confirm).toBeUndefined();
    expect(usesLeft(c, 'feat:Prayer Dice', auto)).toBe(1);
    expect(runActionDetailed(c, 'feat:Prayer Dice', auto, action, 'Prayer Dice', () => 3)).not.toBeNull();
    expect(c.usage['feat:Prayer Dice']).toEqual({ used: 1, per: 'session' });
    expect(runActionDetailed(c, 'feat:Prayer Dice', auto, action, 'Prayer Dice', () => { throw new Error('Second roll'); })).toBeNull();
    applyRest(c, 'short');
    applyRest(c, 'long');
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    expect(usesLeft(loaded, 'feat:Prayer Dice', auto)).toBe(0);
    expect(poolDice(loaded, auto.dicePool!)).toHaveLength(3);
  });

  it('takes an independent uniform sample per die and retains legitimate repeated faces', () => {
    const random = vi.spyOn(Math, 'random');
    for (const sample of [0, 0.24999, 0.25, 0.49999, 0.5, 0.74999, 0.75, 0.99999]) random.mockReturnValueOnce(sample);
    expect(Array.from({ length: 8 }, () => rollLocal('1d4'))).toEqual([1, 1, 2, 2, 3, 3, 4, 4]);
    expect(random).toHaveBeenCalledTimes(8);
    const c = seraph();
    c.features = c.features.filter(f => f.name !== 'Devout');
    random.mockClear();
    random.mockReturnValueOnce(0).mockReturnValueOnce(0).mockReturnValueOnce(0.99999);
    const pool = c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool!;
    rollDicePool(c, pool);
    expect(random).toHaveBeenCalledTimes(3);
    expect(poolDice(c, pool).map(e => e.counter!.value)).toEqual([4, 1, 1]);
  });

  it('migrates existing dice labels and session use without altering values or identities', () => {
    const c = seraph();
    const pool = c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool!;
    rollDicePool(c, pool, () => 2);
    const ids = c.effects.map(e => e.id);
    c.effects.forEach((e, i) => { e.rollTriggers![0].label = `Prayer Dice: +2 (die ${i + 1})`; });
    updateCharacterAutomation(c, [catalog]);
    expect(c.effects.map(e => e.id)).toEqual(ids);
    expect(c.effects.map(e => e.counter!.value)).toEqual([2, 2, 2]);
    expect(c.effects.every(e => e.rollTriggers![0].label === 'Prayer die: +2')).toBe(true);
    expect(c.usage['feat:Prayer Dice']).toEqual({ used: 1, per: 'session' });
  });
  it('rolls distinct Prayer Dice with Devout dropping exactly one lowest die and persists them across rests', () => {
    const c = seraph();
    const pool = c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool!;
    const faces = [2, 4, 1, 2];
    const lines = rollDicePool(c, pool, () => faces.shift()!);
    expect(lines[0]).toContain('4d4, keep highest 3');
    expect(poolDice(c, pool).map(e => e.counter!.value)).toEqual([4, 2, 2]);
    applyRest(c, 'long');
    expect(poolDice(c, pool)).toHaveLength(3);
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    expect(poolDice(loaded, pool).map(e => e.counter!.value)).toEqual([4, 2, 2]);
    loaded.effects.splice(loaded.effects.indexOf(poolDice(loaded, pool)[1]), 1);
    expect(poolDice(loaded, pool).map(e => e.counter!.value)).toEqual([4, 2]);
    rollDicePool(c, pool, () => 3);
    expect(poolDice(c, pool).map(e => e.counter!.value)).toEqual([3, 3, 3]);
  });

  it('rolls only Spellcast dice without Devout and never rolls an extra die when the trait is nonpositive', () => {
    const c = seraph();
    const pool = c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool!;
    c.features = c.features.filter(f => f.name !== 'Devout');
    let count = 0;
    rollDicePool(c, pool, () => { count++; return 2; });
    expect(count).toBe(3);
    c.traits.find(t => t.spellcast)!.mod = -1;
    rollDicePool(c, pool, () => { throw new Error('Must not roll'); });
    expect(poolDice(c, pool)).toHaveLength(0);
  });

  it('offers one option per die for any roll and mitigation, consuming only selected dice', () => {
    const c = seraph();
    const pool = c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool!;
    rollDicePool(c, pool, () => 2);
    for (const context of [
      { source: 'action,trait:Presence', hope: 8, fear: 2, total: 10 },
      { source: 'damage', hope: 0, fear: 0, total: 10, kind: 'damage' as const },
      { source: '', hope: 0, fear: 0, total: 10, kind: 'damage' as const },
    ]) {
      const options = availableRollTriggers(c, context).filter(t => t.source.startsWith('Prayer Dice'));
      expect(options).toHaveLength(3);
      expect(options.every(t => t.trigger.addDice === '2' && t.trigger.audience === 'any')).toBe(true);
    }
    const mitigation = availableMitigations(c).filter(m => m.source.startsWith('Prayer Dice'));
    expect(mitigation).toHaveLength(3);
    const result = computeDamage(c, mitigation, { damage: 10, type: 'physical', armor: false, selected: new Set(mitigation.slice(0, 2).map(m => m.id)) });
    expect(result.damage).toBe(6);
    expect(applyDamage(c, result, 'physical')).not.toBeNull();
    expect(poolDice(c, pool)).toHaveLength(1);
  });

  it('shares the Devout-adjusted Sparing Touch limit across both healing choices', () => {
    const c = seraph();
    const auto = c.features.find(f => f.name === 'Sparing Touch')!.automation!;
    expect(usesMax(c, 'feat:Sparing Touch', auto)).toBe(2);
    runActionDetailed(c, 'feat:Sparing Touch', auto, auto.actions![0], 'Sparing Touch');
    runActionDetailed(c, 'feat:Sparing Touch', auto, auto.actions![1], 'Sparing Touch');
    expect(usesLeft(c, 'feat:Sparing Touch', auto)).toBe(0);
    applyRest(c, 'long');
    expect(usesLeft(c, 'feat:Sparing Touch', auto)).toBe(2);
    c.features = c.features.filter(f => f.name !== 'Devout');
    expect(usesMax(c, 'feat:Sparing Touch', auto)).toBe(1);
  });

  it('offers Spirit Weapon only on matching weapon attacks and Sacred Resonance on matching weapon damage', () => {
    const c = seraph();
    for (const range of ['Melee', 'Very Close', 'Close', 'Far']) {
      c.activeWeapons[0].range = range;
      const triggers = availableRollTriggers(c, { source: 'attack,action', weapon: 'Blade', hope: 8, fear: 2, total: 10 });
      expect(triggers.some(t => t.source === 'Spirit Weapon')).toBe(range !== 'Far');
      const options = availableRollOptions(c, ['damage'], c.activeWeapons[0]);
      expect(options.some(o => o.option.matchingDice && o.option.defaultOn)).toBe(range !== 'Far');
    }
    expect(availableRollTriggers(c, { source: 'spellcast,action', hope: 8, fear: 2, total: 10 }).some(t => t.source === 'Spirit Weapon')).toBe(false);
    c.activeWeapons[0].range = 'Close';
    expect(availableRollOptions(c, ['damage', 'spelldamage'], c.activeWeapons[0]).some(o => o.option.matchingDice)).toBe(false);
  });

  it('upgrades Wings of Light from d8 to d12 and refreshes saved Seraph automation', () => {
    const c = seraph('winged-sentinel');
    const wings = availableRollOptions(c, ['damage']).find(o => o.source === 'Wings of Light')!;
    expect(rollOptionDice(c, wings.option)).toBe('1d12');
    c.features = c.features.filter(f => f.name !== 'Power of the Gods');
    expect(rollOptionDice(c, wings.option)).toBe('1d8');
    c.features.forEach(f => { delete f.automation; });
    updateCharacterAutomation(c, [catalog]);
    expect(c.features.find(f => f.name === 'Prayer Dice')!.automation!.dicePool).toBeDefined();
    expect(c.features.find(f => f.name === 'Ethereal Visage')!.automation!.hopeReward).toMatchObject({ removeFear: 1, roll: 'trait:Presence' });
  });
});
