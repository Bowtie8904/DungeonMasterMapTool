import { beforeEach, expect, it, vi } from 'vitest';
import { App } from './app';
import { renderLogEntry } from './chat';

vi.mock('./chat', () => ({ renderLogEntry: vi.fn() }));
vi.mock('./chat-action-reports', () => ({ ChatActionReports: class { reset() {} } }));

class Card {
  dataset: Record<string, string> = {};
  parentElement?: Container;
  details: Record<string, { open: boolean }> = {};
  constructor(readonly label: string) {}
  get isConnected() { return !!this.parentElement; }
  getBoundingClientRect() { return { top: 0, bottom: 30 }; }
  querySelector(selector: string) { return this.details[selector] ?? null; }
  remove() {
    if (this.parentElement) this.parentElement.children.splice(this.parentElement.children.indexOf(this), 1);
    this.parentElement = undefined;
  }
}
class Container {
  children: Card[] = [];
  insertBefore(card: Card, before: Card | null) {
    card.remove();
    const index = before ? this.children.indexOf(before) : this.children.length;
    this.children.splice(index, 0, card);
    card.parentElement = this;
  }
}
beforeEach(() => vi.clearAllMocks());

it.each(['rollUpdate', 'chatUpdate'] as const)('keeps %s in its original DOM position without duplicate log entries', type => {
  const kind = type === 'rollUpdate' ? 'roll' : 'chat';
  const log = [1, 2, 3].map(id => ({ kind, m: { id, ts: id, text: `Original ${id}`, total: id } }));
  const middle = log[1];
  const cards = log.map(entry => new Card(String(entry.m.id)));
  const content = new Container();
  cards.forEach(card => content.insertBefore(card, null));
  const cache = new WeakMap<object, Card>(log.map((entry, index) => [entry, cards[index]]));
  const replacement = new Card('updated 2');
  vi.mocked(renderLogEntry).mockReturnValue(replacement as unknown as HTMLElement);
  const context = {
    log, logCards: cache, logContent: content, playerId: 'player',
    logEl: { scrollTop: 17, getBoundingClientRect: () => ({ top: 0 }) },
    store: { applyRollUpdate: vi.fn() }, followChat: false, section: 'chat', chatScrollTop: 17,
    captureChatScroll: vi.fn(), restoreChatScroll: vi.fn(),
    renderLog(entry?: object) { Reflect.get(App.prototype, 'renderLog').call(context, entry); },
  };
  Reflect.get(App.prototype, 'handle').call(context, type === 'rollUpdate'
    ? { type, id: 2, ts: 2, total: 99 }
    : { type, message: { id: 2, ts: 2, text: 'Updated' } });
  expect(log).toHaveLength(3);
  expect(log[1]).toBe(middle);
  expect(log.map(entry => entry.m.id)).toEqual([1, 2, 3]);
  expect(content.children.map(card => card.label)).toEqual(['1', 'updated 2', '3']);
  expect(context.chatScrollTop).toBe(17);
  expect(context.restoreChatScroll).toHaveBeenCalledOnce();
});

it.each(['rollUpdate', 'chatUpdate'] as const)('preserves independent disclosure states across repeated %s messages', type => {
  const kind = type === 'rollUpdate' ? 'roll' : 'chat';
  const entry = { kind, m: { id: 2, ts: 2, total: 1 } };
  const original = new Card('original');
  original.details = { 'details.roll-breakdown': { open: true }, 'details.roll-source': { open: false } };
  const content = new Container();
  content.insertBefore(original, null);
  const cache = new WeakMap<object, Card>([[entry, original]]);
  const replacement = () => {
    const card = new Card('updated');
    card.details = { 'details.roll-breakdown': { open: false }, 'details.roll-source': { open: false } };
    return card;
  };
  vi.mocked(renderLogEntry).mockImplementation(() => replacement() as unknown as HTMLElement);
  const context = {
    log: [entry], logCards: cache, logContent: content, playerId: 'player',
    logEl: { scrollTop: 0, getBoundingClientRect: () => ({ top: 0 }) },
    store: { applyRollUpdate: vi.fn() }, followChat: false, section: 'chat', chatScrollTop: 0,
    captureChatScroll: vi.fn(), restoreChatScroll: vi.fn(),
    renderLog(updated?: object) { Reflect.get(App.prototype, 'renderLog').call(context, updated); },
  };
  const update = () => Reflect.get(App.prototype, 'handle').call(context, type === 'rollUpdate'
    ? { type, id: 2, ts: 2, total: 99 }
    : { type, message: { id: 2, ts: 2, text: 'Updated' } });
  update();
  expect(content.children[0].details['details.roll-breakdown'].open).toBe(true);
  expect(content.children[0].details['details.roll-source'].open).toBe(false);
  content.children[0].details['details.roll-breakdown'].open = false;
  content.children[0].details['details.roll-source'].open = true;
  update();
  expect(content.children[0].details['details.roll-breakdown'].open).toBe(false);
  expect(content.children[0].details['details.roll-source'].open).toBe(true);
});
