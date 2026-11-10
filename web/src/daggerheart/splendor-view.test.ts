import { afterEach, expect, it, vi } from 'vitest';
import { chooseSplendorAction, fetchSplendorCandidates, healingBudget, openSplendorAction } from './splendor-view';
import { emptyCharacter } from './data';
import type { Actor } from '../protocol';
import * as api from '../api';

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  max = '';
  disabled = false;
  hidden = false;
  open = false;
  ariaLabel = '';
  get valueAsNumber(): number { return Number(this.value); }
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  attributes = new Map<string, string>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  prepend(...children: Array<Element | string>): void { this.children.unshift(...children); }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes.set(name, value); }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void {}
  all(tag: string): Element[] {
    return this.children.flatMap(child => typeof child === 'string' ? [] : [...(child.tag === tag ? [child] : []), ...child.all(tag)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

function dom(): Element {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
  return body;
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const actors = [{ id: 'self', name: 'Caster', ownerId: 'player' }, { id: 'ally', name: 'Ally', ownerId: 'other' }] as Actor[];
const context = { source: 'attack,action', hope: 5, fear: 5, total: 10 };

it('opens only target selection after choosing a Healing Hands chat benefit', async () => {
  const body = dom();
  const submit = vi.fn(async () => {});
  const closed = chooseSplendorAction(emptyCharacter('Caster'), 'self',
    { id: 'hp-success', label: 'Healing Hands: clear 2 HP', splendorAction: 'healingHands' }, actors, submit, context);
  const dialog = body.all('dialog')[0];
  expect(dialog.all('select')).toHaveLength(1);
  expect(dialog.all('option').map(option => option.value)).toEqual(['ally']);
  await dialog.all('button').find(button => button.text() === 'Apply')!.click();
  await closed;
  expect(submit).toHaveBeenCalledWith({ targetActorId: 'ally' });
});

it.each([
  [context, 2],
  [{ ...context, hope: 8, fear: 2, outcome: 'hope' as const }, 2],
  [{ ...context, hope: 2, fear: 8, outcome: 'fear' as const }, 1],
  [undefined, 1],
])('uses one Second Wind benefit picker, defaulting Hope/critical target to self', async (roll, selects) => {
  const body = dom();
  const submit = vi.fn(async () => {});
  const closed = chooseSplendorAction(emptyCharacter('Caster'), 'self',
    { id: 'use', label: 'Second Wind', splendorAction: 'secondWind' }, actors, submit, roll);
  const dialog = body.all('dialog')[0];
  expect(dialog.all('select')).toHaveLength(selects);
  dialog.all('select')[0].value = 'stress';
  if (selects === 2) expect(dialog.all('select')[1].value).toBe('self');
  await dialog.all('button').find(button => button.text() === 'Apply')!.click();
  await closed;
  expect(submit).toHaveBeenCalledWith({ choiceId: 'stress', targetActorId: 'self' });
});

it('allocates Salvation Beam with linked sliders and a live cost capped to free Stress slots', async () => {
  const body = dom();
  const c = emptyCharacter('Caster');
  c.stress = { on: 4, slots: 6, total: 6 };
  expect(healingBudget(c)).toBe(2);
  const submit = vi.fn(async () => {});
  const closed = chooseSplendorAction(c, 'self', { id: 'heal', label: 'Salvation Beam', splendorAction: 'salvation' }, actors, submit, undefined, {
    allies: [{ actorId: 'self', actorName: 'Caster', hp: 3 }, { actorId: 'ally', actorName: 'Ally', hp: 4 },
      { actorId: 'two', actorName: 'Second', hp: 1 }], features: [],
  });
  const dialog = body.all('dialog')[0];
  const inputs = dialog.all('input');
  const apply = dialog.all('button').find(button => button.text() === 'Mark Stress and heal')!;
  expect(inputs).toHaveLength(4);
  expect(apply.disabled).toBe(true);
  inputs[1].value = '1';
  inputs[1].listeners.get('input')?.();
  expect(inputs[0].value).toBe('1');
  expect(dialog.text()).toContain('Stress cost: 1 / 2 available slots');
  inputs[2].value = '2';
  inputs[2].listeners.get('input')?.();
  expect(apply.disabled).toBe(true);
  inputs[2].value = '1';
  inputs[2].listeners.get('input')?.();
  expect(apply.disabled).toBe(false);
  expect(dialog.text()).toContain('Stress cost: 2 / 2 available slots');
  await apply.click();
  await closed;
  expect(submit).toHaveBeenCalledWith({ allocations: [{ actorId: 'ally', amount: 1 }, { actorId: 'two', amount: 1 }] });
});

it('searches feature and character names together and submits exactly one exhausted use', async () => {
  const body = dom();
  const submit = vi.fn(async () => {});
  const closed = chooseSplendorAction(emptyCharacter('Caster'), 'self', { id: 'restore', label: 'Invigoration', splendorAction: 'invigoration' },
    actors, submit, undefined, { allies: [], features: [
      { actorId: 'ally', actorName: 'Ally', key: 'card:Second Wind', name: 'Second Wind', text: 'Recover HP.', used: 1, max: 1, per: 'rest' },
      { actorId: 'self', actorName: 'Caster', key: 'feat:Rally', name: 'Rally', text: 'Grant a die.', used: 1, max: 1, per: 'session' },
    ] });
  const dialog = body.all('dialog')[0];
  expect(dialog.all('input')).toHaveLength(1);
  const search = dialog.all('input')[0];
  search.value = 'ALLY WIND';
  search.listeners.get('input')?.();
  const rows = dialog.all('div').filter(node => node.className === 'dcard');
  expect(rows.map(row => row.hidden)).toEqual([false, true]);
  search.value = 'missing';
  search.listeners.get('input')?.();
  expect(rows.every(row => row.hidden)).toBe(true);
  search.value = '';
  search.listeners.get('input')?.();
  expect(rows.every(row => !row.hidden)).toBe(true);
  await rows[0].all('button')[0].click();
  await dialog.all('button').find(button => button.text() === 'Restore one use')!.click();
  await closed;
  expect(submit).toHaveBeenCalledWith({ restoreActorId: 'ally', restoreKey: 'card:Second Wind' });
});

it('preserves the dialog and resources after a rejected Hope spend', async () => {
  const body = dom();
  const c = emptyCharacter('Caster');
  c.hope.on = 3;
  const submit = vi.fn(async () => { throw new Error('Hope changed; reload.'); });
  const closed = chooseSplendorAction(c, 'self', { id: 'damage', label: 'Stunning Sunlight', splendorAction: 'sunlight' }, actors, submit);
  const dialog = body.all('dialog')[0];
  const amount = dialog.all('input')[0];
  const apply = dialog.all('button').find(button => button.text() === 'Spend Hope and roll')!;
  amount.value = '4';
  amount.listeners.get('input')?.();
  expect(apply.disabled).toBe(true);
  amount.value = '3';
  amount.listeners.get('input')?.();
  await apply.click();
  expect(submit).toHaveBeenCalledWith({ amount: 3 });
  expect(dialog.open).toBe(true);
  expect(dialog.text()).toContain('Hope changed; reload.');
  expect(c.hope.on).toBe(3);
  await dialog.all('button').find(button => button.text() === 'Cancel')!.click();
  await closed;
});

it('loads sanitized candidates and surfaces host errors', async () => {
  const fetch = vi.spyOn(api, 'hostFetch').mockResolvedValue(new Response(JSON.stringify({ allies: [], features: [] })));
  expect(await fetchSplendorCandidates('caster')).toEqual({ allies: [], features: [] });
  expect(fetch).toHaveBeenCalledWith('/api/splendor/caster', { credentials: 'same-origin' });
  fetch.mockResolvedValue(new Response(JSON.stringify({ error: 'Actor disabled' }), { status: 400 }));
  await expect(fetchSplendorCandidates('caster')).rejects.toThrow('Actor disabled');
});

it.each(['zone', 'resurrection'] as const)('executes %s directly without a redundant confirmation', async kind => {
  const submit = vi.fn(async () => {});
  await openSplendorAction(emptyCharacter('Caster'), 'self', { id: 'use', label: kind, splendorAction: kind }, actors, submit);
  expect(submit).toHaveBeenCalledWith({});
});
