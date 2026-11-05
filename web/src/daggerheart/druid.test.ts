import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, type CharacterSheet } from './data';
import {
  actionBlocker, activeFeatures, activeWeapons, addEffect, applyRest, availableRollOptions, damagePool, derivedStats,
  runActionDetailed, syncDerived, traitTotal,
} from './engine';
import { activateAura, beastformCatalog, buildBeastform, canCastDomain, channelElement, transformBeastform } from './druid';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
function druid(level = 8) {
  const c = emptyCharacter('Druid');
  Object.assign(c, { className: 'Druid', subclass: 'Warden of the Elements', level });
  c.hp.slots = 6; c.hp.on = 0; c.hope.on = 6; c.stress.slots = 12; c.stress.on = 0; c.proficiency.on = 3;
  c.features = catalog.entries.filter(e => e.id === 'druid' || e.id.startsWith('druid-warden-of-the-elements'))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: e.kind === 'class' ? 'Class feature - Druid' : `Subclass ${e.stage} - Warden of the Elements` })));
  return c;
}
function action(c: CharacterSheet, name: string, id?: string) {
  const a = activeFeatures(c).find(f => f.name === name)!.automation!;
  return { a, action: id ? a.actions!.find(x => x.id === id)! : a.actions![0], key: `feat:${name}` };
}
function transform(c: CharacterSheet, form: string) {
  const use = action(c, 'Beastform');
  return transformBeastform(c, use.key, use.a, use.action, { form });
}
function channel(c: CharacterSheet, element: string) {
  const use = action(c, `Elemental Incarnation: ${element}`);
  return channelElement(c, use.key, use.a, use.action);
}
function aura(c: CharacterSheet) {
  const use = action(c, 'Elemental Aura');
  return activateAura(c, use.key, use.a, use.action);
}

describe('Druid automation', () => {
  it('contains all 24 categories and rejects above-tier choices without spending resources', () => {
    const c = druid(1);
    expect(beastformCatalog(c)).toHaveLength(24);
    expect(() => transform(c, 'Powerful Beast')).toThrow('tier');
    expect(c.stress.on).toBe(0);
    expect(c.effects).toHaveLength(0);
  });
  it('temporarily supplies attacks, stats, advantages and features without changing equipment', () => {
    const c = druid();
    const weapons = structuredClone(c.activeWeapons);
    const baseTrait = traitTotal(c, 'Strength');
    const base = derivedStats(c);
    transform(c, 'Powerful Beast');
    expect(c.stress.on).toBe(1);
    expect(traitTotal(c, 'Strength')).toBe(baseTrait + 3);
    expect(derivedStats(c).major).toBe(base.major + 2);
    expect(activeWeapons(c)[0]).toMatchObject({ damage: 'd10+4', trait: 'Strength' });
    expect(activeFeatures(c).some(f => f.name === 'Rampage')).toBe(true);
    expect(availableRollOptions(c, ['attack', 'action']).some(o => o.option.proficiency === 1)).toBe(true);
    for (const targets of [['reaction'], ['action'], ['attack', 'action']]) {
      const advantages = availableRollOptions(c, targets).filter(o => o.source === 'Beastform Advantages');
      expect(advantages).toHaveLength(1);
      expect(advantages[0].option).toMatchObject({ id: 'advantages', advantage: 'advantage' });
      expect(advantages[0].option.label).toContain('navigate, protect, scare');
    }
    expect(canCastDomain(c)).toBe(false);
    c.effects = [];
    syncDerived(c);
    expect(c.activeWeapons).toEqual(weapons);
    expect(traitTotal(c, 'Strength')).toBe(baseTrait);
    expect(activeFeatures(c).some(f => f.name === 'Rampage')).toBe(false);
    expect(canCastDomain(c)).toBe(true);
  });
  it('Evolution chooses a separate trait and pays Hope rather than base Stress', () => {
    const c = druid();
    const base = traitTotal(c, 'Knowledge');
    const use = action(c, 'Evolution');
    transformBeastform(c, use.key, use.a, use.action, { form: 'Agile Scout', trait: 'Knowledge' });
    expect(c.hope.on).toBe(3);
    expect(c.stress.on).toBe(0);
    expect(traitTotal(c, 'Knowledge')).toBe(base + 1);
  });
  it('rolls poison and Fire retaliation directly without damage-roll bonuses', () => {
    const c = druid();
    channel(c, 'Fire');
    const fire = action(c, 'Elemental Incarnation: Fire', 'retaliate');
    expect(fire.action.sendRoll?.dice).toBe('1d10');
    expect(fire.action.damage).toBeUndefined();
    expect(actionBlocker(c, fire.key, fire.a, fire.action)).toBeNull();
    for (const [form, feature] of [['Stalking Arachnid', 'Venomous Bite'], ['Striking Serpent', 'Venomous Strike']]) {
      transform(c, form);
      const poison = action(c, feature, 'poison');
      expect(poison.action.sendRoll?.dice).toBe('1d10');
      expect(poison.action.sendRoll?.label).toContain('direct physical');
      expect(poison.action.damage).toBeUndefined();
      expect(availableRollOptions(c, ['damage']).some(o => o.source === feature)).toBe(false);
    }
    c.effects = [];
    expect(actionBlocker(c, fire.key, fire.a, fire.action)).toBeTruthy();
  });
  it('builds evolved forms with base features and the specified additive upgrades', () => {
    const c = druid();
    const legendary = buildBeastform(c, { form: 'Legendary Beast', base: 'Pack Predator' }, false).effect;
    expect(legendary.weapon?.damage).toBe('d8+2');
    expect(legendary.modifiers).toContainEqual({ roll: 'damage', value: 6 });
    expect(legendary.modifiers).toContainEqual({ stat: 'trait.Strength', value: 3 });
    expect(legendary.modifiers).toContainEqual({ stat: 'evasion', value: 3 });
    const mythic = buildBeastform(c, { form: 'Mythic Beast', base: 'Powerful Beast' }, false).effect;
    expect(mythic.weapon?.damage).toBe('d12+4');
    expect(mythic.features?.some(f => f.name === 'Rampage')).toBe(true);
    expect(() => buildBeastform(c, { form: 'Legendary Beast', base: 'Powerful Beast' }, false)).toThrow('ordinary');
  });
  it('validates hybrid counts, ordinary categories, distinct selections and extra Stress', () => {
    const c = druid();
    const choice = { form: 'Legendary Hybrid', categories: ['Agile Scout', 'Pack Predator'],
      advantages: ['deceive', 'locate', 'sneak', 'attack'], features: ['Agile', 'Pack Hunting'] };
    const use = action(c, 'Beastform');
    transformBeastform(c, use.key, use.a, use.action, choice);
    expect(c.stress.on).toBe(2);
    expect(activeWeapons(c)[0].damage).toBe('d10+8');
    expect(activeFeatures(c).some(f => f.name === 'Pack Hunting')).toBe(true);
    expect(() => buildBeastform(c, { ...choice, features: ['Agile', 'Agile'] }, false)).toThrow('different features');
    expect(() => buildBeastform(c, { ...choice, categories: ['Agile Scout', 'Agile Scout'] }, false)).toThrow('different ordinary');
    expect(() => buildBeastform(c, { ...choice, categories: ['Legendary Beast', 'Pack Predator'] }, false)).toThrow('ordinary');
  });
  it('removes Fragile forms on Major damage but durable forms only at last HP', () => {
    const c = druid();
    transform(c, 'Agile Scout');
    let r = computeDamage(c, availableMitigations(c), { damage: derivedStats(c).major, type: 'physical', armor: false, selected: new Set() });
    applyDamage(c, r, 'physical');
    expect(c.effects.some(e => e.beastform)).toBe(false);
    c.hp.on = 0;
    transform(c, 'Powerful Beast');
    r = computeDamage(c, availableMitigations(c), { damage: derivedStats(c).severe, type: 'magic', armor: false, selected: new Set() });
    applyDamage(c, r, 'magic');
    expect(c.effects.some(e => e.beastform)).toBe(true);
    c.hp.on = c.hp.slots;
    syncDerived(c);
    expect(c.effects.some(e => e.beastform)).toBe(false);
  });
  it('removes dependent shell buffs and old form features on replacement', () => {
    const c = druid();
    c.activeArmor = { name: 'Armor', score: 3, tier: 1, major: 6, severe: 12 };
    syncDerived(c);
    transform(c, 'Armored Sentry');
    const use = action(c, 'Armored Shell');
    runActionDetailed(c, use.key, use.a, use.action, 'Armored Shell');
    expect(c.effects.find(e => e.name === 'Retracted Shell')?.requiresEffect).toContain('Armored Sentry');
    transform(c, 'Pack Predator');
    expect(c.effects.some(e => e.name === 'Retracted Shell')).toBe(false);
    expect(activeFeatures(c).some(f => f.name === 'Armored Shell')).toBe(false);
  });
  it('channels exclusively, grants owned Dominion and ends aura on switching', () => {
    const c = druid();
    const base = derivedStats(c);
    channel(c, 'Earth');
    expect(derivedStats(c).major).toBe(base.major + 3);
    const earth = availableMitigations(c).find(m => m.option.id === 'earth')!;
    const result = computeDamage(c, [earth], { damage: derivedStats(c).severe, type: 'magic', armor: false, selected: new Set([earth.id]) }, () => 6);
    expect(result.hp).toBe(0);
    c.hp.on = c.hp.slots - 1;
    const capped = computeDamage(c, [earth], { damage: derivedStats(c).severe, type: 'magic', armor: false, selected: new Set([earth.id]) }, () => 6);
    expect(capped.used[earth.id]).toHaveLength(1);
    expect(capped.hp).toBe(0);
    c.hp.on = 0;
    aura(c);
    channel(c, 'Air');
    expect(c.effects.some(e => e.name === 'Channeling: Earth' || e.group === 'aura' || e.name === 'Elemental Dominion: Earth')).toBe(false);
    expect(derivedStats(c).evasion).toBe(base.evasion + 1);
    expect(availableRollOptions(c, ['action', 'trait:Agility']).some(o => o.option.defaultOn && o.option.advantage === 'advantage')).toBe(true);
    expect(() => aura(c)).toThrow('unavailable');
    applyRest(c, 'short');
    syncDerived(c);
    expect(c.effects.some(e => e.group === 'channel' || e.requiresEffect)).toBe(false);
  });
  it('gates aura buttons and expires channel, Dominion and aura on Severe damage', () => {
    const c = druid();
    const water = action(c, 'Elemental Aura: Water');
    expect(actionBlocker(c, water.key, water.a, water.action)).toBeTruthy();
    channel(c, 'Water'); aura(c);
    expect(actionBlocker(c, water.key, water.a, water.action)).toBeNull();
    const r = computeDamage(c, availableMitigations(c), { damage: derivedStats(c).severe, type: 'magic', armor: false, selected: new Set() });
    applyDamage(c, r, 'magic');
    expect(c.effects).toHaveLength(0);
    expect(actionBlocker(c, water.key, water.a, water.action)).toBeTruthy();
  });
  it('adds Fire Dominion dice automatically, and Earth grants remain opt-in', () => {
    const c = druid();
    channel(c, 'Fire');
    expect(c.effects.find(e => e.name === 'Elemental Dominion: Fire')?.damage?.extraDice).toBe(1);
    transform(c, 'Pack Predator');
    expect(damagePool(c, activeWeapons(c)[0])?.extraDice).toBe(1);
    expect(damagePool(c, { ...activeWeapons(c)[0], fixedDice: true })).toBeUndefined();
    const base = traitTotal(c, 'Strength');
    addEffect(c, { name: 'Earth aura', kind: 'buff', rollOptions: [{ id: 'strength', label: '+1 Strength', roll: 'trait:Strength', value: 1 }] });
    const option = availableRollOptions(c, ['action', 'trait:Strength']).find(o => o.source === 'Earth aura')!;
    expect(option.option.defaultOn).toBeUndefined();
    expect(traitTotal(c, 'Strength')).toBe(base);
  });
  it('refreshes legacy elemental lists without duplicate names or resetting uses', () => {
    const c = druid();
    c.features = c.features.filter(f => !f.name.includes(':'));
    c.features.push({ name: 'Fire', source: 'Subclass foundation - Warden of the Elements', text: 'Old combined list' });
    c.usage['feat:Elemental Aura'] = { used: 1, per: 'rest' };
    updateCharacterAutomation(c, [catalog]); updateCharacterAutomation(c, [catalog]);
    expect(c.features.filter(f => /^Elemental (Incarnation|Aura|Dominion):/.test(f.name))).toHaveLength(12);
    expect(c.features.some(f => f.name === 'Fire')).toBe(false);
    expect(c.usage['feat:Elemental Aura'].used).toBe(1);
  });
  it('reports the total Clarity Stress and Warden ally count, and configures targeted Regeneration', () => {
    const c = druid();
    const renewal = catalog.entries.filter(e => e.id.startsWith('druid-warden-of-renewal'));
    c.features.push(...renewal.flatMap(e => e.features!.map(f => ({ ...f, source: `Subclass ${e.stage} - Warden of Renewal` }))));
    const clarity = action(c, 'Clarity of Nature');
    expect(runActionDetailed(c, clarity.key, clarity.a, clarity.action, 'Clarity')!.join(' ')).toContain(`${Math.max(0, traitTotal(c, 'Instinct'))} Stress in total`);
    const protection = action(c, 'Warden’s Protection');
    expect(runActionDetailed(c, protection.key, protection.a, protection.action, 'Protection', () => 4)!.join(' ')).toContain('up to 4 allies');
    expect(c.hope.on).toBe(4);
    const regen = action(c, 'Regeneration');
    expect(regen.action).toMatchObject({ target: 'actor', cost: { hope: 3 }, steps: [{ op: 'clear', resource: 'hp', amount: '1d4' }] });
  });
});
