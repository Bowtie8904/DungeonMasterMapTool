import { describe, expect, it } from 'vitest';
import { emptyCharacter } from './data';
import { rollSourceFor } from './roll-source';
import { rerollRequest } from './rollbuttons';
import type { AvailableRollTrigger } from './engine';
import type { RollMessage } from '../protocol';

describe('roll source snapshots', () => {
  it('copies only descriptions and does not follow subsequent sheet edits', () => {
    const c = emptyCharacter('Roller');
    c.features.push({ name: 'Feature', text: '**Rules**', source: 'Class', automation: { actions: [{ id: 'use', label: 'Use' }] } });
    const source = rollSourceFor(c, 'feat:Feature');
    c.features[0].text = 'Changed rules';
    expect(source).toEqual({ name: 'Feature', text: '**Rules**', source: 'Class' });
    expect(rollSourceFor(c, 'trait:Agility')).toBeUndefined();
  });

  it('uses individual spell text and preserves its grimoire context', () => {
    const c = emptyCharacter('Roller');
    c.loadout.push({ name: 'Book', domain: 'Codex', type: 'Grimoire', level: 1, recall: 1,
      text: 'Whole book', spells: [{ name: 'Bolt', text: 'Bolt rules' }, { name: 'Shield', text: 'Shield rules' }] });
    expect(rollSourceFor(c, 'card:Book/Bolt')).toEqual({
      name: 'Bolt', text: 'Bolt rules', source: 'Book - Codex - Level 1 Grimoire',
    });
    expect(rollSourceFor(c, 'card:Book')?.text).toBe('Whole book');
    c.vault.push({ name: 'Spell', domain: 'Sage', type: 'Spell', level: 2, recall: 0, text: 'Spell rules' });
    expect(rollSourceFor(c, 'card:Spell')?.text).toBe('Spell rules');
  });

  it('resolves heritage, equipment and item actions without stock or automation', () => {
    const c = emptyCharacter('Roller');
    c.heritage.push({ name: 'Heritage', text: 'Heritage rules', source: 'Ancestry' });
    c.activeWeapons.push({ name: 'Sword', slot: 'Primary', trait: 'Agility', range: 'Melee', damage: 'd6',
      damageType: 'phys', hands: 1, tier: 1, feature: { name: 'Sharp', text: 'Sharp rules' } });
    c.activeArmor = { name: 'Armor', score: 3, tier: 1, feature: { name: 'Heavy', text: 'Heavy rules' } };
    c.inventory.push({ name: 'Potion', note: 'Potion rules', type: 'consumable', qty: 2 });
    expect(rollSourceFor(c, 'feat:Heritage')?.text).toBe('Heritage rules');
    expect(rollSourceFor(c, 'weapon:Sword')?.text).toBe('Sharp\n\nSharp rules');
    expect(rollSourceFor(c, 'armor:Armor')?.text).toBe('Heavy\n\nHeavy rules');
    expect(rollSourceFor(c, 'item:Potion')).toEqual({ name: 'Potion', text: 'Potion rules', source: 'consumable' });
  });

  it('retains the original source on full and Hope-only rerolls, including legacy messages', () => {
    const rollSource = { name: 'Spell', text: 'Original rules', source: 'Codex' };
    const m: RollMessage = { id: 1, ts: 0, fromId: 'p', from: 'Player', expression: '2d12+2',
      rolls: [3, 5], total: 10, kind: 'duality', rollSource, source: 'spellcast' };
    const t: AvailableRollTrigger = { key: 'feat:Reroll', source: 'Reroll', automation: {},
      trigger: { id: 'reroll', label: 'Again', on: 'duality', reroll: 'hope' } };
    expect(rerollRequest(m, t)?.rollSource).toBe(rollSource);
    t.trigger.reroll = 'duality';
    const original = { label: 'Spellcast', expression: m.expression, rollSource };
    expect(rerollRequest(m, t, original)?.rollSource).toBe(rollSource);
    expect(rerollRequest({ ...m, rollSource: undefined }, t, original)?.rollSource).toBe(rollSource);
  });
});
