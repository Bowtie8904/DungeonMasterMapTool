import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { cardFrom } from './compendium';
import { emptyCharacter } from './data';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, rollOptionDice, runAction, setGmFear,
  sheetActions, syncEffectDependencies, syncResources, usesLeft,
} from './engine';
import { availableMitigations, computeDamage } from './damage';
import { rollContext } from './rollbuttons';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
const entry = (id: string) => catalog.entries.find(card => card.id === id)!;
const rules = (id: string) => entry(id).automation!;
function character(...ids: string[]) {
  const c = emptyCharacter('Caster');
  c.hope.on = 6;
  c.loadout = ids.map(id => cardFrom(entry(id), c));
  syncResources(c);
  return c;
}
const cast = (name: string, hope = 9, fear = 2, total = 20) =>
  ({ source: `action,spellcast,card:${name}`, hope, fear, total });
const damage = { source: 'damage,magicdamage', hope: 0, fear: 0, total: 15, kind: 'damage' as const };

afterEach(() => setGmFear(0));

describe('Dread online automation', () => {
  it('tracks Veil activation separately from token spending and restores it on rest', () => {
    setGmFear(4);
    const c = character('umbral-veil'), a = rules('umbral-veil'), key = 'card:Umbral Veil';
    expect(usesLeft(c, key, a)).toBe(1);
    expect(runAction(c, key, a, a.actions![0], 'Umbral Veil')).toBeTruthy();
    expect(usesLeft(c, key, a)).toBe(0);
    expect(actionBlocker(c, key, a, a.actions![0])).toContain('No uses left');
    expect(runAction(c, key, a, a.actions![1], 'Umbral Veil')).toBeTruthy();
    expect(c.resources!.veil).toBe(3);
    expect(usesLeft(c, key, a)).toBe(0);
    applyRest(c, 'short');
    expect(usesLeft(c, key, a)).toBe(1);
  });

  it('tracks Dark Army summons until long rest without blocking fiend damage or mitigation', () => {
    const c = character('dark-army'), a = rules('dark-army'), key = 'card:Dark Army';
    expect(usesLeft(c, key, a)).toBe(1);
    runAction(c, key, a, a.conditionalRollTriggers![0], 'Dark Army');
    expect(usesLeft(c, key, a)).toBe(0);
    const option = availableRollOptions(c, ['damage'])[0];
    expect(actionBlocker(c, option.key, option.automation, option.option)).toBeNull();
    expect(availableMitigations(c)).toHaveLength(1);
    applyRest(c, 'short');
    expect(usesLeft(c, key, a)).toBe(0);
    applyRest(c, 'long');
    expect(usesLeft(c, key, a)).toBe(1);
  });

  it.each(['terrify', 'wall-of-hunger', 'dark-army', 'damnation', 'dire-strike', 'jump-scare'])(
    'does not leave follow-up buttons on %s', id => expect(sheetActions(rules(id))).toEqual([]));

  it('uses Hope damage for critical Blighting Strike, and suppresses known failed damage', () => {
    const c = character('blighting-strike');
    expect(availableRollTriggers(c, cast('Blighting Strike', 5, 5)).map(t => t.trigger.id)).toEqual(['hope-damage']);
    expect(availableRollTriggers(c, cast('Blighting Strike', 2, 8)).map(t => t.trigger.id)).toContain('fear-damage');
    expect(availableRollTriggers(c, { ...cast('Blighting Strike', 9, 2, 11), difficulty: 15 }).map(t => t.trigger.id))
      .toEqual(['fail-hope', 'fail-stress']);
  });

  it('places Terrify, Wall of Hunger, Dark Army and Damnation follow-ups on cast results', () => {
    const c = character('terrify', 'wall-of-hunger', 'dark-army', 'damnation');
    expect(availableRollTriggers(c, cast('Terrify')).map(t => t.trigger.id)).toEqual(['stress']);
    expect(availableRollTriggers(c, cast('Damnation'))[0].trigger.resourceRoll).toMatchObject({ resource: 'stress', sides: 20 });
    for (const [name, id, difficulty] of [['Wall of Hunger', 'use', 10], ['Dark Army', 'summon', 14]] as const) {
      expect(availableRollTriggers(c, cast(name, 9, 2, difficulty)).map(t => t.trigger.id)).toEqual([id]);
      expect(availableRollTriggers(c, cast(name, 9, 2, difficulty - 1))).toEqual([]);
    }
  });

  it('offers Spellcast-trait reactions, with paid damage only on their own result', () => {
    const c = character('hideous-retribution');
    expect(sheetActions(rules('hideous-retribution'))).toMatchObject([{ id: 'reaction', actionRoll: { trait: 'spellcast', reaction: true } }]);
    expect(availableRollTriggers(c, { ...damage, source: 'incomingDamage' })[0].trigger.audience).toBe('ally');
    expect(availableRollTriggers(c, { ...cast('Hideous Retribution'),
      source: 'reaction,spellcast,card:Hideous Retribution,featureAction:card:Hideous Retribution:reaction' })[0].trigger)
      .toMatchObject({ id: 'damage', cost: { stress: 1 }, damage: { dice: 'd6', type: 'magic' } });
    expect(availableRollTriggers(c, cast('Hideous Retribution'))).toEqual([]);
  });

  it('offers Siphon healing only on its own damage, even after its long-rest damage use', () => {
    const c = character('siphon-essence');
    c.usage['card:Siphon Essence'] = { used: 1, per: 'longRest' };
    const context = rollContext({ id: 1, ts: 1, from: 'Caster', fromId: 'player', actorId: 'caster',
      source: 'damage,magicdamage,spelldamage', expression: '2d12+4', total: 20, rolls: [8, 8], rollSource: { name: 'Siphon Essence', text: '', source: 'Dread' } })!;
    expect(availableRollTriggers(c, context)[0]).toMatchObject({
      automation: { uses: undefined }, trigger: { id: 'heal', selectAmount: { resource: 'hp', heal: true, min: 0 } },
    });
    expect(availableRollTriggers(c, damage)).toEqual([]);
  });

  it('gates Dire Strike to weapon damage and Jump Scare to magic damage', () => {
    const c = character('dire-strike', 'jump-scare');
    expect(availableRollTriggers(c, { ...damage, weapon: 'Staff' }).map(t => t.trigger.id)).toEqual(['use', 'use']);
    expect(availableRollTriggers(c, { ...damage, source: 'damage', weapon: 'Sword' }).map(t => t.source)).toEqual(['Dire Strike']);
    expect(availableRollTriggers(c, { ...damage, source: 'damage,magicdamage,spelldamage', weapon: 'Spell' }).map(t => t.source)).toEqual(['Jump Scare']);
    expect(rules('dire-strike').rollTriggers![0]).toMatchObject({ removeFear: 1, cost: { hope: 1 } });
  });

  it('rolls Summon Horror and positive-Hope Darkfire immediately using Spellcast dice', () => {
    expect(rules('summon-horror').actions![0]).toMatchObject({ hostOwned: true, damage: { dice: 'd8+1', count: 'spellcast' } });
    expect(rules('darkfire').actions![0]).toMatchObject({
      hostOwned: true, selectAmount: { resource: 'hope', min: 1 }, damage: { dice: 'd8+6', count: 'spellcast' },
    });
  });

  it('binds Veil tokens to a scene buff and Dark Army to its remaining tokens', () => {
    setGmFear(4);
    const c = character('umbral-veil', 'dark-army');
    const veil = rules('umbral-veil');
    expect(sheetActions(veil).map(a => a.id)).toEqual(['cast', 'spend']);
    runAction(c, 'card:Umbral Veil', veil, veil.actions![0], 'Umbral Veil');
    expect(c.resources!.veil).toBe(4);
    expect(c.effects[0]).toMatchObject({ name: 'Umbral Veil', until: 'scene' });
    c.effects = [];
    syncResources(c);
    expect(c.resources!.veil).toBe(0);
    const army = rules('dark-army');
    runAction(c, 'card:Dark Army', army, army.conditionalRollTriggers![0], 'Dark Army');
    expect(c.effects[0]).toMatchObject({ name: 'Dark Army', tokenResource: 'fiends' });
    c.resources!.fiends = 0;
    syncEffectDependencies(c);
    expect(c.effects).toEqual([]);
    applyRest(c, 'short');
    expect(c.resources!.fiends).toBe(0);
  });

  it('gives caster and allies the same scene/action-expiring physical immunity', () => {
    const c = character('spectral-mist');
    const a = rules('spectral-mist'), action = a.actions![0];
    runAction(c, 'card:Spectral Mist', a, action, 'Spectral Mist');
    expect(c.hope.on).toBe(4);
    expect(c.effects[0]).toMatchObject({ name: 'Spectral Mist', until: 'scene', endOnAction: true });
    expect(action.chatBenefits![0].steps[0].effect).toEqual(action.steps![0].effect);
    const result = (type: 'magic' | 'physical') => computeDamage(c, availableMitigations(c), {
      damage: 15, type, armor: false, selected: new Set(),
    });
    expect(result('physical')).toMatchObject({ damage: 0, hp: 0 });
    expect(result('magic')).toMatchObject({ damage: 15, negated: false });
    applyRest(c, 'short');
    expect(c.effects).toEqual([]);
  });

  it('preselects Avatar damage scaled by live Fear and Hope-paid action maintenance, never reactions', () => {
    const c = character('avatar-of-terror'), a = rules('avatar-of-terror');
    runAction(c, 'card:Avatar of Terror', a, a.actions![0], 'Avatar of Terror');
    expect(availableRollOptions(c, ['reaction'])).toEqual([]);
    expect(availableRollOptions(c, ['action'])[0].option).toMatchObject({ defaultOn: true, cost: { hope: 1 }, hostOwned: true });
    const option = availableRollOptions(c, ['damage'])[0].option;
    expect(option.defaultOn).toBe(true);
    setGmFear(3);
    expect(rollOptionDice(c, option)).toBe('3d6');
    setGmFear(1);
    expect(rollOptionDice(c, option)).toBe('1d6');
    setGmFear(0);
    expect(rollOptionDice(c, option)).toBeUndefined();
  });
});
