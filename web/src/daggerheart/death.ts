import type { CharacterSheet } from './data';

export type DeathMove = 'blaze' | 'avoid' | 'risk' | 'spectral' | 'egg' | 'battleHardened' | 'unbreakable';

export interface DeathMoveExtras { blessedHope?: number; mask?: boolean; wearingMask?: boolean }
export interface DeathOptions {
  options: Array<{ move: string; label: string; key?: string; disabled?: string }>;
  bonuses: Array<{ key: string; label: string; kind: 'hopeBonus' | 'hopeSpend' | 'mask'; moves: string[]; bonus?: number; disabled?: string }>;
  undying: boolean;
}

export function recoverableHp(c: CharacterSheet): number {
  return Math.max(0, c.hp.on - hpHealingFloor(c));
}

export function hpHealingFloor(c: CharacterSheet): number {
  return Math.min(c.hp.slots, (c.permanentHp ?? 0)
    + c.effects.reduce((sum, effect) => sum + Math.max(0, effect.lockedHp ?? 0), 0));
}

export function basicDeathOptions(): DeathOptions {
  return { options: [{ move: 'blaze', label: 'Blaze of Glory' }, { move: 'avoid', label: 'Avoid Death' }, { move: 'risk', label: 'Risk It All' }], bonuses: [], undying: false };
}

export function canMakeDeathMove(c: CharacterSheet): boolean {
  return c.hp.slots > 0 && (c.permanentHp ?? 0) < c.hp.slots && c.hp.on === c.hp.slots && !c.pendingDeathRoll && !c.effects.some(e => e.unconsciousHp !== undefined);
}

export function syncDeathState(c: CharacterSheet): void {
  c.permanentHp = Math.max(0, Math.min(c.hp.slots, Math.floor(c.permanentHp ?? 0)));
  c.hp.on = Math.max(hpHealingFloor(c), Math.min(c.hp.slots, c.hp.on));
  c.scars = Math.max(0, Math.min(c.hope.total, Math.floor(c.scars ?? 0)));
  c.hope.slots = Math.max(0, c.hope.total - c.scars);
  c.hope.on = Math.min(c.hope.on, c.hope.slots);
  c.effects = c.effects.filter(e => e.unconsciousHp === undefined || c.hp.on >= e.unconsciousHp);
  c.effects = c.effects.filter(e => {
    if (e.spectralHp === undefined) return true;
    if (c.hp.on >= c.hp.slots) { c.spectralBlocked = true; return false; }
    return c.hp.on >= e.spectralHp;
  });
  if (c.hp.on < c.hp.slots && !c.effects.some(e => e.spectralHp !== undefined)) c.spectralBlocked = false;
}
