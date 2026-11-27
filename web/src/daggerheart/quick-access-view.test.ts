import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter, type CharacterSheet, type Feature } from './data';
import { favoriteKey } from './quick-access';
import { renderSheetView, SheetBus } from './view';
import { openDamageDialog } from './damage-view';

vi.mock('./damage-view', () => ({ openDamageDialog: vi.fn() }));

class Element {
  className = '';
  ownText = '';
  get textContent(): string { return this.text; }
  set textContent(value: string) { this.ownText = value; }
  ariaLabel = '';
  disabled = false;
  hidden = false;
  value = '';
  valueAsNumber = 0;
  type = '';
  attributes: Record<string, string> = {};
  style = { cssText: '', height: '', setProperty: () => {} };
  readOnly = false;
  clientWidth = 0;
  clientHeight = 30;
  offsetHeight = 32;
  get scrollHeight(): number {
    return 10 + 20 * this.value.split('\n').reduce((lines, line) => lines + Math.max(1, Math.ceil(line.length / Math.max(1, this.clientWidth / 10))), 0);
  }
  dataset: Record<string, string> = {};
  children: Array<Element | string> = [];
  classList = {
    toggle: (name: string, force?: boolean) => {
      const names = new Set(this.className.split(' ').filter(Boolean));
      if (force ?? !names.has(name)) names.add(name); else names.delete(name);
      this.className = [...names].join(' ');
    },
    add: (name: string) => this.classList.toggle(name, true),
    contains: (name: string) => this.className.split(' ').includes(name),
  };
  listeners = new Map<string, (event: Event) => void>();
  constructor(readonly tag: string) {}
  get text(): string { return this.ownText + this.children.map(c => typeof c === 'string' ? c : c.text).join(''); }
  get childElementCount(): number { return this.children.filter(c => typeof c !== 'string').length; }
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  setAttribute(name: string, value: string): void { this.attributes[name] = value; }
  addEventListener(event: string, handler: (event: Event) => void): void { this.listeners.set(event, handler); }
  querySelector(): null { return null; }
  querySelectorAll(selector: string): Element[] { return this.all().slice(1).filter(node => selector.split(', ').includes(node.tag) || node.matches(selector)); }
  matches(selector: string): boolean { return selector.startsWith('.') && this.classList.contains(selector.slice(1)); }
  all(): Element[] { return [this, ...this.children.flatMap(c => typeof c === 'string' ? [] : c.all())]; }
  click(): void { this.listeners.get('click')?.({ preventDefault() {}, stopPropagation() {} } as Event); }
}

afterEach(() => vi.unstubAllGlobals());

function setup(readOnly = false, configure?: (c: CharacterSheet) => void, visible = false) {
  let mini: Element | null = null;
  const frames: FrameRequestCallback[] = [];
  const observers: ResizeObserverCallback[] = [];
  vi.stubGlobal('document', {
    createElement: (tag: string) => new Element(tag),
    createElementNS: (_namespace: string, tag: string) => new Element(tag),
    addEventListener: () => {},
  });
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: ResizeObserverCallback) { observers.push(callback); }
    observe(): void {}
    disconnect(): void {}
  });
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.push(callback); });
  vi.stubGlobal('HTMLInputElement', class { static [Symbol.hasInstance](node: Element): boolean { return node.tag === 'input'; } });
  vi.stubGlobal('HTMLTextAreaElement', class { static [Symbol.hasInstance](node: Element): boolean { return node.tag === 'textarea'; } });
  const c = emptyCharacter('Hero');
  c.mancer = { done: true };
  const feature: Feature = { name: 'Magic Gift', source: 'Class', text: 'Complete gift description',
    automation: { uses: { max: 2, per: 'longRest' }, resources: [{ id: 'gift', label: 'Gift tokens', max: 4, initial: 0 }],
      actions: [{ id: 'gift', label: 'Gain Hope', steps: [{ op: 'gain', resource: 'hope', amount: 1 }] }] } };
  c.features = [feature];
  c.resources = { gift: 2 };
  c.quickAccessFavorites = [favoriteKey('feature', feature)];
  configure?.(c);
  const bus = new SheetBus();
  const changed = vi.fn();
  const commitDamage = vi.fn();
  const log = vi.fn();
  let content: Element;
  const view = renderSheetView({
    character: c, bus, readOnly: () => readOnly, onRoll: () => false, onChange: changed,
    onIncomingDamage: commitDamage, onLog: log,
    visible: () => visible, miniVisible: () => true, quickAccessVisible: () => true,
    onQuickAccess: node => { content = node as unknown as Element; },
    onMiniStrip: strip => { mini = strip as unknown as Element | null; },
  });
  return {
    c, bus, changed, commitDamage, log, content: () => content, mini: () => mini, view: view as unknown as Element,
    flushLayout: () => { frames.splice(0).forEach(callback => callback(0)); },
    resize: () => { observers[0]([], {} as ResizeObserver); },
  };
}

it('opens damage mitigation from a hidden sheet with the existing host and save/report callbacks', () => {
  vi.mocked(openDamageDialog).mockClear();
  const test = setup();
  expect(test.bus.takeDamage?.()).toBe(true);
  expect(openDamageDialog).toHaveBeenCalledOnce();
  const args = vi.mocked(openDamageDialog).mock.calls[0];
  expect(args[1]).toBe(test.c);
  expect(args[4]).toBe(test.commitDamage);
  args[2]('Marked 1 HP', 1, true);
  expect(test.log).toHaveBeenCalledWith('Marked 1 HP', undefined, undefined, true);
  expect(test.changed).toHaveBeenCalled();
});

it('does not open damage mitigation for read-only or unfinished characters', () => {
  vi.mocked(openDamageDialog).mockClear();
  for (const test of [
    setup(true),
    setup(false, c => { c.mancer = { done: false }; }),
    setup(false, c => { c.transformation = 'Shapeshifter'; c.ancestryFeature = undefined; }),
  ]) expect(test.bus.takeDamage?.()).toBe(false);
  expect(openDamageDialog).not.toHaveBeenCalled();
});

it('uses multiline Background fields and preserves edits, pronouns and blur-only renames', () => {
  const test = setup(false, c => {
    c.otherDetails = 'Other\nDetails';
    c.notes = 'Saved\nNotes';
    c.backgroundQuestions = ['Saved\nAnswer'];
    c.connections = ['Old\nFriend'];
  }, true);
  const button = (label: string) => test.view.all().find(node => node.tag === 'button' && node.text === label)!;
  button('Background').click();
  const areas = () => test.view.all().filter(node => node.className === 'bg-textarea');
  expect(areas()).toHaveLength(Object.keys(test.c.description).length + 4);
  expect(areas().every(node => node.tag === 'textarea' && node.readOnly)).toBe(true);
  test.view.all().find(node => node.className === 'sheet-edit-switch')!.click();
  expect(areas().every(node => !node.readOnly)).toBe(true);
  const change = (node: Element, value: string) => {
    node.value = value;
    node.listeners.get('input')!({ target: node } as unknown as Event);
  };
  const hair = areas().find(node => node.ariaLabel === 'Hair')!;
  change(hair, 'Long braids\nWith ribbons');
  expect(test.c.description.Hair).toBe('Long braids\nWith ribbons');
  change(areas().find(node => node.ariaLabel === 'Pronouns')!, 'she/her\nthey/them');
  expect(test.c.pronouns).toBe('she/her\nthey/them');
  const name = areas().find(node => node.ariaLabel === 'Name')!;
  const saves = test.changed.mock.calls.length;
  change(name, 'New\nName');
  expect(test.c.name).toBe('Hero');
  expect(test.c.description.Name).toBe('Hero');
  expect(test.changed).toHaveBeenCalledTimes(saves);
  name.listeners.get('blur')!({ target: name } as unknown as Event);
  expect(test.c.name).toBe('New\nName');
  expect(test.c.description.Name).toBe('New\nName');
  expect(test.changed).toHaveBeenCalledTimes(saves + 1);
  name.listeners.get('blur')!({ target: name } as unknown as Event);
  expect(test.changed).toHaveBeenCalledTimes(saves + 1);
  for (const [index, value] of ['Details\nEdited', 'Notes\nEdited', 'Answer\nEdited', 'Connection\nEdited'].entries()) {
    change(areas()[Object.keys(test.c.description).length + index], value);
  }
  expect([test.c.otherDetails, test.c.notes, test.c.backgroundQuestions[0], test.c.connections[0]])
    .toEqual(['Details\nEdited', 'Notes\nEdited', 'Answer\nEdited', 'Connection\nEdited']);
});

it('fits saved Background text, grows and shrinks on input and refits when width becomes available or changes', () => {
  const test = setup(false, c => { c.notes = 'A long saved note that wraps'; }, true);
  test.view.all().find(node => node.tag === 'button' && node.text === 'Background')!.click();
  const note = test.view.all().find(node => node.ariaLabel === 'Notes')!;
  test.flushLayout();
  expect(note.style.height).toBe('');
  note.clientWidth = 100;
  test.resize();
  test.flushLayout();
  expect(note.style.height).toBe(`${note.scrollHeight + 2}px`);
  const original = parseInt(note.style.height);
  note.value += '\nAnother long line that wraps';
  note.listeners.get('input')!({ target: note } as unknown as Event);
  expect(parseInt(note.style.height)).toBeGreaterThan(original);
  note.value = 'Short';
  note.listeners.get('input')!({ target: note } as unknown as Event);
  expect(note.style.height).toBe('32px');
  note.value = 'Text that wraps at different widths';
  note.clientWidth = 300;
  test.resize();
  test.flushLayout();
  const wideHeight = parseInt(note.style.height);
  note.clientWidth = 80;
  test.resize();
  test.flushLayout();
  expect(parseInt(note.style.height)).toBeGreaterThan(wideHeight);
});

it('keeps read-only Background fields visible but rejects input and rename commits', () => {
  const test = setup(true, c => { c.notes = 'Saved\nNotes'; }, true);
  test.view.all().find(node => node.tag === 'button' && node.text === 'Background')!.click();
  const areas = test.view.all().filter(node => node.className === 'bg-textarea');
  expect(areas.every(node => node.readOnly && !node.disabled)).toBe(true);
  const name = areas.find(node => node.ariaLabel === 'Name')!;
  name.value = 'Forbidden';
  name.listeners.get('input')!({ target: name } as unknown as Event);
  name.listeners.get('blur')!({ target: name } as unknown as Event);
  expect(test.c.name).toBe('Hero');
  const note = areas.find(node => node.ariaLabel === 'Notes')!;
  note.value = 'Forbidden';
  note.listeners.get('input')!({ target: note } as unknown as Event);
  expect(test.c.notes).toBe('Saved\nNotes');
  expect(test.changed).not.toHaveBeenCalled();
});

it('shows item artwork on carried equipment and loot with an accessible disclosure', () => {
  const image = (name: string) => `/item-icons/lorc/${name}.svg`;
  const test = setup(false, c => {
    const weapon = { name: 'Sword', image: image('sword'), slot: 'Primary' as const, trait: 'Agility' as const,
      damageType: 'phys' as const, damage: 'd6', range: 'Melee', hands: 1 as const, tier: 1 };
    c.activeWeapons = [weapon];
    c.carriedWeapons = [{ ...weapon, name: 'Carried sword', image: image('carried-sword') }];
    c.activeArmor = { name: 'Leather', image: image('leather'), score: 3, tier: 1 };
    c.carriedArmor = [{ name: 'Plate', image: image('plate'), score: 4, tier: 1 }];
    c.inventory = [{ name: 'Potion', image: image('potion'), type: 'consumable', qty: 2, note: 'Drink it.' }];
  }, true);
  test.view.all().find(node => node.tag === 'button' && node.text.startsWith('Equipment'))!.click();
  const sources = () => test.view.all().filter(node => node.tag === 'img').map(node => (node as unknown as { src: string }).src);
  for (const name of ['carried-sword', 'plate']) expect(sources()).toContain(image(name));
  for (const name of ['sword', 'leather']) expect(sources()).not.toContain(image(name));
  test.view.all().find(node => node.tag === 'button' && node.text.startsWith('Loot'))!.click();
  expect(sources()).toContain(image('potion'));
  const disclosure = test.view.all().find(node => node.className === 'loot-description-toggle')!;
  expect(disclosure.text).toBe('Potion (Unclassified rarity)');
  expect(disclosure.attributes['aria-label']).toBe("Show Potion's description (Unclassified rarity)");
});

it('places a take-damage button directly after Evasion on the minimized bar, disabled when read-only', () => {
  for (const readOnly of [false, true]) {
    const strip = setup(readOnly).mini()!.all().find(n => n.className === 'mini-strip')!;
    const top = strip.children.filter((n): n is Element => typeof n !== 'string');
    expect(top[0].classList.contains('mini-evasion')).toBe(true);
    const label = [top[1].attributes['title'], top[1].attributes['aria-label'], top[1].ariaLabel, (top[1] as unknown as { title?: string }).title].join(' ');
    expect(label).toMatch(/damage/i);
    expect(top[1].disabled).toBe(readOnly);
  }
});

it('shows only available resource blocks above the minimized controls and saves normal marking', () => {
  const test = setup(false, c => {
    c.scars = 1;
    c.hope.on = 0;
    c.hp.slots = 6;
    c.activeArmor = { name: 'Armor', score: 3, tier: 1 };
  });
  const resources = () => test.mini()!.all().find(n => n.className === 'mini-resources')!;
  const tracks = () => resources().all().filter(n => n.className === 'pips');
  expect(test.mini()!.children[0]).toBe(resources());
  expect(tracks().map(n => n.dataset.kind)).toEqual(['hp', 'stress', 'armor', 'hope']);
  expect(tracks().map(n => n.children.length)).toEqual([
    test.c.hp.slots, test.c.stress.slots, test.c.armorTrack.slots, test.c.hope.slots,
  ]);
  expect(resources().all().some(n => n.classList.contains('locked') || n.classList.contains('scarred'))).toBe(false);
  for (const kind of ['hp', 'stress', 'armor', 'hope']) {
    const track = () => tracks().find(n => n.dataset.kind === kind)!;
    const state = kind === 'armor' ? test.c.armorTrack : test.c[kind as 'hp' | 'stress' | 'hope'];
    const saves = test.changed.mock.calls.length;
    (track().children[1] as Element).click();
    expect(state.on).toBe(2);
    expect(track().all().filter(n => n.classList.contains('on'))).toHaveLength(2);
    (track().children[1] as Element).click();
    expect(state.on).toBe(1);
    expect(test.changed).toHaveBeenCalledTimes(saves + 2);
  }
});

it('refreshes minimized resource values and capacity on sheet notifications', async () => {
  const test = setup();
  const saves = test.changed.mock.calls.length;
  test.c.hp.on = 3;
  test.c.hp.slots = 8;
  test.bus.notify(false);
  await Promise.resolve();
  const hp = test.mini()!.all().find(n => n.dataset.kind === 'hp')!;
  expect(hp.children).toHaveLength(8);
  expect(hp.all().filter(n => n.classList.contains('on'))).toHaveLength(3);
  expect(test.changed).toHaveBeenCalledTimes(saves);
});

it('preserves HP healing locks and disables minimized resources when read-only', () => {
  const test = setup(false, c => {
    c.hp.slots = 6;
    c.permanentHp = 1;
    c.effects.push({ id: 'lock', name: 'HP lock', kind: 'debuff', lockedHp: 1, until: 'rest' });
    c.hp.on = 3;
  });
  const hp = () => test.mini()!.all().find(n => n.dataset.kind === 'hp')!;
  expect((hp().children[0] as Element).disabled).toBe(true);
  expect((hp().children[1] as Element).disabled).toBe(true);
  (hp().children[2] as Element).click();
  expect(test.c.hp.on).toBe(2);
  (hp().children[1] as Element).click();
  expect(test.c.hp.on).toBe(2);
  const readOnly = setup(true);
  const resources = readOnly.mini()!.all().find(n => n.className === 'mini-resources')!;
  const buttons = resources.all().filter(n => n.tag === 'button');
  expect(buttons.length).toBeGreaterThan(0);
  expect(buttons.every(n => n.disabled)).toBe(true);
  buttons.forEach(n => n.click());
  expect(readOnly.changed).not.toHaveBeenCalled();
});

it('renders only nonempty sections, with descriptions collapsed but feature actions, tokens and uses visible', () => {
  const test = setup();
  const content = test.content();
  expect(content.all().filter(n => n.tag === 'h3').map(n => n.text)).toEqual(['Character Features']);
  expect(content.text).toContain('Gain Hope');
  expect(content.text).toContain('Gift tokens');
  expect(content.text).toContain('2/2 per long rest');
  expect(content.all().find(n => n.className === 'quick-description')!.hidden).toBe(true);
  expect(content.all().find(n => n.tag === 'input')!.value).toBe('2');
});

it('retains description expansion on resource refresh, and saves favorite removal while minimized', async () => {
  const test = setup();
  test.content().all().find(n => n.className === 'quick-description-toggle')!.click();
  test.c.resources!.gift = 3;
  test.bus.notify(false);
  await Promise.resolve();
  expect(test.content().all().find(n => n.className === 'quick-description')!.hidden).toBe(false);
  expect(test.content().all().find(n => n.tag === 'input')!.value).toBe('3');
  const saves = test.changed.mock.calls.length;
  test.content().all().find(n => n.classList.contains('favorite-button'))!.click();
  expect(test.c.quickAccessFavorites).toEqual([]);
  expect(test.changed).toHaveBeenCalledTimes(saves + 1);
  expect(test.content().text).toContain('No active favorites.');
  expect(test.content().all().filter(n => n.tag === 'h3')).toEqual([]);
});

it('keeps descriptions available but disables favorite and gameplay controls on read-only sheets', () => {
  const test = setup(true);
  const controls = test.content().all().filter(n => ['button', 'input', 'select'].includes(n.tag));
  expect(controls.filter(n => n.className !== 'quick-description-toggle').every(n => n.disabled)).toBe(true);
  expect(controls.find(n => n.className === 'quick-description-toggle')!.disabled).toBe(false);
  controls.find(n => n.className === 'quick-description-toggle')!.click();
  expect(test.content().all().find(n => n.className === 'quick-description')!.hidden).toBe(false);
  expect(test.changed).not.toHaveBeenCalled();
});

it('executes shared gameplay actions and refreshes uses and token controls while minimized', () => {
  const test = setup(false, c => { c.hope.on = 0; });
  test.content().all().find(n => n.tag === 'button' && n.text === 'Gain Hope')!.click();
  expect(test.c.hope.on).toBe(1);
  expect(test.content().text).toContain('1/2 per long rest');
  const input = test.content().all().find(n => n.tag === 'input')!;
  input.valueAsNumber = 4;
  input.listeners.get('change')!({} as Event);
  expect(test.c.resources!.gift).toBe(4);
  expect(test.content().all().find(n => n.tag === 'input')!.value).toBe('4');
  expect(test.changed).toHaveBeenCalledTimes(3);
});

it('orders all sections, retains grimoire actions/counters, and omits equipment and inventory management', () => {
  const test = setup(false, c => {
    c.activeWeapons = [{ name: 'Sword', slot: 'Primary', trait: 'Agility', range: 'Melee', damage: 'd8', damageType: 'phys', hands: 1, tier: 1 }];
    c.loadout = [{ name: 'Book', level: 1, domain: 'Codex', type: 'Grimoire', text: 'All spells', recall: 1,
      automation: { resources: [{ id: 'book', label: 'Book tokens', initial: 0 }] },
      spells: [{ name: 'First spell', text: 'First description', automation: {
        uses: { max: 1, per: 'rest' }, actions: [{ id: 'spell', label: 'Invoke spell' }],
      } }] }];
    c.inventory = [{ name: 'Potion', note: 'Potion description', type: 'consumable', qty: 3, use: '1d4' }];
    c.quickAccessFavorites!.push(favoriteKey('weapon', c.activeWeapons[0]), favoriteKey('domain', c.loadout[0]), favoriteKey('item', c.inventory[0]));
  });
  const content = test.content();
  expect(content.all().filter(n => n.tag === 'h3').map(n => n.text)).toEqual(['Weapons', 'Domain Cards', 'Character Features', 'Items']);
  const buttons = content.all().filter(n => n.tag === 'button').map(n => n.text);
  expect(buttons).toContain('Damage');
  expect(buttons.some(text => text.startsWith('Attack'))).toBe(true);
  expect(buttons).toContain('Invoke spell');
  expect(buttons).toContain('Use');
  expect(content.text).toContain('1/1 per rest');
  expect(content.text).toContain('Tokens');
  expect(content.text).toContain('\u00d73');
  expect(buttons.join(' ')).not.toMatch(/Unequip|Move to vault|Recall|Attach|Delete|transfer/i);
  const spell = content.all().find(n => n.className === 'dspell')!;
  expect(spell.all().find(n => n.className === 'quick-description')!.hidden).toBe(true);
  spell.all().find(n => n.className === 'quick-description-toggle')!.click();
  expect(spell.all().find(n => n.className === 'quick-description')!.hidden).toBe(false);
  expect(spell.text).toContain('First description');
});
