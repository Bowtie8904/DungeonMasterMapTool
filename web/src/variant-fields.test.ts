import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { createVariant, type CatalogObject } from './catalog-fields';
import { readPath, replaceFormula, variantFields, writeField } from './variant-fields';

const load = (category: string, id: string): CatalogObject => {
  const file = JSON.parse(readFileSync(new URL(`../../dmmap-daggerheart/${category}.json`, import.meta.url), 'utf8')) as { entries: CatalogObject[] };
  return file.entries.find(entry => entry.id === id)!;
};

describe('flat variant fields', () => {
  it('exposes a healing potion dice formula and description, but no plumbing', () => {
    const potion = structuredClone(load('consumables', 'minor-health-potion'));
    const fields = variantFields(potion);
    const dice = fields.find(field => field.kind === 'dice')!;
    expect(dice.value).toBe('1d4');
    expect(fields.some(field => field.label === 'Description')).toBe(true);
    expect(fields.every(field => !/\b(id|op|name)\b/i.test(field.label))).toBe(true);
    expect(writeField(potion, dice, '2d6+1')).toBe(true);
    expect(writeField(potion, dice, 'banana')).toBe(false);
    expect(readPath(potion, dice.path)).toBe('2d6+1');
    expect(replaceFormula('Clear 1d4 HP.', '1d4', '2d6+1')).toBe('Clear 2d6+1 HP.');
    expect(replaceFormula('Clear 11d4 HP.', '1d4', '2d6')).toBe('Clear 11d4 HP.');
  });

  it('exposes weapon statistics and feature text, and armor numbers', () => {
    const bow = structuredClone(load('weapons', 'longbow'));
    const labels = variantFields(bow).map(field => field.label);
    expect(labels).toEqual(expect.arrayContaining(['Feature text', 'Damage dice', 'Damage bonus', 'Range', 'Trait']));
    const feature = variantFields(bow).find(field => field.label === 'Feature text')!;
    writeField(bow, feature, 'Custom text');
    expect((bow.feature as CatalogObject).text).toBe('Custom text');
    const bonus = variantFields(bow).find(field => field.label === 'Damage bonus')!;
    expect(writeField(bow, bonus, '5')).toBe(true);
    expect(((bow.weapon as CatalogObject).damage as CatalogObject).bonus).toBe(5);
    const plate = structuredClone(load('armor', 'full-plate-armor'));
    const armor = variantFields(plate);
    expect(armor.map(field => field.label)).toEqual(expect.arrayContaining(['Armor score', 'Major threshold', 'Severe threshold', 'Evasion: Bonus']));
    expect(armor.find(field => field.label === 'Evasion: Bonus')).toBeDefined();
  });

  it('keeps edited values after the unique-name copy is made', () => {
    const potion = structuredClone(load('consumables', 'bolster-potion'));
    const fields = variantFields(potion);
    const bonus = fields.find(field => field.label.endsWith('Bonus'))!;
    writeField(potion, bonus, '3');
    const copy = createVariant(potion, 'Red Lemonade', []);
    expect(copy.name).toBe('Red Lemonade');
    expect(JSON.stringify(copy)).toContain('(Red Lemonade)');
    expect(JSON.stringify(copy)).toContain('"value":3');
  });
});
