// Character creation and level-up rules (Daggerheart SRD 1.0). Pure functions over the compendium data and the sheet,
// so the Character Mancer and the Level-up guide stay thin and the rules can be unit-tested without a DOM.

import {
  Advancement, AdvancementType, CharacterSheet, CreationChoices, Feature, LevelUpRecord, TRAIT_ORDER, TraitName, emptyCharacter, tierOf,
} from './data';
import { CompendiumEntry, addToSheet, armorFrom, cardFrom, fetchCompendiumCategory, weaponFrom } from './compendium';
import { Automation, syncDerived } from './engine';
import { CLASS_GUIDES, ClassGuide, GENERIC_GUIDE } from './guide';
import { martialStances, stanceProblems } from './brawler';
import { applyCompanionTraining, buildCompanion, companionChoiceProblems, companionRules, companionTrainingProblems } from './companion';
import type { CompanionRules } from './companion';

export interface RulesData {
  classes: CompendiumEntry[];
  ancestries: CompendiumEntry[];
  communities: CompendiumEntry[];
  weapons: CompendiumEntry[];
  armor: CompendiumEntry[];
  consumables: CompendiumEntry[];
  cards: CompendiumEntry[];
  /** Optional Hope & Fear transformations; empty when the data folder has none. */
  transformations: CompendiumEntry[];
}

export async function loadRulesData(): Promise<RulesData> {
  const get = async (name: string) => (await fetchCompendiumCategory(name)).entries;
  const [classes, ancestries, communities, weapons, armor, consumables, cards] = await Promise.all(
    ['classes', 'ancestries', 'communities', 'weapons', 'armor', 'consumables', 'domain-cards'].map(get),
  );
  const transformations = await get('transformations').catch(() => [] as CompendiumEntry[]);
  return { classes, ancestries, communities, weapons, armor, consumables, cards, transformations };
}

const TRANSFORMATION_SOURCE = 'Transformation - ';

/** Gives the character one transformation (or none for ''); a character can only have one, so any previous one is replaced. Its features are always active and are not domain cards. */
export function setTransformation(d: RulesData, c: CharacterSheet, name: string, ancestryFeature = c.ancestryFeature): void {
  const entry = name ? d.transformations.find((e) => e.name === name) : undefined;
  const single = entry?.features?.some((f) => f.automation?.actions?.some((a) => a.ancestryChoice));
  if (single && !ancestryFeatureChoices(d.ancestries, c.ancestry).some((f) => f.name === ancestryFeature)) throw new Error('Choose the ancestry feature to retain.');
  if (single || c.ancestryFeature) replaceAncestry(d.ancestries, c, c.ancestry, single ? ancestryFeature : undefined);
  if (c.transformation !== entry?.name) {
    const old = c.heritage.filter((f) => f.source.startsWith(TRANSFORMATION_SOURCE)).map((f) => f.name);
    c.effects = c.effects.filter((e) => !old.some((name) => e.source === name || e.source?.startsWith(`${name} - `)));
  }
  c.heritage = c.heritage.filter((f) => !f.source.startsWith(TRANSFORMATION_SOURCE));
  c.transformation = entry?.name;
  if (entry?.automation) c.heritage.push({ source: `${TRANSFORMATION_SOURCE}${entry.name}`, name: entry.name, text: entry.text ?? '', automation: entry.automation });
  for (const f of entry?.features ?? []) c.heritage.push(toFeature(`${TRANSFORMATION_SOURCE}${entry!.name}`, f));
  if (c.mancer?.choices) c.mancer.choices.transformation = entry?.name;
}

export function ancestryFeatureChoices(entries: CompendiumEntry[], ancestry: string): Feature[] {
  const names = ancestry.split(' / ');
  return names.flatMap((name, i) => {
    const entry = entries.find((e) => e.name === name);
    return (entry?.features ?? []).flatMap((f, j) => names.length > 1 && i !== j ? []
      : [toFeature(`Ancestry - ${name} (${j === 0 ? 'first' : 'second'})`, f)]);
  });
}

export function replaceAncestry(entries: CompendiumEntry[], c: CharacterSheet, ancestry: string, feature?: string): void {
  const choices = ancestryFeatureChoices(entries, ancestry);
  if (!choices.length || (feature !== undefined && !choices.some((f) => f.name === feature))) throw new Error('Choose a valid ancestry and feature.');
  const next = feature ? choices.filter((f) => f.name === feature) : choices;
  const entry = entries.find((e) => e.name === ancestry);
  if (!feature && entry?.automation) next.push({ source: `Ancestry - ${ancestry}`, name: entry.name, text: entry.text ?? '', automation: entry.automation });
  const old = c.heritage.filter((f) => f.source.startsWith('Ancestry - '));
  const removed = old.filter((f) => !next.some((n) => n.name === f.name)).map((f) => f.name);
  c.effects = c.effects.filter((e) => !removed.some((name) => e.source === name || e.source?.startsWith(`${name} - `)));
  const grant = (features: Feature[], stat: 'hp' | 'stress' | 'evasion') => features.reduce((n, f) => n + (f.automation?.creation?.[stat] ?? 0), 0);
  for (const stat of ['hp', 'stress', 'evasion'] as const) {
    const delta = grant(next, stat) - grant(old, stat);
    if (stat === 'evasion') c.evasion += delta;
    else {
      c[stat].slots = Math.max(1, Math.min(c[stat].total, c[stat].slots + delta));
      c[stat].on = Math.min(c[stat].on, c[stat].slots);
    }
  }
  c.heritage = [...c.heritage.filter((f) => !f.source.startsWith('Ancestry - ')), ...next];
  c.ancestry = ancestry;
  c.ancestryFeature = feature;
  if (c.testingBuild) {
    const [first, second] = ancestry.split(' / ');
    Object.assign(c.testingBuild, { ancestry: first, ancestry2: second, ancestryFeature: feature });
  }
}

export const TRAIT_VALUES = [2, 1, 1, 0, 0, -1];
export const MAX_LEVEL = 10;
export const MAX_TRACK = 12;
export const LOADOUT_SIZE = 5;

export function guideFor(className: string): ClassGuide {
  return CLASS_GUIDES[className] ?? GENERIC_GUIDE;
}

export const classEntries = (d: RulesData) => d.classes.filter((e) => e.kind === 'class').sort((a, b) => a.name.localeCompare(b.name));

export const findClass = (d: RulesData, name: string) => d.classes.find((e) => e.kind === 'class' && e.name === name);

/** Subclass names (compendium names such as "Ranger: Wayfinder") that have a Foundation card. */
export function subclassesOf(d: RulesData, className: string): string[] {
  return [...new Set(d.classes.filter((e) => e.kind === 'subclass' && e.parent === className && e.stage === 'foundation').map((e) => e.name))].sort();
}

export type Stage = 'foundation' | 'specialization' | 'mastery';
export const STAGES: Stage[] = ['foundation', 'specialization', 'mastery'];

export const subclassCard = (d: RulesData, name: string, stage: Stage) => d.classes.find((e) => e.kind === 'subclass' && e.name === name && e.stage === stage);

/** "Ranger: Wayfinder" -> "Wayfinder" */
export const shortSubclass = (name: string) => name.replace(/^[^:]+:\s*/, '');

export function startingCards(d: RulesData, choices: string | CreationChoices): CompendiumEntry[] {
  const domains = typeof choices === 'string' ? findClass(d, choices)?.domains ?? [] : creationAllowances(d, choices).domains;
  return d.cards.filter((e) => e.level === 1 && !!e.domain && domains.includes(e.domain)).sort((a, b) => a.domain!.localeCompare(b.domain!) || a.name.localeCompare(b.name));
}

function creationEntries(d: RulesData, ch: CreationChoices): CompendiumEntry[] {
  return [
    findClass(d, ch.className), subclassCard(d, ch.subclass, 'foundation'),
    d.communities.find((e) => e.name === ch.community),
    d.transformations.find((e) => e.name === ch.transformation),
  ].filter((e): e is CompendiumEntry => !!e);
}

function wholeAncestry(d: RulesData, ch: CreationChoices): CompendiumEntry | undefined {
  if (ch.transformation === 'Shapeshifter') return undefined;
  return !ch.ancestry2 || ch.ancestry2 === ch.ancestry ? d.ancestries.find((e) => e.name === ch.ancestry) : undefined;
}

function ancestryFeatures(d: RulesData, ch: CreationChoices) {
  if (ch.transformation === 'Shapeshifter') return ancestryFeatureChoices(d.ancestries, ch.ancestry2 && ch.ancestry2 !== ch.ancestry ? `${ch.ancestry} / ${ch.ancestry2}` : ch.ancestry).filter((f) => f.name === ch.ancestryFeature);
  const first = d.ancestries.find((e) => e.name === ch.ancestry);
  const second = d.ancestries.find((e) => e.name === (ch.ancestry2 || ch.ancestry));
  return [first?.features?.[0], second?.features?.[1]];
}

/** Creation grants are evaluated from the selected sources, never feature names or already-built sheets. */
export function creationAllowances(d: RulesData, ch: CreationChoices): { hp: number; stress: number; evasion: number; domainCards: number; experienceBonus: number; domains: string[] } {
  const entries = creationEntries(d, ch);
  const sources = [...entries.flatMap((e) => [e.automation, ...(e.features ?? []).map((f) => f.automation)]),
    wholeAncestry(d, ch)?.automation, ...ancestryFeatures(d, ch).map((f) => f?.automation)];
  const out = { hp: 0, stress: 0, evasion: 0, domainCards: 2, experienceBonus: 0, domains: [...(findClass(d, ch.className)?.domains ?? [])] };
  for (const a of sources) {
    const grant = a?.creation;
    if (!grant) continue;
    for (const stat of ['hp', 'stress', 'evasion', 'domainCards', 'experienceBonus'] as const) {
      const value = grant[stat];
      if (typeof value === 'number' && Number.isFinite(value)) out[stat] += Math.trunc(value);
    }
    out.domains.push(...(grant.domains ?? []));
  }
  out.domainCards = Math.max(0, out.domainCards);
  out.experienceBonus = Math.max(0, out.experienceBonus);
  out.domains = [...new Set(out.domains)];
  return out;
}

export function creationCardProblems(d: RulesData, ch: CreationChoices): string[] {
  const p: string[] = [];
  const count = creationAllowances(d, ch).domainCards;
  if (ch.cards.length !== count) p.push(`Choose ${count} domain cards.`);
  if (new Set(ch.cards).size !== ch.cards.length) p.push('Choose different domain cards; duplicates are not allowed.');
  const eligible = new Set(startingCards(d, ch).map((e) => e.name));
  if (ch.cards.some((name) => !eligible.has(name))) p.push('Choose only level 1 domain cards from your available domains.');
  return p;
}

/** Tier 1 gear offered at creation. */
export const tier1 = (list: CompendiumEntry[]) => list.filter((e) => (e.tier ?? 1) === 1);

/** Gear flagged "starter" in the data; falls back to tier 1 for data files without the flag. */
export const starterGear = (list: CompendiumEntry[]) => {
  const flagged = list.filter((e) => e.starter);
  return flagged.length ? flagged : tier1(list);
};

export function experienceProblems(names: string[]): string[] {
  return names.some((n) => !n.trim()) || names.length < 2 ? ['Write two Experiences.'] : [];
}

export function creationExperienceProblems(d: RulesData, ch: CreationChoices): string[] {
  const p = experienceProblems(ch.experiences);
  if (creationAllowances(d, ch).experienceBonus > 0) {
    const index = ch.experienceBonusIndex;
    if (index === undefined || !Number.isInteger(index) || index < 0 || !ch.experiences[index]?.trim()) {
      p.push('Choose an Experience for the permanent creation bonus.');
    }
  }
  return p;
}

export function traitsValid(t: Record<TraitName, number>): boolean {
  const got = TRAIT_ORDER.map((n) => t[n]).sort((a, b) => b - a);
  return got.length === TRAIT_VALUES.length && got.every((v, i) => v === TRAIT_VALUES[i]);
}

export function suggestedTraits(guide: ClassGuide): Record<TraitName, number> {
  const out = {} as Record<TraitName, number>;
  TRAIT_ORDER.forEach((n, i) => { out[n] = guide.traits[i] ?? 0; });
  return out;
}

export function emptyChoices(): CreationChoices {
  return {
    className: '', subclass: '', ancestry: '', community: '', traits: suggestedTraits(GENERIC_GUIDE), primary: '', armor: '', potion: 'health',
    classItem: '', experiences: ['', ''], cards: [], background: [], connections: [], name: '', pronouns: '', description: '',
  };
}

/** Why the choices cannot be turned into a character yet, or an empty list. */
export function creationProblems(d: RulesData, ch: CreationChoices): string[] {
  const p: string[] = [];
  if (!findClass(d, ch.className)) p.push('Choose a class.');
  if (!ch.subclass) p.push('Choose a subclass.');
  const elements = subclassCard(d, ch.subclass, 'foundation')?.features?.flatMap(f => f.automation?.elementChoice ?? []) ?? [];
  if (elements.length && !elements.includes(ch.element ?? '')) p.push('Choose your Elementalist element.');
  const companion = creationCompanionRules(d, ch);
  if (companion) p.push(...(ch.companion ? companionChoiceProblems(ch.companion) : ['Set up your companion.']));
  const stances = subclassCard(d, ch.subclass, 'foundation')?.features?.flatMap(f => f.automation?.stances ?? []) ?? [];
  if (stances.length) p.push(...stanceProblems(stances, ch.stances ?? [], 2, 1));
  if (!ch.ancestry || !ch.community) p.push('Choose an ancestry and a community.');
  if (ch.transformation === 'Shapeshifter' && !ancestryFeatureChoices(d.ancestries, ch.ancestry2 && ch.ancestry2 !== ch.ancestry ? `${ch.ancestry} / ${ch.ancestry2}` : ch.ancestry).some((f) => f.name === ch.ancestryFeature)) p.push('Choose the ancestry feature to retain.');
  if (!traitsValid(ch.traits)) p.push('Assign +2, +1, +1, 0, 0 and -1 to your six traits.');
  const primary = d.weapons.find((e) => e.name === ch.primary);
  if (!primary) p.push('Choose a primary weapon.');
  else if (primary.weapon?.burden && /two/i.test(primary.weapon.burden) && ch.secondary) p.push('A two-handed weapon leaves no hand for a secondary weapon.');
  if (!d.armor.some((e) => e.name === ch.armor)) p.push('Choose armor.');
  p.push(...creationExperienceProblems(d, ch));
  p.push(...creationCardProblems(d, ch));
  return p;
}

function toFeature(source: string, f: { name: string; text: string; type?: string; automation?: Automation }): Feature {
  const hope = f.type === 'hope';
  const cost = hope ? Number(/spend (\d+) hope/i.exec(f.text)?.[1] ?? 3) : undefined;
  return { source, name: f.name, text: f.text, hope: hope || undefined, cost, automation: f.automation };
}

function markSpellcast(c: CharacterSheet, trait?: string): void {
  if (!trait || c.traits.some((t) => t.spellcast)) return;
  const t = c.traits.find((x) => x.name === trait);
  if (t) t.spellcast = true;
}

/** Builds the level 1 sheet from the Mancer choices (the SRD creation steps). Keeps free-form data of an earlier build. */
export function buildCharacter(d: RulesData, ch: CreationChoices, prev?: CharacterSheet): CharacterSheet {
  if (ch.transformation === 'Shapeshifter' && !ancestryFeatures(d, ch).length) throw new Error('Choose the ancestry feature to retain.');
  const cardProblems = creationCardProblems(d, ch);
  if (!prev?.testingCharacter && cardProblems.length) throw new Error(cardProblems.join(' '));
  const experienceSelectionProblems = creationExperienceProblems(d, ch);
  if (creationAllowances(d, ch).experienceBonus > 0 && experienceSelectionProblems.length) throw new Error(experienceSelectionProblems.join(' '));
  const name = ch.name.trim() || prev?.name || 'Unnamed hero';
  const c = emptyCharacter(name);
  const cls = findClass(d, ch.className);
  if (!cls) throw new Error(`Unknown class ${ch.className}`);
  c.className = cls.name;
  const grants = creationAllowances(d, ch);
  c.domains = grants.domains;
  c.evasion = (cls.startingEvasion ?? 10) + grants.evasion;
  c.hp.slots = Math.min(MAX_TRACK, Math.max(1, (cls.startingHp ?? 6) + grants.hp));
  c.stress.slots = Math.min(MAX_TRACK, Math.max(1, c.stress.slots + grants.stress));
  c.pronouns = ch.pronouns;
  c.player = prev?.player ?? '';
  c.notes = prev?.notes ?? '';
  c.otherDetails = ch.description;
  c.description.Name = name;
  c.description.Pronouns = ch.pronouns;
  if (prev) for (const k of Object.keys(prev.description)) if (!['Name', 'Pronouns'].includes(k)) c.description[k] = prev.description[k];
  for (const t of c.traits) t.mod = ch.traits[t.name] ?? 0;

  for (const f of cls.features ?? []) c.features.push(toFeature(f.type === 'hope' ? 'Hope feature' : `Class feature - ${cls.name}`, f));
  const foundation = subclassCard(d, ch.subclass, 'foundation');
  c.subclass = shortSubclass(ch.subclass);
  for (const f of foundation?.features ?? []) c.features.push(toFeature(`Subclass foundation - ${c.subclass}`, f));
  if (foundation?.features?.some(f => f.automation?.elementChoice)) {
    const elements = foundation.features.flatMap(f => f.automation?.elementChoice ?? []);
    if (!elements.includes(ch.element ?? '') && !prev?.testingCharacter) throw new Error('Choose your Elementalist element.');
    c.element = ch.element;
  }
  if (martialStances(c).length) {
    const errors = stanceProblems(martialStances(c), ch.stances ?? [], 2, 1);
    if (errors.length && !prev?.testingCharacter) throw new Error(errors.join(' '));
    c.knownStances = [...(ch.stances ?? [])];
  }
  markSpellcast(c, foundation?.spellcastTrait);
  const companion = companionRules(c);
  if (companion && !prev?.testingCharacter) {
    const problems = ch.companion ? companionChoiceProblems(ch.companion) : ['Set up your companion.'];
    if (problems.length) throw new Error(problems.join(' '));
    c.companion = buildCompanion(ch.companion!, companion);
  }

  c.ancestry = ch.ancestry2 && ch.ancestry2 !== ch.ancestry ? `${ch.ancestry} / ${ch.ancestry2}` : ch.ancestry;
  ancestryFeatures(d, ch).forEach((f, i) => {
    if (!f) return;
    const label = i === 0 ? 'first' : 'second';
    const feature = 'source' in f ? f : toFeature(`Ancestry - ${i === 0 ? ch.ancestry : ch.ancestry2 || ch.ancestry} (${label})`, f);
    c.heritage.push(feature);
  });
  const ancestry = wholeAncestry(d, ch);
  if (ancestry?.automation) c.heritage.push({ source: `Ancestry - ${ancestry.name}`, name: ancestry.name, text: ancestry.text ?? '', automation: ancestry.automation });
  c.community = ch.community;
  for (const f of d.communities.find((e) => e.name === ch.community)?.features ?? []) c.heritage.push(toFeature(`Community - ${ch.community}`, f));

  setTransformation(d, c, ch.transformation ?? '', ch.ancestryFeature);
  for (const entry of creationEntries(d, ch).filter((e) => e !== d.transformations.find((t) => t.name === ch.transformation))) {
    if (entry.automation) c.features.push({ source: `Creation - ${entry.name}`, name: entry.name, text: entry.text ?? '', automation: entry.automation });
  }

  const primary = d.weapons.find((e) => e.name === ch.primary);
  const secondary = ch.secondary ? d.weapons.find((e) => e.name === ch.secondary) : undefined;
  if (primary?.weapon) c.activeWeapons.push(weaponFrom(primary));
  if (secondary?.weapon) c.activeWeapons.push(weaponFrom(secondary));
  const armor = d.armor.find((e) => e.name === ch.armor);
  if (armor?.armor) c.activeArmor = armorFrom(armor);

  c.gold = 10;
  c.inventory.push(
    { name: 'Torch', type: 'gear', qty: 1 },
    { name: '50 feet of rope', type: 'gear', qty: 1 },
    { name: 'Basic supplies', type: 'gear', qty: 1 },
  );
  const potion = d.consumables.find((e) => e.name === (ch.potion === 'stamina' ? 'Minor Stamina Potion' : 'Minor Health Potion'));
  if (potion) addToSheet(c, 'consumables', potion);
  if (ch.classItem.trim()) c.inventory.push({ name: ch.classItem.trim(), type: 'class item', qty: 1 });

  c.experiences = ch.experiences.map((n, i) => ({
    name: n.trim(), mod: 2 + (i === ch.experienceBonusIndex ? grants.experienceBonus : 0),
  })).filter((e) => e.name);
  for (const n of ch.cards) {
    const card = d.cards.find((e) => e.name === n && e.level === 1);
    if (card) (c.loadout.length < LOADOUT_SIZE ? c.loadout : c.vault).push(cardFrom(card, c));
  }
  const qa = (l: Array<{ question: string; answer: string }>) => l.map((x) => (x.answer.trim() ? `${x.question}\n${x.answer.trim()}` : x.question));
  c.backgroundQuestions = qa(ch.background);
  c.connections = qa(ch.connections);

  c.mancer = { done: true, choices: structuredClone(ch) };
  syncDerived(c);
  return c;
}

// ---- level up ----

interface SlotRule { perTier: [number, number, number]; cost: number }

/** Advancement slots per tier 2, 3 and 4. Subclass and multiclass share one slot per tier (see advancementLeft). */
export const ADVANCEMENT_RULES: Record<AdvancementType, SlotRule> = {
  traits: { perTier: [3, 3, 3], cost: 1 },
  hp: { perTier: [2, 2, 2], cost: 1 },
  stress: { perTier: [2, 2, 2], cost: 1 },
  experiences: { perTier: [1, 1, 1], cost: 1 },
  card: { perTier: [1, 1, 1], cost: 1 },
  evasion: { perTier: [1, 1, 1], cost: 1 },
  subclass: { perTier: [0, 1, 1], cost: 1 },
  proficiency: { perTier: [0, 1, 1], cost: 2 },
  multiclass: { perTier: [0, 1, 1], cost: 2 },
  combo: { perTier: [1, 1, 1], cost: 1 },
};

export const ADVANCEMENT_TYPES = Object.keys(ADVANCEMENT_RULES) as AdvancementType[];

export function advancementCost(t: AdvancementType): number {
  return ADVANCEMENT_RULES[t].cost;
}

export function usedAdvancements(c: CharacterSheet): Record<AdvancementType, number> {
  const used = Object.fromEntries(ADVANCEMENT_TYPES.map((t) => [t, 0])) as Record<AdvancementType, number>;
  for (const l of c.levelUps ?? []) for (const a of l.advancements) used[a.type]++;
  return used;
}

export function subclassStageOf(c: CharacterSheet): Stage {
  const n = (c.levelUps ?? []).flatMap((l) => l.advancements).filter((a) => a.type === 'subclass').length;
  return STAGES[Math.min(n, 2)];
}

/** How often an advancement can still be picked when reaching {@code level} (the tier of that level and below). */
export function advancementLeft(c: CharacterSheet, type: AdvancementType, level: number, pending: Advancement[] = []): number {
  const tier = tierOf(level);
  const used = usedAdvancements(c);
  if (type === 'combo') {
    if (!c.features.some(f => f.automation?.rollOptions?.some(o => o.combo))) return 0;
    const currentTier = tierOf(level);
    const taken = (c.levelUps ?? []).filter(l => tierOf(l.level) === currentTier).flatMap(l => l.advancements).filter(a => a.type === 'combo').length;
    return Math.max(0, 1 - taken - pending.filter(a => a.type === 'combo').length);
  }
  for (const a of pending) used[a.type]++;
  const slots = (t: AdvancementType) => ADVANCEMENT_RULES[t].perTier.slice(0, Math.max(0, tier - 1)).reduce((a, b) => a + b, 0) - used[t];
  if (type === 'subclass' || type === 'multiclass') {
    const pool = ADVANCEMENT_RULES.subclass.perTier.slice(0, Math.max(0, tier - 1)).reduce((a, b) => a + b, 0) - used.subclass - used.multiclass;
    if (type === 'subclass') return Math.max(0, Math.min(pool, 2 - used.subclass));
    return Math.max(0, Math.min(pool, 1 - used.multiclass));
  }
  return Math.max(0, slots(type));
}

export function achievementsAt(level: number): { experience: boolean; clearTraits: boolean } {
  return { experience: [2, 5, 8].includes(level), clearTraits: [5, 8].includes(level) };
}

/** Domain cards that can be taken on reaching {@code level}: class domains up to the level, multiclass domains up to half of it. */
export function levelUpCards(d: RulesData, c: CharacterSheet, level: number, pending: Advancement[] = []): CompendiumEntry[] {
  const own = findClass(d, c.className)?.domains ?? [];
  const multi = [...(c.levelUps ?? []).flatMap((l) => l.advancements), ...pending].filter((a) => a.type === 'multiclass' && a.multiclass).map((a) => a.multiclass!.domain);
  const owned = new Set([...c.loadout, ...c.vault].map((x) => x.name));
  for (const a of pending) if (a.type === 'card' && a.card) owned.add(a.card);
  const half = Math.ceil(level / 2);
  return d.cards
    .filter((e) => !!e.domain && !owned.has(e.name) && ((own.includes(e.domain) && (e.level ?? 1) <= level) || (multi.includes(e.domain) && (e.level ?? 1) <= half)))
    .sort((a, b) => (a.level ?? 1) - (b.level ?? 1) || a.domain!.localeCompare(b.domain!) || a.name.localeCompare(b.name));
}

/** Every card the character owns, for the optional swap. */
export function ownedCards(c: CharacterSheet): string[] {
  return [...c.loadout, ...c.vault].map((x) => x.name);
}

export function levelUpProblems(d: RulesData, c: CharacterSheet, rec: LevelUpRecord): string[] {
  const p: string[] = [];
  if (rec.level !== c.level + 1 || rec.level > MAX_LEVEL) p.push('Wrong level.');
  const stanceSelection = levelUpStances(d, c, rec);
  if (stanceSelection.count) p.push(...stanceProblems(stanceSelection.catalog, rec.stances ?? [], stanceSelection.count, tierOf(rec.level), c.knownStances));
  const ach = achievementsAt(rec.level);
  p.push(...companionLevelUpProblems(d, c, rec));
  if (ach.experience && !rec.experience?.trim()) p.push('Write the new Experience.');
  const cost = rec.advancements.reduce((n, a) => n + advancementCost(a.type), 0);
  if (cost !== 2) p.push('Choose advancements worth two points.');
  const picked: Advancement[] = [];
  for (const a of rec.advancements) {
    if (advancementLeft(c, a.type, rec.level, picked) < 1) p.push(`No ${a.type} advancement left in your tiers.`);
    picked.push(a);
    if (a.type === 'traits') {
      const names = new Set(a.traits ?? []);
      if (names.size !== 2 || [...names].some((n) => c.traits.find((t) => t.name === n)?.marked && !(ach.clearTraits))) p.push('Choose two unmarked traits.');
    } else if (a.type === 'experiences') {
      if (new Set(a.experiences ?? []).size !== 2) p.push('Choose two Experiences.');
    } else if (a.type === 'card' && !a.card) p.push('Choose the extra domain card.');
    else if (a.type === 'multiclass' && !(a.multiclass?.className && a.multiclass.domain && a.multiclass.subclass)) p.push('Finish the multiclass choices.');
  }
  if (!rec.card && levelUpCards(d, c, rec.level, rec.advancements).length) p.push('Choose a new domain card.');
  const traitPicks = rec.advancements.flatMap((a) => (a.type === 'traits' ? a.traits ?? [] : []));
  if (new Set(traitPicks).size !== traitPicks.length) p.push('A trait can only be raised once per level.');
  if (rec.swap?.out || rec.swap?.in) {
    const out = [...c.loadout, ...c.vault].find((x) => x.name === rec.swap!.out);
    const inn = d.cards.find((e) => e.name === rec.swap!.in);
    if (!out || !inn || (inn.level ?? 1) > out.level) p.push('A swapped card must be of the same level or lower.');
  }
  return p;
}

export function levelUpStances(d: RulesData, c: CharacterSheet, rec: LevelUpRecord): { catalog: NonNullable<Automation['stances']>; count: number } {
  const catalog = martialStances(c);
  if (catalog.length) return { catalog, count: 1 };
  const foundation = rec.advancements.find(a => a.multiclass)?.multiclass?.subclass;
  const added = foundation ? subclassCard(d, foundation, 'foundation')?.features?.flatMap(f => f.automation?.stances ?? []) ?? [] : [];
  return { catalog: added.filter(s => s.tier === 1), count: added.length ? 2 : 0 };
}

/** Applies a validated level-up record to the sheet and remembers it in the level history. */
export function applyLevelUp(d: RulesData, c: CharacterSheet, rec: LevelUpRecord): void {
  const companionProblems = companionLevelUpProblems(d, c, rec);
  if (companionProblems.length) throw new Error(companionProblems.join(' '));
  const companionCount = companionLevelUpCount(d, c, rec);
  const stanceSelection = levelUpStances(d, c, rec);
  if (stanceSelection.count) {
    const errors = stanceProblems(stanceSelection.catalog, rec.stances ?? [], stanceSelection.count, tierOf(rec.level), c.knownStances);
    if (errors.length) throw new Error(errors.join(' '));
    c.knownStances = [...(c.knownStances ?? []), ...rec.stances!];
  }
  const ach = achievementsAt(rec.level);
  c.level = rec.level;
  if (ach.experience) {
    c.proficiency.on = Math.min(c.proficiency.total, c.proficiency.on + 1);
    if (rec.experience?.trim()) c.experiences.push({ name: rec.experience.trim(), mod: 2 });
  }

  if (ach.clearTraits) for (const t of c.traits) t.marked = false;
  const addCard = (name: string, replace?: string) => {
    const entry = d.cards.find((e) => e.name === name);
    if (!entry) return;
    const card = cardFrom(entry, c);
    if (replace) {
      const inLoadout = c.loadout.findIndex((x) => x.name === replace);
      if (inLoadout >= 0) { c.loadout[inLoadout] = card; return; }
      const inVault = c.vault.findIndex((x) => x.name === replace);
      if (inVault >= 0) { c.vault[inVault] = card; return; }
    }
    if (c.loadout.length < LOADOUT_SIZE) c.loadout.push(card);
    else c.vault.push(card);
  };
  for (const a of rec.advancements) {
    switch (a.type) {
      case 'traits':
        for (const n of a.traits ?? []) {
          const t = c.traits.find((x) => x.name === n);
          if (t) { t.mod += 1; t.marked = true; }
        }
        break;
      case 'hp': c.hp.slots = Math.min(MAX_TRACK, c.hp.slots + 1); break;
      case 'stress': c.stress.slots = Math.min(MAX_TRACK, c.stress.slots + 1); break;
      case 'experiences': for (const i of a.experiences ?? []) if (c.experiences[i]) c.experiences[i].mod += 1; break;
      case 'card': if (a.card) addCard(a.card); break;
      case 'evasion': c.evasion += 1; break;
      case 'proficiency': c.proficiency.on = Math.min(c.proficiency.total, c.proficiency.on + 1); break;
      case 'subclass': {
        const stage = subclassStageOf(c);
        const next = STAGES[STAGES.indexOf(stage) + 1];
        const card = next ? subclassCard(d, `${c.className}: ${c.subclass}`, next) : undefined;
        for (const f of card?.features ?? []) c.features.push(toFeature(`Subclass ${next} - ${c.subclass}`, f));
        break;
      }
      case 'multiclass': {
        const m = a.multiclass;
        const cls = m ? findClass(d, m.className) : undefined;
        if (!m || !cls) break;
        for (const f of (cls.features ?? []).filter((x) => x.type !== 'hope')) c.features.push(toFeature(`Multiclass ${cls.name} - class feature`, f));
        const card = subclassCard(d, m.subclass, 'foundation');
        for (const f of card?.features ?? []) c.features.push(toFeature(`Multiclass ${cls.name} - ${shortSubclass(m.subclass)} foundation`, f));
        markSpellcast(c, card?.spellcastTrait);
        if (!c.domains.includes(m.domain)) c.domains.push(m.domain);
        break;
      }
    }
  }
  if (rec.card) addCard(rec.card);
  if (rec.swap?.out && rec.swap.in) addCard(rec.swap.in, rec.swap.out);
  if (c.companion && companionRules(c)) {
    if (ach.experience) c.companion.experienceStats.push({ name: rec.companionExperience!.trim(), mod: 2 });
    applyCompanionTraining(c, rec.companionTraining ?? [], companionCount);
  }
  (c.levelUps ??= []).push(structuredClone(rec));
  syncDerived(c);
}

export function creationCompanionRules(d: RulesData, ch: CreationChoices): CompanionRules | undefined {
  return subclassCard(d, ch.subclass, 'foundation')?.features?.find(f => f.automation?.companion)?.automation?.companion;
}

export function companionLevelUpCount(d: RulesData, c: CharacterSheet, rec: LevelUpRecord): number {
  if (!companionRules(c)) return 0;
  const next = rec.advancements.some(a => a.type === 'subclass')
    ? STAGES[STAGES.indexOf(subclassStageOf(c)) + 1] : undefined;
  const extra = next ? subclassCard(d, `${c.className}: ${c.subclass}`, next)?.features?.reduce((n, f) => n + (f.automation?.companionTraining ?? 0), 0) ?? 0 : 0;
  return 1 + extra;
}

export function companionLevelUpProblems(d: RulesData, c: CharacterSheet, rec: LevelUpRecord): string[] {
  const rules = companionRules(c);
  if (!rules || !c.companion) return [];
  const pet = structuredClone(c.companion);
  const ach = achievementsAt(rec.level);
  if (ach.experience) {
    if (!rec.companionExperience?.trim()) return ['Write the new companion Experience.'];
    pet.experienceStats.push({ name: rec.companionExperience.trim(), mod: 2 });
  }
  return companionTrainingProblems(rules, pet, rec.companionTraining ?? [], companionLevelUpCount(d, c, rec));
}
