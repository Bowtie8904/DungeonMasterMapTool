import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { openAutomationRestDialog } from './rest';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<(event?: unknown) => void>>();
  private ownText = '';
  get textContent(): string { return this.ownText; }
  set textContent(value: string) { this.ownText = value; this.children = []; }
  className = '';
  hidden = false;
  disabled = false;
  checked = false;
  value = '';
  removed = false;
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
  addEventListener(event: string, handler: (event?: unknown) => void): void {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), handler]);
  }
  click(): void { if (!this.disabled) this.listeners.get('click')?.forEach(handler => handler({ target: this })); }
  change(): void { this.listeners.get('change')?.forEach(handler => handler()); }
  focus(): void {}
  remove(): void { this.removed = true; }
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
  /** Chooses the select option whose caption starts with `caption`. */
  choose(caption: string): void {
    const option = this.all('option').find(item => item.text.startsWith(caption));
    if (!option) throw new Error(`No option ${caption} in ${this.all('option').map(item => item.text).join(', ')}`);
    this.value = option.value;
    this.change();
  }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const action = (ruleId: string, mode: 'button' | 'option', label: string,
                extra: Partial<RuleDescriptor> = {}): RuleDescriptor => ({
  sourceId: 'ruleset:base', sourceInstanceId: 'ruleset:base', ruleId, bindingId: mode,
  revision: 3, surface: mode === 'button' ? 'downtime' : 'rest', mode, label, available: true, ...extra,
});
const ally = { cardinality: 'one' as const, options: [{ id: 'mira', label: 'Mira', version: 'mira-v1' }] };
const offer = () => ({
  kind: 'short', revision: 3, offerVersion: 'rest-v1.signed', plan: { moves: 2, longMoves: 0 },
  preparation: [action('extra', 'option', 'Extra time', { defaultOn: true })],
  moves: [
    { ...action('heal', 'button', 'Recover'), sourceName: 'Core', restMove: { id: 'recover', kind: 'short' },
      inputs: [{ id: 'amount', kind: 'integer', label: 'Amount', min: 1, max: 3, default: 2 }],
      options: [action('care', 'option', 'Care', { defaultOn: true, surface: 'downtime' })] },
    { ...action('heal-ally', 'button', 'Recover (an ally)'), sourceName: 'Core',
      restMove: { id: 'recover', kind: 'short', ally: true }, targetSelection: ally, options: [] },
    { ...action('prepare', 'button', 'Prepare'), sourceName: 'Core', restMove: { id: 'prepare', kind: 'any', group: 'prep' },
      options: [] },
    { ...action('prepare-group', 'button', 'Prepare with party'), sourceName: 'Core',
      restMove: { id: 'prepare-group', kind: 'any', group: 'prep' }, options: [] },
  ],
  completion: [action('benefit', 'option', 'Benefit')],
});

let body: Element;
let keyListeners: Array<(event: unknown) => void>;
beforeEach(() => {
  body = new Element('body');
  keyListeners = [];
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    addEventListener: (_: string, handler: (event: unknown) => void) => keyListeners.push(handler),
    removeEventListener: (_: string, handler: (event: unknown) => void) => {
      keyListeners = keyListeners.filter(item => item !== handler);
    } });
});
afterEach(() => vi.unstubAllGlobals());

const dialog = () => body.children[0];
const open = async (kind: 'short' | 'long' = 'short') => { await openAutomationRestDialog('hero', kind, async () => {}); await settle(); };
const button = (caption: string) => dialog().all('button').find(item => item.text === caption)!;
const moveSelects = () => dialog().all('select').filter(select => select.all('option')[0]?.text === 'None');

describe('format-1 styled rest dialog', () => {
  it('renders the legacy shell and submits a downtime move with its options', async () => {
    const calls: string[] = [];
    let submitted: Record<string, unknown> | undefined;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      calls.push(init.body ? 'post' : 'get');
      if (!init.body) return Response.json(offer());
      submitted = JSON.parse(String(init.body)) as Record<string, unknown>;
      return Response.json({ actions: [] });
    }));
    expect(await openAutomationRestDialog('hero', 'short', async () => { calls.push('flush'); })).toBe(true);
    await settle();
    expect(calls).toEqual(['flush', 'get', 'get']);
    const shell = dialog();
    expect(shell.className).toBe('dh dh-rolldlg-backdrop');
    expect(shell.children[0].className).toBe('dh-rolldlg dh-restdlg dh-option-rolldlg');
    expect(shell.text).toContain('Short rest - downtime moves');
    expect(shell.text).toContain('Each character gets 2 downtime moves');
    expect(shell.all('div').filter(div => div.className === 'dh-rest-slot')).toHaveLength(2);
    expect(shell.text).toContain('Move 1');
    expect(shell.text).toContain('Extra time');
    expect(shell.text).not.toContain('Recover (an ally)');
    moveSelects()[0].choose('Recover');
    expect(moveSelects()[1].all('option').find(item => item.text.startsWith('Recover'))!.text)
      .toBe('Recover (already chosen)');
    button('Take short rest').click();
    await settle();
    expect(submitted?.request).toEqual({
      requestId: expect.any(String), offerVersion: 'rest-v1.signed', kind: 'short', expectedRevision: 3,
      preparation: [{ sourceInstanceId: 'ruleset:base', ruleId: 'extra', bindingId: 'option', inputs: {} }],
      moves: [{ selection: { sourceInstanceId: 'ruleset:base', ruleId: 'heal', bindingId: 'button', inputs: { amount: 2 } },
        options: [{ sourceInstanceId: 'ruleset:base', ruleId: 'care', bindingId: 'option', inputs: {} }] }],
      completion: [],
    });
    expect(shell.removed).toBe(true);
    expect(keyListeners).toHaveLength(0);
  });

  it('blocks a second move of the same group and sends ally moves to the chosen recipient', async () => {
    let submitted: { request: { moves: unknown[] } } | undefined;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json(offer());
      submitted = JSON.parse(String(init.body)) as typeof submitted;
      return Response.json({ actions: [] });
    }));
    await open();
    moveSelects()[0].choose('Prepare');
    const blocked = moveSelects()[1].all('option').find(item => item.text.startsWith('Prepare with party'))!;
    expect(blocked.text).toBe('Prepare with party (already chosen)');
    expect(blocked.disabled).toBe(true);
    moveSelects()[1].choose('Recover');
    const target = dialog().all('select').find(select => select.all('option')[0]?.text === 'Self')!;
    expect(target.all('option').map(item => item.text)).toEqual(['Self', 'Mira']);
    target.value = 'mira'; target.change();
    button('Take short rest').click();
    await settle();
    expect(submitted?.request.moves[1]).toEqual({
      selection: { sourceInstanceId: 'ruleset:base', ruleId: 'heal-ally', bindingId: 'button', inputs: {} },
      options: [], target: { recipientId: 'mira', version: 'mira-v1' } });
  });

  it('labels extra and additional move slots like format 1', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...offer(),
      plan: { moves: 3, longMoves: 1, adjustments: [{ field: 'moves', before: 2, after: 3, sourceName: 'Bard' }] },
      moves: [...offer().moves,
        { ...action('song', 'button', 'Song', { sourceInstanceId: 'card:bard' }), sourceName: 'Bard', freeSlot: true,
          restMove: { id: 'song', kind: 'any' }, options: [] },
        { ...action('project', 'button', 'Work on a project'), sourceName: 'Core', restMove: { id: 'project', kind: 'longMove' },
          options: [] }] })));
    await open();
    const text = dialog().text;
    expect(text).toContain('Each character gets 2 downtime moves');
    expect(text).toContain('Extra move (Bard)');
    expect(text).toContain('Additional move (Bard: Song)');
    expect(text).toContain('You may choose one long-rest move during this short rest.');
    moveSelects()[0].choose('Work on a project');
    expect(moveSelects()[1].all('option').find(item => item.text.startsWith('Work on a project'))!.text)
      .toBe('Work on a project (already chosen)');
  });

  it('refreshes the host preview when preparation changes', async () => {
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      urls.push(url);
      return Response.json(url.includes('preparation')
        ? { ...offer(), plan: { moves: 3, longMoves: 0 } } : offer());
    }));
    await open();
    await settle();
    expect(urls[1]).toContain('preparation=');
    expect(moveSelects()).toHaveLength(3);
    const toggle = dialog().all('input').find(input => input.checked)!;
    toggle.checked = false; toggle.change();
    await settle();
    expect(moveSelects()).toHaveLength(2);
    expect(urls).toHaveLength(2);
  });

  it('shows a preview error and disables the rest', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => Response.json(url.includes('preparation')
      ? { ...offer(), previewError: 'Not enough Hope.' } : offer())));
    await open();
    await settle();
    expect(dialog().text).toContain('Not enough Hope.');
    expect(button('Take short rest').disabled).toBe(true);
  });

  it('retries an uncertain response with the identical request', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), preparation: [] });
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({ actions: [] });
    }));
    await open();
    moveSelects()[0].choose('Prepare');
    button('Take short rest').click();
    await settle();
    expect(dialog().text).toContain('Connection lost.');
    expect(moveSelects()[0].disabled).toBe(true);
    button('Take short rest').click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(dialog().removed).toBe(true);
  });

  it('unlocks the plan after a rejected request so it can be corrected', async () => {
    let posts = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), preparation: [] });
      posts++;
      return posts === 1 ? Response.json({ error: 'Choose fewer moves.' }, { status: 409 }) : Response.json({ actions: [] });
    }));
    await open();
    button('Take short rest').click();
    await settle();
    expect(dialog().text).toContain('Choose fewer moves.');
    expect(moveSelects()[0].disabled).toBe(false);
  });

  it('applies the chosen completion options to the host stages without extra prompts', async () => {
    const calls: Array<{ url: string; body: Record<string, unknown> }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), preparation: [], postOutcomeChoices: true,
        completion: [action('benefit', 'option', 'Benefit'), action('reward', 'option', 'Reward')] });
      const request = JSON.parse(String(init.body)) as Record<string, unknown>;
      calls.push({ url, body: request });
      if (url.endsWith('/complete')) return Response.json({ actions: [] });
      if ('request' in request) return Response.json({ phase: 'beforeReset', choiceVersion: 'first', cleared: {},
        reports: [], actions: [action('benefit', 'option', 'Benefit')] });
      return Response.json({ phase: 'afterReset', choiceVersion: 'second', cleared: {}, reports: [],
        actions: [action('reward', 'option', 'Reward')] });
    }));
    await open();
    const benefit = dialog().all('input').find(input => !input.checked)!;
    benefit.checked = true; benefit.change();
    button('Take short rest').click();
    await settle();
    expect(calls.map(call => call.url.slice(call.url.lastIndexOf('/')))).toEqual(['/choices', '/choices', '/complete']);
    expect((calls[0].body.request as { completion: unknown[] }).completion).toEqual([]);
    const selection = { sourceInstanceId: 'ruleset:base', ruleId: 'benefit', bindingId: 'option', inputs: {} };
    expect(calls[1].body.selections).toEqual([selection]);
    expect(calls[2].body.choiceVersion).toBe('second');
    expect((calls[2].body.request as { completion: unknown[] }).completion).toEqual([selection]);
    expect(dialog().removed).toBe(true);
  });

  it('prompts only for stage choices that were not offered up front', async () => {
    const calls: Array<{ url: string; body: Record<string, unknown> }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), preparation: [], postOutcomeChoices: true, completion: [] });
      const request = JSON.parse(String(init.body)) as Record<string, unknown>;
      calls.push({ url, body: request });
      if (url.endsWith('/complete')) return Response.json({ actions: [] });
      if ('request' in request) return Response.json({ phase: 'beforeReset', choiceVersion: 'first', cleared: {},
        reports: ['Cleared 2 Stress.'], actions: [action('surprise', 'option', 'Surprise reward', { defaultOn: true })] });
      return Response.json({ phase: 'afterReset', choiceVersion: 'second', cleared: {}, reports: [], actions: [] });
    }));
    await open();
    button('Take short rest').click();
    await settle();
    expect(dialog().removed).toBe(false);
    expect(dialog().text).toContain('Cleared 2 Stress.');
    expect(dialog().text).toContain('Surprise reward');
    button('Take short rest').click();
    await settle();
    expect(calls[1].body.selections).toEqual([
      { sourceInstanceId: 'ruleset:base', ruleId: 'surprise', bindingId: 'option', inputs: {} }]);
    expect(dialog().removed).toBe(true);
  });

  it('prepares random rest preparation before choosing moves', async () => {
    const calls: Array<{ url: string; body: Record<string, unknown> }> = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer(), postOutcomeChoices: true, prepareBeforeMoves: true,
        moves: [], completion: [] });
      const request = JSON.parse(String(init.body)) as Record<string, unknown>;
      calls.push({ url, body: request });
      if ('prepare' in request) {
        if (calls.length === 1) throw new Error('Preparation response lost.');
        return Response.json({ ...offer(), postOutcomeChoices: true, preparation: [], completion: [],
          choiceVersion: 'prepared', plan: { moves: 3, longMoves: 0 }, reports: ['Preparation rolled 1.'] });
      }
      if (url.endsWith('/complete')) return Response.json({ actions: [] });
      return Response.json({ phase: 'preparedVersion' in request ? 'beforeReset' : 'afterReset',
        choiceVersion: 'next', cleared: {}, reports: [], actions: [] });
    }));
    await open();
    expect(moveSelects()).toHaveLength(0);
    button('Prepare rest').click();
    await settle();
    expect(dialog().text).toContain('Preparation response lost.');
    button('Prepare rest').click();
    await settle();
    expect(calls[1].body).toEqual(calls[0].body);
    expect(dialog().text).toContain('Preparation rolled 1.');
    expect(moveSelects()).toHaveLength(3);
    moveSelects()[0].choose('Prepare');
    button('Take short rest').click();
    await settle();
    const preparedRequest = calls[0].body.request as Record<string, unknown>;
    const movesRequest = calls[2].body.request as Record<string, unknown>;
    expect(calls[2].body.preparedVersion).toBe('prepared');
    expect(movesRequest.requestId).toBe(preparedRequest.requestId);
    expect(movesRequest.preparation).toEqual(preparedRequest.preparation);
    expect(movesRequest.moves).toHaveLength(1);
    expect(dialog().removed).toBe(true);
  });

  it('closes on Escape and keeps legacy hosts on the legacy dialog', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(offer())));
    await openAutomationRestDialog('hero', 'long', async () => {});
    expect(dialog().text).toContain('Long rest - downtime moves');
    expect(button('Take long rest')).toBeDefined();
    keyListeners[0]({ key: 'Escape' });
    expect(dialog().removed).toBe(true);
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationRestDialog('hero', 'long', async () => {})).toBe(false);
  });

  it('shows load failures in the legacy shell', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ error: 'Character not found.' }, { status: 409 })));
    expect(await openAutomationRestDialog('hero', 'short', async () => {})).toBe(true);
    expect(dialog().text).toContain('Character not found.');
    expect(button('Take short rest').disabled).toBe(true);
  });
});
