import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { openAutomationCheckDialog } from './check';
import { AUTOMATION_COMPATIBILITY_VERSION } from './panel';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  className = '';
  hidden = false;
  disabled = false;
  checked = false;
  value = '';
  closed = false;
  get valueAsNumber(): number { return Number(this.value); }
  constructor(readonly tagName: string) {}
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
const option: RuleDescriptor = {
  sourceId: 'feature:focus', sourceInstanceId: 'focus-1', ruleId: 'focus', bindingId: 'roll',
  revision: 3, surface: 'roll', mode: 'option', label: 'Focus', available: true, defaultOn: true,
  inputs: [{ id: 'amount', kind: 'integer', label: 'Amount', min: 1, max: 3, default: 2 }],
};
const offer = { revision: 3, label: 'Agility Action', options: [option] };
let body: Element;
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-authored ordinary checks', () => {
  it('flushes before fetching and submits host-described selections', async () => {
    const calls: string[] = [];
    let submitted: unknown;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push(init.body ? 'post' : 'get');
      if (!init.body) {
        expect(url).toBe(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/check?kind=action&trait=Agility`);
        return Response.json(offer);
      }
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    expect(await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => { calls.push('flush'); })).toBe(true);
    expect(calls).toEqual(['flush', 'get']);
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Focus');
    dialog.all('button').find(button => button.text === 'Roll')!.click();
    await settle();
    expect(submitted).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 3, checkRoll: { kind: 'action', trait: 'Agility' },
      selections: [{ sourceInstanceId: 'focus-1', ruleId: 'focus', bindingId: 'roll', inputs: { amount: 2 } }],
    } });
    expect(calls).toEqual(['flush', 'get', 'post']);
    expect(dialog.closed).toBe(true);
  });

  it('retains the exact intent after an uncertain submission', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer, label: 'Finesse Reaction' });
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'reaction', 'Finesse', 'Reaction', async () => {});
    const dialog = body.all('dialog')[0], go = dialog.all('button').find(button => button.text === 'Roll')!;
    go.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    expect(dialog.all('input').every(input => input.disabled)).toBe(true);
    go.click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(JSON.parse(requests[0]).request.checkRoll).toEqual({ kind: 'reaction', trait: 'Finesse' });
    expect(dialog.closed).toBe(true);
  });

  it('uses the legacy dialog only for an explicitly legacy or older host', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {})).toBe(false);
    expect(body.all('dialog')[0].closed).toBe(true);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })));
    expect(await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {})).toBe(false);
  });

  it('shows compatibility and invalid-offer errors without falling back', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'Client version 1 differs from host version 2.', code: 'automationCompatibilityMismatch' }, { status: 409 })));
    expect(await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {})).toBe(true);
    expect(body.all('dialog')[0].text).toContain('Client version 1 differs from host version 2.');
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ options: [] })));
    expect(await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {})).toBe(true);
    expect(body.all('dialog')[1].text).toContain('invalid roll offer');
  });

  it('does not open a legacy roll after the loading dialog is dismissed', async () => {
    let finish!: () => void;
    const before = new Promise<void>(resolve => { finish = resolve; });
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    const opening = openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', () => before);
    body.all('button')[0].click();
    finish();
    expect(await opening).toBe(true);
  });
});
