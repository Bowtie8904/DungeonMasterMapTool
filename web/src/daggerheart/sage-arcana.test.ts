import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { cardFrom } from './compendium';
import { emptyCharacter } from './data';
import {
  actionBlocker, amountAvailable, applyRest, availableRollOptions, availableRollTriggers, rollBonus, runAction,
  selectedRollBonus, sheetActions, syncResources,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';

const cards: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
const consumables: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/consumables.json', import.meta.url), 'utf8'));
const entry = (id: string) => cards.entries.find(card => card.id === id)!;
const rules = (id: string) => entry(id).automation!;
function character(...ids: string[]) {
  const c = emptyCharacter('Caster');
  c.traits.find(t => t.name === 'Instinct')!.spellcast = true;
  c.traits.find(t => t.name === 'Instinct')!.mod = 3;
  c.hope.on = 6;
  c.stress.slots = 6;
  c.loadout = ids.map(id => cardFrom(entry(id), c));
  syncResources(c);
  return c;
}

describe('Sage and Arcana online automation', () => {
  it.each([
    ['gifted-tracker', ['question']], ['unleash-chaos', ['cast', 'replenish']], ['rune-ward', ['infuse']],
    ['flight', []], ['chain-lightning', ['cast']], ['thorn-skin', ['cast']], ['wild-fortress', ['hit']],
    ['telekinesis', []], ['cloaking-blast', ['use']], ['confusing-aura', ['defend']],
    ['rejuvenation-barrier', []], ['earthquake', []], ['fane-of-the-wilds', []],
    ['plant-dominion', []], ['sensory-projection', []], ['falling-sky', []],
  ])('keeps only the requested explicit controls on %s', (id, ids) => {
    expect(sheetActions(rules(id as string)).map(a => a.id)).toEqual(ids);
  });

  it('initializes Chaos on session reset and requires a positive available casting pool', () => {
    const c = character('unleash-chaos');
    const a = rules('unleash-chaos'), cast = a.actions![0];
    expect(a.resources![0]).toMatchObject({ session: true, sessionReset: '@spellcast' });
    expect(actionBlocker(c, 'card:Unleash Chaos', a, cast)).toContain('Not enough');
    c.resources!.chaos = 3;
    expect(actionBlocker(c, 'card:Unleash Chaos', a, cast)).toBeNull();
    expect(cast.hostRollAction).toBe(true);
    expect(a.conditionalRollTriggers![0].spentTokenDamage).toEqual({ sides: 10 });
  });

  it('creates Flight only from success steps and offers preselected host-paid flying actions', () => {
    const c = character('flight');
    c.traits.find(t => t.name === 'Agility')!.mod = 2;
    const a = rules('flight');
    expect(c.effects).toHaveLength(0);
    runAction(c, 'card:Flight', a, { id: 'success', label: 'Flight', steps: a.spellcastSuccessSteps }, 'Flight');
    expect(c.resources!.flight).toBe(2);
    const flying = availableRollOptions(c, ['action'])[0];
    expect(flying.option).toMatchObject({ defaultOn: true, hostOwned: true, cost: { flight: 1 } });
    expect(availableRollOptions(c, ['reaction'])).toHaveLength(0);
  });

  it('groups Conjure Swarm into independent beetle and Fire Flies spells', () => {
    const swarm = entry('conjure-swarm');
    expect(swarm.spells!.map(s => s.name)).toEqual(['Tekaira Armored Beetles', 'Fire Flies']);
    expect(swarm.spells![0].automation!.actions![0].cost).toEqual({ stress: 1 });
    expect(swarm.spells![1].automation!.conditionalRollTriggers![0]).toMatchObject({
      on: 'card:Fire Flies', cost: { hope: 1 }, damage: { dice: '2d8+3' },
    });
  });

  it('places success costs and damage on their own results, including the additional restraint', () => {
    const c = character('vicious-entangle', 'wild-fortress', 'falling-sky');
    const options = availableRollTriggers(c, { source: 'action,spellcast,card:Vicious Entangle', hope: 7, fear: 2, total: 14 });
    expect(options.map(t => t.trigger.id)).toEqual(['damage', 'restrain']);
    expect(options[1].trigger.cost).toEqual({ hope: 1 });
    expect(availableRollTriggers(c, { source: 'action,spellcast,card:Wild Fortress', hope: 2, fear: 3, total: 12, difficulty: 13 })).toHaveLength(0);
    expect(rules('falling-sky').conditionalRollTriggers![0].resourceRoll).toMatchObject({ resource: 'stress', sides: 20, bonusPerDie: 2 });
  });

  it('allows the base Confusing Aura with zero free Stress and validates variable pool availability', () => {
    const c = character('confusing-aura', 'fane-of-the-wilds');
    c.stress.on = c.stress.slots;
    expect(actionBlocker(c, 'card:Confusing Aura', rules('confusing-aura'), rules('confusing-aura').conditionalRollTriggers![0])).toBeNull();
    expect(amountAvailable(c, 'stress')).toBe(0);
    expect(amountAvailable(c, 'hope')).toBe(6);
    c.resources!.fane = 4;
    expect(amountAvailable(c, 'fane')).toBe(4);
  });

  it('offers the Fane critical token only for critical Sage spells, not Arcana or noncritical rolls', () => {
    const c = character('fane-of-the-wilds', 'vicious-entangle', 'flight');
    const context = { source: 'action,spellcast,card:Vicious Entangle', hope: 6, fear: 6, total: 12 };
    expect(availableRollTriggers(c, context).some(t => t.trigger.id === 'crit')).toBe(true);
    expect(availableRollTriggers(c, { ...context, fear: 5 }).some(t => t.trigger.id === 'crit')).toBe(false);
    expect(availableRollTriggers(c, { ...context, source: 'action,spellcast,card:Flight' }).some(t => t.trigger.id === 'crit')).toBe(false);
  });

  it('binds Thorn Skin to its tokens and ends Wild Surge with additional Stress at rest', () => {
    const c = character('thorn-skin', 'wild-surge');
    for (const id of ['thorn-skin', 'wild-surge']) {
      const card = entry(id), a = card.automation!;
      runAction(c, `card:${card.name}`, a, a.actions![0], card.name);
    }
    expect(c.effects.map(e => e.name)).toEqual(expect.arrayContaining(['Thorn Skin', 'Wild Surge']));
    const option = availableRollOptions(c, ['action']).find(o => o.source === 'Wild Surge')!;
    expect(selectedRollBonus(c, rollBonus(c, ['action']), [option]).value).toBe(1);
    expect(c.resources!['wild-surge']).toBe(1);
    applyRest(c, 'short');
    expect(c.stress.on).toBe(2);
    expect(c.resources!.thorns).toBe(0);
    expect(c.resources!['wild-surge']).toBe(0);
    expect(c.effects).toHaveLength(0);
  });

  it.each([1, 8])('reduces damage by the Rune Ward die %i and destroys it only on 8', face => {
    const c = character();
    c.resources = { 'ward-power': 1 };
    c.effects.push({ id: 'ward', name: 'Rune Ward benefits', kind: 'buff', tokenResource: 'ward-power',
      resources: [{ id: 'ward-power', label: 'Ward power', initial: 1 }],
      mitigation: [{ id: 'ward', label: 'Rune Ward', cost: { hope: 1 }, roll: { sides: 8 }, destroyResourceOnMax: 'ward-power', reportRoll: true }] });
    const mitigations = availableMitigations(c);
    const result = computeDamage(c, mitigations, { damage: 15, type: 'physical', armor: false,
      selected: new Set([mitigations[0].id]), rolls: { [mitigations[0].id]: [face] } });
    expect(result.damage).toBe(15 - face);
    applyDamage(c, result, 'physical');
    expect(c.hope.on).toBe(5);
    expect(c.resources!['ward-power']).toBe(face === 8 ? 0 : 1);
    expect(c.effects.some(e => e.name === 'Rune Ward benefits')).toBe(face !== 8);
  });

  it('adds all five automated foraged consumables, including a one-die reroll charm', () => {
    const items = consumables.entries.filter(e => e.id.startsWith('foraged-'));
    expect(items).toHaveLength(5);
    expect(items.every(e => e.automation?.actions?.length)).toBe(true);
    expect(items.find(e => e.id === 'foraged-rune')!.automation!.rollOptions![0]).toMatchObject({ roll: 'spellcast', value: 2, consumeItem: true });
    expect(items.find(e => e.id === 'foraged-charm')!.automation!.actions![0].steps![0].effect!.rollTriggers![0]).toMatchObject({ on: 'any', rerollAnyDie: true, endEffect: 'Foraged Luck Charm' });
  });
});
