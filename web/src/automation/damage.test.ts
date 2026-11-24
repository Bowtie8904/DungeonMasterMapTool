import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { openAutomationDamageDialog } from './damage';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<(event?: unknown) => void>>();
  private textValue = '';
  className = '';
  disabled = false;
  checked = false;
  hidden = false;
  value = '';
  closed = false;
  constructor(readonly tagName: string) {}
  get textContent(): string { return this.textValue; }
  set textContent(value: string) { this.textValue = value; this.children = []; }
  append(...children: Array<Element | string>): void {
    this.children.push(...children.map(child => {
      if (typeof child !== 'string') return child;
      const node = new Element('#text'); node.textContent = child; return node;
    }));
  }
  replaceChildren(...children: Element[]): void { this.children = []; this.append(...children); }
  addEventListener(event: string, handler: (event?: unknown) => void): void {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), handler]);
  }
  click(): void { if (!this.disabled) this.listeners.get('click')?.forEach(handler => handler()); }
  focus(): void {}
  showModal(): void {}
  close(): void { this.closed = true; this.listeners.get('close')?.forEach(handler => handler()); }
  remove(): void {}
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 20));
const brace: RuleDescriptor = {
  sourceId: 'feature:brace', sourceInstanceId: 'brace-1', ruleId: 'brace', bindingId: 'prepare',
  revision: 4, surface: 'damage', mode: 'button', label: 'Brace', available: true,
};
const offer = {
  revision: 4, thresholds: { major: 5, severe: 10 },
  applied: { amount: 7, severity: 2, hitPoints: 2, armorMarked: 0, hitPointsRemaining: 4, armorRemaining: 3 },
  stages: { 'damage.prepare': { actions: [brace] }, 'damage.marks': { actions: [] }, 'damage.applied': { actions: [] } },
};
let body: Element;
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-described incoming damage', () => {
  it('previews, submits the chosen mitigation and retries an uncertain request identically', async () => {
    const posts: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init?.body) return Response.json(offer);
      posts.push(String(init.body));
      if (posts.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    let applied = 0;
    expect(await openAutomationDamageDialog('hero', async () => {}, () => { applied++; })).toBe(true);
    const dialog = body.all('dialog')[0];
    const [amount, armor] = dialog.all('input');
    amount.value = '7'; armor.value = '1';
    dialog.all('select')[0].value = 'magical';
    dialog.all('button').find(button => button.text === 'Preview')!.click();
    await settle();
    expect(dialog.text).toContain('severity 2');
    dialog.all('input').find(field => field.tagName === 'input' && field.listeners.has('change'))!.checked = true;
    dialog.all('button').find(button => button.text === 'Take damage')!.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    dialog.all('button').find(button => button.text === 'Retry')!.click();
    await settle();
    expect(posts).toEqual([posts[0], posts[0]]);
    expect(JSON.parse(posts[0])).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 4, damage: { amount: 7, armor: 1, type: 'magical' },
      selections: [{ sourceInstanceId: 'brace-1', ruleId: 'brace', bindingId: 'prepare', inputs: {} }],
    } });
    expect(dialog.closed).toBe(true);
    expect(applied).toBe(1);
  });

  it('reloads the preview after a stale revision', async () => {
    let gets = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init?.body) { gets++; return Response.json(offer); }
      return Response.json({ error: 'The character changed.' }, { status: 409 });
    }));
    await openAutomationDamageDialog('hero', async () => {});
    const dialog = body.all('dialog')[0];
    dialog.all('button').find(button => button.text === 'Preview')!.click();
    await settle();
    dialog.all('button').find(button => button.text === 'Take damage')!.click();
    await settle();
    expect(gets).toBe(3);
    expect(dialog.text).toContain('The character changed.');
  });

  it('falls back to the legacy dialog for format-1 hosts', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationDamageDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })));
    expect(await openAutomationDamageDialog('hero', async () => {})).toBe(false);
    expect(body.all('dialog')).toHaveLength(0);
  });
});
