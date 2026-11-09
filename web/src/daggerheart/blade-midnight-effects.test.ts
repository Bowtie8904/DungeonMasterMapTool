import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, type CharacterSheet, type DomainCard } from './data';
import { actionBlocker, applyRest, availableRollOptions, commitRollOptions, configuredRollOption, derivedStats, rollOptionQuantityMax, runAction, selectedRollBonus, rollBonus, type Automation } from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));

function load(c: CharacterSheet, id: string): Automation {
  const entry = catalog.entries.find(card => card.id === id)!;
  const card: DomainCard = { name: entry.name, domain: entry.domain!, level: entry.level!,
    type: entry.cardType as DomainCard['type'], recall: entry.recall!, text: entry.text!, automation: entry.automation };
  c.loadout.push(card);
  return card.automation!;
}

describe('Blade and Midnight effect corrections', () => {
  it('offers scrutiny advantage only on non-attack Presence rolls while still spending attack tokens', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'uncanny-disguise');
    c.traits[0].spellcast = true;
    c.traits[0].mod = 2;
    runAction(c, 'card:Uncanny Disguise', a, a.actions![0], 'Uncanny Disguise');
    expect(availableRollOptions(c, ['action', 'trait:Presence']).some(s => s.option.id === 'scrutiny')).toBe(true);
    const attackOptions = availableRollOptions(c, ['action', 'attack', 'trait:Presence']);
    expect(attackOptions.some(s => s.option.id === 'scrutiny')).toBe(false);
    expect(attackOptions.some(s => s.option.id === 'act')).toBe(true);
  });

  it('scales the single Rage Up option from one to two without increasing the attack total', () => {
    const c = emptyCharacter('Test');
    load(c, 'rage-up');
    c.stress.slots = 6;
    c.traits.find(t => t.name === 'Strength')!.mod = 3;
    const options = availableRollOptions(c, ['action', 'attack']);
    expect(options).toHaveLength(1);
    expect(rollOptionQuantityMax(c, options[0].option)).toBe(2);
    const selected = configuredRollOption(c, options[0], 2);
    expect(selected.option).toMatchObject({ cost: { stress: 2 }, linkedDamageMultiplier: 4, value: 0 });
    expect(selectedRollBonus(c, rollBonus(c, ['action', 'attack']), [selected]).value).toBe(0);
    expect(() => configuredRollOption(c, options[0], 3)).toThrow('from 1 to 2');
    expect(() => configuredRollOption(c, options[0], 1.5)).toThrow('whole number');
    commitRollOptions(c, [selected], false, ['action', 'attack']);
    expect(c.stress.on).toBe(2);
    c.stress.on = 5;
    expect(rollOptionQuantityMax(c, options[0].option)).toBe(1);
    expect(() => configuredRollOption(c, options[0], 2)).toThrow('from 1 to 1');
  });

  it('replaces disguise tokens with the current Spellcast trait and spends them only on action rolls', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'uncanny-disguise');
    c.traits.find(trait => trait.name === 'Presence')!.spellcast = true;
    c.traits.find(trait => trait.name === 'Presence')!.mod = 1;
    c.effects.push({ id: 'presence', name: 'Presence bonus', kind: 'buff', modifiers: [{ stat: 'trait.Presence', value: 1 }] });
    c.resources = { disguise: 9 };
    expect(a.actions).toHaveLength(1);
    runAction(c, 'card:Uncanny Disguise', a, a.actions![0], 'Uncanny Disguise');
    expect(c.stress.on).toBe(1);
    expect(c.resources.disguise).toBe(2);
    expect(c.effects.filter(effect => effect.name === 'Uncanny Disguise')).toHaveLength(1);
    expect(availableRollOptions(c, ['reaction', 'trait:Presence'])).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    c.vault.push(c.loadout.pop()!);
    for (let remaining = 1; remaining >= 0; remaining--) {
      const selected = availableRollOptions(c, ['action', 'attack']).filter(option => option.option.id === 'act');
      expect(selected).toHaveLength(1);
      expect(selected[0].option.defaultOn).toBe(true);
      commitRollOptions(c, selected, false, ['action', 'attack']);
      expect(c.resources.disguise).toBe(remaining);
    }
    expect(c.effects.some(effect => effect.name === 'Uncanny Disguise')).toBe(false);
  });

  it('does not stack a recast disguise and does not leave a zero-token disguise active', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'uncanny-disguise');
    c.traits[0].spellcast = true;
    c.traits[0].mod = 2;
    runAction(c, 'card:Uncanny Disguise', a, a.actions![0], 'Uncanny Disguise');
    runAction(c, 'card:Uncanny Disguise', a, a.actions![0], 'Uncanny Disguise');
    expect(c.resources!.disguise).toBe(2);
    expect(c.effects.filter(effect => effect.name === 'Uncanny Disguise')).toHaveLength(1);
    c.traits[0].mod = 0;
    runAction(c, 'card:Uncanny Disguise', a, a.actions![0], 'Uncanny Disguise');
    expect(c.resources!.disguise).toBe(0);
    expect(c.effects.some(effect => effect.name === 'Uncanny Disguise')).toBe(false);
  });

  it.each(['deadly-focus', 'uncanny-focus'])('marks %s for scene expiry without restoring its rest use', id => {
    const c = emptyCharacter('Test');
    const a = load(c, id);
    const name = c.loadout[0].name;
    const base = derivedStats(c).proficiency;
    runAction(c, `card:${name}`, a, a.actions![0], name);
    expect(derivedStats(c).proficiency).toBe(base + 1);
    expect(c.effects.find(effect => effect.name === name)?.until).toBe('scene');
    c.effects = c.effects.filter(effect => effect.until !== 'scene');
    expect(derivedStats(c).proficiency).toBe(base);
    expect(actionBlocker(c, `card:${name}`, a, a.actions![0])).toBeTruthy();
  });

  it.each(['short', 'long'] as const)('toggles Phantom Retreat and removes it on a %s rest', kind => {
    const c = emptyCharacter('Test');
    const a = load(c, 'phantom-retreat');
    c.hope.on = 6;
    const use = () => runAction(c, 'card:Phantom Retreat', a, a.actions![0], 'Phantom Retreat');
    use();
    expect(c.effects.find(effect => effect.name === 'Phantom Retreat')?.until).toBe('rest');
    use();
    expect(c.effects).toHaveLength(0);
    expect(c.hope.on).toBe(4);
    use();
    applyRest(c, kind);
    expect(c.effects).toHaveLength(0);
  });

  it('links Dark Whispers Stress to its only Spellcast action', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'dark-whispers');
    expect(a.actions).toHaveLength(1);
    expect(a.actions![0]).toMatchObject({ actionRoll: { trait: 'spellcast' }, cost: { stress: 1 } });
  });

  it('applies manually removed linked Hidden through Vanishing Dodge', () => {
    const c = emptyCharacter('Test');
    const a = load(c, 'vanishing-dodge');
    c.hope.on = 2;
    runAction(c, 'card:Vanishing Dodge', a, a.actions![0], 'Vanishing Dodge');
    expect(c.hope.on).toBe(1);
    expect(c.effects[0]).toMatchObject({ name: 'Hidden', kind: 'buff', until: 'manual',
      onlineCondition: { id: 'hidden', iconId: 'cond_invisible' } });
  });

  it('automatically gains Spellcharge only on committed magic HP and reports the capped gain', () => {
    const c = emptyCharacter('Test');
    load(c, 'spellcharge');
    c.traits[0].spellcast = true;
    c.traits[0].mod = 2;
    c.hp.slots = 6;
    const options = availableMitigations(c);
    const input = { damage: 100, type: 'magic' as const, armor: false, selected: new Set<string>() };
    const preview = computeDamage(c, options, input);
    expect(c.resources?.spellcharge ?? 0).toBe(0);
    expect(applyDamage(c, preview, 'magic')).toContain('Spellcharge: gained 2 Spellcharge');
    expect(c.resources!.spellcharge).toBe(2);
    const physical = computeDamage(c, options, { ...input, type: 'physical' });
    expect(applyDamage(c, physical, 'physical')).not.toContain('gained');
    expect(c.resources!.spellcharge).toBe(2);
  });

  it('does not award Spellcharge when mitigation prevents HP or the trait is negative', () => {
    const c = emptyCharacter('Test');
    load(c, 'spellcharge');
    c.traits[0].spellcast = true;
    c.traits[0].mod = -1;
    const options = availableMitigations(c);
    const input = { damage: 1, type: 'magic' as const, armor: false, selected: new Set<string>() };
    applyDamage(c, computeDamage(c, options, input), 'magic');
    expect(c.resources!.spellcharge).toBe(0);
    const negated = computeDamage(c, options, { ...input, damage: 0 });
    expect(applyDamage(c, negated, 'magic')).not.toContain('gained');
  });
});
