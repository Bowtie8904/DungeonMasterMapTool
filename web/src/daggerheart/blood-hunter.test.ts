import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, normalizeSheet } from './data';
import {
  actionBlocker, actionCost, addEffect, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions,
  derivedStats, rollBonus, rollOptionDice, runAction, selectedRollBonus, sheetActions, statBreakdown, suggestedAdvantage, traitTotal,
} from './engine';
import { hpHealingFloor, recoverableHp, syncDeathState } from './death';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { mutagenChoices, resolveRest, restSlots, type RestPick } from './rest';
import { updateCharacterAutomation } from './persist';
import { effectSource } from '../effects';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function hunter(order: string, stages = ['foundation', 'specialization', 'mastery']) {
  const c = emptyCharacter('Hunter');
  c.className = 'Blood Hunter';
  c.subclass = `Order of the ${order}`;
  c.hp.slots = 7;
  c.hope.on = 6;
  c.stress.slots = 6;
  c.proficiency.on = 3;
  c.traits[0].spellcast = true;
  c.features = catalog.entries.filter(e => e.id === 'blood-hunter'
    || e.id?.startsWith(`blood-hunter-order-of-the-${order.toLowerCase().replace(/ /g, '-')}-`) && stages.includes(e.stage!))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), text: f.text ?? '',
      source: e.kind === 'class' ? 'Class feature - Blood Hunter' : `Subclass ${e.stage} - Order of the ${order}` })));
  return c;
}

function use(c: ReturnType<typeof hunter>, name: string) {
  const automation = c.features.find(f => f.name === name)!.automation!;
  return runAction(c, `feat:${name}`, automation, automation.actions![0], name);
}

const brew = (benefits: string[]): RestPick[] => [{ move: '' }, { move: '' }, {
  move: 'act:feat:Mutagens|use', mutagens: { increase: 'Agility', decrease: 'Strength', benefits },
}];

describe('Blood Hunter automation', () => {
  it('offers Shadowed Grit on Fear action rolls but never reaction rolls', () => {
    const c = hunter('Ghost Slayer');
    const ctx = { hope: 2, fear: 8, total: 10 };
    const grit = (source: string) => availableRollTriggers(c, { ...ctx, source }).some(t => t.source === 'Shadowed Grit');
    expect(grit('action,trait:Agility')).toBe(true);
    expect(grit('reaction,trait:Agility')).toBe(false);
    expect(grit('action,reaction,trait:Agility')).toBe(false);
    expect(availableRollTriggers(c, { ...ctx, hope: 10, source: 'action' }).some(t => t.source === 'Shadowed Grit')).toBe(false);
  });

  it('creates a single Blood Maledict buff with optional advantage, expiring on Severe damage or rest', () => {
    const c = hunter('Ghost Slayer');
    expect(use(c, 'Blood Maledict')).toBeTruthy();
    expect(c.hope.on).toBe(3);
    const option = availableRollOptions(c, ['action']).filter(o => o.source === 'Blood Maledict');
    expect(suggestedAdvantage(option)).toBe(1);
    use(c, 'Blood Maledict');
    expect(c.effects.filter(e => e.name === 'Blood Maledict')).toHaveLength(1);
    c.hp.on = c.hp.slots;
    applyDamage(c, computeDamage(c, availableMitigations(c), { damage: 999, type: 'physical', armor: false, selected: new Set() }), 'physical');
    expect(c.effects.some(e => e.name === 'Blood Maledict')).toBe(false);
    c.hope.on = 3;
    use(c, 'Blood Maledict');
    applyRest(c, 'short');
    expect(c.effects.some(e => e.name === 'Blood Maledict')).toBe(false);
  });

  it.each([[1, '1d6'], [5, '2d6'], [8, '3d6']])('scales Crimson Rite at level %i without choosing a weapon or type', (level, dice) => {
    const c = hunter('Ghost Slayer');
    c.level = Number(level);
    expect(use(c, 'Crimson Rite')).toBeTruthy();
    expect(c.hp.on).toBe(1);
    const option = availableRollOptions(c, ['damage'])[0];
    expect(rollOptionDice(c, option.option)).toBe(dice);
    commitRollOptions(c, [option]);
    expect(c.effects.some(e => e.name === 'Crimson Rite')).toBe(true);
    applyRest(c, 'long');
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
  });

  it('evaluates Chasing Death against current HP, including after healing and all-HP-marked states', () => {
    const c = hunter('Ghost Slayer');
    c.level = 8;
    const action = c.features.find(f => f.name === 'Crimson Rite')!.automation!.actions![0];
    for (const free of [1, 2, 3]) {
      c.hp.on = c.hp.slots - free;
      expect(actionCost(c, action)).toEqual({ hp: 0 });
      expect(use(c, 'Crimson Rite')).toBeTruthy();
      expect(c.hp.on).toBe(c.hp.slots - free);
      expect(rollOptionDice(c, availableRollOptions(c, ['damage'])[0].option)).toBe(free === 1 ? '3d8' : '3d6');
    }
    c.hp.on = 0;
    expect(actionCost(c, action)).toEqual({ hp: 1 });
    expect(rollOptionDice(c, availableRollOptions(c, ['damage'])[0].option)).toBe('3d6');
    c.hp.on = c.hp.slots;
    expect(use(c, 'Crimson Rite')).toBeNull();
    c.features = c.features.filter(f => f.name !== 'Chasing Death');
    c.hp.on = c.hp.slots - 1;
    expect(actionCost(c, action)).toEqual({ hp: 1 });
    expect(rollOptionDice(c, availableRollOptions(c, ['damage'])[0].option)).toBe('3d6');
  });

  it('offers the Psychometry Stress claim only on a successful feature Spellcast, including criticals', () => {
    const c = hunter('Ghost Slayer');
    const automation = c.features.find(f => f.name === 'Grim Psychometry')!.automation!;
    expect(automation.actions![0].actionRoll).toEqual({ trait: 'spellcast', difficulty: 12 });
    const ctx = { source: 'action,spellcast,feat:Grim Psychometry', hope: 2, fear: 4, total: 11 };
    expect(availableRollTriggers(c, ctx).some(t => t.source === 'Grim Psychometry')).toBe(false);
    for (const success of [{ ...ctx, total: 12 }, { ...ctx, hope: 1, fear: 1, total: 2 }]) {
      expect(availableRollTriggers(c, success).some(t => t.source === 'Grim Psychometry')).toBe(true);
    }
    expect(availableRollTriggers(c, { ...ctx, source: 'action,spellcast', total: 20 })
      .some(t => t.source === 'Grim Psychometry')).toBe(false);
    runAction(c, 'feat:Grim Psychometry', automation, automation.rollTriggers![0], 'Vision');
    expect(c.stress.on).toBe(1);
    expect(c.effects.some(e => e.name === 'Grim Psychometry')).toBe(true);
  });

  it('consumes Veilwalker on its selected next attack option, not on unrelated rolls', () => {
    const c = hunter('Ghost Slayer');
    use(c, 'Veilwalker');
    expect(c.hope.on).toBe(4);
    expect(availableRollOptions(c, ['action', 'trait:Agility'])).toHaveLength(0);
    const options = availableRollOptions(c, ['action', 'attack']);
    expect(suggestedAdvantage(options)).toBe(1);
    expect(options[0].option.defaultOn).toBe(true);
    commitRollOptions(c, options);
    expect(c.effects).toHaveLength(0);
  });

  it('gates Lycan threshold and non-attack trait bonuses on Wolf Form without adding the transformation', () => {
    const c = hunter('Lycan');
    c.level = 5;
    const before = derivedStats(c);
    expect(c.transformation).toBeFalsy();
    expect(availableRollOptions(c, ['action', 'trait:Agility']).some(o => o.source === 'Werewolf')).toBe(true);
    expect(availableRollOptions(c, ['action', 'trait:Strength']).some(o => o.source === 'Control the Beast')).toBe(false);
    addEffect(c, { name: 'Wolf Form', kind: 'buff', until: 'rest', modifiers: [{ roll: 'attack', dice: '1d10' }] });
    expect(derivedStats(c).major).toBe(before.major + 3);
    expect(derivedStats(c).severe).toBe(before.severe + 3);
    expect(statBreakdown(c).major).toContainEqual({ label: 'Feral Hide', value: 3 });
    const options = availableRollOptions(c, ['action', 'trait:Agility']).filter(o => o.source === 'Control the Beast');
    expect(selectedRollBonus(c, rollBonus(c, ['action', 'trait:Agility']), options).dice).toEqual(['1d10']);
    expect(availableRollOptions(c, ['action', 'attack', 'trait:Agility']).some(o => o.source === 'Control the Beast')).toBe(false);
    expect(availableRollOptions(c, ['reaction', 'trait:Strength']).some(o => o.source === 'Control the Beast')).toBe(false);
    applyRest(c, 'short');
    expect(derivedStats(c).major).toBe(before.major);
  });
});

describe('Mutagens downtime', () => {
  it('describes every selectable benefit and expands both current and previously brewed effects individually', () => {
    const c = hunter('Mutant');
    const definition = c.features.find(f => f.name === 'Mutagens')!.automation!.actions![0].mutagens!;
    for (const benefit of definition.benefits) {
      expect(benefit.effect.text?.trim()).toBeTruthy();
      const old = { ...structuredClone(benefit.effect), id: benefit.id, source: 'Mutagens', text: undefined };
      expect(effectSource(c, old)).toEqual({ title: benefit.effect.name, text: benefit.effect.text });
    }
    resolveRest(c, 'long', brew(['durable', 'rapidity']));
    const lock = c.effects.find(e => e.name === 'Volatile Toxins')!;
    const text = c.features.find(f => f.name === 'Volatile Toxins')!.text;
    expect(lock.source).toBe('Volatile Toxins');
    expect(lock.text).toBe(text);
    expect(effectSource(c, lock)).toEqual({ title: 'From Volatile Toxins', text });
    expect(effectSource(c, { ...lock, source: 'Mutagens', text: 'Old HP lock text.' }))
      .toEqual({ title: 'From Volatile Toxins', text });
    const traits = c.effects.find(e => e.name === 'Mutagens')!;
    expect(effectSource(c, traits)?.text).toBe('+2 Agility, -2 Strength.');
  });

  it('provides a free restricted third slot and unlocks benefits/trait strength with learned features', () => {
    const c = hunter('Mutant', ['foundation']);
    const definition = c.features.find(f => f.name === 'Mutagens')!.automation!.actions![0].mutagens!;
    expect(mutagenChoices(c, definition)).toMatchObject({ traitBonus: 1, maxBenefits: 1 });
    expect(mutagenChoices(c, definition).benefits).toHaveLength(3);
    expect(restSlots(c, 'short')[2].moves).toHaveLength(1);
    expect(sheetActions(c.features.find(f => f.name === 'Mutagens')!.automation)).toHaveLength(0);
    expect(() => resolveRest(c, 'short', [{ move: '' }, { move: '' }, { move: 'prepare' }])).toThrow('not available');
    c.features.push(...hunter('Mutant').features.filter(f => ['Improved Mutagens', 'Volatile Toxins', 'Mastered Mutagens'].includes(f.name)));
    expect(mutagenChoices(c, definition)).toMatchObject({ traitBonus: 2, maxBenefits: 2 });
    expect(mutagenChoices(c, definition).benefits).toHaveLength(9);
  });

  it.each(['short', 'long'] as const)('brews two benefits after a %s rest and unlocks old HP before recovery at the next rest', kind => {
    const c = hunter('Mutant');
    const baseTrait = traitTotal(c, 'Agility');
    resolveRest(c, kind, brew(['ironskin', 'steelflesh']));
    expect(c.hp.on).toBe(1);
    expect(hpHealingFloor(c)).toBe(1);
    expect(traitTotal(c, 'Agility')).toBe(baseTrait + 2);
    expect(derivedStats(c).major).toBe(c.level + 3);
    expect(derivedStats(c).severe).toBe(c.level * 2 + 3);
    runAction(c, 'heal', {}, { id: 'heal', label: 'heal', steps: [{ op: 'clear', resource: 'hp', amount: 'all' }] }, 'heal');
    expect(c.hp.on).toBe(1);
    c.hp.on = 0;
    syncDeathState(c);
    expect(c.hp.on).toBe(1);
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), 'Hunter');
    expect(hpHealingFloor(loaded)).toBe(1);
    resolveRest(c, kind, [{ move: kind === 'long' ? 'tend-all' : 'tend' }], {}, () => 6);
    expect(c.hp.on).toBe(0);
    expect(c.effects).toHaveLength(0);
    expect(traitTotal(c, 'Agility')).toBe(baseTrait);
  });

  it('keeps new locks through the brewing rest and does not erase permanent HP', () => {
    const c = hunter('Mutant');
    c.permanentHp = 1;
    c.hp.on = 4;
    const picks = brew(['durable', 'rapidity']);
    picks[0] = { move: 'tend-all' };
    resolveRest(c, 'long', picks);
    expect(c.hp.on).toBe(2);
    expect(hpHealingFloor(c)).toBe(2);
    expect(recoverableHp(c)).toBe(0);
    expect(derivedStats(c).armorSlots).toBe(2);
    expect(derivedStats(c).evasion).toBe(c.evasion + 1);
    resolveRest(c, 'short', [{ move: 'tend' }], {}, () => 4);
    expect(c.hp.on).toBe(1);
    expect(c.permanentHp).toBe(1);
  });

  it('does not confuse the new brewing HP cost with whether the rest recovered HP', () => {
    for (const healed of [false, true]) {
      const c = hunter('Mutant');
      c.hope.on = 2;
      c.hp.on = healed ? 1 : 0;
      c.features.push({ name: 'Rest observer', source: 'test', text: '', automation: { rest: { passives: [{
        id: 'no-recovery', label: 'No recovery', defaultOn: true, condition: 'noRecovery',
        steps: [{ op: 'gain', resource: 'hope', amount: 1 }],
      }] } } });
      const picks = brew(['durable', 'rapidity']);
      if (healed) picks[0] = { move: 'tend-all' };
      resolveRest(c, 'long', picks);
      expect(c.hp.on).toBe(1);
      expect(c.hope.on).toBe(healed ? 2 : 3);
    }
  });

  it('rejects invalid traits, unavailable/duplicate/excess benefits and unaffordable HP atomically', () => {
    for (const benefits of [[], ['durable', 'durable'], ['durable', 'rapidity', 'aetherblood'], ['unknown']]) {
      const c = hunter('Mutant');
      const before = structuredClone(c);
      expect(() => resolveRest(c, 'long', brew(benefits))).toThrow('distinct available benefits');
      expect(c).toEqual(before);
    }
    const c = hunter('Mutant', ['foundation']);
    expect(() => resolveRest(c, 'long', brew(['rapidity']))).toThrow('distinct available benefits');
    const invalid = brew(['durable']);
    invalid[2].mutagens!.decrease = invalid[2].mutagens!.increase;
    expect(() => resolveRest(c, 'long', invalid)).toThrow('different traits');
    const full = hunter('Mutant');
    full.hp.on = full.hp.slots;
    expect(() => resolveRest(full, 'long', brew(['durable', 'rapidity']))).toThrow('free HP');
    expect(full.effects).toHaveLength(0);
    expect(full.longRestRevision).toBeUndefined();
  });

  it('exposes benefit actions/options with costs and preserves narrative-only Aetherblood', () => {
    const c = hunter('Mutant');
    resolveRest(c, 'long', brew(['celerity', 'nerves-of-steel']));
    const celerity = c.effects.find(e => e.name === 'Celerity')!;
    expect(actionBlocker(c, 'effect:celerity', {}, celerity.actions![0])).toContain('Required effect');
    addEffect(c, { name: 'Restrained', kind: 'debuff' });
    runAction(c, 'effect:celerity', {}, celerity.actions![0], 'Celerity');
    expect(c.stress.on).toBe(1);
    expect(c.effects.some(e => e.name === 'Restrained')).toBe(false);
    const nerves = c.effects.find(e => e.name === 'Nerves of Steel')!;
    runAction(c, 'effect:nerves', {}, nerves.actions![0], 'Nerves');
    expect(c.hope.on).toBe(4);
    resolveRest(c, 'long', brew(['hunter-senses', 'fury']));
    const options = availableRollOptions(c, ['action', 'attack']);
    const fury = options.find(o => o.source === 'Fury')!;
    expect(selectedRollBonus(c, rollBonus(c, ['attack']), [fury]).value).toBe(3);
    commitRollOptions(c, [fury]);
    expect(c.stress.on).toBe(2);
    expect(suggestedAdvantage(options.filter(o => o.source === "Hunter's Senses"))).toBe(1);
    resolveRest(c, 'long', brew(['aetherblood']));
    expect(c.effects.find(e => e.name === 'Aetherblood')).toMatchObject({ kind: 'buff', until: 'rest' });
    expect(hpHealingFloor(c)).toBe(0);
    expect(availableRollOptions(c, ['action', 'attack'])).toHaveLength(0);
  });

  it('refreshes existing Blood Hunter sheets from the catalog without resetting resources', () => {
    const c = hunter('Mutant');
    c.features.forEach(f => { f.automation = undefined; });
    c.hp.on = 2;
    updateCharacterAutomation(c, [catalog]);
    expect(restSlots(c, 'long')).toHaveLength(3);
    expect(c.hp.on).toBe(2);
    expect(c.features.find(f => f.name === 'Mastered Mutagens')!.automation).toBeUndefined();
  });
});
