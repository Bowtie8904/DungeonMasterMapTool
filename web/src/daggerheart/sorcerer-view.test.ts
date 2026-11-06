import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { chooseChannelCard, chooseFeatureEffects, chooseRollModification } from './sorcerer-view';
import { emptyCharacter } from './data';
import type { RollMessage } from '../protocol';
import type { CompendiumFile } from './compendium';
import { renderSheetView } from './view';

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  checked = false;
  disabled = false;
  hidden = false;
  open = false;
  removed = false;
  ariaLabel = '';
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: (name: string) => this.className.split(' ').includes(name) };
  dataset: Record<string, string> = {};
  attributes: Record<string, string> = {};
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void { this.removed = true; }
  focus(): void {}
  querySelector(): null { return null; }
  querySelectorAll(selector: string): Element[] {
    const tags = selector.split(',');
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(tags.includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  click(): void { if (!this.disabled) this.listeners.get('click')?.(); }
}

afterEach(() => vi.unstubAllGlobals());

function setup() {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  return body;
}

const roll: RollMessage = { id: 1, ts: 1, fromId: 'player', from: 'Player', source: 'damage,magicdamage',
  expression: '2d8+1d4+3', rolls: [2, 6, 4], total: 15 };
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('Warlock feature controls', () => {
  it('renders one Favor resource and the standard blue Boon button without Favor feature actions', () => {
    setup();
    const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
    const c = emptyCharacter('Warlock');
    c.className = 'Warlock';
    c.mancer = { done: true };
    c.hope.on = 3;
    c.features = catalog.entries.find(e => e.id === 'warlock')!.features!
      .map(f => ({ ...structuredClone(f), source: 'Class feature - Warlock' }));
    const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
    expect(root.querySelectorAll('input').filter(i => i.ariaLabel === 'Favor')).toHaveLength(1);
    const buttons = root.querySelectorAll('button');
    const boon = buttons.find(b => b.text() === 'Patron’s Boon (3 Hope)')!;
    expect(boon.className).toContain('primary');
    expect(boon.disabled).toBe(false);
    expect(buttons.some(b => /tribute|gain Favor|replace gained Hope/i.test(b.text()))).toBe(false);
    boon.click();
    expect(c.hope.on).toBe(0);
  });
});

describe('Sorcerer selection dialogs', () => {
  it('groups the long-rest counter with manual swap, not unlimited Help, and disables only swap after use', () => {
    setup();
    const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
    const c = emptyCharacter('Sorcerer');
    c.mancer = { done: true };
    c.features = catalog.entries.find(e => e.id === 'sorcerer-primal-origin-specialization')!.features!
      .map(f => ({ ...f, source: 'Subclass specialization - Primal Origin' }));
    const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
    const group = root.querySelectorAll('span').find(e => e.ariaLabel === 'Enchanted Aid limited actions')!;
    expect(group.text()).toContain('1/1 per long rest');
    expect(group.text()).not.toContain('Help an Ally');
    const manual = group.querySelectorAll('button').find(b => b.text() === 'Swap Duality dice (manual)')!;
    expect(manual.disabled).toBe(false);
    manual.click();
    expect(c.usage['feat:Enchanted Aid'].used).toBe(1);
    const refreshed = root.querySelectorAll('span').find(e => e.ariaLabel === 'Enchanted Aid limited actions')!;
    expect(refreshed.text()).toContain('0/1 per long rest');
    expect(refreshed.querySelectorAll('button')[0].disabled).toBe(true);
    expect(root.querySelectorAll('button').find(b => b.text() === 'Help an Ally: d8')!.disabled).toBe(false);
  });

  it('renders all individual dice, selects multiple rerolls and keeps errors inline without paying on cancel', async () => {
    const body = setup();
    const submit = vi.fn().mockRejectedValueOnce(new Error('Not enough Hope')).mockResolvedValueOnce(undefined);
    const closed = vi.fn();
    chooseRollModification(roll, { id: 'reroll', label: 'Volatile Magic', on: 'damage', damageDice: 'reroll' }, submit, closed);
    const dialog = body.children[0] as Element;
    expect(dialog.open).toBe(true);
    const buttons = dialog.querySelectorAll('button');
    const dice = buttons.filter(b => b.className.includes('roll-die'));
    expect(dice.map(d => d.text())).toEqual(['d82', 'd86', 'd44']);
    const apply = buttons.find(b => b.text() === 'Apply')!;
    expect(apply.disabled).toBe(true);
    dice[0].click(); dice[2].click();
    expect(dice.map(d => d.attributes['aria-pressed'])).toEqual(['true', 'false', 'true']);
    apply.click();
    await settle();
    expect(submit).toHaveBeenLastCalledWith({ dice: [0, 2], expectedDice: [2, 6, 4] });
    expect(dialog.text()).toContain('Not enough Hope');
    expect(dialog.open).toBe(true);
    apply.click();
    await settle();
    expect(dialog.removed).toBe(true);
    expect(closed).toHaveBeenCalledOnce();
    const cancelSubmit = vi.fn();
    chooseRollModification(roll, { id: 'double', label: 'Manipulate Magic', on: 'damage', damageDice: 'double' }, cancelSubmit, closed);
    const cancelDialog = body.children[1] as Element;
    cancelDialog.querySelectorAll('button').find(b => b.text() === 'Cancel')!.click();
    expect(cancelSubmit).not.toHaveBeenCalled();
  });

  it('allows exactly one doubled die and sends its original index', async () => {
    const body = setup();
    const submit = vi.fn().mockResolvedValue(undefined);
    chooseRollModification(roll, { id: 'double', label: 'Manipulate Magic', on: 'damage', damageDice: 'double' }, submit, () => {});
    const dialog = body.children[0] as Element;
    const dice = dialog.querySelectorAll('button').filter(b => b.className.includes('roll-die'));
    dice[0].click(); dice[2].click();
    expect(dice.map(d => d.attributes['aria-pressed'])).toEqual(['false', 'false', 'true']);
    dialog.querySelectorAll('button').find(b => b.text() === 'Apply')!.click();
    await settle();
    expect(submit).toHaveBeenCalledWith({ dice: [2], expectedDice: [2, 6, 4] });
  });

  it('keeps the dice snapshot from dialog open so the host can reject stale selections', async () => {
    const body = setup();
    const submit = vi.fn().mockResolvedValue(undefined);
    const changed = { ...structuredClone(roll), rolls: [2, 6, 4] };
    chooseRollModification(changed, { id: 'double', label: 'Manipulate Magic', on: 'damage', damageDice: 'double' }, submit, () => {});
    changed.rolls[0] = 8;
    const dialog = body.children[0] as Element;
    dialog.querySelectorAll('button').find(b => b.className.includes('roll-die'))!.click();
    dialog.querySelectorAll('button').find(b => b.text() === 'Apply')!.click();
    await settle();
    expect(submit).toHaveBeenCalledWith({ dice: [0], expectedDice: [2, 6, 4] });
  });

  it('offers the three action benefits and submits only the selected choice', async () => {
    const body = setup();
    const submit = vi.fn().mockResolvedValue(undefined);
    chooseRollModification(roll, { id: 'action', label: 'Manipulate Magic', on: 'action', choices: [
      { id: 'reach', label: 'Extend reach by one range' }, { id: 'bonus', label: '+2 to this roll', addDice: '2' },
      { id: 'target', label: 'Hit an additional target within range' },
    ] }, submit, () => {});
    const dialog = body.children[0] as Element;
    const apply = dialog.querySelectorAll('button').find(b => b.text() === 'Apply')!;
    expect(apply.disabled).toBe(true);
    const choices = dialog.querySelectorAll('input');
    expect(choices.map(c => c.value)).toEqual(['reach', 'bonus', 'target']);
    choices[1].listeners.get('change')?.();
    apply.click(); await settle();
    expect(submit).toHaveBeenCalledWith({ choiceId: 'bonus' });
  });

  it('offers active domain cards as selectable cards with separate scaled Hope and damage buttons', async () => {
    const body = setup();
    const c = emptyCharacter('Sorcerer');
    c.loadout = [{ name: 'Active spell', level: 4, domain: 'Arcana', type: 'Spell', recall: 1, text: 'Magic' }];
    c.vault = [{ ...c.loadout[0], name: 'Vault spell' }];
    const submit = vi.fn();
    chooseChannelCard(c, submit);
    const dialog = body.children[0] as Element;
    expect(dialog.text()).not.toContain('Vault spell');
    dialog.querySelectorAll('button').find(b => b.className === 'dcard')!.click();
    expect(dialog.text()).toContain('Gain 4 Hope');
    dialog.querySelectorAll('button').find(b => b.text() === 'Prepare +8 damage')!.click();
    await settle();
    expect(submit).toHaveBeenCalledWith('Active spell', 'damage');
  });

  it('requires exactly two Transcendence choices and an explicit trait when chosen', async () => {
    const body = setup();
    const submit = vi.fn();
    chooseFeatureEffects({ id: 'use', label: 'Transcendence', chooseEffects: { count: 2, options: [
      { id: 'evasion', label: '+2 Evasion', effect: { name: 'Evasion', kind: 'buff' } },
      { id: 'trait', label: '+1 trait', chooseTrait: true, effect: { name: 'Trait', kind: 'buff' } },
      { id: 'proficiency', label: '+1 Proficiency', effect: { name: 'Proficiency', kind: 'buff' } },
    ] } }, submit);
    const dialog = body.children[0] as Element;
    const checks = dialog.querySelectorAll('input');
    for (const check of checks.slice(0, 2)) { check.checked = true; check.listeners.get('change')?.(); }
    const apply = dialog.querySelectorAll('button').find(b => b.text() === 'Apply')!;
    expect(apply.disabled).toBe(true);
    const trait = dialog.querySelectorAll('select')[0];
    trait.value = 'Presence'; trait.listeners.get('change')?.();
    expect(apply.disabled).toBe(false);
    checks[2].checked = true; checks[2].listeners.get('change')?.();
    expect(apply.disabled).toBe(true);
    checks[2].checked = false; checks[2].listeners.get('change')?.();
    apply.click(); await settle();
    expect(submit).toHaveBeenCalledWith(['evasion', 'trait'], 'Presence');
  });
});
