import { describe, expect, it } from 'vitest';
import { CATALOG_LABELS, CatalogShapes, cloneValue, entryTemplate, entryFieldTab, fieldHelp, ruleSection, ruleSummary, STEP_TEMPLATES } from './catalog-fields';

describe('guided catalog forms', () => {
  it('organizes entry fields by task while retaining uncommon fields in Advanced', () => {
    for (const key of ['id', 'name', 'weapon', 'armor', 'startingHp', 'recall']) expect(entryFieldTab(key)).toBe('Overview');
    for (const key of ['feature', 'features', 'spells']) expect(entryFieldTab(key)).toBe('Features');
    expect(entryFieldTab('automation')).toBe('Automation');
    expect(entryFieldTab('customExtension')).toBe('Advanced');
  });
  it('explains rule groups and produces readable operation cards without altering their data', () => {
    expect(ruleSection('timing')).toBe('When / activation');
    expect(ruleSection('requiresEffect')).toBe('Conditions');
    expect(ruleSection('cost')).toBe('Costs & limits');
    expect(ruleSection('steps')).toBe('Effects / results');
    expect(ruleSummary({ op: 'gain', resource: 'hope', amount: 2 })).toBe('Gain 2 hope');
    expect(ruleSummary({ label: 'Heal', cost: { hope: 1 } })).toBe('Heal');
    expect(ruleSummary([])).toBe('0 items');
    expect(ruleSummary(null)).toBe('(not set)');
  });
  it('offers concrete effect choices as independent typed operations', () => {
    const heal = STEP_TEMPLATES.find(template => template.label === 'Restore HP');
    expect(heal?.value).toEqual({ op: 'clear', resource: 'hp', amount: 1 });
    const modifier = STEP_TEMPLATES.find(template => template.label === 'Apply a modifier effect');
    expect(modifier?.value).toMatchObject({ op: 'effect', effect: { until: 'rest', automation: { modifiers: [{ stat: 'evasion', value: 1 }] } } });
    const copy = cloneValue(heal!.value);
    copy.amount = 3;
    expect(heal?.value.amount).toBe(1);
  });
  it('provides independent creation templates and helpful descriptions for every category', () => {
    for (const category of Object.keys(CATALOG_LABELS)) {
      const template = entryTemplate(category);
      expect(template).toMatchObject({ id: '', name: '' });
      template.name = 'Changed';
      expect(entryTemplate(category).name).toBe('');
    }
    expect(fieldHelp('startingHp')).toContain('new characters only');
    expect(fieldHelp('uses')).toContain('independent');
    expect(fieldHelp('resources')).toContain('once');
  });
  it('learns nested optional fields and list templates from all automation owners', () => {
    const source = {
      category: 'classes', entries: [{ name: 'Example', features: [{
        name: 'Rule', text: '', automation: { actions: [{ id: 'grant', label: 'Grant', cost: { favor: 1 }, steps: [{ op: 'gain', resource: 'favor', amount: 2 }] }] },
      }] }],
    };
    const shapes = new CatalogShapes([source]);
    expect(shapes.fields('weapons.automation')).toHaveProperty('modifiers');
    expect(shapes.fields('weapons.automation.actions[]')).toHaveProperty('cost');
    expect(shapes.fields('ancestries.features[]')).toHaveProperty('automation');
    expect(shapes.samples('items.automation.actions').some(sample => typeof sample === 'object' && sample !== null && 'id' in sample && sample.id === 'grant')).toBe(true);
    const copy = cloneValue(source);
    copy.entries[0].name = 'Copy';
    expect(source.entries[0].name).toBe('Example');
  });
});
