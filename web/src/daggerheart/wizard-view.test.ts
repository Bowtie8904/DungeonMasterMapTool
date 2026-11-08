import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { renderSheetView, SheetBus } from './view';

class Element {
  className = '';
  textContent = '';
  ariaLabel = '';
  disabled = false;
  readOnly = false;
  type = '';
  attributes: Record<string, string> = {};
  style = { cssText: '', setProperty: () => {} };
  dataset = {};
  children: Array<Element | string> = [];
  classList = {
    toggle: () => {}, add: () => {},
    contains: (name: string) => this.className.split(' ').includes(name),
  };
  listeners = new Map<string, () => void>();
  constructor(readonly tag: string) {}
  get text(): string { return this.textContent + this.children.map(c => typeof c === 'string' ? c : c.text).join(''); }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; if (name === 'aria-label') this.ariaLabel = value; }
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  querySelector(): null { return null; }
  querySelectorAll(selector: string): Element[] {
    if (selector === 'input, textarea, select, button') return this.all().slice(1).filter(e => ['input', 'textarea', 'select', 'button'].includes(e.tag));
    return [];
  }
  matches(): boolean { return this.attributes.role === 'tab' || this.className.split(' ').includes('description-toggle'); }
  focus(): void {}
  all(): Element[] { return [this, ...this.children.flatMap(c => typeof c === 'string' ? [] : c.all())]; }
}

afterEach(() => vi.unstubAllGlobals());

it('refreshes Conjure Shield Evasion and tooltip sources when manual Hope crosses 2 in either direction', () => {
  const created: Element[] = [];
  vi.stubGlobal('document', {
    createElement: (tag: string) => { const element = new Element(tag); created.push(element); return element; },
    createElementNS: (_namespace: string, tag: string) => new Element(tag),
    addEventListener: () => {},
  });

  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
  const shield = catalog.entries.find(e => e.id === 'wizard-school-of-war-specialization')!.features!
    .find(f => f.name === 'Conjure Shield')!;
  const c = emptyCharacter('Wizard');
  c.className = 'Wizard';
  c.mancer = { done: true };
  c.evasion = 11;
  c.proficiency.on = 3;
  c.hope.on = 2;
  c.features = [{ ...structuredClone(shield), source: 'Subclass specialization - School of War' }];
  const onChange = vi.fn();
  const miniStrips: Element[] = [];
  const root = renderSheetView({ character: c, onRoll: () => false, onChange,
    onMiniStrip: strip => { if (strip) miniStrips.push(strip as unknown as Element); } }) as unknown as Element;
  const evasion = () => root.all().find(e => e.classList.contains('v-e'))!;
  const clickHope = (n: number) => root.all().find(e => e.ariaLabel === `hope ${n}`)!.listeners.get('click')!();
  const tooltipSources = () => created.filter(e => e.className === 'stat-tooltip').find(e => e.text.includes('Total'))!.text;
  expect(evasion().text).toBe('Evasion14');
  expect(tooltipSources()).toContain('Conjure Shield');
  created.length = 0;
  clickHope(2);
  expect(c.hope.on).toBe(1);
  expect(evasion().text).toBe('Evasion11');
  expect(miniStrips.at(-1)?.text).toContain('Evasion11');
  expect(onChange).toHaveBeenCalledTimes(2);
  expect(tooltipSources()).not.toContain('Conjure Shield');
  created.length = 0;
  clickHope(2);
  expect(c.hope.on).toBe(2);
  expect(evasion().text).toBe('Evasion14');
  expect(miniStrips.at(-1)?.text).toContain('Evasion14');
  expect(onChange).toHaveBeenCalledTimes(3);
  expect(tooltipSources()).toContain('Conjure Shield');
});

it('updates sheet controls and minimized trait rolls when read-only is toggled', () => {
  class Input extends Element {}
  class Textarea extends Element {}
  vi.stubGlobal('HTMLInputElement', Input);
  vi.stubGlobal('HTMLTextAreaElement', Textarea);
  vi.stubGlobal('document', {
    createElement: (tag: string) => tag === 'input' ? new Input(tag) : tag === 'textarea' ? new Textarea(tag) : new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag),
    addEventListener: () => {},
  });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Character');
  c.mancer = { done: true };
  const bus = new SheetBus();
  const onChange = vi.fn();
  const strips: Element[] = [];
  let disabled = false;
  const root = renderSheetView({ character: c, bus, readOnly: () => disabled, onRoll: () => false, onChange,
    onMiniStrip: strip => { if (strip) strips.push(strip as unknown as Element); } }) as unknown as Element;
  const miniButtons = () => strips.at(-1)!.all().filter(e => e.className === 'mini-trait');
  const name = () => root.all().find(e => e.tag === 'p' && e.className === 'name')!;
  expect(name().text).not.toContain('Disabled');
  expect(miniButtons().every(button => !button.disabled)).toBe(true);
  disabled = true;
  bus.notify();
  expect(name().text).toContain('Disabled - read-only');
  expect(root.all().some(e => e.className === 'sheet-error')).toBe(false);
  expect(miniButtons().every(button => button.disabled)).toBe(true);
  const controls = root.querySelectorAll('input, textarea, select, button');
  expect(controls.filter(control => control.attributes.role === 'tab').every(control => !control.disabled)).toBe(true);
  expect(controls.filter(control => control.tag === 'button' && control.attributes.role !== 'tab').every(control => control.disabled)).toBe(true);
  root.listeners.get('click')!();
  root.listeners.get('input')!();
  root.listeners.get('change')!();
  expect(onChange).toHaveBeenCalledTimes(1);
  disabled = false;
  bus.notify();
  expect(name().text).not.toContain('Disabled');
  expect(miniButtons().every(button => !button.disabled)).toBe(true);
  expect(onChange).toHaveBeenCalledTimes(2);
  root.listeners.get('click')!();
  expect(onChange).toHaveBeenCalledTimes(3);
});
