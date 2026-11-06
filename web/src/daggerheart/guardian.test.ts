import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import type { CompendiumFile } from './compendium';
import {
  applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, effectCounterMax, effectCounterText,
  derivedStats, rollBonus, runActionDetailed, selectedRollBonus, syncDerived,
} from './engine';
import { availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';
import { renderSheetView } from './view';
import { openDamageDialog } from './damage-view';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function guardian(level = 1) {
  const c = emptyCharacter('Guardian');
  c.className = 'Guardian';
  c.subclass = 'Vengeance';
  c.level = level;
  c.hope.on = 6;
  c.hp.slots = 6;
  c.features = catalog.entries.filter(e => e.id === 'guardian' || e.id.startsWith('guardian-vengeance-'))
    .flatMap(e => (e.features ?? []).map(f => ({
      ...structuredClone(f), source: e.kind === 'class' ? 'Class feature - Guardian' : `Subclass ${e.stage} - Vengeance`,
    })));
  syncDerived(c);
  return c;
}

function activate(c: ReturnType<typeof guardian>, name: string) {
  const a = c.features.find(f => f.name === name)!.automation!;
  return runActionDetailed(c, `feat:${name}`, a, a.actions![0], name);
}

class Element {
  className = '';
  textContent = '';
  ariaLabel = '';
  value = '';
  checked = false;
  disabled = false;
  style = { cssText: '', setProperty: () => {} };
  dataset = {};
  children: Array<Element | string> = [];
  classList = {
    toggle: () => {}, add: () => {},
    contains: (name: string) => this.className.split(' ').includes(name),
  };
  listeners = new Map<string, () => void>();
  constructor(readonly tag: string) {}
  get text(): string { return this.textContent + this.children.map(c => typeof c === 'string' ? c : c.text).join(''); }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(): void {}
  addEventListener(event: string, handler: () => void): void { this.listeners.set(event, handler); }
  querySelector(): null { return null; }
  querySelectorAll(): Element[] { return []; }
  focus(): void {}
  all(tag: string): Element[] {
    return [...(this.tag === tag ? [this] : []), ...this.children.flatMap(c => typeof c === 'string' ? [] : c.all(tag))];
  }
}

function fakeDom() {
  vi.stubGlobal('document', {
    createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag),
    addEventListener: () => {},
  });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
}

afterEach(() => vi.unstubAllGlobals());

describe('Guardian automation', () => {
  it('visibly tracks the active die as a class resource without a second saved counter', () => {
    fakeDom();
    const c = guardian(5);
    c.mancer = { done: true };
    expect(effectCounterText(c, 'Unstoppable')).toBe('Inactive');
    const outputs = () => (renderSheetView({ character: c, onRoll: () => false }) as unknown as Element)
      .all('output').filter(e => e.ariaLabel === 'Unstoppable Die');
    expect(outputs().map(e => e.text)).toEqual(['Inactive', 'Inactive']);
    activate(c, 'Unstoppable');
    expect(outputs().map(e => e.text)).toEqual(['1 / d6', '1 / d6']);
    c.effects[0].counter!.value = 5;
    expect(outputs().map(e => e.text)).toEqual(['5 / d6', '5 / d6']);
    expect(c.resources).not.toHaveProperty('unstoppable');
    c.effects = [];
    expect(outputs().map(e => e.text)).toEqual(['Inactive', 'Inactive']);
  });

  it('shows the physical severity reduction explanation in the mitigation dialog, including saved buffs', () => {
    fakeDom();
    const c = guardian();
    activate(c, 'Unstoppable');
    delete c.effects[0].mitigation![0].description;
    c.effects[0].counter!.value = 3;
    updateCharacterAutomation(c, [catalog]);
    const host = new Element('root');
    openDamageDialog(host as unknown as HTMLElement, c, () => {});
    const row = host.all('label').find(e => e.text.includes('Severe to Major'))!;
    expect(row).toBeDefined();
    expect(row.text).toContain('Major to Minor, Minor to None');
    expect(row.text).toContain('Magic damage is unaffected');
    expect(row.all('input')[0].checked).toBe(true);
    expect(c.effects[0].counter!.value).toBe(3);
  });

  it('creates a scene buff with an independent tracked die and level-scaled maximum', () => {
    const c = guardian();
    expect(availableRollOptions(c, ['damage']).some(o => o.source === 'Unstoppable')).toBe(false);
    expect(activate(c, 'Unstoppable')).not.toBeNull();
    const effect = c.effects.find(e => e.name === 'Unstoppable')!;
    expect(effect.kind).toBe('buff');
    expect(effect.until).toBe('scene');
    expect(effect.counter?.value).toBe(1);
    expect(effectCounterMax(c, effect)).toBe(4);
    expect(activate(c, 'Unstoppable')).toBeNull();
    const option = availableRollOptions(c, ['damage']).find(o => o.source === 'Unstoppable')!;
    expect(option.option.defaultOn).toBe(true);
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [option]).value).toBe(1);
    commitRollOptions(c, [option]);
    expect(c.usage['feat:Unstoppable'].used).toBe(1);
    effect.counter!.value = 4;
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [availableRollOptions(c, ['damage']).find(o => o.source === 'Unstoppable')!]).value).toBe(4);
    c.level = 5;
    expect(effectCounterMax(c, effect)).toBe(6);
    const other = guardian(5);
    activate(other, 'Unstoppable');
    expect(other.effects.find(e => e.name === 'Unstoppable')!.counter!.value).toBe(1);
    applyRest(c, 'short');
    expect(c.effects.some(e => e.name === 'Unstoppable')).toBe(false);
    expect(activate(c, 'Unstoppable')).toBeNull();
    applyRest(c, 'long');
    expect(activate(c, 'Unstoppable')).not.toBeNull();
  });

  it('offers advancement only on damage while active without reusing the activation limit', () => {
    const c = guardian();
    const ctx = { source: 'damage', hope: 0, fear: 0, total: 15, kind: 'damage' as const };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    activate(c, 'Unstoppable');
    const trigger = availableRollTriggers(c, ctx).find(t => t.trigger.advanceEffect)!;
    expect(trigger.automation.uses).toBeUndefined();
    expect(availableRollTriggers(c, { ...ctx, source: 'attack,action', kind: 'duality' }).some(t => t.trigger.advanceEffect)).toBe(false);
    expect(availableRollOptions(c, ['attack']).some(o => o.source === 'Unstoppable')).toBe(false);
  });

  it('offers physical severity mitigation only while active and a situational Proficiency bonus', () => {
    const c = guardian();
    activate(c, 'Unstoppable');
    const mitigation = availableMitigations(c).find(o => o.source === 'Unstoppable')!;
    expect(mitigation.option.defaultOn).toBe(true);
    expect(mitigation.option.when?.type).toBe('physical');
    expect(mitigation.option.severity).toBe(1);
    const severe = derivedStats(c).severe;
    const input = { damage: severe, type: 'physical' as const, armor: false, selected: new Set([mitigation.id]) };
    expect(computeDamage(c, availableMitigations(c), input).hp).toBe(2);
    expect(computeDamage(c, availableMitigations(c), { ...input, type: 'magic' }).hp).toBe(3);
    const reprisal = availableRollOptions(c, ['damage']).find(o => o.source === 'Act of Reprisal')!;
    expect(reprisal.option.proficiency).toBe(1);
    expect(reprisal.option.defaultOn).toBeUndefined();
    expect(availableRollOptions(c, ['attack']).some(o => o.source === 'Act of Reprisal')).toBe(false);
  });

  it('pays Nemesis Hope, keeps one prioritized buff and ends it on either rest', () => {
    const c = guardian(10);
    const ctx = { source: 'attack,action', hope: 3, fear: 9, total: 14 };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    activate(c, 'Nemesis');
    expect(c.hope.on).toBe(4);
    expect(availableRollTriggers(c, ctx).find(t => t.trigger.swapDuality)?.trigger.requiresEffect).toBe('Prioritized');
    expect(availableRollTriggers(c, { ...ctx, source: 'action' })).toHaveLength(0);
    expect(availableRollTriggers(c, { ...ctx, fear: 3 })).toHaveLength(0);
    activate(c, 'Nemesis');
    expect(c.hope.on).toBe(2);
    expect(c.effects.filter(e => e.name === 'Prioritized')).toHaveLength(1);
    applyRest(c, 'short');
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    activate(c, 'Nemesis');
    applyRest(c, 'long');
    expect(c.effects.some(e => e.name === 'Prioritized')).toBe(false);
  });

  it('refreshes old feature automation without resetting uses or active die values', () => {
    const c = guardian(5);
    activate(c, 'Unstoppable');
    c.effects[0].counter!.value = 3;
    for (const f of c.features) f.automation = undefined;
    updateCharacterAutomation(c, [catalog]);
    expect(c.features.find(f => f.name === 'Act of Reprisal')!.automation!.rollOptions).toHaveLength(1);
    expect(c.effects[0].counter!.value).toBe(3);
    expect(c.usage['feat:Unstoppable'].used).toBe(1);
  });
});
