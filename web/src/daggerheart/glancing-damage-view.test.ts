import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter, type CharacterSheet, type Weapon } from './data';
import { rollBonus } from './engine';
import { openRollDialog, type RollRequest } from './view';

class Element {
  className = '';
  textContent = '';
  type = '';
  value = '';
  checked = false;
  disabled = false;
  hidden = false;
  removed = false;
  classList = { toggle: () => {} };
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  focus(): void {}
  remove(): void { this.removed = true; }
  async fire(event: string): Promise<void> { await this.listeners.get(event)?.(); }
  get text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text).join(''); }
  all(predicate: (element: Element) => boolean): Element[] {
    return [...(predicate(this) ? [this] : []), ...this.children.flatMap(child => typeof child === 'string' ? [] : child.all(predicate))];
  }
}

afterEach(() => vi.unstubAllGlobals());

function setup(submit: (request: RollRequest) => Promise<boolean>, prepare?: (c: CharacterSheet) => void) {
  const c = emptyCharacter('Fighter');
  c.proficiency.on = 5;
  c.stress = { on: 0, slots: 6, total: 6 };
  c.features = [{ name: 'Damage option', source: 'Test', text: '', automation: { rollOptions: [
    { id: 'extra', label: 'Extra damage', roll: 'damage', dice: '1d6', cost: { stress: 1 } },
  ] } }];
  prepare?.(c);
  const weapon: Weapon = { name: 'Blade', damage: 'd8+2', damageType: 'phys', slot: 'Primary', hands: 1, tier: 1, trait: 'Agility', range: 'Melee' };
  c.activeWeapons = [weapon];
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag), addEventListener: () => {}, removeEventListener: () => {} });
  const host = { ownerDocument: { body } } as unknown as HTMLElement;
  const sent = vi.fn();
  openRollDialog(host, c, 'Blade damage', [], rollBonus(c, ['damage'], weapon, false), submit, sent,
    ['damage'], weapon, () => true, undefined, false,
    { key: 'card:Glancing Blow', automation: {}, action: { id: 'use', label: 'Glancing Blow', cost: { stress: 1 } } },
    () => true, undefined, () => true, 0, () => true, () => true, undefined, () => true, true);
  return { c, body, sent, go: body.all(e => e.tag === 'button' && e.text === 'Roll to chat')[0] };
}

it('uses the normal damage options and half live proficiency rounded up, paying optional costs only after success', async () => {
  let resolve!: (sent: boolean) => void;
  const submit = vi.fn((_request: RollRequest) => new Promise<boolean>(done => { resolve = done; }));
  const { c, body, sent, go } = setup(submit);
  const option = body.all(e => e.className === 'dh-roll-option-row')[0];
  expect(option.text).toContain('Extra damage');
  const check = option.all(e => e.type === 'checkbox')[0];
  check.checked = true;
  await check.fire('change');
  expect(c.stress.on).toBe(0);
  c.proficiency.on = 7;
  const rolling = go.fire('click');
  expect(submit.mock.calls[0][0]).toMatchObject({
    expression: '4d8+2+1d6', weaponDamage: { proficiency: 4, proficiencyBonus: 0 },
  });
  expect(c.stress.on).toBe(0);
  expect(go.disabled).toBe(true);
  await go.fire('click');
  expect(submit).toHaveBeenCalledTimes(1);
  resolve(true);
  await rolling;
  expect(c.stress.on).toBe(1);
  expect(sent).toHaveBeenCalledOnce();
});

it('cancellation and rejected or disconnected submissions do not pay any optional costs', async () => {
  const submit = vi.fn(async () => false);
  const { c, body, sent, go } = setup(submit);
  const option = body.all(e => e.className === 'dh-roll-option-row')[0].all(e => e.type === 'checkbox')[0];
  option.checked = true;
  await option.fire('change');
  await go.fire('click');
  expect(c.stress.on).toBe(0);
  expect(sent).not.toHaveBeenCalled();
  submit.mockRejectedValueOnce(new Error('Disconnected'));
  await go.fire('click');
  expect(c.stress.on).toBe(0);
  expect(body.text).toContain('Disconnected');
  const canceled = setup(submit);
  await canceled.body.all(e => e.tag === 'button' && e.text === 'Cancel')[0].fire('click');
  expect(canceled.body.all(e => e.className === 'dh dh-rolldlg-backdrop')[0].removed).toBe(true);
  expect(submit).toHaveBeenCalledTimes(2);
});

it('consumes prepared damage options from the refreshed authoritative sheet rather than stale effect objects', async () => {
  let resolve!: (sent: boolean) => void;
  const { c, go } = setup(() => new Promise<boolean>(done => { resolve = done; }), c => {
    c.effects = [{ id: 'prepared', name: 'Prepared damage', kind: 'buff', until: 'manual',
      modifiers: [{ roll: 'damage', dice: '1d6', consume: true }] }];
  });
  const rolling = go.fire('click');
  c.effects = structuredClone(c.effects);
  resolve(true);
  await rolling;
  expect(c.effects).toEqual([]);
});
