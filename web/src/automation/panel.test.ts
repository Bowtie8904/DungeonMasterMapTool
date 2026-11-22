import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor, RuleIntent } from './controls';

class Element {
  children: Array<Element> = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  hidden = false;
  className = '';
  disabled = false;
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
  click(): void { this.listeners.get('click')?.forEach(handler => handler()); }
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const { automationPanel, executeAutomation } = await import('./panel');
type DescribedActions = import('./panel').DescribedActions;
const fake = (element: HTMLElement) => element as unknown as Element;

beforeEach(() => vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) }));
const action = (overrides: Partial<RuleDescriptor> = {}): RuleDescriptor => ({
  sourceId: 'classes:tester:features:rally', sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use',
  revision: 4, surface: 'sheet', mode: 'button', label: 'Rally (2 Hope)', available: true, ...overrides,
});

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

afterEach(() => vi.unstubAllGlobals());

describe('automation panel', () => {
  it('stays hidden without an automation pack or button actions', async () => {
    const none = automationPanel('a1', 'sheet', 'Actions', async () => null, vi.fn());
    const toggles = automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 1, actions: [action({ mode: 'option' })] }), vi.fn());
    await settle();
    expect(none.hidden).toBe(true);
    expect(toggles.hidden).toBe(true);
  });

  it('renders configured labels, submits intents and refreshes', async () => {
    let revision = 4;
    const fetchActions = vi.fn(async (): Promise<DescribedActions> => ({ revision, actions: [action({ revision })] }));
    const submit = vi.fn(async (_actor: string, _intent: RuleIntent) => { revision += 1; return { actions: [{ report: 'Roll total: 14.' }] }; });
    const panel = automationPanel('a1', 'sheet', 'Actions', fetchActions, submit);
    await settle();
    const button = fake(panel).all('button')[0];
    expect(panel.hidden).toBe(false);
    expect(button.text).toBe('Rally (2 Hope)');
    button.click();
    await settle();
    await settle();
    expect(submit).toHaveBeenCalledWith('a1', expect.objectContaining({ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', expectedRevision: 4 }));
    expect(fetchActions).toHaveBeenCalledTimes(2);
    expect(fake(panel).text).toContain('Roll total: 14.');
  });

  it('describes and submits on the configured surface and event', async () => {
    const fetchActions = vi.fn(async (): Promise<DescribedActions> => ({ revision: 1, actions: [action({ revision: 1 })] }));
    const submit = vi.fn(async () => ({ actions: [] }));
    const panel = automationPanel('a1', 'downtime', 'Downtime', fetchActions, submit, 'downtime.move');
    await settle();
    fake(panel).all('button')[0].click();
    await settle();
    expect(fetchActions).toHaveBeenCalledWith('a1', 'downtime', 'downtime.move');
    expect(submit).toHaveBeenCalledWith('a1', expect.objectContaining({ ruleId: 'rally' }));
  });

  it('shows host failures instead of hiding them', async () => {
    const panel = automationPanel('a1', 'sheet', 'Actions', async () => { throw new Error('Not your sheet.'); }, vi.fn());
    await settle();
    expect(panel.hidden).toBe(false);
    expect(fake(panel).text).toContain('Not your sheet.');
  });

  it('submits only profile IDs and retries the identical roll after a lost response', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, options: RequestInit) => {
      requests.push(String(options.body));
      if (requests.length === 1) throw new Error('Lost response');
      return new Response(JSON.stringify({ event: { check: { total: 17 } }, actions: [] }));
    }));
    const fetchActions = vi.fn(async () => ({ revision: 5, actions: [], profiles: [
      { id: 'far', sourceInstanceId: 's1', label: 'Thrown', sourceName: 'Blade', damage: { sides: 8 } },
      { id: 'near', sourceInstanceId: 's1', label: 'Close', sourceName: 'Blade' },
    ] }));
    const panel = automationPanel('a1', 'sheet', 'Actions', fetchActions);
    await settle();
    const buttons = fake(panel).all('button');
    expect(buttons.map(button => button.text)).toEqual(['Blade: Thrown (attack)', 'Blade: Thrown (damage)', 'Blade: Close (attack)']);
    buttons[1].click();
    await settle();
    expect(fake(panel).text).toContain('Lost response');
    buttons[1].click();
    await settle();
    expect(requests).toHaveLength(2);
    expect(requests[1]).toBe(requests[0]);
    expect(JSON.parse(requests[0])).toEqual({ request: { requestId: expect.any(String), expectedRevision: 5,
      selections: [], profileRoll: { sourceInstanceId: 's1', profileId: 'far', kind: 'damage' } } });
    expect(fake(panel).text).toContain('Blade: Thrown (damage): 17');
    expect(fetchActions).toHaveBeenCalledTimes(2);
  });

  it('maps an intent to a host selection without client-only fields', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await executeAutomation('a1', {
      requestId: 'r1', expectedRevision: 4, sourceId: 'classes:tester:features:rally', sourceInstanceId: 'f-rally',
      ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: { amount: 2 },
    } as RuleIntent);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/automation/a1');
    expect(JSON.parse(String(init.body))).toEqual({
      request: { requestId: 'r1', expectedRevision: 4, selections: [{ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: { amount: 2 } }] },
    });
  });
});
