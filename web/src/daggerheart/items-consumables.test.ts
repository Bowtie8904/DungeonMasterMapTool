import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { mockCharacter, type CharacterSheet, type InventoryItem, type Weapon } from './data';
import {
  actionBlocker, availableRollOptions, derivedStats, evalValue, rollOptionDice, runActionDetailed, selectionBlocker, syncResources, traitTotal, useConsumableUnit,
  withSelectedAmount, type ActionDef, type Automation,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { attachBlocker, attachItem, attachTargets, detachItem } from './attachments';
import { resolveRest } from './rest';

type Entry = { id: string; name: string; text: string; automation?: Automation };
const load = (file: string): Entry[] => JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${file}.json`, import.meta.url), 'utf8')).entries;
const items = load('items');
const consumables = load('consumables');
const rules = (list: Entry[], name: string) => structuredClone(list.find(e => e.name === name)!.automation!);
const fixed = (...faces: number[]) => { let i = 0; return () => faces[i++ % faces.length]; };

function sheet(mutate?: (c: CharacterSheet) => void): CharacterSheet {
  const c = mockCharacter();
  c.heritage = []; c.features = []; c.loadout = []; c.vault = []; c.inventory = []; c.effects = []; c.usage = {}; c.resources = {};
  c.hp.on = 0; c.stress.on = 0; c.hope.on = 2; c.armorTrack.on = 0; c.scars = 0; c.permanentHp = 0;
  mutate?.(c);
  syncResources(c);
  return c;
}
function carry(c: CharacterSheet, list: Entry[], name: string, qty = 1): InventoryItem {
  const item: InventoryItem = { name, type: list === consumables ? 'consumable' : 'gear', qty, automation: rules(list, name) };
  c.inventory.push(item);
  syncResources(c);
  return item;
}
const action = (item: InventoryItem, id: string): ActionDef => item.automation!.actions!.find(a => a.id === id)!;

describe('item and consumable catalog', () => {
  it('gives every consumable an executable automation', () => {
    const missing = consumables.filter(e => {
      const a = e.automation;
      return !a || !(a.actions?.length || a.rollOptions?.length || a.rest?.passives?.length || a.mitigation?.length || a.deathMove);
    }).map(e => e.name);
    expect(missing).toEqual([]);
  });

  it('only references custom resources that the entry declares', () => {
    const builtIn = new Set(['hp', 'stress', 'hope', 'armor', 'gold', 'scars', 'permanentHp']);
    const problems: string[] = [];
    for (const e of [...items, ...consumables]) {
      const declared = new Set((e.automation?.resources ?? []).map(r => r.id));
      const json = JSON.stringify(e.automation ?? {});
      for (const m of json.matchAll(/"resource":"([^"]+)"/g)) if (!builtIn.has(m[1]) && !declared.has(m[1])) problems.push(`${e.name}: ${m[1]}`);
      for (const m of json.matchAll(/"cost":\{([^}]*)\}/g))
        for (const key of [...m[1].matchAll(/"([^"]+)":/g)].map(k => k[1])) if (!builtIn.has(key) && !declared.has(key)) problems.push(`${e.name}: cost ${key}`);
    }
    expect(problems).toEqual([]);
  });

  it('recipes craft existing catalog consumables through the host', () => {
    const recipes = items.flatMap(e => (e.automation?.actions ?? []).filter(a => a.downtime?.craft).map(a => ({ name: e.name, craft: a.downtime!.craft!, timing: a.timing })));
    expect(recipes.map(r => r.name).sort()).toEqual(['Minor Health Potion Recipe', 'Minor Stamina Potion Recipe', 'Mythic Dust Recipe', 'Vial of Darksmoke Recipe']);
    for (const r of recipes) {
      expect(consumables.some(e => e.id === r.craft), r.craft).toBe(true);
      expect(r.timing).toBe('downtime');
    }
    const c = sheet();
    carry(c, items, 'Minor Health Potion Recipe');
    const result = resolveRest(c, 'long', [{ move: 'act:item:Minor Health Potion Recipe|downtime-narrative' }]);
    expect(result.lines.join('\n')).toContain('the host adds the crafted consumable');
  });

  it('no longer burns uses or stock on buttons that only duplicate mitigation', () => {
    expect(rules(items, 'Ring of Resistance').actions).toBeUndefined();
    expect(rules(consumables, 'Mirror of Marigold').actions).toBeUndefined();
    expect(rules(items, 'Veilcloak').uses).toBeUndefined();
    expect(rules(items, 'Warp Pendant').actions![0].cost).toEqual({ stress: 1 });
  });
});

describe('rolled tables and selected amounts', () => {
  it('Ancient Runestone spends a Hope and applies the rolled outcome', () => {
    const c = sheet(c => { c.stress.on = 3; });
    const stone = carry(c, items, 'Ancient Runestone');
    const log = runActionDetailed(c, 'item:Ancient Runestone', stone.automation!, action(stone, 'use'), 'Ancient Runestone', fixed(6))!;
    expect(c.hope.on).toBe(1);
    expect(c.stress.on).toBe(0);
    expect(log).toContain('rolled 1d6 = 6: Clear all Stress.');
    runActionDetailed(c, 'item:Ancient Runestone', stone.automation!, action(stone, 'use'), 'Ancient Runestone', fixed(4));
    expect(c.effects.map(e => e.name)).toContain('Runestone: Spellcast Advantage');
  });

  it('Armor Stitcher spends the chosen Hope and clears that many Armor Slots', () => {
    const c = sheet(c => { c.hope.on = 3; c.armorTrack.on = 3; });
    const stitcher = carry(c, consumables, 'Armor Stitcher');
    const chosen = withSelectedAmount(action(stitcher, 'use'), 2);
    runActionDetailed(c, 'item:Armor Stitcher', stitcher.automation!, chosen, 'Armor Stitcher');
    expect(c.hope.on).toBe(1);
    expect(c.armorTrack.on).toBe(1);
  });

  it('Backsies Stone needs at least one Hope and spends all of it', () => {
    const c = sheet(c => { c.hope.on = 0; c.resources = { feedTokens: 3 }; });
    expect(evalValue('@feedTokens', c)).toBe(3);
    const stone = carry(c, consumables, 'Backsies Stone');
    expect(actionBlocker(c, 'item:Backsies Stone', stone.automation!, action(stone, 'use'))).toBe('Not enough Hope');
    c.hope.on = 4;
    runActionDetailed(c, 'item:Backsies Stone', stone.automation!, action(stone, 'use'), 'Backsies Stone');
    expect(c.hope.on).toBe(0);
  });
});

describe('consumable portions and units', () => {
  it('Boomberry Jam keeps the jar until its last portion and throws with the remaining portions', () => {
    const c = sheet();
    const jam = carry(c, consumables, 'Boomberry Jam', 2);
    expect(c.resources!.jam_portions).toBe(5);
    const eat = action(jam, 'eat');
    for (let i = 0; i < 4; i++) {
      runActionDetailed(c, 'item:Boomberry Jam', jam.automation!, eat, 'Boomberry Jam');
      useConsumableUnit(c, jam, eat);
    }
    expect(jam.qty).toBe(2);
    expect(c.resources!.jam_portions).toBe(1);
    runActionDetailed(c, 'item:Boomberry Jam', jam.automation!, eat, 'Boomberry Jam');
    useConsumableUnit(c, jam, eat);
    expect(jam.qty).toBe(1);
    expect(c.resources!.jam_portions).toBe(5);
    useConsumableUnit(c, jam, action(jam, 'throw'));
    expect(c.inventory).not.toContain(jam);
  });

  it('keeps reusable consumable actions and uses up ordinary ones', () => {
    const c = sheet();
    const flower = carry(c, consumables, 'Aetherflower');
    useConsumableUnit(c, flower, action(flower, 'sense'));
    expect(flower.qty).toBe(1);
    const moss = carry(c, consumables, 'Vial of Moondrip');
    runActionDetailed(c, 'item:Vial of Moondrip', moss.automation!, action(moss, 'use'), 'Vial of Moondrip');
    useConsumableUnit(c, moss, action(moss, 'use'));
    expect(c.inventory).not.toContain(moss);
    expect(c.effects.find(e => e.name === 'Darkvision')?.until).toBe('rest');
  });

  it('Hero\u2019s Powder offers a die size chosen in the roll dialog, d4 by default', () => {
    const c = sheet();
    const powder = carry(c, consumables, 'Hero\u2019s Powder');
    runActionDetailed(c, 'item:Hero\u2019s Powder', powder.automation!, action(powder, 'use'), 'Hero\u2019s Powder');
    const option = availableRollOptions(c, ['action', 'trait:Agility']).find(o => o.option.dieSizes);
    expect(option?.option.dieSizes).toEqual([4, 6, 8, 10, 12, 20]);
    expect(rollOptionDice(c, option!.option)).toBe('1d4');
    expect(availableRollOptions(c, ['reaction', 'trait:Agility']).some(o => o.option.dieSizes)).toBe(true);
  });

  it('Superior Alconite Crystal is offered only while charged', () => {
    const c = sheet();
    carry(c, items, 'Superior Alconite Crystal');
    const offered = () => availableRollOptions(c, ['action', 'spellcast', 'trait:Knowledge']).some(o => o.option.expendOn === 4);
    expect(offered()).toBe(true);
    c.resources!.superior_alconite = 0;
    expect(offered()).toBe(false);
  });
});

describe('thrown consumables', () => {
  it('Unstable Arcane Shard leaves a pending damage effect whose damage dialog can be sent', () => {
    const c = sheet();
    const shard = carry(c, consumables, 'Unstable Arcane Shard');
    runActionDetailed(c, 'item:Unstable Arcane Shard', shard.automation!, action(shard, 'throw'), 'Unstable Arcane Shard');
    useConsumableUnit(c, shard, action(shard, 'throw'));
    expect(c.inventory).not.toContain(shard);
    const effect = c.effects.find(e => e.name === 'Unstable Arcane Shard: damage')!;
    expect(effect.source).toBe('Unstable Arcane Shard');
    const damage = effect.actions!.find(a => a.damage)!;
    const pending = { key: `effect:${effect.id}:pending`, source: 'Unstable Arcane Shard damage', automation: {},
      option: { id: 'pending', label: 'Unstable Arcane Shard damage', roll: '', cost: damage.cost } };
    expect(selectionBlocker(c, [pending])).toBeNull();
    runActionDetailed(c, `effect:${effect.id}`, {}, damage, effect.name);
    expect(c.effects.some(e => e.name === 'Unstable Arcane Shard: damage')).toBe(false);
    expect(selectionBlocker(c, [pending])).toBe('This effect is no longer active');
  });
});

describe('special consumables', () => {
  it('Doomsday Potion scars slots rolled below 12 and grants Invincible on the first 12', () => {
    const c = sheet(c => { c.hp.on = 3; c.stress.on = 2; c.hope.on = 1; });
    const potion = carry(c, consumables, 'Doomsday Potion');
    const slots = c.hope.slots;
    const log = runActionDetailed(c, 'item:Doomsday Potion', potion.automation!, action(potion, 'drink'), 'Doomsday Potion', fixed(3, 5, 7, 12))!;
    expect(c.scars).toBe(2);
    expect(c.hope.slots).toBe(slots - 2);
    expect(c.hp.on).toBe(0);
    expect(c.stress.on).toBe(0);
    expect(c.effects.some(e => e.name === 'Invincible')).toBe(true);
    expect(log.some(line => line.includes('(marked, 2d12 keep highest): 5'))).toBe(true);
  });

  it('Snakeskin Spirit heals a scar and Witherroot Ichor permanently loses an HP slot', () => {
    const c = sheet(c => { c.scars = 1; });
    const spirit = carry(c, consumables, 'Snakeskin Spirit');
    runActionDetailed(c, 'item:Snakeskin Spirit', spirit.automation!, action(spirit, 'drink'), 'Snakeskin Spirit');
    expect(c.scars).toBe(0);
    const ichor = carry(c, consumables, 'Witherroot Ichor');
    runActionDetailed(c, 'item:Witherroot Ichor', ichor.automation!, action(ichor, 'use'), 'Witherroot Ichor', fixed(2));
    expect(c.permanentHp).toBe(1);
    expect(c.hp.on).toBe(3);
  });

  it('Cockerel Claw Tea refreshes rest-limited uses but not session uses', () => {
    const c = sheet(c => { c.usage = { 'feat:A': { used: 1, per: 'longRest' }, 'feat:B': { used: 1, per: 'session' } }; });
    const tea = carry(c, consumables, 'Cockerel Claw Tea');
    runActionDetailed(c, 'item:Cockerel Claw Tea', tea.automation!, action(tea, 'drink'), 'Cockerel Claw Tea');
    expect(Object.keys(c.usage)).toEqual(['feat:B']);
  });

  it('Berserker\u2019s Brew trades at least 1 Finesse and Knowledge for Strength', () => {
    const c = sheet(c => { c.traits.find(t => t.name === 'Instinct')!.mod = 0; });
    const brew = carry(c, consumables, 'Berserker\u2019s Brew');
    const before = { str: traitTotal(c, 'Strength'), fin: traitTotal(c, 'Finesse') };
    runActionDetailed(c, 'item:Berserker\u2019s Brew', brew.automation!, action(brew, 'use'), 'Berserker\u2019s Brew');
    expect(traitTotal(c, 'Strength')).toBe(before.str + 1);
    expect(traitTotal(c, 'Finesse')).toBe(before.fin - 1);
  });

  it('Potion of Vigilance ends when HP is marked', () => {
    const c = sheet();
    const potion = carry(c, consumables, 'Potion of Vigilance');
    const evasion = derivedStats(c).evasion;
    runActionDetailed(c, 'item:Potion of Vigilance', potion.automation!, action(potion, 'use'), 'Potion of Vigilance');
    expect(derivedStats(c).evasion).toBe(evasion + 1);
    const r = computeDamage(c, availableMitigations(c), { damage: 1, type: 'physical', armor: false, selected: new Set() });
    applyDamage(c, r, 'physical');
    expect(c.effects.some(e => e.name === 'Potion of Vigilance')).toBe(false);
  });
});

describe('Lightning Scarab', () => {
  it('stores diverted damage and reports it when the brooch explodes', () => {
    const c = sheet();
    const scarab = carry(c, items, 'Lightning Scarab');
    const option = availableMitigations(c).find(m => m.option.storeDamage)!;
    const r = computeDamage(c, availableMitigations(c), { damage: 7, type: 'magic', armor: false, selected: new Set([option.id]) }, fixed(5));
    expect(r.hp).toBe(0);
    const report = applyDamage(c, r, 'magic', fixed(5))!;
    expect(c.resources!.scarab_damage).toBe(7);
    expect(report).toContain('stored 7 damage');
    const log = runActionDetailed(c, 'item:Lightning Scarab', scarab.automation!, action(scarab, 'explode'), 'Lightning Scarab')!;
    expect(log).toContain('The brooch explodes: take 7 damage (everything it absorbed).');
    expect(c.resources!.scarab_damage).toBe(0);
  });
});

describe('rest-time items', () => {
  it('Hopekeeper Locket is imbued only from 6 Hope and refunds a Hope at 0', () => {
    const low = sheet(c => { c.hope.on = 5; });
    carry(low, items, 'Hopekeeper Locket');
    const lowResult = resolveRest(low, 'long', [], { passives: { 'item:Hopekeeper Locket|imbue': true } });
    expect(low.resources!.hopekeeper_locket).toBe(0);
    expect(lowResult.lines.join('\n')).toContain('requires 6 Hope');
    const c = sheet(c => { c.hope.on = 6; });
    const locket = carry(c, items, 'Hopekeeper Locket');
    resolveRest(c, 'long', [], { passives: { 'item:Hopekeeper Locket|imbue': true } });
    expect(c.resources!.hopekeeper_locket).toBe(1);
    expect(c.hope.on).toBe(5);
    expect(actionBlocker(c, 'item:Hopekeeper Locket', locket.automation!, action(locket, 'use'))).toBe('Requires zero Hope');
    c.hope.on = 0;
    runActionDetailed(c, 'item:Hopekeeper Locket', locket.automation!, action(locket, 'use'), 'Hopekeeper Locket');
    expect(c.hope.on).toBe(1);
    expect(c.resources!.hopekeeper_locket).toBe(0);
  });

  it('Sweet Moss is a consumed rest benefit', () => {
    const c = sheet(c => { c.hp.on = 4; });
    carry(c, consumables, 'Sweet Moss', 1);
    resolveRest(c, 'short', [], { passives: { 'item:Sweet Moss|hp': true } }, fixed(3));
    expect(c.hp.on).toBe(1);
    expect(c.inventory.find(i => i.name === 'Sweet Moss')?.qty ?? 0).toBe(0);
  });
});

describe('attachments', () => {
  const plain = (name: string, range = 'Melee'): Weapon => ({ name, slot: 'Primary', damageType: 'phys', trait: 'Strength', range, damage: 'd8', hands: 1, tier: 1 });

  it('attaches Bloodstone to a featureless weapon and detaching restores it', () => {
    const c = sheet(c => { c.activeWeapons = [plain('Broadsword')]; c.carriedWeapons = [{ ...plain('Rapier'), feature: { name: 'Quick', text: '' } }]; });
    const stone = carry(c, items, 'Bloodstone');
    const def = stone.automation!.attach!;
    expect(attachTargets(c, def).map(g => g.name)).toEqual(['Broadsword']);
    attachItem(c, stone, c.activeWeapons[0]);
    expect(c.inventory).not.toContain(stone);
    expect(c.activeWeapons[0].feature?.name).toBe('Brutal');
    expect(c.activeWeapons[0].automation?.damage?.explode).toBe('once');
    detachItem(c, c.activeWeapons[0], 0);
    expect(c.activeWeapons[0].feature).toBeUndefined();
    expect(c.activeWeapons[0].automation).toBeUndefined();
    expect(c.inventory.find(i => i.name === 'Bloodstone')?.qty).toBe(1);
  });

  it('applies gem traits, keeps existing features and checks requirements', () => {
    const c = sheet(c => { c.activeWeapons = [{ ...plain('Longsword'), feature: { name: 'Reliable', text: '' }, automation: { modifiers: [{ roll: 'attack', value: 1 }] } }]; });
    const gem = carry(c, items, 'Gem of Precision');
    const charm = carry(c, items, 'Airblade Charm');
    attachItem(c, gem, c.activeWeapons[0]);
    attachItem(c, charm, c.activeWeapons[0]);
    const w = c.activeWeapons[0];
    expect(w.trait).toBe('Finesse');
    expect(w.feature?.name).toBe('Reliable');
    expect(w.automation?.modifiers).toHaveLength(1);
    expect(w.automation?.rollOptions?.[0].id).toBe('airblade');
    detachItem(c, w, 0);
    expect(w.trait).toBe('Strength');
    expect(w.automation?.rollOptions?.[0].id).toBe('airblade');
    detachItem(c, w, 0);
    expect(w.automation).toEqual({ modifiers: [{ roll: 'attack', value: 1 }] });
    expect(attachBlocker(plain('Shortbow', 'Far'), rules(items, 'Airblade Charm').attach!)).toBe('Requires a Melee weapon.');
    expect(attachBlocker(plain('Broadsword'), rules(items, 'Tarrasque Sinew').attach!)).toBe('Requires a bow.');
    expect(attachBlocker(plain('Shortbow', 'Far'), rules(items, 'Tarrasque Sinew').attach!)).toBeNull();
  });

  it('gives featureless armor the Valorstone Resilient mitigation', () => {
    const c = sheet(c => { c.activeArmor = { name: 'Leather Armor', score: 3, major: 6, severe: 13, tier: 1 }; });
    const stone = carry(c, items, 'Valorstone');
    expect(availableMitigations(c).some(m => m.option.id === 'resilient')).toBe(false);
    attachItem(c, stone, c.activeArmor!);
    expect(c.activeArmor!.feature?.name).toBe('Resilient');
    expect(availableMitigations(c).some(m => m.option.id === 'resilient')).toBe(true);
  });
});
