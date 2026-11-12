import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { emptyCharacter, dualityExpression, normalizeSheet } from './data';
import type { CompendiumFile } from './compendium';
import { actionBlocker, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions, derivedStats, rollBonus, runActionDetailed, selectionBlocker, sheetActions, suggestedAdvantage, syncResources } from './engine';
import { applyDamage, availableMitigations, computeDamage } from './damage';
import { ancestryFeatureChoices, buildCharacter, creationAllowances, emptyChoices, replaceAncestry, setTransformation, type RulesData } from './rules';
import { availableMoves, resolveRest } from './rest';
import { updateCharacterAutomation } from './persist';
import { rollContext } from './rollbuttons';

const catalog = (name: string): CompendiumFile => JSON.parse(readFileSync(new URL(`../../../dmmap-daggerheart/${name}.json`, import.meta.url), 'utf8'));
const files = ['classes', 'ancestries', 'communities', 'weapons', 'armor', 'consumables', 'domain-cards', 'transformations', 'items'].map(catalog);
const entries = (name: string) => files.find((f) => f.category === name)!.entries;
const data: RulesData = { classes: entries('classes'), ancestries: entries('ancestries'), communities: entries('communities'), weapons: entries('weapons'), armor: entries('armor'), consumables: entries('consumables'), cards: entries('domain-cards'), transformations: entries('transformations'), items: entries('items') };
function character(name: string) {
  const c = emptyCharacter(name);
  c.ancestry = 'Human';
  c.hp.slots = c.stress.slots = 6;
  c.activeArmor = { name: 'Test armor', tier: 1, score: 3, major: 6, severe: 12 };
  setTransformation(data, c, name);
  syncResources(c);
  return c;
}

describe('transformation automation', () => {
  it('applies Gifted to reaction rolls without stacking its action bonus', () => {
    const c = character('Demigod');
    expect(rollBonus(c, ['reaction', 'trait:Agility']).value).toBe(1);
    expect(rollBonus(c, ['action', 'trait:Agility']).value).toBe(1);
    expect(rollBonus(c, ['damage']).value).toBe(1);
  });

  it('applies Ghost physical resistance without stacking and doubles magic before reductions', () => {
    const c = character('Ghost');
    const options = availableMitigations(c);
    const physical = computeDamage(c, options, { damage: 15, type: 'physical', armor: false, selected: new Set() });
    expect(physical.damage).toBe(8);
    c.heritage.push({ name: 'Other resistance', source: 'test', text: '', automation: { mitigation: [{ id: 'half', label: 'Half', halve: true, passive: true }] } });
    expect(computeDamage(c, availableMitigations(c), { damage: 15, type: 'physical', armor: false, selected: new Set() }).damage).toBe(8);
    const magic = computeDamage(c, options, { damage: 8, type: 'magic', armor: false, selected: new Set() });
    expect(magic.damage).toBe(16);
    expect(magic.hp).toBe(3);
    const f = c.heritage.find((f) => f.name === 'Ephemeral')!;
    expect(runActionDetailed(c, 'feat:Ephemeral', f.automation!, f.automation!.actions![0], f.name)).toContain('spent 2 Stress');
    expect(c.stress.on).toBe(2);
  });

  it('tracks Feed through reloads and rests, offers hunger only at zero, and pays for a Fear d20', () => {
    const c = character('Vampire');
    const hungry = availableRollOptions(c, ['action']).find((o) => o.option.id === 'hungry')!;
    expect(hungry.option.defaultOn).toBe(true);
    expect(suggestedAdvantage([hungry])).toBe(-1);
    const reaction = availableRollOptions(c, ['reaction', 'trait:Agility']);
    expect(reaction).toHaveLength(1);
    expect(suggestedAdvantage(reaction)).toBe(-1);
    expect(reaction.some((o) => o.option.fearDie === 20)).toBe(false);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    let spend = availableRollOptions(c, ['action']).find((o) => o.option.fearDie === 20)!;
    expect(selectionBlocker(c, [spend])).toBeTruthy();
    c.resources!.feedTokens = 3;
    expect(availableRollOptions(c, ['reaction', 'trait:Agility'])).toHaveLength(0);
    expect(availableRollOptions(c, ['action']).some((o) => o.option.id === 'hungry')).toBe(false);
    spend = availableRollOptions(c, ['action']).find((o) => o.option.fearDie === 20)!;
    commitRollOptions(c, [spend]);
    expect(c.resources!.feedTokens).toBe(2);
    expect(dualityExpression(2, 0, 12, 20)).toBe('1d12+1d20+2');
    expect(dualityExpression(0, -1, 20, 20)).toBe('1d20+1d20-1d6');
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    updateCharacterAutomation(loaded, files);
    expect(loaded.resources!.feedTokens).toBe(2);
    applyRest(loaded, 'short');
    expect(loaded.resources!.feedTokens).toBe(2);
    applyRest(loaded, 'long');
    expect(loaded.resources!.feedTokens).toBe(1);
    applyRest(loaded, 'long');
    applyRest(loaded, 'long');
    expect(loaded.resources!.feedTokens).toBe(0);
  });

  it('offers Feed only on Fangs attacks and configures Fangs with normal attack/damage actions', () => {
    const c = character('Vampire');
    const fangs = c.heritage.find((f) => f.name === 'Fangs')!.automation!;
    expect(fangs.actions!.find((a) => a.actionRoll)?.actionRoll).toEqual({ trait: 'highest', attack: true });
    expect(fangs.actions!.find((a) => a.damage)?.damage).toEqual({ dice: 'd6', type: 'physical' });
    const roll = { source: 'attack,action', hope: 8, fear: 4, total: 14, weapon: 'Sword' };
    expect(availableRollTriggers(c, roll)).toHaveLength(0);
    expect(availableRollTriggers(c, { ...roll, weapon: 'Fangs' })[0].trigger).toMatchObject({ tokenGain: { resource: 'feedTokens', max: 6 }, cost: { stress: 1 } });
    expect(rollContext({ id: 1, ts: 1, expression: '2d12', kind: 'duality', rolls: [8, 4], total: 12, source: 'attack', weapon: 'Fangs', fromId: 'player', from: 'Player' })?.weapon).toBe('Fangs');
  });

  it('activates Wolf Form by feature and mitigation, adds automatic bonuses, and ends on rest/rampage', () => {
    const c = character('Werewolf');
    const wolf = c.heritage.find((f) => f.name === 'Wolf Form')!.automation!;
    const rampage = c.heritage.find((f) => f.name === 'Howling Rampage')!.automation!;
    expect(actionBlocker(c, 'feat:Howling Rampage', rampage, rampage.actions![0])).toBeTruthy();
    runActionDetailed(c, 'feat:Wolf Form', wolf, wolf.actions![0], 'Wolf Form');
    expect(c.effects[0]).toMatchObject({ name: 'Wolf Form', until: 'rest' });
    expect(c.stress.on).toBe(1);
    expect(rollBonus(c, ['action', 'attack']).dice).toEqual(['1d10']);
    expect(rollBonus(c, ['damage']).dice).toEqual(['1d10']);
    expect(rollBonus(c, ['spelldamage']).dice).toEqual(['1d10']);
    expect(rollBonus(c, ['action', 'trait:Agility']).dice).toEqual([]);
    expect(availableRollTriggers(c, { source: 'action', hope: 9, fear: 4, total: 13 })[0].trigger.cost).toEqual({ stress: 1 });
    expect(availableRollTriggers(c, { source: 'action', hope: 4, fear: 9, total: 13 })).toHaveLength(0);
    expect(availableRollTriggers(c, { source: 'action', hope: 9, fear: 9, total: 18 })).toHaveLength(1);
    expect(rampage.actions![0].sendRoll?.dice).toBe('@tierd20');
    runActionDetailed(c, 'feat:Howling Rampage', rampage, rampage.actions![0], 'Howling Rampage');
    expect(c.effects).toHaveLength(0);
    const options = availableMitigations(c);
    const input = { damage: 8, type: 'physical' as const, armor: false, selected: new Set(options.map((m) => m.id)) };
    const damage = computeDamage(c, options, input);
    expect(damage.hp).toBeGreaterThan(0);
    expect(applyDamage(c, damage, 'physical')).toContain('Wolf Form');
    expect(c.effects[0].name).toBe('Wolf Form');
    expect(c.stress.on).toBe(2);
    applyRest(c, 'short');
    expect(c.effects).toHaveLength(0);
    const noHp = computeDamage(c, availableMitigations(c), { ...input, damage: 0 });
    expect(noHp.applied).toHaveLength(0);
    expect(noHp.costs).toEqual({});
    c.stress.on = c.stress.slots;
    expect(computeDamage(c, availableMitigations(c), input).error).toContain('Stress');
  });

  it('requires an explicit Shapeshifter feature and restores ancestry bonuses when removed', () => {
    const c = character('');
    replaceAncestry(data.ancestries, c, 'Giant');
    const giant = ancestryFeatureChoices(data.ancestries, 'Giant');
    const hp = c.hp.slots;
    expect(() => setTransformation(data, c, 'Shapeshifter')).toThrow('Choose');
    expect(c.transformation).toBeUndefined();
    const nonHp = giant.find((f) => !f.automation?.creation?.hp)!;
    setTransformation(data, c, 'Shapeshifter', nonHp.name);
    expect(c.heritage.filter((f) => f.source.startsWith('Ancestry - ')).map((f) => f.name)).toEqual([nonHp.name]);
    expect(c.hp.slots).toBe(hp - 1);
    expect(sheetActions(c.heritage.find((f) => f.name === 'Change Shape')!.automation)).toHaveLength(1);
    setTransformation(data, c, '');
    expect(c.heritage.filter((f) => f.source.startsWith('Ancestry - '))).toHaveLength(2);
    expect(c.hp.slots).toBe(hp);
  });

  it('changes ancestry/feature transactionally through downtime moves and preserves choices on reload', () => {
    const c = character('');
    const feature = ancestryFeatureChoices(data.ancestries, 'Human')[0].name;
    setTransformation(data, c, 'Shapeshifter', feature);
    const moves = availableMoves(c, 'short');
    const change = moves.find((m) => m.ancestryChoice === 'ancestry')!;
    const skin = moves.find((m) => m.ancestryChoice === 'feature')!;
    const before = structuredClone(c);
    expect(() => resolveRest(c, 'short', [{ move: change.id }])).toThrow('Choose');
    expect(() => resolveRest(c, 'short', [{ move: skin.id, ancestry: { ancestry: 'Human', feature } }], { ancestries: data.ancestries })).toThrow('different');
    expect(c).toEqual(before);
    const elf = ancestryFeatureChoices(data.ancestries, 'Elf');
    const result = resolveRest(c, 'short', [
      { move: change.id, ancestry: { ancestry: 'Elf', feature: elf[0].name } },
      { move: skin.id, ancestry: { ancestry: 'Elf', feature: elf[1].name } },
    ], { ancestries: data.ancestries });
    expect(result.lines.join('\n')).toContain('Elf');
    expect(c.ancestry).toBe('Elf');
    expect(c.ancestryFeature).toBe(elf[1].name);
    const loaded = normalizeSheet(JSON.parse(JSON.stringify(c)), c.name);
    updateCharacterAutomation(loaded, files);
    expect(loaded.heritage.filter((f) => f.source.startsWith('Ancestry - ')).map((f) => f.name)).toEqual([elf[1].name]);
    expect(loaded.ancestryFeature).toBe(elf[1].name);
    expect(derivedStats(loaded)).toEqual(derivedStats(c));
  });

  it('applies only the retained ancestry creation grant in the Mancer, including mixed ancestry', () => {
    const ch = { ...emptyChoices(), className: 'Warrior', subclass: 'Warrior: Call of the Brave', ancestry: 'Giant', transformation: 'Shapeshifter', experiences: ['One', 'Two'] };
    const features = ancestryFeatureChoices(data.ancestries, 'Giant');
    expect(() => buildCharacter(data, ch)).toThrow('Choose');
    const withHp = { ...ch, ancestryFeature: features.find((f) => f.automation?.creation?.hp)!.name };
    expect(creationAllowances(data, withHp).hp).toBe(1);
    const withoutHp = { ...ch, ancestryFeature: features.find((f) => !f.automation?.creation?.hp)!.name };
    expect(creationAllowances(data, withoutHp).hp).toBe(0);
    expect(ancestryFeatureChoices(data.ancestries, 'Giant / Human')).toHaveLength(2);
  });

  it('drops transformation-owned effects when the transformation is removed', () => {
    const c = character('Werewolf');
    const wolf = c.heritage.find((f) => f.name === 'Wolf Form')!.automation!;
    runActionDetailed(c, 'feat:Wolf Form', wolf, wolf.actions![0], 'Wolf Form');
    setTransformation(data, c, '');
    expect(rollBonus(c, ['attack']).dice).toEqual([]);
    expect(c.effects).toHaveLength(0);
  });

  it('ends effects from an ancestry feature that is no longer retained', () => {
    const c = character('');
    replaceAncestry(data.ancestries, c, 'Galapa');
    const retract = c.heritage.find((f) => f.name === 'Retract')!;
    runActionDetailed(c, 'feat:Retract', retract.automation!, retract.automation!.actions![0], 'Retract');
    expect(c.effects).toHaveLength(1);
    setTransformation(data, c, 'Shapeshifter', c.heritage.find((f) => f.name !== 'Retract')!.name);
    expect(c.effects).toHaveLength(0);
  });
});
