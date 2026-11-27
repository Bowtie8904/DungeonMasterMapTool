import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
import { COMMON_BACKGROUND_FIELDS, emptyCharacter } from './data';
import { openLevelUp, openMancer } from './mancer';
import { buildCharacter, emptyChoices, guideFor, loadRulesData, shortSubclass, startingCards, subclassesOf, suggestedTraits, type RulesData } from './rules';

vi.mock('./rules', async importOriginal => ({
  ...await importOriginal<typeof import('./rules')>(),
  loadRulesData: vi.fn(),
}));

class Element {
  className = '';
  value = '';
  disabled = false;
  classList = { toggle() {} };
  textContent = '';
  children: Array<Element | string> = [];
  parent?: Element;
  listeners: Record<string, (event: { key?: string; preventDefault(): void }) => void> = {};
  constructor(readonly tag: string) {}
  append(...nodes: Array<Element | string>) {
    for (const node of nodes) {
      if (node instanceof Element) node.parent = this;
      this.children.push(node);
    }
  }
  replaceChildren(...nodes: Array<Element | string>) { this.children = []; this.append(...nodes); }
  addEventListener(type: string, handler: (event: { key?: string; preventDefault(): void }) => void) { this.listeners[type] = handler; }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); }
  querySelector(selector: string): Element | null {
    return this.all().find(node => selector.startsWith('.') && node.className.split(' ').includes(selector.slice(1))) ?? null;
  }
  all(): Element[] { return [this, ...this.children.flatMap(child => child instanceof Element ? child.all() : [])]; }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
  click() { this.listeners.click?.({ preventDefault() {} }); }
}

const load = (name: string) => JSON.parse(readFileSync(resolve(__dirname, '../../../dmmap-daggerheart', `${name}.json`), 'utf8')).entries;
const data: RulesData = {
  classes: load('classes'), ancestries: load('ancestries'), communities: load('communities'), weapons: load('weapons'),
  armor: load('armor'), consumables: load('consumables'), cards: load('domain-cards'), transformations: load('transformations'),
  items: load('items'),
};

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

async function open(className: string) {
  const body = new Element('body');
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), addEventListener() {}, removeEventListener() {} });
  vi.mocked(loadRulesData).mockResolvedValue(data);
  const choices = { ...emptyChoices(), className, subclass: subclassesOf(data, className)[0], ancestry: 'Human', community: data.communities[0].name };
  const character = emptyCharacter('Hero');
  character.mancer = { done: false, choices };
  await openMancer({ ownerDocument: { body } } as unknown as HTMLElement, character, vi.fn());
  const heading = () => body.all().find(node => node.tag === 'h2')?.text();
  const button = (label: string) => body.all().find(node => node.tag === 'button' && node.text() === label)!;
  for (let i = 0; i < 10 && heading() !== 'Choose your primary weapon'; i++) button('Next').click();
  expect(heading()).toBe('Choose your primary weapon');
  return { body, heading, button };
}

it.each([
  ['Warlock', 'Scimitar', 'Fighting Cloak'],
  ['Witch', 'Casting Dagger', 'Rune Shield'],
])('highlights %s primary and secondary recommendations without selecting them', async (name, primary, secondary) => {
  const { body, heading, button } = await open(name);
  const primaryRow = body.all().find(node => node.tag === 'tr' && node.text().startsWith(primary))!;
  expect(primaryRow.text()).toContain('suggested');
  expect(primaryRow.className).not.toContain('sel');
  primaryRow.click();
  button('Next').click();
  expect(heading()).toBe('Choose your secondary weapon');
  const secondaryRow = body.all().find(node => node.tag === 'tr' && node.text().startsWith(secondary))!;
  expect(secondaryRow.text()).toContain('suggested');
  expect(secondaryRow.className).not.toContain('sel');
  secondaryRow.click();
  button('Next').click();
  expect(heading()).toBe('Choose your armor');
  const armorRow = body.all().find(node => node.tag === 'tr' && node.text().startsWith('Mage Robes'))!;
  expect(armorRow.text()).toContain('suggested');
});

it('does not offer Brawler\'s Strike in creation and requires an ordinary Brawler weapon', async () => {
  const { body, heading, button } = await open('Brawler');
  expect(body.text()).not.toContain("Brawler's Strike");
  body.all().find(node => node.tag === 'tr' && node.text().startsWith('Scimitar'))!.click();
  button('Next').click();
  expect(heading()).toBe('Choose your secondary weapon');
  body.all().find(node => node.tag === 'tr' && node.text().startsWith('Fighting Cloak'))!.click();
  button('Next').click();
  expect(heading()).toBe('Choose your armor');
  expect(body.all().find(node => node.tag === 'tr' && node.text().startsWith('Brigandine Armor'))!.text()).toContain('suggested');
});

it('highlights Assassin recommendations and skips the secondary step for the two-handed katana', async () => {
  const { body, heading, button } = await open('Assassin');
  const katana = body.all().find(node => node.tag === 'tr' && node.text().startsWith('Katana'))!;
  expect(katana.text()).toContain('suggested');
  katana.click();
  button('Next').click();
  expect(heading()).toBe('Choose your armor');
  expect(body.all().find(node => node.tag === 'tr' && node.text().startsWith('Brigandine Armor'))!.text()).toContain('suggested');
});

function backgroundWizardSetup() {
  const body = new Element('body');
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), addEventListener() {}, removeEventListener() {} });
  const d = structuredClone(data);
  d.classes.find(e => e.kind === 'class' && e.name === 'Bard')!.backgroundFields = ['Instrument'];
  d.classes.find(e => e.kind === 'class' && e.name === 'Warlock')!.backgroundFields = ['instrument', 'Patron', 'Age', 'Sphere of influence'];
  vi.mocked(loadRulesData).mockResolvedValue(d);
  const guide = guideFor('Bard');
  const choices = { ...emptyChoices(), name: 'Hero', className: 'Bard', subclass: subclassesOf(d, 'Bard')[0],
    ancestry: 'Human', community: d.communities[0].name, traits: suggestedTraits(guide),
    primary: 'Quarterstaff', armor: guide.armor, experiences: ['Singer', 'Traveler'],
    classItem: guide.classItems[0].charAt(0).toUpperCase() + guide.classItems[0].slice(1),
    cards: startingCards(d, 'Bard').slice(0, 2).map(e => e.name) };
  const character = buildCharacter(d, choices);
  const heading = () => body.all().find(node => node.tag === 'h2')?.text();
  const button = (label: string) => body.all().find(node => node.tag === 'button' && node.text() === label)!;
  const input = (label: string) => body.all().find(node => node.tag === 'label' && node.text().startsWith(label))!
    .all().find(node => node.tag === 'input')!;
  const fill = (label: string, value: string) => {
    const node = input(label);
    node.value = value;
    node.listeners.input?.({ preventDefault() {} });
  };
  return { body, d, character, heading, button, input, fill, host: { ownerDocument: { body } } as unknown as HTMLElement };
}

it('offers all shared and configured fields in creation and applies optional answers only on completion', async () => {
  const { body, character, heading, button, fill, host } = backgroundWizardSetup();
  character.mancer!.done = false;
  const done = vi.fn();
  await openMancer(host, character, done);
  for (let i = 0; i < 20 && heading() !== 'Who are you?'; i++) button('Next').click();
  expect(heading()).toBe('Who are you?');
  const labels = body.all().filter(node => node.tag === 'label').map(node => node.text());
  for (const field of [...COMMON_BACKGROUND_FIELDS, 'Instrument']) expect(labels.some(label => label.startsWith(field))).toBe(true);
  fill('Physique', 'Tall');
  fill('Instrument', 'Lute');
  expect(character.description.Instrument).toBe('');
  button('Next').click();
  button('Create character').click();
  expect(character.description).toMatchObject({ Physique: 'Tall', Instrument: 'Lute', Attitude: '' });
  expect(done).toHaveBeenCalledOnce();
});

it('offers only new non-duplicate multiclass fields, keeps edits local, and accepts blank answers', async () => {
  const { body, d, character, heading, button, fill, host } = backgroundWizardSetup();
  character.level = 4;
  character.description.Instrument = 'Lute';
  const before = structuredClone(character);
  const done = vi.fn();
  await openLevelUp(host, character, done);
  fill('New Experience', 'Witch hunter');
  button('Next').click();
  body.all().find(node => node.tag === 'button' && node.text().startsWith('Multiclass'))!.click();
  button('Next').click();
  body.all().find(node => node.tag === 'button' && node.text().startsWith('Warlock'))!.click();
  button('Dread').click();
  const subclass = subclassesOf(d, 'Warlock')[0];
  body.all().find(node => node.tag === 'button' && node.text().startsWith(shortSubclass(subclass)))!.click();
  button('Next').click();
  expect(heading()).toBe('Your new class background');
  expect(body.all().filter(node => node.tag === 'label').map(node => node.text())).toEqual(['Patron', 'Sphere of influence']);
  fill('Patron', 'The moon');
  expect(character).toEqual(before);
  button('Next').click();
  expect(heading()).toBe('Take a new domain card');
  body.all().find(node => node.tag === 'button' && node.className.startsWith('cho'))!.click();
  button('Next').click();
  button('Next').click();
  button('Level up!').click();
  expect(done).toHaveBeenCalledOnce();
  expect(character.description).toMatchObject({ Instrument: 'Lute', Patron: 'The moon', 'Sphere of influence': '' });
});
