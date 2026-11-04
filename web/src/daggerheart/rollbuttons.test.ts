import { afterEach, describe, expect, it, vi } from 'vitest';
import { rollButtons } from './rollbuttons';
import type { CharacterSheet } from './data';
import type { RollMessage } from '../protocol';

class FakeElement {
  className = '';
  hidden = false;
  disabled = false;
  title = '';
  textContent = '';
  children: FakeElement[] = [];
  listeners = new Map<string, Array<() => unknown>>();
  constructor(readonly tagName: string) {}
  append(...nodes: Array<FakeElement | string>): void {
    for (const node of nodes) {
      if (typeof node === 'string') {
        const text = new FakeElement('#text');
        text.textContent = node;
        this.children.push(text);
      } else this.children.push(node);
    }
  }
  replaceChildren(...nodes: FakeElement[]): void { this.children = nodes; }
  addEventListener(type: string, callback: () => unknown): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback]);
  }
  async click(): Promise<void> {
    for (const callback of this.listeners.get('click') ?? []) await callback();
  }
  queryAll(tag: string): FakeElement[] {
    return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap((child) => child.queryAll(tag))];
  }
  get text(): string { return this.textContent + this.children.map((child) => child.text).join(''); }
}

function character(name: string, feature: { name: string; automation: object } | undefined): CharacterSheet {
  return {
    name,
    activeWeapons: [],
    features: feature ? [feature] : [],
    heritage: [],
    loadout: [],
    inventory: [],
    effects: [],
    resources: {},
  } as unknown as CharacterSheet;
}

function hostFor(sheets: Record<string, CharacterSheet>) {
  const sent: unknown[] = [];
  const logged: string[] = [];
  return {
    sent,
    logged,
    host: {
      actorIds: () => Object.keys(sheets),
      sheet: async (id: string) => {
        const character = sheets[id];
        return character ? {
          character,
          bus: { send: () => false, log: () => undefined, toast: () => undefined, notify: () => undefined } as never,
          save: Object.assign(() => undefined, { flush: async () => undefined }),
        } : undefined;
      },
      send: (roll: unknown) => { sent.push(roll); return true; },
      log: (text: string) => logged.push(text),
      toast: vi.fn(),
    },
  };
}

const original: RollMessage = {
  id: 21,
  ts: 1000,
  fromId: 'player-b',
  from: 'Bob',
  kind: 'duality',
  label: 'Agility action',
  expression: '2d12+2',
  rolls: [3, 9],
  total: 14,
  actorId: 'actor-b',
  source: 'action',
  breakdown: [
    { label: 'Hope', expression: '1d12', rolls: [3], total: 3 },
    { label: 'Fear', expression: '1d12', rolls: [9], total: 9 },
    { label: 'Modifier', expression: '+2', rolls: [], total: 2 },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe('cross-player roll buttons', () => {
  it('shows an ally-only feature on another player’s Fear action and sends one host action without local duplicate logging', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn(async (_input: string, _init?: RequestInit) => ({ ok: true, status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const source = character('Alice', {
      name: 'Hallowed Aura',
      automation: { rollTriggers: [{
        id: 'hope', label: 'Change Fear into Hope', on: 'action', when: 'fear',
        outcome: 'hope', audience: 'ally',
      }] },
    });
    const rolled = character('Bob', undefined);
    const { host, logged, sent } = hostFor({ 'actor-a': source, 'actor-b': rolled });
    const box = rollButtons(original, host as never) as unknown as FakeElement;
    await new Promise((resolve) => setTimeout(resolve, 0));
    const buttons = box.queryAll('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0].text).toContain('Alice');
    await buttons[0].click();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      actorId: 'actor-a', key: 'feat:Hallowed Aura', actionId: 'hope',
      roll: { id: 21, ts: 1000 },
    });
    expect(sent).toHaveLength(0);
    expect(logged).toHaveLength(0);
  });

  it('reconstructs a reroll from a persisted roll card and does not create a second feature log', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    vi.stubGlobal('fetch', vi.fn(async (_input: string, _init?: RequestInit) => ({ ok: true, status: 200 })));
    const roller = character('Bob', {
      name: 'Luckbender',
      automation: { rollTriggers: [{
        id: 'reroll', label: 'Luckbender reroll', on: 'action', audience: 'any', reroll: 'duality',
      }] },
    });
    const { host, logged, sent } = hostFor({ 'actor-b': roller });
    const box = rollButtons(original, host as never) as unknown as FakeElement;
    await new Promise((resolve) => setTimeout(resolve, 0));
    const buttons = box.queryAll('button');
    expect(buttons).toHaveLength(1);
    await buttons[0].click();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({
      actorId: 'actor-b',
      kind: 'duality',
      expression: '2d12+2',
      contributions: [
        { label: 'Hope', expression: '1d12' },
        { label: 'Fear', expression: '1d12' },
        { label: 'Modifier', expression: '+2' },
      ],
    });
    expect(logged).toHaveLength(0);
  });

  it('does not offer an outcome conversion for Hope or a critical roll', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const source = character('Alice', {
      name: 'Aura',
      automation: { rollTriggers: [{
        id: 'hope', label: 'Convert to Hope', on: 'action', outcome: 'hope', audience: 'any',
      }] },
    });
    const { host } = hostFor({ 'actor-a': source });
    for (const rolls of [[9, 3], [8, 8]]) {
      const box = rollButtons({ ...original, actorId: 'actor-b', rolls }, host as never) as unknown as FakeElement;
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(box.queryAll('button')).toHaveLength(0);
    }
  });
});
