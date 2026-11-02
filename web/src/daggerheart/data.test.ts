import { describe, expect, it } from 'vitest';
import { dualityExpression, dualityResult, dualitySucceeds, handsUsed, mockCharacter, tierOf } from './data';
import {
  Automation, addEffect, applyRest, consumeBonus, damageContributions, damageExpression, derivedStats, rollBonus, runAction, syncDerived, traitContributions, traitTotal,
} from './engine';
import { addToSheet } from './compendium';

describe('daggerheart data', () => {
  it('maps level to tier', () => {
    expect([1, 2, 4, 5, 7, 8, 10].map(tierOf)).toEqual([1, 2, 2, 3, 3, 4, 4]);
  });

  it('derives stats from armor and level', () => {
    const c = mockCharacter();
    expect(derivedStats(c)).toMatchObject({ major: 8, severe: 14, evasion: 13, armorSlots: 3 });
    c.activeArmor = undefined;
    expect(derivedStats(c)).toMatchObject({ major: 3, severe: 6, evasion: 12 });
  });

  it('applies armor penalties and syncs armor slots', () => {
    const c = mockCharacter();
    c.activeArmor = {
      name: 'Full Plate', score: 4, major: 11, severe: 25, tier: 3,
      automation: { modifiers: [{ stat: 'evasion', value: -2 }, { stat: 'trait.Agility', value: -1 }] },
    };
    const base = traitTotal({ ...c, activeArmor: undefined }, 'Agility');
    expect(derivedStats(c).evasion).toBe(10);
    expect(traitTotal(c, 'Agility')).toBe(base - 1);
    syncDerived(c);
    expect(c.armorTrack.slots).toBe(4);
  });

  it('control potion buffs the next finesse roll once', () => {
    const c = mockCharacter();
    const potion: Automation = {
      actions: [{
        id: 'drink', label: 'Drink',
        steps: [{ op: 'effect', effect: { name: 'Control Potion', kind: 'buff', until: 'rest', modifiers: [{ roll: 'trait:Finesse', value: 1, consume: true }] } }],
      }],
    };
    expect(runAction(c, 'item:cp', potion, potion.actions![0], 'Control Potion')).not.toBeNull();
    expect(c.effects).toHaveLength(1);
    expect(rollBonus(c, ['trait:Agility']).value).toBe(0);
    const bonus = rollBonus(c, ['attack', 'trait:Finesse']);
    expect(bonus.value).toBe(1);
    consumeBonus(c, bonus);
    expect(c.effects).toHaveLength(0);
  });

  it('health potion clears hit points and rests expire effects and uses', () => {
    const c = mockCharacter();
    c.hp.on = 4;
    const heal: Automation = { actions: [{ id: 'd', label: 'Drink', steps: [{ op: 'clear', resource: 'hp', amount: 'all' }] }] };
    runAction(c, 'item:hp', heal, heal.actions![0], 'Potion');
    expect(c.hp.on).toBe(0);
    addEffect(c, { name: 'Rested', kind: 'buff', until: 'rest' });
    applyRest(c, 'short');
    expect(c.effects).toHaveLength(0);
  });

  it('names trait penalties, weapon features and every roll modifier separately', () => {
    const c = mockCharacter();
    c.activeArmor!.automation = { modifiers: [{ stat: 'trait.Agility', value: -1 }] };
    c.activeWeapons[0].feature = { name: 'Accurate', text: '' };
    c.activeWeapons[0].automation = { modifiers: [{ roll: 'attack', value: 1 }] };
    addEffect(c, { name: 'Blessing', kind: 'buff', modifiers: [{ roll: 'attack', value: 2, dice: 'd4' }] });
    const bonus = rollBonus(c, ['attack', 'trait:Agility']);
    expect(bonus.contributions).toEqual([
      { label: 'Blessing', expression: '2+1d4' },
      { label: 'Shortbow - Accurate', expression: '1' },
    ]);
    const trait = traitContributions(c, 'Agility');
    expect(trait.map(s => Number(s.expression))).toEqual([2, -1]);
    expect(trait.reduce((n, s) => n + Number(s.expression), 0)).toBe(traitTotal(c, 'Agility'));
    const damage = damageContributions(c, c.activeWeapons[0], rollBonus(c, ['damage']));
    expect(damage[0]).toEqual({ label: 'Shortbow damage (Proficiency 2)', expression: '2d6', notes: [{ label: 'Base Proficiency', impact: '2 dice' }] });
    expect(damage[1]).toEqual({ label: 'Shortbow damage bonus', expression: '+3' });
    addEffect(c, { name: 'Proficiency Potion', kind: 'buff', modifiers: [{ stat: 'proficiency', value: 1 }] });
    const boosted = damageContributions(c, c.activeWeapons[0], rollBonus(c, ['damage']));
    expect(boosted[0].expression).toBe('3d6');
    expect(boosted[0].notes).toContainEqual({ label: 'Proficiency Potion', impact: '+1 die' });
  });

  it('adds compendium entries to the sheet', () => {
    const c = mockCharacter();
    const before = c.carriedArmor.length;
    expect(addToSheet(c, 'armor', { name: 'Test Mail', tier: 1, armor: { score: 3, major: 6, severe: 12 } } as never)).not.toBeNull();
    expect(c.carriedArmor).toHaveLength(before + 1);
    expect(damageExpression('d8', 2, { value: 1, dice: [], applied: [], names: [] } as never)).toContain('2d8');
  });

  it('counts hands of active weapons', () => {
    expect(handsUsed(mockCharacter())).toBe(2);
  });

  it('builds duality expressions', () => {
    expect(dualityExpression(0, 0)).toBe('2d12');
    expect(dualityExpression(3, 1)).toBe('2d12+1d6+3');
    expect(dualityExpression(-1, -1)).toBe('2d12-1d6-1');
  });

  it('interprets duality rolls', () => {
    const r = dualityResult([9, 4, -3], 12 + 2);
    expect(r).toMatchObject({ outcome: 'hope', advantage: -3, mod: 4, total: 14 });
    expect(dualitySucceeds(r, 15)).toBe(false);
    const crit = dualityResult([5, 5], 10);
    expect(crit.outcome).toBe('crit');
    expect(dualitySucceeds(crit, 99)).toBe(true);
  });
});
