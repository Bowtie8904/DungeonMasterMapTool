import { describe, expect, it, vi } from 'vitest';
import { keptRolls, renderLogEntry, rollDetails } from './chat';
import type { RollMessage } from './protocol';

const message: RollMessage = {
  id: 1, ts: 0, fromId: 'player', from: 'Player', kind: 'duality',
  expression: '2d12-1d6+3', rolls: [9, 4, -2], total: 14,
};

describe('roll breakdowns', () => {
  it('shows only the feature consequence below the dice, without repeating its name or result', () => {
    class Element {
      className = '';
      children: Array<Element | string> = [];
      append(...children: Array<Element | string>) { this.children.push(...children); }
      setAttribute() {}
      get text(): string { return this.children.map(child => typeof child === 'string' ? child : child.text).join(''); }
      find(cls: string): Element | undefined {
        return this.className === cls ? this : this.children.flatMap(child => typeof child === 'string' ? [] : [child])
          .map(child => child.find(cls)).find(Boolean);
      }
    }
    vi.stubGlobal('document', { createElement: () => new Element() });
    try {
      for (const [result, consequence] of [[1, 'Marked 1 Stress.'], [6, 'No Stress marked.']] as const) {
        const card = renderLogEntry({ kind: 'roll', m: {
          ...message, kind: 'dice', label: 'Unshakable', expression: '1d6', rolls: [result], total: result,
          featureRoll: { name: 'Unshakable', result, consequence },
        } }, 'player') as unknown as Element;
        const line = card.find('roll-feature-result')!;
        expect(line.text).toBe(consequence);
        expect(line.children).toEqual([consequence]);
        expect(card.find('roll-title')?.text).toBe('Unshakable');
      }
    } finally { vi.unstubAllGlobals(); }
  });

  it('shows discarded dice without counting them, including ties and signed mixed pools', () => {
    expect(keptRolls('4d6kh3+1d8-3d4kh2+6', [2, 6, 2, 4, 8, -1, -3, -4])).toEqual([true, true, false, true, true, false, true, true]);
    const details = rollDetails({ ...message, kind: undefined, expression: '4d6kh3+6', rolls: [2, 6, 2, 4], total: 18 });
    expect(details.map((d) => d.total)).toEqual([12, 6]);
    expect(details.flatMap((d) => d.rolls)).toEqual([2, 6, 2, 4]);
  });
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
