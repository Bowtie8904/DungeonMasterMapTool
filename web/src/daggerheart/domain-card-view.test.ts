import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { renderSheetView, type RollRequest } from './view';

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
  children: Array<Element | string> = [];
  get childElementCount(): number { return this.children.filter(child => typeof child !== 'string').length; }
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(): void {}
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

it('disables Teleport casts on unsupported hosts and after its long-rest use is spent', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'teleport')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 5, type: 'Spell', recall: 2,
    text: entry.text ?? '', spellcast: 'Knowledge', automation: entry.automation }];
  for (const [supported, spent, disabled] of [[false, false, true], [true, false, false], [true, true, true]]) {
    c.usage = spent ? { 'card:Teleport': { used: 1, per: 'longRest' } } : {};
    const root = renderSheetView({ character: c, onRoll: () => true, supportsDomainCardAutomation: () => supported }) as unknown as Element;
    await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
    expect(root.querySelectorAll('button').find(button => button.text() === 'Spellcast roll')!.disabled).toBe(disabled);
  }
});

it('displays loadout and vault cards by ascending level without reordering the sheet data', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  const card = (name: string, level: number) => ({
    name, level, domain: 'Codex', type: 'Ability' as const, recall: 1, text: '',
  });
  c.loadout = [card('Loadout 5', 5), card('Loadout 1', 1), card('Loadout 3', 3)];
  c.vault = [card('Vault 4', 4), card('Vault 2', 2)];
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const names = root.querySelectorAll('div')
    .filter(element => element.className === 'dcard')
    .map(element => element.querySelectorAll('span').find(child => child.className === 'nm')!.text());
  expect(names).toEqual(['Loadout 1', 'Loadout 3', 'Loadout 5', 'Vault 2', 'Vault 4']);
  expect(c.loadout.map(item => item.name)).toEqual(['Loadout 5', 'Loadout 1', 'Loadout 3']);
  expect(c.vault.map(item => item.name)).toEqual(['Vault 4', 'Vault 2']);
});

it('rolls Arcane Barrage only after a successful send and charges the chosen Hope once', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-illiat')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.hope.on = 4;
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 1, type: 'Grimoire', recall: 2, text: entry.text ?? '', spells: structuredClone(entry.spells) }];
  const requests: RollRequest[] = [];
  let connected = false;
  const root = renderSheetView({ character: c, onRoll: request => { requests.push(request); return connected; } }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const barrage = root.querySelectorAll('button').find(button => button.text() === 'Arcane Barrage');
  expect(root.querySelectorAll('button').map(button => button.text()).filter(text => /Arcane|Telepathy|Recall/.test(text))).toContain('Arcane Barrage');
  await barrage!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  const input = dialog.querySelectorAll('input')[0];
  const go = dialog.querySelectorAll('button').find(button => button.text() === 'Spend Hope and roll')!;
  input.value = '5';
  await go.click();
  expect(requests).toHaveLength(0);
  expect(c.hope.on).toBe(4);
  input.value = '3';
  await go.click();
  expect(c.hope.on).toBe(4);
  expect(c.usage['card:Book of Illiat/Arcane Barrage']).toBeUndefined();
  connected = true;
  await go.click();
  expect(requests.at(-1)?.expression).toBe('3d6');
  expect(c.hope.on).toBe(1);
  expect(c.usage['card:Book of Illiat/Arcane Barrage'].used).toBe(1);
});

it('does not spend Hope or a rest use when Arcane Barrage is canceled', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-illiat')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.hope.on = 4;
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 1, type: 'Grimoire', recall: 2, text: entry.text ?? '', spells: structuredClone(entry.spells) }];
  const onRoll = vi.fn(() => true);
  const root = renderSheetView({ character: c, onRoll }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  await root.querySelectorAll('button').find(button => button.text() === 'Arcane Barrage')!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  await dialog.querySelectorAll('button').find(button => button.text() === 'Cancel')!.click();
  expect(dialog.open).toBe(false);
  expect(c.hope.on).toBe(4);
  expect(c.usage['card:Book of Illiat/Arcane Barrage']).toBeUndefined();
  expect(onRoll).not.toHaveBeenCalled();
});

it('disables Homet spells on old hosts and independently when their uses are exhausted', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-homet')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 7, type: 'Grimoire', recall: 0, text: entry.text ?? '',
    spellcast: 'Knowledge', spells: structuredClone(entry.spells) }];
  const old = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await old.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  expect(old.querySelectorAll('button').filter(button => button.text() === 'Spellcast roll').every(button => button.disabled)).toBe(true);
  c.usage['card:Book of Homet/Pass Through'] = { used: 1, per: 'rest' };
  const supported = renderSheetView({ character: c, onRoll: () => true, supportsDomainCardAutomation: () => true }) as unknown as Element;
  await supported.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const spells = supported.querySelectorAll('button').filter(button => button.text() === 'Spellcast roll');
  expect(spells).toHaveLength(2);
  expect(spells.map(button => button.disabled)).toEqual([true, false]);
});

it('allows Banish difficulty to be adjusted and cleared on the card', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'banish')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 6, type: 'Spell', recall: 0,
    text: entry.text ?? '', automation: entry.automation, banishDifficulty: 18 }];
  const onChange = vi.fn();
  const root = renderSheetView({ character: c, onRoll: () => true, onChange }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  onChange.mockClear();
  const input = root.querySelectorAll('input').find(element => element.ariaLabel === 'Banish difficulty')!;
  expect(input.value).toBe('18');
  input.value = '17';
  input.listeners.get('change')?.();
  expect(c.loadout[0].banishDifficulty).toBe(17);
  input.value = '';
  input.listeners.get('change')?.();
  expect(c.loadout[0].banishDifficulty).toBeUndefined();
  expect(onChange).toHaveBeenCalledTimes(2);
});

it('swaps Codex-Touched with the selected vault card without paying Recall', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'codex-touched')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.stress.on = 0;
  const card = (name: string, domain = 'Codex') => ({ name, domain, level: 1, type: 'Ability' as const, recall: 2, text: '' });
  c.loadout = [{ ...card(entry.name), automation: entry.automation }, card('two'), card('three'), card('four')];
  c.vault = [card('Replacement', 'Arcana')];
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  await root.querySelectorAll('button').find(button => button.text() === 'Codex-Touched: swap with vault')!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  const replacement = dialog.querySelectorAll('button').find(button => button.text() === 'Replacement')!;
  const fourth = c.loadout.pop()!;
  await replacement.click();
  expect(c.loadout[0].name).toBe('Codex-Touched');
  expect(c.usage['card:Codex-Touched:action:swap']).toBeUndefined();
  c.loadout.push(fourth);
  await replacement.click();
  expect(c.loadout.map(item => item.name)).toContain('Replacement');
  expect(c.vault.map(item => item.name)).toContain('Codex-Touched');
  expect(c.stress.on).toBe(0);
  expect(c.usage['card:Codex-Touched:action:swap']).toMatchObject({ used: 1, per: 'rest' });
});
