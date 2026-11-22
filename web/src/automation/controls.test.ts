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
  recipient: 'Choose a character', recipients: 'Recipients',
  wholeNumber: (min, max) => `Choose a whole number from ${min} to ${max}.`,
  selectDice: (count, min) => min === undefined ? `Choose ${count} dice.` : `Choose ${min} to ${count} dice.`,
  selectOptions: count => `Choose ${count} option(s).`,
  allocate: (total, max) => `Distribute exactly ${total}, at most ${max} each.`,
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

  it('collects the versioned recipient alongside rule inputs and retries that exact choice', async () => {
    const body = dom();
    const submit = vi.fn(async (_intent: RuleIntent) => {}).mockRejectedValueOnce(new Error('Lost response'));
    const root = asElement(ruleControl({ ...descriptor, targetSelection: { cardinality: 'one', options: [
      { id: 'a', label: 'Ada', version: 'a1' }, { id: 'b', label: 'Bea', version: 'b2' },
    ] }, inputs: [{ kind: 'integer', id: 'amount', label: 'Amount', min: 1, max: 3 }] }, submit, text));
    root.all('button')[0].click();
    const cancelled = body.all('dialog')[0];
    cancelled.all('button')[0].click();
    expect(submit).not.toHaveBeenCalled();
    root.all('button')[0].click();
    const dialog = body.all('dialog')[1];
    expect(dialog.all('legend')[0].text).toBe(text.recipient);
    dialog.all('button')[1].click();
    expect(dialog.text).toContain('Choose 1 option(s).');
    const [first, second, amount] = dialog.all('input');
    second.checked = true;
    amount.value = '2';
    dialog.all('button')[1].click();
    await settle();
    expect(submit.mock.calls[0][0]).toMatchObject({ target: { recipientId: 'b', version: 'b2' }, inputs: { amount: 2 } });
    expect(second.disabled).toBe(true);
    first.checked = true;
    amount.value = '1';
    dialog.all('button')[1].click();
    await settle();
    expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
    expect(dialog.removed).toBe(true);
  });

  it('accepts any die count between the minimum and the maximum', async () => {
    const body = dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, inputs: [{
      id: 'reroll', kind: 'die.select', label: 'Reroll', count: 2, min: 1,
      choices: [{ id: 'die-a', label: 'd8: 3', sides: 8, face: 3 }, { id: 'die-b', label: 'd6: 3', sides: 6, face: 3 }],
    }] }, submit, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    dialog.all('button')[1].click();
    expect(dialog.text).toContain('Choose 1 to 2 dice.');
    dialog.all('input')[1].checked = true;
    dialog.all('button')[1].click();
    await settle();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ inputs: { reroll: ['die-b'] } }));
  });

  it('prefills an integer input with its declared default', () => {
    const body = dom();
    const root = asElement(ruleControl({ ...descriptor, inputs: [{ id: 'amount', kind: 'integer', label: 'Amount', min: 0, max: 20, default: 2 }] }, async () => {}, text));
    root.all('button')[0].click();
    expect(body.all('dialog')[0].all('input')[0].value).toBe('2');
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

  it('renders single choices as radios submitting one ID and multi choices as an exact-size list', async () => {
    const body = dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, inputs: [
      { id: 'stance', kind: 'choice', label: 'Stance', count: 1, options: [{ id: 'guard', label: 'Guard' }, { id: 'focus', label: 'Focus' }] },
      { id: 'traits', kind: 'choice', label: 'Traits', count: 2,
        options: [{ id: 'agility', label: 'Agility' }, { id: 'strength', label: 'Strength' }, { id: 'finesse', label: 'Finesse' }] },
    ] }, submit, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    const inputs = dialog.all('input');
    expect(inputs.map(input => input.type)).toEqual(['radio', 'radio', 'checkbox', 'checkbox', 'checkbox']);
    inputs[1].checked = true;
    inputs[2].checked = true;
    dialog.all('button')[1].click();
    expect(dialog.text).toContain('Choose 2 option(s).');
    expect(submit).not.toHaveBeenCalled();
    inputs[4].checked = true;
    dialog.all('button')[1].click();
    await settle();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ inputs: { stance: 'focus', traits: ['agility', 'finesse'] } }));
  });

  it('allocates an exact total with per-option maxima and omits zero entries', async () => {
    const body = dom();
    const submit = vi.fn(async () => {});
    const root = asElement(ruleControl({ ...descriptor, inputs: [
      { id: 'split', kind: 'allocation', label: 'Distribute', total: 3, max: 2,
        options: [{ id: 'armor', label: 'Armor' }, { id: 'hope', label: 'Hope' }, { id: 'stress', label: 'Stress' }] },
    ] }, submit, text));
    root.all('button')[0].click();
    const dialog = body.all('dialog')[0];
    const inputs = dialog.all('input');
    expect(inputs.map(input => input.type)).toEqual(['number', 'number', 'number']);
    for (const [armor, hope] of [['3', '0'], ['1', '1'], ['1.5', '1.5']]) {
      inputs[0].value = armor;
      inputs[1].value = hope;
      dialog.all('button')[1].click();
      expect(submit).not.toHaveBeenCalled();
      expect(dialog.text).toContain('Distribute exactly 3, at most 2 each.');
    }
    inputs[0].value = '2';
    inputs[1].value = '0';
    inputs[2].value = '1';
    dialog.all('button')[1].click();
    await settle();
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ inputs: { split: { armor: 2, stress: 1 } } }));
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
