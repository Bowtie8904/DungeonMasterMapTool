import type { RollSource } from '../protocol';
import type { CharacterSheet } from './data';
import { activeFeatures, activeWeapons } from './engine';
import { companionWeapon } from './companion';
import { activeCards } from './copycat';

/** Resolve action keys, including individual grimoire spells, without copying automation. */
export function rollSourceFor(c: CharacterSheet, key: string): RollSource | undefined {
  const companion = companionWeapon(c);
  if (companion && (key === 'companion' || key === `weapon:${companion.name}`)) return {
    name: companion.name, source: `Companion - ${companion.trait} - ${companion.range} - ${companion.damage} ${companion.damageType}`,
    text: 'Command with a Spellcast Roll. Spend Hope to add an applicable companion Experience. Attacks use the owner\'s Proficiency and applicable benefits, including Ranger\'s Focus. A successful Hope command grants advantage only if the next action builds on the success.',
  };
  for (const f of [...activeFeatures(c), ...c.heritage]) {
    if (key === `feat:${f.name}`) return { name: f.name, text: f.text, source: f.source };
  }
  for (const d of [...activeCards(c), ...c.vault]) {
    const source = `${d.domain} - Level ${d.level} ${d.type}`;
    const copied = c.loadout.some(card => card.borrowedCard?.card === d);
    const cardKey = copied ? `card:Copycat/${d.name}` : `card:${d.name}`;
    if (key === cardKey) return { name: d.name, text: d.text, source: copied ? `Copycat - ${source}` : source };
    for (const s of d.spells ?? []) {
      if (key === `${cardKey}/${s.name}`) return { name: s.name, text: s.text, source: `${copied ? 'Copycat - ' : ''}${d.name} - ${source}` };
    }
  }
  for (const w of [...activeWeapons(c), ...c.carriedWeapons]) {
    const grantedBy = w.generated ? c.features.find(f => f.automation?.unarmedWeapon?.name === w.name) : undefined;
    if (key === `weapon:${w.name}`) return {
      name: w.name, text: w.feature ? `${w.feature.name}\n\n${w.feature.text}` : grantedBy ? `${grantedBy.name}\n\n${grantedBy.text}` : '',
      source: `${w.trait} - ${w.range} - ${w.damage} ${w.damageType}`,
    };
  }
  for (const a of [...(c.activeArmor ? [c.activeArmor] : []), ...c.carriedArmor]) {
    if (key === `armor:${a.name}`) return {
      name: a.name, text: a.feature ? `${a.feature.name}\n\n${a.feature.text}` : '',
      source: `Armor score ${a.score} - Tier ${a.tier}`,
    };
  }
  const i = c.inventory.find((i) => key === `item:${i.name}`);
  return i ? { name: i.name, text: i.note ?? '', source: i.type } : undefined;
}
