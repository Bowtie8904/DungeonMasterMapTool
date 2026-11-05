import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, type CharacterSheet } from './data';
import type { CompendiumFile } from './compendium';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, damageExpression,
  effectiveAdvantage, rollBonus, rollTargets, runAction, selectedRollBonus, selectionBlocker, suggestedAdvantage, usesLeft, type Automation,
} from './engine';
import { applyDamage, availableMitigations, computeDamage, mitigationBlocker } from './damage';

const directory = new URL('../../../dmmap-daggerheart/', import.meta.url);
const catalog = (name: string): CompendiumFile => JSON.parse(readFileSync(new URL(`${name}.json`, directory), 'utf8'));
const ancestries = catalog('ancestries');
function rules(ancestry: string, name: string): Automation {
  const found = ancestries.entries.find((e) => e.id === ancestry)?.features?.find((f) => f.name === name)?.automation;
  if (!found) throw new Error(`Missing ancestry automation ${ancestry}/${name}`);
  return structuredClone(found);
}
function character(ancestry: string): CharacterSheet {
  const c = emptyCharacter(ancestry);
  c.heritage = structuredClone(ancestries.entries.find((e) => e.id === ancestry)!.features!)
    .map((f) => ({ ...f, source: ancestry }));
  c.hp.slots = 6;
  c.stress.slots = 6;
  c.hope.on = 6;
  return c;
}

describe('ancestry automation and matching catalog rules', () => {
  it('offers Anchoring Hook only on reactions and only while in stock', () => {
    const c = character('elf');
    const hook = catalog('items').entries.find((entry) => entry.id === 'anchoring-hook')!;
    c.inventory = [{ name: hook.name, type: 'gear', qty: 1, automation: hook.automation }];
    expect(availableRollOptions(c, ['action', 'trait:Agility'])).toHaveLength(0);
    expect(availableRollOptions(c, ['reaction', 'trait:Agility']).map((option) => option.source))
      .toEqual(['Quick Reactions', 'Anchoring Hook']);
    c.inventory[0].qty = 0;
    expect(availableRollOptions(c, ['reaction']).map((option) => option.source)).toEqual(['Quick Reactions']);
  });

  it('separates reaction-only and action-only chat triggers while retaining generic Duality triggers', () => {
    const c = character('elf');
    c.features = [{ source: 'Test', name: 'Modes', text: '', automation: { rollTriggers: [
      { id: 'action', label: 'Action only', on: 'action' },
      { id: 'reaction', label: 'Reaction only', on: 'reaction' },
      { id: 'either', label: 'Either', on: 'duality' },
    ] } }];
    const ctx = { source: 'reaction,trait:Agility', hope: 8, fear: 4, total: 12 };
    expect(availableRollTriggers(c, ctx).map((trigger) => trigger.trigger.id)).toEqual(['reaction', 'either']);
    expect(availableRollTriggers(c, { ...ctx, source: 'action,trait:Agility' }).map((trigger) => trigger.trigger.id))
      .toEqual(['action', 'either']);
  });

  it('replaces action with reaction and keeps the mode ahead of bounded source descriptions', () => {
    const targets = ['spellcast', 'trait:Instinct', `card:${'Long spell name'.repeat(8)}`, 'action'];
    const reaction = rollTargets(targets, true);
    expect(reaction).not.toContain('action');
    expect(reaction).toContain('trait:Instinct');
    expect(reaction.join(',').slice(0, 80).split(',')).toContain('reaction');
    expect(rollTargets(reaction, false)).not.toContain('reaction');
    expect(rollTargets(reaction, false)[0]).toBe('action');
  });

  it('offers Quick Reactions as an optional reaction-roll advantage costing one Stress', () => {
    const c = character('elf');
    expect(availableRollOptions(c, ['action', 'trait:Agility'])).toHaveLength(0);
    const options = availableRollOptions(c, ['reaction', 'trait:Agility']);
    const reaction = options.find(o => o.source === 'Quick Reactions')!;
    expect(reaction.option).toMatchObject({
      label: 'Quick Reactions (reaction roll)', cost: { stress: 1 }, advantage: 'advantage',
    });
    expect(reaction.option.defaultOn).not.toBe(true);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    expect(suggestedAdvantage([reaction])).toBe(1);
    expect(effectiveAdvantage(1, [reaction])).toBe(1);
    expect(selectionBlocker(c, [reaction])).toBeNull();
    expect(c.stress.on).toBe(0);
    commitRollOptions(c, [reaction]);
    expect(c.stress.on).toBe(1);
    c.stress.on = c.stress.slots;
    expect(selectionBlocker(c, [reaction])).toBeTruthy();
    expect(rules('elf', 'Quick Reactions').actions).toBeUndefined();
  });

  it('counts per-scene uses and blocks them until the scene ends or a rest', () => {
    const c = character('aetheris');
    const limited: Automation = { uses: { max: 1, per: 'scene' }, actions: [{ id: 'use', label: 'Limited' }] };
    expect(usesLeft(c, 'limited', limited)).toBe(1);
    expect(runAction(c, 'limited', limited, limited.actions![0], 'Limited')).not.toBeNull();
    expect(c.usage.limited).toEqual({ used: 1, per: 'scene' });
    expect(usesLeft(c, 'limited', limited)).toBe(0);
    expect(actionBlocker(c, 'limited', limited, limited.actions![0])).toBe('No uses left this scene');
    applyRest(c, 'short');
    expect(usesLeft(c, 'limited', limited)).toBe(1);
    expect(ancestries.entries.find((e) => e.id === 'gnome')!.features!.find((f) => f.name === 'Flicker Step')!.automation).toBeUndefined();
  });

  it('starts Ignition on primary damage only and pays Stress once', () => {
    const c = character('emberkin');
    const primary = { name: 'Sword', slot: 'Primary', damageType: 'phys', trait: 'Agility', range: 'Melee', damage: 'd8', hands: 1, tier: 1 } as const;
    const secondary = { ...primary, name: 'Dagger', slot: 'Secondary' } as const;
    c.activeWeapons = [primary, secondary];
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'], secondary)).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'], { ...primary, name: 'Elemental Breath' })).toHaveLength(0);
    const options = availableRollOptions(c, ['damage'], primary);
    expect(options).toHaveLength(1);
    expect(options[0].active).toBe(false);
    const bonus = selectedRollBonus(c, rollBonus(c, ['damage'], primary), options);
    expect(damageExpression('d8', 3, bonus)).toBe('3d8+1d6');
    expect(c.stress.on).toBe(0);
    commitRollOptions(c, options);
    expect(c.stress.on).toBe(1);
    expect(c.effects[0].name).toBe('Ignition');
    const active = availableRollOptions(c, ['damage'], primary);
    expect(active[0].active).toBe(true);
    commitRollOptions(c, active);
    expect(c.stress.on).toBe(1);
    expect(c.effects).toHaveLength(1);
  });

  it('only offers Retract resistance and action disadvantage while retracted', () => {
    const c = character('galapa');
    expect(availableRollOptions(c, ['action'])).toHaveLength(0);
    expect(availableMitigations(c)).toHaveLength(0);
    const a = rules('galapa', 'Retract');
    runAction(c, 'feat:Retract', a, a.actions![0], 'Retract');
    expect(c.effects[0]).toMatchObject({ name: 'Retracted', blocksMovement: true });
    const options = availableRollOptions(c, ['action', 'trait:Agility']);
    expect(options[0].option.defaultOn).toBe(true);
    expect(suggestedAdvantage(options)).toBe(-1);
    expect(effectiveAdvantage(1, options)).toBe(1);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    const mitigation = availableMitigations(c);
    expect(mitigation[0].option.defaultOn).toBe(true);
    const input = { damage: 12, type: 'physical' as const, armor: false, selected: new Set([mitigation[0].id]) };
    expect(computeDamage(c, mitigation, input).damage).toBe(6);
    expect(computeDamage(c, mitigation, { ...input, type: 'magic' }).damage).toBe(12);
    c.effects = [];
    expect(mitigationBlocker(c, mitigation[0], 'physical')).toContain('no longer active');
  });

  it('suppresses only disadvantage for Surefooted and suggests situational advantage', () => {
    const c = character('goblin');
    const options = availableRollOptions(c, ['trait:Agility', 'action']);
    expect(options[0].option.defaultOn).toBe(true);
    expect(effectiveAdvantage(-1, options)).toBe(0);
    expect(effectiveAdvantage(1, options)).toBe(1);
    expect(effectiveAdvantage(-1, [])).toBe(-1);
    expect(availableRollOptions(c, ['trait:Instinct', 'action'])).toHaveLength(0);
    for (const [ancestry, trait] of [['simiah', 'Agility'], ['ursusfolk', 'Instinct']]) {
      const matching = availableRollOptions(character(ancestry), [`trait:${trait}`, 'action']);
      expect(suggestedAdvantage(matching)).toBe(1);
      expect(suggestedAdvantage([])).toBe(0);
      expect(effectiveAdvantage(-1, matching)).toBe(-1);
    }
  });

  it('changes outcome-dependent reactions without changing the Duality Dice', () => {
    const c = character('infernis');
    c.loadout = [{ name: 'Blighting Strike', domain: 'Dread', level: 1, type: 'Spell', recall: 0, text: '',
      automation: catalog('domain-cards').entries.find((e) => e.name === 'Blighting Strike')!.automation }];
    const roll = { source: 'card:Blighting Strike,spellcast,action', hope: 2, fear: 11, total: 15 };
    expect(availableRollTriggers(c, roll).map((t) => t.trigger.id)).toContain('fear-damage');
    const changed = availableRollTriggers(c, { ...roll, outcome: 'hope' });
    expect(changed.map((t) => t.trigger.id)).toContain('hope-damage');
    expect(changed.map((t) => t.trigger.id)).not.toContain('fear-damage');
    expect(changed.map((t) => t.source)).not.toContain('Fearless');
    expect(roll).toMatchObject({ hope: 2, fear: 11, total: 15 });
    expect(availableRollTriggers(c, { ...roll, hope: 11, outcome: 'hope' })
      .some((t) => t.trigger.when === 'hope')).toBe(false);
  });

  it('describes actual rolls, target effects and limited actions in the catalog', () => {
    expect(rules('clank', 'Purposeful Design').creation?.experienceBonus).toBe(1);
    expect(rules('fungril', 'Fungril Network').actions![0].actionRoll).toEqual({ trait: 'Instinct', difficulty: 12 });
    expect(rules('firbolg', 'Unshakable').actions![0].check).toEqual({ dice: '1d6', unless: 6, mark: 'stress' });
    expect(rules('katari', 'Retracting Claws').actions![0].actionRoll).toEqual({ trait: 'Agility', attack: true });
    for (const [ancestry, name, trait, dice] of [['drakona', 'Elemental Breath', 'Instinct', 'd8'], ['ribbet', 'Long Tongue', 'Finesse', 'd12']]) {
      const a = rules(ancestry, name);
      expect(a.actions![0].actionRoll).toEqual({ trait, attack: true });
      expect(a.actions![1].damage?.dice).toBe(dice);
    }
    for (const ancestry of ['firbolg', 'centaur']) {
      const a = rules(ancestry, 'Charge');
      expect(a.actions![0].damage).toMatchObject({ dice: '1d12', fixed: true, type: 'physical' });
      expect(a.rollTriggers![0]).toMatchObject({ on: 'trait:Agility', cost: { stress: 1 }, damage: { dice: '1d12', fixed: true } });
    }
    expect(rules('aetheris', 'Hallowed Aura').rollTriggers![0]).toMatchObject({ audience: 'ally', when: 'fear', outcome: 'hope' });
    expect(rules('faerie', 'Luckbender').rollTriggers![0]).toMatchObject({ audience: 'any', on: 'action', reroll: 'duality' });
    const storm = rules('skykin', 'Eye of the Storm').actions![0];
    expect(storm.target).toBe('actor');
    expect(storm.steps![0].effect).toMatchObject({ endOnSourceSevere: true, modifiers: [{ stat: 'evasion', value: 1 }] });
    expect(rules('tidekin', 'Lifespring').actions![0]).toMatchObject({ target: 'actor', steps: [{ op: 'clear', resource: 'hp', amount: 1 }] });
    const c = character('halfling');
    const luck = rules('halfling', 'Luckbringer');
    expect(luck.actions![0].target).toBe('party');
    c.usage['feat:Luckbringer'] = { used: 1, per: 'session' };
    expect(actionBlocker(c, 'feat:Luckbringer', luck, luck.actions![0])).toBe('No uses left this session');
  });

  it('expires own damage-linked buffs on Severe damage even when fewer HP remain', () => {
    const c = character('galapa');
    c.hp.on = 5;
    c.effects = [
      { id: 'mantle', name: 'Mantle', kind: 'buff', endOnSourceSevere: true },
      { id: 'other', name: 'Other caster grant', kind: 'buff', endOnSourceSevere: true, sourceActorId: 'other-caster' },
    ];
    const input = { damage: 3, type: 'physical' as const, armor: false, selected: new Set<string>() };
    const result = computeDamage(c, [], input);
    expect(result.final).toBe(3);
    applyDamage(c, result, input.type);
    expect(c.hp.on).toBe(6);
    expect(c.effects.map((effect) => effect.id)).toEqual(['other']);
    const mantle = catalog('classes').entries.flatMap((e) => e.features ?? []).find((f) => f.name === 'Patron’s Mantle')!;
    expect(mantle.automation!.actions![0].steps![0].effect!.endOnSourceSevere).toBe(true);
  });

  it('applies matching outcome, healing and advantage fixes outside ancestries', () => {
    const sources = (category: string) => catalog(category).entries.flatMap((e) => [e, ...(e.features ?? [])]);
    const find = (category: string, name: string) => sources(category).find((s) => s.name === name)!.automation!;
    expect(find('communities', 'Unbound').rollTriggers![0]).toMatchObject({ outcome: 'hope', when: 'fear' });
    expect(find('domain-cards', 'Stealth Expertise').rollTriggers![0]).toMatchObject({ audience: 'any', outcome: 'hope', cost: { stress: 1 } });
    expect(find('items', 'Red Nose of Hope').rollTriggers![0]).toMatchObject({ outcome: 'hope', uses: { max: 1, per: 'longRest' } });
    for (const [category, name] of [['classes', 'Life Support'], ['domain-cards', 'Healing Strike']]) {
      expect(find(category, name).actions![0]).toMatchObject({ target: 'actor', steps: [{ op: 'clear', resource: 'hp', amount: 1 }] });
    }
    expect(find('classes', 'Sparing Touch').actions!.map((a) => [a.target, a.steps![0].resource, a.steps![0].amount]))
      .toEqual([['actor', 'hp', 2], ['actor', 'stress', 2]]);
    for (const name of ['Privilege', 'Well-Read', 'Steady', 'Scoundrel', 'Low-Light Living', 'Lightfoot']) {
      expect(find('communities', name).rollOptions![0]).toMatchObject({ roll: 'action', advantage: 'advantage' });
      expect(find('communities', name).rollOptions![0].description).toBeTruthy();
    }
    for (const [category, name] of [['items', 'Skeleton Key'], ['items', 'Veilcloak'], ['armor', 'Deep-Forged Coral Armor']]) {
      expect(find(category, name).rollOptions![0].advantage).toBe('advantage');
    }
  });

  it('excludes attacks from Stealth Expertise without excluding non-attack Fear actions', () => {
    const c = character('galapa');
    const card = catalog('domain-cards').entries.find((e) => e.name === 'Stealth Expertise')!;
    c.loadout = [{ name: card.name, text: card.text!, domain: 'Midnight', type: 'Ability', level: 4, recall: 0, automation: card.automation }];
    const ctx = { source: 'action,trait:Agility', hope: 3, fear: 9, total: 12 };
    expect(availableRollTriggers(c, ctx).filter((t) => t.source === card.name)).toHaveLength(1);
    for (const source of ['attack,action,trait:Agility', 'action,attack,spellcast', 'attack']) {
      expect(availableRollTriggers(c, { ...ctx, source }).filter((t) => t.source === card.name)).toHaveLength(0);
    }
    expect(availableRollTriggers(c, { ...ctx, hope: 10 }).filter((t) => t.source === card.name)).toHaveLength(0);
  });
});
