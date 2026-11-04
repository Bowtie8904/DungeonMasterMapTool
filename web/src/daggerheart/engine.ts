// Automation engine for the dmmap-daggerheart data format (see docs/DAGGERHEART_DATA.md).
// Pure functions over the CharacterSheet so they can be unit-tested without a DOM.

import { Advantage, CharacterSheet, Feature, TraitName, TRAIT_ORDER, tierOf } from './data';
import type { RollContribution } from '../protocol';
import type { MitigationOption } from './damage';

export type Resource = string;
export type Until = 'manual' | 'rest' | 'shortRest' | 'longRest' | 'scene' | 'session';

/** Value of a modifier: a number or an expression such as "@spellcast", "@tier" or "-@level". */
export type ModValue = number | string;

export interface Modifier {
  /** Passive stat: evasion, armorScore, majorThreshold, severeThreshold, thresholds, proficiency, loadoutMax, traits, trait.<Name> */
  stat?: string;
  /** Roll target: attack, damage, spellcast, trait:<Name> */
  roll?: string;
  value?: ModValue;
  /** Dice added to the roll (damage rolls), e.g. "d6" */
  dice?: string;
  /** Level-scaled replacement for dice, e.g. {"2": "2d6", "5": "3d8"} */
  diceByLevel?: Record<string, string>;
  /** Removed once it has been applied to a matching roll */
  consume?: boolean;
}

export interface EffectDef {
  name: string;
  kind: 'buff' | 'debuff';
  until?: Until;
  text?: string;
  modifiers?: Modifier[];
  blocksMovement?: boolean;
  endOnSourceSevere?: boolean;
  sourceActorId?: string;
  sourceKey?: string;
}

export interface Step {
  op: 'clear' | 'gain' | 'mark' | 'effect';
  resource?: Resource;
  amount?: number | string;
  effect?: EffectDef;
}

export interface ActionDef {
  id: string;
  label: string;
  timing?: 'action' | 'downtime' | 'rest';
  downtime?: {
    rest?: RestKind;
    consumeItem?: boolean;
    table?: { expression: string; outcomes: string[]; repeat?: string };
  };
  cost?: Partial<Record<Resource, ModValue>>;
  steps?: Step[];
  /** Own use limit of this action, counted separately from the card's other actions. */
  uses?: { max: number; per: 'shortRest' | 'longRest' | 'rest' | 'scene' | 'session' };
  /** Sends a plain roll to the chat, for example the d4 of I See It Coming added to the Evasion against one attack. */
  sendRoll?: { dice: string; base?: 'evasion'; label: string };
  actionRoll?: { trait?: TraitName | 'highest'; attack?: boolean; difficulty?: number };
  ancestryChoice?: 'ancestry' | 'feature';
  endEffect?: string;
  requiresEffect?: string;
  damage?: { dice: string; fixed?: boolean; extraDice?: number; type?: 'physical' | 'magic' };
  target?: 'actor' | 'party';
  transferHope?: boolean;
  check?: { dice: string; unless: number; mark: 'stress' };
}

export interface ResourceDef {
  id: string;
  label: string;
  initial: ModValue;
  max?: ModValue;
  session?: boolean;
  longRestLoss?: number;
}

export interface RollOption extends ActionDef {
  roll: string;
  value?: ModValue;
  dice?: string;
  diceByLevel?: Record<string, string>;
  description?: string;
  uses?: Automation['uses'];
  consumeItem?: boolean;
  /** Effect started by the first roll that selects this option; while it is active the option is pre-selected and free. */
  effect?: EffectDef;
  weapon?: 'source' | 'primary' | 'secondary' | 'any';
  defaultOn?: boolean;
  advantage?: 'advantage' | 'disadvantage';
  ignoreDisadvantage?: boolean;
  requiresEffect?: string;
  hopeDie?: 20;
  fearDie?: 20;
  resourceEmpty?: string;
  input?: { label: string; default: number; min: number; max: number };
  quantity?: { resource: string; label: string };
}

/** A button a feature offers on a finished roll in the chat. */
export interface RollTrigger extends ActionDef {
  on: string;
  when?: 'always' | 'fear' | 'hope' | 'hopeOrCritical' | 'failure' | 'hopeDie' | 'dieMatches';
  die?: number;
  matchResource?: string;
  reroll?: 'hope' | 'duality';
  advantage?: boolean;
  /** Offered only while an effect with this name is active. */
  requiresEffect?: string;
  /** Removes the effect with this name when the button is used. */
  endEffect?: string;
  description?: string;
  audience?: 'self' | 'ally' | 'any';
  outcome?: 'hope' | 'fear';
  weapon?: string;
  tokenGain?: { resource: string; max: number };
}

export interface Automation {
  restReroll?: boolean;
  rest?: {
    extraMoves?: number;
    longMovesInShort?: boolean;
    passives?: RestBenefit[];
    modifiers?: RestMoveModifier[];
  };
  rollTriggers?: RollTrigger[];
  modifiers?: Modifier[];
  uses?: { max: number; per: 'shortRest' | 'longRest' | 'rest' | 'scene' | 'session' };
  actions?: ActionDef[];
  resources?: ResourceDef[];
  creation?: { hp?: number; stress?: number; evasion?: number; domainCards?: number; domains?: string[]; experienceBonus?: number };
  rollOptions?: RollOption[];
  damage?: { extraDice: number; dropLowest: number };
  /** Options offered in the incoming-damage dialog (see damage.ts). */
  mitigation?: MitigationOption[];
}

export interface RestBenefit extends ActionDef {
  defaultOn: boolean;
  rest?: RestKind;
  extraMoves?: number;
  reroll?: boolean;
  consumeItem?: boolean;
  move?: string;
  bonus?: number;
  condition?: 'noRecovery' | 'armorMarked';
}

export interface RestMoveModifier {
  move: string;
  rest?: RestKind;
  target?: 'ally';
  bonus?: number;
  steps?: Step[];
  note?: string;
}

export interface ActiveEffect extends EffectDef {
  id: string;
  source?: string;
}

const TRAIT_SET = new Set<string>(TRAIT_ORDER);

/** Automation of everything currently contributing passive modifiers. */
export function automationSources(c: CharacterSheet): Automation[] {
  const out: Automation[] = [];
  const add = (a?: Automation) => { if (a) out.push(a); };
  add(c.activeArmor?.automation);
  c.activeWeapons.forEach((w) => add(w.automation));
  c.features.forEach((f) => add(f.automation));
  c.heritage.forEach((f) => add(f.automation));
  c.loadout.forEach((d) => add(d.automation));
  c.inventory.filter((i) => i.qty > 0).forEach((i) => add(i.automation));
  return out;
}

export function resourceDefinitions(c: CharacterSheet): ResourceDef[] {
  return [...new Map(automationSources(c).flatMap((a) => a.resources ?? []).map((r) => [r.id, r])).values()];
}

export function syncResources(c: CharacterSheet): void {
  c.resources ??= {};
  for (const r of resourceDefinitions(c)) {
    const max = r.max === undefined ? Infinity : Math.max(0, evalValue(r.max, c));
    const value = c.resources[r.id] ?? evalValue(r.initial, c);
    c.resources[r.id] = Math.max(0, Math.min(max, value));
  }
}

function allModifiers(c: CharacterSheet): Modifier[] {
  const mods = automationSources(c).flatMap((a) => a.modifiers ?? []);
  for (const e of c.effects) mods.push(...(e.modifiers ?? []));
  return mods;
}

let gmFear = 0;
/** The GM's current Fear count (0-12), supplied by the host; `@fear` values read it. */
export function setGmFear(n: number | undefined): void {
  gmFear = Math.max(0, Math.floor(n ?? 0));
}

export function evalValue(value: ModValue | undefined, c: CharacterSheet): number {
  if (typeof value === 'number') return value;
  if (!value) return 0;
  const m = /^([+-]?)@(\w+)$/.exec(value.trim());
  if (!m) return Number(value) || 0;
  const sign = m[1] === '-' ? -1 : 1;
  const key = m[2].toLowerCase();
  let n = 0;
  if (key === 'tier') n = tierOf(c.level);
  else if (key === 'fear') n = gmFear;
  else if (key.startsWith('cards_')) n = [...c.loadout, ...c.vault].filter((d) => d.domain.toLowerCase() === key.slice(6)).length;
  else if (key === 'level') n = c.level;
  else if (key === 'proficiency') n = c.proficiency.on;
  else if (key === 'spellcast') n = c.traits.find((t) => t.spellcast)?.mod ?? 0;
  else {
    const t = c.traits.find((x) => x.name.toLowerCase() === key);
    n = t ? t.mod : 0;
  }
  return sign * n;
}

function statTotal(c: CharacterSheet, ...stats: string[]): number {
  return allModifiers(c).filter((m) => m.stat && stats.includes(m.stat)).reduce((n, m) => n + evalValue(m.value, c), 0);
}

export interface DerivedStats {
  evasion: number;
  armorSlots: number;
  major: number;
  severe: number;
  proficiency: number;
  loadoutMax: number;
}

/** Evasion, armor slots and damage thresholds from the equipped armor, level and all active modifiers. */
export function derivedStats(c: CharacterSheet): DerivedStats {
  const armor = c.activeArmor;
  const baseMajor = armor ? (armor.major ?? armor.score + 4) : 0;
  const baseSevere = armor ? (armor.severe ?? armor.score * 2 + 7) : 0;
  const both = statTotal(c, 'thresholds');
  return {
    evasion: c.evasion + statTotal(c, 'evasion'),
    armorSlots: Math.max(0, (armor?.score ?? 0) + statTotal(c, 'armorScore')),
    major: Math.max(0, baseMajor + c.level + both + statTotal(c, 'majorThreshold')),
    severe: Math.max(0, baseSevere + (armor ? c.level : c.level * 2) + both + statTotal(c, 'severeThreshold')),
    proficiency: Math.max(1, c.proficiency.on + statTotal(c, 'proficiency')),
    loadoutMax: Math.max(0, 5 + statTotal(c, 'loadoutMax')),
  };
}

export interface StatSource { label: string; value: number }

/** Named sources of passive modifiers (equipped gear, features, loadout cards and active effects). */
function namedSources(c: CharacterSheet): Array<{ name: string; auto?: Automation; mods?: Modifier[]; weapon?: CharacterSheet['activeWeapons'][number] }> {
  return [
    ...(c.activeArmor ? [{ name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...c.activeWeapons.map((w) => ({ name: w.feature ? `${w.name} - ${w.feature.name}` : w.name, auto: w.automation, weapon: w })),
    ...c.features.map((f) => ({ name: f.name, auto: f.automation })),
    ...c.heritage.map((f) => ({ name: f.name, auto: f.automation })),
    ...c.loadout.map((d) => ({ name: d.name, auto: d.automation })),
    ...c.inventory.filter((i) => i.qty > 0).map((i) => ({ name: i.name, auto: i.automation })),
    ...c.effects.map((e) => ({ name: e.name, mods: e.modifiers })),
  ];
}

/** Every non-zero contribution to the given stats, by source name. */
export function statSources(c: CharacterSheet, ...stats: string[]): StatSource[] {
  const out: StatSource[] = [];
  for (const s of namedSources(c)) {
    const value = (s.mods ?? s.auto?.modifiers ?? []).filter((m) => m.stat && stats.includes(m.stat)).reduce((n, m) => n + evalValue(m.value, c), 0);
    if (value) out.push({ label: s.name, value });
  }
  return out;
}

/** Where evasion, armor slots and the damage thresholds come from, base values first. */
export function statBreakdown(c: CharacterSheet): Record<'evasion' | 'armor' | 'major' | 'severe', StatSource[]> {
  const armor = c.activeArmor;
  const both = statSources(c, 'thresholds');
  const base = (label: string, value: number): StatSource[] => (value ? [{ label, value }] : []);
  return {
    evasion: [{ label: c.className ? `${c.className} base (with level-ups)` : 'Base', value: c.evasion }, ...statSources(c, 'evasion')],
    armor: [...(armor ? [{ label: armor.name, value: armor.score }] : []), ...statSources(c, 'armorScore')],
    major: [...base(armor ? armor.name : 'No armor', armor ? (armor.major ?? armor.score + 4) : 0), { label: `Level ${c.level}`, value: c.level }, ...both, ...statSources(c, 'majorThreshold')],
    severe: [...base(armor ? armor.name : 'No armor', armor ? (armor.severe ?? armor.score * 2 + 7) : 0), { label: armor ? `Level ${c.level}` : `Level ${c.level} x2`, value: armor ? c.level : c.level * 2 }, ...both, ...statSources(c, 'severeThreshold')],
  };
}

export function traitTotal(c: CharacterSheet, name: TraitName): number {
  const base = c.traits.find((t) => t.name === name)?.mod ?? 0;
  return base + statTotal(c, `trait.${name}`, 'traits');
}

/** Keeps the pip tracks consistent with derived values (armor slots follow the equipped armor). */
export function syncDerived(c: CharacterSheet): DerivedStats {
  syncResources(c);
  const d = derivedStats(c);
  c.armorTrack.slots = Math.min(c.armorTrack.total, d.armorSlots);
  c.armorTrack.on = Math.min(c.armorTrack.on, c.armorTrack.slots);
  return d;
}

export interface RollBonus {
  value: number;
  dice: string[];
  /** Effects whose consume modifiers contributed; call consumeBonus after the roll was sent. */
  applied: Array<{ effect: ActiveEffect; modifier: Modifier }>;
  names: string[];
  contributions: RollContribution[];
}

/** Collects roll modifiers matching any of the targets (for example ['attack', 'trait:Finesse']). */
export function rollBonus(c: CharacterSheet, targets: string[], weapon?: CharacterSheet['activeWeapons'][number], withConsumables = true): RollBonus {
  const out: RollBonus = { value: 0, dice: [], applied: [], names: [], contributions: [] };
  const take = (m: Modifier, name: string, effect?: ActiveEffect) => {
    if (!m.roll || !targets.includes(m.roll)) return;
    if (m.consume && !withConsumables) return;
    const v = evalValue(m.value, c);
    out.value += v;
    const level = Object.keys(m.diceByLevel ?? {}).map(Number).filter((n) => n <= c.level).sort((a, b) => b - a)[0];
    const dice = level === undefined ? m.dice : m.diceByLevel![String(level)];
    if (dice) out.dice.push(dice);
    out.names.push(`${name} ${dice ? `+${dice}` : v >= 0 ? `+${v}` : v}`);
    out.contributions.push({ label: name.slice(0, 120), expression: `${v}${dice ? `+${dice.startsWith('d') ? `1${dice}` : dice}` : ''}` });
    if (effect && m.consume) out.applied.push({ effect, modifier: m });
  };
  for (const e of c.effects) for (const m of e.modifiers ?? []) take(m, e.name, e);
  for (const s of namedSources(c)) {
    if (weapon && s.weapon && s.weapon !== weapon) continue;
    for (const m of s.auto?.modifiers ?? []) take(m, s.name);
  }
  return out;
}

export interface AvailableRollOption {
  key: string;
  source: string;
  /** Display-only provenance; source and key retain their automation identity. */
  origin?: string;
  automation: Automation;
  option: RollOption;
  item?: CharacterSheet['inventory'][number];
  /** The option's own effect is already running, so using it again is free. */
  active?: boolean;
  /** A prepared single-use bonus (for example a drunk potion): ticked by default, removed once the roll was sent. */
  prepared?: { effect: ActiveEffect; modifier: Modifier };
}

export function configuredRollOption(c: CharacterSheet, selection: AvailableRollOption, input?: number): AvailableRollOption {
  const { option } = selection;
  if (!option.input && !option.quantity) return selection;
  const min = option.input?.min ?? 1;
  const max = option.input?.max ?? (c.resources?.[option.quantity!.resource] ?? 0);
  const value = input ?? option.input?.default ?? 1;
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${option.input?.label ?? option.quantity?.label}: choose a whole number from ${min} to ${max}`);
  return { ...selection, option: { ...option,
    value: option.input ? value : evalValue(option.value, c) * value,
    cost: option.quantity ? Object.fromEntries(Object.entries(option.cost ?? {}).map(([key, cost]) => [key, evalValue(cost, c) * value])) : option.cost,
  } };
}

export function featureRollOrigin(c: CharacterSheet, f: Feature): string {
  if (f.source === 'Hope feature') return c.className || f.source;
  const multiclass = /^Multiclass (.+?) - (?:class feature|(.+) (?:foundation|specialization|mastery))$/.exec(f.source);
  if (multiclass) return multiclass[2] || multiclass[1];
  const origin = f.source.replace(/^(?:Ancestry|Community|Transformation|Class feature|Creation|Subclass (?:foundation|specialization|mastery)) - /, '');
  return f.source.startsWith('Ancestry - ') ? origin.replace(/ \((?:first|second)\)$/, '') : origin;
}

export function rollOptionLabel(s: Pick<AvailableRollOption, 'source' | 'origin' | 'option'>): string {
  return sourceActionLabel(s.origin, s.source, s.option.label);
}

export function sourceActionLabel(origin: string | undefined, source: string, action: string): string {
  const parts: string[] = [];
  for (const name of [origin, source, action]
    .filter((name): name is string => !!name?.trim())
    .map((name) => name.trim())) {
    const previous = parts[parts.length - 1];
    if (previous === name) continue;
    if (previous && [' (', ':', ' - '].some(suffix => name.startsWith(previous + suffix))) {
      parts[parts.length - 1] = name;
    } else {
      parts.push(name);
    }
  }
  return parts.join(' - ');
}

/** Optional effects are selected by the player, never inferred from situational prose. */
export function availableRollOptions(c: CharacterSheet, targets: string[], weapon?: CharacterSheet['activeWeapons'][number]): AvailableRollOption[] {
  const sources: Array<{ key: string; name: string; origin?: string; auto?: Automation; item?: CharacterSheet['inventory'][number]; weapon?: CharacterSheet['activeWeapons'][number] }> = [
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...c.activeWeapons.map((w) => ({ key: `weapon:${w.name}`, name: w.name, auto: w.automation, weapon: w })),
    ...c.features.map((f) => ({ key: `feat:${f.name}`, name: f.name, origin: featureRollOrigin(c, f), auto: f.automation })),
    ...c.heritage.map((f) => ({ key: `feat:${f.name}`, name: f.name, origin: featureRollOrigin(c, f), auto: f.automation })),
    ...c.loadout.map((d) => ({ key: `card:${d.name}`, name: d.name, auto: d.automation })),
    ...c.inventory.filter((i) => i.qty > 0 || i.automation?.rollOptions?.some((o) => o.effect && c.effects.some((e) => e.name === o.effect!.name))).map((i) => ({ key: `item:${i.name}`, name: i.name, auto: i.automation, item: i })),
  ];
  const prepared: AvailableRollOption[] = c.effects.flatMap((e) => (e.modifiers ?? []).flatMap((modifier, i) => {
    if (!modifier.consume || !modifier.roll || !targets.includes(modifier.roll)) return [];
    const option: RollOption = { id: `prepared:${e.id}:${i}`, label: 'prepared', roll: modifier.roll, value: modifier.value, dice: modifier.dice, diceByLevel: modifier.diceByLevel };
    return [{ key: `effect:${e.id}:${i}`, source: e.name, automation: {}, option, active: true, prepared: { effect: e, modifier } }];
  }));
  return [...prepared, ...sources.flatMap((s) => (s.auto?.rollOptions ?? [])
    .filter((o) => targets.includes(o.roll))
    .filter((o) => !o.requiresEffect || c.effects.some((e) => e.name === o.requiresEffect))
    .filter((o) => !o.resourceEmpty || (c.resources?.[o.resourceEmpty] ?? 0) === 0)
    .filter((o) => o.weapon === 'primary' || o.weapon === 'secondary'
      ? weapon?.slot.toLowerCase() === o.weapon && c.activeWeapons.includes(weapon)
      : !s.weapon || o.weapon === 'any' || !weapon || weapon === s.weapon)
    .map((option) => ({
      key: option.uses ? `${s.key}:roll:${option.id}` : s.key,
      source: s.name, origin: s.origin, automation: option.uses ? { ...s.auto!, uses: option.uses } : s.auto!, option,
      item: s.item,
      active: !!option.effect && c.effects.some((e) => e.name === option.effect!.name),
    })))];
}

export function rollOptionDice(c: CharacterSheet, option: RollOption): string | undefined {
  const level = Object.keys(option.diceByLevel ?? {}).map(Number).filter((n) => n <= c.level).sort((a, b) => b - a)[0];
  return level === undefined ? option.dice : option.diceByLevel![String(level)];
}

export function selectedRollBonus(c: CharacterSheet, base: RollBonus, selected: AvailableRollOption[]): RollBonus {
  const out = { ...base, dice: [...base.dice], names: [...base.names], contributions: [...base.contributions] };
  for (const selection of selected) {
    const { option } = selection;
    const value = evalValue(option.value, c);
    const dice = rollOptionDice(c, option);
    const label = rollOptionLabel(selection).slice(0, 120);
    out.value += value;
    if (dice) out.dice.push(dice);
    out.names.push(label);
    out.contributions.push({ label, expression: `${value}${dice ? `+${dice.startsWith('d') ? `1${dice}` : dice}` : ''}` });
  }
  return out;
}

export function suggestedAdvantage(selected: AvailableRollOption[]): Advantage {
  const advantage = selected.some((s) => s.option.advantage === 'advantage');
  const disadvantage = selected.some((s) => s.option.advantage === 'disadvantage');
  return advantage === disadvantage ? 0 : advantage ? 1 : -1;
}

export function effectiveAdvantage(requested: Advantage, selected: AvailableRollOption[]): Advantage {
  return requested === -1 && selected.some((s) => s.option.ignoreDisadvantage) ? 0 : requested;
}

export function selectionBlocker(c: CharacterSheet, selected: AvailableRollOption[], experience = false): string | null {
  const cost: Record<string, number> = experience ? { hope: 1 } : {};
  const uses = new Map<string, number>();
  const items = new Map<CharacterSheet['inventory'][number], number>();
  for (const s of selected) {
    if (s.active) continue;
    const block = actionBlocker(c, s.key, s.automation, s.option);
    if (block) return block;
    if (s.item && (!c.inventory.includes(s.item) || s.item.qty <= 0)) return `${s.source}: none left`;
    if (s.option.consumeItem && s.item) items.set(s.item, (items.get(s.item) ?? 0) + 1);
    if (s.automation.uses) {
      const count = (uses.get(s.key) ?? 0) + 1;
      uses.set(s.key, count);
      const left = usesLeft(c, s.key, s.automation);
      if (left !== null && count > left) return `${s.source}: not enough uses left`;
    }
    for (const [res, value] of Object.entries(s.option.cost ?? {})) cost[res] = (cost[res] ?? 0) + evalValue(value, c);
  }
  for (const [item, count] of items) if (item.qty < count) return `${item.name}: not enough inventory`;
  return costBlocker(c, cost);
}

/** Commit only after the send succeeded and the entire selection was checked together. */
export function commitRollOptions(c: CharacterSheet, selected: AvailableRollOption[], experience = false): void {
  const blocker = selectionBlocker(c, selected, experience);
  if (blocker) throw new Error(blocker);
  if (experience) c.hope.on--;
  for (const s of selected) {
    if (s.prepared) {
      const { effect, modifier } = s.prepared;
      effect.modifiers = (effect.modifiers ?? []).filter((m) => m !== modifier);
      if (!effect.modifiers.length && c.effects.includes(effect)) c.effects.splice(c.effects.indexOf(effect), 1);
      continue;
    }
    if (s.active) continue;
    const steps = s.option.effect ? [...(s.option.steps ?? []), { op: 'effect' as const, effect: s.option.effect }] : s.option.steps;
    runAction(c, s.key, s.automation, { ...s.option, steps }, s.source);
    if (s.option.consumeItem && s.item) s.item.qty--;
  }
}

export function traitContributions(c: CharacterSheet, name: TraitName): RollContribution[] {
  return [
    { label: `${name} trait`, expression: String(c.traits.find((t) => t.name === name)?.mod ?? 0) },
    ...statSources(c, `trait.${name}`, 'traits').map((s) => ({ label: s.label.slice(0, 120), expression: String(s.value) })),
  ];
}

export function damageContributions(c: CharacterSheet, weapon: CharacterSheet['activeWeapons'][number], bonus: RollBonus): RollContribution[] {
  const proficiency = derivedStats(c).proficiency;
  const base = damageExpression(weapon.damage, proficiency, { ...bonus, value: 0, dice: [] }, weapon.automation?.damage, weapon.fixedDice);
  const parts = /^(\d+d\d+(?:kh\d+)?)([+-]\d+)?$/i.exec(base);
  const sources = statSources(c, 'proficiency');
  const raw = c.proficiency.on + sources.reduce((n, s) => n + s.value, 0);
  const diceCount = (n: number) => `${n >= 0 ? '+' : ''}${n} ${Math.abs(n) === 1 ? 'die' : 'dice'}`;
  const notes = [
    { label: 'Base Proficiency', impact: `${c.proficiency.on} dice` },
    ...sources.map((s) => ({ label: s.label.slice(0, 120), impact: diceCount(s.value) })),
    ...(raw < 1 ? [{ label: 'Minimum Proficiency', impact: diceCount(1 - raw) }] : []),
    ...(weapon.automation?.damage ? [{ label: weapon.feature?.name ?? weapon.name, impact: `Roll ${weapon.automation.damage.extraDice} extra; discard ${weapon.automation.damage.dropLowest} lowest` }] : []),
  ];
  return [
    { label: `${weapon.name} damage (Proficiency ${proficiency})`.slice(0, 120), expression: parts?.[1] ?? base, ...(parts ? { notes } : {}) },
    ...(parts?.[2] ? [{ label: `${weapon.name} damage bonus`.slice(0, 120), expression: parts[2] }] : []),
    ...bonus.contributions,
  ];
}

/** Removes the consumable modifiers (and emptied effects) once their roll was sent. */
export function consumeBonus(c: CharacterSheet, bonus: RollBonus): void {
  for (const { effect, modifier } of bonus.applied) {
    effect.modifiers = (effect.modifiers ?? []).filter((m) => m !== modifier);
    if (!effect.modifiers.length) c.effects.splice(c.effects.indexOf(effect), 1);
  }
}

let effectCounter = 0;

export function addEffect(c: CharacterSheet, def: EffectDef, source?: string): ActiveEffect {
  const effect: ActiveEffect = {
    ...def,
    until: def.until ?? 'manual',
    modifiers: (def.modifiers ?? []).map((m) => ({ ...m })),
    id: `e${Date.now().toString(36)}${effectCounter++}`,
    source,
  };
  const existing = c.effects.findIndex((e) => e.name === def.name);
  if (existing >= 0) c.effects.splice(existing, 1);
  c.effects.push(effect);
  return effect;
}

export type RestKind = 'short' | 'long';

/** Expires effects and refreshes limited-use abilities for a rest. */
export function applyRest(c: CharacterSheet, kind: RestKind): void {
  syncResources(c);
  if (kind === 'long') for (const r of resourceDefinitions(c)) {
    if (r.longRestLoss) c.resources![r.id] = Math.max(0, c.resources![r.id] - r.longRestLoss);
  }
  const expiring: Until[] = kind === 'long' ? ['rest', 'shortRest', 'longRest', 'scene'] : ['shortRest', 'rest', 'scene'];
  c.effects = c.effects.filter((e) => !expiring.includes(e.until ?? 'manual'));
  for (const key of Object.keys(c.usage)) {
    const per = c.usage[key].per;
    if (per === 'shortRest' || per === 'rest' || per === 'scene' || (kind === 'long' && (per === 'longRest'))) {
      delete c.usage[key];
    }
  }
}

export function usesLeft(c: CharacterSheet, key: string, a: Automation): number | null {
  if (!a.uses || a.uses.per === 'scene') return null;
  return Math.max(0, a.uses.max - (c.usage[key]?.used ?? 0));
}

export function costText(cost?: ActionDef['cost'], c?: CharacterSheet): string {
  if (!cost) return '';
  const parts: string[] = [];
  const labels: Record<string, string> = { hope: 'Hope', stress: 'Stress', armor: 'Armor Slot', hp: 'HP' };
  for (const [res, value] of Object.entries(cost)) {
    if (value === undefined) continue;
    const n = c ? evalValue(value, c) : value;
    if (n) parts.push(`${n} ${labels[res] ?? (c && resourceDefinitions(c).find((r) => r.id === res)?.label) ?? res}`);
  }
  return parts.join(', ');
}

function track(c: CharacterSheet, r: Resource) {
  return r === 'hp' ? c.hp : r === 'stress' ? c.stress : r === 'hope' ? c.hope : r === 'armor' ? c.armorTrack : undefined;
}

function costBlocker(c: CharacterSheet, cost: ActionDef['cost']): string | null {
  syncResources(c);
  for (const [res, value] of Object.entries(cost ?? {})) {
    const n = evalValue(value, c);
    if (!Number.isFinite(n) || n < 0) return `Invalid ${res} cost`;
    const t = track(c, res);
    if (t) {
      if (res === 'hope' ? t.on < n : t.on + n > t.slots) return res === 'hope' ? 'Not enough Hope' : `Not enough free ${res === 'armor' ? 'Armor Slots' : res.toUpperCase()}`;
    } else {
      const def = resourceDefinitions(c).find((r) => r.id === res);
      if (!def) return `Resource unavailable: ${res}`;
      if ((c.resources?.[res] ?? 0) < n) return `Not enough ${def.label}`;
    }
  }
  return null;
}

function payCost(c: CharacterSheet, cost: ActionDef['cost']): void {
  for (const [res, value] of Object.entries(cost ?? {})) {
    const n = evalValue(value, c);
    const t = track(c, res);
    if (t) t.on += res === 'hope' ? -n : n;
    else c.resources![res] -= n;
  }
}

export function isRestOnlyAction(action: ActionDef): boolean {
  return action.timing === 'downtime' || action.timing === 'rest';
}

export function sheetActions(a: Automation | undefined): ActionDef[] {
  return (a?.actions ?? []).filter((action) => !isRestOnlyAction(action) || action.ancestryChoice);
}

/** Returns why the action cannot be used, or null when it can. */
export function actionBlocker(c: CharacterSheet, key: string, a: Automation, action: ActionDef, context: 'sheet' | 'rest' = 'sheet'): string | null {
  if (context !== 'rest' && isRestOnlyAction(action) && !action.ancestryChoice) return 'Choose this move in the rest dialog';
  if (action.requiresEffect && !c.effects.some((e) => e.name === action.requiresEffect)) return 'Required effect is not active';
  if (action.uses) { key = `${key}:action:${action.id}`; a = { ...a, uses: action.uses }; }
  const left = usesLeft(c, key, a);
  if (left !== null && left <= 0) return a.uses?.per === 'session' ? 'No uses left this session' : 'No uses left until a rest';
  for (const step of action.steps ?? []) {
    if (step.resource && !track(c, step.resource) && !resourceDefinitions(c).some((r) => r.id === step.resource)) return `Resource unavailable: ${step.resource}`;
  }
  return costBlocker(c, action.cost);
}

export type DiceRoll = (expression: string, clearingResource?: string) => number;

/** Default local dice roller for expressions such as "1d4+1". */
export const rollLocal: DiceRoll = (expression) => {
  let total = 0;
  for (const m of expression.toLowerCase().replace(/\s/g, '').matchAll(/([+-]?)(?:(\d*)d(\d+)|(\d+))/g)) {
    const sign = m[1] === '-' ? -1 : 1;
    if (m[3]) {
      const count = m[2] ? Number(m[2]) : 1;
      for (let i = 0; i < count; i++) total += sign * (1 + Math.floor(Math.random() * Number(m[3])));
    } else total += sign * Number(m[4]);
  }
  return total;
};

/** Runs an action: pays costs, counts the use, executes steps. Returns a chat-friendly summary. */
export function runAction(c: CharacterSheet, key: string, a: Automation, action: ActionDef, label: string, roll: DiceRoll = rollLocal, context: 'sheet' | 'rest' = 'sheet'): string | null {
  const result = runActionDetailed(c, key, a, action, label, roll, context);
  return result && `${label}: ${result.join(', ') || 'used'}`;
}

/** Like {@link runAction}, but returns the individual effects (empty when nothing visible happened). */
export function runActionDetailed(c: CharacterSheet, key: string, a: Automation, action: ActionDef, label: string, roll: DiceRoll = rollLocal, context: 'sheet' | 'rest' = 'sheet'): string[] | null {
  if (actionBlocker(c, key, a, action, context)) return null;
  if (action.uses) { key = `${key}:action:${action.id}`; a = { ...a, uses: action.uses }; }
  const log: string[] = [];
  payCost(c, action.cost);
  const cost = costText(action.cost, c);
  if (cost) log.push(`spent ${cost}`);
  if (a.uses && a.uses.per !== 'scene') {
    const u = c.usage[key] ?? { used: 0, per: a.uses.per };
    u.used++;
    c.usage[key] = u;
  }
  for (const step of action.steps ?? []) {
    const t = step.resource ? track(c, step.resource) : null;
    const amount = (fallback: number) => typeof step.amount === 'number' ? step.amount
      : step.amount && /\d*d\d+/i.test(step.amount)
        ? roll(step.amount.replace(/@[a-z]+/gi, (value) => String(evalValue(value, c))), step.op === 'clear' && t ? step.resource : undefined)
        : step.amount ? evalValue(step.amount, c) : fallback;
    if (step.resource && !t) {
      const def = resourceDefinitions(c).find((r) => r.id === step.resource)!;
      const before = c.resources![def.id];
      const n = Math.max(0, step.amount === 'all' ? before : amount(1));
      const max = def.max === undefined ? Infinity : Math.max(0, evalValue(def.max, c));
      c.resources![def.id] = step.op === 'gain' ? Math.min(max, before + n) : Math.max(0, before - n);
      log.push(`${step.op === 'gain' ? 'gained' : 'spent'} ${Math.abs(c.resources![def.id] - before)} ${def.label}`);
      continue;
    }
    if (step.op === 'clear' && t && step.resource) {
      const n = step.amount === 'all' ? t.on : amount(1);
      const cleared = Math.min(t.on, Math.max(0, n));
      t.on -= cleared;
      log.push(`cleared ${cleared} ${step.resource === 'hp' ? 'HP' : step.resource === 'armor' ? 'Armor Slots' : 'Stress'}${typeof step.amount === 'string' && step.amount !== 'all' ? ` (${step.amount})` : ''}`);
    } else if (step.op === 'gain' && t && step.resource) {
      const n = Math.max(0, amount(1));
      const before = t.on;
      t.on = Math.min(t.slots, t.on + n);
      log.push(`gained ${t.on - before} ${step.resource === 'hope' ? 'Hope' : step.resource}`);
    } else if (step.op === 'mark' && t && step.resource) {
      const n = Math.max(0, amount(1));
      t.on = Math.min(t.slots, t.on + n);
      log.push(`marked ${n} ${step.resource}`);
    } else if (step.op === 'effect' && step.effect) {
      addEffect(c, step.effect, label);
      log.push(`${step.effect.kind === 'debuff' ? 'afflicted by' : 'gained'} ${step.effect.name}`);
    }
  }
  if (action.endEffect) {
    c.effects = c.effects.filter((e) => e.name !== action.endEffect);
    log.push(`ended ${action.endEffect}`);
  }
  return log;
}

/** Parses a weapon damage string like "d8+1" or "1d10+2" against the proficiency (dice count). */
export function damageExpression(damage: string, proficiency: number, extra: RollBonus, pool?: Automation['damage'], fixed = false): string {
  const m = /^(\d*)d(\d+)([+-]\d+)?$/i.exec(damage.replace(/\s/g, ''));
  const count = fixed && m ? Number(m[1] || 1) : proficiency + (pool?.extraDice ?? 0);
  const keep = count - (pool?.dropLowest ?? 0);
  let e = m ? `${count}d${m[2]}${pool?.dropLowest ? `kh${keep}` : ''}${m[3] ?? ''}` : damage;
  if (extra.value) e += extra.value > 0 ? `+${extra.value}` : `${extra.value}`;
  for (const d of extra.dice) e += `+${d.startsWith('d') ? `1${d}` : d}`;
  return e;
}

/** Extra damage of a critical hit: the maximum result of the (kept) damage dice, never the modifiers. */
export function criticalBonus(expression: string): number {
  let sum = 0;
  for (const m of expression.matchAll(/(?:^|\+)(\d+)d(\d+)(?:kh(\d+))?/gi)) sum += Number(m[3] ?? m[1]) * Number(m[2]);
  return sum;
}

/** True when a spell's text asks the player to make a Spellcast Roll (not merely mentions one). */
export function asksSpellcastRoll(text: string): boolean {
  return /\b(make|makes|made|roll|rolls|cast)\b[^.]{0,40}spellcast roll/i.test(text);
}

export function isTrait(name: string): name is TraitName {
  return TRAIT_SET.has(name);
}

export interface RollContext {
  /** Comma separated roll targets of the original roll, for example `attack,trait:Agility,action`. */
  source: string;
  hope: number;
  fear: number;
  total: number;
  outcome?: 'hope' | 'fear';
  weapon?: string;
}

export interface AvailableRollTrigger { key: string; source: string; automation: Automation; trigger: RollTrigger }

function triggerApplies(c: CharacterSheet, t: RollTrigger, ctx: RollContext): boolean {
  if (t.on !== 'duality' && !ctx.source.split(',').includes(t.on)) return false;
  if (t.requiresEffect && !c.effects.some((e) => e.name === t.requiresEffect)) return false;
  if (t.weapon && t.weapon !== ctx.weapon) return false;
  // Players never know the difficulty, so any roll may have failed (Hope rolls and criticals included).
  const failed = true;
  switch (t.when ?? 'always') {
    case 'fear': return ctx.hope !== ctx.fear && (ctx.outcome ? ctx.outcome === 'fear' : ctx.fear > ctx.hope);
    case 'hopeOrCritical': return ctx.hope === ctx.fear || (ctx.outcome ? ctx.outcome === 'hope' : ctx.hope > ctx.fear);
    case 'hope': return ctx.hope !== ctx.fear && (ctx.outcome ? ctx.outcome === 'hope' : ctx.hope > ctx.fear);
    case 'failure': return failed;
    case 'hopeDie': return ctx.hope === t.die;
    case 'dieMatches': {
      const n = t.matchResource ? c.resources?.[t.matchResource] ?? 0 : 0;
      return n > 0 && (ctx.hope === n || ctx.fear === n);
    }
    default: return true;
  }
}

/** Buttons the character's features offer on a finished roll. */
export function availableRollTriggers(c: CharacterSheet, ctx: RollContext): AvailableRollTrigger[] {
  const sources: Array<{ key: string; name: string; auto?: Automation }> = [
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...c.activeWeapons.map((w) => ({ key: `weapon:${w.name}`, name: w.name, auto: w.automation })),
    ...c.features.map((f) => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...c.heritage.map((f) => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...c.loadout.map((d) => ({ key: `card:${d.name}`, name: d.name, auto: d.automation })),
    ...c.inventory.filter((i) => i.qty > 0).map((i) => ({ key: `item:${i.name}`, name: i.name, auto: i.automation })),
  ];
  return sources.flatMap((s) => (s.auto?.rollTriggers ?? []).filter((t) => triggerApplies(c, t, ctx))
    .map((trigger) => ({ key: s.key, source: s.name, automation: s.auto!, trigger })));
}
