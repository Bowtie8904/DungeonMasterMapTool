import { afterEach, describe, expect, it, vi } from 'vitest';
import { InventoryConflict, getSheet, saveSheet } from '../api';
import { applyInventory, autosave } from './persist';
import { mockCharacter } from './data';

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, getSheet: vi.fn(), saveSheet: vi.fn() };
});

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('chat inventory synchronization', () => {
  it('updates only inventory and ignores older or duplicate generations', () => {
    const c = mockCharacter();
    c.notes = 'Unsaved notes';
    const activeArmor = c.activeArmor;
    expect(applyInventory(c, { inventoryRevision: 2, inventory: [{ name: 'Torch', type: 'gear', qty: 1 }] })).toBe(true);
    expect(c.inventory[0].name).toBe('Torch');
    expect(c.activeArmor).toBeUndefined();
    expect(c.activeWeapons).toEqual([]);
    expect(c.notes).toBe('Unsaved notes');
    expect(applyInventory(c, { inventoryRevision: 1, activeArmor })).toBe(false);
    expect(applyInventory(c, { inventoryRevision: 2, activeArmor })).toBe(false);
    expect(c.activeArmor).toBeUndefined();
  });

  it('flush waits for writes and reports a stale-save conflict without restoring old inventory', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    vi.stubGlobal('CustomEvent', class { constructor(public type: string, public options: unknown) {} });
    const c = mockCharacter();
    c.notes = 'Keep pending notes';
    const onError = vi.fn();
    vi.mocked(saveSheet).mockRejectedValueOnce(new InventoryConflict({ inventoryRevision: 1, inventory: [] }));
    const save = autosave('actor', c, onError);
    await expect(save.flush()).rejects.toBeInstanceOf(InventoryConflict);
    expect(c.inventory).toEqual([]);
    expect(c.inventoryRevision).toBe(1);
    expect(c.notes).toBe('Keep pending notes');
    expect(onError).toHaveBeenCalledOnce();
    vi.mocked(saveSheet).mockResolvedValue(undefined);
    await save.flush();
    expect(vi.mocked(saveSheet).mock.calls[1][1].inventory).toEqual([]);
    vi.mocked(getSheet).mockResolvedValue({ sheet: { inventoryRevision: 3, inventory: [] } });
    await save.refresh();
    expect(c.inventoryRevision).toBe(3);
  });
});
