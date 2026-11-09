import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, type CharacterSheet, type DomainCard } from './data';
import type { CompendiumFile } from './compendium';
import { addEffect, availableRollOptions, availableRollTriggers, commitRollOptions, deathrunProficiency, derivedStats, rollBonus, selectedRollBonus, statBreakdown } from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { availableMoves, resolveRest } from './rest';
import { rollContext } from './rollbuttons';
import type { RollMessage } from '../protocol';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
function load(c: CharacterSheet, ...ids: string[]) {
  for (const id of ids) {
    const entry = catalog.entries.find(card => card.id === id)!;
    c.loadout.push({ ...entry, type: entry.cardType, text: entry.text ?? '' } as DomainCard);
  }
}
const weapon = { name: 'Sword', slot: 'Primary' as const, trait: 'Strength' as const, range: 'Melee', damage: 'd8', damageType: 'phys' as const, hands: 1 as const, tier: 1 };
const context = { source: 'action,attack,trait:Instinct', hope: 8, fear: 3, total: 11, kind: 'duality' as const, weapon: 'Sword' };

describe('Bone/Valor feedback automation', () => {
  it.each([[3, 2], [-1, 0], [0, 0], [4, 2], [-3, -1]])('rounds half current Agility (%i) up to %i Evasion', (agility, bonus) => {
    const c = emptyCharacter('Hero');
    c.traits.find(t => t.name === 'Agility')!.mod = agility - 1;
    c.effects.push({ id: 'agility', name: 'Current Agility', kind: 'buff', modifiers: [{ stat: 'trait.Agility', value: 1 }] });
    load(c, 'untouchable');
    expect(derivedStats(c).evasion - c.evasion).toBe(bonus);
    expect(statBreakdown(c).evasion.filter(s => s.label === 'Untouchable').reduce((sum, s) => sum + s.value, 0)).toBe(bonus);
  });

  it('offers Forceful Push only from Hope or critical damage context', () => {
    const c = emptyCharacter('Hero'); load(c, 'forceful-push');
    expect(availableRollOptions(c, ['damage'], weapon)).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'], weapon, 'fear')).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'], weapon, 'hope')).toHaveLength(1);
    expect(availableRollOptions(c, ['damage'], weapon, 'critical')).toHaveLength(1);
  });

  it('routes printed card costs to their actual result actions', () => {
    const c = emptyCharacter('Hero'); c.activeWeapons = [weapon];
    load(c, 'know-thy-enemy', 'rousing-strike', 'inevitable', 'lead-by-example', 'breaking-blow');
    const triggers = availableRollTriggers(c, context);
    expect(triggers.filter(t => t.source === 'Know Thy Enemy').map(t => t.trigger.id)).toEqual(['information', 'use']);
    expect(triggers.some(t => t.source === 'Inevitable')).toBe(true);
    expect(triggers.some(t => t.source === 'Rousing Strike')).toBe(false);
    expect(availableRollTriggers(c, { ...context, hope: 8, fear: 8 }).some(t => t.source === 'Rousing Strike')).toBe(true);
    const damage = availableRollTriggers(c, { ...context, source: 'damage', kind: 'damage' });
    expect(damage.filter(t => ['Lead by Example', 'Breaking Blow'].includes(t.source))).toHaveLength(2);
    expect(availableRollTriggers(c, { ...context, source: 'damage,spelldamage', kind: 'damage' }).some(t => t.source === 'Breaking Blow')).toBe(false);
  });

  it('rolls Redirect before payment and only offers payment if its actual roll contains a 6', () => {
    const c = emptyCharacter('Hero'); load(c, 'redirect');
    const action = c.loadout[0].automation!.actions![0];
    expect(action.cost).toBeUndefined();
    expect(action.sendRoll?.dice).toBe('@total_proficiencyd6');
    const roll = { id: 1, ts: 1, kind: 'dice', source: 'featureAction:card:Redirect:use', rolls: [2, 6], total: 8 } as RollMessage;
    expect(availableRollTriggers(c, rollContext(roll)!)).toHaveLength(1);
    expect(availableRollTriggers(c, rollContext({ ...roll, rolls: [2, 5] })!)).toHaveLength(0);
  });

  it('offers Support Tank as two paid ally rerolls and not a card action', () => {
    const c = emptyCharacter('Hero'); load(c, 'support-tank');
    expect(c.loadout[0].automation!.actions).toBeUndefined();
    expect(availableRollTriggers(c, context).map(t => [t.trigger.audience, t.trigger.rerollDualityDie, t.trigger.cost?.hope]))
      .toEqual([['ally', 'hope', 2], ['ally', 'fear', 2]]);
  });

  it('has one default weapon-only Cruel Precision dropdown using current trait bonuses', () => {
    const c = emptyCharacter('Hero'); load(c, 'cruel-precision');
    c.traits.find(t => t.name === 'Agility')!.mod = 2;
    addEffect(c, { name: 'Agility', kind: 'buff', modifiers: [{ stat: 'trait.Agility', value: 1 }] });
    const [selection] = availableRollOptions(c, ['damage'], weapon);
    expect(selection.option).toMatchObject({ defaultOn: true, traitChoices: ['Finesse', 'Agility'] });
    selection.option = { ...selection.option, traitValue: 'Agility' };
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [selection]).value).toBe(3);
    expect(availableRollOptions(c, ['damage', 'spelldamage'], weapon)).toHaveLength(0);
  });

  it('keeps Breaking Blow unconsumed by browser commits and cancellation; the host commits selected damage', () => {
    const c = emptyCharacter('Hero'); load(c, 'breaking-blow');
    addEffect(c, c.loadout[0].automation!.rollTriggers![0].steps![0].effect!);
    const options = availableRollOptions(c, ['damage'], weapon);
    expect(options).toHaveLength(1);
    expect(options[0].option).toMatchObject({ defaultOn: true, dice: '2d12', hostOwned: true });
    commitRollOptions(c, []);
    commitRollOptions(c, options);
    expect(c.effects.some(e => e.name === 'Breaking Blow')).toBe(true);
  });

  it('exposes Ground Pound damage, deferred Wrangle payment, and a persistent stance', () => {
    const c = emptyCharacter('Hero'); load(c, 'ground-pound', 'wrangle', 'hold-the-line', 'i-see-it-coming');
    expect(c.loadout[0].automation!.rollTriggers![0].damage).toMatchObject({ dice: '4d10+8', fixed: true });
    expect(c.loadout[1].automation!.actions![0].cost).toBeUndefined();
    expect(c.loadout[1].automation!.rollTriggers![0].cost).toEqual({ hope: 1 });
    expect(c.loadout[2].automation!.actions![0].steps![0].effect).toMatchObject({ name: 'Hold the Line', until: 'manual' });
    expect(c.loadout[3].automation!.actions![0]).toMatchObject({ hostOwned: true, sendRoll: { dice: '1d4' }, cost: { stress: 1 } });
  });

  it('clears Rise Up Stress only on committed HP damage and keeps Unyielding dice hidden metadata', () => {
    const c = emptyCharacter('Hero'); c.stress = { on: 3, slots: 6, total: 6 }; c.hp.slots = 6;
    load(c, 'rise-up', 'unyielding-armor', 'octarine-brace');
    const mitigations = availableMitigations(c);
    expect(mitigations.find(m => m.source === 'Unyielding Armor')!.option.hiddenRoll).toBe(true);
    expect(mitigations.find(m => m.source === 'Octarine Brace')!.option.giveFear).toBe(1);
    const result = computeDamage(c, mitigations, { damage: 100, type: 'physical', armor: false, selected: new Set() });
    expect(c.stress.on).toBe(3);
    applyDamage(c, result, 'physical');
    expect(c.stress.on).toBe(2);
  });

  it('Recovery allows one long-rest move and consumes its temporary grant on the rest', () => {
    const c = emptyCharacter('Hero'); c.hp.on = 3;
    addEffect(c, { name: 'Recovery', kind: 'buff', until: 'rest', consumeLongRestMove: true, rest: { longMovesInShort: true } });
    expect(availableMoves(c, 'short').some(m => m.id === 'tend-all')).toBe(true);
    expect(() => resolveRest(c, 'short', [{ move: 'tend-all' }, { move: 'stress-all' }])).toThrow('only one');
    resolveRest(c, 'short', [{ move: 'tend-all' }]);
    expect(c.hp.on).toBe(0);
    expect(availableMoves(c, 'short').some(m => m.id === 'tend-all')).toBe(false);
  });

  it('does not apply Rise Up when mitigation replaces all marked HP', () => {
    const c = emptyCharacter('Hero'); load(c, 'rise-up');
    c.stress = { on: 3, slots: 6, total: 6 }; c.hp.slots = 6;
    c.effects.push({ id: 'replace', name: 'Replacement', kind: 'buff',
      mitigation: [{ id: 'replace', label: 'Replace HP', passive: true, replaceHp: true }] });
    const r = computeDamage(c, availableMitigations(c), { damage: 100, type: 'physical', armor: false, selected: new Set() });
    expect(r.hp).toBe(0);
    expect(r.applied.some(m => m.option.clearStress)).toBe(false);
    applyDamage(c, r, 'physical');
    expect(c.stress.on).toBe(3);
  });

  it('leaves cross-character Recovery payment and grants to the host transaction', () => {
    const c = emptyCharacter('Hero'); load(c, 'recovery');
    c.hope = { on: 3, slots: 6, total: 6 };
    resolveRest(c, 'short', [], { passives: { 'card:Recovery|ally': true }, allies: { 'card:Recovery|ally': 'ally' } });
    expect(c.hope.on).toBe(3);
    expect(c.effects.some(e => e.consumeLongRestMove)).toBe(false);
  });

  it('Deathrun never hides exhaustion behind normal minimum Proficiency and preserves result provenance', () => {
    const c = emptyCharacter('Hero'); load(c, 'deathrun');
    expect(availableRollOptions(c, ['action', 'attack'], weapon)[0].option.cost).toEqual({ hope: 3 });
    addEffect(c, c.loadout[0].automation!.actions![0].steps![0].effect!);
    c.proficiency.on = 2;
    expect(deathrunProficiency(c, 0)).toBe(3);
    expect(deathrunProficiency(c, 2)).toBe(1);
    expect(deathrunProficiency(c, 3)).toBe(0);
    c.effects[0].deathrunRoll = { expression: '3d8', total: 12, rolls: [3, 4, 5] };
    c.effects[0].deathrunRemaining = [0];
    c.proficiency.on = 6;
    expect(deathrunProficiency(c, 1)).toBe(0);
    const ctx = { ...context, source: 'damage', kind: 'damage' as const, deathrun: { effectId: 'old', count: 2 } };
    c.effects = [];
    expect(availableRollTriggers(c, ctx).some(t => t.trigger.deathrunResult)).toBe(true);
  });

  it('Strategic Approach retains automatic rest tokens without manual rest/clear buttons', () => {
    const c = emptyCharacter('Hero'); load(c, 'strategic-approach', 'armorer');
    expect(c.loadout[0].automation!.actions!.map(a => a.id)).toEqual(['spend']);
    expect(c.loadout[1].automation!.rest!.modifiers!.map(m => m.repairAllies)).toEqual([1, 1]);
  });
});
