import { expect, it } from 'vitest';
import { emptyCharacter, normalizeSheet } from './data';
import { derivedStats, statBreakdown, syncDerived } from './engine';
import { migrateVitality, removeVitality } from './vitality';

it('migrates legacy benefits once without adding HP or Stress twice, using normal effect modifiers', () => {
  const c = emptyCharacter('Hero');
  c.vitalityBenefits = ['hp', 'thresholds'];
  c.hp.slots = 7;
  const before = derivedStats(c);
  migrateVitality(c);
  migrateVitality(c);
  expect(c.effects).toHaveLength(1);
  expect(c.effects[0]).toMatchObject({ name: 'Vitality', kind: 'buff', vitality: true, manualChanges: { hp: 1 } });
  expect(c.hp.slots).toBe(7);
  expect(derivedStats(c).major).toBe(before.major + 2);
  expect(derivedStats(c).severe).toBe(before.severe + 2);
  expect(statBreakdown(c).major.some(row => row.label === 'Vitality' && row.value === 2)).toBe(true);
});

it('requires edit mode and reverses the buff but retains permanent one-time use after reload', () => {
  const c = emptyCharacter('Hero');
  const before = derivedStats(c);
  c.vitalityBenefits = ['hp', 'thresholds'];
  c.hp.slots = 7;
  c.hp.on = 7;
  migrateVitality(c);
  const effect = c.effects[0];
  removeVitality(c, effect, false);
  expect(c.effects).toHaveLength(1);
  removeVitality(c, effect, true);
  expect(c.effects).toHaveLength(0);
  expect(c.hp.slots).toBe(6);
  expect(c.hp.on).toBe(6);
  expect(c.vitalityBenefits).toEqual(['hp', 'thresholds']);
  expect(c.vitalityRemoval).toEqual({ effectId: effect.id, editMode: true });
  expect(derivedStats(c)).toMatchObject({ major: before.major, severe: before.severe });
  const restored = normalizeSheet(JSON.parse(JSON.stringify(c)), 'Hero');
  syncDerived(restored);
  expect(restored.effects).toHaveLength(0);
  expect(restored.hp.slots).toBe(6);
});
