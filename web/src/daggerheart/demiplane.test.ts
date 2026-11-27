import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { recreateDemiplane, DemiplaneAncestryChoiceRequired, DemiplaneCharacter, DemiplaneSelection } from './demiplane';
import { derivedStats } from './engine';
import { RulesData, subclassStageOf } from './rules';
import { CLASS_GUIDES } from './guide';
import { COMMON_BACKGROUND_FIELDS, normalizeSheet } from './data';

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

function mixedFixture(first: string, second: string): DemiplaneCharacter {
  const source = fixture(1);
  source.engines = source.engines.filter(e => e.key !== 'ancestry');
  source.engines.push(
    { name: 'core/selection/ancestry/mixed-ancestry/index.eng', key: 'mixed', args: { sourceRow: 'heritage-ancestry' } },
    { name: 'character_ancestry_mixed-ancestry-name', key: 'custom-name', args: {}, value: 'Cursed Rabbitfolk' },
    ...[first, second].map((name, i) => ({
      name: `tabula/ancestry-feature/${name.toLowerCase().replaceAll(' ', '-')}.eng`,
      key: `feature-${i}`, args: { name, parentEngine: 'mixed',
        sourceRow: `heritage-ancestry-features-${i === 0 ? 'top' : 'bottom'}` },
    })),
  );
  return source;
}

describe('Demiplane reconstruction', () => {
  it('maps saved pronouns, appearance, notes and ordered question answers into Details and creation choices', () => {
    const source = fixture(3);
    const field = (name: string, value: string) => source.engines.push({ name, key: name, args: {}, value });
    field('character_details_pronoun', 'he/him');
    field('character_details_eyes', 'Emeralds holding a glint of mischief');
    field('character_details_clothes', 'Red cloth and leather tunic');
    field('character_details_body', 'Tall');
    field('character_details_color', 'Chestnut and cream');
    field('character_details_attitude', 'Mischievous');
    field('character_details_description', 'A wandering storyteller.');
    field('character_details_notes', 'Keep this\non two lines.');
    field('character_details_background-question-2--answer', 'The mentor');
    field('character_details_background-question-0--answer', 'The village elders');
    field('character_details_connection-question-1--answer', 'We disagree about magic');
    const { character: c, issues } = recreateDemiplane(source, data, items);
    expect(c.pronouns).toBe('he/him');
    expect(c.description).toMatchObject({ Pronouns: 'he/him', Eyes: 'Emeralds holding a glint of mischief',
      Attire: 'Red cloth and leather tunic', Physique: 'Tall', 'Skin / Fur': 'Chestnut and cream', Attitude: 'Mischievous' });
    expect(Object.keys(c.description)).toEqual(Object.keys(recreateDemiplane(fixture(3), data, items).character.description));
    expect(c.description).not.toHaveProperty('Body');
    expect(c.description).not.toHaveProperty('Color');
    expect(c.otherDetails).toBe('A wandering storyteller.');
    expect(c.notes).toBe('Keep this\non two lines.');
    expect(c.backgroundQuestions).toEqual([
      `${CLASS_GUIDES.Bard.background[0]}\nThe village elders`,
      CLASS_GUIDES.Bard.background[1],
      `${CLASS_GUIDES.Bard.background[2]}\nThe mentor`,
    ]);
    expect(c.connections[1]).toBe(`${CLASS_GUIDES.Bard.connections[1]}\nWe disagree about magic`);
    expect(c.mancer?.choices).toMatchObject({ pronouns: 'he/him', description: 'A wandering storyteller.',
      background: [
        { question: CLASS_GUIDES.Bard.background[0], answer: 'The village elders' },
        { question: CLASS_GUIDES.Bard.background[1], answer: '' },
        { question: CLASS_GUIDES.Bard.background[2], answer: 'The mentor' },
      ] });
    expect(normalizeSheet(JSON.parse(JSON.stringify(c)), c.name)).toMatchObject({
      pronouns: c.pronouns, description: c.description, otherDetails: c.otherDetails,
      backgroundQuestions: c.backgroundQuestions, connections: c.connections, notes: c.notes,
    });
    expect(issues.some(issue => issue.includes('character_details'))).toBe(false);
  });

  it('reports an unavailable public portrait, but not one that was fetched successfully', () => {
    const source = fixture(1);
    source.engines.push({ name: 'character_avatar', key: 'avatar', args: {}, value: '' });
    const unavailable = recreateDemiplane({ ...source, portraitIssue: 'The portrait could not be fetched.' }, data, items);
    expect(unavailable.reportIssues).toContain('The portrait could not be fetched.');
    const available = recreateDemiplane({ ...source, portrait: 'data:image/webp;base64,YQ==' }, data, items);
    expect(available.reportIssues.some(issue => /portrait/i.test(issue))).toBe(false);
    expect(available.issues.some(issue => /portrait/i.test(issue))).toBe(false);
  });

  it('preserves saved question wording and uses numbered labels when no verified guide exists', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.name = 'Homebrew';
    classes.filter(e => e.parent === 'Bard').forEach(e => { e.parent = 'Homebrew'; });
    source.engines.find(e => e.key === 'class')!.args.name = 'Homebrew';
    source.engines.push(
      { name: 'character_details_background-question-2--answer', key: 'answer', args: {}, value: 'Free him from this weapon' },
      { name: 'character_details_connection-question-0--question', key: 'prompt', args: {}, value: 'Who saved you?' },
      { name: 'character_details_connection-question-0--answer', key: 'connection', args: {}, value: 'My friend' },
    );
    const c = recreateDemiplane(source, { ...data, classes }, items).character;
    expect(c.backgroundQuestions).toEqual(['Background question 3\nFree him from this weapon']);
    expect(c.connections).toEqual(['Who saved you?\nMy friend']);
    expect(c.mancer?.choices?.background).toEqual([{ question: 'Background question 3', answer: 'Free him from this weapon' }]);
  });

  it('keeps the legacy pronouns field and rejects conflicting or malformed details', () => {
    const source = fixture(1);
    source.engines.push({ name: 'character_pronouns', key: 'pronouns', args: {}, value: 'they/them' });
    expect(recreateDemiplane(source, data, items).character.pronouns).toBe('they/them');
    source.engines.push({ name: 'character_details_pronoun', key: 'detail-pronouns', args: {}, value: 'he/him' });
    expect(() => recreateDemiplane(source, data, items)).toThrow('conflicting values');
    source.engines.pop();
    source.engines.push({ name: 'character_details_background-question-0--answer', key: 'answer', args: {}, value: 42 });
    expect(() => recreateDemiplane(source, data, items)).toThrow('is not text');
    source.engines.pop();
    source.engines.push({ name: 'character_details_background-question-100--answer', key: 'answer', args: {}, value: 'Answer' });
    expect(() => recreateDemiplane(source, data, items)).toThrow('unsupported question index');
  });

  it('imports Warlock patron and sphere as narrative details without changing mechanics', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes.filter(e => e.name === 'Bard' || e.parent === 'Bard'));
    classes.find(e => e.kind === 'class')!.name = 'Warlock';
    classes.find(e => e.kind === 'class')!.backgroundFields = ['Patron', 'Sphere of influence'];
    classes.filter(e => e.parent === 'Bard').forEach(e => { e.parent = 'Warlock'; });
    source.engines.find(e => e.key === 'class')!.args.name = 'Warlock';
    const baseline = recreateDemiplane(source, { ...data, classes }, items).character;
    source.engines.push(
      { name: 'element-class_custom-option_warlock-patron_value', key: 'patron', args: {}, value: 'A fallen deity' },
      { name: 'element-class_custom-option_warlock-sphere-of-influence_value', key: 'sphere', args: {}, value: 'War' },
    );
    const c = recreateDemiplane(source, { ...data, classes }, items).character;
    expect(c.description).toMatchObject({ Patron: 'A fallen deity', 'Sphere of influence': 'War' });
    expect(c.features).toEqual(baseline.features);
    expect(derivedStats(c)).toEqual(derivedStats(baseline));
    const withoutConfiguredFields = structuredClone(classes);
    withoutConfiguredFields.find(e => e.kind === 'class')!.backgroundFields = [];
    const unconfigured = recreateDemiplane(source, { ...data, classes: withoutConfiguredFields }, items).character;
    expect(unconfigured.description).not.toHaveProperty('Patron');
    expect(unconfigured.description).not.toHaveProperty('Sphere of influence');
    expect(unconfigured.otherDetails).not.toContain('fallen deity');
    source.engines.push({ name: 'character_details_background-question-2--answer', key: 'answer', args: {}, value: 'Free him from this weapon' });
    expect(recreateDemiplane(source, { ...data, classes }, items).character.backgroundQuestions)
      .toEqual([...CLASS_GUIDES.Warlock.background.slice(0, 2), `${CLASS_GUIDES.Warlock.background[2]}\nFree him from this weapon`]);
  });

  it('maps appearance aliases, rejects conflicting aliases and never adds unknown detail fields', () => {
    const source = fixture(1);
    for (const [field, value] of [['eyes-like', 'Green'], ['body-thats', 'Tall'], ['the-color-of', 'Brown'],
      ['attitude-like', 'Cheerful'], ['favorite-food', 'Soup']])
      source.engines.push({ name: `character_details_${field}`, key: field, args: {}, value });
    const c = recreateDemiplane(source, data, items).character;
    expect(c.description).toMatchObject({ Eyes: 'Green', Physique: 'Tall', 'Skin / Fur': 'Brown', Attitude: 'Cheerful' });
    expect(c.description).not.toHaveProperty('favorite-food');
    source.engines.push({ name: 'character_details_eyes', key: 'eyes', args: {}, value: 'Blue' });
    expect(() => recreateDemiplane(source, data, items)).toThrow('conflicting values');
  });

  it('matches configured class fields by saved names and explicit labels without adding unconfigured fields', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = [
      'Instrument', 'Life Motto', 'Stage Name', 'Caf\u00e9 Style', "Traveler's Mark", 'Catchphrase', 'Sigil', 'Blank',
    ];
    source.engines.push(
      { name: 'character_details_INSTRUMENT', key: 'instrument', args: {}, value: 'Lute' },
      { name: 'character_details_life-motto', key: 'motto', args: {}, value: 'Make them laugh' },
      { name: 'character_details_custom-1', key: 'stage', args: { name: 'STAGE_name' }, value: 'The Star' },
      { name: 'character_details_custom-2', key: 'style', args: { label: 'Cafe\u0301-Style' }, value: 'Bright' },
      { name: 'character_details_custom-3', key: 'phrase', args: { customName: 'Catchphrase' }, value: 'Encore!' },
      { name: 'element-class_custom-option_BARD-traveler\u2019s-mark_value', key: 'mark', args: {}, value: 'Compass' },
      { name: 'element-class_custom-option_bard-id-123_value', key: 'sigil', args: { label: 'Sigil' }, value: 'Star' },
      { name: 'element-class_custom-option_bard-instrument_value', key: 'instrument-copy', args: {}, value: 'Lute' },
      { name: 'character_details_blank', key: 'blank', args: {} },
      { name: 'character_details_instrument-case', key: 'partial', args: {}, value: 'Not an instrument' },
      { name: 'character_details_favorite-food', key: 'food', args: {}, value: 'Soup' },
      { name: 'element-other_custom-option_bard-instrument_value', key: 'other', args: {}, value: 'Not this class' },
      { name: 'element-class_custom-option_ranger-instrument_value', key: 'wrong-class', args: { label: 'Instrument' }, value: 'Not a Bard option' },
      { name: 'character_details_background-question-5--answer', key: 'question', args: { name: 'Instrument' }, value: 'An answer' },
      { name: 'character_details_background_question_6__answer', key: 'other-question', args: { name: 'Instrument' }, value: 'Another answer' },
    );
    const { character: c, issues, reportIssues } = recreateDemiplane(source, { ...data, classes }, items);
    expect(c.description).toMatchObject({
      Instrument: 'Lute', 'Life Motto': 'Make them laugh', 'Stage Name': 'The Star',
      'Caf\u00e9 Style': 'Bright', "Traveler's Mark": 'Compass', Catchphrase: 'Encore!', Sigil: 'Star', Blank: '',
    });
    expect(Object.keys(c.description)).toEqual([
      ...COMMON_BACKGROUND_FIELDS, 'Instrument', 'Life Motto', 'Stage Name', 'Caf\u00e9 Style', "Traveler's Mark", 'Catchphrase', 'Sigil', 'Blank',
    ]);
    expect(c.description).not.toHaveProperty('favorite-food');
    expect(c.mancer?.choices?.backgroundDetails).toEqual(c.description);
    expect(normalizeSheet(JSON.parse(JSON.stringify(c)), c.name).description).toEqual(c.description);
    expect(issues.some(issue => issue.includes('character_details_INSTRUMENT'))).toBe(false);
    expect(issues.some(issue => issue.includes('element-class_custom-option_bard-instrument_value'))).toBe(false);
    expect(issues.some(issue => issue.includes('character_details_favorite-food'))).toBe(true);
    expect(reportIssues.some(issue => /favorite.food/i.test(issue))).toBe(false);
  });

  it('imports configured fields for a matched multiclass using that class engine, without cross-class guessing', () => {
    const source = fixture(5);
    source.engines = source.engines.filter(e => !e.key.startsWith('level-5-') && e.key !== 'Words of Discord');
    source.engines.push(
      { name: 'core/selection/level-up/generic-option/index.eng', key: 'multi', args: { name: 'Multiclass', level: 5 } },
      { name: 'tabula/class/warlock.eng', key: 'warlock', args: { name: 'Warlock', slug: 'warlock', parentEngine: 'multi' } },
      { name: 'tabula/subclass/pact-of-the-endless-foundation.eng', key: 'endless',
        args: { name: 'Pact Of The Endless', parentEngine: 'warlock', sourceRow: 'warlock-multiclass-subclass-choice' } },
      { name: 'core/selection/level-up/extra-domain/index.eng', key: 'dread',
        args: { name: 'Dread', parentEngine: 'warlock', sourceRow: 'warlock-multiclass-domain-choice' } },
      { name: 'element-class_custom-option_bard-instrument_value', key: 'instrument', args: {}, value: 'Lute' },
      { name: 'element-warlock_custom-option_warlock-patron_value', key: 'patron', args: {}, value: 'The moon' },
      { name: 'element-warlock_custom-option_warlock-sphere-of-influence_value', key: 'sphere', args: {}, value: 'Tides' },
      { name: 'element-warlock_custom-option_warlock-instrument_value', key: 'multi-instrument', args: {}, value: 'Lute' },
      { name: 'character_details_shared_name_value', key: 'shared', args: {}, value: 'The Wanderer' },
      { name: 'element-class_custom-option_bard-patron_value', key: 'other-patron', args: {}, value: 'Wrong class' },
      { name: 'element-warlock_custom-option_warlock-unconfigured_value', key: 'unknown', args: {}, value: 'No row' },
    );
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Instrument', 'Shared Name'];
    classes.find(e => e.kind === 'class' && e.name === 'Warlock')!.backgroundFields!.push('instrument');
    const { character: c, issues } = recreateDemiplane(source, { ...data, classes }, items);
    expect(c.levelUps![3].advancements).toContainEqual({ type: 'multiclass',
      multiclass: { className: 'Warlock', subclass: 'Warlock: Pact Of The Endless', domain: 'Dread' } });
    expect(Object.keys(c.description)).toEqual([...COMMON_BACKGROUND_FIELDS, 'Instrument', 'Shared Name', 'Patron', 'Sphere of influence']);
    expect(c.description).toMatchObject({ Instrument: 'Lute', 'Shared Name': 'The Wanderer',
      Patron: 'The moon', 'Sphere of influence': 'Tides' });
    expect(c.mancer?.choices?.backgroundDetails).toEqual(c.description);
    expect(issues.some(issue => issue.includes('element-warlock_custom-option_warlock-patron_value'))).toBe(false);
    expect(issues.some(issue => issue.includes('element-class_custom-option_bard-patron_value'))).toBe(true);
    expect(c.description).not.toHaveProperty('Unconfigured');

    source.engines.find(e => e.key === 'multi-instrument')!.value = 'Harp';
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow('conflicting values for "Instrument"');
    source.engines.find(e => e.key === 'multi-instrument')!.value = 'Lute';
    source.engines.find(e => e.key === 'dread')!.args.name = 'Not a domain';
    const unresolved = recreateDemiplane(source, { ...data, classes }, items).character;
    expect(unresolved.description).not.toHaveProperty('Patron');
    expect(unresolved.description).not.toHaveProperty('Sphere of influence');
  });

  it('rejects conflicting and ambiguous matching field names instead of choosing a value', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Instrument', 'Motto'];
    source.engines.push(
      { name: 'character_details_instrument', key: 'detail', args: {}, value: 'Lute' },
      { name: 'element-class_custom-option_bard-instrument_value', key: 'class-detail', args: {}, value: 'Harp' },
    );
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow(/conflicting values for "Instrument".*Nothing was imported/);
    source.engines.find(e => e.key === 'class-detail')!.value = '';
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow('conflicting values for "Instrument"');
    source.engines = source.engines.filter(e => e.key !== 'class-detail');
    source.engines.find(e => e.key === 'detail')!.args.label = 'Motto';
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow(/matches multiple configured fields.*Instrument.*Motto/);

    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Instrument', 'Instrument Value'];
    source.engines.find(e => e.key === 'detail')!.name = 'character_details_instrument_value';
    source.engines.find(e => e.key === 'detail')!.args = {};
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow('matches multiple configured fields');

    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Caf\u00e9', 'Cafe\u0301'];
    source.engines.find(e => e.key === 'detail')!.name = 'character_details_caf\u00e9';
    source.engines.find(e => e.key === 'detail')!.args = {};
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow('matches multiple configured fields');
  });

  it('preserves configured labels that are also JavaScript object property names', () => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['__proto__', 'constructor'];
    source.engines.push(
      { name: 'character_details___proto__', key: 'proto', args: {}, value: 'A label' },
      { name: 'character_details_constructor', key: 'constructor', args: {}, value: 'Another label' },
    );
    const c = recreateDemiplane(source, { ...data, classes }, items).character;
    expect(Object.getOwnPropertyDescriptor(c.description, '__proto__')?.value).toBe('A label');
    expect(Object.getOwnPropertyDescriptor(c.description, 'constructor')?.value).toBe('Another label');
    expect(Object.getPrototypeOf(c.description)).toBe(Object.prototype);
  });

  it.each([null, 42, false, {}, []])('rejects a matching class-specific value that is not text (%s)', value => {
    const source = fixture(1);
    const classes = structuredClone(data.classes);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Instrument'];
    source.engines.push({ name: 'element-class_custom-option_bard-instrument_value', key: 'bad', args: {}, value });
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).toThrow(/Instrument.*not text.*Nothing was imported/);
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = [];
    expect(() => recreateDemiplane(source, { ...data, classes }, items)).not.toThrow();
  });

  it('reconstructs the linked mixed ancestry from the ordered selected features, not its custom name', () => {
    const { character: c, issues } = recreateDemiplane(mixedFixture('Caprine Leap', 'Ignition'), data, items);
    expect(c.ancestry).toBe('Faun / Emberkin');
    expect(c.heritage.filter(f => f.source.startsWith('Ancestry - ')).map(f => f.name))
      .toEqual(['Caprine Leap', 'Ignition']);
    expect(c.mancer?.choices?.ancestry).toBe('Faun');
    expect(c.mancer?.choices?.ancestry2).toBe('Emberkin');
    expect(issues.some(issue => /mixed-ancestry|caprine-leap|ignition/i.test(issue))).toBe(false);
  });

  it('requires explicit valid choices for each ambiguous ancestry feature', () => {
    const source = mixedFixture('Amphibious', 'Kick');
    expect(() => recreateDemiplane(source, data, items)).toThrow(DemiplaneAncestryChoiceRequired);
    try { recreateDemiplane(source, data, items); } catch (e) {
      expect(e).toMatchObject({ position: 0, feature: 'Amphibious', candidates: ['Ribbet', 'Tidekin'] });
    }
    try { recreateDemiplane(source, data, items, { 0: 'Tidekin' }); } catch (e) {
      expect(e).toMatchObject({ position: 1, feature: 'Kick', candidates: ['Faun', 'Centaur'] });
    }
    const c = recreateDemiplane(source, data, items, { 0: 'Tidekin', 1: 'Centaur' }).character;
    expect(c.ancestry).toBe('Tidekin / Centaur');
    expect(c.heritage.filter(f => f.source.startsWith('Ancestry - ')).map(f => f.name)).toEqual(['Amphibious', 'Kick']);
    expect(() => recreateDemiplane(source, data, items, { 0: 'Faun' })).toThrow('invalid ancestry choice');
  });

  it('rejects missing, unknown, conflicting and duplicate mixed-ancestry selections', () => {
    const source = mixedFixture('Caprine Leap', 'Ignition');
    source.engines.find(e => e.key === 'feature-0')!.args.parentEngine = 'other';
    expect(() => recreateDemiplane(source, data, items)).toThrow('missing first feature');
    source.engines.find(e => e.key === 'feature-0')!.args.parentEngine = 'mixed';
    source.engines.find(e => e.key === 'feature-0')!.args.name = 'Unknown feature';
    expect(() => recreateDemiplane(source, data, items)).toThrow('no matching local ancestry');
    source.engines.find(e => e.key === 'feature-0')!.args.name = 'Caprine Leap';
    source.engines.push({ ...source.engines.find(e => e.key === 'feature-0')! });
    expect(() => recreateDemiplane(source, data, items)).toThrow('multiple saved selections');
    source.engines.pop();
    source.engines.push({ name: 'tabula/ancestry/elf.eng', key: 'elf', args: { name: 'Elf' } });
    expect(() => recreateDemiplane(source, data, items)).toThrow('conflicting standard and mixed');
  });

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

  it.each([
    ['Assassin', [2, -1, 1, 0, 0, 1]],
    ['Warlock', [1, -1, 0, 1, 2, 0]],
    ['Witch', [0, -1, 0, 2, 1, 1]],
  ])('imports %s suggested traits before replaying level-up increases', (name, initial) => {
    const source = fixture(2);
    const classes = structuredClone(data.classes.filter(e => e.name === 'Bard' || e.parent === 'Bard'));
    classes.find(e => e.kind === 'class' && e.name === 'Bard')!.name = name;
    classes.filter(e => e.parent === 'Bard').forEach(e => { e.parent = name; });
    source.engines.find(e => e.key === 'class')!.args.name = name;
    source.engines = source.engines.filter(e => !e.name.startsWith('trait-selection-value'));
    source.engines.push({ name: 'use-suggested-traits', key: 'suggested', args: {}, value: 1 });
    const c = recreateDemiplane(source, { ...data, classes }, items).character;
    expect(c.mancer?.choices?.traits).toEqual(Object.fromEntries(['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge'].map((trait, i) => [trait, initial[i]])));
    expect(c.traits.map(t => t.mod)).toEqual(initial.map((value, i) => value + (i === 3 || i === 4 ? 1 : 0)));
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

  it('matches SRD 2 keepsakes regardless of typographic apostrophes', () => {
    const source = fixture(1);
    const saved = ['a ring you can\'t remove', 'A carving that symbolizes your patron', 'A list of names with several marked off',
      'a rusted blade inscribed with an insignia', 'A small, harmless pet', 'a scrying stone', 'A book you\'re trying to translate'];
    for (const name of saved) source.engines.push({ name: 'core/selection/equipment/custom/index.eng', key: name, args: { customName: name } });
    const result = recreateDemiplane(source, data, items);
    for (const name of [...saved.slice(0, 6), 'A book you’re trying to translate']) {
      const entry = items.find((e: { name: string }) => e.name === name)!;
      expect(entry, name).toBeDefined();
      expect(result.character.inventory.find(e => e.name === name), name).toMatchObject({ qty: 1, type: 'gear', note: entry.text, image: entry.image });
    }
    expect(result.issues.some(issue => issue.includes('Custom item'))).toBe(false);
  });
});
