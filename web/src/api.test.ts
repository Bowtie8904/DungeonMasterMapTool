import { afterEach, expect, it, vi } from 'vitest';
import { applyDeathRecovery, configureDmSheet, getDeathOptions, logout, takeDeathMove, uploadArtwork, useFeature } from './api';

afterEach(() => vi.unstubAllGlobals());

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
