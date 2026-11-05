import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import {
  applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, configuredRollOption,
  criticalBonus, damageContributions, damageExpression, damageRollFormula, rollBonus, rollOptionDice,
  runAction, selectedRollBonus, selectionBlocker, syncDerived,
} from './engine';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function assassin(guild = 'executioners', stage = 'mastery') {
  const c = emptyCharacter('Assassin');
  c.className = 'Assassin';
  c.level = 8;
  const stages = ['foundation', 'specialization', 'mastery'];
  c.features = catalog.entries.filter((e) => e.id === 'assassin'
    || (e.id?.startsWith(`assassin-${guild}-guild-`) && stages.indexOf(e.stage ?? '') <= stages.indexOf(stage)))
    .flatMap((e) => (e.features ?? []).map((f) => ({ ...f, text: f.text ?? '', source: e.name })));
  c.activeWeapons = [{ name: 'Dagger', slot: 'Primary', damageType: 'phys', trait: 'Finesse', range: 'Melee', damage: 'd8+2', hands: 1, tier: 1 }];
  c.proficiency.on = 2;
  c.hope.on = 4;
  syncDerived(c);
  return c;
}

describe('Assassin catalog automation', () => {
  it('starts a single rest-duration mark from either action and offers optional dice on any damage', () => {
    const c = assassin();
    const mark = c.features.find((f) => f.name === 'Marked For Death')!;
    expect(availableRollOptions(c, ['damage']).some((o) => o.option.id === 'marked-for-death')).toBe(false);
    const attack = { source: 'attack,action', hope: 9, fear: 2, total: 11, weapon: 'Dagger' };
    const trigger = availableRollTriggers(c, attack).find((t) => t.source === mark.name)!;
    expect(trigger).toBeDefined();
    expect(availableRollTriggers(c, { ...attack, weapon: undefined }).some((t) => t.source === mark.name)).toBe(false);
    expect(runAction(c, trigger.key, trigger.automation, trigger.trigger, trigger.source)).toBeTruthy();
    expect(c.stress.on).toBe(1);
    expect(c.effects).toHaveLength(1);
    expect(runAction(c, 'feat:Marked For Death', mark.automation!, mark.automation!.actions![0], mark.name)).toBeTruthy();
    expect(c.effects).toHaveLength(1);
    const option = availableRollOptions(c, ['damage', 'spelldamage']).find((o) => o.option.id === 'marked-for-death')!;
    expect(option.active).toBe(false);
    expect(rollOptionDice(c, option.option)).toBe('4d8');
    applyRest(c, 'short');
    expect(c.effects).toHaveLength(0);
    expect(availableRollOptions(c, ['damage']).some((o) => o.option.id === 'marked-for-death')).toBe(false);
  });

  it.each([
    ['poisoners', 'foundation', 1, '1d4'],
    ['poisoners', 'mastery', 5, '3d4'],
    ['executioners', 'foundation', 3, '2d6'],
    ['executioners', 'specialization', 7, '3d6'],
    ['executioners', 'mastery', 8, '4d8'],
  ])('scales marked dice for %s %s at level %i', (guild, stage, level, dice) => {
    const c = assassin(guild, stage);
    c.level = level;
    c.effects = [{ id: 'mark', name: 'Marked for Death', kind: 'buff', until: 'rest' }];
    const option = availableRollOptions(c, ['damage']).find((o) => o.option.id === 'marked-for-death')!;
    expect(rollOptionDice(c, option.option)).toBe(dice);
  });

  it('doubles the final formula and every contribution without rolling extra dice', () => {
    const c = assassin();
    c.effects = [{ id: 'mark', name: 'Marked for Death', kind: 'buff', until: 'rest' }];
    const selected = availableRollOptions(c, ['damage']);
    const bonus = selectedRollBonus(c, rollBonus(c, ['damage']), selected);
    const weapon = c.activeWeapons[0];
    const base = damageExpression(weapon.damage, 2, bonus);
    expect(base).toBe('2d8+2+4d8');
    const critical = criticalBonus(base);
    expect(critical).toBe(48);
    const parts = [...damageContributions(c, weapon, bonus), { label: 'Critical', expression: String(critical) }];
    const result = damageRollFormula(`${base}+${critical}`, parts, selected);
    expect(result.expression).toBe('(2d8+2+4d8+48)*2');
    expect(result.contributions.map((p) => p.expression)).toEqual(parts.map((p) => `(${p.expression})*2`));
    expect(result.contributions[0].notes).toContainEqual({ label: expect.stringContaining('First Strike'), impact: 'Double the rolled damage total' });
    expect(damageRollFormula(base, parts, [])).toEqual({ expression: base, contributions: parts });
  });

  it('offers Death Strike only on damage and shares True Strike usage between sheet and chat', () => {
    const c = assassin();
    const attack = { source: 'attack,action', hope: 3, fear: 9, total: 12, weapon: 'Dagger' };
    const damage = { ...attack, source: 'damage', kind: 'damage' as const, hope: 0, fear: 0 };
    expect(availableRollTriggers(c, damage).map((t) => t.source)).toEqual(['Death Strike']);
    expect(availableRollTriggers(c, attack).map((t) => t.source)).toEqual(expect.arrayContaining(['True Strike', 'Marked For Death']));
    const strike = c.features.find((f) => f.name === 'True Strike')!;
    runAction(c, 'feat:True Strike', strike.automation!, strike.automation!.actions![0], strike.name);
    const trigger = availableRollTriggers(c, attack).find((t) => t.source === strike.name)!;
    expect(runAction(c, trigger.key, trigger.automation, trigger.trigger, trigger.source)).toBeNull();
    applyRest(c, 'short');
    expect(runAction(c, trigger.key, trigger.automation, trigger.trigger, trigger.source)).toBeNull();
    applyRest(c, 'long');
    expect(runAction(c, trigger.key, trigger.automation, trigger.trigger, trigger.source)).toBeTruthy();
  });

  it('clears poison only on long rests and charges one token for poison damage options', () => {
    const c = assassin('poisoners');
    c.resources!.poison = 3;
    applyRest(c, 'short');
    expect(c.resources!.poison).toBe(3);
    const options = availableRollOptions(c, ['damage']);
    const leaf = configuredRollOption(c, options.find((o) => o.option.id === 'fear-leaf')!, 9);
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [leaf]).value).toBe(9);
    expect(() => configuredRollOption(c, leaf, 0)).toThrow();
    expect(() => configuredRollOption(c, leaf, 21)).toThrow();
    commitRollOptions(c, [leaf]);
    expect(c.resources!.poison).toBe(2);
    const attack = { source: 'attack,action', hope: 9, fear: 3, total: 12, weapon: 'Dagger' };
    const poison = availableRollTriggers(c, attack).find((t) => t.trigger.id === 'poison')!;
    expect(poison.trigger.label).toBe('Afflict with poison');
    runAction(c, poison.key, poison.automation, poison.trigger, poison.source);
    expect(c.resources!.poison).toBe(1);
    applyRest(c, 'long');
    expect(c.resources!.poison).toBe(0);
    expect(selectionBlocker(c, [leaf])).toBeTruthy();
  });
});
