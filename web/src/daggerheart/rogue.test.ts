import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import {
  applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, runAction,
  selectedRollBonus, rollBonus, usesLeft, usesMax,
} from './engine';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function rogue(master = true) {
  const c = emptyCharacter('Rogue');
  c.features = catalog.entries.filter(e => e.id === 'rogue-syndicate-specialization' || master && e.id === 'rogue-syndicate-mastery')
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), text: f.text ?? '', source: e.name })));
  return c;
}

function contact(c: ReturnType<typeof rogue>) {
  const f = c.features.find(x => x.name === 'Contacts Everywhere')!;
  expect(runAction(c, `feat:${f.name}`, f.automation!, f.automation!.actions![0], f.name)).toBeTruthy();
}

describe('Rogue automation', () => {
  it('configures Dark Cloud as a difficulty 15 Spellcast roll', () => {
    const f = catalog.entries.find(e => e.id === 'rogue-nightwalker-specialization')!.features!.find(x => x.name === 'Dark Cloud')!;
    expect(f.automation?.actions?.[0].actionRoll).toEqual({ trait: 'spellcast', difficulty: 15 });
  });

  it('upgrades the existing session counter without resetting it', () => {
    const c = rogue(false);
    contact(c);
    const f = c.features[0];
    expect(usesLeft(c, 'feat:Contacts Everywhere', f.automation!)).toBe(0);
    c.features.push(rogue().features.find(x => x.name === 'Reliable Backup')!);
    expect(usesMax(c, 'feat:Contacts Everywhere', f.automation!)).toBe(3);
    expect(usesLeft(c, 'feat:Contacts Everywhere', f.automation!)).toBe(2);
    applyRest(c, 'long');
    expect(usesLeft(c, 'feat:Contacts Everywhere', f.automation!)).toBe(2);
    expect(c.effects).toHaveLength(1);
  });

  it('offers two effect-bound chat choices and preselectable damage that consumes the buff', () => {
    const c = rogue();
    contact(c);
    const triggers = availableRollTriggers(c, { source: 'action,trait:Agility', hope: 4, fear: 7, total: 11 });
    expect(triggers.map(t => t.trigger.dualityDieBonus)).toEqual([{ die: 'hope', value: 3 }, { die: 'fear', value: 3 }]);
    const [option] = availableRollOptions(c, ['damage']);
    expect(option.option.defaultOn).toBe(true);
    expect(selectedRollBonus(c, rollBonus(c, ['damage']), [option]).dice).toEqual(['2d8']);
    commitRollOptions(c, [option]);
    expect(c.effects).toHaveLength(0);
    expect(availableRollTriggers(c, { source: 'action', hope: 4, fear: 7, total: 11 })).toHaveLength(0);
  });

  it('offers the upgraded Presence option only while the buff is active and consumes it on use', () => {
    const c = rogue();
    contact(c);
    expect(availableRollOptions(c, ['trait:Agility'])).toHaveLength(0);
    const [option] = availableRollOptions(c, ['trait:Presence']);
    expect(option.option.hopeDie).toBe(20);
    commitRollOptions(c, [option]);
    expect(c.effects).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
  });

  it('spends the buff only when the shield actually reduces HP marked', () => {
    const c = rogue();
    c.hp.slots = 6;
    contact(c);
    const options = availableMitigations(c);
    const shield = options.find(m => m.source === 'Reliable Backup')!;
    const input = { damage: 1, type: 'physical' as const, armor: false, selected: new Set([shield.id]) };
    const zero = computeDamage(c, options, { ...input, damage: 0 });
    expect(zero.applied).toHaveLength(0);
    applyDamage(c, zero, 'physical');
    expect(c.effects).toHaveLength(1);
    const baseline = computeDamage(c, options, { ...input, selected: new Set() });
    const damage = computeDamage(c, options, input);
    expect(damage.hp).toBe(baseline.hp - 1);
    expect(damage.applied).toHaveLength(1);
    expect(applyDamage(c, damage, 'physical')).toBeTruthy();
    expect(c.effects).toHaveLength(0);
  });
});
