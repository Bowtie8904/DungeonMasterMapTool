import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { renderSheetView, SheetBus, type RollRequest } from './view';
import type { CardPreferences, CardPreferenceState } from './card-preferences';
import { addEffect, applyRest, derivedStats, isManualOverride, removeManualOverride, setManualStat, setTrackSlots, statBreakdown, syncDerived, traitTotal } from './engine';

class Element {
  className = '';
  private content = '';
  get textContent(): string { return this.content; }
  set textContent(value: string) { this.content = value; this.children = []; }
  value = '';
  get valueAsNumber(): number { return Number(this.value); }
  checked = false;
  readOnly = false;
  validationMessage = '';
  files?: File[];
  disabled = false;
  hidden = false;
  open = false;
  ariaLabel = '';
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: (name: string) => this.className.split(' ').includes(name) };
  dataset: Record<string, string> = {};
  children: Array<Element | string> = [];
  get childElementCount(): number { return this.children.filter(child => typeof child !== 'string').length; }
  listeners = new Map<string, () => unknown>();
  attributes: Record<string, string> = {};
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  insertBefore(child: Element, before: Element | null): void {
    const i = before ? this.children.indexOf(before) : -1;
    if (i < 0) this.append(child);
    else this.children.splice(i, 0, child);
  }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; if (name === 'aria-label') this.ariaLabel = value; }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void {}
  focus(): void {}
  select(): void {}
  setCustomValidity(message: string): void { this.validationMessage = message; }
  reportValidity(): boolean { return !this.validationMessage; }
  querySelector(): null { return null; }
  matches(): boolean { return this.attributes.role === 'tab' || ['Card view', 'List view'].includes(this.ariaLabel) || this.className === 'feature-search'; }
  querySelectorAll(selector: string): Element[] {
    const tags = selector.split(',').map(tag => tag.trim());
    return this.children.flatMap(child => typeof child === 'string' ? [] :
      [...(tags.includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  async click(): Promise<void> { if (!this.disabled) await this.listeners.get('click')?.(); }
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('defaults edit mode off and gates base traits, Proficiency, names, background and Experiences', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  c.hp.slots = 6;
  c.experiences = [{ name: 'Survivor', mod: 2 }];
  c.traits[0].mod = 3;
  addEffect(c, { name: 'Trait buff', kind: 'buff', modifiers: [{ stat: 'trait.Agility', value: 5 }] });
  const changed = vi.fn();
  const root = renderSheetView({ character: c, onRoll: () => true, onChange: changed }) as unknown as Element;
  const toggle = () => root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!;
  const prof = () => root.querySelectorAll('div').find(node => node.dataset.kind === 'prof')!;
  expect(toggle().attributes['aria-checked']).toBe('false');
  expect(root.querySelectorAll('span').find(node => node.text() === 'Edit mode')!.className).toBe('sheet-edit-label muted');
  expect(prof().querySelectorAll('button').every(b => b.disabled)).toBe(true);
  expect(root.querySelectorAll('button').some(b => b.text() === 'Add a transformation')).toBe(false);
  await root.querySelectorAll('button').find(b => b.text() === 'Background')!.click();
  expect(root.querySelectorAll('textarea').every(input => input.readOnly)).toBe(true);
  changed.mockClear();
  await toggle().click();
  expect(changed).not.toHaveBeenCalled();
  expect(toggle().attributes['aria-checked']).toBe('true');
  expect(root.querySelectorAll('span').find(node => node.text() === 'Edit mode')!.className).toBe('sheet-edit-label');
  expect(prof().querySelectorAll('button').every(b => !b.disabled)).toBe(true);
  expect(root.querySelectorAll('textarea').every(input => !input.readOnly)).toBe(true);
  const trait = root.querySelectorAll('input').find(input => input.ariaLabel === 'Agility base modifier')!;
  expect(trait.value).toBe('3');
  trait.value = '99';
  trait.listeners.get('change')?.();
  expect(c.traits[0].mod).toBe(99);
  expect(traitTotal(c, 'Agility')).toBe(104);
  const negative = root.querySelectorAll('input').find(input => input.ariaLabel === 'Agility base modifier')!;
  negative.value = '-99';
  negative.listeners.get('change')?.();
  expect(c.traits[0].mod).toBe(-99);
  const invalid = root.querySelectorAll('input').find(input => input.ariaLabel === 'Agility base modifier')!;
  for (const value of ['100', '-100', '1.5']) {
    invalid.value = value;
    invalid.listeners.get('change')?.();
    expect(c.traits[0].mod).toBe(-99);
    expect(invalid.validationMessage).toContain('-99 to +99');
  }
  const name = root.querySelectorAll('input').find(input => input.ariaLabel === 'Character name')!;
  name.value = 'Renamed';
  name.listeners.get('input')?.();
  expect(c.name).toBe('Renamed');
  const experience = root.querySelectorAll('input').find(input => input.ariaLabel === 'Experience 1 name')!;
  experience.value = 'Explorer';
  experience.listeners.get('input')?.();
  const bonus = root.querySelectorAll('input').find(input => input.ariaLabel === 'Experience 1 bonus')!;
  bonus.value = '5';
  bonus.listeners.get('change')?.();
  expect(c.experiences[0]).toEqual({ name: 'Explorer', mod: 5 });
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Add Experience')!.click();
  expect(c.experiences).toHaveLength(2);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Delete Experience 1')!.click();
  expect(c.experiences).toHaveLength(1);
  await toggle().click();
  expect(root.querySelectorAll('input').some(input => input.ariaLabel === 'Character name')).toBe(false);
  expect(root.querySelectorAll('textarea').every(input => input.readOnly)).toBe(true);
});

it('deletes domain cards only from this character in Card and List views', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  c.loadout = [{ name: 'Active', level: 1, domain: 'Blade', type: 'Ability', recall: 1, text: '' }];
  c.vault = [{ ...c.loadout[0], name: 'Vaulted' }];
  const other = structuredClone(c);
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(b => b.text() === 'Domain Cards')!.click();
  expect(root.querySelectorAll('button').some(b => b.ariaLabel.startsWith('Remove '))).toBe(false);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Remove Active from this character')!.click();
  expect(c.loadout).toHaveLength(0);
  expect(other.loadout).toHaveLength(1);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'List view')!.click();
  const summary = root.querySelectorAll('summary')[0];
  expect(summary.querySelectorAll('button').some(b => b.ariaLabel === 'Remove Vaulted from this character')).toBe(true);
  await summary.querySelectorAll('button').find(b => b.ariaLabel === 'Remove Vaulted from this character')!.click();
  expect(c.vault).toHaveLength(0);
  expect(other.vault).toHaveLength(1);
});

it('edits contiguous capacities and keeps Armor overrides additive and persistent across rests', () => {
  const c = emptyCharacter('Hero');
  c.hp.slots = 6;
  c.hp.on = 5;
  c.stress.on = 4;
  c.activeArmor = { name: 'Armor', score: 3, tier: 1 };
  addEffect(c, { name: 'Armor buff', kind: 'buff', modifiers: [{ stat: 'armorScore', value: 2 }] });
  setTrackSlots(c, 'hp', 9);
  expect(c.hp.slots).toBe(9);
  expect(c.hp.on).toBe(5);
  setTrackSlots(c, 'hp', 3);
  expect(c.hp).toEqual({ total: 12, slots: 3, on: 3 });
  c.permanentHp = 2;
  addEffect(c, { name: 'HP lock', kind: 'debuff', lockedHp: 1, until: 'rest' });
  setTrackSlots(c, 'hp', 0);
  expect(c.hp.slots).toBe(3);
  setTrackSlots(c, 'stress', 2);
  expect(c.stress).toEqual({ total: 12, slots: 2, on: 2 });
  setTrackSlots(c, 'armor', 6);
  expect(derivedStats(c).armorSlots).toBe(6);
  expect(statBreakdown(c).armor).toContainEqual({ label: 'Manual override', value: 1 });
  const restored = structuredClone(c);
  syncDerived(restored);
  applyRest(restored, 'long');
  expect(derivedStats(restored).armorSlots).toBe(6);
  restored.activeArmor!.score = 4;
  syncDerived(restored);
  expect(restored.armorTrack.slots).toBe(7);
  setTrackSlots(restored, 'armor', 4);
  expect(statBreakdown(restored).armor).toContainEqual({ label: 'Manual override', value: -2 });
  setTrackSlots(restored, 'armor', 6);
  expect(restored.effects.find(isManualOverride)?.modifiers).toEqual([]);
  expect(restored.effects.find(isManualOverride)?.manualChanges).toEqual({ hp: -3, stress: -4 });
});

it('combines all manual changes without double-counting and reverses only manual adjustments', () => {
  const c = emptyCharacter('Hero');
  c.hp.slots = 6;
  c.evasion = 10;
  c.activeArmor = { name: 'Armor', score: 3, tier: 1 };
  addEffect(c, { name: 'Buff', kind: 'buff', modifiers: [{ stat: 'trait.Agility', value: 5 }, { stat: 'evasion', value: 2 }] });
  setTrackSlots(c, 'hp', 8);
  setTrackSlots(c, 'stress', 4);
  setManualStat(c, 'proficiency', 3);
  setManualStat(c, 'trait.Agility', 5);
  setManualStat(c, 'evasion', 12);
  setTrackSlots(c, 'armor', 4);
  setManualStat(c, 'trait.Agility', 7);
  expect(c.effects.filter(isManualOverride)).toHaveLength(1);
  const effect = c.effects.find(isManualOverride)!;
  expect(effect.manualChanges).toEqual({ hp: 2, stress: -2, proficiency: 2, 'trait.Agility': 7, evasion: 2 });
  expect(effect.text).toContain('HP slots: +2');
  expect(effect.text).toContain('Stress slots: -2');
  expect(effect.text).toContain('Agility base trait: +7');
  expect(effect.text).toContain('Evasion: +2');
  expect(derivedStats(c)).toMatchObject({ evasion: 14, proficiency: 3, armorSlots: 4 });
  expect(traitTotal(c, 'Agility')).toBe(12);
  expect(statBreakdown(c).evasion).toContainEqual({ label: 'Manual override', value: 2 });
  const restored = structuredClone(c);
  applyRest(restored, 'long');
  expect(restored.effects.find(isManualOverride)?.manualChanges).toEqual(effect.manualChanges);
  c.hp.slots++;
  c.evasion++;
  c.traits[0].mod++;
  removeManualOverride(c, effect);
  expect(c.hp.slots).toBe(7);
  expect(c.stress.slots).toBe(6);
  expect(c.proficiency.on).toBe(1);
  expect(c.evasion).toBe(11);
  expect(c.traits[0].mod).toBe(1);
  expect(derivedStats(c).armorSlots).toBe(3);
  expect(c.effects.map(item => item.name)).toEqual(['Buff']);
});

it('clears reverted adjustments and migrates existing Armor-only overrides into the combined effect', () => {
  const c = emptyCharacter('Hero');
  setManualStat(c, 'trait.Strength', -5);
  setManualStat(c, 'trait.Strength', 0);
  expect(c.effects.filter(isManualOverride)).toHaveLength(0);
  addEffect(c, { name: 'Manual override', kind: 'debuff', modifiers: [{ stat: 'armorScore', value: -1 }] }, 'manual:armor-slots');
  setManualStat(c, 'evasion', 2);
  expect(c.effects.filter(isManualOverride)).toHaveLength(1);
  expect(c.effects.find(isManualOverride)?.modifiers).toEqual([{ stat: 'armorScore', value: -1 }]);
  setManualStat(c, 'evasion', 0);
  expect(c.effects.filter(isManualOverride)).toHaveLength(1);
  setTrackSlots(c, 'armor', 0);
  expect(c.effects.filter(isManualOverride)).toHaveLength(0);
});

it('presents a friendly manual source and hides only the override Armor badge', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  addEffect(c, { name: 'Armor buff', kind: 'buff', modifiers: [{ stat: 'armorScore', value: 2 }] }, 'Armor feature');
  setTrackSlots(c, 'armor', 3);
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(b => b.text() === 'Effects (2)')!.click();
  const cards = root.querySelectorAll('div').filter(node => node.className === 'feat hope');
  const manual = cards.find(node => node.text().includes('Manual override'))!;
  const other = cards.find(node => node.text().includes('Armor buff'))!;
  expect(manual.text()).toContain('from sheet edit mode');
  expect(manual.text()).not.toContain('manual:armor-slots');
  expect(manual.querySelectorAll('div').filter(node => node.className === 'badges')).toEqual([]);
  expect(other.text()).toContain('from Armor feature');
  expect(other.querySelectorAll('div').find(node => node.className === 'badges')!.text()).toContain('armorScore +2');
  expect(derivedStats(c).armorSlots).toBe(3);
  expect(statBreakdown(c).armor).toContainEqual({ label: 'Manual override', value: 1 });
});

it('edits base Evasion rather than its buffed total and records Proficiency pip edits', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  c.evasion = 10;
  addEffect(c, { name: 'Evasion buff', kind: 'buff', modifiers: [{ stat: 'evasion', value: 3 }] });
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  expect(root.querySelectorAll('input').some(input => input.ariaLabel === 'Evasion base value')).toBe(false);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  const evasion = root.querySelectorAll('input').find(input => input.ariaLabel === 'Evasion base value')!;
  expect(evasion.value).toBe('10');
  evasion.value = '15';
  evasion.listeners.get('change')?.();
  expect(derivedStats(c).evasion).toBe(18);
  const proficiency = root.querySelectorAll('div').find(node => node.dataset.kind === 'prof')!;
  await proficiency.querySelectorAll('button')[3].click();
  expect(c.proficiency.on).toBe(4);
  expect(c.effects.find(isManualOverride)?.manualChanges).toEqual({ evasion: 5, proficiency: 3 });
  await root.querySelectorAll('button').find(b => b.text() === 'Effects (2)')!.click();
  expect(root.text()).toContain('Evasion: +5');
  expect(root.text()).toContain('Proficiency: +3');
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  expect(root.querySelectorAll('button').find(b => b.text() === 'Remove')!.disabled).toBe(false);
  expect(root.querySelectorAll('button').filter(b => b.text() === 'Remove')[1].disabled).toBe(true);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  await root.querySelectorAll('button').filter(b => b.text() === 'Remove')[1].click();
  expect(c.evasion).toBe(10);
  expect(c.proficiency.on).toBe(1);
  expect(c.effects.filter(isManualOverride)).toEqual([]);
});

it('uses resource pips for capacities only in edit mode and retains normal marking otherwise', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  c.hp.slots = 6;
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  const track = (kind: string) => root.querySelectorAll('div').find(node => node.dataset.kind === kind)!;
  await track('hp').querySelectorAll('button')[2].click();
  expect(c.hp.on).toBe(3);
  expect(track('hp').querySelectorAll('button')[8].disabled).toBe(true);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  await track('hp').querySelectorAll('button')[8].click();
  expect(c.hp.slots).toBe(9);
  expect(c.hp.on).toBe(3);
  await track('hp').querySelectorAll('button')[4].click();
  expect(c.hp.slots).toBe(4);
  await track('stress').querySelectorAll('button')[8].click();
  expect(c.stress.slots).toBe(9);
  await track('armor').querySelectorAll('button')[0].click();
  expect(c.armorTrack.slots).toBe(1);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  await track('armor').querySelectorAll('button')[0].click();
  expect(c.armorTrack.on).toBe(1);
  expect(c.armorTrack.slots).toBe(1);
});

it('offers portrait editing only in edit mode, uploads images and surfaces upload failures', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ artwork: '/a/new.png' })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'Invalid image' }), { status: 400 }));
  vi.stubGlobal('fetch', fetch);
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  const root = renderSheetView({ character: c, actorId: 'hero', onRoll: () => true }) as unknown as Element;
  expect(root.querySelectorAll('div').some(node => node.ariaLabel === 'Edit character image')).toBe(false);
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  const portrait = root.querySelectorAll('div').find(node => node.ariaLabel === 'Edit character image')!;
  expect(portrait.text()).toContain('Edit image');
  const file = portrait.querySelectorAll('input')[0];
  file.files = [new File(['image'], 'portrait.png', { type: 'image/png' })];
  await file.listeners.get('change')?.();
  expect(root.querySelectorAll('img')).toHaveLength(1);
  expect(fetch.mock.calls[0][0]).toBe('/api/actors/hero/artwork');
  const next = root.querySelectorAll('div').find(node => node.ariaLabel === 'Edit character image')!.querySelectorAll('input')[0];
  next.files = file.files;
  await next.listeners.get('change')?.();
  expect(root.text()).toContain('Could not update character image: Invalid image');
  await root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!.click();
  expect(root.querySelectorAll('input').some(input => input.files)).toBe(false);
});

it('never lets edit mode bypass a disabled actor and restores it only after re-enabling', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  vi.stubGlobal('HTMLInputElement', Element);
  vi.stubGlobal('HTMLTextAreaElement', Element);
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  const bus = new SheetBus();
  let readOnly = false;
  const root = renderSheetView({ character: c, bus, readOnly: () => readOnly, onRoll: () => true }) as unknown as Element;
  const toggle = () => root.querySelectorAll('button').find(b => b.ariaLabel === 'Edit mode')!;
  await toggle().click();
  const staleInput = root.querySelectorAll('input').find(input => input.ariaLabel === 'Agility base modifier')!;
  readOnly = true;
  bus.notify();
  expect(toggle().disabled).toBe(true);
  expect(toggle().attributes['aria-checked']).toBe('false');
  expect(root.querySelectorAll('input').some(input => input.ariaLabel === 'Agility base modifier')).toBe(false);
  staleInput.value = '99';
  staleInput.listeners.get('change')?.();
  expect(c.traits[0].mod).toBe(0);
  await toggle().click();
  expect(toggle().attributes['aria-checked']).toBe('false');
  readOnly = false;
  bus.notify();
  expect(toggle().disabled).toBe(false);
  expect(toggle().attributes['aria-checked']).toBe('true');
});

it('switches both zones globally without saving character data and preserves expanded full cards', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  let state: CardPreferenceState = { view: 'card', pending: false, error: '' };
  const listeners: Array<(state: CardPreferenceState) => void> = [];
  const preferences: CardPreferences = {
    subscribe(listener) { listeners.push(listener); listener(state); },
    load: async () => {},
    save: async view => { state = { ...state, view }; listeners.forEach(listener => listener(state)); },
  };
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: 'Active', level: 2, domain: 'Codex', type: 'Spell', recall: 1, text: 'Full active text',
    automation: { resources: [{ id: 'tokens', label: 'Tokens', max: 3, initial: 2 }] } }];
  c.resources = { tokens: 2 };
  c.vault = [{ name: 'Vaulted', level: 1, domain: 'Arcana', type: 'Ability', recall: 2, text: 'Full vaulted text' }];
  const changed = vi.fn();
  const share = vi.fn();
  const views = [c, structuredClone(c)].map(character => renderSheetView({
    character, onRoll: () => true, onChange: changed, onShare: share, cardPreferences: preferences,
  }) as unknown as Element);
  for (const root of views) await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  changed.mockClear();
  const [root] = views;
  expect(root.querySelectorAll('details')).toHaveLength(0);
  const switches = root.querySelectorAll('button').filter(button => ['Card view', 'List view'].includes(button.ariaLabel));
  expect(switches).toHaveLength(2);
  expect(switches[0].attributes['aria-pressed']).toBe('true');
  await switches[1].click();
  for (const view of views) {
    const rows = view.querySelectorAll('details');
    expect(rows).toHaveLength(2);
    expect(rows.every(row => !row.open)).toBe(true);
    const summaries = view.querySelectorAll('summary');
    expect(summaries.map(row => row.children.slice(0, 4).map(child => (child as Element).text())))
      .toEqual([['2', 'Active', 'Codex', '\u26A1 1'], ['1', 'Vaulted', 'Arcana', '\u26A1 2']]);
    expect(rows[0].text()).toContain('Full active text');
    expect(rows[0].querySelectorAll('input').length).toBeGreaterThan(0);
    expect(rows[1].text()).toContain('Full vaulted text');
  }
  const row = root.querySelectorAll('details')[0];
  row.open = true;
  row.listeners.get('toggle')?.();
  await root.querySelectorAll('button').find(button => button.ariaLabel === 'Post Active to chat')!.click();
  expect(row.open).toBe(true);
  expect(share).toHaveBeenCalledWith({ category: 'domain', item: c.loadout[0] });
  await root.querySelectorAll('button').find(button => button.ariaLabel === 'Card view')!.click();
  await root.querySelectorAll('button').find(button => button.ariaLabel === 'List view')!.click();
  expect(root.querySelectorAll('details')[0].open).toBe(true);
  const search = root.querySelectorAll('input').find(input => input.ariaLabel === 'Search vault cards')!;
  search.value = 'missing';
  search.listeners.get('input')?.();
  expect(root.querySelectorAll('details')[1].hidden).toBe(true);
  expect(changed).not.toHaveBeenCalled();
});

it('disables Teleport casts on unsupported hosts and after its long-rest use is spent', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'teleport')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 5, type: 'Spell', recall: 2,
    text: entry.text ?? '', spellcast: 'Knowledge', automation: entry.automation }];
  for (const [supported, spent, disabled] of [[false, false, true], [true, false, false], [true, true, true]]) {
    c.usage = spent ? { 'card:Teleport': { used: 1, per: 'longRest' } } : {};
    const root = renderSheetView({ character: c, onRoll: () => true, supportsDomainCardAutomation: () => supported }) as unknown as Element;
    await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
    expect(root.querySelectorAll('button').find(button => button.text() === 'Spellcast roll')!.disabled).toBe(disabled);
  }
});

it('displays loadout and vault cards by ascending level without reordering the sheet data', async () => {
  vi.stubGlobal('document', { body: new Element('body'), createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  const card = (name: string, level: number) => ({
    name, level, domain: 'Codex', type: 'Ability' as const, recall: 1, text: '',
  });
  c.loadout = [card('Loadout 5', 5), card('Loadout 1', 1), card('Loadout 3', 3)];
  c.vault = [card('Vault 4', 4), card('Vault 2', 2)];
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const names = root.querySelectorAll('div')
    .filter(element => element.className === 'dcard')
    .map(element => element.querySelectorAll('span').find(child => child.className === 'nm')!.text());
  expect(names).toEqual(['Loadout 1', 'Loadout 3', 'Loadout 5', 'Vault 2', 'Vault 4']);
  expect(c.loadout.map(item => item.name)).toEqual(['Loadout 5', 'Loadout 1', 'Loadout 3']);
  expect(c.vault.map(item => item.name)).toEqual(['Vault 4', 'Vault 2']);
});

it('rolls Arcane Barrage only after a successful send and charges the chosen Hope once', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-illiat')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.hope.on = 4;
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 1, type: 'Grimoire', recall: 2, text: entry.text ?? '', spells: structuredClone(entry.spells) }];
  const requests: RollRequest[] = [];
  let connected = false;
  const root = renderSheetView({ character: c, onRoll: request => { requests.push(request); return connected; } }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const barrage = root.querySelectorAll('button').find(button => button.text() === 'Arcane Barrage');
  expect(root.querySelectorAll('button').map(button => button.text()).filter(text => /Arcane|Telepathy|Recall/.test(text))).toContain('Arcane Barrage');
  await barrage!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  const input = dialog.querySelectorAll('input')[0];
  const go = dialog.querySelectorAll('button').find(button => button.text() === 'Spend Hope and roll')!;
  input.value = '5';
  await go.click();
  expect(requests).toHaveLength(0);
  expect(c.hope.on).toBe(4);
  input.value = '3';
  await go.click();
  expect(c.hope.on).toBe(4);
  expect(c.usage['card:Book of Illiat/Arcane Barrage']).toBeUndefined();
  connected = true;
  await go.click();
  expect(requests.at(-1)?.expression).toBe('3d6');
  expect(c.hope.on).toBe(1);
  expect(c.usage['card:Book of Illiat/Arcane Barrage'].used).toBe(1);
});

it('does not spend Hope or a rest use when Arcane Barrage is canceled', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-illiat')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.hope.on = 4;
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 1, type: 'Grimoire', recall: 2, text: entry.text ?? '', spells: structuredClone(entry.spells) }];
  const onRoll = vi.fn(() => true);
  const root = renderSheetView({ character: c, onRoll }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  await root.querySelectorAll('button').find(button => button.text() === 'Arcane Barrage')!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  await dialog.querySelectorAll('button').find(button => button.text() === 'Cancel')!.click();
  expect(dialog.open).toBe(false);
  expect(c.hope.on).toBe(4);
  expect(c.usage['card:Book of Illiat/Arcane Barrage']).toBeUndefined();
  expect(onRoll).not.toHaveBeenCalled();
});

it('disables Homet spells on old hosts and independently when their uses are exhausted', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'book-of-homet')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 7, type: 'Grimoire', recall: 0, text: entry.text ?? '',
    spellcast: 'Knowledge', spells: structuredClone(entry.spells) }];
  const old = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await old.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  expect(old.querySelectorAll('button').filter(button => button.text() === 'Spellcast roll').every(button => button.disabled)).toBe(true);
  c.usage['card:Book of Homet/Pass Through'] = { used: 1, per: 'rest' };
  const supported = renderSheetView({ character: c, onRoll: () => true, supportsDomainCardAutomation: () => true }) as unknown as Element;
  await supported.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  const spells = supported.querySelectorAll('button').filter(button => button.text() === 'Spellcast roll');
  expect(spells).toHaveLength(2);
  expect(spells.map(button => button.disabled)).toEqual([true, false]);
});

it('allows Banish difficulty to be adjusted and cleared on the card', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'banish')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.loadout = [{ name: entry.name, domain: 'Codex', level: 6, type: 'Spell', recall: 0,
    text: entry.text ?? '', automation: entry.automation, banishDifficulty: 18 }];
  const onChange = vi.fn();
  const root = renderSheetView({ character: c, onRoll: () => true, onChange }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  onChange.mockClear();
  const input = root.querySelectorAll('input').find(element => element.ariaLabel === 'Banish difficulty')!;
  expect(input.value).toBe('18');
  input.value = '17';
  input.listeners.get('change')?.();
  expect(c.loadout[0].banishDifficulty).toBe(17);
  input.value = '';
  input.listeners.get('change')?.();
  expect(c.loadout[0].banishDifficulty).toBeUndefined();
  expect(onChange).toHaveBeenCalledTimes(2);
});

it('swaps Codex-Touched with the selected vault card without paying Recall', async () => {
  const body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const entry = catalog.entries.find(item => item.id === 'codex-touched')!;
  const c = emptyCharacter('Caster');
  c.mancer = { done: true };
  c.stress.on = 0;
  const card = (name: string, domain = 'Codex') => ({ name, domain, level: 1, type: 'Ability' as const, recall: 2, text: '' });
  c.loadout = [{ ...card(entry.name), automation: entry.automation }, card('two'), card('three'), card('four')];
  c.vault = [card('Replacement', 'Arcana')];
  const root = renderSheetView({ character: c, onRoll: () => true }) as unknown as Element;
  await root.querySelectorAll('button').find(button => button.text() === 'Domain Cards')!.click();
  await root.querySelectorAll('button').find(button => button.text() === 'Codex-Touched: swap with vault')!.click();
  const dialog = body.querySelectorAll('dialog')[0];
  const replacement = dialog.querySelectorAll('button').find(button => button.text() === 'Replacement')!;
  const fourth = c.loadout.pop()!;
  await replacement.click();
  expect(c.loadout[0].name).toBe('Codex-Touched');
  expect(c.usage['card:Codex-Touched:action:swap']).toBeUndefined();
  c.loadout.push(fourth);
  await replacement.click();
  expect(c.loadout.map(item => item.name)).toContain('Replacement');
  expect(c.vault.map(item => item.name)).toContain('Codex-Touched');
  expect(c.stress.on).toBe(0);
  expect(c.usage['card:Codex-Touched:action:swap']).toMatchObject({ used: 1, per: 'rest' });
});
