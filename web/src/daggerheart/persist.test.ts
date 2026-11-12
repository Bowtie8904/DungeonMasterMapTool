import { afterEach, describe, expect, it, vi } from 'vitest';
import { InventoryConflict, getSheet, saveSheet } from '../api';
import { applyFeatureState, applyInventory, autosave, featureTarget, loadCharacter, saveImportedCharacter, updateCharacterAutomation } from './persist';
import { emptyCharacter, mockCharacter } from './data';
import type { CompendiumFile } from './compendium';

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api');
  return { ...actual, getSheet: vi.fn(), saveSheet: vi.fn() };
});

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('chat inventory synchronization', () => {
  it('saves imports with current host revisions and restores the blank sheet on persistence failure', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    const c = emptyCharacter('Actor');
    c.inventoryRevision = 3;
    c.compendiumRevision = 4;
    const save = autosave('imported', c, vi.fn());
    const imported = mockCharacter();
    imported.mancer = { done: true };
    vi.mocked(saveSheet).mockResolvedValue(undefined);
    await saveImportedCharacter(c, imported, save);
    expect(c.name).toBe(imported.name);
    expect(c.inventoryRevision).toBe(3);
    expect(c.compendiumRevision).toBe(4);
    expect(saveSheet).toHaveBeenCalledTimes(2);
    await expect(saveImportedCharacter(c, imported, save)).rejects.toThrow('already has a character');

    const blank = emptyCharacter('Uncreated');
    const failing = autosave('failed-import', blank, vi.fn());
    await failing.flush();
    vi.mocked(saveSheet).mockRejectedValue(new Error('Disk full'));
    await expect(saveImportedCharacter(blank, imported, failing)).rejects.toThrow('Disk full');
    expect(blank.mancer?.done).toBe(false);
    expect(blank.className).toBe('');
    expect(blank.name).toBe('Uncreated');
    vi.mocked(saveSheet).mockReset();
  });

  it('does not overwrite a newer sheet loaded by an import save conflict or import while read-only', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    vi.stubGlobal('CustomEvent', class { constructor(public type: string, public options: unknown) {} });
    const c = emptyCharacter('Actor');
    const save = autosave('import-conflict', c, vi.fn());
    vi.mocked(saveSheet).mockResolvedValue(undefined);
    await save.flush();
    const imported = mockCharacter();
    const newer = { ...mockCharacter(), name: 'Created elsewhere' };
    vi.mocked(saveSheet).mockRejectedValue(new InventoryConflict(newer, '"conflict"'));
    await expect(saveImportedCharacter(c, imported, save)).rejects.toThrow('changed in another window');
    expect(c.name).toBe('Created elsewhere');
    vi.mocked(saveSheet).mockClear();
    await expect(saveImportedCharacter(emptyCharacter('Disabled'), imported, save, () => false)).rejects.toThrow('read-only');
    expect(saveSheet).not.toHaveBeenCalled();
    vi.mocked(saveSheet).mockReset();
  });

  it('preserves saved testing-sheet manual edits and removed cards instead of rebuilding on every load', async () => {
    const saved = mockCharacter();
    saved.mancer = { done: true };
    saved.testingBuild = { className: 'Ranger', subclass: 'Wayfinder', stage: 'foundation', level: 3,
      ancestry: 'Human', community: 'Wildborne', multiclasses: [] };
    saved.hp.slots = 9;
    saved.stress.slots = 3;
    saved.proficiency.on = 6;
    saved.traits[0].mod = 99;
    saved.loadout = [];
    saved.vault = [];
    vi.mocked(getSheet).mockResolvedValue({ testingCharacter: true, sheet: saved });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ categories: [] }))));
    const loaded = await loadCharacter('tester', 'Tester');
    expect(loaded.hp.slots).toBe(9);
    expect(loaded.stress.slots).toBe(3);
    expect(loaded.proficiency.on).toBe(6);
    expect(loaded.traits[0].mod).toBe(99);
    expect(loaded.loadout).toEqual([]);
    expect(loaded.vault).toEqual([]);
  });

  it('defers pending edits and blocks all background saves while read-only, then resumes when enabled', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('window', globalThis);
    let visibilityChange: (() => void) | undefined;
    vi.stubGlobal('document', { visibilityState: 'hidden', addEventListener: (type: string, handler: () => void) => {
      if (type === 'visibilitychange') visibilityChange = handler;
    } });
    const c = mockCharacter();
    const onError = vi.fn();
    let readOnly = false;
    const save = autosave('disabled', c, onError, 700, () => readOnly);
    c.notes = 'Pending edit';
    save();
    readOnly = true;
    await vi.advanceTimersByTimeAsync(700);
    save();
    visibilityChange!();
    await save.flush();
    await vi.runAllTimersAsync();
    expect(saveSheet).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    expect(c.notes).toBe('Pending edit');
    readOnly = false;
    save();
    await vi.advanceTimersByTimeAsync(700);
    expect(saveSheet).toHaveBeenCalledTimes(1);
    expect(vi.mocked(saveSheet).mock.calls[0][1].notes).toBe('Pending edit');
  });

  it('finds targeted nested grimoire actions and conditional card actions', () => {
    const c = mockCharacter();
    c.loadout = [{ name: 'Book', domain: 'Codex', level: 1, type: 'Grimoire', recall: 0, text: '',
      spells: [{ name: 'Armor', text: '', automation: { actions: [{ id: 'cast', label: 'Cast', target: 'actor' }] } }],
      automation: { conditionalActions: [{ id: 'other', label: 'Other', target: 'actor' }] } }];
    expect(featureTarget(c, 'card:Book/Armor', 'cast')).toBe('actor');
    expect(featureTarget(c, 'card:Book', 'other')).toBe('actor');
    expect(featureTarget(c, 'card:Book/Missing', 'cast')).toBeUndefined();
  });
  it('refreshes the full sheet live without saving it back or overwriting a pending edit', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    vi.stubGlobal('CustomEvent', class { constructor(public type: string, public options: unknown) {} });
    const c = mockCharacter();
    const save = autosave('live', c, vi.fn());
    vi.mocked(getSheet).mockResolvedValue({ version: '"new"', sheet: { ...c, notes: 'Player notes', gold: 12 } });
    await save.refresh();
    expect(c.notes).toBe('Player notes');
    expect(c.gold).toBe(12);
    expect(saveSheet).not.toHaveBeenCalled();
    c.notes = 'Pending DM edit';
    vi.mocked(getSheet).mockResolvedValue({ version: '"newer"', sheet: { ...c, notes: 'Another player edit' } });
    await save.refresh();
    expect(c.notes).toBe('Pending DM edit');
  });

  it('refreshes an unsaved sheet when its actor is renamed', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    vi.stubGlobal('CustomEvent', class { constructor(public type: string, public options: unknown) {} });
    const c = mockCharacter();
    const save = autosave('new-actor', c, vi.fn());
    vi.mocked(getSheet).mockResolvedValue({ version: '"empty"', name: 'New actor name', sheet: null });
    await save.refresh();
    expect(c.name).toBe('New actor name');
    expect(c.description.Name).toBe('New actor name');
    expect(saveSheet).not.toHaveBeenCalled();
  });

  it('rejects full-sheet conflicts explicitly and adopts the latest complete state', async () => {
    vi.stubGlobal('window', globalThis);
    vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
    vi.stubGlobal('CustomEvent', class { constructor(public type: string, public options: unknown) {} });
    const c = mockCharacter();
    const save = autosave('conflict', c, vi.fn());
    c.notes = 'Stale DM edit';
    vi.mocked(saveSheet).mockRejectedValueOnce(new InventoryConflict({ ...c, notes: 'Newest player edit', gold: 20 }, '"latest"'));
    await expect(save.flush()).rejects.toBeInstanceOf(InventoryConflict);
    expect(c.notes).toBe('Newest player edit');
    expect(c.gold).toBe(20);
  });
  it('merges partial death feature inventory without clearing equipment and honors independent card revisions', () => {
    const c = mockCharacter();
    const armor = c.activeArmor;
    const weapons = c.activeWeapons;
    expect(applyFeatureState(c, { actorId: 'a', featureRevision: 1, inventoryRevision: 1,
      inventory: [], compendiumRevision: 1, loadout: [], vault: [], longRestRevision: 3, permanentHp: 2, pendingDeathRoll: { id: 4, ts: 5 } })).toBe(true);
    expect(c.activeArmor).toBe(armor);
    expect(c.activeWeapons).toBe(weapons);
    expect(c.longRestRevision).toBe(3);
    expect(c.pendingDeathRoll).toEqual({ id: 4, ts: 5 });
    const card = { ...mockCharacter().loadout[0], name: 'Unbreakable' };
    expect(applyFeatureState(c, { actorId: 'a', featureRevision: 2, compendiumRevision: 2, vault: [card] })).toBe(true);
    expect(c.vault).toEqual([card]);
    expect(applyFeatureState(c, { actorId: 'a', featureRevision: 3, pendingDeathRoll: null })).toBe(true);
    expect(c.pendingDeathRoll).toBeUndefined();
    expect(c.permanentHp).toBe(2);
    const hp = c.hp;
    const inventory = c.inventory;
    const vault = c.vault;
    expect(applyFeatureState(c, { actorId: 'a', featureRevision: 3, inventoryRevision: 99,
      compendiumRevision: 99, hp: { ...c.hp, on: 0 }, permanentHp: 0, pendingDeathRoll: { id: 99, ts: 99 }, inventory: [], vault: [] })).toBe(false);
    expect(c.hp).toBe(hp);
    expect(c.inventory).toBe(inventory);
    expect(c.vault).toBe(vault);
    expect(c.pendingDeathRoll).toBeUndefined();
    expect(c.permanentHp).toBe(2);
  });

  it('refreshes death metadata and routes gear actions while preserving GM modifiers', () => {
    const c = mockCharacter();
    c.inventory = [{ name: 'Mask', type: 'gear', qty: 1, automation: { modifiers: [{ stat: 'evasion', value: 2 }] } }];
    updateCharacterAutomation(c, [{ format: 'dmmap-daggerheart', version: 1, label: 'Items', category: 'items', entries: [{ id: 'mask', name: 'Mask', automation: {
      deathMove: { kind: 'mask', bonus: 1, moves: ['avoid', 'risk'] },
      resources: [{ id: 'death-mask', label: 'Tokens', initial: 0, max: 4 }],
      actions: [{ id: 'gain', label: 'Gain token', steps: [{ op: 'gain', resource: 'death-mask', amount: 1 }] }],
    } }] }]);
    expect(c.inventory[0].automation?.deathMove?.kind).toBe('mask');
    expect(c.resources?.['death-mask']).toBe(0);
    expect(c.inventory[0].automation?.actions).toHaveLength(1);
    expect(c.inventory[0].automation?.modifiers).toEqual([{ stat: 'evasion', value: 2 }]);
  });
  it('adds reaction-only gear options without replacing existing custom mechanics or duplicating options', () => {
    const c = mockCharacter();
    c.inventory = [{ name: 'Anchoring Hook', type: 'gear', qty: 1, automation: {
      modifiers: [{ stat: 'evasion', value: 2 }],
    } }];
    const files: CompendiumFile[] = [{
      format: 'dmmap-daggerheart', version: 1, label: 'Items', category: 'items', entries: [{
        id: 'anchoring-hook', name: 'Anchoring Hook', automation: {
          rollOptions: [{ id: 'anchor', label: 'Avoid forced movement', roll: 'reaction', advantage: 'advantage' }],
        },
      }],
    }];
    updateCharacterAutomation(c, files);
    const rules = c.inventory[0].automation!;
    expect(rules.rollOptions).toHaveLength(1);
    rules.rollOptions![0].label = 'GM label';
    updateCharacterAutomation(c, files);
    expect(rules.rollOptions).toHaveLength(1);
    expect(rules.rollOptions![0].label).toBe('GM label');
    expect(rules.modifiers).toEqual([{ stat: 'evasion', value: 2 }]);
  });

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

  it('never attaches compendium automation to custom items', () => {
    const c = mockCharacter();
    c.inventory = [{ name: 'Custom recipe', type: 'gear', qty: 1, custom: true }];
    updateCharacterAutomation(c, [{
      format: 'dmmap-daggerheart', version: 1, label: 'Items', category: 'items', entries: [{ id: 'recipe', name: 'Custom recipe', automation: {
        actions: [{ id: 'craft', label: 'Craft', timing: 'downtime' }], rest: { extraMoves: 1 },
      } }],
    }]);
    expect(c.inventory[0].automation).toBeUndefined();
  });

  it('applies transferred gold with newer inventory generations only', () => {
    const c = mockCharacter();
    c.gold = 3;
    expect(applyInventory(c, { inventoryRevision: 2, gold: 9 })).toBe(true);
    expect(c.gold).toBe(9);
    expect(applyInventory(c, { inventoryRevision: 2, gold: 1 })).toBe(false);
    expect(c.gold).toBe(9);
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
