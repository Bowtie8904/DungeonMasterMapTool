import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, type DomainCard } from './data';
import { actionBlocker, applyRest, availableRollOptions, availableRollTriggers, runAction, type RollContext } from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
function caster(id: string) {
  const c = emptyCharacter('Caster');
  c.hp = { on: 0, slots: 6, total: 6 };
  c.stress = { on: 0, slots: 6, total: 6 };
  c.hope.on = 6;
  c.traits.find(trait => trait.name === 'Knowledge')!.spellcast = true;
  const entry = catalog.entries.find(card => card.id === id)!;
  const card: DomainCard = { name: entry.name, domain: 'Blood', level: entry.level!, type: 'Spell',
    recall: entry.recall!, text: entry.text!, automation: entry.automation };
  c.loadout.push(card);
  return { c, a: card.automation!, key: `card:${card.name}` };
}
const result = (source: string, extra: Partial<RollContext> = {}): RollContext =>
  ({ source, hope: 9, fear: 3, total: 15, difficulty: 10, ...extra });

describe('Blood domain feedback', () => {
  it('creates Lifeblood through a host-owned enchanted-item action, not an unlinked buff', () => {
    const { a } = caster('lifeblood-talisman');
    expect(a.actions?.[0]).toMatchObject({ witchAction: 'lifeblood', bloodAutomation: true, cost: { hp: 1 } });
  });

  it('offers Brand only on damage and Parasite only after a successful cast', () => {
    const { c, a } = caster('brand-of-castigation');
    expect(a.actions).toBeUndefined();
    expect(availableRollTriggers(c, result('damage', { kind: 'damage' }))[0].trigger.cost).toEqual({ stress: 1 });
    expect(availableRollTriggers(c, result('spellcast'))).toHaveLength(0);
    const parasite = caster('parasite-of-the-will');
    expect(parasite.a.actions?.some(action => action.cost?.hp)).toBe(false);
    expect(availableRollTriggers(parasite.c, result(parasite.key))).toHaveLength(1);
    expect(availableRollTriggers(parasite.c, result(parasite.key, { total: 2 }))).toHaveLength(0);
  });

  it.each([
    [{ hope: 9, fear: 3 }, 2], [{ hope: 3, fear: 9 }, 1], [{ hope: 5, fear: 5 }, 2],
  ] as const)('reports outcome-dependent Vitality Stress with one exclusive payment: %j', (dice, amount) => {
    const { c, key } = caster('vitality-manipulation');
    const options = availableRollOptions(c, [key, 'spellcast', 'action']);
    expect(options[0].option.advantage).toBe('advantage');
    const triggers = availableRollTriggers(c, result(key, dice));
    expect(triggers).toHaveLength(2);
    for (const { trigger } of triggers) {
      expect(trigger.cost).toEqual({ stress: 1 });
      expect(trigger.exclusiveRollGroup).toBe('vitality');
      expect(trigger.report?.text).toContain(`${amount} Stress`);
    }
    expect(availableRollTriggers(c, result(key, { hope: 3, fear: 9, total: 2 }))).toHaveLength(0);
  });

  it('defers Mutual Suffering use until its success action and restores it on rest', () => {
    const { c, a, key } = caster('mutual-suffering');
    const action = a.actions![0];
    expect(action.actionRoll).toEqual({ trait: 'spellcast', reaction: true });
    expect(runAction(c, key, a, action, 'Mutual Suffering')).not.toBeNull();
    expect(c.usage[key]).toBeUndefined();
    const trigger = availableRollTriggers(c, result(`featureAction:${key}:react,reaction,spellcast`))[0].trigger;
    expect(runAction(c, key, a, trigger, 'Mutual Suffering')).not.toBeNull();
    expect(c.usage[key].used).toBe(1);
    expect(actionBlocker(c, key, a, action)).toBeTruthy();
    applyRest(c, 'short');
    expect(actionBlocker(c, key, a, action)).toBeNull();
  });

  it('combines Bloodbath payment and Spellcast and configures a paid ally recovery', () => {
    const { a } = caster('bloodbath');
    expect(a.actions).toHaveLength(1);
    expect(a.actions![0]).toMatchObject({ hostRollAction: true, actionRoll: { trait: 'spellcast' }, cost: { hope: 1 },
      chatBenefits: [{ cost: { stress: 1 }, steps: [{ op: 'clear', resource: 'hp', amount: 1 }] }] });
  });

  it('gates glyph activation on success and removes its buff and action on Severe damage', () => {
    const { c, a, key } = caster('glyph-of-hemorrhaging');
    expect(a.actions).toBeUndefined();
    const trigger = availableRollTriggers(c, result(key))[0].trigger;
    expect(availableRollTriggers(c, result(key, { total: 2 }))).toHaveLength(0);
    runAction(c, key, a, trigger, 'Glyph');
    expect(c.hp.on).toBe(1);
    expect(c.effects[0]).toMatchObject({ name: 'Glyph of Hemorrhaging', endOnDamage: 3,
      actions: [{ cost: { stress: 1 } }] });
    applyDamage(c, computeDamage(c, [], { damage: 100, type: 'magic', armor: false, selected: new Set() }), 'magic');
    expect(c.effects).toHaveLength(0);
  });

  it.each([1, 2, 3])('locks Sanguine Feast %i-HP payment to the same refund and double target HP', amount => {
    const { c, key } = caster('sanguine-feast');
    const triggers = availableRollTriggers(c, result(key));
    expect(triggers).toHaveLength(3);
    const trigger = triggers[amount - 1].trigger;
    expect(trigger.cost).toEqual({ hope: 2, hp: amount });
    expect(trigger.report?.text).toContain(`target marks ${amount * 2} HP`);
    expect(trigger.exclusiveRollGroup).toBe('feast');
    expect(trigger.chatBenefits?.[0]).toMatchObject({ sourceOnly: true,
      steps: [{ op: 'clear', resource: 'hp', amount }] });
  });

  it('checks Lifeblood against actual HP to mark, not severity, and charges Hope only when eligible', () => {
    for (const [damage, marked, expected] of [[1, 0, 1], [10, 0, 1], [20, 0, 2], [20, 5, 1]]) {
      const { c } = caster('lifeblood-talisman');
      c.hp.on = marked;
      c.armorTrack.slots = 0;
      c.effects.push({ id: 'bearer', name: 'Lifeblood Talisman benefits', kind: 'buff',
        mitigation: [{ id: 'protect', label: 'Lifeblood Talisman', markedHpOnly: true, hp: -1,
          when: { minMarkedHp: 2 }, cost: { hope: 1 } }] });
      c.activeArmor = { name: 'Test', score: 0, major: 10, severe: 20, tier: 1 };
      c.level = 0;
      const options = availableMitigations(c);
      const computed = computeDamage(c, options, { damage, type: 'physical', armor: false, selected: new Set(options.map(o => o.id)) });
      expect(computed.hp).toBe(expected);
      expect(computed.costs.hope ?? 0).toBe(computed.hpBeforeReplace >= 2 ? 1 : 0);
    }
  });
});
