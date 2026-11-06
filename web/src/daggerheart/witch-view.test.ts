import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { renderSheetView, type RollRequest } from './view';
import { chooseTalismanHope } from './witch-view';

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
  checked = false;
  disabled = false;
  hidden = false;
  open = false;
  ariaLabel = '';
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: (name: string) => this.className.split(' ').includes(name) };
  dataset: Record<string, string> = {};
  attributes: Record<string, string> = {};
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void {}
  focus(): void {}
  select(): void {}
  querySelector(): null { return null; }
  querySelectorAll(selector: string): Element[] {
    const tags = selector.split(',');
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(tags.includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function setup() {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
  const c = emptyCharacter('Witch');
  c.className = 'Witch';
  c.mancer = { done: true };
  c.traits.find(t => t.name === 'Knowledge')!.spellcast = true;
  c.traits.find(t => t.name === 'Knowledge')!.mod = 3;
  c.features = catalog.entries.filter(e => e.id === 'witch' || e.parent === 'Witch')
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: e.name })));
  return { c, body };
}

describe('Witch controls', () => {
  it('tracks Witch tokens and the lunar die only on their cards, without exposing linked pools as class resources', () => {
    const { c } = setup();
    c.effects.push({ id: 'held-talisman', name: 'Enchanted Talisman benefits', kind: 'buff', witchLinked: 'talisman',
      resources: [{ id: 'talisman-tokens:creator:activation', label: 'Talisman tokens', initial: 2 }] });
    const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
    expect(root.querySelectorAll('input').filter(i => i.ariaLabel === 'Tokens')).toHaveLength(3);
    expect(root.querySelectorAll('input').some(i => i.ariaLabel === 'Talisman tokens')).toBe(false);
    expect(root.querySelectorAll('output').filter(i => i.ariaLabel === 'Lunar die')).toHaveLength(1);
    const header = root.querySelectorAll('div').find(e => e.className === 'ident-right')!;
    expect(header.text()).not.toContain('Tokens');
    expect(header.text()).not.toContain('Talisman tokens');
    expect(header.text()).not.toContain('Lunar die');
    expect(c.resources!['talisman-tokens:creator:activation']).toBe(2);
  });

  it('sends exactly three Commune dice to chat and spends its use only after a successful send', async () => {
    const { c } = setup();
    const requests: RollRequest[] = [];
    let connected = false;
    const root = renderSheetView({ character: c, actorId: 'witch', onRoll: r => { requests.push(r); return connected; } }) as unknown as Element;
    const button = root.querySelectorAll('button').find(b => b.text() === 'Commune')!;
    await button.click();
    expect(requests[0].expression).toBe('0+3d6');
    expect(requests[0].rollSource?.text).toContain('psychically experience');
    expect(requests[0].actorId).toBe('witch');
    expect(c.usage['feat:Commune']).toBeUndefined();
    connected = true;
    await button.click();
    expect(c.usage['feat:Commune'].used).toBe(1);
    expect(root.querySelectorAll('button').find(b => b.text() === 'Commune')!.disabled).toBe(true);
  });

  it('requires an updated host for cross-actor grants without spending a use', async () => {
    const { c } = setup();
    const logs: string[] = [];
    const root = renderSheetView({ character: c, onRoll: () => false, onLog: text => logs.push(text) }) as unknown as Element;
    await root.querySelectorAll('button').find(b => b.text() === 'Circle of Power')!.click();
    expect(logs).toContain('Update the host to use Witch automation.');
    expect(c.usage['feat:Circle of Power']).toBeUndefined();
    expect(c.effects.some(e => e.witchGrant)).toBe(false);
  });

  it('keeps Hope selection and API errors inline and only submits valid whole amounts', async () => {
    const { c, body } = setup();
    c.hope.on = 4;
    const submit = vi.fn().mockRejectedValueOnce(new Error('The talisman was already imbued.')).mockResolvedValueOnce(undefined);
    chooseTalismanHope(c, submit);
    const dialog = body.children[0] as Element;
    const input = dialog.querySelectorAll('input')[0];
    const go = dialog.querySelectorAll('button').find(b => b.text() === 'Imbue talisman')!;
    for (const invalid of ['0', '1.5', '5']) {
      input.value = invalid;
      await go.click();
    }
    expect(submit).not.toHaveBeenCalled();
    input.value = '3';
    await go.click();
    expect(submit).toHaveBeenCalledWith(3);
    expect(dialog.text()).toContain('The talisman was already imbued.');
    expect(dialog.open).toBe(true);
    expect(c.hope.on).toBe(4);
    await go.click();
    expect(dialog.open).toBe(false);
  });
});
