import { afterEach, expect, it, vi } from 'vitest';
import { configureDmSheet } from '../api';
import { cachedInventoryRarity, fetchCompendiumCategory, fetchCompendiumIndex, invalidateCompendium, needsInventoryRarityCatalogs, refreshInventoryRarities } from './compendium';
import { loadRulesData } from './rules';
import { refreshCharacterAutomation } from './persist';
import { emptyCharacter } from './data';

afterEach(() => { configureDmSheet(''); invalidateCompendium(); vi.unstubAllGlobals(); });

it('shares downloads across actors, rules and tools without sharing mutable automation', async () => {
  const categories = ['classes', 'ancestries', 'communities', 'weapons', 'armor', 'consumables', 'domain-cards', 'transformations', 'items'];
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/compendium'
    ? { categories: categories.map(category => ({ category, revision: '1' })) }
    : { category: url.split('/').pop(), entries: [] })));
  vi.stubGlobal('fetch', fetch);
  await Promise.all([refreshCharacterAutomation(emptyCharacter('A')), refreshCharacterAutomation(emptyCharacter('B')), loadRulesData(), fetchCompendiumIndex()]);
  expect(fetch.mock.calls.filter(([url]) => url === '/api/compendium')).toHaveLength(1);
  expect(fetch.mock.calls.filter(([url]) => url === '/api/compendium/classes')).toHaveLength(1);
  expect(fetch.mock.calls.filter(([url]) => url === '/api/compendium/items')).toHaveLength(1);
  const first = await fetchCompendiumCategory('classes');
  first.entries.push({ id: 'local', name: 'Local edit' });
  expect((await fetchCompendiumCategory('classes')).entries).toEqual([]);
});

it('revalidates the index and downloads only changed revisioned catalogs after edits/reconnects', async () => {
  let revision = '1';
  const request = vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/compendium'
    ? { categories: [{ category: 'classes', revision }, { category: 'armor', revision: 'unchanged' }] }
    : { category: url.split('/').pop(), entries: [{ id: revision, name: revision }] })));
  await fetchCompendiumIndex(request);
  await Promise.all([fetchCompendiumCategory('classes', request), fetchCompendiumCategory('armor', request)]);
  revision = '2';
  invalidateCompendium();
  const [classes] = await Promise.all([fetchCompendiumCategory('classes', request), fetchCompendiumCategory('armor', request)]);
  expect(classes.entries[0].name).toBe('2');
  expect(request.mock.calls.filter(([url]) => url === '/api/compendium')).toHaveLength(2);
  expect(request.mock.calls.filter(([url]) => url.endsWith('/armor'))).toHaveLength(1);
});

it('does not cache failures or reuse a pre-invalidation in-flight response', async () => {
  let resolve!: (response: Response) => void;
  const request = vi.fn<(url: string) => Promise<Response>>()
    .mockImplementationOnce(() => new Promise(done => { resolve = done; }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ categories: [] })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ entries: [{ id: 'new', name: 'New' }] })));
  const first = fetchCompendiumCategory('classes', request);
  invalidateCompendium();
  resolve(new Response(JSON.stringify({ entries: [{ id: 'old', name: 'Old' }] })));
  expect((await first).entries[0].name).toBe('New');
  const failed = vi.fn().mockResolvedValueOnce(new Response('', { status: 503 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ entries: [] })));
  await expect(fetchCompendiumCategory('classes', failed)).rejects.toThrow('503');
  expect((await fetchCompendiumCategory('classes', failed)).entries).toEqual([]);
});

it('keeps custom fetchers and DM authorization scopes isolated', async () => {
  const request = vi.fn(async () => new Response(JSON.stringify({ entries: [] })));
  const other = vi.fn(async () => new Response(JSON.stringify({ entries: [] })));
  await fetchCompendiumCategory('classes', request);
  await fetchCompendiumCategory('classes', other);
  configureDmSheet('different-grant');
  await fetchCompendiumCategory('classes', request);
  expect(request).toHaveBeenCalledTimes(2);
  expect(other).toHaveBeenCalledTimes(1);
});

it('resolves inventory rarity from the scoped catalog without changing sheet data', async () => {
  let revision = '1';
  let includeItems = true;
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/compendium'
    ? { categories: [
      ...(includeItems ? [{ category: 'items', revision }] : []),
      { category: 'consumables', revision: '1' },
    ] }
    : url.endsWith('/items')
      ? { category: 'items', entries: [
        { id: 'relic', name: 'Relic', rarity: revision === '1' ? 'Rare' : 'Legendary' },
        { id: 'duplicate-a', name: 'Duplicate', rarity: 'Common' },
        { id: 'duplicate-b', name: 'Duplicate', rarity: 'Rare' },
      ] }
      : { category: 'consumables', entries: [{ id: 'relic', name: 'Relic', rarity: 'Uncommon' }] })));
  vi.stubGlobal('fetch', fetch);
  const item = { name: 'Relic', type: 'gear', qty: 1 } as const;
  const linked = { ...item, name: 'Old name', catalog: { category: 'items', id: 'relic', values: {} } };
  const before = JSON.stringify([item, linked]);
  expect(cachedInventoryRarity(item)).toBeUndefined();
  await refreshInventoryRarities();
  expect(cachedInventoryRarity(item)).toBe('Rare');
  expect(cachedInventoryRarity(linked)).toBe('Rare');
  expect(cachedInventoryRarity({ ...item, type: 'consumable' })).toBe('Uncommon');
  expect(cachedInventoryRarity({ ...item, custom: true })).toBeUndefined();
  expect(cachedInventoryRarity({ ...item, name: 'Duplicate' })).toBeUndefined();
  expect(cachedInventoryRarity({ ...item, catalog: { category: 'items', id: 'missing', values: {} } })).toBeUndefined();
  expect(cachedInventoryRarity({ ...item, catalog: { category: 'consumables', id: 'relic', values: {} } })).toBeUndefined();
  const copy = await fetchCompendiumCategory('items');
  copy.entries[0].rarity = 'Common';
  expect(cachedInventoryRarity(item)).toBe('Rare');
  expect(JSON.stringify([item, linked])).toBe(before);

  revision = '2';
  invalidateCompendium();
  await refreshInventoryRarities();
  expect(cachedInventoryRarity(linked)).toBe('Legendary');
  includeItems = false;
  invalidateCompendium();
  await refreshInventoryRarities();
  expect(cachedInventoryRarity(item)).toBeUndefined();
  expect(cachedInventoryRarity({ ...item, type: 'consumable' })).toBe('Uncommon');
  configureDmSheet('another-test-scope');
  expect(cachedInventoryRarity({ ...item, type: 'consumable' })).toBeUndefined();
});

it('loads missing inventory catalogs when an initially empty sheet receives items', async () => {
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/compendium'
    ? { categories: [{ category: 'items' }] }
    : { category: 'items', entries: [{ id: 'relic', name: 'Relic', rarity: 'Rare' }] })));
  vi.stubGlobal('fetch', fetch);
  const item = { name: 'Relic', type: 'gear', qty: 1 } as const;
  const consumable = { name: 'Potion', type: 'consumable', qty: 1 } as const;
  expect(needsInventoryRarityCatalogs([])).toBe(false);
  expect(needsInventoryRarityCatalogs([{ ...item, custom: true }])).toBe(false);
  expect(needsInventoryRarityCatalogs([item])).toBe(true);
  await fetchCompendiumIndex();
  expect(needsInventoryRarityCatalogs([consumable])).toBe(false);
  expect(needsInventoryRarityCatalogs([item, consumable])).toBe(true);
  await refreshInventoryRarities();
  expect(needsInventoryRarityCatalogs([item, consumable])).toBe(false);
  expect(cachedInventoryRarity(item)).toBe('Rare');
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/items'))).toHaveLength(1);
  configureDmSheet('different-grant');
  expect(needsInventoryRarityCatalogs([item])).toBe(true);
});

it('retains successful category refreshes and reports every failed category', async () => {
  let fail = true;
  const fetch = vi.fn(async (url: string) => {
    if (url === '/api/compendium') return new Response(JSON.stringify({
      categories: [{ category: 'items', label: 'Items' }, { category: 'consumables', label: 'Consumables' }],
    }));
    if (url.endsWith('/consumables') && fail) return new Response('', { status: 503 });
    return new Response(JSON.stringify({ entries: [
      { id: 'relic', name: 'Relic', rarity: url.endsWith('/items') ? 'Rare' : 'Uncommon' },
    ] }));
  });
  vi.stubGlobal('fetch', fetch);
  const item = { name: 'Relic', type: 'gear', qty: 1 } as const;
  const consumable = { ...item, type: 'consumable' } as const;
  await expect(refreshInventoryRarities()).rejects.toThrow('Consumables: Compendium unavailable (503)');
  expect(cachedInventoryRarity(item)).toBe('Rare');
  expect(cachedInventoryRarity(consumable)).toBeUndefined();
  expect(needsInventoryRarityCatalogs([item, consumable])).toBe(true);
  fail = false;
  await refreshInventoryRarities();
  expect(cachedInventoryRarity(consumable)).toBe('Uncommon');
  expect(needsInventoryRarityCatalogs([item, consumable])).toBe(false);
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/items'))).toHaveLength(1);
});
