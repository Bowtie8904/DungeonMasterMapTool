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

vi.mock('./controls', () => ({
  ruleControl: (descriptor: RuleDescriptor, submit: (intent: RuleIntent) => Promise<void>) => {
    const button = document.createElement('button');
    button.textContent = descriptor.label ?? '';
    button.addEventListener('click', () => void submit({ requestId: 'r1', sourceId: descriptor.sourceId,
      sourceInstanceId: descriptor.sourceInstanceId, ruleId: descriptor.ruleId, bindingId: descriptor.bindingId,
      expectedRevision: descriptor.revision, inputs: {} } as RuleIntent));
    return button;
  },
}));

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
    const submit = vi.fn(async (_actor: string, intent: RuleIntent) => { revision += 1; return intent; });
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
  });

  it('shows host failures instead of hiding them', async () => {
    const panel = automationPanel('a1', 'sheet', 'Actions', async () => { throw new Error('Not your sheet.'); }, vi.fn());
    await settle();
    expect(panel.hidden).toBe(false);
    expect(fake(panel).text).toContain('Not your sheet.');
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
      event: { type: 'action.request' },
      request: { requestId: 'r1', expectedRevision: 4, selections: [{ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: { amount: 2 } }] },
    });
  });
});
