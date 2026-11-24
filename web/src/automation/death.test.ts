import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { openAutomationDeathDialog } from './death';
import { AUTOMATION_COMPATIBILITY_VERSION } from './panel';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<() => void>>();
  private textValue = '';
  className = '';
  disabled = false;
  closed = false;
  inert = false;
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
  addEventListener(event: string, handler: () => void): void {
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

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const action: RuleDescriptor = {
  sourceId: 'feature:renewal', sourceInstanceId: 'renewal-1', ruleId: 'renew', bindingId: 'choose',
  revision: 3, surface: 'death', mode: 'button', label: 'Renew', available: true,
};
let body: Element;
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-authored death options', () => {
  it('flushes before fetching and submits the host-described action', async () => {
    const calls: string[] = [];
    let submitted: unknown;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push(init.body ? 'post' : 'get');
      if (!init.body) {
        expect(url).toBe(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/death`);
        return Response.json({ revision: 3, actions: [action] });
      }
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    expect(await openAutomationDeathDialog('hero', async () => { calls.push('flush'); })).toBe(true);
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Renew');
    dialog.all('button').find(button => button.text === 'Renew')!.click();
    await settle();
    expect(submitted).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 3, deathAction: true,
      selections: [{ sourceInstanceId: 'renewal-1', ruleId: 'renew', bindingId: 'choose', inputs: {} }],
    } });
    expect(calls).toEqual(['flush', 'get', 'post']);
    expect(dialog.closed).toBe(true);
  });

  it('retries an uncertain submission with the identical intent', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: 3, actions: [action] });
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    await openAutomationDeathDialog('hero', async () => {});
    const dialog = body.all('dialog')[0];
    dialog.all('button').find(button => button.text === 'Renew')!.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    dialog.all('button').find(button => button.text === 'Retry')!.click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(dialog.closed).toBe(true);
  });

  it('reloads stale options instead of retrying a rejected revision', async () => {
    let loads = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: ++loads, actions: [action] });
      return Response.json({ error: 'The character changed.' }, { status: 409 });
    }));
    await openAutomationDeathDialog('hero', async () => {});
    const dialog = body.all('dialog')[0];
    dialog.all('button').find(button => button.text === 'Renew')!.click();
    await settle();
    expect(loads).toBe(2);
    expect(dialog.text).toContain('The character changed.');
    expect(dialog.all('button').find(button => button.text === 'Renew')?.disabled).toBe(false);
  });

  it('falls back only for an explicitly legacy or older host', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'Client version 1 differs from host version 2.' }, { status: 409 })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(true);
    expect(body.all('dialog')[2].text).toContain('Client version 1 differs from host version 2.');
  });

  it('does not open a legacy dialog after the loading dialog is dismissed', async () => {
    let finish!: () => void;
    const before = new Promise<void>(resolve => { finish = resolve; });
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    const opening = openAutomationDeathDialog('hero', () => before);
    body.all('button').find(button => button.text === 'Cancel')!.click();
    finish();
    expect(await opening).toBe(true);
  });
});
