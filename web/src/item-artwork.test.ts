import { afterEach, expect, it, vi } from 'vitest';
import { itemImagePath, itemName } from './item-artwork';
import { addToSheet, armorFrom, weaponFrom, type CompendiumEntry } from './daggerheart/compendium';
import { emptyCharacter, normalizeSheet } from './daggerheart/data';
import { updateCharacterAutomation } from './daggerheart/persist';
import { renderSharedCard } from './shared-cards';

class Element {
  children: Array<Element | string> = [];
  listeners = new Map<string, () => void>();
  classList = { toggle: () => {} };
  replacement?: Element;
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  replaceWith(node: Element): void { this.replacement = node; }
  all(): Element[] { return [this, ...this.children.flatMap(child => typeof child === 'string' ? [] : child.all())]; }
}

afterEach(() => vi.unstubAllGlobals());
const image = '/item-icons/lorc/potion-ball.svg';

it('allows only local icon paths without schemes, traversal or markup', () => {
  expect(itemImagePath(image)).toBe(image);
  for (const value of [undefined, '', '/item-icons/../secret.svg', '//example.org/x.svg',
    'https://example.org/x.svg', 'data:image/svg+xml,test', '<svg>', '/item-icons/lorc/a.svg?x=1', '/item-icons/lorc/%2e.svg']) {
    expect(itemImagePath(value)).toBeNull();
  }
});

it('renders decorative artwork in names and transfer cards without hiding legacy names', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const name = itemName({ name: 'Potion', image }) as unknown as Element;
  expect(name.all().find(node => node.tag === 'img')).toMatchObject({ src: image, alt: '', draggable: false });
  expect((itemName({ name: 'Custom' }) as unknown as Element).all().some(node => node.tag === 'img')).toBe(false);
  const card = renderSharedCard({
    category: 'item', item: { name: 'Potion', image, type: 'consumable', qty: 2 },
    cardId: 'offer', actorId: 'hero', actorName: 'Hero', available: 2, offerQuantity: 2,
  }) as unknown as Element;
  expect(card.all().find(node => node.tag === 'img')).toMatchObject({ src: image });
  const icon = name.all().find(node => node.tag === 'img')!;
  icon.listeners.get('error')!();
  expect(icon.replacement).toMatchObject({ title: `Item image unavailable: ${image}`, ariaLabel: 'Item image unavailable' });
});

it('preserves artwork through catalog conversions and sheet serialization', () => {
  const entry: CompendiumEntry = { id: 'potion', name: 'Potion', image };
  const c = emptyCharacter('Hero');
  addToSheet(c, 'consumables', entry);
  expect(c.inventory[0].image).toBe(image);
  expect(normalizeSheet(JSON.parse(JSON.stringify(c)), 'Hero').inventory[0].image).toBe(image);
  expect(armorFrom({ ...entry, armor: { score: 3, major: 6, severe: 12 } }).image).toBe(image);
  expect(weaponFrom({ ...entry, weapon: { slot: 'primary', trait: 'Agility', range: 'Melee',
    burden: 'One-Handed', damage: { dice: 'd6', bonus: 0, type: 'physical' } } }).image).toBe(image);
});

it('hydrates legacy catalog loot but never custom items or existing image overrides', () => {
  const c = emptyCharacter('Hero');
  c.inventory = [
    { name: 'Potion', qty: 2, type: 'consumable' },
    { name: 'Potion', qty: 1, type: 'consumable', custom: true },
    { name: 'Potion', qty: 1, type: 'consumable', image: '/item-icons/lorc/poison-bottle.svg' },
  ];
  updateCharacterAutomation(c, [{ format: 'dmmap-daggerheart', version: 1, category: 'consumables', label: 'Consumables',
    entries: [{ id: 'potion', name: 'Potion', image }] }]);
  expect(c.inventory.map(item => item.image)).toEqual([image, undefined, '/item-icons/lorc/poison-bottle.svg']);
  expect(c.inventory.map(item => item.qty)).toEqual([2, 1, 1]);
});
