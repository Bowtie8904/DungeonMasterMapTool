import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { emptyCharacter, multiclassesOf } from './data';
import { addToSheet } from './compendium';
import { resourceDefinitions, runAction } from './engine';
import { loadCharacter } from './persist';
import { classEntries, RulesData, subclassCard, subclassesOf } from './rules';
import { applyTestingBuild, defaultTestingBuild, testingBuildProblems } from './testing';

const load = (name: string) => JSON.parse(readFileSync(resolve(__dirname, '../../../dmmap-daggerheart', `${name}.json`), 'utf8'));
const data: RulesData = {
  classes: load('classes').entries, ancestries: load('ancestries').entries, communities: load('communities').entries,
  weapons: load('weapons').entries, armor: load('armor').entries, consumables: load('consumables').entries,
  cards: load('domain-cards').entries, transformations: load('transformations').entries,
};
const catalogIndex = { categories: ['classes', 'ancestries', 'communities', 'weapons', 'armor', 'consumables', 'domain-cards', 'transformations']
  .map(category => ({ category })) };
const tester = () => Object.assign(emptyCharacter('Test hero'), { testingCharacter: true });

describe('DM-marked testing characters', () => {
  it('accepts unrestricted integer traits and preserves them across rebuilds and reloads', async () => {
    const c = tester();
    const b = defaultTestingBuild(data);
    const traits = Object.fromEntries(c.traits.map((t, i) => [t.name, [-12, 0, 15, 15, 42, -3][i]]));
    applyTestingBuild(data, c, b, traits);
    expect(Object.fromEntries(c.traits.map(t => [t.name, t.mod]))).toEqual(traits);
    const next = classEntries(data).find(cls => cls.name !== b.className)!;
    applyTestingBuild(data, c, { ...b, className: next.name, subclass: subclassesOf(data, next.name)[0] });
    expect(Object.fromEntries(c.traits.map(t => [t.name, t.mod]))).toEqual(traits);
    expect(c.traits.filter(t => t.spellcast).map(t => t.name)).toEqual([subclassCard(data, c.testingBuild!.subclass, 'foundation')!.spellcastTrait]);
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
      ok: true, json: async () => url.startsWith('/api/sheet/') ? { sheet: c, testingCharacter: true }
        : url === '/api/compendium' ? catalogIndex : load(url.slice('/api/compendium/'.length)),
    })));
    try {
      const loaded = await loadCharacter('a', 'Test hero');
      expect(Object.fromEntries(loaded.traits.map(t => [t.name, t.mod]))).toEqual(traits);
    } finally { vi.unstubAllGlobals(); }
  });

  it('rejects invalid trait values before changing the sheet', () => {
    const c = tester();
    const before = structuredClone(c);
    const traits = Object.fromEntries(c.traits.map(t => [t.name, 1]));
    for (const value of [NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => applyTestingBuild(data, c, defaultTestingBuild(data), { ...traits, Agility: value })).toThrow('whole number');
      expect(c).toEqual(before);
    }
  });

  it('rejects ordinary sheets without mutating them', () => {
    const c = emptyCharacter('Normal');
    const before = structuredClone(c);
    expect(() => applyTestingBuild(data, c, defaultTestingBuild(data))).toThrow('DM-marked');
    expect(c).toEqual(before);
  });

  it('switches every class and activates every accessible card, including level 10 cards', () => {
    const c = tester();
    for (const cls of classEntries(data)) {
      const b = { ...defaultTestingBuild(data), className: cls.name, subclass: subclassesOf(data, cls.name)[0] };
      applyTestingBuild(data, c, b);
      expect(c.className).toBe(cls.name);
      expect(c.features.some((f) => f.source.includes('Subclass mastery'))).toBe(true);
      expect(c.loadout.map((card) => card.name).sort()).toEqual(data.cards.filter((card) => c.domains.includes(card.domain!)).map((card) => card.name).sort());
      expect(c.loadout.length).toBeGreaterThan(5);
      expect(c.loadout.some((card) => card.level === 10)).toBe(true);
      expect(c.vault).toEqual([]);
      expect(c.testingCharacter).toBe(true);
    }
  });

  it('allows several multiclasses at level 1, replacing obsolete domains and features', () => {
    const c = tester();
    const b = defaultTestingBuild(data);
    b.level = 1;
    b.multiclasses = classEntries(data).filter((cls) => cls.name !== b.className).slice(0, 3).map((cls) => ({
      className: cls.name, subclass: subclassesOf(data, cls.name)[0], stage: 'mastery', domain: cls.domains![0],
    }));
    applyTestingBuild(data, c, b);
    expect(multiclassesOf(c)).toHaveLength(3);
    for (const m of b.multiclasses) {
      expect(c.domains).toContain(m.domain);
      expect(c.features.some((f) => f.source.includes(`Multiclass ${m.className}`))).toBe(true);
    }
    b.multiclasses = [];
    applyTestingBuild(data, c, b);
    expect(multiclassesOf(c)).toEqual([]);
    expect(c.features.some((f) => f.source.startsWith('Multiclass'))).toBe(false);
    expect(c.domains).toEqual(expect.arrayContaining(classEntries(data).find((cls) => cls.name === b.className)!.domains!));
    const duplicate = { ...b, multiclasses: [{ ...b, domain: c.domains[0] }] };
    expect(testingBuildProblems(data, duplicate)).toContain('Choose each class only once.');
  });

  it('can persist a build containing every class within the host sheet size limit', () => {
    const c = tester();
    const b = defaultTestingBuild(data);
    b.multiclasses = classEntries(data).filter((cls) => cls.name !== b.className).map((cls) => ({
      className: cls.name, subclass: subclassesOf(data, cls.name)[0], stage: 'mastery', domain: cls.domains![0],
    }));
    applyTestingBuild(data, c, b);
    expect(multiclassesOf(c)).toHaveLength(classEntries(data).length - 1);
    expect(Buffer.byteLength(JSON.stringify(c), 'utf8')).toBeLessThan(512 * 1024);
    expect(c.loadout.length).toBeGreaterThan(5);
  });

  it('rebuilds mixed heritage, transformation, subclass stage and spellcast without losing personal data', () => {
    const c = tester();
    const b = defaultTestingBuild(data);
    applyTestingBuild(data, c, b);
    c.notes = 'Keep these notes';
    const equipment = structuredClone(c.activeWeapons);
    b.className = 'Ranger';
    b.subclass = 'Ranger: Wayfinder';
    b.stage = 'foundation';
    b.ancestry = 'Human';
    b.ancestry2 = 'Giant';
    b.community = data.communities[1].name;
    b.transformation = data.transformations[0].name;
    applyTestingBuild(data, c, b);
    expect(c.ancestry).toBe('Human / Giant');
    expect(c.heritage.some((f) => f.source === 'Ancestry - Human (first)')).toBe(true);
    expect(c.heritage.some((f) => f.source === 'Ancestry - Giant (second)')).toBe(true);
    expect(c.heritage.some((f) => f.source === `Community - ${b.community}`)).toBe(true);
    expect(c.transformation).toBe(b.transformation);
    expect(c.features.some((f) => /Subclass (specialization|mastery)/.test(f.source))).toBe(false);
    expect(c.traits.filter((t) => t.spellcast).map((t) => t.name)).toEqual([subclassCard(data, b.subclass, 'foundation')!.spellcastTrait]);
    expect(c.notes).toBe('Keep these notes');
    expect(c.activeWeapons).toEqual(equipment);
    expect(c.vault).toEqual([]);
  });

  it('initializes new resource pools and removes obsolete pools, usage and source effects', () => {
    const d = structuredClone(data);
    const cls = classEntries(d)[0];
    cls.features = [{ name: 'Test pool', text: '', automation: {
      resources: [{ id: 'testingPool', label: 'Test pool', initial: 7, max: 10 }],
      actions: [{ id: 'spend', label: 'Spend', cost: { testingPool: 1 } }],
    } }];
    const c = tester();
    const b = defaultTestingBuild(d);
    applyTestingBuild(d, c, b);
    expect(c.resources?.testingPool).toBe(7);
    runAction(c, 'feat:Test pool', c.features.find((f) => f.name === 'Test pool')!.automation!, { id: 'spend', label: 'Spend', cost: { testingPool: 1 } }, 'Test pool');
    applyTestingBuild(d, c, b);
    expect(c.resources?.testingPool).toBe(6);
    c.effects.push({ id: 'old', name: 'Old pool effect', kind: 'buff', source: 'Test pool - Spend' });
    c.effects.push({ id: 'locked', name: 'Volatile Toxins', kind: 'debuff', source: 'Test pool', lockedHp: 1, until: 'rest' });
    c.usage['feat:Test pool:action:spend'] = { used: 1, per: 'session' };
    const next = classEntries(d)[1];
    applyTestingBuild(d, c, { ...b, className: next.name, subclass: subclassesOf(d, next.name)[0] });
    expect(resourceDefinitions(c).some((r) => r.id === 'testingPool')).toBe(false);
    expect(c.resources?.testingPool).toBeUndefined();
    expect(c.usage['feat:Test pool:action:spend']).toBeUndefined();
    expect(c.effects.some((e) => e.id === 'old')).toBe(false);
    expect(c.effects.some((e) => e.id === 'locked')).toBe(true);
    expect(c.hp.on).toBeGreaterThanOrEqual(1);
  });

  it('uses the host marker on reload and refreshes automation, not a forged sheet marker', async () => {
    const c = tester();
    applyTestingBuild(data, c, defaultTestingBuild(data));
    const fetch = vi.fn(async (url: string) => {
      if (url.startsWith('/api/sheet/')) return { ok: true, json: async () => ({ sheet: c, testingCharacter: true }) };
      if (url === '/api/compendium') return { ok: true, json: async () => catalogIndex };
      return { ok: true, json: async () => load(url.slice('/api/compendium/'.length)) };
    });
    vi.stubGlobal('fetch', fetch);
    try {
      const loaded = await loadCharacter('a', 'Test hero');
      expect(loaded.testingCharacter).toBe(true);
      expect(loaded.testingBuild).toEqual(c.testingBuild);
      expect(loaded.loadout).toEqual(c.loadout);
      fetch.mockImplementation(async (url: string) => {
        if (url.startsWith('/api/sheet/')) return { ok: true, json: async () => ({ sheet: c, testingCharacter: false }) };
        if (url === '/api/compendium') return { ok: true, json: async () => ({ categories: [] }) };
        throw new Error('Unexpected request');
      });
      const normal = await loadCharacter('a', 'Test hero');
      expect(normal.testingCharacter).toBe(false);
      expect(normal.testingBuild).toBeUndefined();
    } finally { vi.unstubAllGlobals(); }
  });

  it('keeps test compendium cards active without duplicates while normal cards go to the vault', () => {
    const c = tester();
    applyTestingBuild(data, c, defaultTestingBuild(data));
    const card = data.cards.find((entry) => c.domains.includes(entry.domain!))!;
    const count = c.loadout.length;
    expect(addToSheet(c, 'domain-cards', card)).toContain('always active');
    expect(c.loadout).toHaveLength(count);
    expect(c.vault).toEqual([]);
    const normal = emptyCharacter('Normal');
    addToSheet(normal, 'domain-cards', card);
    expect(normal.loadout).toEqual([]);
    expect(normal.vault).toHaveLength(1);
  });
});
