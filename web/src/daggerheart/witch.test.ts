import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import {
  actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions,
  derivedStats, effectCounterText, rollBonus, runActionDetailed, suggestedAdvantage, syncDerived, usesLeft,
} from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function witch() {
  const c = emptyCharacter('Witch');
  c.className = 'Witch';
  c.subclass = 'Witch: Moon';
  c.hp.slots = 6;
  c.stress.slots = 6;
  c.hope.on = 6;
  c.activeArmor = { name: 'Leather', score: 3, major: 5, severe: 11, tier: 1 };
  c.traits.find(t => t.name === 'Instinct')!.spellcast = true;
  c.traits.find(t => t.name === 'Instinct')!.mod = 3;
  c.features = catalog.entries.filter(e => e.id === 'witch' || e.parent === 'Witch')
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), source: e.name })));
  syncDerived(c);
  return c;
}

function feature(c: ReturnType<typeof witch>, name: string) {
  return c.features.find(f => f.name === name)!;
}

function use(c: ReturnType<typeof witch>, name: string, id = 'use', face = 1) {
  const f = feature(c, name);
  return runActionDetailed(c, `feat:${name}`, f.automation!, f.automation!.actions!.find(a => a.id === id)!, name, () => face);
}

function succeed(c: ReturnType<typeof witch>, name: string) {
  const ctx = { source: `action,spellcast,feat:${name}`, hope: 9, fear: 4, total: 16, difficulty: 13 };
  const f = feature(c, name);
  const automatic = f.automation?.rollTriggers?.find(t => t.automatic);
  const trigger = automatic ? { key: `feat:${name}`, automation: f.automation!, trigger: automatic }
    : availableRollTriggers(c, ctx).find(t => t.key === `feat:${name}`)!;
  expect(trigger).toBeDefined();
  return runActionDetailed(c, trigger.key, trigger.automation, trigger.trigger, name);
}

describe('Witch automation', () => {
  it('configures Commune dice and restores the chart on existing sheets', () => {
    const c = witch();
    const f = feature(c, 'Commune');
    expect(f.automation!.actions![0].sendRoll).toEqual({ dice: '@spellcastd6', label: 'Commune' });
    expect(f.text).toContain('psychically experience a scene');
    f.text = 'Old chartless copy';
    updateCharacterAutomation(c, [catalog]);
    expect(f.text).toContain('1-3');
    expect(use(c, 'Commune')).not.toBeNull();
    expect(use(c, 'Commune')).toBeNull();
    applyRest(c, 'short');
    expect(use(c, 'Commune')).toBeNull();
    applyRest(c, 'long');
    expect(use(c, 'Commune')).not.toBeNull();
    c.traits.find(t => t.spellcast)!.mod = 0;
    expect(actionBlocker(c, 'feat:Commune', f.automation!, f.automation!.actions![0])).toMatch(/positive Spellcast/);
  });

  it('rolls Walk Between Worlds without paying Stress or consuming its rest use, then enters only on success', () => {
    const c = witch();
    const a = feature(c, 'Walk Between Worlds').automation!;
    expect(a.actions![0].actionRoll).toEqual({ trait: 'spellcast', difficulty: 13 });
    const before = c.stress.on;
    use(c, 'Walk Between Worlds');
    expect(c.stress.on).toBe(before);
    expect(usesLeft(c, 'feat:Walk Between Worlds', a)).toBe(1);
    expect(availableRollTriggers(c, { source: 'action,feat:Walk Between Worlds', hope: 3, fear: 4, total: 7, difficulty: 13 })).toHaveLength(0);
    expect(availableRollTriggers(c, { source: 'action,feat:Other spell', hope: 10, fear: 5, total: 15, difficulty: 13 })).toHaveLength(0);
    succeed(c, 'Walk Between Worlds');
    expect(c.stress.on).toBe(before + 1);
    expect(c.resources!['walk-between-worlds']).toBe(3);
    expect(usesLeft(c, 'feat:Walk Between Worlds', a)).toBe(0);
    expect(use(c, 'Walk Between Worlds', 'spend')!.join(' ')).toContain('spent 1 Tokens');
    expect(c.resources!['walk-between-worlds']).toBe(2);
    c.effects = c.effects.filter(e => e.until !== 'scene');
    syncDerived(c);
    expect(c.resources!['walk-between-worlds']).toBe(0);
    expect(use(c, 'Walk Between Worlds', 'spend')).toBeNull();
  });

  it('spends Circle tokens without spending further rest uses and ends the source on the last token', () => {
    const c = witch();
    use(c, 'Circle of Power');
    expect(c.resources!['circle-of-power']).toBe(3);
    expect(c.effects.find(e => e.witchGrant === 'circle')).toBeDefined();
    for (let i = 0; i < 3; i++) expect(use(c, 'Circle of Power', 'spend')).not.toBeNull();
    expect(c.effects.some(e => e.witchGrant === 'circle')).toBe(false);
    expect(use(c, 'Circle of Power', 'spend')).toBeNull();
    expect(use(c, 'Circle of Power')).toBeNull();
    applyRest(c, 'short');
    expect(use(c, 'Circle of Power')).not.toBeNull();
  });

  it('suggests Vexing Malison only on attacks, without preselecting narrative advantage', () => {
    const c = witch();
    expect(availableRollOptions(c, ['trait:Instinct'])).toHaveLength(0);
    const [option] = availableRollOptions(c, ['attack']);
    expect(option.source).toBe('Vexing Malison');
    expect(option.option.defaultOn).not.toBe(true);
    expect(suggestedAdvantage([option])).toBe(1);
  });

  it('grants Glamour only on a successful Spellcast and drops it on HP unless Stress is paid', () => {
    const c = witch();
    use(c, 'Night’s Glamour', 'roll');
    expect(c.effects.some(e => e.name === "Night's Glamour")).toBe(false);
    expect(availableRollTriggers(c, { source: 'action,spellcast,feat:Night’s Glamour', hope: 9, fear: 4, total: 16, difficulty: 13 })).toHaveLength(0);
    succeed(c, 'Night’s Glamour');
    const [appearance] = availableRollOptions(c, ['action', 'trait:Presence']);
    expect(appearance.option.advantage).toBe('advantage');
    expect(appearance.option.defaultOn).not.toBe(true);
    const options = availableMitigations(c);
    const maintain = options.find(m => m.option.preserveEffect)!;
    const before = c.stress.on;
    const damage = computeDamage(c, options, { damage: 1, type: 'magic', armor: false, selected: new Set([maintain.id]) });
    applyDamage(c, damage, 'magic');
    expect(c.stress.on).toBe(before + 1);
    expect(c.effects.some(e => e.name === "Night's Glamour")).toBe(true);
    applyDamage(c, computeDamage(c, options, { damage: 1, type: 'magic', armor: false, selected: new Set() }), 'magic');
    expect(c.effects.some(e => e.name === "Night's Glamour")).toBe(false);
  });

  it('does not charge Glamour Stress when HP is not marked, and drops it on damage rolls unless maintained', () => {
    const c = witch();
    succeed(c, 'Night’s Glamour');
    const options = availableMitigations(c);
    const maintain = options.find(m => m.option.preserveEffect)!;
    const before = c.stress.on;
    applyDamage(c, computeDamage(c, options, { damage: 0, type: 'magic', armor: false, selected: new Set([maintain.id]) }), 'magic');
    expect(c.stress.on).toBe(before);
    const keep = availableRollOptions(c, ['damage']).find(o => o.option.preserveEffect)!;
    commitRollOptions(c, [keep], false, ['damage']);
    expect(c.stress.on).toBe(before + 1);
    expect(c.effects.some(e => e.name === "Night's Glamour")).toBe(true);
    commitRollOptions(c, [], false, ['damage']);
    expect(c.effects.some(e => e.name === "Night's Glamour")).toBe(false);
  });

  it('records Glamour maintenance independently of simultaneous Stress recovery', () => {
    const c = witch();
    succeed(c, 'Night’s Glamour');
    c.features.push({ name: 'Not Done Yet', source: 'Subclass', text: '',
      automation: { mitigation: [{ id: 'recover', label: 'Not Done Yet', passive: true, finalSevere: true, recoveryChoice: true }] } });
    const options = availableMitigations(c);
    const maintain = options.find(m => m.option.preserveEffect)!;
    const before = c.stress.on;
    const damage = computeDamage(c, options, { damage: derivedStats(c).severe, type: 'magic', armor: false, recovery: 'stress', selected: new Set([maintain.id]) });
    expect(damage.hp).toBe(3);
    applyDamage(c, damage, 'magic');
    expect(c.stress.on).toBe(before);
    expect(c.effects.find(e => e.name === "Night's Glamour")!.hpMaintenance).toBe(1);
  });

  it.each([1, 2, 3, 4, 5, 6])('tracks Lunar face %i with exactly its matching benefit and cycles once per rest', face => {
    const c = witch();
    const base = derivedStats(c);
    expect(use(c, 'Lunar Phases', 'advance')).toBeNull();
    expect(use(c, 'Lunar Phases', 'roll', face)!.join(' ')).toContain(`rolled ${face}`);
    expect(effectCounterText(c, 'Lunar Phases')).toContain(String(face));
    expect(use(c, 'Lunar Phases', 'roll')).toBeNull();
    expect(derivedStats(c).evasion - base.evasion).toBe(face >= 5 ? 1 : 0);
    expect(derivedStats(c).major - base.major).toBe(face === 4 ? 3 : 0);
    expect(derivedStats(c).severe - base.severe).toBe(face === 4 ? 3 : 0);
    expect(rollBonus(c, ['damage']).value).toBe(face === 2 || face === 3 ? 2 : 0);
    expect(availableMitigations(c).some(m => m.option.negate)).toBe(face === 1);
    expect(use(c, 'Lunar Phases', 'advance')).not.toBeNull();
    expect(c.hope.on).toBe(5);
    expect(c.effects.find(e => e.group === 'lunar-phases')!.counter!.value).toBe(face % 6 + 1);
    expect(c.effects.filter(e => e.group === 'lunar-phases')).toHaveLength(1);
    expect(use(c, 'Lunar Phases', 'advance')).toBeNull();
    applyRest(c, 'short');
    expect(use(c, 'Lunar Phases', 'roll')).toBeNull();
    expect(use(c, 'Lunar Phases', 'advance')).not.toBeNull();
  });

  it('negates only Minor damage for one Hope on Lunar face 1', () => {
    const c = witch();
    use(c, 'Lunar Phases', 'roll', 1);
    const options = availableMitigations(c);
    const negate = options.find(m => m.option.negate)!;
    const input = { damage: 1, type: 'physical' as const, armor: false, selected: new Set([negate.id]) };
    const minor = computeDamage(c, options, input);
    expect(minor.negated).toBe(true);
    applyDamage(c, minor, 'physical');
    expect(c.hp.on).toBe(0);
    expect(c.hope.on).toBe(5);
    const major = computeDamage(c, options, { ...input, damage: derivedStats(c).major });
    expect(major.negated).toBe(false);
    expect(major.costs.hope).toBeUndefined();
  });

  it('supports linked talisman pools in mitigation and source rest clears tokens', () => {
    const c = witch();
    c.effects.push({ id: 'talisman', name: 'Enchanted Talisman', kind: 'buff', witchLinked: 'talisman',
      resources: [{ id: 'talisman-tokens', label: 'Talisman tokens', initial: 0 }],
      mitigation: [{ id: 'protect', label: 'Talisman', hp: -1, markedHpOnly: true, when: { markingHp: true }, quantity: true, cost: { 'talisman-tokens': 1 } }] });
    c.resources!['talisman-tokens'] = 3;
    const options = availableMitigations(c);
    const protect = options.find(m => m.option.id === 'protect')!;
    const damage = computeDamage(c, options, { damage: derivedStats(c).severe, type: 'physical', armor: false, selected: new Set([protect.id]), quantities: { [protect.id]: 2 } });
    expect(damage.hp).toBe(1);
    expect(damage.error).toBeNull();
    applyDamage(c, damage, 'physical');
    expect(c.resources!['talisman-tokens']).toBe(1);
    const minor = computeDamage(c, options, { damage: 1, type: 'physical', armor: false, selected: new Set([protect.id]) });
    expect(minor.hp).toBe(0);
    expect(minor.costs['talisman-tokens']).toBe(1);
    expect(minor.applied).toContain(protect);
    const none = computeDamage(c, options, { damage: 0, type: 'physical', armor: false, selected: new Set([protect.id]) });
    expect(none.costs['talisman-tokens']).toBeUndefined();
    expect(none.applied).not.toContain(protect);
    applyDamage(c, minor, 'physical');
    expect(c.resources!['talisman-tokens']).toBe(0);
    use(c, 'Circle of Power');
    applyRest(c, 'short');
    expect(c.resources!['circle-of-power']).toBe(0);
    expect(c.effects.some(e => e.witchGrant === 'circle')).toBe(false);
  });
});
