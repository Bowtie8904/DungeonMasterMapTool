import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, type Weapon } from './data';
import { configuredRollOption, damageContributions, rollBonus, selectedRollBonus, type AvailableRollOption, type RollOption } from './engine';

const files = ['classes', 'ancestries', 'communities', 'weapons', 'armor', 'consumables', 'domain-cards', 'transformations', 'items'];
const options: Array<{ catalog: string; name: string; text: string; option: RollOption }> = [];
function collect(value: unknown, catalog: string, name = '', text = ''): void {
  if (Array.isArray(value)) { value.forEach(item => collect(item, catalog, name, text)); return; }
  if (!value || typeof value !== 'object') return;
  const object = value as Record<string, unknown>;
  if (typeof object.name === 'string') name = object.name;
  if (typeof object.text === 'string') text = object.text;
  for (const [key, child] of Object.entries(object)) {
    if ((key === 'rollOptions' || key === 'conditionalRollOptions') && Array.isArray(child)) {
      for (const option of child as RollOption[]) {
        if (option.roll === 'damage') options.push({ catalog, name, text, option });
      }
    }
    collect(child, catalog, name, text);
  }
}
for (const file of files) collect(JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${file}.json`, import.meta.url), 'utf8')), file);

describe('catalog damage attribution', () => {
  it.each(options)('$catalog: $name / $option.id has one described contribution', ({ name, text, option }) => {
    const c = emptyCharacter('Roller');
    c.level = 5;
    c.features.push({ name, text, source: 'Catalog', automation: { rollOptions: [option] } });
    c.resources = {};
    for (const resource of ['fear', 'fiends', 'upstaged', 'retribution', 'slayer-dice', 'spellcharge', 'twilight']) c.resources[resource] = 2;
    const selection: AvailableRollOption = { key: `feat:${name}`, source: name, automation: {}, option };
    const configured = configuredRollOption(c, selection);
    const bonus = selectedRollBonus(c, rollBonus(c, ['damage']), [configured]);
    expect(bonus.contributions).toHaveLength(1);
    expect(bonus.contributions[0].rollSource).toEqual({ name, text, source: 'Catalog' });
    expect(bonus.contributions[0].label).not.toBe('');
  });

  it('separates additive extra dice but leaves Proficiency and the flat weapon bonus alone', () => {
    const c = emptyCharacter('Roller');
    c.proficiency.on = 2;
    const weapon: Weapon = { name: 'Strike', slot: 'Primary', damage: 'd8+d6+3', damageType: 'phys',
      range: 'Melee', trait: 'Strength', tier: 1, hands: 1 };
    c.activeWeapons.push(weapon);
    c.effects.push({ id: 'fire', name: 'Elemental Dominion: Fire', text: 'Fire rules', kind: 'buff',
      damage: { extraDice: 1, dropLowest: 0, proficiencyOnly: true } });
    const parts = damageContributions(c, weapon, rollBonus(c, ['damage']), 1);
    expect(parts[0].expression).toBe('3d8+3d6');
    expect(parts[0].notes).toContainEqual({ label: 'Attack-linked Proficiency', impact: '+1 die' });
    expect(parts[1]).toMatchObject({ label: 'Strike damage bonus', expression: '+3' });
    expect(parts[2]).toMatchObject({ label: 'Elemental Dominion: Fire', expression: '1d8+1d6',
      rollSource: { name: 'Elemental Dominion: Fire', text: 'Fire rules' } });
    weapon.damage = 'd8+3';
    const single = damageContributions(c, weapon, rollBonus(c, ['damage']));
    expect(single.map(part => part.expression)).toEqual(['2d8', '+3', '1d8']);
    expect(single[1].label).toBe('Strike damage bonus');
  });
});
