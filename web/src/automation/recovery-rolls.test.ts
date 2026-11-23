import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatMessage } from '../protocol';
import type { RuleDescriptor } from './controls';
import { AUTOMATION_COMPATIBILITY_VERSION } from './panel';
import { historyRecoveryRollControls } from './recovery-rolls';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  disabled = false;
  inert = false;
  open = false;
  constructor(readonly tagName: string) {}
  append(...children: Array<Element | string>): void {
    this.children.push(...children.map(child => {
      if (typeof child !== 'string') return child;
      const node = new Element('#text'); node.textContent = child; return node;
    }));
  }
  replaceChildren(...children: Element[]): void { this.children = []; this.append(...children); }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => void): void {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), handler]);
  }
  click(): void { if (!this.disabled && !this.inert) this.listeners.get('click')?.forEach(handler => handler()); }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.forEach(handler => handler()); }
  remove(): void {}
  focus(): void {}
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const fake = (element: HTMLElement) => element as unknown as Element;
const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const offer: ChatMessage = {
  id: 21, ts: 33, fromId: 'owner', from: 'Mira', text: 'Recovery',
  automationRecoveryRoll: { sourceActorId: 'mira', holderId: 'mira', track: 'hp', used: false },
};
const action: RuleDescriptor = {
  sourceId: 'cards:chance', sourceInstanceId: 'chance', ruleId: 'reroll', bindingId: 'use',
  revision: 3, surface: 'chat', mode: 'button', label: 'Reroll', available: true,
};
const description = { revision: 3, holderRevision: 7, offerRevision: 0, actions: [action] };

beforeEach(() => vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), body: new Element('body') }));
afterEach(() => vi.unstubAllGlobals());

describe('saved downtime rolls', () => {
  it('loads owner-specific actions and retries an uncertain roll unchanged', async () => {
    const posts: string[] = [];
    const fetch = vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(posts.length > 1 ? { ...description, actions: [] } : description);
      posts.push(String(init.body));
      if (posts.length === 1) throw new Error('Reply lost');
      return Response.json({ actions: [{ report: 'Kept the higher die.' }] });
    });
    vi.stubGlobal('fetch', fetch);
    expect(historyRecoveryRollControls(offer, [])).toBeNull();
    const card = historyRecoveryRollControls(offer, [{ id: 'ada', name: 'Ada' }])!;
    expect(fetch).not.toHaveBeenCalled();
    fake(card).open = true;
    fake(card).listeners.get('toggle')!.forEach(listener => listener());
    await settle();
    expect(fetch).toHaveBeenCalledWith(
      `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/ada/rest-rolls/21/33`,
      expect.objectContaining({ cache: 'no-store' }));
    fake(card).all('button')[0].click();
    await settle();
    expect(fake(card).text).toContain('Reply lost');
    fake(card).all('button')[0].click();
    await settle();
    expect(posts).toEqual([posts[0], posts[0]]);
    expect(JSON.parse(posts[0]).request).toEqual({
      requestId: expect.any(String), expectedRevision: 3, expectedHolderRevision: 7,
      expectedOfferRevision: 0,
      selection: { sourceInstanceId: 'chance', ruleId: 'reroll', bindingId: 'use', inputs: {} },
    });
    expect(fake(card).text).toContain('Kept the higher die.');
    expect(historyRecoveryRollControls({ ...offer, automationRecoveryRoll: { ...offer.automationRecoveryRoll!, used: true } },
      [{ id: 'ada', name: 'Ada' }])).toBeNull();
  });

  it('refreshes stale choices without resubmitting', async () => {
    let gets = 0, posts = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...description, revision: 3 + gets++ });
      posts++;
      return Response.json({ error: 'Roll changed.' }, { status: 409 });
    }));
    const card = historyRecoveryRollControls(offer, [{ id: 'ada', name: 'Ada' }])!;
    fake(card).open = true;
    fake(card).listeners.get('toggle')!.forEach(listener => listener());
    await settle();
    fake(card).all('button')[0].click();
    await settle();
    expect(posts).toBe(1);
    expect(gets).toBe(2);
    expect(fake(card).text).toContain('Roll changed.');
  });
});
