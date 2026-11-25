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

const { AUTOMATION_COMPATIBILITY_VERSION, automationPanel, describeAutomation, executeAutomation, historyResultControls } = await import('./panel');
type DescribedActions = import('./panel').DescribedActions;
const fake = (element: HTMLElement) => element as unknown as Element;

beforeEach(() => vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), body: new Element('body') }));
const action = (overrides: Partial<RuleDescriptor> = {}): RuleDescriptor => ({
  sourceId: 'classes:tester:features:rally', sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use',
  revision: 4, surface: 'sheet', mode: 'button', label: 'Rally (2 Hope)', available: true, ...overrides,
});

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

afterEach(() => vi.unstubAllGlobals());

describe('saved roll controls', () => {
  const roll = { actorId: 'hero', fromId: 'owner', from: 'Player', id: 1, ts: 2, expression: '1d6', rolls: [5], total: 5,
    automationEvent: { type: 'roll.finished', sessionId: 'session', resultId: 'r1' } };
  const toggleOpen = (card: HTMLElement) => {
    (card as HTMLDetailsElement).open = true;
    fake(card).listeners.get('toggle')!.forEach(listener => listener());
  };

  it('loads no historical descriptors until the current owner opens one result', async () => {
    const get = vi.fn(async () => new Response('{}', { status: 409 }));
    vi.stubGlobal('fetch', get);
    expect(historyResultControls(roll, 'viewer', 'session', true)).toBeNull();
    expect(historyResultControls(roll, 'owner', 'session', false)).toBeNull();
    expect(historyResultControls(roll, 'owner', 'old', true)).toBeNull();
    const cards = Array.from({ length: 50 }, () => historyResultControls(roll, 'owner', 'session', true)!);
    expect(get).not.toHaveBeenCalled();
    toggleOpen(cards[3]);
    await settle();
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero?resultId=r1`, expect.objectContaining({ cache: 'no-store' }));
    expect(fake(cards[3]).text).toContain('No current actions');
  });

  it('keeps a lost POST for retry and does not attach revised actions to the original roll', async () => {
    const requests: string[] = [];
    const fetch = vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: 8, actions: [action({ eachId: 'd1' })] });
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Reply lost');
      return Response.json({ revision: 9, resultPublished: true, event: { check: { total: 7 }, resultId: 'r2' },
        resultActions: [action({ label: 'New button' })], actions: [{ report: 'Edited.' }] });
    });
    vi.stubGlobal('fetch', fetch);
    const card = historyResultControls(roll, 'owner', 'session', true)!;
    toggleOpen(card);
    await settle();
    fake(card).all('button')[0].click();
    await settle();
    toggleOpen(card);
    expect(fetch).toHaveBeenCalledTimes(2);
    fake(card).all('button')[0].click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(JSON.parse(requests[0]).request).toEqual({ resultId: 'r1', requestId: expect.any(String), expectedRevision: 8,
      selections: [{ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: {} }] });
    expect(fake(card).text).toContain('Revised total: 7\nEdited.');
    expect(fake(card).all('button')).toHaveLength(0);
  });

  it('reloads a rejected offer without automatically submitting it again', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: requests.length ? 7 : 5, actions: [action()] });
      requests.push(String(init.body));
      return requests.length === 1 ? Response.json({ error: 'Sheet changed' }, { status: 409 })
        : Response.json({ actions: [{ report: 'Gained Hope' }] });
    }));
    const card = historyResultControls(roll, 'owner', 'session', true)!;
    toggleOpen(card);
    await settle();
    fake(card).all('button')[0].click();
    await settle();
    expect(fake(card).text).toContain('Sheet changed');
    expect(requests).toHaveLength(1);
    fake(card).all('button')[0].click();
    await settle();
    expect(JSON.parse(requests[1]).request.expectedRevision).toBe(7);
    expect(fake(card).text).toContain('Gained Hope');
    expect(fake(card).all('button')).toHaveLength(0);
  });

  it('refreshes a lone repeatable result after each distinct use', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ revision: 5 + requests.length, actions: [action({ repeatable: true })] });
      requests.push(String(init.body));
      return Response.json({ revision: 5 + requests.length, actions: [{ report: 'Applied.' }] });
    }));
    const card = historyResultControls(roll, 'owner', 'session', true)!;
    toggleOpen(card);
    await settle();
    fake(card).all('button')[0].click();
    await settle();
    expect(fake(card).all('button')).toHaveLength(1);
    fake(card).all('button')[0].click();
    await settle();
    expect(requests).toHaveLength(2);
    const first = JSON.parse(requests[0]).request, second = JSON.parse(requests[1]).request;
    expect(first.expectedRevision).toBe(5);
    expect(second.expectedRevision).toBe(6);
    expect(second.requestId).not.toBe(first.requestId);
    expect(fake(card).all('button')).toHaveLength(1);
  });
});

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

  it('folds per-die buttons into one compact chip per stored die', async () => {
    const dice = ['d1', 'd2', 'd3', 'd4'].flatMap((eachId, i) => [
      action({ eachId, ruleId: 'spend-for-hope', label: `Prayer Dice (+${i + 1})` }),
      action({ eachId, ruleId: 'remove-die', label: `Remove Prayer Dice die (${i + 1})` }),
    ]);
    const submit = vi.fn(async (_actor: string, _intent: RuleIntent) => ({}));
    const panel = automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 4, actions: dice }), submit);
    await settle();
    const buttons = fake(panel).all('button');
    expect(buttons.map(b => b.text)).toEqual(['+', '\u00d7', '+', '\u00d7', '+', '\u00d7', '+', '\u00d7']);
    expect(fake(panel).all('strong').map(s => s.text)).toEqual(['1', '2', '3', '4']);
    buttons[2].click();
    await settle();
    expect(submit).toHaveBeenCalledWith('a1', expect.objectContaining({ ruleId: 'spend-for-hope', eachId: 'd2' }));
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

  it('submits the stored effect activation with the profile intent', async () => {
    const fetchMock = vi.fn(async () => Response.json({ event: { check: { total: 9 } }, actions: [] }));
    vi.stubGlobal('fetch', fetchMock);
    const panel = fake(automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 5, actions: [], profiles: [
      { id: 'effect.profile', effectInstanceId: 'activation-1', sourceInstanceId: 's1',
        label: 'Claws', sourceName: 'Form', damage: { sides: 8 } },
    ] })));
    await settle();
    panel.all('button')[1].click();
    await settle();
    const [, options] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(String(options.body))).toEqual({ request: { requestId: expect.any(String), expectedRevision: 5,
      selections: [], profileRoll: { sourceInstanceId: 's1', profileId: 'effect.profile', kind: 'damage',
        effectInstanceId: 'activation-1' } } });
  });

  it('renders and submits a host-described spellcast profile without attack or damage controls', async () => {
    const fetchMock = vi.fn(async () => Response.json({
      revision: 6, event: { check: { total: 17, outcome: 'success' } }, actions: [],
    }));
    vi.stubGlobal('fetch', fetchMock);
    const panel = fake(automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 5, actions: [], profiles: [
      { id: 'cast', sourceInstanceId: 's1', label: 'Cast', sourceName: 'Spell', rollKind: 'spellcast' },
    ] })));
    await settle();
    const buttons = panel.all('button');
    expect(buttons.map(button => button.text)).toEqual(['Spell: Cast (spellcast)']);
    buttons[0].click();
    await settle();
    const [, options] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(String(options.body))).toEqual({ request: { requestId: expect.any(String), expectedRevision: 5,
      selections: [], profileRoll: { sourceInstanceId: 's1', profileId: 'cast', kind: 'spellcast' } } });
    expect(panel.text).toContain('Spell: Cast (spellcast): 17 (success)');
  });

  it('disables exhausted profiles and displays the host reason', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const panel = fake(automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 5, actions: [], profiles: [
      { id: 'cast', sourceInstanceId: 's1', label: 'Cast', sourceName: 'Spell', rollKind: 'spellcast',
        available: false, reason: 'No uses remain.' },
    ] })));
    await settle();
    expect(panel.all('button')[0].disabled).toBe(true);
    expect(panel.text).toContain('No uses remain.');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('loads a saved target offer after the roll and sends its version with the result action', async () => {
    const calls: Array<{ url: string; body?: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
      calls.push({ url, body: options.body?.toString() });
      if (url.includes('resultId=')) return Response.json({ revision: 6, actions: [action({
        surface: 'roll', targetSelection: { cardinality: 'all', version: 'saved', options: [{ id: 'b', label: 'Bea' }] },
      })] });
      if (calls.length === 1) return Response.json({ revision: 6, event: { check: { total: 17 }, resultId: 'r1' },
        resultActions: [action({ surface: 'roll', available: false, targetSelection: { cardinality: 'all', version: '', options: [] } })] });
      return Response.json({ revision: 7, actions: [{ report: 'Applied.' }] });
    }));
    const fetchActions = vi.fn(async () => ({ revision: 5, actions: [], profiles: [
      { id: 'far', sourceInstanceId: 's1', label: 'Thrown', sourceName: 'Blade' },
    ] }));
    const panel = fake(automationPanel('a1', 'sheet', 'Actions', fetchActions));
    await settle();
    panel.all('button')[0].click();
    await settle();
    await settle();
    expect(calls.map(call => call.url)).toEqual([`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1`, `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1?resultId=r1`]);
    expect(panel.text).toContain('Recipients: Bea');
    panel.all('button').find(button => button.text === 'Rally (2 Hope)')!.click();
    await settle();
    expect(JSON.parse(calls[2].body!).request).toEqual({ requestId: expect.any(String), expectedRevision: 6,
      resultId: 'r1', target: { version: 'saved' },
      selections: [{ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', inputs: {} }] });
  });

  it('refreshes target offers for a revised result rather than the old roll', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
      calls.push(url);
      if (url.includes('resultId=')) return Response.json({ revision: 7, actions: [action({
        targetSelection: { cardinality: 'all', version: 'revised', options: [{ id: 'b', label: 'Bea' }] },
      })] });
      if (calls.length === 1) return Response.json({ revision: 6, event: { check: { total: 17 }, resultId: 'r1' },
        resultActions: [action({ label: 'Edit' })] });
      expect(JSON.parse(String(options.body)).request.resultId).toBe('r1');
      return Response.json({ revision: 7, resultPublished: true, event: { check: { total: 19 }, resultId: 'r2' },
        resultActions: [action({ targetSelection: { cardinality: 'all', version: '', options: [] } })], actions: [] });
    }));
    const panel = fake(automationPanel('a1', 'sheet', 'Actions', async () => ({ revision: 5, actions: [], profiles: [
      { id: 'far', sourceInstanceId: 's1', label: 'Thrown', sourceName: 'Blade' },
    ] })));
    await settle();
    panel.all('button')[0].click();
    await settle();
    panel.all('button').find(button => button.text === 'Edit')!.click();
    await settle();
    await settle();
    expect(calls).toEqual([`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1`, `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1`, `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1?resultId=r2`]);
    expect(panel.text).toContain('Revised total: 19');
    expect(panel.text).toContain('Recipients: Bea');
  });

  it('maps an intent to a host selection without client-only fields', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await executeAutomation('a1', {
      requestId: 'r1', expectedRevision: 4, sourceId: 'classes:tester:features:rally', sourceInstanceId: 'f-rally',
      ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: { amount: 2 },
    } as RuleIntent);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1`);
    expect(JSON.parse(String(init.body))).toEqual({
      request: { requestId: 'r1', expectedRevision: 4, selections: [{ sourceInstanceId: 'f-rally', ruleId: 'rally', bindingId: 'use', eachId: 'd1', inputs: { amount: 2 } }] },
    });
  });

  it('distinguishes an incompatible host from an unavailable format-2 pack', async () => {
    const fetchMock = vi.fn(async () => Response.json({
      code: 'automationCompatibilityMismatch',
      error: `Online automation compatibility mismatch (client version ${AUTOMATION_COMPATIBILITY_VERSION}; host version 9). Update the browser and host to matching versions and reload.`,
    }, { status: 409 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(describeAutomation('a1')).rejects.toThrow(`client version ${AUTOMATION_COMPATIBILITY_VERSION}; host version 9`);
    fetchMock.mockResolvedValueOnce(Response.json({ error: 'Automation is unavailable.' }, { status: 409 }));
    await expect(describeAutomation('a1')).resolves.toBeNull();
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 404 }));
    await expect(describeAutomation('a1')).resolves.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('never retries a mutation against an older unversioned automation endpoint', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(null, { status: 404 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(executeAutomation('a1', {
      requestId: 'r1', expectedRevision: 4, sourceId: 'classes:tester:features:rally', sourceInstanceId: 'f-rally',
      ruleId: 'rally', bindingId: 'use', inputs: {},
    } as RuleIntent)).rejects.toThrow(`client version ${AUTOMATION_COMPATIBILITY_VERSION}; host version unknown`);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(`/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/a1`);
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
