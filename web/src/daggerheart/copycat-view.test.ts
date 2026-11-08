import { afterEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { chooseCopycatCard, openCopycat } from './copycat-view';
import * as api from '../api';
import { emptyCharacter, type DomainCard } from './data';
import { prepareBorrowedCard } from './copycat';
import { renderSheetView } from './view';

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
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
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(selector.split(',').includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

function dom(): Element {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  return body;
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const card = (name: string, level: number): DomainCard => ({ name, level, domain: 'Grace', type: 'Ability', recall: 1, text: '' });

it('filters cards case-insensitively and restores all cards when search is cleared', async () => {
  const css = readFileSync(new URL('./sheet.css', import.meta.url), 'utf8');
  expect(css).toMatch(/\.dh-copycat-dialog\s+\.dh-copycat-list\s+\[hidden\]\s*\{\s*display:\s*none\s*!important;\s*\}/);
  const body = dom();
  const c = emptyCharacter('Caster');
  const closed = chooseCopycatCard(c, [
    { actorId: 'one', actorName: 'Mage', card: { ...card('Fire spell', 2), domain: 'Codex' } },
    { actorId: 'two', actorName: 'Bard', card: card('Song', 3) },
  ], vi.fn(async () => {}));
  const dialog = body.querySelectorAll('dialog')[0];
  const input = dialog.querySelectorAll('input')[0];
  const rows = dialog.querySelectorAll('div').filter(node => node.className === 'dcard');
  input.value = 'CODEX fire';
  input.listeners.get('input')?.();
  expect(rows.map(row => row.hidden)).toEqual([false, true]);
  input.value = 'missing';
  input.listeners.get('input')?.();
  expect(rows.every(row => row.hidden)).toBe(true);
  expect(dialog.querySelectorAll('p').find(node => node.text() === 'No cards match your search.')!.hidden).toBe(false);
  input.value = '';
  input.listeners.get('input')?.();
  expect(rows.every(row => !row.hidden)).toBe(true);
  await dialog.querySelectorAll('button').find(button => button.text() === 'Cancel')!.click();
  await closed;
});

it('flushes before listing and submits the chosen source through the host before refreshing', async () => {
  const body = dom();
  const c = emptyCharacter('Caster');
  c.hope.on = 3;
  const fetch = vi.spyOn(api, 'hostFetch').mockResolvedValue(new Response(JSON.stringify({
    cards: [{ actorId: 'other', actorName: 'Other', card: card('Borrowed', 3) }],
  })));
  const submit = vi.spyOn(api, 'useFeature').mockResolvedValue(undefined);
  const save = { flush: vi.fn(async () => {}), refresh: vi.fn(async () => {}) };
  const closed = openCopycat(c, 'caster', 'card:Copycat', 'use', save);
  await vi.waitFor(() => expect(body.querySelectorAll('dialog')).toHaveLength(1));
  expect(save.flush).toHaveBeenCalledOnce();
  expect(fetch).toHaveBeenCalledWith('/api/copycat/caster', { credentials: 'same-origin' });
  await body.querySelectorAll('dialog')[0].querySelectorAll('button').find(button => button.text() === 'Copy (2 Hope)')!.click();
  await closed;
  expect(submit).toHaveBeenCalledWith({ actorId: 'caster', key: 'card:Copycat', actionId: 'use',
    copyActorId: 'other', copyCardName: 'Borrowed' });
  expect(save.flush).toHaveBeenCalledTimes(2);
  expect(save.refresh).toHaveBeenCalledOnce();
});

it('disables unaffordable copies and leaves Hope untouched when submission fails or is canceled', async () => {
  const body = dom();
  const c = emptyCharacter('Caster');
  c.hope.on = 2;
  const submit = vi.fn(async () => { throw new Error('Source card was vaulted.'); });
  const closed = chooseCopycatCard(c, [card('One', 1), card('Five', 5)].map(card => ({ actorId: 'other', actorName: 'Other', card })), submit);
  const dialog = body.querySelectorAll('dialog')[0];
  expect(dialog.className).toContain('dh-copycat-dialog');
  const list = dialog.children.find(child => child instanceof Element && child.className.includes('dh-copycat-list'));
  const footer = dialog.children.at(-1) as Element;
  expect(list).toBeDefined();
  expect(footer.className).toBe('acts');
  expect(footer.text()).toBe('Cancel');
  const buttons = dialog.querySelectorAll('button');
  expect(buttons.find(button => button.text() === 'Copy (3 Hope)')?.disabled).toBe(true);
  await buttons.find(button => button.text() === 'Copy (1 Hope)')!.click();
  expect(submit).toHaveBeenCalledOnce();
  expect(dialog.text()).toContain('Source card was vaulted.');
  expect(dialog.open).toBe(true);
  expect(c.hope.on).toBe(2);
  await buttons.find(button => button.text() === 'Cancel')!.click();
  await closed;
  expect(c.hope.on).toBe(2);
});

it('renders copied spell actions and quantity damage inside Copycat, without a recall/vault control or loadout slot', async () => {
  const body = dom();
  const copied: DomainCard = { ...card('Borrowed Book', 1), type: 'Grimoire', spells: [{
    name: 'Borrowed spell', text: '', automation: { actions: [{ id: 'clear', label: 'Borrowed healing', steps: [{ op: 'clear', resource: 'stress', amount: 1 }] }] },
  }, { name: 'Barrage', text: '', automation: { actions: [{ id: 'barrage', label: 'Copied barrage', hopeDice: { sides: 6, type: 'magic' } }] } }] };
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.stress.on = 2;
  c.hope.on = 3;
  c.loadout = [{ ...card('Copycat', 8), borrowedCard: { sourceActorId: 'other', sourceActorName: 'Other', card: prepareBorrowedCard(copied) } }];
  let connected = false;
  const root = renderSheetView({ character: c, onRoll: () => connected }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  expect(root.text()).toContain('Copied from Other');
  expect(root.text()).toContain('Borrowed Book');
  const buttons = root.querySelectorAll('button');
  expect(buttons.filter(button => button.text() === 'Move to vault')).toHaveLength(1);
  expect(buttons.some(button => button.text().startsWith('Recall'))).toBe(false);
  await buttons.find(button => button.text() === 'Borrowed healing')!.click();
  expect(c.stress.on).toBe(1);
  expect(c.loadout).toHaveLength(1);
  await root.querySelectorAll('button').find(button => button.text() === 'Copied barrage')!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  const go = dialog.querySelectorAll('button').find(button => button.text() === 'Spend Hope and roll')!;
  await go.click();
  expect(c.hope.on).toBe(3);
  connected = true;
  await go.click();
  expect(c.hope.on).toBe(2);
});
