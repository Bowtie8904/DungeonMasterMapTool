import { afterEach, expect, it, vi } from 'vitest';
import { INVENTORY_ITEM_ICONS, itemImagePath, itemName } from './item-artwork';
import { addToSheet, armorFrom, weaponFrom, type CompendiumEntry } from './daggerheart/compendium';
import { emptyCharacter, normalizeSheet } from './daggerheart/data';
import { updateCharacterAutomation } from './daggerheart/persist';
import { renderSharedCard } from './shared-cards';
import { gradedItemName, rarityGrade } from './daggerheart/inventory-grade';

class Element {
  className = '';
  children: Array<Element | string> = [];
  listeners = new Map<string, () => void>();
  classList = { add: () => {}, toggle: () => {} };
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

it.each([
  ['Rune Ward', '/item-icons/lorc/rune-stone.svg'],
  ['Lifeblood Talisman', '/item-icons/lorc/gem-pendant.svg'],
  ['Enchanted Talisman', '/item-icons/lorc/gem-pendant.svg'],
])('displays legacy %s artwork without changing the saved item', (name, expected) => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const item = { name, type: 'gear' as const, qty: 1, witchTalisman: { sourceActorId: 'caster', activationId: 'activation' } };
  const rendered = gradedItemName(item, rarityGrade(undefined)) as unknown as Element;
  expect(rendered.all().find(node => node.tag === 'img')).toMatchObject({ src: expected, alt: '' });
  const card = renderSharedCard({ category: 'item', item, cardId: 'offer', actorId: 'caster', actorName: 'Caster',
    available: 1, offerQuantity: 1 }) as unknown as Element;
  expect(card.all().find(node => node.tag === 'img')).toMatchObject({ src: expected });
  expect(item).not.toHaveProperty('image');
  expect((itemName({ name }) as unknown as Element).all().some(node => node.tag === 'img')).toBe(false);
  expect((itemName({ ...item, image }) as unknown as Element).all().find(node => node.tag === 'img'))
    .toMatchObject({ src: image });
  expect((itemName(item, INVENTORY_ITEM_ICONS.gear) as unknown as Element).all().find(node => node.tag === 'img'))
    .toMatchObject({ src: expected });
});

it('does not assign artwork to unrelated enchanted-item markers', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  for (const name of ['Other item', 'toString']) {
    const item = { name, witchTalisman: { sourceActorId: 'caster', activationId: 'activation' } };
    expect((itemName(item) as unknown as Element).all().some(node => node.tag === 'img')).toBe(false);
  }
});

it.each([
  ['loot', '/item-icons/delapouite/chest.svg'],
  ['gear', '/item-icons/delapouite/backpack.svg'],
  ['consumable', '/item-icons/lorc/standing-potion.svg'],
  ['class item', '/item-icons/lorc/gem-pendant.svg'],
] as const)('shows the %s fallback in shared cards without changing saved inventory', (type, expected) => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const item = { name: "a ring you can't remove", type, qty: 1 };
  const render = () => renderSharedCard({ category: 'item', item, cardId: 'offer', actorId: 'hero',
    actorName: 'Hero', available: 1, offerQuantity: 1 }) as unknown as Element;
  expect(render().all().find(node => node.tag === 'img')).toMatchObject({ src: expected, alt: '' });
  expect(item).not.toHaveProperty('image');
  expect(INVENTORY_ITEM_ICONS[type]).toBe(expected);
});

it('preserves explicit artwork and reports invalid artwork instead of hiding it with a fallback', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const fallback = INVENTORY_ITEM_ICONS.gear;
  expect((itemName({ name: 'Ring', image }, fallback) as unknown as Element).all().find(n => n.tag === 'img'))
    .toMatchObject({ src: image });
  const invalid = itemName({ name: 'Ring', image: 'https://example.test/image.svg' }, fallback) as unknown as Element;
  expect(invalid.all().some(n => n.tag === 'img')).toBe(false);
  expect(invalid.all().find(n => n.className === 'item-artwork-error'))
    .toMatchObject({ ariaLabel: 'Item image unavailable' });
  expect((itemName({ name: 'Ring', image: '' }, fallback) as unknown as Element).all().find(n => n.tag === 'img'))
    .toMatchObject({ src: fallback });
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
