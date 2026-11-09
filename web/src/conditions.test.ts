import { describe, expect, it } from 'vitest';
import { conditionIconId, CUSTOM_CONDITION_ICON_IDS, DEFAULT_CUSTOM_CONDITION_ICON } from './conditions';

describe('condition icons', () => {
  it('uses the requested icons for the built-in conditions', () => {
    expect(conditionIconId('hidden')).toBe('cond_invisible');
    expect(conditionIconId('restrained')).toBe('cond_restrained');
    expect(conditionIconId('vulnerable')).toBe('cond_concentrating');
  });

  it('offers only non-reserved icons for custom conditions and defaults to bleeding', () => {
    expect(CUSTOM_CONDITION_ICON_IDS).toHaveLength(21);
    expect(CUSTOM_CONDITION_ICON_IDS).not.toContain('cond_invisible');
    expect(CUSTOM_CONDITION_ICON_IDS).not.toContain('cond_restrained');
    expect(CUSTOM_CONDITION_ICON_IDS).not.toContain('cond_concentrating');
    expect(conditionIconId('custom')).toBe(DEFAULT_CUSTOM_CONDITION_ICON);
    expect(conditionIconId('custom', 'cond_blessed')).toBe('cond_blessed');
    expect(conditionIconId('custom', 'cond_invisible')).toBe(DEFAULT_CUSTOM_CONDITION_ICON);
  });
});
