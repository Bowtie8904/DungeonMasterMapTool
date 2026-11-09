import type { CharacterSheet } from './data';
import type { ActiveEffect } from './engine';

export function isVitalityEffect(effect: ActiveEffect): boolean {
  return effect.vitality === true;
}

/** Legacy grants already changed HP/Stress slots; only record them, never grant them again. */
export function migrateVitality(c: CharacterSheet): void {
  if (!c.vitalityBenefits?.length || c.vitalityEffectRemoved || c.effects.some(isVitalityEffect)) return;
  const manualChanges: { hp?: number; stress?: number } = {};
  const vitalityTotalChanges: { hp?: number; stress?: number } = {};
  for (const stat of ['hp', 'stress'] as const) if (c.vitalityBenefits.includes(stat)) {
    manualChanges[stat] = 1;
    if (c[stat].total === c[stat].slots) vitalityTotalChanges[stat] = 1;
  }
  c.effects.push({
    id: 'vitality-permanent', name: 'Vitality', kind: 'buff', until: 'manual', source: 'Vitality',
    vitality: true, manualChanges, vitalityTotalChanges,
    text: 'Permanent benefits: ' + c.vitalityBenefits.map(benefit => benefit === 'thresholds' ? '+2 to both damage thresholds' : `+1 ${benefit === 'hp' ? 'HP' : 'Stress'} slot`).join(', ') + '. Removable only in sheet edit mode; removal reverses these benefits.',
    modifiers: c.vitalityBenefits.includes('thresholds') ? [{ stat: 'thresholds', value: 2 }] : [],
  });
}

export function removeVitality(c: CharacterSheet, effect: ActiveEffect, editMode: boolean): void {
  if (!editMode || !isVitalityEffect(effect) || !c.effects.includes(effect)) return;
  for (const stat of ['hp', 'stress'] as const) {
    c[stat].slots = Math.max(0, c[stat].slots - (effect.manualChanges?.[stat] ?? 0));
    c[stat].on = Math.min(c[stat].on, c[stat].slots);
    c[stat].total = Math.max(c[stat].slots, c[stat].total - (effect.vitalityTotalChanges?.[stat] ?? 0));
  }
  c.effects = c.effects.filter(item => item !== effect);
  c.vitalityEffectRemoved = true;
  c.vitalityRemoval = { effectId: effect.id, editMode: true };
}
