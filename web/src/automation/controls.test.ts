import { afterEach, describe, expect, it, vi } from 'vitest';
import { ControlText, RuleDescriptor, RuleIntent, ruleControl } from './controls';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<() => void>>();
  textContent = '';
  value = '';
  disabled = false;
  checked = false;
  type = '';
  open = false;
  removed = false;
  constructor(readonly tagName: string) {}
  get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
  append(...children: Array<Element | string>): void {
    this.children.push(...children.map(child => {
      if (typeof child !== 'string') return child;
      const node = new Element('#text'); node.textContent = child; return node;
    }));
  }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => void): void {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), handler]);
  }
  click(): void { if (!this.disabled) this.listeners.get('click')?.forEach(handler => handler()); }
  close(): void { this.open = false; this.listeners.get('close')?.forEach(handler => handler()); }
  showModal(): void { this.open = true; }
  focus(): void {}
  remove(): void { this.removed = true; }
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const text: ControlText = {
  cancel: 'Cancel', retry: 'Retry', pending: 'Applying', failed: 'Action failed',
  wholeNumber: (min, max) => `Choose a whole number from ${min} to ${max}.`,
  selectDice: count => `Choose ${count} dice.`,
};
const descriptor: RuleDescriptor = {
  sourceId: 'custom:rule', sourceInstanceId: 'instance-1', ruleId: 'continue', bindingId: 'result',
  revision: 3, surface: 'chat', mode: 'button', available: true, label: '<b>Renamed feature</b>',
};

function dom(): Element {
  const body = new Element('body');
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), body });
  vi.stubGlobal('crypto', { getRandomValues: (values: Uint32Array) => values.fill(1) });
  return body;
}

async function settle(): Promise<void> { await Promise.resolve(); await Promise.resolve(); }
function asElement(element: HTMLElement): Element { return element as unknown as Element; }
afterEach(() => vi.unstubAllGlobals());

describe('generic rule controls', () => {
  it('submits immediately without a confirmation when no choices are needed', async () => {
    const body = dom();
    const sent: RuleIntent[] = [];
    const root = asElement(ruleControl(descriptor, async intent => { sent.push(intent); }, text));
    expect(root.text).toContain('<b>Renamed feature</b>');
    expect(root.all('b')).toHaveLength(0);
    const button = root.all('button')[0];
    button.click(); button.click();
    await settle();
    expect(body.all('dialog')).toHaveLength(0);
    expect(sent).toEqual([{ requestId: '00000001000000010000000100000001', sourceId: 'custom:rule', sourceInstanceId: 'instance-1',
      ruleId: 'continue', bindingId: 'result', expectedRevision: 3, inputs: {} }]);
    expect(button.disabled).toBe(true);
  });

  it('allows cancellation and enforces a distinct die count before submission', async () => {
    const body = dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, inputs: [{
      id: 'remove', kind: 'die.select', label: 'Choose two', count: 2, default: ['die-a'],
      choices: [{ id: 'die-a', label: 'd8: 3', sides: 8, face: 3 }, { id: 'die-b', label: 'd6: 3', sides: 6, face: 3 }],
    }] }, submit, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    dialog.all('button')[1].click();
    expect(dialog.text).toContain('Choose 2 dice.');
    expect(submit).not.toHaveBeenCalled();
    dialog.all('button')[0].click();
    expect(dialog.removed).toBe(true);
    root.all('button')[0].click();
    const reopened = body.all('dialog')[1];
    reopened.all('input')[1].checked = true;
    reopened.all('button')[1].click();
    await settle();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ inputs: { remove: ['die-a', 'die-b'] } }));
  });

  it('retains the request ID and inputs after an uncertain failure', async () => {
    const body = dom();
    const sent: RuleIntent[] = [];
    const root = asElement(ruleControl({ ...descriptor, inputs: [{ id: 'amount', kind: 'integer', label: 'Amount', min: 1, max: 3 }] },
      async intent => {
        sent.push(intent);
        if (sent.length === 1) throw new Error('Connection interrupted');
      }, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    dialog.all('input')[0].value = '2';
    dialog.all('button')[1].click();
    await settle();
    expect(dialog.text).toContain('Connection interrupted');
    expect(dialog.all('input')[0].disabled).toBe(true);
    dialog.all('input')[0].value = '3';
    dialog.all('button')[1].click();
    await settle();
    expect(sent).toHaveLength(2);
    expect(sent[1]).toEqual(sent[0]);
    expect(sent[1].inputs.amount).toBe(2);
    expect(dialog.removed).toBe(true);
  });

  it('shows why a button is disabled and never independently submits a roll option', () => {
    dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, available: false, reason: 'No uses remain.' }, submit, text));
    expect(root.text).toContain('No uses remain.');
    root.all('button')[0].click();
    expect(submit).not.toHaveBeenCalled();
    expect(() => ruleControl({ ...descriptor, mode: 'option' }, submit, text)).toThrow('enclosing');
  });

  it('rejects fractional and out-of-range quantities without sending an intent', async () => {
    const body = dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, inputs: [
      { id: 'amount', kind: 'integer', label: 'Amount', min: 1, max: 3 },
    ] }, submit, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    for (const invalid of ['', '0', '1.5', '4']) {
      dialog.all('input')[0].value = invalid;
      dialog.all('button')[1].click();
      expect(submit).not.toHaveBeenCalled();
      expect(dialog.text).toContain('Choose a whole number from 1 to 3.');
    }
    dialog.all('input')[0].value = '3';
    dialog.all('button')[1].click();
    await settle();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ inputs: { amount: 3 } }));
  });

  it('sends the stable per-die identity rather than relying on its face or array position', async () => {
    dom();
    const sent: RuleIntent[] = [];
    const root = asElement(ruleControl({ ...descriptor, eachId: 'd17' }, async intent => { sent.push(intent); }, text));
    root.all('button')[0].click();
    await settle();
    expect(sent[0].eachId).toBe('d17');
  });
});
