import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatMessage } from '../protocol';
import type { RuleDescriptor } from './controls';
import { AUTOMATION_COMPATIBILITY_VERSION } from './panel';
import { historyClaimControls } from './claims';

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
  focus(): void {}
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const fake = (element: HTMLElement) => element as unknown as Element;
const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const offer: ChatMessage = {
  id: 21, ts: 33, fromId: 'owner', from: 'Ada', actorId: 'ada', text: 'Share',
  automationClaim: { sourceActorId: 'ada', sourceInstanceId: 'card-a', group: 'share-ally' },
};
const action: RuleDescriptor = {
  sourceId: 'domain-cards:share', sourceInstanceId: 'card-a', ruleId: 'share-ally', bindingId: 'claim',
  revision: 3, surface: 'chat', mode: 'button', label: 'Gain Hope', available: true,
};
const description = { revision: 3, recipientRevision: 7, claimRevision: 0, actions: [action] };

beforeEach(() => vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), body: new Element('body') }));
afterEach(() => vi.unstubAllGlobals());

describe('saved automation claims', () => {
  it('loads the owner-specific choices lazily and retries an uncertain claim unchanged', async () => {
    const posts: string[] = [];
    const fetch = vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(posts.length > 1 ? { ...description, actions: [] } : description);
      posts.push(String(init.body));
      if (posts.length === 1) throw new Error('Reply lost');
      return Response.json({ actions: [{ report: 'Mira gained Hope.' }] });
    });
    vi.stubGlobal('fetch', fetch);
    expect(historyClaimControls(offer, [])).toBeNull();
    const card = historyClaimControls(offer, [{ id: 'mira', name: 'Mira' }])!;
    expect(fetch).not.toHaveBeenCalled();
    fake(card).open = true;
    fake(card).listeners.get('toggle')!.forEach(listener => listener());
    await settle();
    expect(fetch).toHaveBeenCalledWith(
      `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/ada/claims/21/33?recipientId=mira`,
      expect.objectContaining({ cache: 'no-store' }));
    fake(card).all('button')[0].click();
    await settle();
    expect(fake(card).text).toContain('Reply lost');
    fake(card).all('button')[0].click();
    await settle();
    expect(posts).toEqual([posts[0], posts[0]]);
    expect(JSON.parse(posts[0]).request).toEqual({
      requestId: expect.any(String), recipientId: 'mira', expectedRevision: 3,
      expectedRecipientRevision: 7, expectedClaimRevision: 0,
      selection: { sourceInstanceId: 'card-a', ruleId: 'share-ally', bindingId: 'claim', inputs: {} },
    });
    expect(fake(card).text).toContain('Mira gained Hope.');
    expect(fake(card).all('button')).toHaveLength(0);
  });

  it('refreshes a stale offer without submitting it a second time', async () => {
    let gets = 0, posts = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...description, revision: 3 + gets++ });
      posts++;
      return Response.json({ error: 'Offer changed.' }, { status: 409 });
    }));
    const card = historyClaimControls(offer, [{ id: 'mira', name: 'Mira' }])!;
    fake(card).open = true;
    fake(card).listeners.get('toggle')!.forEach(listener => listener());
    await settle();
    fake(card).all('button')[0].click();
    await settle();
    expect(posts).toBe(1);
    expect(gets).toBe(2);
    expect(fake(card).text).toContain('Offer changed.');
    expect(fake(card).all('button')).toHaveLength(1);
  });
});
