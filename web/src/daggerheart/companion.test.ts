import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, normalizeSheet } from './data';
import type { CharacterSheet, CompanionTraining, LevelUpRecord } from './data';
import { actionBlocker, applyRest, availableRollOptions, availableRollTriggers, damageExpression, derivedStats, rollBonus, runActionDetailed, syncDerived } from './engine';
import { applyCompanionTraining, buildCompanion, companionAvailable, companionEntitlements, companionRules, companionTrainingProblems, companionWeapon, markCompanionDamage, resolveBonded } from './companion';
import { applyLevelUp, buildCharacter, companionLevelUpCount, companionLevelUpProblems, creationProblems, emptyChoices, type RulesData } from './rules';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { resolveRest } from './rest';
import { updateCharacterAutomation } from './persist';
import { criticalMeleeBonus } from './brawler';
import { rollSourceFor } from './roll-source';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
const foundation = catalog.entries.find(e => e.id === 'ranger-beastbound-foundation')!;
const choices = { name: 'Ash', animal: 'Wolf', attack: 'Bite', damageType: 'phys' as const, experiences: ['Scout', 'Loyal'] };
function ranger(create = true): CharacterSheet {
  const c = emptyCharacter('Ranger');
  c.className = 'Ranger'; c.subclass = 'Beastbound'; c.hp.slots = 6; c.mancer!.done = true;
  c.features = [catalog.entries.find(e => e.id === 'ranger')!, foundation].flatMap(e =>
    e.features!.map(f => ({ ...structuredClone(f), source: e === foundation ? 'Subclass foundation - Beastbound' : 'Class feature - Ranger' })));
  c.traits[0].spellcast = true; c.traits[0].mod = 2;
  c.experiences = [{ name: 'Owner only', mod: 2 }, { name: 'Tracker', mod: 2 }];
  if (create) c.companion = buildCompanion(choices, companionRules(c)!);
  return c;
}
function rulesData(): RulesData {
  return { classes: catalog.entries, ancestries: [], communities: [], weapons: [], armor: [], consumables: [], cards: [], transformations: [] };
}
const train = (c: CharacterSheet, picks: CompanionTraining[]) => applyCompanionTraining(c, picks, picks.length);
const level = (n: number, advancements: LevelUpRecord['advancements'] = [{ type: 'hp' }, { type: 'hp' }]): LevelUpRecord =>
  ({ level: n, advancements, companionTraining: [{ id: 'aware' }], ...(n === 2 || n === 5 || n === 8 ? { experience: 'Veteran', companionExperience: 'Protector' } : {}) });

describe('Beastbound Ranger companions', () => {
  it('uses the official sheet starting values and caps without creating a separate actor', () => {
    const c = ranger(), pet = c.companion!;
    expect(pet.stress).toEqual({ on: 0, slots: 3, total: 6 });
    expect(pet.evasion).toBe(10); expect(pet.damage).toBe('d6'); expect(pet.range).toBe('Melee');
    expect(pet.experienceStats).toEqual([{ name: 'Scout', mod: 2 }, { name: 'Loyal', mod: 2 }]);
    expect(companionRules(c)!.options.map(o => o.max)).toEqual([3, 1, 1, 1, 3, 3, 1, 3]);
    expect(c.activeWeapons).toEqual([]);
    expect(normalizeSheet(JSON.parse(JSON.stringify(c)), c.name).companion).toEqual(pet);
  });
  it('requires companion creation for Beastbound but not other subclasses', () => {
    const d = rulesData(), ch = { ...emptyChoices(), className: 'Ranger', subclass: 'Ranger: Beastbound', cards: [] };
    expect(creationProblems(d, ch)).toContain('Set up your companion.');
    expect(creationProblems(d, { ...ch, subclass: 'Ranger: Wayfinder' })).not.toContain('Set up your companion.');
    const rangerClass = structuredClone(d.classes.find(e => e.id === 'ranger')!);
    rangerClass.automation = { creation: { domainCards: -2 } };
    d.classes = [rangerClass, foundation];
    const c = buildCharacter(d, { ...ch, experiences: ['Owner', 'Tracker'], companion: choices });
    expect(c.companion?.name).toBe('Ash'); expect(c.mancer?.choices?.companion).toEqual(choices);
  });
  it('requires and records training and milestone Experiences as part of the owner level-up', () => {
    const c = ranger(), d = rulesData(), rec = level(2);
    expect(companionLevelUpProblems(d, c, { ...rec, companionTraining: [] })).toContain('Choose 1 companion training option(s).');
    expect(() => applyLevelUp(d, c, { ...rec, companionExperience: '' })).toThrow('companion Experience');
    expect(c.level).toBe(1);
    expect(companionLevelUpProblems(d, c, rec)).toEqual([]);
    applyLevelUp(d, c, rec);
    expect(c.companion!.evasion).toBe(12);
    expect(c.companion!.experienceStats[2]).toEqual({ name: 'Protector', mod: 2 });
    expect(c.levelUps![0].companionTraining).toEqual(rec.companionTraining);
  });
  it('grants Expert/Advanced Training only at their acquisition, not every later level', () => {
    const c = ranger(), d = rulesData();
    c.level = 4;
    const rec = level(5, [{ type: 'subclass' }, { type: 'hp' }]);
    rec.companionTraining!.push({ id: 'resilient' });
    expect(companionLevelUpCount(d, c, rec)).toBe(2);
    applyLevelUp(d, c, rec);
    expect(companionLevelUpCount(d, c, level(6))).toBe(1);
    c.level = 7;
    const mastery = level(8, [{ type: 'subclass' }, { type: 'hp' }]);
    mastery.companionTraining!.push({ id: 'resilient' }, { id: 'light-in-the-dark' });
    expect(companionLevelUpCount(d, c, mastery)).toBe(3);
    applyLevelUp(d, c, mastery);
    expect(companionLevelUpCount(d, c, level(9))).toBe(1);
  });
  it('keeps multiclass companion setup empty and catches up only eligible levels', () => {
    const c = ranger(false); c.className = 'Warrior'; c.subclass = 'Call of the Brave'; c.level = 4; c.features = [];
    const rec = level(5, [{ type: 'multiclass', multiclass: { className: 'Ranger', domain: 'Sage', subclass: 'Ranger: Beastbound' } }]);
    delete rec.companionTraining;
    applyLevelUp(rulesData(), c, rec);
    expect(companionRules(c)).toBeDefined(); expect(c.companion).toBeUndefined();
    expect(companionEntitlements(c)).toEqual({ training: 1, experiences: 3 });
    applyLevelUp(rulesData(), c, level(6));
    expect(companionEntitlements(c)).toEqual({ training: 2, experiences: 3 });
    expect(c.companion).toBeUndefined();
  });
  it('validates Intelligent targets, Vicious die/range steps and repeat limits before mutation', () => {
    const c = ranger(), rules = companionRules(c)!;
    expect(() => train(c, [{ id: 'intelligent', experience: 99 }])).toThrow('Experience');
    train(c, [{ id: 'intelligent', experience: 1 }, { id: 'vicious', increase: 'range' }, { id: 'vicious', increase: 'damage' }]);
    expect(c.companion!.experienceStats[1].mod).toBe(3);
    expect(c.companion!.range).toBe('Very Close'); expect(c.companion!.damage).toBe('d8');
    train(c, [{ id: 'vicious', increase: 'damage' }]);
    const before = JSON.stringify(c.companion);
    expect(companionTrainingProblems(rules, c.companion!, [{ id: 'vicious', increase: 'damage' }], 1)).not.toEqual([]);
    expect(() => train(c, [{ id: 'vicious', increase: 'damage' }])).toThrow('available');
    expect(JSON.stringify(c.companion)).toBe(before);
    expect(() => train(c, [{ id: 'unknown' }])).toThrow('available');
  });
  it('adds a seventh Hope slot exactly once and preserves scars and spent Hope', () => {
    const c = ranger(); c.hope.on = 4;
    train(c, [{ id: 'light-in-the-dark' }]);
    syncDerived(c); syncDerived(c);
    expect(c.hope).toEqual({ slots: 7, total: 7, on: 4 });
    expect(() => train(c, [{ id: 'light-in-the-dark' }])).toThrow('available');
    c.scars = 2; syncDerived(c);
    expect(c.hope.slots).toBe(5);
    c.features = []; syncDerived(c);
    expect(c.hope.total).toBe(6);
  });
  it('uses owner Spellcast and Proficiency, Focus and other attack benefits for companion rolls', () => {
    const c = ranger(); c.proficiency.on = 3;
    const weapon = companionWeapon(c)!;
    expect(weapon.trait).toBe('Agility');
    expect(damageExpression(weapon.damage, derivedStats(c).proficiency, rollBonus(c, ['damage'], weapon))).toBe('3d6');
    expect(availableRollOptions(c, ['action', 'attack', 'companion'], weapon).some(o => o.option.id === 'rangers-focus')).toBe(true);
    c.effects.push({ id: 'focus', name: 'Ranger’s Focus', kind: 'buff' });
    expect(availableRollTriggers(c, { source: 'companion,attack,spellcast,action', hope: 9, fear: 3, total: 14, weapon: weapon.name })
      .map(t => t.trigger.id)).toContain('reroll');
    expect(availableRollTriggers(c, { source: 'companion,spellcast,action', hope: 9, fear: 3, total: 14 })
      .map(t => t.trigger.id)).toContain('build-on-companion');
    expect(availableRollTriggers(c, { source: 'companion,spellcast,action', hope: 2, fear: 3, total: 5 })
      .map(t => t.trigger.id)).not.toContain('build-on-companion');
    expect(rollSourceFor(c, `weapon:${weapon.name}`)?.source).toContain('Companion');
    c.features.push({ name: 'Critical melee', source: 'Multiclass', text: '', automation: { criticalMelee: { hope: 1, stress: 1, proficiency: 1 } } });
    expect(criticalMeleeBonus(c, weapon.name, true).proficiency).toBe(1);
  });
  it('marks one Stress per damage, offers Armored and locks out a full-stress companion until a long rest', () => {
    const c = ranger();
    train(c, [{ id: 'armored' }, { id: 'resilient' }, { id: 'resilient' }, { id: 'resilient' }]);
    c.activeArmor = { name: 'Armor', score: 3, tier: 1 }; syncDerived(c);
    markCompanionDamage(c, true);
    expect(c.armorTrack.on).toBe(1); expect(c.companion!.stress.on).toBe(0);
    for (let i = 0; i < 6; i++) markCompanionDamage(c);
    expect(companionAvailable(c)).toBe(false);
    expect(() => markCompanionDamage(c)).toThrow('unavailable');
    applyRest(c, 'short'); expect(companionAvailable(c)).toBe(false);
    applyRest(c, 'long'); expect(companionAvailable(c)).toBe(true); expect(c.companion!.stress.on).toBe(5);
  });
  it('shares Creature Comfort uses and clears each participant independently', () => {
    const c = ranger(); train(c, [{ id: 'creature-comfort' }]); c.stress.on = 0; c.companion!.stress.on = 2;
    const auto = companionRules(c)!.options.find(o => o.id === 'creature-comfort')!.automation!;
    expect(runActionDetailed(c, 'feat:Creature Comfort', auto, auto.actions![1], 'Comfort')).toEqual(['cleared 0 owner Stress and 1 companion Stress']);
    expect(actionBlocker(c, 'feat:Creature Comfort', auto, auto.actions![0])).toContain('rest');
    applyRest(c, 'short');
    expect(runActionDetailed(c, 'feat:Creature Comfort', auto, auto.actions![0], 'Comfort')).toEqual(['gained 1 Hope']);
  });
  it('clears companion Stress with owner downtime recovery and returns before long-rest moves', () => {
    const c = ranger(); c.stress.on = 3; c.companion!.stress.on = 2;
    resolveRest(c, 'short', [{ move: 'stress' }], {}, () => 2);
    expect(c.stress.on).toBe(1); expect(c.companion!.stress.on).toBe(0);
    c.companion!.stress.on = 3; syncDerived(c); c.stress.on = 4;
    const result = resolveRest(c, 'long', [{ move: 'stress-all' }]);
    expect(result.lines.some(line => line.includes('returns'))).toBe(true);
    expect(c.companion!.stress.on).toBe(0); expect(companionAvailable(c)).toBe(true);
  });
  it.each([true, false])('Bonded spends every free companion Stress and resolves a rescue only on a six (%s)', rescued => {
    const c = ranger(); train(c, [{ id: 'bonded' }]); c.hp.on = c.hp.slots; c.companion!.stress.on = 1;
    const report = resolveBonded(c, () => rescued ? 6 : 3);
    expect(report).toContain('marks 2 Stress');
    expect(c.hp.on).toBe(c.hp.slots - (rescued ? 1 : 0));
    expect(companionAvailable(c)).toBe(false);
    expect(resolveBonded(c, () => 6)).toBeUndefined();
  });
  it('applies Bonded from incoming damage, not just manual HP pips', () => {
    const c = ranger(); train(c, [{ id: 'bonded' }]); c.hp.on = 5;
    const r = computeDamage(c, [], { damage: 1, type: 'physical', armor: false, selected: new Set() });
    expect(applyDamage(c, r, 'physical', () => 6)).toContain('Bonded');
    expect(c.hp.on).toBe(5);
  });
  it('does not consume companion Stress for HP that cannot be healed', () => {
    const c = ranger(); train(c, [{ id: 'bonded' }]); c.hp.on = c.hp.slots;
    c.effects.push({ id: 'locked', name: 'HP lock', kind: 'debuff', lockedHp: c.hp.slots });
    expect(resolveBonded(c, () => 6)).toBeUndefined();
    expect(c.companion!.stress.on).toBe(0);
  });
  it('rejects incomplete training rows instead of throwing', () => {
    const c = ranger();
    expect(companionTrainingProblems(companionRules(c)!, c.companion!, new Array(2), 2)).toHaveLength(2);
  });
  it('Loyal Friend redirects last-HP damage and shares its long-rest use with the other direction', () => {
    const c = ranger(); c.hp.on = 5;
    const loyal = catalog.entries.find(e => e.id === 'ranger-beastbound-mastery')!.features!.find(f => f.name === 'Loyal Friend')!;
    c.features.push({ ...structuredClone(loyal), source: 'Subclass mastery - Beastbound' });
    const options = availableMitigations(c), option = options.find(o => o.option.companionIntercept)!;
    const input = { damage: 10, type: 'physical' as const, armor: false, selected: new Set([option.id]) };
    const r = computeDamage(c, options, input);
    expect(r.hp).toBe(0);
    expect(applyDamage(c, r, 'physical')).toContain('marked 1 Stress');
    expect(c.hp.on).toBe(5); expect(c.companion!.stress.on).toBe(1);
    expect(actionBlocker(c, 'feat:Loyal Friend', loyal.automation!, loyal.automation!.actions![0])).not.toBeNull();
    applyRest(c, 'long'); c.companion!.stress.on = 2;
    expect(actionBlocker(c, 'feat:Loyal Friend', loyal.automation!, loyal.automation!.actions![0])).toBeNull();
    runActionDetailed(c, 'feat:Loyal Friend', loyal.automation!, loyal.automation!.actions![0], 'Loyal Friend');
    expect(computeDamage(c, options, input).hp).toBeGreaterThan(0);
  });
  it('refreshes old multiclass foundation metadata without resetting companion state or spent uses', () => {
    const c = ranger(); c.className = 'Warrior';
    c.features[2].source = 'Multiclass Ranger - Beastbound foundation';
    c.features[2].automation = undefined;
    c.features[2].text = 'Old companion description';
    c.levelUps = [{ level: 5, advancements: [{ type: 'multiclass', multiclass: { className: 'Ranger', domain: 'Sage', subclass: 'Ranger: Beastbound' } }] }];
    c.companion!.stress.on = 1; c.usage['feat:Loyal Friend'] = { used: 1, per: 'longRest' };
    const before = JSON.stringify(c.companion);
    updateCharacterAutomation(c, [catalog]);
    expect(companionRules(c)).toBeDefined(); expect(JSON.stringify(c.companion)).toBe(before);
    expect(c.features[2].text).toContain('your very next action must build on that success');
    expect(c.usage['feat:Loyal Friend'].used).toBe(1);
  });
  it('keeps Battle-Bonded passive and removes its obsolete activated effect on refresh', () => {
    const c = ranger();
    const feature = catalog.entries.find(e => e.id === 'ranger-beastbound-specialization')!.features!.find(f => f.name === 'Battle-Bonded')!;
    expect(feature.automation).toBeUndefined();
    c.features.push({ ...feature, source: 'Subclass specialization - Beastbound', automation: { actions: [] } });
    c.effects.push({ id: 'old-bond', name: 'Battle-Bonded (one attack)', kind: 'buff', modifiers: [{ stat: 'evasion', value: 2 }] });
    updateCharacterAutomation(c, [catalog]);
    expect(c.effects).toHaveLength(0);
  });
  it('announces Loyal Friend and spends its use without changing either damage track', () => {
    const c = ranger();
    const auto = catalog.entries.find(e => e.id === 'ranger-beastbound-mastery')!.features!.find(f => f.name === 'Loyal Friend')!.automation!;
    expect(auto.actions![0].confirm).toBeUndefined();
    const tracks = JSON.stringify([c.hp, c.companion!.stress, c.armorTrack]);
    expect(runActionDetailed(c, 'feat:Loyal Friend', auto, auto.actions![0], 'Loyal Friend')).toEqual([]);
    expect(JSON.stringify([c.hp, c.companion!.stress, c.armorTrack])).toBe(tracks);
    expect(actionBlocker(c, 'feat:Loyal Friend', auto, auto.actions![0])).not.toBeNull();
  });
  it('offers Hold Them Off only for damage rolls with a three-Hope cost', () => {
    const c = ranger();
    expect(availableRollOptions(c, ['attack']).some(o => o.option.label === 'Hold Them Off')).toBe(false);
    const option = availableRollOptions(c, ['damage']).find(o => o.option.label === 'Hold Them Off')!;
    expect(option.option.cost).toEqual({ hope: 3 });
    const auto = c.features.find(f => f.name === 'Hold Them Off')!.automation!;
    expect(auto.actions![0].cost).toEqual({ hope: 3 });
  });
  it('allows Armored protection for Loyal Friend without marking companion Stress or double-spending the interception', () => {
    const c = ranger(); c.hp.on = 5; c.armorTrack.slots = 2;
    const loyal = catalog.entries.find(e => e.id === 'ranger-beastbound-mastery')!.features!.find(f => f.name === 'Loyal Friend')!;
    c.features.push({ ...structuredClone(loyal), source: 'Subclass mastery - Beastbound' });
    let options = availableMitigations(c);
    const armored = options.find(m => m.option.companionArmored)!;
    const input = { damage: 10, type: 'physical' as const, armor: false, selected: new Set([armored.id]) };
    expect(computeDamage(c, options, input).inactive[armored.id]).toContain('Armored');
    train(c, [{ id: 'armored' }]); options = availableMitigations(c);
    let r = computeDamage(c, options, input);
    expect(applyDamage(c, r, 'physical')).toContain('Armored');
    expect(c.companion!.stress.on).toBe(0); expect(c.armorTrack.on).toBe(1); expect(c.hp.on).toBe(5);
    applyRest(c, 'long');
    r = computeDamage(c, options, { ...input, selected: new Set(options.filter(m => m.option.companionIntercept).map(m => m.id)) });
    expect(r.error).toContain('not enough uses');
    r = computeDamage(c, options, input);
    c.companion!.unavailable = true;
    expect(applyDamage(c, r, 'physical')).toBeNull();
    expect(c.armorTrack.on).toBe(1); expect(c.hp.on).toBe(5);
  });
});
