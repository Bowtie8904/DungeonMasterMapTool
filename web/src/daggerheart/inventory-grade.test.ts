import { afterEach, expect, it, vi } from 'vitest';
import { gradedItemName, rarityGrade, tierGrade } from './inventory-grade';

class Element {
  className = '';
  title = '';
  children: Array<Element | string> = [];
  classList = { add: (...names: string[]) => { this.className += ` ${names.join(' ')}`; } };
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  addEventListener(): void {}
}

afterEach(() => vi.unstubAllGlobals());

it.each([
  ['common', 1, 'Common rarity'],
  [' UNCOMMON ', 2, 'Uncommon rarity'],
  ['Rare', 3, 'Rare rarity'],
  ['Legendary', 4, 'Legendary rarity'],
] as const)('maps %s rarity to grade %i', (rarity, level, label) => {
  expect(rarityGrade(rarity)).toEqual({ level, label });
});

it('uses the Common color without labeling unclassified rarities as Common', () => {
  for (const value of ['Foraged', '', '  ', 'mythic', undefined, null, 3]) {
    expect(rarityGrade(value)).toEqual({ level: 1, label: 'Unclassified rarity' });
  }
});

it('does not invent equipment tiers', () => {
  for (const value of [0, -1, 2.5, 5, NaN]) expect(tierGrade(value)).toBeUndefined();
  expect([1, 2, 3, 4].map(tierGrade)).toEqual([
    { level: 1, label: 'Tier 1' }, { level: 2, label: 'Tier 2' },
    { level: 3, label: 'Tier 3' }, { level: 4, label: 'Tier 4' },
  ]);
});

it('keeps the name and accessible grade even without artwork', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const name = gradedItemName({ name: 'Old sword' }, tierGrade(3)) as unknown as Element;
  expect(name.className.split(' ')).toContain('inventory-grade-3');
  expect(name.title).toBe('Tier 3');
  expect(name.children).toMatchObject([
    { className: 'item-name-text', children: ['Old sword'] },
    { className: 'inventory-grade-label', children: [' (Tier 3)'] },
  ]);
  const unclassified = gradedItemName({ name: 'Unknown' }, rarityGrade(undefined)) as unknown as Element;
  expect(unclassified.className.split(' ')).toContain('inventory-grade-1');
  expect(unclassified.title).toBe('Unclassified rarity');
  expect(unclassified.children).toMatchObject([
    { className: 'item-name-text', children: ['Unknown'] },
    { className: 'inventory-grade-label', children: [' (Unclassified rarity)'] },
  ]);
  expect(gradedItemName({ name: 'Unknown' }).className).toBe('item-name');
});
