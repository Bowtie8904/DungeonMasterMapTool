import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { chooseStance } from './brawler-view';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';

class Element {
  className = '';
  ariaLabel = '';
  hidden = false;
  disabled = false;
  value = '';
  children: Array<Element | string> = [];
  listeners = new Map<string, () => void>();
  classList = {
    toggle: (name: string, enabled: boolean) => {
      const classes = new Set(this.className.split(' ').filter(Boolean));
      if (enabled) classes.add(name); else classes.delete(name);
      this.className = [...classes].join(' ');
    },
    contains: (name: string) => this.className.split(' ').includes(name),
  };
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Element[]): void {
    this.children = children;
    if (this.tag === 'select') this.value = children[0]?.value ?? '';
  }
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  showModal(): void {}
  close(): void { this.listeners.get('close')?.(); }
  remove(): void {}
  find(label: string): Element | undefined {
    if (this.ariaLabel === label) return this;
    return this.children.flatMap(child => typeof child === 'string' ? [] : child.find(label) ?? []).at(0);
  }
}

afterEach(() => vi.unstubAllGlobals());

describe('martial stance dialog', () => {
  it('hides the trait field with a CSS-safe class unless Favored is selected', () => {
    const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
    const c = emptyCharacter('Martial Artist');
    c.testingCharacter = true;
    c.features = catalog.entries.find(e => e.id === 'brawler-martial-artist-foundation')!.features!
      .map(f => ({ ...structuredClone(f), source: 'Subclass foundation - Martial Artist' }));
    c.knownStances = ['Favored', 'Reliable'];
    const body = new Element('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
    const submit = vi.fn();
    chooseStance(c, submit, vi.fn());
    const dialog = body.children[0] as Element;
    const select = dialog.find('Known stance')!;
    const label = dialog.children.find(child => child instanceof Element && child.tag === 'label') as Element;
    expect(label.hidden).toBe(false);
    expect(label.classList.contains('hidden')).toBe(false);
    select.value = 'Reliable';
    select.listeners.get('change')!();
    expect(label.hidden).toBe(true);
    expect(label.classList.contains('hidden')).toBe(true);
    select.value = 'Favored';
    select.listeners.get('change')!();
    expect(label.hidden).toBe(false);
    expect(label.classList.contains('hidden')).toBe(false);
  });
});
