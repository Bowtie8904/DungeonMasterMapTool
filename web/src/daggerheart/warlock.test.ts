import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import type { CompendiumFile } from './compendium';
import { actionBlocker, applyRest, availableRollTriggers, sheetActions, syncDerived, usesLeft } from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { patronSides } from './warlock';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function warlock(level = 3) {
  const c = emptyCharacter('Warlock');
  c.className = 'Warlock';
  c.subclass = 'Pact Of The Wrathful';
  c.level = level;
  c.hp.slots = 5;
  c.features = catalog.entries.filter(e => e.id === 'warlock' || e.id.startsWith('warlock-pact-of-the-wrathful-'))
    .flatMap(e => (e.features ?? []).map(f => ({
      ...structuredClone(f), source: e.kind === 'class' ? 'Class feature - Warlock' : `Subclass ${e.stage} - ${c.subclass}`,
    })));
  c.activeWeapons = [{ name: 'Blade', slot: 'Primary', range: 'Melee', damage: 'd8', damageType: 'phys', trait: 'Strength', hands: 1, tier: 1 }];
  syncDerived(c);
  return c;
}

describe('Warlock catalog automation', () => {
  it('keeps the Boon reroll, adds its Hope action, and only shows Favor in class resources', () => {
    const c = warlock();
    const boon = c.features.find(f => f.name === 'Patron’s Boon')!.automation!;
    expect(sheetActions(boon)).toEqual([{ id: 'use', label: 'Patron’s Boon', cost: { hope: 3 } }]);
    expect(boon.rollTriggers![0]).toMatchObject({ reroll: 'duality', advantage: true, cost: { hope: 3 } });
    const favor = c.features.find(f => f.name === 'Favor')!.automation!;
    expect(sheetActions(favor)).toEqual([]);
    expect(favor.classResourceOnly).toBe(true);
    expect(favor.hopeReward).toEqual({ favor: 1, roll: 'action' });
    expect(favor.actions).toHaveLength(1);
    expect(favor.actions![0].timing).toBe('downtime');
    expect(c.resources!.favor).toBe(3);
  });

  it('offers Diminish on Hope action rolls, not Fear, reaction, known failures or damage', () => {
    const c = warlock();
    const ctx = { source: 'action', hope: 10, fear: 3, total: 15 };
    const triggers = (overrides = {}) => availableRollTriggers(c, { ...ctx, ...overrides }).filter(t => t.source === 'Diminish My Foes');
    expect(triggers()).toHaveLength(1);
    expect(triggers({ source: 'attack,action', weapon: 'Blade' })).toHaveLength(1);
    expect(triggers({ hope: 2 })).toHaveLength(0);
    expect(triggers({ hope: 3 })).toHaveLength(0);
    expect(triggers({ source: 'reaction' })).toHaveLength(0);
    expect(triggers({ difficulty: 99 })).toHaveLength(0);
    expect(triggers({ source: 'damage', kind: 'damage' })).toHaveLength(0);
  });

  it('ends Menacing Reach only on equipped primary attacks while its buff is active', () => {
    const c = warlock();
    const ctx = { source: 'action,attack', hope: 10, fear: 3, total: 15, weapon: 'Blade' };
    const reach = () => availableRollTriggers(c, ctx).filter(t => t.source === 'Menacing Reach');
    expect(reach()).toHaveLength(0);
    c.effects.push({ id: 'reach', name: 'Menacing Reach', kind: 'buff', until: 'manual', text: 'Blade reach: Very Close' });
    expect(reach()).toHaveLength(1);
    expect(availableRollTriggers(c, { ...ctx, weapon: 'Secondary' }).some(t => t.source === 'Menacing Reach')).toBe(false);
    expect(availableRollTriggers(c, { ...ctx, source: 'damage', kind: 'damage' }).some(t => t.source === 'Menacing Reach')).toBe(false);
    const auto = c.features.find(f => f.name === 'Menacing Reach')!.automation!;
    expect(actionBlocker(c, 'feat:Menacing Reach', auto, auto.actions![0])).toContain('already active');
  });

  it.each([3, 5])('rolls Vengeance from final HP, spends one Favor and explains attacker HP at level %i', level => {
    const c = warlock(level);
    c.hp.on = 0;
    const options = availableMitigations(c);
    const vengeance = options.find(m => m.option.patronDice === 'vengeance')!;
    const r = computeDamage(c, options, { damage: 15, type: 'physical', armor: false, selected: new Set([vengeance.id]) });
    expect(r.hp).toBeGreaterThan(0);
    let rolls = 0;
    const text = applyDamage(c, r, 'physical', expression => {
      expect(expression).toBe(`1d${patronSides(c)}`);
      return ++rolls === 1 ? 4 : 3;
    });
    expect(rolls).toBe(r.hp);
    expect(c.resources!.favor).toBe(2);
    expect(text).toContain('the attacker marks 1 HP');
    expect(text).toContain(`${r.hp} HP marked`);
    const none = computeDamage(c, options, { damage: 0, type: 'physical', armor: false, selected: new Set([vengeance.id]) });
    expect(none.applied).not.toContain(vengeance);
    expect(none.costs.favor).toBeUndefined();
  });

  it('does not spend Vengeance when another option replaces all HP', () => {
    const c = warlock();
    c.hp.on = 0;
    c.features.push({ name: 'Prevent', source: 'Test', text: '', automation: { mitigation: [{ id: 'prevent', label: 'Prevent', replaceHp: true }] } });
    const options = availableMitigations(c);
    const r = computeDamage(c, options, { damage: 99, type: 'physical', armor: false, selected: new Set(options.map(o => o.id)) });
    expect(r.hp).toBe(0);
    expect(r.applied.some(m => m.option.patronDice)).toBe(false);
    expect(r.costs.favor).toBeUndefined();
  });

  it('declares repeatable damage rerolls, feature-only Ire and shared rest limits', () => {
    const c = warlock();
    const reroll = availableRollTriggers(c, { source: 'damage', kind: 'damage', hope: 0, fear: 0, total: 5 })
      .find(t => t.source === 'Fearsome Attack')!;
    expect(reroll.trigger).toMatchObject({ damageDice: 'reroll', repeatable: true, cost: { favor: 1 } });
    const ire = c.features.find(f => f.name === 'Otherworldly Ire')!.automation!;
    expect(ire.actions![0].patronDice).toBe('ire');
    expect(ire.mitigation).toBeUndefined();
    c.usage['feat:Otherworldly Ire'] = { used: 1, per: 'rest' };
    expect(usesLeft(c, 'feat:Otherworldly Ire', ire)).toBe(0);
    applyRest(c, 'short');
    expect(usesLeft(c, 'feat:Otherworldly Ire', ire)).toBe(1);
    c.resources!.favor = 0;
    expect(actionBlocker(c, 'feat:Otherworldly Ire', ire, ire.actions![0])).toBe('Not enough Favor');
  });

  it('refreshes old feature metadata without resetting Favor or uses', () => {
    const c = warlock();
    c.resources!.favor = 12;
    c.usage['feat:Otherworldly Ire'] = { used: 1, per: 'rest' };
    for (const f of c.features) f.automation = undefined;
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    updateCharacterAutomation(loaded, [catalog]);
    expect(loaded.resources!.favor).toBe(12);
    expect(loaded.usage['feat:Otherworldly Ire'].used).toBe(1);
    expect(loaded.features.find(f => f.name === 'Otherworldly Ire')!.automation!.actions![0].patronDice).toBe('ire');
  });
});
