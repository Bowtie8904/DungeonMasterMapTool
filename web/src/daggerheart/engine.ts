// Automation engine for the dmmap-daggerheart data format (see docs/DAGGERHEART_DATA.md).
// Pure functions over the CharacterSheet so they can be unit-tested without a DOM.

import { CharacterSheet, TraitName, TRAIT_ORDER, tierOf } from './data';
import type { RollContribution } from '../protocol';

export type Resource = 'hp' | 'stress' | 'hope' | 'armor';
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
  /** Removed once it has been applied to a matching roll */
  consume?: boolean;
}

export interface EffectDef {
  name: string;
  kind: 'buff' | 'debuff';
  until?: Until;
  text?: string;
  modifiers?: Modifier[];
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
  cost?: Partial<Record<Resource, number>>;
  steps?: Step[];
}

export interface Automation {
  modifiers?: Modifier[];
  uses?: { max: number; per: 'shortRest' | 'longRest' | 'rest' | 'scene' | 'session' };
  actions?: ActionDef[];
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
  return out;
}

function allModifiers(c: CharacterSheet): Modifier[] {
  const mods = automationSources(c).flatMap((a) => a.modifiers ?? []);
  for (const e of c.effects) mods.push(...(e.modifiers ?? []));
  return mods;
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
function namedSources(c: CharacterSheet): Array<{ name: string; auto?: Automation; mods?: Modifier[] }> {
  return [
    ...(c.activeArmor ? [{ name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...c.activeWeapons.map((w) => ({ name: w.feature ? `${w.name} - ${w.feature.name}` : w.name, auto: w.automation })),
    ...c.features.map((f) => ({ name: f.name, auto: f.automation })),
    ...c.heritage.map((f) => ({ name: f.name, auto: f.automation })),
    ...c.loadout.map((d) => ({ name: d.name, auto: d.automation })),
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
export function rollBonus(c: CharacterSheet, targets: string[]): RollBonus {
  const out: RollBonus = { value: 0, dice: [], applied: [], names: [], contributions: [] };
  const take = (m: Modifier, name: string, effect?: ActiveEffect) => {
    if (!m.roll || !targets.includes(m.roll)) return;
    const v = evalValue(m.value, c);
    out.value += v;
    if (m.dice) out.dice.push(m.dice);
    out.names.push(`${name} ${m.dice ? `+${m.dice}` : v >= 0 ? `+${v}` : v}`);
    out.contributions.push({ label: name.slice(0, 120), expression: `${v}${m.dice ? `+${m.dice.startsWith('d') ? `1${m.dice}` : m.dice}` : ''}` });
    if (effect && m.consume) out.applied.push({ effect, modifier: m });
  };
  for (const e of c.effects) for (const m of e.modifiers ?? []) take(m, e.name, e);
  for (const s of namedSources(c)) for (const m of s.auto?.modifiers ?? []) take(m, s.name);
  return out;
}

export function traitContributions(c: CharacterSheet, name: TraitName): RollContribution[] {
  return [
    { label: `${name} trait`, expression: String(c.traits.find((t) => t.name === name)?.mod ?? 0) },
    ...statSources(c, `trait.${name}`, 'traits').map((s) => ({ label: s.label.slice(0, 120), expression: String(s.value) })),
  ];
}

export function damageContributions(c: CharacterSheet, weapon: CharacterSheet['activeWeapons'][number], bonus: RollBonus): RollContribution[] {
  const proficiency = derivedStats(c).proficiency;
  const base = damageExpression(weapon.damage, proficiency, { ...bonus, value: 0, dice: [] });
  const parts = /^(\d+d\d+)([+-]\d+)?$/i.exec(base);
  const sources = statSources(c, 'proficiency');
  const raw = c.proficiency.on + sources.reduce((n, s) => n + s.value, 0);
  const diceCount = (n: number) => `${n >= 0 ? '+' : ''}${n} ${Math.abs(n) === 1 ? 'die' : 'dice'}`;
  const notes = [
    { label: 'Base Proficiency', impact: `${c.proficiency.on} dice` },
    ...sources.map((s) => ({ label: s.label.slice(0, 120), impact: diceCount(s.value) })),
    ...(raw < 1 ? [{ label: 'Minimum Proficiency', impact: diceCount(1 - raw) }] : []),
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
  if (!a.uses) return null;
  return Math.max(0, a.uses.max - (c.usage[key]?.used ?? 0));
}

export function costText(cost?: ActionDef['cost']): string {
  if (!cost) return '';
  const parts: string[] = [];
  if (cost.hope) parts.push(`${cost.hope} Hope`);
  if (cost.stress) parts.push(`${cost.stress} Stress`);
  if (cost.armor) parts.push(`${cost.armor} Armor Slot`);
  if (cost.hp) parts.push(`${cost.hp} HP`);
  return parts.join(', ');
}

function track(c: CharacterSheet, r: Resource) {
  return r === 'hp' ? c.hp : r === 'stress' ? c.stress : r === 'hope' ? c.hope : c.armorTrack;
}

/** Returns why the action cannot be used, or null when it can. */
export function actionBlocker(c: CharacterSheet, key: string, a: Automation, action: ActionDef): string | null {
  const left = usesLeft(c, key, a);
  if (left !== null && left <= 0) return 'No uses left until a rest';
  for (const [res, n] of Object.entries(action.cost ?? {}) as Array<[Resource, number]>) {
    const t = track(c, res);
    if (res === 'hope' ? t.on < n : t.on + n > t.slots) return res === 'hope' ? 'Not enough Hope' : `Not enough free ${res === 'armor' ? 'Armor Slots' : res.toUpperCase()}`;
  }
  return null;
}

export type DiceRoll = (expression: string) => number;

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
export function runAction(c: CharacterSheet, key: string, a: Automation, action: ActionDef, label: string, roll: DiceRoll = rollLocal): string | null {
  if (actionBlocker(c, key, a, action)) return null;
  const log: string[] = [];
  for (const [res, n] of Object.entries(action.cost ?? {}) as Array<[Resource, number]>) {
    const t = track(c, res);
    if (res === 'hope') t.on -= n;
    else t.on += n;
  }
  const cost = costText(action.cost);
  if (cost) log.push(`spent ${cost}`);
  if (a.uses) {
    const u = c.usage[key] ?? { used: 0, per: a.uses.per };
    u.used++;
    c.usage[key] = u;
  }
  for (const step of action.steps ?? []) {
    const t = step.resource ? track(c, step.resource) : null;
    if (step.op === 'clear' && t && step.resource) {
      const n = step.amount === 'all' ? t.on : typeof step.amount === 'number' ? step.amount : roll(String(step.amount));
      const cleared = Math.min(t.on, n);
      t.on -= cleared;
      log.push(`cleared ${cleared} ${step.resource === 'hp' ? 'HP' : 'Stress'}${typeof step.amount === 'string' && step.amount !== 'all' ? ` (${step.amount})` : ''}`);
    } else if (step.op === 'gain' && t && step.resource) {
      const n = typeof step.amount === 'number' ? step.amount : roll(String(step.amount ?? 1));
      const before = t.on;
      t.on = Math.min(t.slots, t.on + n);
      log.push(`gained ${t.on - before} ${step.resource === 'hope' ? 'Hope' : step.resource}`);
    } else if (step.op === 'mark' && t && step.resource) {
      const n = typeof step.amount === 'number' ? step.amount : 1;
      t.on = Math.min(t.slots, t.on + n);
      log.push(`marked ${n} ${step.resource}`);
    } else if (step.op === 'effect' && step.effect) {
      addEffect(c, step.effect, label);
      log.push(`${step.effect.kind === 'debuff' ? 'afflicted by' : 'gained'} ${step.effect.name}`);
    }
  }
  return `${label}: ${log.join(', ') || 'used'}`;
}

/** Parses a weapon damage string like "d8+1" or "1d10+2" against the proficiency (dice count). */
export function damageExpression(damage: string, proficiency: number, extra: RollBonus): string {
  const m = /^(\d*)d(\d+)([+-]\d+)?$/i.exec(damage.replace(/\s/g, ''));
  let e = m ? `${proficiency}d${m[2]}${m[3] ?? ''}` : damage;
  if (extra.value) e += extra.value > 0 ? `+${extra.value}` : `${extra.value}`;
  for (const d of extra.dice) e += `+${d.startsWith('d') ? `1${d}` : d}`;
  return e;
}

export function isTrait(name: string): name is TraitName {
  return TRAIT_SET.has(name);
}
