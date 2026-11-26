import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { openMancer } from './mancer';
import { emptyChoices, loadRulesData, subclassesOf, type RulesData } from './rules';

vi.mock('./rules', async importOriginal => ({
  ...await importOriginal<typeof import('./rules')>(),
  loadRulesData: vi.fn(),
}));

class Element {
  className = '';
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

it('allows Brawler to choose the suggested generated strike without a secondary weapon', async () => {
  const { body, heading, button } = await open('Brawler');
  const strike = body.all().find(node => node.tag === 'button' && node.text().startsWith("Brawler's Strike"))!;
  expect(strike.text()).toContain('suggested');
  strike.click();
  button('Next').click();
  expect(heading()).toBe('Choose your armor');
  expect(body.all().find(node => node.tag === 'tr' && node.text().startsWith('Brigandine Armor'))!.text()).toContain('suggested');
  button('Back').click();
  body.all().find(node => node.tag === 'tr' && node.text().startsWith('Scimitar'))!.click();
  button('Next').click();
  expect(heading()).toBe('Choose your secondary weapon');
  body.all().find(node => node.tag === 'tr' && node.text().startsWith('Fighting Cloak'))!.click();
  button('Back').click();
  body.all().find(node => node.tag === 'button' && node.text().startsWith("Brawler's Strike"))!.click();
  button('Next').click();
  expect(heading()).toBe('Choose your armor');
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
