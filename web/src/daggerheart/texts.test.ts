import { describe, expect, it } from 'vitest';
import cases from '../../../src/test/resources/dmmt/text-template-cases.json';
import { applyReportLines, formatText, templatePlaceholders, validateTexts } from './texts';
import { actionButtonText } from './engine';

describe('formatText', () => {
  it('matches the shared conformance cases', () => {
    for (const c of cases as { template: string; vars: Record<string, string | number>; expected: string }[]) {
      expect(formatText(c.template, c.vars), c.template).toBe(c.expected);
    }
  });
  it('lists placeholders', () => {
    expect(templatePlaceholders('{a} {{b}} {c}')).toEqual(['a', 'c']);
  });
});

describe('validateTexts', () => {
  it('accepts valid texts and reports problems', () => {
    expect(validateTexts({ button: 'Use {source}', report: ['{effects}'] }, 'x')).toEqual([]);
    expect(validateTexts({ button: '{nope}' }, 'x')).toHaveLength(1);
    expect(validateTexts({ button: '{effects}' }, 'x')).toHaveLength(1);
    expect(validateTexts({ bogus: 'a' }, 'x')).toHaveLength(1);
    expect(validateTexts({ report: 'a' }, 'x')).toHaveLength(1);
  });
});

describe('applied texts', () => {
  it('keeps the cost suffix automatic', () => {
    expect(actionButtonText('Drink it', 'Use', {}, '1 Hope')).toBe('Drink it (1 Hope)');
    expect(actionButtonText(undefined, 'Use', {}, '1 Hope')).toBe('Use (1 Hope)');
    expect(actionButtonText(undefined, 'Use', {}, '')).toBe('Use');
  });
  it('keeps "spent" first in reports', () => {
    expect(applyReportLines({ report: ['{actor} feels better', '{effects}'] }, ['spent 1 Hope', 'Cleared 2 HP'], { actor: 'Ayla' }, 'spent 1 Hope'))
      .toEqual(['spent 1 Hope', 'Ayla feels better', 'Cleared 2 HP']);
    expect(applyReportLines(undefined, ['a'], {})).toEqual(['a']);
  });
});
