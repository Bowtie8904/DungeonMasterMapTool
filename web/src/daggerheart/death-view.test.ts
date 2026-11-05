import { afterEach, describe, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { openDeathDialog, openDeathRecovery, openScarsDialog } from './death-view';
import type { RollMessage } from '../protocol';

class Element {
  className = '';
  textContent = '';
  value = '';
  disabled = false;
  removed = false;
  isConnected = true;
  checked = false;
  max = '';
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
  get text(): string { return this.textContent + this.children.map(c => typeof c === 'string' ? c : c.text).join(''); }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  remove(): void { this.removed = true; }
  fire(event: string): unknown { return this.listeners.get(event)?.(); }
  all(tag: string): Element[] {
    return [...(this.tag === tag ? [this] : []), ...this.children.flatMap(c => typeof c === 'string' ? [] : c.all(tag))];
  }
}

function root(): HTMLElement {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  return new Element('root') as unknown as HTMLElement;
}

afterEach(() => vi.unstubAllGlobals());

describe('death-move dialogs', () => {
  it('only sends the confirmed choice, disables duplicates and displays host errors', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    const take = vi.fn().mockRejectedValueOnce(new Error('Host unavailable')).mockResolvedValueOnce(undefined);
    openDeathDialog(r, c, take);
    const box = r as unknown as Element;
    const select = box.all('select')[0];
    select.value = 'risk';
    select.fire('change');
    expect(box.text).toContain('Apply recovery manually on older hosts');
    expect(take).not.toHaveBeenCalled();
    const confirm = box.all('button').find(b => b.text === 'Confirm death move')!;
    const pending = confirm.fire('click');
    expect(confirm.disabled).toBe(true);
    await pending;
    expect(take).toHaveBeenCalledWith('risk');
    expect(box.text).toContain('Host unavailable');
    expect(confirm.disabled).toBe(false);
    await confirm.fire('click');
    expect(box.all('div')[0].removed).toBe(true);
  });

  it('cancels without taking a death move and rechecks HP before confirmation', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    const take = vi.fn();
    openDeathDialog(r, c, take);
    const box = r as unknown as Element;
    c.hp.on--;
    await box.all('button').find(b => b.text === 'Confirm death move')!.fire('click');
    expect(take).not.toHaveBeenCalled();
    expect(box.text).toContain('requires all HP');
    box.all('button').find(b => b.text === 'Cancel')!.fire('click');
    expect(box.all('div')[0].removed).toBe(true);
  });

  it('validates scar adjustments and makes healing an explicit GM-approved edit', () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.scars = 2;
    const changed = vi.fn();
    openScarsDialog(r, c, changed);
    const box = r as unknown as Element;
    const amount = box.all('input')[0];
    const apply = box.all('button').find(b => b.text === 'Apply GM-approved change')!;
    expect(box.text).toContain('Heal one only at the GM');
    for (const value of ['', '-1', '1.5', '7']) {
      amount.value = value;
      apply.fire('click');
      expect(c.scars).toBe(2);
      expect(changed).not.toHaveBeenCalled();
    }
    amount.value = '1';
    apply.fire('click');
    expect(c.scars).toBe(1);
    expect(changed).toHaveBeenCalledOnce();
  });

  it('loads authoritative alternatives and only sends relevant confirmed bonuses', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    c.hope.on = 3;
    const take = vi.fn().mockResolvedValue(undefined);
    const load = vi.fn().mockResolvedValue({
      options: [{ move: 'risk', label: 'Risk It All' }, { move: 'spectral', label: 'Spectral Form', disabled: 'Blocked for this trigger' }],
      bonuses: [{ key: 'blessed', label: 'Blessed', kind: 'hopeSpend', moves: ['risk'] }, { key: 'mask', label: 'Mask', kind: 'mask', moves: ['risk'], bonus: 1 }], undying: true,
    });
    openDeathDialog(r, c, take, load);
    const box = r as unknown as Element;
    const confirm = box.all('button').find(b => b.text === 'Confirm death move')!;
    expect(confirm.disabled).toBe(true);
    await Promise.resolve();
    const [spend, mask, wearing] = box.all('input');
    spend.value = '2';
    mask.checked = true;
    await confirm.fire('click');
    expect(take).not.toHaveBeenCalled();
    expect(box.text).toContain('Confirm you are wearing');
    wearing.checked = true;
    await confirm.fire('click');
    expect(take).toHaveBeenCalledWith('risk', { blessedHope: 2, mask: true, wearingMask: true });
  });

  it('shows authoritative disabled reasons and rejects unsupported action routes', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    const take = vi.fn();
    openDeathDialog(r, c, take, async () => ({ options: [{ move: 'spectral', label: 'Spectral Form', disabled: 'Already used for this trigger' }], bonuses: [], undying: false }));
    await Promise.resolve();
    const box = r as unknown as Element;
    expect(box.text).toContain('Already used for this trigger');
    await box.all('button').find(b => b.text === 'Confirm death move')!.fire('click');
    expect(take).not.toHaveBeenCalled();
    const old = root();
    openDeathDialog(old, c, take, undefined, 'egg');
    expect((old as unknown as Element).text).toContain('does not support expanded');
  });

  it('bounds recovery by budget and nonpermanent marks and sends critical amounts', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    c.permanentHp = 2;
    c.stress.on = 3;
    const roll = { deathResult: { outcome: 'hope', budget: 5, claimed: false } } as RollMessage;
    const submit = vi.fn().mockResolvedValue(undefined);
    openDeathRecovery(r, c, roll, submit);
    const box = r as unknown as Element;
    const [hp, , stress] = box.all('input');
    hp.value = '5'; stress.value = '1';
    const apply = box.all('button').find(b => b.text === 'Apply recovery')!;
    await apply.fire('click');
    expect(submit).not.toHaveBeenCalled();
    hp.value = '3'; stress.value = '3';
    await apply.fire('click');
    expect(submit).not.toHaveBeenCalled();
    hp.value = '2';
    await apply.fire('click');
    expect(submit).toHaveBeenCalledWith({ hp: 2, stress: 3 });
    const critical = root();
    roll.deathResult!.outcome = 'crit';
    openDeathRecovery(critical, c, roll, submit);
    await (critical as unknown as Element).all('button').find(b => b.text === 'Clear all HP and Stress')!.fire('click');
    expect(submit).toHaveBeenLastCalledWith({ hp: 4, stress: 3 });
  });

  it('confirms undying permanent cost without sending recovery and handles host failure', async () => {
    const r = root();
    const c = emptyCharacter('Aria');
    c.hp.on = c.hp.slots = 6;
    const submit = vi.fn().mockRejectedValue(new Error('Already resolved'));
    openDeathRecovery(r, c, { deathResult: { outcome: 'fear', undying: true, claimed: false } } as RollMessage, submit);
    const box = r as unknown as Element;
    expect(box.text).toContain('can never be healed');
    await box.all('button').find(b => b.text === "Confirm Won't Stay Dead")!.fire('click');
    expect(submit).toHaveBeenCalledWith({ undying: true });
    expect(box.text).toContain('Already resolved');
  });
});
