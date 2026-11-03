import { describe, expect, it } from 'vitest';
import { formatActionReport, parseActionReport } from './action-summary';

describe('action report', () => {
  it('round-trips the chat action report', () => {
    const report = { title: 'Minor Health Potion - Drink', character: 'Jitta', lines: ['cleared 2 HP (1d4)'] };
    expect(parseActionReport(formatActionReport(report))).toEqual(report);
    expect(parseActionReport('Jitta: Potion - Drink: cleared 2 HP')).toBeNull();
  });
});
