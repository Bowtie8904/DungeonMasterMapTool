import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { derivedStats } from './engine';
import { chooseSpecialCard } from './special-card-view';
import { migrateVitality } from './vitality';

class Element {
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  disabled = false;
  checked = false;
  value = '';
  max = '';
  textContent = '';
  style = { cssText: '' };
  constructor(readonly tag: string) {}
  get valueAsNumber(): number { return Number(this.value); }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  addEventListener(event: string, fn: () => unknown): void { this.listeners.set(event, fn); }
  showModal(): void {}
  close(): void { this.listeners.get('close')?.(); }
  remove(): void {}
  all(tag: string): Element[] { return this.children.flatMap(child => typeof child === 'string' ? [] : [...(child.tag === tag ? [child] : []), ...child.all(tag)]); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
function setup(): Element {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
  vi.stubGlobal('window', globalThis);
  return body;
}

it('requires two distinct permanent benefits and cancellation never submits', async () => {
  const body = setup();
  const submit = vi.fn(async () => {});
  const c = emptyCharacter('Hero');
  c.testingCharacter = true;
  chooseSpecialCard(c, { id: 'choose', label: 'Vitality', specialAction: 'vitality' }, () => 0, submit);
  const boxes = body.all('input'), buttons = body.all('button');
  expect(buttons[1].disabled).toBe(true);
  for (const box of boxes.slice(0, 2)) { box.checked = true; box.listeners.get('change')?.(); }
  expect(buttons[1].disabled).toBe(false);
  await buttons[0].click();
  expect(submit).not.toHaveBeenCalled();
  await buttons[1].click();
  expect(submit).toHaveBeenCalledWith({ choices: ['stress', 'hp'] });
});

it('validates whole stolen Fear against the live pool and submits no local roll or cost', async () => {
  vi.useFakeTimers();
  const body = setup();
  const submit = vi.fn(async () => {});
  let fear = 5;
  const c = emptyCharacter('Hero');
  const before = structuredClone(c);
  chooseSpecialCard(c, { id: 'use', label: 'Night Terror', specialAction: 'nightTerror' }, () => fear, submit);
  const input = body.all('input')[0], go = body.all('button')[1];
  input.value = '2.5';
  await go.click();
  expect(submit).not.toHaveBeenCalled();
  input.value = '5';
  fear = 3;
  await vi.advanceTimersByTimeAsync(250);
  expect(input.max).toBe('3');
  await go.click();
  expect(submit).not.toHaveBeenCalled();
  input.value = '3';
  await go.click();
  expect(submit).toHaveBeenCalledWith({ amount: 3 });
  expect(c).toEqual(before);
});

it('applies the permanent threshold benefit independently of the card being active', () => {
  const c = emptyCharacter('Hero');
  const before = derivedStats(c);
  c.vitalityBenefits = ['hp', 'thresholds'];
  migrateVitality(c);
  expect(derivedStats(c).major).toBe(before.major + 2);
  expect(derivedStats(c).severe).toBe(before.severe + 2);
});
