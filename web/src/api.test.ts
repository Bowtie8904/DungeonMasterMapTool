import { afterEach, expect, it, vi } from 'vitest';
import { acceptSheetVersion, applyDeathRecovery, configureDmSheet, configureSheetActionResponses, configureSheetPatches, getDeathOptions, logout, saveSheet, takeDeathMove, tokenArtwork, uploadArtwork, useFeature } from './api';
import { emptyCharacter } from './daggerheart/data';

afterEach(() => { configureSheetPatches(false); configureSheetActionResponses(false); vi.unstubAllGlobals(); });

it('sends versioned top-level patches only with a known accepted baseline and capability', async () => {
  const c = emptyCharacter('Hero');
  c.otherDetails = 'delete me';
  c.notes = 'Unchanged character background. '.repeat(4000);
  acceptSheetVersion('patch-actor', '"one"', c);
  configureSheetPatches(true);
  const fetch = vi.fn(async (_url: string, _init: RequestInit) => new Response(JSON.stringify({ version: '"two"' })));
  vi.stubGlobal('fetch', fetch);
  c.hp.on = 1;
  Reflect.deleteProperty(c, 'otherDetails');
  await saveSheet('patch-actor', c);
  const init = fetch.mock.calls[0][1] as RequestInit;
  expect(new Headers(init.headers).get('X-DMMT-Sheet-Patch')).toBe('1');
  expect(new Headers(init.headers).get('If-Match')).toBe('"one"');
  expect(JSON.parse(String(init.body))).toEqual({ fields: { hp: c.hp }, removed: ['otherDetails'] });
  expect(new TextEncoder().encode(String(init.body)).length).toBeLessThan(512);
  expect(String(init.body).length).toBeLessThan(JSON.stringify(c).length / 100);
  c.notes = 'Next edit';
  await saveSheet('patch-actor', c);
  expect(JSON.parse(String(fetch.mock.calls[1][1].body))).toEqual({ fields: { notes: 'Next edit' }, removed: [] });
  await saveSheet('unknown-patch-baseline', c);
  expect(new Headers(fetch.mock.calls[2][1].headers).has('X-DMMT-Sheet-Patch')).toBe(false);
  expect(JSON.parse(String(fetch.mock.calls[2][1].body)).name).toBe('Hero');
  configureSheetPatches(false);
  await saveSheet('patch-actor', c);
  expect(new Headers(fetch.mock.calls[3][1].headers).has('X-DMMT-Sheet-Patch')).toBe(false);
});

it('requests committed feature sheets only from capable hosts and retains empty legacy success', async () => {
  const state = { actorId: 'hero', version: '"done"', sheet: emptyCharacter('Hero'), testingCharacter: false };
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify({ ok: true, sheetState: state })));
  vi.stubGlobal('fetch', fetch);
  configureSheetActionResponses(true);
  expect((await useFeature({ actorId: 'hero', key: '', actionId: 'use' }))?.sheetState).toEqual(state);
  expect(new Headers(fetch.mock.calls[0][1]?.headers).get('X-DMMT-Sheet-Response')).toBe('1');
  configureSheetActionResponses(false);
  fetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
  expect(await useFeature({ actorId: 'hero', key: '', actionId: 'use' })).toBeUndefined();
  expect(new Headers(fetch.mock.calls[1][1]?.headers).has('X-DMMT-Sheet-Response')).toBe(false);
});

it('loads token editor settings and saves a crop without replacing its source', async () => {
  const settings = { source: '/a/source.png', color: '#123456', sizeTiles: 1.5, zoom: 2, centerX: .2, centerY: .8 };
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(settings)))
    .mockResolvedValueOnce(new Response(JSON.stringify({ artwork: '/a/finished.png' })));
  vi.stubGlobal('fetch', fetch);
  expect(await tokenArtwork('a b')).toEqual(settings);
  expect(fetch.mock.calls[0][0]).toBe('/api/actors/a%20b/artwork');
  expect((await tokenArtwork('a b', settings)).artwork).toBe('/a/finished.png');
  expect(fetch.mock.calls[1][1].method).toBe('PUT');
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(settings);
});

it('reports unsupported hosts, read-only actors and malformed token responses', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(new Response('{}', { status: 404 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'Actor is disabled' }), { status: 403 }))
    .mockResolvedValueOnce(new Response('{}')));
  await expect(tokenArtwork('actor')).rejects.toThrow('Update the host');
  await expect(tokenArtwork('actor')).rejects.toThrow('Actor is disabled');
  await expect(tokenArtwork('actor')).rejects.toThrow('invalid character token settings');
});

it('does not report logout success when the host rejects it', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Session unavailable' }), { status: 503 })));
  await expect(logout()).rejects.toThrow('Session unavailable');
});

it('logs out through the authenticated same-origin endpoint', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  await logout();
  expect(fetch).toHaveBeenCalledWith('/api/logout', { method: 'POST', credentials: 'same-origin' });
});

it('loads encoded authoritative death options with authenticated credentials', async () => {
  const options = { options: [{ move: 'risk', label: 'Risk It All' }], bonuses: [], undying: false };
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(options)));
  vi.stubGlobal('fetch', fetch);
  expect(await getDeathOptions('a b')).toEqual(options);
  expect(fetch).toHaveBeenCalledWith('/api/death-move/a%20b', { credentials: 'same-origin' });
});

it('preserves basic death payloads and sends expanded recovery/range contracts', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  await takeDeathMove('actor', 'risk');
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ actorId: 'actor', move: 'risk' });
  await takeDeathMove('actor', 'avoid', { mask: true, wearingMask: true });
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ actorId: 'actor', move: 'avoid', mask: true, wearingMask: true });
  await applyDeathRecovery('actor', { id: 1, ts: 2 }, { undying: true });
  expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ actorId: 'actor', roll: { id: 1, ts: 2 }, undying: true });
  await applyDeathRecovery('actor', { id: 1, ts: 2 }, { hp: 2, stress: 3 });
  expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ actorId: 'actor', roll: { id: 1, ts: 2 }, hp: 2, stress: 3 });
  await useFeature({ actorId: 'actor', key: 'card:Life Ward', actionId: 'use', targetActorId: 'offline', confirmRange: true });
  expect(JSON.parse(fetch.mock.calls[4][1].body).confirmRange).toBe(true);
});

it('reports authoritative death option and claim errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify({ error: 'Recovery is no longer pending' }), { status: 409 })));
  await expect(applyDeathRecovery('actor', { id: 1, ts: 2 }, { hp: 1 })).rejects.toThrow('no longer pending');
  await expect(getDeathOptions('actor')).rejects.toThrow('no longer pending');
});

it('returns the uploaded portrait URL and authenticates actor-scoped DM sheet uploads', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ artwork: '/a/portrait.png' })));
  vi.stubGlobal('fetch', fetch);
  const image = new File(['image'], 'portrait.png', { type: 'image/png' });
  expect(await uploadArtwork('a b', image)).toBe('/a/portrait.png');
  expect(fetch.mock.calls[0][0]).toBe('/api/actors/a%20b/artwork');
  expect(fetch.mock.calls[0][1].body).toBe(image);
  configureDmSheet('sheet-test');
  try {
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({ artwork: '/a/next.png' })));
    expect(await uploadArtwork('hero', image)).toBe('/a/next.png');
    expect(fetch.mock.calls[1][1].credentials).toBe('omit');
    expect(fetch.mock.calls[1][1].headers.get('Authorization')).toBe('Bearer sheet-test');
  } finally { configureDmSheet(''); }
});

it('surfaces portrait upload rejection and invalid response data', async () => {
  const image = new File(['image'], 'portrait.png', { type: 'image/png' });
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ error: 'Invalid image' }), { status: 400 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({})));
  vi.stubGlobal('fetch', fetch);
  await expect(uploadArtwork('hero', image)).rejects.toThrow('Invalid image');
  await expect(uploadArtwork('hero', image)).rejects.toThrow('invalid artwork URL');
});
