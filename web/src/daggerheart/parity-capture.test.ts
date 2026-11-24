import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import type { Automation, ActionDef, DiceRoll } from './engine';
import {
  actionBlocker, applyRest,   availableRollOptions, commitRollOptions, configuredRollOption, derivedStats, effectiveAdvantage, experienceModifier,
  rollBonus, runActionDetailed, selectedRollBonus, selectionBlocker, sheetActions, suggestedAdvantage, syncResources, traitTotal, useConsumableUnit,
  withSelectedAmount,
} from './engine';
import { emptyCharacter } from './data';
import { armorFrom, cardFrom, weaponFrom } from './compendium';
import type { CompendiumEntry } from './compendium';
import type { CharacterSheet } from './types';

/**
 * Captures the legacy engine's behavior for every row of docs/automation-parity-ledger.csv on one standard sheet.
 * Set PARITY_CAPTURE=1 to rewrite the committed baseline; otherwise the run must reproduce it exactly.
 * The Java ParityLedgerTest replays the same probes through the converted catalogs and compares the results.
 */
const ROOT = new URL('../../../', import.meta.url);
const BASELINE = new URL('src/test/resources/dmmt/automation/parity/ledger-baseline.json', ROOT);

interface Row { category: string; pointer: string }

function csv(text: string): Row[] {
  const rows: string[][] = [];
  let row: string[] = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') { field += '"'; i++; } else if (ch === '"') quoted = false; else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(field); field = ''; }
    else if (ch === '\n') { row.push(field.replace(/\r$/, '')); rows.push(row); row = []; field = ''; }
    else field += ch;
  }
  const [head, ...body] = rows;
  return body.filter(r => r.length === head.length).map(r => Object.fromEntries(head.map((h, i) => [h, r[i]])) as unknown as Row);
}

export const faceOf = (sides: number) => Math.min(3, sides);

/** Every die shows min(3, sides); matches the Java harness so totals are comparable. */
export const constantRoll: DiceRoll = (expression) => {
  let total = 0;
  for (const m of expression.toLowerCase().replace(/\s/g, '').matchAll(/([+-]?)(?:(\d*)d(\d+)(?:kh(\d+))?|(\d+))/g)) {
    const sign = m[1] === '-' ? -1 : 1;
    if (m[3]) {
      const count = m[4] ? Number(m[4]) : m[2] ? Number(m[2]) : 1;
      total += sign * count * faceOf(Number(m[3]));
    } else total += sign * Number(m[5]);
  }
  return total;
};

function catalog(category: string): { entries: Array<CompendiumEntry & { features?: Array<{ name: string; text: string; automation?: Automation }> }> } {
  return JSON.parse(readFileSync(new URL(`dmmap-daggerheart/${category}.json`, ROOT), 'utf8'));
}

export function standardSheet(): CharacterSheet {
  const c = emptyCharacter('Parity');
  c.level = 5;
  c.evasion = 10;
  c.hope = { slots: 6, total: 6, on: 3 };
  c.hp = { slots: 6, total: 12, on: 3 };
  c.stress = { slots: 6, total: 12, on: 3 };
  c.armorTrack = { slots: 4, total: 12, on: 2 };
  c.proficiency = { slots: 6, total: 6, on: 2 };
  c.gold = 20;
  c.traits.forEach(t => { t.mod = 1; t.spellcast = t.name === 'Instinct'; });
  return c;
}

interface Placed { sheet: CharacterSheet; key: string; automation: Automation; consumable?: CharacterSheet['inventory'][number] }

function place(row: Row): Placed | null {
  const parts = row.pointer.split('/').filter(Boolean);
  const entry = catalog(row.category).entries[Number(parts[1])];
  const c = standardSheet();
  if (parts[2] === 'automation') {
    const automation = entry.automation!;
    if (row.category === 'armor') {
      c.activeArmor = armorFrom(entry);
      return { sheet: c, key: `armor:${entry.name}`, automation };
    }
    if (row.category === 'weapons') {
      c.activeWeapons = [weaponFrom(entry)];
      return { sheet: c, key: `weapon:${entry.name}`, automation };
    }
    if (row.category === 'items' || row.category === 'consumables') {
      const consumable = row.category === 'consumables';
      const item = { name: entry.name, image: entry.image, note: entry.text, type: consumable ? 'consumable' as const : 'gear' as const,
        qty: 2, capped: consumable, automation: structuredClone(automation) };
      c.inventory.push(item);
      return { sheet: c, key: `item:${entry.name}`, automation: item.automation, consumable: item };
    }
    if (row.category === 'domain-cards') {
      const card = cardFrom(entry, c);
      c.loadout.push(card);
      return { sheet: c, key: `card:${entry.name}`, automation: card.automation! };
    }
    return null;
  }
  if (parts[2] === 'spells') {
    const card = cardFrom(entry, c);
    c.loadout.push(card);
    const spell = card.spells![Number(parts[3])];
    return { sheet: c, key: `card:${entry.name}/${spell.name}`, automation: spell.automation! };
  }
  const feature = { source: 'parity', name: entry.features![Number(parts[3])].name, text: '',
    automation: structuredClone(entry.features![Number(parts[3])].automation) };
  (row.category === 'classes' ? c.features : c.heritage).push(feature);
  // Legacy resources are defined by whichever sibling feature declares them (e.g. Favor), so the realistic sheet carries those siblings.
  const related = [entry, ...(row.category === 'classes' && entry.parent ? catalog(row.category).entries.filter((e: any) => e !== entry && (e.id === entry.parent || e.name === entry.parent)) : [])];
  related.forEach((owner) => owner.features!.forEach((sibling, index) => {
    if (owner === entry && index === Number(parts[3]) || !sibling.automation?.resources?.length) return;
    (row.category === 'classes' ? c.features : c.heritage).push({ source: 'parity', name: sibling.name, text: '',
      automation: { resources: structuredClone(sibling.automation.resources) } });
  }));
  return { sheet: c, key: `feat:${feature.name}`, automation: feature.automation! };
}

function snapshot(c: CharacterSheet) {
  syncResources(c);
  return {
    hope: c.hope.on, hp: c.hp.on, stress: c.stress.on, armor: c.armorTrack.on, gold: c.gold ?? 0,
    resources: Object.fromEntries(Object.entries(c.resources ?? {}).sort()),
    usage: Object.fromEntries(Object.entries(c.usage).map(([k, v]) => [k, v.used]).sort()),
    effects: c.effects.map(e => e.name).sort(),
    inventory: c.inventory.map(i => `${i.name}:${i.qty}`).sort(),
  };
}

function amountOf(action: ActionDef): number | undefined {
  if (action.selectAmount) return action.selectAmount.min ?? 1;
  return action.resourceRoll || action.castTokens ? 1 : undefined;
}

/** Effects the legacy UI/host applies outside runActionDetailed, which the new engine applies inside the rule. */
function delegatedDelta(action: ActionDef, amount: number | undefined): Record<string, number> {
  const delta: Record<string, number> = {};
  const resource = action.castTokens?.resource ?? action.selectAmount?.resource ?? action.resourceRoll?.resource;
  const hostSpends = action.hostOwned || action.resourceRoll || action.castTokens;
  if (hostSpends && resource && amount !== undefined) delta[resource] = (delta[resource] ?? 0) - amount;
  if (action.transferHope || action.witchAction === 'talisman') delta.hope = (delta.hope ?? 0) - 1;
  if (action.check?.mark === 'stress' && action.check.unless !== 3) delta.stress = (delta.stress ?? 0) + 1;
  if (action.hopeDice) delta.hope = (delta.hope ?? 0) - 1;
  if (action.splendorAction === 'secondWind') delta.hp = (delta.hp ?? 0) - 1;
  // The Java host charges ceil(level / 2) Hope for the borrowed card; the parity harness lends a level 3 card.
  if (action.copycat) delta.hope = (delta.hope ?? 0) - 2;
  // The Java host moves the loadout card to the vault; the "hope" benefit gives its level (the harness card is level 1).
  if (action.channelRawPower) delta.hope = (delta.hope ?? 0) + 1;
  return delta;
}

function probeActions(row: Row) {
  const placed = place(row);
  const out: Record<string, unknown> = {};
  if (!placed) return out;
  const probe = sheetActions(placed.automation);
  for (const original of probe) {
    const fresh = place(row)!;
    const amount = amountOf(original);
    const action = amount === undefined ? original : withSelectedAmount({ ...original, selectedAmount: amount }, amount);
    const available = fresh.automation.actions?.find(a => a.id === original.id) ?? original;
    void available;
    syncResources(fresh.sheet);
    const blocker = actionBlocker(fresh.sheet, fresh.key, fresh.automation, action);
    const before = snapshot(fresh.sheet);
    let after = before, log: string[] | null = null, error: string | undefined;
    if (!blocker) {
      try {
        log = runActionDetailed(fresh.sheet, fresh.key, fresh.automation, action, action.label, constantRoll);
        if (log && fresh.consumable) useConsumableUnit(fresh.sheet, fresh.consumable, action);
        after = snapshot(fresh.sheet);
      } catch (e) { error = String((e as Error).message); }
    }
    const delta = delegatedDelta(original, amount);
    out[original.id] = { label: original.label, ...(amount === undefined ? {} : { amount }), blocked: !!blocker, ...(blocker ? { blocker } : {}),
      ...(Object.keys(delta).length ? { delegatedDelta: delta } : {}),
      ...(error ? { error } : { log, after }) };
  }
  return out;
}

function probeStats(row: Row) {
  const placed = place(row);
  if (!placed) return undefined;
  syncResources(placed.sheet);
  const baseline = derivedStats(standardSheet());
  const stats = derivedStats(placed.sheet);
  const delta = Object.fromEntries(Object.entries(stats).map(([k, v]) => [k, v - (baseline as unknown as Record<string, number>)[k]]).filter(([, v]) => v !== 0));
  return delta;
}

function probeRest(row: Row) {
  const placed = place(row);
  if (!placed) return undefined;
  const result: Record<string, unknown> = {};
  for (const kind of ['short', 'long'] as const) {
    const fresh = place(row)!;
    syncResources(fresh.sheet);
    for (const action of sheetActions(fresh.automation).filter(a => a.uses || fresh.automation.uses)) {
      runActionDetailed(fresh.sheet, fresh.key, fresh.automation, action, action.label, constantRoll);
    }
    const before = snapshot(fresh.sheet);
    applyRest(fresh.sheet, kind);
    const after = snapshot(fresh.sheet);
    result[kind] = { usageBefore: before.usage, usageAfter: after.usage, resourcesAfter: after.resources };
  }
  return result;
}

const TRAITS = ['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge'] as const;

function diceTotal(dice: string[]): number {
  return dice.reduce((sum, d) => sum + constantRoll(d), 0);
}

/** The roll scenarios every row is probed with; the Java harness builds the same ones. */
function rollScenarios(placed: Placed) {
  const scenarios: Array<{ id: string; targets: string[]; weapon?: CharacterSheet['activeWeapons'][number] }> = [];
  for (const kind of ['action', 'reaction']) for (const trait of TRAITS) scenarios.push({ id: `check:${kind}:${trait}`, targets: [kind, `trait:${trait}`] });
  const weapon = placed.sheet.activeWeapons[0];
  if (weapon) {
    scenarios.push({ id: 'attack', targets: ['attack', `trait:${weapon.trait}`, 'action'], weapon });
    scenarios.push({ id: 'damage', targets: ['damage', ...(weapon.damageType === 'magic' ? ['magicdamage'] : [])], weapon });
  }
  return scenarios;
}

/** Weapon rolls need a weapon: rows that are not weapons are probed with a plain Longsword. */
function placeForRolls(row: Row): Placed | null {
  const placed = place(row);
  if (placed && !placed.sheet.activeWeapons.length) placed.sheet.activeWeapons = [weaponFrom(catalog('weapons').entries[1])];
  if (placed) placed.sheet.experiences = [{ name: 'Parity', mod: 2 }];
  return placed;
}

function probeRolls(row: Row) {
  const placed = placeForRolls(row);
  if (!placed) return undefined;
  const out: Record<string, unknown> = {};
  for (const scenario of rollScenarios(placed)) {
    const fresh = placeForRolls(row)!;
    syncResources(fresh.sheet);
    const weapon = scenario.weapon ? fresh.sheet.activeWeapons[0] : undefined;
    const always = rollBonus(fresh.sheet, scenario.targets, weapon, false);
    const options = availableRollOptions(fresh.sheet, scenario.targets, weapon);
    const offered: Record<string, unknown> = {};
    options.forEach((option, index) => {
      const probe = placeForRolls(row)!;
      syncResources(probe.sheet);
      const probeWeapon = scenario.weapon ? probe.sheet.activeWeapons[0] : undefined;
      const mine = availableRollOptions(probe.sheet, scenario.targets, probeWeapon)[index];
      let selected = mine;
      let blocker: string | null = null;
      try { selected = configuredRollOption(probe.sheet, mine); } catch (e) { blocker = (e as Error).message; }
      blocker ??= selectionBlocker(probe.sheet, [selected], !!selected.option.experience);
      const entry: Record<string, unknown> = { label: mine.option.label, ...(mine.option.advantage ? { advantage: mine.option.advantage } : {}) };
      if (blocker) entry.blocker = blocker;
      else {
        const base = rollBonus(probe.sheet, scenario.targets, probeWeapon, false);
        const withOption = selectedRollBonus(probe.sheet, base, [selected]);
        const before = snapshot(probe.sheet);
        const experience = !!selected.option.experience;
        commitRollOptions(probe.sheet, [selected], experience, scenario.targets);
        const after = snapshot(probe.sheet);
        const advantage = effectiveAdvantage(suggestedAdvantage([selected]), [selected]);
        entry.total = withOption.value - base.value + diceTotal(withOption.dice) - diceTotal(base.dice) + advantage * 3
          + (experience ? experienceModifier(2, [selected]) : 0);
        entry.advantageSuggested = suggestedAdvantage([selected]);
        entry.spent = Object.fromEntries((['hope', 'hp', 'stress', 'armor', 'gold'] as const).map(k => [k, after[k] - before[k]]).filter(([, v]) => v !== 0));
      }
      offered[`${mine.option.id}`] = entry;
    });
    const traitName = scenario.targets.find(t => t.startsWith('trait:'))?.slice(6) as TraitName | undefined;
    const traitShift = traitName ? traitTotal(fresh.sheet, traitName) - (fresh.sheet.traits.find(t => t.name === traitName)?.mod ?? 0) : 0;
    out[scenario.id] = { always: always.value + diceTotal(always.dice) + traitShift, offered };
  }
  return out;
}

export function capture(): Record<string, unknown> {
  const rows = csv(readFileSync(new URL('docs/automation-parity-ledger.csv', ROOT), 'utf8'));
  const out: Record<string, unknown> = {};
  for (const row of rows) {
    const key = `${row.category}${row.pointer}`;
    out[key] = { actions: probeActions(row), stats: probeStats(row), rest: probeRest(row), rolls: probeRolls(row) };
  }
  return out;
}

describe('legacy parity baseline', () => {
  it('captures every ledger row deterministically', () => {
    const data = { format: 'dmmap-automation-ledger-baseline', version: 1, rows: capture() };
    const text = JSON.stringify(data, null, 1) + '\n';
    if (process.env.PARITY_CAPTURE) writeFileSync(BASELINE, text);
    else if (existsSync(BASELINE)) expect(text).toBe(readFileSync(BASELINE, 'utf8'));
    expect(Object.keys(data.rows).length).toBeGreaterThan(1000);
  });
});
