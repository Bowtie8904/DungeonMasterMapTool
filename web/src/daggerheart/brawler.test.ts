import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import type { CompendiumFile } from './compendium';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, configuredRollOption,
  damageContributions, damageExpression, damagePool, derivedStats, rollBonus, selectionBlocker, syncDerived,
} from './engine';
import { comboDie, criticalMeleeBonus, martialStances, missingStanceChoices, refocus, shiftStance, stanceProblems } from './brawler';
import { transferable } from '../shared-cards';
import { rollSourceFor } from './roll-source';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { advancementLeft, applyLevelUp, buildCharacter, creationProblems, emptyChoices, levelUpProblems, type RulesData } from './rules';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function brawler(subclass = 'martial-artist', level = 10) {
  const c = emptyCharacter('Brawler');
  c.className = 'Brawler';
  c.subclass = subclass === 'martial-artist' ? 'Martial Artist' : 'Juggernaut';
  c.level = level;
  c.hp.slots = 6;
  c.evasion = 10;
  c.hope.on = 6;
  c.proficiency.on = 3;
  c.traits.find(t => t.name === 'Instinct')!.mod = 3;
  c.features = catalog.entries.filter(e => e.id === 'brawler' || e.id.startsWith(`brawler-${subclass}-`))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: e.kind === 'class' ? 'Class feature - Brawler' : `Subclass ${e.stage} - ${c.subclass}` })));
  c.knownStances = martialStances(c).map(s => s.name);
  syncDerived(c);
  c.resources!.focus = 6;
  return c;
}

function shift(c: ReturnType<typeof brawler>, name: string, stress = false) {
  const auto = c.features.find(f => f.name === 'Stance Fighter')!.automation!;
  return shiftStance(c, 'feat:Stance Fighter', auto, auto.actions!.find(a => a.id === (stress ? 'flow-shift' : 'shift'))!, name, 'Instinct');
}

describe('Brawler automation', () => {
  it('creates only an unarmed primary, scales BOTH pools, and retains the chosen trait', () => {
    const c = brawler();
    expect(c.activeWeapons).toHaveLength(1);
    expect(c.activeWeapons[0].name).toBe("Brawler's Strike");
    const weaponSource = rollSourceFor(c, `weapon:${c.activeWeapons[0].name}`);
    expect(weaponSource?.name).toBe("Brawler's Strike");
    expect(weaponSource?.text).toBe(`I Am The Weapon\n\n${c.features.find(f => f.name === 'I Am The Weapon')!.text}`);
    expect(weaponSource?.source).toContain('Melee');
    expect(transferable({ category: 'weapon', item: c.activeWeapons[0] })).toBe(false);
    const bonus = rollBonus(c, ['damage']);
    expect(damageExpression(c.activeWeapons[0].damage, 3, bonus)).toBe('3d8+3d6');
    expect(damageExpression('4d6kh3', 2, bonus)).toBe('4d6kh3');
    expect(damageContributions(c, c.activeWeapons[0], bonus).map(p => p.expression)).toEqual(['3d8+3d6']);
    expect(derivedStats(c).evasion).toBe(11);
    c.brawlerTrait = 'Presence';
    syncDerived(c);
    expect(c.activeWeapons[0].trait).toBe('Presence');
    const actual = { ...c.activeWeapons[0], name: 'Shield', slot: 'Secondary' as const, generated: undefined };
    expect(transferable({ category: 'weapon', item: actual })).toBe(true);
    c.activeWeapons.push(actual);
    syncDerived(c);
    expect(c.activeWeapons).toEqual([actual]);
    expect(c.effects.some(e => e.name === 'I Am The Weapon')).toBe(false);
    expect(derivedStats(c).evasion).toBe(10);
    c.activeWeapons = [];
    syncDerived(c);
    expect(c.activeWeapons[0].trait).toBe('Presence');
    syncDerived(c);
    expect(c.effects.filter(e => e.name === 'I Am The Weapon')).toHaveLength(1);
  });

  it('limits legacy stance catch-up to levels after acquiring the foundation', () => {
    const c = brawler('martial-artist', 7);
    c.knownStances = [];
    expect(missingStanceChoices(c)).toBe(8);
    c.levelUps = [{ level: 5, advancements: [{ type: 'multiclass', multiclass: {
      className: 'Brawler', domain: 'Bone', subclass: 'Brawler: Martial Artist',
    } }] }];
    expect(missingStanceChoices(c)).toBe(4);
    c.knownStances = ['Reliable', 'Quick'];
    expect(missingStanceChoices(c)).toBe(2);
  });

  it('offers alternate Combo costs only for Melee damage and pays just the selected cost', () => {
    const c = brawler();
    const options = availableRollOptions(c, ['damage'], c.activeWeapons[0]).filter(o => o.option.combo);
    expect(options).toHaveLength(2);
    expect(selectionBlocker(c, options)).toMatch(/only one Combo/);
    commitRollOptions(c, [options[1]]);
    expect(c.resources!.focus).toBe(5);
    expect(c.stress.on).toBe(0);
    expect(availableRollOptions(c, ['attack'], c.activeWeapons[0]).some(o => o.option.combo)).toBe(false);
    expect(availableRollOptions(c, ['damage'], { ...c.activeWeapons[0], range: 'Far' }).some(o => o.option.combo)).toBe(false);
    c.features = c.features.filter(f => f.name !== 'Flow State');
    expect(availableRollOptions(c, ['damage'], c.activeWeapons[0]).filter(o => o.option.combo)).toHaveLength(1);
    expect(comboDie(c)).toBe(4);
    c.levelUps = [{ level: 2, advancements: [{ type: 'combo' }] }, { level: 5, advancements: [{ type: 'combo' }] }];
    expect(comboDie(c)).toBe(8);
    expect(advancementLeft(c, 'combo', 6)).toBe(0);
    expect(advancementLeft(c, 'combo', 8)).toBe(1);
  });

  it('offers Overwhelm on attack chat and Surrounded with one editable Hope by default', () => {
    const c = brawler('juggernaut');
    const ctx = { source: 'attack,action', weapon: c.activeWeapons[0].name, hope: 8, fear: 3, total: 14 };
    expect(availableRollTriggers(c, ctx).some(t => t.source === 'Overwhelm')).toBe(true);
    expect(availableRollTriggers(c, { ...ctx, source: 'action' }).some(t => t.source === 'Overwhelm')).toBe(false);
    const option = availableRollOptions(c, ['attack'], c.activeWeapons[0]).find(o => o.source === 'Surrounded')!;
    expect(configuredRollOption(c, option).option.cost).toEqual({ hope: 1 });
    commitRollOptions(c, [configuredRollOption(c, option, 3)]);
    expect(c.hope.on).toBe(3);
    expect(() => configuredRollOption(c, option, 4)).toThrow();
    expect(criticalMeleeBonus(c, c.activeWeapons[0].name, true)).toEqual({ hope: 1, stress: 1, proficiency: 1 });
    expect(criticalMeleeBonus(c, c.activeWeapons[0].name, false).proficiency).toBe(0);
    c.activeWeapons[0].range = 'Far';
    expect(criticalMeleeBonus(c, c.activeWeapons[0].name, true).proficiency).toBe(0);
  });

  it('includes Eye for an Eye in mitigation with shared rest limit and actual reflected HP', () => {
    const c = brawler('juggernaut');
    const opts = availableMitigations(c);
    const eye = opts.find(o => o.source === 'Eye for an Eye')!;
    const r = computeDamage(c, opts, { damage: 1, type: 'physical', armor: false, selected: new Set([eye.id]) });
    expect(applyDamage(c, r, 'physical')).toContain('adversary within Melee range marks 1 HP');
    expect(c.stress.on).toBe(1);
    expect(c.usage['feat:Eye for an Eye'].used).toBe(1);
    expect(computeDamage(c, availableMitigations(c), { damage: 1, type: 'physical', armor: false, selected: new Set([eye.id]) }).applied).not.toContainEqual(eye);
    applyRest(c, 'short');
    expect(c.usage['feat:Eye for an Eye']).toBeUndefined();
  });

  it.each(['hope', 'stress'] as const)('automatically applies Not Done Yet, choosing %s only on final Severe damage', recovery => {
    const c = brawler('juggernaut');
    c.hope.on = 2; c.stress.on = 3;
    const severe = derivedStats(c).severe;
    const input = { damage: severe, type: 'physical' as const, armor: false, selected: new Set<string>(), recovery };
    const r = computeDamage(c, availableMitigations(c), input);
    expect(r.applied.some(m => m.option.recoveryChoice)).toBe(true);
    applyDamage(c, r, 'physical');
    expect(c.hope.on).toBe(recovery === 'hope' ? 3 : 2);
    expect(c.stress.on).toBe(recovery === 'stress' ? 2 : 3);
    c.hp.on = 0;
    c.activeArmor = { name: 'Armor', score: 2, tier: 1, major: 2, severe: 5 };
    syncDerived(c);
    const mitigated = computeDamage(c, availableMitigations(c), { ...input, damage: derivedStats(c).severe, armor: true });
    expect(mitigated.applied.some(m => m.option.recoveryChoice)).toBe(false);
  });

  it('holds a maximum of 6 Focus; refocus replaces rather than adds, once per rest', () => {
    const c = brawler();
    const stance = c.features.find(f => f.name === 'Stance Fighter')!;
    expect(stance.automation?.resources).toBeUndefined();
    expect(stance.automation?.actions?.some(a => a.refocus)).toBe(false);
    expect(stance.text).toContain('If you are already in a stance when you shift into a different stance, you automatically drop out of the previously active stance.');
    expect(stance.text).toContain('When you drop out of a stance, you lose any ongoing benefits it provides.');
    const a = c.features.find(f => f.name === 'Focus')!.automation!;
    const action = a.actions!.find(a => a.refocus)!;
    const faces = [2, 5, 1];
    refocus(c, 'feat:Focus', a, action, () => faces.shift()!);
    expect(c.resources!.focus).toBe(5);
    expect(actionBlocker(c, 'feat:Focus', a, action)).toContain('rest');
    expect(() => refocus(c, 'feat:Focus', a, action, () => 6)).toThrow();
    applyRest(c, 'short');
    refocus(c, 'feat:Focus', a, action, () => 6);
    expect(c.resources!.focus).toBe(6);
    c.resources!.focus = 99;
    syncDerived(c);
    expect(c.resources!.focus).toBe(6);
  });

  it.each([0, -1])('clears Focus without rolling at Instinct %i and consumes refocusing for the rest', instinct => {
    const c = brawler();
    c.traits.find(t => t.name === 'Instinct')!.mod = instinct;
    const auto = c.features.find(f => f.name === 'Focus')!.automation!;
    const action = auto.actions!.find(a => a.refocus)!;
    refocus(c, 'feat:Focus', auto, action, () => { throw new Error('Must not roll'); });
    expect(c.resources!.focus).toBe(0);
    expect(actionBlocker(c, 'feat:Focus', auto, action)).toContain('rest');
  });

  it('shifts only to known tier-eligible stances, replaces buffs, and offers Flow State', () => {
    const c = brawler();
    shift(c, 'Reliable');
    expect(c.resources!.focus).toBe(5);
    expect(rollBonus(c, ['attack']).value).toBe(1);
    shift(c, 'Favored', true);
    expect(c.resources!.focus).toBe(5);
    expect(c.stress.on).toBe(1);
    expect(c.effects.filter(e => e.stance)).toHaveLength(1);
    expect(rollBonus(c, ['attack']).value).toBe(0);
    expect(rollBonus(c, ['damage']).value).toBe(3);
    c.effects.push({ id: 'instinct-bonus', name: 'Instinct bonus', kind: 'buff', modifiers: [{ stat: 'trait.Instinct', value: 2 }] });
    expect(rollBonus(c, ['damage']).value).toBe(5);
    c.level = 1;
    expect(() => shift(c, 'Exacting')).toThrow();
    c.knownStances = [];
    expect(() => shift(c, 'Quick')).toThrow();
    expect(c.resources!.focus).toBe(5);
  });

  it('automates passive stance stats, per-pool Aggressive dice, and Exacting host rules', () => {
    const c = brawler(), w = c.activeWeapons[0];
    shift(c, 'Anchored');
    expect(derivedStats(c).major).toBe(c.level + 2);
    expect(derivedStats(c).severe).toBe(c.level * 2 + 2);
    shift(c, 'Aggressive');
    expect(derivedStats(c).evasion).toBe(10);
    expect(damageExpression(w.damage, 3, rollBonus(c, ['damage']), damagePool(c, w))).toBe('4d8kh3+4d6kh3');
    shift(c, 'Exacting');
    expect(damagePool(c, w)?.maximumOnes).toBe(true);
    expect(damagePool(c, w)?.extraDice).toBe(0);
  });

  it('wires conditional stance options, target actions, and Stable without requiring free Armor', () => {
    const c = brawler();
    shift(c, 'Quick');
    const quick = availableRollOptions(c, ['attack']);
    expect(quick.filter(o => o.source === 'Quick')).toHaveLength(2);
    expect(selectionBlocker(c, quick)).toMatch(/one cost/);
    shift(c, 'Honed');
    const honed = availableRollOptions(c, ['attack']).find(o => o.source === 'Honed')!;
    expect(honed.option.proficiency).toBe(1);
    shift(c, 'Grappling');
    const ctx = { source: 'attack', hope: 8, fear: 2, total: 10, weapon: c.activeWeapons[0].name };
    expect(availableRollTriggers(c, ctx).filter(t => t.source === 'Stance Fighter')).toHaveLength(2);
    expect(availableRollTriggers(c, { ...ctx, weapon: 'Bow' }).filter(t => t.source === 'Stance Fighter')).toHaveLength(0);
    shift(c, 'Stable');
    const mitigations = availableMitigations(c);
    const stable = mitigations.find(m => m.option.insteadOfArmor)!;
    c.armorTrack.on = c.armorTrack.slots;
    const result = computeDamage(c, mitigations, { damage: derivedStats(c).severe, type: 'physical', armor: false, selected: new Set([stable.id]) });
    expect(result.final).toBe(2);
    expect(result.armorMarked).toBe(0);
    expect(result.costs.focus).toBe(1);
    expect(result.error).toBeNull();
  });

  it.each(['severe', 'lastHp', 'rest'] as const)('drops stances on %s and loses their bonuses', reason => {
    const c = brawler();
    shift(c, 'Reliable');
    if (reason === 'rest') applyRest(c, 'short');
    else {
      if (reason === 'lastHp') c.hp.on = c.hp.slots - 1;
      applyDamage(c, computeDamage(c, availableMitigations(c), {
        damage: reason === 'severe' ? derivedStats(c).severe : 1, type: 'physical', armor: false, selected: new Set(),
      }), 'physical');
    }
    expect(c.effects.some(e => e.stance)).toBe(false);
    expect(rollBonus(c, ['attack']).value).toBe(0);
  });

  it('retains chosen stances, Focus and active stance across sheet and catalog reloads', () => {
    const c = brawler();
    shift(c, 'Reliable');
    const copy = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    updateCharacterAutomation(copy, [catalog]);
    expect(copy.knownStances).toEqual(c.knownStances);
    expect(copy.resources!.focus).toBe(5);
    expect(copy.effects.find(e => e.stance)?.name).toBe('Reliable');
    expect(martialStances(copy)).toHaveLength(16);
  });

  it('upgrades old stance sheets to a separate Focus feature without restoring spent uses or changing bonuses', () => {
    const c = brawler();
    shift(c, 'Favored');
    const modifiers = structuredClone(c.effects.find(e => e.stance)!.modifiers);
    c.effects.find(e => e.stance)!.text = 'Old description';
    c.features = c.features.filter(f => f.name !== 'Focus');
    c.features.find(f => f.name === 'Stance Fighter')!.text = 'Old stance rules';
    c.usage['feat:Stance Fighter:action:refocus'] = { used: 1, per: 'rest' };
    updateCharacterAutomation(c, [catalog]);
    updateCharacterAutomation(c, [catalog]);
    expect(c.features.filter(f => f.name === 'Focus')).toHaveLength(1);
    expect(c.resources!.focus).toBe(5);
    const focus = c.features.find(f => f.name === 'Focus')!;
    expect(focus.text).toContain('Focus represents your character’s poise, clarity, and control.');
    expect(actionBlocker(c, 'feat:Focus', focus.automation!, focus.automation!.actions![0])).toContain('rest');
    expect(c.usage['feat:Stance Fighter:action:refocus']).toBeUndefined();
    expect(c.effects.find(e => e.stance)?.text).toBe('Gain a bonus to damage rolls equal to a trait of your choice.');
    expect(c.effects.find(e => e.stance)?.modifiers).toEqual(modifiers);
    expect(c.features.find(f => f.name === 'Stance Fighter')!.text).toContain('mark your last Hit Point');
    applyRest(c, 'short');
    expect(actionBlocker(c, 'feat:Focus', focus.automation!, focus.automation!.actions![0])).toBeNull();
  });

  it('keeps the supplied descriptions verbatim for all sixteen stance choices and buffs', () => {
    const stances = martialStances(brawler());
    expect(Object.fromEntries(stances.map(s => [s.name, s.effect.text]))).toEqual({
      Favored: 'Gain a bonus to damage rolls equal to a trait of your choice.',
      Invigorating: 'On a successful attack, roll a d4. On a result of 4, gain a Focus.',
      Quick: 'When you make an attack, you can spend a Focus or mark a Stress to target another creature within range with that attack.',
      Reliable: 'Gain a +1 bonus to your attack rolls.',
      Aggressive: 'Gain a −1 penalty to your Evasion. On a successful attack, roll an additional damage die and discard the lowest result.',
      Anchored: 'Gain a +2 bonus to your damage thresholds. While in this stance, you can’t be moved against your will.',
      Defensive: 'Attack rolls targeting you from within Melee range have disadvantage unless the attacker marks a Stress to negate the disadvantage.',
      Otherworldly: 'On a successful attack, you can deal physical or magic damage.',
      Grappling: 'On a successful attack within Melee range, you can spend a Focus or mark a Stress to temporarily Restrain the target or throw the target up to Close range.',
      Scary: 'On a successful attack, the target must mark a Stress.',
      Stable: 'You can spend a Focus instead of an Armor Slot to reduce damage.',
      Vigilant: 'When you are targeted by an attack, you can mark a Stress to gain a d6 bonus to your Evasion against the attack.',
      Crushing: 'When you deal Severe damage, you can spend a Hope to force the target to mark an additional Hit Point.',
      Exacting: 'When you roll a 1 on a damage die, you can treat it as the highest value on the die instead.',
      Honed: 'Spend a Focus before you make an attack roll to gain a +1 bonus to your Proficiency for that attack.',
      Isolating: 'Gain advantage on attack rolls when there are no other creatures within Very Close range of you or your target.',
    });
  });

  it('provides Keen Defenses, Focus Cannon actions and a source-specific damage chat button', () => {
    const c = brawler();
    const keen = c.features.find(f => f.name === 'Keen Defenses')!.automation!.actions![0];
    expect(keen.cost).toEqual({ focus: 1 });
    expect(keen.sendRoll?.dice).toBe('@tier');
    const cannon = c.features.find(f => f.name === 'Focus Cannon')!.automation!;
    expect(cannon.actions![0].actionRoll?.trait).toBe('Instinct');
    expect(cannon.actions![0].cost).toEqual({ focus: 1 });
    expect(cannon.actions![1].damage).toEqual({ dice: 'd20+3', type: 'magic' });
    const ctx = { hope: 8, fear: 3, total: 14 };
    expect(availableRollTriggers(c, { ...ctx, source: 'action,trait:Instinct,feat:Focus Cannon' }).some(t => t.source === 'Focus Cannon')).toBe(true);
    expect(availableRollTriggers(c, { ...ctx, source: 'action,trait:Instinct' }).some(t => t.source === 'Focus Cannon')).toBe(false);
  });

  it('requires two tier-1 creation stances and one eligible new stance per level', () => {
    const data: RulesData = { classes: catalog.entries, ancestries: [{ id: 'human', name: 'Human' }], communities: [{ id: 'town', name: 'Town' }],
      weapons: [], armor: [], consumables: [], items: JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/items.json', import.meta.url), 'utf8')).entries, transformations: [], cards: [
        { id: 'one', name: 'One', level: 1, domain: 'Valor', cardType: 'Ability' },
        { id: 'two', name: 'Two', level: 1, domain: 'Bone', cardType: 'Ability' },
      ] };
    const choices = { ...emptyChoices(), className: 'Brawler', subclass: 'Brawler: Martial Artist', ancestry: 'Human', community: 'Town',
      experiences: ['Fighter', 'Wanderer'], stances: ['Reliable', 'Quick'], cards: ['One', 'Two'] };
    expect(creationProblems(data, { ...choices, stances: ['Exacting', 'Quick'] }).some(p => p.includes('stance'))).toBe(true);
    const c = buildCharacter(data, choices);
    expect(c.knownStances).toEqual(['Reliable', 'Quick']);
    const rec = { level: 2, advancements: [{ type: 'combo' as const }, { type: 'evasion' as const }], experience: 'Veteran', stances: ['Aggressive'] };
    expect(levelUpProblems(data, c, rec)).toEqual([]);
    applyLevelUp(data, c, rec);
    expect(c.knownStances).toEqual(['Reliable', 'Quick', 'Aggressive']);
    expect(comboDie(c)).toBe(6);
    expect(levelUpProblems(data, c, { level: 3, advancements: [], stances: ['Exacting'] }).some(p => p.includes('stance'))).toBe(true);
    expect(stanceProblems(martialStances(c), ['Quick', 'Quick'], 2, 1)).toHaveLength(1);
  });
});
