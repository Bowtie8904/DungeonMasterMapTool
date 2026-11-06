import { describe, expect, it } from 'vitest';
import { effectSource, renderEffects } from './effects';
import { emptyCharacter } from './daggerheart/data';
import type { ActiveEffect } from './daggerheart/engine';

describe('effectSource', () => {
  it('does not render class-resource dice as individual effect rows', () => {
    const c = emptyCharacter('Seraph');
    c.effects = [{ id: 'pool-prayer-1', name: 'Prayer Dice 1', kind: 'buff', dicePool: 'prayer' }];
    const rendered: Array<Node | string> = [];
    const host = { replaceChildren: (...nodes: Array<Node | string>) => { rendered.push(...nodes); } } as HTMLElement;
    renderEffects(host, [{ actorId: 'seraph', name: 'Seraph', character: c }], new Set(), () => {}, () => {});
    expect(rendered).toEqual([]);
  });

  const effect = (over: Partial<ActiveEffect> = {}): ActiveEffect => ({ id: 'e1', name: 'Fury', kind: 'buff', source: "Patron's Fury", ...over });

  it('shows the action source and effect details', () => {
    const c = emptyCharacter('A');
    c.features.push({ source: 'Subclass', name: "Patron's Fury", text: 'Full feature text.' });
    expect(effectSource(c, effect({ text: 'Patron dice add to damage.' }))).toEqual({
      title: "From Patron's Fury",
      text: 'Patron dice add to damage.\n\nFull feature text.',
    });
  });

  it('falls back to the effect text, then null', () => {
    const c = emptyCharacter('A');
    expect(effectSource(c, effect({ source: 'Control Potion - Drink', text: 'Next Finesse Roll +1.' }))).toEqual({
      title: 'From Control Potion - Drink',
      text: 'Next Finesse Roll +1.',
    });
    expect(effectSource(c, effect({ source: undefined }))).toBeNull();
  });
});
