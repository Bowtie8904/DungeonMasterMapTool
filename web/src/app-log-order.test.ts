import { beforeEach, expect, it, vi } from 'vitest';
import { App } from './app';
import { renderLogEntry } from './chat';

vi.mock('./chat', () => ({ renderLogEntry: vi.fn() }));

class Card {
  parentElement?: Container;
  constructor(readonly label: string) {}
  get isConnected() { return !!this.parentElement; }
  getBoundingClientRect() { return { top: 0, bottom: 30 }; }
  querySelector() { return null; }
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
    renderLog() { Reflect.get(App.prototype, 'renderLog').call(context); },
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
