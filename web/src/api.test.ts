import { afterEach, expect, it, vi } from 'vitest';
import { logout } from './api';

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
