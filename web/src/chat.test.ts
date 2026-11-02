import { describe, expect, it } from 'vitest';
import { rollDetails } from './chat';
import type { RollMessage } from './protocol';

const message: RollMessage = {
  id: 1, ts: 0, fromId: 'player', from: 'Player', kind: 'duality',
  expression: '2d12-1d6+3', rolls: [9, 4, -2], total: 14,
};

describe('roll breakdowns', () => {
  it('uses the host-resolved sources without recomputing from a changed sheet', () => {
    const breakdown = [
      { label: 'Agility', expression: '2', rolls: [], total: 2 },
      { label: 'Control Potion', expression: '1', rolls: [], total: 1 },
    ];
    expect(rollDetails({ ...message, breakdown })).toBe(breakdown);
  });

  it('explains legacy duality dice, disadvantage and unnamed modifiers', () => {
    const details = rollDetails(message);
    expect(details.map(d => [d.label, d.total])).toEqual([
      ['Hope die', 9], ['Fear die', 4], ['Disadvantage', -2], ['Modifier', 3],
    ]);
    expect(details.reduce((sum, d) => sum + d.total, 0)).toBe(message.total);
  });

  it('explains ordinary mixed dice, penalties and zero bonuses', () => {
    const details = rollDetails({ ...message, kind: undefined, expression: '2d6+1d4-3+0', rolls: [3, 5, 2], total: 7 });
    expect(details.map(d => d.total)).toEqual([8, 2, -3, 0]);
    expect(details.flatMap(d => d.rolls)).toEqual([3, 5, 2]);
  });
});
