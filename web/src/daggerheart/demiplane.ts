import { Advancement, CharacterSheet, COMMON_BACKGROUND_FIELDS, handsUsed, ignoresWeaponBurden, LevelUpRecord, TRAIT_ORDER, TraitName } from './data';
import { addToSheet, armorFrom, CompendiumEntry, weaponFrom } from './compendium';
import { derivedStats, syncDerived } from './engine';
import { CLASS_GUIDES } from './guide';
import { backgroundFields, characterBackgroundFields, syncBackgroundDetails } from './background';
import {
  applyLevelUp, buildCharacter, creationAllowances, emptyChoices, levelUpProblems, RulesData,
  shortSubclass, traitsValid,
} from './rules';

export interface DemiplaneSelection {
  name: string;
  key: string;
  args: Record<string, unknown>;
  value?: unknown;
}

export interface DemiplaneCharacter {
  name: string;
  level: number;
  summary: { class?: string; subclass?: string };
  engines: DemiplaneSelection[];
  portrait?: string;
  portraitIssue?: string;
}

export interface DemiplaneResult {
  character: CharacterSheet;
  issues: string[];
  reportIssues: string[];
}

export class DemiplaneAncestryChoiceRequired extends Error {
  constructor(readonly position: 0 | 1, readonly feature: string, readonly candidates: string[]) {
    super(`Choose the ancestry for ${position === 0 ? 'first' : 'second'} feature "${feature}".`);
  }
}

const text = (value: unknown): string => typeof value === 'string' ? value : '';
const normalize = (value: string): string => value.normalize('NFKC').trim().replace(/\s+/g, ' ').replace(/[‘’]/g, "'").toLowerCase();
const itemMatches = (entries: CompendiumEntry[], name: string): CompendiumEntry[] => {
  const exact = entries.filter(e => normalize(e.name) === normalize(name));
  if (exact.length || normalize(name) !== '50ft of rope') return exact;
  return entries.filter(e => normalize(e.name) === '50 feet of rope');
};
const title = (e: DemiplaneSelection): string => text(e.args.name) || text(e.args.customName)
  || e.name.split('/').pop()?.replace(/\.eng$/, '').replace(/-/g, ' ') || e.name;

interface ImportedClassSelection { className: string; engine: DemiplaneSelection }

function importClassBackgroundDetails(classes: CompendiumEntry[], character: CharacterSheet, engines: DemiplaneSelection[],
                                      classSelections: ImportedClassSelection[], consumed: Set<DemiplaneSelection>): Record<string, string> {
  const fieldKey = (name: string) => normalize(name).replace(/[\s_-]+/g, ' ').trim();
  const fields = characterBackgroundFields(classes, character).slice(COMMON_BACKGROUND_FIELDS.length)
    .map(label => ({ label, key: fieldKey(label) }));
  const allKeys = new Set(fields.map(field => field.key));
  const scopes = classSelections.filter(({ engine }) => engine.key).map(({ className, engine }) => ({
    prefix: `element-${engine.key}_custom-option_`,
    slugs: [...new Set([className, text(engine.args.slug), engine.name.split('/').pop()?.replace(/\.eng$/, '') ?? '']
      .map(fieldKey).filter(Boolean))],
    fields: new Set(backgroundFields(classes, [className]).slice(COMMON_BACKGROUND_FIELDS.length).map(fieldKey)),
  }));
  const answers = new Map<string, { value: string; source: string }>();
  const labels = (engine: DemiplaneSelection) => [text(engine.args.name), text(engine.args.label), text(engine.args.customName)];
  for (const engine of engines) {
    if (consumed.has(engine)) continue;
    const matches = new Set<string>();
    const addMatches = (names: string[], allowed: ReadonlySet<string>) => {
      const keys = new Set(names.map(fieldKey).filter(Boolean));
      for (const field of fields) if (allowed.has(field.key) && keys.has(field.key)) matches.add(field.label);
    };
    if (engine.name.toLowerCase().startsWith('character_details_')
        && !/^character_details_(background|connection)[_-]question[_-]/i.test(engine.name)) {
      const name = engine.name.slice('character_details_'.length);
      const withoutValue = name.toLowerCase().endsWith('_value') ? name.slice(0, -'_value'.length) : '';
      addMatches([name, withoutValue, ...labels(engine)], allKeys);
    }
    for (const scope of scopes) {
      if (!engine.name.startsWith(scope.prefix) || !engine.name.toLowerCase().endsWith('_value')) continue;
      const name = fieldKey(engine.name.slice(scope.prefix.length, -'_value'.length));
      const fieldNames = scope.slugs.filter(slug => name.startsWith(`${slug} `))
        .map(slug => name.slice(slug.length + 1));
      if (fieldNames.length) addMatches([...fieldNames, ...labels(engine)], scope.fields);
    }
    if (matches.size > 1)
      throw new Error(`Character details: "${engine.name}" matches multiple configured fields (${[...matches].join(', ')}). Nothing was imported.`);
    const field = [...matches][0];
    if (!field) continue;
    consumed.add(engine);
    if (engine.value === undefined) continue;
    if (typeof engine.value !== 'string')
      throw new Error(`Character details: "${engine.name}" for "${field}" is not text. Nothing was imported.`);
    const previous = answers.get(field);
    if (previous && previous.value !== engine.value)
      throw new Error(`Character details: conflicting values for "${field}" in "${previous.source}" and "${engine.name}". Nothing was imported.`);
    answers.set(field, { value: engine.value, source: engine.name });
  }
  return Object.fromEntries([...answers].map(([field, answer]) => [field, answer.value]));
}

/** Replays saved choices with local rules, not Demiplane's rendered totals or executable engine files. */
export function recreateDemiplane(source: DemiplaneCharacter, data: RulesData, items: CompendiumEntry[],
                                  ancestryChoices: Partial<Record<0 | 1, string>> = {}): DemiplaneResult {
  if (!source || !Array.isArray(source.engines) || !source.engines.length
      || source.engines.some(e => !e || typeof e.name !== 'string' || typeof e.key !== 'string'
        || !e.args || typeof e.args !== 'object' || Array.isArray(e.args))) {
    throw new Error('Demiplane returned an unsupported character format. Nothing was imported.');
  }
  const issues: string[] = [];
  const reportIssues: string[] = [];
  const consumed = new Set<DemiplaneSelection>();
  const all = source.engines;
  const use = (e: DemiplaneSelection) => { consumed.add(e); return e; };
  const scalar = (name: string): unknown => {
    const matches = all.filter(e => e.name === name);
    if (matches.length > 1) throw new Error(`Demiplane contains conflicting values for "${name}". Nothing was imported.`);
    return matches.length ? use(matches[0]).value : undefined;
  };
  const detailText = (...fields: string[]): string => {
    const values = fields.map(field => {
      const value = scalar(field);
      if (value !== undefined && typeof value !== 'string')
        throw new Error(`Character details: "${field}" is not text. Nothing was imported.`);
      return value;
    }).filter((value): value is string => value !== undefined);
    if (new Set(values).size > 1)
      throw new Error(`Character details: conflicting values for ${fields.join(' / ')}. Nothing was imported.`);
    return values[0] ?? '';
  };
  const match = (entries: CompendiumEntry[], name: string, context: string, required = false): CompendiumEntry | undefined => {
    const matches = context === 'Equipment' ? itemMatches(entries, name)
      : entries.filter(e => normalize(e.name) === normalize(name));
    if (matches.length === 1) return matches[0];
    const message = matches.length > 1
      ? `${context}: "${name}" matches multiple local catalog entries; no match was guessed.`
      : `${context}: "${name || '(missing name)'}" was not found in the local catalog.`;
    if (required) throw new Error(`${message} This build cannot be recreated safely. Nothing was imported.`);
    issues.push(message);
    if (context !== 'Equipment') reportIssues.push(matches.length > 1
      ? `Feature or card "${name}" matches multiple compendium entries and was not imported.`
      : `Feature or card "${name || '(missing name)'}" was not found in the compendium.`);
    return undefined;
  };
  const selected = (pattern: RegExp) => all.filter(e => pattern.test(e.name));
  const unique = (entries: DemiplaneSelection[], context: string): DemiplaneSelection | undefined => {
    if (entries.length > 1) throw new Error(`${context}: multiple saved selections are not supported. Nothing was imported.`);
    return entries[0] ? use(entries[0]) : undefined;
  };
  const ch = emptyChoices();
  ch.name = text(scalar('character_name')) || source.name;
  if (!ch.name?.trim()) throw new Error('The shared character has no name. Nothing was imported.');
  const cls = unique(selected(/^tabula\/class\//).filter(e => e.args.builderSection === 'class'), 'Class');
  ch.className = match(data.classes.filter(e => e.kind === 'class'),
    cls ? title(cls) : text(source.summary?.class), 'Class', true)!.name;
  const classSelections: ImportedClassSelection[] = cls ? [{ className: ch.className, engine: cls }] : [];
  const sub = unique(selected(/^tabula\/subclass\//).filter(e => e.args.sourceRow === 'class-foundation'), 'Subclass');
  const subName = sub ? title(sub) : text(source.summary?.subclass);
  const foundations = data.classes.filter(e => e.kind === 'subclass' && e.parent === ch.className && e.stage === 'foundation');
  ch.subclass = match(foundations.map(e => ({ ...e, name: shortSubclass(e.name) })), shortSubclass(subName), 'Subclass', true)!.name;
  ch.subclass = foundations.find(e => normalize(shortSubclass(e.name)) === normalize(ch.subclass))!.name;
  const ancestry = unique(selected(/^tabula\/ancestry\//), 'Ancestry');
  const mixed = unique(selected(/^core\/selection\/ancestry\/mixed-ancestry\/index\.eng$/), 'Mixed ancestry');
  const community = unique(selected(/^tabula\/community\//), 'Community');
  if (mixed && ancestry) throw new Error('Ancestry: conflicting standard and mixed selections. Nothing was imported.');
  if (mixed) {
    for (const [position, row] of [[0, 'heritage-ancestry-features-top'], [1, 'heritage-ancestry-features-bottom']] as const) {
      const feature = unique(selected(/^tabula\/ancestry-feature\//)
        .filter(e => e.args.parentEngine === mixed.key && e.args.sourceRow === row), `Mixed ancestry ${row}`);
      if (!feature) throw new Error(`Mixed ancestry: missing ${position === 0 ? 'first' : 'second'} feature. Nothing was imported.`);
      const featureName = title(feature);
      const candidates = data.ancestries.filter(e => normalize(e.features?.[position]?.name ?? '') === normalize(featureName));
      if (!featureName || !candidates.length) throw new Error(`Mixed ancestry: feature "${featureName || '(missing name)'}" has no matching local ancestry. Nothing was imported.`);
      const names = candidates.map(e => e.name);
      if (new Set(names.map(normalize)).size !== names.length)
        throw new Error(`Mixed ancestry: duplicate local ancestry names for "${featureName}". Nothing was imported.`);
      const choice = ancestryChoices[position];
      if (choice && !names.includes(choice)) throw new Error(`Mixed ancestry: invalid ancestry choice for "${featureName}". Nothing was imported.`);
      if (names.length > 1 && !choice) throw new DemiplaneAncestryChoiceRequired(position, featureName, names);
      if (position === 0) ch.ancestry = choice || names[0];
      else ch.ancestry2 = choice || names[0];
    }
    scalar('character_ancestry_mixed-ancestry-name');
  } else {
    ch.ancestry = match(data.ancestries, ancestry ? title(ancestry) : '', 'Ancestry', true)!.name;
  }
  ch.community = match(data.communities, community ? title(community) : '', 'Community', true)!.name;
  ch.traits = Object.fromEntries(TRAIT_ORDER.map(name => [name, 0])) as Record<TraitName, number>;
  const manualTraits = [['trait-selection-value-2', 2], ['trait-selection-value-1', 1],
    ['trait-selection-value-1-second', 1], ['trait-selection-value-neg-1', -1]] as const;
  const useSuggested = scalar('use-suggested-traits');
  if (useSuggested === 1 || useSuggested === true) {
    const values = CLASS_GUIDES[ch.className]?.traits;
    if (!values) throw new Error(`Starting traits: the suggested allocation for "${ch.className}" is not supported. Nothing was imported.`);
    TRAIT_ORDER.forEach((name, i) => { ch.traits[name] = values[i]; });
    manualTraits.forEach(([field]) => scalar(field));
  } else {
    const allocated = new Set<TraitName>();
    for (const [field, value] of manualTraits) {
      const name = text(scalar(field));
      const trait = TRAIT_ORDER.find(t => normalize(t) === normalize(name));
      if (!trait || allocated.has(trait)) throw new Error(`Starting traits: invalid or missing "${field}". Nothing was imported.`);
      ch.traits[trait] = value;
      allocated.add(trait);
    }
  }
  if (!traitsValid(ch.traits)) throw new Error('The starting trait allocation could not be reconstructed. Nothing was imported.');
  const stances = foundations.find(e => e.name === ch.subclass)?.features?.flatMap(f => f.automation?.stances ?? []) ?? [];
  if (stances.length && sub) {
    ch.stances = selected(/^tabula\/unique-element\//)
      .filter(e => e.args.parentEngine === sub.key && e.args.sharedSelectionPoolID === 'martial-artist-form')
      .map(e => match(stances.map(s => ({ id: s.name, name: s.name })), title(use(e)), 'Martial Artist form', true)!.name);
  }
  ch.experiences = [0, 1].map(i => text(scalar(`character_experience_${i}_name`)));
  if (ch.experiences.some(n => !n.trim())) issues.push('One or both starting Experiences are blank. Fill them in on the sheet.');
  ch.pronouns = detailText('character_details_pronoun', 'character_pronouns');
  const questions = (kind: 'background' | 'connection', guide: string[]) => {
    const prefix = `character_details_${kind}-question-`;
    const indices = new Set<number>(guide.map((_, i) => i));
    for (const e of all.filter(e => e.name.startsWith(prefix))) {
      const suffix = /^(\d+)--(answer|question)$/.exec(e.name.slice(prefix.length));
      if (!suffix) continue;
      const index = Number(suffix[1]);
      if (!Number.isSafeInteger(index) || index > 99)
        throw new Error(`Character details: unsupported question index in "${e.name}". Nothing was imported.`);
      indices.add(index);
    }
    return [...indices].sort((a, b) => a - b).map(i => ({
      question: detailText(`${prefix}${i}--question`) || guide[i]
        || `${kind === 'background' ? 'Background' : 'Connection'} question ${i + 1}`,
      answer: detailText(`${prefix}${i}--answer`),
    }));
  };
  ch.background = questions('background', CLASS_GUIDES[ch.className]?.background ?? []);
  ch.connections = questions('connection', CLASS_GUIDES[ch.className]?.connections ?? []);
  ch.description = detailText('character_details_description');
  const description: Record<string, string> = {};
  for (const [field, label] of [
    ['age', 'Age'], ['height', 'Height'], ['hair', 'Hair'],
  ]) {
    const value = detailText(`character_details_${field}`);
    if (value) description[label] = value;
  }
  for (const [label, fields] of [
    ['Eyes', ['eyes', 'eyes-like']],
    ['Attire', ['clothes', 'attire']],
    ['Physique', ['body', 'body-thats', 'physique']],
    ['Skin / Fur', ['skin', 'color', 'the-color-of']],
    ['Attitude', ['attitude', 'attitude-like']],
  ] as const) {
    const value = detailText(...fields.map(field => `character_details_${field}`));
    if (value) description[label] = value;
  }
  const notes = detailText('character_details_notes');
  const cards = selected(/^tabula\/domain\//);
  const matchedCards = new Map<DemiplaneSelection, string>();
  for (const e of cards) {
    use(e);
    const entry = match(data.cards, title(e), `Domain card (${text(e.args.sourceRow) || 'unknown acquisition'})`);
    if (entry) matchedCards.set(e, entry.name);
  }
  ch.cards = cards.filter(e => e.args.builderSection === 'class').flatMap(e => matchedCards.get(e) ?? []);
  const grants = creationAllowances(data, ch);
  if (grants.experienceBonus) {
    throw new Error('This build requires a starting Experience bonus selection that cannot yet be read reliably from Demiplane. Nothing was imported; use Create Character instead.');
  }
  if (ch.cards.length !== grants.domainCards) throw new Error(
    [...issues, `Starting domain cards: expected ${grants.domainCards}, found ${ch.cards.length} matched selections. Nothing was imported.`].join('\n'));
  let c: CharacterSheet;
  try { c = buildCharacter(data, ch); }
  catch (error) {
    throw new Error(`This build needs additional creation selections: ${(error as Error).message} They could not be read reliably from Demiplane. Nothing was imported; use Create Character instead.`);
  }
  syncBackgroundDetails(data.classes, c, description);
  c.notes = notes;
  const savedLevel = scalar('character_level');
  const level = savedLevel === undefined ? source.level : savedLevel;
  if (typeof level !== 'number' || !Number.isInteger(level) || level < 1 || level > 10)
    throw new Error('The shared character has an unsupported level (expected 1-10). Nothing was imported.');
  const options = selected(/^core\/selection\/level-up\/generic-option\/index\.eng$/);
  const children = (e: DemiplaneSelection) => all.filter(child => child.args.parentEngine === e.key);
  const descendants = (e: DemiplaneSelection): DemiplaneSelection[] => {
    const found = new Set<DemiplaneSelection>();
    let parents = [e];
    while (parents.length) {
      const next = parents.flatMap(children).filter(child => child !== e && !found.has(child));
      next.forEach(child => found.add(child));
      parents = next;
    }
    return [...found];
  };
  for (let next = 2; next <= level; next++) {
    const rec: LevelUpRecord = { level: next, advancements: [] };
    if ([2, 5, 8].includes(next)) {
      rec.experience = text(scalar(`character_experience_${[2, 5, 8].indexOf(next) + 2}_name`));
    }
    const regular = cards.filter(e => e.args.sourceRow === `level-${next}-domain-card`);
    if (regular.length > 1) issues.push(`Level ${next}: multiple regular domain-card choices were found; only the first matched card was used.`);
    rec.card = regular.flatMap(e => matchedCards.get(e) ?? [])[0];
    for (const option of options.filter(e => Number(e.args.level) === next)) {
      use(option);
      const name = normalize(title(option));
      const details = children(option);
      let advancement: Advancement | undefined;
      if (name === 'increase traits') {
        const traits = details.filter(e => /^character_trait_\w+_value--/.test(e.name) && e.value === 1).map(e => {
          use(e);
          return TRAIT_ORDER.find(t => e.name.startsWith(`character_trait_${t.toLowerCase()}_value--`));
        }).filter((t): t is TraitName => !!t);
        details.filter(e => e.name.endsWith('level-up-option-increase-traits-current')).forEach(use);
        if (traits.length === 2) advancement = { type: 'traits', traits };
        else issues.push(`Level ${next}: Increase Traits did not identify two traits; the increase was not applied.`);
      } else if (name === 'choose domain card') {
        const extra = details.filter(e => matchedCards.has(e));
        if (extra.length === 1) advancement = { type: 'card', card: matchedCards.get(extra[0]) };
        else issues.push(`Level ${next}: the extra domain card could not be identified; it was not added.`);
      } else if (name === 'increase experiences') {
        const increases = all.filter(e => /^character_experience_\d+_value--/.test(e.name)
          && e.name.endsWith(`--${option.key}`) && e.value === 1);
        const indices = increases.map(e => Number(/^character_experience_(\d+)_value--/.exec(e.name)![1]));
        if (new Set(indices).size === 2 && indices.every(i => i < c.experiences.length + (rec.experience ? 1 : 0))) {
          increases.forEach(use);
          details.filter(e => e.name.endsWith('level-up-option-increase-experiences-current')).forEach(use);
          advancement = { type: 'experiences', experiences: indices };
        } else issues.push(`Level ${next}: Increase Experiences did not identify two valid Experiences; the increases were not applied.`);
      } else if (name === 'multiclass') {
        const nested = descendants(option);
        const multiClass = unique(nested.filter(e => /^tabula\/class\//.test(e.name)), `Level ${next} multiclass`);
        if (multiClass) {
          const entry = match(data.classes.filter(e => e.kind === 'class'), title(multiClass), `Level ${next} multiclass`);
          const slug = text(multiClass.args.slug) || multiClass.name.split('/').pop()!.replace(/\.eng$/, '');
          const related = descendants(multiClass);
          const foundation = unique(all.filter(e => /^tabula\/subclass\//.test(e.name)
            && (related.includes(e) || e.args.sourceRow === `${slug}-multiclass-subclass-choice`)), `Level ${next} multiclass foundation`);
          const domain = unique(all.filter(e => e.name === 'core/selection/level-up/extra-domain/index.eng'
            && (related.includes(e) || e.args.sourceRow === `${slug}-multiclass-domain-choice`)), `Level ${next} multiclass domain`);
          const subclasses = data.classes.filter(e => e.kind === 'subclass' && e.parent === entry?.name && e.stage === 'foundation');
          const matched = foundation && match(subclasses.map(e => ({ ...e, name: shortSubclass(e.name) })),
            shortSubclass(title(foundation)), `Level ${next} multiclass foundation`);
          const localSubclass = matched && subclasses.find(e => shortSubclass(e.name) === matched.name);
          const localDomain = domain && entry?.domains?.find(d => normalize(d) === normalize(title(domain)));
          if (entry && localSubclass && localDomain) {
            advancement = { type: 'multiclass', multiclass: { className: entry.name, subclass: localSubclass.name, domain: localDomain } };
            classSelections.push({ className: entry.name, engine: multiClass });
          } else issues.push(`Level ${next}: the multiclass foundation or domain could not be matched; multiclass features were not applied.`);
        } else issues.push(`Level ${next}: the multiclass class selection could not be read; multiclass features were not applied.`);
      } else {
        const types: Record<string, Advancement['type']> = {
          'gain hp slot': 'hp', 'gain stress slot': 'stress', 'increase evasion': 'evasion',
          'upgrade subclass': 'subclass', 'increase proficiency': 'proficiency',
        };
        if (types[name]) advancement = { type: types[name] };
        else issues.push(`Level ${next}: "${title(option)}" is not a supported advancement and was not applied. Review this level manually.`);
      }
      if (advancement) rec.advancements.push(advancement);
    }
    for (const problem of levelUpProblems(data, c, rec)) issues.push(`Level ${next}: ${problem}`);
    try { applyLevelUp(data, c, rec); }
    catch (error) { throw new Error(`Level ${next} cannot be recreated safely: ${(error as Error).message} Nothing was imported.`); }
  }
  syncBackgroundDetails(data.classes, c, {
    ...description,
    ...importClassBackgroundDetails(data.classes, c, all, classSelections, consumed),
  });
  if (c.mancer?.choices) c.mancer.choices.backgroundDetails = { ...c.description };
  for (const card of cards.filter(e => !ch.cards.includes(matchedCards.get(e) ?? ''))) {
    const name = matchedCards.get(card);
    if (name && ![...c.loadout, ...c.vault].some(e => e.name === name))
      issues.push(`Domain card "${name}" has an unsupported acquisition record and was not added. Add it from the compendium if still owned.`);
  }
  c.activeWeapons = [];
  c.carriedWeapons = [];
  c.carriedArmor = [];
  delete c.activeArmor;
  c.inventory = [];
  const equipment = selected(/^tabula\/equipment\//);
  const wrappers = selected(/^core\/selection\/equipment\/starting-class\/index\.eng$/);
  const custom = selected(/^core\/selection\/equipment\/custom\/index\.eng$/);
  const primary = text(scalar('character_hand_primary_equipped-id'));
  const secondary = text(scalar('character_hand_secondary_equipped-id'));
  const both = text(scalar('character_hand_both_equipped-id'));
  const equipIds = new Set([primary, secondary, both].filter(v => v && v !== 'na'));
  for (const e of equipment) {
    use(e);
    const wrapper = wrappers.find(w => w.key === e.args.parentEngine);
    if (wrapper) use(wrapper);
    const name = wrapper ? title(wrapper) : title(e);
    const entries = [...data.weapons, ...data.armor, ...data.consumables, ...items];
    const entry = match(entries, name, 'Equipment');
    const equipped = equipIds.has(e.key);
    const armorEquipped = scalar(`${e.key}-is-equipped`) === 1;
    if (!entry) {
      c.inventory.push({ name, type: 'gear', qty: 1, note: 'Imported without a matching catalog entry; mechanics need manual setup.' });
    } else if (entry.weapon) {
      const weapon = weaponFrom(entry);
      if (equipped && (ignoresWeaponBurden(c) || handsUsed(c) + weapon.hands <= 2)
          && !c.activeWeapons.some(w => w.slot === weapon.slot)) {
        c.activeWeapons.push(weapon);
      } else {
        c.carriedWeapons.push(weapon);
        if (equipped) issues.push(`Equipment: "${entry.name}" conflicts with the local weapon slots or hand limit and was moved to carried weapons.`);
      }
      if (equipped) equipIds.delete(e.key);
    } else if (entry.armor) {
      if (armorEquipped && !c.activeArmor) c.activeArmor = armorFrom(entry);
      else {
        c.carriedArmor.push(armorFrom(entry));
        if (armorEquipped) issues.push(`Equipment: multiple armors were marked equipped; "${entry.name}" was moved to carried armor.`);
      }
    } else addToSheet(c, data.consumables.includes(entry) ? 'consumables' : 'items', entry);
  }
  for (const id of equipIds) issues.push(`An equipped weapon (${id}) could not be matched to imported equipment. Review the equipped weapons.`);
  for (const e of custom) {
    use(e);
    const wrapper = wrappers.find(w => w.key === e.args.parentEngine);
    if (wrapper) use(wrapper);
    const name = title(e);
    const entry = itemMatches(items, name);
    if (entry.length === 1) addToSheet(c, 'items', entry[0]);
    else {
      c.inventory.push({ name, type: 'gear', qty: 1 });
      issues.push(`Custom item "${name}" was kept as plain inventory without automation${entry.length > 1 ? ' (ambiguous catalog name)' : ''}.`);
    }
  }
  if (equipment.length || custom.length) {
    issues.push('Item quantities were not present in the supported equipment records. Each saved item was imported as one; review quantities and any item modifications.');
    reportIssues.push('Item quantities could not be verified. Each item was imported as one; review quantities.');
  }
  const gold = ['handfuls', 'bags', 'chests'].map(unit => scalar(`character_gold_playtest_${unit}_current`));
  c.gold = gold.every(value => value === undefined) ? c.gold : gold.reduce<number>((sum, value, i) => {
    if (value === undefined) return sum;
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`Invalid saved gold amount (${['handfuls', 'bags', 'chests'][i]}). Nothing was imported.`);
    const total = sum + value * 10 ** (i + 1);
    if (!Number.isSafeInteger(total)) throw new Error('The saved gold amount is too large to import safely. Nothing was imported.');
    return total;
  }, 0);
  syncDerived(c);
  for (const [field, track, maximum] of [
    ['character_health_current', c.hp, c.hp.slots],
    ['character_stress_current', c.stress, c.stress.slots],
    ['character_hope_current', c.hope, c.hope.slots],
    ['character_armor_slots_current', c.armorTrack, derivedStats(c).armorSlots],
  ] as const) {
    const value = scalar(field);
    if (typeof value === 'number' && Number.isInteger(value) && value >= 0) {
      track.on = Math.min(value, maximum);
      if (value > maximum) issues.push(`${field}: saved value ${value} exceeded the local maximum ${maximum} and was reduced.`);
    } else issues.push(`${field}: no supported saved value; the new-character default was used.`);
  }
  const scars = scalar('character_scars_current');
  if (scars !== undefined && scars !== 0)
    issues.push(`Demiplane records ${String(scars)} scars. Death/scar benefits were not imported; review them manually.`);
  issues.push('Features, equipment mechanics and derived stats use the local catalogs, not Demiplane overrides. Active effects, limited-use counters and manual stat overrides are not transferred.');
  if (all.some(e => e.name === 'character_avatar') && !source.portrait)
    reportIssues.push(source.portraitIssue ?? 'The Demiplane portrait could not be downloaded. The actor keeps its current token artwork.');
  if (cards.length > 5) {
    const warning = 'The active loadout could not be verified. The first five acquired matched cards are active and the rest are in the vault; review your loadout.';
    issues.push(warning);
    reportIssues.push(warning);
  }
  const noLimits = scalar('class-no-limits');
  if (noLimits) issues.push('Demiplane has unrestricted class options enabled. Only recognized saved choices were applied; review local rule differences.');
  const ignored = /^(core\/character\.eng|getting-started-name-current|character_avatar|character_class_spellbook-type|edit-permission|view-permission|sheet-.*|tab-.*|header-button-.*)$/;
  for (const e of all.filter(e => !consumed.has(e) && !ignored.test(e.name))) {
    const effect = /^effect-value--(.+)-toggle$/.exec(e.name);
    if (effect && e.value === 0) continue;
    const label = effect ? `Effect "${effect[1].replace(/-/g, ' ')}"` : `Saved selection "${title(e)}"`;
    issues.push(`Not transferred: ${label}${e.value !== undefined ? ` (${JSON.stringify(e.value).slice(0, 160)})` : ''}. `
      + `Its Demiplane field "${e.name}" is not supported. Review this selection in Demiplane and recreate it manually if needed.`);
  }
  return { character: c, issues: [...new Set(issues)], reportIssues: [...new Set(reportIssues)] };
}
