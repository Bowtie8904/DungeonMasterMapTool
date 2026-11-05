// Compendium data (dmmap-daggerheart files served by the host) and helpers that move entries onto a sheet.

import { Armor, CharacterSheet, DomainCard, InventoryItem, Spell, Weapon } from './data';
import type { Automation } from './engine';
import { hostFetch } from '../api';

export interface CompendiumFeature {
  name: string;
  text: string;
  type?: string;
  automation?: Automation;
}

export interface CompendiumEntry {
  id: string;
  name: string;
  tier?: number;
  rarity?: string;
  text?: string;
  automation?: Automation;
  feature?: { name: string; text: string };
  features?: CompendiumFeature[];
  armor?: { score: number; major: number; severe: number };
  weapon?: {
    slot: 'primary' | 'secondary';
    trait: string;
    range: string;
    damage: { dice: string; bonus: number; type: 'physical' | 'magic' };
    burden: string;
  };
  cardType?: string;
  /** Grimoires: the individual spells */
  spells?: Spell[];
  domain?: string;
  level?: number;
  recall?: number;
  kind?: string;
  parent?: string;
  stage?: string;
  domains?: string[];
  startingHp?: number;
  startingEvasion?: number;
  spellcastTrait?: string;
  /** Basic gear offered at character creation (weapons and armor). */
  starter?: boolean;
}

export interface CompendiumFile {
  format: string;
  version: number;
  category: string;
  label: string;
  entries: CompendiumEntry[];
}

export interface CompendiumIndexItem {
  category: string;
  label: string;
  count: number;
}

export type CompendiumFetch = (url: string) => Promise<Response>;
const compendiumFetch: CompendiumFetch = url => hostFetch(url, { credentials: 'same-origin' });

export async function fetchCompendiumIndex(request: CompendiumFetch = compendiumFetch): Promise<CompendiumIndexItem[]> {
  const res = await request('/api/compendium');
  if (!res.ok) throw new Error(`Compendium unavailable (${res.status})`);
  const body = (await res.json()) as { categories?: CompendiumIndexItem[] } | CompendiumIndexItem[];
  return Array.isArray(body) ? body : body.categories ?? [];
}

export async function fetchCompendiumCategory(category: string, request: CompendiumFetch = compendiumFetch): Promise<CompendiumFile> {
  const res = await request(`/api/compendium/${encodeURIComponent(category)}`);
  if (!res.ok) throw new Error(`Cannot load ${category} (${res.status})`);
  return (await res.json()) as CompendiumFile;
}

/** Entries that can be put on a character sheet, with the label of the target list. */
export const ADDABLE: Record<string, string> = {
  armor: 'Add to carried armor',
  weapons: 'Add to carried weapons',
  consumables: 'Add to inventory',
  items: 'Add to inventory',
  'domain-cards': 'Add to vault',
};

export function matchesSearch(e: CompendiumEntry, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const hay = [e.name, e.domain, e.parent, e.rarity, e.cardType, e.text, ...(e.features ?? []).map((f) => f.name)].join(' ').toLowerCase();
  return q.split(/\s+/).every((w) => hay.includes(w));
}

function damageText(w: NonNullable<CompendiumEntry['weapon']>): string {
  const b = w.damage.bonus;
  return `${w.damage.dice}${b ? (b > 0 ? `+${b}` : `${b}`) : ''}`;
}

export function armorFrom(e: CompendiumEntry): Armor {
  const a = e.armor!;
  return { name: e.name, score: a.score, major: a.major, severe: a.severe, tier: e.tier ?? 1, feature: e.feature, automation: e.automation };
}

export function weaponFrom(e: CompendiumEntry): Weapon {
  const w = e.weapon!;
  return {
    name: e.name, slot: w.slot === 'secondary' ? 'Secondary' : 'Primary', damageType: w.damage.type === 'magic' ? 'magic' : 'phys',
    trait: w.trait as Weapon['trait'], range: w.range, damage: damageText(w), hands: /two/i.test(w.burden) ? 2 : 1, tier: e.tier ?? 1,
    feature: e.feature, automation: e.automation,
  };
}

export function cardFrom(e: CompendiumEntry, c: CharacterSheet): DomainCard {
  const spell = e.cardType === 'Spell' || e.cardType === 'Grimoire';
  return {
    level: e.level ?? 1, domain: e.domain ?? '', type: (e.cardType as DomainCard['type']) ?? 'Ability', name: e.name, text: e.text ?? '',
    recall: e.recall ?? 0, spellcast: spell ? c.traits.find((t) => t.spellcast)?.name : undefined, automation: e.automation,
    ...(e.spells?.length ? { spells: structuredClone(e.spells) } : {}),
  };
}

/** Copies a compendium entry onto the sheet. Returns a short description of where it went, or null when not addable. */
export function addToSheet(c: CharacterSheet, category: string, e: CompendiumEntry): string | null {
  if (category === 'armor' && e.armor) {
    c.carriedArmor.push(armorFrom(e));
    return 'carried armor';
  }
  if (category === 'weapons' && e.weapon) {
    c.carriedWeapons.push(weaponFrom(e));
    return 'carried weapons';
  }
  if (category === 'consumables' || category === 'items') {
    const consumable = category === 'consumables';
    const existing = c.inventory.find((i) => i.name === e.name && i.type === (consumable ? 'consumable' : 'gear'));
    if (existing) existing.qty = Math.min(consumable ? 5 : 99, existing.qty + 1);
    else {
      const item: InventoryItem = { name: e.name, note: e.text, type: consumable ? 'consumable' : 'gear', qty: 1, capped: consumable, automation: e.automation };
      c.inventory.push(item);
    }
    return 'inventory';
  }
  if (category === 'domain-cards' && e.domain) {
    if (c.testingCharacter) {
      if (!c.domains.includes(e.domain)) return null;
      if (!c.loadout.some((card) => card.name === e.name)) c.loadout.push(cardFrom(e, c));
      return 'testing loadout (always active)';
    }
    c.vault.push(cardFrom(e, c));
    return 'domain card vault';
  }
  return null;
}

/** One line per automated behaviour, for the compendium detail view. */
export function describeAutomation(a?: Automation): string[] {
  if (!a) return [];
  const out: string[] = [];
  for (const m of a.modifiers ?? []) {
    const v = typeof m.value === 'number' ? (m.value >= 0 ? `+${m.value}` : `${m.value}`) : m.dice ? `+${m.dice}` : String(m.value);
    if (m.stat) out.push(`${m.stat} ${v}`);
    else if (m.roll) out.push(`${m.roll} rolls ${v}${m.consume ? ' (next roll only)' : ''}`);
  }
  if (a.uses) out.push(`${a.uses.max} use(s) per ${a.uses.per}`);
  if (a.restReroll) out.push('Chat: reroll a short-rest downtime die and keep the higher result');
  for (const ac of a.actions ?? []) out.push(`Button: ${ac.label}`);
  for (const resource of a.resources ?? []) out.push(`${resource.label}: starts at ${resource.initial}${resource.max !== undefined ? `, maximum ${resource.max}` : ''}${resource.session ? ', resets when the GM starts a new session' : ''}`);
  if (a.creation) {
    for (const stat of ['hp', 'stress', 'evasion', 'domainCards'] as const) {
      const value = a.creation[stat];
      if (value) out.push(`At creation: ${stat === 'domainCards' ? 'domain cards' : stat.toUpperCase()} ${value > 0 ? '+' : ''}${value}`);
    }
    if (a.creation.domains?.length) out.push(`At creation: additional domains ${a.creation.domains.join(', ')}`);
  }
  for (const option of a.rollOptions ?? []) {
    const cost = Object.entries(option.cost ?? {}).map(([resource, amount]) => `${amount} ${resource}`).join(', ');
    out.push(`Optional ${option.roll} bonus: ${option.label}${cost ? ` (cost: ${cost}${option.quantity ? ' per token' : ''})` : ''}${option.hopeDie ? `; Hope d${option.hopeDie}` : ''}${option.input ? `; ${option.input.label} (default +${option.input.default})` : ''}${option.quantity ? '; choose how many tokens to spend' : ''}`);
  }
  if (a.damage) out.push(`Weapon damage: ${a.damage.extraDice} extra dice, discard ${a.damage.dropLowest} lowest`);
  return out;
}

/** Features of a class/ancestry/community entry as sheet features. */
export function featuresOf(e: CompendiumEntry, source: string): Array<{ source: string; name: string; text: string; automation?: Automation }> {
  return (e.features ?? []).map((f) => ({ source, name: f.name, text: f.text, automation: f.automation }));
}
