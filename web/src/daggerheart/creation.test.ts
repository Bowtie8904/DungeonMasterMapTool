import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { CompendiumEntry, describeAutomation } from './compendium';
import { emptyCharacter, normalizeSheet } from './data';
import { syncDerived } from './engine';
import { buildCharacter, creationAllowances, creationCardProblems, creationExperienceProblems, creationProblems, emptyChoices, RulesData, startingCards } from './rules';
import { applyTestingBuild } from './testing';

const feature = (name: string, creation: NonNullable<NonNullable<CompendiumEntry['automation']>['creation']>) => ({
  name, text: '', automation: { creation },
});
const data = (): RulesData => ({
  items: JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/items.json', import.meta.url), 'utf8')).entries,
  classes: [
    { id: 'class', name: 'Scholar', kind: 'class', domains: ['Arcana'], startingHp: 6, startingEvasion: 10,
      features: [feature('Unfamiliar class name', { hp: 1 })] },
    { id: 'subclass', name: 'Scholar: Knowledge', kind: 'subclass', parent: 'Scholar', stage: 'foundation',
      features: [feature('Extra learning', { domainCards: 1 })] },
  ],
  ancestries: [
    { id: 'a', name: 'First', features: [feature('First gift', { stress: 1 }), feature('Not inherited', { hp: 8, domainCards: 3 })] },
    { id: 'b', name: 'Second', features: [feature('Also not inherited', { hp: 8 }), feature('Second gift', { evasion: 2 })] },
  ],
  communities: [{ id: 'community', name: 'Town', automation: { creation: { stress: 1 } }, features: [feature('Breadth', { domains: ['Grace'] })] }],
  transformations: [{ id: 'form', name: 'Changed', features: [feature('New life', { hp: 1 })] }],
  cards: [
    { id: 'a1', name: 'Arcana one', domain: 'Arcana', level: 1 },
    { id: 'a2', name: 'Arcana two', domain: 'Arcana', level: 1 },
    { id: 'g1', name: 'Grace one', domain: 'Grace', level: 1 },
    { id: 'v1', name: 'Valor one', domain: 'Valor', level: 1 },
    { id: 'a3', name: 'Arcana higher', domain: 'Arcana', level: 2 },
  ],
  weapons: [{ id: 'weapon', name: 'Sword', weapon: { slot: 'primary', trait: 'Agility', range: 'Melee', damage: { dice: 'd6', bonus: 0, type: 'physical' }, burden: 'One-Handed' } }],
  armor: [{ id: 'armor', name: 'Leather', armor: { score: 2, major: 6, severe: 12 } }],
  consumables: [],
});

function choices() {
  return { ...emptyChoices(), className: 'Scholar', subclass: 'Scholar: Knowledge', ancestry: 'First', ancestry2: 'Second',
    community: 'Town', transformation: 'Changed', primary: 'Sword', armor: 'Leather', experiences: ['Reader', 'Traveler'],
    cards: ['Arcana one', 'Arcana two', 'Grace one'] };
}

describe('data-driven creation grants', () => {
  it('describes additive creation and resource automation in the compendium', () => {
    expect(describeAutomation({ creation: { hp: 1, stress: 1, evasion: 1, domainCards: 1, domains: ['Grace'] },
      resources: [{ id: 'favor', label: 'Favor', initial: 3, max: 6 }],
      rollOptions: [{ id: 'patron', label: 'Patron Die', roll: 'attack', cost: { favor: 1 } }],
      damage: { extraDice: 1, dropLowest: 1 },
    })).toEqual([
      'Favor: starts at 3, maximum 6', 'At creation: HP +1', 'At creation: STRESS +1', 'At creation: EVASION +1',
      'At creation: domain cards +1', 'At creation: additional domains Grace',
      'Optional attack bonus: Patron Die (cost: 1 favor)', 'Weapon damage: 1 extra dice, discard 1 lowest',
    ]);
  });

  it('combines class, foundation, selected mixed heritage, community and transformation grants', () => {
    const d = data(), ch = choices();
    expect(creationAllowances(d, ch)).toEqual({ hp: 2, stress: 2, evasion: 2, domainCards: 3, experienceBonus: 0, domains: ['Arcana', 'Grace'] });
    expect(creationProblems(d, ch)).toEqual([]);
    const c = buildCharacter(d, ch);
    expect(c.hp.slots).toBe(8);
    expect(c.stress.slots).toBe(8);
    expect(c.evasion).toBe(12);
    expect(c.loadout.map((e) => e.name)).toEqual(ch.cards);
    expect(c.domains).toEqual(['Arcana', 'Grace']);
    syncDerived(c);
    syncDerived(c);
    expect(c.hp.slots).toBe(8);
    expect(c.stress.slots).toBe(8);
    expect(c.evasion).toBe(12);
  });

  describe('permanent creation Experience bonuses', () => {
    function bonusData() {
      const d = data();
      d.ancestries[0].features![0] = feature('A custom creation feature', { experienceBonus: 1 });
      d.ancestries[0].features![1] = { name: 'Other feature', text: '' };
      return d;
    }

    it.each([undefined, -1, 2, 0.5, NaN])('requires a valid selected Experience index (%s)', (experienceBonusIndex) => {
      const d = bonusData(), ch = { ...choices(), experienceBonusIndex };
      expect(creationExperienceProblems(d, ch)).toContain('Choose an Experience for the permanent creation bonus.');
      expect(creationProblems(d, ch)).toContain('Choose an Experience for the permanent creation bonus.');
      expect(() => buildCharacter(d, ch)).toThrow('Choose an Experience');
    });

    it('applies the selected grant once, remembers it across draft revisits and reruns, and follows renames by index', () => {
      const d = bonusData(), ch = { ...choices(), experienceBonusIndex: 1 };
      const original = structuredClone(d);
      const c = buildCharacter(d, ch);
      expect(c.experiences).toEqual([{ name: 'Reader', mod: 2 }, { name: 'Traveler', mod: 3 }]);
      expect(c.mancer?.choices?.experienceBonusIndex).toBe(1);
      const revisited = structuredClone(c.mancer!.choices!);
      revisited.experiences[1] = '  Veteran traveler  ';
      const rebuilt = buildCharacter(d, revisited, c);
      expect(rebuilt.experiences).toEqual([{ name: 'Reader', mod: 2 }, { name: 'Veteran traveler', mod: 3 }]);
      const restored = normalizeSheet(JSON.parse(JSON.stringify(rebuilt)), rebuilt.name);
      syncDerived(restored);
      syncDerived(restored);
      expect(restored.experiences).toEqual(rebuilt.experiences);
      expect(restored.mancer?.choices?.experienceBonusIndex).toBe(1);
      expect(d).toEqual(original);
    });

    it('only inherits the chosen mixed ancestry feature and ignores the remembered selection without a grant', () => {
      const d = bonusData(), ch = { ...choices(), experienceBonusIndex: 0 };
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([3, 2]);
      ch.ancestry = 'Second';
      ch.ancestry2 = 'First';
      expect(creationAllowances(d, ch).experienceBonus).toBe(0);
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([2, 2]);
      ch.ancestry = 'First';
      ch.ancestry2 = '';
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([3, 2]);
    });

    it('aggregates grants from every supported creation source without inferring feature names', () => {
      const d = bonusData(), ch = { ...choices(), experienceBonusIndex: 0 };
      d.classes[0].automation = { creation: { experienceBonus: 1 } };
      d.communities[0].features!.push(feature('Another source', { experienceBonus: 1 }));
      d.transformations[0].automation = { creation: { experienceBonus: 1 } };
      expect(creationAllowances(d, ch).experienceBonus).toBe(4);
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([6, 2]);
      d.ancestries[0].features![0] = { name: 'Purposeful Design', text: 'Not an automated feature.' };
      expect(creationAllowances(d, ch).experienceBonus).toBe(3);
    });

    it('uses whole-ancestry bonuses only when unmixed and ignores invalid grant values', () => {
      const d = data(), ch = { ...choices(), experienceBonusIndex: 0 };
      d.ancestries[0].automation = { creation: { experienceBonus: 1 } };
      d.ancestries[0].features![1] = { name: 'Other feature', text: '' };
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([2, 2]);
      ch.ancestry2 = '';
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([3, 2]);
      for (const experienceBonus of [NaN, Infinity, -1, 0]) {
        d.ancestries[0].automation = { creation: { experienceBonus } };
        expect(creationAllowances(d, ch).experienceBonus).toBe(0);
        expect(creationExperienceProblems(d, ch)).toEqual([]);
      }
    });

    it('keeps duplicate names selectable by index and rejects a blank selected Experience', () => {
      const d = bonusData(), ch = { ...choices(), experiences: ['Same', 'Same'], experienceBonusIndex: 1 };
      expect(buildCharacter(d, ch).experiences.map((e) => e.mod)).toEqual([2, 3]);
      ch.experiences[1] = ' ';
      expect(creationExperienceProblems(d, ch)).toContain('Choose an Experience for the permanent creation bonus.');
      expect(() => buildCharacter(d, ch)).toThrow();
    });

    it('does not reapply new compendium creation metadata on a legacy sheet reload', () => {
      const c = buildCharacter(data(), choices());
      c.heritage[0].automation = { creation: { experienceBonus: 1 } };
      const restored = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
      syncDerived(restored);
      expect(restored.experiences.map((e) => e.mod)).toEqual([2, 2]);
    });

    it('gives new testing characters a stable first-Experience default and preserves it on refresh', () => {
      const d = bonusData();
      const c = Object.assign(emptyCharacter('Tester'), { testingCharacter: true });
      const b = { className: 'Scholar', subclass: 'Scholar: Knowledge', stage: 'foundation' as const, level: 1,
        ancestry: 'First', ancestry2: 'Second', community: 'Town', multiclasses: [] };
      applyTestingBuild(d, c, b);
      expect(c.experiences.map((e) => e.mod)).toEqual([3, 2]);
      c.experiences[0].mod = 5;
      applyTestingBuild(d, c, b);
      expect(c.experiences.map((e) => e.mod)).toEqual([5, 2]);
    });
  });

  it('updates card count and eligibility when selected sources change', () => {
    const d = data(), ch = choices();
    expect(startingCards(d, ch).map((e) => e.name)).toContain('Grace one');
    ch.community = '';
    expect(creationCardProblems(d, ch)).toContain('Choose only level 1 domain cards from your available domains.');
    ch.subclass = '';
    expect(creationAllowances(d, ch).domainCards).toBe(2);
    expect(creationCardProblems(d, ch)).toContain('Choose 2 domain cards.');
    expect(startingCards(d, 'Scholar').map((e) => e.name)).toEqual(['Arcana one', 'Arcana two']);
  });

  it('uses whole-ancestry automation only for an unmixed ancestry', () => {
    const d = data(), ch = choices();
    d.ancestries[0].automation = { creation: { hp: 2 } };
    expect(creationAllowances(d, ch).hp).toBe(2);
    ch.ancestry2 = '';
    expect(creationAllowances(d, ch).hp).toBe(12);
  });

  it('does not infer automation from names and applies entry-level transformation grants once', () => {
    const d = data(), ch = choices();
    d.ancestries[0].features![0] = { name: 'Endurance', text: 'Not an automated grant.' };
    d.transformations[0].automation = { creation: { stress: 1 } };
    const c = buildCharacter(d, ch);
    expect(c.hp.slots).toBe(8);
    expect(c.stress.slots).toBe(8);
    expect(c.heritage.filter((f) => f.name === 'Changed')).toHaveLength(1);
    expect(c.features.filter((f) => f.name === 'Changed')).toHaveLength(0);
  });

  it.each([
    ['duplicate', ['Arcana one', 'Arcana one', 'Grace one']],
    ['wrong domain', ['Arcana one', 'Arcana two', 'Valor one']],
    ['wrong level', ['Arcana one', 'Arcana two', 'Arcana higher']],
    ['missing', ['Arcana one', 'Arcana two', 'Missing']],
    ['wrong count', ['Arcana one', 'Arcana two']],
  ])('rejects %s card selections in validation and building', (_label, cards) => {
    const d = data(), ch = { ...choices(), cards };
    expect(creationCardProblems(d, ch).length).toBeGreaterThan(0);
    expect(() => buildCharacter(d, ch)).toThrow();
  });

  it('caps tracks, routes overflow cards to the vault and never mutates catalog automation', () => {
    const d = data(), ch = choices();
    d.classes[0].automation = { creation: { hp: 100, stress: 100, domainCards: 3 } };
    for (let i = 3; i <= 5; i++) d.cards.push({ id: `extra${i}`, name: `Extra ${i}`, level: 1, domain: 'Arcana' });
    ch.cards.push('Extra 3', 'Extra 4', 'Extra 5');
    const original = structuredClone(d);
    const c = buildCharacter(d, ch);
    expect(c.hp.slots).toBe(12);
    expect(c.stress.slots).toBe(12);
    expect(c.loadout).toHaveLength(5);
    expect(c.vault).toHaveLength(1);
    expect(d).toEqual(original);
  });

  it('does not reapply creation grants to stored sheets', () => {
    const c = emptyCharacter('Legacy');
    c.hp.slots = 9;
    c.stress.slots = 8;
    c.evasion = 13;
    c.heritage.push({ name: 'Previously applied', source: 'Ancestry', text: '', automation: { creation: { hp: 1, stress: 1, evasion: 1 } } });
    const restored = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    syncDerived(restored);
    expect([restored.hp.slots, restored.stress.slots, restored.evasion]).toEqual([9, 8, 13]);
  });
});
