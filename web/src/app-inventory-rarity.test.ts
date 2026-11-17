import { afterEach, expect, it, vi } from 'vitest';
import { App } from './app';
import { configureDmSheet } from './api';
import { cachedInventoryRarity, invalidateCompendium } from './daggerheart/compendium';
import { emptyCharacter, type CharacterSheet } from './daggerheart/data';
import type { ServerMessage } from './protocol';

afterEach(() => { configureDmSheet(''); invalidateCompendium(); vi.unstubAllGlobals(); });

it('loads rarity metadata for legacy inventory updates without saving presentation data', async () => {
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/compendium'
    ? { categories: [{ category: 'items' }] }
    : { category: 'items', entries: [{ id: 'relic', name: 'Relic', rarity: 'Rare' }] })));
  vi.stubGlobal('fetch', fetch);
  const character = emptyCharacter('Collector');
  const bus = { notify: vi.fn() };
  const save = vi.fn();
  const context = {
    conn: { sheetSync: false },
    sheetHandles: new Map([['actor', { character, bus, save }]]),
    toast: vi.fn(),
    refreshInventoryRarities() { Reflect.get(App.prototype, 'refreshInventoryRarities').call(this); },
    ensureInventoryRarities(sheet: CharacterSheet) {
      Reflect.get(App.prototype, 'ensureInventoryRarities').call(this, sheet);
    },
  };
  const message: ServerMessage = {
    type: 'sheetInventory', actorId: 'actor',
    inventory: { inventoryRevision: 1, inventory: [{ name: 'Relic', type: 'gear', qty: 1 }] },
  };
  Reflect.get(App.prototype, 'handle').call(context, message);
  expect(bus.notify).toHaveBeenCalledWith();
  const serialized = JSON.stringify(character.inventory);
  await vi.waitFor(() => expect(cachedInventoryRarity(character.inventory[0])).toBe('Rare'));
  expect(bus.notify).toHaveBeenCalledWith(false);
  expect(JSON.stringify(character.inventory)).toBe(serialized);
  expect(save).not.toHaveBeenCalled();
  expect(context.toast).not.toHaveBeenCalled();

  Reflect.get(App.prototype, 'handle').call(context, {
    ...message, inventory: { inventoryRevision: 2, inventory: character.inventory },
  });
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/items'))).toHaveLength(1);
});

it('reports a failed inventory rarity refresh instead of hiding the error', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })));
  const character = emptyCharacter('Collector');
  const context = {
    conn: { sheetSync: false },
    sheetHandles: new Map([['actor', { character, bus: { notify: vi.fn() } }]]),
    toast: vi.fn(),
    refreshInventoryRarities() { Reflect.get(App.prototype, 'refreshInventoryRarities').call(this); },
    ensureInventoryRarities(sheet: CharacterSheet) {
      Reflect.get(App.prototype, 'ensureInventoryRarities').call(this, sheet);
    },
  };
  Reflect.get(App.prototype, 'handle').call(context, {
    type: 'sheetInventory', actorId: 'actor',
    inventory: { inventoryRevision: 1, inventory: [{ name: 'Relic', type: 'gear', qty: 1 }] },
  });
  await vi.waitFor(() => expect(context.toast).toHaveBeenCalledWith('Could not refresh inventory rarity: Compendium unavailable (503)'));
});

it('repaints available rarities even when another category fails', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/compendium') return new Response(JSON.stringify({
      categories: [{ category: 'items', label: 'Items' }, { category: 'consumables', label: 'Consumables' }],
    }));
    if (url.endsWith('/consumables')) return new Response('', { status: 503 });
    return new Response(JSON.stringify({ entries: [{ id: 'relic', name: 'Relic', rarity: 'Rare' }] }));
  }));
  const character = emptyCharacter('Collector');
  const bus = { notify: vi.fn() };
  const context = {
    conn: { sheetSync: false },
    sheetHandles: new Map([['actor', { character, bus }]]),
    toast: vi.fn(),
    refreshInventoryRarities() { Reflect.get(App.prototype, 'refreshInventoryRarities').call(this); },
    ensureInventoryRarities(sheet: CharacterSheet) {
      Reflect.get(App.prototype, 'ensureInventoryRarities').call(this, sheet);
    },
  };
  Reflect.get(App.prototype, 'handle').call(context, {
    type: 'sheetInventory', actorId: 'actor',
    inventory: { inventoryRevision: 1, inventory: [
      { name: 'Relic', type: 'gear', qty: 1 }, { name: 'Potion', type: 'consumable', qty: 1 },
    ] },
  });
  await vi.waitFor(() => expect(context.toast).toHaveBeenCalledWith(
    'Could not refresh inventory rarity: Consumables: Compendium unavailable (503)'));
  expect(cachedInventoryRarity(character.inventory[0])).toBe('Rare');
  expect(bus.notify).toHaveBeenCalledWith(false);
});
