import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { acceptSheetVersion, getSheet, InventoryConflict, saveSheet, useFeature, type FeatureResponse, type SheetData } from '../api';
import { emptyCharacter, type CharacterSheet } from './data';
import { autosave, rememberLoadedCharacter } from './persist';

vi.mock('../api', async () => ({
  ...await vi.importActual<typeof import('../api')>('../api'),
  getSheet: vi.fn(), saveSheet: vi.fn(), useFeature: vi.fn(),
}));
beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal('window', globalThis);
  vi.stubGlobal('document', { addEventListener: vi.fn(), dispatchEvent: vi.fn() });
  vi.stubGlobal('CustomEvent', class extends Event {});
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
function fixture() {
  const c = emptyCharacter('Hero');
  c.testingCharacter = false;
  rememberLoadedCharacter(c, structuredClone(c));
  acceptSheetVersion('actor', '"initial"', c);
  return { c, save: autosave('actor', c, vi.fn()) };
}
function committed(c: CharacterSheet, version: string): FeatureResponse {
  return { ok: true, sheetState: { actorId: 'actor', version, sheet: structuredClone(c), testingCharacter: false } };
}
const request = { actorId: 'actor', key: 'feat:Test', actionId: 'use' };

it('skips unchanged initial saves but preserves new characters and migrations', async () => {
  const { c, save } = fixture();
  await save.flush();
  expect(saveSheet).not.toHaveBeenCalled();
  const migrated = emptyCharacter('Migrated');
  rememberLoadedCharacter(migrated, { ...migrated, notes: 'before migration' });
  await autosave('migration', migrated, vi.fn()).flush();
  await autosave('new', emptyCharacter('New'), vi.fn()).flush();
  expect(saveSheet).toHaveBeenCalledTimes(2);
  c.notes = 'Edit';
  await save.flush();
  expect(saveSheet).toHaveBeenCalledTimes(3);
});

it('coalesces refresh bursts and ignores duplicate accepted versions', async () => {
  const { c, save } = fixture();
  vi.mocked(getSheet).mockResolvedValue({ version: '"next"', sheet: { ...c, gold: 9 } });
  await Promise.all([save.refresh('"next"'), save.refresh('"next"'), save.refresh('"next"')]);
  expect(getSheet).toHaveBeenCalledTimes(1);
  await save.refresh('"next"');
  expect(getSheet).toHaveBeenCalledTimes(1);
  expect(c.gold).toBe(9);
});

it('applies committed actions without a GET or save-back, even when the push arrives first', async () => {
  const { c, save } = fixture();
  let finish!: (response: FeatureResponse) => void;
  vi.mocked(useFeature).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const action = save.action(request);
  await vi.waitFor(() => expect(useFeature).toHaveBeenCalledOnce());
  await save.refresh('"committed"');
  finish(committed({ ...c, gold: 7 }, '"committed"'));
  await action;
  await save.flush();
  expect(c.gold).toBe(7);
  expect(getSheet).not.toHaveBeenCalled();
  expect(saveSheet).not.toHaveBeenCalled();
});

it('serializes different action buttons so delayed responses cannot roll the sheet back', async () => {
  const { c, save } = fixture();
  let finish!: (response: FeatureResponse) => void;
  vi.mocked(useFeature).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockImplementationOnce(async () => committed({ ...c, gold: c.gold + 3 }, '"B"'));
  const first = save.action(request);
  const second = save.action({ ...request, actionId: 'another-button' });
  await vi.waitFor(() => expect(useFeature).toHaveBeenCalledTimes(1));
  finish(committed({ ...c, gold: 4 }, '"A"'));
  await Promise.all([first, second]);
  expect(useFeature).toHaveBeenCalledTimes(2);
  expect(c.gold).toBe(7);
  expect(getSheet).not.toHaveBeenCalled();
});

it('discards an old GET completing after an action representation', async () => {
  const { c, save } = fixture();
  let finish!: (data: SheetData) => void;
  const old = structuredClone(c);
  vi.mocked(getSheet).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const refresh = save.refresh();
  await vi.waitFor(() => expect(getSheet).toHaveBeenCalledOnce());
  vi.mocked(useFeature).mockResolvedValue(committed({ ...c, gold: 8 }, '"new"'));
  await save.action(request);
  finish({ version: '"old"', sheet: old });
  await refresh;
  expect(c.gold).toBe(8);
});

it('preserves edits made during an action and leaves version conflict protection intact', async () => {
  const { c, save } = fixture();
  let finish!: (response: FeatureResponse) => void;
  vi.mocked(useFeature).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const action = save.action(request);
  await vi.waitFor(() => expect(useFeature).toHaveBeenCalledOnce());
  const latest = { ...c, gold: 2 };
  c.notes = 'Unsaved local text';
  finish(committed(latest, '"new"'));
  await action;
  expect(c.notes).toBe('Unsaved local text');
  expect(c.gold).not.toBe(2);
  expect(getSheet).not.toHaveBeenCalled();
  vi.mocked(saveSheet).mockRejectedValueOnce(new InventoryConflict(latest, '"new"'));
  await expect(save.flush()).rejects.toBeInstanceOf(InventoryConflict);
});

it('falls back to a GET on legacy action success, and never replays failed actions', async () => {
  const { c, save } = fixture();
  vi.mocked(useFeature).mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('Disconnected'));
  vi.mocked(getSheet).mockResolvedValue({ version: '"legacy"', sheet: { ...c, gold: 3 } });
  await save.action(request);
  expect(getSheet).toHaveBeenCalledTimes(1);
  expect(c.gold).toBe(3);
  await expect(save.action(request)).rejects.toThrow('Disconnected');
  expect(useFeature).toHaveBeenCalledTimes(2);
});

it('preserves edits made during slow saves and suppresses their matching notification GET', async () => {
  const { c, save } = fixture();
  let finish!: (data: SheetData) => void;
  vi.mocked(saveSheet).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValueOnce({ version: '"second"' });
  c.notes = 'First edit';
  const first = save.flush();
  const submitted = structuredClone(c);
  await save.refresh('"first"');
  c.notes = 'Typed while saving';
  const second = save.flush();
  finish({ version: '"first"', sheet: submitted });
  await Promise.all([first, second]);
  expect(c.notes).toBe('Typed while saving');
  expect(saveSheet).toHaveBeenCalledTimes(2);
  expect(JSON.parse(vi.mocked(saveSheet).mock.calls[1][2]!).notes).toBe('Typed while saving');
  expect(getSheet).not.toHaveBeenCalled();
});
