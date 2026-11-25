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
  checked = false;
  hidden = false;
  value = '';
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
  remove(): void { this.closed = true; }
  fire(event: string): void { this.listeners.get(event)?.forEach(handler => handler()); }
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const action: RuleDescriptor = {
  sourceId: 'feature:renewal', sourceInstanceId: 'renewal-1', ruleId: 'renew', bindingId: 'choose',
  revision: 3, surface: 'death', mode: 'button', label: 'Renew', available: true,
};
let body: Element;
const dialogOf = () => body.children.find(child => child.className.includes('dh-rolldlg-backdrop'))!;
const confirmOf = (dialog: Element) => dialog.all('button').find(button => button.text === 'Confirm death move')!;
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-authored death options', () => {
  it('renders the format-1 dialog, flushes before fetching and submits the selected death action', async () => {
    const calls: string[] = [];
    let submitted: unknown;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push(init.body ? 'post' : 'get');
      if (!init.body) {
        expect(url).toBe(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/death`);
        return Response.json({ revision: 3, actions: [{ ...action, cost: '1 Hope', description: 'Renew yourself.' }] });
      }
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    expect(await openAutomationDeathDialog('hero', async () => { calls.push('flush'); }, 'Ada')).toBe(true);
    const dialog = dialogOf();
    expect(dialog.children[0].className).toBe('dh-rolldlg dh-option-rolldlg');
    expect(dialog.text).toContain('Ada - Death move');
    expect(dialog.all('option')[0].text).toBe('Renew (1 Hope)');
    expect(dialog.text).toContain('Renew yourself.');
    expect(dialog.text).toContain('The result is shared publicly in chat.');
    confirmOf(dialog).click();
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
    const dialog = dialogOf();
    confirmOf(dialog).click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    confirmOf(dialog).click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(dialog.closed).toBe(true);
  });

  it('submits the selected death roll with its options in the same dialog', async () => {
    const option: RuleDescriptor = { ...action, surface: 'roll', mode: 'option', defaultOn: true, label: 'Bonus', sourceName: 'Blessed' };
    let submitted: unknown;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: 3, actions: [action], rolls: {
        avoid: { label: 'Avoid Death', options: [] },
        risk: { label: 'Risk It All', description: 'Roll Hope and Fear.', options: [option] },
      } });
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    await openAutomationDeathDialog('hero', async () => {});
    const dialog = dialogOf();
    expect(dialog.all('option').map(entry => entry.text)).toEqual(['Avoid Death', 'Risk It All', 'Renew']);
    const select = dialog.all('select')[0];
    select.value = '1';
    select.fire('change');
    expect(dialog.text).toContain('Roll Hope and Fear.');
    expect(dialog.text).toContain('Bonus - Blessed');
    expect(dialog.all('input')[0].checked).toBe(true);
    confirmOf(dialog).click();
    await settle();
    expect(body.children).toHaveLength(1);
    expect(submitted).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 3, deathRoll: { move: 'risk' },
      selections: [{ sourceInstanceId: 'renewal-1', ruleId: 'renew', bindingId: 'choose', inputs: {} }],
    } });
    expect(dialog.closed).toBe(true);
  });

  it('reloads stale options instead of retrying a rejected revision', async () => {
    let loads = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: ++loads, actions: [action] });
      return Response.json({ error: 'The character changed.' }, { status: 409 });
    }));
    await openAutomationDeathDialog('hero', async () => {});
    const dialog = dialogOf();
    confirmOf(dialog).click();
    await settle();
    expect(loads).toBe(2);
    expect(dialog.text).toContain('The character changed.');
    expect(confirmOf(dialog).disabled).toBe(false);
  });

  it('disables unavailable death actions with their reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ revision: 3,
      actions: [{ ...action, available: false, reason: 'Already used.' }] })));
    await openAutomationDeathDialog('hero', async () => {});
    const dialog = dialogOf();
    expect(dialog.all('option')[0].text).toBe('Renew — Already used.');
    expect(confirmOf(dialog).disabled).toBe(true);
  });

  it('falls back only for an explicitly legacy or older host', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { error: 'Client version 1 differs from host version 2.' }, { status: 409 })));
    expect(await openAutomationDeathDialog('hero', async () => {})).toBe(true);
    expect(body.children[2].text).toContain('Client version 1 differs from host version 2.');
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
