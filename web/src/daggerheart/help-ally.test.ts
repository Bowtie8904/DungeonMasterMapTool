import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, dualityExpression } from './data';
import { actionBlocker, availableRollOptions, selectedRollBonus, rollBonus, suggestedAdvantage } from './engine';
import { helpAllyChoices, helpAllyDie } from './help-ally';
import { updateCharacterAutomation } from './persist';

const classes: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));
const cards: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
const consumables: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/consumables.json', import.meta.url), 'utf8'));

describe('Help an Ally catalog integration', () => {
  it.each([
    ['bard-wordsmith-mastery', 'Epic Poetry', 10],
    ['sorcerer-primal-origin-specialization', 'Enchanted Aid', 8],
  ])('offers the printed help die for %s', (id, name, sides) => {
    const c = emptyCharacter('Helper');
    c.features = classes.entries.find(entry => entry.id === id)!.features!
      .map(feature => ({ ...feature, source: 'Subclass' }));
    const choice = helpAllyChoices(c).find(choice => choice.name === name)!;
    expect(choice.action.helpAlly?.die).toBe(sides);
    expect(choice.action.cost?.hope).toBe(1);
    expect(helpAllyDie(c, choice)).toBe(sides);
    expect(helpAllyDie(c)).toBe(name === 'Epic Poetry' ? 10 : 6);
  });

  it('keeps Enchanted Aid unlimited independently of its Spellcast swap', () => {
    const c = emptyCharacter('Helper');
    c.features = classes.entries.find(entry => entry.id === 'sorcerer-primal-origin-specialization')!.features!
      .map(feature => ({ ...feature, source: 'Subclass' }));
    c.usage['feat:Enchanted Aid'] = { per: 'longRest', used: 1 };
    const choice = helpAllyChoices(c)[0];
    expect(actionBlocker(c, choice.key, choice.automation, choice.action)).toBeNull();
    expect(choice.action.helpAlly?.spellcastOnly).toBe(true);
  });

  it('offers Familiar beastform help only while that granted feature is active', () => {
    const c = emptyCharacter('Helper');
    const druid = classes.entries.find(entry => entry.id === 'druid')!;
    const familiar = druid.features!.flatMap(feature => feature.automation?.beastforms ?? []).find(form => form.name === 'Household Friend')!;
    c.effects.push({ id: 'form', name: 'Familiar', kind: 'buff', until: 'manual',
      features: familiar.features!.map(feature => ({ ...feature, source: 'Beastform' })) });
    expect(helpAllyChoices(c)[0].action.helpAlly?.die).toBe(8);
    expect(helpAllyDie(c)).toBe(8);
    c.effects = [];
    expect(helpAllyChoices(c)).toHaveLength(0);
    expect(helpAllyDie(c)).toBe(6);
  });

  it('gates Runic Adrenaline d8 on marked HP and loads Tactician assistance metadata', () => {
    const c = emptyCharacter('Helper');
    for (const name of ['Runic Adrenaline', 'Tactician']) {
      const entry = cards.entries.find(entry => entry.name === name)!;
      c.loadout.push({ name, domain: entry.domain!, type: 'Ability', level: entry.level!, recall: entry.recall!,
        text: entry.text!, automation: entry.automation });
    }
    expect(availableRollOptions(c, ['action'])).toHaveLength(0);
    expect(helpAllyDie(c)).toBe(6);
    c.hp.on = 1;
    expect(availableRollOptions(c, ['action'])[0].option.advantageDie).toBe(8);
    expect(helpAllyDie(c)).toBe(8);
    c.vault.push(...c.loadout.splice(0, 1));
    expect(helpAllyDie(c)).toBe(6);
    expect(c.loadout[0].automation?.helpAllyExperience).toBe(true);
  });

  it('keeps fixed help dice separate from own advantage and summed bonuses', () => {
    const c = emptyCharacter('Ally');
    const option = { key: 'effect:help', source: 'Help', automation: {},
      option: { id: 'help', label: 'Help', roll: 'action', helpAllyDie: 10 as const, advantage: 'advantage' as const } };
    expect(selectedRollBonus(c, rollBonus(c, ['action']), [option]).dice).toEqual([]);
    expect(dualityExpression(2, 1, 12, 12, 10)).toBe('2d12+1d10+2');
    expect(dualityExpression(2, -1, 12, 12, 6, 8)).toBe('2d12-1d8+2');
    const disadvantage = { ...option, option: { ...option.option, advantage: 'disadvantage' as const } };
    expect(suggestedAdvantage([option, disadvantage])).toBe(0);
    expect(suggestedAdvantage([option, option, disadvantage])).toBe(1);
    const fern = consumables.entries.find(entry => entry.name === 'Phantom Fern Shoots')!;
    expect(fern.automation?.actions?.[0].steps?.[0].effect?.disadvantageDie).toBe(8);
  });

  it('applies the strongest passive override to standard and free-Hope feature help', () => {
    const c = emptyCharacter('Helper');
    const entry = classes.entries.find(entry => entry.id === 'bard-wordsmith-mastery')!;
    c.features = entry.features!.map(feature => ({ ...feature, source: 'Subclass' }));
    c.features.push({ name: 'Free help', text: '', source: 'Custom', automation: {
      actions: [{ id: 'help', label: 'Free help', helpAlly: { die: 6 } }],
    } });
    c.activeArmor = { name: 'Custom armor', tier: 1, score: 1, major: 5, severe: 10,
      automation: { helpAllyDie: { die: 8 } } };
    expect(helpAllyDie(c)).toBe(10);
    expect(helpAllyDie(c, helpAllyChoices(c).find(choice => choice.name === 'Free help'))).toBe(10);
    c.inventory.push({ name: 'Custom item', type: 'gear', qty: 1, custom: true,
      automation: { helpAllyDie: { die: 12 } } });
    expect(helpAllyDie(c)).toBe(12);
    c.inventory[0].qty = 0;
    expect(helpAllyDie(c)).toBe(10);
  });

  it('refreshes older equipped Helper armor metadata without losing other inventory', () => {
    const armor: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/armor.json', import.meta.url), 'utf8'));
    const c = emptyCharacter('Helper');
    c.activeArmor = { name: 'Love Guard', tier: 2, score: 4, major: 8, severe: 16 };
    updateCharacterAutomation(c, [armor]);
    expect(c.activeArmor.automation?.helpAllyEvasion).toBe(1);
    expect(c.activeArmor.automation?.actions?.[0].helpAlly?.die).toBe(6);
  });
});
