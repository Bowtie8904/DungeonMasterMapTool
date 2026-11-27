import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import type { Actor } from './protocol';

class Target {
  isContentEditable = false;
  constructor(readonly editable = false, readonly interactive = false) {}
  closest(selector: string): Target | null {
    return this.editable && selector.startsWith('input') || this.interactive && selector.startsWith('button') ? this : null;
  }
}

afterEach(() => vi.unstubAllGlobals());

function fixture() {
  let modal = false;
  vi.stubGlobal('HTMLElement', Target);
  vi.stubGlobal('document', { querySelector: () => modal ? {} : null });
  const actor: Actor = { id: 'hero', ownerId: 'player', name: 'Hero' };
  const win = {
    isOpen: false, minimised: false,
    get isMinimised() { return this.isOpen && this.minimised; },
    setOpen: vi.fn((open: boolean) => { win.isOpen = open; win.minimised = false; }),
    toggleMinimised: vi.fn(() => { win.minimised = !win.minimised; }),
    toggleQuickAccess: vi.fn(), closeQuickAccess: vi.fn(),
  };
  const handle = { bus: { takeDamage: vi.fn(() => { modal = true; return true; }) } };
  const other = { closeQuickAccess: vi.fn() };
  let toolsHidden = true;
  const view = { tool: 'draw', measureMode: 'radius', paused: false, focusActor: vi.fn(() => true) };
  const measure = { disabled: false, click: vi.fn(() => { view.tool = 'measure'; toolsHidden = false; }) };
  const distance = { click: vi.fn(() => { view.tool = 'measure'; view.measureMode = 'distance'; }) };
  const context = {
    myActors: [{ ...actor, id: 'disabled', disabled: true }, actor],
    ensureSheet: vi.fn().mockResolvedValue(handle),
    sheetWindows: new Map<string, typeof win | typeof other>([[actor.id, win], ['other', other]]),
    quickAccessButton: new Target(), toast: vi.fn(),
    view, toolButtons: new Map([['measure', measure]]), distanceRulerButton: distance,
    flyout: { classList: { contains: () => toolsHidden } }, closeTools: vi.fn(),
  };
  const press = (key: string, overrides: Partial<KeyboardEvent> = {}) => {
    const event = { key, target: new Target(), preventDefault: vi.fn(), ...overrides };
    const handled = Reflect.get(App.prototype, 'playerShortcut').call(context, event);
    return { handled, event };
  };
  const settle = async () => { await Promise.resolve(); await Promise.resolve(); };
  return { actor, context, win, other, handle, measure, distance, press, settle, modal: (open: boolean) => { modal = open; } };
}

describe('player character shortcuts', () => {
  it('opens the active sheet, then alternates condensed and expanded without closing it', async () => {
    const f = fixture();
    for (const [key, condensed] of [['c', false], ['c', true], ['C', false]] as const) {
      const { handled, event } = f.press(key);
      await f.settle();
      expect(handled).toBe(true);
      expect(event.preventDefault).toHaveBeenCalledOnce();
      expect(f.win.isOpen).toBe(true);
      expect(f.win.isMinimised).toBe(condensed);
    }
    expect(f.context.ensureSheet).toHaveBeenCalledWith(f.actor);
    expect(f.win.setOpen).toHaveBeenCalledTimes(1);
    expect(f.win.toggleMinimised).toHaveBeenCalledTimes(2);
  });

  it('uses the condensed-sheet tray only while that active sheet is open and condensed', async () => {
    const f = fixture();
    for (const [open, condensed, expected] of [
      [false, false, f.context.quickAccessButton],
      [true, false, f.context.quickAccessButton],
      [true, true, undefined],
      [false, true, f.context.quickAccessButton],
    ] as const) {
      f.win.isOpen = open;
      f.win.minimised = condensed;
      f.press('f');
      await f.settle();
      expect(f.win.toggleQuickAccess).toHaveBeenLastCalledWith(expected);
      expect(f.win.isOpen).toBe(open);
      expect(f.win.minimised).toBe(condensed);
    }
    expect(f.other.closeQuickAccess).toHaveBeenCalledTimes(4);
  });

  it('opens incoming-damage mitigation without opening the sheet or stacking queued dialogs', async () => {
    const f = fixture();
    f.press('d');
    f.press('d');
    await f.settle();
    expect(f.handle.bus.takeDamage).toHaveBeenCalledOnce();
    expect(f.win.setOpen).not.toHaveBeenCalled();
    expect(f.press('d').handled).toBe(false);
  });

  it.each(['c', 'f', 'd', 'r', ' '])('ignores %s while typing, composing, modified, repeated, handled or in a dialog', key => {
    const f = fixture();
    for (const overrides of [
      { target: new Target(true) }, { target: Object.assign(new Target(), { isContentEditable: true }) },
      { ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true },
      { repeat: true }, { isComposing: true }, { defaultPrevented: true },
    ]) {
      expect(f.press(key, overrides as Partial<KeyboardEvent>).handled).toBe(false);
    }
    f.modal(true);
    expect(f.press(key).handled).toBe(false);
    expect(f.context.ensureSheet).not.toHaveBeenCalled();
    expect(f.measure.click).not.toHaveBeenCalled();
    expect(f.distance.click).not.toHaveBeenCalled();
    expect(f.context.view.focusActor).not.toHaveBeenCalled();
  });

  it('ignores missing active characters and unrelated keys', () => {
    const f = fixture();
    expect(f.press('p').handled).toBe(false);
    f.context.myActors = [{ ...f.actor, disabled: true }];
    for (const key of ['c', 'f', 'd', ' ']) expect(f.press(key).handled).toBe(false);
    expect(f.context.ensureSheet).not.toHaveBeenCalled();
  });

  it('activates Distance ruler without a character, and never closes already-open measure options', () => {
    const f = fixture();
    f.context.myActors = [];
    expect(f.press('r').handled).toBe(true);
    expect(f.context.view.tool).toBe('measure');
    expect(f.context.view.measureMode).toBe('distance');
    expect(f.measure.click).toHaveBeenCalledOnce();
    for (const previous of ['radius', 'cone', 'rect']) {
      f.context.view.measureMode = previous;
      f.press('R');
      expect(f.context.view.measureMode).toBe('distance');
    }
    expect(f.measure.click).toHaveBeenCalledOnce();
    expect(f.distance.click).toHaveBeenCalledTimes(4);
    expect(f.context.ensureSheet).not.toHaveBeenCalled();
  });

  it('does not activate the ruler when paused or measurements are unavailable', () => {
    const f = fixture();
    f.context.view.paused = true;
    expect(f.press('r').handled).toBe(false);
    f.context.view.paused = false;
    f.measure.disabled = true;
    expect(f.press('r').handled).toBe(false);
    f.context.toolButtons.clear();
    expect(f.press('r').handled).toBe(false);
    expect(f.measure.click).not.toHaveBeenCalled();
    expect(f.distance.click).not.toHaveBeenCalled();
  });

  it('centers and selects only the active character, including while paused', () => {
    const f = fixture();
    const { handled, event } = f.press(' ');
    expect(handled).toBe(true);
    expect(event.preventDefault).toHaveBeenCalledOnce();
    expect(f.context.view.focusActor).toHaveBeenCalledWith(f.actor.id);
    expect(f.context.closeTools).toHaveBeenCalledOnce();
    f.context.view.paused = true;
    f.press(' ');
    expect(f.context.view.focusActor).toHaveBeenCalledTimes(2);
    expect(f.context.ensureSheet).not.toHaveBeenCalled();
  });

  it('leaves tools unchanged with no token and preserves Space activation on interactive controls', () => {
    const f = fixture();
    f.context.view.focusActor.mockReturnValue(false);
    f.press(' ');
    expect(f.context.closeTools).not.toHaveBeenCalled();
    const { handled, event } = f.press(' ', { target: new Target(false, true) as unknown as EventTarget });
    expect(handled).toBe(false);
    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(f.context.view.focusActor).toHaveBeenCalledOnce();
  });

  it('does not act if the active character changes or a dialog opens during loading', async () => {
    const f = fixture();
    f.press('c');
    f.context.myActors = [];
    await f.settle();
    expect(f.win.setOpen).not.toHaveBeenCalled();
    f.context.myActors = [f.actor];
    f.press('f');
    f.modal(true);
    await f.settle();
    expect(f.win.toggleQuickAccess).not.toHaveBeenCalled();
  });

  it('reports load failures and unavailable damage actions', async () => {
    const f = fixture();
    f.context.ensureSheet.mockRejectedValueOnce(new Error('Not connected'));
    f.press('c');
    await f.settle();
    expect(f.context.toast).toHaveBeenCalledWith('Cannot load the character sheet: Not connected');
    f.handle.bus.takeDamage.mockReturnValue(false);
    f.press('d');
    await f.settle();
    expect(f.context.toast).toHaveBeenLastCalledWith('Finish character creation and ancestry selection before taking damage.');
  });
});
