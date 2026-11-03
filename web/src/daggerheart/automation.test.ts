import { describe, expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import {
  Automation, actionBlocker, asksSpellcastRoll, availableRollOptions, availableRollTriggers, commitRollOptions, criticalBonus, damageContributions, damageExpression,
  derivedStats, evalValue, rollBonus, runAction, selectedRollBonus, selectionBlocker, setGmFear, sheetActions, syncDerived,
} from './engine';
import { updateCharacterAutomation } from './persist';
import type { CompendiumFile } from './compendium';

function warlock() {
  const c = emptyCharacter('Warlock');
  c.className = 'Warlock';
  c.hp.slots = 5;
  c.traits.find((t) => t.name === 'Presence')!.mod = 2;
  c.traits.find((t) => t.name === 'Presence')!.spellcast = true;
  c.features = [{
    source: 'Class feature - Warlock', name: 'Favor', text: '',
    automation: {
      resources: [{ id: 'favor', label: 'Favor', initial: 3 }],
      actions: [{ id: 'tribute', label: 'Tribute', timing: 'downtime', steps: [{ op: 'gain', resource: 'favor', amount: '@spellcast' }] }],
      rollOptions: [{ id: 'patron', label: 'Patron Die', roll: 'action', dice: 'd6', diceByLevel: { '5': 'd8' }, cost: { favor: 1 } }],
    },
  }];
  syncDerived(c);
  return c;
}

describe('configurable character automation', () => {
  it('hides rest-only actions without hiding other actions on the same source', () => {
    const tribute: import('./engine').ActionDef = { id: 'arbitrary-id', label: 'Custom move', timing: 'downtime' };
    const gain = { id: 'gain-favor', label: 'Successful Hope roll: gain Favor' };
    expect(sheetActions({ actions: [tribute, gain] })).toEqual([gain]);
    expect(sheetActions({ actions: [{ id: 'benefit', label: 'Custom benefit', timing: 'rest' }] })).toEqual([]);
    expect(sheetActions({ actions: [{ id: 'use', label: 'Downtime in the name alone does not restrict timing' }] })).toHaveLength(1);
    expect(sheetActions({ actions: [{ id: 'tribute', label: 'Tribute' }] })).toHaveLength(1);
  });

  it('rejects downtime execution outside a rest without spending resources', () => {
    const c = warlock();
    const a = c.features[0].automation!;
    const before = structuredClone(c);
    expect(actionBlocker(c, 'feat:Favor', a, a.actions![0])).toBe('Choose this move in the rest dialog');
    expect(runAction(c, 'feat:Favor', a, a.actions![0], 'Tribute')).toBeNull();
    expect(c).toEqual(before);
  });
  it('reads the GM Fear and domain card counts in values', () => {
    const c = emptyCharacter('Sorcerer');
    c.loadout = [{ name: 'A', domain: 'Sage' } as never];
    c.vault = [{ name: 'B', domain: 'Sage' } as never, { name: 'C', domain: 'Arcana' } as never];
    setGmFear(5);
    expect(evalValue('@fear', c)).toBe(5);
    expect(evalValue('@cards_sage', c)).toBe(2);
    setGmFear(0);
  });
  it('scopes uses per action and builds fixed spell damage', () => {
    const c = emptyCharacter('Wizard');
    const auto: Automation = { actions: [{ id: 'a', label: 'A', uses: { max: 1, per: 'rest' } }, { id: 'b', label: 'B' }] };
    expect(runAction(c, 'card:X', auto, auto.actions![0], 'X')).not.toBeNull();
    expect(actionBlocker(c, 'card:X', auto, auto.actions![0])).not.toBeNull();
    expect(actionBlocker(c, 'card:X', auto, auto.actions![1])).toBeNull();
    const none = { value: 0, dice: [], applied: [], names: [], contributions: [] };
    expect(damageExpression('1d20+3', 3, none, undefined, true)).toBe('1d20+3');
    expect(damageExpression('d6+1', 3, none, { extraDice: 1, dropLowest: 0 })).toBe('4d6+1');
  });
  it('only asks for a Spellcast button when the text requests the roll', () => {
    expect(asksSpellcastRoll('Make a **Spellcast Roll** against a target')).toBe(true);
    expect(asksSpellcastRoll('You gain a bonus to Spellcast Rolls.')).toBe(false);
  });
  it('computes critical damage as the maximum of the kept damage dice', () => {
    expect(criticalBonus('2d8+3')).toBe(16);
    expect(criticalBonus('3d8kh2+1d6+2')).toBe(22);
    expect(criticalBonus('2d8-1d4')).toBe(16);
  });
  it('offers effect-gated failure triggers on any roll', () => {
    const c = emptyCharacter('Ranger');
    c.features = [{
      source: 'Class feature', name: 'Ranger’s Focus', text: '',
      automation: { rollTriggers: [{ id: 'r', label: 'Reroll', on: 'attack', when: 'failure', reroll: 'duality', requiresEffect: 'Focus', endEffect: 'Focus' }] },
    }];
    const ctx = { source: 'attack,action', hope: 9, fear: 2, total: 12 };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    c.effects.push({ id: 'e1', name: 'Focus', kind: 'buff', until: 'scene', modifiers: [] });
    expect(availableRollTriggers(c, ctx)).toHaveLength(1);
  });
  it('offers roll triggers from features by roll source and dice', () => {
    const c = emptyCharacter('Wizard');
    c.features = [{
      source: 'Class feature', name: 'Strange Patterns', text: '',
      automation: {
        resources: [{ id: 'strange-pattern', label: 'Number', initial: 0, max: 12 }],
        rollTriggers: [{ id: 'hope', label: 'Hope', on: 'duality', when: 'dieMatches', matchResource: 'strange-pattern' }],
      },
    }, {
      source: 'Ancestry', name: 'Hawkeye', text: '',
      automation: { rollTriggers: [{ id: 'r', label: 'Reroll', on: 'attack', cost: { hope: 2 }, reroll: 'hope' }] },
    }, {
      source: 'Ancestry', name: 'Fearless', text: '',
      automation: { rollTriggers: [{ id: 'f', label: 'Fearless', on: 'duality', when: 'fear' }] },
    }];
    syncDerived(c);
    c.resources!['strange-pattern'] = 7;
    const names = (source: string, hope: number, fear: number) =>
      availableRollTriggers(c, { source, hope, fear, total: hope + fear }).map((t) => t.key);
    expect(names('attack,trait:Agility,action', 3, 4)).toEqual(['feat:Hawkeye', 'feat:Fearless']);
    expect(names('trait:Agility,action', 7, 2)).toEqual(['feat:Strange Patterns']);
    c.resources!['strange-pattern'] = 0;
    expect(names('trait:Agility,action', 7, 2)).toEqual([]);
  });

  it('initializes custom pools once, survives old sheets, and evaluates gain actions', () => {
    const c = warlock();
    expect(c.resources).toEqual({ favor: 3 });
    const a = c.features[0].automation!;
    expect(runAction(c, 'feat:Favor', a, a.actions![0], 'Tribute', undefined, 'rest')).toContain('gained 2 Favor');
    expect(c.resources!.favor).toBe(5);
    c.resources!.favor = 0;
    syncDerived(c);
    expect(c.resources!.favor).toBe(0);
    expect(normalizeSheet(c, c.name).resources!.favor).toBe(0);
  });

  it('lists inventory and drunk potions in the roll options and consumes them on roll', () => {
    const c = warlock();
    c.inventory = [{ name: 'Stride Potion', type: 'consumable', qty: 2, automation: {
      actions: [{ id: 'use', label: 'Drink', steps: [{ op: 'effect', effect: { name: 'Stride Potion', kind: 'buff', until: 'manual', modifiers: [{ roll: 'trait:Agility', value: 1, consume: true }] } }] }],
      rollOptions: [{ id: 'potion', label: 'Drink', roll: 'trait:Agility', value: 1, consumeItem: true }],
    } }] as never;
    expect(availableRollOptions(c, ['trait:Strength'])).toHaveLength(0);
    // not drunk yet: unticked, ticking uses up one potion
    let options = availableRollOptions(c, ['trait:Agility']);
    expect(options).toHaveLength(1);
    expect(options[0].active).toBeFalsy();
    expect(selectedRollBonus(c, rollBonus(c, ['trait:Agility'], undefined, false), options).value).toBe(1);
    commitRollOptions(c, options);
    expect(c.inventory[0].qty).toBe(1);
    // drunk beforehand: listed as prepared and free, removed once the roll was sent
    const a = c.inventory[0].automation!;
    runAction(c, 'item:Stride Potion', a, a.actions![0], 'Stride Potion');
    expect(rollBonus(c, ['trait:Agility'], undefined, false).value).toBe(0);
    options = availableRollOptions(c, ['trait:Agility']);
    expect(options.map((o) => !!o.prepared)).toEqual([true, false]);
    commitRollOptions(c, [options[0]]);
    expect(c.effects).toHaveLength(0);
    expect(c.inventory[0].qty).toBe(1);
  });

  it('keeps a rest-long item option free after the last item was used', () => {
    const c = warlock();
    c.level = 1;
    c.inventory = [{ name: 'Toxilily Bulb', type: 'consumable', qty: 1, automation: {
      rollOptions: [{ id: 'cloves', label: 'clove', roll: 'damage', value: '@proficiency', consumeItem: true, effect: { name: 'Toxilily Bulb', kind: 'buff', until: 'rest' } }],
    } }] as never;
    commitRollOptions(c, availableRollOptions(c, ['damage']));
    expect(c.inventory[0].qty).toBe(0);
    const options = availableRollOptions(c, ['damage']);
    expect(options).toHaveLength(1);
    expect(options[0].active).toBe(true);
    expect(selectionBlocker(c, options)).toBeNull();
    commitRollOptions(c, options);
    expect(c.inventory[0].qty).toBe(0);
  });

  it('keeps an effect-based option free and pre-selected while its effect is active', () => {
    const c = warlock();
    c.level = 2;
    c.features[0].automation!.rollOptions = [{
      id: 'fury', label: 'Fury', roll: 'damage', dice: '1d6', diceByLevel: { '2': '2d6' }, cost: { favor: 1 },
      effect: { name: 'Fury', kind: 'buff', until: 'scene' },
    }];
    let options = availableRollOptions(c, ['damage']);
    expect(options[0].active).toBe(false);
    commitRollOptions(c, options);
    expect(c.resources!.favor).toBe(2);
    expect(c.effects.map((e) => e.name)).toEqual(['Fury']);
    options = availableRollOptions(c, ['damage']);
    expect(options[0].active).toBe(true);
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), options).dice).toEqual(['2d6']);
    c.resources!.favor = 0;
    expect(selectionBlocker(c, options)).toBeNull();
    commitRollOptions(c, options);
    expect(c.resources!.favor).toBe(0);
    expect(c.effects).toHaveLength(1);
  });

  it('caps custom pools and never routes an unknown resource into armor', () => {
    const c = warlock();
    const a = c.features[0].automation!;
    a.resources![0].max = 4;
    runAction(c, 'favor', a, a.actions![0], 'Tribute', undefined, 'rest');
    expect(c.resources!.favor).toBe(4);
    expect(actionBlocker(c, 'unknown', {}, { id: 'use', label: 'Use', cost: { missing: 1 } })).toContain('Resource unavailable');
    expect(runAction(c, 'unknown', {}, { id: 'use', label: 'Use', cost: { missing: 1 } }, 'Missing')).toBeNull();
    expect(c.armorTrack.on).toBe(0);
    c.traits.find((t) => t.spellcast)!.mod = -1;
    c.hp.on = 2;
    const healing = { id: 'heal', label: 'Heal', steps: [{ op: 'clear' as const, resource: 'hp', amount: '@spellcast' }] };
    runAction(c, 'healing', {}, healing, 'Heal');
    expect(c.hp.on).toBe(2);
  });

  it('combines multiple optional bonuses and charges their aggregate costs including Experience', () => {
    const c = warlock();
    c.inventory.push({
      name: 'Charm', qty: 1, type: 'gear', automation: {
        rollOptions: [
          { id: 'a', label: 'First', roll: 'action', value: '@tier', cost: { hope: 1, stress: 1 } },
          { id: 'b', label: 'Second', roll: 'action', value: 2, cost: { hope: 1, favor: 2 } },
        ],
      },
    });
    const options = availableRollOptions(c, ['action']);
    expect(selectionBlocker(c, options, true)).toBe('Not enough Hope');
    expect(c.resources!.favor).toBe(3);
    expect(c.hope.on).toBe(2);
    c.hope.on = 3;
    const bonus = selectedRollBonus(c, rollBonus(c, ['action']), options);
    expect(bonus.value).toBe(3);
    expect(bonus.dice).toEqual(['d6']);
    expect(bonus.contributions.map((b) => b.label)).toEqual(['Favor - Patron Die', 'Charm - First', 'Charm - Second']);
    expect(selectionBlocker(c, options, true)).toBeNull();
    commitRollOptions(c, options, true);
    expect(c.resources!.favor).toBe(0);
    expect(c.hope.on).toBe(0);
    expect(c.stress.on).toBe(1);
    expect(c.inventory[0].qty).toBe(1);
    c.level = 5;
    expect(selectedRollBonus(c, rollBonus(c, []), options.slice(0, 1)).dice).toEqual(['d8']);
  });

  it('checks shared uses, inventory stock and consumable quantities for the whole selection', () => {
    const c = warlock();
    const a: Automation = {
      uses: { max: 1, per: 'longRest' },
      rollOptions: [{ id: 'one', label: 'One', roll: 'damage', value: 1 }, { id: 'two', label: 'Two', roll: 'damage', value: 2 }],
    };
    c.inventory.push({ name: 'Limited', qty: 1, type: 'gear', automation: a });
    const options = availableRollOptions(c, ['damage']);
    expect(selectionBlocker(c, options)).toContain('not enough uses');
    expect(selectionBlocker(c, options.slice(0, 1))).toBeNull();
    c.inventory[0].qty = 0;
    expect(selectionBlocker(c, options.slice(0, 1))).toContain('none left');
    expect(availableRollOptions(c, ['damage'])).toEqual([]);
    c.inventory[0].qty = 1;
    delete a.uses;
    for (const o of a.rollOptions!) o.consumeItem = true;
    expect(selectionBlocker(c, availableRollOptions(c, ['damage']))).toContain('not enough inventory');
  });

  it('only uses owned inventory and equipped weapons, and Powerful changes only the base weapon pool', () => {
    const c = warlock();
    c.inventory.push({ name: 'Quiver', type: 'gear', qty: 1, automation: { rollOptions: [{ id: 'arrow', label: 'Arrow', roll: 'damage', value: '@tier' }] } });
    const weapon = {
      name: 'Greatstaff', slot: 'Primary' as const, trait: 'Knowledge' as const, range: 'Very Far', damage: '1d6+6',
      damageType: 'magic' as const, hands: 2 as const, tier: 3, feature: { name: 'Powerful', text: '' },
      automation: { damage: { extraDice: 1, dropLowest: 1 } },
    };
    c.activeWeapons.push(weapon);
    c.carriedWeapons.push({ ...weapon, name: 'Carried', automation: { rollOptions: [{ id: 'bad', label: 'Not active', roll: 'damage', value: 99 }] } });
    const bonus = selectedRollBonus(c, rollBonus(c, ['damage']), availableRollOptions(c, ['damage'], weapon));
    bonus.dice.push('d8');
    bonus.contributions.push({ label: 'Other damage', expression: '1d8' });
    expect(damageExpression(weapon.damage, derivedStats(c).proficiency, bonus, weapon.automation.damage)).toBe('2d6kh1+6+1+1d8');
    expect(damageContributions(c, weapon, bonus).map((b) => b.expression)).toEqual(['2d6kh1', '+6', '1', '1d8']);
  });

  it('offers offhand primary bonuses only for primary damage and scopes local weapon rolls', () => {
    const c = warlock();
    const base = { damageType: 'phys' as const, trait: 'Agility' as const, range: 'Melee', damage: '1d6', hands: 1 as const, tier: 1 };
    const primary = { ...base, name: 'Main', slot: 'Primary' as const };
    const offhand = {
      ...base, name: 'Offhand', slot: 'Secondary' as const,
      automation: {
        modifiers: [{ roll: 'attack', value: 1 }],
        rollOptions: [
          { id: 'paired', label: 'Paired', roll: 'damage', value: 2, weapon: 'primary' as const },
          { id: 'sharp', label: 'Sharp', roll: 'damage', dice: '1d4' },
        ],
      },
    };
    c.activeWeapons.push(primary, offhand);
    expect(availableRollOptions(c, ['damage'], primary).map((o) => o.option.id)).toEqual(['paired']);
    expect(availableRollOptions(c, ['damage'], offhand).map((o) => o.option.id)).toEqual(['sharp']);
    expect(rollBonus(c, ['attack'], primary).value).toBe(0);
    expect(rollBonus(c, ['attack'], offhand).value).toBe(1);
    c.activeWeapons.pop();
    expect(availableRollOptions(c, ['damage'], primary)).toEqual([]);
  });

  it('refreshes feature rules but leaves equipment and items untouched', () => {
    const c = warlock();
    c.features[0].automation = undefined;
    c.resources!.favor = 1;
    c.hp.slots = 9;
    c.inventory.push({ name: 'Charging Quiver', type: 'gear', qty: 1 });
    const files = [
      { category: 'classes', entries: [{ name: 'Warlock', kind: 'class', features: [{ name: 'Favor', text: '', automation: { resources: [{ id: 'favor', label: 'Favor', initial: 3 }] } }] }] },
      { category: 'items', entries: [{ name: 'Charging Quiver', automation: { rollOptions: [{ id: 'arrow', label: 'Arrow', roll: 'damage', value: '@tier' }] } }] },
    ] as CompendiumFile[];
    updateCharacterAutomation(c, files);
    expect(c.resources!.favor).toBe(1);
    expect(c.hp.slots).toBe(9);
    expect((c.features[0] as { automation?: Automation }).automation?.resources).toHaveLength(1);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
  });

  it('refreshes learned subclass features from the correct stage', () => {
    const c = warlock();
    c.subclass = 'Test';
    c.features.push({ source: 'Subclass mastery - Test', name: 'Improvement', text: '' });
    updateCharacterAutomation(c, [{
      category: 'classes', entries: [
        { name: 'Warlock: Test', parent: 'Warlock', kind: 'subclass', stage: 'foundation', features: [{ name: 'Improvement', text: '', automation: { modifiers: [{ stat: 'evasion', value: 1 }] } }] },
        { name: 'Warlock: Test', parent: 'Warlock', kind: 'subclass', stage: 'mastery', features: [{ name: 'Improvement', text: '', automation: { modifiers: [{ stat: 'evasion', value: 3 }] } }] },
      ],
    }] as CompendiumFile[]);
    expect(c.features[1].automation?.modifiers).toEqual([{ stat: 'evasion', value: 3 }]);
  });
});
