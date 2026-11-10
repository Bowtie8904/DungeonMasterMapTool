import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { mockCharacter } from './daggerheart/data';
import type { Actor } from './protocol';

class Element {
  children: Array<Element | string> = [];
  listeners = new Map<string, (event: object) => void>();
  className = '';
  private textValue = '';
  get textContent() { return this.textValue; }
  set textContent(value: string) { this.textValue = value; this.children = []; }
  title = '';
  src = '';
  tabIndex = -1;
  role = '';
  ariaLabel = '';
  classList = {
    add: (name: string) => { this.className += ` ${name}`; },
    remove: (name: string) => { this.className = this.className.split(' ').filter(c => c !== name).join(' '); },
  };
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>) { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>) { this.textValue = ''; this.children = children; }
  addEventListener(type: string, callback: (event: object) => void) { this.listeners.set(type, callback); }
  setAttribute(name: string, value: string) { if (name === 'role') this.role = value; }
  removeAttribute(name: string) { if (name === 'role') this.role = ''; }
  fire(type: string, event = {}) { this.listeners.get(type)?.(event); }
  all(): Element[] { return [this, ...this.children.flatMap(child => typeof child === 'string' ? [] : child.all())]; }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  find(cls: string): Element { return this.all().find(e => e.className.split(' ').includes(cls))!; }
}

afterEach(() => { vi.unstubAllGlobals(); });

const actor: Actor = { id: 'actor', name: 'Lyra', ownerId: 'player', artwork: '/a/portrait.png' };

function fixture(actors = [actor]) {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  const character = mockCharacter();
  character.className = 'Wizard';
  character.subclass = 'School of Knowledge';
  character.level = 4;
  const handle = { character, bus: { artwork: actor.artwork, notify: vi.fn() } };
  const actorsEl = new Element('div');
  const context = {
    playerId: 'player', myActors: [] as Actor[], actorsEl,
    actorRowUpdates: new Map<string, () => void>(),
    sheetHandles: new Map([[actor.id, handle]]),
    ensureSheet: vi.fn().mockResolvedValue(handle),
    scheduleEffects: vi.fn(), syncTokenConditionEffects: vi.fn(),
    openSheet: vi.fn(), view: { focus: vi.fn() },
    logContent: { querySelectorAll: () => [] },
    quickAccessButton: { disabled: true, title: '' },
    sheetWindows: new Map(),
  };
  const render = () => Reflect.get(App.prototype, 'renderActors').call(context, { actors, tokens: [{ actorId: actor.id }] });
  return { context, character, handle, actorsEl, render };
}

describe('compact player actor list', () => {
  it('shows portrait, prominent name, class/subclass and a separate level, without action buttons', () => {
    const f = fixture();
    f.render();
    const row = f.actorsEl.find('actor');
    expect(row.find('name').text()).toBe('Lyra');
    expect(row.find('actor-class').text()).toBe('Wizard / School of Knowledge');
    expect(row.find('actor-level').text()).toBe('LVL4');
    expect(row.all().find(e => e.tag === 'img')?.src).toBe(actor.artwork);
    expect(row.all().some(e => e.tag === 'button')).toBe(false);
    expect(row.tabIndex).toBe(0);
    expect(row.ariaLabel).toBe('Open character sheet for Lyra');
  });

  it('opens only on double-click or Enter/Space, never focusing the camera on single-click', () => {
    const f = fixture();
    f.render();
    const row = f.actorsEl.find('actor');
    row.fire('click');
    row.find('name').fire('click');
    expect(f.context.openSheet).not.toHaveBeenCalled();
    expect(f.context.view.focus).not.toHaveBeenCalled();
    row.fire('dblclick');
    expect(f.context.openSheet).toHaveBeenCalledWith(actor);
    const preventDefault = vi.fn();
    row.fire('keydown', { key: 'Enter', preventDefault });
    row.fire('keydown', { key: ' ', preventDefault });
    row.fire('keydown', { key: 'Enter', repeat: true, preventDefault });
    row.fire('keydown', { key: 'ArrowDown', preventDefault });
    expect(f.context.openSheet).toHaveBeenCalledTimes(3);
    expect(preventDefault).toHaveBeenCalledTimes(3);
  });

  it('refreshes level, class/subclass and artwork in place after sheet changes', () => {
    const f = fixture();
    f.render();
    const row = f.actorsEl.find('actor');
    f.character.level = 7;
    f.character.className = 'Bard';
    f.character.subclass = 'Wordsmith';
    f.handle.bus.artwork = '/a/new.png';
    f.context.actorRowUpdates.get(actor.id)!();
    expect(f.actorsEl.find('actor')).toBe(row);
    expect(row.find('actor-class').text()).toBe('Bard / Wordsmith');
    expect(row.find('actor-level').text()).toBe('LVL7');
    expect(row.all().find(e => e.tag === 'img')?.src).toBe('/a/new.png');
  });

  it('fills a loading row when its existing sheet handle becomes available', async () => {
    const f = fixture();
    f.context.sheetHandles.clear();
    let finish!: (handle: typeof f.handle) => void;
    const loading = new Promise<typeof f.handle>(resolve => { finish = resolve; });
    f.context.ensureSheet.mockReturnValue(loading);
    f.render();
    const row = f.actorsEl.find('actor');
    expect(row.find('actor-class').text()).toBe('Loading character...');
    expect(row.find('actor-level').text()).toBe('LVL--');
    f.context.sheetHandles.set(actor.id, f.handle);
    finish(f.handle);
    await loading;
    expect(f.actorsEl.find('actor')).toBe(row);
    expect(row.find('actor-class').text()).toBe('Wizard / School of Knowledge');
    expect(row.find('actor-level').text()).toBe('LVL4');
  });

  it('keeps disabled/testing actors readable and excludes other players', () => {
    const disabled = { ...actor, disabled: true, testingCharacter: true };
    const f = fixture([disabled, { ...actor, id: 'other', ownerId: 'other-player' }]);
    f.render();
    const rows = f.actorsEl.all().filter(e => e.className === 'actor');
    expect(rows).toHaveLength(1);
    expect(rows[0].find('actor-identity').text()).toBe('LyraDisabledTesting');
    rows[0].fire('dblclick');
    expect(f.context.openSheet).toHaveBeenCalledWith(disabled);
    expect(f.context.quickAccessButton.disabled).toBe(true);
  });

  it('enables the rail Quick Access star for the first active character', () => {
    const f = fixture([{ ...actor, id: 'off', name: 'Off', disabled: true }, actor]);
    f.render();
    expect(f.context.quickAccessButton).toEqual({ disabled: false, title: `Quick Access (${actor.name})` });
  });

  it('uses an initial for missing portraits and handles uncreated characters', () => {
    const f = fixture([{ ...actor, artwork: undefined }]);
    f.handle.bus.artwork = undefined;
    f.character.className = '';
    f.character.subclass = '';
    f.render();
    expect(f.actorsEl.find('art').text()).toBe('L');
    expect(f.actorsEl.find('actor-class').text()).toBe('Character not created');
  });

  it('shows loading failures inline and ignores completions for replaced rows', async () => {
    const f = fixture();
    f.context.sheetHandles.clear();
    f.context.ensureSheet.mockRejectedValue(new Error('Not connected'));
    f.render();
    const first = f.actorsEl.find('actor-class');
    expect(first.text()).toBe('Loading character...');
    f.render();
    await vi.waitFor(() => expect(f.actorsEl.find('actor-class').text()).toBe('Cannot load character: Not connected'));
    expect(first.text()).toBe('Loading character...');
    expect(f.actorsEl.find('actor-class').text()).toBe('Cannot load character: Not connected');
    expect(f.actorsEl.find('actor-class').role).toBe('alert');
  });
});
