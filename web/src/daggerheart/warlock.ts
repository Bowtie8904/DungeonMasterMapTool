import type { CharacterSheet } from './data';
import { type DiceRoll, rollLocal } from './engine';

export function patronSides(c: CharacterSheet): number {
  return c.level >= 5 ? 8 : 6;
}

export function vengeanceReport(c: CharacterSheet, hp: number, roll: DiceRoll = rollLocal): string {
  const sides = patronSides(c);
  const dice = Array.from({ length: hp }, () => roll(`1d${sides}`));
  return `Deadly Vengeance: rolled ${hp}d${sides} [${dice.join(', ')}] for ${hp} HP marked; the attacker marks ${dice.filter(n => n >= 4).length} HP (one per 4+).`;
}
