import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, normalizeSheet } from './data';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions,
  derivedStats, resourceDefinitions, resourceGroupRoom, runAction, summonCircles, summonEntities, summonLimits, summonProblem, syncDerived, syncResources,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';
import {
  buildCharacter, emptyChoices, guideFor, startingCards, subclassesOf, suggestedTraits, type RulesData,
} from './rules';

const load = (name: string): CompendiumFile =>
  JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${name}.json`, import.meta.url), 'utf8'));
const catalog = load('classes');
const data: RulesData = {
  classes: catalog.entries, ancestries: load('ancestries').entries, communities: load('communities').entries,
  weapons: load('weapons').entries, armor: load('armor').entries, consumables: load('consumables').entries,
  cards: load('domain-cards').entries, transformations: load('transformations').entries, items: load('items').entries,
};

function summoner(subclass = 'necromancy') {
  const c = emptyCharacter('Summoner');
  c.className = 'Summoner';
  c.subclass = subclass === 'necromancy' ? 'Necromancy' : 'Theurgy';
  c.level = 8;
  c.hp.slots = 6;
  c.hope.on = 6;
  c.evasion = 10;
  c.traits.find(t => t.name === 'Knowledge')!.spellcast = true;
  c.features = catalog.entries.filter(e => e.id === 'summoner' || e.id.startsWith(`summoner-${subclass}-`))
    .flatMap(e => (e.features ?? []).map(f => ({
      ...structuredClone(f), text: f.text ?? '',
      source: e.kind === 'class' ? 'Class feature - Summoner' : `Subclass ${e.stage} - ${c.subclass}`,
    })));
  syncDerived(c);
  return c;
}

function action(c: ReturnType<typeof summoner>, name: string, id?: string) {
  const auto = c.features.find(f => f.name === name)!.automation!;
  const definition = id ? auto.actions!.find(a => a.id === id)! : auto.actions![0];
  return { auto, definition, use: () => runAction(c, `feat:${name}`, auto, definition, name) };
}

const circles = (c: ReturnType<typeof summoner>) =>
  [1, 2, 3, 4].map(n => c.resources?.[`summoner-circle-${n}`] ?? 0);

const attack = { source: 'action,attack', hope: 10, fear: 2, total: 18, difficulty: 15 };
const eligible = (c: ReturnType<typeof summoner>, source: string, ctx = attack) =>
  availableRollTriggers(c, ctx).filter(t => t.source === source
    && !actionBlocker(c, t.key, t.automation, t.trigger));

describe('Summoner playtest catalog', () => {
  it.each(['Necromancy', 'Theurgy'])('creates %s with both domains, Knowledge and catalog-backed keepsakes', subclass => {
    const guide = guideFor('Summoner');
    const ch = { ...emptyChoices(), name: 'Summoner', className: 'Summoner',
      subclass: subclassesOf(data, 'Summoner').find(s => s.endsWith(subclass))!,
      ancestry: 'Human', community: data.communities[0].name, traits: suggestedTraits(guide),
      primary: guide.primary, armor: guide.armor, classItem: guide.classItems[0],
      experiences: ['Occult scholar', 'Spirit keeper'] };
    ch.cards = startingCards(data, ch).slice(0, 2).map(card => card.name);
    const c = buildCharacter(data, ch);
    expect(c.hp.slots).toBe(6);
    expect(c.evasion).toBe(10);
    expect(c.domains).toEqual(['Blood', 'Splendor']);
    expect(c.traits.find(t => t.spellcast)?.name).toBe('Knowledge');
    expect(c.activeWeapons[0].name).toBe('Greatstaff');
    expect(c.activeArmor?.name).toBe('Leather Armor');
    expect(c.inventory.some(i => i.name === guide.classItems[0])).toBe(true);
    expect(c.resources?.['summoner-circle-1']).toBe(0);
    for (const name of guide.classItems) {
      const item = data.items!.find(i => i.name === name)!;
      expect(item).toBeDefined();
      expect(readFileSync(new URL(`../../public${item.image}`, import.meta.url), 'utf8')).toContain('<svg');
    }
    expect(catalog.entries.filter(e => e.parent === 'Summoner' && e.name.endsWith(subclass))
      .map(e => e.stage).sort()).toEqual(['foundation', 'mastery', 'specialization']);
  });

  it('pays the Hope feature and summon Stress without fabricating recipient rewards or circle allocation', () => {
    const c = summoner();
    c.stress.on = 2;
    expect(action(c, 'Aid of the Spirits').use()).toBeTruthy();
    expect(c.hope.on).toBe(3);
    expect(c.stress.on).toBe(1);
    expect(action(c, 'Aid of the Spirits').definition.chatBenefits).toBeUndefined();
    expect(c.stress.on).toBe(1);
    const summon = action(c, 'Summon Entity');
    expect(summon.use()).toBeTruthy();
    expect(c.stress.on).toBe(2);
    expect(circles(c)).toEqual([0, 0, 0, 0]);
    c.stress.on = c.stress.slots;
    expect(action(c, 'Summon Entity').use()).toBeNull();
    const fate = action(c, 'First Circle - Fate Spirit');
    expect(fate.use()).toBeNull();
    c.resources!['summoner-circle-1'] = 1;
    expect(fate.use()).toBeTruthy();
    expect(c.resources!['summoner-circle-1']).toBe(0);
    expect(fate.use()).toBeNull();
  });

  it('summons Entities through circles with per-circle, total and per-action limits', () => {
    const c = summoner();
    c.features = c.features.filter(f => f.source?.startsWith('Class feature'));
    c.level = 5; // tier 3
    syncDerived(c);
    const { auto, definition } = action(c, 'Summon Entity');
    expect(summonCircles(c).map(x => [x.resource, x.entity])).toEqual([['summoner-circle-1', 'Fate Spirit']]);
    expect(summonLimits(c, definition)).toEqual({ perAction: 3, total: 5, held: 0, room: 5 });
    expect(() => summonEntities(c, 'feat:Summon Entity', auto, definition, 'Summon Entity', { 'summoner-circle-2': 1 }))
      .toThrow(/not unlocked/);
    expect(summonProblem(c, definition, { 'summoner-circle-1': 4 })).toMatch(/at most 3/);
    expect(summonProblem(c, definition, { 'summoner-circle-1': -1 })).toMatch(/whole numbers/);
    expect(() => summonEntities(c, 'feat:Summon Entity', auto, definition, 'Summon Entity', {})).toThrow(/at least one/);
    expect(c.stress.on).toBe(0);
    const log = summonEntities(c, 'feat:Summon Entity', auto, definition, 'Summon Entity', { 'summoner-circle-1': 3 });
    expect(log).toContain('summoned 3 Fate Spirits (First Circle)');
    expect(c.stress.on).toBe(1);
    expect(circles(c)).toEqual([3, 0, 0, 0]);
    expect(summonProblem(c, definition, { 'summoner-circle-1': 3 })).toMatch(/can hold 2 more Fate Spirits/);
    summonEntities(c, 'feat:Summon Entity', auto, definition, 'Summon Entity', { 'summoner-circle-1': 2 });
    expect(circles(c)).toEqual([5, 0, 0, 0]);
    expect(actionBlocker(c, 'feat:Summon Entity', auto, definition)).toBe('Your Entity limit is reached');
  });

  it('unlocks circles by subclass stage, names each Entity and keeps the Fourth Circle at one', () => {
    const c = summoner('theurgy');
    syncDerived(c);
    expect(summonCircles(c).map(x => x.entity)).toEqual(['Fate Spirit', 'Angel', 'Archangel', 'Divine Manifestation']);
    const { auto, definition } = action(c, 'Summon Entity');
    expect(summonProblem(c, definition, { 'summoner-circle-4': 2 })).toMatch(/Fourth Circle can hold 1 more Divine Manifestation/);
    const log = summonEntities(c, 'feat:Summon Entity', auto, definition, 'Summon Entity', { 'summoner-circle-4': 1 });
    expect(log).toContain('summoned 1 Divine Manifestation (Fourth Circle)');
    expect(c.resources!['summoner-hallowed-hope']).toBe(3);
    expect(summonCircles(summoner('necromancy')).map(x => x.entity)).toEqual(['Fate Spirit', 'Shambling Corpse', 'Ghost', 'Death Knight']);
  });

  it('groups the four circles in the header with a combined level-sized cap', () => {
    const c = summoner();
    const auto = c.features.find(f => f.name === 'Summon Entity')!.automation!;
    expect(auto.classResourceOnly).toBe(true);
    const defs = resourceDefinitions(c).filter(r => r.group === 'Summoning circles');
    expect(defs.map(r => r.label)).toEqual(['First Circle', 'Second Circle', 'Third Circle', 'Fourth Circle']);
    expect(defs.every(r => !r.cardOnly && r.groupMax === '@level')).toBe(true);
    expect(c.features.filter(f => f.name !== 'Summon Entity').flatMap(f => f.automation?.resources ?? [])
      .every(r => !r.id.startsWith('summoner-circle'))).toBe(true);
    c.resources!['summoner-circle-1'] = 5;
    c.resources!['summoner-circle-2'] = 3;
    expect(resourceGroupRoom(c, defs[2])).toBe(0);
    expect(resourceGroupRoom(c, defs[1])).toBe(3);
    c.resources!['summoner-circle-4'] = 4;
    syncResources(c);
    expect(circles(c)).toEqual([5, 3, 0, 0]);
    c.resources!['summoner-circle-2'] = 2;
    c.resources!['summoner-circle-4'] = 4;
    syncResources(c);
    expect(c.resources!['summoner-circle-4']).toBe(1);
    expect(resourceGroupRoom(c, defs[0])).toBe(5);
  });

  it('spends corpses only on confirmed hits and harvests without paying Stress', () => {
    const c = summoner();
    c.resources!['summoner-circle-2'] = 3;
    const ctx = { ...attack, source: 'action,attack,spellcast,feat:Hunger of the Grave' };
    expect(eligible(c, 'Hunger of the Grave', { ...ctx, total: 1 })).toHaveLength(0);
    expect(eligible(c, 'Hunger of the Grave', { ...ctx, source: 'action,spellcast' })).toHaveLength(0);
    const hit = eligible(c, 'Hunger of the Grave', ctx)[0];
    expect(runAction(c, hit.key, hit.automation, hit.trigger, hit.source))
      .toContain('Dealt 15 physical damage. One Shambling Corpse removed.');
    expect(c.resources!['summoner-circle-2']).toBe(2);
    const stress = c.stress.on;
    action(c, 'Grim Harvest').use();
    expect(c.resources!['summoner-circle-2']).toBe(3);
    expect(c.stress.on).toBe(stress);
    c.resources!['summoner-circle-1'] = 5;
    action(c, 'Grim Harvest').use();
    expect(c.resources!['summoner-circle-2']).toBe(3);
  });

  it('gates Ghost Evasion on the Third Circle and expends one Ghost only after Spectral Terror succeeds', () => {
    const c = summoner();
    expect(derivedStats(c).evasion).toBe(10);
    c.resources!['summoner-circle-3'] = 3;
    expect(derivedStats(c).evasion).toBe(11);
    const ctx = { ...attack, source: 'action,spellcast,feat:Spectral Terror' };
    expect(eligible(c, 'Spectral Terror', { ...ctx, total: 1 })).toHaveLength(0);
    const hit = eligible(c, 'Spectral Terror', ctx)[0];
    runAction(c, hit.key, hit.automation, hit.trigger, hit.source);
    expect(c.resources!['summoner-circle-3']).toBe(2);
    c.resources!['summoner-circle-3'] = 0;
    expect(derivedStats(c).evasion).toBe(10);
    expect(eligible(c, 'Spectral Terror', ctx)).toHaveLength(0);
  });

  it('uses the Fourth Circle Death Knight for one 2d12 bonus and consumes it on Severe protection', () => {
    const c = summoner();
    expect(eligible(c, 'Deathly Warrior')).toHaveLength(0);
    const bulwark = availableMitigations(c).find(m => m.source === 'Knightly Bulwark')!;
    expect(computeDamage(c, [bulwark], { damage: derivedStats(c).severe, type: 'physical', armor: false, selected: new Set([bulwark.id]) }).error)
      .toBe('Not enough Fourth Circle');
    c.resources!['summoner-circle-4'] = 1;
    expect(eligible(c, 'Deathly Warrior', { ...attack, total: 1 })).toHaveLength(0);
    expect(eligible(c, 'Deathly Warrior', { ...attack, hope: 2, fear: 10 })).toHaveLength(0);
    const trigger = eligible(c, 'Deathly Warrior')[0];
    expect(trigger.trigger.outcome).toBe('fear');
    expect(trigger.trigger.giveFear).toBe(1);
    runAction(c, trigger.key, trigger.automation, trigger.trigger, trigger.source);
    const bonus = availableRollOptions(c, ['damage']).find(o => o.source === 'Deathly Warrior damage')!;
    expect(bonus.option.dice).toBe('2d12');
    commitRollOptions(c, [bonus]);
    expect(c.effects.some(e => e.name === 'Deathly Warrior damage')).toBe(false);
    expect(c.resources!['summoner-circle-4']).toBe(1);
    const mitigation = availableMitigations(c).find(m => m.source === 'Knightly Bulwark')!;
    const stats = derivedStats(c);
    const result = computeDamage(c, [mitigation], {
      damage: stats.severe, type: 'physical', armor: false, selected: new Set([mitigation.id]),
    });
    expect(result.hp).toBe(2);
    applyDamage(c, result, 'physical');
    expect(c.resources!['summoner-circle-4']).toBe(0);
    expect(eligible(c, 'Deathly Warrior')).toHaveLength(0);
  });

  it('gates Angel advantage and upgrades separate magic damage only while an Archangel exists', () => {
    const c = summoner('theurgy');
    expect(availableRollOptions(c, ['action', 'trait:Presence'])).toHaveLength(0);
    c.resources!['summoner-circle-2'] = 2;
    expect(availableRollOptions(c, ['action', 'trait:Presence'])[0].option.advantage).toBe('advantage');
    expect(availableRollOptions(c, ['action', 'trait:Knowledge'])).toHaveLength(0);
    expect(eligible(c, 'Hopeful Strike').map(t => t.trigger.steps?.[0].effect?.rollOptions?.[0].dice)).toEqual(['1d10']);
    expect(actionBlocker(c, 'feat:Heavenly Aid', action(c, 'Heavenly Aid').auto, action(c, 'Heavenly Aid', 'help').definition)).toBeTruthy();
    c.resources!['summoner-circle-3'] = 1;
    expect(eligible(c, 'Hopeful Strike')).toHaveLength(1);
    const strike = eligible(c, 'Hopeful Strike')[0];
    expect(strike.trigger.damage).toBeUndefined();
    expect(strike.trigger.steps?.[0].effect?.rollOptions?.[0].dice).toBe('1d12');
    runAction(c, strike.key, strike.automation, strike.trigger, strike.source);
    expect(c.effects.find(e => e.name === 'Hopeful Strike damage')?.rollOptions?.[0].dice).toBe('1d12');
    expect(c.resources!['summoner-circle-2']).toBe(1);
    const hope = c.hope.on;
    const help = action(c, 'Heavenly Aid', 'help');
    expect(help.definition.sendRoll?.dice).toBe('1d6');
    help.use();
    expect(c.hope.on).toBe(hope);
    expect(c.resources!['summoner-circle-3']).toBe(0);
    expect(eligible(c, 'Hopeful Strike')[0].trigger.steps?.[0].effect?.rollOptions?.[0].dice).toBe('1d10');
    expect(eligible(c, 'Hopeful Strike', { ...attack, total: 1 })).toHaveLength(0);
  });

  it('offers Hallowed Hope as a host-resolved action and reaction roll option only while Extra Hope dice remain', () => {
    const c = summoner('theurgy');
    expect(c.features.find(f => f.name === 'Fourth Circle - Divine Manifestation')?.automation?.actions).toBeUndefined();
    const targets = [['action', 'attack'], ['action', 'spellcast'], ['reaction']];
    expect(availableRollOptions(c, ['action']).some(s => s.option.id === 'hallowed-hope')).toBe(false);
    c.resources!['summoner-hallowed-hope'] = 2;
    for (const t of targets) {
      const option = availableRollOptions(c, t).find(s => s.option.id === 'hallowed-hope');
      expect(option?.option.hostOwned).toBe(true);
      expect(option?.option.cost).toEqual({ 'summoner-hallowed-hope': 1 });
      expect(option?.option.extraHopeDie?.dismiss).toBe('summoner-circle-4');
    }
    expect(availableRollOptions(c, ['damage']).some(s => s.option.id === 'hallowed-hope')).toBe(false);
  });

  it.each(['necromancy', 'theurgy'])('preserves %s circles across rests and catalog refreshes', subclass => {
    let c = summoner(subclass);
    c.resources!['summoner-circle-1'] = 2;
    c.resources!['summoner-circle-3'] = 1;
    c.resources!['summoner-circle-4'] = 1;
    if (subclass === 'theurgy') c.resources!['summoner-hallowed-hope'] = 2;
    const before = structuredClone(c.resources);
    applyRest(c, 'short');
    applyRest(c, 'long');
    c = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    updateCharacterAutomation(c, [catalog]);
    syncResources(c);
    expect(c.resources).toEqual(before);
    expect(resourceDefinitions(c).every(r => !r.resetOn && !r.session)).toBe(true);
  });
});
