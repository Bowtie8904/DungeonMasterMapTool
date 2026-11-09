import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import type { Automation } from './engine';
import { mockCharacter } from './data';
import {
  availableMoves, moveBlocker, resolveRest, restPassives, restSlots, restSummary, shortRestBlocker,
} from './rest';
import { parseRestReport } from './rest-summary';

const fixed = (n: number) => () => n;
function rules(category: string, name: string): Automation {
  const file: { entries: Array<{ name: string; automation?: Automation; features?: Array<{ name: string; automation?: Automation }> }> } =
    JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${category}.json`, import.meta.url), 'utf8'));
  const source = file.entries.flatMap((e) => [e, ...(e.features ?? [])]).find((e) => e.name === name);
  if (!source?.automation) throw new Error(`Missing automation: ${category}/${name}`);
  return structuredClone(source.automation);
}
const feature = (name: string, category = 'ancestries') => ({ source: 'test', name, text: '', automation: rules(category, name) });

describe('daggerheart rests', () => {
  it('uses metadata for renamed/homebrew sources, never names or prose', () => {
    const c = mockCharacter();
    c.heritage.push({ source: 'test', name: 'Unlisted ancestry', text: '', automation: {
      rest: { longMovesInShort: true, extraMoves: 1, passives: [{
        id: 'heal', label: 'Custom recovery', defaultOn: true, steps: [{ op: 'clear', resource: 'hp', amount: 2 }],
      }] },
      actions: [{ id: 'meditate', label: 'Custom ritual', timing: 'downtime', steps: [{ op: 'gain', resource: 'hope', amount: 2 }] }],
    } });
    c.hp.on = 5; c.hope.on = 0;
    expect(restSlots(c, 'short')).toHaveLength(3);
    const ritual = availableMoves(c, 'short').find((m) => m.id === 'act:feat:Unlisted ancestry|meditate');
    expect(ritual).toBeTruthy();
    resolveRest(c, 'short', [{ move: ritual!.id }]);
    expect(c.hp.on).toBe(3);
    expect(c.hope.on).toBe(2);
    const d = mockCharacter();
    d.heritage.push({ source: 'test', name: 'Efficient', text: 'As a downtime move, heal.' });
    d.inventory.push({ name: 'Premium Bedroll', type: 'gear', qty: 1 });
    expect(availableMoves(d, 'short').some((m) => m.id === 'tend-all')).toBe(false);
    expect(restPassives(d, 'short')).toEqual([]);
    expect(availableMoves(d, 'short').some((m) => m.id.startsWith('act:'))).toBe(false);
  });

  it('adds downtime options without granting slots and resolves configured roll tables', () => {
    const c = mockCharacter();
    c.loadout.push({ name: 'Forager', automation: rules('domain-cards', 'Forager') } as never);
    c.inventory.push({ name: 'Potioncraft Kit', type: 'gear', qty: 1, automation: rules('items', 'Potioncraft Kit') });
    const slots = restSlots(c, 'short');
    expect(slots).toHaveLength(2);
    const forage = 'act:card:Forager|forage';
    const ingredients = 'act:item:Potioncraft Kit|ingredients';
    for (const slot of slots) {
      expect(slot.moves.map((m) => m.id)).toEqual(expect.arrayContaining([forage, ingredients, 'tend']));
    }
    expect(restSlots(c, 'long')).toHaveLength(2);
    expect(() => resolveRest(c, 'short', [{ move: forage }, { move: ingredients }, { move: 'prepare' }])).toThrow('Too many downtime moves');
    const hp = c.hp.on, stress = c.stress.on;
    const result = resolveRest(c, 'short', [{ move: forage }, { move: ingredients }], {}, () => 1);
    expect(result.lines.some((l) => l.includes('host rolls for your foraged consumable'))).toBe(true);
    expect(result.lines.some((l) => l.includes('Heal'))).toBe(true);
    expect([c.hp.on, c.stress.on]).toEqual([hp, stress]);
    c.heritage.push(feature('Celestial Trance'));
    expect(restSlots(c, 'short')).toHaveLength(3);
    expect(restSlots(c, 'short')[2].moves.map((m) => m.id)).toEqual(expect.arrayContaining([forage, ingredients]));
  });

  it('only applies configured equipment benefits while equipped', () => {
    const c = mockCharacter();
    c.activeWeapons[0].automation = rules('weapons', 'Blessed Anlace');
    c.hp.on = 4;
    resolveRest(c, 'short', []);
    expect(c.hp.on).toBe(3);
    c.carriedWeapons.push(...c.activeWeapons);
    c.activeWeapons = [];
    resolveRest(c, 'short', []);
    expect(c.hp.on).toBe(3);
  });

  it('applies configured move modifiers and only consumes a benefit when its move is chosen', () => {
    const c = mockCharacter();
    c.hp.on = 4; c.stress.on = 4;
    c.loadout.push({ name: 'Soothing Speech', automation: rules('domain-cards', 'Soothing Speech') } as never);
    c.inventory.push({ name: 'Pipeweed', type: 'consumable', qty: 2, automation: rules('consumables', 'Pipeweed') });
    const o = { passives: { 'item:Pipeweed|smoke': true } };
    const result = resolveRest(c, 'short', [{ move: 'tend', ally: true }], o, () => 2);
    expect(c.hp.on).toBe(2);
    expect(result.lines[0]).toContain('+1 Soothing Speech');
    expect(c.inventory.find((i) => i.name === 'Pipeweed')!.qty).toBe(2);
    resolveRest(c, 'short', [{ move: 'stress' }], o, () => 2);
    expect(c.stress.on).toBe(1);
    expect(c.inventory.find((i) => i.name === 'Pipeweed')!.qty).toBe(1);
  });

  it('keeps an existing Safe Haven extra move free and expires it after rest', () => {
    const c = mockCharacter();
    c.hope.on = 2;
    c.loadout.push({ name: 'Safe Haven', automation: rules('domain-cards', 'Safe Haven') } as never);
    c.effects.push({ id: 'haven', name: 'Safe Haven', kind: 'buff', until: 'rest', rest: { extraMoves: 1 } });
    c.inventory.push({ name: 'Potion of Stability', type: 'consumable', qty: 1, automation: rules('consumables', 'Potion of Stability') });
    const o = { passives: { 'card:Safe Haven|shelter': true, 'item:Potion of Stability|drink': true } };
    expect(restSlots(c, 'short', o)).toHaveLength(4);
    const before = structuredClone(c);
    expect(() => resolveRest(c, 'short', [{ move: 'tend' }, { move: 'tend' }], o)).toThrow();
    expect(c).toEqual(before);
    resolveRest(c, 'short', [], o);
    expect(c.hope.on).toBe(2);
    expect(c.effects.some(e => e.name === 'Safe Haven')).toBe(false);
    expect(c.inventory.find((i) => i.name === 'Potion of Stability')!.qty).toBe(0);
  });

  it('requires host casting before a Safe Haven downtime checkbox can be resolved', () => {
    const c = mockCharacter();
    c.hope.on = 2;
    c.loadout.push({ name: 'Safe Haven', automation: rules('domain-cards', 'Safe Haven') } as never);
    const options = { passives: { 'card:Safe Haven|shelter': true } };
    expect(restSlots(c, 'short', options)).toHaveLength(3);
    expect(() => resolveRest(c, 'short', [], options)).toThrow('Cast the selected Safe Haven');
    expect(c.hope.on).toBe(2);
  });

  it('enforces configured rest kinds and rolls back a failure after an earlier move', () => {
    const c = mockCharacter();
    c.hope.on = 3;
    c.features.push({ source: 'test', name: 'Homebrew', text: '', automation: {
      actions: [
        { id: 'first', label: 'First', timing: 'downtime', cost: { hope: 2 } },
        { id: 'second', label: 'Second', timing: 'downtime', cost: { hope: 2 } },
        { id: 'long', label: 'Long only', timing: 'downtime', downtime: { rest: 'long' } },
      ],
    } });
    expect(availableMoves(c, 'short').some((m) => m.id.endsWith('|long'))).toBe(false);
    expect(availableMoves(c, 'long').some((m) => m.id.endsWith('|long'))).toBe(true);
    const before = structuredClone(c);
    expect(() => resolveRest(c, 'short', [{ move: 'act:feat:Homebrew|first' }, { move: 'act:feat:Homebrew|second' }])).toThrow();
    expect(c).toEqual(before);
  });

  it('offers two moves, short moves in a short rest and long moves in a long rest', () => {
    const c = mockCharacter();
    expect(restSlots(c, 'short')).toHaveLength(2);
    const short = availableMoves(c, 'short').map((m) => m.id);
    expect(short).toContain('tend');
    expect(short).not.toContain('tend-all');
    const long = availableMoves(c, 'long').map((m) => m.id);
    expect(long).toContain('tend-all');
    expect(long).not.toContain('tend');
  });

  it('lets Clank Efficient and the Recovery card take long moves in a short rest', () => {
    const c = mockCharacter();
    c.heritage.push(feature('Efficient'));
    expect(availableMoves(c, 'short').map((m) => m.id)).toContain('stress-all');
    const d = mockCharacter();
    d.loadout.push({ name: 'Recovery', automation: rules('domain-cards', 'Recovery') } as never);
    expect(availableMoves(d, 'short').map((m) => m.id)).toContain('repair-all');
  });

  it('rejects choosing the same move twice', () => {
    const c = mockCharacter();
    expect(() => resolveRest(c, 'short', [{ move: 'tend' }, { move: 'tend' }])).toThrow(/already chosen/);
    expect(() => resolveRest(c, 'short', [{ move: 'prepare' }, { move: 'prepare-group' }])).toThrow(/already chosen/);
    expect(moveBlocker('short', [{ move: 'tend' }, { move: '' }], 1, 'tend')).toBeTruthy();
  });

  it('allows only one long-rest move in a short rest', () => {
    const c = mockCharacter();
    c.heritage.push(feature('Efficient'));
    c.hp.on = 5; c.stress.on = 5;
    expect(() => resolveRest(c, 'short', [{ move: 'tend-all' }, { move: 'stress-all' }])).toThrow(/only one long-rest move/);
    resolveRest(c, 'short', [{ move: 'tend-all' }, { move: 'stress' }], {}, fixed(1));
    expect(c.hp.on).toBe(0);
  });

  it('adds extra move slots', () => {
    const c = mockCharacter();
    c.heritage.push(feature('Celestial Trance'));
    c.inventory.push({ name: "Timekeeper's Pendant", type: 'gear', qty: 1, automation: rules('items', 'Timekeeper\u2019s Pendant') });
    expect(restSlots(c, 'short')).toHaveLength(4);
  });

  it('prepares alone for 1 Hope and with others for 2', () => {
    const c = mockCharacter();
    c.hope.on = 0;
    resolveRest(c, 'short', [{ move: 'prepare' }], {}, fixed(1));
    expect(c.hope.on).toBe(1);
    c.shortRestsInRow = 0; c.hope.on = 0;
    resolveRest(c, 'short', [{ move: 'prepare-group' }], {}, fixed(1));
    expect(c.hope.on).toBe(2);
  });

  it('clears HP and Stress by 1d4+Tier', () => {
    const c = mockCharacter();
    c.hp.on = 5; c.stress.on = 5;
    const picks = [{ move: 'tend' }, { move: 'stress' }];
    const r = resolveRest(c, 'short', picks, {}, fixed(2));
    expect(c.hp.on).toBe(3);
    expect(c.stress.on).toBe(3);
    expect(r.lines).toHaveLength(2);
  });

  it('lets players untick a passive such as the Premium Bedroll', () => {
    const make = () => { const c = mockCharacter(); c.stress.on = 3; c.inventory.push({
      name: 'Premium Bedroll', type: 'gear', qty: 1, automation: rules('items', 'Premium Bedroll'),
    }); return c; };
    const a = make();
    expect(restPassives(a, 'short').map((p) => p.id)).toContain('item:Premium Bedroll|recovery');
    resolveRest(a, 'short', []);
    expect(a.stress.on).toBe(2);
    const b = make();
    resolveRest(b, 'short', [], { passives: { 'item:Premium Bedroll|recovery': false } });
    expect(b.stress.on).toBe(3);
  });

  it('records downtime dice for explicit Oasis reactions instead of automatically rerolling', () => {
    const c = mockCharacter();
    c.heritage.push(feature('Oasis', 'communities'));
    c.hp.on = 6;
    const rolls = [1, 4];
    const result = resolveRest(c, 'short', [{ move: 'tend' }], {}, () => rolls.shift()!);
    expect(c.hp.on).toBe(5);
    expect(rolls).toEqual([4]);
    expect(result.dice).toEqual([{ line: 0, expression: '1d4+2', value: 1, resource: 'hp', recovery: 5, cleared: 1 }]);
    expect(restPassives(c, 'short').some(p => p.id.includes('Oasis'))).toBe(false);
    const d = mockCharacter();
    d.heritage.push(feature('Oasis', 'communities'));
    d.hp.on = 6;
    resolveRest(d, 'short', [{ move: 'tend' }], { passives: { 'feat:Oasis|reroll': false } }, () => 1);
    expect(d.hp.on).toBe(5);
  });

  it('records capped and configured recovery dice for later rest reactions', () => {
    const c = mockCharacter();
    c.hp.on = 1;
    const capped = resolveRest(c, 'short', [{ move: 'tend' }], {}, () => 4);
    expect(capped.dice?.[0]).toMatchObject({ resource: 'hp', cleared: 1, recovery: 0 });
    c.hp.on = 5;
    c.features.push({ source: 'Custom', name: 'Recovery ritual', text: '', automation: {
      actions: [{ id: 'heal', label: 'Recover', timing: 'downtime', steps: [{ op: 'clear', resource: 'hp', amount: '1d4' }] }],
    } });
    const custom = resolveRest(c, 'short', [{ move: 'act:feat:Recovery ritual|heal' }], {}, () => 2);
    expect(custom.dice?.[0]).toMatchObject({ expression: '1d4', value: 2, resource: 'hp', cleared: 2, recovery: 3 });
    expect(c.hp.on).toBe(3);
  });

  it('formats the chat report so it can be recognised and parsed', () => {
    const c = mockCharacter();
    const picks = [{ move: 'prepare' }];
    const text = restSummary(c, 'long', picks, { lines: ['A', 'B'] });
    expect(text.startsWith('LONG REST\n')).toBe(true);
    expect(parseRestReport('\u{1F3D5}\uFE0F SHORT REST\nsub')?.kind).toBe('short');
    expect(text.length).toBeLessThan(1000);
    expect(parseRestReport(text)).toEqual({ kind: 'long', subtitle: `${c.name} \u00B7 1 downtime move`, lines: ['A', 'B'] });
    expect(parseRestReport('hello there')).toBeNull();
  });
  it('long rest clears everything', () => {
    const c = mockCharacter();
    c.hp.on = 6; c.stress.on = 4;
    resolveRest(c, 'long', [{ move: 'tend-all' }, { move: 'stress-all' }]);
    expect([c.hp.on, c.stress.on]).toEqual([0, 0]);
  });

  it('forces a long rest after three short rests', () => {
    const c = mockCharacter();
    for (let i = 0; i < 3; i++) resolveRest(c, 'short', []);
    expect(shortRestBlocker(c, 'short')).not.toBeNull();
    resolveRest(c, 'long', []);
    expect(shortRestBlocker(c, 'short')).toBeNull();
  });

  it('offers the Warlock tribute downtime move', () => {
    const c = mockCharacter();
    c.features.push({
      source: 'Class', name: 'Favor', text: '',
      automation: {
        resources: [{ id: 'favor', label: 'Favor', initial: 3 }],
        actions: [{ id: 'tribute', label: 'Downtime tribute', timing: 'downtime', steps: [{ op: 'gain', resource: 'favor', amount: 2 }] }],
      },
    });
    const move = availableMoves(c, 'short').find((m) => m.id.startsWith('act:feat:Favor'));
    expect(move).toBeTruthy();
    const r = resolveRest(c, 'short', [{ move: move!.id }]);
    expect(r.lines[0]).toContain('Downtime tribute');
    expect(c.resources?.favor).toBe(5);
  });

  it('resolves configured downtime crafting and consumes downtime consumables only during a rest', () => {
    const c = mockCharacter();
    c.stress.on = 0;
    c.inventory.push(
      { name: 'Vial of Darksmoke Recipe', type: 'gear', qty: 1, automation: rules('items', 'Vial of Darksmoke Recipe') },
      { name: 'Mucin of Slipperiness', type: 'consumable', qty: 1, automation: rules('consumables', 'Mucin of Slipperiness') },
    );
    const moves = availableMoves(c, 'short');
    const recipe = moves.find((m) => m.id === 'act:item:Vial of Darksmoke Recipe|use');
    const mucin = moves.find((m) => m.id === 'act:item:Mucin of Slipperiness|use');
    expect(recipe).toBeTruthy();
    expect(mucin).toBeTruthy();
    expect(moves.some((m) => m.id === 'nar:Vial of Darksmoke Recipe')).toBe(false);
    resolveRest(c, 'short', [{ move: recipe!.id }, { move: mucin!.id }]);
    expect(c.stress.on).toBe(1);
    expect(c.inventory.find((i) => i.name === 'Mucin of Slipperiness')!.qty).toBe(0);
    expect(c.effects.some((e) => e.name === 'Restraint Resistance' && e.source === 'Mucin of Slipperiness: Use')).toBe(true);
    resolveRest(c, 'short', []);
    expect(c.effects.some((e) => e.name === 'Restraint Resistance')).toBe(false);
  });

  it("adds Insomniac's Periapt only when nothing was cleared", () => {
    const c = mockCharacter();
    c.hp.on = 0; c.stress.on = 0;
    c.inventory.push({ name: "Insomniac's Periapt", type: 'gear', qty: 1, automation: rules('items', 'Insomniac\u2019s Periapt') });
    resolveRest(c, 'short', []);
    expect(c.effects.map((e) => e.name)).toContain('Attack and Damage +2');
  });
});
