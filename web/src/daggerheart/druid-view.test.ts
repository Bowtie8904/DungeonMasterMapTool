import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { chooseBeastform } from './druid-view';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';

class Element {
  ariaLabel = '';
  value = '';
  textContent = '';
  checked = false;
  children: Array<Element | string> = [];
  listeners = new Map<string, () => void>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void {
    this.children.push(...children);
    if (this.tag === 'select' && !this.value) this.value = (this.children[0] as Element)?.value ?? '';
  }
  replaceChildren(...children: Array<Element | string>): void { this.children = []; this.append(...children); }
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  showModal(): void {}
  close(): void { this.listeners.get('close')?.(); }
  remove(): void {}
  find(label: string): Element | undefined {
    if (this.ariaLabel === label) return this;
    return this.children.flatMap(child => typeof child === 'string' ? [] : child.find(label) ?? []).at(0);
  }
  text(): string { return this.textContent + this.children.map(x => typeof x === 'string' ? x : x.text()).join(''); }
}

afterEach(() => vi.unstubAllGlobals());

describe('Beastform picker', () => {
  const setup = (level: number, evolution = false) => {
    const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
    const c = emptyCharacter('Druid');
    c.level = level;
    c.features = catalog.entries.find(e => e.id === 'druid')!.features!.map(f => ({ ...structuredClone(f), source: 'Class feature - Druid' }));
    const body = new Element('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
    const submit = vi.fn();
    chooseBeastform(c, evolution, submit);
    return { c, submit, dialog: body.children[0] as Element };
  };
  it('only offers eligible categories and cancellation does not spend anything', () => {
    const { c, submit, dialog } = setup(1);
    const select = dialog.find('Beastform category')!;
    expect(select.children).toHaveLength(6);
    expect(select.text()).not.toContain('Powerful Beast');
    const before = JSON.stringify(c);
    dialog.listeners.get('close')?.();
    expect(submit).not.toHaveBeenCalled();
    expect(JSON.stringify(c)).toBe(before);
  });
  it('collects an explicit Evolution trait and evolved base category', () => {
    const { submit, dialog } = setup(8, true);
    const category = dialog.find('Beastform category')!;
    category.value = 'Mythic Beast'; category.listeners.get('change')!();
    const base = dialog.find('Base category')!;
    expect(base.children).toHaveLength(12);
    base.value = 'Powerful Beast';
    dialog.find('Evolution trait')!.value = 'Knowledge';
    const go = dialog.children.find(x => x instanceof Element && x.tag === 'div' && x.text().includes('Transform')) as Element;
    (go.children[1] as Element).listeners.get('click')!();
    expect(submit).toHaveBeenCalledWith({ form: 'Mythic Beast', base: 'Powerful Beast', trait: 'Knowledge' });
  });
  it('excludes composite categories from Mythic Hybrid sources', () => {
    const { dialog } = setup(8);
    const category = dialog.find('Beastform category')!;
    category.value = 'Mythic Hybrid'; category.listeners.get('change')!();
    const details = dialog.children[2] as Element;
    expect(details.text()).toContain('Choose 3 categories');
    expect(details.text()).not.toContain('Legendary Beast');
    expect(details.text()).not.toContain('Legendary Hybrid');
  });
});
