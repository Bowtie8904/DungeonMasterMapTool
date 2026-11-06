import { afterEach, describe, expect, it, vi } from 'vitest';
import { chooseWarlockAmount } from './warlock-view';
import { emptyCharacter } from './data';
import type { ActionDef } from './engine';

class Element {
  textContent = '';
  value = '';
  disabled = false;
  open = false;
  removed = false;
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(): void {}
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void { this.removed = true; }
  focus(): void {}
  select(): void {}
  all(tag: string): Element[] {
    return this.children.flatMap(child => typeof child === 'string' ? [] : [...(child.tag === tag ? [child] : []), ...child.all(tag)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  click(): void { if (!this.disabled) this.listeners.get('click')?.(); }
}

afterEach(() => vi.unstubAllGlobals());
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('Warlock amount dialogs', () => {
  it.each<ActionDef>([
    { id: 'use', label: 'Deathless Embrace', patronDice: 'heal' },
    { id: 'use', label: 'Deadly Vengeance', patronDice: 'vengeance' },
    { id: 'use', label: 'Otherworldly Ire', patronDice: 'ire' },
    { id: 'use', label: 'Diminish My Foes', spendFavor: true },
  ])('validates input, prevents double submission and retains errors for $label', async action => {
    const body = new Element('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag) });
    const c = emptyCharacter('Warlock');
    c.hp.slots = 6;
    c.resources = { favor: 3 };
    const submit = vi.fn().mockRejectedValueOnce(new Error('Host unavailable')).mockResolvedValueOnce(undefined);
    const closed = vi.fn();
    chooseWarlockAmount(c, action, submit, closed);
    const dialog = body.children[0] as Element;
    const amount = dialog.all('input')[0];
    const go = dialog.all('button').find(b => b.text() === action.label)!;
    expect(dialog.open).toBe(true);
    for (const invalid of ['', '0', '-1', '1.5', '999', 'NaN']) {
      amount.value = invalid;
      go.click();
      expect(submit).not.toHaveBeenCalled();
      expect(dialog.text()).toContain('Enter a whole number');
    }
    amount.value = '2';
    go.click(); go.click();
    expect(submit).toHaveBeenCalledTimes(1);
    await settle();
    expect(dialog.text()).toContain('Host unavailable');
    expect(dialog.open).toBe(true);
    expect(amount.value).toBe('2');
    expect(amount.disabled).toBe(false);
    go.click(); await settle();
    expect(submit).toHaveBeenCalledTimes(2);
    expect(submit).toHaveBeenLastCalledWith(2);
    expect(dialog.removed).toBe(true);
    expect(closed).toHaveBeenCalledOnce();
    const cancelSubmit = vi.fn();
    chooseWarlockAmount(c, action, cancelSubmit);
    const cancelDialog = body.children[1] as Element;
    cancelDialog.all('button').find(b => b.text() === 'Cancel')!.click();
    expect(cancelSubmit).not.toHaveBeenCalled();
    expect(c.resources.favor).toBe(3);
  });
});
