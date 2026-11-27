import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { chooseChannelCard, chooseFeatureEffects, chooseRollModification } from './sorcerer-view';
import { emptyCharacter, type CharacterSheet } from './data';
import type { RollMessage } from '../protocol';
import type { CompendiumFile } from './compendium';
import { openRollDialog, renderSheetView, type RollRequest } from './view';
import { openHelpAlly } from './help-ally';
import { availableRollOptions, rollBonus } from './engine';
import { groupFeatures } from './feature-groups';
import { updateCharacterAutomation } from './persist';

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
  get ownerDocument() { return document; }
  style = { cssText: '', setProperty: () => {} };
  classList = { toggle: () => {}, add: () => {}, contains: (name: string) => this.className.split(' ').includes(name) };
  dataset: Record<string, string> = {};
  attributes: Record<string, string> = {};
  children: Array<Element | string> = [];
  listeners = new Map<string, () => unknown>();
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  insertBefore(child: Element, before: Element | null): void {
    const index = before ? this.children.indexOf(before) : -1;
    if (index < 0) this.children.push(child); else this.children.splice(index, 0, child);
  }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => unknown): void { this.listeners.set(event, handler); }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; }
  showModal(): void { this.open = true; }
  close(): void { this.open = false; this.listeners.get('close')?.(); }
  remove(): void { this.removed = true; }
  focus(): void {}
  querySelector(selector: string): Element | null {
    if (!selector.startsWith('.')) return null;
    const name = selector.slice(1);
    for (const child of this.children) {
      if (typeof child === 'string') continue;
      if (child.className.split(' ').includes(name)) return child;
      const nested = child.querySelector(selector);
      if (nested) return nested;
    }
    return null;
  }
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
    createElementNS: (_namespace: string, tag: string) => new Element(tag), addEventListener: () => {}, removeEventListener: () => {} });
  vi.stubGlobal('ResizeObserver', class { observe(): void {} disconnect(): void {} });
  vi.stubGlobal('requestAnimationFrame', () => {});
  return body;
}

const roll: RollMessage = { id: 1, ts: 1, fromId: 'player', from: 'Player', source: 'damage,magicdamage',
  expression: '2d8+1d4+3', rolls: [2, 6, 4], total: 15 };
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('Automatic Duality dice', () => {
  const cards: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
  const consumables: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/consumables.json', import.meta.url), 'utf8'));

  function addRunic(c: CharacterSheet): void {
    const entry = cards.entries.find(item => item.name === 'Runic Adrenaline')!;
    c.loadout.push({ name: entry.name, domain: entry.domain!, type: 'Ability', level: entry.level!,
      recall: entry.recall!, text: entry.text!, automation: structuredClone(entry.automation) });
  }

  function addConsumableEffect(c: CharacterSheet, name: string): void {
    const entry = consumables.entries.find(item => item.name === name)!;
    const effect = entry.automation!.actions![0].steps![0].effect!;
    c.effects.push({ ...structuredClone(effect), id: name });
  }

  function open(c: CharacterSheet, targets = ['action', 'trait:Agility']) {
    const body = setup();
    const requests: RollRequest[] = [];
    openRollDialog(body as unknown as HTMLElement, c, 'Agility', [{ label: 'Agility', expression: '3' }],
      rollBonus(c, targets, undefined, false), request => { requests.push(request); return false; }, () => {}, targets,
      undefined, undefined, undefined, false, undefined, undefined, undefined, undefined, 0,
      undefined, undefined, undefined, undefined, false, undefined, undefined, () => true);
    const dialog = body.querySelector('.dh-rolldlg')!;
    const button = (text: string) => dialog.querySelectorAll('button').find(item => item.text() === text)!;
    return { dialog, requests, button };
  }

  it('removes both die selectors while retaining the roll mode and standard d6 dice', () => {
    const { dialog, requests, button } = open(emptyCharacter('Hero'));
    expect(dialog.querySelectorAll('select').map(item => item.ariaLabel)).not.toContain('Advantage die');
    expect(dialog.querySelectorAll('select').map(item => item.ariaLabel)).not.toContain('Disadvantage die');
    expect(dialog.querySelectorAll('label').some(item => item.text().startsWith('Own advantage die'))).toBe(false);
    button('Advantage (+d6)').click();
    button('Roll to chat').click();
    button('Disadvantage (-d6)').click();
    button('Roll to chat').click();
    button('Normal').click();
    button('Roll to chat').click();
    expect(requests.map(request => request.expression)).toEqual(['2d12+1d6+3', '2d12-1d6+3', '2d12+3']);
    expect(requests[0].contributions).toContainEqual({ label: 'Advantage', expression: '1d6' });
    expect(requests[1].contributions).toContainEqual({ label: 'Disadvantage', expression: '-1d6' });
  });

  it.each([
    ['action', ['action', 'trait:Agility']],
    ['attack', ['action', 'attack', 'trait:Agility']],
    ['spellcast', ['action', 'spellcast', 'trait:Agility']],
    ['companion', ['action', 'companion', 'trait:Agility']],
    ['reaction', ['reaction', 'trait:Agility']],
  ])('automatically applies marked-HP Runic Adrenaline on a %s roll', (_name, targets) => {
    const c = emptyCharacter('Hero');
    c.hp.on = 1;
    addRunic(c);
    const { dialog, requests, button } = open(c, targets);
    const passive = dialog.querySelectorAll('label').find(item => item.text().includes('Runic Adrenaline'))!
      .querySelectorAll('input')[0];
    expect(passive.checked).toBe(true);
    expect(passive.disabled).toBe(true);
    button('Advantage (+d8)').click();
    button('Roll to chat').click();
    expect(requests[0].expression).toBe('2d12+1d8+3');
    expect(requests[0].contributions).toContainEqual({ label: 'Advantage', expression: '1d8' });
  });

  it('recomputes the passive on reaction changes and ignores unmarked or vaulted Runic Adrenaline', () => {
    const c = emptyCharacter('Hero');
    c.hp.on = 1;
    addRunic(c);
    const active = open(c);
    active.button('Reaction roll').click();
    active.button('Advantage (+d8)').click();
    active.button('Roll to chat').click();
    expect(active.requests[0]).toMatchObject({ source: expect.stringContaining('reaction'), expression: '2d12+1d8+3' });
    c.hp.on = 0;
    const unmarked = open(c);
    unmarked.button('Advantage (+d6)').click();
    unmarked.button('Roll to chat').click();
    expect(unmarked.requests[0].expression).toBe('2d12+1d6+3');
    c.hp.on = 1;
    c.vault.push(...c.loadout.splice(0));
    const vaulted = open(c);
    vaulted.button('Advantage (+d6)').click();
    vaulted.button('Roll to chat').click();
    expect(vaulted.requests[0].expression).toBe('2d12+1d6+3');
  });

  it('refreshes an older saved Runic Adrenaline card before rolling', () => {
    const c = emptyCharacter('Hero');
    c.hp.on = 1;
    addRunic(c);
    const old = c.loadout[0].automation!.conditionalRollOptions![0];
    old.roll = 'action';
    delete old.required;
    updateCharacterAutomation(c, [cards]);
    const { dialog, requests, button } = open(c, ['reaction', 'trait:Agility']);
    const passive = dialog.querySelectorAll('label').find(item => item.text().includes('Runic Adrenaline'))!
      .querySelectorAll('input')[0];
    expect(passive.checked).toBe(true);
    expect(passive.disabled).toBe(true);
    button('Advantage (+d8)').click();
    button('Roll to chat').click();
    expect(requests[0].expression).toBe('2d12+1d8+3');
  });

  it('blocks stale dialogs when a required die override appears or disappears', () => {
    const c = emptyCharacter('Hero');
    addRunic(c);
    const beforeMarking = open(c);
    beforeMarking.button('Advantage (+d6)').click();
    c.hp.on = 1;
    beforeMarking.button('Roll to chat').click();
    expect(beforeMarking.requests).toHaveLength(0);
    expect(beforeMarking.dialog.text()).toContain('A required bonus changed. Reopen this roll dialog.');

    const beforeHealing = open(c);
    beforeHealing.button('Advantage (+d8)').click();
    c.hp.on = 0;
    beforeHealing.button('Roll to chat').click();
    expect(beforeHealing.requests).toHaveLength(0);
    expect(beforeHealing.dialog.text()).toContain('A selected bonus changed. Reopen this roll dialog.');
  });

  it('uses the active Lightheaded disadvantage die without changing advantage', () => {
    const c = emptyCharacter('Hero');
    addConsumableEffect(c, 'Phantom Fern Shoots');
    const active = open(c);
    active.button('Advantage (+d6)').click();
    active.button('Roll to chat').click();
    active.button('Disadvantage (-d8)').click();
    active.button('Roll to chat').click();
    expect(active.requests.map(request => request.expression)).toEqual(['2d12+1d6+3', '2d12-1d8+3']);
    expect(active.requests[1].contributions).toContainEqual({ label: 'Disadvantage', expression: '-1d8' });
    c.effects = [];
    const removed = open(c);
    removed.button('Disadvantage (-d6)').click();
    removed.button('Roll to chat').click();
    expect(removed.requests[0].expression).toBe('2d12-1d6+3');
  });

  it('keeps helper dice separate from the automatically sized own advantage die', () => {
    const c = emptyCharacter('Ally');
    c.hp.on = 1;
    addRunic(c);
    c.effects.push({ id: 'help-1', name: 'Help from Helper', kind: 'buff', until: 'manual',
      helpAlly: { die: 10 }, rollOptions: [{ id: 'help', label: 'Help an Ally', roll: 'action',
        advantage: 'advantage', helpAllyDie: 10, hostOwned: true, defaultOn: true, excludeRolls: ['reaction', 'damage'] }] });
    const { dialog, requests, button } = open(c);
    expect(dialog.text()).toContain('Fixed d10 helper die');
    const own = dialog.querySelectorAll('input').find(item => item.ariaLabel === 'Also roll your own advantage die')!;
    own.checked = true;
    own.listeners.get('change')?.();
    expect(button('Advantage (help + d8)')).toBeDefined();
    button('Roll to chat').click();
    expect(requests[0]).toMatchObject({ expression: '2d12+1d8+3', helpAdvantage: true,
      hostOptions: [{ key: 'effect:help-1', id: 'help', helpAllyDie: 10 }] });
    expect(requests[0].contributions).toContainEqual({ label: 'Advantage', expression: '1d8' });
    button('Reaction roll').click();
    expect(dialog.text()).not.toContain('Fixed d10 helper die');
    button('Advantage (+d8)').click();
    button('Roll to chat').click();
    expect(requests[1]).toMatchObject({ expression: '2d12+1d8+3', source: expect.stringContaining('reaction') });
    expect(requests[1].helpAdvantage).toBeUndefined();
    expect(c.effects).toHaveLength(1);
  });

  it('uses the largest explicit override, including a replacement smaller than d6', () => {
    const c = emptyCharacter('Hero');
    c.features.push({ name: 'Custom die', source: 'Custom', text: '', automation: {
      rollOptions: [{ id: 'die', label: 'Custom die', roll: 'action', advantageDie: 4, required: true }],
    } });
    const small = open(c);
    small.button('Advantage (+d4)').click();
    small.button('Roll to chat').click();
    expect(small.requests[0].expression).toBe('2d12+1d4+3');
    c.hp.on = 1;
    addRunic(c);
    const both = open(c);
    both.button('Advantage (+d8)').click();
    both.button('Roll to chat').click();
    expect(both.requests[0].expression).toBe('2d12+1d8+3');
  });

  it('keeps unrelated situational feature die choices available', () => {
    const c = emptyCharacter('Hero');
    addConsumableEffect(c, 'Hero’s Powder');
    const { dialog, requests, button } = open(c);
    const size = dialog.querySelectorAll('select').find(item => item.ariaLabel.includes('Hero’s Powder') && item.ariaLabel.includes('die size'))!;
    expect(size).toBeDefined();
    size.value = '8';
    size.listeners.get('change')?.();
    const option = dialog.querySelectorAll('label').find(item => item.text().includes('Hero’s Powder'))!
      .querySelectorAll('input')[0];
    option.checked = true;
    option.listeners.get('change')?.();
    button('Roll to chat').click();
    expect(requests[0].expression).toBe('2d12+3+1d8');
  });
});

describe('Grouped class features', () => {
  const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

  it('groups only the four opted-in feature families, retaining Focus as its own feature', () => {
    const groups = catalog.entries.flatMap(entry => groupFeatures(entry.features ?? []))
      .filter(group => group.children.length);
    expect(groups.map(group => [group.feature.name, group.children.length])).toEqual([
      ['Gifted Performer', 3], ['Elemental Dominion', 4], ['Elemental Aura', 4], ['Elemental Incarnation', 4],
    ]);
    const martial = catalog.entries.find(e => e.id === 'brawler-martial-artist-foundation')!;
    expect(groupFeatures(martial.features!).map(group => group.feature.name)).toEqual(['Stance Fighter', 'Focus']);
  });

  it('does not hide missing, duplicate, or unrelated features', () => {
    const parent = { name: 'Parent', source: 'one', automation: { groupGrantedFeatures: true, grantsFeatures: ['Child', 'Missing'] } };
    const child = { name: 'Child', source: 'one' };
    const other = { name: 'Child', source: 'two' };
    expect(groupFeatures([other, parent, child])).toEqual([
      { feature: other, children: [] }, { feature: parent, children: [child] },
    ]);
    const duplicate = { ...child };
    expect(groupFeatures([parent, child, duplicate])).toEqual([
      { feature: parent, children: [] }, { feature: child, children: [] }, { feature: duplicate, children: [] },
    ]);
    const secondParent = { ...parent, name: 'Another parent' };
    expect(groupFeatures([parent, secondParent, child]).every(group => group.children.length === 0)).toBe(true);
  });

  it('renders song sections with independent counters and buttons after one song is used', async () => {
    setup();
    const c = emptyCharacter('Bard');
    c.className = 'Bard';
    c.mancer = { done: true };
    c.features = catalog.entries.find(e => e.id === 'bard-troubadour-foundation')!.features!
      .map(f => ({ ...structuredClone(f), source: 'Subclass foundation - Troubadour' }));
    const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
    const parent = root.querySelectorAll('div').find(e => e.className === 'feat' && e.text().includes('Gifted Performer'))!;
    const sections = () => root.querySelectorAll('div').filter(e => e.className === 'feature-section');
    expect(parent.querySelectorAll('div').filter(e => e.className === 'feature-section')).toHaveLength(3);
    expect(sections().map(section => section.text())).toEqual(expect.arrayContaining([
      expect.stringContaining('Relaxing Song'), expect.stringContaining('Epic Song'), expect.stringContaining('Heartbreaking Song'),
    ]));
    expect(sections().every(section => section.text().includes('1/1 per long rest'))).toBe(true);
    await root.querySelectorAll('button').find(b => b.text() === 'Epic Song')!.click();
    expect(c.usage['feat:Epic Song'].used).toBe(1);
    expect(c.usage['feat:Relaxing Song']).toBeUndefined();
    expect(sections().find(section => section.text().includes('Epic Song'))!.text()).toContain('0/1 per long rest');
    expect(sections().find(section => section.text().includes('Relaxing Song'))!.text()).toContain('1/1 per long rest');
  });

  it('refreshes legacy parent metadata and retains spent song uses', () => {
    const c = emptyCharacter('Bard');
    c.className = 'Bard';
    c.subclass = 'Troubadour';
    c.features = catalog.entries.find(e => e.id === 'bard-troubadour-foundation')!.features!
      .map(f => ({ ...structuredClone(f), source: 'Subclass foundation - Troubadour' }));
    delete c.features[0].automation!.groupGrantedFeatures;
    c.usage['feat:Relaxing Song'] = { used: 1, per: 'longRest' };
    updateCharacterAutomation(c, [catalog]);
    const group = groupFeatures(c.features).find(g => g.feature.name === 'Gifted Performer')!;
    expect(group.children).toHaveLength(3);
    expect(c.usage['feat:Relaxing Song'].used).toBe(1);
  });

  it('keeps individual song chat links on their own sections', () => {
    setup();
    const c = emptyCharacter('Bard');
    c.className = 'Bard';
    c.mancer = { done: true };
    c.features = catalog.entries.find(e => e.id === 'bard-troubadour-foundation')!.features!
      .map(f => ({ ...structuredClone(f), source: 'Subclass foundation - Troubadour' }));
    const onShare = vi.fn();
    const root = renderSheetView({ character: c, onRoll: () => false, onShare }) as unknown as Element;
    const sections = root.querySelectorAll('div').filter(e => e.className === 'feature-section');
    const song = sections.find(section => section.text().includes('Epic Song'))!;
    const share = song.querySelectorAll('button').find(button => button.attributes['aria-label'] === 'Post Epic Song to chat')!;
    share.click();
    expect(onShare).toHaveBeenCalledWith({ category: 'feature', item: c.features.find(f => f.name === 'Epic Song') });
  });

  it('renders every elemental action within its corresponding parent and retains the aura limit', () => {
    setup();
    const c = emptyCharacter('Druid');
    c.className = 'Druid';
    c.mancer = { done: true };
    c.features = catalog.entries.filter(e => e.id.startsWith('druid-warden-of-the-elements-'))
      .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: `Subclass ${e.stage} - Warden of the Elements` })));
    c.usage['feat:Elemental Aura'] = { used: 1, per: 'rest' };
    const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
    const parents = root.querySelectorAll('div').filter(e => e.className === 'feat');
    for (const name of ['Elemental Incarnation', 'Elemental Aura', 'Elemental Dominion']) {
      const card = parents.find(e => e.text().includes(name))!;
      expect(card.querySelectorAll('div').filter(e => e.className === 'feature-section')).toHaveLength(4);
    }
    const aura = parents.find(e => e.text().includes('Elemental Aura'))!;
    expect(aura.text()).toContain('0/1 per rest');
    const buttons = root.querySelectorAll('button').map(button => button.text());
    for (const label of ['Channel Fire', 'Channel Earth', 'Channel Water', 'Channel Air', 'Fire retaliation',
      'Move attacker', 'Roll damage reduction', 'Make attacker Vulnerable']) {
      expect(buttons.some(text => text.startsWith(label))).toBe(true);
    }
  });
});

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
    const root = renderSheetView({ character: c, onRoll: () => false, supportsHelpAlly: () => true }) as unknown as Element;
    const group = root.querySelectorAll('span').find(e => e.ariaLabel === 'Enchanted Aid limited actions')!;
    expect(group.text()).toContain('1/1 per long rest');
    expect(group.text()).not.toContain('Help an Ally');
    const manual = group.querySelectorAll('button').find(b => b.text() === 'Swap Duality dice')!;
    expect(manual.disabled).toBe(false);
    manual.click();
    expect(c.usage['feat:Enchanted Aid'].used).toBe(1);
    const refreshed = root.querySelectorAll('span').find(e => e.ariaLabel === 'Enchanted Aid limited actions')!;
    expect(refreshed.text()).toContain('0/1 per long rest');
    expect(refreshed.querySelectorAll('button')[0].disabled).toBe(true);
    expect(root.querySelectorAll('button').find(b => b.text() === 'Help an Ally: d8 (1 Hope)')!.disabled).toBe(false);
  });

  describe('Help an Ally dialogs', () => {
    it('selects an ally without die or description inputs or spending resources locally', async () => {
      const body = setup();
      const c = emptyCharacter('Helper');
      const send = vi.fn(async () => {});
      openHelpAlly(body as unknown as HTMLElement, c, () => [{ id: 'ally', name: 'Ally', ownerId: 'player' }], send);
      const dialog = body.querySelectorAll('dialog')[0];
      const target = dialog.querySelectorAll('select').find(e => e.ariaLabel === 'Ally to help')!;
      expect(dialog.querySelectorAll('select').map(e => e.ariaLabel)).toEqual(['Ally to help', 'Help feature']);
      expect(dialog.querySelectorAll('select').find(e => e.ariaLabel === 'Help feature')!.className).toBe('dh-help-feature');
      expect(dialog.querySelectorAll('textarea')).toHaveLength(0);
      expect(dialog.text()).toContain('d6 advantage die');
      target.value = 'ally';
      const hope = c.hope.on;
      dialog.querySelectorAll('button').find(e => e.text() === 'Help ally')!.click();
      await settle();
      expect(send).toHaveBeenCalledWith('ally', {}, undefined);
      expect(c.hope.on).toBe(hope);
      expect(dialog.removed).toBe(true);
    });

    it('preselects the fixed buff die and retains the buff when sending fails', async () => {
      const body = setup();
      const c = emptyCharacter('Ally');
      c.effects.push({ id: 'help-1', name: 'Help from Helper', kind: 'buff', until: 'manual',
        helpAlly: { die: 6 }, rollOptions: [{ id: 'help', label: 'Help an Ally', roll: 'action',
          advantage: 'advantage', helpAllyDie: 6, hostOwned: true, defaultOn: true, excludeRolls: ['reaction', 'damage'] }] });
      const requests: RollRequest[] = [];
      openRollDialog(body as unknown as HTMLElement, c, 'Agility', [{ label: 'Agility', expression: '3' }],
        rollBonus(c, ['action'], undefined, false), async request => { requests.push(request); return false; }, () => {}, ['action'],
        undefined, undefined, undefined, false, undefined, undefined, undefined, undefined, 0, undefined, undefined, undefined, undefined, false, undefined, undefined, () => true);
      const dialog = body.querySelector('.dh-rolldlg')!;
      expect(dialog.querySelectorAll('select').some(e => e.ariaLabel.includes('Help an Ally die size'))).toBe(false);
      expect(dialog.text()).toContain('Fixed d6 helper die');
      expect(dialog.querySelectorAll('select').some(e => e.ariaLabel === 'Advantage die' || e.ariaLabel === 'Disadvantage die')).toBe(false);
      expect(dialog.querySelectorAll('input').find(e => e.ariaLabel === 'Also roll your own advantage die')!.checked).toBe(false);
      dialog.querySelectorAll('button').find(e => e.text() === 'Roll to chat')!.click();
      await settle();
      expect(requests[0]).toMatchObject({ kind: 'duality', expression: '2d12+3', helpAdvantage: true,
        hostOptions: [{ key: 'effect:help-1', id: 'help', helpAllyDie: 6 }] });
      expect(c.effects).toHaveLength(1);
      expect(dialog.text()).toContain('Not connected');
      expect(availableRollOptions(c, ['action', 'reaction'])).toHaveLength(0);
      expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    });

    it('places standard Help directly below rest controls and disables it on older hosts', () => {
      setup();
      const c = emptyCharacter('Hero');
      c.mancer = { done: true };
      const root = renderSheetView({ character: c, onRoll: () => false }) as unknown as Element;
      const actions = root.querySelectorAll('div').filter(e => e.className === 'header-actions');
      expect(actions[0].text()).toContain('Short restLong rest');
      expect(actions[1].text()).toBe('Help an Ally');
      expect(actions[1].querySelectorAll('button')[0].disabled).toBe(true);
    });
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
