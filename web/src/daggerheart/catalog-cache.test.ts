import { afterEach, expect, it, vi } from 'vitest';
import { configureDmSheet } from '../api';
import { fetchCompendiumCategory, fetchCompendiumIndex, invalidateCompendium } from './compendium';
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
