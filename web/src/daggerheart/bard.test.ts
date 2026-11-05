import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CompendiumFile } from './compendium';
import { emptyCharacter } from './data';
import {
  addEffect, applyRest, availableRollOptions, availableRollTriggers, commitRollOptions,
  runAction, selectedRollBonus, rollBonus, selectionBlocker, usesLeft, usesMax,
} from './engine';
import { updateCharacterAutomation } from './persist';

const catalog: CompendiumFile = JSON.parse(readFileSync(new URL('../../../dmmap-daggerheart/classes.json', import.meta.url), 'utf8'));

function bard() {
  const c = emptyCharacter('Bard');
  c.className = 'Bard';
  c.subclass = 'Troubadour';
  c.features = catalog.entries.filter(e => e.id === 'bard' || e.id?.startsWith('bard-troubadour'))
    .flatMap(e => (e.features ?? []).map(f => ({ ...structuredClone(f), text: f.text ?? '',
      source: e.kind === 'class' ? 'Class feature - Bard' : `Subclass ${e.stage} - Troubadour` })));
  return c;
}

describe('Bard automation', () => {
  it('gives each song an independent long-rest limit and upgrades it without resetting uses', () => {
    const c = bard();
    const virtuoso = c.features.find(f => f.name === 'Virtuoso')!;
    c.features.splice(c.features.indexOf(virtuoso), 1);
    for (const name of ['Relaxing Song', 'Epic Song', 'Heartbreaking Song']) {
      const song = c.features.find(f => f.name === name)!;
      const key = `feat:${name}`;
      expect(usesMax(c, key, song.automation!)).toBe(1);
      expect(runAction(c, key, song.automation!, song.automation!.actions![0], name)).toBeTruthy();
      expect(usesLeft(c, key, song.automation!)).toBe(0);
    }
    c.features.push(virtuoso);
    for (const name of ['Relaxing Song', 'Epic Song', 'Heartbreaking Song']) {
      const song = c.features.find(f => f.name === name)!;
      expect(usesMax(c, `feat:${name}`, song.automation!)).toBe(2);
      expect(usesLeft(c, `feat:${name}`, song.automation!)).toBe(1);
    }
    applyRest(c, 'short');
    expect(c.usage['feat:Relaxing Song'].used).toBe(1);
    applyRest(c, 'long');
    expect(c.usage['feat:Relaxing Song']).toBeUndefined();
  });

  it('refreshes existing Gifted Performer sheets with the split songs without duplicate features or lost usage', () => {
    const c = bard();
    c.features = c.features.filter(f => !['Epic Song', 'Heartbreaking Song'].includes(f.name));
    c.features.find(f => f.name === 'Relaxing Song')!.text = 'Old combined songs';
    c.usage['feat:Relaxing Song'] = { used: 1, per: 'longRest' };
    updateCharacterAutomation(c, [catalog]);
    updateCharacterAutomation(c, [catalog]);
    expect(c.features.filter(f => ['Relaxing Song', 'Epic Song', 'Heartbreaking Song'].includes(f.name))).toHaveLength(3);
    expect(c.features.find(f => f.name === 'Relaxing Song')!.text).not.toContain('Old combined');
    expect(c.usage['feat:Relaxing Song'].used).toBe(1);
  });

  it.each(['action', 'reaction', 'damage'])('spends one resolved Rally Die on an optional %s roll', target => {
    const c = emptyCharacter('Ally');
    addEffect(c, { name: 'Rally Die', kind: 'buff', until: 'session', unique: true,
      rollOptions: ['action', 'reaction', 'damage'].map(roll => ({
        id: roll, label: 'Spend Rally Die', roll, dice: '1d10', endEffect: 'Rally Die',
      })) });
    const options = availableRollOptions(c, target === 'reaction' ? ['reaction', 'action'] : [target]);
    expect(options).toHaveLength(1);
    expect(options[0].active).toBe(false);
    expect(c.effects).toHaveLength(1);
    expect(selectedRollBonus(c, rollBonus(c, [target]), options).dice).toEqual(['1d10']);
    commitRollOptions(c, options);
    expect(c.effects).toHaveLength(0);
    expect(availableRollOptions(c, ['damage'])).toHaveLength(0);
    expect(selectionBlocker(c, options)).toContain('no longer active');
  });

  it('shares the Rally Die with the clear-Stress action and retains it through rests', () => {
    const c = emptyCharacter('Ally');
    c.stress.on = 5;
    const action = { id: 'stress', label: 'Spend Rally Die', endEffect: 'Rally Die',
      steps: [{ op: 'clear' as const, resource: 'stress', amount: '1d8' }] };
    addEffect(c, { name: 'Rally Die', kind: 'buff', until: 'session', actions: [action] });
    applyRest(c, 'long');
    expect(c.effects).toHaveLength(1);
    c.stress.on = 5;
    expect(runAction(c, 'effect:die', {}, action, 'Rally Die', () => 3)).toContain('cleared 3 Stress');
    expect(c.stress.on).toBe(2);
    expect(c.effects).toHaveLength(0);
  });

  it('offers Heart of a Poet after action rolls and Epic Poetry as a d10 help action', () => {
    const entries = catalog.entries.filter(e => e.id?.startsWith('bard-wordsmith'));
    const c = emptyCharacter('Wordsmith');
    c.features = entries.flatMap(e => (e.features ?? []).map(f => ({ ...f, text: f.text ?? '', source: e.name })));
    const triggers = availableRollTriggers(c, { source: 'action,trait:Presence', hope: 5, fear: 8, total: 13 });
    expect(triggers.find(t => t.source === 'Heart of a Poet')?.trigger).toMatchObject({ addDice: '1d4', cost: { hope: 1 } });
    expect(availableRollOptions(c, ['action']).some(o => o.source === 'Heart of a Poet')).toBe(false);
    expect(c.features.find(f => f.name === 'Epic Poetry')?.automation?.actions?.[0])
      .toMatchObject({ sendRoll: { dice: '1d10' }, cost: { hope: 1 } });
  });
});
