import { afterEach, expect, it, vi } from 'vitest';
import { mockCharacter } from './data';
import { openDamageDialog } from './damage-view';

class Element {
  className = '';
  textContent = '';
  type = '';
  value = '';
  checked = false;
  disabled = false;
  classList = { toggle: () => {} };
  children: Array<Element | string> = [];
  listeners = new Map<string, () => void>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  focus(): void {}
  remove(): void {}
  fire(event: string): void { this.listeners.get(event)?.(); }
  get text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text).join(''); }
  all(predicate: (element: Element) => boolean): Element[] {
    return [...(predicate(this) ? [this] : []), ...this.children.flatMap(child => typeof child === 'string' ? [] : child.all(predicate))];
  }
}

afterEach(() => vi.unstubAllGlobals());

it('groups mitigation and Armor Slot counts beside independent checkboxes, and aligns recovery dropdowns', () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag),
    addEventListener: () => {}, removeEventListener: () => {} });
  const c = mockCharacter();
  c.heritage = []; c.loadout = []; c.inventory = []; c.activeWeapons = [];
  c.activeArmor = undefined;
  c.armorTrack.on = 0;
  c.armorTrack.slots = 3;
  c.hope.on = 5;
  c.features = [{ source: 'Test', name: 'Protection', text: '', automation: { mitigation: [
    { id: 'armor', label: 'Flexible armor', defaultOn: true, armorSlots: 'any' },
    { id: 'tokens', label: 'Spend protection', defaultOn: true, quantity: true, cost: { hope: 1 }, reduce: 1 },
    { id: 'recovery', label: 'Recover', passive: true, recoveryChoice: true },
  ] } }];
  const root = new Element('root');
  const done = vi.fn();
  openDamageDialog(root as unknown as HTMLElement, c, done, { damage: 1, type: 'physical' });
  const rows = () => root.all(element => element.className === 'dh-roll-option-row');
  const armor = rows().find(row => row.text.includes('Mark Armor'))!;
  armor.all(element => element.type === 'checkbox')[0].checked = true;
  armor.all(element => element.type === 'checkbox')[0].fire('change');
  const controls = root.all(element => element.className === 'dh-roll-option-control');
  expect(controls.map(control => control.text)).toEqual(['Armor Slots', 'How many to spend']);
  for (const row of rows().filter(row => row.all(element => element.type === 'number').length)) {
    const labels = row.all(element => element.tag === 'label');
    expect(labels).toHaveLength(2);
    expect(labels[0].all(element => element.tag === 'input').map(input => input.type)).toEqual(['checkbox']);
    expect(labels[1].all(element => element.tag === 'input').map(input => input.type)).toEqual(['number']);
  }
  const quantity = controls[1].all(element => element.type === 'number')[0];
  quantity.value = '2';
  quantity.fire('change');
  expect(rows().find(row => row.text.includes('Spend protection'))!.all(element => element.type === 'checkbox')[0].checked).toBe(true);
  expect(c.hope.on).toBe(5);
  expect(done).not.toHaveBeenCalled();
  const recovery = root.all(element => element.className === 'dh-roll-field')[0];
  expect(recovery.text).toContain('Not Done Yet');
  expect(recovery.all(element => element.tag === 'select')).toHaveLength(1);
});
