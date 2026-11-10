import { afterEach, expect, it, vi } from 'vitest';

const trayClose = vi.fn();
const trays: Array<{ isExternal: boolean }> = [];
vi.mock('./view', () => ({
  SheetBus: class { notify = vi.fn(); },
  renderSheetView: () => document.createElement('div'),
}));
vi.mock('./quick-access-tray', () => ({
  QuickAccessTray: class {
    isOpen = false; isExternal = false; button = document.createElement('button'); close = trayClose;
    constructor() { trays.push(this); }
    setContent(): void {}
  },
}));

class FakeElement {
  className = '';
  title = '';
  style: Record<string, string> = {};
  children: FakeElement[] = [];
  parent: FakeElement | null = null;
  listeners = new Map<string, Array<(event: unknown) => void>>();
  classList = {
    contains: (name: string) => this.className.split(' ').includes(name),
    toggle: (name: string, force?: boolean) => {
      const names = new Set(this.className.split(' ').filter(Boolean));
      const on = force ?? !names.has(name);
      if (on) names.add(name); else names.delete(name);
      this.className = [...names].join(' ');
    },
    remove: (name: string) => this.classList.toggle(name, false),
  };
  constructor(readonly tag: string) {}
  append(...nodes: Array<FakeElement | string>): void {
    for (const node of nodes) if (typeof node !== 'string') { node.parent = this; this.children.push(node); }
  }
  replaceChildren(...nodes: FakeElement[]): void { this.children = []; this.append(...nodes); }
  addEventListener(type: string, fn: (event: unknown) => void): void { this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]); }
  dispatch(type: string, target: FakeElement): void {
    for (const fn of this.listeners.get(type) ?? []) fn({ target, preventDefault() {} });
  }
  closest(selector: string): FakeElement | null {
    const tags = selector.split(',').map(s => s.trim());
    for (let node: FakeElement | null = this; node; node = node.parent) if (tags.includes(node.tag)) return node;
    return null;
  }
  all(): FakeElement[] { return [this, ...this.children.flatMap(c => c.all())]; }
}

afterEach(() => vi.unstubAllGlobals());

it('toggles minimise when the title bar is double-clicked, ignoring its buttons', async () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag), activeElement: null });
  const { SheetWindow } = await import('./window');
  const win = new SheetWindow({ character: {} } as never, 'Hero', () => {}, () => {});
  const frame = win.element as unknown as FakeElement;
  const title = frame.children.find(c => c.className === 'dh-win-title')!;
  const heading = title.all().find(c => c.tag === 'h2')!;
  title.dispatch('dblclick', heading);
  expect(frame.classList.contains('minimised')).toBe(true);
  expect(trayClose).toHaveBeenCalled();
  title.dispatch('dblclick', title);
  expect(frame.classList.contains('minimised')).toBe(false);
  title.dispatch('dblclick', title.children.find(c => c.tag === 'button')!);
  expect(frame.classList.contains('minimised')).toBe(false);
  trayClose.mockClear();
  trays.at(-1)!.isExternal = true;
  title.dispatch('dblclick', title);
  expect(frame.classList.contains('minimised')).toBe(true);
  expect(trayClose).not.toHaveBeenCalled();
});
