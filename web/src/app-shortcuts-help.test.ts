import { afterEach, expect, it, vi } from 'vitest';
import { App } from './app';
import { confirmDialog, noticeDialog } from './dialogs';

class Element {
  className = '';
  children: Array<Element | string> = [];
  attributes = new Map<string, string>();
  listeners = new Map<string, () => void>();
  isConnected = true;
  open = false;
  tabIndex = -1;
  focus = vi.fn();
  constructor(readonly tag: string) {}
  append(...nodes: Array<Element | string>): void { this.children.push(...nodes); }
  replaceChildren(...nodes: Array<Element | string>): void { this.children = nodes; }
  setAttribute(name: string, value: string): void { this.attributes.set(name, value); }
  addEventListener(type: string, listener: () => void): void { this.listeners.set(type, listener); }
  click(): void { this.listeners.get('click')?.(); }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void { this.isConnected = false; }
  all(): Element[] { return [this, ...this.children.flatMap(node => typeof node === 'string' ? [] : node.all())]; }
  text(): string { return this.children.map(node => typeof node === 'string' ? node : node.text()).join(''); }
}

afterEach(() => vi.unstubAllGlobals());

function fixture() {
  const body = new Element('body');
  const documentStub = { body, activeElement: null as Element | null, createElement: (tag: string) => new Element(tag), addEventListener: vi.fn() };
  vi.stubGlobal('document', documentStub);
  vi.stubGlobal('HTMLElement', Element);
  const storage = { getItem: vi.fn(() => null), setItem: vi.fn() };
  vi.stubGlobal('localStorage', storage);
  const context = {
    audio: {}, audioGesture: vi.fn(), updateSoundUi: vi.fn(),
    audioSection: new Element('section'), volumeBox: new Element('div'),
    soundBtn: new Element('button'), soundToggle: new Element('input'),
  };
  const section = Reflect.get(App.prototype, 'buildSettings').call(context) as Element;
  const button = section.all().find(node => node.className === 'settings-shortcuts')!;
  return { section, button, body, documentStub, storage };
}

it('adds a compact informational Shortcuts button to Settings and lists only implemented bindings', () => {
  const f = fixture();
  expect(f.button.tag).toBe('button');
  expect(f.button.text()).toBe('Shortcuts');
  expect(f.button.attributes.get('aria-haspopup')).toBe('dialog');
  expect(f.section.children[0]).toBe(f.button);
  f.documentStub.activeElement = f.button;
  f.button.click();
  const dialog = f.body.all().find(node => node.tag === 'dialog')!;
  expect(dialog.open).toBe(true);
  expect(dialog.className).toBe('app-message-dialog');
  expect(dialog.text()).toContain('Player shortcuts');
  const reference = dialog.all().find(node => node.className === 'shortcut-help')!;
  expect(reference.tabIndex).toBe(0);
  expect(dialog.children.includes(reference)).toBe(true);
  expect(reference.all().some(node => node.tag === 'button')).toBe(false);
  for (const key of ['C', 'F', 'D', 'R', 'Space', 'P', 'Arrow keys', 'Ctrl+Z / Cmd+Z', 'Delete / Backspace', 'Escape']) {
    expect(dialog.text()).toContain(`${key} -`);
  }
  for (const restriction of ['host permission', 'while paused', 'never tokens']) {
    expect(dialog.text()).toContain(restriction);
  }
  expect(dialog.text()).not.toMatch(/Enter \/ Space on a My actors row|The active character is your first non-disabled character|Shift\+C|hotbar|target the token/i);
  const buttons = dialog.all().filter(node => node.tag === 'button');
  expect(buttons).toHaveLength(1);
  expect(buttons[0].text()).toBe('OK');
  expect(buttons[0].focus).toHaveBeenCalledOnce();
  buttons[0].click();
  expect(dialog.isConnected).toBe(false);
  expect(f.button.focus).toHaveBeenCalledOnce();
  expect(f.storage.setItem).not.toHaveBeenCalled();
});

it('restores focus when the informational dialog is dismissed through its native close event', () => {
  const f = fixture();
  f.documentStub.activeElement = f.button;
  f.button.click();
  const dialog = f.body.all().find(node => node.tag === 'dialog')!;
  dialog.close();
  expect(dialog.open).toBe(false);
  expect(dialog.isConnected).toBe(false);
  expect(f.button.focus).toHaveBeenCalledOnce();
});

it('preserves plain-text notices and cancellable confirmation dialogs', async () => {
  const f = fixture();
  const notice = noticeDialog('Plain <text>', 'Notice');
  const dialog = f.body.all().find(node => node.tag === 'dialog')!;
  expect(dialog.all().find(node => node.tag === 'p')!.text()).toBe('Plain <text>');
  dialog.all().find(node => node.tag === 'button')!.click();
  expect(await notice).toBe(true);
  const confirmation = confirmDialog('Keep the existing behavior?', 'Confirm', 'Continue');
  const confirm = f.body.all().filter(node => node.tag === 'dialog').at(-1)!;
  const buttons = confirm.all().filter(node => node.tag === 'button');
  expect(buttons.map(node => node.text())).toEqual(['Cancel', 'Continue']);
  buttons[0].click();
  expect(await confirmation).toBe(false);
});
