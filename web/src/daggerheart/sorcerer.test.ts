import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, normalizeSheet, type DomainCard, type Weapon } from './data';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, derivedStats,
  runActionDetailed, selectedRollBonus, rollBonus, syncDerived, traitTotal, usesLeft,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';
import { channelRawPower, chooseActionEffects } from './sorcerer';
import { damageDieChoices } from './sorcerer-view';
import { buildCharacter, creationProblems, emptyChoices, type RulesData } from './rules';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
const magic: Weapon = { name: 'Wand', slot: 'Primary', damage: 'd8+2', damageType: 'magic', trait: 'Instinct', range: 'Far', hands: 1, tier: 1 };
const card: DomainCard = { name: 'Spell', level: 3, domain: 'Arcana', type: 'Spell', text: 'A spell.', recall: 1 };

function sorcerer(subclass = 'elemental-origin') {
  const c = emptyCharacter('Sorcerer');
  c.className = 'Sorcerer';
  c.subclass = subclass === 'elemental-origin' ? 'Elemental Origin' : 'Primal Origin';
  c.hope.on = 6;
  c.activeWeapons = [structuredClone(magic)];
  c.features = catalog.entries.filter(e => e.id === 'sorcerer' || e.id.startsWith(`sorcerer-${subclass}-`))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f),
      source: e.kind === 'class' ? 'Class feature - Sorcerer' : `Subclass ${e.stage} - ${c.subclass}` })));
  c.traits.find(t => t.name === 'Instinct')!.spellcast = true;
  syncDerived(c);
  return c;
}

function feature(c: ReturnType<typeof sorcerer>, name: string) {
  const auto = c.features.find(f => f.name === name)!.automation!;
  return { auto, action: auto.actions![0], key: `feat:${name}` };
}

describe('Sorcerer catalog automation', () => {
  it('retains the two manual actions and offers dice edits only on magic damage', () => {
    const c = sorcerer('primal-origin');
    expect(feature(c, 'Volatile Magic').action.cost).toEqual({ hope: 3 });
    expect(feature(c, 'Manipulate Magic').action.cost).toEqual({ stress: 1 });
    for (const source of ['damage,magicdamage', 'damage,spelldamage']) {
      expect(availableRollTriggers(c, { source, hope: 0, fear: 0, total: 14, kind: 'damage' })
        .map(t => t.trigger.damageDice).sort()).toEqual(['double', 'reroll']);
    }
    expect(availableRollTriggers(c, { source: 'damage', hope: 0, fear: 0, total: 14, kind: 'damage' })).toHaveLength(0);
    const attack = availableRollTriggers(c, { source: 'action,attack', weapon: 'Wand', hope: 8, fear: 4, total: 12 });
    expect(attack).toHaveLength(1);
    expect(attack[0].trigger.choices?.map(choice => choice.addDice)).toEqual([undefined, '2', undefined]);
    expect(availableRollTriggers(c, { source: 'action,trait:Instinct', hope: 8, fear: 4, total: 12 })).toHaveLength(0);
    expect(availableRollTriggers(c, { source: 'action,spellcast', hope: 8, fear: 4, total: 12 }).filter(t => t.source === 'Manipulate Magic')).toHaveLength(1);
  });

  it('rolls Minor Illusion against 10 and posts the unmodified Evasion/Help dice', () => {
    const c = sorcerer('primal-origin');
    expect(feature(c, 'Minor Illusion').action.actionRoll).toEqual({ trait: 'spellcast', difficulty: 10 });
    const evasion = feature(sorcerer(), 'Natural Evasion').action;
    expect(evasion.sendRoll).toMatchObject({ dice: '1d6' });
    expect(evasion.cost).toEqual({ stress: 1 });
    const help = feature(c, 'Enchanted Aid');
    const manual = help.auto.actions!.find(a => a.id === 'manual-swap')!;
    expect(manual.label).toBe('Swap Duality dice');
    expect(runActionDetailed(c, help.key, help.auto, manual, 'Enchanted Aid')).not.toBeNull();
    expect(c.usage[help.key].used).toBe(1);
    expect(help.action.usesSource).toBe(false);
    expect(help.action.sendRoll?.dice).toBe('1d8');
    c.usage['feat:Enchanted Aid'] = { used: 1, per: 'longRest' };
    expect(actionBlocker(c, help.key, help.auto, help.action)).toBeNull();
    const [swap] = availableRollTriggers(c, { source: 'spellcast,action', hope: 2, fear: 9, total: 11 })
      .filter(t => t.source === 'Enchanted Aid');
    expect(swap.trigger).toMatchObject({ audience: 'ally', swapDuality: true });
    expect(swap.automation.uses).toEqual({ max: 1, per: 'longRest' });
    for (let i = 0; i < 3; i++) expect(runActionDetailed(c, help.key, help.auto, help.action, 'Enchanted Aid')).not.toBeNull();
    expect(c.usage['feat:Enchanted Aid'].used).toBe(1);
    expect(actionBlocker(c, swap.key, swap.automation, swap.trigger)).toContain('No uses left');
    applyRest(c, 'short');
    expect(actionBlocker(c, swap.key, swap.automation, swap.trigger)).toContain('No uses left');
    applyRest(c, 'long');
    expect(actionBlocker(c, swap.key, swap.automation, swap.trigger)).toBeNull();
    expect(actionBlocker(c, help.key, help.auto, manual)).toBeNull();
    runActionDetailed(c, swap.key, swap.automation, swap.trigger, 'Enchanted Aid');
    expect(actionBlocker(c, help.key, help.auto, manual)).toContain('No uses left');
  });

  it('vaults only an active card, grants capped Hope or one consumable level-based damage buff', () => {
    const c = sorcerer();
    c.hope.on = 1;
    c.loadout = [structuredClone(card)];
    const { auto, action, key } = feature(c, 'Channel Raw Power');
    expect(() => channelRawPower(c, key, auto, action, 'Missing', 'hope')).toThrow('active loadout');
    expect(c.usage[key]).toBeUndefined();
    channelRawPower(c, key, auto, action, card.name, 'hope');
    expect(c.hope.on).toBe(4);
    expect(c.loadout).toHaveLength(0);
    expect(c.vault).toHaveLength(1);
    c.loadout = [structuredClone(card)];
    expect(() => channelRawPower(c, key, auto, action, card.name, 'damage')).toThrow('No uses left');
    applyRest(c, 'long');
    channelRawPower(c, key, auto, action, card.name, 'damage');
    const option = availableRollOptions(c, ['damage'], magic).find(o => o.source === 'Channel Raw Power')!;
    expect(option.option.value).toBe(6);
    expect(availableRollOptions(c, ['damage'], { ...magic, damageType: 'phys' }).some(o => o.source === 'Channel Raw Power')).toBe(false);
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [option]).value).toBe(6);
    commitRollOptions(c, [option]);
    expect(c.effects).toHaveLength(0);
  });

  it('requires two distinct Transcendence benefits and applies every passive stat until a rest', () => {
    const c = sorcerer();
    const { auto, action, key } = feature(c, 'Transcendence');
    const base = derivedStats(c);
    expect(() => chooseActionEffects(c, key, auto, action, ['severe', 'severe'])).toThrow('different benefits');
    expect(() => chooseActionEffects(c, key, auto, action, ['trait', 'proficiency'])).toThrow('Choose a trait');
    expect(c.usage[key]).toBeUndefined();
    chooseActionEffects(c, key, auto, action, ['severe', 'evasion']);
    expect(derivedStats(c)).toMatchObject({ severe: base.severe + 4, evasion: base.evasion + 2 });
    expect(usesLeft(c, key, auto)).toBe(0);
    applyRest(c, 'short');
    expect(derivedStats(c)).toEqual(base);
    expect(usesLeft(c, key, auto)).toBe(0);
    applyRest(c, 'long');
    const before = traitTotal(c, 'Knowledge');
    chooseActionEffects(c, key, auto, action, ['trait', 'proficiency'], 'Knowledge');
    expect(traitTotal(c, 'Knowledge')).toBe(before + 1);
    expect(derivedStats(c).proficiency).toBe(base.proficiency + 1);
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    expect(traitTotal(loaded, 'Knowledge')).toBe(before + 1);
  });

  it('grants the same long-rest Charge through both buttons and magic mitigation, and consumes +10 once', () => {
    const c = sorcerer('primal-origin');
    const { auto, key } = feature(c, 'Arcane Charge');
    for (const action of auto.actions!) {
      const before = c.hope.on;
      runActionDetailed(c, key, auto, action, 'Arcane Charge');
      expect(c.hope.on).toBe(before - Number(action.cost?.hope ?? 0));
      expect(c.effects.filter(e => e.name === 'Charged')).toHaveLength(1);
    }
    c.effects = [];
    const options = availableMitigations(c);
    const charge = options.find(m => m.source === 'Arcane Charge')!;
    expect(charge.option.defaultOn).toBe(true);
    const input = { damage: 2, armor: false, selected: new Set([charge.id]) };
    applyDamage(c, computeDamage(c, options, { ...input, type: 'physical' }), 'physical');
    expect(c.effects).toHaveLength(0);
    applyDamage(c, computeDamage(c, options, { ...input, damage: 0, type: 'magic' }), 'magic');
    expect(c.effects).toHaveLength(0);
    applyDamage(c, computeDamage(c, options, { ...input, type: 'magic' }), 'magic');
    expect(c.effects.map(e => e.name)).toEqual(['Charged']);
    applyRest(c, 'short');
    const bonus = availableRollOptions(c, ['damage'], magic).find(o => o.source === 'Arcane Charge')!;
    expect(bonus.option.value).toBe(10);
    expect(availableRollOptions(c, ['damage'], { ...magic, damageType: 'phys' }).some(o => o.source === 'Arcane Charge')).toBe(false);
    commitRollOptions(c, [bonus]);
    expect(c.effects).toHaveLength(0);
    runActionDetailed(c, key, auto, auto.actions![1], 'Arcane Charge');
    applyRest(c, 'long');
    expect(c.effects).toHaveLength(0);
  });

  it('validates, saves and reloads the creation element and charges the optional bonuses separately', () => {
    const d: RulesData = { classes: catalog.entries, ancestries: [], communities: [], transformations: [], cards: [], weapons: [], armor: [], consumables: [] };
    const choices = { ...emptyChoices(), className: 'Sorcerer', subclass: 'Sorcerer: Elemental Origin' };
    expect(creationProblems(d, choices)).toContain('Choose your Elementalist element.');
    choices.element = 'Lightning';
    expect(creationProblems(d, choices)).not.toContain('Choose your Elementalist element.');
    const testing = emptyCharacter('Sorcerer');
    testing.testingCharacter = true;
    const c = buildCharacter(d, choices, testing);
    expect(c.element).toBe('Lightning');
    c.hope.on = 6;
    for (const [targets, expected] of [[['action'], 2], [['damage'], 3]] as const) {
      const option = availableRollOptions(c, [...targets]).find(o => o.source === 'Elementalist')!;
      expect(option.option.value).toBe(expected);
      commitRollOptions(c, [option]);
    }
    expect(c.hope.on).toBe(4);
    updateCharacterAutomation(c, [catalog]);
    expect(normalizeSheet(JSON.parse(JSON.stringify(c)), c.name).element).toBe('Lightning');
  });

  it('enumerates actual mixed damage dice with their global indexes and excludes unmodifiable Combo Dice', () => {
    const dice = damageDieChoices({ id: 1, ts: 1, fromId: 'p', from: 'P', total: 26, expression: '(2d8kh1+1d4+3)*2+3',
      rolls: [2, 6, 4, 3], breakdown: [
        { label: 'Weapon', expression: '(2d8kh1+1d4)*2', rolls: [2, 6, 4], total: 20 },
        { label: 'Modifier', expression: '(3)*2', rolls: [], total: 6 },
        { label: 'Combo Strike rolls', expression: '1d4', rolls: [3], total: 3 },
      ] });
    expect(dice.map(d => [d.index, d.sides, d.value, d.kept])).toEqual([[0, 8, 2, false], [1, 8, 6, true], [2, 4, 4, true]]);
  });

  it('lets the Foraged Luck Charm select a Disadvantage or Combo die without widening ordinary rerolls', () => {
    const roll = { id: 1, ts: 1, fromId: 'p', from: 'P', total: 11, expression: '2d12-1d6+3',
      rolls: [5, 6, -3, 3], breakdown: [
        { label: 'Action', expression: '2d12-1d6', rolls: [5, 6, -3], total: 8 },
        { label: 'Combo Strike rolls', expression: '1d4', rolls: [3], total: 3 },
      ] };
    expect(damageDieChoices(roll).map(d => d.index)).toEqual([0, 1]);
    expect(damageDieChoices(roll, true).map(d => [d.index, d.sides])).toEqual([[0, 12], [1, 12], [2, 6], [3, 4]]);
  });
});
