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
  type = '';
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
  remove(): void { this.closed = true; }
  // The roll dialog is the legacy backdrop div; tests address it as 'dialog'.
  all(tag: string): Element[] {
    const match = this.tagName === tag || tag === 'dialog' && this.className.includes('dh-rolldlg-backdrop');
    return [...(match ? [this] : []), ...this.children.flatMap(child => child.all(tag))];
  }
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
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    addEventListener: () => {}, removeEventListener: () => {} });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-authored ordinary checks', () => {
  it('shows the format-1 heading, applied bonuses, option values and the host preview', async () => {
    const urls: string[] = [];
    const manual: RuleDescriptor = { ...option, sourceId: 'ruleset:base', sourceInstanceId: 'base',
      ruleId: 'manual-roll-mode', bindingId: 'use', label: 'Override roll mode', defaultOn: false, inputs: [] };
    const boost = { ...option, sourceInstanceId: 'boost-1', ruleId: 'boost', label: 'Boost', cost: '1 Hope', inputs: [],
      value: 2, dice: ['1d6'], suggestsMode: 'advantage' };
    const base = { modifier: 3, mode: 'normal', dice: [], applied: ['Bless +1'] };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      urls.push(url);
      const selections = new URL(url, 'http://host').searchParams.get('selections');
      return Response.json({ ...offer, options: [manual, boost], base,
        ...(selections ? { preview: { modifier: 5, mode: 'advantage', dice: ['1d6'], applied: ['Bless +1', 'Boost +2'] } } : {}) });
    }));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {}, undefined, 'Agility (+2)');
    const dialog = body.all('dialog')[0];
    expect(dialog.all('h4')[0].text).toBe('Agility (+3)');
    expect(dialog.text).toContain('Applied: Bless +1.');
    expect(dialog.text).toContain('Boost (+2) +1d6; costs 1 Hope when selected');
    expect(dialog.text).toContain('Modifier +3');
    await new Promise(resolve => setTimeout(resolve, 200));
    expect(JSON.parse(new URL(urls[1], 'http://host').searchParams.get('selections')!)).toEqual([
      { sourceInstanceId: 'boost-1', ruleId: 'boost', bindingId: 'roll', inputs: {} }]);
    expect(dialog.text).toContain('Modifier +5; extra dice 1d6');
    expect(dialog.all('button').find(button => button.text === 'Advantage (+d6)')!.className).toContain(' on');
  });
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
    dialog.all('button').find(button => button.text === 'Roll to chat')!.click();
    await settle();
    expect(submitted).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 3, checkRoll: { kind: 'action', trait: 'Agility' },
      selections: [{ sourceInstanceId: 'focus-1', ruleId: 'focus', bindingId: 'roll', inputs: { amount: 2 } }],
    } });
    expect(calls).toEqual(['flush', 'get', 'post']);
    expect(dialog.closed).toBe(true);
  });

  it('submits the chosen Experience as a host-described roll option', async () => {
    let submitted: unknown;
    const experience: RuleDescriptor = {
      ...option, sourceId: 'ruleset:base', sourceInstanceId: 'base', ruleId: 'experience',
      bindingId: 'use', label: 'Use an Experience', defaultOn: false,
      inputs: [{ id: 'experience', kind: 'record.select', label: 'Choose an Experience', options: [
        { label: 'Giant talk (+2)', value: { id: 'Giant talk', name: 'Giant talk', mod: 2 } },
        { label: 'Giant talk (+3)', value: { id: 'Giant talk', name: 'Giant talk', mod: 3 } },
      ] }],
    };
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer, options: [experience] });
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {});
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Giant talk (+3)');
    const select = dialog.all('select')[0];
    select.value = '1';
    dialog.all('button').find(button => button.text === 'Roll to chat')!.click();
    await settle();
    expect(submitted).toMatchObject({ request: { selections: [{
      sourceInstanceId: 'base', ruleId: 'experience', bindingId: 'use',
      inputs: { experience: { id: 'Giant talk', name: 'Giant talk', mod: 3 } },
    }] } });
  });

  it('shows Adept as a format-1 checkbox that doubles the Experience chosen in the dropdown', async () => {
    let submitted: unknown;
    const records = [{ label: 'Giant talk (+2)', value: { id: 'Giant talk', name: 'Giant talk', mod: 2 } }];
    const experience: RuleDescriptor = { ...option, sourceId: 'ruleset:base', sourceInstanceId: 'base', ruleId: 'experience',
      bindingId: 'use', label: 'Use an Experience', defaultOn: false, cost: '1 Hope', exclusiveGroup: 'core-experience',
      inputs: [{ id: 'experience', kind: 'record.select', label: 'Choose an Experience', options: records }] };
    const adept: RuleDescriptor = { ...experience, sourceId: 'feature:adept', sourceInstanceId: 'adept-1', ruleId: 'adept',
      bindingId: 'roll', label: 'Adept', cost: '1 Stress', sourceName: 'Adept' };
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer, options: [experience, adept] });
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {});
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Experience (1 Hope, or 1 Stress with Adept)');
    expect(dialog.all('fieldset').flatMap(set => set.all('fieldset'))).toHaveLength(1);
    const box = dialog.all('input').find(input => input.type === 'checkbox')!;
    box.checked = true;
    box.listeners.get('change')?.forEach(handler => handler());
    const roll = dialog.all('button').find(button => button.text === 'Roll to chat')!;
    roll.click();
    await settle();
    expect(submitted).toBeUndefined();
    expect(dialog.text).toContain('Select an Experience to use Adept');
    dialog.all('select')[0].value = '0';
    roll.disabled = false;
    roll.click();
    await settle();
    expect(submitted).toMatchObject({ request: { selections: [{ sourceInstanceId: 'adept-1', ruleId: 'adept',
      inputs: { experience: { id: 'Giant talk', name: 'Giant talk', mod: 2 } } }] } });
  });

  it('names options by source and shows per-token costs, quantities and consumed items like format 1', async () => {
    const tide: RuleDescriptor = { ...option, sourceInstanceId: 'tide-1', ruleId: 'tide', label: 'Spend Tide token',
      sourceName: 'Tidecaller', defaultOn: false, unitCost: '1 Tide', costPer: 'token',
      inputs: [{ id: 'quantity', kind: 'integer', label: 'Tide tokens', min: 1, max: 3 }] };
    const potion: RuleDescriptor = { ...option, sourceInstanceId: 'potion-1', ruleId: 'drink', label: 'Drink',
      sourceName: 'Minor Stamina Potion', defaultOn: false, inputs: [], consumes: { name: 'Minor Stamina Potion', left: 2 } };
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...offer, options: [
      { ...tide, value: 1 }, { ...potion, dice: ['1d4'] }] })));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {}, undefined, 'Agility',
      descriptor => `${descriptor.sourceName} - ${descriptor.label}`);
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Tidecaller - Spend Tide token (+1 per token); costs 1 Tide per token when selected');
    expect(dialog.text).toContain('Minor Stamina Potion - Drink +1d4; uses up 1 Minor Stamina Potion (2 left)');
    const control = dialog.all('label').find(label => label.className === 'dh-roll-option-control')!;
    expect(control.hidden).toBe(false);
    expect(control.text).toContain('Tide tokens');
  });

  it('submits a manual roll mode through the generic choice control', async () => {
    let submitted: unknown;
    const manual: RuleDescriptor = {
      ...option, sourceId: 'ruleset:base', sourceInstanceId: 'base', ruleId: 'manual-roll-mode',
      bindingId: 'use', label: 'Override roll mode', defaultOn: false,
      inputs: [{ id: 'mode', kind: 'choice', label: 'Roll mode', count: 1, options: [
        { id: 'normal', label: 'Normal' }, { id: 'advantage', label: 'Advantage (+d6)' },
        { id: 'disadvantage', label: 'Disadvantage (-d6)' },
      ] }],
    };
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer, options: [manual] });
      submitted = JSON.parse(String(init.body));
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {});
    const dialog = body.all('dialog')[0];
    expect(dialog.text).toContain('Disadvantage (-d6)');
    dialog.all('button').find(button => button.text === 'Disadvantage (-d6)')!.click();
    dialog.all('button').find(button => button.text === 'Roll to chat')!.click();
    await settle();
    expect(submitted).toMatchObject({ request: { selections: [{
      sourceInstanceId: 'base', ruleId: 'manual-roll-mode', bindingId: 'use', inputs: { mode: 'disadvantage' },
    }] } });
  });

  it('retains the exact intent after an uncertain submission', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init.body) return Response.json({ ...offer, label: 'Finesse Reaction' });
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'reaction', 'Finesse', 'Reaction', async () => {}, { id: 45, ts: 2000 });
    const dialog = body.all('dialog')[0], go = dialog.all('button').find(button => button.text === 'Roll to chat')!;
    expect(dialog.text).not.toContain('Roll type');
    go.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    expect(dialog.all('input').every(input => input.disabled)).toBe(true);
    go.click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(JSON.parse(requests[0]).request.checkRoll).toEqual({ kind: 'reaction', trait: 'Finesse', prompt: { id: 45, ts: 2000 } });
    expect(dialog.closed).toBe(true);
  });

  it('reloads host options when switching kind and locks the kind for an uncertain retry', async () => {
    const calls: string[] = [], requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init.body) {
        calls.push(url);
        return Response.json(url.includes('kind=reaction')
          ? { revision: 4, label: 'Agility Reaction', options: [] } : offer);
      }
      requests.push(String(init.body));
      if (requests.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    await openAutomationCheckDialog('hero', 'action', 'Agility', 'Agility', async () => {});
    const dialog = body.all('dialog')[0];
    const reaction = dialog.all('button').find(button => button.text === 'Reaction roll')!;
    reaction.click();
    expect(reaction.disabled).toBe(true);
    await settle();
    expect(calls).toEqual([
      `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/check?kind=action&trait=Agility`,
      `/api/automation/${AUTOMATION_COMPATIBILITY_VERSION}/hero/check?kind=reaction&trait=Agility`,
    ]);
    expect(dialog.text).not.toContain('Focus');
    const go = dialog.all('button').find(button => button.text === 'Roll to chat')!;
    go.click();
    await settle();
    const action = dialog.all('button').find(button => button.text === 'Normal roll')!;
    expect(action.disabled).toBe(true);
    expect(reaction.disabled).toBe(true);
    action.click();
    go.click();
    await settle();
    expect(requests).toEqual([requests[0], requests[0]]);
    expect(JSON.parse(requests[0]).request).toMatchObject({
      expectedRevision: 4, checkRoll: { kind: 'reaction', trait: 'Agility' }, selections: [],
    });
    expect(calls).toHaveLength(2);
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
    body.all('button').find(button => button.text === 'Cancel')!.click();
    finish();
    expect(await opening).toBe(true);
  });
});
