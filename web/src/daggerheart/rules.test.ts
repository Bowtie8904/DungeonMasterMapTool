import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, LevelUpRecord, multiclassesOf, normalizeSheet, TRAIT_ORDER } from './data';
import { plain } from './rich';
import { derivedStats, statBreakdown } from './engine';
import { CLASS_GUIDES, GENERIC_GUIDE } from './guide';
import {
  advancementLeft, applyLevelUp, buildCharacter, classEntries, creationProblems, emptyChoices, guideFor, levelUpCards, levelUpProblems, RulesData,
  startingCards, subclassesOf, suggestedTraits, setTransformation, starterGear, tier1, creationAllowances,
  creationCompanionRules, subclassCard,
} from './rules';

const load = (f: string) => JSON.parse(readFileSync(resolve(__dirname, '../../../dmmap-daggerheart', f), 'utf8')).entries;
const data: RulesData = {
  classes: load('classes.json'), ancestries: load('ancestries.json'), communities: load('communities.json'), weapons: load('weapons.json'),
  armor: load('armor.json'), consumables: load('consumables.json'), cards: load('domain-cards.json'), transformations: load('transformations.json'),
  items: load('items.json'),
};

function choicesFor(className: string) {
  const g = guideFor(className);
  const ch = emptyChoices();
  ch.className = className;
  ch.subclass = subclassesOf(data, className)[0];
  ch.element = subclassCard(data, ch.subclass, 'foundation')?.features
    ?.flatMap(f => f.automation?.elementChoice ?? [])[0];
  if (creationCompanionRules(data, ch)) ch.companion = { name: 'Ash', animal: 'Wolf', attack: 'Bite', damageType: 'phys', experiences: ['Scout', 'Loyal'] };
  ch.ancestry = 'Human';
  ch.community = data.communities[0].name;
  ch.traits = suggestedTraits(g);
  ch.primary = g.primary || starterGear(data.weapons).find(e => e.weapon?.slot === 'primary' && e.weapon.burden === 'One-Handed')!.name;
  ch.secondary = g.secondary;
  ch.armor = g.armor || starterGear(data.armor)[0].name;
  ch.experiences = ['Tavern brawler', 'Bookworm'];
  ch.cards = startingCards(data, ch).slice(0, creationAllowances(data, ch).domainCards).map((e) => e.name);
  ch.name = 'Test';
  return ch;
}

describe('character mancer rules', () => {
  it('grants compendium-backed supplies and every standard class keepsake', () => {
    for (const [className, guide] of Object.entries(CLASS_GUIDES)) {
      for (const classItem of guide.classItems) {
        const ch = { ...choicesFor(className), classItem };
        const c = buildCharacter(data, ch);
        for (const name of ['Torch', '50 feet of rope', 'Basic supplies', classItem]) {
          const entry = data.items.find(e => e.name === name)!;
          expect(entry, name).toBeDefined();
          expect(c.inventory.find(i => i.name === name)).toMatchObject({ name, qty: 1, type: 'gear', note: entry.text });
        }
        expect(c.gold).toBe(10);
        expect(c.inventory.filter(i => i.type === 'consumable')).toHaveLength(1);
      }
    }
    expect(data.items.some(e => e.name === GENERIC_GUIDE.classItems[0])).toBe(true);
  });

  it('covers every SRD class guide and preserves the Blood Hunter fallback', () => {
    for (const name of ['Assassin', 'Bard', 'Brawler', 'Druid', 'Guardian', 'Ranger', 'Rogue', 'Seraph', 'Sorcerer', 'Warlock', 'Warrior', 'Witch', 'Wizard']) {
      expect(CLASS_GUIDES[name], name).toBeDefined();
      expect(CLASS_GUIDES[name].classItems, name).toHaveLength(2);
      expect(CLASS_GUIDES[name].background, name).toHaveLength(3);
      expect(CLASS_GUIDES[name].connections, name).toHaveLength(3);
    }
    expect(guideFor('Bard')).toEqual(CLASS_GUIDES.Bard);
    expect(guideFor('Blood Hunter')).toBe(GENERIC_GUIDE);
    const typed = buildCharacter(data, { ...choicesFor('Warlock'), classItem: 'A ring you can’t remove' });
    expect(typed.inventory.find(i => i.name === "a ring you can't remove")).toMatchObject({ type: 'gear', qty: 1 });
  });

  it('applies the supplied suggested traits and creates their catalog-backed equipment', () => {
    const cases = [
      { name: 'Assassin', traits: [2, -1, 1, 0, 0, 1], primary: 'Katana', armor: 'Brigandine Armor' },
      { name: 'Brawler', traits: [1, 1, 0, 2, 0, -1], primary: "Brawler's Strike", armor: 'Brigandine Armor' },
      { name: 'Warlock', traits: [1, -1, 0, 1, 2, 0], primary: 'Scimitar', secondary: 'Fighting Cloak', armor: 'Mage Robes' },
      { name: 'Witch', traits: [0, -1, 0, 2, 1, 1], primary: 'Casting Dagger', secondary: 'Rune Shield', armor: 'Mage Robes' },
    ];
    for (const expected of cases) {
      const guide = guideFor(expected.name);
      expect(guide).toMatchObject(expected.secondary ? { traits: expected.traits, primary: expected.primary, secondary: expected.secondary, armor: expected.armor }
        : { traits: expected.traits, primary: expected.primary, armor: expected.armor });
      const ch = choicesFor(expected.name);
      expect(creationProblems(data, ch), expected.name).toEqual([]);
      const c = buildCharacter(data, ch);
      expect(c.traits.map(t => t.mod), expected.name).toEqual(expected.traits);
      expect(c.activeWeapons.map(w => w.name), expected.name).toEqual([expected.primary, ...(expected.secondary ? [expected.secondary] : [])]);
      expect(c.activeArmor?.name).toBe(expected.armor);
      if (expected.name === 'Brawler') {
        expect(c.activeWeapons[0]).toMatchObject({ generated: 'brawler', trait: 'Instinct', range: 'Melee', damage: 'd8+d6', damageType: 'phys' });
        expect(derivedStats(c).evasion).toBe(c.evasion + 1);
      } else {
        expect(c.activeWeapons[0].automation).toEqual(data.weapons.find(e => e.name === expected.primary)?.automation);
      }
      if (expected.secondary) expect(c.activeWeapons[1].automation).toEqual(data.weapons.find(e => e.name === expected.secondary)?.automation);
      expect(c.activeArmor?.automation).toEqual(data.armor.find(e => e.name === expected.armor)?.automation);
    }
  });

  it('uses the supplied equipment statistics and feature automation', () => {
    const weapon = (name: string) => data.weapons.find(e => e.name === name)!;
    expect(weapon('Katana')).toMatchObject({
      starter: true, weapon: { trait: 'Agility', range: 'Melee', damage: { dice: '1d10', bonus: 3, type: 'physical' }, burden: 'Two-Handed' },
      feature: { name: 'Quick' }, automation: { rollOptions: [{ cost: { stress: 1 } }] },
    });
    expect(weapon('Scimitar')).toMatchObject({
      starter: true, weapon: { trait: 'Presence', range: 'Melee', damage: { dice: '1d8', bonus: 1, type: 'physical' }, burden: 'One-Handed' },
    });
    expect(weapon('Fighting Cloak')).toMatchObject({
      starter: true, weapon: { slot: 'secondary', trait: 'Presence', range: 'Melee', damage: { dice: '1d4', type: 'physical' }, burden: 'One-Handed' },
      feature: { name: 'Padded' }, automation: { modifiers: [{ stat: 'thresholds', value: 2 }] },
    });
    expect(weapon('Casting Dagger')).toMatchObject({
      starter: true, weapon: { trait: 'Instinct', range: 'Close', damage: { dice: '1d6', type: 'magic' }, burden: 'One-Handed' },
      feature: { name: 'Versatile' }, automation: { alternateProfiles: [{ trait: 'Instinct', range: 'Melee', damage: '1d8' }] },
    });
    expect(weapon('Rune Shield')).toMatchObject({
      starter: true, weapon: { slot: 'secondary', trait: 'Knowledge', range: 'Melee', damage: { dice: '1d4', type: 'magic' }, burden: 'One-Handed' },
      feature: { name: 'Protective' }, automation: { modifiers: [{ stat: 'armorScore', value: 1 }] },
    });
    expect(data.armor.find(e => e.name === 'Brigandine Armor')).toMatchObject({
      starter: true, armor: { major: 6, severe: 12, score: 3 }, feature: { name: 'Lined' },
      automation: { mitigation: [{ cost: { stress: 1 }, negate: true }] },
    });
    expect(data.armor.find(e => e.name === 'Mage Robes')).toMatchObject({
      starter: true, armor: { major: 4, severe: 10, score: 2 }, feature: { name: 'Enchanted' },
      automation: { modifiers: [{ stat: 'thresholds', value: '@spellcast' }] },
    });
  });

  it('only accepts the generated unarmed option for its class and without a secondary weapon', () => {
    const ch = choicesFor('Brawler');
    ch.secondary = 'Fighting Cloak';
    expect(creationProblems(data, ch)).toContain("Brawler's Strike requires no other active weapons.");
    expect(() => buildCharacter(data, ch)).toThrow('requires no other active weapons');
    const bard = { ...choicesFor('Bard'), primary: "Brawler's Strike" };
    expect(creationProblems(data, bard)).toContain('Choose a primary weapon.');
    const ordinary = { ...choicesFor('Brawler'), primary: 'Scimitar', secondary: 'Fighting Cloak' };
    expect(creationProblems(data, ordinary)).toEqual([]);
    expect(buildCharacter(data, ordinary).activeWeapons.map(w => w.name)).toEqual(['Scimitar', 'Fighting Cloak']);
  });

  it('uses edited catalog descriptions and isolated automation, but preserves custom keepsakes', () => {
    const items = structuredClone(data.items);
    const torch = items.find(e => e.name === 'Torch')!;
    torch.text = 'Host-customized torch';
    torch.automation = { uses: { max: 2, per: 'session' } };
    const ch = { ...choicesFor('Bard'), classItem: 'My handmade keepsake' };
    const first = buildCharacter({ ...data, items }, ch);
    const second = buildCharacter({ ...data, items }, ch);
    const granted = first.inventory.find(i => i.name === 'Torch')!;
    expect(granted.note).toBe(torch.text);
    expect(granted.automation).toEqual(torch.automation);
    granted.automation!.uses!.max = 9;
    expect(torch.automation.uses!.max).toBe(2);
    expect(second.inventory.find(i => i.name === 'Torch')!.automation!.uses!.max).toBe(2);
    expect(first.inventory.find(i => i.name === ch.classItem)).toEqual({ name: ch.classItem, type: 'class item', qty: 1 });
    expect(() => buildCharacter({ ...data, items: items.filter(e => e.name !== 'Torch') }, ch)).toThrow('Ask the DM to restore it');
    expect(creationProblems({ ...data, items: items.filter(e => e.name !== 'Torch') }, ch).join(' ')).toContain('Ask the DM to restore it');
    expect(() => buildCharacter({ ...data, items: [...items, torch] }, ch)).toThrow('exactly one entry');
  });

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
    expect(starterGear(data.armor)).toEqual(data.armor.filter((e) => e.starter));
    const weapons = starterGear(data.weapons);
    expect(weapons).toEqual(data.weapons.filter((e) => e.starter));
    expect(weapons.every((e) => (e.tier ?? 1) === 1)).toBe(true);
    expect(weapons.some((e) => e.weapon?.slot === 'secondary')).toBe(true);
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
