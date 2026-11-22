import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor, RuleIntent } from './controls';

class Element {
  children: Array<Element> = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  hidden = false;
  className = '';
  disabled = false;
  checked = false;
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
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
  change(): void { this.listeners.get('change')?.forEach(handler => handler()); }
  focus(): void {}
  showModal(): void {}
  close(): void { this.listeners.get('close')?.forEach(handler => handler()); }
  remove(): void {}
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const { automationPanel, executeAutomation } = await import('./panel');
type DescribedActions = import('./panel').DescribedActions;
const fake = (element: HTMLElement) => element as unknown as Element;

beforeEach(() => vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), body: new Element('body') }));
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

    it('chooses profile options locally, validates only their inputs, and freezes selections for retry', async () => {
      const requests: string[] = [];
      vi.stubGlobal('fetch', vi.fn(async (_url: string, options: RequestInit) => {
        requests.push(String(options.body));
        if (requests.length === 1) throw new Error('Lost response');
        return new Response(JSON.stringify({ event: { check: { total: 8 } } }));
      }));
      const option = (ruleId: string, extra: Partial<RuleDescriptor> = {}) =>
        action({ ruleId, label: ruleId, surface: 'roll', mode: 'option', ...extra });
      const fetchActions = vi.fn(async () => ({ revision: 4, actions: [], profiles: [{
        id: 'claw', label: 'Claw', sourceInstanceId: 'gear', sourceName: 'Weapon', options: { attack: [
          option('first', { defaultOn: true, exclusiveGroup: 'stance', inputs: [
            { kind: 'choice', label: 'Choose', id: 'choice', count: 1, options: [{ id: 'a', label: 'A' }] }] }),
          option('second', { defaultOn: true, exclusiveGroup: 'stance', inputs: [
            { kind: 'integer', id: 'n', label: 'Amount', min: 1, max: 3, default: 2 }] }),
          option('spent', { defaultOn: true, available: false, reason: 'Not enough Hope' }),
        ] } }] }));
      const panel = fake(automationPanel('a1', 'sheet', 'Actions', fetchActions));
      await settle();
      panel.all('button')[0].click();
      const dialog = fake(document.body).all('dialog')[0];
      const [first, , second, amount, spent] = dialog.all('input');
      expect([first.checked, second.checked, spent.checked, spent.disabled]).toEqual([true, false, false, true]);
      expect(dialog.text).toContain('Not enough Hope');
      dialog.all('button')[1].click();
      expect(requests).toHaveLength(0);
      expect(dialog.text).toContain('Select exactly 1 option.');
      second.checked = true;
      second.change();
      expect(first.checked).toBe(false);
      expect(amount.value).toBe('2');
      amount.value = '3';
      expect(fetchActions).toHaveBeenCalledTimes(1);
      dialog.all('button')[1].click();
      await settle();
      expect(fetchActions).toHaveBeenCalledTimes(1);
      expect(JSON.parse(requests[0]).request).toEqual({ requestId: expect.any(String), expectedRevision: 4,
        profileRoll: { sourceInstanceId: 'gear', profileId: 'claw', kind: 'attack' },
        selections: [{ sourceInstanceId: 'f-rally', ruleId: 'second', bindingId: 'use', inputs: { n: 3 } }] });
      amount.value = '1';
      dialog.all('button')[1].click();
      await settle();
      expect(requests).toEqual([requests[0], requests[0]]);
      expect(panel.text).toContain('Claw (attack): 8');
    });

    it('rolls directly if all options are unavailable and refreshes a rejection without rolling again', async () => {
      const post = vi.fn(async () => new Response(JSON.stringify({error: 'Sheet changed'}), {status: 409}));
      vi.stubGlobal('fetch', post);
      const fetchActions = vi.fn(async () => ({ revision: 4, actions: [], profiles: [{ id: 'claw', label: 'Claw',
        sourceInstanceId: 'gear', sourceName: 'Weapon', options: { attack: [action({ mode: 'option', available: false, reason: 'Spent' })] } }] }));
      const panel = fake(automationPanel('a1', 'sheet', 'Actions', fetchActions));
      await settle();
      expect(panel.text).toContain('Spent');
      panel.all('button')[0].click();
      await settle();
      expect(fake(document.body).all('dialog')).toHaveLength(0);
      expect(panel.text).toContain('Sheet changed');
      expect(post).toHaveBeenCalledTimes(1);
      expect(fetchActions).toHaveBeenCalledTimes(2);
    });

  it('shows the party inline, sends only its version, and refreshes a rejected offer without reapplying', async () => {
      const requests: string[] = [];
      vi.stubGlobal('fetch', vi.fn(async (_url: string, options: RequestInit) => {
        requests.push(String(options.body));
        return requests.length === 1
          ? new Response(JSON.stringify({ error: 'Party changed. Refresh this action.' }), { status: 409 })
          : new Response('{}');
      }));
      const fetchActions = vi.fn(async () => ({ revision: 4, actions: [action({ targetSelection: {
        cardinality: 'all', version: requests.length ? 'new' : 'old',
        options: requests.length ? [{ id: 'b', label: 'Bea' }] : [{ id: 'a', label: 'Ada' }],
      } })] }));
      const panel = fake(automationPanel('a1', 'sheet', 'Actions', fetchActions));
      await settle();
      expect(panel.text).toContain('Recipients: Ada');
      panel.all('button')[0].click();
      await settle();
      expect(requests).toHaveLength(1);
      expect(panel.text).toContain('Party changed.');
      expect(panel.text).toContain('Recipients: Bea');
      expect(panel.text).not.toContain('Ada');
      panel.all('button')[0].click();
      await settle();
      const [first, second] = requests.map(body => JSON.parse(body).request);
      expect(first.target).toEqual({ version: 'old' });
      expect(second.target).toEqual({ version: 'new' });
      expect(second.requestId).not.toBe(first.requestId);
      expect(first.selections[0]).toEqual({ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', inputs: {} });
  });
});
