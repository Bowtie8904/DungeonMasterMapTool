import { afterEach, describe, expect, it, vi } from 'vitest';
import { emptyCharacter } from './data';
import { openTestingEditor } from './testing-view';
import { applyTestingBuild } from './testing';

vi.mock('./rules', async importOriginal => ({
  ...await importOriginal<typeof import('./rules')>(),
  loadRulesData: async () => ({
    classes: [{ name: 'Wizard', kind: 'class', domains: ['Codex'] }, { name: 'Wizard: School', parent: 'Wizard', kind: 'subclass', stage: 'foundation' }],
    ancestries: [{ name: 'Human' }], communities: [{ name: 'Highborne' }], transformations: [],
  }),
}));
vi.mock('./testing', async importOriginal => ({
  ...await importOriginal<typeof import('./testing')>(),
  applyTestingBuild: vi.fn(),
}));

class Element {
  children: Array<Element | string> = [];
  listeners: Record<string, () => void> = {};
  value = '';
  disabled = false;
  isConnected = true;
  textContent = '';
  get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Array<Element | string>): void { this.children = children; }
  addEventListener(event: string, handler: () => void): void { this.listeners[event] = handler; }
  remove(): void { this.isConnected = false; }
  all(tag: string): Element[] {
    return this.children.flatMap(child => typeof child === 'string' ? [] : [...(child.tag === tag ? [child] : []), ...child.all(tag)]);
  }
}

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('testing trait editor', () => {
  const setup = async () => {
    const body = new Element('body');
    vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag), addEventListener: () => {}, removeEventListener: () => {} });
    const c = Object.assign(emptyCharacter('Tester'), { testingCharacter: true });
    const done = vi.fn();
    await openTestingEditor({ ownerDocument: { body } } as unknown as HTMLElement, c, done);
    const inputs = body.all('input') as Array<Element & { ariaLabel: string; type: string; min?: string; max?: string; step: string }>;
    const apply = body.all('button').find(button => button.children.includes('Apply testing build'))!;
    return { body, c, done, inputs, apply };
  };

  it('uses unbounded integer spinners and applies draft values only on submit', async () => {
    const { c, inputs, apply, done } = await setup();
    const traits = inputs.filter(input => input.ariaLabel !== 'Testing level');
    expect(traits).toHaveLength(6);
    const before = structuredClone(c.traits);
    for (const [i, input] of traits.entries()) {
      expect(input.type).toBe('number');
      expect(input.step).toBe('1');
      expect(input.min).toBeUndefined();
      expect(input.max).toBeUndefined();
      input.value = String(i - 10);
      input.listeners.input();
    }
    expect(c.traits).toEqual(before);
    expect(apply.disabled).toBe(false);
    apply.listeners.click();
    expect(applyTestingBuild).toHaveBeenCalledWith(expect.anything(), c, expect.anything(),
      Object.fromEntries(c.traits.map((trait, i) => [trait.name, i - 10])));
    expect(done).toHaveBeenCalledOnce();
  });

  it('blocks empty/fractional values and discards edits on Cancel', async () => {
    const { c, body, inputs, apply, done } = await setup();
    const before = structuredClone(c);
    const trait = inputs.find(input => input.ariaLabel === 'Testing Agility')!;
    for (const value of ['', '1.5']) {
      trait.value = value;
      trait.listeners.input();
      expect(apply.disabled).toBe(true);
    }
    body.all('button').find(button => button.children.includes('Cancel'))!.listeners.click();
    expect(c).toEqual(before);
    expect(applyTestingBuild).not.toHaveBeenCalled();
    expect(done).not.toHaveBeenCalled();
  });
});
