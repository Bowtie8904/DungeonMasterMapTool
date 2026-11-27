import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { syncDerived } from './engine';
import { renderSheetView } from './view';
class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
  checked = false;
  readOnly = false;
  validationMessage = '';
  files?: File[];
  disabled = false;
  hidden = false;
  isConnected = true;
  open = false;
  ariaLabel = '';
  src = '';
  title = '';
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: (name: string) => this.className.split(' ').includes(name) };
  dataset: Record<string, string> = {};
  children: Array<Element | string> = [];
  get childElementCount(): number { return this.children.filter(child => typeof child !== 'string').length; }
  listeners = new Map<string, () => unknown>();
  attributes: Record<string, string> = {};
  constructor(readonly tag: string) {}
  get ownerDocument(): Document { return document; }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  insertBefore(child: Element, before: Element | null): void {
    const i = before ? this.children.indexOf(before) : -1;
    if (i < 0) this.append(child);
    else this.children.splice(i, 0, child);
  }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; if (name === 'aria-label') this.ariaLabel = value; }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void {}
  focus(): void {}
  select(): void {}
  setCustomValidity(message: string): void { this.validationMessage = message; }
  reportValidity(): boolean { return !this.validationMessage; }
  querySelector(_selector: string): Element | null { return null; }
  matches(): boolean { return this.attributes.role === 'tab' || ['Card view', 'List view'].includes(this.ariaLabel) || this.className === 'feature-search'; }
  querySelectorAll(selector: string): Element[] {
    const tags = selector.split(',').map(tag => tag.trim());
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(tags.includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('shows the four Summoning circles as one header widget with a level-capped total', () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  vi.stubGlobal('HTMLInputElement', Element);
  vi.stubGlobal('HTMLTextAreaElement', Element);
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
  const c = emptyCharacter('Summoner');
  c.mancer = { done: true };
  c.className = 'Summoner';
  c.subclass = 'Necromancy';
  c.level = 3;
  c.features = catalog.entries.filter(e => e.id === 'summoner' || e.id === 'summoner-necromancy-foundation')
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), text: f.text ?? '', source: `Class feature - ${e.name}` })));
  syncDerived(c);
  c.resources!['summoner-circle-1'] = 1;
  c.resources!['summoner-circle-2'] = 1;
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  const widgets = root.querySelectorAll('div').filter(d => d.className === 'dh-resource-group');
  expect(widgets).toHaveLength(1);
  const widget = widgets[0];
  expect(widget.ariaLabel).toBe('Summoning circles');
  expect(widget.querySelectorAll('span').map(s => s.text())).toEqual(['First Circle', 'Second Circle', 'Third Circle', 'Fourth Circle']);
  const inputs = widget.querySelectorAll('input');
  expect(inputs.map(i => i.value)).toEqual(['1', '1', '0', '0']);
  expect(widget.querySelectorAll('output')).toHaveLength(0);
  expect(widget.title).toBe('Summoning circles: 2 / 3');
  inputs[2].value = '5';
  inputs[2].listeners.get('change')?.();
  expect(c.resources!['summoner-circle-3']).toBe(1);
  expect(root.querySelectorAll('input').filter(i => i.ariaLabel === 'Fourth Circle')).toHaveLength(1);
});
