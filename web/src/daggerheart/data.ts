// Daggerheart character sheet model, mock character and pure rule helpers.
// Rules content follows the Daggerheart SRD 1.0 (Darrington Press Community Gaming License).

import type { ActiveEffect, Automation } from './engine';

export type TraitName = 'Agility' | 'Strength' | 'Finesse' | 'Instinct' | 'Presence' | 'Knowledge';

export interface Trait {
  name: TraitName;
  mod: number;
  marked: boolean;
  spellcast?: boolean;
}

export interface Track {
  /** usable slots */
  slots: number;
  /** pips shown (the rest are locked until gained through advancement) */
  total: number;
  /** marked pips */
  on: number;
}

export interface Weapon {
  name: string;
  slot: 'Primary' | 'Secondary';
  damageType: 'phys' | 'magic';
  trait: TraitName;
  range: string;
  damage: string;
  hands: 1 | 2;
  tier: number;
  feature?: { name: string; text: string };
  automation?: Automation;
}

export interface Armor {
  name: string;
  /** Armor Score: usable armor slots */
  score: number;
  /** Base damage thresholds printed on the armor (level is added on top) */
  major?: number;
  severe?: number;
  tier: number;
  feature?: { name: string; text: string };
  automation?: Automation;
}

export interface InventoryItem {
  name: string;
  note?: string;
  type: 'gear' | 'consumable' | 'class item' | 'loot';
  qty: number;
  /** dice expression for a Use button (potions) */
  use?: string;
  capped?: boolean;
  /** Compendium automation: its actions replace the plain dice Use button */
  automation?: Automation;
}

export interface DomainCard {
  level: number;
  domain: string;
  type: 'Ability' | 'Spell' | 'Grimoire';
  name: string;
  text: string;
  recall: number;
  spellcast?: TraitName;
  automation?: Automation;
}

export interface Feature {
  source: string;
  name: string;
  text: string;
  hope?: boolean;
  cost?: number;
  automation?: Automation;
}

export interface Experience {
  name: string;
  mod: number;
}

/** Everything the player decided in the Character Mancer, kept so the choices can be reviewed and edited. */
export interface CreationChoices {
  className: string;
  /** Compendium name of the subclass, e.g. "Ranger: Wayfinder" */
  subclass: string;
  ancestry: string;
  /** Mixed Ancestry: the ancestry that supplies the second feature */
  ancestry2?: string;
  community: string;
  /** Optional transformation (Hope & Fear); a character can only have one */
  transformation?: string;
  traits: Record<TraitName, number>;
  primary: string;
  secondary?: string;
  armor: string;
  potion: 'health' | 'stamina';
  classItem: string;
  experiences: string[];
  cards: string[];
  /** Question and answer, one entry per background question */
  background: Array<{ question: string; answer: string }>;
  connections: Array<{ question: string; answer: string }>;
  name: string;
  pronouns: string;
  description: string;
}

export type AdvancementType = 'traits' | 'hp' | 'stress' | 'experiences' | 'card' | 'evasion' | 'subclass' | 'proficiency' | 'multiclass';

export interface Advancement {
  type: AdvancementType;
  /** traits: the two raised traits */
  traits?: TraitName[];
  /** experiences: indices of the two raised Experiences */
  experiences?: number[];
  /** card: extra domain card (compendium name) */
  card?: string;
  /** multiclass: class, one of its domains and the subclass whose Foundation card is gained */
  multiclass?: { className: string; domain: string; subclass: string };
}

export interface LevelUpRecord {
  /** Level reached */
  level: number;
  /** Tier achievement: the new Experience gained at levels 2, 5 and 8 */
  experience?: string;
  advancements: Advancement[];
  /** Domain card acquired in step four (compendium name) */
  card?: string;
  /** Optional swap of a previously acquired card for another of equal or lower level */
  swap?: { out: string; in: string };
}

export interface CharacterSheet {
  /** Host-owned transfer generation; stale inventory saves are rejected. */
  inventoryRevision?: number;
  /** Character Mancer state; absent for sheets that were never created with it */
  mancer?: { done: boolean; choices?: CreationChoices };
  levelUps?: LevelUpRecord[];
  name: string;
  pronouns: string;
  className: string;
  subclass: string;
  domains: string[];
  ancestry: string;
  community: string;
  /** Optional transformation (Hope & Fear); its features are in `heritage` */
  transformation?: string;
  player: string;
  level: number;
  /** Base Evasion from the class; armor and effects are added by derivedStats */
  evasion: number;
  /** Active buffs and debuffs (Effects tab) */
  effects: ActiveEffect[];
  /** Limited-use counters keyed by source (features, cards, items) */
  usage: Record<string, { used: number; per: NonNullable<Automation['uses']>['per'] }>;
  armorTrack: Track;
  hp: Track;
  stress: Track;
  hope: Track;
  proficiency: Track;
  traits: Trait[];
  experiences: Experience[];
  features: Feature[];
  heritage: Feature[];
  activeWeapons: Weapon[];
  activeArmor?: Armor;
  carriedWeapons: Weapon[];
  carriedArmor: Armor[];
  gold: number;
  inventory: InventoryItem[];
  loadout: DomainCard[];
  vault: DomainCard[];
  description: Record<string, string>;
  otherDetails: string;
  notes: string;
  backgroundQuestions: string[];
  connections: string[];
}

export const TRAIT_ACTIONS: Record<TraitName, string> = {
  Agility: 'Sprint, Leap, Maneuver',
  Strength: 'Lift, Smash, Grapple',
  Finesse: 'Control, Hide, Tinker',
  Instinct: 'Perceive, Sense, Navigate',
  Presence: 'Charm, Perform, Deceive',
  Knowledge: 'Recall, Analyze, Comprehend',
};

export const TRAIT_ORDER: TraitName[] = ['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge'];

/** The class, domain and subclass of every multiclass advancement taken so far. */
export function multiclassesOf(c: CharacterSheet): Array<{ className: string; domain: string; subclass: string }> {
  return (c.levelUps ?? []).flatMap((l) => l.advancements).flatMap((a) => (a.type === 'multiclass' && a.multiclass?.className ? [a.multiclass] : []));
}

export function tierOf(level: number): number {
  if (level <= 1) return 1;
  if (level <= 4) return 2;
  if (level <= 7) return 3;
  return 4;
}

export function handsUsed(c: CharacterSheet): number {
  return c.activeWeapons.reduce((n, w) => n + w.hands, 0);
}

export function signed(n: number): string {
  return n >= 0 ? `+${n}` : `${n}`;
}

export function shortTrait(t: TraitName): string {
  return t.slice(0, 3);
}

export function traitMod(c: CharacterSheet, name: TraitName): number {
  return c.traits.find((t) => t.name === name)?.mod ?? 0;
}

export type Advantage = -1 | 0 | 1;

/** Builds the dice expression the host evaluates: 2d12 plus an optional advantage die and modifier. */
export function dualityExpression(mod: number, adv: Advantage): string {
  let e = '2d12';
  if (adv === 1) e += '+1d6';
  else if (adv === -1) e += '-1d6';
  if (mod > 0) e += `+${mod}`;
  else if (mod < 0) e += `${mod}`;
  return e;
}

export interface DualityResult {
  hope: number;
  fear: number;
  advantage: number;
  mod: number;
  total: number;
  outcome: 'crit' | 'hope' | 'fear';
}

/** Interprets host roll dice (Hope, Fear, optional signed advantage die) for a duality roll. */
export function dualityResult(rolls: number[], total: number): DualityResult {
  const [hope = 0, fear = 0, advantage = 0] = rolls;
  const mod = total - rolls.reduce((a, b) => a + b, 0);
  return { hope, fear, advantage, mod, total, outcome: hope === fear ? 'crit' : hope > fear ? 'hope' : 'fear' };
}

export function dualitySucceeds(r: DualityResult, difficulty: number): boolean {
  return r.outcome === 'crit' || r.total >= difficulty;
}

export const OUTCOME_LABEL = { crit: 'Critical success', hope: 'With Hope', fear: 'With Fear' } as const;

/** A blank level 1 sheet: nothing chosen yet, so the sheet offers the Character Mancer. */
export function emptyCharacter(name: string): CharacterSheet {
  return {
    mancer: { done: false },
    levelUps: [],
    name,
    pronouns: '',
    className: '',
    subclass: '',
    domains: [],
    ancestry: '',
    community: '',
    player: '',
    level: 1,
    evasion: 0,
    effects: [],
    usage: {},
    armorTrack: { slots: 0, total: 12, on: 0 },
    hp: { slots: 0, total: 12, on: 0 },
    stress: { slots: 6, total: 12, on: 0 },
    hope: { slots: 6, total: 6, on: 2 },
    proficiency: { slots: 6, total: 6, on: 1 },
    traits: TRAIT_ORDER.map((n) => ({ name: n, mod: 0, marked: false })),
    experiences: [],
    features: [],
    heritage: [],
    activeWeapons: [],
    carriedWeapons: [],
    carriedArmor: [],
    gold: 0,
    inventory: [],
    loadout: [],
    vault: [],
    description: { Name: name, Pronouns: '', Age: '', Height: '', Eyes: '', Hair: '', Skin: '', Attire: '' },
    otherDetails: '',
    notes: '',
    backgroundQuestions: [],
    connections: [],
  };
}

/** Fills fields missing from a stored sheet (older saves) with the blank defaults. */
export function normalizeSheet(stored: Partial<CharacterSheet>, name: string): CharacterSheet {
  const base = emptyCharacter(name);
  const sheet = { ...base, ...stored } as CharacterSheet;
  if (!stored.mancer) sheet.mancer = { done: true };
  return sheet;
}

export function mockCharacter(): CharacterSheet {
  const flexible = { name: 'Flexible', text: '+1 to Evasion.' };
  const flexibleAuto: Automation = { modifiers: [{ stat: 'evasion', value: 1 }] };
  return {
    name: 'Mira Ashvale',
    pronouns: 'she/her',
    className: 'Ranger',
    subclass: 'Wayfinder',
    domains: ['Bone', 'Sage'],
    ancestry: 'Human',
    community: 'Wildborne',
    player: '',
    level: 3,
    evasion: 12,
    effects: [],
    usage: {},
    armorTrack: { slots: 3, total: 12, on: 1 },
    hp: { slots: 6, total: 12, on: 2 },
    stress: { slots: 6, total: 12, on: 1 },
    hope: { slots: 6, total: 6, on: 4 },
    proficiency: { slots: 6, total: 6, on: 2 },
    traits: [
      { name: 'Agility', mod: 2, marked: false },
      { name: 'Strength', mod: -1, marked: false },
      { name: 'Finesse', mod: 1, marked: true },
      { name: 'Instinct', mod: 1, marked: true, spellcast: true },
      { name: 'Presence', mod: 0, marked: false },
      { name: 'Knowledge', mod: 0, marked: false },
    ],
    experiences: [
      { name: 'Raised by Wolves', mod: 2 },
      { name: 'Pathfinder', mod: 2 },
      { name: 'Fast Learner, always the first to pick up a new trick on the trail', mod: 2 },
    ],
    features: [
      { source: 'Class feature - Ranger', name: 'Class feature name', text: 'Feature text comes from the class data.' },
      { source: 'Hope feature - spend 3 Hope', name: 'Class Hope feature', text: 'Class Hope feature text comes from the class data.', hope: true, cost: 3 },
      { source: 'Subclass foundation - Wayfinder', name: 'Foundation feature (Spellcast: Instinct)', text: 'Foundation feature text and Spellcast trait come from the subclass card.' },
    ],
    heritage: [
      { source: 'Ancestry - Human (first)', name: 'Ancestry feature one', text: 'Text from the ancestry card.' },
      { source: 'Ancestry - Human (second)', name: 'Ancestry feature two', text: 'Ancestry feature text.' },
      { source: 'Community - Wildborne', name: 'Community feature', text: 'Community feature text from the community card.' },
    ],
    activeWeapons: [{ name: 'Shortbow', slot: 'Primary', damageType: 'phys', trait: 'Agility', range: 'Far', damage: '2d6+3', hands: 2, tier: 1 }],
    activeArmor: { name: 'Gambeson Armor', score: 3, major: 5, severe: 11, tier: 1, feature: flexible, automation: flexibleAuto },
    carriedWeapons: [
      { name: 'Dagger', slot: 'Secondary', damageType: 'phys', trait: 'Finesse', range: 'Melee', damage: 'd8+1', hands: 1, tier: 1 },
      {
        name: 'Hunting Knife', slot: 'Primary', damageType: 'phys', trait: 'Agility', range: 'Melee', damage: 'd8+2', hands: 1, tier: 1,
        feature: { name: 'Quick', text: 'Mark Stress to target another creature in range' },
      },
    ],
    carriedArmor: [{ name: 'Leather Armor', score: 3, major: 6, severe: 13, tier: 1, feature: flexible, automation: flexibleAuto }],
    gold: 12,
    inventory: [
      { name: 'Torch', note: 'Starting kit', type: 'gear', qty: 1 },
      { name: '50 feet of rope', type: 'gear', qty: 1 },
      { name: 'Basic supplies', type: 'gear', qty: 1 },
      { name: 'Minor Health Potion', note: 'Clear 1d4 HP', type: 'consumable', qty: 2, automation: { actions: [{ id: 'use', label: 'Drink', steps: [{ op: 'clear', resource: 'hp', amount: '1d4' }] }] } },
      { name: 'Minor Stamina Potion', note: 'Clear 1d4 Stress', type: 'consumable', qty: 5, capped: true, automation: { actions: [{ id: 'use', label: 'Drink', steps: [{ op: 'clear', resource: 'stress', amount: '1d4' }] }] } },
      { name: 'Class item', note: 'Listed on the class character guide', type: 'class item', qty: 1 },
      { name: 'Silver Locket', note: 'Loot - Uncommon', type: 'loot', qty: 1 },
    ],
    loadout: [
      { level: 1, domain: 'Bone', type: 'Ability', name: 'Sample Bone Ability', text: 'Card feature text appears here from the domain card data.', recall: 1 },
      { level: 1, domain: 'Sage', type: 'Spell', name: 'Sample Sage Spell', text: 'Card feature text. Spells can start a Spellcast roll.', recall: 1, spellcast: 'Instinct' },
      { level: 3, domain: 'Bone', type: 'Ability', name: 'Sample Level 3 Card', text: 'Card feature text. Usage limits can be ticked here.', recall: 2 },
    ],
    vault: [{ level: 2, domain: 'Sage', type: 'Spell', name: 'Sample Vault Card', text: 'Card feature text.', recall: 1 }],
    description: { Name: 'Mira Ashvale', Pronouns: 'she/her', Age: '29', Height: '5\'7"', Eyes: 'Grey', Hair: 'Black, braided', Skin: 'Tanned', Attire: 'Green travel cloak' },
    otherDetails: 'Scar across the left hand, carries a wolf-tooth charm.',
    notes: '',
    backgroundQuestions: ['', '', ''],
    connections: ['', ''],
  };
}
