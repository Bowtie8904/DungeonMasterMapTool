import { isCatalogObject, type CatalogObject, type CatalogValue } from './catalog-fields';

export type VariantFieldKind = 'text' | 'long' | 'number' | 'dice' | 'select';
type Path = Array<string | number>;

/** One flat, human-readable value of an entry; `path` addresses it inside the working copy. */
export interface VariantField {
  section: string;
  label: string;
  kind: VariantFieldKind;
  path: Path;
  value: string | number;
  options?: string[];
}

const DICE = /^\s*\d*d\d+\s*([+-]\s*\d+)?\s*$/i;
export const isDiceFormula = (value: string): boolean => DICE.test(value);

const TRAITS = ['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge'];
const RANGES = ['Melee', 'Very Close', 'Close', 'Far', 'Very Far'];
const BURDENS = ['One-Handed', 'Two-Handed'];
const DAMAGE_TYPES = ['physical', 'magical'];
const RARITIES = ['Common', 'Uncommon', 'Rare', 'Legendary'];

const KEY_LABELS: Record<string, string> = {
  value: 'Bonus', amount: 'Amount', dice: 'Dice', damage: 'Damage dice', max: 'Maximum uses', stress: 'Stress cost',
  hope: 'Hope cost', armor: 'Armor slots cost', difficulty: 'Difficulty', proficiency: 'Proficiency bonus',
  extraDice: 'Extra dice', dropLowest: 'Lowest dice dropped', onesAs: 'Ones count as', bonus: 'Bonus', gold: 'Gold cost',
  fail: 'Failure value', success: 'Success value', sides: 'Die sides', initial: 'Starting amount', min: 'Minimum',
  expression: 'Dice', default: 'Default value', hopeDie: 'Hope die size', damageDie: 'Damage die size', expendOn: 'Spent on',
  extraMoves: 'Extra moves', minHope: 'Minimum Hope', minimumHope: 'Minimum Hope', markStress: 'Stress marked',
  gainHope: 'Hope gained', giveFear: 'Fear given', saveArmor: 'Armor slots saved', armorSeverity: 'Armor severity',
};

function humanize(text: string): string {
  const words = text.replace(/^trait[.:]/, '').replace(/[_.:]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
  return words ? words[0].toUpperCase() + words.slice(1) : '';
}

function describe(parent: CatalogObject): string {
  if (typeof parent.stat === 'string') return humanize(parent.stat);
  if (typeof parent.roll === 'string') return `${humanize(parent.roll)} roll`;
  if (typeof parent.resource === 'string') return humanize(parent.resource).toUpperCase() === 'HP' ? 'HP' : humanize(parent.resource);
  return '';
}

function sectionOf(chain: CatalogObject[]): string {
  for (let i = chain.length - 1; i >= 0; i--) {
    const label = chain[i].label;
    if (typeof label === 'string' && label.trim()) return label.trim();
  }
  return 'Passive effects';
}

/** Every flat value a DM can sensibly change: statistics, numbers, dice formulas and descriptions. */
export function variantFields(entry: CatalogObject): VariantField[] {
  const fields: VariantField[] = [];
  const add = (field: VariantField): void => { fields.push(field); };
  const select = (current: unknown, options: string[]): string[] =>
    typeof current === 'string' && current && !options.includes(current) ? [...options, current] : options;

  if (typeof entry.text === 'string') add({ section: 'Description', label: 'Description', kind: 'long', path: ['text'], value: entry.text });
  if (typeof entry.rarity === 'string') {
    add({ section: 'Description', label: 'Rarity', kind: 'select', path: ['rarity'], value: entry.rarity, options: select(entry.rarity, RARITIES) });
  }
  if (typeof entry.tier === 'number') add({ section: 'Description', label: 'Tier', kind: 'number', path: ['tier'], value: entry.tier });

  const feature = entry.feature;
  if (isCatalogObject(feature)) {
    if (typeof feature.name === 'string') add({ section: 'Feature', label: 'Feature name', kind: 'text', path: ['feature', 'name'], value: feature.name });
    if (typeof feature.text === 'string') add({ section: 'Feature', label: 'Feature text', kind: 'long', path: ['feature', 'text'], value: feature.text });
  }

  const armor = entry.armor;
  if (isCatalogObject(armor)) {
    const labels: Record<string, string> = { score: 'Armor score', major: 'Major threshold', severe: 'Severe threshold' };
    for (const key of ['score', 'major', 'severe']) {
      if (typeof armor[key] === 'number') add({ section: 'Armor', label: labels[key], kind: 'number', path: ['armor', key], value: armor[key] });
    }
  }

  const weapon = entry.weapon;
  if (isCatalogObject(weapon)) {
    const choice = (key: string, label: string, options: string[]): void => {
      if (typeof weapon[key] === 'string') add({ section: 'Weapon', label, kind: 'select', path: ['weapon', key], value: weapon[key], options: select(weapon[key], options) });
    };
    choice('trait', 'Trait', TRAITS);
    choice('range', 'Range', RANGES);
    const damage = weapon.damage;
    if (isCatalogObject(damage)) {
      if (typeof damage.dice === 'string') add({ section: 'Weapon', label: 'Damage dice', kind: 'dice', path: ['weapon', 'damage', 'dice'], value: damage.dice });
      if (typeof damage.bonus === 'number') add({ section: 'Weapon', label: 'Damage bonus', kind: 'number', path: ['weapon', 'damage', 'bonus'], value: damage.bonus });
      if (typeof damage.type === 'string') {
        add({ section: 'Weapon', label: 'Damage type', kind: 'select', path: ['weapon', 'damage', 'type'], value: damage.type, options: select(damage.type, DAMAGE_TYPES) });
      }
    }
    choice('burden', 'Burden', BURDENS);
  }

  const automation = entry.automation;
  if (isCatalogObject(automation)) {
    const seen = new Map<string, number>();
    const walk = (value: CatalogValue, path: Path, chain: CatalogObject[], inEffect: boolean): void => {
      if (Array.isArray(value)) {
        value.forEach((child, index) => { if (typeof child === 'object') walk(child, [...path, index], chain, inEffect); });
        return;
      }
      if (!isCatalogObject(value)) return;
      const here = [...chain, value];
      for (const [key, child] of Object.entries(value)) {
        const childPath = [...path, key];
        if (typeof child === 'object' && child !== null) { walk(child, childPath, here, inEffect || key === 'effect'); continue; }
        const kind: VariantFieldKind | '' = typeof child === 'number' ? 'number'
          : typeof child === 'string' && (key === 'text' || key === 'description') ? 'long'
            : typeof child === 'string' && key !== 'name' && DICE.test(child) ? 'dice' : '';
        if (!kind || key === 'version') continue;
        const section = sectionOf(here);
        const who = describe(value);
        const base = key === 'text' ? (inEffect ? 'Effect description' : 'Rules text')
          : key === 'description' ? 'Description' : (KEY_LABELS[key] ?? humanize(key));
        const label = who ? `${who}: ${base}` : base;
        const count = (seen.get(`${section}|${label}`) ?? 0) + 1;
        seen.set(`${section}|${label}`, count);
        add({ section, label: count > 1 ? `${label} (${count})` : label, kind, path: [...automationPath, ...childPath], value: child as string | number });
      }
    };
    const automationPath: Path = ['automation'];
    walk(automation, [], [], false);
  }
  return fields;
}

export function readPath(entry: CatalogObject, path: Path): CatalogValue | undefined {
  let current: CatalogValue | undefined = entry;
  for (const key of path) {
    if (current === null || typeof current !== 'object') return undefined;
    current = (current as Record<string | number, CatalogValue>)[key];
  }
  return current;
}

/** Writes a raw form value into the working copy, keeping numbers numeric. Returns false when the value is invalid. */
export function writeField(entry: CatalogObject, field: VariantField, raw: string): boolean {
  let value: string | number = raw;
  if (field.kind === 'number') {
    const parsed = Number(raw);
    if (raw.trim() === '' || !Number.isFinite(parsed)) return false;
    value = parsed;
  } else if (field.kind === 'dice') {
    if (!isDiceFormula(raw)) return false;
    value = raw.trim();
  }
  let target = entry as unknown as Record<string | number, CatalogValue>;
  for (const key of field.path.slice(0, -1)) target = target[key] as unknown as Record<string | number, CatalogValue>;
  target[field.path[field.path.length - 1]] = value;
  return true;
}

/** Replaces a changed dice formula inside descriptive text so "Clear 1d4 HP." follows its formula. */
export function replaceFormula(text: string, previous: string, next: string): string {
  const old = previous.trim();
  if (!old || old === next.trim()) return text;
  const escaped = old.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s*');
  return text.replace(new RegExp(`(?<![\\w])${escaped}(?![\\w])`, 'gi'), next.trim());
}
