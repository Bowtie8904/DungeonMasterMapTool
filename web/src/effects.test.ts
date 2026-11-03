import { describe, expect, it } from 'vitest';
import { effectSource } from './effects';
import { emptyCharacter } from './daggerheart/data';
import type { ActiveEffect } from './daggerheart/engine';

describe('effectSource', () => {
  const effect = (over: Partial<ActiveEffect> = {}): ActiveEffect => ({ id: 'e1', name: 'Fury', kind: 'buff', source: "Patron's Fury", ...over });

  it('returns the full feature that started the effect', () => {
    const c = emptyCharacter('A');
    c.features.push({ source: 'Subclass', name: "Patron's Fury", text: 'Full feature text.' });
    expect(effectSource(c, effect())).toEqual({ title: "Patron's Fury", text: 'Full feature text.' });
  });

  it('falls back to the effect text, then null', () => {
    const c = emptyCharacter('A');
    expect(effectSource(c, effect({ text: 'Own text' }))).toEqual({ title: 'Fury', text: 'Own text' });
    expect(effectSource(c, effect())).toBeNull();
  });
});
