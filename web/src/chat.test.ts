import { describe, expect, it, vi } from 'vitest';
import { keptRolls, renderLogEntry, rollDetails } from './chat';
import type { ChatMessage, RollMessage } from './protocol';

const message: RollMessage = {
  id: 1, ts: 0, fromId: 'player', from: 'Player', kind: 'duality',
  expression: '2d12-1d6+3', rolls: [9, 4, -2], total: 14,
};

describe('roll breakdowns', () => {
  it('previews the row feature rather than the main weapon on hover and focus, with Escape dismissal', () => {
    class Element {
      id = '';
      role = '';
      className = '';
      tabIndex = -1;
      children: Array<Element | string> = [];
      attrs = new Map<string, string>();
      listeners = new Map<string, (event?: { key: string }) => void>();
      style: Record<string, string> = {};
      classList = {
        add: (name: string) => { this.className += ` ${name}`; },
        remove: (name: string) => { this.className = this.className.split(' ').filter(value => value !== name).join(' '); },
      };
      append(...children: Array<Element | string>) { this.children.push(...children); }
      setAttribute(key: string, value: string) { this.attrs.set(key, value); }
      addEventListener(type: string, handler: (event?: { key: string }) => void) { this.listeners.set(type, handler); }
      getBoundingClientRect() { return { left: 100, top: 650, bottom: 680, height: 200 }; }
      get textContent(): string { return this.children.map(child => typeof child === 'string' ? child : child.textContent).join(''); }
      find(cls: string): Element | undefined {
        return this.className.split(' ').includes(cls) ? this : this.children.flatMap(child => typeof child === 'string' ? [] : [child])
          .map(child => child.find(cls)).find(Boolean);
      }
    }
    vi.stubGlobal('document', { createElement: () => new Element() });
    vi.stubGlobal('window', { innerWidth: 800, innerHeight: 700 });
    try {
      const card = renderLogEntry({ kind: 'roll', m: {
        ...message, kind: 'damage', expression: '1d8+5', rolls: [3, 4, 1], total: 8,
        rollSource: { name: 'Sword', text: 'Weapon rules', source: 'Weapon' },
        breakdown: [{ label: 'Combo Strike rolls', expression: '2d4', rolls: [4, 1], total: 5,
          rollSource: { name: 'Combo Strike', text: '**Combo rules**', source: 'Brawler' } }],
      } }, 'player') as unknown as Element;
      const row = card.find('has-source-preview')!;
      const panel = row.find('roll-source-preview')!;
      expect(panel.textContent).toBe('BrawlerCombo StrikeCombo rules');
      expect(row.tabIndex).toBe(0);
      expect(row.attrs.get('aria-describedby')).toBe(panel.id);
      expect(panel.role).toBe('tooltip');
      row.listeners.get('mouseenter')!();
      expect(panel.style.top).toBe('446px');
      row.listeners.get('keydown')!({ key: 'Escape' });
      expect(row.className).toContain('source-preview-dismissed');
      row.listeners.get('focus')!();
      expect(row.className).not.toContain('source-preview-dismissed');
    } finally { vi.unstubAllGlobals(); }
  });
  it.each([
    ['long', true, false],
    ['short', true, false],
    ['short', false, true],
    ['short', true, true],
    ['long', false, false],
  ] as const)('renders %s rest actions with rewards=%s and recovery dice=%s', (kind, reward, dice) => {
    class Element {
      className = '';
      children: Array<Element | string> = [];
      append(...children: Array<Element | string>) { this.children.push(...children); }
      setAttribute() {}
    }
    vi.stubGlobal('document', { createElement: () => new Element() });
    try {
      const m: ChatMessage = {
        id: 2, ts: 0, fromId: 'player', from: 'Player', actorId: 'forager',
        text: `${kind.toUpperCase()} REST\nForager completed downtime.\nForager: 1d6 = 6.`,
        ...(reward ? { actionBenefits: {
          options: [{ id: 'foraged-rune', label: 'Choose Arcane Rune', sourceOnly: true, exclusiveClaim: true,
            steps: [{ op: 'inventory', itemId: 'foraged-rune' }] }],
          actorIds: ['forager'], claimedBy: [], sessionId: 'session', allowSourceOwner: true,
        } } : {}),
        ...(dice ? { restDice: [{ line: 2, expression: '1d6', value: 6 }] } : {}),
      };
      const actions = document.createElement('div');
      const renderActions = vi.fn(() => actions);
      const card = renderLogEntry({ kind: 'chat', m }, 'player', undefined, undefined, renderActions) as unknown as Element;
      expect(card.className).toContain('rest-report');
      expect(renderActions).toHaveBeenCalledTimes(reward || dice ? 1 : 0);
      if (reward || dice) {
        expect(renderActions).toHaveBeenCalledWith(m);
        expect(card.children.filter(child => child === (actions as unknown as Element))).toHaveLength(1);
      }
    } finally { vi.unstubAllGlobals(); }
  });

  it('shows sender portraits for chat and rolls and falls back when artwork fails', () => {
    class Element {
      className = '';
      src = '';
      children: Array<Element | string> = [];
      listeners = new Map<string, () => void>();
      removed = false;
      append(...children: Array<Element | string>) { this.children.push(...children); }
      setAttribute() {}
      addEventListener(type: string, handler: () => void) { this.listeners.set(type, handler); }
      remove() { this.removed = true; }
      find(cls: string): Element | undefined {
        return this.className === cls ? this : this.children.flatMap(child => typeof child === 'string' ? [] : [child])
          .map(child => child.find(cls)).find(Boolean);
      }
    }
    vi.stubGlobal('document', { createElement: () => new Element() });
    try {
      for (const entry of [
        { kind: 'chat' as const, m: { id: 1, ts: 0, fromId: 'player', from: 'Mara', text: 'Hello', fromArtwork: '/a/portrait.img' } },
        { kind: 'roll' as const, m: { ...message, fromArtwork: '/a/portrait.img' } },
      ]) {
        const avatar = (renderLogEntry(entry, 'player') as unknown as Element).find('message-avatar')!;
        expect(avatar.children).toHaveLength(1);
        const portrait = avatar.children[0] as Element;
        expect(portrait.src).toBe('/a/portrait.img');
        portrait.listeners.get('error')!();
        expect(portrait.removed).toBe(true);
        expect(avatar.children[1]).toBe(entry.m.from[0]);
      }
      const legacy = renderLogEntry({ kind: 'roll', m: message }, 'player') as unknown as Element;
      expect(legacy.find('message-avatar')!.children).toEqual(['P']);
      const dm = renderLogEntry({ kind: 'roll', m: { ...message, fromId: 'dm', fromArtwork: '/a/portrait.img' } }, 'player') as unknown as Element;
      expect(dm.find('message-avatar')!.children).toEqual(['G']);
    } finally { vi.unstubAllGlobals(); }
  });
  it('explains doubled damage without treating the multiplier as another flat bonus', () => {
    const details = rollDetails({ ...message, kind: undefined, expression: '(3d8kh2+2+2d4+24)*2', rolls: [1, 8, 4, 2, 3], total: 86 });
    expect(details.map((d) => d.total)).toEqual([24, 4, 10, 48]);
    expect(details.flatMap((d) => d.rolls)).toEqual([1, 8, 4, 2, 3]);
    expect(details.map((d) => d.expression)).toEqual(['(3d8kh2)*2', '(+2)*2', '(+2d4)*2', '(+24)*2']);
    expect(keptRolls('(3d8kh2+2+2d4+24)*2', [1, 8, 4, 2, 3])).toEqual([false, true, true, true, true]);
  });

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
      const reaction = renderLogEntry({ kind: 'roll', m: {
        ...message, label: 'Agility', source: 'trait:Agility,reaction',
      } }, 'player') as unknown as Element;
      expect(reaction.find('roll-title')?.text).toBe('Agility - Reaction roll');
      const death = renderLogEntry({ kind: 'roll', m: {
        ...message, kind: 'dice', deathMove: 'risk', label: 'Aria - Risk It All',
        expression: '2d12', rolls: [9, 4], total: 13,
        breakdown: [{ label: 'Hope die', expression: '1d12', rolls: [9], total: 9 },
          { label: 'Fear die', expression: '1d12', rolls: [4], total: 4 }],
        featureRoll: { name: 'Risk It All', result: 13, consequence: 'Clear 9 HP and/or Stress.' },
      } }, 'player') as unknown as Element;
      expect(death.find('roll-dice')?.text).toBe('Hope9Fear4');
      expect(death.find('roll-feature-result')?.text).toBe('Clear 9 HP and/or Stress.');
      const adjusted = renderLogEntry({ kind: 'roll', m: {
        ...message, kind: 'dice', deathMove: 'risk', expression: '2d12', rolls: [4, 5], total: 9,
        deathResult: { outcome: 'hope', hope: 4, fear: 5, adjustedHope: 5, bonus: 1, budget: 5, claimed: false, undying: false },
      } }, 'player') as unknown as Element;
      expect(adjusted.find('roll-dice')?.text).toBe('Hope4Fear5');
      expect(adjusted.find('roll-outcome hope')?.text).toBe('With Hope');
      expect(adjusted.text).toContain('Adjusted Hope 5 (+1 bonus)');
      const rawCrit = renderLogEntry({ kind: 'roll', m: {
        ...message, kind: 'dice', deathMove: 'risk', expression: '2d12', rolls: [5, 5], total: 10,
        deathResult: { outcome: 'crit', hope: 5, fear: 5, adjustedHope: 7, bonus: 2, budget: 7, claimed: false, undying: false },
      } }, 'player') as unknown as Element;
      expect(rawCrit.find('roll-dice')?.text).toBe('Hope5Fear5');
      expect(rawCrit.find('roll-outcome crit')?.text).toBe('Critical success');
      expect(rawCrit.className).toContain('critical-roll');
      expect(rawCrit.find('roll-die hope')).toBeDefined();
      expect(rawCrit.find('roll-die fear')).toBeDefined();
      expect(adjusted.className).not.toContain('critical-roll');
      const dualityCrit = renderLogEntry({ kind: 'roll', m: {
        ...message, kind: 'duality', expression: '2d12', rolls: [6, 6], total: 12,
      } }, 'player') as unknown as Element;
      expect(dualityCrit.className).toContain('critical-roll');
      const dm = renderLogEntry({ kind: 'roll', m: { ...message, fromId: 'dm', from: 'DM' } }, 'player') as unknown as Element;
      expect(dm.find('message-sender')?.text).toBe('Gamemaster');
      expect(dm.find('message-avatar')?.text).toBe('G');
      const scene = renderLogEntry({ kind: 'chat', m: {
        id: 2, ts: 0, fromId: 'dm', from: 'DM', system: 'sceneEnd',
        text: 'SCENE ENDED\nScene limits reset.\n• Aria: 1 use refreshed; ended Fury',
      } }, 'player') as unknown as Element;
      expect(scene.className).toContain('system-report');
      expect(scene.find('system-banner')?.text).toBe('Scene ended');
      expect(scene.find('rest-moves')?.text).toBe('Aria: 1 use refreshed; ended Fury');
      const forged = renderLogEntry({ kind: 'chat', m: { id: 3, ts: 0, fromId: 'player', from: 'P', system: 'newSession', text: 'NEW SESSION' } }, 'player') as unknown as Element;
      expect(forged.find('system-banner')).toBeUndefined();
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
