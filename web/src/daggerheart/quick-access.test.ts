import { expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet, type DomainCard, type Feature, type Weapon } from './data';
import { favoriteKey, quickAccessEntries, quickAccessPlacement, quickAccessSidePlacement } from './quick-access';

const sword: Weapon = { name: 'Sword', slot: 'Primary', trait: 'Agility', range: 'Melee', damage: 'd8', damageType: 'phys', hands: 1, tier: 1 };
const card: DomainCard = { name: 'Spell', domain: 'Arcana', level: 1, type: 'Spell', text: 'Spell text', recall: 1 };

it('retains favorites through equipment/loadout swaps and saved-sheet reloads', () => {
  const c = emptyCharacter('Hero');
  const bow = { ...sword, name: 'Bow' };
  const otherCard = { ...card, name: 'Other spell' };
  c.activeWeapons = [sword];
  c.carriedWeapons = [bow];
  c.loadout = [card];
  c.vault = [otherCard];
  c.quickAccessFavorites = [favoriteKey('weapon', sword), favoriteKey('weapon', bow),
    favoriteKey('domain', card), favoriteKey('domain', otherCard)];
  expect(quickAccessEntries(c).weapons).toEqual([sword]);
  expect(quickAccessEntries(c).cards).toEqual([card]);
  [c.activeWeapons, c.carriedWeapons] = [c.carriedWeapons, c.activeWeapons];
  [c.loadout, c.vault] = [c.vault, c.loadout];
  const reloaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
  expect(reloaded.quickAccessFavorites).toEqual(c.quickAccessFavorites);
  expect(quickAccessEntries(reloaded).weapons).toEqual([bow]);
  expect(quickAccessEntries(reloaded).cards).toEqual([otherCard]);
  [c.activeWeapons, c.carriedWeapons] = [c.carriedWeapons, c.activeWeapons];
  [c.loadout, c.vault] = [c.vault, c.loadout];
  expect(quickAccessEntries(c).weapons).toEqual([sword]);
  expect(quickAccessEntries(c).cards).toEqual([card]);
});

it('keeps categories and feature sources distinct and does not change item transfer identity', () => {
  expect(favoriteKey('weapon', sword)).not.toBe(favoriteKey('item', sword));
  expect(favoriteKey('feature', { name: 'Gift', source: 'Class' })).not.toBe(favoriteKey('feature', { name: 'Gift', source: 'Ancestry' }));
  const upgraded: Weapon = { ...sword, damage: 'd12' };
  expect(favoriteKey('weapon', upgraded)).toBe(favoriteKey('weapon', sword));
  expect(favoriteKey('weapon', { ...sword, tier: 2 })).not.toBe(favoriteKey('weapon', sword));
  expect(favoriteKey('item', { name: 'Note', type: 'loot', custom: true, note: 'One' }))
    .not.toBe(favoriteKey('item', { name: 'Note', type: 'loot', custom: true, note: 'Two' }));
  expect(sword).not.toHaveProperty('favorite');
});

it('includes grouped children once, with all children when the parent is favored', () => {
  const c = emptyCharacter('Hero');
  const child: Feature = { name: 'Song', text: 'Sing', source: 'Class' };
  const parent: Feature = { name: 'Performer', text: 'Perform', source: 'Class',
    automation: { groupGrantedFeatures: true, grantsFeatures: ['Song'] } };
  c.features = [parent, child];
  c.quickAccessFavorites = [favoriteKey('feature', child)];
  expect(quickAccessEntries(c).features).toEqual([{ feature: child, children: [] }]);
  c.quickAccessFavorites.push(favoriteKey('feature', parent));
  expect(quickAccessEntries(c).features).toEqual([{ feature: parent, children: [child] }]);
});

it('sorts weapons Primary first, cards by level, features in Character-tab order and items alphabetically', () => {
  const c = emptyCharacter('Hero');
  const offhand: Weapon = { ...sword, name: 'Buckler', slot: 'Secondary' };
  const high = { ...card, name: 'High', level: 3 };
  const low = { ...card, name: 'Low', level: 1 };
  const first: Feature = { name: 'Zeal', text: '', source: 'Class' };
  const second: Feature = { name: 'Aim', text: '', source: 'Class' };
  c.activeWeapons = [offhand, sword];
  c.loadout = [high, low];
  c.features = [first, second];
  c.inventory = [{ name: 'rope', type: 'loot', qty: 1 }, { name: 'Antidote', type: 'loot', qty: 1 }] as typeof c.inventory;
  c.quickAccessFavorites = [favoriteKey('weapon', offhand), favoriteKey('weapon', sword), favoriteKey('domain', high),
    favoriteKey('domain', low), favoriteKey('feature', second), favoriteKey('feature', first),
    ...c.inventory.map(i => favoriteKey('item', i))];
  const entries = quickAccessEntries(c);
  expect(entries.weapons.map(w => w.name)).toEqual(['Sword', 'Buckler']);
  expect(entries.cards.map(d => d.name)).toEqual(['Low', 'High']);
  expect(entries.features.map(f => f.feature.name)).toEqual(['Zeal', 'Aim']);
  expect(entries.items.map(i => i.name)).toEqual(['Antidote', 'rope']);
});

it('ignores removed entries and permanently vaulted cards without erasing bookmarks', () => {
  const c = emptyCharacter('Hero');
  c.loadout = [{ ...card, permanentlyVaulted: true }];
  c.quickAccessFavorites = [favoriteKey('domain', card), favoriteKey('weapon', sword), favoriteKey('item', { name: 'Potion' })];
  expect(quickAccessEntries(c)).toEqual({ weapons: [], cards: [], features: [], items: [] });
  expect(c.quickAccessFavorites).toHaveLength(3);
});

it('places the tray above bottom bars and below top bars, clamping horizontal bounds', () => {
  const top = quickAccessPlacement({ top: 0, bottom: 80, left: 1100 }, 1200, 800);
  expect(top).toMatchObject({ left: 792, width: 400, top: 88, bottom: undefined, maxHeight: 600 });
  const bottom = quickAccessPlacement({ top: 720, bottom: 800, left: -100 }, 1200, 800);
  expect(bottom).toMatchObject({ left: 8, top: undefined, bottom: 88, maxHeight: 600 });
});

it('limits height to available space and uses an anchor-independent mobile drawer', () => {
  expect(quickAccessPlacement({ top: 100, bottom: 180, left: 0 }, 1200, 300).maxHeight).toBe(104);
  expect(quickAccessPlacement({ top: 100, bottom: 180, left: 300 }, 390, 600)).toEqual({
    left: 8, width: 374, top: undefined, bottom: 8, maxHeight: 584,
  });
});

it('places the rail tray right of the star, bottom-aligned and growing upward', () => {
  expect(quickAccessSidePlacement({ right: 78, bottom: 700 }, 1200, 800)).toEqual({
    left: 86, width: 400, top: undefined, bottom: 100, maxHeight: 600,
  });
  expect(quickAccessSidePlacement({ right: 78, bottom: 300 }, 1200, 800)).toMatchObject({ bottom: 332, maxHeight: 460 });
  expect(quickAccessSidePlacement({ right: 78, bottom: 300 }, 390, 600)).toEqual({
    left: 8, width: 374, top: undefined, bottom: 8, maxHeight: 584,
  });
});
