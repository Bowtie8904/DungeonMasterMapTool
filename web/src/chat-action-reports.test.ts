import { afterEach, expect, it, vi } from 'vitest';
import { ChatActionReports, chatActionLabels } from './chat-action-reports';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

class Control {
  disabled = false;
  title = '';
  hidden: object | null = null;
  constructor(public textContent: string) {}
  closest() { return this.hidden; }
  getAttribute() { return null; }
}

class Card {
  dataset = { messageId: '4', messageTs: '5' };
  buttons = [new Control('Gain 1 Hope'), new Control('Reroll · Ally'), new Control('Claim · Friend')];
  querySelectorAll() { return this.buttons; }
  contains(node: object) { return node === this; }
}

it('mirrors rewards, reactions and claims, including unavailable explanations but not hidden controls', () => {
  const card = new Card();
  card.buttons[1].disabled = true;
  card.buttons[1].title = 'Not enough Hope';
  card.buttons[2].hidden = card;
  expect(chatActionLabels(card as unknown as HTMLElement)).toEqual([
    { label: 'Gain 1 Hope', disabled: false, detail: '' },
    { label: 'Reroll · Ally', disabled: true, detail: 'Not enough Hope' },
  ]);
  // Collapsing the entire chat sidebar does not change the controls inside its cards.
  card.buttons[0].hidden = {};
  expect(chatActionLabels(card as unknown as HTMLElement)[0].label).toBe('Gain 1 Hope');
});

it('coalesces paints, deduplicates unchanged reports and republishes after reconnect', () => {
  vi.useFakeTimers();
  vi.stubGlobal('window', globalThis);
  let mutate = () => {};
  vi.stubGlobal('MutationObserver', class {
    constructor(callback: () => void) { mutate = callback; }
    observe() {}
  });
  const card = new Card();
  const root = { children: [card] };
  const send = vi.fn(() => true);
  const reporter = new ChatActionReports(root as unknown as HTMLElement, send);
  mutate(); mutate(); mutate();
  vi.advanceTimersByTime(100);
  expect(send).toHaveBeenCalledTimes(1);
  expect(send.mock.calls[0]).toEqual([{
    type: 'chatActions', id: 4, ts: 5, actions: card.buttons.map(button => ({
      label: button.textContent, disabled: false, detail: '',
    })),
  }]);
  mutate();
  vi.advanceTimersByTime(100);
  expect(send).toHaveBeenCalledTimes(1);
  card.buttons = [];
  mutate();
  vi.advanceTimersByTime(100);
  expect(send.mock.calls.at(-1)).toEqual([{ type: 'chatActions', id: 4, ts: 5, actions: [] }]);
  card.buttons = [new Control('Claim')];
  send.mockReturnValue(false);
  reporter.reset();
  vi.advanceTimersByTime(100);
  send.mockReturnValue(true);
  reporter.reset();
  vi.advanceTimersByTime(100);
  expect(send.mock.calls.at(-1)).toEqual([{
    type: 'chatActions', id: 4, ts: 5, actions: [{ label: 'Claim', disabled: false, detail: '' }],
  }]);
});
