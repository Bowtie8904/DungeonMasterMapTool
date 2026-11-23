import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { AUTOMATION_COMPATIBILITY_VERSION } from './panel';
import { openAutomationRestDialog } from './rest';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  className = '';
  hidden = false;
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
  click(): void { if (!this.disabled) this.listeners.get('click')?.forEach(handler => handler()); }
  change(): void { this.listeners.get('change')?.forEach(handler => handler()); }
  focus(): void {}
  showModal(): void {}
  close(): void { this.listeners.get('close')?.forEach(handler => handler()); }
  remove(): void {}
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const action = (ruleId: string, mode: 'button' | 'option', label: string,
                extra: Partial<RuleDescriptor> = {}): RuleDescriptor => ({
  sourceId: 'ruleset:base', sourceInstanceId: 'ruleset:base', ruleId, bindingId: mode,
  revision: 3, surface: mode === 'button' ? 'downtime' : 'rest', mode, label, available: true, ...extra,
});
const offer = () => ({
  kind: 'short', revision: 3, offerVersion: 'rest-v1.signed', plan: { moves: 2, longMoves: 0 },
  preparation: [action('extra', 'option', 'Extra time', { defaultOn: true })],
  moves: [
    { ...action('heal', 'button', 'Recover'), sourceName: 'Camp rules', restMove: { id: 'recover', kind: 'short' },
      inputs: [{ id: 'amount', kind: 'integer', label: 'Amount', min: 1, max: 3, default: 2 }],
      options: [action('care', 'option', 'Care', { defaultOn: true, surface: 'downtime' })] },
    { ...action('prepare', 'button', 'Prepare'), sourceName: 'Camp rules', restMove: { id: 'prepare', kind: 'any' },
      options: [] },
  ],
  completion: [action('benefit', 'option', 'Benefit')],
});

let body: Element;
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-authored rest dialog', () => {
  it('flushes before fetching and submits host-described moves and choices', async () => {
    const calls: string[] = [];
    let submitted: Record<string, unknown> | undefined;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      calls.push(init.body ? 'post' : 'get');
      if (!init.body) return Response.json(offer());
      submitted = JSON.parse(String(init.body)) as Record<string, unknown>;
      return Response.json({ actions: [{ report: 'Recovered 2 HP.' }],
        partyRecoveries: [{ holderName: 'Mira', track: 'armorTrack', requested: 2, cleared: 1, label: 'Shared repair' }] });
    }));
    const handled = await openAutomationRestDialog('hero', 'short', async () => { calls.push('flush'); });
    expect(handled).toBe(true);
    expect(calls).toEqual(['flush', 'get']);
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Camp rules: Recover');
    const select = dialog.all('select')[0];
    select.value = '0'; select.change();
    const finish = dialog.all('button').find(button => button.text === 'Complete rest')!;
    finish.click();
    await settle();
    const request = submitted?.request as Record<string, unknown>;
    expect(request).toEqual({
      requestId: expect.any(String), offerVersion: 'rest-v1.signed', kind: 'short', expectedRevision: 3,
      preparation: [{ sourceInstanceId: 'ruleset:base', ruleId: 'extra', bindingId: 'option', inputs: {} }],
      moves: [{ selection: { sourceInstanceId: 'ruleset:base', ruleId: 'heal', bindingId: 'button', inputs: { amount: 2 } },
        options: [{ sourceInstanceId: 'ruleset:base', ruleId: 'care', bindingId: 'option', inputs: {} }] }],
      completion: [],
    });
    expect(dialog.text).toContain('Recovered 2 HP.');
    expect(dialog.text).toContain('Mira cleared 1 armorTrack (requested 2; Shared repair).');
    expect(calls).toEqual(['flush', 'get', 'post']);
  });

  it('retries an uncertain response with the identical request', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(offer());
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({ actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0], go = dialog.all('button').find(button => button.text === 'Complete rest')!;
    go.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    expect(dialog.all('select')[0].disabled).toBe(true);
    go.click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(dialog.text).toContain('Rest completed.');
  });

  it('offers benefits after the resolved moves and again after the first completion phase', async () => {
    const calls: Array<{ url: string; body: Record<string, unknown> }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), postOutcomeChoices: true });
      const request = JSON.parse(String(init.body)) as Record<string, unknown>;
      calls.push({ url, body: request });
      if (url.endsWith('/complete')) return Response.json({ actions: [{ report: 'Benefits settled.' }] });
      if ('request' in request) return Response.json({ phase: 'beforeReset', choiceVersion: 'first-signature',
        cleared: { stress: 2 }, reports: ['Cleared 2 Stress.'],
        actions: [action('after-move', 'option', 'Reward recovery', { defaultOn: true })] });
      return Response.json({ phase: 'afterReset', choiceVersion: 'second-signature', cleared: { stress: 2 },
        reports: ['Recovery rewarded.'], actions: [action('after-reset', 'option', 'Reward Hope', { defaultOn: true })] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0];
    expect(dialog.text).not.toContain('Benefit');
    const go = dialog.all('button').find(button => button.text === 'Complete rest')!;
    go.click();
    await settle();
    expect(dialog.text).toContain('Cleared 2 Stress.');
    expect(dialog.text).toContain('Reward recovery');
    expect(dialog.text).toContain('stress: 2');
    go.click();
    await settle();
    expect(calls[1].body).toEqual({ requestId: expect.any(String), choiceVersion: 'first-signature',
      selections: [{ sourceInstanceId: 'ruleset:base', ruleId: 'after-move', bindingId: 'option', inputs: {} }] });
    expect(dialog.text).toContain('Reward Hope');
    go.click();
    await settle();
    expect(calls[2].body.choiceVersion).toBe('second-signature');
    expect((calls[2].body.request as { completion: unknown[] }).completion).toEqual([
      { sourceInstanceId: 'ruleset:base', ruleId: 'after-move', bindingId: 'option', inputs: {} },
      { sourceInstanceId: 'ruleset:base', ruleId: 'after-reset', bindingId: 'option', inputs: {} },
    ]);
    expect(dialog.text).toContain('Benefits settled.');
  });

  it('retains rolled preparation while the owner chooses downtime moves', async () => {
    const calls: Array<{ url: string; body: Record<string, unknown> }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), postOutcomeChoices: true, prepareBeforeMoves: true,
        moves: [], completion: [] });
      const request = JSON.parse(String(init.body)) as Record<string, unknown>;
      calls.push({ url, body: request });
      if ('prepare' in request) {
        if (calls.length === 1) throw new Error('Preparation response lost.');
        return Response.json({ ...offer(), postOutcomeChoices: true, preparation: [], choiceVersion: 'prepared',
          plan: { moves: 3, longMoves: 0 }, reports: ['Preparation rolled 1.'] });
      }
      if (url.endsWith('/complete')) return Response.json({ actions: [{ report: 'Rest completed.' }] });
      return Response.json({ phase: 'preparedVersion' in request ? 'beforeReset' : 'afterReset',
        choiceVersion: 'next', cleared: {}, reports: [], actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0];
    expect(dialog.all('select')).toHaveLength(0);
    const prepare = dialog.all('button').find(button => button.text === 'Prepare rest')!;
    prepare.click();
    await settle();
    expect(dialog.text).toContain('Preparation response lost.');
    prepare.click();
    await settle();
    expect(calls[1].body).toEqual(calls[0].body);
    expect(dialog.text).toContain('Preparation rolled 1.');
    expect(dialog.all('select')).toHaveLength(3);
    const select = dialog.all('select')[0];
    select.value = '1'; select.change();
    dialog.all('button').find(button => button.text === 'Complete rest')!.click();
    await settle();
    const preparedRequest = calls[0].body.request as Record<string, unknown>;
    const movesRequest = calls[2].body.request as Record<string, unknown>;
    expect(calls[2].body.preparedVersion).toBe('prepared');
    expect(movesRequest.requestId).toBe(preparedRequest.requestId);
    expect(movesRequest.preparation).toEqual(preparedRequest.preparation);
    expect(movesRequest.moves).toHaveLength(1);
    expect(dialog.text).toContain('Rest completed.');
  });

  it('finishes without another confirmation when no post-outcome benefits are available', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), postOutcomeChoices: true });
      calls.push(url);
      if (url.endsWith('/complete')) return Response.json({ actions: [] });
      const first = calls.length === 1;
      return Response.json({ phase: first ? 'beforeReset' : 'afterReset', choiceVersion: first ? 'one' : 'two',
        cleared: {}, reports: [], actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0];
    dialog.all('button').find(button => button.text === 'Complete rest')!.click();
    await settle();
    expect(calls.map(url => url.split('/').at(-1))).toEqual(['choices', 'choices', 'complete']);
    expect(dialog.text).toContain('Rest completed.');
  });

  it('retries uncertain staged and final submissions without changing the request', async () => {
    const previews: string[] = [], completions: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), postOutcomeChoices: true });
      const payload = String(init.body);
      if (url.endsWith('/complete')) {
        completions.push(payload);
        if (completions.length === 1) throw new Error('Final response lost.');
        return Response.json({ actions: [] });
      }
      const request = JSON.parse(payload) as Record<string, unknown>;
      if ('request' in request) {
        previews.push(payload);
        if (previews.length === 1) throw new Error('Preview response lost.');
        return Response.json({ phase: 'beforeReset', choiceVersion: 'one', cleared: {}, reports: [], actions: [] });
      }
      return Response.json({ phase: 'afterReset', choiceVersion: 'two', cleared: {}, reports: [], actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0], go = dialog.all('button').find(button => button.text === 'Complete rest')!;
    go.click();
    await settle();
    expect(dialog.text).toContain('Preview response lost.');
    expect(dialog.all('select')[0].disabled).toBe(true);
    go.click();
    await settle();
    expect(previews).toEqual([previews[0], previews[0]]);
    expect(dialog.text).toContain('Final response lost.');
    go.click();
    await settle();
    expect(completions).toEqual([completions[0], completions[0]]);
    expect(dialog.text).toContain('Rest completed.');
  });

  it('requires a host-described recipient and submits its version with the move', async () => {
    const described = offer();
    Object.assign(described.moves[0], { targetSelection: { cardinality: 'one', options: [
      { id: 'mira', label: 'Mira', version: 'target-version' },
      { id: 'borin', label: 'Borin', version: 'other-version' },
    ] } });
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(described);
      requests.push(String(init.body));
      return Response.json({ actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0], select = dialog.all('select')[0];
    select.value = '0'; select.change();
    expect(dialog.text).toContain('Mira');
    const go = dialog.all('button').find(button => button.text === 'Complete rest')!;
    go.click();
    expect(requests).toHaveLength(0);
    expect(dialog.text).toContain('Select exactly 1');
    const recipient = dialog.all('fieldset').find(fieldset => fieldset.text.includes('Mira'))!;
    recipient.all('input')[0].checked = true;
    go.click();
    await settle();
    expect(JSON.parse(requests[0]).request.moves[0].target).toEqual({ recipientId: 'mira', version: 'target-version' });
  });

  it('allows corrections after a rejected request without reusing its ID', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(offer());
      requests.push(String(init.body));
      return requests.length === 1 ? Response.json({ error: 'No slots remain.' }, { status: 409 })
        : Response.json({ actions: [] });
    }));
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0], go = dialog.all('button').find(button => button.text === 'Complete rest')!;
    go.click();
    await settle();
    expect(dialog.text).toContain('No slots remain.');
    expect(dialog.all('select')[0].disabled).toBe(false);
    go.click();
    await settle();
    expect(JSON.parse(requests[0]).request.requestId).not.toBe(JSON.parse(requests[1]).request.requestId);
  });

  it('only falls back for legacy hosts and displays compatibility errors', async () => {
    const fetch = vi.fn(async () => Response.json({ legacy: true }));
    vi.stubGlobal('fetch', fetch);
    expect(await openAutomationRestDialog('hero', 'long', async () => {})).toBe(false);
    expect(fetch).toHaveBeenCalledWith(
      `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/rest?kind=long`, expect.any(Object));
    fetch.mockResolvedValueOnce(Response.json({ code: 'automationCompatibilityMismatch',
      error: 'Client version 1 differs from host version 2.' }, { status: 409 }));
    expect(await openAutomationRestDialog('hero', 'long', async () => {})).toBe(true);
    expect(body.all('dialog').at(-1)?.text).toContain('Client version 1 differs from host version 2.');
  });

  it('keeps the dialog open when the sheet rerenders during a rest', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(offer())));
    const sheet = new Element('sheet');
    body.append(sheet);
    await openAutomationRestDialog('hero', 'short', async () => {});
    const dialog = body.all('dialog')[0];
    sheet.replaceChildren(new Element('replacement'));
    expect(body.all('dialog')).toContain(dialog);
    expect(dialog.text).toContain('Complete rest');
  });

  it('does not open the legacy dialog if the rest dialog was closed while loading', async () => {
    let respond!: (response: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { respond = resolve; })));
    const loading = openAutomationRestDialog('hero', 'short', async () => {});
    await settle();
    body.all('button').find(button => button.text === 'Cancel')!.click();
    respond(Response.json({ legacy: true }));
    expect(await loading).toBe(true);
  });
});
