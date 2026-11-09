import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, type DomainCard } from './data';
import { actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, derivedStats, damageRollFormula, maximizeDamageDie, maximizableDamageDice, rollBonus, rollOptionDice, runAction, selectedRollBonus, selectionBlocker, setGmFear, statBreakdown, statSources, suggestedAdvantage, traitTotal, type Automation } from './engine';
import { availableMitigations, mitigationBlocker } from './damage';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));

function rules(id: string, spell?: string): Automation {
  const entry = catalog.entries.find((card) => card.id === id);
  const automation = spell ? entry?.spells?.find((item) => item.name === spell)?.automation : entry?.automation;
  if (!automation) throw new Error(`Missing automation for ${id}${spell ? `/${spell}` : ''}`);
  return automation;
}

describe('domain-card cost and limit corrections', () => {
  const load = (c: ReturnType<typeof emptyCharacter>, id: string) => {
    const entry = catalog.entries.find(card => card.id === id)!;
    const card: DomainCard = { name: entry.name, domain: entry.domain!, level: entry.level!, type: entry.cardType as DomainCard['type'],
      recall: entry.recall!, text: entry.text ?? '', automation: entry.automation, spells: entry.spells };
    c.loadout.push(card);
    return card.automation!;
  };

  it.each([[1, 9, 19], [2, 11, 24], [5, 13, 31], [8, 15, 38]])(
    'uses Bare Bones base thresholds at level %i without double counting level', (level, major, severe) => {
      const c = emptyCharacter('Test');
      c.level = level;
      c.traits.find(trait => trait.name === 'Strength')!.mod = 2;
      c.effects.push({ id: 'strength', name: 'Strength bonus', kind: 'buff', modifiers: [{ stat: 'trait.Strength', value: 1 }] });
      load(c, 'bare-bones');
      expect(derivedStats(c)).toMatchObject({ armorSlots: 6, major: major + level, severe: severe + level });
      const breakdown = statBreakdown(c);
      expect(breakdown.major.reduce((sum, source) => sum + source.value, 0)).toBe(major + level);
      expect(breakdown.severe.reduce((sum, source) => sum + source.value, 0)).toBe(severe + level);
      c.activeArmor = { name: 'Armor', score: 2, major: 5, severe: 10, tier: 1 };
      expect(derivedStats(c)).toMatchObject({ armorSlots: 2, major: 5 + level, severe: 10 + level });
      delete c.activeArmor;
      c.vault.push(c.loadout.pop()!);
      expect(derivedStats(c).armorSlots).toBe(0);
    });

  it('replaces Flight tokens with current Agility and never awards less than one', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'flight');
    const trait = c.traits.find(trait => trait.name === 'Agility')!;
    trait.mod = 2;
    c.effects.push({ id: 'agility', name: 'Agility bonus', kind: 'buff', modifiers: [{ stat: 'trait.Agility', value: 1 }] });
    c.resources = { flight: 9 };
    runAction(c, 'card:Flight', a, a.actions![0], 'Flight');
    expect(c.resources!.flight).toBe(3);
    trait.mod = -4;
    runAction(c, 'card:Flight', a, a.actions![0], 'Flight');
    expect(c.resources!.flight).toBe(1);
  });

  it('regenerates long-rest tokens after expiring trait bonuses, including vaulted cards', () => {
    const c = emptyCharacter('Test');
    load(c, 'strategic-approach');
    load(c, 'inspirational-words');
    load(c, 'restoration');
    c.traits.find(trait => trait.name === 'Knowledge')!.mod = -1;
    c.traits.find(trait => trait.name === 'Presence')!.mod = 2;
    c.traits.find(trait => trait.name === 'Instinct')!.spellcast = true;
    c.traits.find(trait => trait.name === 'Instinct')!.mod = 3;
    c.effects.push({ id: 'presence', name: 'Temporary Presence', kind: 'buff', until: 'rest',
      modifiers: [{ stat: 'trait.Presence', value: 4 }] });
    c.vault.push(...c.loadout.splice(0));
    applyRest(c, 'long');
    expect(c.resources).toMatchObject({ strategic: 1, inspiration: 2, restoration: 3 });
  });

  it('spends all Never Upstaged tokens and all Sigil dice, including pools larger than four', () => {
    const c = emptyCharacter('Test');
    c.level = 8;
    load(c, 'never-upstaged');
    load(c, 'sigil-of-retribution');
    c.resources = { upstaged: 7, retribution: 6 };
    const options = availableRollOptions(c, ['damage']);
    const upstaged = options.find(option => option.option.id === 'upstaged')!;
    const sigil = options.find(option => option.source === 'Sigil of Retribution')!;
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [upstaged]).value).toBe(35);
    expect(rollOptionDice(c, sigil.option)).toBe('6d8');
    expect(c.resources!.upstaged).toBe(7);
    commitRollOptions(c, [upstaged, sigil], false, ['damage']);
    expect(c.resources!.upstaged).toBe(0);
    expect(c.resources!.retribution).toBe(0);
    expect(selectionBlocker(c, [upstaged])).toContain('no tokens');
  });

  it('keeps Smite charged until weapon damage is sent and does not offer it for spells', () => {
    const c = emptyCharacter('Test');
    c.hope.on = 6;
    const a = load(c, 'smite');
    runAction(c, 'card:Smite', a, a.actions![0], 'Smite');
    expect(c.hope.on).toBe(3);
    expect(availableRollOptions(c, ['damage', 'spelldamage'])).toHaveLength(0);
    const option = availableRollOptions(c, ['damage'])[0];
    expect(option.option).toMatchObject({ multiplier: 2, magicDamage: true });
    expect(damageRollFormula('2d8+2', [], [option]).expression).toBe('(2d8+2)*2');
    expect(c.effects.some(effect => effect.name === 'Smite')).toBe(true);
    commitRollOptions(c, [option], false, ['damage']);
    expect(c.effects.some(effect => effect.name === 'Smite')).toBe(false);
  });

  it('gates Runic Adrenaline advantage on marked HP and offers its paid die after the trait roll', () => {
    const c = emptyCharacter('Test');
    load(c, 'runic-adrenaline');
    expect(availableRollOptions(c, ['action', 'trait:Strength'])).toHaveLength(0);
    c.hp.on = 1;
    expect(availableRollOptions(c, ['action', 'trait:Strength'])[0].option.advantageDie).toBe(8);
    const triggers = availableRollTriggers(c, { source: 'action,trait:Strength', hope: 3, fear: 6, total: 9 });
    expect(triggers).toHaveLength(1);
    expect(triggers[0].trigger).toMatchObject({ addDice: '1d8', cost: { hp: 1 } });
  });

  it('requires critical attacks for all three distinct Champions Edge choices', () => {
    const c = emptyCharacter('Test');
    load(c, 'champions-edge');
    const ctx = { source: 'action,attack', hope: 3, fear: 6, total: 9 };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    expect(availableRollTriggers(c, { ...ctx, hope: 6, total: 12 })).toHaveLength(3);
    expect(availableRollTriggers(c, { ...ctx, source: 'action', hope: 6 })).toHaveLength(0);
  });

  it('rejects Blighting Strike failure choices on known success or any critical', () => {
    const c = emptyCharacter('Test');
    load(c, 'blighting-strike');
    const ctx = { source: 'action,spellcast,card:Blighting Strike', hope: 3, fear: 6, total: 9, difficulty: 14 };
    expect(availableRollTriggers(c, ctx).filter(t => t.trigger.failedOnly)).toHaveLength(2);
    expect(availableRollTriggers(c, { ...ctx, total: 15 }).filter(t => t.trigger.failedOnly)).toHaveLength(0);
    expect(availableRollTriggers(c, { ...ctx, hope: 3, fear: 3, total: 6 }).filter(t => t.trigger.failedOnly)).toHaveLength(0);
  });

  it('finds nested grimoire reactions and removes them when their book is vaulted', () => {
    const c = emptyCharacter('Test');
    load(c, 'book-of-vagras');
    const ctx = { source: 'action,spellcast,card:Book of Vagras,card:Arcane Door', hope: 4, fear: 6, total: 13, difficulty: 13 };
    expect(availableRollTriggers(c, ctx).map(t => t.key)).toContain('card:Book of Vagras/Arcane Door');
    expect(availableRollTriggers(c, { ...ctx, total: 12 })).toHaveLength(0);
    c.vault.push(c.loadout.pop()!);
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
  });

  it('offers Blink Out only for its successful Spellcast roll while active', () => {
    const c = emptyCharacter('Test');
    const automation = rules('blink-out');
    c.loadout = [{ name: 'Blink Out', domain: 'Arcana', level: 4, type: 'Spell', recall: 1, text: '', automation }];
    const ctx = { source: 'action,spellcast,card:Blink Out', hope: 5, fear: 6, total: 11 };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    expect(availableRollTriggers(c, { ...ctx, total: 12 })[0].trigger.spendHopeAmount).toBe(true);
    expect(availableRollTriggers(c, { ...ctx, hope: 5, fear: 5 })).toHaveLength(1);
    expect(availableRollTriggers(c, { ...ctx, source: 'action,spellcast,card:Other', total: 20 })).toHaveLength(0);
    c.vault.push(c.loadout.pop()!);
    expect(availableRollTriggers(c, { ...ctx, total: 20 })).toHaveLength(0);
  });

  it('resets Homet spell uses independently at the correct rest', () => {
    const c = emptyCharacter('Test');
    const pass = rules('book-of-homet', 'Pass Through');
    const gate = rules('book-of-homet', 'Plane Gate');
    expect(pass).toMatchObject({ automaticRestUse: true, spellcastDifficulty: 13, uses: { max: 1, per: 'rest' } });
    expect(gate).toMatchObject({ automaticRestUse: true, spellcastDifficulty: 14, uses: { max: 1, per: 'longRest' } });
    c.usage['card:Book of Homet/Pass Through'] = { used: 1, per: 'rest' };
    c.usage['card:Book of Homet/Plane Gate'] = { used: 1, per: 'longRest' };
    applyRest(c, 'short');
    expect(c.usage['card:Book of Homet/Pass Through']).toBeUndefined();
    expect(c.usage['card:Book of Homet/Plane Gate'].used).toBe(1);
    applyRest(c, 'long');
    expect(c.usage['card:Book of Homet/Plane Gate']).toBeUndefined();
  });

  it('uses the printed grimoire difficulties and tracks Runic Lock on success', () => {
    for (const [book, spell, difficulty] of [
      ['book-of-grynn', 'Wall of Flame', 15], ['book-of-ronin', 'Transform', 15],
      ['book-of-sitil', 'Illusion', 14], ['book-of-tyfar', 'Mysterious Mist', 13],
      ['book-of-vagras', 'Arcane Door', 13], ['book-of-yarrow', 'Timejammer', 18],
    ] as const) expect(rules(book, spell).spellcastDifficulty).toBe(difficulty);
    expect(rules('book-of-vagras', 'Runic Lock')).toMatchObject({
      spellcastDifficulty: 15, automaticRestUse: true, uses: { max: 1, per: 'rest' },
    });
  });

  it('only offers the Arcana-Touched dice swap with four active Arcana cards', () => {
    const c = emptyCharacter('Test');
    const card = (name: string): DomainCard => ({ name, domain: 'Arcana', level: 1, type: 'Ability', recall: 0, text: '' });
    c.loadout = [{ ...card('Arcana-Touched'), automation: rules('arcana-touched') }, card('two'), card('three')];
    c.vault = [card('four')];
    const ctx = { source: 'action', hope: 4, fear: 8, total: 12 };
    expect(availableRollTriggers(c, ctx)).toHaveLength(0);
    c.loadout.push(c.vault.pop()!);
    expect(availableRollTriggers(c, ctx)[0].trigger.swapDuality).toBe(true);
    expect(availableRollTriggers(c, { ...ctx, fear: 4 })).toHaveLength(0);
    c.usage['card:Arcana-Touched'] = { used: 1, per: 'rest' };
    expect(actionBlocker(c, 'card:Arcana-Touched', rules('arcana-touched'), rules('arcana-touched').conditionalRollTriggers![0])).toBeTruthy();
  });

  it('keeps Midnight-Touched damage independent of its zero-Hope rest action', () => {
    const c = emptyCharacter('Test');
    const automation = rules('midnight-touched');
    const card = (name: string): DomainCard => ({ name, domain: 'Midnight', level: 1, type: 'Ability', recall: 0, text: '' });
    c.stress.slots = 6;
    c.hope.on = 1;
    c.loadout = [{ ...card('Midnight-Touched'), automation }, card('two'), card('three')];
    c.vault = [card('four')];
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    c.loadout.push(c.vault.pop()!);
    const action = automation.conditionalActions![0];
    expect(actionBlocker(c, 'card:Midnight-Touched', automation, action)).toBe('Requires zero Hope');
    c.hope.on = 0;
    runAction(c, 'card:Midnight-Touched', automation, action, 'Midnight-Touched');
    expect(c.hope.on).toBe(1);
    expect(c.usage['card:Midnight-Touched'].used).toBe(1);
    const option = availableRollOptions(c, ['damage'])[0];
    expect(option.option.input?.source).toBe('attackFear');
    commitRollOptions(c, [option]);
    expect(c.stress.on).toBe(1);
  });

  it('applies Frenzy bonuses and its Armor restriction only while active', () => {
    const c = emptyCharacter('Test');
    const automation = rules('frenzy');
    c.loadout = [{ name: 'Frenzy', domain: 'Blade', level: 8, type: 'Ability', recall: 3, text: '', automation }];
    const baseline = derivedStats(c).severe;
    expect(availableMitigations(c)).toHaveLength(0);
    expect(rollBonus(c, ['damage']).value).toBe(0);
    runAction(c, 'card:Frenzy', automation, automation.conditionalActions![0], 'Frenzy');
    expect(derivedStats(c).severe).toBe(baseline + 8);
    expect(rollBonus(c, ['damage']).value).toBe(10);
    expect(availableMitigations(c)[0].option).toMatchObject({ passive: true, armorBlocked: true });
    c.effects = [];
    expect(derivedStats(c).severe).toBe(baseline);
    expect(availableMitigations(c)).toHaveLength(0);
    expect(actionBlocker(c, 'card:Frenzy', automation, automation.conditionalActions![0])).toBeTruthy();
  });

  it('updates Eldritch Flesh thresholds and Power Through Pain damage from marked tracks', () => {
    const c = emptyCharacter('Test');
    const card = (name: string, id: string): DomainCard => ({ name, domain: 'Blood', level: 1, type: 'Ability', recall: 0, text: '', automation: rules(id) });
    c.loadout = [card('Eldritch Flesh', 'eldritch-flesh'), card('Power Through Pain', 'power-through-pain')];
    const base = derivedStats(c);
    c.stress.on = 3;
    c.hp.on = 2;
    expect(derivedStats(c)).toMatchObject({ major: base.major + 3, severe: base.severe + 3 });
    expect(statSources(c, 'thresholds')[0].value).toBe(3);
    expect(rollBonus(c, ['damage']).value).toBe(4);
    c.stress.on = 0;
    c.hp.on = 0;
    expect(derivedStats(c).severe).toBe(base.severe);
    expect(rollBonus(c, ['damage']).value).toBe(0);
  });

  it('toggles Shadowhunter benefits only while its card is active and the player is shrouded', () => {
    const c = emptyCharacter('Test');
    const automation = rules('shadowhunter');
    c.loadout = [{ name: 'Shadowhunter', domain: 'Midnight', level: 8, type: 'Ability', recall: 2, text: '', automation }];
    const baseline = derivedStats(c).evasion;
    expect(availableRollOptions(c, ['attack'])).toHaveLength(0);
    runAction(c, 'card:Shadowhunter', automation, automation.conditionalActions![0], 'Shadowhunter');
    expect(derivedStats(c).evasion).toBe(baseline + 1);
    expect(availableRollOptions(c, ['attack'])[0].option).toMatchObject({ advantage: 'advantage', defaultOn: true });
    c.vault.push(c.loadout.pop()!);
    expect(derivedStats(c).evasion).toBe(baseline);
    expect(availableRollOptions(c, ['attack'])).toHaveLength(0);
    c.loadout.push(c.vault.pop()!);
    runAction(c, 'card:Shadowhunter', automation, automation.conditionalActions![1], 'Shadowhunter');
    expect(derivedStats(c).evasion).toBe(baseline);
  });

  it('offers Voice of Reason damage Proficiency only while Stress is full', () => {
    const c = emptyCharacter('Test');
    c.stress.slots = 6;
    c.loadout = [{ name: 'Voice of Reason', domain: 'Splendor', level: 3, type: 'Ability', recall: 1, text: '', automation: rules('voice-of-reason') }];
    expect(availableRollOptions(c, ['action'])[0].option.advantage).toBe('advantage');
    c.stress.on = 5;
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    c.stress.on = 6;
    const selected = availableRollOptions(c, ['damage']);
    expect(selected[0].option).toMatchObject({ proficiency: 1, defaultOn: true });
    expect(selectionBlocker(c, selected)).toBeNull();
    c.stress.on = 5;
    expect(selectionBlocker(c, selected)).toContain('no longer meets');
  });

  it('offers Dread-Touched Fear prevention independently of its rest use', () => {
    const c = emptyCharacter('Test');
    const automation = rules('dread-touched');
    const card = (name: string): DomainCard => ({ name, domain: 'Dread', level: 1, type: 'Ability', recall: 0, text: '' });
    c.stress.slots = 6;
    c.loadout = [{ ...card('Dread-Touched'), automation }, card('two'), card('three')];
    c.vault = [card('four')];
    const roll = { source: 'action', hope: 2, fear: 8, total: 10 };
    expect(availableRollTriggers(c, roll)).toHaveLength(0);
    c.loadout.push(c.vault.pop()!);
    c.usage['card:Dread-Touched'] = { used: 1, per: 'rest' };
    expect(availableRollTriggers(c, { ...roll, difficulty: 11 })).toHaveLength(0);
    expect(availableRollTriggers(c, { ...roll, hope: 8, fear: 2 })).toHaveLength(0);
    const trigger = availableRollTriggers(c, roll)[0];
    expect(trigger.trigger.cost?.stress).toBe(2);
    expect(actionBlocker(c, trigger.key, trigger.automation, trigger.trigger)).toBeNull();
    expect(availableRollTriggers(c, { ...roll, difficulty: 10 })).toHaveLength(1);
  });

  it('restricts Body Basher and Cruel Precision to weapon damage and current traits', () => {
    const c = emptyCharacter('Test');
    c.loadout = ['body-basher', 'cruel-precision'].map(id => {
      const entry = catalog.entries.find(item => item.id === id)!;
      return { name: entry.name, domain: entry.domain!, level: 1, type: 'Ability', recall: 0, text: '', automation: rules(id) };
    });
    c.traits.find(t => t.name === 'Strength')!.mod = 2;
    c.effects.push({ id: 'strong', name: 'Strength buff', kind: 'buff', modifiers: [{ stat: 'trait.Strength', value: 1 }] });
    const melee = { name: 'Sword', slot: 'Primary' as const, trait: 'Strength' as const, range: 'Melee', damage: 'd8', damageType: 'phys' as const, hands: 1 as const, tier: 1 };
    const ranged = { ...melee, range: 'Far' };
    let options = availableRollOptions(c, ['damage'], melee);
    expect(selectedRollBonus(c, rollBonus(c, ['damage'], melee), [options.find(o => o.source === 'Body Basher')!]).value).toBe(3);
    expect(options.filter(o => o.source === 'Cruel Precision')).toHaveLength(1);
    expect(options.find(o => o.source === 'Cruel Precision')!.option).toMatchObject({ defaultOn: true, traitChoices: ['Finesse', 'Agility'] });
    options = availableRollOptions(c, ['damage'], ranged);
    expect(options.some(o => o.source === 'Body Basher')).toBe(false);
    expect(availableRollOptions(c, ['damage', 'spelldamage'], melee)).toHaveLength(0);
  });

  it('tracks Deadly Focus until manually ended without restoring its use', () => {
    const c = emptyCharacter('Test');
    const automation = rules('deadly-focus');
    c.loadout = [{ name: 'Deadly Focus', domain: 'Blade', level: 4, type: 'Ability', recall: 2, text: '', automation }];
    const base = derivedStats(c).proficiency;
    runAction(c, 'card:Deadly Focus', automation, automation.actions![0], 'Deadly Focus');
    expect(derivedStats(c).proficiency).toBe(base + 1);
    c.effects = [];
    expect(derivedStats(c).proficiency).toBe(base);
    expect(actionBlocker(c, 'card:Deadly Focus', automation, automation.actions![0])).toBeTruthy();
  });

  it('offers Critical Inspiration as mutually exclusive ally benefits', () => {
    const action = rules('critical-inspiration').actions![0];
    expect(action.chatBenefits).toHaveLength(2);
    expect(action.chatBenefits?.map(b => b.steps?.[0].resource)).toEqual(['stress', 'hope']);
    expect(rules('critical-inspiration').uses).toEqual({ max: 1, per: 'rest' });
  });

  it('lets exactly one ally claim Lean on Me while clearing the user’s Stress', () => {
    const c = emptyCharacter('Test');
    const automation = rules('lean-on-me');
    c.stress.slots = 6;
    c.stress.on = 3;
    const action = automation.actions![0];
    expect(action.chatBenefits?.[0]).toMatchObject({ exclusiveClaim: true, steps: [{ op: 'clear', resource: 'stress', amount: 2 }] });
    expect(action.allowSourceOwner).toBe(true);
    runAction(c, 'card:Lean on Me', automation, action, 'Lean on Me');
    expect(c.stress.on).toBe(1);
    expect(actionBlocker(c, 'card:Lean on Me', automation, action)).toBeTruthy();
  });

  it('offers Weave the Flesh base and upgraded ally benefits with one rest use', () => {
    const c = emptyCharacter('Test');
    const automation = rules('weave-the-flesh');
    c.hp.slots = 6;
    c.stress.slots = 6;
    const [base, upgrade] = automation.actions!;
    expect(base.cost).toEqual({ hp: 1 });
    expect(base.chatBenefits?.map(b => b.steps[0].resource)).toEqual(['hp', 'stress']);
    expect(upgrade.cost).toEqual({ hp: 1, stress: 1 });
    expect(upgrade.chatBenefits?.[0].steps.map(s => s.resource)).toEqual(['hp', 'stress']);
    runAction(c, 'card:Weave the Flesh', automation, base, 'Weave the Flesh');
    expect(c.hp.on).toBe(1);
    expect(actionBlocker(c, 'card:Weave the Flesh', automation, upgrade)).toBeTruthy();
  });

  it('applies Uncanny Focus and offers Pick and Pull advantage', () => {
    const c = emptyCharacter('Test');
    const focus = rules('uncanny-focus');
    c.loadout = [
      { name: 'Uncanny Focus', domain: 'Blade', level: 4, type: 'Ability', recall: 2, text: '', automation: focus },
      { name: 'Pick and Pull', domain: 'Midnight', level: 1, type: 'Ability', recall: 0, text: '', automation: rules('pick-and-pull') },
    ];
    const baseline = derivedStats(c).proficiency;
    runAction(c, 'card:Uncanny Focus', focus, focus.actions![0], 'Uncanny Focus');
    expect(derivedStats(c).proficiency).toBe(baseline + 1);
    expect(availableRollOptions(c, ['action'])[0].option.advantage).toBe('advantage');
  });

  it('charges Safe Haven when casting for a rest, not when an ally enters it', () => {
    const automation = rules('safe-haven');
    expect(automation.actions![0].cost?.hope).toBe(2);
    expect(automation.rest?.passives?.[0].extraMoves).toBe(1);
    expect(automation.rest?.passives?.[0]).toMatchObject({ castBeforeRest: 'use', cost: { hope: 2 } });
    expect(automation.actions?.[0].chatBenefits?.[0]).toMatchObject({ id: 'enter', steps: [{ op: 'effect' }] });
    expect((automation.actions?.[0].chatBenefits?.[0] as unknown as { cost?: unknown }).cost).toBeUndefined();
  });

  it('uses the printed difficulties for additional domain Spellcast rolls', () => {
    for (const [id, difficulty] of [
      ['final-words', 13], ['forest-sprites', 13], ['resurrection', 20],
      ['rift-walker', 15], ['salvation-beam', 16], ['veil-of-night', 13],
    ] as const) {
      expect(rules(id).spellcastDifficulty).toBe(difficulty);
    }
  });

  it('offers Healing Field free and upgraded party healing with one shared use', () => {
    const c = emptyCharacter('Test');
    const automation = rules('healing-field');
    const [base, upgrade] = automation.actions!;
    expect(base.cost?.hope).toBeUndefined();
    expect(base.steps?.[0]).toMatchObject({ resource: 'hp', amount: 1 });
    expect(base.chatBenefits?.[0].steps?.[0].amount).toBe(1);
    expect(upgrade.cost?.hope).toBe(2);
    expect(upgrade.steps?.[0]).toMatchObject({ resource: 'hp', amount: 2 });
    expect(upgrade.chatBenefits?.[0].steps?.[0].amount).toBe(2);
    c.hp.slots = 6;
    c.hp.on = 2;
    c.hope.on = 2;
    runAction(c, 'card:Healing Field', automation, base, 'Healing Field');
    expect(c.hp.on).toBe(1);
    expect(actionBlocker(c, 'card:Healing Field', automation, upgrade)).toBeTruthy();
    applyRest(c, 'long');
    expect(actionBlocker(c, 'card:Healing Field', automation, upgrade)).toBeNull();
  });

  it('applies Armorer and Fortified Armor only when armor is equipped', () => {
    const c = emptyCharacter('Test');
    c.activeArmor = undefined;
    c.loadout = ['armorer', 'fortified-armor'].map(id => {
      const entry = catalog.entries.find(card => card.id === id)!;
      return { name: entry.name, domain: entry.domain!, level: 1, type: 'Ability', recall: 0, text: '', automation: entry.automation };
    });
    expect(statSources(c, 'armorScore')).toHaveLength(0);
    expect(statSources(c, 'thresholds')).toHaveLength(0);
    c.activeArmor = { name: 'Armor', score: 3, tier: 1, major: 6, severe: 12 };
    expect(derivedStats(c)).toMatchObject({ armorSlots: 4, major: 6 + c.level + 2, severe: 12 + c.level + 2 });
    c.activeArmor = undefined;
    expect(statSources(c, 'armorScore')).toHaveLength(0);
    expect(statSources(c, 'thresholds')).toHaveLength(0);
  });

  it('gates Bone and Splendor benefits on active loadout, including when a dialog is open', () => {
    const c = emptyCharacter('Test');
    const bone = rules('bone-touched');
    const splendor = rules('splendor-touched');
    const card = (name: string, domain: string, automation?: Automation): DomainCard => ({ name, domain, level: 1, type: 'Ability', recall: 0, text: '', automation });
    c.hope.on = 6;
    c.loadout = [card('Bone-Touched', 'Bone', bone), card('B1', 'Bone'), card('B2', 'Bone'), card('Splendor-Touched', 'Splendor', splendor), card('S1', 'Splendor'), card('S2', 'Splendor')];
    c.vault = [card('vault bone', 'Bone'), card('vault splendor', 'Splendor')];
    const action = bone.conditionalActions![0];
    expect(actionBlocker(c, 'card:Bone-Touched', bone, action)).toBeTruthy();
    expect(availableMitigations(c).every(option => !!mitigationBlocker(c, option, 'physical'))).toBe(true);
    c.loadout.push(card('B3', 'bone'), card('S3', 'splendor'));
    expect(actionBlocker(c, 'card:Bone-Touched', bone, action)).toBeNull();
    const options = availableMitigations(c);
    expect(options).toHaveLength(2);
    c.vault.push(c.loadout.pop()!);
    expect(mitigationBlocker(c, options[0], 'physical')).toBeTruthy();
  });

  it.each([
    ['codex-touched', 'Codex', 'spellcast', 'proficiency'],
    ['sage-touched', 'Sage', 'trait:Agility', 'agility'],
    ['dread-touched', 'Dread', 'action', 'fear'],
  ] as const)('offers %s roll options only with four active matching cards', (id, domain, target, optionId) => {
    const c = emptyCharacter('Test');
    const entry = catalog.entries.find(card => card.id === id)!;
    const card = (name: string, value: string): DomainCard => ({ name, domain: value, level: 1, type: 'Ability', recall: 0, text: '' });
    c.stress.slots = 6;
    c.traits.find(t => t.name === 'Agility')!.mod = 2;
    c.features.push({ name: 'Bonus Agility', source: 'Test', text: '', automation: { modifiers: [{ stat: 'trait.Agility', value: 1 }, { stat: 'proficiency', value: 1 }] } });
    c.loadout = [{ ...card(entry.name, domain), automation: entry.automation }, card('two', domain), card('three', domain)];
    c.vault = [card('vault', domain)];
    expect(availableRollOptions(c, [target])).toHaveLength(0);
    c.loadout.push(card('four', domain.toLowerCase()));
    setGmFear(4);
    const option = availableRollOptions(c, [target]).find(item => item.option.id === optionId)!;
    expect(option).toBeDefined();
    const bonus = selectedRollBonus(c, rollBonus(c, []), [option]);
    expect(bonus.value).toBe(id === 'codex-touched' ? derivedStats(c).proficiency : id === 'sage-touched' ? 3 : 4);
    commitRollOptions(c, [option]);
    if (id === 'codex-touched') expect(c.stress.on).toBe(1);
    else {
      expect(actionBlocker(c, option.key, option.automation, option.option)).toBeTruthy();
      applyRest(c, 'short');
      expect(actionBlocker(c, option.key, option.automation, option.option)).toBeNull();
    }
    setGmFear(0);
  });

  it.each([
    ['arcana-touched', 'Arcana', 'spellcast', 1],
    ['blade-touched', 'Blade', 'attack', 2],
    ['bone-touched', 'Bone', 'trait.Agility', 1],
    ['splendor-touched', 'Splendor', 'severeThreshold', 3],
    ['valor-touched', 'Valor', 'armorScore', 1],
  ] as const)('gates %s passive bonuses on four active matching cards', (id, domain, stat, amount) => {
    const c = emptyCharacter('Test');
    const entry = catalog.entries.find((card) => card.id === id)!;
    const card = (name: string, cardDomain: string): DomainCard => ({
      name, domain: cardDomain, level: 1, type: 'Ability', recall: 0, text: '',
    });
    c.loadout = [{ ...card(entry.name, domain), automation: entry.automation }, card('two', domain), card('three', domain)];
    c.vault = [card('vault', domain)];
    const bonus = () => stat === 'spellcast' || stat === 'attack'
      ? rollBonus(c, [stat]).value
      : statSources(c, stat).reduce((sum, source) => sum + source.value, 0);
    expect(bonus()).toBe(0);
    c.loadout.push(card('four', domain.toLowerCase()));
    expect(bonus()).toBe(amount);
    if (stat === 'trait.Agility') expect(traitTotal(c, 'Agility')).toBe(c.traits.find(t => t.name === 'Agility')!.mod + amount);
    if (stat === 'severeThreshold') expect(derivedStats(c).severe).toBe(c.level * 2 + amount);
    c.loadout.pop();
    expect(bonus()).toBe(0);
    c.loadout.push(card('four', domain));
    c.vault.push(c.loadout.shift()!);
    c.loadout.push(card('replacement', domain));
    expect(bonus()).toBe(0);
  });

  it.each([
    ['deft-deceiver', 'Deft Deceiver', 'action', 'hope'],
    ['reckless', 'Reckless', 'attack', 'stress'],
  ] as const)('applies %s advantage and pays only when the roll option is committed', (id, name, target, resource) => {
    const c = emptyCharacter('Test');
    c.hope.on = 2;
    c.stress.slots = 6;
    c.loadout = [{ name, domain: 'Blade', level: 1, type: 'Ability', recall: 0, text: '', automation: rules(id) }];
    const options = availableRollOptions(c, [target]);
    expect(options).toHaveLength(1);
    expect(suggestedAdvantage(options)).toBe(1);
    expect(resource === 'hope' ? c.hope.on : c.stress.on).toBe(resource === 'hope' ? 2 : 0);
    commitRollOptions(c, options);
    expect(resource === 'hope' ? c.hope.on : c.stress.on).toBe(1);
  });

  it.each([
    ['deft-maneuvers', 'stress', 1],
    ['divination', 'hope', 3],
    ['smite', 'hope', 3],
    ['summon-horror', 'stress', 1],
    ['transcendent-union', 'hope', 5],
    ['wild-surge', 'stress', 1],
  ] as const)('charges the stated %s action cost', (id, resource, amount) => {
    const c = emptyCharacter('Test');
    c.hope.on = 6;
    c.stress.slots = 6;
    const automation = rules(id);
    const action = automation.actions![0];
    expect(action.cost).toEqual({ [resource]: amount });
    expect(runAction(c, `card:${id}`, automation, action, id)).not.toBeNull();
    expect(resource === 'hope' ? c.hope.on : c.stress.on).toBe(resource === 'hope' ? 6 - amount : amount);
  });

  it('charges Reaper’s Strike on the selected attack option', () => {
    const c = emptyCharacter('Test');
    c.hope.on = 2;
    c.loadout = [{ name: 'Reaper’s Strike', domain: 'Blade', level: 9, type: 'Ability', recall: 3, text: '', automation: rules('reapers-strike') }];
    const option = availableRollOptions(c, ['attack'])[0];
    expect(option.option.cost).toEqual({ hope: 1 });
    commitRollOptions(c, [option]);
    expect(c.hope.on).toBe(1);
    expect(availableRollOptions(c, ['attack']).length).toBe(1);
    expect(selectionBlocker(c, [option])).not.toBeNull();
  });

  it('maximizes exactly one die while preserving mixed dice and keep-highest pools', () => {
    expect(maximizableDamageDice('2d8kh1+1d6+3')).toEqual([{ index: 0, sides: 8 }, { index: 1, sides: 8 }, { index: 2, sides: 6 }]);
    expect(maximizeDamageDie('2d8kh1+1d6+3', 1)).toBe('1d8kh0+8+1d6+3');
    expect(maximizeDamageDie('2d8kh1+1d6+3', 2)).toBe('2d8kh1+6+3');
    expect(() => maximizeDamageDie('1d8-1d6', 1)).toThrow();
  });

  it('limits Pick and Pull to non-attack action rolls and Eclipse to a successful cast', () => {
    const c = emptyCharacter('Test');
    c.loadout = ['pick-and-pull', 'eclipse'].map(id => ({
      name: id === 'eclipse' ? 'Eclipse' : 'Pick and Pull', domain: 'Midnight', level: 1,
      type: id === 'eclipse' ? 'Spell' : 'Ability', recall: 0, text: '', automation: rules(id),
    }));
    expect(availableRollOptions(c, ['action', 'trait:Finesse']).map(o => o.source)).toContain('Pick and Pull');
    expect(availableRollOptions(c, ['action', 'attack', 'trait:Finesse']).map(o => o.source)).not.toContain('Pick and Pull');
    const context = { source: 'action,spellcast,card:Eclipse', hope: 5, fear: 4, total: 15 };
    expect(availableRollTriggers(c, context).map(t => t.source)).not.toContain('Eclipse');
    expect(availableRollTriggers(c, { ...context, total: 16 }).map(t => t.source)).toContain('Eclipse');
  });

  it('shares Mending Touch’s upgrade limit without limiting its base healing', () => {
    const c = emptyCharacter('Test');
    const automation = rules('mending-touch');
    const key = 'card:Mending Touch';
    c.loadout = [{ name: 'Mending Touch', domain: 'Splendor', level: 1, type: 'Spell', recall: 1, text: '', automation }];
    c.hope.on = 6;
    c.hp.on = 4;
    c.stress.on = 3;
    const [hp, stress, greaterHp, greaterStress] = automation.actions!;
    expect([hp, stress, greaterHp, greaterStress].map(a => a.target)).toEqual(['actor', 'actor', 'actor', 'actor']);
    expect(runAction(c, key, automation, greaterHp, 'Mending Touch')).not.toBeNull();
    expect(actionBlocker(c, key, automation, greaterStress)).toBeTruthy();
    expect(actionBlocker(c, key, automation, hp)).toBeNull();
    expect(runAction(c, key, automation, stress, 'Mending Touch')).not.toBeNull();
    expect(actionBlocker(c, key, automation, greaterStress)).toBeTruthy();
    applyRest(c, 'long');
    expect(actionBlocker(c, key, automation, greaterStress)).toBeNull();
  });

  it('does not impose a generic per-rest limit on Banish', () => {
    const automation = rules('banish');
    expect(automation.uses).toBeUndefined();
    expect(automation.conditionalRollTriggers?.[0]).toMatchObject({ on: 'card:Banish', banishDifficulty: true });
  });

  it.each([
    ['dark-army', 'Dark Army', 'Dread', 'fiends', 'long'],
    ['thorn-skin', 'Thorn Skin', 'Sage', 'thorns', 'short'],
  ] as const)('limits %s activation independently and clears tokens on rest', (id, name, domain, resource, reset) => {
    const c = emptyCharacter('Test');
    const automation = rules(id);
    c.loadout = [{ name, domain, level: 8, type: 'Spell', recall: 0, text: '', automation }];
    c.hope.on = 6;
    const cast = automation.actions![0];
    const key = `card:${name}`;
    expect(runAction(c, key, automation, cast, name)).not.toBeNull();
    expect(actionBlocker(c, key, automation, cast)).toBeTruthy();
    expect(actionBlocker(c, key, automation, automation.actions!.find(action => action.id === 'clear')!)).toBeNull();
    c.resources![resource] = 2;
    applyRest(c, 'short');
    expect(c.resources![resource]).toBe(0);
    if (reset === 'long') {
      expect(actionBlocker(c, key, automation, cast)).toBeTruthy();
      applyRest(c, 'long');
    }
    expect(actionBlocker(c, key, automation, cast)).toBeNull();
    c.resources![resource] = 2;
    c.vault.push(c.loadout.pop()!);
    applyRest(c, 'short');
    expect(c.resources![resource]).toBe(0);
  });

  it('leaves A Soldier’s Bond manual and offers Battle Cry benefits without affecting the source', () => {
    expect(catalog.entries.find((card) => card.id === 'a-soldiers-bond')?.automation).toBeUndefined();
    const automation = rules('battle-cry');
    const action = automation.actions![0];
    expect(automation.uses).toEqual({ max: 1, per: 'longRest' });
    expect(action.steps).toBeUndefined();
    expect(action.chatBenefits).toEqual([{
      id: 'battle-cry',
      label: 'If you can hear the cry: clear 1 Stress and gain 1 Hope',
      steps: [
        { op: 'clear', resource: 'stress', amount: 1 },
        { op: 'gain', resource: 'hope', amount: 1 },
      ],
    }]);
  });

  it.each([
    ['astral-projection', undefined, 'stress'],
    ['bloodbath', undefined, 'hope'],
    ['book-of-vyola', 'Shared Clarity', 'hope'],
  ] as const)('pays and checks the %s action cost', (id, spell, resource) => {
    const c = emptyCharacter('Test');
    c.stress.slots = 6;
    c.hope.on = 2;
    c.traits.find(trait => trait.name === 'Knowledge')!.spellcast = true;
    const automation = rules(id, spell);
    const action = automation.actions![0];
    const key = `card:${id}${spell ? `/${spell}` : ''}`;
    expect(action.cost).toEqual({ [resource]: 1 });
    expect(runAction(c, key, automation, action, id)).not.toBeNull();
    expect(resource === 'stress' ? c.stress.on : c.hope.on).toBe(1);
    applyRest(c, 'long');
    if (resource === 'stress') c.stress.on = c.stress.slots;
    else c.hope.on = 0;
    expect(actionBlocker(c, key, automation, action)).toBeTruthy();
  });

  it('limits condition avoidance independently of the Presence bonus and resets at rest', () => {
    const c = emptyCharacter('Test');
    c.hope.on = 2;
    const automation = rules('bold-presence');
    const key = 'card:Bold Presence';
    const action = automation.actions![0];
    c.loadout = [{ name: 'Bold Presence', domain: 'Valor', level: 2, type: 'Ability', recall: 0, text: '', automation }];
    expect(runAction(c, key, automation, action, 'Bold Presence')).not.toBeNull();
    expect(actionBlocker(c, key, automation, action)).toBeTruthy();
    expect(availableRollOptions(c, ['trait:Presence']).some((option) => option.source === 'Bold Presence')).toBe(true);
    applyRest(c, 'short');
    expect(actionBlocker(c, key, automation, action)).toBeNull();
  });

  it('keeps comprehensive nested spell automation and targets one actor for protective buffs', () => {
    expect(rules('book-of-exota', 'Repudiate')).toMatchObject({
      uses: { max: 1, per: 'rest' },
      actions: [{ id: 'reaction', usesSource: false, actionRoll: { trait: 'spellcast', reaction: true } }],
      conditionalRollTriggers: [{ id: 'stop', on: 'featureAction:card:Book of Exota/Repudiate:reaction' }],
    });
    expect(rules('book-of-exota', 'Create Construct').actions?.map(action => action.id)).toEqual(['summon', 'command', 'end']);
    expect(rules('book-of-korvax', 'Recant').actions?.[0]).toMatchObject({
      cost: { hope: 1 }, report: { text: expect.stringContaining('Reaction (15)') },
    });
    expect(rules('book-of-ronin', 'Eternal Enervation')).toMatchObject({
      uses: { max: 1, per: 'longRest' },
      actions: [{ id: 'cast', actionRoll: { trait: 'spellcast' } }],
    });

    expect(rules('book-of-ava', 'Tava’s Armor').actions?.[0]).toMatchObject({
      target: 'anyActor',
      cost: { hope: 1 },
      steps: [{ op: 'effect', effect: { modifiers: [{ stat: 'armorScore', value: 1 }] } }],
      report: { text: expect.stringContaining('+1 Armor Score') },
    });
    expect(rules('book-of-sitil', 'Parallela').actions?.[0]).toMatchObject({
      target: 'anyActor',
      cost: { hope: 2 },
      steps: [{ op: 'effect', effect: { rollOptions: [{
        roll: 'attack', defaultOn: true, endEffect: 'Parallela',
        description: expect.stringContaining('no numerical attack bonus'),
      }] } }],
      report: { text: expect.stringContaining('one additional target') },
    });
    expect(rules('shield-aura').actions?.[0]).toMatchObject({
      target: 'anyActor',
      cost: { stress: 1 },
      steps: [{ op: 'effect', effect: {
        exclusiveSource: true,
        text: expect.stringContaining('one additional threshold'),
        mitigation: [{ armorSeverity: 1, endEffectIfNoHp: 'Shield Aura' }],
      } }],
    });
    expect(rules('book-of-yarrow', 'Magic Immunity').actions?.[0]).toMatchObject({
      cost: { hope: 5 },
      steps: [{ op: 'effect', effect: {
        until: 'rest',
        text: 'Immune to magic damage until your next rest.',
        mitigation: [{ passive: true, when: { type: 'magic' }, negate: true }],
      } }],
    });
  });

  it('configures successful spell results, rest uses, and table-facing reports', () => {
    expect(rules('book-of-ava', 'Ice Spike').conditionalRollTriggers?.[0]).toMatchObject({
      on: 'card:Ice Spike', successIfKnown: true, damage: { dice: 'd6', type: 'physical' },
    });

    const rune = rules('book-of-korvax', 'Rune Circle').actions!;
    expect(rune[0]).toMatchObject({ cost: { stress: 1 }, report: { text: expect.stringContaining('circle is active') } });
    expect(rune[0].damage).toBeUndefined();
    expect(rune[1]).toMatchObject({ damage: { dice: '2d12+4', fixed: true, type: 'magic' }, report: { text: expect.stringContaining('knocked back') } });

    const flame = rules('book-of-grynn', 'Wall of Flame');
    expect(flame.actions?.[0].damage).toMatchObject({ dice: '4d10+3', fixed: true, type: 'magic' });
    expect(flame.conditionalRollTriggers?.[0]).toMatchObject({
      id: 'damage', on: 'card:Wall of Flame', successIfKnown: true,
      damage: { dice: '4d10+3', fixed: true, type: 'magic' },
    });

    const manifest = rules('manifest-wall');
    expect(manifest.actions).toBeUndefined();
    expect(manifest.uses).toEqual({ max: 1, per: 'rest' });
    expect(manifest.conditionalRollTriggers?.[0]).toMatchObject({
      on: 'card:Manifest Wall', successIfKnown: true, cost: { hope: 1 },
      report: { text: expect.stringContaining('create the wall') },
    });

    const octarine = rules('octarine-wall');
    expect(octarine.actions?.[0].usesSource).toBe(false);
    expect(octarine.actions?.[0].damage).toMatchObject({ dice: 'd12', type: 'magic' });
    expect(octarine.conditionalRollTriggers?.[0]).toMatchObject({
      id: 'create', on: 'card:Octarine Wall', successIfKnown: true, giveFear: 1,
      chatDamage: { dice: 'd12', type: 'magic' },
      report: { text: expect.stringContaining('1 Fear') },
    });

    const teleport = rules('teleport');
    expect(teleport).toMatchObject({ automaticRestUse: true, uses: { max: 1, per: 'longRest' } });
    expect(teleport.actions).toBeUndefined();
  });

  it('keeps Grace-Touched passive-only and reports automatic Inspiration pool benefits', () => {
    const grace = rules('grace-touched');
    expect(grace.conditionalActions).toBeUndefined();
    expect(grace.conditionalRollTriggers?.[0]).toMatchObject({
      on: 'damage', loadoutDomain: { domain: 'Grace', min: 4 },
    });

    const inspiration = rules('inspirational-words');
    expect(inspiration.resources?.[0]).toMatchObject({ resetOn: 'longRest', resetValue: '@total_presence' });
    expect(inspiration.actions?.map(action => action.id)).toEqual(['give']);
    expect(inspiration.actions?.[0].chatBenefits?.map(benefit => benefit.report?.text)).toEqual([
      'The ally clears 1 Stress.',
      'The ally clears 1 Hit Point.',
      'The ally gains 1 Hope.',
    ]);
  });

  it('configures rest-limited and marked-HP card results without generic buttons', () => {
    const troublemaker = rules('troublemaker');
    expect(troublemaker.uses).toEqual({ max: 1, per: 'rest' });
    expect(troublemaker.actions?.[0]).toMatchObject({
      usesSource: false, actionRoll: { trait: 'Presence' },
    });
    expect(troublemaker.conditionalRollTriggers?.[0]).toMatchObject({
      id: 'stress', on: 'featureAction:card:Troublemaker:use', successIfKnown: true,
      sendRoll: { dice: '@total_proficiencyd4kh1' },
      report: { text: expect.stringContaining('{result}') },
    });

    const deflection = rules('book-of-grynn', 'Arcane Deflection');
    expect(deflection.uses).toEqual({ max: 1, per: 'longRest' });
    expect(deflection.mitigation?.[0]).toMatchObject({
      uses: 'source', cost: { hope: 1 }, negate: true,
    });

    const wave = rules('disintegration-wave');
    expect(wave.uses).toEqual({ max: 1, per: 'longRest' });
    expect(wave.automaticRestUse).toBe(true);
    expect(wave.actions).toBeUndefined();
    expect(wave.conditionalRollTriggers?.[0]).toMatchObject({
      on: 'card:Disintegration Wave', successIfKnown: true,
      repeatable: true, usesSource: false, cost: { stress: 1 },
      report: { text: expect.stringContaining('repeat this option once per target') },
    });

    const charisma = rules('endless-charisma').conditionalRollTriggers!;
    expect(charisma).toMatchObject([
      { id: 'hope', on: 'trait', excludeRolls: ['reaction', 'attack'], cost: { hope: 1 }, rerollDualityDie: 'hope' },
      { id: 'fear', on: 'trait', excludeRolls: ['reaction', 'attack'], cost: { hope: 1 }, rerollDualityDie: 'fear' },
    ]);

    const upstaged = rules('never-upstaged');
    expect(upstaged.resources?.[0]).toMatchObject({ resetOn: 'rest' });
    expect(upstaged.mitigation?.[0]).toMatchObject({
      markedHpOnly: true, cost: { stress: 1 }, gainTokens: { resource: 'upstaged', perHp: 1 },
    });
    expect(upstaged.rollOptions?.[0]).toMatchObject({
      defaultOn: true, resourceMultiplier: { resource: 'upstaged', value: 5 },
      steps: [{ op: 'clear', resource: 'upstaged', amount: 'all' }],
    });
  });
});
