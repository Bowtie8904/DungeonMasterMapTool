import type { RollSource } from '../protocol';
import type { CharacterSheet } from './data';

/** Resolve action keys, including individual grimoire spells, without copying automation. */
export function rollSourceFor(c: CharacterSheet, key: string): RollSource | undefined {
  for (const f of [...c.features, ...c.heritage]) {
    if (key === `feat:${f.name}`) return { name: f.name, text: f.text, source: f.source };
  }
  for (const d of [...c.loadout, ...c.vault]) {
    const source = `${d.domain} - Level ${d.level} ${d.type}`;
    if (key === `card:${d.name}`) return { name: d.name, text: d.text, source };
    for (const s of d.spells ?? []) {
      if (key === `card:${d.name}/${s.name}`) return { name: s.name, text: s.text, source: `${d.name} - ${source}` };
    }
  }
  for (const w of [...c.activeWeapons, ...c.carriedWeapons]) {
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
