import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, handsUsed, ignoresWeaponBurden, type Weapon } from './data';
import {
  availableRollOptions, availableRollTriggers, commitRollOptions, configuredRollOption, rollBonus,
  rollOptionDice, selectedRollBonus, sheetActions, syncDerived, usesLeft,
} from './engine';
import { availableMoves, resolveRest, restSlots } from './rest';
import { creationIgnoresWeaponBurden, emptyChoices, type RulesData } from './rules';
import { rollContext } from './rollbuttons';
import { setDisabledCopycatSources } from './copycat';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
const primary: Weapon = { name: 'Greatsword', slot: 'Primary', damage: 'd10+3', damageType: 'phys', trait: 'Strength', range: 'Melee', hands: 2, tier: 1 };
const secondary: Weapon = { ...primary, name: 'Dagger', slot: 'Secondary', damage: 'd8+2', hands: 1 };

function warrior(subclass = 'slayer') {
  const c = emptyCharacter('Warrior');
  c.className = 'Warrior';
  c.level = 5;
  c.proficiency.on = 3;
  c.hope.on = 4;
  c.activeWeapons = [structuredClone(primary), structuredClone(secondary)];
  c.features = catalog.entries.filter(e => e.id === 'warrior' || e.id.startsWith(`warrior-call-of-the-${subclass}-`))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: `Warrior - ${e.stage ?? 'class'}` })));
  syncDerived(c);
  return c;
}

describe('Warrior automation', () => {
  it('suspends granted downtime actions while the source is disabled and restores them on enabling', () => {
    const source = warrior().features.find(f => f.automation?.partyDowntime)!;
    const ally = emptyCharacter('Ally');
    ally.effects.push({ id: 'grant', name: source.name, kind: 'buff', until: 'manual', modifiers: [],
      partyDowntimeGrant: true, sourceActorId: 'warrior', actions: structuredClone(source.automation!.actions) });
    try {
      expect(availableMoves(ally, 'long').some(move => move.id === 'act:effect:grant|train')).toBe(true);
      setDisabledCopycatSources(['warrior']);
      expect(availableMoves(ally, 'long').some(move => move.id === 'act:effect:grant|train')).toBe(false);
      expect(ally.effects).toHaveLength(1);
      setDisabledCopycatSources([]);
      expect(availableMoves(ally, 'long').some(move => move.id === 'act:effect:grant|train')).toBe(true);
    } finally { setDisabledCopycatSources([]); }
  });
  it('ignores burden in equipment and creation, but not for other classes', () => {
    const c = warrior();
    expect(ignoresWeaponBurden(c)).toBe(true);
    expect(handsUsed(c)).toBe(0);
    c.features = [];
    expect(handsUsed(c)).toBe(3);
    const data = { classes: catalog.entries } as RulesData;
    const choices = { ...emptyChoices(), className: 'Warrior' };
    expect(creationIgnoresWeaponBurden(data, choices)).toBe(true);
    expect(creationIgnoresWeaponBurden(data, { ...choices, className: 'Rogue' })).toBe(false);
  });

  it('offers Courage only on failed Fear action rolls and preselects eligible Rise to the Challenge', () => {
    const c = warrior('brave');
    expect(sheetActions(c.features.find(f => f.name === 'Courage')!.automation)).toEqual([]);
    const fear = { source: 'action', hope: 2, fear: 9, total: 11 };
    expect(availableRollTriggers(c, fear).some(t => t.source === 'Courage')).toBe(true);
    for (const ctx of [{ ...fear, source: 'reaction,action' }, { ...fear, hope: 10 }, { ...fear, difficulty: 10 }])
      expect(availableRollTriggers(c, ctx).some(t => t.source === 'Courage')).toBe(false);
    c.hp.on = c.hp.slots - 2;
    expect(availableRollOptions(c, ['action']).find(s => s.source === 'Rise to the Challenge')?.option).toMatchObject({ label: 'Rise to the Challenge (Hope d20)', hopeDie: 20, defaultOn: true });
    expect(availableRollOptions(c, ['reaction']).some(s => s.source === 'Rise to the Challenge')).toBe(true);
    c.hp.on--;
    expect(availableRollOptions(c, ['action']).some(s => s.source === 'Rise to the Challenge')).toBe(false);
  });

  it('stores dice only in the class pool and scales both dice and cost by the chosen quantity', () => {
    for (const target of ['attack', 'damage']) {
      const c = warrior();
      c.resources!['slayer-dice'] = 3;
      const auto = c.features.find(f => f.name === 'Slayer')!.automation!;
      expect(auto.classResourceOnly).toBe(true);
      expect(sheetActions(auto)).toEqual([]);
      expect(auto.resources![0]).toMatchObject({ session: true, sessionHope: true });
      const option = availableRollOptions(c, [target]).find(s => s.source === 'Slayer')!;
      const selected = configuredRollOption(c, option, 2);
      const bonus = selectedRollBonus(c, rollBonus(c, [target]), [selected]);
      expect(bonus.dice).toContain('2d6');
      expect(bonus.contributions.at(-1)?.notes).toEqual([{ label: 'Dice resource', impact: 'slayer-dice' }]);
      commitRollOptions(c, [selected]);
      expect(c.resources!['slayer-dice']).toBe(1);
      expect(() => configuredRollOption(c, option, 2)).toThrow();
    }
  });

  it('adds exactly one secondary die without consuming the reroll limit', () => {
    const c = warrior();
    const option = availableRollOptions(c, ['damage'], c.activeWeapons[0]).find(s => s.source === 'Weapon Specialist')!;
    expect(rollOptionDice(c, option.option)).toBe('1d8');
    commitRollOptions(c, [option]);
    expect(c.hope.on).toBe(3);
    expect(usesLeft(c, 'feat:Weapon Specialist', c.features.find(f => f.name === 'Weapon Specialist')!.automation!)).toBe(1);
    c.activeWeapons.pop();
    expect(availableRollOptions(c, ['damage']).some(s => s.source === 'Weapon Specialist')).toBe(false);
  });

  it('offers the Slayer reroll only for Slayer ones, on attack or damage', () => {
    const c = warrior();
    for (const source of ['attack,action', 'damage']) {
      const ctx = rollContext({ id: 1, ts: 1, fromId: 'p', from: 'P', actorId: 'a',
        kind: source === 'damage' ? undefined : 'duality', expression: source === 'damage' ? '1d10+2d6' : '2d12+2d6',
        source, total: 20, rolls: [6, 8, 1, 5], breakdown: [
          { label: 'Base', expression: '2d12', total: 14, rolls: [6, 8] },
          { label: 'Slayer', expression: '2d6', total: 6, rolls: [1, 5], notes: [{ label: 'Dice resource', impact: 'slayer-dice' }] },
        ] })!;
      expect(availableRollTriggers(c, ctx).some(t => t.trigger.rerollResourceDice === 'slayer-dice')).toBe(true);
      expect(availableRollTriggers(c, { ...ctx, resourceOnes: [] }).some(t => t.trigger.rerollResourceDice)).toBe(false);
    }
  });

  it('grants normal downtime slots and gives non-Slayers one consumable buff', () => {
    const trainer = warrior();
    const feature = trainer.features.find(f => f.name === 'Martial Preparation')!;
    expect(feature.automation?.resources).toBeUndefined();
    for (const kind of ['short', 'long'] as const) {
      const ally = emptyCharacter('Ally');
      ally.effects.push({ id: 'grant', name: feature.name, kind: 'buff', until: 'manual', modifiers: [],
        partyDowntimeGrant: true, actions: structuredClone(feature.automation!.actions) });
      expect(restSlots(ally, kind)).toHaveLength(2);
      const move = availableMoves(ally, kind).find(m => m.id === 'act:effect:grant|train')!;
      resolveRest(ally, kind, [{ move: move.id }]);
      expect(ally.resources?.['slayer-dice']).toBeUndefined();
      expect(ally.effects.some(e => e.name === 'Martial Preparation: Slayer Die')).toBe(true);
      const option = availableRollOptions(ally, ['attack']).find(s => s.option.diceResource === 'slayer-dice')!;
      commitRollOptions(ally, [option]);
      expect(availableRollOptions(ally, ['damage']).some(s => s.option.diceResource === 'slayer-dice')).toBe(false);
    }
    const move = availableMoves(trainer, 'short').find(m => m.id === 'act:feat:Martial Preparation|train')!;
    resolveRest(trainer, 'short', [{ move: move.id }]);
    expect(trainer.resources!['slayer-dice']).toBe(1);
    expect(trainer.effects.some(e => e.name === 'Martial Preparation: Slayer Die')).toBe(false);
  });
});
