import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import cases from '../../../src/test/resources/dmmt/text-template-cases.json';
import { applyReportLines, damageRollLabel, formatText, templatePlaceholders, validateCatalogTexts, validateTexts } from './texts';
import { actionButtonText, blockedTitle, optionRowParts, rollOptionAppliedLabel, rollOptionText } from './engine';
import type { ActionDef } from './engine';

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

describe('damageRollLabel', () => {
  it.each([
    ['Successful Power Push', 'Successful Power Push damage'],
    ['Successful Ice Spike', 'Successful Ice Spike damage'],
    ['Successful Fireball: damage', 'Successful Fireball: damage'],
    ['Blood Spike: mark a Stress, roll d10 damage', 'Blood Spike: mark a Stress, roll d10 damage'],
    ['Siphon Essence: roll d12+4 damage (once per long rest)', 'Siphon Essence: roll d12+4 damage (once per long rest)'],
    ['Damage', 'Damage'],
    ['Sword', 'Sword damage'],
  ])('labels %s without redundant damage', (label, expected) => {
    expect(damageRollLabel(label)).toBe(expected);
  });
});

describe('catalog button labels', () => {
  it('does not repeat the enclosing feature name on an action button', () => {
    const directory = new URL('../../../dmmap-daggerheart/', import.meta.url);
    const checked: string[] = [];
    function check(value: unknown, path: string, owner = ''): void {
      if (Array.isArray(value)) { value.forEach((item, i) => check(item, `${path}[${i}]`, owner)); return; }
      if (!value || typeof value !== 'object') return;
      const record = value as Record<string, unknown>;
      const name = typeof record.name === 'string' ? record.name : owner;
      if (name && /\.(?:actions|conditionalActions)\[\d+\]$/.test(path) && typeof record.label === 'string') {
        const template = record.texts && typeof record.texts === 'object'
          && 'button' in record.texts && typeof record.texts.button === 'string' ? record.texts.button : undefined;
        const button = actionButtonText(template, record.label, { source: name }, '');
        const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        if (button.toLowerCase() !== name.toLowerCase()) {
          expect(button, path).not.toMatch(new RegExp(`\\b${escaped}\\b`, 'i'));
        }
        checked.push(path);
      }
      for (const [key, child] of Object.entries(record)) check(child, `${path}.${key}`, name);
    }
    for (const file of readdirSync(directory).filter(name => name.endsWith('.json'))) {
      check(JSON.parse(readFileSync(new URL(file, directory), 'utf8')), file);
    }
    expect(checked.length).toBeGreaterThan(100);
  });
});

describe('validateCatalogTexts', () => {
  it('names the file and path of bad texts', () => {
    const doc = { entries: [{ automation: { actions: [{ id: 'a', texts: { button: '{nope}' } }] } }] };
    expect(validateCatalogTexts('f.json', doc)).toEqual(['f.json entries[0].automation.actions[0].texts: texts.button uses unknown placeholder {nope}'.replace('texts: texts', 'texts: texts')]);
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

describe('per-field overrides', () => {
  it('formats option, applied and blocked texts', () => {
    const option = { label: 'Boost', texts: { option: 'Big {label}', applied: 'Used {source}' } };
    const s = { source: 'Card', origin: undefined, option } as unknown as Parameters<typeof rollOptionText>[0];
    expect(rollOptionText(s)).toBe('Big Boost');
    expect(rollOptionAppliedLabel(s)).toBe('Used Card');
    expect(rollOptionText({ ...s, option: { label: 'Boost' } } as typeof s)).toBe('Card - Boost');
    expect(optionRowParts(s)).toEqual({ name: 'Card', text: 'Big Boost' });
    expect(optionRowParts({ ...s, option: { label: 'Boost', texts: { name: 'Nm', option: 'Does it' } } } as typeof s)).toEqual({ name: 'Nm', text: 'Does it' });
    expect(blockedTitle({ label: 'X', texts: { blocked: 'Nope.' } } as ActionDef, 'No Hope', {})).toBe('Nope. No Hope');
    expect(blockedTitle({ label: 'X' } as ActionDef, 'No Hope', {})).toBe('No Hope');
  });
});
