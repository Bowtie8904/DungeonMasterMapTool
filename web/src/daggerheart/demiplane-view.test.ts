import { afterEach, expect, it, vi } from 'vitest';
import { getDemiplaneCharacter } from '../api';
import { noticeDialog } from '../dialogs';
import { fetchCompendiumCategory } from './compendium';
import { emptyCharacter } from './data';
import { DemiplaneAncestryChoiceRequired, recreateDemiplane } from './demiplane';
import { openDemiplaneImport } from './demiplane-view';
import { loadRulesData } from './rules';

vi.mock('../api', () => ({ getDemiplaneCharacter: vi.fn() }));
vi.mock('../dialogs', () => ({ noticeDialog: vi.fn().mockResolvedValue(true) }));
vi.mock('./compendium', () => ({ fetchCompendiumCategory: vi.fn().mockResolvedValue({ entries: [] }) }));
vi.mock('./rules', () => ({ loadRulesData: vi.fn().mockResolvedValue({}) }));
vi.mock('./demiplane', async importOriginal => ({
  ...await importOriginal<typeof import('./demiplane')>(), recreateDemiplane: vi.fn(),
}));

class Element {
  className = '';
  value = '';
  disabled = false;
  isConnected = true;
  textContent = '';
  children: Array<Element | string> = [];
  listeners = new Map<string, (event: { preventDefault: () => void }) => unknown>();
  attributes: Record<string, string> = {};
  constructor(readonly tag: string) {}
  append(...nodes: Array<Element | string>) { this.children.push(...nodes); }
  replaceChildren(...nodes: Array<Element | string>) { this.children = nodes; }
  addEventListener(type: string, handler: (event: { preventDefault: () => void }) => unknown) { this.listeners.set(type, handler); }
  setAttribute(name: string, value: string) { this.attributes[name] = value; }
  removeAttribute(name: string) { delete this.attributes[name]; }
  showModal() {}
  close() { this.listeners.get('close')?.({ preventDefault() {} }); }
  remove() { this.isConnected = false; }
  focus() {}
  find(tag: string): Element {
    if (this.tag === tag) return this;
    for (const child of this.children) {
      if (typeof child !== 'string') {
        const found = child.find(tag);
        if (found) return found;
      }
    }
    return undefined!;
  }
}

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

function open(save: (character: ReturnType<typeof emptyCharacter>, portrait?: string) => Promise<string | void>, canImport = () => true) {
  const body = new Element('body');
  vi.stubGlobal('HTMLElement', Element);
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag), activeElement: null });
  openDemiplaneImport('hero', canImport, save);
  const dialog = body.find('dialog');
  const input = dialog.find('input');
  input.value = 'https://app.demiplane.com/nexus/daggerheart/character-sheet/test';
  return { dialog, input, submit: () => dialog.find('form').listeners.get('submit')!({ preventDefault() {} }) };
}

it('opens only a link dialog and reports import issues only after the save completes', async () => {
  const c = emptyCharacter('Imported Hero');
  c.level = 5;
  vi.mocked(recreateDemiplane).mockReturnValue({ character: c,
    issues: ['Unmatched item: Moon Sword.', 'Portrait not transferred.', 'Unsupported field.'],
    reportIssues: ['Item quantities could not be verified.', 'Feature or card "Missing Spell" was not found in the compendium.',
      'The active loadout could not be verified.'] });
  vi.mocked(getDemiplaneCharacter).mockResolvedValue({ name: c.name, level: 5, summary: {}, engines: [] });
  let finish!: () => void;
  const save = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
  const { dialog, input, submit } = open(save);
  expect(getDemiplaneCharacter).not.toHaveBeenCalled();
  submit();
  await vi.waitFor(() => expect(save).toHaveBeenCalledWith(c));
  expect(input.disabled).toBe(true);
  expect(noticeDialog).not.toHaveBeenCalled();
  const escape = vi.fn();
  dialog.listeners.get('cancel')!({ preventDefault: escape });
  expect(escape).toHaveBeenCalled();
  finish();
  await vi.waitFor(() => expect(noticeDialog).toHaveBeenCalled());
  expect(vi.mocked(noticeDialog).mock.calls[0][0]).toContain('level 5 and saved');
  const report = vi.mocked(noticeDialog).mock.calls[0][0];
  expect(report).toContain('Item quantities');
  expect(report).toContain('Missing Spell');
  expect(report).toContain('active loadout could not be verified');
  expect(report).not.toMatch(/Moon Sword|Portrait|Unsupported field/);
  expect(dialog.isConnected).toBe(false);
  expect(loadRulesData).toHaveBeenCalledTimes(1);
  expect(fetchCompendiumCategory).toHaveBeenCalledWith('items');
});

it('passes the public portrait to the save handler and reports a post-save artwork failure', async () => {
  const character = emptyCharacter('Portrait Hero');
  const portrait = 'data:image/webp;base64,cmVwbGFjZWQ=';
  vi.mocked(recreateDemiplane).mockReturnValue({ character, issues: [], reportIssues: [] });
  vi.mocked(getDemiplaneCharacter).mockResolvedValue({ name: character.name, level: 1, summary: {}, engines: [], portrait });
  const save = vi.fn().mockResolvedValue('Portrait could not be saved.');
  const { submit } = open(save);
  submit();
  await vi.waitFor(() => expect(noticeDialog).toHaveBeenCalled());
  expect(save).toHaveBeenCalledWith(character, portrait);
  expect(vi.mocked(noticeDialog).mock.calls[0][0]).toContain('Portrait could not be saved.');
  expect(vi.mocked(noticeDialog).mock.calls[0][1]).toBe('Character imported - review needed');
});

it('keeps link and errors visible after a failed save and never shows success', async () => {
  vi.mocked(recreateDemiplane).mockReturnValue({ character: emptyCharacter('Hero'), issues: [], reportIssues: [] });
  const { dialog, input, submit } = open(vi.fn().mockRejectedValue(new Error('Disk full')));
  submit();
  await vi.waitFor(() => expect(input.disabled).toBe(false));
  expect(dialog.isConnected).toBe(true);
  expect(dialog.children[0]).toBeDefined();
  expect(noticeDialog).not.toHaveBeenCalled();
  const form = dialog.find('form');
  expect(form.children.some(child => child instanceof Element && child.textContent === 'Disk full')).toBe(true);
});

it('checks actor permissions before fetching', async () => {
  const save = vi.fn();
  const { input, submit } = open(save, () => false);
  submit();
  await vi.waitFor(() => expect(input.disabled).toBe(false));
  expect(getDemiplaneCharacter).not.toHaveBeenCalled();
  expect(save).not.toHaveBeenCalled();
  expect(noticeDialog).not.toHaveBeenCalled();
});

it('shows only success when there are no user-facing warnings', async () => {
  vi.mocked(recreateDemiplane).mockReturnValue({ character: emptyCharacter('Hero'),
    issues: ['Unsupported field.'], reportIssues: [] });
  const { submit } = open(vi.fn().mockResolvedValue(undefined));
  submit();
  await vi.waitFor(() => expect(noticeDialog).toHaveBeenCalled());
  expect(noticeDialog).toHaveBeenCalledWith('Hero was imported at level 1 and saved.', 'Character imported');
});

it('asks for ambiguous ancestries one at a time without refetching or saving before resolution', async () => {
  const c = emptyCharacter('Hero');
  vi.mocked(getDemiplaneCharacter).mockResolvedValue({ name: c.name, level: 1, summary: {}, engines: [] });
  vi.mocked(recreateDemiplane)
    .mockImplementationOnce(() => { throw new DemiplaneAncestryChoiceRequired(0, 'Amphibious', ['Ribbet', 'Tidekin']); })
    .mockImplementationOnce(() => { throw new DemiplaneAncestryChoiceRequired(1, 'Kick', ['Faun', 'Centaur']); })
    .mockReturnValueOnce({ character: c, issues: [], reportIssues: [] });
  const save = vi.fn().mockResolvedValue(undefined);
  const { dialog, input, submit } = open(save);
  submit();
  await vi.waitFor(() => expect(dialog.find('select')).toBeDefined());
  expect(save).not.toHaveBeenCalled();
  expect(dialog.find('select').children).toHaveLength(3);
  dialog.find('select').value = 'Tidekin';
  submit();
  await vi.waitFor(() => expect(vi.mocked(recreateDemiplane)).toHaveBeenCalledTimes(2));
  expect(save).not.toHaveBeenCalled();
  dialog.find('select').value = 'Centaur';
  submit();
  await vi.waitFor(() => expect(save).toHaveBeenCalledWith(c));
  expect(vi.mocked(recreateDemiplane).mock.calls[2][3]).toEqual({ 0: 'Tidekin', 1: 'Centaur' });
  expect(getDemiplaneCharacter).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(input.disabled).toBe(false));
});

it('discards pending ancestry choices when the link changes', async () => {
  vi.mocked(getDemiplaneCharacter).mockResolvedValue({ name: 'Hero', level: 1, summary: {}, engines: [] });
  vi.mocked(recreateDemiplane)
    .mockImplementationOnce(() => { throw new DemiplaneAncestryChoiceRequired(0, 'Amphibious', ['Ribbet', 'Tidekin']); })
    .mockReturnValueOnce({ character: emptyCharacter('Other'), issues: [], reportIssues: [] });
  const save = vi.fn().mockResolvedValue(undefined);
  const { dialog, input, submit } = open(save);
  submit();
  await vi.waitFor(() => expect(dialog.find('select')).toBeDefined());
  input.value = 'https://app.demiplane.com/nexus/daggerheart/character-sheet/other';
  input.listeners.get('input')!({ preventDefault() {} });
  expect(dialog.find('select')).toBeUndefined();
  submit();
  await vi.waitFor(() => expect(save).toHaveBeenCalled());
  expect(getDemiplaneCharacter).toHaveBeenCalledTimes(2);
  expect(vi.mocked(recreateDemiplane).mock.calls[1][3]).toEqual({});
});
