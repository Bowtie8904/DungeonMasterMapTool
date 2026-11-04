import { afterEach, describe, expect, it, vi } from 'vitest';
import { openPopout } from './popout';
import { addFromPopout } from './compendium-popout';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('tool pop-outs', () => {
  const entry = { id: 'potion', name: 'Potion' };
  function setup() {
    vi.useFakeTimers();
    const listeners = new Set<(event: unknown) => void>();
    const popup = {
      closed: false, location: { href: 'about:blank', replace: vi.fn() },
      focus: vi.fn(), postMessage: vi.fn(),
    };
    const host = {
      open: vi.fn<() => typeof popup | null>(() => popup),
      setTimeout: globalThis.setTimeout, setInterval: globalThis.setInterval,
      addEventListener: vi.fn((_name: string, callback: (event: unknown) => void) => listeners.add(callback)),
      removeEventListener: vi.fn((_name: string, callback: (event: unknown) => void) => listeners.delete(callback)),
    };
    vi.stubGlobal('window', host);
    vi.stubGlobal('location', { origin: 'https://game.test' });
    const ack = (data: unknown, source: unknown = popup, origin = 'https://game.test') => {
      for (const listener of listeners) listener({ data, source, origin });
    };
    return { popup, host, ack, listeners };
  }

  it('does not close the original tool when the browser blocks the pop-out', () => {
    vi.stubGlobal('window', { open: vi.fn(() => null) });
    expect(openPopout('/?popout=compendium', 1180, 900)).toBeNull();
  });

  it('opens a receiving sheet once and accepts only its same-origin acknowledgement', async () => {
    const { popup, host, ack, listeners } = setup();
    const operation = addFromPopout('actor 1', 'consumables', entry);
    expect(host.open).toHaveBeenCalledWith('', 'dmmt-compendium-sheet-actor 1', 'popup,width=900,height=900');
    expect(popup.location.replace).toHaveBeenCalledWith('/sheet/actor%201');
    const request = popup.postMessage.mock.calls[0][0];
    expect(request).toMatchObject({ type: 'dmmt-compendium-add', actorId: 'actor 1', category: 'consumables', entry });
    let completed = false;
    void operation.then(() => { completed = true; });
    const reply = { type: 'dmmt-compendium-added', id: request.id, target: "Mara's inventory" };
    ack(reply, {}, 'https://game.test');
    ack(reply, popup, 'https://unrelated.test');
    await Promise.resolve();
    expect(completed).toBe(false);
    ack(reply);
    await expect(operation).resolves.toBe("Mara's inventory");
    expect(listeners.size).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('focuses an existing receiving sheet without reloading unsaved edits', async () => {
    const { popup, ack } = setup();
    popup.location.href = 'https://game.test/sheet/actor';
    const operation = addFromPopout('actor', 'items', entry);
    expect(popup.location.replace).not.toHaveBeenCalled();
    expect(popup.focus).toHaveBeenCalled();
    const request = popup.postMessage.mock.calls[0][0];
    ack({ type: 'dmmt-compendium-added', id: request.id, target: null });
    await expect(operation).resolves.toBeNull();
  });

  it('reports receiving-sheet failures and blocked pop-ups', async () => {
    const { popup, host, ack } = setup();
    const operation = addFromPopout('actor', 'items', entry);
    const request = popup.postMessage.mock.calls[0][0];
    ack({ type: 'dmmt-compendium-added', id: request.id, error: 'Could not save the character sheet.' });
    await expect(operation).rejects.toThrow('Could not save the character sheet.');
    host.open.mockReturnValue(null);
    await expect(addFromPopout('actor', 'items', entry)).rejects.toThrow('blocked');
  });

  it('retries the same request ID until timeout and never silently claims success', async () => {
    const { popup } = setup();
    const operation = addFromPopout('actor', 'items', entry);
    const failure = expect(operation).rejects.toThrow('Check the receiving sheet');
    vi.advanceTimersByTime(30_000);
    await failure;
    expect(new Set(popup.postMessage.mock.calls.map(call => call[0].id)).size).toBe(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
