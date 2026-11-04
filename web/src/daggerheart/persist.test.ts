import { afterEach, describe, expect, it, vi } from 'vitest';
import { InventoryConflict, getSheet, saveSheet } from '../api';
import { applyInventory, autosave, updateCharacterAutomation } from './persist';
import { mockCharacter } from './data';
import type { CompendiumFile } from './compendium';

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, getSheet: vi.fn(), saveSheet: vi.fn() };
});

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('chat inventory synchronization', () => {
  it('merges DM domain additions without replacing pending card edits on ordinary inventory updates', () => {
    const c = mockCharacter();
    c.notes = 'Pending notes';
    const card = { name: 'DM card', domain: 'Grace', level: 1, type: 'Spell' as const, text: 'Granted', recall: 0 };
    const loadout = c.loadout;
    expect(applyInventory(c, { inventoryRevision: 1, compendiumRevision: 1, loadout, vault: [card] })).toBe(true);
    expect(c.vault).toEqual([card]);
    expect(c.compendiumRevision).toBe(1);
    c.vault = [];
    expect(applyInventory(c, { inventoryRevision: 2, compendiumRevision: 1, loadout: [], vault: [card] })).toBe(true);
    expect(c.vault).toEqual([]);
    expect(c.loadout).toBe(loadout);
    expect(c.notes).toBe('Pending notes');
  });

  it('adds missing rest metadata to saved gear without overwriting GM rules', () => {
    const c = mockCharacter();
    c.inventory = [{ name: 'Custom recipe', type: 'gear', qty: 1, automation: {
      actions: [{ id: 'craft', label: 'GM label', cost: { stress: 2 } }],
      modifiers: [{ stat: 'evasion', value: 3 }],
    } }];
    const files: CompendiumFile[] = [{
      format: 'dmmap-daggerheart', version: 1, label: 'Items', category: 'items', entries: [{ id: 'recipe', name: 'Custom recipe', automation: {
        actions: [{ id: 'craft', label: 'Craft', timing: 'downtime', cost: { stress: 1 } }],
        rest: { extraMoves: 1 },
      } }],
    }];
    updateCharacterAutomation(c, files);
    const a = c.inventory[0].automation!;
    expect(a.actions![0]).toEqual({ id: 'craft', label: 'GM label', cost: { stress: 2 }, timing: 'downtime' });
    expect(a.modifiers).toEqual([{ stat: 'evasion', value: 3 }]);
    expect(a.rest).toEqual({ extraMoves: 1 });
    a.rest = { extraMoves: 2 };
    a.actions![0].timing = 'action';
    updateCharacterAutomation(c, files);
    expect(a.rest).toEqual({ extraMoves: 2 });
    expect(a.actions![0].timing).toBe('action');
  });

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
