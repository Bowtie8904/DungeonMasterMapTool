import { describe, expect, it } from 'vitest';
import { emptyCharacter } from './data';
import { addEffect, applyRest, availableRollOptions, damageExpression, rollBonus, runActionDetailed, selectedRollBonus, syncDerived } from './engine';
import { availableMitigations, computeDamage } from './damage';

describe('DM-managed effect protection', () => {
  it('does not replace a DM effect with another effect of the same name or group', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Ward', kind: 'buff', group: 'wards', dmManaged: true });

    addEffect(character, { name: 'Ward', kind: 'buff', group: 'wards' }, 'Spell');
    expect(character.effects.map(effect => effect.id)).toContain('dm-1');
    expect(character.effects).toHaveLength(2);
  });

  it('still expires a managed effect at the applicable rest', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Ward', kind: 'buff', until: 'longRest', dmManaged: true });
    applyRest(character, 'short');
    expect(character.effects.some(effect => effect.id === 'dm-1')).toBe(true);
    applyRest(character, 'long');
    expect(character.effects.some(effect => effect.id === 'dm-1')).toBe(false);
  });

  it('does not end a DM effect when a same-named player effect is ended', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Ward', kind: 'buff', dmManaged: true });
    addEffect(character, { name: 'Ward', kind: 'buff' }, 'Spell');
    runActionDetailed(character, 'feat:test', {}, { id: 'end', label: 'End', endEffect: 'Ward' }, 'Test');
    expect(character.effects.map(effect => effect.id)).toEqual(['dm-1']);
  });

  it('applies passive managed resistance only to the matching damage type', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Ward', kind: 'buff', dmManaged: true,
      mitigation: [{ id: 'part-0', label: 'Ward', passive: true, halve: true, when: { type: 'magic' } }] });
    const options = availableMitigations(character);
    const input = { damage: 11, armor: false, selected: new Set<string>() };
    expect(computeDamage(character, options, { ...input, type: 'magic' }).damage).toBe(6);
    expect(computeDamage(character, options, { ...input, type: 'physical' }).damage).toBe(11);
  });

  it('applies HP and Stress capacity effects and restores the base on expiry', () => {
    const character = emptyCharacter('Hero');
    character.hp = { total: 12, slots: 6, on: 6 };
    character.stress = { total: 12, slots: 6, on: 2 };
    character.effects.push({ id: 'dm-1', name: 'Vigor', kind: 'buff', until: 'rest', dmManaged: true,
      modifiers: [{ stat: 'hpSlots', value: 2 }, { stat: 'stressSlots', value: -3 }] });
    syncDerived(character);
    syncDerived(character);
    expect([character.hp.slots, character.stress.slots, character.stress.on]).toEqual([8, 3, 2]);
    applyRest(character, 'short');
    expect([character.hp.slots, character.hp.on, character.stress.slots, character.capacityApplied]).toEqual([6, 6, 6, undefined]);
  });

  it('keeps penalty dice negative in the roll and contribution', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Hex', kind: 'debuff', dmManaged: true,
      rollOptions: [{ id: 'part-0', label: 'Hex', roll: 'damage', dice: '-1d6', hostOwned: true }] });
    const selected = availableRollOptions(character, ['damage']);
    const bonus = selectedRollBonus(character, rollBonus(character, ['damage']), selected);
    expect(bonus.contributions[0].expression).toBe('0-1d6');
    expect(damageExpression('d8', 1, bonus)).toBe('1d8-1d6');
  });

  it('applies passive penalty dice without a player selecting an option', () => {
    const character = emptyCharacter('Hero');
    character.effects.push({ id: 'dm-1', name: 'Hex', kind: 'debuff', dmManaged: true,
      modifiers: [{ roll: 'damage', value: -1, dice: '-d6' }] });
    const bonus = rollBonus(character, ['damage']);
    expect(bonus.contributions[0].expression).toBe('-1-1d6');
    expect(damageExpression('d8', 1, bonus)).toBe('1d8-1-1d6');
  });

});
