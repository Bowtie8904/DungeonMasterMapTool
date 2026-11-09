import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { InventoryConflict, saveSheet } from './api';
import { mockCharacter } from './daggerheart/data';
import { autosave } from './daggerheart/persist';
import type { Token } from './protocol';

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return { ...actual, saveSheet: vi.fn() };
});

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('window', globalThis);
  vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
  vi.stubGlobal('CustomEvent', class extends Event {
    constructor(type: string) { super(type); }
  });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

function fixture() {
  const character = mockCharacter();
  character.effects = [{ id: 'online-condition:token:restrained', name: 'Restrained', kind: 'debuff',
    text: '', onlineCondition: { tokenId: 'token', id: 'restrained' } }];
  const errors = vi.fn();
  const save = autosave('actor', character, errors);
  const bus = { notify: vi.fn() };
  const tokens: Token[] = [{ id: 'token', actorId: 'actor', x: 0, y: 0, sizeTiles: 1,
    conditions: [{ id: 'restrained', type: 'restrained', name: 'Restrained', iconId: 'cond_restrained' }] }];
  const context = {
    store: { state: { mapId: 'map', levelId: 'level', tokens } },
    view: { canEditConditions: true },
    sheetHandles: new Map([['actor', { character, bus, save }]]),
    pendingConditionRemovals: new Set<string>(),
    sendTokenCondition: vi.fn(() => true),
    scheduleEffects: vi.fn(),
  };
  const remove = () => Reflect.get(App.prototype, 'sendMissingConditionEffects').call(context, 'actor', character) as Promise<void>;
  return { character, errors, save, bus, context, remove };
}

describe('linked condition removal persistence', () => {
  it('persists the selected condition icon in linked actor effects', () => {
    const f = fixture();
    f.character.effects = [];
    f.context.store.state.tokens[0].conditions = [
      { id: 'custom-fire', type: 'custom', name: 'On Fire', iconId: 'cond_burning' },
    ];
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(
      f.context, { id: 'actor' }, f.context.sheetHandles.get('actor'));
    expect(f.character.effects[0].onlineCondition).toEqual({
      tokenId: 'token', id: 'custom-fire', iconId: 'cond_burning',
    });
  });

  it('saves removed effects and unrelated edits before the host condition command, without duplicate commands', async () => {
    const f = fixture();
    let finishSave!: () => void;
    vi.mocked(saveSheet).mockImplementation(() => new Promise(resolve => {
      finishSave = () => resolve(undefined);
    }));
    f.character.effects = [];
    f.character.notes = 'Keep this edit';
    const removal = f.remove();
    f.save();
    expect(saveSheet).toHaveBeenCalledTimes(1);
    expect(vi.mocked(saveSheet).mock.calls[0][1].effects).toEqual([]);
    expect(vi.mocked(saveSheet).mock.calls[0][1].notes).toBe('Keep this edit');
    expect(f.context.pendingConditionRemovals.has('token:restrained')).toBe(true);
    expect(f.context.sendTokenCondition).not.toHaveBeenCalled();
    await f.remove();
    finishSave();
    await removal;
    await vi.advanceTimersByTimeAsync(700);
    expect(saveSheet).toHaveBeenCalledTimes(1);
    expect(f.context.sendTokenCondition).toHaveBeenCalledTimes(1);
    expect(f.context.sendTokenCondition).toHaveBeenCalledWith('token',
      { id: 'restrained', type: 'restrained', name: 'Restrained', iconId: 'cond_restrained' }, false);
    expect(f.errors).not.toHaveBeenCalled();
  });

  it('retains genuine version-conflict reporting and does not change the token after a failed save', async () => {
    const f = fixture();
    const latest = structuredClone(f.character);
    latest.notes = 'Edited in another window';
    vi.mocked(saveSheet).mockRejectedValue(new InventoryConflict(latest, 'new-version'));
    f.character.effects = [];
    await f.remove();
    expect(f.context.sendTokenCondition).not.toHaveBeenCalled();
    expect(f.context.pendingConditionRemovals.size).toBe(0);
    expect(f.errors).toHaveBeenCalledTimes(1);
    expect(f.character.effects).toHaveLength(1);
    expect(f.character.notes).toBe('Edited in another window');
    expect(f.bus.notify).toHaveBeenCalled();
  });

  it('does not send removals into a different map after saving', async () => {
    const f = fixture();
    vi.mocked(saveSheet).mockImplementation(async () => {
      f.context.store.state.mapId = 'different-map';
      return undefined;
    });
    f.character.effects = [];
    await f.remove();
    expect(f.context.sendTokenCondition).not.toHaveBeenCalled();
    expect(f.context.pendingConditionRemovals.size).toBe(0);
  });

  it('does not clear another actor\'s pending removal when synchronizing effects', () => {
    const f = fixture();
    f.context.pendingConditionRemovals.add('token:restrained');
    const other = { character: mockCharacter(), save: vi.fn(), bus: { notify: vi.fn() } };
    other.character.effects = [];
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(f.context, { id: 'other' }, other);
    expect(f.context.pendingConditionRemovals.has('token:restrained')).toBe(true);
    f.context.store.state.tokens[0].conditions = [];
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(f.context, { id: 'actor' }, f.context.sheetHandles.get('actor'));
    expect(f.context.pendingConditionRemovals.size).toBe(0);
  });

  it('preserves actor effects when switching to a map without the actor token', () => {
    const f = fixture();
    f.context.store.state.tokens = [];
    const saved = structuredClone(f.character.effects);
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(f.context, { id: 'actor' }, f.context.sheetHandles.get('actor'));
    expect(f.character.effects).toEqual(saved);
    expect(f.context.sendTokenCondition).not.toHaveBeenCalled();
    expect(f.bus.notify).not.toHaveBeenCalled();
  });

  it('does not remove saved actor effects because an old map token has an empty condition list', () => {
    const f = fixture();
    f.context.store.state.tokens[0].conditions = [];
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(f.context, { id: 'actor' }, f.context.sheetHandles.get('actor'));
    expect(f.character.effects).toHaveLength(1);
    expect(f.bus.notify).not.toHaveBeenCalled();
  });

  it('reuses saved actor effects for a fresh token and removes them explicitly from that token', async () => {
    const f = fixture();
    f.context.store.state.tokens[0].id = 'fresh-token';
    Reflect.get(App.prototype, 'applyTokenConditionEffects').call(f.context, { id: 'actor' }, f.context.sheetHandles.get('actor'));
    expect(f.character.effects).toHaveLength(1);
    expect(f.character.effects[0].onlineCondition?.tokenId).toBe('token');
    await f.remove();
    expect(f.context.sendTokenCondition).not.toHaveBeenCalled();
    f.character.effects = [];
    vi.mocked(saveSheet).mockResolvedValue(undefined);
    await f.remove();
    expect(f.context.sendTokenCondition).toHaveBeenCalledWith('fresh-token',
      { id: 'restrained', type: 'restrained', name: 'Restrained', iconId: 'cond_restrained' }, false);
  });
});
