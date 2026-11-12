import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { recreateDemiplane, DemiplaneCharacter, DemiplaneSelection } from './demiplane';
import { derivedStats } from './engine';
import { RulesData, subclassStageOf } from './rules';

const load = (name: string) => JSON.parse(readFileSync(resolve(__dirname, '../../../dmmap-daggerheart', `${name}.json`), 'utf8')).entries;
const data: RulesData = {
  classes: load('classes'), ancestries: load('ancestries'), communities: load('communities'), weapons: load('weapons'),
  armor: load('armor'), consumables: load('consumables'), cards: load('domain-cards'), transformations: load('transformations'),
  items: load('items'),
};
const items = load('items');
function fixture(level = 10): DemiplaneCharacter {
  const engines: DemiplaneSelection[] = [];
  const add = (name: string, key: string, args: Record<string, unknown> = {}, value?: unknown) => engines.push({ name, key, args, value });
  const field = (name: string, value: unknown, parentEngine?: string) => add(name, name, { parentEngine }, value);
  add('core/character.eng', 'root');
  add('tabula/class/bard.eng', 'class', { builderSection: 'class' });
  add('tabula/subclass/wordsmith-foundation.eng', 'sub', { name: 'Wordsmith', sourceRow: 'class-foundation' });
  add('tabula/ancestry/elf.eng', 'ancestry', { name: 'Elf' });
  add('tabula/community/slyborne.eng', 'community', { name: 'Slyborne' });
  field('character_name', 'Imported Hero');
  field('character_level', level);
  field('trait-selection-value-2', 'presence');
  field('trait-selection-value-1', 'finesse');
  field('trait-selection-value-1-second', 'instinct');
  field('trait-selection-value-neg-1', 'strength');
  field('character_experience_0_name', 'Performer');
  field('character_experience_1_name', 'Diplomat');
  field('character_experience_2_name', 'Traveler');
  field('character_experience_3_name', 'Loremaster');
  field('character_experience_4_name', 'Hero');
  const card = (name: string, sourceRow: string, parentEngine = 'class', builderSection = 'level-up') =>
    add(`tabula/domain/${name.toLowerCase().replaceAll(' ', '-')}.eng`, name, { name, sourceRow, parentEngine, builderSection });
  card('Deft Deceiver', 'class-domain-deck-cards', 'class', 'class');
  card('Book of Tyfar', 'class-domain-deck-cards', 'class', 'class');
  const option = (n: number, name: string, suffix = '') => {
    const key = `level-${n}-${name}${suffix}`;
    add('core/selection/level-up/generic-option/index.eng', key, { name, level: n, sourceRow: `level-${n}-level-option` });
    return key;
  };
  const traits = (n: number, a: string, b: string) => {
    const key = option(n, 'Increase Traits');
    for (const t of [a, b]) field(`character_trait_${t}_value--${key}`, 1, key);
    field(`${key}-level-up-option-increase-traits-current`, 2, key);
  };
  const regular = ['', '', 'Book of Ava', 'Book of Norai', 'Book of Exota', 'Teleport', 'Banish',
    'Endless Charisma', 'Astral Projection', 'Disintegration Wave', 'Notorious'];
  for (let n = 2; n <= level; n++) {
    card(regular[n], `level-${n}-domain-card`);
    if (n === 2 || n === 7 || n === 10) traits(n, 'presence', 'instinct');
    if (n === 4) traits(n, 'agility', 'strength');
    if (n === 2 || n === 5 || n === 8) {
      const key = option(n, 'Choose Domain Card');
      card({ 2: 'Book of Sitil', 5: 'Words of Discord', 8: 'Book of Vyola' }[n]!, `${key}-choose-domain`, key);
    }
    if (n === 3) { option(n, 'Gain HP Slot'); option(n, 'Gain HP Slot', '-second'); }
    if (n === 4 || n === 10) option(n, 'Increase Evasion');
    if (n === 5 || n === 8) option(n, 'Upgrade Subclass');
    if (n === 6 || n === 9) option(n, 'Increase Proficiency');
    if (n === 7) option(n, 'Gain HP Slot');
  }
  for (const [name, slug, key] of [['Quarterstaff', 'quarterstaff', 'staff'], ['Rapier', 'rapier', 'rapier'],
    ['Gambeson Armor', 'gambeson-armor', 'armor'], ['Minor Health Potion', 'minor-health-potion-playtest', 'potion']]) {
    add('core/selection/equipment/starting-class/index.eng', `${key}-wrapper`, { name });
    add(`tabula/equipment/${slug}.eng`, key, { parentEngine: `${key}-wrapper` });
  }
  add('core/selection/equipment/custom/index.eng', 'torch', { customName: 'Torch' });
  field('character_hand_primary_equipped-id', 'rapier');
  field('character_hand_secondary_equipped-id', 'na');
  field('character_hand_both_equipped-id', 'na');
  field('armor-is-equipped', 1);
  field('character_health_current', 2);
  field('character_stress_current', 1);
  field('character_hope_current', 3);
  field('character_armor_slots_current', 1);
  field('character_gold_playtest_handfuls_current', 3);
  field('character_gold_playtest_bags_current', 1);
  field('character_gold_playtest_chests_current', 1);
  return { name: 'Imported Hero', level, summary: { class: 'Bard', subclass: 'Wordsmith' }, engines };
}

describe('Demiplane reconstruction', () => {
  it('uses suggested class traits and then replays level-up increases, ignoring inactive manual choices', () => {
    const source = fixture(2);
    source.engines.push({ name: 'use-suggested-traits', key: 'suggested', args: { parentEngine: 'class' }, value: 1 });
    const { character: c, issues } = recreateDemiplane(source, data, items);
    expect(c.traits.map(t => t.mod)).toEqual([0, -1, 1, 1, 3, 1]);
    expect(issues.some(issue => issue.includes('use-suggested-traits'))).toBe(false);
    source.engines = source.engines.filter(e => !e.name.startsWith('trait-selection-value'));
    expect(recreateDemiplane(source, data, items).character.traits).toEqual(c.traits);
    source.engines.find(e => e.key === 'suggested')!.value = 0;
    expect(() => recreateDemiplane(source, data, items)).toThrow('trait-selection-value-2');
  });

  it('imports the linked Brawler suggested traits and saved Martial Artist forms', () => {
    const source = fixture(1);
    source.engines = source.engines.filter(e => !e.name.startsWith('trait-selection-value'));
    Object.assign(source.engines.find(e => e.key === 'class')!, { name: 'tabula/class/brawler.eng' });
    Object.assign(source.engines.find(e => e.key === 'sub')!.args, { name: 'Martial Artist' });
    Object.assign(source.engines.find(e => e.key === 'Deft Deceiver')!.args, { name: 'Bare Bones' });
    Object.assign(source.engines.find(e => e.key === 'Book of Tyfar')!.args, { name: 'Forceful Push' });
    source.engines.push({ name: 'use-suggested-traits', key: 'suggested', args: { parentEngine: 'class' }, value: 1 });
    for (const name of ['Quick', 'Favored']) source.engines.push({
      name: `tabula/unique-element/${name.toLowerCase()}.eng`, key: name,
      args: { name, parentEngine: 'sub', sharedSelectionPoolID: 'martial-artist-form' },
    });
    const c = recreateDemiplane(source, data, items).character;
    expect(c.className).toBe('Brawler');
    expect(c.traits.map(t => t.mod)).toEqual([1, 1, 0, 2, 0, -1]);
    expect(c.knownStances).toEqual(['Quick', 'Favored']);
    expect(c.mancer?.done).toBe(true);
    source.engines.find(e => e.key === 'Quick')!.args.name = 'Unknown form';
    expect(() => recreateDemiplane(source, data, items)).toThrow('Unknown form');
    source.engines = source.engines.filter(e => e.args.sharedSelectionPoolID !== 'martial-artist-form');
    expect(() => recreateDemiplane(source, data, items)).toThrow('Choose 2');
  });

  it('never substitutes generic suggested traits for an unsupported class', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    const bard = classes.find(e => e.kind === 'class' && e.name === 'Bard')!;
    bard.name = 'Homebrew';
    classes.filter(e => e.parent === 'Bard').forEach(e => { e.parent = 'Homebrew'; });
    source.engines.find(e => e.key === 'class')!.args.name = 'Homebrew';
    source.engines.push({ name: 'use-suggested-traits', key: 'suggested', args: {}, value: 1 });
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow('suggested allocation for "Homebrew"');
  });

  it('replays the complete level 10 build, named cards, features, equipment and resources', () => {
    const { character: c, issues } = recreateDemiplane(fixture(), data, items);
    expect(c.mancer?.done).toBe(true);
    expect(c.testingCharacter).not.toBe(true);
    expect(c.level).toBe(10);
    expect(c.className).toBe('Bard');
    expect(c.subclass).toBe('Wordsmith');
    expect(subclassStageOf(c)).toBe('mastery');
    expect(c.levelUps).toHaveLength(9);
    expect(c.levelUps![0].advancements).toEqual([{ type: 'traits', traits: ['Presence', 'Instinct'] }, { type: 'card', card: 'Book of Sitil' }]);
    expect(c.levelUps![1].advancements).toEqual([{ type: 'hp' }, { type: 'hp' }]);
    expect(c.hp.slots).toBe(8);
    expect(c.hp.on).toBe(2);
    expect(c.stress.on).toBe(1);
    expect(c.hope.on).toBe(3);
    expect(c.proficiency.on).toBe(6);
    expect(c.traits.find(t => t.name === 'Presence')?.mod).toBe(5);
    expect(c.traits.find(t => t.name === 'Agility')?.mod).toBe(1);
    expect(c.experiences).toHaveLength(5);
    expect(c.features.filter(f => f.source.startsWith('Subclass mastery'))).not.toHaveLength(0);
    expect(c.heritage.some(f => f.source.startsWith('Community - Slyborne'))).toBe(true);
    expect([...c.loadout, ...c.vault]).toHaveLength(14);
    expect(c.loadout).toHaveLength(5);
    expect(c.vault.some(card => card.name === 'Notorious')).toBe(true);
    expect(c.activeWeapons.map(w => w.name)).toEqual(['Rapier']);
    expect(c.carriedWeapons.map(w => w.name)).toEqual(['Quarterstaff']);
    expect(c.activeArmor?.name).toBe('Gambeson Armor');
    expect(c.inventory.find(item => item.name === 'Minor Health Potion')?.automation).toBeTruthy();
    expect(c.gold).toBe(1130);
    expect(derivedStats(c).evasion).toBeGreaterThan(c.evasion);
    expect(issues.filter(issue => /Level \d+:|Not transferred:/.test(issue))).toEqual([]);
    expect(issues.some(issue => issue.includes('loadout'))).toBe(true);
  });

  it('normalizes names but never chooses an ambiguous or missing core identity', () => {
    const source = fixture(1);
    source.engines.find(e => e.key === 'ancestry')!.args.name = '  ELF ';
    expect(recreateDemiplane(source, data, items).character.ancestry).toBe('Elf');
    expect(() => recreateDemiplane(source, { ...data, ancestries: [...data.ancestries, data.ancestries.find(e => e.name === 'Elf')!] }, items))
      .toThrow('matches multiple');
    source.engines.find(e => e.key === 'ancestry')!.args.name = 'Homebrew elf';
    expect(() => recreateDemiplane(source, data, items)).toThrow('Nothing was imported');
  });

  it('replays Experience increases using saved per-advancement Experience indices', () => {
    const source = fixture(3);
    source.engines = source.engines.filter(e => !e.key.startsWith('level-3-Gain HP Slot'));
    source.engines.push({ name: 'core/selection/level-up/generic-option/index.eng', key: 'experiences',
      args: { name: 'Increase Experiences', level: 3 } });
    for (const index of [0, 2]) source.engines.push({
      name: `character_experience_${index}_value--experiences`, key: `experience-${index}`,
      args: { parentEngine: 'experiences' }, value: 1,
    });
    source.engines.push({ name: 'core/selection/level-up/generic-option/index.eng', key: 'stress',
      args: { name: 'Gain Stress Slot', level: 3 } });
    const { character: c, issues } = recreateDemiplane(source, data, items);
    expect(c.experiences.map(e => e.mod)).toEqual([3, 2, 3]);
    expect(c.stress.slots).toBe(7);
    expect(c.levelUps![1].advancements[0]).toEqual({ type: 'experiences', experiences: [0, 2] });
    expect(issues.some(issue => issue.includes('Increase Experiences'))).toBe(false);
  });

  it('matches multiclass class, domain and foundation by name without changing the original class', () => {
    const source = fixture(5);
    source.engines = source.engines.filter(e => !e.key.startsWith('level-5-') && e.key !== 'Words of Discord');
    source.engines.push(
      { name: 'core/selection/level-up/generic-option/index.eng', key: 'multi', args: { name: 'Multiclass', level: 5 } },
      { name: 'tabula/class/ranger.eng', key: 'ranger', args: { name: 'Ranger', slug: 'ranger', multiclassed: '1', parentEngine: 'multi' } },
      { name: 'tabula/subclass/wayfinder-foundation.eng', key: 'wayfinder',
        args: { name: 'Wayfinder', parentEngine: 'ranger', sourceRow: 'ranger-multiclass-subclass-choice' } },
      { name: 'core/selection/level-up/extra-domain/index.eng', key: 'sage',
        args: { name: 'Sage', parentEngine: 'ranger', sourceRow: 'ranger-multiclass-domain-choice' } },
    );
    const { character: c, issues } = recreateDemiplane(source, data, items);
    expect(c.className).toBe('Bard');
    expect(c.domains).toContain('Sage');
    expect(c.levelUps![3].advancements).toEqual([{ type: 'multiclass',
      multiclass: { className: 'Ranger', subclass: 'Ranger: Wayfinder', domain: 'Sage' } }]);
    expect(c.features.some(f => f.source.startsWith('Multiclass Ranger - Wayfinder foundation'))).toBe(true);
    expect(issues.some(issue => /multiclass|Not transferred.*ranger|Not transferred.*sage/i.test(issue))).toBe(false);
  });

  it('describes unmatched cards/items, unsupported advancements/state and limits without guessing', () => {
    const source = fixture(3);
    source.engines.find(e => e.key === 'Book of Norai')!.args.name = 'Unknown domain';
    source.engines.find(e => e.key === 'staff-wrapper')!.args.name = 'Custom weapon';
    source.engines.push({ name: 'character_custom_bonus', key: 'bonus', args: {}, value: 7 });
    source.engines.find(e => e.key === 'level-3-Gain HP Slot-second')!.args.name = 'New advancement';
    source.engines.find(e => e.name === 'character_hope_current')!.value = 100;
    const { character: c, issues, reportIssues } = recreateDemiplane(source, data, items);
    expect(c.inventory.some(item => item.name === 'Custom weapon')).toBe(true);
    expect(c.carriedWeapons).toEqual([]);
    expect(c.hope.on).toBe(c.hope.slots);
    expect(issues.join('\n')).toMatch(/Unknown domain/);
    expect(issues.join('\n')).toMatch(/New advancement.*not a supported advancement/);
    expect(issues.join('\n')).toMatch(/character_custom_bonus/);
    expect(issues.join('\n')).toMatch(/saved value 100/);
    expect(reportIssues).toEqual([
      'Feature or card "Unknown domain" was not found in the compendium.',
      'Item quantities could not be verified. Each item was imported as one; review quantities.',
    ]);
  });

  it('fails safely for unreadable creation data and never mutates its inputs or shared catalogs', () => {
    const source = fixture(1);
    const snapshot = JSON.stringify({ source, data });
    recreateDemiplane(source, data, items);
    expect(JSON.stringify({ source, data })).toBe(snapshot);
    source.engines.find(e => e.key === 'Deft Deceiver')!.args.name = 'Missing starting card';
    expect(() => recreateDemiplane(source, data, items)).toThrow('Starting domain cards');
    source.engines = source.engines.filter(e => e.name !== 'trait-selection-value-2');
    expect(() => recreateDemiplane(source, data, items)).toThrow('Starting traits');
  });

  it('resolves equipment independently of source order and rejects unsafe currency totals', () => {
    const source = fixture(1);
    source.engines.sort((a, b) => Number(b.key === 'armor') - Number(a.key === 'armor'));
    const result = recreateDemiplane(source, data, items);
    expect(result.character.activeWeapons.map(e => e.name)).toEqual(['Rapier']);
    expect(result.issues.some(issue => issue.includes('could not be matched to imported equipment'))).toBe(false);
    source.engines.find(e => e.name === 'character_gold_playtest_chests_current')!.value = Number.MAX_SAFE_INTEGER;
    expect(() => recreateDemiplane(source, data, items)).toThrow('too large to import safely');
  });

  it('preserves starting gold only when saved gold is absent, including explicit zero and partial saves', () => {
    const source = fixture(1);
    source.engines = source.engines.filter(e => !e.name.startsWith('character_gold_playtest_'));
    expect(recreateDemiplane(source, data, items).character.gold).toBe(10);
    source.engines.push({ name: 'character_gold_playtest_handfuls_current', key: 'gold', args: {}, value: 0 });
    expect(recreateDemiplane(source, data, items).character.gold).toBe(0);
    source.engines[source.engines.length - 1].value = 2;
    expect(recreateDemiplane(source, data, items).character.gold).toBe(20);
    source.engines[source.engines.length - 1].value = -1;
    expect(() => recreateDemiplane(source, data, items)).toThrow('Invalid saved gold');
  });

  it('matches Demiplane rope spelling and both official Brawler keepsakes to catalog inventory', () => {
    const source = fixture(1);
    for (const name of ['50ft of Rope', 'hand wraps from a mentor', 'a book about your secret hobby']) {
      source.engines.push({ name: 'core/selection/equipment/custom/index.eng', key: name, args: { customName: name } });
    }
    const result = recreateDemiplane(source, data, items);
    for (const name of ['50 feet of rope', 'hand wraps from a mentor', 'a book about your secret hobby']) {
      const entry = items.find((e: { name: string }) => e.name === name)!;
      expect(result.character.inventory.find(e => e.name === name)).toMatchObject({ qty: 1, type: 'gear', note: entry.text });
      expect(result.issues.some(issue => issue.includes(`Custom item "${name}"`))).toBe(false);
    }
    expect(result.character.inventory.some(e => e.name === '50ft of Rope')).toBe(false);
    const duplicate = [...items, items.find((e: { name: string }) => e.name === '50 feet of rope')!];
    expect(recreateDemiplane(source, data, duplicate).issues.join('\n')).toContain('ambiguous catalog name');
    const exact = [...items, { id: 'alternate-rope', name: '50ft of Rope', text: 'Host rope entry' }];
    expect(recreateDemiplane(source, data, exact).character.inventory.find(e => e.name === '50ft of Rope')?.note).toBe('Host rope entry');
  });
});
