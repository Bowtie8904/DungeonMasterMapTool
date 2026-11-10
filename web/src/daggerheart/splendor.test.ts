import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter, type DomainCard } from './data';
import { availableRollTriggers, type Automation } from './engine';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/domain-cards.json', import.meta.url), 'utf8'));
function load(id: string) {
  const entry = catalog.entries.find(card => card.id === id)!;
  const character = emptyCharacter('Caster');
  character.loadout = [{ name: entry.name, domain: 'Splendor', level: entry.level!, type: entry.cardType as DomainCard['type'],
    recall: entry.recall!, text: entry.text ?? '', automation: entry.automation }];
  return { character, automation: entry.automation as Automation };
}

it('offers only result-appropriate Healing Hands recovery, treating criticals as successes', () => {
  const { character } = load('healing-hands');
  const context = { source: 'action,spellcast,card:Healing Hands', hope: 8, fear: 3, total: 13, difficulty: 13 };
  for (const roll of [context, { ...context, hope: 4, fear: 4, total: 8 }]) {
    const triggers = availableRollTriggers(character, roll);
    expect(triggers).toHaveLength(2);
    expect(triggers.map(t => t.trigger.steps?.find(step => step.op === 'clear')?.amount)).toEqual([2, 2]);
    expect(triggers.every(t => t.trigger.target === 'actor' && t.trigger.excludeSourceActor && t.trigger.cost?.stress === 1)).toBe(true);
  }
  const failed = availableRollTriggers(character, { ...context, total: 12 });
  expect(failed).toHaveLength(2);
  expect(failed.map(t => t.trigger.steps?.find(step => step.op === 'clear')?.amount)).toEqual([1, 1]);
});

it('uses one Second Wind control on the card and on successful attack results', () => {
  const { character, automation } = load('second-wind');
  expect(automation.actions).toHaveLength(1);
  expect(automation.actions![0].splendorAction).toBe('secondWind');
  const context = { source: 'action,attack', hope: 8, fear: 3, total: 13, difficulty: 13 };
  expect(availableRollTriggers(character, context).map(t => t.trigger.splendorAction)).toEqual(['secondWind']);
  expect(availableRollTriggers(character, { ...context, difficulty: undefined }).map(t => t.trigger.splendorAction)).toEqual(['secondWind']);
  expect(availableRollTriggers(character, { ...context, total: 12 })).toHaveLength(0);
});

it.each([
  ['zone-of-protection', 'Zone of Protection', 16],
  ['salvation-beam', 'Salvation Beam', 16],
  ['resurrection', 'Resurrection', 20],
] as const)('offers %s only after successful casts, not as an extra card action', (id, name, difficulty) => {
  const { character, automation } = load(id);
  expect(automation.actions ?? []).toHaveLength(0);
  const context = { source: `action,spellcast,card:${name}`, hope: 8, fear: 3, total: difficulty, difficulty };
  expect(availableRollTriggers(character, context)).toHaveLength(1);
  expect(availableRollTriggers(character, { ...context, total: difficulty - 1 })).toHaveLength(0);
  expect(availableRollTriggers(character, { ...context, hope: 2, fear: 2, total: 4 })).toHaveLength(1);
});

it('has no redundant Restoration preparation or Sunlight damage buttons on the cards', () => {
  expect(load('restoration').automation.actions?.some(action => action.id === 'clear' || action.id === 'prepare')).toBe(false);
  const { character, automation } = load('stunning-sunlight');
  expect((automation.actions ?? []).every(action => action.actionRoll?.trait === 'spellcast')).toBe(true);
  const context = { source: 'action,spellcast,card:Stunning Sunlight', hope: 8, fear: 3, total: 13 };
  expect(availableRollTriggers(character, context).map(t => t.trigger.splendorAction)).toEqual(['sunlight']);
});
