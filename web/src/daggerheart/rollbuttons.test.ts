import { afterEach, describe, expect, it, vi } from 'vitest';
import { rerollRequest, rollButtons, rollContext } from './rollbuttons';
import type { CharacterSheet } from './data';
import type { ChatMessage, RollMessage } from '../protocol';
import { applyRest } from './engine';
import { renderLogEntry } from '../chat';

class FakeElement {
  className = '';
  hidden = false;
  disabled = false;
  title = '';
  textContent = '';
  value = '';
  max = '';
  setAttribute(): void {}
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
  it('carries Honed Proficiency from only the clicked attack into damage', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', undefined);
    const { host } = hostFor({ 'actor-b': c });
    const sheet = await host.sheet('actor-b');
    const damage = vi.fn(() => true);
    const box = rollButtons({ ...original, source: 'attack,action,proficiency:1', weapon: 'Dagger' },
      { ...host, sheet: async () => ({ ...sheet!, bus: { damage } }) } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button').find(b => b.text === 'Damage')!.click();
    expect(damage).toHaveBeenCalledWith('Dagger', false, 9, 1);
  });

  it('opens Focus Cannon damage only on its own Instinct roll without paying Focus again', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', { name: 'Focus Cannon', automation: {
      rollTriggers: [{ id: 'damage', label: 'Focus Cannon damage', on: 'feat:Focus Cannon', damage: { dice: 'd20+3', type: 'magic' } }],
    } });
    const { host } = hostFor({ 'actor-b': c });
    const sheet = await host.sheet('actor-b');
    const spellDamage = vi.fn(() => true);
    const box = rollButtons({ ...original, source: 'action,trait:Instinct,feat:Focus Cannon' },
      { ...host, sheet: async () => ({ ...sheet!, bus: { spellDamage } }) } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button').find(b => b.text === 'Focus Cannon damage')!.click();
    expect(spellDamage).toHaveBeenCalledWith('Focus Cannon damage', { dice: 'd20+3', type: 'magic' }, false, expect.any(Function), undefined);
  });

  it('labels Pummeljoy critical Melee claims with both additional rewards', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', { name: 'Pummeljoy', automation: { criticalMelee: { hope: 1, stress: 1, proficiency: 1 } } });
    c.activeWeapons = [{ name: 'Strike', range: 'Melee' }] as CharacterSheet['activeWeapons'];
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, source: 'attack,action', weapon: 'Strike', rolls: [7, 7], rewardClaimed: false },
      { ...host, playerId: 'player-b', supportsRollRewards: () => true }) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button').some(b => b.text === 'Gain 2 Hope / Clear 2 Stress')).toBe(true);
  });

  it.each([
    ['Relaxing Song', 'heal', 'Within Close range: clear 1 HP', 'hp', 'clear', 1],
    ['Heartbreaking Song', 'hope', 'Within Close range: gain 1 Hope', 'hope', 'gain', 1],
    ['Rally', 'stress', 'Clear 1 Stress', 'stress', 'clear', 1],
    ['Rousing Speech', 'stress', 'Clear 2 Stress', 'stress', 'clear', 2],
  ] as const)('renders clickable %s claims on action-used chat cards', async (song, id, label, resource, op, amount) => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    const message: ChatMessage = {
      id: 7, ts: 100, fromId: 'bard-player', from: 'Bard', text: `ACTION USED\n${song}\nBard`,
      actionBenefits: { sessionId: 'session', actorIds: ['a'], claimedBy: [],
        options: [{ id, label, steps: [{ op, resource, amount }] }] },
    };
    const { host } = hostFor({ a: character('Ally', undefined) });
    const supported = { ...host, playerId: 'ally-player', supportsPartyFeatures: () => true, sessionId: () => 'session' };
    const render = (playerId: string) => renderLogEntry({ kind: 'chat', m: message }, playerId, undefined, undefined,
      m => rollButtons(m, { ...supported, playerId })) as unknown as FakeElement;
    const card = render('ally-player');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(card.className).toContain('action-report');
    expect(card.queryAll('button').map(button => button.text)).toEqual([`${label} · Ally`]);
    expect(render('bard-player').queryAll('button')).toHaveLength(0);
    await card.queryAll('button')[0].click();
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ actorId: 'a', benefitId: id, roll: { id: 7, ts: 100 } });
    expect(card.queryAll('button')).toHaveLength(0);
    const updated = render('ally-player');
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(updated.queryAll('button')).toHaveLength(0);
  });

  it('renders performance claims on plain chat messages without requiring a parsed action report', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const message: ChatMessage = { id: 8, ts: 100, fromId: 'bard-player', from: 'Bard', text: 'Song',
      actionBenefits: { sessionId: 'session', actorIds: ['a'], claimedBy: [],
        options: [{ id: 'heal', label: 'Clear 1 HP', steps: [{ op: 'clear', resource: 'hp', amount: 1 }] }] } };
    const { host } = hostFor({ a: character('Ally', undefined) });
    const card = renderLogEntry({ kind: 'chat', m: message }, 'ally-player', undefined, undefined,
      m => rollButtons(m, { ...host, playerId: 'ally-player', supportsPartyFeatures: () => true, sessionId: () => 'session' })) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(card.queryAll('button').map(button => button.text)).toEqual(['Clear 1 HP · Ally']);
  });

  it('offers performance claims to eligible allies once per player, not once per actor or option', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    const message: ChatMessage = { id: 7, ts: 100, fromId: 'bard-player', from: 'Bard', text: 'ACTION USED\nRally\nBard',
      actionBenefits: { sessionId: 'session', actorIds: ['a', 'b'], claimedBy: [], options: [
        { id: 'hope', label: 'Gain Hope', steps: [{ op: 'gain', resource: 'hope', amount: 1 }] },
        { id: 'stress', label: 'Clear Stress', steps: [{ op: 'clear', resource: 'stress', amount: 1 }] },
      ] } };
    const { host } = hostFor({ a: character('First', undefined), b: character('Second', undefined), c: character('Ineligible', undefined) });
    const supported = { ...host, playerId: 'ally-player', supportsPartyFeatures: () => true, sessionId: () => 'session' };
    expect(rollButtons(message, { ...supported, supportsPartyFeatures: () => false })).toBeNull();
    expect(rollButtons(message, { ...supported, sessionId: () => 'old' })).toBeNull();
    expect(rollButtons(message, { ...supported, playerId: 'bard-player' })).toBeNull();
    const box = rollButtons(message, supported) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')).toHaveLength(4);
    await box.queryAll('button')[0].click();
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ actorId: 'a', benefitId: 'hope', roll: { id: 7, ts: 100 } });
    expect(box.queryAll('button')).toHaveLength(0);
    expect(message.actionBenefits!.claimedBy).toEqual(['ally-player']);
  });

  it('retains performance claims and displays errors when the host rejects a claim', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Nothing to clear' }), { status: 400 })));
    const message: ChatMessage = { id: 8, ts: 100, fromId: 'bard-player', from: 'Bard', text: 'Song',
      actionBenefits: { sessionId: 'session', actorIds: ['a'], claimedBy: [],
        options: [{ id: 'heal', label: 'Clear HP', steps: [{ op: 'clear', resource: 'hp', amount: 1 }] }] } };
    const { host } = hostFor({ a: character('Ally', undefined) });
    const box = rollButtons(message, { ...host, playerId: 'ally-player', supportsPartyFeatures: () => true, sessionId: () => 'session' }) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button')[0].click();
    expect(host.toast).toHaveBeenCalledWith('Nothing to clear');
    expect(message.actionBenefits!.claimedBy).toEqual([]);
    expect(box.queryAll('button')[0].disabled).toBe(false);
  });

  it('offers sender-only pending Risk recovery and flushes before the idempotent claim', async () => {
    const body = new FakeElement('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new FakeElement(tag) });
    const order: string[] = [];
    const fetch = vi.fn().mockImplementation(async () => { order.push('claim'); return new Response(null, { status: 204 }); });
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', undefined);
    c.hp = { on: 6, slots: 6, total: 12 };
    c.permanentHp = 2;
    c.pendingDeathRoll = { id: 21, ts: 1000 };
    const message: RollMessage = { ...original, kind: 'dice', deathMove: 'risk',
      deathResult: { outcome: 'hope', hope: 3, fear: 4, adjustedHope: 4, bonus: 1, budget: 4, claimed: false, undying: false } };
    const { host } = hostFor({ 'actor-b': c });
    const sheet = await host.sheet('actor-b');
    const supported = { ...host, playerId: original.fromId, supportsDeathMoveOptions: () => true,
      sheet: async () => ({ ...sheet!, save: Object.assign(() => {}, { flush: async () => { order.push('flush'); } }) }) };
    expect(rollContext(message)).toBeNull();
    expect(rollButtons(message, { ...supported, playerId: 'other' })).toBeNull();
    expect(rollButtons(message, { ...supported, supportsDeathMoveOptions: () => false })).toBeNull();
    const box = rollButtons(message, supported) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button').map(b => b.text)).toEqual(['Apply recovery']);
    await box.queryAll('button')[0].click();
    const inputs = body.queryAll('input');
    inputs[0].value = '2';
    inputs[2].value = '2';
    await body.queryAll('button').find(b => b.text === 'Apply recovery')!.click();
    expect(order).toEqual(['flush', 'claim']);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ actorId: 'actor-b', roll: { id: 21, ts: 1000 }, hp: 2, stress: 2 });
    expect(message.deathResult!.claimed).toBe(true);
    expect(box.queryAll('button')).toHaveLength(0);
  });

  it('requires permanent-cost confirmation before undying conversion and hides stale pending claims', async () => {
    const body = new FakeElement('body');
    vi.stubGlobal('document', { body, createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', undefined);
    c.hp = { on: 6, slots: 6, total: 12 };
    c.pendingDeathRoll = { id: 21, ts: 1000 };
    const message: RollMessage = { ...original, kind: 'dice', deathMove: 'risk',
      deathResult: { outcome: 'fear', hope: 3, fear: 9, adjustedHope: 3, bonus: 0, budget: 3, claimed: false, undying: true } };
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons(message, { ...host, playerId: original.fromId, supportsDeathMoveOptions: () => true }) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button')[0].click();
    expect(fetch).not.toHaveBeenCalled();
    await body.queryAll('button').find(b => b.text === "Confirm Won't Stay Dead")!.click();
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ actorId: 'actor-b', roll: { id: 21, ts: 1000 }, undying: true });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button').map(b => b.text)).toEqual(['Apply recovery']);
    c.pendingDeathRoll = undefined;
    for (const callback of box.listeners.get('refresh-roll-actions') ?? []) await callback();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button')).toHaveLength(0);
  });
  it('never offers ordinary rewards or reactions for death-move rolls', () => {
    const { host } = hostFor({});
    for (const deathMove of ['avoid', 'risk'] as const) {
      expect(rollButtons({
        id: 1, ts: 1, from: 'Player', fromId: 'player', actorId: 'actor',
        expression: '2d12', rolls: [9, 4], total: 13, kind: 'dice', deathMove,
      }, host)).toBeNull();
    }
  });
  it.each(['hope', 'duality'] as const)('preserves the reaction marker when rerolling %s', reroll => {
    const request = rerollRequest({ ...original, source: 'reaction,trait:Agility' }, {
      key: 'test', source: 'Reroll', automation: {}, trigger: { id: 'reroll', label: 'Reroll', on: 'duality', reroll },
    });
    expect(request?.source?.split(',')).toContain('reaction');
    expect(request?.source?.split(',')).not.toContain('action');
  });

  it.each([
    [[3, 9], false, 9],
    [[7, 7], true, 7],
    [[4, 18], false, 18],
  ] as const)('passes the clicked attack Fear Die into Damage (%s)', async (rolls, critical, fear) => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', undefined);
    const { host } = hostFor({ 'actor-b': c });
    const sheet = await host.sheet('actor-b');
    const damage = vi.fn(() => true);
    const box = rollButtons({ ...original, source: 'attack,action', weapon: 'Dagger', rolls: [...rolls] },
      { ...host, sheet: async () => ({ ...sheet!, bus: { damage } }) } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    await box.queryAll('button').find((b) => b.text === 'Damage')!.click();
    expect(damage).toHaveBeenCalledWith('Dagger', critical, fear, 0);
  });

  it('offers damage reactions on single-die damage, without Duality rewards or triggers', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const c = character('Bob', { name: 'Death Strike', automation: { rollTriggers: [
      { id: 'use', label: 'Death Strike', on: 'damage', cost: { stress: 1 } },
      { id: 'duality', label: 'Not for damage', on: 'duality' },
    ] } });
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, kind: undefined, source: 'damage', expression: '1d8', rolls: [5], total: 5, rewardClaimed: false },
      { ...host, playerId: original.fromId, supportsRollRewards: () => true } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button').map((b) => b.text)).toEqual(['Death Strike (1 Stress)']);
    await box.queryAll('button')[0].click();
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      actorId: 'actor-b', key: 'feat:Death Strike', actionId: 'use', roll: { id: original.id, ts: original.ts },
    });
  });

  it('disables poison reactions without tokens and filters out attacks without a weapon', async () => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const c = character('Bob', { name: 'Toxic Concoctions', automation: {
      resources: [{ id: 'poison', label: 'Poison tokens', initial: 0 }],
      rollTriggers: [{ id: 'poison', label: 'Afflict with poison', on: 'attack', weaponAttack: true, cost: { poison: 1 } }],
    } });
    const { host } = hostFor({ 'actor-b': c });
    const box = rollButtons({ ...original, source: 'attack,action', weapon: 'Dagger' }, host as never) as unknown as FakeElement;
    const unarmed = rollButtons({ ...original, source: 'attack,action' }, host as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(unarmed.queryAll('button')).toHaveLength(0);
    expect(box.queryAll('button').find((b) => b.text.startsWith('Afflict'))?.disabled).toBe(true);
    c.resources!.poison = 1;
    for (const refresh of box.listeners.get('refresh-roll-actions') ?? []) await refresh();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(box.queryAll('button').find((b) => b.text.startsWith('Afflict'))?.disabled).toBe(false);
  });

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

  it.each([[9, 3], [8, 8]])('never offers standard rewards on reaction rolls (%s)', async (hope, fear) => {
    vi.stubGlobal('document', { createElement: (tag: string) => new FakeElement(tag) });
    const { host } = hostFor({ 'actor-b': character('Bob', undefined) });
    const box = rollButtons({ ...original, source: 'trait:Agility,reaction', rolls: [hope, fear], rewardClaimed: false }, {
      ...host, playerId: 'player-b', supportsRollRewards: () => true,
    } as never) as unknown as FakeElement;
    await new Promise(resolve => setTimeout(resolve, 0));
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
