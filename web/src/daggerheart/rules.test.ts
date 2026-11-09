import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, LevelUpRecord, multiclassesOf, normalizeSheet, TRAIT_ORDER } from './data';
import { plain } from './rich';
import { derivedStats, statBreakdown } from './engine';
import { CLASS_GUIDES } from './guide';
import {
  advancementLeft, applyLevelUp, buildCharacter, classEntries, creationProblems, emptyChoices, levelUpCards, levelUpProblems, RulesData,
  startingCards, subclassesOf, suggestedTraits, setTransformation, starterGear, tier1, creationAllowances,
  creationCompanionRules, subclassCard,
} from './rules';

const load = (f: string) => JSON.parse(readFileSync(resolve(__dirname, '../../../dmmap-daggerheart', f), 'utf8')).entries;
const data: RulesData = {
  classes: load('classes.json'), ancestries: load('ancestries.json'), communities: load('communities.json'), weapons: load('weapons.json'),
  armor: load('armor.json'), consumables: load('consumables.json'), cards: load('domain-cards.json'), transformations: load('transformations.json'),
};

function choicesFor(className: string) {
  const g = CLASS_GUIDES[className];
  const ch = emptyChoices();
  ch.className = className;
  ch.subclass = subclassesOf(data, className)[0];
  ch.element = subclassCard(data, ch.subclass, 'foundation')?.features
    ?.flatMap(f => f.automation?.elementChoice ?? [])[0];
  if (creationCompanionRules(data, ch)) ch.companion = { name: 'Ash', animal: 'Wolf', attack: 'Bite', damageType: 'phys', experiences: ['Scout', 'Loyal'] };
  ch.ancestry = 'Human';
  ch.community = data.communities[0].name;
  ch.traits = suggestedTraits(g);
  ch.primary = g.primary;
  ch.armor = g.armor;
  ch.experiences = ['Tavern brawler', 'Bookworm'];
  ch.cards = startingCards(data, ch).slice(0, creationAllowances(data, ch).domainCards).map((e) => e.name);
  ch.name = 'Test';
  return ch;
}

describe('character mancer rules', () => {
  it('builds valid level 1 characters for the SRD classes', () => {
    for (const name of Object.keys(CLASS_GUIDES)) {
      const ch = choicesFor(name);
      expect(creationProblems(data, ch), name).toEqual([]);
      const c = buildCharacter(data, ch);
      expect(c.level).toBe(1);
      expect(c.mancer?.done).toBe(true);
      expect(c.loadout).toHaveLength(creationAllowances(data, ch).domainCards);
      expect(c.hp.slots).toBeGreaterThan(0);
      expect(c.activeArmor).toBeTruthy();
      expect(c.traits.map((t) => t.mod).sort()).toEqual([-1, 0, 0, 1, 1, 2]);
      expect(derivedStats(c).evasion).toBeGreaterThan(0);
    }
  });

  it('applies ancestry creation bonuses', () => {
    const ch = choicesFor('Warrior');
    const base = buildCharacter(data, ch);
    ch.ancestry = 'Giant';
    expect(buildCharacter(data, ch).hp.slots).toBe(base.hp.slots + 1);
    ch.ancestry = 'Human';
    expect(buildCharacter(data, ch).stress.slots).toBe(7);
    ch.ancestry = 'Earthkin';
    expect(derivedStats(buildCharacter(data, ch)).armorSlots).toBe(derivedStats(base).armorSlots + 1);
  });

  it('gives at most one transformation, as heritage and not as a domain card', () => {
    const ch = choicesFor('Warrior');
    ch.transformation = 'Ghost';
    const c = buildCharacter(data, ch);
    expect(c.transformation).toBe('Ghost');
    expect(c.heritage.filter((f) => f.source === 'Transformation - Ghost')).toHaveLength(2);
    expect(c.loadout).toHaveLength(2);
    setTransformation(data, c, 'Demigod');
    expect(c.heritage.some((f) => f.source.includes('Ghost'))).toBe(false);
    expect(c.heritage.filter((f) => f.source.startsWith('Transformation - '))).toHaveLength(2);
    expect(c.mancer?.choices?.transformation).toBe('Demigod');
    setTransformation(data, c, '');
    expect(c.transformation).toBeUndefined();
    expect(c.heritage.some((f) => f.source.startsWith('Transformation - '))).toBe(false);
  });

  it('keeps the player notes when rebuilding', () => {
    const prev = emptyCharacter('x');
    prev.notes = 'keep me';
    expect(buildCharacter(data, choicesFor('Rogue'), prev).notes).toBe('keep me');
  });

  it('rejects incomplete choices', () => {
    const ch = choicesFor('Bard');
    ch.traits = { ...ch.traits, Agility: 2, Strength: 2 };
    expect(creationProblems(data, ch)).not.toEqual([]);
  });

  it('offers only the class domains at creation', () => {
    const cls = classEntries(data).find((e) => e.name === 'Wizard')!;
    expect(startingCards(data, 'Wizard').every((e) => cls.domains!.includes(e.domain!))).toBe(true);
    expect(tier1(data.weapons).length).toBeGreaterThan(20);
  });

  it('offers only the flagged basic gear', () => {
    expect(starterGear(data.armor).map((e) => e.name).sort()).toEqual(['Chainmail Armor', 'Full Plate Armor', 'Gambeson Armor', 'Leather Armor']);
    const weapons = starterGear(data.weapons);
    expect(weapons.every((e) => e.starter)).toBe(true);
    expect(weapons.some((e) => e.weapon?.slot === 'secondary')).toBe(true);
    expect(weapons.map((e) => e.name)).not.toContain('Katana');
  });

  it('lists the sources of derived stats', () => {
    const c = buildCharacter(data, choicesFor('Warrior'));
    const s = statBreakdown(c);
    expect(s.major.length).toBeGreaterThan(0);
    expect(s.armor.length).toBeGreaterThan(0);
  });

  it('strips the light markdown of the compendium', () => {
    expect(plain('**Bold** and _it_')).toBe('Bold and it');
  });

  it('normalizes stored sheets', () => {
    expect(normalizeSheet({ name: 'Old' }, 'Old').mancer?.done).toBe(true);
    expect(TRAIT_ORDER).toHaveLength(6);
  });
});

describe('level up rules', () => {
  const rec = (level: number, advancements: LevelUpRecord['advancements'], extra: Partial<LevelUpRecord> = {}): LevelUpRecord => ({
    level, advancements, ...extra,
  });

  it('levels 1 to 10 with remembered choices', () => {
    const c = buildCharacter(data, choicesFor('Warrior'));
    const card = (lvl: number) => levelUpCards(data, c, lvl)[0]?.name;
    const step = (level: number, adv: LevelUpRecord['advancements'], extra: Partial<LevelUpRecord> = {}) => {
      const r = rec(level, adv, { card: card(level), ...extra });
      expect(levelUpProblems(data, c, r), `level ${level}`).toEqual([]);
      applyLevelUp(data, c, r);
    };
    step(2, [{ type: 'hp' }, { type: 'evasion' }], { experience: 'Second wind' });
    expect(c.level).toBe(2);
    expect(c.experiences).toHaveLength(3);
    expect(c.proficiency.on).toBe(2);
    expect(c.hp.slots).toBe(buildCharacter(data, choicesFor('Warrior')).hp.slots + 1);
    step(3, [{ type: 'traits', traits: ['Agility', 'Strength'] }, { type: 'stress' }]);
    expect(c.traits.find((t) => t.name === 'Agility')!.marked).toBe(true);
    step(4, [{ type: 'experiences', experiences: [0, 1] }, { type: 'card', card: levelUpCards(data, c, 4)[1].name }]);
    step(5, [{ type: 'subclass' }, { type: 'hp' }], { experience: 'Veteran' });
    expect(c.traits.every((t) => !t.marked)).toBe(true);
    expect(c.proficiency.on).toBe(3);
    expect(c.levelUps).toHaveLength(4);
    expect(c.features.some((f) => f.source.startsWith('Subclass specialization'))).toBe(true);
  });

  it('rejects wrong point totals and used-up options', () => {
    const c = buildCharacter(data, choicesFor('Wizard'));
    expect(levelUpProblems(data, c, rec(2, [{ type: 'hp' }]))).toContain('Choose advancements worth two points.');
    expect(advancementLeft(c, 'proficiency', 2)).toBe(0);
    expect(advancementLeft(c, 'multiclass', 4)).toBe(0);
    expect(advancementLeft(c, 'multiclass', 5)).toBe(1);
  });

  it('supports multiclassing', () => {
    const c = buildCharacter(data, choicesFor('Warrior'));
    c.level = 4;
    c.experiences.push({ name: 'a', mod: 2 });
    const sub = subclassesOf(data, 'Bard')[0];
    const domain = data.classes.find((e) => e.name === 'Bard' && e.kind === 'class')!.domains![0];
        const r = rec(5, [{ type: 'multiclass', multiclass: { className: 'Bard', domain, subclass: sub } }], { experience: 'x', card: levelUpCards(data, c, 5)[0].name });
    expect(levelUpProblems(data, c, r)).toEqual([]);
    applyLevelUp(data, c, r);
    expect(c.domains).toContain(domain);
    expect(multiclassesOf(c).map((m) => m.className)).toEqual(['Bard']);
  });
});
