// Incoming damage: thresholds, Armor Slots and config-driven mitigation (automation.mitigation).
// Pure functions over the CharacterSheet so they can be unit-tested without a DOM.

import type { CharacterSheet, InventoryItem } from './data';
import {
  Automation, DiceRoll, EffectDef, ModValue, Resource, activeFeatures, activeWeapons, addEffect, costText, derivedStats, evalValue, resourceDefinitions, rollLocal, syncEffectDependencies, syncResources, usesLeft, noUsesLeftText,
} from './engine';
import { formatDamageReport } from './damage-summary';
import { syncDeathState } from './death';
import { companionAvailable, hasCompanionTraining, markCompanionDamage, resolveBonded } from './companion';

export type DamageType = 'physical' | 'magic';
/** 0 = none, 1 = Minor, 2 = Major, 3 = Severe, 4 = massive (twice the Severe threshold). */
export type Severity = 0 | 1 | 2 | 3 | 4;
export type SeverityName = 'minor' | 'major' | 'severe' | 'massive';

export const SEVERITY_LABELS = ['None', 'Minor', 'Major', 'Severe', 'Massive'] as const;
const SEVERITY_NAMES: Array<SeverityName | undefined> = [undefined, 'minor', 'major', 'severe', 'massive'];

export type MitigationCost = Partial<Record<Resource, ModValue>>;

export interface MitigationWhen {
  type?: DamageType;
  /** Severity after damage reductions, before severity reductions. */
  severity?: SeverityName[];
  /** Only while an Armor Slot is marked against this damage. */
  armor?: boolean;
  /** Only when marking Armor Slots would mark the last free slot. */
  lastArmor?: boolean;
  /** Only while every Armor Slot is already marked. */
  armorFull?: boolean;
  /** Only when this damage would mark the last Hit Point. */
  lastHp?: boolean;
  /** Only while at most this many Hit Points are unmarked. */
  hpUnmarkedMax?: number;
  /** Only while the value (for example "@cards_splendor") is at least `at`. */
  min?: { value: ModValue; at: number };
  markingHp?: boolean;
}

export interface MitigationRoll {
  /** Number of dice: a value, "hp" (one per HP that would be marked) or "quantity". Default 1. */
  count?: ModValue | 'hp' | 'quantity';
  sides: number;
  /** Without a success face the total reduces the damage; with it, hits scale severity/hp/saveArmor. */
  success?: number;
  /** Every hit applies the effect; otherwise any hit applies it once. */
  each?: boolean;
}

export interface MitigationOption {
  companionIntercept?: boolean;
  companionArmored?: boolean;
  markedHpOnly?: boolean;
  reflectHp?: boolean;
  finalSevere?: boolean;
  recoveryChoice?: boolean;
  insteadOfArmor?: boolean;
  id: string;
  label: string;
  description?: string;
  /** Applied automatically whenever its conditions hold. */
  passive?: boolean;
  defaultOn?: boolean;
  requiresEffect?: string;
  when?: MitigationWhen;
  /** Paid on apply; "@hp" means the HP the damage would mark. */
  cost?: MitigationCost;
  /** Own limit, or "source" to count against the source's `uses`. */
  uses?: Automation['uses'] | 'source';
  consumeItem?: boolean;
  /** The player picks how many times to apply it; cost and dice scale with the quantity. */
  quantity?: boolean;
  halve?: boolean;
  double?: boolean;
  effect?: EffectDef;
  excludesEffect?: string;
  reduce?: ModValue;
  negate?: boolean;
  thresholds?: number;
  severity?: number;
  hp?: number;
  replaceHp?: boolean;
  armorSlots?: number | 'any';
  armorSeverity?: number;
  armorCost?: MitigationCost;
  armorBlocked?: boolean;
  saveArmor?: number;
  roll?: MitigationRoll;
  /** Rolled on apply and posted with the summary, e.g. Shrug It Off's vault check. */
  followUp?: { dice: string; text: string };
}

export interface AvailableMitigation {
  /** Unique id within the dialog. */
  id: string;
  key: string;
  source: string;
  automation: Automation;
  option: MitigationOption;
  item?: InventoryItem;
}

export interface DamageInput {
  recovery?: 'hope' | 'stress';
  damage: number;
  type: DamageType;
  armor: boolean;
  /** Armor Slots to mark when an option allows any number. */
  armorCount?: number;
  selected: Set<string>;
  quantities?: Record<string, number>;
  /** Dice rolled per option id; extended on demand and kept for the dialog. */
  rolls?: Record<string, number[]>;
}

export interface DamageResult {
  recovery?: 'hope' | 'stress';
  input: number;
  damage: number;
  major: number;
  severe: number;
  incoming: Severity;
  final: Severity;
  negated: boolean;
  hp: number;
  hpBeforeReplace: number;
  armorAllowed: number;
  armorUsed: number;
  armorMarked: number;
  armorBlocked: string | null;
  anyArmor: boolean;
  /** Applied options with their effective multiplier (dice hits / quantity). */
  applied: AvailableMitigation[];
  /** Why a selected or passive option does not apply right now. */
  inactive: Record<string, string>;
  costs: Record<string, number>;
  quantities: Record<string, number>;
  rolls: Record<string, number[]>;
  /** Dice that actually counted for each applied option. */
  used: Record<string, number[]>;
  error: string | null;
}

/** Every mitigation option the character currently has access to. */
export function availableMitigations(c: CharacterSheet): AvailableMitigation[] {
  const out: AvailableMitigation[] = [];
  const add = (key: string, source: string, a?: Automation, item?: InventoryItem) => {
    for (const option of a?.mitigation ?? []) {
      if (option.requiresEffect && !c.effects.some((e) => e.name === option.requiresEffect)) continue;
      out.push({ id: `${key}/${option.id}`, key, source, automation: a!, option, item });
    }
  };
  if (c.activeArmor) add(`armor:${c.activeArmor.name}`, c.activeArmor.name, c.activeArmor.automation);
  for (const w of activeWeapons(c)) add(`weapon:${w.name}`, w.name, w.automation);
  for (const f of [...activeFeatures(c), ...c.heritage]) add(`feat:${f.name}`, f.name, f.automation);
  for (const d of c.loadout) add(`card:${d.name}`, d.name, d.automation);
  for (const i of c.inventory.filter((x) => x.qty > 0)) add(`item:${i.name}`, i.name, i.automation, i);
  for (const e of c.effects) add(`effect:${e.id}`, e.name, { mitigation: e.mitigation });
  return out;
}

export function severityOf(damage: number, major: number, severe: number, massive = true): Severity {
  if (damage <= 0) return 0;
  if (massive && severe > 0 && damage >= severe * 2) return 4;
  if (damage >= severe) return 3;
  if (damage >= major) return 2;
  return 1;
}

function value(v: ModValue | undefined, c: CharacterSheet, hp = 0): number {
  if (typeof v === 'string') {
    const t = v.trim().toLowerCase();
    if (t === '@hp') return hp;
    if (t === '@armorscore') return derivedStats(c).armorSlots;
  }
  return evalValue(v, c);
}

function usageKey(m: AvailableMitigation): { key: string; auto: Automation } | null {
  const uses = m.option.uses;
  if (!uses) return null;
  if (uses === 'source') return m.automation.uses ? { key: m.key, auto: m.automation } : null;
  return { key: `${m.key}:mitigation:${m.option.id}`, auto: { uses } };
}

/** Conditions that do not depend on the damage being calculated. */
export function mitigationBlocker(c: CharacterSheet, m: AvailableMitigation, type: DamageType): string | null {
  const o = m.option;
  if (o.companionIntercept && !companionAvailable(c)) return 'Your companion is unavailable';
  if (o.companionArmored && !hasCompanionTraining(c, 'armored')) return 'Your companion has not learned Armored';
  if (o.excludesEffect && c.effects.some((e) => e.name === o.excludesEffect)) return 'Effect is already active';
  if (o.requiresEffect && !c.effects.some((e) => e.name === o.requiresEffect)) return 'Required effect is no longer active';
  const u = usageKey(m);
  if (u) {
    const left = usesLeft(c, u.key, u.auto);
    if (left !== null && left <= 0) return noUsesLeftText(u.auto.uses?.per);
  }
  if (o.consumeItem && (!m.item || m.item.qty <= 0)) return 'None left';
  const w = o.when ?? {};
  if (w.type && w.type !== type) return `${w.type === 'magic' ? 'Magic' : 'Physical'} damage only`;
  if (w.hpUnmarkedMax !== undefined && c.hp.slots - c.hp.on > w.hpUnmarkedMax) return `Only with ${w.hpUnmarkedMax} or fewer HP unmarked`;
  if (w.armorFull && c.armorTrack.on < c.armorTrack.slots) return 'Only while every Armor Slot is marked';
  if (w.min && value(w.min.value, c) < w.min.at) return 'Requirement not met';
  for (const res of Object.keys(o.cost ?? {})) {
    if (!['hp', 'stress', 'hope', 'armor'].includes(res) && !resourceDefinitions(c).some((r) => r.id === res)) return `Resource unavailable: ${res}`;
  }
  return null;
}

/** Highest quantity whose scaled cost the character can pay on its own. */
export function quantityMax(c: CharacterSheet, o: MitigationOption): number {
  syncResources(c);
  let max = 20;
  for (const [res, v] of Object.entries(o.cost ?? {})) {
    const n = value(v, c);
    if (n <= 0) continue;
    const have = res === 'hope' ? c.hope.on
      : res === 'stress' ? c.stress.slots - c.stress.on
        : res === 'hp' ? c.hp.slots - c.hp.on
          : res === 'armor' ? c.armorTrack.slots - c.armorTrack.on
            : c.resources?.[res] ?? 0;
    max = Math.min(max, Math.floor(have / n));
  }
  return Math.max(0, max);
}

function dice(state: Record<string, number[]>, id: string, n: number, sides: number, roll: DiceRoll): number[] {
  const pool = state[id] ??= [];
  while (pool.length < n) pool.push(roll(`1d${sides}`));
  return pool.slice(0, n);
}

export function computeDamage(c: CharacterSheet, options: AvailableMitigation[], input: DamageInput, roll: DiceRoll = rollLocal): DamageResult {
  const d = derivedStats(c);
  const rolls = input.rolls ?? {};
  const used: Record<string, number[]> = {};
  const inactive: Record<string, string> = {};
  const qty = (m: AvailableMitigation) => m.option.quantity ? Math.max(0, Math.floor(input.quantities?.[m.id] ?? 1)) : 1;
  let active = options.filter((m) => m.option.passive || input.selected.has(m.id));
  active = active.filter((m) => {
    const why = mitigationBlocker(c, m, input.type) ?? (qty(m) <= 0 ? 'Choose a quantity' : null);
    if (why) inactive[m.id] = why;
    return !why;
  });
  const drop = (pred: (m: AvailableMitigation) => string | null) => {
    active = active.filter((m) => {
      const why = pred(m);
      if (why) inactive[m.id] = why;
      return !why;
    });
  };
  const hits = (m: AvailableMitigation, hpCount = 0): number => {
    const r = m.option.roll;
    if (!r) return 1;
    const count = r.count === 'hp' ? hpCount : r.count === 'quantity' ? qty(m) : r.count === undefined ? 1 : value(r.count, c);
    const faces = dice(rolls, m.id, Math.max(0, count), r.sides, roll);
    used[m.id] = faces;
    if (r.success === undefined) return faces.reduce((a, b) => a + b, 0);
    const n = faces.filter((f) => f >= r.success!).length;
    return r.each ? n : n > 0 ? 1 : 0;
  };
  const effectScale = (m: AvailableMitigation, hpCount = 0) => (m.option.roll?.success !== undefined ? hits(m, hpCount) : 1) * (m.option.roll ? 1 : qty(m));

  const major = d.major + active.reduce((n, m) => n + (m.option.thresholds ?? 0), 0);
  const severe = d.severe + active.reduce((n, m) => n + (m.option.thresholds ?? 0), 0);

  // 1. Resistance (never stacks), then flat and rolled reductions.
  let damage = Math.max(0, Math.floor(input.damage || 0));
  if (active.some((m) => m.option.double && !m.option.when?.severity?.length)) damage *= 2;
  if (active.some((m) => m.option.halve && !m.option.when?.severity?.length)) damage = Math.ceil(damage / 2);
  let reduction = 0;
  for (const m of active.filter((x) => !x.option.when?.severity?.length)) {
    if (m.option.reduce !== undefined) reduction += value(m.option.reduce, c) * qty(m);
    if (m.option.roll && m.option.roll.success === undefined) reduction += hits(m);
  }
  damage = Math.max(0, damage - reduction);

  // 2. Severity from thresholds; severity-conditioned options are checked against it.
  const incoming = severityOf(damage, major, severe);
  drop((m) => {
    const s = m.option.when?.severity;
    if (!s?.length) return null;
    const name = SEVERITY_NAMES[incoming];
    return name && s.includes(name) ? null : `Only on ${s.map((x) => SEVERITY_LABELS[SEVERITY_NAMES.indexOf(x)]).join(' or ')} damage`;
  });
  const negated = incoming > 0 && active.some((m) => m.option.negate);

  // 3. Armor Slots.
  const blockers = active.filter((m) => m.option.armorBlocked);
  const armorBlocked = blockers.length ? `${blockers[0].source}: ${blockers[0].option.label}` : null;
  const free = Math.max(0, c.armorTrack.slots - c.armorTrack.on);
  const base = input.armor && !armorBlocked && !negated && incoming > 0;
  drop(m => m.option.insteadOfArmor && input.armor ? 'Choose Focus or an Armor Slot, not both' : null);
  drop((m) => m.option.when?.armor && !base ? 'Requires marking an Armor Slot' : null);
  const anyArmor = active.some((m) => m.option.armorSlots === 'any');
  const extra = active.reduce((n, m) => n + (typeof m.option.armorSlots === 'number' ? m.option.armorSlots : 0), 0);
  const armorAllowed = armorBlocked ? 0 : anyArmor ? Math.max(1, free) : 1 + extra;
  const armorUsed = !base ? 0 : anyArmor ? Math.max(1, Math.min(armorAllowed, Math.floor(input.armorCount ?? 1))) : 1 + extra;
  drop((m) => m.option.when?.lastArmor && !(armorUsed > 0 && c.armorTrack.on + armorUsed >= c.armorTrack.slots) ? 'Only when marking your last Armor Slot' : null);
  const perSlot = 1 + active.reduce((n, m) => n + (m.option.armorSeverity ?? 0), 0);
  const saved = Math.min(armorUsed, active.reduce((n, m) => n + (m.option.saveArmor ?? 0) * effectScale(m), 0));
  const armorMarked = armorUsed - saved;

  // 4. Severity reductions, then HP.
  let final: Severity = 0;
  let hp = 0;
  if (!negated) {
    const reduce = armorUsed * perSlot + active.reduce((n, m) => n + (m.option.severity ?? 0) * effectScale(m), 0);
    final = Math.max(0, incoming - reduce) as Severity;
    hp = final;
    const before = hp;
    hp = Math.max(0, hp + active.filter((m) => !m.option.when?.lastHp && !m.option.markedHpOnly).reduce((n, m) => n + (m.option.hp ?? 0) * effectScale(m, before), 0));
  }
  const remaining = Math.max(0, c.hp.slots - c.hp.on);
  drop((m) => m.option.when?.lastHp && !(hp > 0 && hp >= remaining) ? 'Only when you would mark your last HP' : null);
  const hpBeforeReplace = Math.min(hp, remaining);
  hp = Math.max(0, hpBeforeReplace + active.filter(m => m.option.markedHpOnly)
    .reduce((n, m) => n + (m.option.hp ?? 0) * effectScale(m, hpBeforeReplace), 0));
  if (active.some((m) => m.option.replaceHp)) hp = 0;
  hp = Math.min(hp, remaining);
  drop((m) => m.option.when?.markingHp && hpBeforeReplace <= 0 ? 'Requires marking at least one HP' : null);
  drop(m => m.option.finalSevere && (negated || final < 3) ? 'Only when taking Severe damage' : null);

  // 5. Costs.
  const costs: Record<string, number> = {};
  const addCost = (cost: MitigationCost | undefined, times: number) => {
    for (const [res, v] of Object.entries(cost ?? {})) {
      const n = value(v, c, hpBeforeReplace) * times;
      if (n) costs[res] = (costs[res] ?? 0) + n;
    }
  };
  for (const m of active) addCost(m.option.cost, qty(m));
  for (const m of active) addCost(m.option.armorCost, armorMarked);
  const quantities: Record<string, number> = {};
  for (const m of active) if (m.option.quantity) quantities[m.id] = qty(m);
  const result: DamageResult = {
    recovery: input.recovery ?? (c.hope.on < c.hope.slots ? 'hope' : 'stress'),
    input: Math.max(0, Math.floor(input.damage || 0)), damage: negated ? 0 : damage, major, severe, incoming, final, negated, hp, hpBeforeReplace,
    armorAllowed, armorUsed, armorMarked, armorBlocked, anyArmor, applied: active, inactive, costs, quantities, rolls, used, error: null,
  };
  result.error = damageBlocker(c, result, input.type);
  return result;
}

function damageBlocker(c: CharacterSheet, r: DamageResult, type: DamageType): string | null {
  syncResources(c);
  for (const m of r.applied.filter(m => m.option.companionIntercept)) {
    const why = mitigationBlocker(c, m, type);
    if (why) return why;
  }
  const armor = r.armorMarked + (r.costs.armor ?? 0);
  if (armor > c.armorTrack.slots - c.armorTrack.on) return 'Not enough free Armor Slots';
  if ((r.costs.hope ?? 0) > c.hope.on) return 'Not enough Hope';
  if (c.stress.on + (r.costs.stress ?? 0) > c.stress.slots) return 'Not enough free Stress slots';
  if (c.hp.on + r.hp + (r.costs.hp ?? 0) > c.hp.slots && (r.costs.hp ?? 0) > 0) return 'Not enough free HP slots';
  for (const [res, n] of Object.entries(r.costs)) {
    if (['hp', 'stress', 'hope', 'armor'].includes(res)) continue;
    const def = resourceDefinitions(c).find((x) => x.id === res);
    if (!def) return `Resource unavailable: ${res}`;
    if ((c.resources?.[res] ?? 0) < n) return `Not enough ${def.label}`;
  }
  const uses = new Map<string, { left: number; n: number }>();
  for (const m of r.applied) {
    const u = usageKey(m);
    if (!u) continue;
    const entry = uses.get(u.key) ?? { left: usesLeft(c, u.key, u.auto) ?? Infinity, n: 0 };
    entry.n++;
    uses.set(u.key, entry);
    if (entry.n > entry.left) return `${m.source}: not enough uses left`;
  }
  return null;
}

/** Marks HP and Armor Slots, pays costs, counts uses, consumes items. Returns the chat summary, or null when blocked. */
export function applyDamage(c: CharacterSheet, r: DamageResult, type: DamageType, roll: DiceRoll = rollLocal): string | null {
  if (damageBlocker(c, r, type)) return null;
  const armor = r.armorMarked + (r.costs.armor ?? 0);
  c.armorTrack.on = Math.min(c.armorTrack.slots, c.armorTrack.on + armor);
  c.hp.on = Math.min(c.hp.slots, c.hp.on + r.hp + (r.costs.hp ?? 0));
  c.stress.on = Math.min(c.stress.slots, c.stress.on + (r.costs.stress ?? 0));
  c.hope.on = Math.max(0, c.hope.on - (r.costs.hope ?? 0));
  c.effects = c.effects.filter(e => !(e.stance && ((!r.negated && r.final >= 3) || c.hp.on >= c.hp.slots))
    && !(e.endOnDamage && !r.negated && r.final >= e.endOnDamage)
    && !(e.beastform && c.hp.on >= c.hp.slots)
    && !(!r.negated && r.final >= 3 && e.endOnSourceSevere && !e.sourceActorId));
  syncEffectDependencies(c);
  for (const [res, n] of Object.entries(r.costs)) {
    if (!['hp', 'stress', 'hope', 'armor'].includes(res)) c.resources![res] = Math.max(0, (c.resources![res] ?? 0) - n);
  }
  const notes: string[] = [];
  for (const m of r.applied) {
    if (m.option.companionIntercept) notes.push(m.option.companionArmored
      ? 'Armored: marked one owner Armor Slot instead of companion Stress for Loyal Friend.'
      : markCompanionDamage(c));
    if (m.option.reflectHp) notes.push(`Eye for an Eye: the adversary within Melee range marks ${r.hp} HP.`);
    if (m.option.recoveryChoice) {
      if (r.recovery === 'stress') {
        const n = Math.min(1, c.stress.on);
        c.stress.on -= n;
        notes.push(`Not Done Yet: cleared ${n} Stress.`);
      } else {
        const n = Math.min(1, c.hope.slots - c.hope.on);
        c.hope.on += n;
        notes.push(`Not Done Yet: gained ${n} Hope.`);
      }
    }
    if (m.option.effect) addEffect(c, m.option.effect, m.source);
    const u = usageKey(m);
    if (u) {
      const entry = c.usage[u.key] ?? { used: 0, per: u.auto.uses!.per };
      entry.used++;
      c.usage[u.key] = entry;
    }
    if (m.option.consumeItem && m.item && m.item.qty > 0) m.item.qty--;
    if (m.option.followUp) notes.push(`${m.option.label}: rolled ${roll(m.option.followUp.dice)} (${m.option.followUp.text})`);
  }
  const bonded = resolveBonded(c, roll);
  if (bonded) notes.push(bonded);
  syncDeathState(c);
  return damageSummary(c, r, type, notes);
}

export function damageSummary(c: CharacterSheet, r: DamageResult, type: DamageType, notes: string[] = []): string {
  const used = r.applied.map((m) => {
    const faces = r.used[m.id];
    return `${m.option.label}${m.option.quantity ? ` x${r.quantities[m.id]}` : ''}${faces?.length ? ` [${faces.join(', ')}]` : ''}`;
  });
  if (r.armorUsed) used.unshift(`${r.armorUsed} Armor Slot${r.armorUsed === 1 ? '' : 's'}${r.armorMarked !== r.armorUsed ? ` (${r.armorMarked} marked)` : ''}`);
  const severity = r.negated ? 'Negated' : `${SEVERITY_LABELS[r.incoming]}${r.final !== r.incoming ? ` → ${SEVERITY_LABELS[r.final]}` : ''}`;
  const cost = costText(r.costs, c);
  return formatDamageReport({
    name: c.name || 'Character', input: r.input, damage: r.damage, type, severity, hp: r.hp,
    lines: [...used.map((u) => `Used ${u}`), ...(cost ? [`Paid ${cost}`] : []), ...notes],
  });
}
