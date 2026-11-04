import { describe, expect, it } from 'vitest';
import { actionReportTitle, formatActionReport, parseActionReport } from './action-summary';
import { emptyCharacter } from './data';

describe('action report', () => {
  it.each([
    ['Community - Hearthborne', 'Close-Knit', 'Close-Knit', 'Hearthborne - Close-Knit'],
    ['Community - Freeborne', 'Unbound', 'Unbound', 'Freeborne - Unbound'],
    ['Ancestry - Elf', 'Quick Reactions', 'Quick Reactions (reaction roll)', 'Elf - Quick Reactions (reaction roll)'],
    ['Community - Orderborne', 'Dedicated', 'Dedicated (Hope d20)', 'Orderborne - Dedicated (Hope d20)'],
    ['Community - Duneborne', 'Oasis', 'Reroll downtime die', 'Duneborne - Oasis - Reroll downtime die'],
    ['Ancestry - Human (first)', 'High Stamina', 'Use', 'Human - High Stamina - Use'],
    ['Class feature - Ranger', 'Focus', 'Focus', 'Ranger - Focus'],
    ['Hope feature', 'No Mercy', 'Activate', 'Warrior - No Mercy - Activate'],
    ['Multiclass Bard - Wordsmith foundation', 'Rousing Speech', 'Use', 'Wordsmith - Rousing Speech - Use'],
    ['', 'Custom', 'Custom', 'Custom'],
  ])('uses feature provenance for %s / %s', (source, name, action, expected) => {
    const c = emptyCharacter('Hero');
    c.className = 'Warrior';
    for (const field of ['features', 'heritage'] as const) {
      c.features = [];
      c.heritage = [];
      c[field] = [{ source, name, text: '' }];
      expect(actionReportTitle(c, `feat:${name}`, name, action)).toBe(expected);
    }
  });

  it('preserves equipment sources and deduplicates identical action names', () => {
    const c = emptyCharacter('Hero');
    expect(actionReportTitle(c, 'item:Potion', 'Potion', 'Drink')).toBe('Potion - Drink');
    expect(actionReportTitle(c, 'card:Boost', 'Boost', 'Boost')).toBe('Boost');
  });

  it('round-trips the chat action report', () => {
    const report = { title: 'Minor Health Potion - Drink', character: 'Jitta', lines: ['cleared 2 HP (1d4)'] };
    expect(parseActionReport(formatActionReport(report))).toEqual(report);
    expect(parseActionReport('Jitta: Potion - Drink: cleared 2 HP')).toBeNull();
  });
});
