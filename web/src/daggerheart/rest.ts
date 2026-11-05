// Daggerheart downtime: which moves a rest offers and what choosing them does to the sheet.
import { CharacterSheet, tierOf } from './data';
import {
  ActionDef, Automation, DiceRoll, RestBenefit, RestKind, actionBlocker, applyRest, evalValue, rollLocal, runAction, syncDerived,
} from './engine';
import { formatRestReport } from './rest-summary';
import type { CompendiumEntry } from './compendium';
import type { AncestryChoice } from './ancestry-choice';
import { replaceAncestry } from './rules';
import { syncDeathState } from './death';

export interface RestMove { id: string; label: string; /** the move can be aimed at an ally (chat only) */ ally?: boolean; hint?: string; ancestryChoice?: ActionDef['ancestryChoice'] }
export interface RestSlot { label: string; moves: RestMove[] }
export interface RestPick { move: string; ally?: boolean; ancestry?: AncestryChoice }

/** A passive benefit of the sheet that the player may switch off for this rest (e.g. not using the bedroll). */
export interface RestPassive { id: string; label: string; defaultOn: boolean }
export interface RestOptions {
  /** Overrides per passive id; absent means the passive's default. */
  passives?: Record<string, boolean>;
  ancestries?: CompendiumEntry[];
}

export const MAX_SHORT_RESTS_IN_ROW = 3;

const SHORT_MOVES: RestMove[] = [
  { id: 'tend', label: 'Tend to Wounds (1d4+Tier HP)', ally: true },
  { id: 'stress', label: 'Clear Stress (1d4+Tier)' },
  { id: 'repair', label: 'Repair Armor (1d4+Tier Armor Slots)', ally: true },
];
const PREPARE: RestMove[] = [
  { id: 'prepare', label: 'Prepare (alone): gain 1 Hope' },
  { id: 'prepare-group', label: 'Prepare (with others): gain 2 Hope' },
];
const LONG_MOVES: RestMove[] = [
  { id: 'tend-all', label: 'Tend to All Wounds (clear all HP)', ally: true },
  { id: 'stress-all', label: 'Clear All Stress' },
  { id: 'repair-all', label: 'Repair All Armor', ally: true },
  { id: 'project', label: 'Work on a Project' },
];

interface Source { key: string; name: string; automation: Automation; item?: CharacterSheet['inventory'][number] }
function sources(c: CharacterSheet): Source[] {
  const out: Source[] = [];
  const add = (key: string, name: string, automation: Automation | undefined, item?: Source['item']) => {
    if (automation) out.push({ key, name, automation, item });
  };
  for (const f of [...c.features, ...c.heritage]) add(`feat:${f.name}`, f.name, f.automation);
  for (const d of c.loadout) {
    add(`card:${d.name}`, d.name, d.automation);
    for (const s of d.spells ?? []) add(`card:${d.name}/${s.name}`, s.name, s.automation);
  }
  for (const i of c.inventory.filter((i) => i.qty > 0)) add(`item:${i.name}`, i.name, i.automation, i);
  for (const w of c.activeWeapons) add(`weapon:${w.name}`, w.name, w.automation);
  if (c.activeArmor) add(`armor:${c.activeArmor.name}`, c.activeArmor.name, c.activeArmor.automation);
  return out;
}

function consume(s: Source): void {
  if (!s.item || s.item.qty <= 0) throw new Error(`${s.name}: no item left to consume`);
  s.item.qty--;
}

export function shortRestBlocker(c: CharacterSheet, kind: RestKind): string | null {
  return kind === 'short' && (c.shortRestsInRow ?? 0) >= MAX_SHORT_RESTS_IN_ROW
    ? `The party has taken ${MAX_SHORT_RESTS_IN_ROW} short rests in a row and must take a long rest next.` : null;
}

export function canTakeLongMovesInShortRest(c: CharacterSheet): boolean {
  return sources(c).some((s) => s.automation.rest?.longMovesInShort);
}

const LONG_IDS = new Set(LONG_MOVES.map((m) => m.id));
const moveKey = (id: string): string => (id === 'prepare-group' ? 'prepare' : id);

/** Why `id` cannot be chosen for slot `index` given the other picks, or null. */
export function moveBlocker(kind: RestKind, picks: RestPick[], index: number, id: string): string | null {
  const others = picks.filter((p, i) => i !== index && p.move);
  if (others.some((p) => moveKey(p.move) === moveKey(id))) return 'already chosen';
  if (kind === 'short' && LONG_IDS.has(id) && others.some((p) => LONG_IDS.has(p.move))) return 'only one long-rest move per short rest';
  return null;
}

/** Passive benefits and optional extras of the sheet; each can be switched off before resting. */
export function restPassives(c: CharacterSheet, kind: RestKind): RestPassive[] {
  return benefits(c, kind).map(({ s, b, id }) => ({ id, label: `${s.name}: ${b.label}`, defaultOn: b.defaultOn }));
}

function benefits(c: CharacterSheet, kind: RestKind): Array<{ s: Source; b: RestBenefit; id: string }> {
  return sources(c).flatMap((s) => (s.automation.rest?.passives ?? [])
    .filter((b) => (!b.rest || b.rest === kind) && !actionBlocker(c, s.key, s.automation, b, 'rest'))
    .map((b) => ({ s, b, id: `${s.key}|${b.id}` })));
}
export function passiveOn(c: CharacterSheet, kind: RestKind, o: RestOptions, id: string): boolean {
  const p = restPassives(c, kind).find((x) => x.id === id);
  return !!p && (o.passives?.[id] ?? p.defaultOn);
}

function downtimeActions(c: CharacterSheet, kind: RestKind): RestMove[] {
  return sources(c).flatMap((s) => (s.automation.actions ?? [])
    .filter((a) => a.timing === 'downtime'
      && (!a.downtime?.rest || a.downtime.rest === kind) && !actionBlocker(c, s.key, s.automation, a, 'rest'))
    .map((a) => ({ id: `act:${s.key}|${a.id}`, label: `${s.name}: ${a.label}`, ancestryChoice: a.ancestryChoice })));
}

export function availableMoves(c: CharacterSheet, kind: RestKind): RestMove[] {
  const long = kind === 'long' || canTakeLongMovesInShortRest(c);
  return [...(kind === 'short' ? SHORT_MOVES : []), ...PREPARE, ...(long ? LONG_MOVES : []), ...downtimeActions(c, kind)];
}

export function restSlots(c: CharacterSheet, kind: RestKind, o: RestOptions = {}): RestSlot[] {
  const moves = availableMoves(c, kind);
  const slots: RestSlot[] = [{ label: 'Move 1', moves }, { label: 'Move 2', moves }];
  for (const s of sources(c)) {
    for (let i = 0; i < (s.automation.rest?.extraMoves ?? 0); i++) slots.push({ label: `Extra move (${s.name})`, moves });
  }
  for (const { s, b, id } of benefits(c, kind)) {
    if (passiveOn(c, kind, o, id)) {
      for (let i = 0; i < (b.extraMoves ?? 0); i++) slots.push({ label: `Extra move (${s.name})`, moves });
    }
  }
  return slots;
}

export interface RestDie {
  line: number;
  expression: string;
  value: number;
  resource?: 'hp' | 'stress' | 'armor';
  recovery?: number;
  cleared?: number;
  used?: boolean;
}
export interface RestResult { lines: string[]; dice?: RestDie[] }

export function resolveRest(c: CharacterSheet, kind: RestKind, picks: RestPick[], o: RestOptions = {}, roll: DiceRoll = rollLocal): RestResult {
  const next = structuredClone(c);
  const result = resolveRestOnSheet(next, kind, picks, o, roll);
  Object.assign(c, next);
  return result;
}

function resolveRestOnSheet(c: CharacterSheet, kind: RestKind, picks: RestPick[], o: RestOptions, roll: DiceRoll): RestResult {
  const restBlock = shortRestBlocker(c, kind);
  if (restBlock) throw new Error(restBlock);
  syncDerived(c);
  const tier = tierOf(c.level);
  const lines: string[] = [];
  const rolledDice: RestDie[] = [];
  const hpBefore = c.hp.on;
  const stressBefore = c.stress.on;
  const slots = restSlots(c, kind, o);
  if (picks.length > slots.length) throw new Error('Too many downtime moves chosen');
  picks.forEach((p, i) => {
    if (!p.move) return;
    if (!slots[i].moves.some((m) => m.id === p.move)) throw new Error(`${slots[i].label}: that move is not available`);
    const block = moveBlocker(kind, picks, i, p.move);
    if (block) throw new Error(`${slots[i].moves.find((m) => m.id === p.move)!.label}: ${block}`);
  });
  const chosen = picks.filter((p) => p.move);
  const previousEffects = new Set(c.effects.map((e) => e.id));
  const selected = benefits(c, kind).filter(({ id }) => passiveOn(c, kind, o, id));
  const reroll = selected.some(({ b }) => b.reroll);
  const dice = (expr: string): { value: number; text: string } => {
    const first = roll(expr);
    rolledDice.push({ line: lines.length, expression: expr, value: first });
    if (!reroll) return { value: first, text: String(first) };
    const second = roll(expr);
    return { value: Math.max(first, second), text: `${first}/${second}, kept ${Math.max(first, second)}` };
  };
  const floor = (t: typeof c.hp) => t === c.hp ? c.permanentHp ?? 0 : 0;
  const clear = (t: typeof c.hp, n: number): number => { const k = Math.min(Math.max(0, t.on - floor(t)), Math.max(0, n)); t.on -= k; return k; };
  const recovery = (t: typeof c.hp, resource: RestDie['resource'], n: number): number => {
    const die = rolledDice[rolledDice.length - 1];
    die.resource = resource;
    die.recovery = Math.max(floor(t), t.on - n);
    die.cleared = Math.min(Math.max(0, t.on - floor(t)), Math.max(0, n));
    return clear(t, n);
  };

  const execute = (s: Source, action: ActionDef): void => {
    const rolls: string[] = [];
    const result = runAction(c, s.key, s.automation, action, `${s.name}: ${action.label}`, (expression, clearingResource) => {
      const r = dice(expression);
      if (clearingResource === 'hp' || clearingResource === 'stress' || clearingResource === 'armor') {
        const track = clearingResource === 'hp' ? c.hp : clearingResource === 'stress' ? c.stress : c.armorTrack;
        const die = rolledDice[rolledDice.length - 1];
        die.resource = clearingResource;
        die.recovery = Math.max(floor(track), track.on - r.value);
        die.cleared = Math.min(Math.max(0, track.on - floor(track)), Math.max(0, r.value));
      }
      rolls.push(`${expression} = ${r.text}`);
      return r.value;
    }, 'rest');
    if (result === null) throw new Error(`${s.name}: ${actionBlocker(c, s.key, s.automation, action, 'rest') ?? 'move could not be used'}`);
    lines.push(`${result}${rolls.length ? ` (${rolls.join('; ')})` : ''}`);
  };
  for (const { s, b } of selected.filter(({ b }) => b.extraMoves)) {
    execute(s, b);
    if (b.consumeItem) consume(s);
  }
  const modifiers = sources(c).flatMap((s) => (s.automation.rest?.modifiers ?? []).map((m) => ({ s, m })));

  for (const p of chosen) {
    const applicable = modifiers.filter(({ m }) => m.move === p.move
      && (!m.rest || m.rest === kind) && (!m.target || (m.target === 'ally' && p.ally)));
    const moveBenefits = selected.filter(({ b }) => b.move === p.move);
    const bonus = applicable.reduce((n, { m }) => n + (m.bonus ?? 0), 0)
      + moveBenefits.reduce((n, { b }) => n + (b.bonus ?? 0), 0);
    const bonusNames = [...applicable.filter(({ m }) => m.bonus).map(({ s }) => s.name),
      ...moveBenefits.filter(({ b }) => b.bonus).map(({ s }) => s.name)];
    const bonusText = bonus ? `, +${bonus} ${bonusNames.join(', ')}` : '';
    switch (p.move) {
      case 'tend': {
        const r = dice(`1d4+${tier}`);
        if (p.ally) {
          lines.push(`Tend to Wounds on an ally: clears ${r.value + bonus} HP (1d4+${tier} = ${r.text}${bonusText})`);
        } else lines.push(`Tend to Wounds on self: clears ${recovery(c.hp, 'hp', r.value + bonus)} HP (1d4+${tier} = ${r.text}${bonusText})`);
        break;
      }
      case 'stress': {
        const r = dice(`1d4+${tier}`);
        lines.push(`Clear Stress: clears ${recovery(c.stress, 'stress', r.value + bonus)} Stress (1d4+${tier} = ${r.text}${bonusText})`);
        break;
      }
      case 'repair': {
        const r = dice(`1d4+${tier}`);
        if (p.ally) lines.push(`Repair Armor on an ally: clears ${r.value + bonus} Armor Slots (1d4+${tier} = ${r.text}${bonusText})`);
        else lines.push(`Repair Armor: clears ${recovery(c.armorTrack, 'armor', r.value + bonus)} Armor Slots (1d4+${tier} = ${r.text}${bonusText})`);
        break;
      }
      case 'tend-all': lines.push(p.ally ? 'Tend to All Wounds on an ally' : `Tend to All Wounds: clears ${clear(c.hp, c.hp.on)} HP`); break;
      case 'stress-all': lines.push(`Clear All Stress: clears ${clear(c.stress, c.stress.on)} Stress`); break;
      case 'repair-all': lines.push(p.ally ? 'Repair All Armor on an ally' : `Repair All Armor: clears ${clear(c.armorTrack, c.armorTrack.on)} Armor Slots`); break;
      case 'project': lines.push('Works on a Project'); break;
      case 'prepare': case 'prepare-group': {
        const n = p.move === 'prepare' ? 1 : 2;
        const before = c.hope.on;
        c.hope.on = Math.min(c.hope.slots, c.hope.on + n);
        lines.push(`${p.move === 'prepare' ? 'Prepare (alone)' : 'Prepare (with others)'}: gains ${c.hope.on - before} Hope`);
        break;
      }
      default: {
        const [key, actionId] = p.move.slice(4).split('|');
        const s = sources(c).find((x) => x.key === key);
        const a = s?.automation.actions?.find((x) => x.id === actionId);
        if (!s || !a) throw new Error('Downtime move no longer available');
        if (a.ancestryChoice) {
          if (!p.ancestry || !o.ancestries) throw new Error('Choose an ancestry feature for this downtime move.');
          if (a.ancestryChoice === 'feature' && p.ancestry.ancestry !== c.ancestry) throw new Error('Only Skin Deep must choose a feature of your current ancestry.');
          if (a.ancestryChoice === 'feature' && p.ancestry.feature === c.ancestryFeature) throw new Error('Choose a different ancestry feature.');
          replaceAncestry(o.ancestries, c, p.ancestry.ancestry, p.ancestry.feature);
          lines.push(`${s.name}: ${c.ancestry} - ${c.ancestryFeature}`);
        }
        execute(s, a);
        const table = a.downtime?.table;
        if (table) {
          const evaluate = (expression: string) => expression.replace(/@[a-z]+/gi, (value) => String(evalValue(value, c)));
          const countRoll = table.repeat ? dice(evaluate(table.repeat)) : null;
          const count = countRoll ? Math.max(0, Math.floor(countRoll.value)) : 1;
          if (count > 100) throw new Error('Downtime table exceeds 100 results');
          if (countRoll) lines.push(`${s.name}: ${table.repeat} = ${countRoll.text}; ${count} results`);
          for (let i = 0; i < count; i++) {
            const r = dice(evaluate(table.expression));
            const outcome = table.outcomes[Math.max(0, Math.min(table.outcomes.length - 1, r.value - 1))];
            if (!outcome) throw new Error(`${s.name}: empty downtime roll table`);
            lines.push(`${s.name}: ${table.expression} = ${r.text}: ${outcome}`);
          }
        }
        if (a.downtime?.consumeItem || s.item?.type === 'consumable') consume(s);
      }
    }
    for (const { s, m } of applicable) {
      if (m.steps?.length) execute(s, { id: `modifier:${p.move}`, label: 'Rest move benefit', steps: m.steps });
      if (m.note) lines.push(`${s.name}: ${m.note}`);
    }
    for (const { s, b } of moveBenefits) {
      if (b.steps?.length || b.cost) execute(s, b);
      if (b.consumeItem) consume(s);
    }
  }

  for (const { s, b } of selected.filter(({ b }) => !b.extraMoves && !b.move && !b.reroll && b.condition !== 'noRecovery')) {
    if (b.condition === 'armorMarked' && c.armorTrack.on === 0) continue;
    execute(s, b);
    if (b.consumeItem) consume(s);
  }

  c.shortRestsInRow = kind === 'long' ? 0 : (c.shortRestsInRow ?? 0) + 1;
  if (kind === 'long') c.longRestRevision = (c.longRestRevision ?? 0) + 1;
  const gainedEffects = c.effects.filter((e) => !previousEffects.has(e.id));
  applyRest(c, kind);
  // Effects created by a downtime move last until the next rest, not this one.
  c.effects.push(...gainedEffects.filter((e) => !c.effects.some((current) => current.id === e.id)));
  if (c.hp.on === hpBefore && c.stress.on === stressBefore) {
    for (const { s, b } of selected.filter(({ b }) => b.condition === 'noRecovery')) execute(s, b);
  }
  syncDeathState(c);
  return { lines, dice: rolledDice };
}

/** One chat message; the host clips chat text at 1000 characters, so keep it compact. */
export function restSummary(c: CharacterSheet, kind: RestKind, picks: RestPick[], result: RestResult): string {
  const moves = picks.filter((p) => p.move).length;
  return formatRestReport({
    kind,
    subtitle: `${c.name} · ${moves} downtime move${moves === 1 ? '' : 's'}`,
    lines: result.lines.length ? result.lines : ['No downtime moves chosen'],
  });
}
