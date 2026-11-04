import { afterEach, describe, expect, it, vi } from 'vitest';
import { rollButtons } from './rollbuttons';
import type { CharacterSheet } from './data';
import type { RollMessage } from '../protocol';
import { applyRest } from './engine';

class FakeElement {
  className = '';
  hidden = false;
  disabled = false;
  title = '';
  textContent = '';
  value = '';
  open = false;
  removed = false;
  get valueAsNumber(): number { return this.value === '' ? NaN : Number(this.value); }
  showModal(): void { this.open = true; }
  close(): void {
    this.open = false;
    for (const callback of this.listeners.get('close') ?? []) void callback();
  }
  remove(): void { this.removed = true; }
  focus(): void {}
  select(): void {}
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
    usage: {},
    resources: {},
    hope: { on: 2, slots: 6 },
    stress: { on: 2, slots: 6 },
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
  it('uses a styled Feed popup with inline validation, cancellation, retry and single submission', async () => {
    const body = new FakeElement('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'Could not save Feed' }), { status: 500 }))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', { name: 'Feed', automation: { rollTriggers: [{
      id: 'feed', label: 'Feed', on: 'attack', weapon: 'Fangs', cost: { stress: 1 },
      description: 'Use only after a successful Fangs attack against a creature that can bleed.',
      tokenGain: { resource: 'feedTokens', max: 6 },
    }] } });
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, source: 'attack,action', weapon: 'Fangs' }, host as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    const feed = box.queryAll('button').find((b) => b.text.startsWith('Feed'))!;
    await feed.click();
    let dialog = body.queryAll('dialog')[0];
    expect(dialog.open).toBe(true);
    expect(dialog.className).toBe('dh dh-rolldlg');
    expect(dialog.text).toContain('creature that can bleed');
    expect(fetch).not.toHaveBeenCalled();
    await dialog.queryAll('button')[0].click();
    expect(dialog.removed).toBe(true);
    expect(feed.disabled).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
    await feed.click();
    dialog = body.queryAll('dialog')[1];
    const amount = dialog.queryAll('input')[0];
    const confirm = dialog.queryAll('button')[1];
    for (const invalid of ['', '0', '7', '1.5']) {
      amount.value = invalid;
      await confirm.click();
      expect(dialog.text).toContain('Enter a whole number from 1 to 6.');
      expect(fetch).not.toHaveBeenCalled();
    }
    amount.value = '3';
    await confirm.click();
    expect(dialog.text).toContain('Could not save Feed');
    expect(dialog.open).toBe(true);
    expect(amount.value).toBe('3');
    expect(confirm.disabled).toBe(false);
    await Promise.all([confirm.click(), confirm.click()]);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      actorId: 'actor-b', key: 'feat:Feed', actionId: 'feed', roll: { id: original.id, ts: original.ts }, amount: 3,
    });
    expect(dialog.removed).toBe(true);
    expect(c.stress.on).toBe(2);
    expect(host.toast).not.toHaveBeenCalled();
  });

  it('offers Oasis on another players rest and sends exactly the selected die to the host', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Dune', { name: 'Oasis', automation: { restReroll: true, uses: { max: 1, per: 'rest' } } });
    const fetch = vi.fn(async (_input: string, _init?: RequestInit) => {
      c.usage['feat:Oasis'] = { used: 1, per: 'rest' };
      return { ok: true, status: 200 };
    });
    vi.stubGlobal('fetch', fetch);
    const { host } = hostFor({ 'actor-a': c });
    const box = rollButtons({
      id: 33, ts: 1200, fromId: 'player-b', from: 'Bob', actorId: 'actor-b',
      text: 'SHORT REST\nBob\nRecovery',
      restDice: [
        { line: 0, expression: '1d4+1', value: 2 },
        { line: 1, expression: '1d4+1', value: 3, resource: 'stress' },
      ],
    }, host as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')).toHaveLength(2);
    expect(box.queryAll('button')[1].text).toBe('Oasis: reroll Stress 1d4+1 · Dune');
    await box.queryAll('button')[1].click();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({
      actorId: 'actor-a', key: 'feat:Oasis', actionId: 'reroll', roll: { id: 33, ts: 1200 }, restDie: 1,
    });
    expect(box.queryAll('button')).toHaveLength(0);
    expect(box.hidden).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('shares Oasis usage across reports and restores buttons after either kind of rest', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Dune', { name: 'Oasis', automation: { restReroll: true, uses: { max: 1, per: 'rest' } } });
    const { host } = hostFor({ 'actor-a': c });
    const boxes = [33, 34].map(id => rollButtons({
      id, ts: 1200, fromId: 'player-b', from: 'Bob', actorId: `ally-${id}`,
      text: 'SHORT REST\nBob\nRecovery',
      restDice: [{ line: 0, expression: '1d4+1', value: 2, resource: 'hp' }],
    }, host as never) as unknown as FakeElement);
    const refresh = async () => {
      for (const box of boxes) for (const listener of box.listeners.get('refresh-roll-actions') ?? []) await listener();
      await new Promise(resolve => setTimeout(resolve, 0));
    };
    await refresh();
    expect(boxes.every(box => box.queryAll('button').length === 1)).toBe(true);
    for (const kind of ['short', 'long'] as const) {
      c.usage['feat:Oasis'] = { used: 1, per: 'rest' };
      await refresh();
      expect(boxes.every(box => box.hidden && box.queryAll('button').length === 0)).toBe(true);
      applyRest(c, kind);
      await refresh();
      expect(boxes.every(box => !box.hidden && box.queryAll('button').length === 1)).toBe(true);
    }
  });

  it('does not consume Oasis or hide its buttons when the host rejects the reroll', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Could not save' }), { status: 500 })));
    const c = character('Dune', { name: 'Oasis', automation: { restReroll: true, uses: { max: 1, per: 'rest' } } });
    const { host } = hostFor({ 'actor-a': c });
    const box = rollButtons({
      id: 33, ts: 1200, fromId: 'player-b', from: 'Bob', actorId: 'ally',
      text: 'SHORT REST\nBob\nRecovery',
      restDice: [{ line: 0, expression: '1d4+1', value: 2, resource: 'stress' }],
    }, host as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button')[0].click();
    expect(host.toast).toHaveBeenCalledWith('Could not save');
    expect(c.usage['feat:Oasis']).toBeUndefined();
    expect(box.queryAll('button')[0].disabled).toBe(false);
  });

  it('claims a Hope reward once through the host without locally granting Hope', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn(async (_input: string, _init?: RequestInit) => ({ ok: true, status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', undefined);
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, rolls: [9, 3], rewardClaimed: false }, {
      ...host, playerId: 'player-b', supportsRollRewards: () => true,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    const reward = box.queryAll('button')[0];
    expect(reward.text).toBe('Gain 1 Hope');
    await Promise.all([reward.click(), reward.click()]);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][0]).toBe('/api/roll-reward');
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      actorId: 'actor-b', roll: { id: original.id, ts: original.ts },
    });
    expect(c.hope.on).toBe(2);
    expect(box.queryAll('button')).toHaveLength(0);
  });

  it('offers critical recovery at capped Hope only when Stress is marked, and refreshes after resource edits', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', undefined);
    c.hope.on = c.hope.slots;
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, rolls: [8, 8], rewardClaimed: false }, {
      ...host, playerId: 'player-b', supportsRollRewards: () => true,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')[0].text).toBe('Gain 1 Hope / Clear 1 Stress');
    expect(box.queryAll('button')[0].disabled).toBe(false);
    c.stress.on = 0;
    for (const listener of box.listeners.get('refresh-roll-actions') ?? []) await listener();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')[0].disabled).toBe(true);
  });

  it('reports a failed claim and allows retry without changing resources or consuming the reward', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Could not save reward' }), { status: 500 }));
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', undefined);
    const { host } = hostFor({ 'actor-b': c });
    const roll = { ...original, rolls: [9, 3], rewardClaimed: false };
    const box = rollButtons(roll, {
      ...host, playerId: 'player-b', supportsRollRewards: () => true,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button')[0].click();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(host.toast).toHaveBeenCalledWith('Could not save reward');
    expect(roll.rewardClaimed).toBe(false);
    expect(c.hope.on).toBe(2);
    expect(box.queryAll('button')[0].disabled).toBe(false);
  });

  it.each([
    ['Fear', [3, 9], false, 'player-b', true],
    ['already claimed', [9, 3], true, 'player-b', true],
    ['legacy roll', [9, 3], undefined, 'player-b', true],
    ['other roller', [9, 3], false, 'player-a', true],
    ['older host', [9, 3], false, 'player-b', false],
  ] as const)('does not offer reward for %s', async (_name, rolls, rewardClaimed, playerId, supported) => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const { host } = hostFor({ 'actor-b': character('Bob', undefined) });
    const box = rollButtons({ ...original, rolls: [...rolls], rewardClaimed }, {
      ...host, playerId, supportsRollRewards: () => supported,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')).toHaveLength(0);
  });

  it('offers converted Hope rewards and disables capped Hope until a resource refresh', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', undefined);
    c.hope.on = c.hope.slots;
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, outcome: 'hope', rewardClaimed: false }, {
      ...host, playerId: 'player-b', supportsRollRewards: () => true,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')[0].disabled).toBe(true);
    c.hope.on--;
    for (const listener of box.listeners.get('refresh-roll-actions') ?? []) await listener();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')[0].disabled).toBe(false);
  });

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
