import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { renderSheetView, SheetBus, type RollRequest } from './view';

class Element {
  className = '';
  textContent = '';
  type = '';
  value = '';
  hidden = false;
  disabled = false;
  checked = false;
  style = {};
  classList = { toggle: vi.fn() };
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  get ownerDocument() { return document; }
  append(...nodes: Array<Element | string>) { this.children.push(...nodes); }
  replaceChildren(...nodes: Array<Element | string>) { this.children = nodes; }
  addEventListener(type: string, listener: () => unknown) { this.listeners.set(type, listener); }
  setAttribute() {}
  querySelector() { return null; }
  querySelectorAll() { return []; }
  focus() {}
  remove() {}
  get text(): string { return this.textContent + this.children.map(node => typeof node === 'string' ? node : node.text).join(''); }
  all(predicate: (node: Element) => boolean): Element[] {
    return [...(predicate(this) ? [this] : []), ...this.children.flatMap(node => typeof node === 'string' ? [] : node.all(predicate))];
  }
}
afterEach(() => vi.unstubAllGlobals());

it('keeps hidden-sheet damage/spell handlers live without building full DOM, and coalesces notifications', async () => {
  const body = new Element('body');
  const created = vi.fn((tag: string) => new Element(tag));
  vi.stubGlobal('document', { body, createElement: created, addEventListener: vi.fn(), removeEventListener: vi.fn() });
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
  vi.stubGlobal('requestAnimationFrame', vi.fn());
  const c = emptyCharacter('Hero');
  c.activeWeapons = [{ name: 'Sword', slot: 'Primary', damageType: 'phys', damage: 'd8', hands: 1, tier: 1, trait: 'Agility', range: 'Melee' }];
  const bus = new SheetBus();
  const changed = vi.fn();
  const onRoll = vi.fn((_request: RollRequest) => true);
  const view = renderSheetView({ character: c, bus, visible: () => false, onChange: changed, onRoll }) as unknown as Element;
  expect(view.children).toEqual([]);
  expect(created).toHaveBeenCalledTimes(1);
  changed.mockClear();
  bus.notify();
  bus.notify(false);
  bus.notify();
  await Promise.resolve();
  expect(changed).toHaveBeenCalledTimes(1);
  expect(bus.damage?.('Sword')).toBe(true);
  expect(body.text).toContain('Sword damage');
  const go = body.all(node => node.tag === 'button' && node.text === 'Roll to chat')[0];
  await go.listeners.get('click')?.();
  expect(onRoll).toHaveBeenCalledTimes(1);
  expect(view.children).toEqual([]);
  expect(bus.spellDamage?.('Spell', { dice: '2d6', fixed: true }, false, vi.fn())).toBe(true);
  expect(body.text).toContain('Spell damage');
});

it.each([6, 12])('includes the linked Rage Up source in a %i damage bonus', async bonus => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag), addEventListener: vi.fn(), removeEventListener: vi.fn() });
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
  vi.stubGlobal('requestAnimationFrame', vi.fn());
  const c = emptyCharacter('Hero');
  c.activeWeapons = [{ name: 'Sword', slot: 'Primary', damageType: 'phys', damage: 'd8', hands: 1, tier: 1, trait: 'Strength', range: 'Melee' }];
  const source = { name: 'Rage Up', text: 'Original Rage Up rules', source: 'Copycat - Blade - Level 6 Ability' };
  const bus = new SheetBus();
  const onRoll = vi.fn((_request: RollRequest) => true);
  renderSheetView({ character: c, bus, visible: () => false, onRoll });
  expect(bus.damage?.('Sword', false, undefined, 0, bonus, undefined, undefined, undefined, source)).toBe(true);
  await body.all(node => node.tag === 'button' && node.text === 'Roll to chat')[0].listeners.get('click')?.();
  expect(onRoll.mock.calls[0][0]).toMatchObject({
    contributions: expect.arrayContaining([{ label: 'Rage Up', expression: String(bonus), rollSource: source }]),
  });
});
